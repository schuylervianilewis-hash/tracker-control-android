/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "speed_pending_queue.h"

#include <stdlib.h>
#include <string.h>

struct speed_pending_entry {
    uint64_t deadline_ns;
    size_t packet_len;
    struct speed_pending_metadata metadata;
    uint8_t *packet;
    struct speed_pending_entry *next;
};

int speed_pending_queue_init(struct speed_pending_queue *queue,
                             size_t max_packets, size_t max_bytes) {
    if (queue == NULL || max_packets == 0 || max_bytes == 0)
        return -1;

    memset(queue, 0, sizeof(*queue));
    queue->max_packets = max_packets;
    queue->max_bytes = max_bytes;
    return 0;
}

int speed_pending_queue_push_ex(struct speed_pending_queue *queue,
                                uint64_t deadline_ns,
                                const uint8_t *packet, size_t packet_len,
                                const struct speed_pending_metadata *metadata) {
    if (queue == NULL || packet == NULL || packet_len == 0 ||
        queue->max_packets == 0 || queue->max_bytes == 0)
        return -1;

    if (queue->packet_count >= queue->max_packets ||
        packet_len > queue->max_bytes - queue->queued_bytes)
        return -2;

    struct speed_pending_entry *entry = malloc(sizeof(*entry));
    if (entry == NULL)
        return -3;

    entry->packet = malloc(packet_len);
    if (entry->packet == NULL) {
        free(entry);
        return -3;
    }
    memcpy(entry->packet, packet, packet_len);
    entry->deadline_ns = deadline_ns;
    entry->packet_len = packet_len;
    if (metadata != NULL)
        entry->metadata = *metadata;
    else
        memset(&entry->metadata, 0, sizeof(entry->metadata));
    entry->next = NULL;

    /* Stable insertion: packets with equal deadlines retain arrival order. */
    struct speed_pending_entry **cursor = &queue->head;
    while (*cursor != NULL && (*cursor)->deadline_ns <= deadline_ns)
        cursor = &(*cursor)->next;
    entry->next = *cursor;
    *cursor = entry;

    queue->packet_count++;
    queue->queued_bytes += packet_len;
    return 0;
}

int speed_pending_queue_pop_ready_ex(struct speed_pending_queue *queue,
                                     uint64_t now_ns,
                                     uint8_t **packet_out,
                                     size_t *packet_len_out,
                                     struct speed_pending_metadata *metadata_out) {
    if (queue == NULL || packet_out == NULL || packet_len_out == NULL)
        return -1;

    *packet_out = NULL;
    *packet_len_out = 0;
    if (metadata_out != NULL)
        memset(metadata_out, 0, sizeof(*metadata_out));
    struct speed_pending_entry *entry = queue->head;
    if (entry == NULL || entry->deadline_ns > now_ns)
        return 0;

    queue->head = entry->next;
    queue->packet_count--;
    queue->queued_bytes -= entry->packet_len;
    *packet_out = entry->packet;
    *packet_len_out = entry->packet_len;
    if (metadata_out != NULL)
        *metadata_out = entry->metadata;
    free(entry);
    return 1;
}

int speed_pending_queue_push(struct speed_pending_queue *queue,
                             uint64_t deadline_ns,
                             const uint8_t *packet, size_t packet_len) {
    return speed_pending_queue_push_ex(queue, deadline_ns, packet, packet_len, NULL);
}

int speed_pending_queue_pop_ready(struct speed_pending_queue *queue,
                                  uint64_t now_ns,
                                  uint8_t **packet_out,
                                  size_t *packet_len_out) {
    return speed_pending_queue_pop_ready_ex(queue, now_ns, packet_out,
                                            packet_len_out, NULL);
}

int speed_pending_queue_next_deadline(const struct speed_pending_queue *queue,
                                      uint64_t *deadline_ns_out) {
    if (queue == NULL || deadline_ns_out == NULL)
        return 0;
    if (queue->head == NULL)
        return 0;
    *deadline_ns_out = queue->head->deadline_ns;
    return 1;
}

void speed_pending_queue_clear(struct speed_pending_queue *queue) {
    if (queue == NULL)
        return;

    struct speed_pending_entry *entry = queue->head;
    while (entry != NULL) {
        struct speed_pending_entry *next = entry->next;
        free(entry->packet);
        free(entry);
        entry = next;
    }
    queue->head = NULL;
    queue->packet_count = 0;
    queue->queued_bytes = 0;
}
