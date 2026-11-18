/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "speed_limit.h"

#include <stdio.h>
#include <stdlib.h>

static int failures;

#define CHECK(condition, message)                                           \
    do {                                                                    \
        if (!(condition)) {                                                 \
            fprintf(stderr, "FAIL: %s\n", (message));                      \
            failures++;                                                     \
        }                                                                   \
    } while (0)

static void test_preset_burst_sizes(void) {
    CHECK(speed_bucket_burst_capacity(12500) == 3125,
          "100 Kbps preset gets a quarter-second burst");
    CHECK(speed_bucket_burst_capacity(32000) == 8000,
          "256 Kbps preset gets a quarter-second burst");
    CHECK(speed_bucket_burst_capacity(64000) == 16000,
          "512 Kbps preset gets a quarter-second burst");
    CHECK(speed_bucket_burst_capacity(125000) == 31250,
          "1 Mbps preset gets a quarter-second burst");
    CHECK(speed_bucket_burst_capacity(375000) == 93750,
          "3 Mbps preset gets a quarter-second burst");
    CHECK(speed_bucket_burst_capacity(1) == 2920,
          "small rates still allow at least two MSS-sized chunks");
    CHECK(speed_bucket_burst_capacity(0) == 0,
          "unlimited has no finite burst bucket");
}

static void test_initial_burst_and_consumption(void) {
    struct speed_bucket bucket;
    speed_bucket_init(&bucket, 12500, 100);

    CHECK(bucket.capacity_bytes == 3125,
          "bucket capacity is derived from configured byte rate");
    CHECK(speed_bucket_available(&bucket, 100) == 3125,
          "bucket starts with one full burst");
    CHECK(speed_bucket_consume(&bucket, 1460, 100),
          "first MSS-sized chunk is immediately allowed");
    CHECK(speed_bucket_available(&bucket, 100) == 1665,
          "consumption subtracts exact payload bytes");
    CHECK(!speed_bucket_consume(&bucket, 1666, 100),
          "bucket refuses to overspend available tokens");
}

static void test_fractional_refill_is_not_lost(void) {
    struct speed_bucket bucket;
    speed_bucket_init(&bucket, 12500, 0);
    CHECK(speed_bucket_consume(&bucket, 3125, 0), "drain initial burst");

    CHECK(speed_bucket_available(&bucket, 40000) == 0,
          "half-byte refill is retained as a fraction");
    CHECK(bucket.refill_remainder == UINT64_C(500000000),
          "fractional refill numerator is retained");
    CHECK(speed_bucket_available(&bucket, 80000) == 1,
          "two half-byte intervals produce one full byte");
    CHECK(speed_bucket_consume(&bucket, 1, 80000),
          "newly earned byte can be consumed");
}

static void test_wait_calculation(void) {
    struct speed_bucket bucket;
    speed_bucket_init(&bucket, 12500, 0);
    CHECK(speed_bucket_consume(&bucket, 3125, 0), "drain initial burst");

    CHECK(speed_bucket_wait_ns(&bucket, 1, 0) == 80000,
          "100 Kbps requires 80 microseconds per byte");
    CHECK(speed_bucket_wait_ns(&bucket, 1460, 0) == 116800000,
          "one MSS waits at the configured 100 Kbps rate");
    CHECK(speed_bucket_wait_ns(&bucket, 3126, 0) == UINT64_MAX,
          "a request larger than capacity is reported as impossible");
    CHECK(speed_bucket_wait_ns(&bucket, 1, 80000) == 0,
          "wait ends when enough tokens have accrued");
}

static void test_refill_caps_at_burst_capacity(void) {
    struct speed_bucket bucket;
    speed_bucket_init(&bucket, 32000, 0);
    CHECK(speed_bucket_consume(&bucket, 8000, 0), "drain initial burst");
    CHECK(speed_bucket_available(&bucket, UINT64_C(10000000000)) == 8000,
          "long idle period cannot accrue beyond burst capacity");
    CHECK(bucket.refill_remainder == 0,
          "fractional credit is cleared when bucket reaches capacity");
}

static void test_live_rate_update_preserves_credit_safely(void) {
    struct speed_bucket bucket;
    speed_bucket_init(&bucket, 12500, 0);
    CHECK(speed_bucket_consume(&bucket, 3125, 0), "drain initial burst");

    speed_bucket_update(&bucket, 25000, 0);
    CHECK(speed_bucket_wait_ns(&bucket, 1000, 0) == 40000000,
          "live rate increase applies without restarting the bucket");

    speed_bucket_update(&bucket, 0, 0);
    CHECK(speed_bucket_available(&bucket, 0) == UINT64_MAX,
          "zero rate means unlimited");
    CHECK(speed_bucket_consume(&bucket, UINT64_C(1000000000), 0),
          "unlimited mode never blocks on token availability");

    speed_bucket_update(&bucket, 12500, 0);
    CHECK(speed_bucket_available(&bucket, 0) == 3125,
          "resuming from unlimited starts with one bounded burst");
}

static void test_large_elapsed_refill_saturates_without_integer_wrap(void) {
    struct speed_bucket bucket;
    uint64_t rate = UINT64_MAX / 2;
    speed_bucket_init(&bucket, rate, 0);
    uint64_t capacity = bucket.capacity_bytes;
    CHECK(speed_bucket_consume(&bucket, capacity, 0), "drain large-rate bucket");
    CHECK(speed_bucket_available(&bucket, UINT64_MAX) == capacity,
          "large elapsed-time multiplication saturates instead of wrapping");
}

static void test_monotonic_clock_regression_is_harmless(void) {
    struct speed_bucket bucket;
    speed_bucket_init(&bucket, 12500, 1000);
    CHECK(speed_bucket_consume(&bucket, 3125, 1000), "drain initial burst");
    CHECK(speed_bucket_available(&bucket, 900) == 0,
          "a non-monotonic test timestamp cannot create tokens");
    CHECK(bucket.last_ns == 1000,
          "backwards time does not move the refill baseline backwards");
}

int main(void) {
    test_preset_burst_sizes();
    test_initial_burst_and_consumption();
    test_fractional_refill_is_not_lost();
    test_wait_calculation();
    test_refill_caps_at_burst_capacity();
    test_live_rate_update_preserves_credit_safely();
    test_large_elapsed_refill_saturates_without_integer_wrap();
    test_monotonic_clock_regression_is_harmless();

    if (failures != 0) {
        fprintf(stderr, "speed_limit_test: %d failure(s)\n", failures);
        return EXIT_FAILURE;
    }

    puts("speed_limit_test: all tests passed");
    return EXIT_SUCCESS;
}
