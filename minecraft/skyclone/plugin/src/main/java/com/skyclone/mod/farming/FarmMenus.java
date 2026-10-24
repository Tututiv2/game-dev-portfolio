package com.skyclone.mod.farming;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Every farming menu: the Farming hub, crop guide + fortune breakdown, the tool bench and the Harvest Fair. */
final class FarmMenus {

    private final FarmingModule m;

    FarmMenus(FarmingModule m) { this.m = m; }

    static Material icon(Crop c) {
        return switch (c) {
            case WHEAT -> Material.WHEAT;
            case CARROT -> Material.CARROT;
            case POTATO -> Material.POTATO;
            case PUMPKIN -> Material.PUMPKIN;
            case MELON -> Material.MELON_SLICE;
            case SUGAR_CANE -> Material.SUGAR_CANE;
            case CACTUS -> Material.CACTUS;
            case COCOA -> Material.COCOA_BEANS;
            case MUSHROOM -> Material.RED_MUSHROOM;
            case NETHER_WART -> Material.NETHER_WART;
            case SUNFLOWER -> Material.SUNFLOWER;
            case MOONFLOWER -> Material.BLUE_ORCHID;
            case WILD_ROSE -> Material.ROSE_BUSH;
        };
    }

    static String where(Crop c) {
        return switch (c) {
            case WHEAT, CARROT, POTATO -> "Hub Farm, The Barn";
            case PUMPKIN, MELON -> "The Barn";
            case SUGAR_CANE, COCOA -> "The Barn, Mushroom Desert oasis";
            case CACTUS -> "Mushroom Desert";
            case MUSHROOM -> "Mushroom Desert gorge";
            case NETHER_WART -> "The Barn (wart cellar)";
            case SUNFLOWER, MOONFLOWER, WILD_ROSE -> "The Barn flower meadow, the Garden";
        };
    }

