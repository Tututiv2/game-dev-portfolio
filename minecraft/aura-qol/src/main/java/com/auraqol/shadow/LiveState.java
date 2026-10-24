package com.auraqol.shadow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.auraqol.skyblock.ProgressRate;
import com.auraqol.skyblock.RateTracker;

/**
 * Everything one connection to a Shadow Prison server has told this client,
 * plus every number the panels draw. Pure: clocks are passed in, nothing here
 * touches Minecraft, so the whole model is unit-tested.
 *
 * <p><b>Two active clocks, on purpose.</b> Both are {@link RateTracker}s (AFK
 * gaps are cut out of the time, retroactively, exactly like the Hypixel money
 * tracker):
 * <ul>
 *   <li>the <b>mining</b> clock runs on breaks, skill XP and the player moving -
 *       walk away and blocks/hour freezes instead of decaying;</li>
 *   <li>the <b>earnings</b> clock runs on all of that AND on income. Shadow
 *       Prison pays while you are away (crypto rigs), and that money is real:
 *       if it did not move the clock, an hour of AFK rig payouts would pile up
 *       at one frozen instant and the "last 10 minutes" would claim an hour's
 *       money when you came back.</li>
 * </ul>
 *
 * <p><b>Money per hour counts what you MINE, the moment it is in your inventory</b>
 * (owner, 2026-10-13: "tracking every block i get and its value"). The server
 * measures your inventory's sell value, so compacting, Fortune, enchant drops
 * that go straight to the inventory and custom ores all count, and BREAKS
 * carries that gain. It goes into the earnings ledger as the "Mined" row, next
 * to every other payout (crypto, enchants, quests...). The {@code selling}
 * payout is NOT added: /sellall only turns ore that was already counted as
 * "Mined" into cash, so adding it would count every block twice.
 *
 * <p><b>SNAPSHOT is state, not events.</b> A snapshot may carry a BREAKS or
 * INCOME frame; those are applied to nothing that counts (no blocks, no money),
 * because a snapshot is re-sent on every world change and counting it would
 * count the same payout twice. Every state frame in it (balance, rank, boosts,
 * skills, contract, area) is applied normally, and a skill's XP in a snapshot
 * re-bases it without counting as XP gained.
 */
public final class LiveState {

    /** Below this much active time there is no rate (one payout would extrapolate to billions). */
    public static final long MIN_RATE_MS = 15_000;
    /** The longest window offered is 30 minutes; keep a little more. */
    public static final long KEEP_MS = 31 * 60_000L;
    /** The skill panel follows the skill that last gained XP within this much real time. */
    public static final long TRAINING_MS = 10 * 60_000L;
    /** Most rows the earnings panel lists; the rest are summed into one "Other" row. */
    public static final int MAX_ROWS = 6;
    /** The earnings row for value mined into the inventory. */
    public static final String MINED = "mined";
    /** /sellall's payout id: conversion of already-counted ore, never added to money per hour. */
    public static final String SELLING = "selling";
    /** The earnings row for value gained while fighting (mob drops into the inventory). */
    public static final String COMBAT = "combat";

    public record Earning(String label, double centsPerHour) {
    }

    public record BoostView(String name, String multiplierText, long remainingMs, boolean permanent) {
    }

    public record SkillView(String displayName, long level, long xpInLevel, long xpForLevel, double xpPerHour,
                            long etaMs) {
        public boolean maxed() {
            return xpForLevel <= 0;
        }
    }

    private static final class SkillState {
        String displayName;
        long level;
        long xpIn;
        long xpFor;
    }

    // ---- handshake
    private boolean welcomed;
    private boolean supported;
    private long protocol;
    private Set<String> features = Set.of();
    private long flushMs;
    private long lookupsPerMinute;
    private final ServerClock clock = new ServerClock();

    // ---- clocks and ledgers
    private final long idleMs;
    private RateTracker mining;
    private RateTracker earning;
    private final ActiveLedger blocks = new ActiveLedger(KEEP_MS, 8192);
    private final ActiveLedger value = new ActiveLedger(KEEP_MS, 8192);
    private final ActiveLedger income = new ActiveLedger(KEEP_MS, 16384);
    private final ActiveLedger xp = new ActiveLedger(KEEP_MS, 8192);
    private final ActiveLedger kills = new ActiveLedger(KEEP_MS, 8192);
    private final ActiveLedger combatValue = new ActiveLedger(KEEP_MS, 8192);
    private boolean seenCombat;
    /** Wall time of the last BREAKS / COMBAT that had something in it: whichever is later is the activity. */
    private long lastMiningAt = Long.MIN_VALUE;
    private long lastCombatAt = Long.MIN_VALUE;
    private final Map<String, String> sourceNames = new HashMap<>();
    private boolean seenBreaks;
    private boolean seenIncome;
    private long soldCents;

