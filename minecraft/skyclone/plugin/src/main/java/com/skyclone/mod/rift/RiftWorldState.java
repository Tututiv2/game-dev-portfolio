package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Things lying around the Rift: Riddle Souls (52 anchors with hints the generator wrote), mote orbs
 * (25 Motes + 2 s each, research 08 sec 5.3, back after a while per player), Farmer Wilma's eggs, and
 * the gatherable Rift plants and crystals that feed the seven Rift collections.
 */
final class RiftWorldState implements Listener {

    record Spot(String name, Location loc, String hint, String area) {}

    /** Block -> (item, source). Every one regrows after {@code rift.gather-regen} seconds. */
    static final Map<Material, String> GATHER = new LinkedHashMap<>();
    static {
        GATHER.put(Material.DEAD_BUSH, "WILTED_BERBERIS");
        GATHER.put(Material.BROWN_MUSHROOM, "AGARICUS_CAP");
        GATHER.put(Material.BIG_DRIPLEAF, "CADUCOUS_STEM");
        GATHER.put(Material.BIG_DRIPLEAF_STEM, "CADUCOUS_STEM");
        GATHER.put(Material.CARROTS, "HALF_EATEN_CARROT");
        GATHER.put(Material.RED_STAINED_GLASS, "HEMOVIBE");
        GATHER.put(Material.PURPLE_STAINED_GLASS, "TIMITE");
        GATHER.put(Material.BLUE_STAINED_GLASS, "LIVING_METAL");
    }

    private final RiftModule m;
    private List<Spot> souls = List.of(), motes = List.of(), eggs = List.of();
    private long layoutStamp;
    /** Broken resource blocks waiting to grow back: location -> (data, due ms). */
    private final Map<Location, Object[]> regrow = new LinkedHashMap<>();
    private final Random rnd = new Random();
    private int fastTick;

    RiftWorldState(RiftModule m) { this.m = m; }

    void register() {
        Sky.worlds().addBreakHandler(new WorldService.BreakHandler() {
            @Override public int priority() { return 40; }
            @Override public boolean handles(Player p, Block b, Layout.Zone zone) {
                return RiftCatalog.ISLAND.equals(Sky.worlds().islandOf(b.getWorld())) && (GATHER.containsKey(b.getType()) || m.cat.shiftedItem(b) != null);
            }
            @Override public void onBreak(Player p, Block b, BlockBreakEvent event) { gather(p, b); }
        });
    }

    // =========================================================================================
    // anchors
    // =========================================================================================

    void reload() {
        Layout l = Sky.worlds().layout(RiftCatalog.ISLAND);
        World w = Sky.worlds().world(RiftCatalog.ISLAND);
        if (l == null || w == null) return;
        List<Spot> s = new ArrayList<>(), mo = new ArrayList<>(), eg = new ArrayList<>();
        for (Layout.Anchor a : l.anchors().values()) {
            Location loc = new Location(w, a.x(), a.y(), a.z());
            if (a.name().matches("soul[.][0-9]+")) s.add(new Spot(a.name(), loc, l.data().getOrDefault(a.name() + ".hint", "Somewhere in the Rift."),
                    l.data().getOrDefault(a.name() + ".area", "wyld_woods")));
            else if (a.name().startsWith("mote.")) mo.add(new Spot(a.name(), loc, null, null));
            else if (a.name().startsWith("egg.")) eg.add(new Spot(a.name(), loc, null, null));
        }
        s.sort((a, b) -> Integer.compare(num(a.name()), num(b.name())));
        souls = s; motes = mo; eggs = eg;
        layoutStamp = System.currentTimeMillis();
    }

    private static int num(String n) {
        try { return Integer.parseInt(n.substring(n.indexOf('.') + 1)); } catch (RuntimeException e) { return 0; }
    }

    List<Spot> souls() { ensure(); return souls; }
    List<Spot> motes() { ensure(); return motes; }
    List<Spot> eggs() { ensure(); return eggs; }

