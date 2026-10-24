package com.skyclone.mod.combatzones;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nest dragon's flight model (playtest-endgame: the vanilla AI froze 110 blocks from the nest).
 * Flies the shipping model over the real nest numbers: podium top (52.5, 63, -42.5), spawn anchor
 * 19 above it (EndGenerator {@code boss.dragon} = floorY + 22).
 */
class DragonFlightTest {

    static final double CX = 52.5, CY = 63, CZ = -42.5;

    static DragonFlight fresh(double speedMult, long seed) {
        return new DragonFlight(CX, CY, CZ, 52.5, 82, -42.5, speedMult, new Random(seed));
    }

    @Test
    void neverStrandedFarFromTheNestAndNeverStill() {
        for (long seed = 1; seed <= 20; seed++) {
            for (double sm : new double[]{0.8, 1.0, 2.25}) {
                DragonFlight f = fresh(sm, seed);
                double lx = f.x, ly = f.y, lz = f.z;
                int still = 0;
                for (int t = 0; t < 20 * 60 * 5; t++) {           // five minutes
                    f.tick();
                    assertTrue(f.horizontalFromCentre() <= DragonFlight.ORBIT_RADIUS + 8,
                            "seed " + seed + " x" + sm + " strayed " + f.horizontalFromCentre() + " at tick " + t);
                    assertTrue(f.y >= CY + DragonFlight.FLOOR - 1e-9 && f.y <= CY + DragonFlight.ORBIT_ALT + 6, "altitude " + (f.y - CY));
                    double moved = Math.sqrt((f.x - lx) * (f.x - lx) + (f.y - ly) * (f.y - ly) + (f.z - lz) * (f.z - lz));
                    lx = f.x; ly = f.y; lz = f.z;
                    // the frozen dragon of the playtest: outside a perch it must always be flying
                    if (f.mode() != DragonFlight.Mode.PERCH && moved < 0.05) still++; else still = 0;
                    assertTrue(still < 20, "seed " + seed + " x" + sm + " hung still for a second in " + f.mode() + " at tick " + t);
                }
            }
        }
    }

    @Test
    void comesDownIntoMeleeReachOfTheDaisEveryCycle() {
        for (long seed = 1; seed <= 20; seed++) {
            DragonFlight f = fresh(1.0, seed);
            int perchTicksInReach = 0, perches = 0;
            DragonFlight.Mode prev = f.mode();
            for (int t = 0; t < 20 * 60 * 3; t++) {               // three minutes
                f.tick();
                if (f.mode() == DragonFlight.Mode.PERCH && prev != DragonFlight.Mode.PERCH) perches++;
                prev = f.mode();
                // a player on the dais (y 62) reaching ~4 blocks: body bottom within reach of the podium
                if (f.mode() == DragonFlight.Mode.PERCH && Math.hypot(f.x - CX, f.z - CZ) < 3 && f.y - CY < 4) perchTicksInReach++;
            }
            assertTrue(perches >= 5, "seed " + seed + ": only " + perches + " perches in three minutes");
            assertTrue(perchTicksInReach >= 5 * 100, "seed " + seed + ": in reach only " + perchTicksInReach + " ticks");
        }
    }

    @Test
    void staysAboveTheCrystalTowersWhileCrossingTheirRing() {
        for (long seed = 1; seed <= 20; seed++) {
            DragonFlight f = fresh(1.0, seed);
            for (int t = 0; t < 20 * 60 * 3; t++) {
                f.tick();
                double h = f.horizontalFromCentre();
                // tower centres at radius 26, 2.6 thick: the solid band is 23.4..28.6, tops <= +29 (+4 iron bars)
                if (h > 23 && h < 29) assertTrue(f.y - CY >= 29, "seed " + seed + " clipped a tower top at +" + (f.y - CY) + " in " + f.mode());
            }
        }
    }

    @Test
    void rushCatchesARunningPlayerAndEndsTheCharge() {
        DragonFlight f = fresh(1.0, 7);
        for (int t = 0; t < 200; t++) f.tick();                    // up on the orbit
        double px = CX + 20, py = CY - 1, pz = CZ + 10;
        f.charge(px, py, pz);
        int t = 0;
        while (!f.inContact() && t < DragonFlight.CHARGE_MAX) {
            px += 0.28;                                              // sprinting away, 5.6 blocks/s
            f.retarget(px, py, pz);
            f.tick();
            t++;
        }
        assertTrue(f.inContact(), "the charge never reached a sprinting player");
        f.endCharge();
        assertEquals(DragonFlight.Mode.CIRCLE, f.mode());
    }

    @Test
    void aMissedChargeGivesUp() {
        DragonFlight f = fresh(1.0, 3);
        f.charge(CX + 500, CY, CZ);                                  // far outside: leashed to LEASH
        for (int t = 0; t <= DragonFlight.CHARGE_MAX; t++) f.tick();
        assertEquals(DragonFlight.Mode.CIRCLE, f.mode());
        assertTrue(f.horizontalFromCentre() <= DragonFlight.LEASH + 2);
    }

    @Test
    void variantSpeedMatters() {
        DragonFlight young = fresh(2.25, 1), old = fresh(0.8, 1);
        double ly = 0, lo = 0;
        double[] a = {young.x, young.y, young.z}, b = {old.x, old.y, old.z};
        for (int t = 0; t < 100; t++) {
            young.tick(); old.tick();
            ly += Math.sqrt(Math.pow(young.x - a[0], 2) + Math.pow(young.y - a[1], 2) + Math.pow(young.z - a[2], 2));
            lo += Math.sqrt(Math.pow(old.x - b[0], 2) + Math.pow(old.y - b[1], 2) + Math.pow(old.z - b[2], 2));
            a = new double[]{young.x, young.y, young.z}; b = new double[]{old.x, old.y, old.z};
        }
        assertTrue(ly > lo * 1.8, "young " + ly + " vs old " + lo);
    }

    @Test
    void resyncContinuesFromTheEntity() {
        DragonFlight f = fresh(1.0, 2);
        f.resync(-48, 104, -12);                                     // where the vanilla AI parked it
        for (int t = 0; t < 20 * 15; t++) f.tick();
        assertTrue(f.horizontalFromCentre() <= DragonFlight.ORBIT_RADIUS + 8, "still " + f.horizontalFromCentre() + " out after 15 s");
    }

    @Test
    void dragonYawFacesTheWayItFlies() {
        // vanilla puts the head at (sin yaw, -cos yaw)
        for (double[] v : new double[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {0.6, -0.8}}) {
            double r = Math.toRadians(DragonFlight.dragonYaw(v[0], v[1]));
            assertEquals(v[0], Math.sin(r), 1e-6);
            assertEquals(v[1], -Math.cos(r), 1e-6);
        }
    }
}
