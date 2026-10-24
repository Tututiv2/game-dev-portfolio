package com.skyclone.api.profile;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Per-player persistent state, the easy way. A module keeps its state in a plain class with a
 * public no-arg constructor and Gson-friendly fields, and asks for it by a key:
 *
 * <pre>
 *   SkillData d = Sky.profiles().member(player, "skills", SkillData.class);
 *   d.xp.merge("FARMING", 4.0, Double::sum);     // just mutate it
 * </pre>
 *
 * Core serialises every loaded object every 10 seconds and on quit, writing only those whose
 * JSON changed, in one SQLite (WAL) transaction. No dirty flags. Call {@link #saveNow} after
 * something that must never be lost (a trade, a rare drop being claimed).
 *
 * <p><b>Member vs shared.</b> A profile can have several co-op members. {@link #member} is that
 * player's own (skills, collections contribution, pets, inventory-like things). {@link #shared}
 * is the profile's (island, minions, bank-adjacent state, garden).
 *
 * <p>Data is loaded BEFORE {@code SkyEvents.ProfileReady} fires. Do per-player setup on that
 * event, not on PlayerJoinEvent — on join the data may not be there yet.
 */
public interface ProfileService {

    <T> T member(Player p, String key, Class<T> type);

    <T> T shared(Player p, String key, Class<T> type);

    /** Load/modify a possibly-offline player's member data atomically; applies live if they are online. */
    <T> CompletableFuture<Void> modifyOffline(UUID player, String key, Class<T> type, Consumer<T> change);

    <T> CompletableFuture<T> readOffline(UUID player, String key, Class<T> type);

    void saveNow(Player p);

    boolean isLoaded(Player p);

    /** Stable id of the player's active profile. */
    String profileId(Player p);

    /** Fruit-style profile name ("Apple", "Banana"...). */
    String profileName(Player p);

    /** Members of a LOADED profile (someone online); {@code List.of()} otherwise - use {@link #membersAsync}. */
    List<UUID> members(String profileId);

    /**
     * Members of any profile, online or not (current members only; left members excluded).
     * Completes on the DB thread when it has to read. (Added by the core gate, 2026-10-02.)
     */
    CompletableFuture<List<UUID>> membersAsync(String profileId);

    /**
     * Loaded AND {@code SkyEvents.ProfileReady} has fired. {@link #isLoaded} is true from join, a tick
     * earlier; per-player setup should wait for this. (Added by the core gate, 2026-10-02.)
     */
    boolean isReady(Player p);

    /** Add a co-op member (invite accepted). Their next login loads this profile. */
    CompletableFuture<Boolean> addMember(String profileId, UUID player);

    /** Profile game mode: "normal", "ironman", "stranded", "bingo". */
    String mode(Player p);
}
