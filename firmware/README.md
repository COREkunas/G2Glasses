# Firmware archive

Included: **unmodified official Halliday G2 OTA 1.1.1**, server version code 2122, captured through the account update flow on 19 October 2026.

- File: [halliday-g2-1.1.1-official-ota.zip](halliday-g2-1.1.1-official-ota.zip)
- Size: **7,204,026 bytes**
- SHA-256: `9cb9d3f093c379bea22b8433fd6f029fd794372e26e04fc40a4369519e6d2874`
- Per-entry hashes and sizes: [manifest.json](manifest.json)

The public filename removes the service-side record/version identifier. The archive bytes are unchanged. No account response, signed download URL, token, binding key or per-device dump accompanies it.

```text
python tools/verify_firmware.py
```

This checks size, archive hash, entry inventory, per-entry hashes and ZIP CRCs without executing anything. Earlier research also checked manifest digests, a detached CMS signature and APK-v2 content signing under the embedded public certificate. Those checks do not identify which trust anchors the device enforces, nor establish permission to run a modified image. The included verifier does not repeat those cryptographic signature checks.

**This is not a factory recovery image.** It lacks a complete boot/recovery/storage/calibration backup and external resources. Its `ota.sh` is device installer material, not a PC script to run. Do not execute it or treat this archive as a blank-device restore package.

The owner's installed **1.0.1** main-flash and configuration backups are **not distributed**. They contain device-specific state and are required to remain private. The public [native modules](../native/) and [offline startup patch preparer](../tools/prepare_startup_patch.py) are the custom software deliverables; they are not a complete custom firmware release.

Vendor and third-party rights remain with their respective owners. Inclusion for this owner-led research archive does not turn the firmware into open-source software or grant a new vendor license.
