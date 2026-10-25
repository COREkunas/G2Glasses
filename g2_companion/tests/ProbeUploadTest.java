package local.halliday.g2lab;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;

public final class ProbeUploadTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static Protocol.Packet reply(int command, int flags, byte[] bytes) {
        return new Protocol.Decoder().feed(Protocol.encode(1, command, flags, bytes)).get(0);
    }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        byte[] module = Files.readAllBytes(Paths.get(args[0]));
        String id = "0123456789abcdef0123456789abcdef";
        rejects(() -> new ProbeUpload(module, "../outside"));
        byte[] bad = module.clone(); bad[100] ^= 1;
        rejects(() -> new ProbeUpload(bad, id));
        for (int limit : new int[]{1, 64, 192, 4096}) {
            ProbeUpload u = new ProbeUpload(module, id);
            ProbeUpload.Step s = u.next();
            check(s.command == 4 && s.type == 7);
            check(u.next() == null);
            check(new String(s.payload, "UTF-8").equals("{\"filename\":\"g2probe_" + id + ".o\",\"size\":792}"));
            u.reply(reply(4, 0x12, new byte[]{(byte)(limit >> 8), (byte)limit}));
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            while ((s = u.next()).command == 7) {
                check(s.payload.length <= Math.min(limit, 192));
                data.write(s.payload); check(u.next() == null);
                u.reply(reply(7, 0x10, new byte[0]));
            }
            check(Arrays.equals(data.toByteArray(), module));
            check(s.command == 5 && s.type == 3 && !u.complete());
            CRC32 crc = new CRC32(); crc.update(data.toByteArray());
            long wire = 0; for (byte b : s.payload) wire = (wire << 8) | (b & 255);
            check(wire == crc.getValue() && wire != 0);
            u.reply(reply(5, 0x10, new byte[0]));
            check(u.complete() && !u.active() && u.next() == null);
        }
        ProbeUpload u = new ProbeUpload(module, id);
        u.next(); u.reply(reply(4, 0x21, new byte[]{0x17}));
        check(!u.active() && !u.complete() && u.error() != null);
        for (int failureCommand : new int[]{4, 7, 5}) {
            u = new ProbeUpload(module, id); u.next();
            u.reply(reply(4, 0x12, new byte[]{0x10, 0}));
            if (failureCommand == 4) {
                u = new ProbeUpload(module, id); u.next();
                u.reply(reply(4, 0x12, new byte[]{0, 0}));
            } else {
                ProbeUpload.Step s;
                while ((s = u.next()).command != failureCommand) u.reply(reply(s.command, 0x10, new byte[0]));
                u.reply(reply(failureCommand, 0x21, new byte[]{0x17}));
            }
            check(!u.complete());
            check(u.next().command == 6);
            u.reply(reply(6, 0x10, new byte[0]));
            check(!u.active() && !u.complete());
        }
        System.out.println("Probe upload checks passed: " + checks);
    }
}
