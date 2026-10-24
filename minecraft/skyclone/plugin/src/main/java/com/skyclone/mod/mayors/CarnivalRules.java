package com.skyclone.mod.mayors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Carnival's arithmetic, pure: tokens for a score, the Party set's Carnival Craze, goal progress and Carnival Perk
 * tiers. The live classes only ask these questions, so the JUnit test can check every number without a server.
 */
final class CarnivalRules {

    private CarnivalRules() {}

    /** The three booths, as the goals and the stats name them. */
    enum Game {
        FRUIT("Fruit Digging", "&6"), SHOOT("Zombie Shootout", "&c"), FISH("Catch a Fish", "&3");
        final String title, color;
        Game(String title, String color) { this.title = title; this.color = color; }
        static Game of(String s) {
            for (Game g : values()) if (g.name().equalsIgnoreCase(s)) return g;
            return null;
        }
    }

    /** Party set "Carnival Craze": +4% tokens per piece worn (5 pieces, +20%). */
    static final double CRAZE_PER_PIECE = 0.04;
    static final String[] PARTY_PIECES = {"PARTY_HAT", "PARTY_NECKLACE", "PARTY_CLOAK", "PARTY_BELT", "PARTY_GLOVES"};

    /** Reference: 1 token per 100 points, rounded down; Carnival Craze multiplies it. */
    static long tokens(int score, double perPoint, int partyPieces) {
        double base = Math.floor(Math.max(0, score) * perPoint + 1e-9);
        return (long) Math.floor(base * (1 + CRAZE_PER_PIECE * Math.max(0, Math.min(5, partyPieces))) + 1e-9);
    }

    /** The single-game stat a goal reads ({@code plays} is counted across games, not here). */
    static int stat(String kind, Map<String, Integer> game) {
        Integer v = game.get(kind);
        return v == null ? 0 : v;
    }

    /**
     * Goals newly met by a finished game: {@code game} is FRUIT/SHOOT/FISH, {@code stats} that game's numbers,
     * {@code plays} how many games of it this Carnival (this one included), {@code done} goals already paid.
     */
    static List<EventData.Goal> newlyMet(List<EventData.Goal> goals, String game, Map<String, Integer> stats, int plays, List<String> done) {
        List<EventData.Goal> out = new ArrayList<>();
        for (EventData.Goal g : goals) {
            if (!g.game.equalsIgnoreCase(game) || done.contains(g.id)) continue;
            int have = "plays".equals(g.kind) ? plays : stat(g.kind, stats);
            if (have >= g.target) out.add(g);
        }
        return out;
    }

    /** Tier 0..max of a perk the player owns, clamped to the perk's tiers. */
    static int tier(EventData.Perk perk, Map<String, Integer> owned) {
        Integer t = owned == null ? null : owned.get(perk.id);
        return t == null ? 0 : Math.max(0, Math.min(perk.costs.size(), t));
    }

    /** What the perk is worth at {@code tier} (e.g. Candy Scavenger 3 = 6 [%]). */
    static double value(EventData.Perk perk, int tier) { return perk == null ? 0 : perk.perTier * Math.max(0, tier); }

    /** Tokens for the next tier, or -1 when maxed. */
    static long nextCost(EventData.Perk perk, int tier) { return tier >= perk.costs.size() ? -1 : perk.costs.get(tier); }
}
