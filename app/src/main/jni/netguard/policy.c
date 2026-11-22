/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Copyright © 2026
 */

// Routing policy for both egress paths. The decision itself lives in
// wgbridge-rs/src/policy.rs, where `cargo test` covers it in CI; this file
// reaches it, caches the handful of facts the packet path needs so the common
// case never crosses the boundary at all, and remembers a flow's verdict.
// The pure packet decision is mirrored below so the packet path does not cross
// the Rust FFI boundary on every packet; the equivalent Rust function remains
// the tested definition of that decision.

#include "netguard.h"

#include <dlfcn.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <sys/timerfd.h>

#define POLICY_ABI_VERSION 2

static pthread_once_t policy_once = PTHREAD_ONCE_INIT;
static int policy_ok = 0;
static int speed_ok = 0;

static int (*p_abi_version)(void) = NULL;
static void (*p_set_route_uids)(const jint *uids, int count, int default_tunnel) = NULL;
static void (*p_clear_route_uids)(void) = NULL;
static int (*p_is_tunnel_uid)(jint uid) = NULL;
static int (*p_wants_tunnel)(int local_dest, int is_dns, int tunnel_uid, int dns_direct) = NULL;
static int (*p_dest_tunnelled)(int version, const void *daddr) = NULL;
static int (*p_speed_configure)(uint64_t upload_bps, uint64_t download_bps,
                                const int32_t *uids, size_t uid_count) = NULL;
static int64_t (*p_speed_reserve)(int32_t direction, int32_t uid,
                                  size_t bytes, int32_t dns_port_53) = NULL;
static int (*p_speed_record)(int32_t direction, uint64_t bytes) = NULL;
static void (*p_speed_bypass)(uint64_t bytes) = NULL;
static void (*p_speed_delayed)(uint64_t bytes) = NULL;
static int (*p_speed_snapshot)(uint64_t *out, size_t out_len) = NULL;
static void (*p_speed_reset_queue_peaks)(void) = NULL;

// Facts the packet path reads per packet. Mirrored here rather than queried
// across the boundary: with no per-app override configured — the shipped
// default — every UID gets the same answer, and rediscovering that per packet
// is what made the fork expensive enough to show up as degraded DNS.
static _Atomic int policy_has_overrides = 0;
static _Atomic int policy_default_tunnel = 1;
static _Atomic int policy_dns_direct = 0;

static void policy_load() {
    // Java loads libnetguard only, so the bridge is resolved here rather than
    // linked: a DT_NEEDED would stop libnetguard loading at all whenever the
    // Rust library is missing, and would couple the CMake output to a cargo
    // one that is deliberately built late (see app/gradle/wgbridge.gradle).
    // Java's own System.loadLibrary("wgbridge") returns this same soinfo when
    // the tunnel starts, so there is exactly one policy table. Never dlclose.
    void *handle = dlopen("libwgbridge.so", RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        log_android(ANDROID_LOG_ERROR, "policy: cannot load libwgbridge: %s", dlerror());
        return;
    }

    p_speed_configure = dlsym(handle, "tc_speed_prototype_configure");
    p_speed_reserve = dlsym(handle, "tc_speed_prototype_reserve");
    p_speed_record = dlsym(handle, "tc_speed_prototype_record");
    p_speed_bypass = dlsym(handle, "tc_speed_prototype_bypass");
    p_speed_delayed = dlsym(handle, "tc_speed_prototype_delayed");
    p_speed_snapshot = dlsym(handle, "tc_speed_prototype_snapshot");
    p_speed_reset_queue_peaks = dlsym(handle, "tc_speed_prototype_reset_queue_peaks");
    speed_ok = p_speed_configure != NULL && p_speed_reserve != NULL &&
               p_speed_record != NULL && p_speed_bypass != NULL && p_speed_delayed != NULL &&
               p_speed_snapshot != NULL;
    if (!speed_ok)
        log_android(ANDROID_LOG_WARN, "speed shaping: shared limiter symbols unavailable");

    p_abi_version = dlsym(handle, "tc_policy_abi_version");
    p_set_route_uids = dlsym(handle, "tc_policy_set_route_uids");
    p_clear_route_uids = dlsym(handle, "tc_policy_clear_route_uids");
    p_is_tunnel_uid = dlsym(handle, "tc_policy_is_tunnel_uid");
    p_wants_tunnel = dlsym(handle, "tc_policy_wants_tunnel");
    p_dest_tunnelled = dlsym(handle, "tc_policy_dest_tunnelled");

    if (p_abi_version == NULL || p_set_route_uids == NULL || p_clear_route_uids == NULL ||
        p_is_tunnel_uid == NULL || p_wants_tunnel == NULL || p_dest_tunnelled == NULL) {
        log_android(ANDROID_LOG_ERROR, "policy: missing symbol: %s", dlerror());
        return;
    }

    int abi = p_abi_version();
    if (abi != POLICY_ABI_VERSION) {
        log_android(ANDROID_LOG_ERROR, "policy: ABI %d, expected %d", abi, POLICY_ABI_VERSION);
        return;
    }

    policy_ok = 1;
    log_android(ANDROID_LOG_WARN, "policy: libwgbridge %p ABI %d", handle, abi);
}

