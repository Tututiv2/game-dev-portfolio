package com.thestill.mine;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.core.Service;
import com.thestill.region.Region;
import com.thestill.region.RegionService;
import com.thestill.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the Depths: what is in them, how empty they are, and when they refill.
 *
 * <p>Reset is triggered by <b>whichever comes first</b> — a fraction of the mine
 * mined out, or a time cap. The fraction is the real trigger; the cap exists so
 * a mine nobody has touched still turns over occasionally, and so a mine that
 * somehow stops counting cannot get stuck empty forever.
 */
public final class MineService implements Service {

    /** Blocks broken since the last refill. Lives on the region, so it survives restarts. */
    public static final StateKey<Long> BROKEN = StateKey.ofLong("mine.broken", 0L);

    /**
     * {@code "mine"} or {@code "plains"} — see {@link DepthPlan.Kind}.
     *
     * <p>Both kinds have an ore table and both refill. The kind decides the
     * <em>shape</em> of the place — an enclosed shaft or open ground under a
     * false sky — and what spawns in it. Stored on the region rather than
     * recomputed from the depth number so that a hand-authored one-off is
     * possible later without
     * touching {@link DepthPlan}.
     */
    public static final StateKey<String> KIND = StateKey.ofString("depth.kind", "mine");

    /** When the mine last <em>started</em> refilling, epoch millis. */
    public static final StateKey<Long> LAST_RESET = StateKey.ofLong("mine.last-reset", 0L);

    /**
     * True only once a refill has run all the way to the last block.
     *
     * <p>Separate from {@link #LAST_RESET} because they answer different
     * questions, and conflating them is a bug I shipped and caught: the timestamp
     * is stamped when a fill <em>begins</em>, so that a mine cannot re-queue
     * itself every tick while it works. Kill the server mid-fill and the mine
     * comes back looking recently reset while actually being half empty — and
     * because its mined-fraction is zero, nothing would touch it again until the
     * 45 minute time cap.
     *
     * <p>Shadow Prison has the same stamp-at-start behaviour and compensates with
     * a blanket "reset everything" sweep 15 seconds after boot. This flag is the
     * narrower version: only the mines that were actually interrupted get redone.
     */
    public static final StateKey<Boolean> FILLED = StateKey.ofBoolean("mine.filled", false);

    private final Logger log;
    private final Plugin plugin;
    private final RegionService regions;

    private final Map<String, OreTable> tables = new ConcurrentHashMap<>();
    private final Map<String, MineFill> active = new ConcurrentHashMap<>();

    private final Dial<Double> resetFraction;
    private final Dial<Integer> resetCapMinutes;
    private final Dial<Integer> fillBudgetMillis;
    private final Dial<Integer> checkSeconds;
    private final Dial<Boolean> announceResets;

    /** Where players get put when a mine they are standing in refills. */
    private Function<Region, Location> ejectTarget = region -> null;

    private BukkitTask checkTask;
    private BukkitTask fillTask;

    public MineService(Logger log, Plugin plugin, RegionService regions, DialRegistry dials) {
        this.log = log;
        this.plugin = plugin;
        this.regions = regions;

        this.resetFraction = dials.doubleDial("mine.reset-fraction", 0.35)
                .category("Mines").label("Reset at")
                .describe("Refill once this fraction of the mine has been broken. "
                        + "Lower means more resets and less walking to find ore.")
                .range(0.05, 1.0).step(0.05)
                .register();

        this.resetCapMinutes = dials.intDial("mine.reset-cap-minutes", 45)
                .category("Mines").label("Reset time cap")
                .describe("Refill after this long regardless of how little was mined, so a "
                        + "quiet mine still turns over and a stuck counter cannot strand one empty.")
                .range(1, 1440).step(5)
                .register();

        this.fillBudgetMillis = dials.intDial("mine.fill-budget-ms", 2)
                .category("Mines").label("Refill budget per tick")
                .describe("Milliseconds per tick spent placing blocks. A tick is 50ms, so 2 is "
                        + "invisible. Raising this makes resets faster and the server jerkier.")
                .range(1, 25).step(1)
                .register();

        this.checkSeconds = dials.intDial("mine.check-seconds", 5)
                .category("Mines").label("Reset check interval")
                .describe("How often mines are checked for whether they are due a refill.")
                .range(1, 300).step(1)
                .register();

        this.announceResets = dials.boolDial("mine.announce-resets", true)
                .category("Mines").label("Announce resets")
                .describe("Tell players in a mine that it is refilling.")
                .register();
    }

    // ---- lifecycle ------------------------------------------------------

