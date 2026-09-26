# Full-resolution desktop preview engineering pass

Baseline: `22ce4ed55b5f2d9385057f72bedba1e77f480fa0`, current `v2/streaming-engine`.
The user's brief authorizes inline implementation and final commit/push without
intermediate design approvals. Preserve working behavior, not internal structure.

## Design and decisions

Use the existing Tauri HTTP plugin's streaming response for compressed OCB2 bytes,
transfer those bytes to a dedicated worker, parse the existing shared OCB2 format,
decode Annex-B H.264 using WebCodecs, and present full-resolution VideoFrames on an
OffscreenCanvas. A small timestamp-based decoded-frame queue absorbs burst arrival;
requestAnimationFrame controls display, not network polling. Close every discarded
VideoFrame and abort transport/terminate the worker on unmount or Stop. Decode errors
reconnect for a fresh config/IDR; unavailable WebCodecs uses the existing NV12 preview.
The preview never changes phone codec settings or starts/stops the native webcam.

This keeps the existing native virtual camera and CPU fallback. A cross-process
D3D11 texture rewrite is deferred: the supplied physical evidence shows healthy
native output and does not justify its compatibility risk in this preview-focused pass.

Use one Android bitrate resolver for startup and live updates, with explicit Auto
and Manual modes in desktop/phone controls, persisted configuration and HTTP state.
Keep current automatic resolution/FPS values, bounded by encoder capability.

## Tasks and verification

- [x] Compressed preview: shared parser, worker decoder/presenter, cancellable native
  streaming, bounded decoder/frame queues, transforms, isolated recovery/fallback.
  Test discontinuity, Annex-B codec extraction, presentation cadence and frame disposal;
  run TypeScript/build. Actual WebView2 exercise deferred at the user's request.
- [x] Bitrate policy: consistent startup/live resolution, persistence/API/phone and
  desktop controls. Test auto/manual values and capability clamps; build Android.
- [x] Verify native 30/60 advertisement in code, preserve regular 60 and existing compatibility;
  correct only a reproduced queue issue. Label timings as measurements/estimates.
- [x] Run affected frontend/Rust/Android tests and release builds; record physical gaps.
- [x] Review diff against baseline and update architecture/validation notes.
- [ ] Exercise native camera and preview with available hardware: explicitly deferred
  to the next live session at the user's request, not a completed verification.

Delivery: commit and push to the requested branch after these checks. The final task
response records the resulting SHA and confirms remote state.

## Review focus

Stop/unmount during pending fetch; decoder reset during a transform/config change;
unavailable hardware codec and software fallback; 30 vs true 60 fps negotiation;
manual bitrate surviving restart and phone/desktop revision synchronization.

## Progress

- Clean baseline and exact branch verified. No Android device connected initially;
  requested a device asynchronously while implementation proceeds.
- Baseline suites: frontend 25 + browser corpus 10, producer 33, backend 14,
  legacy scheduler 23 passed in preceding audit. Legacy scheduler tests do not
  validate production queue; physical evidence supplied by user takes precedence.
- The user subsequently connected a Samsung S24, then explicitly requested finishing
  implementation before live testing/debugging. No APK was deployed or live camera
  session started. No smoothness, acceleration, GPU load or latency improvement is
  claimed as physically measured.
- Read-only code review found clean-feed fallback ownership, explicit OCB2 decoder
  color configuration, missing-animation-API error handling, and legacy saved bitrate
  normalization issues. All four were addressed before final builds.

## Final automated/build evidence (2026-09-26)

- `npm test`: 30 frontend tests plus 10 shared OCB2 corpus cases passed. Includes
  actual worker code with mocked WebCodecs and focused timestamp/frame-disposal tests;
  these are not a substitute for the real WebView2 decoder/rendering test.
- `npm run tauri build`: TypeScript/Vite and release application passed; MSI and
  NSIS Windows installers produced successfully.
- Tauri backend `cargo test`: 14 tests passed.
- Producer `cargo test --release`: 33 tests passed; `cargo build --release` passed.
- Android `./gradlew.bat testDebugUnitTest assembleDebug assembleRelease lintDebug`:
  67 unit tests passed, debug/release APK builds succeeded, lint 0 errors/39 warnings.
- `git diff --check`: passed. Remote requested branch still pointed at the rollback
  baseline immediately before delivery; no merge or force-push is needed.
