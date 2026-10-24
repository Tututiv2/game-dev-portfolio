package com.skyclone.mod.minions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The profile's minion state: SHARED profile data (key {@code "minions"}) because the island, its
 * minions, the minion slots and the unique crafts all belong to the co-op, not to one member
 * (research 05 co-op table). Gson-friendly: public no-arg constructors, plain fields, no Bukkit types.
 * Never change a field's TYPE (CORE_GUIDE trap 3) - only add fields.
 */
public final class MinionStore {

    /** Placed minions by their own id (a random uuid string). */
    public Map<String, Placed> placed = new LinkedHashMap<>();
    /** Unique tiers ever obtained by crafting/upgrading: "COBBLESTONE:4". Drives the slot table. */
    public Set<String> crafted = new LinkedHashSet<>();
    /** The starter Cobblestone Minion I was handed out (once per profile). */
    public boolean starterGiven;
    /** Co-op XP shares waiting for an offline member: uuid -> skill -> xp. */
    public Map<String, Map<String, Double>> pendingXp = new HashMap<>();
    /** Placed island crystals (Farm Crystal, Postcard...) by their own id. */
    public Map<String, Crystal> crystals = new LinkedHashMap<>();
    /** Lifetime items produced by every minion of this profile. */
    public long totalGenerated;
    /** Lifetime coins earned by hoppers. */
    public double totalHopperCoins;
    /** Epoch ms until which the island's Beacon is powered by a Power Crystal (0 = never). Drains in real time. */
    public long beaconPoweredUntil;
    /** The crystal powering the Beacon right now is a Scorched one (+1% minion speed). */
    public boolean beaconScorched;
    /** The profile stat the powered Beacon boosts for every member ({@code Stat} name), null = none chosen yet. */
    public String beaconStat;
    /**
     * Wave 5, Winter Crystal ("once every SkyBlock Year, a present will spawn somewhere on the island"): the
     * SkyBlock year the last present appeared, and where the unclaimed one sits (packed, 0 = none) and its world.
     */
    public int presentYear;
    public long presentAt;
    public String presentWorld;

    /** One placed minion. */
    public static final class Placed {
        public String id;
        public String type;
        public int tier;
        public String world;
        public int x, y, z;
        public float yaw;
        /** Storage slots in order: the first tierSlots are the minion's own, the rest the attached chest. */
        public List<Slot> storage = new ArrayList<>();
        /** Fractional production carried between ticks so the long-run average is exact. */
        public Map<String, Double> frac = new HashMap<>();
        /** Harvests owed but not yet whole (0..1). */
        public double progress;
        public String fuel;
        public int fuelCount;
        /** Milliseconds left on the fuel item currently burning (timed fuels only). */
        public long fuelLeftMs;
        public String upgrade1, upgrade2, shipping, chest, skin;
        public boolean infused;
        /** Epoch ms the simulation last advanced to. */
        public long lastUpdate;
        public long generated;
        /** Coins earned by the shipping item, collected with Collect All. */
        public double heldCoins;
        public long placedAt;
        public String placedBy;
        public boolean fullNotified;
        /** Packed block positions this minion placed in the world (so players never farm them for drops). */
        public List<Long> visuals = new ArrayList<>();
        /**
         * Wave 5: sitting on a Minion Chair (research 4.3): it does not work, burns no fuel and does not count
         * toward the placement cap. {@code chair} is the item id it sits on (handed back when it stands up).
         */
        public boolean seated;
        public String chair;
        /** Wave 5: it accepted Free Will and stayed (+10% speed for good; travels with the item like infusion). */
        public boolean freeWill;
        /**
         * Wave 5: packed position of the REAL chest block standing next to it for its attached storage chest
         * (null = no block: the storage still works, shown as a small chest display on its back).
         */
        public Long chestBlock;

        public int count(String id) {
            int n = 0;
            for (Slot s : storage) if (s.id.equals(id)) n += s.amount;
            return n;
        }

        public int stored() {
            int n = 0;
            for (Slot s : storage) n += s.amount;
            return n;
        }

        public boolean hasUpgrade(String id) {
            return id != null && (id.equals(upgrade1) || id.equals(upgrade2));
        }
    }

    public static final class Slot {
        public String id;
        public int amount;
        public Slot() {}
        public Slot(String id, int amount) { this.id = id; this.amount = amount; }
    }

    public static final class Crystal {
        public String id;
        public String item;
        public String world;
        public int x, y, z;
        public long placedAt;
    }

    public static String uniqueKey(String type, int tier) { return type + ":" + tier; }

    /** Minions that count toward the placement cap: everything not sitting on a Minion Chair. */
    public int working() {
        int n = 0;
        for (Placed m : placed.values()) if (!m.seated) n++;
        return n;
    }
}
