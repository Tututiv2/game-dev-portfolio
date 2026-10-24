package com.skyclone.mod.mayors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * One round of Fruit Digging, pure (no Bukkit), after the reference wiki: a 7x7 patch of sand holds 34 fruit,
 * 10 bombs and 5 bottles of rum; the Carnival Shovel digs 15 times in 5 minutes.
 * <ul>
 *   <li>Mango 300 · Dragonfruit 1,200 · Coconut 200 and the next dig cannot set off a bomb · Apple 0 +100 for every
 *       apple found so far (dug or splashed) · Cherry 200 +300 for every cherry before it · Pomegranate 200 and the
 *       next dig's points x1.5 · Durian 800 and the next dig's points x0.5 · Watermelon 100 and it splashes open every
 *       undiscovered fruit around it for half that fruit's points.</li>
 *   <li>A bomb destroys every undiscovered fruit around it (the round goes on). Rum switches the shovel's dowsing off
 *       for the next dig.</li>
 *   <li>Dowsing after each dig: <b>Mines</b> counts the bombs touching the square, <b>Treasure</b> names the best fruit
 *       touching it, <b>Anchor</b> points at the lowest-scoring fruit touching it.</li>
 * </ul>
 * Cell {@code i} is column {@code i % W} (x, west to east) and row {@code i / W} (z, north to south).
 */
final class FruitDig {

    static final int W = 7, H = 7, CELLS = W * H;
    static final List<String> FRUITS = List.of("DRAGONFRUIT", "DURIAN", "MANGO", "COCONUT", "CHERRY", "POMEGRANATE", "WATERMELON", "APPLE");

    enum Mode {
        MINES("&cMines", "Counts the bombs touching the square you dig."),
        ANCHOR("&9Anchor", "Points at the lowest-scoring fruit touching it."),
        TREASURE("&6Treasure", "Names the best fruit touching it.");
        final String label, about;
        Mode(String label, String about) { this.label = label; this.about = about; }
        Mode next() { return values()[(ordinal() + 1) % values().length]; }
    }

    /** What one dig did: chat line, points, the dowsing line, the cell Anchor points at (-1), every cell whose look changed. */
    record Result(String line, int points, String dowse, int anchorCell, List<Integer> changed) {}

    final String[] cells;
    final boolean[] dug = new boolean[CELLS];
    final boolean[] destroyed = new boolean[CELLS];
    final int[] earned = new int[CELLS];
    int digsLeft;
    int score;
    int apples, cherries, fruitsFound, bombs;
    final Set<String> unique = new LinkedHashSet<>();
    boolean dragonfruit;
    double nextMult = 1.0;
    boolean shield, drunk;
    boolean over;
    String endReason;

    FruitDig(String[] cells, int digs) { this.cells = cells; this.digsLeft = digs; }

    static boolean isFruit(String c) { return c != null && FRUITS.contains(c); }

