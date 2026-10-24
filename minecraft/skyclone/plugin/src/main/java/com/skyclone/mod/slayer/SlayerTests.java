package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.gen.Layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Live self-tests: {@code /sb selftest slayer}. Each returns null on success or what is wrong. */
final class SlayerTests {

    private SlayerTests() {}

    static void register(SlayerModule mod) {
        Sky.commands().selfTest("slayer.catalog", () -> {
            SlayerCatalog c = mod.catalog;
            if (c.types.size() != 6) return "expected 6 slayer types, got " + c.types.size();
            int[] tiers = {5, 5, 4, 4, 4, 5};
            String[] ids = {"zombie", "spider", "wolf", "enderman", "blaze", "vampire"};
            for (int i = 0; i < ids.length; i++) {
                SlayerCatalog.Type t = c.type(ids[i]);
                if (t == null) return "missing type " + ids[i];
                if (t.tiers.size() != tiers[i]) return ids[i] + " has " + t.tiers.size() + " tiers, research says " + tiers[i];
                long top = t.xp.length == 0 ? 0 : t.xp[t.xp.length - 1];
                long want = "vampire".equals(t.id) ? 2400 : 1_000_000;
                if (top != want) return ids[i] + " max level XP " + top + " != " + want;
                if (t.rewards.size() != t.xp.length) return ids[i] + " rewards/levels mismatch";
            }
            if (c.type("zombie").tier(4).cost != 50000) return "Zombie T4 cost should be 50,000 (research 2.3)";
            if (c.type("blaze").tier(4).health != 150_000_000) return "Blaze T4 HP should be 150M";
            return null;
        });

        // wave-5 regression: the slayer master repeated his three intro lines on every click before the first quest
        Sky.commands().selfTest("slayer.intro-once", () -> {
            SlayerData d = new SlayerData();
            if (SlayerModule.introOnce(d).size() != 3) return "a new hunter does not hear the 3-line intro";
            if (!d.introHeard) return "the intro was not marked heard";
            if (!SlayerModule.introOnce(d).isEmpty()) return "the intro repeats on the second click";
            SlayerData vet = new SlayerData();
            vet.questsStarted = 4;
            if (!SlayerModule.introOnce(vet).isEmpty()) return "a hunter with quests behind him hears the intro";
            return null;
        });

        Sky.commands().selfTest("slayer.mobs-exist", () -> {
            List<String> missing = new ArrayList<>();
            for (SlayerCatalog.Type t : mod.catalog.types) {
                for (SlayerCatalog.Tier tier : t.tiers) if (Sky.mobs().def(tier.mob) == null) missing.add(tier.mob);
                for (SlayerCatalog.Mini m : t.minibosses) if (Sky.mobs().def(m.mob) == null) missing.add(m.mob);
            }
            for (String id : List.of("slayer_spider_5b", "slayer_wolf_pup", "slayer_egg_sac", "slayer_broodling", "slayer_bat",
                    "slayer_quazii", "slayer_typhoeus", "slayer_fire_pillar", "slayer_clotgoyle", "slayer_spring")) {
                if (Sky.mobs().def(id) == null) missing.add(id);
            }
            for (MobDef d : Sky.mobs().all()) {
                if (!d.id.startsWith("slayer_mini_")) continue;
                for (var dr : d.drops) if (!Sky.items().exists(dr.item)) missing.add(d.id + " drop " + dr.item);
            }
            return missing.isEmpty() ? null : "missing: " + missing;
        });

        Sky.commands().selfTest("slayer.miniboss-damage", () -> {
            // a miniboss is a speed bump on the way to its boss, never deadlier than the boss itself
            List<String> bad = new ArrayList<>();
            for (SlayerCatalog.Type t : mod.catalog.types) {
                for (SlayerCatalog.Mini m : t.minibosses) {
                    MobDef d = Sky.mobs().def(m.mob);
                    SlayerCatalog.Tier tier = t.tier(m.minTier);
                    if (d == null || tier == null) continue;
                    if (d.damage > Math.max(50, tier.dps)) bad.add(m.mob + " " + (long) d.damage + " > " + (long) tier.dps);
                }
            }
            return bad.isEmpty() ? null : "minibosses out-hit their boss: " + bad;
        });

        Sky.commands().selfTest("slayer.drops-exist", () -> {
            List<String> bad = new ArrayList<>();
            for (SlayerCatalog.Type t : mod.catalog.types) {
                for (SlayerCatalog.LootEntry e : t.loot) {
                    if (e.token && !Sky.items().exists(e.item)) bad.add(t.id + " token " + e.item);
                    var def = Sky.items().def(e.item);
                    if (def != null && def.extra != null && def.extra.has("src") && "slayer".equals(def.extra.get("src").getAsString())) continue;
                    if (def == null && e.rng > 100_000 && !e.item.startsWith("ENCHANTMENT_") && !e.item.startsWith("DYE_")
                            && !e.item.endsWith("_RUNE") && !e.item.startsWith("PET_SKIN")) {
                        bad.add(t.id + " rare drop " + e.item);
                    }
                }
            }
            return bad.isEmpty() ? null : "not registered by anyone: " + bad;
        });

        Sky.commands().selfTest("slayer.loot-math", () -> {
            // research 04 / NEU: Ghoul T4 Foul Flesh 16.22%, Broodweaver T5 Toxic Arrow Poison 12.809%, Seraph T4 Verdict Core 0.0565%
            String r = expect(mod, "zombie", 4, "FOUL_FLESH", 16.22);
            if (r == null) r = expect(mod, "spider", 5, "TOXIC_ARROW_POISON", 12.809);
            if (r == null) r = expect(mod, "enderman", 4, "JUDGEMENT_CORE", 0.0565);
            if (r == null) r = expect(mod, "blaze", 4, "ARCHFIEND_DICE", 1.011);
            if (r != null) return r;
            // a level-0 player cannot roll a level-7 drop
            Map<String, Double> low = SlayerMath.chances(mod.catalog.type("zombie"), 5, 0, 0, 0, null, 1);
            if (low.containsKey("WARDEN_HEART")) return "Sentinel Heart rolled below Zombie Slayer 7";
            return null;
        });

        Sky.commands().selfTest("slayer.level-math", () -> {
            SlayerCatalog.Type z = mod.catalog.type("zombie"), v = mod.catalog.type("vampire"), w = mod.catalog.type("wolf");
            if (SlayerMath.level(z.xp, 1_000_000) != 9) return "Zombie 1,000,000 XP should be level 9";
            if (SlayerMath.level(z.xp, 999_999) != 8) return "Zombie 999,999 XP should be level 8";
            if (SlayerMath.level(z.xp, 15) != 2) return "Zombie 15 XP should be level 2";
            if (SlayerMath.level(w.xp, 249) != 2) return "Wolf 249 XP should be level 2";
            if (SlayerMath.level(v.xp, 2400) != 5) return "Vampire 2,400 XP should be level 5";
            if (Math.abs(SlayerMath.healSeconds(9) - 10) > 1e-9) return "after-kill heal should last 10 s at level 9";
            if (SlayerMath.cost(100000, 50) != 50000) return "Cut-Rate Contracts should halve the fee";
            return null;
        });

        Sky.commands().selfTest("slayer.rng-scores", () -> {
            SlayerCatalog.LootEntry wh = mod.catalog.type("zombie").lootEntry("WARDEN_HEART");
            SlayerCatalog.LootEntry jc = mod.catalog.type("enderman").lootEntry("JUDGEMENT_CORE");
            if (wh == null || wh.rng != 3_674_250) return "Sentinel Heart meter XP should be 3,674,250";
            if (jc == null || jc.rng != 885_562) return "Verdict Core meter XP should be 885,562";
            return null;
        });

        Sky.commands().selfTest("slayer.recipes", () -> {
            List<String> bad = new ArrayList<>();
            int n = 0;
            for (Recipe r : Sky.recipes().all()) {
                if (r.id == null || !r.id.startsWith("slayer:")) continue;
                n++;
                if (!Sky.items().exists(r.result)) bad.add(r.id + " result");
                if (r.unlock != null) {
                    String d = Sky.requirements().describe(r.unlock);
                    if (d == null || d.isBlank() || d.contains("?")) bad.add(r.id + " unlock " + r.unlock);
                }
            }
            if (n == 0) return "no slayer recipes registered";
            return bad.isEmpty() ? null : bad.toString();
        });

        Sky.commands().selfTest("slayer.requirements", () -> {
            String d = Sky.requirements().describe("slayer:zombie:3");
            if (d == null || !d.contains("Zombie Slayer 3")) return "slayer:zombie:3 describes as '" + d + "'";
            String e = Sky.requirements().describe("slayer:eman:7");
            if (e == null || !e.contains("Enderman Slayer 7")) return "alias eman not resolved: '" + e + "'";
            for (SlayerCatalog.Type t : mod.catalog.types) {
                for (SlayerCatalog.Tier tier : t.tiers) {
                    if (tier.requirement == null) continue;
                    String x = Sky.requirements().describe(tier.requirement);
                    if (x == null || x.isBlank()) return t.id + " T" + tier.tier + " requirement does not describe";
                }
            }
            return null;
        });

        Sky.commands().selfTest("slayer.icons", () -> {
            int n = 0;
            for (SlayerCatalog.Type t : mod.catalog.types) {
                for (SlayerCatalog.LootEntry e : t.loot) {
                    if (!Sky.items().exists(e.item)) continue;
                    if (SlayerMenus.display(e.item, List.of("&7x")) == null) return "no icon for " + e.item;
                    n++;
                }
            }
            return n == 0 ? "no loot icons built" : null;
        });

        // Finish wave 2026-10-03: the crafting book listed these with nothing behind them.
        Sky.commands().selfTest("slayer.utility-items", () -> {
            for (String id : List.of("TACTICAL_INSERTION", "VOODOO_DOLL", "VOODOO_DOLL_WILTED")) {
                com.skyclone.api.item.ItemDef d = Sky.items().def(id);
                if (d == null) return id + " is not registered";
                if (d.abilities == null || d.abilities.isEmpty()) return id + " has no ability";
            }
            return null;
        });

        // ---- wave 5 (2026-10-06) ------------------------------------------------------------------

        Sky.commands().selfTest("slayer.hunting-shards", () -> {
            int n = 0;
            for (SlayerCatalog.Type t : mod.catalog.types) {
                for (SlayerCatalog.LootEntry e : t.loot) {
                    if (!e.shard) continue;
                    n++;
                    if (e.name == null || e.name.isBlank() || e.rarity == null) return t.id + " shard " + e.item + " has no name/rarity";
                    if (!e.item.startsWith("SHARD_")) return t.id + " shard " + e.item + " is not a hunting shard id";
                    boolean ownPool = e.item.equals("SHARD_PARAGON");     // audit C2: the Paragon rolls in its own pool
                    if (e.token || e.independent != ownPool) return e.item + (ownPool ? " must roll in its own pool" : " must be a weighted drop");
                }
                // without hunting a shard must leave the pool and the rest still sums to 1
                Map<String, Double> c = SlayerMath.chances(t, t.maxTier(), 9, 0, 0, null, 1, e -> !e.shard);
                double sum = 0;
                for (Map.Entry<String, Double> en : c.entrySet()) {
                    sum += en.getValue();
                    SlayerCatalog.LootEntry le = en.getKey().isEmpty() ? null : t.lootEntry(en.getKey());
                    if (le != null && le.shard) return t.id + " kept shard " + le.item + " in a pool that excludes shards";
                }
                if (Math.abs(sum - 1) > 1e-9) return t.id + " pool without shards sums to " + sum;
            }
            if (n < 7) return "expected >= 7 shard rows (Revenant, Paragon x5, Primordial, Burningsoul), got " + n;
            // research 2.5: Revenant Shard w150 next to Foul Flesh w2000 (16.22%) at T4 = 1.2165%
            String bad = expect(mod, "zombie", 4, "SHARD_REVENANT", 1.2165);
            if (bad != null) return bad;
            bad = expect(mod, "spider", 5, "SHARD_PRIMORDIAL", 0.71);
            if (bad != null) return bad;
            return expect(mod, "zombie", 4, "FOUL_FLESH", 16.22);    // the token absorbed the shard: research odds unchanged
        });

        Sky.commands().selfTest("slayer.wave5-hooks", () -> {
            // soulflow request 1: the mixin requirement exists and answers
            if (!Sky.requirements().known("slayer")) return "slayer requirement prefix not registered";
            String desc = Sky.requirements().describe("slayer:mixin:END_PORTAL_FUMES_MIXIN");
            if (desc == null || desc.isBlank() || desc.contains("Slayer ?")) return "slayer:mixin: describe broken: " + desc;
            // behaviour mixins grant no flat stat any more (Wolf Fur = one-hit MF, End Portal Fumes = Overflow)
            if (SlayerItems.MIXINS.get("WOLF_FUR_MIXIN")[0] != null) return "Wolf Fur Mixin still grants a flat stat";
            if (SlayerItems.MIXINS.get("END_PORTAL_FUMES_MIXIN")[0] != null) return "End Portal Fumes still grants a flat stat";
            // The Primordial: 10% (+ attribute), never twice
            if (Math.abs(SlayerMath.cocoonChance(true, 10, 2.5, false) - 0.125) > 1e-9) return "cocoon chance 10%+2.5% != 12.5%";
            if (SlayerMath.cocoonChance(true, 10, 0, true) != 0) return "a hatched monster could cocoon again";
            if (SlayerMath.cocoonChance(false, 10, 50, false) != 0) return "cocoon without the belt";
            // Paragon discount stacks after the mayor's half price: 100,000 -> 50,000 -> 47,500
            if (SlayerMath.cost(100_000, 50, 5) != 47_500) return "discount stacking: " + SlayerMath.cost(100_000, 50, 5);
            if (Sky.items().exists(Cocoon.BELT) && Sky.items().def(Cocoon.BELT) == null) return "THE_PRIMORDIAL def missing";
            return null;
        });

        // wave 5 integration: other modules' effects that land in our fights
        Sky.commands().selfTest("slayer.pet-reforge-hooks", () -> {
            // Fire Pillar: we count 1 per strike; pets doubles it for a Wisp (Coldfused folded in) - never both
            if (SlayerMath.PILLAR_HITS_PER_STRIKE != 1) return "pillar strike must count 1 here (pets doubles it)";
            if (!SlayerMath.coldfusedBreakBonus(true, true) || SlayerMath.coldfusedBreakBonus(true, false)
                    || SlayerMath.coldfusedBreakBonus(false, true)) return "coldfused break bonus rule";
            if (SlayerDials.COLDFUSED_BREAK_SECONDS.get() <= 0) return "coldfused bonus lasts no time";
            // Tarantula pet Webbed Cells: 30% weaker Vitality reduction (T5 venom 75% -> 52.5%)
            if (Math.abs(SlayerMath.venomVitality(75, 30) - 52.5) > 1e-9 || SlayerMath.venomVitality(75, 0) != 75) return "webbed cells";
            // pets: the ability key we read must still exist on the Tarantula pet (a renamed key would silently read 0)
            if (Sky.has(com.skyclone.api.feature.Features.Pets.class)) {
                String pets = readResource("data/pets/pets.json");
                if (pets != null && !pets.contains("\"tarantula_webbed_cells\"")) return "pets no longer has tarantula_webbed_cells";
            }
            return null;
        });

        Sky.commands().selfTest("slayer.mob-families", () -> {
            // accessories request 3: every slayer mob names its families (accessories skips inference when set)
            for (String id : List.of("slayer_egg_sac", "slayer_clotgoyle", "slayer_zombie_1", "slayer_spider_1", "slayer_wolf_1",
                    "slayer_enderman_1", "slayer_blaze_1", "slayer_vampire_1", "slayer_fire_pillar")) {
                MobDef d = Sky.mobs().def(id);
                if (d == null) return "mob " + id + " not registered";
                if (d.extra == null || !d.extra.has("mobTypes") || d.extra.getAsJsonArray("mobTypes").isEmpty()) return id + " has no mobTypes";
            }
            String egg = Sky.mobs().def("slayer_egg_sac").extra.get("mobTypes").toString();
            if (!egg.contains("arthropod") || egg.contains("egg_sac")) return "egg sac families wrong: " + egg;
            if (!Sky.mobs().def("slayer_clotgoyle").extra.get("mobTypes").toString().contains("undead")) return "Blood Gargoyle not undead";
            return null;
        });

        Sky.commands().selfTest("slayer.fee-currency", () -> {
            SlayerCatalog.Type v = mod.catalog.type("vampire");
            if (v == null) return "no vampire slayer";
            SlayerCatalog.Tier t1 = v.tier(1);
            boolean motes = Quests.paysMotes(v);
            if (motes != Sky.has(com.skyclone.api.feature.Features.Rift.class)
                    && !"coins".equalsIgnoreCase(SlayerDials.VAMPIRE_FEE_CURRENCY.get())) {
                return "Motes fee should follow Features.Rift being provided";
            }
            String text = Quests.feeText(v, 2000);
            if (motes ? !text.contains("Motes") : !text.contains("coins")) return "fee text '" + text + "' for motes=" + motes;
            if (motes && Math.abs(mod.quests.basePrice(v, t1) - t1.cost * SlayerDials.COST_SCALE.get()) > 1e-6) {
                return "a Motes fee must not carry the coins-per-mote multiplier";
            }
            if (Quests.paysMotes(mod.catalog.type("zombie"))) return "only the Bloodfiend charges Motes";
            return null;
        });

        Sky.commands().selfTest("slayer.npc-anchor", () -> {
            if (Sky.worlds().island("hub") == null) return "hub island is not registered (the hub module builds npc.slayer_master)";
            Layout l = Sky.worlds().layout("hub");
            if (l == null) return "hub has no layout yet";
            return l.anchor("npc.slayer_master") == null ? "hub layout lacks anchor npc.slayer_master" : null;
        });

        // ---- real-game audit 2026-10-06 (docs/AUDIT_REAL_GAME_2026-10-06.md rows C1-C8) ------------------------
        Sky.commands().selfTest("slayer.audit-2026-10-06", () -> {
            SlayerCatalog c = mod.catalog;
            // C1: ERE Artifact Upgrader from Enderman 6
            SlayerCatalog.LootEntry ere = c.type("enderman").lootEntry("EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER");
            if (ere == null || ere.minLevel != 6) return "C1: ERE Artifact Upgrader should need Enderman 6";
            // C2: Paragon 0.25% every T4, 0.5% every T5, level 5, own pool, never below T4
            for (SlayerCatalog.Type t : c.types) {
                if (t.id.equals("vampire")) continue;
                SlayerCatalog.LootEntry p = t.lootEntry("SHARD_PARAGON");
                if (p == null || !p.independent || p.minLevel != 5) return "C2: " + t.id + " Paragon must be an own-pool level-5 drop";
                for (int i = 1; i <= t.maxTier(); i++) {
                    double want = i == 4 ? 0.25 : i == 5 ? 0.5 : 0;
                    if (Math.abs(p.chanceAt(i) - want) > 1e-9) return "C2: " + t.id + " T" + i + " Paragon " + p.chanceAt(i) + "%, want " + want + "%";
                }
            }
            // C3: only Gabagool reaches the Inferno summoner; the other four distillates are lootshare only
            SlayerCatalog.Type b = c.type("blaze");
            Map<String, Double> own = SlayerMath.chances(b, 3, 9, 0, 0, null, 1);
            Map<String, Double> share = SlayerMath.chances(b, 3, 9, 0, 0, null, 1, e -> true, true);
            String r = expect(mod, "blaze", 3, "CRUDE_GABAGOOL_DISTILLATE", 4.87);
            if (r != null) return "C3: " + r;
            for (String dist : List.of("BLAZE_ROD_DISTILLATE", "GLOWSTONE_DUST_DISTILLATE", "MAGMA_CREAM_DISTILLATE", "NETHER_STALK_DISTILLATE")) {
                if (own.containsKey(dist)) return "C3: " + dist + " drops to the summoner";
                if (!share.containsKey(dist)) return "C3: " + dist + " missing from the lootshare roll";
            }
            // C4: the meter ramps the selected drop 1x -> 3x
            if (Math.abs(SlayerDials.RNG_BOOST.get() - 2.0) > 1e-9)
                return "C4: dial slayer.rng-boost is " + SlayerDials.RNG_BOOST.get() + " (a saved override?); the reference ramp is 2 (1x -> 3x)";
            // C5-C7
            r = expect(mod, "enderman", 4, "SUMMONING_EYE", 0.565);
            if (r == null) r = expect(mod, "enderman", 4, "TWILIGHT_ARROW_POISON", 12.7);
            if (r == null) r = expect(mod, "zombie", 5, "FOUL_FLESH", 13.6082);
            if (r != null) return r;
            SlayerCatalog.LootEntry web = c.type("spider").lootEntry("TARANTULA_WEB");
            if (web.minAt(4) != 32 || web.maxAt(4) != 48) return "C6: Broodweaver T4 webs " + web.minAt(4) + "-" + web.maxAt(4) + ", want 32-48";
            // C8: the reference's new name is Shredded Sinew, so ours is not
            var def = Sky.items().def("SHARD_OF_THE_SHREDDED");
            if (def != null && def.name != null && def.name.contains("Shredded Sinew")) return "C8: SHARD_OF_THE_SHREDDED shows the reference name " + def.name;
            return null;
        });
    }

    /** A bundled resource as text (another module's data file, read only to check an id contract), or null. */
    private static String readResource(String path) {
        try (java.io.InputStream in = Sky.plugin().getResource(path)) {
            return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static String expect(SlayerModule mod, String type, int tier, String item, double pct) {
        SlayerCatalog.Type t = mod.catalog.type(type);
        Map<String, Double> c = SlayerMath.chances(t, tier, 9, 0, 0, null, 1);
        Double got = c.get(item);
        if (got == null) return type + " T" + tier + " cannot drop " + item;
        if (Math.abs(got * 100 - pct) > 0.01) return type + " T" + tier + " " + item + " = " + (got * 100) + "%, research " + pct + "%";
        return null;
    }
}
