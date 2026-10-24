package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Beehive;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HONEY (research 07 §4.1 "Honeycomb: Galatea hives", research 03: honey hives on Torrent Heights):
 * <ul>
 *   <li><b>Hives</b> (bee nests on the Torrent Canyon and the Moonmarsh's Spiral Mesa): hit or right-click a
 *       full one for Honeycomb + Foraging XP. Each hive refills PER PLAYER (you see it empty, others still see it
 *       full), so nobody steals anybody's honey. Honey Haul (double), Honey Refill (instant), the daily
 *       Hive Mind perk (+2 comb / 20 % faster) apply.</li>
 *   <li><b>Lathering</b> (NEU pot lore): right-click a fig, mangrove or spiral tree with a Pot of Honeycomb;
 *       after the pot's wait (60 / 30 / 15 min, Behemoth at once; Honeycomb talismans, Hivehog and Hive Mind cut
 *       it) a critter of that tree's kind comes for the honey: a hunting-module critter you can lasso.
 *       Honey Serendipity makes it come at once, Honey Luck brings a second; a lured critter you kill drops
 *       Honeycomb (Lathering Greed adds to it). The wait survives logging out.</li>
 * </ul>
 */
final class Hives implements WorldService.BreakHandler, Listener {

    static final String HONEYCOMB = "HONEYCOMB";

    /** Critters each wood lures (hunting's mob ids, weights = hunting's rarity weights C50/U26/R14/E7/L3). TUNE. */
    static final Map<Wood, String[][]> LURES = Map.of(
            Wood.FIG, new String[][]{{"critter_hideonleaf", "50"}, {"critter_chocobun", "50"}, {"critter_woodchucker", "26"},
                    {"critter_hivethief", "14"}, {"critter_fenlord", "7"}, {"critter_chameleon", "3"}},
            Wood.MANGROVE, new String[][]{{"critter_crow", "50"}, {"critter_phanflare", "50"}, {"critter_treefrog", "26"},
                    {"critter_heron", "26"}, {"critter_murkbat", "14"}, {"critter_toucan", "7"}, {"critter_macaw", "3"}},
            Wood.HELIX, new String[][]{{"critter_honeybuzz", "50"}, {"critter_pollendart", "50"}, {"critter_honeybug", "26"},
                    {"critter_woodpecker", "26"}, {"critter_vulture", "14"}, {"critter_queen_bee", "7"}, {"critter_beeheemoth", "7"}});

    /** Lured critter -> the player who lathered (for Lathering Greed), with when it leaves. */
    private final Map<UUID, UUID> lured = new HashMap<>();
    private final Map<UUID, Long> luredUntil = new HashMap<>();

