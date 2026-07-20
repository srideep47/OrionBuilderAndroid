#ifndef ORION_BRIDGE_SERVER_H
#define ORION_BRIDGE_SERVER_H

#include <godot_cpp/classes/node.hpp>
#include <godot_cpp/classes/object.hpp>
#include <godot_cpp/classes/stream_peer_tcp.hpp>
#include <godot_cpp/classes/tcp_server.hpp>
#include <godot_cpp/variant/dictionary.hpp>
#include <godot_cpp/variant/string.hpp>

using namespace godot;

/**
 * Engine singleton (registered via register_singleton in register_types.cpp, NOT a GDScript
 * autoload) exposing a loopback NDJSON control socket for the AI-control bridge. Deliberately not
 * an autoload/scene-tree node: an agent with node-CRUD/project-editing tools can't accidentally
 * delete or corrupt the control channel this way (see runtime/godot's design notes).
 *
 * Polled once per frame from the main thread via SceneTree's "process_frame" signal (connected in
 * the constructor) rather than a background thread — Godot's scene-tree/node APIs are not safe to
 * touch from arbitrary threads, so accepting a connection and dispatching a request synchronously
 * within one frame is the correct concurrency model here, not a shortcut. Single client at a time
 * for v1.
 *
 * **Confirmed on-device (Honor Magic 7 Pro, 2026-07-20): the frame loop — and therefore this
 * bridge — stops running entirely once the hosting Activity is backgrounded.** Reproduced directly:
 * identical ping requests succeed instantly while GodotHostActivity (the G1 full-screen task) is
 * foreground, and hang until client timeout the moment the user navigates back to Orion's own UI
 * (nothing is polling `is_connection_available()` anymore — Android suspends/deprioritizes the
 * backgrounded `:godot` process's scheduling). The G2 Compose-embedded path (RemoteGodotFragment/
 * GodotService, bound from the always-foreground MainActivity) does **not** hit this — confirmed
 * the same ping succeeds regardless of which Orion screen is visible, since GodotService's process
 * priority follows whatever's bound to it (MainActivity), not a specific screen's visibility. This
 * is a real architectural argument for G2 over G1 as the primary AI-control surface, not just a UX
 * preference: an agent needs the bridge responsive while the user is doing something else in the
 * app, which only G2 delivers today.
 */
class OrionBridgeServer : public Object {
	GDCLASS(OrionBridgeServer, Object)

private:
	static OrionBridgeServer *singleton;

	Ref<TCPServer> server;
	Ref<StreamPeerTCP> client;
	String line_buffer;
	String auth_token;

	// G4: while > 0, the tree is temporarily unpaused and this decrements once per process_frame;
	// hitting 0 re-pauses. Implements "step N frames" without any engine-side single-step API (none
	// exists for GDExtension) — see the "step" action's comment in orion_bridge_server.cpp.
	int step_frames_remaining = 0;

	void _try_connect_to_scene_tree();
	void _on_process_frame();
	void _accept_pending_connection();
	void _pump_client();
	void _handle_line(const String &line);
	Dictionary _dispatch(const Dictionary &request);
	void _write_token_file();

	// G4 mutating-op helpers (orion_bridge_server_ops.cpp)
	Dictionary _op_get_property(const Dictionary &request);
	Dictionary _op_set_property(const Dictionary &request);
	Dictionary _op_call_method(const Dictionary &request);
	Dictionary _op_list_properties(const Dictionary &request);
	Dictionary _op_list_methods(const Dictionary &request);
	Dictionary _op_create_node(const Dictionary &request);
	Dictionary _op_delete_node(const Dictionary &request);
	Dictionary _op_set_paused(const Dictionary &request);
	Dictionary _op_step(const Dictionary &request);
	Dictionary _op_simulate_input(const Dictionary &request);
	Dictionary _op_reload_script(const Dictionary &request);
	Dictionary _op_classdb(const Dictionary &request);
	Dictionary _op_save_scene(const Dictionary &request);
	Dictionary _op_change_scene(const Dictionary &request);
	Dictionary _op_project_setting(const Dictionary &request);
	Dictionary _op_input_actions(const Dictionary &request);

	// Resolves a "/root/..." NodePath string against the live SceneTree. Returns nullptr (and does
	// NOT populate err_out — callers check for nullptr themselves) if there's no scene tree yet or
	// the path doesn't resolve to a node.
	Node *_resolve_node(const String &path);

protected:
	static void _bind_methods();

public:
	// constexpr (not const) so this is an implicitly-inline variable (C++17) — a plain `static
	// const int` here compiles fine but needs a separate out-of-class definition once ODR-used
	// (e.g. passed to a variadic call), which Android's dynamic linker enforces at dlopen time even
	// though the build-time linker let it slide — confirmed on-device: "cannot locate symbol
	// OrionBridgeServer::PORT" at dlopen.
	static constexpr int PORT = 8139;

	static OrionBridgeServer *get_singleton();

	OrionBridgeServer();
	~OrionBridgeServer();
};

#endif // ORION_BRIDGE_SERVER_H
