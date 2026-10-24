package com.aisky.showcontrol;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.world.IslandDef;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Locale;
import java.util.regex.Pattern;

/** Human names for ids, colour codes stripped. Every lookup falls back to a title-cased id, never throws. */
final class Names {

    private Names() {}

    private static final Pattern CODES = Pattern.compile("(?i)[&§][0-9a-fk-orx]");

    static String strip(String s) {
        if (s == null) return "";
        return CODES.matcher(s).replaceAll("").trim();
    }

    static String plain(Component c) {
        if (c == null) return "";
        return strip(PlainTextComponentSerializer.plainText().serialize(c));
    }

    static String titleCase(String id) {
        if (id == null || id.isBlank()) return "";
        String[] parts = id.toLowerCase(Locale.ROOT).replace(':', ' ').split("[_ ]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    static String island(String id) {
        if (id == null) return "nowhere";
        try {
            IslandDef d = Sky.worlds().island(id);
            if (d != null && d.displayName != null && !d.displayName.isBlank()) return strip(d.displayName);
        } catch (RuntimeException ignored) { }
        return titleCase(id);
    }

    static String item(String id) {
        if (id == null) return "";
        try {
            ItemDef d = Sky.items().def(id);
            if (d != null && d.name != null && !d.name.isBlank()) return strip(d.name);
        } catch (RuntimeException ignored) { }
        return titleCase(id);
    }

    static String mob(MobDef d) {
        if (d == null) return "something";
        if (d.name != null && !d.name.isBlank()) return strip(d.name);
        return titleCase(d.id);
    }

    static String mob(String id) {
        if (id == null) return "something";
        try {
            MobDef d = Sky.mobs().def(id);
            if (d != null) return mob(d);
        } catch (RuntimeException ignored) { }
        return titleCase(id);
    }

    static String calendarEvent(String id) {
        try {
            for (CalendarEvent e : Sky.calendar().events()) {
                if (e.id != null && e.id.equals(id) && e.name != null) return strip(e.name);
            }
        } catch (RuntimeException ignored) { }
        return titleCase(id);
    }

    /** "an"/"a" for an English noun phrase. */
    static String article(String noun) {
        if (noun == null || noun.isEmpty()) return "a";
        return "aeiouAEIOU".indexOf(noun.charAt(0)) >= 0 ? "an" : "a";
    }

    static String coins(double v) {
        if (v >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0).replace(".0M", "M");
        if (v >= 10_000) return String.format(Locale.ROOT, "%.1fk", v / 1_000.0).replace(".0k", "k");
        return String.format(Locale.ROOT, "%,d", (long) Math.floor(v));
    }
}
