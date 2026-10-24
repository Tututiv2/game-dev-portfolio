package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.fishing.FishingCatalog.Pool;
import com.skyclone.mod.fishing.FishingCatalog.PoolCreature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Live checks the integrator runs with {@code /sb selftest fishing}. Each returns null on pass. */
final class FishSelfTests {

    private FishSelfTests() {}

    static void register() {
        Sky.commands().selfTest("fishing.data-loaded", FishSelfTests::dataLoaded);
        Sky.commands().selfTest("fishing.pool-mobs-exist", FishSelfTests::poolMobs);
        Sky.commands().selfTest("fishing.items-exist", FishSelfTests::items);
        Sky.commands().selfTest("fishing.drops-exist", FishSelfTests::drops);
        Sky.commands().selfTest("fishing.recipes-valid", FishSelfTests::recipes);
        Sky.commands().selfTest("fishing.formula-wait", FishSelfTests::formulaWait);
        Sky.commands().selfTest("fishing.formula-pool-odds", FishSelfTests::formulaPool);
        Sky.commands().selfTest("fishing.formula-trophy", FishSelfTests::formulaTrophy);
        Sky.commands().selfTest("fishing.bayou-layout", FishSelfTests::bayou);
        Sky.commands().selfTest("fishing.npc-anchors", FishSelfTests::npcAnchors);
        Sky.commands().selfTest("fishing.menus-build", FishSelfTests::menusBuild);
        Sky.commands().selfTest("fishing.shops-and-treasure", FishSelfTests::shopsAndTreasure);
        Sky.commands().selfTest("fishing.witch-potions-neutralised", FishSelfTests::witchPotions);
        Sky.commands().selfTest("fishing.gusher-clock", FishSelfTests::gusherClock);
    }

    /** The Gusher keys off the crimson eruption event when it exists, and the clock hangs together. */
    static String gusherClock() {
        Trophies.EruptionClock c = Trophies.eruptionClock();
        String id = FishDials.ERUPTION_EVENT.get();
        boolean registered = false;
        for (var e : Sky.calendar().events()) if (e.id != null && e.id.equals(id) && e.intervalMinutes > 0) registered = true;
        if (registered && c.own()) return "eruption event '" + id + "' is registered but the Gusher uses its own cycle";
        if (c.cycle() <= 0) return "cycle " + c.cycle();
        if (c.since() < 0 || c.since() > c.cycle()) return "since " + c.since() + " outside 0.." + c.cycle();
        if (Math.abs(c.since() + c.untilNext() - c.cycle()) > 2_000L)
            return "since " + c.since() + " + until " + c.untilNext() + " != cycle " + c.cycle();
        long s = Trophies.gusherSeconds();
        if (s <= 0 || s > c.cycle() / 1000L + 3600L) return "gusherSeconds " + s;
        return null;
    }

    static String dataLoaded() {
        FishingCatalog c = Fishing.CAT;
        if (c.pools.size() < 19) return "only " + c.pools.size() + " pools loaded (expected 19)";
        if (c.trophy.fish.size() != 18) return c.trophy.fish.size() + " trophy fish (expected 18)";
        if (c.parts.size() != 18) return c.parts.size() + " rod parts (expected 18)";
        if (c.baits.size() < 20) return c.baits.size() + " baits (expected >= 20)";
        if (c.treasure.water.size() != 3) return "treasure table needs good/great/outstanding";
        return null;
    }

    static String poolMobs() {
        List<String> bad = new ArrayList<>();
        int n = 0;
        for (Pool p : Fishing.CAT.pools) for (PoolCreature c : p.creatures) {
            n++;
            MobDef d = Sky.mobs().def(c.mob);
            if (d == null) bad.add(c.mob);
            else if (!d.has("sea_creature")) bad.add(c.mob + "(no sea_creature flag)");
        }
        if (n < 80) return "only " + n + " pool entries";
        return bad.isEmpty() ? null : "pool creatures missing: " + bad;
    }

    static String items() {
        List<String> bad = new ArrayList<>();
        for (String id : Fishing.CAT.rods.keySet()) if (!Sky.items().exists(id)) bad.add(id);
        for (String id : Fishing.CAT.baits.keySet()) if (!Sky.items().exists(id)) bad.add(id);
        for (String id : Fishing.CAT.parts.keySet()) if (!Sky.items().exists(id)) bad.add(id);
        for (String stem : Fishing.CAT.trophy.fish.keySet())
            for (String t : FishingCatalog.TIERS) if (!Sky.items().exists(stem + "_" + t)) bad.add(stem + "_" + t);
        for (FishingCatalog.Catch c : Fishing.CAT.fish) if (!Sky.items().exists(c.item)) bad.add(c.item);
        for (FishingCatalog.Catch c : Fishing.CAT.lavaFish) if (!Sky.items().exists(c.item)) bad.add(c.item);
        if (!Sky.items().exists("MAGMA_FISH")) bad.add("MAGMA_FISH");
        return bad.isEmpty() ? null : "missing item ids: " + bad;
    }

