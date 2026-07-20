#include "orion_bridge_server.h"

#include <godot_cpp/classes/engine.hpp>
#include <godot_cpp/classes/file_access.hpp>
#include <godot_cpp/classes/image.hpp>
#include <godot_cpp/classes/json.hpp>
#include <godot_cpp/classes/node.hpp>
#include <godot_cpp/classes/performance.hpp>
#include <godot_cpp/classes/project_settings.hpp>
#include <godot_cpp/classes/scene_tree.hpp>
#include <godot_cpp/classes/texture2d.hpp>
#include <godot_cpp/classes/time.hpp>
#include <godot_cpp/classes/viewport.hpp>
#include <godot_cpp/classes/viewport_texture.hpp>
#include <godot_cpp/classes/window.hpp>
#include <godot_cpp/variant/array.hpp>
#include <godot_cpp/variant/callable_method_pointer.hpp>
#include <godot_cpp/variant/node_path.hpp>
#include <godot_cpp/variant/utility_functions.hpp>

OrionBridgeServer *OrionBridgeServer::singleton = nullptr;

OrionBridgeServer *OrionBridgeServer::get_singleton() {
	return singleton;
}

void OrionBridgeServer::_bind_methods() {
	// No script-facing methods yet — this is a control-plane socket, not a scripting API surface.
}

static Dictionary node_to_dict(Node *node) {
	Dictionary d;
	d["name"] = String(node->get_name());
	d["class"] = node->get_class();
	Array children;
	int count = node->get_child_count();
	for (int i = 0; i < count; i++) {
		children.append(node_to_dict(node->get_child(i)));
	}
	d["children"] = children;
	return d;
}

OrionBridgeServer::OrionBridgeServer() {
	singleton = this;

	server.instantiate();
	Error err = server->listen(PORT, "127.0.0.1");
	if (err != OK) {
		UtilityFunctions::push_error("OrionBridgeServer: failed to listen on 127.0.0.1:", PORT, " err=", err);
	} else {
		UtilityFunctions::print("OrionBridgeServer: listening on 127.0.0.1:", PORT);
	}

	auth_token = UtilityFunctions::str(Time::get_singleton()->get_ticks_usec()) +
			UtilityFunctions::str(UtilityFunctions::randi());
	_write_token_file();

	// MODULE_INITIALIZATION_LEVEL_SCENE fires during class registration, before Main::start()
	// actually creates the SceneTree — Engine::get_main_loop() is still null here (confirmed
	// on-device). Defer the connection; it re-defers itself until the SceneTree exists, which
	// happens within the first real frame once the engine's main loop starts.
	callable_mp(this, &OrionBridgeServer::_try_connect_to_scene_tree).call_deferred();
}

void OrionBridgeServer::_try_connect_to_scene_tree() {
	SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
	if (!tree) {
		callable_mp(this, &OrionBridgeServer::_try_connect_to_scene_tree).call_deferred();
		return;
	}
	Error connect_err = tree->connect("process_frame", callable_mp(this, &OrionBridgeServer::_on_process_frame));
	UtilityFunctions::print("OrionBridgeServer: connected to process_frame, err=", (int)connect_err);
}

OrionBridgeServer::~OrionBridgeServer() {
	if (server.is_valid()) {
		server->stop();
	}
	if (singleton == this) {
		singleton = nullptr;
	}
}

void OrionBridgeServer::_write_token_file() {
	Ref<FileAccess> f = FileAccess::open("user://orion_bridge_token.txt", FileAccess::WRITE);
	if (f.is_valid()) {
		f->store_string(auth_token);
		f->close();
	}
}

void OrionBridgeServer::_on_process_frame() {
	// _pump_client() first: it calls client->poll(), which is what actually updates a disconnected
	// client's status. Calling _accept_pending_connection() first (the original order) reads last
	// frame's now-stale CONNECTED status, so a new connection arriving in the same frame the
	// previous one closes gets wrongly dropped as "single client already active" — confirmed via a
	// real race: a from-scratch NDJSON test client reconnecting for consecutive requests hit this
	// almost immediately.
	_pump_client();
	_accept_pending_connection();

	if (step_frames_remaining > 0) {
		step_frames_remaining--;
		if (step_frames_remaining == 0) {
			SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
			if (tree) {
				tree->set_pause(true);
			}
		}
	}
}

Node *OrionBridgeServer::_resolve_node(const String &path) {
	SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
	if (!tree || !tree->get_root()) {
		return nullptr;
	}
	if (path.is_empty() || path == "/root") {
		return tree->get_root();
	}
	return tree->get_root()->get_node_or_null(NodePath(path));
}

void OrionBridgeServer::_accept_pending_connection() {
	if (!server.is_valid() || !server->is_connection_available()) {
		return;
	}
	if (client.is_valid() && client->get_status() == StreamPeerTCP::STATUS_CONNECTED) {
		// v1: single client at a time — drop new connection attempts while one is active.
		server->take_connection();
		return;
	}
	client = server->take_connection();
	line_buffer = "";
}