    void register() {
        Sky.worlds().addBreakHandler(this);
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Sky.abilities().register("foraging_lather", this::lather);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 40L);
    }

    static boolean isHive(Material m) { return m == Material.BEE_NEST || m == Material.BEEHIVE; }

    static String key(Block b) { return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    // ================================================================================ hives

    @Override public int priority() { return 390; }

    @Override
    public boolean handles(Player p, Block block, Layout.Zone zone) {
        return FDials.enabled.get() && FDials.hives.get() && isHive(block.getType()) && Chopper.toughIsland(Chopper.island(block.getWorld()));
    }

    @Override
    public void onBreak(Player p, Block block, BlockBreakEvent event) { harvest(p, block); }

    /** Right-click a hive (any item) harvests it too; vanilla bottling/shearing never happens on our islands. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (!isHive(b.getType()) || !Chopper.toughIsland(Chopper.island(b.getWorld())) || Sky.worlds().buildBypass(e.getPlayer())) return;
        e.setCancelled(true);
        if (FDials.hives.get()) harvest(e.getPlayer(), b);
    }

    void harvest(Player p, Block b) {
        if (!Sky.profiles().isReady(p)) return;
        ForagingData d = ForagingModule.data(p);
        long now = System.currentTimeMillis();
        String k = key(b);
        Long ready = d.hiveReady.get(k);
        if (ready != null && ready > now) {
            p.sendActionBar(Text.c("&eThis hive is empty. &7It refills for you in &a" + Text.duration((ready - now) / 1000) + "&7."));
            showEmpty(p, b);
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int face = Bonuses.dailyHive(p);
        boolean doubled = rng.nextDouble() * 100 < Bonuses.honeyHaulPercent(p);
        int n = HiveMath.honeycomb(FDials.hiveHoneycomb.get(), face == HiveMath.DAILY_COMB, doubled);
        Chopper.giveChunked(p, HONEYCOMB, n, LootSource.FORAGING);
        Sky.service(SkillService.class).addXp(p, SkillType.FORAGING, FDials.hiveXp.get() * FDials.xpMultiplier.get(), "foraging:honey_hive");
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.FORAGING, HONEYCOMB, n, b.getLocation()));
        d.hivesHarvested++;
        d.honeycomb += n;
        boolean instant = rng.nextDouble() * 100 < Bonuses.honeyRefillPercent(p);
        if (instant) {
            d.hiveReady.remove(k);
            Msg.send(p, "&6Honey Refill! &7The hive filled right back up.");
        } else {
            d.hiveReady.put(k, now + HiveMath.refillMillis(FDials.hiveRefillSeconds.get(), face == HiveMath.DAILY_REFILL));
            showEmpty(p, b);
        }
        if (doubled) p.sendActionBar(Text.c("&6Honey Haul! &7Double honeycomb"));
        p.playSound(b.getLocation(), Sound.BLOCK_BEEHIVE_SHEAR, 1f, 1.1f);
        p.spawnParticle(Particle.DRIPPING_HONEY, b.getLocation().add(0.5, 0.2, 0.5), 10, 0.35, 0.2, 0.35, 0);
    }

    private static void showEmpty(Player p, Block b) {
        BlockData bd = b.getBlockData().clone();
        if (bd instanceof Beehive h) { h.setHoneyLevel(0); p.sendBlockChange(b.getLocation(), h); }
    }

    // ================================================================================ lathering

    private boolean lather(AbilityService.Use use) {
        Player p = use.player();
        if (!Sky.profiles().isReady(p)) return false;
        String island = Chopper.island(p.getWorld());
        if (!Chopper.toughIsland(island)) { Msg.error(p, "Lather trees on the Moonmarsh or in the Torrent Canyon."); return false; }
        Block b = p.getTargetBlockExact(5);
        Wood w = b == null ? null : Wood.ofBlock(b.getType().name(), true);
        if (w == null || w.toughness <= 0) { Msg.error(p, "Look at a fig, mangrove or spiral tree to lather it."); return false; }
        ForagingData d = ForagingModule.data(p);
        for (ForagingData.Lather l : d.lathered)
            if (l.world.equals(b.getWorld().getName()) && Math.abs(l.x - b.getX()) <= 4 && Math.abs(l.z - b.getZ()) <= 4) {
                Msg.error(p, "You already lathered this tree."); return false;
            }
        if (d.lathered.size() >= Math.max(1, FDials.latherMax.get())) {
            Msg.error(p, "You already have " + d.lathered.size() + " lathered trees waiting for critters."); return false;
        }
        ItemDef def = use.itemDef();
        double minutes = def != null && def.extra != null && def.extra.has("latherMinutes") ? def.extra.get("latherMinutes").getAsDouble() : 60;
        ItemStack hand = use.item();
        if (hand == null || hand.getAmount() < 1) return false;
        hand.setAmount(hand.getAmount() - 1);
        long wait = HiveMath.latherMillis(minutes, Bonuses.latherFasterPercent(p));
        boolean instant = ThreadLocalRandom.current().nextDouble() * 100 < Bonuses.honeySerendipityPercent(p);
        ForagingData.Lather l = new ForagingData.Lather();
        l.world = b.getWorld().getName(); l.x = b.getX(); l.y = b.getY(); l.z = b.getZ();
        l.wood = w.name(); l.pot = def == null ? null : def.id;
        l.due = System.currentTimeMillis() + (instant ? 0 : wait);
        d.lathered.add(l);
        p.playSound(b.getLocation(), Sound.BLOCK_HONEY_BLOCK_PLACE, 1f, 1f);
        p.spawnParticle(Particle.DRIPPING_HONEY, b.getLocation().add(0.5, 0.8, 0.5), 25, 0.6, 0.8, 0.6, 0);
        if (instant || wait == 0) Msg.send(p, (instant ? "&6Honey Serendipity! " : "") + "&7Something is already coming for the honey...");
        else Msg.send(p, "&aYou lathered the " + w.display + " tree. &7A critter will come for the honey in &a" + Text.duration(wait / 1000) + "&7.");
        return true;
    }

    /** Every 2 s: hive visuals, due lathered trees, lured critters that should leave. */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            ForagingData d = ForagingModule.data(p);
            if (!d.hiveReady.isEmpty()) refreshHives(p, d, now);
            if (!d.lathered.isEmpty()) checkLathered(p, d, now);
        }
        for (Iterator<Map.Entry<UUID, Long>> it = luredUntil.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() > now) continue;
            if (Bukkit.getEntity(e.getKey()) instanceof LivingEntity le && le.isValid()) Sky.mobs().remove(le);
            lured.remove(e.getKey());
            it.remove();
        }
    }

    private void refreshHives(Player p, ForagingData d, long now) {
        World w = p.getWorld();
        Location at = p.getLocation();
        for (Iterator<Map.Entry<String, Long>> it = d.hiveReady.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            String[] k = e.getKey().split(":");
            if (k.length != 4) { it.remove(); continue; }
            boolean due = e.getValue() <= now;
            if (!k[0].equals(w.getName())) { if (due) it.remove(); continue; }
            int x = ForagingModule.parse(k[1], 0), y = ForagingModule.parse(k[2], 0), z = ForagingModule.parse(k[3], 0);
            if (Math.abs(x - at.getBlockX()) > 64 || Math.abs(z - at.getBlockZ()) > 64) { if (due) it.remove(); continue; }
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = w.getBlockAt(x, y, z);
            if (!isHive(b.getType())) { it.remove(); continue; }
            if (due) { p.sendBlockChange(b.getLocation(), b.getBlockData()); it.remove(); }
            else showEmpty(p, b);
        }
    }

    private void checkLathered(Player p, ForagingData d, long now) {
        for (Iterator<ForagingData.Lather> it = d.lathered.iterator(); it.hasNext(); ) {
            ForagingData.Lather l = it.next();
            if (l.due > now) continue;
            World w = Bukkit.getWorld(l.world);
            if (w == null) { it.remove(); continue; }
            boolean near = p.getWorld().equals(w) && Math.abs(p.getLocation().getBlockX() - l.x) <= 48 && Math.abs(p.getLocation().getBlockZ() - l.z) <= 48;
            if (!near) {
                if (!l.told) { l.told = true; Msg.send(p, "&eA critter is waiting at the tree you lathered &7(" + l.x + ", " + l.z + ")&e. Go and see!"); }
                continue;
            }
            it.remove();
            Wood wood;
            try { wood = Wood.valueOf(l.wood); } catch (RuntimeException ex) { wood = Wood.FIG; }
            int n = 1 + (ThreadLocalRandom.current().nextDouble() * 100 < Bonuses.honeyLuckPercent(p) ? 1 : 0);
            int spawned = 0;
            for (int i = 0; i < n; i++) if (lure(p, w, l, wood)) spawned++;
            if (spawned == 0) Msg.send(p, "&7The honey on the " + wood.display + " tree dried up; no critter came. &8(no hunting critters are registered)");
            else {
                d.crittersLured += spawned;
                Msg.send(p, "&6" + (spawned > 1 ? "Two critters were" : "A critter was") + " drawn to your honey! &7Catch "
                        + (spawned > 1 ? "them" : "it") + " by the " + wood.display + " tree.");
                p.playSound(p.getLocation(), Sound.ENTITY_BEE_POLLINATE, 1f, 1f);
            }
        }
    }

    /** Spawn one critter of this wood's table beside the lathered log. */
    private boolean lure(Player p, World w, ForagingData.Lather l, Wood wood) {
        String id = pick(wood);
        if (id == null) return false;
        Location at = groundBeside(w, l.x, l.y, l.z);
        LivingEntity mob = Sky.mobs().spawn(id, at, p);
        if (mob == null) return false;
        lured.put(mob.getUniqueId(), p.getUniqueId());
        luredUntil.put(mob.getUniqueId(), System.currentTimeMillis() + 180_000L);   // it wanders off after 3 minutes (TUNE)
        w.spawnParticle(Particle.DRIPPING_HONEY, at.clone().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0);
        return true;
    }

    /** A weighted pick among the lure table's critters the hunting module registered, or null. */
    static String pick(Wood wood) {
        String[][] t = LURES.get(wood);
        if (t == null) return null;
        List<String[]> ok = new ArrayList<>();
        int total = 0;
        for (String[] row : t) if (Sky.mobs().def(row[0]) != null) { ok.add(row); total += Integer.parseInt(row[1]); }
        if (ok.isEmpty()) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (String[] row : ok) { r -= Integer.parseInt(row[1]); if (r < 0) return row[0]; }
        return ok.get(0)[0];
    }

    /** Standable air beside the base of the tree that log belongs to. */
    static Location groundBeside(World w, int x, int y, int z) {
        int by = y;
        while (by > w.getMinHeight() + 1 && Wood.ofBlock(w.getBlockAt(x, by - 1, z).getType().name(), true) != null) by--;
        int[][] dirs = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {3, 1}, {-3, -1}, {1, -3}, {-1, 3}};
        for (int[] dv : dirs)
            for (int dy = 3; dy >= -4; dy--) {
                Block feet = w.getBlockAt(x + dv[0], by + dy, z + dv[1]);
                if (feet.getType().isAir() && feet.getRelative(0, 1, 0).getType().isAir() && feet.getRelative(0, -1, 0).getType().isSolid())
                    return feet.getLocation().add(0.5, 0, 0.5);
            }
        return new Location(w, x + 0.5, by + 1, z + 0.5);
    }

    /** Lathering Greed: a lured critter you kill drops Honeycomb. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        if (e.mob == null) return;
        UUID owner = lured.remove(e.mob.getUniqueId());
        luredUntil.remove(e.mob.getUniqueId());
        if (owner == null || e.killer == null || !e.killer.getUniqueId().equals(owner)) return;
        int n = (int) Math.round(FDials.latherCombOnKill.get() * (1 + Bonuses.latheringGreedPercent(e.killer) / 100.0));
        if (n > 0) {
            Chopper.giveChunked(e.killer, HONEYCOMB, n, LootSource.MOB_DROP);
            if (Sky.profiles().isReady(e.killer)) ForagingModule.data(e.killer).honeycomb += n;
        }
    }

    /**
     * Lathering Greed for a CAPTURED critter (hunting request 10): hunting's lasso/net/black hole removes the critter
     * and then gives its shard through the loot service, so a shard gain while one of YOUR lured critters has just
     * vanished (not killed: onKill forgets those) is that capture.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShard(SkyEvents.ItemGain e) {
        if (e.player == null || e.itemId == null || !e.itemId.startsWith("SHARD_") || lured.isEmpty()) return;
        UUID me = e.player.getUniqueId();
        for (Iterator<Map.Entry<UUID, UUID>> it = lured.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, UUID> en = it.next();
            if (!me.equals(en.getValue())) continue;
            if (Bukkit.getEntity(en.getKey()) instanceof LivingEntity le && le.isValid()) continue;
            it.remove();
            luredUntil.remove(en.getKey());
            int n = (int) Math.round(FDials.latherCombOnKill.get() * (1 + Bonuses.latheringGreedPercent(e.player) / 100.0));
            if (n > 0) {
                Chopper.giveChunked(e.player, HONEYCOMB, n, LootSource.MOB_DROP);
                if (Sky.profiles().isReady(e.player)) ForagingModule.data(e.player).honeycomb += n;
            }
            return;   // one capture, one payout
        }
    }

    int luredAlive() { return lured.size(); }
}
