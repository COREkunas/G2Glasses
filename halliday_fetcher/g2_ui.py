from __future__ import annotations

import os
import queue
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import messagebox, ttk

from .g2 import G2Cancelled, G2Preserver, G2Session, watch_g2


class G2Panel(ttk.Frame):
    def __init__(self, master, directory: Path):
        super().__init__(master, padding=12)
        self.directory = directory.resolve()
        self.session: G2Session | None = None
        self.preserver: G2Preserver | None = None
        self.devices = []
        self.stop = threading.Event()
        self.events = queue.Queue()
        self.worker: threading.Thread | None = None
        self.closed = False
        self.controls = []
        self.email = tk.StringVar()
        self.password = tk.StringVar()
        self.token = tk.StringVar()
        self.firmware = tk.StringVar(value="Sign in to load your glasses.")
        self.fallback_code = tk.StringVar()
        self.interval = tk.StringVar(value="60")
        self.state = tk.StringVar(value="Not watching — sign in to begin.")
        self._build()
        self._after = self.after(100, self._drain)

    def _build(self):
        ttk.Label(self, text="Save new G2 firmware to your PC", font=("Segoe UI", 13, "bold")).pack(anchor="w")
        ttk.Label(self, text="Keep this window open and the PC awake while watching. Sign in again after closing the app.", wraplength=830).pack(anchor="w", pady=(3, 8))
        auth = ttk.LabelFrame(self, text="Your Halliday G2 account", padding=8)
        auth.pack(fill="x")
        for row, (label, variable, hidden) in enumerate([
            ("Email", self.email, False), ("Password", self.password, True),
            ("Or existing G2 access token", self.token, True),
        ]):
            ttk.Label(auth, text=label).grid(row=row, column=0, sticky="w", pady=2)
            entry = ttk.Entry(auth, textvariable=variable, show="•" if hidden else "")
            entry.grid(row=row, column=1, sticky="ew", padx=8, pady=2)
            self.controls.append((entry, "normal"))
        self.login_button = ttk.Button(auth, text="Sign in / load glasses", command=self._login)
        self.login_button.grid(row=0, column=2, rowspan=3, padx=4)
        self.controls.append((self.login_button, "normal"))
        auth.columnconfigure(1, weight=1)

        device = ttk.LabelFrame(self, text="Your glasses", padding=8)
        device.pack(fill="x", pady=8)
        self.device_combo = ttk.Combobox(device, state="readonly")
        self.device_combo.grid(row=0, column=0, columnspan=3, sticky="ew")
        self.device_combo.bind("<<ComboboxSelected>>", self._selected)
        self.controls.append((self.device_combo, "readonly"))
        ttk.Label(device, textvariable=self.firmware).grid(row=1, column=0, columnspan=3, sticky="w", pady=(5, 0))
        ttk.Label(device, text="Firmware code if missing from account:").grid(row=2, column=0, sticky="w", pady=(4, 0))
        entry = ttk.Entry(device, textvariable=self.fallback_code, width=12)
        entry.grid(row=2, column=1, sticky="w", padx=8, pady=(4, 0))
        self.controls.append((entry, "normal"))
        ttk.Label(device, text="Usually leave blank; do not enter a version name.").grid(row=2, column=2, sticky="w")
        device.columnconfigure(0, weight=1)

        actions = ttk.Frame(self)
        actions.pack(fill="x", pady=(0, 5))
        self.check_button = ttk.Button(actions, text="Check and save now", command=lambda: self._run(False))
        self.check_button.pack(side="left")
        self.watch_button = ttk.Button(actions, text="Start watching & save", command=lambda: self._run(True))
        self.watch_button.pack(side="left", padx=6)
        self.stop_button = ttk.Button(actions, text="Stop", state="disabled", command=self._stop)
        self.stop_button.pack(side="left")
        ttk.Label(actions, text="Check every").pack(side="left", padx=(18, 4))
        interval = ttk.Spinbox(actions, from_=1, to=1440, textvariable=self.interval, width=5)
        interval.pack(side="left")
        ttk.Label(actions, text="minutes").pack(side="left", padx=4)
        self.controls.extend([(self.check_button, "normal"), (self.watch_button, "normal"), (interval, "normal")])
        self.progress = ttk.Progressbar(self, mode="determinate")
        self.progress.pack(fill="x", pady=(3, 5))
        ttk.Label(self, textvariable=self.state, wraplength=830).pack(anchor="w")
        folder = ttk.Frame(self)
        folder.pack(fill="x", pady=(5, 5))
        ttk.Label(folder, text="Saved in downloads/g2 — SHA-256 and package size verified.").pack(side="left")
        ttk.Button(folder, text="Open saved files", command=self._open_folder).pack(side="right")
        self.log = tk.Text(self, height=9, wrap="word", state="disabled")
        self.log.pack(fill="both", expand=True)
        self._busy(False)

    def _busy(self, busy: bool):
        for widget, normal in self.controls:
            widget.configure(state="disabled" if busy else normal)
        if not busy and (not self.session or not self.session.access_token or not self.devices):
            self.check_button.configure(state="disabled")
            self.watch_button.configure(state="disabled")
        self.stop_button.configure(state="normal" if busy else "disabled")

    def _log(self, message: str):
        self.log.configure(state="normal")
        self.log.insert("end", time.strftime("%H:%M:%S") + "  " + message + "\n")
        # Bound memory use during a long watch session.
        if int(self.log.index("end-1c").split(".")[0]) > 500:
            self.log.delete("1.0", "100.0")
        self.log.see("end")
        self.log.configure(state="disabled")

    def _emit(self, kind, value):
        self.events.put((kind, value))

    def _start_worker(self, action):
        if self.worker and self.worker.is_alive():
            return
        self.stop.clear()
        self._busy(True)
        self.progress["value"] = 0

        def work():
            try:
                action()
            except G2Cancelled:
                pass
            except Exception as exc:
                if not self.stop.is_set():
                    self._emit("error", str(exc))
            finally:
                self._emit("finished", None)

        self.worker = threading.Thread(target=work, daemon=True)
        self.worker.start()

    def _login(self):
        email, password, token = self.email.get().strip(), self.password.get(), self.token.get().strip()
        if not token and (not email or not password):
            messagebox.showinfo("Sign in", "Enter your Halliday G2 email and password, or paste an existing G2 access token.")
            return
        # Clear the password widget immediately; the worker uses it only for this login.
        self.password.set("")
        self.token.set("")
        self.session = None
        self.preserver = None
        self.devices = []
        self.device_combo.set("")
        self.device_combo["values"] = []
        self.firmware.set("Loading your glasses…")
        self.state.set("Signing in and loading your glasses…")

        def action():
            session = G2Session(access_token=token)
            if not token:
                session.login(email, password)
            if self.stop.is_set():
                return
            devices = session.get_devices()
            if not self.stop.is_set():
                self._emit("signed_in", (session, devices))

        self._start_worker(action)

    def _selected(self, _event=None):
        index = self.device_combo.current()
        if 0 <= index < len(self.devices):
            device = self.devices[index]
            self.firmware.set(f"Reported firmware: {device.firmware_version}   |   Code: {device.firmware_code if device.firmware_code is not None else 'not reported'}")
            self.fallback_code.set("")

    def _run(self, watching: bool):
        index = self.device_combo.current()
        if not self.preserver or not 0 <= index < len(self.devices):
            return
        try:
            minutes = int(self.interval.get())
            if not 1 <= minutes <= 1440:
                raise ValueError()
            code_text = self.fallback_code.get().strip()
            code = int(code_text) if code_text else None
            if code is not None and code < 0:
                raise ValueError()
        except ValueError:
            messagebox.showerror("Check settings", "Use 1–1440 minutes and a non-negative whole firmware code, or leave the code blank.")
            return
        device = self.devices[index]
        if device.firmware_code is None and code is None:
            messagebox.showinfo("Firmware code missing", "Connect your glasses in the official phone app, then reload devices. If you know the actual numeric firmware code, enter it in the fallback field.")
            return
        preserver = self.preserver
        self.state.set("Watching — checking now…" if watching else "Checking now…")
        self._log(f"{'Watching' if watching else 'Checking'} {device.label}.")

        def action():
            if watching:
                watch_g2(preserver, device.serial, self.stop, interval_seconds=minutes * 60,
                         fallback_code=code, emit=self._emit)
            else:
                result = preserver.check_and_save(
                    device.serial, self.stop, fallback_code=code,
                    status=lambda message: self._emit("status", message),
                    progress=lambda done, total: self._emit("progress", (done, total)),
                )
                self._emit("result", result)

        self._start_worker(action)

    def _stop(self):
        self.stop.set()
        self.state.set("Stopping — waiting for the current request to finish…")
        self.stop_button.configure(state="disabled")

    def _open_folder(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        os.startfile(self.directory)

    def _drain(self):
        if self.closed:
            return
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "signed_in":
                    self.session, self.devices = value
                    self.preserver = G2Preserver(self.session, self.directory)
                    self.device_combo["values"] = [item.label for item in self.devices]
                    if self.devices:
                        self.device_combo.current(0)
                        self._selected()
                        self.state.set("Signed in. Ready to check or start watching.")
                    else:
                        self.state.set("Signed in, but no glasses are listed. Pair them in the G2 phone app, then reload.")
                    self._log(f"Sign-in accepted. Loaded {len(self.devices)} device(s).")
                elif kind == "status":
                    if not self.stop.is_set():
                        self.state.set(value)
                elif kind == "progress":
                    done, total = value
                    self.progress["value"] = done / total * 100 if total else 0
                    if not self.stop.is_set():
                        self.state.set(f"Downloading: {done / 1048576:.1f} MB" + (f" / {total / 1048576:.1f} MB" if total else ""))
                elif kind == "result":
                    result = value
                    self.firmware.set(f"Reported firmware: {result.device.firmware_version}   |   Code: {result.device.firmware_code}")
                    if result.status == "saved":
                        self.progress["value"] = 100
                        message = f"Saved firmware {result.offer.version_name}: {result.path.name}\nSHA-256 verified: {result.sha256}"
                        self.bell()
                    elif result.status == "already_saved":
                        message = f"Firmware {result.offer.version_name} is already saved and verified."
                    else:
                        message = "No newer firmware is currently offered to these glasses."
                    self._log(message)
                    self.state.set(message.split("\n")[0])
                elif kind == "next_check":
                    if not self.stop.is_set():
                        self.state.set("Watching — next check " + time.strftime("%d %b %H:%M", time.localtime(value)))
                elif kind in ("warning", "error"):
                    self._log(value)
                    self.state.set(("Action needed: " if kind == "error" else "Retry scheduled: ") + value)
                elif kind == "finished":
                    self._busy(False)
                    if self.stop.is_set():
                        self.state.set("Stopped — no background checks are running.")
        except queue.Empty:
            pass
        self._after = self.after(100, self._drain)

    def close(self):
        self.closed = True
        self.stop.set()
        self.after_cancel(self._after)
