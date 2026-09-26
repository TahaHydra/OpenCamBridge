# Preview engine pass — September 2026

Regression baseline: `22ce4ed55b5f2d9385057f72bedba1e77f480fa0`.

## Change

The old H.264 preview polled a native shared-memory ring, bilinearly downscaled
it to a 960-pixel edge on the CPU, copied pixels through Tauri IPC, uploaded WebGL
textures and combined a 16 ms timeout with requestAnimationFrame. Its independent
sampling/queueing could skip source frames even when native output was healthy.
It also discarded source resolution before rendering. The healthy native output
was evidence against treating the capture stream as the source of that symptom.

The new primary path is compressed OCB2 -> native HTTP streaming -> worker parser
-> WebCodecs -> full-resolution VideoFrame -> OffscreenCanvas. Only compressed
bytes cross IPC. Codec configuration is derived from the actual SPS, not a fixed
baseline-profile codec string. SDR color metadata and upright mirror/rotation are
applied explicitly. Presentation is timestamp-driven at display refresh, with a
small bounded frame queue; capture/decoder callbacks never wait for rendering.

Compatibility fallback retains the old NV12 preview. Failure/reconnect is local
to the preview; it never changes the phone's codec or stops native output.
Native output scheduling and format negotiation are unchanged from the baseline.
30 FPS sources do not advertise 60 FPS; real 60 FPS sources retain those modes.

Bitrate now has Auto and Manual controls on phone/desktop. One capability-bounded
resolver is used for both initial encoder configuration and live updates. Auto:
1080p30 10 Mbps, 1080p60 16 Mbps, 720p30 6 Mbps, 720p60 9 Mbps, lower sizes 3 Mbps.
Manual supports 1–50 Mbps requests, within encoder capability. Legacy non-default
requests migrate to Manual; the old flat 4 Mbps default migrates to Auto. The
desktop also now imports the actual keyframe interval instead of resetting it to 5.

## Tradeoffs

- Preview and native output decode independently when both are enabled. This costs
  a second decoder session and compressed network stream; it eliminates raw IPC
  and isolates renderer failure. Disable preview when it is not needed.
- Worker/canvas/codec availability is runtime-dependent. Unsupported WebViews or
  unrepresentable HDR metadata use native compatibility preview. Hardware preference
  is not reported as proof of actual hardware acceleration.
- The initial preview cushion is two source intervals (capped at 70 ms); this is
  intentional smoothing, not a claim of zero presentation latency.
- CPU copies in the native Windows output and the fixed sequence queue remain.
  The user supplied healthy native measurements (~29.96 unique FPS at 30 FPS).
  Replacing that path without a reproduced problem would not establish a benefit.
- Windows virtual-camera OS support is unchanged from baseline; this change does
  not claim to add native MF virtual-camera support to every Windows 10 release.

## Validation

Build and automated check results are recorded in the implementation plan. A Samsung
S24 was detected over ADB, but the user explicitly requested completing implementation
first and deferring live testing/debugging. No APK was deployed during this pass.
Smoothness, color parity, GPU load and glass-to-glass latency have NOT been physically
validated for this implementation. Baseline physical measurements must not be presented
as measurements of this new preview.

Next live session: preview-only and preview+native at regular 1080p30 and supported
regular 60 FPS; compare against baseline; front/back, rotate/mirror, resize/clean feed,
Stop/start, disconnect/reconnect, Auto/Manual bitrate across restart, MJPEG, OBS,
Windows Camera and Discord/browser consumers. Observe displayed unique FPS, skips,
decoded queue, CPU/GPU and color/detail on a fine-line/color target over a sustained run.
