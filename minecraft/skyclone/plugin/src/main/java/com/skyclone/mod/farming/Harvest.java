package com.skyclone.mod.farming;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Crop breaking on the islands this module owns (hub fields, The Barn, the Mushroom Desert): fortune, drops,
 * Farming XP, the Gather event, tool counters, Harvest Fair score and contest drops, then regrowth.
 */
final class Harvest implements WorldService.BreakHandler {

    private final FarmingModule m;

    Harvest(FarmingModule m) { this.m = m; }

    @Override public int priority() { return 100; }

    @Override
    public boolean handles(Player p, Block block, Layout.Zone zone) {
        String island = Sky.worlds().islandOf(block.getWorld());
        if (island == null || !m.dials.islandSet().contains(island)) return false;
        Material t = block.getType();
        // crops, plus stems and the soil under crops (claimed so nothing else does anything with them)
        boolean ours = Crop.ofBlock(t.getKey().getKey()) != null || t == Material.MELON_STEM || t == Material.PUMPKIN_STEM
                || t == Material.ATTACHED_MELON_STEM || t == Material.ATTACHED_PUMPKIN_STEM || t == Material.FARMLAND;
        // the zone lookup only for crop blocks: every other break on the hub (the coal mine...) skips it
        return ours && (!"hub".equals(island) || inHubFields(block));
    }

    /**
     * The hub is a town first: crops planted as decoration outside the farm district (window boxes, village gardens)
     * are not a field. Only blocks inside the zones/areas named by {@code farming.hub-zones} are farmed (blank = all).
     */
    boolean inHubFields(Block b) {
        String names = m.dials.hubZones.get();
        if (names == null || names.isBlank()) return true;
        Layout l = Sky.worlds().layout("hub");
        if (l == null) return true;
        double x = b.getX() + 0.5, y = b.getY() + 0.5, z = b.getZ() + 0.5;
        for (String n : names.split(",")) {
            String id = n.trim();
            if (id.isEmpty()) continue;
            com.skyclone.api.world.gen.Box area = l.area(id);
            if (area != null && area.grow(4).contains(x, y, z)) return true;
            for (Layout.Zone zn : l.zones()) if (zn.id().equals(id) && zn.box().contains(x, y, z)) return true;
        }
        return false;
    }

    @Override
    public void onBreak(Player p, Block block, BlockBreakEvent event) {
        Crop crop = Crop.ofBlock(block.getType().getKey().getKey());
        if (crop == null) return;
        if (!Sky.profiles().isReady(p)) return;
        if (m.regrowth.isPending(block) && crop.shape == Crop.Shape.AGEABLE) return;   // still growing back
        List<Block> taken = new ArrayList<>();
        switch (crop.shape) {
            case AGEABLE -> {
                if (!(block.getBlockData() instanceof Ageable ag) || ag.getAge() < ag.getMaximumAge()) {
                    immature(p);
                    return;
                }
                taken.add(block);
            }
            case BLOCK, PLANT -> taken.add(block);
            case STACK -> {
                Block bottom = block;
                while (bottom.getRelative(BlockFace.DOWN).getType() == block.getType()) bottom = bottom.getRelative(BlockFace.DOWN);
                Block from = block.equals(bottom) ? bottom.getRelative(BlockFace.UP) : block;
                if (from.getType() != block.getType()) { immature(p); return; }   // only the root is left: it is growing
                for (Block b = from; b.getType() == block.getType(); b = b.getRelative(BlockFace.UP)) taken.add(b);
            }
            case TALL -> {
                Block lower = block;
                if (block.getBlockData() instanceof Bisected bi && bi.getHalf() == Bisected.Half.TOP) lower = block.getRelative(BlockFace.DOWN);
                taken.add(lower);
            }
        }
        String mushroomColor = block.getType() == Material.BROWN_MUSHROOM ? "brown" : "red";
        ItemStack held = p.getInventory().getItemInMainHand();
        int drops = harvest(p, crop, taken.size(), held, mushroomColor, block.getLocation().add(0.5, 0.5, 0.5));
        if (drops < 0) return;
        // ---- take the blocks and schedule them back ----
        double secs = crop.regrowSeconds * Math.max(0.05, m.dials.regrowMultiplier.get());
        breakFx(block.getLocation(), block.getBlockData());   // Effect.STEP_SOUND is marked for removal in 26.2
        switch (crop.shape) {
            case AGEABLE -> {
                BlockData mature = block.getBlockData().clone();
                Ageable young = (Ageable) mature.clone();
                young.setAge(0);
                block.setBlockData(young, false);
                m.regrowth.schedule(block, mature, secs, m.dials.regrowStages.get());
            }
            case BLOCK, PLANT -> {
                BlockData was = block.getBlockData().clone();
                block.setType(Material.AIR, false);
                m.regrowth.schedule(block, was, secs, 1);
            }
            case STACK -> {
                // top-down removal without physics; each block comes back one interval after the one below
                for (int i = taken.size() - 1; i >= 0; i--) {
                    Block b = taken.get(i);
                    BlockData was = b.getBlockData().clone();
                    b.setType(Material.AIR, false);
                    m.regrowth.schedule(b, was, secs * (i + 1), 1);
                }
            }
            case TALL -> {
                Block lower = taken.get(0), upper = lower.getRelative(BlockFace.UP);
                BlockData lo = lower.getBlockData().clone(), up = upper.getBlockData().clone();
                upper.setType(Material.AIR, false);
                lower.setType(Material.AIR, false);
                m.regrowth.schedule(lower, lo, secs, 1);
                m.regrowth.schedule(upper, up, secs + 0.05, 1);
            }
        }
    }

