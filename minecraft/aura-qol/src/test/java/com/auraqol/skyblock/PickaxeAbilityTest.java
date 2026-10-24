package com.auraqol.skyblock;

import static com.auraqol.skyblock.PickaxeAbility.Phase.ACTIVE;
import static com.auraqol.skyblock.PickaxeAbility.Phase.COOLDOWN;
import static com.auraqol.skyblock.PickaxeAbility.Phase.NONE;
import static com.auraqol.skyblock.PickaxeAbility.Phase.READY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PickaxeAbilityTest {

    /** A drill's lore, the parts that matter. */
    static final List<String> DRILL_LORE = List.of(
            "§7Breaking Power 9",
            "§7Mining Speed: §a+1,850",
            "",
            "§6Ability: Mining Speed Boost  §e§lRIGHT CLICK",
            "§7Grants §a+300% §6⸕ Mining Speed §7for §a20s§7.",
            "§8Cooldown: §a120s",
            "",
            "§5§lEPIC DRILL");

    @Test
    void loreParse() {
        PickaxeAbility.Lore l = PickaxeAbility.parseLore(DRILL_LORE);
        assertEquals("Mining Speed Boost", l.name());
        assertEquals(120, l.cooldownSec());
        assertEquals(20, l.durationSec());
        assertNull(PickaxeAbility.parseLore(List.of("§7Damage: +100", "§9Sharpness V")));
        assertNull(PickaxeAbility.parseLore(null));
    }

    @Test
    void fullCycleFromChatWithLoreTiming() {
        PickaxeAbility a = new PickaxeAbility();
        assertEquals(NONE, a.view(0).phase());
        a.lore(PickaxeAbility.parseLore(DRILL_LORE));
        assertEquals(READY, a.view(0).phase());
        assertEquals("Mining Speed Boost", a.view(0).name());

        assertTrue(a.chat(1_000, "§aYou used your §6Mining Speed Boost §aPickaxe Ability!", true));
        PickaxeAbility.View v = a.view(6_000);
        assertEquals(ACTIVE, v.phase());
        assertEquals(15_000, v.msLeft());
        assertEquals(5_000, v.activeMs());

        assertTrue(a.chat(21_000, "§cYour §6Mining Speed Boost §chas expired!", true));
        v = a.view(21_000);
        assertEquals(COOLDOWN, v.phase());
        assertEquals(100_000, v.msLeft());

        assertTrue(a.chat(121_500, "§6Mining Speed Boost §ais now available!", true));
        assertEquals(READY, a.view(121_500).phase());
    }

    @Test
    void serverMessagesBeatComputedTiming() {
        PickaxeAbility a = new PickaxeAbility();
        a.lore(PickaxeAbility.parseLore(DRILL_LORE)); // says 120 s
        a.chat(0, "You used your Mining Speed Boost Pickaxe Ability!", true);
        a.chat(20_000, "Your Mining Speed Boost has expired!", true);
        // Core of the Mountain / Sky Mall made it shorter: the server says ready at 90 s
        a.chat(90_000, "Mining Speed Boost is now available!", true);
        assertEquals(READY, a.view(90_000).phase());
        // pressing it again early: the server states the real wait
        a.chat(95_000, "You used your Mining Speed Boost Pickaxe Ability!", true);
        a.chat(115_000, "Your Mining Speed Boost has expired!", true);
        a.chat(120_000, "§cThis ability is on cooldown for 62s.", true);
        assertEquals(62_000, a.view(120_000).msLeft());
    }

    @Test
    void cooldownMessageIgnoredWhenNotHoldingAMiningTool() {
        PickaxeAbility a = new PickaxeAbility();
        a.chat(0, "You used your Pickobulus Pickaxe Ability!", true);
        assertFalse(a.chat(1_000, "This ability is on cooldown for 3s.", false));
        assertEquals(COOLDOWN, a.view(1_000).phase());
        assertEquals(-1, a.view(1_000).msLeft()); // still unknown, not 3 s
    }

    @Test
    void pickobulusIsInstantNoActivePhase() {
        PickaxeAbility a = new PickaxeAbility();
        a.chat(0, "You used your Pickobulus Pickaxe Ability!", true);
        assertEquals(COOLDOWN, a.view(10).phase());
        assertTrue(a.busy(10));
    }

    @Test
    void unknownDurationActiveCountsUpAndTimesOut() {
        PickaxeAbility a = new PickaxeAbility();
        a.chat(0, "You used your Maniac Miner Pickaxe Ability!", true);
        PickaxeAbility.View v = a.view(7_000);
        assertEquals(ACTIVE, v.phase());
        assertEquals(-1, v.msLeft());
        assertEquals(7_000, v.activeMs());
        // no "expired" line ever came
        assertEquals(COOLDOWN, a.view(61_000).phase());
    }

    @Test
    void tabWidgetSyncsTheCountdown() {
        PickaxeAbility a = new PickaxeAbility();
        a.tab(0, new TabWidgets.AbilityStatus("Mining Speed Boost", true, 0));
        assertEquals(READY, a.view(0).phase());
        assertFalse(a.busy(0));
        a.chat(1_000, "You used your Mining Speed Boost Pickaxe Ability!", true);
        a.chat(21_000, "Your Mining Speed Boost has expired!", true);
        a.tab(21_000, new TabWidgets.AbilityStatus("Mining Speed Boost", false, 80));
        assertEquals(80_000, a.view(21_000).msLeft());
        // a 1 s rounding difference does not make the countdown jump
        a.tab(22_000, new TabWidgets.AbilityStatus("Mining Speed Boost", false, 80));
        assertEquals(79_000, a.view(22_000).msLeft());
        a.tab(101_000, new TabWidgets.AbilityStatus("Mining Speed Boost", true, 0));
        assertEquals(READY, a.view(101_000).phase());
    }

    @Test
    void tabAvailableDoesNotCancelARunningAbility() {
        PickaxeAbility a = new PickaxeAbility();
        a.chat(0, "You used your Mining Speed Boost Pickaxe Ability!", true);
        a.tab(500, new TabWidgets.AbilityStatus("Mining Speed Boost", true, 0));
        assertEquals(ACTIVE, a.view(1_000).phase());
    }

    @Test
    void unrelatedChatIsIgnored() {
        PickaxeAbility a = new PickaxeAbility();
        assertFalse(a.chat(0, "Your Pet is now available!", true));
        assertFalse(a.chat(0, "[NPC] Emissary Ceanna: Mining Speed Boost is great", true));
        assertFalse(a.chat(0, "Your Kill Combo has expired! You reached a 15 Kill Combo!", true));
        assertEquals(NONE, a.view(0).phase());
    }

    @Test
    void cooldownLineVariants() {
        PickaxeAbility a = new PickaxeAbility();
        // the pickaxe wording counts without checking the held item
        assertTrue(a.chat(0, "§cYour pickaxe ability is on cooldown for 30s.", false));
        assertEquals(30_000, a.view(0).msLeft());
        // a named ability, likewise, and it names the ability
        PickaxeAbility b = new PickaxeAbility();
        assertTrue(b.chat(0, "Your Pickobulus is on cooldown for 12s.", false));
        assertEquals("Pickobulus", b.view(0).name());
        assertEquals(12_000, b.view(0).msLeft());
        // another item's named ability is not ours
        PickaxeAbility c = new PickaxeAbility();
        assertFalse(c.chat(0, "Your Pocket Bank is on cooldown for 5s.", true));
        assertFalse(c.chat(0, "Time Warp is on cooldown for 1h 2m.", true));
        assertEquals(PickaxeAbility.Phase.NONE, c.view(0).phase());
    }
}
