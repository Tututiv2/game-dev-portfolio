package com.skyclone.mod.accessories;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.item.Rarity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every tunable of the module, registered in enable(). Read with .get() at use time. */
final class AccDials {

    private AccDials() {}

    static Dials.Dial<Integer> BASE_SLOTS, REDSTONE_PER_TIER, MILESTONE_SLOTS, EXCHANGE_SLOTS, EXCHANGE_COST, MAX_PAIRS;
    static Dials.Dial<String> MILESTONE_LEVELS, EXCHANGE_ITEM;
    static Dials.Dial<Double> PAIR_COST_BASE, POWER_SCALE, DUNGEON_MP_MULT, GRAVITY_STEP, SELL_CAP_FACTOR, HEALTH_ABILITY_WINDOW;
    static Dials.Dial<Integer> PIECE_EVERY, PIECE_CAP;
    static Dials.Dial<Boolean> FIRE_DOWNSIDES;
    static Dials.Dial<Double> FIST_COOLDOWN, GUIDED_BAT_SECONDS, SKULL_ORBIT_SECONDS;
    static Dials.Dial<Integer> FIST_PULSES, MASK_BATS, KEEP_BAG_SLOTS;
    static Dials.Dial<String> BAT_ABILITY_ITEMS, BAT_MASKS;
    static Dials.Dial<Double> MINING_TARGET_SECONDS;
    static Dials.Dial<Integer> RIFT_PRISM_MP, MP_PER_TUNING, STONES_TO_LEARN, TEMPLATE_MAX, TEMPLATE_BITS,
            SNAPSHOT_SECONDS, PIGGY_MIN, COIN_FIND_SECONDS, MAX_MP_XP_PER_SECOND;
    static Dials.Dial<Boolean> NPC, COUNT_INVENTORY, COMPACTOR_ON, BAG_REQUIRES;
    static final Map<Rarity, Dials.Dial<Integer>> MP = new EnumMap<>(Rarity.class);
    /** Tuning stat -> value per point. Order = the menu order. */
    static final Map<String, Dials.Dial<Double>> TUNING = new LinkedHashMap<>();
    static final Map<String, Dials.Dial<Double>> ENRICH = new LinkedHashMap<>();

