package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** The text report for one generator run: what was built, where everything is, what is wrong. */
public final class IslandReport {

    private IslandReport() {}

    public static String text(IslandGenerator gen, IslandRun.Result run, Walkability.Report walk) {
        StringBuilder sb = new StringBuilder();
        MemoryCanvas c = run.canvas;
        Layout l = run.layout;
        sb.append("ISLAND ").append(gen.islandId()).append("   generator ").append(gen.getClass().getName())
                .append("   version ").append(gen.version()).append("   seed ").append(run.seed).append('\n');
        sb.append(String.format(Locale.ROOT, "  generated in %,d ms; %,d cells written, %,d blocks (non-air), %d distinct states, %d fence/pane/wall connections fixed%n",
                run.generateMillis, c.written(), c.blockCount(), c.paletteSize() - 1, run.connected));
        Box b = c.bounds();
        sb.append("  declared bounds ").append(Walkability.boxStr(gen.bounds())).append('\n');
        sb.append("  actual bounds   ").append(b == null ? "(empty)" : Walkability.boxStr(b)).append('\n');
        if (b != null) sb.append(String.format(Locale.ROOT, "  size %d x %d x %d (x, y, z)%n", b.maxX() - b.minX() + 1, b.maxY() - b.minY() + 1, b.maxZ() - b.minZ() + 1));
        if (run.outsideBounds > 0)
            sb.append("  !! ").append(run.outsideBounds).append(" writes OUTSIDE the declared bounds (they will not be cleared on rebuild): ")
                    .append(run.outsideSamples).append('\n');
        // ---- block names ----
        List<String> unknown = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        c.forEachId((x, y, z, id) -> {
            String n = BlockState.name(c.palette(id));
            counts.merge(n, 1, Integer::sum);
        });
        for (String n : counts.keySet()) if (!KnownBlocks.known(n)) unknown.add(n + " x" + counts.get(n));
        if (!KnownBlocks.available()) sb.append("  (block-name check unavailable: gen/known_blocks.txt not on the classpath)\n");
        else if (!unknown.isEmpty()) sb.append("  !! UNKNOWN BLOCK NAMES (they become stone in game): ").append(unknown).append('\n');
        else sb.append("  all ").append(counts.size()).append(" block names are valid\n");
        List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort((a, bb) -> Integer.compare(bb.getValue(), a.getValue()));
        sb.append("  most used: ");
        for (int i = 0; i < Math.min(14, top.size()); i++) sb.append(top.get(i).getKey()).append(' ').append(String.format(Locale.ROOT, "%,d", top.get(i).getValue())).append(i < 13 ? ", " : "");
        sb.append('\n');
        // ---- layout ----
        sb.append(String.format(Locale.ROOT, "LAYOUT  %d anchors, %d areas, %d zones, %d data%n", l.anchors().size(), l.areas().size(), l.zones().size(), l.data().size()));
        for (Layout.Anchor a : l.anchors().values())
            sb.append(String.format(Locale.ROOT, "  anchor %-34s %8.1f %6.1f %8.1f  yaw %.0f%n", a.name(), a.x(), a.y(), a.z(), a.yaw()));
        for (Map.Entry<String, Box> e : l.areas().entrySet())
            sb.append(String.format(Locale.ROOT, "  area   %-34s %s%n", e.getKey(), Walkability.boxStr(e.getValue())));
        for (Layout.Zone z : l.zones())
            sb.append(String.format(Locale.ROOT, "  zone   %-20s &%c%-22s %s%s%s%n", z.id(), z.color(), z.displayName(), Walkability.boxStr(z.box()),
                    z.flags().isEmpty() ? "" : " " + z.flags(), z.requirement() == null ? "" : " requires " + z.requirement()));
        for (Map.Entry<String, String> e : l.data().entrySet())
            sb.append(String.format(Locale.ROOT, "  data   %-34s %s%n", e.getKey(), e.getValue()));
        if (walk != null) sb.append(walk.text());
        return sb.toString();
    }
}
