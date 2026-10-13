# Release preparation — stable foundation

Base: stable `7844558` plus the documentation-only regression report. Preserve
experimental checkpoint `24928d7`; do not import its streaming changes.

1. Use `branding/logo-master.png` as the only editable logo master. Generate
   platform icons, MSI artwork and store exports with a reproducible command.
   The mark contains no lettering; product text remains separate.
2. Add website, documentation, contact and optional support to existing About
   screens. Keep all features free; no donation prompts in capture/pairing.
3. Package native producer, DLL and host beside the Windows application, resolve
   them relative to the executable, and retain source-development fallback.
   MSI must enforce Windows 11 x64, install shortcuts, register/remove the camera,
   and scope its inbound pairing firewall exception to private local networks.
4. Prepare a new protected Android upload key, API 36 release APK/AAB and signed
   Windows binaries/MSI with the installed Certum identity. Never commit keys,
   passwords or private certificate material. Verify signatures and payloads.
5. Provide English/French store text, graphics, privacy/data-safety guidance,
   installation/troubleshooting docs, third-party notices and website corrections.
   Screenshots must depict actual app UI, not invented features or private video.
6. Run focused automated checks/builds and package inspection. Record precise
   limitations: clean-PC installation and physical LAN/device acceptance cannot
   be inferred from a successful build. Do not publish unverified claims.

No streaming, decoder, scheduler, capture-rate or automatic quality changes.

User additions during preparation: discreet Google Play review invitation with
local dismissal, explicit privacy link, opt-in GitHub release checks with a blue
available-update action, and authenticated companion compatibility information.
