package com.skyclone.mod.dungeons.puzzle;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Three Strangers (our take on the reference "Three Weirdos", research 04 sec 3.9). Three NPCs stand by three chests and
 * each says one thing. The RULE, written on a sign in the room: <b>exactly one of them is lying</b>. Statements are
 * generated from a small grammar and brute-forced (3 reward positions x the 3 "who lies" cases) until exactly one chest
 * is consistent. Opening the wrong chest fails the puzzle. Pure.
 */
public final class Strangers {

    // None of these may be a world NPC's name (Corvin, Fennick, Hessa, Orrin, Pell and Tavi were; wave-4 names pass).
    // Guarded by NpcNamesTest.
    public static final String[] NAMES = {"Ardo", "Belka", "Cuthbert", "Dresk", "Elva", "Fabian", "Gorran", "Horatio", "Ilvo",
            "Jorn", "Kestra", "Lomm", "Marrow", "Nessa", "Osgood", "Prewitt", "Quist", "Rhoswen", "Stelk", "Tullus", "Ulda", "Varn"};

    public record Puzzle(String[] names, String[] lines, int reward, int liar, St[] statements) {}

    public enum Kind { NOT_MINE, MINE, IS_X, NOT_X, LIAR, HONEST, BOTH_HONEST, ONE_LIES_NOT_X }

    public record St(Kind kind, int x) {}

    private Strangers() {}

    public static Puzzle generate(long seed) {
        Random r = new Random(seed);
        List<String> pool = new ArrayList<>(List.of(NAMES));
        java.util.Collections.shuffle(pool, r);
        String[] names = {pool.get(0), pool.get(1), pool.get(2)};
        for (int attempt = 0; attempt < 5000; attempt++) {
            St[] st = new St[3];
            for (int s = 0; s < 3; s++) {
                Kind k = Kind.values()[r.nextInt(Kind.values().length)];
                int other = (s + 1 + r.nextInt(2)) % 3;
                st[s] = new St(k, other);
            }
            int found = -1, liar = -1, solutions = 0;
            for (int reward = 0; reward < 3; reward++) {
                boolean ok = false;
                int okLiar = -1;
                for (int lie = 0; lie < 3; lie++) {
                    boolean[] truth = {lie != 0, lie != 1, lie != 2};
                    boolean consistent = true;
                    for (int s = 0; s < 3 && consistent; s++) if (eval(st[s], s, reward, truth) != truth[s]) consistent = false;
                    if (consistent) { if (ok) { okLiar = -2; } else { ok = true; okLiar = lie; } }
                }
                if (ok) { solutions++; found = reward; liar = okLiar; }
            }
            if (solutions != 1 || liar < 0) continue;
            String[] lines = new String[3];
            for (int s = 0; s < 3; s++) lines[s] = text(st[s], s, names);
            return new Puzzle(names, lines, found, liar, st);
        }
        // a fallback that is always unique: two truth-tellers pointing at the reward, one liar claiming his own chest
        St[] fb = {new St(Kind.NOT_MINE, 1), new St(Kind.IS_X, 0), new St(Kind.MINE, 0)};
        return new Puzzle(names, new String[]{text(fb[0], 0, names), text(fb[1], 1, names), text(fb[2], 2, names)}, 2, 1, fb);
    }

    /** Whether statement {@code st} by speaker {@code s} is TRUE in the world (reward, truth). */
    static boolean eval(St st, int s, int reward, boolean[] truth) {
        int a = (s + 1) % 3, b = (s + 2) % 3;
        return switch (st.kind) {
            case NOT_MINE -> reward != s;
            case MINE -> reward == s;
            case IS_X -> reward == st.x;
            case NOT_X -> reward != st.x;
            case LIAR -> !truth[st.x];
            case HONEST -> truth[st.x];
            case BOTH_HONEST -> truth[a] && truth[b];
            case ONE_LIES_NOT_X -> (!truth[a] || !truth[b]) && reward != st.x;
        };
    }

    private static String text(St st, int s, String[] n) {
        String x = n[st.x];
        return switch (st.kind) {
            case NOT_MINE -> "The reward is not in my chest!";
            case MINE -> "My chest has the reward!";
            case IS_X -> "The reward is in " + x + "'s chest.";
            case NOT_X -> "The reward isn't in " + x + "'s chest.";
            case LIAR -> x + " is lying.";
            case HONEST -> x + " is telling the truth.";
            case BOTH_HONEST -> "Both of them are telling the truth.";
            case ONE_LIES_NOT_X -> "At least one of them is lying, and the reward is not in " + x + "'s chest!";
        };
    }

    /** How many chests are consistent with the rule "exactly one liar" (a valid puzzle has exactly 1). */
    public static int solutions(Puzzle p) {
        int n = 0;
        for (int reward = 0; reward < 3; reward++) {
            boolean ok = false;
            for (int lie = 0; lie < 3 && !ok; lie++) {
                boolean[] truth = {lie != 0, lie != 1, lie != 2};
                boolean consistent = true;
                for (int s = 0; s < 3 && consistent; s++) if (eval(p.statements()[s], s, reward, truth) != truth[s]) consistent = false;
                ok = consistent;
            }
            if (ok) n++;
        }
        return n;
    }
}
