# H.264 pipeline review

Review of the V2 H.264 path as it stood on `v2/streaming-engine` at `7e33baa`.

> **Status: all items below are implemented.** The review is kept as the record
> of why each change was made — the reasoning is the part worth preserving, and
> several of the findings are the kind that will look like arbitrary choices to
> whoever reads the code next. Line numbers refer to the pre-change code.
>
> Each section now ends with a note on what shipped. Two things turned out to be
> broader than the review described, and are called out where they apply:
>
> - **Item 5** had three enforcement points, not one. `H264SettingsPolicy` pinned
>   the interval, the desktop slider was disabled, *and* the desktop overwrote
>   `h264KeyframeInterval` on every settings patch. Fixing only the first two
>   would have left the value silently reset on the next edit.
> - **Item 4's** phone-side half needed a protocol decision. A bare
>   discontinuity marker cannot be a type-3 record, because the spec requires
>   one complete access unit; it is a heartbeat carrying the discontinuity flag,
>   and the producer now honours that flag on any record type. `protocol/SPEC.md`
>   documents this.

Scope of the read:

- `android/.../camera/H264Streamer.kt` — Camera2 → MediaCodec surface encode, OCB2 broadcast
- `android/.../protocol/Ocb2.kt`, `windows/.../rust-frame-producer/src/ocb2.rs` — framing
- `windows/.../rust-frame-producer/src/mf_decoder.rs` — Media Foundation decode
- `windows/.../rust-frame-producer/src/main.rs` — `run_h264_v2`, orientation, ring write

## Verdict

The design is right and the hard parts are already handled correctly. The
zero-copy capture path (Camera2 writes straight into the encoder input surface,
Kotlin never touches a YUV frame) is the correct architecture and is genuinely
implemented that way. Several things that are easy to get wrong are right here:

- **Coded vs display height.** `mf_decoder.rs` locates the UV plane at
  `pitch * coded_height`, not `pitch * height`. 1080p decodes into a 1088-row
  surface; getting this wrong is the classic green-edge/chroma-shift bug, and
  the code documents exactly why.
- **The `ProcessOutput` ABI call.** Calling the vtable directly and keeping the
  `HRESULT` as a value, rather than letting the `windows` crate build an `Error`
  on the `NEED_MORE_INPUT` path, avoids a real crash class. This is not a hack.
- **Sample-allocation ownership.** Treating `CAN_PROVIDE_SAMPLES` the same as
  `PROVIDES_SAMPLES` and letting the MFT allocate is correct with a DXGI device
  manager, and the comment explains the access violation it prevents.
- **Annex-B normalisation and in-band SPS/PPS recovery.** `annexB()` plus
  `extractAnnexBCodecConfig()` means the Qualcomm encoders that honour
  `PREPEND_HEADER_TO_SYNC_FRAMES` but never emit a config buffer still produce a
  well-formed OCB2 type-2 record.
- **Rotation without a restart.** `onDeviceOrientationChanged()` re-emits
  stream-info plus a keyframe, and `h264_decode_format_changed()` keeps the
  decoder alive across a metadata-only change. Restarting the pipeline on every
  hand tilt would have been the obvious wrong answer.
- **Generation guarding.** Both codec and camera callbacks compare identity
  against the live object before acting, which is what makes teardown safe on
  Qualcomm's late callbacks.

So: it is good enough to ship on. What follows is where it costs more than it
needs to, and where the diagnostics will mislead whoever reads them next.

---

## 1. Frame latency is reported relative to the first frame, so it reads far too low

`main.rs:2534`

```rust
let offset = *capture_clock_offset.get_or_insert(
    receive_ns as i128 - record.capture_timestamp_ns as i128,
);
let aligned_capture = record.capture_timestamp_ns as i128 + offset;
latency_ms_sum += ((decode_ns as i128 - aligned_capture).max(0) as u64) / 1_000_000;
```

The phone's `capture_timestamp_ns` is a Camera2 `SENSOR_TIMESTAMP`-derived clock
and this PC's `monotonic_ns()` is a different one, so an offset is needed. But
the offset is captured **once**, from the first frame, with `get_or_insert`.

That first frame is the worst possible sample: it is the first IDR after an HTTP
connect, behind encoder warm-up and decoder initialisation. Whatever true
latency it had is baked into `offset` and subtracted from every subsequent
frame. The `.max(0)` then clamps the negative remainder to zero.

