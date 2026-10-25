package local.halliday.g2lab;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synthetic notification text; no live notification, location or account data. */
public final class NavigationTest {
    static int checks;
    static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    static NavGuidance parse(String p,boolean ongoing,String title,String text){return NavGuidance.parse(p,ongoing,title,text,"","12 min • 6 km • ETA 14:30",null);}
    static void valid(NavRelayState.Card c){
        check(c.title.getBytes(StandardCharsets.UTF_8).length<=48,"title byte limit");
        check(c.body.getBytes(StandardCharsets.UTF_8).length<=512&&c.body.length()<=256,"body limits");
        byte[] packet=Protocol.encode(1,0x3e,5,c.payload());
        Protocol.Decoder d=new Protocol.Decoder();List<Protocol.Packet> decoded=new ArrayList<>();
        for(int i=0;i<packet.length;i+=20)decoded.addAll(d.feed(Arrays.copyOfRange(packet,i,Math.min(i+20,packet.length))));
        check(decoded.size()==1&&decoded.get(0).command==0x3e&&d.rejected==0,"fragmented note packet");
    }
    public static void main(String[] args){
        NavGuidance right=parse(NavGuidance.MAPS,true,"200 m - Turn right","Example Street");
        check(right!=null&&right.title.equals("RIGHT  200 m"),"English instruction");
        check(right.body.contains("Example Street")&&right.body.contains("ETA 14:30"),"retain supplied metadata");
        check(parse(NavGuidance.WAZE,true,"Turn left","500 ft - Test Road").title.equals("LEFT"),"no invented distance in title");
        check(parse(NavGuidance.MAPS,true,"0,4 km - Sukite į dešinę","Vilniaus g.").title.equals("RIGHT  0,4 km"),"Lithuanian instruction");
        check(parse(NavGuidance.WAZE,true,"Make a U-turn","100 m").title.equals("U-TURN"),"U-turn");
        check(parse(NavGuidance.MAPS,true,"At the roundabout take exit 2","200 m").title.equals("ROUNDABOUT"),"exit text preserved");
        check(parse(NavGuidance.MAPS,true,"Continue straight","2 km").title.equals("STRAIGHT"),"straight");
        check(parse(NavGuidance.MAPS,true,"300 m","Right Street").title.equals("Google Maps"),"road name is not a right turn");
        check(parse(NavGuidance.MAPS,true,"Через 200 м поверните направо","Улица").body.contains("Улица"),"unknown language remains source text");
        check(parse("other.messages",true,"Turn right 200 m","Secret") == null,"unrelated package");
        check(parse(NavGuidance.MAPS,false,"Traffic nearby","6 km") == null,"non-navigation alert");
        check(parse(NavGuidance.MAPS,false,"Turn left in 200 m","Test Street") != null,"clear nonongoing turn");
        check(parse(NavGuidance.MAPS,true,"Downloading offline map","6 km") == null,"offline download filtered");
        check(NavGuidance.parse(NavGuidance.WAZE,true,"Welcome","",null,null,null)==null,"no navigation clues");
        check(NavGuidance.parse(NavGuidance.MAPS,true,"You have arrived","",null,null,null)!=null,"arrival without distance");
        check(NavGuidance.clean("A\u202eB\nC\u0000D").equals("AB CD"),"controls stripped");
        String longText=String.join("",Collections.nCopies(800,"🔵ą界"));
        NavGuidance unicode=parse(NavGuidance.MAPS,true,"Turn right 200 m",longText);
        check(unicode.body.getBytes(StandardCharsets.UTF_8).length<=420&&unicode.body.length()<=210,"Unicode truncation");
        NavRelayState state=new NavRelayState();valid(state.card(0));
        state.update(right,1000,1700000000000L);NavRelayState.Card a=state.card(1000);valid(a);
        check(a.body.contains("Updated "),"visible observation time");
        check(state.card(120999).id.equals(a.id),"fresh until exact threshold");
        check(state.card(121000).title.equals("DIRECTIONS NOT CURRENT"),"stale route replaced");
        state.update(right,122000,1700000121000L);
        check(state.card(122000).id.equals(a.id),"duplicate refresh without duplicate content");
        NavGuidance left=parse(NavGuidance.MAPS,true,"Turn left 100 m","Next Street");
        state.update(left,123000,1700000122000L);state.update(right,124000,1700000123000L);
        check(state.card(124000).title.startsWith("RIGHT"),"latest wins; no stale backlog");
        state.update(null,125000,1700000124000L);check(state.card(125000).body.contains("ended"),"removed notification clears guidance");
        state.listenerLost();check(state.card(126000).body.contains("access unavailable"),"listener loss clears guidance");
        state.update(unicode,127000,1700000125000L);valid(state.card(127000));
        state.stop();check(state.card(128000).title.equals("NAVIGATION STOPPED"),"stop card");valid(state.card(128000));
        state.update(right,129000,1700000126000L);check(state.card(129000).title.equals("NAVIGATION STOPPED"),"notification cannot restart stopped relay");
        NavRelayState old=new NavRelayState();old.update(right,-200000,1700000000000L);
        check(old.card(1000).title.equals("DIRECTIONS NOT CURRENT"),"old initial notification not treated as fresh");
        for(String unit:new String[]{"m","km","ft","mi","yd"}){NavRelayState x=new NavRelayState();x.update(parse(NavGuidance.MAPS,true,"Turn right in 1.5 "+unit,longText),0,1700000000000L);valid(x.card(0));}
        System.out.println("Navigation parser/state/transport checks passed: "+checks+"; synthetic fixtures only.");
    }
}
