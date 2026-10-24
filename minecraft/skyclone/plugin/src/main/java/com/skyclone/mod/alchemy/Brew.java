package com.skyclone.mod.alchemy;

/**
 * The brewing rules, PURE (no Bukkit), so unit tests and self-tests run the exact logic the stand uses.
 *
 * <pre>
 *   Water Bottle + Nether Wart          -> Awkward Potion
 *   Awkward + effect ingredient          -> Potion of X at the ingredient's level
 *   Water + Fermented Spider Eye         -> Weakness (the one water-based recipe)
 *   Potion + Glowstone family            -> +1/+2/+3 levels (once, capped at the brewable max)
 *   Potion + Redstone family             -> +8/+16/+40 minutes (once; not instant or XP potions)
 *   Potion + Gunpowder / Ench. Gunpowder -> splash / wide splash
 *   Potion + Fermented Spider Eye        -> its corrupted twin (Speed -> Slowness...)
 * </pre>
 * Research 08 section 2.2.
 */
public final class Brew {

    private Brew() {}

    public enum Kind { WATER, AWKWARD, POTION }

    /**
     * One bottle in a stand. {@code ext} = extra seconds from a duration modifier; {@code enh} = levels
     * added by a level modifier (0 = none applied); {@code splash} 0 none, 1 gunpowder, 2 enchanted;
     * {@code brewer} = the brewer's Alchemy duration bonus in percent, stamped when first brewed.
     */
    public record Bottle(Kind kind, String effect, int level, int ext, int enh, int splash, double brewer, String brew) {
        /** Without a Basic Brew. */
        public Bottle(Kind kind, String effect, int level, int ext, int enh, int splash, double brewer) {
            this(kind, effect, level, ext, enh, splash, brewer, null);
        }

        public static Bottle water() { return new Bottle(Kind.WATER, null, 0, 0, 0, 0, 0); }
        public static Bottle awkward() { return new Bottle(Kind.AWKWARD, null, 0, 0, 0, 0, 0); }
        /** A Basic Brew (Cheap Coffee...) standing in for an Awkward Potion. */
        public static Bottle basicBrew(String item) { return new Bottle(Kind.AWKWARD, null, 0, 0, 0, 0, 0, item); }
        public static Bottle potion(String effect, int level) { return new Bottle(Kind.POTION, effect, level, 0, 0, 0, 0); }

        public boolean extended() { return ext > 0; }
        public boolean enhanced() { return enh > 0; }
        public boolean isBasicBrew() { return kind == Kind.AWKWARD && brew != null; }
        public Bottle withBrewer(double pct) { return new Bottle(kind, effect, level, ext, enh, splash, pct, brew); }
    }

    /** What happened to one bottle. {@code ingredient} is set when an effect ingredient made it (for its unlock key). */
    public record Result(Bottle bottle, double xp, EffectTable.Ingredient ingredient) {}

    /**
     * React one bottle with one ingredient item. Null = no reaction (the bottle is left alone and,
     * if no bottle reacts, the stand does not brew).
     */
    public static Result react(EffectTable t, Bottle b, String item) {
        if (b == null || item == null) return null;
        EffectTable.Modifier mod = t.modifier(item);
        switch (b.kind()) {
            case WATER -> {
                if (mod != null && "AWKWARD".equals(mod.type)) return new Result(Bottle.awkward(), mod.xp, null);
                EffectTable.Ingredient ing = t.ingredient("WATER", item);
                return ing == null ? null : fromIngredient(t, ing);
            }
            case AWKWARD -> {
                EffectTable.Ingredient ing = t.ingredient("AWKWARD", item);
                if (ing == null) return null;
                Result r = fromIngredient(t, ing);
                if (r == null || b.brew() == null) return r;
                // a Basic Brew only brews the potions it can boost, and the potion carries it
                EffectTable.BasicBrew bb = t.brew(b.brew());
                if (bb == null || !bb.appliesTo(t.effect(r.bottle().effect()))) return null;
                Bottle nb = r.bottle();
                return new Result(new Bottle(nb.kind(), nb.effect(), nb.level(), nb.ext(), nb.enh(), nb.splash(),
                        nb.brewer(), b.brew()), r.xp(), r.ingredient());
            }
            case POTION -> {
                if (mod == null) return null;
                EffectTable.Effect e = t.effect(b.effect());
                if (e == null) return null;
                return modify(t, e, b, mod);
            }
        }
        return null;
    }

