package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.ui.Msg;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Museum "Co-op Soulbound" enforcement inside this module (research 08 6.2). The marker is
 * {@code ItemData.extra.museum_soulbound = <profileId>}, written when an item is first donated.
 * <ul>
 *   <li>lore: a {@code Co-op Soulbound} line on every marked item (core only draws it for the def tag);</li>
 *   <li>pickup: a marked item lying on the ground can only be picked up by a member of its co-op;</li>
 *   <li>the Museum itself refuses another co-op's soulbound item ({@link Museum#foreignBound}).</li>
 * </ul>
 * Bank trades already refuse it (bank/Trades.untradeable reads the same key). The auction house checks only
 * def tags today: see Requests in docs/handoff/progression.md.
 */
final class SoulboundGuard implements Listener {

    private final Map<UUID, Long> told = new HashMap<>();

    void registerLore() {
        Sky.items().addLoreContributor("progression.museum_soulbound", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                if (def == null || data == null || data.extra == null) return List.of();
                if (def.has("soulbound")) return List.of();          // core already draws the footer line
                if (!data.extra.has(Museum.SOULBOUND_KEY) && !data.extra.has("coop_soulbound")) return List.of();
                return List.of("&8&l* &8Co-op Soulbound &8&l*");
            }
        });
        // Daedalus Blade: the bestiary half of its passive is implemented here (Bestiary.modify / magicFindVs)
        Sky.items().addLoreContributor("progression.daedalus", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                if (def == null || def.id == null) return List.of();
                if (!def.id.equals("DAEDALUS_AXE") && !def.id.equals("STARRED_DAEDALUS_AXE")) return List.of();
                return List.of("&7Grants &c+" + com.skyclone.api.ui.Text.commas(Prog.daedalusDamage.get()) + "% &7Damage and &b+"
                                + com.skyclone.api.ui.Text.commas(Prog.daedalusMf.get()) + " &bMagic Find",
                        "&7vs mythological creatures per",
                        "&7Mythological Bestiary tier you have.");
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!Prog.museumPickupGuard.get()) return;
        if (!Museum.foreignBound(p, e.getItem().getItemStack())) return;
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = told.get(p.getUniqueId());
        if (last == null || now - last > 5000) {
            told.put(p.getUniqueId(), now);
            Msg.error(p, "That item is Co-op Soulbound to another co-op.");
        }
    }

    void forget(Player p) { told.remove(p.getUniqueId()); }
}
