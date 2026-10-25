package local.halliday.g2lab;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONException;

/** Strictly selected non-account fields; raw information replies never enter logs. */
public final class DiagnosticReply {
    public static String summarize(Protocol.Packet packet) throws JSONException {
        if ((packet.flags & 15) != 7 || packet.payload.length > 4096)
            throw new JSONException("Unexpected diagnostic format");
        JSONObject source = new JSONObject(new String(packet.payload, StandardCharsets.UTF_8));
        JSONObject selected = new JSONObject();
        if (packet.command == 0x20) {
            version(source, selected, "firmware");
            version(source, selected, "emmc_fw_version");
            number(source, selected, "build_no");
            number(source, selected, "battery");
            number(source, selected, "charger");
        } else if (packet.command == 0x28) {
            for (String key : new String[]{"wear", "charger", "battery", "hfp", "record"})
                number(source, selected, key);
        } else if (packet.command == 0x2c) {
            number(source, selected, "used");
            number(source, selected, "avail");
        } else throw new JSONException("Not a diagnostic command");
        return selected.toString();
    }

    private static void number(JSONObject source, JSONObject target, String key) throws JSONException {
        Object value = source.opt(key);
        if (value instanceof Boolean) target.put(key, value);
        if (value instanceof Number) {
            double number = ((Number)value).doubleValue();
            if (!Double.isNaN(number) && !Double.isInfinite(number)) target.put(key, value);
        }
    }

    private static void version(JSONObject source, JSONObject target, String key) throws JSONException {
        Object value = source.opt(key);
        if (value instanceof String && ((String)value).matches("[0-9][0-9A-Za-z._-]{0,63}"))
            target.put(key, value);
    }
}
