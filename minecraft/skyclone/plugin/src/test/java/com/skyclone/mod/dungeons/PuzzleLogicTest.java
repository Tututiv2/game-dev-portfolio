package com.skyclone.mod.dungeons;

import com.skyclone.mod.dungeons.puzzle.GridPuzzles;
import com.skyclone.mod.dungeons.puzzle.Strangers;
import com.skyclone.mod.dungeons.puzzle.TicTacToe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every generated puzzle is solvable and the failable ones are fair (research 04 sec 3.9). */
class PuzzleLogicTest {

    @Test
    void ticTacToeComputerNeverLoses() {
        // exhaustive: every possible sequence of player moves against the minimax computer
        assertTrue(neverLoses(new int[9], true));
    }

    private boolean neverLoses(int[] b, boolean computerTurn) {
        int w = TicTacToe.winner(b);
        if (w == TicTacToe.PLAYER) return false;
        if (w == TicTacToe.COMPUTER || TicTacToe.full(b)) return true;
        if (computerTurn) {
            int m = TicTacToe.bestMove(b, 0);
            b[m] = TicTacToe.COMPUTER;
            boolean ok = neverLoses(b, false);
            b[m] = TicTacToe.EMPTY;
            return ok;
        }
        for (int i = 0; i < 9; i++) {
            if (b[i] != TicTacToe.EMPTY) continue;
            b[i] = TicTacToe.PLAYER;
            boolean ok = neverLoses(b, true);
            b[i] = TicTacToe.EMPTY;
            if (!ok) return false;
        }
        return true;
    }

    @Test
    void strangersHaveExactlyOneAnswer() {
        Random r = new Random(1);
        for (int i = 0; i < 300; i++) {
            Strangers.Puzzle p = Strangers.generate(r.nextLong());
            assertEquals(1, Strangers.solutions(p));
            assertEquals(3, p.lines().length);
            assertNotEquals(p.names()[0], p.names()[1]);
        }
    }

    @Test
    void waterBoardSolvable() {
        Random r = new Random(2);
        for (int i = 0; i < 200; i++) {
            GridPuzzles.Water w = GridPuzzles.water(r.nextLong());
            assertEquals(3, Integer.bitCount(w.closed()));
            boolean[] s = w.solve();
            assertNotNull(s);
            assertEquals(0x1F, w.doorsAfter(s));
            assertNotEquals(0x1F, w.doorsAfter(new boolean[6]), "not solved by doing nothing");
            assertTrue(w.minLevers() >= 2, "playtest-endgame: no board is solved by one lever");
            for (int k = 0; k < 6; k++) {
                boolean[] one = new boolean[6];
                one[k] = true;
                assertNotEquals(0x1F, w.doorsAfter(one), "solved by lever " + k + " alone");
            }
        }
    }

    /**
     * playtest-endgame: the Tic Tac Toe top row was out of reach without jumping. Every button must be inside the
     * 4.5-block block-interaction reach from a player standing on the floor (feet at h 1, eye 1.62 above them) right
     * in front of the board (the button sits in the last 2/16 of its block; the eye can be ~0.4 short of it).
     */
    @Test
    void ticTacToeButtonsInReach() {
        double eye = 1 + 1.62, reach = 4.5, across = 0.45;
        for (int row = 0; row < 3; row++) {
            int h = com.skyclone.mod.dungeons.puzzle.PuzzleBuilders.tttRowH(row);
            double lo = h + 0.375, hi = h + 0.625;                     // a wall button is 4/16 tall, centred
            double up = eye < lo ? lo - eye : eye > hi ? eye - hi : 0;
            double d = Math.sqrt(up * up + across * across);
            assertTrue(d <= reach - 0.25, "row " + row + " (h " + h + ") is " + d + " from the eye");
            assertTrue(h >= 1, "row " + row + " is above the floor");
        }
    }

    /**
     * wave-4 endgame: Creeper Beams lanterns at floor+6 and up against the wall could only be clicked looking straight up
     * from directly below. Every lantern height (and its mirrored partner's) must be a normal click from a player
     * standing 2 blocks out from the wall, and every pair's midpoint must still pass through the creeper.
     */
    @Test
    void creeperBeamLanternsInReach() {
        double eye = 1 + 1.62, reach = 4.5, across = 2.0;
        double creeper = com.skyclone.mod.dungeons.puzzle.PuzzleBuilders.BEAM_CREEPER_H + 0.85;   // Puzzles.BeamsPuzzle centre
        for (int h = com.skyclone.mod.dungeons.puzzle.PuzzleBuilders.BEAM_LOW; h <= com.skyclone.mod.dungeons.puzzle.PuzzleBuilders.BEAM_HIGH; h++) {
            int q = com.skyclone.mod.dungeons.puzzle.PuzzleBuilders.beamPairH(h);
            for (int y : new int[]{h, q}) {
                double up = Math.abs(y + 0.5 - eye);
                assertTrue(Math.sqrt(up * up + across * across) <= reach - 0.25, "a lantern at h " + y + " is out of reach");
                assertTrue(y >= com.skyclone.mod.dungeons.puzzle.PuzzleBuilders.BEAM_LOW && y <= com.skyclone.mod.dungeons.puzzle.PuzzleBuilders.BEAM_HIGH, "partner of h " + h + " at h " + y);
            }
            double mid = (h + 0.5 + q + 0.5) / 2;
            assertTrue(Math.abs(mid - creeper) <= 1.6, "pair " + h + "/" + q + " misses the creeper by " + Math.abs(mid - creeper));
        }
    }

    @Test
    void teleportMazeRoute() {
        Random r = new Random(3);
        for (int i = 0; i < 200; i++) {
            int h = GridPuzzles.teleport(r.nextLong()).hops();
            assertTrue(h >= 2 && h <= 5, "hops " + h);
        }
    }

    @Test
    void boulderSolvable() {
        Random r = new Random(4);
        for (int i = 0; i < 15; i++) {
            long g = GridPuzzles.boulder(r.nextLong());
            int s = GridPuzzles.boulderSolve(g, 200_000);
            assertTrue(s >= 1, "boulder needs at least one push and can be solved");
        }
    }

    @Test
    void icePathSolvable() {
        Random r = new Random(5);
        for (int i = 0; i < 100; i++) {
            int s = GridPuzzles.icePath(r.nextLong()).solve();
            assertTrue(s >= 4 && s <= 14);
        }
    }

    @Test
    void iceFillHasAWalk() {
        Random r = new Random(6);
        for (int n : new int[]{3, 5, 7})
            for (int i = 0; i < 40; i++) {
                GridPuzzles.IceFill f = GridPuzzles.iceFill(r.nextLong(), n);
                assertTrue(f.valid(f.solution()), n + "x" + n);
                int blocked = n * n - f.free();
                assertTrue(blocked >= 2 && blocked <= 6, "obstacles " + blocked);
                if (n <= 5) assertNotNull(GridPuzzles.hamiltonian(f, 2_000_000), "independent solver agrees");
            }
        List<Integer> bad = new ArrayList<>(GridPuzzles.iceFill(9, 5).solution());
        bad.remove(bad.size() - 1);
        assertTrue(!GridPuzzles.iceFill(9, 5).valid(bad), "an incomplete walk is rejected");
    }
}