    private static Result fromIngredient(EffectTable t, EffectTable.Ingredient ing) {
        EffectTable.Effect e = t.effect(ing.effect);
        if (e == null) return null;
        int lvl = Math.max(1, Math.min(ing.level, e.brewMax));
        if (e.level(lvl) == null) return null;
        return new Result(Bottle.potion(e.id, lvl), ing.xp, ing);
    }

    private static Result modify(EffectTable t, EffectTable.Effect e, Bottle b, EffectTable.Modifier mod) {
        int cap = Math.max(e.brewMax, 1);
        switch (mod.type) {
            case "LEVEL" -> {
                if (b.enhanced() || b.level() >= cap) return null;
                int nl = Math.min(cap, b.level() + mod.amount);
                if (e.level(nl) == null) return null;
                return new Result(new Bottle(Kind.POTION, e.id, nl, b.ext(), nl - b.level(), b.splash(), b.brewer(), b.brew()), mod.xp, null);
            }
            case "DURATION" -> {
                if (!canExtend(e, b)) return null;
                return new Result(new Bottle(Kind.POTION, e.id, b.level(), mod.amount, b.enh(), b.splash(), b.brewer(), b.brew()), mod.xp, null);
            }
            case "BOTH" -> {
                boolean lv = !b.enhanced() && b.level() < cap;
                boolean du = canExtend(e, b);
                if (!lv && !du) return null;
                int nl = lv ? Math.min(cap, b.level() + mod.levels) : b.level();
                if (e.level(nl) == null) return null;
                return new Result(new Bottle(Kind.POTION, e.id, nl, du ? mod.amount : b.ext(),
                        lv ? nl - b.level() : b.enh(), b.splash(), b.brewer(), b.brew()), mod.xp, null);
            }
            case "SPLASH" -> {
                if (!e.brewable || b.level() > e.brewMax || b.splash() >= mod.amount) return null;
                return new Result(new Bottle(Kind.POTION, e.id, b.level(), b.ext(), b.enh(), mod.amount, b.brewer(), b.brew()), mod.xp, null);
            }
            case "CORRUPT" -> {
                EffectTable.Effect to = t.effect(e.corruptsTo);
                if (to == null) return null;
                int nl = Math.max(1, Math.min(b.level(), to.brewMax));
                if (to.level(nl) == null) return null;
                int ext = to.extendable ? b.ext() : 0;
                EffectTable.BasicBrew bb = t.brew(b.brew());
                String brew = bb != null && bb.appliesTo(to) ? b.brew() : null;   // the corrupted twin keeps it only if it still boosts it
                return new Result(new Bottle(Kind.POTION, to.id, nl, ext, b.enh(), b.splash(), b.brewer(), brew), mod.xp, null);
            }
            default -> { return null; }
        }
    }

    private static boolean canExtend(EffectTable.Effect e, Bottle b) {
        if (b.extended() || !e.extendable || e.isInstant()) return false;
        EffectTable.Level l = e.level(b.level());
        return l != null && l.seconds > 0;
    }

    /**
     * Seconds a potion lasts when drunk: (base + extension) x (1 + brewer%) x (1 + affinity%) x parrot.
     * Instant potions return 0.
     */
    public static long duration(EffectTable t, Bottle b, double affinityPct, double parrotMult) {
        EffectTable.Effect e = t.effect(b.effect());
        if (e == null) return 0;
        EffectTable.Level l = e.level(b.level());
        if (l == null || l.seconds <= 0) return 0;
        double s = (l.seconds + Math.max(0, b.ext())) * (1 + Math.max(0, b.brewer()) / 100.0)
                * (1 + Math.max(0, affinityPct) / 100.0) * Math.max(1.0, parrotMult);
        return Math.round(s);
    }

