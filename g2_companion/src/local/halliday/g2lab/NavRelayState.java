package local.halliday.g2lab;

import java.text.SimpleDateFormat;
import java.util.*;

/** One newest card only; stale cues are replaced, never replayed as a queue. */
public final class NavRelayState {
    public static final long STALE_MS=120000,MIN_SEND_MS=3000;
    private NavGuidance current;private long seen,changedWall;private String idle="Waiting for navigation";
    private boolean ended;
    public synchronized void update(NavGuidance next,long elapsed,long wall){
        if(next==null){current=null;idle="Navigation ended or unavailable";seen=elapsed;return;}
        if(current==null||!current.title.equals(next.title)||!current.body.equals(next.body)||!current.source.equals(next.source))changedWall=wall;
        current=next;seen=elapsed;
    }
    public synchronized void listenerLost(){current=null;idle="Notification access unavailable";}
    public synchronized void stop(){ended=true;current=null;}
    public static final class NativeFrame {
        public final int state,fresh;public final NavGuidance guidance;
        NativeFrame(int s,int f,NavGuidance g){state=s;fresh=f;guidance=g;}
    }
    public synchronized NativeFrame nativeFrame(long now){
        if(ended)return new NativeFrame(3,0,null);
        if(current==null)return new NativeFrame(idle.contains("access")?4:idle.contains("ended")?3:0,0,null);
        long age=now-seen;
        if(age<0||age>=STALE_MS)return new NativeFrame(2,0,null);
        return new NativeFrame(1,Math.max(1,(int)((STALE_MS-age)/1000)),current);
    }
    public static final class Card {
        public final String title,body,id;
        Card(String title,String body){this.title=title;this.body=body;id=title+"\n"+body;}
        public byte[] payload(){return Protocol.note(title,body);}
    }
    public synchronized Card card(long elapsed){
        if(ended)return new Card("NAVIGATION STOPPED","Relay stopped on phone.");
        if(current==null)return new Card("G2 NAVIGATION",idle+".\nStart a route in the selected phone app.");
        if(elapsed<seen||elapsed-seen>=STALE_MS)return new Card("DIRECTIONS NOT CURRENT","No fresh navigation notification.\nCheck the route on your phone.");
        String time=new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date(changedWall));
        return new Card(current.title,NavGuidance.clip(current.body+"\n"+current.source+" | Updated "+time,512,256));
    }
}
