package local.halliday.g2lab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Loopback integration tests. The fixture deliberately separates 226 from commit. */
public final class DisposableFtpTestTest {
    private static int checks;
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    static final class Server implements AutoCloseable {
        final String mode;final ServerSocket listener;final Thread thread;
        volatile boolean stopped;volatile Throwable failure;volatile Socket control;volatile ServerSocket passive;
        String path;byte[] file,pending;int offset,stores,retrieves,rest,deletes,sessions,commits;
        final List<String> commands=new ArrayList<>();
        Server(String mode)throws IOException {
            this.mode=mode;listener=new ServerSocket(0,4,InetAddress.getLoopbackAddress());
            thread=new Thread(()->{
                while(!stopped)try{serve();}catch(SocketException|EOFException expected){}
                catch(Throwable e){failure=e;break;}
            },"disposable-fixture");thread.setDaemon(true);thread.start();
        }
        DisposableFtpTest client(){
            DisposableFtpTest f=new DisposableFtpTest(p->{Socket s=new Socket();s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),p==21?listener.getLocalPort():p),1500);return s;});
            path=f.scratchPath();return f;
        }
        void serve()throws Exception {
            try(Socket c=listener.accept()) {
                control=c;sessions++;offset=0;c.setSoTimeout(4000);
                BufferedReader input=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8));
                PrintWriter out=new PrintWriter(new OutputStreamWriter(c.getOutputStream(),StandardCharsets.US_ASCII),true);
                out.print("220-ready\r\nfixture\r\n220 ready\r\n");out.flush();String line;
                while((line=input.readLine())!=null) {
                    String[] pair=line.split(" ",2);String verb=pair[0],arg=pair.length>1?pair[1]:"";commands.add(verb);
                    switch(verb) {
                        case "USER":check(arg.equals("fs"),"fs role");out.print("331 pass\r\n");break;
                        case "PASS":out.print(mode.equals("loginDenied")?"530 fixture-secret\r\n":"230 ok\r\n");break;
                        case "TYPE":check(arg.equals("I"),"binary transfer");out.print("200 binary\r\n");break;
                        case "EPSV":
                            if(mode.equals("pasv"))out.print("502 unsupported\r\n");
                            else if(mode.equals("badPort"))out.print("229 (|||22|)\r\n");
                            else{passive=new ServerSocket(0,1,InetAddress.getLoopbackAddress());out.print("229 (|||"+passive.getLocalPort()+"|)\r\n");}break;
                        case "PASV":passive=new ServerSocket(0,1,InetAddress.getLoopbackAddress());int p=passive.getLocalPort();out.print("227 (203,0,113,9,"+(p/256)+","+(p%256)+")\r\n");break;
                        case "REST":
                            check(arg.equals("611"),"fixed restart offset");rest++;offset=611;
                            out.print(mode.equals("restDenied")?"550 fixture-secret\r\n":mode.equals("350")?"350 restart ready\r\n":"320 restart ready\r\n");break;
                        case "LIST":case "RETR":
                            check(arg.equals(verb.equals("LIST")?"/tmp":path),"all reads scoped");
                            out.print("150 data\r\n");out.flush();
                            try(Socket d=passive.accept()) {
                                byte[] bytes;
                                if(verb.equals("LIST")) {
                                    String listing=(file!=null || mode.equals("collision"))?"-rw-r--r-- 1 root root 8192 Jan 1 2026 "+path.substring(5)+"\r\n":"";
                                    bytes=mode.equals("largeList")?new byte[65537]:listing.getBytes(StandardCharsets.UTF_8);
                                }else{
                                    retrieves++;check(file!=null,"server file exists");bytes=file.clone();
                                    if(mode.equals("short") && retrieves==3)bytes=Arrays.copyOf(bytes,8191);
                                    if(mode.equals("oversize") && retrieves==3)bytes=Arrays.copyOf(bytes,8193);
                                    if(mode.equals("differentReads") && retrieves==4)bytes[700]^=1;
                                }
                                d.getOutputStream().write(bytes);
                            }finally{passive.close();}
                            out.print("226 complete\r\n");break;
                        case "STOR":
                            check(arg.equals(path) && path.matches("/tmp/g2-usb-probe-[0-9a-f-]{36}\\.bin"),"writes restricted to random tmp file");stores++;
                            if(mode.equals("storeDenied")){out.print("550 fixture-secret\r\n");passive.close();break;}
                            out.print("150 upload\r\n");out.flush();
                            byte[] payload;
                            try(Socket d=passive.accept()){
                                ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;
                                while((n=d.getInputStream().read(buffer))!=-1){if(b.size()+n>8192)throw new AssertionError("unbounded payload");b.write(buffer,0,n);}payload=b.toByteArray();
                            }finally{passive.close();}
                            if(stores==1){check(payload.length==8192 && offset==0,"initial fixture only");pending=payload;}
                            else{
                                check(stores==2 && payload.length==1 && payload[0]==1 && offset==611,"one fixed byte and no write retry");
                                pending=file.clone();
                                if(mode.equals("ignoredOffset"))pending[0]=1;
                                else if(mode.equals("truncatedAtOffset")){pending=Arrays.copyOf(file,612);pending[611]=1;}
                                else if(!mode.equals("notSaved"))pending[611]=1;
                                if(mode.equals("otherByteCorrupt"))pending[700]^=1;
                            }
                            out.print("226 complete before close/commit\r\n");break;
                        case "DELE":
                            check(arg.equals(path),"delete scoped to our file");deletes++;
                            if(mode.equals("deleteDenied"))out.print("550 denied\r\n");
                            else{if(!mode.equals("deleteLies"))file=null;out.print("250 removed\r\n");}break;
                        case "QUIT":
                            if(pending!=null){file=pending;pending=null;commits++;}
                            out.print("221 bye\r\n");out.flush();return;
                        default:throw new AssertionError("Unexpected command "+verb);
                    }
                    out.flush();
                }
            }finally{if(passive!=null){passive.close();passive=null;}}
        }
        public void close()throws Exception {
            stopped=true;listener.close();if(control!=null)control.close();if(passive!=null)passive.close();thread.join(2000);
            if(failure!=null)throw new AssertionError("Fixture failure",failure);
        }
    }
    public static void main(String[] args)throws Exception {
        for(String mode:new String[]{"normal","350","pasv","ignoredOffset","truncatedAtOffset","notSaved","otherByteCorrupt",
                "short","oversize","differentReads","storeDenied","restDenied","deleteDenied","deleteLies","collision","largeList","loginDenied","badPort"}) {
            try(Server s=new Server(mode);DisposableFtpTest f=s.client()) {
                Map<String,Object> r=f.run("fs","fixture-secret",state->{});
                boolean verified=Arrays.asList("normal","350","pasv","deleteDenied","deleteLies").contains(mode);
                check(Boolean.valueOf(verified).equals(r.get("offsetWritesVerified")),mode+" verification");
                check(!r.toString().contains("fixture-secret"),"credentials/server echo absent");
                check(Boolean.FALSE.equals(r.get("partitionWrites")) && Boolean.FALSE.equals(r.get("usbFlagChanged")),"no firmware change");
                if(Arrays.asList("normal","350","pasv").contains(mode)) {
                    check(r.get("status").equals("offset_test_passed"),"complete success status");
                    check(s.stores==2 && s.rest==1 && s.retrieves==4 && s.sessions==7 && s.commits==2,"separate sessions and bounded operations");
                    check(s.file==null && Boolean.TRUE.equals(r.get("cleanupConfirmed")),"deleted and absence checked");
                    check(((List<?>)r.get("readbacks")).size()==4,"all readback evidence retained");
                }
                if(mode.startsWith("delete"))check(r.get("status").equals("offset_test_passed_cleanup_unconfirmed") && s.file!=null,"cleanup failure distinct");
                if(mode.equals("collision"))check(s.stores==0 && s.deletes==0,"never overwrite/delete collision");
                check(s.stores<=2 && s.rest<=1 && s.deletes<=1,"no retry loop");
                check(s.commands.stream().noneMatch(v->Arrays.asList("APPE","RNFR","RNTO","SITE").contains(v)),"no other writing verbs");
            }
        }
        try(Server s=new Server("normal");DisposableFtpTest f=s.client()) {
            Map<String,Object> r=f.run("fs","fixture-secret",state->{
                if(String.valueOf(state.get("phase")).startsWith("Reading the complete original"))f.close();
            });
            check(r.get("status").equals("cancelled") && s.stores==1 && s.rest==0 && s.deletes==0,"cancellation prevents patch and documents remaining file");
            check(Boolean.FALSE.equals(r.get("cleanupConfirmed")),"cancel cleanup not falsely confirmed");
        }
        for(String[] argsBad:new String[][]{{"ota","secret"},{"fs","x\r\nSTOR /dev/misc"}}) {
            DisposableFtpTest f=new DisposableFtpTest(p->{throw new AssertionError("must not connect");});
            Map<String,Object> r=f.run(argsBad[0],argsBad[1],state->{});
            check(r.get("status").equals("offset_test_failed"),"invalid credentials refused before network");
        }
        DisposableFtpTest once=new DisposableFtpTest(p->{throw new AssertionError("must not connect");});once.close();
        check(once.run("fs","fixture-secret",state->{}).get("status").equals("cancelled"),"pre-cancel handled");
        try{once.run("fs","fixture-secret",state->{});throw new AssertionError("reused experiment");}catch(IllegalStateException expected){checks++;}
        System.out.println("Disposable-file FTP checks passed: "+checks+"; loopback only.");
    }
}
