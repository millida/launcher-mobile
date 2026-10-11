#pragma once

#include <android/log.h>
#include <android/native_window.h>
#include <jni.h>

#include <cstdint>

#define MILLIDA_EXPORT extern "C" __attribute__((visibility("default")))

#define MILLIDA_LOG_TAG "millida-runtime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, MILLIDA_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, MILLIDA_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MILLIDA_LOG_TAG, __VA_ARGS__)

namespace millida {

struct SurfaceSize {
    int32_t width;
    int32_t height;
};

SurfaceSize surface_size();

void surface_attach(ANativeWindow* window, int32_t width, int32_t height);
void surface_detach();

/**
 * Creates the display and the context once, and the window surface every time
 * the view hands over a new one: a rotation or a trip to the background
 * replaces the underlying window, and drawing into the old one crashes.
 */
bool egl_prepare(const char* renderer_dir, const char* renderer_library, const char* egl_library);
bool egl_make_current();
bool egl_swap_buffers();
void egl_set_swap_interval(int interval);
void* egl_proc_address(const char* name);
void egl_release();

enum class EventKind : int32_t {
    CursorPosition = 1,
    MouseButton = 2,
    Key = 3,
    Char = 4,
    Scroll = 5,
    WindowSize = 6,
    Focus = 7,
    CursorDelta = 8,
    CloseRequest = 9,
};

struct InputEvent {
    EventKind kind;
    int32_t a;
    int32_t b;
    int32_t c;
    int32_t d;
};

void input_push(const InputEvent& event);
bool input_pop(InputEvent* out);
void input_clear();

bool jvm_start(const char* java_home,
               const char* const* jvm_args,
               int jvm_arg_count,
               const char* main_class,
               const char* const* program_args,
               int program_arg_count,
               const char* runtime_dir,
               const char* work_dir,
               const char* log_path);

bool jvm_running();

void jvm_request_exit();

/** Makes Android's reserved signals 33-41 harmless wherever they were left at the default disposition. */
void signals_guard(const char* moment);

/** Repeats the guard for the rest of the process and reports what the absorbed signals were. */
void signals_watch();

}  // namespace millida
