/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "speed_limit.h"

#include <stddef.h>

#define NANOS_PER_SECOND UINT64_C(1000000000)
#define MIN_BURST_BYTES UINT64_C(2920) /* two conservative TCP MSS-sized chunks */

/*
 * Calculate floor(a * b / divisor) and its remainder without a wide integer.
 * The caller guarantees a < divisor and divisor != 0, so the quotient fits
 * in uint64_t. The bitwise long-division loop avoids overflowing either the
 * intermediate product or the doubled remainder.
 */
static uint64_t mul_div_rem_u64(uint64_t a, uint64_t b,
                                uint64_t divisor, uint64_t *remainder) {
    uint64_t quotient = 0;
    uint64_t rem = 0;

    for (int bit = 63; bit >= 0; bit--) {
        uint64_t carry = 0;

        if (rem >= divisor - rem) {
            rem -= divisor - rem;
            carry = 1;
        } else {
            rem += rem;
        }
        quotient = quotient * 2 + carry;

        if ((b >> bit) & UINT64_C(1)) {
            if (a != 0 && rem >= divisor - a) {
                rem -= divisor - a;
                quotient++;
            } else {
                rem += a;
            }
        }
    }

    if (remainder != NULL)
        *remainder = rem;
    return quotient;
}

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
     * Split elapsed time into whole seconds and a sub-second remainder.
     * Saturate against available bucket room before multiplying whole seconds.
     * The fractional product is evaluated with overflow-safe long division.
     */
    uint64_t room = bucket->capacity_bytes - bucket->tokens;
    uint64_t whole_seconds = elapsed_ns / NANOS_PER_SECOND;
    uint64_t fractional_ns = elapsed_ns % NANOS_PER_SECOND;
    uint64_t rate = bucket->rate_bytes_per_sec;

    if (whole_seconds != 0) {
        uint64_t seconds_to_fill = room / rate;
        if (room % rate != 0)
            seconds_to_fill++;
        if (whole_seconds >= seconds_to_fill) {
            bucket->tokens = bucket->capacity_bytes;
            bucket->refill_remainder = 0;
            return;
        }
    }

    uint64_t earned_whole = whole_seconds * rate;
    uint64_t fractional_remainder = 0;
    uint64_t earned_fraction =
            mul_div_rem_u64(fractional_ns, rate, NANOS_PER_SECOND,
                            &fractional_remainder);

    /* Preserve fractional credit across calls without overflowing. */
    if (fractional_remainder >=
            NANOS_PER_SECOND - bucket->refill_remainder) {
        fractional_remainder -=
                NANOS_PER_SECOND - bucket->refill_remainder;
        if (earned_fraction == UINT64_MAX) {
            bucket->tokens = bucket->capacity_bytes;
            bucket->refill_remainder = 0;
            return;
        }
        earned_fraction++;
    } else {
        fractional_remainder += bucket->refill_remainder;
    }

    uint64_t remaining_room = room - earned_whole;
    if (earned_fraction >= remaining_room) {
        bucket->tokens = bucket->capacity_bytes;
        bucket->refill_remainder = 0;
    } else {
        bucket->tokens += earned_whole + earned_fraction;
        bucket->refill_remainder = fractional_remainder;
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
    uint64_t rate = bucket->rate_bytes_per_sec;

    /*
     * Split the numerator before multiplication. deficit / rate is bounded
     * by the minimum burst size (2920); the fractional term uses safe
     * multiply/divide with an exact remainder for ceiling to nanoseconds.
     */
    uint64_t whole_seconds = deficit / rate;
    if (whole_seconds > UINT64_MAX / NANOS_PER_SECOND)
        return UINT64_MAX;

    uint64_t wait = whole_seconds * NANOS_PER_SECOND;
    uint64_t fractional_remainder = 0;
    uint64_t fractional_wait =
            mul_div_rem_u64(deficit % rate, NANOS_PER_SECOND, rate,
                            &fractional_remainder);

    if (wait > UINT64_MAX - fractional_wait)
        return UINT64_MAX;
    wait += fractional_wait;

    if (fractional_remainder != 0) {
        if (wait == UINT64_MAX)
            return UINT64_MAX;
        wait++;
    }
    return wait;
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
