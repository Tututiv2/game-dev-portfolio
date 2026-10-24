package org.aincrad.plugin;

import org.aincrad.plugin.command.AincradCommand;
import org.aincrad.plugin.district.DistrictGenerationPhase;
import org.aincrad.plugin.district.StreetPavingPhase;
import org.aincrad.plugin.editor.BlockSnapshot;
import org.aincrad.plugin.editor.Editor;
import org.aincrad.plugin.editor.EditorCommands;
import org.aincrad.plugin.editor.EditorListener;
import org.aincrad.plugin.generation.GenerationScheduler;
import org.aincrad.plugin.layer.LayerActivationController;
import org.aincrad.plugin.layer.LayerSpawnGuard;
import org.aincrad.plugin.ledger.GenerationLedger;
import org.aincrad.plugin.mask.ChunkMaskCompiler;
import org.aincrad.plugin.mask.MaskEnforcer;
import org.aincrad.plugin.mask.MaskResult;
import org.aincrad.plugin.npc.NpcRegistry;
import org.aincrad.plugin.safety.SafeZoneGuard;
import org.aincrad.plugin.service.ServiceAnchorRegistry;
import org.aincrad.plugin.avenue.ProcessionalAvenuePhase;
import org.aincrad.plugin.palace.PalaceExteriorPhase;
import org.aincrad.plugin.palace.PalaceInteriorPhase;
import org.aincrad.plugin.plaza.ClockTowerPhase;
import org.aincrad.plugin.plaza.PlazaArcadePhase;
import org.aincrad.plugin.plaza.PlazaFoundationPhase;
import org.aincrad.plugin.road.RoadGenerationPhase;
import org.aincrad.plugin.generation.PregenerationDriver;
import org.aincrad.plugin.terrain.FloorOneTerrain;
import org.aincrad.plugin.terrain.ShellRimPhase;
import org.aincrad.plugin.terrain.TerrainProfile;
import org.aincrad.plugin.terrain.TerrainShapingPhase;
import org.aincrad.plugin.wall.TownWallPhase;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class AincradPlugin extends JavaPlugin {

    private GenerationLedger ledger;
    private Editor editor;
    private LayerActivationController layerController;
    private MaskResult maskResult;
    private MaskEnforcer maskEnforcer;
    private boolean fullFloorAuthorized;

    /** 5,000-block radius shell, so a 10,000-block world border. */
    private static final double FLOOR_DIAMETER = 10000.0;
    private GenerationScheduler generationScheduler;
    private TerrainShapingPhase terrainShapingPhase;
    private RoadGenerationPhase roadGenerationPhase;
    private TownWallPhase townWallPhase;
    private PlazaFoundationPhase plazaFoundationPhase;
    private PalaceExteriorPhase palaceExteriorPhase;
    private ProcessionalAvenuePhase processionalAvenuePhase;
    private ClockTowerPhase clockTowerPhase;
    private PalaceInteriorPhase palaceInteriorPhase;
    private DistrictGenerationPhase districtGenerationPhase;
    private SafeZoneGuard safeZoneGuard;
    private final ServiceAnchorRegistry serviceAnchors = new ServiceAnchorRegistry();
    private final NpcRegistry npcRegistry = new NpcRegistry();
    private PlazaArcadePhase plazaArcadePhase;
    private StreetPavingPhase streetPavingPhase;
    private PregenerationDriver pregenerationDriver;
    private ShellRimPhase shellRimPhase;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            ChunkMaskCompiler compiler = ChunkMaskCompiler.fromClasspathResource(getClassLoader());
            maskResult = compiler.compile();
            fullFloorAuthorized = compiler.fullFloorAuthorized();
            getLogger().info("Authorized generation scope: "
                    + (fullFloorAuthorized ? "FULL FLOOR ONE (owner-approved)" : "Town review slice only"));
            if (maskResult.passes()) {
                getLogger().info("Chunk mask compiled: " + maskResult.total() + " chunks (matches layout JSON).");
            } else {
                getLogger().severe("Chunk mask MISMATCH: compiled " + maskResult.total()
                        + " chunks, layout JSON expects " + maskResult.expectedTotal()
                        + ". Generation must not proceed until this is fixed.");
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to compile chunk mask", e);
        }

        int activationRadius = getConfig().getInt("layers.activation-radius", 128);
        layerController = new LayerActivationController(activationRadius);
        getServer().getPluginManager().registerEvents(new LayerSpawnGuard(layerController), this);

        try {
            getDataFolder().mkdirs();
            Path dbPath = getDataFolder().toPath().resolve("generation-ledger.sqlite");
            ledger = new GenerationLedger(dbPath);
            ledger.putMeta("world_seed", getConfig().getString("generation.world-seed", "unset"));
            ledger.putMeta("generator_build", getDescription().getVersion());
            ledger.putMeta("layout_schema_version", "2");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to open generation ledger", e);
        }

        NamespacedKey aincradKey = NamespacedKey.fromString("aincrad:floors_01_10");
        if (maskResult != null && aincradKey != null) {
            safeZoneGuard = new SafeZoneGuard(aincradKey);
            getServer().getPluginManager().registerEvents(safeZoneGuard, this);
            maskEnforcer = new MaskEnforcer(maskResult, aincradKey, getLogger(),
                    fullFloorAuthorized ? MaskEnforcer.Scope.FULL_FLOOR
                                        : MaskEnforcer.Scope.REVIEW_SLICE);
            getServer().getPluginManager().registerEvents(maskEnforcer, this);
            enforceMaskBoundary(aincradKey, maskResult);

            World aincradWorld = Bukkit.getWorld(aincradKey);
            if (aincradWorld != null) {
                int perTickBudget = getConfig().getInt("generation.per-tick-block-budget", 4096);
                generationScheduler = new GenerationScheduler(aincradWorld, perTickBudget, getLogger());
                generationScheduler.runTaskTimer(this, 1L, 1L);

                long worldSeed = getConfig().getString("generation.world-seed", "aincrad-f01-tob-v1").hashCode();
                TerrainProfile terrainProfile = new TerrainProfile(worldSeed);
                if (ledger != null) {
                    terrainShapingPhase = new TerrainShapingPhase(ledger, terrainProfile, generationScheduler, getLogger());
                    roadGenerationPhase = new RoadGenerationPhase(ledger, terrainProfile, maskResult, generationScheduler, getLogger());
                    townWallPhase = new TownWallPhase(ledger, terrainProfile, maskResult, generationScheduler, getLogger());
                    plazaFoundationPhase = new PlazaFoundationPhase(ledger, terrainProfile, generationScheduler, getLogger());
                    palaceExteriorPhase = new PalaceExteriorPhase(ledger, terrainProfile, maskResult, generationScheduler, getLogger());
                    processionalAvenuePhase = new ProcessionalAvenuePhase(ledger, terrainProfile, maskResult, generationScheduler, getLogger());
                    clockTowerPhase = new ClockTowerPhase(ledger, maskResult, generationScheduler, getLogger());
                    plazaArcadePhase = new PlazaArcadePhase(ledger, maskResult, generationScheduler, getLogger());
                    palaceInteriorPhase = new PalaceInteriorPhase(ledger, maskResult, generationScheduler, getLogger());
                    districtGenerationPhase = new DistrictGenerationPhase(ledger, terrainProfile, maskResult, generationScheduler, getLogger(), worldSeed);
                    streetPavingPhase = new StreetPavingPhase(ledger, terrainProfile, maskResult, generationScheduler, getLogger(), worldSeed);
                    pregenerationDriver = new PregenerationDriver(aincradWorld, ledger,
                            new FloorOneTerrain(worldSeed), getLogger());
                    shellRimPhase = new ShellRimPhase(aincradWorld, ledger, getLogger());
                }
            }
        }

        npcRegistry.configure(
                getConfig().getInt("npc.initial-active-budget-town-wide", 160),
                getConfig().getInt("npc.initial-active-budget-per-10-chunks", 48),
                getConfig().getInt("layers.activation-radius", 128));

        AincradCommand executor = new AincradCommand(this, ledger, layerController, maskResult);
        PluginCommand command = getCommand("aincrad");
        if (command != null) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        enableEditor();

        getLogger().info("AincradPlugin enabled.");
    }

    /**
     * Wires up the built-in world editor.
     *
     * <p>The {@code //verb} shortcuts are registered in plugin.yml under names that literally begin
     * with a slash, because the server strips the first slash a player types: {@code //set} arrives as
     * the command {@code /set}. That is a real convention but a fragile-looking one, so every verb is
     * also reachable as {@code /aed <verb>} and this method reports how many shortcuts actually bound.
     * If the count ever comes back short, the fallback still works and the log says so, rather than
     * players discovering it by typing into silence.</p>
     */
    private void enableEditor() {
        editor = new Editor(this);
        String packingFault = BlockSnapshot.selfTest();
        if (packingFault != null) {
            // Undo would silently restore blocks to the wrong coordinates. Refuse to offer the editor
            // at all rather than hand out a destructive tool.
            getLogger().severe("World editor disabled: " + packingFault);
            editor = null;
            return;
        }

        EditorCommands editorCommands = new EditorCommands(editor);
        int bound = 0;
        List<String> missing = new ArrayList<>();
        for (String verb : EditorCommands.VERBS) {
            PluginCommand shortcut = getCommand("/" + verb);
            if (shortcut == null) {
                missing.add("//" + verb);
                continue;
            }
            shortcut.setExecutor(editorCommands);
            shortcut.setTabCompleter(editorCommands);
            bound++;
        }
        PluginCommand aed = getCommand("aed");
        if (aed != null) {
            aed.setExecutor(editorCommands);
            aed.setTabCompleter(editorCommands);
        }

        EditorListener listener = new EditorListener(editor);
        getServer().getPluginManager().registerEvents(listener, this);
        listener.startOutlineRenderer(this);

        getLogger().info("World editor ready: " + bound + " of " + EditorCommands.VERBS.size()
                + " // shortcuts bound" + (aed != null ? ", /aed fallback active" : ""));
        if (!missing.isEmpty()) {
            getLogger().warning("These // shortcuts did not bind and must be used as /aed instead: "
                    + String.join(" ", missing));
        }
    }

    public Editor getEditor() {
        return editor;
    }

    @Override
    public void onDisable() {
        if (editor != null) {
            int stopped = editor.queue().cancelAll();
            if (stopped > 0) {
                getLogger().info("Cancelled " + stopped + " editor operation(s) on shutdown.");
            }
        }
        if (generationScheduler != null) {
            generationScheduler.cancel();
        }
        if (ledger != null) {
            try {
                ledger.close();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Failed to close generation ledger cleanly", e);
            }
        }
        getLogger().info("AincradPlugin disabled.");
    }

    /**
     * Places the aincrad:floors_01_10 spawn point inside the authorized mask (so the server's
     * own automatic spawn-area preparation never touches out-of-mask chunks) and sets a
     * WorldBorder sized to the mask's bounding box (a real, vanilla-enforced hard boundary) as a
     * second, independent layer of protection against generating outside the 7,912-chunk mask.
     */
    private void enforceMaskBoundary(NamespacedKey aincradKey, MaskResult mask) {
        World world = Bukkit.getWorld(aincradKey);
        if (world == null) {
            getLogger().warning("aincrad:floors_01_10 world not yet available at plugin enable time; "
                    + "mask boundary (spawn point + world border) was NOT set this boot.");
            return;
        }

        // Documented Town of Beginnings arrival point - inside the mask regardless of whether
        // the Town itself has been generated yet.
        Location arrival = new Location(world, 0, 323, 4416);
        world.setSpawnLocation(arrival);

        if (fullFloorAuthorized) {
            // Full floor is authorized, so the border is the floor itself: centred on the floor's
            // origin and sized to the 5,000-block radius shell. The old review-slice border was
            // 2,176 blocks - about 22% of the floor - and once the owner was exploring rather than
            // reviewing a slice, it read as an arbitrary invisible wall across the world.
            world.getWorldBorder().setCenter(0.0, 0.0);
            world.getWorldBorder().setSize(FLOOR_DIAMETER);
            getLogger().info("World border set to the full floor: centre (0,0), size "
                    + FLOOR_DIAMETER + " (radius " + (FLOOR_DIAMETER / 2) + ")");
        } else {
            int minBlockX = mask.minChunkX() * 16;
            int maxBlockX = (mask.maxChunkX() + 1) * 16 - 1;
            int minBlockZ = mask.minChunkZ() * 16;
            int maxBlockZ = (mask.maxChunkZ() + 1) * 16 - 1;
            double centerX = (minBlockX + maxBlockX) / 2.0;
            double centerZ = (minBlockZ + maxBlockZ) / 2.0;
            double size = Math.max(maxBlockX - minBlockX + 1, maxBlockZ - minBlockZ + 1) + 128;
            world.getWorldBorder().setCenter(centerX, centerZ);
            world.getWorldBorder().setSize(size);
            getLogger().info("Review-slice border: centre (" + centerX + "," + centerZ + ") size " + size);
        }
    }

    public GenerationLedger getLedger() {
        return ledger;
    }

    public LayerActivationController getLayerController() {
        return layerController;
    }

    public MaskResult getMaskResult() {
        return maskResult;
    }

    public MaskEnforcer getMaskEnforcer() {
        return maskEnforcer;
    }

    public GenerationScheduler getGenerationScheduler() {
        return generationScheduler;
    }

    public TerrainShapingPhase getTerrainShapingPhase() {
        return terrainShapingPhase;
    }

    public RoadGenerationPhase getRoadGenerationPhase() {
        return roadGenerationPhase;
    }

    public TownWallPhase getTownWallPhase() {
        return townWallPhase;
    }

    public PlazaFoundationPhase getPlazaFoundationPhase() {
        return plazaFoundationPhase;
    }

    public PalaceExteriorPhase getPalaceExteriorPhase() {
        return palaceExteriorPhase;
    }

    public ProcessionalAvenuePhase getProcessionalAvenuePhase() {
        return processionalAvenuePhase;
    }

    public ClockTowerPhase getClockTowerPhase() {
        return clockTowerPhase;
    }

    public PalaceInteriorPhase getPalaceInteriorPhase() {
        return palaceInteriorPhase;
    }

    public DistrictGenerationPhase getDistrictGenerationPhase() {
        return districtGenerationPhase;
    }

    public SafeZoneGuard getSafeZoneGuard() {
        return safeZoneGuard;
    }

    public ServiceAnchorRegistry getServiceAnchors() {
        return serviceAnchors;
    }

    public NpcRegistry getNpcRegistry() {
        return npcRegistry;
    }

    public PlazaArcadePhase getPlazaArcadePhase() {
        return plazaArcadePhase;
    }

    public StreetPavingPhase getStreetPavingPhase() {
        return streetPavingPhase;
    }

    public PregenerationDriver getPregenerationDriver() {
        return pregenerationDriver;
    }

    public ShellRimPhase getShellRimPhase() {
        return shellRimPhase;
    }
}
