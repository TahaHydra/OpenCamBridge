# Framing and lazy regular-session 60 FPS

Implemented on `codex/full-frame-lazy-60fps`, based on stable development revision
`b984a26`. Main and experimental checkpoint `24928d7` are unchanged. Android and
Windows development binaries retain version `0.1.0`; identify this build by the
feature branch and its commit, not the version number alone.

## Behavior

- Existing preview Fit/Fill and mirror controls remain independent of output
  framing. The crop editor temporarily shows the full source for editing.
- Output Fit preserves the complete captured image with padding when necessary.
  Fill center-crops to fill. Custom applies the chosen rectangle once and fits
  the entire selection into the consumer's requested frame. NV12 chroma alignment
  may expand the selection to neighboring pixel pairs. Rotation remains automatic.
- Supported capture sizes remain 720p/1080p. This does not add sensor-native aspect
  modes, 4K, a new ring ABI, high-speed sessions, or a new preview/transport path.
- When a user selects regular 60 FPS that camera metadata cannot prove, the
  existing candidate loop tries fixed `[60,60]` if the hardware AVC encoder and
  Camera2 output size support the request. No startup scan or device allowlist.
  The ordinary repeating-request variant is first. A steady-30 rejection can try
  session parameters once; temporary limitations skip that extra attempt.
- Verification uses a one-second warmup and two-second measurement of sensor
  timestamps, frame duration, exposure, encoded access-unit timestamps and actual
  encoder delivery. Camera and encoder cadence must reach 90% of the target;
  typical sensor duration must be within 5% of the target interval.
- Bright, steady 30 FPS is a request-configuration rejection, retryable after
  24 hours. Long exposure, thermal limits, incomplete measurements and temporary
  failures are inconclusive, retryable after a minute. A later failure does not
  erase earlier verified evidence. Advanced settings can reset learned modes.
- The bounded local cache separates firmware, camera, dimensions, FPS, encoder,
  actual preview attachment and session-parameter variant. Firmware changes
  invalidate it. Verified configurations skip repeated startup measurement.
- Healthy 30 FPS fallback keeps the session and encoder, updates the repeating
  request and selected metadata, requests an IDR, and verifies the settled cadence.
  A dead or unsettled pipeline uses the existing teardown/fallback loop. Windows
  receives the selected stream configuration after startup completes.
- The service runtime failure monitor now leaves startup errors to that loop and
  ignores queued failures from an older pipeline generation. Existing runtime
  adaptive behavior is otherwise retained.

## Automated and device checks — 2026-10-14

| Check | Result |
| --- | --- |
| Android debug unit tests | 103 tests, zero failures/errors |
| Android debug APK and lint | Built; lint: zero errors, 58 warnings |
| Frontend tests / OCB2 corpus | 87 tests / 10 corpus cases passed |
| Frontend and Windows companion | Production builds passed |
| Native producer | Release build passed |
| Native framing geometry/pixels | Passed, including Full portrait padding and no second Custom crop |
| Native buffer-lock, resize, first-frame, installer CLI self-tests | All four passed |
| Android installation | Debug APK installed with `adb install -r`; no uninstall or storage clear |

The native build helper stopped at its existing registered-path guard: COM points
to the canonical `x64/Release` DLL rather than its installer-directory copy. Both
files have identical SHA-256
`98F95066389BF709559CF35F20F03F270498BB786D4C9ECF6F3A27106A59C341`.
Compilation and self-tests passed. Fresh DLL/host/producer binaries were also
copied beside the built companion and hash-checked, avoiding older staged copies.
The signed release candidate in `release-output/0.1.0` was not replaced.

OnePlus LE2115 / Android 14, 1080p fixed 60, short encoder-only trials:

| Camera | Session parameters | Capture / encoded FPS | Verdict |
| --- | --- | --- | --- |
| Rear 0 | No | 56.88 / 56.88 | Verified |
| Rear 0 | Yes | 57.52 / 57.51 | Verified |
| Front 1 | No | 60.03 / 60.03 | Verified |
| Front 1 | Yes | 60.03 / 60.03 | Verified |

The actual `H264Streamer` also selected and verified 60 on both cameras with
encoder-only output; subsequent cached startups took 101 ms rear and 237 ms front.
These are startup measurements, not glass-to-glass latency or sustained-session
benchmarks. The separate preview-attached configuration still needs normal use.
The debug-only instrumentation can reproduce these checks; it is excluded from
release APKs and does not record images or use the network.

The WebCodecs worker, phone video subscriptions, preview presentation timing,
network queues and ring schema have no source changes in this branch.

## Manual acceptance still required

1. Use the same connection and scene as the smooth stable baseline. Test desktop
   preview and OBS output at 720p/1080p, front/rear, 30/60; check visible cadence.
2. Rotate the phone. Check output Fit keeps the captured image, Fill removes bars
   by cropping, and Custom matches the entire selected region. Change preview
   Fit/Fill and confirm it does not change OBS framing.
3. Start 60 with phone preview enabled and disabled; allow a few seconds for the
   first verification of each configuration, then stop/start to check cached use.
4. Test dim light, reconnects and mode/camera switches. Confirm a fallback reports
   its actual selection and both Windows paths recover. Reset learned modes only
   when an immediate retest is desired.

Real unsupported-device/in-session fallback, thermal soak, broad device coverage
and long-session visual smoothness are not proven by these short checks. Do not
merge into main until the user reports visual acceptance. 4K remains deferred.
