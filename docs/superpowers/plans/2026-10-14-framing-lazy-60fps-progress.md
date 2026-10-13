# Framing and lazy 60 FPS execution record

User approved direct implementation on 2026-10-14; no further planning/approval
gates. Branch: `codex/full-frame-lazy-60fps`, base `b984a26`.

Latest user instructions override the original proposed design: retain separate
preview Fit/Fill controls and virtual-camera Fit/Fill/Custom framing. Custom fits
the selected rectangle without another crop; Fill remains center-crop-to-fill.
Keep automatic rotation. Defer 4K, native-aspect capture expansion and ring ABI
changes. Preserve stable WebCodecs and rejected checkpoint `24928d7`.

Execution checklist:
- [x] Native framing regression test, fix, focused pixel/geometry verification.
- [x] Pure capture verification/cache policy tests and implementation.
- [x] Camera2 trial candidates, measured startup verification and bounded fallback.
- [x] Capability/status labels and retry/reset access using existing controls.
- [x] Test fixed 60 with/without session parameters on the connected OnePlus.
- [x] Full focused suites, Android/Windows builds, debug install preserving data.
- [x] One focused final review and regression test for its startup-recovery finding.
- [x] Commit feature branch for the authorized push; leave main unchanged.

Ruling: use the existing startup candidate loop for verification so Windows does
not connect to a provisional 60 FPS pipeline that then changes cadence unnoticed.
Healthy-session fallback updates repeating request, selected metadata and IDR;
dead-session fallback reuses the existing teardown loop. No later thermal policy.

Ruling: verification observes complete encoded access units and sensor timestamps,
not UI FPS counters. Initial slow/low-light/thermal/failed-start evidence is
inconclusive, not a permanent device blacklist. Measurement has a six-second
ceiling; camera open and session configuration each have a five-second timeout.

Ruling: execute inline with one final reviewer, per the user's efficiency request.

Validation results and manual test boundaries are recorded in
`docs/testing/2026-10-14-framing-lazy-60fps.md`.
