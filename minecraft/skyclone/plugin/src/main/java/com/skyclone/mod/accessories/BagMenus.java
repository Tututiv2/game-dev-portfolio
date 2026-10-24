package com.skyclone.mod.accessories;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Accessory Bag (paged storage), its upgrades and the accessory guide. */
final class BagMenus {

    static final int PER_PAGE = 45;

    private final Bags bags;
    private final Catalog cat;
    private Ui ui;

    BagMenus(Bags bags, Catalog cat) {
        this.bags = bags;
        this.cat = cat;
    }

    void wire(Ui ui) { this.ui = ui; }

    // ---- the bag -------------------------------------------------------------------------------

    void open(Player p, int page) {
        if (!Bags.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        if (AccDials.BAG_REQUIRES.get() && bags.redstoneTier(p) < 2) {
            Msg.error(p, "The Accessory Bag unlocks at Redstone Collection II.");
            return;
        }
        int cap = bags.capacity(p);
        int highest = cap;
        for (int slot : bags.bagView(p).keySet()) highest = Math.max(highest, slot + 1);
        int pages = Math.max(1, (highest + PER_PAGE - 1) / PER_PAGE);
        final int pg = Math.max(0, Math.min(page, pages - 1));
        Snapshot snap = bags.snapshot(p);

        Menu m = new Menu("Accessory Bag (" + (pg + 1) + "/" + pages + ")", 6).keepFreeSlotItems();
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = pg * PER_PAGE + i;
            if (bags.isBroken(p, idx)) {
                m.set(i, Icons.of(Material.BARRIER, "&cUnreadable item", "&7The item stored here could not be",
                        "&7loaded. It is kept safe and untouched.", "&7Please tell an admin (slot " + idx + ")."));
                continue;
            }
            ItemStack held = bags.bagItem(p, idx);
            if (idx < cap || held != null) {
                m.free(i);
                m.getInventory().setItem(i, held == null ? null : held.clone());
            } else {
                m.set(i, Icons.of(Material.RED_STAINED_GLASS_PANE, "&cLocked Slot",
                        "&7Unlock more slots through the", "&7Redstone collection, SkyBlock level",
                        "&7milestones and &aBag Upgrades&7.", "", "&eClick to view upgrades!"),
                        c -> openUpgrades(c.player()));
            }
        }
        for (int i = PER_PAGE; i < 54; i++) m.set(i, Icons.filler());
        if (pg > 0) m.set(45, Icons.prevPage(pg + 1), c -> open(c.player(), pg - 1));
        if (pg < pages - 1) m.set(53, Icons.nextPage(pg + 1), c -> open(c.player(), pg + 1));
        drawInfo(m, p, snap, cap);
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.closeButton();
        m.set(50, Icons.of(Material.ENCHANTING_TABLE, "&dAccessory Bag Thaumaturgy",
                "&7Pick a &aPower&7, spread your", "&7tuning points and learn power", "&7stones.", "", "&eClick to open!"),
                c -> ui.powers.openMain(c.player()));
        m.set(51, Icons.of(Material.LEATHER_CHESTPLATE, "&aYour Equipment", "&7Necklace, cloak, belt and gloves.", "",
                "&eClick to open!"), c -> ui.equipment.open(c.player()));
        m.set(52, Icons.of(Material.BOOK, "&aAccessory Guide", "&7Every accessory there is, and", "&7which ones you are missing.", "",
                "&eClick to browse!"), c -> openGuide(c.player(), true, 0));
        // The info icons were built from the bag as it was at open; redraw them whenever the bag changes
        // (playtest: "Accessory Power 0 / Slots 0/9" stayed stale until the menu was reopened).
        m.onFreeSlotChange(c -> {
            Player pl = c.player();
            if (sync(pl, m, pg) && isOpen(pl, m)) drawInfo(m, pl, bags.snapshot(pl), bags.capacity(pl));
        });
        m.onClose(pl -> {
            sync(pl, m, pg);
            try { Sky.profiles().saveNow(pl); } catch (RuntimeException ignored) { }
        });
        m.open(p);
    }

    /** The two live icons on the bag's bottom row: Accessory Power (46) and Bag Upgrades (47). */
    private void drawInfo(Menu m, Player p, Snapshot snap, int cap) {
        m.set(46, infoIcon(p, snap, cap), c -> ui.powers.openMain(c.player()));
        m.set(47, Icons.of(Material.CHEST, "&aBag Upgrades", "&7Slots: &a" + cap,
                "&7Buy more slots and see where", "&7the rest come from.", "", "&eClick to open!"), c -> openUpgrades(c.player()));
    }

