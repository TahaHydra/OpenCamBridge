# OpenCamBridge — getting started

OpenCamBridge is free software that uses your Android phone as a Windows webcam.
Download the matching phone and Windows companion versions from
https://xhydra.fr/products/opencambridge . Support: opencambridge@xhydra.fr.

## Requirements

- Android 10 or later, a working camera and a supported encoder. Available
  resolution/FPS varies by phone and lens; 60 FPS is not guaranteed.
- Windows 11 x64, build 22000 or newer, with Microsoft Edge WebView2 Runtime.
  Windows 10, ARM64-native Windows, Windows N without its media components,
  iOS, macOS and Linux virtual cameras are not supported by this release.
- For Wi-Fi, phone and PC must be on the same trusted private network. Guest
  Wi-Fi/client isolation, VPNs and corporate policies may block local discovery.
- Administrator approval installs the Windows camera and firewall exception.
  Normal capture runs without administrator privileges.

## Install and connect over Wi-Fi

1. Install the signed Windows MSI. It installs the companion, its native camera
   components, a Start-menu/desktop shortcut and private-network pairing access.
   Allow the WebView2 bootstrapper if the runtime is absent; that download needs
   Internet access. A machine with WebView2 already present can use local capture
   without Internet.
2. Install OpenCamBridge from Google Play (or the signed direct-download APK).
   Grant camera permission; allow the camera-service notification when requested.
3. Open the companion and choose Wi-Fi / Pair a phone. On Android, open Settings
   > Connection, scan the PC's QR code or use its short code, check the PC name,
   and approve. Stop capture first if scanning needs the same camera.
4. Choose Wi-Fi + token on the phone, then start/restart capture so that the
   network listener uses that mode. Select the saved phone on Windows and connect.
   Pairing alone never starts capture. No developer account or cloud account is
   required by the app; a Google account may be required by the Play Store.
5. In OBS, Teams or another camera application, choose **OpenCamBridge Camera**.
   Application-specific formats and portrait support vary. NV12 is preferred;
   YUY2/RGB32 are compatibility choices. Choose a supported source rate.

The desktop preview shows the source. Its Fit/Fill is a preview choice; use
Output framing to select the crop sent to camera applications. Preview pause
does not intentionally stop the virtual camera. Audio is not included.

## Updates, reviews and privacy

Settings > Updates checks official GitHub releases when requested. You can
enable a check at startup; it is off by default. A blue bottom-left button
appears when a newer stable Windows version is found and opens the official
release download page. It stays out of Clean feed. Updates never install
silently. Keep both apps on compatible releases; explicit protocol/minimum
version warnings explain when an update is needed.

About contains contact, documentation, privacy and optional support. Its Google
Play review invitation is hidden after opening the store or choosing Don't show
again. The app cannot see whether you actually submitted a rating. The Play
link becomes usable after the app listing is public.

## Optional USB

USB mode requires Google's Android platform-tools/ADB and phone USB debugging;
ADB is not bundled in this release. Install platform-tools, authorize this PC on
the phone, choose USB only and select the explicit phone in the companion. Never
approve USB debugging on a PC you do not trust. Wi-Fi mode does not require ADB.

## Troubleshooting and removal

- Verify both apps run, capture is started, Wi-Fi mode is active and the network
  profile is Private. Keep firewall rules restricted to Private/local subnet;
  do not open router ports or expose the app to the Internet.
- Re-pair if an entry was revoked. Advanced manual IP/token entry is available.
- If video is blank, try a supported 720p/30 mode or MJPEG compatibility. Close
  other apps using the phone camera. Keep the phone ventilated and stop capture
  if it becomes uncomfortable; this is not a sustained thermal-performance claim.
- For camera installation errors, repair/reinstall the MSI as administrator,
  close camera clients and check Windows camera privacy permissions. Reboot if
  Windows still has an old camera DLL loaded.
- Uninstall the companion in Windows Settings. The installer removes its camera,
  firewall exception and shortcuts. User pairings/preferences/logs are retained;
  use Forget/Revoke and clear local app data if you want them removed. Removing
  the Android app deletes its app-private settings and pairing keys.
- Share logs with support only after checking for device names, IP addresses and
  other details you do not wish to disclose. No logs are uploaded automatically.

## Optional support

All features stay free. Contributions unlock nothing and are never required.
Ko-fi: https://ko-fi.com/tahahydra

BNB Smart Chain / BEP-20 only: `0x26AD6cA6252E946835FD85Eeb3F4253A1A1666cd`.
Verify the network/address yourself before sending; transfers cannot be reversed
by OpenCamBridge. Donation links open external providers with their own policies.
