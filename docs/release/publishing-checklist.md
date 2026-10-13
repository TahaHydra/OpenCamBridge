# Publisher checklist

## Android

1. Use the signed release AAB, application ID `com.opencambridge.android`, version
   `0.1.0` / code 1, target API 36, minimum API 29. Increment version code for each
   later Play upload. Enroll in Play App Signing; the new local key is the upload
   key. Play's distribution signing key may differ from the direct APK key.
2. Back up `.release-secrets/opencambridge-upload.p12` and its password in a
   protected password manager/offline backup. The DPAPI password file is bound
   to this Windows account/machine; copying it alone is not a portable backup.
   Never add these files to Git, release archives, screenshots or support logs.
   Upload certificate PEM is public; it is not the private upload key.
3. Publish the app-specific privacy page at the listing's exact URL before
   submission. The prepared policy HTML/Markdown is not evidence it is live.
4. Complete content rating, target audience, app access and foreground camera
   service declarations. Explain the continuous user-started camera stream and
   provide the Play Console demonstration video when requested. Review camera
   permissions and notification behavior on current Android versions.
5. Data Safety: no developer backend, analytics SDK, ad SDK or automatic log/video
   upload. Verify Google's current definitions for local peer-device transfers,
   user-initiated support email and external sites before submitting final answers.
   Do not mark the HTTP LAN video transport as encrypted in transit. Do not claim
   a complete privacy/legal audit or select answers solely from this draft.
6. Submit actual current screenshots and English/French listing text. The Play
   review link will work only after this package listing is published. Follow
   applicable account verification/closed-testing requirements in your Console.
7. Release-signing differs from the development APK: do not uninstall the user's
   debug app or erase pairing data just to test the signed release. Use a spare
   phone/profile for signed fresh-install acceptance, or arrange a deliberate
   migration. Direct APK and Play installs may not be mutually upgradable if their
   signing identities differ.

## Windows

Use the signed x64 MSI; verify certificate subject **Taha Laachari**, Certum chain,
SHA-256 and timestamp. Branding/publisher text is xHydra; this does not turn the
personal certificate into an organization certificate. Signing does not guarantee
immediate SmartScreen reputation or compatibility on every PC.

Before public release, test a clean Windows 11 x64 PC without this checkout,
Visual Studio, Rust, Node, ADB or pre-existing camera registration. Check standard
user launch, administrator installation, shortcuts, WebView2 bootstrap/offline
case, registration, pairing on a private network, camera consumer, upgrade,
rollback, uninstall and locked-camera reboot behavior. Do not treat package
inspection or this developer machine as that clean-PC matrix.

## Website and downloads

Publish the privacy page and reviewed guide. Replace the current website's
outdated statements that stable video is MJPEG-only and H.264 is not consumed by
the native pipeline. State Windows 11 x64 and Android 10+ accurately; remove the
source-development-only download wording only when artifacts are actually live.
Link signed MSI, Play listing and optionally the direct signed APK, with matching
SHA256SUMS, release notes, licence/notices and source revision. Verify ownership
and operation of opencambridge@xhydra.fr; source code cannot create the mailbox.

## Optional Windows update checks

Publish a stable GitHub release in `TahaHydra/OpenCamBridge` using a numeric tag
such as `v0.1.0`, then `v0.1.1`. Do not mark production tags as draft/prerelease.
Attach the signed MSI, checksum file and release notes. The companion checks
`https://api.github.com/repos/TahaHydra/OpenCamBridge/releases/latest`, validates
the version and shows a blue update action only for a newer stable version.
Startup checks are opt-in; checks time out and never block local capture.
Downloads open the official release page; this is not silent self-installation.

Compatibility is determined by explicit control/video protocol and minimum app
versions, not by different patch numbers alone. New Android builds report real
package versions instead of the legacy hardcoded `2.0.0`. Pairing can request
authenticated PC compatibility metadata; legacy peers remain compatible with
the old exchange and have unknown compatibility metadata. These checks are not
a guarantee that every future release combination will work.

No Play publication, GitHub release publication, website deployment or external
support transaction is performed by the local build scripts.

## Official publisher references (checked 2026-10-13)

- [Store graphics and screenshot requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en): the feature graphic uses 1024×500 RGB; at least two screenshots are required. Screenshots are deferred at the user's request.
- [16 KiB native page-size compatibility](https://developer.android.com/guide/practices/page-sizes): APK ZIP/ELF alignment has been checked; actual device execution and Play-generated splits still need validation.
- [Tips/contributions and Play Billing](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en): the exception depends on the entire contribution going to the creator and granting no digital benefits. Keep Ko-fi configured for voluntary contributions only; no memberships, shop goods, badges or app unlocks. Verify the account's actual configuration before publishing the link.
