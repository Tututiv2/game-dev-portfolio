package com.shadowprison;

import com.shadowprison.core.ServiceStack;
import com.shadowprison.dial.Dial;
import com.shadowprison.dial.DialRegistry;
import com.shadowprison.grant.Claims;
import com.shadowprison.grant.Grants;
import com.shadowprison.item.ItemDef;
import com.shadowprison.item.Items;
import com.shadowprison.item.Rarity;
import com.shadowprison.menu.MenuService;
import com.shadowprison.menu.Prompts;
import com.shadowprison.money.Wallets;
import com.shadowprison.profile.Profiles;
import com.shadowprison.stat.Stat;
import com.shadowprison.stat.Stats;
import com.shadowprison.store.Database;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

/**
 * The composition root. Named phases in the one order that works — store before services,
 * services before anything that talks to a player — instead of SP1's single 2,934-line onEnable.
 * Shutdown is the exact reverse through {@link ServiceStack}, every step isolated.
 */
public final class ShadowPrison extends JavaPlugin {

    private ServiceStack services;
    private Database db;
    private DialRegistry dials;
    private Profiles profiles;
    private Wallets wallets;
    private Items items;
    private Grants grants;
    private Stats stats;
    private Prompts prompts;
    private MenuService menus;

    /** Phase 0 demo dials — they prove the dial -> /config -> behaviour path end to end. */
    Dial startingCoins;
    Dial joinMessage;

    @Override
    public void onEnable() {
        long startedAt = System.currentTimeMillis();
        services = new ServiceStack(getLogger());
        try {
            openStore();
            buildServices();
            registerContent();
            startServices();
            registerCommands();
            registerListeners();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "startup failed — disabling rather than running half-wired", t);
            services.stopAll();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Shadow Prison 2 enabled in " + (System.currentTimeMillis() - startedAt) + "ms — "
                + dials.size() + " dials, " + items.all().size() + " items");
    }

    @Override
    public void onDisable() {
        if (services != null) {
            services.stopAll();   // reverse order; a throwing stop never skips the ones after it
        }
    }

    // ---- phases ----------------------------------------------------------------------------

    private void openStore() {
        db = services.start(new Database(getLogger(), new File(getDataFolder(), "shadowprison.db")));
    }

    private void buildServices() {
        dials = new DialRegistry(getLogger(), db);
        profiles = new Profiles(getLogger(), db);
        wallets = new Wallets(db);
        items = new Items(this, db);
        grants = new Grants(new Claims(db));
        stats = new Stats();
        prompts = new Prompts(this);
        menus = new MenuService(getLogger());
    }

    private void registerContent() {
        // Dials are registered BEFORE the registry starts, so their stored values load over defaults.
        startingCoins = dials.money("economy.starting-coins", "Economy", "Starting Coins",
                "Coins a brand-new player receives on their very first join.",
                10_000L, 0L, 1_000_000_000L, 10_000L, Material.GOLD_NUGGET);
        joinMessage = dials.toggle("core.join-message", "Core", "Welcome Message",
                "Greet players with the server welcome line when they join.", true, Material.OAK_SIGN);

        items.register(ItemDef.builder("starter_pickaxe", Material.WOODEN_PICKAXE)
                .name("Starter Pickaxe").rarity(Rarity.COMMON).category("PICKAXE")
                .stat(Stat.MINING_SPEED, 10).line("Everyone starts somewhere.").build());
        items.register(ItemDef.builder("void_shard", Material.AMETHYST_SHARD)
                .name("Void Shard").rarity(Rarity.RARE).category("MATERIAL")
                .line("Hums when you hold it up to the dark.").build());
        items.register(ItemDef.builder("founders_relic", Material.NETHER_STAR)
                .name("Founder's Relic").rarity(Rarity.LEGENDARY).category("RELIC").unique()
                .stat(Stat.MINING_FORTUNE, 25).stat(Stat.SKILL_WISDOM, 5)
                .line("Given to those who were here first.").build());

        // Held item + worn armor feed the one stat table.
        stats.register((player, map) -> {
            map.addAll(items.statsOf(player.getInventory().getItemInMainHand()));
            for (ItemStack armor : player.getInventory().getArmorContents()) {
                map.addAll(items.statsOf(armor));
            }
        });
    }

    private void startServices() {
        services.start(dials);
        services.start(profiles);
        services.start(menus);
    }

    private void registerCommands() {
        Commands cmd = new Commands(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> cmd.register(event.registrar()));
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(profiles, this);
        pm.registerEvents(menus, this);
        pm.registerEvents(prompts, this);
        pm.registerEvents(new JoinFlow(this), this);
    }

    // ---- accessors for the command/menu layer ------------------------------------------------

    Database db() { return db; }
    DialRegistry dials() { return dials; }
    Profiles profiles() { return profiles; }
    Wallets wallets() { return wallets; }
    Items items() { return items; }
    Grants grants() { return grants; }
    Stats stats() { return stats; }
    Prompts prompts() { return prompts; }

    boolean isAdmin(Player player) {
        return player.hasPermission("shadow.admin");
    }
}
