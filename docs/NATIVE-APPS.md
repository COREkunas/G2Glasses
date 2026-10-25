# Native modules and permanent installation

G2 has a native NuttX ELF module loader. Our demonstrated application uses that loader and the original system's display/input services. It is not an Android APK, a browser app, or a complete custom operating system.

## Included builds

| Artifact | Purpose | Status |
|---|---|---|
| `g2_native_probe/g2_probe.o` | 792-byte constructor writes `G2OK` into its own RAM | Small marker hardware experiment previously succeeded |
| `native/mindylab/mindylab.o` | 9,004-byte MINDYLAB screen, tap isolation and persistent idle worker | Exact corrected binary tested after reboot and without G2 USB |
| `native/navigation/mindylab.o` | 13,084-byte navigation/source snapshot | Experimental, not qualified as a permanent everyday navigation app |

**MINDYLAB compatibility:** installed `1.0.1.202609052141`, build `2018`, with the exact traced AP code. Version labels alone are insufficient: these modules contain absolute function addresses and ABI assumptions. Do not load them on captured 1.1.1, another G2 revision, Even G2, or older Halliday hardware.

The assembled `.c` files are frozen complete sources, avoiding dependencies on private source-generation chains. Rebuild with Python and Android NDK `30.0.14904198`'s LLVM toolchain (or set `LLVM_BIN` to the matching compiler directory):

```powershell
python native/build.py mindylab
python native/build.py navigation
python g2_native_probe/build.py
```

The first two builds were reproduced byte-for-byte during public release preparation. Different compiler versions can change the result. The public tree does not include raw ROM/AP images required by the historical machine-code emulators, so rebuilding is not equivalent to rerunning that private hardware/emulation qualification.

## How our permanent installation worked

The working route had three distinct parts:

1. **Initial access:** existing account authentication, verified backups, enabled USB and working stock ADB/NuttShell. The exact module was tested temporarily before persistence.
2. **Persistence:** place the verified additional module at `/data/ml.o`; replace an existing diagnostic echo line in the AP's embedded `/etc/init.d/rcS` with padded `insmod /data/ml.o ml`. The constructor starts a deferred idle worker; it does not immediately take over the display.
3. **Phone control:** authenticate Bluetooth, establish G2 Wi-Fi/FTP, read `/tmp/mindy-mobile.status`, and write fixed-format Start/Stop records to `/tmp/mindy-mobile.cmd`. The worker uses a per-boot nonce, increasing sequence and a checksum. These are session/format guards, not cryptographic authentication; FTP/account access supplies the external access boundary.

The diagnostic line resides in an 8 KiB startup-data sector at AP offset `0x280000` (main NOR offset `0x410000`). The first persistent experiment rewrote the original sector, then tested a boot marker. The later app hook changed 25 bytes relative to that marker image (26 bytes differ from the original stock AP). These narrow writes were verified on one unit; they did not prove arbitrary firmware acceptance.

An [offline patch preparer](../tools/prepare_startup_patch.py) can reproduce the original-AP-to-autoload transformation from a matching **private local AP backup**. It never opens a device or flashes. The patched output remains private and is not included here.

```text
python tools/prepare_startup_patch.py PRIVATE_AP_BACKUP PRIVATE_OUTPUT_OUTSIDE_REPOSITORY
```

It rejects unknown inputs and existing/public-tree outputs. A matching output only verifies the transformation; it is not an instruction to flash that file.

There is deliberately no generic one-click persistent installer in this release. The historical writer embedded the original owner's sector and safety baselines. Publishing or substituting those for someone else's device would be wrong. A new installation must establish its own exact image, geometry, backup consistency and rollback plan before any persistent write. Do not use an FTP whole-partition overwrite as a substitute.

## Runtime and rollback limits

- The tested module borrows the framebuffer and the known input callback while its custom screen is active. Ordinary tap events are consumed; other events are forwarded.
- Stop restores the prior framebuffer, viewer lock and callback. The proven sample does not cover every gesture or concurrency interleaving.
- **Do not unload a module after it has hooked input.** A paused stock dispatcher can retain its callback pointer even after the visible slot is restored. Keep it resident until a normal reboot.
- The persistent worker checks `/data/ml.off` at startup. This is a disable mechanism within a working filesystem, not a cold recovery system.
- Updating/reverting the module file while the original OS works is different from restoring an unbootable AP. An official update may overwrite the startup hook.

No dashboard app icon or public official app-install API has been demonstrated by this work. The additional module coexists with stock firmware; full source-built firmware and independent restore remain separate research milestones.
