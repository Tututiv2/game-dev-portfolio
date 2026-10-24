package com.skyclone.mod.dungeons.puzzle;

import com.skyclone.gen.Blocks;
import com.skyclone.mod.dungeons.gen.Geo;
import com.skyclone.mod.dungeons.gen.RoomCtx;

import java.util.List;
import java.util.Map;

/**
 * The BLOCKS of every puzzle room (research 04 sec 3.9), authored in the room's door frame (u across, v away from the
 * door, h above the floor) and recording the named points/data the runtime half reads. Pure.
 *
 * <p>Point naming per puzzle is documented in docs/handoff/dungeons.md. Every puzzle also records
 * {@code puzzle.chest} (where the blessing chest appears when it is solved) and {@code puzzle.sign} (the rules sign).
 */
public final class PuzzleBuilders {

    private PuzzleBuilders() {}

    public static final List<String> IDS = List.of("tic_tac_toe", "three_strangers", "creeper_beams", "water_board", "teleport_maze",
            "higher_lower", "boulder", "ice_path", "ice_fill", "quiz");

    private static final Map<String, String> NAMES = Map.of(
            "tic_tac_toe", "Tic Tac Toe", "three_strangers", "Three Strangers", "creeper_beams", "Creeper Beams",
            "water_board", "Water Board", "teleport_maze", "Teleport Maze", "higher_lower", "Higher or Lower",
            "boulder", "Boulder", "ice_path", "Ice Path", "ice_fill", "Ice Fill", "quiz", "Quiz");

    /** Lowest floor number (0 = Entrance) the puzzle appears on (research 04 sec 3.9 "Floors"). */
    public static int minFloor(String id) {
        return switch (id) {
            case "higher_lower" -> 2;
            case "boulder", "ice_path" -> 3;
            case "quiz" -> 4;
            case "ice_fill" -> 7;
            default -> 0;
        };
    }

    /** Whether a mistake FAILS the puzzle (the rest only waste time). */
    public static boolean failable(String id) {
        return switch (id) {
            case "tic_tac_toe", "three_strangers", "higher_lower", "quiz" -> true;
            default -> false;
        };
    }

    public static String name(String id) { return NAMES.getOrDefault(id, id); }

    public static void build(String id, RoomCtx ctx) {
        ctx.info.data.put("puzzle", id);
        ctx.lights(12);
        switch (id) {
            case "tic_tac_toe" -> ticTacToe(ctx);
            case "three_strangers" -> strangers(ctx);
            case "creeper_beams" -> beams(ctx);
            case "water_board" -> water(ctx);
            case "teleport_maze" -> teleport(ctx);
            case "higher_lower" -> higherLower(ctx);
            case "boulder" -> boulder(ctx);
            case "ice_path" -> icePath(ctx);
            case "ice_fill" -> iceFill(ctx);
            case "quiz" -> quiz(ctx);
            default -> { }
        }
        if (!ctx.info.points.containsKey("puzzle.chest")) ctx.fpoint("puzzle.chest", 14, 1, 3);
        if (!ctx.info.points.containsKey("puzzle.sign")) {
            ctx.fset(12, 1, 1, ctx.theme.trim.at(ctx.x(12, 1), ctx.fy + 1, ctx.z(12, 1)));
            ctx.fpoint("puzzle.sign", 12, 2, 1);
        }
    }

    // ---- Tic Tac Toe: a 3x3 board on the far wall, a button under each cell -----------------------

    /**
     * Height above the floor block of a board row (0 = top). playtest-endgame: the top row sat at h 7, whose button is
     * 4.75 blocks above a standing player's eye (feet h 1 + 1.62), past the 4.5-block reach, so only a jump-click hit
     * it. Rows at 6/4/2 put every button inside reach from the floor ({@code PuzzleLogicTest.ticTacToeButtonsInReach}).
     */
    public static int tttRowH(int row) { return 6 - row * 2; }

    private static void ticTacToe(RoomCtx c) {
        for (int u = 9; u <= 19; u++) for (int h = 1; h <= 8; h++) c.fset(u, h, 29, "dark_oak_planks");
        for (int i = 0; i < 9; i++) {
            int col = i % 3, row = i / 3;
            int u = 17 - col * 3, h = tttRowH(row);
            c.fset(u, h, 29, "white_concrete");
            c.fset(u, h, 28, "stone_button[face=wall,facing=" + c.toward() + ",powered=false]");
            c.fpoint("ttt.cell." + i, u, h, 29);
            c.fpoint("ttt.button." + i, u, h, 28);
        }
        c.fpoint("puzzle.chest", 14, 1, 24);
        c.fpoint("puzzle.sign", 14, 1, 20);
    }

