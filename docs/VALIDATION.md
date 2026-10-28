# Public release validation — 25 October 2026

All checks below were run on the isolated, sanitized public snapshot. No glasses or phone were accessed during release preparation.

| Check | Result |
|---|---|
| Python downloader suite | 35 tests passed using synthetic account/update responses |
| Android wire protocol | 46 checks passed |
| Storage-only marker upload protocol | 1,670 checks passed in fixtures; does not prove BLE upload on hardware |
| Console request vectors | 12 checks passed |
| Read-only FTP/backup | 99 checks passed using local loopback servers |
| RAM diagnostic records/read limits | 2,602 checks passed |
| Native app ABI/FTP | 186 checks each for synthetic temporary and persistent sessions |
| Navigation parser/state | 54 checks passed |
| Native navigation record/FTP | Passed synthetic encoding, source age, state and path checks |
| Disposable-file FTP | 489 checks passed, loopback only |
| BLE navigation handoff/transport | 29 checks passed with deterministic Android callback stubs |
| Public USB write gate | Refused before any connection |
| Android account/JSON tests | Compiled; not executed on Android in this release pass |
| APK | Built and signature verification passed |
| Corrected MINDYLAB | Rebuilt byte-for-byte, 9,004 bytes, SHA-256 `918ff848b72dc604fd191ec25e885b77bd3269b0df54bba5cf0f8d4f8184a67c` |
| Navigation snapshot | Rebuilt byte-for-byte, 13,084 bytes, SHA-256 `9511f124148a87e2d1a9ea5b878acc9c45f16f54ceef16aaded6d86bc18d02f3` |
| Generic official OTA | Exact size/hash, entry hashes and CRCs passed |
| Offline startup patch | Reproduced the verified complete patched AP hash from the matching private original; output retained outside the public tree |

The APK in this release has a new research signing certificate and the public USB gate. It was not installed or hardware-retested in this publication pass. Earlier hardware results apply to the specific original research revisions described in the story, not every feature of the current snapshot.

Checksums for distributed binaries are in `releases/SHA256SUMS.txt`. The publication inventory records only public relative paths, sizes and hashes. No test output containing local absolute paths is included.

## README image update

Four owner-approved images were visually reviewed before publication. Both MINDYLAB PNGs match their source crops pixel-for-pixel. The original four files retain their original hashes. All derivatives were encoded from fresh RGB pixels; decoded metadata and raw JPEG/PNG containers confirm that no EXIF/GPS, XMP, IPTC, comments or embedded thumbnails were carried over. The Gallery/status/navigation UI is outside the screenshot crops. No AI retouching was used. See [the media audit](images/media-audit.json).

## English video publication — 28 October 2026

The 4:50 English review was checked by decoding the complete MP4 and verifying H.264 video at 1920 × 1080 / 30 fps, stereo AAC audio and English subtitle metadata. It includes 93 timed subtitle cues; captions are burned in and the optional subtitle track is disabled by default. The published video matches the previously verified export byte-for-byte. The preview, final scene samples and GitHub link were visually checked; automated speech checks are not a human listening pass. See [video details and hashes](video/media-audit.json). No firmware or app binary changed in this publication update.
