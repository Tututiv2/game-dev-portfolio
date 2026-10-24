package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemService;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mining on the PRIVATE ISLAND (a BUILD zone, so blocks break the vanilla way and core's gather
 * listener puts the drops in the inventory / collections / sacks). In the reference, stone, cobble
 * (a cobblestone generator) and ores broken there pay Mining XP and roll Mining Fortune exactly like
 * in the mines; before this class nothing did, because the dig engine stands aside in BUILD zones.
 *
 * <p>Only blocks that GREW there count: {@code Sky.worlds().playerPlaced(block)} (core's per-block
 * mark, still true inside the break event of a placed block, cleared when a generator forms cobble)
 * means placing and breaking never farms XP or fortune. The XP per block is the mining block table's
 * own number (hub row first, then the Deep Caverns row for obsidian / glowstone / diamond blocks), so
 * a cobblestone is worth exactly what it is worth in the hub mine.
 *
 * <p>No powder, commissions, rare finds or regeneration: those belong to the mining islands.
 */
final class IslandMining implements Listener {

    static final String PRIVATE = "private_island";
    /** Table rows to borrow XP / kind from, in order. */
    private static final String[] TABLES = {"hub", "deep_caverns", "dwarven_mines"};

    private final MiningModule m;

    IslandMining(MiningModule m) { this.m = m; }

    /** The table row that gives this material its XP on the island, or null (not a mining block). */
    BlockTable.Def def(Material type) {
        String name = type.name();
        if (name.endsWith("_BLOCK")) return null;   // ore blocks (incl. the Deep Caverns' diamond block row) are always placed here
        for (String t : TABLES) {
            BlockTable.Def d = m.blocks.lookup(t, name);
            if (d != null && d.xp > 0 && d.kind != BlockTable.Kind.METAL && d.kind != BlockTable.Kind.GEMSTONE
                    && d.kind != BlockTable.Kind.HARD_STONE && d.kind != BlockTable.Kind.STAR
                    && d.kind != BlockTable.Kind.PURE_ORE) return d;   // a coal/diamond BLOCK only exists on an island because someone placed it
        }
        return null;
    }

