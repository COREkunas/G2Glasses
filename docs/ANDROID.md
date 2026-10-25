# G2 Display Lab — Android

The public snapshot is **0.18-public**, based on the research companion. Android 13/API 33 or newer and Bluetooth LE are required. This is a debuggable research app, not a hardened production release. The bundled APK was built from this sanitized source with a new local debug signing key; no private signing key is distributed.

## Install and connect

Install [the APK](../releases/G2-Display-Lab-0.18-public.apk) on your own phone using Android's normal package installer, or select your phone explicitly if using ADB:

```text
adb -s PHONE_SERIAL install -r releases/G2-Display-Lab-0.18-public.apk
```

Do not use the glasses' ADB serial for Android APK installation: G2 runs NuttX, not Android. If an older app has a different signature, Android will refuse an in-place update. Removing the older app also removes its local files; copy any private backups first. This release does not automate uninstalling it.

Close the official Halliday companion, retain the existing pairing, and wake the glasses. Sign in directly to Halliday from Display Lab, select your binding, allow Bluetooth permissions, connect, and wait for diagnostics. Credentials are not intentionally persisted; restarting the app requires a new sign-in.

## Try existing-firmware functions

- **Send my note** replaces the dashboard/cheat-sheet text. Open that card on G2 to see it. This is not a native application launch.
- **Check filesystem access (read only)** starts the local Wi-Fi/FTP workflow and reads its allowed paths. Allow Nearby Wi-Fi and accept Android's connection dialog.
- **Full flash + configuration backup** reads the 16 MiB main NOR and refreshes `misc`, `bootinfo` and `factory`, twice each. A successful comparison is evidence of consistent reads, not proof of independent recovery.
- **Read RAM diagnostic over Wi-Fi / FTP** is useful only when a matching diagnostic worker has already been prepared. It does not install that worker.

The backup files stay in the app's private storage. With a USB-debuggable phone and this debug build, copy them to private PC storage using a binary-safe ADB transfer. Do not route binary dumps through text-mode PowerShell redirection. Never publish the result. Filesystem geometry or version mismatches require investigation; do not turn a failed check into a write.

VPN routing can block Android's local G2 network. Diagnose that separately; the app does not change VPN security settings. Keep the app visible for its diagnostic workflows. The navigation foreground service is designed to allow Maps/Waze to be visible instead.

## Native MINDYLAB controls

These require the **compatible native module already installed or loaded on G2**. The Android APK alone cannot create the startup hook.

1. Connect/authenticate G2, then select **Connect MINDYLAB controls**.
2. Accept G2 Wi-Fi and wait for the ready status.
3. Wear and wake the glasses, starting from the dashboard. Select **Start MINDYLAB on glasses**.
4. Make brief taps. Select Stop, or let the two-minute demo finish.

The worker restores the saved framebuffer and input ownership; it does not navigate the stock UI to a chosen page. Starting from another stock page may return to that page. See [native requirements](NATIVE-APPS.md).

## Experimental navigation

The source includes native navigation, selected-app notification filtering, full/compact layouts, direction text and timeout handling. It requires the separate compatible navigation module, not just the permanent counter demo. Enable notification access, select Maps or Waze, start native navigation, accept G2 Wi-Fi, then start a route while stationary for testing.

Only information supplied by notifications is available. This is not a map engine or direct Maps/Waze integration. Unknown maneuvers must not get a guessed arrow. Online maps may require mobile data while connected to G2 Wi-Fi. Current source expires stale source notifications after two minutes; lost transport hides guidance and exits on a shorter lease. These mechanisms do not make this a validated road-navigation product. The latest source revisions have offline validation; the documented hardware test established a waiting-screen Start/Stop path, not complete real-route accuracy.

## Public-build differences

The original USB configuration experiment depended on exact private `misc`, `bootinfo` and `factory` fingerprints, full backups and a PC receipt. Those fingerprints and fixtures are removed. The public build's USB write buttons and workflow are disabled **before a network connection**. Other research actions still do what their labels describe, including a disposable file write test and storage-only probe upload; they are not all read-only. Do not mistake the old BLE probe-upload experiment for a proved native installation route.

## Build

Install JDK (tested with Android Studio's bundled JBR), Android SDK platform 36, and Build Tools 37.0.0. Set `JAVA_HOME` and `ANDROID_HOME`; `ANDROID_BUILD_TOOLS` can select another installed build-tools version. No Gradle/network dependency resolution is used by the build script.

```powershell
python g2_companion/build.py --tests-only
python g2_companion/build.py
```

The tests use synthetic bytes and local loopback FTP servers, not devices. Android `org.json` authentication tests are compiled but require an Android runtime to execute; the host build does not claim to have run them. The generated signing key remains in ignored `g2_companion/build/` and must never be committed.
