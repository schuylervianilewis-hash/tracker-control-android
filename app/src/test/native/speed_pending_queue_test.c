#include "speed_pending_queue.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures;

#define CHECK(condition, message) do { \
    if (!(condition)) { \
        fprintf(stderr, "FAIL: %s\\n", message); \
        failures++; \
    } \
} while (0)

static void test_deadline_order_and_no_early_release(void) {
    struct speed_pending_queue queue;
    const uint8_t a[] = {1, 2, 3};
    const uint8_t b[] = {4, 5};
    const uint8_t c[] = {6};
    uint8_t *packet = NULL;
    size_t length = 0;
    uint64_t deadline = 0;

    CHECK(speed_pending_queue_init(&queue, 8, 64) == 0, "initialize queue");
    CHECK(speed_pending_queue_push(&queue, 300, a, sizeof(a)) == 0, "queue late packet");
    CHECK(speed_pending_queue_push(&queue, 100, b, sizeof(b)) == 0, "queue early packet");
    CHECK(speed_pending_queue_push(&queue, 100, c, sizeof(c)) == 0, "queue equal-deadline packet");
    CHECK(queue.packet_count == 3 && queue.queued_bytes == 6, "account queued capacity");
    CHECK(speed_pending_queue_next_deadline(&queue, &deadline) == 1 && deadline == 100,
          "expose earliest deadline");

    CHECK(speed_pending_queue_pop_ready(&queue, 99, &packet, &length) == 0,
          "never release before deadline");
    CHECK(packet == NULL && length == 0, "empty pop clears output values");

    CHECK(speed_pending_queue_pop_ready(&queue, 100, &packet, &length) == 1,
          "release first due packet");
    CHECK(length == sizeof(b) && memcmp(packet, b, length) == 0,
          "earliest packet released first");
    free(packet);

    CHECK(speed_pending_queue_pop_ready(&queue, 100, &packet, &length) == 1,
          "release equal-deadline packet");
    CHECK(length == sizeof(c) && memcmp(packet, c, length) == 0,
          "equal deadlines preserve arrival order");
    free(packet);

    CHECK(speed_pending_queue_pop_ready(&queue, 299, &packet, &length) == 0,
          "later packet remains queued");
    CHECK(speed_pending_queue_pop_ready(&queue, 300, &packet, &length) == 1,
          "release packet at its deadline");
    CHECK(length == sizeof(a) && memcmp(packet, a, length) == 0,
          "packet bytes are copied and preserved");
    free(packet);
    CHECK(queue.packet_count == 0 && queue.queued_bytes == 0, "drain counters return to zero");
    CHECK(speed_pending_queue_next_deadline(&queue, &deadline) == 0, "empty queue has no deadline");
    speed_pending_queue_clear(&queue);
}

static void test_packet_and_byte_bounds(void) {
    struct speed_pending_queue queue;
    const uint8_t packet[] = {1, 2, 3, 4};
    CHECK(speed_pending_queue_init(&queue, 2, 8) == 0, "initialize bounded queue");
    CHECK(speed_pending_queue_push(&queue, 1, packet, sizeof(packet)) == 0,
          "first packet fits");
    CHECK(speed_pending_queue_push(&queue, 2, packet, sizeof(packet)) == 0,
          "second packet fits byte budget");
    CHECK(speed_pending_queue_push(&queue, 3, packet, 1) == -2,
          "packet-count limit rejects overflow");
    CHECK(queue.packet_count == 2 && queue.queued_bytes == 8,
          "queue counters remain unchanged after packet-count rejection");
    speed_pending_queue_clear(&queue);

    CHECK(speed_pending_queue_init(&queue, 8, 7) == 0, "initialize byte-bounded queue");
    CHECK(speed_pending_queue_push(&queue, 1, packet, sizeof(packet)) == 0,
          "first packet fits byte budget");
    CHECK(speed_pending_queue_push(&queue, 2, packet, sizeof(packet)) == -2,
          "byte limit rejects overflow");
    CHECK(queue.packet_count == 1 && queue.queued_bytes == sizeof(packet),
          "byte overflow does not corrupt queue accounting");
    speed_pending_queue_clear(&queue);
}

static void test_input_validation(void) {
    struct speed_pending_queue queue;
    const uint8_t packet[] = {1};
    uint8_t *out = NULL;
    size_t length = 0;
    CHECK(speed_pending_queue_init(NULL, 1, 1) == -1, "reject null queue");
    CHECK(speed_pending_queue_init(&queue, 0, 1) == -1, "reject zero packet capacity");
    CHECK(speed_pending_queue_init(&queue, 1, 0) == -1, "reject zero byte capacity");
    CHECK(speed_pending_queue_init(&queue, 1, 1) == 0, "initialize valid queue");
    CHECK(speed_pending_queue_push(&queue, 1, NULL, 1) == -1, "reject null packet");
    CHECK(speed_pending_queue_push(&queue, 1, packet, 0) == -1, "reject empty packet");
    CHECK(speed_pending_queue_pop_ready(NULL, 1, &out, &length) == -1, "reject null pop queue");
    CHECK(speed_pending_queue_pop_ready(&queue, 1, NULL, &length) == -1, "reject null packet output");
    speed_pending_queue_clear(&queue);
}

int main(void) {
    test_deadline_order_and_no_early_release();
    test_packet_and_byte_bounds();
    test_input_validation();
    if (failures != 0) {
        fprintf(stderr, "speed_pending_queue_test: %d failure(s)\\n", failures);
        return EXIT_FAILURE;
    }
    puts("speed_pending_queue_test: all tests passed");
    return EXIT_SUCCESS;
}
