import argparse
import glob
import hashlib
import inspect
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile

API_LEVEL = 26
EM_AARCH64 = 183
BUILD_FORMAT = 2
PART_DONE = ".millida-part"
# Android 15 devices may run with 16 KB pages; a library with 4 KB LOAD alignment does not load there.
PAGE_ALIGN = "-Wl,-z,max-page-size=16384"

SDL_CMAKE = [
    "-DSDL_SHARED=ON",
    "-DSDL_STATIC=OFF",
    "-DSDL_ANDROID_JAR=OFF",
    "-DSDL_TEST_LIBRARY=OFF",
    "-DSDL_TESTS=OFF",
    "-DSDL_EXAMPLES=OFF",
    "-DSDL_VENDOR_INFO=millida",
    "-DCMAKE_SHARED_LINKER_FLAGS=" + PAGE_ALIGN,
]

# glslang and SPIRV-Tools are linked in statically; their symbols and the
# static C++ runtime stay private to the library.
# SHADERC_SKIP_INSTALL stays off: shaderc forwards it to glslang as an unevaluated
# generator expression, glslang still installs and its export misses SPIRV-Tools-opt.
SHADERC_CMAKE = [
    "-DBUILD_SHARED_LIBS=OFF",
    "-DSHADERC_SKIP_TESTS=ON",
    "-DSHADERC_SKIP_EXAMPLES=ON",
    "-DSHADERC_SKIP_EXECUTABLES=ON",
    "-DSHADERC_SKIP_COPYRIGHT_CHECK=ON",
    "-DSHADERC_ENABLE_WERROR_COMPILE=OFF",
    "-DSPIRV_SKIP_TESTS=ON",
    "-DSPIRV_SKIP_EXECUTABLES=ON",
    "-DSPIRV_WERROR=OFF",
    "-DENABLE_GLSLANG_BINARIES=OFF",
    "-DGLSLANG_TESTS=OFF",
    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--exclude-libs,ALL -Wl,--no-undefined " + PAGE_ALIGN,
]

SPIRV_CROSS_CMAKE = [
    "-DSPIRV_CROSS_SHARED=ON",
    "-DSPIRV_CROSS_STATIC=OFF",
    "-DSPIRV_CROSS_CLI=OFF",
    "-DSPIRV_CROSS_ENABLE_TESTS=OFF",
    "-DSPIRV_CROSS_SKIP_INSTALL=ON",
    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--exclude-libs,ALL -Wl,--no-undefined " + PAGE_ALIGN,
]

LWJGL_SPARSE = [
    "/LICENSE.md",
    "/config/linux/",
    "/modules/lwjgl/core/src/main/c/",
    "/modules/lwjgl/core/src/generated/c/",
    "/modules/lwjgl/opengl/src/main/c/",
    "/modules/lwjgl/opengl/src/generated/c/",
    "/modules/lwjgl/stb/src/main/c/",
    "/modules/lwjgl/stb/src/generated/c/",
    "/modules/lwjgl/spng/src/main/c/",
    "/modules/lwjgl/spng/src/generated/c/",
    "/modules/lwjgl/nuklear/src/main/c/",
    "/modules/lwjgl/nuklear/src/generated/c/",
    "/modules/lwjgl/tinyfd/src/main/c/",
    "/modules/lwjgl/tinyfd/src/generated/c/",
    "/modules/lwjgl/zstd/src/main/c/",
    "/modules/lwjgl/zstd/src/generated/c/",
    "/modules/lwjgl/xxhash/src/main/c/",
    "/modules/lwjgl/shaderc/src/generated/java/",
    "/modules/lwjgl/spvc/src/generated/java/",
]

# LWJGL resolves these with apiGetFunctionAddress when the class loads: one
# missing name fails the whole binding, so the library must export every one.
FUNCTION_TABLES = {
    "libshaderc.so": "modules/lwjgl/shaderc/src/generated/java/org/lwjgl/util/shaderc/Shaderc.java",
    "libspirv-cross.so": "modules/lwjgl/spvc/src/generated/java/org/lwjgl/util/spvc/Spvc.java",
}
REQUIRED_FUNCTION = re.compile(r'apiGetFunctionAddress\(\w+, "(\w+)"\)')
JNI_ENTRY = re.compile(r"JNICALL\s+(Java_\w+)\s*\(")

COMPONENT_LIBRARIES = [
    "libSDL3.so",
    "liblwjgl.so",
    "liblwjgl_opengl.so",
    "liblwjgl_stb.so",
    "liblwjgl_spng.so",
    "liblwjgl_nuklear.so",
    "liblwjgl_tinyfd.so",
    "liblwjgl_zstd.so",
    "libshaderc.so",
    "libspirv-cross.so",
]

