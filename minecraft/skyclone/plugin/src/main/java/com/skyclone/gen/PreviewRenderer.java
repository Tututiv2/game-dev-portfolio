package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.util.Pack;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a canvas into pictures a builder can judge its work from:
 * <ul>
 *   <li><b>top.png</b> — map view, one cell per block (scaled up for small islands), coloured by the
 *       top block, shaded by height and by the slope to the north-west like a vanilla map, water
 *       tinted by depth, chunk grid and coordinate ticks in the margin.</li>
 *   <li><b>iso.png</b> / <b>iso_nw.png</b> — isometric 3D views from the south-east and the
 *       north-west: lit top faces, darker sides, outlined silhouettes, so buildings read as 3D.</li>
 *   <li><b>walk.png</b> — the walkability flood over a dimmed map: reachable ground green, standable
 *       but cut-off ground orange, anchors as dots (red X = unreachable), zones and portals outlined.</li>
 * </ul>
 */
public final class PreviewRenderer {

    private PreviewRenderer() {}

    private static final int BG = 0x161A22;
    private static final int MARGIN = 34;

    // =======================================================================================
    // shared top-down data
    // =======================================================================================

    static final class Top {
        final int minX, minZ, w, d, minY, maxY;
        final int[] y, id, rgb;

        Top(int minX, int minZ, int w, int d, int minY, int maxY) {
            this.minX = minX; this.minZ = minZ; this.w = w; this.d = d; this.minY = minY; this.maxY = maxY;
            y = new int[w * d]; id = new int[w * d]; rgb = new int[w * d];
            java.util.Arrays.fill(y, Integer.MIN_VALUE);
        }
    }

    static Top top(MemoryCanvas c, Box b) {
        Top t = new Top(b.minX(), b.minZ(), b.maxX() - b.minX() + 1, b.maxZ() - b.minZ() + 1, b.minY(), b.maxY());
        int n = c.paletteSize();
        boolean[] skip = new boolean[n];
        for (int i = 1; i < n; i++) { BlockInfo bi = c.info(i); skip[i] = bi.isAir() || bi.invisible; }
        c.forEachId((x, y, z, id) -> {
            if (skip[id]) return;
            int ix = x - t.minX, iz = z - t.minZ;
            if (ix < 0 || iz < 0 || ix >= t.w || iz >= t.d) return;
            int i = iz * t.w + ix;
            if (y > t.y[i]) { t.y[i] = y; t.id[i] = id; }
        });
        double range = Math.max(8, t.maxY - t.minY);
        for (int iz = 0; iz < t.d; iz++)
            for (int ix = 0; ix < t.w; ix++) {
                int i = iz * t.w + ix;
                if (t.y[i] == Integer.MIN_VALUE) { t.rgb[i] = BG; continue; }
                int x = t.minX + ix, z = t.minZ + iz, yy = t.y[i];
                BlockInfo info = c.info(t.id[i]);
                int col = BlockColors.rgb(c.palette(t.id[i]));
                if (info.water) {
                    int depth = 0, floorId = 0;
                    for (int k = yy - 1; k >= yy - 24; k--) {
                        int fid = c.getId(x, k, z);
                        if (fid != 0 && c.info(fid).water) { depth++; continue; }
                        floorId = fid;
                        break;
                    }
                    int floorCol = floorId == 0 ? 0x1A2A55 : BlockColors.rgb(c.palette(floorId));
                    col = BlockColors.mix(floorCol, 0x3460C8, Math.min(0.88, 0.5 + depth * 0.1));
                } else if (info.sprite || info.renderH <= 3) {
                    int below = c.getId(x, yy - 1, z);
                    if (below != 0) col = BlockColors.mix(BlockColors.rgb(c.palette(below)), col, info.sprite ? 0.45 : 0.7);
                }
                double hs = 0.74 + 0.42 * (yy - t.minY) / range;
                int nw = (ix > 0 && iz > 0) ? t.y[i - t.w - 1] : Integer.MIN_VALUE;
                double sl = 1;
                if (nw != Integer.MIN_VALUE) sl = 1 + Math.max(-0.28, Math.min(0.2, (yy - nw) * 0.07));
                else sl = 1.1;
                t.rgb[i] = BlockColors.shade(col, hs * sl);
            }
        return t;
    }

