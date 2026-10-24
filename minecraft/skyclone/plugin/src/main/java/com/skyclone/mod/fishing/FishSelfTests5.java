package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Wave 5 live checks (Galatea pools, Puddle Jumper, geysers, item effects). Each returns null on pass. */
final class FishSelfTests5 {

    private FishSelfTests5() {}

    static final List<String> TORRHUS = List.of("sc_haggard", "sc_brineling", "sc_sprawl", "sc_torrid", "sc_silkbreeze", "sc_giant_isopod");

    static void register() {
        Sky.commands().selfTest("fishing.galatea-pools", FishSelfTests5::galateaPools);
        Sky.commands().selfTest("fishing.torrhus-creatures", FishSelfTests5::torrhus);
        Sky.commands().selfTest("fishing.galatea-islands", FishSelfTests5::galateaIslands);
        Sky.commands().selfTest("fishing.puddle-jumper-def", FishSelfTests5::jumperDef);
        Sky.commands().selfTest("fishing.puddle-jumper-ride-path", FishSelfTests5::ridePath);
        Sky.commands().selfTest("fishing.puddle-jumper-elusive", FishSelfTests5::elusive);
        Sky.commands().selfTest("fishing.geyser-pick", FishSelfTests5::geyserPick);
        Sky.commands().selfTest("fishing.geyser-flounder", FishSelfTests5::geyserFlounder);
        Sky.commands().selfTest("fishing.geyser-volcano-area", FishSelfTests5::volcanoArea);
        Sky.commands().selfTest("fishing.thunder-bottle", FishSelfTests5::thunderBottle);
        Sky.commands().selfTest("fishing.effect-hooks", FishSelfTests5::effectHooks);
        Sky.commands().selfTest("fishing.isopod-husk", FishSelfTests5::isopodHusk);
        Sky.commands().selfTest("fishing.chum-bucket-items", FishSelfTests5::chumItems);
        Sky.commands().selfTest("fishing.chum-bucket-live", FishSelfTests5::chumLive);
        Sky.commands().selfTest("fishing.chum-bucket-math", FishSelfTests5::chumMath);
        Sky.commands().selfTest("fishing.fish-bowl-pools", FishSelfTests5::fishBowlPools);
        Sky.commands().selfTest("fishing.pet-odds-handshake", FishSelfTests5::petOddsHandshake);
    }

    /** Pets' external-by-fishing markers are read (Slug is one today); a perk pets still runs itself is 0 here. */
    static String petOddsHandshake() {
        if (Sky.plugin().getResource("data/pets/pets.json") == null) return null;
        var ext = FishEffects.externalToFishing();
        if (!ext.contains("slug_slow_and_steady")) return "pets marks slug_slow_and_steady external-by-fishing but it was not read: " + ext;
        if (FishEffects.petOdds(null, "megalodon_feeding_frenzy") != 0) return "no player, no odds";
        double base = FishMath.replaceChance(0.15, 0, 0.8);
        if (Math.abs(base - 0.15) > 1e-12) return "replace chance base changed";
        return null;
    }

    // ---- chum buckets / fish bowls ---------------------------------------------------------------

    /** The three items exist, the Chumcap needs Fishing 15, and the Mooshroom Diver answers only a placed Chumcap. */
    static String chumItems() {
        for (String id : new String[]{ChumBuckets.CHUM, ChumBuckets.BUCKET, ChumBuckets.CHUMCAP})
            if (!Sky.items().exists(id)) return id + " not registered";
        var cap = Sky.items().def(ChumBuckets.CHUMCAP);
        if (cap.requirements == null || !cap.requirements.contains("skill:fishing:15")) return "Chumcap Bucket lacks skill:fishing:15";
        FishingCatalog.Pool pool = null;
        for (FishingCatalog.Pool p : Fishing.CAT.pools) if ("chumcap".equals(p.id)) pool = p;
        if (pool == null || !"chumcap".equals(pool.trigger)) return "pool chumcap missing or not triggered by a placed bucket";
        PoolResolver.Spot s = new PoolResolver.Spot();
        s.island = "hub";
        s.fishingLevel = 60;
        s.carried.add(ChumBuckets.CHUMCAP);            // carrying one is no longer enough
        for (var r : Fishing.RES.replacements(s)) if ("chumcap".equals(r.pool().id)) return "a carried Chumcap still calls the Mooshroom Diver";
        s.chumcap = true;
        boolean live = false;
        for (var r : Fishing.RES.replacements(s)) if ("chumcap".equals(r.pool().id)) live = r.options().stream().anyMatch(o -> o.mob().equals("sc_agarimoo"));
        return live ? null : "a placed Chumcap does not call the Mooshroom Diver";
    }

