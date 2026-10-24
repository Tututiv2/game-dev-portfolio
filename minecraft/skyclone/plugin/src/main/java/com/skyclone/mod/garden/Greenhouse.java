package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** The Greenhouse menu (research 08 1.15): plant, water, harvest, mutations, the Crop Analyzer. */
final class Greenhouse {

    private Greenhouse() {}

    /** Expansion costs (research 08 1.15): secondary 100 Ghostvine + 10 Compost Bundles, tertiary 150 + 20. */
    static final int[][] EXPAND = {{100, 10}, {150, 20}};

    static long stageMillis(GardenData d) {
        GardenTables.GreenhouseTable t = G.T.greenhouse;
        double s = GardenMath.stageSeconds(t.baseStageSeconds, t.minStageSeconds, GreenhouseSim.uniqueCrops(d.greenhouse.cells), G.cropGrowth(d), 0, 0);
        return (long) (s * 1000 / Math.max(0.01, G.greenhouseSpeed.get()));
    }

    static void tick(GardenData d) { tick(null, d); }

    /** {@code p} (may be null) is who is looking: their Bioanalysis talisman line raises the mutation odds. */
    static void tick(Player p, GardenData d) {
        GreenhouseSim.ensureCells(d.greenhouse, d.greenhouse.grids);
        double mutation = 1 + Effects.bioanalysisPct(p) / 100.0;
        GreenhouseSim.advance(G.T.greenhouse, d.greenhouse, System.currentTimeMillis(), stageMillis(d), new java.util.Random(), mutation);
    }

    static void open(Player p) { build(p, 0).open(p); }

    static Menu build(Player p, int grid) {
        GardenData d = G.data(p);
        GardenData.GreenhouseState g = d.greenhouse;
        if (!g.unlocked) return locked(p);
        tick(p, d);
        grid = Math.max(0, Math.min(g.grids - 1, grid));
        final int gr = grid;
        GardenTables.GreenhouseTable t = G.T.greenhouse;
        Menu m = new Menu("Greenhouse" + (g.grids > 1 ? " (" + (grid + 1) + "/" + g.grids + ")" : ""), 6).chrome();
        long sm = stageMillis(d);
        m.set(0, Icons.of(Material.GLASS, "&aGreenhouse",
                "&7Crops grow here in stages, even", "&7while you are away. Neighbours", "&7help (or hurt) each other.", "",
                "&7Stage length: &e" + Text.duration(sm / 1000),
                "&7Unique crops planted: &a" + GreenhouseSim.uniqueCrops(g.cells) + " &8(+3% yield each)",
                "&7Harvests: &a" + Text.commasInt(g.harvested)));
        m.set(9, Icons.of(Material.WATER_BUCKET, "&bWater All", "&7Fills every plant's water.", "",
                "&7Uses a &bWatering Can &7from your", "&7inventory, or one Water Bucket.", "", "&eClick to water!"), c -> { water(c.player()); build(c.player(), gr).open(c.player()); });
        m.set(18, Icons.of(Material.CAULDRON, "&bGreenhouse Well", "&7Refill every watering can in", "&7your inventory for free.", "", "&eClick to refill!"),
                c -> { refill(c.player()); build(c.player(), gr).open(c.player()); });
        m.set(27, Icons.of(Material.DIAMOND_HOE, "&aHarvest All", "&7Harvest every ready plant.", "", "&eClick to harvest!"),
                c -> { harvestAll(c.player(), gr); build(c.player(), gr).open(c.player()); });
        m.set(36, Icons.of(Material.SPYGLASS, "&aCrop Analyzer", "&7Mutations analysed: &a" + g.analyzed.size() + "&7/&a" + t.mutations.size(),
                "&7Analyzer level: &a" + analyzerLevel(d) + " &8(&6+" + analyzerLevel(d) * 5 + "☘ Farming Fortune&8)", "", "&eClick to view!"),
                c -> analyzer(c.player(), 0));
        m.set(8, Icons.of(Material.BOOK, "&aHow neighbours work", effectHelp()));
        if (g.grids > 1) m.set(17, Icons.of(Material.ARROW, "&aNext Greenhouse", "&eClick to switch!"), c -> build(c.player(), (gr + 1) % g.grids).open(c.player()));
        if (g.grids <= EXPAND.length) {
            int[] cost = EXPAND[g.grids - 1];
            m.set(26, Icons.of(Material.SCAFFOLDING, "&aBuild Another Greenhouse",
                    "&7Cost: " + G.itemName("ETHEREAL_VINE") + " &8x" + cost[0], "      " + G.itemName("ENCHANTED_COMPOST") + " &8x" + cost[1], "", "&eClick to build!"),
                    c -> expand(c.player()));
        }
        int base = grid * GreenhouseSim.SIZE * GreenhouseSim.SIZE;
        for (int r = 0; r < GreenhouseSim.SIZE; r++)
            for (int col = 0; col < GreenhouseSim.SIZE; col++) {
                int idx = base + r * GreenhouseSim.SIZE + col;
                m.set(r * 9 + 2 + col, cellIcon(d, idx), c -> clickCell(c.player(), idx, gr, c.shift()));
            }
        m.back("The Desk", () -> Desk.build(p));
        return m;
    }

