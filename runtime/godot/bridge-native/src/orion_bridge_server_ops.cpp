// G4 mutating-op implementations for OrionBridgeServer. Split from orion_bridge_server.cpp purely
// for file size; picked up automatically by bridge-native/SConstruct's Glob("src/*.cpp").
//
// Every op resolves its target node via _resolve_node(path) against the live SceneTree and returns
// a plain {"ok": false, "error": "..."} Dictionary on any failure (missing node, wrong type, etc.) —
// callers (GodotController on the Kotlin side) surface these directly rather than throwing, since a
// failed op here is an expected/handleable outcome for an AI agent probing the scene, not a bug.

#include "orion_bridge_server.h"

#include <godot_cpp/classes/class_db_singleton.hpp>
#include <godot_cpp/classes/engine.hpp>
#include <godot_cpp/classes/input.hpp>
#include <godot_cpp/classes/input_map.hpp>
#include <godot_cpp/classes/input_event_key.hpp>
#include <godot_cpp/classes/input_event_mouse_button.hpp>
#include <godot_cpp/classes/input_event_mouse_motion.hpp>
#include <godot_cpp/classes/node.hpp>
#include <godot_cpp/classes/packed_scene.hpp>
#include <godot_cpp/classes/project_settings.hpp>
#include <godot_cpp/classes/ref_counted.hpp>
#include <godot_cpp/classes/resource_loader.hpp>
#include <godot_cpp/classes/resource_saver.hpp>
#include <godot_cpp/classes/scene_tree.hpp>
#include <godot_cpp/classes/script.hpp>
#include <godot_cpp/classes/window.hpp>
#include <godot_cpp/variant/array.hpp>
#include <godot_cpp/variant/color.hpp>
#include <godot_cpp/variant/node_path.hpp>
#include <godot_cpp/variant/quaternion.hpp>
#include <godot_cpp/variant/rect2.hpp>
#include <godot_cpp/variant/utility_functions.hpp>
#include <godot_cpp/variant/vector2.hpp>
#include <godot_cpp/variant/vector3.hpp>
#include <godot_cpp/variant/vector4.hpp>

// JSON has no vector/color type — a client-sent [x, y, z] arrives here as a Godot `Array`, and
// Object::set() does NOT auto-coerce Array -> Vector3/Color/etc the way it coerces int -> float.
// Confirmed on-device: setting Node3D's "position" (a Vector3 property) with a JSON array silently
// no-ops (Godot's generic setter just declines a type it can't convert, no error surfaced). Look up
// the property's declared type from get_property_list() and convert a numeric JSON array into the
// matching native type before handing it to set() — covers the common "move/resize/recolor a node"
// case; arbitrary method arguments (call_method) don't get this treatment, since that would need
// per-parameter type introspection of the target method, not just the one property being set.
static Variant coerce_for_property(Node *node, const String &property_name, const Variant &value) {
	if (value.get_type() != Variant::ARRAY) {
		return value;
	}
	Array arr = value;
	TypedArray<Dictionary> props = node->get_property_list();
	for (int i = 0; i < props.size(); i++) {
		Dictionary p = props[i];
		if (String(p.get("name", "")) != property_name) {
			continue;
		}
		Variant::Type target = (Variant::Type)(int)p.get("type", (int)Variant::NIL);
		switch (target) {
			case Variant::VECTOR2:
				if (arr.size() >= 2) {
					return Vector2((float)(double)arr[0], (float)(double)arr[1]);
				}
				break;
			case Variant::VECTOR3:
				if (arr.size() >= 3) {
					return Vector3((float)(double)arr[0], (float)(double)arr[1], (float)(double)arr[2]);
				}
				break;
			case Variant::VECTOR4:
				if (arr.size() >= 4) {
					return Vector4((float)(double)arr[0], (float)(double)arr[1], (float)(double)arr[2], (float)(double)arr[3]);
				}
				break;
			case Variant::QUATERNION:
				if (arr.size() >= 4) {
					return Quaternion((float)(double)arr[0], (float)(double)arr[1], (float)(double)arr[2], (float)(double)arr[3]);
				}
				break;
			case Variant::COLOR:
				if (arr.size() >= 3) {
					float a = arr.size() >= 4 ? (float)(double)arr[3] : 1.0f;
					return Color((float)(double)arr[0], (float)(double)arr[1], (float)(double)arr[2], a);
				}
				break;
			case Variant::RECT2:
				if (arr.size() >= 4) {
					return Rect2((float)(double)arr[0], (float)(double)arr[1], (float)(double)arr[2], (float)(double)arr[3]);
				}
				break;
			default:
				break;
		}
		break;
	}
	return value;
}

