package com.skyclone.mod.foraging;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns log breaking where foraging applies: the Park, the Moonmarsh and the Hub's forest.
 * One swing fells {@code 1 + Sweep} logs of the tree (research 07 §4.3) in a quick wave, drops
 * logs x Foraging Fortune through LootService (so collections and sacks see them), grants Foraging
 * XP, fires {@code SkyEvents.Gather}, and hands the whole tree to {@link TreeRegen}.
 *
 * <p>On the private island (a BUILD zone, vanilla breaking) it only ADDS: Foraging XP, fortune
 * extras and the Gather event for natural logs. Logs a player placed are remembered in the chunk's
 * data so re-placing a log is not an XP farm.
 */
final class Chopper implements WorldService.BreakHandler, Listener {

    static final String PARK = "park", MARSH = "galatea", HUB = "hub", PRIVATE = "private_island";
    /** The galatea module's Torrent Canyon island (its spiral trees, hives and Spring Depths are ours to run). */
    static final String TORRHUS = "torrhus_canyon";

    private final TreeRegen regen;
    private final NamespacedKey placedKey;
    /** The tree found by handles() for this player, reused by onBreak (same tick). */
    private final Map<UUID, Tree> lastScan = new HashMap<>();

    /** A tree: its logs in flood-fill order from where it was hit, and whether leaves touch it. */
    record Tree(Wood wood, List<Block> logs, boolean leafy) {}

    Chopper(TreeRegen regen) {
        this.regen = regen;
        this.placedKey = new NamespacedKey(Sky.plugin(), "foraging_placed_logs");
    }

    @Override public int priority() { return 400; }

    // ================================================================================ who handles

    static String island(World w) { return Sky.worlds().islandOf(w); }

    static boolean foragingIsland(String island) { return PARK.equals(island) || MARSH.equals(island) || TORRHUS.equals(island); }

    /** Islands of the tough woods (fig, mangrove, spiral), Tree Gifts, island resources and contests. */
    static boolean toughIsland(String island) { return MARSH.equals(island) || TORRHUS.equals(island); }

    /** Is this block somewhere foraging owns? */
    boolean inForagingGround(Block b, String island, Layout.Zone zone) {
        if (foragingIsland(island)) return true;
        if (!HUB.equals(island)) return false;
        if (!FDials.hubForestOnly.get()) return true;
        if (zone != null && zone.id() != null && zone.id().toLowerCase().contains("forest")) return true;
        Box area = Sky.worlds().area(HUB, "forest");
        return area != null && area.containsXZ(b.getX() + 0.5, b.getZ() + 0.5);
    }

    @Override
    public boolean handles(Player p, Block block, Layout.Zone zone) {
        if (!FDials.enabled.get()) return false;
        String island = island(block.getWorld());
        if (island == null || !inForagingGround(block, island, zone)) return false;
        String mat = block.getType().name();
        if (Wood.isFlower(mat)) return true;
        if (toughIsland(island) && IslandResource.ofBlock(mat) != null) return !regen.isPending(block) || !block.getType().isAir();
        Wood w = Wood.ofBlock(mat, toughIsland(island));
        if (w == null) return false;
        if (regen.isPending(block) && block.getType().isAir()) return false;
        Tree t = scan(block, w);
        // A log of a tree already being regrown passed the leaf test when its tree was first cut. Without
        // this, cutting a trunk at eye level left a stump that touches no leaves and silently refused every
        // swing until the regrowth (wave-3 playtest: 22 swings in the Hub Forest, 2 logs).
        if (FDials.needLeaves.get() && !t.leafy() && !regen.isPending(block)) return false;
        lastScan.put(p.getUniqueId(), t);
        return true;
    }

