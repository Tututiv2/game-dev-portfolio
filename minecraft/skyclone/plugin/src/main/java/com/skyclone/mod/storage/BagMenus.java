package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
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
import java.util.List;
import java.util.Map;

/** Quiver, Personal Vault, the "Your Bags" hub and the bits counter for storage-only items. */
final class BagMenus {

    private final StorageModule mod;

    BagMenus(StorageModule mod) { this.mod = mod; }

    // ---- quiver -----------------------------------------------------------------------------------

    boolean isArrow(ItemStack s) {
        if (Codec.empty(s)) return false;
        String id = Codec.id(s);
        if (mod.catalog().isArrow(id)) return true;
        ItemDef d = Sky.items().def(id);
        return d != null && d.category == com.skyclone.api.item.ItemCategory.ARROW;
    }

    void openQuiver(Player p) {
        if (!Store.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        int cap = mod.store().quiverSlots(p);
        if (cap <= 0 && mod.store().bags(p).quiver.isEmpty()) {
            Msg.error(p, "The Quiver unlocks at " + Text.strip(Store.describe("collection:" + StorageDials.QUIVER_COLLECTION.get() + ":3")) + ".");
            return;
        }
        Store.Spec spec = new Store.Spec("Quiver", "quiver", cap, pl -> mod.store().bags(pl).quiver, this::isArrow,
                "Only arrows fit in the Quiver.", m -> {
                    m.set(m.size() - 6, Icons.back("Your Bags"), c -> openBags(c.player()));
                    m.set(m.size() - 4, Icons.of(Material.ARROW, "&aQuiver",
                            "&7Arrows here are used by your bows", "&7before the ones in your inventory.",
                            "&7Arrows you pick up land here first.", "",
                            "&7Slots: &a" + cap + "&7/&a45", "&7Arrows: &a" + Text.commasInt(quiverTotal(p))));
                });
        mod.store().container(p, spec).open(p);
    }

    private List<Map.Entry<String, ItemStack>> quiverStacks(Player p) {
        List<Map.Entry<String, ItemStack>> out = new ArrayList<>();
        for (Map.Entry<String, String> en : mod.store().bags(p).quiver.entrySet()) {
            ItemStack s = Codec.decode(en.getValue());
            if (s != null) out.add(Map.entry(en.getKey(), s));
        }
        return out;
    }

    long quiverTotal(Player p) {
        long n = 0;
        for (Map.Entry<String, ItemStack> en : quiverStacks(p)) n += en.getValue().getAmount();
        return n;
    }

    int quiverCount(Player p, String id) {
        int n = 0;
        for (Map.Entry<String, ItemStack> en : quiverStacks(p)) if (id.equals(Codec.id(en.getValue()))) n += en.getValue().getAmount();
        return n;
    }

    /** True while the player has the quiver menu open: then only the menu writes the quiver. */
    private boolean quiverBusy(Player p) { return "quiver".equals(mod.openKey(p)); }

    int quiverAdd(Player p, String id, int amount) {
        int cap = mod.store().quiverSlots(p);
        if (cap <= 0 || amount <= 0 || quiverBusy(p) || !Sky.items().exists(id)) return 0;
        Map<String, String> q = mod.store().bags(p).quiver;
        ItemStack proto = Sky.items().create(id, 1);
        int max = Math.max(1, proto.getMaxStackSize());
        int left = amount;
        for (int i = 0; i < cap && left > 0; i++) {
            ItemStack s = Codec.decode(q.get(String.valueOf(i)));
            if (s == null || !s.isSimilar(proto) || s.getAmount() >= max) continue;
            int put = Math.min(left, max - s.getAmount());
            s.setAmount(s.getAmount() + put);
            q.put(String.valueOf(i), Codec.encode(s));
            left -= put;
        }
        for (int i = 0; i < cap && left > 0; i++) {
            if (q.containsKey(String.valueOf(i))) continue;
            int put = Math.min(left, max);
            q.put(String.valueOf(i), Codec.encode(Sky.items().create(id, put)));
            left -= put;
        }
        return amount - left;
    }

    int quiverRemove(Player p, String id, int amount) {
        if (amount <= 0 || quiverBusy(p)) return 0;
        Map<String, String> q = mod.store().bags(p).quiver;
        int left = amount;
        for (Map.Entry<String, ItemStack> en : quiverStacks(p)) {
            if (left <= 0) break;
            ItemStack s = en.getValue();
            if (!id.equals(Codec.id(s))) continue;
            int take = Math.min(left, s.getAmount());
            left -= take;
            if (take >= s.getAmount()) q.remove(en.getKey());
            else { s.setAmount(s.getAmount() - take); q.put(en.getKey(), Codec.encode(s)); }
        }
        return amount - left;
    }

    // ---- personal vault -----------------------------------------------------------------------------

    void openVault(Player p) {
        if (!Store.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        int cap = mod.store().vaultSlots(p);
        if (cap <= 0 && mod.store().bags(p).vault.isEmpty()) { Msg.error(p, "Your bank account has no Personal Vault yet."); return; }
        Store.Spec spec = new Store.Spec("Personal Vault", "vault", cap, pl -> mod.store().bags(pl).vault, null, "", m -> {
            m.set(m.size() - 6, Icons.back("Your Bags"), c -> openBags(c.player()));
            m.set(m.size() - 4, Icons.of(Material.GOLD_BLOCK, "&6Personal Vault",
                    "&7A private item safe that grows with", "&7your bank account.", "",
                    "&7Slots: &a" + cap + "&7/&a45", "", "&8Upgrade your bank account for more."));
        });
        mod.store().container(p, spec).open(p);
    }

    // ---- your bags ------------------------------------------------------------------------------------

    void openBags(Player p) {
        Menu m = new Menu("Your Bags", 6).chrome();
        m.set(4, Icons.of(Material.CHEST, "&aYour Bags", "&7Every bag you carry, in one place."));
        m.set(19, Icons.of(Material.LEATHER, "&aAccessory Bag", "&7Holds your accessories so their", "&7power counts without the space.", "", "&eClick to open!"),
                c -> runOther(c.player(), "accessories"));
        m.set(20, Icons.of(Material.BREWING_STAND, "&aPotion Bag", "&7Keeps your potions handy.", "", "&eClick to open!"),
                c -> runOther(c.player(), "potionbag"));
        m.set(21, Icons.of(Material.FISHING_ROD, "&aFishing Bag", "&7Holds bait for your rod.", "", "&eClick to open!"),
                c -> runOther(c.player(), "fishingbag"));
        int q = Store.ready(p) ? mod.store().quiverSlots(p) : 0;
        m.set(22, Icons.of(Material.ARROW, "&aQuiver", "&7Holds arrows for your bows.", "",
                q > 0 ? "&7Slots: &a" + q : "&cLocked &7- " + Store.describe("collection:" + StorageDials.QUIVER_COLLECTION.get() + ":3"),
                "", q > 0 ? "&eClick to open!" : "&cLocked"), c -> openQuiver(c.player()));
        int sb = Store.ready(p) ? mod.store().sackBagSlots(p) : 0;
        m.set(23, Icons.of(Material.BUNDLE, "&aSack of Sacks", "&7Keep your sacks here so they work", "&7without taking inventory space.", "",
                "&7Slots: &a" + sb, "", "&eClick to open!"), c -> mod.sackMenus().openBag(c.player()));
        m.set(24, Icons.of(Material.BUNDLE, "&aSacks", "&7Everything your sacks hold.", "", "&eClick to open!"),
                c -> mod.sackMenus().openSacks(c.player(), 0));
        int v = Store.ready(p) ? mod.store().vaultSlots(p) : 0;
        m.set(25, Icons.of(Material.LEAD, "&aEquipment", "&7Your necklace, cloak, belt and", "&7gloves slots.", "", "&eClick to open!"),
                c -> runOther(c.player(), "equipment"));
        m.set(29, Icons.of(Material.GOLD_BLOCK, "&6Personal Vault", "&7A private item safe that grows with", "&7your bank account.", "",
                "&7Slots: &a" + v, "", v > 0 ? "&eClick to open!" : "&cLocked"), c -> openVault(c.player()));
        m.set(31, Icons.of(Material.ENDER_CHEST, "&aStorage", "&7Ender chest pages and backpacks.", "", "&eClick to open!"),
                c -> mod.storageMenus().openStorage(c.player()));
        m.set(33, Icons.of(Material.LEATHER_CHESTPLATE, "&aWardrobe", "&7Store whole armour sets and", "&7swap between them instantly.", "", "&eClick to open!"),
                c -> mod.wardrobe().open(c.player(), 0));
        if (StorageDials.EQ_WARDROBE.get()) {
            m.set(34, Icons.of(Material.LEAD, "&aEquipment Wardrobe", "&7Store whole sets of necklace,", "&7cloak, belt and gloves.", "",
                    "&eClick to open!"), c -> mod.equipWardrobe().open(c.player(), 0));
        }
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.open(p);
    }

    static void runOther(Player p, String command) {
        boolean ok;
        try { ok = Sky.commands().run(p, command); } catch (RuntimeException e) { ok = false; }
        if (!ok) Msg.error(p, "Coming soon!");
    }

    // ---- bits counter ------------------------------------------------------------------------------------

    record Offer(String id, java.util.function.Supplier<Integer> price) {}

    List<Offer> offers() {
        List<Offer> out = new ArrayList<>();
        out.add(new Offer("JUMBO_BACKPACK_UPGRADE", () -> StorageDials.PRICE_JUMBO_UPGRADE.get()));
        out.add(new Offer("POCKET_SACK_IN_A_SACK", () -> StorageDials.PRICE_LINING.get()));
        for (String id : List.of("LARGE_DUNGEON_SACK", "RUNE_SACK", "FLOWER_SACK", "DWARVEN_MINES_SACK", "CRYSTAL_HOLLOWS_SACK")) {
            out.add(new Offer(id, () -> StorageDials.PRICE_SPECIAL_SACK.get()));
        }
        return out;
    }

    void openShop(Player p) {
        if (!StorageDials.SHOP.get()) { Msg.error(p, "The storage counter is closed."); return; }
        Menu m = new Menu("Storage Counter", 4).chrome();
        long bits = Store.ready(p) ? Sky.economy().bits(p) : 0;
        m.set(4, Icons.of(Material.DIAMOND, "&bYour Bits: &b" + Text.commasInt(bits),
                "&7Bits are earned in game: from", "&7cookies bought with coins, contests", "&7and events. Nothing here costs money."));
        int slot = 10;
        for (Offer o : offers()) {
            if (!Sky.items().exists(o.id())) continue;
            int price = Math.max(0, o.price().get());
            ItemStack icon = Sky.items().create(o.id());
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7Cost: &b" + Text.commasInt(price) + " Bits");
            extra.add("");
            extra.add(bits >= price ? "&eClick to buy!" : "&cYou don't have enough Bits!");
            m.set(slot, Icons.withLore(icon, extra), c -> buy(c.player(), o.id(), price));
            slot++;
            if (slot % 9 == 8) slot += 2;
        }
        m.set(m.size() - 6, Icons.back("Storage"), c -> mod.storageMenus().openStorage(c.player()));
        m.open(p);
    }

    private void buy(Player p, String id, int price) {
        if (!Store.ready(p)) return;
        ItemDef d = Sky.items().def(id);
        String name = d == null ? id : d.rarity.code() + d.name;
        Sky.menus().confirm(p, "Buy " + Text.strip(name) + "?", List.of("&7Cost: &b" + Text.commasInt(price) + " Bits"), () -> {
            if (Codec.freeSlots(p) <= 0) { Msg.error(p, "Your inventory is full!"); return; }
            if (!Sky.economy().spendBits(p, price, "storage: buy " + id)) { Msg.error(p, "You don't have enough Bits!"); return; }
            Sky.loot().give(p, id, 1, LootSource.NPC_SHOP);
            Msg.success(p, "You bought " + name + "&a!");
            Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
            openShop(p);
        }, () -> openShop(p));
    }
}
