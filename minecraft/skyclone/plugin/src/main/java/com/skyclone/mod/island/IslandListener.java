package com.skyclone.mod.island;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.gen.Box;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Logins, instance builds, guest permissions and the regrowing quarry. */
final class IslandListener implements Listener {

    private static final NamespacedKey NPC_KEY = NamespacedKey.fromString("skyclone:npc");

    private final IslandModule mod;
    private final IslandService svc;
    private final Map<UUID, Long> denyMsg = new HashMap<>();

    IslandListener(IslandModule mod, IslandService svc) {
        this.mod = mod;
        this.svc = svc;
    }

    // =========================================================================================
    // login / logout / builds
    // =========================================================================================

    /** NORMAL runs before core's HIGH handler, which asks {@code home()} where to put the player. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        String key = IslandService.keyOf(p);
        try { svc.store().seen(p.getUniqueId(), p.getName(), key); } catch (RuntimeException ex) { Sky.log().warning("[island] seen failed: " + ex); }
        svc.invalidate(key);
        try { svc.ensureOwner(p, key); } catch (RuntimeException ex) { Sky.log().warning("[island] owner check failed for " + key + ": " + ex); }
        var f =Sky.worlds().ensureInstance(IslandService.ISLAND, key);
        boolean wantHome = svc.dials().firstJoinHome.get()
                && (e.firstJoin || !"last".equalsIgnoreCase(svc.dials().joinLocation.get()));
        if (!f.isDone()) {
            if (wantHome) svc.pendingHome.add(p.getUniqueId());
            Msg.send(p, e.firstJoin ? "&7Your &aPrivate Island &7is being made - you will be taken there the moment it is ready."
                    : "&7Preparing your &aPrivate Island&7...");
        }
        f.whenComplete((loc, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            svc.origin(key);
            if (err != null || loc == null) { svc.pendingHome.remove(p.getUniqueId()); return; }
            if (!p.isOnline()) return;
            svc.spawnHelper(key);
            svc.syncVisitorSetting(key);
            if (svc.pendingHome.remove(p.getUniqueId()) && stillWaiting(p)) {
                svc.goHome(p, e.firstJoin ? () -> mod.welcome(p) : null);
            } else if (e.firstJoin && svc.isOnOwnIsland(p)) {
                mod.welcome(p);
            }
        }));
    }

    /** The player is still where core parked them (limbo, or the hub spawn) - safe to move them home. */
    private static boolean stillWaiting(Player p) {
        String isl = Sky.worlds().islandOf(p.getLocation());
        if (isl == null) return true;
        if (!"hub".equals(isl)) return false;
        Location hub = Sky.worlds().anchor("hub", "spawn");
        return hub != null && hub.getWorld() == p.getWorld() && hub.distanceSquared(p.getLocation()) < 64;
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) {
        svc.pendingHome.remove(e.player.getUniqueId());
        svc.guestAt.remove(e.player.getUniqueId());
        try { svc.syncVisitorSetting(IslandService.keyOf(e.player)); } catch (RuntimeException ignored) { }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        svc.pendingHome.remove(e.getPlayer().getUniqueId());
        svc.guestAt.remove(e.getPlayer().getUniqueId());
        denyMsg.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        if (!IslandService.ISLAND.equals(e.islandId) || e.instanceKey == null) return;
        svc.origin(e.instanceKey);
        if (!svc.membersOnline(e.instanceKey).isEmpty()) svc.spawnHelper(e.instanceKey);
    }

