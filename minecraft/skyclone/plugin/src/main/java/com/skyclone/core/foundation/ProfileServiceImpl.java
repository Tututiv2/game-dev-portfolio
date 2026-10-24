package com.skyclone.core.foundation;

import com.google.gson.Gson;
import com.skyclone.api.config.Dials;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.profile.ProfileService;
import com.skyclone.api.ui.Text;
import com.skyclone.core.foundation.ProfileModel.LoadedProfile;
import com.skyclone.core.foundation.ProfileModel.OfflineWrite;
import com.skyclone.core.foundation.ProfileModel.ProfileRow;
import com.skyclone.core.foundation.ProfileModel.Session;
import com.skyclone.core.foundation.ProfileModel.Slot;
import com.skyclone.core.foundation.ProfileModel.Snapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Profiles: who is on which profile, and every module's per-member / per-profile JSON.
 *
 * <h2>Load path</h2>
 * AsyncPlayerPreLoginEvent (async thread) queues ONE read on the DB thread and blocks on it —
 * because the DB thread is FIFO, that read always lands after the player's own quit save from a
 * fast relog. The snapshot is attached on PlayerJoinEvent; ProfileReady fires the next tick. If the
 * read fails the login is REFUSED: a player is never let in on empty data that a save would then
 * write over their real profile.
 *
 * <h2>Write path</h2>
 * Modules mutate the live objects. Every {@code profiles.autosave-seconds} the main thread
 * serialises every live object and queues ONE transaction with only the documents whose JSON
 * changed. Quit = ProfileUnload, then the same save, then evict. A failed write marks the slot to
 * be rewritten and dumps the JSON to plugins/SkyClone/rescue/ so nothing is silently lost.
 *
 * <h2>Offline changes</h2>
 * modifyOffline / depositOffline decide ON THE MAIN THREAD whether the player is live. If not,
 * the change is a DB transaction that also records a sequenced {@link OfflineWrite}; a login whose
 * snapshot was read before that write re-applies it on attach (and attach waits for any such write
 * still in flight), so a sale that completes during someone's login is never overwritten.
 */
public final class ProfileServiceImpl implements ProfileService, Listener {

    final JavaPlugin plugin;
    final Logger log;
    final SqliteDatabase db;
    final Gson gson = Gsons.PROFILE;

    final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    final Map<String, LoadedProfile> profiles = new ConcurrentHashMap<>();
    final Map<UUID, Snapshot> pending = new ConcurrentHashMap<>();
    final Map<UUID, List<OfflineWrite>> offlineWrites = new ConcurrentHashMap<>();
    final Map<UUID, AtomicInteger> inflight = new ConcurrentHashMap<>();
    final AtomicLong seq = new AtomicLong();

    final Dials.Dial<Integer> autosaveSeconds;
    final Dials.Dial<Integer> maxProfiles;
    final Dials.Dial<Integer> maxCoopMembers;
    final Dials.Dial<Integer> loadTimeoutSeconds;

    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private BukkitTask autosaveTask;
    private int autosaveCounter;
    volatile long lastSaveMillis;
    volatile int lastSaveDocs;
    volatile long lastSaveDurationMs;