    /** The board: the configured counts shuffled into 49 squares (padded with dirt, truncated if over). */
    static String[] layout(Map<String, Integer> counts, long seed) {
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet())
            for (int i = 0; i < Math.max(0, e.getValue()); i++) all.add(e.getKey());
        while (all.size() < CELLS) all.add("DIRT");
        Collections.shuffle(all, new Random(seed));
        return all.subList(0, CELLS).toArray(new String[0]);
    }

    static List<Integer> around(int i) {
        List<Integer> out = new ArrayList<>(8);
        int x = i % W, y = i / W;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && ny >= 0 && nx < W && ny < H) out.add(ny * W + nx);
            }
        return out;
    }

    boolean hidden(int j) { return !dug[j] && !destroyed[j]; }

    int fruitsLeft() {
        int n = 0;
        for (int j = 0; j < CELLS; j++) if (hidden(j) && isFruit(cells[j])) n++;
        return n;
    }

    int totalFruit() {
        int n = 0;
        for (String c : cells) if (isFruit(c)) n++;
        return n;
    }

    /** Points a fruit is worth right now (advances the apple/cherry counters: call once per fruit found). */
    private int value(String c, Map<String, Integer> pts) {
        int base = pts.getOrDefault(c, 0);
        return switch (c) {
            case "APPLE" -> { apples++; yield base + 100 * apples; }
            case "CHERRY" -> { int v = base + 300 * cherries; cherries++; yield v; }
            default -> base;
        };
    }

    private void found(String c) {
        fruitsFound++;
        unique.add(c);
        if ("DRAGONFRUIT".equals(c)) dragonfruit = true;
    }

    /** Dig square {@code i} in dowsing {@code mode}. Null when the dig is not allowed. */
    Result dig(int i, Mode mode, Map<String, Integer> pts) {
        if (over || i < 0 || i >= CELLS || dug[i] || destroyed[i] || digsLeft <= 0) return null;
        dug[i] = true;
        digsLeft--;
        boolean ability = !drunk;
        drunk = false;
        boolean shieldNow = shield;
        shield = false;
        double mult = nextMult;
        nextMult = 1.0;
        List<Integer> changed = new ArrayList<>();
        changed.add(i);
        String c = cells[i];
        String line;
        int gained = 0;
        switch (c) {
            case "BOMB" -> {
                if (shieldNow) {
                    line = "&fThe &fCoconut &fkept the &cbomb &ffrom going off!";
                } else {
                    bombs++;
                    int lost = 0;
                    for (int j : around(i))
                        if (hidden(j) && isFruit(cells[j])) { destroyed[j] = true; changed.add(j); lost++; }
                    line = "&c&lBOOM! &7A bomb went off" + (lost > 0 ? " and destroyed &c" + lost + " &7fruit nearby." : ". Nothing was hurt.");
                }
            }
            case "RUM" -> { drunk = true; line = "&6Rum! &7Your shovel is too tipsy to dowse on the next dig."; }
            case "DIRT" -> line = "&7Just sand.";
            default -> {
                found(c);
                gained = value(c, pts);
                switch (c) {
                    case "COCONUT" -> { shield = true; line = "&fCoconut! &7The next dig cannot set off a bomb."; }
                    case "POMEGRANATE" -> { nextMult = 1.5; line = "&dPomegranate! &7The next dig scores &ax1.5&7."; }
                    case "DURIAN" -> { nextMult = 0.5; line = "&6Durian! &7Worth a lot, but the next dig scores &cx0.5&7."; }
                    case "APPLE" -> line = "&cApple! &7That makes &a" + apples + "&7.";
                    case "CHERRY" -> line = cherries > 1 ? "&cCherry! &7Another pair." : "&cCherry! &7Find another for a bonus.";
                    case "DRAGONFRUIT" -> line = "&d&lDRAGONFRUIT! &7The rarest fruit of all.";
                    case "WATERMELON" -> {
                        int splash = 0, n = 0;
                        for (int j : around(i))
                            if (hidden(j) && isFruit(cells[j])) {
                                dug[j] = true;
                                found(cells[j]);
                                int v = (int) Math.round(value(cells[j], pts) * 0.5);
                                earned[j] = (int) Math.round(v * mult);
                                splash += v;
                                n++;
                                changed.add(j);
                            }
                        gained += splash;
                        line = "&aWatermelon! &7It splashed open &a" + n + " &7fruit" + (n == 1 ? "" : "s") + " for half their points.";
                    }
                    default -> line = "&e" + titleCase(c) + "!";
                }
            }
        }
        gained = (int) Math.round(gained * mult);
        earned[i] = gained;
        score += gained;

        String dowse = null;
        int anchor = -1;
        if (ability) {
            switch (mode) {
                case MINES -> {
                    int n = 0;
                    for (int j : around(i)) if (!dug[j] && "BOMB".equals(cells[j])) n++;
                    dowse = "&c&lMINES! &7There " + (n == 1 ? "is" : "are") + " &c" + n + " &7bomb" + (n == 1 ? "" : "s") + " hidden nearby.";
                }
                case TREASURE -> {
                    String best = null;
                    for (int j : around(i))
                        if (hidden(j) && isFruit(cells[j]) && (best == null || pts.getOrDefault(cells[j], 0) > pts.getOrDefault(best, 0))) best = cells[j];
                    dowse = best == null ? "&6&lTREASURE! &7There are no fruits nearby!"
                            : "&6&lTREASURE! &7There is " + article(best) + " &e" + titleCase(best) + " &7nearby.";
                }
                case ANCHOR -> {
                    for (int j : around(i))
                        if (hidden(j) && isFruit(cells[j]) && (anchor < 0 || pts.getOrDefault(cells[j], 0) < pts.getOrDefault(cells[anchor], 0))) anchor = j;
                    dowse = anchor < 0 ? "&9&lANCHOR! &7There are no fruits nearby!"
                            : "&9&lANCHOR! &7The lowest fruit nearby is to the &e" + direction(i, anchor) + "&7.";
                }
            }
        } else {
            dowse = "&8The rum fogs your shovel: no dowsing this time.";
        }

        if (digsLeft <= 0) { over = true; endReason = "Out of digs!"; }
        else if (fruitsLeft() == 0) { over = true; endReason = "No fruit left to find!"; }
        return new Result(line + (gained > 0 ? " &e+" + gained : ""), gained, dowse, anchor, changed);
    }

    static String direction(int from, int to) {
        int dx = to % W - from % W, dz = to / W - from / W;
        String ns = dz < 0 ? "north" : dz > 0 ? "south" : "";
        String ew = dx > 0 ? "east" : dx < 0 ? "west" : "";
        return ns.isEmpty() ? ew : ew.isEmpty() ? ns : ns + "-" + ew;
    }

    static String article(String c) { return "AEIOU".indexOf(c.charAt(0)) >= 0 ? "an" : "a"; }

    static String titleCase(String id) {
        String s = id.toLowerCase().replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
