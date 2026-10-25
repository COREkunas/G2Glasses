package local.halliday.g2lab;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small experiment using the owner's existing account binding and recovered note command. */
public class MainActivity extends Activity {
    // Official HallidayHeartbeatService._scheduleNextHeartbeat: Duration(3,000,000 us).
    private static final int HEARTBEAT_MS = 3000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Protocol.Decoder decoder = new Protocol.Decoder();
    private final Set<String> found = new HashSet<>();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private GattCallbackRouter gattRouter;
    private BluetoothGattCharacteristic writer;
    private LinearLayout deviceList;
    private TextView status, logView;
    private EditText titleInput, contentInput, emailInput, passwordInput;
    private Spinner accountDevice;
    private Button scanButton, infoButton, sendButton, signInButton, uploadButton, diagnosticsButton, consoleButton, wifiStatusButton, wifiStartButton, wifiStopButton;
    private ProbeUpload probeUpload;
    private FtpDiagnostic ftpDiagnostic;
    private Button nativeConnectButton,nativeRunButton,nativeStopButton;
    private TextView nativeStatus;
    private Spinner navSource,navLayout;private TextView navStatus,navPreview;private Button navStart,navStop;
    private final Runnable navUiTick=new Runnable(){public void run(){
        if(navStatus!=null){
            if(!navStatus.getText().toString().equals(NavSession.status))navStatus.setText(NavSession.status);
            if(!navPreview.getText().toString().equals(NavSession.preview))navPreview.setText(NavSession.preview);
            updateButtons();
        }
        main.postDelayed(this,1000);
    }};
    private Button ftpCheckButton, ftpBackupButton, ftpFullBackupButton, ftpDisposableButton, ftpCancelButton, usbPrepareButton, usbEnableButton, ramDiagnosticButton;
    private String firmwareSummary = "{}";
    private static final int[] DIAGNOSTIC_COMMANDS = {0x20, 0x28, 0x2c};
    private int diagnosticStep = -1;
    private String lastStatusSummary = "";
    private final List<AccountClient.OwnedDevice> ownedDevices = new ArrayList<>();
    private AccountClient.OwnedDevice connectionOwner;
    private boolean scanning, ready, versionOk, authenticated, signingIn;
    private int loginGeneration;
    private int mtu = 23, sid, pendingSid = -1, pendingCommand = -1, writeToken;
    private String pendingLabel;
    private byte[] outgoing;
    private int outgoingOffset;
    private boolean writeInFlight;
    private final Runnable replyTimeout = () -> {
        if (pendingSid < 0) return;
        log("No reply to " + pendingLabel + ". Delivery is unknown. Reconnect before another test.");
        disconnect();
    };
    private final Runnable stopScan = () -> stopScan();
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (!authenticated || gatt == null) return;
            if (pendingSid < 0 && outgoing == null && !uploadActive() && diagnosticStep < 0) request(3, 0, new byte[0], "heartbeat");
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true);
        int pad = dp(20); page.setPadding(pad, pad, pad, pad); page.setBackgroundColor(Color.rgb(247,249,250));
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(pad + edges.left, pad + edges.top, pad + edges.right, pad + edges.bottom); return insets;
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page); setContentView(scroll);
        TextView heading = text("G2 Display Lab", 28); page.addView(heading);
        page.addView(text("Experimental custom companion • " + appVersion(), 14));
        page.addView(text("Close the official Halliday app, keep your existing pairing, and wake the glasses. Keep this app open during the test.", 16));
        page.addView(text("1. Sign in to your Halliday G2 account", 19));
        page.addView(text("Loads the existing key for your glasses. Sign-in goes directly to Halliday. Passwords and keys are not saved or shown in the log.", 14));
        emailInput = new EditText(this); emailInput.setHint("Halliday G2 email"); emailInput.setSingleLine(true);
        emailInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailInput.setSaveEnabled(false); emailInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); page.addView(emailInput);
        passwordInput = new EditText(this); passwordInput.setHint("Password"); passwordInput.setSingleLine(true);
        passwordInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setSaveEnabled(false); passwordInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); page.addView(passwordInput);
        signInButton = button("Sign in / load my glasses", v -> signIn()); page.addView(signInButton);
        accountDevice = new Spinner(this); page.addView(accountDevice);
        page.addView(button("Forget account for this session", v -> {
            stopNavigation();
            disconnect(); loginGeneration++; signingIn = false; ownedDevices.clear();
            accountDevice.setAdapter(null); passwordInput.setText(""); emailInput.setText("");
            log("Account keys removed from this app session."); updateButtons();
        }));
        page.addView(text("2. Connect and authenticate", 19));
        status = text("Not connected", 18); page.addView(status);
        scanButton = button("Find my glasses", v -> findDevices()); page.addView(scanButton);
        deviceList = new LinearLayout(this); deviceList.setOrientation(LinearLayout.VERTICAL); page.addView(deviceList);
        page.addView(button("Disconnect", v -> {stopNavigation();disconnect();}));
        page.addView(text("Navigation • Maps / Waze → G2",21));
        page.addView(text("Launches our native navigation screen on G2 over local Wi-Fi. No USB is needed after installation. Large direction cues, turn distance and instruction text come from the selected phone app's notifications.",14));
        navSource=new Spinner(this);navSource.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Google Maps","Waze"}));page.addView(navSource);
        navLayout=new Spinner(this);navLayout.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Full screen","Compact — top left","Compact — top right"}));navLayout.setSelection(NavSession.layout);page.addView(navLayout);
        navLayout.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int pos,long id){NavSession.layout=pos;}public void onNothingSelected(AdapterView<?> p){}});
        page.addView(text("Screen mode can change while running. Compact mode leaves the rest of the display clear. Navigation keeps an awake display active while you wear G2; Stop restores the dashboard and normal sleep timing.",14));
        page.addView(text("Android notification access is required. While the relay is on, only the selected app's navigation notifications are processed. Route text stays in memory and is sent locally to G2; it is not saved to logs or uploaded by this relay.",14));
        page.addView(button("1. Allow navigation notification access",v->openNotificationAccess()));
        navStart=button("2. Start native navigation on G2",v->startNavigation());page.addView(navStart);
        navStop=button("Stop navigation / return to dashboard",v->stopNavigation());page.addView(navStop);
        navStatus=text(NavSession.status,16);page.addView(navStatus);
        navPreview=text("",18);page.addView(navPreview);
        page.addView(button("Preview a sample turn on phone",v->{
            if(NavSession.active){log("Stop the live relay before previewing a sample.");return;}
            NavGuidance sample=NavGuidance.parse(NavGuidance.MAPS,true,"200 m - Turn right","Example Street","","12 min • 6 km • ETA 14:30",null);
            NavSession.preview="SAMPLE — phone only\n"+sample.title+"\n"+sample.body;navPreview.setText(NavSession.preview);
        }));
        page.addView(text("Accept the G2 Wi-Fi dialog, then open Maps or Waze and start a route. Keep mobile data on for online maps. Only notification details are available; unknown maneuvers get text without a guessed arrow. Directions expire after two minutes without a fresh notification. Losing the phone link returns to the dashboard in about 30 seconds. Stop is also available in the phone notification. Maximum session: four hours.",14));
        page.addView(text("MINDYLAB • native app on G2",21));
        page.addView(text("A custom screen with a tap counter. Once MINDYLAB is installed on your glasses, it is available after every restart without USB. Connect G2, open these controls and press Start. Keep this phone screen open. Stop returns to the dashboard; the screen also exits after two minutes.",14));
        nativeStatus=text("Connect G2, then open the app controls.",16);page.addView(nativeStatus);
        nativeConnectButton=button("Connect MINDYLAB controls",v->ftpDiagnostic.startNative());page.addView(nativeConnectButton);
        nativeRunButton=button("Start MINDYLAB on glasses",v->ftpDiagnostic.nativeCommand(1));page.addView(nativeRunButton);
        nativeStopButton=button("Stop MINDYLAB / return to dashboard",v->ftpDiagnostic.nativeCommand(2));page.addView(nativeStopButton);
        infoButton = button("Read device information", v -> request(0x20, 0, new byte[0], "device information")); page.addView(infoButton);
        diagnosticsButton = button("Check upload prerequisites (read only)", v -> beginDiagnostics());
        page.addView(diagnosticsButton);
        page.addView(text("USB access investigation", 19));
        page.addView(text("Checks whether your firmware accepts a Bluetooth request for its OS name. This does not enable USB. Captured firmware 1.1.1 rejects this request; your installed version may differ.", 14));
        consoleButton = button("Check Bluetooth console (read only)", v -> checkConsole());
        page.addView(consoleButton);
        page.addView(text("Wi-Fi SSH investigation", 19));
        page.addView(text("Starts the glasses' local Wi-Fi link for a PC-assisted SSH check. This does not start recording or update firmware. Stop G2 Wi-Fi when the check is finished.", 14));
        wifiStatusButton = button("Read G2 Wi-Fi status", v -> wifiCommand(0x60)); page.addView(wifiStatusButton);
        wifiStartButton = button("Start G2 Wi-Fi for SSH check", v -> wifiCommand(0x61)); page.addView(wifiStartButton);
        wifiStopButton = button("Stop G2 Wi-Fi", v -> wifiCommand(0x62)); page.addView(wifiStopButton);
        page.addView(text("RAM diagnostic over Wi-Fi", 19));
        page.addView(text("Reads six small temporary diagnostic files for the PC-assisted USB test. FTP only reads; the PC creates the test files in G2 RAM. Keep this app open until the test finishes.",14));
        ramDiagnosticButton=button("Read RAM diagnostic over Wi-Fi / FTP",v->ftpDiagnostic.startRam());page.addView(ramDiagnosticButton);
        page.addView(text("Read-only firmware backup", 19));
        page.addView(text("Checks filesystem access over G2 Wi-Fi. Backup reads the listed firmware partitions twice and compares SHA-256 hashes. Files stay on this phone until copied to your PC. Keep this app open and accept Android's G2 Wi-Fi connection dialog.", 14));
        ftpCheckButton = button("Check filesystem access (read only)", v -> ftpDiagnostic.start(false)); page.addView(ftpCheckButton);
        ftpBackupButton = button("Check access and back up firmware", v -> ftpDiagnostic.start(true)); page.addView(ftpBackupButton);
        page.addView(text("Full backup reads the 16 MiB main flash and refreshes misc, bootinfo and factory. Every file is read twice; both complete flash snapshots are retained. It does not activate USB or change the glasses.", 14));
        ftpFullBackupButton = button("Full flash + configuration backup", v -> ftpDiagnostic.startFullFlash()); page.addView(ftpFullBackupButton);
        page.addView(text("Disposable file test", 19));
        page.addView(text("Creates a temporary test file on G2, changes one byte, checks the whole file twice, then removes it. Firmware and the USB setting stay unchanged. Keep this app open until cleanup finishes.", 14));
        ftpDisposableButton = button("Test FTP offset writes (disposable file)", v -> ftpDiagnostic.startDisposable()); page.addView(ftpDisposableButton);
        page.addView(text("USB activation research — unavailable in public build", 19));
        page.addView(text("First save fresh full-flash and configuration backups and verify their PC copy. Then enable USB with one configuration-byte write and complete readbacks. Keep G2 charging with at least 60% battery. Flash writing can damage the configuration if interrupted; a working recovery connection has not been demonstrated. The app never reboots or retries the write automatically.", 14));
        usbPrepareButton = button("1. Prepare fresh USB-test backups", v -> ftpDiagnostic.startUsb(false)); page.addView(usbPrepareButton);
        usbEnableButton = button("2. Enable USB flag (one attempt)", v -> ftpDiagnostic.startUsb(true)); page.addView(usbEnableButton);
        ftpCancelButton = button("Cancel FTP / stop test services", v -> ftpDiagnostic.stop("Stopping the FTP session. Disposable-file cleanup may remain unconfirmed if interrupted.")); page.addView(ftpCancelButton);
        titleInput = new EditText(this); titleInput.setSingleLine(true); titleInput.setHint("Note title"); titleInput.setText("Hello G2");
        titleInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(48)}); page.addView(titleInput);
        contentInput = new EditText(this); contentInput.setHint("Your text"); contentInput.setMinLines(2);
        contentInput.setText("This text was sent by my own app.");
        contentInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)}); page.addView(contentInput);
        page.addView(text("Send replaces the dashboard cheat sheet. It may appear only when you open that card on the glasses. An accepted command still needs a visual check.", 14));
        sendButton = button("Send my note", v -> {
            try { request(0x3e, 5, Protocol.note(titleInput.getText().toString(), contentInput.getText().toString()), "dashboard note"); }
            catch (IllegalArgumentException e) { log(e.getMessage()); }
        }); page.addView(sendButton);
        page.addView(text("Images: transport and display command are still under investigation.", 14));
        page.addView(text("Module transfer experiment", 19));
        page.addView(text("Stores a 792-byte test module under a new filename on the glasses. This does not run the module or start recording. Close the official app first and keep this screen open.", 14));
        uploadButton = button("Upload test module (storage only)", v -> startProbeUpload());
        page.addView(uploadButton);
        page.addView(text("Connection log", 19));
        logView = text("", 12); logView.setTextIsSelectable(true); page.addView(logView);
        ftpDiagnostic = new FtpDiagnostic(this, new FtpDiagnostic.Host() {
            public boolean idle() { return ready && authenticated && pendingSid < 0 && outgoing == null && !writeInFlight && !uploadActive() && diagnosticStep < 0; }
            public void send(int command, int flags, byte[] payload, String label) { request(command, flags, payload, label); }
            public void log(String message) { MainActivity.this.log(message); }
            public void changed() { updateButtons(); }
            public String firmware() { return firmwareSummary; }
            public void nativeStatus(String value){nativeStatus.setText(value);}
        });
        deleteFile("wifi-start.json");
        adapter = getSystemService(BluetoothManager.class).getAdapter();
        updateButtons(); log("Version " + appVersion() + " ready. Installed MINDYLAB controls are available below.");
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return "unknown"; }
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(30,44,50));
        t.setPadding(0, dp(7), 0, dp(7)); return t;
    }
    private Button button(String name, View.OnClickListener action) {
        Button b = new Button(this); b.setText(name); b.setAllCaps(false); b.setOnClickListener(action); return b;
    }
    private void log(String line) {
        String message = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + line;
        android.util.Log.i("G2DisplayLab", line);
        String old = logView.getText().toString();
        if (old.length() > 12000) old = old.substring(old.length() - 8000);
        logView.setText(old + message + "\n");
    }
    private boolean permissions() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    private void updateButtons() {
        boolean ftpActive = ftpDiagnostic != null && ftpDiagnostic.active();
        boolean idle = ready && pendingSid < 0 && outgoing == null && !writeInFlight && !uploadActive() && diagnosticStep < 0 && !ftpActive;
        nativeConnectButton.setEnabled(idle&&authenticated);
        nativeRunButton.setEnabled(ftpDiagnostic!=null&&ftpDiagnostic.nativeReady()&&!ftpDiagnostic.nativeRunning());
        nativeStopButton.setEnabled(ftpDiagnostic!=null&&ftpDiagnostic.nativeReady()&&ftpDiagnostic.nativeRunning());
        navStart.setEnabled(idle&&authenticated&&!NavSession.active);
        navStop.setEnabled(NavSession.active);navSource.setEnabled(!NavSession.active);
        ftpCheckButton.setEnabled(idle && authenticated); ftpBackupButton.setEnabled(idle && authenticated);
        ftpFullBackupButton.setEnabled(idle && authenticated);
        ftpDisposableButton.setEnabled(idle && authenticated);
        ramDiagnosticButton.setEnabled(idle && authenticated);
        usbPrepareButton.setEnabled(!UsbFlagTest.PUBLIC_RELEASE && idle && authenticated);usbEnableButton.setEnabled(!UsbFlagTest.PUBLIC_RELEASE && idle && authenticated);
        ftpCancelButton.setEnabled(ftpActive && !ftpDiagnostic.usbWriteLocked());
        infoButton.setEnabled(idle && authenticated); sendButton.setEnabled(idle && authenticated);
        uploadButton.setEnabled(idle && authenticated);
        diagnosticsButton.setEnabled(idle && authenticated);
        consoleButton.setEnabled(idle && authenticated);
        wifiStatusButton.setEnabled(idle && authenticated);
        wifiStartButton.setEnabled(idle && authenticated);
        wifiStopButton.setEnabled(idle && authenticated);
        scanButton.setEnabled(gatt == null && !scanning && !signingIn && !ownedDevices.isEmpty()&&!NavSession.active);
        signInButton.setEnabled(!signingIn && gatt == null&&!NavSession.active);
        accountDevice.setEnabled(gatt == null && !signingIn&&!NavSession.active);
    }
    private boolean notificationAccess(){return getSystemService(android.app.NotificationManager.class).isNotificationListenerAccessGranted(new android.content.ComponentName(this,NavNotificationListener.class));}
    private void openNotificationAccess(){
        try{startActivity(new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,new android.content.ComponentName(this,NavNotificationListener.class).flattenToString()));}
        catch(android.content.ActivityNotFoundException e){startActivity(new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}
    }
    private void startNavigation(){
        if(NavSession.active||!authenticated||gatt==null||connectionOwner==null||!permissions()||pendingSid>=0||outgoing!=null||writeInFlight||diagnosticStep>=0||uploadActive()||ftpDiagnostic.active())return;
        if(!notificationAccess()){log("Tap Allow navigation notification access, enable G2 Display Lab, then reconnect G2 and start the relay.");return;}
        if(checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},2);log("Allow Nearby Wi-Fi, then start native navigation again.");return;}
        android.net.wifi.WifiManager wifi=getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
        if(wifi==null||!wifi.isWifiEnabled()){log("Turn on phone Wi-Fi before starting native navigation.");return;}
        try{org.json.JSONObject v=new org.json.JSONObject(firmwareSummary);if(!v.optString("firmware").equals("1.0.1.202609052141")||v.optInt("build_no")!=2018)throw new Exception();}
        catch(Exception e){log("Finish G2 diagnostics first. Native navigation requires installed 1.0.1 build 2018.");return;}
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},3);log("Allow the visible relay notification, then tap Start navigation relay again.");return;
        }
        NavBleClient transferred=null;
        try{
            String pkg=navSource.getSelectedItemPosition()==0?NavGuidance.MAPS:NavGuidance.WAZE;
            transferred=new NavBleClient(gatt,writer,gattRouter,mtu,sid);
            // Remove activity ownership before lifecycle cleanup; never disconnect the adopted link.
            gatt=null;writer=null;gattRouter=null;disconnect();
            NavSession.arm(transferred,pkg);
            startForegroundService(new android.content.Intent(this,NavRelayService.class));
            NavBleClient expected=transferred;
            new Handler(Looper.getMainLooper()).postDelayed(()->NavSession.cancelPending(expected),5000);
            status.setText("Navigation owns the connected G2 — see Navigation below");
            log("Navigation keeps the authenticated Bluetooth connection. Open the selected map app and start a route.");
        }catch(Exception e){if(transferred!=null)transferred.close();NavSession.clear();NavSession.status="Could not start navigation relay. Reconnect G2 and try again.";log(NavSession.status);}
        finally{updateButtons();}
    }
    private void stopNavigation(){
        if(NavSession.active)startService(new android.content.Intent(this,NavRelayService.class).setAction(NavRelayService.STOP));
    }
    private void signIn() {
        if (signingIn || gatt != null) return;
        String email = emailInput.getText().toString(), password = passwordInput.getText().toString();
        if (email.trim().isEmpty() || password.isEmpty()) { log("Enter your Halliday G2 email and password."); return; }
        passwordInput.setText("");
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(passwordInput.getWindowToken(),0);
        emailInput.clearFocus();passwordInput.clearFocus();((View)passwordInput.getParent()).requestFocus();
        stopScan(); ownedDevices.clear(); accountDevice.setAdapter(null);
        signingIn = true; int generation = ++loginGeneration; updateButtons(); log("Signing in directly with Halliday…");
        new Thread(() -> {
            try {
                List<AccountClient.OwnedDevice> devices = new AccountClient().signIn(email, password);
                main.post(() -> {
                    if (isDestroyed() || generation != loginGeneration) return;
                    signingIn = false; ownedDevices.addAll(devices);
                    ArrayAdapter<AccountClient.OwnedDevice> choices = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, ownedDevices);
                    choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); accountDevice.setAdapter(choices);
                    log("Loaded " + devices.size() + " existing glasses binding(s). Choose your glasses, then connect."); updateButtons();
                });
            } catch (Exception e) {
                String reason = e instanceof java.io.IOException ? e.getMessage() : "Unable to read the account response (" + e.getClass().getSimpleName() + ").";
                main.post(() -> {
                    if (isDestroyed() || generation != loginGeneration) return;
                    signingIn = false; log(reason); updateButtons();
                });
            }
        }, "G2-account-login").start();
    }
    private void findDevices() {
        if (!permissions()) { requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 1); return; }
        if (adapter == null || !adapter.isEnabled()) { log("Turn on Bluetooth, then try again."); return; }
        if (gatt != null) return;
        stopScan(); found.clear(); deviceList.removeAllViews();
        for (BluetoothDevice device : adapter.getBondedDevices()) addDevice(device, "paired");
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { log("Bluetooth scanner unavailable."); return; }
        scanning = true; updateButtons(); status.setText("Looking for nearby G2 glasses…");
        try {
            scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            main.postDelayed(stopScan, 10000);
        } catch (RuntimeException e) { scanning = false; log("Could not scan: " + e.getClass().getSimpleName()); updateButtons(); }
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) { main.post(() -> { if (scanning) addDevice(result.getDevice(), "nearby"); }); }
        @Override public void onScanFailed(int code) { main.post(() -> { scanning = false; log("Scan failed: " + code); updateButtons(); }); }
    };
    private void addDevice(BluetoothDevice device, String source) {
        String name = device.getName();
        if (name == null || !name.toLowerCase(Locale.ROOT).contains("halliday") || !found.add(device.getAddress())) return;
        Button b = button(name + " • " + source, v -> connect(device)); deviceList.addView(b);
        log("Found " + name + " (" + source + ").");
    }
    private void stopScan() {
        main.removeCallbacks(stopScan);
        if (scanning && scanner != null && permissions()) scanner.stopScan(scanCallback);
        scanning = false;
        if (status != null && gatt == null) status.setText(found.isEmpty() ? "No glasses found yet" : "Choose your glasses");
        if (scanButton != null) updateButtons();
    }
    private void connect(BluetoothDevice device) {
        if(NavSession.active){log("Stop the navigation relay before using the other G2 controls.");return;}
        if (gatt != null) return;
        AccountClient.OwnedDevice selected = (AccountClient.OwnedDevice)accountDevice.getSelectedItem();
        if (selected == null || !selected.matches(device.getAddress(), device.getName())) {
            log("These glasses do not match the selected account binding. Select the correct glasses above."); return;
        }
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            log("Pair these glasses in the official Halliday app first, then return here."); return;
        }
        connectionOwner = selected;
        stopScan(); decoder.reset(); mtu = 23; sid = 0; ready = false; versionOk = false; authenticated = false;
        status.setText("Connecting to " + device.getName() + "…"); log("Opening Bluetooth connection.");
        gattRouter=new GattCallbackRouter(callback);
        gatt = device.connectGatt(getApplicationContext(), false, gattRouter, BluetoothDevice.TRANSPORT_LE); updateButtons();
        BluetoothGatt expected = gatt;
        main.postDelayed(() -> { if (gatt == expected && !ready) { log("Connection setup timed out."); disconnect(); } }, 20000);
    }
    private void disconnect() {
        if (ftpDiagnostic != null) ftpDiagnostic.disconnected();
        if (uploadActive()) log("Module transfer interrupted. A partial file may remain; execution was not requested.");
        probeUpload = null;
        diagnosticStep = -1; lastStatusSummary = ""; firmwareSummary = "{}";
        deleteFile("wifi-start.json");
        stopScan(); main.removeCallbacks(replyTimeout); main.removeCallbacks(heartbeat); pendingSid = -1; pendingCommand = -1;
        outgoing = null; writeInFlight = false; writeToken++; writer = null; ready = false; versionOk = false;
        authenticated = false; connectionOwner = null; decoder.reset();
        BluetoothGatt old = gatt; gatt = null;
        if(gattRouter!=null)gattRouter.bind(null);gattRouter=null;
        if (old != null) { old.disconnect(); old.close(); log("Disconnected."); }
        status.setText(NavSession.active?"Navigation owns the connected G2 — see Navigation below":"Not connected"); updateButtons();
    }
    private void fail(String message) { log(message); disconnect(); }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int code, int state) { main.post(() -> {
            if (g != gatt) return;
            if (code != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) { fail("Connection ended; Bluetooth status " + code + "."); return; }
            if (state == BluetoothProfile.STATE_CONNECTED) { log("Connected. Discovering services."); if (!g.discoverServices()) fail("Service discovery could not start."); }
        }); }
        @Override public void onServicesDiscovered(BluetoothGatt g, int code) { main.post(() -> {
            if (g != gatt) return;
            BluetoothGattService service = g.getService(UUID.fromString(Protocol.SERVICE));
            if (code != BluetoothGatt.GATT_SUCCESS || service == null) { fail("The recovered G2 service is unavailable."); return; }
            writer = service.getCharacteristic(UUID.fromString(Protocol.WRITE));
            BluetoothGattCharacteristic notify = service.getCharacteristic(UUID.fromString(Protocol.NOTIFY));
            if (writer == null || notify == null || (writer.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) { fail("Required G2 characteristics unavailable."); return; }
            log("G2 service found. Write properties=0x" + Integer.toHexString(writer.getProperties()) + ".");
            BluetoothGattDescriptor cccd = notify.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if (cccd == null || !g.setCharacteristicNotification(notify, true)) { fail("Cannot subscribe to replies."); return; }
            byte[] enable = (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            if (g.writeDescriptor(cccd, enable) != BluetoothStatusCodes.SUCCESS) fail("Reply subscription was not queued.");
        }); }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int code) { main.post(() -> {
            if (g != gatt) return;
            if (code != BluetoothGatt.GATT_SUCCESS) { fail("Reply subscription failed: " + code); return; }
            log("Replies enabled."); if (!g.requestMtu(247)) finishSetup();
        }); }
        @Override public void onMtuChanged(BluetoothGatt g, int value, int code) { main.post(() -> {
            if (g != gatt) return;
            if (code == BluetoothGatt.GATT_SUCCESS) mtu = Math.max(23, value);
            log("Bluetooth packet capacity: " + (mtu - 3) + " bytes."); finishSetup();
        }); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            byte[] copy = value.clone(); main.post(() -> { if (g == gatt && c.getUuid().toString().equals(Protocol.NOTIFY)) receive(copy); });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int code) { main.post(() -> {
            if (g != gatt || !writeInFlight) return;
            writeInFlight = false; writeToken++;
            if (code != BluetoothGatt.GATT_SUCCESS) { fail("Bluetooth write failed: " + code + ". Delivery unknown."); return; }
            main.postDelayed(() -> { if (g == gatt) writeNext(); }, 30);
        }); }
    };
    private void finishSetup() {
        if (ready) return;
        ready = true; status.setText("Checking G2 protocol…");
        request(0, 0, new byte[0], "protocol version");
    }
    private void request(int command, int type, byte[] payload, String label) {
        if (!ready || pendingSid >= 0 || outgoing != null) { log("Wait for the current operation or reconnect."); return; }
        if (command != 0 && !versionOk) { log("A compatible version reply is required first."); return; }
        if (command != 0 && command != 1 && !authenticated) { log("Account authentication is required first."); return; }
        pendingSid = sid = (sid + 1) & 255; pendingCommand = command; pendingLabel = label;
        outgoing = Protocol.encode(sid, command, type, payload); outgoingOffset = 0;
        log("Sending " + label + "; id=" + sid + ", command=0x" + Integer.toHexString(command) + ", bytes=" + outgoing.length + ".");
        main.removeCallbacks(replyTimeout); main.postDelayed(replyTimeout, 12000); updateButtons(); writeNext();
    }
    private void writeNext() {
        if (gatt == null || outgoing == null || writeInFlight) return;
        if (outgoingOffset == outgoing.length) { outgoing = null; updateButtons(); maybeAuthenticate(); advanceDiagnostics(); advanceUpload(); ftpDiagnostic.advance(); return; }
        int end = Math.min(outgoing.length, outgoingOffset + Math.min(mtu - 3, 244));
        byte[] part = Arrays.copyOfRange(outgoing, outgoingOffset, end);
        int type = (writer.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        writeInFlight = true; int token = ++writeToken;
        int result = gatt.writeCharacteristic(writer, part, type);
        if (result != BluetoothStatusCodes.SUCCESS) { writeInFlight = false; fail("Bluetooth write was not queued: " + result); return; }
        outgoingOffset = end;
        main.postDelayed(() -> { if (token == writeToken && writeInFlight) fail("Bluetooth write timed out. Delivery unknown."); }, 4000);
    }
    private void receive(byte[] bytes) {
        int rejected = decoder.rejected;
        for (Protocol.Packet p : decoder.feed(bytes)) {
            String kind = new String[]{"request", "ACK", "NCK", "indication"}[p.kind()];
            log("Received " + kind + "; id=" + p.sid + ", command=0x" + Integer.toHexString(p.command) + ", bytes=" + p.payload.length + ".");
            if (authenticated && p.kind() == 3 && p.command == 0x28) logDiagnostic(p, true);
            if (uploadActive() && p.kind() == 3 && (p.command == 5 || p.command == 6)) {
                fail("The glasses ended the upload. File status is unknown; execution was not requested."); return;
            }
            if (!p.matches(pendingSid, pendingCommand)) continue;
            String label = pendingLabel; int command = pendingCommand;
            main.removeCallbacks(replyTimeout); pendingSid = -1; pendingCommand = -1;
            if (ftpDiagnostic.handles(command)) { ftpDiagnostic.reply(p); updateButtons(); continue; }
            if (diagnosticStep >= 0 && command == DIAGNOSTIC_COMMANDS[diagnosticStep]) {
                if (p.kind() == 2) {
                    log("Diagnostic query 0x" + Integer.toHexString(command) + " rejected: " + Protocol.hex(p.payload));
                    if (p.payload.length == 1 && p.payload[0] == 0x19) { disconnect(); continue; }
                } else logDiagnostic(p, false);
                diagnosticStep++;
                if (diagnosticStep == DIAGNOSTIC_COMMANDS.length) {
                    diagnosticStep = -1;
                    log("Read-only checks finished. Wear detection is not the internal PM state; storage totals do not prove a file can be opened.");
                    status.setText("Diagnostic checks finished — see log");
                }
                advanceDiagnostics(); updateButtons(); continue;
            }
            if (uploadActive() && command >= 4 && command <= 7) {
                probeUpload.reply(p);
                if (!probeUpload.active()) {
                    if (probeUpload.complete()) {
                        log("Upload verified by the glasses (size and CRC32): /data2/upload/" + probeUpload.filename);
                        log("Module stored only. No module execution or recording command was sent.");
                        status.setText("Module stored — execution still unavailable");
                    } else {
                        log(probeUpload.error()); status.setText("Module upload failed — see log");
                        if (command == 4 && p.payload.length == 1 && p.payload[0] == 0x17)
                            log("0x17 is a generic upload-start failure: PM state, active update, or file-open failure. No module data was sent.");
                    }
                }
                advanceUpload(); updateButtons(); continue;
            }
            if (command == ConsoleCheck.COMMAND) {
                log(ConsoleCheck.summarize(p));
                status.setText("Console check finished — USB activation unconfirmed");
                if (p.kind() == 2 && p.payload.length == 1 && p.payload[0] == 0x19) disconnect();
                updateButtons(); continue;
            }
            if (command >= 0x60 && command <= 0x62) {
                wifiReply(p); updateButtons(); continue;
            }
            if (p.kind() == 2) {
                log("Glasses rejected " + label + "; response=" + Protocol.hex(p.payload) + ".");
                status.setText("Command rejected — see log");
                if (command == 1 || command == 3 || (p.payload.length == 1 && p.payload[0] == 0x19)) {
                    disconnect(); continue;
                }
            } else if (command == 0) {
                versionOk = p.version == 0x14 && p.businessVersion == 0x14;
                log("Version ACK: protocol=0x" + Integer.toHexString(p.version) + ", business=0x" + Integer.toHexString(p.businessVersion) + ", payload=" + Protocol.hex(p.payload));
                status.setText(versionOk ? "Authenticating your existing glasses…" : "Different protocol version; test stopped");
                maybeAuthenticate();
            } else if (command == 1) {
                try {
                    org.json.JSONObject response = new org.json.JSONObject(new String(p.payload, StandardCharsets.UTF_8));
                    String result = response.optString("result", "");
                    if (!result.equals("granted") && !result.equals("bonded")) { fail("Unexpected authentication reply. Test stopped."); continue; }
                    authenticated = true; status.setText("Authenticated — ready to send a note");
                    log("Existing account key accepted by the glasses (" + result + ").");
                    main.removeCallbacks(heartbeat); main.postDelayed(heartbeat, HEARTBEAT_MS);
                    beginDiagnostics();
                } catch (org.json.JSONException e) { fail("Could not validate the authentication reply."); continue; }
            } else if (command == 0x3e) {
                status.setText("Note accepted — check your glasses"); log("Dashboard note ACK received. Open the cheat-sheet card and check the text.");
            } else if (command == 0x20) {
                // Device identifiers and account metadata stay on screen, outside the debug log.
                String value = (p.flags & 15) >= 6 ? new String(p.payload, StandardCharsets.UTF_8) : Protocol.hex(p.payload);
                new android.app.AlertDialog.Builder(this).setTitle("Device information").setMessage(value).setPositiveButton("Close", null).show();
            }
            ftpDiagnostic.advance(); updateButtons();
        }
        if (decoder.rejected > rejected) log("Ignored a malformed or incomplete protocol candidate.");
    }
    private void maybeAuthenticate() {
        if (!ready || !versionOk || authenticated || pendingSid >= 0 || outgoing != null || connectionOwner == null) return;
        try { request(1, 7, connectionOwner.handshake(), "existing account authentication"); }
        catch (org.json.JSONException e) { fail("Could not prepare account authentication."); }
    }
    private boolean uploadActive() { return probeUpload != null && probeUpload.active(); }
    private void wifiCommand(int command) {
        if (!authenticated || !ready || pendingSid >= 0 || outgoing != null || writeInFlight || uploadActive() || diagnosticStep >= 0) return;
        if (command < 0x60 || command > 0x62) return;
        if (command == 0x61) {
            deleteFile("wifi-start.json");
            request(command, 7, "{\"mode\":\"ap\"}".getBytes(StandardCharsets.UTF_8), "G2 local Wi-Fi start for SSH check");
        } else request(command, 0, new byte[0], command == 0x60 ? "G2 Wi-Fi status" : "G2 Wi-Fi stop");
    }
    private void wifiReply(Protocol.Packet packet) {
        if (packet.kind() == 2) {
            log("Wi-Fi command 0x" + Integer.toHexString(packet.command) + " rejected: " + Protocol.hex(packet.payload));
            status.setText("G2 Wi-Fi command rejected — see log");
            if (packet.payload.length == 1 && packet.payload[0] == 0x19) disconnect();
            return;
        }
        if (packet.command == 0x62) {
            deleteFile("wifi-start.json"); log("G2 accepted Wi-Fi stop. Local connection details removed.");
            status.setText("G2 Wi-Fi stop accepted"); return;
        }
        try {
            org.json.JSONObject reply = new org.json.JSONObject(new String(packet.payload, StandardCharsets.UTF_8));
            org.json.JSONObject summary = new org.json.JSONObject();
            for (String key : new String[]{"mode", "status", "httpd", "ip", "ipaddr", "addr", "mac"})
                if (reply.has(key)) summary.put(key, reply.get(key));
            log("G2 Wi-Fi reply 0x" + Integer.toHexString(packet.command) + ": " + summary);
            if (packet.command == 0x61) {
                org.json.JSONObject local = new org.json.JSONObject();
                for (String key : new String[]{"ssid", "passwd", "mac"})
                    if (reply.has(key)) local.put(key, reply.get(key));
                try (java.io.FileOutputStream stream = openFileOutput("wifi-start.json", MODE_PRIVATE)) {
                    stream.write(local.toString().getBytes(StandardCharsets.UTF_8));
                }
                log("Local Wi-Fi start accepted. PC can now inspect the link; SSH availability is not yet established.");
                status.setText("Wi-Fi start accepted — ready for PC check");
            } else status.setText("G2 Wi-Fi status received — see log");
        } catch (Exception e) {
            log("Wi-Fi reply could not be interpreted. Raw data and credentials withheld.");
            status.setText("Wi-Fi reply unrecognized — see log");
        }
    }
    private void checkConsole() {
        if (!authenticated || !ready || !versionOk || pendingSid >= 0 || outgoing != null || writeInFlight || uploadActive() || diagnosticStep >= 0) return;
        log("Requesting OS name with fixed read-only command uname -s. No factory write or USB activation command will follow automatically.");
        request(ConsoleCheck.COMMAND, ConsoleCheck.TYPE, ConsoleCheck.payload(), "read-only Bluetooth console check");
    }
    private void beginDiagnostics() {
        if (!authenticated || uploadActive() || diagnosticStep >= 0 || pendingSid >= 0) return;
        diagnosticStep = 0; status.setText("Reading firmware, status and storage…");
        log("Starting read-only diagnostic queries; account identifiers and keys are excluded.");
        advanceDiagnostics(); updateButtons();
    }
    private void advanceDiagnostics() {
        if (diagnosticStep < 0 || !authenticated || pendingSid >= 0 || outgoing != null || writeInFlight) return;
        int command = DIAGNOSTIC_COMMANDS[diagnosticStep];
        // Status mask: wear, charger, battery, HFP and recording; no setting is changed.
        request(command, command == 0x28 ? 2 : 0, command == 0x28 ? new byte[]{0x13, (byte)0x84} : new byte[0],
            command == 0x20 ? "firmware diagnostic" : command == 0x28 ? "wear/charging diagnostic" : "storage diagnostic");
    }
    private void logDiagnostic(Protocol.Packet packet, boolean unsolicited) {
        try {
            String summary = DiagnosticReply.summarize(packet);
            if (packet.command == 0x20 && !unsolicited) firmwareSummary = summary;
            if (unsolicited && (summary.equals("{}") || summary.equals(lastStatusSummary))) return;
            if (packet.command == 0x28) lastStatusSummary = summary;
            log("Diagnostic 0x" + Integer.toHexString(packet.command) + ": " + summary);
        } catch (org.json.JSONException e) { if (!unsolicited) log("Unrecognized diagnostic format; raw reply withheld."); }
    }
    private void startProbeUpload() {
        if (!authenticated || pendingSid >= 0 || outgoing != null || uploadActive() || diagnosticStep >= 0) return;
        try (java.io.InputStream stream = getAssets().open("g2_probe.o")) {
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024]; int n;
            while ((n = stream.read(buffer)) != -1) {
                data.write(buffer, 0, n);
                if (data.size() > 792) throw new java.io.IOException("Unexpected module size");
            }
            probeUpload = new ProbeUpload(data.toByteArray(), UUID.randomUUID().toString().replace("-", ""));
            log("Uploading reviewed RAM-marker probe: " + probeUpload.filename + "; 792 bytes; CRC32=" + Long.toHexString(probeUpload.crc) + ".");
            status.setText("Uploading module — storage test");
            advanceUpload();
        } catch (Exception e) { log("Could not prepare the bundled probe: " + e.getClass().getSimpleName()); }
    }
    private void advanceUpload() {
        if (!uploadActive() || !authenticated || pendingSid >= 0 || outgoing != null || writeInFlight) return;
        ProbeUpload.Step step = probeUpload.next();
        if (step != null) request(step.command, step.type, step.payload,
            step.command == 4 ? "module upload start" : step.command == 7 ? "module upload data" :
            step.command == 5 ? "module upload CRC verification" : "module upload cancel");
    }
    @Override public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (request == 1 && permissions()) findDevices();
        else if (request == 2) log("Wi-Fi permission result received. Tap the FTP button to continue.");
        else if (request == 3) log("Notification permission result received. Tap Start navigation relay again.");
        else log("Nearby devices permission is needed to connect.");
    }
    @Override protected void onResume(){super.onResume();main.removeCallbacks(navUiTick);main.post(navUiTick);}
    @Override protected void onStop() { main.removeCallbacks(navUiTick);super.onStop(); if (ftpDiagnostic == null || !ftpDiagnostic.active()) disconnect(); }
    @Override protected void onDestroy() { disconnect(); loginGeneration++; ownedDevices.clear(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
