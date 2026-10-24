package com.skyclone.mod.armor;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import org.bukkit.entity.Player;

import java.util.Map;

import static com.skyclone.mod.armor.SetIds.*;

/**
 * Stat-shaped set bonuses. Two providers:
 * <ul>
 *   <li>{@code armor_sets} (registered in enable): flat / percent bonuses that do not depend on
 *       anyone else's numbers;</li>
 *   <li>{@code armor_sets_final} (registered in postEnable, so it runs after every module's
 *       enable-time provider): bonuses that read the accumulated totals - Tuxedo health cap,
 *       Smart Miner, Vindicate, Berserk, Rancher's speed cap.</li>
 * </ul>
 */
final class BonusStats {

    private BonusStats() {}

    static final Stat[] COMBAT_STATS = {Stat.HEALTH, Stat.DEFENSE, Stat.STRENGTH, Stat.SPEED, Stat.CRIT_CHANCE,
            Stat.CRIT_DAMAGE, Stat.INTELLIGENCE, Stat.ATTACK_SPEED, Stat.FEROCITY, Stat.TRUE_DEFENSE, Stat.ABILITY_DAMAGE,
            Stat.MAGIC_FIND};

    /** Farming tiered Farming Fortune at four pieces (research 08: full-set totals minus base piece stats). */
    static double farmingFortuneFull(String setId) {
        return switch (setId) {
            case MELON -> 30;       // TUNE: not published; follows the +15/step ladder below
            case CROPIE -> 45;      // 135 full - 90 base
            case SQUASH -> 60;      // 170 - 110
            case FERMENTO -> 75;    // 205 - 130
            case HELIANTHUS -> 75;  // 225 - 150
            default -> 0;
        };
    }

