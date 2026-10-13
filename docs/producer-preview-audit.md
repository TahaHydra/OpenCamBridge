# Producer and preview implementation evidence — 2026-10-13

The native H.264 producer owns the sole desktop OCB2 phone connection. The
WebCodecs preview obtains a producer endpoint through a Tauri command and
subscribes locally. Preview startup now starts or reuses the producer for both
WebCodecs and native compatibility rendering; enabling virtual-camera output
reuses a ready producer and activates the host. Preview retries never issue
phone requests or keyframe requests.

The local endpoint binds only to IPv4 loopback on an ephemeral port. Tauri
generates a fresh 256-bit token for each producer instance and supplies it by
environment variable, independently of the phone credential. Only the port is
reported on stdout. The credential command is guarded by process ownership and
instance identity. Authentication requires an exact path and a dedicated header;
query credentials and unauthenticated requests fail closed. The existing native
HTTP transport is used, so no permissive browser CORS or CSP changes are needed.

Compressed fanout uses shared immutable packets. Configuration and complete GOP
replay are captured atomically with live subscriber registration. Discontinuity,
configuration changes, geometry changes, stream restarts and cache overflow
invalidate dependent pictures. In-band SPS/PPS on a keyframe also supports late
join. The cache is bounded by 8 MiB, 240 pictures and two seconds; each of at most
four clients has a 4 MiB / 32-record queue. Live records older than the selected
profile's 120/250/500 ms budget and
socket writes exceeding 250 ms end that local subscription. A slow subscriber
is removed using nonblocking sends, and preview reconnects to the local producer
to recover a valid keyframe chain. Records larger than 4 MiB remain available to
native decoding and temporarily suspend compressed preview. No preview socket
write occurs on the producer thread.

WebCodecs submission yields to decoder dequeue/output events when its bounded
queue is full, with a two-second no-progress deadline. Cached GOP replay therefore
does not mistake a healthy bootstrap burst for decoder failure. Three repeated
decoder failures or a visible preview receiving only heartbeats without frames
fall back to the native ring preview; waiting for producer startup keeps retrying.

WebCodecs consumes cached GOPs with asynchronous backpressure: at most eight
queued decode requests and sixteen outstanding output timestamps. Dequeue and
output callbacks wake the worker, with a two-second no-progress timeout. This
yields to actual decoder tasks while retaining the existing single compressed
chunk in flight. A 40-AU cached-GOP worker integration test reproduces the prior
microtask-only reconnect loop and verifies successful bounded recovery.

Decoder observations now distinguish D3D11 awareness, device-manager acceptance,
actual IMFDXGIBuffer output counts, CPU output counts, CPU readback time, DXGI
readback time and CPU bytes copied. Readback time includes obtaining/locking the
CPU-visible buffer and copying it to owned NV12 storage; it is a subset of decode
time, not another quantity to add to total pipeline time. These cumulative
observations reset when the decoder is recreated. `d3d11_output` becomes true
only while the latest observed output sample is DXGI-backed. Hardware decoder acceleration
stays unknown for the Microsoft MFT because these observations do not prove its
internal DXVA mode. Multithread protection is enabled and checked on the MF-shared
immediate D3D11 context following [Microsoft's MF D3D11 guidance](https://learn.microsoft.com/en-us/windows/win32/medfound/supporting-direct3d-11-video-decoding-in-media-foundation).

Full-HD portrait OpenH264 output now passes the same dimension and pixel-capacity
limits as the ring. Checked buffer arithmetic, minimum/maximum plane strides and
plane bounds reject malformed output before allocation/copy. Regression tests
cover 1080×1920 padded planes and exact interleaved chroma as well as undersized
strides, excessive pixel counts and extreme dimensions.

Ring v5 consumer observations report local ring-commit-to-delivery age and
delivery/resize processing counters. They are not camera-to-display latency.
Atomic slot epochs, owned decoder buffers and the ring publication copy are
preserved. Direct decoding into a published ring slot has not been implemented:
decoder callbacks, output buffer lifetimes and concurrent overwrite/read rules
do not establish a safe ownership contract for that change. Likewise, mapped
payload byte counts or CPU copy time do not measure physical SSD writes; no
claim about physical disk traffic or hardware acceleration follows from them.

Automated validation: producer unit/protocol/ring/fanout tests (including real
authenticated loopback HTTP), Tauri tests, frontend service/worker tests, browser
OCB2 corpus and production frontend build. A release producer executable is
built for later physical testing. GPU readback values, phone sustained rates,
thermal behavior and OBS/Teams compatibility still require the user's devices.
