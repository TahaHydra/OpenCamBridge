# Full-frame output and lazy capture verification

Status: proposed capture/4K design for user review. Full-mode letterboxing was
confirmed by the user. No capture implementation changes have been made yet.

## Goal and constraints

Preserve automatic rotation and the working WebCodecs preview architecture.
Full means the entire captured image in the desktop preview and virtual camera,
with black bars when a consumer requests a different shape. Only an explicit
crop/ratio/fill choice may discard image content. Support regular-session 60 FPS
on phones whose metadata understates their capabilities, without scanning modes
at app launch or maintaining a phone-model allowlist. Extend 4K as a coordinated
Android/Windows capability, not an Android-only selectable promise.

Keep the rejected experiment at `24928d7` untouched. Work from stable main
`c86dadd`; preserve `release-output/0.1.0` as the signed pre-change candidate.
No compressed fanout, decoder replacement, playout rewrite, automatic thermal
policy, new transport, or donation/update changes belong in this work.

## Framing contract

- Automatic pixel rotation/mirroring remains owned by the current capture/
  producer path. Orientation changes never select Fill or reset the user's crop.
- Full always contains the complete upright source. A saved preview-only Fill
  preference must not silently hide its edges. Use the output-framing control
  as the single intentional crop control; remove competing preview Fill controls.
- A selected crop is applied once. Fit that selected region into the consumer's
  requested frame, with padding if necessary, instead of center-trimming it again.
- The crop editor shows the full source plus the exact selected region. Normal
  preview/clean feed represents the selected region without changing decoding,
  network reads, worker timing or buffering. Prefer presentation geometry over
  full-frame CPU copies. Full remains the unchanged source presentation.
- Camera2 can crop a 4:3 sensor when asked for a 16:9 stream. Full cannot recover
  missing sensor pixels. Offer sensor-aspect capture sizes where the camera and
  hardware encoder support them; show actual source dimensions rather than
  mislabeling every native-aspect source as 1920×1080 or 3840×2160.
- Where supported, explicitly disable Camera2 automatic rotate-and-crop because
  the app already applies rotation. Do not change intentional camera zoom.

## Lazy regular-session 60 FPS

Advertised paths remain the default. A missing 60 FPS AE range or conservative
minimum-frame-duration value must not prevent an explicitly selected trial if
the exact output size exists and a hardware AVC surface encoder supports it.
Keep constrained high-speed capture disabled. Request fixed [60,60] in both
session parameters and repeating requests for an unadvertised trial. Android
documents only advertised ranges as valid, so rejection is a normal outcome,
not an app crash or a reason to retry continuously.

Select/measure only the mode the user actually starts. No hidden startup scan.
States: advertised, unverified, verifying, verified, rejected, inconclusive.
Capability metadata distinguishes selectable trial modes from advertised proof.
The UI labels unverified/rejected modes and reports selected versus measured FPS.

Verification runs inside the real encoder+camera session:

1. Allow one second of warmup after the first frame, then measure two seconds.
   Bound the trial to six seconds after the repeating request is submitted.
   Use monotonic elapsed time, sensor timestamps/frame-duration metadata and
   encoded video-frame presentation timestamps; never count codec-config records.
2. Require approximately target sensor cadence and at least 90% of target capture
   and encoded cadence. Missing optional sensor-duration metadata can use timestamp
   evidence. Duplicate/out-of-order timestamps cannot satisfy the verifier.
3. Treat steady 30 FPS despite a 60 request, or a deterministic unsupported request,
   as a rejected combination. No frames, encoder errors, a camera in use, interrupted
   startup, low-light exposure or current throttling may be inconclusive/transient;
   do not permanently blacklist the mode from a single poor first second.
4. Try same-size 30 FPS using a new repeating request when the session remains
   healthy. Request a new keyframe, update stream-info/selected-FPS telemetry and
   bitrate if accepted. Dynamic bitrate adjustment is optional and can fail; it
   must not make recovery fail. If the session/request is dead or rejected, use
   the existing bounded teardown/candidate fallback path.
5. Generation-check timers and callbacks so a cancelled trial cannot change a
   later camera/mode. Stop/disconnect cancels its verifier and watchdog.

Cache local results by firmware fingerprint, camera ID, size, target FPS, codec,
capture engine, and the **actual** attached preview-output configuration. A
firmware change invalidates evidence. Use bounded storage with a schema version,
retry access for rejected/inconclusive modes, and a way to clear learned results.
Do not overwrite a previously verified result just because a long session slows
down. Continued monitoring is telemetry, not an unsolicited automatic FPS policy.

MJPEG remains compatibility capture. Do not claim the H.264 surface probe proves
CameraX/ImageAnalysis 60 FPS; that path needs its own exact-output evidence and
verification if offered. Keep existing advertised MJPEG behavior in this change.

## 4K and native-aspect support

The current producer/ring is limited to 1920×1080 pixels per slot and native
camera media types stop at 1080p. Exposing 3840×2160 on Android alone is unsafe.
Extend and test producer bounds, software conversion, rotation scratch capacity,
ring capacity/ABI compatibility, consumer validation and NV12 media types together.
Retain existing lower-resolution formats. Gate 4K by actual source capabilities;
do not offer high-cost 4K RGB32/YUY2 conversion by default.

Use even dimensions of at most 4096 on either axis and at most 3840×2160 pixels,
covering landscape/portrait 4K and native-aspect modes within that pixel budget.
Keep 16 slots, each bounded to 12,441,600 NV12 payload bytes. The maximum ring is
about 190 MiB, compared with about 47.5 MiB today; retain that explicit tradeoff
rather than introducing live mapping resizing during this feature.
Reject malformed/oversized
decoded dimensions before allocation or copies. Keep ring depth/timing semantics;
document the increased 4K memory/bandwidth requirement. Do not introduce live
mapping resizing or another cross-process frame backend in this work. Publish a
new producer/DLL pair with ring ABI version 5 and application version 0.2.0, and reject
mismatched binaries instead of interpreting different slot capacities silently.
Keep the existing shared-header/slot field layout and atomic ownership protocol;
the version/hash change marks the expanded contract. Preserve the signed 0.1.0
artifacts, Android upload key and stable MSI upgrade identity.

Expose actual advertised hardware-encoder-supported sizes rather than a global
4K checkbox. A 4K camera claim, encoder capability query, successful session and
sustained encoded delivery are distinct evidence. Resolution and FPS are selected
independently subject to exact tuple verification. Lower-end phones retain their
working 720p/1080p paths and are never forced into 4K or 60 FPS.

## Validation and acceptance

Pure tests cover 59.94/60 pass, steady 30 fail, encoder slower than capture,
warmup/stalls, missing/invalid timestamps, cancelled generations, cache keys and
invalidation, retry behavior, and fixed-range candidate ordering.
Framing tests use edge-marked pixel patterns: Full preserves all four edges in
both orientations; a selected region survives mismatched consumer aspect without
an additional crop; no stretching, chroma misalignment or source overread occurs.
Native tests cover 4K/portrait capacity, overflow/stride rejection and stale ABI
rejection. Frontend tests cover trial labels and unchanged advertised modes.

Run focused suites and matching Android/native/Tauri builds. On the connected
OnePlus, test selected modes only and record capture/encode cadence, actual
fallback, rotation and preview configuration. The user judges visual smoothness.
Do not claim universal compatibility, sustained thermal stability or heat causes
from one phone or a 40-second report. Update release notes/version metadata and
keep this development build distinct from the existing signed 0.1.0 candidate.
