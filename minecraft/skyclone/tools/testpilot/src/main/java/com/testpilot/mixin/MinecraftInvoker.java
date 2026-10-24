package com.testpilot.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The client's own left-click, right-click and crosshair-pick code paths. */
@Mixin(Minecraft.class)
public interface MinecraftInvoker {

    @Invoker("startAttack")
    boolean testpilot$startAttack();

    @Invoker("startUseItem")
    void testpilot$startUseItem();

    @Invoker("pick")
    void testpilot$pick(float partialTicks);
}