void policy_ensure() {
    pthread_once(&policy_once, policy_load);
}

int speed_limit_configure(uint64_t upload_bytes_per_second,
                          uint64_t download_bytes_per_second,
                          const jint *exempt_uids, size_t exempt_count) {
    policy_ensure();
    if (!speed_ok)
        return -1;
    return p_speed_configure(upload_bytes_per_second, download_bytes_per_second,
                             (const int32_t *) exempt_uids, exempt_count);
}

void speed_limit_reset_queue_peaks(void) {
    policy_ensure();
    if (p_speed_reset_queue_peaks != NULL)
        p_speed_reset_queue_peaks();
}

int64_t speed_limit_reserve(int direction, jint uid, size_t bytes, int dns_port_53) {
    policy_ensure();
    if (!speed_ok)
        return 0;
    return p_speed_reserve(direction, uid, bytes, dns_port_53);
}

void speed_limit_record(int direction, size_t bytes) {
    policy_ensure();
    if (speed_ok)
        p_speed_record(direction, (uint64_t) bytes);
}

void speed_limit_record_delayed(size_t bytes) {
    policy_ensure();
    if (speed_ok)
        p_speed_delayed((uint64_t) bytes);
}

void speed_limit_record_bypass(size_t bytes) {
    policy_ensure();
    if (speed_ok)
        p_speed_bypass((uint64_t) bytes);
}

int speed_limit_snapshot(uint64_t *out, size_t out_len) {
    policy_ensure();
    if (!speed_ok || out == NULL || out_len < 13)
        return -1;
    return p_speed_snapshot(out, out_len);
}

static uint64_t speed_monotonic_ns(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0)
        return 0;
    return (uint64_t) now.tv_sec * 1000000000ULL + (uint64_t) now.tv_nsec;
}

int speed_pending_arm(struct context *ctx) {
    if (ctx == NULL || ctx->speed_timer_fd < 0)
        return 0;

    struct itimerspec timer;
    memset(&timer, 0, sizeof(timer));
    uint64_t deadline_ns = 0;
    if (speed_pending_queue_next_deadline(&ctx->speed_queue, &deadline_ns)) {
        timer.it_value.tv_sec = (time_t) (deadline_ns / 1000000000ULL);
        timer.it_value.tv_nsec = (long) (deadline_ns % 1000000000ULL);
    }
    if (timerfd_settime(ctx->speed_timer_fd, TFD_TIMER_ABSTIME, &timer, NULL) < 0) {
        size_t dropped = ctx->speed_queue.packet_count;
        speed_queue_telemetry_record_drop(&ctx->speed_direct_telemetry, dropped);
        speed_queue_telemetry_record_scheduler_error(&ctx->speed_direct_telemetry);
        speed_pending_queue_clear(&ctx->speed_queue);
        speed_queue_telemetry_publish(&ctx->speed_direct_telemetry, 0, 0);
        log_android(ANDROID_LOG_ERROR,
                    "speed shaping: timer arm failed errno %d: %s; cleared %zu queued packets",
                    errno, strerror(errno), dropped);
        return -1;
    }
    return 0;
}

