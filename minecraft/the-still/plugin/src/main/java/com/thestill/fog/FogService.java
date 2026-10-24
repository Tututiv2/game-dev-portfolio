package com.thestill.fog;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.core.Service;
import com.thestill.item.ItemTags;
import com.thestill.mine.DepthPlan;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * THE FOG LAMP — the half that decides who is holding one.
 *
 * <p>The Still is drowned in white fog that goes fully opaque at 22 blocks, and the lamp is
 * the thing you point into it. It is two mechanisms in two files, and they are separate
 * because the game splits them:
 *
 * <ol>
 *   <li><b>The distance</b> is a biome attribute ({@code minecraft:visual/fog_end_distance}),
 *       which only the server can change — so it lives here, and it is switched by sending
 *       one player a different biome.</li>
 *   <li><b>The cone</b> — thinning the fog toward the middle of the screen rather than all
 *       round — is a per-pixel decision, which only the client can make. It lives in
 *       {@code resource-pack/assets/minecraft/shaders/core/block.fsh}, and it recognises the
 *       lamp by the distinctive pair of fog distances the lamplight biome carries.</li>
 * </ol>
 *
 * <p><b>Nothing placed in the world can do this.</b> Minecraft's fog is distance-based, not
 * light-based: a torch lights a surface, and that surface is still fogged out. That is why
 * the obvious version of this feature — a bright block — does not exist and cannot be made
 * to work, and it is worth writing down because it is the first thing anybody tries.
 *
 * <h2>Why the lamp is a cone and heat is not</h2>
 *
 * <p>CLAUDE.md's Phase 4 note says <i>"seeing further is a reason to want heat"</i>: run warm
 * and you can see, stay cold and you are blind, and the Wanting come for whatever is warm.
 * The lamp answers the same question — how do I see in this — so the two are one design
 * decision away from collapsing into a single upgrade nobody has to think about.
 *
 * <p>They are kept apart by SHAPE, not by numbers. <b>Heat is ambient</b>: it lifts the fog
 * all round you, for free, and the price is paid in who arrives. <b>The lamp is directional
 * and it occupies your main hand</b>: it sees a long way down a narrow cone, and the price is
 * that you are not holding a pickaxe and you can only look at one thing at a time. Warm and
 * blind-behind versus cold and seeing one direction are different situations, and a player
 * who has both still has to choose between them. If a later session makes the lamp all-round,
 * or makes heat directional, that choice is gone — so do not.
 *
 * <h2>The failure mode this is built around</h2>
 *
 * <p>A player left in the lamplight biome forever is the one thing that must be impossible,
 * because it is invisible: they simply see further than everyone else, permanently, and
 * nobody can tell why. {@link PacketBiomeChannel} makes it <em>structurally</em> impossible
 * rather than carefully avoided — it never changes the world at all, so a crash, a kick, a
 * chunk reload or a relog all restore the real weather with no code involved. Everything in
 * this class below (quit, death, world change, disable) is belt and braces on top of that,
 * and it is what {@link WorldBiomeChannel} genuinely depends on.
 */
public final class FogService implements Service, Listener {

    private final Logger log;
    private final Plugin plugin;
    private final ItemTags tags;
    /** The catalogue, so this service reads what a lamp is rather than declaring it. */
    private final com.thestill.item.ItemRegistry items;

    private final Dial<Boolean> enabled;
    private final Dial<Boolean> perPlayer;
    private final Dial<Double> distance;
    private final Dial<Integer> radius;
    private final Dial<Integer> refreshTicks;
    private final Dial<String> biomeKey;

    /** Who is lit, and exactly which chunks were sent to them, so douse can be exact. */
    private final Map<UUID, long[]> lit = new HashMap<>();

    private BiomeChannel channel;
    private BukkitTask task;

