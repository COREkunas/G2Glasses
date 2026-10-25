# Firmware Preserver

Requirements: Python 3.11+, Tkinter, and the packages in `requirements.txt`. Windows is the tested desktop environment. Use the virtual-environment setup in the main README. `start.cmd` avoids the PowerShell script-execution-policy issue; changing system policy is unnecessary.

1. Keep the official account binding and pairing. The tool does not bind new glasses.
2. Open **G2 firmware downloader** and enter your account details locally. An existing access token is also supported. Never paste either into issues or screenshots.
3. Select your glasses after **Sign in / load glasses**. If no firmware version code is available, connect them in the official phone app and reload. Do not invent a numeric code from a version label.
4. Use **Check and save now**. Alternatively, **Start watching & save** checks immediately and then at the configured interval (default: 60 minutes), while the program and PC stay awake.
5. Files and verification records are saved under `downloads/g2`. Existing files are rehashed; incomplete or incorrect files are not indexed as successful. Keep this directory out of Git.

Credentials and session tokens are retained in process memory. Closing the program ends the session and watcher. Expiry pauses watching for a new sign-in. Records omit signed download URLs and account secrets, but still review anything before sharing.

The G2 implementation uses the Halliday gateway's account login, binding list, firmware check and OTA-start endpoints. Resolving a download calls `/hallidayiot/app/ota/upgrade/start`, **creating a server-side OTA record**. It does not transfer the update to the glasses, report a completed installation, change a binding, or call the OTA update-status endpoint. This is preservation, not a flasher.

Other legacy tabs refer to the older **DWG** platform. Their presence does not make DWG firmware compatible with G2. The reconstructed G2 API contract came from official app 1.1.1/build 186 and can change server-side.

Run the included synthetic tests without an account:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```
