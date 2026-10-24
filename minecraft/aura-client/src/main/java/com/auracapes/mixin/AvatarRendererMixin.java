package com.auracapes.mixin;

import com.auracapes.CapeRegistry;
import com.auracapes.StreamerName;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;

/**
 * Swaps in our cape while the renderer is collecting what to draw.
 *
 * <p>The mod's only mixin. It runs at the TAIL of {@code extractRenderState},
 * which is the frame's own "gather everything about this player" pass - so by
 * the time we get here vanilla has already filled in the real skin, and all we
 * do is hand back a copy with a different cape. Nothing is fighting anything.
 *
 * <p><b>Why here and not in the cape layer.</b> {@code CapeLayer.submit} was
 * the obvious target and is the wrong one: it reads three things off the render
 * state ({@code isInvisible}, {@code showCape}, {@code skin.cape() != null})
 * and bails before drawing if any fail. Injecting into the drawing code means
 * re-implementing that gate; injecting into the state it reads means vanilla
 * keeps making all three decisions and simply sees a cape where there was none.
 * The invisibility check, the elytra swap and the flap physics all keep working
 * because we never touched them.
 *
 * <p>{@code showCape} is forced true for one specific reason: vanilla derives
 * it from the player's own skin-customisation toggle, which is off by default
 * for anybody who has never owned a cape. Leaving it alone would mean the mod
 * silently does nothing for exactly the people who most want it.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;"
            + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void auracapes$applyCape(Avatar entity, AvatarRenderState state,
                                     float partialTick, CallbackInfo ci) {
        auracapes$applyStreamerName(entity, state, partialTick);

        Identifier texture = CapeRegistry.capeFor(auracapes$wearer(entity));
        if (texture == null) {
            return;
        }
        PlayerSkin skin = state.skin;
        if (skin == null) {
            return;
        }
        // ResourceTexture takes (id, texturePath). The cape layer calls
        // texturePath() and hands the result straight to a render type, so it
        // must be the full resource path - assets/<ns>/textures/cape/x.png -
        // not the short id form. Passing the same Identifier for both is
        // correct here because the path IS the id we built.
        ClientAsset.Texture cape = new ClientAsset.ResourceTexture(texture, texture);
        state.skin = new PlayerSkin(skin.body(), cape, skin.elytra(),
                skin.model(), skin.secure());
        state.showCape = true;
    }

    /**
     * Whose cape this body wears. A player is themselves; a Mannequin is whoever its profile names
     * (Shadow Prison's Hall of Fame statues, 2026-10-15: "my character doesn't have the minecon cape").
     * A Mannequin's own entity UUID is random, so matching on it never found anyone.
     */
    private static java.util.UUID auracapes$wearer(Avatar entity) {
        if (entity instanceof Mannequin m) {
            java.util.UUID id = m.getProfile().partialProfile().id();
            if (id != null && !id.equals(Util.NIL_UUID)) {
                return id;
            }
        }
        return entity.getUUID();
    }

    /**
     * Puts the streamer alias over your own head, for your eyes only.
     *
     * <p><b>Vanilla never draws your own nameplate</b>, not even in third
     * person - {@code shouldShowName} excludes the camera entity, so the
     * obvious implementation (rename the tag) would have shown nothing at all
     * and looked like a broken feature. This writes the tag that vanilla
     * declined to write, which is what makes F5 a preview.
     *
     * <p>{@code nameTagAttachment} has to be set too and that is the whole
     * trap: {@code extractNameTags} assigns the two fields together inside the
     * branch it skipped, so setting only {@code nameTag} hands the renderer a
     * name with no position. It is read first and bailed on if absent, so a
     * missing attachment means no name rather than a crash.
     */
    private void auracapes$applyStreamerName(Avatar entity, AvatarRenderState state,
                                             float partialTick) {
        if (!StreamerName.enabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !entity.getUUID().equals(mc.player.getUUID())) {
            return;
        }
        Vec3 attachment = entity.getAttachments().getNullable(
                EntityAttachment.NAME_TAG, 0, entity.getYRot(partialTick));
        if (attachment == null) {
            return;
        }
        // Swap the name INSIDE the tag rather than replacing the tag: on Hypixel
        // the display name is "[140] playerone" in grey via the team prefix, and a
        // bare literal threw away the level and the colour. AuraQoL's own-name
        // tag may already be here (same TAIL, either order), so start from it.
        Component base = state.nameTag != null ? state.nameTag : entity.getDisplayName();
        Component swapped = StreamerName.swap(base);
        state.nameTag = swapped.getString().contains(StreamerName.alias())
                ? swapped
                : Component.literal(StreamerName.alias());  // name not found in the tag: old behaviour
        state.nameTagAttachment = attachment;
    }
}
