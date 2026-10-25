"""G2 1.1.1 protocol, reconstructed from the owner's installed app (build 186).

Only sign-in, bound-device listing, update checks and package resolution are sent.
The OTA start endpoint creates a server record; no device transfer or status update
is implemented here. Authentication material stays in memory.
"""
from __future__ import annotations

import hashlib
import os
import json
import re
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from .core import HallidayError, HallidaySession, USER_AGENT, download_firmware


G2_ORIGIN = "https://halliday-gateway.halliday-tech.com"
G2_APP_VERSION = "1.1.1"
G2_APP_BUILD = "186"
LOGIN_PATH = "/auth/app/login"
DEVICES_PATH = "/hallidayiot/app/device/bindlist"
CHECK_PATH = "/hallidayiot/app/firmware/upgrade/check"
START_PATH = "/hallidayiot/app/ota/upgrade/start"
ALLOWED_REQUESTS = {("POST", LOGIN_PATH), ("GET", DEVICES_PATH),
                    ("POST", CHECK_PATH), ("POST", START_PATH)}


class G2AuthenticationError(HallidayError):
    pass


class G2ProtocolError(HallidayError):
    pass


class G2Cancelled(HallidayError):
    pass


class G2IntegrityError(HallidayError):
    pass


@dataclass(frozen=True)
class G2Device:
    serial: str
    name: str
    firmware_version: str
    firmware_code: int | None

    @property
    def label(self) -> str:
        return f"{self.name} — {self.serial[-6:]}"


@dataclass(frozen=True)
class G2Offer:
    version_id: int
    version_name: str
    version_code: int
    channel: str
    notes: tuple[str, ...]
    minimum_app_build: int = 0


@dataclass(frozen=True)
class G2Package:
    record_id: int
    url: str
    size: int
    sha256: str
    version_code: int


@dataclass(frozen=True)
class G2CheckResult:
    status: str
    device: G2Device
    offer: G2Offer | None = None
    path: Path | None = None
    sha256: str | None = None


