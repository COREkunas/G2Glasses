package android.bluetooth;
import java.util.UUID;
public final class BluetoothGattCharacteristic {
    public static final int PROPERTY_WRITE_NO_RESPONSE=4,PROPERTY_WRITE=8,WRITE_TYPE_NO_RESPONSE=1,WRITE_TYPE_DEFAULT=2;
    private final UUID id;public BluetoothGattCharacteristic(String id){this.id=UUID.fromString(id);}
    public UUID getUuid(){return id;}public int getProperties(){return PROPERTY_WRITE_NO_RESPONSE;}
}
