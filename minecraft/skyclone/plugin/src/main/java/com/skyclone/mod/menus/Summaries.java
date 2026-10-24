package com.skyclone.mod.menus;

import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Numbers the menus summarise from other modules' services: collection and recipe-book percentages,
 * skill average, next calendar event. Every call is defensive - a broken or absent module yields a
 * zero, never an exception in the SkyBlock Menu.
 */
final class Summaries {

    private Summaries() {}

    /** One collection from data/menus/collections.json (generated from research/data/collections.json). */
    static final class Col { public String category; public String id; public int maxTiers; }

    private static volatile List<Col> collections;

    static List<Col> collections() {
        List<Col> c = collections;
        if (c == null) {
            List<Col> loaded = MenuKit.readJson("collections.json", new TypeToken<List<Col>>() {}.getType());
            c = collections = loaded == null ? List.of() : List.copyOf(loaded);
        }
        return c;
    }

    static int totalCollectionTiers() {
        int t = 0;
        for (Col c : collections()) t += c.maxTiers;
        return t;
    }

    private static volatile List<Col> bossCollections;

    /** The boss collections (data/menus/boss_collections.json, generated from the collections module's data). */
    static List<Col> bossCollections() {
        List<Col> c = bossCollections;
        if (c == null) {
            List<Col> loaded = MenuKit.readJson("boss_collections.json", new TypeToken<List<Col>>() {}.getType());
            c = bossCollections = loaded == null ? List.of() : List.copyOf(loaded);
        }
        return c;
    }

    /**
     * {unlocked tiers, total tiers, collections discovered, collections maxed, boss collections
     * discovered, boss collections}. "Discovered" is ANY progress (count &gt; 0), the moment the
     * collections module prints "COLLECTION UNLOCKED"; it used to be tier &gt;= 1, so a player with
     * coal, ender pearls, gunpowder and bone below tier I read "Discovered 1" after four unlock messages.
     * Boss collections sit outside the 790 resource tiers (the reference keeps them apart too).
     */
    static int[] collectionProgress(Player p) {
        int unlocked = 0, total = 0, discovered = 0, maxed = 0, bossFound = 0;
        Features.Collections svc;
        try { svc = Sky.service(Features.Collections.class); } catch (Throwable e) { svc = null; }
        for (Col c : collections()) {
            total += c.maxTiers;
            int t = tier(svc, p, c);
            unlocked += t;
            if (t > 0 || count(svc, p, c) > 0) discovered++;
            if (t >= c.maxTiers) maxed++;
        }
        for (Col c : bossCollections()) if (tier(svc, p, c) > 0 || count(svc, p, c) > 0) bossFound++;
        return new int[]{unlocked, total, discovered, maxed, bossFound, bossCollections().size()};
    }

    private static int tier(Features.Collections svc, Player p, Col c) {
        if (svc == null) return 0;
        try { return Math.max(0, Math.min(c.maxTiers, svc.tier(p, c.id))); } catch (Throwable e) { return 0; }
    }

    private static long count(Features.Collections svc, Player p, Col c) {
        if (svc == null) return 0;
        try { return svc.count(p, c.id); } catch (Throwable e) { return 0; }
    }

    /** The lore lines both the SkyBlock Menu and the profile viewer show under the progress bar. */
    static List<String> collectionLines(int[] c) {
        List<String> out = new ArrayList<>();
        out.add("&7Collection Tiers: &e" + c[0] + "&6/&e" + c[1]);
        out.add("&7Collections Maxed: &e" + c[3] + "&6/&e" + collections().size());
        if (c[5] > 0) out.add("&7Boss Collections: &e" + c[4] + "&6/&e" + c[5]);
        return out;
    }

    /**
     * The whole Collections summary (SkyBlock Menu slot 20 and the profile viewer). Playtest: the bar
     * read "Collections Unlocked 1/790" (TIERS) while every per-category page of the collections module
     * draws its bar as collections DISCOVERED out of the category's size ("4/12") - so the same label
     * meant two different things one click apart. The bar is now collections unlocked out of 90, which
     * is the sum of the category bars (and what the genre's menu shows); tiers get their own line.
     */
    static List<String> collectionSummary(int[] c) {
        List<String> out = new ArrayList<>(MenuKit.progress("Collections Unlocked", c[2], collections().size()));
        out.addAll(collectionLines(c));
        return out;
    }

    /** {unlocked, total} over CRAFTING recipes. */
    static int[] recipeProgress(Player p) {
        int unlocked = 0, total = 0;
        try {
            for (Recipe r : Sky.recipes().byType(Recipe.Type.CRAFTING)) {
                total++;
                try { if (Sky.recipes().unlocked(p, r)) unlocked++; } catch (Throwable ignored) { /* bad key */ }
            }
        } catch (Throwable ignored) {
            // recipe service unavailable
        }
        return new int[]{unlocked, total};
    }

    static double skillAverage(Player p) {
        try { return Sky.service(SkillService.class).average(p); } catch (Throwable t) { return 0; }
    }

    static int skillLevel(Player p, SkillType s) {
        try { return Sky.service(SkillService.class).level(p, s); } catch (Throwable t) { return 0; }
    }

    static int sbLevel(Player p) {
        try { return Sky.service(Features.SkyLevel.class).level(p); } catch (Throwable t) { return 0; }
    }

    static double sbXp(Player p) {
        try { return Sky.service(Features.SkyLevel.class).xp(p); } catch (Throwable t) { return 0; }
    }

