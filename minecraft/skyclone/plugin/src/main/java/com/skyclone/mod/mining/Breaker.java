package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Everything that happens when a block finishes breaking: drops with fortune and pristine, powder,
 * Mining XP, the Gather event, commissions, drill fuel, regeneration, rare finds and Mining Spread.
 */
final class Breaker {

    private final MiningModule m;
    private final Random rnd = new Random();

    Breaker(MiningModule m) { this.m = m; }

    static boolean fiesta(MiningModule m) {
        try {
            String ev = m.dials.fiestaEvent.get();
            if (ev != null && !ev.isBlank() && Sky.calendar().active(ev)) return true;
            String perk = m.dials.fiestaPerk.get();
            return perk != null && !perk.isBlank() && Sky.service(Features.Mayors.class).perkActive(perk);
        } catch (Throwable t) { return false; }
    }

    /** Block-specific Mining Speed on top of the stat (research 2.3 sources). */
    double contextSpeed(Player p, BlockTable.Def def, Block b) {
        double s = 0;
        if (def.kind == BlockTable.Kind.GEMSTONE) {
            s += m.hotm.value(p, "professional");
            s += m.drills.drillGemSpeed(p);
            if (frontLoaded(p)) s += 250;
        }
        if (def.isDwarvenMetal()) s += m.hotm.value(p, "strong_arm");
        s += m.drills.seasoningNearStar(p)[0];
        s += m.events.togetherSpeed(p);
        return s;
    }

    private boolean frontLoaded(Player p) {
        if (!m.hotm.has(p, "front_loaded")) return false;
        MiningData d = MiningUtil.data(p);
        MiningUtil.rollDay(d);
        return d.gemsToday < 2500;
    }

    /** Total fortune for this block: Mining Fortune + the matching specialised fortune + context. */
    double fortune(Player p, BlockTable.Def def) {
        double f = Sky.stats().get(p, Stat.MINING_FORTUNE);
        switch (def.kind) {
            case ORE, PURE_ORE -> f += Sky.stats().get(p, Stat.ORE_FORTUNE);
            case STANDARD, HARD_STONE, OBSIDIAN -> f += Sky.stats().get(p, Stat.BLOCK_FORTUNE);
            case METAL -> f += Sky.stats().get(p, Stat.DWARVEN_METAL_FORTUNE);
            case GEMSTONE -> f += Sky.stats().get(p, Stat.GEMSTONE_FORTUNE);
            default -> { }
        }
        ItemStack h = p.getInventory().getItemInMainHand();
        String hid = h == null || h.getType().isAir() ? null : Sky.items().idOf(h);
        ItemDef hd = hid == null ? null : Sky.items().def(hid);
        if (hd != null && hd.extra != null) {
            if (def.isTitanium() && hd.extra.has("titaniumFortune")) f += hd.extra.get("titaniumFortune").getAsDouble();
            if (hd.extra.has("undeadFortune")) {
                int n = 0;
                for (Entity e : p.getNearbyEntities(30, 30, 30)) if (e instanceof Zombie || e instanceof Skeleton) n++;
                f += Math.min(25, n * 5);
            }
        }
        if (def.kind == BlockTable.Kind.GEMSTONE && frontLoaded(p)) f += 150;
        f += m.drills.seasoningNearStar(p)[1];
        f += m.events.togetherFortune(p);
        return Math.max(0, f);
    }

    /** Break one block for a player. {@code spread}: a Mining Spread follow-up (no rare finds, no recursion). */
    void breakBlock(Player p, Block b, BlockTable.Def def, boolean spread) {
        if (!MiningUtil.ready(p)) return;
        String island = MiningUtil.island(b.getLocation());
        MiningData d = MiningUtil.data(p);
        MiningUtil.rollDay(d);
        BlockData original = b.getBlockData();
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        boolean fiesta = fiesta(m) && island != null && !"hub".equals(island);

        // ---- drops -------------------------------------------------------------------------
        String dropId = def.drop;
        int base = MiningMath.between(def.min, def.max, rnd);
        // Mining Jubilee (Aug-2026 rework): no doubled drops any more - its +25% Mining Fortune is a stat (MiningStats)
        double jubilee = fiesta ? Math.max(0, m.dials.fiestaDropMult.get()) : 1.0;
        base = (int) Math.max(1, Math.round(base * jubilee * m.dials.dropMult.get()));
        ItemStack held = p.getInventory().getItemInMainHand();
        ItemData hdata = held == null || held.getType().isAir() || Sky.items().idOf(held) == null ? null : Sky.items().data(held);
        int amount;
        if (hdata != null && hdata.enchant("silk_touch") > 0 && def.kind != BlockTable.Kind.GEMSTONE && def.kind != BlockTable.Kind.METAL
                && MiningUtil.exists(b.getType().name())) {
            dropId = b.getType().name();
            amount = 1;
        } else {
            if (hdata != null && hdata.enchant("smelting_touch") > 0 && "COBBLESTONE".equals(dropId)) dropId = "STONE";
            amount = MiningMath.fortuneDrops(base, fortune(p, def), rnd.nextDouble());
        }
        if (def.isTitanium() && m.skyMall.is(p, SkyMall.Buff.TITANIUM)) amount *= 5;
        int flawed = 0;
        if (def.kind == BlockTable.Kind.GEMSTONE && def.gem != null) {
            flawed = MiningMath.pristineProcs(amount, Sky.stats().get(p, Stat.PRISTINE), rnd);
            d.gemsToday += amount;
        }
        int rough = amount - flawed;
        if (MiningUtil.exists(dropId)) {
            give(p, dropId, rough);
            if (flawed > 0) {
                String fid = "FLAWED_" + def.gem + "_GEM";
                give(p, fid, flawed);
                Msg.send(p, "&d&lPRISTINE! &7You found " + MiningUtil.itemName(fid) + " &8x" + flawed + "&7!");
                Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.6f);
            }
        }
        if (fiesta) fiestaDrop(p, def);

