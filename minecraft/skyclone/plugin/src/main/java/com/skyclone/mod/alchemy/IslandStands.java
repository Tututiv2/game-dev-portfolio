package com.skyclone.mod.alchemy;

import java.util.HashMap;
import java.util.Map;

/**
 * The brewing stands placed on a profile's private island, shared key {@code "alchemy_stands"} (one object
 * for every online co-op member). Key = block position relative to the island's slot origin, {@code x,y,z},
 * so the brew follows the island and never the grid slot it happens to sit in.
 */
public final class IslandStands {
    public Map<String, AlchemyData.Stand> stands = new HashMap<>();
}
