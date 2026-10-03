# Local pairing implementation plan

> **For agentic workers:** Use test-driven-development and the task ownership below.

**Goal:** Local QR/code pairing without typing or shortening a permanent token.
**Architecture:** A temporary Windows LAN pairing listener, Android QR/manual client,
and per-PC random credentials. SRP-6a with RFC5054 2048-bit group, SHA256 and AES-GCM
protects the pairing exchange; existing HTTP video/control stays trusted-LAN only.
**Tech stack:** RustCrypto SRP math/AES-GCM, Bouncy Castle SRP client, ZXing scanner,
Windows DPAPI and Android Keystore storage. No cloud or account.
**Spec:** User-approved scope in this conversation; wire contract below.

## Constraints and review focus

- GUI worktree/branch only; preserve the pre-existing Cargo.toml line-ending change.
- No physical phone/app tests; provide the manual acceptance checklist.
- Invitation lasts120 seconds, only while the pairing UI is open; cancel/restart
  invalidates all outstanding exchanges. At most5 manual starts and10 QR starts.
- Both modes require explicit Android approval of the authenticated PC name.
- Verify SRP public values, mutual proofs, message bounds, replay/expiry and tampering.
- Never send a code or permanent credential in plaintext during pairing, QR excludes
  the permanent token, no secrets in discovery, logs or frontend localStorage.
- Revoking a paired PC must reject new requests AND terminate existing streams.
- Preserve Advanced manual IP/token and USB behavior. No network-wide scanning,
  automatic firewall changes, automatic camera/service Start or unrelated redesign.

## Wire contract v1

Desktop temporary HTTP listener accepts private IPv4 clients only. UDP47653 responds
to `OCB_PAIR_DISCOVER_V1` with JSON `{service:"ocb-pair-v1",id,name,port}`.
QR JSON: `{v:1,service:"ocb-pair-v1",id,hosts:[privateIPv4],port,secret}`.
Invitation id32 hex, QR secret64 hex, code8 decimal digits (display grouped4+4).
PC identity32 hex persisted; phone identity32 hex persisted. Names max80 chars.
All binary wire values lowercase hex. Public A/B canonical256-byte big-endian;
salt16 bytes, private ephemerals32 random bytes. Identity UTF8 invitation id;
password UTF8 QR secret or unspaced code. Group RFC5054 2048/N, g=2, SHA256.
SRP is BouncyCastle-compatible: k=H(PAD(N)||PAD(g)), u=H(PAD(A)||PAD(B)),
x=H(salt||H(identity||":"||password)), S=SRP6a premaster, all PAD256 bytes.
M1=H(PAD(A)||PAD(B)||PAD(S)); M2=H(PAD(A)||PAD(M1)||PAD(S)); K=H(PAD(S)).
These proofs intentionally use BC's SRP6Util convention, not Rust srp's default
minimal-byte proof helpers. Use vetted SRP math, not a new PAKE implementation.

1. POST `/pair/start` `{id,kind:"qr"|"code",a}` -> `{session,salt,b,name}`.
   Session32 random hex; reserve attempt before modular exponentiation.
2. POST `/pair/prove` `{session,m1}` -> `{m2,nonce,ciphertext}`.
   AES256-GCM K, random12-byte nonce, ciphertext includes16-byte tag.
   AAD UTF8 `ocb-pair-v1:<session>:pc`; plaintext `{pcId,name}`.
3. Android verifies M2 and decrypts before approval. On approve, create/rotate a
   per-PC64-hex credential; persist it securely before sending the final request.
   POST `/pair/finish` `{session,nonce,ciphertext}`; AAD
   `ocb-pair-v1:<session>:phone`; plaintext `{phoneId,name,port,token,credentialId}`.
   credentialId32 random hex; phone port from local connection settings.
   Desktop derives phone IP from TCP peer, not the payload; DPAPI stores credential.
   Response `{nonce,ciphertext}` AAD `ocb-pair-v1:<session>:ack`, plaintext
   `{ok:true,pcId}`. Identical finish retries get cached ACK; other replays reject.
   Successful invitation cannot pair a second phone. Polling UI receives metadata,
   not secrets; explicit connect command returns selected saved credential in memory.

Saved credential validation/discovery: phone running in LAN mode exposes
`/api/pairing/identify?credentialId=...&nonce=...` (nonce32 random hex), returns
`{phoneId,port,proof}` where proof=HMAC-SHA256(token UTF8,
`ocb-identify-v1:<phoneId>:<credentialId>:<nonce>:<port>`). Desktop verifies before
sending token to a saved IP. Phone UDP47654 accepts JSON `{service:"ocb-phone-v1",
credentialId,nonce}` and returns same identity proof; Windows can rediscover changed
IP with one bounded broadcast query. No secret in either query/response.
Discovery exists only while StreamService is running in LAN mode; full Stop closes it.

## Task ownership and checks

### Task1: Desktop pairing core (parent)
- [ ] Add failing tests: SRP correct/wrong secret and invalid public values; AES tamper;
  invitation expiry/attempt/replay; reject public/loopback endpoints; DPAPI roundtrip.
- [ ] Implement pairing crypto, temporary listener/discovery and encrypted saved store
  under `src-tauri/src/pairing/`; register commands in lib.rs.
- [ ] Run cargo test; cross-language fixed-vector interoperability with Android.

### Task2: Android pairing and authorization (Android worker)
- [ ] Tests first for QR parsing/private endpoints, SRP vector, revoke/persistence logic.
- [ ] Implement QR scanner/manual discovery, authenticated confirmation and per-PC
  Keystore credentials under `android/.../pairing`; connection-settings entry point.
- [ ] Integrate paired authentication and live revocation; LAN identity/discovery lifecycle.
- [ ] Preserve full Stop; scanner requires stopped capture, manual pairing works while
  streaming. Pairing does not automatically start capture or switch security settings.
- [ ] Run Android testDebugUnitTest and assembleDebug; no device interaction.

### Task3: Desktop pairing UI (UI worker)
- [ ] Add saved-phone list, pair QR/code invitation and cancel/expiry states under
  components/connect; put existing IP/token fields under Advanced.
- [ ] Wire typed Tauri commands (parent provides exact contract); no token persistence
  in React preferences; Forget deletes desktop credential, phone UI does revocation.
- [ ] Run npm test/typecheck/build; no interactive app testing.

### Task4: Integration and handoff (parent)
- [ ] Review auth/replay/cancellation and contract; one independent security review.
- [ ] Fix important findings, run combined automated checks and package builds.
- [ ] Document firewall/private-LAN limits, HTTP streaming tradeoff and concise manual
  scan/code/reconnect/revoke checklist. Commit/push current branch as requested workflow.

## Progress / decisions

- Initial branch76f96a3; only pre-existing Cargo.toml line-ending-only change present.
- User explicitly requested implementation without more hands-on testing. Proceed
  from approved scope without repeating internal design approval gates.
- Implementation for Tasks1–3 is now present. Focused crypto, persistence,
  cancellation, replay, revocation and UI lifecycle tests pass. The independent
  source review found a USB OBS script-injection defect; it was fixed with
  script-safe request-token encoding and covered by regression tests.
- The user subsequently requested sequential bounded phases and durable commits,
  not parallel review batches. `docs/release-phases.md` is the current handoff;
  broader dependency/release and performance reviews are separate phases.