    private void backToSkyMenu(Menu menu) {
        if (Sky.has(Features.SkyMenu.class))
            menu.set(menu.closeSlot() - 1, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
    }

    // =================================================================================================
    // main
    // =================================================================================================

    void openMain(Player p) {
        FarmData d = m.data(p);
        Menu menu = new Menu("Farming", 6).chrome();
        SkillService sk = Sky.service(SkillService.class);
        int lvl = sk.level(p, SkillType.FARMING);
        double ff = Sky.stats().get(p, Stat.FARMING_FORTUNE);
        long broken = 0;
        for (long v : d.cropsBroken.values()) broken += v;
        List<String> head = new ArrayList<>();
        head.add("&7Farming level: &a" + lvl + " &7(cap &a" + Math.max(50, sk.cap(p, SkillType.FARMING)) + "&7)");
        if (sk.needed(p, SkillType.FARMING) > 0)
            head.add("&7Progress: &e" + Text.shortNum(sk.progress(p, SkillType.FARMING)) + "&6/&e" + Text.shortNum(sk.needed(p, SkillType.FARMING)));
        head.add("&6☘ Farming Fortune: &a" + Text.commasInt(ff));
        head.add("&3☯ Farming Wisdom: &a" + Text.commasInt(Sky.stats().get(p, Stat.FARMING_WISDOM)));
        head.add("");
        head.add("&7Crops harvested: &e" + Text.commasInt(broken));
        head.add("&7Medals: &6" + d.gold + " &f" + d.silver + " &c" + d.bronze + "   &7Pelts: &5" + Text.commasInt(d.pelts));
        head.add("");
        head.add("&eClick to view your Farming skill!");
        menu.set(4, Icons.of(Material.GOLDEN_HOE, "&aFarming", head), c -> Sky.service(SkillService.class).openMenu(c.player()));

        menu.set(19, Icons.of(Material.WHEAT, "&aCrop Guide", "&7Every crop: base drops, XP, where", "&7to farm it and your fortune for it.", "", "&eClick to view!"),
                c -> openCrops(c.player()));
        ItemStack held = p.getInventory().getItemInMainHand();
        ToolTracker.View v = m.tools.view(p, held);
        menu.set(20, Icons.of(v != null ? held.getType() : Material.IRON_HOE, "&aTool Bench",
                v != null ? List.of("&7Holding: " + Sky.items().def(Sky.items().idOf(held)).name, "&7Level &a" + v.level()
                        + "&7, counter &e" + Text.commasInt(v.counter()), "", "&eClick to open!")
                        : List.of("&7Hold a farming tool to see its", "&7counter, level and upgrades.", "", "&eClick to open!")),
                c -> openTool(c.player()));
        long left = m.contests.millisLeft(), next = m.contests.millisUntilNext();
        menu.set(21, Icons.of(Material.CLOCK, "&eHarvest Fair", List.of(
                m.contests.active ? "&aRunning now! &7" + Text.duration(left / 1000) + " left" : "&7Next fair in &e" + Text.duration(next / 1000),
                m.contests.active ? "&7Crops: " + m.contests.cropList() : "&7Medals and tickets every hour.", "", "&eClick to view!")),
                c -> openContest(c.player()));
        menu.set(22, Icons.of(Material.GOLD_INGOT, "&dHarvesta's Medal Exchange", "&7Extra Farming Fortune, the Farming", "&7level cap, tilling hoes and more.",
                "", "&eClick to open!"), c -> m.shops.openAnita(c.player()));
        menu.set(23, Icons.of(Material.LEAD, "&aTrapper", List.of(d.quest != null ? "&7Hunting: &f" + Text.titleCase(d.quest.rarity) + " " + Text.titleCase(d.quest.animal)
                : "&7Hunt animals in the Mushroom Desert", "&7Pelts: &5" + Text.commasInt(d.pelts), "", "&eClick to view!")), c -> m.trapper.openQuest(c.player()));
        menu.set(24, Icons.of(Material.CRAFTING_TABLE, "&aFarming Tools Recipes", "&7Every hoe, dicer, knife, chopper", "&7and cutter, and how to get it.", "", "&eClick to view!"),
                c -> openToolList(c.player()));
        menu.set(25, Icons.of(Material.EMERALD, "&aFarmhand's Counter", "&7Rookie tools and the Mathematical", "&7Hoe Blueprint.", "", "&eClick to shop!"),
                c -> m.shops.openFarmhand(c.player()));

        Shiny.Hunt hunt = m.shiny == null ? null : m.shiny.hunt(p);
        List<String> shinyLore = new ArrayList<>(List.of("&7They appear while you farm. Catch", "&7one and bring it back to its orb.",
                "&7Delivered: &d" + d.shinyPigsCaught));
        if (hunt != null) shinyLore.add(hunt.caught ? "&aYou caught one - bring it to the orb!" : "&eOne is on the loose - catch it!");
        shinyLore.add("");
        shinyLore.add("&eClick to view!");
        menu.set(29, Icons.of(Material.PORKCHOP, "&dShiny Pigs", shinyLore), c -> m.shiny.open(c.player()));
        menu.set(30, Icons.of(Material.HAY_BLOCK, "&aWarp to The Barn", List.of("&7Wheat, carrots, potatoes, pumpkins,", "&7melons, cocoa, cane and animal pens.",
                "", req(p, m.dials.barnRequirement.get()))), c -> { c.player().closeInventory(); FarmingModule.warp(c.player(), "barn"); });
        menu.set(31, Icons.of(Material.BRUSH, "&6Buried Treasure", List.of(d.treasure != null ? "&aYou have a hint to dig up!"
                        : "&7Digger Nim sells hints to treasure", "&7buried in the Mushroom Desert.", "&7Found: &e" + d.treasuresFound, "", "&eClick to view!")),
                c -> m.treasure.open(c.player()));
        menu.set(32, Icons.of(Material.RED_SAND, "&aWarp to the Mushroom Desert", List.of("&7Cactus, mushrooms, the oasis and", "&7the trapper's den.",
                "", req(p, m.dials.desertRequirement.get()))), c -> { c.player().closeInventory(); FarmingModule.warp(c.player(), "desert"); });
        menu.set(33, Icons.of(Material.SHEARS, "&aShepherd Maela", List.of("&7Shear her sheep against the clock", "&7at the Shepherd's Keep for a fixed purse.",
                "&7Tasks finished: &e" + d.shearingTasksDone, "", "&eClick to view!")), c -> m.shepherd.open(c.player()));
        backToSkyMenu(menu);
        menu.open(p);
    }

    private String req(Player p, String key) {
        if (key == null || key.isBlank()) return "&eClick to warp!";
        if (Sky.requirements().check(p, key)) return "&eClick to warp!";
        return "&cRequires " + Sky.requirements().describe(key);
    }

    // =================================================================================================
    // crops
    // =================================================================================================

    void openCrops(Player p) {
        FarmData d = m.data(p);
        Menu menu = new Menu("Crop Guide", 6).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24};
        ItemStack held = p.getInventory().getItemInMainHand();
        for (int i = 0; i < Crop.values().length; i++) {
            Crop c = Crop.values()[i];
            double f = m.harvest.fortune(p, c, held, "red").total();
            List<String> lore = new ArrayList<>();
            lore.add("&7Base drops: &a" + (c.minDrops == c.maxDrops ? "" + c.minDrops : c.minDrops + "-" + c.maxDrops) + " &7per block");
            lore.add("&7Farming XP: &3" + Text.commas(c.xp) + " &7per block");
            lore.add("&7Regrows in: &a" + Text.commas(c.regrowSeconds * m.dials.regrowMultiplier.get()) + "s");
            lore.add("&7Found at: &b" + where(c));
            lore.add("");
            lore.add("&6☘ Your fortune: &a" + Text.commasInt(f) + " &7(≈" + Text.commas(Math.round(FortuneMath.expected(c.averageDrops(), f) * 10) / 10.0) + " per block)");
            lore.add("&7Harvested: &e" + Text.commasInt(d.broken(c)) + " &7blocks, &e" + Text.commasInt(d.collected(c)) + " &7items");
            long pb = d.personalBest.getOrDefault(c.name(), 0L);
            if (pb > 0) lore.add("&7Harvest Fair best: &d" + Text.commasInt(pb) + " " + ContestMath.Bracket.parse(d.bestBracket.get(c.name())).colored());
            lore.add("");
            lore.add("&eClick for the fortune breakdown!");
            menu.set(slots[i], Icons.of(icon(c), "&a" + c.display, lore), cl -> openCrop(cl.player(), c));
        }
        menu.set(40, Icons.of(Material.BOOK, "&aHow Farming Fortune works", List.of(
                "&7Every &6100☘ &7of fortune is one more", "&7guaranteed drop per crop; the rest", "&7is the chance of one extra.", "",
                "&7Example: &6260☘ &7= 2 extra drops and", "&7a &a60% &7chance of a third.", "",
                "&7Crop tools add fortune for &aone&7 crop;", "&7everything else adds it for all crops.")));
        menu.set(menu.closeSlot() - 1, Icons.back("Farming"), c -> openMain(c.player()));
        menu.open(p);
    }

