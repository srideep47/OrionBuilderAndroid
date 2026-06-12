cmake_minimum_required(VERSION 3.22.1)
project("orionmnn")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Path to MNN framework checkout (headers only; libMNN.so is prebuilt in jniLibs).
# Passed by Gradle via -DMNN_SOURCE_ROOT.
if(NOT DEFINED MNN_SOURCE_ROOT)
    message(FATAL_ERROR "MNN_SOURCE_ROOT not set")
endif()

include_directories("${MNN_SOURCE_ROOT}/include/")
include_directories("${MNN_SOURCE_ROOT}/transformers/llm/engine/include")
include_directories("${MNN_SOURCE_ROOT}/tools/audio/include")
include_directories("${MNN_SOURCE_ROOT}/3rd_party/")
include_directories("${CMAKE_CURRENT_SOURCE_DIR}/third_party/")

# LLM-only subset of the GPTee/LocalAI native layer (no diffusion/sana on-device).
file(GLOB VIDEO_SOURCES "video/*.cpp")

add_library(${CMAKE_PROJECT_NAME} SHARED
    llm_mnn_jni.cpp
    llm_session.cpp
    processor.cpp
    ${VIDEO_SOURCES}
)

# 16KB page size support (Android 15+)
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")

# Prebuilt MNN library (vendored from GPTee, built with LLM + OpenCL support)
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so")

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
    mediandk
    MNN
)
