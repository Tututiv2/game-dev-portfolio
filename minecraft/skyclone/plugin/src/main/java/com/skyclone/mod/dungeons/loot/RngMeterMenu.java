package com.skyclone.mod.dungeons.loot;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.DungeonMenus;
import com.skyclone.mod.dungeons.Floors;
import com.skyclone.mod.dungeons.model.FloorDef;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Dungeon RNG Meter (research 04 sec 3.16): one meter per floor. Pick an item from that floor's chest table; every
 * S-or-better completion adds the run's score; when the meter reaches the item's value the item is guaranteed in the
 * next run's best chest that can hold it (you still pay its price), and the meter resets. Dropping it naturally also
 * resets it.
 */
public final class RngMeterMenu {

    private RngMeterMenu() {}

    public static void open(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        LData d = RewardChests.data(p);
        Menu m = new Menu("RNG Meter", 5).chrome();
        int slot = 10;
        for (FloorDef f : Floors.all()) {
            if (f.isEntrance() || LootTables.floor(f.id) == null) continue;
            if (slot % 9 == 8) slot += 2;
            String sel = d.rngItem.get(f.id);
            LootTables.Entry e = sel == null ? null : LootTables.floor(f.id).entry(sel);
            double xp = d.rngXp.getOrDefault(f.id, 0.0);
            List<String> lore = new ArrayList<>();
            if (e == null) lore.add("&7No item selected.");
            else {
                lore.add("&7Selected: &d" + RewardChests.name(sel));
                lore.add(Text.bar(xp / e.rng, 20) + " &d" + Text.commasInt(Math.min(xp, e.rng)) + "&7/&d" + Text.commasInt(e.rng));
            }
            lore.add("");
            lore.add("&7S runs (score " + DDials.RNG_MIN_SCORE.get() + "+) add their score.");
            lore.add("");
            lore.add("&eClick to choose an item!");
            m.set(slot++, Icons.of(f.master ? Material.WITHER_SKELETON_SKULL : Material.SKELETON_SKULL, (f.master ? "&4" : "&c") + f.display(), lore),
                    c -> floor(c.player(), f));
        }
        m.set(40, Icons.back("Catacombs"), c -> DungeonMenus.main(c.player()));
        m.open(p);
    }

    static void floor(Player p, FloorDef f) {
        LootTables.FloorLoot fl = LootTables.floor(f.id);
        if (fl == null) return;
        List<LootTables.Entry> list = new ArrayList<>();
        for (LootTables.Entry e : fl.entries) if (Sky.items().exists(e.item) && e.cost > 0 || e.rng >= 20_000) list.add(e);
        list.removeIf(e -> !Sky.items().exists(e.item));
        list.sort(Comparator.comparingDouble((LootTables.Entry e) -> -e.rng));
        String sel = RewardChests.data(p).rngItem.get(f.id);
        Sky.menus().paged("RNG Meter - " + f.id, list, e -> {
            ItemStack icon;
            try { icon = LootNames.titled(Sky.items().create(e.item, 1), e.item); } catch (RuntimeException ex) { icon = Icons.of(Material.BARRIER, "&c" + e.item); }
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&7Meter needed: &d" + Text.commasInt(e.rng));
            lore.add("&7Best chest it needs: " + e.min().label());
            lore.add("&7Coin cost: &6" + Text.commas(e.cost));
            lore.add("");
            lore.add("&8Changing or clearing empties the meter.");
            lore.add(e.item.equals(sel) ? "&aSelected! &eClick to clear." : "&eClick to select!");
            return Icons.withLore(icon, lore);
        }, pc -> {
            Player q = pc.click().player();
            LData d = RewardChests.data(q);
            // review fix: clearing also empties the meter. Before, clear-then-select skipped the "switching resets" rule,
            // so XP banked toward one item could be moved onto a cheaper one and claim it at once.
            if (pc.entry().item.equals(d.rngItem.get(f.id))) {
                d.rngItem.remove(f.id);
                d.rngXp.put(f.id, 0.0);
                Msg.send(q, "&7RNG Meter cleared for " + f.display() + " &8(the meter is empty again)&7.");
            } else {
                if (d.rngXp.getOrDefault(f.id, 0.0) > 0) { d.rngXp.put(f.id, 0.0); Msg.send(q, "&7Switching items resets the meter."); }
                d.rngItem.put(f.id, pc.entry().item);
                Msg.success(q, "RNG Meter for " + f.display() + " set to " + LootNames.plain(pc.entry().item) + ".");
            }
            floor(q, f);
        }, page -> page.set(48, Icons.back("RNG Meter"), c -> open(c.player())), 0).open(p);   // fixer: paged() only builds the menu
    }
}