    public ProfileServiceImpl(JavaPlugin plugin, SqliteDatabase db, Dials dials) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.db = db;
        this.autosaveSeconds = dials.integer("profiles.autosave-seconds", 10, "Seconds between profile autosaves (only changed data is written)");
        this.maxProfiles = dials.integer("profiles.max-profiles", 5, "Profiles one player may have (genre: 5 incl. co-ops)");
        this.maxCoopMembers = dials.integer("profiles.max-coop-members", 5, "Members per co-op profile (genre: 5)");
        this.loadTimeoutSeconds = dials.integer("profiles.load-timeout-seconds", 15, "How long a login waits for its profile before being refused");
    }

    // ---- schema --------------------------------------------------------------------------------

    public void migrate() {
        db.migrate("core", 1, ProfileStore.SCHEMA_V1);
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    public void startAutosave() {
        if (autosaveTask != null) return;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            autosaveCounter++;
            if (autosaveCounter >= Math.max(1, autosaveSeconds.get())) {
                autosaveCounter = 0;
                try { saveAll(); } catch (Throwable t) { log.log(Level.SEVERE, "[profiles] autosave failed", t); }
            }
            long now = System.currentTimeMillis();
            pending.values().removeIf(s -> now - s.readAt > 120_000);
        }, 20L, 20L);
    }

    /** Players already online when the plugin enabled (a /reload): load them now, blocking. */
    public void loadOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                Snapshot s = db.transaction(c -> readOrCreate(c, p.getUniqueId(), p.getName())).get(30, TimeUnit.SECONDS);
                attach(p, s);
                log.info("[profiles] loaded already-online player " + p.getName());
            } catch (Throwable t) {
                log.log(Level.SEVERE, "[profiles] could not load already-online player " + p.getName(), t);
                p.kick(Text.c("&cYour SkyBlock profile could not be loaded.\n&7Please rejoin."));
            }
        }
    }

    /** Phase 1 of shutdown, BEFORE modules disable: ProfileUnload for everyone, then save. */
    public void beforeModulesDisable() {
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.uuid);
            if (p != null && !s.unloaded) {
                try { Bukkit.getPluginManager().callEvent(new SkyEvents.ProfileUnload(p)); }
                catch (Throwable t) { log.log(Level.WARNING, "[profiles] ProfileUnload listener threw for " + p.getName(), t); }
            }
            s.unloaded = true;
        }
        saveAll();
    }

    /** Phase 2, after modules disabled: final save (catches anything a module's disable() wrote). */
    public void shutdown() {
        if (autosaveTask != null) { autosaveTask.cancel(); autosaveTask = null; }
        saveAll();
        sessions.clear();
        profiles.clear();
        pending.clear();
    }

    // ---- login ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID u = e.getUniqueId();
        try {
            if (sessions.containsKey(u)) closeExistingSession(u);
            Snapshot s = db.transaction(c -> readOrCreate(c, u, e.getName()))
                    .get(Math.max(3, loadTimeoutSeconds.get()), TimeUnit.SECONDS);
            pending.put(u, s);
        } catch (Throwable t) {
            log.log(Level.SEVERE, "[profiles] could not load profile for " + e.getName() + " (" + u + ") - login refused", t);
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Text.c("&cYour SkyBlock profile could not be loaded.\n&7Please try again in a moment."));
        }
    }

    /** A second login for a uuid that still has a session: save + evict the old one first. */
    private void closeExistingSession(UUID u) throws Exception {
        Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            Player old = Bukkit.getPlayer(u);
            if (old != null) old.kick(Text.c("&cYou logged in from another location."));
            Session s = sessions.get(u);
            if (s != null) unload(s, old, old != null);
            return Boolean.TRUE;
        }).get(10, TimeUnit.SECONDS);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        Snapshot s = pending.remove(u);
        if (sessions.containsKey(u)) {
            // Should be impossible (pre-login closes it). Never attach over a live session.
            log.severe("[profiles] " + p.getName() + " joined while a session was still live; saving it and asking them to rejoin");
            unload(sessions.get(u), null, false);
            s = null;
        }
        if (s == null) {
            log.warning("[profiles] " + p.getName() + " joined without a loaded profile; kicking");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) p.kick(Text.c("&cYour profile did not finish loading.\n&7Please rejoin."));
            });
            return;
        }
        tryAttach(p, s, 0);
    }

    private void tryAttach(Player p, Snapshot s, int attempt) {
        if (!p.isOnline()) return;
        AtomicInteger n = inflight.get(p.getUniqueId());
        if (n != null && n.get() > 0 && attempt < 200) {
            // an offline write for this player is still on the DB thread: wait for it
            Bukkit.getScheduler().runTaskLater(plugin, () -> tryAttach(p, s, attempt + 1), 1L);
            return;
        }
        attach(p, s);
    }

    void attach(Player p, Snapshot s) {
        UUID u = p.getUniqueId();
        LoadedProfile lp = profiles.get(s.profileId);
        if (lp == null) {
            lp = new LoadedProfile(s.profileId, s.profileName, s.mode, s.created);
            for (Map.Entry<String, String> en : s.sharedJson.entrySet()) lp.shared.put(en.getKey(), new Slot(en.getKey(), en.getValue()));
            lp.bank = s.bank;
            lp.bankCap = s.bankCap;
            profiles.put(lp.id, lp);
        }
        // membership list from the DB is authoritative (a co-op invite may have been accepted)
        Set<UUID> fresh = new HashSet<>(s.members);
        fresh.add(u);
        lp.members.retainAll(fresh);
        for (UUID m : fresh) if (!lp.members.contains(m)) lp.members.add(m);

        Session ses = new Session(u, p.getName(), lp, s.firstJoin);
        for (Map.Entry<String, String> en : s.memberJson.entrySet()) ses.member.put(en.getKey(), new Slot(en.getKey(), en.getValue()));
        ses.purse = s.purse;
        ses.bits = s.bits;

        List<OfflineWrite> late = offlineWrites.remove(u);
        if (late != null) {
            for (OfflineWrite w : late) {
                if (w.seq() <= s.seq || !w.profileId().equals(lp.id)) continue;
                if (w.key() != null) {
                    Slot slot = ses.member.computeIfAbsent(w.key(), k -> new Slot(k, null));
                    slot.savedJson = w.json();
                    slot.value = null;
                }
                if (w.purseDelta() != 0) ses.purse += w.purseDelta();
                log.info("[profiles] applied an offline change made during " + p.getName() + "'s login (" +
                        (w.key() != null ? w.key() : "purse " + Coins.formatTenths(w.purseDelta())) + ")");
            }
        }

        lp.online.add(u);
        sessions.put(u, ses);
        if (s.firstJoin) {
            String pid = lp.id;
            db.query(c -> { ProfileStore.markSeen(c, pid, u); return null; });
        }
        Bukkit.getScheduler().runTask(plugin, () -> fireReady(p, ses));
    }

    private void fireReady(Player p, Session ses) {
        if (!p.isOnline() || sessions.get(p.getUniqueId()) != ses) return;
        ses.ready = true;
        try {
            Bukkit.getPluginManager().callEvent(new SkyEvents.ProfileReady(p, ses.firstJoin));
        } catch (Throwable t) {
            log.log(Level.WARNING, "[profiles] ProfileReady listener threw for " + p.getName(), t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) unload(s, e.getPlayer(), true);
    }

    /** ProfileUnload, save, post-quit actions (switch/leave), evict. Main thread. */
    void unload(Session s, Player p, boolean fireEvent) {
        if (s == null || s.evicted) return;
        if (!s.unloaded && fireEvent && p != null) {
            try { Bukkit.getPluginManager().callEvent(new SkyEvents.ProfileUnload(p)); }
            catch (Throwable t) { log.log(Level.WARNING, "[profiles] ProfileUnload listener threw for " + s.name, t); }
        }
        s.unloaded = true;
        s.evicted = true;
        List<PendingWrite> writes = new ArrayList<>();
        collect(s, writes);
        collectShared(s.profile, writes);
        LoadedProfile lp = s.profile;
        sessions.remove(s.uuid, s);
        lp.online.remove(s.uuid);
        if (lp.online.isEmpty()) profiles.remove(lp.id, lp);

        String switchTo = s.switchTo;
        boolean leaving = s.leaving;
        String pid = lp.id;
        String us = s.uuid.toString();
        write(writes, c -> {
            if (switchTo != null && !ProfileStore.switchTo(c, us, switchTo)) {
                log.warning("[profiles] " + s.name + " switched to a profile they are no longer a member of; ignored");
            }
            if (leaving) ProfileStore.leave(c, pid, us);
        }, "quit of " + s.name);
    }

    // ---- DB reads (DB thread) ----------------------------------------------------------------------

    Snapshot readOrCreate(Connection c, UUID u, String playerName) throws SQLException {
        // Every offline write recorded before this point is already in what we are about to read.
        offlineWrites.remove(u);
        return ProfileStore.readOrCreate(c, u, playerName, seq.get(), log);
    }

    // ---- saving --------------------------------------------------------------------------------

    record PendingWrite(Slot slot, String profileId, String uuid, String key, String json) {}

    /** Serialise a session's member slots; adds a write for each changed one. Main thread. */
    void collect(Session s, List<PendingWrite> out) {
        for (Slot slot : s.member.values()) collectSlot(slot, s.profile.id, s.uuid.toString(), out);
    }

    void collectShared(LoadedProfile lp, List<PendingWrite> out) {
        for (Slot slot : lp.shared.values()) collectSlot(slot, lp.id, null, out);
    }

    private void collectSlot(Slot slot, String pid, String uuid, List<PendingWrite> out) {
        Object v = slot.value;
        if (v == null || slot.poisoned) return;
        String json;
        try {
            json = gson.toJson(v);
        } catch (Throwable t) {
            if (warned.add("ser:" + slot.key)) log.log(Level.SEVERE, "[profiles] cannot serialise '" + slot.key + "' ("
                    + v.getClass().getName() + ") - it is NOT being saved", t);
            return;
        }
        if (!slot.forceWrite && json.equals(slot.savedJson)) return;
        slot.savedJson = json;
        slot.forceWrite = false;
        out.add(new PendingWrite(slot, pid, uuid, slot.key, json));
    }

    @FunctionalInterface
    interface ExtraWork { void run(Connection c) throws SQLException; }

    /** Queue one transaction with every write (+ optional extra statements). */
    CompletableFuture<Void> write(List<PendingWrite> writes, ExtraWork extra, String what) {
        if (writes.isEmpty() && extra == null) return CompletableFuture.completedFuture(null);
        long started = System.currentTimeMillis();
        CompletableFuture<Void> f = db.transaction(c -> {
            long now = System.currentTimeMillis();
            if (!writes.isEmpty()) {
                try (PreparedStatement mem = c.prepareStatement(ProfileStore.UPSERT_MEMBER);
                     PreparedStatement sh = c.prepareStatement(ProfileStore.UPSERT_SHARED)) {
                    for (PendingWrite w : writes) {
                        if (w.uuid() != null) {
                            mem.setString(1, w.profileId());
                            mem.setString(2, w.uuid());
                            mem.setString(3, w.key());
                            mem.setString(4, w.json());
                            mem.setLong(5, now);
                            mem.addBatch();
                        } else {
                            sh.setString(1, w.profileId());
                            sh.setString(2, w.key());
                            sh.setString(3, w.json());
                            sh.setLong(4, now);
                            sh.addBatch();
                        }
                    }
                    mem.executeBatch();
                    sh.executeBatch();
                }
            }
            if (extra != null) extra.run(c);
            return null;
        });
        int n = writes.size();
        return f.whenComplete((r, ex) -> {
            if (ex == null) {
                lastSaveMillis = System.currentTimeMillis();
                lastSaveDocs = n;
                lastSaveDurationMs = lastSaveMillis - started;
                return;
            }
            log.log(Level.SEVERE, "[profiles] save failed (" + what + ", " + n + " documents) - rescuing to disk", ex);
            rescue(writes);
            Bukkit.getScheduler().runTask(plugin, () -> { for (PendingWrite w : writes) w.slot().forceWrite = true; });
        });
    }

    private void rescue(List<PendingWrite> writes) {
        try {
            File dir = new File(plugin.getDataFolder(), "rescue");
            dir.mkdirs();
            long now = System.currentTimeMillis();
            for (PendingWrite w : writes) {
                String name = now + "-" + w.profileId() + "-" + (w.uuid() == null ? "shared" : w.uuid()) + "-"
                        + w.key().replaceAll("[^A-Za-z0-9_.-]", "_") + ".json";
                Files.writeString(new File(dir, name).toPath(), w.json(), StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            log.log(Level.SEVERE, "[profiles] rescue write ALSO failed", t);
        }
    }

    /** Everything changed, everyone, one transaction. Main thread. */
    public CompletableFuture<Void> saveAll() {
        List<PendingWrite> writes = new ArrayList<>();
        for (Session s : sessions.values()) collect(s, writes);
        for (LoadedProfile lp : profiles.values()) collectShared(lp, writes);
        return write(writes, null, "autosave");
    }

    @Override
    public void saveNow(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null) saveSession(s);
    }

    CompletableFuture<Void> saveSession(Session s) {
        List<PendingWrite> writes = new ArrayList<>();
        collect(s, writes);
        collectShared(s.profile, writes);
        return write(writes, null, "saveNow " + s.name);
    }

    // ---- data access ---------------------------------------------------------------------------

    Session session(Player p) { return p == null ? null : sessions.get(p.getUniqueId()); }

    Session session(UUID u) { return u == null ? null : sessions.get(u); }

    @Override
    public <T> T member(Player p, String key, Class<T> type) {
        Session s = session(p);
        if (s == null) {
            if (warned.add("unloaded:" + key)) {
                log.log(Level.WARNING, "[profiles] member(" + (p == null ? "null" : p.getName()) + ", '" + key
                        + "') before the profile loaded - returning a throwaway object. Use SkyEvents.ProfileReady.",
                        new Throwable("called from"));
            }
            return newInstance(type);
        }
        return resolve(s.member.computeIfAbsent(key, k -> new Slot(k, null)), type, "member '" + key + "' of " + s.name);
    }

    @Override
    public <T> T shared(Player p, String key, Class<T> type) {
        Session s = session(p);
        if (s == null) {
            if (warned.add("unloaded-shared:" + key)) {
                log.log(Level.WARNING, "[profiles] shared(" + (p == null ? "null" : p.getName()) + ", '" + key
                        + "') before the profile loaded - returning a throwaway object", new Throwable("called from"));
            }
            return newInstance(type);
        }
        return resolve(s.profile.shared.computeIfAbsent(key, k -> new Slot(k, null)), type,
                "shared '" + key + "' of profile " + s.profile.name);
    }

    @SuppressWarnings("unchecked")
    <T> T resolve(Slot slot, Class<T> type, String what) {
        synchronized (slot) {
            Object v = slot.value;
            if (v != null) {
                if (type.isInstance(v)) return (T) v;
                if (warned.add("type:" + slot.key + ":" + type.getName())) {
                    log.warning("[profiles] " + what + " is held as " + v.getClass().getName() + " but asked for as "
                            + type.getName() + " - converting through JSON. Two modules sharing one key?");
                }
                T conv;
                try {
                    conv = gson.fromJson(gson.toJson(v), type);
                } catch (Throwable t) {
                    conv = null;
                }
                if (conv == null) conv = newInstance(type);
                slot.value = conv;
                return conv;
            }
            T obj = null;
            if (slot.savedJson != null) {
                try {
                    obj = gson.fromJson(slot.savedJson, type);
                } catch (Throwable t) {
                    slot.poisoned = true;
                    log.log(Level.SEVERE, "[profiles] " + what + " could not be read as " + type.getSimpleName()
                            + ". The stored JSON is kept untouched and changes to it will NOT be saved this session. JSON: "
                            + abbreviate(slot.savedJson), t);
                }
            }
            if (obj == null) obj = newInstance(type);
            slot.value = obj;
            return obj;
        }
    }

    private static String abbreviate(String s) { return s.length() > 400 ? s.substring(0, 400) + "..." : s; }

    <T> T newInstance(Class<T> type) {
        try {
            Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException e) {
            try {
                return gson.fromJson("{}", type);
            } catch (Throwable t) {
                throw new IllegalArgumentException(type.getName() + " needs a public no-arg constructor for profile data", t);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot create " + type.getName() + ": " + e, e);
        }
    }

    @Override
    public <T> CompletableFuture<T> readOffline(UUID player, String key, Class<T> type) {
        Session s = session(player);
        if (s != null) {
            try {
                T live = resolve(s.member.computeIfAbsent(key, k -> new Slot(k, null)), type, "member '" + key + "' of " + s.name);
                return CompletableFuture.completedFuture(gson.fromJson(gson.toJson(live), type));
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        }
        return db.query(c -> {
            String pid = ProfileStore.activeProfile(c, player);
            if (pid == null) return null;
            String json = ProfileStore.memberJson(c, pid, player, key);
            T obj = json == null ? null : gson.fromJson(json, type);
            return obj == null ? newInstance(type) : obj;
        });
    }

    @Override
    public <T> CompletableFuture<Void> modifyOffline(UUID player, String key, Class<T> type, Consumer<T> change) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable decide = () -> {
            Session s = session(player);
            if (s != null) {
                try {
                    T obj = resolve(s.member.computeIfAbsent(key, k -> new Slot(k, null)), type, "member '" + key + "' of " + s.name);
                    change.accept(obj);
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                    return;
                }
                saveSession(s).whenComplete((r, ex) -> { if (ex != null) result.completeExceptionally(ex); else result.complete(null); });
                return;
            }
            beginOffline(player);
            db.transaction(c -> {
                try {
                    String pid = ProfileStore.activeProfile(c, player);
                    if (pid == null) throw new IllegalStateException("player " + player + " has no profile");
                    String json = ProfileStore.memberJson(c, pid, player, key);
                    T obj = json == null ? null : gson.fromJson(json, type);
                    if (obj == null) obj = newInstance(type);
                    change.accept(obj);
                    String out = gson.toJson(obj);
                    ProfileStore.upsertMember(c, pid, player, key, out);
                    recordOffline(player, new OfflineWrite(seq.incrementAndGet(), pid, key, out, 0));
                    return null;
                } finally {
                    endOffline(player);
                }
            }).whenComplete((r, ex) -> { if (ex != null) result.completeExceptionally(ex); else result.complete(null); });
        };
        if (Bukkit.isPrimaryThread()) decide.run(); else Bukkit.getScheduler().runTask(plugin, decide);
        return result;
    }

    void beginOffline(UUID u) { inflight.computeIfAbsent(u, k -> new AtomicInteger()).incrementAndGet(); }

    void endOffline(UUID u) {
        AtomicInteger n = inflight.get(u);
        if (n != null && n.decrementAndGet() <= 0) inflight.remove(u, n);
    }

    /** DB thread, inside the transaction that made the change. */
    void recordOffline(UUID u, OfflineWrite w) {
        offlineWrites.computeIfAbsent(u, k -> new CopyOnWriteArrayList<>()).add(w);
    }

    long nextSeq() { return seq.incrementAndGet(); }

    @Override public boolean isLoaded(Player p) { return session(p) != null; }

    /** Loaded AND ProfileReady has fired. */
    @Override
    public boolean isReady(Player p) { Session s = session(p); return s != null && s.ready; }

    @Override public String profileId(Player p) { Session s = session(p); return s == null ? null : s.profile.id; }

    @Override public String profileName(Player p) { Session s = session(p); return s == null ? "" : s.profile.name; }

    @Override
    public List<UUID> members(String profileId) {
        LoadedProfile lp = profileId == null ? null : profiles.get(profileId);
        return lp == null ? List.of() : List.copyOf(lp.members);
    }

    @Override
    public CompletableFuture<List<UUID>> membersAsync(String profileId) {
        if (profileId == null) return CompletableFuture.completedFuture(List.of());
        LoadedProfile lp = profiles.get(profileId);
        if (lp != null) return CompletableFuture.completedFuture(List.copyOf(lp.members));
        return db.query(c -> ProfileStore.membersOf(c, profileId));
    }

    @Override public String mode(Player p) { Session s = session(p); return s == null || s.profile.mode == null ? "normal" : s.profile.mode; }

    @Override
    public CompletableFuture<Boolean> addMember(String profileId, UUID player) {
        int maxCoop = Math.max(1, maxCoopMembers.get());
        int maxProf = Math.max(1, maxProfiles.get());
        return db.transaction(c -> ProfileStore.addMember(c, profileId, player, maxCoop, maxProf))
                .thenApply(ok -> {
            if (ok) {
                LoadedProfile lp = profiles.get(profileId);
                if (lp != null && !lp.members.contains(player)) lp.members.add(player);
                log.info("[profiles] " + player + " joined co-op profile " + profileId);
            }
            return ok;
        });
    }

    // ---- profile management (used by ProfileCommands) ----------------------------------------------

    CompletableFuture<List<ProfileRow>> listProfiles(UUID u) {
        return db.query(c -> ProfileStore.listProfiles(c, u));
    }

    /** New empty profile (not active): {id, name}. Fails with a message when the player is at the cap. */
    CompletableFuture<String[]> createExtraProfile(UUID u, String playerName) {
        int max = Math.max(1, maxProfiles.get());
        return db.transaction(c -> {
            if (ProfileStore.profileCount(c, u) >= max) throw new IllegalStateException("You already have the maximum of " + max + " profiles!");
            String id = ProfileStore.createProfile(c, u, playerName, false);
            return new String[]{id, ProfileStore.profileName(c, id)};
        });
    }

    /** Switch: remember the target, kick; the quit save makes it active. */
    void switchProfile(Player p, String profileId, String name) {
        Session s = session(p);
        if (s == null) return;
        s.switchTo = profileId;
        p.kick(Text.c("&aSwitching to profile &e" + name + "&a...\n\n&7Rejoin to continue playing on it."));
    }

    void leaveCoop(Player p) {
        Session s = session(p);
        if (s == null) return;
        s.leaving = true;
        p.kick(Text.c("&eYou left the co-op profile &a" + s.profile.name + "&e.\n\n&7Rejoin to continue on another profile."));
    }

    // ---- diagnostics -------------------------------------------------------------------------------

    public int sessionCount() { return sessions.size(); }

    public int loadedProfileCount() { return profiles.size(); }

    List<String> describe(Player p) {
        Session s = session(p);
        List<String> out = new ArrayList<>();
        if (s == null) { out.add("not loaded"); return out; }
        LoadedProfile lp = s.profile;
        out.add("profile " + lp.name + " (" + lp.id + ") mode=" + lp.mode + " members=" + lp.members.size()
                + " online=" + lp.online.size() + " ready=" + s.ready + " firstJoin=" + s.firstJoin);
        out.add("purse=" + Coins.formatTenths(s.purse) + " bank=" + Coins.formatTenths(lp.bank) + " bits=" + s.bits);
        List<String> mk = new ArrayList<>(s.member.keySet());
        java.util.Collections.sort(mk);
        out.add("member keys (" + mk.size() + "): " + String.join(", ", mk));
        List<String> sk = new ArrayList<>(lp.shared.keySet());
        java.util.Collections.sort(sk);
        out.add("shared keys (" + sk.size() + "): " + String.join(", ", sk));
        for (Slot slot : s.member.values()) if (slot.poisoned) out.add("POISONED member key " + slot.key);
        for (Slot slot : lp.shared.values()) if (slot.poisoned) out.add("POISONED shared key " + slot.key);
        return out;
    }
}
