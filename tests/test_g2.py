from __future__ import annotations

import base64
import hashlib
import io
import json
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from halliday_fetcher.core import HallidayError, download_firmware, _PublicDownloadRedirect
from halliday_fetcher.g2 import (
    CHECK_PATH, DEVICES_PATH, G2_ORIGIN, LOGIN_PATH, START_PATH,
    G2AuthenticationError, G2Cancelled, G2Device, G2IntegrityError,
    G2Offer, G2Package, G2Preserver, G2ProtocolError, G2Session,
    parse_devices, parse_offer, parse_package, watch_g2, _directory_lock,
)


class Response(io.BytesIO):
    def __init__(self, content: bytes, status=200, headers=None):
        super().__init__(content)
        self.status = status
        self.headers = headers or {}

    def geturl(self):
        return "https://firmware.example/ota.zip"


def envelope(data, code=200, status=200):
    return Response(json.dumps({"code": code, "data": data}).encode(), status)


DEVICE = G2Device("owned-serial", "My G2", "1.0.9", 109)
OFFER = G2Offer(17, "1.1.1", 111, "STABLE", ("Fixes",))


class G2ProtocolTests(unittest.TestCase):
    def test_login_encrypts_password_but_not_body_and_retains_only_token(self):
        private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        pem = private.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
        session = G2Session()
        session._opener = Mock()
        session._opener.open.return_value = envelope({"accessToken": "owner-token", "consumer": {"email": "encrypted-profile"}})
        with patch("halliday_fetcher.core.HALLIDAY_RSA_PUBLIC_KEY", pem):
            session.login("owner@example.test", "test-password")
        request = session._opener.open.call_args.args[0]
        body = json.loads(request.data)
        headers = {key.lower(): value for key, value in request.header_items()}
        self.assertEqual(request.full_url, G2_ORIGIN + LOGIN_PATH)
        self.assertEqual(set(body), {"codeId", "loginType", "password", "username"})
        self.assertEqual(body["loginType"], 1)
        actual = private.decrypt(base64.b64decode(body["password"]), padding.PKCS1v15())
        self.assertEqual(actual, hashlib.md5(b"test-password").hexdigest().encode())
        self.assertEqual(private.decrypt(base64.b64decode(headers["xsaeskey"]), padding.PKCS1v15()), session._crypto.aes_key.encode())
        self.assertNotIn("authorization", headers)
        self.assertNotIn("xssign", headers)
        self.assertEqual(headers["x-app-version-code"], "186")
        self.assertEqual(session.access_token, "owner-token")

    def test_g2_flow_uses_actual_numeric_code_and_returns_owned_device(self):
        session = G2Session(access_token="owner-token")
        session._opener = Mock()
        session._opener.open.side_effect = [
            envelope({"records": [{"sn": DEVICE.serial, "deviceName": DEVICE.name,
                                   "firmwareVersion": "1.0.9", "firmwareVersionCode": 109}]}),
            envelope({"firmwareVersionId": 17, "firmwareVersionName": "1.1.1", "firmwareVersionCode": 111}),
            envelope({"recordId": "500", "downloadPath": "https://firmware.example/update.zip", "packageSize": "1234",
                      "checkCode": "a" * 64, "targetFirmwareVersionCode": 111}),
        ]
        self.assertEqual(session.get_devices(), [DEVICE])
        offer = session.check_firmware(DEVICE.serial, DEVICE.firmware_code)
        package = session.resolve_package(DEVICE.serial, offer)
        self.assertEqual(package.size, 1234)
        requests = [call.args[0] for call in session._opener.open.call_args_list]
        self.assertEqual([r.full_url for r in requests], [G2_ORIGIN + path for path in [DEVICES_PATH, CHECK_PATH, START_PATH]])
        self.assertEqual(json.loads(requests[1].data), {"sn": DEVICE.serial, "firmwareVersionCode": 109, "appVersionCode": "186"})
        self.assertEqual(json.loads(requests[2].data), {"sn": DEVICE.serial, "targetFirmwareVersionId": 17})
        self.assertEqual(requests[1].get_header("Authorization"), "Bearer owner-token")
        self.assertNotIn("body", json.loads(requests[1].data))

    def test_session_expiry_and_business_error_are_not_no_update(self):
        for code in [401, 403]:
            session = G2Session(access_token="expired")
            session._opener = Mock()
            session._opener.open.return_value = envelope(None, code)
            with self.assertRaises(G2AuthenticationError):
                session.get_devices()
            self.assertEqual(session.access_token, "")
        session = G2Session(access_token="owner")
        session._opener = Mock()
        session._opener.open.return_value = envelope(None, 500)
        with self.assertRaises(G2ProtocolError):
            session.check_firmware(DEVICE.serial, 109)

    def test_unknown_endpoints_and_redirects_are_rejected(self):
        session = G2Session(access_token="private-token")
        with self.assertRaises(G2ProtocolError):
            session._request("POST", "/hallidayiot/app/device/unbind", {})
        redirect = next(h for h in session._opener.handlers if type(h).__name__ == "_NoRedirect")
        with self.assertRaises(G2ProtocolError):
            redirect.redirect_request(None, None, 307, "", {}, "https://other.example/")

    def test_gateway_block_is_not_misreported_as_bad_credentials(self):
        session = G2Session(access_token="owner-token")
        session._opener = Mock()
        session._opener.open.return_value = Response(b'{"error_name":"blocked"}', status=403)
        with self.assertRaises(G2ProtocolError):
            session.get_devices()
        self.assertEqual(session.access_token, "owner-token")

    def test_missing_code_is_not_guessed_from_version_name(self):
        device = parse_devices([{"sn": "owner", "firmwareVersion": "1.1.1"}])[0]
        self.assertIsNone(device.firmware_code)
        with self.assertRaises(G2ProtocolError):
            parse_offer({"firmwareVersionId": 17, "firmwareVersionName": "1.1.1"})
        self.assertIsNone(parse_offer(None))

    def test_bound_device_info_is_nested_inside_binding_record(self):
        data = {"records": [{"deviceInfo": None}, {"id": 123, "consumerId": "owner", "bondKey": "private",
                            "deviceInfo": {"sn": DEVICE.serial, "deviceName": DEVICE.name,
                                           "firmwareVersion": "1.0.9", "firmwareVersionCode": 109}}]}
        self.assertEqual(parse_devices(data), [DEVICE])
        with self.assertRaises(G2ProtocolError):
            parse_devices([{"deviceInfo": None, "sn": "do-not-use-binding-serial"}])

    def test_wrong_package_version_and_missing_hash_rejected(self):
        value = {"recordId": 12, "downloadPath": "https://firmware.example/ota.zip", "packageSize": 12,
                 "checkCode": "a" * 64, "targetFirmwareVersionCode": 110}
        with self.assertRaises(G2ProtocolError):
            parse_package(value, OFFER)
        value["targetFirmwareVersionCode"] = 111
        for invalid in ["", "a" * 32, "sha256:xyz", "https://other.example/a"]:
            value["checkCode"] = invalid
            with self.assertRaises(G2ProtocolError):
                parse_package(value, OFFER)


