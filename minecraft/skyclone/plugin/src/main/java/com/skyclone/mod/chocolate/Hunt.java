package com.skyclone.mod.chocolate;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Layout;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hopsworth's Hunt: Chocolate Rabbit Eggs hidden on every listed island through Spring. Each meal (Breakfast 7am,
 * Brunch 10am, Lunch 2pm, Dejeuner 5pm, Dinner 9pm, Supper midnight) spawns ONE egg per island at one of that
 * island's hiding places and stays until the same meal spawns again; a player may claim each meal once per spawn,
 * on any island. Eggs are shared entities shown only to players who have not claimed that meal yet. Missed meals
 * go to the Rabbit Bounty Hunter if it has room.
 */
final class Hunt implements Listener {

    static NamespacedKey EGG_KEY;

    static final class Egg {
        final String island;
        final ChocCatalog.Meal meal;
        final long instance;
        Location loc;
        UUID display, hitbox;
        Egg(String island, ChocCatalog.Meal meal, long instance) { this.island = island; this.meal = meal; this.instance = instance; }
        String tag() { return island + "|" + meal.id + "|" + instance; }
    }

    /** A hiding place: near an anchor, y resolved on the real blocks when the egg spawns. */
    record Spot(String anchor, int x, int z, int yHint) {}

    private static final Map<String, Egg> EGGS = new LinkedHashMap<>();
    private static final Map<String, List<Spot>> SPOTS = new HashMap<>();
    private static final Map<UUID, Long> LAST_CLICK = new HashMap<>();
    private static long bootTick;