int speed_shape_or_queue(const struct arguments *args, int direction, int kind,
                         const uint8_t *packet, size_t length, jint uid,
                         int dns_port_53, int protocol, size_t payload_offset,
                         const struct allowed *redirect) {
    if (args == NULL || args->ctx == NULL || packet == NULL || length == 0)
        return -1;

    int64_t wait_ns = speed_limit_reserve(direction, uid, length, dns_port_53);
    if (wait_ns <= 0)
        return 0;

    static _Atomic int upload_pacing_logged = 0;
    static _Atomic int download_pacing_logged = 0;
    _Atomic int *logged = (kind == SPEED_PENDING_DIRECT_UPLOAD
                           ? &upload_pacing_logged : &download_pacing_logged);
    if (atomic_exchange_explicit(logged, 1, memory_order_relaxed) == 0)
        log_android(ANDROID_LOG_WARN,
                    "speed shaping: Direct %s pacing active",
                    kind == SPEED_PENDING_DIRECT_UPLOAD ? "upload" : "download");

    if (args->ctx->speed_timer_fd < 0) {
        speed_limit_record_bypass(length);
        return 0;
    }

    uint64_t now_ns = speed_monotonic_ns();
    if (now_ns == 0 || (uint64_t) wait_ns > UINT64_MAX - now_ns) {
        speed_limit_record_bypass(length);
        return 0;
    }

    struct speed_pending_metadata metadata;
    memset(&metadata, 0, sizeof(metadata));
    metadata.kind = kind;
    metadata.protocol = protocol;
    metadata.uid = uid;
    metadata.payload_offset = payload_offset;
    if (redirect != NULL) {
        metadata.has_redirect = 1;
        metadata.redirect_port = redirect->rport;
        memcpy(metadata.redirect_addr, redirect->raddr,
               sizeof(metadata.redirect_addr) - 1);
        metadata.redirect_addr[sizeof(metadata.redirect_addr) - 1] = '\0';
    }

    int rc = speed_pending_queue_push_ex(&args->ctx->speed_queue,
                                         now_ns + (uint64_t) wait_ns,
                                         packet, length, &metadata);
    if (rc == -3) {
        // Allocation pressure must not turn a TCP pacing decision into a
        // payload drop. Fail open for this packet and keep firewall policy.
        speed_limit_record_bypass(length);
        static _Atomic int allocation_warning_logged = 0;
        if (atomic_exchange_explicit(&allocation_warning_logged, 1,
                                     memory_order_relaxed) == 0)
            log_android(ANDROID_LOG_WARN,
                        "speed shaping: queue allocation failed; bypassing pacing for affected packets");
        return 0;
    }
    if (rc != 0) {
        speed_queue_telemetry_record_drop(&args->ctx->speed_direct_telemetry, 1);
        static _Atomic unsigned long queue_drops = 0;
        unsigned long count = atomic_fetch_add_explicit(&queue_drops, 1,
                                                        memory_order_relaxed) + 1;
        if ((count & 255UL) == 1)
            log_android(ANDROID_LOG_WARN,
                        "speed shaping: pending queue rejected %lu packets (status %d)",
                        count, rc);
        return -1;
    }

    speed_queue_telemetry_publish(&args->ctx->speed_direct_telemetry,
                                   args->ctx->speed_queue.packet_count,
                                   args->ctx->speed_queue.queued_bytes);
    if (speed_pending_arm(args->ctx) < 0)
        return -1;
    return 1;
}

extern FILE *pcap_file;

ssize_t speed_write_tun_packet(const struct arguments *args,
                               const uint8_t *packet, size_t length,
                               jint uid, int dns_port_53) {
    int queued = speed_shape_or_queue(args, 1, SPEED_PENDING_TUN_DOWNLOAD,
                                      packet, length, uid, dns_port_53,
                                      0, 0, NULL);
    if (queued > 0)
        return (ssize_t) length;
    if (queued < 0)
        return -1;

    ssize_t written = write(args->tun, packet, length);
    if (written == (ssize_t) length)
        speed_limit_record(1, length);
    else if (written < 0 || written != (ssize_t) length)
        speed_queue_telemetry_record_drop(&args->ctx->speed_direct_telemetry, 1);
    if (written >= 0 && pcap_file != NULL)
        write_pcap_rec(packet, (size_t) written);
    return written;
}

void speed_pending_dispatch_upload(const struct arguments *args,
                                   const uint8_t *packet, size_t length,
                                   const struct speed_pending_metadata *metadata,
                                   int epoll_fd) {
    if (metadata == NULL || metadata->kind != SPEED_PENDING_DIRECT_UPLOAD ||
        metadata->payload_offset >= length)
        return;

    struct allowed redirect;
    struct allowed *redirect_ptr = NULL;
    if (metadata->has_redirect) {
        memset(&redirect, 0, sizeof(redirect));
        snprintf(redirect.raddr, sizeof(redirect.raddr), "%s", metadata->redirect_addr);
        redirect.rport = (uint16_t) metadata->redirect_port;
        redirect_ptr = &redirect;
    }
    speed_limit_record_delayed(length);
    dispatch_direct_packet(args, packet, length, (uint8_t) metadata->protocol,
                           metadata->payload_offset, metadata->uid,
                           redirect_ptr, epoll_fd);
}

