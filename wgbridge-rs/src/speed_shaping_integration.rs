//! Isolated cross-language integration harness for speed shaping.
//!
//! This module deliberately does not alter the live C packet path or the
//! gotatun transports. It specifies and tests the proposed C -> Rust datagram
//! envelope, reverse-flow UID lookup, and a bounded non-blocking pacing queue.

use std::collections::VecDeque;

use crate::speed_shaping_prototype::{FlowKey, FlowOwnerMap};

pub const ENVELOPE_MAGIC: [u8; 4] = *b"TCS1";
pub const ENVELOPE_VERSION: u8 = 1;
pub const ENVELOPE_HEADER_LEN: usize = 16;
pub const MAX_PACKET_LEN: usize = 65_535;

#[derive(Debug, Eq, PartialEq)]
pub struct DecodedEnvelope<'a> {
    pub uid: i32,
    pub packet: &'a [u8],
}

/// Wire format (all integer fields little-endian):
/// magic[4], version[1], flags[1], reserved[2], uid[4], packet_len[4], packet[].
pub fn decode_envelope(data: &[u8]) -> Option<DecodedEnvelope<'_>> {
    if data.len() < ENVELOPE_HEADER_LEN
        || data[0..4] != ENVELOPE_MAGIC
        || data[4] != ENVELOPE_VERSION
        || data[5] != 0
        || data[6] != 0
        || data[7] != 0
    {
        return None;
    }

    let uid = i32::from_le_bytes(data[8..12].try_into().ok()?);
    let packet_len = u32::from_le_bytes(data[12..16].try_into().ok()?) as usize;
    if uid < 0 || packet_len == 0 || packet_len > MAX_PACKET_LEN {
        return None;
    }
    let end = ENVELOPE_HEADER_LEN.checked_add(packet_len)?;
    if end != data.len() {
        return None;
    }

    Some(DecodedEnvelope {
        uid,
        packet: &data[ENVELOPE_HEADER_LEN..end],
    })
}

/// C ABI used only by the host integration harness. The pointer must reference
/// one complete datagram for the duration of this call; output pointers must
/// reference writable values.
#[no_mangle]
pub unsafe extern "C" fn tc_speed_prototype_decode_envelope(
    data: *const u8,
    data_len: usize,
    uid_out: *mut i32,
    packet_len_out: *mut usize,
) -> i32 {
    if data.is_null()
        || uid_out.is_null()
        || packet_len_out.is_null()
        || data_len < ENVELOPE_HEADER_LEN
        || data_len > ENVELOPE_HEADER_LEN + MAX_PACKET_LEN
    {
        return -1;
    }
    // SAFETY: the C caller contract requires data_len readable bytes.
    let bytes = unsafe { std::slice::from_raw_parts(data, data_len) };
    let Some(decoded) = decode_envelope(bytes) else {
        return -1;
    };
    // SAFETY: both output pointers were checked and are caller-owned writable values.
    unsafe {
        *uid_out = decoded.uid;
        *packet_len_out = decoded.packet.len();
    }
    0
}

/// Extracts a TCP/UDP 5-tuple from a complete, unfragmented IPv4 or IPv6
/// packet. IPv6 extension headers and fragmented packets are intentionally
/// rejected by this prototype; production attribution must handle them or
/// fail closed rather than invent an owner.
pub fn flow_key_from_ip_packet(packet: &[u8]) -> Option<FlowKey> {
    let version = packet.first()? >> 4;
    let (family, protocol, source, destination, l4_offset, packet_end) = match version {
        4 => {
            if packet.len() < 20 {
                return None;
            }
            let ihl = ((packet[0] & 0x0f) as usize).checked_mul(4)?;
            if ihl < 20 || packet.len() < ihl {
                return None;
            }
            let total_len = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            if total_len < ihl + 4 || total_len > packet.len() {
                return None;
            }
            let fragment = u16::from_be_bytes([packet[6], packet[7]]);
            if fragment & 0x3fff != 0 {
                return None;
            }
            let mut source = [0u8; 16];
            let mut destination = [0u8; 16];
            source[..4].copy_from_slice(&packet[12..16]);
            destination[..4].copy_from_slice(&packet[16..20]);
            (4, packet[9], source, destination, ihl, total_len)
        }
        6 => {
            if packet.len() < 40 {
                return None;
            }
            let payload_len = u16::from_be_bytes([packet[4], packet[5]]) as usize;
            let end = 40usize.checked_add(payload_len)?;
            if payload_len < 4 || end > packet.len() {
                return None;
            }
            let mut source = [0u8; 16];
            let mut destination = [0u8; 16];
            source.copy_from_slice(&packet[8..24]);
            destination.copy_from_slice(&packet[24..40]);
            (6, packet[6], source, destination, 40, end)
        }
        _ => return None,
    };

    if protocol != 6 && protocol != 17 {
        return None;
    }
    if l4_offset.checked_add(4)? > packet_end {
        return None;
    }
    let source_port = u16::from_be_bytes([packet[l4_offset], packet[l4_offset + 1]]);
    let destination_port =
        u16::from_be_bytes([packet[l4_offset + 2], packet[l4_offset + 3]]);
    FlowKey::new(
        family,
        protocol,
        source,
        destination,
        source_port,
        destination_port,
    )
}

