package com.skyclone.mod.extras;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/** The phone's two games as pure state (no Bukkit): Snake and Tic Tac Toe (research 08 section 6.4 "Apps"). */
final class PhoneGames {

    private PhoneGames() {}

    /** Snake on a W x H board; cells are y * W + x. */
    static final class Snake {
        static final int UP = 0, RIGHT = 1, DOWN = 2, LEFT = 3;
        final int w, h;
        final Deque<Integer> body = new ArrayDeque<>();   // head first
        private final Random rnd;
        int dir = RIGHT;
        private int pendingDir = RIGHT;
        int food = -1;
        int score;
        boolean dead;

        Snake(int w, int h, long seed) {
            this.w = w;
            this.h = h;
            this.rnd = new Random(seed);
            int y = h / 2;
            body.addFirst(y * w);
            body.addFirst(y * w + 1);
            body.addFirst(y * w + 2);
            placeFood();
        }

        void turn(int d) {
            if (d < 0 || d > 3) return;
            if ((d + 2) % 4 == dir) return;   // no reversing into yourself
            pendingDir = d;
        }

        void step() {
            if (dead) return;
            dir = pendingDir;
            int head = body.peekFirst();
            int x = head % w, y = head / w;
            switch (dir) {
                case UP -> y--;
                case DOWN -> y++;
                case LEFT -> x--;
                default -> x++;
            }
            if (x < 0 || y < 0 || x >= w || y >= h) { dead = true; return; }
            int next = y * w + x;
            boolean eat = next == food;
            if (!eat) body.pollLast();
            if (body.contains(next)) { dead = true; return; }
            body.addFirst(next);
            if (eat) {
                score++;
                placeFood();
            }
        }

        private void placeFood() {
            if (body.size() >= w * h) { food = -1; dead = true; return; }
            int c;
            do { c = rnd.nextInt(w * h); } while (body.contains(c));
            food = c;
        }
    }

    /** Tic Tac Toe against the phone. Cells 0-8; 1 = player (X), 2 = phone (O). */
    static final class TicTacToe {
        static final int[][] LINES = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}, {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {0, 4, 8}, {2, 4, 6}};
        final int[] cells = new int[9];
        private final Random rnd;
        private final double blunder;

        /** {@code blunder}: chance the phone plays a random move instead of the best one (0 = unbeatable). */
        TicTacToe(long seed, double blunder) {
            this.rnd = new Random(seed);
            this.blunder = Math.max(0, Math.min(1, blunder));
        }

        /** 0 = still playing, 1 = player won, 2 = phone won, 3 = draw. */
        int result() {
            for (int[] l : LINES) {
                int a = cells[l[0]];
                if (a != 0 && a == cells[l[1]] && a == cells[l[2]]) return a;
            }
            for (int c : cells) if (c == 0) return 0;
            return 3;
        }

        boolean play(int cell) {
            if (result() != 0 || cell < 0 || cell > 8 || cells[cell] != 0) return false;
            cells[cell] = 1;
            if (result() == 0) phoneMove();
            return true;
        }

        void phoneMove() {
            int free = 0;
            for (int c : cells) if (c == 0) free++;
            if (free == 0) return;
            int move;
            if (rnd.nextDouble() < blunder) {
                do { move = rnd.nextInt(9); } while (cells[move] != 0);
            } else {
                move = best();
            }
            cells[move] = 2;
        }

        private int best() {
            int bestScore = Integer.MIN_VALUE, best = -1;
            for (int i = 0; i < 9; i++) {
                if (cells[i] != 0) continue;
                cells[i] = 2;
                int s = minimax(false, 0);
                cells[i] = 0;
                if (s > bestScore) { bestScore = s; best = i; }
            }
            return best;
        }

        private int minimax(boolean phoneTurn, int depth) {
            int r = result();
            if (r == 2) return 10 - depth;
            if (r == 1) return depth - 10;
            if (r == 3) return 0;
            int best = phoneTurn ? Integer.MIN_VALUE : Integer.MAX_VALUE;
            for (int i = 0; i < 9; i++) {
                if (cells[i] != 0) continue;
                cells[i] = phoneTurn ? 2 : 1;
                int s = minimax(!phoneTurn, depth + 1);
                cells[i] = 0;
                best = phoneTurn ? Math.max(best, s) : Math.min(best, s);
            }
            return best;
        }
    }
}
