#include "millida_bridge.h"

#include <EGL/egl.h>

#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <dlfcn.h>
#include <mutex>
#include <string>

namespace millida {
namespace {

struct EglApi {
    void* handle = nullptr;
    EGLDisplay (*GetDisplay)(EGLNativeDisplayType) = nullptr;
    EGLBoolean (*Initialize)(EGLDisplay, EGLint*, EGLint*) = nullptr;
    EGLBoolean (*ChooseConfig)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*) = nullptr;
    EGLContext (*CreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint*) = nullptr;
    EGLSurface (*CreateWindowSurface)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint*) = nullptr;
    EGLBoolean (*MakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext) = nullptr;
    EGLBoolean (*SwapBuffers)(EGLDisplay, EGLSurface) = nullptr;
    EGLBoolean (*SwapInterval)(EGLDisplay, EGLint) = nullptr;
    EGLBoolean (*DestroySurface)(EGLDisplay, EGLSurface) = nullptr;
    EGLBoolean (*DestroyContext)(EGLDisplay, EGLContext) = nullptr;
    EGLBoolean (*Terminate)(EGLDisplay) = nullptr;
    EGLint (*GetError)() = nullptr;
    __eglMustCastToProperFunctionPointerType (*GetProcAddress)(const char*) = nullptr;
};

struct State {
    EglApi api;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    void* renderer_handle = nullptr;
    ANativeWindow* window = nullptr;
    ANativeWindow* surface_window = nullptr;
    int32_t width = 0;
    int32_t height = 0;
    bool context_ready = false;
};

std::recursive_mutex g_lock;
State g_state;

template <typename Fn>
bool bind_symbol(void* handle, const char* name, Fn* slot, bool required = true) {
    *slot = reinterpret_cast<Fn>(dlsym(handle, name));
    if (*slot == nullptr && required) {
        LOGE("в библиотеке EGL нет функции %s", name);
        return false;
    }
    return true;
}

bool load_egl(const char* egl_library) {
    if (g_state.api.handle != nullptr) return true;
    const char* candidates[] = {egl_library, "libEGL.so"};
    for (const char* candidate : candidates) {
        if (candidate == nullptr || candidate[0] == '\0') continue;
        void* handle = dlopen(candidate, RTLD_LOCAL | RTLD_NOW);
        if (handle == nullptr) {
            LOGW("не открылась библиотека EGL %s: %s", candidate, dlerror());
            continue;
        }
        EglApi api;
        api.handle = handle;
        const bool ok =
            bind_symbol(handle, "eglGetDisplay", &api.GetDisplay) &&
            bind_symbol(handle, "eglInitialize", &api.Initialize) &&
            bind_symbol(handle, "eglChooseConfig", &api.ChooseConfig) &&
            bind_symbol(handle, "eglCreateContext", &api.CreateContext) &&
            bind_symbol(handle, "eglCreateWindowSurface", &api.CreateWindowSurface) &&
            bind_symbol(handle, "eglMakeCurrent", &api.MakeCurrent) &&
            bind_symbol(handle, "eglSwapBuffers", &api.SwapBuffers) &&
            bind_symbol(handle, "eglSwapInterval", &api.SwapInterval) &&
            bind_symbol(handle, "eglDestroySurface", &api.DestroySurface) &&
            bind_symbol(handle, "eglDestroyContext", &api.DestroyContext) &&
            bind_symbol(handle, "eglTerminate", &api.Terminate) &&
            bind_symbol(handle, "eglGetError", &api.GetError);
        bind_symbol(handle, "eglGetProcAddress", &api.GetProcAddress, false);
        if (!ok) {
            dlclose(handle);
            continue;
        }
        g_state.api = api;
        LOGI("EGL взят из %s", candidate);
        return true;
    }
    return false;
}

/**
 * LD_LIBRARY_PATH is read when the process linker starts, so setting it later
 * does not reach dlopen; the renderer's neighbours are opened by absolute path
 * first and its own dependencies then resolve to already-loaded objects.
 */