    static void init() {
        EGG_KEY = new NamespacedKey(Sky.plugin(), "choc_egg");
        bootTick = Bukkit.getCurrentTick();
        // eggs are non-persistent; a reload still clears any copy left in a loaded chunk
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(EGG_KEY, PersistentDataType.STRING)) e.remove();
            }
        }
    }

    // ---- clock ----------------------------------------------------------------------------------------------

    static boolean active() {
        try { return Sky.calendar().active(Choc.HUNT); } catch (Throwable t) { return false; }
    }

    static long nowAbs() {
        SkyDate d = Sky.calendar().now();
        return ChocMath.absMinute(d.year(), d.month(), d.day(), d.hour(), d.minute());
    }

    static List<ChocCatalog.Meal> meals() {
        List<ChocCatalog.Meal> out = new ArrayList<>();
        boolean side = Choc.sideDishes.get();
        for (ChocCatalog.Meal m : Choc.catalog.factory.meals) if (side || !m.side) out.add(m);
        return out;
    }

    static boolean islandExists(String id) {
        try { return Sky.worlds().island(id) != null; } catch (Throwable t) { return false; }
    }

    /** Islands from the dial that are registered and shared (instanced islands have no single egg spot). */
    static List<String> islands() {
        List<String> out = new ArrayList<>();
        for (String s : Choc.huntIslands.get().split(",")) {
            String id = s.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty() || out.contains(id)) continue;
            IslandDef def;
            try { def = Sky.worlds().island(id); } catch (Throwable t) { def = null; }
            if (def != null && !def.instanced) out.add(id);
        }
        return out;
    }

    static String islandName(String id) {
        try {
            IslandDef d = Sky.worlds().island(id);
            if (d != null && d.displayName != null) return d.displayName;
        } catch (Throwable ignored) { }
        return Text.titleCase(id);
    }

    static boolean claimed(ChocData d, ChocCatalog.Meal m, long instance) {
        Long a = d.eggClaims.get(m.id), b = d.eggClaimsPrev.get(m.id);
        return (a != null && a == instance) || (b != null && b == instance);
    }

    // ---- spots ----------------------------------------------------------------------------------------------

    static List<Spot> spots(String island) {
        List<Spot> cached = SPOTS.get(island);
        if (cached != null) return cached;
        Layout l;
        try { l = Sky.worlds().layout(island); } catch (Throwable t) { l = null; }
        if (l == null) return List.of();
        List<String> names = new ArrayList<>();
        for (String n : l.anchors().keySet()) if (n.startsWith("egg.")) names.add(n);
        boolean exact = names.size() >= 3;
        if (!exact) {
            names.clear();
            for (String n : l.anchors().keySet()) {
                if (n.startsWith("cam.") || n.startsWith("spawn.") || n.startsWith("mob.")) continue;
                names.add(n);
            }
        }
        names.sort(null);
        // deterministic shuffle so the 15 places are spread across the island, not the first 15 alphabetically
        names.sort((a, b) -> Integer.compare(mix(island + a), mix(island + b)));
        int want = Math.max(1, Choc.eggSpots.get());
        List<Spot> out = new ArrayList<>();
        for (String n : names) {
            if (out.size() >= want) break;
            Layout.Anchor a = l.anchor(n);
            if (a == null) continue;
            // shared islands are built at layout coordinates (core's anchor() is the same numbers in the island
            // world); reading the layout keeps this from loading a world just to plan hiding places
            int h = mix("off" + island + n);
            double ang = (h & 0xffff) / 65536.0 * Math.PI * 2;
            double r = exact ? 0 : 3 + ((h >>> 16) & 7) % 5;
            out.add(new Spot(n, (int) Math.floor(a.x() + Math.cos(ang) * r), (int) Math.floor(a.z() + Math.sin(ang) * r),
                    (int) Math.floor(a.y())));
        }
        SPOTS.put(island, out);
        return out;
    }

    static int mix(String s) {
        int h = s.hashCode();
        h ^= h >>> 16; h *= 0x85ebca6b; h ^= h >>> 13; h *= 0xc2b2ae35; h ^= h >>> 16;
        return h & 0x7fffffff;
    }

    /** The standing position for the egg, or null when its chunk is not loaded yet (retried later). */
    static Location resolve(World w, String island, ChocCatalog.Meal meal, long instance) {
        List<Spot> sp = spots(island);
        if (sp.isEmpty()) return null;
        int start = mix(island + "|" + meal.id + "|" + instance) % sp.size();
        for (int t = 0; t < sp.size(); t++) {
            Spot s = sp.get((start + t) % sp.size());
            if (!w.isChunkLoaded(s.x() >> 4, s.z() >> 4)) return null;
            Location ok = ground(w, s.x(), s.yHint(), s.z());
            if (ok != null) return ok;
        }
        return null;
    }

    static Location ground(World w, int x, int yHint, int z) {
        for (int dy = 6; dy >= -10; dy--) {
            int y = yHint + dy;
            if (y <= w.getMinHeight() || y + 2 >= w.getMaxHeight()) continue;
            Block floor = w.getBlockAt(x, y, z);
            if (!floor.getType().isSolid() || floor.isLiquid()) continue;
            Block a = floor.getRelative(0, 1, 0), b = floor.getRelative(0, 2, 0);
            if (a.isPassable() && !a.isLiquid() && b.isPassable() && !b.isLiquid()) return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }

    // ---- the egg loop (every 40 ticks) ----------------------------------------------------------------------

    static void tick() {
        if (!active()) {
            if (!EGGS.isEmpty()) clearAll();
            return;
        }
        long abs = nowAbs();
        List<ChocCatalog.Meal> meals = meals();
        java.util.Set<String> live = new java.util.HashSet<>();
        for (String island : islands()) {
            World w = Bukkit.getWorld("sb_" + island);
            boolean built;
            try { built = Sky.worlds().isBuilt(island); } catch (Throwable t) { built = false; }
            if (w == null || !built) continue;
            for (ChocCatalog.Meal m : meals) {
                long inst = ChocMath.mealInstance(abs, m.hour);
                String key = island + "|" + m.id;
                live.add(key);
                Egg e = EGGS.get(key);
                if (e != null && e.instance != inst) { despawn(e); e = null; }
                if (e == null) { e = new Egg(island, m, inst); EGGS.put(key, e); }
                if (e.loc == null) e.loc = resolve(w, island, m, inst);
                if (e.loc == null) continue;
                ensureEntities(e, w);
                updateVisibility(e, w);
            }
        }
        EGGS.entrySet().removeIf(en -> {
            if (live.contains(en.getKey())) return false;
            despawn(en.getValue());
            return true;
        });
    }

    static void clearAll() {
        for (Egg e : EGGS.values()) despawn(e);
        EGGS.clear();
    }

    static void forgetSpots() { SPOTS.clear(); clearAll(); }

    private static Entity entity(UUID id) { return id == null ? null : Bukkit.getEntity(id); }

    static void despawn(Egg e) {
        Entity a = entity(e.display), b = entity(e.hitbox);
        if (a != null) a.remove();
        if (b != null) b.remove();
        e.display = null;
        e.hitbox = null;
    }

    static ItemStack eggHead(ChocCatalog.Meal m) {
        com.skyclone.api.item.ItemDef def = Sky.items().def("EGGLOCATOR");
        if (def != null && def.skull != null) return Icons.skull(def.skull, "&" + m.color + m.name, List.of());
        return new ItemStack(org.bukkit.Material.EGG);
    }

    static Color color(ChocCatalog.Meal m) {
        return switch (m.color == null ? "f" : m.color) {
            case "6" -> Color.ORANGE;
            case "e" -> Color.YELLOW;
            case "9" -> Color.BLUE;
            case "d" -> Color.FUCHSIA;
            case "a" -> Color.LIME;
            case "c" -> Color.RED;
            default -> Color.WHITE;
        };
    }

    static void ensureEntities(Egg e, World w) {
        if (!w.isChunkLoaded(e.loc.getBlockX() >> 4, e.loc.getBlockZ() >> 4)) return;
        Entity d = entity(e.display), h = entity(e.hitbox);
        if (d != null && d.isValid() && h != null && h.isValid()) return;
        despawn(e);
        String tag = e.tag();
        ItemDisplay disp = w.spawn(e.loc.clone().add(0, 0.3, 0), ItemDisplay.class, it -> {
            it.setPersistent(false);
            it.setVisibleByDefault(false);
            it.setItemStack(eggHead(e.meal));
            it.setBillboard(Display.Billboard.VERTICAL);
            it.setGlowing(true);
            it.setGlowColorOverride(color(e.meal));
            it.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
            it.getPersistentDataContainer().set(EGG_KEY, PersistentDataType.STRING, tag);
        });
        Interaction box = w.spawn(e.loc.clone(), Interaction.class, it -> {
            it.setPersistent(false);
            it.setVisibleByDefault(false);
            it.setInteractionWidth(0.8f);
            it.setInteractionHeight(0.8f);
            it.setResponsive(true);
            it.getPersistentDataContainer().set(EGG_KEY, PersistentDataType.STRING, tag);
        });
        e.display = disp.getUniqueId();
        e.hitbox = box.getUniqueId();
    }

    static void updateVisibility(Egg e, World w) {
        Entity d = entity(e.display), h = entity(e.hitbox);
        if (d == null || h == null) return;
        for (Player p : w.getPlayers()) {
            boolean want = Choc.ready(p) && !claimed(Choc.data(p), e.meal, e.instance);
            if (want != p.canSee(d)) {
                if (want) { p.showEntity(Sky.plugin(), d); p.showEntity(Sky.plugin(), h); }
                else { p.hideEntity(Sky.plugin(), d); p.hideEntity(Sky.plugin(), h); }
            }
        }
    }

    static Collection<Egg> eggs() { return EGGS.values(); }

    // ---- claiming -------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (handle(e.getPlayer(), e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(PrePlayerAttackEntityEvent e) {
        if (handle(e.getPlayer(), e.getAttacked())) e.setCancelled(true);
    }

    private static boolean handle(Player p, Entity clicked) {
        if (clicked == null || EGG_KEY == null) return false;
        String tag = clicked.getPersistentDataContainer().get(EGG_KEY, PersistentDataType.STRING);
        if (tag == null) return false;
        long now = System.currentTimeMillis();
        Long last = LAST_CLICK.get(p.getUniqueId());
        if (last != null && now - last < 300) return true;
        LAST_CLICK.put(p.getUniqueId(), now);
        for (Egg egg : EGGS.values()) {
            if (egg.tag().equals(tag)) { claim(p, egg); return true; }
        }
        return true;
    }

    static void claim(Player p, Egg egg) {
        if (!Choc.ready(p)) return;
        ChocData d = Choc.data(p);
        if (claimed(d, egg.meal, egg.instance)) {
            Msg.send(p, "&cYou have already collected this " + egg.meal.name + "! Try again when it respawns.");
            return;
        }
        Long prev = d.eggClaims.put(egg.meal.id, egg.instance);
        if (prev != null) d.eggClaimsPrev.put(egg.meal.id, prev);
        d.eggsFound++;
        Msg.send(p, "&d&lHOPSWORTH'S HUNT &7You found a &" + egg.meal.color + egg.meal.name + " &7in the &a" + islandName(egg.island) + "&7!");
        p.playSound(p.getLocation(), Sound.ENTITY_CHICKEN_EGG, 1f, 1.2f);
        if (egg.loc != null) p.spawnParticle(Particle.HAPPY_VILLAGER, egg.loc.clone().add(0, 0.5, 0), 12, 0.3, 0.3, 0.3, 0);
        Entity a = entity(egg.display), b = entity(egg.hitbox);
        if (a != null) p.hideEntity(Sky.plugin(), a);
        if (b != null) p.hideEntity(Sky.plugin(), b);
        openEgg(p, d, egg.island, "egg");
    }

    /** One egg's contents: a rabbit (new or duplicate) and, rarely, a Chocolate Dye. */
    static void openEgg(Player p, ChocData d, String island, String where) {
        ChocCatalog.Rabbit r = roll(p, d, island);
        Factory.grant(p, r, island != null ? island : where, false, false);
        if (ThreadLocalRandom.current().nextDouble() < Choc.dyeChance.get() && Sky.items().exists("DYE_CHOCOLATE")) {
            Sky.loot().give(p, "DYE_CHOCOLATE", 1, LootSource.EVENT);
            Msg.send(p, "&6&lWOW! &7The egg held a &5Chocolate Dye&7!");
        }
    }

    static ChocCatalog.Rabbit roll(Player p, ChocData d, String island) {
        ChocCatalog.Factory f = Choc.catalog.factory;
        double[] w = ChocMath.eggWeights(f.eggOdds, d.shrine * f.shrine.perLevel,
                Factory.attribute(p, "legendary_rabbit_luck") * 0.01,
                Factory.attribute(p, "mythic_rabbit_luck") * 0.01,
                Factory.attribute(p, "divine_rabbit_luck") * 0.01);
        ChocCatalog.Talisman t = Factory.activeTalisman(p);
        double unique = Choc.uniqueBase.get() + (t == null ? 0 : t.unique) + ChocMath.ability(Choc.catalog, d.rabbits.keySet(), "unique_chance");
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return ChocMath.rollEgg(Choc.catalog, island, Hunt::islandExists, d.rabbits.keySet(), w,
                Choc.residentChance.get(), unique, rng::nextDouble);
    }

    // ---- Bounty Hunter (missed eggs) ------------------------------------------------------------------------

    /** Count meals that ended unclaimed since the last check (also across offline time). */
    static void accrueMissed(ChocData d, long abs) {
        if (d.missedCheckedTo <= 0 || d.missedCheckedTo > abs) { d.missedCheckedTo = abs; return; }
        long from = Math.max(d.missedCheckedTo, abs - 93L * ChocMath.MIN_PER_DAY);
        boolean forced = active() && !ChocMath.springAt(abs);
        for (ChocCatalog.Meal m : meals()) {
            long a = ChocMath.mealInstance(from, m.hour), b = ChocMath.mealInstance(abs, m.hour);
            for (long j = a + 1; j <= b; j++) {
                long inst = j - 1;
                boolean during = ChocMath.springAt(ChocMath.instanceStart(inst, m.hour)) || (forced && j == b);
                if (!during || claimed(d, m, inst)) continue;
                if (d.hitmanEggs < d.hitmanSlots) d.hitmanEggs++;
            }
        }
        d.missedCheckedTo = abs;
    }

    /** Egg Finder attribute: one Bounty Hunter egg every 750 s / level during the Hunt (NEU shard lore). */
    static void eggFinder(Player p, ChocData d, double seconds) {
        double lvl = Factory.attribute(p, "egg_finder");
        if (lvl <= 0 || !active()) return;
        d.eggFinderSeconds += seconds;
        double every = Math.max(30, Choc.eggFinderInterval.get() / lvl);
        if (d.eggFinderSeconds >= every) {
            d.eggFinderSeconds = 0;
            if (d.hitmanEggs < Math.max(1, d.hitmanSlots)) {
                d.hitmanEggs++;
                Msg.send(p, "&d&lHOPSWORTH'S HUNT &7Your Egg Finder turned up an egg for the &aRabbit Bounty Hunter&7!");
            }
        }
    }

    static int claimBounty(Player p, int n) {
        ChocData d = Choc.data(p);
        int done = 0;
        while (done < n && d.hitmanEggs > 0) {
            d.hitmanEggs--;
            openEgg(p, d, null, "bounty");
            done++;
        }
        if (done == 0) Msg.send(p, "&cThe Rabbit Bounty Hunter has no eggs for you.");
        return done;
    }

    // ---- Egg Seeker -----------------------------------------------------------------------------------------

    static boolean seek(Player p) {
        if (!active()) { Msg.send(p, "&cThe Egg Seeker only works during " + Choc.HUNT_NAME + "!"); return false; }
        if (!Choc.ready(p)) return false;
        String island = Sky.worlds().islandOf(p.getLocation());
        ChocData d = Choc.data(p);
        Egg best = null;
        double bestDist = Double.MAX_VALUE;
        for (Egg e : EGGS.values()) {
            if (!e.island.equals(island) || e.loc == null || claimed(d, e.meal, e.instance)) continue;
            if (e.loc.getWorld() != p.getWorld()) continue;
            double dist = e.loc.distanceSquared(p.getLocation());
            if (dist < bestDist) { bestDist = dist; best = e; }
        }
        if (best == null) {
            Msg.send(p, "&aThere are no eggs left for you on this island right now.");
            return true;
        }
        Vector dir = best.loc.toVector().subtract(p.getEyeLocation().toVector());
        double len = dir.length();
        dir.normalize();
        Location eye = p.getEyeLocation();
        for (int i = 1; i <= 12; i++) {
            Location pt = eye.clone().add(dir.clone().multiply(i * 0.75));
            p.spawnParticle(Particle.DUST, pt, 1, 0, 0, 0, 0, new Particle.DustOptions(color(best.meal), 1.2f));
        }
        try { Sky.vitals().flash(p, "&" + best.meal.color + best.meal.name + " &7is &a" + (int) Math.round(len) + " &7blocks away", 60); }
        catch (Throwable ignored) { }
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, (float) Math.max(0.5, Math.min(2.0, 2.0 - len / 100.0)));
        return true;
    }

    // ---- events from other systems --------------------------------------------------------------------------

    static void onFish(Player p) {
        if (!active() || !Choc.ready(p)) return;
        if (ThreadLocalRandom.current().nextDouble() >= Choc.fishEggChance.get()) return;
        Msg.send(p, "&d&lHOPSWORTH'S HUNT &7You fished up a soggy &6Chocolate Egg&7!");
        openEgg(p, Choc.data(p), null, "fishing");
    }

    static void announce(boolean started) {
        if (!Choc.announce.get()) return;
        if (started && Bukkit.getCurrentTick() - bootTick < 200) return;   // the boot-time "already running" start
        String line = started
                ? "&d&lHOPSWORTH'S HUNT &7has begun! Chocolate Rabbit Eggs are hidden on the islands until the end of Spring."
                : "&d&lHOPSWORTH'S HUNT &7is over. The rabbits are back in the Factory.";
        for (Player p : Bukkit.getOnlinePlayers()) if (Choc.ready(p)) Msg.send(p, line);
    }

    /** Icon line for one meal in the sidebar / menus: found, waiting, or not today. */
    static String mealGlyph(ChocData d, ChocCatalog.Meal m, long abs) {
        long inst = ChocMath.mealInstance(abs, m.hour);
        return claimed(d, m, inst) ? "&a✔" : "&" + m.color + "●";
    }

    static ItemStack mealIcon(ChocData d, ChocCatalog.Meal m, long abs) {
        long inst = ChocMath.mealInstance(abs, m.hour);
        boolean got = claimed(d, m, inst);
        long mins = ChocMath.minutesUntilNext(abs, m.hour);
        long realSec = mins * Sky.calendar().millisPerDay() / 1000 / ChocMath.MIN_PER_DAY;
        return Icons.of(got ? org.bukkit.Material.LIME_DYE : org.bukkit.Material.EGG, "&" + m.color + m.name,
                "&7Appears every day at &e" + (m.hour == 0 ? "12am" : m.hour < 12 ? m.hour + "am" : m.hour == 12 ? "12pm" : (m.hour - 12) + "pm") + "&7.",
                "",
                got ? "&aYou found today's egg!" : "&eStill out there somewhere!",
                "&7Next one in &a" + Text.duration(realSec));
    }
}
