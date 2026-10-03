use std::{
    collections::HashSet,
    io::Read,
    net::{SocketAddr, UdpSocket},
    thread,
    time::{Duration, Instant},
};

use hmac::{Hmac, Mac};
use serde::Deserialize;
use sha2::Sha256;
use subtle::ConstantTimeEq;

use super::{crypto::random_hex, policy::private_host, store::Credential};

const DISCOVERY_PORT: u16 = 47654;
const MAX_RESPONSE: usize = 2048;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Identity {
    phone_id: String,
    port: u16,
    proof: String,
}

fn lower_hex(value: &str, length: usize) -> bool {
    value.len() == length
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn valid_credential(credential: &Credential) -> bool {
    lower_hex(&credential.phone.phone_id, 32)
        && lower_hex(&credential.credential_id, 32)
        && lower_hex(&credential.token, 64)
}

fn verified(credential: &Credential, nonce: &str, response: &Identity) -> bool {
    if !valid_credential(credential)
        || !lower_hex(nonce, 32)
        || !lower_hex(&response.phone_id, 32)
        || !lower_hex(&response.proof, 64)
        || response.port < 1024
        || !bool::from(
            response
                .phone_id
                .as_bytes()
                .ct_eq(credential.phone.phone_id.as_bytes()),
        )
    {
        return false;
    }
    let Ok(proof) = hex::decode(&response.proof) else {
        return false;
    };
    let Ok(mut mac) = Hmac::<Sha256>::new_from_slice(credential.token.as_bytes()) else {
        return false;
    };
    mac.update(
        format!(
            "ocb-identify-v1:{}:{}:{}:{}",
            response.phone_id, credential.credential_id, nonce, response.port,
        )
        .as_bytes(),
    );
    // verify_slice compares the full SHA256 proof in constant time.
    mac.verify_slice(&proof).is_ok()
}

fn saved_endpoint(credential: &Credential) -> Option<(String, u16)> {
    let host = private_host(&credential.phone.last_host)?;
    if credential.phone.port < 1024 {
        return None;
    }
    let nonce = random_hex(16);
    let client = reqwest::blocking::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_millis(600))
        .timeout(Duration::from_millis(1000))
        .build()
        .ok()?;
    // No bearer token, headers or permanent secret is sent to an unverified IP.
    let response = client
        .get(format!(
            "http://{}:{}/api/pairing/identify",
            host, credential.phone.port,
        ))
        .query(&[
            ("credentialId", credential.credential_id.as_str()),
            ("nonce", nonce.as_str()),
        ])
        .send()
        .ok()?;
    if !response.status().is_success()
        || response
            .content_length()
            .is_some_and(|size| size > MAX_RESPONSE as u64)
    {
        return None;
    }
    let mut bytes = Vec::new();
    response
        .take((MAX_RESPONSE + 1) as u64)
        .read_to_end(&mut bytes)
        .ok()?;
    if bytes.len() > MAX_RESPONSE {
        return None;
    }
    let identity: Identity = serde_json::from_slice(&bytes).ok()?;
    verified(credential, &nonce, &identity).then(|| (host.to_string(), identity.port))
}

