package local.halliday.g2lab;

import android.bluetooth.*;
import android.os.*;

/** One connection, one callback owner. Dispatch and ownership changes use the main looper. */
final class GattCallbackRouter extends BluetoothGattCallback {
    private final Handler main=new Handler(Looper.getMainLooper());
    private BluetoothGattCallback owner;
    GattCallbackRouter(BluetoothGattCallback owner){this.owner=owner;}
    void bind(BluetoothGattCallback next){owner=next;}
    private interface Event {void deliver(BluetoothGattCallback callback);}
    private void post(Event e){main.post(()->{BluetoothGattCallback c=owner;if(c!=null)e.deliver(c);});}
    @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){post(c->c.onConnectionStateChange(g,status,state));}
    @Override public void onServicesDiscovered(BluetoothGatt g,int status){post(c->c.onServicesDiscovered(g,status));}
    @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){post(c->c.onDescriptorWrite(g,d,status));}
    @Override public void onMtuChanged(BluetoothGatt g,int mtu,int status){post(c->c.onMtuChanged(g,mtu,status));}
    @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){post(o->o.onCharacteristicWrite(g,c,status));}
    @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){
        byte[] copy=value.clone();post(o->o.onCharacteristicChanged(g,c,copy));
    }
}
