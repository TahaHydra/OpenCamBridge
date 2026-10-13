# Website product page replacement copy

Prepared copy for https://xhydra.fr/products/opencambridge . This file does not
publish or replace the existing website. Link downloads only after publication.

## OpenCamBridge

Your phone. Your webcam.

Turn your Android phone into a Windows webcam. Pair over your trusted local
network, preview on your PC, and choose the crop your camera applications receive.
All features are free. No watermark, ads, developer account or cloud video relay.

### Downloads

- Windows companion: signed Windows 11 x64 MSI from the
  [official GitHub releases](https://github.com/TahaHydra/OpenCamBridge/releases/latest).
- Android: [Google Play](https://play.google.com/store/apps/details?id=com.opencambridge.android)
  once the first listing is published. An optional signed APK can accompany the
  GitHub release; direct and Play signing identities may differ.
- Publish matching checksums, source archive, release notes and dependency notices.

### Connect in a few steps

1. Install the Windows companion and Android app.
2. Put both devices on the same trusted private network.
3. Open the companion's pairing invitation, scan it in Android Connection
   settings, verify the PC name and approve.
4. Select Wi-Fi + token on Android, start capture, then connect on Windows.
5. Choose **OpenCamBridge Camera** in your Windows camera application.

H.264 is the primary video path; MJPEG is available for compatibility. Supported
lenses, resolution and FPS depend on the phone. Optional USB mode requires
Android platform-tools and USB debugging. Microphone audio is not included.

### Compatibility and privacy

Requires Android 10+ and Windows 11 on an Intel/AMD x64 PC with WebView2. Windows
10, Windows ARM64 and Windows N without its media components are unsupported in
this release. Do not promise every camera application or every device mode works
without validation. This is the stable pipeline, not the rejected experiment.

Camera frames stay between the phone and PC; other applications you choose may
record/transmit them. LAN transport currently uses HTTP, so use trusted private
networks. Optional Windows release checks contact GitHub only when requested or
enabled. See the [privacy policy](https://xhydra.fr/products/opencambridge/privacy)
and [getting-started guide](https://xhydra.fr/docs/opencambridge/getting-started).

### Support the free app

Contact [opencambridge@xhydra.fr](mailto:opencambridge@xhydra.fr).
Optional [Ko-fi contributions](https://ko-fi.com/tahahydra) help development and
unlock no extra app features. Crypto contributions: BNB Smart Chain / BEP-20,
`0x26AD6cA6252E946835FD85Eeb3F4253A1A1666cd`.

Use `branding/generated/website-banner-1600x782.png` as the banner and the
text-free `play-icon-512.png` as the mark. Illustration is marketing artwork,
not an app screenshot. Keep donation controls modest beside support information.