Consequence: `latency_ms` is not glass-to-glass latency, it is *latency minus
the first frame's latency*, and it will sit near zero regardless of the real
figure. The desktop labels it "End-to-end latency". Anyone using it to judge
whether 1080p60 is viable is reading a number that cannot report a problem.

**Fix.** Use a rolling minimum offset, the way NTP does: keep the smallest
`receive_ns - capture_timestamp_ns` seen over a window (a few seconds), and
re-derive `aligned_capture` from that. The minimum is the sample with the least
queueing delay, which is the best available estimate of pure clock skew.
Re-arm the window on reconnect and on `capture_clock_offset` reset. If a
negative result survives, log it once instead of clamping silently — it means
the offset estimate is stale, which is information.

## 2. `rotate_ms_avg`, `resize_ms_avg` and `write_ms_avg` are hardcoded to zero on this path

`main.rs:2717`

```rust
"decode_ms_avg":{},"rotate_ms_avg":0,"resize_ms_avg":0,"write_ms_avg":0,"total_pipeline_ms":{},
```

Both `{}` are `decode_avg`. And because `on_frame` runs *inside* `d.decode(...)`,
the `Instant::now()` at `main.rs:2471` wraps decode **plus** `orient_nv12`
**plus** `write_nv12_frame`. So on the H.264 path:

- `decode_ms_avg` silently includes rotation and the ring copy,
- `total_pipeline_ms` is a duplicate of it,
- `rotate_ms_avg` and `write_ms_avg` are structurally zero and always will be.

The desktop's 1080p60 bottleneck panel prints "Rust Decode: N ms / IPC Write:
0 ms". The 0 is not a measurement. Worse, the one figure that *is* real is a
sum of three different costs, so it cannot point at which one to fix.

**Fix.** Time the three separately inside `on_frame` — decode is
`ProcessOutput` return to closure entry, rotate is around `orient_nv12`, write
is around `write_nv12_frame` — and emit them as the fields that already exist in
the JSON contract and the desktop `VirtualCamMetrics` interface. No schema
change needed; the fields are already there and already rendered.

## 3. `orient_nv12` is a per-pixel scalar loop with a branch in its innermost body

`main.rs:2187`

```rust
let map = |x, y, source_width, source_height, output_width| {
    let oriented_x = if mirror { output_width - 1 - x } else { x };
    match rotation {
        0 => (oriented_x, y),
        90 => (y, source_height - 1 - oriented_x),
        ...
```

`map` is invoked once per luma sample and once per chroma pair, so the `if
mirror` and the `match rotation` are re-evaluated ~2.6 million times per 1080p
frame, each followed by bounds-checked indexed reads and writes. It runs on the
same thread as the socket read and the ring write.

When it runs depends on `FrameTransformPolicy.calculate`, which for a back
camera is `sensorOrientation - deviceRotation`. With the usual
`sensorOrientation = 90`:

- phone held landscape → rotation 0 → skipped,
- phone held upright → rotation 90 → **full pass every frame**,
- **Mirror** enabled → full pass every frame regardless of rotation.

So it is not always on, but "phone propped upright" and "Mirror ticked" are both
ordinary usage, not corner cases.

**Fixes, cheapest first.**

1. **Hoist the branch.** Specialise per `(rotation, mirror)` combination — eight
   monomorphic loops, or one generic function over a const-generic/closure
   chosen outside the loop. Same output, no per-pixel dispatch.
2. **Use row copies where the geometry allows.** `rotation == 0 && mirror` is
   a per-row reverse; `rotation == 180` is a reversed row walk. Neither needs a
   coordinate mapper. Only 90/270 genuinely transpose, and those want a blocked
   (tiled) transpose for cache locality rather than a naïve scan — the current
   `input[source_y * y_stride + source_x]` walks the source column-wise, which
   is a cache miss per sample.
3. **Do it on the GPU instead.** The decoder is already handing back DXGI-backed
   samples and there is already a D3D11 device and `ID3D11VideoProcessor` in the
   consumer for resize. `VideoProcessorSetStreamRotation` plus a flip does
   rotate, mirror and NV12 output in one GPU pass. This also removes a full CPU
   copy, because today the path is: MFT surface → `scratch` → `oriented_nv12` →
   ring slot, i.e. **three** 3.1 MB copies per 1080p frame before the consumer
   even sees it.

Worth noting for (3): `copy_nv12_sample` calls `ConvertToContiguousBuffer()` on
what is often a DXGI sample, which forces a GPU→CPU readback. Doing the rotate
before that readback is strictly better than doing it after.

