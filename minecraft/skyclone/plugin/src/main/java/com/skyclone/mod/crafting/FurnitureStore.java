package com.skyclone.mod.crafting;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Wave 5: the contents of storage and showcase furniture ("You can store three pages of items in this chest!",
 * "This weapon rack lets you show off 4 weapons"), one row per PLACED piece in {@code crafting_furniture_store}.
 *
 * <p>Why a table and not the chunk or the player: a co-op member must open a chest another member placed (member
 * data of an offline player is not readable), and an island rebuild must not take the items with the blocks. The
 * row is keyed by the piece's own uuid ({@code Furniture.Placed.pid}).
 *
 * <p>Public-server rules: the in-memory map is the truth once a piece has been opened (one server, one thread);
 * the table is written on every close. Exactly one player may have a piece open at a time ({@link #claim}); a piece
 * can only be picked up EMPTY; a piece whose blocks vanished (island rebuilt) has its contents marked orphaned and
 * handed back to its owner on their next login - removed from the table first, so a crash can lose but never dupe.
 */
final class FurnitureStore {

    private static final Type MAP = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private final Gson gson = new Gson();
    /** pid -&gt; slot key ("p0:12", "s3") -&gt; base64 item. Only pieces that have been loaded. */
    private final Map<String, Map<String, String>> cache = new HashMap<>();
    /** pid -&gt; the menu currently showing it (identity: a page switch replaces it before the old one closes). */
    private final Map<String, Object> viewers = new HashMap<>();
    /** pid -&gt; owner uuid, for the dupe scanner and orphaning. */
    private final Map<String, String> owners = new HashMap<>();
    private final Map<String, List<Consumer<Map<String, String>>>> loading = new HashMap<>();
    private final List<CompletableFuture<?>> pending = new ArrayList<>();
    boolean dbOk;

    void enable() {
        try {
            Sky.db().migrate("crafting", 1,
                    "CREATE TABLE IF NOT EXISTS crafting_furniture_store (pid TEXT PRIMARY KEY, owner TEXT NOT NULL, "
                            + "piece TEXT NOT NULL, world TEXT, x INTEGER, y INTEGER, z INTEGER, contents TEXT NOT NULL, "
                            + "orphaned INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS crafting_furniture_store_owner ON crafting_furniture_store (owner, orphaned)");
            dbOk = true;
        } catch (RuntimeException e) {
            Sky.log().warning("[crafting] furniture storage table: " + e + " - storage furniture will not open");
        }
        try {
            Sky.items().addDupeScanSource("crafting_furniture", this::stacksOf);
        } catch (RuntimeException ignored) {}
    }

    // ---- codec -------------------------------------------------------------------------------------------

    static String encode(ItemStack s) {
        if (s == null || s.getType().isAir() || s.getAmount() <= 0) return null;
        return Base64.getEncoder().encodeToString(s.serializeAsBytes());
    }

    static ItemStack decode(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try { return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)); } catch (RuntimeException e) { return null; }
    }

    // ---- loading -----------------------------------------------------------------------------------------

    boolean loaded(String pid) { return cache.containsKey(pid); }

    /** The contents (mutable, main thread), or null when not loaded yet. */
    Map<String, String> peek(String pid) { return cache.get(pid); }

    /** Load a piece's contents and run {@code then} on the main thread (immediately when already loaded). */
    void load(String pid, String owner, Consumer<Map<String, String>> then) {
        if (owner != null && !owner.isEmpty()) owners.put(pid, owner);
        Map<String, String> have = cache.get(pid);
        if (have != null) { then.accept(have); return; }
        if (!dbOk) { Sky.log().warning("[crafting] furniture storage unavailable (no table)"); return; }
        List<Consumer<Map<String, String>>> wait = loading.get(pid);
        if (wait != null) { wait.add(then); return; }
        List<Consumer<Map<String, String>>> list = new ArrayList<>();
        list.add(then);
        loading.put(pid, list);
        Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT contents FROM crafting_furniture_store WHERE pid = ? AND orphaned = 0")) {
                ps.setString(1, pid);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
            }
        }).whenComplete((json, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            List<Consumer<Map<String, String>>> cbs = loading.remove(pid);
            if (err != null) { Sky.log().warning("[crafting] furniture storage load " + pid + ": " + err); return; }
            Map<String, String> m = cache.get(pid);
            if (m == null) {
                m = new LinkedHashMap<>();
                if (json != null) {
                    try { Map<String, String> r = gson.fromJson(json, MAP); if (r != null) m.putAll(r); }
                    catch (RuntimeException ex) { Sky.log().warning("[crafting] unreadable furniture storage " + pid + ": " + ex); }
                }
                cache.put(pid, m);
            }
            if (cbs != null) for (Consumer<Map<String, String>> cb : cbs) {
                try { cb.accept(m); } catch (RuntimeException ex) { Sky.log().warning("[crafting] furniture storage callback: " + ex); }
            }
        }));
    }

    /** Write a piece's contents (async). An empty piece deletes its row. */
    void save(String pid, String owner, String piece, String world, int x, int y, int z) {
        Map<String, String> m = cache.get(pid);
        if (m == null || !dbOk) return;
        String json = m.isEmpty() ? null : gson.toJson(m);
        long now = System.currentTimeMillis();
        CompletableFuture<?> f = Sky.db().query(c -> {
            if (json == null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM crafting_furniture_store WHERE pid = ? AND orphaned = 0")) {
                    ps.setString(1, pid);
                    return ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO crafting_furniture_store (pid, owner, piece, world, x, y, z, contents, orphaned, updated) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?) ON CONFLICT(pid) DO UPDATE SET contents = excluded.contents, updated = excluded.updated, "
                    + "world = excluded.world, x = excluded.x, y = excluded.y, z = excluded.z")) {
                ps.setString(1, pid);
                ps.setString(2, owner == null ? "" : owner);
                ps.setString(3, piece);
                ps.setString(4, world);
                ps.setInt(5, x);
                ps.setInt(6, y);
                ps.setInt(7, z);
                ps.setString(8, json);
                ps.setLong(9, now);
                return ps.executeUpdate();
            }
        });
        f.whenComplete((r, err) -> { if (err != null) Sky.log().warning("[crafting] furniture storage save " + pid + ": " + err); });
        synchronized (pending) { pending.removeIf(CompletableFuture::isDone); pending.add(f); }
    }

    // ---- one viewer at a time ----------------------------------------------------------------------------

    /** Claim the piece for {@code menu}; false when somebody else has it open. */
    boolean claim(String pid, Object menu, Player p) {
        Object cur = viewers.get(pid);
        UUID who = viewerPlayers.get(pid);
        if (cur != null && cur != menu && !p.getUniqueId().equals(who)) {
            Player other = who == null ? null : Bukkit.getPlayer(who);
            if (other != null && other.isOnline()) return false;     // a quit viewer never holds it hostage
        }
        viewers.put(pid, menu);
        viewerPlayers.put(pid, p.getUniqueId());
        return true;
    }

    /** pid -&gt; the player of {@link #viewers} (a page switch by the SAME player is fine; anyone else waits). */
    private final Map<String, UUID> viewerPlayers = new HashMap<>();

    void release(String pid, Object menu) {
        if (viewers.get(pid) == menu) { viewers.remove(pid); viewerPlayers.remove(pid); }
    }

    boolean inUse(String pid) { return viewers.containsKey(pid); }

    /** Close the piece for whoever has it open (their menu's close handler saves it). */
    void kick(String pid) {
        UUID who = viewerPlayers.get(pid);
        Player v = who == null ? null : Bukkit.getPlayer(who);
        if (v != null && v.isOnline()) v.closeInventory();
        viewers.remove(pid);
        viewerPlayers.remove(pid);
    }

    /** Close every open piece (disable). */
    void kickAll() { for (String pid : new ArrayList<>(viewers.keySet())) kick(pid); }

    // ---- pick-up, orphans --------------------------------------------------------------------------------

    /** Every stack a piece holds (for the furnace-style hand back and tests). */
    static List<ItemStack> stacks(Map<String, String> m) {
        List<ItemStack> out = new ArrayList<>();
        if (m == null) return out;
        for (String v : m.values()) { ItemStack s = decode(v); if (s != null) out.add(s); }
        return out;
    }

    /** Forget a piece that was picked up (it was empty). */
    void forget(String pid) { cache.remove(pid); owners.remove(pid); viewers.remove(pid); viewerPlayers.remove(pid); }

    /**
     * The piece's blocks are gone (island rebuilt, admin build mode): mark its row orphaned so the owner gets the
     * items back on login. Nothing is lost and nothing stays reachable twice.
     */
    void orphan(String pid) {
        kick(pid);          // its viewer's close saves first; the DB thread runs that write before this update
        cache.remove(pid);
        viewers.remove(pid);
        viewerPlayers.remove(pid);
        owners.remove(pid);
        if (!dbOk) return;
        CompletableFuture<?> f = Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE crafting_furniture_store SET orphaned = 1 WHERE pid = ?")) {
                ps.setString(1, pid);
                return ps.executeUpdate();
            }
        });
        synchronized (pending) { pending.add(f); }
    }

    /** On login: take every orphaned row of this player out of the table, then give the items. */
    void claimOrphans(Player p) {
        if (!dbOk || p == null) return;
        String owner = p.getUniqueId().toString();
        Sky.db().transaction(c -> {
            List<String> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT contents FROM crafting_furniture_store WHERE owner = ? AND orphaned = 1")) {
                ps.setString(1, owner);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rows.add(rs.getString(1)); }
            }
            if (!rows.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM crafting_furniture_store WHERE owner = ? AND orphaned = 1")) {
                    ps.setString(1, owner);
                    ps.executeUpdate();
                }
            }
            return rows;
        }).whenComplete((rows, err) -> {
            if (err != null || rows == null || rows.isEmpty()) return;
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                int n = 0;
                for (String json : rows) {
                    Map<String, String> m;
                    try { m = gson.fromJson(json, MAP); } catch (RuntimeException ex) { continue; }
                    for (ItemStack s : stacks(m)) {
                        if (p.isOnline()) { Sky.loot().give(p, s, LootSource.STORAGE); n += s.getAmount(); }
                        else Sky.log().warning("[crafting] orphaned furniture item lost (owner went offline): " + owner + " " + s);
                    }
                }
                if (n > 0) Msg.send(p, "&eThe furniture holding some of your items was removed. &a" + n
                        + " &eitem(s) were returned to you.");
            });
        });
    }

    /** Dupe scanner source: every stack in loaded furniture this player placed. */
    private List<ItemStack> stacksOf(Player p) {
        List<ItemStack> out = new ArrayList<>();
        String u = p.getUniqueId().toString();
        for (Map.Entry<String, Map<String, String>> e : cache.entrySet()) {
            if (u.equals(owners.get(e.getKey()))) out.addAll(stacks(e.getValue()));
        }
        return out;
    }

    int loadedCount() { return cache.size(); }

    /** On disable: wait (bounded) for the last writes so a shutdown right after a close keeps the items. */
    void flush() {
        List<CompletableFuture<?>> l;
        synchronized (pending) { l = new ArrayList<>(pending); pending.clear(); }
        try { CompletableFuture.allOf(l.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS); }
        catch (Exception e) { Sky.log().warning("[crafting] furniture storage flush: " + e); }
    }
}
