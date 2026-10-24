package com.skyclone.gen;

/**
 * Where a shape gets its block from, per position. A plain block is {@code Fill.of("stone")};
 * a {@link Palette} is a Fill that varies deterministically by position.
 */
@FunctionalInterface
public interface Fill {

    String at(int x, int y, int z);

    static Fill of(String block) {
        String b = block;
        return (x, y, z) -> b;
    }

    /** A palette spec ("stone_bricks:6,mossy_stone_bricks:2") or a single block. */
    static Fill parse(String spec) { return Palette.of(spec); }
}
