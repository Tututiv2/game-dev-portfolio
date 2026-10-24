package com.skyclone.core.ui;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.skyclone.api.Sky;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.npc.NpcService;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Layout;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Clickable NPCs. Bodies are non-persistent and tagged {@code skyclone:npc=<id>}; holograms are
 * TextDisplays tagged {@code skyclone:npc_holo=<id>}. Because nothing persists, a body vanishes when
 * its chunk unloads — a watchdog every 2 s (and every chunk load) re-spawns any NPC whose chunk is
 * loaded but whose body is gone. NPCs never load chunks or create worlds themselves.
 */
public final class NpcServiceImpl implements NpcService, Listener {

    static final NamespacedKey NPC_KEY = NamespacedKey.fromString("skyclone:npc");
    static final NamespacedKey HOLO_KEY = NamespacedKey.fromString("skyclone:npc_holo");

    private static final class Entry {
        final NpcDef def;
        final Consumer<Player> onClick;
        UUID body;
        final List<UUID> holos = new ArrayList<>();
        Location home;        // resolved spawn point (null until resolvable)
        boolean usedFallback;
        /** spawnAt copy: home is fixed by the module, never re-resolved from the island layout. */
        boolean fixed;
        float lastYaw = Float.NaN, lastPitch = Float.NaN;
        Entry(NpcDef def, Consumer<Player> onClick) { this.def = def; this.onClick = onClick; }
    }

    private final JavaPlugin plugin;
    private final Map<String, Entry> npcs = new LinkedHashMap<>();
    private final Map<UUID, String> byBody = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();
    private final Set<String> talking = new HashSet<>();
    private final Set<String> loggedOnce = new HashSet<>();
    private boolean live;           // afterModules has run: spawn on register
    private BukkitTask lookTask, watchTask;

    public NpcServiceImpl(JavaPlugin plugin) { this.plugin = plugin; }

    void start() {
        cleanupAll();
        lookTask = Bukkit.getScheduler().runTaskTimer(plugin, this::lookTick, 20L, 5L);
        watchTask = Bukkit.getScheduler().runTaskTimer(plugin, this::watchdog, 40L, 40L);
    }

    void afterModules() {
        live = true;
        spawnAll();
    }

    void stop() {
        if (lookTask != null) lookTask.cancel();
        if (watchTask != null) watchTask.cancel();
        lookTask = watchTask = null;
        for (Entry e : npcs.values()) despawn(e);
        cleanupAll();
    }

    public static boolean isNpc(Entity e) {
        return e != null && e.getPersistentDataContainer().has(NPC_KEY, PersistentDataType.STRING);
    }

    private static boolean isTagged(Entity e) {
        var pdc = e.getPersistentDataContainer();
        return pdc.has(NPC_KEY, PersistentDataType.STRING) || pdc.has(HOLO_KEY, PersistentDataType.STRING);
    }

    // ---- NpcService ------------------------------------------------------------------------------

    @Override
    public void register(NpcDef def, Consumer<Player> onClick) {
        if (def == null || def.id == null || def.id.isEmpty()) {
            UiUtil.info("NpcService.register called with a def that has no id; ignored");
            return;
        }
        if (npcs.containsKey(def.id)) {
            UiUtil.info("NPC '" + def.id + "' registered twice; keeping the first registration");
            return;
        }
        Entry e = new Entry(def, onClick);
        npcs.put(def.id, e);
        if (live) UiUtil.main(() -> spawn(e));
    }

    @Override public NpcDef def(String id) { Entry e = npcs.get(id); return e == null ? null : e.def; }

    @Override public Collection<NpcDef> all() {
        List<NpcDef> out = new ArrayList<>(npcs.size());
        for (Map.Entry<String, Entry> e : npcs.entrySet()) if (e.getKey().indexOf('#') < 0) out.add(e.getValue().def);
        return Collections.unmodifiableList(out);
    }

