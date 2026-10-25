package local.halliday.g2lab;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Best-effort text adapter. Notification layouts are not a public navigation API. */
public final class NavGuidance {
    public static final String MAPS="com.google.android.apps.maps",WAZE="com.waze";
    private static final Pattern DISTANCE=Pattern.compile("(?iu)(?<![\\p{L}\\d])\\d+(?:[.,]\\d+)?\\s*(?:km|m|mi|ft|yd)(?![\\p{L}])");
    public final String source,title,body;
    public final String maneuver,distance,instruction,detail;
    private NavGuidance(String source,String title,String body,String maneuver,String distance,String instruction,String detail){this.source=source;this.title=title;this.body=body;this.maneuver=maneuver;this.distance=distance;this.instruction=instruction;this.detail=detail;}
    public static boolean allowed(String pkg){return MAPS.equals(pkg)||WAZE.equals(pkg);}
    static boolean hasDistance(String s){return DISTANCE.matcher(clean(s)).find();}
    public static String name(String pkg){return MAPS.equals(pkg)?"Google Maps":WAZE.equals(pkg)?"Waze":"Navigation";}
    public static NavGuidance parse(String pkg,boolean ongoing,String title,String text,String big,String sub,String[] lines){
        if(!allowed(pkg))return null;
        title=clean(title);text=clean(text);big=clean(big);sub=clean(sub);
        LinkedHashSet<String> pieces=new LinkedHashSet<>();
        for(String s:new String[]{title,text,big,sub})if(!s.isEmpty())pieces.add(s);
        if(lines!=null)for(int i=0;i<Math.min(lines.length,8);i++){String s=clean(lines[i]);if(!s.isEmpty())pieces.add(s);}
        String all=String.join("\n",pieces);if(all.isEmpty())return null;
        String low=all.toLowerCase(Locale.ROOT);
        // Obvious non-navigation ongoing notifications must not overwrite the card.
        if(low.contains("downloading")||low.contains("download paused")||low.contains("offline map")||low.contains("atsisiunč"))return null;
        String direction=direction(title);boolean distance=DISTANCE.matcher(all).find();
        // Maps may put only distance in its title, and the maneuver in text.
        if(direction.isEmpty()&&DISTANCE.matcher(title).matches())direction=direction(text);
        if(direction.isEmpty()&&!distance)return null;
        if(!ongoing&&(direction.isEmpty()||!distance))return null;
        String heading=direction.isEmpty()?name(pkg):direction;
        // Only label a next-turn distance when the instruction title itself supplies it.
        Matcher m=DISTANCE.matcher(title);if(!direction.isEmpty()&&m.find())heading+="  "+m.group();
        String turnDistance="";Matcher next=DISTANCE.matcher(title);
        if(!direction.isEmpty()&&next.find())turnDistance=next.group();
        if(turnDistance.isEmpty()&&!direction.isEmpty()){
            next=DISTANCE.matcher(text);if(next.find()&&next.start()==0&&!text.toLowerCase(Locale.ROOT).contains("eta"))turnDistance=next.group();
        }
        String instruction=direction(title).isEmpty()?text:title;
        String detail=direction(title).isEmpty()?sub:text+(sub.isEmpty()?"":"  "+sub);
        if(instruction.isEmpty())instruction=title;
        return new NavGuidance(name(pkg),clip(heading,48,48),clip(all,420,210),direction,turnDistance,instruction,detail);
    }
    private static String direction(String title){
        String s=title.toLowerCase(Locale.ROOT);
        if(s.matches(".*\\b(?:u[ -]?turn|make a u[ -]?turn)\\b.*")||s.contains("apsisukite"))return "U-TURN";
        if(s.contains("roundabout")||s.contains("žiedin"))return "ROUNDABOUT";
        if(s.matches(".*\\b(?:turn|keep|bear) (?:slightly |sharply |slight |sharp )?left\\b.*")||s.contains("į kairę"))return "LEFT";
        if(s.matches(".*\\b(?:turn|keep|bear) (?:slightly |sharply |slight |sharp )?right\\b.*")||s.contains("į dešinę"))return "RIGHT";
        if(s.contains("continue straight")||s.contains("head straight")||s.contains("važiuokite tiesiai"))return "STRAIGHT";
        if(s.contains("you have arrived")||s.contains("atvykote"))return "ARRIVED";
        return ""; // Never guess an arrow from an unrecognized language or icon.
    }
    static String clean(String s){
        if(s==null)return "";StringBuilder b=new StringBuilder();
        // Bound before regex work, strip controls/bidi overrides, preserve normal Unicode.
        for(int i=0;i<s.length()&&b.length()<1500;){int c=s.codePointAt(i);i+=Character.charCount(c);
            int type=Character.getType(c);
            if(Character.isWhitespace(c)||Character.isSpaceChar(c)){b.append(' ');continue;}
            if(type==Character.CONTROL||type==Character.FORMAT||type==Character.SURROGATE)continue;b.appendCodePoint(c);
        }
        return b.toString().replaceAll(" +"," ").trim();
    }
    public static String clip(String s,int bytes,int chars){
        StringBuilder b=new StringBuilder();int used=0;
        for(int i=0;i<s.length();){int c=s.codePointAt(i);String part=new String(Character.toChars(c));
            int n=part.getBytes(StandardCharsets.UTF_8).length;if(used+n>bytes||b.length()+part.length()>chars)break;
            b.append(part);used+=n;i+=part.length();}
        return b.toString();
    }
}
