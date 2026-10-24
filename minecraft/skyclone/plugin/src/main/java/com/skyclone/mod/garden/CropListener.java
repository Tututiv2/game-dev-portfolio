package com.skyclone.mod.garden;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Everything that happens to blocks on a Garden: harvesting with Farming Fortune and crop
 * fortune, auto-replant and regrowth, milestones, pests rolls, plot ownership, planting the three
 * flowers, the scythe, and the Desk / Composter blocks in the barn.
 *
 * <p>Plots are BUILD zones in the template (so players can design farms), so core lets these
 * events through; this listener (HIGH) is what keeps a locked plot or someone else's garden safe.
 */
final class CropListener implements Listener {

    private final Growth growth;
    private final Map<UUID, Long> nextTell = new HashMap<>();

    /**
     * Crops that are fully grown the moment they are placed (melon, pumpkin, mushrooms, the three
     * flowers). A freshly placed one must grow for one regrow period before it can be harvested,
     * otherwise place-break would multiply items by Farming Fortune with no wait (a dupe).
     * Position key -&gt; millis it becomes harvestable. Held (and persisted) by {@link Growth} since wave 5,
     * so a restart no longer makes a just-placed melon ripe.
     */
    private final Map<String, Long> fresh;

    static final Set<Material> RIPE_ON_PLACE = Set.of(Material.MELON, Material.PUMPKIN, Material.RED_MUSHROOM,
            Material.BROWN_MUSHROOM, Material.SUNFLOWER, Material.ROSE_BUSH, Material.BLUE_ORCHID);

    CropListener(Growth growth) { this.growth = growth; this.fresh = growth.fresh; this.placedOvergrowth = growth.placed; }

    private static String pos(Block b) { return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    private void markFresh(Block b, double seconds) {
        long now = System.currentTimeMillis();
        if (fresh.size() > 20_000) fresh.values().removeIf(t -> t < now);
        fresh.put(pos(b), now + (long) (seconds * 1000));
        growth.marksChanged();
    }

    /** Seconds until a freshly planted crop can be harvested, or 0 when it is ripe. */
    private long freshLeft(Block b) {
        Long t = fresh.get(pos(b));
        if (t == null) return 0;
        long left = t - System.currentTimeMillis();
        if (left <= 0) { fresh.remove(pos(b)); growth.marksChanged(); return 0; }
        return (left + 999) / 1000;
    }

    /** The lower half of a two-block flower, else the block itself. */
    private static Block base(Block b) {
        return b.getBlockData() instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP ? b.getRelative(BlockFace.DOWN) : b;
    }

    private void tell(Player p, String msg) {
        long now = System.currentTimeMillis();
        Long n = nextTell.get(p.getUniqueId());
        if (n != null && now < n) return;
        nextTell.put(p.getUniqueId(), now + 2000);
        Msg.error(p, msg);
    }

    private static boolean gardenWorld(Location l) { return G.ISLAND.equals(Sky.worlds().islandOf(l)); }

    /** Plot id the player may change at {@code l}, or null (and a message) when they may not. */
    private String ownedPlot(Player p, Location l, boolean quiet) {
        int[] t = G.template(G.key(p), l);
        if (t == null) { if (!quiet) tell(p, "This isn't your Garden!"); return null; }
        String plot = G.plotAt(t[0], t[2]);
        if (plot == null) return null;
        if (!G.ready(p)) return null;
        if (!G.data(p).plots.containsKey(plot)) {
            if (!quiet) tell(p, "This plot is still locked! Unlock it at the Desk in your barn.");
            return null;
        }
        return plot;
    }

    // =====================================================================================
    // breaking
    // =====================================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!gardenWorld(b.getLocation())) return;
        Player p = e.getPlayer();
        if (Sky.worlds().buildBypass(p)) return;
        if (e.isCancelled()) return;                     // core already refused (barn plot, paths)
        String plot = ownedPlot(p, b.getLocation(), false);
        if (plot == null) { e.setCancelled(true); return; }
        GardenData d = G.data(p);
        GardenTables.Crop crop = G.T.cropByBlock(b.getType().name().toLowerCase(java.util.Locale.ROOT));
        if (crop != null) {
            if (G.level(d) < crop.level) {
                // a crop you cannot farm yet (wild moonflowers and cane in an overgrown plot) is just
                // overgrowth to you: it clears, with no drops, so a plot can always be cleaned up
                e.setCancelled(true);
                clearColumn(b);
                clearReward(p, d, plot, 1);
                tell(p, crop.name + " can only be farmed from Garden " + GardenMath.roman(crop.level) + " - cleared it away.");
                return;
            }
            e.setCancelled(true);
            if (RIPE_ON_PLACE.contains(b.getType())) {
                long left = freshLeft(base(b));
                if (left > 0) { tell(p, "This " + crop.name + " was only just planted! Ready in " + left + "s."); return; }
            }
            harvest(p, d, plot, crop, b);
            return;
        }
        if (isOvergrowth(b.getType())) {
            boolean mine = placedOvergrowth.remove(pos(base(b)));
            if (mine) growth.marksChanged();
            int n = mine ? 0 : 1;
            n += scythe(p, d, plot, b);
            clearReward(p, d, plot, n);
        }
    }

