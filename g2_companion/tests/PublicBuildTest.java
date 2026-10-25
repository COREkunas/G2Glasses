package local.halliday.g2lab;
import java.io.*;
public final class PublicBuildTest {
    public static void main(String[] args)throws Exception {
        if(!UsbFlagTest.PUBLIC_RELEASE)throw new AssertionError("Public flash gate disabled");
        try(UsbFlagTest t=new UsbFlagTest(port->{throw new AssertionError("Must not connect");},new File("."))){
            try {t.prepare("fs","synthetic",s->{});throw new AssertionError("Unexpected USB write workflow");}
            catch(IOException expected){if(!expected.getMessage().contains("disabled"))throw expected;}
        }
        System.out.println("Public USB workflow refuses before connecting.");
    }
}
