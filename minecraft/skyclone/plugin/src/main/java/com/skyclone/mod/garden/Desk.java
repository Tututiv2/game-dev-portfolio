package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.MemoryCanvas;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Desk (research 08 11.13) and everything it opens: plots, crop milestones, crop upgrades,
 * garden levels, barn skins, garden chips. {@code /desk} anywhere, or the lectern in your barn.
 */
final class Desk {

    private Desk() {}

    static void open(Player p) { build(p).open(p); }

    static Menu build(Player p) {
        GardenData d = G.data(p);
        int lvl = G.level(d);
        Menu m = new Menu("Desk", 6).chrome();
        long need = GardenMath.needed(d.xp, G.T.garden.levels);
        double into = GardenMath.into(d.xp, G.T.garden.levels);
        List<String> info = new ArrayList<>();
        info.add("&7Garden Level: &a" + GardenMath.roman(lvl) + (lvl >= G.maxLevel() ? " &6(MAX)" : ""));
        if (need > 0) {
            info.add("&7Progress to " + GardenMath.roman(lvl + 1) + ": &e" + Text.commasInt(into) + "&6/&e" + Text.commasInt(need));
            info.add(Text.bar(into / need, 20));
        }
        info.add("");
        info.add("&7Copper: " + G.copperText(d.copper));
        info.add("&7Crop Growth: &a+" + G.cropGrowth(d));
        info.add("&7Plots owned: &a" + d.plots.size() + "&7/&a" + G.T.plots().size());
        info.add("&7Crops harvested: &a" + Text.commasInt(d.cropsBroken));
        m.set(4, Icons.of(Material.GRASS_BLOCK, "&aThe Garden", info));
        m.set(10, Icons.of(Material.FILLED_MAP, "&aConfigure Plots", "&7Buy, rename, decorate and lay", "&7out farm presets on your plots.", "", "&eClick to view!"),
                c -> plots(c.player(), false).open(c.player()));
        m.set(11, Icons.of(Material.WHEAT, "&aCrop Milestones", "&7Harvest crops on your Garden to", "&7reach milestones for Garden XP.", "", "&eClick to view!"),
                c -> milestones(c.player()).open(c.player()));
        m.set(12, Icons.of(Material.GOLDEN_HOE, "&aCrop Upgrades", "&7Spend Copper on permanent", "&6☘ Crop Fortune&7 for each crop.", "", "&eClick to view!"),
                c -> upgrades(c.player()).open(c.player()));
        m.set(13, Icons.of(Material.COMPOSTER, "&aComposter", "&7Turns crops and fuel into Compost,", "&7the currency that buys plots.", "",
                        "&7Waiting: &a" + composterReady(d) + " Compost", "", "&eClick to open!"),
                c -> Composter.open(c.player()));
        m.set(14, Icons.of(Material.VILLAGER_SPAWN_EGG, "&aVisitors", "&7Visitors waiting: &a" + d.visitors.size(),
                        "&7Unique served: &a" + d.uniqueServed(), "", "&eClick to view!"),
                c -> GardenModule.visitors.openVisitors(c.player()));
        m.set(15, Icons.of(Material.COPPER_INGOT, "&aCopper Market", "&7Garden tools, vacuums, sprays", "&7and gear, mostly for Copper.", "",
                        "&7You have " + G.copperText(d.copper), "", "&eClick to browse!"),
                c -> Market.open(c.player(), "All", 0));
        m.set(16, Icons.of(Material.PAINTING, "&aBarn Skins", "&7Change how your barn looks.", "", "&7Current: &d" + skinName(d.barnSkin), "", "&eClick to view!"),
                c -> skins(c.player()).open(c.player()));
        m.set(20, Icons.of(Material.ENDER_PEARL, "&aTeleport to Plot", "&7Jump straight to any plot you own.", "", "&eClick to pick a plot!"),
                c -> plots(c.player(), true).open(c.player()));
        m.set(21, Icons.of(Material.SILVERFISH_SPAWN_EGG, "&aPests", "&7Live pests: &c" + d.livePests(),
                        lvl < 5 ? "&cPests appear from Garden V" : "&7Hunt them with a vacuum.", "", "&eClick to view!"),
                c -> PestMenus.open(c.player()));
        m.set(22, Icons.of(Material.GLASS, "&aGreenhouse", d.greenhouse.unlocked ? "&7Grow crops while you are away." : "&cNot built yet",
                        d.greenhouse.unlocked ? "" : "&7Needs Garden VII and a", d.greenhouse.unlocked ? "&eClick to open!" : "&9Greenhouse Blueprint&7."),
                c -> Greenhouse.open(c.player()));
        m.set(23, Icons.of(Material.EXPERIENCE_BOTTLE, "&aGarden Levels", "&7What every Garden level gives.", "", "&eClick to view!"),
                c -> levels(c.player()).open(c.player()));
        m.set(24, Icons.of(Material.AMETHYST_SHARD, "&aGarden Chips", "&7Redeemed chips and their levels.", "&7Level them with " + Chips.SOWDUST + "&7.",
                        "", "&7Sowdust: &2" + Text.commasInt(Math.floor(d.sowdust)), "", "&eClick to view!"),
                c -> Chips.menu(c.player()).open(c.player()));
        m.set(25, Icons.of(Material.NETHER_STAR, "&aGarden Bonuses", "&7What your accessories, equipment,", "&7pet and reforges do in the Garden.",
                        "", "&eClick to view!"),
                c -> Bonuses.build(c.player()).open(c.player()));
        boolean here = G.onGarden(p) && G.inOwnGarden(p);
        m.set(31, Icons.of(Material.OAK_DOOR, here ? "&aTeleport to the Barn" : "&aWarp to your Garden",
                        here ? "&7Back to the barn and your Desk." : "&7Travel to your Garden.", "", "&eClick to go!"),
                c -> { c.player().closeInventory(); if (G.onGarden(c.player()) && G.inOwnGarden(c.player())) teleport(c.player(), "spawn"); else Sky.worlds().warp(c.player(), "garden"); });
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        return m;
    }

