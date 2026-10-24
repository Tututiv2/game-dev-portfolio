package com.thestill;

import com.thestill.command.PlayerCommands;
import com.thestill.command.StillCommand;
import com.thestill.config.DialRegistry;
import com.thestill.config.Dials;
import com.thestill.core.ServiceStack;
import com.thestill.economy.Economy;
import com.thestill.economy.PriceTable;
import com.thestill.economy.SellService;
import com.thestill.item.ItemTags;
import com.thestill.mine.DepthSetup;
import com.thestill.mine.MineListener;
import com.thestill.mine.MineService;
import com.thestill.mine.SessionStats;
import com.thestill.player.Onboarding;
import com.thestill.player.PlayerListener;
import com.thestill.player.Profiles;
import com.thestill.probe.CrashProbe;
import com.thestill.rank.RankLadder;
import com.thestill.rank.RankService;
import com.thestill.region.RegionService;
import com.thestill.store.Database;
import com.thestill.ui.MenuService;
import com.thestill.ui.NametagService;
import com.thestill.ui.sidebar.Sidebar;
import com.thestill.ui.sidebar.StillLines;
import com.thestill.descent.DescentService;
import com.thestill.world.StillWorld;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.List;
import java.util.logging.Level;

/**
 * The composition root.
 *
 * <p>Six named phases, each a method, in the one order that works: configuration
 * before storage, storage before services, services before anything that talks
 * to a player. Shadow Prison did the same job in a single 2,934-line
 * {@code onEnable} where construction, setter injection, listener registration,
 * command binding and task scheduling were interleaved, and the ordering rules
 * survived only as comments.
 *
 * <p>Shutdown is the reverse, and every step is isolated — see
 * {@link ServiceStack}.
 */
public final class TheStillPlugin extends JavaPlugin {

    private ServiceStack services;
    private DialRegistry dialRegistry;
    private Dials dials;
    private Database database;
    private RegionService regions;
    private Profiles profiles;
    private CrashProbe probe;
    private ItemTags itemTags;
    private com.thestill.item.ItemRegistry itemRegistry;

    private PriceTable prices;
    private Economy economy;
    private SellService selling;
    private RankLadder rankLadder;
    private RankService ranks;
    private DescentService descent;
    private com.thestill.descent.WinchService winch;
    private MineService mines;
    private DepthSetup depthSetup;
    private Sidebar sidebar;
    private com.thestill.world.StillWorld stillWorld;
    private NametagService nametags;
    private SessionStats sessions;
    private Onboarding onboarding;
    private com.thestill.core.ManagedEntities managedEntities;
    private com.thestill.role.RoleConfig roleConfig;
    private com.thestill.role.RoleService roles;
    private com.thestill.config.Dial<String> storeUrl;
    private com.thestill.npc.RigStaff rigStaff;
    private com.thestill.cosmetic.CosmeticDials cosmeticDials;
    private com.thestill.cosmetic.ThemeService themeService;
    private com.thestill.cosmetic.NickPrompt nickPrompt;
    private com.thestill.cosmetic.NickService nickService;
    private com.thestill.fog.FogService fog;
    private com.thestill.admin.AdminSetContext adminSet;
    private com.thestill.admin.AdminSetCommand adminSetCommand;
    /** A field rather than a local, because /config's item vault records grants to it too. */
    private com.thestill.admin.AdminAudit adminAudit;

    private BukkitTask flushTask;

    @Override
    public void onEnable() {
        long startedAt = System.currentTimeMillis();
        services = new ServiceStack(getLogger());
        try {
            loadConfiguration();
            openStore();
            startServices();
            registerCommands();
            registerListeners();
            scheduleTasks();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "startup failed — disabling rather than "
                    + "running half-wired, which is how corrupt data gets written", t);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Now that every service has registered its own dials, write the file and
        // report the real total.
        dialRegistry.save();
        getLogger().info(dialRegistry.size() + " dial(s) registered across "
                + dialRegistry.byCategory().size() + " categories");
        getLogger().info("The Still enabled in " + (System.currentTimeMillis() - startedAt)
                + "ms — noise calls, heat keeps.");
    }