    private void ensure() { if (souls.isEmpty() || System.currentTimeMillis() - layoutStamp > 600_000) reload(); }

    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        if (RiftCatalog.ISLAND.equals(e.islandId)) { reload(); m.puzzles.placeHeart(); m.riddles.index(); }
        if ("hub".equals(e.islandId)) m.npcs.placeHubCopy();
    }

    // =========================================================================================
    // collecting (every 10 ticks)
    // =========================================================================================

    void tickFast() {
        fastTick++;
        if (m.sessions.isEmpty()) return;
        ensure();
        boolean sparkle = m.dials.particles.get() && fastTick % 2 == 0;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (m.session(p) == null) continue;
            RiftData d = m.data(p);
            if (d == null) continue;
            Location pl = p.getLocation();
            for (Spot s : souls) {
                if (d.souls.contains(s.name())) continue;
                double d2 = pl.distanceSquared(s.loc());
                if (d2 > 28 * 28) continue;
                // wave 5: a soul behind an unsolved riddle cannot be walked into; it shows dim smoke instead
                boolean open = m.riddles.open(p, d, s.name());
                if (d2 < 2.6) {
                    if (open) { findSoul(p, d, s); continue; }
                    m.riddles.lockedNear(p, s.name());
                } else if (!open && d2 < 9) m.riddles.lockedNear(p, s.name());
                if (sparkle && open) p.spawnParticle(Particle.WITCH, s.loc().clone().add(0, 0.8, 0), 6, 0.2, 0.3, 0.2, 0);
                else if (sparkle && d2 < 12 * 12) p.spawnParticle(Particle.SMOKE, s.loc().clone().add(0, 0.8, 0), 3, 0.15, 0.25, 0.15, 0);
            }
            for (Spot s : motes) {
                Long t = d.motesTaken.get(s.name());
                if (t != null && now - t < m.dials.moteRespawn.get() * 1000L) continue;
                double d2 = pl.distanceSquared(s.loc());
                if (d2 < 2.6) { takeOrb(p, d, s, now); continue; }
                if (sparkle && d2 < 24 * 24)
                    p.spawnParticle(Particle.DUST, s.loc().clone().add(0, 0.7, 0), 5, 0.15, 0.2, 0.15, 0, new Particle.DustOptions(Color.FUCHSIA, 1.3f));
            }
            if (d.wilma == 1) {
                for (Spot s : eggs) {
                    if (d.eggs.contains(s.name())) continue;
                    double d2 = pl.distanceSquared(s.loc());
                    if (d2 < 2.6) {
                        d.eggs.add(s.name());
                        Msg.success(p, "You found one of Farmer Wilma's eggs! &7(" + d.eggs.size() + "/" + eggs.size() + ")");
                        Msg.sound(p, Sound.ENTITY_CHICKEN_EGG, 1.2f);
                        if (d.eggs.size() >= eggs.size()) { d.wilma = 2; Msg.send(p, "&eThat's all of them! Take them back to Farmer Wilma."); }
                    } else if (sparkle && d2 < 20 * 20) p.spawnParticle(Particle.HAPPY_VILLAGER, s.loc().clone().add(0, 0.5, 0), 4, 0.2, 0.2, 0.2, 0);
                }
            }
        }
    }

    /** A riddle handed this soul over (RiftRiddles): collect it wherever the player is. */
    void grantSoul(Player p, String soulName) {
        RiftData d = m.data(p);
        if (d == null) return;
        for (Spot s : souls()) if (s.name().equals(soulName)) { findSoul(p, d, s); return; }
    }

    private void findSoul(Player p, RiftData d, Spot s) {
        if (!d.souls.add(s.name())) return;
        int n = d.souls.size(), total = RiftCatalog.totalSouls();
        p.showTitle(Title.title(Text.c("&5&lRIDDLE SOUL!"), Text.c("&d" + n + "&7/" + total + " found"),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1800), Duration.ofMillis(500))));
        Msg.send(p, "&5&lSOUL! &dYou found a Riddle Soul! &7(" + n + "/" + total + ")");
        Msg.sound(p, Sound.ENTITY_ALLAY_ITEM_GIVEN, 1.4f);
        p.spawnParticle(Particle.END_ROD, s.loc().clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
        m.award(p, "soul:" + num(s.name()), 3, "Riddle Soul");
        int step = m.dials.soulStep.get();
        if (step > 0 && n % step == 0) {
            Msg.send(p, "&dMilestone! &7Your max Rift Time grew by &a" + RiftMath.clock(m.dials.soulBonus.get()) + "ф&7.");
            m.stats.invalidate(p);
            m.travel.addTime(p, m.dials.soulBonus.get());
        }
        if (n >= total) Msg.box(p, "&5&lEVERY RIDDLE SOUL", List.of("&7You found all " + total + " Riddle Souls of the Rift."));
        Sky.profiles().saveNow(p);
    }

    private void takeOrb(Player p, RiftData d, Spot s, long now) {
        d.motesTaken.put(s.name(), now);
        com.skyclone.api.world.gen.Layout.Zone z = Sky.worlds().zoneOf(p);
        boolean cradle = z != null && "cradle".equals(z.id());
        long motes = cradle ? m.dials.cradleMoteValue.get() : m.dials.moteValue.get();
        m.addMotes(p, motes, "Mote Orb");
        if (!cradle) m.travel.addTime(p, m.dials.moteTime.get());
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f);
    }

    // =========================================================================================
    // gathering
    // =========================================================================================

    private void gather(Player p, Block b) {
        if (m.session(p) == null) return;
        Material type = b.getType();
        String shifted = m.cat.shiftedItem(b);
        if (shifted != null) {                       // wave 5: Timite the Timeshift Pistol aged or rejuvenated
            m.cat.shifted.remove(b.getLocation());
            Location key = b.getLocation();
            if (!regrow.containsKey(key)) regrow.put(key, new Object[]{RiftCat.timite(), System.currentTimeMillis() + m.dials.gatherRegen.get() * 1000L});
            b.setType(Material.AIR, false);
            if (Sky.items().exists(shifted)) Sky.loot().give(p, shifted, 1, LootSource.MINING);
            Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.MINING, shifted, 1, b.getLocation()));
            p.playSound(b.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 1.5f);
            return;
        }
        String item = GATHER.get(type);
        if (item == null || !Sky.items().exists(item)) { Msg.error(p, "That will not come loose."); return; }
        boolean mineral = type.name().endsWith("_GLASS");
        int amount = 1;
        LootSource src = mineral ? LootSource.MINING : LootSource.FARMING;
        // the second half of a big dripleaf goes with it
        if (type == Material.BIG_DRIPLEAF_STEM || type == Material.BIG_DRIPLEAF) {
            Block up = b.getRelative(0, 1, 0), down = b.getRelative(0, -1, 0);
            if (up.getType() == Material.BIG_DRIPLEAF) clearForRegrow(up);
            if (down.getType() == Material.BIG_DRIPLEAF_STEM) clearForRegrow(down);
        }
        clearForRegrow(b);
        Sky.loot().give(p, item, amount, src);
        if ("TIMITE".equals(item)) {
            if (rnd.nextDouble() < 0.25 && Sky.items().exists("YOUNGITE")) Sky.loot().give(p, "YOUNGITE", 1, src);
            if (rnd.nextDouble() < 0.05 && Sky.items().exists("OBSOLITE")) Sky.loot().give(p, "OBSOLITE", 1, src);
        }
        if ("LIVING_METAL".equals(item) && rnd.nextDouble() < 0.02 && Sky.items().exists("METAL_HEART"))
            Sky.loot().give(p, "METAL_HEART", 1, src);
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, src, item, amount, b.getLocation()));
        p.playSound(b.getLocation(), mineral ? Sound.BLOCK_AMETHYST_BLOCK_BREAK : Sound.BLOCK_GRASS_BREAK, 1f, 1.2f);
    }

    private void clearForRegrow(Block b) {
        Location key = b.getLocation();
        if (!regrow.containsKey(key))
            regrow.put(key, new Object[]{b.getBlockData().clone(), System.currentTimeMillis() + m.dials.gatherRegen.get() * 1000L});
        b.setType(Material.AIR, false);
    }

    /** Every 5 s: put back what has grown. */
    void tickSlow() {
        long now = System.currentTimeMillis();
        regrow.entrySet().removeIf(e -> {
            if ((long) e.getValue()[1] > now) return false;
            Location l = e.getKey();
            if (l.getWorld() == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return false;
            if (l.getBlock().getType().isAir()) l.getBlock().setBlockData((BlockData) e.getValue()[0], false);
            return true;
        });
        m.puzzles.ensureHeart();
    }

    void restoreAll() {
        for (Map.Entry<Location, Object[]> e : regrow.entrySet()) {
            Location l = e.getKey();
            if (l.getWorld() != null && l.getBlock().getType().isAir()) l.getBlock().setBlockData((BlockData) e.getValue()[0], false);
        }
        regrow.clear();
    }

    int pendingRegrow() { return regrow.size(); }

    Map<String, Integer> soulsByArea(RiftData d) {
        Map<String, Integer> out = new HashMap<>();
        for (Spot s : souls()) if (d != null && d.souls.contains(s.name())) out.merge(s.area(), 1, Integer::sum);
        return out;
    }
}