    private static List<String> effectHelp() {
        List<String> l = new ArrayList<>();
        l.add("&7Each crop passes effects to the");
        l.add("&7crops left, right, above and below:");
        l.add("");
        for (GardenTables.GhCrop k : G.T.greenhouse.crops) {
            List<String> names = new ArrayList<>();
            for (String e : k.effects) { GardenTables.Effect ef = G.T.greenhouse.effects.get(e); if (ef != null) names.add(ef.name); }
            GardenTables.Crop c = G.T.crop(k.crop);
            l.add("&e" + (c == null ? k.crop : c.name) + "&7: " + String.join(", ", names));
        }
        l.add("");
        l.add("&7Empty cells beside the right mix");
        l.add("&7of crops can sprout &dmutations&7.");
        return l;
    }

    private static Menu locked(Player p) {
        GardenData d = G.data(p);
        Menu m = new Menu("Greenhouse", 3).chrome();
        boolean lvl = G.level(d) >= 7;
        boolean bp = G.count(p, "GREENHOUSE_BLUEPRINT") > 0;
        m.set(13, Icons.of(Material.GLASS, "&aBuild the Greenhouse",
                "&7A glass house where crops grow in", "&7timed stages, even while you're away.", "",
                (lvl ? "&a✔ " : "&c✖ ") + "&7Garden VII",
                (bp ? "&a✔ " : "&c✖ ") + "&7A &9Greenhouse Blueprint &8(the Carpenter", "&8visitor brings one at Garden VII)", "",
                lvl && bp ? "&eClick to build!" : "&cNot yet!"), c -> {
            GardenData dd = G.data(c.player());
            if (G.level(dd) < 7) { Msg.error(c.player(), "Requires Garden VII!"); return; }
            if (!G.take(c.player(), G.one("GREENHOUSE_BLUEPRINT", 1))) { Msg.error(c.player(), "You need a Greenhouse Blueprint!"); return; }
            dd.greenhouse.unlocked = true;
            GreenhouseSim.ensureCells(dd.greenhouse, 1);
            Msg.box(c.player(), "&b&lGREENHOUSE BUILT", List.of("&7Plant crops in the grid, keep them", "&7watered, and watch for mutations."));
            G.award(c.player(), "greenhouse:built", 10, "Greenhouse");
            Msg.sound(c.player(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
            open(c.player());
        });
        m.back("The Desk", () -> Desk.build(p));
        return m;
    }

    static ItemStack cellIcon(GardenData d, int idx) {
        GardenTables.GreenhouseTable t = G.T.greenhouse;
        GardenData.Cell c = d.greenhouse.cells.get(idx);
        if (c.crop == null) return Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Empty Cell", "&7Plant a crop here, or leave it", "&7empty for a mutation to sprout.", "", "&eClick to plant!");
        if (c.dead) return Icons.of(Material.DEAD_BUSH, "&8Withered " + name(c), "&7It dried out.", "", "&eClick to clear!");
        int cyc = GreenhouseSim.cycles(t, c);
        GreenhouseSim.Received r = GreenhouseSim.received(t, d.greenhouse.cells, idx);
        List<String> lore = new ArrayList<>();
        lore.add("&7Stage: &a" + Math.min(c.stage, cyc) + "&7/&a" + cyc);
        lore.add(Text.bar(Math.min(c.stage, cyc) / (double) cyc, 16));
        lore.add("&7Water: &b" + c.water + "&7/&b" + t.waterMax);
        if (c.stage < cyc) lore.add("&7Next stage: &e" + Text.duration(Math.max(0, (c.nextStageAt - System.currentTimeMillis()) / 1000)));
        lore.add("");
        if (r.yield() != 0) lore.add("&7Yield from neighbours: " + (r.yield() > 0 ? "&a+" : "&c") + Text.commasInt(r.yield()) + "%");
        if (r.xp() != 0) lore.add("&7XP from neighbours: " + (r.xp() > 0 ? "&a+" : "&c") + Text.commasInt(r.xp()) + "%");
        if (r.water() != 0) lore.add("&7Water kept: " + (r.water() > 0 ? "&a+" : "&c") + Text.commasInt(r.water()) + "%");
        if (r.immune()) lore.add("&aImmune to bad effects");
        lore.add("");
        if (GreenhouseSim.ready(t, c)) lore.add("&eClick to harvest!");
        lore.add("&eShift-click to dig it up!");
        ItemStack it;
        if (c.mutation) {
            GardenTables.Mutation mu = G.T.mutation(c.crop);
            it = G.exists(c.crop) ? G.icon(c.crop, 1) : Icons.of(Material.FLOWERING_AZALEA, "&d" + (mu == null ? c.crop : mu.name));
        } else {
            GardenTables.Crop crop = G.T.crop(c.crop);
            it = crop == null ? new ItemStack(Material.WHEAT) : Icons.of(Desk.cropIcon(crop), crop.display());
        }
        it = Icons.withLore(it, lore);
        if (GreenhouseSim.ready(t, c)) Icons.glow(it);
        return it;
    }

    static String name(GardenData.Cell c) {
        if (c.mutation) { GardenTables.Mutation m = G.T.mutation(c.crop); return m == null ? c.crop : m.name; }
        GardenTables.Crop k = G.T.crop(c.crop);
        return k == null ? c.crop : k.name;
    }

    private static void clickCell(Player p, int idx, int grid, boolean shift) {
        GardenData d = G.data(p);
        tick(p, d);
        GardenData.Cell c = d.greenhouse.cells.get(idx);
        if (c.crop == null) { plantMenu(p, idx, grid).open(p); return; }
        if (c.dead || shift) {
            Sky.menus().confirm(p, "Dig up " + name(c) + "?", List.of("&7The cell will be empty again."), () -> {
                GardenData.Cell cc = G.data(p).greenhouse.cells.get(idx);
                cc.crop = null; cc.mutation = false; cc.dead = false; cc.stage = 0; cc.water = 0;
                build(p, grid).open(p);
            }, () -> build(p, grid).open(p));
            return;
        }
        if (GreenhouseSim.ready(G.T.greenhouse, c)) harvest(p, d, idx);
        build(p, grid).open(p);
    }

    static Menu plantMenu(Player p, int idx, int grid) {
        GardenData d = G.data(p);
        GardenTables.GreenhouseTable t = G.T.greenhouse;
        Menu m = new Menu("Plant a Crop", 4).chrome();
        int slot = 10;
        for (GardenTables.GhCrop k : t.crops) {
            if (slot == 17) slot = 19;
            GardenTables.Crop crop = G.T.crop(k.crop);
            if (crop == null) continue;
            boolean ok = G.level(d) >= crop.level;
            List<String> lore = new ArrayList<>();
            lore.add("&7Yield: &a" + k.yield + " " + crop.name);
            lore.add("&7Stages: &a" + k.cycles);
            for (String e : k.effects) { GardenTables.Effect ef = t.effects.get(e); if (ef != null) lore.add("&8» &7" + ef.name + ": " + ef.desc); }
            lore.add("");
            lore.add("&7Cost: " + G.itemName(crop.item) + " &8x" + t.plantCost);
            lore.add(ok ? "&eClick to plant!" : "&cRequires Garden " + GardenMath.roman(crop.level));
            m.set(slot++, Icons.of(Desk.cropIcon(crop), (ok ? "" : "&c") + crop.display(), lore), c -> {
                if (!ok) return;
                GardenData dd = G.data(c.player());
                GardenData.Cell cell = dd.greenhouse.cells.get(idx);
                if (cell.crop != null) { build(c.player(), grid).open(c.player()); return; }
                if (!G.take(c.player(), G.one(crop.item, t.plantCost))) { Msg.error(c.player(), "You need " + t.plantCost + "x " + Text.strip(G.itemName(crop.item)) + "!"); return; }
                cell.crop = k.crop; cell.mutation = false; cell.dead = false; cell.stage = 0; cell.water = t.waterMax;
                cell.nextStageAt = System.currentTimeMillis() + stageMillis(dd);
                Msg.sound(c.player(), Sound.ITEM_CROP_PLANT, 1f);
                build(c.player(), grid).open(c.player());
            });
        }
        m.back("Greenhouse", () -> build(p, grid));
        return m;
    }

    static void harvest(Player p, GardenData d, int idx) {
        GardenTables.GreenhouseTable t = G.T.greenhouse;
        GardenData.Cell c = d.greenhouse.cells.get(idx);
        if (!GreenhouseSim.ready(t, c)) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // rare finds: Overbloom (chance x (1 + Overbloom/100), research 08 1.12); the Rarefinder chip is part of it
        double rare = EffectMath.overbloomChance(1, Effects.overbloom(p, false));
        if (c.mutation) {
            GardenTables.Mutation mu = G.T.mutation(c.crop);
            if (G.exists(c.crop)) G.give(p, c.crop, 1, LootSource.GARDEN);
            if (mu != null && G.exists("ETHEREAL_VINE") && r.nextDouble() < mu.vine * rare) G.give(p, "ETHEREAL_VINE", 1, LootSource.GARDEN);
            if (!d.greenhouse.analyzed.contains(c.crop)) {
                int dnaBefore = analyzerLevel(d);
                d.greenhouse.analyzed.add(c.crop);
                // first-time analysis Copper: 250 a Common mutation, 750 Soggybud (garden_max.md 6.2); the Rose Dragon's Garden
                // Grace ("more copper from visitors and mutation analysis") and the Synthesis chip (+1% a level) add to it
                long cu = Math.round((mu == null ? 250 : mu.copper) * G.analysisCopper.get()
                        * (1 + Math.max(0, Effects.pet(p, "rose_dragon_spiritual_perfection")) / 100.0)
                        * (1 + G.chipValue(d, "analyzer_copper") / 100.0));
                d.copper += Math.max(0, cu);
                Msg.box(p, "&d&lMUTATION ANALYSED &r" + G.itemName(c.crop), List.of("&7Crop Analyzer: &a" + d.greenhouse.analyzed.size() + "&7/&a" + t.mutations.size(),
                        cu > 0 ? "  &8+&c" + Text.commasInt(cu) + " Copper" : ""));
                Sky.stats().invalidate(p);
                G.award(p, "greenhouse:mutation:" + c.crop, 2, "Mutation analysed");
                for (int lv = dnaBefore + 1; lv <= analyzerLevel(d); lv++) dnaMilestone(p, d, lv);
            }
            G.farmingXp(p, t.xpPerHarvest * 2.0, "garden:greenhouse");
        } else {
            GardenTables.Crop crop = G.T.crop(c.crop);
            long y = GreenhouseSim.yield(t, d.greenhouse.cells, idx, G.chipValue(d, "greenhouse"));
            if (crop != null) {
                G.give(p, crop.item, y, LootSource.FARMING);
                CropListener.milestone(p, d, crop, y);
                if ("WHEAT".equals(crop.key) && G.exists("SEEDS")) G.give(p, "SEEDS", 1, LootSource.FARMING);
                // Sowdust (lane 13, wiki): 5 x round(base / 5 x crop multiplier) x (1 + Farming Fortune / 100)
                GardenTables.GhCrop gc = G.T.ghCrop(crop.key);
                double mult = G.T.garden.sowdust.greenhouseMult.getOrDefault(crop.key, 1.0);
                Chips.earn(d, EffectMath.greenhouseSowdust(gc == null ? y : gc.yield, mult, CropListener.fortune(p, d, crop), Chips.boostPct(p)));
            }
            G.farmingXp(p, t.xpPerHarvest * GreenhouseSim.xpMult(t, d.greenhouse.cells, idx), "garden:greenhouse");
            if (GreenhouseSim.effectsOf(t, c).contains("bonus_drops"))
                for (GardenTables.Bonus b : t.bounty)
                    if (G.exists(b.item) && r.nextDouble() < b.chance * rare) {
                        G.give(p, b.item, 1, LootSource.GARDEN);
                        Msg.rareDrop(p, "&6&lHARVEST BOUNTY!", G.itemName(b.item), 0);
                    }
        }
        squash(p, r);
        d.greenhouse.harvested++;
        // replant the same crop (mutations do not replant themselves)
        if (c.mutation) { c.crop = null; c.mutation = false; c.stage = 0; c.water = 0; }
        else { c.stage = 0; c.nextStageAt = System.currentTimeMillis() + stageMillis(d); }
        Msg.sound(p, Sound.BLOCK_CROP_BREAK, 1f);
    }

    static void harvestAll(Player p, int grid) {
        GardenData d = G.data(p);
        tick(p, d);
        int n = 0;
        for (int i = 0; i < d.greenhouse.cells.size(); i++)
            if (GreenhouseSim.ready(G.T.greenhouse, d.greenhouse.cells.get(i))) { harvest(p, d, i); n++; }
        if (n == 0) Msg.error(p, "Nothing is ready yet."); else Msg.success(p, "Harvested &a" + n + " &7plant" + (n == 1 ? "" : "s") + ".");
    }

    // ---- water -------------------------------------------------------------------------------

    static int capacity(ItemDef def) { return def != null && def.extra.has("capacity") ? def.extra.get("capacity").getAsInt() : 0; }

    static int water(ItemStack it, ItemDef def) {
        try {
            ItemData data = Sky.items().data(it);
            if (data.extra.has("water")) return data.extra.get("water").getAsInt();
        } catch (Throwable ignored) { }
        return capacity(def);
    }

    /** Fill every living plant: 100 can water per point, or one water bucket for everything. */
    static void water(Player p) {
        GardenData d = G.data(p);
        tick(p, d);
        GardenTables.GreenhouseTable t = G.T.greenhouse;
        int need = 0;
        for (GardenData.Cell c : d.greenhouse.cells) if (GreenhouseSim.alive(c)) need += Math.max(0, t.waterMax - c.water);
        if (need == 0) { Msg.send(p, "&aEverything is already watered."); return; }
        long cost = need * 100L;
        for (ItemStack it : p.getInventory().getContents()) {
            String id = it == null ? null : Sky.items().idOf(it);
            ItemDef def = id == null ? null : Sky.items().def(id);
            if (def == null || !def.has("watering_can")) continue;
            int have = water(it, def);
            if (have < cost) continue;
            Sky.items().update(it, dd -> dd.extra.addProperty("water", have - cost));
            fill(d);
            Msg.success(p, "Watered with your " + Text.strip(G.itemName(id)) + " &7(-" + Text.commasInt(cost) + " water).");
            Msg.sound(p, Sound.ITEM_BUCKET_EMPTY, 1.2f);
            return;
        }
        int slot = p.getInventory().first(Material.WATER_BUCKET);
        if (slot >= 0) {
            p.getInventory().setItem(slot, new ItemStack(Material.BUCKET));
            fill(d);
            Msg.success(p, "Watered with a bucket.");
            Msg.sound(p, Sound.ITEM_BUCKET_EMPTY, 1f);
            return;
        }
        Msg.error(p, "You need a watering can with " + Text.commasInt(cost) + " water, or a water bucket!");
    }

    private static void fill(GardenData d) {
        for (GardenData.Cell c : d.greenhouse.cells) if (GreenhouseSim.alive(c)) c.water = G.T.greenhouse.waterMax;
    }

    static void refill(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            String id = it == null ? null : Sky.items().idOf(it);
            ItemDef def = id == null ? null : Sky.items().def(id);
            if (def == null || !def.has("watering_can")) continue;
            int cap = capacity(def);
            Sky.items().update(it, dd -> dd.extra.addProperty("water", cap));
            n++;
        }
        if (n == 0) Msg.error(p, "You have no watering can. The Copper Market sells them.");
        else { Msg.success(p, "Refilled " + n + " watering can" + (n == 1 ? "" : "s") + "."); Msg.sound(p, Sound.ITEM_BUCKET_FILL, 1f); }
    }

    static void expand(Player p) {
        GardenData d = G.data(p);
        if (d.greenhouse.grids > EXPAND.length) return;
        int[] cost = EXPAND[d.greenhouse.grids - 1];
        Map<String, Long> need = new LinkedHashMap<>();
        need.put("ETHEREAL_VINE", (long) cost[0]);
        need.put("ENCHANTED_COMPOST", (long) cost[1]);
        if (!G.take(p, need)) { Msg.error(p, "You need " + cost[0] + " Ghostvine and " + cost[1] + " Compost Bundles!"); return; }
        d.greenhouse.grids++;
        GreenhouseSim.ensureCells(d.greenhouse, d.greenhouse.grids);
        Msg.success(p, "Another greenhouse is ready! You now have &a" + d.greenhouse.grids + "&7.");
        build(p, d.greenhouse.grids - 1).open(p);
    }

    // ---- analyzer ---------------------------------------------------------------------------

    /**
     * Cropie armor's Squashbuckle inside the Greenhouse (audit G9; wiki "Cropie Armor"): 4 / 8 / 12% per crop harvested
     * with 2 / 3 / 4 pieces worn. The armor module owns the out-of-greenhouse 0.01-0.03% part.
     */
    static void squash(Player p, ThreadLocalRandom r) {
        GardenTables.GreenhouseSquash gs = G.T.garden.greenhouseSquash;
        if (gs == null || !G.exists(gs.item)) return;
        int pieces = 0;
        for (ItemStack a : p.getInventory().getArmorContents()) {
            String id = a == null ? null : Sky.items().idOf(a);
            if (id != null && id.startsWith(gs.set)) pieces++;
        }
        double chance = EffectMath.greenhouseSquash(pieces, gs.pct) * Math.max(0, G.greenhouseSquashMult.get());
        if (chance <= 0 || r.nextDouble() >= chance) return;
        G.give(p, gs.item, 1, LootSource.FARMING);
        Msg.rareDrop(p, "&6&lRARE CROP!", G.itemName(gs.item), 0);
    }

    /** DNA Analysis Milestone {@code lv} reached: +5 Farming Fortune and +10 Crop Growth (live, from the level), +15 SkyBlock XP, and the Bioanalysis Talisman at I. */
    static void dnaMilestone(Player p, GardenData d, int lv) {
        GardenTables.AnalyzerReward rw = G.T.greenhouse.analyzerReward;
        List<String> body = new ArrayList<>(List.of("&a&lREWARDS", "  &8+&6" + Effects.fmt(rw.fortune) + "☘ Farming Fortune",
                "  &8+&a" + Effects.fmt(rw.cropGrowth) + " Crop Growth", "  &8+&b" + rw.skyblockXp + " SkyBlock XP"));
        String once = "dna:" + lv;
        if (lv == 1 && rw.firstItem != null && G.exists(rw.firstItem) && !d.onceGiven.contains(once)) {
            d.onceGiven.add(once);
            G.give(p, rw.firstItem, 1, LootSource.OTHER);
            body.add("  &8+" + G.itemName(rw.firstItem));
        }
        Msg.box(p, "&d&lDNA ANALYSIS MILESTONE &a" + GardenMath.roman(lv), body);
        G.award(p, "greenhouse:dna:" + lv, rw.skyblockXp, "DNA Analysis Milestone " + GardenMath.roman(lv));
        Sky.stats().invalidate(p);
    }

    static int analyzerLevel(GardenData d) { return GardenMath.analyzerLevel(d.greenhouse.analyzed.size(), G.T.greenhouse.analyzerLevels); }

    static void analyzer(Player p, int page) {
        GardenData d = G.data(p);
        Sky.menus().paged("Crop Analyzer", G.T.greenhouse.mutations, mu -> {
            boolean known = d.greenhouse.analyzed.contains(mu.id);
            List<String> lore = new ArrayList<>();
            lore.add("&8" + mu.rarity + " MUTATION");
            lore.add("");
            lore.add("&7Sprouts in an empty cell next to:");
            for (String n : mu.needs) {
                GardenTables.Crop c = G.T.crop(n);
                GardenTables.Mutation mm = G.T.mutation(n);
                boolean hidden = !known && mm != null && !d.greenhouse.analyzed.contains(n);
                lore.add(" &8- &e" + (c != null ? c.name : hidden ? "&k??????" : mm != null ? mm.name : n));
            }
            lore.add("&7Chance each stage: &a" + Text.commas(mu.chance * 100) + "%");
            lore.add("");
            lore.add(known ? "&aAnalysed!" : "&cNot analysed yet");
            ItemStack it = known && G.exists(mu.id) ? G.icon(mu.id, 1) : Icons.of(Material.GRAY_DYE, known ? "&d" + mu.name : "&7???");
            return Icons.withLore(it, lore);
        }, null, m -> m.back("Greenhouse", () -> build(p, 0)), page).open(p);
    }
}
