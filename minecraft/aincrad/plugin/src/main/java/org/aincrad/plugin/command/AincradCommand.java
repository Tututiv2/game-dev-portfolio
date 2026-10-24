package org.aincrad.plugin.command;

import org.aincrad.plugin.AincradPlugin;
import org.aincrad.plugin.layer.LayerActivationController;
import org.aincrad.plugin.ledger.GenerationLedger;
import org.aincrad.plugin.mask.MaskResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

public final class AincradCommand implements CommandExecutor, TabCompleter {

    private static final List<String> TOP_LEVEL = List.of("status", "verify", "gen", "layer", "config", "render", "pregen", "tp", "shell", "map", "mapwall");
    private static final List<String> VERIFY_SUB =
            List.of("world", "spawn", "mask", "palace", "buildings", "npc");
    private static final List<String> GEN_SUB = List.of("plan", "status", "resume", "start");
    private static final List<String> GEN_PHASE_SUB =
            List.of("terrain", "roads", "walls", "plaza", "palace", "avenue", "clocktower",
                    "palaceinterior", "districts", "arcade", "streets");
    private static final List<String> LAYER_SUB = List.of("status", "debug");

    private final AincradPlugin plugin;
    private final GenerationLedger ledger;
    private final LayerActivationController layerController;
    private final MaskResult maskResult;

