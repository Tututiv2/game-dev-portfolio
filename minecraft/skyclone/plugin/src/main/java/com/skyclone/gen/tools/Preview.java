package com.skyclone.gen.tools;

import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.gen.IslandReport;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.PreviewRenderer;
import com.skyclone.gen.Walkability;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Offline preview of an island generator (no server):
 * <pre>
 *   java -cp ... com.skyclone.gen.tools.Preview com.skyclone.mod.hub.HubGenerator previews [seed]
 * </pre>
 * Extra args: a seed (number) and/or {@code crop=x1,z1,x2,z2} for zoomed crop_*.png of one district.
 * Runs the generator into memory, floods it for walkability and writes
 * {@code <out>/<islandId>/top.png, iso.png, iso_nw.png, walk.png, report.txt}.
 * Exit code 1 when spawn is missing or any anchor/portal is unreachable or inside a block;
 * 2 on a crash. Normally run through {@code tools/preview.sh}.
 */
public final class Preview {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        if (args.length < 2) {
            System.err.println("usage: Preview <generator class> <output dir> [seed]");
            System.exit(2);
        }
        IslandGenerator gen;
        try {
            gen = (IslandGenerator) Class.forName(args[0]).getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            System.err.println("Could not create " + args[0] + " (it needs a public no-arg constructor): " + t);
            System.exit(2);
            return;
        }
        String id = gen.islandId() == null || gen.islandId().isBlank() ? gen.getClass().getSimpleName() : gen.islandId();
        Long seedArg = null;
        com.skyclone.api.world.gen.Box crop = null;
        for (int i = 2; i < args.length; i++) {
            String a = args[i].trim();
            if (a.isEmpty()) continue;
            if (a.startsWith("crop=")) {
                String[] p = a.substring(5).split(",");
                if (p.length != 4) { System.err.println("crop=x1,z1,x2,z2"); System.exit(2); }
                crop = com.skyclone.api.world.gen.Box.of(Integer.parseInt(p[0].trim()), 0, Integer.parseInt(p[1].trim()),
                        Integer.parseInt(p[2].trim()), 0, Integer.parseInt(p[3].trim()));
            } else seedArg = Long.parseLong(a);
        }
        long seed = seedArg != null ? seedArg : IslandRun.seedOf(id);
        File dir = new File(args[1], id);
        System.out.println("Generating " + gen.getClass().getName() + " (island " + id + ", seed " + seed + ") ...");
        IslandRun.Result run;
        try {
            run = IslandRun.run(gen, seed);
        } catch (Throwable t) {
            System.err.println("GENERATOR CRASHED:");
            t.printStackTrace();
            System.exit(2);
            return;
        }
        System.out.println("  generated in " + run.generateMillis + " ms, flooding for walkability ...");
        Walkability.Report walk = Walkability.analyse(run.canvas, run.layout, new Walkability.Options());
        String report = IslandReport.text(gen, run, walk);
        dir.mkdirs();
        File rep = new File(dir, "report.txt");
        Files.writeString(rep.toPath(), report, StandardCharsets.UTF_8);
        long t0 = System.currentTimeMillis();
        List<File> pngs = PreviewRenderer.writeAll(run.canvas, run.layout, walk, dir, id + " v" + gen.version() + " seed " + seed);
        if (crop != null) pngs.addAll(PreviewRenderer.writeCrop(run.canvas, run.layout, walk, dir, id + " v" + gen.version(), crop));
        System.out.println(report);
        System.out.println("  rendered in " + (System.currentTimeMillis() - t0) + " ms");
        System.out.println("WROTE:");
        System.out.println("  " + rep.getAbsolutePath());
        for (File f : pngs) System.out.println("  " + f.getAbsolutePath());
        if (!walk.ok()) {
            System.out.println("RESULT: PROBLEMS (see the FAIL / SOLID lines above and the red X marks in walk.png)");
            System.exit(1);
        }
        System.out.println("RESULT: OK");
    }
}
