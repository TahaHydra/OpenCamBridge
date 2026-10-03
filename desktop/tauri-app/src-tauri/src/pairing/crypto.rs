use aes_gcm::{
    aead::{Aead, Payload},
    Aes256Gcm, KeyInit, Nonce,
};
use num_bigint::BigUint;
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use srp::{client::SrpClient, groups::G_2048, server::SrpServer};
use subtle::ConstantTimeEq;
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

#[derive(Clone, Serialize, Deserialize)]
pub struct Envelope {
    pub nonce: String,
    pub ciphertext: String,
}

pub fn random_hex(bytes: usize) -> String {
    let mut value = Zeroizing::new(vec![0; bytes]);
    OsRng.fill_bytes(&mut value);
    hex::encode(&*value)
}
pub fn decode(value: &str, size: usize) -> Result<Vec<u8>, String> {
    if value.len() != size * 2 {
        return Err("Invalid pairing message".into());
    }
    hex::decode(value).map_err(|_| "Invalid pairing message".into())
}
pub fn seal(key: &[u8; 32], aad: &str, plain: &[u8]) -> Result<Envelope, String> {
    let mut nonce = [0; 12];
    OsRng.fill_bytes(&mut nonce);
    let ciphertext = Aes256Gcm::new(key.into())
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: plain,
                aad: aad.as_bytes(),
            },
        )
        .map_err(|_| "Pairing encryption failed")?;
    Ok(Envelope {
        nonce: hex::encode(nonce),
        ciphertext: hex::encode(ciphertext),
    })
}
pub fn open(key: &[u8; 32], aad: &str, sealed: &Envelope) -> Result<Vec<u8>, String> {
    let nonce = decode(&sealed.nonce, 12)?;
    if sealed.ciphertext.len() < 32 || sealed.ciphertext.len() > 8192 {
        return Err("Invalid pairing payload".into());
    }
    let ciphertext = hex::decode(&sealed.ciphertext).map_err(|_| "Invalid pairing payload")?;
    Aes256Gcm::new(key.into())
        .decrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: &ciphertext,
                aad: aad.as_bytes(),
            },
        )
        .map_err(|_| "Pairing authentication failed".into())
}

fn padded(value: &BigUint) -> Vec<u8> {
    let raw = value.to_bytes_be();
    let mut result = vec![0; 256];
    result[256 - raw.len()..].copy_from_slice(&raw);
    result
}
fn hash(parts: &[&[u8]]) -> [u8; 32] {
    let mut digest = Sha256::new();
    for part in parts {
        digest.update(part);
    }
    digest.finalize().into()
}

#[derive(Zeroize, ZeroizeOnDrop)]
pub struct Proof {
    pub key: [u8; 32],
    m1: [u8; 32],
    pub m2: [u8; 32],
}
impl Proof {
    #[cfg(test)]
    pub fn client_proof_for_test(&self) -> String {
        hex::encode(self.m1)
    }
    pub fn verify(&self, proof: &str) -> bool {
        decode(proof, 32).is_ok_and(|value| self.m1.ct_eq(&value).into())
    }
}

/// BC SRP6Util wire convention. Public values and hashes are padded to the
/// RFC5054 group width; do not use srp crate's minimal-byte default proofs.
pub fn challenge(
    identity: &str,
    password: &str,
    a_hex: &str,
    salt: &[u8],
    private: &[u8],
) -> Result<(String, Proof), String> {
    let a_bytes = decode(a_hex, 256)?;
    let a = BigUint::from_bytes_be(&a_bytes);
    if a == BigUint::default() || a >= G_2048.n {
        return Err("Invalid SRP public value".into());
    }
    let client = SrpClient::<Sha256>::new(&G_2048);
    let server = SrpServer::<Sha256>::new(&G_2048);
    let verifier = client.compute_verifier(identity.as_bytes(), password.as_bytes(), salt);
    let v = BigUint::from_bytes_be(&verifier);
    let b = BigUint::from_bytes_be(&server.compute_public_ephemeral(private, &verifier));
    if b == BigUint::default() {
        return Err("Invalid SRP challenge".into());
    }
    let b_bytes = padded(&b);
    let u = BigUint::from_bytes_be(&hash(&[&a_bytes, &b_bytes]));
    if u == BigUint::default() {
        return Err("Invalid SRP challenge".into());
    }
    let s = server.compute_premaster_secret(&a, &v, &u, &BigUint::from_bytes_be(private));
    let secret = Zeroizing::new(padded(&s));
    let m1 = hash(&[&a_bytes, &b_bytes, &secret]);
    let m2 = hash(&[&a_bytes, &padded(&BigUint::from_bytes_be(&m1)), &secret]);
    Ok((
        hex::encode(b_bytes),
        Proof {
            key: hash(&[&secret]),
            m1,
            m2,
        },
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn android_bouncycastle_vector_matches_and_wrong_password_cannot_prove() {
        // Independent BC SRP6Client/SRP6Server vector from Android SrpInteropTest.
        let client = SrpClient::<Sha256>::new(&G_2048);
        let public = hex::encode(padded(
            &client.compute_a_pub(&BigUint::from_bytes_be(&(1..=32).collect::<Vec<u8>>())),
        ));
        let salt = (0..16).collect::<Vec<u8>>();
        let private = (65..=96).collect::<Vec<u8>>();
        let (_, proof) = challenge(
            "0123456789abcdef0123456789abcdef",
            "12345678",
            &public,
            &salt,
            &private,
        )
        .unwrap();
        let m1 = "ab65592fc57f210dbb4753bf426c61fa2e5bca95a7eb4279f0841e4a281b9e32";
        assert!(proof.verify(m1));
        assert_eq!(
            hex::encode(proof.m2),
            "3fac09c69f2cef5338045b6bf5ff53473df82929a4345d04790e70f0202cb123"
        );
        assert_eq!(
            hex::encode(proof.key),
            "d457dfd42aac68792e0e4367d6e14d33978c742d8d7c41b5effd7c29d5ef095a"
        );
        let (_, wrong) = challenge(
            "0123456789abcdef0123456789abcdef",
            "87654321",
            &public,
            &salt,
            &private,
        )
        .unwrap();
        assert!(!wrong.verify(m1));
        assert!(challenge("id", "12345678", &"00".repeat(256), &salt, &private).is_err());
        assert!(challenge(
            "id",
            "12345678",
            &hex::encode(G_2048.n.to_bytes_be()),
            &salt,
            &private
        )
        .is_err());
    }
    #[test]
    fn credentials_are_authenticated_and_bound_to_session_and_direction() {
        let key = [7; 32];
        let encrypted = seal(&key, "session:phone", b"permanent secret").unwrap();
        assert_eq!(
            open(&key, "session:phone", &encrypted).unwrap(),
            b"permanent secret"
        );
        assert!(open(&key, "other:phone", &encrypted).is_err());
        assert!(open(&key, "session:ack", &encrypted).is_err());
        let mut tampered = encrypted.clone();
        tampered.ciphertext.replace_range(
            ..2,
            if &tampered.ciphertext[..2] == "00" {
                "01"
            } else {
                "00"
            },
        );
        assert!(open(&key, "session:phone", &tampered).is_err());
    }
}
