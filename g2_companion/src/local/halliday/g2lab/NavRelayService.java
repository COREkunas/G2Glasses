package local.halliday.g2lab;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Owns BLE and local Wi-Fi while Maps/Waze is foreground. No note-card
 * commands, code transfer, firmware writes or process-wide network binding. */
public final class NavRelayService extends Service {
    public static final String STOP="local.halliday.g2lab.STOP_NAV";
    private static final int ID=410;private static final String CHANNEL="g2_navigation";
    private final Handler main=new Handler(Looper.getMainLooper());private NavBleClient ble;
    private ConnectivityManager connectivity;private ConnectivityManager.NetworkCallback callback;
    private PowerManager.WakeLock wakeLock;private volatile NativeAppFtp ftp;
    private volatile boolean stopping,finished,ending;private boolean wifi,filesystem,reading;
    private String ssid,wifiPassword,user,password;private long heartbeat,lastNotificationRefresh;
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,NavRelayService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("MINDYLAB navigation on G2").setContentText(NavSession.status)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null,"Stop / dashboard",stop).build()).build();
    }
    private void status(String message){main.post(()->{if(!finished&&!ending){NavSession.status=message;getSystemService(NotificationManager.class).notify(ID,notification());}});}
    @Override public void onCreate(){super.onCreate();connectivity=getSystemService(ConnectivityManager.class);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"G2 native navigation",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(finished){stopSelf();return START_NOT_STICKY;}
        if(intent!=null&&STOP.equals(intent.getAction())){stopNavigation();return START_NOT_STICKY;}
        if(ble!=null)return START_NOT_STICKY;
        ble=NavSession.take();if(ble==null||!NavSession.active){finish("Native navigation is stopped.");return START_NOT_STICKY;}
        try{startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"G2DisplayLab:native-navigation");
            wakeLock.acquire(4*60*60*1000L+60000);main.postDelayed(this::stopNavigation,4*60*60*1000L);
        }catch(RuntimeException e){finish("Cannot start navigation service. Check permissions.");return START_NOT_STICKY;}
        NavNotificationListener.refreshNow();
        try{if(!NavSession.listenerConnected)android.service.notification.NotificationListenerService.requestRebind(new ComponentName(this,NavNotificationListener.class));}
        catch(RuntimeException e){NavSession.state.listenerLost();}
        ble.begin(new NavBleClient.Host(){
            public void connected(){status("Connecting G2 Wi-Fi for the native screen…");startWifi();main.post(tick);}
            public void failed(String reason){finish(reason+" Native navigation returns after its phone lease expires.");}
        });
        return START_NOT_STICKY;
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(finished||ending)return;
        if(SystemClock.elapsedRealtime()-lastNotificationRefresh>=3000){lastNotificationRefresh=SystemClock.elapsedRealtime();NavNotificationListener.refreshNow();
            // Reconcile active notifications even if Android coalesces an update.
            // Preserve each original postTime; polling never makes stale guidance fresh.
            try{File d=new File(getFilesDir(),"native-app");d.mkdirs();
                try(FileOutputStream out=new FileOutputStream(new File(d,"notification-diagnostic.txt"))){out.write((NavSession.notificationDiagnostic+"\nframe="+NavSession.state.nativeFrame(lastNotificationRefresh).state+" layout="+NavSession.layout+"\n").getBytes(StandardCharsets.UTF_8));}}
            catch(IOException ignored){}}
        if(ble!=null&&ble.idle()&&SystemClock.elapsedRealtime()-heartbeat>=3000){heartbeat=SystemClock.elapsedRealtime();ble.heartbeat();}
        main.postDelayed(this,500);
    }};
    private JSONObject object(Protocol.Packet p)throws Exception{
        if((p.flags&15)!=7||p.payload.length>4096)throw new IOException("Invalid setup reply");
        return new JSONObject(new String(p.payload,StandardCharsets.UTF_8));
    }
    private String credential(JSONObject j,String key,int min,int max)throws Exception{
        Object o=j.opt(key);if(!(o instanceof String))throw new IOException("Missing credential");String s=(String)o;
        if(s.length()<min||s.length()>max||s.chars().anyMatch(c->c<32||c==127))throw new IOException("Invalid credential");return s;
    }
    private void startWifi(){
        ble.request(0x61,7,"{\"mode\":\"ap\"}".getBytes(StandardCharsets.UTF_8),p->{
            wifi=true;
            try{JSONObject j=object(p);ssid=credential(j,"ssid",1,32);wifiPassword=credential(j,"passwd",8,63);
                if(ssid.getBytes(StandardCharsets.UTF_8).length>32)throw new IOException();
                if(stopping){finish("Navigation cancelled before launch.");return;}
                ble.request(0x65,0x46,"fs".getBytes(StandardCharsets.US_ASCII),q->{
                    filesystem=true;
                    try{JSONObject k=object(q);user=credential(k,"user",2,32);password=credential(k,"passwd",1,128);
                        if(!"fs".equals(user))throw new IOException();
                        if(stopping){finish("Navigation cancelled before launch.");return;}join();
                    }catch(Exception e){finish("G2 filesystem setup could not be validated.");}
                });
            }catch(Exception e){finish("G2 Wi-Fi setup could not be validated.");}
        });
    }
    private void join(){
        status("Accept the G2 Wi-Fi dialog. Keep this app visible until navigation starts.");
        WifiNetworkSpecifier spec=new WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(wifiPassword).build();
        NetworkRequest request=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build();
        callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){main.post(()->network(n,connectivity.getLinkProperties(n)));}
            @Override public void onLinkPropertiesChanged(Network n,LinkProperties p){main.post(()->network(n,p));}
            @Override public void onUnavailable(){main.post(()->finish("G2 Wi-Fi was not accepted or is unavailable."));}
            @Override public void onLost(Network n){main.post(()->finish("G2 Wi-Fi lost. Native screen returns automatically after its phone lease expires."));}
        };
        connectivity.requestNetwork(request,callback,120000);
        main.postDelayed(()->{if(!reading&&!ending)finish("No G2 Wi-Fi route arrived.");},135000);
    }
    private void network(Network n,LinkProperties props){
        if(reading||stopping||ending||finished||props==null)return;
        InetAddress gateway=null;
        for(RouteInfo r:props.getRoutes()){InetAddress a=r.getGateway();if(a instanceof Inet4Address&&a.isSiteLocalAddress()&&!a.isAnyLocalAddress()){
            if(gateway!=null&&!gateway.equals(a)){finish("Ambiguous G2 network route.");return;}gateway=a;}}
        if(gateway==null)return;reading=true;final InetAddress target=gateway;final String u=user,p=password;
        new Thread(()->runNative(n,target,u,p),"G2-native-navigation").start();
    }
    private NativeAppFtp login(ReadOnlyFtp.Connector c,String u,String p)throws IOException{
        NativeAppFtp f=new NativeAppFtp(c);ftp=f;
        try{f.login(u,p);return f;}catch(IOException e){f.close();throw e;}
    }
    private NativeAppRecord.Status read(ReadOnlyFtp.Connector c,String u,String p)throws IOException{
        byte[] raw;try(NativeAppFtp f=login(c,u,p)){raw=f.status();f.finish();}finally{ftp=null;}
        NativeAppRecord.Status s=NativeAppRecord.parse(raw);
        File dir=new File(getFilesDir(),"native-app");dir.mkdirs();
        // Status counters only: no route text or account credentials in this file.
        try(FileOutputStream out=new FileOutputStream(new File(dir,"last-nav-status.bin"))){out.write(raw);}
        return s;
    }
    private void send(ReadOnlyFtp.Connector c,String u,String p,NativeAppRecord.Status s,int op,int seq)throws IOException{
        try(NativeAppFtp f=login(c,u,p)){f.send(s,op,seq);f.finish();}finally{ftp=null;}
    }
    private void runNative(Network network,InetAddress target,String u,String p){
        ReadOnlyFtp.Connector connector=port->{Socket s=network.getSocketFactory().createSocket();
            try{s.connect(new InetSocketAddress(target,port),6000);return s;}catch(IOException e){s.close();throw e;}};
        byte[] nonce=null;int route=0,pendingRoute=0,startSeq=0,stopSeq=0,invalid=0;boolean started=false;
        long lastRoute=-10000,routeSent=0,actionSent=0;String lastId="";
        try{
            while(!finished&&!ending){
                NativeAppRecord.Status s;
                try{s=read(connector,u,p);invalid=0;}catch(IOException e){if(++invalid>3)throw e;Thread.sleep(500);continue;}
                if(!s.navigation)throw new IOException("Install native navigation module first");
                if(nonce==null){nonce=s.nonce.clone();route=s.routeSequence;if(s.phase!=1)throw new IOException("Native screen already in use");}
                if(!Arrays.equals(nonce,s.nonce))throw new IOException("G2 restarted");
                if(s.phase==3||s.error!=0)throw new IOException("Native renderer stopped with an error");
                long now=SystemClock.elapsedRealtime();
                if(pendingRoute!=0){if(s.routeSequence==pendingRoute)pendingRoute=0;else if(s.routeSequence>pendingRoute||now-routeSent>15000)throw new IOException("Route acknowledgement missing");}
                if(startSeq!=0&&!started&&s.sequence==startSeq){if(s.result!=0)throw new IOException("Native Start rejected");started=true;status("Native navigation is running on G2. Open "+NavGuidance.name(NavSession.selected)+" and start a route.");}
                if(startSeq!=0&&!started&&now-actionSent>20000)throw new IOException("Native Start not confirmed");
                if(started&&s.phase==1&&stopSeq==0){finishAsync("Native screen ended; reconnect G2 to start again.");return;}
                if(stopping){
                    if(startSeq==0){finishAsync("Navigation cancelled before launch.");return;}
                    if(!started){Thread.sleep(500);continue;}
                    if(stopSeq==0){stopSeq=s.sequence+1;send(connector,u,p,s,2,stopSeq);actionSent=now;}
                    else if(s.sequence==stopSeq&&s.phase==1&&s.restored==1&&s.lockRemoved==1&&s.bufferRestored==1&&s.result==0){finishAsync("Navigation stopped; G2 restored the dashboard.");return;}
                    else if(now-actionSent>20000)throw new IOException("Stop restoration not confirmed");
                }else{
                    NavRelayState.NativeFrame frame=NavSession.state.nativeFrame(now);
                    String id=frame.state+":"+NavSession.layout+":"+(frame.guidance==null?"":frame.guidance.body);
                    if(pendingRoute==0&&(now-lastRoute>=5000||(!id.equals(lastId)&&now-lastRoute>=1000))){
                        byte[] b=NativeNavRecord.encode(s,++route,frame.state,frame.fresh,frame.guidance,NavGuidance.name(NavSession.selected),NavSession.layout);
                        try(NativeAppFtp f=login(connector,u,p)){f.route(b);f.finish();}finally{ftp=null;}
                        pendingRoute=route;routeSent=lastRoute=now;lastId=id;
                    }else if(startSeq==0&&pendingRoute==0&&s.routeSequence>0&&s.linkAge<10){startSeq=s.sequence+1;send(connector,u,p,s,3,startSeq);actionSent=now;}
                }
                Thread.sleep(500);
            }
        }catch(Exception e){finishAsync("Native navigation connection ended ("+e.getClass().getSimpleName()+"). If running, G2 returns after about 30 seconds without updates.");}
        finally{NativeAppFtp f=ftp;if(f!=null)f.close();ftp=null;}
    }
    private void finishAsync(String message){main.post(()->finish(message));}
    private void stopNavigation(){
        if(stopping||ending||finished)return;stopping=true;NavSession.state.stop();status("Stopping native navigation and restoring dashboard…");
        if(!reading){finish("Navigation cancelled before launch.");return;}
        main.postDelayed(()->finish("Navigation stopped on phone; wait for G2's automatic screen return if Stop was not confirmed."),35000);
    }
    private void finish(String message){
        if(finished||ending)return;ending=true;stopping=true;NavSession.status=message;
        main.removeCallbacksAndMessages(null);NativeAppFtp f=ftp;if(f!=null)f.close();
        if(callback!=null){try{connectivity.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}callback=null;}
        main.postDelayed(this::close,4000);cleanupBle();
    }
    private void cleanupBle(){
        if(finished)return;if(ble==null){close();return;}
        if(!ble.idle()){main.postDelayed(this::cleanupBle,100);return;}
        if(filesystem){filesystem=false;ble.request(0x66,0x40,new byte[0],p->cleanupBle());}
        else if(wifi){wifi=false;ble.request(0x62,0,new byte[0],p->close());}
        else close();
    }
    private void close(){
        if(finished)return;finished=true;main.removeCallbacksAndMessages(null);if(ble!=null)ble.close();ble=null;
        user=password=ssid=wifiPassword=null;NavSession.clear();
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();wakeLock=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
    @Override public void onDestroy(){if(!finished){finish("Navigation service ended; G2 returns after its phone lease expires.");close();}super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
