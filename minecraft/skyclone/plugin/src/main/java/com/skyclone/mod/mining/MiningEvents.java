package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mining events (research 2.10): one per mining island every interval, for a while, announced in chat
 * and on a boss bar. 2x Powder, Tailwind (reference: Gone with the Wind), Better Together, Goblin Raid,
 * Raffle, Mithril Feast (reference: Mithril Gourmand); plus the random Fallen Star and Powder Ghast.
 */
final class MiningEvents implements Listener {

    enum Type {
        DOUBLE_POWDER("2x Powder", "&b", "All powder you earn is doubled."),
        TAILWIND("Tailwind", "&f", "Mine while facing the wind for extra Mining Speed."),
        BETTER_TOGETHER("Better Together", "&d", "Mining Speed and Fortune for every miner near you."),
        GOBLIN_RAID("Goblin Raid", "&c", "Goblins pour into the mines! Kill as many as you can."),
        RAFFLE("Raffle", "&6", "Mining finds Raffle Tickets. More tickets, more prizes."),
        MITHRIL_FEAST("Mithril Feast", "&2", "Mithril sometimes drops Tasty Mithril. Collect a feast!"),
        FALLEN_STAR("Fallen Star", "&e", "A star crashed into the mines. Mine it for Starfall!"),
        POWDER_GHAST("Powder Ghast", "&5", "A Powder Ghast drifts through the mines. Hit it for powder!");

        final String display, color, desc;
        Type(String display, String color, String desc) { this.display = display; this.color = color; this.desc = desc; }
    }

    static final class Running {
        Type type;
        long until;
        org.bukkit.block.BlockFace wind;
        BossBar bar;
        final Set<UUID> viewers = new HashSet<>();
        final Map<UUID, Integer> score = new HashMap<>();
        Location star;
        String starGroup;
        final List<LivingEntity> mobs = new ArrayList<>();
        /** Goblin Raid: the district being raided (area id + display name + its floor box), null = none found. */
        String raidZone, raidZoneName;
        com.skyclone.api.world.gen.Box raidBox;
    }

    private static final List<String> ISLANDS = List.of("dwarven_mines", "crystal_hollows", "glacite_tunnels");

    private final MiningModule m;
    private final Map<String, Running> running = new HashMap<>();
    private final Map<String, Long> nextStart = new HashMap<>();
    private final Running[] specials = new Running[2];   // fallen star, powder ghast (dwarven mines)

