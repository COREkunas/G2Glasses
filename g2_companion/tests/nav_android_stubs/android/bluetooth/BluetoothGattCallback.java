package android.bluetooth;
public class BluetoothGattCallback {
    public void onConnectionStateChange(BluetoothGatt g,int status,int state){}
    public void onServicesDiscovered(BluetoothGatt g,int status){}
    public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){}
    public void onMtuChanged(BluetoothGatt g,int mtu,int status){}
    public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){}
    public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){}
}
