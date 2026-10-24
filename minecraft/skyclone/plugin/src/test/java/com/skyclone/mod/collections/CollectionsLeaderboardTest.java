package com.skyclone.mod.collections;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pure ranking behind the collection leaderboards (no server needed). */
class CollectionsLeaderboardTest {

    private static final List<CollectionsLeaderboard.Row> ROWS = List.of(
            new CollectionsLeaderboard.Row("a", 900_000, "A"),
            new CollectionsLeaderboard.Row("b", 50_000, "B"),
            new CollectionsLeaderboard.Row("me", 999_999, "Me (stale row)"),
            new CollectionsLeaderboard.Row("c", 40_000, "C"));

    @Test
    void ownStaleRowNeverOutranksYou() {
        CollectionsLeaderboard.Position p = CollectionsLeaderboard.rank(ROWS, "me", 60_000);
        assertEquals(2, p.rank());
        assertEquals(4, p.size());
    }

    @Test
    void tiesShareARank() {
        assertEquals(2, CollectionsLeaderboard.rank(ROWS, "me", 50_000).rank());
    }

    @Test
    void aProfileNotYetWrittenCountsItselfOnce() {
        CollectionsLeaderboard.Position p = CollectionsLeaderboard.rank(ROWS, "new", 30_000);
        assertEquals(5, p.rank());
        assertEquals(5, p.size());
        assertEquals(100, p.topPercent());
    }

    @Test
    void emptyBoardIsFirstOfOne() {
        CollectionsLeaderboard.Position p = CollectionsLeaderboard.rank(List.of(), "x", 25_000);
        assertEquals(1, p.rank());
        assertEquals(1, p.size());
    }

    @Test
    void topPercentRoundsUpAndIsNeverZero() {
        assertEquals(1, new CollectionsLeaderboard.Position(1, 1000).topPercent());
        assertEquals(5, new CollectionsLeaderboard.Position(50, 1000).topPercent());
        assertEquals(6, new CollectionsLeaderboard.Position(51, 1000).topPercent());
    }
}
