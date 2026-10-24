package com.skyclone.mod.weapons;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dungeon-only weapon behaviour that talks to the dungeons module through public doors only (wave 5):
 * <ul>
 *   <li><b>Class-ability weapons</b> (Highlord, Shieldmaiden, Starward, Riptide, Warden's Blade, Earthen Blade,
 *       Adaptive Blade, Fel Sword; reference lore "Right-click to use your class ability!"): a right-click inside a
 *       dungeon uses the dungeon class ability. The dungeons module has no API call for it, but it binds the ability
 *       to the swap-hands key (F) through {@link PlayerSwapHandItemsEvent} and cancels that event when it handled it;
 *       this class fires exactly that event (never sneaking: sneak + F is the Ultimate). Outside a run nothing happens
 *       (the dungeons listener ignores it and nothing swaps, because the event is ours). A blade with wither scrolls
 *       bound (dungeons' {@code extra.dungeon_scrolls}) casts the scrolls instead, as in the reference.</li>
 * </ul>
 * The Spiritual reforge's spirit decoy (bows) is the reforges module's ({@code Bonuses.spiritual}); an earlier draft
 * of this class summoned one too, which would have doubled it.
 */
final class DungeonWeapons implements Listener {

    static final String SCROLLS_KEY = "dungeon_scrolls";

    private final Traits traits;
    private final Map<UUID, Long> lastClick = new HashMap<>();
    /** Fired by us: our own listeners must not treat it as a real F press. */
    static boolean firing;

    DungeonWeapons(Traits traits) { this.traits = traits; }

    // ---- class ability on right-click ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK) return;
        if (e.useItemInHand() == Event.Result.DENY) return;   // the click was already consumed (dungeon blocks, scrolls)
        Player p = e.getPlayer();
        if (p.isSneaking()) return;                            // sneak + F is the Ultimate: never fire that by accident
        if (act == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) return;
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Traits.idOf(held);
        if (!traits.owns(id) || !traits.of(id).classAbility) return;
        if (!WeaponDials.CLASS_ABILITY.get() || !Passives.inDungeon(p)) return;
        if (hasScrolls(held) || hasOwnRightClick(id)) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 250) return;          // one cast per click (air + block can both fire)
        lastClick.put(p.getUniqueId(), now);
        if (!Sky.items().meetsRequirements(p, held, true)) return;
        if (castClassAbility(p)) e.setUseItemInHand(Event.Result.DENY);
    }

    /** Ask the dungeons module for the class ability the way the F key does. True when it took the press. */
    static boolean castClassAbility(Player p) {
        PlayerSwapHandItemsEvent ev = new PlayerSwapHandItemsEvent(p,
                p.getInventory().getItemInOffHand().clone(), p.getInventory().getItemInMainHand().clone());
        firing = true;
        try {
            Bukkit.getPluginManager().callEvent(ev);
        } finally {
            firing = false;
        }
        return ev.isCancelled();
    }

    static boolean hasScrolls(ItemStack held) {
        try {
            ItemData d = Sky.items().data(held);
            JsonElement s = d == null ? null : d.extra.get(SCROLLS_KEY);
            if (s == null || s.isJsonNull()) return false;
            if (s.isJsonArray()) return !s.getAsJsonArray().isEmpty();
            if (s.isJsonObject()) return !s.getAsJsonObject().isEmpty();
            return !s.getAsString().isBlank();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean hasOwnRightClick(String id) {
        ItemDef d = Sky.items().def(id);
        if (d == null) return false;
        for (AbilityDef a : d.abilities) if (a != null && a.trigger == AbilityDef.Trigger.RIGHT_CLICK) return true;
        return false;
    }

    /** Nothing of ours to clean up any more (the Spiritual decoy is the reforges module's, see the class doc). */
    void removeAll() { }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastClick.remove(e.getPlayer().getUniqueId()); }
}