    // ---- stand layout -------------------------------------------------------------------------

    /** What one stand slot holds, for {@link #arrange}. */
    public enum SlotKind { EMPTY, BOTTLE, INGREDIENT, OTHER }

    /**
     * Where a stand's contents belong after a change. Slot 0 = ingredient slot, 1..3 = bottle slots.
     * {@code from[t]} = the source slot whose item now fills slot {@code t} (-1 = empty), {@code take[t]}
     * = how many of it; {@code back[s]} = how many of source slot {@code s} go back to the player.
     * Every item is accounted for exactly once (unit-tested), so applying it can never dupe or delete.
     */
    public record Arrangement(int[] from, int[] take, int[] back, boolean changed) {}

    /**
     * Put a stand right the way vanilla's shift-click does: bottles one per bottle slot, an
     * ingredient in the top slot. Core's generic shift-click fills free slots in index order, so a
     * stack of Water Bottles lands in the ingredient slot (13 comes before 38/40/42) - this moves them
     * down, one each; a stack dropped on one bottle slot is spread across the empty ones; an
     * ingredient that landed in a bottle slot moves up if the top slot is free; anything left over
     * (a 4th bottle, a second ingredient, a non-brewing item in a bottle slot) goes back to the player.
     */
    public static Arrangement arrange(SlotKind[] kind, int[] amount) {
        int n = 4;
        int[] from = {-1, -1, -1, -1};
        int[] take = new int[n];
        int[] back = new int[n];
        // pending bottles waiting for an empty bottle slot: [source slot, amount]
        int[][] pending = new int[n][];
        int pc = 0;

        // the ingredient slot
        if (kind[0] == SlotKind.BOTTLE && amount[0] > 0) pending[pc++] = new int[]{0, amount[0]};
        else if (kind[0] != SlotKind.EMPTY && amount[0] > 0) { from[0] = 0; take[0] = amount[0]; }

        // the bottle slots: keep one bottle, queue the rest; move/return everything else
        for (int s = 1; s < n; s++) {
            if (kind[s] == SlotKind.EMPTY || amount[s] <= 0) continue;
            if (kind[s] == SlotKind.BOTTLE) {
                from[s] = s; take[s] = 1;
                if (amount[s] > 1) pending[pc++] = new int[]{s, amount[s] - 1};
            } else if (kind[s] == SlotKind.INGREDIENT && from[0] < 0) {
                from[0] = s; take[0] = amount[s];
            } else {
                back[s] += amount[s];
            }
        }

        // queued bottles fill the empty bottle slots, one each, in order
        for (int i = 0; i < pc; i++) {
            int src = pending[i][0], left = pending[i][1];
            for (int t = 1; t < n && left > 0; t++) {
                if (from[t] >= 0) continue;
                from[t] = src; take[t] = 1; left--;
            }
            back[src] += left;
        }

        boolean changed = false;
        for (int t = 0; t < n; t++) {
            boolean had = kind[t] != SlotKind.EMPTY && amount[t] > 0;
            if (back[t] > 0 || (had ? from[t] != t || take[t] != amount[t] : from[t] >= 0)) changed = true;
        }
        return new Arrangement(from, take, back, changed);
    }

    /** God Potion seconds: legacy = flat hours; current = base hours + alchemyHours x level/50, x parrot. */
    public static long godSeconds(boolean legacy, double legacyHours, double baseHours, double alchemyHours,
                                  int alchemyLevel, double parrotMult) {
        double h = legacy ? legacyHours : baseHours + alchemyHours * Math.max(0, Math.min(50, alchemyLevel)) / 50.0;
        return Math.round(h * 3600 * Math.max(1.0, parrotMult));
    }
}
