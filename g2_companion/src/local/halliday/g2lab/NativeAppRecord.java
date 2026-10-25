package local.halliday.g2lab;
import java.io.IOException;
import java.util.Arrays;

/** Fixed MINDYLAB mailbox ABI. No executable addresses or arbitrary commands. */
public final class NativeAppRecord {
    public static final String STATUS="/tmp/mindy-mobile.status",COMMAND="/tmp/mindy-mobile.cmd";
    private NativeAppRecord(){}
    public static int word(byte[] b,int at){return (b[at]&255)|((b[at+1]&255)<<8)|((b[at+2]&255)<<16)|(b[at+3]<<24);}
    static void put(byte[] b,int at,int n){for(int i=0;i<4;i++)b[at+i]=(byte)(n>>>(8*i));}
    static int hash(byte[] b,int n){int value=0x811c9dc5;for(int i=0;i<n;i++)value=(value^(b[i]&255))*0x01000193;return value;}
    public static final class Status {
        public final int version,phase,sequence,result,demoPhase,error,inputs,restored,lockRemoved,bufferRestored,age,pid,starts;
        public final boolean persistent,navigation;
        public final int routeSequence,mode,linkAge,routeState,layout,awakeRequests;
        public final byte[] nonce;
        private Status(byte[] b){version=word(b,4);persistent=version>=2;navigation=version>=3;phase=word(b,8);sequence=word(b,12);result=word(b,16);demoPhase=word(b,20);
            error=word(b,24);inputs=word(b,28);restored=word(b,32);lockRemoved=word(b,36);bufferRestored=word(b,40);
            age=word(b,44);pid=word(b,48);starts=word(b,52);nonce=Arrays.copyOfRange(b,64,80);
            routeSequence=navigation?word(b,84):0;mode=navigation?word(b,88):0;linkAge=navigation?word(b,92):0;routeState=navigation?word(b,96):0;
            layout=version>=4?word(b,100):0;awakeRequests=version>=4?word(b,104):0;}
        public String summary(){return phase==2?"MINDYLAB running • taps: "+inputs:
            phase==3?"Launcher finished • screen restored: "+(restored==1)+" • error: "+error:
            starts>0?"Ready • last taps: "+inputs+" • screen restored: "+(restored==1):persistent?"Installed MINDYLAB ready on G2":"MINDYLAB ready on G2";}
    }
    public static Status parse(byte[] b)throws IOException {
        if(b.length!=128||word(b,0)!=0x31534c4d||(word(b,4)<1||word(b,4)>4)||word(b,124)!=hash(b,124))
            throw new IOException("Native app status is incomplete or incompatible.");
        Status s=new Status(b);
        if(s.phase<1||s.phase>3||s.sequence<0||s.sequence>10000||s.age<0||(!s.persistent&&s.age>910)||s.pid<=0||s.starts<0)
            throw new IOException("Native app status is outside the expected bounds.");
        boolean nonzero=false;for(byte v:s.nonce)nonzero|=v!=0;
        if(!nonzero)throw new IOException("Native app session identifier is missing.");
        if(s.persistent&&word(b,80)!=(s.version==4?7:s.navigation?3:1))throw new IOException("Unexpected launcher capabilities.");
        if(s.layout<0||s.layout>2||s.awakeRequests<0)throw new IOException("Invalid screen status.");
        if(s.navigation&&(s.routeSequence<0||s.routeSequence>1000000||s.mode<0||s.mode>1||s.linkAge<0||s.routeState<0||s.routeState>4))throw new IOException("Invalid navigation status.");
        for(int i=s.version==4?108:s.navigation?100:s.persistent?84:80;i<124;i++)if(b[i]!=0)throw new IOException("Unexpected status extension.");
        return s;
    }
    public static byte[] command(Status s,int op,int seq)throws IOException {
        if((op!=1&&op!=2&&!(op==3&&s.navigation))||seq<=s.sequence||seq>10000||s.phase==3)
            throw new IOException("Native app command is unavailable.");
        if((op==1||op==3)&&s.phase!=1)throw new IOException("The native screen is already running.");
        byte[] b=new byte[64];put(b,0,0x31434c4d);put(b,4,s.version);put(b,8,op);put(b,12,seq);
        System.arraycopy(s.nonce,0,b,16,16);put(b,60,hash(b,60));return b;
    }
}