    void openCrop(Player p, Crop c) {
        Menu menu = new Menu(c.display + " Fortune", 4).chrome();
        ItemStack held = p.getInventory().getItemInMainHand();
        Harvest.Fortune f = m.harvest.fortune(p, c, held, c == Crop.MUSHROOM ? fungiMode(held) : "red");
        List<String> lore = new ArrayList<>();
        for (Harvest.Part part : f.parts()) lore.add("&7" + part.source() + ": &6+" + Text.commas(Math.round(part.value() * 10) / 10.0) + "☘");
        lore.add("");
        lore.add("&7Total: &6" + Text.commas(Math.round(f.total() * 10) / 10.0) + "☘");
        long g = (long) Math.floor(f.total() / 100);
        double ch = f.total() / 100 - g;
        lore.add("&7= &a" + g + " &7extra drop" + (g == 1 ? "" : "s") + " + &a" + Math.round(ch * 100) + "% &7for one more");
        lore.add("&7Expected per block: &e" + Text.commas(Math.round(FortuneMath.expected(c.averageDrops(), f.total()) * 100) / 100.0));
        lore.add("");
        lore.add("&8Uses the item in your hand. Hold a");
        lore.add("&8different tool and reopen to compare.");
        menu.set(13, Icons.of(icon(c), "&6☘ " + c.display + " Fortune", lore));
        // Overbloom: the rare-crop chance (wave 5) - Cropling / Squash / Fermenta / Helianthus during a Harvest Fair
        List<String> ob = new ArrayList<>();
        List<Harvest.Part> obParts = m.perks == null ? List.of() : m.perks.overbloomParts(p);
        for (Harvest.Part part : obParts) ob.add("&7" + part.source() + ": &a+" + Text.commas(Math.round(part.value() * 100) / 100.0));
        double obTotal = 0;
        for (Harvest.Part part : obParts) obTotal += part.value();
        if (ob.isEmpty()) ob.add("&8No Overbloom from your gear or pet.");
        ob.add("");
        ItemDef rare = Sky.items().def(c.contestDrop);
        ob.add("&7Harvest Fair rare crops (&f" + (rare == null ? Text.titleCase(c.contestDrop) : Text.strip(rare.name)) + "&7)");
        ob.add("&7are &a" + Math.round((PerkMath.overbloomMultiplier(obTotal, m.dials.overbloomPercent.get()) - 1) * 100) + "% &7more likely.");
        menu.set(15, Icons.of(Material.PINK_PETALS, "&d✾ Overbloom " + Text.commas(Math.round(obTotal * 100) / 100.0), ob));
        menu.set(30, Icons.back("Crop Guide"), cl -> openCrops(cl.player()));
        menu.open(p);
    }

    private static String fungiMode(ItemStack held) {
        if (held == null || Sky.items().idOf(held) == null) return "red";
        return ToolSpec.fungiOf(Sky.items().data(held).extra);
    }

    // =================================================================================================
    // tools
    // =================================================================================================