/// A bounded, non-blocking holding queue. The event-loop caller supplies the
/// reservation deadline and returns immediately; a separate scheduler may
/// drain ready entries. This primitive never sleeps or waits for capacity.
pub struct BoundedPendingQueue {
    entries: VecDeque<(u64, Vec<u8>)>,
    queued_bytes: usize,
    max_packets: usize,
    max_bytes: usize,
}

impl BoundedPendingQueue {
    pub fn new(max_packets: usize, max_bytes: usize) -> Self {
        Self {
            entries: VecDeque::new(),
            queued_bytes: 0,
            max_packets,
            max_bytes,
        }
    }

    pub fn try_push(&mut self, ready_at_ns: u64, packet: Vec<u8>) -> Result<(), Vec<u8>> {
        let Some(next_bytes) = self.queued_bytes.checked_add(packet.len()) else {
            return Err(packet);
        };
        if self.entries.len() >= self.max_packets || next_bytes > self.max_bytes {
            return Err(packet);
        }
        self.queued_bytes = next_bytes;
        let position = self
            .entries
            .iter()
            .position(|(deadline, _)| *deadline > ready_at_ns)
            .unwrap_or(self.entries.len());
        self.entries.insert(position, (ready_at_ns, packet));
        Ok(())
    }

    pub fn pop_ready(&mut self, now_ns: u64) -> Option<Vec<u8>> {
        let (ready_at, _) = self.entries.front()?;
        if *ready_at > now_ns {
            return None;
        }
        let (_, packet) = self.entries.pop_front()?;
        self.queued_bytes = self.queued_bytes.saturating_sub(packet.len());
        Some(packet)
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn queued_bytes(&self) -> usize {
        self.queued_bytes
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::speed_shaping_prototype::FlowOwnerMap;

    fn ipv4_udp(src: [u8; 4], dst: [u8; 4], sport: u16, dport: u16) -> Vec<u8> {
        let mut packet = vec![0u8; 28];
        packet[0] = 0x45;
        packet[2..4].copy_from_slice(&(28u16).to_be_bytes());
        packet[8] = 64;
        packet[9] = 17;
        packet[12..16].copy_from_slice(&src);
        packet[16..20].copy_from_slice(&dst);
        packet[20..22].copy_from_slice(&sport.to_be_bytes());
        packet[22..24].copy_from_slice(&dport.to_be_bytes());
        packet[24..26].copy_from_slice(&(8u16).to_be_bytes());
        packet
    }

    fn ipv6_tcp(src_last: u8, dst_last: u8, sport: u16, dport: u16) -> Vec<u8> {
        let mut packet = vec![0u8; 60];
        packet[0] = 0x60;
        packet[4..6].copy_from_slice(&(20u16).to_be_bytes());
        packet[6] = 6;
        packet[7] = 64;
        packet[23] = src_last;
        packet[39] = dst_last;
        packet[40..42].copy_from_slice(&sport.to_be_bytes());
        packet[42..44].copy_from_slice(&dport.to_be_bytes());
        packet[52] = 0x50;
        packet
    }

    #[test]
    fn envelope_round_trip_and_rejects_malformed_or_unknown_uid() {
        let packet = ipv4_udp([10, 0, 0, 2], [203, 0, 113, 9], 50000, 53);
        let mut envelope = Vec::new();
        envelope.extend_from_slice(&ENVELOPE_MAGIC);
        envelope.extend_from_slice(&[ENVELOPE_VERSION, 0, 0, 0]);
        envelope.extend_from_slice(&12345i32.to_le_bytes());
        envelope.extend_from_slice(&(packet.len() as u32).to_le_bytes());
        envelope.extend_from_slice(&packet);
        let decoded = decode_envelope(&envelope).unwrap();
        assert_eq!(decoded.uid, 12345);
        assert_eq!(decoded.packet, packet);

        let mut unknown = envelope.clone();
        unknown[8..12].copy_from_slice(&(-1i32).to_le_bytes());
        assert!(decode_envelope(&unknown).is_none());
        assert!(decode_envelope(&envelope[..envelope.len() - 1]).is_none());
    }

    #[test]
    fn inbound_ipv4_udp_packet_recovers_the_outbound_app_uid() {
        let outbound = ipv4_udp([10, 0, 0, 2], [203, 0, 113, 9], 50000, 443);
        let inbound = ipv4_udp([203, 0, 113, 9], [10, 0, 0, 2], 443, 50000);
        let outbound_key = flow_key_from_ip_packet(&outbound).unwrap();
        let inbound_key = flow_key_from_ip_packet(&inbound).unwrap();
        let mut owners = FlowOwnerMap::new(32, 30_000_000_000);
        assert!(owners.remember_outbound(outbound_key, 12345, 10));
        assert_eq!(owners.lookup_inbound(inbound_key, 20), Some(12345));
    }

    #[test]
    fn inbound_ipv6_tcp_packet_recovers_the_outbound_app_uid() {
        let outbound = ipv6_tcp(2, 9, 51000, 443);
        let inbound = ipv6_tcp(9, 2, 443, 51000);
        let outbound_key = flow_key_from_ip_packet(&outbound).unwrap();
        let inbound_key = flow_key_from_ip_packet(&inbound).unwrap();
        let mut owners = FlowOwnerMap::new(32, 30_000_000_000);
        assert!(owners.remember_outbound(outbound_key, 23456, 10));
        assert_eq!(owners.lookup_inbound(inbound_key, 20), Some(23456));
    }

    #[test]
    fn fragmented_and_ipv6_extension_packets_fail_closed() {
        let mut fragmented = ipv4_udp([10, 0, 0, 2], [203, 0, 113, 9], 50000, 53);
        fragmented[6] = 0x20; // MF: fragmented datagram
        assert!(flow_key_from_ip_packet(&fragmented).is_none());

        let mut extension = ipv6_tcp(2, 9, 51000, 443);
        extension[6] = 0; // Hop-by-hop extension; not parsed by this prototype.
        assert!(flow_key_from_ip_packet(&extension).is_none());
    }

    #[test]
    fn pending_queue_is_bounded_and_never_releases_before_deadline() {
        let mut queue = BoundedPendingQueue::new(2, 5);
        assert!(queue.try_push(100, vec![1, 2, 3]).is_ok());
        assert!(queue.try_push(100, vec![4, 5]).is_ok());
        assert_eq!(queue.try_push(100, vec![6]), Err(vec![6]));
        assert_eq!(queue.pop_ready(99), None);
        assert_eq!(queue.pop_ready(100), Some(vec![1, 2, 3]));
        assert_eq!(queue.queued_bytes(), 2);
        assert_eq!(queue.pop_ready(100), Some(vec![4, 5]));
        assert_eq!(queue.len(), 0);
        assert_eq!(queue.queued_bytes(), 0);
    }

    #[test]
    fn pending_queue_orders_deadlines_without_releasing_early() {
        let mut queue = BoundedPendingQueue::new(4, 16);
        assert!(queue.try_push(300, vec![3]).is_ok());
        assert!(queue.try_push(100, vec![1]).is_ok());
        assert!(queue.try_push(200, vec![2]).is_ok());
        assert_eq!(queue.pop_ready(199), Some(vec![1]));
        assert_eq!(queue.pop_ready(199), None);
        assert_eq!(queue.pop_ready(200), Some(vec![2]));
        assert_eq!(queue.pop_ready(299), None);
        assert_eq!(queue.pop_ready(300), Some(vec![3]));
    }

    #[test]
    fn concurrent_direct_and_wireguard_reservations_share_one_bucket() {
        use crate::speed_shaping_prototype::{AggregateLimiter, Direction};
        use std::sync::{Arc, Mutex};
        use std::thread;

        let limiter = Arc::new(Mutex::new(AggregateLimiter::new(1_000, 1_000)));
        let workers: Vec<_> = (0..64)
            .map(|index| {
                let limiter = Arc::clone(&limiter);
                thread::spawn(move || {
                    let uid = if index % 2 == 0 { 10_001 } else { 10_002 };
                    limiter
                        .lock()
                        .unwrap()
                        .reserve(Direction::Upload, uid, 100, false, 0)
                })
            })
            .collect();

        let mut immediate = 0;
        let mut paced = 0;
        for worker in workers {
            match worker.join().expect("reservation worker must complete") {
                crate::speed_shaping_prototype::ReserveDecision::WaitNanos(0) => immediate += 1,
                crate::speed_shaping_prototype::ReserveDecision::WaitNanos(wait) => {
                    assert!(wait > 0);
                    paced += 1;
                }
                other => panic!("known owner should be shaped, got {other:?}"),
            }
        }

        // The shared 2,920-byte burst admits 29 x 100-byte reservations.
        // Splitting callers across two simulated datapaths must not double it.
        assert_eq!(immediate, 29);
        assert_eq!(paced, 35);
    }

    #[test]
    fn malformed_ip_lengths_and_fragment_headers_fail_closed() {
        let mut bad_ihl = ipv4_udp([10, 0, 0, 2], [203, 0, 113, 9], 50000, 443);
        bad_ihl[0] = 0x44;
        assert!(flow_key_from_ip_packet(&bad_ihl).is_none());

        let mut bad_total_len = ipv4_udp([10, 0, 0, 2], [203, 0, 113, 9], 50000, 443);
        bad_total_len[2..4].copy_from_slice(&(19u16).to_be_bytes());
        assert!(flow_key_from_ip_packet(&bad_total_len).is_none());

        let mut non_initial_fragment =
            ipv4_udp([10, 0, 0, 2], [203, 0, 113, 9], 50000, 443);
        non_initial_fragment[7] = 1;
        assert!(flow_key_from_ip_packet(&non_initial_fragment).is_none());

        let mut ipv6_fragment = ipv6_tcp(2, 9, 51000, 443);
        ipv6_fragment[6] = 44; // Fragment extension header.
        assert!(flow_key_from_ip_packet(&ipv6_fragment).is_none());

        let mut truncated_transport = ipv6_tcp(2, 9, 51000, 443);
        truncated_transport[4..6].copy_from_slice(&(2u16).to_be_bytes());
        assert!(flow_key_from_ip_packet(&truncated_transport).is_none());
    }

    #[test]
    fn envelope_rejects_unknown_flags_reserved_bytes_and_trailing_data() {
        let packet = ipv4_udp([10, 0, 0, 2], [203, 0, 113, 9], 50000, 443);
        let mut envelope = Vec::new();
        envelope.extend_from_slice(&ENVELOPE_MAGIC);
        envelope.extend_from_slice(&[ENVELOPE_VERSION, 0, 0, 0]);
        envelope.extend_from_slice(&12345i32.to_le_bytes());
        envelope.extend_from_slice(&(packet.len() as u32).to_le_bytes());
        envelope.extend_from_slice(&packet);

        let mut bad_flags = envelope.clone();
        bad_flags[5] = 1;
        assert!(decode_envelope(&bad_flags).is_none());

        let mut bad_reserved = envelope.clone();
        bad_reserved[7] = 1;
        assert!(decode_envelope(&bad_reserved).is_none());

        let mut trailing = envelope.clone();
        trailing.push(0);
        assert!(decode_envelope(&trailing).is_none());

        let mut zero_length = envelope;
        zero_length[12..16].copy_from_slice(&0u32.to_le_bytes());
        assert!(decode_envelope(&zero_length).is_none());
    }
}
