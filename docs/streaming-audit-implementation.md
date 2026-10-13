# Streaming audit implementation — 2026-10-13

Intent: improve sustained freshness, quality, cadence and compatibility on Android phones from approximately 2021 onward, preserving H.264 surface capture, NV12, MJPEG fallback, atomic slot validation and explicit manual quality choices. Work directly on main, as requested. Implement phone code now; device validation follows later.

## Design and execution plan

1. Correct native scheduling without restoring the previously regressing adaptive scheduler. Use a fixed time target with bounded drift correction and hysteresis. Prove healthy equal-rate cadence, 30/60 conversion, 59.94, clock offsets, stalls and generation resets with deterministic long simulations. Preserve owned safety copies.
2. Make the producer own the phone connection. Fan out compressed OCB2 locally with bounded queues, cached configuration and keyframe recovery. Preview lifecycle must not block decoding or request unnecessary phone IDRs. Keep the native ring preview fallback.
3. Rank and retry Android encoder candidates in platform order with hardware/capability evidence, expand capability-derived regular modes, expose low-light and experimental high-speed choices, rate-limit IDRs, govern compressed queue age, add thermal hysteresis and eligible LAN Wi-Fi lock lifecycle. Automatic adaptation must not silently change explicit manual profiles.
4. Report observed decoder path, DXGI output, readback timing and consumer frame age/delivery. Enable D3D multithread protection for the MF-shared context. Extend compatible consumer media types. Preserve valid stride/dimension/pixel-capacity checks, including full-HD portrait software decoding.
5. Unify Low latency / Balanced / Smooth profile semantics across Android queues, native delivery and preview. Keep quality/bitrate control honest and recover compressed reference chains at a keyframe.
6. Validate builds and automated suites on all three components. Record every audit item as implemented, preserved with reason, or measurement-dependent in the final inventory. Do not present physical SSD writes, GPU acceleration, thermal stability or application interoperability as measured without hardware evidence.

## Deliberate architectural limits

GPU-only cross-process frames, removal of ownership copies, service-created Global mappings and codec changes require architectural and hardware evidence. They are evaluated against correctness and compatibility rather than implemented blindly. Advertised dimensions must fit the versioned ring ABI; unsupported sizes must be rejected consistently. Consumer-aware automatic negotiation must account for multiple camera clients before changing phone capture.

## Review focus

- Slow or hidden preview must not stall the producer or accumulate compressed data.
- Late joining preview must receive codec configuration and a valid keyframe chain.
- Manual camera profiles must survive thermal/transport policies unchanged.
- Camera/session and producer restart must reset generation-dependent state.
- Consumer clock drift must stay bounded without periodic healthy-stream FPS loss.

## Progress ledger

- Repository: pulled main to 7844558; clean baseline.
- Ruling: use independent subsystem agents under subagent-driven-development; integration and final review remain in the primary session. User explicitly requested main and implementation now, so no worktree or repeated design approval gate.
- Tasks 2/3/4 share only documented latency-profile values; coordinate integration in the primary session.
- Task 1: implemented. Shipping C++ fixed-delay selector is exercised directly by 21 one-hour deterministic scenarios, clock estimation, producer stall, paused consumer/ring overwrite, generation reset and empty-ring tests. No old adaptive-scheduler reactivation.
- Task 2: implemented. Authenticated local compressed fanout, bounded queues/cache, decoder bootstrap backpressure and independent preview recovery.
- Task 3: implemented. Platform/performance-ranked encoder alternatives and bounded successful-IDR history, exact capability-derived modes, low-light/high-speed settings, compressed queue age, thermal hysteresis/recovery and Wi-Fi lock lifecycle.
- Task 4: implemented compatible improvements and instrumentation. Portrait software/CLI validation, observed DXGI/readback metrics, MF context protection, consumer-local cadence, sample delivery age/counters, resize comparison control, 60 FPS compatibility media types and fewer backup copies. Architectural proposals adjudicated below.
- Task 5: implemented. Shared low/balanced/smooth settings and age budgets, time-based presentation, manual quality protection and transport-specific automatic bitrate targets.
- Task 6: automated validation completed as recorded below. Hardware/application/quality measurements remain for physical testing.
- Ruling: after the user's cost feedback, stopped all agent work and completed integration/verification in the primary session. No additional agents launched.

