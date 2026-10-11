#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define SHIM_EXPORT __attribute__((visibility("default")))
#define SHIM_LOG_TAG "millida"

static int trace_target(void);

enum {
    FORMAT_BGRA = 0x80E1,
    INTERNAL_BGRA8 = 0x93A1,
    TYPE_UNSIGNED_INT_8_8_8_8_REV = 0x8367,
    BYTES_PER_PIXEL = 4,
};

typedef void (*TexImage2DFn)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void*);
typedef void (*TexSubImage2DFn)(GLenum, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, const void*);
typedef void* (*MapBufferRangeFn)(GLenum, GLintptr, GLsizeiptr, GLbitfield);
typedef GLboolean (*UnmapBufferFn)(GLenum);
typedef __eglMustCastToProperFunctionPointerType (*GetProcAddressFn)(const char*);

static struct {
    TexImage2DFn tex_image;
    TexSubImage2DFn tex_sub_image;
    MapBufferRangeFn map_buffer_range;
    UnmapBufferFn unmap_buffer;
    GetProcAddressFn get_proc_address;
    void* egl;
    void* gles;
} real;

static pthread_once_t real_once = PTHREAD_ONCE_INIT;
static pthread_once_t scratch_once = PTHREAD_ONCE_INIT;
static pthread_key_t scratch_key;
static atomic_bool scratch_ready;
static atomic_bool converted_logged;
static atomic_bool fallback_logged;
static atomic_bool missing_logged;

typedef struct {
    GLint buffer;
    GLint row_length;
    GLint skip_rows;
    GLint skip_pixels;
    GLint alignment;
} Unpack;

typedef struct {
    size_t first;
    size_t stride;
    size_t span;
} Layout;

typedef struct {
    uint8_t* data;
    size_t capacity;
} Scratch;

static void* symbol(void* library, const char* name) {
    return library == NULL ? NULL : dlsym(library, name);
}

static void resolve_real(void) {
    void* egl = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    void* gles = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
    real.egl = egl;
    real.gles = gles;
    real.get_proc_address = (GetProcAddressFn)symbol(egl, "eglGetProcAddress");
    real.tex_image = (TexImage2DFn)symbol(gles, "glTexImage2D");
    real.tex_sub_image = (TexSubImage2DFn)symbol(gles, "glTexSubImage2D");
    real.map_buffer_range = (MapBufferRangeFn)symbol(gles, "glMapBufferRange");
    real.unmap_buffer = (UnmapBufferFn)symbol(gles, "glUnmapBuffer");
    if (real.get_proc_address != NULL) {
        if (real.map_buffer_range == NULL) {
            real.map_buffer_range = (MapBufferRangeFn)real.get_proc_address("glMapBufferRange");
        }
        if (real.unmap_buffer == NULL) {
            real.unmap_buffer = (UnmapBufferFn)real.get_proc_address("glUnmapBuffer");
        }
    }
}

static int resolved(void) {
    pthread_once(&real_once, resolve_real);
    if (real.tex_image != NULL && real.tex_sub_image != NULL) return 1;
    if (!atomic_exchange(&missing_logged, true)) {
        __android_log_print(ANDROID_LOG_ERROR, SHIM_LOG_TAG, "GLES shim: glTexImage2D/glTexSubImage2D not found in libGLESv2.so");
    }
    return 0;
}

static void scratch_release(void* value) {
    Scratch* scratch = value;
    free(scratch->data);
    free(scratch);
}

static void scratch_key_create(void) {
    atomic_store(&scratch_ready, pthread_key_create(&scratch_key, scratch_release) == 0);
}

static uint8_t* scratch_take(size_t size) {
    pthread_once(&scratch_once, scratch_key_create);
    if (!atomic_load(&scratch_ready)) return NULL;
    Scratch* scratch = pthread_getspecific(scratch_key);
    if (scratch == NULL) {
        scratch = calloc(1, sizeof(*scratch));
        if (scratch == NULL) return NULL;
        if (pthread_setspecific(scratch_key, scratch) != 0) {
            free(scratch);
            return NULL;
        }
    }
    if (scratch->capacity < size) {
        uint8_t* grown = realloc(scratch->data, size);
        if (grown == NULL) return NULL;
        scratch->data = grown;
        scratch->capacity = size;
    }
    return scratch->data;
}

static int is_bgra_upload(GLenum format, GLenum type) {
    return format == FORMAT_BGRA && (type == GL_UNSIGNED_BYTE || type == TYPE_UNSIGNED_INT_8_8_8_8_REV);
}

static GLint rgba_internal_format(GLint internal_format) {
    if (internal_format == FORMAT_BGRA) return GL_RGBA;
    if (internal_format == INTERNAL_BGRA8) return GL_RGBA8;
    return internal_format;
}