SDL_PATCHES = [
    (
        "src/core/android/SDL_android.c",
        "    JNIEnv *env = NULL;\n\n    mJavaVM = vm;\n",
        "    JNIEnv *env = NULL;\n\n"
        "    /* Millida: the game's own JVM lives in the same process. A second VM\n"
        "       must not replace the Android one SDL calls back into. */\n"
        "    if (mJavaVM && mJavaVM != vm) {\n"
        "        return JNI_VERSION_1_4;\n"
        "    }\n\n"
        "    mJavaVM = vm;\n",
    ),
    (
        "src/core/android/SDL_android.c",
        "    *buttonID = (*env)->CallIntMethod(env, context, mid,\n",
        "    /* Millida: the host activity has no message box of SDL's own; report\n"
        "       \"no button\" instead of calling a missing method. */\n"
        "    if (!mid) {\n"
        "        (*env)->ExceptionClear(env);\n"
        "        *buttonID = -1;\n"
        "    } else\n"
        "    *buttonID = (*env)->CallIntMethod(env, context, mid,\n",
    ),
    (
        "src/video/SDL_egl.c",
        "        // EGL 1.5 can use eglGetProcAddress() for any symbol. 1.4 and earlier can't use it for core entry points.\n",
        "#ifdef SDL_PLATFORM_ANDROID\n"
        "        /* Millida: with a GL translation layer named in SDL_OPENGL_LIBRARY,\n"
        "           desktop GL entry points must come from it, not from the system GLES\n"
        "           that eglGetProcAddress would return for shared names. */\n"
        "        if (!result && _this->egl_data->opengl_dll_handle && SDL_GetHint(SDL_HINT_OPENGL_LIBRARY)) {\n"
        "            result = SDL_LoadFunction(_this->egl_data->opengl_dll_handle, proc);\n"
        "        }\n"
        "#endif\n"
        "        // EGL 1.5 can use eglGetProcAddress() for any symbol. 1.4 and earlier can't use it for core entry points.\n",
    ),
    (
        "src/video/SDL_egl.c",
        "SDL_FunctionPointer SDL_EGL_GetProcAddressInternal(SDL_VideoDevice *_this, const char *proc)\n{\n",
        "#ifdef SDL_PLATFORM_ANDROID\n"
        "/* Millida: LWJGL builds its GL capabilities the way glXGetProcAddress works, where every\n"
        "   gl* name resolves. Android's EGL returns NULL for desktop-only entry points, and LWJGL\n"
        "   then drops whole GL versions of a non-forward-compatible context, core functions\n"
        "   included. Desktop-only names on a plain GLES context resolve to a call that does nothing. */\n"
        "static SDL_AtomicInt millida_missing_gl_calls;\n\n"
        "static uintptr_t SDLCALL Millida_MissingGLFunction(void)\n"
        "{\n"
        "    if (SDL_AddAtomicInt(&millida_missing_gl_calls, 1) == 0) {\n"
        "        SDL_Log(\"Millida: a desktop OpenGL function that GLES does not have was called and ignored\");\n"
        "    }\n"
        "    return 0;\n"
        "}\n"
        "#endif\n\n"
        "SDL_FunctionPointer SDL_EGL_GetProcAddressInternal(SDL_VideoDevice *_this, const char *proc)\n{\n",
    ),
    (
        "src/video/SDL_egl.c",
        "        if (!result && !is_egl_15_or_later && _this->egl_data->eglGetProcAddress) {\n"
        "            result = _this->egl_data->eglGetProcAddress(proc);\n"
        "        }\n"
        "    }\n"
        "    return result;\n"
        "}\n",
        "        if (!result && !is_egl_15_or_later && _this->egl_data->eglGetProcAddress) {\n"
        "            result = _this->egl_data->eglGetProcAddress(proc);\n"
        "        }\n"
        "#ifdef SDL_PLATFORM_ANDROID\n"
        "        if (!result && !SDL_GetHint(SDL_HINT_OPENGL_LIBRARY) && SDL_strncmp(proc, \"gl\", 2) == 0) {\n"
        "            result = (SDL_FunctionPointer)Millida_MissingGLFunction;\n"
        "        }\n"
        "#endif\n"
        "    }\n"
        "    return result;\n"
        "}\n",
    ),
    (
        "src/video/SDL_egl.c",
        "    attribs[i++] = EGL_RENDERABLE_TYPE;\n    if (_this->gl_config.profile_mask == SDL_GL_CONTEXT_PROFILE_ES) {\n",
        "    attribs[i++] = EGL_RENDERABLE_TYPE;\n"
        "#if defined(SDL_PLATFORM_ANDROID) && defined(EGL_KHR_create_context)\n"
        "    /* Millida: desktop GL is translated on top of GLES 3, so the config is a GLES 3 one. */\n"
        "    if (SDL_GetHint(SDL_HINT_OPENGL_LIBRARY) && SDL_EGL_HasExtension(_this, SDL_EGL_DISPLAY_EXTENSION, \"EGL_KHR_create_context\")) {\n"
        "        attribs[i++] = EGL_OPENGL_ES3_BIT_KHR;\n"
        "        _this->egl_data->eglBindAPI(EGL_OPENGL_ES_API);\n"
        "    } else\n"
        "#endif\n"
        "    if (_this->gl_config.profile_mask == SDL_GL_CONTEXT_PROFILE_ES) {\n",
    ),
    (
        "src/video/SDL_egl.c",
        "    bool profile_es = (profile_mask == SDL_GL_CONTEXT_PROFILE_ES);\n\n"
        "    if (!_this->egl_data) {\n"
        "        SDL_SetError(\"EGL not initialized\");\n"
        "        return NULL;\n"
        "    }\n",
        "    bool profile_es = (profile_mask == SDL_GL_CONTEXT_PROFILE_ES);\n\n"
        "    if (!_this->egl_data) {\n"
        "        SDL_SetError(\"EGL not initialized\");\n"
        "        return NULL;\n"
        "    }\n\n"
        "#if defined(SDL_PLATFORM_ANDROID) && defined(EGL_KHR_create_context)\n"
        "    /* Millida: the context matches the GLES 3 config above. Only the values sent\n"
        "       to EGL change; what SDL_GL_GetAttribute reports stays as requested. */\n"
        "    const bool millida_translated = SDL_GetHint(SDL_HINT_OPENGL_LIBRARY) != NULL &&\n"
        "        SDL_EGL_HasExtension(_this, SDL_EGL_DISPLAY_EXTENSION, \"EGL_KHR_create_context\");\n"
        "    if (millida_translated) {\n"
        "        profile_mask = SDL_GL_CONTEXT_PROFILE_ES;\n"
        "        profile_es = true;\n"
        "        major_version = 3;\n"
        "        minor_version = 0;\n"
        "    }\n"
        "#else\n"
        "    const bool millida_translated = false;\n"
        "#endif\n",
    ),
    (
        "src/video/SDL_egl.c",
        "            // SDL flags match EGL flags.\n            if (_this->gl_config.flags != 0) {\n",
        "            // SDL flags match EGL flags.\n            if (_this->gl_config.flags != 0 && !millida_translated) {\n",
    ),
    (
        "src/video/SDL_egl.c",
        "    if ((major_version < 3 || (minor_version == 0 && profile_es)) &&\n        _this->gl_config.flags == 0 &&\n",
        "    if ((major_version < 3 || (minor_version == 0 && profile_es)) &&\n        (_this->gl_config.flags == 0 || millida_translated) &&\n",
    ),
    (
        "src/video/android/SDL_androidgl.c",
        "    return SDL_EGL_LoadLibrary(_this, path, (NativeDisplayType)0, 0);\n",
        "    if (!SDL_EGL_LoadLibrary(_this, path, (NativeDisplayType)0, 0)) {\n"
        "        return false;\n"
        "    }\n"
        "    /* Millida: hidden helper windows get pbuffer surfaces, so the one config\n"
        "       chosen for every window must allow both kinds. */\n"
        "    _this->egl_data->egl_surfacetype = EGL_WINDOW_BIT | EGL_PBUFFER_BIT;\n"
        "    return true;\n",
    ),
    (
        "src/video/android/SDL_androidwindow.c",
        "bool Android_CreateWindow(SDL_VideoDevice *_this, SDL_Window *window, SDL_PropertiesID create_props)\n{\n",
        "/* Millida: games create a hidden window only to own a shared GL context.\n"
        "   Android has one surface, so such a window draws into a pbuffer instead. */\n"
        "static bool Android_CreateOffscreenWindow(SDL_VideoDevice *_this, SDL_Window *window)\n"
        "{\n"
        "    SDL_WindowData *data = (SDL_WindowData *)SDL_calloc(1, sizeof(*data));\n"
        "    if (!data) {\n"
        "        return false;\n"
        "    }\n"
        "#ifdef SDL_VIDEO_OPENGL_EGL\n"
        "    data->egl_surface = EGL_NO_SURFACE;\n"
        "    if (window->flags & SDL_WINDOW_OPENGL) {\n"
        "        data->egl_surface = SDL_EGL_CreateOffscreenSurface(_this, SDL_max(window->w, 1), SDL_max(window->h, 1));\n"
        "        if (data->egl_surface == EGL_NO_SURFACE) {\n"
        "            SDL_free(data);\n"
        "            return SDL_SetError(\"Could not create an offscreen surface for a hidden window\");\n"
        "        }\n"
        "    }\n"
        "#endif\n"
        "    window->internal = data;\n"
        "    return true;\n"
        "}\n\n"
        "bool Android_CreateWindow(SDL_VideoDevice *_this, SDL_Window *window, SDL_PropertiesID create_props)\n{\n",
    ),
    (
        "src/video/android/SDL_androidwindow.c",
        "    if (Android_Window) {\n        result = SDL_SetError(\"Android only supports one window\");\n        goto endfunction;\n    }\n",
        "    if (Android_Window && (window->flags & (SDL_WINDOW_HIDDEN | SDL_WINDOW_UTILITY))) {\n"
        "        result = Android_CreateOffscreenWindow(_this, window);\n"
        "        goto endfunction;\n"
        "    }\n\n"
        "    if (Android_Window) {\n        result = SDL_SetError(\"Android only supports one window\");\n        goto endfunction;\n    }\n",
    ),
    (
        "src/video/android/SDL_androidwindow.c",
        "    Android_LockActivityMutex();\n\n    if (window == Android_Window) {\n        Android_Window = NULL;\n",
        "    Android_LockActivityMutex();\n\n"
        "    if (window != Android_Window && window->internal) {\n"
        "        SDL_WindowData *offscreen = window->internal;\n"
        "#ifdef SDL_VIDEO_OPENGL_EGL\n"
        "        if (offscreen->egl_surface != EGL_NO_SURFACE) {\n"
        "            SDL_EGL_DestroySurface(_this, offscreen->egl_surface);\n"
        "        }\n"
        "#endif\n"
        "        SDL_free(offscreen);\n"
        "        window->internal = NULL;\n"
        "    }\n\n"
        "    if (window == Android_Window) {\n        Android_Window = NULL;\n",
    ),
]

