#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define HEADER_LEN 16

typedef int32_t (*configure_fn)(uint64_t, uint64_t, const int32_t *, size_t);
typedef int64_t (*reserve_fn)(int32_t, int32_t, size_t, int32_t);
typedef int32_t (*snapshot_fn)(uint64_t *, size_t);
typedef void (*reset_peaks_fn)(void);
typedef void (*wg_bytes_fn)(uint64_t);
typedef void (*wg_drop_fn)(void);
typedef uint64_t (*wg_reset_pending_fn)(void);
typedef int32_t (*decode_fn)(const uint8_t *, size_t, int32_t *, size_t *);

static int failures = 0;

#define CHECK(condition, message) do { \
    if (!(condition)) { \
        fprintf(stderr, "FAIL: %s\n", message); \
        failures++; \
    } \
} while (0)

static void put_u32_le(uint8_t *out, uint32_t value) {
    out[0] = (uint8_t) value;
    out[1] = (uint8_t) (value >> 8);
    out[2] = (uint8_t) (value >> 16);
    out[3] = (uint8_t) (value >> 24);
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s /path/to/libwgbridge.so\n", argv[0]);
        return EXIT_FAILURE;
    }

    void *handle = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    CHECK(handle != NULL, "dlopen libwgbridge.so");
    if (handle == NULL) {
        fprintf(stderr, "dlopen: %s\n", dlerror());
        return EXIT_FAILURE;
    }

    configure_fn configure = (configure_fn) dlsym(handle, "tc_speed_prototype_configure");
    reserve_fn reserve = (reserve_fn) dlsym(handle, "tc_speed_prototype_reserve");
    snapshot_fn snapshot = (snapshot_fn) dlsym(handle, "tc_speed_prototype_snapshot");
    reset_peaks_fn reset_peaks = (reset_peaks_fn) dlsym(handle, "tc_speed_prototype_reset_queue_peaks");
    wg_bytes_fn wg_enqueue = (wg_bytes_fn) dlsym(handle, "tc_speed_prototype_wg_enqueue");
    wg_bytes_fn wg_dequeue = (wg_bytes_fn) dlsym(handle, "tc_speed_prototype_wg_dequeue");
    wg_drop_fn wg_drop = (wg_drop_fn) dlsym(handle, "tc_speed_prototype_wg_drop");
    wg_reset_pending_fn wg_reset_pending =
            (wg_reset_pending_fn) dlsym(handle, "tc_speed_prototype_wg_reset_pending");
    decode_fn decode = (decode_fn) dlsym(handle, "tc_speed_prototype_decode_envelope");
    CHECK(configure != NULL, "dlsym configure");
    CHECK(reserve != NULL, "dlsym reserve");
    CHECK(snapshot != NULL, "dlsym speed telemetry snapshot");
    CHECK(reset_peaks != NULL, "dlsym queue peak reset");
    CHECK(wg_enqueue != NULL, "dlsym WireGuard queue enqueue");
    CHECK(wg_dequeue != NULL, "dlsym WireGuard queue dequeue");
    CHECK(wg_drop != NULL, "dlsym WireGuard drop counter");
    CHECK(wg_reset_pending != NULL, "dlsym WireGuard pending reset");
    CHECK(decode != NULL, "dlsym envelope decoder");
    if (!configure || !reserve || !snapshot || !reset_peaks ||
        !wg_enqueue || !wg_dequeue || !wg_drop || !wg_reset_pending || !decode) {
        dlclose(handle);
        return EXIT_FAILURE;
    }

    CHECK(configure(1000, 2000, NULL, 0) == 0, "configure shared aggregate buckets");
    reset_peaks();
    uint64_t legacy_snapshot[11] = {0};
    uint64_t extended_snapshot[13] = {0};
    CHECK(snapshot(legacy_snapshot, 11) == 0,
          "speed snapshot preserves the original 11-value ABI prefix");
    CHECK(snapshot(extended_snapshot, 13) == 0,
          "speed snapshot exposes extended WireGuard queue peak counters");
    CHECK(extended_snapshot[11] == 0 && extended_snapshot[12] == 0,
          "new WireGuard queue peak counters start at zero");

    // C increments before publishing a datagram; Rust retires it only after
    // the packet has been paced and handed to gotatun.
    wg_enqueue(120);
    wg_enqueue(80);
    CHECK(snapshot(extended_snapshot, 13) == 0, "snapshot queued WireGuard packets");
    CHECK(extended_snapshot[4] == 2 && extended_snapshot[5] == 200,
          "WireGuard current queue counts include socketpair payloads");
    CHECK(extended_snapshot[11] == 2 && extended_snapshot[12] == 200,
          "WireGuard queue high-water marks include socketpair backlog");

    wg_dequeue(120);
    CHECK(snapshot(extended_snapshot, 13) == 0, "snapshot after WireGuard dequeue");
    CHECK(extended_snapshot[4] == 1 && extended_snapshot[5] == 80,
          "WireGuard dequeue retires exactly one packet and its bytes");
    CHECK(extended_snapshot[11] == 2 && extended_snapshot[12] == 200,
          "WireGuard high-water marks remain monotonic");

    // Failed native writes roll back their reservation and are counted as drops.
    wg_enqueue(40);
    wg_dequeue(40);
    wg_drop();
    CHECK(wg_reset_pending() == 1,
          "shutdown reports one datagram abandoned in the socketpair");
    CHECK(snapshot(extended_snapshot, 13) == 0, "snapshot after WireGuard shutdown reset");
    CHECK(extended_snapshot[4] == 0 && extended_snapshot[5] == 0,
          "WireGuard pending queue is empty after shutdown reset");
    CHECK(extended_snapshot[10] == 2,
          "explicit write failure and abandoned shutdown datagram are both counted as drops");
    CHECK(extended_snapshot[11] == 2 && extended_snapshot[12] == 200,
          "shutdown reset preserves WireGuard queue high-water marks");
    CHECK(reserve(0, 10001, 1000, 0) == 0, "Direct caller first upload reservation");
    CHECK(reserve(0, 10002, 1000, 0) == 0, "WireGuard caller shares upload bucket");
    int64_t shared_wait = reserve(0, 10001, 1000, 0);
    CHECK(shared_wait > 0, "cross-caller aggregate reservation is paced");
    CHECK(reserve(1, 10001, 3000, 0) > 0, "download bucket is independently paced");
    CHECK(reserve(0, -1, 1000, 0) > 0, "unknown UID is globally shaped, not exempt");
    CHECK(reserve(0, -1, 1000, 1) == 0, "explicit DNS exception bypasses shaping");

    int32_t exempt_uid = 10002;
    CHECK(configure(1000, 2000, &exempt_uid, 1) == 0, "configure app exception");
    CHECK(reserve(0, exempt_uid, 50000, 0) == 0, "exempt app bypasses shared limiter");

    int sockets[2] = {-1, -1};
    CHECK(socketpair(AF_UNIX, SOCK_DGRAM, 0, sockets) == 0, "create datagram socketpair");
    if (sockets[0] >= 0 && sockets[1] >= 0) {
        uint8_t packet[20] = {
            0x45, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x40, 0x01,
            0x00, 0x00, 10, 0, 0, 2, 203, 0, 113, 9
        };
        uint8_t envelope[HEADER_LEN + sizeof(packet)] = {0};
        memcpy(envelope, "TCS1", 4);
        envelope[4] = 1;
        put_u32_le(envelope + 8, 12345);
        put_u32_le(envelope + 12, sizeof(packet));
        memcpy(envelope + HEADER_LEN, packet, sizeof(packet));

        // Match the production ordering: count before publishing the datagram
        // so the Rust reader cannot race ahead of the enqueue update.
        wg_enqueue(sizeof(packet));
        ssize_t sent = send(sockets[0], envelope, sizeof(envelope), 0);
        CHECK(sent == (ssize_t) sizeof(envelope), "C sender writes one UID+packet datagram");
        if (sent != (ssize_t) sizeof(envelope))
            wg_dequeue(sizeof(packet));

        uint64_t queued_snapshot[13] = {0};
        CHECK(snapshot(queued_snapshot, 13) == 0, "snapshot socketpair datagram backlog");
        CHECK(queued_snapshot[4] == (sent == (ssize_t) sizeof(envelope) ? 1U : 0U),
              "socketpair write is reflected in WireGuard pending packet count");

        uint8_t received[HEADER_LEN + sizeof(packet)] = {0};
        ssize_t got = recv(sockets[1], received, sizeof(received), 0);
        CHECK(got == (ssize_t) sizeof(envelope), "socketpair preserves envelope datagram boundary");
        if (sent == (ssize_t) sizeof(envelope) && got != (ssize_t) sizeof(envelope))
            wg_dequeue(sizeof(packet));

        if (got == (ssize_t) sizeof(envelope)) {
            int32_t uid = -1;
            size_t packet_len = 0;
            CHECK(decode(received, (size_t) got, &uid, &packet_len) == 0,
                  "Rust decoder accepts C socketpair envelope");
            CHECK(uid == 12345, "Rust decoder recovers trusted C-side UID");
            CHECK(packet_len == sizeof(packet), "Rust decoder recovers raw IP packet length");

            // Unknown attribution is valid metadata: Rust must still globally
            // shape this packet rather than reject it or treat it as exempt.
            put_u32_le(received + 8, UINT32_MAX);
            CHECK(decode(received, (size_t) got, &uid, &packet_len) == 0,
                  "Rust decoder accepts unknown UID metadata");
            CHECK(uid == -1, "Rust decoder preserves unknown UID sentinel");
            put_u32_le(received + 8, 12345);
            CHECK(memcmp(received + HEADER_LEN, packet, sizeof(packet)) == 0,
                  "raw IP packet bytes remain unchanged");
            CHECK(decode(received, (size_t) got - 1, &uid, &packet_len) == -1,
                  "truncated envelope is rejected");
            wg_dequeue(sizeof(packet));
            CHECK(snapshot(queued_snapshot, 13) == 0, "snapshot after socketpair handoff");
            CHECK(queued_snapshot[4] == 0 && queued_snapshot[5] == 0,
                  "WireGuard pending count clears after the reader hands off the packet");
        }
        close(sockets[0]);
        close(sockets[1]);
    }

    dlclose(handle);
    if (failures != 0) {
        fprintf(stderr, "speed_shaping_ffi_test: %d failure(s)\n", failures);
        return EXIT_FAILURE;
    }
    puts("speed_shaping_ffi_test: all tests passed");
    return EXIT_SUCCESS;
}