void OrionBridgeServer::_pump_client() {
	if (!client.is_valid()) {
		return;
	}
	client->poll();
	if (client->get_status() != StreamPeerTCP::STATUS_CONNECTED) {
		client.unref();
		return;
	}

	int available = client->get_available_bytes();
	for (int i = 0; i < available; i++) {
		int byte = client->get_8();
		if (byte == '\n') {
			_handle_line(line_buffer);
			line_buffer = "";
		} else if (byte >= 0) {
			line_buffer += String::chr(byte);
		}
	}
}

void OrionBridgeServer::_handle_line(const String &line) {
	if (line.strip_edges().is_empty() || !client.is_valid()) {
		return;
	}

	Variant parsed = JSON::parse_string(line);
	Dictionary response;
	if (parsed.get_type() != Variant::DICTIONARY) {
		response["ok"] = false;
		response["error"] = "invalid JSON request";
	} else {
		response = _dispatch(parsed);
	}

	String out = JSON::stringify(response) + "\n";
	Error werr = client->put_data(out.to_utf8_buffer());
	if (werr != OK) {
		UtilityFunctions::push_error("OrionBridgeServer: put_data failed, err=", (int)werr);
	}
}

Dictionary OrionBridgeServer::_dispatch(const Dictionary &request) {
	Dictionary response;
	String action = request.get("action", "");

	if (request.get("token", "") != auth_token) {
		response["ok"] = false;
		response["error"] = "invalid or missing token";
		return response;
	}

	if (action == "ping") {
		response["ok"] = true;
		response["pong"] = true;
	} else if (action == "capabilities") {
		Array actions;
		const char *names[] = { "ping", "capabilities", "scene_tree", "perf_snapshot", "screenshot",
			"get_property", "set_property", "call_method", "list_properties", "list_methods",
			"create_node", "delete_node", "set_paused", "step", "simulate_input", "reload_script",
			"classdb", "save_scene", "change_scene", "project_setting", "input_actions" };
		for (const char *name : names) actions.append(name);
		response["ok"] = true;
		response["protocol_version"] = 2;
		response["godot_version"] = Engine::get_singleton()->get_version_info();
		response["actions"] = actions;
	} else if (action == "scene_tree") {
		SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
		if (tree && tree->get_root()) {
			response["ok"] = true;
			response["tree"] = node_to_dict(tree->get_root());
		} else {
			response["ok"] = false;
			response["error"] = "no scene tree";
		}
	} else if (action == "perf_snapshot") {
		Performance *perf = Performance::get_singleton();
		Dictionary snapshot;
		snapshot["fps"] = perf->get_monitor(Performance::TIME_FPS);
		snapshot["process_time_ms"] = perf->get_monitor(Performance::TIME_PROCESS) * 1000.0;
		snapshot["physics_process_time_ms"] = perf->get_monitor(Performance::TIME_PHYSICS_PROCESS) * 1000.0;
		snapshot["static_memory_bytes"] = perf->get_monitor(Performance::MEMORY_STATIC);
		snapshot["object_count"] = perf->get_monitor(Performance::OBJECT_COUNT);
		snapshot["node_count"] = perf->get_monitor(Performance::OBJECT_NODE_COUNT);
		response["ok"] = true;
		response["perf"] = snapshot;
	} else if (action == "screenshot") {
		SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
		Window *root = tree ? tree->get_root() : nullptr;
		Ref<Texture2D> tex;
		if (root) {
			tex = root->get_texture();
		}
		Ref<Image> img = tex.is_valid() ? tex->get_image() : Ref<Image>();
		if (img.is_valid()) {
			String rel = "user://orion_bridge_screenshot.png";
			Error err = img->save_png(rel);
			if (err == OK) {
				response["ok"] = true;
				response["path"] = ProjectSettings::get_singleton()->globalize_path(rel);
			} else {
				response["ok"] = false;
				response["error"] = "save_png failed";
			}
		} else {
			response["ok"] = false;
			response["error"] = "no viewport texture available";
		}
	} else if (action == "get_property") {
		response = _op_get_property(request);
	} else if (action == "set_property") {
		response = _op_set_property(request);
	} else if (action == "call_method") {
		response = _op_call_method(request);
	} else if (action == "list_properties") {
		response = _op_list_properties(request);
	} else if (action == "list_methods") {
		response = _op_list_methods(request);
	} else if (action == "create_node") {
		response = _op_create_node(request);
	} else if (action == "delete_node") {
		response = _op_delete_node(request);
	} else if (action == "set_paused") {
		response = _op_set_paused(request);
	} else if (action == "step") {
		response = _op_step(request);
	} else if (action == "simulate_input") {
		response = _op_simulate_input(request);
	} else if (action == "reload_script") {
		response = _op_reload_script(request);
	} else if (action == "classdb") {
		response = _op_classdb(request);
	} else if (action == "save_scene") {
		response = _op_save_scene(request);
	} else if (action == "change_scene") {
		response = _op_change_scene(request);
	} else if (action == "project_setting") {
		response = _op_project_setting(request);
	} else if (action == "input_actions") {
		response = _op_input_actions(request);
	} else {
		response["ok"] = false;
		response["error"] = "unknown action '" + action + "'";
	}

	return response;
}
