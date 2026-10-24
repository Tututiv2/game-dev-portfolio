package com.skyclone.mod.accessories;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
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

/** Personal compactor / deletor configuration and runtime, enrichments and the enrichment swapper. */
final class ToolMenus {

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};

    private final Bags bags;
    private final Catalog cat;
    private Ui ui;

    ToolMenus(Bags bags, Catalog cat) {
        this.bags = bags;
        this.cat = cat;
    }

    void wire(Ui ui) { this.ui = ui; }

    // ---- compactor / deletor -------------------------------------------------------------------

    void openCompactor(Player p) { openList(p, true); }

    void openDeletor(Player p) { openList(p, false); }

    private void openList(Player p, boolean compactor) {
        if (!Bags.ready(p)) return;
        Snapshot snap = bags.snapshot(p);
        int slots = compactor ? snap.compactorSlots : snap.deletorSlots;
        List<String> list = compactor ? Bags.data(p).compactor : Bags.data(p).deletor;
        String title = compactor ? "Personal Compactor" : "Personal Deletor";
        Menu m = new Menu(title, 4).chrome();
        m.set(4, Icons.of(compactor ? Material.DROPPER : Material.DISPENSER, "&a" + title,
                compactor ? "&7Chosen materials in your inventory are" : "&7Chosen items are destroyed the",
                compactor ? "&7turned into their enchanted form." : "&7moment you would pick them up.", "",
                "&7Slots from your bag: &a" + slots + (slots == 0 ? " &c(put one in your Accessory Bag!)" : ""),
                "", "&eClick an item in your inventory to add it.", "&eClick a slot to clear it."));
        for (int i = 0; i < SLOTS.length; i++) {
            final int idx = i;
            if (i >= Math.max(slots, list.size())) {
                m.set(SLOTS[i], Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&8Locked slot", "&7A better " + (compactor ? "compactor" : "deletor"),
                        "&7opens more slots."));
                continue;
            }
            if (i >= list.size()) {
                m.set(SLOTS[i], Icons.of(Material.LIME_STAINED_GLASS_PANE, "&aEmpty slot", "&7Click an item in your", "&7inventory to add it."));
                continue;
            }
            String id = list.get(i);
            ItemStack icon;
            try { icon = Sky.items().create(id); } catch (RuntimeException e) { icon = Icons.of(Material.BARRIER, "&c" + id); }
            List<String> lore = new ArrayList<>();
            lore.add("");
            if (compactor) {
                Catalog.Compact c = cat.compactable().get(id);
                if (c != null) {
                    ItemDef rd = Sky.items().def(c.result());
                    lore.add("&7" + c.need() + "x &f" + itemName(id) + " &7→ " + c.amount() + "x " + (rd == null ? c.result() : rd.name));
                } else lore.add("&cThis can no longer be compacted.");
            }
            if (i >= slots) lore.add("&cInactive: your " + (compactor ? "compactor" : "deletor") + " has too few slots.");
            lore.add("&eClick to remove!");
            m.set(SLOTS[i], Icons.withLore(icon, lore), c -> {
                List<String> l = compactor ? Bags.data(c.player()).compactor : Bags.data(c.player()).deletor;
                if (idx < l.size()) l.remove(idx);
                openList(c.player(), compactor);
            });
        }
        m.set(31, Icons.back("Accessory Bag"), c -> ui.bag.open(c.player(), 0));
        m.onPlayerInventoryClick((c, item) -> add(c.player(), item, compactor));
        m.open(p);
    }

    private static String itemName(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? Text.titleCase(id) : Text.strip(d.name);
    }

    private void add(Player p, ItemStack item, boolean compactor) {
        String id = Sky.items().idOf(item);
        if (id == null && item != null) id = Sky.items().vanillaId(item.getType());
        if (id == null) return;
        ItemDef def = Sky.items().def(id);
        if (def != null && def.isUnique()) { Msg.error(p, "Unique items can't go in there."); return; }
        Snapshot snap = bags.snapshot(p);
        int slots = compactor ? snap.compactorSlots : snap.deletorSlots;
        List<String> list = compactor ? Bags.data(p).compactor : Bags.data(p).deletor;
        if (list.contains(id)) { Msg.error(p, "That is already in the list."); return; }
        if (list.size() >= slots) { Msg.error(p, "No free slot. Put a better one in your Accessory Bag."); return; }
        if (compactor && cat.compactable().get(id) == null) { Msg.error(p, "That item has no enchanted form to compact into."); return; }
        list.add(id);
        Msg.sound(p, Sound.UI_BUTTON_CLICK, 1.2f);
        openList(p, compactor);
    }

    /** Called every second: compact configured materials in the inventory. */
    void tick(Player p) {
        if (!AccDials.COMPACTOR_ON.get()) return;
        Snapshot snap = bags.snapshot(p);
        if (snap.compactorSlots <= 0) return;
        List<String> list = Bags.data(p).compactor;
        int limit = Math.min(list.size(), snap.compactorSlots);
        for (int i = 0; i < limit; i++) {
            Catalog.Compact c = cat.compactable().get(list.get(i));
            if (c == null || c.need() <= 0) continue;
            int have = Sky.items().count(p, c.input());
            int crafts = have / c.need();
            if (crafts <= 0) continue;
            int took = Sky.items().remove(p, c.input(), crafts * c.need());
            int done = took / c.need();
            int left = took - done * c.need();
            if (left > 0) give(p, c.input(), left);
            if (done > 0) give(p, c.result(), done * c.amount());
        }
    }

    private static void give(Player p, String id, int amount) {
        ItemDef d = Sky.items().def(id);
        int stack = d == null ? 64 : Math.max(1, d.effectiveMaxStack());
        while (amount > 0) {
            int n = Math.min(stack, amount);
            Sky.loot().give(p, id, n, LootSource.CRAFT);
            amount -= n;
        }
    }

    // ---- enrichments ---------------------------------------------------------------------------

    static boolean enrichable(Rarity r) {
        return r != null && r.ordinal() >= Rarity.LEGENDARY.ordinal() && r.ordinal() <= Rarity.DIVINE.ordinal();
    }

    /** Right-click with an enrichment: pick an accessory in your bag to apply it to. */
    void openEnrich(Player p, String enrichId) {
        ItemDef ed = Sky.items().def(enrichId);
        if (ed == null || !Bags.ready(p)) return;
        Stat stat = cat.enrichStatById.get(enrichId);
        if (stat == null) return;
        List<Integer> slots = new ArrayList<>();
        for (Map.Entry<Integer, ItemStack> en : bags.bagView(p).entrySet()) {
            ItemDef d = Sky.items().def(Sky.items().idOf(en.getValue()));
            if (Catalog.isAccessory(d) && !Catalog.noPower(d) && enrichable(Sky.items().rarity(en.getValue()))) slots.add(en.getKey());
        }
        if (slots.isEmpty()) { Msg.error(p, "You have no Legendary or better accessory in your bag to enrich."); return; }
        Sky.menus().paged("Apply " + Text.strip(ed.name), slots, slot -> {
            ItemStack it = bags.bagItem(p, slot);
            String cur = enrichmentOf(it);
            return Icons.withLore(it == null ? new ItemStack(Material.BARRIER) : it.clone(), List.of("",
                    cur == null ? "&7Not enriched yet." : "&7Currently enriched: &d" + cur, "&eClick to apply!"));
        }, pc -> {
            Player pl = pc.click().player();
            ItemStack hand = pl.getInventory().getItemInMainHand();
            if (!enrichId.equals(Sky.items().idOf(hand))) { Msg.error(pl, "Hold the enrichment in your hand."); pl.closeInventory(); return; }
            ItemStack target = bags.bagItem(pl, pc.entry());
            if (target == null) return;
            hand.setAmount(hand.getAmount() - 1);
            pl.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
            Sky.items().update(target, d -> setEnrichment(d, stat));
            bags.resave(pl, pc.entry());
            Msg.success(pl, "Enriched " + Text.strip(Sky.items().def(Sky.items().idOf(target)).name) + " with " + stat.display + "!");
            pl.closeInventory();
        }, null, 0).open(p);
    }

    static void setEnrichment(ItemData d, Stat stat) {
        if (d.extra == null) d.extra = new JsonObject();
        d.extra.addProperty("enrichment", stat.name());
    }

    static String enrichmentOf(ItemStack it) {
        if (it == null) return null;
        ItemData d = Sky.items().data(it);
        if (d.extra == null || !d.extra.has("enrichment")) return null;
        Stat s = Stat.parse(d.extra.get("enrichment").getAsString());
        return s == null ? null : s.display;
    }

    /** Right-click with the swapper: choose one stat for every enriched accessory in the bag. */
    void openSwapper(Player p, String swapperId) {
        if (!Bags.ready(p)) return;
        List<Stat> stats = new ArrayList<>(cat.enrichValues.keySet());
        Sky.menus().paged("Enrichment Swapper", stats, s -> Icons.of(Material.PAPER, "&" + s.color + s.display,
                "&7Turn every enrichment in your bag", "&7into " + PowerMenus.statLine(s, cat.enrichValues.get(s)) + "&7.", "",
                "&eClick to swap!"), pc -> {
            Player pl = pc.click().player();
            ItemStack hand = pl.getInventory().getItemInMainHand();
            if (!swapperId.equals(Sky.items().idOf(hand))) { Msg.error(pl, "Hold the swapper in your hand."); pl.closeInventory(); return; }
            int n = 0;
            for (Map.Entry<Integer, ItemStack> en : new ArrayList<>(bags.bagView(pl).entrySet())) {
                if (enrichmentOf(en.getValue()) == null) continue;
                Sky.items().update(en.getValue(), d -> setEnrichment(d, pc.entry()));
                bags.resave(pl, en.getKey());
                n++;
            }
            if (n == 0) { Msg.error(pl, "None of your accessories is enriched."); pl.closeInventory(); return; }
            hand.setAmount(hand.getAmount() - 1);
            pl.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
            Msg.success(pl, "Swapped " + n + " enrichments to " + pc.entry().display + ".");
            pl.closeInventory();
        }, null, 0).open(p);
    }
}
