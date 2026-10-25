# Privacy and publication boundaries

This repository is built from an explicit allowlist in a separate public directory. It is not a copy of the original research workspace or its Git history.

## Excluded

- Account email/password, access or refresh tokens, account bindings and Bluetooth bond keys.
- Real device names, serials, MAC addresses, Wi-Fi session credentials and signed download URLs.
- Per-device NOR/NAND dumps, configuration/factory/calibration partitions, backup manifests and original firmware sectors used by the live writer.
- Raw logs, packet captures, phone application data, research sessions, screenshots, private photographs, personal route/meeting/audio data.
- APK/private signing keys, local SDK/tool installations, absolute private workspace paths and old per-device test runners.

The USB write workflow's private hash constants are replaced with explicit unavailable placeholders and its public execution path refuses before network access. Synthetic protocol fixtures are generated from declared test values. Public-key material and shared client protocol constants in source are not the owner's credentials. Vendor firmware necessarily contains vendor build strings and its public signing certificate; they are distinct from owner data.

## Runtime behavior

The downloader and companion still contact Halliday for account/update information. Current firmware remains stock-based and may run its own network services. This project therefore does not claim complete cloud independence or full privacy yet.

The navigation relay filters the selected Maps/Waze package and keeps route text in process memory for local transfer. Android notification-access permission is broader at the OS level than this application filter. Review source and permission scope yourself. Diagnostics and backups can create sensitive local files even when the application omits credentials from its log.

Before sharing an issue, manually remove identifiers and never attach a raw backup. Keep backups outside public repositories. Automatic pattern scanning helps detect common mistakes but cannot guarantee that arbitrary binary data is free of every private value.
