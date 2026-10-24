package com.smhmines.spawn;

import com.smhmines.build.BuildQueue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Builds THE HOLLOW MOUNTAIN — the SmhMines spawn.
 *
 * <p><b>A colossal hollow mountain.</b> 360 blocks across at the foot, 880 tall, with a grand
 * switchback road cut into its face and a cathedral void inside it that runs the full height and
 * opens at the summit through an oculus. You spawn on a broad ring plaza at its foot, walk in
 * through the entrance arch to see the inside, and climb the outside road to the top, where the
 * planets are.
 *
 * <p><b>Rejection history — read before redesigning.</b>
 * <ol>
 *   <li>v1 flat quartz plaza — "too flat", "too empty".</li>
 *   <li>v2 drill, spawn on its deck — too small, wrong direction.</li>
 *   <li>v3 drill inverted, spawn at the tip — "a statue, no way to climb it".</li>
 *   <li>v4 rock spire with a spiral stair — "too thin, spawn too small, not unique".</li>
 * </ol>
 * The three standing requirements those produced, all of which this file is built around:
 * <b>it must be walkable and provably so</b>; <b>it must be massive, in width as well as
 * height</b>; and <b>it must have an idea in it</b> — here, that the mountain is hollow and you
 * can go inside.
 *
 * <p><b>Cost control.</b> A solid mountain this size is ~36 million block writes and a hollow one
 * with walls thick enough to carve a road into is ~13 million. Neither is buildable in a sane
 * time. So the shell is thin ({@link #SHELL}) and <b>the road brings its own rock</b>: each
 * stretch of road lays a local shoulder of stone and then carves itself out of it. Cost then
 * scales with the length of the road, not with the surface area of the mountain.
 */
public final class SpawnCommand implements CommandExecutor {

    // ===================================================================== palette

    private static final Material ROCK     = Material.DEEPSLATE;
    private static final Material ROCK_2   = Material.TUFF;
    private static final Material ROCK_3   = Material.COBBLED_DEEPSLATE;
    private static final Material ROCK_4   = Material.SMOOTH_BASALT;
    private static final Material ROCK_5   = Material.ANDESITE;
    private static final Material ROCK_CAP = Material.CALCITE;

    private static final Material ROAD      = Material.POLISHED_DEEPSLATE;
    private static final Material ROAD_2    = Material.DEEPSLATE_TILES;
    private static final Material ROAD_HALF = Material.POLISHED_DEEPSLATE_SLAB;
    private static final Material KERB      = Material.DEEPSLATE_BRICKS;
    private static final Material KERB_TRIM = Material.CHISELED_DEEPSLATE;
    private static final Material RAIL      = Material.IRON_BARS;

    private static final Material PLAZA      = Material.TUFF_BRICKS;
    private static final Material PLAZA_TRIM = Material.CHISELED_TUFF_BRICKS;
    private static final Material PLAZA_ALT  = Material.POLISHED_TUFF;

    private static final Material LAMP      = Material.LANTERN;
    private static final Material LAMP_COOL = Material.SOUL_LANTERN;
    private static final Material GLOW_COOL = Material.SEA_LANTERN;
    private static final Material GLOW_WARM = Material.SHROOMLIGHT;
    private static final Material ROD       = Material.END_ROD;
    private static final Material HANG      = Material.IRON_CHAIN;
    private static final Material BEAM_GLASS = Material.YELLOW_STAINED_GLASS;

    /** Ore veins in the rock — and the only light source inside the hollow. */
    private static final Material[] VEIN = {
            Material.DEEPSLATE_COAL_ORE, Material.DEEPSLATE_IRON_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.DEEPSLATE_GOLD_ORE, Material.DEEPSLATE_REDSTONE_ORE, Material.DEEPSLATE_LAPIS_ORE };
    private static final Material[] GLOW_VEIN = {
            Material.GLOWSTONE, Material.SHROOMLIGHT, Material.AMETHYST_BLOCK, Material.SEA_LANTERN };

    private static final Material[] ORE_INLAY = {
            Material.DEEPSLATE_COAL_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.DEEPSLATE_GOLD_ORE, Material.DEEPSLATE_DIAMOND_ORE };
    private static final Material[] GEM_INLAY = {
            Material.EMERALD_BLOCK, Material.LAPIS_BLOCK,
            Material.REDSTONE_BLOCK, Material.AMETHYST_BLOCK };

    // ===================================================================== dials

    /** The mountain. */
    private static final int CLIMB        = 880;   // foot to summit
    private static final double MOUNT_R_BASE = 180;
    private static final double MOUNT_R_TOP  = 52;
    private static final int SHELL        = 4;     // wall thickness of the hollow cone
    private static final int BAND         = 16;    // Y slice used to cut the tapering cone

    /** The road. */
    private static final int ROAD_W       = 15;    // walkable width
    private static final int ROAD_ROCK    = 6;     // shoulder of stone carved in behind it
    private static final int ROAD_CLEAR   = 8;     // headroom carved above it
    /**
     * Rise per block walked on the ramps. 0.5 is the comfort guarantee: half a block is under
     * Minecraft's 0.6 auto-step, so the climb is walked and never jumped. NEVER fix the number of
     * turns instead — the radius changes with height, so a fixed turn count makes the slope vary,
     * and in v4 that produced a stair rising 0.54/step at the bottom and 1.28 at the top.
     */
    private static final double RAMP_SLOPE = 0.5;
    /** Flat terraces punctuating the climb. These are where content goes. */
    private static final int PLAZAS_PER_TURN = 3;
    private static final double PLAZA_ARC = 0.34;      // radians of dead-flat road
    private static final int PLAZA_EXTRA = 14;         // how much wider a plaza is than the road

    /** The base camp ring at the foot. */
    private static final int CAMP_R_OUTER = 252;
    private static final int CAMP_DEPTH   = 44;

    private static final int SUMMIT_R = 56;
    private static final int OCULUS_R = 30;
    private static final int BEACON_H = 26;

    private static final double BEAR_TRADERS = 0;
    private static final double BEAR_BOSS    = 90;
    private static final double BEAR_MINES   = 180;
    private static final double BEAR_PVP     = 270;
    /** Where the road starts, and where the entrance arch into the hollow is. */
    private static final double BEAR_ROAD_START = 45;
    private static final double BEAR_ENTRANCE   = 225;

    private static final TextColor HI = TextColor.color(0xEBD6A3);
    private static final TextColor LO = TextColor.color(0x7A6034);

    // =====================================================================

    private final JavaPlugin plugin;
    private final NamespacedKey holoTag;
    private boolean building;

    public SpawnCommand(JavaPlugin plugin) {
        this.plugin = plugin;
        this.holoTag = new NamespacedKey(plugin, "smh_spawn_holo");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) {
            sender.sendMessage(Component.text("Op only.", NamedTextColor.RED));
            return true;
        }
        switch (args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT)) {
            case "build" -> run(player, false);
            case "wipe" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    player.sendMessage(Component.text("Re-runs the build's own plan as AIR. "
                            + "Stand where you built it. Run: /smhspawn wipe confirm",
                            NamedTextColor.RED));
                    return true;
                }
                run(player, true);
            }
            case "verify" -> verifyFromHere(player);
            case "sethub" -> {
                writeHub(player.getLocation());
                player.sendMessage(Component.text("Hub set to your feet.", NamedTextColor.GREEN));
            }
            default -> {
                player.sendMessage(gradientBold("SMHMINES — THE HOLLOW MOUNTAIN", HI, LO));
                player.sendMessage(Component.text("/smhspawn build   ", NamedTextColor.GOLD)
                        .append(Component.text("raise the mountain at your feet", NamedTextColor.GRAY)));
                player.sendMessage(Component.text("/smhspawn verify  ", NamedTextColor.GOLD)
                        .append(Component.text("prove where you stand is walkable from spawn",
                                NamedTextColor.GRAY)));
                player.sendMessage(Component.text("/smhspawn wipe confirm  ", NamedTextColor.GOLD)
                        .append(Component.text("tear it down", NamedTextColor.GRAY)));
            }
        }
        return true;
    }

    // ===================================================================== profile

    /** Mountain surface radius at a height offset. Single source of truth for the silhouette. */
    private static double mountR(double dy) {
        double t = clamp01(dy / CLIMB);
        // Slightly convex: broad skirted foot, then a steady taper. Reads as a mountain, not a cone.
        return lerp(MOUNT_R_BASE, MOUNT_R_TOP, Math.pow(t, 1.18));
    }

    /** Centre-line radius of the road at a given height — it hugs the surface. */
    private static double roadR(double dy) { return mountR(dy) - ROAD_W / 2.0 - 1; }

    // --- the road height table --------------------------------------------------
    //
    // The road alternates flat terraces with climbing ramps, and its radius shrinks as it rises,
    // so constant slope has no closed form. It is integrated once into a table that every phase
    // reads, which is what guarantees no two blocks disagree about the road's height — the
    // property that makes it walkable and that v3 lacked entirely.

    private static final double DTHETA = 0.003;
    private static double[] HEIGHT_TABLE;
    private static double TOTAL_ANGLE;

    /** Zero on a flat terrace, RAMP_SLOPE on a climbing stretch. */
    private static double slopeAt(double theta) {
        double period = 2 * Math.PI / PLAZAS_PER_TURN;
        return (theta % period) < PLAZA_ARC ? 0 : RAMP_SLOPE;
    }

    private static synchronized void ensureTable() {
        if (HEIGHT_TABLE != null) return;
        double[] tmp = new double[600_000];
        double h = 0, th = 0;
        int i = 0;
        tmp[i++] = 0;
        while (h < CLIMB && i < tmp.length - 1) {
            h += slopeAt(th) * roadR(h) * DTHETA;
            th += DTHETA;
            tmp[i++] = h;
        }
        HEIGHT_TABLE = java.util.Arrays.copyOf(tmp, i);
        TOTAL_ANGLE = th;
    }

    private static double roadSurface(double theta) {
        ensureTable();
        if (theta <= 0) return 0;
        double fi = theta / DTHETA;
        int i = (int) fi;
        if (i >= HEIGHT_TABLE.length - 1) return HEIGHT_TABLE[HEIGHT_TABLE.length - 1];
        return HEIGHT_TABLE[i] + (HEIGHT_TABLE[i + 1] - HEIGHT_TABLE[i]) * (fi - i);
    }

    private static double totalAngle() { ensureTable(); return TOTAL_ANGLE; }
    private static int turns() { return (int) Math.ceil(totalAngle() / (2 * Math.PI)); }
    private static boolean onPlaza(double theta) { return slopeAt(theta) == 0; }

    // ===================================================================== plan

    private void run(Player player, boolean demolish) {
        if (building) {
            player.sendMessage(Component.text("Already working — hold on.", NamedTextColor.YELLOW));
            return;
        }
        World world = player.getWorld();
        int cx = player.getLocation().getBlockX();
        int cz = player.getLocation().getBlockZ();
        int base = player.getLocation().getBlockY() - 1;

        if (!demolish && base + CLIMB + BEACON_H + 24 > world.getMaxHeight() - 1) {
            player.sendMessage(Component.text("Not enough headroom: needs "
                    + (CLIMB + BEACON_H + 24) + " blocks above you.", NamedTextColor.RED));
            return;
        }
        building = true;

        BuildQueue q = new BuildQueue(plugin, world, demolish ? "mountain-wipe" : "mountain");
        q.setDemolish(demolish);

        planCampRing(q, cx, base, cz);
        planGates(q, cx, base, cz);
        planShell(q, cx, base, cz);
        planInterior(q, cx, base, cz);
        planEntrance(q, cx, base, cz);
        planRoad(q, cx, base, cz);
        planPlazas(q, cx, base, cz);
        planSummit(q, cx, base, cz);
        planLights(q, cx, base, cz);

        if (q.phaseCount() > 30_000) {
            player.sendMessage(Component.text("Refusing to run: " + q.phaseCount()
                    + " phases — a dial is wrong. Nothing written.", NamedTextColor.RED));
            building = false;
            return;
        }

        player.sendMessage(gradientBold(demolish ? "TEARING IT DOWN" : "THE MOUNTAIN IS RISING", HI, LO));
        player.sendMessage(Component.text("  " + q.phaseCount() + " phases, ~"
                + Math.max(8, q.volume() / 600_000L) + "s — this one is big.",
                NamedTextColor.DARK_GRAY));

        q.run(pct -> player.sendMessage(Component.text("   ... " + pct + "%", NamedTextColor.DARK_GRAY)),
              () -> {
                  building = false;
                  if (demolish) {
                      clearHolos(world, player.getLocation());
                      player.sendMessage(Component.text("Gone.", NamedTextColor.GREEN));
                  } else finish(player, world, cx, base, cz, q);
              });
    }

    // ---------------------------------------------------------------- the foot

    /** A vast ring plaza around the mountain's foot. This is spawn, and it is deliberately huge. */
    private void planCampRing(BuildQueue q, int cx, int base, int cz) {
        // Rock mass under the ring, narrowing downward like a torn shelf.
        for (int d = 0; d <= CAMP_DEPTH; d += 4) {
            final int yTop = base - d, yBot = Math.max(base - CAMP_DEPTH, yTop - 3);
            final double r = CAMP_R_OUTER - Math.pow(d, 1.32) * 0.9;
            if (r < 8) continue;
            final int ri = (int) Math.ceil(r);
            q.shape("foot-rock", cx - ri, yBot, cz - ri, cx + ri, yTop, cz + ri, (x, y, z) -> {
                double dist = BuildQueue.dist(cx, cz, x, z);
                double wob = (BuildQueue.rroll(x / 5, z / 5, 3) - 50) * 0.14;
                if (dist > r + wob) return;
                int roll = BuildQueue.rroll(x, y, z, 4);
                q.set(x, y, z, roll < 3 ? VEIN[roll % VEIN.length]
                        : roll < 36 ? ROCK : roll < 58 ? ROCK_3 : roll < 78 ? ROCK_2 : ROCK_5);
            });
        }
        // The plaza surface: worked stone near the mountain, raw rock toward the rim.
        q.disc("foot-plaza", cx, cz, MOUNT_R_BASE - 6, CAMP_R_OUTER, base, base, (x, y, z) -> {
            double dist = BuildQueue.dist(cx, cz, x, z);
            if (dist > CAMP_R_OUTER || dist < MOUNT_R_BASE - 4) return;
            double bear = BuildQueue.bearing(cx, cz, x, z);
            boolean spoke = Math.abs((bear % 22.5) - 11.25) < 0.9;
            boolean ring = Math.abs(dist % 24 - 12) < 0.8;
            int roll = BuildQueue.rroll(x, z, 5);
            if (dist > CAMP_R_OUTER * 0.80) {
                q.set(x, y, z, roll < 14 ? ROCK_3 : roll < 48 ? ROCK_2 : ROCK);
            } else {
                q.set(x, y, z, (spoke || ring) ? PLAZA_TRIM
                        : roll < 8 ? KERB : roll < 30 ? PLAZA_ALT : PLAZA);
            }
        });
        // Rim parapet, open at the four gates.
        q.disc("foot-rim", cx, cz, CAMP_R_OUTER - 2, CAMP_R_OUTER + 1, base + 1, base + 2,
                (x, y, z) -> {
                    double dist = BuildQueue.dist(cx, cz, x, z);
                    if (dist > CAMP_R_OUTER || dist < CAMP_R_OUTER - 1) return;
                    double bear = BuildQueue.bearing(cx, cz, x, z);
                    for (double g : new double[]{BEAR_TRADERS, BEAR_BOSS, BEAR_MINES, BEAR_PVP})
                        if (BuildQueue.angDiff(bear, g) < 5) return;
                    q.set(x, y, z, y == base + 2 ? RAIL : KERB);
                });
        // Buttresses springing from the plaza into the mountain face — makes the foot read as heavy.
        for (int i = 0; i < 24; i++) {
            final double bear = i * 15.0;
            final int bx = cx + (int) Math.round((MOUNT_R_BASE + 8) * Math.cos(BuildQueue.rad(bear)));
            final int bz = cz + (int) Math.round((MOUNT_R_BASE + 8) * Math.sin(BuildQueue.rad(bear)));
            q.around("foot-buttress-" + i, bx, bz, 20, () -> {
                double ux = Math.cos(BuildQueue.rad(bear)), uz = Math.sin(BuildQueue.rad(bear));
                double px = Math.cos(BuildQueue.rad(bear + 90)), pz = Math.sin(BuildQueue.rad(bear + 90));
                for (int out = 0; out <= 26; out++) {
                    int h = (int) Math.round(30 * (1 - out / 26.0));
                    for (int w = -3; w <= 3; w++)
                        for (int y = 1; y <= h; y++) {
                            int x = cx + (int) Math.round(ux * (MOUNT_R_BASE + out) + px * w);
                            int z = cz + (int) Math.round(uz * (MOUNT_R_BASE + out) + pz * w);
                            boolean skin = Math.abs(w) == 3 || y == h;
                            q.set(x, base + y, z, skin ? KERB : ROCK_3);
                        }
                }
            });
        }
    }

    private enum Gate { MINES, TRADERS, PVP_SEALED, BOSS_SEALED }

    private void planGates(BuildQueue q, int cx, int base, int cz) {
        gate(q, cx, base, cz, BEAR_MINES,   Gate.MINES);
        gate(q, cx, base, cz, BEAR_TRADERS, Gate.TRADERS);
        gate(q, cx, base, cz, BEAR_PVP,     Gate.PVP_SEALED);
        gate(q, cx, base, cz, BEAR_BOSS,    Gate.BOSS_SEALED);
    }

    private void gate(BuildQueue q, int cx, int base, int cz, double bear, Gate kind) {
        final double ux = Math.cos(BuildQueue.rad(bear)), uz = Math.sin(BuildQueue.rad(bear));
        final double px = Math.cos(BuildQueue.rad(bear + 90)), pz = Math.sin(BuildQueue.rad(bear + 90));
        final Material stone = switch (kind) {
            case MINES -> ROCK; case TRADERS -> KERB_TRIM;
            case PVP_SEALED -> Material.POLISHED_BLACKSTONE_BRICKS;
            case BOSS_SEALED -> Material.BLACKSTONE;
        };
        final Material trim = switch (kind) {
            case MINES -> KERB_TRIM; case TRADERS -> Material.GOLD_BLOCK;
            case PVP_SEALED -> Material.CHISELED_POLISHED_BLACKSTONE;
            case BOSS_SEALED -> Material.CRYING_OBSIDIAN;
        };
        final int half = 12, h = 26, dist = CAMP_R_OUTER - 7;

        q.around("gate-" + kind, cx, cz, CAMP_R_OUTER + 8, () -> {
            for (int w = -half; w <= half; w++) {
                double span = h - Math.pow((double) w / half, 2) * 6.0;
                for (int y = 0; y <= h; y++) {
                    boolean leg = Math.abs(w) >= half - 3;
                    boolean crown = y >= span - 3 && y <= span + 1;
                    if (!leg && !crown) continue;
                    for (int t = -3; t <= 3; t++) {
                        int x = cx + (int) Math.round(ux * (dist + t) + px * w);
                        int z = cz + (int) Math.round(uz * (dist + t) + pz * w);
                        boolean accent = (leg && y % 6 == 0) || (crown && Math.abs(w) < 4);
                        q.set(x, base + 1 + y, z, accent ? trim : stone);
                    }
                }
            }
            Material[] inlay = kind == Gate.MINES ? ORE_INLAY : kind == Gate.TRADERS ? GEM_INLAY : null;
            if (inlay != null)
                for (int i = 0; i < inlay.length; i++)
                    for (int side : new int[]{-half, half}) {
                        int x = cx + (int) Math.round(ux * dist + px * side);
                        int z = cz + (int) Math.round(uz * dist + pz * side);
                        for (int yy = 0; yy < 2; yy++)
                            q.set(x, base + 5 + i * 4 + yy, z, inlay[i]);
                    }
            if (kind == Gate.PVP_SEALED || kind == Gate.BOSS_SEALED)
                for (int w = -half + 4; w <= half - 4; w++)
                    for (int y = 1; y <= h - 10; y++) {
                        int x = cx + (int) Math.round(ux * dist + px * w);
                        int z = cz + (int) Math.round(uz * dist + pz * w);
                        q.set(x, base + y, z, RAIL);
                    }
        });
    }

    // ---------------------------------------------------------------- the mountain

    /** The cone: a thin hollow shell, craggy, veined with ore. */
    private void planShell(BuildQueue q, int cx, int base, int cz) {
        for (int dy = 0; dy <= CLIMB; dy += BAND) {
            final int y1 = dy, y2 = Math.min(CLIMB, dy + BAND - 1);
            final double rOut = mountR(y1) + 4;
            final double rIn = Math.max(1, mountR(y2) - SHELL - 5);
            q.disc("shell", cx, cz, rIn, rOut, base + y1, base + y2, (x, y, z) -> {
                int h = y - base;
                double r = mountR(h);
                double wob = (BuildQueue.rroll(x / 4, h / 6, z / 4, 6) - 50) * 0.10
                           + (BuildQueue.rroll(x / 11, h / 17, z / 11, 61) - 50) * 0.16;
                double rr = r + wob;
                double d = BuildQueue.dist(cx, cz, x, z);
                if (d > rr) return;
                if (d < rr - SHELL) { q.set(x, y, z, Material.AIR); return; }
                int roll = BuildQueue.rroll(x, y, z, 7);
                q.set(x, y, z, roll < 3 ? VEIN[Math.floorMod(h / 31, VEIN.length)]
                        : roll < 32 ? ROCK : roll < 52 ? ROCK_3
                        : roll < 72 ? ROCK_2 : roll < 88 ? ROCK_4 : ROCK_5);
            });
        }
    }

    /**
     * The inside. The mountain's whole point: a void the full 880 blocks, floored at the bottom,
     * open to the sky at the top. Nothing fills it — the shell simply is not there — so it costs
     * almost nothing. What is built is the floor, the glowing veins that light it, and the
     * gallery ledges that make the scale legible.
     */
    private void planInterior(BuildQueue q, int cx, int base, int cz) {
        // Cavern floor, dished so it reads as carved rather than a lid.
        final int fr = (int) MOUNT_R_BASE - SHELL - 4;
        q.shape("cave-floor", cx - fr, base - 10, cz - fr, cx + fr, base + 2, cz + fr, (x, y, z) -> {
            double d = BuildQueue.dist(cx, cz, x, z);
            if (d > fr) return;
            int dip = (int) Math.round(6 * (1 - Math.pow(d / fr, 2)));
            int surface = base - dip;
            int h = y - base;
            if (h > -dip) { q.set(x, y, z, Material.AIR); return; }
            int roll = BuildQueue.rroll(x, y, z, 8);
            q.set(x, y, z, y == surface ? (roll < 12 ? ROCK_3 : roll < 40 ? ROCK_2 : ROCK)
                    : roll < 5 ? VEIN[roll % VEIN.length] : ROCK);
        });

        // Glowing veins climbing the inner wall — the only light in there, and the scale cue.
        for (int i = 0; i < 30; i++) {
            final int seed = i;
            final double bear0 = i * 12.0;
            q.around("cave-vein-" + i, cx, cz, (int) MOUNT_R_BASE + 4, () -> {
                double bear = bear0;
                for (int h = 6; h < CLIMB - 30; h += 2) {
                    bear += (BuildQueue.rroll(seed, h, 71) - 50) * 0.05;
                    double r = mountR(h) - SHELL - 0.5;
                    int x = cx + (int) Math.round(r * Math.cos(BuildQueue.rad(bear)));
                    int z = cz + (int) Math.round(r * Math.sin(BuildQueue.rad(bear)));
                    Material m = BuildQueue.rroll(x, h, z, 72) < 30
                            ? GLOW_VEIN[Math.floorMod(seed + h / 40, GLOW_VEIN.length)]
                            : VEIN[Math.floorMod(seed, VEIN.length)];
                    q.set(x, base + h, z, m);
                    q.set(x, base + h + 1, z, m);
                }
            });
        }

        // Gallery ledges: thin rings jutting from the inner wall. Without something at known
        // scale, a 900-block void just reads as fog.
        for (int lvl = 1; lvl <= 6; lvl++) {
            final int h = lvl * (CLIMB / 7);
            final double r = mountR(h) - SHELL;
            q.disc("cave-ledge-" + lvl, cx, cz, r - 9, r + 1, base + h, base + h + 1,
                    (x, y, z) -> {
                        double d = BuildQueue.dist(cx, cz, x, z);
                        if (d > r || d < r - 8) return;
                        // Break the ring into arcs so it is not a perfect circle.
                        double bear = BuildQueue.bearing(cx, cz, x, z);
                        if (BuildQueue.rroll((int) (bear / 9), 0, 73) < 28) return;
                        q.set(x, y, z, y == base + h ? ROCK_3 : Material.AIR);
                    });
        }
    }

    /** A great arch through the shell at ground level, so the inside is discoverable on foot. */
    private void planEntrance(BuildQueue q, int cx, int base, int cz) {
        final double bear = BEAR_ENTRANCE;
        final double ux = Math.cos(BuildQueue.rad(bear)), uz = Math.sin(BuildQueue.rad(bear));
        final double px = Math.cos(BuildQueue.rad(bear + 90)), pz = Math.sin(BuildQueue.rad(bear + 90));
        final int half = 16, h = 34;

        q.around("entrance", cx, cz, (int) MOUNT_R_BASE + 14, () -> {
            for (int w = -half - 4; w <= half + 4; w++) {
                double span = h - Math.pow((double) w / half, 2) * h;
                for (int y = 0; y <= h + 6; y++) {
                    for (int dpt = -10; dpt <= SHELL + 12; dpt++) {
                        double rr = MOUNT_R_BASE + 8 - dpt;
                        int x = cx + (int) Math.round(ux * rr + px * w);
                        int z = cz + (int) Math.round(uz * rr + pz * w);
                        int yb = base + y;
                        boolean inside = Math.abs(w) < half && y < span;
                        if (inside) {
                            q.set(x, yb, z, Material.AIR);       // the opening
                        } else if (y <= span + 3 && Math.abs(w) <= half + 3) {
                            int roll = BuildQueue.rroll(x, yb, z, 9);
                            q.set(x, yb, z, roll < 18 ? KERB_TRIM : roll < 55 ? KERB : ROCK);
                        }
                    }
                }
            }
            // Floor through the arch so you can walk in.
            for (int w = -half + 1; w < half; w++)
                for (int dpt = -12; dpt <= SHELL + 14; dpt++) {
                    double rr = MOUNT_R_BASE + 8 - dpt;
                    int x = cx + (int) Math.round(ux * rr + px * w);
                    int z = cz + (int) Math.round(uz * rr + pz * w);
                    q.set(x, base - 1, z, BuildQueue.rroll(x, z, 10) < 25 ? PLAZA_ALT : PLAZA);
                }
        });
    }

    /**
     * THE ROAD. The critical system.
     *
     * <p>A switchback road cut into the mountain face, alternating climbing ramps with dead-flat
     * terraces. Built from the shared height table, so its surface height is a pure function of
     * angle and no two neighbouring blocks can disagree — the failure that made v3 unclimbable.
     *
     * <p>Each stretch lays its own shoulder of rock and then carves itself out of it, which is
     * what keeps a mountain this size affordable: cost scales with road length, not surface area.
     */
    private void planRoad(BuildQueue q, int cx, int base, int cz) {
        final double total = totalAngle();
        final double sweep = 0.30;
        final int chunks = (int) Math.ceil(total / sweep);
        final double start = BuildQueue.rad(BEAR_ROAD_START);

        for (int c = 0; c < chunks; c++) {
            final double a0 = c * sweep, a1 = Math.min(total, (c + 1) * sweep);
            final int rMax = (int) MOUNT_R_BASE + 8;
            q.region("road-" + c, cx - rMax, cz - rMax, cx + rMax, cz + rMax, () -> {
                double step = 0.30 / Math.max(10, roadR(roadSurface(a0)));
                for (double th = a0; th <= a1; th += step) {
                    double surf = roadSurface(th);
                    if (surf > CLIMB) return;
                    int dyi = (int) Math.floor(surf);
                    double frac = surf - dyi;
                    double rc = roadR(surf);
                    double a = th + start;
                    int level = base + dyi;

                    for (int w = -ROAD_W / 2 - ROAD_ROCK; w <= ROAD_W / 2 + 2; w++) {
                        double rr = rc + w;
                        int x = cx + (int) Math.round(rr * Math.cos(a));
                        int z = cz + (int) Math.round(rr * Math.sin(a));
                        boolean walkway = w >= -ROAD_W / 2 && w <= ROAD_W / 2;

                        // Shoulder: solid rock behind and beneath, so the road has a mountain to
                        // be cut into even where the shell is thin.
                        for (int u = 1; u <= 10; u++) {
                            int roll = BuildQueue.rroll(x, level - u, z, 11);
                            q.set(x, level - u, z, roll < 30 ? ROCK_3 : roll < 60 ? ROCK : ROCK_2);
                        }
                        if (!walkway) {
                            // Inner cliff — leave rock standing above the cut.
                            if (w < -ROAD_W / 2)
                                for (int u = 0; u <= ROAD_CLEAR + 6; u++) {
                                    int roll = BuildQueue.rroll(x, level + u, z, 12);
                                    q.set(x, level + u, z, roll < 4 ? VEIN[roll % VEIN.length]
                                            : roll < 40 ? ROCK : roll < 66 ? ROCK_3 : ROCK_2);
                                }
                            continue;
                        }

                        // Tread + half-step.
                        boolean outer = w == ROAD_W / 2;
                        boolean inner = w == -ROAD_W / 2;
                        int roll = BuildQueue.rroll(x, z, 13);
                        q.set(x, level - 1, z, (outer || inner) ? KERB
                                : roll < 20 ? ROAD_2 : roll < 30 ? KERB_TRIM : ROAD);
                        if (frac >= 0.5) q.data(x, level, z, ROAD_HALF, "[type=bottom]");

                        // Carve headroom out of the shoulder.
                        for (int u = (frac >= 0.5 ? 1 : 0); u <= ROAD_CLEAR; u++)
                            q.set(x, level + u, z, Material.AIR);

                        // Outer parapet, gapped so it never becomes a tunnel.
                        if (outer && Math.floorMod(x + z, 9) != 0)
                            q.set(x, level + (frac >= 0.5 ? 1 : 0), z, RAIL);
                    }
                }
            });
        }
    }

    /**
     * Terraces. Every flat stretch of road widens outward into a shelf with mine bays cut into
     * the cliff behind it.
     *
     * <p>Strictly OUTBOARD of the road: a flat shelf laid across the path would flatten whatever
     * rise the road gains over its angular width, which in v4 was a 22-block wall across the
     * route at every landing.
     */
    private void planPlazas(BuildQueue q, int cx, int base, int cz) {
        final double total = totalAngle();
        final double period = 2 * Math.PI / PLAZAS_PER_TURN;
        final double start = BuildQueue.rad(BEAR_ROAD_START);
        int n = 0;

        for (double tt = 0; tt < total; tt += period) {
            final double t0 = tt;                 // lambdas need it effectively final
            if (!onPlaza(t0 + 0.01)) continue;
            final double tc = t0 + PLAZA_ARC / 2;
            if (tc > total) break;
            final double surf = roadSurface(tc);
            if (surf > CLIMB - 12) break;
            final int dy = (int) Math.floor(surf);
            final double rc = roadR(surf);
            final int idx = ++n;
            final int rMax = (int) MOUNT_R_BASE + 10;

            q.region("plaza-" + idx, cx - rMax, cz - rMax, cx + rMax, cz + rMax, () -> {
                int level = base + dy;
                for (double th = t0; th <= t0 + PLAZA_ARC; th += 0.004) {
                    double a = th + start;
                    for (int w = ROAD_W / 2; w <= ROAD_W / 2 + PLAZA_EXTRA; w++) {
                        double rr = rc + w;
                        int x = cx + (int) Math.round(rr * Math.cos(a));
                        int z = cz + (int) Math.round(rr * Math.sin(a));
                        boolean edge = w == ROAD_W / 2 + PLAZA_EXTRA;
                        int roll = BuildQueue.rroll(x, z, 14);
                        q.set(x, level - 1, z, edge ? PLAZA_TRIM
                                : roll < 10 ? KERB : roll < 32 ? PLAZA_ALT : PLAZA);
                        for (int u = 0; u <= 7; u++) q.set(x, level + u, z, Material.AIR);
                        for (int u = 2; u <= 8; u++) q.set(x, level - u, z, ROCK_3);
                        if (edge) q.set(x, level, z, RAIL);
                    }
                }
                // Mine bays cut into the cliff behind the terrace.
                for (int b = 0; b < 3; b++) {
                    double a = t0 + PLAZA_ARC * (0.22 + b * 0.28) + start;
                    double ux = Math.cos(a), uz = Math.sin(a);
                    double pxx = Math.cos(a + Math.PI / 2), pzz = Math.sin(a + Math.PI / 2);
                    double rIn = rc - ROAD_W / 2.0 - 1;
                    for (int w = -6; w <= 6; w++)
                        for (int d = 0; d <= 11; d++)
                            for (int yy = 0; yy <= 8; yy++) {
                                int x = cx + (int) Math.round(ux * (rIn - d) + pxx * w);
                                int z = cz + (int) Math.round(uz * (rIn - d) + pzz * w);
                                boolean face = d == 11 || Math.abs(w) == 6 || yy == 8;
                                int yb = level + yy;
                                if (face) q.set(x, yb, z, yy == 8 ? KERB_TRIM : ROCK);
                                else if (yy == 0) q.set(x, yb, z, PLAZA_TRIM);
                                else q.set(x, yb, z, Material.AIR);
                            }
                }
            });
        }
    }

    /** The summit: a broad plateau with a hole in it, so the cathedral below is visible. */
    private void planSummit(BuildQueue q, int cx, int base, int cz) {
        final int top = base + CLIMB;
        q.around("summit", cx, cz, SUMMIT_R + 8, () -> {
            for (int dx = -SUMMIT_R - 2; dx <= SUMMIT_R + 2; dx++)
                for (int dz = -SUMMIT_R - 2; dz <= SUMMIT_R + 2; dz++) {
                    double d = Math.hypot(dx, dz);
                    if (d > SUMMIT_R) continue;
                    int x = cx + dx, z = cz + dz;
                    if (d < OCULUS_R) {                      // the oculus — open to the void below
                        for (int u = -3; u <= 8; u++) q.set(x, top + u, z, Material.AIR);
                        continue;
                    }
                    boolean lip = d < OCULUS_R + 2;
                    boolean rim = d > SUMMIT_R - 3;
                    int roll = BuildQueue.rroll(x, z, 15);
                    q.set(x, top - 1, z, (lip || rim) ? KERB_TRIM
                            : roll < 12 ? ROCK_CAP : roll < 34 ? PLAZA_ALT : PLAZA);
                    for (int u = 0; u <= 8; u++) q.set(x, top + u, z, Material.AIR);
                    for (int u = 2; u <= 6; u++) q.set(x, top - u, z, ROCK_3);
                    if (lip || rim) q.set(x, top, z, RAIL);
                }
            // Beacon on a mast, set on the rim so it does not cap the oculus.
            int bx = cx + OCULUS_R + 10, bz = cz;
            for (int h = 0; h < BEACON_H - 3; h++)
                q.set(bx, top + h, bz, h % 6 == 0 ? KERB_TRIM : ROAD);
            for (int lvl = 1; lvl <= 3; lvl++) {
                int hh = 4 - lvl;
                for (int x = bx - hh; x <= bx + hh; x++)
                    for (int z = bz - hh; z <= bz + hh; z++)
                        q.set(x, top + BEACON_H - lvl, z, Material.IRON_BLOCK);
            }
            q.set(bx, top + BEACON_H, bz, Material.BEACON);
            q.set(bx, top + BEACON_H + 1, bz, BEAM_GLASS);
        });
    }

    private void planLights(BuildQueue q, int cx, int base, int cz) {
        // The foot plaza.
        q.around("light-foot", cx, cz, CAMP_R_OUTER + 2, () -> {
            for (int ring : new int[]{CAMP_R_OUTER - 14, (int) MOUNT_R_BASE + 22})
                for (int a = 0; a < 360; a += 9) {
                    int x = cx + (int) Math.round(ring * Math.cos(BuildQueue.rad(a)));
                    int z = cz + (int) Math.round(ring * Math.sin(BuildQueue.rad(a)));
                    for (int y = 1; y <= 6; y++) q.set(x, base + y, z, y == 6 ? LAMP : KERB);
                }
        });
        // The road, lit on the cliff side the whole way so the route reads from the ground.
        final double total = totalAngle();
        final double start = BuildQueue.rad(BEAR_ROAD_START);
        final int lightChunks = (int) Math.ceil(total / 1.5);
        for (int c = 0; c < lightChunks; c++) {
            final double a0 = c * 1.5, a1 = Math.min(total, (c + 1) * 1.5);
            final int rMax = (int) MOUNT_R_BASE + 8;
            q.region("light-road-" + c, cx - rMax, cz - rMax, cx + rMax, cz + rMax, () -> {
                for (double th = a0; th < a1; th += 0.03) {
                    double surf = roadSurface(th);
                    if (surf > CLIMB) return;
                    int dyi = (int) Math.floor(surf);
                    double rr = roadR(surf) - ROAD_W / 2.0 + 1;
                    double a = th + start;
                    int x = cx + (int) Math.round(rr * Math.cos(a));
                    int z = cz + (int) Math.round(rr * Math.sin(a));
                    q.set(x, base + dyi + 2, z, LAMP_COOL);
                }
            });
        }
        // Summit.
        q.around("light-summit", cx, cz, SUMMIT_R + 2, () -> {
            int top = base + CLIMB;
            for (int a = 0; a < 360; a += 14) {
                int x = cx + (int) Math.round((OCULUS_R + 5) * Math.cos(BuildQueue.rad(a)));
                int z = cz + (int) Math.round((OCULUS_R + 5) * Math.sin(BuildQueue.rad(a)));
                q.set(x, top, z, KERB_TRIM);
                q.set(x, top + 1, z, GLOW_COOL);
            }
        });
    }

    // ===================================================================== finish

    private void finish(Player player, World world, int cx, int base, int cz, BuildQueue q) {
        int spawnR = (int) ((MOUNT_R_BASE + CAMP_R_OUTER) / 2);
        Location hub = new Location(world, cx + 0.5, base + 1.0, cz + spawnR + 0.5, 180f, -12f);
        world.setSpawnLocation(hub);
        writeHub(hub);

        holoAt(world, cx, base, cz, BEAR_MINES,   CAMP_R_OUTER - 10, 30, "STARTER MINES", "the rock pays first");
        holoAt(world, cx, base, cz, BEAR_TRADERS, CAMP_R_OUTER - 10, 30, "TRADERS", "bring ore, leave armed");
        holoAt(world, cx, base, cz, BEAR_PVP,     CAMP_R_OUTER - 10, 30, "SEALED", "not open yet");
        holoAt(world, cx, base, cz, BEAR_BOSS,    CAMP_R_OUTER - 10, 30, "SEALED", "not open yet");
        holoAt(world, cx, base, cz, BEAR_ENTRANCE, (int) MOUNT_R_BASE + 16, 26,
                "THE HOLLOW", "it is empty all the way up");

        player.sendMessage(gradientBold("THE MOUNTAIN STANDS", HI, LO));
        player.sendMessage(Component.text("  " + (int) (MOUNT_R_BASE * 2) + " across at the foot, "
                + CLIMB + " tall, plaza ring " + (CAMP_R_OUTER * 2) + " across.",
                NamedTextColor.DARK_GRAY));
        player.sendMessage(Component.text("  Road: " + turns() + " turns, "
                + (int) (CLIMB / RAMP_SLOPE) + " blocks of walking, terraces every "
                + (int) Math.toDegrees(2 * Math.PI / PLAZAS_PER_TURN) + "°.",
                NamedTextColor.DARK_GRAY));

        Location summit = new Location(world, cx + OCULUS_R + 6.5, base + CLIMB + 1.0, cz + 0.5);
        ClimbVerifier.Result r = ClimbVerifier.canWalk(world, hub, summit, CAMP_R_OUTER + 30);
        if (r.reachable()) {
            player.sendMessage(Component.text("  ✔ CLIMB VERIFIED — summit reachable on foot ("
                    + r.visited() + " blocks walked).", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("  ✘ CLIMB BROKEN — " + r.detail(), NamedTextColor.RED));
        }
        plugin.getLogger().info("[mountain] verified=" + r.reachable() + " visited=" + r.visited()
                + " highestY=" + r.highestY() + " (" + r.detail() + ")");
    }

    private void verifyFromHere(Player player) {
        World world = player.getWorld();
        var cfg = plugin.getConfig();
        Location hub = new Location(world, cfg.getDouble("hub.x"), cfg.getDouble("hub.y"),
                cfg.getDouble("hub.z"));
        ClimbVerifier.Result r = ClimbVerifier.canWalk(world, hub, player.getLocation(),
                CAMP_R_OUTER + 40);
        player.sendMessage(r.reachable()
                ? Component.text("✔ Walkable from spawn (" + r.visited() + " blocks).", NamedTextColor.GREEN)
                : Component.text("✘ Not walkable from spawn — " + r.detail(), NamedTextColor.RED));
    }

    private void writeHub(Location l) {
        plugin.getConfig().set("hub.x", l.getX());
        plugin.getConfig().set("hub.y", l.getY());
        plugin.getConfig().set("hub.z", l.getZ());
        plugin.getConfig().set("hub.yaw", l.getYaw());
        plugin.getConfig().set("hub.pitch", l.getPitch());
        plugin.saveConfig();
    }

    private void holoAt(World world, int cx, int base, int cz,
                        double bear, int dist, int dy, String title, String sub) {
        double ux = Math.cos(BuildQueue.rad(bear)), uz = Math.sin(BuildQueue.rad(bear));
        Location loc = new Location(world, cx + ux * dist + 0.5, base + dy, cz + uz * dist + 0.5);
        for (Entity e : world.getNearbyEntities(loc, 6, 8, 6))
            if (e instanceof TextDisplay
                    && e.getPersistentDataContainer().has(holoTag, PersistentDataType.BYTE)) e.remove();

        Component text = gradientBold(title, HI, LO)
                .append(Component.newline())
                .append(Component.text(sub, NamedTextColor.GRAY, TextDecoration.ITALIC));
        world.spawn(loc, TextDisplay.class, (Consumer<TextDisplay>) d -> {
            d.text(text);
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(false);
            d.setShadowed(true);
            d.setPersistent(false);
            d.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(), new Vector3f(2.6f, 2.6f, 2.6f), new Quaternionf()));
            d.getPersistentDataContainer().set(holoTag, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private void clearHolos(World world, Location around) {
        for (Entity e : world.getNearbyEntities(around, CAMP_R_OUTER + 30, 500, CAMP_R_OUTER + 30))
            if (e instanceof TextDisplay
                    && e.getPersistentDataContainer().has(holoTag, PersistentDataType.BYTE)) e.remove();
    }

    // ===================================================================== util

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    public static Component gradientBold(String text, TextColor from, TextColor to) {
        Component out = Component.empty();
        int n = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            float t = (float) i / n;
            TextColor c = TextColor.color(
                    Math.round(from.red()   + (to.red()   - from.red())   * t),
                    Math.round(from.green() + (to.green() - from.green()) * t),
                    Math.round(from.blue()  + (to.blue()  - from.blue())  * t));
            out = out.append(Component.text(String.valueOf(text.charAt(i)), c)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return out;
    }
}
