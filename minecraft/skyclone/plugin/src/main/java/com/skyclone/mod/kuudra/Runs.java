package com.skyclone.mod.kuudra;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.kuudra.gen.KuudraArenaGenerator;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Every running hunt, the arena pool, and the listeners that route the world into them. */
final class Runs implements Listener {

    final KuudraModule mod;
    final Map<String, Run> runs = new LinkedHashMap<>();
    final Map<UUID, Run> byPlayer = new HashMap<>();
    final Map<UUID, Run> byEntity = new HashMap<>();
    final Set<String> busy = new HashSet<>();
    /** arena key -> generator version it was built with (table kuudra_arenas). */
    final Map<String, Integer> versions = new HashMap<>();
    private final Map<UUID, Long> dockCooldown = new HashMap<>();
    /** The tier each player last set out on (for {@code /kuudra again} after the hollow has closed). */
    final Map<UUID, String> lastTier = new HashMap<>();
    private final Set<UUID> inPassage = new HashSet<>();
    private int counter;
    private long pollCount;

    Runs(KuudraModule mod) { this.mod = mod; }

    Run of(Player p) { return p == null ? null : byPlayer.get(p.getUniqueId()); }

    /** The hunt this player is still busy in: null when none, or when theirs is already won / failed. */
    Run busyIn(Player p) {
        Run r = of(p);
        return r == null || r.finished() ? null : r;
    }

    void tick() {
        for (Run r : new ArrayList<>(runs.values())) {
            try { r.tick(); } catch (RuntimeException e) { Sky.log().log(Level.WARNING, "[kuudra] run tick", e); }
        }
    }

    void closed(Run r) {
        runs.remove(r.id);
        busy.remove(r.arena.key);
        byEntity.values().removeIf(x -> x == r);
        byPlayer.values().removeIf(x -> x == r);
    }

    void closeAll() { for (Run r : new ArrayList<>(runs.values())) r.close(); }

    // =====================================================================================
    // starting a hunt
    // =====================================================================================

