package com.skyclone.core.combat;

import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.DropDef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatFormatTest {

    @Test
    void critIndicatorCyclesColours() {
        // research 01 §11.3: first ✧ white, then every char (commas included) cycles f, e, 6, c.
        assertEquals("&f✧&f1&e2&63&c✧", CombatFormat.indicator(123, true, DamageContext.Kind.MELEE));
        assertEquals("&f✧&f1&e,&62&c3&f4&e✧", CombatFormat.indicator(1234, true, DamageContext.Kind.MELEE));
        assertEquals("&f✧&f0&e✧", CombatFormat.indicator(0, true, DamageContext.Kind.MELEE));
    }

    @Test
    void normalIndicatorIsGreyWithCommas() {
        assertEquals("&71,234", CombatFormat.indicator(1234, false, DamageContext.Kind.MELEE));
        assertEquals("&70", CombatFormat.indicator(-5, false, DamageContext.Kind.MELEE));
        assertEquals("&f99", CombatFormat.indicator(99, false, DamageContext.Kind.TRUE));
    }

    @Test
    void nameplate() {
        assertEquals("&8[&7Lv150&8] &cZealot &a12,000&f/&a13,000&c❤",
                CombatFormat.nameplate("Zealot", 150, 12000, 13000, false, 100000, null));
        assertEquals("&8[&7Lv1&8] &cZombie &e40&f/&a100&c❤",
                CombatFormat.nameplate("Zombie", 1, 40, 100, false, 100000, null));
        assertEquals("&8[&7Lv1&8] &cZombie &c10&f/&a100&c❤",
                CombatFormat.nameplate("Zombie", 1, 10, 100, false, 100000, null));
        assertEquals("&c☠ &4Seraph &a50M&c❤",
                CombatFormat.nameplate("Seraph", 500, 50_000_000, 50_000_000, true, 100000, null));
        assertEquals("Ghoul 1.5M/2M &a",
                CombatFormat.nameplate("Ghoul", 3, 1_500_000, 2_000_000, false, 100000, "{name} {hp}/{max} {hpc}"));
    }

    @Test
    void healthRoundsUpSoALivingMobNeverShowsZero() {
        assertEquals("1", CombatFormat.hpNum(0.2, 100000));
    }

    @Test
    void lootMath() {
        DropDef rare = new DropDef("X", 1, 1, 0.01);
        assertEquals(0.02, LootMath.chance(rare, 100, 0.05, 900), 1e-12);
        assertEquals(0.10, LootMath.chance(rare, 5000, 0.05, 900), 1e-12);
        assertTrue(LootMath.mfApplies(rare, 100, 0.05));
        DropDef common = new DropDef("X", 1, 1, 0.5);
        assertEquals(0.5, LootMath.chance(common, 100, 0.05, 900), 1e-12);
        assertFalse(LootMath.mfApplies(common, 100, 0.05));
        DropDef noMf = new DropDef("X", 1, 1, 0.01);
        noMf.magicFind = false;
        assertEquals(0.01, LootMath.chance(noMf, 100, 0.05, 900), 1e-12);
        DropDef range = new DropDef("X", 2, 4, 1);
        assertEquals(2, LootMath.amount(range, 0));
        assertEquals(3, LootMath.amount(range, 0.5));
        assertEquals(4, LootMath.amount(range, 0.9999));
        DropDef swapped = new DropDef("X", 5, 3, 1);
        int a = LootMath.amount(swapped, 0.99);
        assertTrue(a >= 3 && a <= 5);
    }
}