    // ---- Three Strangers: three NPCs, three chests ---------------------------------------------------

    private static void strangers(RoomCtx c) {
        for (int k = 0; k < 3; k++) {
            int u = 7 + k * 7;
            for (int du = -1; du <= 1; du++) for (int dv = 23; dv <= 25; dv++) c.fset(u + du, 0, dv, c.theme.accent);
            c.fpoint("npc." + k, u, 1, 24);
            c.fset(u, 1, 21, "chest[facing=" + c.toward() + ",type=single,waterlogged=false]");
            c.fpoint("chest." + k, u, 1, 21);
            c.fset(u, 1, 26, "candle[candles=4,lit=true,waterlogged=false]");
        }
        c.fpoint("puzzle.chest", 14, 1, 16);
        c.fpoint("puzzle.sign", 14, 1, 8);
    }

    // ---- Creeper Beams: a creeper on a plinth, sea lanterns on the walls in mirrored pairs -------------

    /**
     * wave-4 endgame: lanterns sat at h 2..8 (mirrored h' = 10 - h round a creeper standing at h 4), and the ones at 6
     * and up against the wall could only be clicked from straight below. The creeper now stands one block lower (h 3,
     * on a 2-high plinth) and every lantern is at h {@link #BEAM_LOW}..{@link #BEAM_HIGH}, mirrored as
     * h' = {@link #BEAM_SUM} - h, so the pair's midpoint stays at the creeper's chest and every lantern is a normal
     * click from a few blocks away (PuzzleLogicTest.creeperBeamLanternsInReach).
     */
    public static final int BEAM_LOW = 2, BEAM_HIGH = 5, BEAM_SUM = 7, BEAM_CREEPER_H = 3;

    /** The height of the lantern that pairs with one at {@code h}. */
    public static int beamPairH(int h) { return BEAM_SUM - h; }

