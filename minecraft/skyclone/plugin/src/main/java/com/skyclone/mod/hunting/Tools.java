package com.skyclone.mod.hunting;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
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
 * The hunting tools: Lassos (Forest critters: hold them until their stamina runs out, reel on cue), Fishing Nets
 * (Water critters: Pull against the critter's weight), Pocket Black Holes (Combat: monsters below 10% HP are
 * pulled in and captured), Huntaxes (cap each hit so a monster lives long enough to be captured), Huntraps
 * (set and forget), Critter Capsules (the Safari) - plus shard items to the Hunting Box, Salts, Charm drops
 * and Water Shards from fishing.
 */
final class Tools implements Listener {

    private static NamespacedKey CAPSULE;
    private static final Set<UUID> CAPTURED = new HashSet<>();

    static void register() {
        CAPSULE = new NamespacedKey(Sky.plugin(), "hunting_capsule");
        Sky.abilities().register("hunting_lasso", u -> Lasso.use(u.player(), u.item()));
        Sky.abilities().register("hunting_lasso_reel", u -> false);
        Sky.abilities().register("hunting_net", u -> net(u.player(), u.item()));
        Sky.abilities().register("hunting_black_hole", u -> blackHole(u.player(), u.item()));
        Sky.abilities().register("hunting_huntaxe_absorb", u -> { HuntingMenus.huntaxe(u.player(), Sky.items().idOf(u.item())).open(u.player()); return false; });
        Sky.abilities().register("hunting_huntaxe_cap", u -> false);
        Sky.abilities().register("hunting_trap", u -> trap(u.player(), u.item()));
        Sky.abilities().register("hunting_capsule", u -> capsule(u.player(), u.item()));
        Bukkit.getPluginManager().registerEvents(new Tools(), Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Tools::tickHoles, 20L, 5L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Tools::tickTraps, 100L, 100L);
        Lasso.start();
    }

    // ================================================================= nets

    static boolean net(Player p, ItemStack item) {
        JsonObject x = Hunt.toolExtra(Sky.items().idOf(item));
        double dist = Hunt.num(x, "distance", 0);
        double range = 7 * (1 + dist / 100.0);
        LivingEntity t = target(p, range, "net");
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        for (int i = 1; i <= (int) range; i += 1) {
            Location at = eye.clone().add(dir.clone().multiply(i));
            p.getWorld().spawnParticle(Particle.SPLASH, at, 3, 0.2, 0.2, 0.2, 0);
        }
        Msg.sound(p, Sound.ENTITY_FISHING_BOBBER_THROW, 1.2f);
        if (t == null) return true;
        MobDef d = Sky.mobs().defOf(t);
        ShardDef s = Hunt.cat.byCritter(d.id);
        if (s == null) return true;
        double need = d.extra.has("pull") ? d.extra.get("pull").getAsDouble() : 5;
        double pull = Hunt.pull(p, Hunt.num(x, "pull", 5));
        double prog = Net.progress(p, t.getUniqueId()) + HuntMath.netProgress(pull, need);
        if (prog >= 1) {
            Net.clear(p);
            catchCritter(p, t, s, "net", Hunt.num(x, "hf", 0));
        } else {
            Net.set(p, t.getUniqueId(), prog);
            t.getWorld().spawnParticle(Particle.BUBBLE, t.getLocation(), 12, 0.3, 0.3, 0.3, 0.1);
            Sky.vitals().flash(p, "&bNet " + Text.bar(prog, 10) + " &7(Pull &a" + HuntMath.fmt(pull) + "&7/&c" + HuntMath.fmt(need) + "&7)", 30);
        }
        return true;
    }

    /** Per-player net progress on one critter (several throws for a heavy one). */
    static final class Net {
        private static final Map<UUID, UUID> TARGET = new HashMap<>();
        private static final Map<UUID, Double> PROG = new HashMap<>();
        static double progress(Player p, UUID target) {
            if (!target.equals(TARGET.get(p.getUniqueId()))) return 0;
            return PROG.getOrDefault(p.getUniqueId(), 0.0);
        }
        static void set(Player p, UUID t, double v) { TARGET.put(p.getUniqueId(), t); PROG.put(p.getUniqueId(), v); }
        static void clear(Player p) { TARGET.remove(p.getUniqueId()); PROG.remove(p.getUniqueId()); }
    }

