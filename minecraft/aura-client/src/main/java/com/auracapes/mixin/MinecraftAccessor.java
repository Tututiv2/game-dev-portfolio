package com.auracapes.mixin;

import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.yggdrasil.ProfileResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * The two fields an account switch has to replace.
 *
 * <p>{@code user} is what the game logs in as - the login packet, "is this
 * me", the session name - and it is {@code final}, which is why this needs
 * {@code @Mutable} rather than a plain setter.
 *
 * <p>{@code profileFuture} is the trap. {@link Minecraft#getGameProfile()}
 * prefers the profile fetched for the LAUNCH account over {@code user}, and
 * singleplayer builds the host player from {@code getGameProfile()}. So
 * swapping only {@code user} would log you into servers as the new name and
 * into your own singleplayer world as the old one.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Mutable
    @Accessor("user")
    void auracapes$setUser(User user);

    @Accessor("profileFuture")
    CompletableFuture<ProfileResult> auracapes$getProfileFuture();

    @Mutable
    @Accessor("profileFuture")
    void auracapes$setProfileFuture(CompletableFuture<ProfileResult> future);
}
