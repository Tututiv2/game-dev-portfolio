package com.thestill.fog;

import com.thestill.item.ItemTags;
import org.bukkit.inventory.ItemStack;

/**
 * The lamp itself.
 *
 * <p><b>Main hand only, and it works only while it is in it.</b> That is the whole design
 * and it is the reason the lamp is worth having rather than simply worth owning:
 *
 * <ul>
 *   <li><b>No toggle.</b> A toggled lamp needs a bit of state per item, and per-item state
 *       is a thing that can be out of step with the world — a lamp switched on in a chest,
 *       a lamp that says off and lights anyway, a player left glowing after a crash. Held
 *       is a question the server can ask the inventory afresh every second, so there is
 *       nothing to desynchronise and nothing to clean up.</li>
 *   <li><b>Not the offhand.</b> Your main hand is your pickaxe hand. Costing it means you
 *       cannot mine and see at the same time — you stop, you look, you put it away, you
 *       dig. That is the Nightside signature (<i>you can't mine and watch at once</i>)
 *       arriving in a world that was already about deciding what to give up, and it is
 *       what stops the lamp being a free upgrade everyone carries forever.</li>
 * </ul>
 *
 * <p><b>Not for sale.</b> Design law: ranks and roles are cosmetic, forever. Sight is not
 * cosmetic — this world's entire currency is what perceives you and what you can perceive —
 * so the lamp is earned in game or it does not exist. It is priced for the rig's INSTRUMENTS
 * unit (which the spec already builds as the thermal-goggles shop) and reachable with money
 * anyone can mine. {@code /still lamp give} is an admin tool for testing, not a source.
 *
 * <p>A soul lantern rather than a lantern because it reads cold, and cold is what this world
 * rewards. Note that it is a <em>placeable</em> block: {@code FogService} cancels placement
 * of a tagged lamp, because the first thing anyone does with a lantern in a dark tunnel is
 * put it on the wall, and a lamp you can lose into a wall is a lamp you will lose into a
 * wall.
 *
 * <p><b>The stack itself is built in {@code StillItems}, not here.</b> This class owns what
 * a lamp <em>is</em> — its identity and how to recognise one — and the catalogue owns what
 * every custom item looks like, so there is exactly one answer to that question and the item
 * vault in {@code /config} cannot fall behind it. Ask
 * {@code items.build(StillItems.FOG_LAMP)}, or {@code FogService.newLamp()}, which does.
 */
public final class FogLamp {

    /** The {@code kind} half of the PDC identity. Never match this item by its name. */
    public static final String KIND = "fog_lamp";

    /** The {@code id} half. Later marks (wider cone, longer reach) are new ids, not new kinds. */
    public static final String MK1 = "mk1";

    private FogLamp() {
    }

    /**
     * True if this stack is a fog lamp of any mark.
     *
     * <p>By tag, matched against the persistent data container. Shadow Prison matched items
     * by display-name regex and an admin tool deleted innocent players' paper; the display
     * name above is decoration and nothing may ever branch on it.
     */
    public static boolean is(ItemTags tags, ItemStack stack) {
        return stack != null && tags.isKind(stack, KIND);
    }
}