## Audit disposition

| IDs | Result |
| --- | --- |
| W01 | Fixed queue now builds its stated cushion, performs paced rate conversion, corrects sustained depth drift with hysteresis and recovers from overwrite/pause. Capture timestamps are compared only as differences. Consumer cadence is local, so a second app cannot change this stream's pacing. |
| W02, W03, W12 | Report D3D11 awareness, manager acceptance, observed DXGI/CPU samples and timed CPU readback; enable and verify MF-shared context protection. Hardware decode remains unknown without external GPU evidence. CPU ring readback remains required by this architecture. |
| W04 | Per-sample resize timing and explicit CPU/GPU comparison control under Advanced output framing. GPU-preferred behavior retains CPU fallback. No unmeasured claim that CPU or GPU is universally faster. The CPU comparison currently uses the existing bilinear implementation, not a newly claimed SIMD kernel. |
| W05 | Preserve the secured mapped file. Payload bandwidth is not physical SSD write traffic. Global pagefile sections would require a privileged service/security model; a temporary-file flag on an existing OPEN_ALWAYS file is not a reliable experiment by itself. Disk/ETW measurements remain necessary. |
| W06, W07 | Preserve owned decode/ring copies and CPU orientation transforms. Readback, rotation and write timing are observable. Direct-to-ring decode and GPU-native orientation require a new ownership/surface contract and GPU measurements. |
| W08 | Add YUY2/RGB32 60 FPS types for a 60-capable source; preserve NV12 preference. Include exact 24/25/50 FPS source types and native lower-resolution geometry. Conversion processing time is observable; low-end PC capacity and app-specific acceptance remain unverified. |
| W09 | Preserve an owned last-good copy only on a new source frame or allocator-layout change. Clear it on stream start; require matching pitch/length before restoration. Never retain a mutable ring-slot pointer. |
| W10 | Software portrait NV12 validation uses dimensions, pixel capacity, strides and plane bounds. Producer CLI also accepts portrait output and handles extreme size arithmetic safely. |
| W11 | Ring v5 exposes commit-to-sample-delivery age, delivery timestamp/count/repeats, processing/resize time, skips, depth and clock slope estimate. FrameServer/app display time is outside this measurement. |
| D01, D02, D04 | One phone H.264 subscriber. Producer fanout isolates slow preview sockets; Android coalesces genuine IDR requests. WebCodecs waits for decoder room during GOP replay instead of reconnecting on a healthy burst. |
| D03 | Keep a separate local WebCodecs decoder, with native reduced-resolution preview fallback. Removing it would require full-resolution raw IPC or shared GPU surfaces; neither is an automatic improvement. |
| D05, X02, X04 | Shared Low latency/Balanced/Smooth settings. Presentation targets 55/90/140 ms; phone and local live compressed queue age budgets 120/250/500 ms. Targets describe buffers, not guaranteed capture-to-display latency. Reference chains are recovered at genuine IDRs. Bootstrap has its own bounded grace. |
| D06, A10 | Retain immutable compressed payloads and bounded queues. Local fanout shares Arc packets. Additional parser/buffer pools need allocation/GC evidence and lifetime tests; no blanket pooling introduced. |
| D07, A11 | Keep MJPEG's latest-frame/drop behavior and compatibility path. Manual and adaptive fallback use valid camera tuples, including lower resolutions. No unmeasured JPEG rewrite. |
| A01, A09 | Hardware/performance evidence with platform-stable ties, alternate codecs per exact mode/path and fallback of optional hints. Missing performance points do not reject an encoder. Successful-IDR preferences are bounded/in-process, keyed by OS fingerprint/camera/mode/path/settings, invalidated on failure/restart and rechecked against current capabilities. |
| A02 | Enumerate Camera2 surface outputs and intersect actual frame durations/AE ceilings/encoder capabilities. Include 15/24/25/30/50/60 where proven; a containing [15,30] AE range does not fabricate exact 24/25 FPS support. |
| A03, A04 | Regular sessions remain default. High-speed is explicit/experimental with crop/control caveats and capability refresh on phone/desktop. Stable versus Low-light priority chooses advertised AE ranges; low-light can reduce actual FPS/increase blur. |
| A05, A06 | Lifecycle-managed thermal listener, dwell/hysteresis, automatic bitrate then FPS/resolution steps and gradual recovery; manual settings stay authoritative. Eligible foreground/screen-on LAN Wi-Fi lock has explicit acquire/release/status. |
| A07 | Separate automatic USB/LAN bitrate targets and thermal scaling; manual bitrate remains unchanged. These are initial policy targets, not VMAF/SSIM-optimized measurements or a proven Wi-Fi congestion controller. Quality/transport/thermal tuning needs physical scenes and telemetry. |
| A08 | Bounded profile-age queues, pre-write age/generation validation, IDR-chain recovery and disconnection after repeated lag. Kernel/socket buffers are outside this application queue. |
| X01 | Correct per-consumer cadence now. Do not automatically drive phone capture from the ring's single last-writer diagnostic tuple: it cannot represent simultaneous consumers. Optional aggregate capture negotiation requires a leased multi-consumer registry and remains unimplemented. |
| X03 | Explicit full-HD pixel/dimension/payload limits in schema, included in its fingerprint, shared generated constants and ABI v5 rejection. 4K/higher-than-1080p and HEVC are future capabilities; increasing ring memory/traffic without evidence is not a defect fix. |
| X05 | Host receive/decode/commit/delivery stages and preview decode/draw counters remain distinct from phone-clock estimates. Clock slope is estimated from long spans. External glass-to-glass measurement is still required. |

