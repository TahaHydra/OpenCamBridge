# Sequential release hardening

Worktree: `C:\Dev\OpenCamBridge-GUI`, branch `ui/camo-redesign`.
Starting commit: `76f96a3bddf15c50453fcb7c09826291496accbb`.

User direction: one phase at a time. Review a bounded area, fix the findings,
verify, and commit a checkpoint before starting the next area. No parallel
review batches. Physical phone/app checks belong in the user's checklist.

## Phase 1 — finish existing local pairing and identified injection fix

- QR and expiring eight-digit code, automatic endpoint/credential exchange.
- SRP mutual authentication and AES-GCM protected pairing; DPAPI/Keystore storage.
- Saved devices, identity-checked rediscovery, forget/revoke including live streams.
- Existing USB and Advanced manual connection retained.
- Fix Windows QR host count exceeding Android's eight-address limit.
- Fix reflected script injection through USB `/obs?token=...`: USB reflects no
  credential, LAN reflects only this request's credential, with script-safe JSON.
- Do not claim release readiness until automated builds/checks are recorded.

Verification so far: 87 Android unit tests (zero failures/errors/skips), debug and
unsigned release APK builds; 33 native Rust tests; 84 frontend tests plus ten
OCB2 conformance cases. The Windows release executable build also passed
(`npm run tauri -- build --no-bundle`). Installer packaging was not run.
No physical phone/app testing was performed. Android build warnings remain for
deprecated Wi-Fi APIs and Gradle's JDK native-access notice.

## Phase 2 — dependency and release configuration review

### 2a — desktop JavaScript dependencies: verified

The full npm audit found four affected build dependencies: baseline-browser-mapping,
browserslist, nanoid and postcss. Updated their compatible lockfile versions and
required transitives (no package.json major-version change). Full `npm audit` now
reports zero advisories. All 84 frontend tests, ten OCB2 conformance cases, and
the frontend production build pass with the updated lockfile.

### 2b — Android pairing crypto update: verified

Updated Bouncy Castle from 1.83 to the current stable 1.86 hardening release:
https://www.bouncycastle.org/download/bouncy-castle-java/ . The complete Android
unit suite (including the fixed SRP interoperability vector), debug APK and unsigned
release APK builds pass. This is not a claim that every upstream advisory affects
the limited SRP API used here, nor a full transitive Android audit.

### 2c — remaining dependency and release configuration review: pending

Check current advisories and applicable fixes in small batches; verify each changed
dependency before committing. cargo-audit is not installed. A web search alone is
not a complete Rust/Android dependency audit. Review signing/distribution state.

## Phase 3 — remaining network/lifecycle/performance risks

Not yet complete. Bound each review to a component and checkpoint its fixes before
moving on. No speculative streaming/scheduler rewrite without evidence.

## Known security boundary

Pairing is encrypted, but existing HTTP video/control traffic and bearer tokens
are not. Trusted private LAN only; do not advertise secure operation on hostile
Wi-Fi. Identity challenges do not encrypt or authenticate the later HTTP transport.
No review or test pass guarantees the absence of vulnerabilities.

## User's physical acceptance checks

1. Windows Wi-Fi → Pair a phone; Android Settings → Connection → Scan PC QR.
   Stop capture before scanning. Verify the PC name and approve.
2. Start Wi-Fi capture on Android; Connect from Saved phones on Windows.
3. Repeat with the short code; try a wrong/expired code and a cancelled invitation.
4. Restart both apps and reconnect without entering a token; optionally change
   the phone's LAN IP and check rediscovery.
5. Revoke the PC on Android during streaming: access must stop and reconnect fail.
   Re-pair, then check Forget removes the Windows saved entry.
6. Check H.264 preview/native camera, MJPEG, OBS, Stop, USB and Advanced manual
   connection. Pairing alone must never start capture.

Allow the Windows app through the firewall on private networks only. Pairing uses
UDP 47653 plus a temporary TCP listener; saved-phone discovery uses UDP 47654.
Guest Wi-Fi/client isolation can prevent pairing. IPv6-only pairing is not supported.
