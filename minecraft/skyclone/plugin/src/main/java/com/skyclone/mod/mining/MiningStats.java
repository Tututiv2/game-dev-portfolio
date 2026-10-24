package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import org.bukkit.entity.Player;

/**
 * StatProvider "mining": Heart of the Mountain perks, the Deep Market buff, active pickaxe abilities,
 * deployable lanterns, the Mining Jubilee wisdom and the frozen-mineshaft perks. Block-specific bonuses
 * (gem speed, Strong Arm, Front Loaded, pickaxe passives) are added at break time by {@link Breaker}.
 */
final class MiningStats {

    private final MiningModule m;

    MiningStats(MiningModule m) {
        this.m = m;
        Sky.stats().addProvider("mining", this::contribute);
    }

    private void contribute(Player p, StatMap s) {
        if (!MiningUtil.ready(p)) return;
        Hotm h = m.hotm;
        s.add(Stat.MINING_SPEED, h.value(p, "mining_speed") + h.value(p, "speedy_mineman"));
        s.add(Stat.MINING_FORTUNE, h.value(p, "mining_fortune") + h.value(p, "fortunate_mineman"));
        s.add(Stat.MINING_SPREAD, h.value(p, "efficient_miner"));
        s.add(Stat.ORE_FORTUNE, h.value(p, "old_school"));
        s.add(Stat.GEMSTONE_FORTUNE, h.value(p, "gem_lover"));
        s.add(Stat.BLOCK_FORTUNE, h.value(p, "blockhead"));
        s.add(Stat.DWARVEN_METAL_FORTUNE, h.value(p, "metal_head"));
        s.add(Stat.MINING_WISDOM, h.value(p, "seasoned_mineman"));
        s.add(Stat.HEAT_RESISTANCE, h.value(p, "keep_it_cool"));
        s.add(Stat.COLD_RESISTANCE, h.value(p, "warm_heart"));
        s.add(Stat.PRISTINE, h.value(p, "mining_master"));
        // Glacite Golem pet "Frozen Perfection": +X Pristine per frozen corpse looted in the current mineshaft
        if (m.tunnels != null) {
            int corpses = m.tunnels.corpsesThisRun(p);
            if (corpses > 0) {
                s.add(Stat.PRISTINE, corpses * MiningUtil.pet(p, "glacite_golem_frozen_perfection"));
                // Mammoth pet "Corpse Crusher": +X Mining Fortune per frozen corpse looted in the current mineshaft
                s.add(Stat.MINING_FORTUNE, corpses * MiningUtil.pet(p, "mammoth_corpse_crusher"));
            }
        }

        String island = MiningUtil.island(p);
        boolean mining = island != null && MiningUtil.MINING_ISLANDS.contains(island);
        if (mining) {
            HotmTree.Perk sf = h.tree.perk("subterranean_fisher");
            int lvl = h.effective(p, "subterranean_fisher");
            if (sf != null && lvl > 0) {
                s.add(Stat.FISHING_SPEED, HotmTree.Perk.at(sf.values_fishingspeed, lvl));
                s.add(Stat.SEA_CREATURE_CHANCE, HotmTree.Perk.at(sf.values_seacreaturechance, lvl));
            }
            if (h.has(p, "miners_blessing")) s.add(Stat.MAGIC_FIND, 30);
            double lone = h.value(p, "lonesome_miner");
            if (lone > 0) for (Stat st : new Stat[]{Stat.STRENGTH, Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE, Stat.DEFENSE, Stat.HEALTH}) s.addPercent(st, lone);
            if (m.skyMall.is(p, SkyMall.Buff.SPEED)) s.add(Stat.MINING_SPEED, 100);
            if (m.skyMall.is(p, SkyMall.Buff.FORTUNE)) s.add(Stat.MINING_FORTUNE, 50);
            // Mining Jubilee (Aug-2026 rework): +25% Mining Fortune and +25 Mining Wisdom on the mining islands
            if (Breaker.fiesta(m)) {
                s.add(Stat.MINING_WISDOM, m.dials.fiestaWisdom.get());
                s.addPercent(Stat.MINING_FORTUNE, m.dials.fiestaFortunePct.get());
            }
        }
        if ("mineshaft".equals(island)) {
            s.add(Stat.MINING_FORTUNE, h.value(p, "rags_to_riches"));
            s.add(Stat.MINING_SPEED, h.value(p, "eager_adventurer"));
            m.tunnels.mayhem(p, s);
        }
        m.abilities.contribute(p, s);
        m.deploy.contribute(p, s);
    }

    /** Gemstone Spread (no core stat exists for it): Steady Hand in mineshafts + the Will-o'-wisp. */
    double gemSpread(Player p) {
        double v = 0;
        if ("mineshaft".equals(MiningUtil.island(p))) v += m.hotm.value(p, "steady_hand");
        v += m.deploy.gemSpread(p);
        return v;
    }
}
