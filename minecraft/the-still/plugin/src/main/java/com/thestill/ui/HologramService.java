package com.thestill.ui;

import com.thestill.core.Service;
import com.thestill.world.StillWorld;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import com.thestill.config.Dial;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Floating text, the way Shadow Prison does it — {@link TextDisplay} entities.
 *
 * <p>This is a port, not an invention. Shadow Prison's {@code FloatingTextService}
 * already settled that holograms are text displays respawned from code on every
 * boot, and this project's own design law says <b>search before you create</b>.
 * The Still shipped a rig covered in oak signs instead, because that law was not
 * followed. It is written down here so the next person checks
 * {@code ../Minecraft game im making/} first.
 *
 * <p>Respawn-from-code is the important half. Displays are entities, so they
 * persist in the world and would stack up a duplicate set every restart. Every one
 * this service creates is tagged, and every tagged one is removed before any are
 * placed, so the count is idempotent no matter how many times the server boots.
 */
public final class HologramService implements Service {

    /** Scoreboard tag on everything this service owns, so cleanup is exact. */
    private static final String TAG = com.thestill.core.ManagedEntities.HOLOGRAM;

    /**
     * How far text carries, and why it is a dial rather than a constant.
     *
     * <p><b>A {@code TextDisplay} is not fogged.</b> Blocks fade out at the biome's
     * {@code fog_end_distance} — 22 blocks down in the depths — and floating text does
     * not: it renders at full strength right out to its view range. So a generous view
     * range does not make a sign easier to read, it makes it <em>punch a hole through
     * the weather</em>, and the first version set it to three (192 blocks) on a banner
     * scaled four and a half times. The owner's report was "giant floating text from
     * extremely far away in the fog", which is exactly what that is.
     *
     * <p>These are dials because they are the kind of number that can only be judged by
     * standing in front of one. Range is a multiplier on 64 blocks.
     */
    private final Dial<Double> labelScale;
    private final Dial<Double> labelRange;
    private final Dial<Double> bannerScale;
    private final Dial<Double> bannerRange;

    /** Only the welcome banners carry this, so the animation loop can find them. */
    private static final String BANNER_TAG = "thestill_banner";

    private static final String BANNER_TEXT = "WELCOME TO THE STILL";
    private static final String BANNER_SUB = "noise calls  ·  heat keeps";

    /**
     * Red to almost-black.
     *
     * <p>Not to pure black: half the letters would be invisible and the words would
     * read as a gap moving through them. A very dark red still reads as black against
     * this fog and keeps the characters legible, which is what makes it look like
     * embers rather than like a rendering fault.
     */
    private static final TextColor EMBER = TextColor.color(0xE8382B);
    private static final TextColor ASH = TextColor.color(0x1A0303);

    private final Logger log;
    private final StillWorld world;
    private final Plugin plugin;

    /**
     * The banners, by id rather than by reference.
     *
     * <p><b>A reference is not safe to hold.</b> The first version kept the entities
     * themselves and dropped any that failed {@code isValid()} — but an entity in an
     * unloaded chunk is not valid, and with nobody online every chunk unloads. So the
     * list emptied itself within a minute of boot and the animation ran forever over
     * nothing, silently and with no error in the log. Ids resolve to null while the
     * chunk is out and resolve again when it comes back.
     */
    private final List<java.util.UUID> banners = new ArrayList<>();
    private BukkitTask animation;
    private float phase;

    private final com.thestill.core.ManagedEntities managed;

    public HologramService(Logger log, Plugin plugin, StillWorld world,
                           com.thestill.core.ManagedEntities managed,
                           com.thestill.config.DialRegistry dials) {
        this.log = log;
        this.plugin = plugin;
        this.world = world;
        this.managed = managed;

        this.labelScale = dials.doubleDial("ui.label-scale", 2.2)
                .category("Interface").label("Shop label size")
                .describe("How much bigger than default a shop's floating name renders.")
                .range(0.5, 6.0).step(0.1)
                .register();
        this.labelRange = dials.doubleDial("ui.label-range", 0.5)
                .category("Interface").label("Shop label range")
                .describe("How far a shop name is visible, as a multiple of 64 blocks. "
                        + "Floating text is NOT hidden by fog, so a high value here "
                        + "shows signs through weather that hides the building.")
                .range(0.1, 4.0).step(0.05)
                .register();
        this.bannerScale = dials.doubleDial("ui.banner-scale", 2.8)
                .category("Interface").label("Welcome banner size")
                .describe("How much bigger than default the welcome banners render.")
                .range(0.5, 8.0).step(0.1)
                .register();
        this.bannerRange = dials.doubleDial("ui.banner-range", 0.55)
                .category("Interface").label("Welcome banner range")
                .describe("How far a welcome banner is visible, as a multiple of 64 "
                        + "blocks. Keep it inside the local fog or it reads through it.")
                .range(0.1, 4.0).step(0.05)
                .register();
    }

