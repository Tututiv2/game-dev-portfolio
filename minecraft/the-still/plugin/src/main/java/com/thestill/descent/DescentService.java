package com.thestill.descent;

import com.thestill.core.Locations;
import com.thestill.core.Service;
import com.thestill.player.Profile;
import com.thestill.rank.Rank;
import com.thestill.rank.RankService;
import com.thestill.region.Region;
import com.thestill.region.RegionService;
import com.thestill.region.RegionType;
import com.thestill.state.StateKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Depths as a <em>route</em>: which ones exist, which you have reached, and
 * where the way in is.
 *
 * <p><b>There is no fast travel, in either direction.</b> This class used to be
 * {@code WarpService} and it used to teleport. It does not any more, and the
 * reason is short: if you can teleport, every movement enchant and traversal item
 * ever written is worthless the moment it exists. Traversal is an investment axis,
 * so travel has to be a real cost. You descend every time; the winch is the only
 * way up, and it screams.
 *
 * <p>What survives that decision is everything that was never actually about
 * teleporting — the list of depths, the arrival record, and the entry point of
 * each depth, which the mine refill still needs somewhere to put people.
 */
public final class DescentService implements Service {

    /**
     * Where this depth's descent lands you. Serialised location string.
     *
     * <p>The key is still {@code warp.point} on purpose. It is a persisted column
     * value in every region row already written, and renaming it would orphan all
     * of them to buy nothing — the name is invisible outside
     * {@code /still region state}.
     */
    public static final StateKey<String> ENTRY_POINT = StateKey.ofString("warp.point", "");

    /**
     * Depths this player has physically stood in, comma separated.
     *
     * <p>Once this was the unlock condition for a warp. With warps gone it is a
     * <em>record</em>: it drives the sidebar, {@code /depths}, and the fact that
     * the first player to reach D14 on a server has done something the others can
     * see they have not. Kept because arriving somewhere for the first time is
     * still the event the whole game is shaped around — it just no longer buys a
     * shortcut.
     *
     * <p>Stored as one string rather than a key per depth: twenty-six ids is about
     * 260 characters, and it keeps the state bag readable.
     */
    public static final StateKey<String> DISCOVERED = StateKey.ofString("warp.discovered", "");

    private final RegionService regions;
    private final RankService ranks;

    private Location hub;

    private final org.bukkit.plugin.Plugin plugin;
    private final com.thestill.player.Profiles profiles;
    private org.bukkit.scheduler.BukkitTask arrivalTask;

    public DescentService(org.bukkit.plugin.Plugin plugin, RegionService regions, RankService ranks,
                          com.thestill.player.Profiles profiles) {
        this.plugin = plugin;
        this.regions = regions;
        this.ranks = ranks;
        this.profiles = profiles;
    }

