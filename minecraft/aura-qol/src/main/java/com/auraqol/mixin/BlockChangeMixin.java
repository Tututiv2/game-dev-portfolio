package com.auraqol.mixin;

import com.auraqol.feature.BlocksPerSecond;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sees every block the SERVER sets in the client world, so blocks/s can count
 * Mining Spread - blocks broken next to the one you mined, which the
 * crosshair never touches.
 *
 * <p>{@code ClientLevel.setServerVerifiedBlockState} is the single funnel for
 * both {@code ClientboundBlockUpdatePacket} and the per-section
 * {@code ClientboundSectionBlocksUpdatePacket} (verified in the 26.1.2 and
 * 26.2 jars), and it runs on the client thread. Injecting at HEAD means the
 * world still holds the OLD state, so old -> new is visible without keeping a
 * copy. Chunk loads do not pass through here, so a chunk arriving full of
 * air never looks like a break; a block the client already broke itself
 * (Garden crops: client prediction) reads as air -> air here and is ignored.
 */
@Mixin(ClientLevel.class)
public abstract class BlockChangeMixin {

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
    private void auraqol$serverBlock(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        BlocksPerSecond.onServerBlock((ClientLevel) (Object) this, pos, state);
    }
}
