package local.halliday.g2lab;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reconstructed from the captured G2 1.1.1 Android app, build 186. */
public final class Protocol {
    public static final String SERVICE = "04000400-0000-1000-8000-009078563412";
    public static final String WRITE = "06000600-0000-1000-8000-009278563412";
    public static final String NOTIFY = "05000500-0000-1000-8000-009178563412";
    private static final int[] FIXED_LENGTH = {0, 1, 2, 4, 8};
    public static final int MAX_PAYLOAD = 8192; // Lab receive bound, not a claimed device limit.

    public static byte[] encode(int sid, int command, int flags, byte[] payload) {
        if ((sid & ~255) != 0 || (command & ~255) != 0 || (flags & ~0x77) != 0)
            throw new IllegalArgumentException("Invalid packet header");
        int type = flags & 15;
        if (payload.length > MAX_PAYLOAD || (type < 5 && payload.length != FIXED_LENGTH[type]))
            throw new IllegalArgumentException("Invalid payload length");
        int head = type >= 5 ? 10 : 8;
        byte[] out = new byte[head + payload.length];
        out[0] = 0x42; out[1] = 0x46; out[3] = 0x14; out[4] = (byte)sid;
        out[5] = 0x14; out[6] = (byte)command; out[7] = (byte)flags;
        if (head == 10) { out[8] = (byte)(payload.length >>> 8); out[9] = (byte)payload.length; }
        System.arraycopy(payload, 0, out, head, payload.length);
        out[2] = (byte)checksum(out, 0, out.length);
        return out;
    }

    public static byte[] note(String title, String content) {
        byte[] t = title.getBytes(StandardCharsets.UTF_8), c = content.getBytes(StandardCharsets.UTF_8);
        // Conservative limits for initial hardware experiments.
        if (t.length > 48 || c.length > 512 || content.length() > 256)
            throw new IllegalArgumentException("Use a title up to 48 UTF-8 bytes and a body up to 256 characters / 512 UTF-8 bytes.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, 1, t); field(out, 2, c);
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, int number, byte[] value) {
        out.write((number << 3) | 2);
        int n = value.length;
        while (n >= 128) { out.write((n & 127) | 128); n >>>= 7; }
        out.write(n); out.write(value, 0, value.length);
    }

    private static int checksum(byte[] b, int start, int length) {
        int sum = 0;
        for (int i = start + 3; i < start + length; i++) sum += b[i] & 255;
        return sum & 255;
    }

    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte v : b) s.append(String.format(java.util.Locale.ROOT, "%02x", v & 255));
        return s.toString();
    }

    public static final class Packet {
        public final int sid, command, flags, version, businessVersion;
        public final byte[] payload;
        Packet(byte[] b, int start, int length, int head) {
            version = b[start + 3] & 255; sid = b[start + 4] & 255;
            businessVersion = b[start + 5] & 255; command = b[start + 6] & 255;
            flags = b[start + 7] & 255;
            payload = Arrays.copyOfRange(b, start + head, start + length);
        }
        public int kind() { return (flags >>> 4) & 3; }
        public boolean matches(int expectedSid, int expectedCommand) {
            return sid == expectedSid && command == expectedCommand && (kind() == 1 || kind() == 2);
        }
    }

    /** Reassembles fragmented notifications; checksum failures cannot produce accepted replies. */
    public static final class Decoder {
        private byte[] buffered = new byte[0];
        public int rejected;
        public void reset() { buffered = new byte[0]; rejected = 0; }
        public List<Packet> feed(byte[] next) {
            if (next.length > MAX_PAYLOAD + 10) { rejected++; resetBuffer(); return new ArrayList<>(); }
            byte[] b = Arrays.copyOf(buffered, buffered.length + next.length);
            System.arraycopy(next, 0, b, buffered.length, next.length);
            List<Packet> packets = new ArrayList<>();
            int pos = 0;
            while (pos + 1 < b.length) {
                if (b[pos] != 0x42 || b[pos + 1] != 0x46) { pos++; continue; }
                if (b.length - pos < 8) break;
                int flags = b[pos + 7] & 255, type = flags & 15;
                if (type > 7 || (flags & 0x80) != 0) { rejected++; pos++; continue; }
                int head = type >= 5 ? 10 : 8;
                if (b.length - pos < head) break;
                int length = head + (type >= 5 ? ((b[pos + 8] & 255) << 8) | (b[pos + 9] & 255) : FIXED_LENGTH[type]);
                if (length > MAX_PAYLOAD + head) { rejected++; pos++; continue; }
                if (b.length - pos < length) break;
                if (checksum(b, pos, length) != (b[pos + 2] & 255)) { rejected++; pos++; continue; }
                packets.add(new Packet(b, pos, length, head)); pos += length;
            }
            buffered = Arrays.copyOfRange(b, pos, b.length);
            if (buffered.length > MAX_PAYLOAD + 10) { rejected++; resetBuffer(); }
            return packets;
        }
        private void resetBuffer() { buffered = new byte[0]; }
    }
}
