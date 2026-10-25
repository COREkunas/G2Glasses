package local.halliday.g2lab;

import java.nio.charset.StandardCharsets;

/** One fixed, read-only experiment. Does not enable USB or accept arbitrary commands. */
public final class ConsoleCheck {
    // Captured firmware: stock business 4, local CmdShell 0x12; text request/reply.
    public static final int COMMAND = 0x92;
    public static final int TYPE = 6;

    private ConsoleCheck() {}

    public static byte[] payload() {
        return "uname -s".getBytes(StandardCharsets.UTF_8);
    }

    public static String summarize(Protocol.Packet packet) {
        // MainActivity separately requires the pending command AND sequence ID.
        if (packet.command != COMMAND) throw new IllegalArgumentException("Not a console check");
        if (packet.kind() == 2) {
            if (packet.payload.length == 1) {
                String code = Protocol.hex(packet.payload);
                return "Bluetooth console request rejected: NCK 0x" + code +
                    (code.equals("0a") ? " (also returned by the captured 1.1.1 rejection stub)." : ".") +
                    " USB was not enabled.";
            }
            return "Bluetooth console request rejected; unexpected reply withheld. USB was not enabled.";
        }
        if (packet.kind() != 1) throw new IllegalArgumentException("Not a command reply");
        if ((packet.flags & 15) == TYPE &&
                new String(packet.payload, StandardCharsets.UTF_8).trim().equals("NuttX")) {
            return "Console reply reports NuttX. Inspect running services before attempting temporary USB activation. USB was not enabled by this check.";
        }
        return "Console ACK received, but the expected OS reply was not verified. Raw reply withheld. USB was not enabled.";
    }
}