## 4. The whole path is one thread, so a decode stall costs the connection

`main.rs:2336` — `read()` → `parser.next()` → decode → rotate → ring write, all
in a single `'connection` loop. While a frame is being decoded, rotated and
copied, nothing is draining the socket.

Follow that through to the phone. `H264Streamer.broadcast()` (`H264Streamer.kt:516`)
does `trySend` into a bounded channel and, on failure, **removes and closes the
client**:

```kotlin
val result = client.trySend(record)
if (result.isFailure) {
    clients.remove(client)
    client.close()
}
```

Capacity is `h264ClientQueueCapacity(fps)` — about half a second of frames. So a
Windows-side stall longer than ~500 ms does not cause a hiccup, it causes a
disconnect: the producer must re-open the HTTP stream, re-`subscribe()`, and
wait for a fresh config + IDR. On a slower GPU at 1080p60 the per-frame budget
is 16.6 ms and the readback + scalar rotate + copy sits inside it, so this is a
reachable state rather than a theoretical one.

**Fixes.**

- **Producer:** split the reader from the decoder. A thread that only does
  `read()` + `parser.next()` and pushes complete records into a small bounded
  queue, and a decode thread draining it. Socket backpressure then reflects
  sustained overload, not per-frame jitter.
- **Phone:** on overflow, prefer dropping to the newest keyframe boundary and
  calling `requestKeyFrame()` over closing the client. Losing a GOP is a visible
  blip; a disconnect is a multi-hundred-millisecond outage. Keep the disconnect,
  but as the response to *repeated* overflow rather than the first one.

## 5. `KEY_I_FRAME_INTERVAL = 1` spends bitrate on recovery that is already on demand

`H264Streamer.kt:329`, with the desktop slider locked to 1 s and labelled "Fixed
at one second for bounded webcam recovery latency."

The reasoning holds for a stream whose receiver cannot ask for a keyframe. This
one can, and already does:

- `subscribe()` (`H264Streamer.kt:286`) calls `requestKeyFrame()` on every new client,
- discontinuity handling on both sides sets `waiting_for_keyframe`,
- `onDeviceOrientationChanged()` pushes stream-info **and** a keyframe,
- and any producer-side decoder reset ends in a reconnect, which is a new
  `subscribe()`, which requests an IDR.

There is no recovery path that waits for a *periodic* IDR. Meanwhile at 60 fps
with CBR, one IDR per second — each also carrying SPS/PPS because
`PREPEND_HEADER_TO_SYNC_FRAMES` is set — forces the rate controller to either
spike over the CBR target or drop IDR quality, which shows up as periodic
"breathing" in the image.

**Fix.** Raise the interval substantially (5–10 s, or effectively open-ended)
and keep relying on the explicit `requestKeyFrame()` calls. This is the single
change with the best quality-per-bit return on the whole path. If you want a
safety net, a periodic IDR every few seconds is still far cheaper than one per
second. The desktop slider should then be unlocked, or the label corrected —
right now it asserts a constraint that the code does not actually have.

## 6. The High-profile fallback also discards CBR

`H264Streamer.kt:334`

```kotlin
if (withHighProfile) {
    setInteger(MediaFormat.KEY_BITRATE_MODE, ...BITRATE_MODE_CBR)
    if (highProfileLevel != null) {
        setInteger(MediaFormat.KEY_PROFILE, ...)
        setInteger(MediaFormat.KEY_LEVEL, ...)
    }
}
```

Profile/level and bitrate mode are independent capabilities, but they are
bundled into one boolean. An encoder that rejects explicit High profile/level —
common enough that the fallback exists — also loses CBR and reverts to the
implementation-default (usually VBR), which for a live virtual camera means
bitrate that wanders.

**Fix.** Make it a three-step ladder: High + CBR → CBR only → minimal. Each step
already has the retry machinery around it; it only needs one more rung. While
there, two cheap additions the format is missing: `KEY_OPERATING_RATE` set to
the target fps (or `Short.MAX_VALUE`) to stop the encoder clocking itself down,
and `KEY_LATENCY = 1`, which several encoders honour to reduce output delay
independently of `KEY_LOW_LATENCY`.

## 7. A software-decode hiccup permanently demotes the session to MJPEG

`main.rs:2551` and `main.rs:2565`, inside the `V2Decoder::Software` arm:

```rust
let (w, h) = copy_i420_to_nv12(&yuv, scratch)?;
...
let (output_width, output_height) = orient_nv12(..., &mut oriented_nv12)?;
```