    /** Pixels per block for map views: aim for ~1200 px across, 1..12. */
    public static int mapScale(Box b) {
        int span = Math.max(b.maxX() - b.minX() + 1, b.maxZ() - b.minZ() + 1);
        return Math.max(1, Math.min(12, 1600 / Math.max(1, span)));
    }

    // =======================================================================================
    // top.png
    // =======================================================================================

    public static BufferedImage topDown(MemoryCanvas c, Box b, String title) {
        Top t = top(c, b);
        int s = mapScale(b);
        BufferedImage img = canvasImage(t.w * s, t.d * s);
        paintMap(img, t, s, 1.0, false);
        Graphics2D g = graphics(img);
        grid(g, t, s);
        ticks(g, t, s, title + "   (north is up, 1 cell = 1 block" + (s > 1 ? ", x" + s : "") + ")");
        g.dispose();
        return img;
    }

    private static BufferedImage canvasImage(int w, int h) {
        BufferedImage img = new BufferedImage(w + MARGIN * 2, h + MARGIN * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(BG));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
        return img;
    }

    private static void paintMap(BufferedImage img, Top t, int s, double bright, boolean gray) {
        for (int iz = 0; iz < t.d; iz++)
            for (int ix = 0; ix < t.w; ix++) {
                int col = t.rgb[iz * t.w + ix];
                if (gray && t.y[iz * t.w + ix] != Integer.MIN_VALUE) {
                    int r = (col >> 16) & 255, gg = (col >> 8) & 255, bb = col & 255;
                    int l = (int) ((r * 0.3 + gg * 0.59 + bb * 0.11) * bright);
                    col = BlockColors.mix((l << 16) | (l << 8) | l, col, 0.25);
                    col = BlockColors.shade(col, 0.75);
                }
                for (int py = 0; py < s; py++)
                    for (int px = 0; px < s; px++) img.setRGB(MARGIN + ix * s + px, MARGIN + iz * s + py, col);
            }
    }

