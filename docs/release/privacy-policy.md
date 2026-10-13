# OpenCamBridge privacy policy

Effective date: 13 October 2026. Publisher: xHydra / Taha Laachari.
Contact: opencambridge@xhydra.fr.

## Local camera, local controls

OpenCamBridge sends camera video directly from your Android phone to your
Windows PC over your local network or a USB/ADB connection. The application has
no cloud video relay, cloud recording service, developer account, advertising
SDK or analytics/telemetry upload. The developer does not receive your camera
video, pairing credentials or diagnostic logs automatically.

Video is processed in memory and the Windows local frame ring. The app does not
offer persistent video recording; an application you select as a camera consumer
may record or transmit it under that application's policies. Local mapped-frame
storage may leave operating-system-managed disk/cache remnants. Do not interpret
"no recording feature" as forensic secure deletion.

## What stays on your devices

The app stores preferences, saved-device names/network endpoints and credentials
needed to reconnect. Android protects pairing keys with its Keystore; Windows
protects saved pairing credentials with DPAPI. Camera permission is used to
capture video and scan pairing QR codes. Network permissions enable local
discovery/control/video. A foreground camera-service notification shows active
capture. The app does not capture microphone audio or read contacts or location
for this webcam workflow.

Diagnostic logs may contain device/camera names, IP addresses, settings, timing
and errors. Windows keeps local logs under `C:\ProgramData\OpenCamBridge`; app
preferences/pairings are stored locally. Logs are not uploaded automatically.
If you email logs or contact support, the information you choose to send is
received through the email provider and used to respond and troubleshoot.

## Network security boundaries

Pairing protects its credential exchange. The current video/control transport
uses HTTP on your trusted LAN, not HTTPS; it must not be described as encrypted
video or safe for hostile/public Wi-Fi. LAN control requires a token. USB-only
mode binds the phone server to loopback. The installer restricts its Windows
pairing firewall exception to private networks and the local subnet. Do not
expose listening ports on your router.

## External services you choose to open

Windows can check public GitHub release metadata on request or once at startup
if you enable that option (off by default). These HTTPS requests expose your
ordinary network IP address to GitHub; they contain no camera video, phone
details, pairing token, diagnostic log or unique app identifier. Updates are
never installed silently. This optional request is not a cloud camera service.

Website/documentation, Google Play reviews, GitHub, support email and optional
Ko-fi links open external apps/sites when selected. Those providers can
receive normal connection/account information under their own privacy policies.
They are not camera relays. Windows installation may download WebView2 from
Microsoft if needed. Platform stores and the operating system may independently
process installation, crash, security and account information.

Review dismissal is a local preference. Opening a review link is not evidence
that you submitted a rating; OpenCamBridge does not receive your rating history.
Optional donations provide no app feature or digital entitlement.

## Control, retention and deletion

Stop capture to stop sending new camera frames. Forget a phone on Windows or
revoke a PC on Android to remove/revoke the pairing. Clear app storage or remove
the Android app to delete app-private data. The Windows uninstaller preserves
user preferences, pairings and logs; delete those local files if you want to
remove them, taking care not to delete another user's files. Data you send to
another camera consumer or external provider is governed by that provider.

Contact opencambridge@xhydra.fr for privacy questions or deletion of information
you sent directly to support. Policy changes will be published with an updated
effective date. This product is a general-purpose webcam tool, not an app aimed
at children.