        // ---- powder ------------------------------------------------------------------------
        if (def.powder != null && island != null && MiningUtil.POWDER_ISLANDS.contains(island)) {
            double pb = def.powderAmount;
            int core = m.hotm.coreLevel(p);
            if (def.isMithril() && core >= 4) pb += 1;
            if (def.kind == BlockTable.Kind.GEMSTONE && core >= 6) pb += 2;
            if (def.isGlaciteMetal() && core >= 8) pb += 3;
            pb *= 1 + m.drills.drillPowderPct(p, def.powder) / 100.0;
            if (def.kind == BlockTable.Kind.GEMSTONE && frontLoaded(p)) pb *= 3;
            m.hotm.grant(p, def.powder, pb, "mining", true);
        }
        if (!d.dailyPowderDone && m.hotm.has(p, "daily_powder") && island != null && MiningUtil.POWDER_ISLANDS.contains(island)) {
            d.dailyPowderDone = true;
            String type = def.powder != null ? def.powder : MiningUtil.powderOf(island);
            m.hotm.grant(p, type, 500.0 * m.hotm.tier(p), "daily powder", true);
            Msg.send(p, "&5Daily Powder! " + MiningUtil.powderColor(type) + "+" + (500 * m.hotm.tier(p)) + " " + MiningUtil.powderName(type));
        }

