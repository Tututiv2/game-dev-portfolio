package com.skyclone.mod.collections;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Wave 5: per-profile-mode collection leaderboards (pure parts; no server needed). */
class CollectionsLeaderboardModesTest {

    private static CollectionsLeaderboard.Raw raw(String coll, String mode, String pid, long total) {
        return new CollectionsLeaderboard.Raw(coll, mode, new CollectionsLeaderboard.Row(pid, total, pid.toUpperCase()));
    }

    @Test
    void classicSpellingsShareOneBoard() {
        assertEquals("normal", CollectionsLeaderboard.boardOf(null, true));
        assertEquals("normal", CollectionsLeaderboard.boardOf("", true));
        assertEquals("normal", CollectionsLeaderboard.boardOf("normal", true));
        assertEquals("normal", CollectionsLeaderboard.boardOf(" Classic ", true));
    }

    @Test
    void restrictedModesGetTheirOwnBoards() {
        assertEquals("ironman", CollectionsLeaderboard.boardOf("IRONMAN", true));
        assertEquals("stranded", CollectionsLeaderboard.boardOf("stranded", true));
        assertEquals("bingo", CollectionsLeaderboard.boardOf("bingo", true));
        // a future mode is never silently mixed into Classic, but junk is
        assertEquals("hardcore", CollectionsLeaderboard.boardOf("hardcore", true));
        assertEquals("normal", CollectionsLeaderboard.boardOf("drop table;--", true));
    }

    @Test
    void splitOffPutsEveryoneOnOneBoard() {
        assertEquals("normal", CollectionsLeaderboard.boardOf("ironman", false));
        List<CollectionsLeaderboard.Raw> raws = List.of(raw("WHEAT", "ironman", "a", 30_000), raw("WHEAT", "normal", "b", 40_000));
        Map<String, List<CollectionsLeaderboard.Row>> g = CollectionsLeaderboard.group(raws, false);
        assertEquals(1, g.size());
        assertEquals("b", g.get(CollectionsLeaderboard.key("normal", "WHEAT")).get(0).profileId());
    }

    @Test
    void boardsAreSortedAndRankedSeparately() {
        List<CollectionsLeaderboard.Raw> raws = List.of(
                raw("WHEAT", "normal", "c1", 900_000),
                raw("WHEAT", "ironman", "i1", 30_000),
                raw("WHEAT", "ironman", "i2", 80_000),
                raw("CARROT_ITEM", "ironman", "i1", 50_000));
        Map<String, List<CollectionsLeaderboard.Row>> g = CollectionsLeaderboard.group(raws, true);
        List<CollectionsLeaderboard.Row> iron = g.get(CollectionsLeaderboard.key("ironman", "WHEAT"));
        assertEquals(List.of("i2", "i1"), iron.stream().map(CollectionsLeaderboard.Row::profileId).toList());
        // i1 is #2 of 2 among Ironmen; the Classic 900,000 does not push it down
        CollectionsLeaderboard.Position pos = CollectionsLeaderboard.rank(iron, "i1", 30_000);
        assertEquals(2, pos.rank());
        assertEquals(2, pos.size());
        assertEquals(1, g.get(CollectionsLeaderboard.key("ironman", "CARROT_ITEM")).size());
        assertNull(g.get(CollectionsLeaderboard.key("normal", "CARROT_ITEM")));
    }

    @Test
    void playersCanNameABoard() {
        assertEquals("normal", CollectionsLeaderboard.parseBoard("classic"));
        assertEquals("ironman", CollectionsLeaderboard.parseBoard("IM"));
        assertEquals("stranded", CollectionsLeaderboard.parseBoard("Stranded"));
        assertNull(CollectionsLeaderboard.parseBoard("wheat"));
        assertEquals("Classic", CollectionsLeaderboard.label("normal"));
        assertEquals("Ironman", CollectionsLeaderboard.label("ironman"));
    }
}
