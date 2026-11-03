#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

#define SHIM_EXPORT __attribute__((visibility("default")))
#define SHIM_LOG_TAG "millida"
#define SYSTEM_LOADER "libvulkan.so"

static struct {
    void* library;
    PFN_vkGetInstanceProcAddr instance_lookup;
    PFN_vkGetDeviceProcAddr device_lookup;
} real;

static pthread_once_t real_once = PTHREAD_ONCE_INIT;

static _Atomic(PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR) real_capabilities;
static _Atomic(PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR) real_capabilities2;
static _Atomic(PFN_vkAcquireNextImageKHR) real_acquire;
static _Atomic(PFN_vkAcquireNextImage2KHR) real_acquire2;
static _Atomic(PFN_vkQueuePresentKHR) real_present;

static atomic_bool turned;
static atomic_bool turned_logged;

static void report(const char* text, const char* detail) {
    __android_log_print(ANDROID_LOG_INFO, SHIM_LOG_TAG, "vk shim: %s %s", text, detail);
    fprintf(stderr, "[millida] vk shim: %s %s\n", text, detail);
}

static void resolve_real(void) {
    real.library = dlopen(SYSTEM_LOADER, RTLD_NOW | RTLD_LOCAL);
    if (real.library == NULL) {
        const char* reason = dlerror();
        report("the system Vulkan loader did not open:", reason != NULL ? reason : SYSTEM_LOADER);
        return;
    }
    real.instance_lookup = (PFN_vkGetInstanceProcAddr) dlsym(real.library, "vkGetInstanceProcAddr");
    real.device_lookup = (PFN_vkGetDeviceProcAddr) dlsym(real.library, "vkGetDeviceProcAddr");
    if (real.instance_lookup == NULL || real.device_lookup == NULL) {
        report("the system Vulkan loader has no lookup functions:", SYSTEM_LOADER);
        real.instance_lookup = NULL;
        real.device_lookup = NULL;
    }
}

/*
 * Android reports the screen rotation as the current transform of a surface and expects the
 * application to draw its frame already turned. A desktop renderer hands that value back as
 * the pre-transform of its swapchain and draws upright, so the picture comes out on its side
 * and stretched. With the identity transform reported, the compositor turns the frame itself.
 * The extent Android reports is the window's own and needs no swap.
 */
static void upright(VkSurfaceCapabilitiesKHR* capabilities) {
    if (capabilities->currentTransform == VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) return;
    if ((capabilities->supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) == 0) return;
    capabilities->currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    atomic_store(&turned, true);
    if (!atomic_exchange(&turned_logged, true)) {
        report("the surface is rotated by the system:", "the game is told it is upright");
    }
}

/*
 * A swapchain whose pre-transform differs from the screen rotation makes Android answer
 * every acquire and present with VK_SUBOPTIMAL_KHR. A desktop renderer rebuilds its
 * swapchain on that answer, every frame, so it is reported as success.
 */
static VkResult settled(VkResult result) {
    return result == VK_SUBOPTIMAL_KHR && atomic_load(&turned) ? VK_SUCCESS : result;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_capabilities(
    VkPhysicalDevice device, VkSurfaceKHR surface, VkSurfaceCapabilitiesKHR* capabilities) {
    const VkResult result = atomic_load(&real_capabilities)(device, surface, capabilities);
    if (result == VK_SUCCESS && capabilities != NULL) upright(capabilities);
    return result;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_capabilities2(
    VkPhysicalDevice device, const VkPhysicalDeviceSurfaceInfo2KHR* info, VkSurfaceCapabilities2KHR* capabilities) {
    const VkResult result = atomic_load(&real_capabilities2)(device, info, capabilities);
    if (result == VK_SUCCESS && capabilities != NULL) upright(&capabilities->surfaceCapabilities);
    return result;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_acquire(
    VkDevice device, VkSwapchainKHR swapchain, uint64_t timeout, VkSemaphore semaphore, VkFence fence, uint32_t* index) {
    return settled(atomic_load(&real_acquire)(device, swapchain, timeout, semaphore, fence, index));
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_acquire2(
    VkDevice device, const VkAcquireNextImageInfoKHR* info, uint32_t* index) {
    return settled(atomic_load(&real_acquire2)(device, info, index));
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_present(VkQueue queue, const VkPresentInfoKHR* info) {
    const VkResult result = atomic_load(&real_present)(queue, info);
    if (info != NULL && info->pResults != NULL) {
        for (uint32_t index = 0; index < info->swapchainCount; index++) {
            info->pResults[index] = settled(info->pResults[index]);
        }
    }
    return settled(result);
}

SHIM_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* name);
SHIM_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* name);

/*
 * The functions the loader exports dispatch on the handle they are given, so they serve
 * every instance and device; the one found through a lookup belongs to a single device
 * and is taken only when the loader exports none.
 */
static PFN_vkVoidFunction wrapped(const char* name, PFN_vkVoidFunction found) {
    if (found == NULL) return NULL;
    if (strcmp(name, "vkGetInstanceProcAddr") == 0) return (PFN_vkVoidFunction) vkGetInstanceProcAddr;
    if (strcmp(name, "vkGetDeviceProcAddr") == 0) return (PFN_vkVoidFunction) vkGetDeviceProcAddr;

    PFN_vkVoidFunction exported = (PFN_vkVoidFunction) dlsym(real.library, name);
    PFN_vkVoidFunction target = exported != NULL ? exported : found;
    if (strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR") == 0) {
        atomic_store(&real_capabilities, (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR) target);
        return (PFN_vkVoidFunction) shim_capabilities;
    }
    if (strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilities2KHR") == 0) {
        atomic_store(&real_capabilities2, (PFN_vkGetPhysicalDeviceSurfaceCapabilities2KHR) target);
        return (PFN_vkVoidFunction) shim_capabilities2;
    }
    if (strcmp(name, "vkAcquireNextImageKHR") == 0) {
        atomic_store(&real_acquire, (PFN_vkAcquireNextImageKHR) target);
        return (PFN_vkVoidFunction) shim_acquire;
    }
    if (strcmp(name, "vkAcquireNextImage2KHR") == 0) {
        atomic_store(&real_acquire2, (PFN_vkAcquireNextImage2KHR) target);
        return (PFN_vkVoidFunction) shim_acquire2;
    }
    if (strcmp(name, "vkQueuePresentKHR") == 0) {
        atomic_store(&real_present, (PFN_vkQueuePresentKHR) target);
        return (PFN_vkVoidFunction) shim_present;
    }
    return found;
}

SHIM_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* name) {
    if (name == NULL) return NULL;
    pthread_once(&real_once, resolve_real);
    if (real.instance_lookup == NULL) return NULL;
    return wrapped(name, real.instance_lookup(instance, name));
}

SHIM_EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* name) {
    if (name == NULL) return NULL;
    pthread_once(&real_once, resolve_real);
    if (real.device_lookup == NULL) return NULL;
    return wrapped(name, real.device_lookup(device, name));
}