    static void contribute(Player p, StatMap into) {
        if (!ArmorDials.ENABLED.get()) return;
        Worn w = Worn.of(p);
        if (!w.any()) { auraOnly(p, into); return; }
        String island = ArmorUtil.island(p);
        long now = System.currentTimeMillis();
        ArmorState.P st = ArmorState.of(p.getUniqueId());

        // ---- dragons
        if (w.full(YOUNG)) {
            double max = Sky.vitals().maxHealth(p);
            if (max > 0 && Sky.vitals().health(p) / max > ArmorDials.YOUNG_HP_THRESHOLD.get()) into.add(Stat.SPEED, ArmorDials.YOUNG_SPEED.get());
        }
        if (w.full(SUPERIOR)) for (Stat s : COMBAT_STATS) into.addPercent(s, ArmorDials.SUPERIOR_PCT.get());
        if (w.full(PROTECTOR)) {
            double def = w.sheetStat(PROTECTOR, Stat.DEFENSE);
            into.add(Stat.DEFENSE, SetMath.protectorBonus(def, Sky.vitals().health(p), Sky.vitals().maxHealth(p), ArmorDials.PROTECTOR_PCT.get()));
        }
        if (w.full(OLD)) oldBlood(w, into);
        if (w.full(STRONG)) {
            int n = ArmorState.live(st.strong, now);
            if (n > 0) into.add(Stat.STRENGTH, n * ArmorDials.STRONG_STRENGTH.get());
        }
        if ("the_end".equals(island) && w.pieces(ENDER) > 0) addSheets(w, ENDER, ArmorDials.ENDER_MULT.get() - 1, into);

        // ---- simple full-set stats
        if (w.full(SPEEDSTER)) into.add(Stat.SPEED, ArmorDials.SPEEDSTER_SPEED.get());
        if (w.full(MINER)) into.add(Stat.MINING_SPEED, ArmorDials.MINER_SPEED.get());
        if (w.full(GLACITE)) into.add(Stat.MINING_SPEED, ArmorDials.GLACITE_PER_LEVEL.get() * skill(p, SkillType.MINING));
        if (w.full(EMERALD)) {
            double b = SetMath.emeraldBonus(collection(p, "EMERALD"), ArmorDials.EMERALD_PER.get(), ArmorDials.EMERALD_CAP.get());
            into.add(Stat.HEALTH, b).add(Stat.DEFENSE, b);
        }
        int arachne = w.pieces(ARACHNE);
        if (arachne > 0) into.add(Stat.HEALTH, 5.0 * arachne).add(Stat.DEFENSE, 5.0 * arachne);
        if (w.full(FAIRY)) into.add(Stat.HEALTH, ArmorDials.FAIRY_HP_PER_SOUL.get() * fairySouls(p));
        if (w.full(MAGMA)) {
            double b = SetMath.perKills(counter(w, "ARMOR_OF_MAGMA_CHESTPLATE", BonusListener.KEY_MAGMA), ArmorDials.MAGMA_PER.get(), ArmorDials.MAGMA_CAP.get());
            into.add(Stat.HEALTH, b).add(Stat.INTELLIGENCE, b);
        }
        if (w.full(YOG)) into.add(Stat.MINING_SPEED, SetMath.perKills(counter(w, "ARMOR_OF_YOG_CHESTPLATE", BonusListener.KEY_YOG),
                ArmorDials.YOG_PER.get(), ArmorDials.YOG_CAP.get()));
        if (w.full(CRYSTAL)) {
            double f = SetMath.crystalFactor(p.getLocation().getBlock().getLightLevel());
            addSheets(w, CRYSTAL, f - 1, into);
        }
        if (w.full(WEREWOLF)) {
            int n = ArmorState.live(st.howl, now);
            if (n > 0) into.add(Stat.DEFENSE, n * ArmorDials.WEREWOLF_DEF.get());
        }
        if ("crimson_isle".equals(island) && w.full(RAMPART)) {
            into.add(Stat.HEALTH, ArmorDials.RAMPART_HP.get()).add(Stat.STRENGTH, ArmorDials.RAMPART_STR.get())
                    .add(Stat.CRIT_DAMAGE, ArmorDials.RAMPART_CD.get());
        }
        boolean mythicTime = "hub".equals(island) && ArmorUtil.eventActive(ArmorDials.MYTHIC_EVENT.get());
        if (mythicTime) {
            double m = ArmorDials.MYTHIC_MULT.get() - 1;
            addSheets(w, MYTHOS, m, into);
            addSheets(w, CHALLENGER, m, into);
            addSheets(w, MINOS, m, into);
        }
        // Magic Find "vs mythological creatures" (Familiarity, Crown of Greed): wave 5 moved it to core's
        // per-target loot hook (BonusCombat.magicFindVs), so it is on that creature's drops only.

        // ---- mining
        if (ArmorUtil.onMiningIsland(p)) {
            if (w.pieces(HEAT) > 0) {
                double m = ArmorDials.HEAT_MULT.get() - 1;
                into.add(Stat.DEFENSE, m * w.sheetStat(HEAT, Stat.DEFENSE)).add(Stat.STRENGTH, m * w.sheetStat(HEAT, Stat.STRENGTH));
            }
            if (w.pieces(TANK_MINER) > 0) into.add(Stat.DEFENSE, (ArmorDials.TANK_MINER_MULT.get() - 1) * w.sheetStat(TANK_MINER, Stat.DEFENSE));
        }
        if (w.full(HEAT)) into.add(Stat.HEAT_RESISTANCE, ArmorDials.HEAT_RESISTANCE.get());
        into.add(Stat.MINING_SPREAD, SetMath.perPiece(w.pieces(MINERAL), ArmorDials.MINERAL_SPREAD.get()));
        into.add(Stat.MINING_SPREAD, SetMath.perPiece(w.pieces(GLOSSY), ArmorDials.GLOSSY_SPREAD.get()));

        // ---- farming ladder (Fermenta/Helianthus also count as the lower tiers for rare crops, not for fortune)
        for (String s : new String[]{MELON, CROPIE, SQUASH, FERMENTO, HELIANTHUS}) {
            int n = w.pieces(s);
            if (n > 1) into.add(Stat.FARMING_FORTUNE, SetMath.tieredThirds(n, farmingFortuneFull(s)));
        }

        // ---- fishing
        int angler = w.pieces(ANGLER);
        if (angler > 0) into.add(Stat.HEALTH, angler * ArmorDials.ANGLER_HP_PER_LEVEL.get() * skill(p, SkillType.FISHING));
        // Festival Fisher (Shark Scale, per piece): more bites while the fishing festival runs, i.e. more sharks.
        if (w.pieces(SHARK) > 0 && ArmorUtil.eventActive(ArmorDials.FISHING_FESTIVAL_EVENT.get()))
            into.add(Stat.SEA_CREATURE_CHANCE, w.pieces(SHARK) * ArmorDials.SHARK_FESTIVAL_SCC.get());
        if (p.isInWater() && (w.full(SPONGE) || w.full(SHARK))) into.multiply(Stat.DEFENSE, ArmorDials.WATER_DEF_MULT.get());
        if (p.isUnderWater()) {
            into.add(Stat.TREASURE_CHANCE, SetMath.perPiece(w.pieces(DIVER), ArmorDials.DIVER_TREASURE.get()));
            into.add(Stat.TREASURE_CHANCE, SetMath.perPiece(w.pieces(ABYSSAL), ArmorDials.ABYSSAL_TREASURE.get()));
        }
        if ("jerrys_workshop".equals(island)) {
            addSheets(w, NUTCRACKER, ArmorDials.ENDER_MULT.get() - 1, into);
            addSheets(w, SNOW_SUIT, ArmorDials.ENDER_MULT.get() - 1, into);
        }
        // Lord's Blessing: 5 pieces with the Magma Lord Necklace (wave 5). Its +20 MF vs magmatic is per target (magicFindVs).
        if (w.lordsBlessing()) into.add(Stat.SEA_CREATURE_CHANCE, 1);
        hunter(w, BRONZE, 0.2, 0.5, into);
        hunter(w, SILVER, 0.4, 1.0, into);
        hunter(w, GOLD_HUNTER, 0.7, 1.5, into);
        hunter(w, DIAMOND_HUNTER, 1.0, 2.0, into);

        auraOnly(p, into);
    }

