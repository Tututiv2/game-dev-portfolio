package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Action-bar segments: powder gains, drill fuel, heat and cold. */
final class Feedback {

    private record Gain(String type, long amount, long until) {}

    private final Map<UUID, Gain> gains = new ConcurrentHashMap<>();
    private final MiningModule m;

    Feedback(MiningModule m) {
        this.m = m;
        Sky.vitals().actionBarSegment("mining_powder", 500, this::powderSegment);
        Sky.vitals().actionBarSegment("mining_fuel", 510, p -> m.drills.fuelSegment(p));
        Sky.vitals().actionBarSegment("mining_temp", 520, this::tempSegment);
    }

    void powder(Player p, String type, long n) {
        long now = System.currentTimeMillis();
        gains.compute(p.getUniqueId(), (k, g) -> {
            long amt = (g != null && g.type.equals(type) && g.until > now) ? g.amount + n : n;
            return new Gain(type, amt, now + 2000);
        });
    }

    private String powderSegment(Player p) {
        Gain g = gains.get(p.getUniqueId());
        if (g == null || g.until < System.currentTimeMillis()) return null;
        return MiningUtil.powderColor(g.type) + "+" + Text.commas(g.amount) + " " + MiningUtil.DOT + " " + MiningUtil.powderName(g.type);
    }

    private String tempSegment(Player p) {
        int heat = m.hollows.heat(p);
        if (heat > 0) return (heat >= 75 ? "&c" : "&6") + "♨ Heat " + heat;
        int cold = m.tunnels.cold(p);
        if (cold > 0) return (cold >= 75 ? "&3" : "&b") + "❄ Cold -" + cold;
        return null;
    }

    void forget(UUID u) { gains.remove(u); }
}