void speed_pending_drain(const struct arguments *args, int epoll_fd) {
    if (args == NULL || args->ctx == NULL)
        return;

    uint64_t expirations;
    if (args->ctx->speed_timer_fd >= 0) {
        ssize_t n = read(args->ctx->speed_timer_fd, &expirations, sizeof(expirations));
        if (n < 0 && errno != EAGAIN && errno != EINTR) {
            speed_queue_telemetry_record_scheduler_error(&args->ctx->speed_direct_telemetry);
            log_android(ANDROID_LOG_WARN, "speed shaping: timer read failed: %s",
                        strerror(errno));
        }
    }

    for (int i = 0; i < 128; i++) {
        uint8_t *packet = NULL;
        size_t length = 0;
        struct speed_pending_metadata metadata;
        int rc = speed_pending_queue_pop_ready_ex(&args->ctx->speed_queue,
                                                  speed_monotonic_ns(),
                                                  &packet, &length, &metadata);
        if (rc <= 0)
            break;
        speed_queue_telemetry_publish(&args->ctx->speed_direct_telemetry,
                                       args->ctx->speed_queue.packet_count,
                                       args->ctx->speed_queue.queued_bytes);

        if (metadata.kind == SPEED_PENDING_DIRECT_UPLOAD) {
            speed_pending_dispatch_upload(args, packet, length, &metadata, epoll_fd);
        } else if (metadata.kind == SPEED_PENDING_TUN_DOWNLOAD) {
            ssize_t written = write(args->tun, packet, length);
            if (written == (ssize_t) length) {
                if (pcap_file != NULL)
                    write_pcap_rec(packet, length);
                speed_limit_record_delayed(length);
                speed_limit_record(1, length);
            } else {
                speed_queue_telemetry_record_drop(&args->ctx->speed_direct_telemetry, 1);
                log_android(ANDROID_LOG_WARN,
                            "speed shaping: delayed TUN write %zd/%zu failed errno %d: %s",
                            written, length, errno, strerror(errno));
            }
        }
        free(packet);
    }
    speed_pending_arm(args->ctx);
}


void set_route_uids(const jint *uids, int count, int default_tunnel, int dns_direct) {
    policy_ensure();

    if (count < 0 || uids == NULL)
        count = 0;

    if (policy_ok)
        p_set_route_uids(uids, count, default_tunnel);

    // A missing/incompatible bridge cannot honour a selected-app policy. Keep
    // the conservative state instead: all eligible traffic takes the tunnel,
    // and direct-app DNS is not allowed to opt out of that protection. This is
    // deliberately independent of the requested default; otherwise a failed
    // load in selected mode would silently route the selected app directly.
    int effective_default_tunnel = policy_ok ? (default_tunnel != 0) : 1;
    int effective_dns_direct = policy_ok ? (dns_direct != 0) : 0;
    atomic_store_explicit(&policy_default_tunnel, effective_default_tunnel,
                          memory_order_release);
    atomic_store_explicit(&policy_dns_direct, effective_dns_direct,
                          memory_order_release);
    // Without the bridge there is no table to consult, so pin every UID to the
    // safe global default. A per-app choice is ignored rather than leaking out
    // of the tunnel.
    atomic_store_explicit(&policy_has_overrides,
                          (policy_ok && count > 0) ? 1 : 0, memory_order_release);

    // Verdicts cached against the previous rules must not survive them.
    route_flow_invalidate();
}

void clear_route_uids() {
    policy_ensure();

    if (policy_ok)
        p_clear_route_uids();

    atomic_store_explicit(&policy_has_overrides, 0, memory_order_release);
    atomic_store_explicit(&policy_default_tunnel, 1, memory_order_release);
    atomic_store_explicit(&policy_dns_direct, 0, memory_order_release);

    route_flow_invalidate();
}

int is_tunnel_uid(jint uid) {
    if (policy_ok)
        return p_is_tunnel_uid(uid);

    return route_default_is_tunnel();
}

/**
 * Whether resolving this packet's UID can change the routing answer.
 *
 * With no per-app override configured, every UID resolves to the same global
 * default, so the packet path can skip the UID lookup and the boundary
 * crossing. This is the shipped default, and keeping it free is what holds the
 * per-packet cost at what it was before per-app routing existed.
 */
int route_uid_relevant() {
    return atomic_load_explicit(&policy_has_overrides, memory_order_acquire);
}

