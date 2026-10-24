package com.skyclone.mod.island;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.island.gen.PrivateIslandGenerator;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The private island: who owns which grid slot, where "home" is, who may build, who is visiting.
 *
 * <p>The instance key of an island is the PROFILE id ({@code Sky.profiles().profileId}), the same key core's
 * own "home" warp uses, so every co-op member shares one island. Locations are mapped back to a key by
 * rounding to the instance grid ({@link IslandMath#originOf}) and looking the origin up in a cache that is
 * filled whenever a key's origin is asked for (logins, visits, warps).
 */
final class IslandService implements Features.PrivateIsland {

    static final String ISLAND = PrivateIslandGenerator.ISLAND;
    static final NamespacedKey SETTING_VISITORS = new NamespacedKey("skyclone", "setting.island.visitors");
    static final NamespacedKey SETTING_NOTIFY = new NamespacedKey("skyclone", "setting.island.guest_notify");

    private final IslandDials dials;
    private final IslandStore store;

    private final Map<Long, String> originToKey = new ConcurrentHashMap<>();
    private final Map<String, long[]> keyToOrigin = new ConcurrentHashMap<>();
    private final Map<String, int[]> sizeCache = new ConcurrentHashMap<>();   // key -> {size, guestExtra, expiresAtSecond}

    /** Guests by uuid -> island key they are standing on. Main thread only. */
    final Map<UUID, String> guestAt = new HashMap<>();
    private final Map<String, Long> hostlessSince = new HashMap<>();
    final Set<String> helperKeys = new HashSet<>();
    final Set<UUID> pendingHome = new HashSet<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private int ticks;

    /** Community Shop tables (tier 1..n), read from the bank module's data if present. */
    int[] sizeTable = null, guestTable = null;

    IslandService(IslandDials dials, IslandStore store) {
        this.dials = dials;
        this.store = store;
    }

    IslandDials dials() { return dials; }

    IslandStore store() { return store; }

    // =========================================================================================
    // keys and origins
    // =========================================================================================

    static String keyOf(Player p) {
        String k = null;
        try { k = Sky.profiles().profileId(p); } catch (RuntimeException ignored) { }
        return k == null || k.isBlank() ? p.getUniqueId().toString() : k;
    }

    static int spacing() {
        IslandDef d = Sky.worlds().island(ISLAND);
        return d == null ? IslandMath.SPACING : Math.max(16, d.instanceSpacing);
    }

    /** {x, z} of a key's grid slot (assigns a slot if the key never had one: only call for real islands). */
    long[] origin(String key) {
        if (key == null) return null;
        long[] o = keyToOrigin.get(key);
        if (o != null) return o;
        Location l = Sky.worlds().instanceOrigin(ISLAND, key);
        if (l == null) return null;
        o = new long[]{l.getBlockX(), l.getBlockZ()};
        keyToOrigin.put(key, o);
        originToKey.put(pack(o[0], o[1]), key);
        return o;
    }

    private static long pack(long x, long z) { return (x << 32) ^ (z & 0xFFFFFFFFL); }

    /** Drop the cached slot of a key (after its instance was released). */
    void forget(String key) {
        long[] o = keyToOrigin.remove(key);
        if (o != null) originToKey.remove(pack(o[0], o[1]), key);
        sizeCache.remove(key);
    }

    World world() { return Sky.worlds().world(ISLAND); }

    boolean inIslandWorld(Location l) {
        return l != null && l.getWorld() != null && ISLAND.equals(Sky.worlds().islandOf(l.getWorld()));
    }

    /** The island key at a location, or null (not the island world, or a slot nobody here owns). */
    String keyAt(Location l) {
        if (!inIslandWorld(l)) return null;
        int sp = spacing();
        long ox = IslandMath.originOf(l.getX(), sp), oz = IslandMath.originOf(l.getZ(), sp);
        String k = originToKey.get(pack(ox, oz));
        if (k != null) return k;
        // not cached yet: every online player's key is a candidate
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isLoaded(p)) continue;
            String pk = keyOf(p);
            if (!keyToOrigin.containsKey(pk)) origin(pk);
        }
        return originToKey.get(pack(ox, oz));
    }

    /** Template-relative {dx, y, dz} of a location on island {@code key}. */
    double[] relative(String key, Location l) {
        long[] o = origin(key);
        if (o == null) return null;
        return new double[]{l.getX() - o[0], l.getY(), l.getZ() - o[1]};
    }

    // =========================================================================================
    // members, size, guests
    // =========================================================================================

    /** Online members of a profile (co-op included). */
    List<Player> membersOnline(String key) {
        List<Player> out = new ArrayList<>();
        Collection<UUID> ids;
        try { ids = Sky.profiles().members(key); } catch (RuntimeException e) { ids = List.of(); }
        for (UUID u : ids) { Player p = Bukkit.getPlayer(u); if (p != null && p.isOnline()) out.add(p); }
        if (out.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) if (Sky.profiles().isLoaded(p) && key.equals(keyOf(p))) out.add(p);
        }
        return out;
    }

    boolean isMember(Player p, String key) { return key != null && key.equals(keyOf(p)); }

    /** {size, guestExtra} for an island, using any online member for the Community Shop requirement keys. */
    int[] upgrades(String key) {
        int now = (int) (System.currentTimeMillis() / 1000);
        int[] c = sizeCache.get(key);
        if (c != null && c[2] > now) return c;
        int sizeTier = 0, guestTier = 0;
        if (dials.readCommunityShop.get()) {
            List<Player> m = membersOnline(key);
            if (!m.isEmpty()) {
                Player p = m.get(0);
                sizeTier = highestTier(p, "island_size", IslandMath.MAX_TIER);
                guestTier = highestTier(p, "guests_limit", guestTable == null ? 5 : guestTable.length);
            } else if (c != null) {
                return c;   // keep the last known numbers while nobody is online to ask
            }
        }
        int size = IslandMath.sizeForTier(sizeTier, dials.baseSize.get(), dials.sizePerTier.get(), sizeTable);
        int extra = guestTier <= 0 ? 0 : (guestTable != null && guestTier <= guestTable.length ? guestTable[guestTier - 1] : guestTier);
        int[] out = {size, extra, now + 30, sizeTier, guestTier};
        sizeCache.put(key, out);
        return out;
    }

    void invalidate(String key) { sizeCache.remove(key); }

    private static int highestTier(Player p, String upgrade, int max) {
        for (int t = max; t >= 1; t--) {
            try { if (Sky.requirements().check(p, "cshop:" + upgrade + ":" + t)) return t; } catch (RuntimeException e) { return 0; }
        }
        return 0;
    }

    int sizeOf(String key) { return upgrades(key)[0]; }

    int guestLimit(String key) { return IslandMath.guestLimit(dials.guestLimit.get(), upgrades(key)[1]); }

    /** Guests standing on island {@code key} right now. */
    List<Player> guestsOn(String key) {
        List<Player> out = new ArrayList<>();
        for (Map.Entry<UUID, String> e : guestAt.entrySet()) {
            if (!key.equals(e.getValue())) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) out.add(p);
        }
        return out;
    }

    /** A friendly owner label for an island ("Steve" or "Steve & Alex"). */
    String ownerLabel(String key) {
        List<Player> on = membersOnline(key);
        Set<String> names = new LinkedHashSet<>();
        String owner = ownerName(key);
        if (owner != null) names.add(owner);
        for (Player p : on) names.add(p.getName());
        for (String n : store.namesOf(key)) { if (names.size() >= 2) break; names.add(n); }
        if (names.isEmpty()) return "Someone";
        List<String> l = new ArrayList<>(names);
        return l.size() == 1 ? l.get(0) : l.get(0) + " & " + l.get(1) + (on.size() > 2 ? " +" + (on.size() - 2) : "");
    }

    // =========================================================================================
    // locations
    // =========================================================================================

    Location spawnOf(String key) {
        Location l = Sky.worlds().instanceAnchor(ISLAND, key, "spawn");
        origin(key);
        return l;
    }

    /** The island's home: a custom /setspawn if one is set, else the template spawn. */
    Location homeOf(String key) {
        IslandSettings s = store.settings(key);
        if (s.homeSet) {
            long[] o = origin(key);
            World w = world();
            if (o != null && w != null) {
                Location l = new Location(w, o[0] + s.hx, s.hy, o[1] + s.hz, s.hyaw, s.hpitch);
                if (standable(l)) return l;
                // the custom spawn was built over or its floor was mined away: use the template spawn rather than drop them
            }
        }
        return spawnOf(key);
    }

    /**
     * Can a player stand here: a solid floor and two passable blocks. Only judged when the chunk is already loaded
     * (never loads one just to look); an unloaded spot is trusted.
     */
    static boolean standable(Location l) {
        World w = l.getWorld();
        if (w == null) return false;
        if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return true;
        // same floor test as /setspawn (the block 0.1 below the feet), so a spawn set on a slab still counts
        return l.clone().add(0, -0.1, 0).getBlock().getType().isSolid()
                && l.clone().add(0, 0.6, 0).getBlock().isPassable() && l.clone().add(0, 1.6, 0).getBlock().isPassable();
    }

    static void teleport(Player p, Location to) {
        if (to == null) return;
        p.setFallDistance(0);
        p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFallDistance(0);
    }

    // =========================================================================================
    // Features.PrivateIsland
    // =========================================================================================

    @Override
    public Location home(Player p) {
        String key = keyOf(p);
        CompletableFuture<Location> f;
        try { f = Sky.worlds().ensureInstance(ISLAND, key); } catch (RuntimeException e) { return null; }
        if (f == null || !f.isDone() || f.isCompletedExceptionally()) return null;
        return homeOf(key);
    }

    @Override
    public boolean isOnOwnIsland(Player p) {
        if (p == null || !inIslandWorld(p.getLocation())) return false;
        return keyOf(p).equals(keyAt(p.getLocation()));
    }

    @Override
    public boolean canBuild(Player p, Location l) {
        if (p == null || l == null || !inIslandWorld(l)) return false;
        String key = keyAt(l);
        if (key == null || !isMember(p, key)) return false;
        if (!insideBuildSquare(key, l)) return false;
        // wave 5: a co-op member whose owner took away BOTH block permissions is no builder at all (core then also
        // denies them containers and workstations; the listener re-allows containers if that toggle is still on).
        // With either one left, core lets the event through and the listener refines place vs break.
        return may(p, key, CoopPerm.BUILD) || may(p, key, CoopPerm.BREAK);
    }

    /** Inside the island's build square (size upgrades) and y range. Membership is NOT checked. */
    boolean insideBuildSquare(String key, Location l) {
        double[] r = relative(key, l);
        if (r == null) return false;
        return IslandMath.insideBuild(r[0], r[1], r[2], sizeOf(key), dials.buildMinY.get(), dials.buildMaxY.get());
    }

    // =========================================================================================
    // co-op owner and per-member permissions (wave 5)
    // =========================================================================================

    private String defaultsSpec;
    private Set<CoopPerm> defaults = EnumSet.allOf(CoopPerm.class);
    private final Set<String> resolvingOwner = new HashSet<>();

    Set<CoopPerm> coopDefaults() {
        String spec = dials.coopDefaults.get();
        if (!Objects.equals(spec, defaultsSpec)) {
            defaults = CoopPerm.parseSet(spec);
            defaultsSpec = spec;
        }
        return defaults;
    }

    static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    /** Current members of a LOADED profile (empty when nobody of it is online). */
    static List<UUID> members(String key) {
        try { return Sky.profiles().members(key); } catch (RuntimeException e) { return List.of(); }
    }

    /** The island's owner, or null while it is not known yet (then nobody is restricted). */
    UUID ownerOf(String key) {
        if (key == null) return null;
        return CoopPerm.resolveOwner(uuid(store.settings(key).owner), members(key), List.of());
    }

    boolean isOwner(Player p, String key) {
        if (!isMember(p, key)) return false;
        UUID o = ownerOf(key);
        return o == null || o.equals(p.getUniqueId());
    }

    /** May member {@code p} do {@code perm} on island {@code key}? Non-members: never. */
    boolean may(Player p, String key, CoopPerm perm) {
        if (p == null || !isMember(p, key)) return false;
        if (!dials.coopPermissions.get()) return true;
        if (Sky.worlds().buildBypass(p)) return true;
        return CoopPerm.allowed(p.getUniqueId(), perm, ownerOf(key), store.settings(key).coopPerms, coopDefaults());
    }

    /** Same check by uuid (offline members, menus). Membership is assumed. */
    boolean may(UUID member, String key, CoopPerm perm) {
        if (!dials.coopPermissions.get()) return true;
        return CoopPerm.allowed(member, perm, ownerOf(key), store.settings(key).coopPerms, coopDefaults());
    }

    /** Owner-only change of one member's toggle. Returns the error, or null. */
    String setPerm(Player by, String key, UUID member, CoopPerm perm, boolean on) {
        if (!isOwner(by, key)) return "Only the island owner can change co-op permissions.";
        if (member.equals(by.getUniqueId())) return "The owner always has every permission.";
        List<UUID> ms = members(key);
        if (!ms.isEmpty() && !ms.contains(member)) return "That player is not a member of this co-op.";
        setPermForce(key, member, perm, on);
        return null;
    }

    void setPermForce(String key, UUID member, CoopPerm perm, boolean on) {
        IslandSettings s = store.settings(key);
        synchronized (s) {
            CoopPerm.Toggles t = s.coopPerms.computeIfAbsent(member.toString(), k -> CoopPerm.Toggles.of(coopDefaults()));
            t.set(perm, on);
        }
        store.save(key);
    }

    void setOwner(String key, UUID owner) {
        IslandSettings s = store.settings(key);
        synchronized (s) {
            s.owner = owner == null ? null : owner.toString();
            if (owner != null) s.coopPerms.remove(owner.toString());
        }
        store.save(key);
    }

    /**
     * Make sure the island has an owner (on every ProfileReady). A solo profile: the player. A recorded owner who is
     * still a member: kept. Otherwise the longest-standing member, read from the profile's member list ordered by
     * join time - core's API exposes no owner and its loaded member list is unordered (hand-off Request 8), so this
     * one read uses the {@code profile_members} table directly, read-only, and falls back to "no owner" (nobody
     * restricted) if that fails.
     */
    void ensureOwner(Player p, String key) {
        IslandSettings s = store.settings(key);
        List<UUID> cur = members(key);
        UUID rec = uuid(s.owner);
        if (rec != null && (cur.isEmpty() || cur.contains(rec))) {
            boolean pruned;
            synchronized (s) { pruned = CoopPerm.prune(s.coopPerms, cur) > 0; }
            if (pruned) store.save(key);
            return;
        }
        if (cur.size() <= 1) { setOwner(key, p.getUniqueId()); return; }
        if (!resolvingOwner.add(key)) return;
        Sky.db().query(c -> {
            List<UUID> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid FROM profile_members WHERE profile_id = ? AND left_at = 0 ORDER BY joined")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { UUID u = uuid(rs.getString(1)); if (u != null) out.add(u); }
                }
            }
            return out;
        }).whenComplete((ordered, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            resolvingOwner.remove(key);
            if (err != null) { Sky.log().info("[island] could not read the member order of " + key + " (" + err + "); owner left unset"); return; }
            UUID o = CoopPerm.resolveOwner(uuid(store.settings(key).owner), members(key), ordered);
            if (o != null) setOwner(key, o);
        }));
    }

    /** The owner's name, or null when the owner is not known. */
    String ownerName(String key) {
        UUID o = ownerOf(key);
        return o == null ? null : nameOf(o);
    }

    String nameOf(UUID u) {
        Player online = Bukkit.getPlayer(u);
        if (online != null) return online.getName();
        IslandStore.PlayerEntry e = store.player(u);
        if (e != null) return e.name();
        String n = Bukkit.getOfflinePlayer(u).getName();
        return n == null ? u.toString().substring(0, 8) : n;
    }

    /** The profile's loaded members, owner first. */
    List<UUID> knownMembers(String key) {
        List<UUID> ms = new ArrayList<>(members(key));
        UUID o = ownerOf(key);
        if (o != null && ms.remove(o)) ms.add(0, o);
        return ms;
    }

    // =========================================================================================
    // actions
    // =========================================================================================

    /** True (and stamps) when the player is off cooldown. */
    boolean offCooldown(Player p) {
        long now = System.currentTimeMillis();
        Long until = cooldown.get(p.getUniqueId());
        if (until != null && until > now) {
            Msg.error(p, "Slow down! Try again in a moment.");
            return false;
        }
        cooldown.put(p.getUniqueId(), now + Math.max(0, dials.visitCooldownSeconds.get()) * 1000L);
        return true;
    }

    /** Take a player to their own island, building it first if needed. */
    void goHome(Player p) { goHome(p, null); }

    void goHome(Player p, Runnable afterArrival) {
        String key = keyOf(p);
        Msg.send(p, "&7Warping to your &aPrivate Island&7...");
        Sky.worlds().ensureInstance(ISLAND, key).whenComplete((loc, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            if (err != null || loc == null) {
                Msg.error(p, "Your island could not be prepared. Try again in a moment.");
                if (err != null) Sky.log().log(Level.WARNING, "[island] ensureInstance failed for " + key, err);
                return;
            }
            spawnHelper(key);
            Location to = homeOf(key);
            teleport(p, to != null ? to : loc);
            if (afterArrival != null) afterArrival.run();
        }));
    }

    /** Why {@code visitor} may not visit island {@code key}, or null when they may. */
    String visitBlocker(Player visitor, String key) {
        IslandSettings s = store.settings(key);
        if (s.banned.contains(visitor.getUniqueId().toString())) return "You are banned from that island.";
        if (!IslandMath.guestsAllowed(s.visitors)) return "That island is not accepting visitors right now.";
        boolean hostOnline = !membersOnline(key).isEmpty();
        if (!hostOnline && !(dials.visitOffline.get() && s.offlineVisits)) return "Nobody from that island is online.";
        int limit = guestLimit(key);
        if (!rankOf(key, visitor.getUniqueId()).ignoresGuestLimit() && countedGuests(key) >= limit)
            return "That island is full! (" + limit + "/" + limit + " guests)";
        return null;
    }

    /** Guests that count toward the guest limit (Island Ranks Friend/Trusted do not). */
    int countedGuests(String key) {
        int n = 0;
        for (Player g : guestsOn(key)) if (!rankOf(key, g.getUniqueId()).ignoresGuestLimit()) n++;
        return n;
    }

    /** A non-member's Island Rank on island {@code key} (wave 5b). */
    IslandRank rankOf(String key, UUID player) {
        if (key == null || player == null) return IslandRank.GUEST;
        return IslandRank.of(store.settings(key).ranks, player.toString());
    }

    /** The island's display title: its Social-20 name, else "Owner's Island". */
    String islandTitle(String key) {
        String n = store.settings(key).islandName;
        return n != null && !n.isBlank() ? n : ownerLabel(key) + "'s Island";
    }

    /** Visit someone's island; {@code force} skips every permission check (admins). */
    void visit(Player p, String key, String label, boolean force) {
        if (key == null) { Msg.error(p, "That player has no island yet."); return; }
        if (isMember(p, key)) { goHome(p); return; }
        if (!force) {
            String why = visitBlocker(p, key);
            if (why != null) { Msg.error(p, why); return; }
        }
        Msg.send(p, "&7Visiting &b" + label + "&7's island...");
        Sky.worlds().ensureInstance(ISLAND, key).whenComplete((loc, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            if (err != null || loc == null) { Msg.error(p, "That island could not be loaded. Try again in a moment."); return; }
            spawnHelper(key);
            Location to = homeOf(key);
            teleport(p, to != null ? to : loc);
        }));
    }

    /** Send a guest off an island with a reason. */
    void sendOff(Player guest, String reason) {
        if (reason != null) Msg.error(guest, reason);
        guestAt.remove(guest.getUniqueId());
        Sky.worlds().sendHome(guest);
    }

    boolean setSpawn(Player p) {
        String key = keyOf(p);
        if (!key.equals(keyAt(p.getLocation()))) { Msg.error(p, "You can only set the spawn on your own island."); return false; }
        if (!may(p, key, CoopPerm.MANAGE)) { Msg.error(p, "The island owner has not given you permission to manage the island."); return false; }
        if (!insideBuildSquare(key, p.getLocation())) { Msg.error(p, "That spot is outside your island's build area."); return false; }
        if (!p.getLocation().clone().add(0, -0.1, 0).getBlock().getType().isSolid()) { Msg.error(p, "Stand on solid ground to set the spawn."); return false; }
        double[] r = relative(key, p.getLocation());
        IslandSettings s = store.settings(key);
        synchronized (s) {
            s.homeSet = true;
            s.hx = r[0]; s.hy = r[1]; s.hz = r[2];
            s.hyaw = p.getLocation().getYaw(); s.hpitch = p.getLocation().getPitch();
        }
        store.save(key);
        Msg.success(p, "Island spawn set to where you are standing.");
        return true;
    }

    void resetSpawn(String key) {
        IslandSettings s = store.settings(key);
        synchronized (s) { s.homeSet = false; }
        store.save(key);
    }

    // =========================================================================================
    // helper NPC
    // =========================================================================================

    void spawnHelper(String key) {
        if (!dials.helperEnabled.get() || key == null) return;
        Location at = Sky.worlds().instanceAnchor(ISLAND, key, "npc.island_helper");
        if (at == null) return;
        Sky.npcs().spawnAt(IslandModule.HELPER_ID, key, at);
        helperKeys.add(key);
    }

    void despawnHelper(String key) {
        Sky.npcs().despawn(IslandModule.HELPER_ID, key);
        helperKeys.remove(key);
    }

    // =========================================================================================
    // the per-second visitor tick
    // =========================================================================================

    void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        Set<String> seen = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            if (!Sky.profiles().isLoaded(p) || !inIslandWorld(p.getLocation())) { leave(u); continue; }
            String key = keyAt(p.getLocation());
            String own = keyOf(p);
            if (key == null || key.equals(own)) { leave(u); if (key != null) seen.add(key); continue; }
            String prev = guestAt.put(u, key);
            if (!key.equals(prev)) {
                if (prev != null) departed(p, prev);
                arrived(p, key);
            }
            if (dials.enforceGuests.get() && !p.hasPermission("skyclone.admin")) enforce(p, key, now);
        }
        // hosts present -> reset the grace timers; mirror the members' visitor setting
        for (String key : new ArrayList<>(hostlessSince.keySet())) if (!membersOnline(key).isEmpty()) hostlessSince.remove(key);
        if (ticks % 5 == 0) for (String key : seen) syncVisitorSetting(key);
        if (ticks % 30 == 0) {
            for (String key : new ArrayList<>(helperKeys)) {
                if (membersOnline(key).isEmpty() && guestsOn(key).isEmpty()) despawnHelper(key);
            }
            cooldown.values().removeIf(t -> t < now);
        }
    }

    private void leave(UUID u) {
        String prev = guestAt.remove(u);
        if (prev != null) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) departed(p, prev);
        }
    }

    private void enforce(Player p, String key, long now) {
        IslandSettings s = store.settings(key);
        if (s.banned.contains(p.getUniqueId().toString())) { sendOff(p, "You are banned from this island."); return; }
        if (!IslandMath.guestsAllowed(s.visitors)) { sendOff(p, "This island is no longer accepting visitors."); return; }
        if (membersOnline(key).isEmpty()) {
            long since = hostlessSince.computeIfAbsent(key, k -> now);
            boolean offlineOk = dials.visitOffline.get() && s.offlineVisits;
            if (!offlineOk && now - since > Math.max(0, dials.hostLeaveGraceSeconds.get()) * 1000L) {
                sendOff(p, "Everyone from this island has left, so you were sent home.");
            }
        }
    }

    private void arrived(Player guest, String key) {
        IslandSettings s = store.settings(key);
        synchronized (s) {
            s.totalVisits++;
            if (s.uniqueGuests.size() < Math.max(0, dials.uniqueGuestCap.get())) s.uniqueGuests.add(guest.getUniqueId().toString());
            String gid = guest.getUniqueId().toString();
            if (s.guestVisits.containsKey(gid) || s.guestVisits.size() < Math.max(0, dials.boardCap.get())) s.guestVisits.merge(gid, 1, Integer::sum);
        }
        store.save(key);
        if (dials.guestArrivalTitle.get()) {
            IslandRank rank = rankOf(key, guest.getUniqueId());
            guest.showTitle(Title.title(com.skyclone.api.ui.Text.c("&a" + islandTitle(key)),
                    com.skyclone.api.ui.Text.c(rank == IslandRank.GUEST ? "&7You are a guest here" : "&7You are a " + rank.color + rank.label + " &7here"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(1800), Duration.ofMillis(500))));
        }
        for (Player m : membersOnline(key)) {
            if (!notifyOn(m)) continue;
            Msg.send(m, "&b[Island] " + rankOf(key, guest.getUniqueId()).prefix() + "&f" + guest.getName() + " &7is visiting your island!");
            Msg.sound(m, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.4f);
        }
    }

    private void departed(Player guest, String key) {
        for (Player m : membersOnline(key)) {
            if (!notifyOn(m)) continue;
            Msg.send(m, "&b[Island] &f" + guest.getName() + " &7left your island.");
        }
    }

    static boolean notifyOn(Player member) {
        try {
            String v = member.getPersistentDataContainer().get(SETTING_NOTIFY, PersistentDataType.STRING);
            return v == null || !v.equalsIgnoreCase("false");
        } catch (RuntimeException e) { return true; }
    }

    /** Island visitor mode = the strictest {@code island.visitors} setting among online members (menus module). */
    void syncVisitorSetting(String key) {
        List<Player> on = membersOnline(key);
        if (on.isEmpty()) return;
        String mode = null;
        for (Player m : on) {
            if (!may(m, key, CoopPerm.MANAGE)) continue;     // a member without Manage cannot close the island through their own settings
            String v;
            try { v = m.getPersistentDataContainer().get(SETTING_VISITORS, PersistentDataType.STRING); } catch (RuntimeException e) { v = null; }
            if (v == null) continue;
            mode = mode == null ? IslandMath.normalizeVisitors(v) : IslandMath.stricter(mode, v);
        }
        if (mode == null) return;
        IslandSettings s = store.settings(key);
        if (!mode.equals(s.visitors)) {
            synchronized (s) { s.visitors = mode; }
            store.save(key);
        }
    }

    /** Set the visitor mode for a whole co-op: island store + every online member's setting mirror. */
    void setVisitors(String key, String mode) {
        String m = IslandMath.normalizeVisitors(mode);
        IslandSettings s = store.settings(key);
        synchronized (s) { s.visitors = m; }
        store.save(key);
        for (Player p : membersOnline(key)) {
            try { p.getPersistentDataContainer().set(SETTING_VISITORS, PersistentDataType.STRING, m); } catch (RuntimeException ignored) { }
        }
    }

    // =========================================================================================
    // template facts
    // =========================================================================================

    Box templateArea(String name) {
        Layout l = Sky.worlds().layout(ISLAND);
        return l == null ? null : l.area(name);
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < a.size(); i++) out[i] = a.get(i).getAsInt();
        return out;
    }

    /** Read the Community Shop's Island Size and Guests Limit tables from the bank module's data, if shipped. */
    void loadCommunityShopTables() {
        // our own table, generated from research 05 by tools/gen/island/gen_upgrades.py
        try (InputStream in = Sky.plugin().getResource("data/island/upgrades.json")) {
            if (in != null) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                sizeTable = ints(o.getAsJsonObject("island_size").getAsJsonArray("tiers"));
                guestTable = ints(o.getAsJsonObject("guests_limit").getAsJsonArray("tiers"));
            }
        } catch (Exception e) {
            Sky.log().log(Level.WARNING, "[island] data/island/upgrades.json unreadable; using the formula", e);
        }
        // the Community Shop's own table (bank module) wins: it is what the shop sells
        try (InputStream in = Sky.plugin().getResource("data/bank/upgrades.json")) {
            if (in == null) return;
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            JsonArray arr = root.isJsonArray() ? root.getAsJsonArray() : null;
            if (arr == null) return;
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String id = o.has("id") ? o.get("id").getAsString() : "";
                if (!o.has("tiers") || !o.get("tiers").isJsonArray()) continue;
                JsonArray tiers = o.getAsJsonArray("tiers");
                int[] vals = new int[tiers.size()];
                for (int i = 0; i < tiers.size(); i++) {
                    JsonObject t = tiers.get(i).getAsJsonObject();
                    vals[i] = t.has("value") ? (int) Math.round(t.get("value").getAsDouble()) : 0;
                }
                if (id.equals("island_size")) sizeTable = vals;
                else if (id.equals("guests_limit")) guestTable = vals;
            }
        } catch (Exception e) {
            Sky.log().log(Level.INFO, "[island] community shop table not readable (" + e.getMessage() + "); using the research formula");
        }
    }
}