    private final Map<java.util.UUID, Long> lockedHintAt = new java.util.HashMap<>();

    /** Tell (at most every 30 s) why a held farming tool adds nothing: core's own requirement message names the gap. */
    private void lockedHint(Player p, ItemStack held) {
        long now = System.currentTimeMillis();
        Long last = lockedHintAt.get(p.getUniqueId());
        if (last != null && now - last < 30_000L) return;
        lockedHintAt.put(p.getUniqueId(), now);
        Sky.items().meetsRequirements(p, held, true);
        Sky.vitals().flash(p, "&cYour tool's fortune is locked until you meet its requirements!", 40);
    }

    private void immature(Player p) {
        // a quiet hint, rate-limited by the action bar itself
        Sky.vitals().flash(p, "&cThis crop is still growing!", 20);
    }

    /**
     * Everything a harvest pays: drops (fortune, dicer, extra items), XP, Gather, tool progress, contest.
     * {@code blocks} = blocks harvested in this break. Returns the main drop count, or -1 if refused.
     * Also used by the admin "simulate" command (with {@code at} = the player's location).
     */
    int harvest(Player p, Crop crop, int blocks, ItemStack held, String mushroomColor, org.bukkit.Location at) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Fortune f = fortune(p, crop, held, mushroomColor);
        int drops = 0;
        for (int i = 0; i < blocks; i++) {
            int base = crop.minDrops + (crop.maxDrops > crop.minDrops ? rnd.nextInt(crop.maxDrops - crop.minDrops + 1) : 0);
            drops += FortuneMath.roll(base, f.total(), rnd.nextDouble());
        }
        drops = (int) Math.max(blocks > 0 ? 1 : 0, Math.round(drops * Math.max(0, m.dials.dropMultiplier.get())));
        String refId = crop == Crop.MUSHROOM ? (mushroomColor.equals("brown") ? "BROWN_MUSHROOM" : "RED_MUSHROOM") : crop.dropId;
        String dropId = resolve(refId, crop);
        Map<String, Integer> gains = new LinkedHashMap<>();
        gains.merge(dropId, drops, Integer::sum);

        // extras (a tool you may not use yet neither dices nor counts - see fortune())
        boolean locked = f.parts().stream().anyMatch(pt -> pt.source().startsWith("&c"));
        ToolTracker.View tool = locked ? null : m.tools.view(p, held);
        if (locked) lockedHint(p, held);
        if (crop == Crop.WHEAT) {
            int seeds = 0;
            for (int i = 0; i < blocks; i++) seeds += FortuneMath.roll(1 + rnd.nextInt(2), f.total(), rnd.nextDouble());
            gains.merge(resolve("SEEDS", null), seeds, Integer::sum);
        }
        if (crop == Crop.POTATO)
            for (int i = 0; i < blocks; i++) if (rnd.nextDouble() < m.dials.poisonChance.get()) gains.merge(resolve("POISONOUS_POTATO", null), 1, Integer::sum);
        if (tool != null && tool.spec().dicer > 0 && tool.spec().boosts(crop)) {
            for (int i = 0; i < blocks; i++) if (rnd.nextDouble() < tool.spec().dicer) {
                int bonus = (int) Math.round(crop.averageDrops() * m.dials.dicerMultiplier.get() * Math.max(1, tool.spec().mk));
                gains.merge(dropId, bonus, Integer::sum);
                drops += bonus;
                Msg.send(p, "&6&lDICER! &7Your " + tool.spec().crops.get(0).display + " burst into &a+" + bonus + " &7extra drops!");
                Msg.sound(p, Sound.BLOCK_WOOD_BREAK, 0.6f);
            }
        }
        // contest
        boolean inContest = m.contests.onHarvest(p, crop, drops);
        if (inContest) {
            int rare = contestFinds(p, crop, blocks);
            if (rare > 0) gains.merge(crop.contestDrop, rare, Integer::sum);
        }