    /** An objective id like "talk_banker" turned into "Talk Banker"; real text passes through. */
    static String objectiveText(Player p) {
        String cur;
        try { cur = Sky.service(Features.Objectives.class).current(p); } catch (Throwable t) { cur = null; }
        if (cur == null || cur.isBlank()) return null;
        // wave 5: the owner's own display text (Features.Objectives.currentText, menus Request 7). The no-op and
        // an owner that does not override it return the id, which is caught by the != cur test below.
        try {
            String text = Sky.service(Features.Objectives.class).currentText(p);
            if (text != null && !text.isBlank() && !text.equals(cur)) return text;
        } catch (Throwable ignored) { /* fall back to the data file */ }
        ObjText known = objectiveTexts().get(cur);
        if (known != null && known.text != null && !known.text.isBlank()) return known.text;
        if (!cur.contains(" ") && cur.contains("_")) return Text.titleCase(cur);
        return cur;
    }

    /** The current objective's how-to hint ("Punch the tree on your island..."), or null. */
    static String objectiveHint(Player p) {
        String cur;
        try { cur = Sky.service(Features.Objectives.class).current(p); } catch (Throwable t) { cur = null; }
        if (cur == null || cur.isBlank()) return null;
        ObjText known = objectiveTexts().get(cur);
        return known == null || known.hint == null || known.hint.isBlank() ? null : known.hint;
    }

    /** One row of progression's data/progression/objectives.json (read-only; progression owns the file). */
    static final class ObjText { public String id; public String text; public String hint; }
    static final class ObjFile { public List<ObjText> chain; }

    private static volatile java.util.Map<String, ObjText> objectiveTexts;

    /**
     * Objective id to its display text. {@code Features.Objectives.current} returns the id
     * ("talk_island_helper"); title-casing it read "Talk Island Helper" instead of the chain's own
     * "Talk to your Island Helper". The text lives in progression's data file, so read it from there
     * (data, not code: menus still never imports progression). Missing file = empty map = old behaviour.
     */
    static java.util.Map<String, ObjText> objectiveTexts() {
        java.util.Map<String, ObjText> m = objectiveTexts;
        if (m != null) return m;
        java.util.Map<String, ObjText> out = java.util.Map.of();
        try (java.io.InputStream in = Sky.plugin().getResource("data/progression/objectives.json")) {
            if (in != null) out = parseObjectiveTexts(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Sky.log().warning("[menus] could not read progression objective texts: " + t);
        }
        return objectiveTexts = out;
    }

    /** Pure: progression's objectives.json ({"chain":[{id,text,hint},...]}) as id to row. */
    static java.util.Map<String, ObjText> parseObjectiveTexts(java.io.Reader json) {
        java.util.Map<String, ObjText> out = new java.util.HashMap<>();
        ObjFile f = new com.google.gson.Gson().fromJson(json, ObjFile.class);
        if (f != null && f.chain != null) for (ObjText o : f.chain) if (o != null && o.id != null) out.put(o.id, o);
        return java.util.Map.copyOf(out);
    }

    /** A calendar event with when it next starts (real ms; 0 = running now). */
    record Upcoming(CalendarEvent event, long millis, boolean active, long remaining) {}

    private static List<Upcoming> upcomingCache;
    private static long upcomingAt;

    /**
     * Every listable calendar event, running ones first, then soonest first. Shared by every player
     * and cached for one second: the tab widget asks for it per player per refresh, and core's
     * {@code millisUntil} walks year by year for events with a large {@code everyYears}.
     */
    static synchronized List<Upcoming> upcoming() {
        long now = System.currentTimeMillis();
        if (upcomingCache != null && now - upcomingAt >= 0 && now - upcomingAt < 1000) return upcomingCache;
        upcomingCache = List.copyOf(computeUpcoming());
        upcomingAt = now;
        return upcomingCache;
    }

    /** Drop the cache (an admin forced an event): the next read recomputes. */
    static synchronized void invalidateUpcoming() { upcomingCache = null; }

    private static List<Upcoming> computeUpcoming() {
        List<Upcoming> all = new ArrayList<>();
        try {
            long horizon = CalendarMath.horizonMillis(Sky.calendar().millisPerDay());
            for (CalendarEvent e : Sky.calendar().events()) {
                if (e == null || e.id == null) continue;
                boolean active = Sky.calendar().active(e.id);
                long ms = active ? 0 : Sky.calendar().millisUntil(e.id);
                if (!CalendarMath.listable(active, ms, horizon)) continue;
                long left = 0;
                if (active) {
                    try { left = Math.max(0, Sky.calendar().millisRemaining(e.id)); } catch (Throwable ignored) { left = 0; }
                }
                all.add(new Upcoming(e, active ? 0 : ms, active, left));
            }
        } catch (Throwable ignored) {
            // calendar not up yet
        }
        all.sort(Comparator.comparing((Upcoming u) -> !u.active()).thenComparingLong(Upcoming::millis)
                .thenComparing(u -> u.event().name == null ? "" : u.event().name.toLowerCase(Locale.ROOT)));
        List<Upcoming> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Upcoming u : all) if (seen.add(CalendarMath.dedupeKey(eventName(u.event()), u.active(), u.millis()))) out.add(u);
        return out;
    }

    static String eventName(CalendarEvent e) {
        return e.name == null || e.name.isBlank() ? Text.titleCase(e.id == null ? "event" : e.id) : e.name;
    }

    /** "in 2h 13m" / "&aACTIVE" */
    static String when(Upcoming u) {
        if (u.active()) return u.remaining() > 0 ? "&aACTIVE &7(ends in " + Text.duration(u.remaining() / 1000) + ")" : "&aACTIVE";
        return "&ein " + Text.duration(u.millis() / 1000);
    }

    static String mayorName() {
        try {
            String n = Sky.service(Features.Mayors.class).mayorName();
            return n == null || n.isBlank() ? null : n;
        } catch (Throwable t) {
            return null;
        }
    }
}
