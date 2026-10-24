package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runtime state and helpers shared by every garden class (tables, dials, data access, items). */
final class G {

    private G() {}

    static final String ISLAND = GardenGenerator.ISLAND;
    static GardenTables T;

    // ---- dials ----------------------------------------------------------------------------------
    static Dials.Dial<String> requirement, pestPerk, visitorPerk;
    static Dials.Dial<Integer> freePlots, visitorMax, pestMaxLive, composterMaxStored, chipMax, presetBlocksPerTick;
    static Dials.Dial<Double> regrowSeconds, visitorMinutes, visitorOfflineMinutes, visitorCopperMult, visitorXpMult,
            pestChance, pestCooldown, pestMinCooldown, pestMaxCooldown, pestFarmingXp, pestBits, pestHealthMult,
            pestOfflinePerHour, pestCropBias, composterSpeedMult, greenhouseSpeed, presetCost, milestoneSbXp,
            plotFortune, plotSbXp, levelSbXp, vacuumCone, sprayWeight, clearRareChance, clearFarmingXp;
    static Dials.Dial<Integer> clearRewardCap;
    // wave 5: other modules' items / pets / reforges in the Garden
    static Dials.Dial<Double> filterAutumnPct, filterWinterPct, eradicatorPerPiece, analysisCopper, squeakyFallback;
    // lanes 9 / 12 / 13 (2026-10-06): pest economy, Sowdust, chips
    static Dials.Dial<Double> sowdustMult, chipSowdustMult, pestCropMult, greenhouseSquashMult;
    static Dials.Dial<Boolean> nightPests;
    static Dials.Dial<Double> pesthunterPerPest, pesthunterCap, pesthunterMinutes, pesthunterPerkMinutes;
    static Dials.Dial<Boolean> protectYoung, allowRemoteOffers, presetsEnabled;

