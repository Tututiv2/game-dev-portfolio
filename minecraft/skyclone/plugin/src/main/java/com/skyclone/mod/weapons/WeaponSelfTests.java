package com.skyclone.mod.weapons;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.mob.MobDef;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** {@code /sb selftest weapons}: does the data hang together at runtime, and do the formulas give research numbers? */
final class WeaponSelfTests {

    private WeaponSelfTests() {}

    static void register(WeaponsModule mod) {
        Sky.commands().selfTest("weapons.quiver-arrow", () -> {
            List<ItemDef> arrows = Quiver.arrowTypes();
            if (arrows.isEmpty()) return "no arrow items registered";
            org.bukkit.inventory.ItemStack proxy = QuiverArrow.build(arrows.get(0), 200);
            if (!QuiverArrow.isProxy(proxy)) return "build() is not recognised by isProxy()";
            if (proxy.getAmount() != 64) return "proxy amount should cap at 64, got " + proxy.getAmount();
            if (QuiverArrow.isProxy(new org.bukkit.inventory.ItemStack(org.bukkit.Material.TIPPED_ARROW))) return "a plain tipped arrow counts as the proxy";
            // the proxy must never be a sky item (sellable / storable / counted as a real ARROW)
            if (Sky.items().idOf(proxy) != null) return "the proxy carries a sky id: " + Sky.items().idOf(proxy);
            if (WeaponMath.infiniteQuiverPct(10, 3) != 30 || WeaponMath.infiniteQuiverPct(0, 3) != 0) return "Infinite Quiver X should be 30%";
            double[] b = WeaponMath.burn(1000, 3, 3);
            if (b[0] != 30 || b[1] != 3) return "Flaming Sword burn of a 1,000 hit should be 30 x 3, got " + b[0] + " x " + b[1];
            return null;
        });

        // ---- wave 5 -------------------------------------------------------------------------------------
        Sky.commands().selfTest("weapons.soulflow-costs", () -> {
            // the soulflow module's gate charges our abilities by id: every id it names must be one of ours
            for (String[] row : new String[][]{{"ETHERWARP_CONDUIT", "weapons_ether_transmission"},
                    {"ASPECT_OF_THE_VOID", "weapons_ether_transmission_void"}, {"VOIDEDGE_KATANA", "weapons_soulcry"},
                    {"TERMINATOR", "weapons_salvation"}, {"GYROKINETIC_WAND", "weapons_gravity_storm"}}) {
                ItemDef d = Sky.items().def(row[0]);
                if (d == null || !Traits.isOurs(d)) continue;
                boolean found = false;
                for (AbilityDef a : d.abilities) if (row[1].equals(a.id)) found = true;
                if (!found) return row[0] + " no longer carries " + row[1] + " (soulflow's cost table would miss it)";
            }
            if (SoulflowLink.scaled(1, 1.0) != 1 || SoulflowLink.scaled(1, 0.2) != 1 || SoulflowLink.scaled(2, 1.5) != 3
                    || SoulflowLink.scaled(0, 5) != 0) return "Soulstream cost scaling changed (ceil, min 1)";
            if (!Sky.has(com.skyclone.api.feature.Features.Soulflow.class) && !SoulflowLink.canPay(null, 1)) return "with no soulflow module Judgement Beam must stay free";
            return null;
        });

        Sky.commands().selfTest("weapons.target-magic-find", () -> {
            if (mod.targets() == null) return "TargetBonuses was never constructed (per-target Magic Find hook not registered)";
            Trait rev = mod.traits().of("SWORD_OF_REVELATIONS");
            ItemDef revDef = Sky.items().def("SWORD_OF_REVELATIONS");
            if (revDef != null && Traits.isOurs(revDef) && (rev.mfVs == null || rev.mfVs.getOrDefault("mythological", 0.0) != 5))
                return "Sword of Revelations should carry +5 Magic Find vs mythological";
            Trait lab = mod.traits().of("DAEDALUS_AXE");
            ItemDef labDef = Sky.items().def("DAEDALUS_AXE");
            if (labDef != null && Traits.isOurs(labDef) && (lab.mythBestiary == null || lab.mythBestiary.mf != 0.2 || lab.mythBestiary.dmg != 1))
                return "Labyrinth Blade should give +1% damage / +0.2 MF per Mythological Bestiary tier";
            if (WeaponMath.bestiaryTier(0, new int[]{10, 25}, 0, 0) != 0 || WeaponMath.bestiaryTier(30, new int[]{10, 25, 50}, 0, 0) != 2
                    || WeaponMath.bestiaryTier(999, new int[]{10, 25, 50}, 25, 0) != 2 || WeaponMath.bestiaryTier(999, new int[]{1, 2, 3}, 0, 2) != 2)
                return "bestiary tier maths changed";
            if (Sky.has(com.skyclone.api.feature.Features.Bestiary.class) && mod.targets().mythFamilies().isEmpty())
                return "progression is loaded but data/progression/bestiary.json gave no mythological_creatures families";
            // a mythological mob def gets the bonus only through the held item: a null player never throws, gives 0
            for (MobDef m : Sky.mobs().all()) {
                if (mod.targets().magicFind(null, m) != 0) return "magicFind(null, " + m.id + ") must be 0";
                break;
            }
            return null;
        });

        Sky.commands().selfTest("weapons.dungeon-class-weapons", () -> {
            List<String> bad = new ArrayList<>();
            for (String id : new String[]{"HYPERION", "VALKYRIE", "ASTRAEA", "SCYLLA", "NECRON_BLADE", "FEL_SWORD", "STONE_BLADE", "EARTH_SHARD"}) {
                ItemDef d = Sky.items().def(id);
                if (d == null || !Traits.isOurs(d)) continue;
                if (!mod.traits().of(id).classAbility) bad.add(id);
            }
            if (!bad.isEmpty()) return "class-ability weapons without the trait: " + bad;
            if (mod.dungeonWeapons() == null) return "DungeonWeapons listener not registered";
            if (WeaponMath.adaptiveMageMana(1000, 25) != 3.75) return "Adaptive Blade (Mage) should restore 25% of 5 + 1% max mana (3.75 at 1,000)";
            if (WeaponMath.healerShare(300, 33) != 99 || WeaponMath.healerShare(-5, 33) != 0) return "Adaptive Blade (Healer) share should be 33%";
            ItemDef fel = Sky.items().def("FEL_SWORD");
            if (fel != null && Traits.isOurs(fel) && !fel.extra.has("dungeonClassBonus")) return "Fel Sword lost its dungeonClassBonus data (dungeons reads it)";
            return null;
        });

        Sky.commands().selfTest("weapons.fear-sources", () -> {
            if (WeaponMath.fearSum(20, 0, 0, 0, 0) != 20) return "4 Great Spook pieces should be 20 Fear";
            if (WeaponMath.fearSum(20, 5, 3, 150, 1) != 43) return "gear 20 + reforge 5 + Dread 3 + Fearcreasing 15 should be 43";
            if (WeaponMath.fearSum(0, 0, 0, 999, 1) != 15) return "Fearcreasing counts at most 150 Primal Fears";
            if (WeaponMath.bestOf(2, 6, 4) != 6) return "only the best Great Spook accessory counts";
            int epic = Passives.greaterSpookFear(com.skyclone.api.item.Rarity.EPIC), leg = Passives.greaterSpookFear(com.skyclone.api.item.Rarity.LEGENDARY);
            if (Sky.plugin().getResource("data/reforges/reforges.json") != null && (epic != 4 || leg != 5))
                return "Greater Spook should give 4 / 5 Fear at EPIC / LEGENDARY (reforges data), got " + epic + " / " + leg;
            ItemDef helm = Sky.items().def("GREAT_SPOOK_HELMET");
            if (helm != null && Passives.otherStat(helm, "FEAR") != 5) return "Great Spook Helmet should carry FEAR 5 in extra.otherStats";
            return null;
        });

        Sky.commands().selfTest("weapons.floor-rolls", () -> {
            int n = 0;
            for (String id : mod.traits().ownedIds()) {
                Trait t = mod.traits().of(id);
                if (t.tiered == null) continue;
                n++;
                ItemDef d = Sky.items().def(id);
                double[] dmg = t.tiered.get("DAMAGE");
                if (dmg != null && d != null && d.damage != dmg[0]) return id + " def damage " + d.damage + " is not the lowest roll " + dmg[0];
            }
            if (Sky.items().exists("ZOMBIE_KNIGHT_SWORD") && Traits.isOurs(Sky.items().def("ZOMBIE_KNIGHT_SWORD")) && n == 0) return "no weapon carries floor rolls";
            if (WeaponMath.tierDelta(new double[]{82, 86, 91}, 0) != 0 || WeaponMath.tierDelta(new double[]{82, 86, 91}, 2) != 9
                    || WeaponMath.tierDelta(new double[]{82, 86, 91}, 9) != 9) return "tierDelta changed";
            return null;
        });

        Sky.commands().selfTest("weapons.wave3-features", () -> {
            // Phasewarp: the Riftwalker Blade carries a sneak ability, the Conduit exists and is ours
            ItemDef aotv = Sky.items().def("ASPECT_OF_THE_VOID");
            if (aotv != null && Traits.isOurs(aotv)) {
                boolean sneak = false;
                for (AbilityDef a : aotv.abilities) if (a.trigger == AbilityDef.Trigger.SNEAK_RIGHT_CLICK) sneak = true;
                if (!sneak) return "Riftwalker Blade has no SNEAK_RIGHT_CLICK Phasewarp";
            }
            ItemDef conduit = Sky.items().def("ETHERWARP_CONDUIT");
            if (conduit == null) return "ETHERWARP_CONDUIT is not registered";
            if (!Sky.items().exists(Teleporters.TUNER)) return Teleporters.TUNER + " is not registered (slayer drop): tuners can not be applied";
            if (!Sky.items().exists(Teleporters.MERGER)) return Teleporters.MERGER + " is not registered (slayer drop): Phasewarp can not be merged";
            // Raise Souls: the two blades hold 2 / 3 souls when the definition is ours
            for (String[] row : new String[][]{{"NECROMANCER_SWORD", "2"}, {"REAPER_SCYTHE", "3"}, {"SUMMONING_RING", "2"}}) {   // ring: materials def, src weapons
                ItemDef d = Sky.items().def(row[0]);
                if (d == null || !Traits.isOurs(d)) continue;
                if (mod.traits().of(row[0]).souls != Integer.parseInt(row[1])) return row[0] + " should hold " + row[1] + " souls";
            }
            if (WeaponMath.soulMana(1) != 24 || WeaponMath.soulMana(500) != 500) return "soul mana formula changed";
            // Labyrinth Blade copies the pet
            ItemDef dae = Sky.items().def("DAEDALUS_AXE");
            if (dae != null && Traits.isOurs(dae) && !mod.traits().of("DAEDALUS_AXE").petCopy) return "Labyrinth Blade lost its pet-stat copy trait";
            if (mod.traits().of("FLAMING_SWORD").ignite <= 0) return "Flaming Sword has no ignite trait";
            return null;
        });

        Sky.commands().selfTest("weapons.items-registered", () -> {
            List<String> missing = new ArrayList<>();
            List<String> foreign = new ArrayList<>();
            for (String id : mod.mainIds()) {
                ItemDef d = Sky.items().def(id);
                if (d == null) missing.add(id);
                else if (!Traits.isOurs(d)) foreign.add(id);
            }
            if (!missing.isEmpty()) return "not registered: " + head(missing);
            if (!foreign.isEmpty()) return "registered by another module first (our behaviour is off for them): " + head(foreign);
            return null;
        });

        Sky.commands().selfTest("weapons.recipe-ingredients-exist", () -> {
            Set<String> bad = new TreeSet<>();
            int n = 0;
            for (Recipe r : Sky.recipes().all()) {
                if (r.id == null || !r.id.startsWith("weapons:")) continue;
                n++;
                if (!Sky.items().exists(r.result)) bad.add(r.result + " (result)");
                if (r.grid != null) for (String cell : r.grid) {
                    if (cell == null || cell.isBlank()) continue;
                    String id = Sky.items().exists(cell) ? cell : cell.substring(0, cell.lastIndexOf(':'));
                    if (!Sky.items().exists(id)) bad.add(id + " in " + r.result);
                }
                if (r.unlock != null && r.unlock.isBlank()) bad.add("blank unlock on " + r.result);
            }
            if (n == 0) return "no weapons recipes registered";
            return bad.isEmpty() ? null : bad.size() + " missing ids: " + head(new ArrayList<>(bad));
        });

        Sky.commands().selfTest("weapons.abilities-have-handlers", () -> {
            List<String> missing = new ArrayList<>();
            for (String id : mod.traits().ownedIds()) {
                ItemDef d = Sky.items().def(id);
                for (AbilityDef a : d.abilities) {
                    switch (a.trigger) {
                        case PASSIVE, ON_HIT, ON_KILL -> { continue; }
                        default -> { }
                    }
                    if (!mod.abilities().registered.contains(a.id)) missing.add(id + ":" + a.id);
                }
            }
            return missing.isEmpty() ? null : "no handler: " + head(missing);
        });

        Sky.commands().selfTest("weapons.formulas-match-research", () -> {
            List<String> bad = new ArrayList<>();
            check(bad, "shortbow 0.5s @0 AS", WeaponMath.shortbowCooldown(0.5, 0, 0.1), 0.5);
            check(bad, "shortbow 0.5s @100 AS", WeaponMath.shortbowCooldown(0.5, 100, 0.1), 0.25);
            check(bad, "shortbow AS capped at 100", WeaponMath.shortbowCooldown(0.5, 400, 0.1), 0.25);
            check(bad, "gilded sword @50M", WeaponMath.midasSwordBonus(50_000_000), 120);
            check(bad, "gilded staff @100M", WeaponMath.midasStaffBonus(100_000_000), 26_000);
            check(bad, "hurricane @20", WeaponMath.hurricaneArrows(20), 2);
            check(bad, "hurricane @250", WeaponMath.hurricaneArrows(250), 5);
            check(bad, "raider dmg @17,500 kills", WeaponMath.raiderDamage(17_500), 35);
            check(bad, "raider dmg cap", WeaponMath.raiderDamage(1_000_000), 35);
            check(bad, "raider str cap", WeaponMath.raiderStrength(10_000_000), 100);
            check(bad, "tactician 11 collections", WeaponMath.tacticianDamage(11), 165);
            check(bad, "shaman 1,000 HP", WeaponMath.perHealth(1000, 50), 20);
            check(bad, "focus burn 1,000 mana DR", WeaponMath.focusBurn(1000)[0], 10);
            check(bad, "focus burn cap", WeaponMath.focusBurn(100_000)[1], 500);
            check(bad, "halberd max 16x", WeaponMath.doubling(10), 16);
            check(bad, "pearl cap 500", WeaponMath.pearlDamage(1_000_000), 500);
            check(bad, "bad health 100%", WeaponMath.badHealthStrength(1000, 1000), 100);
            check(bad, "bounce 1", WeaponMath.bounceMultiplier(1), 0.85);
            // data numbers that must survive the generator
            ItemDef aote = Sky.items().def("ASPECT_OF_THE_END");
            if (aote != null) {
                check(bad, "voidstep blade damage", aote.damage, 100);
                if (aote.abilities.isEmpty() || aote.abilities.get(0).mana != 50) bad.add("voidstep blade mana != 50");
            }
            ItemDef aotd = Sky.items().def("ASPECT_OF_THE_DRAGON");
            if (aotd != null && !aotd.abilities.isEmpty()) check(bad, "wyrm's fury damage", aotd.abilities.get(0).damage, 12_000);
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("weapons.traits-parse", () -> {
            if (!mod.traits().parseErrors().isEmpty()) return "bad traits: " + mod.traits().parseErrors();
            int withW = 0;
            for (String id : mod.traits().ownedIds()) {
                ItemDef d = Sky.items().def(id);
                JsonElement w = d.extra.get("w");
                if (w != null) {
                    withW++;
                    if (Traits.none(mod.traits().of(id))) return id + " has traits that did not load";
                }
            }
            return withW < 40 ? "only " + withW + " weapons carry traits (expected 40+)" : null;
        });

        Sky.commands().selfTest("weapons.mob-types", () -> {
            List<String> bad = new ArrayList<>();
            if (!MobTypes.is("undead", EntityType.ZOMBIE, null)) bad.add("zombie not undead");
            if (!MobTypes.is("arthropod", EntityType.CAVE_SPIDER, null)) bad.add("cave spider not arthropod");
            if (!MobTypes.is("enderman", EntityType.ENDERMAN, null)) bad.add("enderman");
            if (MobTypes.is("undead", EntityType.COW, null)) bad.add("cow undead");
            MobDef sc = new MobDef();
            sc.id = "test_squid";
            sc.flags.add("sea_creature");
            if (!MobTypes.is("sea_creature", EntityType.SQUID, sc)) bad.add("sea creature flag ignored");
            MobDef tagged = new MobDef();
            tagged.id = "test";
            tagged.extra.add("types", new com.google.gson.JsonArray());
            tagged.extra.getAsJsonArray("types").add("spooky");
            if (!MobTypes.is("spooky", EntityType.HUSK, tagged)) bad.add("extra.types ignored");
            return bad.isEmpty() ? null : String.join(", ", bad);
        });

        // Playtest: AOTD in the Graveyard gave no stats and refused every hit ("Requires Catacombs Level 12!").
        // The reference only gates dungeon gear inside dungeon runs, so no weapon of ours may carry plain catacombs:N.
        Sky.commands().selfTest("weapons.catacombs-dungeon-only", () -> {
            List<String> bad = new ArrayList<>();
            for (ItemDef d : Sky.items().all()) {
                if (!Traits.isOurs(d)) continue;
                for (String r : d.requirements) if (r.startsWith("catacombs:")) bad.add(d.id);
            }
            if (!bad.isEmpty()) return "plain catacombs: requirement (enforced everywhere) on " + head(bad);
            ItemDef aotd = Sky.items().def("ASPECT_OF_THE_DRAGON");
            if (aotd != null && Traits.isOurs(aotd) && !aotd.requirements.contains(WeaponsModule.DUNGEON_CATA + ":12"))
                return "ASPECT_OF_THE_DRAGON requirements are " + aotd.requirements + " (want dungeon_catacombs:12)";
            if (mod.dungeonCataCount() < 40) return "only " + mod.dungeonCataCount() + " weapons carry dungeon_catacombs (expected 44)";
            String desc = Sky.requirements().describe(WeaponsModule.DUNGEON_CATA + ":12");
            if (!desc.contains("Catacombs Level 12")) return "dungeon_catacombs is not resolved: " + desc;
            if (WeaponMath.catacombsApplies("dungeon", false) || !WeaponMath.catacombsApplies("dungeon", true)
                    || WeaponMath.catacombsApplies("none", true) || !WeaponMath.catacombsApplies("everywhere", false)
                    || WeaponMath.catacombsApplies("combat", false))
                return "catacombsApplies truth table is wrong";
            if (!WeaponMath.catacombsApplies(WeaponDials.CATACOMBS_REQUIREMENT.get(), false)
                    && !Sky.requirements().check(null, WeaponsModule.DUNGEON_CATA + ":99"))
                return "dungeon_catacombs:99 refused outside a dungeon";
            return null;
        });

        Sky.commands().selfTest("weapons.icons-build", () -> {
            int n = 0;
            for (WeaponMenus.Kind k : WeaponMenus.Kind.values()) {
                for (ItemDef d : mod.menus().items(k)) {
                    Sky.items().create(d.id);
                    n++;
                }
            }
            if (Quiver.arrowTypes().size() < 12) return "only " + Quiver.arrowTypes().size() + " arrow types (expected 12)";
            return n < 100 ? "only " + n + " weapon icons built" : null;
        });
    }

    private static void check(List<String> bad, String what, double got, double want) {
        if (Math.abs(got - want) > 1e-6) bad.add(what + ": got " + got + " want " + want);
    }

    private static String head(List<String> l) {
        return l.size() <= 12 ? String.join(", ", l) : String.join(", ", l.subList(0, 12)) + " ... (+" + (l.size() - 12) + ")";
    }
}
