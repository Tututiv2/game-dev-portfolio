package com.thestill.npc;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.core.ManagedEntities;
import com.thestill.core.Service;
import com.thestill.mine.DepthPlan;
import com.thestill.ui.Gradients;
import com.thestill.world.StillWorld;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The people behind the counters.
 *
 * <p>One clerk per staffed building, standing where {@link StillWorld#serviceCounters()}
 * says — which is to say, derived from the layout and not placed by anybody. Shadow
 * Prison's equivalent has a {@code /shop setnpc} command that saves wherever you happen
 * to be standing; that is exactly the hand-placement this rig was rebuilt to delete, and
 * it is not ported.
 *
 * <h2>Why the rig looks empty without this</h2>
 * There are thirty-five buildings on the deck, each with a door, a lit interior and a
 * floating sign saying what it is for, and until now every one of them was empty. A shop
 * with nobody in it reads as scenery no matter how good the shopfront is.
 */
public final class RigStaff implements Service {

    private final Logger log;
    private final Plugin plugin;
    private final StillWorld world;
    private final ManagedEntities managed;
    private final NamespacedKey counterKey;

    private final Dial<Boolean> enabled;
    private final Dial<Boolean> lookAtPlayers;
    private final Dial<Integer> lookRadius;
    private final Dial<Integer> lookTicks;

    private BukkitTask looking;

    /**
     * Which villager wears which apron.
     *
     * <p>A profession is a free costume: it changes the model's clothing with no assets
     * and no resource pack, and it is the whole difference between "a shopkeeper" and
     * "a villager standing indoors". Anything not named here gets NITWIT, which reads
     * as crew rather than as a trader — correct for the counters that only talk.
     */
    private static final Map<String, Villager.Profession> APRONS = Map.ofEntries(
            Map.entry("assay", Villager.Profession.CARTOGRAPHER),
            Map.entry("tools", Villager.Profession.TOOLSMITH),
            Map.entry("vault", Villager.Profession.LIBRARIAN),
            Map.entry("smeltery", Villager.Profession.ARMORER),
            Map.entry("auger", Villager.Profession.WEAPONSMITH),
            Map.entry("mess", Villager.Profession.BUTCHER),
            Map.entry("claims", Villager.Profession.LIBRARIAN),
            Map.entry("hiring", Villager.Profession.CLERIC),
            Map.entry("outfitter", Villager.Profession.LEATHERWORKER),
            Map.entry("instruments", Villager.Profession.CARTOGRAPHER),
            Map.entry("charts", Villager.Profession.CARTOGRAPHER),
            Map.entry("enchanter", Villager.Profession.CLERIC),
            Map.entry("suit", Villager.Profession.LEATHERWORKER),
            Map.entry("winch", Villager.Profession.MASON),
            Map.entry("muster", Villager.Profession.NITWIT));

    public RigStaff(Logger log, Plugin plugin, StillWorld world,
                    ManagedEntities managed, DialRegistry dials) {
        this.log = log;
        this.plugin = plugin;
        this.world = world;
        this.managed = managed;
        this.counterKey = new NamespacedKey(plugin, "counter");

        this.enabled = dials.boolDial("npc.enabled", true)
                .category("NPCs").label("Rig staff")
                .describe("Put a clerk behind every staffed counter on the rig.")
                .register();
        this.lookAtPlayers = dials.boolDial("npc.look-at-players", true)
                .category("NPCs").label("Clerks look at you")
                .describe("Clerks turn to face the nearest player. Off, they stare at "
                        + "one fixed angle forever, which reads as a prop.")
                .register();
        this.lookRadius = dials.intDial("npc.look-radius", 8)
                .category("NPCs").label("Look radius")
                .describe("How close you have to be before a clerk notices you.")
                .range(2, 32).step(1)
                .register();
        this.lookTicks = dials.intDial("npc.look-period-ticks", 5)
                .category("NPCs").label("Look period")
                .describe("Ticks between turns. Lower is smoother and costs more.")
                .range(1, 40).step(1)
                .register();
    }

    /** The building this entity works in, or null if it is not one of ours. */
    public String counterOf(Entity entity) {
        return entity.getPersistentDataContainer().get(counterKey, PersistentDataType.STRING);
    }

    @Override
    public void start() {
        World depths = world.world();
        if (depths == null || !enabled.asBoolean()) {
            return;
        }
        // Load the rig before sweeping. getEntities() only sees loaded chunks, and at
        // boot the deck is outside everyone's view distance — the same fact that makes
        // HologramService load a chunk before spawning into it. The generation guard
        // catches whatever streams in later; this makes the common case exact rather
        // than merely eventually-correct.
        int half = DepthPlan.RIG_HALF >> 4;
        for (int cx = -half - 1; cx <= half + 1; cx++) {
            for (int cz = -half - 1; cz <= half + 1; cz++) {
                depths.getChunkAt(cx, cz).load();
            }
        }

        int removed = 0;
        for (Entity entity : depths.getEntities()) {
            if (entity.getScoreboardTags().contains(ManagedEntities.STAFF)) {
                entity.remove();
                removed++;
            }
        }

        int placed = 0;
        int refused = 0;
        for (StillWorld.Counter counter : StillWorld.serviceCounters()) {
            Location at = new Location(depths, counter.x(), counter.y(), counter.z(),
                    counter.yaw(), 0f);
            if (!standable(depths, at)) {
                // A missing clerk with a log line beats one embedded in an anvil. Same
                // rule as furnish() refusing a stairwell it cannot finish.
                log.warning("staff: nowhere to stand in '" + counter.id()
                        + "' at " + at.getBlockX() + "," + at.getBlockY() + ","
                        + at.getBlockZ() + " — that counter is unstaffed");
                refused++;
                continue;
            }
            try {
                spawn(depths, at, counter);
                placed++;
            } catch (Exception e) {
                log.log(Level.WARNING, "staff: could not place the clerk for "
                        + counter.id(), e);
            }
        }
        log.info("rig staff: " + placed + " clerk(s) placed, " + removed
                + " stale removed" + (refused > 0 ? ", " + refused + " with nowhere to stand" : ""));

        if (lookAtPlayers.asBoolean()) {
            looking = Bukkit.getScheduler().runTaskTimer(plugin, this::look,
                    40L, Math.max(1, lookTicks.asInt()));
        }
    }

    @Override
    public void stop() {
        if (looking != null) {
            looking.cancel();
            looking = null;
        }
        // The clerks stay. Removing them on a crash-stop would leave the rig empty
        // until the next successful boot; the generation stamp is what cleans up.
    }

    /**
     * Can a clerk stand here?
     *
     * <p>Asks {@link com.thestill.world.ClimbVerifier}, which is the one function in
     * this codebase that knows the answer. The first version rolled its own using
     * {@code Block.isPassable()} and refused to staff five shops — because
     * {@code isPassable()} is false for CARPET, and {@code furnish} carpets about
     * thirty-eight percent of every floor on the rig. A clerk cannot stand where a
     * player provably can is not a bug worth having twice.
     */
    private static boolean standable(World depths, Location at) {
        return com.thestill.world.ClimbVerifier.standable(
                depths, at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    private void spawn(World depths, Location at, StillWorld.Counter counter) {
        // The consumer form configures the entity BEFORE it joins the world, so it
        // never exists for a tick as an untagged, AI-enabled, shovable mob.
        depths.spawn(at, Villager.class, clerk -> {
            clerk.addScoreboardTag(ManagedEntities.STAFF);
            managed.stamp(clerk);
            clerk.getPersistentDataContainer()
                    .set(counterKey, PersistentDataType.STRING, counter.id());

            clerk.customName(Gradients.bold(counter.name(),
                            TextColor.color(0xE8382B), TextColor.color(0xFFD2B0))
                    .append(Component.text("  " + counter.sub(), NamedTextColor.DARK_GRAY)));
            clerk.setCustomNameVisible(true);

            clerk.setProfession(APRONS.getOrDefault(counter.id(), Villager.Profession.NITWIT));
            clerk.setVillagerLevel(5);
            clerk.setAI(false);
            clerk.setPersistent(true);
            clerk.setInvulnerable(true);
            clerk.setSilent(true);
            clerk.setRemoveWhenFarAway(false);
            // Neither of these appears anywhere in Shadow Prison, and both are real
            // gaps. setAI(false) stops a villager walking; it does not stop a player
            // pushing one. On a deck with an open drop shaft in the middle, a clerk
            // who can be shoved is a clerk who ends up at the bottom of it — and
            // gravity turns that into "the NPC never spawned", which is a bug that
            // looks like a different bug. Floating is the visible failure; choose it.
            clerk.setCollidable(false);
            clerk.setGravity(false);
        });
    }

    /**
     * Clerks turn to face you.
     *
     * <p>Ported from Shadow Prison, and it is the single cheapest thing that stops an
     * NPC reading as furniture. The pitch clamp matters: without it they crane at
     * players two floors up, which looks possessed rather than attentive.
     */
    private void look() {
        if (!lookAtPlayers.asBoolean()) {
            return;
        }
        int radius = lookRadius.asInt();
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (Entity nearby : player.getNearbyEntities(radius, 5, radius)) {
                if (!(nearby instanceof LivingEntity clerk)
                        || !nearby.getScoreboardTags().contains(ManagedEntities.STAFF)) {
                    continue;
                }
                double dx = player.getX() - clerk.getX();
                double dy = player.getEyeLocation().getY() - clerk.getEyeLocation().getY();
                double dz = player.getZ() - clerk.getZ();
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
                clerk.setRotation(yaw, Math.clamp(pitch, -30f, 30f));
            }
        }
    }

    /** Every clerk's id, for diagnostics. */
    public List<String> staffed() {
        return StillWorld.serviceCounters().stream().map(StillWorld.Counter::id).toList();
    }
}