SDL_EXPORTS = [
    "JNI_OnLoad",
    "SDL_Init",
    "SDL_CreateWindowWithProperties",
    "SDL_GL_CreateContext",
    "SDL_GL_GetProcAddress",
    "SDL_SetWindowRelativeMouseMode",
]

LWJGL_EXPORTS = {
    "liblwjgl.so": ["JNI_OnLoad", "Java_org_lwjgl_system_MemoryUtil_ngetPageSize", "Java_org_lwjgl_system_libffi_LibFFI_ffi_1get_1closure_1size"],
    "liblwjgl_opengl.so": ["Java_org_lwjgl_opengl_GL11C_nglGetString"],
    "liblwjgl_stb.so": ["Java_org_lwjgl_stb_STBImage_nstbi_1load_1from_1memory__JIJJJI"],
    "liblwjgl_spng.so": ["Java_org_lwjgl_util_spng_SPNG_spng_1ctx_1new"],
}


def fail(message):
    sys.exit("ошибка: " + message)


def run(command, cwd=None, env=None, log=None):
    print("$", " ".join(command), flush=True)
    if log is None:
        subprocess.run(command, cwd=cwd, env=env, check=True)
        return
    with open(log, "w", encoding="utf-8") as out:
        result = subprocess.run(command, cwd=cwd, env=env, stdout=out, stderr=subprocess.STDOUT)
    if result.returncode != 0:
        with open(log, encoding="utf-8", errors="replace") as source:
            tail = source.readlines()[-40:]
        fail("команда упала ({}):\n{}".format(result.returncode, "".join(tail)))


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch_pinned(name, source, cache):
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from make_runtime_components import fetch_pinned as fetch
    return fetch(name, source, cache)



