package local.halliday.g2lab;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Compiled-native ABI fixtures and a local FTP server; never accesses G2. */
public final class NativeAppTest {
    static int checks;
    interface Action {void run() throws Exception;}
    static void check(boolean b){checks++;if(!b)throw new AssertionError();}
    static void rejects(Action a)throws Exception {try{a.run();throw new AssertionError("Accepted invalid input");}catch(IOException expected){checks++;}}
    static byte[] fixture;
    static byte[] ready() {byte[] b=fixture.clone();NativeAppRecord.put(b,8,1);NativeAppRecord.put(b,124,NativeAppRecord.hash(b,124));return b;}
    static class Server implements AutoCloseable {
        final String mode;final ServerSocket listener;final Thread thread;Socket control;ServerSocket passive;
        volatile Throwable failure;byte[] stored;final List<String> commands=new ArrayList<>();
        Server(String mode)throws IOException {
            this.mode=mode;listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            thread=new Thread(()->{try{serve();}catch(SocketException|EOFException expected){}catch(Throwable e){failure=e;}});
            thread.setDaemon(true);thread.start();
        }
        NativeAppFtp client(){return new NativeAppFtp(p->{Socket s=new Socket();s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),p==21?listener.getLocalPort():p),2000);return s;});}
        void serve()throws Exception {
            control=listener.accept();control.setSoTimeout(4000);
            BufferedReader in=new BufferedReader(new InputStreamReader(control.getInputStream(),StandardCharsets.UTF_8));
            PrintWriter out=new PrintWriter(control.getOutputStream());out.print("220 ready\r\n");out.flush();String line;
            while((line=in.readLine())!=null){String[] x=line.split(" ",2);String v=x[0],a=x.length==2?x[1]:"";commands.add(v);
                switch(v){
                case "USER":check(a.equals("fs"));out.print("331 password\r\n");break;
                case "PASS":out.print(mode.equals("denied")?"530 synthetic-secret\r\n":"230 ok\r\n");break;
                case "TYPE":check(a.equals("I"));out.print("200 binary\r\n");break;
                case "EPSV":
                    if(mode.equals("pasv"))out.print("502 unsupported\r\n");
                    else if(mode.equals("badPort"))out.print("229 (|||22|)\r\n");
                    else{passive=new ServerSocket(0,1,InetAddress.getLoopbackAddress());out.print("229 (|||"+passive.getLocalPort()+"|)\r\n");}break;
                case "PASV":passive=new ServerSocket(0,1,InetAddress.getLoopbackAddress());int p=passive.getLocalPort();out.print("227 (203,0,113,9,"+(p/256)+","+(p%256)+")\r\n");break;
                case "RETR":case "STOR":
                    check(a.equals(v.equals("RETR")?NativeAppRecord.STATUS:mode.equals("route")?"/tmp/mindy-nav.route":NativeAppRecord.COMMAND));
                    if(mode.equals("missing")){out.print("550 missing\r\n");passive.close();break;}
                    out.print("150 data\r\n");out.flush();
                    try(Socket d=passive.accept()){
                        if(v.equals("RETR")){byte[] b=ready();if(mode.equals("short"))b=Arrays.copyOf(b,50);if(mode.equals("oversize"))b=Arrays.copyOf(b,129);d.getOutputStream().write(b);}
                        else{ByteArrayOutputStream b=new ByteArrayOutputStream();int n;while((n=d.getInputStream().read())!=-1)b.write(n);stored=b.toByteArray();check(stored.length==(mode.equals("route")?384:64));}
                    }finally{passive.close();}
                    out.print(mode.equals("incomplete")?"426 failed\r\n":"226 complete\r\n");break;
                case "QUIT":out.print("221 bye\r\n");out.flush();return;
                default:throw new AssertionError("Unexpected verb "+v);
                }out.flush();
            }
        }
        public void close()throws Exception{if(control!=null)control.close();if(passive!=null)passive.close();listener.close();thread.join(2000);if(failure!=null)throw new AssertionError(failure);}
    }
    public static void main(String[] args)throws Exception {
        fixture=Files.readAllBytes(Paths.get(args[0],"status-fixture.bin"));
        NativeAppRecord.Status finalStatus=NativeAppRecord.parse(fixture);
        check(finalStatus.phase==3&&finalStatus.restored==1&&finalStatus.lockRemoved==1&&finalStatus.bufferRestored==1);
        rejects(()->NativeAppRecord.command(finalStatus,1,4));
        NativeAppRecord.Status s=NativeAppRecord.parse(ready());
        check(s.version==(args[0].contains("persistent")?2:1));
        byte[] older=ready();NativeAppRecord.put(older,4,1);NativeAppRecord.put(older,80,0);NativeAppRecord.put(older,44,900);NativeAppRecord.put(older,124,NativeAppRecord.hash(older,124));
        check(!NativeAppRecord.parse(older).persistent);
        NativeAppRecord.put(older,44,911);NativeAppRecord.put(older,124,NativeAppRecord.hash(older,124));rejects(()->NativeAppRecord.parse(older));
        byte[] newer=ready();NativeAppRecord.put(newer,4,2);NativeAppRecord.put(newer,80,1);NativeAppRecord.put(newer,44,100000);NativeAppRecord.put(newer,124,NativeAppRecord.hash(newer,124));
        check(NativeAppRecord.parse(newer).persistent&&NativeAppRecord.parse(newer).age==100000);
        NativeAppRecord.put(newer,80,2);NativeAppRecord.put(newer,124,NativeAppRecord.hash(newer,124));rejects(()->NativeAppRecord.parse(newer));
        check(Arrays.equals(NativeAppRecord.command(s,1,4),Files.readAllBytes(Paths.get(args[0],"command-fixture.bin"))));
        for(int i=0;i<fixture.length;i++){byte[] b=fixture.clone();b[i]^=1;rejects(()->NativeAppRecord.parse(b));}
        for(int n:new int[]{0,64,127,129})rejects(()->NativeAppRecord.parse(Arrays.copyOf(fixture,n)));
        rejects(()->NativeAppRecord.command(s,3,4));rejects(()->NativeAppRecord.command(s,1,s.sequence));
        rejects(()->NativeAppRecord.command(s,1,10001));
        for(String mode:new String[]{"normal","pasv"})try(Server server=new Server(mode);NativeAppFtp ftp=server.client()){
            ftp.login("fs","synthetic");NativeAppRecord.Status r=NativeAppRecord.parse(ftp.status());
            ftp.send(r,1,4);ftp.finish();check(Arrays.equals(server.stored,NativeAppRecord.command(s,1,4)));
            check(Collections.frequency(server.commands,"STOR")==1&&server.commands.contains("QUIT"));
        }
        for(String mode:new String[]{"denied","badPort","missing","short","oversize","incomplete"})try(Server server=new Server(mode);NativeAppFtp ftp=server.client()){
            rejects(()->{ftp.login("fs","synthetic");NativeAppRecord.parse(ftp.status());});
            check(!server.commands.contains("STOR"));
        }
        NativeAppFtp never=new NativeAppFtp(p->{throw new AssertionError("Must not connect");});
        rejects(()->never.login("ota","synthetic"));rejects(()->never.login("fs","x\r\nSTOR bad"));
        System.out.println("Native app checks passed: "+checks);
    }
}
