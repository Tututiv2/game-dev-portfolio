package com.skyclone.mod.enchanting;

import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wave 3 enchants that had data but no effect:
 * <ul>
 *   <li><b>Piercing</b> — arrows from the bow pass through enemies (vanilla pierce level), and every target after
 *       the first takes the enchant's % of the damage (a DamageModifier keyed off the arrow's hit count).</li>
 *   <li><b>Dragon Seeker</b> (aiming) — arrows curve toward a dragon within the enchant's radius.</li>
 *   <li><b>Frost Walker</b> — standing on solid ground at the edge of still water freezes a disc of it into
 *       Frosted Ice (radius 2 + level, vanilla); every block we froze is tracked and turned back into water
 *       after a dial's worth of seconds, so a frozen pond can never outlive the walk (vanilla frosted ice only
 *       melts in light, and the hub is dark at night).</li>
 *   <li><b>Rainbow</b> — wool from a sheared sheep comes out in random colours and the sheep takes the colour.</li>
 *   <li><b>The One</b> — Health and Strength per maxed collection (reads the collections module's shipped
 *       data for each collection's top tier, and {@code Features.Collections.tier}).</li>
 *   <li><b>Use-levelling sweep</b> — counters other modules bump on the item directly (dungeons adds Hecatomb
 *       S-runs to the helmet) are turned into levels here every few seconds.</li>
 * </ul>
 */
final class ExtraEnchants implements Listener {

    /** Piercing arrows in flight: arrow uuid -> victims hit so far. */
    private static final Map<UUID, int[]> PIERCING = new HashMap<>();
    /** Arrow uuid -> {expiry ms}. Dragon Seeker arrows and the radius they home in. */
    private static final Map<UUID, Homing> HOMING = new HashMap<>();
    /** attacker uuid -> server tick of a pierced (non-first) arrow hit being routed right now. */
    private static final Map<UUID, Integer> PIERCED_HIT = new HashMap<>();
    /** Frosted blocks we placed: block key -> melt-at ms. */
    private static final Map<Block, Long> FROZEN = new LinkedHashMap<>();
    /** The One: maxed-collection count cache per player {count, until ms}. */
    private static final Map<UUID, long[]> MAXED = new HashMap<>();
    private static Map<String, Integer> collectionTops;

    private record Homing(AbstractArrow arrow, double radius, long until) {}

    private static int task = -1;

    void register() {
        Sky.combat().addModifier("enchanting_piercing", 100, ExtraEnchants::piercingCut);
        Sky.stats().addProvider("enchanting_extra", ExtraEnchants::stats);
        task = Bukkit.getScheduler().scheduleSyncRepeatingTask(Sky.plugin(), ExtraEnchants::tick, 1L, 1L);
    }

    static void shutdown() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        task = -1;
        for (Block b : new ArrayList<>(FROZEN.keySet())) thaw(b);
        FROZEN.clear();
        PIERCING.clear();
        HOMING.clear();
        PIERCED_HIT.clear();
        MAXED.clear();
        CHIMERA.clear();
    }

    private static boolean on() { return EnchantingModule.dials().effects.get(); }

    // ---- bows: Piercing + Dragon Seeker --------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player) || !(e.getProjectile() instanceof AbstractArrow arrow) || !on()) return;
        ItemStack bow = e.getBow();
        Map<String, Integer> ench = Gear.enchants(bow);
        if (ench.isEmpty()) return;
        long now = System.currentTimeMillis();
        int pierce = ench.getOrDefault("piercing", 0);
        if (pierce > 0) {
            arrow.setPierceLevel(Math.max(arrow.getPierceLevel(), Math.min(127, EnchantingModule.dials().piercingTargets.get())));
            if (PIERCING.size() > 1024) PIERCING.clear();
            PIERCING.put(arrow.getUniqueId(), new int[]{0});
        }
        int aim = ench.getOrDefault("aiming", 0);
        EnchantSpec s = EnchantingModule.reg().spec("aiming");
        if (aim > 0 && s != null && s.v(aim) > 0) {
            if (HOMING.size() > 512) HOMING.clear();
            HOMING.put(arrow.getUniqueId(), new Homing(arrow, s.v(aim), now + 10_000));
        }
    }

    /** Runs before core turns the vanilla hit into a SkyBlock hit (core listens at HIGH). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArrowHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof AbstractArrow a)) return;
        int[] n = PIERCING.get(a.getUniqueId());
        if (n == null) return;
        n[0]++;
        if (n[0] > 1 && a.getShooter() instanceof Player p) PIERCED_HIT.put(p.getUniqueId(), Bukkit.getCurrentTick());
    }

    private static void piercingCut(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null || ctx.kind != DamageContext.Kind.PROJECTILE || PIERCED_HIT.isEmpty()) return;
        Integer tick = PIERCED_HIT.remove(p.getUniqueId());
        if (tick == null || tick != Bukkit.getCurrentTick()) return;
        EnchantSpec s = EnchantingModule.reg().spec("piercing");
        double pct = s == null ? 25 : s.v(1);
        ctx.multiplicative *= EnchantMath.piercingShare(2, pct);
    }

    private static void homeAll() {
        if (HOMING.isEmpty()) return;
        long now = System.currentTimeMillis();
        double turn = Math.max(0.05, Math.min(1.0, EnchantingModule.dials().dragonSeekerTurn.get()));
        for (Iterator<Homing> it = HOMING.values().iterator(); it.hasNext(); ) {
            Homing h = it.next();
            AbstractArrow a = h.arrow();
            if (!a.isValid() || now > h.until() || a.isInBlock() || a.isOnGround()) { it.remove(); continue; }
            LivingEntity best = null;
            double bestD = Double.MAX_VALUE;
            Vector vel = a.getVelocity();
            for (Entity x : a.getNearbyEntities(h.radius(), h.radius(), h.radius())) {
                LivingEntity le = x instanceof ComplexEntityPart part ? part.getParent() : (x instanceof LivingEntity l ? l : null);
                if (le == null || le instanceof Player || !isDragon(le)) continue;
                Vector to = centre(le).toVector().subtract(a.getLocation().toVector());
                double d = to.lengthSquared();
                if (d > h.radius() * h.radius() || to.dot(vel) <= 0) continue;
                if (d < bestD) { bestD = d; best = le; }
            }
            if (best == null) continue;
            double speed = Math.max(1.2, vel.length());
            Vector to = centre(best).toVector().subtract(a.getLocation().toVector()).normalize();
            a.setVelocity(vel.normalize().multiply(1 - turn).add(to.multiply(turn)).normalize().multiply(speed));
            if ((now / 50) % 3 == 0) a.getWorld().spawnParticle(Particle.DRAGON_BREATH, a.getLocation(), 1, 0, 0, 0, 0);
        }
    }

    private static Location centre(LivingEntity le) { return le.getLocation().add(0, le.getHeight() / 2, 0); }

    /** A dragon: the vanilla entity, or any sky mob whose id/family/flags say so. */
    static boolean isDragon(LivingEntity le) {
        if (le instanceof EnderDragon) return true;
        MobDef md = Sky.mobs().defOf(le);
        if (md == null) return false;
        if (md.flags != null && md.flags.contains("dragon")) return true;
        if (md.family != null && md.family.toLowerCase(Locale.ROOT).contains("dragon")) return true;
        return md.id != null && md.id.toUpperCase(Locale.ROOT).contains("DRAGON");
    }

    // ---- Frost Walker ----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        Location from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ() && to.getBlockY() == from.getBlockY()) return;
        Player p = e.getPlayer();
        if (!on() || !p.isOnGround() || p.isFlying() || p.isInsideVehicle()) return;
        int lvl = Gear.wornMax(p, "frost_walker");   // cached worn maps: no item parse per step
        if (lvl <= 0 || !frostAllowed(to)) return;
        freeze(to, Math.min(16, 2 + lvl));
    }

    /**
     * Instanced content keeps its water: a dungeon's water puzzle, Kuudra's lava/water arena and the Rift are
     * built around it, so Frost Walker never freezes there (dial-free on purpose: a frozen puzzle is a bypass).
     */
    static boolean frostAllowed(Location at) {
        String island;
        try { island = Sky.worlds().islandOf(at.getWorld()); } catch (RuntimeException e) { return false; }
        if (island == null) return true;
        String i = island.toLowerCase(Locale.ROOT);
        return !(i.contains("dungeon") || i.contains("catacomb") || i.contains("kuudra") || i.contains("rift"));
    }

    /** Vanilla FrostWalkerEnchantment: still water under air, in a disc around the feet, one layer down. */
    private static void freeze(Location at, int radius) {
        long melt = System.currentTimeMillis() + Math.max(1, EnchantingModule.dials().frostWalkerSeconds.get()) * 1000L;
        Block centre = at.getBlock().getRelative(0, -1, 0);
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > r2) continue;
                Block b = centre.getRelative(dx, 0, dz);
                if (b.getType() == Material.FROSTED_ICE && FROZEN.containsKey(b)) { FROZEN.put(b, melt); continue; }
                if (b.getType() != Material.WATER || !b.getRelative(0, 1, 0).getType().isAir()) continue;
                BlockData bd = b.getBlockData();
                if (!(bd instanceof Levelled lv) || lv.getLevel() != 0) continue;
                if (FROZEN.size() > 20_000) return;   // a runaway: never track more than this
                b.setType(Material.FROSTED_ICE, false);
                FROZEN.put(b, melt);
            }
        }
    }

    private static void meltDue() {
        if (FROZEN.isEmpty()) return;
        long now = System.currentTimeMillis();
        int budget = 400;
        for (Iterator<Map.Entry<Block, Long>> it = FROZEN.entrySet().iterator(); it.hasNext() && budget > 0; ) {
            Map.Entry<Block, Long> en = it.next();
            if (en.getValue() > now) continue;
            Block b = en.getKey();
            if (standingOn(b)) { en.setValue(now + 1000); continue; }
            it.remove();
            thaw(b);
            budget--;
        }
    }

    private static boolean standingOn(Block b) {
        Location c = b.getLocation().add(0.5, 1.0, 0.5);
        for (Entity x : b.getWorld().getNearbyEntities(c, 0.9, 0.6, 0.9)) if (x instanceof Player) return true;
        return false;
    }

    private static void thaw(Block b) {
        if (b.getType() == Material.FROSTED_ICE) b.setType(Material.WATER, true);
    }

    // ---- Rainbow -----------------------------------------------------------------------------------------

    private static final DyeColor[] COLOURS = DyeColor.values();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        if (!on() || !(e.getEntity() instanceof Sheep sheep)) return;
        if (Gear.level(e.getItem(), "rainbow") <= 0) return;
        List<ItemStack> drops = new ArrayList<>(e.getDrops());
        DyeColor last = null;
        for (int i = 0; i < drops.size(); i++) {
            ItemStack d = drops.get(i);
            if (d == null || !Tag.WOOL.isTagged(d.getType())) continue;
            last = COLOURS[ThreadLocalRandom.current().nextInt(COLOURS.length)];
            Material m = Material.matchMaterial(last.name() + "_WOOL");
            if (m != null) drops.set(i, new ItemStack(m, d.getAmount()));
        }
        if (last == null) return;
        e.setDrops(drops);
        sheep.setColor(last);
        sheep.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, sheep.getLocation().add(0, 1, 0), 8, 0.4, 0.4, 0.4, 0);
    }

    // ---- Crop Fever (hoe ultimate) ------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGather(SkyEvents.Gather e) {
        if (e.kind != LootSource.FARMING || !on() || !EnchantingModule.ready(e.player)) return;
        Player p = e.player;
        UUID u = p.getUniqueId();
        if (Buffs.active(u, "crop_fever")) {
            // the fever's extra rolls: a chance per crop broken for its enchanted form
            if (e.what != null && ThreadLocalRandom.current().nextDouble() < EnchantingModule.dials().cropFeverRoll.get()) {
                String w = e.what.toUpperCase(Locale.ROOT);
                for (String cand : new String[]{"ENCHANTED_" + w, "ENCHANTED_" + w.replace("_ITEM", ""), "ENCHANTED_" + w.replace("S", "")}) {
                    if (Sky.items().exists(cand)) {
                        Sky.loot().give(p, cand, 1, LootSource.FARMING);
                        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f);
                        break;
                    }
                }
            }
            return;
        }
        int lvl = Gear.level(Gear.held(p), "ultimate_crop_fever");
        EnchantSpec s = lvl <= 0 ? null : EnchantingModule.reg().spec("ultimate_crop_fever");
        if (s == null || ThreadLocalRandom.current().nextDouble() * 100 >= s.v(lvl)) return;
        Buffs.set(u, "crop_fever", 1, 60_000);
        Sky.stats().invalidate(p);
        p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&6&lCROP FEVER!"),
                Text.c("&e+" + EnchantingModule.dials().cropFeverFortune.get().intValue() + "☘ Farming Fortune &7for 60s")));
        Msg.send(p, "&6&lCROP FEVER! &7Your crops are blooming: extra rolls and &6+"
                + EnchantingModule.dials().cropFeverFortune.get().intValue() + "☘ Farming Fortune &7for &a60s&7.");
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.4f);
    }

    // ---- stats: The One, Chimera, Lapidary's mining speed ---------------------------------------------------

    private static void stats(Player p, StatMap m) {
        if (!on()) return;
        theOne(p, m);
        if (Buffs.active(p.getUniqueId(), "crop_fever")) m.add(Stat.FARMING_FORTUNE, EnchantingModule.dials().cropFeverFortune.get());
        Map<Stat, Double> copy = CHIMERA.get(p.getUniqueId());
        if (copy != null) for (Map.Entry<Stat, Double> e : copy.entrySet()) m.add(e.getKey(), e.getValue());
        lapidary(p, m);
    }

    /** Lapidary's second number: Mining Speed while the block in front of you is a gemstone (stained glass). */
    private static void lapidary(Player p, StatMap m) {
        ItemStack held = Gear.held(p);
        int lvl = Gear.level(held, "lapidary");
        EnchantSpec s = lvl <= 0 ? null : EnchantingModule.reg().spec("lapidary");
        if (s == null) return;
        Block b;
        try { b = p.getTargetBlockExact(6); } catch (RuntimeException ex) { return; }
        if (b != null && isGemstone(b.getType())) m.add(Stat.MINING_SPEED, s.v(1, lvl));
    }

    static boolean isGemstone(Material m) { return EnchantMath.isGemstoneBlock(m.name()); }

    /** Chimera: refreshed once a second from the "pets" provider's flat stats (never from inside a provider). */
    private static final Map<UUID, Map<Stat, Double>> CHIMERA = new HashMap<>();

    private static void chimeraSweep() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            int lvl = on() && EnchantingModule.ready(p) ? Gear.level(Gear.held(p), "ultimate_chimera") : 0;
            EnchantSpec s = lvl <= 0 ? null : EnchantingModule.reg().spec("ultimate_chimera");
            Map<Stat, Double> next = new HashMap<>();
            if (s != null) {
                double pct = s.v(lvl) / 100.0;
                for (Stat st : Stat.values()) {
                    Double pet = Sky.stats().breakdown(p, st).get("pets");
                    if (pet != null && pet != 0) next.put(st, pet * pct);
                }
            }
            Map<Stat, Double> cur = CHIMERA.get(u);
            if (next.isEmpty()) { if (cur != null) { CHIMERA.remove(u); Sky.stats().invalidate(p); } continue; }
            if (!next.equals(cur)) { CHIMERA.put(u, next); Sky.stats().invalidate(p); }
        }
        CHIMERA.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    private static void theOne(Player p, StatMap m) {
        double hp = Gear.wornSum(p, "ultimate_the_one", 0);
        double str = Gear.wornSum(p, "ultimate_the_one", 1);
        if (hp <= 0 && str <= 0) return;
        int maxed = maxedCollections(p);
        if (maxed <= 0) return;
        m.add(Stat.HEALTH, hp * maxed);
        m.add(Stat.STRENGTH, str * maxed);
    }

    static int maxedCollections(Player p) {
        long now = System.currentTimeMillis();
        long[] c = MAXED.get(p.getUniqueId());
        if (c != null && c[1] > now) return (int) c[0];
        int n = 0;
        try {
            Features.Collections col = Sky.service(Features.Collections.class);
            if (col != null) for (Map.Entry<String, Integer> e : tops().entrySet()) if (col.tier(p, e.getKey()) >= e.getValue()) n++;
        } catch (RuntimeException ignored) { /* collections module absent: nothing is maxed */ }
        if (MAXED.size() > 500) MAXED.clear();
        MAXED.put(p.getUniqueId(), new long[]{n, now + 30_000});
        return n;
    }

    /** Collection id -> its top tier, read once from the collections module's shipped data. */
    static Map<String, Integer> tops() {
        if (collectionTops != null) return collectionTops;
        Map<String, Integer> out = new HashMap<>();
        try (var in = Sky.plugin().getResource("data/collections/collections.json")) {
            if (in != null) {
                Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
                out.putAll(EnchantMath.collectionTops(JsonParser.parseReader(r)));
            }
        } catch (Exception ignored) { /* unreadable: The One gives nothing rather than breaking stats */ }
        collectionTops = out;
        return out;
    }

    // ---- use-levelling sweep (counters bumped by other modules) -----------------------------------------

    private static void sweepLevels() {
        EnchantRegistry reg = EnchantingModule.reg();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!EnchantingModule.ready(p)) continue;
            for (ItemStack piece : p.getInventory().getArmorContents()) {
                if (!Gear.isSky(piece)) continue;
                ItemData d = Sky.items().data(piece);
                if (d == null || d.enchants == null || d.enchants.isEmpty()) continue;
                for (Map.Entry<String, Integer> en : d.enchants.entrySet()) {
                    EnchantSpec s = reg.spec(en.getKey());
                    if (s == null || !s.levelsByUse() || en.getValue() == null || en.getValue() >= s.maxLevel) continue;
                    int next = s.levelForCounter(StatEnchants.counter(d, en.getKey()));
                    if (next > en.getValue()) { UseLeveling.levelUp(p, piece, en.getKey(), next); break; }
                }
            }
        }
    }

    // ---- ticker ------------------------------------------------------------------------------------------

    private static long ticks;

    private static void tick() {
        ticks++;
        if (on()) homeAll();
        if (ticks % 10 == 0) {
            meltDue();
            long now = System.currentTimeMillis();
            if (PIERCING.size() > 256) PIERCING.keySet().removeIf(u -> { Entity x = Bukkit.getEntity(u); return x == null || !x.isValid(); });
            if (!PIERCED_HIT.isEmpty()) PIERCED_HIT.values().removeIf(t -> t != Bukkit.getCurrentTick());
            if (MAXED.size() > 100) MAXED.values().removeIf(c -> c[1] < now);
        }
        if (ticks % 20 == 0) chimeraSweep();
        if (ticks % 100 == 0) sweepLevels();
    }
}