def _integer(value: Any, field: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise G2ProtocolError(f"Halliday did not provide a valid {field}.")
    if not re.fullmatch(r"[0-9]+", str(value)):
        raise G2ProtocolError(f"Halliday did not provide a valid {field}.")
    result = int(value)
    if result < minimum:
        raise G2ProtocolError(f"Halliday did not provide a valid {field}.")
    return result


def _text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise G2ProtocolError(f"Halliday did not provide {field}.")
    return value.strip()


def parse_devices(data: Any) -> list[G2Device]:
    if isinstance(data, dict):
        data = data.get("records")
    if not isinstance(data, list):
        raise G2ProtocolError("Halliday's device list has an unexpected format.")
    devices: list[G2Device] = []
    seen: set[str] = set()
    for row in data:
        if not isinstance(row, dict):
            raise G2ProtocolError("Halliday returned an invalid device record.")
        # DeviceUpdateModel.fromHttpMap first unwraps each binding's deviceInfo.
        # Binding metadata (including IDs and bond keys) is not device identity.
        if "deviceInfo" in row:
            row = row["deviceInfo"]
            if row is None:
                continue
            if not isinstance(row, dict):
                raise G2ProtocolError("Halliday returned a binding without device information.")
        if row.get("sn") in (None, ""):
            continue
        serial = _text(row.get("sn"), "device serial number")
        if serial in seen:
            raise G2ProtocolError("Halliday returned duplicate device serial numbers.")
        seen.add(serial)
        code = row.get("firmwareVersionCode")
        devices.append(G2Device(
            serial, str(row.get("deviceName") or row.get("deviceModelName") or "Halliday G2"),
            str(row.get("firmwareVersion") or "Unknown"),
            None if code is None or code == "" else _integer(code, "current firmware code"),
        ))
    if data and not devices:
        raise G2ProtocolError("The account's bindings have no reported device serial number yet. Connect your glasses in the G2 phone app, then reload devices.")
    return devices


def parse_offer(data: Any) -> G2Offer | None:
    if data is None or data == {}:
        return None
    if not isinstance(data, dict):
        raise G2ProtocolError("Halliday's firmware response has an unexpected format.")
    notes = data.get("notes") or []
    readable = tuple(
        str(item["updateLog"]) for item in notes
        if isinstance(item, dict) and isinstance(item.get("updateLog"), str)
    ) if isinstance(notes, list) else ()
    return G2Offer(
        _integer(data.get("firmwareVersionId"), "firmware version ID", 1),
        _text(data.get("firmwareVersionName"), "firmware version name"),
        _integer(data.get("firmwareVersionCode"), "offered firmware code", 1),
        str(data.get("channel") or ""), readable,
        _integer(data.get("lowLimitAppVersionCode") or 0, "minimum app build"),
    )


def parse_package(data: Any, offer: G2Offer) -> G2Package:
    if not isinstance(data, dict):
        raise G2ProtocolError("Halliday did not return a firmware package.")
    url = _text(data.get("downloadPath"), "firmware download link")
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise G2ProtocolError("Halliday returned an invalid HTTPS package link.")
    checksum = _text(data.get("checkCode"), "SHA-256 checksum")
    checksum = re.sub(r"(?i)^sha256\s*[:=]\s*", "", checksum).lower()
    if not re.fullmatch(r"[0-9a-f]{64}", checksum):
        raise G2ProtocolError("Halliday's package checksum is not a SHA-256 value.")
    version_code = _integer(data.get("targetFirmwareVersionCode"), "target firmware code", 1)
    if version_code != offer.version_code:
        raise G2ProtocolError("The resolved package does not match the offered firmware version.")
    return G2Package(
        _integer(data.get("recordId"), "update record ID", 1), url,
        _integer(data.get("packageSize"), "package size", 1), checksum, version_code,
    )


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the owner's token or login body to another endpoint.
        raise G2ProtocolError("Halliday redirected an API request; the request was stopped.")


class G2Session:
    def __init__(self, *, access_token: str = "", timezone_name: str = "Europe/Vilnius") -> None:
        self.access_token = self.normalize_token(access_token)
        self.timezone_name = timezone_name
        self.device_id = uuid.uuid4().hex
        self._crypto = HallidaySession()
        self._lock = threading.RLock()
        self._opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=ssl.create_default_context()), _NoRedirect(),
        )

    @staticmethod
    def normalize_token(value: str) -> str:
        token = re.sub(r"(?i)^Bearer\s+", "", value.strip())
        if any(char.isspace() for char in token):
            raise G2AuthenticationError("The access token contains spaces or line breaks.")
        return token

    def _headers(self, *, login: bool = False) -> dict[str, str]:
        headers = {
            "Accept": "application/json", "Content-Type": "application/json",
            "Accept-Language": "en-US", "X-Timezone": self.timezone_name,
            "X-App-Version-Name": G2_APP_VERSION, "X-App-Version-Code": G2_APP_BUILD,
            "X-Platform": "android", "X-OS-Version": "17", "X-OS-SDK": "37",
            "X-OS-Manufacturer": "samsung", "X-OS-Model": "SM-F976B",
            "X-Device-Id": self.device_id, "X-Request-Id": str(uuid.uuid4()),
            "X-Timestamp": str(int(time.time() * 1000)), "User-Agent": USER_AGENT,
        }
        if login:
            # The app encrypts the password, not the whole JSON body. These keys
            # let the server encrypt profile fields in its login response.
            headers["xsaeskey"] = self._crypto._rsa_encrypt(self._crypto.aes_key)
            headers["xsaesiv"] = self._crypto._rsa_encrypt(self._crypto.aes_iv)
        else:
            if not self.access_token:
                raise G2AuthenticationError("Sign in to your Halliday G2 account first.")
            headers["Authorization"] = "Bearer " + self.access_token
        return headers

    def _request(self, method: str, path: str, body: Any = None) -> Any:
        if (method, path) not in ALLOWED_REQUESTS:
            raise G2ProtocolError("This G2 operation is not supported by the downloader.")
        with self._lock:
            headers = self._headers(login=path == LOGIN_PATH)
            encoded = None if body is None else json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            request = urllib.request.Request(G2_ORIGIN + path, data=encoded, headers=headers, method=method)
            try:
                response = self._opener.open(request, timeout=25)
            except urllib.error.HTTPError as exc:
                response = exc
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                raise HallidayError("Could not reach Halliday. Check the PC's internet connection and try again.") from exc
            with response:
                status = response.status
                content = response.read(4 * 1024 * 1024 + 1)
            if len(content) > 4 * 1024 * 1024:
                raise G2ProtocolError("Halliday returned an oversized API response.")
            try:
                value = json.loads(content)
            except (ValueError, UnicodeError):
                value = None
            code = value.get("code") if isinstance(value, dict) else None
            if status == 401 or code in (401, 403, "401", "403"):
                self.access_token = ""
                raise G2AuthenticationError("Halliday rejected or expired this session. Sign in again.")
            if status == 403:
                raise G2ProtocolError("The G2 gateway blocked this PC request (HTTP 403). This does not establish that your password is wrong.")
            if not 200 <= status < 300:
                raise HallidayError(f"Halliday returned HTTP {status}. Try again later.")
            if not isinstance(value, dict):
                raise G2ProtocolError("Halliday returned an unreadable API response.")
            if code not in (200, "200"):
                display_code = str(code) if isinstance(code, int) else "unknown"
                raise G2ProtocolError(f"Halliday did not accept the request (code {display_code}).")
            if "data" not in value:
                raise G2ProtocolError("Halliday's response is missing its data field.")
            return value["data"]

    def login(self, username: str, password: str) -> None:
        if not username.strip() or not password:
            raise G2AuthenticationError("Enter your Halliday account email and password.")
        self.access_token = ""
        password_md5 = hashlib.md5(password.encode("utf-8"), usedforsecurity=False).hexdigest()
        data = self._request("POST", LOGIN_PATH, {
            "codeId": "", "loginType": 1,
            "password": self._crypto._rsa_encrypt(password_md5), "username": username.strip(),
        })
        if not isinstance(data, dict) or not isinstance(data.get("accessToken"), str):
            raise G2AuthenticationError("Sign-in returned no access token. Use an existing G2 token or check your account's sign-in method.")
        self.access_token = self.normalize_token(data["accessToken"])
        if not self.access_token:
            raise G2AuthenticationError("Halliday returned an empty access token.")

    def get_devices(self) -> list[G2Device]:
        return parse_devices(self._request("GET", DEVICES_PATH))

    def check_firmware(self, serial: str, current_code: int) -> G2Offer | None:
        return parse_offer(self._request("POST", CHECK_PATH, {
            "sn": _text(serial, "serial number"),
            "firmwareVersionCode": _integer(current_code, "current firmware code"),
            # PackageInfo.buildNumber is a String in the app's check request.
            "appVersionCode": G2_APP_BUILD,
        }))

    def resolve_package(self, serial: str, offer: G2Offer) -> G2Package:
        return parse_package(self._request("POST", START_PATH, {
            "sn": _text(serial, "serial number"), "targetFirmwareVersionId": offer.version_id,
        }), offer)


