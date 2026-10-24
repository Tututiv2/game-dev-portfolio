package com.skyclone.core.combat;

import com.skyclone.api.mob.MobDef;
import org.bukkit.entity.LivingEntity;

import java.util.UUID;

/** Core's record of one live sky mob. Main thread only. */
final class LiveMob {

    final LivingEntity entity;
    final MobDef def;
    final UUID owner;
    /** Spawn rule that created it, or null. */
    final String ruleId;

    double hp;
    double max;

    UUID lastPlayer;
    long lastPlayerAt;
    boolean dead;
    long spawnedAt = System.currentTimeMillis();
    String lastPlate;
    /**
     * Per-attacker melee rate limit: attacker uuid -> first server tick their next hit may land.
     * Vanilla invulnerability is OFF on sky mobs (maxNoDamageTicks 0) so this is the only gate,
     * which lets several players hit one mob at full speed and lets every arrow land.
     */
    /** Extra players treated like the owner (party / co-op), set by MobService.setAllies. Null = owner only. */
    volatile java.util.function.Predicate<org.bukkit.entity.Player> allies;

    /** Owner, an ally, or an unowned mob. */
    boolean allows(org.bukkit.entity.Player p) {
        if (owner == null) return true;
        if (p == null) return false;
        if (owner.equals(p.getUniqueId())) return true;
        java.util.function.Predicate<org.bukkit.entity.Player> a = allies;
        try { return a != null && a.test(p); } catch (RuntimeException e) { return false; }
    }

    final java.util.Map<UUID, Long> nextHit = new java.util.HashMap<>(4);

    /** True (and the window is opened) when this attacker may land a melee hit at {@code tick}. */
    boolean tryHit(UUID attacker, long tick, int window) {
        Long next = nextHit.get(attacker);
        if (next != null && tick < next) return false;
        if (nextHit.size() > 16) nextHit.values().removeIf(t -> t <= tick);
        nextHit.put(attacker, tick + Math.max(0, window));
        return true;
    }

    LiveMob(LivingEntity entity, MobDef def, UUID owner, String ruleId, double max) {
        this.entity = entity;
        this.def = def;
        this.owner = owner;
        this.ruleId = ruleId;
        this.max = max;
        this.hp = max;
    }
}
