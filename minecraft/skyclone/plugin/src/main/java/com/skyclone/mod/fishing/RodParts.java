package com.skyclone.mod.fishing;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.fishing.FishingCatalog.Part;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rod parts (research 07 §3.10): a Hook, a Line and a Sinker on every rod, applied at the
 * Tackle-smith. Parts live in the rod's {@code ItemData.extra.rodParts}; their stats are an
 * ItemStatContributor, so the lore and the player's real stats always agree.
 */
final class RodParts {

    private RodParts() {}

    static final String[] SLOTS = {"hook", "line", "sinker"};

    static void register() {
        Sky.items().addStatContributor("fishing_rod_parts", (def, data, rarity, sheet) -> {
            if (def == null || def.category != ItemCategory.FISHING_ROD) return;
            for (Part p : Fishing.parts(data)) {
                for (Map.Entry<String, Double> e : p.stats.entrySet()) {
                    Stat s = Stat.parse(e.getKey());
                    if (s != null) sheet.add(s, e.getValue(), ItemStatSheet.Source.OTHER);
                }
                if (p.damage > 0) sheet.addDamage(p.damage, true);
            }
        });
        Sky.items().addLoreContributor("fishing_rod_parts", new LoreContributor() {
            @Override public Section section() { return Section.AFTER_STATS; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                if (def == null || def.category != ItemCategory.FISHING_ROD) return List.of();
                List<String> out = new ArrayList<>();
                out.add("");
                out.add("&9⚓ Hook " + label(Fishing.part(data, "hook")));
                out.add("&9〰 Line " + label(Fishing.part(data, "line")));
                out.add("&9⚓ Sinker " + label(Fishing.part(data, "sinker")));
                return out;
            }
        });
    }

    static String label(String partId) {
        if (partId == null) return "&8NONE";
        ItemDef d = Sky.items().def(partId);
        return d == null ? "&8" + partId : d.rarity.code() + d.name;
    }

    static String slotName(String slot) { return Text.titleCase(slot); }

    // ---- menu -----------------------------------------------------------------------------------

    static void open(Player p) {
        ItemStack rod = p.getInventory().getItemInMainHand();
        Menu m = new Menu("Tackle-smith", 6).chrome();
        m.set(4, Icons.of(Material.ANVIL, "&aTackle-smith",
                "&7Hold a fishing rod, then fit it with", "&7a &9Hook&7, a &9Line &7and a &9Sinker&7.",
                "", "&7Taking a part off gives it back."));
        if (!Fishing.isRod(rod)) {
            m.set(22, Icons.of(Material.BARRIER, "&cNo Rod In Hand", "&7Hold the fishing rod you want", "&7to modify, then open this again."));
        } else {
            ItemData data = Sky.items().data(rod);
            m.set(13, displayCopy(rod));
            int[] at = {29, 31, 33};
            Material[] mats = {Material.TRIPWIRE_HOOK, Material.STRING, Material.IRON_NUGGET};
            for (int i = 0; i < 3; i++) {
                String slot = SLOTS[i];
                String cur = Fishing.part(data, slot);
                List<String> lore = new ArrayList<>();
                lore.add("&7Current: " + label(cur));
                if (cur != null) {
                    ItemDef pd = Sky.items().def(cur);
                    if (pd != null) { lore.add(""); for (String l : pd.lore) if (!l.startsWith("&8")) lore.add(l); }
                }
                lore.add("");
                lore.add("&eClick to change!");
                if (cur != null) lore.add("&bRight-click to remove!");
                ItemStack icon = cur != null && Sky.items().exists(cur) ? Icons.withLore(Baits.stripLore(Sky.items().create(cur, 1)), lore)
                        : Icons.of(mats[i], "&9" + slotName(slot), lore);
                final String fslot = slot;
                m.set(at[i], icon, c -> {
                    if (c.right() && Fishing.part(Sky.items().data(c.player().getInventory().getItemInMainHand()), fslot) != null) remove(c.player(), fslot);
                    else choose(c.player(), fslot);
                });
            }
        }
        m.set(50, Icons.of(Material.EMERALD, "&aBuy Rod Parts", "&7Common parts for coins.", "", "&eClick to browse!"),
                c -> FishMenus.shop(c.player(), "Rod Parts", Fishing.CAT.partShop, () -> open(c.player())));
        m.set(48, Icons.back("Fishing"), c -> FishMenus.main(c.player()));
        m.open(p);
    }

