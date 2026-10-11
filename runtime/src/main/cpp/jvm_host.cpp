#include "millida_bridge.h"

#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <string>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <unwind.h>
#include <vector>

namespace millida {
namespace {

using CreateJavaVm = jint (*)(JavaVM**, JNIEnv**, void*);

// The NDK jni.h stops at JNI_VERSION_1_6; the desktop virtual machine we load
// expects 1.8, so the constant is spelled out here.
constexpr jint kJniVersion18 = 0x00010008;

constexpr size_t kPathLimit = 1024;
constexpr int kBacktraceLimit = 48;
constexpr size_t kReportLimit = 16384;
constexpr size_t kAlternateStackSize = 65536;

std::atomic<bool> g_running{false};
JavaVM* g_game_vm = nullptr;
void* g_jvm_handle = nullptr;

// A signal handler must not build strings, so both paths are copied into fixed
// buffers before the virtual machine starts.
char g_log_path[kPathLimit];
char g_crash_path[kPathLimit];

// SIGSEGV, SIGBUS and SIGFPE are part of how HotSpot works: implicit null
// checks, safepoint polling and stack banging all arrive as faults the virtual
// machine handles itself. Trapping them ahead of libjvm.so turned normal
// operation into crash reports and left the machine's own handler chain broken.
const int kTrappedSignals[] = {SIGABRT, SIGILL};
constexpr size_t kTrappedCount = sizeof(kTrappedSignals) / sizeof(kTrappedSignals[0]);
struct sigaction g_previous[kTrappedCount];
bool g_handlers_installed = false;

// Two threads dying at once used to interleave their bytes in the file; the
// first one through takes the report and the rest go straight to the platform.
std::atomic<bool> g_report_in_flight{false};

void raw_write(int fd, const char* text, size_t length) {
    while (length > 0) {
        const ssize_t written = write(fd, text, length);
        if (written <= 0) {
            if (written < 0 && errno == EINTR) continue;
            return;
        }
        text += written;
        length -= static_cast<size_t>(written);
    }
}

// The whole report is assembled here and handed to a single write, so that a
// concurrent writer on the same file cannot split it in half.
struct Report {
    char data[kReportLimit];
    size_t used;
    int depth;
};

void put_bytes(Report* out, const char* text, size_t length) {
    const size_t room = sizeof(out->data) - out->used;
    if (length > room) length = room;
    memcpy(out->data + out->used, text, length);
    out->used += length;
}

void put_text(Report* out, const char* text) {
    if (text == nullptr) return;
    put_bytes(out, text, strlen(text));
}

void put_dec(Report* out, long value) {
    char buffer[24];
    size_t at = sizeof(buffer);
    unsigned long magnitude =
        value < 0 ? static_cast<unsigned long>(-value) : static_cast<unsigned long>(value);
    do {
        buffer[--at] = static_cast<char>('0' + (magnitude % 10));
        magnitude /= 10;
    } while (magnitude != 0 && at > 1);
    if (value < 0 && at > 0) buffer[--at] = '-';
    put_bytes(out, buffer + at, sizeof(buffer) - at);
}

void put_hex(Report* out, uintptr_t value) {
    static const char digits[] = "0123456789abcdef";
    char buffer[2 * sizeof(uintptr_t)];
    size_t at = sizeof(buffer);
    do {
        buffer[--at] = digits[value & 0xf];
        value >>= 4;
    } while (value != 0 && at > 0);
    put_bytes(out, buffer + at, sizeof(buffer) - at);
}

_Unwind_Reason_Code unwind_step(_Unwind_Context* context, void* argument) {
    auto* out = static_cast<Report*>(argument);
    if (out->depth >= kBacktraceLimit) return _URC_END_OF_STACK;
    const uintptr_t ip = _Unwind_GetIP(context);
    if (ip == 0) return _URC_END_OF_STACK;

    put_text(out, "  #");
    put_dec(out, out->depth);
    put_text(out, " pc 0x");
    put_hex(out, ip);

    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (dladdr(reinterpret_cast<void*>(ip), &info) != 0) {
        if (info.dli_fbase != nullptr) {
            put_text(out, " +0x");
            put_hex(out, ip - reinterpret_cast<uintptr_t>(info.dli_fbase));
        }
        if (info.dli_fname != nullptr) {
            put_text(out, " ");
            put_text(out, info.dli_fname);
        }
        if (info.dli_sname != nullptr) {
            put_text(out, " ");
            put_text(out, info.dli_sname);
        }
    }
    put_text(out, "\n");
    out->depth += 1;
    return _URC_NO_REASON;
}

bool starts_with(const char* text, const char* prefix) {
    while (*prefix != '\0') {
        if (*text != *prefix) return false;
        ++text;
        ++prefix;
    }
    return true;
}

// Platform threads (hardware rendering, binder, the Android runtime's own
// workers) abort for reasons of their own. A report written for them describes
// nothing about the session and only buries the real failure.
const char* const kForeignThreadPrefixes[] = {
    "hwui", "NavDraw", "RenderThread", "binder", "Jit thread", "HeapTask", "Profile Saver",
};

bool foreign_thread(const char* name) {
    if (name == nullptr || name[0] == '\0') return false;
    for (const char* prefix : kForeignThreadPrefixes) {
        if (starts_with(name, prefix)) return true;
    }
    return false;
}

void restore_previous(int signal_number) {
    for (size_t i = 0; i < kTrappedCount; ++i) {
        if (kTrappedSignals[i] == signal_number) {
            sigaction(signal_number, &g_previous[i], nullptr);
            return;
        }
    }
    signal(signal_number, SIG_DFL);
}

void crash_handler(int signal_number, siginfo_t* info, void*) {
    char name[32];
    memset(name, 0, sizeof(name));
    if (prctl(PR_GET_NAME, name, 0, 0, 0) != 0) name[0] = '\0';

    const bool ours = !foreign_thread(name) && !g_report_in_flight.exchange(true);
    if (ours && g_crash_path[0] != '\0') {
        // The flag above admits one writer, so a static buffer is safe here and
        // keeps sixteen kilobytes off the signal stack.
        static Report report;
        report.used = 0;
        report.depth = 0;
        put_text(&report, "\n--- native crash ---\nsignal ");
        put_dec(&report, signal_number);
        put_text(&report, " code ");
        put_dec(&report, info == nullptr ? 0 : info->si_code);
        put_text(&report, " fault 0x");
        put_hex(&report, info == nullptr ? 0 : reinterpret_cast<uintptr_t>(info->si_addr));
        put_text(&report, "\nthread ");
        put_text(&report, name[0] == '\0' ? "?" : name);
        put_text(&report, " tid ");
        put_dec(&report, static_cast<long>(syscall(SYS_gettid)));
        put_text(&report, "\nsession log ");
        put_text(&report, g_log_path[0] == '\0' ? "?" : g_log_path);
        put_text(&report, "\nbacktrace:\n");
        _Unwind_Backtrace(unwind_step, &report);
        put_text(&report, "--- end of native crash ---\n");

        const int fd = open(g_crash_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (fd >= 0) {
            raw_write(fd, report.data, report.used);
            close(fd);
        }
    }

    // The platform tombstone is still worth having, so the original disposition
    // takes the signal back once our own report is on disk.
    restore_previous(signal_number);
    raise(signal_number);
}

void install_crash_handlers() {
    if (g_handlers_installed) return;
    g_handlers_installed = true;

    // An abort can arrive on a thread whose stack is already exhausted, so the
    // handler is given one of its own.
    static char alternate[kAlternateStackSize];
    stack_t alternate_stack{};
    alternate_stack.ss_sp = alternate;
    alternate_stack.ss_size = sizeof(alternate);
    alternate_stack.ss_flags = 0;
    sigaltstack(&alternate_stack, nullptr);

    struct sigaction action{};
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = crash_handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (size_t i = 0; i < kTrappedCount; ++i) {
        sigaction(kTrappedSignals[i], &action, &g_previous[i]);
    }
}

void copy_path(char* target, const char* source) {
    target[0] = '\0';
    if (source == nullptr) return;
    const size_t length = strnlen(source, kPathLimit - 1);
    memcpy(target, source, length);
    target[length] = '\0';
}

void derive_crash_path(const char* log_path) {
    static const char kName[] = "native-crash.txt";
    g_crash_path[0] = '\0';
    if (log_path == nullptr || log_path[0] == '\0') return;

    const char* slash = strrchr(log_path, '/');
    if (slash == nullptr) {
        copy_path(g_crash_path, kName);
        return;
    }
    const size_t prefix = static_cast<size_t>(slash - log_path) + 1;
    if (prefix + sizeof(kName) > kPathLimit) return;
    memcpy(g_crash_path, log_path, prefix);
    memcpy(g_crash_path + prefix, kName, sizeof(kName));
}

bool redirect_streams(const char* log_path) {
    if (log_path == nullptr || log_path[0] == '\0') return false;
    if (freopen(log_path, "a", stdout) == nullptr) return false;
    if (freopen(log_path, "a", stderr) == nullptr) return false;
    // Line buffering keeps the last line before a crash on disk without a
    // flushing thread of our own.
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IOLBF, 0);
    return true;
}

__attribute__((format(printf, 1, 2))) void session_log(const char* format, ...) {
    char line[1024];
    va_list arguments;
    va_start(arguments, format);
    vsnprintf(line, sizeof(line), format, arguments);
    va_end(arguments);
    fputs(line, stderr);
    fputc('\n', stderr);
    fflush(stderr);
}

void* open_libjvm(const char* java_home) {
    const char* relative[] = {
        "/lib/server/libjvm.so",
        "/lib/aarch64/server/libjvm.so",
        "/lib/arm64/server/libjvm.so",
    };
    for (const char* tail : relative) {
        std::string path(java_home);
        path.append(tail);
        void* handle = dlopen(path.c_str(), RTLD_GLOBAL | RTLD_NOW);
        if (handle != nullptr) {
            LOGI("виртуальная машина взята из %s", path.c_str());
            session_log("[millida] jvm loaded from %s", path.c_str());
            return handle;
        }
        const char* reason = dlerror();
        LOGW("нет %s (%s)", path.c_str(), reason);
        session_log("[millida] libjvm not at %s (%s)", path.c_str(), reason == nullptr ? "?" : reason);
    }
    return nullptr;
}

/**
 * The runtime's own libraries depend on each other (libnio needs libnet), and
 * dlopen inside the virtual machine cannot find siblings: LD_LIBRARY_PATH is
 * read when the process starts, long before this point. Opening every library
 * by absolute path puts them all in one namespace, so the dependencies resolve
 * to objects that are already loaded. Several passes, because the order inside
 * the directory is arbitrary.
 */
void preload_runtime_libraries(const char* java_home) {
    if (java_home == nullptr || java_home[0] == '\0') return;
    const char* folders[] = {"/lib", "/lib/server", "/lib/jli"};
    for (int pass = 0; pass < 3; ++pass) {
        int opened = 0;
        for (const char* folder : folders) {
            std::string path(java_home);
            path.append(folder);
            DIR* dir = opendir(path.c_str());
            if (dir == nullptr) continue;
            while (dirent* entry = readdir(dir)) {
                const char* name = entry->d_name;
                const size_t length = strlen(name);
                if (length < 4 || strcmp(name + length - 3, ".so") != 0) continue;
                std::string full(path);
                full.append("/").append(name);
                if (dlopen(full.c_str(), RTLD_GLOBAL | RTLD_NOW) != nullptr) opened += 1;
            }
            closedir(dir);
        }
        if (opened == 0) break;
    }
}

void preload_companions(const char* java_home) {
    const char* companions[] = {"/lib/libjli.so", "/lib/jli/libjli.so"};
    for (const char* tail : companions) {
        std::string path(java_home);
        path.append(tail);
        void* handle = dlopen(path.c_str(), RTLD_GLOBAL | RTLD_NOW);
        if (handle != nullptr) return;
    }
}

}  // namespace

bool jvm_running() { return g_running.load(); }

void jvm_request_exit() {
    if (!g_running.load() || g_game_vm == nullptr) return;
    JNIEnv* env = nullptr;
    if (g_game_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    jclass system = env->FindClass("java/lang/System");
    if (system != nullptr) {
        jmethodID exit = env->GetStaticMethodID(system, "exit", "(I)V");
        if (exit != nullptr) env->CallStaticVoidMethod(system, exit, 0);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    g_game_vm->DetachCurrentThread();
}

bool jvm_start(const char* java_home,
               const char* const* jvm_args,
               int jvm_arg_count,
               const char* main_class,
               const char* const* program_args,
               int program_arg_count,
               const char* runtime_dir,
               const char* work_dir,
               const char* log_path) {
    copy_path(g_log_path, log_path);
    derive_crash_path(g_log_path);
    const bool redirected = redirect_streams(g_log_path);
    install_crash_handlers();

    if (redirected) {
        LOGI("вывод игры уходит в %s, нативные падения в %s", g_log_path, g_crash_path);
    } else {
        LOGW("не удалось перенаправить вывод в %s (%s)", g_log_path[0] == '\0' ? "?" : g_log_path,
             strerror(errno));
    }

    if (g_running.exchange(true)) {
        LOGE("игра уже запущена");
        session_log("[millida] start refused: session already running");
        return false;
    }

    session_log("[millida] session start, java home %s", java_home);
    signals_guard("before the virtual machine");

    setenv("JAVA_HOME", java_home, 1);
    if (runtime_dir != nullptr && runtime_dir[0] != '\0') {
        setenv("MILLIDA_RUNTIME_DIR", runtime_dir, 1);
    }
    if (g_log_path[0] != '\0') {
        setenv("MILLIDA_SESSION_LOG", g_log_path, 1);
    }

    // The process starts in "/", where nothing is writable, and the game's
    // logging writes to the relative path "logs/latest.log".
    if (work_dir != nullptr && work_dir[0] != '\0') {
        if (mkdir(work_dir, 0770) != 0 && errno != EEXIST) {
            session_log("[millida] cannot create work dir %s (%s)", work_dir, strerror(errno));
        }
        if (chdir(work_dir) != 0) {
            session_log("[millida] cannot enter work dir %s (%s)", work_dir, strerror(errno));
        } else {
            // Done before JNI_CreateJavaVM so that the machine picks this up as
            // user.dir, which is what java.io.File resolves relative paths against.
            session_log("[millida] work dir %s", work_dir);
        }
    }

    preload_companions(java_home);
    preload_runtime_libraries(java_home);
    g_jvm_handle = open_libjvm(java_home);
    if (g_jvm_handle == nullptr) {
        LOGE("libjvm.so не найдена в %s", java_home);
        session_log("[millida] session end: libjvm.so not found under %s", java_home);
        g_running.store(false);
        return false;
    }

    auto create = reinterpret_cast<CreateJavaVm>(dlsym(g_jvm_handle, "JNI_CreateJavaVM"));
    if (create == nullptr) {
        LOGE("в libjvm.so нет JNI_CreateJavaVM");
        session_log("[millida] session end: libjvm.so exports no JNI_CreateJavaVM");
        g_running.store(false);
        return false;
    }

    std::vector<std::string> options;
    options.reserve(static_cast<size_t>(jvm_arg_count) + 2);
    options.emplace_back(std::string("-Djava.home=") + java_home);
    for (int i = 0; i < jvm_arg_count; ++i) {
        if (jvm_args[i] != nullptr && jvm_args[i][0] != '\0') options.emplace_back(jvm_args[i]);
    }

    std::vector<JavaVMOption> vm_options(options.size());
    for (size_t i = 0; i < options.size(); ++i) {
        vm_options[i].optionString = const_cast<char*>(options[i].c_str());
        vm_options[i].extraInfo = nullptr;
    }

    JavaVMInitArgs args{};
    args.version = kJniVersion18;
    args.nOptions = static_cast<jint>(vm_options.size());
    args.options = vm_options.data();
    args.ignoreUnrecognized = JNI_TRUE;

    for (const std::string& option : options) {
        session_log("[millida] jvm option %s", option.c_str());
    }

    JNIEnv* env = nullptr;
    const jint created = create(&g_game_vm, &env, &args);
    if (created != JNI_OK || env == nullptr) {
        LOGE("виртуальная машина не запустилась, код %d", created);
        session_log("[millida] session end: JNI_CreateJavaVM failed, code %d%s", created,
                    env == nullptr ? " (no JNIEnv)" : "");
        g_running.store(false);
        return false;
    }

    signals_guard("after the virtual machine started");
    signals_watch();

    std::string binary_name(main_class);
    for (char& ch : binary_name) {
        if (ch == '.') ch = '/';
    }

    jclass entry = env->FindClass(binary_name.c_str());
    if (entry == nullptr) {
        LOGE("не найден главный класс %s", main_class);
        if (env->ExceptionCheck()) env->ExceptionDescribe();
        session_log("[millida] session end: main class %s not found", main_class);
        g_running.store(false);
        return false;
    }

    jmethodID main = env->GetStaticMethodID(entry, "main", "([Ljava/lang/String;)V");
    if (main == nullptr) {
        LOGE("в %s нет метода main", main_class);
        if (env->ExceptionCheck()) env->ExceptionDescribe();
        session_log("[millida] session end: %s has no main method", main_class);
        g_running.store(false);
        return false;
    }

    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray argv = env->NewObjectArray(program_arg_count, string_class, nullptr);
    for (int i = 0; i < program_arg_count; ++i) {
        jstring value = env->NewStringUTF(program_args[i] == nullptr ? "" : program_args[i]);
        env->SetObjectArrayElement(argv, i, value);
        env->DeleteLocalRef(value);
    }

    LOGI("запуск %s с %d аргументами", main_class, program_arg_count);
    session_log("[millida] calling %s.main with %d arguments", main_class, program_arg_count);
    env->CallStaticVoidMethod(entry, main, argv);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("игра завершилась с ошибкой");
        session_log("[millida] session end: %s.main threw, stack trace above", main_class);
        g_running.store(false);
        return false;
    }

    LOGI("игра завершилась штатно");
    session_log("[millida] session end: %s.main returned normally", main_class);
    g_running.store(false);
    return true;
}

}  // namespace millida
