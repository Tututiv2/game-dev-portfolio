import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.gen.IslandReport;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.MemoryCanvas;
import com.skyclone.gen.PreviewRenderer;
import com.skyclone.gen.Walkability;
import com.skyclone.gen.util.LongIntMap;
import com.skyclone.gen.util.LongSet;
import com.skyclone.gen.util.Pack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Cutaway previews for UNDERGROUND islands (the mines module's own tool, not part of the plugin).
 * tools/preview.sh renders the topmost block of every column, which for a cave is its roof. This
 * runs the same generator, the same walkability flood, and then renders horizontal SLICES with
 * everything above the slice removed, so the floor of each cave level can be seen.
 *
 *   java MinesCutaway <generator class> <out dir> <lo:hi:name>... [crop=x1,z1,x2,z2]
 *
 * Writes <out>/<island>/cut_<name>_top.png, _iso.png, _iso_nw.png, _walk.png and report.txt.
 */
public final class MinesCutaway {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        IslandGenerator gen = (IslandGenerator) Class.forName(args[0]).getDeclaredConstructor().newInstance();
        File dir = new File(args[1], gen.islandId());
        dir.mkdirs();
        Box crop = null;
        for (int i = 2; i < args.length; i++) if (args[i].startsWith("crop=")) {
            String[] p = args[i].substring(5).split(",");
            crop = Box.of(Integer.parseInt(p[0]), 0, Integer.parseInt(p[1]), Integer.parseInt(p[2]), 0, Integer.parseInt(p[3]));
        }
        long seed = IslandRun.seedOf(gen.islandId());
        IslandRun.Result run = IslandRun.run(gen, seed);
        System.out.println("generated in " + run.generateMillis + " ms, " + run.canvas.blockCount() + " blocks");
        Walkability.Report walk = Walkability.analyse(run.canvas, run.layout, new Walkability.Options());
        String report = IslandReport.text(gen, run, walk);
        Files.writeString(new File(dir, "report.txt").toPath(), report, StandardCharsets.UTF_8);
        System.out.println(report);
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("crop=")) continue;
            String[] s = args[i].split(":");
            int lo = Integer.parseInt(s[0]), hi = Integer.parseInt(s[1]);
            String name = s[2];
            MemoryCanvas cut = new MemoryCanvas();
            final Box cr = crop;
            run.canvas.forEach((x, y, z, b) -> {
                if (y < lo || y > hi) return;
                if (cr != null && !cr.containsXZ(x, z)) return;
                cut.set(x, y, z, b);
            });
            Box b = cut.bounds();
            if (b == null) { System.out.println("slice " + name + " is empty"); continue; }
            b = b.grow(2);
            Walkability.Report r = new Walkability.Report();
            r.stand = new LongIntMap();
            r.reachable = new LongSet();
            walk.stand.forEach((k, v) -> { int y = Pack.y(k); if (y >= lo && y <= hi) r.stand.put(k, v); });
            walk.reachable.forEach(k -> { int y = Pack.y(k); if (y >= lo && y <= hi) r.reachable.add(k); });
            String title = gen.islandId() + " v" + gen.version() + " slice " + name + " y" + lo + ".." + hi;
            write(PreviewRenderer.topDown(cut, b, title), new File(dir, "cut_" + name + "_top.png"));
            write(PreviewRenderer.iso(cut, b, true, run.layout, title), new File(dir, "cut_" + name + "_iso.png"));
            write(PreviewRenderer.iso(cut, b, false, run.layout, title), new File(dir, "cut_" + name + "_iso_nw.png"));
            write(PreviewRenderer.walk(cut, b, run.layout, r, title), new File(dir, "cut_" + name + "_walk.png"));
            System.out.println("wrote slice " + name);
        }
        System.out.println(walk.ok() ? "RESULT: OK" : "RESULT: PROBLEMS");
        System.exit(walk.ok() ? 0 : 1);
    }

    private static void write(BufferedImage img, File f) throws Exception { ImageIO.write(img, "png", f); }
}