        // ---- XP, events, counters ---------------------------------------------------------------
        double xp = def.xp * m.dials.xpMult.get();
        if (xp > 0) Sky.service(SkillService.class).addXp(p, SkillType.MINING, xp, "mining:" + def.id);
        oreExp(p, original.getMaterial());
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.MINING, def.drop, amount, at));
        d.blocksMined++;
        d.minedById.merge(def.id, 1L, Long::sum);
        if (def.gem != null) d.lastMinedGem = def.gem;
        d.lastMinedBlock = def.id;
        m.comms.onMine(p, def, dropId, amount, at, island);
        m.drills.burn(p, def.fuel);
        m.drills.onToolBlock(p);
        m.abilities.onBlock(p);

        // ---- the block itself ----------------------------------------------------------------
        breakFx(b.getLocation(), original);   // Effect.STEP_SOUND is marked for removal in 26.2
        if (def.kind == BlockTable.Kind.STAR) {
            m.temp.mined(b);
        } else {
            int secs = island == null ? 10 : m.dials.regenSeconds(island);
            BlockData restore = def.isMithril() ? mithrilGrade(original) : null;
            if (def.isMithril() && ("dwarven_mines".equals(island) || "glacite_tunnels".equals(island) || "mineshaft".equals(island))
                    && rnd.nextDouble() * 100 < m.hotm.value(p, "titanium_insanium") && secs > 0) {
                m.regen.schedule(b, Material.POLISHED_DIORITE.createBlockData(), original, secs * 6);
                Msg.send(p, "&fTITANIUM! &7The mithril turned into &fTitanium&7!");
            } else if (secs > 0) {
                m.regen.schedule(b, Material.BEDROCK.createBlockData(), restore, secs);
            } else {
                b.setType(Material.AIR, false);
            }
        }

        // ---- rare things and spread ---------------------------------------------------------
        if (!spread) {
            m.finds.onBlock(p, def, b, island);
            m.tunnels.onBlock(p, def, island);
            spread(p, b, def);
        }
    }

    /**
     * Vanilla experience from an ore (the custom break bypasses vanilla's own drop), doubled at the held tool's
     * {@code extra.doubleExpChance} (the Lapis Pickaxe's 25%). Experience is for enchanting / the anvil.
     */
    private void oreExp(Player p, Material mat) {
        if (!m.dials.oreExp.get() || mat == null) return;
        int[] r = MiningMath.oreExp(mat.name());
        int n = MiningMath.between(r[0], r[1], rnd);
        if (n <= 0) return;
        boolean twice = rnd.nextDouble() < doubleExpChance(p);
        // enchanting (dial enchanting.ore-exp) already pays this ore's experience on the Gather fired below; then only
        // the Lapis Pickaxe's second helping is ours, so the base is never paid twice
        if (m.dials.enchantingOreExp.get()) { if (twice) p.giveExp(n); return; }
        p.giveExp(twice ? n * 2 : n);
    }

    /** The held tool's chance to double ore experience ({@code ItemDef.extra.doubleExpChance}, 0..1), else 0. */
    static double doubleExpChance(Player p) {
        String id = MiningUtil.heldId(p);
        ItemDef d = id == null ? null : Sky.items().def(id);
        if (d == null || d.extra == null || !d.extra.has("doubleExpChance")) return 0;
        try { return Math.max(0, Math.min(1, d.extra.get("doubleExpChance").getAsDouble())); } catch (RuntimeException e) { return 0; }
    }

    private void give(Player p, String id, int n) {
        ItemDef idef = Sky.items().def(id);
        int stack = idef == null ? 64 : Math.max(1, idef.effectiveMaxStack());
        while (n > 0) {
            int k = Math.min(stack, n);
            Sky.loot().give(p, id, k, LootSource.MINING);
            n -= k;
        }
    }

    private BlockData mithrilGrade(BlockData original) {
        double r = rnd.nextDouble();
        Material mat = original.getMaterial();
        if (mat == Material.GRAY_WOOL || mat == Material.CYAN_TERRACOTTA || mat == Material.PRISMARINE
                || mat == Material.PRISMARINE_BRICKS || mat == Material.DARK_PRISMARINE || mat == Material.LIGHT_BLUE_WOOL) {
            // research 6.2: restores as a weighted random grade (60% low, 30% mid, 10% high), keeping the family
            if (mat == Material.GRAY_WOOL || mat == Material.CYAN_TERRACOTTA)
                return (r < 0.6 ? mat : r < 0.9 ? Material.PRISMARINE : Material.LIGHT_BLUE_WOOL).createBlockData();
            return (r < 0.6 ? Material.PRISMARINE : r < 0.85 ? Material.DARK_PRISMARINE : Material.LIGHT_BLUE_WOOL).createBlockData();
        }
        return original;
    }

    private void fiestaDrop(Player p, BlockTable.Def def) {
        boolean gem = def.kind == BlockTable.Kind.GEMSTONE;
        boolean ore = def.kind == BlockTable.Kind.ORE || def.kind == BlockTable.Kind.PURE_ORE || def.kind == BlockTable.Kind.METAL;
        if (!gem && !ore) return;
        if (rnd.nextDouble() < MiningMath.fiestaChance(def.breakingPower, 0, m.dials.fiestaDropDivisor.get())) {
            String id = gem ? "GLOSSY_GEMSTONE" : "REFINED_MINERAL";
            Sky.loot().give(p, id, 1, LootSource.MINING);
            Msg.rareDrop(p, "&6&lJUBILEE DROP!", MiningUtil.itemName(id), 0);
        }
    }

    // ---- Mining Spread ----------------------------------------------------------------------

    private void spread(Player p, Block origin, BlockTable.Def def) {
        double spread = Sky.stats().get(p, Stat.MINING_SPREAD);
        if (def.kind == BlockTable.Kind.HARD_STONE) spread += m.hotm.value(p, "mole");
        if (def.kind == BlockTable.Kind.GEMSTONE) spread += m.stats.gemSpread(p);
        int n = MiningMath.spreadBlocks(spread, rnd.nextDouble());
        if (n <= 0) return;
        List<Block> cand = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        List<Block> frontier = new ArrayList<>(List.of(origin));
        BlockFace[] faces = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (int depth = 0; depth < 3 && cand.size() < n * 3; depth++) {
            List<Block> next = new ArrayList<>();
            for (Block f : frontier) for (BlockFace face : faces) {
                Block nb = f.getRelative(face);
                if (!seen.add(nb) || nb.equals(origin)) continue;
                BlockTable.Def nd = m.engine.mineable(p, nb, false);
                if (nd != null && nd.id.equals(def.id)) { cand.add(nb); next.add(nb); }
            }
            frontier = next;
        }
        Collections.shuffle(cand, rnd);
        List<Block> pick = cand.subList(0, Math.min(n, cand.size()));
        if (pick.isEmpty()) return;
        List<Block> chosen = new ArrayList<>(pick);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            for (Block nb : chosen) {
                if (!p.isOnline()) return;
                BlockTable.Def nd = m.engine.mineable(p, nb, false);
                if (nd == null || !nd.id.equals(def.id) || m.engine.toolPower(p) < nd.breakingPower || !m.drills.hasFuel(p)) continue;
                breakBlock(p, nb, nd, true);
            }
        }, 1L);
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
