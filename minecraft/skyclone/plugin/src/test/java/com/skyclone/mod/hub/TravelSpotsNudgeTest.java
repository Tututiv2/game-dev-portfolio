package com.skyclone.mod.hub;

import com.skyclone.api.world.gen.Box;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A refused walk-in gate must push the player OUT of a one-block-thick portal sheet, never along it.
 * The Rift arch's gate is 1 (x) by 5 (z): "away from the centre" pointed along the sheet for anyone who
 * walked in two columns off-centre, so they slid sideways inside the portal and it fired again.
 */
class TravelSpotsNudgeTest {

    private static Location at(double x, double z, float yaw) { return new Location(null, x, 70, z, yaw, 0f); }

    @Test
    void thinGateAlongZPushesAcrossX() {
        Box sheet = Box.of(-112, 71, 113, -112, 72, 117);          // x thin, z 5 wide
        // walked in from the east (+x), two columns north of the middle, facing west (yaw 90)
        Vector v = TravelSpots.nudge(sheet, at(-111.2, 113.5, 90f));
        assertEquals(0, v.getZ(), 1e-9, "no push along the sheet");
        assertTrue(v.getX() > 0.99, "back out to the east, the side they came from: " + v);
        // and from the west side
        v = TravelSpots.nudge(sheet, at(-111.8, 117.5, -90f));
        assertTrue(v.getX() < -0.99, "back out to the west: " + v);
        // dead centre in x: the way they were facing decides (facing west = pushed east)
        v = TravelSpots.nudge(sheet, at(-111.5, 115.5, 90f));
        assertTrue(v.getX() > 0.99, "centre, facing west -> east: " + v);
    }

    @Test
    void thinGateAlongXPushesAcrossZ() {
        Box sheet = Box.of(10, 71, 4, 14, 72, 4);
        Vector v = TravelSpots.nudge(sheet, at(10.5, 4.9, 180f));
        assertEquals(0, v.getX(), 1e-9);
        assertTrue(v.getZ() > 0.99, "" + v);
    }

    /**
     * Wave-3 playtest: refused at the Rift arch, a player holding forward walked through the sheet during the
     * 3-second cooldown. A refused move into a gate from outside it is undone on every move, not once.
     */
    @Test
    void aRefusedGateHoldsYouBackAPadOnlyNudges() {
        Box sheet = Box.of(-112, 71, 113, -112, 72, 117);
        TravelSpots.Spot gate = new TravelSpots.Spot("gate.rift", "rift", false, sheet);
        TravelSpots.Spot pad = new TravelSpots.Spot("pad.barn", "barn", true, Box.of(0, 70, 0, 4, 70, 4));
        Location outside = at(-110.7, 115.5, 90f), inSheet = at(-111.5, 115.5, 90f);
        outside.setY(71); inSheet.setY(71);
        assertFalse(TravelSpots.inside(sheet, outside));
        assertTrue(TravelSpots.inside(sheet, inSheet));
        assertTrue(TravelSpots.holdsBack(gate, outside), "walking in from outside is undone");
        assertFalse(TravelSpots.holdsBack(gate, inSheet), "already inside (teleported there): nudged out instead");
        Location onPadEdge = new Location(null, -0.5, 71, 2.5);
        assertFalse(TravelSpots.holdsBack(pad, onPadEdge), "pads keep the nudge");
    }

    @Test
    void widePadPushesAwayFromItsCentre() {
        Box pad = Box.of(0, 70, 0, 4, 70, 4);                       // 5 x 5, centre (2.5, 2.5)
        Vector v = TravelSpots.nudge(pad, at(4.5, 2.5, 0f));
        assertEquals(1, v.length(), 1e-9);
        assertTrue(v.getX() > 0.99, "" + v);
    }
}
