//! Shared aggregate limiter for NetGuard's Direct and WireGuard datapaths.
//!
//! The Rust WireGuard transport now uses the upload/download reservations and
//! reverse-flow owner map. The native C Direct path is the remaining integration
//! target and can resolve the exported ABI through the existing dlopen pattern.

use std::collections::{HashMap, HashSet};
use std::sync::{Mutex, OnceLock, PoisonError};
use std::time::Instant;

const NANOS_PER_SECOND: u128 = 1_000_000_000;
const MIN_BURST_BYTES: u64 = 2_920;
const MAX_EXEMPT_UIDS: usize = 8_192;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Direction {
    Upload = 0,
    Download = 1,
}

impl Direction {
    fn from_abi(value: i32) -> Option<Self> {
        match value {
            0 => Some(Self::Upload),
            1 => Some(Self::Download),
            _ => None,
        }
    }

    fn index(self) -> usize {
        self as usize
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ReserveDecision {
    Bypass,
    WaitNanos(u64),
}

#[derive(Clone, Debug)]
struct Bucket {
    rate_bytes_per_second: u64,
    theoretical_arrival_ns: u128,
}

impl Bucket {
    fn new(rate_bytes_per_second: u64) -> Self {
        Self {
            rate_bytes_per_second,
            theoretical_arrival_ns: 0,
        }
    }

    fn set_rate(&mut self, rate_bytes_per_second: u64) {
        self.rate_bytes_per_second = rate_bytes_per_second;
        if rate_bytes_per_second == 0 {
            self.theoretical_arrival_ns = 0;
        }
    }

    // GCRA-style reservation. It returns a start delay rather than sleeping,
    // so neither the C epoll thread nor a Rust Tokio worker has to block.
    fn reserve(&mut self, now_ns: u64, bytes: u64) -> u64 {
        let rate = self.rate_bytes_per_second;
        if rate == 0 || bytes == 0 {
            return 0;
        }

        let rate = rate as u128;
        let interval_ns = ((bytes as u128) * NANOS_PER_SECOND + rate - 1) / rate;
        let burst_bytes = (self.rate_bytes_per_second / 4).max(MIN_BURST_BYTES);
        let burst_ns =
            ((burst_bytes as u128) * NANOS_PER_SECOND + rate - 1) / rate;
        let now = now_ns as u128;

        // A packet larger than the nominal burst is scheduled after enough
        // refill time to avoid granting an unbounded first-packet burst.
        // Tokens available now are equivalent to the burst allowance minus
        // the already-reserved time debt. Include this packet's own size when
        // calculating the wait, so a packet cannot overdraw the remaining
        // burst just because it is the first packet to cross the threshold.
        let debt_ns = self.theoretical_arrival_ns.saturating_sub(now);
        let wait_ns = debt_ns
            .saturating_add(interval_ns)
            .saturating_sub(burst_ns);

        self.theoretical_arrival_ns = self
            .theoretical_arrival_ns
            .max(now)
            .saturating_add(interval_ns);

        wait_ns.min(u64::MAX as u128) as u64
    }
}

#[derive(Clone, Debug)]
pub struct AggregateLimiter {
    buckets: [Bucket; 2],
    exempt_uids: HashSet<i32>,
}

impl AggregateLimiter {
    pub fn new(upload_bytes_per_second: u64, download_bytes_per_second: u64) -> Self {
        Self {
            buckets: [
                Bucket::new(upload_bytes_per_second),
                Bucket::new(download_bytes_per_second),
            ],
            exempt_uids: HashSet::new(),
        }
    }

    pub fn configure(
        &mut self,
        upload_bytes_per_second: u64,
        download_bytes_per_second: u64,
        exempt_uids: &[i32],
    ) {
        self.buckets[Direction::Upload.index()].set_rate(upload_bytes_per_second);
        self.buckets[Direction::Download.index()].set_rate(download_bytes_per_second);
        self.exempt_uids.clear();
        self.exempt_uids.extend(exempt_uids.iter().copied().filter(|uid| *uid >= 0));
    }

    pub fn reserve(
        &mut self,
        direction: Direction,
        uid: i32,
        bytes: u64,
        dns_port_53: bool,
        now_ns: u64,
    ) -> ReserveDecision {
        // Port 53 and explicitly configured, known app UIDs are exceptions.
        // An unknown UID is not an exception: preserve existing traffic and
        // charge it to the aggregate bucket rather than dropping or bypassing it.
        if dns_port_53 || (uid >= 0 && self.exempt_uids.contains(&uid)) {
            return ReserveDecision::Bypass;
        }
        ReserveDecision::WaitNanos(
            self.buckets[direction.index()].reserve(now_ns, bytes),
        )
    }
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub struct FlowKey {
    family: u8,
    protocol: u8,
    source: [u8; 16],
    destination: [u8; 16],
    source_port: u16,
    destination_port: u16,
}

impl FlowKey {
    pub fn new(
        family: u8,
        protocol: u8,
        source: [u8; 16],
        destination: [u8; 16],
        source_port: u16,
        destination_port: u16,
    ) -> Option<Self> {
        if !matches!(family, 4 | 6) || !matches!(protocol, 6 | 17) {
            return None;
        }
        Some(Self {
            family,
            protocol,
            source,
            destination,
            source_port,
            destination_port,
        })
    }

    pub fn reverse(self) -> Self {
        Self {
            family: self.family,
            protocol: self.protocol,
            source: self.destination,
            destination: self.source,
            source_port: self.destination_port,
            destination_port: self.source_port,
        }
    }

    /// True when either endpoint is the conventional DNS port.
    pub fn is_dns_port_53(self) -> bool {
        self.source_port == 53 || self.destination_port == 53
    }
}

#[derive(Clone, Copy, Debug)]
struct FlowOwner {
    uid: i32,
    last_seen_ns: u64,
}

/// Bounded reverse-flow owner table for WireGuard decrypted packets.
/// Outbound metadata must be recorded before the packet is handed to gotatun.
#[derive(Debug)]
pub struct FlowOwnerMap {
    entries: HashMap<FlowKey, FlowOwner>,
    capacity: usize,
    ttl_ns: u64,
}

impl FlowOwnerMap {
    pub fn new(capacity: usize, ttl_ns: u64) -> Self {
        Self {
            entries: HashMap::new(),
            capacity,
            ttl_ns,
        }
    }

    fn expire(&mut self, now_ns: u64) {
        self.entries.retain(|_, entry| {
            now_ns.saturating_sub(entry.last_seen_ns) <= self.ttl_ns
        });
    }

    pub fn remember_outbound(&mut self, key: FlowKey, uid: i32, now_ns: u64) -> bool {
        if uid < 0 || self.capacity == 0 {
            return false;
        }
        self.expire(now_ns);
        if !self.entries.contains_key(&key) && self.entries.len() >= self.capacity {
            if let Some(oldest) = self
                .entries
                .iter()
                .min_by_key(|(_, entry)| entry.last_seen_ns)
                .map(|(key, _)| *key)
            {
                self.entries.remove(&oldest);
            }
        }
        // A new TCP SYN or reused UDP tuple refreshes ownership for the tuple.
        self.entries.insert(
            key,
            FlowOwner {
                uid,
                last_seen_ns: now_ns,
            },
        );
        true
    }

    pub fn lookup_inbound(&mut self, inbound_key: FlowKey, now_ns: u64) -> Option<i32> {
        self.expire(now_ns);
        let outbound_key = inbound_key.reverse();
        let entry = self.entries.get_mut(&outbound_key)?;
        entry.last_seen_ns = now_ns;
        Some(entry.uid)
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }
}

fn global_limiter() -> &'static Mutex<AggregateLimiter> {
    static LIMITER: OnceLock<Mutex<AggregateLimiter>> = OnceLock::new();
    LIMITER.get_or_init(|| Mutex::new(AggregateLimiter::new(0, 0)))
}

pub fn monotonic_now_ns() -> u64 {
    static ORIGIN: OnceLock<Instant> = OnceLock::new();
    let origin = ORIGIN.get_or_init(Instant::now);
    origin.elapsed().as_nanos().min(u64::MAX as u128) as u64
}

/// Configures the prototype's one process-wide pair of aggregate buckets.
///
/// # Safety
/// When exempt_count is nonzero, exempt_uids must point to that many valid i32s.
/// This symbol is exported for C/Rust boundary feasibility tests only; no
/// production caller is connected to it.
#[no_mangle]
pub unsafe extern "C" fn tc_speed_prototype_configure(
    upload_bytes_per_second: u64,
    download_bytes_per_second: u64,
    exempt_uids: *const i32,
    exempt_count: usize,
) -> i32 {
    if exempt_count > MAX_EXEMPT_UIDS || (exempt_count > 0 && exempt_uids.is_null()) {
        return -1;
    }
    let exempt = if exempt_count == 0 {
        &[][..]
    } else {
        // SAFETY: guaranteed by this function's caller contract above.
        unsafe { std::slice::from_raw_parts(exempt_uids, exempt_count) }
    };
    let mut limiter = global_limiter()
        .lock()
        .unwrap_or_else(PoisonError::into_inner);
    limiter.configure(
        upload_bytes_per_second,
        download_bytes_per_second,
        exempt,
    );
    0
}

/// Reserves bytes against the shared aggregate bucket.
///
/// direction: 0 = upload, 1 = download.
/// Returns wait nanoseconds, 0 for explicit bypass/unlimited, or -1 for an
/// invalid direction. Unknown UIDs are still charged to the global bucket.
#[no_mangle]
pub extern "C" fn tc_speed_prototype_reserve(
    direction: i32,
    uid: i32,
    bytes: usize,
    dns_port_53: i32,
) -> i64 {
    let Some(direction) = Direction::from_abi(direction) else {
        return -1;
    };
    let mut limiter = global_limiter()
        .lock()
        .unwrap_or_else(PoisonError::into_inner);
    match limiter.reserve(
        direction,
        uid,
        bytes.min(u64::MAX as usize) as u64,
        dns_port_53 != 0,
        monotonic_now_ns(),
    ) {
        ReserveDecision::Bypass => 0,
        ReserveDecision::WaitNanos(wait) => wait.min(i64::MAX as u64) as i64,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key4(protocol: u8, sport: u16, dport: u16) -> FlowKey {
        let mut source = [0; 16];
        let mut destination = [0; 16];
        source[..4].copy_from_slice(&[10, 0, 0, 2]);
        destination[..4].copy_from_slice(&[203, 0, 113, 9]);
        FlowKey::new(4, protocol, source, destination, sport, dport).unwrap()
    }

    fn key6(protocol: u8, sport: u16, dport: u16) -> FlowKey {
        let mut source = [0; 16];
        let mut destination = [0; 16];
        source[15] = 2;
        destination[15] = 9;
        FlowKey::new(6, protocol, source, destination, sport, dport).unwrap()
    }

    fn wait(decision: ReserveDecision) -> u64 {
        match decision {
            ReserveDecision::WaitNanos(value) => value,
            other => panic!("expected a shaping reservation, got {other:?}"),
        }
    }

    #[test]
    fn direct_and_wireguard_callers_share_one_upload_bucket() {
        let mut limiter = AggregateLimiter::new(1_000, 1_000);
        assert_eq!(wait(limiter.reserve(Direction::Upload, 10001, 1_000, false, 0)), 0);
        assert_eq!(wait(limiter.reserve(Direction::Upload, 10002, 1_000, false, 0)), 0);
        // The third 1 KB reservation exceeds the shared 2.92 KB burst,
        // regardless of which datapath made the first two reservations.
        assert_eq!(
            wait(limiter.reserve(Direction::Upload, 10001, 1_000, false, 0)),
            80_000_000
        );
    }

    #[test]
    fn upload_and_download_have_independent_aggregate_buckets() {
        let mut limiter = AggregateLimiter::new(1_000, 1_000);
        assert_eq!(wait(limiter.reserve(Direction::Upload, 10, 3_000, false, 0)), 80_000_000);
        assert_eq!(wait(limiter.reserve(Direction::Download, 10, 3_000, false, 0)), 80_000_000);
    }

    #[test]
    fn app_exemption_and_dns_bypass_do_not_consume_tokens() {
        let mut limiter = AggregateLimiter::new(1_000, 1_000);
        limiter.configure(1_000, 1_000, &[42]);
        assert_eq!(limiter.reserve(Direction::Upload, 42, 50_000, false, 0), ReserveDecision::Bypass);
        assert_eq!(limiter.reserve(Direction::Upload, -1, 50_000, true, 0), ReserveDecision::Bypass);
        assert_eq!(wait(limiter.reserve(Direction::Upload, 43, 1_000, false, 0)), 0);
    }

    #[test]
    fn unknown_owner_is_globally_shaped_without_being_dropped() {
        let mut limiter = AggregateLimiter::new(1_000, 1_000);
        assert_eq!(
            wait(limiter.reserve(Direction::Download, -1, 1_500, false, 0)),
            0
        );
        assert!(
            wait(limiter.reserve(Direction::Download, -1, 1_500, false, 0)) > 0,
            "unknown UID must consume the same aggregate download bucket"
        );
    }

    #[test]
    fn unlimited_rate_returns_without_waiting() {
        let mut limiter = AggregateLimiter::new(0, 0);
        assert_eq!(wait(limiter.reserve(Direction::Upload, 10, 1_000_000, false, 0)), 0);
    }

    #[test]
    fn reverse_flow_lookup_attributes_ipv4_tcp_and_udp() {
        for protocol in [6, 17] {
            let outbound = key4(protocol, 51_234, 443);
            let mut owners = FlowOwnerMap::new(16, 300_000_000_000);
            assert!(owners.remember_outbound(outbound, 12345, 10));
            assert_eq!(owners.lookup_inbound(outbound.reverse(), 20), Some(12345));
        }
    }

    #[test]
    fn reverse_flow_lookup_attributes_ipv6() {
        let outbound = key6(17, 53_210, 443);
        let mut owners = FlowOwnerMap::new(16, 300_000_000_000);
        assert!(owners.remember_outbound(outbound, 23456, 10));
        assert_eq!(owners.lookup_inbound(outbound.reverse(), 20), Some(23456));
    }

    #[test]
    fn tuple_reuse_replaces_owner_and_expired_flows_are_unknown() {
        let outbound = key4(6, 40_000, 443);
        let mut owners = FlowOwnerMap::new(16, 100);
        assert!(owners.remember_outbound(outbound, 111, 10));
        assert!(owners.remember_outbound(outbound, 222, 20));
        assert_eq!(owners.lookup_inbound(outbound.reverse(), 30), Some(222));
        assert_eq!(owners.lookup_inbound(outbound.reverse(), 131), None);
    }

    #[test]
    fn owner_table_is_bounded_and_evicts_oldest() {
        let first = key4(6, 40_000, 443);
        let second = key4(17, 40_001, 443);
        let mut owners = FlowOwnerMap::new(1, 300_000_000_000);
        assert!(owners.remember_outbound(first, 111, 10));
        assert!(owners.remember_outbound(second, 222, 20));
        assert_eq!(owners.len(), 1);
        assert_eq!(owners.lookup_inbound(first.reverse(), 30), None);
        assert_eq!(owners.lookup_inbound(second.reverse(), 30), Some(222));
    }

    #[test]
    fn rejects_unsupported_flow_protocols() {
        let source = [0; 16];
        let destination = [1; 16];
        assert!(FlowKey::new(4, 1, source, destination, 0, 0).is_none());
        assert!(FlowKey::new(5, 6, source, destination, 1, 2).is_none());
    }

    #[test]
    fn exported_abi_uses_the_same_shared_upload_bucket() {
        assert_eq!(
            unsafe { tc_speed_prototype_configure(1_000, 1_000, std::ptr::null(), 0) },
            0
        );
        assert_eq!(tc_speed_prototype_reserve(0, 10001, 1_000, 0), 0);
        assert_eq!(tc_speed_prototype_reserve(0, 10002, 1_000, 0), 0);
        let shared_wait = tc_speed_prototype_reserve(0, 10001, 1_000, 0);
        assert!(
            shared_wait > 0 && shared_wait <= 80_000_000,
            "expected a positive shared-bucket wait no greater than 80 ms, got {shared_wait}"
        );
        // Unknown owners are charged to the global bucket; only the
        // explicit DNS exception bypasses shaping.
        let unknown_wait = tc_speed_prototype_reserve(0, -1, 1_000, 0);
        assert!(unknown_wait > 0, "unknown UID must remain globally shaped");
        assert_eq!(tc_speed_prototype_reserve(0, -1, 1_000, 1), 0);
    }
}