    MiningEvents(MiningModule m) {
        this.m = m;
        long now = System.currentTimeMillis();
        for (int i = 0; i < ISLANDS.size(); i++) nextStart.put(ISLANDS.get(i), now + 60_000L * (2 + 3L * i));
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 20L);
    }

    static List<Type> eligible(String island) {
        if ("dwarven_mines".equals(island))
            return List.of(Type.DOUBLE_POWDER, Type.TAILWIND, Type.BETTER_TOGETHER, Type.GOBLIN_RAID, Type.RAFFLE, Type.MITHRIL_FEAST);
        return List.of(Type.DOUBLE_POWDER, Type.TAILWIND, Type.BETTER_TOGETHER);
    }

    Running current(String island) {
        Running r = running.get(island);
        return r != null && r.until > System.currentTimeMillis() ? r : null;
    }

    boolean active(String island, Type t) {
        if (island == null) return false;
        if ("mineshaft".equals(island)) island = "glacite_tunnels";
        Running r = current(island);
        return r != null && r.type == t;
    }

    long millisToNext(String island) {
        Long n = nextStart.get(island);
        return n == null ? -1 : Math.max(0, n - System.currentTimeMillis());
    }

    // ---- effects ------------------------------------------------------------------------------

    double speedMultiplier(Player p) {
        String island = MiningUtil.island(p);
        if (!active(island, Type.TAILWIND)) return 1;
        Running r = current("mineshaft".equals(island) ? "glacite_tunnels" : island);
        if (r == null || r.wind == null) return 1;
        org.bukkit.util.Vector look = p.getLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1e-6) return 1;
        double angle = look.normalize().angle(r.wind.getDirection());
        return angle <= Math.PI / 4 ? 1 + m.dials.windBonus.get() / 100.0 : 1;
    }

    private int nearby(Player p) {
        int n = 0;
        for (Player o : p.getWorld().getPlayers())
            if (o != p && o.getLocation().distanceSquared(p.getLocation()) < 30 * 30) n++;
        return Math.min(5, n);
    }

    double togetherSpeed(Player p) {
        return active(MiningUtil.island(p), Type.BETTER_TOGETHER) ? nearby(p) * m.dials.togetherSpeed.get() : 0;
    }

    double togetherFortune(Player p) {
        return active(MiningUtil.island(p), Type.BETTER_TOGETHER) ? nearby(p) * m.dials.togetherFortune.get() : 0;
    }

    boolean starActive() { return specials[0] != null && specials[0].until > System.currentTimeMillis(); }

    boolean nearStar(Location l, double r) {
        Running s = specials[0];
        return s != null && s.star != null && s.until > System.currentTimeMillis() && s.star.getWorld() == l.getWorld()
                && s.star.distanceSquared(l) <= r * r;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        for (String island : ISLANDS) {
            Running r = running.get(island);
            if (r != null && r.until <= now) { end(island, r); running.remove(island); r = null; }
            if (r == null && m.dials.events.get() && now >= nextStart.getOrDefault(island, now)) {
                List<Type> el = eligible(island);
                start(island, el.get(MiningUtil.rnd().nextInt(el.size())), m.dials.eventDuration.get());
            }
            Running cur = running.get(island);
            if (cur != null) updateBar(island, cur, now);
            if (cur != null && cur.type == Type.GOBLIN_RAID) raidTick(island, cur);
        }
        for (int i = 0; i < specials.length; i++) {
            Running s = specials[i];
            if (s == null) continue;
            boolean over = s.until <= now || (i == 0 && s.starGroup != null && m.temp.standing(s.starGroup) == 0)
                    || (i == 1 && s.mobs.stream().noneMatch(LivingEntity::isValid));
            if (over) { endSpecial(i, s); specials[i] = null; }
        }
    }

    /** Start an event now (admin or timer). */
    void start(String island, Type t, int minutes) {
        Running old = running.remove(island);
        if (old != null) end(island, old);
        Running r = new Running();
        r.type = t;
        long dur = Math.max(1, minutes) * 60_000L;
        if (t == Type.GOBLIN_RAID) dur = Math.min(dur, 5 * 60_000L);   // research: kill within 5 minutes
        r.until = System.currentTimeMillis() + dur;
        if (t == Type.TAILWIND) {
            org.bukkit.block.BlockFace[] f = {org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.WEST};
            r.wind = f[MiningUtil.rnd().nextInt(4)];
        }
        if (t == Type.GOBLIN_RAID) pickRaidZone(island, r);
        r.bar = BossBar.bossBar(Text.c(t.color + "&l" + t.display.toUpperCase()), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        running.put(island, r);
        nextStart.put(island, System.currentTimeMillis() + Math.max(m.dials.eventInterval.get(), minutes) * 60_000L);
        for (Player p : players(island)) {
            if (!MiningUtil.setting(p, MiningUtil.SETTING_EVENT_ALERTS)) continue;   // the boss bar still shows
            List<String> lines = new ArrayList<>();
            lines.add("&7" + t.desc);
            if (t == Type.GOBLIN_RAID && r.raidZoneName != null) lines.add("&7Goblins are raiding &b" + r.raidZoneName + "&7!");
            lines.add(t == Type.TAILWIND ? "&7The wind blows &f" + Text.titleCase(r.wind.name()) + "&7." : "&7Ends in &a" + Text.duration(dur / 1000) + "&7.");
            Msg.box(p, t.color + "&l" + t.display.toUpperCase() + " &7event started!", lines);
            Msg.sound(p, Sound.EVENT_RAID_HORN, 1f);
        }
        if (t == Type.GOBLIN_RAID) raidWave(island, r);
    }

    void stop(String island) {
        Running r = running.remove(island);
        if (r != null) end(island, r);
    }

    private void end(String island, Running r) {
        for (UUID u : r.viewers) { Player p = Bukkit.getPlayer(u); if (p != null) p.hideBossBar(r.bar); }
        for (LivingEntity e : r.mobs) if (e.isValid()) Sky.mobs().remove(e);
        if (r.type == Type.GOBLIN_RAID) {
            for (Map.Entry<UUID, Integer> e : r.score.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null || !MiningUtil.ready(p)) continue;
                int k = e.getValue();
                String tier = k >= 20 ? "&6&lGOLD" : k >= 10 ? "&7&lSILVER" : "&c&lBRONZE";
                long powder = k >= 20 ? 2000 : k >= 10 ? 1000 : 500;   // TUNE
                m.hotm.grant(p, "MITHRIL", powder, "goblin raid", true);
                Msg.box(p, "&c&lGOBLIN RAID &7over!", List.of("&7Goblins slain: &a" + k, "&7Reward tier: " + tier, "&2+" + Text.commas(powder) + " Mithril Powder"));
            }
        } else if (r.type == Type.RAFFLE) {
            raffleDraw(r);
        }
        for (Player p : players(island))
            if (MiningUtil.setting(p, MiningUtil.SETTING_EVENT_ALERTS)) Msg.send(p, r.type.color + "&l" + r.type.display.toUpperCase() + " &7event has ended.");
    }

    private void updateBar(String island, Running r, long now) {
        float prog = (float) Math.max(0, Math.min(1, (r.until - now) / (double) Math.max(1, m.dials.eventDuration.get() * 60_000L)));
        r.bar.progress(prog);
        String extra = r.type == Type.TAILWIND ? " &7→ &f" + Text.titleCase(r.wind.name())
                : r.type == Type.GOBLIN_RAID && r.raidZoneName != null ? " &7in &b" + r.raidZoneName : "";
        r.bar.name(Text.c(r.type.color + "&l" + r.type.display.toUpperCase() + extra + " &7" + Text.duration((r.until - now) / 1000)));
        Set<UUID> want = new HashSet<>();
        for (Player p : players(island)) want.add(p.getUniqueId());
        for (UUID u : new HashSet<>(r.viewers)) if (!want.contains(u)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(r.bar);
            r.viewers.remove(u);
        }
        for (UUID u : want) if (r.viewers.add(u)) { Player p = Bukkit.getPlayer(u); if (p != null) p.showBossBar(r.bar); }
    }

    private List<Player> players(String island) {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String i = MiningUtil.island(p);
            if (island.equals(i) || ("glacite_tunnels".equals(island) && "mineshaft".equals(i))) out.add(p);
        }
        return out;
    }

    // ---- goblin raid -----------------------------------------------------------------------------

    /*
     * Wave-5 regression (AI show): the raid used to spawn a Lv90, 15k-HP, 700-damage "Warlord" next to EVERY player
     * on the island at the start, then three Lv40 / 250-damage goblins on every player every 15 s, wherever they
     * stood. A Superior Dragon player (689 HP, 644 Def) mining in the Royal Mines died to the Warlord in 9 s, and six
     * goblins killed the same player in ~16 s while mining; before the first guard it was 11 deaths in 150 s.
     * The reference raid is nothing like that: the boss bar names ONE raided area ("GOBLIN RAID ACTIVE IN <area>"),
     * the raiders live there, and their bestiary family ("Goblin Raiders", NEU mining_3) is mostly Lv5 Weaklings
     * with a Lv60 Battler, a Lv90 Creepertamer and the Lv150 Murderlover / Golem mixed in. So now:
     *  - a raid picks one district ({@link MiningMath#RAID_ZONES}) and spawns ONLY inside it, at least 8 blocks
     *    from every player, and only while someone is there to fight - miners elsewhere are never targeted;
     *  - the roster and weights are {@link MiningMath#RAID_IDS}; at most {@code mining.raid-heavy-max} Lv90+ alive;
     *  - {@code mining.raid-cap} alive in total, {@code mining.raid-wave} per 15 s.
     */

    private void raidTick(String island, Running r) {
        if (System.currentTimeMillis() / 1000 % 15 != 0) return;
        raidWave(island, r);
    }

    /** Pick the raided district: a random {@link MiningMath#RAID_ZONES} entry the island's layout has an area for. */
    private void pickRaidZone(String island, Running r) {
        List<String> zones = new ArrayList<>(MiningMath.RAID_ZONES);
        java.util.Collections.shuffle(zones, MiningUtil.rnd());
        for (String id : zones) {
            com.skyclone.api.world.gen.Box b;
            try { b = Sky.worlds().area(island, id); } catch (RuntimeException e) { b = null; }
            if (b == null) continue;
            r.raidZone = id;
            r.raidBox = b;
            r.raidZoneName = Text.titleCase(id.replace('_', ' '));
            try {
                com.skyclone.api.world.gen.Layout l = Sky.worlds().layout(island);
                if (l != null) for (com.skyclone.api.world.gen.Layout.Zone z : l.zones())
                    if (z.id().equals(id) && z.displayName() != null) { r.raidZoneName = Text.strip(z.displayName()); break; }
            } catch (RuntimeException ignored) { }
            return;
        }
    }

    /** One raid wave: up to {@code mining.raid-wave} raiders inside the raided district, never past the cap. */
    private void raidWave(String island, Running r) {
        r.mobs.removeIf(e -> !e.isValid());
        int n = MiningMath.raidWave(r.mobs.size(), m.dials.raidCap.get(), m.dials.raidWave.get());
        if (n <= 0) return;
        List<Player> present = new ArrayList<>();
        for (Player p : players(island)) if (raidTarget(p, r)) present.add(p);
        if (present.isEmpty()) return;   // nobody there to fight: spawn nothing (and load no chunks)
        if (r.raidBox == null) {   // the island has none of the raid districts: a small light pack on whoever qualifies
            for (Player p : present) spawnNear(p, MiningMath.RAID_IDS[0], Math.min(2, n), r);
            return;
        }
        World w = present.get(0).getWorld();
        int heavy = 0;
        for (LivingEntity e : r.mobs) { MobDef d = Sky.mobs().defOf(e); if (d != null && MiningMath.raidHeavy(d.id)) heavy++; }
        var rnd = MiningUtil.rnd();
        for (int i = 0; i < n; i++) {
            String id = MiningMath.raidPick(rnd.nextDouble(), heavy < m.dials.raidHeavyMax.get());
            if (Sky.mobs().def(id) == null) continue;
            Location spot = raidSpot(w, r.raidBox, present);
            if (spot == null) continue;
            try {
                LivingEntity e = Sky.mobs().spawn(id, spot);
                if (e != null) { r.mobs.add(e); if (MiningMath.raidHeavy(id)) heavy++; }
            } catch (Exception ignored) { }
        }
    }

    /** Raiders never spawn on top of anyone. TUNE */
    static final int RAID_SPAWN_CLEARANCE = 8;

    /** A floor spot inside the raided district: loaded chunk, two blocks of air, not in a SAFE zone, clear of players. */
    private static Location raidSpot(World w, com.skyclone.api.world.gen.Box b, List<Player> players) {
        var rnd = MiningUtil.rnd();
        for (int tries = 0; tries < 24; tries++) {
            int x = b.minX() + rnd.nextInt(Math.max(1, b.maxX() - b.minX() + 1));
            int z = b.minZ() + rnd.nextInt(Math.max(1, b.maxZ() - b.minZ() + 1));
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = b.maxY(); y > b.minY(); y--) {
                if (!w.getBlockAt(x, y - 1, z).getType().isSolid() || !w.getBlockAt(x, y, z).isPassable()
                        || !w.getBlockAt(x, y + 1, z).isPassable()) continue;
                Location l = new Location(w, x + 0.5, y, z + 0.5);
                boolean ok = !safe(l);
                for (Player p : players)
                    if (ok && p.getWorld() == w && p.getLocation().distanceSquared(l) < RAID_SPAWN_CLEARANCE * RAID_SPAWN_CLEARANCE) ok = false;
                if (ok) return l;
                break;   // this column's floor is unusable: try another column
            }
        }
        return null;
    }

    private static boolean safe(Location l) {
        try {
            com.skyclone.api.world.gen.Layout.Zone z = Sky.worlds().zoneAt(l);
            return z != null && z.flags() != null && (z.flags().contains(com.skyclone.api.world.gen.Layout.Zone.SAFE)
                    || z.flags().contains(com.skyclone.api.world.gen.Layout.Zone.NO_MOBS));
        } catch (Throwable ignored) { return false; }
    }

    /** Seconds after a death in which no raid goblins are spawned on the player. TUNE */
    static final long RAID_RESPAWN_GRACE_MS = 30_000L;
    /** Fallback only (no raid district on the island): raiders already round a player that stop more spawning. TUNE */
    static final int RAID_MAX_NEAR = 3;
    /** How far outside the raided district's floor box a player still counts as "at the raid". TUNE */
    static final int RAID_ZONE_MARGIN = 16;
    private final Map<UUID, Long> lastDeath = new HashMap<>();

    @EventHandler
    public void onSkyDeath(SkyEvents.SkyDeath e) {
        if (e.player != null) lastDeath.put(e.player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Who a wave is spawned for: alive, survival/adventure, not in a SAFE / NO_MOBS zone, not within 30 s of a death
     * (every respawn used to walk back into the same pack), and AT the raid - inside the raided district (+16).
     * Without a district (fallback) the old cap of three raiders round the player applies instead.
     */
    private boolean raidTarget(Player p, Running r) {
        if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false;
        Long died = lastDeath.get(p.getUniqueId());
        if (died != null && System.currentTimeMillis() - died < RAID_RESPAWN_GRACE_MS) return false;
        if (safe(p.getLocation())) return false;
        Location l = p.getLocation();
        if (r.raidBox != null) return r.raidBox.grow(RAID_ZONE_MARGIN).contains(l.getX(), l.getY(), l.getZ());
        int near = 0;
        for (LivingEntity e : r.mobs)
            if (e.isValid() && e.getWorld().equals(p.getWorld()) && e.getLocation().distanceSquared(l) < 16 * 16) near++;
        return near < RAID_MAX_NEAR;
    }

    private void spawnNear(Player p, String mob, int n, Running r) {
        if (Sky.mobs().def(mob) == null) return;
        for (int i = 0; i < n; i++) {
            Location spot = groundNear(p.getLocation(), 4, 9);
            if (spot == null) continue;
            try {
                LivingEntity e = Sky.mobs().spawn(mob, spot);
                if (e != null) r.mobs.add(e);
            } catch (Exception ignored) { }
        }
    }

    static Location groundNear(Location c, int min, int max) {
        World w = c.getWorld();
        var rnd = MiningUtil.rnd();
        for (int tries = 0; tries < 16; tries++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            int d = min + rnd.nextInt(Math.max(1, max - min + 1));
            int x = c.getBlockX() + (int) Math.round(Math.cos(a) * d), z = c.getBlockZ() + (int) Math.round(Math.sin(a) * d);
            for (int dy = 3; dy >= -4; dy--) {
                int y = c.getBlockY() + dy;
                Block below = w.getBlockAt(x, y - 1, z);
                if (below.getType().isSolid() && w.getBlockAt(x, y, z).isPassable() && w.getBlockAt(x, y + 1, z).isPassable())
                    return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null) return;
        String island = MiningUtil.island(e.location);
        Running r = island == null ? null : current(island);
        if (r != null && r.type == Type.GOBLIN_RAID && e.def.id.startsWith("mining_raid")) {
            r.score.merge(e.killer.getUniqueId(), 1, Integer::sum);
            m.comms.progress(e.killer, "EVENT", "goblin_raid", 1, null);
        }
        if ("mining_powder_ghast".equals(e.def.id) && MiningUtil.ready(e.killer)) {
            m.hotm.grant(e.killer, "MITHRIL", 600, "powder ghast", true);   // TUNE
            Msg.send(e.killer, "&5&lPOWDER GHAST &7slain! &2+600 Mithril Powder");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(SkyEvents.Damage e) {
        Player p = e.ctx.playerAttacker();
        if (p == null || !(e.ctx.victim instanceof LivingEntity le)) return;
        MobDef d = Sky.mobs().defOf(le);
        if (d != null && "mining_powder_ghast".equals(d.id) && MiningUtil.ready(p)) m.hotm.grant(p, "MITHRIL", 25, "powder ghast hit", true);  // TUNE
    }

    // ---- raffle -----------------------------------------------------------------------------------

    void raffleTicket(Player p) {
        Running r = current("dwarven_mines");
        if (r == null || r.type != Type.RAFFLE) return;
        MiningData d = MiningUtil.data(p);
        String key = Long.toString(r.until);
        if (!key.equals(d.raffleEvent)) { d.raffleEvent = key; d.raffleTickets = 0; m.comms.progress(p, "EVENT", "raffle", 1, null); }
        d.raffleTickets++;
        r.score.merge(p.getUniqueId(), 1, Integer::sum);
        m.comms.progress(p, "RAFFLE", "", 1, null);
        Sky.vitals().flash(p, "&6+1 Raffle Ticket &7(" + d.raffleTickets + ")", 40);
    }

    private void raffleDraw(Running r) {
        int total = r.score.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return;
        int pick = MiningUtil.rnd().nextInt(total);
        UUID grand = null;
        for (Map.Entry<UUID, Integer> e : r.score.entrySet()) { pick -= e.getValue(); if (pick < 0) { grand = e.getKey(); break; } }
        for (Map.Entry<UUID, Integer> e : r.score.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !MiningUtil.ready(p)) continue;
            int rolls = Math.max(1, e.getValue() / 10) + (e.getKey().equals(grand) ? 5 : 0);
            List<String> got = new ArrayList<>();
            for (int i = 0; i < rolls; i++) got.add(m.loot.rollRaffle(p));
            Msg.box(p, "&6&lRAFFLE DRAW", new ArrayList<>(List.of("&7Your tickets: &a" + e.getValue() + (e.getKey().equals(grand) ? " &6&lGRAND PRIZE!" : ""))) {{ addAll(got); }});
        }
    }

    // ---- Fallen Star / Powder Ghast -------------------------------------------------------------

    /** A star crashes near this player (rare per-block trigger, or admin). */
    boolean fallenStar(Player near) {
        if (specials[0] != null) return false;
        Location spot = groundNear(near.getLocation(), 6, 14);
        if (spot == null) return false;
        Running s = new Running();
        s.type = Type.FALLEN_STAR;
        s.until = System.currentTimeMillis() + 10 * 60_000L;
        s.star = spot;
        s.starGroup = "star" + System.currentTimeMillis();
        BlockTable.Def def = TempBlocks.starDef();
        Block c = spot.getBlock();
        for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dy == 1 && (Math.abs(dx) + Math.abs(dz)) > 1) continue;
            Block b = c.getRelative(dx, dy, dz);
            if (!b.isPassable() && !b.getType().isAir()) continue;
            m.temp.place(b, (dx == 0 && dz == 0) ? Material.CRYING_OBSIDIAN : Material.MAGMA_BLOCK, def, s.starGroup);
        }
        specials[0] = s;
        for (Player p : players(MiningUtil.island(spot) == null ? "dwarven_mines" : MiningUtil.island(spot))) {
            if (MiningUtil.setting(p, MiningUtil.SETTING_EVENT_ALERTS)) {
                Msg.send(p, "&e&l☄ FALLEN STAR &7A star crashed down near &f" + spot.getBlockX() + ", " + spot.getBlockY() + ", " + spot.getBlockZ() + "&7! Mine it for &eStarfall&7!");
                Msg.sound(p, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.8f);
            }
            m.comms.progress(p, "EVENT", "fallen_star", 0, null);
        }
        for (int i = 0; i < 2; i++) spawnNear(near, "mining_star_sentry", 1, s);
        return true;
    }

    boolean powderGhast(Player near) {
        if (specials[1] != null) return false;
        Location at = near.getLocation().add(0, 6, 0);
        if (!at.getBlock().isPassable()) at = near.getLocation().add(0, 2, 0);
        Running s = new Running();
        s.type = Type.POWDER_GHAST;
        s.until = System.currentTimeMillis() + 5 * 60_000L;
        try {
            LivingEntity e = Sky.mobs().spawn("mining_powder_ghast", at);
            if (e == null) return false;
            s.mobs.add(e);
        } catch (Exception ex) { return false; }
        specials[1] = s;
        for (Player p : players(MiningUtil.island(near) == null ? "dwarven_mines" : MiningUtil.island(near)))
            if (MiningUtil.setting(p, MiningUtil.SETTING_EVENT_ALERTS)) Msg.send(p, "&5&lPOWDER GHAST &7A Powder Ghast appeared near &f" + near.getName() + "&7! Every hit pays powder.");
        return true;
    }

    private void endSpecial(int i, Running s) {
        if (i == 0 && s.starGroup != null) m.temp.clear(s.starGroup);
        for (LivingEntity e : s.mobs) if (e.isValid()) Sky.mobs().remove(e);
    }

    void shutdown() {
        for (Map.Entry<String, Running> e : running.entrySet()) {
            for (UUID u : e.getValue().viewers) { Player p = Bukkit.getPlayer(u); if (p != null) p.hideBossBar(e.getValue().bar); }
            for (LivingEntity le : e.getValue().mobs) if (le.isValid()) Sky.mobs().remove(le);
        }
        for (int i = 0; i < specials.length; i++) if (specials[i] != null) endSpecial(i, specials[i]);
        m.temp.clear(null);
    }

    List<String> status() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String island : ISLANDS) {
            Running r = current(island);
            out.add(island + ": " + (r == null ? "none, next in " + Text.duration(millisToNext(island) / 1000) : r.type.display
                    + (r.type == Type.GOBLIN_RAID && r.raidZoneName != null ? " in " + r.raidZoneName + ", " + r.mobs.stream().filter(LivingEntity::isValid).count() + " raiders" : "")
                    + " (" + Text.duration((r.until - now) / 1000) + " left)"));
        }
        out.add("fallen star: " + (specials[0] == null ? "none" : "at " + specials[0].star.toVector()));
        out.add("powder ghast: " + (specials[1] == null ? "none" : "alive"));
        return out;
    }

    void giveMithrilFeast(Player p) {
        if (!active(MiningUtil.island(p), Type.MITHRIL_FEAST)) return;
        MiningData d = MiningUtil.data(p);
        d.tastyMithril++;
        Sky.vitals().flash(p, "&2+1 Tasty Mithril &7(" + d.tastyMithril + "/" + m.dials.gourmandTarget.get() + ")", 40);
        if (d.tastyMithril >= m.dials.gourmandTarget.get()) {
            d.tastyMithril = 0;
            m.hotm.grant(p, "MITHRIL", 3000, "mithril feast", true);   // TUNE
            Sky.loot().give(p, "TREASURITE", 1, LootSource.EVENT);
            Msg.box(p, "&2&lMITHRIL FEAST", List.of("&7You served a full feast!", "&2+3,000 Mithril Powder", MiningUtil.itemName("TREASURITE")));
        }
    }
}