    /**
     * A guest who dies on someone else's island (falls off the edge, mostly) gets back up on THAT island's
     * spawn, not sent across the world to their own (core's default asks {@code home()} = the dead player's
     * island). If they may not be there any more, the enforcement tick sends them off as usual.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onSkyDeath(SkyEvents.SkyDeath e) {
        if (e.respawnAt != null) return;
        Player p = e.player;
        Location at = p.getLocation();
        if (!svc.inIslandWorld(at)) return;
        String key = svc.keyAt(at);
        if (key == null || svc.isMember(p, key)) return;
        IslandSettings st = svc.store().settings(key);
        if (st.banned.contains(p.getUniqueId().toString()) || !IslandMath.guestsAllowed(st.visitors)) return;   // core sends them home
        Location home = svc.homeOf(key);
        if (home != null && IslandService.standable(home)) e.respawnAt = home;
    }

    /**
     * Core's warps "home" and "private_island" (the hub portal, /warp home, Fast Travel) always land on the
     * template {@code spawn} anchor. When the island has a custom /setspawn, the landing is moved there, so every
     * way home agrees with /is (hand-off Request 3, done from this side). Only a landing on EXACTLY the template
     * spawn block is moved, so admin teleports to anywhere else are untouched.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN && e.getCause() != PlayerTeleportEvent.TeleportCause.COMMAND) return;
        Location to = e.getTo();
        if (to == null || !svc.inIslandWorld(to)) return;
        String key = svc.keyAt(to);
        if (key == null || !svc.store().settings(key).homeSet) return;
        Location spawn = svc.spawnOf(key);
        if (spawn == null || spawn.getBlockX() != to.getBlockX() || spawn.getBlockY() != to.getBlockY() || spawn.getBlockZ() != to.getBlockZ()) return;
        Location home = svc.homeOf(key);
        if (home == null || home.getWorld() != to.getWorld()) return;
        if (home.getBlockX() == spawn.getBlockX() && home.getBlockY() == spawn.getBlockY() && home.getBlockZ() == spawn.getBlockZ()) return;
        e.setTo(home);
    }

    // =========================================================================================
    // guests
    // =========================================================================================

    /** Island key the player is a GUEST on at {@code at}, or null (member, admin bypass, not an island). */
    private String guestKey(Player p, Location at) {
        if (at == null || !svc.inIslandWorld(at)) return null;
        if (Sky.worlds().buildBypass(p)) return null;
        String key = svc.keyAt(at);
        if (key == null || svc.isMember(p, key)) return null;
        return key;
    }

    private void deny(Player p, String what) {
        long now = System.currentTimeMillis();
        Long next = denyMsg.get(p.getUniqueId());
        if (next != null && next > now) return;
        denyMsg.put(p.getUniqueId(), now + 2000);
        Msg.error(p, "Guests can't " + what + " on this island.");
    }

