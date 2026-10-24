package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Temporary stat penalties boss mechanics put on players (Pestilence armour shred, Paralytic Venom, Heartbreak
 * vitality loss, Blood Gargoyle health sap). Applied through one StatProvider so lore and stats stay honest.
 */
final class Debuffs {

    static final class State {
        long shredUntil;
        double shredFraction = 0.25;
        long venomUntil;
        double venomVitality;
        double venomSpeed;
        boolean noAbsorb;
        long vitalityUntil;
        double vitalityFlat;
        long sapUntil;
        double sapPercent;
    }

    private final Map<UUID, State> states = new HashMap<>();

    private State state(Player p) { return states.computeIfAbsent(p.getUniqueId(), k -> new State()); }

    void shred(Player p, double fraction, long millis) {
        State s = state(p);
        boolean was = s.shredUntil > System.currentTimeMillis();
        s.shredFraction = fraction;
        s.shredUntil = System.currentTimeMillis() + millis;
        if (!was) Sky.stats().invalidate(p);
    }

    void venom(Player p, double vitalityPct, double speedPct, boolean noAbsorb, long millis) {
        State s = state(p);
        s.venomVitality = vitalityPct;
        s.venomSpeed = speedPct;
        s.noAbsorb = noAbsorb;
        s.venomUntil = System.currentTimeMillis() + millis;
        if (noAbsorb && Sky.vitals().absorption(p) > 0) Sky.vitals().addAbsorption(p, -Sky.vitals().absorption(p));
        Sky.stats().invalidate(p);
    }

    void vitality(Player p, double flat, long millis) {
        State s = state(p);
        s.vitalityFlat = flat;
        s.vitalityUntil = System.currentTimeMillis() + millis;
        Sky.stats().invalidate(p);
    }

    void sap(Player p, double percent, long millis) {
        State s = state(p);
        if (Math.abs(s.sapPercent - percent) > 0.01 || s.sapUntil < System.currentTimeMillis()) Sky.stats().invalidate(p);
        s.sapPercent = percent;
        s.sapUntil = System.currentTimeMillis() + millis;
    }

    boolean absorbBlocked(Player p) {
        State s = states.get(p.getUniqueId());
        return s != null && s.noAbsorb && s.venomUntil > System.currentTimeMillis();
    }

    void clear(Player p) {
        if (states.remove(p.getUniqueId()) != null) Sky.stats().invalidate(p);
    }

    /** StatProvider "slayer_debuffs". */
    void contribute(Player p, StatMap into) {
        State s = states.get(p.getUniqueId());
        if (s == null) return;
        long now = System.currentTimeMillis();
        boolean any = false;
        if (s.shredUntil > now) { into.multiply(Stat.DEFENSE, 1 - s.shredFraction); any = true; }
        if (s.venomUntil > now) {
            if (s.venomVitality > 0) into.addPercent(Stat.VITALITY, -s.venomVitality);
            if (s.venomSpeed > 0) into.addPercent(Stat.SPEED, -s.venomSpeed);
            any = true;
        }
        if (s.vitalityUntil > now) { into.add(Stat.VITALITY, -s.vitalityFlat); any = true; }
        if (s.sapUntil > now && s.sapPercent > 0) { into.addPercent(Stat.HEALTH, -s.sapPercent); any = true; }
        if (!any) states.remove(p.getUniqueId());
    }
}
