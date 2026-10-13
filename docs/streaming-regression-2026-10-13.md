# Streaming experiment regression — 2026-10-13

## Decision and device observations

Continue development from stable `78445588732df49b46ec506d49c8d4217fdef759`
(`7844558`). Keep `debug/preview-regression-20261013` at
`24928d7e03d460e6c7832f0532de33587c9b9723` unchanged; do not merge it.
This report adds documentation only to stable `main`.

The user's OnePlus comparison found noticeably smoother Windows desktop preview
on stable, versus visible skipping, stuttering and faster phone heating on the
experiment. The user also recalls significant S24 heating during an earlier
frame-skipping problem; that is a separate observation, not an S24 comparison of
these revisions. Stable already contains WebCodecs preview, GUI redesign,
output framing/cropping and pairing.

These are real-device visual/thermal observations, not instrumented temperature,
power, frame-time or glass-to-glass measurements. They establish the experiment
as an unacceptable product regression on the tested OnePlus, not its root cause.
No experimental build, installation, performance test or code change was made
for this static review.

## Architectural differences and likely explanation

| Area | Stable | Experiment |
|---|---|---|
| Preview input | Direct phone OCB2 subscription; native producer needed separately for camera output | Producer owns the phone subscription and relays compressed records locally; preview alone starts native decoding too |
| Windows work | WebCodecs presentation independent of native processing | Native decode/readback, CPU orientation and ring writes share the loop feeding compressed preview; WebCodecs still decodes separately |
| Presentation | Two-frame cushion, capped at 70 ms; eight-frame queue | 55/90/140 ms profiles, clock-phase slew and 8–16-frame queues |
| Recovery | Transient preview errors reconnect; unsupported environments use ring fallback | Extra queue-age limits, cached GOP joining and fallback after repeated streaming failures |
| Android | Existing regular Camera2/MediaCodec policies | Codec ranking/fallback, broader modes, AE choices, stricter delivery recovery, IDR coalescing, thermal adaptation and eligible LAN Wi-Fi locks |

**Leading hypothesis: lost scheduling isolation.** In experimental producer
`src/main.rs:3503–3519`, `preview.publish()` runs after dequeue on the native
processing loop, before the current AU is decoded. The *next* publication must
wait for that loop's decode/readback, orientation and ring work. Its network
reader uses a four-record channel and blocking `send` (`:3091`, `:3223–3227`).
Native stalls can therefore delay/burst preview delivery and eventually stop
phone socket draining. This dependency exists even during preview-only use.
Its impact on the observed stutter remains unmeasured.

Fanout socket writes run on subscriber threads and publication uses `try_send`;
a slow preview does **not** synchronously block native decoding on socket writes.
However, publication still serializes/copies packets and acquires shared cache/
client mutexes. Bounded queues prevent unbounded memory, not timing coupling.

## Verified recovery gaps versus hypotheses

The following follow directly from code; their occurrence during the user's
comparison is unverified:

- **Late joining lacks prompt keyframe recovery.** `preview_fanout.rs` expires
  GOP replay after two seconds, clears it on stream-info/config changes, and has
  no phone-IDR request path for local subscribers. When replay is unavailable,
  a new WebCodecs subscriber gets metadata/config but discards delta pictures
  until a future IDR. With a five-second encoder interval, startup/reconnect can
  pause for seconds. The stable direct subscription requests an IDR on joining.
- **Transient failures can restore the historically slower renderer.**
  `previewTransportPolicy.js` permits ring fallback after three streaming
  failures without intervening displayed frames; unsupported APIs are no longer
  the sole trigger. The fallback is the 960px raw NV12 IPC path previously
  replaced for preview smoothness. Whether it was active during this comparison
  is unproven.
- **Metadata updates became more disruptive.** Android
  `H264Streamer.broadcastStreamChange()` drains queues, sends codec config with
  discontinuity and waits for IDR, including rotation updates. The worker resets
  its decoder on config/discontinuity. Metadata-only changes can thus interrupt
  both decoders. The worker's timeline reset on stream-info already existed in
  stable; it must not be mislabeled as a newly introduced defect.

Additional hypotheses: bursty native delivery or cached replay can make multiple
frames due at one display refresh; the existing presentation loop keeps the
latest due frame and closes earlier ones. Worker backpressure (eight queued
decodes/sixteen outstanding timestamps), 120/250/500 ms fanout age limits and
250 ms socket-write deadlines can produce catch-up or reconnect behavior under
load. Actual queue occupancy, dropped presentations and recovery counts were
not measured, so neither timing policy nor fanout alone is a proven culprit.

Android codec selection, session fallback and AE behavior changed simultaneously,
so equivalent UI settings need not establish identical effective capture paths.
High-speed capture is opt-in and defaults off; thermal adaptation requires both
Adaptive and Auto bitrate, and Wi-Fi locking excludes USB-only sessions. Do not
attribute the USB/manual-bitrate observations to those conditional features
without evidence. Repeated recovery IDRs, different encoder work or transport
backpressure are possible contributors to heat, not findings. There is no
evidence that frame skipping caused heating, or that the earlier S24 symptoms
share this experiment's cause.

## Future recovery candidates — findings only

The checkpoint is one broad WIP commit. Recover selected hunks as independent,
reviewed patches if later requested; do not cherry-pick the whole commit.

| Candidate | Isolation requirement |
|---|---|
| Full-HD portrait OpenH264 bounds/stride validation and tests | Adapt to stable ring capacity; do not import ABI v5 or change the decoder choice |
| Decoder observations distinguishing DXGI output, manager acceptance and readback cost | Diagnostics only; omit context/protection or decoder behavior changes; DXGI output does not prove hardware acceleration |
| Last-good-frame pitch/length validation and stream-start reset | Separate correctness checks from copy-frequency optimization and scheduler/telemetry changes |
| Focused protocol/buffer regression tests | Port only tests applicable to stable behavior; passing tests do not demonstrate smoother presentation |

Keep producer-owned fanout, transient-error ring fallback, presentation/drift
policy, automatic FPS/thermal policy, queue/keyframe policy, codec ranking,
expanded/high-speed capture, Wi-Fi/bitrate tuning, ABI v5 and new media types
experimental. No optimization, rewrite, decoder replacement or policy change
is authorized by this report. Preserve stable full-source preview and independent
native output framing as the development foundation.