Dictionary OrionBridgeServer::_op_get_property(const Dictionary &request) {
	Dictionary response;
	Node *node = _resolve_node(request.get("path", ""));
	if (!node) {
		response["ok"] = false;
		response["error"] = "node not found";
		return response;
	}
	response["ok"] = true;
	response["value"] = node->get(StringName(String(request.get("property", ""))));
	return response;
}

Dictionary OrionBridgeServer::_op_set_property(const Dictionary &request) {
	Dictionary response;
	Node *node = _resolve_node(request.get("path", ""));
	if (!node) {
		response["ok"] = false;
		response["error"] = "node not found";
		return response;
	}
	String property_name = request.get("property", "");
	Variant value = coerce_for_property(node, property_name, request.get("value", Variant()));
	node->set(StringName(property_name), value);
	response["ok"] = true;
	return response;
}

Dictionary OrionBridgeServer::_op_call_method(const Dictionary &request) {
	Dictionary response;
	Node *node = _resolve_node(request.get("path", ""));
	if (!node) {
		response["ok"] = false;
		response["error"] = "node not found";
		return response;
	}
	Array args = request.get("args", Array());
	response["ok"] = true;
	response["result"] = node->callv(StringName(String(request.get("method", ""))), args);
	return response;
}

Dictionary OrionBridgeServer::_op_list_properties(const Dictionary &request) {
	Dictionary response;
	Node *node = _resolve_node(request.get("path", ""));
	if (!node) {
		response["ok"] = false;
		response["error"] = "node not found";
		return response;
	}
	// Raw pass-through of get_property_list()'s own Dictionary shape (name/type/usage/hint/
	// hint_string, same fields GDScript's Object.get_property_list() exposes) plus the current
	// value per property — maximal introspection detail for an AI agent deciding what to touch,
	// not a curated subset.
	TypedArray<Dictionary> props = node->get_property_list();
	Array out;
	for (int i = 0; i < props.size(); i++) {
		Dictionary p = props[i];
		String pname = p.get("name", "");
		if (pname.is_empty()) {
			continue;
		}
		Dictionary entry = p.duplicate();
		entry["value"] = node->get(StringName(pname));
		out.append(entry);
	}
	response["ok"] = true;
	response["properties"] = out;
	return response;
}

Dictionary OrionBridgeServer::_op_list_methods(const Dictionary &request) {
	Dictionary response;
	Node *node = _resolve_node(request.get("path", ""));
	if (!node) {
		response["ok"] = false;
		response["error"] = "node not found";
		return response;
	}
	response["ok"] = true;
	response["methods"] = node->get_method_list();
	return response;
}

Dictionary OrionBridgeServer::_op_create_node(const Dictionary &request) {
	Dictionary response;
	Node *parent = _resolve_node(request.get("parent_path", ""));
	if (!parent) {
		response["ok"] = false;
		response["error"] = "parent node not found";
		return response;
	}
	String class_name = request.get("class_name", "");
	if (!ClassDBSingleton::get_singleton()->can_instantiate(class_name)) {
		response["ok"] = false;
		response["error"] = "class '" + class_name + "' cannot be instantiated";
		return response;
	}
	Variant instance = ClassDBSingleton::get_singleton()->instantiate(class_name);
	Object *obj = instance;
	Node *node = Object::cast_to<Node>(obj);
	if (!node) {
		// Not a Node subtype (e.g. a Resource) — free it rather than leaking; RefCounted objects
		// are already owned by `instance`'s internal Ref and free themselves when it goes out of
		// scope, so only manually-managed Objects need an explicit free() here.
		if (obj && !Object::cast_to<RefCounted>(obj)) {
			// Object::free() in godot-cpp is the internal GDExtension class-registration callback,
			// not an instance-destroy method — "free" the same way GDScript does, via the bound
			// script-facing method the engine exposes on every Object.
			obj->call("free");
		}
		response["ok"] = false;
		response["error"] = "class '" + class_name + "' does not inherit Node";
		return response;
	}
	String requested_name = request.get("name", "");
	if (!requested_name.is_empty()) {
		node->set_name(requested_name);
	}
	parent->add_child(node);
	SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
	if (tree && tree->get_current_scene()) {
		node->set_owner(tree->get_current_scene());
	}
	response["ok"] = true;
	response["path"] = String(node->get_path());
	return response;
}

Dictionary OrionBridgeServer::_op_delete_node(const Dictionary &request) {
	Dictionary response;
	Node *node = _resolve_node(request.get("path", ""));
	if (!node) {
		response["ok"] = false;
		response["error"] = "node not found";
		return response;
	}
	SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
	if (tree && node == Object::cast_to<Node>(tree->get_root())) {
		response["ok"] = false;
		response["error"] = "refusing to delete the scene root";
		return response;
	}
	node->queue_free();
	response["ok"] = true;
	return response;
}