    @Override
    public void onDisable() {
        // Cancel scheduled work first so nothing queues new writes while the
        // store is draining.
        try {
            if (flushTask != null) {
                flushTask.cancel();
                flushTask = null;
            }
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "could not cancel the flush task (continuing)", t);
        }

        if (services != null) {
            services.stopAll();
        }

        try {
            if (dialRegistry != null) {
                dialRegistry.save();
            }
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "could not save dials.yml (continuing)", t);
        }

        getLogger().info("The Still disabled.");
    }

    // ---- phase 1: configuration ----------------------------------------

    private void loadConfiguration() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("could not create " + dataFolder);
        }
        dialRegistry = new DialRegistry(getLogger(), new File(dataFolder, "dials.yml"));
        // Overrides are read before any dial exists. The registry holds them
        // pending and applies each one the moment its dial registers, so a
        // service can declare its own dials in its own constructor and still
        // pick up the admin's value.
        dialRegistry.load();
        dials = new Dials(dialRegistry);
        // Deliberately no count logged here: services register their own dials as
        // they are constructed, several phases later, so a number printed now
        // would always be wrong. It is logged at the end of enable instead.
    }

    // ---- phase 2: storage ----------------------------------------------

    private void openStore() {
        database = services.start(new Database(getLogger(), dials,
                new File(getDataFolder(), "thestill.db")));
    }

    // ---- phase 3: services ---------------------------------------------

    /**
     * Wiring order is the dependency order, top to bottom, and nothing reaches
     * backwards. Read it as a list of what needs what.
     */
    private void startServices() {
        regions = services.start(new RegionService(getLogger(), database));
        profiles = services.start(new Profiles(getLogger(), database));
        probe = services.start(new CrashProbe(getLogger(), this, database, dials));

        itemTags = new ItemTags(this);
        // THE CATALOGUE. Seeded here, before anything that hands out an item exists,
        // because it is the only place identity can be applied — ItemTags.stamp is
        // package-private. Everything downstream asks it for a stack rather than
        // building one, so /config's item vault is the plugin's real contents rather
        // than a list somebody has to remember to update.
        itemRegistry = com.thestill.item.StillItems.catalogue(itemTags);
        sessions = new SessionStats();

        prices = services.start(new PriceTable(getLogger(),
                new File(getDataFolder(), "prices.yml"), itemTags));
        economy = new Economy(getLogger(), profiles);
        selling = new SellService(dialRegistry, prices, economy);

        roleConfig = services.start(new com.thestill.role.RoleConfig(getLogger(),
                new File(getDataFolder(), "roles.yml")));
        roles = services.start(new com.thestill.role.RoleService(getLogger(), this, roleConfig));

        rankLadder = services.start(new RankLadder(getLogger(),
                new File(getDataFolder(), "ranks.yml")));
        ranks = new RankService(getLogger(), rankLadder, economy, profiles, dialRegistry);

        descent = services.start(new DescentService(this, regions, ranks, profiles));
        mines = services.start(new MineService(getLogger(), this, regions, dialRegistry));
        // Attaches ore tables to already-persisted depths, so a restart does not
        // leave 26 mines that never refill.
        depthSetup = services.start(new DepthSetup(getLogger(), regions, mines, descent));

        stillWorld = services.start(new StillWorld(getLogger(), this, dialRegistry));
        if (stillWorld.world() == null) {
            getLogger().severe("dimension thestill:depths is not loaded — the datapack at "
                    + "server/world/datapacks/thestill-depths is missing or disabled. "
                    + "Run /datapack enable, then restart.");
        }

        // The generation guard comes FIRST: every service below it that spawns an
        // entity stamps through it, and it is also a listener, so it has to exist
        // before anything it protects.
        managedEntities = services.start(new com.thestill.core.ManagedEntities(getLogger(), this));
        services.start(new com.thestill.ui.HologramService(getLogger(), this, stillWorld,
                managedEntities, dialRegistry));
        rigStaff = services.start(new com.thestill.npc.RigStaff(getLogger(), this, stillWorld,
                managedEntities, dialRegistry));
        sidebar = services.start(new Sidebar(getLogger(), this, dialRegistry));
        storeUrl = dialRegistry.stringDial("ui.store-url", "store.thestill.gg")
                .category("Interface").label("Store address")
                .describe("Where /store sends people, and what the sidebar shows at the "
                        + "bottom. Blank hides the sidebar line.")
                .register();
        // The nametag service comes BEFORE the sidebar's lines, because the board's
        // identity row is composed by it. It used to be the other way round and the board
        // built that line itself, which is why a nickname never appeared on it.
        nametags = services.start(new NametagService(getLogger(), this, profiles, ranks, roles));
        new StillLines(profiles, ranks, regions, mines, sessions, selling, dialRegistry, roles,
                storeUrl, nametags).registerAll(sidebar);

        // Cosmetics come after the nametag service and hand themselves back to it: the
        // theme phase is advanced by that service's existing ticker rather than by one of
        // its own, so every animated tag and every animated name on the server moves
        // together. See ThemeService.
        cosmeticDials = new com.thestill.cosmetic.CosmeticDials(dialRegistry);
        themeService = services.start(
                new com.thestill.cosmetic.ThemeService(cosmeticDials, roles));
        nickPrompt = services.start(
                new com.thestill.cosmetic.NickPrompt(this, cosmeticDials));
        nickService = new com.thestill.cosmetic.NickService(profiles, themeService,
                cosmeticDials, nametags);
        nametags.withThemes(themeService);

        // The rig deck is home. Without this, /spawn and the onboarding teleport
        // both fall back to the first loaded world, which is the leftover flat
        // one — a new player would land somewhere that is not the game.
        if (stillWorld.world() != null) {
            var rig = com.thestill.mine.DepthPlan.rigSpawn(stillWorld.world());
            descent.setHub(rig);
            stillWorld.world().setSpawnLocation(rig);
        }

        winch = services.start(new com.thestill.descent.WinchService(this, descent, dialRegistry));

        // The fog lamp starts LAST of the world-facing services, and after StillWorld, because
        // it asks the biome registry a question the datapack has to have answered first.
        fog = services.start(new com.thestill.fog.FogService(getLogger(), this, dialRegistry,
                itemTags, itemRegistry));

        onboarding = new Onboarding(itemRegistry, profiles, descent);

        // /adminset, last because it reaches every service above it. The audit trail is
        // constructed before the actions that write to it, so there is no window in
        // which an edit could happen unrecorded.
        var offlineProfiles = new com.thestill.admin.OfflineProfiles(getLogger(), this,
                database, profiles);
        adminAudit = new com.thestill.admin.AdminAudit(getLogger(), database);
        adminSet = new com.thestill.admin.AdminSetContext(
                new com.thestill.admin.AdminActions(offlineProfiles, profiles, economy, ranks,
                        rankLadder, roles, nametags, adminAudit),
                nickPrompt, adminAudit, new com.thestill.admin.ConfirmGate());
        adminSetCommand = new com.thestill.admin.AdminSetCommand(this, offlineProfiles, adminSet);
    }

    // ---- phase 4: commands ---------------------------------------------

    private void registerCommands() {
        StillCommand still = new StillCommand(this, dialRegistry, regions, profiles, database,
                probe, depthSetup, mines, prices, ranks, stillWorld, onboarding, roles, nametags);
        PlayerCommands player = new PlayerCommands(this, profiles, selling, ranks, descent, nametags);
        // Late-wired, and the reason is the gate rule: /store reaches the same menu a
        // shop NPC will, so the entitlement check belongs on the destination. Handing
        // the command the services rather than a second copy of the logic keeps it
        // that way.
        player.withStore(roles, storeUrl);
        // /nick and /chatcolor get the services, never their own copy of the perk rules —
        // the source shipped a command whose check disagreed with its own menu's.
        player.withCosmetics(nickService, themeService, nickPrompt);
        // /still cosmetic <who> — the instrument. See StillCommand#cosmeticBranch.
        still.withCosmetics(themeService);
        // Late-wired for the same reason as /store: the command hands out an item the service
        // owns, rather than keeping its own idea of what a lamp is.
        still.withFog(fog);
        // /config's item vault. The command gets the catalogue and the audit trail, not
        // a hand-written list of what exists — a second list is exactly the drift the
        // registry was written to make impossible.
        still.withItems(itemRegistry, adminAudit);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(still.build(), "The Still — administration", List.of("st"));
            commands.register(still.buildConfigCommand(), "Tunable dials", List.of());
            commands.register(still.buildFogCommand(), "Toggle the fog where you stand", List.of());
            commands.register(adminSetCommand.build(),
                    "Edit one player — money, ladder, roles, state", List.of());
            player.buildAll().forEach(node -> commands.register(node, null, List.of()));
        });
    }

    // ---- phase 5: listeners --------------------------------------------

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new PlayerListener(getLogger(), profiles,
                regions, dials, onboarding, sidebar, sessions, nametags), this);
        Bukkit.getPluginManager().registerEvents(new MenuService(getLogger()), this);
        Bukkit.getPluginManager().registerEvents(new MineListener(dialRegistry, regions,
                profiles, ranks, mines, sessions), this);
        Bukkit.getPluginManager().registerEvents(
                new com.thestill.descent.DescentListener(dialRegistry, regions), this);
        // The winch is its own listener: the way out of the Depths is a block you
        // right-click, not a command.
        Bukkit.getPluginManager().registerEvents(winch, this);
        // The lamp listens for quit/death/world-change so a lit player is never left lit,
        // and for block placement so a soul lantern cannot be lost into a wall.
        Bukkit.getPluginManager().registerEvents(fog, this);
        Bukkit.getPluginManager().registerEvents(managedEntities, this);
        Bukkit.getPluginManager().registerEvents(new com.thestill.npc.StaffListener(
                this, rigStaff, profiles, selling), this);
        // The prompt claims a chat line at LOWEST; the formatter runs at NORMAL and
        // ignores what the prompt cancelled. Registering them in this order is not what
        // makes that work — the priorities are — but reading them together is the point.
        Bukkit.getPluginManager().registerEvents(nickPrompt, this);
        Bukkit.getPluginManager().registerEvents(new com.thestill.cosmetic.ChatListener(
                profiles, themeService, nametags, cosmeticDials), this);
    }

    // ---- phase 6: scheduled work ---------------------------------------

    private void scheduleTasks() {
        scheduleFlush();
        // Changing the interval live re-arms the task rather than waiting for a
        // restart, which is the whole point of having the dial.
        dials.storeFlushSeconds.onChange(seconds -> Bukkit.getScheduler().runTask(this,
                () -> scheduleFlush()));
    }

    private void scheduleFlush() {
        if (flushTask != null) {
            flushTask.cancel();
        }
        long period = Math.max(1L, dials.storeFlushSeconds.asInt()) * 20L;
        flushTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                profiles.flushDirty();
                regions.flushDirty();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "periodic flush failed", e);
            }
        }, period, period);
    }

    // ---- accessors for later phases ------------------------------------

    public Dials dials() {
        return dials;
    }

    public DialRegistry dialRegistry() {
        return dialRegistry;
    }

    public Database database() {
        return database;
    }

    public RegionService regions() {
        return regions;
    }

    public Profiles profiles() {
        return profiles;
    }

    public ItemTags itemTags() {
        return itemTags;
    }
}