    @Override
    public void start() {
        // Regions are already loaded — this service starts after RegionService.
        loadHubFromRegions();

        // Once a second, not on every move. Arrival is a one-time flag per depth,
        // so a second of latency is invisible, and this costs one cached region
        // lookup per online player rather than one per movement packet.
        arrivalTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                Profile profile = profiles.get(player.getUniqueId());
                if (profile == null) {
                    continue;
                }
                Region here = regions.regionFor(player);
                if (here == null) {
                    continue;
                }
                Region depth = depthOf(here);
                if (depth == null) {
                    continue;
                }
                if (markDiscovered(profile, depth)) {
                    announceArrival(player, depth);
                }
            }
        }, 20L, 20L);
    }

    @Override
    public void stop() {
        if (arrivalTask != null) {
            arrivalTask.cancel();
            arrivalTask = null;
        }
    }

    /**
     * Walks up to the DEPTH a region belongs to.
     *
     * <p>Needed because a plains depth's ore is a nested pit region, so a player
     * standing in the quarry is deepest-in {@code shaft.d5-pit} and not in
     * {@code depth.d5} — and arriving in the quarry is very much arriving at D5.
     */
    private Region depthOf(Region region) {
        for (Region link : regions.index().chainOf(region)) {
            if (link.type() == RegionType.DEPTH) {
                return link;
            }
        }
        return null;
    }

    private void announceArrival(Player player, Region depth) {
        int number = depth.state().get(RankService.DEPTH_NUMBER);
        player.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text("D" + number,
                        net.kyori.adventure.text.format.NamedTextColor.AQUA,
                        net.kyori.adventure.text.format.TextDecoration.BOLD),
                net.kyori.adventure.text.Component.text("first descent",
                        net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(250),
                        java.time.Duration.ofMillis(1600),
                        java.time.Duration.ofMillis(500))));
        player.playSound(player.getLocation(),
                org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 0.8f);
    }

    // ---- hub ------------------------------------------------------------

    /** Falls back to the main world's spawn, so there is always somewhere to send people. */
    public Location hub() {
        if (hub != null) {
            return hub;
        }
        var worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0).getSpawnLocation();
    }

    public void setHub(Location location) {
        this.hub = location.clone();
    }

    /**
     * Where a brand-new player opens their eyes: the Landing, beside its winch.
     *
     * <p><b>Not the rig.</b> The rig used to be the lobby, and starting there taught
     * the world backwards — it handed you safety and asked you to jump out of it, and
     * it left the winch, the most dramatic thing in the whole design, to be found by
     * accident an hour later. Starting on D1 makes the rig something you can see
     * overhead and have to earn, and it puts the first winch pull inside the first
     * five minutes.
     *
     * <p>Beside the winch rather than on it, and facing it, so the first thing in
     * view is the way out.
     */
    public Location landing() {
        var world = Bukkit.getWorld(com.thestill.world.StillWorld.WORLD_KEY);
        return world == null ? null : com.thestill.world.StillWorld.landingSpawn(world);
    }

    /** Loads the hub from the hub region, if one is defined. */
    public void loadHubFromRegions() {
        regions.index().all().stream()
                .filter(region -> region.type() == RegionType.HUB)
                .findFirst()
                .map(region -> Locations.parse(region.state().get(ENTRY_POINT)))
                .ifPresent(location -> this.hub = location);
    }

    // ---- entry points ---------------------------------------------------

    /**
     * Where a depth puts you down.
     *
     * <p>Not a destination you can ask for any more — the descent decides when you
     * arrive. It is still needed because a mine refilling underneath a player has
     * to move them somewhere, and "somewhere" should be this depth's own entrance
     * rather than the far side of the world.
     */
    public Location entryPointOf(Region region) {
        if (region == null) {
            return null;
        }
        Location stored = Locations.parse(region.state().get(ENTRY_POINT));
        if (stored != null) {
            return stored;
        }
        // No explicit point: stand them on top of the middle of the region.
        // Better than refusing, and obviously provisional when you see it.
        var world = Bukkit.getWorld(region.bounds().world());
        if (world == null) {
            return null;
        }
        return region.bounds().center(world).add(0, region.bounds().height() / 2.0 + 1, 0);
    }

    public void setEntryPoint(Region region, Location location) {
        region.state().set(ENTRY_POINT, Locations.serialise(location));
    }

    /** Every depth, in depth order, whether or not the player can enter it. */
    public List<Region> depths() {
        List<Region> depths = new ArrayList<>();
        for (Region region : regions.index().all()) {
            if (region.type() == RegionType.DEPTH) {
                depths.add(region);
            }
        }
        depths.sort(Comparator.comparingInt(r -> r.state().get(RankService.DEPTH_NUMBER)));
        return depths;
    }

    /** The deepest depth this player has actually stood in. */
    public Region deepestReached(Profile profile) {
        Region best = null;
        int bestNumber = -1;
        for (Region depth : depths()) {
            if (!hasReached(profile, depth)) {
                continue;
            }
            int number = depth.state().get(RankService.DEPTH_NUMBER);
            if (number > bestNumber) {
                bestNumber = number;
                best = depth;
            }
        }
        return best;
    }

    /** The next depth they cannot get into yet — what the sidebar should be pointing at. */
    public Region nextLocked(Profile profile) {
        for (Region depth : depths()) {
            if (!ranks.canAccess(profile, depth)) {
                return depth;
            }
        }
        return null;
    }

    // ---- arrival records -------------------------------------------------

    public boolean hasReached(Profile profile, Region region) {
        if (profile == null || region == null) {
            return false;
        }
        String found = profile.state().get(DISCOVERED);
        // Delimited on both sides so "depth.d1" cannot match inside "depth.d12".
        return ("," + found + ",").contains("," + region.id() + ",");
    }

    /**
     * Records that this player has stood here.
     *
     * @return true if this is the first time — the caller announces it
     */
    public boolean markDiscovered(Profile profile, Region region) {
        if (profile == null || region == null || hasReached(profile, region)) {
            return false;
        }
        String found = profile.state().get(DISCOVERED);
        profile.state().set(DISCOVERED, found.isEmpty() ? region.id() : found + "," + region.id());
        return true;
    }

    public int reachedCount(Profile profile) {
        String found = profile.state().get(DISCOVERED);
        return found.isEmpty() ? 0 : found.split(",").length;
    }

    public Rank rankNeededFor(Region region) {
        return ranks.rankNeededFor(region);
    }
}
