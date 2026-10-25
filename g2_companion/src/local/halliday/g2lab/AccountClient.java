package local.halliday.g2lab;

import android.os.Build;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import javax.crypto.Cipher;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Reuses the already verified downloader login and reads only the owner's existing bindings. */
final class AccountClient {
    private static final String ORIGIN = "https://halliday-gateway.halliday-tech.com";
    private static final String LOGIN = "/auth/app/login";
    private static final String DEVICES = "/hallidayiot/app/device/bindlist";
    private static final String PUBLIC_KEY =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAg7l1ooPZkkexYNoCqllu" +
        "SQ6oUVxoPLSRu/rk9Qos3PqCkCGHFicS6zKU5LjErgW2+gUtcpjCDIIDc6wWIwBu" +
        "MszjMwpnVyNHEaTwUQTJkiIxs6lGiyw3H3SmRysxciU9/7zFd1AUbWO8JpjctQ4T" +
        "PZvs04dUSJmIThVchH8tzmoEAmW3TFuF02KpUXSsgQRM0rGD2ZTAlRc2yKgrLjBg" +
        "fyP78zyplAMpnYPg9DvaRuPaSArJXtt9vFNe82IklRABHY4K634NytwK7dYkhhFA" +
        "nxlId1E/QtICTYfDvMUepfcsatYxxMz9AxojGF+ocrjsjJ3w7Dix1JK6acQAYSwms" +
        "QIDAQAB";
    private final String deviceId = UUID.randomUUID().toString().replace("-", "");

    static final class OwnedDevice {
        final String name, serial, bleMac, btMac;
        private final String bondKey;
        OwnedDevice(JSONObject info) throws JSONException, IOException {
            name = string(info, "deviceName"); serial = string(info, "sn");
            bleMac = string(info, "bluetoothMac"); btMac = string(info, "btMac");
            bondKey = string(info, "bondKey").trim();
            if (serial.isEmpty() || bondKey.isEmpty() || bondKey.getBytes(StandardCharsets.UTF_8).length > 131)
                throw new IOException("This binding has no usable existing bond key. Open the official G2 app, connect the glasses, then sign in here again.");
        }
        private static String string(JSONObject json, String key) {
            Object value = json.opt(key); return value instanceof String ? (String)value : "";
        }
        public String toString() { return (name.isEmpty() ? "Halliday G2" : name) + " • " + serial.substring(Math.max(0, serial.length() - 6)); }
        boolean matches(String address, String deviceName) {
            String actual = normalizeMac(address);
            String cloudBle = normalizeMac(bleMac), cloudBt = normalizeMac(btMac);
            if (cloudBle.length() == 12 || cloudBt.length() == 12)
                return actual.equals(cloudBle) || actual.equals(cloudBt);
            return !name.isEmpty() && name.equals(deviceName);
        }
        byte[] handshake() throws JSONException {
            // CmdBond is also the official reconnect authentication. Never generate a replacement UUID.
            JSONObject body = new JSONObject();
            body.put("uuid", bondKey); body.put("type", "android");
            body.put("model", "android-" + Build.VERSION.RELEASE);
            long now = System.currentTimeMillis();
            body.put("timestamp", now);
            body.put("timeZoneOffsetInMinutes", TimeZone.getDefault().getOffset(now) / 60000);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    static String normalizeMac(String value) { return value.replace(":", "").replace("-", "").trim().toUpperCase(Locale.ROOT); }

    List<OwnedDevice> signIn(String email, String password) throws Exception {
        if (email.trim().isEmpty() || password.isEmpty()) throw new IOException("Enter your Halliday G2 email and password.");
        String md5 = Protocol.hex(MessageDigest.getInstance("MD5").digest(password.getBytes(StandardCharsets.UTF_8)));
        JSONObject login = new JSONObject(); login.put("codeId", ""); login.put("loginType", 1);
        login.put("password", rsa(md5)); login.put("username", email.trim());
        Object data = request(LOGIN, login, "");
        if (!(data instanceof JSONObject)) throw new IOException("Sign-in response has an unexpected format.");
        Object tokenValue = ((JSONObject)data).opt("accessToken");
        if (!(tokenValue instanceof String) || ((String)tokenValue).isEmpty() || ((String)tokenValue).matches(".*\\s.*"))
            throw new IOException("Halliday returned no usable access token.");
        Object bindings = request(DEVICES, null, (String)tokenValue);
        return parseBindings(bindings);
    }

    static List<OwnedDevice> parseBindings(Object bindings) throws Exception {
        if (bindings instanceof JSONObject) bindings = ((JSONObject)bindings).opt("records");
        if (!(bindings instanceof JSONArray)) throw new IOException("Device list has an unexpected format.");
        List<OwnedDevice> result = new ArrayList<>(); Set<String> serials = new HashSet<>();
        JSONArray rows = (JSONArray)bindings;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            JSONObject info = row.optJSONObject("deviceInfo"); if (info == null) continue;
            if (info.optString("sn", "").isEmpty()) continue;
            OwnedDevice device = new OwnedDevice(info);
            if (!serials.add(device.serial)) throw new IOException("Halliday returned duplicate device records.");
            result.add(device);
        }
        if (result.isEmpty()) throw new IOException("No existing glasses binding was found on this account.");
        return result;
    }

