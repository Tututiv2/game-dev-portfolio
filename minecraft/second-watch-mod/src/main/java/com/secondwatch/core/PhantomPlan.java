package com.secondwatch.core;

import java.util.Optional;

/**
 * One instruction to fabricate something for exactly one player.
 *
 * <p>Read the field names carefully, because they encode the architecture: this
 * is what {@code victimId} will be <em>shown</em>, and nothing here is allowed
 * to become world state. No damage, no block change, no inventory. The victim
 * can walk straight through the phantom and through anything it stands behind.
 * That is not a limitation to be fixed later - it is the property that keeps the
 * mod free of desync, and it produces the best moment in the game the first time
 * somebody walks through a door their friend can see.
 *
 * @param victimId      the only player who perceives this
 * @param wearingId     whose appearance and voice it borrows
 * @param utterance     the recorded line to replay, if any
 * @param fixedVolume   play at constant gain regardless of distance (Tell.FIXED_VOLUME)
 * @param mono          play equally in both ears (Tell.MONO)
 * @param stripRoomTone remove breath and background between words (Tell.NO_BREATH)
 * @param luring        true if it is actively walking the victim away from their partner
 * @param headingX      where it is leading them, if luring
 * @param headingZ      where it is leading them, if luring
 */
public record PhantomPlan(
        String victimId,
        String wearingId,
        Optional<Utterance> utterance,
        boolean fixedVolume,
        boolean mono,
        boolean stripRoomTone,
        boolean luring,
        double headingX,
        double headingZ) {

    public PhantomPlan {
        if (victimId == null || victimId.isBlank()) throw new IllegalArgumentException("no victim");
        if (wearingId == null || wearingId.isBlank()) throw new IllegalArgumentException("nobody to wear");
        if (victimId.equals(wearingId)) {
            // It wears the OTHER player. A phantom of yourself is a different
            // and much cheaper kind of horror, and it breaks the one rule the
            // players are relying on to survive.
            throw new IllegalArgumentException("the phantom never wears its own victim");
        }
        if (utterance != null && utterance.isPresent()
                && !utterance.get().speakerId().equals(wearingId)) {
            throw new IllegalArgumentException(
                    "phantom would speak in the wrong voice: wearing " + wearingId
                            + " but line belongs to " + utterance.get().speakerId());
        }
    }

    public boolean speaks() {
        return utterance != null && utterance.isPresent();
    }
}
