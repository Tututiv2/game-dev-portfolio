package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.combatzones.gen.EndGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Dragon's Nest: eight Summoning Eyes in the altar wake a dragon (variant rolled by weight),
 * six crystals heal it while they stand, the dragon fights back with rush / fireball / lightning,
 * and on death every participant is paid by loot quality (eyes x 100 + damage-rank bonus).
 * Placed eyes survive a restart (table {@code combatzones_eyes}).
 */
final class DragonFight implements Listener {

    enum State { IDLE, COUNTDOWN, ACTIVE }

    static final String ISLAND = EndGenerator.ID;

    private final CombatzonesModule m;
    private final UUID[] frames = new UUID[8];
    State state = State.IDLE;
    private UUID dragonId;
    DragonMath.Variant variant;
    final Map<UUID, Double> damage = new LinkedHashMap<>();
    final Map<UUID, Integer> eyesBy = new HashMap<>();
    private final List<EnderCrystal> crystals = new ArrayList<>();
    private long lastHit, spawnedAt;
    private int abilityClock;
    private BukkitTask ticker;
    private final Random rng = new Random();
    private final Map<UUID, Long> partHits = new HashMap<>();

    // flight: vanilla AI held in HOVER, the body teleported every tick to DragonFlight's model
    private DragonFlight flight;
    private BukkitTask flightTask;
    private UUID rushTarget;
    private double rushDamage;
    private Location lastSeen;
    private int stuckSeconds, watchdogTrips, flightClock;