    static void register() {
        Dials d = Sky.dials();
        BASE_SLOTS = d.integer("accessories.base-slots", 9, "Accessory Bag slots everyone starts with (research 06 7.3: 9)");
        REDSTONE_PER_TIER = d.integer("accessories.redstone-slots-per-tier", 6, "Bag slots per Redstone collection tier II..IX (research: 6)");
        MILESTONE_LEVELS = d.text("accessories.sblevel-milestones", "10,25,50,75,100,150",
                "SkyBlock levels that each add bag slots (stands in for the reference's six account upgrades; TUNE)");
        MILESTONE_SLOTS = d.integer("accessories.sblevel-slots-each", 2, "Bag slots per SkyBlock level milestone (research: +2 each)");
        EXCHANGE_SLOTS = d.integer("accessories.redstone-exchange-slots", 4, "Bag slots from the one-time redstone exchange (research: +4)");
        EXCHANGE_ITEM = d.text("accessories.redstone-exchange-item", "ENCHANTED_REDSTONE_BLOCK", "Item paid for the redstone exchange");
        EXCHANGE_COST = d.integer("accessories.redstone-exchange-cost", 2, "How many of that item the exchange costs (TUNE)");
        MAX_PAIRS = d.integer("accessories.max-bought-pairs", 99, "Slot pairs the Bagwright sells (research: 99 = +198 slots)");
        PAIR_COST_BASE = d.number("accessories.pair-cost-base", 5360, "Coin cost of pair n = base * n^2 (5,360 gives the research's ~1.76B total)");
        BAG_REQUIRES = d.flag("accessories.bag-needs-redstone-2", false,
                "If true the bag opens only at Redstone collection II (older reference); default: always open");

        int[] def = AccMath.DEFAULT_MP;
        for (Rarity r : Rarity.values()) {
            MP.put(r, d.integer("accessories.mp." + r.name().toLowerCase(), r.ordinal() < def.length ? def[r.ordinal()] : 0,
                    "Accessory Power granted by one " + r.display() + " accessory"));
        }
        RIFT_PRISM_MP = d.integer("accessories.rift-prism-mp", 11, "Accessory Power from a consumed Rift Prism (research: 11)");
        DUNGEON_MP_MULT = d.number("accessories.dungeon-mp-mult", 2, "Accessory Power multiplier of dungeon accessories inside dungeons");
        POWER_SCALE = d.number("accessories.power-scale", 1.0, "Global multiplier on Power stats (1 = research)");
        MP_PER_TUNING = d.integer("accessories.mp-per-tuning-point", 10, "Accessory Power per tuning point (research: 10)");
        STONES_TO_LEARN = d.integer("accessories.stones-to-learn", 9, "Power stones consumed to learn a power (stone lore: 9)");
        TEMPLATE_MAX = d.integer("accessories.template-slots-max", 5, "Tuning template slots a player can unlock");
        TEMPLATE_BITS = d.integer("accessories.template-bits-cost", 10000, "Bits per extra tuning template slot (research: 10,000)");
        SNAPSHOT_SECONDS = d.integer("accessories.recompute-seconds", 5, "Seconds between full re-evaluations of a bag (requirements, rarity)");
        PIGGY_MIN = d.integer("accessories.piggy-min-loss", 20000, "Coins a death must cost before a piggy bank steps in (research: 20k)");
        COIN_FIND_SECONDS = d.integer("accessories.coin-find-seconds", 60, "Seconds between coin-talisman rolls (TUNE)");
        GRAVITY_STEP = d.number("accessories.gravity-blocks-per-step", 10, "Gravity Talisman loses 1 per this many blocks from spawn (TUNE)");
        PIECE_EVERY = d.integer("accessories.piece-bonus-every", 1000, "Vanquished piece bonus: kills (or blocks mined) per +1 stat (research: 1,000)");
        HEALTH_ABILITY_WINDOW = d.number("accessories.health-ability-window", 1.0,
                "Ancient Cloak: seconds after a health-costing ability runs in which its delayed hits still count (TUNE: the rose volleys land within 0.4 s)");
        PIECE_CAP = d.integer("accessories.piece-bonus-cap", 10, "Vanquished piece bonus: the most it can build up to (TUNE)");
        // wave 5
        FIRE_DOWNSIDES = d.flag("accessories.fire-downsides", true,
                "Delirium Necklace / Scourge Cloak make fire hurt more, as their lore says (off = stats only)");
        FIST_COOLDOWN = d.number("accessories.flaming-fist-cooldown", 0.5,
                "Flaming Fist: seconds between fist volleys from melee hits (TUNE; research gives none)");
        FIST_PULSES = d.integer("accessories.flaming-fist-pulses", 6, "Flaming Fist: damage pulses the burn is split into");
        GUIDED_BAT_SECONDS = d.number("accessories.guided-bat-seconds", 5,
                "Bat Person: seconds a bat-summoning ability's bat counts as summoned (TUNE)");
        MASK_BATS = d.integer("accessories.bat-mask-bats", 2, "Bat Person: bats a bat-swarm mask (WITCH_MASK) counts as (armour draws 2)");
        BAT_ABILITY_ITEMS = d.text("accessories.bat-ability-items", "SPIRIT_SCEPTRE,STARRED_SPIRIT_SCEPTRE",
                "Bat Person: items whose ability summons a guided bat (comma list)");
        BAT_MASKS = d.text("accessories.bat-masks", "WITCH_MASK", "Bat Person: helmets that keep bats around you (comma list)");
        SKULL_ORBIT_SECONDS = d.number("accessories.haunted-skull-seconds", 3,
                "Soulweaver Gloves: how long one Haunted Skull circles you (TUNE)");
        KEEP_BAG_SLOTS = d.integer("accessories.keep-bag-slots", 45, "Carnival Mask Bag / New Year Cake Bag storage slots");
        MINING_TARGET_SECONDS = d.number("accessories.mining-target-seconds", 3,
                "'While mining X' bonuses (mithril/titanium/gemstone pieces, Divan Pendant) hold this long after you last hit such a block");        MAX_MP_XP_PER_SECOND = d.integer("accessories.mp-xp-per-second", 100, "SkyBlock XP awards per second while catching up on Accessory Power");
        NPC = d.flag("accessories.npc", true, "Spawn the Thaumaturgist NPC in the hub (anchor npc.thaumaturgist)");
        COUNT_INVENTORY = d.flag("accessories.count-inventory", false, "Accessories in the inventory also count (old reference rule)");
        COMPACTOR_ON = d.flag("accessories.compactor", true, "Personal compactors/deletors run");
        SELL_CAP_FACTOR = d.number("accessories.sell-cap-factor", 1.0,
                "A crafted accessory/equipment/power stone sells to NPCs for at most this x its cheapest recipe's inputs (applied at startup; only lowers)");

        // research 01 20.4 (per-point values flagged "from memory" there; TUNE)
        tune("HEALTH", 5); tune("DEFENSE", 1); tune("SPEED", 1.5); tune("STRENGTH", 1);
        tune("CRIT_DAMAGE", 1); tune("CRIT_CHANCE", 0.2); tune("ATTACK_SPEED", 0.3); tune("INTELLIGENCE", 2);
    }

    private static void tune(String stat, double v) {
        TUNING.put(stat, Sky.dials().number("accessories.tuning." + stat.toLowerCase(), v, "Stat per tuning point: " + stat));
    }

    static int mp(Rarity r) {
        Dials.Dial<Integer> dl = MP.get(r);
        return dl == null ? 0 : Math.max(0, dl.get());
    }

    static int[] mpTable() {
        int[] t = new int[Rarity.values().length];
        for (Rarity r : Rarity.values()) t[r.ordinal()] = mp(r);
        return t;
    }

    static List<Integer> milestoneLevels() {
        List<Integer> out = new ArrayList<>();
        for (String s : MILESTONE_LEVELS.get().split(",")) {
            try { out.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static List<String> tuningOrder() { return new ArrayList<>(TUNING.keySet()); }
}