    /** True while {@code m} is still the player's open top inventory (a redraw must never touch a closed menu). */
    static boolean isOpen(Player p, Menu m) {
        return p.isOnline() && p.getOpenInventory().getTopInventory() == m.getInventory();
    }

    private ItemStack infoIcon(Player p, Snapshot snap, int cap) {
        AccData d = Bags.data(p);
        int mp = bags.magicalPower(p);
        List<String> lore = new ArrayList<>();
        lore.add("&7Accessory Power: &6" + Text.commasInt(mp));
        lore.add("&7Counted accessories: &a" + snap.active.size());
        lore.add("&7Slots used: &a" + bags.bagView(p).size() + "&7/&a" + cap);
        Catalog.Power pw = cat.powers.get(d.power);
        lore.add("&7Power: " + (pw == null ? "&cNone selected" : "&a" + pw.name()));
        lore.add("&7Tuning points: &e" + AccMath.tuningPoints(mp, AccDials.MP_PER_TUNING.get()));
        if (!snap.inactive.isEmpty()) {
            lore.add("");
            lore.add("&cNot counted (" + snap.inactive.size() + "):");
            int shown = 0;
            for (Map.Entry<Integer, String> en : snap.inactive.entrySet()) {
                if (shown++ >= 6) { lore.add("&8...and " + (snap.inactive.size() - 6) + " more"); break; }
                ItemStack it = bags.bagItem(p, en.getKey());
                ItemDef def = Sky.items().def(Sky.items().idOf(it));
                lore.add("&8- &7" + (def == null ? "?" : Text.strip(def.name)) + "&8: " + en.getValue());
            }
        }
        lore.add("");
        lore.add("&eClick to open Thaumaturgy!");
        return Icons.of(Material.NETHER_STAR, "&6Accessory Power", lore);
    }

    /**
     * Validate and persist one page: anything that is not an accessory goes straight back to the player.
     * Returns true when the stored bag changed (so the caller knows to redraw the info icons).
     */
    boolean sync(Player p, Menu m, int page) {
        if (!Bags.ready(p)) return false;
        boolean changed = false;
        for (int i : new ArrayList<>(m.freeSlots())) {
            int idx = page * PER_PAGE + i;
            ItemStack it = m.getInventory().getItem(i);
            if (it != null && it.getType().isAir()) it = null;
            if (it != null) {
                ItemDef def = Sky.items().def(Sky.items().idOf(it));
                if (!Catalog.isAccessory(def)) {
                    m.getInventory().setItem(i, null);
                    giveBack(p, it);
                    Msg.error(p, "Only accessories fit in the Accessory Bag.");
                    it = null;
                } else if (it.getAmount() > 1) {
                    ItemStack rest = it.clone();
                    rest.setAmount(it.getAmount() - 1);
                    it.setAmount(1);
                    m.getInventory().setItem(i, it);
                    giveBack(p, rest);
                }
            }
            ItemStack cur = bags.bagItem(p, idx);
            boolean same = (it == null && cur == null) || (it != null && it.equals(cur));
            if (!same) {
                bags.setBag(p, idx, it);
                changed = true;
            }
        }
        return changed;
    }

    static void giveBack(Player p, ItemStack it) {
        if (it == null || it.getType().isAir()) return;
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
    }

    // ---- upgrades ------------------------------------------------------------------------------

