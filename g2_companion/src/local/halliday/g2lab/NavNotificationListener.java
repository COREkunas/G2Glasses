package local.halliday.g2lab;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.*;

/** Reads only the selected navigation package. Diagnostics contain counts, never route text. */
public final class NavNotificationListener extends NotificationListenerService {
    private static NavNotificationListener instance;private String currentKey;
    @Override public void onListenerConnected(){instance=this;NavSession.listenerConnected=true;refresh();}
    @Override public void onListenerDisconnected(){instance=null;NavSession.listenerConnected=false;currentKey=null;
        if(NavSession.active){NavSession.state.listenerLost();NavSession.preview="Notification access disconnected.";}}
    static void refreshNow(){if(instance!=null)instance.refresh();}
    private void refresh(){
        if(!NavSession.active){currentKey=null;return;}
        try{
            StatusBarNotification[] active=getActiveNotifications();StatusBarNotification best=null;int matched=0,parsed=0;
            if(active!=null)for(StatusBarNotification s:active){
                if(NavSession.selected.equals(s.getPackageName()))matched++;
                if(read(s)!=null){parsed++;if(best==null||s.getPostTime()>best.getPostTime())best=s;}}
            NavSession.notificationDiagnostic="source="+NavGuidance.name(NavSession.selected)+" listener=connected matched="+matched+" parsed="+parsed;
            if(best!=null)onNotificationPosted(best);
            else if(currentKey!=null){currentKey=null;NavSession.update(null);}
        }catch(RuntimeException ignored){NavSession.state.listenerLost();NavSession.notificationDiagnostic="Notification read unavailable";}
    }
    private NavGuidance read(StatusBarNotification s){
        if(!NavSession.active||s==null||!NavSession.selected.equals(s.getPackageName())||!NavGuidance.allowed(s.getPackageName()))return null;
        Notification n=s.getNotification();if(n==null||(n.flags&Notification.FLAG_GROUP_SUMMARY)!=0)return null;
        Bundle e=n.extras;if(e==null)return null;
        try{
            CharSequence[] raw=null;try{raw=e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);}catch(RuntimeException ignored){}String[] lines=null;
            if(raw!=null){lines=new String[Math.min(raw.length,8)];for(int i=0;i<lines.length;i++)lines[i]=raw[i]==null?"":raw[i].toString();}
            String title=value(e,Notification.EXTRA_TITLE),text=value(e,Notification.EXTRA_TEXT);
            // Android 16 ProgressStyle may leave EXTRA_TEXT empty and keep the
            // entire maneuver in TITLE, with the distance in shortCriticalText.
            String critical=value(e,"android.shortCriticalText");
            if(!critical.isEmpty()&&!NavGuidance.hasDistance(title)&&NavGuidance.hasDistance(critical))title=critical+" "+title;
            return NavGuidance.parse(s.getPackageName(),s.isOngoing(),title,text,
                value(e,Notification.EXTRA_BIG_TEXT),value(e,Notification.EXTRA_SUB_TEXT),lines);
        }catch(RuntimeException ignored){return null;}
    }
    private String value(Bundle b,String key){try{Object v=b.get(key);return v instanceof CharSequence?v.toString():"";}catch(RuntimeException ignored){return "";}}
    @Override public void onNotificationPosted(StatusBarNotification s){
        NavGuidance g=read(s);
        if(g!=null){currentKey=s.getKey();NavSession.update(g,s.getPostTime());}
        else if(s!=null&&s.getKey().equals(currentKey)){currentKey=null;NavSession.update(null);}
    }
    @Override public void onNotificationRemoved(StatusBarNotification s){
        if(s!=null&&s.getKey().equals(currentKey)){currentKey=null;NavSession.update(null);}
    }
}
