package com.auraqol.feature;

import com.auraqol.compat.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.phys.Vec3;

/**
 * Renders your own nametag above your own head while the camera is detached (F5).
 *
 * <p><b>Why vanilla never shows it, exactly.</b> It is one line, and it is not in
 * the player renderer at all - it is in
 * {@code LivingEntityRenderer.shouldShowName(LivingEntity, double)}, which ends
 * with {@code entity != minecraft.getCameraEntity()} as one of four conditions.
 * That method feeds {@code EntityRenderer.extractNameTags}, which is what fills
 * in {@code EntityRenderState.nameTag} and {@code nameTagAttachment}; when it
 * says no, {@code nameTag} is set to null and {@code submitNameDisplay} - whose
 * only gate is {@code nameTag != null} - draws nothing.
 *
 * <p><b>Why we write the render state instead of unlocking that check.</b>
 * Cancelling {@code shouldShowName} to return true was the obvious move and is
 * the worse one for two reasons. It is on {@code LivingEntityRenderer}, so it is
 * shared by every mob in the game and a mistake there is a world full of
 * floating names. And {@code AvatarRenderer} overrides it with a SECOND gate on
 * top ({@code entity.shouldShowName() || (hasCustomName && entity == crosshairPick)}),
 * so forcing the super to true still leaves the decision somewhere else and the
 * feature depends on a vanilla detail we would rather not be married to.
 *
 * <p>Writing {@code nameTag} at the TAIL of {@code AvatarRenderer.extractRenderState}
 * instead means vanilla has already finished deciding and has already nulled the
 * field, so there is nothing to fight: we set the two fields the draw call reads
 * and everything downstream - the sneak fade ({@code isDiscrete}), the light
 * level, the extra-ears offset in {@code AvatarRenderer.submitNameDisplay} - keeps
 * working untouched. It also can only ever affect avatars.
 *
 * <p>{@code nameTagAttachment} is computed exactly the way
 * {@code EntityRenderer.extractNameTags} computes it, including the
 * partial-tick yaw, because the attachment point is rotated by the entity's
 * yRot and using the un-interpolated one makes the tag judder as you turn.
 *
 * <p>The gate is {@code entity == minecraft.getCameraEntity()} rather than
 * {@code == minecraft.player} on purpose: it is the same test vanilla uses, so
 * spectating another player in third person shows that player's name, which is
 * the consistent answer rather than a special case.
 */
public final class SelfNametag {

    /**
     * Set by {@link #register()}. The mixin is applied whether or not the
     * feature started, so the flag is what actually turns it on - a register()
     * that throws must leave the game rendering exactly like vanilla.
     */
    private static volatile boolean enabled;

    private SelfNametag() {
    }

    public static void register() {
        enabled = true;
    }

    /**
     * Called from {@code SelfNametagMixin} at the tail of every avatar's
     * render-state extraction - once per visible player per frame, so it does
     * no allocation beyond the one Vec3 vanilla would have allocated anyway.
     */
    public static void apply(Avatar entity, AvatarRenderState state, float partialTick) {
        if (!enabled || !HudConfig.get().selfNametagEnabled) {
            return;
        }
        // Something already wants a name in that spot - a custom name, a team
        // display - so leave it alone. For the camera entity vanilla always
        // leaves this null, which is why the check is free.
        if (state.nameTag != null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null || minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }
        if (entity != minecraft.getCameraEntity()) {
            return;
        }
        // F1 hides every other nametag in the world; ours has no business
        // surviving it.
        if (Mc.hudHidden(minecraft)) {
            return;
        }
        Vec3 attachment = entity.getAttachments()
                .getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partialTick));
        if (attachment == null) {
            // submitNameTag dereferences this, so a null attachment is not a
            // nametag drawn badly - it is a crash. Nothing to draw is correct.
            return;
        }
        state.nameTag = entity.getDisplayName();
        state.nameTagAttachment = attachment;
    }
}