    void openTool(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        ToolTracker.View v = m.tools.view(p, held);
        Menu menu = new Menu("Tool Bench", 5).chrome();
        if (v == null) {
            menu.set(22, Icons.of(Material.BARRIER, "&cNo farming tool in hand", "&7Hold a hoe, dicer, knife, chopper", "&7or cutter, then reopen."));
            menu.set(31, Icons.of(Material.CRAFTING_TABLE, "&aAll farming tools", "&eClick to view!"), c -> openToolList(c.player()));
            menu.set(menu.closeSlot() - 1, Icons.back("Farming"), c -> openMain(c.player()));
            menu.open(p);
            return;
        }
        ToolSpec s = v.spec();
        ItemDef def = Sky.items().def(Sky.items().idOf(held));
        menu.set(4, held.clone());
        // level
        if (s.leveling) {
            double[] prog = FortuneMath.toolProgress(v.xp(), m.tables.toolLevelXp, v.cap());
            menu.set(20, Icons.of(Material.EXPERIENCE_BOTTLE, "&aTool Level " + v.level(), List.of(
                    prog[1] > 0 ? "&7Next: &e" + Text.commasInt(prog[0]) + "&6/&e" + Text.commasInt(prog[1]) : "&6&lMAXED",
                    prog[1] > 0 ? Text.bar(prog[0] / prog[1], 20) : "",
                    "", "&7Each level above 1 adds &6+" + m.dials.levelFortune.get() + "☘", "&7Crop Fortune. Levels come from", "&7collecting crops with this tool.",
                    "", "&7Current bonus: &6+" + FortuneMath.levelFortune(v.level(), m.dials.levelFortune.get()) + "☘",
                    "&7Level cap: &a" + v.cap() + (s.mk > 0 ? " &8(Mk " + "I".repeat(Math.min(3, s.mk)) + (v.overclocks() > 0 ? " + " + v.overclocks() + " Overclocker" + (v.overclocks() == 1 ? "" : "s") : "") + ")" : ""),
                    Perks.toolXpPercent(p) > 0 ? "&7Orchid Mantis &aBright Mind&7: &b+" + Text.commasInt(Perks.toolXpPercent(p)) + "% tool XP" : "")));
        }
        // Overclocker 3000 (Mk III only): +1 level cap each, needs the tool at its Mk III cap (NEU: "Requires tool level 40+")
        if (s.leveling && s.mk >= 3) {
            int maxOc = m.dials.maxOverclocks.get(), haveOc = Shops.have(p, OVERCLOCKER);
            ItemDef ocDef = Sky.items().def(OVERCLOCKER);
            String ocName = ocDef == null ? "Overclocker" : ocDef.rarity.code() + Text.strip(ocDef.name);
            menu.set(30, Icons.of(Material.COMPARATOR, ocName, List.of("&7Applied: &a" + v.overclocks() + "&7/&a" + maxOc + "  &7(+1 level cap each)",
                    "&7Needs tool level &a" + m.dials.capMk3.get() + "&7.", "&7In your inventory: &e" + haveOc, "",
                    v.overclocks() >= maxOc ? "&a&lMAXED" : haveOc > 0 ? "&eClick to apply one!" : "&cYou have none.")), c -> applyOverclocker(c.player()));
        }
        // counter
        if (s.counter || s.analysis) {
            List<String> l = new ArrayList<>();
            l.add("&7Counter: &e" + Text.commasInt(v.counter()));
            if (s.counter) l.add("&7Logarithmic Counter: &6+" + FortuneMath.counterFortune(v.counter(), m.dials.counterPerDigit.get()) + "☘");
            if (s.analysis && !s.crops.isEmpty()) {
                long coll = Sky.service(Features.Collections.class).count(p, s.crops.get(0).collectionId);
                l.add("&7Collection Analysis: &6+" + FortuneMath.analysisFortune(coll, m.dials.analysisPerDigit.get()) + "☘ &8(" + Text.commasInt(coll) + ")");
            }
            long nextDigit = (long) Math.pow(10, FortuneMath.digits(v.counter()));
            l.add(""); l.add("&7Next digit at &e" + Text.commasInt(nextDigit));
            menu.set(21, Icons.of(Material.COMPARATOR, "&aCounter", l));
        } else {
            menu.set(21, Icons.of(Material.GRAY_DYE, "&7No counter", "&7Mk. II hoes and up keep a", "&7Logarithmic Counter."));
        }
        // almanac
        int alm = v.almanac(), maxAlm = m.dials.maxAlmanac.get();
        int have = Shops.have(p, "FARMING_FOR_DUMMIES");
        menu.set(22, Icons.of(Material.BOOK, "&5Farmer's Almanac", List.of("&7Applied: &a" + alm + "&7/&a" + maxAlm + "  &6(+" + alm + "☘ Farming Fortune)",
                "&7In your inventory: &e" + have, "", alm >= maxAlm ? "&a&lMAXED" : have > 0 ? "&eClick to apply one!" : "&cYou have no almanacs.",
                "&8Harvesta sells them.")), c -> applyAlmanac(c.player()));
        // fungi
        if (s.kind.equals("fungi")) {
            String mode = ToolSpec.fungiOf(Sky.items().data(held).extra);
            menu.set(23, Icons.of(mode.equals("red") ? Material.RED_MUSHROOM : Material.BROWN_MUSHROOM, "&aMode: " + (mode.equals("red") ? "&cRed" : "&6Brown"),
                    "&7The chosen colour gets &6+" + m.dials.fungiBonus.get() + "☘&7.", "", "&eClick to switch!"), c -> {
                        ItemStack h = c.player().getInventory().getItemInMainHand();
                        if (Sky.items().idOf(h) == null) return;
                        String nx = ToolSpec.fungiOf(Sky.items().data(h).extra).equals("red") ? "brown" : "red";
                        m.tools.flush(c.player());
                        Sky.items().update(h, dd -> dd.extra.add(ToolSpec.K_FUNGI, new JsonPrimitive(nx)));
                        openTool(c.player());
                    });
        }
        // upgrade path
        if (s.next != null && Sky.items().exists(s.next)) {
            ItemDef nd = Sky.items().def(s.next);
            List<String> l = new ArrayList<>();
            l.add("&7Next: " + nd.rarity.code() + Text.strip(nd.name));
            for (Recipe r : Sky.recipes().producing(s.next)) {
                for (String cell : r.grid) {
                    if (cell == null || cell.isEmpty() || cell.startsWith(Sky.items().idOf(held))) continue;
                    l.add(" &8- &7" + cellName(cell));
                }
                break;
            }
            l.add(""); l.add("&7Counter, level and almanacs carry"); l.add("&7over when you craft the upgrade."); l.add(""); l.add("&eClick to view the recipe!");
            menu.set(24, Icons.of(Material.ANVIL, "&aUpgrade", dedupe(l)), c -> { c.player().closeInventory(); Sky.commands().run(c.player(), "recipe", s.next); });
        } else if (s.mk == 3) {
            menu.set(24, Icons.of(Material.NETHER_STAR, "&6Fully upgraded", "&7This is the top of its line."));
        }
        // fortune summary for its crops
        List<String> fl = new ArrayList<>();
        for (Crop c : s.crops.isEmpty() ? List.of(Crop.WHEAT) : s.crops)
            fl.add("&7" + c.display + ": &6" + Text.commasInt(m.harvest.fortune(p, c, held, "red").total()) + "☘");
        menu.set(31, Icons.of(Material.GOLDEN_CARROT, "&6Fortune with this tool", fl));
        menu.set(menu.closeSlot() - 1, Icons.back("Farming"), c -> openMain(c.player()));
        menu.open(p);
    }

