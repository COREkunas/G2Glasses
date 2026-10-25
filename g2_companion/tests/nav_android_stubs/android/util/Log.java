package android.util;
import java.util.*;
public final class Log {public static final List<String> messages=new ArrayList<>();public static int i(String tag,String text){messages.add(tag+": "+text);return 0;}}
