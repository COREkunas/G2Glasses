package local.halliday.g2lab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Fixed ordinary-file experiment. No caller-supplied path, partition or payload. */
public final class DisposableFtpTest implements Closeable {
    public interface Observer { void update(Map<String,Object> state) throws IOException; }
    public static final int BYTES=8192, OFFSET=611;
    private final ReadOnlyFtp.Connector connector;
    private final String name="g2-usb-probe-"+UUID.randomUUID()+".bin";
    private final String path="/tmp/"+name;
    private volatile Session active;
    private volatile boolean cancelled;
    private boolean used;
    private final Map<String,Object> state=new LinkedHashMap<>();
    private final List<Map<String,Object>> reads=new ArrayList<>();
    private long started;

    public DisposableFtpTest(ReadOnlyFtp.Connector connector) { this.connector=connector; }
    public String scratchPath() { return path; }
    public static byte[] original() {
        byte[] value=new byte[BYTES];for(int i=0;i<value.length;i++) value[i]=(byte)(i*29+7);
        value[OFFSET]=0;return value;
    }
    public static byte[] expected() {byte[] value=original();value[OFFSET]=1;return value;}
    private void check() throws IOException {
        if(cancelled) throw new Failure("Disposable-file test cancelled.");
        if(System.nanoTime()-started>180_000_000_000L) throw new Failure("Disposable-file test exceeded three minutes.");
    }
    private void update(Observer observer,String phase) throws IOException {
        state.put("phase",phase);observer.update(new LinkedHashMap<>(state));
    }
    private Session session(String user,String password) throws IOException {
        check();Session s=new Session();active=s;
        try {s.login(user,password);check();return s;}catch(IOException e){s.close();throw e;}
    }
    public Map<String,Object> run(String user,String password,Observer observer) {
        if(used)throw new IllegalStateException("Each experiment can run only once.");used=true;started=System.nanoTime();
        boolean creationAttempted=false,verified=false,cleanup=false;
        state.put("scratchPath",path);state.put("bytes",BYTES);state.put("offset",OFFSET);
        state.put("partitionWrites",false);state.put("usbFlagChanged",false);state.put("recordingStarted",false);
        state.put("writesToGlasses",false);state.put("offsetWritesVerified",false);state.put("cleanupConfirmed",false);
        state.put("readbacks",reads);state.put("status","in_progress");
        try {
            // Credentials are checked before any connector is invoked.
            if(!"fs".equals(user))throw new Failure("Only the filesystem account is permitted.");token(password,128);
            state.put("originalSha256",hash(original()));state.put("expectedSha256",hash(expected()));
            update(observer,"Checking the new temporary filename");
            try(Session s=session(user,password)) {
                if(s.existsInTmp())throw new Failure("Temporary filename already exists; no file was changed.");
                check();creationAttempted=true;state.put("writesToGlasses",true);
                update(observer,"Creating one disposable 8192-byte file");s.store(false);s.finish();
            }
            verifyTwice(user,password,observer,original(),"original");
            update(observer,"Writing one byte at offset 611 in the disposable file");
            try(Session s=session(user,password)) {s.store(true);s.finish();}
            verifyTwice(user,password,observer,expected(),"patched");
            verified=true;state.put("offsetWritesVerified",true);state.put("allOtherBytesPreserved",true);
            update(observer,"Offset write verified; removing the disposable file");
        } catch(IOException e) {
            state.put("error",safeError(e));state.put("failedPhase",state.get("phase"));
        } finally {
            if(active!=null)active.close();
            if(creationAttempted && !cancelled) {
                // One cleanup attempt through a new control session, never a write retry.
                try(Session s=session(user,password)) {s.removeOwnFile();s.finish();cleanup=true;}
                catch(IOException e){state.put("cleanupError",safeError(e));}
            } else if(!creationAttempted) {state.put("cleanupNotNeeded",true);}
            state.put("cleanupConfirmed",cleanup);
            state.put("status",cancelled?"cancelled":verified?(cleanup?"offset_test_passed":"offset_test_passed_cleanup_unconfirmed"):"offset_test_failed");
            state.put("phase","Finished");
            try {observer.update(new LinkedHashMap<>(state));}catch(IOException e){state.put("reportSaveFailed",true);}
        }
        return new LinkedHashMap<>(state);
    }
    private void verifyTwice(String user,String password,Observer observer,byte[] expected,String label) throws IOException {
        for(int pass=1;pass<=2;pass++) {
            update(observer,"Reading the complete "+label+" file, pass "+pass+" of 2");
            byte[] value;try(Session s=session(user,password)){value=s.retrieve();s.finish();}
            Map<String,Object> read=new LinkedHashMap<>();read.put("phase",label);read.put("pass",pass);
            read.put("bytes",value.length);read.put("sha256",hash(value));read.put("matchesExpected",Arrays.equals(value,expected));
            reads.add(read);
            if(value.length!=BYTES || !Arrays.equals(value,expected))
                throw new Failure("Full disposable-file readback mismatch; offset writes are not verified.");
        }
    }
    private static String safeError(IOException e) {
        return e instanceof Failure?e.getMessage():"Connection or local I/O failed ("+e.getClass().getSimpleName()+").";
    }
    private static String hash(byte[] bytes) {
        try{return Protocol.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public void close() {cancelled=true;Session s=active;if(s!=null)s.close();}
    private static final class Failure extends IOException {
        Failure(String text){super(text);}
        Failure(String action,int code){super(action+" failed (FTP "+code+").");}
    }
    private static final class Reply {
        final int code;final String line;
        Reply(int code,String line){this.code=code;this.line=line;}
    }
    private static void token(String text,int max) throws IOException {
        if(text==null || text.isEmpty() || text.length()>max || text.chars().anyMatch(c->c<32 || c==127))
            throw new Failure("Invalid FTP argument.");
    }
    private static void expect(Reply r,String action,int code) throws IOException {
        if(r.code!=code)throw new Failure(action,r.code);
    }
    /** Private wire client; no general-purpose write API exists. */
    private final class Session implements Closeable {
        private volatile Socket control,data;
        private volatile boolean closed;
        private InputStream in;private OutputStream out;
        void login(String user,String password) throws IOException {
            control=connector.connect(21);control.setSoTimeout(10000);
            if(closed || cancelled){close();throw new Failure("Disposable-file test cancelled.");}
            in=control.getInputStream();out=control.getOutputStream();expect(reply(),"Greeting",220);
            Reply r=command("USER",user);if(r.code==331)r=command("PASS",password);
            expect(r,"Login",230);expect(command("TYPE","I"),"Binary mode",200);
        }
        Reply command(String verb,String arg) throws IOException {
            check();if(closed)throw new Failure("FTP session closed.");
            // Defense in depth: every file mutation is restricted to this internally generated path.
            boolean allowed=(verb.equals("USER") && "fs".equals(arg)) || verb.equals("PASS") ||
                (verb.equals("TYPE") && "I".equals(arg)) ||
                (Arrays.asList("EPSV","PASV","QUIT").contains(verb) && arg==null) ||
                (verb.equals("LIST") && "/tmp".equals(arg)) ||
                (verb.equals("REST") && "611".equals(arg)) ||
                (Arrays.asList("STOR","RETR","DELE").contains(verb) && path.equals(arg));
            if(!allowed)throw new Failure("Command is outside the disposable-file test.");
            if(arg!=null)token(arg,512);
            out.write((verb+(arg==null?"":" "+arg)+"\r\n").getBytes(StandardCharsets.UTF_8));out.flush();return reply();
        }
        String line() throws IOException {
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            for(int i=0;i<4096;i++) {
                check();int v=in.read();if(v<0)throw new EOFException("FTP control connection closed.");
                if(v=='\n')return new String(b.toByteArray(),StandardCharsets.US_ASCII).replaceFirst("\\r$","");b.write(v);
            }
            throw new Failure("FTP reply exceeded the line limit.");
        }
        Reply reply() throws IOException {
            String first=line();if(!first.matches("[1-5][0-9]{2}[ -].*"))throw new Failure("Malformed FTP reply.");
            int code=Integer.parseInt(first.substring(0,3));if(first.charAt(3)==' ')return new Reply(code,first);
            for(int i=0;i<64;i++){String next=line();if(next.startsWith(first.substring(0,3)+" "))return new Reply(code,next);}
            throw new Failure("FTP multiline reply exceeded the limit.");
        }
        int port(int p) throws IOException {if(p<1024 || p>65535)throw new Failure("Unexpected passive port.");return p;}
        int passive() throws IOException {
            Reply r=command("EPSV",null);
            if(r.code==229){Matcher m=Pattern.compile("\\((.)(?:\\1){2}([0-9]{1,5})\\1\\)").matcher(r.line);
                if(!m.find())throw new Failure("Malformed EPSV endpoint.");return port(Integer.parseInt(m.group(2)));}
            if(!Arrays.asList(500,502,504,522).contains(r.code))throw new Failure("EPSV",r.code);
            r=command("PASV",null);expect(r,"PASV",227);
            Matcher m=Pattern.compile("\\((\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})\\)").matcher(r.line);
            if(!m.find())throw new Failure("Malformed PASV endpoint.");int[] v=new int[6];
            for(int i=0;i<6;i++){v[i]=Integer.parseInt(m.group(i+1));if(v[i]>255)throw new Failure("Invalid PASV endpoint.");}
            return port(v[4]*256+v[5]); // Advertised host ignored; connector stays pinned to G2.
        }
        void dataSocket() throws IOException {
            data=connector.connect(passive());data.setSoTimeout(10000);
            if(closed || cancelled){close();throw new Failure("Disposable-file test cancelled.");}
        }
        void preliminary(String verb,String arg) throws IOException {
            Reply r=command(verb,arg);if(r.code!=125 && r.code!=150)throw new Failure(verb,r.code);
        }
        void completed() throws IOException {
            Reply r=reply();if(r.code!=226 && r.code!=250)throw new Failure("Transfer completion",r.code);
        }
        byte[] download(String verb,String arg,int cap) throws IOException {
            try {
                dataSocket();preliminary(verb,arg);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                InputStream input=data.getInputStream();byte[] buffer=new byte[4096];int n;
                while((n=input.read(buffer))!=-1){check();if(bytes.size()+n>cap)throw new Failure("Transfer exceeded its byte limit.");bytes.write(buffer,0,n);}
                data.close();data=null;completed();return bytes.toByteArray();
            }catch(IOException e){close();throw e;}
        }
        boolean existsInTmp() throws IOException {
            byte[] bytes=download("LIST","/tmp",65536);
            // Any occurrence of this high-entropy name counts as occupied, including symlinks.
            return new String(bytes,StandardCharsets.UTF_8).contains(name);
        }
        byte[] retrieve() throws IOException {return download("RETR",path,BYTES);}
        void store(boolean offset) throws IOException {
            try {
                dataSocket();
                if(offset){Reply r=command("REST","611");state.put("restartReply",r.code);
                    if(r.code!=320 && r.code!=350)throw new Failure("REST",r.code);}
                preliminary("STOR",path);check();data.getOutputStream().write(offset?new byte[]{1}:original());
                data.getOutputStream().flush();data.close();data=null;completed();
                // 226 alone is not commit evidence. Caller closes this session and reopens for every read.
            }catch(IOException e){close();throw e;}
        }
        void removeOwnFile() throws IOException {
            Reply r=command("DELE",path);
            if(r.code!=250 && r.code!=550)throw new Failure("Disposable-file cleanup",r.code);
            if(existsInTmp())throw new Failure("Disposable file is still listed after cleanup.");
        }
        void finish() throws IOException {
            // Its next command is processed after the server's stream returns/close completes.
            expect(command("QUIT",null),"Session completion",221);close();
        }
        public void close() {
            closed=true;for(Socket s:new Socket[]{data,control})if(s!=null)try{s.close();}catch(IOException ignored){}
        }
    }
}
