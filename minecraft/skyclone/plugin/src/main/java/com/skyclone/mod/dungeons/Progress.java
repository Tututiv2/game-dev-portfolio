package com.skyclone.mod.dungeons;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.mod.dungeons.model.CataMath;
import com.skyclone.mod.dungeons.model.DClass;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Catacombs and class progress: XP, levels, level-up messages and SkyBlock XP, the {@link Features.Dungeons} service
 * and the requirement prefixes {@code catacombs:<lvl>}, {@code class:<class>:<lvl>} and
 * {@code dungeon_completion:<floor>:<count>}.
 */
public final class Progress implements Features.Dungeons {

    public static final Progress INSTANCE = new Progress();

    private Progress() {}

    public static DData data(Player p) { return Sky.profiles().member(p, "dungeons", DData.class); }

    public static int cap() { return Math.max(1, DDials.CATA_CAP.get()); }

    public static int cataLevel(Player p) { return CataMath.level(data(p).cataXp, cap()); }

    public static int classLevel(Player p, DClass c) { return CataMath.level(data(p).classXp.getOrDefault(c.key(), 0.0), cap()); }

    public static DClass selected(Player p) {
        DClass c = DClass.parse(data(p).selectedClass);
        return c == null ? DClass.BERSERK : c;
    }

    /** Average of the five class levels. */
    public static double classAverage(Player p) {
        double t = 0;
        for (DClass c : DClass.values()) t += classLevel(p, c);
        return t / DClass.values().length;
    }

    @Override public int catacombsLevel(Player p) { return Sky.profiles().isReady(p) ? cataLevel(p) : 0; }

    @Override public int classLevel(Player p, String dungeonClass) {
        DClass c = DClass.parse(dungeonClass);
        return c == null || !Sky.profiles().isReady(p) ? 0 : classLevel(p, c);
    }

    @Override public String selectedClass(Player p) {
        try { return selected(p).name(); } catch (RuntimeException e) { return null; }
    }

    @Override public boolean inDungeon(Player p) { return RunManager.get() != null && RunManager.get().runOf(p) != null; }

    /** Wave 5: true while the player is in a Master Mode run (accessories' Master Skulls apply only there). */
    @Override public boolean masterMode(Player p) {
        if (RunManager.get() == null) return false;
        com.skyclone.mod.dungeons.run.DungeonRun r = RunManager.get().runOf(p);
        return r != null && r.floor != null && r.floor.master;
    }

    // ---- XP ---------------------------------------------------------------------------------

    /** Admin / untracked grant (fires {@link SkyEvents.DungeonXpGain} with source "admin", floor 0). */
    public static double addCataXp(Player p, double xp) { return addCataXp(p, xp, 0, false, "admin"); }