    /** Every drop left on a sea creature exists, and none of the drops this module OWNS was pruned. */
    static String drops() {
        List<String> bad = new ArrayList<>();
        for (Pool p : Fishing.CAT.pools) for (PoolCreature c : p.creatures) {
            MobDef d = Sky.mobs().def(c.mob);
            if (d == null) continue;
            for (DropDef dr : d.drops) if (!Sky.items().exists(dr.item)) bad.add(c.mob + ":" + dr.item);
        }
        List<String> ownLost = new ArrayList<>();
        for (String id : FishingModule.PRUNED) if (FishingModule.OWN_ITEMS.contains(id)) ownLost.add(id);
        if (!ownLost.isEmpty()) return "own drop items were not registered: " + ownLost;
        return bad.isEmpty() ? null : "drops with unknown items: " + bad;
    }

    static String recipes() {
        List<String> bad = new ArrayList<>();
        for (Recipe r : Sky.recipes().all()) {
            if (r.id == null || !r.id.startsWith("fishing:")) continue;
            if (!Sky.items().exists(r.result)) bad.add(r.id + "->" + r.result);
        }
        if (FishingModule.RECIPES_REGISTERED < 20) return "only " + FishingModule.RECIPES_REGISTERED + " fishing recipes registered (skipped: " + FishingModule.RECIPES_SKIPPED + ")";
        return bad.isEmpty() ? null : "recipes with unknown results: " + bad;
    }

    /** research 07 3.2: 150 Fishing Speed at a cap-300 spot halves a 5-10 s wait to 2.5-5 s. */
    static String formulaWait() {
        if (FishMath.waitTicks(200, 150, 300, 0) != 100) return "200 ticks @150/300 should be 100, got " + FishMath.waitTicks(200, 150, 300, 0);
        if (FishMath.waitTicks(400, 150, 300, 0) != 200) return "400 ticks @150/300 should be 200";
        if (FishMath.waitTicks(400, 300, 300, 1) != 1) return "FS at cap should wait the floor (1 tick)";
        if (FishMath.maxBaseTicks(30) != 280) return "Lure 30% should cut the longest wait to 280 ticks, got " + FishMath.maxBaseTicks(30);
        if (Fishing.CAT.fsCap("backwater_bayou", 300) != 200 || Fishing.CAT.fsCap("crimson_isle", 300) != 350) return "island FS caps wrong";
        if (Math.abs(FishMath.seaCreatureProbability(20, 4) - 0.05) > 1e-9) return "SCC 20 on a private island should be 5%";
        return null;
    }

    /** research 07 3.4.1: the classic ten water creatures sum to 4,296 weight; the Water Hydra is 18 of it (0.42%). */
    static String formulaPool() {
        Pool water = Fishing.CAT.pool("water");
        if (water == null) return "no water pool";
        String[] classic = {"sc_squid", "sc_sea_walker", "sc_sea_witch", "sc_sea_archer", "sc_rider_of_the_deep", "sc_catfish",
                "sc_sea_leech", "sc_guardian_defender", "sc_deep_sea_protector", "sc_water_hydra"};
        Map<String, Double> w = new HashMap<>();
        for (PoolCreature c : water.creatures) w.put(c.mob, c.weight);
        double sum = 0;
        for (String id : classic) sum += w.getOrDefault(id, 0.0);
        if (sum != 4296) return "classic water weights sum to " + sum + " (research: 4,296)";
        double hydra = w.getOrDefault("sc_water_hydra", 0.0) / sum;
        if (Math.abs(hydra - 0.0042) > 0.0001) return "Water Hydra share " + hydra + " (research: 0.42%)";
        Pool shark = Fishing.CAT.pool("shark");
        if (shark == null || Math.abs(shark.chance - 0.15) > 1e-9) return "shark replace chance should be 15%";
        return null;
    }