/** The answer every UID gets when no override is configured. */
int route_default_is_tunnel() {
    return atomic_load_explicit(&policy_default_tunnel, memory_order_acquire);
}

/** Whether direct apps' DNS is redirected, in which case DNS follows the UID. */
int route_dns_direct() {
    return atomic_load_explicit(&policy_dns_direct, memory_order_acquire);
}

int route_wants_tunnel(int local_dest, int is_dns, int tunnel_uid, int dns_direct) {
    // This is the literal transcription of policy::wants_tunnel. Keep the
    // pure branch local: crossing the Rust boundary for every packet adds
    // overhead without consulting any mutable policy state. The Rust function
    // remains exported and exhaustively tested as the policy definition.
    if (local_dest && !is_dns)
        return 0;
    if (is_dns && !dns_direct)
        return 1;
    return tunnel_uid;
}

// Whether the running tunnel carries this destination: a split-tunnel profile
// (neither address family fully covered) only carries its AllowedIPs, and gotatun
// would drop the rest. Rust owns the prefixes because it already parses them
// for gotatun, so the two cannot disagree. Only asked once a packet would
// otherwise take the tunnel; a full tunnel answers from one atomic load there.
int route_dest_tunnelled(int version, const void *daddr) {
    policy_ensure();
    // Without the bridge there is no tunnel to split.
    return policy_ok ? p_dest_tunnelled(version, daddr) : 1;
}

// --- Per-flow verdict cache -------------------------------------------------
//
// A tunnelled packet is handed to WireGuard and returns before handle_tcp /
// handle_udp run, so no ng_session is ever created for it. Meanwhile the UID
// lookup upstream is skipped for non-SYN TCP and existing UDP, so packet 2+ of
// a tunnelled flow arrives with uid == -1 and nothing left to resolve it from.
// Falling back to the global default there is wrong in exactly the mode the
// feature exists for: in "selected" mode the default is direct, so the rest of
// a tunnelled TCP flow was routed direct and handle_tcp, finding no session for
// a non-SYN segment, answered it with an RST.
//
// So remember the verdict per flow, keyed on the 5-tuple, and consult it before
// giving up. This is a cache rather than policy, which is why it stays on this
// side of the boundary. Written and read only by the tunnel thread, which is
// why nothing here takes a lock; a reload bumps route_flow_gen instead of
// clearing the table, so entries decided under superseded rules stop matching.

// 4-way set-associative: same 1024-entry / ~56 KB budget as the original
// direct-mapped table, but two concurrently active flows that hash to the
// same set no longer evict each other every packet. A direct-mapped table
// made that eviction ping-pong guaranteed for any colliding pair of busy
// flows, and every miss it caused fell through to a per-packet Binder/procfs
// UID lookup — the exact cost this cache exists to avoid.
#define ROUTE_FLOW_WAYS 4
#define ROUTE_FLOW_SETS 256         // power of two; SETS * WAYS = 1024 entries
#define ROUTE_FLOW_MAX_AGE 300      // seconds idle before an entry is reusable

// Numeric TCP ownership is separate from the route/policy cache.  A route
// entry only needs to remember whether its route lookup succeeded; policy
// revalidation needs the actual owner when Android no longer reports the
// original app UID while a closing socket is disappearing from the kernel
// table.
#define TCP_OWNER_WAYS 4
#define TCP_OWNER_SETS 256         // bounded to 1024 retained TCP owners
#define TCP_OWNER_MAX_AGE 300      // seconds idle before an owner is forgotten

struct route_flow_entry {
    uint32_t gen;                   // 0 = never written
    uint8_t version;
    uint8_t protocol;
    uint8_t tunnel;
    uint8_t uid_known;
    int8_t verdict;                  // -1 unknown, 0 blocked, 1 allowed
    uint16_t sport;
    uint16_t dport;
    uint8_t saddr[16];
    uint8_t daddr[16];
    time_t time;
};

static struct route_flow_entry route_flows[ROUTE_FLOW_SETS][ROUTE_FLOW_WAYS];
static _Atomic uint32_t route_flow_gen = 1;

struct tcp_owner_entry {
    uint32_t gen;
    uint8_t valid;
    uint8_t version;
    uint16_t sport;
    uint16_t dport;
    uint8_t saddr[16];
    uint8_t daddr[16];
    jint uid;
    struct timespec last_used;
};

static struct tcp_owner_entry tcp_owners[TCP_OWNER_SETS][TCP_OWNER_WAYS];
static _Atomic uint32_t tcp_owner_gen = 1;

