# OpenCamBridge 0.1.0 — release candidate

Prepared 13 October 2026 on stable `main`, retaining the working streaming source
from `78445588732df49b46ec506d49c8d4217fdef759`. The rejected experiment remains
unchanged at `debug/preview-regression-20261013`, commit
`24928d7e03d460e6c7832f0532de33587c9b9723`. Nothing from that streaming experiment
was merged. No release has been published or uploaded to a store.

## Included

- Text-free hydra/camera logo on Android, Windows, shortcuts and MSI artwork.
  Replace `branding/logo-master.png` and run the branding generator to update
  icons and banners together. Product/contact links share `branding/product.json`.
- Signed Windows 11 x64 MSI: companion, native producer, camera DLL/host,
  app-local Microsoft CRT dependencies, WebView2 bootstrap, shortcuts and
  administrator camera registration. Pairing firewall access is restricted to
  private networks/local subnet. Fresh-install rollback actions are included.
- Signed Android release APK and Play AAB, Android 10+, target API 36,
  version 0.1.0/code 1. A new protected RSA-4096 upload key was prepared.
- Quiet About links for contact, documentation, privacy and optional Ko-fi or
  BEP-20 contributions. Contributions unlock no features. Review invitations
  disappear locally after opening Google Play or selecting Don't show again;
  the app cannot determine whether a rating was actually submitted.
- Manual/opt-in startup GitHub release checks. A blue bottom-left action appears
  only for a newer stable version and stays out of Clean feed. The action opens
  the official release page; installers are not executed automatically.
- Real Android package-version reporting, explicit protocol/minimum-version
  checks and authenticated optional pairing compatibility metadata. Legacy
  companions remain pairable, with compatibility marked unknown.
- English/French store copy, store/social graphics, privacy/guide HTML for the
  website, setup/troubleshooting, signing/build instructions and publishing gates.

## Verified locally

- Android release APK/AAB build and signing succeeded; 90 JVM tests passed.
  Release lint: 0 errors, 58 warnings. Lint is not claimed warning-free.
- APK signature verified with the intended upload certificate; six 64-bit native
  libraries passed ZIP/ELF 16 KiB alignment checks. AAB JAR signature verified;
  self-signed upload-certificate/timestamp and ZIP stream-order warnings remain.
  Play-generated split delivery and execution on a 16 KiB device are untested.
- Frontend: 86 tests and 10 OCB2 corpus cases passed; production build passed.
  Desktop Rust: 35 tests passed. Native lock/resize/first-frame/CLI self-tests passed.
- Final MSI build/signing succeeded. Read-only MSI inspection found 1,155 payload
  files, three shortcuts, camera/firewall/rollback actions, OS/architecture guards,
  required runtime binaries and CRT DLLs. Authenticode signer: Taha Laachari,
  Certum, SHA-256 with RFC3161 timestamp. Signing does not guarantee SmartScreen
  reputation. The certificate expires 13 October 2027.
- Review caught and corrected an Android transport allowlist omission that
  prevented compatibility metadata requests. A regression check covers the new
  route and rejection of unrelated paths.
- Stable Android camera, native decoder/ring/playout, WebCodecs worker and preview
  timing policy source remain unchanged. Worker output remains
  `h264Preview.worker-wPBmyQQF.js`. This is not a new performance comparison.

## Required before public deployment

1. **Actual phone screenshots are deferred at the user's request.** Google Play
   requires at least two screenshots. Marketing illustrations are not substitutes.
2. Publish the prepared privacy/guide pages, update the website/download links,
   verify the support mailbox and configure the first stable GitHub release.
   The first Play review link works after the listing becomes public.
3. Complete Play Console account/testing, app-access, camera foreground service,
   content-rating and Data Safety declarations; validate the uploaded AAB.
4. Test a clean Windows 11 x64 PC without development tools and a fresh signed
   Android installation: install, same-private-LAN pairing/video, camera consumer,
   upgrade/rollback/uninstall. This developer-machine package inspection does not
   prove that acceptance matrix. Do not erase existing debug-app data to test it.
5. Review dependency redistribution terms and H.264 patent distribution needs.
   The generated inventory covers 739 packages, including conservative build/
   platform dependencies and resolved Android artifacts. 109 entries lack
   embedded licence text and need upstream review; not all are Windows runtime
   dependencies. Android POM declarations and URLs alone are not complete legal
   clearance. Ensure required notices accompany the Play distribution as well.
6. Back up the upload PKCS12 key **and password** securely. The DPAPI password
   file is machine/account-bound and is not a portable password backup. Keep
   private signing material out of release packages and source control.

Release outputs live under ignored `release-output/0.1.0/`; see its
`release-manifest.json` and `SHA256SUMS.txt` for the exact source revision and
artifact hashes. See `publishing-checklist.md` for official publisher references.
