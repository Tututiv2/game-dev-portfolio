package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.fishing.FishingCatalog.Bait;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Baits (research 07 §3.9) and the Fishing Bag. One bait is used per cast: the preferred bait if
 * set, else the first usable bait in the inventory, else the first in the bag. Baits that only work
 * somewhere else (Ice/Frozen on the Winter Workshop, Worm in the Hollows, lava-only baits with a
 * water rod) are skipped, not wasted.
 */
final class Baits {

    /** Playtest wave 5: an island's display name (a title-cased id leaked reference names like "Galatea"). */
    private static String islandName(String id) {
        if (id == null) return "";
        com.skyclone.api.world.IslandDef d = com.skyclone.api.Sky.worlds().island(id);
        return d != null && d.displayName != null ? com.skyclone.api.ui.Text.strip(d.displayName) : com.skyclone.api.ui.Text.titleCase(id.replace('_', ' '));
    }

    private Baits() {}

    /** Where a bait was found: an inventory slot, a Fishing Bag slot, or (both -1, {@code sack}) the player's sacks. */
    record Found(String id, int invSlot, int bagSlot, boolean sack) {
        Found(String id, int invSlot, int bagSlot) { this(id, invSlot, bagSlot, false); }
    }

    static Bait def(String id) { return id == null ? null : Fishing.CAT.baits.get(id); }

    static boolean usable(String id, boolean lavaRod, String island) {
        Bait b = def(id);
        if (b == null) return false;
        if (b.island != null && !b.island.equals(island)) return false;
        if (b.lavaOnly && !lavaRod) return false;
        return true;
    }

