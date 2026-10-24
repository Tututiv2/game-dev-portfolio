package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fishing items with behaviour of their own (wave 5):
 * <ul>
 *   <li><b>Thunder bottles</b> - an Empty Thunder Bottle in your inventory charges every time you slay a lava
 *       sea creature (charge = its Fishing XP x dial, +1% with a Pulse Ring - the ring's reference text) and
 *       becomes a Thunder in a Bottle at 50,000. The charge lives on the item ({@code extra.thunder_charge}).</li>
 *   <li><b>Isopod Husk</b> (Giant Isopod drop) - right-click to eat: +1 Respiration for good, at most 5. Respiration
 *       is the vanilla oxygen bonus (as the enchantment: you hold your breath longer).</li>
 *   <li><b>Shark Tooth Necklaces</b> - during the Fishing Festival a slain shark gets an extra roll at its
 *       tooth's own chance x the necklace's +5..25%.</li>
 * </ul>
 */
final class FishItems implements Listener {

    static final String EMPTY_BOTTLE = "THUNDER_IN_A_BOTTLE_EMPTY";
    static final String FULL_BOTTLE = "THUNDER_IN_A_BOTTLE";
    static final String CHARGE_KEY = "thunder_charge";
    static final String HUSK = "ISOPOD_HUSK";
    static final NamespacedKey RESPIRATION = new NamespacedKey("skyclone", "fishing_isopod_husk");
    static final Set<String> SHARKS = Set.of("sc_nurse_shark", "sc_blue_shark", "sc_tiger_shark", "sc_great_white_shark");

    static void register() {
        Sky.items().addLoreContributor("fishing_thunder_bottle", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                if (def == null || !EMPTY_BOTTLE.equals(def.id)) return List.of();
                long c = charge(data);
                return List.of("", "&7Charge: &e" + Text.commasInt(c) + "&6/&e" + Text.commasInt(FishDials.THUNDER_CHARGE_NEEDED.get()));
            }
        });
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (Fishing.ready(p)) syncRespiration(p);
        }, 200L, 200L);
    }

    static long charge(ItemData d) {
        return d == null || d.extra == null || !d.extra.has(CHARGE_KEY) ? 0 : d.extra.get(CHARGE_KEY).getAsLong();
    }

    // ---- kills: thunder charge, shark teeth -----------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || !Fishing.isSeaCreature(e.def) || !Fishing.ready(p)) return;
        boolean lava = e.def.extra != null && e.def.extra.has("lava") && e.def.extra.get("lava").getAsBoolean();
        if (lava && FishDials.THUNDER_BOTTLES.get()) chargeBottle(p, Fishing.fishingXpOf(e.def));
        if (SHARKS.contains(e.def.id) && FishDials.SHARK_TOOTH_NECKLACES.get() && Fishing.festival()) {
            double bonus = FishEffects.sharkToothBonus(p);
            if (bonus > 0) for (DropDef d : e.def.drops) {
                if (!d.item.endsWith("_SHARK_TOOTH") || !Sky.items().exists(d.item)) continue;
                if (ThreadLocalRandom.current().nextDouble() < d.chance * bonus) {
                    Sky.loot().give(p, d.item, 1, LootSource.MOB_DROP);
                    Msg.send(p, "&bYour necklace caught an extra " + Fishing.name(d.item) + "&b!");
                }
            }
        }
    }

    /** Fish Bowls: an extra rare-shard roll on a plain catch (see {@link FishBowls}). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFishCaught(SkyEvents.FishCaught e) {
        Player p = e.player;
        if (p == null || e.seaCreature || !Fishing.ready(p)) return;
        FishBowls.roll(p, Fishing.island(p.getLocation()));
    }

    /** Charge the first single Empty Thunder Bottle in the inventory. Returns the charge added (0 = no bottle). */
    static long chargeBottle(Player p, double fishingXp) {
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length; i++) {
            ItemStack it = inv[i];
            if (it == null || it.getAmount() != 1 || !EMPTY_BOTTLE.equals(Sky.items().idOf(it))) continue;
            long add = FishMath.thunderCharge(fishingXp, FishDials.THUNDER_CHARGE_MULT.get(), FishEffects.pulseRing(p));
            if (add <= 0) return 0;
            long now = charge(Sky.items().data(it)) + add;
            long need = Math.max(1, FishDials.THUNDER_CHARGE_NEEDED.get());
            if (now >= need && Sky.items().exists(FULL_BOTTLE)) {
                p.getInventory().setItem(i, Sky.items().create(FULL_BOTTLE, 1));
                Msg.send(p, "&5&lTHUNDER IN A BOTTLE! &dYour bottle crackles - it is full.");
                p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.4f);
            } else {
                long v = Math.min(now, need);
                Sky.items().update(it, d -> d.extra.addProperty(CHARGE_KEY, v));
                p.getInventory().setItem(i, it);
                Sky.vitals().flash(p, "&5Thunder Bottle: &e" + Text.commasInt(v) + "&6/&e" + Text.commasInt(need), 40);
            }
            return add;
        }
        return 0;
    }

    // ---- Isopod Husk ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !HUSK.equals(Sky.items().idOf(it))) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Fishing.ready(p)) return;
        FishData d = Fishing.data(p);
        int max = Math.max(0, FishDials.HUSK_MAX.get());
        if (d.isopodHusks >= max) {
            Msg.error(p, "The scratching in your throat is too much - you cannot eat another (" + max + "/" + max + ").");
            return;
        }
        it.setAmount(it.getAmount() - 1);
        d.isopodHusks++;
        syncRespiration(p);
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 1f, 0.8f);
        Msg.send(p, "&3You ate an Isopod Husk: &b+1 Respiration &7(" + d.isopodHusks + "/" + max + ").");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (Fishing.ready(e.getPlayer())) syncRespiration(e.getPlayer()); }, 40L);
    }

    /** The oxygen bonus equals the husks this profile has eaten (re-applied, so a profile switch follows). */
    static void syncRespiration(Player p) {
        try {
            AttributeInstance a = p.getAttribute(Attribute.OXYGEN_BONUS);
            if (a == null) return;
            int n = Math.min(Fishing.data(p).isopodHusks, Math.max(0, FishDials.HUSK_MAX.get()));
            AttributeModifier cur = null;
            for (AttributeModifier m : a.getModifiers()) if (RESPIRATION.equals(m.getKey())) cur = m;
            if (cur != null && cur.getAmount() == n) return;
            if (cur != null) a.removeModifier(cur);
            if (n > 0) a.addModifier(new AttributeModifier(RESPIRATION, n, AttributeModifier.Operation.ADD_NUMBER));
        } catch (RuntimeException ignored) { }
    }
}
