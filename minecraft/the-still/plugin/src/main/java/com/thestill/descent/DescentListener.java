package com.thestill.descent;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.rank.RankService;
import com.thestill.region.Region;
import com.thestill.region.RegionService;
import com.thestill.region.RegionType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * The drop off the rig.
 *
 * <p>The first thing the game asks a player to do is step into a hole and fall
 * 270 blocks through fog onto the Landing. Vanilla answers that with instant
 * death, which would make the opening sixty seconds unplayable and the design
 * note that calls D1 "soft ground" a lie.
 *
 * <p>So the Landing is soft, literally: fall damage is cancelled inside D1 and
 * nowhere else. Deliberately not a blanket rule for the dimension — falling is a
 * real threat everywhere below, and it is one of the things the descent is
 * <em>about</em>. It is only the first drop that the game hands you without
 * asking, so it is only the first drop the game has to make survivable.
 */
public final class DescentListener implements Listener {

    private final RegionService regions;
    private final Dial<Boolean> softLanding;

    public DescentListener(DialRegistry dials, RegionService regions) {
        this.regions = regions;
        this.softLanding = dials.boolDial("descent.soft-landing", true)
                .category("Descent").label("The Landing is soft")
                .describe("Cancel fall damage inside D1. The rig's drop hole opens onto the "
                        + "Landing 270 blocks below and a new player takes that fall before they "
                        + "have been given anything, so without this the opening move of the game "
                        + "kills them. Only D1 — every depth under it falls normally.")
                .register();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL
                || !(event.getEntity() instanceof Player player)
                || !softLanding.asBoolean()) {
            return;
        }
        if (isLanding(regions.regionFor(player))) {
            event.setCancelled(true);
        }
    }

    /** True if this region, or anything it nests inside, is D1. */
    private boolean isLanding(Region region) {
        if (region == null) {
            return false;
        }
        for (Region link : regions.index().chainOf(region)) {
            if (link.type() == RegionType.DEPTH
                    && link.state().get(RankService.DEPTH_NUMBER) == 1) {
                return true;
            }
        }
        return false;
    }
}
