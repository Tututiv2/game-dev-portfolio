package com.thestill.mine;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * A weighted block distribution for one depth.
 *
 * <p>The shape is inherited from Shadow Prison and deliberately not re-derived:
 * <b>filler weighs 75, and every ore together weighs 25</b>. Three quarters of
 * every swing is worthless on purpose — it is what makes the ore feel found
 * rather than handed over, and it is the denominator every later multiplier
 * (Din band, heat state) is tuned against.
 *
 * <p>Immutable. Build one per depth at startup and roll against it forever.
 */
public final class OreTable {

    private final List<Material> materials;
    private final int[] cumulative;
    private final int total;
    private final Material filler;

    private OreTable(List<Material> materials, int[] cumulative, int total, Material filler) {
        this.materials = materials;
        this.cumulative = cumulative;
        this.total = total;
        this.filler = filler;
    }

    public static Builder builder(Material filler) {
        return new Builder(filler);
    }

    /**
     * Picks a block.
     *
     * <p>Linear scan over the cumulative weights: the table has a handful of
     * entries, so a binary search would cost more in branch misprediction than
     * it saves, and this runs millions of times during a reset.
     */
    public Material roll(RandomGenerator random) {
        int pick = random.nextInt(total);
        for (int i = 0; i < cumulative.length; i++) {
            if (pick < cumulative[i]) {
                return materials.get(i);
            }
        }
        return filler; // unreachable while total is the last cumulative value
    }

    public Material filler() {
        return filler;
    }

    public int totalWeight() {
        return total;
    }

    public int entryCount() {
        return materials.size();
    }

    /** Fraction of the table this material occupies, for display and for tests. */
    public double shareOf(Material material) {
        int weight = 0;
        int previous = 0;
        for (int i = 0; i < materials.size(); i++) {
            if (materials.get(i) == material) {
                weight += cumulative[i] - previous;
            }
            previous = cumulative[i];
        }
        return (double) weight / total;
    }

    public Map<Material, Integer> weights() {
        Map<Material, Integer> out = new LinkedHashMap<>();
        int previous = 0;
        for (int i = 0; i < materials.size(); i++) {
            out.merge(materials.get(i), cumulative[i] - previous, Integer::sum);
            previous = cumulative[i];
        }
        return out;
    }

    public static final class Builder {

        private final Material filler;
        private final Map<Material, Integer> weights = new LinkedHashMap<>();

        private Builder(Material filler) {
            this.filler = filler;
        }

        /** Adds an ore. Weights are relative; the convention is that ores sum to 25. */
        public Builder ore(Material material, int weight) {
            if (weight <= 0) {
                throw new IllegalArgumentException("weight for " + material + " must be positive");
            }
            if (!isPlaceable(material)) {
                throw new IllegalArgumentException(material + " is not placeable as a block — "
                        + "a mine holds ore BLOCKS, not the items they drop");
            }
            weights.merge(material, weight, Integer::sum);
            return this;
        }

        /**
         * Whether this material can be placed in the world.
         *
         * <p>Worth checking: a mine holds {@code DEEPSLATE_COAL_ORE}, not
         * {@code COAL}, and confusing the two builds a table full of items that
         * cannot be placed.
         *
         * <p>Paper routes {@code Material.isBlock()} through the registry, which
         * only exists once a server is running, so off-server this degrades to
         * "assume it is fine" rather than failing. The check still fires where it
         * matters — at plugin startup, before a single block is placed.
         */
        private static boolean isPlaceable(Material material) {
            try {
                return material.isBlock();
            } catch (Throwable noRegistry) {
                return true;
            }
        }

        /**
         * Finishes the table, giving the filler whatever weight is left up to
         * {@code totalWeight}.
         *
         * @throws IllegalArgumentException if the ores already exceed the total —
         *         a table with no filler at all is a typo, not a design choice
         */
        public OreTable fillTo(int totalWeight) {
            int oreWeight = weights.values().stream().mapToInt(Integer::intValue).sum();
            if (oreWeight >= totalWeight) {
                throw new IllegalArgumentException("ores weigh " + oreWeight
                        + " which leaves no room for filler in a total of " + totalWeight);
            }
            Map<Material, Integer> all = new LinkedHashMap<>();
            all.put(filler, totalWeight - oreWeight);
            all.putAll(weights);

            List<Material> materials = new ArrayList<>(all.size());
            int[] cumulative = new int[all.size()];
            int running = 0;
            int index = 0;
            for (Map.Entry<Material, Integer> entry : all.entrySet()) {
                materials.add(entry.getKey());
                running += entry.getValue();
                cumulative[index++] = running;
            }
            return new OreTable(List.copyOf(materials), cumulative, running, filler);
        }

        /** The convention: filler 75, ores 25. */
        public OreTable build() {
            return fillTo(100);
        }
    }
}
