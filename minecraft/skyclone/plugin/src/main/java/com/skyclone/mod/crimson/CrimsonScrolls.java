package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Crimson Isle travel scrolls (follow-up 2026-10-06). The reference's "Dusty Travel Scroll to the Kuudra Skull"
 * ({@code NETHER_FORTRESS_BOSS_TRAVEL_SCROLL}, registered by materials with {@code extra.warp = "crimson_skull"}, dropped
 * by Infernal Leviathan chests) unlocks the Forgotten Skull fast travel point. Same shape as the mining / combat-zone
 * scrolls: right-click consumes it once and adds the warp to {@link CrimsonData#scrolls}; reading a known one is
 * refused without consuming. The warp's requirement is {@code crimson:scroll:<warp>}.
 */
final class CrimsonScrolls implements Listener {

    /** warp id -> {anchor, display name}. Only warps listed here are ours to unlock (other modules own theirs). */
    static final Map<String, String[]> WARPS = Map.of("crimson_skull", new String[]{"warp.skull", "Forgotten Skull"});

    static void register() {
        for (Map.Entry<String, String[]> w : WARPS.entrySet())
            Sky.worlds().registerWarp(w.getKey(), CrimsonModule.ISLAND, w.getValue()[0], w.getValue()[1], "crimson:scroll:" + w.getKey());
        Sky.requirements().register("crimson", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (!CrimsonModule.ready(p) || parts.length < 3 || !"scroll".equals(parts[1])) return false;
                CrimsonData d = CrimsonModule.data(p);
                return d.scrolls != null && d.scrolls.contains(parts[2]);
            }
            @Override public String describe(String[] parts) {
                if (parts.length >= 3 && "scroll".equals(parts[1])) {
                    String[] w = WARPS.get(parts[2]);
                    return "&athe " + (w == null ? Text.titleCase(parts[2]) : w[1]) + " Travel Scroll";
                }
                return "&a" + String.join(":", parts);
            }
        });
    }

    /** The crimson warp a held item unlocks, or null when it is not one of ours. */
    static String warpOf(ItemDef def) {
        if (def == null || def.extra == null || !def.extra.has("warp")) return null;
        String w = def.extra.get("warp").getAsString();
        return WARPS.containsKey(w) ? w : null;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType().isAir()) return;
        String id = Sky.items().idOf(it);
        if (id == null) return;
        String warp = warpOf(Sky.items().def(id));
        if (warp == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!CrimsonModule.ready(p)) return;
        CrimsonData d = CrimsonModule.data(p);
        if (d.scrolls == null) d.scrolls = new java.util.HashSet<>();
        if (d.scrolls.contains(warp)) { Msg.error(p, "You already know the way: /warp " + warp); return; }
        it.setAmount(it.getAmount() - 1);
        d.scrolls.add(warp);
        Sky.profiles().saveNow(p);
        Msg.box(p, "&a&lTRAVEL SCROLL", List.of("&7You can now warp straight to", "&a" + WARPS.get(warp)[1] + "&7: &e/warp " + warp));
        Msg.sound(p, Sound.ITEM_BOOK_PAGE_TURN, 1f);
    }
}
