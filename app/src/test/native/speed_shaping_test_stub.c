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
