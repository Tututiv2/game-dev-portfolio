package com.thestill.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arm/confirm rules, and specifically the one Shadow Prison got wrong.
 *
 * <p>Its {@code handleConfirmedAction} built the correct key —
 * {@code admin + ":" + actionId + ":" + targetUuid} — and then compared something
 * else, so an arm belonged to the admin and the button but not the player. Arming
 * "Clear Inventory" on one player and opening another fired it immediately against
 * the second, with no confirmation. That is a two-account, eight-second bug to
 * reproduce by hand and a four-line one to assert.
 */
class ConfirmGateTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    private static final String CLEAR = "inventory.clear";
    private static final String WIPE = "state.wipe";

    @Test
    @DisplayName("the first press arms, the second fires")
    void twoPressesFire() {
        ConfirmGate gate = new ConfirmGate();
        assertEquals(ConfirmGate.Result.ARMED, gate.press(ADMIN, ALICE, CLEAR, 1_000L));
        assertEquals(ConfirmGate.Result.FIRED, gate.press(ADMIN, ALICE, CLEAR, 1_500L));
    }

    @Test
    @DisplayName("firing consumes the arm — a third press arms again rather than repeating")
    void firingConsumesTheArm() {
        ConfirmGate gate = new ConfirmGate();
        gate.press(ADMIN, ALICE, CLEAR, 0L);
        assertEquals(ConfirmGate.Result.FIRED, gate.press(ADMIN, ALICE, CLEAR, 100L));
        assertEquals(ConfirmGate.Result.ARMED, gate.press(ADMIN, ALICE, CLEAR, 200L));
    }

    @Test
    @DisplayName("THE BUG: an arm on one player cannot fire against another")
    void anArmIsBoundToItsTarget() {
        ConfirmGate gate = new ConfirmGate();
        gate.press(ADMIN, ALICE, CLEAR, 0L);
        // Switching to Bob and clicking the same button must NOT wipe Bob.
        assertEquals(ConfirmGate.Result.ARMED, gate.press(ADMIN, BOB, CLEAR, 100L),
                "an arm made against Alice fired against Bob");
        // And Bob's own second press behaves normally from there.
        assertEquals(ConfirmGate.Result.FIRED, gate.press(ADMIN, BOB, CLEAR, 200L));
    }

    @Test
    @DisplayName("an arm is bound to its button too")
    void anArmIsBoundToItsAction() {
        ConfirmGate gate = new ConfirmGate();
        gate.press(ADMIN, ALICE, CLEAR, 0L);
        assertEquals(ConfirmGate.Result.ARMED, gate.press(ADMIN, ALICE, WIPE, 100L),
                "arming Clear Inventory also armed Wipe State");
    }

    @Test
    @DisplayName("an arm lapses, and a lapsed press re-arms rather than firing")
    void armsExpire() {
        ConfirmGate gate = new ConfirmGate();
        gate.press(ADMIN, ALICE, CLEAR, 0L);
        long tooLate = ConfirmGate.WINDOW_MILLIS + 1L;
        assertEquals(ConfirmGate.Result.ARMED, gate.press(ADMIN, ALICE, CLEAR, tooLate));
        assertEquals(ConfirmGate.Result.FIRED,
                gate.press(ADMIN, ALICE, CLEAR, tooLate + 100L));
    }

    @Test
    @DisplayName("one admin's arm is not another's")
    void armsAreNotShared() {
        ConfirmGate gate = new ConfirmGate();
        UUID other = UUID.randomUUID();
        gate.press(ADMIN, ALICE, CLEAR, 0L);
        assertEquals(ConfirmGate.Result.ARMED, gate.press(other, ALICE, CLEAR, 50L));
    }

    @Test
    @DisplayName("remaining only reports the button that is actually armed")
    void remainingIsPerButton() {
        ConfirmGate gate = new ConfirmGate();
        gate.press(ADMIN, ALICE, CLEAR, 0L);
        assertTrue(gate.remaining(ADMIN, ALICE, CLEAR, 0L) > 0);
        assertEquals(0L, gate.remaining(ADMIN, ALICE, WIPE, 0L));
        assertEquals(0L, gate.remaining(ADMIN, BOB, CLEAR, 0L));
        assertEquals(0L, gate.remaining(ADMIN, ALICE, CLEAR, ConfirmGate.WINDOW_MILLIS + 1));
    }

    @Test
    @DisplayName("leaving the page disarms")
    void disarmClears() {
        ConfirmGate gate = new ConfirmGate();
        gate.press(ADMIN, ALICE, CLEAR, 0L);
        gate.disarm(ADMIN);
        assertEquals(0, gate.armedCount());
        assertEquals(ConfirmGate.Result.ARMED, gate.press(ADMIN, ALICE, CLEAR, 100L));
    }
}