        // give
        for (Map.Entry<String, Integer> e : gains.entrySet()) give(p, e.getKey(), e.getValue());

        // XP
        double xp = crop.xp * blocks * Math.max(0, m.dials.xpMultiplier.get());
        if (xp > 0) Sky.service(SkillService.class).addXp(p, SkillType.FARMING, xp, "farming:" + crop.name().toLowerCase());

        // bookkeeping
        FarmData d = m.data(p);
        d.cropsBroken.merge(crop.name(), (long) blocks, Long::sum);
        d.cropsCollected.merge(crop.name(), (long) drops, Long::sum);
        if (tool != null && tool.uuid() != null && (tool.spec().boosts(crop) || tool.spec().generic()))
            m.tools.add(p, held, tool.uuid(), drops, drops * m.dials.toolXpPerDrop.get() * m.perks.toolXpMultiplier(p));

        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.FARMING, refId, drops, at));
        // a grown crop broken here can make a Shiny Pig appear (wave 5)
        if (m.shiny != null && blocks > 0) m.shiny.onHarvest(p, at);
        if (crop == Crop.WHEAT) Sky.service(Features.Objectives.class).complete(p, "harvest_wheat");
        return drops;
    }

    /**
     * Rolls the Harvest Fair's rare crop (Cropling / Squash / Fermenta / Helianthus) once per harvested block and
     * announces each find. Returns how many were found; the caller gives them.
     */
    int contestFinds(Player p, Crop crop, int blocks) {
        double ch = m.dials.contestDropChance(crop.contestDrop);
        if (ch <= 0 || !Sky.items().exists(crop.contestDrop)) return 0;
        // Overbloom: "chance for rare crops" (research 01 stat table), from reforges / accessories / pets - wave 5
        if (m.perks != null) ch *= PerkMath.overbloomMultiplier(m.perks.overbloom(p), m.dials.overbloomPercent.get());
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int n = 0;
        for (int i = 0; i < blocks; i++) if (rnd.nextDouble() < ch) n++;
        if (n > 0) {
            ItemDef d = Sky.items().def(crop.contestDrop);
            for (int i = 0; i < n; i++) Msg.rareDrop(p, "&6&lRARE CROP!", d.rarity.code() + Text.strip(d.name), 0);
        }
        return n;
    }

    /** Hand out {@code n} of an item in stack-sized pieces through the loot service. */
    static void give(Player p, String id, int n) { give(p, id, n, LootSource.FARMING); }

    static void give(Player p, String id, int n, LootSource source) {
        if (n <= 0 || id == null || !Sky.items().exists(id)) return;
        ItemDef d = Sky.items().def(id);
        int max = Math.max(1, d.effectiveMaxStack());
        while (n > 0) {
            int k = Math.min(max, n);
            Sky.loot().give(p, id, k, source);
            n -= k;
        }
    }

    /** A reference id if registered, else the vanilla material's sky id (so drops work without materials). */
    static String resolve(String id, Crop crop) {
        if (Sky.items().exists(id)) return id;
        Material fallback = switch (id) {
            case "CARROT_ITEM" -> Material.CARROT;
            case "POTATO_ITEM" -> Material.POTATO;
            case "INK_SACK:3" -> Material.COCOA_BEANS;
            case "NETHER_STALK" -> Material.NETHER_WART;
            case "MELON" -> Material.MELON_SLICE;
            case "DOUBLE_PLANT" -> Material.SUNFLOWER;
            case "MOONFLOWER" -> Material.BLUE_ORCHID;
            case "WILD_ROSE" -> Material.ROSE_BUSH;
            case "SEEDS" -> Material.WHEAT_SEEDS;
            default -> Material.matchMaterial(id);
        };
        return fallback == null ? id : Sky.items().vanillaId(fallback);
    }

    // =================================================================================================
    // Fortune
    // =================================================================================================

    /** One line of the fortune breakdown. */
    record Part(String source, double value) {}

    record Fortune(List<Part> parts) {
        double total() { double t = 0; for (Part p : parts) t += p.value; return t; }
    }

    /** Total fortune for one crop with the held item, broken down by source (for drops AND for menus). */
    Fortune fortune(Player p, Crop crop, ItemStack held, String mushroomColor) {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part("Farming Fortune", Sky.stats().get(p, Stat.FARMING_FORTUNE)));
        // An item whose requirements you do not meet gives nothing (core drops its stats the same way): its tool
        // fortune, counter, level and enchants all wait until you reach Farming N (and Garden N when the garden runs).
        boolean usable = held == null || held.getType().isAir() || !m.dials.enforceToolRequirements.get()
                || Sky.items().meetsRequirements(p, held, false);
        if (!usable) parts.add(new Part("&cHeld tool locked (requirements)", 0));
        ToolTracker.View v = usable ? m.tools.view(p, held) : null;
        if (v != null && v.spec().boosts(crop)) {
            ToolSpec s = v.spec();
            parts.add(new Part("Tool (" + (s.mk > 0 ? "Mk. " + Text.roman(s.mk) : "base") + ")", s.cropFortune));
            if (s.leveling) parts.add(new Part("Tool level " + v.level(), FortuneMath.levelFortune(v.level(), m.dials.levelFortune.get())));
            if (s.counter) parts.add(new Part("Logarithmic Counter", FortuneMath.counterFortune(v.counter(), m.dials.counterPerDigit.get())));
            if (s.analysis) {
                long coll = Sky.service(Features.Collections.class).count(p, crop.collectionId);
                parts.add(new Part("Collection Analysis", FortuneMath.analysisFortune(coll, m.dials.analysisPerDigit.get())));
            }
            if (s.kind.equals("fungi") && crop == Crop.MUSHROOM) {
                String mode = held == null ? "red" : ToolSpec.fungiOf(Sky.items().data(held).extra);
                if (mode.equals(mushroomColor)) parts.add(new Part("Fungi Cutter (" + mode + ")", m.dials.fungiBonus.get()));
            }
        }
        if (usable && held != null && !held.getType().isAir() && Sky.items().idOf(held) != null) {
            ItemData d = Sky.items().data(held);
            int turbo = d.enchant(crop.turboEnchant);
            if (turbo > 0) {
                EnchantDef e = Sky.items().enchant(crop.turboEnchant);
                parts.add(new Part("Turbo enchant " + Text.roman(turbo), e != null && e.values.length > 0 ? e.value(turbo) : 5.0 * turbo));
            }
            int ded = d.enchant("dedication");
            if (ded > 0) {
                EnchantDef e = Sky.items().enchant("dedication");
                double per = e != null && e.values.length > 0 ? e.value(ded) : new double[]{0.5, 0.75, 1, 2}[Math.min(3, ded - 1)];
                int tier = FortuneMath.milestone(m.data(p).collected(crop), m.tables.milestones(crop));
                parts.add(new Part("Dedication " + Text.roman(ded) + " (milestone " + tier + ")", per * tier));
            }
        }
        if (Sky.profiles().isReady(p)) {
            FarmData fd = m.data(p);
            if (fd.pbUnlocked) {
                long best = fd.personalBest.getOrDefault(crop.name(), 0L);
                parts.add(new Part("Personal Best (" + Text.commasInt(best) + ")",
                        FortuneMath.personalBestFortune(best, m.tables.pbStep(crop), m.dials.pbMaxFortune.get())));
            }
        }
        // crop fortune other modules' things carry (accessories' otherStats, the pet's extra stats) - wave 5
        if (m.perks != null) parts.addAll(m.perks.cropParts(p, crop));
        parts.removeIf(pt -> pt.value == 0 && !pt.source.equals("Farming Fortune") && !pt.source.startsWith("&c"));
        return new Fortune(parts);
    }

    /** Block-break particles + break sound (replaces Effect.STEP_SOUND, marked for removal in 26.2). */
    private static void breakFx(org.bukkit.Location at, org.bukkit.block.data.BlockData data) {
        org.bukkit.World w = at.getWorld();
        if (w == null || data == null) return;
        org.bukkit.Location c = at.clone().add(0.5, 0.5, 0.5);
        w.spawnParticle(org.bukkit.Particle.BLOCK, c, 24, 0.25, 0.25, 0.25, 0, data);
        w.playSound(c, data.getSoundGroup().getBreakSound(), 1f, 1f);
    }
}