    static void choose(Player p, String slot) {
        List<String> options = new ArrayList<>();
        for (Map.Entry<String, Part> e : Fishing.CAT.parts.entrySet())
            if (slot.equals(e.getValue().slot) && Sky.items().exists(e.getKey())) options.add(e.getKey());
        Sky.menus().paged("Choose a " + slotName(slot), options, id -> {
            int have = Sky.items().count(p, id);
            ItemStack icon = Sky.items().create(id, 1);
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7In inventory: " + (have > 0 ? "&a" + have : "&c0"));
            extra.add(have > 0 ? "&eClick to apply!" : "&cYou don't have this part.");
            return Icons.withLore(icon, extra);
        }, pc -> apply(pc.click().player(), slot, pc.entry()), menu -> menu.set(48, Icons.back("Tackle-smith"), c -> open(c.player())), 0).open(p);
    }

    static void apply(Player p, String slot, String partId) {
        ItemStack rod = p.getInventory().getItemInMainHand();
        if (!Fishing.isRod(rod)) { Msg.error(p, "Hold the rod you want to modify."); return; }
        Part part = Fishing.CAT.parts.get(partId);
        if (part == null || !slot.equals(part.slot)) return;
        if (Sky.items().count(p, partId) <= 0) { Msg.error(p, "You don't have a " + Text.strip(Fishing.name(partId)) + "."); return; }
        ItemDef pd = Sky.items().def(partId);
        if (pd != null) for (String req : pd.requirements) if (!Sky.requirements().checkOrTell(p, req)) return;
        String old = Fishing.part(Sky.items().data(rod), slot);
        if (partId.equals(old)) { Msg.error(p, "That part is already on this rod."); return; }
        if (Sky.items().remove(p, partId, 1) < 1) return;
        // re-read the held rod: removing the part may have shifted nothing, but never trust the old reference
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!Fishing.isRod(held)) {   // should not happen; hand the part back
            p.getInventory().addItem(Sky.items().create(partId, 1));
            return;
        }
        Sky.items().update(held, d -> {
            JsonObject o = d.extra.has("rodParts") ? d.extra.getAsJsonObject("rodParts") : new JsonObject();
            o.addProperty(slot, partId);
            d.extra.add("rodParts", o);
        });
        // only hand the old part back once the new one is really on the rod (no write = no swap)
        if (!partId.equals(Fishing.part(Sky.items().data(p.getInventory().getItemInMainHand()), slot))) {
            giveBack(p, Sky.items().create(partId, 1));
            Msg.error(p, "That rod could not be modified.");
            return;
        }
        if (old != null && Sky.items().exists(old)) giveBack(p, Sky.items().create(old, 1));
        Fishing.data(p).partsApplied++;
        Sky.stats().invalidate(p);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.4f);
        Msg.success(p, "Applied " + Text.strip(Fishing.name(partId)) + " to your rod.");
        open(p);
    }

    static void remove(Player p, String slot) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!Fishing.isRod(held)) return;
        String old = Fishing.part(Sky.items().data(held), slot);
        if (old == null) return;
        Sky.items().update(held, d -> {
            if (d.extra.has("rodParts")) d.extra.getAsJsonObject("rodParts").remove(slot);
        });
        // the part comes back only if it is really gone from the rod (otherwise this would print parts)
        if (Fishing.part(Sky.items().data(p.getInventory().getItemInMainHand()), slot) != null) {
            Msg.error(p, "That rod could not be modified.");
            return;
        }
        if (Sky.items().exists(old)) giveBack(p, Sky.items().create(old, 1));
        Sky.stats().invalidate(p);
        Msg.success(p, "Removed " + Text.strip(Fishing.name(old)) + ".");
        open(p);
    }

    /** What a menu shows for a real item: its look, none of its identity (no uuid in a menu slot). */
    static ItemStack displayCopy(ItemStack src) {
        ItemStack out = new ItemStack(src.getType());
        var sm = src.getItemMeta();
        var om = out.getItemMeta();
        if (sm != null && om != null) {
            if (sm.hasDisplayName()) om.displayName(sm.displayName());
            if (sm.hasLore()) om.lore(sm.lore());
            om.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            out.setItemMeta(om);
        }
        return out;
    }

    private static void giveBack(Player p, ItemStack it) {
        Map<Integer, ItemStack> over = p.getInventory().addItem(it);
        for (ItemStack o : over.values()) p.getWorld().dropItemNaturally(p.getLocation(), o);
    }

    /** Part effect summary lines (guide). */
    static List<String> describe(String id) {
        Part p = Fishing.CAT.parts.get(id);
        List<String> out = new ArrayList<>();
        if (p == null) return out;
        ItemDef d = Sky.items().def(id);
        if (d != null) for (String l : d.lore) if (!l.startsWith("&8") && !l.isEmpty()) out.add(l);
        return out;
    }

    static Map<String, List<String>> bySlot() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String s : SLOTS) m.put(s, new ArrayList<>());
        for (Map.Entry<String, Part> e : Fishing.CAT.parts.entrySet()) m.computeIfAbsent(e.getValue().slot, k -> new ArrayList<>()).add(e.getKey());
        return m;
    }
}
