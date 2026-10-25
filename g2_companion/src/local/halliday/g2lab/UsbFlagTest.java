package local.halliday.g2lab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Exact owner/build-specific USB experiment. No arbitrary write path or payload API. */
public final class UsbFlagTest implements Closeable {
    public interface Observer { void update(Map<String,Object> state) throws IOException; }
    public static final boolean PUBLIC_RELEASE = true;
    public static final int SIZE=8192, OFFSET=611, FLASH=16777216;
    public static final String FIRMWARE="1.0.1.202609052141";
    public static final String MISC="PRIVATE_BASELINE_NOT_DISTRIBUTED";
    public static final String ENABLED="PRIVATE_BASELINE_NOT_DISTRIBUTED";
    public static final String BOOTINFO="PRIVATE_BASELINE_NOT_DISTRIBUTED";
    public static final String FACTORY="PRIVATE_BASELINE_NOT_DISTRIBUTED";
    public static final String AP="PRIVATE_BASELINE_NOT_DISTRIBUTED";
    public static final String BOOT="PRIVATE_BASELINE_NOT_DISTRIBUTED";
    private static final String[] CRITICAL={"misc","bootinfo","factory"};
    private static final int[] OFFSETS={0xfe0000,0xfe2000,0xffe000};
    private final ReadOnlyFtp.Connector connector;
    private final File folder;
    private volatile Session active;
    private volatile boolean cancelled;
    private boolean used,writeArmed;
    private long started,powerDiagnosticNanos;
    private final Map<String,Object> state=new LinkedHashMap<>();
    private final List<Map<String,Object>> reads=new ArrayList<>();
    private Observer observer;

