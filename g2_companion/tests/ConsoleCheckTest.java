package local.halliday.g2lab;

import java.nio.charset.StandardCharsets;

public class ConsoleCheckTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++; if (!ok) throw new AssertionError(label);
    }
    private static Protocol.Packet reply(int command, int flags, byte[] body) {
        return new Protocol.Decoder().feed(Protocol.encode(7, command, flags, body)).get(0);
    }
    private static String ack(int flags, String text) {
        return ConsoleCheck.summarize(reply(0x92, flags, text.getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) {
        check(ConsoleCheck.COMMAND == 0x92 && ConsoleCheck.TYPE == 6, "captured descriptor and group");
        check(new String(ConsoleCheck.payload(), StandardCharsets.UTF_8).equals("uname -s"), "fixed read-only request");
        check(ack(0x16, "NuttX\r\n").startsWith("Console reply reports NuttX."), "expected OS response");
        check(!ack(0x16, "uname -s").startsWith("Console reply reports"), "request echo is not execution evidence");
        check(!ack(0x16, "NuttX unexpected-data").startsWith("Console reply reports"), "substring match insufficient");
        check(!ack(0x15, "NuttX").startsWith("Console reply reports"), "unexpected data type");
        check(!ack(0x10, "").startsWith("Console reply reports"), "empty ACK not execution evidence");
        check(ConsoleCheck.summarize(reply(0x92, 0x21, new byte[]{10})).contains("NCK 0x0a"), "rejection distinguished");
        String privateReply = "PRIVATE_TEST_REPLY";
        check(!ack(0x16, privateReply).contains(privateReply), "unrecognized reply excluded from log");
        check(!ConsoleCheck.summarize(reply(0x92, 0x26, privateReply.getBytes(StandardCharsets.UTF_8))).contains(privateReply), "unrecognized NCK excluded from log");
        try { ConsoleCheck.summarize(reply(0x20, 0x16, "NuttX".getBytes(StandardCharsets.UTF_8))); throw new AssertionError("wrong command"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { ack(0x36, "NuttX"); throw new AssertionError("indication"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("Console checks passed: " + checks);
    }
}