def sparse_matches(target, sparse):
    path = os.path.join(target, ".git", "info", "sparse-checkout")
    if not sparse:
        return not os.path.isfile(path)
    if not os.path.isfile(path):
        return False
    with open(path, encoding="utf-8") as handle:
        return handle.read() == "\n".join(sparse) + "\n"


def git_at(repo, commit, target, sparse=None):
    if os.path.isdir(os.path.join(target, ".git")):
        head = subprocess.run(["git", "-C", target, "rev-parse", "HEAD"], capture_output=True, text=True)
        status = subprocess.run(["git", "-C", target, "status", "--porcelain"], capture_output=True, text=True)
        if head.returncode == 0 and head.stdout.strip() == commit and not status.stdout.strip() and sparse_matches(target, sparse):
            return target
        shutil.rmtree(target)
    os.makedirs(target)
    run(["git", "-C", target, "init", "-q"])
    run(["git", "-C", target, "remote", "add", "origin", repo])
    if sparse:
        run(["git", "-C", target, "config", "core.sparseCheckout", "true"])
        with open(os.path.join(target, ".git", "info", "sparse-checkout"), "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(sparse) + "\n")
    run(["git", "-C", target, "fetch", "-q", "--depth", "1", "--filter=blob:none", "origin", commit])
    run(["git", "-C", target, "checkout", "-q", "--detach", "FETCH_HEAD"])
    head = subprocess.run(["git", "-C", target, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    if head != commit:
        fail("{} отдал {} вместо закреплённого {}".format(repo, head, commit))
    return target


def source_at(pin, name, cache, sparse=None):
    return git_at(pin["repo"], pin["commit"], os.path.join(cache, "{}-{}".format(name, pin["commit"][:12])), sparse=sparse)


def apply_patches(root):
    for relative, old, new in SDL_PATCHES:
        path = os.path.join(root, relative)
        with open(path, encoding="utf-8", newline="") as handle:
            text = handle.read()
        if text.count(old) != 1:
            fail("правка SDL не нашла своё место ровно один раз в {}: {!r}".format(relative, old[:80]))
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text.replace(old, new, 1))
    print("правки SDL применены:", len(SDL_PATCHES))


def toolchain(ndk):
    if not ndk or not os.path.isdir(ndk):
        fail("не найден NDK (--ndk или ANDROID_NDK_HOME)")
    bin_dir = os.path.join(ndk, "toolchains", "llvm", "prebuilt", "linux-x86_64", "bin")
    cc = os.path.join(bin_dir, "aarch64-linux-android{}-clang".format(API_LEVEL))
    if not os.path.isfile(cc):
        fail("в NDK нет компилятора " + cc)
    return {
        "cc": cc,
        "ar": os.path.join(bin_dir, "llvm-ar"),
        "ranlib": os.path.join(bin_dir, "llvm-ranlib"),
        "nm": os.path.join(bin_dir, "llvm-nm"),
        "strip": os.path.join(bin_dir, "llvm-strip"),
    }


def cmake_android(cmake, ndk, source, build, options, logs, jobs, target=None):
    shutil.rmtree(build, ignore_errors=True)
    run([
        cmake, "-S", source, "-B", build, "-G", "Ninja",
        "-DCMAKE_TOOLCHAIN_FILE=" + os.path.join(ndk, "build", "cmake", "android.toolchain.cmake"),
        "-DANDROID_ABI=arm64-v8a",
        "-DANDROID_PLATFORM=android-{}".format(API_LEVEL),
        "-DANDROID_STL=c++_static",
        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
        "-DCMAKE_BUILD_TYPE=Release",
    ] + options, log=logs + "-configure.log")
    command = [cmake, "--build", build, "-j", str(jobs)]
    if target:
        command += ["--target", target]
    run(command, log=logs + "-build.log")
    return build


def built_library(build, pattern):
    found = sorted(path for path in glob.glob(os.path.join(build, "**", pattern), recursive=True)
                   if os.path.isfile(path) and not os.path.islink(path))
    if len(found) != 1:
        fail("сборка в {} оставила не одну {}: {}".format(build, pattern, found))
    return found[0]


def build_sdl(src, work, ndk, cmake, jobs):
    build = cmake_android(cmake, ndk, src, os.path.join(work, "sdl-build"), SDL_CMAKE, os.path.join(work, "sdl"), jobs)
    library = os.path.join(build, "libSDL3.so")
    if not os.path.isfile(library):
        fail("сборка SDL не оставила libSDL3.so")
    return library


def build_libffi(archive, work, tools, jobs):
    root = os.path.join(work, "libffi")
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root)
    with tarfile.open(archive, "r:gz") as tar:
        tar.extractall(root, filter="data")
    source = os.path.join(root, os.listdir(root)[0])
    build = os.path.join(root, "build")
    os.makedirs(build)
    env = dict(os.environ, CC=tools["cc"], AR=tools["ar"], RANLIB=tools["ranlib"], CFLAGS="-O2 -fPIC")
    run([
        os.path.join(source, "configure"),
        "--host=aarch64-linux-android",
        "--disable-shared",
        "--enable-static",
        "--with-pic",
        "--disable-docs",
        "--disable-multi-os-directory",
    ], cwd=build, env=env, log=os.path.join(work, "libffi-configure.log"))
    run(["make", "-j", str(jobs)], cwd=build, env=env, log=os.path.join(work, "libffi-build.log"))
    static = os.path.join(build, ".libs", "libffi.a")
    if not os.path.isfile(static):
        fail("сборка libffi не оставила libffi.a")
    return static


def lwjgl_library_name(module):
    return "liblwjgl.so" if module == "core" else "liblwjgl_{}.so".format(module)


def compile_module(name, sources, extra, links, lwjgl, work, tools, jobs, out_dir):
    objects_dir = os.path.join(work, "lwjgl-" + name)
    shutil.rmtree(objects_dir, ignore_errors=True)
    os.makedirs(objects_dir)
    core = os.path.join(lwjgl, "modules", "lwjgl", "core", "src", "main", "c")
    flags = [
        "-c", "-std=gnu11", "-O2", "-fPIC", "-pthread",
        "-DNDEBUG", "-DLWJGL_LINUX", "-DLWJGL_arm64",
        "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0", "-D_GNU_SOURCE", "-D_FILE_OFFSET_BITS=64",
        "-Wno-everything",
        "-I" + core, "-I" + os.path.join(core, "linux"),
    ] + extra
    pending = []
    objects = []
    for index, source in enumerate(sorted(sources)):
        target = os.path.join(objects_dir, "{:04d}-{}.o".format(index, os.path.splitext(os.path.basename(source))[0]))
        objects.append(target)
        pending.append(subprocess.Popen([tools["cc"]] + flags + [source, "-o", target]))
        if len(pending) >= jobs:
            wait(pending.pop(0), source)
    for process in pending:
        wait(process, name)
    library = os.path.join(out_dir, lwjgl_library_name(name))
    version_script = os.path.join(lwjgl, "config", "linux", "version.script")
    run([tools["cc"], "-shared", "-pthread", "-o", library, "-Wl,--no-undefined", "-Wl,-z,noexecstack", PAGE_ALIGN,
         "-Wl,--version-script," + version_script] + objects + links)
    run([tools["strip"], "--strip-unneeded", library])
    return library


def wait(process, what):
    if process.wait() != 0:
        fail("компиляция LWJGL упала на " + what)


def listing(directory, suffix, exclude=()):
    if not os.path.isdir(directory):
        fail("нет папки исходников " + directory)
    return [os.path.join(directory, name) for name in os.listdir(directory)
            if name.endswith(suffix) and not any(part in name for part in exclude)]


def jni_entry_points(sources):
    names = set()
    for source in sources:
        with open(source, encoding="utf-8", errors="replace") as handle:
            names.update(JNI_ENTRY.findall(handle.read()))
    return sorted(names)


def build_lwjgl(lwjgl, ffi, work, tools, jobs, out_dir):
    modules = os.path.join(lwjgl, "modules", "lwjgl")

    def src(module, *parts):
        return os.path.join(modules, module, "src", *parts)

    core_main = src("core", "main", "c")
    core_generated = src("core", "generated", "c")
    core_sources = (
        listing(core_main, ".c")
        + listing(core_generated, ".c")
        + listing(os.path.join(core_generated, "linux"), ".c", exclude=("liburing",))
    )
    compile_module("core", core_sources, [
        "-I" + os.path.join(core_main, "libffi"),
        "-I" + os.path.join(core_main, "libffi", "aarch64"),
    ], [ffi, "-ldl"], lwjgl, work, tools, jobs, out_dir)

    compile_module("opengl", listing(src("opengl", "generated", "c"), ".c", exclude=("_WGL",)),
                   ["-I" + src("opengl", "main", "c")], [], lwjgl, work, tools, jobs, out_dir)

    compile_module("stb", listing(src("stb", "generated", "c"), ".c"),
                   ["-isystem", src("stb", "main", "c")], ["-lm"], lwjgl, work, tools, jobs, out_dir)

    miniz = os.path.join(core_main, "dependencies", "miniz")
    compile_module("spng", listing(src("spng", "generated", "c"), ".c") + listing(miniz, ".c"),
                   ["-I" + src("spng", "main", "c"), "-I" + miniz], ["-lm"], lwjgl, work, tools, jobs, out_dir)

    for name, symbols in LWJGL_EXPORTS.items():
        check_elf(os.path.join(out_dir, name))
        check_exports(os.path.join(out_dir, name), symbols, tools)

    zstd = src("zstd", "main", "c")
    xxhash = src("xxhash", "main", "c")
    jni_only = {
        "nuklear": ([], ["-I" + src("nuklear", "main", "c")], ["-lm"]),
        "tinyfd": (listing(src("tinyfd", "main", "c"), ".c"), ["-I" + src("tinyfd", "main", "c")], []),
        "zstd": (
            [path for part in ("common", "compress", "decompress", "dictBuilder") for path in listing(os.path.join(zstd, part), ".c")]
            + [os.path.join(xxhash, "xxhash.c")],
            # bionic has no qsort_r; zstd falls back to plain qsort only without _GNU_SOURCE.
            ["-U_GNU_SOURCE", "-DZSTD_MULTITHREAD", "-I" + zstd, "-I" + xxhash],
            [],
        ),
    }
    for name, (library_sources, extra, links) in jni_only.items():
        generated = listing(src(name, "generated", "c"), ".c")
        entry_points = jni_entry_points(generated)
        if not entry_points:
            fail("в исходниках LWJGL {} не нашлось ни одной функции JNI".format(name))
        library = compile_module(name, generated + library_sources, extra, links, lwjgl, work, tools, jobs, out_dir)
        check_elf(library)
        check_exports(library, entry_points, tools)


def lwjgl_functions(lwjgl, relative):
    with open(os.path.join(lwjgl, relative), encoding="utf-8") as handle:
        names = REQUIRED_FUNCTION.findall(handle.read())
    if not names:
        fail("в {} не нашлось обязательных функций: разбор привязки LWJGL устарел".format(relative))
    return names


def build_shaderc(pins, lwjgl, cache, work, ndk, cmake, jobs, out_dir):
    tools = toolchain(ndk)
    shaderc = source_at(pins["shaderc"], "shaderc", cache)
    options = SHADERC_CMAKE + [
        "-DSHADERC_GLSLANG_DIR=" + source_at(pins["glslang"], "glslang", cache),
        "-DSHADERC_SPIRV_TOOLS_DIR=" + source_at(pins["spirv-tools"], "spirv-tools", cache),
        "-DSHADERC_SPIRV_HEADERS_DIR=" + source_at(pins["spirv-headers"], "spirv-headers", cache),
        "-DPython_EXECUTABLE=" + sys.executable,
        "-DPython3_EXECUTABLE=" + sys.executable,
    ]
    build = cmake_android(cmake, ndk, shaderc, os.path.join(work, "shaderc-build"), options,
                          os.path.join(work, "shaderc"), jobs, target="shaderc_shared")
    library = os.path.join(out_dir, "libshaderc.so")
    shutil.copyfile(built_library(build, "libshaderc_shared.so*"), library)
    run([tools["strip"], "--strip-unneeded", library])
    check_elf(library)
    check_exports(library, lwjgl_functions(lwjgl, FUNCTION_TABLES["libshaderc.so"]), tools)


def build_spirv_cross(pin, lwjgl, cache, work, ndk, cmake, jobs, out_dir):
    tools = toolchain(ndk)
    source = source_at(pin, "spirv-cross", cache)
    build = cmake_android(cmake, ndk, source, os.path.join(work, "spirv-cross-build"), SPIRV_CROSS_CMAKE,
                          os.path.join(work, "spirv-cross"), jobs, target="spirv-cross-c-shared")
    library = os.path.join(out_dir, "libspirv-cross.so")
    shutil.copyfile(built_library(build, "libspirv-cross-c-shared.so*"), library)
    run([tools["strip"], "--strip-unneeded", library])
    check_elf(library)
    check_exports(library, lwjgl_functions(lwjgl, FUNCTION_TABLES["libspirv-cross.so"]), tools)


def check_elf(path):
    with open(path, "rb") as handle:
        header = handle.read(20)
    if header[:4] != b"\x7fELF" or header[4] != 2 or int.from_bytes(header[18:20], "little") != EM_AARCH64:
        fail(os.path.basename(path) + " — не 64-битный ELF под aarch64")


def check_exports(path, names, tools):
    result = subprocess.run([tools["nm"], "-D", "--defined-only", path], capture_output=True, text=True, check=True)
    # SDL ships a version script, so nm prints names like SDL_Init@@SDL3_0.0.0.
    exported = {line.split()[-1].split("@", 1)[0] for line in result.stdout.splitlines() if line.strip()}
    missing = [name for name in names if name not in exported]
    if missing:
        fail("{} не экспортирует {}".format(os.path.basename(path), ", ".join(missing)))


def part_key(name, pins, recipe):
    digest = hashlib.sha256()
    digest.update(json.dumps({"part": name, "pins": pins, "api": API_LEVEL, "format": BUILD_FORMAT}, sort_keys=True).encode("utf-8"))
    for item in recipe:
        text = inspect.getsource(item) if callable(item) else json.dumps(item, sort_keys=True)
        digest.update(text.encode("utf-8"))
    return digest.hexdigest()[:24]


def cached_part(cache, name, key, build):
    done = os.path.join(cache, "part-{}-{}".format(name, key))
    if os.path.isfile(os.path.join(done, PART_DONE)):
        print("часть {} уже собрана из тех же источников: {}".format(name, done))
        return done
    work = os.path.join(cache, "work-" + name)
    staging = done + ".part"
    for path in (work, staging):
        shutil.rmtree(path, ignore_errors=True)
        os.makedirs(path)
    build(work, staging)
    with open(os.path.join(staging, PART_DONE), "w", encoding="utf-8", newline="\n") as handle:
        handle.write(key + "\n")
    shutil.rmtree(work)
    for stale in glob.glob(os.path.join(cache, "part-{}-*".format(name))):
        if stale != staging:
            shutil.rmtree(stale, ignore_errors=True)
    os.replace(staging, done)
    print("часть {} собрана: {}".format(name, done))
    return done


def main():
    parser = argparse.ArgumentParser(description="Собирает компонент SDL3: libSDL3.so и LWJGL той же версии под arm64-v8a")
    parser.add_argument("--sources", required=True)
    parser.add_argument("--glue", required=True, help="app/src/main/java/org/libsdl/app")
    parser.add_argument("--ndk", default=os.environ.get("ANDROID_NDK_HOME", ""))
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--cache", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()

    with open(args.sources, encoding="utf-8") as handle:
        sources = json.load(handle)
    pins = sources["builds"]["sdl3"]
    os.makedirs(args.cache, exist_ok=True)
    for legacy in glob.glob(os.path.join(args.cache, "sdl3-build-*")) + [os.path.join(args.cache, "sdl3-work")]:
        shutil.rmtree(legacy, ignore_errors=True)

    sdl_src = source_at(pins["sdl"], "sdl", args.cache)
    run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "check_sdl_glue.py"), "--sdl", sdl_src, "--glue", args.glue])
    lwjgl = source_at(pins["lwjgl"], "lwjgl", args.cache, sparse=LWJGL_SPARSE)

    def sdl_part(work, out_dir):
        tools = toolchain(args.ndk)
        patched = os.path.join(work, "sdl")
        shutil.copytree(sdl_src, patched, ignore=shutil.ignore_patterns(".git"))
        apply_patches(patched)
        library = os.path.join(out_dir, "libSDL3.so")
        shutil.copyfile(build_sdl(patched, work, args.ndk, args.cmake, args.jobs), library)
        run([tools["strip"], "--strip-unneeded", library])
        check_elf(library)
        check_exports(library, SDL_EXPORTS, tools)

    def lwjgl_part(work, out_dir):
        tools = toolchain(args.ndk)
        ffi = build_libffi(fetch_pinned(pins["libffi"], sources["downloads"][pins["libffi"]], args.cache), work, tools, args.jobs)
        build_lwjgl(lwjgl, ffi, work, tools, args.jobs, out_dir)

    def shaderc_part(work, out_dir):
        build_shaderc(pins["shaderc"], lwjgl, args.cache, work, args.ndk, args.cmake, args.jobs, out_dir)

    def spirv_cross_part(work, out_dir):
        build_spirv_cross(pins["spirvCross"], lwjgl, args.cache, work, args.ndk, args.cmake, args.jobs, out_dir)

    checks = [run, toolchain, check_elf, check_exports]
    lwjgl_tables = [FUNCTION_TABLES, REQUIRED_FUNCTION.pattern, lwjgl_functions, cmake_android, built_library]
    parts = [
        ("sdl", {"sdl": pins["sdl"]},
         checks + [SDL_PATCHES, SDL_EXPORTS, SDL_CMAKE, apply_patches, cmake_android, build_sdl, sdl_part], sdl_part),
        ("lwjgl", {"lwjgl": pins["lwjgl"], "libffi": sources["downloads"][pins["libffi"]], "sparse": LWJGL_SPARSE},
         checks + [LWJGL_EXPORTS, JNI_ENTRY.pattern, build_libffi, lwjgl_library_name, compile_module, listing,
                   jni_entry_points, build_lwjgl, lwjgl_part], lwjgl_part),
        ("shaderc", {"shaderc": pins["shaderc"], "lwjgl": pins["lwjgl"]},
         checks + lwjgl_tables + [SHADERC_CMAKE, build_shaderc, shaderc_part], shaderc_part),
        ("spirv-cross", {"spirvCross": pins["spirvCross"], "lwjgl": pins["lwjgl"]},
         checks + lwjgl_tables + [SPIRV_CROSS_CMAKE, build_spirv_cross, spirv_cross_part], spirv_cross_part),
    ]
    built = [cached_part(args.cache, name, part_key(name, part_pins, recipe), build) for name, part_pins, recipe, build in parts]

    shutil.rmtree(args.out, ignore_errors=True)
    os.makedirs(args.out)
    for folder in built:
        for name in sorted(os.listdir(folder)):
            if name == PART_DONE:
                continue
            target = os.path.join(args.out, name)
            if os.path.exists(target):
                fail("{} собирается двумя частями сразу".format(name))
            shutil.copyfile(os.path.join(folder, name), target)
    missing = [name for name in COMPONENT_LIBRARIES if not os.path.isfile(os.path.join(args.out, name))]
    if missing:
        fail("в компоненте SDL3 нет " + ", ".join(missing))
    for name in COMPONENT_LIBRARIES:
        check_elf(os.path.join(args.out, name))

    with open(os.path.join(args.out, "millida-sdl.json"), "w", encoding="utf-8", newline="\n") as handle:
        json.dump({"sdlVersion": pins["sdl"]["version"], "lwjglVersion": pins["lwjgl"]["version"],
                   "sdlCommit": pins["sdl"]["commit"], "lwjglCommit": pins["lwjgl"]["commit"],
                   "shadercVersion": pins["shaderc"]["shaderc"]["version"], "spirvCrossVersion": pins["spirvCross"]["version"]},
                  handle, indent=2)
        handle.write("\n")
    for jar in pins["jars"]:
        shutil.copyfile(fetch_pinned(jar, sources["downloads"][jar], args.cache), os.path.join(args.out, jar))
    print("компонент SDL3 готов:", args.out)


if __name__ == "__main__":
    main()