void preload_neighbours(const char* renderer_dir, const char* renderer_library) {
    if (renderer_dir == nullptr || renderer_dir[0] == '\0') return;
    DIR* dir = opendir(renderer_dir);
    if (dir == nullptr) return;
    while (dirent* entry = readdir(dir)) {
        const char* name = entry->d_name;
        const size_t length = strlen(name);
        if (length < 4 || strcmp(name + length - 3, ".so") != 0) continue;
        if (renderer_library != nullptr && strcmp(name, renderer_library) == 0) continue;
        std::string path(renderer_dir);
        path.append("/").append(name);
        if (dlopen(path.c_str(), RTLD_GLOBAL | RTLD_NOW) == nullptr) {
            LOGW("не удалось предзагрузить %s: %s", name, dlerror());
        }
    }
    closedir(dir);
}

bool load_renderer(const char* renderer_dir, const char* renderer_library) {
    if (renderer_library == nullptr || renderer_library[0] == '\0') return true;
    if (g_state.renderer_handle != nullptr) return true;
    preload_neighbours(renderer_dir, renderer_library);
    std::string path;
    if (renderer_library[0] != '/' && renderer_dir != nullptr && renderer_dir[0] != '\0') {
        path.append(renderer_dir).append("/").append(renderer_library);
    } else {
        path.assign(renderer_library);
    }
    g_state.renderer_handle = dlopen(path.c_str(), RTLD_GLOBAL | RTLD_NOW);
    if (g_state.renderer_handle == nullptr) {
        LOGE("рендерер %s не загрузился: %s", path.c_str(), dlerror());
        return false;
    }
    LOGI("рендерер загружен: %s", path.c_str());
    return true;
}

bool choose_config(EGLint renderable, EGLint depth, EGLint alpha) {
    const EGLint attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, renderable,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, alpha,
        EGL_DEPTH_SIZE, depth,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE,
    };
    EGLint found = 0;
    return g_state.api.ChooseConfig(g_state.display, attributes, &g_state.config, 1, &found) == EGL_TRUE && found > 0;
}

bool ensure_context() {
    if (g_state.context_ready) return true;

    g_state.display = g_state.api.GetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_state.display == EGL_NO_DISPLAY) {
        LOGE("EGL не выдал дисплей");
        return false;
    }
    EGLint major = 0;
    EGLint minor = 0;
    if (g_state.api.Initialize(g_state.display, &major, &minor) != EGL_TRUE) {
        LOGE("EGL не инициализировался: 0x%x", g_state.api.GetError());
        return false;
    }
    LOGI("EGL %d.%d", major, minor);

    // Some drivers refuse a version 3 context on a config that only declares
    // ES2, and some refuse a 24-bit depth buffer; both are tried in order.
    const bool picked =
        choose_config(EGL_OPENGL_ES3_BIT, 24, 0) ||
        choose_config(EGL_OPENGL_ES3_BIT, 16, 0) ||
        choose_config(EGL_OPENGL_ES2_BIT, 24, 0) ||
        choose_config(EGL_OPENGL_ES2_BIT, 16, 0);
    if (!picked) {
        LOGE("не нашлось подходящей конфигурации EGL");
        return false;
    }

    const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    g_state.context = g_state.api.CreateContext(g_state.display, g_state.config, EGL_NO_CONTEXT, context_attributes);
    if (g_state.context == EGL_NO_CONTEXT) {
        const EGLint fallback[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
        g_state.context = g_state.api.CreateContext(g_state.display, g_state.config, EGL_NO_CONTEXT, fallback);
    }
    if (g_state.context == EGL_NO_CONTEXT) {
        LOGE("не создался контекст EGL: 0x%x", g_state.api.GetError());
        return false;
    }

    g_state.context_ready = true;
    return true;
}

