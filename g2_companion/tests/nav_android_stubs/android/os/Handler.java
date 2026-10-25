package android.os;
import java.util.*;
/** Deterministic main-loop model, not Android or radio emulation. */
public final class Handler {
    static final class Job {Handler h;Runnable r;long due,order;Job(Handler h,Runnable r,long due,long order){this.h=h;this.r=r;this.due=due;this.order=order;}}
    static final List<Job> jobs=new ArrayList<>();static long time,order;
    public Handler(Looper l){}
    public boolean post(Runnable r){return postDelayed(r,0);}
    public boolean postDelayed(Runnable r,long delay){jobs.add(new Job(this,r,time+delay,order++));return true;}
    public void removeCallbacks(Runnable r){jobs.removeIf(j->j.h==this&&j.r==r);}
    public void removeCallbacksAndMessages(Object ignored){jobs.removeIf(j->j.h==this);}
    public static void reset(){jobs.clear();time=order=0;}
    public static void advance(long delta){long until=time+delta;int budget=10000;
        while(true){Job next=jobs.stream().filter(j->j.due<=until).min(Comparator.<Job>comparingLong(j->j.due).thenComparingLong(j->j.order)).orElse(null);
            if(next==null)break;if(--budget==0)throw new AssertionError("Runaway callback");jobs.remove(next);time=next.due;next.r.run();}
        time=until;
    }
}
