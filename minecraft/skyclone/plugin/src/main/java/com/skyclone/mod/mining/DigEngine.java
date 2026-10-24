package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Custom block breaking (research 6.1). On mining islands the player's BLOCK_BREAK_SPEED is multiplied
 * by zero, so the client never finishes a block by itself. The server tracks a per-player dig: progress
 * per tick = 1 / ticksToBreak, recomputed EVERY tick (abilities, Deep Market, tool swaps change speed
 * mid-break), the crack animation is sent to everyone within 32 blocks with the miner's entity id, and
 * at 100% the {@link Breaker} pays out.
 */
final class DigEngine implements Listener {

    private static final NamespacedKey MOD_KEY = new NamespacedKey("skyclone", "mining_dig");

    static final class Dig {
        Block block;
        BlockTable.Def def;
        float progress;
        int lastSwing;
        int lastStage = -1;
        Vector spark;          // Precision Mining target, world coordinates
        long bpHint;
    }

    private final MiningModule m;
    private final Map<UUID, Dig> digs = new HashMap<>();
    private final Map<UUID, Boolean> zeroed = new HashMap<>();
    /** Tick of each player's last right-click (its arm swing must not start a dig). */
    private final Map<UUID, Integer> rightClick = new HashMap<>();
    private int tick;
    /** island -> ore boxes, refreshed every 30 s. */
    private final Map<String, List<Box>> oreBoxes = new HashMap<>();
    private long oreBoxesAt;

