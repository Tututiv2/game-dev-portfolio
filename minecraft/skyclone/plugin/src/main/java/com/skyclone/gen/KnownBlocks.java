package com.skyclone.gen;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The block ids this Minecraft version knows (resource {@code gen/known_blocks.txt}, extracted
 * from the server API). Lets the preview tool flag typos ("stone_brick" instead of
 * "stone_bricks", "chain" instead of "iron_chain") before they turn into stone in game.
 * If the resource is missing every name is accepted.
 */
public final class KnownBlocks {

    private KnownBlocks() {}

    private static volatile Set<String> names;

    public static Set<String> names() {
        Set<String> n = names;
        if (n != null) return n;
        Set<String> s = new HashSet<>();
        try (InputStream in = KnownBlocks.class.getClassLoader().getResourceAsStream("gen/known_blocks.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) { line = line.trim(); if (!line.isEmpty()) s.add(line); }
            }
        } catch (Exception ignored) { }
        names = n = Collections.unmodifiableSet(s);
        return n;
    }

    public static boolean available() { return !names().isEmpty(); }

    /** True if the block's name (state stripped) exists, or if the list is unavailable. */
    public static boolean known(String state) {
        Set<String> n = names();
        return n.isEmpty() || n.contains(BlockState.name(BlockState.normalise(state)));
    }
}