def _sha256_file(path: Path, stop: threading.Event | None = None) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            if stop and stop.is_set():
                raise G2Cancelled("Stopped.")
            digest.update(chunk)
    return digest.hexdigest()


def _write_json(path: Path, value: Any) -> None:
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


@contextmanager
def _directory_lock(directory: Path):
    """Avoid duplicate OTA records and index overwrites from two app windows."""
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / ".download.lock").open("a+b") as handle:
        if os.fstat(handle.fileno()).st_size == 0:
            handle.write(b"0")
            handle.flush()
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise HallidayError("Another G2 downloader is using this folder. Wait for its check to finish.") from exc
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


class G2Preserver:
    def __init__(self, session: G2Session, directory: Path) -> None:
        self.session = session
        self.directory = directory.resolve()
        self._lock = threading.Lock()

    @staticmethod
    def _stopped(stop: threading.Event) -> None:
        if stop.is_set():
            raise G2Cancelled("Stopped.")

    def check_and_save(
        self, serial: str, stop: threading.Event, *, fallback_code: int | None = None,
        progress: Callable[[int, int | None], None] | None = None,
        status: Callable[[str], None] | None = None,
    ) -> G2CheckResult:
        if not self._lock.acquire(blocking=False):
            raise HallidayError("A G2 check is already running.")
        try:
            self._stopped(stop)
            with _directory_lock(self.directory):
                return self._check_and_save(serial, stop, fallback_code, progress, status or (lambda _: None))
        finally:
            self._lock.release()

    def _check_and_save(self, serial, stop, fallback_code, progress, status) -> G2CheckResult:
        self._stopped(stop)
        status("Reading your glasses' reported firmware…")
        devices = self.session.get_devices()
        device = next((item for item in devices if item.serial == serial), None)
        if device is None:
            raise G2AuthenticationError("These glasses are no longer listed in this account. Load your devices again.")
        code = device.firmware_code if device.firmware_code is not None else fallback_code
        if code is None:
            raise G2ProtocolError("Halliday has not reported a firmware code for these glasses. Connect them in the phone app, then load devices again.")
        self._stopped(stop)
        status("Checking for an offered firmware version…")
        offer = self.session.check_firmware(device.serial, code)
        if offer is None or offer.version_code <= code:
            return G2CheckResult("no_update", device, offer)
        if offer.minimum_app_build > int(G2_APP_BUILD):
            raise G2ProtocolError(f"This firmware requires app build {offer.minimum_app_build} or newer. The downloader currently matches G2 build {G2_APP_BUILD} and needs updating.")
        self._stopped(stop)
        self.directory.mkdir(parents=True, exist_ok=True)
        device_key = hashlib.sha256(device.serial.encode("utf-8")).hexdigest()
        index_path = self.directory / "index.json"
        if index_path.exists():
            try:
                index = json.loads(index_path.read_text(encoding="utf-8"))
                if not isinstance(index, dict) or index.get("format") != 1 or not isinstance(index.get("packages"), dict):
                    raise ValueError()
            except (ValueError, UnicodeError) as exc:
                raise G2ProtocolError("The saved firmware index is damaged. Keep your files and repair downloads/g2/index.json before continuing.") from exc
        else:
            index = {"format": 1, "packages": {}}
        key = f"{device_key}:{offer.version_id}:{offer.version_code}"
        existing = index["packages"].get(key)
        if isinstance(existing, dict):
            saved = (self.directory / str(existing.get("filename", ""))).resolve()
            if saved.parent == self.directory and saved.is_file():
                status("Verifying the copy already on your PC…")
                digest = _sha256_file(saved, stop)
                if digest == existing.get("sha256") and saved.stat().st_size == existing.get("size"):
                    return G2CheckResult("already_saved", device, offer, saved, digest)
        self._stopped(stop)
        status(f"Resolving firmware {offer.version_name}…")
        package = self.session.resolve_package(device.serial, offer)
        self._stopped(stop)
        version = re.sub(r"[^A-Za-z0-9._-]", "_", offer.version_name)[:60]
        filename = f"halliday-g2-{version}-{offer.version_id}-{package.sha256[:12]}.zip"
        status(f"Downloading firmware {offer.version_name}…")
        result = download_firmware(
            package.url, self.directory, suggested_name=filename,
            expected_checksum=package.sha256, expected_size=package.size,
            progress=progress, cancel_event=stop,
        )
        if result.checksum_ok is not True:
            raise G2IntegrityError(f"SHA-256 did not match. The rejected download is marked: {result.path.name}")
        if result.size != package.size:
            raise G2IntegrityError("The package size did not match Halliday's response.")
        if not zipfile.is_zipfile(result.path):
            rejected = result.path.with_name(result.path.name + ".invalid-package")
            result.path.replace(rejected)
            raise G2IntegrityError(f"The downloaded package is not a readable ZIP: {rejected.name}")
        # Finishing the local commit after a complete verified download is safe
        # even if Stop is pressed here. No further network request is made.
        record = {
            "device_key": device_key, "version_id": offer.version_id,
            "version_name": offer.version_name, "version_code": offer.version_code,
            "channel": offer.channel, "release_notes": list(offer.notes),
            "filename": result.path.name, "size": result.size, "sha256": result.sha256,
            "expected_sha256": package.sha256, "checksum_verified": True,
            "saved_at": datetime.now(timezone.utc).isoformat(), "app_build": G2_APP_BUILD,
            "source_host": urllib.parse.urlsplit(package.url).hostname,
            "ota_record_id": package.record_id,
        }
        # Signed URLs, account tokens, passwords and device bond keys are omitted.
        _write_json(result.path.with_suffix(".json"), record)
        index["packages"][key] = record
        _write_json(index_path, index)
        return G2CheckResult("saved", device, offer, result.path, result.sha256)


def watch_g2(
    preserver: G2Preserver, serial: str, stop: threading.Event, *, interval_seconds: float,
    emit: Callable[[str, Any], None], fallback_code: int | None = None,
) -> None:
    """Run only while the desktop app is open; stop on auth/protocol/integrity errors."""
    if interval_seconds < 60:
        raise ValueError("Choose a check interval of at least one minute.")
    while not stop.is_set():
        delay = interval_seconds
        try:
            result = preserver.check_and_save(
                serial, stop, fallback_code=fallback_code,
                status=lambda message: emit("status", message),
                progress=lambda done, total: emit("progress", (done, total)),
            )
            emit("result", result)
        except G2Cancelled:
            break
        except (G2AuthenticationError, G2ProtocolError, G2IntegrityError) as exc:
            emit("error", str(exc))
            break
        except (HallidayError, OSError) as exc:
            if stop.is_set():
                break
            emit("warning", str(exc))
            # Never hammer a failed service, even with a short user interval.
            delay = max(interval_seconds, 300)
        if stop.is_set():
            break
        emit("next_check", time.time() + delay)
        if stop.wait(delay):
            break
