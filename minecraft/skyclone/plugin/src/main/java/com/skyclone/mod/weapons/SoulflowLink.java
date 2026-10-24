package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Soulstream (reference: Soulflow) costs of our abilities, read through {@code Features.Soulflow} (wave 5).
 *
 * <p>Who charges what: the soulflow module's {@code AbilityGate} owns the cost table (Phasewarp 1, Soul Howl 1/1/2,
 * Judgement Beam 1, Gravity Well 10, Cell Sync 2). It refuses a click it can not be paid for and takes the Soulstream on
 * {@code SkyEvents.AbilityUsed}, which core fires only after our handler returned true. Phasewarp and Soul Howl
 * therefore need nothing here. Judgement Beam is the exception: the same left-click is either the beam or a normal
 * shortbow shot, so the gate can not refuse it (that would eat the shot too) and charges it "soft". This class lets
 * the handler decide BEFORE firing: with too little Soulstream the click is a normal shot and nothing is charged.
 *
 * <p>Without the soulflow module, or with its {@code soulflow.enabled} / {@code soulflow.costs-enabled} dials off,
 * every check passes (the abilities are free, as before wave 5).
 */
final class SoulflowLink {

    private SoulflowLink() {}

    private static final Map<UUID, Long> lastMsg = new HashMap<>();

    /** Can {@code p} pay {@code base} Soulstream (scaled by soulflow's cost dial) right now? */
    static boolean canPay(Player p, long base) {
        if (p == null || base <= 0 || !WeaponDials.SOULFLOW_BEAM.get() || !Sky.has(Features.Soulflow.class)) return true;
        if (!soulflowCostsOn()) return true;
        long need = need(base);
        try {
            return Sky.service(Features.Soulflow.class).soulflow(p) >= need;
        } catch (RuntimeException e) {
            return true;   // never let a soulflow fault break the bow
        }
    }

    /** Soulstream the player holds (0 without the module). */
    static long balance(Player p) {
        if (p == null || !Sky.has(Features.Soulflow.class)) return 0;
        try { return Sky.service(Features.Soulflow.class).soulflow(p); } catch (RuntimeException e) { return 0; }
    }

    /**
     * The soulflow module's own dials, read by key at use time (they exist once that module enabled, which
     * {@code Sky.has} has already confirmed; asking again returns the same dial).
     */
    private static boolean soulflowCostsOn() {
        try {
            return Sky.dials().flag("soulflow.enabled", true, "Soulstream system on (soulflow module)").get()
                    && Sky.dials().flag("soulflow.costs-enabled", true, "Soulstream ability costs on (soulflow module)").get();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Soulflow's rule: base x {@code soulflow.cost-scale}, rounded up, never below 1 for a positive base. */
    static long need(long base) {
        double scale;
        try { scale = Sky.dials().number("soulflow.cost-scale", 1.0, "Soulstream cost multiplier (soulflow module)").get(); }
        catch (RuntimeException e) { scale = 1.0; }
        return scaled(base, scale);
    }

    static long scaled(long base, double scale) {
        if (base <= 0) return 0;
        return Math.max(1, (long) Math.ceil(base * Math.max(0, scale)));
    }

    /** "Not enough Soulstream" at most once per 3 s in chat; always on the action bar. */
    static void tellShort(Player p, String ability) {
        Sky.vitals().flash(p, "&c&lNOT ENOUGH SOULSTREAM", 20);
        long now = System.currentTimeMillis();
        Long last = lastMsg.get(p.getUniqueId());
        if (last != null && now - last < 3000) return;
        lastMsg.put(p.getUniqueId(), now);
        Msg.send(p, "&cYou don't have enough Soulstream for " + ability + "! &7(fired a normal shot)");
    }

    static void forget(UUID id) { lastMsg.remove(id); }
}
