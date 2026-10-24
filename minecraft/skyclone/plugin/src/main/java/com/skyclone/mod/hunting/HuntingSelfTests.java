package com.skyclone.mod.hunting;

import com.skyclone.api.Sky;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.hunting.gen.SafariGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Live checks: /sb selftest hunting. Every one returns null on pass. */
final class HuntingSelfTests {

    private HuntingSelfTests() {}

    static final String[] TOOLS = {"ABYSMAL_LASSO", "VINERIP_LASSO", "ENTANGLER_LASSO", "EVERSTRETCH_LASSO", "BASIC_FISHING_NET",
            "MEDIUM_FISHING_NET", "TURBO_FISHING_NET", "GIGANTIC_FISHING_NET", "SMALL_POCKET_BLACK_HOLE", "MEDIUM_POCKET_BLACK_HOLE",
            "LARGE_POCKET_BLACK_HOLE", "VENATOR_GENESIS", "SILVA_DOMINUS", "CURSUS_FERAE", "APEX_PRAEDATOR", "NEX_TITANUM",
            "RETIA_BASICA", "RETIA_MELIORA", "RETIA_ROBUSTA", "RETIA_FORTA", "RETIA_SUPREMA", "CRITTER_CAPSULE",
            "MASTERFUL_CRITTER_CAPSULE", "SAFARI_TICKET_BASIC", "SAFARI_TICKET_ECONOMY", "SAFARI_TICKET_PREMIUM",
            "SAFARI_TICKET_FIRST_CLASS", "ESSENCE_SAFARI", "LUSHLILAC_BONBON", "OCEANDY", "BRINE_TONIC"};

