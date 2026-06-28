# ABANDONED EXPERIMENT — NOT CURRENT ARCHITECTURE

Current production architecture is: Android MJPEG -> rust-frame-producer -> ProgramData IPC framebuffer -> Media Foundation VirtualCameraMediaSource -> OpenCamBridge Camera -> OBS/Teams/etc.

# DirectShow Virtual Camera Experiment

We attempted to use `UnityCaptureFilter` (DirectShow) to create a standalone virtual camera without OBS.
However, modern Windows apps (like Teams V2, Discord) silently block unsigned DirectShow filters. 
To avoid driver signing issues, we migrated to the Media Foundation virtual camera approach (`windows/virtual-camera-mediafoundation`), which registers as a modern camera and works perfectly.

The legacy DirectShow source and experiment scripts have been removed to prevent mixing with production code.

