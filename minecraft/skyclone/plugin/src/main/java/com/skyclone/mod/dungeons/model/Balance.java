package com.skyclone.mod.dungeons.model;

/**
 * How hard a Catacombs hit lands on a player, with the same arithmetic the core applies to a mob or scripted hit
 * ({@code floor(raw x 100 / (100 + Defense))}, core {@code DamageMath.reduce}). Pure, so the shipping numbers can be
 * checked offline ({@code BalanceTest}) and printed live ({@code /sb dungeons balance <floor> [hp] [def]}).
 *
 * <p>Where each raw number comes from (all TUNE, all dials read at use time):
 * <ul>
 *   <li>room / blood / miniboss mob: {@code MobDef.damage x floor.dmgScale x dungeons.mob-damage-scale}</li>
 *   <li>boss body (contact hit): {@code floor.bossDamage x dungeons.boss-melee-share x dungeons.boss-damage-scale}</li>
 *   <li>scripted boss attack: {@code floor.bossDamage x factor x dungeons.boss-damage-scale} (factor per fight)</li>
 *   <li>boss add (Circus Undead, guardians...): contact hit {@code its own number x dungeons.boss-add-melee-share x
 *       dungeons.boss-damage-scale}; its scripted attacks use its full number</li>
 *   <li>every boss-floor number above, health too, then x {@link #partyScale} for runs smaller than five</li>
 * </ul>
 * Hits-to-kill alone is not enough for a duel: see {@link #margin} (playtest-endgame).
 */
public final class Balance {

    private Balance() {}

    /**
     * A reference player for the balance checks. {@code dps} is the damage per second they put into one target (0 =
     * unknown: only "how long do I last" can be answered, not "who wins").
     */
    public record Ref(String label, double health, double defense, double dps) {
        public Ref(String label, double health, double defense) { this(label, health, defense, 0); }
        public double ehp() { return health * (1 + Math.max(0, defense) / 100.0); }
    }

    /**
     * The integration pass's test character: Superior-Dragon-class gear (measured on the stat sheet). Its 2,000 DPS is
     * below what the endgame playtest measured with comparable gear (a Superior Dragon set + Voidstep Blade took a
     * 40,000 HP miniboss down in ~17 s, about 2,350/s), so a fight that passes against it passes with margin.
     */
    public static final Ref MID = new Ref("mid-game (588 HP / 630 Def)", 588, 630, 2_000);
    /** A player the floor's entry requirements describe (Catacombs ~5, early-mid armour). Reported, not asserted. */
    public static final Ref ENTRY = new Ref("F1 entry (500 HP / 250 Def)", 500, 250, 800);

    // ---- time, not hits (playtest-endgame) ---------------------------------------------------------------------
    //
    // "19 hits to kill you" read safe and was not: the F1 miniboss swung every second plus a lightning every 6 s and
    // won a DPS race against a 1,079 HP / 678 Defense player. A duel is decided by RATE, so the checks below compare
    // seconds: how long the player lasts against how long the mob lasts.

    /** Seconds between a mob's melee swings (vanilla's 20-tick attack cooldown; sky mobs keep it). */
    public static final double MELEE_EVERY = 1.0;

    /**
     * A miniboss's attack pattern: melee every {@code meleeEvery} s, plus a special of {@code specialFactor} x its
     * damage every {@code specialEvery} s, and it heals {@code regenFrac} of its max health every {@code regenEvery} s.
     * The live behaviours (dungeons {@code Behaviours}) read these same numbers, so the test checks what ships.
     */
    public record Pattern(double meleeEvery, double specialFactor, double specialEvery, double regenFrac, double regenEvery) {
        public double specialMs() { return specialEvery * 1000; }
        public double regenMs() { return regenEvery * 1000; }
    }

    public static final Pattern PLAIN = new Pattern(MELEE_EVERY, 0, 0, 0, 0);

    /** The pattern of a miniboss behaviour id (TUNE: cadences and factors are not in the research). */
    public static Pattern pattern(String behaviour) {
        if (behaviour == null) return PLAIN;
        return switch (behaviour) {
            case "dungeons_explorer" -> new Pattern(MELEE_EVERY, 1.2, 6, 0, 0);      // lightning
            case "dungeons_monarch" -> new Pattern(MELEE_EVERY, 0, 0, 0.02, 5);       // regenerates
            case "dungeons_excavator" -> new Pattern(MELEE_EVERY, 0, 0, 0.03, 5);     // healing potion (+ slowness)
            case "dungeons_shade" -> new Pattern(MELEE_EVERY, 1.5, 5, 0, 0);          // strike from behind
            case "dungeons_frost" -> new Pattern(MELEE_EVERY, 1.0, 6, 0, 0);          // ice beam (+ slowness)
            default -> PLAIN;
        };
    }

    /** Health per second a mob with this pattern takes off the player, after Defense. */
    public static double landedPerSecond(double raw, Pattern pt, double defense) {
        double s = landed(raw, defense) / Math.max(0.05, pt.meleeEvery());
        if (pt.specialFactor() > 0 && pt.specialEvery() > 0) s += landed(raw * pt.specialFactor(), defense) / pt.specialEvery();
        return s;
    }

    /** Seconds a full-health player lasts against it alone (no healing counted: the safe side). */
    public static double secondsToDie(double raw, Pattern pt, Ref p) {
        double s = landedPerSecond(raw, pt, p.defense());
        return s <= 0 ? Double.POSITIVE_INFINITY : Math.max(1, p.health()) / s;
    }

