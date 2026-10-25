package local.halliday.g2lab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Loopback server fixtures. No glasses, credentials or external network. */
public final class ReadOnlyFtpTest {
    private static int checks;
    private static void check(boolean condition,String label) {checks++;if(!condition)throw new AssertionError(label);}
    private static final byte[] PAYLOAD=new byte[65537];
    static {new Random(5831).nextBytes(PAYLOAD);}
    static class Server implements AutoCloseable {
        final ServerSocket listener; final Thread thread;final List<String> verbs=new ArrayList<>();
        final String mode;volatile Throwable failure;Socket control;ServerSocket passive;int retrieves;
        Server(String mode) throws IOException {
            this.mode=mode;listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            thread=new Thread(()->{try{serve();}catch(SocketException|EOFException expected){}catch(Throwable e){failure=e;}},"fixture");thread.setDaemon(true);thread.start();
        }
        ReadOnlyFtp client() {return new ReadOnlyFtp(port->{Socket s=new Socket();s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),port==21?listener.getLocalPort():port),2000);return s;});}
        void serve() throws Exception {
            control=listener.accept();control.setSoTimeout(5000);
            BufferedReader input=new BufferedReader(new InputStreamReader(control.getInputStream(),StandardCharsets.UTF_8));
            PrintWriter out=new PrintWriter(new OutputStreamWriter(control.getOutputStream(),StandardCharsets.US_ASCII),true);
            out.print("220-fixture\r\nfixture detail\r\n220 ready\r\n");out.flush();String line;
            while((line=input.readLine())!=null) {
                String[] pair=line.split(" ",2);String verb=pair[0],arg=pair.length==2?pair[1]:"";verbs.add(verb);
                switch(verb) {
                    case "USER": check(arg.equals("fs"),"fs account");out.print("331 password\r\n");break;
                    case "PASS": out.print(mode.equals("loginDenied")?"530 synthetic-password-must-not-leak\r\n":"230 ok\r\n");break;
                    case "TYPE":check(arg.equals("I"),"binary mode");out.print("200 binary\r\n");break;
                    case "PWD":out.print("257 \"/\" is working directory\r\n");break;
                    case "EPSV":
                        if(mode.equals("pasv"))out.print("502 unsupported\r\n");
                        else if(mode.equals("badPort"))out.print("229 (|||22|)\r\n");
                        else{passive=new ServerSocket(0,1,InetAddress.getLoopbackAddress());out.print("229 (|||"+passive.getLocalPort()+"|)\r\n");}break;
                    case "PASV":passive=new ServerSocket(0,1,InetAddress.getLoopbackAddress());int p=passive.getLocalPort();out.print("227 (203,0,113,9,"+(p/256)+","+(p%256)+")\r\n");break;
                    case "LIST":case "RETR":
                        if(verb.equals("RETR"))retrieves++;
                        if((mode.equals("denied") && arg.equals("/proc/mtd")) || ((mode.equals("ramMissing") || mode.equals("ramMissingExtra")) && retrieves==1)){
                            out.print("550 synthetic secret\r\n");
                            if(mode.equals("ramMissingExtra"))out.print("226 extra synthetic reply\r\n");
                            passive.close();break;
                        }
                        out.print("150 data\r\n");out.flush();
                        try(Socket d=passive.accept()) {
                            byte[] data=verb.equals("LIST")?"crw-r--r-- 1 root root 0 Jan 1 2026 ap\r\nlrw-r--r-- 1 root root 0 Jan 1 2026 misc -> /dev/other\r\ncrw-r--r-- 1 root root 0 Jan 1 2026 factory\r\n".getBytes(StandardCharsets.UTF_8):
                                arg.equals("/etc/init.d/rcS")?(mode.equals("tooLarge")?PAYLOAD:"#!/bin/sh\nfixture\n".getBytes(StandardCharsets.UTF_8)):PAYLOAD.clone();
                            if(arg.equals("/dev/bes_flash")) {
                                int extra=mode.equals("fullOversize")?1:mode.equals("fullShort")?-1:0;
                                data=new byte[(int)ReadOnlyFtp.FULL_FLASH_BYTES+extra];
                                for(int i=0;i<data.length;i++)data[i]=(byte)(i*17+3);
                                if(mode.equals("fullMismatch") && retrieves==2)data[0x810100]^=1;
                            }
                            if(mode.equals("mismatch") && retrieves==2)data[100]^=1;
                            if(arg.startsWith("/tmp/g2dj_"))data=mode.equals("ramOversize")?new byte[513]:mode.equals("ramShort")?new byte[256]:RamDiagnosticRecordTest.fixture();
                            d.getOutputStream().write(data);
                        }finally{passive.close();}
                        out.print(mode.equals("incomplete")?"426 data error\r\n":"226 complete\r\n");break;
                    case "QUIT":out.print("221 bye\r\n");out.flush();return;
                    default:throw new AssertionError("Forbidden FTP verb: "+verb);
                }
                out.flush();
            }
        }
        public void close() throws Exception {
            if(control!=null)control.close();if(passive!=null)passive.close();listener.close();thread.join(2000);
            if(failure!=null)throw new AssertionError("Fixture failure",failure);
        }
    }
    public static void main(String[] args) throws Exception {
        Path base=Files.createTempDirectory(Paths.get(args[0]),"ftp-fixtures-");
        for(String mode:new String[]{"normal","pasv"})try(Server s=new Server(mode);ReadOnlyFtp f=s.client()) {
            f.login("fs","synthetic");check(f.pwd().equals("/"),"PWD parser");
            check(ReadOnlyFtp.partitions(f.listDevices()).equals(new LinkedHashSet<>(Arrays.asList("ap","factory"))),"symlinks excluded");
            check(new String(f.readSmall("/etc/init.d/rcS"),StandardCharsets.UTF_8).equals("#!/bin/sh\nfixture\n"),"exact text");
            File directory=Files.createDirectory(base.resolve(mode)).toFile();
            BackupFiles.Result r=BackupFiles.saveTwice(f,"ap",directory,PAYLOAD.length);
            check(r.size==PAYLOAD.length,"complete length");check(Arrays.equals(Files.readAllBytes(new File(directory,"ap.bin").toPath()),PAYLOAD),"binary preserved");
            check(r.sha256.equals(Protocol.hex(java.security.MessageDigest.getInstance("SHA-256").digest(PAYLOAD))),"SHA256");
            check(!new File(directory,"ap.pass2.part").exists(),"verified duplicate removed");f.quit();
        }
        for(String mode:new String[]{"mismatch","incomplete"})try(Server s=new Server(mode);ReadOnlyFtp f=s.client()) {
            f.login("fs","synthetic");File directory=Files.createDirectory(base.resolve(mode)).toFile();
            try{BackupFiles.saveTwice(f,"ap",directory);throw new AssertionError("Invalid backup accepted");}catch(IOException expected){checks++;}
            check(!new File(directory,"ap.bin").exists(),"no completed backup on "+mode);
        }
        for(String mode:new String[]{"tooLarge","badPort","loginDenied"})try(Server s=new Server(mode);ReadOnlyFtp f=s.client()) {
            try{f.login("fs","synthetic");f.readSmall("/etc/init.d/rcS");throw new AssertionError("Invalid FTP accepted");}
            catch(IOException expected){checks++;check(!expected.getMessage().contains("synthetic"),"no server credential echo");}
        }
        try(Server s=new Server("denied");ReadOnlyFtp f=s.client()) {
            f.login("fs","synthetic");try{f.readSmall("/proc/mtd");throw new AssertionError("Denied read");}catch(ReadOnlyFtp.Failure e){check(e.code==550,"denied code");}
            check(f.readSmall("/etc/init.d/rcS").length>0,"control reusable after denied path");
            for(String name:new String[]{"../misc","ap\r\nSTOR x","/dev/ap","data","ttyS0"}) {
                try{f.readPartition(name,new ByteArrayOutputStream());throw new AssertionError("Unknown partition");}catch(IOException expected){checks++;}
            }
            try{f.readSmall("/data2/record/private.opus");throw new AssertionError("Private path");}catch(IOException expected){checks++;}
        }
        ReadOnlyFtp never=new ReadOnlyFtp(port->{throw new AssertionError("Should not connect");});
        try{never.login("fs","x\r\nSTOR secret");throw new AssertionError("CRLF credential");}catch(IOException expected){checks++;}
        try{never.login("ota","synthetic");throw new AssertionError("OTA mode");}catch(IOException expected){checks++;}
        String realListing="-rw-rw-rw- 1 1001 512 6815744 Jan 1 1970 ap\r\n-rw-rw-rw- 1 1001 512 8192 Jan 1 1970 misc\r\nlrw-rw-rw- 1 1001 512 8192 Jan 1 1970 factory -> /dev/misc\r\ncrw-rw-rw- 1 1001 512 0 Jan 1 1970 ttyS0\r\n";
        Map<String,Long> sizes=ReadOnlyFtp.partitionSizes(realListing.getBytes(StandardCharsets.UTF_8));
        check(sizes.size()==2 && sizes.get("ap")==6815744L && sizes.get("misc")==8192L,"live listing sizes, no symlinks or character devices");
        for(String bad:new String[]{"0","-1","999999999","bad"}) {
            try{ReadOnlyFtp.partitionSizes(("-rw-rw-rw- 1 1001 512 "+bad+" Jan 1 1970 ap\n").getBytes(StandardCharsets.UTF_8));throw new AssertionError("bad listing size");}catch(IOException expected){checks++;}
        }
        try{ReadOnlyFtp.partitionSizes((realListing+realListing).getBytes(StandardCharsets.UTF_8));throw new AssertionError("duplicate listing");}catch(IOException expected){checks++;}
        try(Server s=new Server("normal");ReadOnlyFtp f=s.client()) {
            f.login("fs","synthetic");File directory=Files.createDirectory(base.resolve("wrongListedSize")).toFile();
            try{BackupFiles.saveTwice(f,"ap",directory,PAYLOAD.length+1);throw new AssertionError("short equal pair accepted");}catch(IOException expected){checks++;}
            check(!new File(directory,"ap.bin").exists(),"identical short reads cannot finalize against larger listed size");
        }
        Map<String,Long> fullSizes=new LinkedHashMap<>();
        for(String name:ReadOnlyFtp.FULL_FLASH_PARTITIONS)fullSizes.put(name,name.equals("bes_flash")?ReadOnlyFtp.FULL_FLASH_BYTES:8192L);
        ReadOnlyFtp.validateFullFlashSizes(fullSizes);checks++;
        check(!ReadOnlyFtp.FIRMWARE_PARTITIONS.contains("bes_flash"),"legacy workflow remains named partitions only");
        for(String name:ReadOnlyFtp.FULL_FLASH_PARTITIONS) {
            Map<String,Long> missing=new HashMap<>(fullSizes);missing.remove(name);
            try{ReadOnlyFtp.validateFullFlashSizes(missing);throw new AssertionError("missing required node");}catch(IOException expected){checks++;}
            Map<String,Long> wrong=new HashMap<>(fullSizes);wrong.put(name,fullSizes.get(name)-1);
            try{ReadOnlyFtp.validateFullFlashSizes(wrong);throw new AssertionError("wrong geometry");}catch(IOException expected){checks++;}
        }
        for(String mode:new String[]{"fullNormal","fullMismatch"})try(Server s=new Server(mode);ReadOnlyFtp f=s.client()) {
            f.login("fs","synthetic");File directory=Files.createDirectory(base.resolve(mode)).toFile();
            BackupFiles.Result result=BackupFiles.saveFullFlashTwice(f,directory);
            boolean matches=mode.equals("fullNormal");
            check(result.size==ReadOnlyFtp.FULL_FLASH_BYTES,"exact full-flash size");
            check(result.repeatedReadMatches==matches,"raw snapshot equality status");
            check(result.localFile.equals(matches?"bes_flash.bin":"bes_flash.pass1.bin"),"differing snapshot cannot have canonical verified filename");
            check(result.secondFile.equals("bes_flash.pass2.bin"),"second complete snapshot retained");
            check(new File(directory,result.localFile).length()==ReadOnlyFtp.FULL_FLASH_BYTES && new File(directory,result.secondFile).length()==ReadOnlyFtp.FULL_FLASH_BYTES,"both disk snapshots full length");
            byte[] second=Files.readAllBytes(new File(directory,result.secondFile).toPath());
            check(result.secondSha256.equals(Protocol.hex(java.security.MessageDigest.getInstance("SHA-256").digest(second))),"second snapshot independent digest");
            check(result.sha256.equals(result.secondSha256)==matches,"both hashes reflect actual equality");
            try{BackupFiles.saveFullFlashTwice(f,directory);throw new AssertionError("overwrote backup");}catch(IOException expected){checks++;}
            check(s.verbs.stream().allMatch(v->Arrays.asList("USER","PASS","TYPE","EPSV","RETR").contains(v)),"full backup sends no writing commands");
        }
        for(String mode:new String[]{"fullShort","fullOversize"})try(Server s=new Server(mode);ReadOnlyFtp f=s.client()) {
            f.login("fs","synthetic");File directory=Files.createDirectory(base.resolve(mode)).toFile();
            try{BackupFiles.saveFullFlashTwice(f,directory);throw new AssertionError("wrong full-flash size accepted");}catch(IOException expected){checks++;}
            check(!new File(directory,"bes_flash.bin").exists() && !new File(directory,"bes_flash.pass1.bin").exists(),"truncated or oversize copies never finalize");
        }
        System.out.println("Read-only FTP and backup checks passed: "+checks+"; loopback fixtures only.");
    }
}
