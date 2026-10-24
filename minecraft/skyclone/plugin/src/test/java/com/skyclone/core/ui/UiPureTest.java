package com.skyclone.core.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure logic of the UI core: number parsing, paging maths, sidebar assembly, level colours, sign text. */
class UiPureTest {

    @Test
    void parsesGenreNumbers() {
        assertEquals(64.0, NumberParse.parse("64"));
        assertEquals(1500.0, NumberParse.parse("1.5k"));
        assertEquals(2_000_000.0, NumberParse.parse("2m"));
        assertEquals(3_000_000_000.0, NumberParse.parse("3b"));
        assertEquals(2_500_000.0, NumberParse.parse(" 2.5M "));
        assertEquals(1234.0, NumberParse.parse("1,234"));
        assertEquals(0.0, NumberParse.parse("0"));
        assertEquals(0.5, NumberParse.parse(".5"));
    }

    @Test
    void rejectsGarbage() {
        for (String bad : new String[]{null, "", " ", "-1", "-1k", "nan", "NaN", "Infinity", "k", "1e5",
                "abc", "1.2.3", "0x10", "+5", ".", "1kk", "99999999999999999t"}) {
            assertNull(NumberParse.parse(bad), "should reject: " + bad);
        }
    }

    @Test
    void pagingMaths() {
        assertEquals(28, PagedLayout.PER_PAGE);
        assertEquals(1, PagedLayout.pages(0));
        assertEquals(1, PagedLayout.pages(28));
        assertEquals(2, PagedLayout.pages(29));
        assertEquals(3, PagedLayout.pages(60));
        assertEquals(2, PagedLayout.clamp(99, 60));
        assertEquals(0, PagedLayout.clamp(-3, 60));
        assertEquals(" (1/3)", PagedLayout.suffix(0, 60));
        assertEquals("", PagedLayout.suffix(0, 5));
        assertFalse(PagedLayout.hasPrev(0));
        assertTrue(PagedLayout.hasNext(0, 60));
        assertFalse(PagedLayout.hasNext(2, 60));
        assertEquals(10, PagedLayout.INNER[0]);
        assertEquals(43, PagedLayout.INNER[27]);
        for (int s : PagedLayout.INNER) {
            int col = s % 9, row = s / 9;
            assertTrue(col >= 1 && col <= 7 && row >= 1 && row <= 4, "slot " + s + " is not inner");
        }
    }

    @Test
    void sidebarDropsLeastImportantFirst() {
        List<String> head = List.of("date", "", "day", "time", "loc", "", "purse", "bits");
        List<String> out = SidebarFrame.compose(head,
                List.of(List.of("a1", "a2"), List.of("b1", "b2", "b3", "b4"), List.of("c1")), "foot", 15);
        assertTrue(out.size() <= 15);
        assertEquals("foot", out.get(out.size() - 1));
        assertEquals("", out.get(out.size() - 2));
        assertTrue(out.contains("a1"));
        assertFalse(out.contains("b1"));
        assertTrue(out.contains("c1"));
        assertTrue(out.indexOf("a1") < out.indexOf("c1"), "priority order kept");
    }

    @Test
    void sidebarWithoutSections() {
        List<String> head = List.of("date", "", "day", "time", "loc", "", "purse", "bits");
        List<String> out = SidebarFrame.compose(head, List.of(), "foot", 15);
        assertEquals(10, out.size());
        assertEquals("foot", out.get(9));
    }

    @Test
    void sidebarNeverExceedsMax() {
        List<String> head = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16");
        List<String> out = SidebarFrame.compose(head, List.of(List.of("x")), "foot", 15);
        assertEquals(15, out.size());
        assertEquals("foot", out.get(14));
    }

    @Test
    void sidebarReportsDroppedSections() {
        List<String> head = List.of("date", "", "day", "time", "loc", "", "purse", "bits", "x");
        List<String> fair = List.of("Harvest Fair", "Wheat 1,952", "BRONZE #15/41");
        List<String> obj = List.of("Objective", "Talk to the Banker");
        List<List<String>> dropped = new java.util.ArrayList<>();
        List<String> out = SidebarFrame.compose(head, List.of(fair, obj), "foot", 15, dropped);
        assertTrue(out.contains("Harvest Fair"));
        assertFalse(out.contains("Objective"));
        assertEquals(List.of(obj), dropped);
    }

    @Test
    void tabFooterPutsOverflowFirst() {
        List<String> out = SidebarFrame.tabFooter(List.of(List.of("W1")), List.of(List.of("Objective", "Talk")), "tail", 40);
        assertEquals(List.of("Objective", "Talk", "", "W1", "", "tail"), out);
    }

    @Test
    void tabFooterJoinsWidgets() {
        List<String> out = SidebarFrame.tabFooter(List.of(List.of("&e&lSkills:", " Farming 5"), List.of(), List.of("&e&lPet:")), "tail", 40);
        assertEquals(List.of("&e&lSkills:", " Farming 5", "", "&e&lPet:", "", "tail"), out);
    }

    @Test
    void levelColours() {
        assertEquals("&7", LevelColors.of(0));
        assertEquals("&7", LevelColors.of(39));
        assertEquals("&f", LevelColors.of(40));
        assertEquals("&b", LevelColors.of(212));
        assertEquals("&4", LevelColors.of(5000));
        assertEquals("&8[&b212&8]", LevelColors.bracket(212));
        assertEquals("&7[&e100&7] ", ChatFormat.prefix(100));
    }

    @Test
    void signTextIsCleaned() {
        assertNull(InputPrompts.clean("cancel"));
        assertNull(InputPrompts.clean("CANCEL"));
        assertNull(InputPrompts.clean("   "));
        assertEquals("hi there", InputPrompts.clean("  hi§c there "));
        assertEquals(InputPrompts.MAX_LEN, InputPrompts.clean("x".repeat(1000)).length());
        List<String> w = InputPrompts.wrapPrompt("Enter the amount you want to deposit");
        assertTrue(w.size() >= 1 && w.size() <= 2);
        for (String l : w) assertTrue(l.length() <= 15, l);
    }
}
