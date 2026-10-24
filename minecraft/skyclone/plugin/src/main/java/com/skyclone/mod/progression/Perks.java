package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * The SkyBlock Level rewards that are more than a gate (research 02 6.2):
 * <ul>
 *   <li>Level 25: the <b>Tome of Progress</b> accessory ({@code BOOK_OF_PROGRESSION}, registered by accessories) is
 *       handed over once per member, plus <b>Stacked Healing</b> (Vitality, TUNE);</li>
 *   <li>Level 50: <b>Defensive Strongarm</b> (Defense, TUNE);</li>
 *   <li>Level 75: <b>Extra Infliction</b> (% more damage to mobs, a final multiplier: research 01 says it applies
 *       after the damage cap; TUNE).</li>
 * </ul>
 * The tome's rarity upgrades at 75/150/225/300/400 need a per-instance rarity that core does not have (Requests).
 */
final class Perks {

    static final String TOME_ID = "BOOK_OF_PROGRESSION";
    static final int HEALING_LEVEL = 25, STRONGARM_LEVEL = 50, INFLICTION_LEVEL = 75;

    /** Give the tome to a member who has reached Level 25 and never got it (level-up and login). */
    void checkTome(Player p) {
        if (!Prog.ready(p) || !Prog.perkTome.get()) return;
        if (Prog.levels.level(p) < PMath.TOME_LEVELS[0]) return;
        PData.Perks d = Prog.perks(p);
        if (d.claimed.contains("tome")) return;
        if (!Sky.items().exists(TOME_ID)) return;            // accessories absent: try again next login
        d.claimed.add("tome");
        try {
            Sky.loot().give(p, TOME_ID, 1, LootSource.EVENT);
            Msg.send(p, "&a&lSKYBLOCK LEVEL REWARD &7You received the " + tomeName() + "&7!");
        } catch (Throwable t) {
            d.claimed.remove("tome");
            Sky.log().warning("[progression] could not give the Tome of Progress to " + p.getName() + ": " + t);
            return;
        }
        Sky.profiles().saveNow(p);
    }

    static String tomeName() {
        try {
            var def = Sky.items().def(TOME_ID);
            if (def != null && def.name != null) return (def.rarity != null ? def.rarity.code() : "&f") + def.name;
        } catch (Throwable ignored) { }
        return "&fTome of Progress";
    }

    void contribute(Player p, StatMap into) {
        if (!Prog.ready(p)) return;
        int lvl = Prog.levels.level(p);
        if (lvl >= HEALING_LEVEL) into.add(Stat.VITALITY, Math.max(0, Prog.perkHealingVitality.get()));
        if (lvl >= STRONGARM_LEVEL) into.add(Stat.DEFENSE, Math.max(0, Prog.perkStrongarmDefense.get()));
    }

    /** Extra Infliction: a final multiplier on a player's hits on mobs (priority 900 = after every other source). */
    void modify(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null || !(ctx.victim instanceof LivingEntity) || ctx.victim instanceof Player || !Prog.ready(p)) return;
        if (Prog.levels.level(p) < INFLICTION_LEVEL) return;
        double pct = Math.max(0, Prog.perkInflictionPct.get());
        if (pct > 0) ctx.multiplicative *= 1 + pct / 100.0;
    }
}
