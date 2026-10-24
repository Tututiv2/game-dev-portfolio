package com.skyclone.mod.farming;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.gen.Layout;

import java.util.ArrayList;
import java.util.List;

/** Live checks for {@code /sb selftest farming}: data hangs together and the formulas give the research numbers. */
final class FarmingSelfTests {

    private FarmingSelfTests() {}

    static void register(FarmingModule m) {
        Sky.commands().selfTest("farming.items-exist", () -> {
            List<String> bad = new ArrayList<>();
            for (Crop c : Crop.values()) {
                String id = Harvest.resolve(c.dropId, c);
                if (!Sky.items().exists(id)) bad.add("drop " + c + "->" + id);
                if (!Sky.items().exists(c.contestDrop)) bad.add("contest drop " + c.contestDrop);
            }
            for (String id : List.of("JACOBS_TICKET", "THEORETICAL_HOE", "FARMING_FOR_DUMMIES", "ROOKIE_HOE", "TALBOTS_THEODOLITE"))
                if (!Sky.items().exists(id)) bad.add(id);
            for (Recipe r : Sky.recipes().all()) {
                if (r.id == null || !r.id.startsWith("farming:")) continue;
                if (!Sky.items().exists(r.result)) bad.add("recipe result " + r.result);
                for (String cell : r.grid) {
                    if (cell == null || cell.isEmpty()) continue;
                    String id = cell;
                    int c = cell.lastIndexOf(':');
                    if (!Sky.items().exists(cell) && c > 0) id = cell.substring(0, c);
                    if (!Sky.items().exists(id)) bad.add(r.result + " needs " + id);
                }
            }
            return bad.isEmpty() ? null : "missing: " + bad;
        });

        Sky.commands().selfTest("farming.tool-specs", () -> {
            int tools = 0;
            List<String> bad = new ArrayList<>();
            for (ItemDef d : Sky.items().all()) {
                if (d.extra == null || !d.extra.has("farmTool")) continue;
                ToolSpec s = ToolSpec.of(d.id, d.extra);
                if (s == null) { bad.add(d.id + " unparsable"); continue; }
                tools++;
                if (s.mk > 0 && s.crops.isEmpty()) bad.add(d.id + " has no crops");
                if (s.next != null && !Sky.items().exists(s.next)) bad.add(d.id + " -> " + s.next + " missing");
                if (s.mk > 1 && Sky.recipes().producing(d.id).isEmpty()) bad.add(d.id + " has no recipe");
            }
            if (tools < 36 + 12) bad.add("only " + tools + " farming tools registered (expected 48: 12 lines x 3 + 12 generic)");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("farming.fortune-formula", () -> {
            // research 08 1.3: 260 fortune = 2 guaranteed extra + 60% for a third
            if (FortuneMath.roll(1, 260, 0.59) != 4) return "260 FF with roll 0.59 should give 4, gave " + FortuneMath.roll(1, 260, 0.59);
            if (FortuneMath.roll(1, 260, 0.61) != 3) return "260 FF with roll 0.61 should give 3";
            if (FortuneMath.roll(5, 0, 0.0) != 5) return "0 FF should not multiply";
            // research 08 1.4: counter 1,234,567 (7 digits) -> +112
            if (FortuneMath.counterFortune(1_234_567, 16) != 112) return "counter 1,234,567 should be +112";
            // research 08 1.3 theoretical max 3,696.2 fortune = 36 bonus drops + 96.2% of a 37th
            int d = FortuneMath.roll(1, 3696.2, 0.95);
            if (d != 38) return "3,696.2 FF with roll 0.95 should give 1+36+1 = 38, gave " + d;
            return null;
        });

        Sky.commands().selfTest("farming.contest-math", () -> {
            // research 08 1.16: brackets by top percentage of participants
            if (ContestMath.bracket(2, 100, false) != ContestMath.Bracket.DIAMOND) return "#2/100 should be DIAMOND";
            if (ContestMath.bracket(5, 100, false) != ContestMath.Bracket.PLATINUM) return "#5/100 should be PLATINUM";
            if (ContestMath.bracket(10, 100, false) != ContestMath.Bracket.GOLD) return "#10/100 should be GOLD";
            if (ContestMath.bracket(30, 100, false) != ContestMath.Bracket.SILVER) return "#30/100 should be SILVER";
            if (ContestMath.bracket(60, 100, false) != ContestMath.Bracket.BRONZE) return "#60/100 should be BRONZE";
            if (ContestMath.bracket(61, 100, false) != ContestMath.Bracket.NONE) return "#61/100 should be no medal";
            if (ContestMath.Bracket.DIAMOND.tickets != 35 || ContestMath.Bracket.BRONZE.tickets != 10) return "ticket table drifted from research 08 1.16";
            if (ContestMath.crops(42, Crop.contestCrops(false)).size() != 3) return "a contest must have 3 crops";
            if (m.tables.anitaFortune.size() != 15) return "Harvesta's Extra Fortune should have 15 tiers, has " + m.tables.anitaFortune.size();
            if (m.tables.farmingCap.size() != 10) return "the Farming cap should have 10 steps (51-60)";
            if (m.tables.toolLevelXp.length != 49) return "tool levels should be 50 (49 steps), table has " + m.tables.toolLevelXp.length;
            int tm = m.tables.maxToolLevel();
            if (FortuneMath.levelCap(1, 0, 15, 30, 40, 10, tm) != 15 || FortuneMath.levelCap(2, 0, 15, 30, 40, 10, tm) != 30
                    || FortuneMath.levelCap(3, 0, 15, 30, 40, 10, tm) != 40 || FortuneMath.levelCap(3, 10, 15, 30, 40, 10, tm) != 50)
                return "Mk I/II/III caps should be 15/30/40 and 10 Overclockers 50";
            if (m.dials.capMk1.get() != 15 || m.dials.capMk2.get() != 30 || m.dials.capMk3.get() != 40 || m.dials.maxOverclocks.get() != 10)
                Sky.log().warning("[farming] tool-cap dials differ from the defaults 15/30/40 + 10 (saved overrides?)");
            if (!Sky.items().exists(FarmMenus.OVERCLOCKER)) return FarmMenus.OVERCLOCKER + " is not registered (garden owns it)";
            return null;
        });

        Sky.commands().selfTest("farming.mobs-and-spawns", () -> {
            List<String> bad = new ArrayList<>();
            int trap = 0;
            for (MobDef d : Sky.mobs().all()) {
                if (d.extra == null || !(d.extra.has("farmAnimal") || d.extra.has("trapper"))) continue;
                if (d.extra.has("trapper")) trap++;
                for (DropDef dr : d.drops) if (!Sky.items().exists(dr.item)) bad.add(d.id + " drops " + dr.item);
            }
            if (trap != 30) bad.add("expected 30 trapper quarries, found " + trap);
            for (String a : List.of("trapper_cow_trackable", "trapper_horse_elusive", "barn_cow", "desert_mooshroom"))
                if (Sky.mobs().def(a) == null) bad.add("mob " + a);
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("farming.island-anchors", () -> {
            List<String> bad = new ArrayList<>();
            check(bad, "barn", List.of("spawn", "npc.contest_host", "npc.harvesta", "npc.farmhand", "npc.farm_guide",
                    "npc.windmill_operator"), List.of("pen.cow", "pen.pig", "pen.chicken", "pen.sheep", "pen.rabbit", "portal.hub", "portal.desert"));
            check(bad, "mushroom_desert", List.of("spawn", "npc.trapper", "npc.surveyor", "npc.fur_trader", "warp.trapper",
                    "npc.shepherd", "npc.treasure_hunter", "npc.rancher"), List.of("desert.rabbits", "keep.sheep", "gorge.mooshrooms", "portal.hub", "portal.barn"));
            if (m.trapper.areas().isEmpty() && Sky.worlds().layout("mushroom_desert") != null) bad.add("desert has no trapper areas");
            for (String a : m.trapper.areas().keySet()) if (m.trapper.spots(a).isEmpty()) bad.add("trapper area " + a + " has no spots");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("farming.garden-handshake", () -> {
            // The Garden reads a tool's extra.crop_fortune (docs/handoff/garden.md 3b) and fires Gather with its
            // collection ids (3c): both ends of that hand-shake must line up or Garden farming loses tool fortune and
            // Harvest Fair score silently.
            List<String> bad = new ArrayList<>();
            for (ItemDef d : Sky.items().all()) {
                ToolSpec s = d.extra == null ? null : ToolSpec.of(d.id, d.extra);
                if (s == null || s.crops.isEmpty()) continue;
                if (!d.extra.has("crop_fortune") || !d.extra.get("crop_fortune").isJsonObject()) { bad.add(d.id + " has no crop_fortune"); continue; }
                for (Crop c : s.crops) {
                    var cf = d.extra.getAsJsonObject("crop_fortune");
                    if (!cf.has(c.milestoneKey) || cf.get(c.milestoneKey).getAsInt() != s.cropFortune) bad.add(d.id + " crop_fortune." + c.milestoneKey);
                }
            }
            for (String id : List.of("WHEAT", "CARROT_ITEM", "POTATO_ITEM", "PUMPKIN", "SUGAR_CANE", "MELON", "CACTUS", "INK_SACK:3",
                    "RED_MUSHROOM", "BROWN_MUSHROOM", "NETHER_STALK", "DOUBLE_PLANT", "MOONFLOWER", "WILD_ROSE"))
                if (Crop.parse(id) == null) bad.add("garden id " + id + " maps to no crop");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("farming.contest-brackets-reachable", () -> {
            // with the live ghost count, first place must be able to reach Diamond (top 2% rounds UP to one place)
            int n = Math.max(0, m.dials.contestGhosts.get()) + 1;
            if (ContestMath.bracket(1, n, false) != ContestMath.Bracket.DIAMOND) return "#1 of " + n + " is not DIAMOND";
            for (Crop c : Crop.values()) if (m.tables.pbStep(c) <= 0) return "no personal-best step for " + c;
            if (Math.abs(FortuneMath.personalBestFortune(1_000_000, m.tables.pbStep(Crop.WHEAT), 100) - 100) > 1e-9)
                return "1,000,000 Wheat personal best should be the +100 cap";
            return null;
        });

        Sky.commands().selfTest("farming.ghost-pace", () -> {
            // NPC farmers progress smoothly: never backwards, never more than ~2x their average pace in a second,
            // exactly their final score at the end (playtest: they looked like they jumped to it at the end)
            int n = Math.max(1, m.dials.contestGhosts.get());
            double[] fin = ContestMath.ghostFinals(42, Crop.WHEAT, n, m.dials.contestGhostMedian.get(), m.dials.contestGhostSigma.get());
            ContestMath.GhostPace[] pace = ContestMath.ghostPaces(42, Crop.WHEAT, n);
            double[] prev = ContestMath.ghostsAt(fin, pace, 0);
            for (int s = 1; s <= 1200; s++) {
                double[] now = ContestMath.ghostsAt(fin, pace, s / 1200.0);
                for (int i = 0; i < n; i++) {
                    if (now[i] < prev[i]) return "ghost " + i + " went backwards at step " + s;
                    if (now[i] - prev[i] > fin[i] * 2.0 / 1200 + 2) return "ghost " + i + " jumped at step " + s;
                }
                prev = now;
            }
            for (int i = 0; i < n; i++) if (prev[i] != fin[i]) return "ghost " + i + " does not end on its final score";
            if (m.dials.shepherdCount.get() < 1) return "farming.shepherd.sheep must be at least 1";
            return null;
        });

        Sky.commands().selfTest("farming.treasure", () -> {
            if (m.dials.treasureReward.get() < m.dials.treasureCost.get()) return "a treasure pays less than its hint costs (dials)";
            Layout l = Sky.worlds().layout(Treasure.ISLAND);
            if (l == null) return "mushroom_desert has no layout yet";
            boolean zone = false;
            for (Layout.Zone z : l.zones()) if (Treasure.ZONE.equals(z.id())) zone = true;
            if (!zone && l.anchor("npc.treasure_hunter") == null) return "no " + Treasure.ZONE + " zone and no npc.treasure_hunter anchor";
            if (Sky.worlds().isBuilt(Treasure.ISLAND) && m.treasure.pickSpot() == null) return "no diggable ground found in the camp";
            return null;
        });

        Sky.commands().selfTest("farming.shops", () -> {
            List<String> bad = new ArrayList<>();
            for (var e : m.tables.shops.entrySet())
                for (Tables.ShopEntry s : e.getValue()) {
                    if (s.coins == 0 && s.gold == 0 && s.silver == 0 && s.bronze == 0 && s.pelts == 0 && s.items.isEmpty()) bad.add(e.getKey() + ":" + s.result + " is free");
                    // what the farming module itself sells must exist; other modules' items are listed only when present
                    if (e.getKey().equals("farmhand") && !Sky.items().exists(s.result)) bad.add("farmhand sells unknown " + s.result);
                }
            if (!m.tables.shops.containsKey("anita") || m.tables.shops.get("anita").size() < 10) bad.add("Harvesta's stock is missing");
            else {
                // her menu has 21 stock slots; anything past them would silently never be sold (the almanac is last)
                int shown = 0;
                for (Tables.ShopEntry s : m.tables.shops.get("anita")) if (s.medal != null || Sky.items().exists(s.result)) shown++;
                if (shown > 21) bad.add("Harvesta lists " + shown + " entries but her menu shows 21");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        // ---- wave 5: other modules' gear and pets inside farming ----------------------------------------------
        Sky.commands().selfTest("farming.perks-index", () -> {
            List<String> bad = new ArrayList<>();
            // only asserts what is registered: a server without accessories / pets / reforges simply has no perks
            if (Sky.items().exists("FERMENTO_ARTIFACT")) {
                boolean cropie = false, fermento = false, helianthus = !Sky.items().exists("HELIANTHUS_RELIC");
                for (PerkMath.Accessory acc : m.perks.accessories()) {
                    if (acc.id().equals("CROPIE_TALISMAN")) cropie = PerkMath.cropFortune(acc.otherStats(), Crop.WHEAT) > 0;
                    if (acc.id().equals("FERMENTO_ARTIFACT")) fermento = PerkMath.cropFortune(acc.otherStats(), Crop.MELON) > 0
                            && PerkMath.cropFortune(acc.otherStats(), Crop.COCOA) > 0 && PerkMath.cropFortune(acc.otherStats(), Crop.NETHER_WART) > 0;
                    if (acc.id().equals("HELIANTHUS_RELIC")) helianthus = true;   // same family, no crop stats: must still be indexed
                }
                if (!cropie) bad.add("Cropling Talisman's wheat fortune not indexed");
                if (!fermento) bad.add("Fermenta Artifact's melon/cocoa/wart fortune not read (key spellings)");
                if (!helianthus) bad.add("Helianthus Relic not indexed in its family (it must out-rank Fermenta)");
            }
            if (m.perks.indexedReforges() == 0 && Sky.items().reforge("overpriced") != null) bad.add("Overpriced/Thorny Overbloom not read from data/reforges");
            if (m.perks.indexedPets() == 0 && Sky.items().exists("PIG;4")) bad.add("Pig's Potato Fortune not read from data/pets");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("farming.perk-math", () -> {
            List<String> bad = new ArrayList<>();
            if (PerkMath.pelts(10, 2, 1.1) != 13) bad.add("pelts (10+2)x1.1 = " + PerkMath.pelts(10, 2, 1.1) + ", want 13");
            if (PerkMath.pelts(5, 0, 1.0) != 5) bad.add("no bonus changed pelts");
            if (Math.abs(PerkMath.overbloomMultiplier(15, m.dials.overbloomPercent.get()) - (1 + 0.15 * m.dials.overbloomPercent.get())) > 1e-9)
                bad.add("overbloom multiplier");
            if (Math.abs(PerkMath.petLerp(0.2, 20, 100) - 20) > 1e-9 || Math.abs(PerkMath.petLerp(0.2, 20, 1) - 0.2) > 1e-9) bad.add("pet lerp ends");
            if (!"gold".equals(PerkMath.bonusMedal(1, 1, 0)) || !"silver".equals(PerkMath.bonusMedal(0, 1, 0))) bad.add("cape medal kind");
            if (m.dials.crestUpgradePelts.get() < 1) bad.add("crest upgrade needs >= 1 pelt");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("farming.garden-requirements", () -> {
            List<String> bad = new ArrayList<>();
            int withLevel = 0;
            boolean apply = m.dials.toolGardenRequirement.get() && Sky.requirements().known("garden");
            for (ItemDef d : Sky.items().all()) {
                ToolSpec s = d == null ? null : ToolSpec.of(d.id, d.extra);
                if (s == null || s.mk == 0) continue;
                if (s.gardenLevel <= 0) { bad.add(d.id + " has no garden level from research"); continue; }
                withLevel++;
                if (apply && (d.requirements == null || !d.requirements.contains("garden:" + s.gardenLevel))) bad.add(d.id + " missing garden:" + s.gardenLevel);
            }
            ItemDef warts = Sky.items().def("THEORETICAL_HOE_WARTS_1");
            ToolSpec ws = warts == null ? null : ToolSpec.of(warts.id, warts.extra);
            if (ws == null || ws.gardenLevel != 10) bad.add("Nether Wart cutter should need Garden X (research items.json)");
            if (withLevel < 36) bad.add("only " + withLevel + " of 36 crop tools carry a garden level");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("farming.contest-book-name", () -> {
            String id = "ENCHANTMENT_TURBO_WHEAT_1";
            if (!Sky.items().exists(id)) return null;                 // enchanting not registered: nothing to name
            String n = Contests.bookName(id);
            return n.contains("(") && n.contains("I)") ? null : "Harvest Fair book reads '" + n + "' (no enchant named)";
        });

        // ---- wave 5, second pass: Shiny Pigs, Dark Cacao Truffle, pets' info perks ---------------------------------
        Sky.commands().selfTest("farming.shiny-data", () -> {
            List<String> bad = new ArrayList<>();
            MobDef pig = Sky.mobs().def(Shiny.MOB);
            if (pig == null) bad.add("mob shiny_pig not registered");
            else {
                if (!Shiny.FAMILY.equals(pig.familyId())) bad.add("shiny_pig family " + pig.familyId() + " (the bestiary's curated family is shiny_pig)");
                if (!pig.behaviours.contains(Shiny.BEHAVIOUR)) bad.add("shiny_pig lacks its no-damage behaviour");
                if (pig.coins != 0 || pig.combatXp != 0 || (pig.drops != null && !pig.drops.isEmpty())) bad.add("shiny_pig must pay nothing itself (the orb pays)");
            }
            if (!Sky.items().exists(Shiny.ORB)) bad.add("SHINY_ORB not registered");
            int[] th = m.tables.shinyPigTiers;
            if (th.length != 15 || th[th.length - 1] != 400) bad.add("Shiny Pig tiers " + java.util.Arrays.toString(th) + " (NEU bracket 5 to 400 = 15)");
            for (int i = 1; i < th.length; i++) if (th[i] <= th[i - 1]) bad.add("tiers not increasing at " + i);
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("farming.shiny-math", () -> {
            List<String> bad = new ArrayList<>();
            if (ShinyMath.coins(2000, 100, 50, 50, 25) != 13125) bad.add("coins (2000+50x100)x1.5x1.25 = " + ShinyMath.coins(2000, 100, 50, 50, 25));
            if (ShinyMath.coins(2000, 100, 0, 0, 0) != 2000) bad.add("bare coins");
            int[] th = m.tables.shinyPigTiers;
            if (ShinyMath.tier(0, th) != 0 || ShinyMath.tier(4, th) != 3 || ShinyMath.tier(400, th) != th.length || ShinyMath.tier(99_999, th) != th.length)
                bad.add("tier lookup");
            if (ShinyMath.stampedeFortune(5, 15) != 75) bad.add("Shine Rush at tier 15 should be +75 Potato Fortune");
            // flee: away from the player, never further than the roam radius (+ one step) from the orb
            double[] t = ShinyMath.fleeTarget(2, 0, 0, 0, 0, 0, 6, 18);
            if (t[0] <= 2) bad.add("flee did not move away from the player");
            double[] edge = ShinyMath.fleeTarget(17, 0, 10, 0, 0, 0, 6, 18);
            if (Math.hypot(edge[0], edge[1]) > 18 + 6) bad.add("flee left the orb circle: " + Math.hypot(edge[0], edge[1]));
            if (!ShinyMath.delivered(1, 0.5, 1, 2.5) || ShinyMath.delivered(3, 0, 0, 2.5)) bad.add("delivery radius");
            double rnd = m.dials.shinyChance.get();
            if (rnd < 0 || rnd > 0.01) bad.add("farming.shiny.chance " + rnd + " looks wrong (per crop broken)");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("farming.truffle", () -> {
            List<String> bad = new ArrayList<>();
            if (ShinyMath.truffleFortune(3, 5, true, 30) != 33) bad.add("3 eaten + buff = 33");
            if (ShinyMath.truffleFortune(9, 5, false, 30) != 5) bad.add("permanent caps at 5");
            if (ShinyMath.truffleFortune(0, 5, false, 30) != 0) bad.add("nothing eaten = 0");
            if (m.dials.truffleMinutes.get() < 1) bad.add("truffle minutes < 1");
            // materials registers the item (lore only); farming consumes it
            if (Sky.items().exists(FarmListener.TRUFFLE) && !Sky.items().def(FarmListener.TRUFFLE).abilities.isEmpty())
                bad.add("the truffle also has a core ability - two right-click handlers");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("farming.garden-handshake-w5", () -> {
            List<String> bad = new ArrayList<>();
            if (!Sky.requirements().known("harvest_fair")) bad.add("requirement prefix harvest_fair not registered (garden's Redline chip)");
            String desc = Sky.requirements().describe("harvest_fair:COCOA_BEANS");
            if (desc == null || !desc.contains("Cocoa")) bad.add("harvest_fair:COCOA_BEANS describes as '" + desc + "' (garden keys must parse)");
            if (m.dials.cogmindPercent.get() < 0) bad.add("negative Cogmind %");
            if (m.dials.gardenAccessoryBonuses.get() && Sky.requirements().known("garden"))
                bad.add("farming.garden-accessory-bonuses is on while the garden counts accessories itself - they pay twice there");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("farming.pet-perk-keys", () -> {
            if (m.perks.petAbilityKeys.isEmpty()) return null;               // pets data not shipped: nothing to read
            List<String> bad = new ArrayList<>();
            for (String k : Perks.FARMING_PET_PERKS) if (!m.perks.petAbilityKeys.contains(k)) bad.add(k);
            return bad.isEmpty() ? null : "pet perks farming reads but data/pets/pets.json does not have: " + bad;
        });
    }

    private static void check(List<String> bad, String island, List<String> anchors, List<String> areas) {
        Layout l = Sky.worlds().layout(island);
        if (l == null) { bad.add(island + " has no layout yet"); return; }
        for (String a : anchors) if (l.anchor(a) == null) bad.add(island + " anchor " + a);
        for (String a : areas) if (l.area(a) == null) bad.add(island + " area " + a);
        int souls = 0;
        for (String a : l.anchors().keySet()) if (a.startsWith("fairy_soul.")) souls++;
        if (souls < 10) bad.add(island + " has only " + souls + " fairy souls");
    }
}
