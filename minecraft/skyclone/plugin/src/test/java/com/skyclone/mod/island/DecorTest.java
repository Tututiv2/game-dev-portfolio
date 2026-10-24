package com.skyclone.mod.island;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5b: the island decorations' rules (parkour, egg hunt, boards, limits, typed text) and Island Ranks. */
class DecorTest {

    private static Decor.State course(boolean loop, int checkpoints) {
        Decor.State s = new Decor.State();
        Decor.add(s, Decor.START, 0, 70, 0, 1, "o");
        for (int i = 1; i <= checkpoints; i++) Decor.add(s, Decor.POINT, i * 5, 70 + i, 0, 1 + i, "o");
        if (!loop) Decor.add(s, Decor.START, 100, 80, 0, 50, "o");
        return s;
    }

    @Test
    void courseIsStartCheckpointsInPlacementOrderFinish() {
        Decor.State s = new Decor.State();
        assertFalse(Decor.course(s).ok(), "no start plate = no course");
        Decor.add(s, Decor.START, 0, 70, 0, 1, "o");
        assertFalse(Decor.course(s).ok(), "one plate and no checkpoint cannot be a course");
        // checkpoints placed out of positional order: placement order wins
        Decor.add(s, Decor.POINT, 30, 70, 0, 2, "o");
        Decor.add(s, Decor.POINT, 10, 70, 0, 3, "o");
        Decor.Course c = Decor.course(s);
        assertTrue(c.ok());
        assertTrue(c.loop());
        assertEquals(List.of("30,70,0", "10,70,0"), c.checkpoints().stream().map(Decor.Placed::pos).toList());
        Decor.add(s, Decor.START, 50, 70, 0, 4, "o");
        c = Decor.course(s);
        assertFalse(c.loop());
        assertEquals("50,70,0", c.end().pos());
        assertEquals(-1, c.role("0,70,0"));
        assertEquals(-2, c.role("50,70,0"));
        assertEquals(1, c.role("10,70,0"));
        assertEquals(-3, c.role("9,9,9"));
    }

    @Test
    void runsMustTakeEveryCheckpointInOrder() {
        Decor.Course c = Decor.course(course(false, 3));
        Decor.Run[] out = new Decor.Run[1];
        assertEquals(Decor.Step.NONE, Decor.step(c, null, "5,71,0", 0, out), "a checkpoint does not start a run");
        assertEquals(Decor.Step.NONE, Decor.step(c, null, "100,80,0", 0, out), "the finish does not start a run");
        assertEquals(Decor.Step.STARTED, Decor.step(c, null, "0,70,0", 1_000, out));
        Decor.Run r = out[0];
        assertNotNull(r);
        assertEquals(Decor.Step.WRONG_CHECKPOINT, Decor.step(c, r, "10,72,0", 1_500, out));
        assertEquals(Decor.Step.CHECKPOINT, Decor.step(c, r, "5,71,0", 2_000, out));
        assertEquals(Decor.Step.NONE, Decor.step(c, r, "5,71,0", 2_100, out), "an already-taken checkpoint is nothing");
        assertEquals(Decor.Step.MISSED, Decor.step(c, r, "100,80,0", 2_200, out));
        assertEquals(Decor.Step.CHECKPOINT, Decor.step(c, r, "10,72,0", 3_000, out));
        assertEquals(Decor.Step.CHECKPOINT, Decor.step(c, r, "15,73,0", 4_000, out));
        assertEquals(Decor.Step.FINISHED, Decor.step(c, r, "100,80,0", 13_340, out));
        assertNull(out[0], "a finished run is over");
        assertEquals(12_340, r.finalMs);
        assertEquals("12.34s", Decor.time(r.finalMs));
        assertEquals("1:02.50", Decor.time(62_500));
        // stepping on the start again restarts the timer
        Decor.step(c, null, "0,70,0", 0, out);
        Decor.Run r2 = out[0];
        Decor.step(c, r2, "5,71,0", 500, out);
        assertEquals(Decor.Step.RESTARTED, Decor.step(c, r2, "0,70,0", 900, out));
        assertEquals(0, out[0].next);
        assertEquals(900, out[0].startMs);
    }

