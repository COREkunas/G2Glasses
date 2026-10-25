package local.halliday.g2lab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Uploads only the reviewed RAM-marker ELF. No loader or recorder command is sent. */
public final class ProbeUpload {
    public static final String SHA256 = "f5a48c0e947699041422636d911ac9e79258563fc5aed394b0196b6b79e5234f";
    private static final int START = 0, DATA = 1, STOP = 2, CANCEL = 3, FINISHED = 4;
    public final String filename;
    public final long crc;
    private final byte[] module;
    private int state = START, offset, sent, chunkLimit = 192;
    private boolean awaiting, complete;
    private String error;

    public ProbeUpload(byte[] bytes, String uniqueId) {
        if (!uniqueId.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid probe identifier");
        try {
            if (bytes.length != 792 || !Protocol.hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(SHA256))
                throw new IllegalArgumentException("Bundled probe does not match the reviewed module");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        module = bytes.clone();
        filename = "g2probe_" + uniqueId + ".o";
        CRC32 checksum = new CRC32(); checksum.update(module); crc = checksum.getValue();
        if (crc == 0) throw new IllegalStateException("A nonzero verification CRC is required");
    }

    public boolean active() { return state != FINISHED; }
    public boolean complete() { return complete; }
    public String error() { return error; }

    public static final class Step {
        public final int command, type;
        public final byte[] payload;
        Step(int command, int type, byte[] payload) {
            this.command = command; this.type = type; this.payload = payload;
        }
    }

    public Step next() {
        if (awaiting || !active()) return null;
        awaiting = true;
        if (state == START) return new Step(4, 7,
            ("{\"filename\":\"" + filename + "\",\"size\":" + module.length + "}").getBytes(StandardCharsets.UTF_8));
        if (state == DATA) {
            sent = Math.min(chunkLimit, module.length - offset);
            return new Step(7, 5, Arrays.copyOfRange(module, offset, offset + sent));
        }
        // dp_pack converts integer payloads from network byte order.
        if (state == STOP) return new Step(5, 3, new byte[]{
            (byte)(crc >>> 24), (byte)(crc >>> 16), (byte)(crc >>> 8), (byte)crc});
        return new Step(6, 1, new byte[]{0});
    }

    /** The caller must first verify sequence ID and command against its outstanding request. */
    public void reply(Protocol.Packet packet) {
        if (!awaiting || !active()) throw new IllegalStateException("No upload reply expected");
        int expected = state == START ? 4 : state == DATA ? 7 : state == STOP ? 5 : 6;
        if (packet.command != expected || (packet.kind() != 1 && packet.kind() != 2))
            throw new IllegalArgumentException("Unexpected upload reply");
        awaiting = false;
        if (state == CANCEL) {
            if (packet.kind() != 1) error += "; cancel rejected";
            state = FINISHED; return;
        }
        if (packet.kind() == 2) {
            error = "Upload command 0x" + Integer.toHexString(expected) + " rejected: " + Protocol.hex(packet.payload);
            state = state == START ? FINISHED : CANCEL; return;
        }
        if (state == START) {
            if ((packet.flags & 15) != 2 || packet.payload.length != 2) {
                error = "Unexpected upload-start ACK; cancelling"; state = CANCEL; return;
            }
            int offered = ((packet.payload[0] & 255) << 8) | (packet.payload[1] & 255);
            if (offered == 0) { error = "Zero upload block size; cancelling"; state = CANCEL; return; }
            chunkLimit = Math.min(chunkLimit, offered); state = DATA;
        } else if ((packet.flags & 15) != 0 || packet.payload.length != 0) {
            error = "Unexpected upload ACK; cancelling"; state = CANCEL;
        } else if (state == DATA) {
            offset += sent;
            if (offset == module.length) state = STOP;
        } else {
            complete = true; state = FINISHED;
        }
    }
}