    static void dials() {
        Dials d = Sky.dials();
        requirement = d.text("garden.requirement", "sblevel:5", "Requirement to enter the Garden (research 08 1.5: SkyBlock Level 5)");
        freePlots = d.integer("garden.free-starter-plots", 1, "Plots given free on the first visit (TUNE: so a new garden can farm at once)");
        regrowSeconds = d.number("garden.regrow-seconds", 45, "Seconds a harvested Garden crop takes to regrow at Crop Growth 0 (TUNE)");
        protectYoung = d.flag("garden.protect-young-crops", true, "Breaking a crop that is not grown yet does nothing (instead of destroying it)");
        visitorMinutes = d.number("garden.visitor-minutes", 15, "Minutes between visitors while a member is online (research 08 1.10)");
        visitorOfflineMinutes = d.number("garden.visitor-offline-minutes", 60, "Minutes between visitors while everyone is offline (research 08 1.10 (?))");
        visitorMax = d.integer("garden.visitor-max", 5, "Visitors that can wait in the Garden at once (research 08 1.10)");
        visitorCopperMult = d.number("garden.visitor-copper-mult", 1.0, "Multiplier on visitor Copper rewards");
        visitorXpMult = d.number("garden.visitor-xp-mult", 1.0, "Multiplier on visitor Garden XP and Farming XP rewards");
        allowRemoteOffers = d.flag("garden.remote-offers", true, "Visitor offers can be answered from the Desk (not only by walking to the visitor)");
        pestChance = d.number("garden.pest-chance", 0.002, "Chance per Garden crop broken that pests appear (research 08 1.12: 1 in 500)");
        pestCooldown = d.number("garden.pest-cooldown-seconds", 300, "Pest cooldown after a spawn (research 08 1.12: 5 minutes)");
        pestMinCooldown = d.number("garden.pest-min-cooldown", 130, "Shortest pest cooldown, seconds (research 08 1.12: 2m10s)");
        pestMaxCooldown = d.number("garden.pest-max-cooldown", 1200, "Longest pest cooldown, seconds (research 08 1.12: 20m)");
        pestFarmingXp = d.number("garden.pest-farming-xp", 1500, "Farming XP per pest killed (research 08 1.12)");
        pestBits = d.number("garden.pest-bits", 5, "Bits per pest killed (research 08 1.12)");
        pestHealthMult = d.number("garden.pest-health-mult", 1.0, "Multiplier on pest health (600 base)");
        pestOfflinePerHour = d.number("garden.pest-offline-per-hour", 1, "Pests that appear per hour nobody is online (research 08 1.12)");
        pestMaxLive = d.integer("garden.pest-max", 16, "Most pests a Garden can hold at once (TUNE)");
        pestCropBias = d.number("garden.pest-crop-weight", 0,
                "Extra spawn weight for the pest of the crop being broken. 0 = the reference: the spray decides which pests spawn, not the crop (audit G4)");
        sprayWeight = d.number("garden.spray-weight", 1000, "Extra spawn weight for pests a sprayed plot attracts (research 08 1.12)");
        composterSpeedMult = d.number("garden.composter-speed-mult", 1.0, "Global multiplier on composter speed");
        composterMaxStored = d.integer("garden.composter-max-stored", 1000, "Compost the composter can hold before it must be collected (TUNE)");
        greenhouseSpeed = d.number("garden.greenhouse-speed", 4.0,
                "Greenhouse growth speed multiplier. TUNE: the research stage is 4 h; 4.0 makes it 1 h so a fresh server sees harvests the same day");
        presetsEnabled = d.flag("garden.presets", true, "Plot presets (auto-laid farm layouts) are available");
        presetCost = d.number("garden.preset-cost", 5000, "Coins to lay a farm preset on a plot (clearing a plot is free)");
        presetBlocksPerTick = d.integer("garden.preset-blocks-per-tick", 4000, "Blocks a preset or barn skin may change per tick");
        milestoneSbXp = d.number("garden.milestone-sb-xp", 1, "SkyBlock XP per crop milestone tier (TUNE)");
        plotFortune = d.number("garden.plot-fortune", 3, "Farming Fortune per plot owned (research 08 1.3)");
        plotSbXp = d.number("garden.plot-sb-xp", 5, "SkyBlock XP per plot owned (research 08 1.5)");
        levelSbXp = d.number("garden.level-sb-xp", 10, "SkyBlock XP per garden level (research 08 1.5)");
        vacuumCone = d.number("garden.vacuum-cone", 0.80, "How closely you must aim a vacuum at a pest (dot product, 1 = exactly)");
        clearRareChance = d.number("garden.clear-rare-chance", 0.0001,
                "Chance per overgrowth block cleared on a plot to find Overgrown Grass (research 08 1.3 '(?)'; TUNE)");
        clearFarmingXp = d.number("garden.clear-farming-xp", 1, "Farming XP per overgrowth block cleared on a plot (TUNE)");
        clearRewardCap = d.integer("garden.clear-reward-cap", 2500,
                "Overgrowth blocks per plot that pay clearing rewards; past it clearing pays nothing (stops a place-and-break farm)");
        chipMax = d.integer("garden.chip-max-level", 20,
                "Ceiling on every garden chip's level; the chip's rarity caps it first (Rare 10, Epic 15, Legendary 20, wiki)");
        sowdustMult = d.number("garden.sowdust-mult", 1.0, "Multiplier on all Sowdust earned (1 = the reference formula)");
        chipSowdustMult = d.number("garden.chip-sowdust-cost-mult", 1.0, "Multiplier on the Sowdust a chip level costs (1 = the wiki table, 25M to max)");
        pestCropMult = d.number("garden.pest-crop-mult", 1.0,
                "Multiplier on a pest's guaranteed enchanted-crop drop (base + Farming Fortune / scale; 1 = the reference)");
        greenhouseSquashMult = d.number("garden.greenhouse-squash-mult", 1.0,
                "Multiplier on Cropie armor's greenhouse Squash chance (4/8/12% at 2/3/4 pieces; 1 = the reference)");
        nightPests = d.flag("garden.night-only-pests", true, "Night-only pests (the Firefly) only spawn at SkyBlock night");
        pestPerk = d.text("garden.pest-perk", "finnegan.pest_eradicator", "Mayor perk id that cuts the pest cooldown by 20%");
        visitorPerk = d.text("garden.visitor-perk", "finnegan.blooming_business", "Mayor perk id that brings visitors 25% sooner");
        filterAutumnPct = d.number("garden.filter-autumn-pct", 15, "Atmospheric Filter in Autumn: % more often pests show up on your Garden (reference lore)");
        filterWinterPct = d.number("garden.filter-winter-pct", 5, "Atmospheric Filter in Winter: % more Copper from Garden visitors (reference lore)");
        eradicatorPerPiece = d.number("garden.eradicator-ff-per-piece", 10,
                "Pesthunter's equipment 'Eradicator': Farming Fortune against pests per piece worn, 0-4 pieces (TUNE: reference shows the tiers, not the numbers)");
        analysisCopper = d.number("garden.analysis-copper", 1.0,
                "Multiplier on first-time Crop Analyzer Copper (250 a Common mutation, 750 Soggybud; the Rose Dragon's Garden Grace adds to it)");
        pesthunterPerPest = d.number("garden.pesthunter-ff-per-pest", 5,
                "Pestwarden's Bonus: Farming Fortune per Pest handed in (wiki Pesthunter Phillip: 5)");
        pesthunterCap = d.number("garden.pesthunter-ff-cap", 200, "Pestwarden's Bonus: most Farming Fortune one hand-in gives (wiki: 200, at 40 Pests)");
        pesthunterMinutes = d.number("garden.pesthunter-minutes", 30, "Pestwarden's Bonus: minutes it lasts (wiki: 30; the Pest Eradicator perk makes it 60)");
        pesthunterPerkMinutes = d.number("garden.pesthunter-perk-minutes", 60, "Pestwarden's Bonus: minutes it lasts while the pest perk is active (research 05: 60 min)");
        squeakyFallback = d.number("garden.squeaky-pct", 2.5,
                "Pest cooldown % cut per worn Squeaky piece (wiki: 2.5% each, newer than NEU's 1%; with a Pest Vest and 3 Pesthunter pieces that reaches the 130 s floor)");
    }

