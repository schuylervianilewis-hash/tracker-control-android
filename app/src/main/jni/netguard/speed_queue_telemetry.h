/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef TRACKERCONTROL_SPEED_QUEUE_TELEMETRY_H
#define TRACKERCONTROL_SPEED_QUEUE_TELEMETRY_H
#include <stddef.h>
#include <stdint.h>
#include <stdatomic.h>
/* Lock-free aggregate publication; the queue itself remains epoll-thread-owned. */
struct speed_queue_telemetry {
    _Atomic uint64_t packets, bytes, peak_packets, peak_bytes;
    _Atomic uint64_t dropped_packets, scheduler_errors;
};
static inline void speed_queue_telemetry_init(struct speed_queue_telemetry *t) {
    atomic_init(&t->packets, 0); atomic_init(&t->bytes, 0);
    atomic_init(&t->peak_packets, 0); atomic_init(&t->peak_bytes, 0);
    atomic_init(&t->dropped_packets, 0); atomic_init(&t->scheduler_errors, 0);
}
static inline void speed_queue_telemetry_update_peak(_Atomic uint64_t *peak, uint64_t value) {
    uint64_t old = atomic_load_explicit(peak, memory_order_relaxed);
    while (old < value && !atomic_compare_exchange_weak_explicit(
            peak, &old, value, memory_order_relaxed, memory_order_relaxed)) { }
}
static inline void speed_queue_telemetry_publish(struct speed_queue_telemetry *t,
                                                  size_t packets, size_t bytes) {
    uint64_t p = (uint64_t) packets, b = (uint64_t) bytes;
    atomic_store_explicit(&t->packets, p, memory_order_relaxed);
    atomic_store_explicit(&t->bytes, b, memory_order_relaxed);
    speed_queue_telemetry_update_peak(&t->peak_packets, p);
    speed_queue_telemetry_update_peak(&t->peak_bytes, b);
}
static inline void speed_queue_telemetry_record_drop(struct speed_queue_telemetry *t,
                                                      uint64_t count) {
    atomic_fetch_add_explicit(&t->dropped_packets, count, memory_order_relaxed);
}
static inline void speed_queue_telemetry_record_scheduler_error(struct speed_queue_telemetry *t) {
    atomic_fetch_add_explicit(&t->scheduler_errors, 1, memory_order_relaxed);
}
#endif
