package com.skyclone.mod.dungeons.puzzle;

/**
 * Tic Tac Toe against a perfect (minimax) computer that moves first (research 04 sec 3.9). Board cells 0..8 row-major;
 * 0 empty, 1 = the player (X), 2 = the computer (O). The computer can never lose, so the best a player can do is a draw,
 * which SOLVES the puzzle; a computer win fails it. Pure.
 */
public final class TicTacToe {

    public static final int EMPTY = 0, PLAYER = 1, COMPUTER = 2;
    private static final int[][] LINES = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};

    private TicTacToe() {}

    /** PLAYER, COMPUTER, or EMPTY when nobody has three in a row. */
    public static int winner(int[] b) {
        for (int[] l : LINES) if (b[l[0]] != EMPTY && b[l[0]] == b[l[1]] && b[l[1]] == b[l[2]]) return b[l[0]];
        return EMPTY;
    }

    public static boolean full(int[] b) {
        for (int v : b) if (v == EMPTY) return false;
        return true;
    }

    /** The computer's best move (perfect play); ties broken by {@code tieBreak} so games differ. -1 if none. */
    public static int bestMove(int[] b, int tieBreak) {
        int best = -1, bestScore = Integer.MIN_VALUE;
        for (int k = 0; k < 9; k++) {
            int i = (k + Math.floorMod(tieBreak, 9)) % 9;
            if (b[i] != EMPTY) continue;
            b[i] = COMPUTER;
            int s = minimax(b, false, 0);
            b[i] = EMPTY;
            if (s > bestScore) { bestScore = s; best = i; }
        }
        return best;
    }

    private static int minimax(int[] b, boolean computerTurn, int depth) {
        int w = winner(b);
        if (w == COMPUTER) return 10 - depth;
        if (w == PLAYER) return depth - 10;
        if (full(b)) return 0;
        int best = computerTurn ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            if (b[i] != EMPTY) continue;
            b[i] = computerTurn ? COMPUTER : PLAYER;
            int s = minimax(b, !computerTurn, depth + 1);
            b[i] = EMPTY;
            best = computerTurn ? Math.max(best, s) : Math.min(best, s);
        }
        return best;
    }
}
