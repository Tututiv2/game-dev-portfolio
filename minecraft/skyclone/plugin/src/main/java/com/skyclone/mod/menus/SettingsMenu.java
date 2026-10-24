package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings (research 08 section 11.11): category icons on the main page; each category page shows
 * up to 7 settings as an icon with its toggle dye underneath (lime = on, gray = off). Choices cycle
 * (left-click forward, right-click back). Values persist in member data "settings".
 */
final class SettingsMenu {

    private SettingsMenu() {}

    static final int[] CAT_SLOTS = {19, 20, 21, 22, 23, 24, 25};
    static final int[] ICON_SLOTS = {10, 11, 12, 13, 14, 15, 16, 28, 29, 30, 31, 32, 33, 34};
    static final int PER_PAGE = ICON_SLOTS.length;

    static Menu build(Player p) {
        Menu m = new Menu("Settings", 6).chrome();
        m.set(4, Icons.of(Material.REDSTONE_TORCH, "&aSettings", List.of(
                "&7Change how the game talks to you,",
                "&7what your tab list shows and who",
                "&7may see your profile.",
                "",
                "&7Settings: &e" + SettingDefs.all().size())));
        SettingDefs.Category[] cats = SettingDefs.Category.values();
        for (int i = 0; i < cats.length && i < CAT_SLOTS.length; i++) {
            SettingDefs.Category c = cats[i];
            List<SettingDefs.Setting> list = SettingDefs.of(c);
            List<String> lore = new ArrayList<>(MenuKit.wrap(c.description));
            lore.add("");
            for (SettingDefs.Setting s : list) lore.add("&8- &7" + s.name() + ": " + SettingDefs.label(s, SettingsStore.get(p, s.key())));
            lore.add("");
            lore.add("&eClick to change!");
            ItemStack icon = c == SettingDefs.Category.PERSONAL
                    ? Icons.playerHead(p.getUniqueId(), "&a" + c.title, lore)
                    : Icons.of(MenuKit.mat(c.icon, Material.PAPER), "&a" + c.title, lore);
            m.set(CAT_SLOTS[i], icon, click -> { MenuKit.click(click.player()); category(click.player(), c, 0).open(click.player()); });
        }
        m.set(40, Icons.of(Material.TNT, "&cReset All Settings", List.of("&7Put every setting back to its", "&7default value.", "", "&eClick to reset!")),
                c -> Sky.menus().confirm(c.player(), "Reset all settings?", List.of("&7Every setting goes back to its default."),
                        () -> { SettingsStore.reset(c.player(), null); Msg.success(c.player(), "All settings were reset."); build(c.player()).open(c.player()); },
                        null));
        MenuKit.backToSkyMenu(m);
        return m;
    }

    static Menu category(Player p, SettingDefs.Category c, int page) {
        List<SettingDefs.Setting> list = SettingDefs.of(c);
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu(MenuKit.clip("Settings - " + c.title + (pages > 1 ? " (" + (pg + 1) + "/" + pages + ")" : "")), 6).chrome();
        m.drawer(menu -> {
            for (int i = 0; i < PER_PAGE; i++) {
                int idx = pg * PER_PAGE + i;
                int iconSlot = ICON_SLOTS[i], dyeSlot = iconSlot + 9;
                if (idx >= list.size()) { menu.set(iconSlot, Icons.filler()); menu.set(dyeSlot, Icons.filler()); continue; }
                SettingDefs.Setting s = list.get(idx);
                menu.set(iconSlot, settingIcon(p, s), click -> flip(click.player(), s, click.right(), menu));
                menu.set(dyeSlot, toggleIcon(p, s), click -> flip(click.player(), s, click.right(), menu));
            }
        });
        if (pg > 0) m.set(45, Icons.prevPage(pg + 1), c2 -> category(c2.player(), c, pg - 1).open(c2.player()));
        if (pg < pages - 1) m.set(53, Icons.nextPage(pg + 1), c2 -> category(c2.player(), c, pg + 1).open(c2.player()));
        m.set(50, Icons.of(Material.TNT, "&cReset " + c.title, List.of("&7Put this page's settings back", "&7to their defaults.", "", "&eClick to reset!")),
                c2 -> {
                    SettingsStore.reset(c2.player(), c);
                    Msg.success(c2.player(), c.title + " settings were reset.");
                    category(c2.player(), c, pg).open(c2.player());
                });
        m.back("Settings", () -> build(p));
        return m;
    }

    private static void flip(Player p, SettingDefs.Setting s, boolean backwards, Menu menu) {
        String cur = SettingsStore.get(p, s.key());
        String next = SettingDefs.next(s, cur, backwards);
        if (!SettingsStore.set(p, s.key(), next)) { MenuKit.deny(p, "That setting could not be changed right now."); return; }
        MenuKit.click(p);
        menu.redraw();
    }

    static ItemStack settingIcon(Player p, SettingDefs.Setting s) {
        String v = SettingsStore.get(p, s.key());
        List<String> lore = new ArrayList<>(MenuKit.wrap(s.description()));
        lore.add("");
        lore.add("&7Currently: " + SettingDefs.label(s, v));
        if (!s.toggle()) lore.add("&7Options: &f" + String.join("&7, &f", s.options()));
        String reader = MenusListener.liveReader(s);
        if (!reader.equals("menus")) lore.add("&8Used by: " + reader);
        lore.add("");
        lore.add(s.toggle() ? "&eClick to toggle!" : "&eLeft-click for next, right-click for previous!");
        return Icons.of(MenuKit.mat(s.icon(), Material.PAPER), "&a" + s.name(), lore);
    }

    static ItemStack toggleIcon(Player p, SettingDefs.Setting s) {
        String v = SettingsStore.get(p, s.key());
        if (s.toggle()) {
            boolean on = "true".equals(v);
            return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE, on ? "&aEnabled" : "&cDisabled",
                    List.of("&7" + s.name(), "", "&eClick to " + (on ? "disable" : "enable") + "!"));
        }
        return Icons.of(Material.LIGHT_BLUE_DYE, SettingDefs.label(s, v), List.of("&7" + s.name(), "", "&eClick to change!"));
    }
}
