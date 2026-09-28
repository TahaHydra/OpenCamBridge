# Real-device lifecycle and output framing pass

Baseline: `0c4712f3f8a3b323bb3715747c7a020e661a689f`, branch `ui/camo-redesign`.
Scope: only the GUI worktree. User explicitly requests one continuous implementation
and physical verification pass, with final commit/push and no intermediate approvals.

## Design

Keep capture, desktop preview and native output separate owners. Native camera Start
attaches a producer to the existing stream, waits for ring readiness, then starts the
host. Native Stop does not stop Android. Phone Stop cancels network/preview work and
releases local native resources off the WebView/UI thread. A single bounded native
HTTP layer serves USB/LAN controls, with polling for state on packaged Tauri.

Keep the full upright source in preview/ring. Output framing uses a normalized crop
rectangle plus fit/fill semantics, applied once by the native compositor to the
consumer's actual output size. Fit padding is neutral black in the negotiated color
space. Existing landscape media types remain available alongside portrait modes.

Android zoom requests one IDR after settling; curtain cleanup restores exact prior
window state. Original packaged artwork is reused, not redesigned.

## Tasks

- [x] Lifecycle/network: bounded native HTTP requests, clean phone offline/reconnect,
  independent producer/host start/stop, transient WebCodecs reconnect without fallback.
- [x] Native compositor: normalized fit/fill/custom geometry, correct YUV padding,
  portrait media types, shared output settings transport without capture restart.
- [x] Framing UI: full-source crop editor, normalized drag/resize, presets, persisted
  desktop output controls independent from capture dimensions and preview-only fit.
- [x] Android: zoom-settled IDR, curtain restoration, capture-path code audit.
- [x] Quality/branding: low manual bitrate warning and original logo restoration.
- [x] Existing tests/builds, focused native geometry tests and focused S24 checks.
- [x] Final independent review and fixes to both reported lifecycle/framing issues.
- Remaining visual/consumer/LAN acceptance checks transferred to the user at their
  explicit request on September 28; see the report below. No claim of full physical
  validation or identical vendor field of view.

## Review focus

Stop during an outstanding request/start; stale callbacks after disconnect; output
crop after rotation; chroma-aligned nonempty rectangles; missing video processor
fallback; LAN tokens not leaked through logs/redirects; 30 FPS not promoted to 60.

## Initial evidence

- Exact branch/baseline verified. Cargo.toml initially marked modified but textual
  diff empty (line endings); preserve it and do not stage unrelated changes.
- GUI is a linked worktree; Git metadata lives in the common repository. No Fable
  working files will be edited; commits necessarily update Git's shared metadata.
- Start native currently invokes restartFullPipelineWithSettings; producer paths
  emit reload-preview. Generic phone fetch has no timeout and uses browser fetch.
- ADB initially has no attached devices; user asked asynchronously to reconnect S24.

## Validation ledger

See [real-device pass report](../../real-device-pass-report.md) for measured results,
architecture, limitations, and the user-run acceptance checklist. All physical results
there were collected from this GUI worktree, not copied from the baseline.
