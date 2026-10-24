package com.skyclone.mod.accessories;

import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Catacombs Expert Ring (+10% Catacombs XP) and the Professor's books (+2/4/6% class XP).
 *
 * <p>The XP is granted inside the dungeons module ({@code Progress.addCataXp / addClassXp}), and the API has no
 * event for it yet (accessories request 12). This binds to that event the moment it exists, by name, so the
 * integrator only has to add it to {@code SkyEvents} and fire it — nothing here changes. The expected shape:
 * <pre>
 *   public static final class DungeonXpGain extends Base {          // in SkyEvents
 *       public final Player player;
 *       public final String track;    // "catacombs", or the class id (BERSERK, HEALER, ...) for class XP
 *       public double amount;         // mutable: listeners scale it before it is added
 *   }
 * </pre>
 * Fired in {@code Progress.addCataXp} with track "catacombs" and in {@code addClassXp} with the class, before the
 * XP is added, and the dungeons module adds {@code event.amount}. Until then {@link #bound} is false and one boot
 * warning says so; the self-test {@code accessories.dungeon-xp} checks only this module's half ({@link #selfCheck})
 * and goes red only if the event exists and did not bind.
 */
final class DungeonXpHook implements Listener {

    static final String EVENT_CLASS = "com.skyclone.api.event.SkyEvents$DungeonXpGain";

    static volatile boolean bound;
    /** True once {@link #EVENT_CLASS} resolves: from then on, NOT binding is accessories' own failure. */
    static volatile boolean apiPresent;
    static volatile String why = "not installed";

    /** Stand-in with exactly the requested shape, so the live self-test can run {@link #shapeError} and
     *  {@link #scale} on the real code path while the API still lacks the event. */
    static final class Probe {
        public final Player player = null;
        public final String track;
        public double amount;
        /** Wave 5: the real event's {@code source}; "admin" grants are left alone. */
        public String source = "run";
        Probe(String track, double amount) { this.track = track; this.amount = amount; }
    }

    /**
     * Accessories' half of the Catacombs/class XP boost, checked end to end with {@link Probe}. Null when it is
     * right. Pass {@code bindError} = {@link #why} when the API has the event: then not being bound is a failure.
     * Pure (unit-tested).
     */
    static String selfCheck(boolean eventExists, boolean isBound, String bindError) {
        try {
            String bad = shapeError(Probe.class);
            if (bad != null) return "probe rejected: " + bad;
            PctSource src = (p, classXp) -> classXp ? 6 : 10;      // a Grimoire and the Expert Ring
            Probe cata = new Probe("catacombs", 1000), cls = new Probe("MAGE", 1000);
            scale(cata, src);
            scale(cls, src);
            if (Math.abs(cata.amount - 1100) > 1e-6) return "catacombs 1000 at +10% became " + cata.amount;
            if (Math.abs(cls.amount - 1060) > 1e-6) return "class 1000 at +6% became " + cls.amount;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "scaling the probe threw " + e;
        }
        if (eventExists && !isBound) return "the API has the event but the hook did not bind: " + bindError;
        return null;
    }

    /** The summed accessory percentage for a player's Catacombs XP ({@code classXp} false) or class XP. */
    @FunctionalInterface
    interface PctSource {
        double pct(Player p, boolean classXp);
    }

    private DungeonXpHook() {}

    /** Null when {@code c} has the shape above, else what is wrong with it. Pure (unit-tested with a stand-in). */
    static String shapeError(Class<?> c) {
        try {
            Field player = c.getField("player"), track = c.getField("track"), amount = c.getField("amount");
            if (!Player.class.isAssignableFrom(player.getType()) || track.getType() != String.class
                    || amount.getType() != double.class || Modifier.isFinal(amount.getModifiers())) {
                return c.getName() + " has the wrong field types (Player player, String track, mutable double amount)";
            }
            return null;
        } catch (NoSuchFieldException e) {
            return c.getName() + " lacks field " + e.getMessage() + " (needs player, track, amount)";
        }
    }

    /** Scale one event's {@code amount} by the player's accessories. */
    static void scale(Object ev, PctSource src) throws ReflectiveOperationException {
        Class<?> c = ev.getClass();
        // wave 5: the real event carries a source; an admin grant (/sb dungeons xp, source "admin") is never boosted
        try {
            Object origin = c.getField("source").get(ev);
            if ("admin".equals(origin)) return;
        } catch (NoSuchFieldException ignored) { }
        Player p = (Player) c.getField("player").get(ev);
        String t = (String) c.getField("track").get(ev);
        Field amount = c.getField("amount");
        double pct = src.pct(p, t != null && !t.equalsIgnoreCase("catacombs"));
        if (pct > 0) amount.setDouble(ev, AccMath.dungeonXp(amount.getDouble(ev), pct));
    }

    @SuppressWarnings("unchecked")
    static void install(EffectEngine engine) {
        Class<?> c;
        try {
            c = Class.forName(EVENT_CLASS, true, DungeonXpHook.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            why = "the API has no " + EVENT_CLASS.replace('$', '.') + " yet (accessories request 12)";
            // not a fault of this module, so not a red self-test: one boot line keeps the gap visible
            Sky.log().warning("[accessories] Catacombs Expert Ring / Professor's books give no XP: " + why);
            return;
        }
        apiPresent = true;
        if (!Event.class.isAssignableFrom(c)) { why = EVENT_CLASS + " is not a Bukkit event"; return; }
        String bad = shapeError(c);
        if (bad != null) { why = bad; return; }
        Bukkit.getPluginManager().registerEvent((Class<? extends Event>) c, new DungeonXpHook(), EventPriority.NORMAL, (ls, ev) -> {
            if (!c.isInstance(ev)) return;
            try {
                scale(ev, engine::dungeonXpPct);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                Sky.log().warning("[accessories] dungeon XP boost failed: " + ex);
            }
        }, Sky.plugin(), true);
        bound = true;
        why = null;
    }
}