static Unpack unpack_read(void) {
    Unpack unpack = {0, 0, 0, 0, 4};
    glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpack.buffer);
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &unpack.row_length);
    glGetIntegerv(GL_UNPACK_SKIP_ROWS, &unpack.skip_rows);
    glGetIntegerv(GL_UNPACK_SKIP_PIXELS, &unpack.skip_pixels);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpack.alignment);
    return unpack;
}

static void unpack_tighten(const Unpack* unpack) {
    if (unpack->buffer != 0) glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
    if (unpack->row_length != 0) glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    if (unpack->skip_rows != 0) glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
    if (unpack->skip_pixels != 0) glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
    if (unpack->alignment != 4) glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
}

static void unpack_restore(const Unpack* unpack) {
    if (unpack->buffer != 0) glBindBuffer(GL_PIXEL_UNPACK_BUFFER, (GLuint)unpack->buffer);
    if (unpack->row_length != 0) glPixelStorei(GL_UNPACK_ROW_LENGTH, unpack->row_length);
    if (unpack->skip_rows != 0) glPixelStorei(GL_UNPACK_SKIP_ROWS, unpack->skip_rows);
    if (unpack->skip_pixels != 0) glPixelStorei(GL_UNPACK_SKIP_PIXELS, unpack->skip_pixels);
    if (unpack->alignment != 4) glPixelStorei(GL_UNPACK_ALIGNMENT, unpack->alignment);
}

static int source_layout(const Unpack* unpack, GLsizei width, GLsizei height, Layout* out) {
    if (unpack->row_length < 0 || unpack->skip_rows < 0 || unpack->skip_pixels < 0) return 0;
    const size_t row_pixels = unpack->row_length > 0 ? (size_t)unpack->row_length : (size_t)width;
    const size_t alignment = unpack->alignment > 0 ? (size_t)unpack->alignment : 4;
    const size_t stride = (row_pixels * BYTES_PER_PIXEL + alignment - 1) / alignment * alignment;
    out->first = (size_t)unpack->skip_rows * stride + (size_t)unpack->skip_pixels * BYTES_PER_PIXEL;
    out->stride = stride;
    out->span = out->first + (size_t)(height - 1) * stride + (size_t)width * BYTES_PER_PIXEL;
    return 1;
}

static void swizzle_bgra_to_rgba(const uint8_t* source, size_t stride, uint8_t* target, size_t width, size_t height) {
    for (size_t y = 0; y < height; ++y) {
        const uint8_t* in = source + y * stride;
        uint8_t* out = target + y * width * BYTES_PER_PIXEL;
        for (size_t x = 0; x < width; ++x) {
            const size_t at = x * BYTES_PER_PIXEL;
            out[at] = in[at + 2];
            out[at + 1] = in[at + 1];
            out[at + 2] = in[at];
            out[at + 3] = in[at + 3];
        }
    }
}

/*
 * A bound unpack buffer is read through a map instead of being passed on:
 * the driver cannot swizzle server-side data for a format it rejects.
 */
static const uint8_t* to_rgba(const void* pixels, GLsizei width, GLsizei height, const Unpack* unpack) {
    Layout layout;
    if (!source_layout(unpack, width, height, &layout)) return NULL;
    uint8_t* target = scratch_take((size_t)width * (size_t)height * BYTES_PER_PIXEL);
    if (target == NULL) return NULL;
    if (unpack->buffer == 0) {
        swizzle_bgra_to_rgba((const uint8_t*)pixels + layout.first, layout.stride, target, (size_t)width, (size_t)height);
        return target;
    }
    if (real.map_buffer_range == NULL || real.unmap_buffer == NULL) return NULL;
    GLint size = 0;
    glGetBufferParameteriv(GL_PIXEL_UNPACK_BUFFER, GL_BUFFER_SIZE, &size);
    const size_t offset = (size_t)(uintptr_t)pixels;
    if (size <= 0 || offset + layout.span > (size_t)size) return NULL;
    const uint8_t* mapped = real.map_buffer_range(
        GL_PIXEL_UNPACK_BUFFER, (GLintptr)(offset + layout.first), (GLsizeiptr)(layout.span - layout.first), GL_MAP_READ_BIT);
    if (mapped == NULL) return NULL;
    swizzle_bgra_to_rgba(mapped, layout.stride, target, (size_t)width, (size_t)height);
    real.unmap_buffer(GL_PIXEL_UNPACK_BUFFER);
    return target;
}

static void report_converted(GLsizei width, GLsizei height, const Unpack* unpack) {
    if (atomic_exchange(&converted_logged, true)) return;
    __android_log_print(ANDROID_LOG_INFO, SHIM_LOG_TAG, "GLES shim: first BGRA upload swizzled to RGBA, %dx%d, unpack buffer %d",
                        width, height, unpack->buffer);
}