    /** research 07 3.7: pity Gold at 100, Diamond at 600; chance = base x (1 + TC/100). */
    static String formulaTrophy() {
        var t = Fishing.CAT.trophy.tiers;
        if (!"GOLD".equals(FishMath.trophyTier(() -> 0.99, 99, 0, 0, t.chances, t.pity))) return "100th catch should be a pity Gold";
        if (!"DIAMOND".equals(FishMath.trophyTier(() -> 0.99, 0, 599, 0, t.chances, t.pity))) return "600th catch should be a pity Diamond";
        if (!"BRONZE".equals(FishMath.trophyTier(() -> 0.99, 0, 0, 0, t.chances, t.pity))) return "a high roll should be Bronze";
        if (Math.abs(FishMath.trophyChance(25, 20) - 0.30) > 1e-9) return "Blobfish 25% with +20 Trophy Chance should be 30%";
        var gold = Fishing.CAT.trophy.fish.get("GOLDEN_FISH");
        if (gold == null || FishMath.fillet(gold.fillet, "DIAMOND") != 1300) return "Golden Fish Diamond should fillet into 1,300 Magmafish";
        return null;
    }

    static String bayou() {
        Layout l = Sky.worlds().layout("backwater_bayou");
        if (l == null) return "backwater_bayou has no layout yet (not generated)";
        List<String> missing = new ArrayList<>();
        for (String a : new String[]{"spawn", "npc.roddy", "npc.bait_shop", "npc.warden", "npc.scrapper"}) if (l.anchor(a) == null) missing.add(a);
        if (l.area("portal.hub") == null) missing.add("area portal.hub");
        int souls = 0, hot = 0;
        for (String k : l.anchors().keySet()) { if (k.startsWith("fairy_soul.")) souls++; if (k.startsWith("fishing.hotspot.")) hot++; }
        if (souls < 10) missing.add("fairy souls (" + souls + ")");
        if (hot < 2) missing.add("hotspot anchors (" + hot + ")");
        boolean marsh = false, lagoon = false;
        for (Layout.Zone z : l.zones()) { marsh |= z.id().contains("marsh"); lagoon |= z.id().contains("lagoon"); }
        if (!marsh) missing.add("marsh zone");
        if (!lagoon) missing.add("lagoon zone");
        return missing.isEmpty() ? null : "bayou layout missing: " + missing;
    }

    /** Everything the menus draw from data builds without throwing (no player needed). */
    static String menusBuild() {
        int lines = 0;
        for (String id : Fishing.CAT.baits.keySet()) lines += Baits.describe(id).size();
        for (String id : Fishing.CAT.parts.keySet()) lines += RodParts.describe(id).size();
        lines += FishMenus.eventLines().size();
        for (Hotspots.Buff b : Hotspots.Buff.values()) { if (b.label(2).isBlank() || b.shortLabel().isBlank()) return "empty hotspot label " + b; }
        for (FishingCatalog.Title t : Fishing.CAT.trophy.titles) if (Trophies.titleName(t.level).isBlank()) return "title " + t.level + " has no name";
        for (String stem : Fishing.CAT.trophy.fish.keySet()) {
            for (String tier : FishingCatalog.TIERS) Sky.items().create(stem + "_" + tier, 1);
            if (Trophies.filletValue(stem + "_BRONZE") <= 0) return stem + " fillets into nothing";
        }
        List<String> order = FishMenus.creatureOrder();
        if (order.size() < 84) return "Sea Creature Guide lists only " + order.size() + " creatures";
        for (String mob : order) if (FishMenus.levelOf(mob) < 0) return mob + " has no level";
        if (FishMenus.wherePools().size() != order.size()) return "a guide creature has no pool";
        return lines < 20 ? "bait/part descriptions are nearly empty (" + lines + " lines)" : null;
    }

    /** Every shop row and treasure row names a real item (rows that do not are hidden or skipped in game). */
    static String shopsAndTreasure() {
        List<String> bad = new ArrayList<>();
        for (List<FishingCatalog.ShopEntry> shop : List.of(Fishing.CAT.baitShop, Fishing.CAT.partShop, Fishing.CAT.keeperShop))
            for (FishingCatalog.ShopEntry e : shop) {
                if (!Sky.items().exists(e.item)) bad.add("shop:" + e.item);
                else Sky.items().create(e.item, 1);
                if (e.coins <= 0) bad.add("free:" + e.item);
            }
        FishingCatalog.Treasure t = Fishing.CAT.treasure;
        List<List<FishingCatalog.TreasureEntry>> tables = new ArrayList<>(t.water.values());
        tables.addAll(t.junk.values());
        for (Map<String, List<FishingCatalog.TreasureEntry>> isl : t.islands.values()) tables.addAll(isl.values());
        for (List<FishingCatalog.TreasureEntry> tab : tables)
            for (FishingCatalog.TreasureEntry e : tab)
                if (!"COINS".equals(e.item) && !Sky.items().exists(e.item)) bad.add("treasure:" + e.item);
        for (String q : new String[]{"good", "great", "outstanding"})
            if (t.water.getOrDefault(q, List.of()).isEmpty()) bad.add("empty water treasure table " + q);
        return bad.isEmpty() ? null : "unknown or broken rows (they are skipped in game): " + bad;
    }

