package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TodoListTest {

    @Test
    void addDoneUndoRemoveUseOneBasedNumbers() {
        TodoList l = new TodoList();
        assertTrue(l.add("Collect minions"));
        assertTrue(l.add("Kill 10 Voidglooms"));
        assertTrue(l.setDone(2, true));
        assertEquals(1, l.doneCount());
        assertEquals(Boolean.FALSE, l.toggle(2));
        assertEquals(Boolean.TRUE, l.toggle(1));
        assertEquals("Collect minions", l.remove(1).text());
        assertEquals(List.of(new TodoList.Item("Kill 10 Voidglooms", false)), l.items());
    }

    @Test
    void outOfRangeIsReportedNotThrown() {
        TodoList l = new TodoList();
        assertNull(l.toggle(1));
        assertNull(l.remove(0));
        assertFalse(l.setDone(5, true));
        assertFalse(l.move(1, 2));
    }

    @Test
    void blankAndOverlongTextAndFormattingCodes() {
        TodoList l = new TodoList();
        assertFalse(l.add("   "));
        assertFalse(l.add(null));
        l.add("§cRed§r text\nwith newline");
        assertEquals("Red text with newline", l.items().get(0).text());
        l.add("x".repeat(500));
        assertEquals(TodoList.MAX_TEXT, l.items().get(1).text().length());
    }

    @Test
    void theListStopsGrowingAtTheCap() {
        TodoList l = new TodoList();
        for (int i = 0; i < TodoList.MAX_ITEMS; i++) {
            assertTrue(l.add("item " + i));
        }
        assertFalse(l.add("one too many"));
        assertEquals(TodoList.MAX_ITEMS, l.size());
    }

    @Test
    void moveAndClearDone() {
        TodoList l = new TodoList();
        l.add("a");
        l.add("b");
        l.add("c");
        assertTrue(l.move(3, 1));
        assertEquals("c", l.items().get(0).text());
        l.setDone(1, true);
        l.setDone(3, true);
        assertEquals(2, l.clearDone());
        assertEquals(List.of(new TodoList.Item("a", false)), l.items());
        l.clear();
        assertEquals(0, l.size());
    }

    @Test
    void savedJsonRoundTrips() {
        TodoList l = new TodoList();
        l.add("Quote \" and backslash \\ survive");
        l.add("Done one");
        l.setDone(2, true);
        List<TodoList.Item> back = TodoList.parse(l.toJson());
        assertEquals(l.items(), back);
    }

    @Test
    void importAcceptsBothShapesAndSkipsJunkEntries() {
        List<TodoList.Item> a = TodoList.parse("[\"Collect minions\", {\"text\":\"Flip\",\"done\":true}, 42, {\"nope\":1}]");
        assertEquals(List.of(new TodoList.Item("Collect minions", false), new TodoList.Item("Flip", true)), a);
        List<TodoList.Item> b = TodoList.parse("{\"_comment\":\"x\",\"items\":[\"One\"]}");
        assertEquals(List.of(new TodoList.Item("One", false)), b);
        assertNull(TodoList.parse("{\"items\": 5}"));
        assertNull(TodoList.parse("not json at all {"));
        assertNull(TodoList.parse("\"just a string\""));
    }

    @Test
    void mergeSkipsDuplicatesIgnoringCase() {
        TodoList l = new TodoList();
        l.add("Collect minions");
        int[] r = l.merge(List.of(new TodoList.Item("collect MINIONS", false),
                new TodoList.Item("Bazaar flips", false), new TodoList.Item("  ", false)));
        assertArrayEquals(new int[] {1, 1}, r);
        assertEquals(2, l.size());
        l.replaceWith(List.of(new TodoList.Item("Only", true)));
        assertEquals(List.of(new TodoList.Item("Only", true)), l.items());
    }
}
