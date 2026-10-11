#include "millida_bridge.h"

#include <array>
#include <mutex>

namespace millida {
namespace {

constexpr size_t kCapacity = 2048;

struct Queue {
    std::array<InputEvent, kCapacity> slots{};
    size_t head = 0;
    size_t tail = 0;
    std::mutex lock;
};

Queue g_queue;

bool droppable(const InputEvent& event) {
    return event.kind == EventKind::CursorPosition ||
           event.kind == EventKind::CursorDelta ||
           event.kind == EventKind::Scroll;
}

/**
 * A full queue gives up pointer motion, never a key or button event: losing a
 * key release leaves the character running on its own.
 */
void make_room() {
    size_t index = g_queue.head;
    while (index != g_queue.tail) {
        if (droppable(g_queue.slots[index])) {
            size_t next = (index + 1) % kCapacity;
            while (next != g_queue.tail) {
                g_queue.slots[index] = g_queue.slots[next];
                index = next;
                next = (next + 1) % kCapacity;
            }
            g_queue.tail = index;
            return;
        }
        index = (index + 1) % kCapacity;
    }
    g_queue.head = (g_queue.head + 1) % kCapacity;
}

}  // namespace

void input_push(const InputEvent& event) {
    std::lock_guard<std::mutex> guard(g_queue.lock);
    if ((g_queue.tail + 1) % kCapacity == g_queue.head) make_room();
    g_queue.slots[g_queue.tail] = event;
    g_queue.tail = (g_queue.tail + 1) % kCapacity;
}

bool input_pop(InputEvent* out) {
    std::lock_guard<std::mutex> guard(g_queue.lock);
    if (g_queue.head == g_queue.tail) return false;
    *out = g_queue.slots[g_queue.head];
    g_queue.head = (g_queue.head + 1) % kCapacity;
    return true;
}

void input_clear() {
    std::lock_guard<std::mutex> guard(g_queue.lock);
    g_queue.head = 0;
    g_queue.tail = 0;
}

}  // namespace millida