    private static List<String> dedupe(List<String> l) { return new ArrayList<>(new java.util.LinkedHashSet<>(l)); }

    private static String cellName(String cell) {
        int c = cell.lastIndexOf(':');
        String id = cell, amt = "1";
        if (c > 0 && cell.substring(c + 1).matches("\\d+") && !Sky.items().exists(cell)) { id = cell.substring(0, c); amt = cell.substring(c + 1); }
        ItemDef d = Sky.items().def(id);
        return (d == null ? Text.titleCase(id) : d.rarity.code() + Text.strip(d.name)) + " &8x" + amt;
    }

    static final String OVERCLOCKER = "OVERCLOCKER_3000";

    private void applyOverclocker(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        ToolTracker.View v = m.tools.view(p, held);
        if (v == null || !v.spec().leveling) { Msg.error(p, "Hold a farming tool."); return; }
        String why = FortuneMath.overclockRefusal(v.spec().mk, v.level(), v.overclocks(), m.dials.capMk3.get(), m.dials.maxOverclocks.get());
        if (why != null) { Msg.error(p, why); return; }
        if (!Shops.take(p, OVERCLOCKER, 1)) { Msg.error(p, "You don't have an Overclocker."); return; }
        m.tools.flush(p);
        Sky.items().update(held, d -> d.extra.add(ToolSpec.K_OVERCLOCK, new JsonPrimitive(ToolSpec.overclockOf(d.extra) + 1)));
        Msg.success(p, "Overclocked! Level cap is now &a" + m.tools.view(p, held).cap() + "&a.");
        Msg.sound(p, Sound.BLOCK_ANVIL_USE, 1.4f);
        openTool(p);
    }

