/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "speed_limit.h"

#include <stddef.h>

#define NANOS_PER_SECOND UINT64_C(1000000000)
#define MIN_BURST_BYTES UINT64_C(2920) /* two conservative TCP MSS-sized chunks */

uint64_t speed_bucket_burst_capacity(uint64_t rate_bytes_per_sec) {
    if (rate_bytes_per_sec == 0)
        return 0;

    uint64_t quarter_second = rate_bytes_per_sec / 4;
    return quarter_second > MIN_BURST_BYTES ? quarter_second : MIN_BURST_BYTES;
}

static void refill(struct speed_bucket *bucket, uint64_t now_ns) {
    if (bucket == NULL)
        return;

    if (bucket->rate_bytes_per_sec == 0) {
        bucket->last_ns = now_ns;
        bucket->refill_remainder = 0;
        return;
    }

    if (now_ns <= bucket->last_ns)
        return;

    uint64_t elapsed_ns = now_ns - bucket->last_ns;
    bucket->last_ns = now_ns;

    if (bucket->tokens >= bucket->capacity_bytes) {
        bucket->tokens = bucket->capacity_bytes;
        bucket->refill_remainder = 0;
        return;
    }

    /*
     * Use a 128-bit intermediate so elapsed_ns * rate cannot overflow before
     * division. Keep the fractional numerator between calls; otherwise small
     * time slices would systematically under-credit low-rate buckets.
     */
    __uint128_t earned_numerator =
            (__uint128_t) elapsed_ns * bucket->rate_bytes_per_sec +
            bucket->refill_remainder;
    uint64_t room = bucket->capacity_bytes - bucket->tokens;
    __uint128_t earned_wide = earned_numerator / NANOS_PER_SECOND;
    if (earned_wide >= room) {
        bucket->tokens = bucket->capacity_bytes;
        bucket->refill_remainder = 0;
    } else {
        bucket->tokens += (uint64_t) earned_wide;
        bucket->refill_remainder =
                (uint64_t) (earned_numerator % NANOS_PER_SECOND);
    }
}

void speed_bucket_init(struct speed_bucket *bucket,
                       uint64_t rate_bytes_per_sec,
                       uint64_t now_ns) {
    if (bucket == NULL)
        return;

    bucket->rate_bytes_per_sec = rate_bytes_per_sec;
    bucket->capacity_bytes = speed_bucket_burst_capacity(rate_bytes_per_sec);
    bucket->tokens = bucket->capacity_bytes;
    bucket->last_ns = now_ns;
    bucket->refill_remainder = 0;
}

void speed_bucket_update(struct speed_bucket *bucket,
                         uint64_t rate_bytes_per_sec,
                         uint64_t now_ns) {
    if (bucket == NULL)
        return;

    refill(bucket, now_ns);

    uint64_t old_rate = bucket->rate_bytes_per_sec;
    uint64_t new_capacity = speed_bucket_burst_capacity(rate_bytes_per_sec);
    bucket->rate_bytes_per_sec = rate_bytes_per_sec;
    bucket->capacity_bytes = new_capacity;

    if (rate_bytes_per_sec == 0) {
        bucket->tokens = 0;
        bucket->refill_remainder = 0;
    } else if (old_rate == 0) {
        /* Resuming from unlimited starts with one bounded burst allowance. */
        bucket->tokens = new_capacity;
        bucket->refill_remainder = 0;
    } else if (bucket->tokens > new_capacity) {
        bucket->tokens = new_capacity;
        bucket->refill_remainder = 0;
    }
}

uint64_t speed_bucket_available(struct speed_bucket *bucket, uint64_t now_ns) {
    if (bucket == NULL || bucket->rate_bytes_per_sec == 0)
        return UINT64_MAX;

    refill(bucket, now_ns);
    return bucket->tokens;
}

uint64_t speed_bucket_wait_ns(struct speed_bucket *bucket,
                              uint64_t bytes,
                              uint64_t now_ns) {
    if (bucket == NULL || bucket->rate_bytes_per_sec == 0 || bytes == 0)
        return 0;

    refill(bucket, now_ns);
    if (bytes <= bucket->tokens)
        return 0;

    /* A request larger than the bucket can never become immediately available. */
    if (bytes > bucket->capacity_bytes)
        return UINT64_MAX;

    uint64_t deficit = bytes - bucket->tokens;
    __uint128_t numerator = (__uint128_t) deficit * NANOS_PER_SECOND;
    uint64_t rate = bucket->rate_bytes_per_sec;
    __uint128_t wait = (numerator + rate - 1) / rate;
    return wait > UINT64_MAX ? UINT64_MAX : (uint64_t) wait;
}

int speed_bucket_consume(struct speed_bucket *bucket,
                         uint64_t bytes,
                         uint64_t now_ns) {
    if (bucket == NULL || bucket->rate_bytes_per_sec == 0 || bytes == 0)
        return 1;

    refill(bucket, now_ns);
    if (bytes > bucket->tokens)
        return 0;

    bucket->tokens -= bytes;
    return 1;
}
