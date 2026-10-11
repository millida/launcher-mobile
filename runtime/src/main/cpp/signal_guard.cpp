#include "millida_bridge.h"

#include <atomic>
#include <csignal>
#include <cstdarg>
#include <cstdio>
#include <pthread.h>
#include <unistd.h>

namespace millida {
namespace {

// Android keeps real-time signals 33-41 for itself: libcore wakes a reader
// blocked on a closing socket with one, the system asks for a stack dump with
// another when it records an ANR. Their handlers come from the zygote, yet the
// game process was killed by exactly these signals, which only happens at the
// default disposition. A handler that does nothing keeps the interruption (no
// SA_RESTART) and lets the game live. The check repeats because the
// disposition can be lost while the machine and its libraries are loading.
constexpr int kPlatformSignalFirst = __SIGRTMIN + 1;
constexpr int kPlatformSignalLast = __SIGRTMIN + 9;
constexpr int kPlatformSignalCount = kPlatformSignalLast - kPlatformSignalFirst + 1;
constexpr useconds_t kWatchPeriodMicros = 250000;
// The disposition is lost while the game loads its libraries and the system's
// signal follows within a few hundred milliseconds, so the start is watched closely.
constexpr useconds_t kStartWatchPeriodMicros = 10000;
constexpr int kStartWatchRounds = 6000;

std::atomic<bool> g_watching{false};

// A signal handler may only touch lock-free atomics; the watcher reports them.
struct Delivery {
    std::atomic<int> count{0};
    std::atomic<int> sender{0};
    std::atomic<int> code{0};
};
Delivery g_deliveries[kPlatformSignalCount];
int g_reported[kPlatformSignalCount];

__attribute__((format(printf, 1, 2))) void report(const char* format, ...) {
    char line[256];
    va_list arguments;
    va_start(arguments, format);
    vsnprintf(line, sizeof(line), format, arguments);
    va_end(arguments);
    LOGW("%s", line);
    fputs(line, stderr);
    fputc('\n', stderr);
    fflush(stderr);
}

void absorb(int signal_number, siginfo_t* info, void*) {
    const int index = signal_number - kPlatformSignalFirst;
    if (index < 0 || index >= kPlatformSignalCount) return;
    Delivery& delivery = g_deliveries[index];
    delivery.sender.store(info == nullptr ? 0 : info->si_pid, std::memory_order_relaxed);
    delivery.code.store(info == nullptr ? 0 : info->si_code, std::memory_order_relaxed);
    delivery.count.fetch_add(1, std::memory_order_relaxed);
}

void report_deliveries() {
    for (int index = 0; index < kPlatformSignalCount; ++index) {
        const int count = g_deliveries[index].count.load(std::memory_order_relaxed);
        if (count == g_reported[index]) continue;
        g_reported[index] = count;
        report("[millida] signal %d absorbed %d times, last from pid %d, code %d", kPlatformSignalFirst + index, count,
               g_deliveries[index].sender.load(std::memory_order_relaxed),
               g_deliveries[index].code.load(std::memory_order_relaxed));
    }
}

void* watch_loop(void*) {
    for (int round = 0;; ++round) {
        usleep(round < kStartWatchRounds ? kStartWatchPeriodMicros : kWatchPeriodMicros);
        signals_guard("during the session");
        report_deliveries();
    }
    return nullptr;
}

}  // namespace

void signals_guard(const char* moment) {
    for (int signal_number = kPlatformSignalFirst; signal_number <= kPlatformSignalLast; ++signal_number) {
        struct sigaction current{};
        if (sigaction(signal_number, nullptr, &current) != 0) continue;
        // The kernel reads the handler slot alone: a null sa_sigaction under SA_SIGINFO is still the default.
        if (current.sa_handler != SIG_DFL) continue;
        struct sigaction harmless{};
        sigemptyset(&harmless.sa_mask);
        harmless.sa_sigaction = absorb;
        harmless.sa_flags = SA_SIGINFO | SA_ONSTACK;
        if (sigaction(signal_number, &harmless, nullptr) == 0) {
            report("[millida] signal %d was at the default disposition %s, made harmless", signal_number, moment);
        }
    }
}

void signals_watch() {
    if (g_watching.exchange(true)) return;
    pthread_t thread;
    if (pthread_create(&thread, nullptr, watch_loop, nullptr) == 0) pthread_detach(thread);
}

}  // namespace millida