    private void applyAlmanac(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        ToolTracker.View v = m.tools.view(p, held);
        if (v == null) { Msg.error(p, "Hold a farming tool."); return; }
        if (!m.dials.almanacAnywhere.get()) { Msg.error(p, "Bring it to Harvesta to apply it."); return; }
        if (v.almanac() >= m.dials.maxAlmanac.get()) { Msg.error(p, "This tool already has the maximum almanacs."); return; }
        if (!Shops.take(p, "FARMING_FOR_DUMMIES", 1)) { Msg.error(p, "You don't have a Farmer's Almanac."); return; }
        m.tools.flush(p);
        Sky.items().update(held, d -> d.extra.add(ToolSpec.K_ALMANAC, new JsonPrimitive(ToolSpec.almanacOf(d.extra) + 1)));
        Sky.stats().invalidate(p);
        Msg.success(p, "Applied a Farmer's Almanac! &6+1☘ Farming Fortune");
        Msg.sound(p, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.2f);
        openTool(p);
    }

    void openToolList(Player p) {
        List<ItemDef> tools = new ArrayList<>();
        for (ItemDef d : Sky.items().all()) if (d.extra != null && (d.extra.has("farmTool") || d.extra.has("tilling"))
                && "farming".equals(d.extra.has("owner") ? d.extra.get("owner").getAsString() : "")) tools.add(d);
        tools.sort((a, b) -> {
            ToolSpec sa = ToolSpec.of(a.id, a.extra), sb = ToolSpec.of(b.id, b.extra);
            String la = sa == null ? "zz" + a.id : sa.line + sa.mk, lb = sb == null ? "zz" + b.id : sb.line + sb.mk;
            return la.compareTo(lb);
        });
        Sky.menus().paged("Farming Tools", tools, d -> {
            ItemStack it;
            try { it = Sky.items().create(d.id); } catch (Exception e) { it = new ItemStack(Material.IRON_HOE); }
            return Icons.withLore(it, List.of("", "&eClick to view recipes!"));
        }, pc -> { pc.click().player().closeInventory(); Sky.commands().run(pc.click().player(), "recipe", pc.entry().id); },
                page -> page.set(page.closeSlot() - 1, Icons.back("Farming"), c -> openMain(c.player())), 0).open(p);
    }

    // =================================================================================================
    // Harvest Fair
    // =================================================================================================

    void openContest(Player p) {
        Menu menu = new Menu("Harvest Fair", 6).chrome();
        menu.refreshEvery(20).drawer(mm -> drawContest(p, mm));
        menu.open(p);
    }

