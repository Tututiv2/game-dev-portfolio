package com.thestill.fog;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The real lamp: one player, one packet, and the world left exactly as it was.
 *
 * <p>{@code ClientboundChunksBiomesPacket} is a biome-only chunk update. It changes what
 * weather a client thinks it is standing in without resending a single block, and like
 * every clientbound packet it goes down one connection — so the player next to you keeps
 * their fog. That is the whole feature. Nothing in the Bukkit API can do it, because
 * biomes are world state in the API's model and a lamp is not.
 *
 * <h2>Why this is reflection and not a compile-time dependency</h2>
 *
 * <p>The owner's ruling on adding ProtocolLib or PacketEvents for this was
 * <i>"why do you need something to depend on son"</i>, and they were right on the
 * merits: those libraries are version-compatibility shims, and this server is pinned to
 * one Paper build with readable Mojang mappings. There is nothing to be compatible with.
 *
 * <p>That leaves compiling against the server internals directly, which needs
 * {@code paperweight-userdev} and a rebuilt {@code build.gradle.kts} — and this build is
 * eight lines of dependencies that every other class on the server is happy with. So the
 * packet is assembled reflectively instead. It costs about a dozen lookups, all of them
 * done ONCE in the constructor, and it buys a build file nobody has to think about and a
 * plugin that still loads if the class ever moves. If any lookup fails the constructor
 * throws, {@code FogService} catches it, logs what it could not find by name, and drops to
 * {@link WorldBiomeChannel} — a working lamp with a worse property, rather than no lamp
 * and a stack trace.
 *
 * <h2>The shapes, taken from the jar rather than from memory</h2>
 *
 * <pre>
 *   ClientboundChunksBiomesPacket(List&lt;ChunkBiomeData&gt;)
 *   ClientboundChunksBiomesPacket$ChunkBiomeData(ChunkPos, byte[])
 *   ChunkPos(int, int)
 *   ServerPlayer.connection                     (a public FIELD, not a getter)
 *   ServerGamePacketListenerImpl.send(Packet)   (inherited from ServerCommonPacketListenerImpl)
 * </pre>
 *
 * <p>Two 26.x details that a from-memory version gets wrong: {@code ResourceLocation} is now
 * {@code net.minecraft.resources.Identifier}, and {@code RegistryAccess.lookupOrThrow} has
 * three overloads with the same parameter type, so reflection cannot pick between them — the
 * lookup here uses {@code lookup(ResourceKey)} returning an {@code Optional}, which has
 * exactly one signature.
 */
final class PacketBiomeChannel implements BiomeChannel {

    private final Logger log;
    private final String biomeKey;

    private final Constructor<?> chunkPosCtor;
    private final Constructor<?> chunkBiomeDataCtor;
    private final Constructor<?> packetCtor;
    private final Class<?> packetInterface;

    private final Method identifierParse;
    private final Method registryLookup;
    private final Method registryGetValue;
    private final Method registryGetId;
    private final Object biomeRegistryKey;

    /** Registry ids are stable for a server's lifetime, so resolve once per world and keep it. */
    private final Map<UUID, Integer> biomeIds = new HashMap<>();

    PacketBiomeChannel(Logger log, String biomeKey) throws ReflectiveOperationException {
        this.log = log;
        this.biomeKey = biomeKey;

        Class<?> chunkPos = Class.forName("net.minecraft.world.level.ChunkPos");
        Class<?> packet = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket");
        Class<?> data = Class.forName(
                "net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket$ChunkBiomeData");
        this.packetInterface = Class.forName("net.minecraft.network.protocol.Packet");

        this.chunkPosCtor = chunkPos.getConstructor(int.class, int.class);
        this.chunkBiomeDataCtor = data.getConstructor(chunkPos, byte[].class);
        this.packetCtor = packet.getConstructor(List.class);

        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier");
        this.identifierParse = identifier.getMethod("parse", String.class);

        Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
        this.biomeRegistryKey = registries.getField("BIOME").get(null);

        Class<?> registryAccess = Class.forName("net.minecraft.core.RegistryAccess");
        Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
        this.registryLookup = registryAccess.getMethod("lookup", resourceKey);

        Class<?> registry = Class.forName("net.minecraft.core.Registry");
        this.registryGetValue = registry.getMethod("getValue", identifier);
        this.registryGetId = registry.getMethod("getId", Object.class);
    }

