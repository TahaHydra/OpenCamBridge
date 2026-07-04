# OpenCamBridge Licensing

The OpenCamBridge project contains code under multiple licenses:

## OpenCamBridge Original Code
All original code in this repository is licensed under the **GNU General Public License v3.0 or later** (GPL-3.0-or-later). See the `LICENSE` file in the root directory for the full text.

## Third-Party and Derived Code

### Windows Media Foundation Virtual Camera
The directory `windows/virtual-camera-mediafoundation/` contains code derived from the Microsoft Windows-Camera VirtualCamera sample.
- **License**: MIT License
- **Copyright**: Copyright (c) Microsoft Corporation. All rights reserved.
- See `windows/virtual-camera-mediafoundation/LICENSE` for the full license text.

### openh264 (H.264 decoder)
The Rust frame producer's experimental `--source h264` mode uses the
[openh264](https://github.com/cisco/openh264) codec library via the
`openh264` Rust crate, compiled **from source** at build time (no Cisco
binary is downloaded or redistributed).
- **License**: BSD-2-Clause (openh264 library and Rust bindings)
- **Copyright**: Copyright (c) Cisco Systems, Inc. (library); the Rust
  bindings are by their respective authors.
- Note: H.264 itself is covered by patents in some jurisdictions. Building
  from source means the Cisco binary patent grant does **not** apply;
  evaluate this for your own distribution needs.

### Research and Reference Material
Projects such as HandyCam, RemoteCam, and scrcpy were studied for inspiration and research purposes only. No code from these projects has been copied or incorporated into OpenCamBridge.