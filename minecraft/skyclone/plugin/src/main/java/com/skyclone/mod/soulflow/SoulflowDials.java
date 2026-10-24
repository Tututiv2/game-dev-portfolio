package com.skyclone.mod.soulflow;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every Soulstream tunable. Read at use time ({@code .get()}). */
final class SoulflowDials {

    private SoulflowDials() {}

    static Dials.Dial<Boolean> ENABLED;
    static Dials.Dial<Boolean> COSTS;
    static Dials.Dial<Double> COST_SCALE;
    static Dials.Dial<Boolean> POOL_NEEDS_ACCESSORY;
    static Dials.Dial<Boolean> INTERNALIZE_NEEDS_ACCESSORY;
    static Dials.Dial<Boolean> INVENTORY_FALLBACK;
    static Dials.Dial<Integer> POOL_MAX;
    static Dials.Dial<Double> COMBAT_SECONDS;
    static Dials.Dial<Boolean> OVERFLOW;
    static Dials.Dial<Double> OVERFLOW_SCALE;
    static Dials.Dial<Boolean> OVERFLOW_FROM_ABILITIES;
    static Dials.Dial<Boolean> DRAINS;
    static Dials.Dial<Boolean> OWNER_GATED_DRAINS;
    static Dials.Dial<Double> SOULWARD_SECONDS;
    static Dials.Dial<Double> SOULWARD_WEAK_SECONDS;
    static Dials.Dial<Double> SOULWARD_WEAK_MULT;
    static Dials.Dial<Double> MESSAGE_COOLDOWN;
    static Dials.Dial<Integer> SACK_INTERNALIZE_MAX;

    static void register() {
        Dials d = Sky.dials();
        ENABLED = d.flag("soulflow.enabled", true,
                "Master switch for Soulstream (reference: Soulflow). Off = no ability costs, no drains, no Overflow conversions");
        COSTS = d.flag("soulflow.costs-enabled", true,
                "Abilities with a Soulstream cost (Phasewarp, Soul Howl, Judgement Beam, Gravity Well...) charge it");
        COST_SCALE = d.number("soulflow.cost-scale", 1.0,
                "Multiplier on every ability's Soulstream cost (rounded up, minimum 1). Research numbers at 1.0");
        POOL_NEEDS_ACCESSORY = d.flag("soulflow.pool-needs-accessory", true,
                "Internalized Soulstream can only be drained while a Soulstream accessory is carried or bagged (reference lore)");
        INTERNALIZE_NEEDS_ACCESSORY = d.flag("soulflow.internalize-needs-accessory", true,
                "Right-click internalizing needs a Soulstream accessory (Pile/Battery/Supercell). TUNE: the reference's exact rule is undocumented");
        INVENTORY_FALLBACK = d.flag("soulflow.inventory-fallback", true,
                "Loose Raw Soulstream in the inventory pays a cost the internalized pool cannot (the pre-accessory behaviour)");
        POOL_MAX = d.integer("soulflow.pool-max", 0,
                "Most Soulstream one player can internalize (0 = no limit). TUNE: the reference has no documented cap");
        COMBAT_SECONDS = d.number("soulflow.combat-seconds", 8.0,
                "Seconds after the last hit dealt or taken that a player still counts as in combat. TUNE: undocumented in research");
        OVERFLOW = d.flag("soulflow.overflow-enabled", true,
                "Soulstream accessories turn Soulstream into Overflow Mana when you leave combat");
        OVERFLOW_SCALE = d.number("soulflow.overflow-scale", 1.0,
                "Multiplier on every accessory's Overflow figure (Pile 200 / Battery 400 / Supercell 600 at 1.0)");
        OVERFLOW_FROM_ABILITIES = d.flag("soulflow.overflow-pays-abilities", true,
                "When mana is short for an ability, Overflow Mana tops it up first (research 01: second pool used once Mana is empty)");
        DRAINS = d.flag("soulflow.drains-enabled", true,
                "Per-5s combat drains: Handy Blood Chalice, Pocket Espresso Machine, Final Destination set while sneaking");
        OWNER_GATED_DRAINS = d.flag("soulflow.charge-owner-gated-drains", true,
                "Charge drains whose bonus another module grants (Last Horizon set, 2 per 5s sneaking). On since wave-5 integration: "
                + "slayer grants Vivid Darkness only while Features.Soulflow has Soulstream to pay (slayer request 13)");
        SOULWARD_SECONDS = d.number("soulflow.soulward-seconds", 5.0,
                "Soulguard Sprig: seconds of invulnerability (research: 5)");
        SOULWARD_WEAK_SECONDS = d.number("soulflow.soulward-weak-seconds", 2.0,
                "Soulguard Sprig: seconds of halved damage afterwards (research: 2)");
        SOULWARD_WEAK_MULT = d.number("soulflow.soulward-weak-multiplier", 0.5,
                "Soulguard Sprig: damage multiplier during the weakness (research: halved)");
        MESSAGE_COOLDOWN = d.number("soulflow.message-cooldown-seconds", 3.0,
                "Seconds between two 'not enough Soulstream' chat lines (the action-bar flash answers every click)");
        SACK_INTERNALIZE_MAX = d.integer("soulflow.sack-internalize-max", 100_000,
                "Most items one 'internalize from sacks' click takes");
    }

    static boolean on() { return ENABLED == null || ENABLED.get(); }

    /** A scaled cost, rounded up, never below 1 for a positive base. */
    static long scaled(long base) {
        if (base <= 0) return 0;
        double s = COST_SCALE == null ? 1.0 : Math.max(0, COST_SCALE.get());
        if (s <= 0) return 0;
        return Math.max(1, (long) Math.ceil(base * s - 1e-9));
    }
}