    /** Start a hunt for {@code leader}'s crew. Returns an error to show, or null when it is starting. */
    String start(Player leader, KCatalog.Tier tier, boolean admin) {
        if (tier == null) return "Unknown tier.";
        if (busyIn(leader) != null) return "You are already on a hunt.";
        if (!admin && !mod.crew.isLeader(leader.getUniqueId())) {
            UUID lead = mod.crew.leader(leader.getUniqueId());
            return "Only your crew's leader" + (lead == null ? "" : " (" + KMenus.nameOf(lead) + ")") + " can start a hunt.";
        }
        List<Player> crew = mod.crew.online(leader.getUniqueId());
        if (!crew.contains(leader)) crew.add(0, leader);
        if (crew.size() > KDials.MAX_PARTY.get()) return "Your crew is larger than " + KDials.MAX_PARTY.get() + ".";
        for (Player m : crew) {
            if (!Sky.profiles().isReady(m)) return m.getName() + " is still loading.";
            if (busyIn(m) != null) return m.getName() + " is already on a hunt.";
            if (admin) continue;
            String why = mod.blocker(m, tier);
            if (why != null) return (m == leader ? "" : m.getName() + ": ") + why;
            // a hunter still standing in a finished hollow (victory / failed) may set straight out again
            if (!KDials.QUEUE_ANYWHERE.get() && byPlayer.get(m.getUniqueId()) == null
                    && !"crimson_isle".equals(Sky.worlds().islandOf(m.getLocation())))
                return (m == leader ? "You" : m.getName()) + " must be on the Crimson Isle (/warp kuudra).";
        }
        // "Hunt again": everyone still attached to a finished hunt leaves it first (chests are already stored)
        for (Player m : crew) {
            Run old = byPlayer.get(m.getUniqueId());
            if (old != null) old.leave(m, false);
        }
        String free = null;
        for (int i = 1; i <= Math.max(1, KDials.MAX_ARENAS.get()); i++) if (!busy.contains("arena-" + i)) { free = "arena-" + i; break; }
        final String key = free;
        if (key == null || Sky.worlds().island(KuudraArenaGenerator.ISLAND) == null) {
            // anyone just detached from a finished hollow must not be left standing in it with no hunt
            for (Player m : crew)
                if (of(m) == null && KuudraArenaGenerator.ISLAND.equals(Sky.worlds().islandOf(m.getLocation()))) mod.sendOut(m);
            return key == null ? "Every hollow is busy right now - try again in a minute." : "The hollow is not registered.";
        }
        busy.add(key);
        Arena arena = new Arena(key);
        String id = "kd" + Long.toString(System.currentTimeMillis(), 36) + (++counter);
        Run r = new Run(mod, id, tier, arena, crew, admin);
        runs.put(id, r);
        for (Player m : crew) {
            byPlayer.put(m.getUniqueId(), r);
            lastTier.put(m.getUniqueId(), tier.id);
            Msg.send(m, "&7Preparing &cLeviathan's Hollow &7(" + tier.colored() + "&7)...");
        }
        ensure(key).whenComplete((loc, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (r.phase == Run.Phase.CLOSED) return;
            if (err != null || loc == null) {
                Sky.log().log(Level.WARNING, "[kuudra] arena " + key + " failed", err);
                r.tell("&cThe hollow could not be prepared. Try again.");
                r.close();
                return;
            }
            arena.origin = Sky.worlds().instanceOrigin(KuudraArenaGenerator.ISLAND, key);
            arena.reset();
            Location spawn = arena.anchor("spawn");
            if (spawn == null) { r.tell("&cThe hollow has no arrival point."); r.close(); return; }
            for (Player m : r.players()) { m.setFallDistance(0); m.teleport(spawn); }
            r.begin();
        }));
        return null;
    }

    /** Build the arena if needed (or rebuild it when the generator changed since it was built). */
    private CompletableFuture<Location> ensure(String key) {
        int ver = new KuudraArenaGenerator().version();
        Integer had = versions.get(key);
        if (had != null && had != ver) Sky.worlds().releaseInstance(KuudraArenaGenerator.ISLAND, key);
        return Sky.worlds().ensureInstance(KuudraArenaGenerator.ISLAND, key).thenApply(l -> {
            if (had == null || had != ver) {
                versions.put(key, ver);
                Sky.db().query(c -> {
                    try (var st = c.prepareStatement("INSERT OR REPLACE INTO kuudra_arenas (k, version) VALUES (?, ?)")) {
                        st.setString(1, key);
                        st.setInt(2, ver);
                        st.executeUpdate();
                    }
                    return null;
                });
            }
            return l;
        });
    }

    /** Admin: forget every idle arena so the next hunt rebuilds it. */
    int rebuildIdle() {
        int n = 0;
        for (int i = 1; i <= Math.max(1, KDials.MAX_ARENAS.get()); i++) {
            String k = "arena-" + i;
            if (busy.contains(k)) continue;
            Sky.worlds().releaseInstance(KuudraArenaGenerator.ISLAND, k);
            versions.put(k, -1);
            n++;
        }
        return n;
    }

    /** {@code /kuudra again}: the same tier as this player's last hunt, with their current crew. */
    String again(Player p) {
        Run r = of(p);
        if (r != null) return r.again(p);
        String t = lastTier.get(p.getUniqueId());
        if (t == null) return "You have not hunted since the last restart - pick a tier on the board (/kuudra).";
        return start(p, mod.cat.tier(t), false);
    }

    // =====================================================================================
    // listeners
    // =====================================================================================

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Run r = of(p);
        if (r == null || r.arena.origin == null) return;
        Block b = e.getClickedBlock();
        if (b.getWorld() != r.arena.world()) return;
        if (r.arena.isBallistaBlock(b)) { e.setCancelled(true); r.fire(p); return; }
        if (r.arena.inArea(b.getLocation().add(0.5, 0.5, 0.5), "cannon", 0.6)) { e.setCancelled(true); r.cannon(p); return; }
        Location free = r.arena.dataBlock("kuudra.chest.free"), paid = r.arena.dataBlock("kuudra.chest.paid");
        if ((free != null && free.getBlock().equals(b)) || (paid != null && paid.getBlock().equals(b))) {
            e.setCancelled(true);
            if (r.phase == Run.Phase.VICTORY) mod.menus.chest(p, r.id);
            else Msg.send(p, "&7The reward chests open once the Leviathan falls.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        Run r = byEntity.get(e.mob.getUniqueId());
        if (r != null) { r.onKill(e); return; }
        if (e.killer != null) mod.introKill(e.killer, e.location);       // The Leviathan's Wake: "Stand the Heat"
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        var ctx = e.ctx;
        if (ctx.victim instanceof Player p) {
            Run r = of(p);
            if (r == null) return;
            // wave4-endgame playtest: an arrow already in flight killed the winner after VICTORY. Once a hunt is won
            // or lost, nothing in its hollow can hurt its hunters (stray shots, burning, a Blight's wither).
            if (r.sheltered(p)) { e.setCancelled(true); return; }
            // a frozen hunter (cutscene) cannot dodge, so nothing may hit them
            if (r.frozen(p)) { e.setCancelled(true); return; }
            r.hurt(p, ctx.attacker);
            if (r.secondWind(p, ctx.finalDamage)) e.setCancelled(true);
            return;
        }
        Run r = byEntity.get(ctx.victim.getUniqueId());
        if (r == null || r.boss == null || !ctx.victim.getUniqueId().equals(r.boss.getUniqueId())) return;
        Player hitter = ctx.playerAttacker();
        if (hitter != null && of(hitter) != r) { e.setCancelled(true); return; }
        if (r.phase != Run.Phase.DPS && r.phase != Run.Phase.LAIR) {
            e.setCancelled(true);
            if (hitter != null) Sky.vitals().flash(hitter, r.phase == Run.Phase.BALLISTA ? "&c&lIMMUNE &7- fire the ballista!" : "&c&lIMMUNE", 20);
            return;
        }
        if (r.crawling()) {
            e.setCancelled(true);
            if (hitter != null) Sky.vitals().flash(hitter, "&c&lIMMUNE &7- it is still dragging itself ashore", 20);
            return;
        }
        double hp = Sky.mobs().health(r.boss);
        if (r.lairDue() && hp - ctx.finalDamage < r.lairHp()) {
            ctx.finalDamage = Math.max(0, hp - r.lairHp());
            Bukkit.getScheduler().runTask(Sky.plugin(), r::toLair);
        }
        if (hitter != null) { Run.PS s = r.ps.get(hitter.getUniqueId()); if (s != null) s.damage += Math.max(0, ctx.finalDamage); }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(SkyEvents.SkyDeath e) {
        Run r = of(e.player);
        if (r != null) r.onDeath(e);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Run r = of(p);
        if (r != null) r.leave(p, false);
        mod.crew.quit(p.getUniqueId());
        inPassage.remove(p.getUniqueId());
        dockCooldown.remove(p.getUniqueId());
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        KData d = mod.data(p);
        Scenes.unfreeze(p);                                  // belt and braces: the modifiers are transient anyway
        // a hunter who already cleared the Leviathan has nothing left to prove: finish the questline quietly
        if (d.totalCompletions() > 0 && d.introStep < KIntro.DONE) { d.introStep = KIntro.DONE; d.introduced = true; }
        if (d.ghostRestore != null) {
            try { p.setGameMode(GameMode.valueOf(d.ghostRestore)); } catch (IllegalArgumentException ex) { p.setGameMode(GameMode.SURVIVAL); }
            d.ghostRestore = null;
        } else if (p.getGameMode() == GameMode.SPECTATOR && KuudraArenaGenerator.ISLAND.equals(Sky.worlds().islandOf(p.getLocation()))) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        if (of(p) == null) {
            mod.takeRunItems(p);
            if (KuudraArenaGenerator.ISLAND.equals(Sky.worlds().islandOf(p.getLocation())))
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline() && of(p) == null) mod.sendOut(p); }, 5L);
        }
        int n = d.unclaimed();
        if (n > 0) Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (p.isOnline()) Msg.send(p, "&6You have &e" + n + " &6unclaimed Leviathan chest" + (n == 1 ? "" : "s") + "! &e/kuudra chests");
        }, 60L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if ("KUUDRA_SHOP_ITEM".equals(Sky.items().idOf(e.getItemDrop().getItemStack()))) e.setCancelled(true);
    }

    // ---- the crimson isle dock ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDockClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        dockClick(e.getPlayer(), e.getRightClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDockHit(PrePlayerAttackEntityEvent e) { dockClick(e.getPlayer(), e.getAttacked()); }

    private void dockClick(Player p, Entity clicked) {
        Entity dock = Sky.npcs().entity(KuudraModule.CRIMSON_DOCK_NPC);
        if (dock == null || !dock.equals(clicked)) return;
        long now = System.currentTimeMillis();
        Long last = dockCooldown.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;
        dockCooldown.put(p.getUniqueId(), now);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline() && Sky.profiles().isReady(p)) mod.menus.dock(p); }, 2L);
    }

    /** Every 10 ticks: walking onto the end of the dock opens the hunt menu (once per visit). */
    void dockPoll() {
        if (!KDials.DOCK_TRIGGER.get()) return;
        World w = Bukkit.getWorld("sb_crimson_isle");
        if (w == null) return;
        Box b = Sky.worlds().area("crimson_isle", "leviathan_passage");
        if (b == null) return;
        long now = System.currentTimeMillis();
        boolean refresh = ++pollCount % 4 == 0;                // every 2 s: notice a fresh faction pledge
        for (Player p : w.getPlayers()) {
            if (refresh && Sky.profiles().isReady(p) && of(p) == null && !Sky.npcs().talking(p, KuudraModule.CRIMSON_DOCK_NPC)) mod.introRefresh(p);
            Location l = p.getLocation();
            boolean inside = b.contains(l.getX(), l.getY(), l.getZ());
            UUID u = p.getUniqueId();
            if (!inside) { inPassage.remove(u); continue; }
            if (!inPassage.add(u)) continue;
            Long last = dockCooldown.get(u);
            if (last != null && now - last < 5000) continue;
            dockCooldown.put(u, now);
            if (Sky.profiles().isReady(p) && Sky.menus().current(p) == null) mod.menus.dock(p);
        }
    }

    void suppressDock(Player p) { dockCooldown.put(p.getUniqueId(), System.currentTimeMillis() + 5000); inPassage.add(p.getUniqueId()); }

    // ---- organs in the Gullet ----

    WorldService.BreakHandler breakHandler() {
        return new WorldService.BreakHandler() {
            @Override public int priority() { return 50; }

            @Override public boolean handles(Player p, Block block, Layout.Zone zone) {
                Run r = of(p);
                return r != null && r.stomach && orbIndex(r, block) >= 0;
            }

            @Override public void onBreak(Player p, Block block, BlockBreakEvent event) {
                Run r = of(p);
                if (r != null) r.orbStrike(p, orbIndex(r, block));
            }
        };
    }

    private static int orbIndex(Run r, Block b) {
        for (int i = 0; i < r.orbLocs.size(); i++) {
            Location o = r.orbLocs.get(i);
            if (o.getWorld() == b.getWorld() && o.getBlockX() == b.getX() && o.getBlockY() == b.getY() && o.getBlockZ() == b.getZ()) return i;
        }
        return -1;
    }

    /** Is this living entity part of a hunt (brood or beast)? */
    Run runOf(LivingEntity le) { return le == null ? null : byEntity.get(le.getUniqueId()); }
}