void destroy_window_surface() {
    if (g_state.surface == EGL_NO_SURFACE) return;
    g_state.api.MakeCurrent(g_state.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    g_state.api.DestroySurface(g_state.display, g_state.surface);
    g_state.surface = EGL_NO_SURFACE;
    g_state.surface_window = nullptr;
}

bool ensure_window_surface() {
    if (g_state.window == nullptr) return false;
    if (g_state.surface != EGL_NO_SURFACE && g_state.surface_window == g_state.window) return true;

    destroy_window_surface();
    g_state.surface = g_state.api.CreateWindowSurface(g_state.display, g_state.config, g_state.window, nullptr);
    if (g_state.surface == EGL_NO_SURFACE) {
        LOGE("не создалась поверхность EGL: 0x%x", g_state.api.GetError());
        return false;
    }
    g_state.surface_window = g_state.window;
    return true;
}

}  // namespace

SurfaceSize surface_size() {
    std::lock_guard<std::recursive_mutex> guard(g_lock);
    return SurfaceSize{g_state.width, g_state.height};
}

void surface_attach(ANativeWindow* window, int32_t width, int32_t height) {
    std::lock_guard<std::recursive_mutex> guard(g_lock);
    // The caller has already acquired a reference, so the previous one is always
    // released, even when the pointer happens to repeat.
    if (g_state.window != nullptr) {
        if (g_state.surface_window == g_state.window && g_state.window != window) destroy_window_surface();
        ANativeWindow_release(g_state.window);
    }
    g_state.window = window;
    g_state.width = width;
    g_state.height = height;
}

void surface_detach() {
    std::lock_guard<std::recursive_mutex> guard(g_lock);
    destroy_window_surface();
    if (g_state.window != nullptr) {
        ANativeWindow_release(g_state.window);
        g_state.window = nullptr;
    }
}

bool egl_prepare(const char* renderer_dir, const char* renderer_library, const char* egl_library) {
    std::lock_guard<std::recursive_mutex> guard(g_lock);
    if (g_state.window == nullptr) {
        LOGE("поверхность отрисовки ещё не готова");
        return false;
    }
    if (!load_egl(egl_library)) return false;
    if (!load_renderer(renderer_dir, renderer_library)) return false;
    if (!ensure_context()) return false;
    return ensure_window_surface();
}

bool egl_make_current() {
    std::lock_guard<std::recursive_mutex> guard(g_lock);
    if (!g_state.context_ready || g_state.surface == EGL_NO_SURFACE) return false;
    return g_state.api.MakeCurrent(g_state.display, g_state.surface, g_state.surface, g_state.context) == EGL_TRUE;
}

bool egl_swap_buffers() {
    std::lock_guard<std::recursive_mutex> guard(g_lock);
    if (!g_state.context_ready) return false;
    if (g_state.window == nullptr) return false;
    if (g_state.surface == EGL_NO_SURFACE || g_state.surface_window != g_state.window) {
        if (!ensure_window_surface()) return false;
        if (g_state.api.MakeCurrent(g_state.display, g_state.surface, g_state.surface, g_state.context) != EGL_TRUE) {
            return false;
        }
    }
    return g_state.api.SwapBuffers(g_state.display, g_state.surface) == EGL_TRUE;
}

void egl_set_swap_interval(int interval) {
    std::lock_guard<std::recursive_mutex> guard(g_lock);
    if (!g_state.context_ready) return;
    g_state.api.SwapInterval(g_state.display, interval);
}

void* egl_proc_address(const char* name) {
    if (name == nullptr) return nullptr;
    std::lock_guard<std::recursive_mutex> guard(g_lock);
    if (g_state.renderer_handle != nullptr) {
        void* direct = dlsym(g_state.renderer_handle, name);
        if (direct != nullptr) return direct;
    }
    if (g_state.api.GetProcAddress != nullptr) {
        void* resolved = reinterpret_cast<void*>(g_state.api.GetProcAddress(name));
        if (resolved != nullptr) return resolved;
    }
    return dlsym(RTLD_DEFAULT, name);
}

void egl_release() {
    std::lock_guard<std::recursive_mutex> guard(g_lock);
    if (!g_state.context_ready) return;
    destroy_window_surface();
    if (g_state.context != EGL_NO_CONTEXT) g_state.api.DestroyContext(g_state.display, g_state.context);
    g_state.api.Terminate(g_state.display);
    g_state.context = EGL_NO_CONTEXT;
    g_state.display = EGL_NO_DISPLAY;
    g_state.context_ready = false;
}

}  // namespace millida
