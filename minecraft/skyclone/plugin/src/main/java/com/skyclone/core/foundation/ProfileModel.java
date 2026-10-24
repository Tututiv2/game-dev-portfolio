package com.skyclone.core.foundation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory shapes of loaded profiles. Package-private plumbing for ProfileServiceImpl. */
final class ProfileModel {

    private ProfileModel() {}

    /** Genre-style profile names. */
    static final List<String> FRUITS = List.of(
            "Apple", "Banana", "Blueberry", "Coconut", "Cucumber", "Grapes", "Kiwi", "Lemon", "Lime", "Mango",
            "Orange", "Papaya", "Pear", "Peach", "Pineapple", "Pomegranate", "Raspberry", "Strawberry", "Tomato",
            "Watermelon", "Zucchini");

    /** One stored JSON document and (once asked for) its live object. */
    static final class Slot {
        final String key;
        /** JSON currently in the database (null = no row yet). */
        volatile String savedJson;
        /** Live object handed to modules; null until first asked for. */
        volatile Object value;
        /** Failed to deserialize: never written back, so the stored JSON survives for repair. */
        volatile boolean poisoned;
        /** Last write failed: rewrite on the next save even if unchanged. */
        volatile boolean forceWrite;

        Slot(String key, String savedJson) { this.key = key; this.savedJson = savedJson; }
    }

    /** A profile with at least one member online. Shared between the online members. */
    static final class LoadedProfile {
        final String id;
        volatile String name;
        volatile String mode;
        final long created;
        final List<UUID> members = new CopyOnWriteArrayList<>();
        final Map<String, Slot> shared = new ConcurrentHashMap<>();
        final Set<UUID> online = ConcurrentHashMap.newKeySet();
        /** tenths */
        volatile long bank;
        /** tenths; 0 = use the default-capacity dial */
        volatile long bankCap;

        LoadedProfile(String id, String name, String mode, long created) {
            this.id = id; this.name = name; this.mode = mode; this.created = created;
        }
    }

    /** One online player on their active profile. */
    static final class Session {
        final UUID uuid;
        final String name;
        final LoadedProfile profile;
        final Map<String, Slot> member = new ConcurrentHashMap<>();
        /** tenths */
        volatile long purse;
        volatile long bits;
        final boolean firstJoin;
        volatile boolean ready;
        /** set by /profiles switch: becomes the active profile after the quit save */
        volatile String switchTo;
        /** set by /coop leave: membership is closed after the quit save */
        volatile boolean leaving;
        /** ProfileUnload has fired (or shutdown phase 1 ran). */
        volatile boolean unloaded;
        /** Saved and removed from the session map; a second unload is a no-op. */
        volatile boolean evicted;

        Session(UUID uuid, String name, LoadedProfile profile, boolean firstJoin) {
            this.uuid = uuid; this.name = name; this.profile = profile; this.firstJoin = firstJoin;
        }
    }

    /** What AsyncPlayerPreLoginEvent read from the database. */
    static final class Snapshot {
        UUID uuid;
        String playerName;
        String profileId;
        String profileName;
        String mode;
        long created;
        List<UUID> members = new ArrayList<>();
        Map<String, String> memberJson = new HashMap<>();
        Map<String, String> sharedJson = new HashMap<>();
        long purse;
        long bits;
        long bank;
        long bankCap;
        boolean firstJoin;
        long seq;
        long readAt;
    }

    /** A change made through the database while the player was offline or mid-login. */
    record OfflineWrite(long seq, String profileId, String key, String json, long purseDelta) {}

    /** One row of /profiles. */
    record ProfileRow(String id, String name, String mode, long created, int members, long purse, boolean active) {}
}
