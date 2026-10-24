package com.skyclone.gen;

import java.util.ArrayList;
import java.util.List;

/**
 * Weighted block choice with deterministic per-position picks.
 *
 * <pre>
 *   Palette wall = Palette.of("stone_bricks:6,mossy_stone_bricks:2,cracked_stone_bricks:1");
 *   canvas.set(x, y, z, wall.at(x, y, z));     // same answer for the same position, every run
 * </pre>
 * Entries may carry block states ("oak_stairs[facing=north]:3"); commas inside [...] are fine.
 * A weight is the number after the LAST ':' when it is all digits; otherwise the weight is 1.
 */
public final class Palette implements Fill {

    private final String[] blocks;
    private final int[] cumulative;
    private final int total;
    private final long salt;

    public Palette(String[] blocks, int[] weights) {
        if (blocks.length == 0) throw new IllegalArgumentException("empty palette");
        this.blocks = blocks.clone();
        this.cumulative = new int[blocks.length];
        int t = 0;
        for (int i = 0; i < blocks.length; i++) {
            t += Math.max(0, weights[i]);
            cumulative[i] = t;
        }
        if (t <= 0) throw new IllegalArgumentException("palette weights sum to 0");
        this.total = t;
        long s = 0;
        for (String b : blocks) s = s * 31 + Hash.of(b);
        this.salt = Hash.mix(s);
    }

    public static Palette of(String spec) {
        List<String> parts = splitTop(spec);
        String[] b = new String[parts.size()];
        int[] w = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i).trim();
            int weight = 1;
            int bracket = p.lastIndexOf(']');
            int colon = p.lastIndexOf(':');
            if (colon > bracket && colon < p.length() - 1) {
                String tail = p.substring(colon + 1).trim();
                if (tail.chars().allMatch(Character::isDigit)) {
                    weight = Integer.parseInt(tail);
                    p = p.substring(0, colon).trim();
                }
            }
            b[i] = BlockState.normalise(p);
            w[i] = weight;
        }
        return new Palette(b, w);
    }

    private static List<String> splitTop(String spec) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == ',' && depth == 0) {
                if (i > start) out.add(spec.substring(start, i));
                start = i + 1;
            }
        }
        if (start < spec.length()) out.add(spec.substring(start));
        return out;
    }

    private String byRoll(long roll) {
        int r = (int) Math.floorMod(roll, (long) total);
        for (int i = 0; i < cumulative.length; i++) if (r < cumulative[i]) return blocks[i];
        return blocks[blocks.length - 1];
    }

    /** Deterministic pick for a position. */
    @Override
    public String at(int x, int y, int z) { return byRoll(Hash.hash(salt, x, y, z) >>> 1); }

    /** Deterministic pick for a position under an extra seed (so two walls of one palette differ). */
    public String pick(long seed, int x, int y, int z) { return byRoll(Hash.hash(salt ^ seed, x, y, z) >>> 1); }

    public String pick(java.util.Random r) { return byRoll(r.nextInt(total)); }

    public String first() { return blocks[0]; }

    public int size() { return blocks.length; }

    public String block(int i) { return blocks[i]; }
}
