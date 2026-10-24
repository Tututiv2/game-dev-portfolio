package com.skyclone.mod.garden;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The Greenhouse as pure maths (research 08 1.15): a grid of cells, each crop grows in timed stages
 * and drinks water each stage; neighbours (orthogonal) pass effects to each other; EMPTY cells next
 * to the right mix of neighbours can sprout a mutation. Everything is lazy - computed from timestamps
 * when someone looks - so it grows while everyone is offline.
 */
public final class GreenhouseSim {

    private GreenhouseSim() {}

    /** What the neighbours of one cell give it. */
    public record Received(double yield, double xp, double water, boolean immune) {}

    public static final int SIZE = 5;

    public static void ensureCells(GardenData.GreenhouseState g, int grids) {
        int want = SIZE * SIZE * Math.max(1, grids);
        while (g.cells.size() < want) g.cells.add(new GardenData.Cell());
    }

    public static List<Integer> neighbours(int index) {
        int grid = index / (SIZE * SIZE), i = index % (SIZE * SIZE);
        int r = i / SIZE, c = i % SIZE;
        List<Integer> out = new ArrayList<>(4);
        int base = grid * SIZE * SIZE;
        if (r > 0) out.add(base + (r - 1) * SIZE + c);
        if (r < SIZE - 1) out.add(base + (r + 1) * SIZE + c);
        if (c > 0) out.add(base + r * SIZE + c - 1);
        if (c < SIZE - 1) out.add(base + r * SIZE + c + 1);
        return out;
    }

    static boolean alive(GardenData.Cell c) { return c != null && c.crop != null && !c.dead; }

    public static int cycles(GardenTables.GreenhouseTable t, GardenData.Cell c) {
        if (c.mutation) {
            for (GardenTables.Mutation m : t.mutations) if (m.id.equals(c.crop)) return m.cycles;
            return 5;
        }
        for (GardenTables.GhCrop k : t.crops) if (k.crop.equals(c.crop)) return k.cycles;
        return 8;
    }

    public static List<String> effectsOf(GardenTables.GreenhouseTable t, GardenData.Cell c) {
        if (!alive(c) || c.mutation) return List.of();
        for (GardenTables.GhCrop k : t.crops) if (k.crop.equals(c.crop)) return k.effects;
        return List.of();
    }

    /** Effects reaching cell {@code index} from its neighbours (immunity and effect spread included). */
    public static Received received(GardenTables.GreenhouseTable t, List<GardenData.Cell> cells, int index) {
        List<String> incoming = new ArrayList<>();
        boolean immune = false;
        for (int n : neighbours(index)) {
            GardenData.Cell nc = cells.get(n);
            List<String> eff = effectsOf(t, nc);
            if (eff.contains("immunity")) immune = true;
            incoming.addAll(eff);
            if (eff.contains("effect_spread"))
                for (int nn : neighbours(n)) if (nn != index) for (String e : effectsOf(t, cells.get(nn))) if (!e.equals("effect_spread")) incoming.add(e);
        }
        double y = 0, x = 0, w = 0;
        for (String e : incoming) {
            GardenTables.Effect def = t.effects.get(e);
            if (def == null) continue;
            if (def.value < 0 && immune) continue;
            switch (def.kind) {
                case "yield" -> y += def.value;
                case "xp" -> x += def.value;
                case "water" -> w += def.value;
                default -> { }
            }
        }
        return new Received(y, x, w, immune);
    }

    public static int uniqueCrops(List<GardenData.Cell> cells) {
        Set<String> s = new HashSet<>();
        for (GardenData.Cell c : cells) if (alive(c) && !c.mutation) s.add(c.crop);
        return Math.min(12, s.size());
    }

    public static boolean ready(GardenTables.GreenhouseTable t, GardenData.Cell c) {
        return alive(c) && c.stage >= cycles(t, c);
    }

    /**
     * Advance every cell to {@code now}. {@code stageMillis} is the current stage length. Water: each
     * stage costs 2-3 (less with Water Retain, more with Water Drain); a stage that ends dry has a
     * 25% chance to wither the crop (TUNE: research says "negative water -&gt; failure chance").
     */
    public static void advance(GardenTables.GreenhouseTable t, GardenData.GreenhouseState g, long now, long stageMillis, Random rnd) {
        advance(t, g, now, stageMillis, rnd, 1.0);
    }

