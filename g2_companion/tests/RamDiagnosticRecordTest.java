package local.halliday.g2lab;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
public final class RamDiagnosticRecordTest {
    static final String ID="0123456789abcdef0123456789abcdef";
    static void put(byte[] b,int o,long v){for(int i=0;i<4;i++)b[o+i]=(byte)(v>>(8*i));}
    static byte[] fixture(){byte[] b=new byte[512];put(b,0,0x314a3247L);put(b,4,1);put(b,8,512);put(b,52,1);put(b,56,336);
        for(int i=0;i<16;i++)b[16+i]=(byte)Integer.parseInt(ID.substring(i*2,i*2+2),16);
        CRC32 c=new CRC32();c.update(b,0,508);put(b,508,c.getValue());return b;}
    public static void main(String[] args)throws Exception{
        byte[] good=fixture();RamDiagnosticRecord.validate(ID,0,good);int checks=1;
        for(int i=0;i<512;i++){byte[] bad=good.clone();bad[i]^=1;
            try{RamDiagnosticRecord.validate(ID,0,bad);throw new AssertionError("corruption accepted");}catch(IOException e){checks++;}}
        for(int n:new int[]{0,511,513})try{RamDiagnosticRecord.validate(ID,0,Arrays.copyOf(good,n));throw new AssertionError();}catch(IOException e){checks++;}
        ReadOnlyFtp never=new ReadOnlyFtp(p->{throw new AssertionError("Unexpected connection");});
        for(String id:new String[]{"../x",ID+"\r\nSTOR x",ID.toUpperCase(),"",null})
            try{never.readRamDiagnostic(id,0);throw new AssertionError();}catch(IOException e){checks++;}
        for(int stage:new int[]{-1,64})try{never.readRamDiagnostic(ID,stage);throw new AssertionError();}catch(IOException e){checks++;}
        for(int stage:new int[]{0,9,10,63}) {
            byte[] v2=good.clone();put(v2,4,2);put(v2,12,stage);put(v2,48,stage==63?99:101);put(v2,52,2);put(v2,60,stage==63?1:0);
            CRC32 c=new CRC32();c.update(v2,0,508);put(v2,508,c.getValue());RamDiagnosticRecord.validate(ID,stage,v2);
            if(RamDiagnosticRecord.terminal(v2)!=(stage==63))throw new AssertionError();checks++;
            if(!RamDiagnosticRecord.path(ID,stage).endsWith(String.format("_%02d.bin",stage)))throw new AssertionError();checks++;
            for(int i=0;i<512;i++){byte[] bad=v2.clone();bad[i]^=1;try{RamDiagnosticRecord.validate(ID,stage,bad);throw new AssertionError();}catch(IOException e){checks++;}}
        }
        for(int[] change:new int[][]{{4,3},{52,3},{48,256},{60,2},{60,1}}){
            byte[] bad=good.clone();put(bad,4,2);put(bad,change[0],change[1]);CRC32 c=new CRC32();c.update(bad,0,508);put(bad,508,c.getValue());
            try{RamDiagnosticRecord.validate(ID,0,bad);throw new AssertionError();}catch(IOException e){checks++;}
        }
        for(String mode:new String[]{"normal","ramMissing","ramOversize"})
            try(ReadOnlyFtpTest.Server s=new ReadOnlyFtpTest.Server(mode);ReadOnlyFtp f=s.client()){
                f.login("fs","synthetic");
                if(!mode.equals("normal"))try{f.readRamDiagnostic(ID,0);throw new AssertionError();}catch(IOException e){checks++;}
                if(!mode.equals("ramOversize"))for(int i=0;i<2;i++){
                    byte[] got=f.readRamDiagnostic(ID,0);RamDiagnosticRecord.validate(ID,0,got);
                    if(!Arrays.equals(got,good))throw new AssertionError();checks++;}
                if(s.verbs.stream().anyMatch(v->Arrays.asList("STOR","DELE","REST","MKD","APPE").contains(v)))throw new AssertionError();
            }
        for(String mode:new String[]{"ramMissing","ramMissingExtra","ramShort"}){
            try(ReadOnlyFtpTest.Server s=new ReadOnlyFtpTest.Server(mode);ReadOnlyFtp f=s.client()){
                f.login("fs","synthetic");
                try{RamFtpRead.twice(f,ID,0);throw new AssertionError("pending file accepted");}
                catch(IOException expected){
                    if(mode.equals("ramShort")){if(!(expected instanceof RamDiagnosticRecord.NotReady))throw expected;}
                    else if(!(expected instanceof ReadOnlyFtp.Failure) || ((ReadOnlyFtp.Failure)expected).code!=550)throw expected;
                    checks++;
                }
                try{f.pwd();throw new AssertionError("refused RETR connection reused");}catch(IOException expected){checks++;}
            }
            try(ReadOnlyFtpTest.Server s=new ReadOnlyFtpTest.Server("normal");ReadOnlyFtp f=s.client()){
                f.login("fs","synthetic");
                if(!Arrays.equals(good,RamFtpRead.twice(f,ID,0)))throw new AssertionError();checks++;
                try{f.pwd();throw new AssertionError("finished connection reused");}catch(IOException expected){checks++;}
            }
        }
        System.out.println("RAM record and bounded read-only FTP checks passed: "+checks);
    }
}
