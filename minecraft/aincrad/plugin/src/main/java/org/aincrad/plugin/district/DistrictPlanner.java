package org.aincrad.plugin.district;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic parcel-graph generator. Lays perimeter city blocks separated by streets, then
 * subdivides each block edge into plots that front onto the street they came from. This produces
 * the irregular European perimeter-block urbanism the spec asks for ("busy and irregular, never a
 * perfect mall grid") rather than a lattice of detached boxes, and it guarantees by construction
 * that every plot has a street to open its door onto.
 *
 * <p>Block interiors are left as courtyards/service yards - that space is what stops the plots from
 * meeting back-to-back and is where later passes can put wells, gardens and alley access.</p>
 *
 * <p>Determinism: everything derives from {@code seed} plus the district id, and blocks are walked
 * in a fixed order, so the same inputs always yield the same parcel list regardless of timing.</p>
 */
public final class DistrictPlanner {

    private static final int STREET_COLLECTOR = 8;
    private static final int STREET_LANE = 5;
    private static final int MIN_BLOCK = 30;
    private static final int MAX_BLOCK = 46;
    private static final int MIN_FRONT = 7;
    private static final int MAX_FRONT = 11;
    private static final int MIN_DEPTH = 10;
    private static final int MAX_DEPTH = 18;
    private static final int CORNER_MAX_W = 16;

    private DistrictPlanner() {
    }

    /**
     * The city blocks themselves, exposed so the street-paving phase can lay carriageway in the
     * corridors between them. The planner reserves those corridors by construction; without this
     * the town has building plots but literally no paved streets, which is exactly how the first
     * overhead render came out - houses scattered on bare grass.
     */
    public static List<int[]> planBlocks(String districtId, int x0, int z0, int x1, int z1, long seed) {
        return layBlocks(x0, z0, x1, z1, new Random(seed ^ districtId.hashCode()));
    }

    public static List<Parcel> plan(String districtId, int x0, int z0, int x1, int z1,
                                     long seed, Parcel.ParcelRole defaultRole) {
        Random rng = new Random(seed ^ districtId.hashCode());
        List<Parcel> parcels = new ArrayList<>();

        List<int[]> blocks = layBlocks(x0, z0, x1, z1, rng);
        int index = 0;
        for (int[] b : blocks) {
            index = subdivideBlock(parcels, districtId, b, rng, index, defaultRole);
        }
        return parcels;
    }

    /** Split the district into street-separated city blocks with irregular sizes. */
    private static List<int[]> layBlocks(int x0, int z0, int x1, int z1, Random rng) {
        List<int[]> blocks = new ArrayList<>();
        int z = z0;
        boolean majorRow = true;
        while (z <= z1) {
            int depth = MIN_BLOCK + rng.nextInt(MAX_BLOCK - MIN_BLOCK + 1);
            int zEnd = Math.min(z + depth - 1, z1);
            if (zEnd - z < MIN_DEPTH * 2) {
                break;
            }
            int x = x0;
            while (x <= x1) {
                int width = MIN_BLOCK + rng.nextInt(MAX_BLOCK - MIN_BLOCK + 1);
                int xEnd = Math.min(x + width - 1, x1);
                if (xEnd - x >= MIN_FRONT * 2) {
                    blocks.add(new int[]{x, z, xEnd, zEnd});
                }
                x = xEnd + 1 + (rng.nextInt(3) == 0 ? STREET_COLLECTOR : STREET_LANE);
            }
            z = zEnd + 1 + (majorRow ? STREET_COLLECTOR : STREET_LANE);
            majorRow = !majorRow;
        }
        return blocks;
    }

    /**
     * Subdivide a block's four street-facing edges into plots.
     *
     * <p>Corner ownership: the north and south terraces run the block's <em>full</em> width and own
     * the corners; the east and west terraces are then trimmed by exactly those two depths. An
     * earlier version instead trimmed every edge by the max plot depth at both ends, which on a
     * typical 36x36 block left a 3-block run - under the 7-block minimum frontage - so most edges
     * silently produced no plots at all and the town came out at 486 buildings instead of 1,100+.
     * Depths are fixed per block edge rather than per plot, which is also how a real terrace reads:
     * a shared build line.</p>
     */
    private static int subdivideBlock(List<Parcel> out, String districtId, int[] block, Random rng,
                                       int index, Parcel.ParcelRole defaultRole) {
        int bx0 = block[0], bz0 = block[1], bx1 = block[2], bz1 = block[3];
        int blockW = bx1 - bx0 + 1;
        int blockD = bz1 - bz0 + 1;

        int dN = edgeDepth(rng, blockD / 2 - 2);
        int dS = edgeDepth(rng, blockD / 2 - 2);
        int dW = edgeDepth(rng, blockW / 2 - 2);
        int dE = edgeDepth(rng, blockW / 2 - 2);

        index = runEdge(out, districtId, rng, index, defaultRole, block,
                Parcel.Facing.NORTH, dN, bx0, bx1);
        index = runEdge(out, districtId, rng, index, defaultRole, block,
                Parcel.Facing.SOUTH, dS, bx0, bx1);
        int sideFrom = bz0 + dN;
        int sideTo = bz1 - dS;
        index = runEdge(out, districtId, rng, index, defaultRole, block,
                Parcel.Facing.WEST, dW, sideFrom, sideTo);
        index = runEdge(out, districtId, rng, index, defaultRole, block,
                Parcel.Facing.EAST, dE, sideFrom, sideTo);
        return index;
    }

    private static int edgeDepth(Random rng, int cap) {
        int hi = Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, cap));
        return MIN_DEPTH + rng.nextInt(Math.max(1, hi - MIN_DEPTH + 1));
    }

    private static int runEdge(List<Parcel> out, String districtId, Random rng, int index,
                                Parcel.ParcelRole defaultRole, int[] block,
                                Parcel.Facing facing, int depth, int runStart, int runEnd) {
        int bx0 = block[0], bz0 = block[1], bx1 = block[2], bz1 = block[3];
        if (runEnd - runStart + 1 < MIN_FRONT) {
            return index;
        }

        int cursor = runStart;
        int lastVariant = -1;
        int consecutive = 0;
        while (cursor + MIN_FRONT - 1 <= runEnd) {
            int front = MIN_FRONT + rng.nextInt(MAX_FRONT - MIN_FRONT + 1);
            if (cursor + front - 1 > runEnd) {
                front = runEnd - cursor + 1;
                if (front < MIN_FRONT) {
                    break;
                }
                if (front > CORNER_MAX_W) {
                    front = CORNER_MAX_W;
                }
            }

            int pMinX, pMaxX, pMinZ, pMaxZ;
            switch (facing) {
                case NORTH -> {
                    pMinX = cursor;
                    pMaxX = cursor + front - 1;
                    pMinZ = bz0;
                    pMaxZ = bz0 + depth - 1;
                }
                case SOUTH -> {
                    pMinX = cursor;
                    pMaxX = cursor + front - 1;
                    pMinZ = bz1 - depth + 1;
                    pMaxZ = bz1;
                }
                case WEST -> {
                    pMinZ = cursor;
                    pMaxZ = cursor + front - 1;
                    pMinX = bx0;
                    pMaxX = bx0 + depth - 1;
                }
                default -> {
                    pMinZ = cursor;
                    pMaxZ = cursor + front - 1;
                    pMinX = bx1 - depth + 1;
                    pMaxX = bx1;
                }
            }

            cursor += front + (rng.nextInt(6) == 0 ? 3 : 0);   // occasional service alley

            if (ExclusionZones.rectBlocked(pMinX, pMinZ, pMaxX, pMaxZ)) {
                continue;
            }

            // Repetition guard: the spec forbids the same façade/plan/roof combination on more
            // than four consecutive plots, so reroll a variant that would make a fifth.
            int variant = rng.nextInt(BuildingPalette.VARIANT_COUNT);
            if (variant == lastVariant) {
                consecutive++;
                if (consecutive >= 3) {
                    variant = (variant + 1 + rng.nextInt(BuildingPalette.VARIANT_COUNT - 1))
                            % BuildingPalette.VARIANT_COUNT;
                    consecutive = 0;
                }
            } else {
                consecutive = 0;
            }
            lastVariant = variant;

            Parcel.ParcelRole role = pickRole(defaultRole, rng);
            String id = "f01/tob/" + districtId + "/parcel_" + String.format("%04d", index++);
            out.add(new Parcel(id, districtId, pMinX, pMinZ, pMaxX, pMaxZ, facing, role, variant));
        }
        return index;
    }

    private static Parcel.ParcelRole pickRole(Parcel.ParcelRole defaultRole, Random rng) {
        int roll = rng.nextInt(100);
        if (defaultRole == Parcel.ParcelRole.HOME) {
            return roll < 82 ? Parcel.ParcelRole.HOME
                    : roll < 92 ? Parcel.ParcelRole.SHOP : Parcel.ParcelRole.WORKSHOP;
        }
        if (defaultRole == Parcel.ParcelRole.SHOP) {
            return roll < 62 ? Parcel.ParcelRole.SHOP
                    : roll < 80 ? Parcel.ParcelRole.HOME
                    : roll < 92 ? Parcel.ParcelRole.WORKSHOP : Parcel.ParcelRole.INN;
        }
        if (defaultRole == Parcel.ParcelRole.WORKSHOP) {
            return roll < 55 ? Parcel.ParcelRole.WORKSHOP
                    : roll < 72 ? Parcel.ParcelRole.HOME
                    : roll < 88 ? Parcel.ParcelRole.SHOP : Parcel.ParcelRole.INN;
        }
        return defaultRole;
    }
}
