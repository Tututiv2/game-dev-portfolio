package com.skyclone.mod.island;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.island.gen.PrivateIslandGenerator;

import java.util.ArrayList;
import java.util.List;

/** Pure checks shared by the self-tests and the unit tests. Null = fine, else what is wrong. */
final class IslandChecks {

    private IslandChecks() {}

    /** Anchors/areas other modules and this one depend on (CONTRACT §4 table: spawn, npc.island_helper, area build). */
    static final List<String> REQUIRED_ANCHORS = List.of("spawn", "npc.island_helper", "minion.pad.1", "minion.pad.5");
    static final List<String> REQUIRED_AREAS = List.of("build", "portal.hub", "quarry", "minions");

    static String layoutProblems(Layout l) {
        List<String> missing = new ArrayList<>();
        for (String a : REQUIRED_ANCHORS) if (l.anchor(a) == null) missing.add("anchor " + a);
        for (String a : REQUIRED_AREAS) if (l.area(a) == null) missing.add("area " + a);
        // the Portal Isle (v4): every destination has its walk-in opening and a standing spot in front of it
        for (String[] d : PrivateIslandGenerator.ALL_PORTALS) {
            Box b = l.area(PrivateIslandGenerator.PORTAL_AREA + d[0]);
            if (b == null) missing.add("area " + PrivateIslandGenerator.PORTAL_AREA + d[0]);
            else if (b.maxX() - b.minX() != 1 || b.maxY() - b.minY() != 2 || b.minZ() != b.maxZ())
                missing.add("area " + PrivateIslandGenerator.PORTAL_AREA + d[0] + " is not a 2x3 opening: " + b);
            if (l.anchor(PrivateIslandGenerator.PORTAL_AREA + d[0]) == null) missing.add("anchor " + PrivateIslandGenerator.PORTAL_AREA + d[0]);
        }
        if (l.area("far_court") == null) missing.add("area far_court");
        if (!missing.isEmpty()) return "missing " + missing;
        int souls = 0;
        for (String n : l.anchors().keySet()) if (n.startsWith("fairy_soul")) souls++;
        if (souls < 10 || souls > 40) return souls + " fairy souls (want 10-40)";
        boolean buildZone = false;
        for (Layout.Zone z : l.zones()) {
            if (!z.flags().contains(Layout.Zone.BUILD)) return "zone " + z.id() + " lacks BUILD: building would be refused inside it";
            Box b = z.box();
            if (b.minX() <= -PrivateIslandGenerator.MAX_HALF && b.maxX() >= PrivateIslandGenerator.MAX_HALF
                    && b.minZ() <= -PrivateIslandGenerator.MAX_HALF && b.maxZ() >= PrivateIslandGenerator.MAX_HALF) buildZone = true;
        }
        if (!buildZone) return "no BUILD zone covers the largest island size (" + (2 * PrivateIslandGenerator.MAX_HALF + 1) + " wide)";
        Box build = l.area("build");
        int h = IslandMath.half(IslandMath.BASE_SIZE);
        if (build.minX() != -h || build.maxX() != h || build.minZ() != -h || build.maxZ() != h)
            return "area build " + build + " is not the default " + IslandMath.BASE_SIZE + "-wide square";
        Layout.Anchor spawn = l.anchor("spawn");
        if (!build.containsXZ(spawn.x(), spawn.z())) return "spawn is outside the build area";
        return null;
    }

    /** Research 05 §6.4: Island Size 161 at tier 0, 241 at tier X; a shipped table must agree. */
    static String sizeProblems(int[] table) {
        if (IslandMath.sizeForTier(0, IslandMath.BASE_SIZE, IslandMath.SIZE_PER_TIER) != 161) return "tier 0 is not 161";
        if (IslandMath.sizeForTier(10, IslandMath.BASE_SIZE, IslandMath.SIZE_PER_TIER) != 241) return "tier X is not 241";
        if (2 * PrivateIslandGenerator.MAX_HALF + 1 != 241) return "MAX_HALF does not match 241";
        if (table != null && table.length > 0) {
            if (table[table.length - 1] != 241) return "community shop Island Size top tier is " + table[table.length - 1] + ", research says 241";
            for (int i = 1; i < table.length; i++) if (table[i] <= table[i - 1]) return "community shop Island Size table is not increasing";
            if (table[0] <= IslandMath.BASE_SIZE) return "community shop Island Size I (" + table[0] + ") is not bigger than the base 161";
        }
        return null;
    }
}
