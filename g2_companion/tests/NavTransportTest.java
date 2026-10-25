package local.halliday.g2lab;
import android.bluetooth.*;
import android.os.Handler;
import java.util.*;
import java.io.ByteArrayOutputStream;

/** Executes production callback routing, ownership and packet transport with a modeled radio. */
public final class NavTransportTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static final class Host implements NavBleClient.Host {int connected,failed;String error="";public void connected(){connected++;}public void failed(String s){failed++;error=s;}}
    static final class Fixture {
        final BluetoothGatt g=new BluetoothGatt();final BluetoothGattCharacteristic w=new BluetoothGattCharacteristic(Protocol.WRITE),n=new BluetoothGattCharacteristic(Protocol.NOTIFY);
        int oldEvents,accepted;final Host host=new Host();
        final GattCallbackRouter router=new GattCallbackRouter(new BluetoothGattCallback(){
            @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] bytes){oldEvents++;}
        });
        final NavBleClient client;
        Fixture(int lastSid,int mtu){Handler.reset();client=new NavBleClient(g,w,router,mtu,lastSid);client.begin(host);}
        void note(){client.note(new NavRelayState.Card("G2 NAVIGATION","Synthetic turn only"),()->accepted++);}
        void response(int sid,int cmd,int flags,byte[] body){byte[] b=Protocol.encode(sid,cmd,flags,body);router.onCharacteristicChanged(g,n,b);Arrays.fill(b,(byte)0);Handler.advance(0);}
        void wrote(){router.onCharacteristicWrite(g,w,0);Handler.advance(30);}
    }
    public static void main(String[] args)throws Exception {
        Fixture a=new Fixture(7,517);check(a.host.connected==1&&a.g.writes.isEmpty(),"adoption does not replay version or auth");
        check(a.g.disconnects==0&&a.g.closes==0,"adoption keeps same GATT");a.note();
        Protocol.Packet sent=new Protocol.Decoder().feed(a.g.writes.get(0)).get(0);
        check(sent.sid==8&&sent.command==0x3e,"continue authenticated sequence");
        a.response(8,0x3e,0x10,new byte[0]);check(a.accepted==0,"ACK before write completion waits");
        a.wrote();check(a.accepted==1&&a.client.idle(),"early ACK completed once");check(a.oldEvents==0,"old activity callback no longer receives packets");
        a.response(8,0x3e,0x10,new byte[0]);check(a.accepted==1,"duplicate ACK ignored");
        a.client.heartbeat();a.wrote();a.response(9,3,0x10,new byte[0]);check(a.client.idle(),"heartbeat on same session");
        a.client.close();a.client.close();check(a.g.disconnects==1&&a.g.closes==1,"close exactly once");
        a.router.onCharacteristicChanged(a.g,a.n,Protocol.encode(10,3,0x10,new byte[0]));Handler.advance(13000);check(a.host.failed==0,"no stale callback or timeout after close");

        Fixture b=new Fixture(255,23);b.note();int consumed=0;ByteArrayOutputStream stream=new ByteArrayOutputStream();
        while(consumed<b.g.writes.size()){byte[] part=b.g.writes.get(consumed++);check(part.length<=20,"small MTU bound");stream.write(part);b.wrote();}
        Protocol.Packet fragmented=new Protocol.Decoder().feed(stream.toByteArray()).get(0);
        check(fragmented.sid==0&&fragmented.command==0x3e,"wrapped sequence and multipart note");
        b.response(0,0x3e,0x10,new byte[0]);check(b.accepted==1,"fragmented note acknowledged");b.client.close();

        Fixture c=new Fixture(10,247);c.note();c.wrote();c.response(11,0x3e,0x21,new byte[]{0x19});
        check(c.host.failed==1&&c.host.error.contains("0x3e")&&c.host.error.contains("0x19"),"NCK exact stage and code");
        check(c.g.disconnects==1&&!c.client.idle(),"NCK closes instead of replaying");
        Fixture d=new Fixture(10,247);d.note();d.wrote();Handler.advance(12000);
        check(d.host.failed==1&&d.g.writes.size()==1&&d.g.closes==1,"timeout closes once without resend");
        Fixture e=new Fixture(10,247);e.g.status=1;e.note();check(e.host.failed==1&&e.g.closes==1,"write rejection closes");

        Fixture f=new Fixture(20,247);NavSession.arm(f.client,NavGuidance.MAPS);NavSession.cancelPending(f.client);
        check(!NavSession.active&&f.g.closes==1,"service-start watchdog closes unclaimed connection");
        Fixture h=new Fixture(20,247);NavSession.arm(h.client,NavGuidance.WAZE);check(NavSession.take()==h.client,"service claims exactly the transferred client");
        check(NavSession.take()==null,"handoff cannot be claimed twice");NavSession.cancelPending(h.client);NavSession.clear();
        check(h.g.closes==0,"activity cleanup cannot close claimed service connection");h.client.close();

        Handler.reset();BluetoothGatt g=new BluetoothGatt();GattCallbackRouter r=new GattCallbackRouter(null);
        NavBleClient early=new NavBleClient(g,new BluetoothGattCharacteristic(Protocol.WRITE),r,247,8);
        r.onConnectionStateChange(g,19,BluetoothProfile.STATE_DISCONNECTED);Handler.advance(0);
        Host late=new Host();early.begin(late);check(late.failed==1&&late.connected==0&&g.writes.isEmpty(),"disconnect before service claims is delivered without writing");
        check(android.util.Log.messages.stream().noneMatch(s->s.contains("Synthetic turn only")),"metadata logs exclude card content");
        Fixture setup=new Fixture(40,247);final int[] callbacks={0};
        setup.client.request(0x61,7,"{\"mode\":\"ap\"}".getBytes("UTF-8"),packet->{
            callbacks[0]++;setup.client.request(0x65,0x46,"fs".getBytes(java.nio.charset.StandardCharsets.US_ASCII),reply->callbacks[0]++);
        });
        setup.response(41,0x61,0x17,"synthetic-credential".getBytes("UTF-8"));setup.wrote();
        check(callbacks[0]==1,"Wi-Fi ACK safely chains FTP request after writes");
        setup.wrote();setup.response(42,0x65,0x17,"synthetic-credential".getBytes("UTF-8"));
        check(callbacks[0]==2&&setup.client.idle(),"FTP setup callback complete");
        setup.client.request(0x66,0x40,new byte[0],packet->callbacks[0]++);setup.wrote();setup.response(43,0x66,0x10,new byte[0]);
        setup.client.request(0x62,0,new byte[0],packet->callbacks[0]++);setup.wrote();setup.response(44,0x62,0x10,new byte[0]);
        check(callbacks[0]==4,"filesystem and Wi-Fi shutdown callbacks");setup.client.close();
        check(android.util.Log.messages.stream().noneMatch(s->s.contains("synthetic-credential")),"setup payloads never logged");
        System.out.println("Navigation production-transport checks passed: "+checks+"; deterministic Android callback stubs, no hardware proof.");
    }
}
