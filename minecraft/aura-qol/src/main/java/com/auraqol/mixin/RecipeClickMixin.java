package com.auraqol.mixin;

import com.auraqol.feature.RecipeBazaar;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

/**
 * Recipe -> Bazaar: a right-click on an ingredient in a SkyBlock recipe menu
 * is cancelled here, before vanilla turns it into a container-click packet,
 * and replaced by one typed {@code /bz} command (see {@code RecipeBazaar}).
 *
 * <p>{@code slotClicked(Slot, int, int, ContainerInput)} has the same
 * signature in 26.1.2 and 26.2 (checked with javap on both merged jars), so
 * one mixin serves both builds. It is the single funnel every mouse and
 * hotbar-key slot interaction goes through, which is why the hook is here
 * rather than on {@code mouseClicked}: there the slot and the shift state are
 * not yet resolved.
 *
 * <p>{@code require = 0}: unlike the other mixins this one sits on a class
 * every inventory and chest opens. If a future Minecraft renamed the method,
 * the mod's other features must keep working and opening a chest must not
 * crash - losing this one feature is the right failure.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class RecipeClickMixin {

    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void auraqol$recipeBuy(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        if (RecipeBazaar.onSlotClick((AbstractContainerScreen<?>) (Object) this, slot, slotId, button, input)) {
            ci.cancel();
        }
    }
}