    /** A bucket stood up in the main world counts in reach, adds chum and (Chumcap) Fishing Speed, then cleans up. */
    static String chumLive() {
        World w = Bukkit.getWorlds().get(0);
        Location at = w.getSpawnLocation().clone().add(0.5, 3, 0.5);
        // wave-5 integration: with no player online the spawn chunk is not loaded (it was only loaded on a boot that
        // built islands), so the entities "did not spawn". Hold the chunk for the length of the test.
        org.bukkit.Chunk chunk = w.getChunkAt(at);
        boolean ticket = chunk.addPluginChunkTicket(Sky.plugin());
        int before = ChumBuckets.inReach(at, false);
        ChumBuckets.Bucket b = ChumBuckets.spawn(java.util.UUID.randomUUID(), "selftest", ChumBuckets.CHUMCAP, at);
        if (b == null) { if (ticket) chunk.removePluginChunkTicket(Sky.plugin()); return "could not stand a bucket up"; }
        try {
            if (ChumBuckets.inReach(at, false) != before + 1) return "a placed bucket is not in reach of itself";
            if (ChumBuckets.inReach(at.clone().add(FishDials.CHUM_REACH.get() + 5, 0, 0), false) > before) return "reach is not limited";
            if (FishDials.CHUM_BUCKETS.get()) {
                if (ChumBuckets.chumFor(at, 2) < 3) return "1 bucket + Glowy Chum Bait should give 3 chum, gave " + ChumBuckets.chumFor(at, 2);
                if (ChumBuckets.fishingSpeed(at) < Math.min(FishDials.CHUMCAP_FS.get(), FishDials.CHUMCAP_FS_CAP.get()))
                    return "a Chumcap in reach gives no Fishing Speed";
            }
            for (java.util.UUID u : new java.util.UUID[]{b.id, b.display, b.label})
                if (u == null || Bukkit.getEntity(u) == null) return "a bucket entity did not spawn";
            if (Bukkit.getEntity(b.id).isPersistent()) return "bucket entities must not persist (they are re-created from the profile record)";
        } finally {
            ChumBuckets.LIVE.remove(b.id);
            ChumBuckets.despawn(b);
        }
        String result = Bukkit.getEntity(b.id) == null ? null : "bucket entity survived despawn";
        if (ticket) chunk.removePluginChunkTicket(Sky.plugin());
        return result;
    }

    /** The reference numbers: +1 per bucket up to +4, Glowy +2 only with a bucket, deposits, the bracelet, returns. */
    static String chumMath() {
        if (FishMath.chumDrop(0, 1, 4, 2, true) != 0) return "Glowy chum without a bucket";
        if (FishMath.chumDrop(0, 1, 4, 2, false) != 2) return "bait-needs-bucket off should keep Glowy's +2";
        if (FishMath.chumDrop(6, 1, 4, 0, true) != 4) return "buckets should cap at +4";
        if (FishMath.chumDrop(2, 1, 4, 2, true) != 4) return "2 buckets + Glowy should be 4";
        if (FishMath.chumcapSpeed(5, 4, 16) != 16 || FishMath.chumcapSpeed(1, 4, 16) != 4) return "Chumcap speed should be +4 each up to +16";
        double[] own = FishMath.chumDeposit(3, 20, 10, false, true, 20, 10);
        double[] other = FishMath.chumDeposit(3, 20, 10, true, true, 20, 10);
        double[] plain = FishMath.chumDeposit(3, 20, 10, true, false, 20, 10);
        if (own[0] != 60 || own[1] != 30) return "own-bucket deposit should not get the bracelet bonus";
        if (other[0] != 120 || other[1] != 60) return "bracelet into someone else's bucket should double the pay";
        if (plain[0] != 60) return "no bracelet, no bonus";
        List<String> back = ChumBuckets.missing(List.of("A", "A", "B"), List.of("A"));
        if (!back.equals(List.of("A", "B"))) return "recorded-minus-standing gave " + back;
        if (!ChumBuckets.missing(List.of("A"), List.of("A", "A")).isEmpty()) return "a standing bucket with no record must never pay out";
        return null;
    }

