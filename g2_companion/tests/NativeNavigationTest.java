package local.halliday.g2lab;
import java.nio.file.*;
import java.util.*;

public final class NativeNavigationTest {
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    static String field(byte[] b,int at,int n){int end=at;while(end<at+n&&b[end]!=0)end++;return new String(b,at,end-at,java.nio.charset.StandardCharsets.US_ASCII);}
    public static void main(String[] args)throws Exception{
        byte[] raw=Files.readAllBytes(Paths.get(args[0],"status-fixture.bin"));NativeAppRecord.put(raw,8,1);NativeAppRecord.put(raw,84,0);NativeAppRecord.put(raw,124,NativeAppRecord.hash(raw,124));
        NativeAppRecord.Status s=NativeAppRecord.parse(raw);check(s.navigation&&s.persistent,"v3 capability");
        check(NativeAppRecord.word(NativeAppRecord.command(s,3,s.sequence+1),8)==3,"native launch opcode");
        NavGuidance g=NavGuidance.parse(NavGuidance.MAPS,true,"200 m","Turn right onto Example Street","","12 min - 6 km",null);
        check(g.maneuver.equals("RIGHT")&&g.distance.equals("200 m"),"Maps split title / maneuver");
        NavGuidance progress=NavGuidance.parse(NavGuidance.MAPS,true,"350\u00a0m \u00b7 Turn right onto Example Street",null,"","Arrive 22:59",null);
        check(progress!=null&&progress.distance.equals("350 m")&&progress.maneuver.equals("RIGHT"),"Android16 Maps ProgressStyle null text / NBSP");
        byte[] b=NativeNavRecord.encode(s,1,1,120,g,"Google Maps");check(b.length==384&&NativeAppRecord.word(b,32)==2,"binary arrow");
        check(field(b,64,24).equals("200 M")&&field(b,88,128).equals("TURN RIGHT ONTO EXAMPLE STREET"),"distance and instruction");
        check(NativeAppRecord.word(b,380)==NativeAppRecord.hash(b,380),"checksum");
        Files.write(Paths.get(args[0],"java-route-fixture.bin"),b);
        check(s.version==4,"new screen capabilities");
        for(int layout=0;layout<=2;layout++)check(NativeAppRecord.word(NativeNavRecord.encode(s,1,1,120,g,"Google Maps",layout),376)==layout,"layout round trip");
        try(NativeAppTest.Server server=new NativeAppTest.Server("route");NativeAppFtp ftp=server.client()){
            ftp.login("fs","synthetic");ftp.route(b);ftp.finish();check(Arrays.equals(b,server.stored),"fixed path FTP byte exact");}
        g=NavGuidance.parse(NavGuidance.WAZE,true,"Turn left","500 ft - Test Road","","12 min - 6 km",null);
        check(g.distance.equals("500 ft"),"Waze leading distance");
        g=NavGuidance.parse(NavGuidance.WAZE,true,"Turn left","Test Road","","12 min - 6 km",null);
        check(g.distance.isEmpty(),"trip distance never becomes turn distance");
        check(NativeNavRecord.ascii("į dešinę • Žirmūnų g.").equals("I DESINE   ZIRMUNU G."),"Lithuanian transliteration");
        NavRelayState state=new NavRelayState();check(state.nativeFrame(0).state==0,"waiting");state.update(g,0,1000);
        check(state.nativeFrame(1000).fresh==119,"source age not transport age");check(state.nativeFrame(120000).state==2,"stale");
        state.listenerLost();check(state.nativeFrame(1).state==4,"listener lost");state.update(null,1,1);check(state.nativeFrame(2).state==3,"removed");
        state.stop();state.update(g,10,10);check(state.nativeFrame(11).state==3,"notifications cannot restart");
        for(int kind:new int[]{0,2,3,4}){byte[] idle=NativeNavRecord.encode(s,1,kind,0,null,"Waze");check(NativeAppRecord.word(idle,32)==0&&NativeAppRecord.word(idle,36)==0,"no stale arrow");}
        System.out.println("Native navigation ABI, age, parsing and fixed-path FTP checks passed.");
    }
}
