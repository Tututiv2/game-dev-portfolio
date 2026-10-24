package org.aincrad.plugin.service;

/**
 * A named town service, bound to a real building parcel. Stable id + version so the anchor can be
 * referenced by later systems (quests, respawn binding, claim delivery) without depending on
 * coordinates, which may move if a district is ever regenerated.
 */
public record ServiceAnchor(
        String id,
        ServiceType type,
        String parcelId,
        int x,
        int y,
        int z,
        String version
) {
    public enum ServiceType {
        STARTER_INFORMATION,
        SAFE_STORAGE,
        FOOD_GENERAL_GOODS,
        REPAIR_CRAFTING,
        INN_RESPAWN_BINDING,
        GUILD_PARTY_PREPARATION,
        FLOOR_MAPPING_INTRODUCTION,
        NORTH_GATE_EXPEDITION_BOARD,
        LOST_AND_FOUND_CLAIM,
        ADMIN_VERIFICATION
    }
}
