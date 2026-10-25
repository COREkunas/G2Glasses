package local.halliday.g2lab;

/** Process memory only. No binding keys or route history in Intents/preferences/files. */
final class NavSession {
    static volatile boolean active,listenerConnected;static String selected=NavGuidance.MAPS;
    static volatile String status="Native navigation is stopped.",preview="";
    static volatile String notificationDiagnostic="Notification reader idle";
    static volatile int layout=0;
    static NavRelayState state=new NavRelayState();
    private static NavBleClient pending;
    static void arm(NavBleClient client,String pkg){
        if(active||client==null||!NavGuidance.allowed(pkg))throw new IllegalStateException();
        selected=pkg;pending=client;state=new NavRelayState();active=true;
        status="Starting navigation on the connected G2…";preview="Waiting for "+NavGuidance.name(pkg)+" directions.";
    }
    static NavBleClient take(){NavBleClient c=pending;pending=null;return c;}
    static void cancelPending(NavBleClient expected){if(pending==expected){status="Navigation service did not start. Reconnect G2.";clear();}}
    static void clear(){if(pending!=null)pending.close();pending=null;active=false;state=new NavRelayState();preview="";}
    static void update(NavGuidance g){update(g,System.currentTimeMillis());}
    static void update(NavGuidance g,long postedWall){
        if(!active)return;long wall=System.currentTimeMillis();long age=Math.max(0,wall-postedWall);
        state.update(g,android.os.SystemClock.elapsedRealtime()-age,wall-age);
        preview=g==null?"Navigation ended or its notification is unavailable.":g.title+"\n"+g.body;
    }
}
