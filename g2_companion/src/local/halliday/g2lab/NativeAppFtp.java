package local.halliday.g2lab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Fixed RAM mailboxes only. Does not transfer modules or firmware. */
public final class NativeAppFtp implements Closeable {
    public static final class Failure extends IOException {
        final int code;
        Failure(String text){super(text);code=0;}
        Failure(String action,int code){super(action+" failed (FTP "+code+").");this.code=code;}
    }
    private static final class Reply {final int code;final String line;Reply(int c,String s){code=c;line=s;}}
    private final ReadOnlyFtp.Connector connector;
    private volatile Socket control,data;private volatile boolean closed;
    private InputStream in;private OutputStream out;
    public NativeAppFtp(ReadOnlyFtp.Connector connector){this.connector=connector;}
    public void login(String user,String password)throws IOException {
        if(!"fs".equals(user))throw new Failure("Only the existing filesystem account is permitted.");
        token(password,128);control=connector.connect(21);control.setSoTimeout(6000);
        if(closed){close();throw new Failure("Cancelled.");}
        in=control.getInputStream();out=control.getOutputStream();expect(reply(),"Greeting",220);
        Reply r=command("USER",user);if(r.code==331)r=command("PASS",password);
        expect(r,"Login",230);expect(command("TYPE","I"),"Binary mode",200);
    }
    public byte[] status()throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();transfer("RETR",NativeAppRecord.STATUS,bytes,128);return bytes.toByteArray();
    }
    public void send(NativeAppRecord.Status status,int operation,int sequence)throws IOException {
        byte[] payload=NativeAppRecord.command(status,operation,sequence);
        store(NativeAppRecord.COMMAND,payload);
    }
    public void route(byte[] payload)throws IOException {
        if(payload.length!=384||NativeAppRecord.word(payload,0)!=0x3156414e||NativeAppRecord.word(payload,380)!=NativeAppRecord.hash(payload,380))throw new Failure("Invalid navigation record.");
        store("/tmp/mindy-nav.route",payload);
    }
    private void store(String path,byte[] payload)throws IOException {
        data=connector.connect(passivePort());data.setSoTimeout(6000);
        try {
            Reply r=command("STOR",path);
            if(r.code!=125&&r.code!=150)throw new Failure("App command",r.code);
            data.getOutputStream().write(payload);data.getOutputStream().flush();data.close();data=null;
            r=reply();if(r.code!=226&&r.code!=250)throw new Failure("App command transfer",r.code);
        }catch(IOException e){close();throw e;}
        // Caller requires QUIT then a matching sequence in device-owned status.
    }
    private Reply command(String verb, String arg) throws IOException {
        if (closed) throw new Failure("FTP cancelled.");
        if (!Arrays.asList("USER","PASS","TYPE","EPSV","PASV","RETR","STOR","QUIT").contains(verb))
            throw new Failure("FTP command is outside the app mailbox protocol.");
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
                if(count+n>limit) throw new Failure("Native app status exceeded its fixed byte limit.");
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
    public void finish() throws IOException { expect(command("QUIT",null),"Session completion",221);close(); }
    public void close() {closed=true;for(Socket s:new Socket[]{data,control})if(s!=null)try{s.close();}catch(IOException ignored){}}
}