    /**
     * The row whose Block Strength sets the BREAK SPEED of this material on the island ({@link IslandSpeed}). Wider
     * than {@link #def}: a placed ore block or a placed cobblestone still breaks at Mining Speed (it just pays
     * nothing), but island-specific meanings of decorative blocks (wool = mithril, glass = gems, terracotta = hard
     * stone) never apply here.
     */
    BlockTable.Def speedDef(Material type) {
        String name = type.name();
        for (String t : TABLES) {
            BlockTable.Def d = m.blocks.lookup(t, name);
            if (d != null && d.kind != BlockTable.Kind.METAL && d.kind != BlockTable.Kind.GEMSTONE
                    && d.kind != BlockTable.Kind.HARD_STONE && d.kind != BlockTable.Kind.STAR) return d;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!m.dials.islandXp.get()) return;
        Block b = e.getBlock();
        if (!PRIVATE.equals(MiningUtil.island(b.getLocation()))) return;
        Player p = e.getPlayer();
        if (!MiningUtil.ready(p)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || Sky.worlds().buildBypass(p)) return;
        BlockTable.Def def = def(b.getType());
        if (def == null) return;
        if (Sky.worlds().playerPlaced(b)) return;          // place-and-break pays nothing
        // Lapis Pickaxe: a chance to double the experience an ore drops (vanilla drops it here, so double it)
        // While enchanting.ore-exp is on, enchanting pays ore experience on the Gather we fire in award(), so vanilla's
        // own orbs would be a second payment: they are dropped only as the Lapis Pickaxe's doubling.
        if (e.getExpToDrop() > 0) {
            boolean twice = ThreadLocalRandom.current().nextDouble() < Breaker.doubleExpChance(p);
            if (m.dials.enchantingOreExp.get()) e.setExpToDrop(twice ? e.getExpToDrop() : 0);
            else if (twice) e.setExpToDrop(e.getExpToDrop() * 2);
        }

        ItemStack tool = p.getInventory().getItemInMainHand();
        Collection<ItemStack> vanilla;
        try { vanilla = b.getDrops(tool, p); } catch (RuntimeException ex) { vanilla = java.util.List.of(); }
        int mined = 0;
        Material dropType = null;
        if (vanilla.isEmpty()) {
            // A drill is a prismarine shard and a gauntlet a head to vanilla, so stone broken with one drops
            // nothing at all. A SkyBlock mining tool with enough Breaking Power gets the table's drop instead;
            // anything else (a fist, a sword) on stone gets nothing, and no XP, like the reference.
            if (!miningTool(tool) || m.engine.toolPower(p) < def.breakingPower || !MiningUtil.exists(def.drop)) return;
            int base = MiningMath.between(def.min, def.max, ThreadLocalRandom.current());
            int total = m.dials.islandFortune.get()
                    ? MiningMath.fortuneDrops(base, m.breaker.fortune(p, def), ThreadLocalRandom.current().nextDouble()) : base;
            for (int left = total; left > 0; left -= 64) Sky.loot().give(p, def.drop, Math.min(64, left), LootSource.MINING);
            award(p, b, def, def.drop, total);
            return;
        }

        // ---- Mining Fortune: extra copies of what the block really dropped -------------------------
        for (ItemStack s : vanilla) {
            if (s == null || s.getType().isAir()) continue;
            dropType = s.getType();
            mined = s.getAmount();
            break;
        }
        boolean silk = !tool.getType().isAir() && Sky.items().idOf(tool) != null && Sky.items().data(tool).enchant("silk_touch") > 0;
        if (dropType != null && mined > 0 && m.dials.islandFortune.get() && !silk) {
            int total = MiningMath.fortuneDrops(mined, m.breaker.fortune(p, def), ThreadLocalRandom.current().nextDouble());
            int extra = total - mined;
            if (extra > 0) give(p, dropType, extra);
            mined = Math.max(mined, total);
        }

        award(p, b, def, dropType != null ? dropType.name() : def.drop, mined);
    }

    /** A SkyBlock pickaxe, drill or gauntlet (by item category, so every module's tools count). */
    static boolean miningTool(ItemStack tool) {
        if (tool == null || tool.getType().isAir()) return false;
        String id = Sky.items().idOf(tool);
        com.skyclone.api.item.ItemDef d = id == null ? null : Sky.items().def(id);
        if (d == null || d.category == null) return false;
        String c = d.category.name();
        return c.equals("PICKAXE") || c.equals("DRILL") || c.equals("GAUNTLET");
    }

    // ---- XP, counters, drill fuel ---------------------------------------------------------------
    private void award(Player p, Block b, BlockTable.Def def, String gathered, int mined) {
        double xp = def.xp * m.dials.xpMult.get();
        if (xp > 0) Sky.service(SkillService.class).addXp(p, SkillType.MINING, xp, "mining:island:" + def.id);
        MiningData d = MiningUtil.data(p);
        d.blocksMined++;
        d.minedById.merge(def.id, 1L, Long::sum);
        d.lastMinedBlock = def.id;
        m.drills.burn(p, def.fuel);
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.MINING, gathered, Math.max(1, mined),
                b.getLocation().add(0.5, 0.5, 0.5)));
    }

    private static void give(Player p, Material type, int n) {
        ItemService items = Sky.items();
        String id = items.vanillaId(type);
        boolean custom = id != null && items.exists(id);
        while (n > 0) {
            int k = Math.min(64, n);
            if (custom) Sky.loot().give(p, id, k, LootSource.MINING);
            else Sky.loot().give(p, new ItemStack(type, k), LootSource.MINING);
            n -= k;
        }
    }
}
