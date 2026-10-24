package com.auraqol.mixin;

import com.auraqol.feature.SelfNametag;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;

/**
 * Puts your own name back into your own render state.
 *
 * <p>TAIL of {@code extractRenderState} is the frame's "gather everything about
 * this player" pass, and by the time we arrive vanilla has already run
 * {@code extractNameTags} and decided - for the camera entity - that there is no
 * name. Filling {@code nameTag} and {@code nameTagAttachment} in afterwards is
 * the whole feature; the drawing code a few methods later gates on nothing else.
 *
 * <p>The descriptor is spelled out rather than left as a bare method name
 * because {@code AvatarRenderer} carries THREE {@code extractRenderState}
 * overloads - the real one plus two bridge methods erased to
 * {@code LivingEntity} and {@code Entity} - and a bare name would be ambiguous.
 * The generic parameter {@code AvatarlikeEntity} erases to
 * {@code net.minecraft.world.entity.Avatar}, which is what the descriptor and
 * the handler signature both have to say.
 *
 * <p>All the reasoning about WHY this hook and not
 * {@code LivingEntityRenderer.shouldShowName} lives on {@link SelfNametag}.
 */
@Mixin(AvatarRenderer.class)
public abstract class SelfNametagMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;"
            + "Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL"))
    private void auraqol$showOwnName(Avatar entity, AvatarRenderState state,
                                     float partialTick, CallbackInfo ci) {
        SelfNametag.apply(entity, state, partialTick);
    }
}