    // ---- state as last sent
    private Msg.Balance balance;
    private Msg.Progress progress;
    private List<Msg.Boost> boosts;
    private Msg.Contract contract;
    private final ProgressRate contractRate = new ProgressRate(120_000, 8_000);
    private final Map<String, SkillState> skills = new HashMap<>();
    private String lastSkill;
    private String trainingSkill;
    private long trainingAt = Long.MIN_VALUE;
    private String area;

    public LiveState(long idleMs) {
        this.idleMs = idleMs;
        this.mining = new RateTracker(idleMs);
        this.earning = new RateTracker(idleMs);
    }

    // ------------------------------------------------------------ handshake

    public void welcome(Msg.Welcome w, long localWallMs) {
        welcomed = true;
        supported = w.supported();
        protocol = w.protocol();
        features = Set.copyOf(new LinkedHashSet<>(w.features()));
        flushMs = w.flushMs();
        lookupsPerMinute = w.lookupsPerMinute();
        clock.sync(w.serverTimeMs(), localWallMs);
    }

    public boolean welcomed() {
        return welcomed;
    }

    /** WELCOME arrived and said this client's protocol is supported. */
    public boolean active() {
        return welcomed && supported;
    }

    public boolean supported() {
        return supported;
    }

    public long protocol() {
        return protocol;
    }

    public Set<String> features() {
        return features;
    }

    /** The server streams this feature ({@code breaks}, {@code income}, ...). */
    public boolean has(String feature) {
        return active() && features.contains(feature);
    }

    public long flushMs() {
        return flushMs;
    }

    public long lookupsPerMinute() {
        return lookupsPerMinute;
    }

    public ServerClock clock() {
        return clock;
    }

    // ------------------------------------------------------------ input

    /**
     * Apply one server frame. WELCOME and RESPONSE are handled by the caller;
     * everything else lands here. Ignored before an accepted WELCOME.
     */
    public void apply(Msg m, long now) {
        if (!active()) {
            return;
        }
        apply(m, now, false);
    }

    private void apply(Msg m, long now, boolean inSnapshot) {
        switch (m) {
            case Msg.Snapshot s -> {
                if (!inSnapshot) {
                    for (Msg f : s.frames()) {
                        apply(f, now, true);
                    }
                }
            }
            case Msg.Breaks b -> {
                if (inSnapshot) {
                    return;
                }
                seenBreaks = true;
                if (b.count() > 0) {
                    lastMiningAt = now;
                }
                if (b.count() > 0 || b.valueCents() != 0) {
                    mining.activity(now);
                    earning.activity(now);
                    long at = mining.activeMs(now);
                    blocks.add(at, "blocks", b.count());
                    value.add(at, "value", b.valueCents());
                    if (b.valueCents() != 0) {
                        seenIncome = true;
                        sourceNames.put(MINED, "Mined");
                        income.add(earning.activeMs(now), MINED, b.valueCents());
                    }
                }
            }
            case Msg.Income in -> {
                if (inSnapshot) {
                    return;
                }
                String id = in.sourceId() == null || in.sourceId().isEmpty() ? "other" : in.sourceId();
                if (SELLING.equals(id)) {
                    soldCents += in.amountCents();   // shown as "sold", never added (it is already "Mined")
                    return;
                }
                seenIncome = true;
                if (in.displayName() != null && !in.displayName().isEmpty()) {
                    sourceNames.put(id, in.displayName());
                }
                if (in.amountCents() != 0) {
                    earning.activity(now);
                    income.add(earning.activeMs(now), id, in.amountCents());
                }
            }
            case Msg.Combat c -> {
                if (inSnapshot) {
                    return;
                }
                seenCombat = true;
                if (c.kills() > 0) {
                    lastCombatAt = now;
                }
                if (c.kills() > 0 || c.valueCents() != 0) {
                    mining.activity(now);
                    earning.activity(now);
                    long at = mining.activeMs(now);
                    kills.add(at, "kills", c.kills());
                    combatValue.add(at, "value", c.valueCents());
                    if (c.valueCents() != 0) {
                        seenIncome = true;
                        sourceNames.put(COMBAT, "Combat");
                        income.add(earning.activeMs(now), COMBAT, c.valueCents());
                    }
                }
            }
            case Msg.Balance b -> balance = b;
            case Msg.Progress p -> progress = p;
            case Msg.Boosts b -> boosts = List.copyOf(b.boosts());
            case Msg.Skill s -> skill(s, now, inSnapshot);
            case Msg.Contract c -> contract(c, now);
            case Msg.Area a -> area = a.name() == null ? "" : a.name();
            default -> {
                // WELCOME / RESPONSE / unknown: not state
            }
        }
    }

