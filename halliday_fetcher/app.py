from __future__ import annotations

import json
import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from .g2_ui import G2Panel

from .core import (
    ANDROID_APPS,
    FILE_URL_ENDPOINT,
    OTA_CHECK_URL,
    HallidayDevice,
    HallidayError,
    HallidaySession,
    adb_devices,
    adb_package_status,
    discover_adb,
    download_firmware,
    extract_ble_firmware_identity,
    find_download_urls,
    g2_download_from_log,
    g2_downloads_from_text,
    iter_ota_logcat,
    redact_headers,
    redact_secrets,
    request_http,
)


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DOWNLOAD_DIRECTORY = PROJECT_ROOT / "downloads"


class FirmwareFetcherApp(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("Halliday Firmware Preserver")
        self.geometry("920x690")
        self.minsize(780, 580)
        self.adb = discover_adb()
        self.capture_stop = threading.Event()
        self.capture_thread: threading.Thread | None = None
        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.captured_urls: list[str] = []
        self.download_cancel = threading.Event()
        self.halliday_session = HallidaySession()
        self.direct_devices: list[HallidayDevice] = []
        self.direct_download: tuple[str, str | None, str | None] | None = None
        self._build_ui()
        self.protocol("WM_DELETE_WINDOW", self._close)
        self.after(100, self._drain_events)
        self.refresh_devices()

    def _close(self) -> None:
        self.g2_tab.close()
        self.capture_stop.set()
        self.download_cancel.set()
        if self.capture_thread and self.capture_thread.is_alive():
            self.capture_thread.join(timeout=0.5)
        self.destroy()

    def _build_ui(self) -> None:
        banner = ttk.Frame(self, padding=(14, 12))
        banner.pack(fill="x")
        ttk.Label(banner, text="Halliday Firmware Preserver", font=("Segoe UI", 17, "bold")).pack(anchor="w")
        ttk.Label(
            banner,
            text="Captures and preserves firmware offered to your own Halliday device. It never flashes firmware.",
        ).pack(anchor="w", pady=(3, 0))

        self.notebook = ttk.Notebook(self)
        self.notebook.pack(fill="both", expand=True, padx=14, pady=(0, 12))
        self.g2_tab = G2Panel(self.notebook, DOWNLOAD_DIRECTORY / "g2")
        self.capture_tab = ttk.Frame(self.notebook, padding=12)
        self.manual_tab = ttk.Frame(self.notebook, padding=12)
        self.direct_tab = ttk.Frame(self.notebook, padding=12)
        self.replay_tab = ttk.Frame(self.notebook, padding=12)
        self.notebook.add(self.g2_tab, text="G2 firmware downloader")
        self.notebook.add(self.capture_tab, text="Capture from phone")
        self.notebook.add(self.manual_tab, text="Download signed URL")
        self.notebook.add(self.direct_tab, text="DWG direct OTA check")
        self.notebook.add(self.replay_tab, text="DWG authorized API replay")
        self._build_capture_tab()
        self._build_manual_tab()
        self._build_direct_tab()
        self._build_replay_tab()

        self.status_var = tk.StringVar(value="Ready")
        ttk.Label(self, textvariable=self.status_var, relief="sunken", anchor="w", padding=(8, 5)).pack(fill="x")

    def _build_capture_tab(self) -> None:
        device_frame = ttk.LabelFrame(self.capture_tab, text="Android phone", padding=10)
        device_frame.pack(fill="x")
        ttk.Label(device_frame, text="Device:").grid(row=0, column=0, sticky="w")
        self.device_combo = ttk.Combobox(device_frame, state="readonly", width=48)
        self.device_combo.grid(row=0, column=1, sticky="ew", padx=8)
        self.device_combo.bind("<<ComboboxSelected>>", lambda _event: self.refresh_package_status())
        ttk.Button(device_frame, text="Refresh", command=self.refresh_devices).grid(row=0, column=2)
        self.device_status_var = tk.StringVar(value="No device checked")
        ttk.Label(device_frame, textvariable=self.device_status_var).grid(
            row=2, column=0, columnspan=3, sticky="w", pady=(8, 0)
        )
        ttk.Label(device_frame, text="App:").grid(row=1, column=0, sticky="w", pady=(8, 0))
        self.app_combo = ttk.Combobox(device_frame, state="readonly", values=list(ANDROID_APPS), width=48)
        self.app_combo.set("Halliday G2")
        self.app_combo.grid(row=1, column=1, sticky="ew", padx=8, pady=(8, 0))
        self.app_combo.bind("<<ComboboxSelected>>", lambda _event: self.refresh_package_status())
        device_frame.columnconfigure(1, weight=1)

        instruction = (
            "1. Select the installed app and start capture before opening its Firmware Update screen.\n"
            "2. Check for an update with your glasses paired. URLs appear only if the app logs them.\n"
            "3. Select a firmware download URL and save it. Capture does not start an installation."
        )
        ttk.Label(self.capture_tab, text=instruction, justify="left").pack(fill="x", pady=10)
        self.auto_preserve_g2 = tk.BooleanVar(value=True)
        ttk.Checkbutton(
            self.capture_tab,
            text="Automatically save G2 packages when their download links appear",
            variable=self.auto_preserve_g2,
        ).pack(anchor="w", pady=(0, 8))
        ttk.Label(
            self.capture_tab,
            text="G2 1.1.1 stores private logs. Use Import G2 update response/log on the Download signed URL tab.",
            wraplength=820,
        ).pack(anchor="w", pady=(0, 8))

        buttons = ttk.Frame(self.capture_tab)
        buttons.pack(fill="x")
        self.capture_button = ttk.Button(buttons, text="Start console capture", command=self.start_capture)
        self.capture_button.pack(side="left")
        self.stop_button = ttk.Button(buttons, text="Stop", command=self.stop_capture, state="disabled")
        self.stop_button.pack(side="left", padx=8)
        ttk.Button(buttons, text="Probe DWG API", command=self.probe_api).pack(side="left")

        ttk.Label(self.capture_tab, text="Captured download URLs:").pack(anchor="w", pady=(12, 4))
        self.url_list = tk.Listbox(self.capture_tab, height=5)
        self.url_list.pack(fill="x")
        ttk.Button(self.capture_tab, text="Download selected URL", command=self.download_selected).pack(
            anchor="e", pady=(6, 10)
        )

        ttk.Label(self.capture_tab, text="OTA-only logs (credentials are redacted):").pack(anchor="w")
        log_frame = ttk.Frame(self.capture_tab)
        log_frame.pack(fill="both", expand=True, pady=(4, 0))
        self.log_text = tk.Text(log_frame, wrap="word", height=14, font=("Consolas", 9))
        log_scroll = ttk.Scrollbar(log_frame, orient="vertical", command=self.log_text.yview)
        self.log_text.configure(yscrollcommand=log_scroll.set)
        self.log_text.pack(side="left", fill="both", expand=True)
        log_scroll.pack(side="right", fill="y")

    def _build_manual_tab(self) -> None:
        ttk.Label(
            self.manual_tab,
            text="Paste an authorized, temporary firmware URL returned by Halliday's app or API.",
        ).pack(anchor="w")
        form = ttk.Frame(self.manual_tab)
        form.pack(fill="x", pady=12)
        ttk.Label(form, text="HTTPS URL:").grid(row=0, column=0, sticky="nw", pady=4)
        self.manual_url = tk.Text(form, height=5, wrap="word")
        self.manual_url.grid(row=0, column=1, sticky="ew", padx=(10, 0), pady=4)
        ttk.Label(form, text="Expected checksum:").grid(row=1, column=0, sticky="w", pady=4)
        self.manual_checksum = ttk.Entry(form)
        self.manual_checksum.grid(row=1, column=1, sticky="ew", padx=(10, 0), pady=4)
        ttk.Label(form, text="Filename (optional):").grid(row=2, column=0, sticky="w", pady=4)
        self.manual_filename = ttk.Entry(form)
        self.manual_filename.grid(row=2, column=1, sticky="ew", padx=(10, 0), pady=4)
        form.columnconfigure(1, weight=1)
        actions = ttk.Frame(self.manual_tab)
        actions.pack(fill="x")
        ttk.Button(actions, text="Import G2 update response/log", command=self.import_g2_response).pack(side="left")
        ttk.Button(actions, text="Download and verify", command=self.download_manual).pack(side="right")
        self.manual_result = tk.Text(self.manual_tab, height=14, wrap="word", state="disabled")
        self.manual_result.pack(fill="both", expand=True, pady=(12, 0))

    def import_g2_response(self) -> None:
        chosen = filedialog.askopenfilename(
            title="Select your G2 update response or exported log",
            filetypes=[("Update response / log", "*.json *.log *.txt"), ("All files", "*.*")],
        )
        if not chosen:
            return
        try:
            source = Path(chosen)
            if source.stat().st_size > 16 * 1024 * 1024:
                raise HallidayError("Choose an update response or log file smaller than 16 MiB.")
            offers = g2_downloads_from_text(source.read_text(encoding="utf-8-sig"))
            if not offers:
                raise HallidayError("No firmware download link found. G2 returns downloadPath in its start-update response, not its version-check response.")
            url, filename, checksum = offers[-1]
            self.manual_url.delete("1.0", "end")
            self.manual_url.insert("1.0", url)
            self.manual_checksum.delete(0, "end")
            self.manual_checksum.insert(0, checksum or "")
            self.manual_filename.delete(0, "end")
            self.manual_filename.insert(0, filename or "halliday-g2-firmware.zip")
            self._set_manual_result(
                f"Loaded the last matching link from {len(offers)} distinct package link(s). "
                "Review it, then choose Download and verify.\n"
                + ("A server checksum is available." if checksum else "No server checksum was found; only local hashes can be calculated.")
            )
            self.notebook.select(self.manual_tab)
        except Exception as exc:
            messagebox.showerror("Could not import G2 response", str(exc))

    def _build_direct_tab(self) -> None:
        ttk.Label(
            self.direct_tab,
            text=(
                "Checks Halliday's official OTA service using your own account and bound device. "
                "Credentials and tokens stay in memory and are never written to disk."
            ),
            wraplength=820,
            justify="left",
        ).pack(anchor="w")

        auth = ttk.LabelFrame(self.direct_tab, text="Halliday account", padding=10)
        auth.pack(fill="x", pady=(10, 8))
        self.direct_username = tk.StringVar()
        self.direct_password = tk.StringVar()
        self.direct_token = tk.StringVar()
        ttk.Label(auth, text="Email / username:").grid(row=0, column=0, sticky="w", pady=3)
        ttk.Entry(auth, textvariable=self.direct_username).grid(row=0, column=1, sticky="ew", padx=8, pady=3)
        ttk.Label(auth, text="Password:").grid(row=1, column=0, sticky="w", pady=3)
        ttk.Entry(auth, textvariable=self.direct_password, show="•").grid(
            row=1, column=1, sticky="ew", padx=8, pady=3
        )
        ttk.Button(auth, text="Log in", command=self.direct_login).grid(row=0, column=2, rowspan=2, padx=(2, 0))
        ttk.Separator(auth, orient="horizontal").grid(row=2, column=0, columnspan=3, sticky="ew", pady=7)
        ttk.Label(auth, text="Or access token:").grid(row=3, column=0, sticky="w", pady=3)
        ttk.Entry(auth, textvariable=self.direct_token, show="•").grid(
            row=3, column=1, sticky="ew", padx=8, pady=3
        )
        ttk.Button(auth, text="Load my devices", command=self.direct_load_devices).grid(row=3, column=2)
        auth.columnconfigure(1, weight=1)

        device = ttk.LabelFrame(self.direct_tab, text="Bound Halliday device", padding=10)
        device.pack(fill="x", pady=(0, 8))
        self.direct_device_combo = ttk.Combobox(device, state="readonly")
        self.direct_device_combo.grid(row=0, column=0, columnspan=4, sticky="ew", pady=(0, 6))
        self.direct_device_combo.bind("<<ComboboxSelected>>", self._direct_device_selected)
        self.direct_sn = tk.StringVar()
        self.direct_type = tk.StringVar()
        self.direct_version = tk.StringVar(value="1.00.20.03")
        ttk.Label(device, text="Serial number:").grid(row=1, column=0, sticky="w", pady=3)
        ttk.Entry(device, textvariable=self.direct_sn).grid(row=1, column=1, sticky="ew", padx=(7, 14), pady=3)
        ttk.Label(device, text="Firmware UID (OTA type):").grid(row=1, column=2, sticky="w", pady=3)
        ttk.Entry(device, textvariable=self.direct_type, width=18).grid(row=1, column=3, sticky="ew", padx=(7, 0), pady=3)
        ttk.Label(device, text="Current firmware:").grid(row=2, column=0, sticky="w", pady=3)
        ttk.Entry(device, textvariable=self.direct_version).grid(
            row=2, column=1, sticky="ew", padx=(7, 14), pady=3
        )
        ttk.Button(device, text="Check and resolve download", command=self.direct_check).grid(
            row=2, column=2, columnspan=2, sticky="e"
        )
        device.columnconfigure(1, weight=1)
        device.columnconfigure(3, weight=1)

        result_row = ttk.Frame(self.direct_tab)
        result_row.pack(fill="x")
        ttk.Label(result_row, text="Official service result:").pack(side="left")
        self.direct_download_button = ttk.Button(
            result_row, text="Download offered firmware", command=self.direct_download_firmware, state="disabled"
        )
        self.direct_download_button.pack(side="right")
        self.direct_result = tk.Text(self.direct_tab, wrap="word", font=("Consolas", 9), height=12)
        self.direct_result.pack(fill="both", expand=True, pady=(4, 0))

    def _build_replay_tab(self) -> None:
        text = (
            "Replay one request captured from your own Halliday session. The request is restricted to "
            "service.halliday-tech.com. Tokens and crypto headers are redacted from the on-screen result "
            "and are never saved by this tool."
        )
        ttk.Label(self.replay_tab, text=text, wraplength=820, justify="left").pack(anchor="w")
        row = ttk.Frame(self.replay_tab)
        row.pack(fill="x", pady=10)
        self.request_path_var = tk.StringVar()
        ttk.Entry(row, textvariable=self.request_path_var).pack(side="left", fill="x", expand=True)
        ttk.Button(row, text="Choose request JSON", command=self.choose_request).pack(side="left", padx=(8, 0))
        ttk.Button(self.replay_tab, text="Send authorized request", command=self.replay_request).pack(anchor="e")
        ttk.Label(
            self.replay_tab,
            text=f"Known endpoints: {OTA_CHECK_URL}  |  {FILE_URL_ENDPOINT}",
            wraplength=820,
        ).pack(anchor="w", pady=(8, 4))
        self.replay_result = tk.Text(self.replay_tab, wrap="word", font=("Consolas", 9))
        self.replay_result.pack(fill="both", expand=True)

    def _selected_serial(self) -> str | None:
        value = self.device_combo.get()
        return value.split(" — ", 1)[0] if value else None

    def refresh_devices(self) -> None:
        if not self.adb:
            self.device_combo["values"] = []
            self.device_status_var.set("ADB was not found. Install Android Platform Tools or add adb.exe to PATH.")
            return
        try:
            devices = adb_devices(self.adb)
        except Exception as exc:
            self.device_status_var.set(f"Could not list phones: {exc}")
            return
        previous = self.device_combo.get()
        values = [f"{serial} — {model}" for serial, model in devices]
        self.device_combo["values"] = values
        if values:
            self.device_combo.set(previous if previous in values else values[0])
            self.refresh_package_status()
        else:
            self.device_status_var.set("No authorized online Android phone found.")

    def refresh_package_status(self) -> None:
        serial = self._selected_serial()
        if not self.adb or not serial:
            return
        name = self.app_combo.get()
        try:
            status = adb_package_status(self.adb, serial, ANDROID_APPS[name])
            if status.get("installed") == "yes":
                running = "running" if status.get("pid") else "not running"
                self.device_status_var.set(
                    f"{name} {status.get('version')} (build {status.get('versionCode')}), {running}"
                )
                if not (self.capture_thread and self.capture_thread.is_alive()):
                    private_g2_logs = name == "Halliday G2" and status.get("version") == "1.1.1"
                    self.capture_button.configure(state="disabled" if private_g2_logs else "normal")
                    if private_g2_logs:
                        self.device_status_var.set(self.device_status_var.get() + " — private logs; import an update response")
            else:
                self.device_status_var.set(f"{name} is not installed for the primary phone user.")
        except Exception as exc:
            self.device_status_var.set(f"Could not inspect app: {exc}")

    def start_capture(self) -> None:
        serial = self._selected_serial()
        if not self.adb or not serial:
            messagebox.showerror("No phone", "Connect and authorize an Android phone first.")
            return
        if self.capture_thread and self.capture_thread.is_alive():
            return
        package = ANDROID_APPS[self.app_combo.get()]
        auto_save = package == ANDROID_APPS["Halliday G2"] and self.auto_preserve_g2.get()
        self.capture_stop.clear()
        self.capture_button.configure(state="disabled")
        self.app_combo.configure(state="disabled")
        self.device_combo.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self.status_var.set("Capturing Halliday OTA logs…")

        def worker() -> None:
            preserved_urls: set[str] = set()
            try:
                for line, urls in iter_ota_logcat(self.adb, serial, self.capture_stop, package):
                    self.events.put(("log", line))
                    identity = extract_ble_firmware_identity(line) if package == ANDROID_APPS["Halliday DWG"] else None
                    if identity:
                        self.events.put(("ble_identity", identity))
                    for url in urls:
                        self.events.put(("url", url))
                    offered = g2_download_from_log(line) if auto_save else None
                    if offered and offered[0] not in preserved_urls:
                        # Use the original URL; the displayed log can have secrets redacted.
                        raw_url = next((url for url in urls if redact_secrets(url) == offered[0]), None)
                        if raw_url:
                            result = download_firmware(
                                raw_url, DOWNLOAD_DIRECTORY / "g2",
                                expected_checksum=offered[1], cancel_event=self.capture_stop,
                            )
                            preserved_urls.add(raw_url)
                            self.events.put(("download_complete", result))
            except Exception as exc:
                self.events.put(("error", str(exc)))
            finally:
                self.events.put(("capture_stopped", None))

        self.capture_thread = threading.Thread(target=worker, daemon=True)
        self.capture_thread.start()

    def stop_capture(self) -> None:
        self.capture_stop.set()
        self.status_var.set("Stopping capture…")

    def probe_api(self) -> None:
        self._run_http({"method": "GET", "url": OTA_CHECK_URL, "headers": {}}, target="capture")

    def download_selected(self) -> None:
        selection = self.url_list.curselection()
        if not selection:
            messagebox.showinfo("Select a URL", "Select a captured firmware URL first.")
            return
        self._begin_download(self.captured_urls[selection[0]])

    def download_manual(self) -> None:
        url = self.manual_url.get("1.0", "end").strip()
        checksum = self.manual_checksum.get().strip() or None
        filename = self.manual_filename.get().strip() or None
        self._begin_download(url, checksum=checksum, filename=filename)

    def _begin_download(self, url: str, *, checksum: str | None = None, filename: str | None = None) -> None:
        if not url:
            messagebox.showerror("Missing URL", "Paste or capture a firmware URL first.")
            return
        self.download_cancel.clear()
        self.status_var.set("Starting firmware download…")

        def progress(received: int, total: int | None) -> None:
            if total:
                self.events.put(("status", f"Downloading: {received / 1048576:.1f} / {total / 1048576:.1f} MB"))
            else:
                self.events.put(("status", f"Downloading: {received / 1048576:.1f} MB"))

        def worker() -> None:
            try:
                result = download_firmware(
                    url,
                    DOWNLOAD_DIRECTORY,
                    suggested_name=filename,
                    expected_checksum=checksum,
                    progress=progress,
                    cancel_event=self.download_cancel,
                )
                self.events.put(("download_complete", result))
            except Exception as exc:
                self.events.put(("error", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def choose_request(self) -> None:
        chosen = filedialog.askopenfilename(
            title="Choose request JSON", filetypes=[("JSON files", "*.json"), ("All files", "*.*")]
        )
        if chosen:
            self.request_path_var.set(chosen)

    def _sync_direct_token(self) -> None:
        pasted = self.direct_token.get().strip()
        if pasted:
            self.halliday_session.access_token = pasted

    def direct_login(self) -> None:
        username = self.direct_username.get().strip()
        password = self.direct_password.get()
        self.status_var.set("Signing in to Halliday…")

        def worker() -> None:
            try:
                result = self.halliday_session.login(username, password)
                if not self.halliday_session.access_token:
                    raise HallidayError(self._api_message(result.json_body, "Halliday login was not accepted."))
                self.events.put(("direct_login", result))
            except Exception as exc:
                self.events.put(("error", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def direct_load_devices(self) -> None:
        self._sync_direct_token()
        self.status_var.set("Loading devices bound to your Halliday account…")

        def worker() -> None:
            try:
                result, devices = self.halliday_session.get_bound_devices()
                if not devices:
                    raise HallidayError(self._api_message(result.json_body, "No bound Halliday devices were returned."))
                self.events.put(("direct_devices", (result, devices)))
            except Exception as exc:
                self.events.put(("error", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def _direct_device_selected(self, _event: object | None = None) -> None:
        index = self.direct_device_combo.current()
        if index < 0 or index >= len(self.direct_devices):
            return
        selected = self.direct_devices[index]
        self.direct_sn.set(selected.serial_number)
        self.direct_type.set(selected.firmware_uid)
        if selected.firmware_version:
            self.direct_version.set(selected.firmware_version)

    @staticmethod
    def _find_json_string(value: object, keys: set[str]) -> str | None:
        if isinstance(value, dict):
            for key, item in value.items():
                if str(key).lower() in keys and isinstance(item, (str, int)) and str(item):
                    return str(item)
            for item in value.values():
                found = FirmwareFetcherApp._find_json_string(item, keys)
                if found:
                    return found
        elif isinstance(value, list):
            for item in value:
                found = FirmwareFetcherApp._find_json_string(item, keys)
                if found:
                    return found
        return None

    @staticmethod
    def _api_message(value: object, fallback: str) -> str:
        found = FirmwareFetcherApp._find_json_string(value, {"msg", "message", "error"})
        return found or fallback

    def direct_check(self) -> None:
        self._sync_direct_token()
        serial_number = self.direct_sn.get()
        device_type = self.direct_type.get()
        version = self.direct_version.get()
        self.direct_download = None
        self.direct_download_button.configure(state="disabled")
        self.status_var.set("Checking Halliday's official firmware service…")

        def worker() -> None:
            try:
                check = self.halliday_session.check_firmware(
                    serial_number=serial_number, device_type=device_type, version_name=version
                )
                url_result = None
                urls = find_download_urls(check.json_body)
                if not urls:
                    file_id = self._find_json_string(check.json_body, {"fileid"})
                    if file_id:
                        url_result = self.halliday_session.get_download_url(file_id)
                self.events.put(("direct_check", (check, url_result)))
            except Exception as exc:
                self.events.put(("error", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def direct_download_firmware(self) -> None:
        if not self.direct_download:
            return
        url, filename, checksum = self.direct_download
        self._begin_download(url, checksum=checksum, filename=filename)

    def replay_request(self) -> None:
        path = Path(self.request_path_var.get())
        if not path.is_file():
            messagebox.showerror("Missing request", "Choose a request JSON file first.")
            return
        try:
            spec = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            messagebox.showerror("Invalid JSON", str(exc))
            return
        self._run_http(spec, target="replay")

    def _run_http(self, spec: dict[str, object], *, target: str) -> None:
        self.status_var.set("Contacting Halliday service…")

        def worker() -> None:
            try:
                result = request_http(spec)
                self.events.put(("http_result", (target, spec, result)))
            except Exception as exc:
                self.events.put(("error", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def _set_manual_result(self, text: str) -> None:
        self.manual_result.configure(state="normal")
        self.manual_result.delete("1.0", "end")
        self.manual_result.insert("1.0", text)
        self.manual_result.configure(state="disabled")

    def _drain_events(self) -> None:
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "log":
                    self.log_text.insert("end", str(payload) + "\n")
                    self.log_text.see("end")
                elif kind == "url":
                    url = str(payload)
                    if url not in self.captured_urls:
                        self.captured_urls.append(url)
                        self.url_list.insert("end", redact_secrets(url))
                elif kind == "status":
                    self.status_var.set(str(payload))
                elif kind == "error":
                    self.status_var.set("Error")
                    messagebox.showerror("Halliday Firmware Preserver", str(payload))
                elif kind == "capture_stopped":
                    self.capture_button.configure(state="normal")
                    self.stop_button.configure(state="disabled")
                    self.app_combo.configure(state="readonly")
                    self.device_combo.configure(state="readonly")
                    self.status_var.set("Capture stopped")
                elif kind == "direct_login":
                    self.direct_password.set("")
                    self.direct_token.set(self.halliday_session.access_token)
                    self.direct_result.delete("1.0", "end")
                    self.direct_result.insert(
                        "1.0",
                        "Login accepted. The access token is held only in memory.\n"
                        "Choose “Load my devices” to fill the OTA fields automatically.",
                    )
                    self.status_var.set("Halliday login accepted")
                elif kind == "direct_devices":
                    _result, devices = payload
                    self.direct_devices = devices
                    self.direct_device_combo["values"] = [item.label for item in devices]
                    self.direct_device_combo.current(0)
                    self._direct_device_selected()
                    self.direct_result.delete("1.0", "end")
                    selected = devices[0]
                    uid_note = selected.firmware_uid or "missing — capture it from the official app"
                    self.direct_result.insert(
                        "1.0",
                        f"Loaded {len(devices)} device(s) from your Halliday account.\n"
                        f"Firmware UID used as OTA type: {uid_note}\n"
                        f"Account deviceType (not used for OTA): "
                        f"{selected.account_device_type or 'missing'}",
                    )
                    self.status_var.set("Bound Halliday devices loaded")
                elif kind == "ble_identity":
                    firmware_uid, serial_number = payload
                    self.direct_type.set(firmware_uid)
                    self.direct_sn.set(serial_number)
                    self.status_var.set("Captured the BLE firmware UID and serial from Halliday DWG")
                elif kind == "direct_check":
                    check, url_result = payload
                    offered = find_download_urls(check.json_body)
                    if url_result is not None:
                        offered.extend(find_download_urls(url_result.json_body))
                    unique = []
                    seen = set()
                    for item in offered:
                        if item[0] not in seen:
                            seen.add(item[0])
                            unique.append(item)
                    if unique:
                        url, filename, checksum = unique[0]
                        filename = filename or self._find_json_string(check.json_body, {"filename"})
                        checksum = checksum or self._find_json_string(
                            check.json_body, {"filechecksum", "checksum", "md5", "sha256"}
                        )
                        self.direct_download = (url, filename, checksum)
                        self.direct_download_button.configure(state="normal")
                    package_file_id = self._find_json_string(check.json_body, {"fileid"})
                    if self.direct_download:
                        interpretation = "Halliday supplied a firmware package and download URL."
                    elif package_file_id:
                        interpretation = (
                            "Halliday supplied data.pkgs[].fileInfo.fileId, but its download URL "
                            "could not be resolved."
                        )
                    else:
                        interpretation = (
                            "Metadata only: the response has no data.pkgs[].fileInfo.fileId. "
                            "firmwareId and firmwareVersionId are database identifiers, not file IDs, "
                            "so Halliday DWG would not request a download URL for this result."
                        )
                    visible = {
                        "firmware_check": check.json_body
                        if check.json_body is not None
                        else check.body.decode("utf-8", errors="replace"),
                        "download_url_result": url_result.json_body
                        if url_result is not None and url_result.json_body is not None
                        else None,
                        "download_ready": bool(self.direct_download),
                        "interpretation": interpretation,
                    }
                    self.direct_result.delete("1.0", "end")
                    self.direct_result.insert("1.0", json.dumps(visible, indent=2, ensure_ascii=False))
                    self.status_var.set(
                        "Firmware download is ready" if self.direct_download else "Firmware check complete"
                    )
                elif kind == "download_complete":
                    result = payload
                    checksum_note = (
                        "not supplied"
                        if result.checksum_ok is None
                        else "MATCH" if result.checksum_ok else "MISMATCH — file marked unsafe"
                    )
                    summary = (
                        f"Saved: {result.path}\n"
                        f"Size: {result.size:,} bytes\n"
                        f"SHA-256: {result.sha256}\n"
                        f"MD5: {result.md5}\n"
                        f"Expected checksum: {checksum_note}\n"
                        f"Content type: {result.content_type or 'unknown'}"
                    )
                    self._set_manual_result(summary)
                    self.notebook.select(self.manual_tab)
                    self.status_var.set(f"Firmware preserved: {result.path.name}")
                    messagebox.showinfo("Download complete", summary)
                elif kind == "http_result":
                    target, spec, result = payload
                    visible = {
                        "request": {
                            "method": spec.get("method", "GET"),
                            "url": spec.get("url"),
                            "headers": redact_headers(spec.get("headers") or {}),
                        },
                        "status": result.status,
                        "headers": redact_headers(result.headers),
                        "body": result.json_body
                        if result.json_body is not None
                        else redact_secrets(result.body.decode("utf-8", errors="replace")),
                    }
                    text = json.dumps(visible, indent=2, ensure_ascii=False)
                    if target == "capture":
                        self.log_text.insert("end", text + "\n")
                        self.log_text.see("end")
                    else:
                        self.replay_result.delete("1.0", "end")
                        self.replay_result.insert("1.0", text)
                    for url, filename, checksum in find_download_urls(result.json_body):
                        if url not in self.captured_urls:
                            self.captured_urls.append(url)
                            self.url_list.insert("end", redact_secrets(url))
                    self.status_var.set(f"Halliday service replied with HTTP {result.status}")
        except queue.Empty:
            pass
        self.after(100, self._drain_events)


def main() -> None:
    try:
        app = FirmwareFetcherApp()
        app.mainloop()
    except HallidayError as exc:
        messagebox.showerror("Halliday Firmware Preserver", str(exc))


if __name__ == "__main__":
    main()