    public FogService(Logger log, Plugin plugin, DialRegistry dials, ItemTags tags,
                      com.thestill.item.ItemRegistry items) {
        this.log = log;
        this.plugin = plugin;
        this.tags = tags;
        this.items = items;

        this.enabled = dials.boolDial("fog.lamp-enabled", false)
                .category("Fog").label("Fog lamp works")
                .describe("The handheld lamp that opens the fog where you look. Ships OFF: half "
                        + "of it is a core shader in the resource pack, and a player without the "
                        + "pack gets a lamp that widens the weather a little and never makes a "
                        + "cone — which reads as broken rather than as optional. Turn it on when "
                        + "the pack is being served.")
                .register();

        this.perPlayer = dials.boolDial("fog.lamp-per-player", true)
                .category("Fog").label("Lamp is personal")
                .describe("On: the lamp is sent to one connection and nobody else sees it. Off: "
                        + "forces the world-repaint fallback, which changes the biome for real and "
                        + "therefore lights up anyone standing next to the holder. Only turn this "
                        + "off to prove whether a problem is in the packet path.")
                .register();

        this.distance = dials.doubleDial("fog.lamp-distance", 90.0)
                .category("Fog").label("Lamp fog distance")
                .describe("How far a lamp lets you see ALL ROUND, in blocks, against the weather's "
                        + "22. The cone down the middle is a multiple of this and lives in the "
                        + "shader. READ-ONLY IN PRACTICE: the number that matters is in the "
                        + "datapack biome file, which the server loads at world load, so changing "
                        + "this dial alone changes nothing. It is here so the two can be checked "
                        + "against each other at startup — the same drift that made dials.yml and "
                        + "ranks.yml outlive their own code.")
                .range(24, 320).step(2)
                .register();

        this.radius = dials.intDial("fog.lamp-radius", 3)
                .category("Fog").label("Lamp chunk radius")
                .describe("How many chunks around the holder are put into the lamplight biome. "
                        + "Fog is sampled at the camera, so this only has to cover where you are "
                        + "standing — but the ring has to be re-sent as you walk, and every chunk "
                        + "that drops out of it has to be put back, so bigger is not better.")
                .range(1, 8).step(1)
                .register();

        this.refreshTicks = dials.intDial("fog.lamp-refresh-ticks", 20)
                .category("Fog").label("Lamp refresh")
                .describe("How often the lamp is re-evaluated. This is also how long it takes for "
                        + "the fog to close after you put the lamp away, so it is a feel setting "
                        + "as much as a cost one — instant would be twitchy, a second reads as the "
                        + "weather rolling back in.")
                .range(5, 200).step(5)
                .register();

        this.biomeKey = dials.stringDial("fog.lamp-biome", "thestill:lamplight")
                .category("Fog").label("Lamplight biome")
                .describe("The datapack biome the lamp puts you in. It must carry fog start 41 and "
                        + "end 90 TOGETHER — that pair is the sentinel the shader's cone looks for, "
                        + "and nothing else in the pack may use it. See block.fsh.")
                .register();
    }

    @Override
    public String serviceName() {
        return "FogService";
    }

    @Override
    public void start() {
        channel = openChannel();
        log.info("fog lamp: " + (channel == null ? "unavailable" : channel.describe()));
        checkDatapackAgreesWithTheDial();

        // Re-armed rather than read once, so changing the interval in /config takes effect
        // now instead of at the next restart. Same pattern as the store flush task.
        schedule();
        refreshTicks.onChange(ignored -> Bukkit.getScheduler().runTask(plugin, this::schedule));
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Everyone off, on the way out. Redundant for the packet channel and load-bearing
        // for the world one, and the cost of getting it wrong is a permanently clear patch
        // of world that nobody can account for.
        for (Map.Entry<UUID, long[]> entry : new ArrayList<>(lit.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && channel != null) {
                channel.douse(player, entry.getValue());
            }
        }
        lit.clear();
    }

    private void schedule() {
        if (task != null) {
            task.cancel();
        }
        long period = Math.max(1L, refreshTicks.asInt());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    // ---- the loop ------------------------------------------------------

    private void tick() {
        if (channel == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                update(player);
            } catch (Throwable t) {
                log.log(Level.WARNING, "fog lamp: update failed for " + player.getName(), t);
            }
        }
    }

    private void update(Player player) {
        long[] want = shouldBeLit(player) ? ring(player) : new long[0];
        long[] have = lit.getOrDefault(player.getUniqueId(), new long[0]);

        if (want.length == 0) {
            if (have.length > 0) {
                channel.douse(player, have);
                lit.remove(player.getUniqueId());
            }
            return;
        }

        // Put back only what the player has walked out of. Dousing the whole ring and
        // re-lighting it would strobe the weather every time somebody crosses a chunk line.
        long[] gone = ChunkRing.shed(have, want);
        if (gone.length > 0) {
            channel.douse(player, gone);
        }
        if (channel.light(player, want)) {
            lit.put(player.getUniqueId(), want);
        } else {
            // Could not light. Forget rather than remember a state we failed to reach —
            // a remembered lit-set we never sent is a douse aimed at chunks that were never
            // changed, which on the world channel repaints somebody else's weather.
            lit.remove(player.getUniqueId());
        }
    }

    /**
     * Held in the main hand, in the Depths, below the fog line.
     *
     * <p>The last clause is not an optimisation. Above the fog the rig already stands in
     * clear air out to 110 blocks, and the lamplight biome only reaches 90 — so lighting a
     * lamp on the deck would make you see LESS, which is the sort of bug that gets reported
     * as "the lamp is backwards" and takes an hour to find. The lamp is for the fog.
     */
    private boolean shouldBeLit(Player player) {
        if (!enabled.asBoolean()) {
            return false;
        }
        if (!FogLamp.is(tags, player.getInventory().getItemInMainHand())) {
            return false;
        }
        World world = player.getWorld();
        NamespacedKey key = world.getKey();
        if (!"thestill".equals(key.getNamespace())) {
            return false;
        }
        return player.getLocation().getBlockY() < DepthPlan.FOG_Y;
    }

    private long[] ring(Player player) {
        return ChunkRing.around(player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4, radius.asInt());
    }

