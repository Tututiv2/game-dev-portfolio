package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Live self-tests: {@code /sb selftest garden}. */
final class GardenTests {

    private GardenTests() {}

    private static boolean validBlock(String b) {
        try { Bukkit.createBlockData(b.startsWith("minecraft:") ? b : "minecraft:" + b); return true; }
        catch (Throwable t) { return false; }
    }

    static void register() {
        Sky.commands().selfTest("garden.data-loaded", () -> {
            GardenTables t = G.T;
            if (t.crops().size() != 13) return "expected 13 crops, got " + t.crops().size();
            if (t.plots().size() != 24) return "expected 24 plots, got " + t.plots().size();
            if (t.allVisitors().size() != 140) return "expected 140 visitors, got " + t.allVisitors().size();
            if (t.pests().size() != 15) return "expected 15 pests, got " + t.pests().size();
            for (GardenTables.Crop c : t.crops()) if (c.milestones.length != 46) return c.key + " has " + c.milestones.length + " milestone tiers (expected 46)";
            if (t.greenhouse.mutations.size() < 39) return "only " + t.greenhouse.mutations.size() + " mutations";
            return null;
        });
        Sky.commands().selfTest("garden.formulas", () -> {
            long[] lv = G.T.garden.levels;
            if (GardenMath.totalXpFor(15, lv) != 60_120) return "Garden XV should need 60,120 XP (research 08 1.5), got " + GardenMath.totalXpFor(15, lv);
            if (GardenMath.level(10_120, lv) != 10) return "10,120 XP should be Garden X";
            int sum = 0;
            for (int c : G.T.garden.cropUpgrades.copper) sum += c;
            if (sum != 7_685) return "crop upgrades should total 7,685 copper per crop (research 08 1.7), got " + sum;
            GardenTables.PlotGroup b = G.T.group("beginner");
            if (b == null || b.costs.get(2).amount != 4 || !"COMPOST".equals(b.costs.get(2).item)) return "third beginner plot should cost 4 Compost (research 08 1.6)";
            if (GardenMath.fortuneDrops(1, 260, 0.5) != 4 || GardenMath.fortuneDrops(1, 260, 0.7) != 3) return "260 fortune should give 3 drops plus a 60% chance of a 4th (research 08 1.3)";
            if (GardenMath.penalty(G.T.garden.pests.penalty, 0, 5) != 30 || GardenMath.penalty(G.T.garden.pests.penalty, 500, 0) != 30)
                return "pest penalty table does not match research 08 1.12";
            GardenMath.CompostRun r = GardenMath.compost(40_000, 100_000, 0, 3_600, 600, 4_000, 2_000, 1_000);
            if (r.made() != 6) return "a base composter should make 6 compost an hour (research 08 1.11), made " + r.made();
            double st = GardenMath.stageSeconds(14_400, 6_063, 0, 0, 0, 0);
            if (st != 14_400) return "greenhouse base stage should be 4h, got " + st;
            return null;
        });
        Sky.commands().selfTest("garden.own-items", () -> {
            List<String> missing = new ArrayList<>();
            for (String id : List.of("COMPOST", "ENCHANTED_COMPOST", "SKYMART_VACUUM", "SPRAYONATOR", "PEST_REPELLENT", "PEST_TRAP",
                    "GREENHOUSE_BLUEPRINT", "HYDRO_CAN_1000", "GARDEN_SCYTHE", "SAM_SCYTHE", "DUNG", "HONEY_JAR", "PLANT_MATTER", "CHEESE_FUEL", "JELLY"))
                if (!G.exists(id)) missing.add(id);
            for (GardenTables.Mutation m : G.T.greenhouse.mutations) if (!G.exists(m.id)) missing.add(m.id);
            for (String v : G.T.garden.vinyls.keySet()) if (!G.exists(v)) missing.add(v);
            for (String c : G.T.garden.chips.keySet()) if (!G.exists(c)) missing.add(c);
            for (GardenTables.Pest p : G.T.pests()) if (Sky.mobs().def(p.mob) == null) missing.add("mob:" + p.mob);
            return missing.isEmpty() ? null : "garden-owned ids not registered: " + missing;
        });
        Sky.commands().selfTest("garden.crop-items", () -> {
            List<String> missing = new ArrayList<>();
            for (GardenTables.Crop c : G.T.crops()) {
                if (!G.exists(c.item)) missing.add(c.item);
                if (c.enchanted != null && !G.exists(c.enchanted)) missing.add(c.enchanted);
            }
            for (GardenTables.PlotGroup g : G.T.garden.plotGroups) for (GardenTables.Cost cost : g.costs) if (!G.exists(cost.item)) missing.add(cost.item);
            return missing.isEmpty() ? null : "crop / plot cost items missing (materials module?): " + new LinkedHashSet<>(missing);
        });
        Sky.commands().selfTest("garden.market-gates", () -> {
            // the Copper Market's "Requires Garden N" must be the item's own "Requires Garden Level N"
            // (PLAYTEST wave4 #14: shop said VIII over a bracelet whose footer said 6)
            List<String> bad = new ArrayList<>();
            for (GardenTables.ShopEntry e : G.T.shop) {
                var def = G.exists(e.item) ? Sky.items().def(e.item) : null;
                if (def == null) continue;
                int use = 0;
                for (String r : def.requirements)
                    if (r != null && r.matches("garden:\\d+")) use = Math.max(use, Integer.parseInt(r.substring(7)));
                if (use > 0 && use != e.level) bad.add(e.item + " shop " + e.level + " vs item " + use);
            }
            return bad.isEmpty() ? null : "market gate disagrees with the item's own garden requirement: " + bad;
        });
        Sky.commands().selfTest("garden.foreign-items", () -> {
            // items other modules own that garden shows or gives; fails until they are registered
            Set<String> missing = new LinkedHashSet<>();
            for (GardenTables.ShopEntry e : G.T.shop) { if (!G.exists(e.item)) missing.add(e.item); for (String n : e.needs.keySet()) if (!G.exists(n)) missing.add(n); }
            for (GardenTables.CompUpgrade u : G.T.garden.composter.upgrades) for (GardenTables.CompTier t : u.tiers) for (String i : t.items.keySet()) if (!G.exists(i)) missing.add(i);
            for (GardenTables.Pest p : G.T.pests()) {
                var def = Sky.mobs().def(p.mob);
                if (def != null) for (var d : def.drops) if (!G.exists(d.item)) missing.add(d.item);
            }
            for (GardenTables.Visitor v : G.T.allVisitors()) for (String w : v.wants) if (!G.exists(w)) missing.add(w);
            return missing.isEmpty() ? null : missing.size() + " referenced ids not registered by their owners: " + missing;
        });
        Sky.commands().selfTest("garden.anchors", () -> {
            Layout l = Sky.worlds().layout(G.ISLAND);
            if (l == null) return "no garden layout (island not registered?)";
            List<String> need = new ArrayList<>(List.of("spawn", "desk", "composter", "npc.market", "npc.pestwarden", "npc.botanist", "npc.barn_hall"));
            for (int i = 1; i <= 6; i++) need.add("visitor." + i);
            for (GardenTables.Plot p : G.T.plots()) need.add("plot." + p.id);
            List<String> missing = new ArrayList<>();
            for (String a : need) if (l.anchor(a) == null) missing.add(a);
            if (l.area("portal.hub") == null) missing.add("area portal.hub");
            int souls = 0;
            for (String a : l.anchors().keySet()) if (a.startsWith("fairy_soul.")) souls++;
            if (souls < 10) missing.add("only " + souls + " fairy souls");
            for (GardenTables.Plot p : G.T.plots()) {
                boolean z = false;
                for (Layout.Zone zone : l.zones()) if (zone.id().equals("garden_plot_" + p.id) && zone.flags().contains(Layout.Zone.BUILD)) z = true;
                if (!z) missing.add("BUILD zone for " + p.id);
            }
            Layout hub = Sky.worlds().layout("hub");
            if (hub != null && hub.anchor("npc.gardener") == null) missing.add("hub anchor npc.gardener (hub builder)");
            if (hub != null && hub.area("portal.garden") == null) missing.add("hub area portal.garden (hub builder)");
            return missing.isEmpty() ? null : "missing: " + missing;
        });
        Sky.commands().selfTest("garden.island-walkable", () -> {
            IslandRun.Result r = IslandRun.run(new GardenGenerator(), IslandRun.seedOf(G.ISLAND));
            Walkability.Report rep = Walkability.analyse(r.canvas, r.layout, new Walkability.Options());
            if (!rep.ok()) return "garden not walkable: " + rep.failed() + " " + rep.failedPortals() + " " + rep.spawnProblem;
            return null;
        });
        Sky.commands().selfTest("garden.barn-skins", () -> {
            for (GardenTables.BarnSkin s : G.T.garden.barnSkins) if (!BarnBuilder.SKINS.containsKey(s.id)) return "barn skin " + s.id + " has no palette";
            for (String k : BarnBuilder.SKINS.keySet()) if (G.T.barnSkin(k) == null) return "palette " + k + " has no data entry";
            return null;
        });
        Sky.commands().selfTest("garden.block-names", () -> {
            // the Placer turns a block string the server rejects into stone, silently: catch it here
            Set<String> bad = new LinkedHashSet<>();
            GardenTables.Plot any = G.T.plots().get(0);
            for (String preset : PlotPresets.ALL.keySet()) {
                com.skyclone.gen.MemoryCanvas mc = new com.skyclone.gen.MemoryCanvas();
                PlotPresets.write(mc, PlotGrid.inner(any.col, any.row), preset);
                for (String b : mc.paletteEntries()) if (!validBlock(b)) bad.add(preset + ":" + b);
            }
            for (String skin : BarnBuilder.SKINS.keySet()) {
                com.skyclone.gen.MemoryCanvas mc = new com.skyclone.gen.MemoryCanvas();
                BarnBuilder.build(mc, skin, 0L);
                for (String b : mc.paletteEntries()) if (!validBlock(b)) bad.add("skin " + skin + ":" + b);
            }
            for (String skin : GreenhouseBuilder.SKINS.keySet()) {
                com.skyclone.gen.MemoryCanvas mc = new com.skyclone.gen.MemoryCanvas();
                GreenhouseBuilder.build(mc, skin);
                for (String b : mc.paletteEntries()) if (!validBlock(b)) bad.add("greenhouse skin " + skin + ":" + b);
            }
            return bad.isEmpty() ? null : "block strings the server rejects: " + bad;
        });
        // ---- wave 5: other modules' items, pets and reforges in the Garden ----
        Sky.commands().selfTest("garden.effects-math", () -> {
            // the piece thresholds must reproduce the reference items' own lore (research/data/auctions_page0.json)
            if (EffectMath.pieceBonus("LOTUS_BRACELET", 117) != 7 || EffectMath.nextThreshold(117) != 150) return "Peony at 117 served should be +7, next at 150";
            if (EffectMath.pieceBonus("LOTUS_BELT", 480) != 9 || EffectMath.nextThreshold(480) != 500) return "Peony at 480 served should be +9, next at 500";
            if (EffectMath.pieceBonus("BLOSSOM_CLOAK", 1244) != 18 || EffectMath.nextThreshold(1244) != 1500) return "Blossom at 1,244 should be +18, next at 1,500";
            if (EffectMath.pieceBonus("BLOSSOM_BRACELET", 2544) != 22.5 || EffectMath.nextThreshold(2544) != -1) return "Blossom at 2,544 should be maxed at +22.5";
            double c = 0.03, ob = 50, top = EffectMath.topUpChance(c, ob);
            if (Math.abs(c + (1 - c) * top - c * 1.5) > 1e-9) return "Overbloom top-up does not reach chance x (1 + ob/100)";
            if (EffectMath.buzzinBarterer(2, 140) != 175) return "Buzzing Trader must cap at 175";
            if (EffectMath.pesthunterBonus(500, 1, 60) != 60 || EffectMath.pesthunterBonus(12, 1, 60) != 12 || EffectMath.pesthunterBonus(-3, 1, 60) != 0)
                return "Pesthunter's Bonus must be kills x per-pest, capped";
            return null;
        });
        Sky.commands().selfTest("garden.effects-items", () -> {
            // every id the effects read must be registered by its owner, and the pet keys must still exist in the pets data
            List<String> missing = new ArrayList<>();
            for (String f : Effects.FAMILIES) for (String sfx : List.of("_TALISMAN", "_RING", "_ARTIFACT")) if (!G.exists(f + sfx)) missing.add(f + sfx);
            for (String id : List.of("ATMOSPHERIC_FILTER", "LOTUS_BELT", "LOTUS_BRACELET", "LOTUS_CLOAK", "LOTUS_NECKLACE", "BLOSSOM_BELT",
                    "BLOSSOM_BRACELET", "BLOSSOM_CLOAK", "BLOSSOM_NECKLACE", "PESTHUNTERS_BELT", "PESTHUNTERS_CLOAK", "PESTHUNTERS_GLOVES",
                    "PESTHUNTERS_NECKLACE", "PEST_VEST")) if (!G.exists(id)) missing.add(id);
            String pets;
            try (java.io.InputStream in = Sky.plugin().getResource("data/pets/pets.json")) {
                pets = in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) { pets = ""; }
            if (!pets.isEmpty()) for (String k : List.of("mosquito_smooth_jazz", "mosquito_buzzin_barterer", "mosquito_bloodsucker_s_betrayal",
                    "rose_dragon_rosy_scales", "rose_dragon_dragon_s_gluttony", "rose_dragon_spiritual_perfection", "slug_repugnant_aroma",
                    "hedgehog_spiky_quills", "hedgehog_fearsome_farmer")) if (!pets.contains("\"" + k + "\"")) missing.add("pet key " + k);
            return missing.isEmpty() ? null : "garden effects read ids nobody registers: " + missing;
        });
        Sky.commands().selfTest("garden.effects-reforges", () -> {
            // the reforge numbers come from the reforges module's data file; a renamed row would silently zero them
            Effects.reindex();
            Map<String, String> want = Map.of("beady", "VACUUM", "buzzing", "VACUUM", "squeaky", "EQUIPMENT", "overpriced", "FARMING_TOOL");
            List<String> bad = new ArrayList<>();
            for (String r : want.keySet()) if (Sky.items().reforge(r) == null) bad.add(r + " not registered");
            if (Effects.rowValue("beady", com.skyclone.api.item.Rarity.LEGENDARY, 0) != 100) bad.add("beady bonus is not +100 FF vs pests");
            double sq = Effects.rowValue("squeaky", com.skyclone.api.item.Rarity.RARE, 0);
            if (!(sq > 0 && sq <= 10)) bad.add("squeaky bonus value " + sq);
            if (Effects.rowOther("overpriced", com.skyclone.api.item.Rarity.LEGENDARY, "OVERBLOOM") <= 0) bad.add("overpriced has no overbloom");
            return bad.isEmpty() ? null : "reforges the Garden reads: " + bad;
        });
        Sky.commands().selfTest("garden.chips", () -> {
            // every chip's effect kind is one the code applies, with the reference lore's number per level
            Set<String> applied = Set.of("ff", "wisdom", "overbloom", "pest_chance", "greenhouse", "analyzer_copper", "visitor_harvest",
                    "ff_buffs", "contest_fortune", "tool_xp");
            Map<String, Double> lore = Map.of("CROPSHOT_GARDEN_CHIP", 3.0, "VERMIN_VAPORIZER_GARDEN_CHIP", 3.0, "RAREFINDER_GARDEN_CHIP", 1.5,
                    "HYPERCHARGE_GARDEN_CHIP", 3.0, "OVERDRIVE_GARDEN_CHIP", 5.0, "QUICKDRAW_GARDEN_CHIP", 1.5);
            List<String> bad = new ArrayList<>();
            for (Map.Entry<String, GardenTables.Chip> e : G.T.garden.chips.entrySet()) {
                if (!applied.contains(e.getValue().kind)) bad.add(e.getKey() + " kind " + e.getValue().kind);
                if (!G.exists(e.getKey())) bad.add(e.getKey() + " not registered");
                Double want = lore.get(e.getKey());
                if (want != null && Math.abs(want - e.getValue().per) > 1e-9) bad.add(e.getKey() + " per " + e.getValue().per + " (lore " + want + ")");
            }
            if (Math.abs(EffectMath.hypercharge(50, 30) - 15) > 1e-9) bad.add("hypercharge 30% of 50");
            return bad.isEmpty() ? null : "chips: " + bad;
        });
        // ---- lanes 9 / 12 / 13 (2026-10-06): the pest economy, Pests currency, Sowdust and chip levels ----
        Sky.commands().selfTest("garden.pest-economy", () -> {
            List<String> bad = new ArrayList<>();
            // G1: Fly at 2,520 Farming Fortune drops 49 Enchanted Wheat (wiki)
            GardenTables.Pest fly = G.T.pest("fly");
            if (fly == null || EffectMath.pestEnchantedCrops(fly.cropBase, fly.cropScale, 2520, 1, 0.99) != 49) bad.add("Fly at 2,520 FF should drop 49 Enchanted Wheat");
            for (GardenTables.Pest p : G.T.pests()) {
                var def = Sky.mobs().def(p.mob);
                if (def == null) { bad.add("no mob " + p.mob); continue; }
                double compacted = 0;
                boolean rare = false;
                for (var d : def.drops) {
                    if (d.item.equals(p.rareCrop)) { rare = Math.abs(d.chance - 0.15) < 1e-9; if (!G.exists(d.item)) bad.add(p.id + " rare crop " + d.item + " not registered"); }
                    if (d.min >= 3 && d.chance < 0.01) compacted += d.chance;
                }
                boolean wantsRare = p.rareCrop != null && !"RANDOM".equals(p.rareCrop);
                if (wantsRare && !rare) bad.add(p.id + " has no 15% rare crop (G2)");
                if (wantsRare && Math.abs(compacted - 0.0075) > 1e-6) bad.add(p.id + " compacted chance " + compacted + " (G3: 0.75%)");
                if (!"field_mouse".equals(p.id) && p.cropScale <= 0) bad.add(p.id + " has no crop drop row (G1)");
                if (def.coins != ("field_mouse".equals(p.id) ? 10_000 : 1_000)) bad.add(p.id + " drops " + def.coins + " coins");
            }
            GardenTables.Pest ff = G.T.pest("firefly");
            if (ff == null || !ff.nightOnly) bad.add("the Firefly must be night-only (G8)");
            for (GardenTables.ShopEntry e : G.T.shop) if (e.item.equals("PEST_TRAP")) bad.add("Pest Trap is sold for Copper (G7: 50 Pests at the Pestwarden)");
            boolean trap = false;
            for (GardenTables.PestShopEntry e : G.T.garden.pesthunterShop) {
                if (e.item.equals("PEST_TRAP") && e.pests == 50) trap = true;
                if (!PestMenus.available(e)) bad.add("Pestwarden sells unknown " + e.item);
                for (String t : e.tradeIn.keySet()) if (!G.exists(t)) bad.add("Pestwarden trade-in unknown " + t);
            }
            if (!trap) bad.add("the Pestwarden must sell the Pest Trap for 50 Pests");
            int[] uses = {1, 3, 5};
            String[] ids = {"SPRAYONATOR", "JUICY_SPRAYONATOR", "SALTY_SPRAYONATOR"};
            for (int i = 0; i < 3; i++) { GardenTables.Sprayer s = G.T.sprayer(ids[i]); if (s == null || s.uses != uses[i]) bad.add(ids[i] + " should use " + uses[i] + " material"); }
            for (String id : List.of("JUICY_NOZZLE", "SALTY_NOZZLE", "GREEN_BANDANA")) if (!G.exists(id)) bad.add(id + " not registered");
            if (EffectMath.pesthunterBonus(EffectMath.pestsToHandIn(500, 40), 5, 200) != 200) bad.add("Pesthunter's Bonus: 40 Pests x 5 FF = 200");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("garden.sowdust-chips", () -> {
            List<String> bad = new ArrayList<>();
            GardenTables.ChipRules r = G.T.garden.chipRules;
            if (EffectMath.chipCostTo(20, r.sowdustCost) != 25_000_000L) bad.add("a chip should cost 25,000,000 Sowdust from 1 to 20, got " + EffectMath.chipCostTo(20, r.sowdustCost));
            if (EffectMath.chipLevelCost(1, r.sowdustCost) != 100_000 || EffectMath.chipLevelCost(19, r.sowdustCost) != 2_650_000) bad.add("chip level costs 100k (->2) .. 2.65M (->20)");
            if (EffectMath.chipRarity(1, r.chipsFor) != 0 || EffectMath.chipRarity(5, r.chipsFor) != 1 || EffectMath.chipRarity(21, r.chipsFor) != 2) bad.add("rarity at 1 / 5 / 21 chips");
            if (EffectMath.chipCap(0, r.caps) != 10 || EffectMath.chipCap(2, r.caps) != 20) bad.add("caps 10 / 15 / 20");
            GardenTables.Chip vv = G.T.garden.chips.get("VERMIN_VAPORIZER_GARDEN_CHIP");
            if (vv == null || vv.perAt(0) != 3 || vv.perAt(1) != 4 || vv.perAt(2) != 5) bad.add("Vermin Vaporizer 3 / 4 / 5 a level");
            if (EffectMath.cropSowdust(1250, false, 0) != 13 || EffectMath.cropSowdust(1250, true, 0) != 6.5) bad.add("Sowdust ceil(FF/100), halved for cane / cactus");
            if (!G.T.garden.sowdust.halved.containsAll(List.of("SUGAR_CANE", "CACTUS"))) bad.add("halved crops");
            String pets;
            try (java.io.InputStream in = Sky.plugin().getResource("data/pets/pets.json")) {
                pets = in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) { pets = ""; }
            if (!pets.isEmpty() && !pets.contains("\"elephant_abundant_harvest\"")) bad.add("pet key elephant_abundant_harvest (Sowdust boost) gone");
            if (Effects.rowValue("earthy", com.skyclone.api.item.Rarity.RARE, 0) != 5) bad.add("Earthy reforge should give +5% Sowdust");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("garden.temporary-fortune", () -> {
            // the Hypercharge chip reads Farming Fortune off alchemy's effect table; a renamed file or field zeroes it silently
            Effects.reindex();
            try (java.io.InputStream in = Sky.plugin().getResource("data/alchemy/effects.json")) {
                if (in == null) return null;                    // no alchemy data in this build: nothing to read, nothing to check
            } catch (Exception e) { return "effects.json: " + e; }
            Map<String, Map<Integer, Double>> t = Effects.potionFortune();
            if (t.isEmpty()) return "no potion effect carries Farming Fortune";
            Map<Integer, Double> hh = t.get("harvest_harbinger");
            if (hh == null || hh.values().stream().noneMatch(v -> v >= 50)) return "harvest_harbinger should give 50 FF: " + hh;
            return null;
        });
        Sky.commands().selfTest("garden.skin-shapes", () -> {
            // every skin is its own building, the Desk is the same lectern under every roof, and nothing leaves its box
            List<String> bad = new ArrayList<>();
            Set<String> shapes = new LinkedHashSet<>();
            for (String id : BarnBuilder.SKINS.keySet()) {
                com.skyclone.gen.MemoryCanvas mc = new com.skyclone.gen.MemoryCanvas();
                BarnBuilder.build(mc, id, 0L);
                com.skyclone.api.world.gen.Box b = mc.bounds(), x = BarnBuilder.BOX;
                if (b.minX() < x.minX() || b.maxX() > x.maxX() || b.maxY() > x.maxY() || b.minZ() < x.minZ() || b.maxZ() > x.maxZ()) bad.add("barn " + id + " leaves its box");
                String desk = mc.get(BarnBuilder.DESK_X, BarnBuilder.FLOOR_Y + 1, BarnBuilder.DESK_Z);
                if (desk == null || !desk.startsWith("lectern")) bad.add("barn " + id + " lost the Desk");
                if (!BarnBuilder.SHAPES.contains(BarnBuilder.skin(id).shape())) bad.add("barn " + id + " unknown shape");
                shapes.add(BarnBuilder.skin(id).shape());
            }
            if (shapes.size() < BarnBuilder.SHAPES.size()) bad.add("barn shapes unused: " + shapes);
            Set<String> gh = new LinkedHashSet<>();
            for (GreenhouseBuilder.Skin s : GreenhouseBuilder.SKINS.values()) {
                if (!gh.add(s.shape())) bad.add("greenhouse " + s.id() + " repeats " + s.shape());
                com.skyclone.gen.MemoryCanvas mc = new com.skyclone.gen.MemoryCanvas();
                GreenhouseBuilder.build(mc, s.id());
                com.skyclone.api.world.gen.Box b = mc.bounds(), x = GreenhouseBuilder.PLACE_BOX;
                if (b.minX() < x.minX() || b.maxX() > x.maxX() || b.maxY() > x.maxY() || b.minZ() < x.minZ() || b.maxZ() > x.maxZ())
                    bad.add("greenhouse " + s.id() + " leaves its box");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
        Sky.commands().selfTest("garden.menus-build", () -> {
            Player p = null;
            for (Player o : Bukkit.getOnlinePlayers()) if (G.ready(o)) { p = o; break; }
            if (p == null) return null;      // needs a loaded profile; passes vacuously with nobody online
            Desk.build(p); Desk.plots(p, false); Desk.plots(p, true); Desk.milestones(p); Desk.upgrades(p); Desk.levels(p); Desk.skins(p); Desk.greenhouseSkins(p);
            Composter.build(p); Composter.upgrades(p); PestMenus.build(p); PestMenus.traps(p); PestMenus.log(p); PestMenus.pestwarden(p);
            Chips.menu(p); GardenModule.visitors.buildVisitors(p); Greenhouse.build(p, 0); Bonuses.build(p);
            for (Map.Entry<String, GardenData.PlotState> e : G.data(p).plots.entrySet()) { Desk.configure(p, e.getKey()); Desk.presets(p, e.getKey()); break; }
            return null;
        });
    }
}