    /** Bonuses that come from OTHER players (Holy Blood, Battalion): apply even when you wear nothing. */
    private static void auraOnly(Player p, StatMap into) {
        double holyR = ArmorDials.HOLY_RANGE.get(), nutR = ArmorDials.NUTCRACKER_RANGE.get();
        double maxR = Math.max(holyR, nutR);
        boolean holy = false;
        int nut = 0;
        for (Player o : p.getWorld().getPlayers()) {
            double d2 = o.getLocation().distanceSquared(p.getLocation());
            if (d2 > maxR * maxR) continue;
            Worn ow = Worn.of(o);
            if (!holy && d2 <= holyR * holyR && ow.full(HOLY)) holy = true;
            if (d2 <= nutR * nutR && ow.full(NUTCRACKER)) nut++;
        }
        if (holy) into.add(Stat.HEALTH_REGEN, ArmorDials.HOLY_REGEN.get());
        int stacks = SetMath.stacks(nut, ArmorDials.NUTCRACKER_STACKS.get());
        if (stacks > 0) into.add(Stat.MAGIC_FIND, stacks * ArmorDials.NUTCRACKER_MF.get()).add(Stat.DEFENSE, stacks * ArmorDials.NUTCRACKER_DEF.get());
    }

    /** Runs after every module's provider: reads the accumulated totals. */
    static void contributeFinal(Player p, StatMap into) {
        if (!ArmorDials.ENABLED.get()) return;
        Worn w = Worn.of(p);
        if (!w.any()) return;
        ArmorState.P st = ArmorState.of(p.getUniqueId());
        if (w.full(GOBLIN)) {
            double intel = into.total(Stat.INTELLIGENCE);
            st.goblinRemoved = Math.max(0, intel);
            if (intel > 0) {
                into.add(Stat.INTELLIGENCE, -into.flat(Stat.INTELLIGENCE));
                into.add(Stat.MINING_SPEED, SetMath.goblinMiningSpeed(intel));
            }
        } else st.goblinRemoved = 0;
        if (w.full(HEAVY) || w.full(SUPER_HEAVY)) into.add(Stat.SPEED, SetMath.vindicateSpeed(into.total(Stat.DEFENSE)));
        if (w.full(BERSERKER)) {
            into.multiply(Stat.HEALTH, SetMath.berserkFactor(4));
            into.multiply(Stat.DEFENSE, SetMath.berserkFactor(4));
        }
        double cap = w.full(TUX_ELEGANT) ? ArmorDials.TUX_ELEGANT_HP.get()
                : w.full(TUX_FANCY) ? ArmorDials.TUX_FANCY_HP.get()
                : w.full(TUX_CHEAP) ? ArmorDials.TUX_CHEAP_HP.get() : -1;
        if (cap > 0) {
            double f = SetMath.tuxedoHealthFactor(into.total(Stat.HEALTH), cap);
            if (f < 1) into.multiply(Stat.HEALTH, f);
        }
        // Peace Treaty (Hunter sets, 2+ pieces): you can no longer fish up sea creatures.
        if (w.peaceTreaty()) into.set(Stat.SEA_CREATURE_CHANCE, 0);
        int boots = w.slotOf(RANCHERS_BOOTS);
        if (boots >= 0) {
            double limit = BonusListener.ranchersCap(w.stacks[boots]);
            double speed = into.total(Stat.SPEED);
            if (limit > 0 && speed > limit) into.multiply(Stat.SPEED, limit / speed);
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Add {@code factor} x every stat on this set's worn pieces (x2 on an island = factor 1). */
    static void addSheets(Worn w, String setId, double factor, StatMap into) {
        if (factor == 0) return;
        for (int i = 0; i < 4; i++) {
            if (!w.usable[i] || w.defs[i] == null || !setId.equals(w.defs[i].setId)) continue;
            ItemStatSheet sh = w.sheet(i);
            for (Map.Entry<Stat, ?> e : sh.view().entrySet()) into.add(e.getKey(), factor * sh.total(e.getKey()));
        }
    }

    /** Old Blood: a share more of the stats Growth / Protection / True Protection / Sugar Rush give the set. */
    private static void oldBlood(Worn w, StatMap into) {
        double boost = ArmorDials.OLD_BOOST.get();
        for (int i = 0; i < 4; i++) {
            if (!w.usable[i] || w.defs[i] == null || !OLD.equals(w.defs[i].setId)) continue;
            ItemData d = Sky.items().data(w.stacks[i]);
            into.add(Stat.HEALTH, boost * enchantValue("growth", d.enchant("growth")));
            into.add(Stat.DEFENSE, boost * enchantValue("protection", d.enchant("protection")));
            into.add(Stat.TRUE_DEFENSE, boost * enchantValue("true_protection", d.enchant("true_protection")));
            into.add(Stat.SPEED, boost * enchantValue("sugar_rush", d.enchant("sugar_rush")));
        }
    }

    private static double enchantValue(String id, int level) {
        if (level <= 0) return 0;
        EnchantDef e = Sky.items().enchant(id);
        return e == null ? 0 : e.value(level);
    }

    private static void hunter(Worn w, String setId, double fishSpeedPerStar, double trophyPerPiece, StatMap into) {
        int n = w.pieces(setId);
        if (n == 0) return;
        into.add(Stat.TROPHY_FISH_CHANCE, n * trophyPerPiece); // TUNE: reference shows the value only in-game
        if (!w.full(setId)) return;
        int stars = 0;
        for (int i = 0; i < 4; i++) if (w.usable[i] && w.defs[i] != null && setId.equals(w.defs[i].setId)) stars += Sky.items().data(w.stacks[i]).stars;
        into.add(Stat.FISHING_SPEED, stars * fishSpeedPerStar);
    }

    static long counter(Worn w, String pieceId, String key) {
        int slot = w.slotOf(pieceId);
        if (slot < 0) return 0;
        JsonObject x = Sky.items().data(w.stacks[slot]).extra;
        return ArmorUtil.longExtra(x, key);
    }

    static int skill(Player p, SkillType t) {
        try { return Sky.service(SkillService.class).level(p, t); } catch (RuntimeException e) { return 0; }
    }

    static long collection(Player p, String id) {
        try { return Sky.service(Features.Collections.class).count(p, id); } catch (RuntimeException e) { return 0; }
    }

    /**
     * Fairy Souls found. No feature interface exposes this yet (request in the hand-off), so it reads a
     * requirement key the progression module may register ("fairysouls:N") by bisection; 0 otherwise.
     */
    static int fairySouls(Player p) {
        try {
            if (!Sky.requirements().check(p, "fairysouls:1")) return 0;
            int lo = 1, hi = 512;
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (Sky.requirements().check(p, "fairysouls:" + mid)) lo = mid; else hi = mid - 1;
            }
            return lo;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
