package com.skyclone.mod.dungeons.party;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One party (1-5 players). In memory: parties do not survive a restart, like the genre's. */
public final class Party {

    public UUID leader;
    public final LinkedHashSet<UUID> members = new LinkedHashSet<>();
    /** invited uuid -> expiry millis. */
    public final Map<UUID, Long> invites = new HashMap<>();
    /** The party's Party Finder listing, or null. */
    public PartyFinder.Listing listing;
    public final long created = System.currentTimeMillis();

    public Party(UUID leader) {
        this.leader = leader;
        members.add(leader);
    }

    public boolean isLeader(Player p) { return p.getUniqueId().equals(leader); }

    public int size() { return members.size(); }

    public List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    public String leaderName() {
        Player p = Bukkit.getPlayer(leader);
        return p != null ? p.getName() : Bukkit.getOfflinePlayer(leader).getName();
    }
}