    /** Fish Bowls read hunting's data: every pooled shard is Rare or better; Galatea has some when hunting ships data. */
    static String fishBowlPools() {
        var pools = FishBowls.pools();
        if (Sky.plugin().getResource("data/hunting/shards.json") == null) return null;   // hunting absent: nothing to read
        if (pools.isEmpty()) return "no rare water shards found in hunting's data";
        for (var e : pools.entrySet()) if (e.getValue().isEmpty()) return e.getKey() + " has an empty pool";
        if (FishBowls.baseChance() <= 0) return "hunting.fish-shard-chance is 0";
        return null;
    }

    // ---- pools ----------------------------------------------------------------------------------

    /** The Galatea pools resolve on their islands, and the Bayou's marsh/lagoon zones fish the Bayou pool. */
    static String galateaPools() {
        PoolResolver r = Fishing.RES;
        PoolResolver.Spot s = new PoolResolver.Spot();
        s.fishingLevel = 60;
        String[][] want = {{"galatea", "marsh"}, {"lotus_atoll", "lagoon"}, {"torrhus_canyon", "torrhus"}, {"backwater_bayou", "bayou"}};
        for (String[] w : want) {
            s.island = w[0];
            s.zoneText = " moonlit_marsh lotus_lagoon";
            var p = r.basePool(s);
            if (p == null || !w[1].equals(p.id)) return w[0] + " fishes " + (p == null ? "nothing" : p.id) + ", expected " + w[1];
            if (r.options(s).size() != 6) return w[0] + " offers " + r.options(s).size() + " creatures, expected 6";
        }
        if (Fishing.CAT.fsCap("lotus_atoll", 300) != 250) return "Lotus Atoll Fishing Speed cap should be 250 (research 01)";
        return null;
    }

    /** Six Torrent Springs creatures, registered, sea creatures, at the bestiary levels, dropping real items. */
    static String torrhus() {
        int[] levels = {44, 58, 71, 85, 105, 186};
        String[] rar = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"};
        for (int i = 0; i < TORRHUS.size(); i++) {
            MobDef d = Sky.mobs().def(TORRHUS.get(i));
            if (d == null) return TORRHUS.get(i) + " not registered";
            if (!Fishing.isSeaCreature(d)) return d.id + " is not flagged sea_creature";
            if (d.level != levels[i]) return d.id + " level " + d.level + ", bestiary says " + levels[i];
            if (!rar[i].equals(Fishing.rarityOf(d))) return d.id + " rarity " + Fishing.rarityOf(d);
            if (d.drops.isEmpty()) return d.id + " drops nothing";
        }
        // the signature drops that hunting's nets / salts need must exist
        for (String id : new String[]{"SEA_BRINE", "REINFORCED_NETTING", "ISOPOD_HUSK", "SUBLIME_SILK"})
            if (!Sky.items().exists(id)) return id + " is not registered (a Torrent Springs drop)";
        return null;
    }

    /** The islands (when generated) carry the hotspot anchors our hotspot counts expect. */
    static String galateaIslands() {
        List<String> notes = new ArrayList<>();
        for (String isl : new String[]{"lotus_atoll", "torrhus_canyon"}) {
            Layout l = Sky.worlds().layout(isl);
            if (l == null) continue;                 // not built yet / module absent: nothing to check
            int hot = 0;
            for (String k : l.anchors().keySet()) if (k.startsWith("fishing.hotspot.")) hot++;
            int want = Fishing.CAT.hotspots.getOrDefault(isl, 0);
            if (hot < want) notes.add(isl + " has " + hot + " hotspot anchors, fishing spawns " + want);
        }
        return notes.isEmpty() ? null : String.join("; ", notes);
    }

    // ---- Puddle Jumper --------------------------------------------------------------------------

