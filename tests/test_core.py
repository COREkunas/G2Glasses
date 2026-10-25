from __future__ import annotations

import base64
import json
import queue
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from halliday_fetcher.core import (
    FILE_URL_ENDPOINT,
    HALLIDAY_APP_BUILD,
    HALLIDAY_APP_VERSION,
    HallidaySession,
    HttpResult,
    OTA_LOG_RE,
    _dart_value,
    _timezone_gmt,
    extract_ble_firmware_identity,
    extract_halliday_devices,
    find_download_urls,
    g2_download_from_log,
    g2_downloads_from_text,
    iter_ota_logcat,
)


class HallidayProtocolTests(unittest.TestCase):
    def test_dart_signature_value_format(self) -> None:
        self.assertEqual(_dart_value({}), "")
        self.assertEqual(_dart_value({"z": 2, "a": [True, None]}), "{a:[true,null],z:2}")

    def test_timezone_header_format(self) -> None:
        self.assertRegex(_timezone_gmt(), r"^GMT[+-]\d{2}:\d{2}$")

    def test_headers_contain_encrypted_session_material(self) -> None:
        session = HallidaySession(access_token="owner-token")
        headers = session._headers({})

        self.assertEqual(headers["Authorization"], "Bearer owner-token")
        self.assertEqual(headers["xsappversioncode"], HALLIDAY_APP_BUILD)
        self.assertEqual(headers["xsappversionname"], HALLIDAY_APP_VERSION)
        self.assertEqual(len(base64.b64decode(headers["xsaeskey"])), 256)
        self.assertEqual(len(base64.b64decode(headers["xsaesiv"])), 256)
        self.assertEqual(len(base64.b64decode(headers["xssign"])), 32)

    def test_aes_body_round_trip(self) -> None:
        session = HallidaySession()
        cleartext = '{"checkType":0,"sn":"owned-device"}'
        encrypted = base64.b64decode(session._aes_encrypt(cleartext))
        decryptor = Cipher(
            algorithms.AES(session.aes_key.encode("utf-8")),
            modes.CBC(session.aes_iv.encode("utf-8")),
        ).decryptor()
        padded = decryptor.update(encrypted) + decryptor.finalize()
        unpadder = padding.PKCS7(128).unpadder()
        decoded = unpadder.update(padded) + unpadder.finalize()
        self.assertEqual(decoded.decode("utf-8"), cleartext)

    def test_get_query_and_post_body_are_wrapped(self) -> None:
        response = HttpResult(200, {}, b"{}", {})
        session = HallidaySession(access_token="owner-token")
        with patch("halliday_fetcher.core.request_http", return_value=response) as send:
            session.get_download_url("file-123")
            get_spec = send.call_args.args[0]
            self.assertTrue(get_spec["url"].startswith(FILE_URL_ENDPOINT + "?parameters="))
            self.assertNotIn("file-123", get_spec["url"])

        with patch("halliday_fetcher.core.request_http", return_value=response) as send:
            session.check_firmware(
                serial_number="owned-device", device_type="Halliday", version_name="1.00.20.03"
            )
            post_spec = send.call_args.args[0]
            self.assertEqual(post_spec["method"], "POST")
            self.assertEqual(set(post_spec["body"]), {"body"})
            self.assertNotIn("owned-device", json.dumps(post_spec["body"]))

    def test_bound_device_extraction(self) -> None:
        value = {
            "data": [
                {
                    "deviceName": "My Halliday",
                    "sn": "SN-123456",
                    "deviceType": "1",
                    "firmwareUid": "PLATO-FW-UID",
                    "firmwareVersion": "1.00.20.03",
                }
            ]
        }
        devices = extract_halliday_devices(value)
        self.assertEqual(len(devices), 1)
        self.assertEqual(devices[0].serial_number, "SN-123456")
        self.assertEqual(devices[0].firmware_uid, "PLATO-FW-UID")
        self.assertEqual(devices[0].account_device_type, "1")

    def test_ble_identity_extraction(self) -> None:
        line = "checkFirmwareVersion-> will do: (state, PLATO-FW-UID, SN-123456, Halliday Glasses)"
        self.assertEqual(extract_ble_firmware_identity(line), ("PLATO-FW-UID", "SN-123456"))

    def test_g2_download_response_keeps_hash_with_url(self) -> None:
        checksum = "sha256:" + "a" * 64
        response = {"data": {"downloadPath": "https://example.com/firmware.zip", "checkCode": checksum}}
        self.assertEqual(find_download_urls(response), [("https://example.com/firmware.zip", None, checksum)])
        self.assertEqual(find_download_urls({"downloadPath": "/private/ota.zip"}), [])

    def test_g2_logs_and_android_rotation_noise(self) -> None:
        self.assertTrue(OTA_LOG_RE.search("POST /hallidayiot/app/firmware/upgrade/check"))
        self.assertTrue(OTA_LOG_RE.search("OtaHttpApi: download completed, file=/cache/ota.zip"))
        self.assertFalse(OTA_LOG_RE.search("InsetsController rotation=0"))

    def test_g2_auto_capture_requires_explicit_package_field(self) -> None:
        self.assertIsNone(g2_download_from_log("OTA POST https://example.com/hallidayiot/app/firmware/upgrade/check"))
        self.assertIsNone(g2_download_from_log("downloadPath: /data/user/0/com.halliday.oasis/cache/ota.zip"))
        digest = "b" * 64
        line = json.dumps({"downloadPath": "https://example.com/ota.zip?signature=abc", "checkCode": "sha256:" + digest})
        self.assertEqual(g2_download_from_log(line), ("https://example.com/ota.zip?signature=abc", "sha256:" + digest))

    def test_g2_import_start_response_and_log(self) -> None:
        digest = "c" * 64
        start = {"code": 200, "data": {"recordId": 12, "downloadPath": "https://example.com/ota.zip", "packageSize": 100, "checkCode": digest}}
        self.assertEqual(g2_downloads_from_text(json.dumps(start)), [("https://example.com/ota.zip", None, digest)])
        self.assertEqual(g2_downloads_from_text('{"data":{"firmwareVersionId":12}}'), [])
        log = "Https | data: {downloadPath: https://example.com/old.zip}\nHttps | data: {downloadPath: https://example.com/ota.zip, checkCode: " + digest + "}\n"
        self.assertEqual(g2_downloads_from_text(log)[-1], ("https://example.com/ota.zip", None, digest))

    def test_g2_capture_stops_even_when_phone_is_silent(self) -> None:
        # Model a silent phone whose stdout only unblocks after logcat terminates.
        class SilentProcess:
            def __init__(self):
                self.finished = threading.Event()
                self.stdout = self.lines()

            def lines(self):
                self.finished.wait(3)
                return
                yield ""

            def terminate(self):
                self.finished.set()

            def wait(self, timeout):
                return 0

        process = SilentProcess()
        stop = threading.Event()
        failures = queue.Queue()

        def capture():
            try:
                list(iter_ota_logcat(Path("adb"), "phone", stop, "com.halliday.oasis"))
            except Exception as exc:
                failures.put(exc)

        with patch("halliday_fetcher.core.adb_package_status", return_value={"pid": "42"}) as status:
            with patch("halliday_fetcher.core.subprocess.Popen", return_value=process) as launch:
                worker = threading.Thread(target=capture, daemon=True)
                worker.start()
                time.sleep(0.05)
                stop.set()
                worker.join(1)
                self.assertFalse(worker.is_alive())
                self.assertTrue(failures.empty())
                status.assert_called_once_with(Path("adb"), "phone", "com.halliday.oasis")
                self.assertIn("--pid=42", launch.call_args.args[0])
                self.assertTrue(process.finished.is_set())

    def test_stop_releases_phone_stream_while_consumer_is_busy(self) -> None:
        # Auto-download can pause the generator after receiving a signed URL.
        class PausedProcess:
            def __init__(self):
                self.stdout = iter(["downloadPath: https://example.com/ota.zip\n"])
                self.terminated = threading.Event()

            def terminate(self):
                self.terminated.set()

            def wait(self, timeout):
                return 0

        process = PausedProcess()
        stop = threading.Event()
        with patch("halliday_fetcher.core.adb_package_status", return_value={"pid": "42"}):
            with patch("halliday_fetcher.core.subprocess.Popen", return_value=process):
                records = iter_ota_logcat(Path("adb"), "phone", stop, "com.halliday.oasis")
                _line, urls = next(records)
                self.assertEqual(urls, ["https://example.com/ota.zip"])
                stop.set()
                self.assertTrue(process.terminated.wait(1))
                records.close()


if __name__ == "__main__":
    unittest.main()
