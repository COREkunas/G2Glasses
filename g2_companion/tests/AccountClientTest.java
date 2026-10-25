package local.halliday.g2lab;

import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Synthetic-only tests run on Android's JSON implementation. No network requests. */
public final class AccountClientTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++; if (!ok) throw new AssertionError(label);
    }
    private static JSONObject info() throws Exception {
        return new JSONObject().put("deviceName", "Halliday-G2-TEST").put("sn", "synthetic-123456")
            .put("bluetoothMac", "AA:BB:CC:DD:EE:FF").put("btMac", "AA:BB:CC:DD:EE:00").put("bondKey", "synthetic-only-test-key");
    }
    private static void mustReject(JSONObject value) throws Exception {
        try { new AccountClient.OwnedDevice(value); throw new AssertionError("bad credential accepted"); }
        catch (java.io.IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        AccountClient.OwnedDevice device = new AccountClient.OwnedDevice(info());
        check(device.matches("aa-bb-cc-dd-ee-ff", "Different name"), "normalized BLE address");
        check(device.matches("AA:BB:CC:DD:EE:00", "Different name"), "classic address");
        check(!device.matches("11:22:33:44:55:66", "Halliday-G2-TEST"), "name cannot override wrong MAC");
        JSONObject noMac = info(); noMac.remove("bluetoothMac"); noMac.remove("btMac");
        check(new AccountClient.OwnedDevice(noMac).matches("11:22:33:44:55:66", "Halliday-G2-TEST"), "exact name fallback only without MAC");
        check(!new AccountClient.OwnedDevice(noMac).matches("11:22:33:44:55:66", "Other G2"), "different name rejected");
        mustReject(info().put("bondKey", "")); mustReject(info().put("bondKey", JSONObject.NULL));
        mustReject(info().put("bondKey", 12345)); mustReject(info().put("sn", ""));
        mustReject(info().put("bondKey", String.join("", Collections.nCopies(132, "a"))));
        check(!device.toString().contains("synthetic-only-test-key"), "key not in displayed label");
        JSONObject handshake = new JSONObject(new String(device.handshake(), StandardCharsets.UTF_8));
        check(handshake.getString("uuid").equals("synthetic-only-test-key"), "existing key reused exactly");
        check(handshake.getString("type").equals("android"), "platform");
        check(handshake.getString("model").getBytes(StandardCharsets.UTF_8).length <= 47, "firmware model bound");
        check(Math.abs(handshake.getLong("timestamp") - System.currentTimeMillis()) < 5000, "milliseconds");
        check(handshake.has("timeZoneOffsetInMinutes"), "timezone field");
        check(handshake.length() == 5, "exact payload keys");
        JSONArray rows = new JSONArray().put(new JSONObject().put("deviceInfo", info()));
        check(AccountClient.parseBindings(rows).size() == 1, "array binding list");
        check(AccountClient.parseBindings(new JSONObject().put("records", rows)).size() == 1, "records wrapper");
        JSONArray outsideKey = new JSONArray().put(new JSONObject().put("bondKey", "wrong-place").put("deviceInfo", info().put("bondKey", "")));
        try { AccountClient.parseBindings(outsideKey); throw new AssertionError("unrelated outer key accepted"); }
        catch (java.io.IOException expected) { checks++; }
        try { AccountClient.parseBindings(new JSONArray().put(rows.get(0)).put(rows.get(0))); throw new AssertionError("duplicates accepted"); }
        catch (java.io.IOException expected) { checks++; }
        try { AccountClient.parseBindings(new JSONArray()); throw new AssertionError("empty account accepted"); }
        catch (java.io.IOException expected) { checks++; }
        System.out.println("Synthetic account/handshake checks passed: " + checks);
    }
}
