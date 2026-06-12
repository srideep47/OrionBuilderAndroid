// JNI bridge into the embedded Node 18 runtime (nodejs-mobile libnode.so).
// node::Start runs the Node event loop on the calling thread and blocks until Node exits, so the
// Kotlin side calls nativeStart on a dedicated thread inside the :node process.
#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

#include "node.h"

#define LOG_TAG "OrionNode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int s_pipe[2];
static bool s_redirected = false;

// Pumps the dup'd stdout/stderr pipe into logcat so console.log() is visible during development.
static void pumpToLogcat() {
    char buf[2048];
    ssize_t n;
    while ((n = read(s_pipe[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[n - 1] == '\n') n--;
        buf[n] = '\0';
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", buf);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_legion_orion_runtime_node_NodeRuntime_nativeRedirectOutput(JNIEnv *, jclass) {
    if (s_redirected) return;
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(s_pipe) != 0) {
        LOGE("failed to create log pipe");
        return;
    }
    dup2(s_pipe[1], STDOUT_FILENO);
    dup2(s_pipe[1], STDERR_FILENO);
    std::thread(pumpToLogcat).detach();
    s_redirected = true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_legion_orion_runtime_node_NodeRuntime_nativeStart(JNIEnv *env, jclass, jobjectArray jargs) {
    const jint argc = env->GetArrayLength(jargs);
    std::vector<std::string> storage;
    storage.reserve(argc);
    for (jint i = 0; i < argc; i++) {
        auto s = reinterpret_cast<jstring>(env->GetObjectArrayElement(jargs, i));
        const char *c = env->GetStringUTFChars(s, nullptr);
        storage.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    std::vector<char *> argv;
    argv.reserve(argc + 1);
    for (auto &str : storage) argv.push_back(const_cast<char *>(str.c_str()));
    argv.push_back(nullptr);

    LOGI("starting node with %d args", argc);
    int code = node::Start(argc, argv.data());
    LOGI("node exited with code %d", code);
    return code;
}