    /** The critter you are looking at (ray, then a 1-block-wide cone), whose capture method matches. */
    static LivingEntity target(Player p, double range, String method) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceEntities(eye, eye.getDirection(), range, 0.9,
                e -> e instanceof LivingEntity le && e != p && Critters.isCritter(le) && method(le, method));
        if (r != null && r.getHitEntity() instanceof LivingEntity le) return le;
        return null;
    }

    private static boolean method(LivingEntity le, String method) {
        if (method == null) return true;
        MobDef d = Sky.mobs().defOf(le);
        return d != null && d.extra.has("method") && method.equals(d.extra.get("method").getAsString());
    }

    /** A critter is caught: it leaves, the player gets its shard(s). */
    static void catchCritter(Player p, LivingEntity t, ShardDef s, String how, double toolHf) {
        boolean sparkle = Critters.sparkling(t);
        Location at = t.getLocation();
        Critters.forget(t.getUniqueId());
        Sky.mobs().remove(t);
        at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.add(0, 0.5, 0), 20, 0.4, 0.4, 0.4, 0);
        int mult = sparkle ? (int) Math.round(HDials.SPARKLING_MULT.get()) : 1;
        Hunt.capture(p, s, how, toolHf, mult);
        if (sparkle) Safari.sparkled(p);
    }

    // ================================================================= black holes

    private static final class Hole {
        final UUID owner;
        final Location at;
        final long ends;
        final double radius;
        final double hf;
        final ItemDisplay display;
        final Map<UUID, Integer> held = new HashMap<>();
        Hole(UUID owner, Location at, long ends, double radius, double hf, ItemDisplay display) {
            this.owner = owner; this.at = at; this.ends = ends; this.radius = radius; this.hf = hf; this.display = display;
        }
    }

    private static final List<Hole> HOLES = new ArrayList<>();

    static boolean blackHole(Player p, ItemStack item) {
        JsonObject x = Hunt.toolExtra(Sky.items().idOf(item));
        HOLES.removeIf(h -> {
            if (h.owner.equals(p.getUniqueId())) { if (h.display != null) h.display.remove(); return true; }
            return false;
        });
        Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2)).add(0, 1.2, 0);
        ItemDisplay disp = null;
        try {
            disp = at.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setItemStack(Sky.items().create(Sky.items().idOf(item)));
                d.setPersistent(false);
                d.setGlowing(true);
            });
        } catch (RuntimeException ignored) { }
        long ends = System.currentTimeMillis() + (long) (Hunt.num(x, "seconds", 180) * 1000);
        HOLES.add(new Hole(p.getUniqueId(), at, ends, Hunt.num(x, "radius", 6), Hunt.num(x, "hf", 0), disp));
        Msg.send(p, "&5You placed a Black Hole! &7Monsters below &c" + HuntMath.fmt(HDials.BLACKHOLE_HP.get()) + "% &7HP near it can be captured.");
        Msg.sound(p, Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.6f);
        return true;
    }

    private static void tickHoles() {
        long now = System.currentTimeMillis();
        for (Iterator<Hole> it = HOLES.iterator(); it.hasNext(); ) {
            Hole h = it.next();
            Player owner = Bukkit.getPlayer(h.owner);
            if (owner == null || now > h.ends || h.at.getWorld() == null || !owner.getWorld().equals(h.at.getWorld())) {
                if (h.display != null) h.display.remove();
                it.remove();
                continue;
            }
            h.at.getWorld().spawnParticle(Particle.PORTAL, h.at, 20, 0.6, 0.6, 0.6, 0.6);
            double pressure = Hunt.snapshot(owner).value("hunters_pressure");
            int needTicks = (int) Math.max(1, Math.round(HDials.BLACKHOLE_SECONDS.get() * 4 / (1 + pressure / 100.0)));
            for (Entity e : h.at.getWorld().getNearbyEntities(h.at, h.radius, h.radius, h.radius)) {
                if (!(e instanceof LivingEntity le) || e instanceof Player || !le.isValid() || !Sky.mobs().isSkyMob(le)) continue;
                UUID own = Sky.mobs().owner(le);
                if (own != null && !own.equals(h.owner)) continue;
                MobDef d = Sky.mobs().defOf(le);
                ShardDef s = combatShard(d);
                if (s == null) continue;
                double max = Sky.mobs().maxHealth(le);
                if (max <= 0 || Sky.mobs().health(le) > max * HDials.BLACKHOLE_HP.get() / 100.0) continue;
                Vector pullv = h.at.toVector().subtract(le.getLocation().toVector());
                if (pullv.lengthSquared() > 0.01) le.setVelocity(pullv.normalize().multiply(0.35));
                int t = h.held.merge(le.getUniqueId(), 1, Integer::sum);
                if (t >= needTicks) {
                    h.held.remove(le.getUniqueId());
                    double hf = h.hf + Hunt.snapshot(owner).value("hunters_grasp");
                    boolean critter = d.extra != null && d.extra.has("critter");
                    if (critter) catchCritter(owner, le, s, "black hole", hf);
                    else {
                        CAPTURED.add(le.getUniqueId());
                        Hunt.capture(owner, s, "black hole", hf, 1);
                        Sky.mobs().setHealth(le, 0);
                    }
                    h.at.getWorld().spawnParticle(Particle.REVERSE_PORTAL, h.at, 60, 0.3, 0.3, 0.3, 0.3);
                }
            }
        }
    }

    /** The shard a monster yields to a Black Hole or a Charm drop (several shards may name one mob). */
    static ShardDef combatShard(MobDef d) {
        if (d == null) return null;
        ShardDef c = Hunt.cat.byCritter(d.id);
        if (c != null) return c;
        List<ShardDef> l = Hunt.cat.byMob(d.id);
        if (l.isEmpty()) return null;
        return l.get(ThreadLocalRandom.current().nextInt(l.size()));
    }

    // ================================================================= huntaxes

    static String heldTool(Player p, String tool) {
        ItemStack h = p.getInventory().getItemInMainHand();
        String id = h.getType().isAir() ? null : Sky.items().idOf(h);
        return tool.equals(Hunt.tool(id)) ? id : null;
    }

    /** Vis Temperata: each strike is capped at cap% of max HP and can never be the killing blow. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Player p = ctx.playerAttacker();
        if (p == null || !(ctx.victim instanceof LivingEntity v) || v instanceof Player || !Sky.mobs().isSkyMob(v)) return;
        String axe = heldTool(p, "huntaxe");
        if (axe == null || ctx.kind == DamageContext.Kind.MAGIC) return;
        double cap = Hunt.num(Hunt.toolExtra(axe), "cap", 15) / 100.0;
        double max = Sky.mobs().maxHealth(v), hp = Sky.mobs().health(v);
        double limit = Math.min(max * cap, hp - 1);
        if (limit <= 0) { e.setCancelled(true); return; }
        if (ctx.finalDamage > limit) ctx.finalDamage = limit;
    }

    /** Absorptio: the huntaxe borrows absorb% of an attuned weapon's damage... */
    static void huntaxeDamage(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null || !Hunt.ready(p)) return;
        String axe = heldTool(p, "huntaxe");
        if (axe == null) return;
        ItemDef w = AttributeEffects.def(Hunt.data(p).huntaxeWeapon.get(axe));
        if (w == null) return;
        double pct = Hunt.num(Hunt.toolExtra(axe), "absorb", 5) / 100.0 * (1 + Hunt.snapshot(p).value("hunters_fangs") / 100.0);
        ctx.base += w.damage * pct;
    }

    /** ...and its stats. */
    static void huntaxeStats(Player p, StatMap m) {
        String axe = heldTool(p, "huntaxe");
        if (axe == null) return;
        ItemDef w = AttributeEffects.def(Hunt.data(p).huntaxeWeapon.get(axe));
        if (w == null || w.stats == null) return;
        double pct = Hunt.num(Hunt.toolExtra(axe), "absorb", 5) / 100.0 * (1 + Hunt.snapshot(p).value("hunters_fangs") / 100.0);
        for (Map.Entry<Stat, Double> e : w.stats.entrySet()) if (e.getValue() != null) m.add(e.getKey(), e.getValue() * pct);
    }

    // ================================================================= huntraps

    static boolean trap(Player p, ItemStack item) {
        if (!Hunt.ready(p)) return false;
        HuntingData d = Hunt.data(p);
        if (d.traps.size() >= HDials.TRAP_MAX.get()) {
            Msg.error(p, "&cYou already have " + d.traps.size() + " Huntraps set. Collect one first (/hunting traps).");
            return false;
        }
        List<ShardCatalog.Habitat> hs = Critters.habitatsAt(p.getLocation(), 12);
        hs.removeIf(h -> h.safari || h.critters.isEmpty());
        if (hs.isEmpty()) {
            Msg.error(p, "&cHuntraps must be set where Shard creatures live. &7See /hunting habitats.");
            return false;
        }
        ShardCatalog.Habitat h = hs.get(ThreadLocalRandom.current().nextInt(hs.size()));
        ShardDef s = Critters.randomShard(h, null);
        if (s == null) return false;
        String id = Sky.items().idOf(item);
        double red = Hunt.num(Hunt.toolExtra(id), "reduction", 0);
        double attr = "Forest".equals(s.alignment) ? Hunt.snapshot(p).value("forest_trap")
                : "Combat".equals(s.alignment) ? Hunt.snapshot(p).value("combat_trap") : 0;
        long ms = (long) (HDials.TRAP_MINUTES.get() * 60_000 * (1 - red / 100.0) / (1 + attr / 100.0));
        HuntingData.Trap t = new HuntingData.Trap();
        t.item = id; t.habitat = h.id; t.world = p.getWorld().getName();
        t.x = p.getLocation().getX(); t.y = p.getLocation().getY(); t.z = p.getLocation().getZ();
        t.placedAt = System.currentTimeMillis(); t.readyAt = t.placedAt + ms; t.shard = s.id;
        if (!takeOne(p, id)) return false;
        d.traps.add(t);
        Sky.profiles().saveNow(p);
        Msg.send(p, "&aYou set a &f" + Text.strip(Sky.items().def(id).name) + " &ain &e" + h.name + "&a. It will catch something in &e"
                + Text.duration(ms / 1000) + "&a. &7(/hunting traps)");
        Msg.sound(p, Sound.BLOCK_TRIPWIRE_ATTACH, 1f);
        return false;   // no ability cooldown/mana; the item was consumed
    }

    private static final Set<String> TRAP_TOLD = new HashSet<>();

    private static void tickTraps() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Hunt.ready(p)) continue;
            for (HuntingData.Trap t : Hunt.data(p).traps) {
                String key = p.getUniqueId() + ":" + t.placedAt;
                if (t.readyAt <= now && TRAP_TOLD.add(key))
                    Msg.send(p, "&a&lHUNTRAP! &7Your trap in &e" + habitatName(t.habitat) + " &7caught something. &e/hunting traps");
            }
        }
    }

    static String habitatName(String id) {
        ShardCatalog.Habitat h = Hunt.cat.habitat(id);
        return h == null ? id : h.name;
    }

    /** Collect a ready trap: its shard is captured and the trap comes back. */
    static boolean collectTrap(Player p, int index) {
        HuntingData d = Hunt.data(p);
        if (index < 0 || index >= d.traps.size()) return false;
        HuntingData.Trap t = d.traps.get(index);
        boolean ready = t.readyAt <= System.currentTimeMillis();
        d.traps.remove(index);
        if (t.item != null && Sky.items().exists(t.item)) Sky.loot().give(p, t.item, 1, com.skyclone.api.loot.LootSource.REWARD);
        if (ready) {
            ShardDef s = Hunt.cat.get(t.shard);
            if (s != null) Hunt.capture(p, s, "huntrap", 0, 1);
        } else {
            Msg.send(p, "&eYou picked your trap up early: it caught nothing.");
        }
        Sky.profiles().saveNow(p);
        return true;
    }

    // ================================================================= capsules (Safari)

    static boolean capsule(Player p, ItemStack item) {
        if (!Safari.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) {
            Msg.error(p, "&cCritter Capsules only work inside the Critter Safari.");
            return false;
        }
        boolean certain = Hunt.num(Hunt.toolExtra(Sky.items().idOf(item)), "certain", 0) > 0
                || Boolean.TRUE.equals(bool(Hunt.toolExtra(Sky.items().idOf(item)), "certain"));
        if (!takeOne(p, Sky.items().idOf(item))) return false;
        Snowball ball = p.launchProjectile(Snowball.class);
        ball.getPersistentDataContainer().set(CAPSULE, PersistentDataType.BYTE, (byte) (certain ? 2 : 1));
        try { ball.setItem(Sky.items().create(certain ? "MASTERFUL_CRITTER_CAPSULE" : "CRITTER_CAPSULE")); } catch (RuntimeException ignored) { }
        Msg.sound(p, Sound.ENTITY_SNOWBALL_THROW, 1f);
        return false;
    }

    /** Take one of {@code id} from the main hand (the live stack, not the ability's snapshot). */
    static boolean takeOne(Player p, String id) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || id == null || !id.equals(Sky.items().idOf(hand))) return false;
        hand.setAmount(hand.getAmount() - 1);
        return true;
    }

    private static Boolean bool(JsonObject o, String k) {
        try { return o != null && o.has(k) ? o.get(k).getAsBoolean() : null; } catch (RuntimeException e) { return null; }
    }

    @EventHandler
    public void onCapsule(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        Byte tag = pr.getPersistentDataContainer().get(CAPSULE, PersistentDataType.BYTE);
        if (tag == null || !(pr.getShooter() instanceof Player p)) return;
        pr.remove();
        if (!(e.getHitEntity() instanceof LivingEntity le) || !Critters.isCritter(le)) {
            pr.getWorld().spawnParticle(Particle.ITEM_SNOWBALL, pr.getLocation(), 8);
            return;
        }
        ShardDef s = Hunt.cat.byCritter(Sky.mobs().defOf(le).id);
        if (s == null) return;
        boolean ok = tag == 2 || ThreadLocalRandom.current().nextDouble() < HuntMath.capsuleChance(s.rarity);
        if (!ok) {
            Msg.send(p, "&cThe " + s.name + " broke out of the capsule!");
            le.getWorld().spawnParticle(Particle.CLOUD, le.getLocation(), 10, 0.3, 0.3, 0.3, 0.05);
            return;
        }
        Safari.capsuleCatch(p, le, s);
    }

    // ================================================================= items: box, salts

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return;
        String id = Sky.items().idOf(hand);
        if (id == null) return;
        Action a = e.getAction();
        if ((a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) && Hunt.shardOfItem(id) != null) {
            e.setCancelled(true);
            Sky.abilities().consumeClick(p);
            if (!Hunt.ready(p)) return;
            int n = Hunt.storeFromInventory(p, id, p.isSneaking() ? Integer.MAX_VALUE : hand.getAmount());
            if (n > 0) {
                ShardDef s = Hunt.cat.get(id);
                Msg.send(p, "&aSent &f" + n + "x " + Hunt.rarityColor(s.rarity) + s.name + " Shard &ato your &aHunting Box&a.");
                Msg.sound(p, Sound.ENTITY_ITEM_PICKUP, 1.4f);
            }
            return;
        }
        if ((a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) && Salts.get(id) != null) {
            if (Salts.consume(p, hand)) e.setCancelled(true);
        }
    }

    // ================================================================= charm drops + fishing

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        LivingEntity mob = e.mob;
        Critters.forget(mob.getUniqueId());
        if (CAPTURED.remove(mob.getUniqueId())) return;
        Player p = e.killer;
        if (p == null || !Hunt.ready(p) || e.def == null) return;
        ShardDef s = combatShard(e.def);
        if (s == null) return;
        boolean sea = e.def.has("sea_creature");
        double charm = Hunt.charm(p, Sky.worlds().islandOf(mob.getWorld()), sea) * HDials.CHARM_SCALE.get();
        if (ThreadLocalRandom.current().nextDouble() * 100 < charm) Hunt.capture(p, s, "charm", 0, 1);
    }

    @EventHandler
    public void onFish(SkyEvents.FishCaught e) {
        Player p = e.player;
        if (p == null || e.seaCreature || !Hunt.ready(p)) return;
        if (ThreadLocalRandom.current().nextDouble() >= HDials.FISH_SHARD_CHANCE.get()) return;
        String island = Sky.worlds().islandOf(p.getWorld());
        List<ShardCatalog.Habitat> water = new ArrayList<>();
        for (ShardCatalog.Habitat h : Hunt.cat.habitats()) if ("water".equals(h.kind) && h.island.equals(island)) water.add(h);
        if (water.isEmpty()) return;
        ShardDef s = Critters.randomShard(water.get(ThreadLocalRandom.current().nextInt(water.size())), null);
        if (s != null) Hunt.capture(p, s, "fishing", 0, 1);
    }

    static void forget(UUID id) {
        Net.TARGET.remove(id);
        Net.PROG.remove(id);
        HOLES.removeIf(h -> { if (h.owner.equals(id)) { if (h.display != null) h.display.remove(); return true; } return false; });
    }

    static int activeHoles() { return HOLES.size(); }

    static void particlesLine(Location a, Location b, Color c) {
        if (a.getWorld() == null || !a.getWorld().equals(b.getWorld())) return;
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.1) return;
        d.normalize();
        Particle.DustOptions dust = new Particle.DustOptions(c, 0.8f);
        for (double t = 0; t < len; t += 0.5) a.getWorld().spawnParticle(Particle.DUST, a.clone().add(d.clone().multiply(t)), 1, 0, 0, 0, 0, dust);
    }

    /** A lasso is a LEAD: never let vanilla tie it to the critter you are roping. */
    @EventHandler(ignoreCancelled = true)
    public void onLeash(org.bukkit.event.entity.PlayerLeashEntityEvent e) {
        ItemStack hand = e.getPlayer().getInventory().getItemInMainHand();
        if (!hand.getType().isAir() && Hunt.tool(Sky.items().idOf(hand)) != null) e.setCancelled(true);
        else if (e.getEntity() instanceof LivingEntity le && Critters.isCritter(le)) e.setCancelled(true);
    }
}
