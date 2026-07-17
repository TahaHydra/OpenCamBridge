# OpenCamBridge — `v2/streaming-engine` Engineering Audit

**Repo:** github.com/TahaHydra/OpenCamBridge
**Branch:** `v2/streaming-engine`
**Commit reviewed:** `3f5ac86` ("docs(audit): record pre-physical blocker closure"), 86 commits on the branch
**Method:** direct source review (not a static-analysis tool run) across Android/Kotlin, Rust, C++/Media Foundation, and TypeScript/Tauri; cross-checked against `protocol/SPEC.md`, `docs/ARCHITECTURE.md`, and the build scripts. Every finding below cites the file(s) and, where useful, line ranges. I did not run the code (no Android device, no Windows box in this environment), so this is a source-level audit, not a runtime one — see the "what this doesn't cover" note at the end.

---

## Executive summary

This is a considerably more mature codebase than the file layout suggests at first glance. The last ~15 commits are almost all `fix()`/`docs(audit)` — this branch is the tail end of a genuine hardening pass, not a first draft. The three hardest problems in a project like this — the Camera2 constrained-high-speed capture path, the OCB2 wire protocol's malformed-input handling, and the cross-process shared-memory ring — are all handled correctly and consistently, with real attention to races, stale-callback identity, and bounds checking. The auth model (bind-time-snapshotted LAN mode, constant-time token compare, loopback-only security-settings changes) is genuinely defense-in-depth, not just "good enough."

That said, `docs/VALIDATION.md` says outright: *"None of the changes on `main` have been run on a device, in OBS, or through adb."* Read everything below with that in mind — the design and the defensive coding are strong, but very little of it has been exercised against real hardware (specific OEM encoders, real EGL drivers, real timing). The gaps that matter most are not "the code is wrong" but "the code has never been proven right against physical devices," plus a handful of concrete, fixable issues around dead/confusing code in the Windows installer, silent failure paths in two specific places, and a hand-duplicated ABI with no single source of truth.

**Priority findings (see full detail below):**