Those `?` do not propagate into `decode_result` — the match arm is an expression
inside `run_h264_v2`, so they return from **the function**. And the caller
(`main.rs:2910`) treats any `Err` from `run_h264_v2` as terminal: it asks the
phone to switch to MJPEG and rewrites the stream URL for the rest of the
process's life.

So one malformed software-decoded frame does not retry — it drops the user from
H.264 to MJPEG until they restart. The Media Foundation arm right beside it
handles exactly this case properly, counting `consecutive_decode_errors` and
recreating the decoder.

This is behind two fallbacks (software decode is only reached after three
consecutive MF failures), so it is unlikely to fire — but it is an inconsistency
with a disproportionate consequence.

**Fix.** Convert both to explicit `Err(...)` values assigned to
`decode_result`, so they flow through the existing
`consecutive_decode_errors >= 3` logic like every other decode failure.

## 8. Smaller notes

- **`MF_E_TRANSFORM_STREAM_CHANGE` does not re-validate the display size**
  (`mf_decoder.rs:328`). It refreshes `coded_width`/`coded_height` but leaves
  `self.width`/`self.height`, and `set_nv12_output_type` returns
  `coded.max(width)`, so a genuinely smaller negotiated size is masked. The
  bounds check in `copy_nv12_sample` catches it safely, but reports it as
  "NV12 surface metadata exceeds locked buffer", which is opaque. Compare the
  new type's frame size against the requested one and fail with that
  comparison in the message.
- **Skipped records bypass `recycle_payload`** (`main.rs:2438`, `main.rs:2441`).
  The `continue` for "waiting for keyframe" and "no stream info yet" skips
  `parser.recycle_payload(record.payload)` at `main.rs:2662`, so those
  allocations are dropped instead of reused. It also skips the once-per-second
  metrics block, meaning a stream stuck waiting for a keyframe emits no metrics
  at all. Both are cheap to fix — recycle before `continue`, and hoist the
  metrics tick out of the record loop.
- **`MF_NALU_LENGTH_SET` is an encoder attribute**, not a decoder input one
  (`mf_decoder.rs:158`). Setting it to 0 is harmless (`.ok()` swallows it) but
  it is not what makes the decoder accept Annex-B — `MF_MT_MPEG_SEQUENCE_HEADER`
  plus auto-detection is. Worth a comment so nobody later "fixes" the wrong
  thing.
- **SPS/PPS is sent twice** — once as a type-2 record and again in-band on every
  IDR via `PREPEND_HEADER_TO_SYNC_FRAMES`. That is deliberate and correct for
  robustness; just be aware it is a per-IDR cost when tuning item 5.

## What shipped

| # | Item | Where | Note |
|---|------|-------|------|
| 5 | GOP default 1 s → 5 s, range 1–10 | `H264SettingsPolicy`, `H264Streamer`, desktop slider + patch | Three enforcement points, not one |
| 1 | Rolling-minimum clock offset | `CaptureClock` in `main.rs` | Self-heals a timestamp-base change |
| 2 | Separate decode / rotate / write timings | `run_h264_v2` | Fields already existed in the JSON contract |
| 7 | Software arm returns `Err` values | `run_h264_v2` | No longer demotes the session to MJPEG |
| 6 | Three-rung encoder format ladder | `configureCodec` | Plus `KEY_OPERATING_RATE`, `KEY_LATENCY` |
| 3 | `orient_nv12` specialised per transform | `transform_plane` in `main.rs` | Row copies for 0/180, tiled transpose for 90/270 |
| 4 | Reader thread + in-place client recovery | `spawn_ocb2_reader`, `broadcast` | Depth-4 queue; overflow drops to a keyframe |

Item 3 took step one and two of the three options (hoist the branch, row copies
and a tiled transpose). **Step three — rotating on the GPU before the readback —
is still open**, and is now the largest remaining win on this path: it would
remove one of the three full-frame copies per frame as well as the CPU rotate.
Item 2 is what makes that measurable, since `rotate_ms_avg` is now a real number
rather than a hardcoded zero.

`orientation_reference` in the test module is the oracle for item 3: it is the
original per-pixel mapping, and
`optimised_orientation_matches_the_per_pixel_reference` asserts the specialised
paths agree with it for all eight `(rotation, mirror)` combinations across three
frame sizes, including a padded stride and a partial trailing tile. Any future
optimisation of that function should keep the reference and extend the cases
rather than replace them.