void route_flow_invalidate() {
    // Wrapping to 0 would make every never-written slot look current, so skip it.
    uint32_t next = atomic_fetch_add_explicit(&route_flow_gen, 1, memory_order_release) + 1;
    if (next == 0)
        atomic_store_explicit(&route_flow_gen, 1, memory_order_release);
}

static size_t route_flow_set(int version, int protocol,
                             const void *saddr, uint16_t sport,
                             const void *daddr, uint16_t dport) {
    size_t alen = (version == 4 ? 4u : 16u);
    // FNV-1a
    uint32_t h = 2166136261u;
    const uint8_t *s = saddr;
    const uint8_t *d = daddr;
    for (size_t i = 0; i < alen; i++) {
        h = (h ^ s[i]) * 16777619u;
        h = (h ^ d[i]) * 16777619u;
    }
    h = (h ^ (uint8_t) version) * 16777619u;
    h = (h ^ (uint8_t) protocol) * 16777619u;
    h = (h ^ (uint8_t) (sport & 0xff)) * 16777619u;
    h = (h ^ (uint8_t) (sport >> 8)) * 16777619u;
    h = (h ^ (uint8_t) (dport & 0xff)) * 16777619u;
    h = (h ^ (uint8_t) (dport >> 8)) * 16777619u;
    return (size_t) (h & (ROUTE_FLOW_SETS - 1));
}

static int tcp_owner_now(struct timespec *now) {
    return clock_gettime(CLOCK_MONOTONIC, now) == 0;
}

static int tcp_owner_expired(const struct timespec *now,
                             const struct timespec *last_used) {
    if (now->tv_sec < last_used->tv_sec ||
        (now->tv_sec == last_used->tv_sec && now->tv_nsec < last_used->tv_nsec))
        return 0;

    time_t seconds = now->tv_sec - last_used->tv_sec;
    return seconds > TCP_OWNER_MAX_AGE ||
           (seconds == TCP_OWNER_MAX_AGE && now->tv_nsec >= last_used->tv_nsec);
}

static int tcp_owner_matches(const struct tcp_owner_entry *entry,
                             uint32_t gen,
                             int version,
                             const void *saddr, uint16_t sport,
                             const void *daddr, uint16_t dport) {
    size_t alen = version == 4 ? 4u : 16u;
    return entry->valid && entry->gen == gen && entry->version == (uint8_t) version &&
           entry->sport == sport && entry->dport == dport &&
           memcmp(entry->saddr, saddr, alen) == 0 &&
           memcmp(entry->daddr, daddr, alen) == 0;
}

int tcp_owner_lookup(int version,
                     const void *saddr, uint16_t sport,
                     const void *daddr, uint16_t dport,
                     jint *uid) {
    if ((version != 4 && version != 6) || uid == NULL)
        return 0;

    struct timespec now;
    if (!tcp_owner_now(&now))
        return 0;

    uint32_t gen = atomic_load_explicit(&tcp_owner_gen, memory_order_acquire);
    struct tcp_owner_entry *set = tcp_owners[route_flow_set(
            version, IPPROTO_TCP, saddr, sport, daddr, dport)];
    for (int way = 0; way < TCP_OWNER_WAYS; way++) {
        struct tcp_owner_entry *entry = &set[way];
        if (!tcp_owner_matches(entry, gen, version, saddr, sport, daddr, dport))
            continue;
        if (tcp_owner_expired(&now, &entry->last_used)) {
            entry->valid = 0;
            continue;
        }
        entry->last_used = now;
        *uid = entry->uid;
        return 1;
    }
    return 0;
}

