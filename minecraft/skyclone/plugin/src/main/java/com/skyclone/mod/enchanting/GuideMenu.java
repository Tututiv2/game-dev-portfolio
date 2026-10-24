package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** "Enchantments Guide": every enchantment, filterable by item type, with a per-level detail page. */
final class GuideMenu {

    private GuideMenu() {}

    /** Filter buttons: label, icon, categories (empty = all, "ULTIMATE" = ultimates only). */
    private record Filter(String label, Material icon, Set<String> cats) {}

    private static final List<Filter> FILTERS = List.of(
            new Filter("All", Material.BOOKSHELF, Set.of()),
            new Filter("Swords", Material.DIAMOND_SWORD, Set.of("SWORD", "LONGSWORD", "GAUNTLET", "DUNGEON_WEAPON")),
            new Filter("Bows", Material.BOW, Set.of("BOW")),
            new Filter("Armor", Material.DIAMOND_CHESTPLATE, Set.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")),
            new Filter("Tools", Material.DIAMOND_PICKAXE, Set.of("PICKAXE", "DRILL", "AXE", "SHOVEL", "SHEARS")),
            new Filter("Farming", Material.DIAMOND_HOE, Set.of("HOE")),
            new Filter("Fishing", Material.FISHING_ROD, Set.of("FISHING_ROD", "FISHING_WEAPON")),
            new Filter("Equipment", Material.LEATHER_CHESTPLATE, Set.of("NECKLACE", "CLOAK", "BELT", "GLOVES")),
            new Filter("Ultimate", Material.NETHER_STAR, Set.of("ULTIMATE")));

    static void open(Player p, String filter, int page) { build(filter, page).open(p); }

    static Menu build(String filterLabel, int page) {
        int fi = 0;
        for (int i = 0; i < FILTERS.size(); i++) if (FILTERS.get(i).label().equalsIgnoreCase(String.valueOf(filterLabel))) fi = i;
        Filter f = FILTERS.get(fi);
        List<EnchantSpec> list = new ArrayList<>();
        for (EnchantSpec s : EnchantingModule.reg().sorted()) {
            if (f.cats().isEmpty()) list.add(s);
            else if (f.cats().contains("ULTIMATE")) { if (s.ultimate) list.add(s); }
            else if (s.categories.stream().anyMatch(f.cats()::contains)) list.add(s);
        }
        final int idx = fi;
        return Sky.menus().paged("Enchantments Guide", list, GuideMenu::icon,
                pc -> detail(pc.entry(), f.label(), pc.page()).open(pc.click().player()),
                m -> {
                    List<String> lore = new ArrayList<>();
                    lore.add("&7Show enchantments for:");
                    for (int i = 0; i < FILTERS.size(); i++) lore.add((i == idx ? "&a▶ " : "&7  ") + FILTERS.get(i).label());
                    lore.add("");
                    lore.add("&eClick to switch!");
                    Filter next = FILTERS.get((idx + 1) % FILTERS.size());
                    m.set(50, Icons.of(f.icon(), "&aFilter: " + f.label(), lore), c -> open(c.player(), next.label(), 0));
                    m.set(48, Icons.of(Material.ENCHANTING_TABLE, "&aEnchant Item", "&7Open the enchanting table.", "", "&eClick to open!"),
                            c -> Sky.commands().run(c.player(), "enchant"));
                    m.set(46, Icons.of(Material.GLISTERING_MELON_SLICE, "&4♨ Vitality",
                            "&7Healing abilities (wands, orbs, flares)", "&7spend &4Vitality&7. Your pool is your",
                            "&4♨ Vitality &7stat and refills &a5%&7/s.", "",
                            "&9Drain &7and &d&lLast Stand &7refill it;", "&7the four &9Vitality &7enchants turn",
                            "&7what you spend into stats.", "", "&eClick to see your pool!"),
                            c -> Sky.commands().run(c.player(), "vitality"));
                }, page);
    }

    static ItemStack icon(EnchantSpec s) {
        int lvl = Math.max(s.minLevel, s.onTable() ? s.tableMax : s.maxLevel);
        List<String> lore = new ArrayList<>(Text.wrap("&7" + s.describe(lvl), 36));
        lore.add("");
        lore.add("&7Levels: &a" + EnchantSpec.roman(s.minLevel) + (s.maxLevel > s.minLevel ? "&7-&a" + EnchantSpec.roman(s.maxLevel) : ""));
        lore.add(s.onTable() ? "&7Enchanting table: &aI&7-&a" + EnchantSpec.roman(s.tableMax) : "&7Enchanting table: &cno");
        if (s.skill > 0) lore.add("&7Requires &aEnchanting " + s.skill);
        lore.add("");
        lore.add("&eClick for every level!");
        return Icons.of(Material.ENCHANTED_BOOK, (s.ultimate ? "&d&l" : "&9") + s.name, lore);
    }

    static Menu detail(EnchantSpec s, String filter, int page) {
        Menu m = new Menu(s.name, 6).chrome();
        m.back("Enchantments Guide", () -> build(filter, page));
        List<String> info = new ArrayList<>();
        info.add("&6Applied To:");
        for (String a : s.appliesToLabels()) info.add("&7- &f" + a);
        if (!s.conflicts.isEmpty()) {
            info.add("");
            info.add("&cConflicts with:");
            List<String> names = new ArrayList<>();
            for (String c : s.conflicts) {
                EnchantSpec cs = EnchantingModule.reg().spec(c);
                names.add(cs == null ? c : cs.name);
            }
            if (s.ultimate) info.add("&7- every other &d&lultimate");
            else for (int i = 0; i < Math.min(8, names.size()); i++) info.add("&7- " + names.get(i));
            if (!s.ultimate && names.size() > 8) info.add("&7... and " + (names.size() - 8) + " more");
        }
        info.add("");
        info.add("&6Where to get it:");
        info.addAll(sources(s));
        m.set(4, Icons.of(Material.ENCHANTED_BOOK, (s.ultimate ? "&d&l" : "&9") + s.name, info));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (int l = s.minLevel; l <= s.maxLevel && i < slots.length; l++, i++) {
            List<String> lore = new ArrayList<>(Text.wrap("&7" + s.describe(l), 36));
            lore.add("");
            lore.add("&7Rarity: " + s.rarity(l).code() + s.rarity(l).display());
            if (s.onTable() && l <= s.tableMax) lore.add("&7Table cost: &3" + s.cost(l) + " Exp Levels");
            int cap = AnvilRules.anvilCap(s, EnchantingModule.dials().anvilOverTable.get());
            if (l > 1 && l <= cap && l > s.minLevel) lore.add("&7Anvil: two " + EnchantSpec.roman(l - 1) + " books");
            if (s.experiments.contains(l)) lore.add("&7Experimentation Table reward");
            if (s.levelsByUse() && l > 1) lore.add("&7Reached at &a" + EnchantSpec.fmt(s.thresholdFor(l)) + " " + StatEnchants.unit(s.levelBy.type));
            m.set(slots[i], Icons.of(Material.ENCHANTED_BOOK, s.displayName(l), lore));
        }
        return m;
    }

    static List<String> sources(EnchantSpec s) {
        List<String> out = new ArrayList<>();
        if (s.onTable()) out.add("&7- Enchanting table (&aI&7-&a" + EnchantSpec.roman(s.tableMax) + "&7)");
        int cap = AnvilRules.anvilCap(s, EnchantingModule.dials().anvilOverTable.get());
        if (cap > Math.max(s.tableMax, s.minLevel)) out.add("&7- Anvil, combining books (up to &a" + EnchantSpec.roman(cap) + "&7)");
        if (!s.experiments.isEmpty()) out.add("&7- Experimentation Table");
        if (s.levelsByUse()) out.add("&7- Levels up by use");
        if (!s.onTable() && s.experiments.isEmpty()) out.add("&7- Rare loot, shops and the Bazaar");
        return out;
    }
}