    // ---- the ways a lamp stops -----------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        // The lamp is dropped or kept by the death rules, and either way the next tick
        // works it out from the inventory. Dousing here is about the respawn: you come back
        // at the rig, which is a different biome, and the ring you were lit in is somewhere
        // three thousand blocks down.
        forget(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        forget(event.getPlayer());
    }

    private void forget(Player player) {
        long[] have = lit.remove(player.getUniqueId());
        if (have != null && have.length > 0 && channel != null) {
            channel.douse(player, have);
        }
    }

    /**
     * A tagged lamp may not be placed as a block.
     *
     * <p>It is a soul lantern, so the first instinct in a dark tunnel is to stick it on the
     * wall — and a placed lamp is a lamp that has stopped being an item, with no way to tell
     * which of the lanterns in the shaft was the expensive one. Cancelling the placement
     * costs one event and removes the entire class of "I lost my lamp" ticket.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (FogLamp.is(tags, event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    // ---- wiring --------------------------------------------------------

    private BiomeChannel openChannel() {
        if (perPlayer.get()) {
            try {
                return new PacketBiomeChannel(log, biomeKey.get());
            } catch (Throwable t) {
                log.log(Level.WARNING, "fog lamp: the per-player path is unavailable — "
                        + "falling back to repainting the world, which everyone standing "
                        + "near the holder will also see", t);
            }
        }
        org.bukkit.block.Biome lamplight = biome(biomeKey.get());
        org.bukkit.block.Biome depths = biome("thestill:depths");
        if (lamplight == null || depths == null) {
            log.warning("fog lamp: " + biomeKey.get() + " is not in the biome registry. The "
                    + "datapack at server/world/datapacks/thestill-depths does not have it, or "
                    + "the server has not been restarted since it was added. The lamp is off.");
            return null;
        }
        return new WorldBiomeChannel(log, lamplight, depths);
    }

    /**
     * Reads the number that actually reaches the client and complains if the dial disagrees.
     *
     * <p>Biome data is datapack-owned and loaded at world load, so no dial can move it while
     * the server is up — which means the dial and the file can drift apart and only the file
     * matters. That trap has cost this project two sessions already, once with a stale
     * {@code dials.yml} holding {@code auto-pickup: true} and once with a {@code ranks.yml}
     * that outlived its own rename. Best-effort and warning-only on purpose: a missing file
     * here must not stop the plugin, it just means nobody gets told.
     */
    private void checkDatapackAgreesWithTheDial() {
        try {
            String path = biomeKey.get().replace(':', '/');
            int slash = path.indexOf('/');
            java.io.File file = new java.io.File(Bukkit.getWorldContainer(),
                    "world/datapacks/thestill-depths/data/" + path.substring(0, slash)
                            + "/worldgen/biome/" + path.substring(slash + 1) + ".json");
            if (!file.isFile()) {
                log.warning("fog lamp: " + file + " does not exist — the lamplight biome has "
                        + "no definition, so the lamp will change nothing you can see.");
                return;
            }
            String text = java.nio.file.Files.readString(file.toPath());
            int at = text.indexOf("fog_end_distance");
            if (at < 0) {
                return;
            }
            String tail = text.substring(at).replaceAll("[^0-9.]+", " ").trim();
            double onDisk = Double.parseDouble(tail.split(" ")[0]);
            if (Math.abs(onDisk - distance.asDouble()) > 0.5) {
                log.warning("fog lamp: the dial says " + distance.asDouble() + " blocks and "
                        + file.getName() + " says " + onDisk + ". The FILE wins — it is what the "
                        + "client is served. Edit the file and restart, or move the dial to match "
                        + "so the next reader is not misled.");
            }
        } catch (Throwable ignored) {
            // Reporting-only. Never worth failing enable over.
        }
    }

    private static org.bukkit.block.Biome biome(String key) {
        try {
            NamespacedKey id = NamespacedKey.fromString(key);
            return id == null ? null : io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME).get(id);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- for the command -----------------------------------------------

    /**
     * A lamp, from the catalogue.
     *
     * <p>This method survives the registry rather than being replaced by it, because
     * {@code /still lamp give} should keep asking the service that decides when a lamp
     * lights for the item — a command holding its own {@code ItemStack} would be a
     * second definition, and one of the two would eventually stop matching. What
     * changed is that the service no longer holds one either: it forwards to
     * {@link com.thestill.item.StillItems}, which is the only place identity can be
     * applied at all.
     */
    public org.bukkit.inventory.ItemStack newLamp() {
        return items.build(com.thestill.item.StillItems.FOG_LAMP);
    }

    /** One line for {@code /still lamp}: what is on, what it is using, and who is lit. */
    public List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("enabled: " + enabled.asBoolean() + "   personal: " + perPlayer.get());
        out.add("channel: " + (channel == null ? "NONE — the lamp does nothing" : channel.describe()));
        out.add("biome: " + biomeKey.get() + " at " + distance.asDouble()
                + " blocks, radius " + radius.asInt() + " chunk(s), every "
                + refreshTicks.asInt() + " tick(s)");
        out.add("lit right now: " + lit.size() + " player(s)");
        return out;
    }
}