    static int composterReady(GardenData d) { Composter.advance(d); return d.composter.stored; }

    /** Teleport to an anchor of your own garden, building it first if needed. */
    static void teleport(Player p, String anchor) { teleport(p, anchor, true); }

    /**
     * {@code checkWarp}: from outside the Garden a plot teleport is a warp into it, so it must meet
     * the Garden's warp requirement (otherwise /plottp skipped the SkyBlock Level gate). Admin
     * commands pass false.
     */
    static void teleport(Player p, String anchor, boolean checkWarp) {
        if (checkWarp && !(G.onGarden(p) && G.inOwnGarden(p))) {
            String req = G.requirement.get();
            if (req != null && !req.isBlank() && !Sky.requirements().checkOrTell(p, req)) return;
        }
        String key = G.key(p);
        Sky.worlds().ensureInstance(G.ISLAND, key).whenComplete((loc, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            if (err != null || loc == null) { Msg.error(p, "Your Garden is not ready yet - try again in a moment."); return; }
            Location at = G.anchor(key, anchor);
            p.teleport(at != null ? at : loc);
            p.setFallDistance(0);
        }));
    }

    // =====================================================================================
    // plots
    // =====================================================================================

    static int slotOf(int col, int row) { return row * 9 + 2 + col; }

    static Menu plots(Player p, boolean teleportMode) {
        GardenData d = G.data(p);
        int lvl = G.level(d);
        Menu m = new Menu(teleportMode ? "Teleport to Plot" : "Configure Plots", 6).chrome();
        m.set(slotOf(2, 2), Icons.of(Material.LECTERN, "&aThe Barn", "&7Your Desk, the composter, the", "&7Copper Market and your visitors.", "", "&eClick to teleport!"),
                c -> { c.player().closeInventory(); teleport(c.player(), "spawn"); });
        Map<String, Integer> boughtInGroup = new HashMap<>();
        for (String id : d.plots.keySet()) { GardenTables.Plot bp = G.T.plot(id); if (bp != null) boughtInGroup.merge(bp.group, 1, Integer::sum); }
        for (GardenTables.Plot pl : G.T.plots()) {
            int slot = slotOf(pl.col, pl.row);
            GardenData.PlotState s = d.plots.get(pl.id);
            GardenTables.PlotGroup g = G.T.group(pl.group);
            if (s != null) {
                List<String> lore = new ArrayList<>();
                lore.add("&8" + g.name + " plot");
                if (s.pests > 0) lore.add("&cൠ " + s.pests + " pest" + (s.pests == 1 ? "" : "s") + "!");
                if (s.sprayUntil > System.currentTimeMillis())
                    lore.add("&7Sprayed with " + G.itemName(s.sprayItem) + " &7(" + Text.duration((s.sprayUntil - System.currentTimeMillis()) / 1000) + ")");
                if (s.preset != null && PlotPresets.ALL.containsKey(s.preset)) lore.add("&7Layout: &a" + PlotPresets.ALL.get(s.preset).name());
                lore.add("");
                lore.add("&eClick to teleport!");
                if (!teleportMode) lore.add("&eRight-click to configure!");
                Material icon = s.icon != null ? Material.matchMaterial(s.icon) : null;
                ItemStack it = Icons.of(icon != null ? icon : Material.GRASS_BLOCK, "&a" + G.plotName(d, pl.id), lore);
                if (s.pests > 0) Icons.glow(it);
                m.set(slot, it, c -> {
                    if (c.right() && !teleportMode) { configure(c.player(), pl.id).open(c.player()); return; }
                    c.player().closeInventory();
                    teleport(c.player(), "plot." + pl.id);
                });
            } else if (lvl >= g.level) {
                int idx = GardenMath.plotCostIndex(boughtInGroup.getOrDefault(pl.group, 0), g.costs.size());
                GardenTables.Cost cost = g.costs.get(idx);
                m.set(slot, Icons.of(Material.OAK_SIGN, "&ePlot - " + pl.number,
                        "&8" + g.name + " plot", "",
                        "&7Cost: " + G.itemName(cost.item) + " &8x" + cost.amount,
                        "&7You have: &e" + Text.commasInt(G.count(p, cost.item)),
                        "", "&7Each plot: &6+" + Text.commasInt(G.plotFortune.get()) + "☘ Farming Fortune",
                        "&7and &b+" + Text.commasInt(G.plotSbXp.get()) + " SkyBlock XP&7.",
                        "", "&eClick to unlock!"),
                        c -> buy(c.player(), pl.id));
            } else {
                m.set(slot, Icons.of(Material.BARRIER, "&cPlot - " + pl.number, "&8" + g.name + " plot", "", "&cRequires Garden " + GardenMath.roman(g.level)));
            }
        }
        m.set(50, Icons.of(Material.BOOK, "&aAbout Plots",
                "&7Plots are where you farm. Buy them", "&7with &aCompost &7from the composter;",
                "&7each group gets dearer the more", "&7of it you own.", "",
                "&7Plots you own are yours to build in:", "&7till, water, plant, decorate."));
        m.back("The Desk", () -> build(p));
        return m;
    }

    static void buy(Player p, String plotId) {
        GardenData d = G.data(p);
        GardenTables.Plot pl = G.T.plot(plotId);
        if (pl == null || d.plots.containsKey(plotId)) return;
        GardenTables.PlotGroup g = G.T.group(pl.group);
        if (G.level(d) < g.level) { Msg.error(p, "Requires Garden " + GardenMath.roman(g.level) + "!"); return; }
        GardenTables.Cost cost = nextCost(d, pl.group);
        Sky.menus().confirm(p, "Unlock Plot - " + pl.number + "?", List.of("&7Cost: " + G.itemName(cost.item) + " &8x" + cost.amount), () -> {
            GardenData dd = G.data(p);
            if (dd.plots.containsKey(plotId)) return;
            // re-priced now: a co-op member may have bought a plot of this group meanwhile
            GardenTables.Cost now = nextCost(dd, pl.group);
            if (now.amount != cost.amount || !now.item.equals(cost.item)) { Msg.error(p, "The price changed - check it again."); plots(p, false).open(p); return; }
            if (!G.take(p, G.one(cost.item, cost.amount))) { Msg.error(p, "You need " + cost.amount + "x " + Text.strip(G.itemName(cost.item)) + "!"); return; }
            unlock(p, dd, plotId);
            plots(p, false).open(p);
        }, () -> plots(p, false).open(p));
    }

    /** What the next plot of a group costs, from how many of that group the garden already owns (research 08 1.6). */
    static GardenTables.Cost nextCost(GardenData d, String group) {
        GardenTables.PlotGroup g = G.T.group(group);
        int bought = 0;
        for (String id : d.plots.keySet()) { GardenTables.Plot bp = G.T.plot(id); if (bp != null && bp.group.equals(group)) bought++; }
        return g.costs.get(GardenMath.plotCostIndex(bought, g.costs.size()));
    }

    static void unlock(Player p, GardenData d, String plotId) {
        GardenData.PlotState s = new GardenData.PlotState();
        s.boughtAt = System.currentTimeMillis();
        d.plots.put(plotId, s);
        GardenTables.Plot pl = G.T.plot(plotId);
        for (Player m : G.members(G.key(p))) {
            Msg.box(m, "&a&lPLOT UNLOCKED &ePlot - " + pl.number,
                    List.of("&a&lREWARDS", "  &8+&6" + Text.commasInt(G.plotFortune.get()) + "☘ Farming Fortune",
                            "  &8+&b" + Text.commasInt(G.plotSbXp.get()) + " SkyBlock XP", "",
                            "&7Clear it with a scythe, or lay a", "&7farm preset from the Desk."));
            G.award(m, "plot:" + plotId, (int) Math.round(G.plotSbXp.get()), "Garden plot");
            Sky.stats().invalidate(m);
        }
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.4f);
    }

    static final String[] ICONS = {"GRASS_BLOCK", "WHEAT", "CARROT", "POTATO", "PUMPKIN", "MELON", "SUGAR_CANE", "CACTUS",
            "COCOA_BEANS", "RED_MUSHROOM", "NETHER_WART", "SUNFLOWER", "BLUE_ORCHID", "ROSE_BUSH", "HAY_BLOCK", "WATER_BUCKET",
            "OAK_SAPLING", "FLOWER_POT", "LANTERN", "COMPOSTER", "BEEHIVE"};

    static Menu configure(Player p, String plotId) {
        GardenData d = G.data(p);
        GardenData.PlotState s = d.plots.get(plotId);
        if (s == null) return plots(p, false);
        Menu m = new Menu("Configure " + Text.strip(G.plotName(d, plotId)), 4).chrome();
        GardenTables.Plot pl = G.T.plot(plotId);
        m.set(4, Icons.of(Material.FILLED_MAP, "&a" + G.plotName(d, plotId), "&7Plot number &b" + pl.number,
                "&7Group: &a" + G.T.group(pl.group).name, "&7Pests here: &c" + s.pests));
        m.set(19, Icons.of(Material.NAME_TAG, "&aRename", "&7Give this plot your own name.", "", "&eClick to rename!"),
                c -> Sky.menus().input(c.player(), "Plot name (max 16)", txt -> {
                    if (txt == null) { configure(c.player(), plotId).open(c.player()); return; }
                    String clean = Text.strip(txt).trim();
                    if (clean.length() > 16) clean = clean.substring(0, 16);
                    if (!clean.matches("[A-Za-z0-9 '!?.\\-]*")) { Msg.error(c.player(), "Letters, numbers and spaces only."); return; }
                    GardenData.PlotState st = G.data(c.player()).plots.get(plotId);
                    if (st != null) st.name = clean.isBlank() ? null : clean;
                    Msg.success(c.player(), "Plot renamed.");
                    configure(c.player(), plotId).open(c.player());
                }));
        m.set(20, Icons.of(Material.ITEM_FRAME, "&aChange Icon", "&7Pick the icon on the plot map.", "", "&eClick to choose!"),
                c -> iconPicker(c.player(), plotId).open(c.player()));
        m.set(21, Icons.of(Material.ENDER_PEARL, "&aTeleport", "&eClick to teleport!"),
                c -> { c.player().closeInventory(); teleport(c.player(), "plot." + plotId); });
        m.set(23, Icons.of(Material.STRUCTURE_BLOCK, "&aFarm Presets", "&7Lay out a whole farm in one go:", "&7lanes, water, soil and seedlings.",
                        G.presetsEnabled.get() ? "" : "&cPresets are turned off.", "", "&eClick to choose!"),
                c -> { if (G.presetsEnabled.get()) presets(c.player(), plotId).open(c.player()); });
        m.set(25, Icons.of(Material.WARPED_FUNGUS_ON_A_STICK, "&aSpray",
                s.sprayUntil > System.currentTimeMillis() ? "&7Sprayed with " + G.itemName(s.sprayItem) : "&7Not sprayed.",
                s.sprayUntil > System.currentTimeMillis() ? "&7Time left: &e" + Text.duration((s.sprayUntil - System.currentTimeMillis()) / 1000) : "&7Use a Plot Sprayer while standing here."));
        m.back("Configure Plots", () -> plots(p, false));
        return m;
    }

    static Menu iconPicker(Player p, String plotId) {
        Menu m = new Menu("Plot Icon", 5).chrome();      // 21 icons need rows 1-3 clear of Back/Close
        int slot = 10;
        for (String ic : ICONS) {
            if (slot % 9 == 8) slot += 2;
            Material mat = Material.matchMaterial(ic);
            if (mat == null || !mat.isItem()) continue;
            m.set(slot++, Icons.of(mat, "&a" + Text.titleCase(ic), "&eClick to use!"), c -> {
                GardenData.PlotState st = G.data(c.player()).plots.get(plotId);
                if (st != null) st.icon = ic;
                configure(c.player(), plotId).open(c.player());
            });
        }
        m.back("Configure", () -> configure(p, plotId));
        return m;
    }

    private static final Set<String> BUSY = new HashSet<>();

    static Menu presets(Player p, String plotId) {
        GardenData d = G.data(p);
        Menu m = new Menu("Farm Presets", 6).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (PlotPresets.Preset pr : PlotPresets.ALL.values()) {
            if (i >= slots.length) break;
            GardenTables.Crop crop = pr.crop() == null ? null : G.T.crop(pr.crop());
            boolean ok = crop == null || G.level(d) >= crop.level;
            double cost = pr.id().equals("clear") ? 0 : G.presetCost.get();
            List<String> lore = new ArrayList<>();
            lore.add("&7" + pr.about());
            lore.add("");
            lore.add("&cReplaces everything on the plot!");
            lore.add("&7Cost: " + (cost <= 0 ? "&aFree" : "&6" + Text.commasInt(cost) + " Coins"));
            lore.add("");
            lore.add(ok ? "&eClick to lay it out!" : "&cRequires Garden " + GardenMath.roman(crop.level));
            Material mat = Material.matchMaterial(pr.icon());
            m.set(slots[i++], Icons.of(mat == null ? Material.PAPER : mat, (ok ? "&a" : "&c") + pr.name(), lore),
                    c -> { if (ok) confirmPreset(c.player(), plotId, pr.id(), cost); });
        }
        m.back("Configure", () -> configure(p, plotId));
        return m;
    }

    static void confirmPreset(Player p, String plotId, String preset, double cost) {
        Sky.menus().confirm(p, "Lay out " + PlotPresets.ALL.get(preset).name() + "?",
                List.of("&cEverything on " + Text.strip(G.plotName(G.data(p), plotId)) + " will be replaced!",
                        "&7Cost: " + (cost <= 0 ? "&aFree" : "&6" + Text.commasInt(cost) + " Coins")),
                () -> applyPreset(p, plotId, preset, cost), () -> presets(p, plotId).open(p));
    }

    static void applyPreset(Player p, String plotId, String preset, double cost) {
        String key = G.key(p);
        String job = key + ":" + plotId;
        GardenData d = G.data(p);
        if (!d.plots.containsKey(plotId)) return;
        if (BUSY.contains(job)) { Msg.error(p, "That plot is already being laid out!"); return; }
        PlotPresets.Preset pre = PlotPresets.ALL.get(preset);
        if (pre == null || !G.presetsEnabled.get()) return;
        GardenTables.Crop pc = pre.crop() == null ? null : G.T.crop(pre.crop());
        if (pc != null && G.level(d) < pc.level) { Msg.error(p, "Requires Garden " + GardenMath.roman(pc.level) + "!"); return; }
        if (cost > 0 && !Sky.economy().withdraw(p, cost, "garden: preset " + preset + " on " + plotId)) {
            Msg.error(p, "You need " + Text.commasInt(cost) + " coins!");
            return;
        }
        GardenTables.Plot pl = G.T.plot(plotId);
        Box in = PlotGrid.inner(pl.col, pl.row);
        MemoryCanvas mc = new MemoryCanvas();
        PlotPresets.write(mc, in, preset);
        Box box = new Box(in.minX(), PlotGrid.GROUND_Y - 3, in.minZ(), in.maxX(), PlotGrid.GROUND_Y + 26, in.maxZ());
        BUSY.add(job);
        d.plots.get(plotId).preset = preset.equals("clear") ? null : preset;
        GardenModule.pests.clearBodies(key);
        Msg.send(p, "&7Laying out &a" + PlotPresets.ALL.get(preset).name() + " &7on &a" + G.plotName(d, plotId) + "&7...");
        p.closeInventory();
        UUID who = p.getUniqueId();
        boolean started = Placer.run(key, mc, box, PlotGrid.GROUND_Y, () -> {
            BUSY.remove(job);
            Player q = Bukkit.getPlayer(who);
            if (q != null) Msg.success(q, "Your plot is ready!");
        });
        if (!started) {
            BUSY.remove(job);
            if (cost > 0) Sky.economy().deposit(p, cost, "garden: preset refund " + preset + " on " + plotId);
            Msg.error(p, "Your Garden isn't ready yet - visit it once, then try again.");
        }
    }

    // =====================================================================================
    // crop milestones, crop upgrades, levels
    // =====================================================================================

    static final int[] CROP_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24};

    static Material cropIcon(GardenTables.Crop c) {
        return switch (c.key) {
            case "WHEAT" -> Material.WHEAT; case "CARROT" -> Material.CARROT; case "POTATO" -> Material.POTATO;
            case "PUMPKIN" -> Material.PUMPKIN; case "SUGAR_CANE" -> Material.SUGAR_CANE; case "MELON" -> Material.MELON_SLICE;
            case "CACTUS" -> Material.CACTUS; case "COCOA_BEANS" -> Material.COCOA_BEANS; case "MUSHROOM" -> Material.RED_MUSHROOM;
            case "NETHER_WART" -> Material.NETHER_WART; case "SUNFLOWER" -> Material.SUNFLOWER; case "MOONFLOWER" -> Material.BLUE_ORCHID;
            default -> Material.ROSE_BUSH;
        };
    }

    static Menu milestones(Player p) {
        GardenData d = G.data(p);
        Menu m = new Menu("Crop Milestones", 6).chrome();
        int i = 0;
        for (GardenTables.Crop c : G.T.crops()) {
            long have = d.milestone(c.key);
            int tier = GardenMath.milestoneTier(have, c.milestones);
            int max = c.milestones.length;
            List<String> lore = new ArrayList<>();
            lore.add("&7Milestone: &a" + GardenMath.roman(Math.max(tier, 0)) + " &8(" + tier + "/" + max + ")");
            if (tier < max) {
                long lo = GardenMath.milestoneThreshold(tier, c.milestones), hi = GardenMath.milestoneThreshold(tier + 1, c.milestones);
                lore.add("&7Progress: &e" + Text.commasInt(have - lo) + "&6/&e" + Text.shortNum(hi - lo));
                lore.add(Text.bar((have - lo) / (double) Math.max(1, hi - lo), 20));
                lore.add("");
                lore.add("&7Next: &2+" + GardenMath.milestoneGardenXp(tier + 1) + " Garden XP&7, &b+" + Math.round(G.milestoneSbXp.get()) + " SkyBlock XP");
            } else lore.add("&6All milestones done!");
            lore.add("");
            lore.add("&7Harvested here: &a" + Text.commasInt(have));
            if (G.level(d) < c.level) lore.add("&cGrows from Garden " + GardenMath.roman(c.level));
            ItemStack it = Icons.of(cropIcon(c), c.display(), lore);
            m.set(CROP_SLOTS[i++], Icons.amount(it, Math.max(1, Math.min(64, tier))));
        }
        m.back("The Desk", () -> build(p));
        return m;
    }

    static Menu upgrades(Player p) {
        GardenData d = G.data(p);
        Menu m = new Menu("Crop Upgrades", 6).chrome();
        GardenTables.CropUpgrades cu = G.T.garden.cropUpgrades;
        m.set(4, Icons.of(Material.GOLDEN_HOE, "&aCrop Upgrades", "&7Each tier: &6+" + cu.fortunePerTier + "☘ Crop Fortune",
                "&7for that crop, and &b+1 SkyBlock XP&7.", "", "&7You have " + G.copperText(d.copper)));
        int i = 0;
        for (GardenTables.Crop c : G.T.crops()) {
            int t = d.upgrade(c.key);
            List<String> lore = new ArrayList<>();
            lore.add("&7Tier: &a" + t + "&7/&a" + cu.copper.length);
            lore.add("&7Bonus: &6+" + t * cu.fortunePerTier + "☘ " + c.name + " Fortune");
            lore.add("");
            boolean click = false;
            if (t < cu.copper.length) {
                int req = cu.levels[t];
                lore.add("&7Next tier: " + G.copperText(cu.copper[t]));
                if (G.level(d) < req) lore.add("&cRequires Garden " + GardenMath.roman(req));
                else { lore.add(d.copper >= cu.copper[t] ? "&eClick to upgrade!" : "&cNot enough Copper!"); click = true; }
            } else lore.add("&6Maxed out!");
            final boolean can = click;
            m.set(CROP_SLOTS[i++], Icons.amount(Icons.of(cropIcon(c), c.display(), lore), Math.max(1, t)), x -> { if (can) buyUpgrade(x.player(), c); });
        }
        m.back("The Desk", () -> build(p));
        return m;
    }

    static void buyUpgrade(Player p, GardenTables.Crop c) {
        GardenData d = G.data(p);
        GardenTables.CropUpgrades cu = G.T.garden.cropUpgrades;
        int t = d.upgrade(c.key);
        if (t >= cu.copper.length) return;
        if (G.level(d) < cu.levels[t]) { Msg.error(p, "Requires Garden " + GardenMath.roman(cu.levels[t]) + "!"); return; }
        if (d.copper < cu.copper[t]) { Msg.error(p, "You need " + Text.commasInt(cu.copper[t]) + " Copper!"); return; }
        d.copper -= cu.copper[t];
        d.upgrades.put(c.key, t + 1);
        G.award(p, "crop:upgrade:" + c.key + ":" + (t + 1), 1, c.name + " Upgrade");
        Msg.success(p, c.name + " upgraded to tier &a" + GardenMath.roman(t + 1) + "&7! &6(+" + (t + 1) * cu.fortunePerTier + "☘)");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
        upgrades(p).open(p);
    }

    static Menu levels(Player p) {
        GardenData d = G.data(p);
        int lvl = G.level(d);
        Menu m = new Menu("Garden Levels", 6).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 31};
        long[] per = G.T.garden.levels;
        for (int l = 1; l <= per.length && l <= slots.length; l++) {
            List<String> lore = new ArrayList<>();
            lore.add("&7Needs &e" + Text.commasInt(GardenMath.totalXpFor(l, per)) + " &7total Garden XP");
            lore.add("");
            lore.add("&a&lREWARDS");
            for (GardenTables.Crop c : G.T.crops()) if (c.level == l) lore.add(" &8+&e" + c.name + " &7crop");
            if (l > 1) { lore.add(" &8+&a10 Crop Growth"); lore.add(" &8+&b" + Math.round(G.levelSbXp.get()) + " SkyBlock XP"); }
            int nv = 0;
            for (GardenTables.Visitor v : G.T.allVisitors()) if (v.level == l) nv++;
            if (nv > 0) lore.add(" &8+&a" + nv + " &7visitors");
            for (int i = 0; i < G.T.garden.cropUpgrades.levels.length; i++)
                if (G.T.garden.cropUpgrades.levels[i] == l) lore.add(" &8+&6Crop Upgrade " + GardenMath.roman(i + 1));
            for (GardenTables.CompUpgrade u : G.T.garden.composter.upgrades) if (u.level == l) lore.add(" &8+&2" + u.name);
            for (GardenTables.PlotGroup g : G.T.garden.plotGroups) if (g.level == l) lore.add(" &8+&a" + g.name + " plots");
            if (l == 5) lore.add(" &8+&cPests");
            if (l == 7) lore.add(" &8+&bGreenhouse");
            for (GardenTables.BarnSkin s : G.T.garden.barnSkins) if (("level:" + l).equals(s.source)) lore.add(" &8+&dBarn Skin: " + s.name);
            boolean done = l <= lvl;
            m.set(slots[l - 1], Icons.of(done ? Material.LIME_STAINED_GLASS_PANE : l == lvl + 1 ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    (done ? "&a" : "&c") + "Garden " + GardenMath.roman(l), lore));
        }
        m.back("The Desk", () -> build(p));
        return m;
    }

    // =====================================================================================
    // barn skins
    // =====================================================================================

    static String skinName(String id) {
        GardenTables.BarnSkin s = G.T.barnSkin(id);
        return s == null ? "Default" : s.name;
    }

    static boolean skinOwned(Player p, GardenData d, GardenTables.BarnSkin s) {
        String src = s.source == null ? "free" : s.source;
        if (src.equals("free") || d.barnSkins.contains(s.id)) return true;
        String[] a = src.split(":");
        try {
            return switch (a[0]) {
                case "level" -> G.level(d) >= Integer.parseInt(a[1]);
                case "unique" -> d.uniqueServed() >= Integer.parseInt(a[1]);
                case "milestone" -> d.milestonePaid.getOrDefault(a[1], 0) >= Integer.parseInt(a[2]);
                case "pests" -> G.member(p).pestsKilled >= Long.parseLong(a[1]);
                default -> false;
            };
        } catch (Exception e) { return false; }
    }

    static String skinSource(GardenTables.BarnSkin s) {
        String[] a = (s.source == null ? "free" : s.source).split(":");
        return switch (a[0]) {
            case "free" -> "&aFree";
            case "level" -> "&7Reach &aGarden " + GardenMath.roman(Integer.parseInt(a[1]));
            case "unique" -> "&7Serve &a" + a[1] + " &7unique visitors";
            case "milestone" -> "&7Reach &a" + Text.titleCase(a[1]) + " &7milestone &a" + GardenMath.roman(Integer.parseInt(a[2]));
            case "pests" -> "&7Kill &c" + a[1] + " &7pests";
            case "pesthunter" -> "&7Buy from the Pestwarden for &6" + Text.commasInt(Long.parseLong(a[1])) + " Pests";
            case "copper" -> "&7Buy for " + G.copperText(Long.parseLong(a[1]));
            default -> "&7?";
        };
    }

    static Menu skins(Player p) {
        GardenData d = G.data(p);
        Menu m = new Menu("Barn Skins", 6).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (GardenTables.BarnSkin s : G.T.garden.barnSkins) {
            if (i >= slots.length) break;
            boolean owned = skinOwned(p, d, s);
            boolean cur = s.id.equals(d.barnSkin);
            Material mat = Material.matchMaterial(s.icon == null ? "OAK_PLANKS" : s.icon);
            boolean buyable = !owned && s.source != null && s.source.startsWith("copper:");
            List<String> lore = new ArrayList<>();
            lore.add("&7Style: &f" + BarnBuilder.style(BarnBuilder.skin(s.id).shape()));
            lore.add(skinSource(s));
            lore.add("");
            lore.add(cur ? "&aYour barn wears this!" : owned ? "&eClick to use!" : buyable ? "&eClick to buy!" : "&cLocked");
            ItemStack it = Icons.of(mat == null || !mat.isItem() ? Material.OAK_PLANKS : mat, (owned ? "&a" : "&c") + s.name + " Barn", lore);
            if (cur) Icons.glow(it);
            m.set(slots[i++], it, c -> {
                if (cur) return;
                if (owned) { applySkin(c.player(), s.id); return; }
                if (buyable) {
                    long price = Long.parseLong(s.source.substring(7));
                    Sky.menus().confirm(c.player(), "Buy " + s.name + " Barn?", List.of("&7Cost: " + G.copperText(price)), () -> {
                        GardenData dd = G.data(c.player());
                        if (dd.copper < price) { Msg.error(c.player(), "You need " + Text.commasInt(price) + " Copper!"); return; }
                        dd.copper -= price;
                        dd.barnSkins.add(s.id);
                        Msg.success(c.player(), "You bought the " + s.name + " barn skin!");
                        skins(c.player()).open(c.player());
                    }, () -> skins(c.player()).open(c.player()));
                }
            });
        }
        GreenhouseBuilder.Skin gs = GreenhouseBuilder.skin(d.greenhouseSkin);
        m.set(50, Icons.of(Material.GLASS, "&aGreenhouse Skins", "&7Change how your greenhouse looks.", "",
                "&7Current: &d" + gs.name(), "", "&eClick to view!"), c -> greenhouseSkins(c.player()).open(c.player()));
        m.back("The Desk", () -> build(p));
        return m;
    }

    // =====================================================================================
    // greenhouse skins (cosmetic; same unlock grammar as barn skins)
    // =====================================================================================

    private static GardenTables.BarnSkin card(GreenhouseBuilder.Skin s) {
        GardenTables.BarnSkin b = new GardenTables.BarnSkin();
        b.id = s.id(); b.name = s.name(); b.icon = s.icon(); b.source = s.source();
        return b;
    }

    static Menu greenhouseSkins(Player p) {
        GardenData d = G.data(p);
        Menu m = new Menu("Greenhouse Skins", 4).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (GreenhouseBuilder.Skin gs : GreenhouseBuilder.SKINS.values()) {
            if (i >= slots.length) break;
            GardenTables.BarnSkin s = card(gs);
            boolean owned = d.greenhouseSkins.contains(s.id) || skinOwned(p, d, s);
            boolean cur = s.id.equals(d.greenhouseSkin == null ? "default" : d.greenhouseSkin);
            Material mat = Material.matchMaterial(s.icon);
            boolean buyable = !owned && s.source.startsWith("copper:");
            List<String> lore = new ArrayList<>();
            lore.add("&7Style: &f" + GreenhouseBuilder.style(gs.shape()));
            lore.add(skinSource(s));
            lore.add("");
            lore.add(cur ? "&aYour greenhouse wears this!" : owned ? "&eClick to use!" : buyable ? "&eClick to buy!" : "&cLocked");
            ItemStack it = Icons.of(mat == null || !mat.isItem() ? Material.GLASS : mat, (owned ? "&a" : "&c") + s.name, lore);
            if (cur) Icons.glow(it);
            m.set(slots[i++], it, c -> {
                if (cur) return;
                if (owned) { applyGreenhouseSkin(c.player(), s.id); return; }
                if (buyable) {
                    long price = Long.parseLong(s.source.substring(7));
                    Sky.menus().confirm(c.player(), "Buy " + s.name + "?", List.of("&7Cost: " + G.copperText(price)), () -> {
                        GardenData dd = G.data(c.player());
                        if (dd.greenhouseSkins.contains(s.id)) { greenhouseSkins(c.player()).open(c.player()); return; }
                        if (dd.copper < price) { Msg.error(c.player(), "You need " + Text.commasInt(price) + " Copper!"); return; }
                        dd.copper -= price;
                        dd.greenhouseSkins.add(s.id);
                        Msg.success(c.player(), "You bought the " + s.name + " greenhouse skin!");
                        greenhouseSkins(c.player()).open(c.player());
                    }, () -> greenhouseSkins(c.player()).open(c.player()));
                }
            });
        }
        m.back("Barn Skins", () -> skins(p));
        return m;
    }

    static void applyGreenhouseSkin(Player p, String skinId) {
        String key = G.key(p);
        if (REBUILDING.contains(key)) { Msg.error(p, "Your Garden is still being rebuilt!"); return; }
        GardenData d = G.data(p);
        d.greenhouseSkin = skinId;
        rebuildGreenhouse(key, skinId, null);
        Msg.success(p, "Your greenhouse is getting a makeover: &d" + GreenhouseBuilder.skin(skinId).name() + "&a!");
        p.closeInventory();
    }

    /** Rebuild one instance's greenhouse in a skin (also used by the admin command). */
    static void rebuildGreenhouse(String key, String skinId, Runnable after) {
        REBUILDING.add(key);
        MemoryCanvas mc = new MemoryCanvas();
        GreenhouseBuilder.build(mc, skinId);
        placeWhenBuilt(key, mc, GreenhouseBuilder.PLACE_BOX, after);
    }

    /**
     * Copy a rebuilt building in once the instance itself is built (wave 5: an admin skin change on a Garden nobody
     * had visited yet wrote into an empty slot, and the first visit then built the default barn over it).
     */
    private static void placeWhenBuilt(String key, MemoryCanvas mc, com.skyclone.api.world.gen.Box box, Runnable after) {
        Runnable done = () -> { REBUILDING.remove(key); if (after != null) after.run(); };
        Sky.worlds().ensureInstance(G.ISLAND, key).whenComplete((loc, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (err != null || loc == null || !Placer.run(key, mc, box, PlotGrid.GROUND_Y, done)) REBUILDING.remove(key);
        }));
    }

    private static final Set<String> REBUILDING = new HashSet<>();

    static void applySkin(Player p, String skinId) {
        String key = G.key(p);
        if (REBUILDING.contains(key)) { Msg.error(p, "Your barn is still being rebuilt!"); return; }
        GardenData d = G.data(p);
        d.barnSkin = skinId;
        rebuildBarn(key, skinId, null);
        Msg.success(p, "Your barn is getting a makeover: &d" + skinName(skinId) + "&a!");
        p.closeInventory();
    }

    /** Rebuild one instance's barn in a skin (also used by the admin command). */
    static void rebuildBarn(String key, String skinId, Runnable after) {
        REBUILDING.add(key);
        MemoryCanvas mc = new MemoryCanvas();
        BarnBuilder.build(mc, skinId, 0L);
        placeWhenBuilt(key, mc, BarnBuilder.BOX, after);
    }
}
