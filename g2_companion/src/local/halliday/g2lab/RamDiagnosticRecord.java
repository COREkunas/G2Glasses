package local.halliday.g2lab;

import java.io.IOException;
import java.util.zip.CRC32;

/** Fixed-size, session-bound RAM journal. No arbitrary filesystem paths. */
public final class RamDiagnosticRecord {
    public static final class NotReady extends IOException { NotReady(){super("RAM diagnostic file is still being written.");} }
    public static String path(String id,int stage) throws IOException {
        if(id==null || !id.matches("[0-9a-f]{32}") || stage<0 || stage>63)
            throw new IOException("Invalid RAM diagnostic session or stage.");
        return "/tmp/g2dj_"+id+"_"+(stage<10?"0":"")+stage+".bin";
    }
    public static long word(byte[] b,int offset) {
        return ((long)b[offset]&255)|(((long)b[offset+1]&255)<<8)|
            (((long)b[offset+2]&255)<<16)|(((long)b[offset+3]&255)<<24);
    }
    public static void validate(String id,int stage,byte[] data) throws IOException {
        path(id,stage);
        if(data.length<512)throw new NotReady();
        long version=data.length>=8?word(data,4):0;
        if(data.length!=512 || word(data,0)!=0x314a3247L || (version!=1 && version!=2) ||
           word(data,8)!=512 || word(data,12)!=stage || word(data,56)!=336 ||
           (version==1 && (stage>5 || word(data,52)!=1)) ||
           (version==2 && (word(data,52)<1 || word(data,52)>2 || word(data,48)>255 ||
                          (word(data,60)&~1L)!=0 || (word(data,60)==1 && word(data,48)!=99))))
            throw new IOException("RAM diagnostic record is incomplete or has an unexpected format.");
        for(int i=0;i<16;i++)
            if((data[16+i]&255)!=Integer.parseInt(id.substring(i*2,i*2+2),16))
                throw new IOException("RAM diagnostic session does not match.");
        CRC32 crc=new CRC32();crc.update(data,0,508);
        if(crc.getValue()!=word(data,508))throw new IOException("RAM diagnostic checksum does not match.");
    }
    public static boolean terminal(byte[] data) {
        return word(data,4)==1?word(data,12)==5:word(data,60)==1;
    }
}
