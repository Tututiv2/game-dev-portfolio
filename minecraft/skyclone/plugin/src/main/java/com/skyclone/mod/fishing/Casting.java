package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.fishing.FishingCatalog.Bait;
import com.skyclone.mod.fishing.FishingCatalog.Part;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The catch loop (research 07 §3.1, §6.3). Every bobber is driven by this class rather than by
 * vanilla's random bite: a cast snapshots the rod and consumes a bait; when the bobber lands (water,
 * lava with a lava rod, or dirt with the Dirt Rod) the stats at THAT spot (hotspot included) set the
 * wait; the bite shows "!!!" over the bobber; reeling inside the window rolls the catch:
 * sea creature (SCC) -> double hook -> which one (pools) -> replacements; else treasure (water) or a
 * trophy fish (Crimson lava); else a plain fish.
 */
final class Casting implements Listener {

    enum Phase { FLYING, WAITING, APPROACH, BITE }

    static final class Cast {
        final UUID player;
        final UUID hook;
        final String rodId;
        final ItemData rod;
        final boolean lavaRod;
        final boolean dirtRod;
        String bait;
        final long castAt = System.currentTimeMillis();
        long landedAt;
        Phase phase = Phase.FLYING;
        String liquid;
        int ticksLeft;
        int approach;
        int biteLeft;
        UUID marker;
        Hotspots.Hotspot hotspot;
        int restTicks;          // ticks the bobber has lain still on something it cannot fish from
        boolean groundHinted;
        // snapshot at landing
        double fs, scc, treasure, dh, trophy, cap;
        Cast(UUID player, UUID hook, String rodId, ItemData rod, boolean lavaRod, boolean dirtRod) {
            this.player = player; this.hook = hook; this.rodId = rodId; this.rod = rod; this.lavaRod = lavaRod; this.dirtRod = dirtRod;
        }
    }

    static final Map<UUID, Cast> CASTS = new HashMap<>();