    @Override
    public void spawnAt(String npcId, String key, Location at) {
        if (npcId == null || key == null || at == null || at.getWorld() == null) return;
        UiUtil.main(() -> {
            Entry base = npcs.get(npcId);
            if (base == null) { once("spawnAt:" + npcId, "spawnAt: no NPC registered with id '" + npcId + "'"); return; }
            String k = npcId + "#" + key;
            Entry old = npcs.remove(k);
            if (old != null) despawn(old);
            Entry e = new Entry(base.def, base.onClick);
            e.home = at.clone();
            e.fixed = true;
            npcs.put(k, e);
            try { spawn(e); } catch (Throwable t) { UiUtil.warn("spawning NPC " + k, t); }
        });
    }

    @Override
    public void despawn(String npcId, String key) {
        if (npcId == null || key == null) return;
        UiUtil.main(() -> {
            Entry e = npcs.remove(npcId + "#" + key);
            if (e != null) despawn(e);
        });
    }

    @Override
    public boolean talking(Player p, String npcId) {
        return p != null && npcId != null && talking.contains(p.getUniqueId() + "|" + npcId);
    }

    @Override
    public Entity entity(String id) {
        Entry e = npcs.get(id);
        if (e == null || e.body == null) return null;
        Entity ent = Bukkit.getEntity(e.body);
        return ent != null && ent.isValid() ? ent : null;
    }