    @Override
    public void start() {
        World depths = world.world();
        if (depths == null) {
            return;
        }
        int removed = clear(depths);
        int placed = 0;
        for (StillWorld.Marker marker : StillWorld.marketMarkers()) {
            try {
                place(depths, marker);
                placed++;
            } catch (Exception e) {
                log.log(Level.WARNING, "could not raise the hologram for "
                        + marker.name(), e);
            }
        }
        placed += raiseBanners(depths);
        log.info("holograms: " + placed + " raised, " + removed + " stale removed");

        // Two ticks a frame. The gradient is recomputed and pushed as one entity
        // metadata update per banner — there are two of them, so this is cheaper
        // than the sidebar and nobody will ever see it on a timings report.
        animation = Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 40L, 2L);
    }

    @Override
    public void stop() {
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
        banners.clear();
        // The displays themselves are deliberately NOT removed on shutdown. They are
        // cosmetic, they are cheap, and clearing them on a crash-stop would leave the
        // world bare until the next successful boot. start() clears them instead,
        // which is the pass that is guaranteed to run.
    }

    /**
     * The welcome banners: one where a new player wakes up, one on the rig deck.
     *
     * <p>The first is the whole reason this exists — you open your eyes on a plain in
     * fog with a road running north, and the server says its name at you in burning
     * letters. It hangs over the arrival apron rather than at the spawn point itself,
     * so it is something you are looking <em>at</em>.
     */
    private int raiseBanners(World depths) {
        int[][] spots = {
                StillWorld.landingBanner(depths),
                {0, com.thestill.mine.DepthPlan.RIG_Y + 8, com.thestill.mine.DepthPlan.RIG_HOLE + 14},
        };
        int placed = 0;
        for (int[] at : spots) {
            try {
                Location location = new Location(depths, at[0] + 0.5, at[1], at[2] + 0.5);
                depths.getChunkAt(location).load();
                TextDisplay display = depths.spawn(location, TextDisplay.class, banner -> {
                    banner.addScoreboardTag(TAG);
                    banner.addScoreboardTag(BANNER_TAG);
                    managed.stamp(banner);
                    banner.setBillboard(Display.Billboard.CENTER);
                    banner.setAlignment(TextDisplay.TextAlignment.CENTER);
                    banner.setSeeThrough(true);
                    banner.setShadowed(true);
                    // No background plate. A dark box behind letters that fade to
                    // black is a dark box with nothing in it — the glow has to be
                    // the only thing you see.
                    banner.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    banner.setTransformation(scaled((float) bannerScale.get().doubleValue()));
                    banner.setViewRange((float) bannerRange.get().doubleValue());
                    banner.setPersistent(true);
                });
                banners.add(display.getUniqueId());
                placed++;
            } catch (Exception e) {
                log.log(Level.WARNING, "could not raise a welcome banner", e);
            }
        }
        return placed;
    }

    /** One frame of the sweep. */
    private void animate() {
        phase += 0.012f;
        if (phase > 1f) {
            phase -= 1f;
        }
        Component frame = Gradients.sweep(BANNER_TEXT, EMBER, ASH, 1.6f, phase, true)
                .append(Component.newline())
                .append(Component.text(BANNER_SUB, NamedTextColor.DARK_GRAY));
        for (java.util.UUID id : banners) {
            // Null while the chunk is unloaded — skipped, never forgotten.
            if (Bukkit.getEntity(id) instanceof TextDisplay banner) {
                banner.text(frame);
            }
        }
    }

    private int clear(World depths) {
        int removed = 0;
        for (var entity : depths.getEntitiesByClass(TextDisplay.class)) {
            if (entity.getScoreboardTags().contains(TAG)) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    private void place(World depths, StillWorld.Marker marker) {
        Location at = new Location(depths, marker.x() + 0.5, marker.y(), marker.z() + 0.5);
        // Loaded explicitly: at boot the rig is usually outside anyone's view
        // distance, and spawning into an unloaded chunk silently does nothing.
        depths.getChunkAt(at).load();

        depths.spawn(at, TextDisplay.class, display -> {
            display.addScoreboardTag(TAG);
            managed.stamp(display);
            display.text(Component.text(marker.name(),
                            marker.locked() ? NamedTextColor.DARK_GRAY : NamedTextColor.AQUA,
                            TextDecoration.BOLD)
                    .append(Component.newline())
                    .append(Component.text(marker.locked() ? "sealed" : marker.sub(),
                            marker.locked() ? NamedTextColor.DARK_RED : NamedTextColor.GRAY)));
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            // SCALE, and it is the only reason these are legible.
            //
            // A TextDisplay renders at its transformation's scale, and the default
            // is 1 — which on a 193-wide deck, from across a plaza, is a smear. The
            // shop names are the only thing telling a player what a building is for
            // until Phase 7 lands NPCs, so they are sized to be read from the far
            // side of the space they label.
            display.setTransformation(scaled((float) labelScale.get().doubleValue()));
            // Deliberately SHORTER than the fog rather than longer. A shop sign is
            // something you read when you are at the shop; a sign you can read from
            // the far side of the deck turns a working platform into a menu, which is
            // the exact failure the market markers were filtered to avoid.
            display.setViewRange((float) labelRange.get().doubleValue());
            display.setPersistent(true);
        });
    }

    /** A transformation that is nothing but a scale. */
    private static Transformation scaled(float scale) {
        return new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f(0f, 0f, 0f, 1f),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0f, 0f, 0f, 1f));
    }
}