    // ---- tables -----------------------------------------------------------------------------------

    static GardenTables load() {
        return GardenTables.load(name -> {
            InputStream in = Sky.plugin().getResource("data/garden/" + name);
            if (in == null) throw new IllegalStateException("data/garden/" + name + " missing from the jar");
            return new InputStreamReader(in, StandardCharsets.UTF_8);
        });
    }

    // ---- data -------------------------------------------------------------------------------------

    static boolean ready(Player p) { return p != null && Sky.profiles().isReady(p); }

    static GardenData data(Player p) { return Sky.profiles().shared(p, "garden", GardenData.class); }

    static GardenMember member(Player p) { return Sky.profiles().member(p, "garden", GardenMember.class); }

    static String key(Player p) {
        String k = Sky.profiles().profileId(p);
        return k == null || k.isBlank() ? p.getUniqueId().toString() : k;
    }

    static int level(GardenData d) { return GardenMath.level(d.xp, T.garden.levels); }

    static int maxLevel() { return T.garden.levels.length; }

    static int cropGrowth(GardenData d) {
        // + the Crop Analyzer's DNA milestones: +10 Crop Growth each (garden_max.md 6.2)
        return GardenMath.cropGrowth(level(d)) + (int) Math.round(Greenhouse.analyzerLevel(d) * T.greenhouse.analyzerReward.cropGrowth);
    }

    /**
     * Sum of chip effects of one kind: level x the per-level value at the chip's rarity (Rare / Epic / Legendary,
     * set by how many chips were redeemed). Kinds (the reference chips' own lore): ff, wisdom, overbloom,
     * pest_chance, greenhouse (%), analyzer_copper (%), visitor_harvest (%), ff_buffs (%), contest_fortune, tool_xp (%).
     */
    static double chipValue(GardenData d, String kind) {
        double v = 0;
        for (Map.Entry<String, GardenTables.Chip> e : T.garden.chips.entrySet())
            if (e.getValue().kind.equals(kind)) v += Chips.level(d, e.getKey()) * e.getValue().perAt(Chips.rarity(d, e.getKey()));
        return v;
    }

    // ---- where things are -------------------------------------------------------------------------

    static World world() { return Sky.worlds().world(ISLAND); }

    static boolean onGarden(Player p) { return ISLAND.equals(Sky.worlds().islandOf(p.getLocation())); }

    static Location origin(String key) { return Sky.worlds().instanceOrigin(ISLAND, key); }

    /** Template x/z of a location relative to {@code key}'s instance, or null when it is not inside it. */
    static int[] template(String key, Location l) {
        if (l == null || l.getWorld() == null || !ISLAND.equals(Sky.worlds().islandOf(l.getWorld()))) return null;
        Location o = origin(key);
        if (o == null) return null;
        int tx = l.getBlockX() - o.getBlockX(), tz = l.getBlockZ() - o.getBlockZ();
        if (tx < PlotGrid.MIN - 8 || tx > PlotGrid.MAX + 8 || tz < PlotGrid.MIN - 8 || tz > PlotGrid.MAX + 8) return null;
        return new int[]{tx, l.getBlockY(), tz};
    }

