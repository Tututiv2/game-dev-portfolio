package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mining Speed on the PRIVATE ISLAND. The island is a BUILD zone, so blocks break the vanilla way and the dig
 * engine (which freezes the client) stands aside. Vanilla knows nothing about Mining Speed: a drill is a
 * prismarine shard to it and broke stone at hand speed (7.5 s), a gauntlet a head.
 *
 * <p>Instead of freezing the client, this scales the player's BLOCK_BREAK_SPEED for the ONE block they start
 * digging, so vanilla itself (client prediction and the server's own check alike) takes exactly the ticks the
 * mining formula gives: {@code ticks = max(4, round(strength * 30 / speed))} with the same 30x / 60x instamine
 * rules as the mines (research 6.1), the strength and kind coming from the same block-table row
 * {@link IslandMining} pays XP from. Dirt, wood, crops and everything that is not a mining block keep their
 * vanilla speed, because the factor is set at the start of each dig, cleared at its end, and cleared the moment
 * a dig starts on anything else (cancelling that one start if the old factor would have insta-broken it).
 *
 * <p>Only a SkyBlock pickaxe / drill / gauntlet with enough Breaking Power (and fuel, for a drill) gets the
 * formula; a fist or a sword stays vanilla, like the reference.
 */
final class IslandSpeed implements Listener {

    private static final NamespacedKey KEY = new NamespacedKey("skyclone", "mining_island_speed");

    private final MiningModule m;
    /** Block each player's factor was computed for. */
    private final Map<UUID, Block> target = new HashMap<>();

    IslandSpeed(MiningModule m) {
        this.m = m;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::refresh, 40L, 5L);
    }

    /**
     * End Stone in the End's quarry (combatzones' area {@code endstone.quarry}, whose break handler pays the drop,
     * XP and Gather): it breaks at Mining Speed like the reference. Strength 30 (SkyHanni's block strength table),
     * Breaking Power 1, the 30x instamine rule. Only the speed is ours; the payout stays combatzones'.
     */
    static final BlockTable.Def END_STONE = endStone();
    static final String END = "the_end", QUARRY = "endstone.quarry";

    private static BlockTable.Def endStone() {
        BlockTable.Def d = new BlockTable.Def();
        d.id = "end_stone";
        d.name = "End Stone";
        d.kind = BlockTable.Kind.STANDARD;
        d.strength = 30;
        d.breakingPower = 1;
        d.drop = "ENDER_STONE";
        return d;
    }

    /** The row whose strength sets this block's dig speed, by place: private island table rows, the End quarry. */
    BlockTable.Def speedRow(Block b) {
        String island = MiningUtil.island(b.getLocation());
        if (IslandMining.PRIVATE.equals(island)) return m.islandMining.speedDef(b.getType());
        if (END.equals(island) && b.getType() == org.bukkit.Material.END_STONE && m.dials.endQuarrySpeed.get()) {
            com.skyclone.api.world.gen.Box box = Sky.worlds().area(END, QUARRY);
            if (box != null && box.contains(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5)) return END_STONE;
        }
        return null;
    }

    /** The block-table row that applies to this block for this player's dig, or null (stay vanilla). */
    BlockTable.Def applies(Player p, Block b) {
        if (!m.dials.islandSpeed.get() || p == null || b == null || !MiningUtil.ready(p)) return null;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || Sky.worlds().buildBypass(p)) return null;
        if (!IslandMining.miningTool(p.getInventory().getItemInMainHand())) return null;
        BlockTable.Def def = speedRow(b);
        if (def == null) return null;
        if (m.engine.toolPower(p) < def.breakingPower || !m.drills.hasFuel(p)) return null;
        return def;
    }

    /** Ticks this player needs for this block by the mining formula (0 instamine, MAX never). */
    int ticks(Player p, BlockTable.Def def) {
        double speed = Sky.stats().get(p, Stat.MINING_SPEED);
        if (speed <= 0) return Integer.MAX_VALUE;
        return MiningMath.ticksToBreak(def.strength, speed, def.kind.sixtyRule());
    }

    /** Set this player's factor for this block; returns the formula ticks, or -1 when vanilla stays. */
    int apply(Player p, Block b, BlockTable.Def def) {
        AttributeInstance a = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (a == null) return -1;
        int t = ticks(p, def);
        if (t == Integer.MAX_VALUE) { clear(p); return -1; }
        a.removeModifier(KEY);
        double base = b.getBreakSpeed(p);
        double k = MiningMath.breakSpeedScale(t, base);
        if (Math.abs(k - 1.0) > 1e-6) a.addTransientModifier(new AttributeModifier(KEY, k - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        target.put(p.getUniqueId(), b);
        return t;
    }

    /** Remove the factor; true when there was one. */
    boolean clear(Player p) {
        target.remove(p.getUniqueId());
        AttributeInstance a = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (a == null || a.getModifier(KEY) == null) return false;
        a.removeModifier(KEY);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        BlockTable.Def def = applies(p, b);
        if (def == null) {
            // vanilla block: drop a factor left over from the last stone, and never let it insta-break this one
            if (clear(p) && e.getInstaBreak() && b.getBreakSpeed(p) < 1f) e.setCancelled(true);
            return;
        }
        int t = apply(p, b, def);
        if (t == 0) e.setInstaBreak(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbort(BlockDamageAbortEvent e) { clear(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        if (target.containsKey(e.getPlayer().getUniqueId())) clear(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent e) { clear(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { clear(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { clear(e.getPlayer()); }

    /**
     * Every 5 ticks: Mining Speed can change mid-dig (an ability, a potion), so a player still on the same block
     * gets the factor recomputed; anyone who left the island or looks elsewhere loses it.
     */
    private void refresh() {
        if (target.isEmpty()) return;
        for (Map.Entry<UUID, Block> en : new HashMap<>(target).entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) { target.remove(en.getKey()); continue; }
            Block b = en.getValue();
            BlockTable.Def def = applies(p, b);
            Block looking = p.getTargetBlockExact(6);
            if (def == null || looking == null || !looking.equals(b)) { clear(p); continue; }
            apply(p, b, def);
        }
    }

    void releaseAll() { for (Player p : Bukkit.getOnlinePlayers()) clear(p); }

    /** For /sb mining info: the factor in effect and the ticks it aims at. */
    String describe(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        AttributeModifier mod = a == null ? null : a.getModifier(KEY);
        Block b = target.get(p.getUniqueId());
        if (mod == null || b == null) return "&7Island speed: &8none";
        BlockTable.Def def = speedRow(b);
        return "&7Island speed: &ax" + String.format(java.util.Locale.US, "%.2f", mod.getAmount() + 1) + " &7on " + b.getType().name()
                + (def == null ? "" : " &8(" + ticks(p, def) + " ticks, strength " + def.strength + ")");
    }
}
