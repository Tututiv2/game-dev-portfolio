package com.skyclone.mod.island;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every island tunable. Read at use time. */
final class IslandDials {

    final Dials.Dial<Integer> baseSize, sizePerTier, buildMinY, buildMaxY, guestLimit, quarryRegenSeconds,
            hostLeaveGraceSeconds, visitCooldownSeconds, uniqueGuestCap;
    final Dials.Dial<Boolean> visitOffline, firstJoinHome, helperEnabled, readCommunityShop, enforceGuests, guestArrivalTitle, repairBridges, coopPermissions, retrofitPortals;
    final Dials.Dial<String> starterKit, joinLocation, coopDefaults;

    IslandDials() {
        Dials d = Sky.dials();
        baseSize = d.integer("island.base-size", IslandMath.BASE_SIZE,
                "Build area width (blocks) of a private island with no Island Size upgrade (research 05: 161)");
        sizePerTier = d.integer("island.size-per-tier", IslandMath.SIZE_PER_TIER,
                "Blocks added per Island Size tier when the Community Shop table is missing (research 05: 161 -> 241 over 10 tiers = 8)");
        buildMinY = d.integer("island.build-min-y", 0, "Lowest y members may build at on their island (research 03: y 0-255)");
        buildMaxY = d.integer("island.build-max-y", 255, "Highest y members may build at on their island (research 03: y 0-255)");
        guestLimit = d.integer("island.guest-limit", 5,
                "Guests allowed on one island at once before Guests Limit upgrades (TUNE: research gives only the upgrade, not the base)");
        quarryRegenSeconds = d.integer("island.quarry-regen-seconds", 6,
                "Seconds before a mined block of the island quarry grows back (0 = never). TUNE: stands in for a cobblestone generator");
        hostLeaveGraceSeconds = d.integer("island.host-leave-grace-seconds", 30,
                "Seconds guests may stay after the last island member goes offline (unless offline visits are allowed)");
        visitCooldownSeconds = d.integer("island.visit-cooldown-seconds", 3, "Per-player cooldown on /visit and /is (anti-spam)");
        uniqueGuestCap = d.integer("island.unique-guest-cap", 500, "How many unique guest ids an island remembers (for the visitor count)");
        visitOffline = d.flag("island.visit-offline", false,
                "Allow /visit to islands with no member online (owners must also opt in). The reference only allows online hosts");
        firstJoinHome = d.flag("island.first-join-home", true,
                "Send joining players to their island as soon as it is built (core sends them to the hub while it builds)");
        helperEnabled = d.flag("island.helper-enabled", true, "Place the island helper NPC on each private island");
        readCommunityShop = d.flag("island.read-community-shop", true,
                "Read Island Size / Guests Limit upgrades through the requirement keys cshop:island_size:N and cshop:guests_limit:N");
        enforceGuests = d.flag("island.enforce-guests", true, "Send guests home when an island closes, bans them or its hosts leave");
        guestArrivalTitle = d.flag("island.guest-arrival-title", true, "Show a title to guests when they arrive on someone's island");
        repairBridges = d.flag("island.repair-bridges", true,
                "Re-lay the old narrow bridges (islet + Portal Isle) of islands built before generator v5, once per island per boot"
                        + " when someone is on it. Only air and the old bridge's own blocks are changed (admin: /sb isle bridges <p>)");
        coopPermissions = d.flag("island.coop-permissions", true,
                "Per-member co-op permissions (build / break / containers / minions / manage). The island owner (the longest-"
                        + "standing member) sets them in /is coop. Off = every member may do everything (research 05 §9.2)");
        coopDefaults = d.text("island.coop-default-perms", "build,break,containers,minions,manage",
                "Permissions a co-op member has until the owner changes them (build,break,containers,minions,manage | all | none)."
                        + " Reference: every member may do everything");
        retrofitPortals = d.flag("island.retrofit-portals", true,
                "Offer (through Gerald) to add Portal Isle parts missing from islands built by an older generator - only into empty sky");
        // The Cobblestone Minion I is NOT in the kit: the minions module already hands every new profile one on
        // join (minions.starter-minion), and listing it here gave new players two (playtest newplayer pass).
        starterKit = d.text("island.starter-kit", "ROOKIE_PICKAXE:1,ROOKIE_AXE:1,ROOKIE_HOE:1,BREAD:8",
                "Items the island helper hands each new member once (ID:amount,...). Research 03 §3.2 starting kit"
                        + " (the starter minion comes from minions.starter-minion)");
        joinLocation = d.text("world.join-location", "home",
                "Where players appear on join: 'home' (private island, else hub) or 'last' (where they logged out).");

        // ---- wave 5b: the Social road's island goods, Island Ranks, Island Naming ----
        decorEnabled = d.flag("island.decor-enabled", true,
                "Island decorations bought from the Island Decorator (parkour, displays, egg hunt, showcases, NPCs) work on private islands");
        decorMax.put(Decor.START, d.integer("island.decor.max-parkour-start", 2, "Parkour Start/End plates per island: the first is the start, a second the finish"));
        decorMax.put(Decor.POINT, d.integer("island.decor.max-checkpoints", 25, "Parkour checkpoints per island (TUNE: the research gives no number)"));
        decorMax.put(Decor.TIMES, d.integer("island.decor.max-times", 3, "Parkour Times plinths per island (TUNE)"));
        decorMax.put(Decor.DISPLAY, d.integer("island.decor.max-displays", 3, "Social Displays per island (TUNE)"));
        decorMax.put(Decor.EGG, d.integer("island.decor.max-eggs", 10, "Egg Hunt eggs per island (the item says: Maximum of 10)"));
        decorMax.put(Decor.SHOWCASE, d.integer("island.decor.max-showcases", 20, "Showcase Blocks per island (TUNE)"));
        decorMax.put(Decor.NPC, d.integer("island.decor.max-npcs", 5, "Island NPCs per island (TUNE)"));
        parkourMaxSeconds = d.integer("island.parkour-max-seconds", 900, "A parkour run is cancelled after this many seconds");
        boardLines = d.integer("island.board-lines", 10, "Lines on a Parkour Times / Social Display plinth");
        boardCap = d.integer("island.board-cap", 200, "How many players' best times / visit counts / egg finds an island remembers");
        rankCap = d.integer("island.rank-cap", 50, "How many players one island can give an Island Rank to (TUNE)");
        rankSocial = d.integer("island.rank-social-level", 9, "Social level needed to give Island Ranks (research 02 §2.3: 9)");
        nameSocial = d.integer("island.name-social-level", 20, "Social level needed to name your island (research 02 §2.3: 20)");
        nameMax = d.integer("island.name-max-length", 24, "Longest island name");
        npcTextMax = d.integer("island.npc-text-max-length", 60, "Longest line an Island NPC can say");
        textBlocklist = d.text("island.text-blocklist", "",
                "Comma-separated words players may not use in island names and Island NPC names/lines (public server)");
        customText = d.flag("island.custom-text", true,
                "Players may type island names and Island NPC names/lines (off = NPCs keep their default name and no line)");
    }

    final Dials.Dial<Boolean> decorEnabled, customText;
    final Dials.Dial<Integer> parkourMaxSeconds, boardLines, boardCap, rankCap, rankSocial, nameSocial, nameMax, npcTextMax;
    final Dials.Dial<String> textBlocklist;
    final java.util.Map<String, Dials.Dial<Integer>> decorMax = new java.util.LinkedHashMap<>();

    /** kind -> current limit. */
    java.util.Map<String, Integer> decorLimits() {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        for (var e : decorMax.entrySet()) m.put(e.getKey(), e.getValue().get());
        return m;
    }
}