    public UsbFlagTest(ReadOnlyFtp.Connector connector,File folder){this.connector=connector;this.folder=folder;}
    public static void require(boolean condition,String reason)throws IOException {if(!condition)throw new Failure(reason);}
    public static String hash(byte[] data){try{return Protocol.hex(MessageDigest.getInstance("SHA-256").digest(data));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String expectedHash(String name){return name.equals("misc")?MISC:name.equals("bootinfo")?BOOTINFO:FACTORY;}
    public static void power(String firmware,int build,int charger,int battery)throws IOException {
        require(FIRMWARE.equals(firmware) && build==2018,"USB test requires installed 1.0.1 build 2018.");
        // Installed 1.0.1: 0 PLUGOUT, 1 PLUGIN, 2 CHARGING, 3 FULL.
        require(charger>=1 && charger<=3,"USB test requires a known plugged-in charging state (1, 2 or 3).");
        require(battery>=60 && battery<=100,"USB test requires at least 60% battery.");
    }
    public static byte[] expected(byte[] original)throws IOException {
        require(original.length==SIZE && hash(original).equals(MISC) && original[OFFSET]==0,"Misc differs from the reviewed original; stop and review.");
        byte[] result=original.clone();result[OFFSET]=1;require(hash(result).equals(ENABLED),"Unexpected enabled configuration.");return result;
    }
    public static void validateBackups(File directory)throws IOException {
        byte[] flash=local(directory,"bes_flash.pass1.bin",FLASH), second=local(directory,"bes_flash.pass2.bin",FLASH);
        require(Arrays.equals(flash,second),"Full-flash snapshots differ.");
        require(hash(Arrays.copyOfRange(flash,0x190000,0x810000)).equals(AP),"Installed AP does not match traced 1.0.1.");
        require(hash(Arrays.copyOfRange(flash,0,0x40000)).equals(BOOT),"Boot region does not match the reviewed backup.");
        for(int i=0;i<CRITICAL.length;i++) {
            String name=CRITICAL[i];byte[] first=local(directory,name+".pass1.bin",SIZE),other=local(directory,name+".pass2.bin",SIZE);
            require(Arrays.equals(first,other) && hash(first).equals(expectedHash(name)),"Critical backup mismatch: "+name+".");
            require(Arrays.equals(first,Arrays.copyOfRange(flash,OFFSETS[i],OFFSETS[i]+SIZE)),"Raw flash and named partition disagree: "+name+".");
        }
    }
    public static byte[] local(File dir,String name,int size)throws IOException {
        File file=new File(dir,name);require(file.isFile() && file.length()==size,"Missing or incomplete local backup: "+name+".");
        byte[] value=Files.readAllBytes(file.toPath());require(value.length==size,"Local backup size changed.");return value;
    }
    private void check()throws IOException {
        require(!cancelled,"USB test cancelled; inspect report before any further action.");
        require(System.nanoTime()-started<900_000_000_000L,"USB test exceeded fifteen minutes.");
    }
    private void update(String phase)throws IOException {state.put("phase",phase);observer.update(new LinkedHashMap<>(state));check();}
    private void begin(Observer observer)throws IOException {
        require(!PUBLIC_RELEASE,"USB configuration writes are disabled in the public build. Private per-device baselines are not distributed.");
        if(used)throw new IllegalStateException("One workflow per instance.");used=true;this.observer=observer;started=System.nanoTime();
        require(folder.isDirectory(),"Missing local output directory.");
        state.put("status","in_progress");state.put("writeAttempted",false);state.put("usbFlagVerified",false);
        state.put("automaticReboot",false);state.put("writeRetries",0);state.put("readbacks",reads);
    }
    private Session session(String user,String password)throws IOException {
        check();require("fs".equals(user),"Only filesystem account allowed.");token(password,128);
        Session s=new Session();active=s;try{s.login(user,password);check();return s;}catch(IOException e){s.close();throw e;}
    }
    private byte[] read(String user,String password,String name,String label,int pass)throws IOException {
        update("Reading "+name+" ("+label+" "+pass+"/2)");byte[] value;
        try(Session s=session(user,password)){value=s.download("RETR","/dev/"+name,name.equals("bes_flash")?FLASH:SIZE);s.finish();}
        int size=name.equals("bes_flash")?FLASH:SIZE;
        String filename=label+name+".pass"+pass+".bin";
        // Preserve even a short read, but do not give it the verified filename.
        File dest=new File(folder,filename+(value.length==size?"":".partial"));
        require(!dest.exists(),"Output already exists.");try(FileOutputStream out=new FileOutputStream(dest)){out.write(value);out.getFD().sync();}
        Map<String,Object> row=new LinkedHashMap<>();row.put("file",dest.getName());row.put("path","/dev/"+name);row.put("bytes",value.length);row.put("sha256",hash(value));reads.add(row);
        update("Saved "+name+" ("+label+" "+pass+"/2)");require(value.length==size,"Short partition read; no write or reboot is allowed.");return value;
    }
    private void pair(String user,String password,String name,String label,String digest)throws IOException {
        byte[] a=read(user,password,name,label,1),b=read(user,password,name,label,2);
        require(Arrays.equals(a,b),"Repeated reads differ: "+name+".");
        if(digest!=null)require(hash(a).equals(digest),"Unexpected complete partition: "+name+". No retry or reboot.");
    }
    public Map<String,Object> prepare(String user,String password,Observer observer)throws IOException {
        begin(observer);
        try {
            update("Checking partition geometry");
            try(Session s=session(user,password)) {ReadOnlyFtp.validateFullFlashSizes(ReadOnlyFtp.partitionSizes(s.download("LIST","/dev",65536)));s.finish();}
            for(String name:CRITICAL)pair(user,password,name,"",expectedHash(name));
            pair(user,password,"bes_flash","",null);validateBackups(folder);
            state.put("backupsVerified",true);state.put("status","usb_backups_ready");
            state.put("preparedAtEpochMs",System.currentTimeMillis());update("Fresh backups verified; copy to PC before enabling USB");
        }catch(IOException e){failure(e);}finally{if(active!=null)active.close();observer.update(new LinkedHashMap<>(state));}
        return new LinkedHashMap<>(state);
    }
    /** Caller must validate the PC receipt and fresh BLE diagnostic before entry. */
    public Map<String,Object> enable(String user,String password,File backups,String firmware,int build,int charger,int battery,long diagnosticNanos,Observer observer)throws IOException {
        begin(observer);
        try {
            power(firmware,build,charger,battery);powerDiagnosticNanos=diagnosticNanos;validateBackups(backups);
            for(String name:CRITICAL)pair(user,password,name,"before-",expectedHash(name));
            expected(local(folder,"before-misc.pass1.bin",SIZE));
            require(System.nanoTime()-diagnosticNanos>=0 && System.nanoTime()-diagnosticNanos<60_000_000_000L,"Power diagnostic expired; no write attempted.");
            update("Preparing the single USB-flag write");
            try(Session s=session(user,password)){s.enableFlag();s.finish();}
            for(String name:CRITICAL)pair(user,password,name,"after-",name.equals("misc")?ENABLED:expectedHash(name));
            state.put("usbFlagVerified",true);state.put("status","usb_flag_enabled_verified");
            update("USB flag verified; restart has not been requested");
        }catch(IOException e){failure(e);}finally{if(active!=null)active.close();observer.update(new LinkedHashMap<>(state));}
        return new LinkedHashMap<>(state);
    }
    private void failure(IOException e) {
        state.put("error",e instanceof Failure?e.getMessage():"Connection or local I/O failure ("+e.getClass().getSimpleName()+").");
        state.put("failedPhase",state.get("phase"));state.put("status",writeArmed?"write_outcome_unverified":"stopped_before_write");
    }
    public void close(){cancelled=true;Session s=active;if(s!=null)s.close();}
    private static final class Failure extends IOException {Failure(String msg){super(msg);}Failure(String op,int code){super(op+" failed (FTP "+code+").");}}
    private static final class Reply {final int code;final String line;Reply(int c,String l){code=c;line=l;}}
    private static void token(String s,int max)throws IOException {require(s!=null && !s.isEmpty() && s.length()<=max && s.chars().noneMatch(c->c<32 || c==127),"Invalid FTP argument.");}
    private static void expect(Reply r,String op,int code)throws IOException {if(r.code!=code)throw new Failure(op,r.code);}

    private final class Session implements Closeable {
        private volatile Socket control,data;private volatile boolean closed;
        private InputStream in;private OutputStream out;
        void login(String user,String password)throws IOException {
            control=connector.connect(21);control.setSoTimeout(10000);if(closed || cancelled){close();throw new Failure("Cancelled.");}
            in=control.getInputStream();out=control.getOutputStream();expect(reply(),"Greeting",220);
            Reply r=command("USER",user);if(r.code==331)r=command("PASS",password);expect(r,"Login",230);expect(command("TYPE","I"),"Binary mode",200);
        }
        Reply command(String verb,String arg)throws IOException {
            check();require(!closed,"FTP session closed.");
            boolean allowed=(verb.equals("USER") && "fs".equals(arg)) || verb.equals("PASS") ||
                (verb.equals("TYPE") && "I".equals(arg)) ||
                (Arrays.asList("EPSV","PASV","QUIT").contains(verb) && arg==null) ||
                (verb.equals("LIST") && "/dev".equals(arg)) ||
                (verb.equals("RETR") && Arrays.asList("/dev/misc","/dev/bootinfo","/dev/factory","/dev/bes_flash").contains(arg)) ||
                (verb.equals("REST") && "611".equals(arg)) ||
                (verb.equals("STOR") && "/dev/misc".equals(arg) && writeArmed);
            require(allowed,"Command outside the USB test allowlist.");if(arg!=null)token(arg,512);
            out.write((verb+(arg==null?"":" "+arg)+"\r\n").getBytes(StandardCharsets.UTF_8));out.flush();return reply();
        }
        String line()throws IOException {
            ByteArrayOutputStream b=new ByteArrayOutputStream();for(int i=0;i<4096;i++){check();int v=in.read();if(v<0)throw new EOFException();if(v=='\n')return new String(b.toByteArray(),StandardCharsets.US_ASCII).replaceFirst("\\r$","");b.write(v);}throw new Failure("FTP reply too long.");
        }
        Reply reply()throws IOException {
            String first=line();require(first.matches("[1-5][0-9]{2}[ -].*"),"Malformed FTP reply.");int c=Integer.parseInt(first.substring(0,3));
            if(first.charAt(3)==' ')return new Reply(c,first);
            for(int i=0;i<64;i++){String next=line();if(next.startsWith(first.substring(0,3)+" "))return new Reply(c,next);}throw new Failure("FTP reply too long.");
        }
        int port(int p)throws IOException {require(p>=1024 && p<=65535,"Invalid passive port.");return p;}
        int passive()throws IOException {
            Reply r=command("EPSV",null);
            if(r.code==229){Matcher m=Pattern.compile("\\((.)(?:\\1){2}([0-9]{1,5})\\1\\)").matcher(r.line);require(m.find(),"Malformed EPSV endpoint.");return port(Integer.parseInt(m.group(2)));}
            require(Arrays.asList(500,502,504,522).contains(r.code),"EPSV rejected.");r=command("PASV",null);expect(r,"PASV",227);
            Matcher m=Pattern.compile("\\((\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})\\)").matcher(r.line);require(m.find(),"Malformed PASV endpoint.");
            int[] v=new int[6];for(int i=0;i<6;i++){v[i]=Integer.parseInt(m.group(i+1));require(v[i]<=255,"Malformed PASV value.");}return port(v[4]*256+v[5]);
        }
        void dataSocket()throws IOException {data=connector.connect(passive());data.setSoTimeout(10000);if(closed || cancelled){close();throw new Failure("Cancelled.");}}
        void preliminary(String verb,String arg)throws IOException {Reply r=command(verb,arg);if(r.code!=125 && r.code!=150)throw new Failure(verb,r.code);}
        void completed()throws IOException {Reply r=reply();if(r.code!=226 && r.code!=250)throw new Failure("Transfer completion",r.code);}
        byte[] download(String verb,String arg,int cap)throws IOException {
            try{dataSocket();preliminary(verb,arg);ByteArrayOutputStream bytes=new ByteArrayOutputStream();InputStream input=data.getInputStream();byte[] b=new byte[16384];int n;
                while((n=input.read(b))!=-1){check();require(bytes.size()+n<=cap,"Transfer exceeded its size limit.");bytes.write(b,0,n);}data.close();data=null;completed();return bytes.toByteArray();
            }catch(IOException e){close();throw e;}
        }
        void enableFlag()throws IOException {
            require(!writeArmed,"A write has already been attempted.");dataSocket();Reply r=command("REST","611");state.put("restartReply",r.code);
            if(r.code!=320 && r.code!=350)throw new Failure("REST",r.code);
            require(System.nanoTime()-powerDiagnosticNanos>=0 && System.nanoTime()-powerDiagnosticNanos<60_000_000_000L,"Power diagnostic expired before STOR; no write attempted.");
            // Observer synchronously persists report and one-attempt lock BEFORE STOR.
            writeArmed=true;state.put("writeAttempted",true);state.put("target","/dev/misc");state.put("offset",OFFSET);state.put("payloadBytes",1);
            update("Write armed; do not disconnect or reboot");preliminary("STOR","/dev/misc");check();
            data.getOutputStream().write(new byte[]{1});data.getOutputStream().flush();data.close();data=null;completed();
        }
        void finish()throws IOException {expect(command("QUIT",null),"Session completion",221);close();}
        public void close(){closed=true;for(Socket s:new Socket[]{data,control})if(s!=null)try{s.close();}catch(IOException ignored){}}
    }
}
