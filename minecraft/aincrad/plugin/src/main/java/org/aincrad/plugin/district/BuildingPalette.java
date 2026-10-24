package org.aincrad.plugin.district;

import org.bukkit.Material;

/**
 * Weighted deterministic material families, per the spec's balance targets: pale stone/calcite
 * walls with dark oak or spruce framing and stone-brick footings, and roofs running roughly
 * 65-75% red/brown terracotta family, 15-25% slate/dark family, and no more than 10% special
 * civic/copper. The variant index (0..{@link #VARIANT_COUNT}-1) is distributed to hit those
 * proportions directly rather than by chance.
 */
public final class BuildingPalette {

    public static final int VARIANT_COUNT = 10;

    private final Material wall;
    private final Material frame;
    private final Material footing;
    private final Material roof;      // stairs material
    private final Material roofSolid; // matching full block for the ridge
    private final Material floorMat;

    private BuildingPalette(Material wall, Material frame, Material footing,
                             Material roof, Material roofSolid, Material floorMat) {
        this.wall = wall;
        this.frame = frame;
        this.footing = footing;
        this.roof = roof;
        this.roofSolid = roofSolid;
        this.floorMat = floorMat;
    }

    public Material wall() {
        return wall;
    }

    public Material frame() {
        return frame;
    }

    public Material footing() {
        return footing;
    }

    public Material roofStairs() {
        return roof;
    }

    public Material roofSolid() {
        return roofSolid;
    }

    public Material floorMaterial() {
        return floorMat;
    }

    /**
     * Variants 0-6 are the terracotta family (70%), 7-8 slate/dark (20%), 9 civic copper (10%).
     */
    public static BuildingPalette of(int variant, Parcel.ParcelRole role) {
        Material wall = switch (variant % 4) {
            case 0 -> Material.CALCITE;
            case 1 -> Material.SMOOTH_SANDSTONE;
            case 2 -> Material.POLISHED_DIORITE;
            default -> Material.WHITE_TERRACOTTA;
        };
        Material frame = (variant % 2 == 0) ? Material.DARK_OAK_LOG : Material.SPRUCE_LOG;
        Material footing = (variant % 3 == 0) ? Material.STONE_BRICKS : Material.ANDESITE;
        Material floorMat = (variant % 2 == 0) ? Material.SPRUCE_PLANKS : Material.DARK_OAK_PLANKS;

        Material roofStairs;
        Material roofSolid;
        if (variant <= 6) {
            if (variant % 3 == 0) {
                roofStairs = Material.BRICK_STAIRS;
                roofSolid = Material.BRICKS;
            } else if (variant % 3 == 1) {
                roofStairs = Material.GRANITE_STAIRS;
                roofSolid = Material.GRANITE;
            } else {
                roofStairs = Material.MUD_BRICK_STAIRS;
                roofSolid = Material.MUD_BRICKS;
            }
        } else if (variant <= 8) {
            roofStairs = Material.DEEPSLATE_TILE_STAIRS;
            roofSolid = Material.DEEPSLATE_TILES;
        } else {
            roofStairs = Material.CUT_COPPER_STAIRS;
            roofSolid = Material.CUT_COPPER;
        }

        if (role == Parcel.ParcelRole.CIVIC) {
            wall = Material.POLISHED_ANDESITE;
            roofStairs = Material.CUT_COPPER_STAIRS;
            roofSolid = Material.CUT_COPPER;
        }
        return new BuildingPalette(wall, frame, footing, roofStairs, roofSolid, floorMat);
    }
}
