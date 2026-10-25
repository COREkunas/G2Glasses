package android.bluetooth;
import java.util.*;
public class BluetoothGatt {
    public final List<byte[]> writes=new ArrayList<>();public int status,disconnects,closes;
    public int writeCharacteristic(BluetoothGattCharacteristic c,byte[] bytes,int type){writes.add(bytes.clone());return status;}
    public void disconnect(){disconnects++;}public void close(){closes++;}
}