    /**
     * Overgrowth a player placed themselves (leaves, flowers carried in) pays nothing when broken
     * again. Held (and persisted) by {@link Growth} since wave 5; the per-plot cap still bounds it.
     */
    private final Set<String> placedOvergrowth;

    /**
     * Clearing a plot's overgrowth pays (research 08 1.3 "Plot clearing: Garden-only drops (Overgrown
     * Grass etc.) (?)"): a little Farming XP per block and a rare Overgrown Grass, for the first
     * {@code garden.clear-reward-cap} blocks of each plot.
     */
    private void clearReward(Player p, GardenData d, String plot, int blocks) {
        if (blocks <= 0) return;
        GardenData.PlotState s = d.plots.get(plot);
        if (s == null) return;
        int pay = Math.max(0, Math.min(blocks, G.clearRewardCap.get() - s.cleared));
        if (pay <= 0) return;
        s.cleared += pay;
        G.farmingXp(p, pay * G.clearFarmingXp.get(), "garden:clearing");
        double chance = EffectMath.overbloomChance(G.clearRareChance.get() * (1 + Sky.stats().get(p, Stat.FARMING_FORTUNE) / 1000.0),
                Effects.overbloom(p, false));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < pay; i++) {
            if (r.nextDouble() >= chance || !G.exists("OVERGROWN_GRASS")) continue;
            G.give(p, "OVERGROWN_GRASS", 1, LootSource.FARMING);
            Msg.rareDrop(p, "&6&lPLOT CLEARING DROP!", G.itemName("OVERGROWN_GRASS"), 0);
        }
        if (s.cleared >= G.clearRewardCap.get())
            Msg.send(p, "&a" + G.plotName(d, plot) + " &7is cleared of its wild growth - no more clearing rewards here.");
    }

    private static final Set<Material> OVERGROWTH = Set.of(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
            Material.POPPY, Material.DANDELION, Material.CORNFLOWER, Material.OXEYE_DAISY, Material.AZURE_BLUET, Material.ALLIUM,
            Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP, Material.LILY_OF_THE_VALLEY,
            Material.PINK_PETALS, Material.LEAF_LITTER, Material.BUSH, Material.OAK_LEAVES, Material.BIRCH_LEAVES,
            Material.DARK_OAK_LEAVES, Material.CHERRY_LEAVES, Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES,
            Material.SPRUCE_LEAVES, Material.LILY_PAD);

    private static boolean isOvergrowth(Material m) { return OVERGROWTH.contains(m); }

    /** Remove a plant without drops: a whole cane/cactus column, both halves of a tall flower, or the block. */
    private static void clearColumn(Block b) {
        Material m = b.getType();
        if (m == Material.SUGAR_CANE || m == Material.CACTUS) {
            Block bottom = b;
            while (bottom.getRelative(BlockFace.DOWN).getType() == m) bottom = bottom.getRelative(BlockFace.DOWN);
            List<Block> col = new ArrayList<>();
            for (Block s = bottom; s.getType() == m && col.size() < 32; s = s.getRelative(BlockFace.UP)) col.add(s);
            for (int i = col.size() - 1; i >= 0; i--) col.get(i).setType(Material.AIR, false);
            return;
        }
        if (b.getBlockData() instanceof Bisected) {
            Block lower = base(b);
            lower.getRelative(BlockFace.UP).setType(Material.AIR, false);
            lower.setType(Material.AIR, false);
            return;
        }
        b.setType(Material.AIR, false);
    }

    /** Garden Scythe / Starter Scythe: clear grass, leaves and flowers around the broken block. */
    /** Returns how many overgrowth blocks around it were cleared (player-placed ones not counted). */
    private int scythe(Player p, GardenData d, String plot, Block b) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(hand);
        if (id == null) return 0;
        ItemDef def = Sky.items().def(id);
        if (def == null || !def.extra.has("radius")) return 0;
        int r = def.extra.get("radius").getAsInt();
        int[] t0 = G.template(G.key(p), b.getLocation());
        if (t0 == null) return 0;
        int n = 0;
        for (int dy = -1; dy <= 4; dy++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Block o = b.getRelative(dx, dy, dz);
                    boolean locked = false;
                    if (!isOvergrowth(o.getType())) {
                        GardenTables.Crop oc = G.T.cropByBlock(o.getType().name().toLowerCase(java.util.Locale.ROOT));
                        if (oc == null || G.level(d) >= oc.level) continue;
                        locked = true;                              // wild moonflowers / cane you cannot farm yet
                    }
                    String op = G.plotAt(t0[0] + dx, t0[2] + dz);
                    if (!plot.equals(op)) continue;
                    if (o.getType().isAir()) continue;
                    if (locked) { clearColumn(o); n++; continue; }
                    if (o.getBlockData() instanceof Bisected bi) {
                        if (bi.getHalf() == Bisected.Half.TOP) continue;
                        clearColumn(o);                             // both halves, or the top would float
                    } else o.setType(Material.AIR, false);
                    if (!placedOvergrowth.remove(pos(o))) n++; else growth.marksChanged();
                }
        if (n > 0) Msg.sound(p, Sound.BLOCK_GRASS_BREAK, 1.2f);
        return n;
    }

    /** Harvest one crop block. The event is already cancelled. */
    private void harvest(Player p, GardenData d, String plot, GardenTables.Crop crop, Block b) {
        Material m = b.getType();
        double regrow = GardenMath.regrowSeconds(G.regrowSeconds.get(), G.cropGrowth(d));
        int units;
        String mainItem = crop.item;
        switch (m) {
            case WHEAT, CARROTS, POTATOES, NETHER_WART, COCOA -> {
                if (!(b.getBlockData() instanceof Ageable a)) return;
                if (a.getAge() < a.getMaximumAge()) {
                    if (!G.protectYoung.get()) b.setType(Material.AIR);
                    return;
                }
                a.setAge(0);
                b.setBlockData(a, false);
                growth.ageable(b, regrow);
                units = 1;
            }
            case MELON, PUMPKIN, RED_MUSHROOM, BROWN_MUSHROOM, BLUE_ORCHID -> {
                BlockData keep = b.getBlockData().clone();
                b.setType(Material.AIR, false);
                growth.restore(b.getWorld(), b.getX(), b.getY(), b.getZ(), keep, regrow);
                if (m == Material.BROWN_MUSHROOM) mainItem = crop.extraItem != null ? crop.extraItem : crop.item;
                units = 1;
            }
            case SUGAR_CANE, CACTUS -> {
                Block start = b;
                if (b.getRelative(BlockFace.DOWN).getType() != m) {
                    start = b.getRelative(BlockFace.UP);              // the base stays planted
                    if (start.getType() != m) { return; }
                }
                List<Block> seg = new ArrayList<>();
                for (Block s = start; s.getType() == m && seg.size() < 16; s = s.getRelative(BlockFace.UP)) seg.add(s);
                BlockData keep = Bukkit.createBlockData(m);
                for (int i = seg.size() - 1; i >= 0; i--) seg.get(i).setType(Material.AIR, false);
                for (int i = 0; i < seg.size(); i++) {
                    Block s = seg.get(i);
                    growth.restore(s.getWorld(), s.getX(), s.getY(), s.getZ(), keep, regrow + i * 0.25);
                }
                units = seg.size();
            }
            case SUNFLOWER, ROSE_BUSH -> {
                Block lower = b.getBlockData() instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP ? b.getRelative(BlockFace.DOWN) : b;
                Block upper = lower.getRelative(BlockFace.UP);
                BlockData lo = lower.getBlockData().clone(), up = upper.getBlockData().clone();
                upper.setType(Material.AIR, false);
                lower.setType(Material.AIR, false);
                growth.restore(lower.getWorld(), lower.getX(), lower.getY(), lower.getZ(), lo, regrow);
                if (up.getMaterial() == m) growth.restore(upper.getWorld(), upper.getX(), upper.getY(), upper.getZ(), up, regrow + 0.1);
                units = 1;
            }
            default -> { return; }
        }
        // ---- drops ----
        Visitors.harvested(G.key(p));          // the Quickdraw chip speeds the visitor clock while someone harvests
        double fortune = fortune(p, d, crop, plot);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long main = 0, extra = 0;
        for (int u = 0; u < units; u++) {
            int base = crop.baseMin + (crop.baseMax > crop.baseMin ? r.nextInt(crop.baseMax - crop.baseMin + 1) : 0);
            main += GardenMath.fortuneDrops(base, fortune, r.nextDouble());
            if ("WHEAT".equals(crop.key) && crop.extraItem != null) extra += GardenMath.fortuneDrops(1 + r.nextInt(2), fortune, r.nextDouble());
        }
        if (G.exists(mainItem)) G.give(p, mainItem, main, LootSource.FARMING);
        if (extra > 0 && G.exists(crop.extraItem)) G.give(p, crop.extraItem, extra, LootSource.FARMING);
        G.farmingXp(p, crop.xp * units, "garden:" + crop.key.toLowerCase(java.util.Locale.ROOT));
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.FARMING, mainItem, (int) Math.min(Integer.MAX_VALUE, main), b.getLocation()));
        d.cropsBroken += units;
        // Sowdust (lane 13, wiki): ceil(total Farming Fortune / 100) per block, halved for Sugar Cane and Cactus,
        // + the Elephant's Abundant Harvest and the Earthy reforge
        Chips.earn(d, units * EffectMath.cropSowdust(fortune, G.T.garden.sowdust.halved.contains(crop.key), Chips.boostPct(p)));
        milestone(p, d, crop, main);
        for (int u = 0; u < units; u++) GardenModule.pests.onCropBroken(p, d, plot, crop, b.getLocation());
    }

    /** Farming Fortune + this crop's fortune (crop upgrades, tool), less the live-pest penalty. */
    static double fortune(Player p, GardenData d, GardenTables.Crop crop) { return fortune(p, d, crop, null); }

    /**
     * {@code plot} (may be null) is where the crop was broken: the Slug's Foul Aroma pays on a sprayed plot.
     * Wave 5 adds the per-crop fortune other modules' things carry on the Garden: accessories' {@code otherStats}
     * (Cropling / Squash / Fermenta...), the Mosquito's Buzzing Trader (Sugar Cane, per unique visitor) and the Slug.
     */
    static double fortune(Player p, GardenData d, GardenTables.Crop crop, String plot) {
        double f = Sky.stats().get(p, Stat.FARMING_FORTUNE);
        f += d.upgrade(crop.key) * G.T.garden.cropUpgrades.fortunePerTier;
        f += toolCropFortune(p.getInventory().getItemInMainHand(), crop.key);
        f += Effects.accessoryCropFortune(p, crop.key);
        if ("SUGAR_CANE".equals(crop.key)) f += EffectMath.buzzinBarterer(Effects.pet(p, "mosquito_buzzin_barterer"), d.uniqueServed());
        GardenData.PlotState ps = plot == null ? null : d.plots.get(plot);
        if (ps != null && ps.sprayUntil > System.currentTimeMillis()) f += Effects.pet(p, "slug_repugnant_aroma");
        // the Overdrive chip: +5 crop fortune a level for a crop of the running Harvest Fair
        double od = G.chipValue(d, "contest_fortune");
        if (od > 0 && Effects.fairCrop(p, crop.key)) f += od;
        double bpc = Sky.stats().get(p, Stat.PEST_CHANCE);
        int pen = GardenMath.penalty(G.T.garden.pests.penalty, bpc, d.livePests());
        return f * (1 - pen / 100.0);
    }

    /**
     * A tool's crop-specific fortune, read from {@code extra.crop_fortune} ({"WHEAT": 50}) on its
     * ItemDef and on its ItemData (counters write the instance one). Farming owns the tools; this
     * is the documented hand-shake (see the hand-off).
     */
    static double toolCropFortune(ItemStack hand, String cropKey) {
        String id = Sky.items().idOf(hand);
        if (id == null) return 0;
        double v = 0;
        ItemDef def = Sky.items().def(id);
        if (def != null) v += read(def.extra, cropKey);
        try {
            ItemData data = Sky.items().data(hand);
            v += read(data.extra, cropKey);
        } catch (Throwable ignored) { }
        return v;
    }

    private static double read(JsonObject extra, String cropKey) {
        if (extra == null || !extra.has("crop_fortune")) return 0;
        JsonElement e = extra.get("crop_fortune");
        if (e.isJsonPrimitive()) return e.getAsDouble();
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has(cropKey)) return o.get(cropKey).getAsDouble();
            if (o.has("ALL")) return o.get("ALL").getAsDouble();
        }
        return 0;
    }

    /** Count harvested crops towards the crop's milestone, paying each new tier. */
    static void milestone(Player p, GardenData d, GardenTables.Crop crop, long amount) {
        long now = d.milestones.merge(crop.key, amount, Long::sum);
        int tier = GardenMath.milestoneTier(now, crop.milestones);
        int paid = d.milestonePaid.getOrDefault(crop.key, 0);
        if (tier <= paid) return;
        for (int t = paid + 1; t <= tier; t++) {
            int gxp = GardenMath.milestoneGardenXp(t);
            int sb = (int) Math.round(G.milestoneSbXp.get());
            Msg.box(p, "&a&lGARDEN MILESTONE &e" + crop.name + " &8" + GardenMath.roman(t - 1) + "➜&e" + GardenMath.roman(t),
                    List.of("&a&lREWARDS", "  &8+&2" + gxp + " &7Garden Experience", "  &8+&b" + sb + " SkyBlock XP"));
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
            d.milestonePaid.put(crop.key, t);
            G.award(p, "crop:milestone:" + crop.key + ":" + t, sb, crop.name + " Milestone " + GardenMath.roman(t));
            G.gardenXp(p, d, gxp, "milestone");
        }
    }

    // =====================================================================================
    // placing, buckets, planting
    // =====================================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        if (!gardenWorld(b.getLocation())) return;
        Player p = e.getPlayer();
        if (Sky.worlds().buildBypass(p)) return;
        String plot = ownedPlot(p, b.getLocation(), false);
        if (plot == null) { e.setCancelled(true); return; }
        GardenData d = G.data(p);
        GardenTables.Crop crop = G.T.cropByBlock(b.getType().name().toLowerCase(java.util.Locale.ROOT));
        if (crop != null && G.level(d) < crop.level) {
            e.setCancelled(true);
            tell(p, crop.name + " needs Garden " + GardenMath.roman(crop.level) + " before it can be planted here!");
            return;
        }
        Material placed = b.getType();
        if (isOvergrowth(placed)) {
            if (placedOvergrowth.size() > 50_000) placedOvergrowth.clear();
            placedOvergrowth.add(pos(b));
            growth.marksChanged();
        }
        if ((placed == Material.SUGAR_CANE || placed == Material.CACTUS) && b.getRelative(BlockFace.DOWN).getType() == placed) {
            // stacking a cane on a cane and breaking it again would return more than was placed
            e.setCancelled(true);
            tell(p, "Plant " + (placed == Material.CACTUS ? "cactus" : "sugar cane") + " on the ground and let it grow!");
            return;
        }
        if (crop != null && RIPE_ON_PLACE.contains(placed))
            markFresh(b, GardenMath.regrowSeconds(G.regrowSeconds.get(), G.cropGrowth(d)));
        // a freshly planted crop grows on the garden's timer (vanilla growth still helps)
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            double t = GardenMath.regrowSeconds(G.regrowSeconds.get(), G.cropGrowth(d)) * 1.5;
            Block now = b.getWorld().getBlockAt(b.getX(), b.getY(), b.getZ());
            if (now.getBlockData() instanceof Ageable a && crop != null && a.getAge() < a.getMaximumAge()) growth.ageable(now, t);
            else if (now.getType() == Material.SUGAR_CANE || now.getType() == Material.CACTUS) {
                BlockData keep = Bukkit.createBlockData(now.getType());
                if (now.getRelative(BlockFace.DOWN).getType() != now.getType()) {
                    growth.restore(now.getWorld(), now.getX(), now.getY() + 1, now.getZ(), keep, t);
                    growth.restore(now.getWorld(), now.getX(), now.getY() + 2, now.getZ(), keep, t + 0.25);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!gardenWorld(e.getBlock().getLocation()) || Sky.worlds().buildBypass(e.getPlayer())) return;
        if (ownedPlot(e.getPlayer(), e.getBlock().getLocation(), false) == null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!gardenWorld(e.getBlock().getLocation()) || Sky.worlds().buildBypass(e.getPlayer())) return;
        if (ownedPlot(e.getPlayer(), e.getBlock().getLocation(), false) == null) e.setCancelled(true);
    }

    /** The barn's Desk (lectern) and composter blocks, flower planting, garden chips. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && b != null && gardenWorld(b.getLocation()) && !buildZone(b.getLocation())) {
            if (b.getType() == Material.LECTERN) { e.setCancelled(true); if (G.ready(p)) Desk.open(p); return; }
            if (b.getType() == Material.COMPOSTER) { e.setCancelled(true); if (G.ready(p)) Composter.open(p); return; }
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack hand = e.getItem();
        String id = hand == null ? null : Sky.items().idOf(hand);
        if (id == null || !G.ready(p)) return;
        if (G.T.garden.chips.containsKey(id)) { e.setCancelled(true); Chips.redeem(p, hand, id); return; }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && b != null && e.getBlockFace() == BlockFace.UP && gardenWorld(b.getLocation()))
            plantFlower(e, p, b, id, hand);
    }

    private static boolean buildZone(Location l) {
        Layout.Zone z = Sky.worlds().zoneAt(l);
        return z != null && z.flags().contains(Layout.Zone.BUILD);
    }

    /** Sunflower / Moonflower / Wild Rose items are crops: right-click grass or dirt to plant one. */
    private void plantFlower(PlayerInteractEvent e, Player p, Block ground, String id, ItemStack hand) {
        GardenTables.Crop crop = G.T.cropByItem(id);
        if (crop == null || !(crop.key.equals("SUNFLOWER") || crop.key.equals("MOONFLOWER") || crop.key.equals("WILD_ROSE"))) return;
        Material g = ground.getType();
        if (g != Material.GRASS_BLOCK && g != Material.DIRT && g != Material.FARMLAND && g != Material.PODZOL && g != Material.COARSE_DIRT) return;
        Block at = ground.getRelative(BlockFace.UP);
        e.setCancelled(true);
        String plot = ownedPlot(p, at.getLocation(), false);
        if (plot == null) return;
        GardenData d = G.data(p);
        if (G.level(d) < crop.level) { tell(p, crop.name + " needs Garden " + GardenMath.roman(crop.level) + "!"); return; }
        boolean tall = !crop.key.equals("MOONFLOWER");
        if (!at.getType().isAir() || tall && !at.getRelative(BlockFace.UP).getType().isAir()) return;
        if (g == Material.FARMLAND) ground.setType(Material.DIRT, false);
        Material m = crop.key.equals("SUNFLOWER") ? Material.SUNFLOWER : crop.key.equals("WILD_ROSE") ? Material.ROSE_BUSH : Material.BLUE_ORCHID;
        if (tall) {
            BlockData lo = Bukkit.createBlockData(m, "[half=lower]");
            BlockData up = Bukkit.createBlockData(m, "[half=upper]");
            at.setBlockData(lo, false);
            at.getRelative(BlockFace.UP).setBlockData(up, false);
        } else at.setType(m, false);
        markFresh(at, GardenMath.regrowSeconds(G.regrowSeconds.get(), G.cropGrowth(d)));
        if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1); else p.getInventory().setItemInMainHand(null);
        Msg.sound(p, Sound.BLOCK_GRASS_PLACE, 1f);
    }

    static String fortuneLine(Player p, GardenData d, GardenTables.Crop crop) {
        return "&6☘ " + Text.commasInt(fortune(p, d, crop)) + " " + crop.name + " Fortune";
    }
}