    DragonFight(CombatzonesModule m) {
        this.m = m;
        Sky.db().migrate("combatzones", 1, "CREATE TABLE IF NOT EXISTS combatzones_eyes (frame INTEGER PRIMARY KEY, uuid TEXT NOT NULL)");
        Sky.db().query(c -> {
            Map<Integer, UUID> rows = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT frame, uuid FROM combatzones_eyes"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.put(rs.getInt(1), UUID.fromString(rs.getString(2)));
            }
            return rows;
        }).thenAccept(rows -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            rows.forEach((f, u) -> { if (f >= 0 && f < 8) frames[f] = u; });
            if (Sky.worlds().isBuilt(ISLAND)) syncFrames();
        }));
        ticker = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 20L, 20L);
    }

    void shutdown() {
        if (ticker != null) ticker.cancel();
        stopFlight();
        removeCrystals();
        LivingEntity d = dragon();
        if (d != null) Sky.mobs().remove(d);
    }

    // =========================================================================================== altar

    int eyesPlaced() { int n = 0; for (UUID u : frames) if (u != null) n++; return n; }

    int eyesOf(UUID u) { int n = 0; for (UUID f : frames) if (u.equals(f)) n++; return n; }

    /** World locations of the eight frames (null when the layout is missing). */
    List<Location> frameLocations() {
        Layout lay = Sky.worlds().layout(ISLAND);
        World w = Sky.worlds().world(ISLAND);
        List<Location> out = new ArrayList<>();
        if (lay == null || w == null || lay.data().get("altar.dragon.frames") == null) return out;
        for (String s : lay.data().get("altar.dragon.frames").split(";")) {
            String[] p = s.split(",");
            out.add(new Location(w, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
        }
        return out;
    }

    Location podium() {
        Layout lay = Sky.worlds().layout(ISLAND);
        World w = Sky.worlds().world(ISLAND);
        if (lay == null || w == null || lay.data().get("podium") == null) return null;
        String[] p = lay.data().get("podium").split(",");
        return new Location(w, Integer.parseInt(p[0]) + 0.5, Integer.parseInt(p[1]), Integer.parseInt(p[2]) + 0.5);
    }

    void syncFrames() {
        List<Location> fl = frameLocations();
        for (int i = 0; i < fl.size() && i < 8; i++) {
            Block b = fl.get(i).getBlock();
            if (b.getType() != Material.END_PORTAL_FRAME) b.setType(Material.END_PORTAL_FRAME, false);
            if (b.getBlockData() instanceof EndPortalFrame epf) {
                boolean want = frames[i] != null || state == State.ACTIVE || state == State.COUNTDOWN;
                if (epf.hasEye() != want) { epf.setEye(want); b.setBlockData(epf, false); }
            }
        }
    }

    private int frameIndex(Block b) {
        List<Location> fl = frameLocations();
        for (int i = 0; i < fl.size(); i++)
            if (fl.get(i).getBlockX() == b.getX() && fl.get(i).getBlockY() == b.getY() && fl.get(i).getBlockZ() == b.getZ()) return i;
        return -1;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onAltar(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.getClickedBlock().getType() != Material.END_PORTAL_FRAME) return;
        if (!ISLAND.equals(Sky.worlds().islandOf(e.getClickedBlock().getWorld()))) return;
        int idx = frameIndex(e.getClickedBlock());
        if (idx < 0) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        if (state != State.IDLE) { Msg.error(p, "The altar is spent. Wait for the dragon to fall."); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(hand);
        if (frames[idx] != null) {
            boolean mine = frames[idx].equals(p.getUniqueId());
            if ("SUMMONING_EYE".equals(id)) {
                // playtest-endgame: a mis-click on a filled frame while placing used to un-place your eye
                Msg.send(p, "&5☬ &7" + (mine ? "This frame already holds your eye." : "This frame is taken.")
                        + " Place it in an empty frame. &7(&e" + eyesPlaced() + "&7/&a8 &7placed)");
                return;
            }
            if (!mine) { Msg.error(p, "That eye belongs to someone else."); return; }
            if (!"SLEEPING_EYE".equals(id)) {
                // taking an eye back is deliberate: hold the Sleeping Eye it gave you (as its lore says)
                Msg.send(p, "&5☬ &7Your eye rests here. Hold your &5Sleeping Eye &7and right-click to take it back.");
                return;
            }
            takeBack(p, idx);
            return;
        }
        if (!"SUMMONING_EYE".equals(id)) {
            Msg.send(p, "&5☬ &7This socket waits for a &5Summoning Eye&7. (" + eyesPlaced() + "/8 placed)");
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        frames[idx] = p.getUniqueId();
        persist(idx, p.getUniqueId());
        Sky.loot().give(p, "SLEEPING_EYE", 1, LootSource.OTHER);
        m.data(p).eyesPlaced++;
        syncFrames();
        int n = eyesPlaced();
        broadcast("&5☬ &d" + p.getName() + " &5placed a Summoning Eye! &7(&e" + n + "&7/&a8&7)");
        playAll(Sound.BLOCK_END_PORTAL_FRAME_FILL, 1f);
        if (n >= 8) startCountdown();
    }

    /** Return one of the player's eyes from a frame. */
    void takeBack(Player p, int idx) {
        if (Sky.items().count(p, "SLEEPING_EYE") < 1) { Msg.error(p, "You need your Sleeping Eye to take your eye back."); return; }
        Sky.items().remove(p, "SLEEPING_EYE", 1);
        frames[idx] = null;
        persist(idx, null);
        Sky.loot().give(p, "SUMMONING_EYE", 1, LootSource.OTHER);
        syncFrames();
        broadcast("&5☬ &d" + p.getName() + " &5took back a Summoning Eye. &7(&e" + eyesPlaced() + "&7/&a8&7)");
    }

    /** A player who leaves The End before the dragon rises gets every eye back. */
    void refundAll(Player p) {
        if (state != State.IDLE) return;
        boolean any = false;
        for (int i = 0; i < 8; i++) if (p.getUniqueId().equals(frames[i])) {
            int sleeping = Sky.items().count(p, "SLEEPING_EYE");
            if (sleeping < 1) continue;
            Sky.items().remove(p, "SLEEPING_EYE", 1);
            frames[i] = null;
            persist(i, null);
            Sky.loot().give(p, "SUMMONING_EYE", 1, LootSource.OTHER);
            any = true;
        }
        if (any) {
            Msg.send(p, "&5☬ &7You left the nest, so your Summoning Eyes came back to you.");
            if (Sky.worlds().isBuilt(ISLAND)) syncFrames();
        }
    }

    private void persist(int frame, UUID u) {
        Sky.db().query(c -> {
            if (u == null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM combatzones_eyes WHERE frame = ?")) { ps.setInt(1, frame); ps.executeUpdate(); }
            } else {
                try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO combatzones_eyes (frame, uuid) VALUES (?, ?)")) {
                    ps.setInt(1, frame); ps.setString(2, u.toString()); ps.executeUpdate();
                }
            }
            return null;
        });
    }

    private void clearPersisted() {
        Sky.db().query(c -> { try (PreparedStatement ps = c.prepareStatement("DELETE FROM combatzones_eyes")) { ps.executeUpdate(); } return null; });
    }

    // =========================================================================================== spawn

    void startCountdown() {
        if (state != State.IDLE) return;
        state = State.COUNTDOWN;
        eyesBy.clear();
        for (UUID u : frames) if (u != null) eyesBy.merge(u, 1, Integer::sum);
        clearPersisted();
        // the sleeping eyes of everyone who placed become remnants
        for (UUID u : eyesBy.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            int n = Math.min(eyesBy.get(u), Sky.items().count(p, "SLEEPING_EYE"));
            if (n > 0) { Sky.items().remove(p, "SLEEPING_EYE", n); Sky.loot().give(p, "REMNANT_OF_THE_EYE", n, LootSource.OTHER); }
        }
        int secs = Math.max(1, m.dials.dragonCountdown.get());
        String[] lines = {"&5☬ &dThe ground begins to shake as an Ender Dragon rises from the void!",
                "&5☬ &dThe crystals hum and the air grows cold...", "&5☬ &dSomething vast beats its wings below the nest..."};
        broadcast(lines[0]);
        playAll(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> broadcast(lines[1 + rng.nextInt(2)]), secs * 10L);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> spawnDragon(null), secs * 20L);
    }

    /** Spawn now. {@code forced} = a variant id from an admin, else rolled. */
    boolean spawnDragon(String forced) {
        Location at = Sky.worlds().anchor(ISLAND, "boss.dragon");
        Location pod = podium();
        if (at == null || pod == null || !Sky.worlds().isBuilt(ISLAND)) {
            broadcast("&cThe nest is not ready (island not built).");
            resetAltar();
            return false;
        }
        // wave 5: Wyrm Caller (Dragon Essence perk) - each eye placed by a holder adds its share to the Superior weight
        Map<String, Double> weights = m.dials.weights();
        int callerEyes = m.essence == null ? 0 : m.essence.wyrmCallerEyes(eyesBy);
        if (callerEyes > 0) weights.put("superior", DraconicMath.superiorWeight(weights.getOrDefault("superior", 4.0), m.essence.wyrmCallerPct(), callerEyes));
        DragonMath.Variant v = forced != null ? DragonMath.variant(forced) : DragonMath.roll(weights, rng);
        if (v == null) v = DragonMath.VARIANTS.get(0);
        LivingEntity d = Sky.mobs().spawn("end_dragon_" + v.id(), at);
        if (d == null) { broadcast("&cThe dragon failed to rise."); resetAltar(); return false; }
        double hp = v.health() * Math.max(0.01, m.dials.dragonHealthScale.get());
        Sky.mobs().setMaxHealth(d, hp);
        if (d instanceof EnderDragon ed) {
            try { ed.setPodium(pod.clone().add(0, 1, 0)); } catch (Throwable ignored) {}
            // HOVER: Paper does not move a hovering dragon at all, so our flight is the only mover
            try { ed.setPhase(EnderDragon.Phase.HOVER); } catch (Throwable ignored) {}
        }
        variant = v;
        dragonId = d.getUniqueId();
        state = State.ACTIVE;
        damage.clear();
        lastHit = spawnedAt = System.currentTimeMillis();
        abilityClock = 0;
        if (eyesBy.isEmpty()) for (UUID u : frames) if (u != null) eyesBy.merge(u, 1, Integer::sum);
        for (int i = 0; i < 8; i++) frames[i] = null;
        syncFrames();
        spawnCrystals(d);
        startFlight(d, pod, v);
        char col = DragonMath.readable(v.color());
        for (Player p : players()) {
            p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&" + col + "&l" + v.name().toUpperCase() + " DRAGON"),
                    Text.c("&7has risen from the void")));
        }
        broadcast("&5☬ &" + col + "&lThe " + v.name() + " Dragon &dhas spawned!");
        playAll(Sound.ENTITY_ENDER_DRAGON_GROWL, 1f);
        return true;
    }

    private void spawnCrystals(LivingEntity d) {
        removeCrystals();
        for (Location l : Sky.worlds().anchors(ISLAND, "crystal.")) {
            EnderCrystal c = l.getWorld().spawn(l.clone().add(0, 0, 0), EnderCrystal.class, ec -> {
                ec.setShowingBottom(false);
                ec.setPersistent(false);
                ec.setInvulnerable(false);
            });
            c.setBeamTarget(d.getLocation().getBlock().getLocation());
            crystals.add(c);
        }
    }

    private void removeCrystals() {
        for (EnderCrystal c : crystals) if (c.isValid()) c.remove();
        crystals.clear();
    }

    LivingEntity dragon() {
        if (dragonId == null) return null;
        Entity e = Bukkit.getEntity(dragonId);
        return e instanceof LivingEntity le && le.isValid() ? le : null;
    }

    // =========================================================================================== flight

    private void startFlight(LivingEntity d, Location pod, DragonMath.Variant v) {
        stopFlight();
        Location at = d.getLocation();
        flight = new DragonFlight(pod.getX(), pod.getY(), pod.getZ(), at.getX(), at.getY(), at.getZ(), v.speedMult(), rng);
        lastSeen = at.clone();
        stuckSeconds = watchdogTrips = flightClock = 0;
        flightTask = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::flightTick, 1L, 1L);
    }

    private void stopFlight() {
        if (flightTask != null) flightTask.cancel();
        flightTask = null;
        flight = null;
        rushTarget = null;
        lastSeen = null;
    }

    /** Every tick: advance the model, carry the body to it, land a rush that reached its target. */
    private void flightTick() {
        if (state != State.ACTIVE || flight == null) return;
        LivingEntity d = dragon();
        if (d == null || d.isDead() || !d.hasAI() || Sky.mobs().health(d) <= 0) return;   // dying: core turned its AI off
        if (d instanceof EnderDragon ed) {
            if (ed.getPhase() == EnderDragon.Phase.DYING) return;
            if (ed.getPhase() != EnderDragon.Phase.HOVER) ed.setPhase(EnderDragon.Phase.HOVER);
        }
        Player target = null;
        if (flight.mode() == DragonFlight.Mode.CHARGE) {
            target = rushTarget == null ? null : Bukkit.getPlayer(rushTarget);
            if (target == null || !inArena(target)) { flight.endCharge(); rushTarget = null; target = null; }
            else flight.retarget(target.getX(), target.getY(), target.getZ());
        }
        flight.tick();
        if (rushTarget != null && flight.mode() != DragonFlight.Mode.CHARGE) rushTarget = null;     // ran out of time: missed
        d.teleport(new Location(d.getWorld(), flight.x, flight.y, flight.z, flight.yaw(), 0f));
        if (target != null && rushTarget != null && flight.inContact()) {
            rushHit(d, target);
            flight.endCharge();
            rushTarget = null;
        }
        if (++flightClock % 5 == 0) for (EnderCrystal c : crystals) if (c.isValid()) c.setBeamTarget(d.getLocation().getBlock().getLocation());
    }

    /**
     * Once a second: the no-movement watchdog. If the body has not moved for 5 s outside a perch, or
     * sits far from where the model says, something else is holding it (the playtest's frozen
     * vanilla phase): continue the model from where the body really is; after three trips in a row,
     * put it back over the nest.
     */
    private void watchdog(LivingEntity d) {
        if (flight == null || !d.hasAI()) return;                          // no AI = core is killing it
        Location now = d.getLocation();
        boolean still = lastSeen != null && lastSeen.getWorld() == now.getWorld() && lastSeen.distanceSquared(now) < 0.25
                && flight.mode() != DragonFlight.Mode.PERCH;
        stuckSeconds = still ? stuckSeconds + 1 : 0;
        lastSeen = now.clone();
        double dx = now.getX() - flight.x, dy = now.getY() - flight.y, dz = now.getZ() - flight.z;
        boolean astray = dx * dx + dy * dy + dz * dz > 12 * 12;
        if (stuckSeconds < 5 && !astray) { watchdogTrips = 0; return; }
        watchdogTrips++;
        stuckSeconds = 0;
        Sky.log().warning("[combatzones] dragon watchdog: body " + (astray ? "astray from" : "not moving with") + " its flight at "
                + now.getBlockX() + "," + now.getBlockY() + "," + now.getBlockZ() + " (trip " + watchdogTrips + ")");
        if (d instanceof EnderDragon ed) { try { ed.setPhase(EnderDragon.Phase.HOVER); } catch (Throwable ignored) {} }
        Location home = watchdogTrips >= 3 ? Sky.worlds().anchor(ISLAND, "boss.dragon") : null;
        if (home != null) {
            d.teleport(home);
            flight.resync(home.getX(), home.getY(), home.getZ());
            watchdogTrips = 0;
        } else flight.resync(now.getX(), now.getY(), now.getZ());
    }

    /** In the fight: within the arena radius of the podium (abilities never reach farther). */
    boolean inArena(Player p) {
        Location pod = podium();
        if (p == null || !p.isValid() || p.isDead() || pod == null || p.getWorld() != pod.getWorld()) return false;
        if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false;
        double r = Math.max(8, m.dials.dragonArenaRadius.get());
        return p.getLocation().distanceSquared(pod) <= r * r;
    }

    /** The vanilla AI must never take the body back (its phases path around the world origin). */
    @EventHandler(ignoreCancelled = true)
    public void onPhase(org.bukkit.event.entity.EnderDragonChangePhaseEvent e) {
        if (dragonId == null || !dragonId.equals(e.getEntity().getUniqueId()) || flight == null) return;
        EnderDragon.Phase to = e.getNewPhase();
        if (to != EnderDragon.Phase.HOVER && to != EnderDragon.Phase.DYING) e.setCancelled(true);
    }

    void resetAltar() {
        stopFlight();
        state = State.IDLE;
        dragonId = null;
        variant = null;
        removeCrystals();
        damage.clear();
        eyesBy.clear();
        if (Sky.worlds().isBuilt(ISLAND)) syncFrames();
    }

    /** Admin: fill the remaining frames as if {@code p} placed them (no items taken). */
    void fillFor(Player p, int n) {
        if (state != State.IDLE) return;
        for (int i = 0; i < 8 && n > 0; i++) if (frames[i] == null) { frames[i] = p.getUniqueId(); persist(i, p.getUniqueId()); n--; }
        syncFrames();
        broadcast("&5☬ &d" + p.getName() + " &5filled the altar. &7(&e" + eyesPlaced() + "&7/&a8&7)");
        if (eyesPlaced() >= 8) startCountdown();
    }

    // =========================================================================================== fight

    private void tick() {
        if (state != State.ACTIVE) return;
        LivingEntity d = dragon();
        if (d == null) {
            broadcast("&5☬ &7The dragon vanished into the void.");
            resetAltar();
            return;
        }
        long idle = System.currentTimeMillis() - lastHit;
        if (idle > m.dials.dragonIdleMinutes.get() * 60_000L) {
            broadcast("&5☬ &7Nobody fought the dragon, so it returned to the void.");
            Sky.mobs().remove(d);
            resetAltar();
            return;
        }
        // linked crystals heal
        crystals.removeIf(c -> !c.isValid());
        double max = Sky.mobs().maxHealth(d), hp = Sky.mobs().health(d);
        if (!crystals.isEmpty() && hp < max) {
            double heal = max * m.dials.crystalHeal.get() / 100.0 * crystals.size();
            Sky.mobs().setHealth(d, Math.min(max, hp + heal));
        }
        watchdog(d);
        // special attacks
        if (++abilityClock >= Math.max(2, m.dials.dragonAbilitySeconds.get())) {
            abilityClock = 0;
            ability(d, hp / Math.max(1, max));
        }
    }

    private void ability(LivingEntity d, double frac) {
        List<Player> targets = new ArrayList<>();
        for (Player p : players()) if (inArena(p)) targets.add(p);
        if (targets.isEmpty()) return;
        int pick = rng.nextInt(3);
        if ("unstable".equals(variant.id()) && rng.nextBoolean()) pick = 2;
        // Rush is a real flight at you: only from the orbit, so it never cuts a perch (the melee window) short
        if (pick == 0 && (flight == null || flight.mode() != DragonFlight.Mode.CIRCLE)) pick = 1 + rng.nextInt(2);
        switch (pick) {
            case 0 -> rush(d, targets.get(rng.nextInt(targets.size())), frac);
            case 1 -> fireballs(d, targets, frac);
            default -> lightning(d, targets, frac);
        }
        if ("superior".equals(variant.id()) && rng.nextInt(3) == 0) lightning(d, targets, frac);
    }

    /**
     * Rush: the dragon flies at the target (telegraphed by a portal line and a roar) and only
     * touching it deals the damage. It used to be an instant hit on anyone within 110 blocks of the
     * podium, which kept landing while the vanilla AI had the body frozen far away (playtest-endgame).
     */
    private void rush(LivingEntity d, Player t, double frac) {
        if (flight == null) return;
        rushDamage = DragonMath.abilityDamage(DragonMath.RUSH, variant, frac);
        rushTarget = t.getUniqueId();
        flight.charge(t.getX(), t.getY(), t.getZ());
        Location from = d.getLocation(), to = t.getLocation();
        org.bukkit.util.Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        if (len > 0.1) {
            dir.normalize();
            for (double s = 0; s < len; s += 1.5) t.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(dir.clone().multiply(s)), 2, 0.2, 0.2, 0.2, 0);
        }
        t.getWorld().playSound(from, Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 1.4f);
        Msg.send(t, "&5☬ &" + DragonMath.readable(variant.color()) + variant.name() + " Dragon &7is rushing at you!");
    }

    private void rushHit(LivingEntity d, Player t) {
        Sky.combat().damagePlayer(t, rushDamage, d, DamageContext.Kind.TRUE);
        org.bukkit.util.Vector dir = new org.bukkit.util.Vector(flight.vx, 0, flight.vz);
        if (dir.lengthSquared() < 1e-4) dir = t.getLocation().toVector().subtract(d.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() > 1e-4) t.setVelocity(dir.normalize().multiply(1.4).setY(0.6));
        t.getWorld().playSound(t.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 2f, 0.6f);
        Msg.send(t, "&5☬ &" + DragonMath.readable(variant.color()) + variant.name() + " Dragon &7used &cRush &7on you for &c"
                + Text.commasInt(rushDamage) + " &7damage.");
    }

    private void fireballs(LivingEntity d, List<Player> targets, double frac) {
        double dmg = DragonMath.abilityDamage(DragonMath.FIREBALL, variant, frac);
        for (int i = 0; i < Math.min(3, targets.size()); i++) {
            Player t = targets.get(rng.nextInt(targets.size()));
            Location spot = t.getLocation().clone();
            for (int k = 0; k < 16; k++) {
                double a = k / 16.0 * Math.PI * 2;
                spot.getWorld().spawnParticle(Particle.FLAME, spot.clone().add(Math.cos(a) * 3, 0.2, Math.sin(a) * 3), 1, 0, 0, 0, 0);
            }
            spot.getWorld().playSound(spot, Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                LivingEntity src = dragon();
                if (src == null) return;
                spot.getWorld().spawnParticle(Particle.EXPLOSION, spot, 3, 1, 0.5, 1, 0);
                spot.getWorld().playSound(spot, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1f);
                for (Player p : spot.getWorld().getPlayers())
                    if (p.getLocation().distanceSquared(spot) <= 3.5 * 3.5) Sky.combat().damagePlayer(p, dmg, src, DamageContext.Kind.TRUE);
            }, 30L);
        }
    }

    private void lightning(LivingEntity d, List<Player> targets, double frac) {
        double dmg = DragonMath.abilityDamage(DragonMath.LIGHTNING, variant, frac);
        int n = "unstable".equals(variant.id()) ? Math.min(4, targets.size()) : Math.min(2, targets.size());
        for (int i = 0; i < n; i++) {
            Player t = targets.get(rng.nextInt(targets.size()));
            t.getWorld().strikeLightningEffect(t.getLocation());
            Sky.combat().damagePlayer(t, dmg, d, DamageContext.Kind.TRUE);
        }
    }

    // ---- vanilla dragon / crystal behaviour off: we do the fighting ----------------------------

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof DragonFireball && ISLAND.equals(Sky.worlds().islandOf(e.getEntity().getWorld()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCloud(EntitySpawnEvent e) {
        if (e.getEntity() instanceof AreaEffectCloud && ISLAND.equals(Sky.worlds().islandOf(e.getLocation().getWorld()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (e.getEntity() instanceof EnderCrystal || e.getEntity() instanceof EnderDragon) { e.setCancelled(true); e.blockList().clear(); }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onCrystalHit(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof EnderCrystal c)) return;
        if (!ISLAND.equals(Sky.worlds().islandOf(c.getWorld()))) return;
        e.setCancelled(true);
        if (!crystals.contains(c)) { if (state != State.ACTIVE) c.remove(); return; }
        Player p = null;
        if (e instanceof EntityDamageByEntityEvent ee) {
            if (ee.getDamager() instanceof Player pl) p = pl;
            else if (ee.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl) p = pl;
        }
        if (p == null) return;
        crystals.remove(c);
        c.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, c.getLocation(), 1);
        c.getWorld().playSound(c.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
        c.remove();
        broadcast("&5☬ &d" + p.getName() + " &5destroyed an End Crystal! &7(" + crystals.size() + " left)");
        if (rng.nextDouble() < m.dials.crystalFragmentChance.get()) Sky.loot().give(p, "CRYSTAL_FRAGMENT", 1, LootSource.MOB_DROP);
        damage.merge(p.getUniqueId(), 1.0, Double::sum);       // a crystal counts as taking part
        lastHit = System.currentTimeMillis();
    }

    /** Hits on a dragon PART (wings, head) are routed to the dragon through the sky pipeline. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPartHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof ComplexEntityPart part)) return;
        if (!(part.getParent() instanceof LivingEntity dragon) || !Sky.mobs().isSkyMob(dragon)) return;
        e.setCancelled(true);
        Player p = null;
        DamageContext.Kind kind = DamageContext.Kind.MELEE;
        ItemStack weapon = null;
        if (e.getDamager() instanceof Player pl) { p = pl; weapon = pl.getInventory().getItemInMainHand(); }
        else if (e.getDamager() instanceof Projectile pr) {
            ProjectileSource src = pr.getShooter();
            if (src instanceof Player pl) { p = pl; kind = DamageContext.Kind.PROJECTILE; weapon = pl.getInventory().getItemInMainHand(); }
        }
        if (p == null) return;
        long now = System.currentTimeMillis();
        Long prev = partHits.get(p.getUniqueId());
        if (kind == DamageContext.Kind.MELEE && prev != null && now - prev < 450) return;      // the hit-rate limit core applies to bodies
        partHits.put(p.getUniqueId(), now);
        Sky.combat().playerAttack(p, dragon, weapon, kind);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        if (state != State.ACTIVE || dragonId == null || e.ctx.victim == null) return;
        if (!dragonId.equals(e.ctx.victim.getUniqueId())) return;
        Player p = e.ctx.playerAttacker();
        if (p == null) return;
        damage.merge(p.getUniqueId(), Math.max(0, e.ctx.finalDamage), Double::sum);
        lastHit = System.currentTimeMillis();
    }

    /** Protector-type dragons take less damage. */
    void modify(DamageContext ctx) {
        if (variant == null || dragonId == null || ctx.victim == null || !dragonId.equals(ctx.victim.getUniqueId())) return;
        if (ctx.playerAttacker() != null && variant.damageTakenMult() != 1.0) ctx.multiplicative *= variant.damageTakenMult();
    }

    // =========================================================================================== death + loot

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.def == null || !e.def.id.startsWith("end_dragon_")) return;
        if (dragonId == null || !dragonId.equals(e.mob.getUniqueId())) return;
        DragonMath.Variant v = variant;
        Map<UUID, Integer> place = DragonMath.placements(damage);
        List<Map.Entry<UUID, Double>> top = new ArrayList<>(damage.entrySet());
        top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<UUID> people = new ArrayList<>(place.keySet());
        for (UUID u : eyesBy.keySet()) if (!people.contains(u)) people.add(u);
        String killer = e.killer != null ? e.killer.getName() : "the void";
        SkillService skills = Sky.service(SkillService.class);
        for (UUID u : people) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !Sky.profiles().isReady(p)) continue;
            int eyes = eyesBy.getOrDefault(u, 0);
            int pl = place.getOrDefault(u, 0);
            int q = DragonMath.quality(eyes, pl);
            DragonMath.Award a = DragonMath.award(v.id(), q, eyes, rng, Sky.items()::exists, Math.max(1, m.dials.dragonMaxFragments.get()));
            String loot;
            if (a.item() != null) {
                Sky.loot().give(p, a.item(), 1, LootSource.MOB_DROP);
                String nm = Sky.items().exists(a.item()) ? Sky.items().def(a.item()).rarity.code() + Text.strip(Sky.items().def(a.item()).name) : a.item();
                loot = nm;
                if (q >= 400) Msg.rareDrop(p, "&d&lDRAGON DROP!", nm, 0);
            } else {
                String frag = v.id().toUpperCase() + "_FRAGMENT";
                if (Sky.items().exists(frag)) Sky.loot().give(p, frag, a.fragments(), LootSource.MOB_DROP);
                loot = "&5" + a.fragments() + "x " + (Sky.items().exists(frag) ? Text.strip(Sky.items().def(frag).name) : frag);
            }
            Sky.loot().give(p, "ENDER_PEARL", 1 + rng.nextInt(4), LootSource.MOB_DROP);
            if (Sky.items().exists("ENCHANTED_ENDER_PEARL")) Sky.loot().give(p, "ENCHANTED_ENDER_PEARL", 1, LootSource.MOB_DROP);
            if (e.killer == null || !e.killer.getUniqueId().equals(u)) skills.addXp(p, SkillType.COMBAT, e.def.combatXp, "dragon:" + v.id());
            CzData d = m.data(p);
            d.dragonKills.merge(v.id(), 1, Integer::sum);
            if (pl > 0 && (d.bestPlacement == 0 || pl < d.bestPlacement)) d.bestPlacement = pl;
            d.bestDragonDamage = Math.max(d.bestDragonDamage, damage.getOrDefault(u, 0.0));
            // wave 5: Dragon Essence by loot quality (research 02: dragons are a source; amount TUNE) + Tavi's last task
            long ess = DraconicMath.dragonEssence(q, m.dials.dragonEssenceDivisor.get());
            if (ess > 0) m.essence.give(p, ess, "dragon:" + v.id());
            m.tavi.dragonSlain(p);
            Sky.service(Features.SkyLevel.class).award(p, "dragon:" + v.id(),
                    v.id().equals("superior") ? m.dials.dragonSbXpSuperior.get() : m.dials.dragonSbXp.get(),
                    "Slay " + ("AEIOUaeiou".indexOf(v.name().charAt(0)) >= 0 ? "an " : "a ") + v.name() + " Dragon");   // "an Unstable / Old Dragon"
            List<String> body = new ArrayList<>();
            body.add("&e" + v.name() + " Dragon &7slain by &a" + killer);
            body.add("");
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                OfflineName on = new OfflineName(top.get(i).getKey());
                body.add("  " + new String[]{"&e&l1st", "&6&l2nd", "&c&l3rd"}[i] + " Damager &7- &f" + on.name + " &7- &e" + Text.commasInt(top.get(i).getValue()));
            }
            body.add("");
            body.add("&7Your Damage: &e" + Text.commasInt(damage.getOrDefault(u, 0.0)) + (pl > 0 ? " &7(Position #" + pl + ")" : ""));
            body.add("&7Eyes Placed: &d" + eyes + " &8| &7Loot Quality: &b" + q);
            body.add("&7Loot: " + loot + (ess > 0 ? " &8+ &d" + Text.commasInt(ess) + " Dragon Essence" : ""));
            Msg.box(p, "&6&lDRAGON DOWN!", body);
            Sky.profiles().saveNow(p);
        }
        stopFlight();
        removeCrystals();
        dragonId = null;
        state = State.IDLE;
        variant = null;
        damage.clear();
        eyesBy.clear();
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (Sky.worlds().isBuilt(ISLAND)) syncFrames(); }, 40L);
    }

    private record OfflineName(String name) {
        OfflineName(UUID u) { this(nameOf(u)); }
        static String nameOf(UUID u) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) return p.getName();
            String n = Bukkit.getOfflinePlayer(u).getName();
            return n == null ? "?" : n;
        }
    }

    // =========================================================================================== helpers

    List<Player> players() {
        World w = Bukkit.getWorld("sb_" + ISLAND);
        return w == null ? List.of() : new ArrayList<>(w.getPlayers());
    }

    void broadcast(String s) { for (Player p : players()) Msg.send(p, s); }

    void playAll(Sound s, float pitch) { for (Player p : players()) Msg.sound(p, s, pitch); }

    /** Sidebar lines while in The End. */
    List<String> sidebar() {
        List<String> out = new ArrayList<>();
        if (state == State.ACTIVE) {
            LivingEntity d = dragon();
            if (d != null && variant != null)
                // playtest-endgame: dark purple / dark gray were unreadable on the dark sidebar
                out.add("&" + DragonMath.readable(variant.color()) + variant.name() + " Dragon &a" + Text.shortNum(Sky.mobs().health(d)) + "&c❤");
        } else if (state == State.COUNTDOWN) out.add("&fDragon: &dRising...");
        else out.add("&fEyes placed: &d" + eyesPlaced() + "&f/&d8");
        return out;
    }

    MobDef defOf(DragonMath.Variant v) { return Sky.mobs().def("end_dragon_" + v.id()); }
}
