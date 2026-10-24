package com.thestill.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.logging.Logger;

/**
 * The one thing standing between this server and an army of duplicate NPCs.
 *
 * <p>Ported from Shadow Prison's {@code NpcGenerationGuard}, which was written after
 * the same bug bit it, and whose own words are the clearest statement of the problem:
 *
 * <blockquote>
 * "At boot the removal sweep only sees entities in chunks that have already streamed
 * in — stragglers loading later survived and doubled up next to their freshly-spawned
 * selves (worst right after moving NPCs, since both old and new spots hold a copy)."
 * </blockquote>
 *
 * <p><b>The Still already has this bug.</b> {@code HologramService.clear()} calls
 * {@code getEntitiesByClass(TextDisplay.class)}, which returns entities in
 * <em>loaded chunks only</em> — and at boot the rig is outside everyone's view
 * distance, which is exactly why {@code place()} has to load the chunk before
 * spawning. So the clear pass has been best-effort since the day it was written, and
 * the only reason nobody has seen a doubled sign is that the rig chunks happen to be
 * loaded during a normal restart. Change the view distance, or add a second world, or
 * put an NPC somewhere quieter, and it starts.
 *
 * <h2>How it works</h2>
 * Every entity this plugin spawns is stamped with <em>this boot's</em> generation id.
 * When a chunk's entities load, anything carrying one of our tags whose stamp is
 * missing or stale is removed on the spot. Old duplicates therefore self-clean as
 * their chunks stream in, with no manual purge and no sweep that has to be complete.
 *
 * <h2>The rule</h2>
 * <b>Never hardcode a tag string at a use site.</b> Add it to {@link #TAGS} and read
 * it from here. Shadow Prison's guard carries the same rule and explains why: it had
 * three parallel lists, they had already drifted, and "that exact class of drift has
 * already eaten the Hall of Fame statues". Protection can then only ever be gained,
 * never silently lost.
 */
public final class ManagedEntities implements Service, Listener {

    /**
     * This boot, as an id.
     *
     * <p>Wall-clock rather than a counter because it has to be comparable across
     * restarts without being stored anywhere. Two boots inside the same millisecond
     * would collide; the server takes seconds to start.
     */
    public static final long BOOT = System.currentTimeMillis();

    /**
     * THE ONE REGISTRY OF MANAGED-ENTITY TAGS.
     *
     * <p>Every system that spawns entities adds its tag here and reads it from here.
     * A tag that is not in this list is not protected and not cleaned up.
     */
    public static final String HOLOGRAM = "thestill_holo";
    public static final String STAFF = "thestill_staff";

    public static final List<String> TAGS = List.of(HOLOGRAM, STAFF);

    private final Logger log;
    private final NamespacedKey generation;

    private int culled;

    public ManagedEntities(Logger log, Plugin plugin) {
        this.log = log;
        this.generation = new NamespacedKey(plugin, "generation");
    }

    /**
     * Marks an entity as belonging to this boot.
     *
     * <p><b>Stamp every spawn.</b> An unstamped entity carrying one of our tags is
     * indistinguishable from a leftover and will be culled the next time its chunk
     * loads — which is the correct behaviour and a very confusing bug.
     */
    public void stamp(Entity entity) {
        entity.getPersistentDataContainer().set(generation, PersistentDataType.LONG, BOOT);
    }

    /** Is this one of ours, from this boot? */
    public boolean isCurrent(Entity entity) {
        Long stamp = entity.getPersistentDataContainer()
                .get(generation, PersistentDataType.LONG);
        return stamp != null && stamp == BOOT;
    }

    private static boolean isManaged(Entity entity) {
        for (String tag : TAGS) {
            if (entity.getScoreboardTags().contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Culls leftovers as their chunks stream in.
     *
     * <p>This is the half a boot sweep cannot do. A sweep can only see what is
     * loaded; this sees everything, eventually, and eventually is soon enough
     * because an entity nobody has loaded is an entity nobody can see.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (isManaged(entity) && !isCurrent(entity)) {
                entity.remove();
                culled++;
            }
        }
    }

    @Override
    public void stop() {
        // Deliberately does NOT remove anything. The entities are cosmetic and
        // cheap, and clearing them on a crash-stop would leave the world bare until
        // the next successful boot. The next boot's stamp is what cleans up.
        if (culled > 0) {
            log.info("managed entities: culled " + culled + " leftover(s) from earlier boots");
        }
    }
}