    private void skill(Msg.Skill s, long now, boolean inSnapshot) {
        String id = s.skillId() == null ? "" : s.skillId();
        SkillState st = skills.get(id);
        long gain = 0;
        if (st != null && !inSnapshot) {
            if (s.level() == st.level) {
                gain = s.xpInLevel() - st.xpIn;
            } else if (s.level() > st.level) {
                // Level-up: the rest of the old level plus what is in the new one.
                // (Several levels at once: the intermediate levels' totals were never
                // sent, so this counts only the two ends - an under-count, never an over-count.)
                gain = Math.max(0, st.xpFor - st.xpIn) + Math.max(0, s.xpInLevel());
            }
        }
        if (st == null) {
            st = new SkillState();
            skills.put(id, st);
        }
        st.displayName = s.displayName() == null || s.displayName().isEmpty() ? id : s.displayName();
        st.level = s.level();
        st.xpIn = s.xpInLevel();
        st.xpFor = s.xpForLevel();
        lastSkill = id;
        if (gain > 0) {
            mining.activity(now);
            earning.activity(now);
            xp.add(mining.activeMs(now), id, gain);
            trainingSkill = id;
            trainingAt = now;
        }
    }

    private void contract(Msg.Contract c, long now) {
        contract = c;
        if (c.state() == Msg.Contract.HUNTING) {
            contractRate.sample(now, c.target() + "|" + c.tier(), c.progressPermille(), 1000);
        } else if (c.state() == Msg.Contract.NONE) {
            contractRate.clear();
        }
    }

    /** The player did something on purpose (moved, looked around, broke a block). */
    public void activity(long now) {
        mining.activity(now);
        earning.activity(now);
    }

    /** {@code /sp reset}: rates and session totals start over; what the server last said stays. */
    public void resetSession() {
        mining = new RateTracker(idleMs);
        earning = new RateTracker(idleMs);
        blocks.clear();
        value.clear();
        income.clear();
        xp.clear();
        kills.clear();
        combatValue.clear();
        soldCents = 0;
        trainingSkill = null;
        trainingAt = Long.MIN_VALUE;
        contractRate.clear();
    }

    public void prune(long now) {
        blocks.prune(mining.activeMs(now));
        value.prune(mining.activeMs(now));
        xp.prune(mining.activeMs(now));
        kills.prune(mining.activeMs(now));
        combatValue.prune(mining.activeMs(now));
        income.prune(earning.activeMs(now));
    }

    // ------------------------------------------------------------ activity

    /** "Combat" if the last thing with something in it was a kill, otherwise "Mining". */
    public String activity() {
        return lastCombatAt != Long.MIN_VALUE && lastCombatAt >= lastMiningAt ? "Combat" : "Mining";
    }

    public boolean seenCombat() {
        return seenCombat;
    }

    public double killsPerHour(long now, boolean recent, long windowMs) {
        return rate(kills, mining, now, recent, windowMs);
    }

    public double combatValueCentsPerHour(long now, boolean recent, long windowMs) {
        return rate(combatValue, mining, now, recent, windowMs);
    }

    public long sessionKills() {
        return Math.round(kills.sessionTotal());
    }

    public long sessionCombatValueCents() {
        return Math.round(combatValue.sessionTotal());
    }

    // ------------------------------------------------------------ mining

    public RateTracker miningClock() {
        return mining;
    }

    public RateTracker earningClock() {
        return earning;
    }

    public boolean seenBreaks() {
        return seenBreaks;
    }

    public boolean miningHasRate(long now) {
        return mining.activeMs(now) >= MIN_RATE_MS;
    }

    public double blocksPerHour(long now, boolean recent, long windowMs) {
        return rate(blocks, mining, now, recent, windowMs);
    }

    /** Estimated value mined per hour, in cents (realised only on sell). */
    public double valueCentsPerHour(long now, boolean recent, long windowMs) {
        return rate(value, mining, now, recent, windowMs);
    }

    public long sessionBlocks() {
        return Math.round(blocks.sessionTotal());
    }

    public long sessionValueCents() {
        return Math.round(value.sessionTotal());
    }

    private static double rate(ActiveLedger l, RateTracker clock, long now, boolean recent, long windowMs) {
        long active = clock.activeMs(now);
        if (active < MIN_RATE_MS) {
            return 0;
        }
        if (recent) {
            return l.perHour(active, windowMs, MIN_RATE_MS);
        }
        return l.sessionTotal() * 3_600_000.0 / active;
    }

    // ------------------------------------------------------------ earnings

    public boolean seenIncome() {
        return seenIncome;
    }

    public boolean earningHasRate(long now) {
        return earning.activeMs(now) >= MIN_RATE_MS;
    }

    /** /sellall payouts this session (cents). Information only - already inside "Mined". */
    public long sessionSoldCents() {
        return soldCents;
    }