    @Test
    void aLoopStartsAndFinishesOnOnePlate() {
        Decor.Course c = Decor.course(course(true, 2));
        Decor.Run[] out = new Decor.Run[1];
        assertEquals(Decor.Step.STARTED, Decor.step(c, null, "0,70,0", 0, out));
        Decor.Run r = out[0];
        assertEquals(Decor.Step.RESTARTED, Decor.step(c, r, "0,70,0", 100, out), "back on the plate before any checkpoint = restart");
        r = out[0];
        Decor.step(c, r, "5,71,0", 500, out);
        assertEquals(Decor.Step.MISSED, Decor.step(c, r, "0,70,0", 600, out));
        Decor.step(c, r, "10,72,0", 900, out);
        assertEquals(Decor.Step.FINISHED, Decor.step(c, r, "0,70,0", 1_200, out));
        assertEquals(1_100, r.finalMs);
    }

    @Test
    void changingTheCourseCancelsRunsAndResetsTimes() {
        Decor.State s = course(false, 1);
        Decor.Course c = Decor.course(s);
        assertTrue(Decor.recordTime(s, c.signature(), "p1", 9_000, 200));
        assertFalse(Decor.recordTime(s, c.signature(), "p1", 9_500, 200), "slower is not a new best");
        assertTrue(Decor.recordTime(s, c.signature(), "p1", 8_000, 200));
        assertTrue(Decor.recordTime(s, c.signature(), "p2", 7_000, 200));
        assertEquals(List.of("p2", "p1"), Decor.leaderboard(s.parkourBest, 10).stream().map(Map.Entry::getKey).toList());
        Decor.Run[] out = new Decor.Run[1];
        Decor.step(c, null, "0,70,0", 0, out);
        Decor.Run r = out[0];
        Decor.add(s, Decor.POINT, 50, 70, 0, 99, "o");
        assertTrue(Decor.syncCourse(s), "a new checkpoint changes the course and clears its times");
        assertTrue(s.parkourBest.isEmpty());
        assertEquals(Decor.Step.COURSE_CHANGED, Decor.step(Decor.course(s), r, "5,71,0", 10, out));
        assertNull(out[0]);
        assertFalse(Decor.syncCourse(s), "an unchanged course keeps its times");
        // the cap drops the slowest
        Decor.State t = course(false, 1);
        String sig = Decor.course(t).signature();
        for (int i = 0; i < 5; i++) Decor.recordTime(t, sig, "p" + i, 1_000L * (i + 1), 3);
        assertEquals(3, t.parkourBest.size());
        assertTrue(t.parkourBest.containsKey("p0") && t.parkourBest.containsKey("p1"));
    }

    @Test
    void eggsAndLimits() {
        Decor.State s = new Decor.State();
        Map<String, Integer> lim = Map.of(Decor.EGG, 2, Decor.START, 2);
        assertNull(Decor.placeProblem(s, Decor.EGG, lim));
        Decor.add(s, Decor.EGG, 1, 70, 1, 1, "o");
        Decor.add(s, Decor.EGG, 2, 70, 2, 1, "o");
        assertNotNull(Decor.placeProblem(s, Decor.EGG, lim), "the item says: maximum of 10 (here 2)");
        assertNull(Decor.placeProblem(s, Decor.SHOWCASE, lim), "no limit for a kind the map does not name");
        assertNotNull(Decor.placeProblem(s, "DIRT", lim));
        assertTrue(Decor.findEgg(s, "g", "1,70,1", 10));
        assertFalse(Decor.findEgg(s, "g", "1,70,1", 10), "each guest finds each egg once");
        assertEquals(0, Decor.completedHunts(s));
        assertTrue(Decor.findEgg(s, "g", "2,70,2", 10));
        assertEquals(1, Decor.completedHunts(s));
        assertEquals(2, Decor.eggProgress(s, "g")[0]);
        Decor.remove(s, 2, 70, 2);
        assertEquals(1, Decor.eggProgress(s, "g")[1], "a removed egg no longer counts");
        assertEquals(1, Decor.eggProgress(s, "g")[0]);
        assertFalse(Decor.findEgg(s, "h", "1,70,1", 1), "the hunter cap holds");
        // the visitors board
        Map<String, Integer> v = new HashMap<>(Map.of("a", 3, "b", 9, "c", 3));
        assertEquals(List.of("b", "a", "c"), Decor.topVisitors(v, 5).stream().map(Map.Entry::getKey).toList());
        assertEquals(1, Decor.topVisitors(v, 1).size());
    }