    void openUpgrades(Player p) {
        if (!Bags.ready(p)) return;
        AccData d = Bags.data(p);
        int cap = bags.capacity(p);
        int tier = bags.redstoneTier(p);
        int milestones = bags.milestonesReached(p);
        int perTier = AccDials.REDSTONE_PER_TIER.get();
        Menu m = new Menu("Bag Upgrades", 6).chrome();
        m.set(4, Icons.of(Material.CHEST, "&aAccessory Bag: &e" + cap + " slots",
                "&7Base: &a" + AccDials.BASE_SLOTS.get(),
                "&7Redstone collection: &a" + Math.max(0, Math.min(tier, 9) - 1) * perTier,
                "&7SkyBlock level milestones: &a" + milestones * AccDials.MILESTONE_SLOTS.get(),
                "&7Redstone exchange: &a" + (d.redstoneExchange ? AccDials.EXCHANGE_SLOTS.get() : 0),
                "&7Bagwright upgrades: &a" + d.boughtPairs * 2));

        List<String> red = new ArrayList<>();
        red.add("&7Every Redstone collection tier from");
        red.add("&7&aII &7to &aIX &7adds &a" + perTier + " &7slots.");
        red.add("");
        red.add("&7Your tier: &e" + Text.roman(Math.max(tier, 0)).replace("0", "-"));
        for (int t = 2; t <= 9; t++) red.add((tier >= t ? "&a✔ " : "&c✘ ") + "&7Tier " + Text.roman(t) + ": &a+" + perTier);
        m.set(20, Icons.of(Material.REDSTONE, "&cRedstone Collection", red));

        List<String> ms = new ArrayList<>();
        ms.add("&7Reaching these SkyBlock levels adds");
        ms.add("&a" + AccDials.MILESTONE_SLOTS.get() + " &7slots each:");
        ms.add("");
        int lvl = Sky.service(Features.SkyLevel.class).level(p);
        for (int need : AccDials.milestoneLevels()) ms.add((lvl >= need ? "&a✔ " : "&c✘ ") + "&7Level " + need);
        m.set(22, Icons.of(Material.EXPERIENCE_BOTTLE, "&bSkyBlock Level Milestones", ms));

        String exItem = AccDials.EXCHANGE_ITEM.get();
        ItemDef exDef = Sky.items().def(exItem);
        String exName = exDef == null ? Text.titleCase(exItem) : Text.strip(exDef.name);
        int exCost = Math.max(1, AccDials.EXCHANGE_COST.get());
        if (d.redstoneExchange) {
            m.set(24, Icons.of(Material.REDSTONE_BLOCK, "&cRedstone Exchange", "&7+" + AccDials.EXCHANGE_SLOTS.get() + " slots", "", "&aAlready done!"));
        } else {
            m.set(24, Icons.of(Material.REDSTONE_BLOCK, "&cRedstone Exchange",
                    "&7A one-time trade: &a+" + AccDials.EXCHANGE_SLOTS.get() + " &7bag slots.", "",
                    "&7Cost: &a" + exCost + "x " + exName, "&7You have: &e" + Sky.items().count(p, exItem), "", "&eClick to trade!"),
                    c -> exchange(c.player()));
        }

        int bought = d.boughtPairs;
        int max = Math.max(0, AccDials.MAX_PAIRS.get());
        if (bought >= max) {
            m.set(31, Icons.of(Material.ENDER_CHEST, "&6Bagwright Jory", "&7Slot pairs bought: &a" + bought + "&7/&a" + max, "", "&aAll bought!"));
        } else {
            long cost = AccMath.pairCost(bought + 1, AccDials.PAIR_COST_BASE.get());
            m.set(31, Icons.of(Material.ENDER_CHEST, "&6Bagwright Jory",
                    "&7Sells &a+2 &7bag slots at a time.", "", "&7Slot pairs bought: &a" + bought + "&7/&a" + max,
                    "&7Next pair: &6" + Text.commasInt(cost) + " coins", "", "&eClick to buy!"),
                    c -> buyPair(c.player()));
        }
        m.set(48, Icons.back("Accessory Bag"), c -> open(c.player(), 0));
        m.open(p);
    }

    private void exchange(Player p) {
        AccData d = Bags.data(p);
        if (d.redstoneExchange) return;
        String item = AccDials.EXCHANGE_ITEM.get();
        int cost = Math.max(1, AccDials.EXCHANGE_COST.get());
        if (!Sky.items().exists(item) || Sky.items().count(p, item) < cost) {
            Msg.error(p, "You need " + cost + "x " + Text.titleCase(item) + " in your inventory.");
            return;
        }
        Sky.menus().confirm(p, "Redstone Exchange", List.of("&7Trade &a" + cost + "x " + Text.titleCase(item),
                "&7for &a+" + AccDials.EXCHANGE_SLOTS.get() + " &7Accessory Bag slots?"), () -> {
            AccData dd = Bags.data(p);
            if (dd.redstoneExchange) return;
            if (Sky.items().count(p, item) < cost) { Msg.error(p, "You no longer have the items."); return; }
            int took = Sky.items().remove(p, item, cost);
            if (took < cost) {
                if (took > 0) Sky.loot().give(p, item, took, com.skyclone.api.loot.LootSource.OTHER);
                Msg.error(p, "Could not take the items.");
                return;
            }
            dd.redstoneExchange = true;
            bags.invalidate(p);
            Msg.success(p, "Your Accessory Bag grew by " + AccDials.EXCHANGE_SLOTS.get() + " slots!");
            openUpgrades(p);
        }, () -> openUpgrades(p));
    }

