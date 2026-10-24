package com.thestill.descent;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.core.Service;
import com.thestill.mine.DepthPlan;
import com.thestill.ui.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The way up, and the loudest thing in the game.
 *
 * <p><b>Dropping in is free. Getting out is earned.</b> You step off the rig and
 * fall; nothing carries you back. To leave you walk to a station and haul on the
 * winch, and the winch <em>screams</em> — it drives the Din to the top of the
 * scale, pulls everything within earshot toward the one spot you are standing
 * still in, and only then lifts you.
 *
 * <p>That is the whole reason it exists. Din currently peaks while you work, which
 * is the wrong moment: you are mobile, unloaded, and free to walk away. The winch
 * moves the peak to when you are carrying the most and want to be somewhere else,
 * standing in a known place for twelve seconds. It turns the entire game into a
 * voluntary-risk loop — every extra swing is a longer walk back and a bigger pile
 * to lose — and it happens every session rather than once a month.
 *
 * <p><b>The scream is not implemented yet</b>, because Din is not wired to
 * anything: {@code com.thestill.din.Din} is arithmetic with no service behind it.
 * {@link #scream} is where it goes, and it is called at the right moment already.
 */
public final class WinchService implements Service, Listener {

    private final Plugin plugin;
    private final DescentService descent;

    private final Dial<Integer> ascentSeconds;
    private final Dial<Boolean> enabled;

    /** Winch head positions, packed, so a right-click is a map lookup and not a scan. */
    private final Map<Long, Integer> winches = new HashMap<>();

    /** Who is currently being lifted, and the task doing it. */
    private final Map<UUID, Integer> lifting = new HashMap<>();

    public WinchService(Plugin plugin, DescentService descent, DialRegistry dials) {
        this.plugin = plugin;
        this.descent = descent;

        this.ascentSeconds = dials.intDial("winch.ascent-seconds", 12)
                .category("Descent").label("Winch ascent")
                .describe("How long the winch takes to lift you. This is not a loading bar — it "
                        + "is the window in which everything that heard it arrives. Shorter makes "
                        + "extraction safe and takes the tension out of the whole loop.")
                .range(3, 60).step(1)
                .register();

        this.enabled = dials.boolDial("winch.enabled", true)
                .category("Descent").label("Winch works")
                .describe("Turn off only to test something else. With this off there is no way "
                        + "back up from a depth except dying.")
                .register();
    }

    @Override
    public void start() {
        registerWinches();
    }

    @Override
    public void stop() {
        lifting.values().forEach(Bukkit.getScheduler()::cancelTask);
        lifting.clear();
        winches.clear();
    }

    /**
     * Every station's winch head.
     *
     * <p>Positions come from {@link DepthPlan}, the same function {@code StillWorld}
     * built them from, so the block a player right-clicks and the block the world
     * placed cannot drift apart.
     */
    private void registerWinches() {
        for (int depth = 1; depth <= DepthPlan.DEPTH_COUNT; depth++) {
            if (!DepthPlan.isPlains(depth)) {
                continue;
            }
            int[] at = DepthPlan.winchLever(depth);
            winches.put(pack(at[0], at[1], at[2]), depth);
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }

    // ---- pulling the lever ----------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        Integer depth = winches.get(pack(block.getX(), block.getY(), block.getZ()));
        if (depth == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!enabled.asBoolean()) {
            Msg.error(player, "The winch is dead.");
            return;
        }
        if (lifting.containsKey(player.getUniqueId())) {
            Msg.warn(player, "It is already hauling.");
            return;
        }
        begin(player, depth);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Integer task = lifting.remove(event.getPlayer().getUniqueId());
        if (task != null) {
            Bukkit.getScheduler().cancelTask(task);
        }
    }

    // ---- the ascent ------------------------------------------------------

    private void begin(Player player, int depth) {
        Location anchor = player.getLocation().clone();
        int seconds = Math.max(1, ascentSeconds.asInt());
        int totalTicks = seconds * 20;

        player.showTitle(Title.title(
                Component.text("THE WINCH", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("everything can hear this", NamedTextColor.DARK_GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400),
                        Duration.ofMillis(400))));
        scream(player, depth);

        org.bukkit.scheduler.BukkitRunnable haul = new org.bukkit.scheduler.BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                elapsed += 4;

                // Walking away aborts it. The winch is a commitment: standing still
                // in one place for twelve seconds is the price of the ride, and
                // being able to trigger it and then leave would refund that price.
                if (!player.isOnline() || player.isDead()
                        || player.getLocation().distanceSquared(anchor) > 64) {
                    abort(player, getTaskId());
                    return;
                }
                if (elapsed >= totalTicks) {
                    finish(player, getTaskId());
                    return;
                }
                haul(player, elapsed / (double) totalTicks);
            }
        };
        haul.runTaskTimer(plugin, 4L, 4L);
        lifting.put(player.getUniqueId(), haul.getTaskId());
    }

    /** One frame of the haul: it gets louder and faster the closer it is to done. */
    private void haul(Player player, double progress) {
        Location at = player.getLocation();
        World world = at.getWorld();
        float pitch = 0.5f + (float) progress * 1.1f;
        player.playSound(at, Sound.BLOCK_CHAIN_PLACE, 1.0f, pitch);
        if (progress > 0.35) {
            player.playSound(at, Sound.BLOCK_NETHERITE_BLOCK_BREAK, 0.7f, pitch * 0.6f);
        }
        if (world != null) {
            world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 8,
                    0.4, 0.8, 0.4, 0.01);
        }
        int remaining = (int) Math.ceil((1 - progress) * ascentSeconds.asInt());
        player.sendActionBar(Component.text("hauling  ", NamedTextColor.DARK_GRAY)
                .append(Component.text(remaining + "s", NamedTextColor.RED,
                        TextDecoration.BOLD)));
    }

    private void finish(Player player, int taskId) {
        Bukkit.getScheduler().cancelTask(taskId);
        lifting.remove(player.getUniqueId());

        Location hub = descent.hub();
        if (hub == null) {
            Msg.error(player, "The winch turns, and nothing happens. Tell an admin.");
            return;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_IRON_GOLEM_REPAIR, 1.0f, 0.6f);
        player.teleport(hub);
        player.playSound(hub, Sound.BLOCK_ANVIL_LAND, 0.6f, 1.4f);
        player.sendMessage(Component.text("The deck. ", NamedTextColor.GRAY)
                .append(Component.text("You are back on the rig.", NamedTextColor.DARK_GRAY)));
    }

    private void abort(Player player, int taskId) {
        Bukkit.getScheduler().cancelTask(taskId);
        lifting.remove(player.getUniqueId());
        if (player.isOnline()) {
            player.sendActionBar(Component.text("The cable goes slack.",
                    NamedTextColor.DARK_GRAY));
        }
    }

    /**
     * The noise, and the point of the whole machine.
     *
     * <p><b>Phase 2 lands here.</b> When Din is wired this must drive the puller's
     * Din to {@code Din.MAX} and re-target every hostile within earshot — including
     * into the neighbouring depths, because a station has no ambient spawns of its
     * own and the fiction is that using the winch is what makes a safe place
     * unsafe. Right now it only makes the sound.
     *
     * <p>Keep the ordering: the scream comes at the <em>start</em> of the haul, not
     * the end. Twelve seconds of standing still after everything has been told
     * where you are is the entire mechanic.
     */
    private void scream(Player player, int depth) {
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        // Deliberately world-wide within the depth rather than player-only: anyone
        // else down here should know somebody just rang the dinner bell.
        for (Player other : world.getPlayers()) {
            other.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 3.0f, 0.35f);
            other.playSound(at, Sound.BLOCK_ANVIL_PLACE, 2.0f, 0.4f);
        }
    }
}