    @Override
    public void talk(Player p, String npcId, List<String> lines, Runnable then) {
        if (p == null) return;
        String key = p.getUniqueId() + "|" + npcId;
        Entry e = npcs.get(npcId);
        String name = e != null && e.def.name != null ? e.def.name : "&f" + Text.titleCase(npcId == null ? "npc" : npcId);
        talking.add(key);
        List<String> safe = lines == null ? List.of() : lines;
        Msg.npc(p, name, safe, () -> {
            talking.remove(key);
            if (then != null) {
                try { then.run(); } catch (Throwable t) { UiUtil.warn("talk() follow-up for NPC " + npcId, t); }
            }
        });
        // Msg.npc skips the follow-up when the player logs off; never leave the lock behind.
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (!p.isOnline()) talking.remove(key); }, 24L * safe.size() + 40L);
    }

    @Override
    public void respawnAll() {
        UiUtil.main(() -> {
            for (Entry e : npcs.values()) { despawn(e); if (!e.fixed) { e.home = null; e.usedFallback = false; } }
            cleanupAll();
            spawnAll();
        });
    }

    // ---- spawning --------------------------------------------------------------------------------

    private void spawnAll() {
        for (Entry e : npcs.values()) {
            try { spawn(e); } catch (Throwable t) { UiUtil.warn("spawning NPC " + e.def.id, t); }
        }
    }

    /** World of an island if it is loaded right now (never creates or loads one). */
    private static World loadedWorld(String island) {
        if (island == null) return null;
        return Bukkit.getWorld("sb_" + island);
    }

    private boolean instanced(String island) {
        try {
            IslandDef d = Sky.worlds().island(island);
            return d != null && d.instanced;
        } catch (Throwable t) { return false; }
    }

    /** Where the NPC should stand, or null if its island is not available yet. */
    private Location resolve(Entry e) {
        if (e.home != null && e.home.getWorld() != null && Bukkit.getWorld(e.home.getWorld().getUID()) != null) return e.home;
        NpcDef d = e.def;
        if (d.island == null) { once("noisland:" + d.id, "NPC '" + d.id + "' has no island; not spawned"); return null; }
        if (instanced(d.island)) {
            once("instanced:" + d.id, "NPC '" + d.id + "' is on instanced island '" + d.island
                    + "'; core spawns shared-island NPCs only (the owning module must place per-instance NPCs)");
            return null;
        }
        if (loadedWorld(d.island) == null) return null;
        Location loc = null;
        try { loc = d.anchor == null ? null : Sky.worlds().anchor(d.island, d.anchor); } catch (Throwable t) { UiUtil.warn("anchor lookup for NPC " + d.id, t); }
        if (loc == null) {
            Location spawn = null;
            try { spawn = Sky.worlds().anchor(d.island, "spawn"); } catch (Throwable ignored) { }
            if (spawn == null) {
                once("nospawn:" + d.id, "NPC '" + d.id + "': anchor '" + d.anchor + "' and 'spawn' both missing on '" + d.island + "'; not spawned");
                return null;
            }
            int k = Math.abs(d.id.hashCode());
            loc = spawn.clone().add(2 + (k % 5) * 1.5, 0, 2 + ((k / 5) % 5) * 1.5);
            e.usedFallback = true;
            once("fallback:" + d.id, "NPC '" + d.id + "': anchor '" + d.anchor + "' missing on '" + d.island + "'; placed near spawn instead");
        }
        e.home = loc;
        return loc;
    }

    private void spawn(Entry e) {
        if (e.body != null) {
            Entity old = Bukkit.getEntity(e.body);
            if (old != null && old.isValid()) return;
        }
        despawn(e);
        Location loc = resolve(e);
        if (loc == null || loc.getWorld() == null) return;
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return; // watchdog spawns it when loaded
        NpcDef d = e.def;
        Entity body = spawnBody(d, loc);
        if (body == null) return;
        e.body = body.getUniqueId();
        byBody.put(e.body, d.id);
        e.lastYaw = Float.NaN;
        spawnHolograms(e, body, loc);
    }

    private Entity spawnBody(NpcDef d, Location loc) {
        EntityType type = EntityType.MANNEQUIN;
        if (d.entity != null && !d.entity.isBlank() && !d.entity.equalsIgnoreCase("MANNEQUIN")) {
            try {
                EntityType t = EntityType.valueOf(d.entity.trim().toUpperCase(Locale.ROOT));
                if (t.isSpawnable() && t.getEntityClass() != null) type = t;
                else once("type:" + d.id, "NPC '" + d.id + "': entity '" + d.entity + "' is not spawnable; using a mannequin");
            } catch (IllegalArgumentException ex) {
                once("type:" + d.id, "NPC '" + d.id + "': unknown entity '" + d.entity + "'; using a mannequin");
            }
        }
        World w = loc.getWorld();
        try {
            if (type == EntityType.MANNEQUIN) {
                return w.spawn(loc, Mannequin.class, m -> {
                    configure(m, d);
                    m.setImmovable(true);
                    try { m.setDescription(null); } catch (Throwable t) { m.setDescription(net.kyori.adventure.text.Component.empty()); }
                    if (d.skin != null && !d.skin.isBlank()) {
                        try {
                            m.setProfile(ResolvableProfile.resolvableProfile()
                                    .uuid(UUID.nameUUIDFromBytes(d.skin.getBytes(StandardCharsets.UTF_8)))
                                    .addProperty(new ProfileProperty("textures", d.skin,
                                            d.skinSignature == null || d.skinSignature.isBlank() ? null : d.skinSignature))
                                    .build());
                        } catch (Throwable t) {
                            once("skin:" + d.id, "NPC '" + d.id + "': skin could not be applied (" + t.getMessage() + ")");
                        }
                    }
                    hand(m, d);
                });
            }
            Class<? extends Entity> cls = type.getEntityClass();
            return w.spawn(loc, cls, ent -> {
                configure(ent, d);
                if (ent instanceof LivingEntity le) hand(le, d);
            });
        } catch (Throwable t) {
            UiUtil.warn("spawning body for NPC " + d.id, t);
            return null;
        }
    }

    private static void configure(Entity ent, NpcDef d) {
        ent.getPersistentDataContainer().set(NPC_KEY, PersistentDataType.STRING, d.id);
        ent.setPersistent(false);
        ent.setInvulnerable(true);
        ent.setSilent(true);
        ent.setGravity(false);
        ent.setCustomNameVisible(false);
        if (ent instanceof LivingEntity le) {
            le.setAI(false);
            le.setCollidable(false);
            le.setRemoveWhenFarAway(false);
            le.setCanPickupItems(false);
        }
    }

    private static void hand(LivingEntity le, NpcDef d) {
        if (d.hand == null || d.hand.isBlank() || le.getEquipment() == null) return;
        ItemStack it = null;
        try { if (Sky.items().exists(d.hand)) it = Sky.items().create(d.hand); } catch (Throwable ignored) { }
        if (it == null) {
            Material m = Material.matchMaterial(d.hand);
            if (m != null && m.isItem()) it = new ItemStack(m);
        }
        if (it != null) le.getEquipment().setItemInMainHand(it);
    }

    /**
     * The nametag: ONE TextDisplay holding every line (title, extra lines, coloured name, CLICK), so the
     * lines sit at native nametag spacing and can never drift apart. The text grows upward from the
     * display's origin, so the origin is placed just above the head. Full-bright so it reads in caves
     * and at night, shadowed, no background box, never visible through walls.
     */
    private void spawnHolograms(Entry e, Entity body, Location loc) {
        NpcDef d = e.def;
        List<String> lines = NpcHolo.lines(d, e.onClick != null);
        if (lines.isEmpty()) return;
        net.kyori.adventure.text.TextComponent.Builder text = net.kyori.adventure.text.Component.text();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) text.append(net.kyori.adventure.text.Component.newline());
            text.append(Text.c(lines.get(i)));   // each line parsed alone: no colour bleeds into the next
        }
        net.kyori.adventure.text.Component built = text.build();
        double gap = 0.12;
        try { gap = UiDials.npcHoloHeight.get(); } catch (Throwable ignored) { }
        Location at = loc.clone().add(0, body.getHeight() + gap, 0);
        at.setYaw(0); at.setPitch(0);
        try {
            TextDisplay td = loc.getWorld().spawn(at, TextDisplay.class, t -> {
                t.getPersistentDataContainer().set(HOLO_KEY, PersistentDataType.STRING, d.id);
                t.setPersistent(false);
                t.text(built);
                t.setBillboard(Display.Billboard.CENTER);
                t.setSeeThrough(false);
                t.setShadowed(true);
                t.setDefaultBackground(false);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.setLineWidth(400);   // never wrap a long title
                t.setBrightness(new Display.Brightness(15, 15));
                t.setGravity(false);
                t.setInvulnerable(true);
            });
            e.holos.add(td.getUniqueId());
        } catch (Throwable t) {
            UiUtil.warn("spawning hologram for NPC " + d.id, t);
        }
    }

    private void despawn(Entry e) {
        if (e.body != null) {
            byBody.remove(e.body);
            Entity b = Bukkit.getEntity(e.body);
            if (b != null) b.remove();
            e.body = null;
        }
        for (UUID h : e.holos) {
            Entity t = Bukkit.getEntity(h);
            if (t != null) t.remove();
        }
        e.holos.clear();
    }

    /** Remove every tagged entity that is not one of our live ones (strays from a crash or reload). */
    private void cleanupAll() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity ent : w.getEntities()) removeIfStray(ent);
        }
    }

    private void removeIfStray(Entity ent) {
        if (!isTagged(ent)) return;
        if (isLive(ent.getUniqueId())) return;
        ent.remove();
    }

    private boolean isLive(UUID id) {
        if (byBody.containsKey(id)) return true;
        for (Entry e : npcs.values()) if (e.holos.contains(id)) return true;
        return false;
    }

    // ---- tasks -----------------------------------------------------------------------------------

    /** Re-spawn NPCs whose chunk is loaded but whose body is missing (chunk unload kills non-persistent bodies). */
    private void watchdog() {
        for (Entry e : npcs.values()) {
            try {
                Entity b = e.body == null ? null : Bukkit.getEntity(e.body);
                if (b != null && b.isValid()) {
                    // keep holograms honest too
                    boolean holosOk = true;
                    for (UUID h : e.holos) { Entity t = Bukkit.getEntity(h); if (t == null || !t.isValid()) { holosOk = false; break; } }
                    if (holosOk) continue;
                    despawn(e);   // a hologram went missing: rebuild the whole NPC
                }
                spawn(e);
            } catch (Throwable t) {
                UiUtil.warn("NPC watchdog for " + e.def.id, t);
            }
        }
    }

    private void lookTick() {
        double range = 8;
        try { range = UiDials.npcLookRange.get(); } catch (Throwable ignored) { }
        for (Entry e : npcs.values()) {
            if (!e.def.lookAtPlayers || e.body == null) continue;
            Entity b = Bukkit.getEntity(e.body);
            if (b == null || !b.isValid()) continue;
            Location at = b.getLocation();
            Player near = null;
            double best = range * range;
            for (Player p : at.getNearbyPlayers(range)) {
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                double d2 = p.getLocation().distanceSquared(at);
                if (d2 <= best) { best = d2; near = p; }
            }
            float yaw, pitch;
            if (near != null) {
                double eyeY = at.getY() + (b instanceof LivingEntity le ? le.getEyeHeight() : 1.6);
                Location eye = near.getEyeLocation();
                double dx = eye.getX() - at.getX(), dz = eye.getZ() - at.getZ(), dy = eye.getY() - eyeY;
                double horiz = Math.sqrt(dx * dx + dz * dz);
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.001, horiz)));
            } else {
                yaw = e.home != null ? e.home.getYaw() : at.getYaw();
                pitch = 0;
            }
            if (Math.abs(yaw - e.lastYaw) < 1f && Math.abs(pitch - e.lastPitch) < 1f) continue;
            e.lastYaw = yaw;
            e.lastPitch = pitch;
            b.setRotation(yaw, pitch);
            if (b instanceof LivingEntity le) le.setBodyYaw(yaw);
        }
    }

    // ---- clicks ----------------------------------------------------------------------------------

    private void click(Player p, String id) {
        Entry e = npcs.get(id);
        if (e == null) return;
        long now = System.currentTimeMillis();
        int gap = 500;
        try { gap = UiDials.npcDebounceMs.get(); } catch (Throwable ignored) { }
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < gap) return;
        lastClick.put(p.getUniqueId(), now);
        if (talking.contains(p.getUniqueId() + "|" + id)) return;
        if (e.onClick == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            try { e.onClick.accept(p); }
            catch (Throwable t) {
                UiUtil.warn("NPC '" + id + "' click handler threw for " + p.getName(), t);
                Msg.error(p, "Something went wrong talking to that NPC.");
            }
        });
    }

    private String npcOf(Entity ent) {
        if (ent == null) return null;
        String id = byBody.get(ent.getUniqueId());
        if (id != null) return id;
        if (ent.getPersistentDataContainer().has(NPC_KEY, PersistentDataType.STRING)) return "";   // stray tagged body
        return null;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEntityEvent e) {
        String id = npcOf(e.getRightClicked());
        if (id == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND || id.isEmpty()) return;
        click(e.getPlayer(), id);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (npcOf(e.getRightClicked()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        String id = npcOf(e.getAttacked());
        if (id == null) return;
        e.setCancelled(true);
        if (!id.isEmpty()) click(e.getPlayer(), id);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        String id = npcOf(e.getEntity());
        if (id == null) return;
        e.setCancelled(true);
        if (id.isEmpty() || !(e instanceof EntityDamageByEntityEvent by)) return;
        Player p = null;
        if (by.getDamager() instanceof Player dp) p = dp;
        else if (by.getDamager() instanceof Projectile pr) {
            ProjectileSource src = pr.getShooter();
            if (src instanceof Player sp) p = sp;
        }
        if (p != null) click(p, id);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCombust(EntityCombustEvent e) {
        if (npcOf(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetEvent e) {
        if (npcOf(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (npcOf(e.getRightClicked()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastClick.remove(id);
        String prefix = id + "|";
        talking.removeIf(k -> k.startsWith(prefix));
    }

    // ---- world / chunk ---------------------------------------------------------------------------

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity ent : e.getEntities()) {
            try { removeIfStray(ent); } catch (Throwable ignored) { }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!live) return;
        Chunk c = e.getChunk();
        World w = c.getWorld();
        List<Entry> here = new ArrayList<>();
        for (Entry en : npcs.values()) {
            Location h = en.home;
            if (h != null && w.equals(h.getWorld()) && (h.getBlockX() >> 4) == c.getX() && (h.getBlockZ() >> 4) == c.getZ()) here.add(en);
        }
        if (here.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> { for (Entry en : here) { try { spawn(en); } catch (Throwable t) { UiUtil.warn("NPC chunk spawn " + en.def.id, t); } } });
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        if (!live) return;
        String island;
        try { island = Sky.worlds().islandOf(e.getWorld()); } catch (Throwable t) { return; }
        if (island == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entry en : npcs.values()) {
                if (!island.equals(en.def.island)) continue;
                en.home = null;
                try { spawn(en); } catch (Throwable t) { UiUtil.warn("NPC world-load spawn " + en.def.id, t); }
            }
        });
    }

    // ---- admin / tests ---------------------------------------------------------------------------

    List<String> describeAll() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> me : npcs.entrySet()) {
            Entry e = me.getValue();
            Entity b = body(e);   // per-entry: a spawnAt copy ("id#key") has its own body, not the base id's
            out.add((b != null ? "&a● " : "&c○ ") + "&f" + me.getKey() + " &7" + e.def.island + "/" + e.def.anchor
                    + (e.usedFallback ? " &c(fallback)" : "") + (b == null ? " &8(not spawned)" : ""));
        }
        return out;
    }

    Location homeOf(String id) {
        Entry e = npcs.get(id);
        if (e == null) return null;
        Location l = resolve(e);
        return l == null ? null : l.clone();
    }

    boolean usedFallback(String id) { Entry e = npcs.get(id); return e != null && e.usedFallback; }

    int count() { return npcs.size(); }

    /** Every registered NPC whose island has a layout must find its anchor in it. */
    String checkAnchors() {
        List<String> missing = new ArrayList<>();
        for (Entry e : npcs.values()) {
            NpcDef d = e.def;
            if (d.island == null || d.anchor == null) { missing.add(d.id + "(no island/anchor)"); continue; }
            Layout l;
            try { l = Sky.worlds().layout(d.island); } catch (Throwable t) { l = null; }
            if (l == null) continue;   // island not built yet: nothing to check against
            if (l.anchor(d.anchor) == null) missing.add(d.id + "->" + d.island + "/" + d.anchor);
        }
        if (missing.isEmpty()) return null;
        return missing.size() + " NPC anchor(s) missing: " + String.join(", ", missing.subList(0, Math.min(12, missing.size())))
                + (missing.size() > 12 ? " ..." : "");
    }

    /** NPCs whose chunk is loaded must have a live body. */
    String checkAlive() {
        List<String> dead = new ArrayList<>();
        for (Entry e : npcs.values()) {
            Location h = e.home;
            if (h == null || h.getWorld() == null) continue;
            if (!h.getWorld().isChunkLoaded(h.getBlockX() >> 4, h.getBlockZ() >> 4)) continue;
            if (body(e) == null) dead.add(e.def.id + (e.fixed ? " (copy)" : ""));
        }
        return dead.isEmpty() ? null : "NPCs in loaded chunks without a body: " + String.join(", ", dead);
    }

    private static Entity body(Entry e) {
        if (e.body == null) return null;
        Entity ent = Bukkit.getEntity(e.body);
        return ent != null && ent.isValid() ? ent : null;
    }

    private void once(String key, String msg) {
        if (loggedOnce.add(key)) UiUtil.info(msg);
    }
}