fn discover(credential: &Credential) -> Option<(String, u16)> {
    let nonce = random_hex(16);
    let query = serde_json::to_vec(&serde_json::json!({
        "service": "ocb-phone-v1", "credentialId": credential.credential_id, "nonce": nonce,
    }))
    .ok()?;
    let deadline = Instant::now() + Duration::from_secs(2);
    let mut sockets = Vec::new();
    let mut sent_interfaces = HashSet::new();
    for interface in if_addrs::get_if_addrs().ok()? {
        if Instant::now() >= deadline {
            break;
        }
        let if_addrs::IfAddr::V4(address) = interface.addr else {
            continue;
        };
        if !address.ip.is_private() || !sent_interfaces.insert(address.ip) {
            continue;
        }
        let Some(broadcast) = address.broadcast else {
            continue;
        };
        // A directed broadcast is confined to this interface's private subnet.
        if !broadcast.is_private() || broadcast == address.ip {
            continue;
        }
        let Ok(socket) = UdpSocket::bind((address.ip, 0)) else {
            continue;
        };
        if socket.set_broadcast(true).is_err() || socket.set_nonblocking(true).is_err() {
            continue;
        }
        if socket.send_to(&query, (broadcast, DISCOVERY_PORT)).is_ok() {
            sockets.push(socket);
        }
    }
    if sockets.is_empty() {
        return None;
    }

    let mut bytes = [0_u8; MAX_RESPONSE + 1];
    while Instant::now() < deadline {
        for socket in &sockets {
            // Bound work per interface so a noisy peer cannot starve the deadline.
            for _ in 0..8 {
                if Instant::now() >= deadline {
                    return None;
                }
                match socket.recv_from(&mut bytes) {
                    Ok((length, SocketAddr::V4(peer))) => {
                        if length > MAX_RESPONSE
                            || !peer.ip().is_private()
                            || peer.port() != DISCOVERY_PORT
                        {
                            continue;
                        }
                        let Ok(identity) = serde_json::from_slice::<Identity>(&bytes[..length])
                        else {
                            continue;
                        };
                        if verified(credential, &nonce, &identity) {
                            return Some((peer.ip().to_string(), identity.port));
                        }
                    }
                    Ok(_) => continue,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => break,
                    Err(_) => break,
                }
            }
        }
        thread::sleep(
            Duration::from_millis(10).min(deadline.saturating_duration_since(Instant::now())),
        );
    }
    None
}

/// Resolve only an authenticated private IPv4 endpoint, without sending its token.
/// The caller may send the saved token only after this function succeeds.
pub fn resolve(credential: &Credential) -> Result<(String, u16), String> {
    if !valid_credential(credential) {
        return Err("Saved pairing is invalid. Forget this phone and pair again.".into());
    }
    saved_endpoint(credential).or_else(|| discover(credential)).ok_or_else(|| {
        "Could not verify the saved phone. Select Wi-Fi and tap Start on the phone, and keep both devices on the same private LAN. If this PC was revoked, pair again.".into()
    })
}

#[cfg(test)]
mod tests {
    use super::super::store::SavedPhone;
    use super::*;

    fn credential() -> Credential {
        Credential {
            phone: SavedPhone {
                phone_id: "a".repeat(32),
                name: "Phone".into(),
                last_host: "192.168.1.20".into(),
                port: 8080,
                paired_at: 0,
            },
            credential_id: "b".repeat(32),
            token: "d".repeat(64),
        }
    }

    fn identity() -> Identity {
        Identity {
            phone_id: "a".repeat(32),
            port: 8080,
            // Independent Node.js crypto.createHmac SHA256 fixture, ASCII token key.
            proof: "70aa84a2fc0c0efe093327c5ad9a0074443b3aca9199775012cd298df9b66150".into(),
        }
    }

    #[test]
    fn identity_proof_uses_ascii_token_and_binds_nonce_phone_and_port() {
        let credential = credential();
        let nonce = "c".repeat(32);
        assert!(verified(&credential, &nonce, &identity()));
        assert!(!verified(&credential, &"e".repeat(32), &identity()));
        let mut other_phone = identity();
        other_phone.phone_id = "e".repeat(32);
        assert!(!verified(&credential, &nonce, &other_phone));
        let mut other_port = identity();
        other_port.port = 8081;
        assert!(!verified(&credential, &nonce, &other_port));
        let mut tampered = identity();
        tampered.proof.replace_range(0..2, "00");
        assert!(!verified(&credential, &nonce, &tampered));
    }

    #[test]
    fn invalid_proof_lengths_hex_and_privileged_ports_fail_closed() {
        let credential = credential();
        for proof in [
            "".to_string(),
            "a".repeat(62),
            "g".repeat(64),
            identity().proof.to_uppercase(),
        ] {
            let mut response = identity();
            response.proof = proof;
            assert!(!verified(&credential, &"c".repeat(32), &response));
        }
        let mut response = identity();
        response.port = 80;
        assert!(!verified(&credential, &"c".repeat(32), &response));
    }

    #[test]
    fn credential_must_contain_canonical_ids_and_full_length_token() {
        let mut credential = credential();
        assert!(valid_credential(&credential));
        credential.token.pop();
        assert!(!valid_credential(&credential));
        credential.token = "d".repeat(64);
        credential.credential_id = "B".repeat(32);
        assert!(!valid_credential(&credential));
        credential.credential_id = "b".repeat(32);
        credential.phone.phone_id = "?".repeat(32);
        assert!(!valid_credential(&credential));
    }
}
