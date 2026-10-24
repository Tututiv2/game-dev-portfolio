package com.skyclone.mod.reforges;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Book of Stats counters: kills on a weapon, crops broken on a farming tool. A farmer breaks many
 * crops a second, so counts are held per item (by its instance uuid) and written onto the stack every
 * two seconds instead of rewriting its lore on every block.
 */
final class StatsBook implements Listener {

    /** player -> item uuid -> pending count. */
    private final Map<UUID, Map<String, Long>> pending = new HashMap<>();

    void register() {
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::flushAll, 40L, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || !p.isOnline()) return;
        note(p, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGather(SkyEvents.Gather e) {
        if (e.player == null || e.kind != LootSource.FARMING) return;
        note(e.player, false);
    }

    private void note(Player p, boolean kill) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemDef def = Gear.def(hand);
        if (def == null) return;
        if (kill ? !ItemMods.weapon(def) : !ItemMods.farmingTool(def)) return;
        ItemData d = Sky.items().data(hand);
        if (d.extra == null || !d.extra.has("stats_book") || d.uuid == null) return;
        pending.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).merge(d.uuid, 1L, Long::sum);
    }

    private void flushAll() {
        if (pending.isEmpty()) return;
        for (UUID id : new ArrayList<>(pending.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) { pending.remove(id); continue; }
            flush(p);
        }
    }

    /** Write pending counts onto the matching stacks (held first, then anywhere in the inventory). */
    private void flush(Player p) {
        Map<String, Long> m = pending.remove(p.getUniqueId());
        if (m == null || m.isEmpty()) return;
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        int held = inv.getHeldItemSlot();
        for (Map.Entry<String, Long> en : m.entrySet()) {
            int slot = -1;
            if (matches(contents, held, en.getKey())) slot = held;
            else for (int i = 0; i < contents.length; i++) if (matches(contents, i, en.getKey())) { slot = i; break; }
            if (slot < 0) continue;   // the item left the inventory; those few counts are lost
            ItemStack s = contents[slot];
            long add = en.getValue();
            Sky.items().update(s, x -> x.extra.addProperty("stats_book", ItemMods.count(x, "stats_book") + add));
            inv.setItem(slot, s);
        }
    }

    private static boolean matches(ItemStack[] contents, int i, String uuid) {
        if (i < 0 || i >= contents.length || Gear.empty(contents[i]) || Gear.def(contents[i]) == null) return false;
        return uuid.equals(Sky.items().data(contents[i]).uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { flush(e.getPlayer()); }
}
