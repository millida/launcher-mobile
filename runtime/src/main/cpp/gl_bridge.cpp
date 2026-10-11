#include "millida_bridge.h"

#include <android/native_window_jni.h>

#include <cstdlib>
#include <string>
#include <vector>

using millida::EventKind;
using millida::InputEvent;

namespace {

std::string read_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::vector<std::string> read_string_array(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) return out;
    const jsize count = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto element = reinterpret_cast<jstring>(env->GetObjectArrayElement(array, i));
        out.emplace_back(read_string(env, element));
        if (element != nullptr) env->DeleteLocalRef(element);
    }
    return out;
}

}  // namespace

MILLIDA_EXPORT void Java_net_millida_launcher_runtime_RuntimeBridge_nativeAttachSurface(
    JNIEnv* env, jclass, jobject surface, jint width, jint height) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LOGE("поверхность не превратилась в окно");
        return;
    }
    millida::surface_attach(window, width, height);
    InputEvent event{EventKind::WindowSize, width, height, 0, 0};
    millida::input_push(event);
}

MILLIDA_EXPORT void Java_net_millida_launcher_runtime_RuntimeBridge_nativeDetachSurface(JNIEnv*, jclass) {
    millida::surface_detach();
}

MILLIDA_EXPORT void Java_net_millida_launcher_runtime_RuntimeBridge_nativePushEvent(
    JNIEnv*, jclass, jint kind, jint a, jint b, jint c, jint d) {
    InputEvent event{static_cast<EventKind>(kind), a, b, c, d};
    millida::input_push(event);
}

MILLIDA_EXPORT void Java_net_millida_launcher_runtime_RuntimeBridge_nativeClearEvents(JNIEnv*, jclass) {
    millida::input_clear();
}

MILLIDA_EXPORT jboolean Java_net_millida_launcher_runtime_RuntimeBridge_nativeIsRunning(JNIEnv*, jclass) {
    return millida::jvm_running() ? JNI_TRUE : JNI_FALSE;
}

MILLIDA_EXPORT void Java_net_millida_launcher_runtime_RuntimeBridge_nativeRequestExit(JNIEnv*, jclass) {
    millida::jvm_request_exit();
}

MILLIDA_EXPORT void Java_net_millida_launcher_runtime_RuntimeBridge_nativeGuardSignals(
    JNIEnv* env, jclass, jstring moment) {
    const std::string text = read_string(env, moment);
    millida::signals_guard(text.c_str());
    millida::signals_watch();
}

MILLIDA_EXPORT void Java_net_millida_launcher_runtime_RuntimeBridge_nativePutEnv(
    JNIEnv* env, jclass, jstring key, jstring value) {
    const std::string name = read_string(env, key);
    const std::string data = read_string(env, value);
    if (name.empty()) return;
    setenv(name.c_str(), data.c_str(), 1);
}

MILLIDA_EXPORT jboolean Java_net_millida_launcher_runtime_RuntimeBridge_nativeStartGame(
    JNIEnv* env,
    jclass,
    jstring java_home,
    jobjectArray jvm_args,
    jstring main_class,
    jobjectArray game_args,
    jstring runtime_dir,
    jstring work_dir,
    jstring log_path) {
    const std::string home = read_string(env, java_home);
    const std::string entry = read_string(env, main_class);
    const std::string runtime = read_string(env, runtime_dir);
    const std::string work = read_string(env, work_dir);
    const std::string log = read_string(env, log_path);
    const std::vector<std::string> jvm = read_string_array(env, jvm_args);
    const std::vector<std::string> game = read_string_array(env, game_args);

    std::vector<const char*> jvm_raw;
    jvm_raw.reserve(jvm.size());
    for (const std::string& value : jvm) jvm_raw.push_back(value.c_str());

    std::vector<const char*> game_raw;
    game_raw.reserve(game.size());
    for (const std::string& value : game) game_raw.push_back(value.c_str());

    const bool ok = millida::jvm_start(
        home.c_str(),
        jvm_raw.empty() ? nullptr : jvm_raw.data(),
        static_cast<int>(jvm_raw.size()),
        entry.c_str(),
        game_raw.empty() ? nullptr : game_raw.data(),
        static_cast<int>(game_raw.size()),
        runtime.c_str(),
        work.c_str(),
        log.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

MILLIDA_EXPORT jboolean Java_net_millida_launcher_bridge_GlfwBridge_setupWindow(
    JNIEnv* env, jclass, jstring renderer_dir, jstring renderer_library, jstring egl_library) {
    const std::string dir = read_string(env, renderer_dir);
    const std::string library = read_string(env, renderer_library);
    const std::string egl = read_string(env, egl_library);
    if (!millida::egl_prepare(dir.c_str(), library.c_str(), egl.c_str())) return JNI_FALSE;
    return millida::egl_make_current() ? JNI_TRUE : JNI_FALSE;
}

MILLIDA_EXPORT jboolean Java_net_millida_launcher_bridge_GlfwBridge_swapBuffers(JNIEnv*, jclass) {
    return millida::egl_swap_buffers() ? JNI_TRUE : JNI_FALSE;
}

MILLIDA_EXPORT void Java_net_millida_launcher_bridge_GlfwBridge_setSwapInterval(JNIEnv*, jclass, jint interval) {
    millida::egl_set_swap_interval(interval);
}

MILLIDA_EXPORT jboolean Java_net_millida_launcher_bridge_GlfwBridge_pollEvent(
    JNIEnv* env, jclass, jintArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 5) return JNI_FALSE;
    InputEvent event{};
    if (!millida::input_pop(&event)) return JNI_FALSE;
    jint values[5] = {
        static_cast<jint>(event.kind),
        event.a,
        event.b,
        event.c,
        event.d,
    };
    env->SetIntArrayRegion(out, 0, 5, values);
    return JNI_TRUE;
}

MILLIDA_EXPORT jint Java_net_millida_launcher_bridge_GlfwBridge_windowWidth(JNIEnv*, jclass) {
    return millida::surface_size().width;
}

MILLIDA_EXPORT jint Java_net_millida_launcher_bridge_GlfwBridge_windowHeight(JNIEnv*, jclass) {
    return millida::surface_size().height;
}

MILLIDA_EXPORT jlong Java_net_millida_launcher_bridge_GlfwBridge_procAddress(
    JNIEnv* env, jclass, jstring name) {
    const std::string symbol = read_string(env, name);
    if (symbol.empty()) return 0;
    return reinterpret_cast<jlong>(millida::egl_proc_address(symbol.c_str()));
}

MILLIDA_EXPORT jboolean Java_net_millida_launcher_bridge_GlfwBridge_makeCurrent(JNIEnv*, jclass) {
    return millida::egl_make_current() ? JNI_TRUE : JNI_FALSE;
}