void tcp_owner_store(int version,
                     const void *saddr, uint16_t sport,
                     const void *daddr, uint16_t dport,
                     jint uid) {
    if ((version != 4 && version != 6) || uid < 0)
        return;

    struct timespec now;
    if (!tcp_owner_now(&now))
        return;

    size_t alen = version == 4 ? 4u : 16u;
    uint32_t gen = atomic_load_explicit(&tcp_owner_gen, memory_order_acquire);
    struct tcp_owner_entry *set = tcp_owners[route_flow_set(
            version, IPPROTO_TCP, saddr, sport, daddr, dport)];
    struct tcp_owner_entry *victim = NULL;
    struct tcp_owner_entry *oldest = NULL;
    struct tcp_owner_entry *reusable = NULL;

    for (int way = 0; way < TCP_OWNER_WAYS; way++) {
        struct tcp_owner_entry *entry = &set[way];
        if (tcp_owner_matches(entry, gen, version, saddr, sport, daddr, dport)) {
            victim = entry;
            break;
        }
        if (reusable == NULL &&
            (entry->gen != gen || !entry->valid ||
             tcp_owner_expired(&now, &entry->last_used)))
            reusable = entry;
        if (oldest == NULL ||
            entry->last_used.tv_sec < oldest->last_used.tv_sec ||
            (entry->last_used.tv_sec == oldest->last_used.tv_sec &&
             entry->last_used.tv_nsec < oldest->last_used.tv_nsec))
            oldest = entry;
    }
    if (victim == NULL)
        victim = reusable;
    if (victim == NULL)
        victim = oldest;

    victim->valid = 1;
    victim->gen = gen;
    victim->version = (uint8_t) version;
    victim->sport = sport;
    victim->dport = dport;
    memset(victim->saddr, 0, sizeof(victim->saddr));
    memset(victim->daddr, 0, sizeof(victim->daddr));
    memcpy(victim->saddr, saddr, alen);
    memcpy(victim->daddr, daddr, alen);
    victim->uid = uid;
    victim->last_used = now;
}

void tcp_owner_forget(int version,
                      const void *saddr, uint16_t sport,
                      const void *daddr, uint16_t dport) {
    if (version != 4 && version != 6)
        return;

    uint32_t gen = atomic_load_explicit(&tcp_owner_gen, memory_order_acquire);
    struct tcp_owner_entry *set = tcp_owners[route_flow_set(
            version, IPPROTO_TCP, saddr, sport, daddr, dport)];
    for (int way = 0; way < TCP_OWNER_WAYS; way++) {
        struct tcp_owner_entry *entry = &set[way];
        if (tcp_owner_matches(entry, gen, version, saddr, sport, daddr, dport))
            entry->valid = 0;
    }
}

void tcp_owner_reset() {
    uint32_t next = atomic_fetch_add_explicit(&tcp_owner_gen, 1, memory_order_release) + 1;
    if (next == 0)
        atomic_store_explicit(&tcp_owner_gen, 1, memory_order_release);
}

static int route_flow_matches(const struct route_flow_entry *e, uint32_t gen,
                              int version, int protocol,
                              const void *saddr, uint16_t sport,
                              const void *daddr, uint16_t dport, time_t now) {
    size_t alen = (version == 4 ? 4u : 16u);
    return e->gen == gen &&
           e->version == (uint8_t) version && e->protocol == (uint8_t) protocol &&
           e->sport == sport && e->dport == dport &&
           memcmp(e->saddr, saddr, alen) == 0 && memcmp(e->daddr, daddr, alen) == 0 &&
           now - e->time <= ROUTE_FLOW_MAX_AGE;
}

static struct route_flow_entry *route_flow_find(int version, int protocol,
                                                const void *saddr, uint16_t sport,
                                                const void *daddr, uint16_t dport,
                                                uint32_t gen, time_t now) {
    struct route_flow_entry *set = route_flows[route_flow_set(
            version, protocol, saddr, sport, daddr, dport)];
    for (int way = 0; way < ROUTE_FLOW_WAYS; way++) {
        struct route_flow_entry *e = &set[way];
        if (route_flow_matches(e, gen, version, protocol, saddr, sport, daddr, dport, now))
            return e;
    }
    return NULL;
}

/**
 * The remembered verdict for this flow, if any.
 *
 * @return 1 when *tunnel was filled in, 0 on a miss.
 */
int route_flow_lookup(int version, int protocol,
                      const void *saddr, uint16_t sport,
                      const void *daddr, uint16_t dport,
                      int *tunnel, int *uid_known) {
    uint32_t gen = atomic_load_explicit(&route_flow_gen, memory_order_acquire);
    struct route_flow_entry *set = route_flows[route_flow_set(
            version, protocol, saddr, sport, daddr, dport)];

    time_t now = time(NULL);
    for (int way = 0; way < ROUTE_FLOW_WAYS; way++) {
        struct route_flow_entry *e = &set[way];
        if (!route_flow_matches(e, gen, version, protocol, saddr, sport, daddr, dport, now))
            continue;
        e->time = now;
        *tunnel = e->tunnel;
        *uid_known = e->uid_known;
        return 1;
    }
    return 0;
}

int route_flow_lookup_verdict(int version, int protocol,
                              const void *saddr, uint16_t sport,
                              const void *daddr, uint16_t dport,
                              int *verdict) {
    uint32_t gen = atomic_load_explicit(&route_flow_gen, memory_order_acquire);
    time_t now = time(NULL);
    struct route_flow_entry *e = route_flow_find(
            version, protocol, saddr, sport, daddr, dport, gen, now);
    if (e == NULL)
        return 0;

    e->time = now;
    *verdict = e->verdict;
    return 1;
}