    private void drawContest(Player p, Menu menu) {
        FarmData d = m.data(p);
        Contests ct = m.contests;
        // status
        List<String> st = new ArrayList<>();
        if (ct.active) {
            st.add("&aThe fair is running! &7" + Text.duration(ct.millisLeft() / 1000) + " left");
            st.add("&7Crops: " + ct.cropList());
            st.add("");
            if (d.contestIndex == ct.index && d.contestCrop != null) {
                Crop c = Crop.parse(d.contestCrop);
                st.add("&7You compete with: &a" + (c == null ? d.contestCrop : c.display));
                st.add("&7Collected: &6" + Text.commasInt(d.contestScore));
                int[] pos = ct.standing(p);
                if (d.contestScore < m.dials.contestMinScore.get()) st.add("&7Collect &e" + m.dials.contestMinScore.get() + " &7to be ranked.");
                else if (pos != null) {
                    ContestMath.Bracket b = ContestMath.bracket(pos[0], pos[1], ct.goated());
                    st.add("&7Position: &e#" + pos[0] + " &7of &e" + pos[1] + "  " + b.colored());
                    st.add("&8Where you'd place if the fair ended now. NPC");
                    st.add("&8farmers keep farming to the end - so keep pace!");
                    if (b != ContestMath.Bracket.DIAMOND) {
                        ContestMath.Bracket nb = ContestMath.Bracket.values()[b.ordinal() + 1];
                        double need = ContestMath.scoreFor(nb, ct.othersNow(c, p.getUniqueId()), ct.goated());
                        st.add("&7Next: " + nb.colored() + " &7at &e" + Text.commasInt(need));
                    }
                }
            } else st.add(ct.eligibleLevel(p) ? "&eFarm one of the crops to join!" : "&cRequires Farming " + Text.roman(m.dials.contestMinLevel.get()) + ".");
        } else {
            st.add("&7Next fair in &e" + Text.duration(ct.millisUntilNext() / 1000));
            st.add("&7Every fair picks 3 crops and lasts");
            st.add("&e" + m.dials.contestDuration.get() + " minutes&7. Farm on the farming");
            st.add("&7islands or in your Garden.");
        }
        menu.set(4, Icons.of(Material.CLOCK, "&eHarvest Fair", st));

        // live crop boards
        if (ct.active) {
            int[] slots = {11, 13, 15};
            for (int i = 0; i < ct.crops.size() && i < 3; i++) {
                Crop c = ct.crops.get(i);
                List<String> l = new ArrayList<>();
                List<Object[]> top = ct.leaderboard(c, 5);
                int k = 1;
                for (Object[] row : top) l.add("&e#" + k++ + " " + ((boolean) row[2] ? "&7" : "&a") + row[0] + " &6" + Text.commasInt((long) row[1]));
                l.add("");
                l.add("&7Real farmers: &a" + ct.participants(c));
                l.add("");
                l.add("&eClick for the full leaderboard!");
                menu.set(slots[i], Icons.of(icon(c), "&a" + c.display, l), cl -> openLeaderboard(cl.player(), c));
            }
        } else {
            for (int s : new int[]{11, 13, 15}) menu.set(s, Icons.filler());
        }

        // schedule
        List<String> sch = new ArrayList<>();
        long now = System.currentTimeMillis();
        long idx = ContestMath.index(now, ct.period(), ct.offset());
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm");
        for (int k = 0, shown = 0; shown < 5 && k < 8; k++) {
            long i = idx + k;
            long start = ContestMath.startOf(i, ct.period(), ct.offset());
            if (start + ct.duration() <= now) continue;
            List<Crop> cs = ContestMath.crops(i, ct.eligible());
            StringBuilder sb = new StringBuilder();
            for (Crop c : cs) { if (sb.length() > 0) sb.append("&7, "); sb.append("&a").append(c.display); }
            String when = start <= now ? "&anow" : "&ein " + Text.duration((start - now) / 1000);
            sch.add("&7" + fmt.format(new Date(start)) + " " + when + "&7: " + sb);
            shown++;
        }
        sch.add("");
        sch.add("&8Times are server time.");
        menu.set(22, Icons.of(Material.WRITABLE_BOOK, "&aUpcoming Fairs", sch));

        // medals & bests
        List<String> med = new ArrayList<>();
        med.add("&6Gold: &e" + d.gold + "  &fSilver: &e" + d.silver + "  &cBronze: &e" + d.bronze);
        med.add("&7Fairs entered: &e" + d.contestsJoined);
        med.add("&7Unique gold crops: &6" + d.uniqueGolds.size());
        med.add("");
        for (ContestMath.Bracket b : ContestMath.Bracket.values())
            if (b != ContestMath.Bracket.NONE) med.add(b.colored() + "&7: &e" + d.medalsEarned.getOrDefault(b.name(), 0));
        med.add(""); med.add("&eClick to open Harvesta's exchange!");
        menu.set(29, Icons.of(Material.GOLD_INGOT, "&6Your Medals", med), c -> m.shops.openAnita(c.player()));

        List<String> pbs = new ArrayList<>();
        for (Crop c : Crop.contestCrops(true)) {
            long pb = d.personalBest.getOrDefault(c.name(), 0L);
            if (pb > 0) pbs.add("&a" + c.display + "&7: &e" + Text.commasInt(pb) + " " + ContestMath.Bracket.parse(d.bestBracket.get(c.name())).colored());
        }
        if (pbs.isEmpty()) pbs.add("&7No personal bests yet.");
        menu.set(31, Icons.of(Material.GOLDEN_CARROT, "&dPersonal Bests", pbs));

        int unclaimed = 0;
        for (FarmData.ContestRecord r : d.history) if (!r.claimed && r.bracket != null) unclaimed++;
        final int un = unclaimed;
        menu.set(33, Icons.of(un > 0 ? Material.CHEST : Material.BOOK, un > 0 ? "&a" + un + " unclaimed reward" + (un == 1 ? "" : "s") + "!" : "&aContest History",
                un > 0 ? List.of("&7Medals, tickets and bits waiting.", "", "&eClick to claim!") : List.of("&7Your last " + d.history.size() + " fairs.", "", "&eClick to view!")),
                c -> {
                    if (un > 0) { int n = m.contests.claimAll(c.player()); Msg.success(c.player(), "Claimed " + n + " reward" + (n == 1 ? "" : "s") + "!"); }
                    else openHistory(c.player());
                });

        menu.set(38, Icons.of(Material.GOLDEN_HELMET, "&6Hall of Fame", "&7The best score ever collected in", "&7each crop by a real farmer.", "", "&eClick to view!"),
                c -> openHallOfFame(c.player()));
        menu.set(40, Icons.of(Material.PAPER, "&aRules", List.of(
                "&71. Reach &aFarming " + Text.roman(m.dials.contestMinLevel.get()) + "&7" + (m.dials.contestRequireHost.get()
                        ? " and talk to " + FarmingModule.HOST_NAME + "&7" + (m.data(p).metHost ? " &a✔" : " &c✖") : "") + "&7.",
                "&72. Farm one of the fair's crops (Hub farm, The", "&7   Barn, the Desert or your Garden); the first",
                "&7   one you harvest is the one you compete in.",
                "&73. Collect at least &e" + m.dials.contestMinScore.get() + " &7to be ranked.",
                "&74. Rank against every farmer, real or not:", "&7   " + ContestMath.Bracket.BRONZE.colored() + " &7top 60%, " + ContestMath.Bracket.SILVER.colored() + " &730%,",
                "&7   " + ContestMath.Bracket.GOLD.colored() + " &710%, " + ContestMath.Bracket.PLATINUM.colored() + " &75%, " + ContestMath.Bracket.DIAMOND.colored() + " &72%.",
                "", "&7Bronze or better also pays a", "&aTurbo-crop I &7book for that crop.",
                "", "&7Rare finds while you farm: &aCropling&7,", "&9Squash&7, &5Fermenta &7and &6Helianthus&7.")));
        menu.set(menu.closeSlot() - 1, Icons.back("Farming"), c -> openMain(c.player()));
    }

