use super::policy::Attempts;
use super::{
    crypto::{self, Envelope, Proof},
    store::{Credential, SavedPhone, Store},
};
use rand::{rngs::OsRng, Rng};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use std::{
    collections::HashMap,
    net::Ipv4Addr,
    time::{SystemTime, UNIX_EPOCH},
};
use zeroize::Zeroizing;

pub struct Window {
    pub expires: Instant,
    pub cancelled: bool,
    pub paired: bool,
    pub attempts: Attempts,
}
impl Window {
    pub fn reserve(&mut self, kind: &str, now: Instant) -> Result<(), String> {
        if self.cancelled || self.paired || now >= self.expires {
            return Err("Pairing invitation has ended".into());
        }
        if !self.attempts.reserve(kind) {
            return Err("Pairing attempts exhausted. Create a new invitation on the PC.".into());
        }
        Ok(())
    }
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InvitationView {
    pub id: String,
    pub name: String,
    pub code: String,
    pub qr_svg: String,
    pub expires_at: u64,
    pub hosts: Vec<String>,
    pub port: u16,
}
#[derive(Serialize)]
pub struct Status {
    pub state: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub phone: Option<SavedPhone>,
}

struct Exchange {
    peer: Ipv4Addr,
    proof: Proof,
    verified: bool,
    finished: Option<(String, Value)>,
}
pub struct Invitation {
    pub view: InvitationView,
    pub window: Window,
    qr_secret: Zeroizing<String>,
    pc_id: String,
    exchanges: HashMap<String, Exchange>,
    pub phone: Option<SavedPhone>,
}
impl Invitation {
    pub fn new(pc_id: String, name: String, mut hosts: Vec<String>, port: u16) -> Result<Self, String> {
        // Android bounds QR connection attempts to eight private interfaces.
        hosts.truncate(8);
        let id = crypto::random_hex(16);
        let secret = Zeroizing::new(crypto::random_hex(32));
        let code = format!("{:08}", OsRng.gen_range(0..100_000_000u32));
        let qr = json!({"v":1,"service":"ocb-pair-v1","id":id,"hosts":hosts,"port":port,"secret":&*secret}).to_string();
        let qr_svg = qrcode::QrCode::new(qr.as_bytes())
            .map_err(|_| "Cannot generate pairing QR")?
            .render::<qrcode::render::svg::Color>()
            .min_dimensions(300, 300)
            .build();
        Ok(Self {
            view: InvitationView {
                id,
                name,
                code,
                qr_svg,
                expires_at: now_ms() + 120_000,
                hosts,
                port,
            },
            window: Window {
                expires: Instant::now() + Duration::from_secs(120),
                cancelled: false,
                paired: false,
                attempts: Attempts { code: 0, qr: 0 },
            },
            qr_secret: secret,
            pc_id,
            exchanges: HashMap::new(),
            phone: None,
        })
    }
    pub fn status(&self) -> Status {
        let state = if self.window.cancelled {
            "cancelled"
        } else if self.window.paired {
            "paired"
        } else if Instant::now() >= self.window.expires {
            "expired"
        } else {
            "waiting"
        };
        Status {
            state,
            phone: self.phone.clone(),
        }
    }
    pub fn listening(&self) -> bool {
        !self.window.cancelled && Instant::now() < self.window.expires
    }
    pub fn cancel(&mut self) {
        self.window.cancelled = true;
        self.clear_secrets();
    }
    pub fn clear_secrets(&mut self) {
        self.exchanges.clear();
        use zeroize::Zeroize;
        self.view.code.zeroize();
        self.view.qr_svg.zeroize();
        self.qr_secret.zeroize();
    }
    pub fn handle(
        &mut self,
        path: &str,
        request: Value,
        peer: Ipv4Addr,
        store: &mut Store,
    ) -> Result<Value, String> {
        if !self.listening() {
            return Err("Pairing invitation has ended".into());
        }
        match path {
            "/pair/start" => {
                #[derive(Deserialize)]
                #[serde(deny_unknown_fields)]
                struct Start {
                    id: String,
                    kind: String,
                    a: String,
                }
                let request: Start =
                    serde_json::from_value(request).map_err(|_| "Invalid pairing request")?;
                if request.id != self.view.id {
                    return Err("Unknown pairing invitation".into());
                }
                self.window.reserve(&request.kind, Instant::now())?;
                let password = if request.kind == "qr" {
                    &*self.qr_secret
                } else {
                    &self.view.code
                };
                let salt = crypto::decode(&crypto::random_hex(16), 16)?;
                let private = Zeroizing::new(crypto::decode(&crypto::random_hex(32), 32)?);
                let (b, proof) =
                    crypto::challenge(&self.view.id, password, &request.a, &salt, &private)?;
                let session = crypto::random_hex(16);
                self.exchanges.insert(
                    session.clone(),
                    Exchange {
                        peer,
                        proof,
                        verified: false,
                        finished: None,
                    },
                );
                Ok(json!({"session":session,"salt":hex::encode(salt),"b":b,"name":self.view.name}))
            }
            "/pair/prove" => {
                #[derive(Deserialize)]
                #[serde(deny_unknown_fields)]
                struct Prove {
                    session: String,
                    m1: String,
                }
                let request: Prove =
                    serde_json::from_value(request).map_err(|_| "Invalid pairing request")?;
                if self.window.paired {
                    return Err("Invitation already paired".into());
                }
                let exchange = self
                    .exchanges
                    .get_mut(&request.session)
                    .ok_or("Unknown pairing session")?;
                if exchange.peer != peer {
                    return Err("Pairing peer changed".into());
                }
                if !exchange.proof.verify(&request.m1) {
                    self.exchanges.remove(&request.session);
                    return Err("Pairing code was not accepted".into());
                }
                exchange.verified = true;
                let sealed = crypto::seal(
                    &exchange.proof.key,
                    &format!("ocb-pair-v1:{}:pc", request.session),
                    json!({"pcId":self.pc_id,"name":self.view.name})
                        .to_string()
                        .as_bytes(),
                )?;
                Ok(
                    json!({"m2":hex::encode(exchange.proof.m2),"nonce":sealed.nonce,"ciphertext":sealed.ciphertext}),
                )
            }
            "/pair/finish" => {
                #[derive(Deserialize)]
                #[serde(deny_unknown_fields)]
                struct Finish {
                    session: String,
                    nonce: String,
                    ciphertext: String,
                }
                let request: Finish =
                    serde_json::from_value(request).map_err(|_| "Invalid pairing request")?;
                let exchange = self
                    .exchanges
                    .get_mut(&request.session)
                    .ok_or("Unknown pairing session")?;
                if !exchange.verified || exchange.peer != peer {
                    return Err("Pairing proof required".into());
                }
                let fingerprint = format!("{}:{}", request.nonce, request.ciphertext);
                if let Some((previous, response)) = &exchange.finished {
                    return if previous == &fingerprint {
                        Ok(response.clone())
                    } else {
                        Err("Pairing replay rejected".into())
                    };
                }
                if self.window.paired {
                    return Err("Invitation already paired".into());
                }
                let plaintext = Zeroizing::new(crypto::open(
                    &exchange.proof.key,
                    &format!("ocb-pair-v1:{}:phone", request.session),
                    &Envelope {
                        nonce: request.nonce,
                        ciphertext: request.ciphertext,
                    },
                )?);
                #[derive(Deserialize)]
                #[serde(rename_all = "camelCase", deny_unknown_fields)]
                struct Phone {
                    phone_id: String,
                    name: String,
                    port: u16,
                    token: String,
                    credential_id: String,
                }
                let phone: Phone =
                    serde_json::from_slice(&plaintext).map_err(|_| "Invalid phone credentials")?;
                crypto::decode(&phone.phone_id, 16)?;
                crypto::decode(&phone.credential_id, 16)?;
                crypto::decode(&phone.token, 32)?;
                if phone.port < 1024
                    || phone.name.is_empty()
                    || phone.name.chars().count() > 80
                    || phone.name.chars().any(char::is_control)
                {
                    return Err("Invalid phone details".into());
                }
                let saved = SavedPhone {
                    phone_id: phone.phone_id,
                    name: phone.name,
                    last_host: peer.to_string(),
                    port: phone.port,
                    paired_at: now_ms(),
                };
                store.upsert(Credential {
                    phone: saved.clone(),
                    credential_id: phone.credential_id,
                    token: phone.token,
                })?;
                let ack = crypto::seal(
                    &exchange.proof.key,
                    &format!("ocb-pair-v1:{}:ack", request.session),
                    json!({"ok":true,"pcId":self.pc_id}).to_string().as_bytes(),
                )?;
                let response =
                    serde_json::to_value(ack).map_err(|_| "Cannot encode pairing response")?;
                exchange.finished = Some((fingerprint, response.clone()));
                self.window.paired = true;
                self.phone = Some(saved);
                Ok(response)
            }
            _ => Err("Unknown pairing endpoint".into()),
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn qr_invitation_fits_the_android_eight_address_limit() {
        let hosts = (1..=12).map(|n| format!("192.168.{n}.1")).collect();
        let invitation = Invitation::new(crypto::random_hex(16), "Test PC".into(), hosts, 9000).unwrap();
        assert_eq!(invitation.view.hosts.len(), 8);
    }
    #[test]
    fn authenticated_finish_persists_once_and_rejects_changed_replay_and_cancel() {
        let directory =
            std::env::temp_dir().join(format!("ocb-pair-session-{}", crypto::random_hex(16)));
        let mut store = Store::load(&directory).unwrap();
        let mut invitation = Invitation::new(
            store.pc_id.clone(),
            "Test PC".into(),
            vec!["192.168.1.1".into()],
            9000,
        )
        .unwrap();
        let peer = "192.168.1.2".parse().unwrap();
        let public = srp::client::SrpClient::<sha2::Sha256>::new(&srp::groups::G_2048)
            .compute_public_ephemeral(&[7; 32]);
        let public = format!("{:0>512}", hex::encode(public));
        let start = invitation
            .handle(
                "/pair/start",
                json!({"id":invitation.view.id,"kind":"code","a":public}),
                peer,
                &mut store,
            )
            .unwrap();
        let session = start["session"].as_str().unwrap();
        // Crypto interoperability is covered separately against Android's BC vector.
        // Here exercise approval/persistence/retry lifecycle with an authenticated client.
        let exchange = &invitation.exchanges[session];
        let key = exchange.proof.key;
        let m1 = exchange.proof.client_proof_for_test();
        let phone_id = crypto::random_hex(16);
        let payload = json!({"phoneId":phone_id,"name":"Test phone","port":8080,"token":crypto::random_hex(32),"credentialId":crypto::random_hex(16)});
        let sealed = crypto::seal(
            &key,
            &format!("ocb-pair-v1:{session}:phone"),
            payload.to_string().as_bytes(),
        )
        .unwrap();
        let finish = json!({"session":session,"nonce":sealed.nonce,"ciphertext":sealed.ciphertext});
        assert!(invitation
            .handle("/pair/finish", finish.clone(), peer, &mut store)
            .is_err());
        invitation
            .handle(
                "/pair/prove",
                json!({"session":session,"m1":m1}),
                peer,
                &mut store,
            )
            .unwrap();
        assert!(invitation
            .handle(
                "/pair/finish",
                finish.clone(),
                "192.168.1.3".parse().unwrap(),
                &mut store
            )
            .is_err());
        let ack = invitation
            .handle("/pair/finish", finish.clone(), peer, &mut store)
            .unwrap();
        assert_eq!(
            invitation
                .handle("/pair/finish", finish.clone(), peer, &mut store)
                .unwrap(),
            ack
        );
        assert_eq!(Store::load(&directory).unwrap().phones.len(), 1);
        let mut changed = finish.clone();
        changed["nonce"] = json!(crypto::random_hex(12));
        assert!(invitation
            .handle("/pair/finish", changed, peer, &mut store)
            .is_err());
        assert_eq!(invitation.status().state, "paired");
        invitation.cancel();
        assert!(invitation
            .handle("/pair/finish", finish, peer, &mut store)
            .is_err());
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[test]
    fn cancelled_expired_and_consumed_invitations_cannot_authenticate() {
        let now = Instant::now();
        let mut window = Window {
            expires: now + Duration::from_secs(120),
            cancelled: false,
            paired: false,
            attempts: Attempts { code: 0, qr: 0 },
        };
        assert!(window.reserve("code", now).is_ok());
        assert!(window
            .reserve("code", now + Duration::from_secs(120))
            .is_err());
        window.cancelled = true;
        assert!(window.reserve("qr", now).is_err());
        window.cancelled = false;
        window.paired = true;
        assert!(window.reserve("qr", now).is_err());
    }
}
