package com.testpilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/** Chat lines and the action bar, as they reach the HUD. Thread-safe. */
public final class ChatLog {

    private static final int KEEP = 500;

    private record Line(long index, long time, String plain, String legacy) {
    }

    private static final Deque<Line> LINES = new ArrayDeque<>();
    private static long total;
    private static String actionBarPlain = "";
    private static String actionBarLegacy = "";
    private static long actionBarTime;

    private ChatLog() {
    }

    public static synchronized void add(Component c) {
        total++;
        LINES.addLast(new Line(total, System.currentTimeMillis(), c.getString(), Text.legacy(c)));
        while (LINES.size() > KEEP) {
            LINES.removeFirst();
        }
    }

    public static synchronized void actionBar(Component c) {
        actionBarPlain = c.getString();
        actionBarLegacy = Text.legacy(c);
        actionBarTime = System.currentTimeMillis();
    }

    public static synchronized long total() {
        return total;
    }

    /** The last {@code n} lines (oldest first). */
    public static synchronized JsonArray last(int n) {
        JsonArray out = new JsonArray();
        int skip = Math.max(0, LINES.size() - n);
        int i = 0;
        for (Line l : LINES) {
            if (i++ >= skip) {
                out.add(json(l));
            }
        }
        return out;
    }

    /** Lines whose running index is greater than {@code since}. */
    public static synchronized JsonArray since(long since) {
        JsonArray out = new JsonArray();
        for (Line l : LINES) {
            if (l.index > since) {
                out.add(json(l));
            }
        }
        return out;
    }

    public static synchronized JsonObject actionBarJson() {
        JsonObject o = new JsonObject();
        o.addProperty("text", actionBarPlain);
        o.addProperty("legacy", actionBarLegacy);
        o.addProperty("ageMs", actionBarTime == 0 ? -1 : System.currentTimeMillis() - actionBarTime);
        return o;
    }

    private static JsonObject json(Line l) {
        JsonObject o = new JsonObject();
        o.addProperty("n", l.index);
        o.addProperty("text", l.plain);
        o.addProperty("legacy", l.legacy);
        o.addProperty("time", l.time);
        return o;
    }
}