class G2PreservationTests(unittest.TestCase):
    def setUp(self):
        self.test_root = Path(__file__).resolve().parents[1] / "tmp"
        self.test_root.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="g2-tests-", dir=self.test_root)
        self.directory = Path(self.temp.name).resolve()
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            archive.writestr("firmware.bin", b"synthetic firmware fixture")
        self.content = output.getvalue()
        self.digest = hashlib.sha256(self.content).hexdigest()
        self.package = G2Package(123, "https://firmware.example/ota.zip?secret=signed", len(self.content), self.digest, 111)
        self.session = Mock()
        self.session.get_devices.return_value = [DEVICE]
        self.session.check_firmware.return_value = OFFER
        self.session.resolve_package.return_value = self.package
        self.preserver = G2Preserver(self.session, self.directory)
        self.stop = threading.Event()
        self.url_validation = patch("halliday_fetcher.core.validate_public_https_url", side_effect=lambda url, **kwargs: url)
        self.url_validation.start()
        self.opener_patch = patch("halliday_fetcher.core.urllib.request.build_opener")
        self.opener = self.opener_patch.start().return_value
        self.opener.open.side_effect = lambda *a, **k: Response(self.content, headers={"Content-Length": str(len(self.content))})

    def tearDown(self):
        self.opener_patch.stop()
        self.url_validation.stop()
        self.assertTrue(self.directory.is_relative_to(self.test_root))
        self.temp.cleanup()

    def test_complete_download_hash_manifest_and_restart_deduplication(self):
        result = self.preserver.check_and_save(DEVICE.serial, self.stop)
        self.assertEqual(result.status, "saved")
        self.assertEqual(result.path.read_bytes(), self.content)
        manifest = json.loads(result.path.with_suffix(".json").read_text())
        self.assertTrue(manifest["checksum_verified"])
        self.assertEqual(manifest["size"], len(self.content))
        self.assertEqual(manifest["sha256"], self.digest)
        persisted = "".join(p.read_text() for p in self.directory.glob("*.json"))
        self.assertNotIn("secret=signed", persisted)
        self.assertNotIn(DEVICE.serial, persisted)
        second = G2Preserver(self.session, self.directory).check_and_save(DEVICE.serial, self.stop)
        self.assertEqual(second.status, "already_saved")
        self.assertEqual(self.session.resolve_package.call_count, 1)
        self.assertEqual(self.opener.open.call_count, 1)

    def test_corrupt_saved_copy_is_preserved_and_downloaded_again(self):
        first = self.preserver.check_and_save(DEVICE.serial, self.stop)
        first.path.write_bytes(b"damaged")
        second = self.preserver.check_and_save(DEVICE.serial, self.stop)
        self.assertEqual(second.status, "saved")
        self.assertNotEqual(first.path, second.path)
        self.assertEqual(first.path.read_bytes(), b"damaged")
        self.assertEqual(second.path.read_bytes(), self.content)

    def test_same_or_older_version_does_not_start_download(self):
        self.session.get_devices.return_value = [G2Device(DEVICE.serial, "G2", "1.1.1", 111)]
        self.assertEqual(self.preserver.check_and_save(DEVICE.serial, self.stop).status, "no_update")
        self.session.resolve_package.assert_not_called()

    def test_unbound_device_is_rejected_before_check(self):
        self.session.get_devices.return_value = []
        with self.assertRaises(G2AuthenticationError):
            self.preserver.check_and_save(DEVICE.serial, self.stop)
        self.session.check_firmware.assert_not_called()

    def test_stop_after_check_prevents_start_request(self):
        def check(*args):
            self.stop.set()
            return OFFER
        self.session.check_firmware.side_effect = check
        with self.assertRaises(G2Cancelled):
            self.preserver.check_and_save(DEVICE.serial, self.stop)
        self.session.resolve_package.assert_not_called()

    def test_checksum_failure_never_records_package_as_saved(self):
        self.session.resolve_package.return_value = G2Package(1, self.package.url, len(self.content), "0" * 64, 111)
        with self.assertRaises(G2IntegrityError):
            self.preserver.check_and_save(DEVICE.serial, self.stop)
        self.assertFalse((self.directory / "index.json").exists())
        self.assertEqual(len(list(self.directory.glob("*.checksum-mismatch"))), 1)

    def test_truncated_download_removes_partial_and_never_records_success(self):
        self.opener.open.side_effect = lambda *a, **k: Response(self.content[:10], headers={"Content-Length": str(len(self.content))})
        with self.assertRaises(HallidayError):
            self.preserver.check_and_save(DEVICE.serial, self.stop)
        self.assertEqual(list(self.directory.glob("*.zip*")), [])
        self.assertFalse((self.directory / "index.json").exists())

    def test_non_zip_package_is_not_indexed(self):
        self.content = b"<html>unexpected server page</html>"
        self.session.resolve_package.return_value = G2Package(1, self.package.url, len(self.content), hashlib.sha256(self.content).hexdigest(), 111)
        with self.assertRaises(G2IntegrityError):
            self.preserver.check_and_save(DEVICE.serial, self.stop)
        self.assertFalse((self.directory / "index.json").exists())
        self.assertEqual(len(list(self.directory.glob("*.invalid-package"))), 1)

    def test_size_mismatch_before_download_does_not_leave_files(self):
        self.session.resolve_package.return_value = G2Package(1, self.package.url, len(self.content) + 1, self.digest, 111)
        with self.assertRaises(HallidayError):
            self.preserver.check_and_save(DEVICE.serial, self.stop)
        self.assertEqual(list(self.directory.glob("*.zip*")), [])

    def test_second_window_cannot_resolve_or_overwrite_the_same_library(self):
        with _directory_lock(self.directory):
            with self.assertRaises(HallidayError):
                G2Preserver(self.session, self.directory).check_and_save(DEVICE.serial, self.stop)
        self.session.resolve_package.assert_not_called()

    def test_newer_required_app_build_stops_before_start(self):
        self.session.check_firmware.return_value = G2Offer(17, "1.1.1", 111, "STABLE", (), 200)
        with self.assertRaises(G2ProtocolError):
            self.preserver.check_and_save(DEVICE.serial, self.stop)
        self.session.resolve_package.assert_not_called()


