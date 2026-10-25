package local.halliday.g2lab;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Owner-started backup or disposable-file session. BLE credentials live only in memory. */
final class FtpDiagnostic {
    interface Host {
        boolean idle(); void send(int command,int flags,byte[] payload,String label);
        void log(String message); void changed(); String firmware(); void nativeStatus(String text);
    }
    private final Activity activity; private final Host host; private final Handler main;
    private final ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private volatile ReadOnlyFtp client;
    private volatile DisposableFtpTest disposableClient;
    private volatile UsbFlagTest usbClient;
    private int usbMode;
    private String usbVersion;
    private long usbDiagnosticNanos;
    private File usbPrepared;
    private volatile boolean usbWriteLocked;
    private volatile boolean cancelled;
    private int stage,awaiting;
    private volatile int generation;
    private boolean backup,fullFlash,disposable,wifiAccepted,ftpAccepted,reading;
    private String ssid,wifiPassword,user,ftpPassword;
    private String ramSession;
    private boolean nativeMode;
    private volatile NativeAppFtp nativeClient;
    private volatile NativeAppRecord.Status nativeLast;
    private volatile boolean nativeSending;
    private final java.util.concurrent.atomic.AtomicInteger nativeOperation=new java.util.concurrent.atomic.AtomicInteger();
    boolean nativeReady(){NativeAppRecord.Status s=nativeLast;return nativeMode&&stage==3&&s!=null&&s.phase!=3&&!nativeSending&&nativeOperation.get()==0;}
    boolean nativeRunning(){NativeAppRecord.Status s=nativeLast;return s!=null&&s.phase==2;}
    void startNative(){start(false,false,false,0,null,true);}
    void nativeCommand(int operation){if(nativeReady()&&(operation==1||operation==2)&&nativeOperation.compareAndSet(0,operation))host.changed();}
    private final Runnable deadline=()->stop("FTP session reached its time limit.");
    FtpDiagnostic(Activity activity,Host host) {
        this.activity=activity;this.host=host;main=new Handler(Looper.getMainLooper());
        connectivity=activity.getSystemService(ConnectivityManager.class);
    }
    boolean active() { return stage!=0; }
    boolean handles(int command) { return active() && command==awaiting; }
    void start(boolean backup) {
        start(backup,false,false);
    }
    void startFullFlash() {start(true,true,false);}
    void startDisposable() {start(false,false,true);}
    void startUsb(boolean enable) {start(false,false,false,enable?2:1);}
    void startRam() {
        try {
            byte[] raw=java.nio.file.Files.readAllBytes(new File(activity.getFilesDir(),"ram-diagnostic-id.txt").toPath());
            if(raw.length!=32)throw new IOException("No prepared RAM diagnostic session.");
            String id=new String(raw,StandardCharsets.US_ASCII);RamDiagnosticRecord.path(id,0);
            start(false,false,false,0,id);
        }catch(Exception e){host.log("Prepare the RAM diagnostic session from the PC first.");}
    }
    boolean usbWriteLocked() {return usbWriteLocked;}
    private void start(boolean backup,boolean fullFlash,boolean disposable) {
        start(backup,fullFlash,disposable,0);
    }
    private void start(boolean backup,boolean fullFlash,boolean disposable,int usbMode) {
        start(backup,fullFlash,disposable,usbMode,null);
    }
    private void start(boolean backup,boolean fullFlash,boolean disposable,int usbMode,String ramId) {
        start(backup,fullFlash,disposable,usbMode,ramId,false);
    }
    private void start(boolean backup,boolean fullFlash,boolean disposable,int usbMode,String ramId,boolean nativeApp) {
        if(active() || !host.idle()) return;
        if(usbMode!=0) {
            try {
                UsbFlagTest.require(!new File(activity.getFilesDir(),"usb-write-attempted.json").exists(),"A USB write was already attempted. Review its result before another write.");
                if(usbMode==2)usbPrepared=readyUsbBackup();
            }catch(Exception e){host.log(e instanceof IOException?e.getMessage():"USB backup receipt could not be validated.");return;}
        }
        android.net.wifi.WifiManager wifi=activity.getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
        if(wifi==null || !wifi.isWifiEnabled()) {
            host.log("Turn on phone Wi-Fi, return to this app, reconnect G2 if needed, then tap the FTP button again.");return;
        }
        if(backup) {
            try { if(new JSONObject(host.firmware()).optString("firmware","").isEmpty()) throw new JSONException("No version"); }
            catch(JSONException e) {host.log("Read the firmware diagnostic successfully before starting a backup.");return;}
        }
        if(disposable || ramId!=null || nativeApp) {
            try {
                JSONObject version=new JSONObject(host.firmware());
                if(!version.optString("firmware","").equals("1.0.1.202609052141") || version.optInt("build_no",-1)!=2018)
                    throw new JSONException("Different firmware");
            } catch(JSONException e) {host.log("Run the firmware diagnostics first. This feature requires installed 1.0.1, build 2018.");return;}
        }
        if(activity.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},2);
            host.log("Allow Nearby Wi-Fi, then tap the FTP button again.");return;
        }
        this.backup=backup;this.fullFlash=fullFlash;this.disposable=disposable;this.usbMode=usbMode;usbWriteLocked=false;cancelled=false;wifiAccepted=false;ftpAccepted=false;reading=false;
        ramSession=ramId;
        nativeMode=nativeApp;nativeLast=null;nativeSending=false;nativeOperation.set(0);
        ssid=null;wifiPassword=null;user=null;ftpPassword=null;generation++;stage=usbMode==0?1:6;awaiting=0;
        activity.deleteFile("wifi-start.json");
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        host.log(nativeApp?"Connecting MINDYLAB controls. Start and Stop use two fixed RAM files.":usbMode==1?"Starting fresh USB-test backups. No configuration write in this step.":usbMode==2?"Starting the guarded USB-flag test. A single persistent write is possible after fresh checks; no automatic reboot.":disposable?"Starting disposable-file FTP offset test. One temporary file; no firmware or USB flag changes.":fullFlash?"Starting full 16 MiB flash backup and fresh misc, bootinfo and factory reads.":backup?"Starting read-only access checks, then repeated firmware reads.":"Starting read-only filesystem checks.");
        main.postDelayed(deadline,(disposable?5:15)*60*1000);host.changed();advance();
        if(ramId!=null)host.log("RAM diagnostic reader: up to 64 session-bound /tmp records. Keep this app open for the PC-assisted test.");
        if(nativeApp)host.nativeStatus("Connecting to the prepared MINDYLAB launcher…");
    }
    void advance() {
        if(!active() || awaiting!=0 || !host.idle()) return;
        if(stage==6)send(0x20,0,null,"fresh firmware and power check for USB test");
        else if(stage==1) send(0x61,7,"{\"mode\":\"ap\"}",usbMode!=0?"G2 Wi-Fi for USB test":disposable?"G2 Wi-Fi for disposable-file test":"G2 Wi-Fi for read-only FTP");
        else if(stage==2) send(0x65,0x46,"fs","filesystem FTP start");
        else if(stage==4) {
            if(ftpAccepted) send(0x66,0x40,null,"filesystem FTP stop");
            else {stage=5;advance();}
        } else if(stage==5) {
            if(wifiAccepted) send(0x62,0,null,"G2 Wi-Fi stop after FTP");
            else finish();
        }
    }
    private void send(int command,int flags,String text,String label) {
        awaiting=command;host.send(command,flags,text==null?new byte[0]:text.getBytes(StandardCharsets.UTF_8),label);
    }
    void reply(Protocol.Packet p) {
        int command=awaiting;awaiting=0;
        if(p.kind()==2) {
            host.log("FTP workflow command 0x"+Integer.toHexString(command)+" rejected; code="+Protocol.hex(p.payload)+".");
            if(command==0x66) {ftpAccepted=false;stage=5;host.log("FTP stop was not confirmed.");}
            else if(command==0x62) {host.log("Wi-Fi stop was not confirmed.");finish();return;}
            else stop("FTP setup stopped before filesystem access.");
            advance();return;
        }
        try {
            if(command==0x20 && usbMode!=0) {
                usbVersion=DiagnosticReply.summarize(p);usbDiagnosticNanos=System.nanoTime();JSONObject v=new JSONObject(usbVersion);
                UsbFlagTest.require(UsbFlagTest.FIRMWARE.equals(v.optString("firmware")) && v.optInt("build_no",-1)==2018,"USB test requires installed 1.0.1 build 2018.");
                if(usbMode==2)UsbFlagTest.power(v.optString("firmware"),v.optInt("build_no",-1),v.optInt("charger",-1),v.optInt("battery",-1));
                host.log("USB-test fresh diagnostic: "+usbVersion);stage=cancelled?4:1;
            } else if(command==0x61) {
                wifiAccepted=true;JSONObject j=json(p);
                ssid=string(j,"ssid",32);wifiPassword=string(j,"passwd",63);
                if(ssid.getBytes(StandardCharsets.UTF_8).length>32 || wifiPassword.length()<8)
                    throw new JSONException("Invalid Wi-Fi credentials");
                host.log("G2 Wi-Fi accepted. Connection credentials remain in memory.");
                stage=cancelled?4:2;
            } else if(command==0x65) {
                ftpAccepted=true;JSONObject j=json(p);user=string(j,"user",32);ftpPassword=string(j,"passwd",128);
                if(!user.equals("fs")) throw new JSONException("Unexpected FTP account");
                host.log("Filesystem FTP accepted. Waiting for the Android Wi-Fi connection.");
                if(cancelled) stage=4;else {stage=3;joinNetwork();}
            } else if(command==0x66) {ftpAccepted=false;stage=5;host.log("G2 acknowledged FTP stop.");}
            else if(command==0x62) {wifiAccepted=false;host.log("G2 acknowledged Wi-Fi stop.");finish();return;}
        } catch(Exception e) {stop(command==0x20 && e instanceof IOException?e.getMessage():"Connection reply could not be validated; raw data and credentials withheld.");}
        advance();host.changed();
    }
    private static JSONObject json(Protocol.Packet p) throws JSONException {
        if((p.flags&15)!=7 || p.payload.length>4096) throw new JSONException("Unexpected reply format");
        return new JSONObject(new String(p.payload,StandardCharsets.UTF_8));
    }
    private static String string(JSONObject j,String key,int max) throws JSONException {
        Object value=j.opt(key);if(!(value instanceof String)) throw new JSONException("Missing string");
        String s=(String)value;
        if(s.isEmpty() || s.length()>max || s.chars().anyMatch(c->c<32 || c==127)) throw new JSONException("Invalid string");
        return s;
    }
    private void joinNetwork() {
        final int token=generation;
        WifiNetworkSpecifier spec=new WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(wifiPassword).build();
        NetworkRequest request=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build();
        callback=new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                main.post(()->{if(token==generation && stage==3) networkReady(n,connectivity.getLinkProperties(n));});
            }
            @Override public void onLinkPropertiesChanged(Network n,LinkProperties properties) {
                main.post(()->{if(token==generation && stage==3) networkReady(n,properties);});
            }
            @Override public void onUnavailable() {main.post(()->{if(token==generation && stage==3) stop("Android could not join G2 Wi-Fi. Check Wi-Fi is enabled and accept its connection dialog.");});}
            @Override public void onLost(Network n) {main.post(()->{if(token==generation && stage==3) stop("G2 Wi-Fi connection was lost; transfer verification and cleanup may be incomplete.");});}
        };
        host.log("Accept Android's connection dialog for your G2. Keep this app open.");
        connectivity.requestNetwork(request,callback,120000);
        main.postDelayed(()->{if(token==generation && stage==3 && !reading) stop("No usable local G2 route arrived within 135 seconds.");},135000);
    }
    private void networkReady(Network network,LinkProperties properties) {
        if(reading || cancelled || properties==null) return;
        InetAddress gateway=null;
        for(RouteInfo route:properties.getRoutes()) {
            InetAddress candidate=route.getGateway();
            if(candidate instanceof Inet4Address && candidate.isSiteLocalAddress() && !candidate.isAnyLocalAddress()) {
                if(gateway!=null && !gateway.equals(candidate)) {stop("Ambiguous local gateway; FTP was not contacted.");return;}
                gateway=candidate;
            }
        }
        if(gateway==null) return;
        reading=true;final InetAddress target=gateway;final int token=generation;
        final String loginUser=user,loginPassword=ftpPassword,version=host.firmware();
        host.log(usbMode!=0?"Connected to G2 Wi-Fi. Starting USB-test checks.":disposable?"Connected to G2 Wi-Fi. Starting the disposable-file test.":"Connected to the requested G2 network. Starting bounded read-only FTP checks.");
        final boolean doBackup=backup,doFullFlash=fullFlash;
        if(nativeMode)new Thread(()->runNative(network,target,loginUser,loginPassword,token),"G2-native-app").start();
        else if(ramSession!=null) {
            final String id=ramSession;
            new Thread(()->runRam(network,target,loginUser,loginPassword,token,id),"G2-RAM-reader").start();
        }
        else if(usbMode!=0)new Thread(()->runUsb(network,target,loginUser,loginPassword,token,usbMode==2),"G2-USB-test").start();
        else if(disposable) new Thread(()->runDisposable(network,target,loginUser,loginPassword,version,token),"G2-disposable-FTP").start();
        else new Thread(()->runReads(network,target,loginUser,loginPassword,version,token,doBackup,doFullFlash),"G2-read-only-FTP").start();
    }
    private void runNative(Network network,InetAddress target,String loginUser,String password,int token) {
        ReadOnlyFtp.Connector connector=port->{Socket s=network.getSocketFactory().createSocket();
            try{s.connect(new InetSocketAddress(target,port),6000);return s;}catch(IOException e){s.close();throw e;}};
        byte[] nonce=null;int pending=0,op=0,invalid=0;long sent=0;
        File dir=new File(activity.getFilesDir(),"native-app");dir.mkdirs();
        try {
            while(!cancelled&&token==generation) {
                byte[] raw;
                try(NativeAppFtp ftp=new NativeAppFtp(connector)){
                    nativeClient=ftp;ftp.login(loginUser,password);raw=ftp.status();ftp.finish();
                }finally{nativeClient=null;}
                NativeAppRecord.Status value;
                try{value=NativeAppRecord.parse(raw);invalid=0;}
                catch(IOException e){if(++invalid>5)throw e;Thread.sleep(500);continue;}
                if(nonce==null)nonce=value.nonce.clone();
                if(!Arrays.equals(nonce,value.nonce))throw new IOException("The G2 launcher session changed. Reconnect controls.");
                nativeLast=value;write(new File(dir,"last-status.bin"),raw);
                if(pending!=0) {
                    if(value.sequence==pending){
                        post(token,value.result==0?(op==1?"G2 accepted Start MINDYLAB.":"G2 accepted Stop MINDYLAB."):"G2 rejected the app action; result="+value.result);
                        pending=0;nativeSending=false;
                    }else if(value.sequence>pending||System.nanoTime()-sent>20_000_000_000L)
                        throw new IOException("App action was not confirmed. No automatic retry; the screen exits after two minutes.");
                }
                main.post(()->{if(token==generation){host.nativeStatus(value.summary());host.changed();}});
                if(value.phase==3)break;
                if(pending==0&&(op=nativeOperation.getAndSet(0))!=0){
                    nativeSending=true;pending=value.sequence+1;sent=System.nanoTime();
                    final String action=op==1?"Starting MINDYLAB…":"Stopping MINDYLAB…";
                    main.post(()->{if(token==generation){host.nativeStatus(action);host.changed();}});
                    try(NativeAppFtp ftp=new NativeAppFtp(connector)){
                        nativeClient=ftp;ftp.login(loginUser,password);ftp.send(value,op,pending);ftp.finish();
                    }finally{nativeClient=null;}
                }
                Thread.sleep(700);
            }
        } catch(Exception e) {
            String message=e instanceof NativeAppFtp.Failure&&((NativeAppFtp.Failure)e).code==550?
                "MINDYLAB is not ready on G2. Wait 20 seconds after restarting, then reconnect controls. Installation must be completed first.":
                "MINDYLAB connection stopped ("+e.getClass().getSimpleName()+"). Any running demo exits after two minutes; no action was retried.";
            post(token,message);main.post(()->{if(token==generation)host.nativeStatus(message);});
        } finally {
            nativeSending=false;nativeOperation.set(0);nativeLast=null;
            NativeAppFtp ftp=nativeClient;if(ftp!=null)ftp.close();nativeClient=null;
            main.post(()->{if(token==generation&&active()){releaseNetwork();stage=4;advance();host.changed();}});
        }
    }
    private void runRam(Network network,InetAddress target,String loginUser,String password,int token,String id) {
        ReadOnlyFtp ftp=null;File directory=new File(activity.getFilesDir(),"ram-diagnostics/"+id);
        JSONObject report=new JSONObject();
        try {
            if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Cannot create local diagnostic directory.");
            report.put("id",id).put("readOnly",true).put("transport","selected G2 Wi-Fi network").put("complete",false);
            JSONArray records=new JSONArray();report.put("records",records);saveReport(directory,report);
            post(token,"RAM diagnostic FTP reader ready; waiting for the PC-created record.");
            int count=0;boolean complete=false;long end=System.nanoTime()+300_000_000_000L;
            while(!cancelled && token==generation && System.nanoTime()<end && count<64 && !complete) {
                int stageIndex=count;
                report.put("waitingForStage",stageIndex).put("readerActive",true).put("pollEpochMs",System.currentTimeMillis());saveReport(directory,report);
                ftp=new ReadOnlyFtp(port->{Socket s=network.getSocketFactory().createSocket();try{s.connect(new InetSocketAddress(target,port),8000);return s;}catch(IOException e){s.close();throw e;}});
                client=ftp;ftp.login(loginUser,password);
                    byte[] first;
                    try{first=RamFtpRead.twice(ftp,id,stageIndex);}
                    catch(ReadOnlyFtp.Failure e){
                        if(e.code==550){report.put("waitingFtpCode",e.code);saveReport(directory,report);Thread.sleep(1000);continue;}
                        throw e;
                    }
                    catch(RamDiagnosticRecord.NotReady e){Thread.sleep(1000);continue;}
                    String name=(stageIndex<10?"0":"")+stageIndex+".bin";write(new File(directory,name),first);
                    complete=RamDiagnosticRecord.terminal(first);
                    records.put(new JSONObject().put("stage",stageIndex).put("bytes",512).put("sha256",hash(first)).put("receivedEpochMs",System.currentTimeMillis()).put("elapsedRealtimeNanos",SystemClock.elapsedRealtimeNanos()).put("format",RamDiagnosticRecord.word(first,4)).put("event",RamDiagnosticRecord.word(first,48)).put("terminal",complete));
                    count++;report.put("complete",complete);saveReport(directory,report);
                    post(token,"RAM diagnostic stage "+stageIndex+" verified twice over Wi-Fi/FTP.");
            }
            report.put("complete",complete).put("cancelled",cancelled);saveReport(directory,report);
            post(token,complete?"RAM diagnostic final record verified over Wi-Fi/FTP.":"RAM diagnostic reader stopped; saved stages remain available on this phone.");
        } catch(Exception e) {
            try{report.put("failure","RAM diagnostic transfer did not complete; credentials withheld.").put("failureClass",e.getClass().getSimpleName());if(e instanceof ReadOnlyFtp.Failure)report.put("ftpCode",((ReadOnlyFtp.Failure)e).code);saveReport(directory,report);}catch(Exception ignored){}
            post(token,"RAM diagnostic transfer stopped. Check the local report; no G2 write was made by FTP.");
        } finally {
            try{report.put("readerActive",false);saveReport(directory,report);}catch(Exception ignored){}
            if(ftp!=null)ftp.quit();if(client==ftp)client=null;
            main.post(()->{if(token==generation && active()){releaseNetwork();stage=4;advance();host.changed();}});
        }
    }
    private File readyUsbBackup()throws IOException,JSONException {
        File root=activity.getFilesDir();byte[] idBytes=java.nio.file.Files.readAllBytes(new File(root,"usb-prepared-id.txt").toPath());
        String id=new String(idBytes,StandardCharsets.US_ASCII);
        UsbFlagTest.require(id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"),"No valid prepared USB backup.");
        File dir=new File(root,"usb-tests/"+id),reportFile=new File(dir,"report.json"),receipt=new File(dir,"pc-verified.sha256");
        UsbFlagTest.require(reportFile.length()>0 && reportFile.length()<65536 && receipt.length()==64,"Copy and verify fresh USB backups on the PC first.");
        byte[] raw=java.nio.file.Files.readAllBytes(reportFile.toPath());JSONObject r=new JSONObject(new String(raw,StandardCharsets.UTF_8));
        UsbFlagTest.require("usb-preflight".equals(r.optString("workflow")) && "usb_backups_ready".equals(r.optString("status")),"USB backups are not verified.");
        long age=System.currentTimeMillis()-r.optLong("preparedAtEpochMs",0);
        UsbFlagTest.require(age>=0 && age<30*60*1000,"USB backups expired; prepare a fresh set.");
        UsbFlagTest.require(UsbFlagTest.hash(raw).equals(new String(java.nio.file.Files.readAllBytes(receipt.toPath()),StandardCharsets.US_ASCII)),"PC verification receipt does not match these backups.");
        return dir;
    }
    private void runUsb(Network network,InetAddress target,String loginUser,String password,int token,boolean enable) {
        JSONObject report=new JSONObject();File directory=null;UsbFlagTest test=null;boolean preserve=false;
        try {
            directory=new File(activity.getFilesDir(),"usb-tests/"+UUID.randomUUID());
            if(!directory.mkdirs() || directory.getUsableSpace()<128L*1024*1024)throw new IOException("Cannot reserve local backup space.");
            report.put("workflow",enable?"usb-enable":"usb-preflight");report.put("appVersion","0.11.1");
            report.put("firmwareDiagnostic",new JSONObject(usbVersion));report.put("status","in_progress");
            if(enable)report.put("backupSession",usbPrepared.getName());saveReport(directory,report);
            test=new UsbFlagTest(port->{
                if(cancelled || token!=generation)throw new IOException("Cancelled");Socket socket=network.getSocketFactory().createSocket();
                try{socket.connect(new InetSocketAddress(target,port),6000);return socket;}catch(IOException e){socket.close();throw e;}
            },directory);usbClient=test;if(cancelled || token!=generation)test.close();
            final File folder=directory;
            UsbFlagTest.Observer observer=state->{
                try {
                    for(Map.Entry<String,Object> item:state.entrySet())report.put(item.getKey(),JSONObject.wrap(item.getValue()));
                    saveReport(folder,report);
                    if(Boolean.TRUE.equals(state.get("writeAttempted")) && !usbWriteLocked) {
                        File lock=new File(activity.getFilesDir(),"usb-write-attempted.json");
                        if(!lock.createNewFile())throw new IOException("A previous USB write attempt exists.");
                        write(lock,("{\"session\":\""+folder.getName()+"\"}").getBytes(StandardCharsets.US_ASCII));usbWriteLocked=true;
                        main.post(()->host.changed());
                    }
                }catch(JSONException e){throw new IOException("Cannot save USB-test evidence.");}
                post(token,"USB test: "+state.get("phase")+".");
            };
            Map<String,Object> result;
            if(enable) {
                readyUsbBackup();JSONObject v=new JSONObject(usbVersion);
                result=test.enable(loginUser,password,usbPrepared,v.optString("firmware"),v.optInt("build_no",-1),v.optInt("charger",-1),v.optInt("battery",-1),usbDiagnosticNanos,observer);
            }else result=test.prepare(loginUser,password,observer);
            for(Map.Entry<String,Object> item:result.entrySet())report.put(item.getKey(),JSONObject.wrap(item.getValue()));saveReport(directory,report);
            if("usb_backups_ready".equals(result.get("status"))) {
                write(new File(activity.getFilesDir(),"usb-prepared-id.txt"),directory.getName().getBytes(StandardCharsets.US_ASCII));
                post(token,"Fresh USB-test backups verified. Wait for the PC copy and verification before tapping Enable USB flag.");
            }else if("usb_flag_enabled_verified".equals(result.get("status"))) {
                post(token,"USB flag enabled and verified by two complete misc reads; bootinfo and factory also unchanged. No reboot sent. Keep the charger connected and wait for PC verification.");
            }else {post(token,"USB test stopped: "+result.getOrDefault("error","Inspect saved report."));preserve=Boolean.TRUE.equals(result.get("writeAttempted"));}
        }catch(Exception e){
            preserve=usbWriteLocked;try{report.put("status",preserve?"write_outcome_unverified":"stopped_before_write");report.put("error",e instanceof IOException?e.getMessage():e.getClass().getSimpleName());if(directory!=null)saveReport(directory,report);}catch(Exception ignored){}
            post(token,"USB workflow stopped. Inspect its report; do not retry or reboot if a write was attempted.");
        }finally {
            if(test!=null)test.close();if(usbClient==test)usbClient=null;
            if(directory!=null)post(token,"Local USB report: usb-tests/"+directory.getName()+"/report.json");
            final boolean hold=preserve;
            main.post(()->{if(token==generation && active()){
                usbWriteLocked=false;
                if(hold){stage=8;main.removeCallbacks(deadline);host.log("Write result unverified: leaving G2 services running for investigation. Do not reboot or retry.");}
                else{releaseNetwork();stage=4;advance();}host.changed();
            }});
        }
    }
    private void runDisposable(Network network,InetAddress target,String loginUser,String password,String version,int token) {
        JSONObject report=new JSONObject();File directory=null;DisposableFtpTest test=null;
        try {
            directory=new File(activity.getFilesDir(),"ftp-disposable/"+UUID.randomUUID());
            if(!directory.mkdirs())throw new IOException("Cannot create output directory");
            report.put("workflow","disposable-file-offset-test");report.put("appVersion","0.11.1");
            report.put("firmwareDiagnostic",new JSONObject(version));report.put("status","in_progress");
            report.put("startedUtc",new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.ROOT){{setTimeZone(TimeZone.getTimeZone("UTC"));}}.format(new Date()));
            saveReport(directory,report);
            test=new DisposableFtpTest(port->{
                if(cancelled || token!=generation)throw new IOException("Cancelled");
                Socket socket=network.getSocketFactory().createSocket();
                try{socket.connect(new InetSocketAddress(target,port),6000);return socket;}
                catch(IOException e){socket.close();throw e;}
            });
            disposableClient=test;if(cancelled || token!=generation)test.close();
            final File folder=directory;
            Map<String,Object> result=test.run(loginUser,password,state->{
                try {
                    for(Map.Entry<String,Object> item:state.entrySet()) report.put(item.getKey(),JSONObject.wrap(item.getValue()));
                    saveReport(folder,report);
                }catch(JSONException e){throw new IOException("Cannot serialize disposable test report");}
                post(token,"Disposable FTP: "+state.get("phase")+".");
            });
            for(Map.Entry<String,Object> item:result.entrySet())report.put(item.getKey(),JSONObject.wrap(item.getValue()));
            saveReport(directory,report);
            boolean verified=Boolean.TRUE.equals(result.get("offsetWritesVerified")),removed=Boolean.TRUE.equals(result.get("cleanupConfirmed"));
            post(token,verified?(removed?"Disposable FTP test PASSED: offset 611 changed correctly, all other bytes preserved, two full readbacks matched, temporary file removed.":"Offset writes verified, but disposable-file cleanup is unconfirmed. Check the saved report."):"Disposable FTP test did not pass. "+result.getOrDefault("error","The test was interrupted."));
            if(!removed && !Boolean.TRUE.equals(result.get("cleanupNotNeeded")))post(token,"Cleanup unconfirmed for "+test.scratchPath()+". No automatic write retry.");
            post(token,"USB flag and firmware partitions were not changed by this test.");
        }catch(Exception e){
            String reason="Disposable-file workflow failed ("+e.getClass().getSimpleName()+").";
            try{report.put("status","failed");report.put("error",reason);if(directory!=null)saveReport(directory,report);}catch(Exception ignored){}
            post(token,reason);
        }finally{
            if(test!=null)test.close();if(disposableClient==test)disposableClient=null;
            if(directory!=null)post(token,"Local report: ftp-disposable/"+directory.getName()+"/report.json");
            main.post(()->{if(token==generation && active()){releaseNetwork();stage=4;advance();host.changed();}});
        }
    }
    private void runReads(Network network,InetAddress target,String loginUser,String password,String version,int token,boolean backup,boolean fullFlash) {
        JSONObject report=new JSONObject();File directory=null;ReadOnlyFtp ftp=null;String operation="connect/login";
        try {
            directory=new File(activity.getFilesDir(),"ftp-readonly/"+UUID.randomUUID());
            if(!directory.mkdirs()) throw new IOException("Cannot create local output directory.");
            report.put("workflow",fullFlash?"full-main-flash-backup":backup?"firmware-backup":"filesystem-diagnostic");report.put("firmwareDiagnostic",new JSONObject(version));
            report.put("appVersion","0.11.1");report.put("restorationTested",false);report.put("mainNORRepeatedReadsMatch",false);
            report.put("startedUtc",new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.ROOT){{setTimeZone(TimeZone.getTimeZone("UTC"));}}.format(new Date()));
            report.put("status","in_progress");report.put("writesToGlasses",false);
            report.put("geometryVerified",false);report.put("fullFlashOrRecoveryImage",false);saveReport(directory,report);
            ftp=new ReadOnlyFtp(port->{
                if(cancelled || token!=generation) throw new IOException("Cancelled");
                Socket socket=network.getSocketFactory().createSocket();
                try{socket.connect(new InetSocketAddress(target,port),6000);return socket;}
                catch(IOException e){socket.close();throw e;}
            });if(cancelled || token!=generation) throw new IOException("Cancelled");client=ftp;
            ftp.login(loginUser,password);operation="PWD";report.put("pwd",ftp.pwd());
            operation="LIST /dev";
            byte[] listing=ftp.listDevices();write(new File(directory,"dev-listing.txt"),listing);
            Set<String> present=ReadOnlyFtp.partitions(listing);report.put("listedFirmwarePartitions",new JSONArray(present));
            Map<String,Long> sizes=ReadOnlyFtp.partitionSizes(listing);
            report.put("listedPartitionBytes",new JSONObject(sizes));operation="RETR /etc/init.d/rcS";
            byte[] init=ftp.readSmall("/etc/init.d/rcS");
            if(init.length==0) throw new ReadOnlyFtp.Failure("System file read was empty.");
            write(new File(directory,"rcS.txt"),init);report.put("rcSBytes",init.length);report.put("rcSSha256",hash(init));
            report.put("filesystemReadVerified",true);saveReport(directory,report);
            post(token,"Filesystem access verified: /dev listing and system startup file retrieved.");
            // Installed 1.0.1 closed FTP during the first optional proc read.
            // Keep virtual proc files out of the partition-backup path.
            report.put("procReadsSkipped",true);
            JSONArray results=new JSONArray();report.put("partitions",results);
            boolean flashMatches=false;
            if(backup) {
                if(directory.getUsableSpace()<128L*1024*1024) throw new ReadOnlyFtp.Failure("Less than 128 MiB free for verified backups.");
                if(fullFlash) ReadOnlyFtp.validateFullFlashSizes(sizes);
                else if(!present.contains("ap")) throw new ReadOnlyFtp.Failure("Installed AP partition was not found in /dev; backup stopped.");
                Set<String> requested=fullFlash?ReadOnlyFtp.FULL_FLASH_PARTITIONS:ReadOnlyFtp.FIRMWARE_PARTITIONS;
                report.put("requestedPartitions",new JSONArray(requested));
                for(String name:requested) if(present.contains(name)) {
                    if(cancelled || token!=generation) throw new IOException("Cancelled");
                    if(!sizes.containsKey(name)) throw new ReadOnlyFtp.Failure("Missing unambiguous listed size for /dev/"+name+".");
                    operation="Repeated RETR /dev/"+name;
                    JSONObject row=new JSONObject();row.put("path","/dev/"+name);row.put("status","reading");results.put(row);saveReport(directory,report);
                    row.put("listedBytes",sizes.get(name));
                    post(token,"Reading /dev/"+name+" twice for SHA-256 verification…");
                    BackupFiles.Result result=fullFlash && name.equals("bes_flash")?BackupFiles.saveFullFlashTwice(ftp,directory):BackupFiles.saveTwice(ftp,name,directory,sizes.get(name));
                    row.put("status",result.repeatedReadMatches?"verified_repeated_read":"complete_reads_differ");row.put("bytes",result.size);row.put("sha256",result.sha256);row.put("localFile",result.localFile);
                    if(result.secondFile!=null) {
                        JSONArray reads=new JSONArray();
                        reads.put(new JSONObject().put("localFile",result.localFile).put("bytes",result.size).put("sha256",result.sha256));
                        reads.put(new JSONObject().put("localFile",result.secondFile).put("bytes",result.size).put("sha256",result.secondSha256));
                        row.put("reads",reads);flashMatches=result.repeatedReadMatches;
                        report.put("mainNORReadsComplete",true);report.put("mainNORRepeatedReadsMatch",flashMatches);
                    }
                    saveReport(directory,report);
                    post(token,result.repeatedReadMatches?"Verified /dev/"+name+": "+result.size+" bytes; two matching reads.":"Full-flash reads differ. Both complete 16 MiB snapshots are saved for PC comparison; stable backup is not verified.");
                }
            }
            report.put("status",fullFlash?(flashMatches?"full_main_flash_backed_up":"full_main_flash_reads_differ"):backup?"listed_partitions_backed_up":"diagnostic_complete");saveReport(directory,report);
            post(token,fullFlash?(flashMatches?"Full 16 MiB flash and fresh configuration backups verified. Ready for PC copy and comparison.":"Both full-flash snapshots saved, but they differ. PC comparison is required before treating this as a stable backup."):backup?"Firmware partition reads verified and saved locally. PC copy and layout review are still required.":"Read-only FTP diagnostic finished. No firmware partitions were read by this diagnostic-only run.");
        } catch(Exception e) {
            String reason=e instanceof ReadOnlyFtp.Failure?e.getMessage():e instanceof IOException?"Local I/O or connection failed ("+e.getClass().getSimpleName()+").":"Diagnostic failed ("+e.getClass().getSimpleName()+").";
            try{report.put("status",cancelled?"cancelled":"failed");report.put("error",reason);report.put("failedOperation",operation);if(directory!=null) saveReport(directory,report);}catch(Exception ignored){}
            post(token,operation+": "+reason+" Partial files are not verified backups.");
        } finally {
            if(ftp!=null) ftp.quit();if(client==ftp)client=null;
            if(directory!=null) post(token,"Local report: ftp-readonly/"+directory.getName()+"/report.json");
            main.post(()->{if(token==generation && active()) {releaseNetwork();stage=4;advance();host.changed();}});
        }
    }
    private static String hash(byte[] value) throws NoSuchAlgorithmException {return Protocol.hex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static void write(File path,byte[] data) throws IOException {try(FileOutputStream out=new FileOutputStream(path)){out.write(data);out.getFD().sync();}}
    private static void saveReport(File directory,JSONObject report) throws IOException,JSONException {
        File temporary=new File(directory,"report.json.tmp"),destination=new File(directory,"report.json");
        write(temporary,report.toString(2).getBytes(StandardCharsets.UTF_8));
        java.nio.file.Files.move(temporary.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private void post(int token,String message) {main.post(()->{if(token==generation)host.log(message);});}
    void stop(String message) {
        if(!active()) return;host.log(message);cancelled=true;
        if(nativeClient!=null)nativeClient.close();
        if(client!=null)client.close();if(disposableClient!=null)disposableClient.close();if(usbClient!=null)usbClient.close();releaseNetwork();
        // Await any outstanding BLE reply before sending cleanup commands.
        if(awaiting==0) {stage=4;advance();}host.changed();
    }
    private void releaseNetwork() {
        if(callback!=null) {try{connectivity.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}callback=null;}
    }
    void disconnected() {
        if(!active())return;
        if(nativeClient!=null)nativeClient.close();
        host.log("FTP session interrupted. Service shutdown is unconfirmed; reconnect to stop G2 Wi-Fi.");
        cancelled=true;if(client!=null)client.close();if(disposableClient!=null)disposableClient.close();if(usbClient!=null)usbClient.close();releaseNetwork();finish();
    }
    private void finish() {
        main.removeCallbacks(deadline);releaseNetwork();stage=0;awaiting=0;generation++;
        ssid=null;wifiPassword=null;user=null;ftpPassword=null;reading=false;
        nativeLast=null;nativeSending=false;nativeOperation.set(0);
        activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);host.changed();
    }
}