    private static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        return g;
    }

    private static void grid(Graphics2D g, Top t, int s) {
        int step = s >= 3 ? 16 : 64;
        g.setColor(new Color(0, 0, 0, s >= 3 ? 38 : 50));
        for (int x = Math.floorDiv(t.minX, step) * step; x <= t.minX + t.w; x += step) {
            if (x < t.minX) continue;
            int px = MARGIN + (x - t.minX) * s;
            g.drawLine(px, MARGIN, px, MARGIN + t.d * s);
        }
        for (int z = Math.floorDiv(t.minZ, step) * step; z <= t.minZ + t.d; z += step) {
            if (z < t.minZ) continue;
            int pz = MARGIN + (z - t.minZ) * s;
            g.drawLine(MARGIN, pz, MARGIN + t.w * s, pz);
        }
        // the origin cross, which is where most islands are centred
        if (t.minX <= 0 && t.minX + t.w > 0 && t.minZ <= 0 && t.minZ + t.d > 0) {
            g.setColor(new Color(255, 255, 255, 70));
            int ox = MARGIN + (0 - t.minX) * s + s / 2, oz = MARGIN + (0 - t.minZ) * s + s / 2;
            g.drawLine(ox - 6, oz, ox + 6, oz);
            g.drawLine(ox, oz - 6, ox, oz + 6);
        }
    }

    private static void ticks(Graphics2D g, Top t, int s, String title) {
        int span = Math.max(t.w, t.d);
        int step = span <= 80 ? 8 : span <= 200 ? 16 : span <= 500 ? 32 : 64;
        g.setColor(new Color(0xC8CEDA));
        FontMetrics fm = g.getFontMetrics();
        for (int x = Math.floorDiv(t.minX, step) * step; x <= t.minX + t.w; x += step) {
            if (x < t.minX) continue;
            int px = MARGIN + (x - t.minX) * s;
            String lab = "x" + x;
            g.drawString(lab, px - fm.stringWidth(lab) / 2, MARGIN - 4);
            g.drawLine(px, MARGIN - 3, px, MARGIN);
        }
        for (int z = Math.floorDiv(t.minZ, step) * step; z <= t.minZ + t.d; z += step) {
            if (z < t.minZ) continue;
            int pz = MARGIN + (z - t.minZ) * s;
            String lab = "z" + z;
            g.drawString(lab, 2, pz + 4);
        }
        g.setColor(Color.WHITE);
        g.drawString(title, MARGIN, t.d * s + MARGIN + 20);
    }

    // =======================================================================================
    // iso.png
    // =======================================================================================

    /** Isometric view. {@code fromSouthEast} true: camera in the south-east; false: north-west. */
    public static BufferedImage iso(MemoryCanvas c, Box b, boolean fromSouthEast, Layout layout, String title) {
        int spanX = b.maxX() - b.minX() + 1, spanZ = b.maxZ() - b.minZ() + 1, spanY = b.maxY() - b.minY() + 1;
        int s = Math.max(2, Math.min(16, (2200 / Math.max(1, spanX + spanZ)) & ~1));
        int sign = fromSouthEast ? 1 : -1;
        // transformed X = sign*x, Z = sign*z ; projection px = (X - Z) * s, py = (X + Z) * s/2 - y * s
        // a block at x covers [x, x+1]; mirrored it covers [-x-1, -x], so its min corner is -x-1
        int tMinX = fromSouthEast ? b.minX() : -b.maxX() - 1, tMaxX = fromSouthEast ? b.maxX() + 1 : -b.minX();
        int tMinZ = fromSouthEast ? b.minZ() : -b.maxZ() - 1, tMaxZ = fromSouthEast ? b.maxZ() + 1 : -b.minZ();
        int pxMin = (tMinX - tMaxZ) * s, pxMax = (tMaxX - tMinZ) * s;
        int pyMin = (tMinX + tMinZ) * s / 2 - (b.maxY() + 1) * s, pyMax = (tMaxX + tMaxZ) * s / 2 - b.minY() * s;
        int W = pxMax - pxMin + 1 + 2 * MARGIN, H = pyMax - pyMin + 1 + 2 * MARGIN + 20;
        int offX = -pxMin + MARGIN, offY = -pyMin + MARGIN;
        int[] pix = new int[W * H];
        int[] zb = new int[W * H];
        java.util.Arrays.fill(zb, Integer.MIN_VALUE);
        for (int i = 0; i < pix.length; i++) {
            int yy = i / W;
            pix[i] = BlockColors.mix(0x1B2130, 0x0E1118, (double) yy / H);
        }
        int n = c.paletteSize();
        BlockInfo[] info = new BlockInfo[n];
        int[] col = new int[n];
        boolean[] occl = new boolean[n];
        for (int i = 1; i < n; i++) {
            info[i] = c.info(i);
            col[i] = BlockColors.rgb(c.palette(i));
            occl[i] = info[i].cube && !info[i].invisible;
        }
        Stencils st = new Stencils(s);
        double range = Math.max(8, b.maxY() - b.minY());
        final int fW = W, fH = H;
        c.forEachId((x, y, z, id) -> {
            BlockInfo bi = info[id];
            if (bi == null || bi.isAir() || bi.invisible) return;
            if (x < b.minX() || x > b.maxX() || z < b.minZ() || z > b.maxZ() || y < b.minY() || y > b.maxY()) return;
            int X = fromSouthEast ? x : -x - 1, Z = fromSouthEast ? z : -z - 1;
            int ox = (X - Z) * s + offX, oy = (X + Z) * s / 2 - y * s + offY;
            int depth = X + y + Z;
            int above = c.getId(x, y + 1, z);
            int ex = c.getId(x + sign, y, z), sz = c.getId(x, y, z + sign);
            boolean drawTop = above == 0 || !occl[above] || bi.renderH < 16;
            boolean drawE = ex == 0 || !occl[ex];
            boolean drawS = sz == 0 || !occl[sz];
            if (!drawTop && !drawE && !drawS) return;
            int base = col[id];
            double hs = 0.88 + 0.24 * (y - b.minY()) / range;
            if (bi.sprite) {
                blit(pix, zb, fW, fH, st.sprite(bi.renderH), ox, oy, depth, BlockColors.shade(base, hs));
                return;
            }
            int h = Math.max(1, Math.min(16, bi.renderH));
            if (bi.water) {
                if (drawTop) blit(pix, zb, fW, fH, st.top(h), ox, oy, depth, BlockColors.shade(BlockColors.mix(base, 0x5A8CF0, 0.25), hs));
                if (drawE && ex == 0) blit(pix, zb, fW, fH, st.east(h), ox, oy, depth, BlockColors.shade(base, 0.7));
                if (drawS && sz == 0) blit(pix, zb, fW, fH, st.south(h), ox, oy, depth, BlockColors.shade(base, 0.55));
                return;
            }
            if (drawTop) blit(pix, zb, fW, fH, st.top(h), ox, oy, depth, BlockColors.shade(base, hs * 1.06));
            if (drawE) blit(pix, zb, fW, fH, st.east(h), ox, oy, depth, BlockColors.shade(base, hs * 0.78));
            if (drawS) blit(pix, zb, fW, fH, st.south(h), ox, oy, depth, BlockColors.shade(base, hs * 0.6));
        });
        // silhouette outlines: darken where depth jumps
        int[] out = pix.clone();
        for (int py = 1; py < H; py++)
            for (int px = 1; px < W; px++) {
                int i = py * W + px;
                int d0 = zb[i];
                if (d0 == Integer.MIN_VALUE) continue;
                int dl = zb[i - 1], du = zb[i - W];
                if (dl == Integer.MIN_VALUE || du == Integer.MIN_VALUE || d0 - dl >= 2 || d0 - du >= 2 || dl - d0 >= 2 || du - d0 >= 2)
                    out[i] = BlockColors.shade(pix[i], 0.62);
            }
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, W, H, out, 0, W);
        Graphics2D g = graphics(img);
        if (layout != null) {
            List<Layout.Anchor> show = new ArrayList<>();
            for (Layout.Anchor a : layout.anchors().values()) if (a.name().equals("spawn") || a.name().startsWith("npc.") || a.name().startsWith("portal.") || a.name().startsWith("warp.")) show.add(a);
            boolean labels = show.size() <= 80;
            for (Layout.Anchor a : show) {
                double X = sign * a.x(), Z = sign * a.z();
                int px = (int) Math.round((X - Z) * s + offX), py = (int) Math.round((X + Z) * s / 2.0 - a.y() * s + offY);
                g.setColor(Color.BLACK);
                g.fillOval(px - 4, py - 4, 9, 9);
                g.setColor(a.name().equals("spawn") ? new Color(0xFFE14D) : new Color(0x4DE1FF));
                g.fillOval(px - 3, py - 3, 7, 7);
                if (labels) label(g, a.name(), px + 6, py - 4, Color.WHITE);
            }
        }
        g.setColor(Color.WHITE);
        g.drawString(title + "   (isometric, camera in the " + (fromSouthEast ? "SOUTH-EAST: north is up-right, east is down-right" : "NORTH-WEST: south is down-left, west is up-left") + ")", MARGIN, H - 8);
        g.dispose();
        return img;
    }

    private static void blit(int[] pix, int[] zb, int W, int H, int[] stencil, int ox, int oy, int depth, int color) {
        for (int k = 0; k < stencil.length; k += 3) {
            int px = ox + stencil[k], py = oy + stencil[k + 1];
            if (px < 0 || py < 0 || px >= W || py >= H) continue;
            int i = py * W + px;
            if (depth >= zb[i]) {
                zb[i] = depth;
                pix[i] = stencil[k + 2] == 1 ? BlockColors.shade(color, 0.9) : color;
            }
        }
    }

    /** Pixel offsets (dx, dy, isEdge) for each face, relative to the projection of the block's (x, y, z) corner. */
    private static final class Stencils {
        final int s;
        final Map<Integer, int[]> cache = new HashMap<>();

        Stencils(int s) { this.s = s; }

        /** Projected corner of the unit cube at local (lx, ly, lz) relative to the block origin. */
        private double[] p(double lx, double ly, double lz) { return new double[]{(lx - lz) * s, (lx + lz) * s / 2.0 - ly * s}; }

        int[] top(int h) {
            return cache.computeIfAbsent(h, k -> raster(p(0, h / 16.0, 0), p(1, h / 16.0, 0), p(1, h / 16.0, 1), p(0, h / 16.0, 1)));
        }

        int[] east(int h) {
            return cache.computeIfAbsent(100 + h, k -> raster(p(1, h / 16.0, 0), p(1, h / 16.0, 1), p(1, 0, 1), p(1, 0, 0)));
        }

        int[] south(int h) {
            return cache.computeIfAbsent(200 + h, k -> raster(p(0, h / 16.0, 1), p(1, h / 16.0, 1), p(1, 0, 1), p(0, 0, 1)));
        }

        int[] sprite(int h) {
            return cache.computeIfAbsent(300 + h, k -> {
                double cx = 0, w = Math.max(1, s * 0.35);
                double[] a = p(0.5, 0, 0.5);
                double topY = a[1] - h / 16.0 * s;
                return raster(new double[]{a[0] - w, topY}, new double[]{a[0] + w, topY}, new double[]{a[0] + w, a[1]}, new double[]{a[0] - w, a[1]});
            });
        }

        private int[] raster(double[]... poly) {
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] v : poly) { minX = Math.min(minX, v[0]); maxX = Math.max(maxX, v[0]); minY = Math.min(minY, v[1]); maxY = Math.max(maxY, v[1]); }
            List<int[]> px = new ArrayList<>();
            for (int y = (int) Math.floor(minY); y <= (int) Math.ceil(maxY); y++)
                for (int x = (int) Math.floor(minX); x <= (int) Math.ceil(maxX); x++)
                    if (insideOrEdge(poly, x + 0.5, y + 0.5)) px.add(new int[]{x, y});
            java.util.Set<Long> set = new java.util.HashSet<>();
            for (int[] q : px) set.add(((long) q[0] << 32) ^ (q[1] & 0xFFFFFFFFL));
            int[] out = new int[px.size() * 3];
            int i = 0;
            for (int[] q : px) {
                boolean edge = s >= 6 && (!set.contains(((long) (q[0] + 1) << 32) ^ (q[1] & 0xFFFFFFFFL))
                        || !set.contains(((long) (q[0] - 1) << 32) ^ (q[1] & 0xFFFFFFFFL))
                        || !set.contains(((long) q[0] << 32) ^ ((q[1] + 1) & 0xFFFFFFFFL))
                        || !set.contains(((long) q[0] << 32) ^ ((q[1] - 1) & 0xFFFFFFFFL)));
                out[i++] = q[0]; out[i++] = q[1]; out[i++] = edge ? 1 : 0;
            }
            return out;
        }

        private static boolean insideOrEdge(double[][] poly, double x, double y) {
            boolean in = false;
            for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
                double xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1];
                // distance to edge: count pixels whose centre is within half a pixel of an edge as inside
                double dx = xj - xi, dy = yj - yi, len2 = dx * dx + dy * dy;
                if (len2 > 0) {
                    double t = Math.max(0, Math.min(1, ((x - xi) * dx + (y - yi) * dy) / len2));
                    double ex = xi + t * dx - x, ey = yi + t * dy - y;
                    if (ex * ex + ey * ey <= 0.26) return true;
                }
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
            }
            return in;
        }
    }

    // =======================================================================================
    // walk.png
    // =======================================================================================

    private static final Map<Character, Integer> LEGACY = Map.ofEntries(
            Map.entry('0', 0x000000), Map.entry('1', 0x0000AA), Map.entry('2', 0x00AA00), Map.entry('3', 0x00AAAA),
            Map.entry('4', 0xAA0000), Map.entry('5', 0xAA00AA), Map.entry('6', 0xFFAA00), Map.entry('7', 0xAAAAAA),
            Map.entry('8', 0x555555), Map.entry('9', 0x5555FF), Map.entry('a', 0x55FF55), Map.entry('b', 0x55FFFF),
            Map.entry('c', 0xFF5555), Map.entry('d', 0xFF55FF), Map.entry('e', 0xFFFF55), Map.entry('f', 0xFFFFFF));

    public static BufferedImage walk(MemoryCanvas c, Box b, Layout layout, Walkability.Report r, String title) {
        Top t = top(c, b);
        int s = mapScale(b);
        BufferedImage img = canvasImage(t.w * s, t.d * s);
        paintMap(img, t, s, 0.75, true);
        // per-column reachability
        byte[] state = new byte[t.w * t.d];       // 1 = standable only, 2 = reachable
        if (r.stand != null) r.stand.forEach((k, f) -> {
            int ix = Pack.x(k) - t.minX, iz = Pack.z(k) - t.minZ;
            if (ix < 0 || iz < 0 || ix >= t.w || iz >= t.d) return;
            int i = iz * t.w + ix;
            if (r.reachable.contains(k)) state[i] = 2;
            else if (state[i] == 0 && !Walkability.onTree(c, k)) state[i] = 1;
        });
        for (int iz = 0; iz < t.d; iz++)
            for (int ix = 0; ix < t.w; ix++) {
                int st = state[iz * t.w + ix];
                if (st == 0) continue;
                for (int py = 0; py < s; py++)
                    for (int px = 0; px < s; px++) {
                        int X = MARGIN + ix * s + px, Y = MARGIN + iz * s + py;
                        int base = img.getRGB(X, Y) & 0xFFFFFF;
                        img.setRGB(X, Y, BlockColors.mix(base, st == 2 ? 0x34D158 : 0xFF8A1E, st == 2 ? 0.5 : 0.62));
                    }
            }
        Graphics2D g = graphics(img);
        grid(g, t, s);
        // zones
        g.setStroke(new BasicStroke(1.5f));
        for (Layout.Zone z : layout.zones()) {
            Color zc = new Color(LEGACY.getOrDefault(Character.toLowerCase(z.color()), 0xFFFFFF));
            int[] r0 = rect(t, s, z.box());
            g.setColor(new Color(zc.getRed(), zc.getGreen(), zc.getBlue(), 200));
            g.drawRect(r0[0], r0[1], r0[2], r0[3]);
            label(g, "⏣ " + z.displayName() + (z.flags().isEmpty() ? "" : " " + z.flags()), r0[0] + 3, r0[1] + 12, zc);
        }
        // areas (portals bold magenta, others thin white)
        for (var e : layout.areas().entrySet()) {
            int[] r0 = rect(t, s, e.getValue());
            boolean portal = e.getKey().startsWith("portal.");
            g.setStroke(new BasicStroke(portal ? 3f : 1f));
            g.setColor(portal ? new Color(0xFF3DF5) : new Color(255, 255, 255, 120));
            g.drawRect(r0[0], r0[1], r0[2], r0[3]);
            if (portal || layout.areas().size() <= 60) label(g, e.getKey(), r0[0] + 2, r0[1] + r0[3] + 12, portal ? new Color(0xFF8DFA) : new Color(0xDDDDDD));
        }
        g.setStroke(new BasicStroke(2f));
        // anchors
        Map<String, Walkability.AnchorResult> res = new HashMap<>();
        for (Walkability.AnchorResult a : r.anchors) res.put(a.name(), a);
        boolean labels = layout.anchors().size() <= 120;
        for (Layout.Anchor a : layout.anchors().values()) {
            int px = MARGIN + (int) Math.round((a.x() - t.minX) * s), pz = MARGIN + (int) Math.round((a.z() - t.minZ) * s);
            Walkability.AnchorResult ar = res.get(a.name());
            Walkability.Status stt = ar == null ? Walkability.Status.SKIPPED : ar.status();
            boolean bad = stt == Walkability.Status.UNREACHABLE || stt == Walkability.Status.INSIDE_SOLID;
            if (bad) {
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(5f));
                g.drawLine(px - 7, pz - 7, px + 7, pz + 7); g.drawLine(px - 7, pz + 7, px + 7, pz - 7);
                g.setColor(new Color(0xFF2B2B));
                g.setStroke(new BasicStroke(3f));
                g.drawLine(px - 7, pz - 7, px + 7, pz + 7); g.drawLine(px - 7, pz + 7, px + 7, pz - 7);
                label(g, a.name() + (stt == Walkability.Status.INSIDE_SOLID ? " (IN SOLID)" : " (UNREACHABLE)"), px + 9, pz - 6, new Color(0xFF6B6B));
            } else {
                Color fill = a.name().equals("spawn") ? new Color(0xFFE14D) : stt == Walkability.Status.NEAR ? new Color(0xFFC14D)
                        : stt == Walkability.Status.SKIPPED ? new Color(0x9A9A9A) : new Color(0x4DE1FF);
                g.setColor(Color.BLACK);
                g.fillOval(px - 5, pz - 5, 11, 11);
                g.setColor(fill);
                g.fillOval(px - 4, pz - 4, 9, 9);
                if (labels || a.name().equals("spawn")) label(g, a.name(), px + 7, pz - 5, Color.WHITE);
            }
        }
        // legend
        int ly = MARGIN + t.d * s + 16;
        legendBox(g, MARGIN, ly, 0x34D158, "reachable from spawn");
        legendBox(g, MARGIN + 170, ly, 0xFF8A1E, "standable but cut off");
        g.setColor(new Color(0xFF3DF5));
        g.drawRect(MARGIN + 350, ly - 9, 12, 10);
        g.setColor(Color.WHITE);
        g.drawString("portal area", MARGIN + 366, ly);
        g.drawString(title + "   " + (r.ok() ? "OK" : "PROBLEMS: " + r.failed().size() + " anchor(s), " + r.failedPortals().size() + " portal(s)"
                + (r.spawnProblem != null ? ", " + r.spawnProblem : "")), MARGIN + 450, ly);
        g.dispose();
        return img;
    }

    private static void legendBox(Graphics2D g, int x, int y, int rgb, String text) {
        g.setColor(new Color(rgb));
        g.fillRect(x, y - 9, 12, 10);
        g.setColor(Color.WHITE);
        g.drawString(text, x + 16, y);
    }

    private static int[] rect(Top t, int s, Box b) {
        int x0 = MARGIN + (b.minX() - t.minX) * s, z0 = MARGIN + (b.minZ() - t.minZ) * s;
        return new int[]{x0, z0, (b.maxX() - b.minX() + 1) * s - 1, (b.maxZ() - b.minZ() + 1) * s - 1};
    }

    private static void label(Graphics2D g, String text, int x, int y, Color c) {
        g.setColor(new Color(0, 0, 0, 200));
        g.drawString(text, x + 1, y + 1);
        g.drawString(text, x - 1, y + 1);
        g.setColor(c);
        g.drawString(text, x, y);
    }

    // =======================================================================================
    // everything at once
    // =======================================================================================

    /** Writes top.png, iso.png, iso_nw.png and walk.png into {@code dir}; returns the files written. */
    public static List<File> writeAll(MemoryCanvas c, Layout layout, Walkability.Report r, File dir, String title) throws IOException {
        dir.mkdirs();
        Box b = c.bounds();
        List<File> out = new ArrayList<>();
        if (b == null) return out;
        b = b.grow(2);
        out.add(write(topDown(c, b, title), new File(dir, "top.png")));
        out.add(write(iso(c, b, true, layout, title), new File(dir, "iso.png")));
        out.add(write(iso(c, b, false, layout, title), new File(dir, "iso_nw.png")));
        out.add(write(walk(c, b, layout, r, title), new File(dir, "walk.png")));
        return out;
    }

    /** Zoomed renders of one area (x/z of {@code area}; full height): crop_top/iso/iso_nw/walk.png. */
    public static List<File> writeCrop(MemoryCanvas c, Layout layout, Walkability.Report r, File dir, String title, Box area) throws IOException {
        dir.mkdirs();
        Box all = c.bounds();
        List<File> out = new ArrayList<>();
        if (all == null) return out;
        Box b = new Box(area.minX(), all.minY(), area.minZ(), area.maxX(), all.maxY(), area.maxZ());
        String t = title + "  crop " + area.minX() + "," + area.minZ() + " -> " + area.maxX() + "," + area.maxZ();
        out.add(write(topDown(c, b, t), new File(dir, "crop_top.png")));
        out.add(write(iso(c, b, true, layout, t), new File(dir, "crop_iso.png")));
        out.add(write(iso(c, b, false, layout, t), new File(dir, "crop_iso_nw.png")));
        out.add(write(walk(c, b, layout, r, t), new File(dir, "crop_walk.png")));
        return out;
    }

    private static File write(BufferedImage img, File f) throws IOException {
        ImageIO.write(img, "png", f);
        return f;
    }

    static String fmt(String f, Object... a) { return String.format(Locale.ROOT, f, a); }
}
