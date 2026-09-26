# Playout measurements

What the Phase 1 instrumentation actually showed, so the next person does not have to
re-derive it. Dated 2026-07-25, OnePlus 9 (LE2115), 1920x1080 at 30 fps,
`OMX.qcom.video.encoder.avc`, Media Foundation H.264 MFT with D3D11 output.

Reproduce with:

```bash
OCB_TRACE_FRAMES=1 ./target/release/rust-frame-producer.exe --source h264 --url http://127.0.0.1:8080/stream.ocb2 --source-width 1920 --source-height 1080 --source-fps 30
```

## The jitter originates on the phone, not in the transport

This is the question the OCB2 send-delta field was added to settle, and it is settled.
Holding each frame's sender-side cadence next to its arrival cadence:

| seq | `send_dus` | `arrive_dus` |
|---:|---:|---:|
| 2928 | 34786 | 34781 |
| 2929 | 36133 | 34418 |
| 2931 | 30340 | 31249 |
| 2933 | 33593 | 34048 |
| 2938 | 33568 | 34760 |
| 2945 | 32579 | 32671 |

The two track each other frame for frame. The phone's own send intervals swing between
roughly 24 ms and 53 ms around a 33 ms mean, and the transport reproduces that swing
faithfully rather than adding to it.

So the batching is upstream of the socket — MediaCodec output scheduling and the
encoder's own pacing — not USB, TCP, or adb forwarding. That also means it cannot be
fixed by tuning the transport, and confirms the receiver has to absorb it. Which is what
the playout buffer does.

Note this refines the earlier reading of the same symptom. An earlier session measured
arrivals in bursts of two or three and could not attribute them; the pairing above shows
the phone emitting the same pattern.

## Per-stage cost is not the problem

Steady-state, per frame: decode 2–6 ms, rotate 5–8 ms, ring write ~0.13 ms, total
8–12 ms against a 33 ms budget. End-to-end latency 23 ms. Nothing here is close to
starving the output; the pipeline has ample headroom and always did.

The first frame after connecting costs ~94 ms (MFT pipeline construction), which is why
the trace reports it separately rather than folding it into an average.

## Ring health

`ring_write_sequence` advances monotonically, `ring_frames_overwritten` stays 0,
`replaced_frames` 0, `transport_fps` = `decoded_fps` = 28–31. Frames are not being lost
anywhere in the chain — the complaint was always about *when* they were shown, never
about whether they arrived.

## What this run did not exercise

`playout_*` metrics all read zero here because `consumer_attached` was false: the
scheduler lives in the virtual-camera DLL, which only runs once an application opens the
camera. Exercising it on-device needs a camera consumer attached.

The scheduler itself is covered by the deterministic simulations in `playout/src/tests.rs`,
which reach conditions a short live capture cannot — an hour of clock drift at ±100 and
±200 ppm, 29.97 into 30 fps, stalls on both sides of the buffer depth, and timestamp
rewinds.

One thing to watch when a consumer does attach: `negotiated_fps_num` defaults to 60
while the source runs at 30, so until a consumer negotiates 30 the scheduler will
correctly repeat every other frame.

## Simulation findings that changed the design

Three bugs were found by the deterministic suite, not by inspection, and each one had
already survived a round of reasoning that said it was fine.

**A PI controller on buffer depth oscillates.** The mapping already integrates — a rate
offset applied to elapsed source time accumulates — so the plant is an integrator, and a
PI controller driving an integrator is third order. It hunted between 66 ms and 133 ms
around an 80 ms target and dropped a quarter of all frames at the top of every swing.

**Phase correction alone cannot track a rate error.** Replacing the PI loop with a bounded
phase nudge fixed the oscillation but not the underlying slope. Sustained +200 ppm needs
0.72 s of cumulative pullback over an hour; the anchor is a host timestamp that cannot go
below zero and only had ~95 ms of headroom. It ground down, every frame's due time landed
in the future forever, and playout stalled dead after 43 minutes with 30015 consecutive
underruns. The slope is now *measured* from `ring_write_timestamp_ns` against
`capture_timestamp_ns`, which is a direct observation rather than a control loop.

**Lateness measured against a moving anchor fights the servo.** The servo moves the anchor
to drain a deep buffer, and while draining, punctual arrivals sit "after" their due time.
That read as lateness, raised the target, pushed the anchor forward, and cancelled the
drain — the pair deadlocked with depth pinned at the full ring and the servo saturated.
Lateness is now only counted when the buffer is *below* target, since a frame cannot be
meaningfully late when there is more buffered ahead of it than was asked for.

Also worth recording: the measurement baseline must keep growing rather than restarting
each update. A short baseline makes the estimate a hostage to arrival jitter — measuring
across two clustered burst arrivals reads as a huge spurious rate error, which turned
smooth playback into 66 ms output gaps.

## Frame-rate conversion is not jitter

The single largest scheduler bug was classifying planned conversion as failure. At 30 fps
in and 60 fps out every second request must repeat, and the old code counted each one an
underrun worth +10 ms of latency, so the buffer walked to its ceiling within a second of
streaming. Conversion now produces `RepeatPlanned` and `planned_drops`, neither of which
touches the target; only `RepeatUnderrun` and genuine late arrivals do.
