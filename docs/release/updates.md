# Windows update publishing contract

The release source is GitHub, not a separate website version endpoint. Publish
to `TahaHydra/OpenCamBridge`, tag `vMAJOR.MINOR.PATCH`, stable/non-draft. Example
public API data understood by the companion:

```json
{
  "tag_name": "v0.1.1",
  "draft": false,
  "prerelease": false
}
```

Attach `OpenCamBridge_0.1.1_x64_en-US.msi`, Android artifacts as appropriate,
`SHA256SUMS.txt` and release notes identifying compatibility requirements. The
signed MSI must retain upgrade code `E3D55F76-6AF6-4ABC-BCC7-31782DCD39C4` and app
identifier `com.tahahydra.opencambridge` across updates. Bump Tauri/Cargo/Android
version names together and Android version code monotonically. Never recycle a
published version for a different binary.

Version checks fetch only public metadata over HTTPS, use a ten-second timeout,
64 KiB response bound, no redirects and no unique identifier or phone data.
Offline/HTTP errors leave camera operation unchanged. Checks run on request, or
once per launch after the user enables startup checks. The blue bottom-left
action appears only after detecting a newer numeric stable version and opens
the official GitHub release page. Prereleases and malformed tags are ignored or
reported. No installer is downloaded or executed automatically.

This is a release notification/download flow, not the Tauri updater plugin.
There is no updater private key, arbitrary remote command, silent restart or
automatic MSI installation. Users must close camera consumers and install the
signed upgrade. Optional checks disclose the request IP to GitHub; the privacy
policy records this exception to offline/local-only camera operation.

Compatibility metadata currently declares control protocol 1, OCB2 video protocol
2, and minimum companion/phone app 0.1.0. When a future change breaks an interface,
update those requirements deliberately and test both upgrade directions. A
version difference alone must not stop otherwise compatible phones. Android's
optional PC metadata is encrypted/authenticated after the existing pairing proof;
older companions can reject the extension without breaking legacy pairing.
