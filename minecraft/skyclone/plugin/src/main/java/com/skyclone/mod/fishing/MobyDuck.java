package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Moby-Duck (audit 2026-10-06 F10; wiki /w/Moby-Duck): the Junk Sinker's 1-in-6 OUTSTANDING junk catch, an
 * "aging item". Carried in the inventory it gains +0.1 Fishing Wisdom per hour online, up to +30 (300 hours), and
 * then evolves into the Collector's Edition. Reading (right-clicking) either book uses it up for that much Fishing
 * Wisdom for 60 minutes; the FIRST Collector's Edition a player reads also gives +1 Fishing Wisdom for good. The
 * bonus lives on the item ({@code extra.moby_wisdom}), so the book is unstackable and keeps its age when traded.
 *
 * <p>Also the fishing stat provider: the read buff, the Bookworm +1 and the Pearl of the Swamp's +1 Treasure Chance.
 */
final class MobyDuck implements Listener {

    static final String DUCK = "MOBY_DUCK";
    static final String EDITION = "MOBY_DUCK_COLLECTOR_EDITION";
    static final String KEY = "moby_wisdom";
    /** Online minutes since the last aging step, per player (a step is 6 minutes = 0.1 hour). */
    private static final Map<UUID, Integer> MINUTES = new HashMap<>();
    static final int STEP_MINUTES = 6;

    MobyDuck() {}

    static void register() {
        Sky.items().addLoreContributor("fishing_moby_duck", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                if (def == null || !DUCK.equals(def.id)) return List.of();
                return List.of("", "&7Current Bonus: &3+" + FishMath.oneDecimal(wisdom(data)) + "☯ Fishing Wisdom"
                        + " &8/ " + FishMath.oneDecimal(FishDials.MOBY_MAX.get()));
            }
        });
        Sky.stats().addProvider("fishing", MobyDuck::stats);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            if (!FishDials.MOBY_DUCK.get()) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!Fishing.ready(p)) continue;
                int m = MINUTES.merge(p.getUniqueId(), 1, Integer::sum);
                if (m < STEP_MINUTES) continue;
                MINUTES.put(p.getUniqueId(), 0);
                age(p, FishDials.MOBY_PER_HOUR.get() * STEP_MINUTES / 60.0);
            }
        }, 1200L, 1200L);
    }

    static double wisdom(ItemData d) {
        return d == null || d.extra == null || !d.extra.has(KEY) ? 0 : d.extra.get(KEY).getAsDouble();
    }

    /** Age every Moby-Duck the player carries by {@code add} Fishing Wisdom; one that reaches the cap evolves. */
    static void age(Player p, double add) {
        if (add <= 0) return;
        double max = FishDials.MOBY_MAX.get();
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length; i++) {
            ItemStack it = inv[i];
            if (it == null || !DUCK.equals(Sky.items().idOf(it))) continue;
            double now = FishMath.mobyAge(wisdom(Sky.items().data(it)), add, max);
            if (now >= max && Sky.items().exists(EDITION)) {
                p.getInventory().setItem(i, Sky.items().create(EDITION, 1));
                Msg.send(p, "&d&lEVOLUTION! &7Your " + Fishing.name(DUCK) + " &7has evolved into the "
                        + Fishing.name(EDITION) + "&7!");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            } else {
                Sky.items().update(it, d -> d.extra.addProperty(KEY, now));
                p.getInventory().setItem(i, it);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        String id = it == null ? null : Sky.items().idOf(it);
        if (!DUCK.equals(id) && !EDITION.equals(id)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Fishing.ready(p) || !FishDials.MOBY_DUCK.get()) return;
        FishData d = Fishing.data(p);
        boolean edition = EDITION.equals(id);
        double w = edition ? FishDials.MOBY_MAX.get() : wisdom(Sky.items().data(it));
        if (w <= 0) {
            Msg.error(p, "The pages are still stuck together. Carry it a while longer.");
            return;
        }
        long now = System.currentTimeMillis();
        double keep = now < d.mobyUntil ? d.mobyWisdom : 0;
        d.mobyWisdom = Math.max(keep, w);
        d.mobyUntil = now + Math.max(1, FishDials.MOBY_MINUTES.get()) * 60_000L;
        it.setAmount(it.getAmount() - 1);
        Msg.send(p, "&3You read " + Fishing.name(id) + "&3: &b+" + FishMath.oneDecimal(d.mobyWisdom)
                + "☯ Fishing Wisdom &3for &a" + FishDials.MOBY_MINUTES.get() + " minutes&3.");
        if (edition && !d.mobyBookworm) {
            d.mobyBookworm = true;
            Msg.send(p, "&6&lBOOKWORM! &3+" + FishMath.oneDecimal(FishDials.MOBY_BOOKWORM.get()) + "☯ Fishing Wisdom &7for good.");
        }
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        Sky.stats().invalidate(p);
        Sky.profiles().saveNow(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { MINUTES.remove(e.getPlayer().getUniqueId()); }

    /** The fishing stat provider: read buff + Bookworm (Fishing Wisdom), Pearl of the Swamp (Treasure Chance). */
    static void stats(Player p, StatMap into) {
        if (!Fishing.ready(p)) return;
        FishData d = Fishing.data(p);
        into.add(Stat.FISHING_WISDOM, FishMath.mobyWisdom(d.mobyWisdom, d.mobyUntil, System.currentTimeMillis(),
                d.mobyBookworm, FishDials.MOBY_BOOKWORM.get()));
        if (d.swampPearl) into.add(Stat.TREASURE_CHANCE, FishDials.PEARL_TREASURE.get());
    }
}
