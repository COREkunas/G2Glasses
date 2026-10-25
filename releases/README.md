# Public binaries

`G2-Display-Lab-0.18-public.apk` is built from this repository's sanitized companion source. It uses a separate research signing key; the private key is not included. It is a debug/research build, and its owner-specific USB write workflow is disabled.

The corrected native MINDYLAB and experimental navigation ELF objects live under `native/`; the generic official vendor OTA lives under `firmware/`. They are different artifact types and are not interchangeable.

`SHA256SUMS.txt` lists the public binary hashes. `PUBLIC-MANIFEST.json` at repository root lists the complete publication contents except the manifest and privacy report themselves. Build directories and runtime files are not part of the release. Read the Android and native-app guides before use.