    static void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Casting::tick, 1L, 1L);
    }

    static void stop() {
        for (Cast c : CASTS.values()) clearMarker(c);
        CASTS.clear();
    }

    // ---- events ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (!FishDials.ENABLED.get()) return;
        Player p = e.getPlayer();
        FishHook hook = e.getHook();
        String island = Fishing.island(p.getLocation());
        if (island == null) return;                       // not a game world: leave vanilla alone
        switch (e.getState()) {
            case FISHING -> cast(e, p, hook, island);
            case BITE, LURED -> {
                Cast c = CASTS.get(hook.getUniqueId());
                if (c != null) e.setCancelled(true);       // vanilla bites never happen on our hooks
            }
            case CAUGHT_FISH -> {
                // a vanilla bite slipped through (or the hook is not ours): never hand out vanilla loot
                // (books, saddles, name tags) on a game world
                e.setCancelled(true);
                Entity caught = e.getCaught();
                if (caught != null) caught.remove();
                reel(p, hook);
                hook.remove();
            }
            case REEL_IN, FAILED_ATTEMPT, IN_GROUND -> reel(p, hook);
            default -> { }
        }
    }

    private void cast(PlayerFishEvent e, Player p, FishHook hook, String island) {
        EquipmentSlot hand = e.getHand() == null ? EquipmentSlot.HAND : e.getHand();
        ItemStack rod = hand == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand() : p.getInventory().getItemInMainHand();
        String rodId = Sky.items().idOf(rod);
        ItemDef rd = rodId == null ? null : Sky.items().def(rodId);
        // not a fishing rod (Grappling Hook, whips...): their own module owns that bobber
        if (rd == null || rd.category != com.skyclone.api.item.ItemCategory.FISHING_ROD) return;
        if (!Fishing.ready(p)) { e.setCancelled(true); return; }
        if (!Sky.items().meetsRequirements(p, rod, true)) { e.setCancelled(true); return; }
        boolean lava = Fishing.isLavaRod(rodId);
        if (lava && !FishDials.LAVA.get()) { e.setCancelled(true); Msg.error(p, "Lava fishing is switched off."); return; }
        ItemData data = Sky.items().data(rod);
        boolean dirt = rd.extra != null && rd.extra.has("dirt") && FishDials.DIRT_ROD.get();
        Cast c = new Cast(p.getUniqueId(), hook.getUniqueId(), rodId, data, lava, dirt);
        // The bait is taken when the bobber LANDS somewhere it can fish (see land()), so a cast onto
        // dry ground or into the wrong liquid never wastes one.
        // vanilla must never bite on its own: push its timer far away; we run the clock.
        hook.setApplyLure(false);
        hook.setSkyInfluenced(false);
        hook.setRainInfluenced(false);
        hook.setWaitTime(72000, 72000);
        CASTS.put(hook.getUniqueId(), c);
        Fishing.data(p).casts++;
        if (lava && Trophies.TROPHY_ISLAND.equals(island)) Trophies.onLavaCast(p);
    }

    private void reel(Player p, FishHook hook) {
        Cast c = CASTS.remove(hook.getUniqueId());
        if (c == null) return;
        clearMarker(c);
        if (c.phase != Phase.BITE) return;
        Location at = hook.getLocation();
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (p.isOnline() && Fishing.ready(p)) resolve(p, c, at);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        for (Iterator<Cast> it = CASTS.values().iterator(); it.hasNext(); ) {
            Cast c = it.next();
            if (c.player.equals(e.getPlayer().getUniqueId())) { clearMarker(c); it.remove(); }
        }
        Trophies.SESSIONS.remove(e.getPlayer().getUniqueId());
    }

    /** Admin: make every waiting bobber of this player bite on the next tick. Returns how many. */
    static int forceBite(Player p) {
        int n = 0;
        for (Cast c : CASTS.values()) {
            if (!c.player.equals(p.getUniqueId()) || c.liquid == null || c.ticksLeft == Integer.MAX_VALUE) continue;
            if (c.phase == Phase.WAITING) { c.ticksLeft = 1; c.approach = 0; n++; }
            else if (c.phase == Phase.APPROACH) { c.approach = 0; n++; }
        }
        return n;
    }

    // ---- the clock -------------------------------------------------------------------------------

    private static void tick() {
        for (Iterator<Cast> it = CASTS.values().iterator(); it.hasNext(); ) {
            Cast c = it.next();
            Entity ent = Bukkit.getEntity(c.hook);
            Player p = Bukkit.getPlayer(c.player);
            if (!(ent instanceof FishHook hook) || !hook.isValid() || p == null || !p.isOnline()) {
                clearMarker(c);
                it.remove();
                continue;
            }
            try { step(p, hook, c); } catch (RuntimeException ex) {
                Sky.log().warning("[fishing] hook tick: " + ex);
                clearMarker(c);
                it.remove();
            }
        }
    }

    private static void step(Player p, FishHook hook, Cast c) {
        if (hook.getHookedEntity() != null) return;        // stuck in a creature (the Puddle Jumper ride): no bites
        if (c.phase == Phase.FLYING) {
            String liquid = liquidAt(hook, c);
            if (liquid == null) {
                if (System.currentTimeMillis() - c.castAt > 20_000 && !c.dirtRod) hook.setWaitTime(72000, 72000);
                groundHint(p, hook, c);
                return;
            }
            if (liquid.equals("wrong")) {
                c.phase = Phase.WAITING;
                c.ticksLeft = Integer.MAX_VALUE;   // wrong rod for this liquid: nothing will ever bite
                Sky.vitals().flash(p, c.lavaRod ? "&cLava rods only work in lava!" : "&cYou need a lava rod to fish in lava!", 60);
                return;
            }
            land(p, hook, c, liquid);
            return;
        }
        if ("lava".equals(c.liquid)) holdOnLava(hook);
        if ("dirt".equals(c.liquid)) hook.setVelocity(new Vector(0, 0, 0));
        switch (c.phase) {
            case WAITING -> {
                if (c.ticksLeft == Integer.MAX_VALUE) return;
                if (--c.ticksLeft <= 0) { c.phase = Phase.APPROACH; }
            }
            case APPROACH -> {
                if (c.approach-- > 0) {
                    approachParticles(hook, c);
                } else bite(p, hook, c);
            }
            case BITE -> {
                if (--c.biteLeft <= 0) {
                    clearMarker(c);
                    c.phase = Phase.WAITING;
                    c.ticksLeft = waitTicks(c, p);
                    c.approach = approachTicks(c);
                }
            }
            default -> { }
        }
    }

    /**
     * A bobber that came to rest on a deck, a rail or the bank used to sit there silently for ever
     * (playtest: 15 casts onto the Bayou Docks pier, no hint why nothing bit). Say so once per cast,
     * after it has lain still for half a second, naming what it is lying on.
     */
    private static void groundHint(Player p, FishHook hook, Cast c) {
        if (c.groundHinted || c.dirtRod) return;
        if (!hook.isOnGround() || hook.getVelocity().lengthSquared() > 0.0025) { c.restTicks = 0; return; }
        if (++c.restTicks < 10) return;
        c.groundHinted = true;
        Block on = hook.getLocation().getBlock();
        if (on.getType().isAir() || !on.getType().isSolid()) on = on.getRelative(0, -1, 0);
        String what = on.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        Sky.vitals().flash(p, "&eYour bobber landed on " + what + " - cast over open "
                + (c.lavaRod ? "lava" : "water") + "!", 60);
    }

    /** "water", "lava", "dirt", "wrong" (liquid the rod cannot fish), or null while still flying. */
    private static String liquidAt(FishHook hook, Cast c) {
        Block b = hook.getLocation().getBlock();
        Material m = b.getType();
        // a bobber resting on a lily pad is floating on the water under it (the Bayou and the Lagoon
        // are dotted with pads, so a cast that found one used to sit there with nothing ever biting).
        // Its box is 0.25 wide, so it can rest on the EDGE of a pad with its centre over plain air.
        if ((m == Material.LILY_PAD || m.isAir() && hook.isOnGround())
                && b.getRelative(0, -1, 0).getType() == Material.WATER) {
            b = b.getRelative(0, -1, 0);
            m = Material.WATER;
        }
        boolean water = m == Material.WATER || m == Material.BUBBLE_COLUMN
                || (b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged())
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.KELP || m == Material.KELP_PLANT;
        boolean lava = m == Material.LAVA || b.getRelative(0, -1, 0).getType() == Material.LAVA && hook.getVelocity().lengthSquared() < 0.01;
        if (lava) return c.lavaRod ? "lava" : "wrong";
        if (water) return c.lavaRod ? "wrong" : "water";
        if (c.dirtRod && hook.isOnGround()) {
            Material below = b.getRelative(0, -1, 0).getType();
            if (below == Material.DIRT || below == Material.GRASS_BLOCK || below == Material.COARSE_DIRT
                    || below == Material.ROOTED_DIRT || below == Material.PODZOL || below == Material.MUD || below == Material.FARMLAND
                    || below == Material.DIRT_PATH)
                return "dirt";
        }
        return null;
    }

    private static void holdOnLava(FishHook hook) {
        Location l = hook.getLocation();
        Block b = l.getBlock();
        // Vanilla gives lava no buoyancy, so a fast cast sinks the hook several blocks before our tick
        // sees it: float it on the TOP lava block of the column, not on whichever block it sank into.
        if (b.getType() == Material.LAVA) {
            for (int i = 0; i < 16 && b.getRelative(0, 1, 0).getType() == Material.LAVA; i++) b = b.getRelative(0, 1, 0);
        }
        double top = b.getY() + (b.getType() == Material.LAVA ? 0.9 : 0.0);
        if (b.getType() != Material.LAVA && b.getRelative(0, -1, 0).getType() == Material.LAVA) top = b.getY() - 0.1;
        hook.setGravity(false);
        hook.setVelocity(new Vector(0, 0, 0));
        hook.setFireTicks(0);
        double bob = Math.sin(System.currentTimeMillis() / 300.0) * 0.04;
        if (Math.abs(l.getY() - (top + bob)) > 0.02) {
            Location to = l.clone();
            to.setY(top + bob);
            hook.teleport(to);
        }
    }

    /** Accessories' "chance not to use bait": Spiked Atrocity 10%, else Bait Ring 5% (the best one owned). */
    static double baitAccessoryKeep(Player p) {
        try {
            com.skyclone.api.feature.Features.Accessories acc = Sky.service(com.skyclone.api.feature.Features.Accessories.class);
            if (acc.has(p, "SPIKED_ATROCITY")) return 0.10 + FishEffects.baitKeep(p);
            if (acc.has(p, "BAIT_RING")) return 0.05 + FishEffects.baitKeep(p);
        } catch (RuntimeException ignored) { }
        return FishEffects.baitKeep(p);
    }

    private static void land(Player p, FishHook hook, Cast c, String liquid) {
        c.liquid = liquid;
        c.landedAt = System.currentTimeMillis();
        c.phase = Phase.WAITING;
        if ("lava".equals(liquid)) hook.setGravity(false);
        String island = Fishing.island(hook.getLocation());
        // bait: one per cast, taken now that the bobber is in fishable liquid (Caster / Stingy Sinker may keep it)
        Baits.Found found = Baits.find(p, c.lavaRod, island);
        if (found != null) {
            c.bait = found.id();
            double keep = Fishing.enchant(c.rod, "caster") / 100.0;
            for (Part pt : Fishing.parts(c.rod)) keep += pt.keepBait;
            keep += baitAccessoryKeep(p);
            if (ThreadLocalRandom.current().nextDouble() >= keep) Baits.consume(p, found);
        }
        c.hotspot = Hotspots.at(hook.getLocation());
        double hotMult = 1;
        for (Part pt : Fishing.parts(c.rod)) if (pt.hotspotMult > 0) hotMult *= pt.hotspotMult;
        Bait bait = Baits.def(c.bait);
        c.fs = Sky.stats().get(p, Stat.FISHING_SPEED) + (bait == null ? 0 : bait.fs);
        c.scc = Sky.stats().get(p, Stat.SEA_CREATURE_CHANCE) + (bait == null ? 0 : bait.scc);
        c.treasure = Sky.stats().get(p, Stat.TREASURE_CHANCE) + (bait == null ? 0 : bait.treasure);
        c.dh = Sky.stats().get(p, Stat.DOUBLE_HOOK_CHANCE);
        c.trophy = Sky.stats().get(p, Stat.TROPHY_FISH_CHANCE) + (bait == null ? 0 : bait.trophy);
        if (c.hotspot != null) {
            double v = c.hotspot.buff.amount * hotMult;
            switch (c.hotspot.buff) {
                case FISHING_SPEED -> c.fs += v;
                case SEA_CREATURE_CHANCE -> c.scc += v;
                case DOUBLE_HOOK -> c.dh += v;
                case TROPHY -> c.trophy += v;
                case TREASURE -> c.treasure += v;
            }
        }
        if ("jerrys_workshop".equals(island)) {
            ItemDef rd = Sky.items().def(c.rodId);
            if (rd != null && rd.extra != null && rd.extra.has("winterBonus")) {
                var wb = rd.extra.getAsJsonObject("winterBonus");
                double m = FishDials.WINTER_ROD_BONUS_MULT.get();
                if (wb.has("FISHING_SPEED")) c.fs += wb.get("FISHING_SPEED").getAsDouble() * m;
                if (wb.has("SEA_CREATURE_CHANCE")) c.scc += wb.get("SEA_CREATURE_CHANCE").getAsDouble() * m;
            }
        }
        for (Part pt : Fishing.parts(c.rod)) if (pt.junk && "backwater_bayou".equals(island)) c.treasure += 10;
        c.fs += ChumBuckets.fishingSpeed(p.getLocation());   // placed Chumcap Buckets nearby: +4 each, up to +16
        if (bait != null && bait.fsMult != 1.0) c.fs *= bait.fsMult;
        // Treasure Hook (research mining_fishing_gear.md 2b/2g): "only items and treasure" - Sea Creature
        // Chance, Double Hook Chance and Trophy Fish Chance are nullified (resolve() also skips the creature roll).
        for (Part pt : Fishing.parts(c.rod)) if (pt.noCreatures) { c.scc = 0; c.dh = 0; c.trophy = 0; }
        c.cap = Fishing.CAT.fsCap(island, FishDials.FS_CAP.get());
        // Flash: a chance the fish bites the moment the bobber lands.
        double flash = Fishing.enchant(c.rod, "ultimate_flash") / 100.0 * FishEffects.flashMult(p);
        if (flash > 0 && ThreadLocalRandom.current().nextDouble() < flash) {
            c.ticksLeft = 1;
            c.approach = 0;
        } else {
            c.ticksLeft = waitTicks(c, p);
            c.approach = approachTicks(c);
        }
        if (c.hotspot != null) Sky.vitals().flash(p, "&d✦ Hotspot: " + c.hotspot.buff.label(hotMult), 50);
    }

    private static int waitTicks(Cast c, Player p) {
        double lure = Fishing.enchant(c.rod, "lure");
        int base = FishMath.baseTicks(() -> ThreadLocalRandom.current().nextDouble(), lure);
        return FishMath.waitTicks(base, c.fs, c.cap, Math.max(0, FishDials.MIN_WAIT.get()));
    }

    private static int approachTicks(Cast c) {
        int lo = Math.max(0, FishDials.LURE_MIN.get()), hi = Math.max(lo, FishDials.LURE_MAX.get());
        int t = lo + ThreadLocalRandom.current().nextInt(hi - lo + 1);
        double qb = Fishing.enchant(c.rod, "quick_bite") / 100.0;
        return (int) Math.round(t * Math.max(0, 1 - qb));
    }

    private static void approachParticles(FishHook hook, Cast c) {
        if (c.approach % 2 != 0) return;
        Location l = hook.getLocation();
        double d = 0.3 + c.approach * 0.12;
        double a = c.approach * 0.35;
        Location at = l.clone().add(Math.cos(a) * d, 0.1, Math.sin(a) * d);
        l.getWorld().spawnParticle("lava".equals(c.liquid) ? Particle.FLAME : Particle.FISHING, at, 1, 0, 0, 0, 0);
    }

    private static void bite(Player p, FishHook hook, Cast c) {
        c.phase = Phase.BITE;
        c.biteLeft = Math.max(5, FishDials.BITE_WINDOW.get());
        Location l = hook.getLocation();
        boolean lava = "lava".equals(c.liquid);
        l.getWorld().spawnParticle(lava ? Particle.LAVA : Particle.SPLASH, l.clone().add(0, 0.2, 0), lava ? 6 : 25, 0.2, 0.1, 0.2, 0.1);
        p.playSound(l, lava ? Sound.BLOCK_LAVA_POP : Sound.ENTITY_FISHING_BOBBER_SPLASH, 1f, 1f);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.8f);
        if (!lava) hook.setVelocity(new Vector(0, -0.25, 0));
        try {
            TextDisplay td = l.getWorld().spawn(l.clone().add(0, 0.9, 0), TextDisplay.class, t -> {
                t.text(Text.c("&c&l!!!"));
                t.setBillboard(Display.Billboard.CENTER);
                t.setPersistent(false);
                t.setSeeThrough(true);
            });
            c.marker = td.getUniqueId();
        } catch (RuntimeException ignored) { }
    }

    static void clearMarker(Cast c) {
        if (c.marker == null) return;
        Entity e = Bukkit.getEntity(c.marker);
        if (e != null) e.remove();
        c.marker = null;
    }

    // ---- the catch -------------------------------------------------------------------------------

    /** Roll and hand out what this reel caught. */
    static void resolve(Player p, Cast c, Location bobber) {
        String island = Fishing.island(bobber);
        if (island == null) return;
        boolean lava = "lava".equals(c.liquid);
        FishData d = Fishing.data(p);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Part> parts = Fishing.parts(c.rod);
        boolean noCreatures = false;
        for (Part pt : parts) noCreatures |= pt.noCreatures;

        // 1) sea creature?
        double divisor = 1;
        for (String s : FishDials.SCC_PENALTY_ISLANDS.get().split(",")) if (s.trim().equals(island)) divisor = FishDials.SCC_PENALTY_DIVISOR.get();
        double pSea = noCreatures ? 0 : FishMath.seaCreatureProbability(c.scc, divisor);
        if (pSea > 0 && r.nextDouble() < pSea) {
            if (!SeaCreatures.roomFor(p)) {
                Sky.vitals().flash(p, "&cToo many sea creatures around - you reel in a fish instead.", 50);
            } else {
                String mob = pickCreature(p, c, bobber, island);
                if (mob != null) {
                    hookCreature(p, c, bobber, mob, lava);
                    materialize(p, parts);
                    return;
                }
            }
        }
        // 2) not a creature
        if (lava) {
            String stem = Trophies.roll(new Trophies.Context(p, island, Fishing.zoneText(bobber), bobber, c.rodId, c.rod,
                    c.bait, c.trophy, System.currentTimeMillis() - c.landedAt));
            if (stem != null) {
                Trophies.award(p, stem, Fishing.enchant(c.rod, "charm") / 100.0);
                fire(p, stem, false, true);
            } else {
                giveCatch(p, Fishing.CAT.lavaFish, true);
            }
        } else {
            double pTreasure = FishMath.percent(c.treasure);
            if (pTreasure > 0 && r.nextDouble() < pTreasure) treasure(p, c, island, parts);
            else giveCatch(p, Fishing.CAT.fish, false);
        }
        materialize(p, parts);
    }

    /** Pick the creature: base/add pools, weight modifiers, then replacement pools. */
    static String pickCreature(Player p, Cast c, Location bobber, String island) {
        PoolResolver.Spot s = spot(p, c, bobber, island);
        return pickFrom(p, c, Fishing.RES.options(s), mods(p, c, s), Fishing.RES.replacements(s));
    }

    /** The roll itself, from a resolved spot (the admin simulator resolves once and rolls many times). */
    static String pickFrom(Player p, Cast c, List<PoolResolver.Option> opts, PoolResolver.Mods mods, List<PoolResolver.Replacement> reps) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        PoolResolver.Option pick = FishMath.pick(opts, o -> PoolResolver.weight(o, mods), r::nextDouble);
        String mob = pick == null ? null : pick.mob();
        for (PoolResolver.Replacement rep : reps) {
            double chance = replaceChance(p, c, rep.pool());
            if (chance <= 0 || r.nextDouble() >= chance) continue;
            PoolResolver.Option o = FishMath.pick(rep.options(), x -> PoolResolver.weight(x, mods), r::nextDouble);
            if (o != null) { mob = o.mob(); break; }
        }
        return mob;
    }

    static PoolResolver.Spot spot(Player p, Cast c, Location bobber, String island) {
        PoolResolver.Spot s = new PoolResolver.Spot();
        s.island = island;
        s.zoneText = Fishing.zoneText(bobber);
        // Phlegm Pool (Phlegmburst, 0.1% replace): the Crimson Isle has no zone named for the pool, so
        // like the trophy-fish zones it falls back to the whole isle's lava instead of never existing.
        // (same rule as Trophies.zone: strict-zones off, or no such zone on the isle)
        if (Trophies.TROPHY_ISLAND.equals(island)
                && (!FishDials.TROPHY_STRICT_ZONES.get() || !Fishing.islandHasZone(island, "plhleg|phlegm")))
            s.zoneText = (s.zoneText == null ? "" : s.zoneText) + " phlegm";
        s.liquid = c.liquid == null ? (c.lavaRod ? "lava" : "water") : c.liquid;
        s.y = bobber.getY();
        s.fishingLevel = Fishing.level(p);
        s.night = Fishing.night();
        s.bait = c.bait;
        s.carried = carried(p);
        s.festival = Fishing.festival();
        s.spooky = Fishing.spooky();
        s.hotspot = c.hotspot != null;
        s.chumcap = ChumBuckets.ownChumcapNear(p, p.getLocation());
        Bait b = Baits.def(c.bait);
        s.wormsOnly = b != null && b.wormsOnly && "crystal_hollows".equals(island);
        s.requirement = mob -> {
            MobDef d = Sky.mobs().def(mob);
            if (d == null) return false;
            if (d.extra == null || !d.extra.has("requires")) return true;
            return Sky.requirements().check(p, d.extra.get("requires").getAsString());
        };
        return s;
    }

    static Set<String> carried(Player p) {
        Set<String> out = new HashSet<>();
        for (ItemStack it : p.getInventory().getContents()) {
            String id = it == null ? null : Sky.items().idOf(it);
            if (id != null) out.add(id);
        }
        return out;
    }

    static PoolResolver.Mods mods(Player p, Cast c, PoolResolver.Spot s) {
        PoolResolver.Mods m = new PoolResolver.Mods();
        m.rarity = mob -> Fishing.rarityOf(Sky.mobs().def(mob));
        for (Part pt : Fishing.parts(c.rod)) {
            if (pt.commonWeight > 0) m.commonWeight *= pt.commonWeight;
            for (Map.Entry<String, Double> e : pt.mobWeight.entrySet()) m.mob.merge(e.getKey(), e.getValue(), (a, b) -> a * b);
        }
        Bait b = Baits.def(c.bait);
        if (b != null) {
            if (b.rareAlways > 0) m.rareWeight *= b.rareAlways;
            if (b.rareDay > 0 && !s.night) m.rareWeight *= b.rareDay;
            if (b.rareNight > 0 && s.night) m.rareWeight *= b.rareNight;
        }
        ItemStack helm = p.getInventory().getHelmet();
        if (helm != null && "SQUID_HAT".equals(Sky.items().idOf(helm))) { m.mob.merge("sc_squid", 2.0, (a, x) -> a * x); m.mob.merge("sc_inkling", 2.0, (a, x) -> a * x); }
        return m;
    }

    /** Chance that a replacement pool takes over this bite: base + bait / part / armour bonuses, capped. */
    static double replaceChance(Player p, Cast c, FishingCatalog.Pool pool) {
        Bait b = Baits.def(c.bait);
        double bonus = 0;
        double cap = 1.0;
        switch (pool.id) {
            case "shark" -> {
                if (b != null) bonus += b.sharkChance;
                bonus += Sky.items().setPieces(p, "shark_scale") * FishDials.SHARK_ARMOR_PER_PIECE.get();
                cap = FishDials.SHARK_CAP.get();
            }
            case "spooky" -> {
                if (b != null) bonus += b.spookyChance;
                for (Part pt : Fishing.parts(c.rod)) bonus += pt.spookyChance;
                ItemDef rd = Sky.items().def(c.rodId);
                if (rd != null && rd.extra != null && rd.extra.has("spookyBonus")) bonus += rd.extra.get("spookyBonus").getAsDouble();
                cap = FishDials.SPOOKY_CAP.get();
            }
            case "water_hotspot", "lava_hotspot" -> {
                if (b != null) bonus += b.hotspotChance;
                for (Part pt : Fishing.parts(c.rod)) bonus += pt.hotspotChance;
                cap = FishDials.HOTSPOT_CAP.get();
            }
            default -> { }
        }
        // pet perks that are odds here once pets hands them over (FishEffects.petOdds): "a% more often"
        String petKey = switch (pool.id) {
            case "shark" -> "megalodon_feeding_frenzy";
            case "spooky" -> "bat_sonar";
            case "water_hotspot", "lava_hotspot" -> "hermit_crab_hotspot_hazard";
            default -> null;
        };
        double mult = petKey == null ? 1 : 1 + FishEffects.petOdds(p, petKey);
        return FishMath.replaceChance((pool.chance + bonus) * mult, 0, cap);
    }

    private static void hookCreature(Player p, Cast c, Location bobber, String mob, boolean lava) {
        boolean dbl = FishMath.percent(c.dh) > 0 && ThreadLocalRandom.current().nextDouble() < FishMath.percent(c.dh);
        double corrupt = Fishing.enchant(c.rod, "corruption") / 100.0;
        if (corrupt > 0) corrupt += FishEffects.infection(p);     // the attribute raises an existing chance
        double frail = Fishing.enchant(c.rod, "frail");
        Bait b = Baits.def(c.bait);
        int chum = b == null ? 0 : b.chum;
        Location at = bobber.clone().add(0, lava ? 0.6 : 0.3, 0);
        int n = dbl && SeaCreatures.count(p.getUniqueId()) + 2 <= FishDials.CAP_PLAYER.get() ? 2 : 1;
        int spawned = 0;
        for (int i = 0; i < n; i++) if (SeaCreatures.spawn(p, mob, at, corrupt, frail, chum, true) != null) spawned++;
        if (spawned == 0) { giveCatch(p, lava ? Fishing.CAT.lavaFish : Fishing.CAT.fish, lava); return; }
        FishData d = Fishing.data(p);
        d.caught.merge(mob, (long) spawned, Long::sum);
        MobDef def = Sky.mobs().def(mob);
        String rarity = Fishing.rarityOf(def);
        String msg = def != null && def.extra != null && def.extra.has("catchMessage") ? def.extra.get("catchMessage").getAsString() : "Something bit!";
        if (Fishing.setting(p, "fishing.sea_creature_alerts", true)) Msg.send(p, Fishing.rarityColor(rarity) + msg);
        if (spawned > 1) {
            d.doubleHooks++;
            Msg.send(p, "&e&lDOUBLE HOOK! &7Two of them!");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.8f);
        }
        boolean rare = def != null && def.extra != null && def.extra.has("rare") && def.extra.get("rare").getAsBoolean();
        if (rare) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
            if (Fishing.setting(p, "fishing.sea_creature_alerts", true)) {
                p.showTitle(net.kyori.adventure.title.Title.title(Text.c(Fishing.rarityColor(rarity) + "&l" + (def == null ? mob : def.name)),
                        Text.c("&7surfaced from the " + (lava ? "lava" : "depths") + "!"),
                        net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(1800),
                                java.time.Duration.ofMillis(400))));
            }
            if (FishDials.ANNOUNCE_RARE.get()) {
                String line = "&6&lRARE CATCH! &f" + p.getName() + " &7hooked a " + Fishing.rarityColor(rarity) + (def == null ? mob : def.name) + "&7!";
                for (Player o : p.getWorld().getPlayers()) if (o != p) Msg.send(o, line);
            }
        }
        if (d.poolsSeen.putIfAbsent(mob, System.currentTimeMillis()) == null)
            Msg.send(p, "&b&lNEW SEA CREATURE! &7Added &f" + (def == null ? mob : def.name) + " &7to your Sea Creature Guide.");
        fire(p, mob, true, lava);
    }

    private static void giveCatch(Player p, List<FishingCatalog.Catch> table, boolean lava) {
        FishingCatalog.Catch pick = FishMath.pick(table, x -> x.weight, () -> ThreadLocalRandom.current().nextDouble());
        if (pick == null || !Sky.items().exists(pick.item)) return;
        Sky.loot().give(p, pick.item, 1, LootSource.FISHING);
        Fishing.data(p).itemsCaught++;
        Fishing.data(p).fished.merge(pick.item, 1L, Long::sum);
        Fishing.addXp(p, pick.xp, "catch:" + pick.item);
        Bukkit.getPluginManager().callEvent(new SkyEvents.Gather(p, LootSource.FISHING, pick.item, 1, p.getLocation()));
        fire(p, pick.item, false, lava);
    }

    private static void treasure(Player p, Cast c, String island, List<Part> parts) {
        FishingCatalog.Treasure t = Fishing.CAT.treasure;
        double blessed = Fishing.enchant(c.rod, "blessing") / 100.0;
        Bait b = Baits.def(c.bait);
        if (b != null) blessed += b.blessed;
        boolean junk = false;
        for (Part pt : parts) junk |= pt.junk;
        junk &= "backwater_bayou".equals(island);
        double[] qb = FishEffects.treasureQuality(p, junk);
        String q = FishMath.treasureQuality(t.weightsFor(junk), blessed, qb[0], qb[1], () -> ThreadLocalRandom.current().nextDouble());
        List<FishingCatalog.TreasureEntry> table = new ArrayList<>();
        if (junk) table.addAll(t.junk.getOrDefault(q, List.of()));
        else {
            table.addAll(t.water.getOrDefault(q, List.of()));
            Map<String, List<FishingCatalog.TreasureEntry>> extra = t.islands.get(island);
            if (extra != null) table.addAll(extra.getOrDefault(q, List.of()));
        }
        table.removeIf(e -> !"COINS".equals(e.item) && !Sky.items().exists(e.item));
        FishingCatalog.TreasureEntry e = FishMath.pick(table, x -> x.weight, () -> ThreadLocalRandom.current().nextDouble());
        if (e == null) { giveCatch(p, Fishing.CAT.fish, false); return; }
        int n = FishMath.between(e.min, e.max, () -> ThreadLocalRandom.current().nextDouble());
        String what;
        if ("COINS".equals(e.item)) {
            double coins = n * FishDials.TREASURE_COINS_MULT.get() * (1 + FishEffects.treasureCoins(p));
            Sky.economy().deposit(p, coins, "fishing: treasure " + q);
            what = "&6" + Text.commasInt(coins) + " Coins";
        } else {
            Sky.loot().give(p, e.item, n, LootSource.FISHING);
            what = Fishing.name(e.item) + (n > 1 ? " &8x" + n : "");
        }
        FishData d = Fishing.data(p);
        if (!"COINS".equals(e.item)) d.fished.merge(e.item, (long) n, Long::sum);
        String head = switch (q) {
            case "outstanding" -> { d.treasureOutstanding++; yield "&d&lOUTSTANDING CATCH!"; }
            case "great" -> { d.treasureGreat++; yield "&9&lGREAT CATCH!"; }
            default -> { d.treasureGood++; yield "&6&lGOOD CATCH!"; }
        };
        Msg.send(p, head + " &7You found " + what + "&7.");
        if (!junk && ThreadLocalRandom.current().nextDouble() < FishEffects.wetBook(p) && Sky.items().exists("WET_BOOK")) {
            Sky.loot().give(p, "WET_BOOK", 1, LootSource.FISHING);
            Msg.send(p, "&9A soggy &fWet Book &9was tangled in the treasure!");
        }
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, q.equals("outstanding") ? 0.6f : 1.2f);
        Fishing.addXp(p, t.xp.getOrDefault(q, 25.0), "treasure:" + q);
        Bukkit.getPluginManager().callEvent(new SkyEvents.FishCaught(p, "COINS".equals(e.item) ? "COINS" : e.item,
                false, false, true, q));
    }

    /** Sinkers that "materialize" an item with every catch, and the Festive Sinker's gifts. */
    private static void materialize(Player p, List<Part> parts) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Part pt : parts) {
            if (!pt.materialize.isEmpty()) {
                String id = pt.materialize.get(r.nextInt(pt.materialize.size()));
                if (Sky.items().exists(id)) Sky.loot().give(p, id, 1, LootSource.FISHING);
            }
            if (pt.festive > 0 && Fishing.jerrySeason() && r.nextDouble() < pt.festive && Sky.items().exists("WHITE_GIFT")) {
                Sky.loot().give(p, "WHITE_GIFT", 1, LootSource.FISHING);
                Msg.send(p, "&fA &aGift &fwas tangled in your line!");
            }
        }
    }

    private static void fire(Player p, String what, boolean sea, boolean lava) {
        Bukkit.getPluginManager().callEvent(new SkyEvents.FishCaught(p, what, sea, lava));
    }
}
