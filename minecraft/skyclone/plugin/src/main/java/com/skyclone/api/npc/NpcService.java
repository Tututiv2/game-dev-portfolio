package com.skyclone.api.npc;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/** Registry of clickable NPCs. Register in enable(); core spawns them when their island loads. */
public interface NpcService {

    /** {@code onClick} runs on right- or left-click (with a 0.5s per-player debounce). */
    void register(NpcDef def, Consumer<Player> onClick);

    NpcDef def(String id);

    Collection<NpcDef> all();

    /** Live entity, or null when the island is not loaded. */
    Entity entity(String id);

    /** Sequential "[NPC] Name: ..." lines, then {@code then}. Blocks re-click until finished. */
    void talk(Player p, String npcId, List<String> lines, Runnable then);

    /**
     * Place a copy of a registered NPC at an exact location, for INSTANCED islands (the island helper
     * on each private island, garden visitors) which core cannot place itself. {@code key} names the
     * copy (usually the instance key); calling again with the same key moves it. Same name, holograms
     * and click handler as the registered NPC - the handler can tell copies apart by where the player
     * is. Not persisted: call again after a restart (e.g. on ProfileReady / IslandBuilt).
     * (Added by the core gate, 2026-10-02.)
     */
    void spawnAt(String npcId, String key, Location at);

    /** Remove a copy made by {@link #spawnAt}. */
    void despawn(String npcId, String key);

    /** True while {@link #talk} is running for this player and NPC (clicks on it are ignored then). */
    boolean talking(Player p, String npcId);

    /** Re-spawn every NPC (after a rebuild). */
    void respawnAll();
}