    static void register() {
        // wave-5 integration: the requirement keys and the menu door other modules use
        Sky.commands().selfTest("hunting.integration-hooks", () -> {
            if (!Sky.requirements().known("safari")) return "requirement prefix 'safari' is not registered (reforges' Majestic reads safari:active)";
            if (!Sky.requirements().known("skill")) return "skill: requirements unknown (the Safari warp gate)";
            if (!Sky.commands().exists("hunting")) return "/hunting is not registered (Features.Hunting.openMenu runs it)";
            return null;
        });

        Sky.commands().selfTest("hunting.catalog", () -> {
            if (Hunt.cat.size() != 322) return "expected 322 shards, catalog has " + Hunt.cat.size();
            List<String> bad = new ArrayList<>();
            for (ShardDef s : Hunt.cat.all()) if (!Sky.items().exists(s.id)) bad.add(s.id);
            return bad.isEmpty() ? null : "shard items not registered: " + bad;
        });
        Sky.commands().selfTest("hunting.levelling-matches-research", () -> {
            if (HuntMath.level("COMMON", 96) != 10 || HuntMath.level("COMMON", 95) != 9) return "COMMON 96 shards != level 10";
            if (HuntMath.maxShards("LEGENDARY") != 24 || HuntMath.maxShards("EPIC") != 32 || HuntMath.maxShards("RARE") != 48
                    || HuntMath.maxShards("UNCOMMON") != 64) return "max shards per rarity drifted from the reference table";
            if (HuntMath.captureXp("COMMON") != 75 || HuntMath.captureXp("LEGENDARY") != 1000) return "capture XP drifted from research 02";
            if (HuntMath.shardsPerCatch(250, 0.99) != 3 || HuntMath.shardsPerCatch(250, 0.1) != 4) return "Hunting Fortune math wrong";
            return null;
        });
        Sky.commands().selfTest("hunting.critters-registered", () -> {
            List<String> bad = new ArrayList<>();
            for (ShardDef s : Hunt.cat.all()) {
                String m = s.critterMob();
                if (m != null && Sky.mobs().def(m) == null) bad.add(m);
            }
            for (ShardCatalog.Habitat h : Hunt.cat.habitats())
                for (String c : h.critters) if (Hunt.cat.byCritter(c) == null) bad.add(h.id + ":" + c);
            return bad.isEmpty() ? null : "critters missing: " + bad.subList(0, Math.min(12, bad.size()));
        });
        Sky.commands().selfTest("hunting.mob-sources-exist", () -> {
            List<String> bad = new ArrayList<>();
            for (String m : Hunt.cat.mobIds()) if (Sky.mobs().def(m) == null) bad.add(m);
            return bad.isEmpty() ? null : "shard source mobs nobody registered (renamed?): " + bad;
        });
        Sky.commands().selfTest("hunting.every-shard-obtainable", () -> {
            Set<String> fusible = new HashSet<>();
            List<ShardDef> all = new ArrayList<>(Hunt.cat.all());
            // self pairs (the line rule) + pairs inside each family (the family rule): cheap, and they cover every rule
            Map<String, List<ShardDef>> fam = new HashMap<>();
            for (ShardDef a : all) for (String f : a.families) fam.computeIfAbsent(f, k -> new ArrayList<>()).add(a);
            for (ShardDef a : all) for (Fusion.Output o : Fusion.outputs(Hunt.cat, a, a)) fusible.add(o.shardId());
            for (List<ShardDef> members : fam.values())
                for (ShardDef a : members) for (ShardDef b : members) for (Fusion.Output o : Fusion.outputs(Hunt.cat, a, b)) fusible.add(o.shardId());
            List<String> bad = new ArrayList<>();
            for (ShardDef s : all) if (s.sources.stream().allMatch(x -> "fusion".equals(x.type)) && !fusible.contains(s.id)) bad.add(s.id);
            return bad.isEmpty() ? null : "fusion-only shards no fusion makes: " + bad;
        });
        Sky.commands().selfTest("hunting.effects-resolve", () -> {
            List<String> bad = new ArrayList<>();
            Map<String, Integer> max = new HashMap<>();
            for (ShardDef s : Hunt.cat.all()) {
                max.put(s.attr, 10);
                if (("stat".equals(s.effect.kind) || "stat_pct".equals(s.effect.kind)) && Stat.parse(s.effect.stat) == null) bad.add(s.attr + ":" + s.effect.stat);
                if (s.effect.stat2 != null && Stat.parse(s.effect.stat2) == null) bad.add(s.attr + ":" + s.effect.stat2);
            }
            AttrSnapshot snap = new AttrSnapshot(max, Hunt.cat, Set.of());
            for (Map.Entry<String, Double> e : snap.values.entrySet()) if (!Double.isFinite(e.getValue())) bad.add("nan:" + e.getKey());
            if (snap.levels.size() != Hunt.cat.size()) bad.add("snapshot dropped attributes: " + snap.levels.size());
            return bad.isEmpty() ? null : "unresolvable effects: " + bad;
        });
        Sky.commands().selfTest("hunting.tools", () -> {
            List<String> bad = new ArrayList<>();
            for (String t : TOOLS) {
                ItemDef d = Sky.items().def(t);
                if (d == null) { bad.add("missing " + t); continue; }
                for (AbilityDef a : d.abilities) if (a.id != null && !a.id.startsWith("hunting_")) bad.add(t + " ability " + a.id);
            }
            return bad.isEmpty() ? null : String.join(", ", bad);
        });
        Sky.commands().selfTest("hunting.safari-layout", () -> {
            Layout l = Sky.worlds().layout(SafariGenerator.ID);
            if (l == null) return "no layout for " + SafariGenerator.ID;
            for (String a : new String[]{"spawn", "npc.safari_receptionist", "npc.safari_manager", "npc.safari_perks", "npc.safari_warden", "npc.huntmaster"})
                if (l.anchor(a) == null) return "missing anchor " + a;
            for (String b : Safari.BIOMES) if (l.area("biome." + b) == null) return "missing area biome." + b;
            if (l.area("portal.hub") == null) return "missing portal.hub";
            return null;
        });
        Sky.commands().selfTest("hunting.habitats-exist", () -> {
            List<String> bad = new ArrayList<>();
            for (ShardCatalog.Habitat h : Hunt.cat.habitats()) {
                if (Sky.worlds().island(h.island) == null) { bad.add(h.id + " (island " + h.island + ")"); continue; }
                Box b = Sky.worlds().area(h.island, h.area);
                if (b == null) bad.add(h.id + " (area " + h.island + "/" + h.area + ")");
            }
            return bad.isEmpty() ? null : "habitats with no place to live: " + bad;
        });
        // playtest wave 5: "/sb hunting spawn <p> SHARD_INKLOTL" said Unknown critter (its id is SHARD_SEPIALOT)
        Sky.commands().selfTest("hunting.find-by-name", () -> {
            for (ShardDef s : Hunt.cat.all()) {
                if (Hunt.cat.find(s.name) != s) return "'" + s.name + "' does not find " + s.id;
                if (Hunt.cat.find("SHARD_" + s.name.toUpperCase(java.util.Locale.ROOT).replace(' ', '_')) != s)
                    return "SHARD_<name> does not find " + s.id;
                if (Hunt.cat.find(s.id) != s) return "a name shadows the id " + s.id;
            }
            ShardDef ink = Hunt.cat.find("SHARD_INKLOTL");
            if (ink == null || ink.critterMob() == null || Sky.mobs().def(ink.critterMob()) == null)
                return "SHARD_INKLOTL does not resolve to a spawnable critter";
            return null;
        });
        // playtest wave 5: Lotus Lagoon critters swam 8-12 deep and 20-60 out while a net reaches ~7
        Sky.commands().selfTest("hunting.water-critters-netable", () -> {
            if (HDials.WATER_DEPTH.get() < 0 || HDials.WATER_DEPTH.get() > 6) return "hunting.water-critter-depth outside 0..6: a net cannot reach it";
            if (HDials.WATER_LEASH.get() <= 0) return "hunting.water-critter-leash must be > 0";
            if (Math.hypot(HuntMath.WATER_SPAWN_MAX, HDials.WATER_DEPTH.get() + 0.6) >= 7)
                return "water critters appear outside one 7-block net throw";
            List<String> bad = new ArrayList<>();
            for (ShardCatalog.Habitat h : Hunt.cat.habitats()) {
                if (!"water".equals(h.kind)) continue;
                for (String c : h.critters) {
                    MobDef d = Sky.mobs().def(c);
                    if (d == null || d.extra == null || !d.extra.has("method") || !"net".equals(d.extra.get("method").getAsString()))
                        bad.add(h.id + ":" + c);
                }
            }
            return bad.isEmpty() ? null : "water-habitat critters a net cannot target: " + bad.subList(0, Math.min(12, bad.size()));
        });
        Sky.commands().selfTest("hunting.critter-defs-sane", () -> {
            for (MobDef d : Sky.mobs().all()) {
                if (!d.id.startsWith("critter_")) continue;
                if (d.extra == null || !d.extra.has("shard") || !Sky.items().exists(d.extra.get("shard").getAsString())) return d.id + " has no shard item";
                if (d.health <= 0) return d.id + " has no health";
            }
            return null;
        });
    }
}
