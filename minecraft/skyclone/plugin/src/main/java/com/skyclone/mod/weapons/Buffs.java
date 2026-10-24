package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.stat.StatProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived state the weapon abilities create:
 * <ul>
 *   <li><b>stat buffs</b> on players (Quickstep's +100 Speed), keyed so the same buff never stacks,
 *       exposed through the "weapons" StatProvider;</li>
 *   <li><b>flags</b> on players with an optional value (Reckless Power, Focus Burn's next hit);</li>
 *   <li><b>marks</b> on mobs with a value (frozen, provoked, dusk poison, plate-split stacks);</li>
 *   <li><b>freezes</b>: AI off for a while, restored exactly as it was.</li>
 * </ul>
 * Nothing here is persisted: a restart clears every buff, which is how the genre behaves too.
 */
final class Buffs implements StatProvider {

    private record Buff(Stat stat, double amount, long until) {}

    private record Mark(double value, long until) {}

    private final Map<UUID, Map<String, Buff>> buffs = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Mark>> flags = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Mark>> marks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> frozenUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> frozenHadAi = new ConcurrentHashMap<>();

    static long now() { return System.currentTimeMillis(); }

    // ---- stat buffs ------------------------------------------------------------------------------

    void buff(Player p, String key, Stat stat, double amount, double seconds) {
        buffs.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(key, new Buff(stat, amount, now() + (long) (seconds * 1000)));
        Sky.stats().invalidate(p);
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            Player q = Bukkit.getPlayer(id);
            if (q != null) Sky.stats().invalidate(q);
        }, Math.max(1, (long) (seconds * 20) + 1));
    }

    double buffValue(Player p, String key) {
        Map<String, Buff> m = buffs.get(p.getUniqueId());
        if (m == null) return 0;
        Buff b = m.get(key);
        return b == null || b.until < now() ? 0 : b.amount;
    }

    @Override
    public void contribute(Player player, StatMap into) {
        Map<String, Buff> m = buffs.get(player.getUniqueId());
        if (m == null || m.isEmpty()) return;
        long t = now();
        for (Iterator<Buff> it = m.values().iterator(); it.hasNext(); ) {
            Buff b = it.next();
            if (b.until < t) { it.remove(); continue; }
            into.add(b.stat, b.amount);
        }
    }

    // ---- player flags ----------------------------------------------------------------------------

    void flag(Player p, String key, double value, double seconds) {
        flags.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>()).put(key, new Mark(value, now() + (long) (seconds * 1000)));
    }

    boolean has(Player p, String key) { return flagValue(p, key) != null; }

    /** Value of an unexpired flag, or null. */
    Double flagValue(Player p, String key) {
        Map<String, Mark> m = flags.get(p.getUniqueId());
        if (m == null) return null;
        Mark mk = m.get(key);
        if (mk == null) return null;
        if (mk.until < now()) { m.remove(key); return null; }
        return mk.value;
    }

    /** Read and remove a flag (one-shot effects like "your next hit"). */
    Double consume(Player p, String key) {
        Double v = flagValue(p, key);
        if (v != null) clear(p, key);
        return v;
    }

    void clear(Player p, String key) {
        Map<String, Mark> m = flags.get(p.getUniqueId());
        if (m != null) m.remove(key);
    }

    // ---- mob marks -------------------------------------------------------------------------------

    void mark(Entity e, String key, double value, double seconds) {
        marks.computeIfAbsent(e.getUniqueId(), k -> new ConcurrentHashMap<>()).put(key, new Mark(value, now() + (long) (seconds * 1000)));
    }

    Double markValue(Entity e, String key) {
        if (e == null) return null;
        Map<String, Mark> m = marks.get(e.getUniqueId());
        if (m == null) return null;
        Mark mk = m.get(key);
        if (mk == null) return null;
        if (mk.until < now()) { m.remove(key); return null; }
        return mk.value;
    }

    // ---- freezes ---------------------------------------------------------------------------------

    void freeze(LivingEntity e, double seconds) {
        if (e == null || !e.isValid() || e instanceof Player) return;
        UUID id = e.getUniqueId();
        long until = now() + (long) (seconds * 1000);
        Long prev = frozenUntil.get(id);
        if (prev == null) frozenHadAi.put(id, e.hasAI());
        if (prev != null && prev > until) return;
        frozenUntil.put(id, until);
        e.setAI(false);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> thaw(e, false), Math.max(1, (long) (seconds * 20)));
    }

    boolean frozen(Entity e) {
        Long u = e == null ? null : frozenUntil.get(e.getUniqueId());
        return u != null && u > now();
    }

    private void thaw(LivingEntity e, boolean force) {
        UUID id = e.getUniqueId();
        Long u = frozenUntil.get(id);
        if (u == null) return;
        if (!force && u > now() + 25) return;   // re-frozen later: that task thaws it
        frozenUntil.remove(id);
        Boolean had = frozenHadAi.remove(id);
        if (e.isValid()) e.setAI(had == null || had);
    }

    // ---- housekeeping ----------------------------------------------------------------------------

    void forget(UUID player) {
        buffs.remove(player);
        flags.remove(player);
    }

    /** Every 30 s: drop expired marks of dead mobs. */
    void sweep() {
        long t = now();
        marks.values().forEach(m -> m.values().removeIf(mk -> mk.until < t));
        marks.values().removeIf(Map::isEmpty);
        for (Map.Entry<UUID, Long> e : frozenUntil.entrySet()) {
            if (e.getValue() < t - 5000) {
                Entity ent = Bukkit.getEntity(e.getKey());
                if (ent instanceof LivingEntity le) thaw(le, true);
                else { frozenUntil.remove(e.getKey()); frozenHadAi.remove(e.getKey()); }
            }
        }
    }

    /** At shutdown: give every frozen mob its AI back. */
    void thawAll() {
        for (UUID id : frozenUntil.keySet()) {
            Entity ent = Bukkit.getEntity(id);
            if (ent instanceof LivingEntity le) thaw(le, true);
        }
        frozenUntil.clear();
        frozenHadAi.clear();
    }
}
