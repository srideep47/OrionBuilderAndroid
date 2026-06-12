cmake_minimum_required(VERSION 3.22.1)
project("nodebridge")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Node 18 embedding headers (node.h, v8.h, uv.h, node_api.h, ...) from nodejs-mobile.
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/include/node")

add_library(nodebridge SHARED node_bridge.cpp)

# Prebuilt Node runtime (nodejs-mobile v18.20.4, arm64-v8a).
add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so")

# 16KB page support for our bridge lib (libnode.so itself is vendored as-is).
target_link_options(nodebridge PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
target_link_libraries(nodebridge node android ${log-lib})
