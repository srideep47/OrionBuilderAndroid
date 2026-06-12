// JNI bridge into the embedded Node 18 runtime (nodejs-mobile libnode.so).
// node::Start runs the Node event loop on the calling thread and blocks until Node exits, so the
// Kotlin side calls nativeStart on a dedicated thread inside the :node process.
#include <jni.h>
#include <android/log.h>
#include <csignal>
#include <cstdio>
#include <dlfcn.h>
#include <fcntl.h>
#include <string>
#include <thread>
#include <ucontext.h>
#include <unistd.h>
#include <vector>

#include "node.h"

#define LOG_TAG "OrionNode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int s_pipe[2];
static bool s_redirected = false;

// Honor/MagicOS encrypts system tombstones, so log fault details ourselves before the default
// handler runs: signal, fault address, and dladdr-resolved PC/LR so the faulting library+symbol
// is visible in logcat even without a readable tombstone.
static void crashHandler(int sig, siginfo_t *info, void *uctx) {
    auto *uc = reinterpret_cast<ucontext_t *>(uctx);
    uintptr_t pc = uc->uc_mcontext.pc;
    uintptr_t lr = uc->uc_mcontext.regs[30];
    Dl_info dpc{}, dlr{};
    dladdr(reinterpret_cast<void *>(pc), &dpc);
    dladdr(reinterpret_cast<void *>(lr), &dlr);
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "NATIVE CRASH sig=%d fault_addr=%p | pc=%p sym=%s lib=%s off=0x%lx | lr=%p sym=%s lib=%s off=0x%lx",
                        sig, info->si_addr,
                        reinterpret_cast<void *>(pc),
                        dpc.dli_sname ? dpc.dli_sname : "?",
                        dpc.dli_fname ? dpc.dli_fname : "?",
                        dpc.dli_fbase ? static_cast<unsigned long>(pc - reinterpret_cast<uintptr_t>(dpc.dli_fbase)) : 0UL,
                        reinterpret_cast<void *>(lr),
                        dlr.dli_sname ? dlr.dli_sname : "?",
                        dlr.dli_fname ? dlr.dli_fname : "?",
                        dlr.dli_fbase ? static_cast<unsigned long>(lr - reinterpret_cast<uintptr_t>(dlr.dli_fbase)) : 0UL);
    signal(sig, SIG_DFL);
    raise(sig);
}

static void installCrashLogger() {
    struct sigaction sa{};
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (int sig : {SIGSEGV, SIGABRT, SIGILL, SIGBUS, SIGTRAP, SIGFPE}) {
        sigaction(sig, &sa, nullptr);
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    installCrashLogger();
    return JNI_VERSION_1_6;
}

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
Java_com_legion_orion_runtime_node_NodeRuntime_nativeRedirectOutput(JNIEnv *env, jclass, jstring jStderrFile) {
    if (s_redirected) return;
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(s_pipe) != 0) {
        LOGE("failed to create log pipe");
        return;
    }
    dup2(s_pipe[1], STDOUT_FILENO);
    // stderr writes go straight to a file so a fatal in-process exit(1) can't lose them: the kernel
    // persists each write() immediately, unlike the logcat pipe whose pump thread dies with us.
    const char *path = env->GetStringUTFChars(jStderrFile, nullptr);
    int errFd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    env->ReleaseStringUTFChars(jStderrFile, path);
    if (errFd >= 0) {
        dup2(errFd, STDERR_FILENO);
        close(errFd);
    } else {
        dup2(s_pipe[1], STDERR_FILENO);
    }
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
    // V8 installs its own SIGSEGV (wasm trap) handler during boot, displacing ours from JNI_OnLoad.
    // Re-install after boot so addon-load faults are logged even with encrypted system tombstones.
    std::thread([] {
        sleep(5);
        installCrashLogger();
        LOGI("crash logger re-armed post-boot");
    }).detach();
    int code = node::Start(argc, argv.data());
    LOGI("node exited with code %d", code);
    return code;
}