    @Test
    void storedStateSurvivesNullsAndUnknownKinds() {
        Decor.State s = Decor.sanitize(null);
        assertNotNull(s.placed);
        Decor.State t = new Decor.State();
        t.placed.add(new Decor.Placed("NOT_A_DECOR", 0, 0, 0, 7));
        t.placed.add(new Decor.Placed(Decor.EGG, 0, 0, 0, 9));
        t.parkourBest = null;
        t.eggsFound = null;
        Decor.sanitize(t);
        assertEquals(1, t.placed.size());
        assertEquals(9, t.seq, "the next seq continues after the stored ones");
        assertNotNull(t.parkourBest);
        assertNotNull(t.eggsFound);
        assertTrue(Decor.materialMatches(Decor.EGG, "PLAYER_WALL_HEAD"));
        assertFalse(Decor.materialMatches(Decor.SHOWCASE, "AIR"));
    }

    @Test
    void typedTextIsCleaned() {
        assertEquals("Red Name", Decor.cleanText("&cRed §lName<>", 24));
        assertEquals("Hi there!", Decor.cleanText("  Hi   there! ", 24));
        assertNull(Decor.cleanText("&a&l", 24));
        assertNull(Decor.cleanText("{}[]<>", 24), "only symbols = nothing usable");
        assertEquals("text:", Decor.cleanText("{\"text\":\"\"}", 24), "JSON-ish input is flattened to plain words");
        assertEquals("abcd", Decor.cleanText("abcdefgh", 4));
        assertTrue(Decor.blocked("You are a B-a-d word", "badword"));
        assertFalse(Decor.blocked("Fine text", "badword, other"));
        assertFalse(Decor.blocked("anything", ""));
        assertEquals("farmer", Decor.nextLook("none", 1));
        assertEquals("nitwit", Decor.nextLook("none", -1));
    }

    @Test
    void ranksOnlyLoosenGuestRules() {
        Map<String, String> ranks = new HashMap<>();
        assertEquals(IslandRank.GUEST, IslandRank.of(ranks, "x"));
        assertTrue(IslandRank.set(ranks, "x", IslandRank.FRIEND, 2));
        assertTrue(IslandRank.set(ranks, "y", IslandRank.TRUSTED, 2));
        assertFalse(IslandRank.set(ranks, "z", IslandRank.FRIEND, 2), "rank cap");
        assertTrue(IslandRank.set(ranks, "x", IslandRank.TRUSTED, 2), "re-ranking someone already ranked ignores the cap");
        assertTrue(IslandRank.set(ranks, "x", IslandRank.GUEST, 2));
        assertFalse(ranks.containsKey("x"), "Guest clears the entry");
        // Friend: guest limit + doors; Trusted: + containers + pickup; Guest: the island's settings, nothing more
        assertTrue(IslandRank.FRIEND.ignoresGuestLimit() && IslandRank.FRIEND.doors(false));
        assertFalse(IslandRank.FRIEND.containers(false) || IslandRank.FRIEND.pickup(false));
        assertTrue(IslandRank.TRUSTED.containers(false) && IslandRank.TRUSTED.pickup(false));
        assertFalse(IslandRank.GUEST.ignoresGuestLimit() || IslandRank.GUEST.doors(false) || IslandRank.GUEST.containers(false));
        assertTrue(IslandRank.GUEST.containers(true), "a rank never takes away what the island allows everyone");
        assertEquals(IslandRank.TRUSTED, IslandRank.parse("trust"));
        assertNull(IslandRank.parse("builder"), "there is no building rank");
        // workstations members always keep, containers are not among them
        assertTrue(CoopPerm.workstation("CRAFTING_TABLE"));
        assertFalse(CoopPerm.workstation("CHEST"));
        assertFalse(CoopPerm.workstation("BREWING_STAND"));
    }
}
