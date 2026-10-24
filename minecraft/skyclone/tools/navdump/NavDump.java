package navdump;

import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.MemoryCanvas;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Cuts a box out of a generated island and writes it as a TestPilot nav fixture, so the walker's
 * planner ({@code Nav}) can be tested against the REAL geometry a playtest got stuck in, with no
 * server and no client. Run through {@code tools/navdump.sh}.
 *
 * <p>Format (gzip, UTF-8 text):
 * <pre>
 *   NAVDUMP 1 &lt;generator&gt; &lt;seed&gt; x1 y1 z1 x2 y2 z2
 *   P &lt;id&gt; &lt;block string&gt;          one per palette entry (id 0 = air, never listed)
 *   A &lt;name&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;            every layout anchor inside the box
 *   C &lt;x&gt; &lt;z&gt; &lt;y&gt;:&lt;id&gt;:&lt;n&gt; ...     non-air runs up the column, only for columns with any
 * </pre>
 * Unwritten cells are air, as in the game's void worlds.
 */
public final class NavDump {

    public static void main(String[] args) throws Exception {
        if (args.length < 8) {
            System.err.println("usage: NavDump <generator class> <out.txt.gz> x1 y1 z1 x2 y2 z2 [seed]");
            System.exit(2);
        }
        IslandGenerator gen = (IslandGenerator) Class.forName(args[0]).getDeclaredConstructor().newInstance();
        String id = gen.islandId() == null || gen.islandId().isBlank() ? gen.getClass().getSimpleName() : gen.islandId();
        int x1 = Integer.parseInt(args[2]), y1 = Integer.parseInt(args[3]), z1 = Integer.parseInt(args[4]);
        int x2 = Integer.parseInt(args[5]), y2 = Integer.parseInt(args[6]), z2 = Integer.parseInt(args[7]);
        long seed = args.length > 8 ? Long.parseLong(args[8]) : IslandRun.seedOf(id);
        System.out.println("Generating " + id + " (seed " + seed + ") ...");
        IslandRun.Result run = IslandRun.run(gen, seed);
        MemoryCanvas c = run.canvas;
        Map<String, Integer> pal = new HashMap<>();
        StringBuilder body = new StringBuilder();
        StringBuilder head = new StringBuilder();
        int columns = 0;
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                StringBuilder col = new StringBuilder();
                int runId = 0, runY = 0, runN = 0;
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2) + 1; y++) {
                    int pid = 0;
                    if (y <= Math.max(y1, y2) && !c.isAir(x, y, z)) {
                        String b = c.get(x, y, z);
                        Integer known = pal.get(b);
                        if (known == null) {
                            known = pal.size() + 1;
                            pal.put(b, known);
                            head.append("P ").append(known).append(' ').append(b).append('\n');
                        }
                        pid = known;
                    }
                    if (pid == runId && runN > 0) {
                        runN++;
                        continue;
                    }
                    if (runId != 0 && runN > 0) {
                        col.append(' ').append(runY).append(':').append(runId).append(':').append(runN);
                    }
                    runId = pid;
                    runY = y;
                    runN = 1;
                }
                if (col.length() > 0) {
                    body.append("C ").append(x).append(' ').append(z).append(col).append('\n');
                    columns++;
                }
            }
        }
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new FileOutputStream(args[1])), StandardCharsets.UTF_8))) {
            w.write("NAVDUMP 1 " + args[0] + " " + seed + " " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + "\n");
            w.write(head.toString());
            // anchors inside the box, so a test can start where an NPC or a warp puts the player
            for (var a : run.layout.anchors().values()) {
                if (a.x() >= Math.min(x1, x2) && a.x() <= Math.max(x1, x2) + 1 && a.z() >= Math.min(z1, z2)
                        && a.z() <= Math.max(z1, z2) + 1 && a.y() >= Math.min(y1, y2) && a.y() <= Math.max(y1, y2) + 1) {
                    w.write("A " + a.name() + " " + a.x() + " " + a.y() + " " + a.z() + "\n");
                }
            }
            w.write(body.toString());
        }
        System.out.println("WROTE " + args[1] + " (" + pal.size() + " block states, " + columns + " columns)");
    }
}
