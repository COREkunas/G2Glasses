from __future__ import annotations

import hashlib
import base64
import hmac
import ipaddress
import json
import os
import queue
import re
import secrets
import shutil
import socket
import ssl
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Iterable


API_ORIGIN = "https://service.halliday-tech.com"
OTA_CHECK_URL = f"{API_ORIGIN}/system/app/firmware/upgrade/check"
OTA_START_URL = f"{API_ORIGIN}/system/app/firmware/upgrade/start"
OTA_STATUS_URL = f"{API_ORIGIN}/system/app/firmware/upgrade/status"
FILE_URL_ENDPOINT = f"{API_ORIGIN}/system/app/file/downloadurl"
LOGIN_URL = f"{API_ORIGIN}/auth/app/login"
DEVICE_BIND_LIST_URL = f"{API_ORIGIN}/system/app/device/bindlist"

USER_AGENT = "HallidayFirmwarePreserver/0.1 (owner-authorized research)"
HALLIDAY_APP_VERSION = "3.6.1"
HALLIDAY_APP_BUILD = "206"
ANDROID_APPS = {"Halliday DWG": "com.halliday.glasses", "Halliday G2": "com.halliday.oasis"}
HALLIDAY_HMAC_KEY = b"sJEqEJD5x3VJT42l"
HALLIDAY_RSA_PUBLIC_KEY = b"""-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAg7l1ooPZkkexYNoCqllu
SQ6oUVxoPLSRu/rk9Qos3PqCkCGHFicS6zKU5LjErgW2+gUtcpjCDIIDc6wWIwBu
MszjMwpnVyNHEaTwUQTJkiIxs6lGiyw3H3SmRysxciU9/7zFd1AUbWO8JpjctQ4T
PZvs04dUSJmIThVchH8tzmoEAmW3TFuF02KpUXSsgQRM0rGD2ZTAlRc2yKgrLjBg
fyP78zyplAMpnYPg9DvaRuPaSArJXtt9vFNe82IklRABHY4K634NytwK7dYkhhFA
nxlId1E/QtICTYfDvMUepfcsatYxxMz9AxojGF+ocrjsjJ3w7Dix1JK6acQAYSwms
QIDAQAB
-----END PUBLIC KEY-----
"""
HALLIDAY_RANDOM_ALPHABET = "1234567890AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz"
SECRET_HEADER_RE = re.compile(
    r"^(authorization|cookie|set-cookie|xsaeskey|xsaesiv|xssign|api[-_]?key)$",
    re.IGNORECASE,
)
JWT_RE = re.compile(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b")
AUTH_TEXT_RE = re.compile(
    r"(?i)(authorization|accessToken|refreshToken|xsaeskey|xsaesiv|xssign)"
    r"([\"']?\s*[:=]\s*[\"']?)([^\s,}\"']+)"
)
URL_RE = re.compile(r"https://[^\s\"'<>]+", re.IGNORECASE)
OTA_LOG_RE = re.compile(
    r"(?i)(httpDownloadOtaFile|httpFetchDownloadUrl|downloadUrl|downloadOtaFile|"
    r"firmware[_ -]?upgrade|checkFirmwareVersion|\bOTA\b|/system/app/file/downloadurl|"
    r"OtaHttpApi|downloadPath|/hallidayiot/app/(?:firmware|ota)/|"
    r"download (?:started|completed|failed), file=)"
)
FIRMWARE_ID_LOG_RE = re.compile(
    r"checkFirmwareVersion->\s*will do:\s*\([^,]*,\s*([^,]+),\s*([^,]+),",
    re.IGNORECASE,
)


class HallidayError(RuntimeError):
    """A user-facing Halliday fetcher error."""


@dataclass(slots=True)
class DownloadResult:
    path: Path
    size: int
    sha256: str
    md5: str
    expected_checksum: str | None
    checksum_ok: bool | None
    content_type: str | None


@dataclass(slots=True)
class HttpResult:
    status: int
    headers: dict[str, str]
    body: bytes
    json_body: Any | None


@dataclass(slots=True)
class HallidayDevice:
    label: str
    serial_number: str
    firmware_uid: str
    account_device_type: str
    firmware_version: str
    raw: dict[str, Any]


def redact_secrets(value: str) -> str:
    value = JWT_RE.sub("<JWT_REDACTED>", value)
    return AUTH_TEXT_RE.sub(lambda match: f"{match.group(1)}{match.group(2)}<REDACTED>", value)


def redact_headers(headers: dict[str, str]) -> dict[str, str]:
    return {
        key: ("<REDACTED>" if SECRET_HEADER_RE.match(key) else redact_secrets(str(value)))
        for key, value in headers.items()
    }


def _load_crypto() -> tuple[Any, Any, Any, Any, Any]:
    try:
        from cryptography.hazmat.primitives import hashes, padding, serialization
        from cryptography.hazmat.primitives.asymmetric import padding as asymmetric_padding
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    except ImportError as exc:
        raise HallidayError(
            "The direct Halliday client needs the 'cryptography' package. "
            "Run: python -m pip install -r requirements.txt"
        ) from exc
    return hashes, padding, serialization, asymmetric_padding, (Cipher, algorithms, modes)


def _dart_value(value: Any) -> str:
    """Mirror the app's recursive value serialization used by its HMAC routine."""
    if isinstance(value, dict):
        items = [f"{key}:{_dart_value(value[key])}" for key in sorted(value)]
        return "{" + ",".join(items) + "}" if items else ""
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(_dart_value(item) for item in value) + "]"
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    return str(value)


def _halliday_signature(raw_body: Any, language: str, timestamp_ms: int) -> str:
    values = {"timestamp": timestamp_ms, "language": language, "body": raw_body}
    signing_text = ",".join(f"{key}:{_dart_value(values[key])}" for key in sorted(values))
    digest = hmac.new(HALLIDAY_HMAC_KEY, signing_text.encode("utf-8"), hashlib.sha256).digest()
    return base64.b64encode(digest).decode("ascii")


def _random_halliday_secret() -> str:
    return "".join(secrets.choice(HALLIDAY_RANDOM_ALPHABET) for _ in range(16))


def _timezone_gmt() -> str:
    offset = datetime.now().astimezone().utcoffset()
    offset_seconds = int(offset.total_seconds()) if offset is not None else 0
    sign = "+" if offset_seconds >= 0 else "-"
    hours, remainder = divmod(abs(offset_seconds), 3600)
    minutes = remainder // 60
    return f"GMT{sign}{hours:02d}:{minutes:02d}"


def _find_string_by_key(value: Any, wanted: set[str]) -> str | None:
    if isinstance(value, dict):
        for key, item in value.items():
            if key.lower() in wanted and isinstance(item, str) and item:
                return item
        for item in value.values():
            found = _find_string_by_key(item, wanted)
            if found:
                return found
    elif isinstance(value, list):
        for item in value:
            found = _find_string_by_key(item, wanted)
            if found:
                return found
    return None


def extract_halliday_devices(value: Any) -> list[HallidayDevice]:
    devices: list[HallidayDevice] = []
    seen: set[str] = set()

    def visit(node: Any) -> None:
        if isinstance(node, dict):
            lowered = {str(key).lower(): item for key, item in node.items()}
            serial = lowered.get("sn") or lowered.get("serialnumber")
            firmware_uid = lowered.get("firmwareuid")
            account_device_type = lowered.get("devicetype") or lowered.get("type")
            version = (
                lowered.get("firmwareversion")
                or lowered.get("versionname")
                or lowered.get("softwareversion")
            )
            if isinstance(serial, str) and serial and serial not in seen:
                seen.add(serial)
                name = lowered.get("devicename") or lowered.get("modelname") or "Halliday Glasses"
                devices.append(
                    HallidayDevice(
                        label=f"{name} — {serial[-6:]}",
                        serial_number=serial,
                        firmware_uid=str(firmware_uid or ""),
                        account_device_type=str(account_device_type or ""),
                        firmware_version=str(version or ""),
                        raw=node,
                    )
                )
            for item in node.values():
                visit(item)
        elif isinstance(node, list):
            for item in node:
                visit(item)

    visit(value)
    return devices


def extract_ble_firmware_identity(line: str) -> tuple[str, str] | None:
    """Extract (firmwareUid, serial) from the official app's pre-OTA debug record."""
    match = FIRMWARE_ID_LOG_RE.search(line)
    if not match:
        return None
    firmware_uid = match.group(1).strip().strip("'\"")
    serial_number = match.group(2).strip().strip("'\"")
    if not firmware_uid or not serial_number:
        return None
    if firmware_uid.lower() in {"null", "none"} or serial_number.lower() in {"null", "none"}:
        return None
    return firmware_uid, serial_number


class HallidaySession:
    """Owner-authorized implementation of the request format used by Halliday DWG 3.6.1."""

    def __init__(
        self,
        *,
        access_token: str = "",
        language: str = "en-US",
        os_version: str = "15",
    ) -> None:
        self.access_token = access_token.strip()
        self.language = language or "en-US"
        self.os_version = os_version or "15"
        self.aes_key = _random_halliday_secret()
        self.aes_iv = _random_halliday_secret()

    def _rsa_encrypt(self, value: str) -> str:
        _, _, serialization, asymmetric_padding, _ = _load_crypto()
        public_key = serialization.load_pem_public_key(HALLIDAY_RSA_PUBLIC_KEY)
        encrypted = public_key.encrypt(value.encode("utf-8"), asymmetric_padding.PKCS1v15())
        return base64.b64encode(encrypted).decode("ascii")

    def _aes_encrypt(self, value: str) -> str:
        _, crypto_padding, _, _, cipher_parts = _load_crypto()
        Cipher, algorithms, modes = cipher_parts
        padder = crypto_padding.PKCS7(128).padder()
        padded = padder.update(value.encode("utf-8")) + padder.finalize()
        encryptor = Cipher(
            algorithms.AES(self.aes_key.encode("utf-8")),
            modes.CBC(self.aes_iv.encode("utf-8")),
        ).encryptor()
        encrypted = encryptor.update(padded) + encryptor.finalize()
        return base64.b64encode(encrypted).decode("ascii")

    def _headers(self, raw_body: Any) -> dict[str, str]:
        timestamp_ms = int(time.time() * 1000)
        headers = {
            "xstimezone": _timezone_gmt(),
            "xslanguage": self.language,
            "xsostype": "1",
            "xsosversion": self.os_version,
            "xsappversioncode": HALLIDAY_APP_BUILD,
            "xsappversionname": HALLIDAY_APP_VERSION,
            "xstimestamp": str(timestamp_ms),
            "xsaeskey": self._rsa_encrypt(self.aes_key),
            "xsaesiv": self._rsa_encrypt(self.aes_iv),
            "xssign": _halliday_signature(raw_body, self.language, timestamp_ms),
            "Accept": "application/json",
        }
        if self.access_token:
            headers["Authorization"] = f"Bearer {self.access_token}"
        return headers

    def request(self, method: str, url: str, raw_body: Any) -> HttpResult:
        method = method.upper()
        headers = self._headers(raw_body)
        if method == "GET":
            query = "&".join(f"{key}={value}" for key, value in raw_body.items()) if raw_body else ""
            request_url = url
            if query:
                encrypted_query = urllib.parse.urlencode({"parameters": self._aes_encrypt(query)})
                request_url = f"{url}?{encrypted_query}"
            return request_http({"method": "GET", "url": request_url, "headers": headers})

        compact_json = json.dumps(raw_body, separators=(",", ":"), ensure_ascii=False)
        encrypted_body = {"body": self._aes_encrypt(compact_json)}
        return request_http(
            {"method": method, "url": url, "headers": headers, "body": encrypted_body}
        )

    def login(self, username: str, password: str) -> HttpResult:
        if not username.strip() or not password:
            raise HallidayError("Enter your Halliday username/email and password.")
        password_md5 = hashlib.md5(password.encode("utf-8"), usedforsecurity=False).hexdigest()
        body = {
            "codeId": "",
            "loginType": 1,
            "password": self._rsa_encrypt(password_md5),
            "username": username.strip(),
        }
        result = self.request("POST", LOGIN_URL, body)
        token = _find_string_by_key(result.json_body, {"token", "accesstoken"})
        if token:
            self.access_token = token
        return result

    def get_bound_devices(self) -> tuple[HttpResult, list[HallidayDevice]]:
        if not self.access_token:
            raise HallidayError("Log in or paste an access token first.")
        result = self.request("GET", DEVICE_BIND_LIST_URL, {})
        return result, extract_halliday_devices(result.json_body)

    def check_firmware(self, *, serial_number: str, device_type: str, version_name: str) -> HttpResult:
        if not self.access_token:
            raise HallidayError("Log in or paste an access token first.")
        if not serial_number.strip() or not device_type.strip() or not version_name.strip():
            raise HallidayError(
                "Serial number, firmware UID (the OTA 'type') and current firmware version are required."
            )
        body = {
            "checkType": 0,
            "sn": serial_number.strip(),
            "type": device_type.strip(),
            "versionName": version_name.strip(),
        }
        return self.request("POST", OTA_CHECK_URL, body)

    def get_download_url(self, file_id: str) -> HttpResult:
        if not self.access_token:
            raise HallidayError("Log in or paste an access token first.")
        if not file_id:
            raise HallidayError("The update response did not contain a firmware file ID.")
        return self.request("GET", FILE_URL_ENDPOINT, {"id": file_id})


def _is_public_ip(address: str) -> bool:
    parsed = ipaddress.ip_address(address)
    return not (
        parsed.is_private
        or parsed.is_loopback
        or parsed.is_link_local
        or parsed.is_multicast
        or parsed.is_reserved
        or parsed.is_unspecified
    )


def validate_public_https_url(url: str, *, allow_halliday_api: bool = True) -> str:
    cleaned = url.strip()
    parsed = urllib.parse.urlsplit(cleaned)
    if parsed.scheme.lower() != "https" or not parsed.hostname:
        raise HallidayError("Only HTTPS download URLs are accepted.")
    if parsed.username or parsed.password:
        raise HallidayError("URLs containing embedded usernames or passwords are rejected.")

    hostname = parsed.hostname.lower().rstrip(".")
    if hostname in {"localhost", "localhost.localdomain"}:
        raise HallidayError("Localhost URLs are rejected.")

    if allow_halliday_api and hostname == "service.halliday-tech.com":
        return cleaned

    try:
        addresses = {item[4][0] for item in socket.getaddrinfo(hostname, 443, type=socket.SOCK_STREAM)}
    except socket.gaierror as exc:
        raise HallidayError(f"Could not resolve download host {hostname}: {exc}") from exc
    if not addresses or any(not _is_public_ip(address) for address in addresses):
        raise HallidayError("The URL resolves to a local or private network address and was rejected.")
    return cleaned


def _safe_filename(value: str | None, fallback: str = "halliday-firmware.bin") -> str:
    candidate = Path(value or fallback).name.strip().strip(".")
    candidate = re.sub(r"[<>:\"/\\|?*\x00-\x1f]", "_", candidate)
    return candidate[:180] or fallback


def _content_disposition_filename(header: str | None) -> str | None:
    if not header:
        return None
    encoded = re.search(r"filename\*=UTF-8''([^;]+)", header, re.IGNORECASE)
    if encoded:
        return urllib.parse.unquote(encoded.group(1))
    plain = re.search(r'filename\s*=\s*"?([^";]+)', header, re.IGNORECASE)
    return plain.group(1).strip() if plain else None


def _unique_path(directory: Path, filename: str) -> Path:
    target = directory / filename
    if not target.exists() and not target.with_suffix(target.suffix + ".part").exists():
        return target
    stem, suffix = target.stem, target.suffix
    for number in range(1, 10_000):
        candidate = directory / f"{stem}-{number}{suffix}"
        if not candidate.exists() and not candidate.with_suffix(candidate.suffix + ".part").exists():
            return candidate
    raise HallidayError("Could not create a unique output filename.")


def _normalize_checksum(value: str | None) -> str | None:
    if not value:
        return None
    normalized = re.sub(r"(?i)^(md5|sha256)\s*[:=]\s*", "", value.strip())
    normalized = re.sub(r"[^0-9a-fA-F]", "", normalized).lower()
    return normalized or None


class _PublicDownloadRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_public_https_url(newurl, allow_halliday_api=False)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download_firmware(
    url: str,
    output_directory: Path,
    *,
    suggested_name: str | None = None,
    expected_checksum: str | None = None,
    expected_size: int | None = None,
    progress: Callable[[int, int | None], None] | None = None,
    cancel_event: threading.Event | None = None,
) -> DownloadResult:
    if expected_size is not None and (type(expected_size) is not int or expected_size <= 0):
        raise HallidayError("Expected package size must be a positive number of bytes.")
    if cancel_event and cancel_event.is_set():
        raise HallidayError("Download cancelled.")
    safe_url = validate_public_https_url(url, allow_halliday_api=False)
    output_directory.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(
        safe_url,
        headers={"User-Agent": USER_AGENT, "Accept": "application/octet-stream,*/*;q=0.8"},
        method="GET",
    )

    context = ssl.create_default_context()
    try:
        opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=context), _PublicDownloadRedirect(),
        )
        response = opener.open(request, timeout=45)
    except urllib.error.HTTPError as exc:
        raise HallidayError(f"Firmware server returned HTTP {exc.code}: {exc.reason}") from exc
    except urllib.error.URLError as exc:
        raise HallidayError(f"Could not open firmware URL: {exc.reason}") from exc

    with response:
        final_url = response.geturl()
        validate_public_https_url(final_url)
        disposition_name = _content_disposition_filename(response.headers.get("Content-Disposition"))
        path_name = urllib.parse.unquote(Path(urllib.parse.urlsplit(final_url).path).name)
        filename = _safe_filename(suggested_name or disposition_name or path_name)
        target = _unique_path(output_directory, filename)
        partial = target.with_suffix(target.suffix + ".part")
        total_header = response.headers.get("Content-Length")
        total = int(total_header) if total_header and total_header.isdigit() else None
        if expected_size is not None and total is not None and total != expected_size:
            raise HallidayError("The server's download size differs from the offered package size.")
        sha256 = hashlib.sha256()
        md5 = hashlib.md5(usedforsecurity=False)
        received = 0

        try:
            with partial.open("xb") as output:
                while True:
                    if cancel_event and cancel_event.is_set():
                        raise HallidayError("Download cancelled.")
                    chunk = response.read(256 * 1024)
                    if not chunk:
                        break
                    output.write(chunk)
                    sha256.update(chunk)
                    md5.update(chunk)
                    received += len(chunk)
                    if expected_size is not None and received > expected_size:
                        raise HallidayError("The download exceeded the offered package size.")
                    if progress:
                        progress(received, total)
                if (total is not None and received != total) or (expected_size is not None and received != expected_size):
                    raise HallidayError("The firmware download is incomplete; no completed package was saved.")
        except Exception:
            partial.unlink(missing_ok=True)
            raise

    sha256_hex = sha256.hexdigest()
    md5_hex = md5.hexdigest()
    normalized = _normalize_checksum(expected_checksum)
    checksum_ok: bool | None = None
    if normalized:
        actual = md5_hex if len(normalized) == 32 else sha256_hex if len(normalized) == 64 else ""
        checksum_ok = bool(actual) and actual == normalized

    final_target = target
    if checksum_ok is False:
        final_target = target.with_name(target.name + ".checksum-mismatch")
    partial.replace(final_target)
    return DownloadResult(
        path=final_target,
        size=received,
        sha256=sha256_hex,
        md5=md5_hex,
        expected_checksum=normalized,
        checksum_ok=checksum_ok,
        content_type=response.headers.get("Content-Type"),
    )