    static Found find(Player p, boolean lavaRod, String island) {
        FishData d = Fishing.data(p);
        String pref = d.preferredBait;
        if (pref != null && usable(pref, lavaRod, island)) {
            Found f = findId(p, d, pref);
            if (f != null) return f;
        }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            String id = it == null ? null : Sky.items().idOf(it);
            if (id != null && usable(id, lavaRod, island)) return new Found(id, i, -1);
        }
        if (d.bagEnabled) {
            for (int i = 0; i < d.bag.size(); i++) {
                String[] e = entry(d.bag.get(i));
                if (e != null && usable(e[0], lavaRod, island)) return new Found(e[0], -1, i);
            }
        }
        // last: the sacks (brief: baits come from the inventory or the sacks). A no-op service counts 0.
        if (Sky.has(Features.Sacks.class)) {
            Features.Sacks sacks = Sky.service(Features.Sacks.class);
            for (String id : Fishing.CAT.baits.keySet()) {
                if (Fishing.CAT.baits.get(id).trophyBait != null || !usable(id, lavaRod, island)) continue;
                if (sacks.count(p, id) > 0) return new Found(id, -1, -1, true);
            }
        }
        return null;
    }

    private static Found findId(Player p, FishData d, String id) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && id.equals(Sky.items().idOf(it))) return new Found(id, i, -1);
        }
        if (d.bagEnabled) {
            for (int i = 0; i < d.bag.size(); i++) {
                String[] e = entry(d.bag.get(i));
                if (e != null && e[0].equals(id)) return new Found(id, -1, i);
            }
        }
        if (Sky.has(Features.Sacks.class) && Sky.service(Features.Sacks.class).count(p, id) > 0) return new Found(id, -1, -1, true);
        return null;
    }

    /** Take one of the found bait. */
    static void consume(Player p, Found f) {
        if (f.sack) {
            Sky.service(Features.Sacks.class).remove(p, f.id, 1);
            return;
        }
        if (f.invSlot >= 0) {
            ItemStack it = p.getInventory().getItem(f.invSlot);
            if (it != null && f.id.equals(Sky.items().idOf(it))) {
                it.setAmount(it.getAmount() - 1);
                p.getInventory().setItem(f.invSlot, it.getAmount() <= 0 ? null : it);
            }
            return;
        }
        FishData d = Fishing.data(p);
        if (f.bagSlot < 0 || f.bagSlot >= d.bag.size()) return;
        String[] e = entry(d.bag.get(f.bagSlot));
        if (e == null || !e[0].equals(f.id)) return;
        int n = Integer.parseInt(e[1]) - 1;
        d.bag.set(f.bagSlot, n <= 0 ? "" : f.id + ":" + n);
    }

    static String[] entry(String s) {
        if (s == null || s.isEmpty()) return null;
        int c = s.lastIndexOf(':');
        if (c <= 0) return null;
        String n = s.substring(c + 1);
        if (!n.chars().allMatch(Character::isDigit) || n.isEmpty()) return null;
        return new String[]{s.substring(0, c), n};
    }

    // ---- the Fishing Bag --------------------------------------------------------------------------

    /** Bag slots (research 02: unlocked at Raw Cod III with 9 slots, +9 at VII, IX, X and XI). */
    static int capacity(Player p) {
        if (!FishDials.BAG_REQUIRE_COLLECTION.get()) return 45;
        if (!Sky.has(Features.Collections.class)) return 9;
        int t = Sky.service(Features.Collections.class).tier(p, "RAW_FISH");
        if (t < 3) return 0;
        if (t >= 11) return 45;
        if (t >= 10) return 36;
        if (t >= 9) return 27;
        if (t >= 7) return 18;
        return 9;
    }

    static int count(FishData d, String id) {
        int n = 0;
        for (String s : d.bag) {
            String[] e = entry(s);
            if (e != null && e[0].equals(id)) n += Integer.parseInt(e[1]);
        }
        return n;
    }

    static void openBag(Player p) {
        if (!Fishing.ready(p)) return;
        int cap = capacity(p);
        if (cap <= 0) {
            Msg.error(p, "Your Fishing Bag unlocks at &aRaw Cod Collection III&c.");
            return;
        }
        FishData d = Fishing.data(p);
        while (d.bag.size() < 45) d.bag.add("");
        Menu m = new Menu("Fishing Bag", 6);
        for (int i = 0; i < 45; i++) {
            String[] e = entry(d.bag.get(i));
            if (e != null && (!Sky.items().exists(e[0]) || def(e[0]) == null)) {
                // a stored id that is no longer a registered bait: keep the record untouched (never free the
                // slot, or saving the empty slot would erase it) and say so
                m.set(i, Icons.of(Material.BARRIER, "&cUnknown Bait", "&7" + e[0] + " &8x" + e[1],
                        "&7This item is not a bait any more.", "&7It stays stored here."));
            } else if (i < cap) {
                m.free(i);
                if (e != null) {
                    int n = Integer.parseInt(e[1]);
                    int shown = Math.min(n, Math.max(1, Sky.items().create(e[0], 1).getMaxStackSize()));
                    m.getInventory().setItem(i, Sky.items().create(e[0], shown));
                    // anything above one stack (only possible from older data) is handed back, never lost;
                    // the record is corrected in the same tick so the two can never both exist
                    if (n > shown) {
                        d.bag.set(i, e[0] + ":" + shown);
                        Sky.loot().give(p, e[0], n - shown, com.skyclone.api.loot.LootSource.STORAGE);
                    }
                }
            } else {
                m.set(i, Icons.of(Material.RED_STAINED_GLASS_PANE, "&cLocked Slot", "&7More slots unlock with your", "&aRaw Cod Collection&7."));
            }
        }
        for (int i = 45; i < 54; i++) m.set(i, Icons.filler());
        m.keepFreeSlotItems();
        m.set(45, Icons.of(Material.FISHING_ROD, "&aFishing Bag",
                "&7Holds the baits you fish with.", "&7Only baits fit; anything else is", "&7handed back when you close it.",
                "", "&7Slots: &a" + cap + "&7/45"));
        m.set(47, Icons.of(d.bagEnabled ? Material.LIME_DYE : Material.GRAY_DYE, "&aUse Bag Baits: " + (d.bagEnabled ? "&aON" : "&cOFF"),
                "&7When your inventory has no bait,", "&7take one from this bag.", "", "&eClick to toggle!"), c -> {
            save(c.player(), c.menu());
            Fishing.data(c.player()).bagEnabled = !Fishing.data(c.player()).bagEnabled;
            openBag(c.player());
        });
        String pref = d.preferredBait;
        m.set(51, Icons.of(Material.TRIPWIRE_HOOK, "&aPreferred Bait",
                "&7Used first whenever you have it.", "", "&7Current: " + (pref == null ? "&8None (first found)" : Fishing.name(pref)),
                "", "&eClick to choose!"), c -> { save(c.player(), c.menu()); choosePreferred(c.player()); });
        m.set(48, Icons.back("Fishing"), c -> { save(c.player(), c.menu()); FishMenus.main(c.player()); });
        m.closeButton();
        m.onClose(pl -> save(pl, m));
        m.open(p);
    }

    private static final java.util.Set<Menu> SAVED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Store the bag's free slots: baits into the data, anything else back to the player. */
    static void save(Player p, Menu m) {
        if (!SAVED.add(m)) return;          // a button already saved this menu; the close must not save it again
        if (!Fishing.ready(p)) {
            // Profile gone mid-menu: the stored bag is still the one this menu was drawn from, so the
            // shown copies are discarded. Handing them back would DUPLICATE every bait in the bag.
            for (int i : m.freeSlots()) m.getInventory().setItem(i, null);
            Sky.log().warning("[fishing] Fishing Bag of " + p.getName() + " closed without a profile; kept the stored bag");
            return;
        }
        FishData d = Fishing.data(p);
        while (d.bag.size() < 45) d.bag.add("");
        List<ItemStack> giveBack = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            if (!m.freeSlots().contains(i)) continue;
            ItemStack it = m.getInventory().getItem(i);
            m.getInventory().setItem(i, null);
            if (it == null || it.getType().isAir()) { d.bag.set(i, ""); continue; }
            String id = Sky.items().idOf(it);
            if (id != null && def(id) != null) d.bag.set(i, id + ":" + it.getAmount());
            else { d.bag.set(i, ""); giveBack.add(it); }
        }
        for (ItemStack it : giveBack) {
            Map<Integer, ItemStack> over = p.getInventory().addItem(it);
            for (ItemStack o : over.values()) p.getWorld().dropItemNaturally(p.getLocation(), o);
        }
        if (!giveBack.isEmpty()) Msg.send(p, "&7Only baits fit in the Fishing Bag - the rest went back to you.");
        Sky.profiles().saveNow(p);
    }

    static void choosePreferred(Player p) {
        FishData d = Fishing.data(p);
        List<String> ids = new ArrayList<>();
        for (String id : Fishing.CAT.baits.keySet()) if (Sky.items().exists(id) && !id.contains("_BRONZE") && !id.contains("_SILVER") && !id.contains("_GOLD") && !id.contains("_DIAMOND")) ids.add(id);
        ids.add(0, "");
        Sky.menus().paged("Preferred Bait", ids, id -> {
            if (id.isEmpty()) return Icons.of(Material.BARRIER, "&cNo Preference", "&7Use the first bait found.", "",
                    d.preferredBait == null ? "&aSelected!" : "&eClick to select!");
            ItemStack icon = Sky.items().create(id, 1);
            int inv = Sky.items().count(p, id), bag = count(d, id);
            List<String> lore = new ArrayList<>(describe(id));
            lore.add("");
            lore.add("&7In inventory: &a" + inv + "  &7In bag: &a" + bag);
            lore.add("");
            lore.add(id.equals(d.preferredBait) ? "&aSelected!" : "&eClick to select!");
            return Icons.withLore(stripLore(icon), lore);
        }, pc -> {
            Fishing.data(pc.click().player()).preferredBait = pc.entry().isEmpty() ? null : pc.entry();
            Msg.success(pc.click().player(), "Preferred bait: " + (pc.entry().isEmpty() ? "none" : Text.strip(Fishing.name(pc.entry()))));
            openBag(pc.click().player());
        }, menu -> menu.set(48, Icons.back("Fishing Bag"), c -> openBag(c.player())), 0).open(p);
    }

    static ItemStack stripLore(ItemStack it) {
        var meta = it.getItemMeta();
        if (meta != null) { meta.lore(new ArrayList<>()); it.setItemMeta(meta); }
        return it;
    }

    /** Human description of what a bait does (menus, guide). */
    static List<String> describe(String id) {
        Bait b = def(id);
        List<String> out = new ArrayList<>();
        if (b == null) return out;
        if (b.fs != 0) out.add("&b+" + Text.commas(b.fs) + " ☂ Fishing Speed");
        if (b.fsMult != 1.0) out.add("&c☂ Fishing Speed x" + Text.commas(b.fsMult));
        if (b.scc != 0) out.add("&3+" + Text.commas(b.scc) + " α Sea Creature Chance");
        if (b.treasure != 0) out.add("&6+" + Text.commas(b.treasure) + " ⛃ Treasure Chance");
        if (b.trophy != 0) out.add("&6+" + Text.commas(b.trophy) + " ♔ Trophy Fish Chance");
        if (b.rareDay != 0) out.add("&7Rare creatures x" + Text.commas(b.rareDay) + " by day");
        if (b.rareNight != 0) out.add("&7Rare creatures x" + Text.commas(b.rareNight) + " at night");
        if (b.rareAlways != 0) out.add("&7Rare creatures x" + Text.commas(b.rareAlways));
        if (b.spookyChance != 0) out.add("&6+" + pct(b.spookyChance) + " spooky creature chance");
        if (b.sharkChance != 0) out.add("&b+" + pct(b.sharkChance) + " shark chance");
        if (b.hotspotChance != 0) out.add("&d+" + pct(b.hotspotChance) + " hotspot creature chance");
        if (b.blessed != 0) out.add("&9+" + pct(b.blessed) + " GREAT/OUTSTANDING treasure");
        if (b.chum != 0) out.add("&a+" + b.chum + " Chum per creature");
        if (b.wormsOnly) out.add("&7Only worms bite");
        if ("CARROT_BAIT".equals(id)) out.add("&75% chance at the &cCarrot Monarch");
        if ("CORRUPTED_BAIT".equals(id)) out.add("&5Lures Obfuscated trophy fish");
        if (b.trophyBait != null) out.add("&5Lures the next Obfuscated fish");
        if (b.island != null) out.add("&cOnly on " + islandName(b.island));
        if (b.lavaOnly) out.add("&cLava rods only");
        return out;
    }

    static String pct(double f) { return Text.commas(f * 100) + "%"; }

    static Map<String, Integer> owned(Player p) {
        Map<String, Integer> m = new HashMap<>();
        FishData d = Fishing.data(p);
        for (String id : Fishing.CAT.baits.keySet()) {
            int n = Sky.items().count(p, id) + count(d, id)
                    + (Sky.has(Features.Sacks.class) ? Sky.service(Features.Sacks.class).count(p, id) : 0);
            if (n > 0) m.put(id, n);
        }
        return m;
    }

    static ItemDef itemDef(String id) { return Sky.items().def(id); }
}