    /**
     * Add Catacombs XP. Wave 5: fires {@link SkyEvents.DungeonXpGain} first (track "catacombs"), so the Catacombs Expert
     * Ring and other listeners can scale it; adds what is left and returns it (0 when nothing was added).
     */
    public static double addCataXp(Player p, double xp, int floor, boolean master, String source) {
        if (xp <= 0 || !Sky.profiles().isReady(p)) return 0;
        xp = fire(p, "catacombs", selectedClass0(p), floor, master, xp, source);
        if (xp <= 0) return 0;
        DData d = data(p);
        int before = CataMath.level(d.cataXp, cap());
        d.cataXp += xp;
        int after = CataMath.level(d.cataXp, cap());
        for (int l = before + 1; l <= after; l++) {
            Msg.box(p, "&3&lSKILL LEVEL UP &cCatacombs " + (l > 1 ? "&8" + Text.roman(l - 1) + "&8 ➜ " : "") + "&c" + Text.roman(l),
                    java.util.List.of("&a&lREWARDS",
                            "  &8+&c" + Text.commas(CataMath.statBoostPercent(l) - CataMath.statBoostPercent(l - 1)) + "% &7dungeon item stat boost",
                            "  &8+&b" + sbXpForCata(l) + " SkyBlock XP"));
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f);
            Sky.service(Features.SkyLevel.class).award(p, "catacombs:" + l, sbXpForCata(l), "Catacombs Level " + l);
        }
        return xp;
    }

    public static double addClassXp(Player p, DClass c, double xp) { return addClassXp(p, c, xp, 0, false, "admin"); }

    /** Add class XP; fires {@link SkyEvents.DungeonXpGain} (track = the class id) first. Returns what was added. */
    public static double addClassXp(Player p, DClass c, double xp, int floor, boolean master, String source) {
        if (xp <= 0 || c == null || !Sky.profiles().isReady(p)) return 0;
        xp = fire(p, c.name(), c.name(), floor, master, xp, source);
        if (xp <= 0) return 0;
        DData d = data(p);
        double old = d.classXp.getOrDefault(c.key(), 0.0);
        int before = CataMath.level(old, cap());
        d.classXp.put(c.key(), old + xp);
        int after = CataMath.level(old + xp, cap());
        for (int l = before + 1; l <= after; l++) {
            Msg.send(p, "&3&l▶ " + c.colored() + " &7class reached level &a" + l + "&7!");
            Sky.service(Features.SkyLevel.class).award(p, "dungeon_class:" + c.key() + ":" + l, 4, c.display + " Level " + l);
        }
        return xp;
    }

    private static String selectedClass0(Player p) {
        try { return selected(p).name(); } catch (RuntimeException e) { return null; }
    }

    /**
     * Fire {@link SkyEvents.DungeonXpGain} (core wave 5) and return the amount the listeners left (never negative). A
     * listener that throws is Bukkit's problem (logged by the server); the original amount is kept in that case.
     */
    static double fire(Player p, String track, String cls, int floor, boolean master, double xp, String source) {
        try {
            SkyEvents.DungeonXpGain ev = new SkyEvents.DungeonXpGain(p, track, cls, floor, master, xp, source);
            org.bukkit.Bukkit.getPluginManager().callEvent(ev);
            double out = ev.amount;
            return Double.isFinite(out) ? Math.max(0, out) : xp;
        } catch (RuntimeException e) {
            return xp;
        }
    }

    /** SkyBlock XP per Catacombs level (TUNE: 20 per level to 39, 40 from 40). */
    static int sbXpForCata(int level) { return level >= 40 ? 40 : 20; }

    // ---- requirement resolvers ----------------------------------------------------------------

    static void registerResolvers() {
        Sky.requirements().register("catacombs", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (DDials.IGNORE_REQS.get()) return true;
                return parts.length >= 2 && Sky.profiles().isReady(p) && cataLevel(p) >= parseInt(parts[1]);
            }
            @Override public String describe(String[] parts) { return "&aCatacombs Level " + (parts.length >= 2 ? parts[1] : "?"); }
        });
        Sky.requirements().register("class", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 3 || !Sky.profiles().isReady(p)) return false;
                DClass c = DClass.parse(parts[1]);
                return c != null && classLevel(p, c) >= parseInt(parts[2]);
            }
            @Override public String describe(String[] parts) {
                DClass c = parts.length >= 2 ? DClass.parse(parts[1]) : null;
                return "&a" + (c == null ? "?" : c.display) + " Class Level " + (parts.length >= 3 ? parts[2] : "?");
            }
        });
        Sky.requirements().register("dungeon_completion", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (DDials.IGNORE_REQS.get()) return true;
                if (parts.length < 2 || !Sky.profiles().isReady(p)) return false;
                int need = parts.length >= 3 ? parseInt(parts[2]) : 1;
                return data(p).completions.getOrDefault(parts[1].toUpperCase(java.util.Locale.ROOT), 0) >= need;
            }
            @Override public String describe(String[] parts) {
                String f = parts.length >= 2 ? parts[1].toUpperCase(java.util.Locale.ROOT) : "?";
                return "&aCompleting The Catacombs " + (f.equals("E") ? "Entrance" : "Floor " + f.replace("F", "").replace("M", "M"));
            }
        });
    }

    static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return Integer.MAX_VALUE; }
    }
}
