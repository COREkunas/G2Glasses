package local.halliday.g2lab;

import org.json.*;
import java.nio.charset.StandardCharsets;

/** Synthetic privacy/format checks, using Android's actual JSON implementation. */
public final class DiagnosticReplyTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Check " + checks); }
    private static Protocol.Packet packet(int command, int type, String json) {
        return new Protocol.Decoder().feed(Protocol.encode(1, command, 0x10 | type, json.getBytes(StandardCharsets.UTF_8))).get(0);
    }
    public static void main(String[] args) throws Exception {
        String summary = DiagnosticReply.summarize(packet(0x20, 7,
            "{\"firmware\":\"1.1.1.202609221616\",\"battery\":80,\"charger\":1,\"sn\":\"private-sn\",\"bondKey\":\"private-key\",\"bt_addr\":\"private-mac\",\"extra\":{\"password\":\"private-pass\"}}"));
        JSONObject info = new JSONObject(summary);
        check(info.length() == 3);
        check(info.getString("firmware").equals("1.1.1.202609221616"));
        check(!summary.contains("private"));
        check(info.getInt("battery") == 80);
        check(DiagnosticReply.summarize(packet(0x20, 7, "{\"firmware\":\"bad\\nversion\",\"battery\":{\"token\":\"secret\"},\"charger\":\"secret\"}")).equals("{}"));
        JSONObject status = new JSONObject(DiagnosticReply.summarize(packet(0x28, 7,
            "{\"wear\":1,\"hfp\":true,\"record\":false,\"battery\":90,\"unknown\":123}")));
        check(status.length() == 4 && status.getBoolean("hfp") && !status.getBoolean("record"));
        check(status.getInt("wear") == 1);
        JSONObject storage = new JSONObject(DiagnosticReply.summarize(packet(0x2c, 7, "{\"used\":10,\"avail\":20,\"path\":\"private-file\"}")));
        check(storage.length() == 2 && storage.getInt("avail") == 20);
        for (Protocol.Packet bad : new Protocol.Packet[]{packet(0x20, 6, "{}"), packet(1, 7, "{}"),
            packet(0x20, 7, "not json"), packet(0x20, 7, "[]")}) {
            try { DiagnosticReply.summarize(bad); throw new AssertionError("Malformed reply accepted"); }
            catch (JSONException expected) { checks++; }
        }
        System.out.println("Diagnostic privacy/format checks passed: " + checks);
    }
}