    @Override
    public void onBreak(Player p, Block block, BlockBreakEvent event) {
        String mat = block.getType().name();
        if (Wood.isFlower(mat)) { pickFlower(p, block); return; }
        String island = island(block.getWorld());
        IslandResource res = toughIsland(island) ? IslandResource.ofBlock(mat) : null;
        if (res != null) { pickResource(p, block, res); return; }
        Tree t = lastScan.remove(p.getUniqueId());
        if (t == null || t.logs().isEmpty() || !t.logs().get(0).equals(block)) {
            Wood w = Wood.ofBlock(mat, toughIsland(island));
            if (w == null) return;
            t = scan(block, w);
        }
        chop(p, t, island, false);
    }

    // ================================================================================ tree scan

    /** Flood-fill the logs of one wood connected to {@code start} (26 neighbours), nearest first. */
    Tree scan(Block start, Wood wood) {
        int cap = Math.max(16, FDials.treeScanCap.get());
        List<Block> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Block> q = new ArrayDeque<>();
        q.add(start);
        seen.add(pack(start));
        boolean leafy = false;
        World w = start.getWorld();
        while (!q.isEmpty() && out.size() < cap) {
            Block b = q.poll();
            out.add(b);
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = b.getX() + dx, ny = b.getY() + dy, nz = b.getZ() + dz;
                        if (ny < w.getMinHeight() || ny >= w.getMaxHeight()) continue;
                        Block n = w.getBlockAt(nx, ny, nz);
                        String nm = n.getType().name();
                        if (!leafy && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1 && Wood.isLeaves(nm)) leafy = true;
                        if (!wood.blocks.contains(nm)) continue;
                        if (seen.add(pack(n))) q.add(n);
                    }
        }
        return new Tree(wood, out, leafy);
    }

    private static long pack(Block b) { return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | (b.getY() & 0xFFF); }

    // ================================================================================ the chop

    /** Fell part of a tree for a player. {@code thrown} = the Throwing Axe ability. Returns logs felled. */
    int chop(Player p, Tree t, String island, boolean thrown) {
        if (!Sky.profiles().isReady(p)) return 0;
        Wood wood = t.wood();
        boolean marsh = toughIsland(island);
        if (marsh && ForagingModule.marsh() != null && !ForagingModule.marsh().mayChop(p, t)) return 0;

        // Sweep: the stat, plus what only counts against this wood or this kind of cut (attributes, wave 5)
        double sweep = Sky.stats().get(p, Stat.SWEEP) + Bonuses.woodSweep(p, wood) + (thrown ? Bonuses.throwSweep(p) : Bonuses.meleeSweep(p));
        ItemStack hand = p.getInventory().getItemInMainHand();
        double penalty = thrown ? HotfAbilities.throwPenalty(p, hand) : 0;
        if (thrown) sweep += HotfAbilities.manaToss(p);
        boolean firstCut = !regen.isPending(t.logs().get(0));
        if (firstCut) sweep *= 1 + Bonuses.firstSweepPercent(p) / 100.0;   // Torrid: the first Sweep on a tree
        // cutting order on the Moonmarsh giants (research 07 §4.4): the wrong part first costs half the Sweep
        List<Block> order = t.logs();
        boolean wrong = false;
        if (marsh && wood.stages() > 1 && FDials.cuttingOrder.get()) {
            int hitStage = stageOf(wood, t.logs().get(0)), first = hitStage;
            for (Block b : t.logs()) first = Math.min(first, stageOf(wood, b));
            wrong = hitStage > first;
            if (wrong) warnOrder(p, wood, first);
            List<Block> same = new ArrayList<>(), rest = new ArrayList<>();
            for (Block b : t.logs()) (stageOf(wood, b) == hitStage ? same : rest).add(b);
            same.addAll(rest);
            order = same;
        }
        double toughness = Math.max(0, wood.toughness - Bonuses.toughClaws(p));     // Grizzly Bear: tough claws
        int per = wood.toughness > 0 ? SweepMath.logsPerSwingTough(sweep, toughness, thrown, penalty, wrong)
                : SweepMath.logsPerSwing(sweep, 0, thrown, penalty, wrong);
        if (ThreadLocalRandom.current().nextDouble() * 100 < Bonuses.chopPercent(p)) per++;   // Hummingbird: Chop
        int n = Math.min(t.logs().size(), per);
        if (Hotf.timber(p, hand, wood)) n = t.logs().size();
        List<Block> cut = new ArrayList<>(order.subList(0, n));

        // the whole tree regrows together
        int delay = marsh ? FDials.regenMarshSeconds.get() : FDials.regenSeconds.get();
        regen.record(t.logs().get(0).getWorld(), t.logs(), delay);

        // drops
        double ff = Sky.stats().get(p, Stat.FORAGING_FORTUNE) + Bonuses.woodFortune(p, wood);
        int drops = SweepMath.drops(n, ff, ThreadLocalRandom.current());
        giveChunked(p, wood.itemId, drops, LootSource.FORAGING);

        // the axe in hand adds its own trick
        ItemStack held = p.getInventory().getItemInMainHand();
        axePerks(p, held, wood, n, marsh);

        // XP and the event other modules listen to
        double xp = wood.xp * n * FDials.xpMultiplier.get();
        Sky.service(SkillService.class).addXp(p, SkillType.FORAGING, xp, "foraging:" + wood.itemId);
        Hotf.onLogs(p, wood, n, marsh);
        Location at = t.logs().get(0).getLocation().add(0.5, 0.5, 0.5);
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.FORAGING, wood.blocks.get(0), drops, at));

        ForagingData d = ForagingModule.data(p);
        d.logs += n;
        d.byWood.merge(wood.itemId, (long) n, Long::sum);
        if (marsh) { d.marshLogs += n; Contest.addLogs(p, wood, n); }

        fell(cut);
        if (marsh && ForagingModule.marsh() != null) ForagingModule.marsh().onChopped(p, t, n);
        p.playSound(at, Sound.BLOCK_WOOD_BREAK, 1f, thrown ? 0.8f : 1f);
        return n;
    }

    static int stageOf(Wood wood, Block b) {
        char axis = b.getBlockData() instanceof org.bukkit.block.data.Orientable o
                ? Character.toLowerCase(o.getAxis().name().charAt(0)) : 'y';
        return wood.stage(b.getType().name(), axis);
    }

    private final Map<UUID, Long> orderWarned = new HashMap<>();

    private void warnOrder(Player p, Wood wood, int first) {
        long now = System.currentTimeMillis();
        Long last = orderWarned.get(p.getUniqueId());
        if (last != null && now - last < 8000) return;
        orderWarned.put(p.getUniqueId(), now);
        com.skyclone.api.ui.Msg.send(p, "&cWrong cutting order! &7Cut a " + wood.display + " tree's &a" + wood.stageName(first)
                + " &7first &8(" + wood.orderLine() + ")&7. &c-50% logs");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
    }

    /** Remove the cut logs ring by ring (the genre's "eaten in a wave" look). */
    private void fell(List<Block> cut) {
        int step = Math.max(0, FDials.waveTicks.get());
        if (step == 0 || cut.size() <= 1) { for (Block b : cut) breakOne(b); return; }
        // the scan order is breadth-first from the hit block, so chunks of it are rings
        int per = Math.max(1, (int) Math.ceil(cut.size() / 6.0));
        for (int i = 0; i < cut.size(); i += per) {
            List<Block> part = cut.subList(i, Math.min(cut.size(), i + per));
            List<Block> copy = new ArrayList<>(part);
            if (i == 0) { for (Block b : copy) breakOne(b); continue; }
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { for (Block b : copy) breakOne(b); }, (long) step * (i / per));
        }
    }

    private static void breakOne(Block b) {
        Material type = b.getType();
        if (type.isAir()) return;
        b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3, 0, b.getBlockData());
        b.setType(Material.AIR, false);
    }

    private void pickFlower(Player p, Block b) {
        if (!Sky.profiles().isReady(p)) return;
        String id = Sky.items().vanillaId(b.getType());
        regen.record(b.getWorld(), List.of(b), FDials.flowerRegenSeconds.get());
        b.setType(Material.AIR, false);
        if (id != null && Sky.items().exists(id)) Sky.loot().give(p, id, 1, LootSource.FORAGING);
        Sky.service(SkillService.class).addXp(p, SkillType.FORAGING, 1 * FDials.xpMultiplier.get(), "foraging:flower");
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.FORAGING, b.getType().name(), 1, b.getLocation()));
        p.playSound(b.getLocation(), Sound.BLOCK_GRASS_BREAK, 1f, 1.2f);
    }

    /** Moonmarsh island resources: Velvet Lilac, Sea Lumies, Ruby Veilshroom (research 07 §4.1). */
    private void pickResource(Player p, Block b, IslandResource r) {
        if (!Sky.profiles().isReady(p)) return;
        List<Block> parts = new ArrayList<>();
        parts.add(b);
        if (b.getBlockData() instanceof org.bukkit.block.data.Bisected bi) {
            Block other = b.getRelative(0, bi.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM ? 1 : -1, 0);
            if (other.getType() == b.getType()) parts.add(other);
        }
        int amount = b.getBlockData() instanceof org.bukkit.block.data.type.SeaPickle sp ? Math.max(1, sp.getPickles()) : 1;
        boolean wet = b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
        regen.record(b.getWorld(), parts, FDials.resourceRegenSeconds.get());
        for (Block x : parts) x.setType(wet ? Material.WATER : Material.AIR, false);

        ForagingData d = ForagingModule.data(p);
        boolean doubled = d.hotfUnlocked && ThreadLocalRandom.current().nextDouble() < IslandResource.collectorChance(Hotf.level(d, "collector"));
        if (doubled) amount *= 2;
        giveChunked(p, r.itemId, amount, LootSource.FORAGING);
        Sky.service(SkillService.class).addXp(p, SkillType.FORAGING, r.xp * FDials.xpMultiplier.get(), "foraging:" + r.itemId);
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.FORAGING, r.itemId, amount, b.getLocation()));
        d.resources.merge(r.itemId, (long) amount, Long::sum);
        Contest.addResource(p, r, amount);
        if (doubled) p.sendActionBar(com.skyclone.api.ui.Text.c("&aCollector! &7Double " + r.display));
        b.getWorld().spawnParticle(r == IslandResource.SEA_LUMIES ? Particle.GLOW : Particle.HAPPY_VILLAGER,
                b.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0);
        p.playSound(b.getLocation(), r == IslandResource.SEA_LUMIES ? Sound.BLOCK_SLIME_BLOCK_BREAK
                : r == IslandResource.RUBY_VEILSHROOM ? Sound.BLOCK_FUNGUS_BREAK : Sound.BLOCK_GRASS_BREAK, 1f, 1.3f);
    }

    static void giveChunked(Player p, String id, int amount, LootSource src) {
        if (amount <= 0 || !Sky.items().exists(id)) return;
        int max = Math.max(1, Sky.items().def(id).effectiveMaxStack());
        while (amount > 0) {
            int n = Math.min(max, amount);
            Sky.loot().give(p, id, n, src);
            amount -= n;
        }
    }

    // ================================================================================ axe tricks

    private void axePerks(Player p, ItemStack held, Wood wood, int logs, boolean marsh) {
        String id = held == null ? null : Sky.items().idOf(held);
        if (id == null) return;
        ItemDef def = Sky.items().def(id);
        if (def == null || def.extra == null) return;
        JsonObject ex = def.extra;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (ex.has("appleChance")) {
            double ch = ex.get("appleChance").getAsDouble();
            int apples = 0;
            for (int i = 0; i < logs; i++) if (rng.nextDouble() < ch) apples++;
            if (apples > 0) giveChunked(p, "APPLE", apples, LootSource.FORAGING);
        }
        if (ex.has("planks") && wood.plankItem() != null) giveChunked(p, wood.plankItem(), ex.get("planks").getAsInt() * logs, LootSource.FORAGING);
        boolean unique = def.isUnique();
        if (!unique) return;
        // counters live on the axe itself
        if ("ROOKIE_AXE".equals(id)) {
            ItemData d = Sky.items().data(held);
            if (d.enchant("efficiency") == 0) Sky.items().update(held, x -> x.enchants.put("efficiency", 1));
        }
        if (ex.has("growsAt")) {
            int goal = ex.get("growsAt").getAsInt();
            Sky.items().update(held, x -> {
                int c = (x.extra.has("blocksBroken") ? x.extra.get("blocksBroken").getAsInt() : 0) + logs;
                x.extra.addProperty("blocksBroken", c);
                if (c >= goal && x.enchant("efficiency") == 0) {
                    x.enchants.put("efficiency", 1);
                    com.skyclone.api.ui.Msg.send(p, "&aYour " + def.name + " &alearned &9Efficiency I&a!");
                }
            });
        }
        if (marsh && ex.has("frenzyEvery")) {
            Sky.items().update(held, x -> x.extra.addProperty("logsCut",
                    (x.extra.has("logsCut") ? x.extra.get("logsCut").getAsLong() : 0) + logs));
            Sky.stats().invalidate(p);
        }
    }

    /** Axes strip logs on right-click in vanilla: never on our forests (a stripped tree can no longer be cut or regrow). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStrip(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getItem() == null) return;
        if (!e.getItem().getType().name().endsWith("_AXE")) return;
        String island = island(e.getClickedBlock().getWorld());
        if (!(foragingIsland(island) || HUB.equals(island)) || Sky.worlds().buildBypass(e.getPlayer())) return;
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    }

    // ================================================================================ private island

    /** Natural logs broken on a BUILD island grant XP + fortune extras (core lets the vanilla drop happen). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanillaBreak(BlockBreakEvent e) {
        if (!FDials.privateIslandXp.get()) return;
        Block b = e.getBlock();
        Wood w = Wood.ofBlock(b.getType().name(), false);
        if (w == null) return;
        String island = island(b.getWorld());
        if (!PRIVATE.equals(island)) return;
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        if (unmarkPlaced(b)) return;   // a log the player placed: no XP
        double ff = Sky.stats().get(p, Stat.FORAGING_FORTUNE);
        int extra = SweepMath.dropsForLog(ff, ThreadLocalRandom.current()) - 1;
        if (extra > 0) giveChunked(p, w.itemId, extra, LootSource.FORAGING);
        Sky.service(SkillService.class).addXp(p, SkillType.FORAGING, w.xp * FDials.xpMultiplier.get(), "foraging:" + w.itemId);
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.FORAGING, b.getType().name(), 1 + extra, b.getLocation()));
        ForagingData d = ForagingModule.data(p);
        d.logs++;
        d.byWood.merge(w.itemId, 1L, Long::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (Wood.ofBlock(b.getType().name(), false) == null) return;
        if (!PRIVATE.equals(island(b.getWorld()))) return;
        Chunk c = b.getChunk();
        long[] cur = c.getPersistentDataContainer().getOrDefault(placedKey, PersistentDataType.LONG_ARRAY, new long[0]);
        if (cur.length >= 4096) return;   // a cap so a chunk's data stays small
        long k = pack(b);
        for (long v : cur) if (v == k) return;
        long[] next = java.util.Arrays.copyOf(cur, cur.length + 1);
        next[cur.length] = k;
        c.getPersistentDataContainer().set(placedKey, PersistentDataType.LONG_ARRAY, next);
    }

    private boolean unmarkPlaced(Block b) {
        Chunk c = b.getChunk();
        long[] cur = c.getPersistentDataContainer().get(placedKey, PersistentDataType.LONG_ARRAY);
        if (cur == null || cur.length == 0) return false;
        long k = pack(b);
        for (int i = 0; i < cur.length; i++) {
            if (cur[i] != k) continue;
            long[] next = new long[cur.length - 1];
            System.arraycopy(cur, 0, next, 0, i);
            System.arraycopy(cur, i + 1, next, i, cur.length - i - 1);
            c.getPersistentDataContainer().set(placedKey, PersistentDataType.LONG_ARRAY, next);
            return true;
        }
        return false;
    }
}