Dictionary OrionBridgeServer::_op_set_paused(const Dictionary &request) {
	Dictionary response;
	SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
	if (!tree) {
		response["ok"] = false;
		response["error"] = "no scene tree";
		return response;
	}
	tree->set_pause((bool)request.get("paused", true));
	response["ok"] = true;
	response["paused"] = tree->is_paused();
	return response;
}

Dictionary OrionBridgeServer::_op_step(const Dictionary &request) {
	Dictionary response;
	SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
	if (!tree) {
		response["ok"] = false;
		response["error"] = "no scene tree";
		return response;
	}
	if (!tree->is_paused()) {
		response["ok"] = false;
		response["error"] = "tree is not paused; call set_paused with paused=true first";
		return response;
	}
	int frames = (int)request.get("frames", 1);
	if (frames < 1) {
		response["ok"] = false;
		response["error"] = "frames must be >= 1";
		return response;
	}
	// No native single-step API exists for GDExtension — approximate it by unpausing for exactly
	// `frames` process_frame ticks (counted in _on_process_frame) then re-pausing.
	step_frames_remaining = frames;
	tree->set_pause(false);
	response["ok"] = true;
	response["stepping_frames"] = frames;
	return response;
}

Dictionary OrionBridgeServer::_op_simulate_input(const Dictionary &request) {
	Dictionary response;
	String type = request.get("type", "");
	Input *input = Input::get_singleton();

	if (type == "key") {
		Ref<InputEventKey> ev;
		ev.instantiate();
		ev->set_keycode((Key)(int)request.get("keycode", 0));
		ev->set_physical_keycode((Key)(int)request.get("keycode", 0));
		ev->set_pressed((bool)request.get("pressed", true));
		ev->set_echo((bool)request.get("echo", false));
		input->parse_input_event(ev);
	} else if (type == "mouse_button") {
		Ref<InputEventMouseButton> ev;
		ev.instantiate();
		Array pos = request.get("position", Array());
		if (pos.size() >= 2) {
			ev->set_position(Vector2((float)(double)pos[0], (float)(double)pos[1]));
		}
		ev->set_button_index((MouseButton)(int)request.get("button_index", 1));
		ev->set_pressed((bool)request.get("pressed", true));
		input->parse_input_event(ev);
	} else if (type == "mouse_motion") {
		Ref<InputEventMouseMotion> ev;
		ev.instantiate();
		Array pos = request.get("position", Array());
		if (pos.size() >= 2) {
			ev->set_position(Vector2((float)(double)pos[0], (float)(double)pos[1]));
		}
		Array rel = request.get("relative", Array());
		if (rel.size() >= 2) {
			ev->set_relative(Vector2((float)(double)rel[0], (float)(double)rel[1]));
		}
		input->parse_input_event(ev);
	} else if (type == "action") {
		// `action` is reserved by the NDJSON envelope for the operation name
		// (`simulate_input`). Reusing it for the InputMap name overwrote dispatch before this
		// handler could run. Protocol v2 therefore carries the gameplay name as `input_action`.
		StringName action_name = StringName(String(request.get("input_action", "")));
		if ((bool)request.get("pressed", true)) {
			input->action_press(action_name, (float)(double)request.get("strength", 1.0));
		} else {
			input->action_release(action_name);
		}
	} else {
		response["ok"] = false;
		response["error"] = "unknown simulate_input type '" + type + "' (expected key|mouse_button|mouse_motion|action)";
		return response;
	}

	response["ok"] = true;
	return response;
}

