# V2 validation checklist

`AUDIT.md` is authoritative for evidence. Do not mark a physical item passed from a build, process state, or producer decode metric.

## Automated matrix

- [ ] Android: `testDebugUnitTest`, `assembleDebug`, `assembleRelease`, `lint`.
- [ ] Producer: `cargo fmt --check`, `cargo test --all-targets`, `cargo build --release`.
- [ ] Tauri backend: `cargo fmt --check`, `cargo test --all-targets`, `cargo build --release`.
- [ ] Frontend: TypeScript check, frontend tests, production build.
- [ ] Native: Release DLL and installer/host, installer CLI, ring ABI, buffer-lock, NV12 resize, built/installed/registered/runtime identity checks.
- [ ] Shared OCB2 corpus passes Kotlin, Rust, and browser JavaScript.

## Physical phone path — NOT YET TESTED

- [ ] Enumerate selected camera regular and constrained-high-speed modes on OnePlus 9 OxygenOS.
- [ ] Prove actual 1080p60 where a complete public Camera2 + encoder path sustains it.
- [ ] Prove 720p60 downgrade, user-selected 1080p30/720p30, and exact explicit rejection.
- [ ] Verify regular, direct constrained-high-speed, and GPU bridge paths or record exact capability/session rejection.
- [ ] Verify capture-session FPS, bridge output FPS, encoded FPS, transport FPS, and no fake repeated-frame FPS.
- [ ] Verify all lenses, torch/zoom, continuous autofocus, rotation/mirror, phone preview active/failure state.
- [ ] Verify screen off for 30 minutes, thermal behavior, USB disconnect/reconnect without app restart.

## H.264 end to end — NOT YET TESTED

- [ ] `/stream.ocb2` supplies stream info, codec config, complete AUs, heartbeat, and keyframe after connect.
- [ ] Windows reports Microsoft decoder identity, D3D11 output state, and hardware decode `unknown` unless proven.
- [ ] OBS and FFmpeg show correct NV12 colors/geometry at each advertised 720p/1080p 30/60 format.
- [ ] Rotation/mirror match phone, web, desktop preview, OBS, and FFmpeg.
- [ ] Reconnect, discontinuity, device loss, and intermediate adaptive rejection recover at a new keyframe.
- [ ] Latency, drops/replacements, unique/repeated counters, and bitrate remain truthful.

## MJPEG compatibility — NOT YET TESTED

- [ ] Explicit MJPEG modes match canonical per-resolution FPS capability.
- [ ] H.264 1080p60 failure selects supported MJPEG such as 1080p30 and reports desired/selected/actual separately.
- [ ] Content-Length multipart parsing survives fragmentation/reconnect.
- [ ] Phone-side rotation/mirror produces identical pixels in every consumer without downstream duplication.
- [ ] Pacing writes a frame that arrived before its deadline even if no later frame arrives.

## Windows camera and packaging — NOT YET TESTED where physical/external

- [ ] Elevated `--register`, `--status`, `--unregister`, and re-register work on a clean Windows machine.
- [ ] Non-admin registration shows a clear elevation command.
- [ ] Start rejects a dead host, missing registration, stale binary identity, missing actual tuple, or fewer than three ring commits.
- [ ] OBS crash clears current consumer readiness within two seconds.
- [ ] External allocators exercise all buffer-lock fallthrough paths.
- [ ] D3D device loss recreates resize resources; simulated failure uses CPU fallback.

## Security and synchronization

- [ ] Automated policy tests cover LAN token enforcement, `/health`, bind snapshot, loopback-only security, CORS, and constant-time selected comparison.
- [ ] Physical LAN: wrong/missing token is 401, token header/query works, random origins receive no CORS grant.
- [ ] Concurrent phone/web/Tauri mutations produce 409 rollback/422 alternatives without cross-client patch merging.
- [ ] SSE and polling converge on the same authoritative generation/revision.
