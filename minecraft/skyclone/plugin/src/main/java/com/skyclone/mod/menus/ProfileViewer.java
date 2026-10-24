package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Stats &amp; Equipment" (research 08 section 11.2): your own with the SkyBlock Menu's head icon or
 * {@code /stats}; anyone else's with {@code /pv <player>} or sneak + right-click. Live: redrawn every
 * second while open. Stat pages list every stat of a category; a stat's breakdown lists every
 * provider that adds to it ({@code Sky.stats().breakdown}).
 */
final class ProfileViewer {

    private ProfileViewer() {}

    static final int[] ARMOR = {1, 10, 19, 28};
    static final int[] EQUIP = {2, 11, 20, 29};
    static final int HEAD = 4, HELD = 37, PET = 47;
    static final int[] CATS = {13, 14, 15, 16};
    static final int SKILLS = 22, SLAYER = 23, DUNGEONS = 24, PROGRESS = 25, COLLECTIONS = 31, BAG = 32, EQUIP_BTN = 33, COINS = 34;

    static final String[] SLAYERS = {"zombie", "spider", "wolf", "enderman", "blaze", "vampire"};
    static final String[] CLASSES = {"healer", "mage", "berserk", "archer", "tank"};

    // ---- access ---------------------------------------------------------------------------------

    /** Opens target's profile for viewer if allowed; tells the viewer why not otherwise. */
    static void open(Player viewer, Player target) {
        if (target == null || !target.isOnline()) { Msg.error(viewer, "That player is not online."); return; }
        if (!Sky.profiles().isReady(target)) { Msg.error(viewer, "That player's profile is still loading."); return; }
        boolean self = viewer.getUniqueId().equals(target.getUniqueId());
        if (!self && !viewer.hasPermission("skyclone.admin") && !SettingsStore.on(target, SettingDefs.PV_ALLOW)) {
            Msg.error(viewer, target.getName() + " has profile viewing turned off.");
            return;
        }
        build(viewer, target).open(viewer);
    }

    static Menu build(Player viewer, Player target) {
        boolean self = viewer.getUniqueId().equals(target.getUniqueId());
        String title = self ? "Stats & Equipment" : clip(target.getName() + "'s Profile");
        Menu m = new Menu(title, 6).chrome();
        m.drawer(menu -> {
            if (!target.isOnline()) { closeGone(viewer); return; }
            try { draw(menu, viewer, target, self); } catch (Throwable t) { Sky.log().warning("[menus] profile viewer draw: " + t); }
        });
        m.refreshEvery(20);
        if (self) MenuKit.backToSkyMenu(m);
        return m;
    }

