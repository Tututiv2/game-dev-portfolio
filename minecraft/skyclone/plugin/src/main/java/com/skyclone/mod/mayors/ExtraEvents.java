package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.time.SkyDate;

/**
 * Drives the perk-made festivals that are not the Spooky Festival (that one is SpookyFestival's):
 * <ul>
 *   <li><b>Mining Fiesta</b> ({@code mining_fiesta}): on for the WHOLE term while Cole's {@code cole.mining_fiesta}
 *       is active, which is true when Cole is mayor with it on his ballot or minister with it as his minister perk
 *       (reference since Feb 2026; SkyblockMoney/work/mining.md section 4). Also on during Foxy's mining Extra Event.</li>
 *   <li><b>Extra Fishing Festival</b> ({@code fishing_festival_extra}): Foxy's Extra Event, fishing variant, on the
 *       {@code mayors.extra-event.*} window (reference Y518: Late Summer 22-24).</li>
 * </ul>
 * The effects belong to mining and fishing; this only says WHEN.
 */
final class ExtraEvents {

    private final MayorsModule mod;

    ExtraEvents(MayorsModule mod) { this.mod = mod; }

    /** Is Foxy's Extra Event of {@code variant} running on this date? */
    boolean extraWindow(String variant, SkyDate d) {
        ElectionService es = mod.election();
        if (!es.perkActive("foxy.extra_event") || !variant.equals(es.extraEventVariant())) return false;
        MayorDials dl = mod.dials();
        return ElectionMath.inWindow(d.month(), d.day(), dl.extraEventMonth.get(), dl.extraEventDay.get(), dl.extraEventDays.get());
    }

    boolean fiestaWanted(SkyDate d) {
        boolean term = mod.dials().fiestaWholeTerm.get() && mod.election().perkActive("cole.mining_fiesta");
        return term || extraWindow("mining", d);
    }

    /** Every second. */
    void tick() {
        SkyDate d = Sky.calendar().now();
        Cal.drive(Cal.FIESTA, fiestaWanted(d));
        Cal.drive(Cal.FISHING_EXTRA, extraWindow("fishing", d));
    }
}
