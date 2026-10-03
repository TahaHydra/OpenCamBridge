use std::net::Ipv4Addr;
pub fn private_host(host: &str) -> Option<Ipv4Addr> {
    host.parse::<Ipv4Addr>().ok().filter(|ip| ip.is_private())
}
pub struct Attempts {
    pub code: u8,
    pub qr: u8,
}
impl Attempts {
    pub fn reserve(&mut self, kind: &str) -> bool {
        let (used, limit) = match kind {
            "code" => (&mut self.code, 5),
            "qr" => (&mut self.qr, 10),
            _ => return false,
        };
        if *used >= limit {
            return false;
        }
        *used += 1;
        true
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pairing_never_targets_public_loopback_dns_or_url_injection() {
        assert!(private_host("192.168.1.50").is_some());
        assert!(private_host("10.2.3.4").is_some());
        assert!(private_host("172.31.1.2").is_some());
        for host in [
            "127.0.0.1",
            "8.8.8.8",
            "172.32.1.2",
            "192.168.1.2@evil.test",
            "localhost",
            "169.254.1.2",
            "10.0.0.1:80",
        ] {
            assert!(private_host(host).is_none(), "{host}");
        }
    }
    #[test]
    fn attempts_are_global_and_never_reset_by_client_or_handshake_failure() {
        let mut attempts = Attempts { code: 0, qr: 0 };
        for _ in 0..5 {
            assert!(attempts.reserve("code"));
        }
        assert!(!attempts.reserve("code"));
        assert!(!attempts.reserve("unknown"));
        for _ in 0..10 {
            assert!(attempts.reserve("qr"));
        }
        assert!(!attempts.reserve("qr"));
    }
}