def request_http(spec: dict[str, Any], *, timeout: int = 30) -> HttpResult:
    method = str(spec.get("method", "GET")).upper()
    url = validate_public_https_url(str(spec.get("url", "")))
    if urllib.parse.urlsplit(url).hostname != "service.halliday-tech.com":
        raise HallidayError("API replay is restricted to service.halliday-tech.com.")

    supplied_headers = spec.get("headers") or {}
    if not isinstance(supplied_headers, dict):
        raise HallidayError("Request headers must be a JSON object.")
    headers = {str(key): str(value) for key, value in supplied_headers.items()}
    headers.setdefault("User-Agent", USER_AGENT)
    body_value = spec.get("body")
    data: bytes | None
    if body_value is None:
        data = None
    elif isinstance(body_value, (dict, list)):
        data = json.dumps(body_value, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        headers.setdefault("Content-Type", "application/json")
    else:
        data = str(body_value).encode("utf-8")

    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    context = ssl.create_default_context()
    try:
        response = urllib.request.urlopen(request, timeout=timeout, context=context)
    except urllib.error.HTTPError as exc:
        response = exc
    except urllib.error.URLError as exc:
        raise HallidayError(f"Halliday API request failed: {exc.reason}") from exc

    with response:
        body = response.read(4 * 1024 * 1024)
        response_headers = dict(response.headers.items())
        status = int(response.status)
    try:
        json_body = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        json_body = None
    return HttpResult(status=status, headers=response_headers, body=body, json_body=json_body)


def find_download_urls(value: Any) -> list[tuple[str, str | None, str | None]]:
    found: list[tuple[str, str | None, str | None]] = []

    def visit(node: Any) -> None:
        if isinstance(node, dict):
            url = next(
                (
                    item
                    for key, item in node.items()
                    if key.lower() in {"downloadurl", "downloadpath", "fileurl", "firmwareurl", "url"}
                    and isinstance(item, str)
                    and item.startswith("https://")
                ),
                None,
            )
            if url:
                filename = next(
                    (str(item) for key, item in node.items() if key.lower() in {"filename", "name"}),
                    None,
                )
                checksum = next(
                    (
                        str(item)
                        for key, item in node.items()
                        if key.lower() in {"filechecksum", "checksum", "checkcode", "md5", "sha256"}
                    ),
                    None,
                )
                found.append((url, filename, checksum))
            for item in node.values():
                visit(item)
        elif isinstance(node, list):
            for item in node:
                visit(item)

    visit(value)
    unique: list[tuple[str, str | None, str | None]] = []
    seen: set[str] = set()
    for candidate in found:
        if candidate[0] not in seen:
            seen.add(candidate[0])
            unique.append(candidate)
    return unique


def g2_download_from_log(line: str) -> tuple[str, str | None] | None:
    """Recognize an explicit G2 package URL, never an API/check endpoint."""
    match = re.search(r'''(?i)\bdownloadPath["']?\s*[:=]\s*["']?(https://[^\s"'<>]+)''', line)
    if not match:
        return None
    url = match.group(1).rstrip(".,);}]")
    checksum = re.search(r'''(?i)\bcheckCode["']?\s*[:=]\s*["']?((?:sha256[:=]\s*)?[0-9a-f]{64})\b''', line)
    return url, checksum.group(1) if checksum else None


def g2_downloads_from_text(text: str) -> list[tuple[str, str | None, str | None]]:
    """Read an owner-provided start-update response or exported app log."""
    try:
        decoded = json.loads(text)
    except json.JSONDecodeError:
        decoded = None
    if decoded is not None:
        return find_download_urls(decoded)
    offers: dict[str, tuple[str, str | None, str | None]] = {}
    for line in text.splitlines():
        item = g2_download_from_log(line)
        if item:
            offers.pop(item[0], None)
            offers[item[0]] = (item[0], None, item[1])
    return list(offers.values())


def discover_adb() -> Path | None:
    located = shutil.which("adb") or shutil.which("adb.exe")
    if located:
        return Path(located)
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        candidate = Path(local_app_data) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        if candidate.exists():
            return candidate
    return None


def adb_devices(adb: Path) -> list[tuple[str, str]]:
    result = subprocess.run(
        [str(adb), "devices", "-l"], capture_output=True, text=True, timeout=10, check=False
    )
    devices: list[tuple[str, str]] = []
    for line in result.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 2 or fields[1] != "device":
            continue
        serial = fields[0]
        model_match = re.search(r"\bmodel:([^\s]+)", line)
        devices.append((serial, model_match.group(1) if model_match else "Android device"))
    return devices


def adb_package_status(
    adb: Path, serial: str, package: str = "com.halliday.glasses"
) -> dict[str, str]:
    if package not in ANDROID_APPS.values():
        raise HallidayError("Select a supported Halliday app.")
    result = subprocess.run(
        [str(adb), "-s", serial, "shell", "dumpsys", "package", package],
        capture_output=True,
        text=True,
        timeout=15,
        check=False,
    )
    if "versionName=" not in result.stdout:
        return {"installed": "no"}
    version = re.search(r"\bversionName=([^\s]+)", result.stdout)
    version_code = re.search(r"\bversionCode=(\d+)", result.stdout)
    process = subprocess.run(
        [str(adb), "-s", serial, "shell", "pidof", package],
        capture_output=True,
        text=True,
        timeout=10,
        check=False,
    )
    return {
        "installed": "yes",
        "version": version.group(1) if version else "unknown",
        "versionCode": version_code.group(1) if version_code else "unknown",
        "pid": process.stdout.strip(),
    }


def iter_ota_logcat(
    adb: Path,
    serial: str,
    stop_event: threading.Event,
    package: str = "com.halliday.glasses",
) -> Iterable[tuple[str, list[str]]]:
    status = adb_package_status(adb, serial, package)
    process_id = status.get("pid", "")
    if not process_id:
        name = next(name for name, value in ANDROID_APPS.items() if value == package)
        raise HallidayError(f"{name} is not running. Open the app on the phone first.")
    process_id = process_id.split()[0]

    command = [str(adb), "-s", serial, "logcat", f"--pid={process_id}", "-v", "brief"]
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    lines: queue.Queue[str | None] = queue.Queue()

    def read_lines() -> None:
        try:
            assert process.stdout is not None
            for line in process.stdout:
                if OTA_LOG_RE.search(line):
                    lines.put(line)
        finally:
            lines.put(None)

    reader = threading.Thread(target=read_lines, daemon=True)
    reader.start()
    capture_finished = threading.Event()

    def stop_logcat() -> None:
        # The consumer may be downloading a package between yielded log records.
        # Stop still needs to terminate the phone log stream immediately.
        while not capture_finished.wait(0.25):
            if stop_event.is_set():
                process.terminate()
                return

    stopper = threading.Thread(target=stop_logcat, daemon=True)
    stopper.start()
    try:
        while not stop_event.is_set():
            try:
                line = lines.get(timeout=0.25)
            except queue.Empty:
                continue
            if line is None:
                break
            if not OTA_LOG_RE.search(line):
                continue
            urls = [match.rstrip(".,);]") for match in URL_RE.findall(line)]
            yield redact_secrets(line.rstrip()), urls
    finally:
        capture_finished.set()
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=3)
        reader.join(timeout=1)
        stopper.join(timeout=1)
