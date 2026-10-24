package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pests (research 08 1.12): they appear while you farm, hover over the plot, cut your Farming
 * Fortune while they live, and only a vacuum can hurt them. Sprays, repellents, vinyls, traps and
 * the Pestwarden all live here.
 *
 * <p>The COUNT of pests per plot is saved (PlotState.pests); the hovering bodies are not (sky mobs
 * never persist), so they are re-created whenever a member is near the plot.
 */
final class Pests implements Listener {

    /** A live pest body. */
    private record Ref(String key, String plot, String pest, Location home) {}

    private final Map<UUID, Ref> live = new HashMap<>();
    private final Map<UUID, Vector> drift = new HashMap<>();
    private final Map<UUID, Long> lastVacuum = new HashMap<>();
    private final Map<UUID, Long> lastMeleeHint = new HashMap<>();
    static boolean vacuuming;

    // =====================================================================================
    // setup
    // =====================================================================================

    void register() {
        Sky.mobs().registerBehaviour("garden_pest", new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) {
                mob.setGravity(false);
                mob.setSilent(true);
                mob.setPersistent(false);
                if (mob instanceof Slime s) s.setSize(1);
            }

            @Override public void onTick(LivingEntity mob, MobDef def, long tick) { hover(mob); }

            @Override public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
                if (vacuuming) return;
                ctx.cancelled = true;
                // Say why, once in a while: a sword hit that silently does nothing reads as a broken mob
                // (wave-4 loops playtest: 30 clean hits on a Fly, no damage, no message).
                Player hitter = ctx.playerAttacker();
                if (hitter != null) {
                    long now = System.currentTimeMillis();
                    Long last = lastMeleeHint.get(hitter.getUniqueId());
                    if (last == null || now - last > 10_000L) {
                        lastMeleeHint.put(hitter.getUniqueId(), now);
                        Sky.vitals().flash(hitter, "&6ൠ &cOnly a Vacuum hurts pests! &7(Copper Market at the barn)", 60);
                    }
                }
            }
        });
        Sky.abilities().register("garden_vacuum", this::vacuum);
        Sky.abilities().register("garden_pest_tracker", this::tracker);
        Sky.abilities().register("garden_stereo", u -> { stereoMenu(u.player()); return true; });
        Sky.abilities().register("garden_spray", this::spray);
        Sky.abilities().register("garden_spray_config", u -> { sprayMenu(u.player(), u.item()); return true; });
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::maintain, 60L, 60L);
    }

    // =====================================================================================
    // spawning
    // =====================================================================================

    /** Roll for pests after a Garden crop break (research 08 1.12 "Pest roll"). */
    void onCropBroken(Player p, GardenData d, String plot, GardenTables.Crop crop, Location at) {
        if (G.level(d) < 5) return;
        long now = System.currentTimeMillis();
        if (now < d.pestCooldownUntil) return;
        GardenData.PlotState ps = d.plots.get(plot);
        double chance = G.pestChance.get();
        boolean sprayed = ps != null && ps.sprayUntil > now;
        if (sprayed) chance *= Math.max(1, ps.sprayMult);
        chance *= Effects.filterPestMult(p);              // Atmospheric Filter, Autumn: pests 15% more often
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() >= chance) return;
        if (d.livePests() >= G.pestMaxLive.get()) return;
        // the radio only plays a vinyl someone in the garden still owns
        if (d.vinyl != null && G.count(p, d.vinyl) <= 0) d.vinyl = null;
        double bpc = Sky.stats().get(p, Stat.PEST_CHANCE);
        int n = Math.min(G.pestMaxLive.get() - d.livePests(), GardenMath.pestsPerSpawn(bpc, r.nextDouble()));
        Map<String, Integer> made = new LinkedHashMap<>();
        double jazz = 1 + Math.max(0, Effects.pet(p, "mosquito_smooth_jazz")) / 100.0;
        for (int i = 0; i < n; i++) {
            GardenTables.Pest pest = pick(d, ps, crop, sprayed, jazz);
            if (pest == null) break;
            made.merge(pest.id, 1, Integer::sum);
            if (ps != null) addPest(ps, pest.id);
            spawnBody(G.key(p), plot, pest.id, at);
        }
        if (made.isEmpty()) return;
        d.pestCooldownUntil = now + (long) (cooldown(p) * 1000);
        int total = made.values().stream().mapToInt(Integer::intValue).sum();
        String where = "&aPlot &7- &b" + G.T.plot(plot).number;
        for (Player m : G.members(G.key(p))) {
            if (total == 1) Msg.send(m, "&6&lYUCK! &7A &6ൠ " + G.T.pest(made.keySet().iterator().next()).name + " &7has appeared in " + where + "&7!");
            else Msg.send(m, "&6&lEWW! &6" + total + " &6ൠ Pests &7have spawned in " + where + "&7!");
            Msg.sound(m, Sound.ENTITY_SILVERFISH_AMBIENT, 0.6f);
        }
    }

    /** Weighted pest choice: base 100, +spray, +crop bias, +vinyl (research 08 1.12). */
    GardenTables.Pest pick(GardenData d, GardenData.PlotState ps, GardenTables.Crop crop, boolean sprayed) {
        return pick(d, ps, crop, sprayed, 1.0);
    }

    /** {@code vinylMult}: the Mosquito's Smooth Jazz ("pest vinyls are N% more effective") scales the vinyl's weight. */
    GardenTables.Pest pick(GardenData d, GardenData.PlotState ps, GardenTables.Crop crop, boolean sprayed, double vinylMult) {
        int lvl = G.level(d);
        String vinylPest = d.vinyl == null ? null : G.T.garden.vinyls.get(d.vinyl);
        double total = 0;
        List<GardenTables.Pest> cand = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        boolean night = isNight();
        for (GardenTables.Pest pest : G.T.pests()) {
            if (pest.level > lvl) continue;
            if (pest.nightOnly && !night && G.nightPests.get()) continue;     // wiki: "Fireflies can only spawn during the night."
            double wt = 100;
            boolean flower = crop != null && pest.crops.contains(crop.key);
            if (pest.id.equals("lunar_moth") && !flower) wt = 0;
            if (crop != null && crop.pest.equals(pest.id)) wt += G.pestCropBias.get();
            if (sprayed && ps.sprayItem != null && pest.attractants.contains(ps.sprayItem)) wt += G.sprayWeight.get();
            if (pest.id.equals(vinylPest)) wt += 1000 * Math.max(1, vinylMult);
            if (wt <= 0) continue;
            cand.add(pest);
            w.add(wt);
            total += wt;
        }
        if (cand.isEmpty()) return null;
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (int i = 0; i < cand.size(); i++) { roll -= w.get(i); if (roll <= 0) return cand.get(i); }
        return cand.get(cand.size() - 1);
    }

    /** SkyBlock night (core calendar); day when the calendar cannot say. */
    static boolean isNight() {
        try { return Sky.calendar().now().isNight(); } catch (Throwable t) { return false; }
    }

    /** Seconds off the pest cooldown from the hunting Pest Cooldown attribute (Moth shard; 0 without hunting). */
    static double attrCooldownSeconds(Player p) {
        try { return Math.max(0, Sky.service(Features.Hunting.class).attributeLevel(p, "value:pest_cooldown")); } catch (Throwable t) { return 0; }
    }

    double cooldown(Player p) {
        double reduction = 0;
        if (G.perk(G.pestPerk.get())) reduction += 0.20;
        try {
            for (ItemStack it : Sky.service(Features.Equipment.class).worn(p)) {
                String id = Sky.items().idOf(it);
                if (id == null) continue;
                if (id.startsWith("PESTHUNTERS_")) reduction += 0.10;
                if (id.equals("PEST_VEST")) reduction += 0.15;
                // Squeaky reforge on a worn piece: pests return 2.5% sooner per piece (wiki, newer than the reforge
                // data's NEU 1%), so a Pest Vest + 3 Pesthunter pieces + 4 Squeaky reach the 2m10s floor (garden_max 1.2)
                if ("squeaky".equals(Effects.reforgeOf(it))) reduction += G.squeakyFallback.get() / 100.0;
            }
        } catch (Throwable ignored) { }
        GardenMember m = G.member(p);
        double rep = m.repellentUntil > System.currentTimeMillis() ? Math.max(1, m.repellentFactor) : 1;
        // research 08 1.12: the floor is 1m10s "with Eradicator" (the mayor perk), 2m10s without
        double min = G.perk(G.pestPerk.get()) ? Math.min(G.pestMinCooldown.get(), 70) : G.pestMinCooldown.get();
        return GardenMath.pestCooldown(G.pestCooldown.get(), rep, reduction, attrCooldownSeconds(p), min, G.pestMaxCooldown.get());
    }

    /** Spawn one hovering pest body in a plot of an instance. {@code near} may be null (random spot). */
    LivingEntity spawnBody(String key, String plot, String pestId, Location near) {
        GardenTables.Pest pest = G.T.pest(pestId);
        GardenTables.Plot pl = G.T.plot(plot);
        if (pest == null || pl == null) return null;
        Box in = PlotGrid.inner(pl.col, pl.row);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double tx, tz;
        Location o = G.origin(key);
        if (o == null) return null;
        if (near != null) {
            tx = near.getX() - o.getX() + r.nextDouble(-6, 6);
            tz = near.getZ() - o.getZ() + r.nextDouble(-6, 6);
        } else {
            tx = r.nextDouble(in.minX() + 4, in.maxX() - 4);
            tz = r.nextDouble(in.minZ() + 4, in.maxZ() - 4);
        }
        tx = Math.max(in.minX() + 1, Math.min(in.maxX() - 1, tx));
        tz = Math.max(in.minZ() + 1, Math.min(in.maxZ() - 1, tz));
        Location at = new Location(o.getWorld(), o.getX() + tx, PlotGrid.GROUND_Y + 2.5 + r.nextDouble(1.5), o.getZ() + tz);
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return null;
        LivingEntity e;
        try { e = Sky.mobs().spawn(pest.mob, at); } catch (Throwable t) { Sky.log().warning("[garden] pest spawn failed: " + t); return null; }
        if (e == null) return null;
        double mult = G.pestHealthMult.get();
        if (mult != 1.0) Sky.mobs().setMaxHealth(e, Sky.mobs().maxHealth(e) * mult);
        live.put(e.getUniqueId(), new Ref(key, plot, pestId, at.clone()));
        at.getWorld().spawnParticle(Particle.CLOUD, at, 8, 0.3, 0.3, 0.3, 0.02);
        return e;
    }

    private void hover(LivingEntity mob) {
        Ref ref = live.get(mob.getUniqueId());
        if (ref == null) return;
        Vector v = drift.computeIfAbsent(mob.getUniqueId(), k -> randomDir());
        if (ThreadLocalRandom.current().nextDouble() < 0.08) { v = randomDir(); drift.put(mob.getUniqueId(), v); }
        Location l = mob.getLocation().add(v);
        Location home = ref.home;
        if (l.distanceSquared(home) > 14 * 14 || l.getY() < PlotGrid.GROUND_Y + 1.6 || l.getY() > PlotGrid.GROUND_Y + 5) {
            Vector back = home.toVector().subtract(mob.getLocation().toVector()).normalize().multiply(0.35);
            drift.put(mob.getUniqueId(), back);
            l = mob.getLocation().add(back);
        }
        l.setYaw((float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ())));
        if (!l.getBlock().isPassable()) { drift.put(mob.getUniqueId(), randomDir()); return; }
        mob.teleport(l);
    }

    private static Vector randomDir() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new Vector(r.nextDouble(-1, 1), r.nextDouble(-0.3, 0.3), r.nextDouble(-1, 1)).normalize().multiply(0.3);
    }

    /** Every 3 s: re-create bodies near members, drop bodies nobody is near, expire sprays, catch offline pests up. */
    private void maintain() {
        // drop stale refs
        for (Iterator<Map.Entry<UUID, Ref>> it = live.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Ref> e = it.next();
            Entity ent = Bukkit.getEntity(e.getKey());
            if (ent == null || !ent.isValid()) { it.remove(); drift.remove(e.getKey()); }
        }
        Map<String, Player> byKey = new HashMap<>();
        // (every body is accounted for by kind, so a pest that was a Fly when it spawned is a Fly when it comes back)
        for (Player p : Bukkit.getOnlinePlayers()) if (G.ready(p) && G.onGarden(p) && G.inOwnGarden(p)) byKey.putIfAbsent(G.key(p), p);
        // remove bodies of gardens nobody is in
        for (Iterator<Map.Entry<UUID, Ref>> it = live.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Ref> e = it.next();
            if (byKey.containsKey(e.getValue().key)) continue;
            Entity ent = Bukkit.getEntity(e.getKey());
            if (ent instanceof LivingEntity le) Sky.mobs().remove(le);
            it.remove();
        }
        for (Map.Entry<String, Player> e : byKey.entrySet()) {
            Player p = e.getValue();
            GardenData d = G.data(p);
            Map<String, List<String>> have = new HashMap<>();
            for (Ref r : live.values()) if (r.key.equals(e.getKey())) have.computeIfAbsent(r.plot, k -> new ArrayList<>()).add(r.pest);
            for (Map.Entry<String, GardenData.PlotState> ps : d.plots.entrySet()) {
                GardenData.PlotState st = ps.getValue();
                List<String> got = have.getOrDefault(ps.getKey(), List.of());
                int want = Math.max(0, st.pests);
                if (want <= got.size()) continue;
                GardenTables.Plot pl = G.T.plot(ps.getKey());
                if (pl == null) continue;
                Location c = G.worldAt(e.getKey(), PlotGrid.centerX(pl.col), PlotGrid.GROUND_Y, PlotGrid.centerZ(pl.row));
                if (c == null || c.getWorld() != p.getWorld() || c.distanceSquared(p.getLocation()) > 140 * 140) continue;
                // the kinds still missing a body: the saved kinds minus the ones already flying
                List<String> missing = new ArrayList<>(st.pestTypes);
                for (String g : got) missing.remove(g);
                while (st.pestTypes.size() < want) { String t = randomPestFor(d); st.pestTypes.add(t); missing.add(t); }
                int need = want - got.size();
                for (int i = 0; i < need; i++) spawnBody(e.getKey(), ps.getKey(), i < missing.size() ? missing.get(i) : randomPestFor(d), null);
            }
        }
    }

    /** One more pest of {@code type} on a plot (count and kind together). */
    static void addPest(GardenData.PlotState s, String type) {
        s.pests = Math.max(0, s.pests) + 1;
        if (type != null) s.pestTypes.add(type);
    }

    /** One pest of {@code type} gone from a plot. */
    static void removePest(GardenData.PlotState s, String type) {
        s.pests = Math.max(0, s.pests - 1);
        if (!s.pestTypes.remove(type) && !s.pestTypes.isEmpty() && s.pestTypes.size() > s.pests) s.pestTypes.remove(0);
        while (s.pestTypes.size() > s.pests) s.pestTypes.remove(s.pestTypes.size() - 1);
    }

    static void clearPests(GardenData.PlotState s) { s.pests = 0; s.pestTypes.clear(); }

    String randomPestFor(GardenData d) {
        GardenTables.Pest p = pick(d, null, null, false);
        return p == null ? "fly" : p.id;
    }

    /** Offline catch-up (research 08 1.12: 1 pest per hour offline), on the first member's login. */
    void offline(Player p, GardenData d) {
        long now = System.currentTimeMillis();
        if (d.lastSeen > 0 && G.level(d) >= 5 && !d.plots.isEmpty()) {
            double hours = (now - d.lastSeen) / 3_600_000.0;
            int n = (int) Math.floor(hours * G.pestOfflinePerHour.get());
            n = Math.min(n, G.pestMaxLive.get() - d.livePests());
            List<String> ids = new ArrayList<>(d.plots.keySet());
            for (int i = 0; i < n; i++) addPest(d.plots.get(ids.get(ThreadLocalRandom.current().nextInt(ids.size()))), randomPestFor(d));
            if (n > 0) Msg.send(p, "&6&lEWW! &7While you were away &6" + n + " ൠ pest" + (n == 1 ? "" : "s") + " &7moved into your Garden.");
        }
        d.lastSeen = now;
    }

    // =====================================================================================
    // killing
    // =====================================================================================

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.def == null || e.def.id == null || !e.def.id.startsWith("garden_pest_")) return;
        Ref ref = live.remove(e.mob.getUniqueId());
        drift.remove(e.mob.getUniqueId());
        Player p = e.killer;
        String pestId = e.def.id.substring("garden_pest_".length());
        if (p == null || !G.ready(p)) return;
        GardenData d = G.data(p);
        String plot = ref != null ? ref.plot : null;
        if (plot != null && d.plots.containsKey(plot)) removePest(d.plots.get(plot), pestId);
        reward(p, pestId, plot);
        pestHarvest(p, pestId, e.def);
    }

    /** Pest kill rewards beyond the mob's coins and drops (core pays those): Farming XP, bits, counters. */
    void reward(Player p, String pestId, String plot) {
        GardenMember m = G.member(p);
        m.pestsKilled++;
        m.pestKills.merge(pestId, 1L, Long::sum);
        m.pestBag++;                                   // the Pests currency: 1 per pest vacuumed (lane 9, G7)
        GardenTables.Pest pest = G.T.pest(pestId);
        // 1,500 Farming XP a pest; the Field Mouse 6,000 (wiki)
        double xp = G.pestFarmingXp.get() * (pest == null ? 1 : Math.max(0, pest.xpMult));
        G.farmingXp(p, xp, "garden:pest");
        long bits = Math.round(G.pestBits.get());
        if (bits > 0) Sky.economy().addBits(p, bits, "garden: pest " + pestId);
        String where = plot == null ? "" : " &7in &aPlot &7- &b" + G.T.plot(plot).number;
        Msg.send(p, "&6&lPEST! &7You killed a &6" + (pest == null ? pestId : pest.name) + where + "&7! &8(+" + Text.commasInt(xp)
                + " Farming XP, +1 Pest &7- &6" + Text.commasInt(m.pestBag) + " &8in your bag)");
        Msg.sound(p, Sound.ENTITY_ITEM_PICKUP, 0.6f);
        ItemStack hand = p.getInventory().getItemInMainHand();
        ItemDef def = Sky.items().def(Sky.items().idOf(hand));
        if (def != null && def.category == com.skyclone.api.item.ItemCategory.VACUUM)
            Sky.items().update(hand, dd -> dd.extra.addProperty("vacuum_bag",
                    (dd.extra.has("vacuum_bag") ? dd.extra.get("vacuum_bag").getAsLong() : 0) + 1));
        Sky.sidebar().refresh(p);
    }

    /** Live pest bodies of one instance. */
    List<LivingEntity> bodies(String key) {
        List<LivingEntity> out = new ArrayList<>();
        for (Map.Entry<UUID, Ref> e : live.entrySet())
            if (e.getValue().key.equals(key) && Bukkit.getEntity(e.getKey()) instanceof LivingEntity le && le.isValid()) out.add(le);
        return out;
    }

    void clearBodies(String key) {
        for (LivingEntity le : bodies(key)) { live.remove(le.getUniqueId()); Sky.mobs().remove(le); }
    }

    // =====================================================================================
    // vacuums
    // =====================================================================================

    private boolean vacuum(AbilityService.Use u) {
        Player p = u.player();
        if (!G.onGarden(p) || !G.inOwnGarden(p)) { Sky.vitals().flash(p, "&cVacuums only work in your Garden!", 30); return false; }
        GardenTables.Vacuum v = G.T.vacuum(u.itemDef().id);
        double range = v != null ? v.range : u.itemDef().extra.has("range") ? u.itemDef().extra.get("range").getAsDouble() : 5;
        double dps = v != null ? v.damage : u.itemDef().damage;
        long now = System.currentTimeMillis();
        Long last = lastVacuum.put(p.getUniqueId(), now);
        double dt = last == null ? 0.2 : Math.max(0.05, Math.min(0.25, (now - last) / 1000.0));
        GardenData d = G.data(p);
        dps = vacuumDamage(p, u.item(), dps, 0);
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        boolean hit = false;
        for (LivingEntity pest : bodies(G.key(p))) {
            Location c = pest.getLocation().add(0, pest.getHeight() / 2, 0);
            Vector to = c.toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > range || dist < 0.01) continue;
            if (to.normalize().dot(look) < G.vacuumCone.get()) continue;
            vacuuming = true;
            try { Sky.combat().rawDamage(pest, dps * dt, p, DamageContext.Kind.TRUE); }
            finally { vacuuming = false; }
            beam(eye, c);
            pest.teleport(pest.getLocation().add(eye.toVector().subtract(c.toVector()).normalize().multiply(0.15)));
            hit = true;
        }
        if (!hit) eye.getWorld().spawnParticle(Particle.CLOUD, eye.clone().add(look.clone().multiply(2)), 2, 0.1, 0.1, 0.1, 0.01);
        else Msg.sound(p, Sound.ENTITY_PHANTOM_FLAP, 1.6f);
        return true;
    }

    /**
     * A vacuum's damage per second with everything that touches it: the held vacuum's stat sheet damage bonus
     * (Beady's and Bookworm's +Damage), the Buzzing reforge (double damage), the Hedgehog's Quills (+N% to pests)
     * and the garden's vacuum chip.
     */
    static double vacuumDamage(Player p, ItemStack vacuum, double base, double chipPct) {
        double dps = base;
        try { dps += Math.max(0, Sky.items().sheet(vacuum).damageBonus()); } catch (Throwable ignored) { }
        if ("buzzing".equals(Effects.reforgeOf(vacuum))) dps *= 2;
        dps *= 1 + Math.max(0, Effects.pet(p, "hedgehog_spiky_quills")) / 100.0;
        dps *= 1 + chipPct / 100.0;
        return dps;
    }

    /**
     * Farming Fortune against pests: the player's Farming Fortune, Beady on the held vacuum (+100), the Pesthunter's
     * equipment "Eradicator" tiers and the Hedgehog's Pest Terror.
     */
    static double pestFortune(Player p) {
        double f = Sky.stats().get(p, Stat.FARMING_FORTUNE);
        f += Effects.reforgeValue(p.getInventory().getItemInMainHand(), "beady", 0, 100);
        f += EffectMath.eradicatorFortune(Effects.pesthunterPieces(p), G.eradicatorPerPiece.get());
        f += Effects.pet(p, "hedgehog_fearsome_farmer");
        return f;
    }

    /** The crop a pest is tied to (research 08 1.12); the Field Mouse and Lunar Moth pick one. */
    static GardenTables.Crop cropOf(String pestId, int gardenLevel) {
        List<GardenTables.Crop> pool = new ArrayList<>();
        for (GardenTables.Crop c : G.T.crops()) {
            if (c.level > gardenLevel) continue;
            if (pestId.equals(c.pest)) return c;
            boolean flower = c.key.equals("SUNFLOWER") || c.key.equals("MOONFLOWER") || c.key.equals("WILD_ROSE");
            if ("field_mouse".equals(pestId) || "lunar_moth".equals(pestId) && flower) pool.add(c);
        }
        return pool.isEmpty() ? null : pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /**
     * What a pest gives on top of core's drop roll (lane 9):
     * <ul>
     *   <li>its guaranteed crop: {@code base + fortune / scale} ENCHANTED crops (G1), the fortune being Farming Fortune
     *       against pests plus that crop's own crop fortune (crop upgrades, crop accessories). The Lunar Moth drops all
     *       three flowers, the Field Mouse one random crop on that crop's pest row; the Slug a red or brown mushroom;</li>
     *   <li>Overbloom's extra chance at its rare drops - the 15% rare crop (G2) included - since core rolled them at
     *       their base chance; this tops the odds up to {@code chance x (1 + Overbloom/100)};</li>
     *   <li>the Field Mouse's 30% random rare crop.</li>
     * </ul>
     * The crops count towards crop milestones as the base crops they are made of (160 each).
     */
    void pestHarvest(Player p, String pestId, MobDef def) {
        GardenData d = G.data(p);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        GardenTables.Pest pest = G.T.pest(pestId);
        double vsPests = pestFortune(p);
        for (GardenTables.Crop c : pestCrops(pestId, G.level(d))) {
            GardenTables.CropRow row = pest != null && pest.cropScale > 0 ? null : G.T.garden.pests.cropRows.get(c.key);
            double base = row != null ? row.base : pest == null ? 1 : pest.cropBase;
            double scale = row != null ? row.scale : pest == null ? 52.5 : pest.cropScale;
            double fortune = vsPests + d.upgrade(c.key) * G.T.garden.cropUpgrades.fortunePerTier + Effects.accessoryCropFortune(p, c.key);
            long n = EffectMath.pestEnchantedCrops(base, scale, fortune, G.pestCropMult.get(), r.nextDouble());
            String ench = c.enchanted;
            if ("MUSHROOM".equals(c.key) && r.nextBoolean() && G.exists("ENCHANTED_BROWN_MUSHROOM")) ench = "ENCHANTED_BROWN_MUSHROOM";
            if (n <= 0 || !G.exists(ench)) continue;
            G.give(p, ench, n, LootSource.FARMING);
            CropListener.milestone(p, d, c, n * 160);
        }
        double ob = Effects.overbloom(p, true);
        String rareCrop = pest == null ? null : pest.rareCrop;
        if ("RANDOM".equals(rareCrop)) {
            // the Field Mouse: a random rare crop (wiki: 30%, of an in-season crop - this game has no crop seasons)
            List<String> pool = new ArrayList<>();
            for (String id : G.T.garden.pests.rareCrops) if (G.exists(id)) pool.add(id);
            if (!pool.isEmpty() && r.nextDouble() < Math.min(1, EffectMath.overbloomChance(pest.rareChance, ob))) {
                String got = pool.get(r.nextInt(pool.size()));
                G.give(p, got, 1, LootSource.GARDEN);
                Msg.rareDrop(p, "&6&lPEST DROP!", G.itemName(got), 0);
            }
        }
        if (def == null || ob <= 0) return;
        for (var drop : def.drops) {
            boolean rare = drop.item.equals(rareCrop);
            if ((drop.chance > 0.05 && !rare) || drop.chance <= 0 || !G.exists(drop.item)) continue;
            if (r.nextDouble() >= EffectMath.topUpChance(drop.chance, ob)) continue;
            int amt = drop.min + (drop.max > drop.min ? r.nextInt(drop.max - drop.min + 1) : 0);
            G.give(p, drop.item, Math.max(1, amt), LootSource.GARDEN);
            Msg.rareDrop(p, "&6&lPEST DROP! &d(Overbloom)", G.itemName(drop.item), 0);
        }
    }

    /** The crops a pest drops: its own, the Lunar Moth's three flowers, or one random unlocked crop for the Field Mouse. */
    static List<GardenTables.Crop> pestCrops(String pestId, int gardenLevel) {
        if ("lunar_moth".equals(pestId)) {
            List<GardenTables.Crop> out = new ArrayList<>();
            for (String k : List.of("SUNFLOWER", "MOONFLOWER", "WILD_ROSE")) { GardenTables.Crop c = G.T.crop(k); if (c != null) out.add(c); }
            return out;
        }
        GardenTables.Crop c = cropOf(pestId, gardenLevel);
        return c == null ? List.of() : List.of(c);
    }

    private static void beam(Location from, Location to) {
        Vector step = to.toVector().subtract(from.toVector());
        double len = step.length();
        step.normalize().multiply(0.6);
        Location l = from.clone();
        for (double t = 0; t < len; t += 0.6) {
            l.add(step);
            from.getWorld().spawnParticle(Particle.CLOUD, l, 1, 0.02, 0.02, 0.02, 0);
        }
    }

    private boolean tracker(AbilityService.Use u) {
        Player p = u.player();
        if (!G.onGarden(p) || !G.inOwnGarden(p)) { Sky.vitals().flash(p, "&cThere are no pests to track here.", 30); return false; }
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity pest : bodies(G.key(p))) {
            double dd = pest.getLocation().distanceSquared(p.getLocation());
            if (dd < bd) { bd = dd; best = pest; }
        }
        if (best == null) {
            GardenData d = G.data(p);
            if (d.livePests() > 0) {
                for (Map.Entry<String, GardenData.PlotState> e : d.plots.entrySet())
                    if (e.getValue().pests > 0) { Sky.vitals().flash(p, "&6ൠ Pests waiting in &a" + G.plotName(d, e.getKey()), 50); return true; }
            }
            Sky.vitals().flash(p, "&aNo pests in your Garden!", 40);
            return true;
        }
        Location eye = p.getEyeLocation();
        Vector dir = best.getLocation().toVector().subtract(eye.toVector());
        double dist = dir.length();
        dir.normalize();
        for (int i = 1; i <= 16; i++) eye.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, eye.clone().add(dir.clone().multiply(i * 0.8)), 1, 0, 0, 0, 0);
        Sky.vitals().flash(p, "&6ൠ Nearest pest: &e" + Text.commasInt(dist) + "m", 50);
        return true;
    }

    // =====================================================================================
    // sprays, repellent, larvae, vinyls
    // =====================================================================================

    static final List<String> SPRAYS = List.of("COMPOST", "HONEY_JAR", "DUNG", "PLANT_MATTER", "CHEESE_FUEL", "JELLY");

    private boolean spray(AbilityService.Use u) {
        Player p = u.player();
        if (!G.onGarden(p) || !G.inOwnGarden(p)) { Msg.error(p, "You can only spray plots in your own Garden!"); return false; }
        int[] t = G.template(G.key(p), p.getLocation());
        String plot = t == null ? null : G.plotAt(t[0], t[2]);
        GardenData d = G.data(p);
        if (plot == null || !d.plots.containsKey(plot)) { Msg.error(p, "Stand in one of your plots to spray it!"); return false; }
        String mat = selected(u.item());
        GardenTables.Sprayer s = G.T.sprayer(u.itemDef().id);
        if (s == null) return false;
        int uses = Math.max(1, s.uses);               // wiki: a Basic nozzle uses 1 material, Juicy 3, Salty 5
        if (G.count(p, mat) < uses) {
            Msg.error(p, "You need " + uses + "x " + Text.strip(G.itemName(mat)) + " to spray! Left-click to pick a material.");
            return false;
        }
        if (!G.take(p, G.one(mat, uses))) return false;
        GardenData.PlotState ps = d.plots.get(plot);
        ps.sprayItem = mat;
        ps.sprayUntil = System.currentTimeMillis() + s.minutes * 60_000L;
        ps.sprayBpc = s.bpc;
        ps.sprayMult = s.mult;
        Msg.success(p, "SPRAYED! &7You sprayed &a" + G.plotName(d, plot) + " &7with " + G.itemName(mat) + "&7 for &a" + s.minutes + "m&7!");
        p.getWorld().spawnParticle(Particle.FALLING_WATER, p.getLocation().add(0, 2, 0), 40, 2, 0.5, 2, 0);
        return true;
    }

    static String selected(ItemStack it) {
        try {
            var data = Sky.items().data(it);
            if (data.extra.has("spray")) return data.extra.get("spray").getAsString();
        } catch (Throwable ignored) { }
        return "COMPOST";
    }

    void sprayMenu(Player p, ItemStack sprayer) {
        Menu m = new Menu("Plot Sprayer", 3).chrome();
        int slot = 10;
        for (String mat : SPRAYS) {
            List<String> lore = new ArrayList<>();
            List<String> who = new ArrayList<>();
            for (GardenTables.Pest pest : G.T.pests()) if (pest.attractants.contains(mat)) who.add(pest.name);
            lore.add("&7Attracts: &a" + String.join(", ", who));
            lore.add("&7You have: &e" + Text.commasInt(G.count(p, mat)));
            lore.add("");
            boolean sel = mat.equals(selected(sprayer));
            lore.add(sel ? "&aSelected!" : "&eClick to select!");
            ItemStack icon = Icons.withLore(G.icon(mat, 1), lore);
            if (sel) Icons.glow(icon);
            m.set(slot++, icon, c -> {
                ItemStack hand = c.player().getInventory().getItemInMainHand();
                if (G.T.sprayer(Sky.items().idOf(hand)) == null) { Msg.error(c.player(), "Hold your sprayer!"); return; }
                Sky.items().update(hand, dd -> dd.extra.addProperty("spray", mat));
                Msg.success(c.player(), "Your sprayer now uses " + Text.strip(G.itemName(mat)) + ".");
                c.player().closeInventory();
            });
        }
        m.open(p);
    }

    void stereoMenu(Player p) {
        GardenData d = G.data(p);
        Menu m = new Menu("Pest Radio", 4).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24};
        int i = 0;
        for (Map.Entry<String, String> e : G.T.garden.vinyls.entrySet()) {
            if (i >= slots.length) break;
            String vinyl = e.getKey();
            GardenTables.Pest pest = G.T.pest(e.getValue());
            boolean own = G.count(p, vinyl) > 0;
            boolean playing = vinyl.equals(d.vinyl);
            List<String> lore = new ArrayList<>(List.of("&7Draws in: &6" + (pest == null ? "?" : pest.name), ""));
            lore.add(playing ? "&aNow playing! &eClick to stop." : own ? "&eClick to play!" : "&cYou need this vinyl.");
            ItemStack icon = own ? Icons.withLore(G.icon(vinyl, 1), lore) : Icons.of(Material.GRAY_DYE, "&c" + Text.strip(G.itemName(vinyl)), lore);
            if (playing) Icons.glow(icon);
            m.set(slots[i++], icon, c -> {
                GardenData dd = G.data(c.player());
                if (vinyl.equals(dd.vinyl)) { dd.vinyl = null; Msg.send(c.player(), "&7The music stops."); }
                else if (G.count(c.player(), vinyl) > 0) { dd.vinyl = vinyl; Msg.success(c.player(), "Now playing " + Text.strip(G.itemName(vinyl)) + "."); }
                else { Msg.error(c.player(), "You don't have that vinyl!"); return; }
                stereoMenu(c.player());
            });
        }
        m.back("Pests", () -> PestMenus.build(p));
        m.open(p);
    }

    /** Right-click consumables: repellents and larvae. */
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        String id = it == null ? null : Sky.items().idOf(it);
        if (id == null) return;
        Player p = e.getPlayer();
        switch (id) {
            case "PEST_REPELLENT", "PEST_REPELLENT_MAX" -> {
                e.setCancelled(true);
                if (!G.ready(p)) return;
                int f = id.endsWith("MAX") ? 4 : 2;
                GardenMember m = G.member(p);
                long now = System.currentTimeMillis();
                if (m.repellentUntil <= now || f >= m.repellentFactor) m.repellentFactor = f;   // MAX wins, they do not stack
                m.repellentUntil = now + 3_600_000L;
                consume(p, it);
                Msg.success(p, "You smell terrible to pests: they take &a" + m.repellentFactor + "x &7as long to show up for &a60m&7.");
            }
            case "WRIGGLING_LARVA" -> {
                e.setCancelled(true);
                if (!G.ready(p)) return;
                GardenMember m = G.member(p);
                if (m.larva >= 5) { Msg.error(p, "You can't stomach another one."); return; }
                m.larva++;
                consume(p, it);
                Sky.stats().invalidate(p);
                Msg.success(p, "Gross. &2+2 ൠ Bonus Pest Chance &7(" + m.larva + "/5)");
            }
            case "LOCUST_LARVA" -> {
                e.setCancelled(true);
                if (!G.ready(p) || !G.onGarden(p)) { Msg.error(p, "Eat this on your Garden!"); return; }
                consume(p, it);
                reward(p, "locust", null);
            }
            default -> { }
        }
    }

    private static void consume(Player p, ItemStack it) {
        if (it.getAmount() > 1) it.setAmount(it.getAmount() - 1); else p.getInventory().setItemInMainHand(null);
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 1f);
    }

    // =====================================================================================
    // traps (offline catching) - lazy: computed when looked at
    // =====================================================================================

    static final long TRAP_MILLIS = 15 * 60_000L;     // research 08 1.12: ~1 pest per 15 minutes

    void tickTraps(GardenData d) { tickTraps(d, 0); }

    /** {@code fasterPct}: the Mosquito's Turncoat ("pest traps catch their next pest N% faster"). */
    void tickTraps(GardenData d, double fasterPct) {
        long now = System.currentTimeMillis();
        long every = (long) Math.max(60_000L, TRAP_MILLIS * (1 - Math.max(0, Math.min(90, fasterPct)) / 100.0));
        for (GardenData.TrapState t : d.traps) {
            GardenTables.Trap def = G.T.trap(t.item);
            if (def == null) continue;
            if (t.last <= 0) t.last = now;
            while (t.baitLeft > 0 && t.caught.size() < def.capacity && now - t.last >= every) {
                t.last += every;
                String pest = trapPick(d, t.bait, def.mouseMult);
                if (pest == null) break;
                t.caught.add(pest);
                t.baitLeft--;
            }
            if (t.baitLeft <= 0 || t.caught.size() >= def.capacity) t.last = now;
        }
    }

    private String trapPick(GardenData d, String bait, double mouseMult) {
        int lvl = Math.max(5, G.level(d));
        double total = 0;
        List<String> ids = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        for (GardenTables.Pest p : G.T.pests()) {
            if (p.level > lvl) continue;
            double wt = 100 + (p.attractants.contains(bait) ? 1000 : 0);
            if (p.id.equals("field_mouse")) wt *= mouseMult;
            ids.add(p.id);
            w.add(wt);
            total += wt;
        }
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (int i = 0; i < ids.size(); i++) { r -= w.get(i); if (r <= 0) return ids.get(i); }
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Empty a trap: every caught pest counts as killed (drops, coins, XP, bits). */
    void collectTrap(Player p, GardenData d, GardenData.TrapState t) {
        int n = 0;
        for (String pestId : new ArrayList<>(t.caught)) {
            GardenTables.Pest pest = G.T.pest(pestId);
            if (pest == null) continue;
            MobDef def = Sky.mobs().def(pest.mob);
            if (def != null) {
                Sky.loot().roll(p, def.drops, LootSource.GARDEN, Sky.stats().get(p, Stat.MAGIC_FIND));
                if (def.coins > 0) Sky.economy().deposit(p, def.coins, "garden: trapped " + pestId);
            }
            reward(p, pestId, null);
            pestHarvest(p, pestId, def);
            n++;
        }
        t.caught.clear();
        if (n > 0) Msg.success(p, "You emptied the trap: &a" + n + " &7pest" + (n == 1 ? "" : "s") + ".");
    }
}