| # | Finding | Area | Severity |
|---|---|---|---|
| 1 | `VirtualCamera_Installer.exe`, run with no args (exactly what the desktop app's own error message tells a user to do), drops into an undocumented interactive Microsoft-sample console menu (`HWMediaSource`/`AugmentedMediaSource`/custom color-mode test harness) that has nothing to do with OpenCamBridge | Packaging / dead code | **High** (real user-facing confusion, easy fix) |
| 2 | Ring ABI (`OpenCamBridgeRingHeader`/`SlotHeader`) is hand-duplicated in 3 places (C++, Rust producer, Rust desktop preview) with only a manually-typed 64-bit hash as the cross-check | Shared-memory ABI | **Medium** (latent risk, not a live bug today) |
| 3 | `MainActivity.startStreamService()` failure is logged to logcat only, never to `AppLogger`/UI — a failed stream start is invisible to the user | Error handling | **Medium** |
| 4 | `dev-reset.ps1` sets `$ErrorActionPreference = "SilentlyContinue"` globally, so an unelevated run silently no-ops the service-stop steps it needs admin for | Packaging / error handling | **Medium** (already partially known — README documents the manual workaround) |
| 5 | Zero automated tests for the state machine, HTTP/auth layer, camera/codec integration, or the 1,700+ line desktop sync UI; what tests exist are good but narrow | Tests | **Medium** |
| 6 | Five whole C++/C# subprojects (WinRT manager, WinUI app, systray, 2 MSI installers) plus a full C++ test harness are inherited from the upstream Microsoft sample and never built by this project's own tooling | Dead code | **Low-Medium** (repo hygiene / audit surface, not a runtime risk) |
| 7 | `HWMediaSource.cpp`/`AugmentedMediaSource.cpp` (~1,700 lines) compile into the shipped DLL but are unreachable in this product | Dead code | **Low** |

Everything else is detailed per your review checklist below.

---

## 1. Application entry points

**Android:** `MainActivity` (Compose UI + permission request) → `StreamService` (foreground service, `foregroundServiceType="camera"`, `START_STICKY`). `MainActivity.onCreate()` unconditionally calls `requestPermissionsAndStart()` on every launch (`MainActivity.kt:240`) — this is a deliberate design ("cable-reconnect launches must bring the foreground service back without requiring a tap," per the inline comment), not an oversight, but it does mean simply opening the app starts the camera every time.

- **Finding (Medium):** `startStreamService()` (`MainActivity.kt:258-269`) catches the foreground-service-start exception and only calls `Log.e(...)` — logcat only. Compare with `AppLogger.kt`, which is the actual backing store for the phone's Logs tab and `/api/logs`. If `startForegroundService()` throws (e.g. Android 12+ background-start restrictions, or a permission race), the failure is invisible in the app UI and in the in-app Logs tab — the Start button just appears to do nothing. Every other failure path in this codebase (camera bind, codec start, rebind) correctly routes through `AppLogger`/`StreamState.lastError`; this one path doesn't.
  *Fix:* route the catch through `AppLogger.e("System", ...)` and set a user-visible state (e.g. reuse `StreamState.lastError`) so the UI can show something.

**Rust producer:** `main()` in `main.rs` — dispatches on `--source` (`mjpeg` / `h264` / `test-pattern`) into `run_h264_v2`, an MJPEG loop, or a synthetic-pattern loop, each independently reconnecting/backing off. Clean separation.

**Media Foundation DLL:** `DllGetActivatableClassFactory`/`GetActivationFactory` (`dllmain.cpp`) → `VirtualCameraMediaSourceActivate::ActivateObject` (`VirtualCameraMediaSourceActivate.cpp:9`), which branches on the `VCAM_KIND` attribute into `SimpleMediaSource` (the one this product actually uses), `HWMediaSource`, or `AugmentedMediaSource` — see §15 on the latter two.

**VirtualCamera_Installer.exe** (`main.cpp:372`, `wmain`): three real code paths —
1. `--self-test-pipeline` → runs the buffer-lock and NV12-resize self-tests (`OcbRunBufferLockFallbackSelfTests`, `OcbRunResizeFallbackSelfTests`) and prints PASS/FAIL. Good that these exist; nothing in `dev-build-vcam.ps1` invokes this automatically, so it only runs if someone remembers to run it by hand.
2. `--mode host` → registers the vcam and blocks (`Sleep(1000)` loop) until killed by the Tauri parent; emits `OCB_VCAM_HOST_READY` on stdout as the activation handshake `virtualcam.rs` waits for. This is the actual production path.
3. **No arguments** → falls into `VCamApp()`, an interactive console menu inherited from the Microsoft sample (`register / remove / TestVCam / TestCustomControl / quit`). See §15 for why this matters more than it sounds.

**Tauri desktop:** `src-tauri/src/main.rs` (3 lines, delegates to `lib.rs::run()`) → `lib.rs` wires up the Tauri builder with the `adb`, `virtualcam`, `nv12_preview`, and `logger` command modules. Straightforward, nothing notable.

---

## 2. Android CameraX, Camera2, constrained high-speed, and GPU bridge

This is the single strongest part of the codebase. `H264Capabilities.kt` inspects, per candidate `(width,height,fps)` mode, three independent capture engines and reports *why* each is or isn't available:

- `REGULAR_SURFACE` — validated against the regular `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` **and** `getOutputMinFrameDuration` (so a device that advertises an FPS range but can't actually sustain it at that size is correctly rejected, not just assumed) — `H264Capabilities.kt:116-136`.
- `HIGH_SPEED_SURFACE` — `REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO` + `getHighSpeedVideoFpsRangesFor(size)`, direct match on `upper == fps` (`CapturePathPolicy.directRange`).
- `HIGH_SPEED_GPU_BRIDGE` — same high-speed capability, but for when the camera's high-speed rate is a multiple of the target (e.g. 120→60) rather than an exact match (`CapturePathPolicy.bridgeRange`).

`H264Streamer.kt` then actually **uses** the high-speed path correctly: `SessionConfiguration.SESSION_HIGH_SPEED`, requests built and submitted via `CameraConstrainedHighSpeedCaptureSession.createHighSpeedRequestList()` + `setRepeatingBurst()` (`H264Streamer.kt:668-683`), never `setRepeatingRequest()` on a constrained session. This is the exact gap a prior audit pass (visible in the repo's own `AUDIT.md`) called out as missing, and it is now implemented correctly.

`HighSpeedGpuBridge.kt` is a genuine zero-CPU-copy bridge: Camera2 writes into a `SurfaceTexture`, GLES renders into the MediaCodec input `Surface` (and optionally the phone preview surface), paced by the **camera's own frame timestamps** (`texture.timestamp`), not wall-clock — so decimating 120fps→60fps drops exactly every other camera frame regardless of scheduling jitter (`HighSpeedGpuBridge.kt:188-224`). EGL/GL/Surface teardown is guarded by a `stopped` flag against double-release, and the render thread is a dedicated `HandlerThread` that `quitSafely()`s on stop.

Session setup is defensive throughout: every async camera/session callback checks the request's `generation`/`captureGeneration` against a monotonically-incrementing counter before acting, so a callback that arrives after a `stop()`/rebind is a no-op instead of touching freed state (`H264Streamer.kt:463-489`, `561-571`). Preview-surface rejection retries encoder-only instead of failing the whole session (`configureCameraSession`, `onConfigureFailed` → `mayRetry`).

**Minor note (not a bug):** `setLinearZoom()`'s smooth-zoom animation calls `refreshRequest()` every ~30ms, which rebuilds and resubmits the entire high-speed request burst each tick (`H264Streamer.kt:751-760`). Fine at the current zoom-speed settings; worth knowing if someone later makes the zoom animation faster/more granular, since each tick is a real `createHighSpeedRequestList`+`setRepeatingBurst` call, not a cheap one.

---

## 3. MJPEG and H.264 processing

**MJPEG (`MjpegStreamer.kt`):** `processFrame()` (`:327-493`) is deliberately paced — JPEG encoding happens only at the target FPS (±10%) and idles at ~2fps when `mjpegClientCount == 0`, so nobody streaming means near-zero CPU cost, not "encode every camera frame and drop it at the HTTP layer." Scratch buffers (`nv21Buffer`, `nv21RotatedBuffer`, `nv21TransformScratch`) are reused across frames and only reallocated on a resolution change — no per-frame GC pressure. Rotation/mirror happens once, on the NV21 buffer, before JPEG encode, with an explicit comment that no downstream consumer may rotate again. The whole function is wrapped in try/finally with `imageProxy.close()` in `finally`, so an exception mid-frame can't leak a camera buffer.

**H.264 (`H264Streamer.kt`):** true zero-copy — Camera2 writes directly into the MediaCodec input `Surface`; Kotlin never touches a YUV buffer on this path (except inside the GPU bridge, which stays entirely on the GPU). `onOutputBufferAvailable` correctly reassembles `BUFFER_FLAG_PARTIAL_FRAME` fragments into one complete access unit before publishing (`:354-372`) — matches the OCB2 contract that a "video access unit" record is always one complete Annex-B AU. Two specific, well-documented OEM workarounds are present: normalizing AVCC length-prefixed NALs to Annex-B once at the source (`annexB()`, `:858-874`), and deriving SPS/PPS from the first IDR when a Qualcomm encoder honors `PREPEND_HEADER_TO_SYNC_FRAMES` but never emits a `BUFFER_FLAG_CODEC_CONFIG` buffer or exposes `csd-*` (`extractAnnexBCodecConfig`, `:876-907`, called from `publishAccessUnit`).

Nothing concerning found in either path.

---

## 4. OCB2 parsing, framing, reconnect, and malformed-input handling

Reviewed both implementations: `android/.../protocol/Ocb2.kt` (writer) and `rust-frame-producer/src/ocb2.rs` (reader/`Parser`), against `protocol/SPEC.md`.

The Rust parser (`ocb2.rs:119-169`) is careful: it checks `payload_len > MAX_PAYLOAD` (16 MiB) **before** allocating anything, `record_type` is range-checked, `header_size`/`version`/`magic` are all validated, and it correctly buffers partial reads (`available.len() < HEADER_SIZE` / `< total` → `Ok(None)`, wait for more bytes) rather than assuming TCP delivers whole records. It has real unit tests, not smoke tests: fragmented single-byte reads reassembling correctly, multiple records in one read, a malformed length rejected *before* the allocation happens, and a mid-record reconnect correctly discarding the partial record via `reset()` (`ocb2.rs:203-268`).

The producer's connection loop (`main.rs:2089-2110`) treats **any** parse error as fatal to the current connection: `break 'connection` → outer loop reconnects with `parser.reset()`. `waiting_for_keyframe` is reset on every reconnect and on every `FLAG_DISCONTINUITY` record, and the MF decoder is explicitly flushed on discontinuity (`main.rs:2185-2190`) — this matches the spec's requirement that a client "flushes its decoder on a discontinuity, and waits for a keyframe before resuming decode," and it's actually implemented, not just documented.

- **Finding (Low):** `mf_decoder.rs::decode()` grows its input buffer via `access_unit.len().next_power_of_two().min(16 * 1024 * 1024)` and then does a raw `copy_nonoverlapping` of the **full** `access_unit.len()` bytes (`mf_decoder.rs:226-237`). This is only safe today because `ocb2::MAX_PAYLOAD` is also `16 * 1024 * 1024` and the parser enforces that limit upstream — but that's an implicit cross-file contract with no assertion or shared constant tying the two together. If `MAX_PAYLOAD` is ever raised in `ocb2.rs` without updating `mf_decoder.rs`, this reintroduces a buffer overflow.
  *Fix:* either `use crate::ocb2::MAX_PAYLOAD` for the clamp in `mf_decoder.rs`, or add an explicit bounds check inside `decode()` itself instead of relying on caller discipline.

The `/obs` embedded browser page (served from `ControlServer.kt`, see §11) contains a **third**, hand-written OCB2 parser in inline JavaScript, which does independently re-implement the same 16 MiB payload cap (`if(l>16777216)throw ...`) and the same "reset decoder state on stream-info/discontinuity" logic. It's consistent with the other two today, but it's a third hand-maintained copy of the same wire-format logic in a third language, with no shared conformance test across all three.

---

## 5. State transitions and concurrent commands

`StreamState.kt` uses a CAS-loop-updated immutable `PipelineSnapshot` (`updatePipelineSnapshot`, `:110-116`) rather than scattered mutable fields for the parts that must be internally consistent (lifecycle, desired config, selected/actual pipeline, fallback state) — and every "actual pipeline" / "selected pipeline" publish checks the snapshot's `generation` before applying (`publishSelectedPipeline`, `publishActualPipeline`, `:322-363`), so a slow-arriving metrics callback from a *previous* pipeline generation can't silently overwrite the current one's state. This is exactly the kind of check that's easy to skip and hard to get right; it's present and correct everywhere I looked.

`PipelineController.kt` is a single-actor command queue (one `Job` draining an `ArrayDeque` behind a plain lock, woken by a `CONFLATED` channel) with per-command-type coalescing rules: repeated `ApplySettings` patches from the same source/revision merge into one (newest-field-wins, `mergePatches`), `Stop`/`Recover`/`RuntimeH264Failure` jump the queue and cancel superseded lower-priority work. All camera start/stop/rebind/recover logic in `StreamService.kt` runs exclusively through this actor, so there is no path for two camera-lifecycle operations to run concurrently. I traced the conflated-channel wake pattern for a missed-wakeup race (item sent between "queue drained" and "actor re-subscribes") and it's the standard, correct use of a capacity-1 conflated channel as an edge-triggered signal — no race.

- **Finding (Low, doc drift):** `docs/ARCHITECTURE.md` describes the lifecycle states as "STOPPED/STARTING/STREAMING/REBINDING/STOPPING/ERROR"; the actual `LifecycleState` enum is `STOPPED, STARTING, STREAMING, STOPPING, RECONFIGURING, RECOVERING, FAILED` (`StreamState.kt:13-15`). Not a bug, just a doc that hasn't kept up with the real state names.
- **Finding (Low, dead code):** `StreamState.streaming: AtomicBoolean` is annotated `// Deprecated, use lifecycleState` (`StreamState.kt:141`) and is still written from ~6 call sites, but I confirmed by grep that it is **never read anywhere** in the codebase. It's a write-only vestigial field. Harmless, but worth deleting so a future reader doesn't waste time treating it as a real signal.

---

## 6. Phone, web, and Tauri synchronization

All three surfaces (phone-embedded web UI served by `ControlServer.kt`, the `/obs` browser-source page, and the Tauri desktop app) — plus the Rust producer itself — participate in the **same** revision/requestId optimistic-concurrency protocol:

- Every settings mutation carries `baseRevision` + `requestId`; the server rejects a stale `baseRevision` with `CONFLICT` (`StreamService.kt:538-545`) and memoizes results by `requestId` in a bounded (128-entry) LRU map (`appliedRequestResults`, `StreamService.kt:49-51`) so a client's retried POST after a dropped response doesn't double-apply.
- The Tauri desktop (`ControlPanel.tsx`) uses the identical fields (`baseRevision: authoritativeRevisionRef.current`, `requestId: crypto.randomUUID()`, `clientType: 'tauri'`) for every settings/zoom/torch call (e.g. `:676-681`, `:769-772`, `:790-793`).
- The Rust producer's `request_phone_mjpeg_fallback` (`main.rs:1763-1850`) does the same negotiation over HTTP — including handling `409` (revision conflict → retry) and `422` (rejected mode → retry against the server-provided `alternatives` list) — so an automatic H.264→MJPEG fallback initiated by the *producer* can't clobber a concurrent manual change from the *phone* or *desktop*.

Push-based sync is real, not just polling: `GET /api/state/events` is a Server-Sent-Events endpoint (`ControlServer.kt:1197-1217`) that only emits when `revision`/`generation`/`lifecycleState` actually change. The desktop's 1-second poll (`ControlPanel.tsx:403-434`) is explicitly commented as a "reconnect/version-skew fallback," not the primary sync path — and it's guarded by `isSyncingRef` so an in-flight desktop-originated change isn't clobbered by a concurrent phone-originated read. This is a coherent, deliberately-designed multi-writer protocol, consistently implemented across three languages.

No issues found here beyond what's already noted in §4 (the OCB2 parser being hand-duplicated three times, of which this SSE/REST protocol is not a part).

---

## 7. Producer lifecycle and fallback behavior

`run_h264_v2` (`main.rs:2008ff`) implements the fallback ladder documented in `protocol/SPEC.md` and actually matches it:

1. Media Foundation hardware decode is tried first (D3D11 device manager attached when the MFT is `MF_SA_D3D11_AWARE`).
2. After 3 consecutive decode errors on the hardware path, it recreates the D3D11 device/MFT (covers device-loss); after a further 3, it falls to software (`openh264`) (`main.rs:2358-2372`).
3. On the software path, if decoded FPS stays under 80% of the target for 3 consecutive ~1s measurement windows, it returns an error (`main.rs:2507-2519`) that the outer loop (`main.rs:2653-2658`) turns into a call to `request_phone_mjpeg_fallback` — i.e. it asks Android to rebind to MJPEG, exactly as documented.
4. 3 consecutive errors on the software path itself is fatal for the whole producer run (bubbles up, not silently retried forever).

Desktop-side lifecycle (`virtualcam.rs::start_virtual_camera_host`): detects and reaps a stale/dead child handle before restarting (`:230-242`), drains the host process's stdout/stderr on background threads (avoiding the classic "child blocks because nobody's reading its full pipe buffer" deadlock), and waits up to 8 seconds for an explicit `OCB_VCAM_HOST_READY` handshake line rather than assuming "the process launched" means "the vcam is active" (`:300-338`).

- **Finding (Low, resilience):** `virtualcam.rs` uses `std::sync::Mutex` + `.lock().unwrap()` at essentially every state access (dozens of call sites). If any single thread ever panics while holding one of these mutexes (`state.child`, `state.last_error`, `state.metrics`, etc.), that mutex is poisoned and **every subsequent** `.lock().unwrap()` on it panics too — there's no poison-recovery anywhere. In practice this means one unexpected panic anywhere in the vcam-management code could brick all virtual-camera controls until the user restarts the desktop app, rather than degrading gracefully. I didn't find an actual panic-triggering bug, but the blast radius of one, should it exist, is larger than it needs to be.
  *Fix:* either switch to `parking_lot::Mutex` (doesn't poison) or handle the `PoisonError` explicitly (`.unwrap_or_else(|e| e.into_inner())`) at each call site.

---

## 8. Media Foundation decoding and allocator handling

`mf_decoder.rs` reads like a direct, well-documented fix for exactly the class of crash the repo's own `AUDIT.md` flags ("Media Foundation decoder ownership/crash handling"):

- The output-sample allocation strategy branches on whether the MFT reports `MFT_OUTPUT_STREAM_PROVIDES_SAMPLES` vs `CAN_PROVIDE_SAMPLES`, with an explicit comment explaining that supplying a caller-owned `MFCreateMemoryBuffer` sample in the wrong mode "makes ownership ambiguous and caused a repeatable `IUnknown::Release` access violation" (`mf_decoder.rs:154-171`).
- `ProcessOutput` is called **directly through the raw vtable pointer** rather than through the `windows` crate's safe wrapper, with a comment explaining that the safe wrapper's `Result` conversion constructs a COM error object on the (extremely common, non-error) `NEED_MORE_INPUT` path, and that construction could dereference an invalid error-info pointer and crash (`mf_decoder.rs:271-282`). This is a specific, verifiable, and unusual piece of defensive engineering — worth calling out as a real strength, not just "looks fine."
- Drop order is deliberately controlled: `MfRuntimeGuard` (owns `CoInitializeEx`/`MFStartup` lifetime) is declared **last** in the struct and the comment explains Rust drops fields in declaration order, so COM/MF stays alive until every other field (D3D device, transform, samples) has released first (`mf_decoder.rs:42-50`).
- `copy_nv12_sample` bounds-checks the locked surface (`scan_offset`, `required`, buffer `length`) before copying, and handles both `IMF2DBuffer2`/`Lock2DSize` and the plain contiguous-buffer fallback.

No issues found in this file.

---

## 9. Shared-memory ABI, ownership, races, and bounds

This is the most carefully engineered subsystem in the codebase, and I checked it the hardest given the stakes (a bug here is a memory-safety bug across a process/language boundary).

**Write side** (`main.rs::write_nv12_frame`, `:456-522`): classic seqlock discipline — `write_epoch` is set and `committed_epoch` is zeroed *before* the payload copy; the payload is `copy_nonoverlapping`'d; a release fence runs; then `committed_epoch` is set to match `write_epoch`; only then is `published_slot` updated. The producer always writes to `(current_published + 1) % 3`, never to the slot a reader might currently be reading, and with 3 slots there's a full frame of headroom before the producer could lap a stalled reader. `validate_nv12_metadata` (`main.rs:789-816`) bounds-checks width/height/stride/total-size against `MAX_NV12_SIZE` **before** the copy happens, so a malformed decode can't overflow the fixed-size slot payload.

**Read side**, implemented independently in **three** places (C++ `SharedMemoryClient::CopyStableSlot`, and two Rust readers — the desktop's `nv12_preview.rs::read_latest`) — all three correctly apply the same double-check: read `write_epoch`/`committed_epoch` before the copy, copy into a local snapshot, copy the payload out, then **re-read** the live epochs and discard the result if they changed mid-copy (a proper seqlock reader). All three also validate `width/height/stride/payload_size/data_offset` against the mapped view size before touching memory, and reject non-2-aligned dimensions, oversized strides, etc. The C++ side additionally added GPU-accelerated (D3D11 VideoProcessor) aspect-correct letterbox resizing with an automatic CPU bilinear-letterbox fallback on device loss (`SharedMemoryClient.cpp` + `Nv12ResizeFallback.h`) — this replaces what `docs/ARCHITECTURE.md` still describes as "nearest-neighbor" scaling; the doc is stale, the actual behavior is better than documented.

- **Finding (Medium) — structural, not a live bug today:** the ring ABI (`OpenCamBridgeRingHeader` / `OpenCamBridgeSlotHeader`) is hand-typed independently in three files: `SharedMemoryClient.h` (C++), `main.rs` (Rust producer), and `nv12_preview.rs` (Rust desktop preview). The only cross-check is a manually-chosen 64-bit constant (`OCBR_ABI_HASH` / `RING_ABI_HASH`, currently `0x4f43425200030090` in all three) that every side checks for equality. If a future change adds/reorders/resizes a field in one definition and the author forgets to bump this literal in **all three** files, the mismatch will not be caught — the hash still matches, but the byte layout doesn't, and every field read after the point of divergence is garbage (potentially still "in bounds" per the size checks, just wrong). The C++ side does have `static_assert`s on the struct size and a few field offsets (`SharedMemoryClient.h:77-81`); the Rust sides only check this at `#[test]` time (`main.rs:979-1088`), which requires someone to run `cargo test` — it's not enforced by a normal `cargo build`.
  *Fix:* generate the hash from the actual field layout (e.g. hash the `(name, offset, size)` tuples in a build script) instead of a hand-typed literal, or — simpler — add a per-field `static_assert(offsetof(...))` in C++ and an equivalent `assert_eq!`/`std::mem::offset_of!` check in **both** Rust files, and treat a struct-layout change as requiring updates in three specific files, documented as such at the top of each.

---

## 10. Virtual-camera media negotiation and sample timing

`SimpleMediaStream::Initialize` (`SimpleMediaStream.cpp:126-159`) advertises exactly the formats `protocol/SPEC.md` documents, in the documented preference order: NV12 1920×1080@60, 1920×1080@30, 1280×720@60, 1280×720@30, then RGB32 1280×720@30 as the compatibility fallback for consumers that can't negotiate NV12. `RequestSample` (`:281-369`) generates monotonically-increasing synthetic sample times (accumulated frame duration, not tied 1:1 to the source capture timestamp) — this matches the spec ("Sample timestamps are monotonically increasing Media Foundation 100-nanosecond units") and avoids feeding a potentially non-monotonic or jittery source timestamp into MF's presentation clock. A ring-read failure falls back to a deterministic synthetic frame from `SimpleFrameGenerator` rather than returning a corrupt/uninitialized sample, and that fallback path is never counted as a successful frame in the diagnostics counters.

- **Finding (Low, cosmetic/misleading):** `SimpleMediaStream::Stop()` (`SimpleMediaStream.cpp:441-452`) is annotated `_Requires_lock_held_(m_Lock)` immediately above a body that itself does `winrt::slim_lock_guard lock(m_Lock);` — i.e. the SAL annotation claims the caller must already hold the lock, while the method actually acquires it itself. This isn't a live bug (no caller holds `m_Lock` when calling `Stop()` — it's a separate per-instance lock, not shared with `SimpleMediaSource`'s own lock), but the annotation is simply wrong, and it's exactly the kind of comment that could mislead a future maintainer into removing the `lock_guard` "because the annotation says it's already locked," which *would* then be a real bug (or, if `slim_mutex` isn't reentrant, a deadlock if ever called under lock).
  *Fix:* delete the stray annotation (it looks copy-pasted from `StopInternal`, which genuinely does require the lock held).

---

## 11. Previews, OBS browser path, and desktop rendering

**Phone-side local preview:** routed through `CameraPreviewContainer` (`MainActivity.kt:57-132`), a `TextureView`-backed container that recomputes fit/cover scaling and rotation/mirror on every size or config change, and cleanly releases the `Surface` on `onSurfaceTextureDestroyed`/`releasePreview()`.

**OBS browser-source path:** `ControlServer.serveObs()` (`ControlServer.kt:203ff`) serves a self-contained HTML/JS page that uses `WebCodecs` (`VideoDecoder`/`EncodedVideoChunk`) to decode the `/stream.ocb2` feed directly in the browser, with a clear failure message ("this browser lacks WebCodecs. Select MJPEG compatibility mode.") and fallback to a plain MJPEG `<img>` tag. As noted in §4, this page contains its own hand-rolled OCB2 parser matching the same framing rules (magic/version/header-size checks, 16 MiB payload cap, discontinuity → decoder flush) as the Rust and Kotlin sides.

**Desktop preview:** `Nv12RingPreview.tsx` + `nv12_preview.rs` — the Rust side is a **third** independent, correctly-implemented seqlock reader of the shared ring (see §9), packaged into a small custom `NVPR` header + raw NV12 bytes handed to the frontend. Bounds-checked the same way as the C++ reader (offsets validated against `MAPPING_SIZE` before the copy).

No functional issues found; the only recurring theme (OCB2/ring logic reimplemented per-language rather than from one schema) is already covered in §4/§9.

---

## 12. USB and LAN authentication

This is a genuinely strong design for what it is (a self-hosted, single-user tool), and I looked hard for the usual holes:

- **Bind-time TOCTOU is explicitly defended against.** `ControlServer.start()` snapshots `accessMode` into `boundLan` at bind time and the comment explains why: if the socket bound to `0.0.0.0` (LAN), the token requirement must hold even if `accessMode` is later flipped to `usbOnly` in memory, because the *bind address* can't change without a restart — otherwise "an authenticated LAN client could disable authentication for everyone while the server is still LAN-reachable" (`ControlServer.kt:71-90`). This is a real class of bug in self-hosted servers and it's correctly closed here.
- **Constant-time token compare:** `MessageDigest.isEqual` (`ControlServer.kt:182-190`), which is documented (and, since a long-fixed JDK issue, actually implemented) to run in constant time regardless of length mismatch — not a naive `==`/`.equals()`.
- **Second, independent privilege boundary:** even a LAN client with a *valid* token cannot change `accessMode`/`port`/`accessToken` — those fields are silently stripped from the patch unless the request is loopback (`serveUpdateSettings`, `ControlServer.kt:1339-1349`). This directly implements the README's claim that "Security settings themselves can only be changed from the phone or over USB," and it's a genuinely separate check from token validation, not the same guard reused.
- **`/health` is the only unauthenticated route**, matching spec exactly.
- **CORS is a tight allowlist** (`tauri.localhost`, `localhost:1420`, `127.0.0.1:1420`), not a wildcard — the comment explains this is deliberately scoped to just the desktop app's known origins.
- **Token generation:** `UUID.randomUUID().toString().replace("-", "")` (`SettingsManager.kt:19`) — backed by `SecureRandom`, ~122 bits of real entropy (a UUIDv4 has 6 fixed version/variant bits out of 128; the code comment says "Full 128-bit," which is a very minor overstatement — 122 bits is still effectively unbrute-forceable, this is a documentation nit, not a security issue).
- **USB mode:** the phone binds to `127.0.0.1` only; the desktop's `adb forward` (`adb.rs`) tunnels through the cable, so no token is needed and no LAN exposure occurs. `adb.rs` uses `std::process::Command` with argument arrays throughout (`.args([...])`), never shell-string concatenation, so there's no command-injection surface from a device serial or port value.

I found no gaps in this subsystem worth flagging as bugs. The design consistently defends against the two failure modes that actually matter for this threat model (a LAN attacker without the token, and a LAN client *with* the token trying to escalate to change security settings), and does so with independent checks at each layer rather than one guard reused everywhere.

---

## 13. Resource cleanup, leaks, deadlocks, and crash recovery

Broadly good discipline:

- `H264Streamer.releaseCaptureAttempt()` (`H264Streamer.kt:217-254`) tears down session→camera→codec→surface in dependency order, waits (bounded, 1.5s timeout) for `onClosed` signals on both the session and the camera device before proceeding, and best-effort-catches failures on each individual teardown call so one failing step doesn't abort the rest of cleanup.
- `HandlerThread`s (camera, codec, GPU-bridge render thread) are consistently `quitSafely()`'d and joined with a bounded timeout, with a fallback (move on) rather than an unbounded `.join()` that could hang shutdown forever.
- `virtualcam.rs` detects and reaps a stale/dead child process before starting a new one, rather than leaking process handles across restarts (`:230-242`).
- The shared-memory ring's `SharedMemoryClient` destructor (`~SharedMemoryClient()`) calls `SetConsumerAttached(false)` and releases all D3D11/COM resources it holds (`SharedMemoryClient.cpp:64-93`).

Two concrete gaps:

- **(Medium)** `dev-reset.ps1` sets `$ErrorActionPreference = "SilentlyContinue"` as its very first line (`dev-reset.ps1:2`), globally, for the rest of the script. `Stop-Service FrameServer -Force`/`Stop-Service FrameServerMonitor -Force` require admin; if the script isn't run elevated, those calls fail silently and the script still prints `"Reset done."` in green. The README's own Troubleshooting section already documents the manual admin-PowerShell fallback for exactly this ("If DLL copy fails: ... `Stop-Service FrameServer -Force`") — i.e. this is a known rough edge, not a hidden one, but the script itself gives no signal that it happened.
  *Fix:* scope `SilentlyContinue` to the specific commands that are expected to fail benignly (already done in a few places lower in the script via explicit `-ErrorAction SilentlyContinue`), and report per-step success/failure instead of a single blanket "Reset done."
- **(Low)** `virtualcam.rs`'s pervasive `Mutex::lock().unwrap()` pattern — see §7 finding — means one panic anywhere poisons shared state broadly rather than degrading locally.

---

## 14. CPU, memory, allocation, and latency hotspots

I looked specifically for per-frame allocation and unnecessary copies, since that's where a phone-as-webcam tool lives or dies on battery/latency:

- MJPEG path reuses NV21/rotation scratch buffers across frames (`MjpegStreamer.kt`, reallocated only on resolution change) and paces JPEG encoding to the target FPS, idling to ~2fps with zero clients — a deliberate CPU/battery optimization, not an oversight (see §3).
- H.264 path is genuinely zero-copy end-to-end: Camera2 → MediaCodec input `Surface` directly; the GPU bridge case stays entirely on GPU (SurfaceTexture/EGL), never touching a YUV buffer on the CPU either.
- The shared-memory ring is NV12 throughout the hot path; BGRA/RGB32 conversion is deferred to the one legacy-consumer-compatibility case that actually needs it (`Nv12ToRgb32` is only invoked when a consumer negotiates the RGB32 fallback type).
- GPU-accelerated resize (D3D11 VideoProcessor) with a correctness-preserving CPU bilinear fallback, rather than a cheap-but-lossy nearest-neighbor scale, on the one path that has to resize at all (source/consumer resolution mismatch).

The only latency-relevant note I'd flag (not a problem at this product's expected scale): the `/api/state/events` SSE handler polls `StreamState.toStatusDto()` and re-serializes the full status DTO every 250ms **per connected client** (`ControlServer.kt:1197-1216`), rather than being driven by an actual state-change notification. At 1-3 simultaneous clients (phone WebView + desktop app, which is this tool's actual use case) this is negligible; it would not scale to many simultaneous viewers, but that's not a goal here.

---

## 15. Duplicated / dead / legacy code

This is where I'd focus real cleanup effort, since it's the one category with concrete, low-risk, high-clarity fixes available.

**Whole unbuilt subprojects.** `VirtualCameraSample.sln` lists eight projects; `dev-build-vcam.ps1` (the project's *only* build entry point for this part of the tree) builds exactly two of them — `VirtualCameraMediaSource.vcxproj` and `VirtualCamera_Installer.vcxproj` (verified by reading the script's `msbuild` invocations, `dev-build-vcam.ps1:85-124`). The other six — `VirtualCameraManager_WinRT` (a WinRT registrar/manager component), `VirtualCameraManager_App` (a full WinUI app with its own pages/controls/Assets), `VirtualCameraSystray` (a systray app), two `.vdproj` MSI installer projects, and `VirtualCameraTest` (a full C++ data-driven test harness with its own `main.cpp` and test-data XML) — are inherited wholesale from the upstream Microsoft virtual-camera sample, sit in the tree, and are never compiled by anything this project runs. Because they're never compiled, nobody would notice if they silently stopped compiling against the modified `SharedMemoryClient.h` ABI or the current `SimpleMediaSource` implementation.

- **Nuance worth getting right:** `VirtualCameraTest.vcxproj` (the actual test-runner project) is indeed dead/unbuilt. But several *individual source files* that physically live in that same `VirtualCameraTest/` directory — `SimpleMediaSourceUT.cpp/h`, `MediaSourceUT_Common.cpp/h`, `VCamUtils.cpp/h`, `EVRHelper.cpp/h`, `MediaCaptureUtils.cpp/h`, `HWMediaSourceUT.cpp/h`, `AugmentedMediaSourceUT.cpp/h` — are pulled in via relative path (`..\VirtualCameraTest\SimpleMediaSourceUT.cpp`, etc.) and **compiled directly into the real, shipped `VirtualCamera_Installer.exe`** (verified in `VirtualCamera_Installer.vcxproj:138-159`). `SimpleMediaSourceUT` is not a test — it's the class `main.cpp`'s production `--mode host` path actually uses to register and create the virtual camera (`main.cpp:400-403`). This is a real architectural smell independent of whether anything is "dead": production functionality lives inside, and is included from, a directory named `VirtualCameraTest`, inherited unrefactored from the original sample's test project. It works, but the ownership boundary is confusing for anyone trying to figure out what's actually shipped.

**Reachable-but-pointless interactive menu (the highest-impact item in this whole audit, in my opinion).** `virtualcam.rs::register_virtual_camera_backend()` tells a real end user, verbatim: *"Please use the VirtualCamera_Installer.exe to register the camera manually for the MVP."* A user who does exactly that — runs `VirtualCamera_Installer.exe` with no arguments, which is the natural thing to do — lands in `VCamApp()` (`main.cpp:275-370`), an interactive console menu straight from the Microsoft sample: `1 - register / 2 - remove / 3 - TestVCam / 4 - TestCustomControl / 5 - quit`, and "register" itself then asks the user to choose between `VCam-SimpleMediaSource / VCam-HWMediaSource / VCam-AugmentedMediaSource` — none of which are meaningful choices for this product (only `SimpleMediaSource` does anything useful here; the other two are the dead paths from §16 below). There's no OpenCamBridge branding, no guidance, and a wrong menu choice does something (registers a different, non-functional virtual-camera kind) rather than nothing.
*Fix:* give the installer a dedicated, non-interactive `--register`/`--unregister` mode that does exactly the one thing this product needs (register as `Synthetic`/`SimpleMediaSource`), have the desktop app invoke that instead of pointing the user at the raw exe, and gate the full interactive menu behind an explicit `--dev-menu` flag (or delete it from the shipped build entirely).

**Dead code inside the code that *is* built.** `HWMediaSource.cpp` (656 lines) and `AugmentedMediaSource.cpp` (1,043 lines) compile into the shipped `VirtualCameraMediaSource.dll` but are unreachable at runtime for this product: they only activate when `ActivateObject` sees `bIsWrappingCamera == true` (i.e. `MF_VIRTUALCAMERA_ASSOCIATED_CAMERA_SOURCES` or `VCAM_DEVICE_INFO` was set on the activation attributes), and the only code that ever sets those attributes is `VirtualCameraRegistrar` inside the unbuilt `VirtualCameraManager_WinRT` project (confirmed by grep — `VirtualCamera_Installer/main.cpp` never sets `VCAM_KIND` to anything but implicitly relies on `Synthetic`). ~1,700 lines of production DLL code, unreachable, is worth pruning or at minimum clearly marking as vestigial.

**Small, self-contained items:**
- `StreamState.streaming` — write-only dead `AtomicBoolean` (§5).
- `desktop/tauri-app/test_cors.cjs` / `test_cors_all.cjs` — two manual, ad-hoc Node scripts sitting at the app's root (require a live phone at `127.0.0.1:8080` to do anything; print raw HTTP responses) that look like tests but aren't wired into any test runner — `package.json` has no `"test"` script at all (§17). Either delete them, move them into a `scripts/`/`dev-tools/` folder, or turn them into real automated tests.

---

## 16. Error handling that hides failures

I grepped broadly for swallowed exceptions across all four languages (Kotlin `catch (_: Exception) {}`, Rust `let _ = ...`, TS `catch {}`, C++ `(void)`-discarded results) and spot-checked a representative sample rather than treating a raw count as meaningful, since most hits are legitimate best-effort cleanup (e.g. `H264Streamer.releaseCaptureAttempt()`'s `try { oldSession?.stopRepeating() } catch (_: Exception) {}` — you don't want a failing `stopRepeating()` to abort the rest of teardown). Two genuine failures-actually-hidden-from-the-user cases:

1. **`MainActivity.startStreamService()`** — logcat-only, invisible to the user; see §1.
2. **`dev-reset.ps1`'s blanket `SilentlyContinue`** — see §13.

Everything else I checked in this category (Rust teardown paths, Kotlin cleanup catches, the handful of empty TS `catch {}` blocks in `ControlPanel.tsx`) is defensible best-effort cleanup, not failures being hidden from a user who needed to know about them.

One more soft item: the shared-memory build-identity hashes (`producer_build_hash`/`installed_dll_build_hash`, §18) are computed and stored correctly, but are surfaced to the user only as two truncated hex strings in a diagnostics tooltip (`ControlPanel.tsx:1224-1225`) with no automatic "these don't match, rebuild" warning — the detection mechanism exists but doesn't actually alert anyone.

---

## 17. Tests that may pass without proving real behavior

The good news: what exists is genuinely meaningful, not vacuous. `CapturePathPolicyTest.kt`, `FrameTransformPolicyTest.kt`, `Nv21TransformTest.kt`, and `ApplyPatchCoalescingPolicyTest.kt` all test real, extracted pure-policy logic with concrete assertions (not `assertTrue(true)`-style padding). On the Rust side, `ocb2.rs` and `main.rs` have real unit tests for parser framing/malformed-length rejection, ABI struct size/offset checks, NV12 resize letterbox math, and the buffer-lock fallback chain ordering (`BufferLockFallback.h`'s self-tests, also runnable live via `VirtualCamera_Installer.exe --self-test-pipeline`).

The gap is **coverage breadth**, not test quality:

- **Zero automated tests** for `StreamService.kt` (the actual state machine), `ControlServer.kt` (the HTTP/auth layer — nothing exercises the bind-time-TOCTOU defense or the loopback-only security-settings gate described in §12), `H264Streamer.kt`/`MjpegStreamer.kt` (camera/codec integration), or any part of the 1,700+/429-line `ControlPanel.tsx`/`App.tsx` desktop sync logic. All of this correctness currently rests entirely on the manual protocols in `docs/RUNTIME-CHECKLIST.md`, `docs/V1-RUNTIME-TEST-PROTOCOL.md`, and `docs/VALIDATION.md` — and `docs/VALIDATION.md` says outright that none of the current branch's changes have actually been run through that manual protocol yet.
- `VirtualCameraTest` (the C++ project with real data-driven test infrastructure, `DataDriveTestBase.cpp`) exists and looks legitimate, but per §15 it isn't part of the build — so it provides zero ongoing regression protection today, however good the individual test cases might be.
- `desktop/tauri-app` has no `"test"` npm script and no test framework installed at all (checked `package.json`).

Given how much of the hard-won correctness here lives in exactly the untested layers (auth boundary checks, the pipeline state machine, revision-conflict handling), I'd prioritize a thin layer of tests over the HTTP auth boundary (§12's guarantees are exactly the kind of thing you want a regression test for, since they're security-relevant and easy to accidentally regress in a future refactor) before anything else on this list.

---

## 18. Packaging, installation, updates, and stale-binary risks

`dev-build-vcam.ps1` is itself carefully written: it restores pinned NuGet packages only on first run (network required once, offline afterward), passes an explicit `/p:SolutionDir` so package/output paths resolve identically to a full solution build, and has documented flags for the DLL-lock problem (`-ForceKillApps` to also close Teams/Zoom/etc. holding the DLL, `-NoKill` for CI/scripted runs). This reflects real experience with the Windows driver-DLL-locking pain point, not a naive build script.

The shared-memory ring carries SHA-256 build-identity hashes of both the running producer and the running DLL specifically to detect a stale-binary mismatch (e.g., a user who rebuilt only the Rust producer via `cargo build --release`, per the README's "Build from source" section, without rerunning `dev-build-vcam.ps1` to rebuild the DLL) — see §16 for why the detection mechanism, while present and correctly computed, doesn't yet actively warn anyone.

`dev-reset.ps1` deliberately does **not** delete `framebuffer.bin` ("Keeping framebuffer.bin to avoid stale Media Foundation mappings") — that's a documented, intentional tradeoff, not an oversight, and is a reasonable one (avoids a live Media Foundation session holding a stale mapping to a file that no longer exists).

Registration (`VirtualCamera_Installer`) correctly requires admin and is kept as a separate, explicit step from the rest of the (non-admin) day-to-day dev workflow — appropriate for a Windows virtual-camera driver. The concrete gap in this whole area is the installer's no-argument behavior discussed in §15, which I'd treat as a packaging/installation issue as much as a dead-code one: it's the literal on-ramp for a new user trying to register the camera for the first time.

---

## What this audit doesn't cover

I read and reasoned about the source; I did not build or run any of it (no Android device, no Windows machine, no OBS in this environment). Everything above is a source-level finding — logic, bounds, concurrency, and design review — not a confirmation that the H.264 encoder paths, the constrained-high-speed session, or the GPU bridge actually behave as designed on real OEM hardware. `docs/VALIDATION.md`'s own checklist is the right next step for that, and per its own text, it hasn't been run yet against this branch.

---

## Suggested priority order

1. Fix the `VirtualCamera_Installer.exe` no-args UX (§15/§18) — it's the cheapest fix with the most direct new-user impact.
2. Route `MainActivity.startStreamService()`'s failure through `AppLogger`/`StreamState.lastError` (§1/§16).
3. Scope down `dev-reset.ps1`'s `$ErrorActionPreference` and report per-step success/failure (§13/§16).
4. Add the missing bounds tie-back in `mf_decoder.rs::decode()` (§4) — cheap, removes a latent overflow-on-future-change risk.
5. Add a real cross-file layout check (not just a hand-typed hash) for the shared-memory ABI (§9) before the ring header changes again.
6. Decide what to do with the six unbuilt subprojects and the two dead `*MediaSource.cpp` files (§15) — delete, or clearly mark as vendor-inherited/unused, so future contributors and future audits don't have to re-derive this.
7. Add automated tests over the HTTP auth boundary (§12/§17) specifically, before broader coverage — it's the highest-value, most regression-prone surface that currently has zero automated protection.
8. Actually run `docs/VALIDATION.md`'s checklist against physical hardware — everything above is necessary but not sufficient without that.