/**
 * Remember this flow's verdict, in the first empty/stale/current-gen-oldest
 * way of its set. It is a cache, and a miss only costs the fallback path that
 * ran before it existed. uid_known distinguishes a stable per-app decision
 * from the fail-closed tunnel route used while Android cannot resolve an owner.
 */
void route_flow_store(int version, int protocol,
                      const void *saddr, uint16_t sport,
                      const void *daddr, uint16_t dport,
                      int tunnel, int uid_known) {
    size_t alen = (version == 4 ? 4u : 16u);
    uint32_t gen = atomic_load_explicit(&route_flow_gen, memory_order_acquire);
    struct route_flow_entry *set = route_flows[route_flow_set(
            version, protocol, saddr, sport, daddr, dport)];

    // Pick a way to write, cheapest-safe choice first: reuse this flow's own
    // entry if it is already cached, else an empty/superseded/expired way,
    // else evict the oldest entry in the set (a working set wider than 4
    // concurrently-hot flows per set degrades to more fallback lookups, not
    // to a wrong answer).
    time_t now = time(NULL);
    struct route_flow_entry *victim = NULL;
    int8_t verdict = ROUTE_FLOW_VERDICT_UNKNOWN;
    struct route_flow_entry *oldest = &set[0];
    for (int way = 0; way < ROUTE_FLOW_WAYS; way++) {
        struct route_flow_entry *e = &set[way];
        if (route_flow_matches(e, gen, version, protocol, saddr, sport, daddr, dport, now)) {
            victim = e;
            verdict = e->verdict;
            break;
        }
        if (victim == NULL && (e->gen != gen || now - e->time > ROUTE_FLOW_MAX_AGE))
            victim = e;
        if (e->time < oldest->time)
            oldest = e;
    }
    if (victim == NULL)
        victim = oldest;

    struct route_flow_entry *e = victim;
    e->gen = gen;
    e->version = (uint8_t) version;
    e->protocol = (uint8_t) protocol;
    e->tunnel = (uint8_t) (tunnel ? 1 : 0);
    e->uid_known = (uint8_t) (uid_known ? 1 : 0);
    e->verdict = verdict;
    e->sport = sport;
    e->dport = dport;
    memset(e->saddr, 0, sizeof(e->saddr));
    memset(e->daddr, 0, sizeof(e->daddr));
    memcpy(e->saddr, saddr, alen);
    memcpy(e->daddr, daddr, alen);
    e->time = now;
}

void route_flow_store_verdict(int version, int protocol,
                              const void *saddr, uint16_t sport,
                              const void *daddr, uint16_t dport,
                              int verdict) {
    if (verdict != ROUTE_FLOW_VERDICT_BLOCKED && verdict != ROUTE_FLOW_VERDICT_ALLOWED)
        return;

    uint32_t gen = atomic_load_explicit(&route_flow_gen, memory_order_acquire);
    time_t now = time(NULL);
    struct route_flow_entry *e = route_flow_find(
            version, protocol, saddr, sport, daddr, dport, gen, now);

    // A policy result can be produced before the route branch (for example a
    // blocked first TCP packet), so make sure there is a generation-scoped
    // entry to attach it to. Only create a missing entry here: an existing
    // route may carry a resolved per-app tunnel and uid_known decision that a
    // policy verdict must not replace with the global default.
    if (e == NULL) {
        route_flow_store(version, protocol, saddr, sport, daddr, dport,
                         route_default_is_tunnel(), 0);
        gen = atomic_load_explicit(&route_flow_gen, memory_order_acquire);
        now = time(NULL);
        e = route_flow_find(version, protocol, saddr, sport, daddr, dport, gen, now);
    }

    if (e != NULL) {
        e->verdict = (int8_t) verdict;
        e->time = now;
    }
}

void route_flow_clear_verdict(int version, int protocol,
                              const void *saddr, uint16_t sport,
                              const void *daddr, uint16_t dport) {
    uint32_t gen = atomic_load_explicit(&route_flow_gen, memory_order_acquire);
    time_t now = time(NULL);
    struct route_flow_entry *e = route_flow_find(
            version, protocol, saddr, sport, daddr, dport, gen, now);
    if (e != NULL) {
        e->verdict = ROUTE_FLOW_VERDICT_UNKNOWN;
        e->time = now;
    }
}
