#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SHIM_EXPORT __attribute__((visibility("default")))
#define SHIM_LOG_TAG "millida"
#define REAL_LIBRARY_VARIABLE "MILLIDA_GL_LIBRARY"

typedef void* (*LookupFn)(const char*);

static struct {
    void* library;
    LookupFn lookup;
} real;

static pthread_once_t real_once = PTHREAD_ONCE_INIT;
static atomic_bool stub_logged;

static void report(const char* text, const char* detail) {
    __android_log_print(ANDROID_LOG_ERROR, SHIM_LOG_TAG, "glx shim: %s %s", text, detail);
    fprintf(stderr, "[millida] glx shim: %s %s\n", text, detail);
}

/*
 * LWJGL 3.4 asks a context that is not forward compatible for the deprecated entry points
 * of a GL version first and, when one of them is missing, never looks the core ones up.
 * The translation layers lack the packed vertex attributes of GL 3.3, so every core entry
 * point of 3.3 stayed empty and the first glVertexAttribDivisor aborted the game. A desktop
 * driver resolves each of these names; here they resolve to a call that does nothing, and
 * a core-profile game never makes it.
 */
static void packed_attribute_stub(void) {
    if (!atomic_exchange(&stub_logged, true)) {
        report("a packed vertex attribute call of GL 3.3 was made and ignored", "");
    }
}

static bool is_packed_attribute(const char* name) {
    static const char* const families[] = {
        "glVertexP", "glTexCoordP", "glMultiTexCoordP", "glNormalP", "glColorP", "glSecondaryColorP",
    };
    for (size_t index = 0; index < sizeof(families) / sizeof(families[0]); index++) {
        const size_t length = strlen(families[index]);
        if (strncmp(name, families[index], length) != 0) continue;
        const char* rest = name + length;
        if (rest[0] < '1' || rest[0] > '4') continue;
        if (strcmp(rest + 1, "ui") == 0 || strcmp(rest + 1, "uiv") == 0) return true;
    }
    return false;
}

static void resolve_real(void) {
    static const char* const lookups[] = {
        "glXGetProcAddress", "glXGetProcAddressARB", "eglGetProcAddress", "OSMesaGetProcAddress",
    };
    const char* path = getenv(REAL_LIBRARY_VARIABLE);
    if (path == NULL || path[0] == '\0') {
        report(REAL_LIBRARY_VARIABLE, "is not set: the game gets no OpenGL functions");
        return;
    }
    real.library = dlopen(path, RTLD_LAZY | RTLD_LOCAL);
    if (real.library == NULL) {
        const char* reason = dlerror();
        report("the renderer did not open:", reason != NULL ? reason : path);
        return;
    }
    for (size_t index = 0; index < sizeof(lookups) / sizeof(lookups[0]) && real.lookup == NULL; index++) {
        real.lookup = (LookupFn) dlsym(real.library, lookups[index]);
    }
}

SHIM_EXPORT void* glXGetProcAddress(const char* name) {
    if (name == NULL) return NULL;
    pthread_once(&real_once, resolve_real);
    if (real.library == NULL) return NULL;
    void* found = real.lookup != NULL ? real.lookup(name) : NULL;
    if (found == NULL) found = dlsym(real.library, name);
    if (found == NULL && is_packed_attribute(name)) found = (void*) packed_attribute_stub;
    return found;
}

SHIM_EXPORT void* glXGetProcAddressARB(const char* name) {
    return glXGetProcAddress(name);
}
