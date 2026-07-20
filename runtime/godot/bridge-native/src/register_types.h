#ifndef ORION_BRIDGE_REGISTER_TYPES_H
#define ORION_BRIDGE_REGISTER_TYPES_H

#include <godot_cpp/core/class_db.hpp>

using namespace godot;

void initialize_orion_bridge_module(ModuleInitializationLevel p_level);
void uninitialize_orion_bridge_module(ModuleInitializationLevel p_level);

#endif // ORION_BRIDGE_REGISTER_TYPES_H