    /** Is the player standing in their OWN profile's garden? */
    static boolean inOwnGarden(Player p) { return template(key(p), p.getLocation()) != null; }

    /** Plot id at template x/z (only inside a plot's buildable part), or null. */
    static String plotAt(int tx, int tz) {
        int col = PlotGrid.colOf(tx), row = PlotGrid.rowOf(tz);
        if (col < 0 || row < 0 || PlotGrid.isBarn(col, row)) return null;
        if (!PlotGrid.insideInner(tx, tz, col, row)) return null;
        GardenTables.Plot pl = T.plotAt(col, row);
        return pl == null ? null : pl.id;
    }

    static Location worldAt(String key, double tx, double ty, double tz) {
        Location o = origin(key);
        return o == null ? null : new Location(o.getWorld(), o.getX() + tx, ty, o.getZ() + tz);
    }

    static Location anchor(String key, String anchor) { return Sky.worlds().instanceAnchor(ISLAND, key, anchor); }

    static String plotName(GardenData d, String plotId) {
        GardenTables.Plot pl = T.plot(plotId);
        GardenData.PlotState s = d.plots.get(plotId);
        if (s != null && s.name != null && !s.name.isBlank()) return s.name;
        return "Plot - " + (pl == null ? "?" : pl.number);
    }

    /** Online members of a profile (co-op) that are ready. */
    static List<Player> members(String key) {
        List<Player> out = new ArrayList<>();
        for (UUID u : Sky.profiles().members(key)) {
            Player m = Bukkit.getPlayer(u);
            if (m != null && ready(m)) out.add(m);
        }
        if (out.isEmpty()) for (Player m : Bukkit.getOnlinePlayers()) if (ready(m) && key.equals(key(m))) out.add(m);
        return out;
    }

    // ---- items ----------------------------------------------------------------------------------

    static boolean exists(String id) { return id != null && Sky.items().exists(id); }

    static String itemName(String id) {
        ItemDef d = id == null ? null : Sky.items().def(id);
        if (d == null) return Text.titleCase(id == null ? "?" : id.replace(':', ' '));
        return d.rarity.code() + Text.strip(d.name);
    }

    static ItemStack icon(String id, int amount) {
        try {
            if (exists(id)) return Sky.items().create(id, Math.max(1, Math.min(64, amount)));
        } catch (Throwable ignored) { }
        return new ItemStack(Material.PAPER);
    }

    /** Count across inventory and sacks. */
    static long count(Player p, String id) {
        long n = Sky.items().count(p, id);
        try { n += Sky.service(Features.Sacks.class).count(p, id); } catch (Throwable ignored) { }
        return n;
    }

    static boolean has(Player p, Map<String, ? extends Number> need) {
        for (Map.Entry<String, ? extends Number> e : need.entrySet()) if (count(p, e.getKey()) < e.getValue().longValue()) return false;
        return true;
    }

    /** Take items (inventory first, then sacks). Check {@link #has} first; returns false if it could not take all. */
    static boolean take(Player p, Map<String, ? extends Number> need) {
        if (!has(p, need)) return false;
        for (Map.Entry<String, ? extends Number> e : need.entrySet()) {
            long left = e.getValue().longValue();
            while (left > 0) {
                int got = Sky.items().remove(p, e.getKey(), (int) Math.min(Integer.MAX_VALUE, left));
                if (got <= 0) break;
                left -= got;
            }
            if (left > 0) {
                try { left -= Sky.service(Features.Sacks.class).remove(p, e.getKey(), (int) Math.min(Integer.MAX_VALUE, left)); }
                catch (Throwable ignored) { }
            }
            if (left > 0) { Sky.log().warning("[garden] could not take " + left + " " + e.getKey() + " from " + p.getName()); return false; }
        }
        return true;
    }

