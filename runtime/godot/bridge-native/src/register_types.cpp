#include "register_types.h"
#include "orion_bridge_server.h"

#include <gdextension_interface.h>
#include <godot_cpp/classes/engine.hpp>
#include <godot_cpp/core/class_db.hpp>
#include <godot_cpp/core/defs.hpp>
#include <godot_cpp/godot.hpp>

using namespace godot;

static OrionBridgeServer *orion_bridge_server_singleton = nullptr;

void initialize_orion_bridge_module(ModuleInitializationLevel p_level) {
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
		return;
	}

	ClassDB::register_class<OrionBridgeServer>();

	orion_bridge_server_singleton = memnew(OrionBridgeServer);
	Engine::get_singleton()->register_singleton("OrionBridgeServer", OrionBridgeServer::get_singleton());
}

void uninitialize_orion_bridge_module(ModuleInitializationLevel p_level) {
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
		return;
	}

	Engine::get_singleton()->unregister_singleton("OrionBridgeServer");

	if (orion_bridge_server_singleton) {
		memdelete(orion_bridge_server_singleton);
		orion_bridge_server_singleton = nullptr;
	}
}

extern "C" {
GDExtensionBool GDE_EXPORT orion_bridge_library_init(
		GDExtensionInterfaceGetProcAddress p_get_proc_address,
		GDExtensionClassLibraryPtr p_library,
		GDExtensionInitialization *r_initialization) {
	godot::GDExtensionBinding::InitObject init_obj(p_get_proc_address, p_library, r_initialization);

	init_obj.register_initializer(initialize_orion_bridge_module);
	init_obj.register_terminator(uninitialize_orion_bridge_module);
	init_obj.set_minimum_library_initialization_level(MODULE_INITIALIZATION_LEVEL_SCENE);

	return init_obj.init();
}
}
