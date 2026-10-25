package local.halliday.g2lab;

import java.io.*;
import java.security.*;
import java.util.Arrays;

/** Only a completed, matching pair of binary reads receives a .bin filename. */
public final class BackupFiles {
    public static final class Result {
        public final long size; public final String sha256;
        public final String localFile,secondFile,secondSha256;
        public final boolean repeatedReadMatches;
        Result(long size,byte[] hash,String localFile,byte[] secondHash,String secondFile) {
            this.size=size;sha256=Protocol.hex(hash);this.localFile=localFile;
            secondSha256=Protocol.hex(secondHash);this.secondFile=secondFile;
            repeatedReadMatches=Arrays.equals(hash,secondHash);
        }
    }
    public static Result saveTwice(ReadOnlyFtp ftp,String name,File directory) throws IOException {
        return saveTwice(ftp,name,directory,-1);
    }
    public static Result saveTwice(ReadOnlyFtp ftp,String name,File directory,long expectedSize) throws IOException {
        return save(ftp,name,directory,expectedSize,false);
    }
    /** Keep both complete full-flash snapshots, including when active settings differ. */
    public static Result saveFullFlashTwice(ReadOnlyFtp ftp,File directory) throws IOException {
        return save(ftp,"bes_flash",directory,ReadOnlyFtp.FULL_FLASH_BYTES,true);
    }
    private static Result save(ReadOnlyFtp ftp,String name,File directory,long expectedSize,boolean keepSnapshots) throws IOException {
        if(!ReadOnlyFtp.PARTITIONS.contains(name)) throw new IOException("Unknown partition.");
        File first=new File(directory,name+".pass1.part"),second=new File(directory,name+".pass2.part"),done=new File(directory,name+".bin");
        File snapshot1=new File(directory,name+".pass1.bin"),snapshot2=new File(directory,name+".pass2.bin");
        if(first.exists() || second.exists() || done.exists() || snapshot1.exists() || snapshot2.exists()) throw new IOException("Backup target already exists.");
        long[] lengths=new long[2];byte[][] hashes=new byte[2][];
        for(int pass=0;pass<2;pass++) {
            MessageDigest digest;
            try { digest=MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable."); }
            try(FileOutputStream file=new FileOutputStream(pass==0?first:second);
                DigestOutputStream output=new DigestOutputStream(file,digest)) {
                lengths[pass]=ftp.readPartition(name,output); output.flush();file.getFD().sync();
            }
            hashes[pass]=digest.digest();
        }
        if(lengths[0]==0 || lengths[0]!=lengths[1] || (!keepSnapshots && !Arrays.equals(hashes[0],hashes[1])))
            throw new IOException("Repeated reads differ or are empty; partial files retained, backup unverified.");
        if(expectedSize!=-1 && lengths[0]!=expectedSize)
            throw new IOException("Completed read size differs from the device listing; backup unverified.");
        boolean matches=Arrays.equals(hashes[0],hashes[1]);
        File destination=matches?done:snapshot1;
        if(!first.renameTo(destination)) throw new IOException("Could not finalize local backup.");
        if(keepSnapshots) {
            if(!second.renameTo(snapshot2)) throw new IOException("Could not finalize second flash snapshot.");
        } else if(!second.delete()) throw new IOException("Verified data saved, but local duplicate cleanup failed.");
        return new Result(lengths[0],hashes[0],destination.getName(),hashes[1],keepSnapshots?snapshot2.getName():null);
    }
}