    private static void beams(RoomCtx c) {
        for (int h = 1; h < BEAM_CREEPER_H; h++) c.fset(14, h, 14, c.theme.pillar.at(c.x(14, 14), c.fy + h, c.z(14, 14)));
        c.fpoint("creeper", 14, BEAM_CREEPER_H, 14);
        int placed = 0, idx = 0;
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int t = 0; t < 200 && placed < 4; t++) {
            int[] p = wallSpot(c);
            int[] q = {29 - p[0], beamPairH(p[1]), 29 - p[2]};
            if (!okLantern(q) || !used.add(key(p)) || !used.add(key(q))) continue;
            c.fset(p[0], p[1], p[2], "sea_lantern");
            c.fset(q[0], q[1], q[2], "sea_lantern");
            c.fpoint("lantern." + idx++, p[0], p[1], p[2]);
            c.fpoint("lantern." + idx++, q[0], q[1], q[2]);
            placed++;
        }
        for (int t = 0, d = 0; t < 100 && d < 3; t++) {
            int[] p = wallSpot(c);
            if (!used.add(key(p))) continue;
            c.fset(p[0], p[1], p[2], "sea_lantern");
            c.fpoint("lantern." + idx++, p[0], p[1], p[2]);
            d++;
        }
        c.info.data.put("lanterns", String.valueOf(idx));
        c.fpoint("puzzle.chest", 14, 1, 20);
    }

    private static String key(int[] p) { return p[0] + "," + p[1] + "," + p[2]; }

    /** A lantern spot against one of the 4 walls (u or v at 0 / 29), away from the door opening. */
    private static int[] wallSpot(RoomCtx c) {
        while (true) {
            int side = c.rng.nextInt(4);
            int a = 2 + c.rng.nextInt(26), h = BEAM_LOW + c.rng.nextInt(BEAM_HIGH - BEAM_LOW + 1);
            int[] p = switch (side) {
                case 0 -> new int[]{0, h, a};
                case 1 -> new int[]{29, h, a};
                case 2 -> new int[]{a, h, 29};
                default -> new int[]{a, h, 0};
            };
            if (okLantern(p)) return p;
        }
    }

    private static boolean okLantern(int[] p) {
        if (p[1] < BEAM_LOW || p[1] > BEAM_HIGH) return false;
        boolean doorArea = p[2] <= 4 && p[0] >= 10 && p[0] <= 18;
        return !doorArea;
    }

    // ---- Water Board: levers, a water lever, five doors in series before the chest ---------------------

    private static void water(RoomCtx c) {
        String[] colors = {"gold_block", "terracotta", "emerald_block", "quartz_block", "diamond_block", "clay"};
        for (int i = 0; i < 6; i++) {
            int u = 4 + i * 4;
            c.fset(u, 1, 12, colors[i]);
            c.fset(u, 2, 12, "lever[face=floor,facing=" + c.away() + ",powered=false]");
            c.fpoint("water.lever." + i, u, 2, 12);
        }
        c.fset(14, 1, 7, "lapis_block");
        c.fset(14, 2, 7, "lever[face=floor,facing=" + c.away() + ",powered=false]");
        c.fpoint("water.main", 14, 2, 7);
        // the corridor to the chest, 1 wide, with glass sides and five wool doors
        String[] wool = {"purple_wool", "orange_wool", "blue_wool", "lime_wool", "red_wool"};
        for (int v = 18; v <= 29; v++) {
            for (int h = 1; h <= 3; h++) { c.fset(13, h, v, "tinted_glass"); c.fset(15, h, v, "tinted_glass"); }
            c.fset(14, 3, v, "tinted_glass");
        }
        for (int d = 0; d < 5; d++) {
            int v = 20 + d * 2;
            c.fset(14, 1, v, wool[d]);
            c.fset(14, 2, v, wool[d]);
            c.fpoint("water.door." + d, 14, 1, v);
        }
        c.fset(14, 1, 29, "chest[facing=" + c.toward() + ",type=single,waterlogged=false]");
        c.fpoint("puzzle.chest", 14, 1, 29);
        // the "board": a dark wall with channels the water runs down when released
        for (int u = 1; u <= 28; u++) for (int h = 4; h <= 11; h++) if (u < 12 || u > 16) c.fset(u, h, 29, "polished_blackstone");
        for (int d = 0; d < 5; d++) c.fpoint("water.outlet." + d, 2 + d * 2 + (d >= 3 ? 14 : 0), 10, 28);
    }

    // ---- Teleport Maze: 3x3 sealed chambers with pads -----------------------------------------------

    private static void teleport(RoomCtx c) {
        String wall = "polished_deepslate";
        for (int u = 0; u <= 29; u++)
            for (int v = 0; v <= 29; v++) {
                boolean line = u % 10 == 0 || u % 10 == 9 || v % 10 == 0 || v % 10 == 9;
                if (!line) continue;
                // the door opening into the entrance chamber (1, 0)
                if (v == 0 && u >= 13 && u <= 15) continue;
                for (int h = 1; h <= 11; h++) c.fset(u, h, v, wall);
            }
        GridPuzzles.Teleport tp = GridPuzzles.teleport(c.seed ^ 0x7E1E);
        StringBuilder dest = new StringBuilder();
        for (int ch = 0; ch < 9; ch++) {
            int cu = ch % 3, cv = ch / 3;
            int bu = cu * 10 + 1, bv = cv * 10 + 1;
            c.fpoint("tp.arrive." + ch, bu + 4, 1, bv + 4);
            c.fset(bu + 4, 11, bv + 4, c.theme.lightHanging);
            for (int i = 0; i < tp.pads()[ch].length; i++) {
                int[] spot = GridPuzzles.PAD_SPOTS[tp.pads()[ch][i]];
                c.fset(bu + spot[0], 0, bv + spot[1], "end_portal_frame[eye=false,facing=north]");
                c.fpoint("tp.pad." + ch + "." + i, bu + spot[0], 1, bv + spot[1]);
                dest.append(ch).append(':').append(i).append('>').append(tp.dest()[ch][i]).append(';');
            }
        }
        c.info.data.put("tp.dest", dest.toString());
        c.fset(14, 1, 17, "gold_block");
        c.fpoint("puzzle.chest", 15, 1, 15);
        c.fbox("tp.goal", 11, 1, 11, 18, 4, 18);
        c.fpoint("puzzle.sign", 14, 1, 4);
    }

    // ---- Higher or Lower: ten blazes around a pillar --------------------------------------------------

    private static void higherLower(RoomCtx c) {
        for (int u = 13; u <= 16; u++) for (int v = 13; v <= 16; v++) for (int h = 1; h <= 8; h++) c.fset(u, h, v, "nether_bricks");
        for (int u = 13; u <= 16; u++) for (int v = 13; v <= 16; v++) c.fset(u, 9, v, "red_nether_brick_slab[type=bottom,waterlogged=false]");
        boolean asc = c.rng.nextBoolean();
        c.info.data.put("hl.order", asc ? "asc" : "desc");
        for (int i = 0; i < 10; i++) {
            double a = Math.PI * 2 * i / 10;
            int u = 14 + (int) Math.round(Math.cos(a) * 9), v = 14 + (int) Math.round(Math.sin(a) * 9);
            c.fset(u, 0, v, "magma_block");
            c.fpoint("blaze." + i, u, 2 + (i % 3) * 2, v);
        }
        if (asc) c.fpoint("puzzle.chest", 14, 10, 14); else c.fpoint("puzzle.chest", 14, 1, 11);
    }

    // ---- Boulder: 7 x 6 grid of 3x3x3 boulders, walls either side ---------------------------------------

    private static void boulder(RoomCtx c) {
        long grid = GridPuzzles.boulder(c.seed ^ 0xB0);
        StringBuilder g = new StringBuilder();
        for (int r = 0; r < GridPuzzles.BR; r++) for (int col = 0; col < GridPuzzles.BC; col++) g.append(GridPuzzles.boulderAt(grid, col, r) ? '1' : '0');
        c.info.data.put("boulder.grid", g.toString());
        // side walls so the grid is the only way through
        for (int v = 6; v <= 23; v++)
            for (int h = 1; h <= 4; h++) {
                for (int u = 0; u <= 3; u++) c.fset(u, h, v, c.theme.wall.at(c.x(u, v), c.fy + h, c.z(u, v)));
                for (int u = 25; u <= 29; u++) c.fset(u, h, v, c.theme.wall.at(c.x(u, v), c.fy + h, c.z(u, v)));
            }
        for (int r = 0; r < GridPuzzles.BR; r++)
            for (int col = 0; col < GridPuzzles.BC; col++) {
                int u0 = 4 + col * 3, v0 = 6 + r * 3;
                c.fpoint("boulder." + col + "." + r, u0 + 1, 1, v0 + 1);
                if (!GridPuzzles.boulderAt(grid, col, r)) continue;
                for (int du = 0; du < 3; du++) for (int dv = 0; dv < 3; dv++) for (int h = 1; h <= 3; h++)
                    c.fset(u0 + du, h, v0 + dv, (du + dv + h) % 4 == 0 ? "cobblestone" : "stone");
            }
        for (int u = 4; u <= 24; u++) c.fset(u, 5, 5, c.theme.trim.at(c.x(u, 5), c.fy + 5, c.z(u, 5)));
        c.fset(2, 1, 4, "stone_button[face=floor,facing=" + c.away() + ",powered=false]");
        c.fpoint("boulder.reset", 2, 1, 4);
        c.fpoint("puzzle.chest", 14, 1, 27);
        c.fbox("boulder.exit", 4, 1, 24, 24, 4, 29);
    }

    // ---- Ice Path: a 13x13 ice grid with obstacles; push the silverfish to the goal ----------------------

    private static void icePath(RoomCtx c) {
        GridPuzzles.IcePath ip = GridPuzzles.icePath(c.seed ^ 0x1CE);
        int ou = 8, ov = 8;
        for (int u = ou - 1; u <= ou + GridPuzzles.IP; u++)
            for (int v = ov - 1; v <= ov + GridPuzzles.IP; v++) {
                boolean rim = u == ou - 1 || v == ov - 1 || u == ou + GridPuzzles.IP || v == ov + GridPuzzles.IP;
                if (rim) { c.fset(u, 1, v, "packed_ice"); continue; }
                int col = u - ou, row = v - ov;
                c.fset(u, 0, v, "packed_ice");
                if (ip.blocked()[row * GridPuzzles.IP + col]) { c.fset(u, 1, v, "blue_ice"); c.fset(u, 2, v, "snow_block"); }
            }
        int gc = ip.goal() % GridPuzzles.IP;
        c.fset(ou + gc, 0, ov + GridPuzzles.IP - 1, "lime_concrete");
        c.info.data.put("ice.start", String.valueOf(ip.start()));
        c.info.data.put("ice.goal", String.valueOf(ip.goal()));
        StringBuilder b = new StringBuilder();
        for (boolean x : ip.blocked()) b.append(x ? '1' : '0');
        c.info.data.put("ice.grid", b.toString());
        c.info.data.put("ice.origin", ou + "," + ov);
        c.fpoint("ice.fish", ou + ip.start() % GridPuzzles.IP, 1, ov);
        c.fpoint("puzzle.chest", 14, 1, 25);
    }

    // ---- Ice Fill: three boards 3x3, 5x5, 7x7 in a row with gates ----------------------------------------

    private static void iceFill(RoomCtx c) {
        int[] sizes = {3, 5, 7};
        int[] startV = {3, 9, 17};
        StringBuilder meta = new StringBuilder();
        for (int k = 0; k < 3; k++) {
            int n = sizes[k];
            GridPuzzles.IceFill f = GridPuzzles.iceFill(c.seed ^ (0x1CEF + k), n);
            int ou = 14 - n / 2, ov = startV[k];
            for (int u = ou - 1; u <= ou + n; u++)
                for (int v = ov - 1; v <= ov + n; v++) {
                    boolean rim = u == ou - 1 || u == ou + n || v == ov - 1 || v == ov + n;
                    boolean gap = (v == ov - 1 || v == ov + n) && u == 14;
                    if (rim && !gap) { c.fset(u, 1, v, c.theme.trim.at(c.x(u, v), c.fy + 1, c.z(u, v))); c.fset(u, 2, v, c.theme.trim.at(c.x(u, v), c.fy + 2, c.z(u, v))); continue; }
                    if (rim) continue;
                    int cell = (v - ov) * n + (u - ou);
                    if (f.blocked()[cell]) { c.fset(u, 1, v, "stone_bricks"); c.fset(u, 0, v, "stone_bricks"); }
                    else c.fset(u, 0, v, "ice");
                }
            // a full-width wall after each board so the next one can only be reached through its gate
            for (int u = 0; u <= 29; u++) {
                if (u == 14) continue;
                for (int h = 1; h <= 4; h++) c.fset(u, h, ov + n, c.theme.wall.at(c.x(u, ov + n), c.fy + h, c.z(u, ov + n)));
            }
            // gate after the board (closed until it is complete), except after the last one the chest waits
            c.fset(14, 1, ov + n, k < 2 ? "iron_bars" : "air");
            c.fset(14, 2, ov + n, k < 2 ? "iron_bars" : "air");
            c.fpoint("fill." + k + ".gate", 14, 1, ov + n);
            c.fpoint("fill." + k + ".start", 14, 1, ov - 1);
            StringBuilder g = new StringBuilder();
            for (boolean x : f.blocked()) g.append(x ? '1' : '0');
            c.info.data.put("fill." + k + ".grid", g.toString());
            c.info.data.put("fill." + k + ".origin", ou + "," + ov + "," + n);
            meta.append(n).append(';');
        }
        // walkways between boards
        for (int v = 0; v <= 29; v++) if (!isBoardRow(v)) c.fset(14, 0, v, "polished_andesite");
        c.fpoint("puzzle.chest", 14, 1, 27);
        c.info.data.put("fill.boards", meta.toString());
    }

    private static boolean isBoardRow(int v) {
        return (v >= 3 && v <= 5) || (v >= 9 && v <= 13) || (v >= 17 && v <= 23);
    }

    // ---- Quiz: an NPC and three answer pedestals ----------------------------------------------------------

    private static void quiz(RoomCtx c) {
        String[] colors = {"red_terracotta", "yellow_terracotta", "lime_terracotta"};
        for (int k = 0; k < 3; k++) {
            int u = 7 + k * 7;
            c.fset(u, 1, 16, colors[k]);
            c.fset(u, 2, 16, "stone_button[face=floor,facing=" + c.away() + ",powered=false]");
            c.fpoint("quiz.opt." + k, u, 1, 16);
            c.fpoint("quiz.label." + k, u, 3, 16);
        }
        for (int u = 11; u <= 17; u++) for (int v = 23; v <= 26; v++) c.fset(u, 0, v, c.theme.accent);
        c.fpoint("quiz.npc", 14, 1, 24);
        c.fpoint("puzzle.chest", 14, 1, 20);
        c.fset(14, Geo.CEIL_Y - Geo.FLOOR_Y - 1, 24, c.theme.lightHanging);
        // a ring of candles around the speaker
        for (int[] o : new int[][]{{10, 22}, {18, 22}, {10, 27}, {18, 27}}) c.fset(o[0], 1, o[1], "candle[candles=3,lit=true,waterlogged=false]");
        c.fset(14, 1, 28, Blocks.stairs(c.theme.stairs, c.toward()));
    }
}
