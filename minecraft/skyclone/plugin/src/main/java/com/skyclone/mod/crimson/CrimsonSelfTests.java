package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code /sb selftest crimson}: does the isle's data hang together on the live server? */
final class CrimsonSelfTests {

    private CrimsonSelfTests() {}

    static final String[] SETS = {"CRIMSON", "AURORA", "TERROR", "FERVOR", "HOLLOW"};
    static final String[] PIECES = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};

    static void register() {
        Sky.commands().selfTest("crimson.items-exist", CrimsonSelfTests::itemsExist);
        Sky.commands().selfTest("crimson.mobs-and-spawns", CrimsonSelfTests::mobs);
        Sky.commands().selfTest("crimson.anchors", CrimsonSelfTests::anchors);
        Sky.commands().selfTest("crimson.research-numbers", CrimsonSelfTests::numbers);
        Sky.commands().selfTest("crimson.quests-roll", CrimsonSelfTests::questRoll);
        Sky.commands().selfTest("crimson.boss-singleton", CrimsonSelfTests::bossSingleton);
        Sky.commands().selfTest("crimson.cindermaw-split", CrimsonSelfTests::cindermawSplit);
        Sky.commands().selfTest("crimson.pearls", CrimsonSelfTests::pearls);
        Sky.commands().selfTest("crimson.kuudra-requirements", CrimsonSelfTests::kuudraReqs);
        Sky.commands().selfTest("crimson.audit-rep-and-coins", CrimsonSelfTests::auditRepAndCoins);
        Wave5Tests.register();
    }

    /**
     * Real-game audit 2026-10-06 (K13/K14/K16/K17): quest rep 30..125, Leviathan rep 10..50 per tier through the
     * live dial, the Emissary discount at each rank on the live keys, and the prestige coin part on the live defs.
     */
    private static String auditRepAndCoins() {
        List<String> bad = new ArrayList<>();
        for (CrimsonTables.Quest q : CrimsonModule.tables.quests) if (q.rep < 30 || q.rep > 125) bad.add("quest " + q.id + " rep " + q.rep);
        List<Integer> lev = Factions.leviathanRepTable();
        for (int t = 0; t < 5; t++) if (CrimsonRules.kuudraRep(lev, t) <= 0) bad.add("leviathan rep tier " + t + " = " + CrimsonRules.kuudraRep(lev, t));
        int[] reps = {0, 1000, 3000, 7000, 12000}, pct = {0, 5, 10, 15, 20};
        for (int i = 0; i < reps.length; i++) if (Factions.emissaryPercent(reps[i]) != pct[i]) bad.add("emissary at " + reps[i] + " = " + Factions.emissaryPercent(reps[i]));
        int keys = 0;
        for (CrimsonTables.ShopEntry e : CrimsonModule.tables.shop) if (e.emissary) keys++;
        if (keys != 10) bad.add("emissary keys " + keys);
        if (Prestige.fixedCoins("CRIMSON_HELMET") <= 0) bad.add("CRIMSON_HELMET has no prestige coin part");
        if (Prestige.fixedCoins("INFERNAL_CRIMSON_HELMET") != 0) bad.add("Infernal has a prestige coin part");
        return fail(bad, "audit rep/coin problems");
    }

    /**
     * Two copies of a mini-boss collapse into one that carries the fight on (playtest-endgame: an admin spawn
     * plus the rule's own respawn were alive together, so one fight paid out twice). Spawned high over the
     * first world's spawn and removed again; picks a boss with no live copy so a real fight is never touched.
     */
    private static String bossSingleton() {
        String id = null;
        for (String[] b : CrimsonMenus.BOSSES) if (Sky.mobs().def(b[0]) != null && Sky.mobs().live(b[0]).isEmpty()) { id = b[0]; break; }
        if (id == null) return null;                       // every boss is up in a real fight: nothing safe to test with
        org.bukkit.World w = org.bukkit.Bukkit.getWorlds().get(0);
        org.bukkit.Location spawn = w.getSpawnLocation();
        org.bukkit.Location at = new org.bukkit.Location(w, spawn.getBlockX() + 0.5, Math.min(w.getMaxHeight() - 8, 300), spawn.getBlockZ() + 0.5);
        w.getChunkAt(at);
        org.bukkit.entity.LivingEntity a = null, b = null;
        try {
            a = Sky.mobs().spawn(id, at);
            if (a == null) return id + " did not spawn";
            double hp = Sky.mobs().maxHealth(a) * 0.4;
            Sky.mobs().setHealth(a, hp);
            b = Sky.mobs().spawn(id, at.clone().add(3, 0, 0));
            if (b == null) return id + " second copy did not spawn";
            int alive = 0;
            for (org.bukkit.entity.LivingEntity e : Sky.mobs().live(id)) if (e.getWorld() == w && e.isValid()) alive++;
            if (alive != 1) return alive + " copies of " + id + " alive after a second spawn (want 1)";
            if (a.isValid()) return "the older " + id + " was not removed";
            if (Math.abs(Sky.mobs().health(b) - hp) > 1) return "the surviving " + id + " has " + Sky.mobs().health(b) + " HP, not the fight's " + hp;
            if (b.getLocation().distanceSquared(at) > 1) return "the surviving " + id + " did not take the older one's place";
            return null;
        } finally {
            if (a != null && a.isValid()) Sky.mobs().remove(a);
            if (b != null && b.isValid()) Sky.mobs().remove(b);
        }
    }

    /**
     * The Cindermaw's split blazes take the boss HP dial and stay on their arena (playtest wave4-endgame: they
     * spawned at the full 2.5M under a 0.002 boss scale and one flew out over the void). Uses a Cindermaw
     * spawned high over the first world's spawn, only when none is up, and removes everything again.
     */
    private static String cindermawSplit() {
        String id = "crimson_ashfang";
        MobDef cd = Sky.mobs().def(Bosses.SPLIT_MOB);
        if (Sky.mobs().def(id) == null || cd == null) return "crimson_ashfang / " + Bosses.SPLIT_MOB + " not registered";
        if (!Sky.mobs().live(id).isEmpty()) return null;          // a real fight is up: nothing safe to test with
        org.bukkit.World w = org.bukkit.Bukkit.getWorlds().get(0);
        org.bukkit.Location spawn = w.getSpawnLocation();
        org.bukkit.Location at = new org.bukkit.Location(w, spawn.getBlockX() + 0.5, Math.min(w.getMaxHeight() - 8, 300), spawn.getBlockZ() + 0.5);
        w.getChunkAt(at);
        org.bukkit.entity.LivingEntity boss = null;
        List<org.bukkit.entity.LivingEntity> kids = new ArrayList<>();
        try {
            boss = Sky.mobs().spawn(id, at);
            if (boss == null) return id + " did not spawn";
            kids = Bosses.split(boss);
            if (kids.size() != 2) return "split made " + kids.size() + " blazes (want 2)";
            if (!Bosses.split(boss).isEmpty()) return "a second split made more blazes";
            double s = CrimsonModule.D.bossHpScale.get();
            double want = Math.max(1, cd.health * (s > 0 ? s : 1));
            for (org.bukkit.entity.LivingEntity k : kids) {
                if (!Bosses.isSplitChild(k)) return "a split blaze is not tracked as the Cindermaw's";
                if (Math.abs(Sky.mobs().maxHealth(k) - want) > 1) return "split blaze max HP " + Sky.mobs().maxHealth(k) + ", want " + want + " (boss-hp-scale " + s + ")";
            }
            org.bukkit.entity.LivingEntity k = kids.get(0);
            // Load the chunk 32 blocks out first: 26.x has no spawn chunks, and a mob teleported into an unloaded
            // chunk is unloaded with it (invalid), so the leash's teleport home would silently do nothing - the
            // first live run failed with "the leashed blaze is 32 blocks from its arena" for exactly that reason.
            org.bukkit.Location out = at.clone().add(Bosses.CHILD_LEASH + 20, 0, 0);
            w.getChunkAt(out);
            if (!k.teleport(out) || !k.isValid()) return "could not move a split blaze 32 blocks out (test setup)";
            if (!Bosses.leashNow(k)) return "a split blaze 32 blocks out was not leashed";
            if (k.getLocation().distance(at) > Bosses.CHILD_LEASH) return "the leashed blaze is " + Math.round(k.getLocation().distance(at)) + " blocks from its arena";
            if (Bosses.leashNow(k)) return "a split blaze at home was leashed again";
            return null;
        } finally {
            for (org.bukkit.entity.LivingEntity k : kids) if (k.isValid()) Sky.mobs().remove(k);
            if (boss != null && boss.isValid()) Sky.mobs().remove(boss);
        }
    }

    private static String fail(List<String> bad, String what) {
        if (bad.isEmpty()) return null;
        return bad.size() + " " + what + ": " + String.join(", ", bad.subList(0, Math.min(12, bad.size())));
    }

    /** Every id this module references: owned armor/weapons/keys, shop goods and costs, quest deliveries, prestige targets, drops. */
    private static String itemsExist() {
        List<String> bad = new ArrayList<>();
        for (String s : SETS)
            for (String t : CrimsonRules.TIER_PREFIX)
                for (String p : PIECES) if (!Sky.items().exists(t + s + "_" + p)) bad.add(t + s + "_" + p);
        for (String id : List.of("RUNIC_STAFF", "HOLLOW_WAND", "TORMENTOR", "KUUDRA_TIER_KEY", "KUUDRA_HOT_TIER_KEY",
                "KUUDRA_BURNING_TIER_KEY", "KUUDRA_FIERY_TIER_KEY", "KUUDRA_INFERNAL_TIER_KEY"))
            if (!Sky.items().exists(id)) bad.add(id);
        for (CrimsonTables.ShopEntry e : CrimsonModule.tables.shop) {
            if (!Sky.items().exists(e.item)) bad.add("shop:" + e.item);
            for (String c : e.items) if (!Sky.items().exists((String) CrimsonRules.cell(c)[0])) bad.add("shopcost:" + c);
        }
        for (CrimsonTables.Quest q : CrimsonModule.tables.quests)
            if (q.type.equals("FETCH") && !Sky.items().exists(q.target)) bad.add("quest:" + q.target);
        for (ItemDef d : Sky.items().all()) {
            if (d.extra == null || !d.extra.has("prestigeTo")) continue;
            String to = d.extra.get("prestigeTo").getAsString();
            if (!Sky.items().exists(to)) bad.add("prestige:" + to);
            for (String c : Prestige.cost(d.id)) if (!Sky.items().exists((String) CrimsonRules.cell(c)[0])) bad.add("prestigecost:" + c);
        }
        for (CrimsonTables.Belt b : CrimsonModule.tables.dojo.belts) if (!Sky.items().exists(b.id)) bad.add("belt:" + b.id);
        for (MobDef m : Sky.mobs().all()) {
            if (m.id == null || !m.id.startsWith("crimson_")) continue;
            for (DropDef d : m.drops) if (!Sky.items().exists(d.item)) bad.add(m.id + ":" + d.item);
        }
        return fail(bad, "missing item ids");
    }

    /** Spawn rules point at registered mobs; quest kill targets exist; every mob has a drop. */
    private static String mobs() {
        List<String> bad = new ArrayList<>();
        for (SpawnRule r : CrimsonModule.RULES.values()) if (Sky.mobs().def(r.mob) == null) bad.add("rule " + r.id + " -> " + r.mob);
        for (CrimsonTables.Quest q : CrimsonModule.tables.quests)
            if ((q.type.equals("KILL") || q.type.equals("BOSS")) && Sky.mobs().def(q.target) == null) bad.add("quest " + q.id + " -> " + q.target);
        for (String[] b : CrimsonMenus.BOSSES) {
            MobDef d = Sky.mobs().def(b[0]);
            if (d == null) bad.add("boss " + b[0]);
            else if (!d.has("boss")) bad.add(b[0] + " not flagged boss");
        }
        for (MobDef m : Sky.mobs().all())
            if (m.id != null && m.id.startsWith("crimson_") && !m.id.startsWith("crimson_dojo") && m.drops.isEmpty()) bad.add(m.id + " has no drops");
        return fail(bad, "mob problems");
    }

    /** The built layout has every anchor/area the module and the shops module stand NPCs and spawners on. */
    private static String anchors() {
        Layout l = Sky.worlds().layout(CrimsonModule.ISLAND);
        if (l == null) return "no layout for crimson_isle yet";
        List<String> bad = new ArrayList<>();
        List<String> need = new ArrayList<>(List.of("spawn", "dojo.center", "dojo.spectate", "warp.drakehold", "warp.carmine", "warp.tomb", "warp.skull",
                "crimson.crater_lookout", "npc.peddler_dragontail", "npc.peddler_scarleton", "npc.brewer_scarleton", "npc.brewer_dragontail",
                "npc.generator_smith_scarleton", "npc.generator_smith_dragontail", "npc.lampwright", "npc.trophy_keeper", "npc.phone_dealer",
                "npc.frantic_engineer", "npc.devotee", "npc.mage_envoy", "npc.barbarian_envoy"));
        for (CrimsonTables.Npc n : CrimsonModule.tables.npcs) need.add(n.anchor);
        for (String[] b : CrimsonMenus.BOSSES) need.add(b[1]);
        for (String a : need) if (l.anchor(a) == null) bad.add(a);
        if (l.area("portal.hub") == null) bad.add("area portal.hub");
        for (SpawnRule r : CrimsonModule.RULES.values()) {
            if (r.area != null && l.area(r.area) == null) bad.add("area " + r.area);
            for (String a : r.anchors) if (l.anchor(a) == null) bad.add("anchor " + a);
        }
        int souls = 0;
        for (String k : l.anchors().keySet()) if (k.startsWith("fairy_soul.")) souls++;
        if (souls < 10 || souls > 40) bad.add("fairy souls " + souls + " (want 10-40)");
        Box arena = l.area("dojo.arena");
        if (arena == null) bad.add("area dojo.arena");
        return fail(bad, "missing anchors/areas");
    }

    /** The Belly of the Beast is built with its pearl spots, pearls/perfume exist, and the daily roll is sound. */
    private static String pearls() {
        Layout l = Sky.worlds().layout(CrimsonModule.ISLAND);
        if (l == null) return "no layout for crimson_isle yet";
        List<String> bad = new ArrayList<>();
        for (String a : List.of("crimson.belly", "crimson.matriarch")) if (l.anchor(a) == null) bad.add(a);
        int n = 0;
        while (l.anchor("crimson.pearl." + (n + 1)) != null) n++;
        if (n < 6) bad.add("only " + n + " pearl spots");
        if (!Sky.items().exists(Pearls.PEARL)) bad.add(Pearls.PEARL + " not registered");
        if (!Sky.items().exists(Pearls.PERFUME)) bad.add(Pearls.PERFUME + " not registered (perfume cannot be used)");
        int[] o = CrimsonRules.pearlOrder("00000000-0000-0000-0000-000000000001", "2026-10-04", Math.max(1, n));
        if (!java.util.Arrays.equals(o, CrimsonRules.pearlOrder("00000000-0000-0000-0000-000000000001", "2026-10-04", Math.max(1, n)))) bad.add("order not deterministic");
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        int allow = CrimsonModule.D.pearlsPerDay.get();
        for (int i = 0; i < allow; i++) {
            Map<Integer, Integer> show = CrimsonRules.pearlsShowing(o, allow, taken);
            if (show.isEmpty()) { bad.add("ran out of pearls after " + i); break; }
            taken.add(show.values().iterator().next());
        }
        if (!CrimsonRules.pearlsShowing(o, allow, taken).isEmpty()) bad.add("pearls left after collecting the allowance");
        return fail(bad, "pearl problems");
    }

    /** With the kuudra module up, the armor and the three weapons carry the reference's Leviathan clear. */
    private static String kuudraReqs() {
        if (!CrimsonModule.D.armorNeedsKuudra.get()) return null;
        if (!CrimsonModule.kuudraResolverPresent()) return null;          // armor stays on its Combat level only
        List<String> bad = new ArrayList<>();
        Map<String, String> want = Map.of("CRIMSON_CHESTPLATE", "kuudra:basic", "HOT_AURORA_HELMET", "kuudra:hot",
                "BURNING_TERROR_BOOTS", "kuudra:burning", "FIERY_FERVOR_LEGGINGS", "kuudra:fiery", "INFERNAL_HOLLOW_CHESTPLATE", "kuudra:infernal");
        want.forEach((id, req) -> {
            ItemDef d = Sky.items().def(id);
            if (d == null) bad.add(id + " missing");
            else if (!d.requirements.contains(req)) bad.add(id + " lacks " + req + " " + d.requirements);
        });
        return fail(bad, "Leviathan clear problems");
    }

    /** Formulas and data against research numbers. */
    private static String numbers() {
        List<String> bad = new ArrayList<>();
        Map<String, Double> hp = Map.of("crimson_bladesoul", 50e6, "crimson_magma_boss", 200e6, "crimson_mage_outlaw", 70e6,
                "crimson_barbarian_duke", 70e6, "crimson_ashfang", 50e6);
        hp.forEach((id, v) -> { MobDef d = Sky.mobs().def(id); if (d == null || d.health != v) bad.add(id + " hp " + (d == null ? "?" : d.health) + " != " + v); });
        // research 06: Hot ~ Basic x1.26, Infernal ~ Basic x2.52 (chestplate health 230 -> 290 -> 580)
        ItemDef basic = Sky.items().def("CRIMSON_CHESTPLATE"), hot = Sky.items().def("HOT_CRIMSON_CHESTPLATE"), inf = Sky.items().def("INFERNAL_CRIMSON_CHESTPLATE");
        if (basic == null || hot == null || inf == null) bad.add("crimson chestplates missing");
        else {
            double b = basic.stats.getOrDefault(Stat.HEALTH, 0.0);
            if (b != 230) bad.add("CRIMSON_CHESTPLATE health " + b + " != 230");
            double rh = hot.stats.getOrDefault(Stat.HEALTH, 0.0) / b, ri = inf.stats.getOrDefault(Stat.HEALTH, 0.0) / b;
            if (Math.abs(rh - 1.26) > 0.03) bad.add("hot ratio " + rh);
            if (Math.abs(ri - 2.52) > 0.05) bad.add("infernal ratio " + ri);
        }
        List<CrimsonTables.Rank> ranks = CrimsonModule.tables.factions.ranks;
        int[] th = {0, 1000, 3000, 7000, 12000};
        for (int i = 0; i < th.length && i < ranks.size(); i++) if (ranks.get(i).rep != th[i]) bad.add("rank " + i + " at " + ranks.get(i).rep);
        if (CrimsonRules.rankIndex(ranks, 2999) != 1 || CrimsonRules.rankIndex(ranks, 3000) != 2) bad.add("rankIndex wrong at 3,000");
        if (!"HOT_AURORA_HELMET".equals(CrimsonRules.nextPrestige("AURORA_HELMET"))) bad.add("nextPrestige");
        if (CrimsonRules.nextPrestige("INFERNAL_TERROR_BOOTS") != null) bad.add("infernal has a next prestige");
        int sb = 0;
        for (CrimsonTables.Rank r : ranks) sb += r.sbxp;
        if (sb != 75) bad.add("rank SkyBlock XP total " + sb + " != 75");
        int prev = -1;
        for (CrimsonTables.Belt b : CrimsonModule.tables.dojo.belts) { if (b.points <= prev) bad.add("belts not increasing at " + b.id); prev = b.points; }
        SpawnRule mb = CrimsonModule.RULES.get("crimson_boss_magma_boss");
        if (mb == null || mb.respawnSeconds != CrimsonModule.D.magmaRespawn.get()) bad.add("magma boss respawn not applied");
        return fail(bad, "number mismatches");
    }

    /** A board rolls 5 distinct quests for both factions at every rank, deterministically. */
    private static String questRoll() {
        List<String> bad = new ArrayList<>();
        for (String f : CrimsonRules.FACTIONS)
            for (int rank = 0; rank < 5; rank++) {
                List<CrimsonTables.Quest> a = CrimsonRules.rollQuests(Factions.usable(), f, rank, 42L + rank, 5);
                List<CrimsonTables.Quest> b = CrimsonRules.rollQuests(Factions.usable(), f, rank, 42L + rank, 5);
                if (a.size() != 5) bad.add(f + " rank " + rank + " rolled " + a.size());
                if (!a.equals(b)) bad.add(f + " rank " + rank + " not deterministic");
                for (CrimsonTables.Quest q : a) if (!q.forFaction(f) || q.minRank > rank) bad.add(q.id + " offered to " + f + "/" + rank);
            }
        return fail(bad, "quest roll problems");
    }
}
