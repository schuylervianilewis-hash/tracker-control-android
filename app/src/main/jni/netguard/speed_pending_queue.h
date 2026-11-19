/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef TRACKERCONTROL_SPEED_PENDING_QUEUE_H
#define TRACKERCONTROL_SPEED_PENDING_QUEUE_H

#include <stddef.h>
#include <stdint.h>

/*
 * Bounded, non-blocking packet holding queue for the native Direct datapath.
 * The caller supplies monotonic absolute deadlines. Queue operations never
 * sleep, wait for capacity, or retain the caller's packet buffer.
 */
#define SPEED_PENDING_REDIRECT_MAX 46

enum speed_pending_kind {
    SPEED_PENDING_DIRECT_UPLOAD = 1,
    SPEED_PENDING_TUN_DOWNLOAD = 2
};

struct speed_pending_metadata {
    int kind;
    int protocol;
    int uid;
    size_t payload_offset;
    int has_redirect;
    int redirect_port;
    char redirect_addr[SPEED_PENDING_REDIRECT_MAX];
};

struct speed_pending_entry;

struct speed_pending_queue {
    struct speed_pending_entry *head;
    size_t packet_count;
    size_t queued_bytes;
    size_t max_packets;
    size_t max_bytes;
};

int speed_pending_queue_init(struct speed_pending_queue *queue,
                             size_t max_packets, size_t max_bytes);

/* 0 = queued, -1 = invalid input, -2 = capacity exceeded, -3 = allocation failed. */
int speed_pending_queue_push_ex(struct speed_pending_queue *queue,
                                uint64_t deadline_ns,
                                const uint8_t *packet, size_t packet_len,
                                const struct speed_pending_metadata *metadata);

int speed_pending_queue_push(struct speed_pending_queue *queue,
                             uint64_t deadline_ns,
                             const uint8_t *packet, size_t packet_len);

/* 1 = packet returned, 0 = no packet due, -1 = invalid arguments.
 * The returned packet is malloc-owned by the caller and must be free()'d.
 */
int speed_pending_queue_pop_ready_ex(struct speed_pending_queue *queue,
                                     uint64_t now_ns,
                                     uint8_t **packet_out,
                                     size_t *packet_len_out,
                                     struct speed_pending_metadata *metadata_out);

int speed_pending_queue_pop_ready(struct speed_pending_queue *queue,
                                  uint64_t now_ns,
                                  uint8_t **packet_out,
                                  size_t *packet_len_out);

/* 1 = deadline available, 0 = queue empty. */
int speed_pending_queue_next_deadline(const struct speed_pending_queue *queue,
                                      uint64_t *deadline_ns_out);

void speed_pending_queue_clear(struct speed_pending_queue *queue);

#endif