## Later physical validation

Automated evidence on this workspace:

| Check | Result |
| --- | --- |
| Android full JVM suite | 111 tests, 0 failures/errors |
| Android debug + release APKs, lint | Builds pass; lint 0 errors, 46 warnings |
| Producer unit/protocol/ring/fanout suite | 43 pass, including authenticated loopback HTTP and portrait fallback |
| Desktop backend suite | 33 pass, including ABI rejection, framing wire settings and process-generation ownership |
| Frontend service/actual worker suite | 90 pass, including 40-AU cached GOP replay with asynchronous decoder output |
| Browser OCB2 corpus | 10 pass |
| Retained Rust scheduler suite | 23 pass |
| Shipping C++ scheduler | 21 one-hour scenarios pass, plus pause/overwrite, stall, generation and empty-ring cases |
| Native DLL/host pipeline self-tests | Buffer locks, NV12 resize/settings, bootstrap and installer CLI pass |
| Production artifacts | Frontend, desktop executable, producer executable, native DLL/host and APKs built |
| Consistency | Generated ABI check, Rust formatting and git whitespace checks pass |

The final thermal review additionally corrected the advertised stream cadence:
when an automatic regular session is capped from 60 to 30 FPS, OCB2 stream info
and camera-session telemetry report the effective cadence, with discontinuity and
coalesced IDR recovery. Requested/manual settings remain intact. Decoder output
path telemetry follows the latest observed sample rather than staying latched to
an earlier DXGI output.

Initial sandbox restrictions blocked socket/path-based checks; elevated reruns
passed. Some simultaneous builds encountered transient F: device/cache errors;
the affected checks passed sequentially with a fresh Gradle daemon. These are
build-environment observations, not evidence about app performance.

Use the built Android debug APK, matching v5 producer/DLL/host and frontend. Rebuild/register the native camera using the normal development script before application testing; this session's test script builds without registration or installation.

1. USB and LAN, portrait/landscape, front/rear, screen curtain, reconnect and camera changes.
2. 30/60 FPS one-hour soak; compare consumer frame age, skips/repeats, unique FPS and decode/readback/resize times.
3. OBS/Chrome/Teams/Zoom/Discord: NV12 first, then YUY2/RGB32 and supported regional rates. Compare sample processing against the negotiated interval (16.67 ms at 60 FPS).
4. Advanced → Resize processing: compare CPU and GPU under the same framing/output/source conditions. Do not sum decoder readback time with decode time; readback is a subset.
5. 30–60 minute thermal/low-light/high-speed/Wi-Fi-lock device matrix. Verify manual requests never change and automatic recovery restores the requested tuple.
6. Record physical DiskIO/ETW evidence for framebuffer writeback under normal and memory-pressure runs. Logical payload/copy rates are not disk writes.
7. Identical captured scenes for bitrate/SSIM/VMAF and external high-frame-rate/LED timing for glass-to-glass latency.
