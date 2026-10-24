package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The isle's gathering: netherrack, nether quartz and glowstone on the volcano's flanks, red sand in the
 * Burning Desert, mycelium in the Mystic Marsh - only inside the generator's {@code gather.*} areas and
 * never in SAFE/BUILD zones. Mined blocks turn depleted and grow back. Mining XP per research 02 §2.4.2
 * (netherrack 0.5, quartz 5, glowstone 7, sand 3); mycelium is a TUNE value.
 */
final class Gathering implements WorldService.BreakHandler {

    /** {@code power}: Breaking Power the held tool needs (0 = any); {@code area}: only inside that area, null = the AREAS. */
    record Kind(String item, int min, int max, double xp, String depleted, int power, String area, String fortune) {
        Kind(String item, int min, int max, double xp, String depleted) { this(item, min, max, xp, depleted, 0, null, null); }
    }

    static final String OPAL_AREA = "gather.tomb_opal";

    static final Map<Material, Kind> KINDS = Map.of(
            Material.NETHERRACK, new Kind("NETHERRACK", 1, 1, 0.5, "bedrock"),
            Material.NETHER_QUARTZ_ORE, new Kind("QUARTZ", 1, 1, 5, "bedrock"),
            Material.GLOWSTONE, new Kind("GLOWSTONE_DUST", 2, 4, 7, "bedrock"),
            Material.RED_SAND, new Kind("SAND:1", 1, 1, 3, "red_sandstone"),
            Material.MYCELIUM, new Kind("MYCEL", 1, 1, 5, "dirt"),
            // wave 5 (generator v4). Sulphur Ore: the generator only ever places sponge as ore, so it is minable
            // anywhere on the isle; XP is TUNE (research 02 lists the Sulphur collection, not its XP).
            Material.SPONGE, new Kind("SULPHUR_ORE", 1, 2, 10, "bedrock", 0, "*", null),
            // Opal veins in the Smoldering Tomb: mining's own numbers for opal (blocks.json: BP 7, 70 XP, 2 gemstone
            // powder) and Gemstone Fortune, only inside the tomb's vein area.
            Material.WHITE_STAINED_GLASS, new Kind("ROUGH_OPAL_GEM", 1, 1, 70, "blackstone", 7, OPAL_AREA, "GEMSTONE_FORTUNE"));

    static final List<String> AREAS = List.of("gather.volcano", "gather.desert", "gather.marsh");
    private static final Map<Location, BlockData> PENDING = new HashMap<>();

    @Override public int priority() { return 500; }

    @Override
    public boolean handles(Player p, Block block, Layout.Zone zone) {
        if (!CrimsonModule.ISLAND.equals(Sky.worlds().islandOf(block.getWorld()))) return false;
        if (!KINDS.containsKey(block.getType())) return false;
        if (zone != null && zone.flags() != null && (zone.flags().contains(Layout.Zone.SAFE) || zone.flags().contains(Layout.Zone.BUILD))) return false;
        Kind k = KINDS.get(block.getType());
        if ("*".equals(k.area())) return true;
        if (k.area() != null) {
            Box b = Sky.worlds().area(CrimsonModule.ISLAND, k.area());
            return b != null && b.contains(block.getX(), block.getY(), block.getZ());
        }
        for (String a : AREAS) {
            Box b = Sky.worlds().area(CrimsonModule.ISLAND, a);
            if (b != null && b.contains(block.getX(), block.getY(), block.getZ())) return true;
        }
        return false;
    }

    @Override
    public void onBreak(Player p, Block block, BlockBreakEvent event) {
        Kind k = KINDS.get(block.getType());
        if (k == null || PENDING.containsKey(block.getLocation())) return;
        if (!Sky.items().exists(k.item)) return;
        if (k.power() > 0 && p.getGameMode() != org.bukkit.GameMode.CREATIVE && Sky.stats().get(p, Stat.BREAKING_POWER) < k.power()) {
            event.setCancelled(true);
            Sky.vitals().flash(p, "&cRequires Breaking Power " + k.power() + "!", 40);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int base = k.min + (k.max > k.min ? r.nextInt(k.max - k.min + 1) : 0);
        Stat fs = k.fortune() == null ? Stat.MINING_FORTUNE : Stat.valueOf(k.fortune());
        double fortune = Sky.stats().get(p, fs);
        double mult = 1 + Math.max(0, fortune) / 100.0;
        int amount = (int) Math.floor(base * mult);
        if (r.nextDouble() < base * mult - amount) amount++;
        amount = Math.max(1, amount);
        Location loc = block.getLocation();
        BlockData original = block.getBlockData().clone();
        PENDING.put(loc, original);
        block.setType(Material.matchMaterial(k.depleted) == null ? Material.BEDROCK : Material.matchMaterial(k.depleted), false);
        Sky.loot().give(p, k.item, amount, LootSource.MINING);
        if (original.getMaterial() == Material.MYCELIUM && r.nextDouble() < 0.08 && Sky.items().exists("MUSHROOM_SPORE"))
            Sky.loot().give(p, "MUSHROOM_SPORE", 1, LootSource.MINING);      // TUNE: spores from the marsh
        Sky.service(SkillService.class).addXp(p, SkillType.MINING, k.xp, "crimson: " + k.item);
        if (original.getMaterial() == Material.WHITE_STAINED_GLASS) {
            try { Sky.service(com.skyclone.api.feature.Features.Mining.class).addPowder(p, "GEMSTONE", 2, "crimson: tomb opal"); }
            catch (RuntimeException ignored) { }
            Attrs.ghastlyDistraction(p);                     // hunting's Dive Ghast shard
        }
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.MINING, k.item, amount, loc));
        p.getWorld().spawnParticle(Particle.BLOCK, loc.clone().add(0.5, 0.5, 0.5), 12, 0.3, 0.3, 0.3, original);
        p.playSound(loc, original.getSoundGroup().getBreakSound(), 1f, 1f);
        long delay = 20L * Math.max(3, CrimsonModule.D.gatherRegenSeconds.get());
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> restore(loc), delay);
    }

    private static void restore(Location loc) {
        BlockData d = PENDING.remove(loc);
        if (d == null || loc.getWorld() == null) return;
        loc.getBlock().setBlockData(d, false);
        loc.getWorld().playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.3f, 0.6f);
    }

    /** Put every depleted block back (shutdown). */
    static void restoreAll() {
        for (Location l : new java.util.ArrayList<>(PENDING.keySet())) restore(l);
    }
}
