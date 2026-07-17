# Media Foundation virtual camera history

OpenCamBridge originally imported Microsoft's Windows Camera `Samples/VirtualCamera` project under its MIT license. Early bring-up used the sample SimpleMediaSource, test menu, UWP manager, systray, and MSI projects.

The current production design is documented in `ARCHITECTURE.md` and `NATIVE_DEPENDENCY_MAP.md`. Production registration/host ownership now lives in `VirtualCamera_Installer/ProductionVirtualCamera.*`; the DLL activates only OpenCamBridge Synthetic/SimpleMediaSource; inherited sample projects are archived in `windows/virtual-camera-mediafoundation/upstream-samples` and are not built.

Historical synthetic-frame visibility did not validate the current NV12 ring, OCB2 decoder, OBS, or FFmpeg pipeline. Current physical results must be recorded only in `AUDIT.md`.