    static Map<String, Long> one(String id, long amount) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(id, amount);
        return m;
    }

    /** Give n of an item in stack-sized pieces. Unknown ids are logged and skipped. */
    static void give(Player p, String id, long n, LootSource src) {
        if (n <= 0) return;
        if (!exists(id)) { Sky.log().warning("[garden] tried to give unknown item " + id); return; }
        int max = Math.max(1, Sky.items().def(id).effectiveMaxStack());
        while (n > 0) {
            int k = (int) Math.min(max, n);
            Sky.loot().give(p, id, k, src);
            n -= k;
        }
    }

    // ---- progression ------------------------------------------------------------------------------

    static void award(Player p, String key, int xp, String reason) {
        if (xp <= 0) return;
        try { Sky.service(Features.SkyLevel.class).award(p, key, xp, reason); } catch (Throwable ignored) { }
    }

    static void farmingXp(Player p, double xp, String source) {
        if (xp <= 0) return;
        try { Sky.service(com.skyclone.api.skill.SkillService.class).addXp(p, com.skyclone.api.skill.SkillType.FARMING, xp, source); }
        catch (Throwable ignored) { }
    }

    static boolean perk(String id) {
        try { return id != null && !id.isBlank() && Sky.service(Features.Mayors.class).perkActive(id); } catch (Throwable t) { return false; }
    }

    /** Add Garden XP and announce level-ups (rewards: research 08 1.5). */
    static void gardenXp(Player p, GardenData d, double amount, String why) {
        if (amount <= 0) return;
        int before = level(d);
        d.xp += amount;
        int after = level(d);
        if (after > before) levelUps(p, d, before, after);
    }

    static void levelUps(Player p, GardenData d, int from, int to) {
        for (int l = Math.max(from + 1, d.levelPaid + 1); l <= to; l++) {
            List<String> body = new ArrayList<>();
            body.add("&a&lREWARDS");
            for (GardenTables.Crop c : T.crops()) if (c.level == l) body.add("  &8+&e" + c.name + " &7can now be grown here");
            body.add("  &8+&a10 Crop Growth");
            body.add("  &8+&b" + (int) Math.round(levelSbXp.get()) + " SkyBlock XP");
            int newVisitors = 0;
            for (GardenTables.Visitor v : T.allVisitors()) if (v.level == l) newVisitors++;
            if (newVisitors > 0) body.add("  &8+&a" + newVisitors + " &7new visitors may drop by");
            for (int i = 0; i < T.garden.cropUpgrades.levels.length; i++)
                if (T.garden.cropUpgrades.levels[i] == l) body.add("  &8+&6Crop Upgrade " + GardenMath.roman(i + 1));
            for (GardenTables.CompUpgrade u : T.garden.composter.upgrades) if (u.level == l) body.add("  &8+&2" + u.name + " &7upgrades");
            for (GardenTables.PlotGroup g : T.garden.plotGroups) if (g.level == l && l > 1) body.add("  &8+&a" + g.name + " &7plots can be bought");
            if (l == 5) body.add("  &8+&cPests &7start turning up while you farm");
            if (l == 7) body.add("  &8+&bThe Greenhouse &7(bring a blueprint)");
            for (GardenTables.BarnSkin s : T.garden.barnSkins) if (("level:" + l).equals(s.source)) body.add("  &8+&dBarn Skin: " + s.name);
            for (Player m : members(key(p))) {
                Msg.box(m, "&2&lGARDEN LEVEL UP &aGarden " + GardenMath.roman(l - 1) + " &8➜ &a" + GardenMath.roman(l), body);
                Msg.sound(m, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
                award(m, "garden:level:" + l, (int) Math.round(levelSbXp.get()), "Garden Level " + GardenMath.roman(l));
            }
            d.levelPaid = l;
        }
        for (Player m : members(key(p))) Sky.sidebar().refresh(m);
    }

    /** Re-grant keyed SkyBlock XP awards (cheap, idempotent): used on ProfileReady so co-op members catch up. */
    static void syncAwards(Player p) {
        GardenData d = data(p);
        int lvl = level(d);
        for (int l = 2; l <= lvl; l++) award(p, "garden:level:" + l, (int) Math.round(levelSbXp.get()), "Garden Level " + GardenMath.roman(l));
        for (String plot : d.plots.keySet()) award(p, "plot:" + plot, (int) Math.round(plotSbXp.get()), "Garden plot");
        for (Map.Entry<String, Integer> e : d.upgrades.entrySet())
            for (int t = 1; t <= e.getValue(); t++) award(p, "crop:upgrade:" + e.getKey() + ":" + t, 1, "Crop Upgrade");
        for (Map.Entry<String, Integer> e : d.milestonePaid.entrySet())
            for (int t = 1; t <= e.getValue(); t++) award(p, "crop:milestone:" + e.getKey() + ":" + t, (int) Math.round(milestoneSbXp.get()), "Crop Milestone");
    }

    static String copperText(long c) { return "&c" + Text.commasInt(c) + " Copper"; }

    static void sound(Player p, Sound s, float pitch) { Msg.sound(p, s, pitch); }
}
