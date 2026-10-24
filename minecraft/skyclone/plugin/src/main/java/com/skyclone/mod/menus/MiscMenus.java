package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The smaller screens: Your Bags (research 08 section 11.8), the SkyBlock Guide (every feature in
 * one place - our addition), and stand-ins for the Quest Log and Active Effects that are used only
 * while their owning modules are missing.
 */
final class MiscMenus {

    private MiscMenus() {}

    // ---- Your Bags ------------------------------------------------------------------------------------

    /** slot -> feature id, research 08 section 11.8 (19 accessory ... 24 sacks) + equipment at 25. */
    static final int[] BAGS = {19, 20, 21, 22, 23, 24, 25};
    static final String[] BAG_IDS = {"accessories", "potionbag", "fishingbag", "quiver", "sackofsacks", "sacks", "equipment"};

    static Menu bags(Player p) {
        Menu m = new Menu("Your Bags", 6).chrome();
        m.set(4, MenuKit.icon("icon.bags", "CHEST", "&aYour Bags", List.of(
                "&7Different bags let you carry more",
                "&7of one kind of item, so your",
                "&7inventory stays free for loot.")));
        for (int i = 0; i < BAG_IDS.length; i++) {
            String id = BAG_IDS[i];
            m.set(BAGS[i], bagIcon(p, id), c -> { MenuKit.click(c.player()); Links.open(c.player(), id); });
        }
        MenuKit.backToSkyMenu(m);
        return m;
    }

    static ItemStack bagIcon(Player p, String id) {
        FeatureIndex.Feature f = FeatureIndex.get(id);
        List<String> lore = new ArrayList<>(MenuKit.wrap(f.description()));
        switch (id) {
            case "accessories" -> {
                if (Sky.has(Features.Accessories.class)) {
                    lore.add("");
                    lore.add("&7Magical Power: &6" + Text.commasInt(ProfileViewer.safeInt(() -> Sky.service(Features.Accessories.class).magicalPower(p))));
                }
            }
            case "potionbag" -> { lore.add(""); lore.add("&7Active effects: &e" + SkyBlockMenu.activeEffects(p).size()); }
            default -> { }
        }
        lore.addAll(tail(id, "open"));
        ItemStack icon = switch (id) {
            case "potionbag" -> MenuKit.potion(Color.fromRGB(0x3366FF), "&a" + f.name(), lore);
            default -> MenuKit.icon(f.texture(), f.icon(), "&a" + f.name(), lore);
        };
        return icon;
    }

    static List<String> tail(String id, String verb) {
        List<String> out = new ArrayList<>(Links.stateLore(id));
        if (out.isEmpty()) {
            out.add("");
            String hint = Links.commandHint(id);
            if (!hint.isEmpty()) out.add(hint);
            out.add("&eClick to " + verb + "!");
        }
        return out;
    }

    // ---- SkyBlock Guide ---------------------------------------------------------------------------------

    /** One slot per FeatureIndex category: a row of seven, then two centred under it. */
    static final int[] GUIDE_CATS = {19, 20, 21, 22, 23, 24, 25, 30, 32};

    static Menu guide(Player p) {
        Menu m = new Menu("SkyBlock Guide", 6).chrome();
        m.set(4, Icons.of(Material.KNOWLEDGE_BOOK, "&aSkyBlock Guide", List.of(
                "&7Every feature of the game, sorted",
                "&7by what it is for, with the command",
                "&7that opens it.",
                "",
                "&7Features: &a" + FeatureIndex.all().size())));
        FeatureIndex.Category[] cats = FeatureIndex.Category.values();
        for (int i = 0; i < cats.length && i < GUIDE_CATS.length; i++) {
            FeatureIndex.Category c = cats[i];
            List<FeatureIndex.Feature> list = FeatureIndex.of(c);
            List<String> lore = new ArrayList<>();
            for (FeatureIndex.Feature f : list) {
                Links.State st = Links.state(f.id());
                lore.add((st == Links.State.MISSING ? "&8- &7" : "&8- &a") + f.name() + (st == Links.State.MISSING ? " &8(soon)" : ""));
            }
            lore.add("");
            lore.add("&eClick to browse!");
            m.set(GUIDE_CATS[i], Icons.of(MenuKit.mat(c.icon, Material.PAPER), "&" + c.color + c.title, lore),
                    click -> { MenuKit.click(click.player()); guideCategory(click.player(), c).open(click.player()); });
        }
        m.set(40, Icons.of(Material.OAK_SIGN, "&aAll Commands", List.of("&7Every command you can type,", "&7in chat.", "", "&8/commands", "&eClick to list!")),
                c -> { MenuKit.click(c.player()); c.player().closeInventory(); Links.open(c.player(), "commands"); });
        MenuKit.backToSkyMenu(m);
        return m;
    }

    static Menu guideCategory(Player p, FeatureIndex.Category c) {
        List<FeatureIndex.Feature> list = FeatureIndex.of(c);
        return Sky.menus().paged(c.title, list,
                f -> MenuKit.icon(f.texture(), f.icon(), (Links.state(f.id()) == Links.State.MISSING ? "&c" : "&a") + f.name(),
                        withTail(MenuKit.wrap(f.description()), f.id())),
                pc -> { MenuKit.click(pc.click().player()); Links.open(pc.click().player(), pc.entry().id()); },
                menu -> menu.back("SkyBlock Guide", () -> guide(p)), 0);
    }

    private static List<String> withTail(List<String> body, String id) {
        List<String> out = new ArrayList<>(body);
        out.addAll(tail(id, "open"));
        return out;
    }

    // ---- fallbacks -------------------------------------------------------------------------------------

    /** Quest Log stand-in: just the current objective (progression's /questlog is the real one). */
    static Menu quests(Player p) {
        Menu m = new Menu("Quest Log", 4).chrome();
        String obj = Summaries.objectiveText(p);
        List<String> lore = new java.util.ArrayList<>();
        if (obj == null) {
            lore.add("&7You have no objective right now.");
        } else {
            lore.add("&e" + obj);
            String hint = Summaries.objectiveHint(p);
            if (hint != null) { lore.add(""); lore.addAll(com.skyclone.api.ui.Text.wrap("&7" + hint, 34)); }
            lore.add("");
            lore.add("&7Complete it to get the next one!");
        }
        m.set(13, Icons.of(Material.WRITABLE_BOOK, "&aCurrent Objective", lore));
        m.set(m.closeSlot() - 1, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        return m;
    }

    /** Active Effects stand-in, read from Features.Potions (alchemy's /effects is the real one). */
    static Menu effects(Player p) {
        Map<String, Integer> fx = SkyBlockMenu.activeEffects(p);
        List<Map.Entry<String, Integer>> list = new ArrayList<>(fx.entrySet());
        list.sort(Map.Entry.comparingByKey());
        return Sky.menus().paged("Active Effects", list,
                e -> MenuKit.potion(Color.fromRGB(0xCC33CC), "&a" + Text.titleCase(e.getKey()) + " " + Text.roman(Math.max(1, e.getValue())),
                        List.of("&7Level " + e.getValue())),
                null,
                menu -> menu.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player())), 0);
    }
}