    public AincradCommand(AincradPlugin plugin, GenerationLedger ledger, LayerActivationController layerController,
                           MaskResult maskResult) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.layerController = layerController;
        this.maskResult = maskResult;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /aincrad <status|verify|gen|layer|config>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "tp", "town" -> handleTp(sender);
            case "map" -> handleMap(sender);
            case "mapwall" -> handleMapWall(sender, args);
            case "shell" -> {
                var sp = plugin.getShellRimPhase();
                if (sp == null) {
                    sender.sendMessage("Shell phase unavailable.");
                } else if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
                    sp.stop();
                    sender.sendMessage("Shell/rim stopped after " + sp.chunksDone() + " chunks.");
                } else if (args.length >= 3) {
                    int minR = Integer.parseInt(args[1]);
                    int maxR = Integer.parseInt(args[2]);
                    int cpt = args.length >= 4 ? Integer.parseInt(args[3]) : 4;
                    int n = sp.start(plugin, minR, maxR, cpt);
                    sender.sendMessage("Shell/rim started over " + n + " chunks (r=" + minR + ".." + maxR + ")");
                } else {
                    sender.sendMessage("Usage: /aincrad shell <minRadius> <maxRadius> [chunksPerTick]");
                    sender.sendMessage("       running=" + sp.isRunning() + " chunks=" + sp.chunksDone()
                            + " edits=" + sp.editsApplied());
                }
            }
            case "render" -> handleRender(sender);
            case "pregen" -> handlePregen(sender, args);
            case "status" -> handleStatus(sender);
            case "verify" -> handleVerify(sender, args);
            case "gen" -> handleGen(sender, args);
            case "layer" -> handleLayer(sender);
            case "config" -> handleConfig(sender);
            default -> sender.sendMessage("Unknown subcommand: " + args[0]);
        }
        return true;
    }

    private void handlePregen(CommandSender sender, String[] args) {
        var driver = plugin.getPregenerationDriver();
        if (driver == null) {
            sender.sendMessage("Pregeneration driver unavailable (world not ready at enable time).");
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase() : "status";
        switch (sub) {
            case "start" -> {
                int chunksPerTick = args.length >= 3 ? Integer.parseInt(args[2]) : 4;
                int limit = args.length >= 4 ? Integer.parseInt(args[3]) : 0;
                driver.start(plugin, chunksPerTick, limit);
                sender.sendMessage("Pregeneration started: " + chunksPerTick + " chunks/tick"
                        + (limit > 0 ? (", limit " + limit) : ", to completion"));
            }
            case "stop" -> {
                driver.stop();
                sender.sendMessage("Pregeneration stopped at cursor " + driver.cursor());
            }
            default -> {
                driver.loadCursor();
                int total = driver.totalChunks();
                sender.sendMessage("=== Full Floor One pregeneration ===");
                sender.sendMessage("  total in-disc chunks : " + total);
                sender.sendMessage("  cursor (resume point): " + driver.cursor()
                        + String.format("  (%.2f%%)", driver.cursor() * 100.0 / total));
                sender.sendMessage("  running              : " + driver.isRunning());
                if (driver.chunksDone() > 0) {
                    sender.sendMessage("  this run             : " + driver.chunksDone() + " chunks, "
                            + driver.editsApplied() + " edits, "
                            + String.format("%.1f", driver.chunksPerSecond()) + " chunks/s");
                    double remaining = (total - driver.cursor()) / Math.max(0.1, driver.chunksPerSecond());
                    sender.sendMessage("  projected remaining  : "
                            + String.format("%.1f hours at this rate", remaining / 3600.0));
                }
            }
        }
    }

    /**
     * Puts a player at the Town of Beginnings arrival point in the Aincrad dimension.
     *
     * <p>Exists because joining the server drops you in the vanilla overworld (server.properties
     * still has {@code level-name=world}); the Town lives in {@code aincrad:floors_01_10}, a
     * separate dimension, so without this you would have to type the {@code /execute in … run
     * teleport} form by hand and would reasonably conclude the Town was missing.</p>
     */
    private void handleTp(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this in-game, as a player.");
            return;
        }
        NamespacedKey key = NamespacedKey.fromString("aincrad:floors_01_10");
        World world = key != null ? Bukkit.getWorld(key) : null;
        if (world == null) {
            sender.sendMessage("aincrad:floors_01_10 is not loaded.");
            return;
        }
        // Land on top of whatever is actually there, so a regraded plaza or a roof cannot suffocate.
        int x = 0, z = 4416;
        int y = Math.max(world.getHighestBlockYAt(x, z) + 1, 323);
        player.teleport(new Location(world, x + 0.5, y, z + 0.5, 180f, 0f));  // facing north, at the Palace
        player.setAllowFlight(true);
        player.sendMessage("Welcome to the Town of Beginnings.");
        player.sendMessage("You are in the Central Plaza facing north toward Black Iron Palace.");
        player.sendMessage("Flight enabled. Landmarks: Palace north, market east, crafting quarter west,");
        player.sendMessage("church southwest, the wall ring around you, and the southern rim beyond it.");
    }

    /**
     * Gives the player an in-game map of the Town, centred on the plaza at the widest zoom.
     *
     * <p>At {@link org.bukkit.map.MapView.Scale#FARTHEST} one map covers 2,048 blocks, which is wide
     * enough to hold the entire 1,000-block town plus its surroundings. Deliberately left unlocked
     * so it fills in as the player flies around - a locked map is a frozen snapshot and would show
     * nothing but the chunks already explored.</p>
     */
    private void handleMap(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this in-game, as a player.");
            return;
        }
        NamespacedKey key = NamespacedKey.fromString("aincrad:floors_01_10");
        World world = key != null ? Bukkit.getWorld(key) : null;
        if (world == null) {
            sender.sendMessage("aincrad:floors_01_10 is not loaded.");
            return;
        }
        org.bukkit.map.MapView view = Bukkit.createMap(world);
        view.setScale(org.bukkit.map.MapView.Scale.FARTHEST);
        view.setCenterX(0);
        view.setCenterZ(4500);
        view.setTrackingPosition(true);
        view.setUnlimitedTracking(true);

        org.bukkit.inventory.ItemStack item =
                new org.bukkit.inventory.ItemStack(org.bukkit.Material.FILLED_MAP);
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.MapMeta meta) {
            meta.setMapView(view);
            meta.setDisplayName("Floor One - Town of Beginnings");
            item.setItemMeta(meta);
        }
        player.getInventory().addItem(item);
        sender.sendMessage("Map added to your inventory (covers 2048 blocks, centred on the plaza).");
        sender.sendMessage("Hold it and fly around - it fills in as you explore.");
        sender.sendMessage("Tip: press F3+G to see chunk borders, or F1 to hide the HUD for screenshots.");
    }

    /**
     * Builds the big shared wall map on the plaza's northern side. Fully revealed on build, because
     * each tile paints from world data rather than from anyone's exploration history.
     */
    private void handleMapWall(CommandSender sender, String[] args) {
        NamespacedKey key = NamespacedKey.fromString("aincrad:floors_01_10");
        World world = key != null ? Bukkit.getWorld(key) : null;
        if (world == null) {
            sender.sendMessage("aincrad:floors_01_10 is not loaded.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            sender.sendMessage(org.aincrad.plugin.render.FloorMapBoard.cancel()
                    ? "Map board build cancelled." : "No map board build is running.");
            return;
        }
        // Default 6 blocks/pixel: a 5x3 board then covers 3840x2304 blocks, comfortably the whole
        // 1,000-block town plus its road approaches.
        int bpp = args.length >= 2 ? Integer.parseInt(args[1]) : 6;
        int cols = args.length >= 3 ? Integer.parseInt(args[2]) : 5;
        int rows = args.length >= 4 ? Integer.parseInt(args[3]) : 3;
        // The board defaults to centring on the town, but a whole-floor board has to centre on the
        // floor instead - the floor is a disc about (0,0) and the town sits near its southern edge,
        // so a floor-wide board centred on the town would spend half its pixels on empty void.
        // "/aincrad mapwall 16 5 5 0 0" is the entire 10,000-block floor.
        int centreX = args.length >= 5 ? Integer.parseInt(args[4]) : 0;
        int centreZ = args.length >= 6 ? Integer.parseInt(args[5]) : 4500;
        int boardX = -2, boardTopY = 328, boardZ = 4390;
        String plan = org.aincrad.plugin.render.FloorMapBoard.start(plugin, world, sender,
                centreX, centreZ, bpp, cols, rows, boardX, boardTopY, boardZ);
        if (plan == null) {
            sender.sendMessage("A map board build is already running. /aincrad mapwall stop to cancel.");
            return;
        }
        sender.sendMessage("Building map board: " + plan);
        sender.sendMessage("This runs a few chunks per tick so the server keeps ticking; progress");
        sender.sendMessage("is reported here. It stands on the plaza's north side at (" + boardX
                + ", " + boardZ + ") - walk up and look north.");
    }

    private void handleRender(CommandSender sender) {
        NamespacedKey key = NamespacedKey.fromString("aincrad:floors_01_10");
        World world = key != null ? Bukkit.getWorld(key) : null;
        if (world == null || maskResult == null) {
            sender.sendMessage("World or mask unavailable.");
            return;
        }
        // getDataFolder() can be relative, so walking parents off it may hit null. Resolve to an
        // absolute path first: <project>/server/plugins/AincradPlugin -> <project>/visuals.
        java.io.File dataFolder = plugin.getDataFolder().getAbsoluteFile();
        java.io.File projectRoot = dataFolder.getParentFile()   // plugins
                .getParentFile()                                 // server
                .getParentFile();                                // project root
        java.io.File out = new java.io.File(projectRoot, "visuals");
        sender.sendMessage("Rendering overhead map from the live world (this loads chunks)...");
        try {
            var r = org.aincrad.plugin.render.OverheadMapRenderer.render(
                    world, maskResult, out, -640, 3980, 660, 5030, 2);
            sender.sendMessage("  colour map   : " + r.colourMap().getAbsolutePath());
            sender.sendMessage("  elevation map: " + r.elevationMap().getAbsolutePath());
            sender.sendMessage("  " + r.width() + "x" + r.height() + " px, " + r.sampled()
                    + " in-mask samples, surface Y " + r.minY() + ".." + r.maxY());
        } catch (java.io.IOException e) {
            sender.sendMessage("Render failed: " + e.getMessage());
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Render failed", e);
        }
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage("=== Aincrad status ===");
        sender.sendMessage("Plugin version: " + plugin.getDescription().getVersion());
        if (maskResult != null) {
            sender.sendMessage("Mask: " + maskResult.total() + " chunks (" + (maskResult.passes() ? "PASS" : "FAIL") + ")");
        } else {
            sender.sendMessage("Mask: not compiled - check console log");
        }
        if (ledger != null) {
            try {
                Map<GenerationLedger.FeatureState, Integer> counts = ledger.countByState();
                sender.sendMessage("Ledger feature states: " + counts);
            } catch (SQLException e) {
                sender.sendMessage("Ledger: error reading state (" + e.getMessage() + ")");
            }
        } else {
            sender.sendMessage("Ledger: not available");
        }
        sender.sendMessage("Online players: " + Bukkit.getOnlinePlayers().size());
        if (plugin.getMaskEnforcer() != null) {
            sender.sendMessage("Mask violations detected this session: " + plugin.getMaskEnforcer().getViolationCount());
        }
    }

    private void handleVerify(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /aincrad verify <world|spawn|mask>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "world" -> {
                NamespacedKey key = NamespacedKey.fromString("aincrad:floors_01_10");
                World world = key != null ? Bukkit.getWorld(key) : null;
                if (world == null) {
                    sender.sendMessage("aincrad:floors_01_10 is NOT loaded.");
                    return;
                }
                sender.sendMessage("aincrad:floors_01_10 loaded. minHeight=" + world.getMinHeight()
                        + " maxHeight=" + world.getMaxHeight() + " (inclusive top build Y="
                        + (world.getMaxHeight() - 1) + ")");
                sender.sendMessage("World border: center=" + world.getWorldBorder().getCenter()
                        + " size=" + world.getWorldBorder().getSize());
                sender.sendMessage("Spawn location: " + world.getSpawnLocation());
                // Each sample names what it is meant to prove, so a wrong result is obvious.
                Object[][] samplePoints = {
                        {0, 4416, "plaza centre (expect paving)"},
                        {200, 4000, "NEW north wall (expect stone brick)"},
                        {-200, 4000, "NEW north wall (expect stone brick)"},
                        {500, 4200, "NEW east side wall (expect stone brick)"},
                        {-500, 4200, "NEW west side wall (expect stone brick)"},
                        {0, 5000, "south arc, unchanged from v1 (expect stone brick)"},
                        {400, 4200, "WAS v1 circle wall, now interior (expect GRASS = demolished)"},
                        {-400, 4200, "WAS v1 circle wall, now interior (expect GRASS = demolished)"},
                        {0, 4000, "north gate opening (expect NO wall)"},
                        {-380, 4000, "NW gate opening (expect NO wall)"},
                        {380, 4000, "NE gate opening (expect NO wall)"},
                        {0, 4350, "plaza deck N (expect FLAT Y=322)"},
                        {0, 4480, "plaza deck S (expect FLAT Y=322)"},
                        {-90, 4416, "plaza deck W (expect FLAT Y=322)"},
                        {90, 4416, "plaza deck E (expect FLAT Y=322)"},
                        {0, 4136, "palace dome apex (expect Y=386-387 iron crown)"},
                        {60, 4136, "palace roof terrace (expect Y=366 deepslate)"},
                        {0, 4210, "palace hall south wall (expect blackstone)"},
                        {160, 4136, "palace podium edge (expect Y=328 deck)"},
                };
                for (Object[] p : samplePoints) {
                    int x = (Integer) p[0], z = (Integer) p[1];
                    int highestY = world.getHighestBlockYAt(x, z);
                    String blockType = world.getBlockAt(x, highestY, z).getType().name();
                    sender.sendMessage("  (" + x + "," + z + ") Y=" + highestY + " " + blockType
                            + "   <- " + p[2]);
                }
            }
            case "palace" -> {
                NamespacedKey key = NamespacedKey.fromString("aincrad:floors_01_10");
                World world = key != null ? Bukkit.getWorld(key) : null;
                if (world == null) {
                    sender.sendMessage("aincrad:floors_01_10 is NOT loaded.");
                    return;
                }
                // Depth probes at exact coordinates - getHighestBlockYAt cannot see inside a
                // hollow structure, so shell/interior claims need direct block reads.
                Object[][] probes = {
                        {0, 328, 4136, "AIR?no", "hall floor = podium deck"},
                        {0, 340, 4136, "AIR", "hall interior hollow (for later interiors phase)"},
                        {0, 350, 4136, "AIR", "hall interior hollow, upper"},
                        // Offset from the axis: X=0 here is the grand entrance archway, not wall.
                        {40, 340, 4210, "AIR?no", "hall SOUTH wall exists at depth (off-axis)"},
                        {0, 335, 4211, "AIR", "grand entrance archway is open on the axis"},
                        {0, 340, 4062, "AIR?no", "hall NORTH wall exists at depth"},
                        {110, 340, 4136, "AIR?no", "hall EAST wall exists at depth"},
                        {83, 360, 4136, "AIR?no", "drum wall at r=83"},
                        {70, 360, 4136, "AIR", "drum interior open at r=70"},
                        // Stair walkability: each tread solid with air directly above it proves a
                        // 1-block riser. A 2-block riser (the v1 bug) would show solid at the
                        // "air above" probe.
                        {0, 327, 4222, "AIR?no", "palace stair tread 1 solid"},
                        {0, 328, 4222, "AIR", "stair 1 riser is ONE block (air above tread)"},
                        {0, 326, 4223, "AIR?no", "palace stair tread 2 solid"},
                        {0, 327, 4223, "AIR", "stair 2 riser is ONE block"},
                        {0, 324, 4225, "AIR?no", "palace stair tread 4 solid"},
                        {0, 325, 4225, "AIR", "stair 4 riser is ONE block"},
                        {0, 322, 4227, "AIR?no", "stair foot lands on avenue level Y=322"},
                        {0, 323, 4227, "AIR", "stair foot headroom clear"},
                        {0, 322, 4250, "AIR?no", "processional avenue deck (flat Y=322)"},
                        {0, 322, 4290, "AIR?no", "processional avenue deck (flat Y=322)"},
                        {0, 323, 4250, "AIR", "avenue headroom clear"},
                        {0, 322, 4416, "AIR?no", "Teleport Gate floor"},
                        {0, 330, 4416, "AIR", "Teleport Gate walkthrough clear (not a solid wall)"},
                        {5, 330, 4416, "AIR?no", "Teleport Gate post"},
                        {0, 366, 4416, "AIR?no", "clock tower crown at 44 blocks above deck"},
                        {7, 355, 4416, "AIR?no", "clock dial, east face"},
                        {0, 361, 4416, "AIR?no", "bell"},
                        // Interiors: rooms exist, are enterable, and the sealed one is truly sealed.
                        {0, 330, 4190, "AIR", "processional nave is open floor space"},
                        {0, 328, 4190, "AIR?no", "nave floor"},
                        {17, 330, 4190, "AIR?no", "nave/gallery partition is solid here"},
                        {17, 330, 4182, "AIR", "nave->gallery DOORWAY is open"},
                        {0, 330, 4085, "AIR", "prison-processing hall interior open"},
                        {0, 330, 4106, "AIR", "processing hall doorway open on axis"},
                        {-85, 330, 4080, "AIR", "sealed resurrection chamber void exists"},
                        {-63, 330, 4080, "AIR?no", "resurrection chamber is SEALED (no door)"},
                        {-63, 330, 4097, "AIR", "west offices ARE reachable (door open)"},
                        {0, 345, 4190, "AIR?no", "room ceiling present over nave"},
                        {0, 350, 4136, "AIR", "Monument Hall still open to the dome (no ceiling)"},
                        {31, 330, 4120, "AIR?no", "Monument Hall <-> gallery wall is solid"},
                        {31, 330, 4136, "AIR", "Monument Hall <-> gallery DOORWAY open"},
                        {50, 345, 4136, "AIR?no", "side gallery HAS a ceiling (not swallowed by hall)"},
                        {0, 345, 4085, "AIR?no", "processing hall ceiling present"},
                        {0, 317, 4055, "AIR", "underground water seam tunnel is open"},
                        {144, 317, 4030, "AIR", "seam reaches toward hidden-dungeon intake"},
                        {0, 386, 4136, "AIR?no", "dome apex shell"},
                        {30, 382, 4136, "AIR?no", "dome shell at r=30"},
                        {45, 372, 4136, "AIR?no", "dome shell near springline r=45"},
                        {0, 375, 4136, "AIR", "under-dome void is open"},
                };
                int fails = 0;
                for (Object[] p : probes) {
                    int x = (Integer) p[0], y = (Integer) p[1], z = (Integer) p[2];
                    String want = (String) p[3];
                    String got = world.getBlockAt(x, y, z).getType().name();
                    boolean ok = want.equals("AIR") ? got.equals("AIR") : !got.equals("AIR");
                    if (!ok) {
                        fails++;
                    }
                    sender.sendMessage("  " + (ok ? "PASS" : "FAIL") + " (" + x + "," + y + "," + z + ") "
                            + got + "   <- " + p[4]);
                }
                sender.sendMessage(fails == 0 ? "Palace shell: ALL PROBES PASS"
                        : "Palace shell: " + fails + " PROBE(S) FAILED");
            }
            case "npc" -> {
                NamespacedKey key = NamespacedKey.fromString("aincrad:floors_01_10");
                World world = key != null ? Bukkit.getWorld(key) : null;
                var phase = plugin.getDistrictGenerationPhase();
                var reg = plugin.getNpcRegistry();
                if (world == null || phase == null) {
                    sender.sendMessage("World or district phase unavailable.");
                    return;
                }
                var parcels = phase.plan();
                var roster = reg.build(parcels, phase.terrainProfile(), phase.worldSeed());
                reg.persist(ledger, plugin.getLogger());

                sender.sendMessage("=== NPC layer ===");
                boolean countOk = roster.size() >= 1200 && roster.size() <= 1800;
                sender.sendMessage("  logical records       : " + roster.size()
                        + "  (spec 1200-1800) " + (countOk ? "PASS" : "FAIL"));
                java.util.Map<String, Integer> byOcc = new java.util.TreeMap<>();
                int inInterior = 0;
                for (var r : roster) {
                    byOcc.merge(r.occupation().name(), 1, Integer::sum);
                    if (world.getBlockAt(r.x(), r.y(), r.z()).getType().isAir()) {
                        inInterior++;
                    }
                }
                sender.sendMessage("  by occupation         : " + byOcc);
                sender.sendMessage("  stand inside buildings: " + inInterior + "/" + roster.size()
                        + (inInterior == roster.size() ? "  PASS" : "  FAIL"));
                try {
                    sender.sendMessage("  persisted rows        : " + ledger.countNpcs());
                } catch (SQLException e) {
                    sender.sendMessage("  persisted rows        : error " + e.getMessage());
                }

                // Budget stress test: a worst-case crowd standing in the densest districts.
                java.util.List<int[]> crowd = new java.util.ArrayList<>();
                for (int cx = -400; cx <= 400; cx += 80) {
                    for (int cz = 4040; cz <= 4900; cz += 80) {
                        crowd.add(new int[]{cx, 323, cz});
                    }
                }
                var active = reg.computeActive(crowd);
                java.util.Map<Long, Integer> perCluster = new java.util.HashMap<>();
                for (var r : active) {
                    perCluster.merge(r.clusterKey(), 1, Integer::sum);
                }
                int worstCluster = perCluster.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                sender.sendMessage("  stress: " + crowd.size() + " simulated players across town");
                sender.sendMessage("  active town-wide      : " + active.size() + "/"
                        + reg.townWideBudget() + (active.size() <= reg.townWideBudget()
                        ? "  PASS" : "  FAIL"));
                sender.sendMessage("  worst 10-chunk cluster: " + worstCluster + "/"
                        + reg.localClusterBudget() + (worstCluster <= reg.localClusterBudget()
                        ? "  PASS" : "  FAIL"));

                // Floor-awareness: a player on Floor 7's band must not wake Floor 1 residents.
                var upper = reg.computeActive(java.util.List.of(new int[]{0, 1300, 4416}));
                sender.sendMessage("  Floor-7 player wakes F1: " + upper.size()
                        + (upper.isEmpty() ? "  PASS (floor-aware)" : "  FAIL"));

                // The spec forbids an armour-stand population; prove none were spawned.
                long stands = world.getEntities().stream()
                        .filter(e -> e.getType() == org.bukkit.entity.EntityType.ARMOR_STAND).count();
                long living = world.getLivingEntities().size();
                sender.sendMessage("  armour stands in world: " + stands
                        + (stands == 0 ? "  PASS (no entity population)" : "  FAIL"));
                sender.sendMessage("  living entities loaded: " + living);
            }
            case "buildings" -> {
                NamespacedKey key = NamespacedKey.fromString("aincrad:floors_01_10");
                World world = key != null ? Bukkit.getWorld(key) : null;
                var phase = plugin.getDistrictGenerationPhase();
                if (world == null || phase == null) {
                    sender.sendMessage("World or district phase unavailable.");
                    return;
                }
                var parcels = phase.plan();
                int total = 0, doorOk = 0, interiorOk = 0, roofOk = 0, headroomOk = 0;
                int twoStorey = 0, cellars = 0, upperOk = 0;
                int skyEnclosedOk = 0, highestOk = 0, wallsOk = 0, stairsOk = 0, bedOk = 0;
                java.util.List<String> failures = new java.util.ArrayList<>();
                for (var p : parcels) {
                    var bp = org.aincrad.plugin.district.BuildingBuilder.plan(
                            p, phase.terrainProfile(), phase.worldSeed());
                    total++;
                    // 1. A real door on the street frontage.
                    var doorType = world.getBlockAt(bp.doorX(), bp.baseY() + 1, bp.doorZ()).getType();
                    boolean d = doorType.name().endsWith("_DOOR");
                    if (d) {
                        doorOk++;
                    } else if (failures.size() < 5) {
                        failures.add(p.id() + " no door @" + bp.doorX() + "," + bp.doorZ() + " got " + doorType);
                    }
                    // 2. The square behind the door is open - the no-fake-doors rule.
                    boolean i1 = world.getBlockAt(bp.insideX(), bp.baseY() + 1, bp.insideZ()).getType().isAir();
                    boolean i2 = world.getBlockAt(bp.insideX(), bp.baseY() + 2, bp.insideZ()).getType().isAir();
                    if (i1 && i2) {
                        interiorOk++;
                    } else if (failures.size() < 5) {
                        failures.add(p.id() + " door opens into solid @" + bp.insideX() + "," + bp.insideZ());
                    }
                    // 3. Standing headroom at the room centre.
                    boolean h = world.getBlockAt(p.centreX(), bp.baseY() + 1, p.centreZ()).getType().isAir()
                            && world.getBlockAt(p.centreX(), bp.baseY() + 2, p.centreZ()).getType().isAir();
                    if (h) {
                        headroomOk++;
                    }
                    // 4. Roof closed above the centre. Scan the whole roof zone rather than a
                    // fixed offset: the ridge sits at wallTop+1+rise where rise varies 3-6 with
                    // the building's span, so probing a fixed height lands in the attic void and
                    // reports a false failure (it did - 0/1150 in boot-test23.log).
                    boolean r = false;
                    for (int y = bp.wallTop() + 1; y <= bp.wallTop() + 12 && !r; y++) {
                        if (!world.getBlockAt(p.centreX(), y, p.centreZ()).getType().isAir()) {
                            r = true;
                        }
                    }
                    if (r) {
                        roofOk++;
                    } else if (failures.size() < 5) {
                        failures.add(p.id() + " no roof above centre (wallTop " + bp.wallTop() + ")");
                    }
                    // 5. INDEPENDENT check A - full-coverage roof test. Every interior column must
                    // have something solid above the wall top, so an actual hole anywhere in the
                    // roof fails, not just one at the centre. Uses the heightmap, a different code
                    // path from the scan in check 4.
                    boolean sealed = true;
                    for (int sx = p.minX() + 1; sx <= p.maxX() - 1 && sealed; sx++) {
                        for (int sz = p.minZ() + 1; sz <= p.maxZ() - 1; sz++) {
                            if (world.getHighestBlockYAt(sx, sz) <= bp.wallTop()) {
                                sealed = false;
                                break;
                            }
                        }
                    }
                    if (sealed) {
                        skyEnclosedOk++;
                    } else if (failures.size() < 6) {
                        failures.add(p.id() + " roof hole: an interior column is open to sky");
                    }
                    // 6. INDEPENDENT check B - a different Bukkit API path from the scan loop.
                    if (world.getHighestBlockYAt(p.centreX(), p.centreZ()) > bp.wallTop()) {
                        highestOk++;
                    }
                    // 7. Wall integrity above the window band. Roof overhangs of neighbouring
                    // terraces write into each other's columns, so this checks a taller neighbour's
                    // wall was not punched through by an adjacent roof's clearing pass.
                    boolean w = !world.getBlockAt(p.minX(), bp.baseY() + 4, p.centreZ()).getType().isAir()
                            && !world.getBlockAt(p.maxX(), bp.baseY() + 4, p.centreZ()).getType().isAir()
                            && !world.getBlockAt(p.centreX(), bp.baseY() + 4, p.minZ()).getType().isAir()
                            && !world.getBlockAt(p.centreX(), bp.baseY() + 4, p.maxZ()).getType().isAir();
                    if (w) {
                        wallsOk++;
                    } else if (failures.size() < 6) {
                        failures.add(p.id() + " wall gap at y=" + (bp.baseY() + 4));
                    }
                    if (bp.twoStorey()) {
                        twoStorey++;
                        if (world.getBlockAt(p.centreX(), bp.ceil1() + 1, p.centreZ()).getType().isAir()) {
                            upperOk++;
                        }
                        // 8. Staircase actually climbable: treads rising exactly 1 each, 2 air above.
                        boolean st = true;
                        for (int i = 0; i < 3 && st; i++) {
                            int sx = p.minX() + 1 + i;
                            int sy = bp.baseY() + 1 + i;
                            int sz = p.minZ() + 1;
                            if (sx >= p.maxX()) {
                                break;
                            }
                            boolean tread = world.getBlockAt(sx, sy, sz).getType().name().endsWith("_STAIRS");
                            boolean clear = world.getBlockAt(sx, sy + 1, sz).getType().isAir()
                                    && world.getBlockAt(sx, sy + 2, sz).getType().isAir();
                            st = tread && clear;
                        }
                        if (st) {
                            stairsOk++;
                        } else if (failures.size() < 6) {
                            failures.add(p.id() + " staircase not climbable");
                        }
                        // 9. Bed on the upper floor.
                        boolean bed = false;
                        for (int bx = p.maxX() - 3; bx <= p.maxX() - 1 && !bed; bx++) {
                            for (int bz = p.maxZ() - 3; bz <= p.maxZ() - 1; bz++) {
                                if (world.getBlockAt(bx, bp.ceil1() + 1, bz).getType().name().endsWith("_BED")) {
                                    bed = true;
                                    break;
                                }
                            }
                        }
                        if (bed) {
                            bedOk++;
                        }
                    }
                    if (bp.cellar()) {
                        cellars++;
                    }
                }
                sender.sendMessage("=== Building acceptance over ALL " + total + " parcels ===");
                sender.sendMessage("  real door on frontage : " + doorOk + "/" + total
                        + (doorOk == total ? "  PASS" : "  FAIL"));
                sender.sendMessage("  door opens to interior: " + interiorOk + "/" + total
                        + (interiorOk == total ? "  PASS" : "  FAIL"));
                sender.sendMessage("  centre headroom >=2   : " + headroomOk + "/" + total
                        + (headroomOk == total ? "  PASS" : "  FAIL"));
                sender.sendMessage("  roof closed overhead  : " + roofOk + "/" + total
                        + (roofOk == total ? "  PASS" : "  FAIL"));
                int upperPct = total == 0 ? 0 : twoStorey * 100 / total;
                int cellarPct = total == 0 ? 0 : cellars * 100 / total;
                sender.sendMessage("  accessible upper floor: " + twoStorey + "/" + total + " = "
                        + upperPct + "%  (spec >=45%) " + (upperPct >= 45 ? "PASS" : "FAIL"));
                sender.sendMessage("  cellar space          : " + cellars + "/" + total + " = "
                        + cellarPct + "%  (spec >=12%) " + (cellarPct >= 12 ? "PASS" : "FAIL"));
                sender.sendMessage("  upper storey walkable : " + upperOk + "/" + twoStorey);
                sender.sendMessage("--- independent checks (different method from the scan above) ---");
                sender.sendMessage("  every column roofed   : " + skyEnclosedOk + "/" + total
                        + (skyEnclosedOk == total ? "  PASS" : "  FAIL"));
                // Diagnostic: is stored sky-light data even trustworthy here? These three points
                // are buried under hundreds of blocks of solid fill, so any non-zero reading means
                // the light data is stale and a light-based enclosure test would be meaningless.
                int b1 = world.getBlockAt(0, 100, 4416).getLightFromSky();
                int b2 = world.getBlockAt(0, 200, 4700).getLightFromSky();
                int b3 = world.getBlockAt(-85, 330, 4080).getLightFromSky();  // sealed iron chamber
                sender.sendMessage("  [diag] skylight deep underground (0,100,4416)=" + b1
                        + " (0,200,4700)=" + b2 + " sealed-chamber(-85,330,4080)=" + b3
                        + (b1 == 0 && b2 == 0 && b3 == 0
                        ? "  -> light data trustworthy" : "  -> LIGHT DATA STALE, ignore light tests"));
                sender.sendMessage("  highestBlock > walls  : " + highestOk + "/" + total
                        + (highestOk == total ? "  PASS" : "  FAIL"));
                sender.sendMessage("  wall integrity        : " + wallsOk + "/" + total
                        + (wallsOk == total ? "  PASS" : "  FAIL"));
                sender.sendMessage("  staircase climbable   : " + stairsOk + "/" + twoStorey
                        + (stairsOk == twoStorey ? "  PASS" : "  FAIL"));
                sender.sendMessage("  bed on upper floor    : " + bedOk + "/" + twoStorey
                        + (bedOk == twoStorey ? "  PASS" : "  FAIL"));
                for (String f : failures) {
                    sender.sendMessage("  e.g. " + f);
                }
            }
            case "spawn" -> {
                NamespacedKey key = NamespacedKey.fromString("aincrad:floors_01_10");
                World world = key != null ? Bukkit.getWorld(key) : null;
                var phase = plugin.getDistrictGenerationPhase();
                if (world == null || phase == null) {
                    sender.sendMessage("World or district phase unavailable.");
                    return;
                }
                sender.sendMessage("=== Spawn / safe zone / services ===");

                // Arrival volume must be collision-safe: spec wants at least 9 x 7 x 5 clear.
                int ax = 0, ay = 323, az = 4416;
                int clear = 0, checked = 0;
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = 0; dy < 5; dy++) {
                            checked++;
                            if (world.getBlockAt(ax + dx, ay + dy, az + dz).getType().isAir()) {
                                clear++;
                            }
                        }
                    }
                }
                sender.sendMessage("  arrival volume 9x7x5 clear: " + clear + "/" + checked
                        + (clear == checked ? "  PASS" : "  FAIL"));
                sender.sendMessage("  stands on solid ground    : "
                        + (!world.getBlockAt(ax, ay - 1, az).getType().isAir() ? "PASS" : "FAIL"));

                // Safe zone: Y-awareness is the part that is easy to get wrong.
                boolean sPlaza = org.aincrad.plugin.safety.SafeZone.isSafe(0, 323, 4416);
                boolean sApron = org.aincrad.plugin.safety.SafeZone.isSafe(0, 323, 4010);
                boolean sOutside = org.aincrad.plugin.safety.SafeZone.isSafe(0, 323, 3900);
                boolean sCave = org.aincrad.plugin.safety.SafeZone.isSafe(0, 150, 4416);
                boolean sVoidApron = org.aincrad.plugin.safety.SafeZone.isSafe(0, 323, 5100);
                sender.sendMessage("  plaza is safe             : " + (sPlaza ? "PASS" : "FAIL"));
                sender.sendMessage("  inside gate apron is safe : " + (sApron ? "PASS" : "FAIL"));
                sender.sendMessage("  far outside NOT safe      : " + (!sOutside ? "PASS" : "FAIL"));
                sender.sendMessage("  cave under town NOT safe  : " + (!sCave ? "PASS" : "FAIL")
                        + "   (Y-aware)");
                sender.sendMessage("  past floor edge NOT safe  : " + (!sVoidApron ? "PASS" : "FAIL"));
                var guard = plugin.getSafeZoneGuard();
                if (guard != null) {
                    sender.sendMessage("  guard blocked so far      : spawns=" + guard.blockedSpawns()
                            + " pvp=" + guard.blockedPvp() + " fire=" + guard.blockedFire()
                            + " explosions=" + guard.blockedExplosions());
                }

                // Service anchors must all bind to real, built parcels.
                var parcels = phase.plan();
                var anchors = plugin.getServiceAnchors()
                        .resolve(parcels, phase.terrainProfile(), phase.worldSeed());
                int bound = anchors.size();
                int required = org.aincrad.plugin.service.ServiceAnchor.ServiceType.values().length;
                sender.sendMessage("  service anchors bound     : " + bound + "/" + required
                        + (bound == required ? "  PASS" : "  FAIL"));
                int reachable = 0;
                for (var a : anchors.values()) {
                    if (world.getBlockAt(a.x(), a.y(), a.z()).getType().isAir()) {
                        reachable++;
                    }
                }
                sender.sendMessage("  anchors stand in interiors: " + reachable + "/" + bound
                        + (reachable == bound ? "  PASS" : "  FAIL"));
                for (var a : anchors.values()) {
                    sender.sendMessage("    " + a.type() + " -> " + a.parcelId()
                            + " @(" + a.x() + "," + a.y() + "," + a.z() + ")");
                }
            }
            case "mask" -> {
                if (maskResult == null) {
                    sender.sendMessage("Mask was not compiled at startup - check console log.");
                    return;
                }
                sender.sendMessage("Mask total: " + maskResult.total() + " (expected " + maskResult.expectedTotal() + ")");
                sender.sendMessage("Bounds X " + maskResult.minChunkX() + ".." + maskResult.maxChunkX()
                        + " Z " + maskResult.minChunkZ() + ".." + maskResult.maxChunkZ());
                sender.sendMessage(maskResult.passes() ? "PASS" : "FAIL");
            }
            default -> sender.sendMessage("Unknown verify target: " + args[1]);
        }
    }

    private void handleGen(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /aincrad gen <plan|status|resume>");
            return;
        }
        if (ledger == null) {
            sender.sendMessage("Ledger not available.");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "plan" -> {
                try {
                    sender.sendMessage("Feature states (dry run, no changes made): " + ledger.countByState());
                    if (maskResult != null) {
                        sender.sendMessage("Mask chunks available for phases: " + maskResult.total());
                    }
                } catch (SQLException e) {
                    sender.sendMessage("Error: " + e.getMessage());
                }
                // Dry-run the parcel graph without writing a single block.
                if (plugin.getDistrictGenerationPhase() != null) {
                    var parcels = plugin.getDistrictGenerationPhase().plan();
                    java.util.Map<String, Integer> byDistrict = new java.util.TreeMap<>();
                    java.util.Map<String, Integer> byRole = new java.util.TreeMap<>();
                    int overlaps = 0;
                    for (int i = 0; i < parcels.size(); i++) {
                        var p = parcels.get(i);
                        byDistrict.merge(p.districtId(), 1, Integer::sum);
                        byRole.merge(p.role().name(), 1, Integer::sum);
                        for (int j = i + 1; j < parcels.size(); j++) {
                            if (p.overlaps(parcels.get(j))) {
                                overlaps++;
                            }
                        }
                    }
                    sender.sendMessage("Parcel ledger DRY RUN: " + parcels.size()
                            + " parcels (spec target 1100-1350)");
                    sender.sendMessage("  by district: " + byDistrict);
                    sender.sendMessage("  by role: " + byRole);
                    sender.sendMessage("  overlapping pairs: " + overlaps + (overlaps == 0 ? " (OK)" : " (BAD)"));
                }
            }
            case "status" -> {
                try {
                    sender.sendMessage("Feature states: " + ledger.countByState());
                } catch (SQLException e) {
                    sender.sendMessage("Error: " + e.getMessage());
                }
                var scheduler = plugin.getGenerationScheduler();
                if (scheduler != null) {
                    sender.sendMessage("Scheduler: pendingJobs=" + scheduler.getPendingJobCount()
                            + " editsApplied=" + scheduler.getTotalEditsApplied()
                            + " jobsCompleted=" + scheduler.getTotalJobsCompleted()
                            + " hasWork=" + scheduler.hasWork());
                }
            }
            case "resume", "start" -> {
                if (maskResult == null) {
                    sender.sendMessage("Mask is not available (not compiled at startup).");
                    return;
                }
                String phaseArg = args.length >= 3 ? args[2].toLowerCase() : "terrain";
                int considered = switch (phaseArg) {
                    case "terrain" -> plugin.getTerrainShapingPhase() != null
                            ? plugin.getTerrainShapingPhase().enqueueAllIncrementally(maskResult.chunks(), plugin, 200)
                            : -1;
                    case "roads" -> plugin.getRoadGenerationPhase() != null
                            ? plugin.getRoadGenerationPhase().enqueueAllIncrementally(plugin, 200)
                            : -1;
                    case "walls" -> plugin.getTownWallPhase() != null
                            ? plugin.getTownWallPhase().enqueueAllIncrementally(plugin, 200)
                            : -1;
                    case "plaza" -> plugin.getPlazaFoundationPhase() != null
                            ? plugin.getPlazaFoundationPhase().enqueueAllIncrementally(maskResult, plugin, 200)
                            : -1;
                    case "palace" -> plugin.getPalaceExteriorPhase() != null
                            ? plugin.getPalaceExteriorPhase().enqueueAllIncrementally(plugin, 200)
                            : -1;
                    case "avenue" -> plugin.getProcessionalAvenuePhase() != null
                            ? plugin.getProcessionalAvenuePhase().enqueueAllIncrementally(plugin, 200)
                            : -1;
                    case "clocktower" -> plugin.getClockTowerPhase() != null
                            ? plugin.getClockTowerPhase().enqueueAllIncrementally(plugin, 200)
                            : -1;
                    case "palaceinterior" -> plugin.getPalaceInteriorPhase() != null
                            ? plugin.getPalaceInteriorPhase().enqueueAllIncrementally(plugin, 200)
                            : -1;
                    case "districts" -> plugin.getDistrictGenerationPhase() != null
                            ? plugin.getDistrictGenerationPhase().enqueueAllIncrementally(plugin, 40)
                            : -1;
                    case "arcade" -> plugin.getPlazaArcadePhase() != null
                            ? plugin.getPlazaArcadePhase().enqueueAllIncrementally(plugin, 200)
                            : -1;
                    case "streets" -> {
                        var dp = plugin.getDistrictGenerationPhase();
                        var sp = plugin.getStreetPavingPhase();
                        yield (dp != null && sp != null)
                                ? sp.enqueueAllIncrementally(plugin, dp.plan(), dp.districtBounds(),
                                        dp.districtIds(), 200)
                                : -1;
                    }
                    default -> -2;
                };
                if (considered == -2) {
                    sender.sendMessage("Usage: /aincrad gen start <terrain|roads|walls|plaza>");
                } else if (considered == -1) {
                    sender.sendMessage("That phase is not available (world not ready at plugin enable time).");
                } else {
                    sender.sendMessage("Incrementally planning and enqueueing " + considered
                            + " " + phaseArg + " chunks (200/tick) - already-committed chunks are skipped as reached. "
                            + "Check /aincrad gen status for progress.");
                }
            }
            default -> sender.sendMessage("Unknown gen subcommand: " + args[1]);
        }
    }

    private void handleLayer(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This subcommand requires a player.");
            return;
        }
        Location loc = player.getLocation();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        int localFloor = layerController.localFloorForY(loc.getBlockY());
        List<int[]> positions = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            Location otherLoc = other.getLocation();
            positions.add(new int[]{otherLoc.getBlockX(), otherLoc.getBlockY(), otherLoc.getBlockZ()});
        }
        BitSet mask = layerController.activeFloorMaskForColumn(chunkX, chunkZ, positions);
        sender.sendMessage("Local floor at your position: " + localFloor);
        sender.sendMessage("Active floor mask for this column: " + mask);
    }

    private void handleConfig(CommandSender sender) {
        sender.sendMessage("world-seed=" + plugin.getConfig().getString("generation.world-seed"));
        sender.sendMessage("worker-count=" + plugin.getConfig().getInt("generation.worker-count"));
        sender.sendMessage("per-tick-block-budget=" + plugin.getConfig().getInt("generation.per-tick-block-budget"));
        sender.sendMessage("layer activation-radius=" + plugin.getConfig().getInt("layers.activation-radius"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return TOP_LEVEL;
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase()) {
                case "verify" -> VERIFY_SUB;
                case "gen" -> GEN_SUB;
                case "layer" -> LAYER_SUB;
                default -> List.of();
            };
        }
        if (args.length == 3 && "gen".equalsIgnoreCase(args[0])
                && ("start".equalsIgnoreCase(args[1]) || "resume".equalsIgnoreCase(args[1]))) {
            return GEN_PHASE_SUB;
        }
        return List.of();
    }
}
