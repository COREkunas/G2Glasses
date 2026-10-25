package local.halliday.g2lab;
import java.io.IOException;
import java.util.Arrays;
/** One isolated, read-only control connection per requested RAM stage. */
public final class RamFtpRead {
    public static byte[] twice(ReadOnlyFtp ftp,String id,int stage) throws IOException {
        try {
            byte[] first=ftp.readRamDiagnostic(id,stage);
            RamDiagnosticRecord.validate(id,stage,first);
            byte[] second=ftp.readRamDiagnostic(id,stage);
            RamDiagnosticRecord.validate(id,stage,second);
            if(!Arrays.equals(first,second))throw new IOException("RAM diagnostic repeated reads differ.");
            return first;
        } finally {
            // A refused RETR can leave an implementation-specific reply/data
            // state. Never reuse this control connection for a later stage.
            ftp.close();
        }
    }
}
