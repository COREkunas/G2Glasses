# Technical findings and evidence boundaries

This is a public summary of our own tests through 25 October 2026. Raw reports contain device data and are intentionally not mirrored. **Observed hardware behavior, static analysis and modeled outcomes are separate forms of evidence.**

## Firmware and platform

The downloaded official 1.1.1 package contains full replacement components, including `nuttx_ap.bin`, `nuttx_apc1.bin`, `nuttx_bth.bin`, `best1700_watch.bin`, an algorithm module and multimedia configuration. It is not a differential patch, nor a complete factory image. Embedded build information identifies the BES/BEST1700 family and NuttX; a `watch` filename does not make the DSP image unrelated to G2. The signing format is Android-style ZIP signing, but G2 itself is not Android.

The tested installed base remained **1.0.1.202609052141/build 2018**. We traced its own addresses before runtime experiments. Unrelated older Actions/ATS3089 Halliday firmware is incompatible.

## Bluetooth and Wi-Fi/FTP

The implementation is in [Protocol.java](../g2_companion/src/local/halliday/g2lab/Protocol.java), with synthetic wire-vector tests.

| Item | Recovered behavior |
|---|---|
| GATT service | `04000400-0000-1000-8000-009078563412` |
| Write characteristic | `06000600-0000-1000-8000-009278563412` |
| Notify characteristic | `05000500-0000-1000-8000-009178563412` |
| Frame | `BF` prefix, checksum, protocol/business versions, session id, command, flags and typed length/payload |
| `0x00`, `0x01`, `0x03` | Version exchange, existing binding authentication, heartbeat |
| `0x20`, `0x28`, `0x2c` | Device/firmware, wear/charging, storage queries |
| `0x3e` | Dashboard note using protobuf text fields |
| `0x60`–`0x62`, `0x65` | Wi-Fi status/start/stop and FTP service negotiation; see source for exact request types |

The companion uses the existing account binding, not a replacement pairing key. A successful BLE ACK is not automatically proof of a visible screen. Dashboard notes and native apps use different paths.

Authenticated filesystem-mode FTP gave us root-level filesystem visibility. FTP here is a file-transfer protocol, not SSH. Read-only access supported backups, while controlled writes later supported fixed RAM mailboxes and additional module files. Session credentials are obtained at runtime and are not published. FTP is not encrypted at the application layer; local Wi-Fi access and device authentication must not be confused with end-to-end encryption.

## USB activation

Our four-contact magnetic connector and modified cable provided USB data. Stock configuration initially prevented USB enumeration; after a reviewed setting change and restart, a composite USB/ADB interface worked. On the traced installed image the setting was a byte at offset 611 of `misc`; the backing write could rewrite an entire 8 KiB erase sector. That fact is **not a universal write instruction**. Per-device baselines, adequate power and full readbacks mattered. A measured pinout for other hardware revisions is not supplied.

## What the firmware writes actually proved

A controlled original-sector rewrite and small startup-script patch succeeded, followed by normal reboot. The additional module then loaded automatically. This establishes that this particular stock startup-data change was accepted. It does **not** establish that all firmware is unsigned, all checks are bypassed, rollback policy is absent, or a complete rebuilt image can boot.

Main NOR backups were read twice and compared. That 16 MiB region must not be described as every storage chip/core's complete contents. Device-specific factory/calibration, other flash/storage and recovery requirements remain part of the restore problem.

## Independent recovery remains unresolved

Raw ROM USB tests produced descriptor failures on multiple direct ports and an actual USB 2.0 hub. RAM-only tests using working stock initialization made useful progress through descriptor requests, configuration and bounded transfers. Some cycles needed physical recovery; a successful later restart does not make every path reliable.

Offline work traced endpoint/DMA timeout cleanup, controller reset and stronger clock/reset-controller (CMU) reset paths. A ROM-only digital-reset fixture can reach its modeled ready state while deliberately leaving USB disconnected and old DMA buffers quarantined. A hypothetical delayed DMA write can still alter an old buffer after superficial reset checks pass. The model exposed this gap; it did not prove the hardware does or does not do that.

Remaining requirements before claiming independent recovery:

1. Reliable cold power/clock/PHY/SPI and USB initialization without the normal AP.
2. Endpoint/DMA quiescence and safe session restart, with hardware evidence.
3. ROM transport and RAM-programmer loading/acceptance/start/exit on the actual device.
4. A complete restore inventory and verified write/readback/reboot path.
5. Exact custom-image build inputs, boot-chain acceptance and resource compatibility.

Until those are proved, a backup is valuable but is not a guaranteed way out of an unbootable image.

## Public primary-source leads

- [Halliday](https://www.hallidayglobal.com/) and [official design/update information](https://www.hallidayglobal.com/design): vendor product and update context. Our inability to find a public supported custom-app route is a dated research finding, not a claim that official updates do not exist.
- [Apache NuttX](https://github.com/apache/nuttx): upstream OS/module-loader context; not the exact G2 source tree.
- [OpenVela BEST1700 BSP artifact, pinned revision](https://github.com/open-vela/vendor_bes/blob/36465377d944a575ce8a0c7ba45259f5c6c61ea8/boards/best1700_ep/aos_evb/configs/ap/libnx_bestbsp_ap.a): compared during reset-helper research. Matching individual functions does not supply the complete Halliday firmware build.
- [OpenPineBuds HAL](https://github.com/pine64/OpenPineBuds/tree/main/platform/hal): corroborating BES concepts from a different chip, not a G2 board specification.
- [Android Wi-Fi network factory](https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/WifiNetworkFactory.java): source used for the phone's local-network workflow.