    void openLeaderboard(Player p, Crop c) {
        List<Object[]> rows = m.contests.leaderboard(c, 28);
        List<Object[]> numbered = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) numbered.add(new Object[]{i + 1, rows.get(i)[0], rows.get(i)[1], rows.get(i)[2]});
        Sky.menus().paged(c.display + " Leaderboard", numbered, row -> {
            boolean ghost = (boolean) row[3];
            boolean me = !ghost && p.getName().equals(row[1]);
            return Icons.of(ghost ? Material.ARMOR_STAND : me ? Material.EMERALD : Material.PLAYER_HEAD,
                    "&e#" + row[0] + " " + (me ? "&a&l" : ghost ? "&7" : "&a") + row[1], "&7Collected: &6" + Text.commasInt((long) row[2]),
                    ghost ? "&8(a farmer from the countryside)" : "");
        }, pc -> {}, page -> page.set(page.closeSlot() - 1, Icons.back("Harvest Fair"), cl -> openContest(cl.player())), 0).open(p);
    }

    /** All-time best real scores per crop (Contests' Hall of Fame; NPC farmers are not in it). */
    void openHallOfFame(Player p) {
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d");
        Sky.menus().paged("Hall of Fame", Crop.contestCrops(true), c -> {
            List<String> l = new ArrayList<>();
            List<Contests.Fame> top = m.contests.hallOfFame(c);
            if (top.isEmpty()) l.add("&7Nobody has placed in this crop yet.");
            for (int i = 0; i < top.size(); i++) {
                Contests.Fame f = top.get(i);
                ContestMath.Bracket b = ContestMath.Bracket.parse(f.bracket);
                boolean me = p.getUniqueId().toString().equals(f.uuid);
                l.add("&e#" + (i + 1) + " " + (me ? "&a&l" : "&a") + f.name + " &6" + Text.commasInt(f.score)
                        + " " + (b == ContestMath.Bracket.NONE ? "" : b.colored()) + " &8" + fmt.format(new Date(f.start)));
            }
            long pb = m.data(p).personalBest.getOrDefault(c.name(), 0L);
            l.add("");
            l.add("&7Your best: &e" + (pb > 0 ? Text.commasInt(pb) : "-"));
            return Icons.of(icon(c), "&a" + c.display, l);
        }, pc -> {}, page -> page.set(page.closeSlot() - 1, Icons.back("Harvest Fair"), cl -> openContest(cl.player())), 0).open(p);
    }

    void openHistory(Player p) {
        List<FarmData.ContestRecord> list = new ArrayList<>(m.data(p).history);
        java.util.Collections.reverse(list);
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d HH:mm");
        Sky.menus().paged("Contest History", list, r -> {
            Crop c = Crop.parse(r.crop);
            ContestMath.Bracket b = ContestMath.Bracket.parse(r.bracket);
            return Icons.of(c == null ? Material.WHEAT : icon(c), "&a" + (c == null ? r.crop : c.display) + " &8" + fmt.format(new Date(r.start)),
                    "&7Collected: &6" + Text.commasInt(r.score),
                    r.bracket == null ? "&7Not ranked (under " + m.dials.contestMinScore.get() + ")" : "&7Place: &e#" + r.position + " &7of &e" + r.participants,
                    r.bracket == null ? "" : b == ContestMath.Bracket.NONE ? "&7Participation" : b.colored(),
                    r.claimed ? "&8Claimed" : "&aUnclaimed");
        }, pc -> {}, page -> page.set(page.closeSlot() - 1, Icons.back("Harvest Fair"), cl -> openContest(cl.player())), 0).open(p);
    }
}
