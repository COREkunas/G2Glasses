package local.halliday.g2lab;

import java.util.*;

public class ProtocolTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++; if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        // Independently calculated from the recovered on-wire layout.
        check(Protocol.hex(Protocol.encode(1, 0, 0, new byte[0])).equals("4246291401140000"), "version vector");
        check(Protocol.hex(Protocol.note("A", "Hi")).equals("0a014112024869"), "protobuf vector");
        byte[] note = Protocol.encode(2, 0x3e, 5, Protocol.note("A", "Hi"));
        check(Protocol.hex(note).equals("4246851402143e0500070a014112024869"), "note vector");
        for (int split = 0; split <= note.length; split++) {
            Protocol.Decoder d = new Protocol.Decoder();
            List<Protocol.Packet> found = new ArrayList<>();
            found.addAll(d.feed(Arrays.copyOfRange(note, 0, split)));
            found.addAll(d.feed(Arrays.copyOfRange(note, split, note.length)));
            check(found.size() == 1 && found.get(0).command == 0x3e, "every fragment boundary " + split);
        }
        byte[] ack = Protocol.encode(2, 0x3e, 0x10, new byte[0]);
        Protocol.Packet p = new Protocol.Decoder().feed(ack).get(0);
        check(p.matches(2, 0x3e) && !p.matches(1, 0x3e) && !p.matches(2, 0), "match sequence and command");
        byte[] nck = Protocol.encode(2, 0x3e, 0x21, new byte[]{3});
        check(new Protocol.Decoder().feed(nck).get(0).kind() == 2, "negative acknowledgment");
        byte[] joined = new byte[note.length + ack.length + 3];
        joined[0] = 0x42; joined[1] = 1; joined[2] = 2;
        System.arraycopy(note, 0, joined, 3, note.length);
        System.arraycopy(ack, 0, joined, 3 + note.length, ack.length);
        check(new Protocol.Decoder().feed(joined).size() == 2, "coalesced packets with leading noise");
        byte[] bad = note.clone(); bad[bad.length - 1] ^= 1;
        Protocol.Decoder decoder = new Protocol.Decoder();
        check(decoder.feed(bad).isEmpty() && decoder.rejected == 1, "reject corrupt payload");
        check(decoder.feed(ack).size() == 1, "recover after corruption");
        for (int type = 0; type <= 7; type++) {
            int size = type == 0 ? 0 : type < 5 ? 1 << (type - 1) : 300;
            byte[] payload = new byte[size]; new Random(type).nextBytes(payload);
            byte[] wire = Protocol.encode(255, 0xfe, 0x10 | type, payload);
            check(Arrays.equals(new Protocol.Decoder().feed(wire).get(0).payload, payload), "data type " + type);
            if (type >= 5) check(wire[8] == 1 && wire[9] == 44, "big endian length");
        }
        check(Protocol.hex(Protocol.note("", "é")).equals("0a001202c3a9"), "UTF-8 byte length");
        byte[] longText = Protocol.note("", String.join("", Collections.nCopies(128, "a")));
        check(longText[3] == (byte)0x80 && longText[4] == 1, "protobuf multi-byte length");
        try { Protocol.note("", String.join("", Collections.nCopies(257, "a"))); throw new AssertionError("limit"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { Protocol.encode(0, 0, 1, new byte[0]); throw new AssertionError("fixed size"); }
        catch (IllegalArgumentException expected) { checks++; }
        byte[] ftp = Protocol.encode(1,0x65,0x46,new byte[]{0x66,0x73});
        check(Protocol.hex(ftp).equals("4246af140114654600026673"),"FTP start independent wire vector");
        Protocol.Packet control = new Protocol.Decoder().feed(ftp).get(0);
        check(control.flags==0x46 && control.kind()==0,"control bit is not ACK bit");
        check(Protocol.hex(Protocol.encode(2,0x66,0x40,new byte[0])).equals("4246d01402146640"),"FTP stop wire vector");
        byte[] controlAck=Protocol.encode(1,0x65,0x57,new byte[]{'{','}'});
        check(new Protocol.Decoder().feed(controlAck).get(0).matches(1,0x65),"control flag retained on reply");
        try { Protocol.encode(0,0,0x80,new byte[0]); throw new AssertionError("segmentation unsupported"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("Protocol checks passed: " + checks);
    }
}
