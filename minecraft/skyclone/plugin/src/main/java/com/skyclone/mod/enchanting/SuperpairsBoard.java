package com.skyclone.mod.enchanting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Superpairs as a pure state machine (research 08 §3.4): face-down tiles, each click costs one click,
 * two revealed tiles with the SAME reward stay matched and the reward is won; a mismatch flips back.
 * Special single tiles: "+N clicks" and "Instant Find" (claims a random unfound pair).
 * Matching is by reward identity (item + amount), so two identical-looking tiles always match.
 */
public final class SuperpairsBoard {

    public enum Kind { REWARD, CLICKS, INSTANT }

    public enum Outcome { IGNORED, FIRST, MATCH, MISMATCH, BONUS_CLICKS, INSTANT_FIND }

    public static final class Tile {
        public final Kind kind;
        public final ExperimentSpec.Reward reward;   // REWARD only
        public final int clicks;                     // CLICKS only
        public boolean revealed;
        public boolean matched;

        Tile(Kind kind, ExperimentSpec.Reward reward, int clicks) { this.kind = kind; this.reward = reward; this.clicks = clicks; }

        public String key() { return reward == null ? kind.name() : reward.key(); }
    }

    public final List<Tile> tiles;
    public final List<ExperimentSpec.Reward> won = new ArrayList<>();
    public int clicksLeft;
    public int clicksUsed;
    private int open = -1;
    /** A mismatched pair still showing; flipped back on the next click or by the controller's timer. */
    public int pendingA = -1;
    public int pendingB = -1;

    private SuperpairsBoard(List<Tile> tiles, int clicks) { this.tiles = tiles; this.clicksLeft = clicks; }

    public static SuperpairsBoard deal(ExperimentSpec.Tier t, int clicks, Random rnd) { return deal(t, clicks, rnd, 0); }

    /** {@code ultraRareBoostPct}: Guardian pet's Lucky Seven (+% chance for ultra rare books). */
    public static SuperpairsBoard deal(ExperimentSpec.Tier t, int clicks, Random rnd, double ultraRareBoostPct) {
        return deal(t, clicks, rnd, ultraRareBoostPct, List.of(), 1.0);
    }

    /**
     * Deal a board. With probability {@code t.ultraRareChance}% x (1 + boost/100) x {@code chanceMultiplier}
     * (Supreme and up), ONE pair is replaced by an Endcap Upgrade picked uniformly from {@code ultraPool}
     * (wiki: "Ultra-Rare Items", 0.027% each at Metaphysical). The pool must hold only registered ids.
     */
    public static SuperpairsBoard deal(ExperimentSpec.Tier t, int clicks, Random rnd, double ultraRareBoostPct,
                                       List<String> ultraPool, double chanceMultiplier) {
        List<Tile> list = new ArrayList<>();
        int specials = Math.max(0, Math.min(t.specials, t.tiles()));
        if ((t.tiles() - specials) % 2 != 0) specials++;
        for (int i = 0; i < specials; i++) {
            if (i % 2 == 0) list.add(new Tile(Kind.CLICKS, null, 2 + rnd.nextInt(2)));
            else list.add(new Tile(Kind.INSTANT, null, 0));
        }
        int pairs = (t.tiles() - specials) / 2;
        ExperimentSpec.Reward ultra = rollUltraRare(t, rnd, ultraRareBoostPct, ultraPool, chanceMultiplier);
        for (int i = 0; i < pairs; i++) {
            ExperimentSpec.Reward r = i == 0 && ultra != null ? ultra : t.roll(rnd, ultraRareBoostPct);
            if (r == null) r = fallback();
            list.add(new Tile(Kind.REWARD, r, 0));
            list.add(new Tile(Kind.REWARD, r, 0));
        }
        Collections.shuffle(list, rnd);
        return new SuperpairsBoard(list, clicks);
    }

    /** Chance (0..1) that a game of this tier deals an Endcap Upgrade pair. */
    public static double ultraRareChance(ExperimentSpec.Tier t, double ultraRareBoostPct, double chanceMultiplier) {
        if (t == null || t.ultraRareChance <= 0) return 0;
        double c = t.ultraRareChance / 100.0 * (1 + Math.max(0, ultraRareBoostPct) / 100.0) * Math.max(0, chanceMultiplier);
        return Math.min(1, c);
    }

    static ExperimentSpec.Reward rollUltraRare(ExperimentSpec.Tier t, Random rnd, double boostPct, List<String> pool, double mult) {
        if (pool == null || pool.isEmpty()) return null;
        double c = ultraRareChance(t, boostPct, mult);
        if (c <= 0 || rnd.nextDouble() >= c) return null;
        ExperimentSpec.Reward r = new ExperimentSpec.Reward();
        r.item = pool.get(rnd.nextInt(pool.size()));
        r.amount = 1;
        r.weight = 0;
        return r;
    }

    private static ExperimentSpec.Reward fallback() {
        ExperimentSpec.Reward r = new ExperimentSpec.Reward();
        r.item = "EXP_BOTTLE";
        r.amount = 4;
        return r;
    }

    public boolean finished() { return clicksLeft <= 0 || allFound(); }

    public boolean allFound() {
        for (Tile t : tiles) if (t.kind == Kind.REWARD && !t.matched) return false;
        return true;
    }

    /** Flip a showing mismatch back face-down. */
    public void hidePending() {
        if (pendingA >= 0) tiles.get(pendingA).revealed = false;
        if (pendingB >= 0) tiles.get(pendingB).revealed = false;
        pendingA = pendingB = -1;
    }

    public Outcome click(int index, Random rnd) {
        if (finished() || index < 0 || index >= tiles.size()) return Outcome.IGNORED;
        if (index == pendingA || index == pendingB) return Outcome.IGNORED;
        hidePending();
        Tile t = tiles.get(index);
        if (t.matched || t.revealed) return Outcome.IGNORED;
        clicksLeft--;
        clicksUsed++;
        t.revealed = true;
        switch (t.kind) {
            case CLICKS -> {
                t.matched = true;
                clicksLeft += t.clicks;
                return Outcome.BONUS_CLICKS;
            }
            case INSTANT -> {
                t.matched = true;
                List<Integer> candidates = new ArrayList<>();
                for (int i = 0; i < tiles.size(); i++) {
                    Tile o = tiles.get(i);
                    if (o.kind == Kind.REWARD && !o.matched && i != open) candidates.add(i);
                }
                Collections.shuffle(candidates, rnd);
                for (int a : candidates) {
                    for (int b : candidates) {
                        if (a != b && tiles.get(a).key().equals(tiles.get(b).key())) {
                            claim(a, b);
                            return Outcome.INSTANT_FIND;
                        }
                    }
                }
                return Outcome.INSTANT_FIND;
            }
            default -> {
                if (open < 0) { open = index; return Outcome.FIRST; }
                Tile first = tiles.get(open);
                if (first.key().equals(t.key())) {
                    claim(open, index);
                    open = -1;
                    return Outcome.MATCH;
                }
                pendingA = open;
                pendingB = index;
                open = -1;
                return Outcome.MISMATCH;
            }
        }
    }

    private void claim(int a, int b) {
        Tile x = tiles.get(a), y = tiles.get(b);
        x.revealed = y.revealed = true;
        x.matched = y.matched = true;
        won.add(x.reward);
    }

    public int openIndex() { return open; }
}
