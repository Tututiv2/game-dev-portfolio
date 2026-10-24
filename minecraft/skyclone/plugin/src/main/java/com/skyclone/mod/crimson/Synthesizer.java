package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
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
import java.util.Objects;

/**
 * Wave 5 (accessories hand-off: "crimson: Synthesizers"): the Synthesizer v1/v2/v3 necklaces are the
 * accessories module's items; what they DO lives here, because what they eat - X, Y and Z - are Crimson Isle
 * drops this module registers.
 *
 * <p>Hold one and run {@code /synthesizer} (or click it in {@code /crimson}): feed X / Y / Z from your
 * inventory and sacks, one stat point each, up to the reference caps ({@link CrimsonRules#synthSlots}). The
 * count lives on the item ({@code ItemData.extra.synth_x/y/z}), so the stats travel with it, and an
 * {@code ItemStatContributor} puts them on its sheet, so they count wherever the necklace is worn and show in
 * its lore. Items are taken before the stack is written; a stack that changed under the menu refunds them.
 */
final class Synthesizer {

    private Synthesizer() {}

    static boolean is(String id) { return id != null && id.startsWith("SYNTHESIZER_V") && !CrimsonRules.synthSlots(id).isEmpty(); }

    static void register() {
        Sky.items().addStatContributor("crimson_synthesizer", (def, data, rarity, sheet) -> contribute(def, data, sheet));
        Sky.items().addLoreContributor("crimson_synthesizer", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) { return lore(def, data); }
        });
        Sky.commands().register(SkyCommand.of("synthesizer", "Feed X, Y and Z to the Synthesizer you hold")
                .alias("synth").category("Combat").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (CrimsonModule.ready(p)) open(p); }));
    }

    /** Fed points of one slot, clamped to its cap (a hand-edited or migrated value never exceeds it). */
    static int fed(ItemData data, CrimsonTables.SynthSlot s) {
        if (data == null || data.extra == null) return 0;
        String k = CrimsonRules.synthKey(s.item);
        if (!data.extra.has(k)) return 0;
        try { return Math.max(0, Math.min(s.cap, data.extra.get(k).getAsInt())); } catch (RuntimeException e) { return 0; }
    }

    static void contribute(ItemDef def, ItemData data, ItemStatSheet sheet) {
        if (def == null || !is(def.id)) return;
        for (CrimsonTables.SynthSlot s : CrimsonRules.synthSlots(def.id)) {
            int n = fed(data, s);
            Stat st = Stat.parse(s.stat);
            if (n > 0 && st != null) sheet.add(st, n, ItemStatSheet.Source.OTHER);
        }
    }

    static List<String> lore(ItemDef def, ItemData data) {
        if (def == null || !is(def.id)) return List.of();
        List<String> l = new ArrayList<>();
        l.add("&7Current Bonuses:");
        for (CrimsonTables.SynthSlot s : CrimsonRules.synthSlots(def.id)) {
            Stat st = Stat.parse(s.stat);
            int n = fed(data, s);
            l.add(" " + (n >= s.cap ? "&a" : "&e") + n + "&7/&a" + s.cap + " " + (st == null ? s.stat : st.label())
                    + " &8(" + Text.strip(CUtil.name(s.item)) + ")");
        }
        l.add("&8Hold it and type /synthesizer to feed it.");
        return l;
    }

    // ---- the menu ----------------------------------------------------------------------------------------

    static void open(Player p) {
        if (!CrimsonModule.D.synthesizer.get()) { Msg.error(p, "Synthesizers are switched off right now."); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(hand);
        if (!is(id)) { Msg.error(p, "Hold a Synthesizer in your hand first."); return; }
        String uuid = Sky.items().data(hand).uuid;
        menu(p, id, uuid).open(p);
    }

    static Menu menu(Player p, String id, String uuid) {
        Menu m = new Menu("Synthesizer", 4).chrome();
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemData data = Sky.items().data(hand);
        m.set(4, hand.clone());
        List<CrimsonTables.SynthSlot> slots = CrimsonRules.synthSlots(id);
        int[] at = slots.size() == 1 ? new int[]{13} : slots.size() == 2 ? new int[]{12, 14} : new int[]{11, 13, 15};
        for (int i = 0; i < slots.size() && i < at.length; i++) {
            CrimsonTables.SynthSlot s = slots.get(i);
            int n = fed(data, s);
            int have = CUtil.have(p, s.item);
            Stat st = Stat.parse(s.stat);
            List<String> lore = new ArrayList<>();
            lore.add("&7Each &f" + Text.strip(CUtil.name(s.item)) + " &7adds &a+1 " + (st == null ? s.stat : st.label()) + "&7.");
            lore.add("");
            lore.add("&7Synthesized: " + (n >= s.cap ? "&a" : "&e") + n + "&7/&a" + s.cap);
            lore.add("&7" + CUtil.bar(s.cap <= 0 ? 1 : (double) n / s.cap));
            lore.add("&7You have: " + (have > 0 ? "&a" : "&c") + Text.commasInt(have) + " &8(inventory + sacks)");
            lore.add("");
            if (n >= s.cap) lore.add("&aFully synthesized!");
            else {
                lore.add("&eLeft-click to feed 1!");
                lore.add("&eRight-click to feed 10!");
                lore.add("&eShift-click to feed as many as fit!");
            }
            final CrimsonTables.SynthSlot slot = s;
            m.set(at[i], Icons.of(CUtil.icon(s.item, Material.PAPER).getType(), "&5Synthesize " + Text.strip(CUtil.name(s.item)), lore), c -> {
                int want = c.shift() ? Integer.MAX_VALUE : c.right() ? 10 : 1;
                feed(c.player(), id, uuid, slot, want);
                menu(c.player(), id, uuid).open(c.player());
            });
        }
        m.back("Crimson Isle", () -> CrimsonMenus.mainMenu(p));
        return m;
    }

    /**
     * Feed up to {@code want} of one slot's item into the held Synthesizer {@code uuid}. Takes the items first
     * (inventory, then sacks), then writes the stack; if the held stack is no longer that Synthesizer, nothing
     * is taken. Returns how many were fed.
     */
    static int feed(Player p, String id, String uuid, CrimsonTables.SynthSlot s, int want) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!Objects.equals(id, Sky.items().idOf(hand)) || !Objects.equals(uuid, Sky.items().data(hand).uuid)) {
            Msg.error(p, "Keep the Synthesizer in your hand while you feed it.");
            p.closeInventory();
            return 0;
        }
        if (!Sky.items().exists(s.item)) { Msg.error(p, "Nothing on this server makes " + s.item + " yet."); return 0; }
        int n = fed(Sky.items().data(hand), s);
        int room = CrimsonRules.synthTake(n, s.cap, Math.max(0, want));
        int take = Math.min(room, CUtil.have(p, s.item));
        if (room <= 0) { Msg.error(p, "That bonus is already full."); return 0; }
        if (take <= 0) { Msg.error(p, "You don't have any " + Text.strip(CUtil.name(s.item)) + "."); return 0; }
        int took = CUtil.take(p, s.item, take);
        if (took <= 0) return 0;
        String key = CrimsonRules.synthKey(s.item);
        final int total = Math.min(s.cap, n + took);
        try {
            Sky.items().update(hand, d -> d.extra.addProperty(key, total));
            p.getInventory().setItemInMainHand(hand);
        } catch (RuntimeException ex) {
            CUtil.refund(p, s.item, took);
            Msg.error(p, "The Synthesizer would not take it; your items were returned.");
            return 0;
        }
        Msg.send(p, "&aSynthesized &f" + took + "x " + CUtil.name(s.item) + "&a. &7(" + total + "/" + s.cap + ")");
        Msg.sound(p, Sound.BLOCK_BEACON_POWER_SELECT, 1.6f);
        return took;
    }
}