    @Override
    public String describe() {
        return "per-player packet (ClientboundChunksBiomesPacket) — nobody else sees your lamp";
    }

    @Override
    public boolean light(Player player, long[] chunks) {
        try {
            World world = player.getWorld();
            int biomeId = biomeIdIn(world);
            if (biomeId < 0) {
                return false;
            }
            // (max - min) / 16. Straight off the Bukkit API rather than out of the level
            // handle, because it is the same number and one fewer thing to reflect on.
            int sections = (world.getMaxHeight() - world.getMinHeight()) >> 4;
            byte[] buffer = BiomeBuffer.uniform(sections, biomeId);

            List<Object> payload = new ArrayList<>(chunks.length);
            for (long key : chunks) {
                Object pos = chunkPosCtor.newInstance(ChunkRing.chunkX(key), ChunkRing.chunkZ(key));
                // One buffer object shared by every entry: the packet only ever reads it.
                payload.add(chunkBiomeDataCtor.newInstance(pos, buffer));
            }
            send(player, packetCtor.newInstance(payload));
            return true;
        } catch (Throwable t) {
            // Not swallowed. A lamp that fails silently is a lamp the owner reports as
            // "does nothing" with no way to tell whether the pack, the biome or the packet
            // is at fault — and this is the only one of the three that can say so.
            log.log(Level.WARNING, "fog lamp: could not send biomes to "
                    + player.getName() + " — the lamp will not light", t);
            return false;
        }
    }

    @Override
    public void douse(Player player, long[] chunks) {
        if (!player.isOnline()) {
            // Nothing to put back: the client is gone, and the biome only ever existed in
            // its head. This is the property that makes the packet path the safe one — a
            // player cannot be left in the lamplight biome, because they were never in it
            // anywhere the server can remember.
            return;
        }
        World world = player.getWorld();
        for (long key : chunks) {
            try {
                // A full chunk resend rather than a second biome packet carrying
                // thestill:depths. The depths are not uniform — there is a haven biome
                // painted round every winch — so "put it back to depths" would quietly
                // strip a station's clear air for whoever had been holding a lamp. Asking
                // the server for the truth cannot get that wrong, and it is affordable
                // here because this is a void world and its chunks are mostly nothing.
                world.refreshChunk(ChunkRing.chunkX(key), ChunkRing.chunkZ(key));
            } catch (Throwable ignored) {
                // Worst case the player keeps a clear chunk until they walk away and it
                // reloads. Not worth a log line per chunk.
            }
        }
    }

    /** The biome's numeric id in this world's registry, or -1 if the datapack did not load it. */
    private int biomeIdIn(World world) {
        Integer cached = biomeIds.get(world.getUID());
        if (cached != null) {
            return cached;
        }
        int resolved = -1;
        try {
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            Object access = level.getClass().getMethod("registryAccess").invoke(level);
            Object maybe = registryLookup.invoke(access, biomeRegistryKey);
            Object registry = ((Optional<?>) maybe).orElse(null);
            if (registry != null) {
                Object biome = registryGetValue.invoke(registry,
                        identifierParse.invoke(null, biomeKey));
                if (biome != null) {
                    resolved = (Integer) registryGetId.invoke(registry, biome);
                }
            }
        } catch (Throwable t) {
            log.log(Level.WARNING, "fog lamp: could not resolve " + biomeKey
                    + " in the biome registry", t);
        }
        if (resolved < 0) {
            log.warning("fog lamp: " + biomeKey + " is not in the biome registry — the "
                    + "datapack at server/world/datapacks/thestill-depths did not load it, "
                    + "and the lamp cannot work without it.");
        }
        biomeIds.put(world.getUID(), resolved);
        return resolved;
    }

    private void send(Player player, Object packet) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Field connectionField = handle.getClass().getField("connection");
        Object connection = connectionField.get(handle);
        Method send = connection.getClass().getMethod("send", packetInterface);
        send.invoke(connection, packet);
    }
}
