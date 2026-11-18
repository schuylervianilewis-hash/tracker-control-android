/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef TRACKERCONTROL_SPEED_LIMIT_H
#define TRACKERCONTROL_SPEED_LIMIT_H

#include <stdint.h>

/*
 * A small, allocation-free token bucket for the native packet hot path.
 * A rate of zero means unlimited. Time is supplied by the caller so tests can
 * use a deterministic monotonic clock without sleeping.
 */
struct speed_bucket {
    uint64_t rate_bytes_per_sec;
    uint64_t capacity_bytes;
    uint64_t tokens;
    uint64_t last_ns;
    uint64_t refill_remainder;
};

void speed_bucket_init(struct speed_bucket *bucket,
                       uint64_t rate_bytes_per_sec,
                       uint64_t now_ns);

void speed_bucket_update(struct speed_bucket *bucket,
                         uint64_t rate_bytes_per_sec,
                         uint64_t now_ns);

uint64_t speed_bucket_burst_capacity(uint64_t rate_bytes_per_sec);
uint64_t speed_bucket_available(struct speed_bucket *bucket, uint64_t now_ns);
uint64_t speed_bucket_wait_ns(struct speed_bucket *bucket,
                              uint64_t bytes,
                              uint64_t now_ns);
int speed_bucket_consume(struct speed_bucket *bucket,
                         uint64_t bytes,
                         uint64_t now_ns);

#endif
