package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class SkyblockTextTest {

    @Test
    void purseFromARealLookingSidebar() {
        // What PlayerTeam.formatNameForTeam gives for Hypixel's lines: colour
        // codes, an emoji spacer, and the team-name filler in the middle.
        List<String> sidebar = List.of(
                "§07/10/26 §8m42AB",
                " ",
                "§7 ⏲ Late Summer 12th",
                " §7⏣ §bVillage",
                "§r §r",
                "Purse: §6§r§652,763,737 §r§e(+50)",
                "Bits: §b1,234",
                "§ewww.hypixel.net");
        assertEquals(52_763_737.0, SkyblockText.purse(sidebar), 1e-6);
    }

    @Test
    void piggyAndDecimalsAndAbsent() {
        assertEquals(1234.5, SkyblockText.purse(List.of("Piggy: 1,234.5")), 1e-9);
        assertEquals(0.0, SkyblockText.purse(List.of("Purse: 0")), 1e-9);
        assertNull(SkyblockText.purse(List.of("Motes: 5,000", "Bits: 10")));
        assertNull(SkyblockText.purse(List.of()));
        assertNull(SkyblockText.purse(null));
        assertNull(SkyblockText.purse(Arrays.asList("x", null)));
    }

    @Test
    void skyblockTitle() {
        assertTrue(SkyblockText.isSkyblockTitle("§e§lSKYBLOCK"));
        assertTrue(SkyblockText.isSkyblockTitle("SKYBLOCK CO-OP"));
        assertFalse(SkyblockText.isSkyblockTitle("BED WARS"));
        assertFalse(SkyblockText.isSkyblockTitle(null));
    }

    static final String ADDED_HOVER = "§aAdded items:\n"
            + "§a  +1,280 §fEnchanted Cobblestone §7(Mining Sack)\n"
            + "§a  +64 §fCoal §7(Mining Sack, Large Mining Sack)\n"
            + "§a  +3 §fRough Ruby Gemstone §7(Gemstone Sack)\n"
            + "§7  and 3 other items.\n"
            + "\n"
            + "§eThis message can be disabled in the settings.";

    @Test
    void sackHoverLinesParseWithCommasAndMultipleSacks() {
        List<SkyblockText.SackChange> c = SkyblockText.sackChanges(ADDED_HOVER);
        assertEquals(List.of(
                new SkyblockText.SackChange("Enchanted Cobblestone", 1280),
                new SkyblockText.SackChange("Coal", 64),
                new SkyblockText.SackChange("Rough Ruby Gemstone", 3)), c);
    }

    @Test
    void sackHoverLinesWithModernIconGlyphs() {
        // Same shape as SkyHanni's SackApi sackChangeRegex ([+-][\d,]+) (.+) \((.+)\), with the
        // private-use icons the modern client gets around item and sack names.
        List<SkyblockText.SackChange> c = SkyblockText.sackChanges(
                "Added items:\n  §a+3 §f\uE010 Rough Ruby Gemstone §7(Gemstone Sack) \uE07F\n  §a+1,280 §fWheat §7(Agronomy Sack)");
        assertEquals(List.of(
                new SkyblockText.SackChange("Rough Ruby Gemstone", 3),
                new SkyblockText.SackChange("Wheat", 1280)), c);
    }

    @Test
    void removedLinesAreNegative() {
        List<SkyblockText.SackChange> c = SkyblockText.sackChanges("Removed items:\n  -256 Wheat (Agronomy Sack)");
        assertEquals(List.of(new SkyblockText.SackChange("Wheat", -256)), c);
        assertTrue(SkyblockText.sackChanges(null).isEmpty());
        assertTrue(SkyblockText.sackChanges("nothing here").isEmpty());
    }

    @Test
    void sackSummaryLine() {
        assertTrue(SkyblockText.isSackSummary("§6[Sacks] §r§a+1,280§r§e items§r§e. (Last 30s.)"));
        assertFalse(SkyblockText.isSackSummary("[NPC] Sacks guy: hi"));
    }

    @Test
    void tradeLinesAreRecognised() {
        assertTrue(SkyblockText.isTradeMessage("[Bazaar] Bought 64x Enchanted Diamond for 87,578 coins!"));
        assertTrue(SkyblockText.isTradeMessage("§6[Bazaar] §r§7Sold 640x Wheat for 3,840 coins!"));
        assertTrue(SkyblockText.isTradeMessage("You sold Rotten Flesh x64 for 128 Coins!"));
        assertTrue(SkyblockText.isTradeMessage("Deposited 1,000,000 coins! There's now 5,000,000 coins in the account!"));
        assertTrue(SkyblockText.isTradeMessage("Withdrew 2M coins! There's now 3M coins left in the account!"));
        assertTrue(SkyblockText.isTradeMessage("Trade completed with [MVP+] Someone!"));
        assertFalse(SkyblockText.isTradeMessage("RARE DROP! Summoning Eye"));
        assertFalse(SkyblockText.isTradeMessage("You died and lost 1,234 coins!"));
        assertFalse(SkyblockText.isTradeMessage(null));
    }

    @Test
    void bazaarDeliveriesAreParsed() {
        SkyblockText.Acquired a = SkyblockText.bazaarAcquired("[Bazaar] Bought 64x Enchanted Diamond for 87,578 coins!");
        assertEquals(new SkyblockText.Acquired("Enchanted Diamond", 64), a);
        SkyblockText.Acquired b = SkyblockText.bazaarAcquired(
                "§6[Bazaar] §7Claimed §a1,280x §fWheat §7worth §6768 coins §7bought for 0.6 each!");
        assertEquals(new SkyblockText.Acquired("Wheat", 1280), b);
        assertNull(SkyblockText.bazaarAcquired("[Bazaar] Sold 64x Wheat for 384 coins!"));
        assertNull(SkyblockText.bazaarAcquired(null));
    }

    @Test
    void sackMenus() {
        assertTrue(SkyblockText.isSackMenu("Mining Sack"));
        assertTrue(SkyblockText.isSackMenu("Sack of Sacks"));
        assertTrue(SkyblockText.isSackMenu("§8Large Agronomy Sack"));
        assertFalse(SkyblockText.isSackMenu("Bazaar"));
        assertFalse(SkyblockText.isSackMenu(null));
    }
}
