import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.PreviewRenderer;
import com.skyclone.gen.Walkability;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * Views for CAVE islands, where the normal top-down preview only shows the ceiling.
 *
 *   java HollowsView <generator class> <out dir> <view>...
 *   view = cut:<name>:<maxY>                      top-down cutaway of everything at or below maxY
 *        | iso:<name>:x0,y0,z0,x1,y1,z1           isometric of a box (SE camera)
 *        | isonw:<name>:x0,y0,z0,x1,y1,z1         isometric of a box (NW camera)
 *        | walk:<name>:x0,y0,z0,x1,y1,z1          walkability map of a box (green reachable)
 *        | top:<name>:x0,y0,z0,x1,y1,z1           top-down of a box
 */
public final class HollowsView {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        IslandGenerator gen = (IslandGenerator) Class.forName(args[0]).getDeclaredConstructor().newInstance();
        File dir = new File(args[1]);
        dir.mkdirs();
        long seed = IslandRun.seedOf(gen.islandId());
        IslandRun.Result run = IslandRun.run(gen, seed);
        System.out.println("generated in " + run.generateMillis + " ms");
        Walkability.Report walk = null;
        Box all = gen.bounds();
        for (int i = 2; i < args.length; i++) {
            String[] p = args[i].split(":");
            String kind = p[0], name = p[1];
            Box b;
            if (kind.equals("cut")) {
                int maxY = Integer.parseInt(p[2]);
                int minY = p.length > 3 ? Integer.parseInt(p[3]) : all.minY();
                ImageIO.write(cut(run.canvas, all, maxY, minY), "png", new File(dir, name + ".png"));
            } else {
                String[] c = p[2].split(",");
                b = Box.of(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]), Integer.parseInt(c[4]), Integer.parseInt(c[5]));
                switch (kind) {
                    case "iso" -> ImageIO.write(PreviewRenderer.iso(run.canvas, b, true, run.layout, name), "png", new File(dir, name + ".png"));
                    case "isonw" -> ImageIO.write(PreviewRenderer.iso(run.canvas, b, false, run.layout, name), "png", new File(dir, name + ".png"));
                    case "top" -> ImageIO.write(PreviewRenderer.topDown(run.canvas, b, name), "png", new File(dir, name + ".png"));
                    case "walk" -> {
                        if (walk == null) {
                            Walkability.Options o = new Walkability.Options();
                            o.skipFloating = true;
                            walk = Walkability.analyse(run.canvas, run.layout, o);
                        }
                        ImageIO.write(PreviewRenderer.walk(run.canvas, b, run.layout, walk, name), "png", new File(dir, name + ".png"));
                    }
                    default -> System.out.println("unknown view " + kind);
                }
            }
            System.out.println("wrote " + new File(dir, name + ".png").getAbsolutePath());
        }
    }

    /**
     * Cutaway: for every column, go down from maxY through rock to the first air, then on to the floor
     * under that air, and paint the floor (shaded by height; dark where the column is solid to minY).
     * Rock cut by the plane is drawn dark grey, so caves read as lit shapes on a dark ground.
     */
    static java.awt.image.BufferedImage cut(com.skyclone.gen.MemoryCanvas c, Box all, int maxY, int minY) {
        int s = 3;
        int w = all.maxX() - all.minX() + 1, d = all.maxZ() - all.minZ() + 1;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w * s, d * s, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int z = all.minZ(); z <= all.maxZ(); z++)
            for (int x = all.minX(); x <= all.maxX(); x++) {
                int y = maxY;
                while (y >= minY && !c.isAir(x, y, z)) y--;
                int col;
                if (y < minY) col = 0x202020;
                else {
                    while (y >= minY && c.isAir(x, y, z)) y--;
                    if (y < minY) col = 0x000000;
                    else {
                        col = com.skyclone.gen.BlockColors.rgb(c.get(x, y, z));
                        col = com.skyclone.gen.BlockColors.shade(col, 0.55 + 0.6 * (y - minY) / Math.max(1.0, maxY - minY));
                    }
                }
                for (int a = 0; a < s; a++) for (int bb = 0; bb < s; bb++) img.setRGB((x - all.minX()) * s + a, (z - all.minZ()) * s + bb, col);
            }
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(255, 255, 255, 60));
        for (int k = all.minX(); k <= all.maxX(); k++) if (k % 32 == 0) g.drawLine((k - all.minX()) * s, 0, (k - all.minX()) * s, d * s);
        for (int k = all.minZ(); k <= all.maxZ(); k++) if (k % 32 == 0) g.drawLine(0, (k - all.minZ()) * s, w * s, (k - all.minZ()) * s);
        g.setColor(java.awt.Color.WHITE);
        g.drawString("cut y<=" + maxY + "  grid 32  x " + all.minX() + ".." + all.maxX() + "  z " + all.minZ() + ".." + all.maxZ(), 8, 14);
        g.dispose();
        return img;
    }
}
