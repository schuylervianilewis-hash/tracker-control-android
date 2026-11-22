#include "speed_queue_telemetry.h"
#include <assert.h>
int main(void) {
    struct speed_queue_telemetry telemetry;
    speed_queue_telemetry_init(&telemetry);
    speed_queue_telemetry_publish(&telemetry, 5, 1200);
    assert(atomic_load(&telemetry.packets) == 5);
    assert(atomic_load(&telemetry.bytes) == 1200);
    assert(atomic_load(&telemetry.peak_packets) == 5);
    assert(atomic_load(&telemetry.peak_bytes) == 1200);
    speed_queue_telemetry_publish(&telemetry, 2, 400);
    assert(atomic_load(&telemetry.packets) == 2);
    assert(atomic_load(&telemetry.bytes) == 400);
    assert(atomic_load(&telemetry.peak_packets) == 5);
    assert(atomic_load(&telemetry.peak_bytes) == 1200);
    speed_queue_telemetry_publish(&telemetry, 8, 1800);
    assert(atomic_load(&telemetry.peak_packets) == 8);
    assert(atomic_load(&telemetry.peak_bytes) == 1800);
    speed_queue_telemetry_record_drop(&telemetry, 3);
    speed_queue_telemetry_record_scheduler_error(&telemetry);
    assert(atomic_load(&telemetry.dropped_packets) == 3);
    assert(atomic_load(&telemetry.scheduler_errors) == 1);
    return 0;
}
