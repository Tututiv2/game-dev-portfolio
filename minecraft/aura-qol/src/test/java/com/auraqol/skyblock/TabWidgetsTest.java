package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class TabWidgetsTest {

    /**
     * A Dwarven Mines tab list the way the client hands it over: 80 entries in
     * column order, colour codes, the column titles, widgets separated by
     * blank entries, and the Powders widget straight after Commissions.
     */
    static List<String> dwarvenTab() {
        List<String> t = new ArrayList<>();
        t.add("§3§lPlayers §r§f(9)");
        t.add("§8[§r§b312§r§8] §r§bTutut §r§6⚒");
        t.add("§8[§r§9201§r§8] §r§aSomeone");
        while (t.size() < 20) {
            t.add("");
        }
        t.add("§e§lInfo");
        t.add("§b§lArea: §r§7Dwarven Mines");
        t.add(" Server: §r§8mini123AB");
        t.add(" Gems: §r§a1,234");
        t.add("");
        t.add("§9§lCommissions:");
        t.add(" §r§fMithril Miner: §r§c45%");
        t.add(" §r§fGoblin Slayer: §r§aDONE");
        t.add(" §r§fRoyal Mines Titanium: §r§e12.5%");
        t.add(" §r§f2x Mithril Powder Collector: §r§c0%");
        t.add("§9§lPowders:");
        t.add(" Mithril: §r§21,234,567");
        t.add(" Gemstone: §r§d2,345,678");
        t.add("");
        t.add("§3§lPickaxe Ability:");
        t.add(" Mining Speed Boost: §r§aAvailable");
        while (t.size() < 80) {
            t.add("");
        }
        return t;
    }

    @Test
    void commissionsFromARealLookingDwarvenTab() {
        List<TabWidgets.Commission> c = TabWidgets.commissions(dwarvenTab());
        assertEquals(4, c.size(), c.toString());
        assertEquals("Mithril Miner", c.get(0).name());
        assertEquals(45.0, c.get(0).percent(), 1e-9);
        assertEquals("45%", c.get(0).progress());
        assertFalse(c.get(0).done());
        assertEquals("Goblin Slayer", c.get(1).name());
        assertTrue(c.get(1).done());
        assertEquals("DONE", c.get(1).progress());
        assertEquals(100.0, c.get(1).percent(), 1e-9);
        assertEquals("Royal Mines Titanium", c.get(2).name());
        assertEquals("12.5%", c.get(2).progress());
        assertEquals("2x Mithril Powder Collector", c.get(3).name());
        assertEquals(0.0, c.get(3).percent(), 1e-9);
    }

    @Test
    void stopsAtBlankLineAndAtTheNextWidget() {
        List<String> t = List.of("Commissions:", " Mithril Miner: 10%", "", " Glacite Collector: 50%");
        assertEquals(1, TabWidgets.commissions(t).size());
        // No blank: the Powders header and its " Mithril: 1,234" entries must not leak in.
        List<String> u = List.of("Commissions:", " Mithril Miner: 10%", "Powders:", " Mithril: 1,234");
        assertEquals(1, TabWidgets.commissions(u).size());
        // A column title straight after.
        List<String> v = List.of("Commissions:", " Tungsten Collector: 99%", "§e§lAccount Info", " Bank: 1");
        assertEquals(1, TabWidgets.commissions(v).size());
    }

    @Test
    void headerVariantsAndProgressFormats() {
        List<String> t = Arrays.asList(
                "§lCommissions (2/4):",
                " Chest Looter: 3/5",
                " Corpse Looter: 5/5",
                " Lucky Raffle: Completed",
                " Glacite Walker Slayer: 66.7 %",
                " Excavate a Fossil: 100%");
        List<TabWidgets.Commission> c = TabWidgets.commissions(t);
        assertEquals(5, c.size());
        assertEquals("3/5", c.get(0).progress());
        assertEquals(60.0, c.get(0).percent(), 1e-9);
        assertTrue(c.get(1).done());
        assertTrue(c.get(2).done());
        assertEquals(66.7, c.get(3).percent(), 1e-9);
        assertTrue(c.get(4).done());
        assertEquals("DONE", c.get(4).progress());
    }

    @Test
    void noCommissionsWidgetMeansEmpty() {
        assertTrue(TabWidgets.commissions(List.of("Area: Hub", "Server: m1", "Commission Milestones: 5")).isEmpty());
        assertTrue(TabWidgets.commissions(List.of("Commissions:", "")).isEmpty());
        assertTrue(TabWidgets.commissions(List.of()).isEmpty());
        assertTrue(TabWidgets.commissions(null).isEmpty());
        // A milestone header is not the widget.
        assertTrue(TabWidgets.commissions(List.of("Commission Milestones:", " Mithril Miner: 5%")).isEmpty());
    }

    @Test
    void areaAndMiningIslands() {
        assertEquals("Dwarven Mines", TabWidgets.area(dwarvenTab()));
        assertTrue(TabWidgets.isMiningArea("Dwarven Mines"));
        assertTrue(TabWidgets.isMiningArea("Crystal Hollows"));
        assertTrue(TabWidgets.isMiningArea("Mineshaft"));
        assertFalse(TabWidgets.isMiningArea("Hub"));
        assertFalse(TabWidgets.isMiningArea(null));
        assertEquals("Catacombs (F7)", TabWidgets.area(List.of("Dungeon: Catacombs (F7)")));
        assertNull(TabWidgets.area(List.of("Server: m1")));
    }

    @Test
    void pickaxeAbilityWidget() {
        TabWidgets.AbilityStatus s = TabWidgets.pickaxeAbility(dwarvenTab());
        assertEquals("Mining Speed Boost", s.name());
        assertTrue(s.available());

        s = TabWidgets.pickaxeAbility(List.of("Pickaxe Ability:", " Pickobulus: §r§c45s"));
        assertEquals("Pickobulus", s.name());
        assertFalse(s.available());
        assertEquals(45, s.secondsLeft());

        s = TabWidgets.pickaxeAbility(List.of("Pickaxe Ability: Mining Speed Boost: Available"));
        assertEquals("Mining Speed Boost", s.name());
        assertTrue(s.available());

        s = TabWidgets.pickaxeAbility(List.of("Pickaxe Ability: Maniac Miner: 1m 5s"));
        assertEquals(65, s.secondsLeft());

        assertNull(TabWidgets.pickaxeAbility(List.of("Area: Hub")));
        assertNull(TabWidgets.pickaxeAbility(List.of("Pickaxe Ability:", " Nothing selected")));
    }

    @Test
    void secondsAndClean() {
        assertEquals(45, TabWidgets.seconds("45s"));
        assertEquals(125, TabWidgets.seconds("2m 5s"));
        assertEquals(125, TabWidgets.seconds("2m05s"));
        assertEquals(3600, TabWidgets.seconds("1h"));
        assertEquals(-1, TabWidgets.seconds("Available"));
        assertEquals(-1, TabWidgets.seconds(""));
        // emoji fillers, nbsp, double spaces, colour codes
        assertEquals("Purse: 1,234", TabWidgets.clean("§6Purse:  🎂§r1,234 "));
        assertEquals("", TabWidgets.clean(null));
    }
}