    /** Money per hour, in cents: value mined into the inventory + every payout except selling. */
    public double incomeCentsPerHour(long now, boolean recent, long windowMs) {
        return rate(income, earning, now, recent, windowMs);
    }

    public long sessionIncomeCents() {
        return Math.round(income.sessionTotal());
    }

    /**
     * Per-source cents/hour in the same mode as the headline. They ADD UP to
     * {@link #incomeCentsPerHour}: biggest first, and past {@link #MAX_ROWS}
     * the smallest are folded into one "Other" row rather than dropped.
     */
    public List<Earning> incomeRows(long now, boolean recent, long windowMs) {
        long active = earning.activeMs(now);
        if (active < MIN_RATE_MS) {
            return List.of();
        }
        Map<String, Double> by = recent ? income.byKey(active, windowMs) : income.sessionByKey();
        double span = recent ? Math.max(1, Math.min(windowMs, active)) : active;
        List<Earning> rows = new ArrayList<>();
        for (Map.Entry<String, Double> e : by.entrySet()) {
            double ph = e.getValue() * 3_600_000.0 / span;
            if (ph != 0) {
                rows.add(new Earning(sourceNames.getOrDefault(e.getKey(), e.getKey()), ph));
            }
        }
        rows.sort(Comparator.comparingDouble((Earning r) -> -Math.abs(r.centsPerHour())));
        if (rows.size() <= MAX_ROWS) {
            return rows;
        }
        double rest = 0;
        for (int i = MAX_ROWS - 1; i < rows.size(); i++) {
            rest += rows.get(i).centsPerHour();
        }
        List<Earning> out = new ArrayList<>(rows.subList(0, MAX_ROWS - 1));
        out.add(new Earning("Other", rest));
        return out;
    }

    // ------------------------------------------------------------ rank-up

    public Msg.Balance balance() {
        return balance;
    }

    public Msg.Progress progress() {
        return progress;
    }

    public RankEta.Result rankEta(long now, boolean recent, long windowMs) {
        if (progress == null || balance == null) {
            return new RankEta.Result(RankEta.Kind.HIDDEN, -1);
        }
        long cost = progress.maxRank() ? -1 : progress.nextRankCostCents();
        double rate = earningHasRate(now) ? incomeCentsPerHour(now, recent, windowMs) : 0;
        return RankEta.of(balance.balanceCents(), cost, rate);
    }

    // ------------------------------------------------------------ boosts

    public boolean seenBoosts() {
        return boosts != null;
    }

    /** Active boosts with their local countdown; expired ones are already gone. */
    public List<BoostView> boosts(long localWallMs) {
        if (boosts == null) {
            return List.of();
        }
        List<BoostView> out = new ArrayList<>(boosts.size());
        for (Msg.Boost b : boosts) {
            boolean permanent = b.expiresAtMs() == 0;
            long left = clock.remaining(b.expiresAtMs(), localWallMs);
            if (!permanent && left <= 0) {
                continue;
            }
            out.add(new BoostView(b.name(), b.multiplierText(), permanent ? -1 : left, permanent));
        }
        return out;
    }

    // ------------------------------------------------------------ contract

    public Msg.Contract contract() {
        return contract;
    }

    /** Milliseconds until the bar fills at the recent pace, or -1 if unknown. */
    public long contractEtaMs(long now) {
        if (contract == null || contract.state() != Msg.Contract.HUNTING) {
            return -1;
        }
        return contractRate.etaMs(now);
    }

    // ------------------------------------------------------------ skills

    public boolean seenSkills() {
        return lastSkill != null;
    }

    /**
     * The skill you are training: the one that last gained XP in the last
     * {@link #TRAINING_MS}, else the last one the server sent. Null before any.
     */
    public SkillView training(long now, boolean recent, long windowMs) {
        String id = trainingSkill != null && now - trainingAt <= TRAINING_MS ? trainingSkill : lastSkill;
        if (id == null) {
            return null;
        }
        SkillState st = skills.get(id);
        if (st == null) {
            return null;
        }
        double perHour = 0;
        long active = mining.activeMs(now);
        if (active >= MIN_RATE_MS) {
            if (recent) {
                double sum = xp.byKey(active, windowMs).getOrDefault(id, 0.0);
                perHour = sum * 3_600_000.0 / Math.max(1, Math.min(windowMs, active));
            } else {
                perHour = xp.sessionByKey().getOrDefault(id, 0.0) * 3_600_000.0 / active;
            }
        }
        long eta = -1;
        if (st.xpFor > 0 && perHour > 0) {
            double left = Math.max(0, st.xpFor - st.xpIn);
            eta = Math.round(left / perHour * 3_600_000.0);
        }
        return new SkillView(st.displayName, st.level, st.xpIn, st.xpFor, perHour, eta);
    }

    // ------------------------------------------------------------ area

    /** Null before any AREA, "" when not in a named area. */
    public String area() {
        return area;
    }
}
