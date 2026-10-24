package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SkillActivityTest {

    @Test
    void readsTheSkillFromARealActionBar() {
        assertEquals("Farming", SkillActivity.parse("2,345/2,345❤     +23.4 Farming (1,234,567/2,000,000)     1,000/1,000✎ Mana"));
        assertEquals("Combat", SkillActivity.parse("+150 Combat (45.3%)"));
        assertEquals("Fishing", SkillActivity.parse("+1,200.5 Fishing (12/50,000)"));
        assertEquals("Foraging", SkillActivity.parse("§3+6 Foraging (2,000/3,000)"));
        assertEquals("Mining", SkillActivity.parse("+3.5 Mining (100/200)"));
    }

    @Test
    void ignoresLinesWithoutSkillXp() {
        assertNull(SkillActivity.parse("2,345/2,345❤     1,000/1,000✎ Mana"));
        assertNull(SkillActivity.parse("+5 Defense"));
        assertNull(SkillActivity.parse(null));
    }

    @Test
    void switchesToTheLatestSkillAndFadesAfterAMinute() {
        SkillActivity a = new SkillActivity();
        a.observe("+3 Mining (1/2)", 0);
        assertEquals("Mining", a.current(1000));
        a.observe("+150 Combat (45.3%)", 5000);
        assertEquals("Combat", a.current(6000));
        a.observe("2,345/2,345❤", 7000);                  // no XP: stays Combat
        assertEquals("Combat", a.current(8000));
        assertNull(a.current(5000 + SkillActivity.HOLD_MS + 1));
    }
}