    static String npcAnchors() {
        List<String> missing = new ArrayList<>();
        for (FishNpcs.Row r : FishNpcs.ROWS) {
            Layout l = Sky.worlds().layout(r.island);
            if (l == null) continue;   // that island is another module's and may not be built yet
            if (l.anchor(r.anchor) == null) missing.add(r.island + ":" + r.anchor);
        }
        return missing.isEmpty() ? null : "anchors missing (NPC stands near spawn instead): " + missing;
    }

    /**
     * Playtest "loops" (Sea Witch took a 706-Defense player to 2 HP, killed one): a harmful splash
     * from a sea witch must never land as a vanilla effect, whether she is alive (core charges one
     * pipeline hit instead) or already dead with the bottle in the air (SeaCreatureGuard).
     * Spawns each witch-type sea creature for one tick, high above the main world's spawn.
     */
    static String witchPotions() {
        List<String> witches = new ArrayList<>();
        for (Pool p : Fishing.CAT.pools) for (PoolCreature c : p.creatures) {
            MobDef d = Sky.mobs().def(c.mob);
            if (d != null && "WITCH".equalsIgnoreCase(d.entity) && !witches.contains(c.mob)) witches.add(c.mob);
        }
        if (!witches.contains("sc_sea_witch")) return "sc_sea_witch is not a WITCH in any pool";
        org.bukkit.World w = org.bukkit.Bukkit.getWorlds().get(0);
        org.bukkit.Location spawn = w.getSpawnLocation();
        org.bukkit.Location at = new org.bukkit.Location(w, spawn.getBlockX() + 0.5, Math.min(w.getMaxHeight() - 8, 300), spawn.getBlockZ() + 0.5);
        w.getChunkAt(at);
        org.bukkit.inventory.ItemStack bottle = new org.bukkit.inventory.ItemStack(org.bukkit.Material.SPLASH_POTION);
        org.bukkit.inventory.meta.PotionMeta pm = (org.bukkit.inventory.meta.PotionMeta) bottle.getItemMeta();
        pm.setBasePotionType(org.bukkit.potion.PotionType.HARMING);
        bottle.setItemMeta(pm);
        List<String> bad = new ArrayList<>();
        for (String id : witches) {
            org.bukkit.entity.LivingEntity witch = null;
            List<org.bukkit.entity.Entity> junk = new ArrayList<>();
            try {
                witch = Sky.mobs().spawn(id, at);
                if (witch == null) { bad.add(id + ": did not spawn"); continue; }
                for (boolean orphan : new boolean[]{false, true}) {
                    final org.bukkit.entity.LivingEntity thrower = witch;
                    org.bukkit.entity.SplashPotion pot = w.spawn(at.clone().add(0, 3, 0), org.bukkit.entity.SplashPotion.class, sp -> {
                        sp.setItem(bottle.clone());
                        sp.setShooter(thrower);
                    });
                    junk.add(pot);
                    SeaCreatureGuard.tag(pot, witch);
                    if (!SeaCreatureGuard.tagged(pot)) { bad.add(id + ": shot not tagged as a sea creature's"); break; }
                    if (orphan) pot.setShooter(null);   // she died with the bottle in the air
                    Map<org.bukkit.entity.LivingEntity, Double> hit = new HashMap<>();
                    hit.put(witch, 1.0);
                    org.bukkit.event.entity.PotionSplashEvent ev = new org.bukkit.event.entity.PotionSplashEvent(pot, witch, null, null, hit);
                    org.bukkit.Bukkit.getPluginManager().callEvent(ev);
                    if (!ev.isCancelled() && ev.getIntensity(witch) > 0)
                        bad.add(id + (orphan ? " (dead thrower)" : " (live thrower)") + ": vanilla Instant Damage still lands at intensity " + ev.getIntensity(witch));
                }
            } catch (RuntimeException ex) {
                bad.add(id + ": " + ex);
            } finally {
                for (org.bukkit.entity.Entity e : junk) e.remove();
                if (witch != null && witch.isValid()) Sky.mobs().remove(witch);
            }
        }
        return bad.isEmpty() ? null : String.join("; ", bad);
    }
}
