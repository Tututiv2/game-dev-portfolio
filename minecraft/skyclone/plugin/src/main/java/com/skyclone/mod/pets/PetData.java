package com.skyclone.mod.pets;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-member pet state (profile key "pets"). Gson-friendly: plain fields, no Bukkit types. Fields
 * may be ADDED later; never change a field's type (CORE_GUIDE trap 3).
 */
public final class PetData {

    /** One pet in the Pets menu. A pet here is data, not an item: it cannot be duplicated by inventory tricks. */
    public static final class OwnedPet {
        /** Stable id; equals the uuid of the item it was learned from (dupe guard). */
        public String uuid;
        public String type;
        public String tier;
        public double exp;
        public String heldItem;
        public int candyUsed;
        public long obtained;
        /** Per-pet progress counters (wave 3): "bulwark" = blaze/demon kills for the wisps' Ember Ward. Null = none. */
        public java.util.Map<String, Integer> counters;
        /** Applied pet skin item id (wave 5, cosmetic only), or null. */
        public String skin;

        public int counter(String key) {
            Integer v = counters == null ? null : counters.get(key);
            return v == null ? 0 : v;
        }

        public void bump(String key) {
            if (counters == null) counters = new java.util.LinkedHashMap<>();
            counters.merge(key, 1, Integer::sum);
        }
    }

    /** A pet left with the Pet Sitter for a rarity upgrade. */
    public static final class KatJob {
        public OwnedPet pet;
        public String toTier;
        /** Epoch millis when the upgrade is done. */
        public long readyAt;
        public long startedAt;
    }

    /** "When I arrive on <island>, summon <pet>." */
    public static final class AutoRule {
        public String island;
        public String petUuid;
    }

    public List<OwnedPet> pets = new ArrayList<>();
    /** uuid of the summoned pet, or null. */
    public String active;
    /** uuids of pets sitting in Exp Share slots. */
    public List<String> expShare = new ArrayList<>();
    public KatJob kat;
    public List<AutoRule> autopet = new ArrayList<>();
    /** Viewer setting: hide every floating pet (yours and other players'). */
    public boolean hidePets;
    /** 0 = rarity, 1 = level, 2 = skill, 3 = name. */
    public int sort;
    /** Pet types given to the Pet Collector (each raises the Taming cap by 1). */
    public Set<String> collector = new LinkedHashSet<>();
    /** Highest Pet Score this member ever reached (for the one-time SkyBlock XP awards). */
    public int bestScore;
    /** Mythological mobs slain while a Beastmaster Crest counted (wave 5: the crest's stats grow with them). */
    public int mythKills;

    public OwnedPet find(String uuid) {
        if (uuid == null) return null;
        for (OwnedPet p : pets) if (uuid.equals(p.uuid)) return p;
        return null;
    }
}
