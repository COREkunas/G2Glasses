package local.halliday.g2lab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Narrow FTP reader. All sockets use the caller's selected network; no write API. */
public final class ReadOnlyFtp implements Closeable {
    public interface Connector { Socket connect(int port) throws IOException; }
    public static final Set<String> FIRMWARE_PARTITIONS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "misc", "bootinfo", "factory", "ota_info", "ota_flag", "ap", "ota_b", "apc1", "hifi", "updll", "updll1", "smfjson")));
    public static final Set<String> FULL_FLASH_PARTITIONS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
        "misc", "bootinfo", "factory", "bes_flash")));
    public static final Set<String> PARTITIONS;
    static {
        LinkedHashSet<String> names=new LinkedHashSet<>(FIRMWARE_PARTITIONS);names.addAll(FULL_FLASH_PARTITIONS);
        PARTITIONS=Collections.unmodifiableSet(names);
    }
    public static final long FULL_FLASH_BYTES=16L*1024*1024;
    public static void validateFullFlashSizes(Map<String,Long> sizes) throws IOException {
        for(String name:FULL_FLASH_PARTITIONS) {
            long expected=name.equals("bes_flash")?FULL_FLASH_BYTES:8192;
            if(!sizes.containsKey(name) || sizes.get(name)!=expected)
                throw new Failure("Full-flash backup requires /dev/"+name+" to list exactly "+expected+" bytes.");
        }
    }
    private static final Set<String> FILES = new HashSet<>(Arrays.asList(
        "/etc/init.d/rcS", "/proc/mounts", "/proc/mtd", "/proc/partitions"));
    public static final class Failure extends IOException {
        public final int code;
        Failure(String operation, int code) { super(operation + " failed (FTP " + code + ")."); this.code=code; }
        Failure(String reason) { super(reason); code=0; }
    }
    private static final class Reply {
        final int code; final String line;
        Reply(int code, String line) { this.code=code; this.line=line; }
    }
    private final Connector connector;
    private volatile Socket control, data;
    private volatile boolean closed;
    private InputStream in;
    private OutputStream out;
    public ReadOnlyFtp(Connector connector) { this.connector=connector; }

    public void login(String user, String password) throws IOException {
        if (!"fs".equals(user)) throw new Failure("Only the filesystem account is permitted.");
        token(password, 128);
        control=connector.connect(21); control.setSoTimeout(10000);
        if (closed) { close(); throw new Failure("FTP cancelled."); }
        in=control.getInputStream(); out=control.getOutputStream();
        expect(reply(), "Greeting", 220);
        Reply r=command("USER", user);
        if (r.code==331) r=command("PASS", password);
        expect(r,"Login",230); expect(command("TYPE","I"),"Binary mode",200);
    }
    public String pwd() throws IOException {
        Reply r=command("PWD",null); expect(r,"PWD",257);
        Matcher m=Pattern.compile("^257 \\\"((?:[^\\\"]|\\\"\\\")*)\\\"").matcher(r.line);
        if (!m.find()) throw new Failure("Unrecognized working-directory reply.");
        String path=m.group(1).replace("\"\"","\"");
        if (!path.startsWith("/") || path.length()>256 || path.chars().anyMatch(c->c<32 || c==127))
            throw new Failure("Invalid working-directory reply.");
        return path;
    }
    public byte[] listDevices() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); transfer("LIST","/dev",bytes,65536);
        return bytes.toByteArray();
    }
    public static Set<String> partitions(byte[] listing) {
        Set<String> found=new LinkedHashSet<>();
        for(String line:new String(listing,StandardCharsets.UTF_8).split("\\r?\\n")) {
            // No symlink following and no arbitrary path derived from server text.
            if (line.startsWith("l") || line.contains(" -> ")) continue;
            String trimmed=line.trim(); String[] fields=trimmed.split("\\s+");
            if(fields.length>0 && PARTITIONS.contains(fields[fields.length-1])) found.add(fields[fields.length-1]);
        }
        return found;
    }
    public byte[] readSmall(String path) throws IOException {
        if (!FILES.contains(path)) throw new Failure("File is outside the diagnostic allowlist.");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); transfer("RETR",path,bytes,65536);
        return bytes.toByteArray();
    }
    public byte[] readRamDiagnostic(String id,int stage) throws IOException {
        String path=RamDiagnosticRecord.path(id,stage);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        transfer("RETR",path,bytes,512);
        return bytes.toByteArray();
    }
    public static Map<String,Long> partitionSizes(byte[] listing) throws IOException {
        Map<String,Long> found=new LinkedHashMap<>();
        for(String line:new String(listing,StandardCharsets.UTF_8).split("\\r?\\n")) {
            if(!line.startsWith("-") || line.contains(" -> ")) continue;
            String[] fields=line.trim().split("\\s+");
            if(fields.length!=9 || !PARTITIONS.contains(fields[8])) continue;
            long size;
            try {size=Long.parseLong(fields[4]);}catch(NumberFormatException e){throw new Failure("Invalid listed partition size.");}
            if(size<=0 || size>16L*1024*1024 || found.put(fields[8],size)!=null)
                throw new Failure("Invalid or duplicate listed partition size.");
        }
        return found;
    }
    public long readPartition(String name, OutputStream target) throws IOException {
        if (!PARTITIONS.contains(name)) throw new Failure("Partition is outside the backup allowlist.");
        // A cap, not an assumption about the installed partition geometry.
        long cap=name.equals("ap") || name.equals("bes_flash") ? FULL_FLASH_BYTES :
            Arrays.asList("apc1","hifi","ota_b","updll","updll1").contains(name) ? 8L*1024*1024 : 1024L*1024;
        return transfer("RETR","/dev/"+name,target,cap);
    }
    private Reply command(String verb, String arg) throws IOException {
        if (closed) throw new Failure("FTP cancelled.");
        if (!Arrays.asList("USER","PASS","TYPE","PWD","EPSV","PASV","LIST","RETR","QUIT").contains(verb))
            throw new Failure("FTP command is not read-only.");
        if (arg!=null) token(arg,512);
        out.write((verb+(arg==null?"":" "+arg)+"\r\n").getBytes(StandardCharsets.UTF_8)); out.flush();
        return reply();
    }
    private static void token(String text,int max) throws IOException {
        if (text==null || text.isEmpty() || text.length()>max || text.chars().anyMatch(c->c<32 || c==127))
            throw new Failure("Invalid FTP argument.");
    }
    private String line() throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        for(int i=0;i<4096;i++) {
            int v=in.read(); if(v<0) throw new EOFException("FTP control connection closed.");
            if(v=='\n') return new String(b.toByteArray(),StandardCharsets.US_ASCII).replaceFirst("\\r$","");
            b.write(v);
        }
        throw new Failure("FTP reply exceeded the line limit.");
    }
    private Reply reply() throws IOException {
        String first=line();
        if (!first.matches("[1-5][0-9]{2}[ -].*")) throw new Failure("Malformed FTP reply.");
        int code=Integer.parseInt(first.substring(0,3));
        if(first.charAt(3)==' ') return new Reply(code,first);
        for(int i=0;i<64;i++) {
            String next=line(); if(next.startsWith(first.substring(0,3)+" ")) return new Reply(code,next);
        }
        throw new Failure("FTP multiline reply exceeded the limit.");
    }
    private static void expect(Reply r,String action,int code) throws IOException {
        if(r.code!=code) throw new Failure(action,r.code); // Server text deliberately excluded.
    }
    private int passivePort() throws IOException {
        Reply r=command("EPSV",null);
        if(r.code==229) {
            Matcher m=Pattern.compile("\\((.)(?:\\1){2}([0-9]{1,5})\\1\\)").matcher(r.line);
            if(!m.find()) throw new Failure("Malformed EPSV endpoint.");
            return port(Integer.parseInt(m.group(2)));
        }
        if(!Arrays.asList(500,502,504,522).contains(r.code)) throw new Failure("EPSV",r.code);
        r=command("PASV",null); expect(r,"PASV",227);
        Matcher m=Pattern.compile("\\((\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})\\)").matcher(r.line);
        if(!m.find()) throw new Failure("Malformed PASV endpoint.");
        int[] v=new int[6];for(int i=0;i<6;i++) { v[i]=Integer.parseInt(m.group(i+1)); if(v[i]>255) throw new Failure("Invalid PASV endpoint."); }
        // Ignore advertised host: both data/control connections stay on the pinned G2 endpoint.
        return port(v[4]*256+v[5]);
    }
    private static int port(int p) throws IOException {
        if(p<1024 || p>65535) throw new Failure("Unexpected FTP passive port."); return p;
    }
    private long transfer(String verb,String path,OutputStream target,long limit) throws IOException {
        long start=System.nanoTime(),count=0;
        boolean accepted=false;
        data=connector.connect(passivePort()); data.setSoTimeout(15000);
        try {
            Reply preliminary=command(verb,path);
            if(preliminary.code!=125 && preliminary.code!=150) throw new Failure(verb,preliminary.code);
            accepted=true;
            InputStream input=data.getInputStream();byte[] buffer=new byte[32768];int n;
            while((n=input.read(buffer))!=-1) {
                if(closed) throw new Failure("FTP cancelled.");
                if(System.nanoTime()-start>180_000_000_000L) throw new Failure("Transfer exceeded the three-minute limit.");
                if(count+n>limit) throw new Failure("Transfer exceeded the byte limit; no complete backup claimed.");
                target.write(buffer,0,n);count+=n;
            }
            data.close();data=null;
            Reply done=reply();
            if(done.code!=226 && done.code!=250) throw new Failure("Transfer completion",done.code);
            return count;
        } catch(IOException e) {
            // A rejected RETR leaves control usable; incomplete data/protocol streams do not.
            if(accepted || !(e instanceof Failure) || ((Failure)e).code==0 || ((Failure)e).code<400) close();
            throw e;
        } finally { if(data!=null) { try { data.close(); } catch(IOException ignored) {} data=null; } }
    }
    public void quit() { try { if(!closed && out!=null) command("QUIT",null); } catch(IOException ignored) {} close(); }
    public void close() {
        closed=true;
        for(Socket s:new Socket[]{data,control}) if(s!=null) try{s.close();}catch(IOException ignored){}
    }
}
