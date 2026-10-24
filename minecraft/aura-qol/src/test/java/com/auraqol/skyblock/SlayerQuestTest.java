package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SlayerQuestTest {

    /** A Hub sidebar mid-quest, as PlayerTeam.formatNameForTeam returns it (top line first). */
    static List<String> revenantSidebar(String status) {
        return List.of(
                "§707/10/26 §8m42AB",
                " ",
                "§7 Late Summer 12th",
                "§7 ⏣ §cGraveyard",
                "§r ",
                "Purse: §652,763,737",
                "Bits: §b1,234",
                "§r  ",
                "§cSlayer Quest",
                "§4Revenant Horror §cIV",
                status,
                "§r   ",
                "§ewww.hypixel.net");
    }

    @Test
    void progressWithParentheses() {
        SlayerQuest.Quest q = SlayerQuest.parse(revenantSidebar("§7(§e1,234§7/§c1,500§7) Combat XP"));
        assertEquals("Revenant Horror", q.boss());
        assertEquals(4, q.tier());
        assertEquals("Revenant Horror IV", q.title());
        assertEquals(SlayerQuest.State.PROGRESS, q.state());
        assertEquals(1234, q.current(), 1e-9);
        assertEquals(1500, q.target(), 1e-9);
        assertEquals("Combat XP", q.unit());
        assertEquals(266, q.remaining(), 1e-9);
        assertEquals(1234.0 / 1500, q.fraction(), 1e-9);
    }

    @Test
    void progressWithoutParenthesesAndAbbreviated() {
        SlayerQuest.Quest q = SlayerQuest.parse(revenantSidebar(" §e1.2k§7/§c1.5k Combat XP"));
        assertEquals(1200, q.current(), 1e-9);
        assertEquals(1500, q.target(), 1e-9);
    }

    @Test
    void spawnedAndSlain() {
        SlayerQuest.Quest q = SlayerQuest.parse(revenantSidebar("§eSlay the Boss!"));
        assertEquals(SlayerQuest.State.SPAWNED, q.state());
        assertEquals(1.0, q.fraction(), 1e-9);
        assertFalse(q.hasProgress());
        q = SlayerQuest.parse(revenantSidebar("§aBoss slain!"));
        assertEquals(SlayerQuest.State.SLAIN, q.state());
    }

    @Test
    void riftKillsSkullPrefixAndDigitTier() {
        SlayerQuest.Quest q = SlayerQuest.parse(List.of(
                "Slayer Quest",
                "☠ Riftstalker Bloodfiend III",
                "(15/40) Kills"));
        assertEquals("Riftstalker Bloodfiend", q.boss());
        assertEquals(3, q.tier());
        assertEquals("Kills", q.unit());
        assertEquals(15, q.current(), 1e-9);

        q = SlayerQuest.parse(List.of("Slayer Quest", "Voidgloom Seraph 2", "", "(0/7,500) Combat XP"));
        assertEquals("Voidgloom Seraph", q.boss());
        assertEquals(2, q.tier());
        assertEquals(7500, q.target(), 1e-9);
    }

    @Test
    void emojiFillerInTheMiddleOfTheLine() {
        // Hypixel puts the owner name (an emoji) between team prefix and suffix.
        SlayerQuest.Quest q = SlayerQuest.parse(List.of(
                "§cSlayer Quest👹",
                "§4Tarantula Brood🎂§4father §cV",
                "§7(§e600§7/§c2,000§7) 🍬Combat XP"));
        assertEquals("Tarantula Broodfather", q.boss());
        assertEquals(5, q.tier());
        assertEquals(600, q.current(), 1e-9);
    }

    @Test
    void noQuest() {
        assertNull(SlayerQuest.parse(List.of("Purse: 5", "Bits: 3", "www.hypixel.net")));
        assertNull(SlayerQuest.parse(List.of()));
        assertNull(SlayerQuest.parse(null));
        assertNull(SlayerQuest.parse(List.of("Slayer Quest")));
    }

    @Test
    void unknownStatusStillShowsTheBoss() {
        SlayerQuest.Quest q = SlayerQuest.parse(List.of("Slayer Quest", "Sven Packmaster IV", "something new"));
        assertEquals("Sven Packmaster", q.boss());
        assertEquals(SlayerQuest.State.UNKNOWN, q.state());
        assertFalse(q.hasProgress());
    }

    @Test
    void numbers() {
        assertEquals(1_500_000, SlayerQuest.number("1.5M"), 1e-6);
        assertEquals(2400, SlayerQuest.number("2,400"), 1e-9);
        assertTrue(Double.isNaN(SlayerQuest.number("x")));
    }

    @Test
    void etaFromRecentRate() {
        ProgressRate r = new ProgressRate(120_000, 10_000);
        r.sample(0, "rev4", 0, 1500);
        assertTrue(Double.isNaN(r.perMinute(5_000)));
        r.sample(10_000, "rev4", 100, 1500);
        r.sample(20_000, "rev4", 200, 1500);
        // 200 XP in 20 s = 600/min; 1300 left -> 130 s
        assertEquals(600, r.perMinute(20_000), 1e-6);
        assertEquals(130_000, r.etaMs(20_000));
        // standing still decays the rate instead of freezing it
        assertTrue(r.perMinute(40_000) < 600);
        // new quest starts over
        r.sample(50_000, "sven3", 0, 750);
        assertTrue(Double.isNaN(r.perMinute(50_000)));
        assertEquals(-1, r.etaMs(50_000));
        // value going down (claimed, new quest of the same kind) starts over too
        r.sample(60_000, "sven3", 300, 750);
        r.sample(61_000, "sven3", 10, 750);
        assertTrue(Double.isNaN(r.perMinute(80_000)));
    }

    @Test
    void rateWindowDropsOldHistory() {
        ProgressRate r = new ProgressRate(60_000, 10_000);
        // fast early, slow late: the old fast part must age out
        r.sample(0, "k", 0, 10_000);
        r.sample(10_000, "k", 1000, 10_000);
        for (int i = 1; i <= 20; i++) {
            r.sample(10_000 + i * 10_000, "k", 1000 + i * 10, 10_000);
        }
        double rate = r.perMinute(210_000);
        // late pace is 10 per 10 s = 60/min; a rate polluted by the start would be ~340
        assertTrue(rate < 100, "rate " + rate);
    }

    @Test
    void oneLineHeaderForm() {
        SlayerQuest.Quest q = SlayerQuest.parse(List.of(
                "Slayer Quest: Revenant Horror IV", "(1,234/4,800) Combat XP", "Time left: 2:41"));
        assertEquals("Revenant Horror", q.boss());
        assertEquals(4, q.tier());
        assertEquals(4800, q.target(), 1e-9);
        // lower-case spawned wording with a timer after it
        q = SlayerQuest.parse(List.of("Slayer Quest", " Sven Packmaster III", " Slay the boss! 2:41"));
        assertEquals(SlayerQuest.State.SPAWNED, q.state());
    }
}
