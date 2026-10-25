package local.halliday.g2lab;
import java.io.IOException;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Fixed native route ABI; no executable payloads or route history. */
public final class NativeNavRecord {
    private NativeNavRecord(){}
    static String ascii(String s){
        String n=Normalizer.normalize(NavGuidance.clean(s),Normalizer.Form.NFD).toUpperCase(Locale.ROOT);
        StringBuilder b=new StringBuilder();
        for(int i=0;i<n.length();){int c=n.codePointAt(i);i+=Character.charCount(c);
            if(Character.getType(c)==Character.NON_SPACING_MARK)continue;
            b.append(c>=32&&c<=126?(char)c:c==0x2022?' ': '?');}
        return b.toString();
    }
    static void field(byte[] b,int at,int size,String s){
        byte[] raw=ascii(s).getBytes(StandardCharsets.US_ASCII);System.arraycopy(raw,0,b,at,Math.min(raw.length,size-1));
    }
    public static byte[] encode(NativeAppRecord.Status s,int seq,int state,int fresh,NavGuidance g,String source)throws IOException{
        return encode(s,seq,state,fresh,g,source,0);
    }
    public static byte[] encode(NativeAppRecord.Status s,int seq,int state,int fresh,NavGuidance g,String source,int layout)throws IOException{
        if(layout<0||layout>2||(layout!=0&&s.version<4))throw new IOException("Install the updated screen modes first.");
        if(!s.navigation||seq<=s.routeSequence||seq>1000000||state<0||state>4||fresh<0||fresh>120||(state==1&&(g==null||fresh==0)))throw new IOException("Native navigation record unavailable.");
        byte[] b=new byte[384];NativeAppRecord.put(b,0,0x3156414e);NativeAppRecord.put(b,4,s.version>=4?2:1);NativeAppRecord.put(b,8,seq);NativeAppRecord.put(b,12,state);
        System.arraycopy(s.nonce,0,b,16,16);field(b,40,24,source);
        if(state==1){int turn=0;String[] labels={"","LEFT","RIGHT","STRAIGHT","U-TURN","ROUNDABOUT","ARRIVED"};for(int i=1;i<labels.length;i++)if(labels[i].equals(g.maneuver))turn=i;
            NativeAppRecord.put(b,32,turn);NativeAppRecord.put(b,36,fresh);field(b,64,24,g.distance);field(b,88,128,g.instruction);field(b,216,160,g.detail);}
        NativeAppRecord.put(b,376,layout);NativeAppRecord.put(b,380,NativeAppRecord.hash(b,380));return b;
    }
}
