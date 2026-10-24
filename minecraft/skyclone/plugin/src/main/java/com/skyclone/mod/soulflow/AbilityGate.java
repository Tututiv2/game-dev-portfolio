package com.skyclone.mod.soulflow;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Msg;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Soulstream costs on OTHER modules' abilities, without touching their code.
 *
 * <p>Core fires item abilities from a HIGH-priority interact listener. This one runs at LOW on the same events, works
 * out which ability core is about to try (same trigger rules, same "click on a chest is not an ability" rule), and:
 * <ol>
 *   <li>refuses it with {@code AbilityService.consumeClick} when the ability has a hard Soulstream cost the player
 *       cannot pay ("You don't have enough Soulstream!"), so no mana and no cooldown are spent;</li>
 *   <li>otherwise, when ordinary mana is short, tops it up from Overflow Mana.</li>
 * </ol>
 * The cost itself is taken on {@link SkyEvents.AbilityUsed}, which core fires only after the handler SUCCEEDED, so a
 * refused cast (cooldown, no target, out of mana) costs no Soulstream.
 */
final class AbilityGate implements Listener {

    private final SoulflowService svc;
    private final Map<UUID, Long> lastMsg = new HashMap<>();

    AbilityGate(SoulflowService svc) { this.svc = svc; }

    // ---- which ability is this click? (mirror of core's AbilityListener + AbilityServiceImpl.find) ----

    static AbilityDef find(ItemDef def, AbilityDef.Trigger t) {
        if (def == null || def.abilities == null || def.abilities.isEmpty() || t == null) return null;
        for (AbilityDef a : def.abilities) if (a != null && a.trigger == t) return a;
        if (t == AbilityDef.Trigger.SNEAK_RIGHT_CLICK) {
            for (AbilityDef a : def.abilities) if (a != null && a.trigger == AbilityDef.Trigger.RIGHT_CLICK) return a;
        }
        return null;
    }

    /** The cost entry for an ability on an item, or null when it is free / conditional and the condition is off. */
    SoulflowConfig.Cost costOf(ItemStack stack, ItemDef def, AbilityDef a) {
        if (def == null || a == null) return null;
        SoulflowConfig.Cost c = svc.config().costFor(a.id, def.id, a.trigger == null ? null : a.trigger.name());
        if (c == null) return null;
        if (c.whenExtra != null && !extraPositive(stack, c.whenExtra)) return null;
        return c;
    }

    static boolean extraPositive(ItemStack stack, String key) {
        try {
            ItemData d = Sky.items().data(stack);
            JsonElement e = d == null || d.extra == null ? null : d.extra.get(key);
            return e != null && e.getAsDouble() > 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ---- pre-check --------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action act = e.getAction();
        if (act == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType().isAir()) return;
        if (act == Action.RIGHT_CLICK_BLOCK && !p.isSneaking() && e.getClickedBlock() != null
                && e.getClickedBlock().getType().isInteractable()) return;   // core opens the block instead
        AbilityDef.Trigger t;
        if (act == Action.RIGHT_CLICK_AIR || act == Action.RIGHT_CLICK_BLOCK) {
            t = p.isSneaking() ? AbilityDef.Trigger.SNEAK_RIGHT_CLICK : AbilityDef.Trigger.RIGHT_CLICK;
        } else {
            t = AbilityDef.Trigger.LEFT_CLICK;
        }
        if (!before(p, held, t)) {
            if (t != AbilityDef.Trigger.LEFT_CLICK) e.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof LivingEntity)) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType().isAir()) return;
        before(p, held, p.isSneaking() ? AbilityDef.Trigger.SNEAK_RIGHT_CLICK : AbilityDef.Trigger.RIGHT_CLICK);
    }

    /** False = the click was refused (the ability must not fire). */
    boolean before(Player p, ItemStack held, AbilityDef.Trigger t) {
        if (!SoulflowDials.on() || !Sky.profiles().isReady(p)) return true;
        ItemDef def = Sky.items().def(Sky.items().idOf(held));
        AbilityDef a = find(def, t);
        if (a == null) return true;
        SoulflowConfig.Cost c = SoulflowDials.COSTS.get() ? costOf(held, def, a) : null;
        if (c != null && !c.soft()) {
            long need = SoulflowDials.scaled(c.cost);
            if (need > 0 && svc.soulflow(p) < need) {
                Sky.abilities().consumeClick(p);
                refuse(p, need);
                return false;
            }
        }
        topUpMana(p, held, a);
        return true;
    }

    private void refuse(Player p, long need) {
        long now = System.currentTimeMillis();
        Long last = lastMsg.get(p.getUniqueId());
        long cd = (long) (Math.max(0, SoulflowDials.MESSAGE_COOLDOWN.get()) * 1000);
        if (last == null || now - last >= cd) {
            lastMsg.put(p.getUniqueId(), now);
            Msg.send(p, "&cYou don't have enough Soulstream! &7(Needs &3" + SoulflowMath.units(need) + "&7)");
            // Playtest wave 5: with 377 internalized and no accessory the line above read as a lie.
            SoulflowData d = svc.data(p);
            if (d != null && d.pool >= need && !svc.poolUsable(p)) {
                Msg.send(p, "&7Your &3" + SoulflowMath.units(d.pool) + " &7internalized Soulstream is locked: carry or bag a"
                        + " &3Soulstream accessory &7(Soulstream Pile or better) to draw on it. &8(/soulflow)");
            }
        }
        Sky.vitals().flash(p, "&c&lNOT ENOUGH SOULSTREAM", 30);
        Msg.sound(p, Sound.BLOCK_SOUL_SAND_BREAK, 0.6f);
    }

    /** Overflow Mana pays what ordinary mana lacks, only for an ability that is actually ready to fire. */
    private void topUpMana(Player p, ItemStack held, AbilityDef a) {
        if (!SoulflowDials.OVERFLOW_FROM_ABILITIES.get() || a.mana <= 0) return;
        SoulflowData d = svc.data(p);
        if (d == null || d.overflow <= 0) return;
        if (Sky.abilities().cooldownLeft(p, a.id) > 0) return;
        if (!Sky.items().meetsRequirements(p, held, false)) return;
        double max = Sky.vitals().maxMana(p);
        double cost = SoulflowMath.manaCost(a.mana, max);
        double draw = SoulflowMath.overflowDraw(Sky.vitals().mana(p), cost, d.overflow, max);
        if (draw <= 0) return;
        Sky.vitals().addMana(p, draw);
        d.overflow = Math.max(0, d.overflow - draw);
        svc.show(p, "&3-" + Math.round(draw) + "ʬ Overflow");
    }

    // ---- the charge ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUsed(SkyEvents.AbilityUsed e) {
        if (!SoulflowDials.on() || !SoulflowDials.COSTS.get() || e.player == null || e.ability == null) return;
        ItemDef def = Sky.items().def(Sky.items().idOf(e.item));
        SoulflowConfig.Cost c = costOf(e.item, def, e.ability);
        if (c == null) return;
        long need = SoulflowDials.scaled(c.cost);
        if (need <= 0) return;
        if (!svc.consume(e.player, need, "ability:" + e.ability.id) && !c.soft()) {
            // the pre-check passed this tick, so only a same-tick inventory change gets here
            Sky.log().fine("[soulflow] " + e.player.getName() + " cast " + e.ability.id + " but could not pay " + need);
        }
    }

    void forget(Player p) { lastMsg.remove(p.getUniqueId()); }
}
