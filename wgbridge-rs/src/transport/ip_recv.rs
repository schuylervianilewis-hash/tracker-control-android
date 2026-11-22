//! IpRecv over the socketpair read-end. The C side writes one TCS1 UID+IP
//! envelope per datagram; SOCK_DGRAM preserves those message boundaries.

use std::io;
use std::os::fd::{AsRawFd, OwnedFd};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use gotatun::packet::{Ip, Packet, PacketBufPool};
use gotatun::tun::{IpRecv, MtuWatcher};
use tokio::io::unix::AsyncFd;
use tokio::io::Interest;

use crate::speed_shaping_integration::{
    decode_envelope, flow_key_from_ip_packet, ENVELOPE_HEADER_LEN, MAX_PACKET_LEN,
};
use crate::speed_shaping_prototype::{
    monotonic_now_ns, record_transmitted, record_delayed, record_scheduler_error,
    record_dropped_packet, record_wg_pending, tc_speed_prototype_reserve, FlowOwnerMap, PendingGuard,
};

/// Upper bound on packets drained per wakeup so one busy burst can't starve
/// the executor.
const MAX_BATCH: usize = 32;
const FLOW_OWNER_CAPACITY: usize = 8_192;
const FLOW_OWNER_TTL_NS: u64 = 300_000_000_000;

pub struct SocketpairRecv {
    afd: AsyncFd<OwnedFd>,
    mtu: MtuWatcher,
    owners: Arc<Mutex<FlowOwnerMap>>,
    envelope: Vec<u8>,
}

impl SocketpairRecv {
    /// Takes ownership of `fd` (already a private dup). Sets it non-blocking
    /// for use with the tokio reactor. The owner map is shared with TunFdSend
    /// so decrypted responses inherit the app UID of their outbound flow.
    pub fn new(
        fd: OwnedFd,
        mtu: u16,
        owners: Arc<Mutex<FlowOwnerMap>>,
    ) -> io::Result<Self> {
        set_nonblocking(&fd)?;
        // SAFETY: `fd` was set to non-blocking mode immediately above.
        let afd = unsafe { AsyncFd::register_with_interest(fd, Interest::READABLE)? };
        Ok(Self {
            afd,
            mtu: MtuWatcher::new(mtu),
            owners,
            envelope: vec![0; ENVELOPE_HEADER_LEN + MAX_PACKET_LEN],
        })
    }

    /// Convenience constructor for isolated transport tests.
    #[cfg(test)]
    pub fn with_fresh_owners(fd: OwnedFd, mtu: u16) -> io::Result<Self> {
        Self::new(
            fd,
            mtu,
            Arc::new(Mutex::new(FlowOwnerMap::new(
                FLOW_OWNER_CAPACITY,
                FLOW_OWNER_TTL_NS,
            ))),
        )
    }
}

fn set_nonblocking(fd: &OwnedFd) -> io::Result<()> {
    // SAFETY: fd is a valid owned descriptor for the duration of the calls.
    unsafe {
        let flags = libc::fcntl(fd.as_raw_fd(), libc::F_GETFL);
        if flags < 0 {
            return Err(io::Error::last_os_error());
        }
        if libc::fcntl(fd.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK) < 0 {
            return Err(io::Error::last_os_error());
        }
    }
    Ok(())
}

fn read_fd(fd: i32, buf: &mut [u8]) -> isize {
    // SAFETY: buf is valid for writes of buf.len() bytes.
    unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) }
}

impl IpRecv for SocketpairRecv {
    async fn recv<'a>(
        &'a mut self,
        pool: &mut PacketBufPool,
    ) -> io::Result<impl Iterator<Item = Packet<Ip>> + Send + 'a> {
        loop {
            let mut guard = self.afd.readable().await?;
            let fd = self.afd.get_ref().as_raw_fd();

            let mut packets: Vec<Packet<Ip>> = Vec::new();
            loop {
                if packets.len() >= MAX_BATCH {
                    break;
                }
                let n = read_fd(fd, &mut self.envelope);
                if n < 0 {
                    let e = io::Error::last_os_error();
                    if e.kind() == io::ErrorKind::WouldBlock {
                        guard.clear_ready();
                        break;
                    }
                    // Retryable; must not bubble up — gotatun treats an IpRecv
                    // error as fatal and permanently stops the outbound pump.
                    if e.kind() == io::ErrorKind::Interrupted {
                        continue;
                    }
                    if packets.is_empty() {
                        return Err(e);
                    }
                    break;
                }
                if n == 0 {
                    // Socketpair write-end closed: the C side shut down.
                    if packets.is_empty() {
                        return Err(io::Error::new(
                            io::ErrorKind::UnexpectedEof,
                            "wg socketpair closed",
                        ));
                    }
                    break;
                }

                let Some(decoded) = decode_envelope(&self.envelope[..n as usize]) else {
                    // The native writer counted this datagram before publishing it.
                    // Retire the count even when its envelope is malformed.
                    record_wg_pending(false, (n as usize).saturating_sub(ENVELOPE_HEADER_LEN));
                    record_dropped_packet();
                    // Invalid framing must never be interpreted as raw IP.
                    continue;
                };
                let uid = decoded.uid;
                let packet_bytes = decoded.packet;
                let packet_len = packet_bytes.len();
                // This packet was counted by the native socketpair writer. Keep
                // it pending through pacing and validation until it is handed
                // to gotatun (or discarded on one of the error paths below).
                let _pending = PendingGuard::from_enqueued(packet_len);
                let flow = flow_key_from_ip_packet(packet_bytes);
                let dns_port_53 = flow.map(|key| key.is_dns_port_53()).unwrap_or(false);

                if let Some(key) = flow {
                    let now = monotonic_now_ns();
                    self.owners
                        .lock()
                        .unwrap_or_else(|poisoned| poisoned.into_inner())
                        .remember_outbound(key, uid, now);
                }

                // Reuse gotatun's packet pool after removing the C/Rust envelope.
                let mut packet = pool.get();
                if packet_len > packet.buf_mut().len() {
                    record_dropped_packet();
                    continue;
                }
                packet.buf_mut()[..packet_len].copy_from_slice(packet_bytes);
                packet.truncate(packet_len);
                let ip = match packet.try_into_ip() {
                    Ok(ip) => ip,
                    Err(_) => {
                        record_dropped_packet();
                        continue;
                    }
                };

                // The reservation is shared with the native Direct path through
                // the same process-wide Rust limiter. Sleeping here yields the
                // Tokio task; it never blocks a C epoll thread.
                let wait_ns = tc_speed_prototype_reserve(
                    0,
                    uid,
                    packet_len,
                    if dns_port_53 { 1 } else { 0 },
                );
                if wait_ns > 0 {
                    tokio::time::sleep(Duration::from_nanos(wait_ns as u64)).await;
                    record_delayed(packet_len);
                    record_transmitted(crate::speed_shaping_prototype::Direction::Upload, packet_len);
                } else if wait_ns < 0 {
                    // Direction is a compile-time constant above. Keep the
                    // tunnel alive if the ABI contract is ever violated.
                    record_scheduler_error();
                    log::error!("speed limiter rejected WireGuard upload reservation");
                    tokio::time::sleep(Duration::from_secs(1)).await;
                } else {
                    record_transmitted(crate::speed_shaping_prototype::Direction::Upload, packet_len);
                }
                packets.push(ip);
            }

            if !packets.is_empty() {
                return Ok(packets.into_iter());
            }
            // Nothing (valid) read; wait for the next readiness event.
        }
    }

    fn mtu(&self) -> MtuWatcher {
        self.mtu.clone()
    }
}