    private static String rsa(String value) throws Exception {
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding"); cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static String randomAesString() {
        String alphabet = "1234567890AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz";
        SecureRandom random = new SecureRandom(); StringBuilder out = new StringBuilder();
        for (int i = 0; i < 16; i++) out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return out.toString();
    }
    private Object request(String path, JSONObject body, String token) throws Exception {
        if (!path.equals(LOGIN) && !path.equals(DEVICES)) throw new IOException("Unsupported account request.");
        HttpsURLConnection connection = (HttpsURLConnection)new URL(ORIGIN + path).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(20000); connection.setReadTimeout(20000);
        connection.setRequestMethod(body == null ? "GET" : "POST");
        connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept-Language", "en-US"); connection.setRequestProperty("X-Timezone", TimeZone.getDefault().getID());
        connection.setRequestProperty("X-App-Version-Name", "1.1.1"); connection.setRequestProperty("X-App-Version-Code", "186");
        connection.setRequestProperty("X-Platform", "android"); connection.setRequestProperty("X-OS-Version", Build.VERSION.RELEASE);
        connection.setRequestProperty("X-OS-SDK", String.valueOf(Build.VERSION.SDK_INT));
        connection.setRequestProperty("X-OS-Manufacturer", Build.MANUFACTURER); connection.setRequestProperty("X-OS-Model", Build.MODEL);
        connection.setRequestProperty("X-Device-Id", deviceId); connection.setRequestProperty("X-Request-Id", UUID.randomUUID().toString());
        connection.setRequestProperty("X-Timestamp", String.valueOf(System.currentTimeMillis()));
        connection.setRequestProperty("User-Agent", "G2DisplayLab/0.3 (owner-authorized research)");
        if (path.equals(LOGIN)) {
            connection.setRequestProperty("xsaeskey", rsa(randomAesString())); connection.setRequestProperty("xsaesiv", rsa(randomAesString()));
        } else connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(encoded.length);
                try (OutputStream stream = connection.getOutputStream()) { stream.write(encoded); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Halliday returned HTTP " + status + ". Check sign-in details or try again later.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (bytes.size() + n > 4 * 1024 * 1024) throw new IOException("Halliday response is too large.");
                    bytes.write(buffer, 0, n);
                }
            }
            JSONObject response = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            int code = response.optInt("code", -1);
            if (code != 200) throw new IOException("Halliday rejected the request (code " + code + "). Check your sign-in details.");
            if (!response.has("data")) throw new IOException("Halliday response is missing data.");
            return response.get("data");
        } finally { connection.disconnect(); }
    }
}