    /** {@code mutationMult}: Bioanalysis ("crops are N% more likely to mutate") multiplies each recipe's chance. */
    public static void advance(GardenTables.GreenhouseTable t, GardenData.GreenhouseState g, long now, long stageMillis, Random rnd, double mutationMult) {
        if (stageMillis <= 0) stageMillis = 1;
        if (g.lastSim <= 0) g.lastSim = now;
        List<GardenData.Cell> cells = g.cells;
        for (int i = 0; i < cells.size(); i++) {
            GardenData.Cell c = cells.get(i);
            if (!alive(c)) continue;
            int guard = 0;
            while (c.stage < cycles(t, c) && c.nextStageAt > 0 && now >= c.nextStageAt && guard++ < 64) {
                Received r = received(t, cells, i);
                int cost = t.waterPerStage[0] + rnd.nextInt(Math.max(1, t.waterPerStage[t.waterPerStage.length - 1] - t.waterPerStage[0] + 1));
                double factor = Math.max(0, 1 - r.water() / 100.0);
                c.water -= (int) Math.round(cost * factor);
                if (c.water < 0) {
                    c.water = 0;
                    if (rnd.nextDouble() < 0.25) { c.dead = true; break; }
                }
                c.stage++;
                c.nextStageAt += stageMillis;
            }
        }
        // mutations sprout in empty cells, once per elapsed stage
        int steps = (int) Math.min(48, (now - g.lastSim) / stageMillis);
        for (int s = 0; s < steps; s++) {
            long when = g.lastSim + (s + 1) * stageMillis;
            for (int i = 0; i < cells.size(); i++) {
                GardenData.Cell c = cells.get(i);
                if (c.crop != null) continue;
                Map<String, Integer> around = new HashMap<>();
                for (int n : neighbours(i)) {
                    GardenData.Cell nc = cells.get(n);
                    if (alive(nc) && nc.stage >= 1) around.merge(nc.crop, 1, Integer::sum);
                }
                if (around.isEmpty()) continue;
                for (GardenTables.Mutation m : t.mutations) {
                    if (!satisfied(m.needs, around)) continue;
                    if (rnd.nextDouble() >= Math.min(1.0, m.chance * Math.max(1.0, mutationMult))) continue;
                    c.crop = m.id;
                    c.mutation = true;
                    c.stage = 0;
                    c.dead = false;
                    c.water = t.waterMax;
                    c.nextStageAt = when + stageMillis;
                    break;
                }
            }
        }
        g.lastSim += steps * stageMillis;
        if (g.lastSim < now - stageMillis * 48) g.lastSim = now;
    }

    public static boolean satisfied(List<String> needs, Map<String, Integer> around) {
        Map<String, Integer> want = new HashMap<>();
        for (String n : needs) want.merge(n, 1, Integer::sum);
        for (Map.Entry<String, Integer> e : want.entrySet()) if (around.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        return true;
    }

    /**
     * Yield of a ready crop: base x (1 + neighbour yield% + 3% per unique crop) x (1 + chip%). Every yield bonus is
     * additive except the Evergreen chip's, which multiplies (research 08 "Greenhouse").
     */
    public static long yield(GardenTables.GreenhouseTable t, List<GardenData.Cell> cells, int index, double chipPercent) {
        GardenData.Cell c = cells.get(index);
        if (c.mutation) return 1;
        int base = 0;
        for (GardenTables.GhCrop k : t.crops) if (k.crop.equals(c.crop)) base = k.yield;
        Received r = received(t, cells, index);
        double mult = (1 + r.yield() / 100.0 + 0.03 * uniqueCrops(cells)) * (1 + Math.max(0, chipPercent) / 100.0);
        return Math.max(0, Math.round(base * Math.max(0, mult)));
    }

    public static double xpMult(GardenTables.GreenhouseTable t, List<GardenData.Cell> cells, int index) {
        return Math.max(0, 1 + received(t, cells, index).xp() / 100.0);
    }
}