Dictionary OrionBridgeServer::_op_reload_script(const Dictionary &request) {
	Dictionary response;
	Node *node = _resolve_node(request.get("path", ""));
	if (!node) {
		response["ok"] = false;
		response["error"] = "node not found";
		return response;
	}
	String script_path = request.get("script_path", "");
	if (script_path.is_empty()) {
		response["ok"] = false;
		response["error"] = "script_path is required";
		return response;
	}

	// Snapshot every current property's value BEFORE reassigning the script — set_script() resets
	// exported/member variables to the new script's defaults, so this is the only way to carry
	// still-relevant state (e.g. an in-progress health value) across a live script edit, matching
	// the same choreography the Godot editor's own hot-reload does.
	Dictionary before_values;
	TypedArray<Dictionary> before_props = node->get_property_list();
	for (int i = 0; i < before_props.size(); i++) {
		Dictionary p = before_props[i];
		String pname = p.get("name", "");
		if (pname.is_empty()) {
			continue;
		}
		before_values[pname] = node->get(StringName(pname));
	}

	// CACHE_MODE_REPLACE forces re-reading the file from disk — GDScript resources are otherwise
	// cached by path, so a plain load() would silently return the stale pre-edit script.
	Ref<Script> new_script = ResourceLoader::get_singleton()->load(script_path, "Script", ResourceLoader::CACHE_MODE_REPLACE);
	if (!new_script.is_valid()) {
		response["ok"] = false;
		response["error"] = "failed to load script at '" + script_path + "'";
		return response;
	}
	node->set_script(new_script);

	// Restore only properties that still exist on the new script — silently applying stale
	// properties the new script no longer declares would be a no-op at best and a confusing
	// mismatch at worst, so this is deliberately conservative rather than best-effort.
	Array restored;
	TypedArray<Dictionary> after_props = node->get_property_list();
	for (int i = 0; i < after_props.size(); i++) {
		Dictionary p = after_props[i];
		String pname = p.get("name", "");
		if (pname.is_empty() || !before_values.has(pname)) {
			continue;
		}
		node->set(StringName(pname), before_values[pname]);
		restored.append(pname);
	}

	response["ok"] = true;
	response["restored_properties"] = restored;
	return response;
}

Dictionary OrionBridgeServer::_op_classdb(const Dictionary &request) {
	Dictionary response;
	ClassDBSingleton *db = ClassDBSingleton::get_singleton();
	String class_name = request.get("class_name", "");
	response["ok"] = true;
	if (class_name.is_empty()) {
		response["classes"] = db->get_class_list();
		return response;
	}
	if (!db->class_exists(class_name)) {
		response["ok"] = false;
		response["error"] = "class '" + class_name + "' does not exist";
		return response;
	}
	response["class_name"] = class_name;
	response["parent"] = db->get_parent_class(class_name);
	response["can_instantiate"] = db->can_instantiate(class_name);
	response["properties"] = db->class_get_property_list(class_name);
	response["methods"] = db->class_get_method_list(class_name);
	response["signals"] = db->class_get_signal_list(class_name);
	return response;
}

Dictionary OrionBridgeServer::_op_save_scene(const Dictionary &request) {
	Dictionary response;
	SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
	Node *root = tree ? tree->get_current_scene() : nullptr;
	if (!root) {
		response["ok"] = false;
		response["error"] = "no current scene";
		return response;
	}
	String path = request.get("path", root->get_scene_file_path());
	if (path.is_empty()) path = "res://Main.tscn";
	if (!path.begins_with("res://") || (!path.ends_with(".tscn") && !path.ends_with(".scn"))) {
		response["ok"] = false;
		response["error"] = "path must be a res:// .tscn or .scn file";
		return response;
	}
	Ref<PackedScene> packed;
	packed.instantiate();
	Error pack_error = packed->pack(root);
	Error save_error = pack_error == OK ? ResourceSaver::get_singleton()->save(packed, path) : pack_error;
	response["ok"] = save_error == OK;
	response["path"] = path;
	response["error_code"] = (int)save_error;
	if (save_error != OK) response["error"] = "scene save failed";
	return response;
}

Dictionary OrionBridgeServer::_op_change_scene(const Dictionary &request) {
	Dictionary response;
	String path = request.get("path", "");
	SceneTree *tree = Object::cast_to<SceneTree>(Engine::get_singleton()->get_main_loop());
	Error err = tree && path.begins_with("res://") ? tree->change_scene_to_file(path) : ERR_INVALID_PARAMETER;
	response["ok"] = err == OK;
	response["path"] = path;
	response["error_code"] = (int)err;
	if (err != OK) response["error"] = "change_scene_to_file failed";
	return response;
}

Dictionary OrionBridgeServer::_op_project_setting(const Dictionary &request) {
	Dictionary response;
	String name = request.get("name", "");
	if (name.is_empty()) {
		response["ok"] = false;
		response["error"] = "name is required";
		return response;
	}
	ProjectSettings *settings = ProjectSettings::get_singleton();
	if (request.has("value")) {
		settings->set_setting(name, request["value"]);
		Error err = settings->save();
		response["ok"] = err == OK;
		response["saved"] = err == OK;
		if (err != OK) response["error"] = "project.godot save failed";
	} else {
		response["ok"] = true;
	}
	response["name"] = name;
	response["value"] = settings->get_setting(name);
	return response;
}

Dictionary OrionBridgeServer::_op_input_actions(const Dictionary &request) {
	Dictionary response;
	response["ok"] = true;
	response["actions"] = InputMap::get_singleton()->get_actions();
	return response;
}
