package local.halliday.g2lab;

import android.bluetooth.*;
import android.os.*;
import java.util.*;

/** Adopts the owner's already authenticated GATT session; never reconnects or repeats login. */
final class NavBleClient {
    interface Host {void connected();void failed(String reason);}
    private Host host;private final Handler main=new Handler(Looper.getMainLooper());
    private final Protocol.Decoder decoder=new Protocol.Decoder();
    private BluetoothGatt gatt;private BluetoothGattCharacteristic writer;private GattCallbackRouter router;private byte[] out;
    private int sid,pending=-1,command=-1,offset,mtu=23;private boolean writing,closed,authenticated;
    private String failure="Navigation Bluetooth session ended.";
    private Protocol.Packet answer;private Runnable noteDone;
    private java.util.function.Consumer<Protocol.Packet> requestDone;
    private final Runnable timeout=()->fail("G2 did not reply to "+stage(command)+". Last card may remain on the glasses.");
    NavBleClient(BluetoothGatt g,BluetoothGattCharacteristic w,GattCallbackRouter r,int packetMtu,int lastSid){
        if(g==null||w==null||r==null||packetMtu<23||(lastSid&~255)!=0)throw new IllegalArgumentException();
        gatt=g;writer=w;router=r;mtu=packetMtu;sid=lastSid;authenticated=true;
        r.bind(callback);
        log("Adopted authenticated G2 session; last id="+sid+"; packet capacity="+(mtu-3)+". No disconnect or new handshake.");
    }
    void begin(Host next){
        if(host!=null)throw new IllegalStateException("Navigation session already has an owner");
        host=next;if(closed)next.failed(failure);else next.connected();
    }
    private static String stage(int cmd){
        switch(cmd){case 0:return "protocol version (0x00)";case 1:return "account authentication (0x01)";
            case 3:return "heartbeat (0x03)";case 0x3e:return "dashboard note (0x3e)";default:return "connection setup";}
    }
    // Protocol metadata only: never log auth/ACK payloads, device addresses or route text.
    private static void log(String message){android.util.Log.i("G2NavBLE",message);}
    boolean idle(){return authenticated&&!closed&&pending<0&&out==null&&!writing;}
    void note(NavRelayState.Card card,Runnable done){if(!idle())return;noteDone=done;send(0x3e,5,card.payload());}
    void heartbeat(){if(idle())send(3,0,new byte[0]);}
    void request(int cmd,int flags,byte[] payload,java.util.function.Consumer<Protocol.Packet> done){
        if(!idle())throw new IllegalStateException("Bluetooth busy");
        if(cmd!=0x61&&cmd!=0x62&&cmd!=0x65&&cmd!=0x66)throw new IllegalArgumentException();
        requestDone=done;send(cmd,flags,payload);
    }
    private void send(int cmd,int flags,byte[] payload){
        if(closed||pending>=0||out!=null)return;
        if(cmd!=3&&cmd!=0x3e&&cmd!=0x61&&cmd!=0x62&&cmd!=0x65&&cmd!=0x66)throw new IllegalArgumentException();
        pending=sid=(sid+1)&255;command=cmd;answer=null;out=Protocol.encode(sid,cmd,flags,payload);offset=0;
        if(cmd!=3)log("Sending "+stage(cmd)+"; id="+sid+"; bytes="+out.length);
        main.removeCallbacks(timeout);main.postDelayed(timeout,12000);writeNext();
    }
    private void writeNext(){
        if(closed||out==null||writing)return;
        if(offset==out.length){out=null;complete();return;}
        int end=Math.min(out.length,offset+Math.min(mtu-3,244));byte[] part=Arrays.copyOfRange(out,offset,end);
        int type=(writer.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try{writing=true;if(gatt.writeCharacteristic(writer,part,type)!=BluetoothStatusCodes.SUCCESS){fail("G2 Bluetooth write failed. Last card may remain.");return;}offset=end;}
        catch(RuntimeException e){fail("Bluetooth permission or connection was lost.");}
    }
    private void receive(byte[] bytes){
        for(Protocol.Packet p:decoder.feed(bytes))if(p.matches(pending,command)){
            if(command!=3||p.kind()!=1)log("Received "+(p.kind()==1?"ACK":"NCK")+" for "+stage(command)+"; id="+p.sid+"; payload bytes="+p.payload.length);
            answer=p;complete();
        }
    }
    private void complete(){
        if(answer==null||out!=null||writing||closed)return;
        Protocol.Packet p=answer;int cmd=command;pending=command=-1;answer=null;main.removeCallbacks(timeout);
        if(p.kind()!=1){
            String code=p.payload.length==1?String.format(Locale.ROOT,"0x%02x",p.payload[0]&255):"unavailable ("+p.payload.length+" response bytes)";
            fail("G2 rejected "+stage(cmd)+"; error="+code+". Last card may remain.");return;
        }
        if(cmd==0x3e){Runnable done=noteDone;noteDone=null;if(done!=null)done.run();}
        else if(cmd!=3){java.util.function.Consumer<Protocol.Packet> done=requestDone;requestDone=null;if(done!=null)done.accept(p);}
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int code,int state){main.post(()->{
            if(closed||g!=gatt)return;
            log("Connection callback; status="+code+"; state="+state);
            if(code!=0||state==BluetoothProfile.STATE_DISCONNECTED){fail("G2 disconnected (Bluetooth status "+code+"). Last navigation card may remain.");return;}
        });}
        @Override public void onMtuChanged(BluetoothGatt g,int n,int code){main.post(()->{if(!closed&&g==gatt&&code==0)mtu=Math.max(23,n);});}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){
            byte[] copy=value.clone();main.post(()->{if(!closed&&g==gatt&&c.getUuid().toString().equals(Protocol.NOTIFY))receive(copy);});
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int code){main.post(()->{
            if(closed||g!=gatt||!writing||!c.getUuid().toString().equals(Protocol.WRITE))return;writing=false;
            if(code!=0){fail("G2 write failed. Last card may remain.");return;}main.postDelayed(()->{if(!closed)writeNext();},30);
        });}
    };
    private void fail(String reason){if(closed)return;failure=reason;log(reason);close();if(host!=null)host.failed(reason);}
    void close(){
        closed=true;main.removeCallbacksAndMessages(null);authenticated=false;noteDone=null;requestDone=null;
        if(router!=null)router.bind(null);router=null;
        if(out!=null)Arrays.fill(out,(byte)0);out=null;
        decoder.reset();BluetoothGatt old=gatt;gatt=null;
        if(old!=null){try{old.disconnect();}catch(RuntimeException ignored){}try{old.close();}catch(RuntimeException ignored){}}
    }
}