    static String jumperDef() {
        MobDef d = Sky.mobs().def(PuddleJumper.MOB);
        if (d == null) return "sc_puddle_jumper not registered";
        if (!PuddleJumper.elusive(d)) return "the Puddle Jumper is not elusive";
        if (!d.behaviours.contains("fishing_puddle_jumper")) return "no fishing_puddle_jumper behaviour";
        if (d.damage != 0 || !d.has("passive")) return "the Puddle Jumper should be passive (it never attacks)";
        if (!"LEGENDARY".equals(Fishing.rarityOf(d))) return "rarity " + Fishing.rarityOf(d);
        return null;
    }

    /** The leap starts and ends at take-off height, peaks at the dial height and never jumps more than a block a tick. */
    static String ridePath() {
        double r = FishDials.PJ_RADIUS.get(), h = FishDials.PJ_HEIGHT.get();
        double[] a = FishMath.rideOffset(0, r, h), mid = FishMath.rideOffset(0.5, r, h), z = FishMath.rideOffset(1, r, h);
        if (Math.abs(a[0]) + Math.abs(a[1]) + Math.abs(a[2]) > 1e-9) return "the leap does not start at the frog";
        if (Math.abs(mid[1] - h) > 1e-9) return "peak " + mid[1] + " != " + h;
        if (Math.abs(z[1]) > 1e-9) return "it does not land at take-off height";
        int steps = 120;                              // 6 s at 20 ticks
        for (int i = 1; i <= steps; i++) {
            double[] p0 = FishMath.rideOffset((i - 1) / (double) steps, r, h), p1 = FishMath.rideOffset(i / (double) steps, r, h);
            double dx = p1[0] - p0[0], dy = p1[1] - p0[1], dz = p1[2] - p0[2];
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > 1.0) return "step " + i + " moves more than a block a tick";
        }
        return null;
    }

    /** A Puddle Jumper shrugs off damage (elusive) until its rider finishes it. Spawned for one tick. */
    static String elusive() {
        World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (w == null) return null;
        Location at = w.getSpawnLocation().clone().add(0, 40, 0);
        org.bukkit.Chunk chunk = w.getChunkAt(at);                 // not loaded with nobody online (see chumLive)
        boolean ticket = chunk.addPluginChunkTicket(Sky.plugin());
        LivingEntity le;
        try { le = Sky.mobs().spawn(PuddleJumper.MOB, at); } catch (RuntimeException e) { if (ticket) chunk.removePluginChunkTicket(Sky.plugin()); return "spawn failed: " + e; }
        if (le == null) { if (ticket) chunk.removePluginChunkTicket(Sky.plugin()); return "spawn returned null"; }
        try {
            double before = Sky.mobs().health(le);
            Sky.combat().rawDamage(le, 1_000, null, DamageContext.Kind.TRUE);
            if (!le.isValid() || le.isDead() || Sky.mobs().health(le) < before)
                return FishDials.PUDDLE_JUMPER.get() ? "an elusive Puddle Jumper took damage" : null;
            return null;
        } finally {
            if (le.isValid()) Sky.mobs().remove(le);
            if (ticket) chunk.removePluginChunkTicket(Sky.plugin());
        }
    }

    // ---- geysers --------------------------------------------------------------------------------

    /** Geyser picking is deterministic, spaced, and capped. */
    static String geyserPick() {
        List<int[]> cand = new ArrayList<>();
        for (int x = 0; x < 40; x += 2) for (int z = 0; z < 40; z += 2) cand.add(new int[]{x, z});
        List<int[]> a = FishMath.pickSites(cand, 5, 10), b = FishMath.pickSites(new ArrayList<>(cand.reversed()), 5, 10);
        if (a.size() != 5) return "picked " + a.size() + " of 5";
        for (int i = 0; i < a.size(); i++) if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1]) return "order-dependent pick";
        for (int i = 0; i < a.size(); i++) for (int j = i + 1; j < a.size(); j++) {
            double dx = a.get(i)[0] - a.get(j)[0], dz = a.get(i)[1] - a.get(j)[1];
            if (dx * dx + dz * dz < 100) return "two geysers closer than 10";
        }
        return null;
    }

    /** "Within 2 blocks of a geyser" (research 07 3.7) - and not 3. */
    static String geyserFlounder() {
        World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (w == null) return null;
        List<Location> g = List.of(new Location(w, 100, 60, 100));
        if (!Geysers.near(g, new Location(w, 101.9, 60.5, 100.5), 2)) return "1.4 blocks away should count";
        if (Geysers.near(g, new Location(w, 103.2, 60.5, 100.5), 2)) return "2.7 blocks away should not count";
        if (Geysers.near(g, new Location(w, 100.5, 70, 100.5), 2)) return "10 blocks above should not count";
        return null;
    }

    /** The Crimson Isle exposes either geyser anchors or the crater area + lava level the search needs. */
    static String volcanoArea() {
        Layout l = Sky.worlds().layout(Trophies.TROPHY_ISLAND);
        if (l == null) return null;                    // isle not generated in this world
        boolean anchors = false;
        for (String k : l.anchors().keySet()) anchors |= k.startsWith("geyser");
        if (anchors) return null;
        if (l.area(Trophies.VOLCANO_AREA) == null) return "crimson_isle has neither geyser anchors nor area " + Trophies.VOLCANO_AREA;
        if (Geysers.lavaLevel(l) == Integer.MIN_VALUE) return "crimson_isle layout data crimson.crater has no lava level (search falls back to a column scan)";
        return null;
    }

    // ---- items / effects ------------------------------------------------------------------------

    /** Charge math (+1% Pulse Ring), the bottle items exist and the empty one never stacks. */
    static String thunderBottle() {
        if (FishMath.thunderCharge(12_000, 1, false) != 12_000) return "Thunder should charge 12,000 (its Fishing XP)";
        if (FishMath.thunderCharge(12_000, 1, true) != 12_120) return "Pulse Ring should add 1%";
        if (FishMath.thunderCharge(-5, 1, true) != 0) return "negative charge";
        if (!Sky.items().exists(FishItems.EMPTY_BOTTLE) || !Sky.items().exists(FishItems.FULL_BOTTLE)) return "bottle items missing";
        var def = Sky.items().def(FishItems.EMPTY_BOTTLE);
        if (def.maxStack != 1) return "Empty Thunder Bottle stacks (" + def.maxStack + "): its charge lives on the item";
        ItemStack it = Sky.items().create(FishItems.EMPTY_BOTTLE, 1);
        Sky.items().update(it, d -> d.extra.addProperty(FishItems.CHARGE_KEY, 777L));
        ItemData d = Sky.items().data(it);
        if (FishItems.charge(d) != 777L) return "charge did not persist on the item";
        return null;
    }

    /** Treasure quality bonuses move the odds the right way and Slug / Flying Fish numbers apply. */
    static String effectHooks() {
        Map<String, Double> w = Fishing.CAT.treasure.weights;
        double g0 = FishMath.treasureShare(w, 0, 0, 0, "great"), g1 = FishMath.treasureShare(w, 0, 0.5, 0, "great");
        double o0 = FishMath.treasureShare(w, 0, 0, 0, "outstanding"), o1 = FishMath.treasureShare(w, 0, 0, 1.0, "outstanding");
        if (!(g1 > g0) || !(o1 > o0)) return "great/outstanding bonuses do not raise their odds";
        double sum = 0;
        for (String q : new String[]{"good", "great", "outstanding"}) sum += FishMath.treasureShare(w, 0.5, 0.2, 0.3, q);
        if (Math.abs(sum - 1) > 1e-9) return "treasure odds sum to " + sum;
        if (FishMath.slugPatienceMs(20_000, 50) != 10_000) return "a 50% Slug should halve the Slugfish wait";
        if (FishMath.slugPatienceMs(20_000, 500) != 2_000) return "the Slug cut is not capped at 90%";
        if (FishEffects.flashMult(null) < 1) return "Flash multiplier below 1 with no pet";
        return null;
    }

    /** Husks are capped by the dial and the item exists; a fresh FishData has eaten none. */
    static String isopodHusk() {
        if (!Sky.items().exists(FishItems.HUSK)) return "ISOPOD_HUSK not registered";
        if (new FishData().isopodHusks != 0) return "fresh data has husks";
        MobDef iso = Sky.mobs().def("sc_giant_isopod");
        boolean drops = false;
        if (iso != null) for (DropDef dr : iso.drops) drops |= FishItems.HUSK.equals(dr.item);
        return drops ? null : "the Giant Isopod does not drop Isopod Husks";
    }
}