    @Override
    public void start() {
        long checkPeriod = Math.max(1L, checkSeconds.asInt()) * 20L;
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkAll,
                checkPeriod, checkPeriod);
        // Every tick, but each step is bounded by the budget dial.
        fillTask = Bukkit.getScheduler().runTaskTimer(plugin, this::stepFills, 1L, 1L);
    }

    @Override
    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        if (fillTask != null) {
            fillTask.cancel();
        }
        // A half-filled mine is left as it is. It is not corrupt — it is a mine
        // with a hole in it, and the next reset finishes the job.
        if (!active.isEmpty()) {
            log.info("shutdown during " + active.size() + " mine refill(s); they resume on the "
                    + "next reset");
        }
        active.clear();
    }

    // ---- registration ---------------------------------------------------

    public void setOreTable(String regionId, OreTable table) {
        tables.put(regionId, table);
    }

    public OreTable oreTable(String regionId) {
        return tables.get(regionId);
    }

    public boolean isMine(String regionId) {
        return tables.containsKey(regionId);
    }

    public Map<String, OreTable> mines() {
        return new LinkedHashMap<>(tables);
    }

    /** Lets the warp system decide where ejected players land. */
    public void setEjectTarget(Function<Region, Location> ejectTarget) {
        this.ejectTarget = ejectTarget;
    }

    // ---- counters -------------------------------------------------------

    /** Called from the block-break listener. Cheap on purpose: this is a hot path. */
    public void recordBreak(Region region) {
        region.state().set(BROKEN, region.state().get(BROKEN) + 1L);
    }

    public double minedFraction(Region region) {
        long volume = region.bounds().volume();
        if (volume <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) region.state().get(BROKEN) / volume);
    }

    public boolean isResetting(String regionId) {
        return active.containsKey(regionId);
    }

    public double resetProgress(String regionId) {
        MineFill fill = active.get(regionId);
        return fill == null ? 1.0 : fill.progress();
    }

    // ---- reset ----------------------------------------------------------

    private void checkAll() {
        long now = System.currentTimeMillis();
        long capMillis = resetCapMinutes.asInt() * 60_000L;

        for (String regionId : tables.keySet()) {
            if (active.containsKey(regionId)) {
                continue;
            }
            Region region = regions.index().byId(regionId);
            if (region == null) {
                continue;
            }
            if (!region.state().get(FILLED)) {
                // Never filled, or a fill was interrupted by a shutdown. Either
                // way the mine is not whole and the timestamp cannot tell us so.
                reset(region, region.state().get(LAST_RESET) == 0L
                        ? "first fill" : "interrupted fill");
                continue;
            }
            if (minedFraction(region) >= resetFraction.get()) {
                reset(region, "mined out");
            } else if (now - region.state().get(LAST_RESET) >= capMillis) {
                reset(region, "time cap");
            }
        }
    }

    /**
     * Starts a refill.
     *
     * @return false if the mine has no ore table, no region, or is already filling
     */
    public boolean reset(Region region, String reason) {
        if (region == null || active.containsKey(region.id())) {
            return false;
        }
        OreTable table = tables.get(region.id());
        if (table == null) {
            return false;
        }
        World world = Bukkit.getWorld(region.bounds().world());
        if (world == null) {
            log.warning("cannot reset " + region.id() + ": world '"
                    + region.bounds().world() + "' is not loaded");
            return false;
        }

        ejectPlayers(region, world);

        active.put(region.id(), new MineFill(world, region.bounds(), table,
                ThreadLocalRandom.current()));
        region.state().set(BROKEN, 0L);
        region.state().set(LAST_RESET, System.currentTimeMillis());
        // Cleared here and set again only when the fill actually finishes, so an
        // interrupted refill is visible as such after a restart.
        region.state().set(FILLED, false);
        log.info("resetting " + region.id() + " (" + reason + ", "
                + region.bounds().volume() + " blocks)");
        return true;
    }

    /**
     * Moves everyone out before the blocks land on them.
     *
     * <p>Uses the region expanded by a couple of blocks, so someone standing in
     * the doorway is moved too rather than being sealed in by the first block
     * placed next to them.
     */
    private void ejectPlayers(Region region, World world) {
        var safeZone = region.bounds().expanded(2, 2);
        Location target = ejectTarget.apply(region);
        if (target == null) {
            target = world.getSpawnLocation();
        }
        for (Player player : world.getPlayers()) {
            Location at = player.getLocation();
            if (!safeZone.contains(at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
                continue;
            }
            player.teleport(target);
            if (announceResets.asBoolean()) {
                player.sendMessage(Component.text("The shaft is refilling. ",
                                NamedTextColor.GRAY)
                        .append(Component.text("Moved you out.", NamedTextColor.DARK_GRAY)));
            }
        }
    }

    private void stepFills() {
        if (active.isEmpty()) {
            return;
        }
        // ONE deadline for the whole tick, spent across however many mines are
        // filling. An earlier version divided the budget per mine with a floor,
        // which meant 26 simultaneous first fills quietly cost 13x the configured
        // budget - the floor beat the division. A shared deadline cannot drift:
        // the cost of a reset storm is that it takes longer, not that it takes
        // more of the tick.
        long budgetNanos = Math.max(1, fillBudgetMillis.asInt()) * 1_000_000L;
        long deadline = System.nanoTime() + budgetNanos;
        long slice = Math.max(100_000L, budgetNanos / active.size());

        var iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (System.nanoTime() >= deadline) {
                // Out of tick. The mines further down the iteration order wait,
                // which is deliberate: finishing D1 completely while D26 has not
                // started is far more useful to players than 26 half-filled mines.
                break;
            }
            try {
                if (entry.getValue().step(Math.min(slice, deadline - System.nanoTime()))) {
                    iterator.remove();
                    Region region = regions.index().byId(entry.getKey());
                    if (region != null) {
                        region.state().set(FILLED, true);
                    }
                    log.info("finished refilling " + entry.getKey() + " ("
                            + entry.getValue().placed() + " blocks)");
                }
            } catch (Exception e) {
                log.log(Level.SEVERE, "refill of " + entry.getKey() + " failed; abandoning", e);
                iterator.remove();
            }
        }
    }
}
