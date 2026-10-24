package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * The "Deep Market" perk (reference: Sky Mall): one random buff per SkyBlock day on every mining island.
 * Research 2.9: +100 Mining Speed, +50 Mining Fortune, +15% powder, -20% pickaxe cooldowns,
 * 10x treasure goblin chance, 5x Titanium drops.
 */
final class SkyMall {

    enum Buff {
        SPEED("&6+100 ⸕ Mining Speed"),
        FORTUNE("&6+50 ☘ Mining Fortune"),
        POWDER("&a+15% &7more Powder while mining"),
        COOLDOWN("&a-20% &7Pickaxe Ability cooldowns"),
        GOBLINS("&a10x &7chance to find treasure goblins"),
        TITANIUM("&a5x &fTitanium &7drops");

        final String text;
        Buff(String text) { this.text = text; }
    }

    private final MiningModule m;
    private long lastDay = -1;
    /** Admin override, or null to follow the calendar. */
    Buff forced;

    SkyMall(MiningModule m) {
        this.m = m;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::check, 100L, 100L);
    }

    long dayKey() {
        try {
            SkyDate d = Sky.calendar().now();
            return d.year() * 372L + (d.month() - 1) * 31L + d.day();
        } catch (Throwable t) {
            return System.currentTimeMillis() / (20L * 60_000L);
        }
    }

    Buff today() {
        if (forced != null) return forced;
        long k = dayKey();
        long h = k * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        return Buff.values()[(int) Math.floorMod(h, (long) Buff.values().length)];
    }

    boolean is(Player p, Buff b) {
        return MiningUtil.ready(p) && MiningUtil.onMiningIsland(p) && m.hotm.has(p, "sky_mall") && today() == b;
    }

    double powderBonus(Player p) { return is(p, Buff.POWDER) ? 15 : 0; }

    private void check() {
        long k = dayKey();
        if (k == lastDay) return;
        boolean first = lastDay < 0;
        lastDay = k;
        if (first) return;
        Buff b = today();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!MiningUtil.ready(p) || !MiningUtil.onMiningIsland(p) || !m.hotm.has(p, "sky_mall")) continue;
            Msg.send(p, "&b&lDEEP MARKET &7Today's buff: " + b.text + "&7.");
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f);
        }
    }
}
