#include "netguard.h"

/* Host defensive tests exercise the existing packet writers without starting
 * the event-loop timer or loading the Rust limiter. Preserve their former
 * immediate TUN-write behavior; production builds use policy.c instead. */
ssize_t speed_write_tun_packet(const struct arguments *args,
                               const uint8_t *packet, size_t length,
                               jint uid, int dns_port_53) {
    (void) uid;
    (void) dns_port_53;
    return write(args->tun, packet, length);
}

/* ip_header_test links ip.c without the production policy bridge. */
void speed_limit_record(int direction, size_t bytes) {
    (void) direction;
    (void) bytes;
}

/* Host packet tests do not load the Rust bridge; queue telemetry is inert. */
void speed_limit_wg_enqueue(size_t bytes) { (void) bytes; }
void speed_limit_wg_dequeue(size_t bytes) { (void) bytes; }
void speed_limit_wg_drop(void) { }
uint64_t speed_limit_wg_reset_pending(void) { return 0; }