static void report_fallback(GLsizei width, GLsizei height, const Unpack* unpack) {
    if (atomic_exchange(&fallback_logged, true)) return;
    __android_log_print(ANDROID_LOG_WARN, SHIM_LOG_TAG, "GLES shim: BGRA upload %dx%d passed as RGBA without swizzle, unpack buffer %d",
                        width, height, unpack->buffer);
}

static atomic_int uploads_traced;

static void trace_upload(const char* call, GLint internalformat, GLenum format, GLenum type, GLsizei width, GLsizei height) {
    if (atomic_fetch_add(&uploads_traced, 1) >= 30) return;
    fprintf(stderr, "[millida] gles shim %s internal=0x%x format=0x%x type=0x%x %dx%d\n", call, (unsigned)internalformat,
            (unsigned)format, (unsigned)type, (int)width, (int)height);
    fflush(stderr);
}

SHIM_EXPORT void GL_APIENTRY glTexImage2D(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height,
                                          GLint border, GLenum format, GLenum type, const void* pixels) {
    if (trace_target() >= 0) trace_upload("glTexImage2D", internalformat, format, type, width, height);
    if (!resolved()) return;
    if (!is_bgra_upload(format, type)) {
        real.tex_image(target, level, internalformat, width, height, border, format, type, pixels);
        return;
    }
    const GLint internal = rgba_internal_format(internalformat);
    const Unpack unpack = unpack_read();
    if (width <= 0 || height <= 0 || (pixels == NULL && unpack.buffer == 0)) {
        real.tex_image(target, level, internal, width, height, border, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return;
    }
    const uint8_t* rgba = to_rgba(pixels, width, height, &unpack);
    if (rgba == NULL) {
        report_fallback(width, height, &unpack);
        real.tex_image(target, level, internal, width, height, border, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return;
    }
    report_converted(width, height, &unpack);
    unpack_tighten(&unpack);
    real.tex_image(target, level, internal, width, height, border, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    unpack_restore(&unpack);
}

SHIM_EXPORT void GL_APIENTRY glTexSubImage2D(GLenum target, GLint level, GLint xoffset, GLint yoffset, GLsizei width,
                                             GLsizei height, GLenum format, GLenum type, const void* pixels) {
    if (trace_target() >= 0) trace_upload("glTexSubImage2D", 0, format, type, width, height);
    if (!resolved()) return;
    if (!is_bgra_upload(format, type)) {
        real.tex_sub_image(target, level, xoffset, yoffset, width, height, format, type, pixels);
        return;
    }
    const Unpack unpack = unpack_read();
    if (width <= 0 || height <= 0 || (pixels == NULL && unpack.buffer == 0)) {
        real.tex_sub_image(target, level, xoffset, yoffset, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return;
    }
    const uint8_t* rgba = to_rgba(pixels, width, height, &unpack);
    if (rgba == NULL) {
        report_fallback(width, height, &unpack);
        real.tex_sub_image(target, level, xoffset, yoffset, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        return;
    }
    report_converted(width, height, &unpack);
    unpack_tighten(&unpack);
    real.tex_sub_image(target, level, xoffset, yoffset, width, height, GL_RGBA, GL_UNSIGNED_BYTE, rgba);
    unpack_restore(&unpack);
}

/*
 * SDL takes eglGetProcAddress from the library named by SDL_EGL_LIBRARY and,
 * on EGL 1.5, asks it for core GL names before trying a plain dlsym.
 */
static atomic_int lookups;
static atomic_int misses;

// The game log only carries stdout/stderr, so the lookup trace goes there as well as logcat.
static void trace(const char* format, const char* name, const void* address) {
    fprintf(stderr, format, name, address);
    fputc(10, stderr);
    fflush(stderr);
    __android_log_print(ANDROID_LOG_INFO, SHIM_LOG_TAG, format, name, address);
}

__attribute__((constructor)) static void announce(void) {
    pthread_once(&real_once, resolve_real);
    trace("[millida] gles shim loaded %s egl=%p", "", real.egl);
}

typedef void (*DrawArraysFn)(GLenum, GLint, GLsizei);
typedef void (*DrawElementsFn)(GLenum, GLsizei, GLenum, const void*);
typedef void (*DrawElementsBaseVertexFn)(GLenum, GLsizei, GLenum, const void*, GLint);
typedef void (*DrawArraysInstancedFn)(GLenum, GLint, GLsizei, GLsizei);
typedef void (*DrawElementsInstancedFn)(GLenum, GLsizei, GLenum, const void*, GLsizei);
typedef EGLBoolean (*SwapBuffersFn)(EGLDisplay, EGLSurface);
typedef EGLBoolean (*MakeCurrentFn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);

static DrawArraysFn real_draw_arrays;
static DrawElementsFn real_draw_elements;
static DrawElementsBaseVertexFn real_draw_elements_base_vertex;
static DrawArraysInstancedFn real_draw_arrays_instanced;
static DrawElementsInstancedFn real_draw_elements_instanced;

// MILLIDA_GL_TRACE=<frame> turns the draw trace on: a summary every 120 frames
// and every draw of that one frame, for a device where only the log can see.
static int trace_frame = -2;
static atomic_int frame_number;
static atomic_int frame_draws;
static atomic_int frame_errors;
static atomic_int frame_offscreen;
static atomic_int frame_no_program;
static atomic_int current_logged;

static int trace_target(void) {
    if (trace_frame == -2) {
        const char* value = getenv("MILLIDA_GL_TRACE");
        trace_frame = value == NULL ? -1 : atoi(value);
    }
    return trace_frame;
}

static void note_draw(const char* call, GLenum mode, GLsizei count, GLsizei instances) {
    GLint program = 0;
    GLint vao = 0;
    GLint fbo = 0;
    glGetIntegerv(GL_CURRENT_PROGRAM, &program);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &fbo);
    const GLenum error = glGetError();
    atomic_fetch_add(&frame_draws, 1);
    if (error != GL_NO_ERROR) atomic_fetch_add(&frame_errors, 1);
    if (fbo != 0) atomic_fetch_add(&frame_offscreen, 1);
    if (program == 0) atomic_fetch_add(&frame_no_program, 1);
    if (atomic_load(&frame_number) != trace_target()) return;
    GLint viewport[4] = {0, 0, 0, 0};
    GLboolean depth_mask = GL_TRUE;
    GLboolean color_mask[4] = {GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE};
    glGetIntegerv(GL_VIEWPORT, viewport);
    glGetBooleanv(GL_DEPTH_WRITEMASK, &depth_mask);
    glGetBooleanv(GL_COLOR_WRITEMASK, color_mask);
    fprintf(stderr,
            "[millida] gl draw %s mode=0x%x count=%d inst=%d prog=%d vao=%d fbo=%d err=0x%x vp=%d,%d,%d,%d depth=%d blend=%d cull=%d scissor=%d dmask=%d cmask=%d%d%d%d\n",
            call, (unsigned)mode, (int)count, (int)instances, program, vao, fbo, (unsigned)error, viewport[0], viewport[1],
            viewport[2], viewport[3], glIsEnabled(GL_DEPTH_TEST), glIsEnabled(GL_BLEND), glIsEnabled(GL_CULL_FACE),
            glIsEnabled(GL_SCISSOR_TEST), depth_mask, color_mask[0], color_mask[1], color_mask[2], color_mask[3]);
}

static void GL_APIENTRY traced_draw_arrays(GLenum mode, GLint first, GLsizei count) {
    real_draw_arrays(mode, first, count);
    note_draw("glDrawArrays", mode, count, 1);
}

// Set when a frame binds an offscreen framebuffer for drawing, cleared at every swap.
static atomic_int frame_bound_offscreen;
static atomic_int blits_skipped;

/*
 * With fboEnable off the frame is drawn straight into the window, yet a
 * full-screen copy of the game's framebuffer texture still comes at the end of
 * the frame, and that texture holds nothing: the window ended up black. A
 * frame that never bound an offscreen framebuffer has nothing to present, so
 * that copy is the one draw left out. It is recognised the way the game issues
 * it: one quad, no depth test, alpha writes off.
 */
enum { PRESENT_TEXTURES = 64 };
static _Atomic GLuint framebuffer_colors[PRESENT_TEXTURES];
static atomic_int framebuffer_colors_next;

static void remember_framebuffer_color(GLuint texture) {
    if (texture == 0) return;
    for (int index = 0; index < PRESENT_TEXTURES; ++index) {
        if (atomic_load(&framebuffer_colors[index]) == texture) return;
    }
    const int slot = atomic_fetch_add(&framebuffer_colors_next, 1) % PRESENT_TEXTURES;
    atomic_store(&framebuffer_colors[slot], texture);
}

static int is_framebuffer_color(GLuint texture) {
    if (texture == 0) return 0;
    for (int index = 0; index < PRESENT_TEXTURES; ++index) {
        if (atomic_load(&framebuffer_colors[index]) == texture) return 1;
    }
    return 0;
}

// Only a quad that samples a framebuffer's colour texture counts: a GUI quad
// drawn the same way (slot highlight, tooltip) samples anything else.
static int is_empty_present(GLsizei count) {
    if (count != 6 || atomic_load(&frame_bound_offscreen) || glIsEnabled(GL_DEPTH_TEST)) return 0;
    GLboolean mask[4] = {GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE};
    glGetBooleanv(GL_COLOR_WRITEMASK, mask);
    if (mask[3] != GL_FALSE) return 0;
    GLint texture = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture);
    return is_framebuffer_color((GLuint)texture);
}

static void GL_APIENTRY traced_draw_elements(GLenum mode, GLsizei count, GLenum type, const void* indices) {
    if (is_empty_present(count)) {
        if (atomic_fetch_add(&blits_skipped, 1) == 0) {
            trace("[millida] gles shim left out the %s of an empty framebuffer %p", "full-screen copy", NULL);
        }
        return;
    }
    real_draw_elements(mode, count, type, indices);
    if (trace_target() >= 0) note_draw("glDrawElements", mode, count, 1);
}

static void GL_APIENTRY traced_draw_elements_base_vertex(GLenum mode, GLsizei count, GLenum type, const void* indices,
                                                         GLint base) {
    real_draw_elements_base_vertex(mode, count, type, indices, base);
    note_draw("glDrawElementsBaseVertex", mode, count, 1);
}

static void GL_APIENTRY traced_draw_arrays_instanced(GLenum mode, GLint first, GLsizei count, GLsizei instances) {
    real_draw_arrays_instanced(mode, first, count, instances);
    note_draw("glDrawArraysInstanced", mode, count, instances);
}

static void GL_APIENTRY traced_draw_elements_instanced(GLenum mode, GLsizei count, GLenum type, const void* indices,
                                                       GLsizei instances) {
    real_draw_elements_instanced(mode, count, type, indices, instances);
    note_draw("glDrawElementsInstanced", mode, count, instances);
}

static void* traced(const char* name, void* found) {
    if (found == NULL) return found;
    if (strcmp(name, "glDrawElements") == 0) { real_draw_elements = (DrawElementsFn)found; return (void*)traced_draw_elements; }
    if (trace_target() < 0) return found;
    if (strcmp(name, "glDrawArrays") == 0) { real_draw_arrays = (DrawArraysFn)found; return (void*)traced_draw_arrays; }
    if (strcmp(name, "glDrawElementsBaseVertex") == 0) {
        real_draw_elements_base_vertex = (DrawElementsBaseVertexFn)found;
        return (void*)traced_draw_elements_base_vertex;
    }
    if (strcmp(name, "glDrawArraysInstanced") == 0) {
        real_draw_arrays_instanced = (DrawArraysInstancedFn)found;
        return (void*)traced_draw_arrays_instanced;
    }
    if (strcmp(name, "glDrawElementsInstanced") == 0) {
        real_draw_elements_instanced = (DrawElementsInstancedFn)found;
        return (void*)traced_draw_elements_instanced;
    }
    return found;
}

typedef void (*BindFramebufferFn)(GLenum, GLuint);
typedef void (*FramebufferTexture2DFn)(GLenum, GLenum, GLenum, GLuint, GLint);
typedef void (*FramebufferRenderbufferFn)(GLenum, GLenum, GLenum, GLuint);
typedef GLenum (*CheckFramebufferStatusFn)(GLenum);
typedef void (*RenderbufferStorageFn)(GLenum, GLenum, GLsizei, GLsizei);
static BindFramebufferFn real_bind_framebuffer;
static FramebufferTexture2DFn real_framebuffer_texture;
static FramebufferRenderbufferFn real_framebuffer_renderbuffer;
static CheckFramebufferStatusFn real_check_framebuffer;
static RenderbufferStorageFn real_renderbuffer_storage;
static atomic_int fbo_logged;

static int fbo_log_open(void) {
    return trace_target() >= 0 && atomic_fetch_add(&fbo_logged, 1) < 200;
}

static void GL_APIENTRY traced_bind_framebuffer(GLenum target, GLuint framebuffer) {
    real_bind_framebuffer(target, framebuffer);
    if (framebuffer != 0 && (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER)) {
        atomic_store(&frame_bound_offscreen, 1);
    }
    if (!fbo_log_open()) return;
    fprintf(stderr, "[millida] gl fbo bind target=0x%x id=%u err=0x%x tid=%d isfb=%d\n", (unsigned)target, framebuffer,
            (unsigned)glGetError(), (int)gettid(), framebuffer == 0 ? 1 : (int)glIsFramebuffer(framebuffer));
    fflush(stderr);
}

static void GL_APIENTRY traced_framebuffer_texture(GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level) {
    real_framebuffer_texture(target, attachment, textarget, texture, level);
    if (attachment == GL_COLOR_ATTACHMENT0) remember_framebuffer_color(texture);
    if (!fbo_log_open()) return;
    fprintf(stderr, "[millida] gl fbo texture attach=0x%x tex=%u err=0x%x\n", (unsigned)attachment, texture, (unsigned)glGetError());
    fflush(stderr);
}

static void GL_APIENTRY traced_framebuffer_renderbuffer(GLenum target, GLenum attachment, GLenum rbtarget, GLuint renderbuffer) {
    real_framebuffer_renderbuffer(target, attachment, rbtarget, renderbuffer);
    if (!fbo_log_open()) return;
    fprintf(stderr, "[millida] gl fbo renderbuffer attach=0x%x rb=%u err=0x%x\n", (unsigned)attachment, renderbuffer,
            (unsigned)glGetError());
    fflush(stderr);
}

static GLenum GL_APIENTRY traced_check_framebuffer(GLenum target) {
    const GLenum status = real_check_framebuffer(target);
    if (fbo_log_open()) {
        fprintf(stderr, "[millida] gl fbo status=0x%x\n", (unsigned)status);
        fflush(stderr);
    }
    return status;
}

static void GL_APIENTRY traced_renderbuffer_storage(GLenum target, GLenum format, GLsizei width, GLsizei height) {
    real_renderbuffer_storage(target, format, width, height);
    if (!fbo_log_open()) return;
    fprintf(stderr, "[millida] gl rb storage format=0x%x %dx%d err=0x%x\n", (unsigned)format, (int)width, (int)height,
            (unsigned)glGetError());
    fflush(stderr);
}

static void* traced_fbo(const char* name, void* found) {
    if (found == NULL) return found;
    if (strcmp(name, "glBindFramebuffer") == 0) { real_bind_framebuffer = (BindFramebufferFn)found; return (void*)traced_bind_framebuffer; }
    if (strcmp(name, "glFramebufferTexture2D") == 0) {
        real_framebuffer_texture = (FramebufferTexture2DFn)found;
        return (void*)traced_framebuffer_texture;
    }
    if (trace_target() < 0) return found;
    if (strcmp(name, "glFramebufferRenderbuffer") == 0) {
        real_framebuffer_renderbuffer = (FramebufferRenderbufferFn)found;
        return (void*)traced_framebuffer_renderbuffer;
    }
    if (strcmp(name, "glCheckFramebufferStatus") == 0) {
        real_check_framebuffer = (CheckFramebufferStatusFn)found;
        return (void*)traced_check_framebuffer;
    }
    if (strcmp(name, "glRenderbufferStorage") == 0) {
        real_renderbuffer_storage = (RenderbufferStorageFn)found;
        return (void*)traced_renderbuffer_storage;
    }
    return found;
}

SHIM_EXPORT EGLBoolean EGLAPIENTRY eglSwapBuffers(EGLDisplay display, EGLSurface surface) {
    pthread_once(&real_once, resolve_real);
    static SwapBuffersFn swap;
    if (swap == NULL) swap = (SwapBuffersFn)symbol(real.egl, "eglSwapBuffers");
    atomic_store(&frame_bound_offscreen, 0);
    if (trace_target() >= 0) {
        const int frame = atomic_fetch_add(&frame_number, 1);
        if (frame % 120 == 0 || frame == trace_target()) {
            fprintf(stderr, "[millida] gl frame %d draws=%d errors=%d offscreen=%d noprog=%d tid=%d ctx=%p\n", frame,
                    atomic_load(&frame_draws), atomic_load(&frame_errors), atomic_load(&frame_offscreen),
                    atomic_load(&frame_no_program), (int)gettid(), (void*)eglGetCurrentContext());
            fflush(stderr);
        }
        atomic_store(&frame_draws, 0);
        atomic_store(&frame_errors, 0);
        atomic_store(&frame_offscreen, 0);
        atomic_store(&frame_no_program, 0);
    }
    return swap == NULL ? EGL_FALSE : swap(display, surface);
}

/*
 * The pack's loading screen renders on its own thread with the window's
 * context and parks the game thread on a second, shared one. Framebuffers and
 * vertex arrays are not shared between contexts, and Angelica keeps a single
 * state cache for both, so after loading the game drew with objects that do
 * not exist where it draws. The two threads trade contexts instead: the game
 * thread always gets the window's context and the loading thread the shared
 * one, so every object the game makes lives where it is used later.
 */
static pthread_mutex_t swap_lock = PTHREAD_MUTEX_INITIALIZER;
static EGLContext display_context = EGL_NO_CONTEXT;
static EGLContext partner_context = EGL_NO_CONTEXT;
static pid_t creator_tid;
static pid_t game_tid;
static pid_t display_owner;
static int game_on_partner;
static _Thread_local EGLContext requested_context = EGL_NO_CONTEXT;
static _Thread_local int requested_known;
static atomic_int swaps_logged;
static atomic_int refusals_logged;

static EGLContext context_for(pid_t tid, EGLSurface draw, EGLContext context) {
    EGLContext actual = context;
    pthread_mutex_lock(&swap_lock);
    if (context != EGL_NO_CONTEXT && display_context == EGL_NO_CONTEXT && draw != EGL_NO_SURFACE) {
        display_context = context;
        creator_tid = tid;
    } else if (context != EGL_NO_CONTEXT && context == display_context && game_tid == 0 && tid != creator_tid) {
        game_tid = tid;
    }
    const int window_taken = display_owner != 0 && display_owner != tid;
    if (game_tid != 0 && tid == game_tid) {
        if (context != EGL_NO_CONTEXT && context != display_context) {
            partner_context = context;
            // Another thread already holds the window's context: the game keeps
            // the shared one rather than fail on a context that is busy.
            actual = window_taken ? context : display_context;
            game_on_partner = !window_taken;
        } else {
            game_on_partner = 0;
        }
    } else if (context == display_context && game_tid != 0 && (game_on_partner || window_taken)) {
        actual = EGL_NO_CONTEXT;
    }
    pthread_mutex_unlock(&swap_lock);
    return actual;
}

static void note_current(pid_t tid, EGLContext actual) {
    pthread_mutex_lock(&swap_lock);
    if (actual == display_context && actual != EGL_NO_CONTEXT) {
        display_owner = tid;
    } else if (display_owner == tid) {
        display_owner = 0;
    }
    pthread_mutex_unlock(&swap_lock);
}

SHIM_EXPORT EGLBoolean EGLAPIENTRY eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context) {
    pthread_once(&real_once, resolve_real);
    static MakeCurrentFn make_current;
    if (make_current == NULL) make_current = (MakeCurrentFn)symbol(real.egl, "eglMakeCurrent");
    if (make_current == NULL) return EGL_FALSE;
    const pid_t tid = gettid();
    const EGLContext actual = context_for(tid, draw, context);
    if (trace_target() >= 0 && atomic_fetch_add(&current_logged, 1) < 40) {
        fprintf(stderr, "[millida] egl make current tid=%d ctx=%p actual=%p draw=%p\n", (int)tid, (void*)context, (void*)actual,
                (void*)draw);
        fflush(stderr);
    }
    if (actual != context && atomic_fetch_add(&swaps_logged, 1) == 0) {
        trace("[millida] gles shim gave the %s thread the window context %p", "game", actual);
    }
    // Angelica keeps one state cache for every thread: GL work from the loading
    // screen's thread leaves it describing bindings the game's context never
    // had. Refusing that thread its context makes the pack fall back to
    // loading without its own screen, with all GL work on the game thread.
    if (context != EGL_NO_CONTEXT && actual == EGL_NO_CONTEXT) {
        if (atomic_fetch_add(&refusals_logged, 1) == 0) {
            trace("[millida] gles shim refused the window context to the %s thread %p", "loading screen", context);
        }
        return EGL_FALSE;
    }
    const EGLBoolean done = make_current(display, draw, read, actual);
    if (done) {
        requested_context = context;
        requested_known = actual != context;
        note_current(tid, actual);
    }
    return done;
}

typedef EGLContext (*GetCurrentContextFn)(void);

SHIM_EXPORT EGLContext EGLAPIENTRY eglGetCurrentContext(void) {
    pthread_once(&real_once, resolve_real);
    if (requested_known) return requested_context;
    static GetCurrentContextFn current;
    if (current == NULL) current = (GetCurrentContextFn)symbol(real.egl, "eglGetCurrentContext");
    return current == NULL ? EGL_NO_CONTEXT : current();
}

typedef void (*ShaderSourceFn)(GLuint, GLsizei, const GLchar* const*, const GLint*);
static ShaderSourceFn real_shader_source;
static atomic_int blocks_restored;

static const char FFP_STRUCT[] = "struct FFPUniformBlock";
static const char FFP_BLOCK[] = "layout(std140) uniform FFPUniformBlock";
static const char FFP_VARIABLE[] = "uniform FFPUniformBlock ";

/*
 * Angelica's ES translation asks SPIRV-Cross for plain uniforms, which turns the
 * FFP uniform block into a struct uniform, while it still fills the block
 * through a uniform buffer. The block is put back by name so that buffer
 * reaches the shader; member accesses through the instance name stay valid.
 */
static char* restore_ffp_block(const char* source) {
    const char* struct_at = strstr(source, FFP_STRUCT);
    if (struct_at == NULL) return NULL;
    const char* close = strstr(struct_at, "};");
    if (close == NULL) return NULL;
    const char* variable = strstr(close, FFP_VARIABLE);
    if (variable == NULL) return NULL;
    const char* name = variable + sizeof(FFP_VARIABLE) - 1;
    const char* name_end = strchr(name, ';');
    if (name_end == NULL || name_end - name > 64) return NULL;

    const size_t length = strlen(source);
    char* out = malloc(length + sizeof(FFP_BLOCK) + 80);
    if (out == NULL) return NULL;
    size_t at = 0;
    memcpy(out + at, source, (size_t)(struct_at - source));
    at += (size_t)(struct_at - source);
    memcpy(out + at, FFP_BLOCK, sizeof(FFP_BLOCK) - 1);
    at += sizeof(FFP_BLOCK) - 1;
    const char* body = struct_at + sizeof(FFP_STRUCT) - 1;
    memcpy(out + at, body, (size_t)(close - body));
    at += (size_t)(close - body);
    out[at++] = '}';
    out[at++] = ' ';
    memcpy(out + at, name, (size_t)(name_end - name));
    at += (size_t)(name_end - name);
    out[at++] = ';';
    const char* after_close = close + 2;
    memcpy(out + at, after_close, (size_t)(variable - after_close));
    at += (size_t)(variable - after_close);
    const char* rest = name_end + 1;
    const size_t rest_length = length - (size_t)(rest - source);
    memcpy(out + at, rest, rest_length);
    at += rest_length;
    out[at] = '\0';
    return out;
}

static void GL_APIENTRY shim_shader_source(GLuint shader, GLsizei count, const GLchar* const* strings, const GLint* lengths) {
    if (count <= 0 || strings == NULL) {
        real_shader_source(shader, count, strings, lengths);
        return;
    }
    size_t total = 0;
    for (GLsizei index = 0; index < count; ++index) {
        if (strings[index] == NULL) continue;
        total += lengths != NULL && lengths[index] >= 0 ? (size_t)lengths[index] : strlen(strings[index]);
    }
    char* joined = malloc(total + 1);
    if (joined == NULL) {
        real_shader_source(shader, count, strings, lengths);
        return;
    }
    size_t at = 0;
    for (GLsizei index = 0; index < count; ++index) {
        if (strings[index] == NULL) continue;
        const size_t part = lengths != NULL && lengths[index] >= 0 ? (size_t)lengths[index] : strlen(strings[index]);
        memcpy(joined + at, strings[index], part);
        at += part;
    }
    joined[at] = '\0';
    char* restored = strstr(joined, FFP_VARIABLE) != NULL ? restore_ffp_block(joined) : NULL;
    if (restored == NULL) {
        free(joined);
        real_shader_source(shader, count, strings, lengths);
        return;
    }
    if (atomic_fetch_add(&blocks_restored, 1) == 0) {
        trace("[millida] gles shim restored %s as a uniform block %p", "FFPUniformBlock", NULL);
    }
    const GLchar* single = restored;
    real_shader_source(shader, 1, &single, NULL);
    free(restored);
    free(joined);
}

SHIM_EXPORT __eglMustCastToProperFunctionPointerType EGLAPIENTRY eglGetProcAddress(const char* procname) {
    if (procname != NULL && strcmp(procname, "glTexImage2D") == 0) {
        return (__eglMustCastToProperFunctionPointerType)glTexImage2D;
    }
    if (procname != NULL && strcmp(procname, "glTexSubImage2D") == 0) {
        return (__eglMustCastToProperFunctionPointerType)glTexSubImage2D;
    }
    pthread_once(&real_once, resolve_real);
    void* found = real.get_proc_address == NULL ? NULL : (void*)real.get_proc_address(procname);
    // Android's eglGetProcAddress may answer NULL for core GLES names, and SDL's
    // own dlsym fallback does not reach them through this library.
    if (found == NULL && procname != NULL) found = symbol(real.gles, procname);
    if (found == NULL && procname != NULL) found = symbol(real.egl, procname);
    if (trace_target() >= 0 && procname != NULL && strstr(procname, "TexImage") != NULL) trace("[millida] gles shim lookup %s=%p", procname, found);
    int seen = atomic_fetch_add(&lookups, 1);
    if (trace_target() >= 0 && seen < 5) trace("[millida] gles shim lookup %s=%p", procname == NULL ? "(null)" : procname, found);
    if (trace_target() >= 0 && found == NULL && atomic_fetch_add(&misses, 1) < 40) {
        trace("[millida] gles shim MISSING %s %p", procname == NULL ? "(null)" : procname, found);
    }
    if (procname != NULL) found = traced(procname, found);
    if (procname != NULL) found = traced_fbo(procname, found);
    if (procname != NULL && found != NULL && strcmp(procname, "glShaderSource") == 0) {
        real_shader_source = (ShaderSourceFn)found;
        found = (void*)shim_shader_source;
    }
    return (__eglMustCastToProperFunctionPointerType)found;
}
