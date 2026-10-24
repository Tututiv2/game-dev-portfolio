package com.secondwatch.game;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A copy of somebody, shown to exactly one player.
 *
 * <p><b>It is packets, not an entity.</b> The {@link Mannequin} held here is
 * built but never added to the world - it exists only so the vanilla packet
 * constructors have something to read a position and an id out of. Nothing on
 * the server knows this thing is there. It cannot collide, cannot be hit, cannot
 * be pushed, cannot appear in {@code /kill @e}, and the other player cannot see
 * it under any circumstances, because the only copy of it that has ever existed
 * is a stream of bytes sent down one connection.
 *
 * <p>That is the RENDER layer rule from the spec made physical, and it buys the
 * best moment in the mod for free: you can walk straight through the phantom. A
 * player who does that, in front of a friend who sees nothing there, has just
 * lost the ability to trust their own eyes - and no world state was harmed.
 *
 * <p>26.2 makes this unusually clean by shipping {@code Mannequin}, an entity
 * that renders as a player and carries a {@code ResolvableProfile}. Give it the
 * partner's profile and it arrives wearing their skin and their name.
 */
public final class PhantomPuppet {

    private final ServerPlayer viewer;
    private final Mannequin body;

    private double x;
    private double y;
    private double z;
    private float yaw;

    private PhantomPuppet(ServerPlayer viewer, Mannequin body) {
        this.viewer = viewer;
        this.body = body;
    }

    /**
     * Show {@code viewer} a copy of {@code wearing} standing at the given spot.
     */
    public static PhantomPuppet spawn(ServerPlayer viewer, GameProfile wearing,
                                      double x, double y, double z, float yaw) {
        ServerLevel level = viewer.level();
        Mannequin body = Mannequin.create(EntityTypes.MANNEQUIN, level);
        body.setPos(x, y, z);
        body.setYRot(yaw);
        body.setYHeadRot(yaw);

        PhantomPuppet puppet = new PhantomPuppet(viewer, body);
        puppet.x = x;
        puppet.y = y;
        puppet.z = z;
        puppet.yaw = yaw;

        viewer.connection.send(new ClientboundAddEntityPacket(body, 0, body.blockPosition()));

        // The skin and the name. This is the whole illusion in one packet.
        SynchedEntityData.DataValue<ResolvableProfile> profile =
                SynchedEntityData.DataValue.create(
                        Mannequin.DATA_PROFILE, ResolvableProfile.createResolved(wearing));
        viewer.connection.send(
                new ClientboundSetEntityDataPacket(body.getId(), List.of(profile)));

        return puppet;
    }

    public ServerPlayer viewer() {
        return viewer;
    }

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    public boolean viewerStillOnline() {
        return !viewer.hasDisconnected();
    }

    /**
     * Move it. Called every tick while it is walking somewhere, because a
     * phantom that teleports is a phantom that has been caught.
     */
    public void moveTo(double nx, double ny, double nz, float nyaw) {
        this.x = nx;
        this.y = ny;
        this.z = nz;
        this.yaw = nyaw;

        body.setPos(nx, ny, nz);
        body.setYRot(nyaw);
        body.setYHeadRot(nyaw);

        viewer.connection.send(new ClientboundEntityPositionSyncPacket(
                body.getId(),
                new PositionMoveRotation(new Vec3(nx, ny, nz), Vec3.ZERO, nyaw, 0f),
                false));

        // Head rotation travels separately. Without this the body turns and the
        // face does not, which is a different and much cheaper kind of uncanny
        // than the one this mod is going for.
        viewer.connection.send(new ClientboundRotateHeadPacket(
                body, (byte) Math.floorMod((int) (nyaw * 256.0f / 360.0f), 256)));
    }

    /** Take it away. It does not run off, it simply stops having been there. */
    public void despawn() {
        if (viewer.hasDisconnected()) return;
        viewer.connection.send(new ClientboundRemoveEntitiesPacket(body.getId()));
    }
}