    private static void closeGone(Player viewer) {
        org.bukkit.Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (Sky.menus().current(viewer) != null) viewer.closeInventory();
            Msg.error(viewer, "That player went offline.");
        });
    }

    static String clip(String s) { return s.length() > 32 ? s.substring(0, 32) : s; }

    // ---- main page --------------------------------------------------------------------------------

    static void draw(Menu m, Player viewer, Player t, boolean self) {
        // armor
        ItemStack[] armor = t.getInventory().getArmorContents();   // boots, legs, chest, helmet
        String[] armorNames = {"Helmet", "Chestplate", "Leggings", "Boots"};
        for (int i = 0; i < 4; i++) {
            ItemStack piece = armor.length == 4 ? armor[3 - i] : null;
            ItemStack shown = MenuKit.displayCopy(piece, null);
            m.set(ARMOR[i], shown != null ? shown
                    : Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Empty " + armorNames[i] + " Slot"));
        }
        // equipment
        drawEquipment(m, t);
        // held item
        ItemStack held = MenuKit.displayCopy(t.getInventory().getItemInMainHand(), List.of("", "&8Held item"));
        m.set(HELD, held != null ? held : Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Empty Hand"));
        // head
        m.set(HEAD, head(t));
        // stat categories
        StatCategories.Category[] cats = StatCategories.Category.values();
        for (int i = 0; i < cats.length && i < CATS.length; i++) {
            StatCategories.Category c = cats[i];
            m.set(CATS[i], categoryIcon(t, c, true), click -> { MenuKit.click(click.player()); statPage(viewer, t, c).open(viewer); });
        }
        // summaries
        m.set(SKILLS, skillsIcon(t, self), self ? c -> { MenuKit.click(viewer); Links.open(viewer, "skills"); } : null);
        m.set(SLAYER, slayerIcon(t, self), self ? c -> { MenuKit.click(viewer); Links.open(viewer, "slayer"); } : null);
        m.set(DUNGEONS, dungeonIcon(t), self ? c -> { MenuKit.click(viewer); Links.open(viewer, "dungeons"); } : null);
        m.set(PROGRESS, progressIcon(t, self), self ? c -> { MenuKit.click(viewer); Links.open(viewer, "sblevel"); } : null);
        m.set(COLLECTIONS, collectionsIcon(t, self), self ? c -> { MenuKit.click(viewer); Links.open(viewer, "collections"); } : null);
        m.set(BAG, bagIcon(t, self), self ? c -> { MenuKit.click(viewer); Links.open(viewer, "accessories"); } : null);
        if (self) {
            m.set(EQUIP_BTN, Icons.of(Material.LEATHER_HELMET, "&aEquipment", List.of("&7Change your necklace, cloak,", "&7belt and gloves.", "", "&eClick to open!")),
                    c -> { MenuKit.click(viewer); Links.open(viewer, "equipment"); });
        }
        if (self || SettingsStore.on(t, SettingDefs.PV_COINS)) m.set(COINS, coinsIcon(t));
        m.set(PET, petIcon(t, self), self ? c -> { MenuKit.click(viewer); Links.open(viewer, "pets"); } : null);
        if (!self) {
            if (!SettingsStore.on(t, SettingDefs.TRADE_REQUESTS)) {
                // Settings -> Chat -> Trade Requests off: say so before the click (the click still accepts THEIR request)
                m.set(50, Icons.of(Material.GRAY_DYE, "&7Send Trade Request", List.of("&c" + t.getName() + " is not accepting", "&ctrade requests.", "",
                                "&8If they asked you first, you", "&8can still accept here.", "&eClick to accept!")),
                        c -> tradeFromViewer(viewer, t));
            } else {
                m.set(50, Icons.of(Material.EMERALD, "&aSend Trade Request", List.of("&7Offer " + t.getName() + " a trade.", "", "&8/trade " + t.getName(), "&eClick to trade!")),
                        c -> tradeFromViewer(viewer, t));
            }
            m.set(51, Icons.of(Material.OAK_DOOR, "&aVisit Island", List.of("&7Visit " + t.getName() + "'s private island.", "", "&8/visit " + t.getName(), "&eClick to visit!")),
                    c -> { MenuKit.click(viewer); viewer.closeInventory(); if (!Sky.commands().run(viewer, "visit", t.getName())) MenuKit.comingSoon(viewer, "Island visits"); });
        }
    }

    /**
     * The viewer's trade button. {@code Sky.commands().run} does not pass through the command-preprocess gate, so
     * the Trade Requests setting is checked here with the same rule (MenusListener.tradeAllowed).
     */
    private static void tradeFromViewer(Player viewer, Player t) {
        MenuKit.click(viewer);
        viewer.closeInventory();
        if (t.isOnline() && !MenusListener.tradeAllowed(viewer, t)) { Msg.error(viewer, t.getName() + " is not accepting trade requests."); return; }
        if (!Sky.commands().run(viewer, "trade", t.getName())) MenuKit.comingSoon(viewer, "Trading");
    }

    private static void drawEquipment(Menu m, Player t) {
        String[] names = {"Necklace", "Cloak", "Belt", "Gloves"};
        ItemStack[] shown = new ItemStack[4];
        boolean installed = Sky.has(Features.Equipment.class);
        List<ItemStack> worn;
        try { worn = Sky.service(Features.Equipment.class).worn(t); } catch (Throwable e) { worn = List.of(); }
        List<ItemStack> unplaced = new ArrayList<>();
        for (ItemStack it : worn == null ? List.<ItemStack>of() : worn) {
            if (it == null || it.getType().isAir()) continue;
            int idx = -1;
            try {
                ItemDef d = Sky.items().def(Sky.items().idOf(it));
                if (d != null) idx = switch (d.category) {
                    case NECKLACE -> 0; case CLOAK -> 1; case BELT -> 2; case GLOVES -> 3; default -> -1;
                };
            } catch (Throwable ignored) { /* unknown item */ }
            if (idx >= 0 && shown[idx] == null) shown[idx] = MenuKit.displayCopy(it, null);
            else unplaced.add(it);
        }
        for (int i = 0; i < 4; i++) {
            if (shown[i] == null && !unplaced.isEmpty()) shown[i] = MenuKit.displayCopy(unplaced.remove(0), null);
            m.set(EQUIP[i], shown[i] != null ? shown[i]
                    : Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Empty " + names[i] + " Slot",
                    installed ? List.of() : List.of("&8Equipment slots are coming soon.")));
        }
    }

    // ---- icons -------------------------------------------------------------------------------------

    static ItemStack head(Player t) {
        List<String> lore = new ArrayList<>();
        int lvl = Summaries.sbLevel(t);
        lore.add("&7SkyBlock Level: " + MenuKit.sbLevelTag(lvl));
        lore.add("&7Profile: &a" + Sky.profiles().profileName(t));
        lore.add("&7Skill Average: &6" + String.format(java.util.Locale.US, "%.1f", Summaries.skillAverage(t)));
        int mp = safeInt(() -> Sky.service(Features.Accessories.class).magicalPower(t));
        if (mp > 0) lore.add("&7Magical Power: &6" + Text.commasInt(mp));
        String pet = MenuKit.petLabel(t);
        if (pet != null) lore.add("&7Pet: " + pet);
        return Icons.playerHead(t.getUniqueId(), MenuKit.levelColor(lvl) + t.getName(), lore);
    }

    static ItemStack categoryIcon(Player t, StatCategories.Category c, boolean clickable) {
        List<String> lore = new ArrayList<>(MenuKit.wrap(c.description));
        lore.add("");
        for (Stat s : StatCategories.of(c)) {
            double v = Sky.stats().get(t, s);
            if (c != StatCategories.Category.COMBAT && v == s.base && v == 0) continue;   // keep long pages short
            lore.add(" " + StatCategories.valueLine(s, v));
        }
        if (lore.get(lore.size() - 1).isEmpty()) lore.add("&8Nothing here yet.");
        if (clickable) { lore.add(""); lore.add("&eClick for details!"); }
        return Icons.of(MenuKit.mat(c.icon, Material.PAPER), "&" + c.color + c.title, lore);
    }

    private static ItemStack skillsIcon(Player t, boolean self) {
        List<String> lore = new ArrayList<>();
        for (SkillType s : SkillType.values()) {
            int lvl = Summaries.skillLevel(t, s);
            lore.add(" &7" + s.display + ": &e" + lvl + (s.cosmetic ? " &8(Cosmetic)" : ""));
        }
        lore.add("");
        lore.add("&7Skill Average: &6" + String.format(java.util.Locale.US, "%.1f", Summaries.skillAverage(t)));
        if (self) { lore.add(""); lore.add("&eClick to view!"); }
        return Icons.of(Material.DIAMOND_SWORD, "&aSkills", lore);
    }

    private static ItemStack slayerIcon(Player t, boolean self) {
        List<String> lore = new ArrayList<>();
        if (!Sky.has(Features.Slayers.class)) lore.add("&8Slayer is coming soon.");
        else {
            Features.Slayers s = Sky.service(Features.Slayers.class);
            for (String type : SLAYERS) {
                int lvl = safeInt(() -> s.level(t, type));
                double xp = safeDouble(() -> s.xp(t, type));
                lore.add(" &7" + Text.titleCase(type) + " Slayer: &e" + lvl + " &8(" + Text.commasInt(xp) + " XP)");
            }
            if (safeBool(() -> s.questActive(t))) { lore.add(""); lore.add("&cA slayer quest is active!"); }
        }
        if (self && Sky.has(Features.Slayers.class)) { lore.add(""); lore.add("&eClick to view!"); }
        return Icons.of(Material.ROTTEN_FLESH, "&cSlayers", lore);
    }

    private static ItemStack dungeonIcon(Player t) {
        List<String> lore = new ArrayList<>();
        if (!Sky.has(Features.Dungeons.class)) lore.add("&8Dungeons are coming soon.");
        else {
            Features.Dungeons d = Sky.service(Features.Dungeons.class);
            lore.add("&7Catacombs Level: &e" + safeInt(() -> d.catacombsLevel(t)));
            lore.add("");
            for (String c : CLASSES) lore.add(" &7" + Text.titleCase(c) + ": &e" + safeInt(() -> d.classLevel(t, c)));
        }
        return Icons.of(Material.WITHER_SKELETON_SKULL, "&cDungeons", lore);
    }

    private static ItemStack progressIcon(Player t, boolean self) {
        List<String> lore = new ArrayList<>();
        int lvl = Summaries.sbLevel(t);
        lore.add("&7SkyBlock Level: " + MenuKit.sbLevelTag(lvl));
        if (Sky.has(Features.Mining.class)) {
            Features.Mining mi = Sky.service(Features.Mining.class);
            lore.add("&7Heart of the Mountain: &5" + Text.roman(Math.max(1, safeInt(() -> mi.hotmLevel(t)))));
            lore.add("&7Mithril Powder: &2" + Text.commasInt(safeLong(() -> mi.powder(t, "mithril"))));
            lore.add("&7Gemstone Powder: &d" + Text.commasInt(safeLong(() -> mi.powder(t, "gemstone"))));
            lore.add("&7Glacite Powder: &b" + Text.commasInt(safeLong(() -> mi.powder(t, "glacite"))));
        }
        if (Sky.has(Features.Garden.class)) {
            Features.Garden g = Sky.service(Features.Garden.class);
            lore.add("&7Garden Level: &a" + Text.roman(Math.max(1, safeInt(() -> g.gardenLevel(t)))));
            lore.add("&7Copper: &c" + Text.commasInt(safeLong(() -> g.copper(t))));
        }
        if (Sky.has(Features.Bestiary.class)) {
            lore.add("&7Bestiary Milestone: &e" + safeInt(() -> Sky.service(Features.Bestiary.class).milestone(t)));
        }
        if (self) { lore.add(""); lore.add("&eClick to view SkyBlock Leveling!"); }
        return Icons.of(Material.EXPERIENCE_BOTTLE, "&aProgress", lore);
    }

    private static ItemStack collectionsIcon(Player t, boolean self) {
        List<String> lore = new ArrayList<>();
        int[] c = Summaries.collectionProgress(t);
        lore.addAll(Summaries.collectionSummary(c));
        if (self) { lore.add(""); lore.add("&eClick to view!"); }
        return Icons.of(Material.PAINTING, "&aCollections", lore);
    }

    private static ItemStack bagIcon(Player t, boolean self) {
        List<String> lore = new ArrayList<>();
        if (!Sky.has(Features.Accessories.class)) lore.add("&8The accessory bag is coming soon.");
        else lore.add("&7Magical Power: &6" + Text.commasInt(safeInt(() -> Sky.service(Features.Accessories.class).magicalPower(t))));
        if (self) { lore.add(""); lore.add("&eClick to open!"); }
        return MenuKit.icon("icon.bags", "GOLD_NUGGET", "&aAccessory Bag", lore);
    }

    static ItemStack coinsIcon(Player t) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Purse: &6" + Sky.economy().format(Sky.economy().purse(t)));
        lore.add("&7Bank: &6" + Sky.economy().format(Sky.economy().bank(t)));
        lore.add("&7Bits: &b" + Text.commasInt(Sky.economy().bits(t)));
        return Icons.of(Material.GOLD_INGOT, "&6Coins", lore);
    }

    private static ItemStack petIcon(Player t, boolean self) {
        String label = MenuKit.petLabel(t);
        if (label == null) {
            List<String> lore = new ArrayList<>(List.of("&7No pet summoned."));
            if (self) { lore.add(""); lore.add("&eClick to open your pets!"); }
            return Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Empty Pet Slot", lore);
        }
        Features.Pets pets = Sky.service(Features.Pets.class);
        String type = pets.activeType(t);
        String rarity = pets.activeRarity(t);
        ItemStack base = petLook(type + ";" + MenuKit.rarityIndex(rarity));
        List<String> lore = new ArrayList<>(List.of("&7Active pet", "&7Rarity: " + com.skyclone.api.item.Rarity.parse(rarity).code() + com.skyclone.api.item.Rarity.parse(rarity).display()));
        if (self) { lore.add(""); lore.add("&eClick to open your pets!"); }
        if (base == null) return Icons.of(Material.BONE, label, lore);
        org.bukkit.inventory.meta.ItemMeta meta = base.getItemMeta();
        meta.displayName(Text.c(label));
        meta.lore(Text.lines(lore));
        base.setItemMeta(meta);
        return base;
    }

    /** Pet item id -> a data-free look-alike, so a live (1 s) redraw never mints a unique pet item per tick. */
    private static final Map<String, ItemStack> PET_LOOKS = new java.util.concurrent.ConcurrentHashMap<>();

    static ItemStack petLook(String itemId) {
        ItemStack cached = PET_LOOKS.get(itemId);
        if (cached != null) return cached.clone();
        ItemStack look = null;
        try {
            if (Sky.items().exists(itemId)) look = MenuKit.displayCopy(Sky.items().create(itemId), null);
        } catch (Throwable ignored) { /* fall back to a bone */ }
        if (look == null) return null;
        if (PET_LOOKS.size() > 512) PET_LOOKS.clear();
        PET_LOOKS.put(itemId, look);
        return look.clone();
    }

    // ---- stat pages ---------------------------------------------------------------------------------

    static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    static Menu statPage(Player viewer, Player t, StatCategories.Category c) {
        boolean self = viewer.getUniqueId().equals(t.getUniqueId());
        Menu m = new Menu(clip(self ? c.title : t.getName() + " - " + c.title), 6).chrome();
        m.drawer(menu -> {
            if (!t.isOnline()) { closeGone(viewer); return; }
            menu.set(4, categoryIcon(t, c, false));
            List<Stat> stats = StatCategories.of(c);
            for (int i = 0; i < stats.size() && i < GRID.length; i++) {
                Stat s = stats.get(i);
                menu.set(GRID[i], statIcon(t, s), click -> { MenuKit.click(click.player()); breakdown(viewer, t, s).open(viewer); });
            }
        });
        m.refreshEvery(20);
        StatCategories.Category[] all = StatCategories.Category.values();
        StatCategories.Category prev = all[Math.floorMod(c.ordinal() - 1, all.length)];
        StatCategories.Category next = all[Math.floorMod(c.ordinal() + 1, all.length)];
        m.set(45, Icons.of(Material.ARROW, "&a" + prev.title, "&7Previous category"), click -> { MenuKit.click(viewer); statPage(viewer, t, prev).open(viewer); });
        m.set(53, Icons.of(Material.ARROW, "&a" + next.title, "&7Next category"), click -> { MenuKit.click(viewer); statPage(viewer, t, next).open(viewer); });
        m.back(self ? "Stats & Equipment" : t.getName() + "'s Profile", () -> build(viewer, t));
        return m;
    }

    static ItemStack statIcon(Player t, Stat s) { return statIcon(t, s, true); }

    /** {@code live} false: the icon stored in the offline snapshot, without the click line (breakdowns are live only). */
    static ItemStack statIcon(Player t, Stat s, boolean live) {
        StatCategories.Info info = StatCategories.info(s);
        double v = Sky.stats().get(t, s);
        List<String> lore = new ArrayList<>(MenuKit.wrap(info.description()));
        lore.add("");
        lore.add("&7Base: &f" + StatCategories.format(s, s.base));
        List<Part> parts = breakdownOf(t, s);
        int shown = 0;
        for (Part part : parts) {
            if (shown++ >= 5) { lore.add("&8...and " + (parts.size() - 5) + " more"); break; }
            lore.add("&8- &7" + StatCategories.sourceName(part.source()) + ": " + StatCategories.partValue(s, part.kind(), part.value()));
        }
        if (s == Stat.SPEED) lore.add("&8Speed is capped at 400.");
        if (live) {
            lore.add("");
            lore.add("&eClick for the full breakdown!");
        }
        return Icons.of(MenuKit.mat(info.icon(), Material.PAPER), StatCategories.valueLine(s, v), lore);
    }

    /** One breakdown row: provider id, what kind of change it is, and its value. */
    record Part(String key, String source, StatCategories.PartKind kind, double value) {}

    /**
     * Every non-neutral breakdown row: flat rows first (largest first), then percent bonuses, then
     * multipliers. Core's "base" row is left out (the menus show the base separately).
     */
    static List<Part> breakdownOf(Player t, Stat s) {
        Map<String, Double> raw;
        try { raw = Sky.stats().breakdown(t, s); } catch (Throwable e) { raw = Map.of(); }
        List<Part> list = new ArrayList<>();
        if (raw != null) for (Map.Entry<String, Double> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            StatCategories.SourceKey k = StatCategories.parseKey(e.getKey());
            double v = e.getValue();
            if (k.kind() == StatCategories.PartKind.MULTIPLIER ? Math.abs(v - 1) < 1e-9 : Math.abs(v) < 1e-9) continue;
            if (k.kind() == StatCategories.PartKind.FLAT && k.source().equalsIgnoreCase("base")) continue;
            list.add(new Part(e.getKey(), k.source(), k.kind(), v));
        }
        list.sort(Comparator.comparingInt((Part p) -> p.kind().ordinal())
                .thenComparingDouble(p -> p.kind() == StatCategories.PartKind.MULTIPLIER ? -Math.abs(p.value() - 1) : -Math.abs(p.value())));
        return list;
    }

    static String signed(Stat s, double v) {
        return StatCategories.partValue(s, StatCategories.PartKind.FLAT, v);
    }

    static Menu breakdown(Player viewer, Player t, Stat s) {
        StatCategories.Info info = StatCategories.info(s);
        Menu m = new Menu(clip(s.display + " Breakdown"), 6).chrome();
        m.drawer(menu -> {
            if (!t.isOnline()) { closeGone(viewer); return; }
            for (int slot : GRID) menu.set(slot, Icons.filler());
            double total = Sky.stats().get(t, s);
            StatMap snap;
            try { snap = Sky.stats().snapshot(t); } catch (Throwable e) { snap = new StatMap(); }
            List<Part> parts = breakdownOf(t, s);
            double flatSum = 0;
            for (Part part : parts) if (part.kind() == StatCategories.PartKind.FLAT) flatSum += part.value();
            List<String> lore = new ArrayList<>(MenuKit.wrap(info.description()));
            lore.add("");
            lore.add("&7Base: &f" + StatCategories.format(s, s.base));
            lore.add("&7Added by sources: " + signed(s, flatSum));
            double pct = snap.percent(s), mult = snap.multiplier(s);
            if (pct != 0) lore.add("&7Percent bonus: " + StatCategories.partValue(s, StatCategories.PartKind.PERCENT, pct));
            if (mult != 1) lore.add("&7Multiplier: " + StatCategories.partValue(s, StatCategories.PartKind.MULTIPLIER, mult));
            lore.add("");
            lore.add("&7Total: " + StatCategories.valueLine(s, total));
            lore.add("&8(base + sources) x (1 + percent) x multiplier");
            menu.set(4, Icons.of(MenuKit.mat(info.icon(), Material.PAPER), StatCategories.valueLine(s, total), lore));
            if (parts.isEmpty()) {
                menu.set(22, Icons.of(Material.GRAY_DYE, "&7Nothing adds to this stat yet", MenuKit.wrap("Armor, pets, accessories, skills and potions can all raise it.")));
                return;
            }
            int i = 0;
            for (Part part : parts) {
                if (i >= GRID.length) break;
                String name = StatCategories.sourceName(part.source());
                List<String> l = new ArrayList<>();
                String value = StatCategories.partValue(s, part.kind(), part.value());
                switch (part.kind()) {
                    case FLAT -> {
                        l.add("&7Gives: " + value + " &" + s.color + s.symbol + " " + s.display);
                        double share = flatSum == 0 ? 0 : part.value() / flatSum;
                        l.add("&7Share of sources: &e" + MenuKit.pct(Math.abs(share)));
                    }
                    case PERCENT -> l.add("&7Raises your total " + s.display + " by " + value + "&7.");
                    case MULTIPLIER -> l.add("&7Multiplies your total " + s.display + " by " + value + "&7.");
                }
                l.add("&8source id: " + part.key());
                menu.set(GRID[i++], Icons.of(MenuKit.mat(StatCategories.sourceIcon(part.source()), Material.PAPER), "&a" + name, l));
            }
        });
        m.refreshEvery(20);
        m.back(info.category().title, () -> statPage(viewer, t, info.category()));
        return m;
    }

    // ---- safe service reads -----------------------------------------------------------------------

    interface IntS { int get(); }
    interface LongS { long get(); }
    interface DoubleS { double get(); }
    interface BoolS { boolean get(); }

    static int safeInt(IntS s) { try { return s.get(); } catch (Throwable t) { return 0; } }
    static long safeLong(LongS s) { try { return s.get(); } catch (Throwable t) { return 0; } }
    static double safeDouble(DoubleS s) { try { return s.get(); } catch (Throwable t) { return 0; } }
    static boolean safeBool(BoolS s) { try { return s.get(); } catch (Throwable t) { return false; } }

    static boolean isEquipmentCategory(ItemCategory c) { return c != null && c.isEquipment(); }
}