    /**
     * HIGHEST: runs after core's HIGH protection, which denies containers and workstations to anyone who
     * cannot build here (so guests by default). This only loosens containers when the owner allows it, and
     * tightens doors/switches/plates when the owner forbids them.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        String mk = memberKey(p, b.getLocation());
        if (mk != null) { memberInteract(e, p, mk, b); return; }
        String key = guestKey(p, b.getLocation());
        if (key == null) return;
        IslandSettings s = svc.store().settings(key);
        IslandRank rank = svc.rankOf(key, p.getUniqueId());   // wave 5b: Island Ranks loosen the guest rules for one player
        Material t = b.getType();
        if (e.getAction() == Action.PHYSICAL) {
            boolean decorPlate = mod.decor() != null && mod.decor().placedAt(key, b) != null;   // parkour plates are for guests
            if (t == Material.FARMLAND || (!rank.doors(s.guestDoors) && !decorPlate && Tag.PRESSURE_PLATES.isTagged(t))) e.setUseInteractedBlock(Event.Result.DENY);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        BlockState st = b.getState(false);
        if (st instanceof InventoryHolder) {
            if (rank.containers(s.guestContainers)) e.setUseInteractedBlock(Event.Result.ALLOW);
            else { e.setUseInteractedBlock(Event.Result.DENY); deny(p, "open containers"); }
            return;
        }
        if (Tag.DOORS.isTagged(t) || Tag.TRAPDOORS.isTagged(t) || Tag.FENCE_GATES.isTagged(t) || Tag.BUTTONS.isTagged(t) || t == Material.LEVER) {
            if (!rank.doors(s.guestDoors)) { e.setUseInteractedBlock(Event.Result.DENY); deny(p, "use doors or switches"); }
        }
    }

    private static boolean isNpc(Entity e) {
        try { return NPC_KEY != null && e.getPersistentDataContainer().has(NPC_KEY, PersistentDataType.STRING); }
        catch (RuntimeException ex) { return false; }
    }

    /** Entities a guest must not touch: stands (minions), frames, paintings, storage carts and farm animals. */
    private static boolean protectedEntity(Entity e) {
        if (isNpc(e)) return false;
        if (e instanceof ArmorStand || e instanceof Hanging || e instanceof StorageMinecart) return true;
        if (e instanceof Animals a) {
            try { return !Sky.mobs().isSkyMob(a); } catch (RuntimeException ex) { return true; }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!protectedEntity(e.getRightClicked())) return;
        if (guestKey(e.getPlayer(), e.getRightClicked().getLocation()) == null) return;
        e.setCancelled(true);
        deny(e.getPlayer(), "touch that");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent e) {
        if (!protectedEntity(e.getRightClicked())) return;
        if (guestKey(e.getPlayer(), e.getRightClicked().getLocation()) == null) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        if (guestKey(e.getPlayer(), e.getRightClicked().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player p = attacker(e.getDamager());
        if (p == null || !protectedEntity(e.getEntity())) return;
        if (guestKey(p, e.getEntity().getLocation()) == null) return;
        e.setCancelled(true);
        deny(p, "hurt that");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = attacker(e.getRemover());
        if (p != null && guestKey(p, e.getEntity().getLocation()) != null) e.setCancelled(true);
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Item it = e.getItem();
        String key = guestKey(p, it.getLocation());
        if (key == null) return;
        if (svc.rankOf(key, p.getUniqueId()).pickup(svc.store().settings(key).guestPickup)) return;
        UUID mine = p.getUniqueId();
        if (mine.equals(it.getThrower()) || mine.equals(it.getOwner())) return;
        e.setCancelled(true);
    }

    // =========================================================================================
    // co-op members: the owner's per-member permissions (wave 5)
    // =========================================================================================

    /**
     * Island key when {@code p} is a co-op MEMBER of the island at {@code at} and permissions apply to them
     * (dial on, not in build mode); null otherwise. The owner is returned too - {@code svc.may} lets them do anything.
     */
    private String memberKey(Player p, Location at) {
        if (at == null || !svc.inIslandWorld(at)) return null;
        if (!svc.dials().coopPermissions.get() || Sky.worlds().buildBypass(p)) return null;
        String key = svc.keyAt(at);
        return key != null && svc.isMember(p, key) ? key : null;
    }

    /** True (and tells them, throttled) when a member lacks {@code perm} on the island at {@code at}. */
    private boolean lacks(Player p, Location at, CoopPerm perm) {
        String key = memberKey(p, at);
        if (key == null || svc.may(p, key, perm)) return false;
        denyMember(p, perm);
        return true;
    }

    private void denyMember(Player p, CoopPerm perm) {
        long now = System.currentTimeMillis();
        Long next = denyMsg.get(p.getUniqueId());
        if (next != null && next > now) return;
        denyMsg.put(p.getUniqueId(), now + 2000);
        Msg.error(p, "The island owner has not given you the \"" + perm.label + "\" permission. &7(/is coop)");
    }

    /**
     * Containers for members: core (HIGH) already denied them to a member with neither block permission (canBuild is
     * false for them); this re-allows them when the Containers toggle is on, and denies them otherwise.
     */
    private void memberInteract(PlayerInteractEvent e, Player p, String key, Block b) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (CoopPerm.workstation(b.getType().name())) {
            // wave 5b: a crafting table or an anvil changes nothing in the world; a member who lost both block toggles
            // (so core treats them as a non-builder) still gets to use them on their own island
            if (!svc.canBuild(p, b.getLocation()) && svc.insideBuildSquare(key, b.getLocation()) && !p.isSneaking())
                e.setUseInteractedBlock(Event.Result.ALLOW);
            return;
        }
        if (!(b.getState(false) instanceof InventoryHolder)) return;
        if (svc.may(p, key, CoopPerm.CONTAINERS)) {
            if (!svc.canBuild(p, b.getLocation()) && svc.insideBuildSquare(key, b.getLocation())) e.setUseInteractedBlock(Event.Result.ALLOW);
        } else {
            e.setUseInteractedBlock(Event.Result.DENY);
            denyMember(p, CoopPerm.CONTAINERS);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberPlace(BlockPlaceEvent e) {
        if (lacks(e.getPlayer(), e.getBlock().getLocation(), CoopPerm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberBreak(BlockBreakEvent e) {
        if (lacks(e.getPlayer(), e.getBlock().getLocation(), CoopPerm.BREAK)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberBucketEmpty(PlayerBucketEmptyEvent e) {
        if (lacks(e.getPlayer(), e.getBlock().getLocation(), CoopPerm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberBucketFill(PlayerBucketFillEvent e) {
        if (lacks(e.getPlayer(), e.getBlock().getLocation(), CoopPerm.BREAK)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberSign(SignChangeEvent e) {
        if (lacks(e.getPlayer(), e.getBlock().getLocation(), CoopPerm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null && lacks(e.getPlayer(), e.getEntity().getLocation(), CoopPerm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberEntityPlace(EntityPlaceEvent e) {
        if (e.getPlayer() != null && lacks(e.getPlayer(), e.getEntity().getLocation(), CoopPerm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberHangingBreak(HangingBreakByEntityEvent e) {
        Player p = attacker(e.getRemover());
        if (p != null && lacks(p, e.getEntity().getLocation(), CoopPerm.BREAK)) e.setCancelled(true);
    }

    /** Item frames (rotate / take the item) count as building; armour stands' gear as a container. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberFrame(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Hanging && !isNpc(e.getRightClicked())
                && lacks(e.getPlayer(), e.getRightClicked().getLocation(), CoopPerm.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberStand(PlayerArmorStandManipulateEvent e) {
        if (lacks(e.getPlayer(), e.getRightClicked().getLocation(), CoopPerm.CONTAINERS)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMemberHitDecoration(EntityDamageByEntityEvent e) {
        Entity t = e.getEntity();
        if (!(t instanceof Hanging) && !(t instanceof ArmorStand)) return;
        if (isNpc(t)) return;
        Player p = attacker(e.getDamager());
        if (p != null && lacks(p, t.getLocation(), CoopPerm.BREAK)) e.setCancelled(true);
    }

    // =========================================================================================
    // the quarry grows back
    // =========================================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        int secs = svc.dials().quarryRegenSeconds.get();
        if (secs <= 0) return;
        Block b = e.getBlock();
        if (!svc.inIslandWorld(b.getLocation())) return;
        Material type = b.getType();
        if (!IslandMath.regrows(type.name())) return;
        String key = svc.keyAt(b.getLocation());
        if (key == null) return;
        Box q = svc.templateArea("quarry");
        long[] o = svc.origin(key);
        if (q == null || o == null) return;
        int rx = (int) (b.getX() - o[0]), rz = (int) (b.getZ() - o[1]);
        if (!q.contains(rx + 0.5, b.getY() + 0.5, rz + 0.5)) return;
        final Location at = b.getLocation();
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            Block now = at.getBlock();
            if (!now.getType().isAir()) return;
            for (Entity ent : at.getWorld().getNearbyEntities(at.clone().add(0.5, 0.5, 0.5), 0.8, 0.8, 0.8))
                if (ent instanceof LivingEntity) return;                     // never entomb someone
            now.setType(type, false);
        }, secs * 20L);
    }
}