    DigEngine(MiningModule m) {
        this.m = m;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tickAll, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::attributes, 20L, 10L);
        Sky.worlds().addBreakHandler(new WorldService.BreakHandler() {
            @Override public int priority() { return 100; }
            @Override public boolean handles(Player p, Block block, Layout.Zone zone) { return mineable(p, block, false) != null; }
            @Override public void onBreak(Player p, Block block, BlockBreakEvent event) { vanillaBreak(p, block); }
        });
    }

    // ---- what is mineable ------------------------------------------------------------------

    /** The def for this block if this player may mine it here, else null. {@code tell}: explain why not. */
    BlockTable.Def mineable(Player p, Block b, boolean tell) {
        String island = MiningUtil.island(b.getLocation());
        if (island == null) return null;
        BlockTable.Def def = m.temp.defAt(b);
        if (def == null) {
            if (!"hub".equals(island) && !MiningUtil.MINING_ISLANDS.contains(island)) return null;
            def = m.blocks.lookup(island, b.getType().name());
        }
        if (def == null) return null;     // placeholders (bedrock) are never in the table
        Location l = b.getLocation().add(0.5, 0.5, 0.5);
        // the coal_mine area is where players STAND; its walls and ores sit just outside the box
        if ("hub".equals(island) && !inArea(island, l, "coal_mine", 4)) return null;
        if ((def.areaOnly || m.dials.oreAreasOnly.get()) && !"hub".equals(island) && !inOreArea(island, l)) return null;
        if (!m.dials.allowSafeZones.get()) {
            Layout.Zone z = Sky.worlds().zoneAt(l);
            if (z != null && z.flags() != null && (z.flags().contains(Layout.Zone.SAFE) || z.flags().contains(Layout.Zone.BUILD))) return null;
        }
        return def;
    }

    private boolean inArea(String island, Location l, String name, int margin) {
        Box box = Sky.worlds().area(island, name);
        if (box == null) return false;
        Box g = margin > 0 ? box.grow(margin) : box;
        return g.contains(l.getX(), l.getY(), l.getZ());
    }

    private boolean inOreArea(String island, Location l) {
        long now = System.currentTimeMillis();
        if (now - oreBoxesAt > 30_000) { oreBoxes.clear(); oreBoxesAt = now; }
        List<Box> boxes = oreBoxes.computeIfAbsent(island, k -> {
            List<Box> out = new ArrayList<>();
            Layout lay = Sky.worlds().layout(k);
            if (lay != null) for (Map.Entry<String, Box> e : lay.areas().entrySet()) {
                String n = e.getKey();
                if (n.startsWith("ores") || n.startsWith("mine") || n.equals("coal_mine") || n.contains(".ores")) out.add(e.getValue());
            }
            return out;
        });
        // an island that declares no ore areas: plain stone is never mineable there (towns stay intact)
        for (Box b : boxes) if (b.grow(3).contains(l.getX(), l.getY(), l.getZ())) return true;
        return false;
    }

    /** Whether this player's client should be frozen (custom breaking applies where they stand). */
    private boolean customHere(Player p) {
        if (!m.dials.engine.get() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return false;
        if (Sky.worlds().buildBypass(p)) return false;
        String island = MiningUtil.island(p);
        if (island == null) return false;
        if (MiningUtil.MINING_ISLANDS.contains(island)) return true;
        return "hub".equals(island) && inArea(island, p.getLocation(), "coal_mine", m.dials.hubMineMargin.get());
    }

    private void attributes() {
        for (Player p : Bukkit.getOnlinePlayers()) attribute(p);
    }

    /**
     * Freeze or release one player's client-side breaking. Checks the REAL modifier rather than a cache,
     * so a modifier the server dropped (respawn, another plugin resetting attributes) is put back within
     * 10 ticks instead of never.
     */
    void attribute(Player p) {
        boolean want = customHere(p);
        AttributeInstance a = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (a == null) return;
        boolean has = a.getModifier(MOD_KEY) != null;
        if (has == want) { zeroed.put(p.getUniqueId(), want); return; }
        a.removeModifier(MOD_KEY);
        if (want) a.addTransientModifier(new AttributeModifier(MOD_KEY, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        zeroed.put(p.getUniqueId(), want);
    }

    /** Arriving on (or leaving) a mining island applies the freeze on the next tick, not up to 10 ticks later. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        abort(p);
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (p.isOnline()) attribute(p); });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (p.isOnline()) attribute(p); });
    }

    /** A right-click (or dropping an item) also swings the arm; that swing must not start a dig. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) rightClick.put(e.getPlayer().getUniqueId(), tick);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) { rightClick.put(e.getPlayer().getUniqueId(), tick); }

    void release(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (a != null) a.removeModifier(MOD_KEY);
        zeroed.remove(p.getUniqueId());
        abort(p);
    }

    void releaseAll() { for (Player p : Bukkit.getOnlinePlayers()) release(p); }

    // ---- events -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(BlockDamageEvent e) {
        Player p = e.getPlayer();
        if (!m.dials.engine.get() || !MiningUtil.ready(p)) return;
        if (p.getGameMode() == GameMode.CREATIVE || Sky.worlds().buildBypass(p)) return;
        Block b = e.getBlock();
        BlockTable.Def def = mineable(p, b, true);
        if (def == null) { abort(p); return; }
        Dig d = digs.get(p.getUniqueId());
        if (d != null && d.block.equals(b)) { d.lastSwing = tick; return; }
        start(p, b, def, e.getBlockFace());
    }

    private void start(Player p, Block b, BlockTable.Def def, org.bukkit.block.BlockFace face) {
        abort(p);
        Dig d = new Dig();
        d.block = b;
        d.def = def;
        d.lastSwing = tick;
        if (m.hotm.has(p, "precision_mining") && (def.kind == BlockTable.Kind.ORE || def.kind == BlockTable.Kind.PURE_ORE || def.kind == BlockTable.Kind.METAL))
            d.spark = sparkOn(b, face);
        digs.put(p.getUniqueId(), d);
        step(p, d, true);
    }

    private static double reach(Player p) {
        AttributeInstance ra = p.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        return ra != null ? ra.getValue() + 0.5 : 5.0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbort(BlockDamageAbortEvent e) {
        Dig d = digs.get(e.getPlayer().getUniqueId());
        if (d != null && d.block.equals(e.getBlock())) abort(e.getPlayer());
    }

    /**
     * Arm swings keep a dig alive. They also START one when the client did not send a fresh start-dig:
     * with a frozen client it keeps "destroying" the same position, so a block that grew back under the
     * crosshair, or a re-target the client folded into one action, would otherwise never progress
     * (research 6.1 step 3: "start on new block if swinging"). Right-click swings and swings at a mob in
     * front of the wall do not start anything.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        Dig d = digs.get(p.getUniqueId());
        if (d != null) { d.lastSwing = tick; return; }
        if (!m.dials.engine.get() || !MiningUtil.ready(p) || !customHere(p)) return;
        Integer rc = rightClick.get(p.getUniqueId());
        if (rc != null && tick - rc <= 1) return;
        double range = reach(p);
        RayTraceResult hit = p.getWorld().rayTrace(p.getEyeLocation(), p.getEyeLocation().getDirection(), range,
                FluidCollisionMode.NEVER, true, 0.0, en -> en != p && en instanceof org.bukkit.entity.LivingEntity);
        if (hit == null || hit.getHitEntity() != null || hit.getHitBlock() == null) return;
        Block b = hit.getHitBlock();
        BlockTable.Def def = mineable(p, b, false);
        if (def == null) return;
        start(p, b, def, hit.getHitBlockFace());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        abort(e.getPlayer());
        zeroed.remove(e.getPlayer().getUniqueId());
        rightClick.remove(e.getPlayer().getUniqueId());
    }

    /** Creative players and the engine-off fallback: a vanilla break that core offered to us. */
    private void vanillaBreak(Player p, Block b) {
        if (m.dials.engine.get() && p.getGameMode() != GameMode.CREATIVE) return;   // the engine does it
        BlockTable.Def def = mineable(p, b, true);
        if (def == null) return;
        if (p.getGameMode() != GameMode.CREATIVE && toolPower(p) < def.breakingPower) { bpHint(p, def); return; }
        m.breaker.breakBlock(p, b, def, false);
    }

    // ---- the dig ----------------------------------------------------------------------------

    private Vector sparkOn(Block b, org.bukkit.block.BlockFace face) {
        var r = MiningUtil.rnd();
        double u = 0.2 + r.nextDouble() * 0.6, v = 0.2 + r.nextDouble() * 0.6;
        Vector c = new Vector(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        if (face == null) face = org.bukkit.block.BlockFace.UP;
        Vector n = face.getDirection().multiply(0.51);
        Vector off = switch (face) {
            case UP, DOWN -> new Vector(u - 0.5, 0, v - 0.5);
            case NORTH, SOUTH -> new Vector(u - 0.5, v - 0.5, 0);
            default -> new Vector(0, v - 0.5, u - 0.5);
        };
        return c.add(n).add(off);
    }

    private void tickAll() {
        tick++;
        if (digs.isEmpty()) return;
        for (Map.Entry<UUID, Dig> en : new ArrayList<>(digs.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) { digs.remove(en.getKey()); continue; }
            step(p, en.getValue(), false);
        }
    }

    private void step(Player p, Dig d, boolean first) {
        if (tick - d.lastSwing > 6) { abort(p); return; }
        BlockTable.Def now = mineable(p, d.block, false);
        if (now == null || !now.id.equals(d.def.id)) { abort(p); return; }
        RayTraceResult hit = p.rayTraceBlocks(reach(p), FluidCollisionMode.NEVER);
        if (hit == null || hit.getHitBlock() == null || !hit.getHitBlock().equals(d.block)) { abort(p); return; }

        if (toolPower(p) < d.def.breakingPower) { bpHint(p, d.def); return; }
        if (!m.drills.hasFuel(p)) {
            if (System.currentTimeMillis() > d.bpHint) {
                Sky.vitals().flash(p, "&cYour drill is out of fuel! &7(/drill)", 40);
                d.bpHint = System.currentTimeMillis() + 2000;
            }
            return;
        }
        double speed = speed(p, d, hit);
        boolean sixty = d.def.kind.sixtyRule();
        int ticks = MiningMath.ticksToBreak(d.def.strength, speed, sixty);
        if (ticks == 0) { finish(p, d); return; }
        if (ticks == Integer.MAX_VALUE) return;
        d.progress += 1f / ticks;
        if (d.spark != null && tick % 4 == 0)
            p.spawnParticle(Particle.CRIT, d.spark.getX(), d.spark.getY(), d.spark.getZ(), 2, 0.02, 0.02, 0.02, 0);
        if (d.progress >= 1f) { finish(p, d); return; }
        int stage = Math.min(9, (int) (d.progress * 10));
        if (stage != d.lastStage) { d.lastStage = stage; crack(p, d.block, d.progress); }
    }

    /** The speed that applies to this block right now, with every block-specific bonus. */
    double speed(Player p, Dig d, RayTraceResult hit) {
        BlockTable.Def def = d.def;
        double s = Sky.stats().get(p, Stat.MINING_SPEED);
        s += m.breaker.contextSpeed(p, def, d.block);
        if (d.spark != null && hit != null && hit.getHitPosition() != null && hit.getHitPosition().distance(d.spark) < 0.3) s *= 1.3;
        s *= m.events.speedMultiplier(p);
        s *= m.tunnels.speedMultiplier(p);
        if (s <= 0 && def.breakingPower <= 0) s = m.dials.handSpeed.get();
        return s;
    }

    int toolPower(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        if (h != null && !h.getType().isAir()) {
            String id = Sky.items().idOf(h);
            ItemDef def = id == null ? null : Sky.items().def(id);
            if (def != null && !def.requirements.isEmpty() && !Sky.items().meetsRequirements(p, h, false)) return 0;
        }
        return (int) Math.floor(Sky.stats().get(p, Stat.BREAKING_POWER));
    }

    private void bpHint(Player p, BlockTable.Def def) {
        Dig d = digs.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (d != null && now < d.bpHint) return;
        if (d != null) d.bpHint = now + 2000;
        // Wave-5 regression playtest: a Mithril Pickaxe (Breaking Power 5) held at HotM 1 was told "You need a
        // tool with Breaking Power 4" - its unmet requirement (HotM 2) zeroes the power, so name that instead.
        ItemStack h = p.getInventory().getItemInMainHand();
        if (h != null && !h.getType().isAir()) {
            String id = Sky.items().idOf(h);
            ItemDef idef = id == null ? null : Sky.items().def(id);
            if (idef != null && !idef.requirements.isEmpty() && !Sky.items().meetsRequirements(p, h, false)) {
                String need = null;
                for (String key : idef.requirements)
                    if (!Sky.requirements().check(p, key)) { need = Sky.requirements().describe(key); break; }
                Sky.vitals().flash(p, "&cYou can't use your " + idef.name + " &cyet" + (need == null ? "" : ": requires " + need) + "&c!", 40);
                return;
            }
        }
        Sky.vitals().flash(p, "&cYou need a tool with &2Ⓟ Breaking Power " + def.breakingPower + " &cto mine " + def.name + "!", 40);
    }

    private void finish(Player p, Dig d) {
        digs.remove(p.getUniqueId());
        crack(p, d.block, 0f);
        m.breaker.breakBlock(p, d.block, d.def, false);
    }

    void abort(Player p) {
        Dig d = digs.remove(p.getUniqueId());
        if (d != null && d.lastStage >= 0) crack(p, d.block, 0f);
    }

    private void crack(Player miner, Block b, float progress) {
        Location l = b.getLocation();
        for (Entity e : b.getWorld().getNearbyEntities(l, 32, 32, 32, en -> en instanceof Player)) {
            ((Player) e).sendBlockDamage(l, Math.max(0f, Math.min(1f, progress)), miner.getEntityId());
        }
    }

    int activeDigs() { return digs.size(); }
}
