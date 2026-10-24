package com.skyclone.mod.enchanting;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Enchants that level up by use (research 01 §14.1): Champion & Toxophilite (Combat XP), Compact
 * (blocks mined), Cultivating (crops), Absorb (logs), Expertise (sea creature kills). The counter
 * lives on the ITEM (ItemData.extra "ench_progress_&lt;id&gt;"), so it travels with it. Progress is
 * batched in memory and written to the item at most every few seconds (re-rendering lore on every
 * crop would be wasteful), and immediately when a level is crossed.
 */
final class UseLeveling implements Listener {

    private record Key(UUID player, String itemUuid, String enchant) {}

    private static final Map<Key, Long> PENDING = new HashMap<>();

    void register() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(Sky.plugin(), UseLeveling::flushAll, 100L, 100L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onXp(SkyEvents.SkillXpGain e) {
        if (e.skill != SkillType.COMBAT || e.amount <= 0 || !EnchantingModule.ready(e.player)) return;
        ItemStack held = Gear.held(e.player);
        long amt = Math.round(e.amount);
        if (CombatEnchants.isBow(Gear.def(held))) bump(e.player, held, "toxophilite", amt);
        else bump(e.player, held, "champion", amt);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGather(SkyEvents.Gather e) {
        if (!EnchantingModule.ready(e.player)) return;
        ItemStack held = Gear.held(e.player);
        if (e.kind == LootSource.MINING) {
            bump(e.player, held, "compact", 1);
            compactDrop(e.player, held, e.what);
        } else if (e.kind == LootSource.FARMING) {
            bump(e.player, held, "cultivating", Math.max(1, e.amount));
        } else if (e.kind == LootSource.FORAGING) {
            bump(e.player, held, "absorb", 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || !e.def.has("sea_creature") || !EnchantingModule.ready(e.killer)) return;
        bump(e.killer, Gear.held(e.killer), "expertise", 1);
    }

    /** Compact: a chance for a mined block to also drop its enchanted form. */
    private static void compactDrop(Player p, ItemStack held, String what) {
        if (what == null || !EnchantingModule.dials().effects.get()) return;
        int lvl = Gear.level(held, "compact");
        EnchantSpec s = EnchantingModule.reg().spec("compact");
        if (lvl <= 0 || s == null) return;
        if (ThreadLocalRandom.current().nextDouble() * 100 >= s.v(1, lvl)) return;
        String w = what.toUpperCase(Locale.ROOT);
        for (String cand : new String[]{"ENCHANTED_" + w, "ENCHANTED_" + w.replace("_ORE", ""), "ENCHANTED_" + w.replace("_BLOCK", "")}) {
            if (Sky.items().exists(cand)) { Sky.loot().give(p, cand, 1, LootSource.MINING); return; }
        }
    }

    static void bump(Player p, ItemStack held, String enchant, long amount) {
        if (amount <= 0 || !Gear.isSky(held)) return;
        ItemData d = Sky.items().data(held);
        Integer lvl = d.enchants == null ? null : d.enchants.get(enchant);
        if (lvl == null || lvl <= 0 || d.uuid == null) return;
        EnchantSpec s = EnchantingModule.reg().spec(enchant);
        if (s == null || !s.levelsByUse()) return;
        Key k = new Key(p.getUniqueId(), d.uuid, enchant);
        long pending = PENDING.merge(k, amount, Long::sum);
        long have = StatEnchants.counter(d, enchant) + pending;
        if (lvl < s.maxLevel && have >= s.thresholdFor(lvl + 1)) flush(p, k);
    }

    private static void flushAll() {
        for (Iterator<Map.Entry<Key, Long>> it = PENDING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Long> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey().player());
            if (p == null) { it.remove(); continue; }
            Key k = en.getKey();
            long add = en.getValue();
            it.remove();
            write(p, k, add);
        }
    }

    private static void flush(Player p, Key k) {
        Long add = PENDING.remove(k);
        if (add != null) write(p, k, add);
    }

    /** Find the stack with that uuid and add the progress (+ level up). */
    private static void write(Player p, Key k, long add) {
        ItemStack target = null;
        for (ItemStack s : p.getInventory().getContents()) {
            if (!Gear.isSky(s)) continue;
            ItemData d = Sky.items().data(s);
            if (k.itemUuid().equals(d.uuid)) { target = s; break; }
        }
        if (target == null) return;
        EnchantSpec s = EnchantingModule.reg().spec(k.enchant());
        if (s == null) return;
        int[] up = {0};
        Sky.items().update(target, d -> {
            long v = StatEnchants.counter(d, k.enchant()) + add;
            d.extra.add(StatEnchants.counterKey(k.enchant()), new JsonPrimitive(v));
            int cur = d.enchants.getOrDefault(k.enchant(), 1);
            int next = Math.max(cur, s.levelForCounter(v));
            if (next > cur) { d.enchants.put(k.enchant(), next); up[0] = next; }
        });
        if (up[0] > 0) announce(p, s, up[0]);
    }

    /**
     * Raise a level-by-use enchant on a stack whose counter another module bumped directly (dungeons adds
     * Hecatomb S-runs to the helmet's {@code ench_progress_hecatomb}). Never lowers a level.
     */
    static void levelUp(Player p, ItemStack target, String enchant, int level) {
        EnchantSpec s = EnchantingModule.reg().spec(enchant);
        if (s == null || target == null) return;
        int[] up = {0};
        Sky.items().update(target, d -> {
            int cur = d.enchants.getOrDefault(enchant, 0);
            int next = Math.min(s.maxLevel, Math.max(cur, level));
            if (cur > 0 && next > cur) { d.enchants.put(enchant, next); up[0] = next; }
        });
        if (up[0] > 0) announce(p, s, up[0]);
    }

    private static void announce(Player p, EnchantSpec s, int level) {
        Msg.send(p, "&dYour " + s.name + " enchantment rose to " + EnchantSpec.roman(level) + "!");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.8f);
        Sky.stats().invalidate(p);
    }
}
