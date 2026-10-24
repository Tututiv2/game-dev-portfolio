package com.thestill.rank;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.economy.Economy;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.region.Region;
import com.thestill.state.StateKey;

import java.util.logging.Logger;

/**
 * Who is what rank, and what that lets them into.
 *
 * <p>Rank lives in the player's state bag and depth requirements live in the
 * region's, so neither needed a schema change and neither needs a permission
 * plugin. "Can this player enter this depth" is two integer reads.
 */
public final class RankService {

    /** The player's position on the ladder. */
    public static final StateKey<Integer> RANK_INDEX = StateKey.ofInt("rank.index", 0);

    /** Lowest rank index allowed into a depth. Set on the depth region. */
    public static final StateKey<Integer> REQUIRED_RANK = StateKey.ofInt("depth.required-rank", 0);

    /** Which depth this is — D1 is 1. Used for ordering and display. */
    public static final StateKey<Integer> DEPTH_NUMBER = StateKey.ofInt("depth.number", 0);

    private final Logger log;
    private final RankLadder ladder;
    private final Economy economy;
    private final Profiles profiles;
    private final Dial<Double> costScale;

    public RankService(Logger log, RankLadder ladder, Economy economy, Profiles profiles,
                       DialRegistry dials) {
        this.log = log;
        this.ladder = ladder;
        this.economy = economy;
        this.profiles = profiles;

        this.costScale = dials.doubleDial("rank.cost-scale", 0.08)
                .category("Ranks").label("Rank cost scale")
                .describe("Multiplies every rank cost. This is a PHASE 1 SCAFFOLD: the ladder is "
                        + "tuned for a game that has Efficiency, insta-mine, crates and prestige "
                        + "multipliers, and Phase 1 has none of them. At 1.0 a new player needs "
                        + "5h19m to reach D5, which is not a gate anyone can sit and watch. "
                        + "Set this back to 1.0 when Phase 7 lands the enchant ladder.")
                .range(0.01, 1.0).step(0.01)
                .register();
    }

    public RankLadder ladder() {
        return ladder;
    }

    /**
     * What this rank actually costs right now.
     *
     * <p>Everything that shows or charges a rank price goes through here rather
     * than reading {@link Rank#cost()} directly, so the scale cannot be applied
     * in one place and forgotten in another — which would show a player one price
     * and take a different one.
     *
     * <p>Scaling the whole ladder rather than rewriting its early rungs is
     * deliberate: the <em>relative</em> shape is the part that was tuned over
     * months, and it survives untouched. Only the absolute magnitude is wrong for
     * Phase 1, and it is wrong by a single factor.
     */
    public long costOf(Rank rank) {
        if (rank == null) {
            return 0L;
        }
        return Math.round(rank.cost() * costScale.get());
    }

    public double costScale() {
        return costScale.get();
    }

    public Rank rankOf(Profile profile) {
        return ladder.byIndex(profile.state().get(RANK_INDEX));
    }

    public Rank nextRank(Profile profile) {
        return ladder.next(rankOf(profile));
    }

    /**
     * Buys the next rank.
     *
     * <p>Withdraw first, then promote. If the withdrawal fails nothing else has
     * happened; doing it the other way round means a failed payment leaves a
     * promoted player, and that is the sort of thing one person finds and the
     * whole server is doing by the evening.
     */
    public Result rankup(Profile profile) {
        Rank current = rankOf(profile);
        Rank next = ladder.next(current);
        if (next == null) {
            return new Result(Status.AT_MAX, current, null, 0L, 0L);
        }
        long cost = costOf(next);
        if (!economy.canAfford(profile, cost)) {
            return new Result(Status.TOO_POOR, current, next, cost,
                    cost - profile.balance());
        }
        if (!economy.withdraw(profile, cost, "rankup to " + next.id())) {
            return new Result(Status.TOO_POOR, current, next, cost,
                    Math.max(0L, cost - profile.balance()));
        }
        profile.state().set(RANK_INDEX, next.index());
        profiles.saveDurable(profile);
        log.info(profile.name() + " ranked up to " + next.display());
        return new Result(Status.PROMOTED, current, next, cost, 0L);
    }

    /** Admin path: no cost, no checks beyond the ladder's bounds. */
    public Rank setRank(Profile profile, int index) {
        Rank rank = ladder.byIndex(index);
        profile.state().set(RANK_INDEX, rank.index());
        profiles.saveDurable(profile);
        return rank;
    }

    // ---- access ---------------------------------------------------------

    /** The rank a region demands. Zero — everyone — unless the region says otherwise. */
    public int requiredRank(Region region) {
        return region == null ? 0 : region.state().get(REQUIRED_RANK);
    }

    public boolean canAccess(Profile profile, Region region) {
        return rankOf(profile).index() >= requiredRank(region);
    }

    public Rank rankNeededFor(Region region) {
        return ladder.byIndex(requiredRank(region));
    }

    public record Result(Status status, Rank from, Rank to, long cost, long shortBy) {

        public boolean promoted() {
            return status == Status.PROMOTED;
        }
    }

    public enum Status {
        PROMOTED,
        AT_MAX,
        TOO_POOR
    }
}