    private void buyPair(Player p) {
        AccData d = Bags.data(p);
        int n = d.boughtPairs + 1;
        if (n > AccDials.MAX_PAIRS.get()) return;
        long cost = AccMath.pairCost(n, AccDials.PAIR_COST_BASE.get());
        Sky.menus().confirm(p, "Buy 2 Bag Slots?", List.of("&7Cost: &6" + Text.commasInt(cost) + " coins"), () -> {
            AccData dd = Bags.data(p);
            if (dd.boughtPairs + 1 != n) return;   // a double click bought it already
            if (!Sky.economy().withdraw(p, cost, "accessories: bag slot pair #" + n)) {
                Msg.error(p, "You don't have enough coins!");
                return;
            }
            dd.boughtPairs = n;
            Sky.service(Features.SkyLevel.class).award(p, "accessories:bagpair:" + n, 2, "Accessory Bag upgrade");
            bags.invalidate(p);
            Msg.success(p, "Bought 2 more Accessory Bag slots!");
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
            openUpgrades(p);
        }, () -> openUpgrades(p));
    }

    // ---- guide ---------------------------------------------------------------------------------

    void openGuide(Player p, boolean missingOnly, int page) {
        if (!Bags.ready(p)) return;
        Snapshot snap = bags.snapshot(p);
        Set<String> countedFamilies = new HashSet<>();
        for (Snapshot.Active a : snap.active) countedFamilies.add(Catalog.family(a.def()));
        Map<String, Integer> famBest = new HashMap<>();
        for (Snapshot.Active a : snap.active) famBest.put(Catalog.family(a.def()), Catalog.familyRank(a.def()));
        List<String> ids = new ArrayList<>();
        for (String id : cat.accessoryIds) {
            ItemDef d = Sky.items().def(id);
            if (d == null || Catalog.noPower(d)) continue;
            if (missingOnly) {
                Integer best = famBest.get(Catalog.family(d));
                if (best != null && best >= Catalog.familyRank(d)) continue;   // you already count this tier or better
            }
            ids.add(id);
        }
        ids.sort(Comparator.comparing((String id) -> Catalog.family(Sky.items().def(id)))
                .thenComparingInt(id -> Catalog.familyRank(Sky.items().def(id))));
        String title = missingOnly ? "Missing Accessories" : "All Accessories";
        Menu m = Sky.menus().paged(title, ids, id -> guideIcon(p, snap, countedFamilies, id),
                pc -> guideClick(pc.click().player(), pc.entry()),
                menu -> {
                    menu.set(48, Icons.back("Accessory Bag"), c -> open(c.player(), 0));
                    menu.set(50, Icons.of(missingOnly ? Material.ENDER_EYE : Material.ENDER_PEARL,
                            "&aShowing: " + (missingOnly ? "Missing only" : "Everything"), "",
                            "&eClick to switch!"), c -> openGuide(c.player(), !missingOnly, 0));
                    menu.set(4, Icons.of(Material.BOOK, "&aAccessory Guide",
                            "&7" + ids.size() + " accessories listed.", "&7Only the best of each family counts."));
                }, page);
        m.open(p);
    }

    private ItemStack guideIcon(Player p, Snapshot snap, Set<String> countedFamilies, String id) {
        ItemStack it;
        try { it = Sky.items().create(id); } catch (RuntimeException e) { return Icons.of(Material.BARRIER, "&c" + id); }
        ItemDef d = Sky.items().def(id);
        List<String> extra = new ArrayList<>();
        extra.add("");
        if (snap.activeIds.contains(id)) extra.add("&a✔ Counted in your bag");
        else if (snap.bagIds.contains(id)) extra.add("&e✔ In your bag, but not counted");
        else if (countedFamilies.contains(Catalog.family(d))) extra.add("&8Another tier of this family counts");
        else extra.add("&c✘ Missing");
        Rarity r = d.rarity;
        extra.add("&7Accessory Power: &6+" + (int) Math.round(AccMath.mpFor(r, AccDials.mpTable()) * Catalog.mpMult(d)));
        boolean craftable = !Sky.recipes().producing(id).isEmpty();
        extra.add(craftable ? "&eClick to view its recipe!" : "&8Not crafted: drops, shops or events.");
        return Icons.withLore(it, extra);
    }

    private void guideClick(Player p, String id) {
        if (Sky.recipes().producing(id).isEmpty()) {
            Msg.send(p, "&7That accessory is not crafted: it comes from drops, shops or events.");
            return;
        }
        if (!Sky.commands().run(p, "recipe", id)) Msg.error(p, "The recipe viewer is not available right now.");
    }
}