class WatchTests(unittest.TestCase):
    def test_auth_failure_stops_without_repeated_requests(self):
        preserver, emit = Mock(), Mock()
        preserver.check_and_save.side_effect = G2AuthenticationError("Sign in again")
        watch_g2(preserver, "owner", threading.Event(), interval_seconds=60, emit=emit)
        self.assertEqual(preserver.check_and_save.call_count, 1)
        emit.assert_called_once_with("error", "Sign in again")

    def test_stop_wakes_idle_watch_without_another_request(self):
        stop, waiting = threading.Event(), threading.Event()
        preserver = Mock()
        preserver.check_and_save.return_value = "done"
        def emit(kind, value):
            if kind == "next_check":
                waiting.set()
        thread = threading.Thread(target=watch_g2, args=(preserver, "owner", stop),
                                  kwargs={"interval_seconds": 3600, "emit": emit}, daemon=True)
        thread.start()
        self.assertTrue(waiting.wait(2))
        stop.set()
        thread.join(1)
        self.assertFalse(thread.is_alive())
        self.assertEqual(preserver.check_and_save.call_count, 1)

    def test_download_redirect_checked_before_following(self):
        with patch("halliday_fetcher.core.validate_public_https_url", side_effect=HallidayError("Private address")):
            with self.assertRaises(HallidayError):
                _PublicDownloadRedirect().redirect_request(None, None, 302, "", {}, "https://127.0.0.1/private")


if __name__ == "__main__":
    unittest.main()