    /** Seconds the player needs to kill it (its regeneration counted); infinite when it out-heals the player. */
    public static double secondsToKill(double mobHp, Pattern pt, Ref p) {
        double heal = pt.regenFrac() > 0 && pt.regenEvery() > 0 ? mobHp * pt.regenFrac() / pt.regenEvery() : 0;
        double net = p.dps() - heal;
        return net <= 0 ? Double.POSITIVE_INFINITY : mobHp / net;
    }

    /** How many times over the player outlasts the mob: 2 = the mob dies with the player at half health. */
    public static double margin(double mobHp, double raw, Pattern pt, Ref p) {
        double kill = secondsToKill(mobHp, pt, p);
        return Double.isInfinite(kill) ? 0 : secondsToDie(raw, pt, p) / kill;
    }

    /** What a raw hit takes off the player after Defense. */
    public static double landed(double raw, double defense) {
        return Math.floor(Math.max(0, raw) * 100.0 / (100.0 + Math.max(0, defense)));
    }

    /** Hits of this size that kill a full-health player (Integer.MAX_VALUE when it lands for 0). */
    public static int hitsToKill(double raw, double health, double defense) {
        double l = landed(raw, defense);
        return l <= 0 ? Integer.MAX_VALUE : (int) Math.ceil(Math.max(1, health) / l);
    }

    public static int hitsToKill(double raw, Ref p) { return hitsToKill(raw, p.health(), p.defense()); }

    public static double roomMob(double mobDamage, double floorDmgScale, double mobDamageDial) {
        return mobDamage * floorDmgScale * mobDamageDial;
    }

    public static double bossBody(double floorBossDamage, double meleeShare, double bossDamageDial) {
        return floorBossDamage * Math.max(0, meleeShare) * bossDamageDial;
    }

    public static double scripted(double floorBossDamage, double factor, double bossDamageDial) {
        return floorBossDamage * factor * bossDamageDial;
    }

    public static double add(double addDamage, double bossDamageDial) {
        return addDamage * bossDamageDial;
    }

    /** A boss add's contact hit: its research figure x {@code dungeons.boss-add-melee-share} x the damage dial. */
    public static double addContact(double addDamage, double addMeleeShare, double bossDamageDial) {
        return addDamage * Math.max(0, addMeleeShare) * bossDamageDial;
    }

    // ---- party size (wave-4 endgame) ----------------------------------------------------------------------------
    //
    // Boss floors are party content: research HP and every add on one target assume five players. Solo, a Superior
    // Dragon player (983 HP / 741 Defense) died on F2 in ~10 s three times and F3 took 30% of his health within 2 s of
    // arriving. The boss floor's health and damage therefore scale with the run's member count.

    /** Largest party (research 04 sec 3.1); the party a boss floor is sized for. */
    public static final int FULL_PARTY = 5;

    /**
     * The multiplier for a party of {@code members}: {@code solo} with one member, rising linearly to 1.0 at
     * {@link #FULL_PARTY}. A solo value above 1 is allowed (a harder solo), a negative one is treated as 0.
     */
    public static double partyScale(int members, double solo) {
        int n = Math.max(1, Math.min(FULL_PARTY, members));
        double s = Math.max(0, solo);
        return s + (1 - s) * (n - 1) / (double) (FULL_PARTY - 1);
    }

    /**
     * How long a fallen Lecturer guardian stays down before it rises again (F3: "all four within 10 seconds"). The
     * window is sized for a full party splitting up across the four pedestals; a smaller party kills them one after
     * another, so it grows with (5 / members)^2: 10 s at five, ~16 s at four, ~62 s at two, 250 s solo.
     */
    public static double guardianWindowSeconds(int members, double fullPartySeconds) {
        int n = Math.max(1, Math.min(FULL_PARTY, members));
        double r = FULL_PARTY / (double) n;
        return fullPartySeconds * r * r;
    }

    // ---- room packs (wave-5 regression) ------------------------------------------------------------------------
    //
    // A room is not one duel: its mobs swing together and die one at a time, so the damage a fight costs is every mob's
    // rate times how long IT stays alive. Killing n alike mobs in turn costs landed x (hp/dps) x (1 + 2 + ... + n): the
    // cost grows with the square of how many engage at once, which is why the aggro range matters as much as the numbers.

    /**
     * The wave-5 regression character: Superior Dragon (683 HP / 644 Defense) with an Undead Sword (Smite V). Its 320
     * DPS is measured, not guessed: the same player killed a 20,000 HP Ghoul Horror II in 63 s with that sword.
     */
    public static final Ref EARLY = new Ref("solo Superior Dragon + Undead Sword (683 HP / 644 Def, 320 DPS)", 683, 644, 20_000 / 63.0);

    /**
     * Health a player loses killing these mobs in the order given while all of them swing at him (melee every
     * {@link #MELEE_EVERY} s, no healing counted: the safe side). Infinite when the player deals no damage.
     */
    public static double packTaken(double[] raw, double[] hp, Ref p) {
        if (p.dps() <= 0) return Double.POSITIVE_INFINITY;
        double t = 0, taken = 0;
        for (int i = 0; i < raw.length; i++) {
            t += Math.max(0, hp[i]) / p.dps();
            taken += landed(raw[i], p.defense()) / MELEE_EVERY * t;
        }
        return taken;
    }

    /** {@link #packTaken(double[], double[], Ref)} for {@code n} alike mobs. */
    public static double packTaken(int n, double raw, double hp, Ref p) {
        double[] r = new double[Math.max(0, n)], h = new double[r.length];
        java.util.Arrays.fill(r, raw);
        java.util.Arrays.fill(h, hp);
        return packTaken(r, h, p);
    }
}
