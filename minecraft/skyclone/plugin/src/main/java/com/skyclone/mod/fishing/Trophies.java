package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.util.Requirements;
import com.skyclone.mod.fishing.FishingCatalog.TrophyFish;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Trophy Fishing (research 07 §3.7): eighteen lava fish of the Crimson Isle, each with a base chance
 * and a condition, four tiers with pity, titles, filleting into Magmafish, and the Golden Fish that
 * shows up after a long lava session.
 */
final class Trophies {

    private Trophies() {}

    static final String[] RARITY_ORDER = {"LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON"};
    static final String TROPHY_ISLAND = "crimson_isle";

    // ---- Golden Fish session ---------------------------------------------------------------------

    static final class Session { long start; long last; long goldenUntil; }
    static final Map<UUID, Session> SESSIONS = new HashMap<>();

    /** Called on every lava cast on the Crimson Isle; may make a Golden Fish appear. */
    static void onLavaCast(Player p) {
        long now = System.currentTimeMillis();
        Session s = SESSIONS.computeIfAbsent(p.getUniqueId(), k -> new Session());
        if (s.start == 0 || now - s.last > FishDials.GOLDEN_GAP.get() * 1000L) s.start = now;
        s.last = now;
        if (s.goldenUntil > now) return;
        double minutes = (now - s.start) / 60000.0;
        double sooner = FishEffects.goldenSooner(p);            // Goldfin attribute: "cooldown reduced by v s"
        double chance = FishMath.goldenFishChance(minutes, Math.max(0, FishDials.GOLDEN_START.get() - sooner),
                Math.max(0.1, FishDials.GOLDEN_FULL.get() - sooner));
        if (chance > 0 && ThreadLocalRandom.current().nextDouble() < chance) {
            s.goldenUntil = now + FishDials.GOLDEN_WINDOW.get() * 1000L;
            Msg.send(p, "&6&lA GOLDEN FISH &eis circling your line! &7Reel in a lava catch within &e"
                    + FishDials.GOLDEN_WINDOW.get() + "s&7.");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 2f);
        }
    }

    static boolean goldenActive(Player p) {
        Session s = SESSIONS.get(p.getUniqueId());
        return s != null && s.goldenUntil > System.currentTimeMillis();
    }

    static void forceGolden(Player p) {
        Session s = SESSIONS.computeIfAbsent(p.getUniqueId(), k -> new Session());
        s.goldenUntil = System.currentTimeMillis() + FishDials.GOLDEN_WINDOW.get() * 1000L;
    }

    static long sessionMinutes(Player p) {
        Session s = SESSIONS.get(p.getUniqueId());
        if (s == null || s.start == 0 || System.currentTimeMillis() - s.last > FishDials.GOLDEN_GAP.get() * 1000L) return 0;
        return (System.currentTimeMillis() - s.start) / 60000;
    }

    // ---- the volcano's eruption clock (Gusher) -----------------------------------------------------
    // The Gusher bites 10-15 minutes after the volcano erupts (research 07 3.7). The crimson module
    // runs the real eruption as a calendar event (`crimson_volcano_eruption`, every 135 min) with its
    // own announcement and crater particles, so the Gusher keys off THAT event's start (dial
    // fishing.trophy.eruption-event); only if no such event is registered does fishing fall back to
    // its own fixed cycle (eruption-cycle-minutes) and announce that eruption itself. Until
    // 2026-10-03 the Gusher ran on a private 30-minute clock nobody could see; now the geysers steam
    // while it bites, the island is told when the window opens and closes, and the sidebar / trophy
    // menu show the countdown.

    /** Millis since the last eruption began, the cycle length, millis until the next one; own = fallback cycle. */
    record EruptionClock(long since, long cycle, long untilNext, boolean own) {}

    private static CalendarEvent eruptionEvent() {
        String id = FishDials.ERUPTION_EVENT.get();
        if (id == null || id.isBlank()) return null;
        try {
            for (CalendarEvent e : Sky.calendar().events()) if (id.equals(e.id) && e.intervalMinutes > 0) return e;
        } catch (RuntimeException ignored) { }
        return null;
    }

    static EruptionClock eruptionClock() {
        long now = System.currentTimeMillis();
        CalendarEvent ev = eruptionEvent();
        if (ev == null) {
            long cycle = Math.max(1, FishDials.ERUPTION_CYCLE.get()) * 60_000L;
            long pos = Math.floorMod(now, cycle);
            return new EruptionClock(pos, cycle, cycle - pos, true);
        }
        long cycle = ev.intervalMinutes * 60_000L;
        long dur = Math.max(0, ev.durationMinutes) * 60_000L;
        if (Sky.calendar().active(ev.id)) {
            long rem = Sky.calendar().millisRemaining(ev.id);
            long since = rem > 0 ? Math.max(0, dur - rem) : 0;
            return new EruptionClock(since, cycle, Math.max(0, cycle - since), false);
        }
        long until = Math.max(0, Sky.calendar().millisUntil(ev.id));
        return new EruptionClock(Math.max(0, cycle - until), cycle, until, false);
    }

    private static long fromMs() { return Math.max(0, FishDials.GUSHER_FROM.get()) * 60_000L; }
    /** End of the window (GUSHER_TO is an inclusive minute). */
    private static long toEndMs(EruptionClock c) { return Math.min(c.cycle(), (FishDials.GUSHER_TO.get() + 1) * 60_000L); }
    private static boolean open(EruptionClock c) { return c.since() >= fromMs() && c.since() < toEndMs(c); }

    /** True while the Gusher bites (research 07 3.7: minutes 10-15 after an eruption). */
    static boolean gusherWindow() { return open(eruptionClock()); }

    /** Seconds the open window has left, or seconds until it opens (when closed). */
    static long gusherSeconds() {
        EruptionClock c = eruptionClock();
        if (open(c)) return (toEndMs(c) - c.since()) / 1000L;
        if (c.since() < fromMs()) return (fromMs() - c.since()) / 1000L;
        return (c.untilNext() + fromMs()) / 1000L;
    }

    /** Seconds until the next eruption. */
    static long eruptionSeconds() { return eruptionClock().untilNext() / 1000L; }

    /** "4:05" / "12m". */
    static String clock(long seconds) {
        if (seconds >= 600) return (seconds / 60) + "m";
        return (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private static long lastSince = -1;
    private static boolean lastOpen;
    private static int task = -1;

    static void start() {
        stop();
        task = org.bukkit.Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Trophies::eruptionTick, 40L, 20L).getTaskId();
    }

    static void stop() {
        if (task != -1) org.bukkit.Bukkit.getScheduler().cancelTask(task);
        task = -1;
    }

    /** Once a second: announce the eruption / window edges to players on the Crimson Isle, steam the geysers. */
    private static void eruptionTick() {
        if (!FishDials.ENABLED.get() || !FishDials.LAVA.get()) return;
        List<Player> here = new ArrayList<>();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers())
            if (Fishing.ready(p) && TROPHY_ISLAND.equals(Fishing.island(p.getLocation()))) here.add(p);
        // the edges are tracked even with nobody there, so a player arriving later never hears a stale one
        EruptionClock c = eruptionClock();
        boolean open = open(c);
        boolean first = lastSince < 0;
        boolean erupted = !first && c.since() + 5_000L < lastSince;   // the clock wrapped: a new eruption began
        boolean opened = !first && open && !lastOpen;
        boolean closed = !first && !open && lastOpen;
        lastSince = c.since();
        lastOpen = open;
        if (here.isEmpty()) return;
        List<Location> geysers = Geysers.sites(TROPHY_ISLAND);
        if (open) for (Location g : geysers) steam(g, false);
        if (first) return;                                  // never announce on boot, only on a real edge
        String msg = null;
        Sound sound = null;
        if (erupted) {
            for (Location g : geysers) steam(g, true);
            if (c.own()) {                                  // the crimson event announces its own eruption
                msg = "&c&lVOLCANO ERUPTION! &7The Crimson volcano erupted. &6Gushers &7surface in &e"
                        + FishDials.GUSHER_FROM.get() + " minutes&7.";
                sound = Sound.ENTITY_GENERIC_EXPLODE;
            }
        } else if (opened) {
            msg = "&6&lGUSHERS! &7The geysers are steaming - &6Gushers &7bite in the volcano for &e"
                    + clock((toEndMs(c) - c.since()) / 1000L) + "&7.";
            sound = Sound.BLOCK_LAVA_EXTINGUISH;
        } else if (closed) {
            msg = "&7The geysers have gone quiet. Next eruption in &e" + clock(c.untilNext() / 1000L) + "&7.";
        }
        if (msg == null) return;
        for (Player p : here) {
            Msg.send(p, msg);
            if (sound != null) p.playSound(p.getLocation(), sound, 0.6f, 0.6f);
        }
    }

    private static void steam(Location g, boolean burst) {
        World w = g.getWorld();
        if (w == null || !w.isChunkLoaded(g.getBlockX() >> 4, g.getBlockZ() >> 4)) return;
        Location at = g.clone().add(0.5, 0.6, 0.5);
        if (burst) {
            w.spawnParticle(org.bukkit.Particle.LAVA, at, 30, 0.6, 0.4, 0.6, 0.2);
            w.spawnParticle(org.bukkit.Particle.CAMPFIRE_SIGNAL_SMOKE, at, 20, 0.4, 1.5, 0.4, 0.05);
        } else {
            w.spawnParticle(org.bukkit.Particle.CAMPFIRE_COSY_SMOKE, at, 3, 0.25, 0.3, 0.25, 0.02);
        }
    }

    // ---- the roll -------------------------------------------------------------------------------

    /** What a lava cast knows when it is reeled in. */
    record Context(Player player, String island, String zoneText, Location bobber, String rodId, ItemData rod,
                   String bait, double trophyChance, long bobberAgeMs) {}

    /** The trophy fish this catch is, or null. Only Crimson Isle lava. Rarer fish are tried first. */
    static String roll(Context c) {
        if (!TROPHY_ISLAND.equals(c.island())) return null;
        if (FishEffects.teraShell(c.player())) return null;     // Tera Shell Necklace: "you can no longer catch Trophies"
        if (goldenActive(c.player())) {
            SESSIONS.get(c.player().getUniqueId()).goldenUntil = 0;
            SESSIONS.get(c.player().getUniqueId()).start = System.currentTimeMillis();
            return "GOLDEN_FISH";
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (String rarity : RARITY_ORDER) {
            for (Map.Entry<String, TrophyFish> e : Fishing.CAT.trophy.fish.entrySet()) {
                TrophyFish f = e.getValue();
                if (!rarity.equals(f.rarity) || "golden".equals(f.cond)) continue;
                double chance;
                if ("mana".equals(f.cond)) chance = FishMath.manaRayChance(Sky.vitals().mana(c.player()), c.trophyChance());
                else {
                    if (f.rate == null || !condition(f.cond, c)) continue;
                    chance = FishMath.trophyChance(f.rate, c.trophyChance());
                }
                if (chance > 0 && r.nextDouble() < chance) return e.getKey();
            }
        }
        return null;
    }

    static boolean condition(String cond, Context c) {
        if (cond == null || cond.equals("any")) return true;
        if (cond.startsWith("bait:")) return c.bait() != null && c.bait().startsWith(cond.substring(5));
        if (cond.startsWith("zone:")) return zone(cond.substring(5), c);
        switch (cond) {
            // research 07 3.7: "within 4 blocks of Sulphur Ore" - the angler or the bobber may be the one near it
            case "sulphur": return nearBlock(c.player().getLocation(), 4, Material.SPONGE, Material.WET_SPONGE)
                    || nearBlock(c.bobber(), 4, Material.SPONGE, Material.WET_SPONGE);
            case "geyser": {
                // research 07 3.7: bobber within 2 blocks of a geyser. Geysers are the island's geyser anchors or,
                // when it has none, the ones Geysers found in the volcano lava; none found yet = the volcano rule.
                List<Location> geysers = Geysers.sites(c.island());
                if (geysers.isEmpty()) return zone("volcano", c);
                return Geysers.near(geysers, c.bobber(), FishDials.GEYSER_RADIUS.get());
            }
            case "eruption": return zone("volcano", c) && gusherWindow();
            case "patience": return c.bobberAgeMs() >= slugfishWaitMs(c.player());
            case "height": return zone("volcano", c) && c.player().getLocation().getY() - c.bobber().getY() >= 8;
            case "vanille": return "STARTER_LAVA_ROD".equals(c.rodId()) && (c.rod() == null || c.rod().enchants.isEmpty());
            default: return false;
        }
    }

    /** Slugfish: the bobber must have been out 20 s, less the Slug pet's "Patience" percent. */
    static long slugfishWaitMs(Player p) { return FishMath.slugPatienceMs(20_000, FishEffects.slugPercent(p)); }

    /** Area that stands in for a missing "volcano" zone (the crimson module names its crater lava area this). */
    static final String VOLCANO_AREA = "gather.volcano";

    /**
     * A zone condition holds in that zone; on an island with no such zone it holds everywhere (graceful fallback).
     * The Crimson Isle has no zone named "volcano" but does have the crater's area, so the "volcano" fish
     * (Volcanic Stonefish, Flyfish, Gusher) are held to that area instead of biting island-wide.
     */
    static boolean zone(String keys, Context c) {
        if (PoolResolver.anyKeyword(List.of(keys.split("\\|")), c.zoneText())) return true;
        if (FishDials.TROPHY_STRICT_ZONES.get() && keys.contains("volcano") && !Fishing.islandHasZone(c.island(), keys)) {
            com.skyclone.api.world.gen.Box a = volcanoArea(c.island());
            if (a != null) return inArea(a, c.bobber());
        }
        return !FishDials.TROPHY_STRICT_ZONES.get() || !Fishing.islandHasZone(c.island(), keys);
    }

    static com.skyclone.api.world.gen.Box volcanoArea(String island) {
        try { return Sky.worlds().area(island, VOLCANO_AREA); } catch (RuntimeException e) { return null; }
    }

    /** Inside the area's columns, from 8 below its floor up to anywhere above it (a Flyfish angler stands high). */
    static boolean inArea(com.skyclone.api.world.gen.Box a, Location l) {
        return l != null && a.containsXZ(l.getX(), l.getZ()) && l.getY() >= a.minY() - 8;
    }

    static boolean nearBlock(Location at, int r, Material... types) {
        World w = at.getWorld();
        int bx = at.getBlockX(), by = at.getBlockY(), bz = at.getBlockZ();
        for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
            Material m = w.getBlockAt(bx + x, by + y, bz + z).getType();
            for (Material t : types) if (m == t) return true;
        }
        return false;
    }

    // ---- awarding -------------------------------------------------------------------------------

    /** Roll a tier (pity, Charm) and give the fish. Returns the tier. */
    static String award(Player p, String stem, double charm) {
        FishData d = Fishing.data(p);
        String tier = FishMath.trophyTier(() -> ThreadLocalRandom.current().nextDouble(), d.sinceGold, d.sinceDiamond, charm,
                FishEffects.petOdds(p, "spinosaurus_pursuit"), Fishing.CAT.trophy.tiers.chances, Fishing.CAT.trophy.tiers.pity);
        give(p, stem, tier);
        return tier;
    }

    /** Credit + item + XP + messages for one trophy fish of a known tier. */
    static void give(Player p, String stem, String tier) {
        TrophyFish f = Fishing.CAT.trophy.fish.get(stem);
        if (f == null) return;
        FishData d = Fishing.data(p);
        int titleBefore = title(d);
        boolean firstTier = d.trophy(stem, tier) == 0;
        d.trophies.computeIfAbsent(stem, k -> new HashMap<>()).merge(tier, 1, Integer::sum);
        d.trophyTotal++;
        int ti = FishingCatalog.tierIndex(tier);
        if (ti >= 3) { d.sinceDiamond = 0; d.sinceGold = 0; } else d.sinceDiamond++;
        if (ti == 2) d.sinceGold = 0; else if (ti < 2) d.sinceGold++;
        String item = stem + "_" + tier;
        if (Sky.items().exists(item)) Sky.loot().give(p, item, 1, LootSource.FISHING);
        double xp = Fishing.CAT.trophy.xp.getOrDefault(f.rarity, 100.0) * Fishing.CAT.trophy.tiers.xpMult.getOrDefault(tier, 1.0);
        Fishing.addXp(p, xp, "trophy:" + stem);
        if (Fishing.setting(p, "fishing.trophy_alerts", true)) {
            Msg.send(p, "&6&lTROPHY FISH! &7You caught a " + Fishing.rarityColor(f.rarity) + f.name + " " + tierLabel(tier) + "&7."
                    + (firstTier ? " &a&lNEW!" : ""));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f + ti * 0.1f);
        if (firstTier) {
            int sb = switch (tier) { case "SILVER" -> 4; case "GOLD" -> 8; case "DIAMOND" -> 16; default -> 2; };   // TUNE
            Sky.service(Features.SkyLevel.class).award(p, "trophy:" + stem + ":" + tier, sb, "Trophy Fish: " + f.name + " " + tier);
        }
        int titleAfter = title(d);
        if (titleAfter > titleBefore) {
            String name = titleName(titleAfter);
            Msg.box(p, "&6&lTROPHY FISHER TITLE &e" + name, List.of("&7You now hold the title of &6" + name + "&7.",
                    titleAfter == 2 ? "&7Something huge now stirs in the Crimson lava..." : "&7The Trophy Keeper has new wares for you."));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    static void awardCorrupted(Player p) {
        if (!Fishing.CAT.trophy.fish.containsKey("OBFUSCATED_FISH_1")) return;
        Msg.send(p, "&5The corrupted creature left something strange behind...");
        award(p, "OBFUSCATED_FISH_1", 0);
    }

    static String tierLabel(String tier) {
        return switch (tier) {
            case "DIAMOND" -> "&b&lDIAMOND";
            case "GOLD" -> "&6&lGOLD";
            case "SILVER" -> "&7&lSILVER";
            default -> "&8&lBRONZE";
        };
    }

    // ---- titles ---------------------------------------------------------------------------------

    /** 0 = none, 1 Novice (every fish Bronze+), 2 Adept (Silver+), 3 Expert (Gold+), 4 Master (Diamond). */
    static int title(FishData d) {
        int best = 0;
        for (FishingCatalog.Title t : Fishing.CAT.trophy.titles) {
            int need = FishingCatalog.tierIndex(t.tier);
            boolean all = !Fishing.CAT.trophy.fish.isEmpty();
            for (String stem : Fishing.CAT.trophy.fish.keySet()) if (d.bestTier(stem) < need) { all = false; break; }
            if (all) best = Math.max(best, t.level);
        }
        return best;
    }

    static String titleName(int level) {
        for (FishingCatalog.Title t : Fishing.CAT.trophy.titles) if (t.level == level) return t.name;
        return "Trophy Fisher";
    }

    /** Fish still missing for the next title (for the menu). */
    static List<String> missingFor(FishData d, int level) {
        List<String> out = new ArrayList<>();
        String tier = null;
        for (FishingCatalog.Title t : Fishing.CAT.trophy.titles) if (t.level == level) tier = t.tier;
        if (tier == null) return out;
        int need = FishingCatalog.tierIndex(tier);
        for (Map.Entry<String, TrophyFish> e : Fishing.CAT.trophy.fish.entrySet())
            if (d.bestTier(e.getKey()) < need) out.add(e.getValue().name);
        return out;
    }

    // ---- filleting ------------------------------------------------------------------------------

    /** Magmafish one trophy item is worth, 0 if it is not a trophy fish. */
    static int filletValue(String itemId) {
        if (itemId == null) return 0;
        for (String tier : FishingCatalog.TIERS) {
            if (!itemId.endsWith("_" + tier)) continue;
            String stem = itemId.substring(0, itemId.length() - tier.length() - 1);
            TrophyFish f = Fishing.CAT.trophy.fish.get(stem);
            return f == null ? 0 : FishMath.fillet(f.fillet, tier);
        }
        return 0;
    }

    // ---- requirement resolver "trophy" -----------------------------------------------------------

    /**
     * {@code trophy:title:<1-4>} (a trophy fisher title), {@code trophy:<FISH>:<tier>} (that fish at
     * that tier or better), {@code trophy:count:<n>} (n trophy fish caught in total).
     */
    static final class Resolver implements Requirements.Resolver {
        @Override public boolean check(Player p, String[] parts) {
            if (parts.length < 3 || !Fishing.ready(p)) return false;
            FishData d = Fishing.data(p);
            String what = parts[1].toUpperCase(Locale.ROOT);
            try {
                if (what.equals("TITLE")) return title(d) >= Integer.parseInt(parts[2]);
                if (what.equals("COUNT")) return d.trophyTotal >= Long.parseLong(parts[2]);
            } catch (NumberFormatException e) { return false; }
            int need = FishingCatalog.tierIndex(parts[2]);
            return need >= 0 && d.bestTier(what) >= need;
        }

        @Override public String describe(String[] parts) {
            if (parts.length < 3) return "&cTrophy Fishing";
            String what = parts[1].toUpperCase(Locale.ROOT);
            if (what.equals("TITLE")) {
                try { return "&6" + titleName(Integer.parseInt(parts[2])); } catch (NumberFormatException e) { return "&6Trophy Fisher title"; }
            }
            if (what.equals("COUNT")) return "&6" + parts[2] + " Trophy Fish caught";
            TrophyFish f = Fishing.CAT.trophy.fish.get(what);
            return "&6" + (f == null ? what : f.name) + " " + parts[2].toUpperCase(Locale.ROOT) + " &6trophy";
        }
    }

    static int titleOf(Player p) { return Fishing.ready(p) ? title(Fishing.data(p)) : 0; }
}
