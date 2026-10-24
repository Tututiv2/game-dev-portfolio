package com.skyclone.mod.minions;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.minions.MinionCatalog.FuelDef;
import com.skyclone.mod.minions.MinionCatalog.TierDef;
import com.skyclone.mod.minions.MinionCatalog.TypeDef;
import com.skyclone.mod.minions.MinionCatalog.UpgradeDef;
import com.skyclone.mod.minions.MinionStore.Crystal;
import com.skyclone.mod.minions.MinionStore.Placed;
import com.skyclone.mod.minions.MinionStore.Slot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The minion system at runtime: the production tick for every loaded profile, the armour stands, and
 * every player action (place, pick up, collect, fuel, upgrades, quick upgrade). All on the main thread.
 *
 * <p>Profile data is SHARED (co-op): re-fetched with {@link #store(Player)} on every use, never cached
 * across ticks (CORE_GUIDE traps 1-2). A profile is "loaded" while at least one member is online and
 * ready; its minions are then simulated in real time. A profile nobody has loaded is frozen and catches
 * up from {@code Placed.lastUpdate} the moment a member logs in (research 02 section 4.7).
 */
final class MinionManager {

    static final String KEY = "minions";
    /** Wave 5 item ids: Free Will (registered here), the Minion Chair (crafting's furniture item), the Postcard. */
    static final String FREE_WILL = "FREE_WILL";
    static final String MINION_CHAIR = "MINION_CHAIR";
    static final String POSTCARD = "POSTCARD";
    /** {@code Placed.chair} when the minion sits on a PLACED Minion Chair furniture piece (crafting's), not an item. */
    static final String FURNITURE_SEAT = "@furniture";
    /** Crafting's chunk-PDC registry of placed furniture (read-only here: id + block positions). */
    private static final NamespacedKey FURNITURE_KEY = NamespacedKey.fromString("skyclone:furniture");

    final MinionsModule mod;
    final MinionCatalog cat;

    /** minion id -> armour stand */
    final Map<String, UUID> stands = new HashMap<>();
    /** crystal id -> armour stand */
    final Map<String, UUID> crystalStands = new HashMap<>();
    /** world -> packed positions of blocks minions placed (drop guard). */
    final Map<String, Set<Long>> visualIndex = new HashMap<>();
    /** profile id -> player to greet with the offline catch-up summary */
    final Map<String, UUID> welcome = new HashMap<>();
    private final Set<String> warnedUnknown = new HashSet<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();

    MinionManager(MinionsModule mod) {
        this.mod = mod;
        this.cat = mod.cat;
    }

    // ---- profiles -------------------------------------------------------------------------------------

    record Prof(String profileId, Player any, List<Player> online) {}

    List<Prof> loaded() {
        Map<String, List<Player>> by = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            String id = Sky.profiles().profileId(p);
            if (id == null || id.isEmpty()) continue;
            by.computeIfAbsent(id, k -> new ArrayList<>()).add(p);
        }
        List<Prof> out = new ArrayList<>(by.size());
        for (Map.Entry<String, List<Player>> e : by.entrySet()) out.add(new Prof(e.getKey(), e.getValue().get(0), e.getValue()));
        return out;
    }

    Prof prof(String profileId) {
        for (Prof p : loaded()) if (p.profileId.equals(profileId)) return p;
        return null;
    }

    MinionStore store(Player p) { return Sky.profiles().shared(p, KEY, MinionStore.class); }

    // ---- the environment a minion works in ------------------------------------------------------------

    /** Pet type -> which minions its L100 "+30%" perk speeds up (research 02 section 4.6). TUNE: level x 0.3%. */
    static boolean petBoosts(String pet, TypeDef t) {
        if (pet == null) return false;
        return switch (pet) {
            case "RABBIT" -> "FARMING".equals(t.category);
            case "OCELOT" -> "FORAGING".equals(t.category);
            case "MAGMA_CUBE" -> "SLIME".equals(t.id) || "MAGMA_CUBE".equals(t.id);
            case "SNAIL" -> "RED_SAND".equals(t.id);
            case "PIGMAN" -> "PIG".equals(t.id);
            case "SPIDER" -> "SPIDER".equals(t.id) || "CAVESPIDER".equals(t.id) || "TARANTULA".equals(t.id);
            default -> false;
        };
    }

    /** The pet whose ability {@link MinionSim#petAbilityKeys} names (for the fallback when the pets service cannot answer). */
    static String petOfAbility(String key) {
        if (key == null) return null;
        int i = key.indexOf('_');
        String head = i < 0 ? key : key.substring(0, i);
        return switch (head) {
            case "pigman" -> "PIGMAN";
            case "magma" -> "MAGMA_CUBE";
            case "spider" -> "SPIDER";
            case "snail" -> "SNAIL";
            case "rabbit" -> "RABBIT";
            case "ocelot" -> "OCELOT";
            default -> null;
        };
    }

    final class Env implements MinionSim.Env {
        final Prof prof;
        final MinionStore store;
        boolean live;
        /** Epoch ms the step being simulated starts at (a powered Beacon only counts before it runs dry). */
        long stepStart = System.currentTimeMillis();
        private final Map<String, Double> cache = new HashMap<>();

        Env(Prof prof, MinionStore store) {
            this.prof = prof;
            this.store = store;
        }

        @Override public double speedBonus(Placed m, TypeDef t) { return breakdown(m, t).values().stream().mapToDouble(Double::doubleValue).sum(); }

        /** Every source of speed, labelled, for the minion menu tooltip. */
        Map<String, Double> breakdown(Placed m, TypeDef t) {
            Map<String, Double> b = new LinkedHashMap<>();
            for (UpgradeDef u : MinionSim.installed(cat, m)) {
                if (u.appliesTo(t) && u.speed > 0) b.merge(name(u.id), u.speed, Double::sum);
            }
            if (m.infused) {
                UpgradeDef inf = cat.upgrade("MITHRIL_INFUSION");
                b.put(name("MITHRIL_INFUSION"), inf == null ? 10 : inf.speed);
            }
            if (m.freeWill) {
                UpgradeDef fw = cat.upgrade(FREE_WILL);
                b.put("Free Will", fw == null ? 10 : fw.speed);
            }
            Set<String> seen = new HashSet<>();
            for (Crystal c : store.crystals.values()) {
                if (!seen.add(c.item)) continue;
                UpgradeDef u = cat.upgrade(c.item);
                if (u == null || !u.appliesTo(t)) continue;
                if ("beacon".equals(u.slot)) {
                    // research 02 section 4.6: Beacon I-V +2..10% to every minion, only while a Power Crystal runs
                    if (stepStart < store.beaconPoweredUntil) b.put("Beacon " + Text.roman(Math.max(1, u.tier)), u.speed + (store.beaconScorched ? scorchedBonus() : 0));
                    continue;
                }
                double v = u.speed;
                if (u.perUniqueType > 0) v += u.perUniqueType * distinctPlaced(store, "FARMING");
                b.put(name(c.item), v);
            }
            double pet = petBonus(m, t);
            if (pet > 0) b.put("Pet", pet);
            if ("POTATO".equals(t.id)) {
                double acc = 0;
                for (Player p : prof.online) {
                    var a = Sky.service(Features.Accessories.class);
                    if (a.has(p, "POTATO_RING")) acc = Math.max(acc, mod.dials.potatoRing.get());
                    else if (a.has(p, "POTATO_TALISMAN")) acc = Math.max(acc, mod.dials.potatoTalisman.get());
                }
                if (acc > 0) b.put("Potato accessory", acc);
            }
            if ("MINING".equals(t.category)) {
                double cole = cached("mayor.cole", () -> Sky.service(Features.Mayors.class).perkValue("cole.prospection"));
                if (cole > 0) b.put("Mayor perk", cole);
            }
            if ("INFERNO".equals(t.id)) {
                long n = store.placed.values().stream().filter(x -> "INFERNO".equals(x.type)).count();
                double v = Math.min(mod.dials.celsiusCap.get(), n * mod.dials.celsiusPer.get());
                if (v > 0) b.put("Rising Heat", v);
            }
            double g = mod.dials.speedBonus.get();
            if (g != 0) b.put("Server bonus", g);
            return b;
        }

        /**
         * The best "minions work X% faster on your island" pet perk among the members standing on this island.
         * Offline catch-up gets none (nobody was there), which is the reference's "on your island" wording.
         */
        private double petBonus(Placed m, TypeDef t) {
            if (!live) return 0;
            List<String> keys = MinionSim.petAbilityKeys(t);
            if (keys.isEmpty()) return 0;
            double best = 0;
            var pets = Sky.service(Features.Pets.class);
            for (Player p : prof.online) {
                if (!p.getWorld().getName().equals(m.world) || !"private_island".equals(islandOf(p))) continue;
                for (String key : keys) {
                    double v;
                    try {
                        v = pets.abilityValue(p, key, "a");
                    } catch (Throwable ex) {
                        // fallback (older pets service): the matching pet's level x dial, the pre-wave-5 rule
                        String type = pets.activeType(p);
                        v = type != null && type.equals(petOfAbility(key)) && petBoosts(type, t)
                                ? pets.activeLevel(p) * mod.dials.petPerLevel.get() : 0;
                    }
                    if (Double.isFinite(v)) best = Math.max(best, v);
                }
            }
            return best;
        }

        private String islandOf(Player p) {
            try { return Sky.worlds().islandOf(p.getLocation()); } catch (Throwable e) { return null; }
        }

        private double cached(String k, java.util.function.Supplier<Double> s) {
            return cache.computeIfAbsent(k, x -> { try { return s.get(); } catch (Throwable e) { return 0.0; } });
        }

        @Override public double outputMult(Placed m, TypeDef t) {
            double o = mod.dials.outputMult.get();
            double derpy = cached("mayor.derpy", () -> Sky.service(Features.Mayors.class).perkValue("derpy.turbo_minions"));
            if (derpy > 0) o *= derpy;
            for (UpgradeDef u : MinionSim.installed(cat, m)) {
                if (!u.appliesTo(t)) continue;
                if (u.output != null && u.output > 0) o *= u.output;
                if (u.voidlingTierBonus > 0 && "VOIDLING".equals(t.id)) o *= 1 + u.voidlingTierBonus * m.tier / 100.0;
            }
            return o;
        }

        @Override public boolean solarActive() {
            try { return live && !Sky.calendar().now().isNight(); } catch (Throwable e) { return live; }
        }

        @Override public int maxStack(String itemId) {
            ItemDef d = Sky.items().def(itemId);
            return d == null ? 64 : Math.max(1, d.effectiveMaxStack());
        }

        @Override public double npcPrice(String itemId) {
            ItemDef d = Sky.items().def(itemId);
            return d == null ? 0 : d.npcSell;
        }

        @Override public boolean known(String itemId) { return Sky.items().exists(itemId); }
    }

    Env env(Prof prof, MinionStore s) { return new Env(prof, s); }

    /**
     * Env for a single online player (menus, admin), equivalent to a LIVE tick's (the viewer is usually on the
     * island: the menu shows the Solar Panel by day and the pet perk). Admin fast-forwards set {@code live = false}.
     */
    Env env(Player p) {
        Prof pr = prof(Sky.profiles().profileId(p));
        if (pr == null) pr = new Prof(Sky.profiles().profileId(p), p, List.of(p));
        Env e = new Env(pr, store(p));
        e.live = true;
        return e;
    }

    static int distinctPlaced(MinionStore s, String category) {
        Set<String> types = new HashSet<>();
        for (Placed m : s.placed.values()) {
            TypeDef t = MinionsModule.get().cat.type(m.type);
            if (t != null && category.equals(t.category)) types.add(t.id);
        }
        return types.size();
    }

    // ---- the production tick --------------------------------------------------------------------------

    void tick() {
        if (!mod.dials.enabled.get()) return;
        long now = System.currentTimeMillis();
        for (Prof pr : loaded()) {
            MinionStore s;
            try { s = store(pr.any); } catch (Throwable t) { continue; }
            if (s.placed.isEmpty()) { welcome.remove(pr.profileId); continue; }
            Env env = env(pr, s);
            long made = 0;
            double coins = 0;
            double longest = 0;
            for (Placed m : new ArrayList<>(s.placed.values())) {
                try {
                    TypeDef t = cat.type(m.type);
                    if (t == null) continue;
                    double cap = mod.dials.maxOfflineHours.get();
                    if (cap > 0 && m.lastUpdate > 0 && now - m.lastUpdate > cap * 3_600_000L) m.lastUpdate = now - (long) (cap * 3_600_000L);
                    World w = Bukkit.getWorld(m.world);
                    boolean live = w != null && isLive(w, m);
                    boolean paused = false;
                    if (live && !m.seated && mod.dials.layoutCheck.get()) paused = layoutValid(w, m, t, now) == 0;
                    MinionSim.Result r = new MinionSim.Result();
                    // Research 02 section 4.7: time nobody was here is caught up assuming a PERFECT layout and no
                    // sunshine. Only the last tick period is "live" (layout check, Solar Panel); without the split a
                    // blocked minion lost its whole offline production on the login tick.
                    long gapEnd = now - periodMs();
                    if (m.lastUpdate > 0 && gapEnd - m.lastUpdate > periodMs()) {
                        env.live = false;
                        merge(r, advance(m, gapEnd, env, false));
                    }
                    env.live = live;
                    merge(r, advance(m, now, env, paused));
                    if (!r.unknown.isEmpty()) {
                        for (String u : r.unknown) if (warnedUnknown.add(u))
                            Sky.log().warning("[minions] " + m.type + " minion produced unknown item " + u + " (owner module missing?)");
                    }
                    made += r.total();
                    coins += r.coins;
                    longest = Math.max(longest, r.seconds);
                    s.totalGenerated += r.total();
                    s.totalHopperCoins += r.coins;
                    // a seated minion is on a break: no full warning, no bubble (it is not trying to work)
                    boolean full = !m.seated && MinionSim.isFull(cat, m, t, MinionSim.capacity(cat, m), env);
                    ArmorStand as = stand(m.id);
                    if (as != null) mod.visuals.bubble(as, paused ? "&cThis location isn't perfect! :(" : full ? "&cMy storage is full! :(" : null);
                    if (full && !m.fullNotified) {
                        m.fullNotified = true;
                        notifyFull(pr, m, t);
                    } else if (!full) {
                        m.fullNotified = false;
                    }
                } catch (Throwable ex) {
                    Sky.log().log(Level.WARNING, "[minions] tick failed for minion " + m.id + " (" + m.type + ")", ex);
                }
            }
            UUID greet = welcome.remove(pr.profileId);
            if (greet != null && longest > 120 && (made > 0 || coins > 0)) {
                Player p = Bukkit.getPlayer(greet);
                if (p != null) {
                    Msg.send(p, "&aWhile you were away your minions made &e" + Text.commas(made) + " &aitems"
                            + (coins > 0 ? " &aand their hoppers earned &6" + Text.commasInt(coins) + " coins" : "") + "&a!");
                    Msg.send(p, "&7Collect them from each minion, or &e/minions&7.");
                }
            }
        }
    }

    long periodMs() { return Math.max(1, mod.dials.tickSeconds.get()) * 1000L; }

    double scorchedBonus() {
        UpgradeDef s = cat.upgrade("SCORCHED_POWER_CRYSTAL");
        return s == null ? 1 : s.speed;
    }

    /**
     * Advance one minion to {@code to}, split where the island's Beacon runs out of power so the boost only
     * covers the time it was really powered (offline catch-ups span days; a crystal lasts 48 h).
     */
    MinionSim.Result advance(Placed m, long to, Env env, boolean paused) {
        MinionSim.Result r = new MinionSim.Result();
        long until = env.store.beaconPoweredUntil;
        if (m.lastUpdate > 0 && until > m.lastUpdate && until < to) {
            env.stepStart = m.lastUpdate;
            merge(r, MinionSim.advance(cat, m, until, env, paused));
        }
        env.stepStart = m.lastUpdate > 0 ? m.lastUpdate : to;
        merge(r, MinionSim.advance(cat, m, to, env, paused));
        return r;
    }

    static void merge(MinionSim.Result into, MinionSim.Result r) {
        for (Map.Entry<String, Long> e : r.produced.entrySet()) into.produced.merge(e.getKey(), e.getValue(), Long::sum);
        into.harvests += r.harvests;
        into.coins += r.coins;
        into.lost += r.lost;
        into.full = into.full || r.full;
        into.seconds += r.seconds;
        for (String u : r.unknown) if (!into.unknown.contains(u)) into.unknown.add(u);
    }

    boolean isLive(World w, Placed m) {
        if (!w.isChunkLoaded(m.x >> 4, m.z >> 4)) return false;
        double r = mod.dials.animateRange.get();
        double r2 = r * r;
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            double dx = l.getX() - m.x, dz = l.getZ() - m.z;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    int layoutValid(World w, Placed m, TypeDef t, long now) {
        MinionVisuals.Anim a = mod.visuals.anim(m.id);
        if (a.valid < 0 || now - a.validAt > 15_000) {
            a.valid = mod.visuals.validCells(w, m, t);
            a.validAt = now;
        }
        return a.valid;
    }

    private void notifyFull(Prof pr, Placed m, TypeDef t) {
        if (!mod.dials.notifyFull.get()) return;
        NamespacedKey key = new NamespacedKey("skyclone", "setting.notify.minion_full");
        Features.Settings settings = Sky.service(Features.Settings.class);
        for (Player p : pr.online) {
            // wave 5: the menus module's Settings service; the raw PDC key stays as the fallback it was before
            boolean on;
            try { on = settings.flag(p, "notify.minion_full", true); } catch (Throwable ex) { on = true; }
            String v = p.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (!on || "false".equalsIgnoreCase(v)) continue;
            Msg.send(p, "&cYour " + MinionText.name(t, m.tier) + " &cis full! &7(" + MinionText.where(m) + "&7)");
        }
    }

    // ---- animation and bodies ---------------------------------------------------------------------------

    void animate() {
        if (!mod.dials.animate.get()) return;
        long now = System.currentTimeMillis();
        for (Prof pr : loaded()) {
            MinionStore s;
            try { s = store(pr.any); } catch (Throwable t) { continue; }
            Env env = null;
            for (Placed m : s.placed.values()) {
                ArmorStand as = stand(m.id);
                if (as == null) continue;
                MinionVisuals.Anim a = mod.visuals.anim(m.id);
                mod.visuals.unswing(as, a);
                if (m.seated) { a.nextAt = now + 5000; continue; }   // on its chair: no work to show
                mod.visuals.idleMobs(as.getWorld(), m, a);
                if (now < a.nextAt) continue;
                TypeDef t = cat.type(m.type);
                TierDef td = t == null ? null : t.tier(m.tier);
                if (td == null) continue;
                World w = as.getWorld();
                if (!isLive(w, m)) { a.nextAt = now + 5000; continue; }
                if (env == null) env = env(pr, s);
                env.live = true;
                boolean full = MinionSim.isFull(cat, m, t, MinionSim.capacity(cat, m), env);
                boolean blocked = mod.dials.layoutCheck.get() && layoutValid(w, m, t, now) == 0;
                if (a.nextAt > 0 && !full && !blocked) {
                    try { mod.visuals.act(w, m, t, as); }
                    catch (Throwable ex) { Sky.log().log(Level.FINE, "[minions] animation failed", ex); }
                }
                double speed = env.speedBonus(m, t);
                FuelDef f = MinionSim.activeFuel(cat, m, t);
                if (f != null && MinionSim.fuelSecondsLeft(cat, m) > 0 && (!f.solar || env.solarActive())) speed += f.speed;
                double tba = MinionSim.effectiveTba(td.tba, speed);
                a.nextAt = now + Math.max(1000L, (long) (tba * 1000));
            }
        }
    }

    ArmorStand stand(String minionId) {
        UUID u = stands.get(minionId);
        if (u == null) return null;
        Entity e = Bukkit.getEntity(u);
        return e instanceof ArmorStand as && as.isValid() ? as : null;
    }

    /** Spawn missing bodies, remove orphaned ones. */
    void ensureStands() {
        Set<String> alive = new HashSet<>();
        Set<String> aliveCrystals = new HashSet<>();
        Set<String> aliveProfiles = new HashSet<>();
        for (Prof pr : loaded()) {
            MinionStore s;
            try { s = store(pr.any); } catch (Throwable t) { continue; }
            for (Placed m : s.placed.values()) {
                alive.add(m.id);
                World w = Bukkit.getWorld(m.world);
                if (w == null || !w.isChunkLoaded(m.x >> 4, m.z >> 4)) continue;
                ArmorStand existing = stand(m.id);
                if (existing != null) { mod.visuals.syncChest(existing, m); mod.visuals.syncChair(existing, m); continue; }
                TypeDef t = cat.type(m.type);
                if (t == null) continue;
                try {
                    ArmorStand as = mod.visuals.spawnStand(w, m, t, pr.profileId);
                    stands.put(m.id, as.getUniqueId());
                } catch (Throwable ex) {
                    Sky.log().log(Level.WARNING, "[minions] could not spawn minion body " + m.id, ex);
                }
            }
            for (Crystal c : s.crystals.values()) {
                aliveCrystals.add(c.id);
                World w = Bukkit.getWorld(c.world);
                if (w == null || !w.isChunkLoaded(c.x >> 4, c.z >> 4)) continue;
                UUID u = crystalStands.get(c.id);
                Entity e = u == null ? null : Bukkit.getEntity(u);
                if (e != null && e.isValid()) continue;
                crystalStands.put(c.id, spawnCrystal(w, c, pr.profileId).getUniqueId());
            }
            aliveProfiles.add(pr.profileId);
            try { ensurePresent(pr, s); }
            catch (Throwable ex) { Sky.log().log(Level.FINE, "[minions] present failed", ex); }
        }
        for (String id : new ArrayList<>(presentStands.keySet())) if (!aliveProfiles.contains(id)) removePresent(id);
        for (Iterator<Map.Entry<String, UUID>> it = stands.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, UUID> e = it.next();
            if (alive.contains(e.getKey())) continue;
            Entity ent = Bukkit.getEntity(e.getValue());
            if (ent != null) ent.remove();
            mod.visuals.removeMobs(e.getKey());
            mod.visuals.removeChest(e.getKey());
            mod.visuals.removeChair(e.getKey());
            mod.visuals.anims.remove(e.getKey());
            it.remove();
        }
        for (Iterator<Map.Entry<String, UUID>> it = crystalStands.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, UUID> e = it.next();
            if (aliveCrystals.contains(e.getKey())) continue;
            Entity ent = Bukkit.getEntity(e.getValue());
            if (ent != null) ent.remove();
            it.remove();
        }
    }

    private ArmorStand spawnCrystal(World w, Crystal c, String profileId) {
        Location at = MinionVisuals.standLocation(w, c.x, c.y, c.z, 0);
        ItemDef d = Sky.items().def(c.item);
        return w.spawn(at, ArmorStand.class, as -> {
            as.setSmall(true);
            as.setInvisible(true);
            as.setBasePlate(false);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setPersistent(false);
            as.setSilent(true);
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
                    EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
                as.addEquipmentLock(s, org.bukkit.entity.ArmorStand.LockType.ADDING_OR_CHANGING);
            Material mat = d == null ? null : Material.matchMaterial(d.material);
            as.getEquipment().setHelmet(d != null && d.skull != null
                    ? com.skyclone.api.ui.Icons.skull(d.skull, " ", List.of())
                    : new ItemStack(mat != null && mat.isItem() && !mat.isAir() ? mat : Material.AMETHYST_CLUSTER));
            as.customName(Text.c(d == null ? "&dCrystal" : d.rarity.code() + d.name));
            as.setCustomNameVisible(true);
            as.getPersistentDataContainer().set(mod.visuals.crystalKey, PersistentDataType.STRING, profileId + "|" + c.id);
        });
    }

    void despawnAll() {
        for (UUID u : stands.values()) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        for (UUID u : crystalStands.values()) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        for (String id : new ArrayList<>(presentStands.keySet())) removePresent(id);
        for (String id : new ArrayList<>(mod.visuals.anims.keySet())) mod.visuals.removeMobs(id);
        for (String id : new ArrayList<>(mod.visuals.chestDisplays.keySet())) mod.visuals.removeChest(id);
        for (String id : new ArrayList<>(mod.visuals.chairDisplays.keySet())) mod.visuals.removeChair(id);
        stands.clear();
        crystalStands.clear();
        mod.visuals.anims.clear();
    }

    void respawn(String minionId) {
        mod.visuals.removeChest(minionId);
        mod.visuals.removeChair(minionId);
        UUID u = stands.remove(minionId);
        if (u == null) return;
        Entity e = Bukkit.getEntity(u);
        if (e != null) e.remove();
    }

    // ---- visual block index (drop guard) ----------------------------------------------------------------

    void indexVisual(String world, long key) { visualIndex.computeIfAbsent(world, k -> new HashSet<>()).add(key); }

    void unindexVisual(String world, long key) {
        Set<Long> s = visualIndex.get(world);
        if (s != null) s.remove(key);
    }

    boolean isVisualBlock(Block b) {
        Set<Long> s = visualIndex.get(b.getWorld().getName());
        return s != null && s.contains(MinionVisuals.pack(b));
    }

    void indexStore(MinionStore s) {
        for (Placed m : s.placed.values()) for (Long k : m.visuals) indexVisual(m.world, k);
    }

    /** A player broke a block a minion placed: it is gone from the minion's list too. */
    void forgetVisual(Player p, Block b) {
        long key = MinionVisuals.pack(b);
        unindexVisual(b.getWorld().getName(), key);
        if (!Sky.profiles().isReady(p)) return;
        for (Placed m : store(p).placed.values()) m.visuals.remove(Long.valueOf(key));
    }

    // ---- ownership ------------------------------------------------------------------------------------

    /** Is this location on the player's own private island (and may they build there)? */
    boolean onOwnIsland(Player p, Location l) {
        if (l == null || l.getWorld() == null) return false;
        if (Sky.worlds().buildBypass(p) && mod.dials.adminBypass.get()) return true;
        String island = Sky.worlds().islandOf(l);
        if (!"private_island".equals(island)) return false;
        if (Sky.has(Features.PrivateIsland.class)) {
            Features.PrivateIsland pi = Sky.service(Features.PrivateIsland.class);
            return pi.canBuild(p, l);
        }
        // Fallback while no island module is installed: the instance slot keyed by the profile id.
        try {
            Location o = Sky.worlds().instanceOrigin("private_island", Sky.profiles().profileId(p));
            IslandDef d = Sky.worlds().island("private_island");
            int half = (d == null ? 1024 : d.instanceSpacing) / 2;
            return o != null && o.getWorld() != null && o.getWorld().equals(l.getWorld())
                    && Math.abs(l.getX() - o.getX()) < half && Math.abs(l.getZ() - o.getZ()) < half;
        } catch (Throwable t) {
            return false;
        }
    }

    /** May this player change this minion right now (on the island, or anywhere when remote access is on)? */
    boolean canManage(Player p, Placed m) {
        if (mod.dials.remoteAccess.get()) return true;
        if (Sky.worlds().buildBypass(p)) return true;
        World w = Bukkit.getWorld(m.world);
        return w != null && p.getWorld().equals(w) && p.getLocation().distanceSquared(new Location(w, m.x, m.y, m.z)) < 160 * 160;
    }

    /** Per-player bookkeeping dropped on quit (the rate-limit map otherwise grows for ever). */
    void forget(Player p) { lastAction.remove(p.getUniqueId()); }

    boolean rateLimited(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastAction.get(p.getUniqueId());
        if (last != null && now - last < mod.dials.actionCooldownMs.get()) return true;
        lastAction.put(p.getUniqueId(), now);
        return false;
    }

    // ---- slots ------------------------------------------------------------------------------------------

    int communitySlots(Player p) {
        for (int i = Math.max(0, cat.slots.communityShopMax); i >= 1; i--) {
            try { if (Sky.requirements().check(p, "cshop:minion_slots:" + i)) return i; } catch (Throwable ignored) { return 0; }
        }
        return 0;
    }

    int slotsFor(Player p, MinionStore s) {
        int base = cat.placeableFor(s.crafted.size());
        return Math.min(mod.dials.slotCap.get(), base + communitySlots(p) + mod.dials.bonusSlots.get());
    }

    /** Record a unique tier; first time pays SkyBlock XP and may unlock a slot. */
    void markCrafted(Player p, String type, int tier, boolean announce) {
        if (!Sky.profiles().isReady(p)) return;
        TypeDef t = cat.type(type);
        if (t == null || t.tier(tier) == null) return;
        MinionStore s = store(p);
        int before = slotsFor(p, s);
        if (!s.crafted.add(MinionStore.uniqueKey(type, tier))) return;
        int xp = cat.sbxpForTier(tier);
        try { Sky.service(Features.SkyLevel.class).award(p, "minion:" + type + ":" + tier, xp, "New unique minion: " + Text.strip(MinionText.name(t, tier))); }
        catch (Throwable ignored) { }
        if (announce) {
            Msg.send(p, "&eNew unique minion: " + MinionText.name(t, tier) + "&e! &8(" + s.crafted.size() + " crafted)"
                    + (xp > 0 ? " &b+" + xp + " SkyBlock XP" : ""));
        }
        int after = slotsFor(p, s);
        if (after > before) {
            Msg.box(p, "&b&lMINION SLOT UNLOCKED", List.of("&7You can now place &a" + after + " &7minions on your island!",
                    "&7Next slot at &e" + Math.max(0, cat.nextSlotAt(s.crafted.size())) + " &7unique minions."));
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        }
    }

    // ---- items --------------------------------------------------------------------------------------------

    ItemStack minionItem(String type, int tier, long generated, boolean infused) {
        return minionItem(type, tier, generated, infused, false);
    }

    ItemStack minionItem(String type, int tier, long generated, boolean infused, boolean loyal) {
        TypeDef t = cat.type(type);
        ItemStack it = Sky.items().create(t.itemId(tier));
        if (generated > 0 || infused || loyal) {
            Sky.items().update(it, d -> {
                if (generated > 0) d.extra.addProperty("minionGenerated", generated);
                if (infused) d.extra.addProperty("minionInfused", true);
                if (loyal) d.extra.addProperty("minionFreeWill", true);
            });
        }
        return it;
    }

    /** Give n of an item in proper stacks through the loot pipeline. Returns how many hit the ground. */
    int giveItems(Player p, String id, long n, LootSource src) {
        if (n <= 0 || !Sky.items().exists(id)) return 0;
        ItemDef d = Sky.items().def(id);
        int max = d == null ? 64 : Math.max(1, d.effectiveMaxStack());
        int dropped = 0;
        final long total = n;
        while (n > 0) {
            int amt = (int) Math.min(n, max);
            dropped += Sky.loot().give(p, Sky.items().create(id, amt), src);
            n -= amt;
        }
        if (src == LootSource.MINION) creditCompacted(p, id, total);
        return dropped;
    }

    /**
     * The reference credits collections for what a minion COMPACTED (an Enchanted Cobblestone collected from a
     * minion counts as 160 Cobblestone). The loot pipeline's ItemGain only credits ids the collections module
     * knows, so a compacted id is resolved back through the compaction chain here and credited as its raw
     * item. Skipped when collections already counts the compacted id itself (no double credit).
     */
    void creditCompacted(Player p, String id, long n) {
        if (n <= 0 || p == null) return;
        Features.Collections coll = Sky.service(Features.Collections.class);
        if (coll == null || coll.isCollectionItem(id)) return;
        MinionCatalog.RawEquivalent raw = cat.rawEquivalent(id, coll::isCollectionItem);
        if (raw == null || raw.amount() <= 0) return;
        coll.add(p, raw.item(), raw.amount() * n);
    }

    /** How many of an item fit in the player's main inventory. */
    static int inventorySpace(Player p, String id) {
        return new InvSpace(p).room(id);
    }

    static int maxStackOf(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? 64 : Math.max(1, d.effectiveMaxStack());
    }

    /**
     * A running model of the player's 36 main slots. Several different items compete for the SAME empty
     * slots, so space must be reserved as it is promised: asking "how many cobblestone fit?" and then "how
     * many coal fit?" separately counted every empty slot twice, and Collect All threw the rest on the floor.
     */
    static final class InvSpace {
        private int empty;
        private final Map<String, Integer> partial = new HashMap<>();

        InvSpace(Player p) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                if (s == null || s.getType().isAir()) { empty++; continue; }
                String id = Sky.items().idOf(s);
                if (id == null) continue;
                int max = maxStackOf(id);
                if (max > 1 && s.getAmount() < max) partial.merge(id, max - s.getAmount(), Integer::sum);
            }
        }

        /** How many of this item would fit right now (nothing reserved). */
        int room(String id) {
            return partial.getOrDefault(id, 0) + empty * maxStackOf(id);
        }

        /** Reserve room for up to {@code want}; returns how many are reserved. */
        int take(String id, int want) {
            if (want <= 0) return 0;
            int max = maxStackOf(id);
            int fromPartial = Math.min(want, partial.getOrDefault(id, 0));
            if (fromPartial > 0) partial.merge(id, -fromPartial, Integer::sum);
            int left = want - fromPartial;
            int slots = Math.min(empty, (left + max - 1) / max);
            int intoEmpty = Math.min(left, slots * max);
            empty -= slots;
            int rest = slots * max - intoEmpty;
            if (rest > 0) partial.merge(id, rest, Integer::sum);
            return fromPartial + intoEmpty;
        }
    }

    // ---- placing / picking up ---------------------------------------------------------------------------

    void tryPlace(Player p, ItemStack hand, EquipmentSlot slot, Block clicked, BlockFace face) {
        String id = Sky.items().idOf(hand);
        TypeDef t = cat.typeOfItem(id);
        int tier = MinionCatalog.tierOfItem(id);
        if (t == null || t.tier(tier) == null) return;
        if (!Sky.profiles().isReady(p)) return;
        // Wave 5: sneak + right-click a PLACED Minion Chair to sit the new minion on it (research 4.3: it does not
        // work and does not count toward the cap). Without sneaking the chair seats the player (crafting's).
        boolean onChair = mod.dials.chairs.get() && furnitureChairAt(clicked);
        if (onChair && !p.isSneaking()) return;
        if (rateLimited(p)) return;
        Block target = onChair ? clicked : clicked.getRelative(face == null ? BlockFace.UP : face);
        if (!onChair) {
            if (!target.isEmpty() && !target.isPassable()) { Msg.error(p, "You can't place a minion there!"); return; }
            if (target.isLiquid()) { Msg.error(p, "Minions don't like getting their feet wet!"); return; }
            if (!target.getRelative(BlockFace.DOWN).getType().isSolid()) { Msg.error(p, "A minion needs solid ground to stand on!"); return; }
        }
        if (!onOwnIsland(p, target.getLocation())) { Msg.error(p, "You can only place minions on your own island!"); return; }
        MinionStore s = store(p);
        for (Placed o : s.placed.values())
            if (o.world.equals(target.getWorld().getName()) && o.x == target.getX() && o.y == target.getY() && o.z == target.getZ()) {
                Msg.error(p, onChair ? "A minion already sits on that chair!" : "There is already a minion there!");
                return;
            }
        int cap = slotsFor(p, s);
        if (!onChair && s.working() >= cap) {
            Msg.error(p, "You can only place a maximum of " + cap + " minions!");
            Msg.send(p, "&eCraft new unique minions to unlock more slots: &b/minions slots");
            return;
        }
        ItemStack cur = p.getInventory().getItem(slot);
        if (cur == null || !id.equals(Sky.items().idOf(cur))) return;
        ItemData data = Sky.items().data(cur);
        if (cur.getAmount() > 1) cur.setAmount(cur.getAmount() - 1);
        else p.getInventory().setItem(slot, null);

        long now = System.currentTimeMillis();
        Placed m = new Placed();
        m.id = UUID.randomUUID().toString();
        m.type = t.id;
        m.tier = tier;
        m.world = target.getWorld().getName();
        m.x = target.getX();
        m.y = target.getY();
        m.z = target.getZ();
        m.yaw = Math.round((p.getLocation().getYaw() + 180f) / 90f) * 90f;
        if (onChair) {
            m.seated = true;
            m.chair = FURNITURE_SEAT;
            // stairs face their backrest; the minion looks the other way
            if (target.getBlockData() instanceof org.bukkit.block.data.Directional d) m.yaw = yawOf(d.getFacing().getOppositeFace());
        }
        m.lastUpdate = now;
        m.placedAt = now;
        m.placedBy = p.getName();
        if (data.extra.has("minionGenerated")) { try { m.generated = data.extra.get("minionGenerated").getAsLong(); } catch (Exception ignored) { } }
        if (data.extra.has("minionInfused")) { try { m.infused = data.extra.get("minionInfused").getAsBoolean(); } catch (Exception ignored) { } }
        if (data.extra.has("minionFreeWill")) { try { m.freeWill = data.extra.get("minionFreeWill").getAsBoolean(); } catch (Exception ignored) { } }
        s.placed.put(m.id, m);
        try {
            ArmorStand as = mod.visuals.spawnStand(target.getWorld(), m, t, Sky.profiles().profileId(p));
            stands.put(m.id, as.getUniqueId());
        } catch (Throwable ex) {
            Sky.log().log(Level.WARNING, "[minions] could not spawn minion body", ex);
        }
        Msg.send(p, "&bYou placed a minion! (" + s.working() + "/" + cap + ")");
        if (onChair) Msg.send(p, "&7It sits on the chair: it rests, and does not count toward your minion limit.");
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        try { Sky.service(Features.Objectives.class).complete(p, "place_minion"); } catch (Throwable ignored) { }
        Sky.profiles().saveNow(p);
    }

    /** Minecraft yaw of a horizontal facing (south 0, west 90, north 180, east 270). */
    static float yawOf(BlockFace f) {
        return switch (f) {
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> 270f;
            default -> 0f;
        };
    }

    /** Pick a minion up: the minion (with its generated counter), its attachments, its storage. */
    boolean pickup(Player p, String minionId) { return pickup(p, minionId, false); }

    boolean pickup(Player p, String minionId, boolean force) {
        if (!Sky.profiles().isReady(p)) return false;
        MinionStore s = store(p);
        Placed m = s.placed.get(minionId);
        if (m == null) return false;
        if (!force && !canManage(p, m)) { Msg.error(p, "Visit your island to pick up this minion!"); return false; }
        if (!force && !fitsPickup(p, m)) {
            Msg.error(p, "You don't have enough inventory space to pick up this minion and everything in it!");
            Msg.send(p, "&7Collect its items first, or make some room.");
            return false;
        }
        TypeDef t = cat.type(m.type);
        s.placed.remove(minionId);           // remove FIRST: a failure below can lose, never duplicate
        dismantle(p, m, t, true);
        Msg.send(p, "&aYou picked up a minion! You now have " + s.working() + " out of a maximum of "
                + slotsFor(p, s) + " minions placed.");
        Msg.sound(p, Sound.ENTITY_ITEM_PICKUP, 1f);
        Sky.profiles().saveNow(p);
        return true;
    }

    /**
     * Take a minion that is ALREADY removed from the store out of the world and hand back what it held: its
     * blocks, mobs, body and chest block go; attachments, infinite fuel, the chair it sat on, its storage and its
     * hopper coins are given to the player; with {@code withMinion} also the minion item itself (pick-up).
     */
    void dismantle(Player p, Placed m, TypeDef t, boolean withMinion) {
        World w = Bukkit.getWorld(m.world);
        mod.visuals.clearAll(w, m, t);
        removeChestBlock(m);
        respawn(m.id);
        if (withMinion && t != null) giveRaw(p, minionItem(m.type, m.tier, m.generated, m.infused, m.freeWill));
        for (String up : attachments(m))
            if (Sky.items().exists(up)) giveRaw(p, Sky.items().create(up));
        FuelDef f = cat.fuel(m.fuel);
        if (f != null && f.infinite() && Sky.items().exists(m.fuel)) giveRaw(p, Sky.items().create(m.fuel));
        for (Slot sl : m.storage) giveItems(p, sl.id, sl.amount, LootSource.STORAGE);
        if (m.heldCoins >= 0.1) Sky.economy().deposit(p, Math.floor(m.heldCoins * 10) / 10, "minion: hopper sales (" + m.type + ")");
    }

    /** Every one-off item attached to a minion that comes back when it is dismantled (the chair included). */
    static List<String> attachments(Placed m) {
        List<String> l = new ArrayList<>(6);
        for (String up : new String[]{m.upgrade1, m.upgrade2, m.shipping, m.chest, m.skin, m.seated ? m.chair : null})
            if (up != null && !up.isEmpty()) l.add(up);
        return l;
    }

    private void giveRaw(Player p, ItemStack it) { Sky.loot().give(p, it, LootSource.STORAGE); }

    /**
     * Would the minion, its attachments and every stored item fit in the inventory? A pick-up that did not fit
     * used to pour up to a thousand items on the floor, where anyone could take them a minute later.
     */
    boolean fitsPickup(Player p, Placed m) { return fitsDismantle(p, m, true, null); }

    /** Would everything {@link #dismantle} gives fit, plus one {@code extra} item (Free Will's Postcard)? */
    boolean fitsDismantle(Player p, Placed m, boolean withMinion, String extra) {
        InvSpace space = new InvSpace(p);
        TypeDef t = cat.type(m.type);
        if (extra != null && Sky.items().exists(extra) && space.take(extra, 1) < 1) return false;
        if (withMinion && t != null && space.take(t.itemId(m.tier), 1) < 1) return false;
        for (String up : attachments(m))
            if (Sky.items().exists(up) && space.take(up, 1) < 1) return false;
        FuelDef f = cat.fuel(m.fuel);
        if (f != null && f.infinite() && Sky.items().exists(m.fuel) && space.take(m.fuel, 1) < 1) return false;
        for (Slot sl : m.storage) if (Sky.items().exists(sl.id) && space.take(sl.id, sl.amount) < sl.amount) return false;
        return true;
    }

    // ---- collecting ---------------------------------------------------------------------------------------

    /** Collect one storage slot; returns items moved. */
    int collectSlot(Player p, Placed m, int index) {
        if (index < 0 || index >= m.storage.size()) return 0;
        Slot s = m.storage.get(index);
        int take = Math.min(s.amount, inventorySpace(p, s.id));
        if (take <= 0) { Msg.error(p, "Your inventory is full!"); return 0; }
        s.amount -= take;
        String id = s.id;
        if (s.amount <= 0) m.storage.remove(index);
        Map<SkillType, Double> xp = new EnumMap<>(SkillType.class);
        addXp(xp, id, take);
        giveItems(p, id, take, LootSource.MINION);
        distributeXp(p, xp);
        return take;
    }

    /** Collect everything that fits, plus held hopper coins. Returns items moved. */
    int collectAll(Player p, Placed m, boolean quiet) {
        int moved = 0;
        boolean blocked = false;
        Map<SkillType, Double> xp = new EnumMap<>(SkillType.class);
        Map<String, Integer> got = new LinkedHashMap<>();
        InvSpace space = new InvSpace(p);
        for (int i = m.storage.size() - 1; i >= 0; i--) {
            Slot s = m.storage.get(i);
            int take = space.take(s.id, s.amount);
            if (take < s.amount) blocked = true;
            if (take <= 0) continue;
            s.amount -= take;
            got.merge(s.id, take, Integer::sum);
            moved += take;
            if (s.amount <= 0) m.storage.remove(i);
        }
        for (Map.Entry<String, Integer> e : got.entrySet()) {
            addXp(xp, e.getKey(), e.getValue());
            giveItems(p, e.getKey(), e.getValue(), LootSource.MINION);
        }
        if (m.heldCoins >= 0.1) {
            double c = Math.floor(m.heldCoins * 10) / 10;
            m.heldCoins -= c;
            Sky.economy().deposit(p, c, "minion: hopper sales (" + m.type + ")");
            if (!quiet) Msg.send(p, "&6+" + Text.commas(c) + " coins &7from the minion's hopper.");
        }
        distributeXp(p, xp);
        if (blocked && !quiet) Msg.error(p, "Your inventory is full!");
        if (moved > 0) { m.fullNotified = false; Msg.sound(p, Sound.ENTITY_ITEM_PICKUP, 1.2f); }
        return moved;
    }

    private void addXp(Map<SkillType, Double> xp, String item, int amount) {
        MinionCatalog.XpEntry e = cat.xp.get(item);
        if (e == null || e.xp <= 0) return;
        SkillType st = SkillType.parse(e.skill);
        if (st == null) return;
        xp.merge(st, e.xp * amount, Double::sum);
    }

    /** Co-op: every member gets an equal share; offline ones receive it on their next login (research 02 section 4.7). */
    void distributeXp(Player collector, Map<SkillType, Double> xp) {
        if (xp.isEmpty()) return;
        SkillService skills = Sky.service(SkillService.class);
        List<UUID> members = new ArrayList<>(Sky.profiles().members(Sky.profiles().profileId(collector)));
        if (!mod.dials.coopSplit.get() || members.size() <= 1) {
            for (Map.Entry<SkillType, Double> e : xp.entrySet()) skills.addXp(collector, e.getKey(), e.getValue(), "minion");
            return;
        }
        MinionStore s = store(collector);
        double n = members.size();
        for (UUID u : members) {
            Player pl = Bukkit.getPlayer(u);
            for (Map.Entry<SkillType, Double> e : xp.entrySet()) {
                double share = e.getValue() / n;
                if (pl != null && Sky.profiles().isReady(pl) && Sky.profiles().profileId(pl).equals(Sky.profiles().profileId(collector)))
                    skills.addXp(pl, e.getKey(), share, "minion");
                else
                    s.pendingXp.computeIfAbsent(u.toString(), k -> new HashMap<>()).merge(e.getKey().name(), share, Double::sum);
            }
        }
    }

    void grantPendingXp(Player p) {
        MinionStore s = store(p);
        Map<String, Double> mine = s.pendingXp.remove(p.getUniqueId().toString());
        if (mine == null || mine.isEmpty()) return;
        SkillService skills = Sky.service(SkillService.class);
        double total = 0;
        for (Map.Entry<String, Double> e : mine.entrySet()) {
            SkillType st = SkillType.parse(e.getKey());
            if (st == null) continue;
            skills.addXp(p, st, e.getValue(), "minion");
            total += e.getValue();
        }
        Msg.send(p, "&aYour co-op collected from your minions while you were away: &3+" + Text.commas(Math.round(total * 10) / 10.0) + " skill XP&a.");
    }

    // ---- fuel, upgrades, attachments ----------------------------------------------------------------------

    /** Put the clicked inventory item into the right slot of the minion. Returns a message or null. */
    String install(Player p, Placed m, ItemStack clicked) {
        if (!canManage(p, m)) return "&cVisit your island to change this minion!";
        String id = Sky.items().idOf(clicked);
        if (id == null) return null;
        TypeDef t = cat.type(m.type);
        if (t == null) return null;
        if (MINION_CHAIR.equals(id)) return seat(p, m, id);
        FuelDef f = cat.fuel(id);
        if (f != null) return installFuel(p, m, t, f, clicked);
        UpgradeDef u = cat.upgrade(id);
        if (u == null) {
            if (cat.isMinionItem(id)) return "&cPick this minion up first if you want to swap it.";
            return "&cThat is not a minion fuel or upgrade.";
        }
        switch (u.slot) {
            case "upgrade": {
                if (!u.appliesTo(t)) return "&cThis upgrade does nothing for a " + t.name + " Minion.";
                if (u.requirement != null && !Sky.requirements().checkOrTell(p, u.requirement)) return "";
                if (m.upgrade1 != null && m.upgrade2 != null) return "&cBoth upgrade slots are full!";
                if (u.onePerProfile) {
                    for (Placed o : store(p).placed.values())
                        if (o.hasUpgrade(u.id)) return "&cYou can only use one " + name(u.id) + " &cper island!";
                }
                if (!take(p, id)) return null;
                if (m.upgrade1 == null) m.upgrade1 = id; else m.upgrade2 = id;
                refreshBody(m);
                return "&aInstalled " + name(id) + "&a.";
            }
            case "shipping": {
                if (m.shipping != null) return "&cThis minion already has an automated shipping item!";
                if (!take(p, id)) return null;
                m.shipping = id;
                return "&aInstalled " + name(id) + "&a.";
            }
            case "chest": {
                if (m.chest != null) return "&cThis minion already has a storage chest attached!";
                if (!take(p, id)) return null;
                m.chest = id;
                // wave 5: the chest also stands next to the minion as a real block, when there is a free spot
                boolean placed = placeChestBlockNear(m, Sky.profiles().profileId(p));
                refreshBody(m);
                return "&aAttached " + name(id) + "&a: &e+" + u.slots + " &astorage slots."
                        + (placed ? " &7It stands beside the minion: click it to open." : "");
            }
            case "freewill":
                return "&eClick &6Free Will &ein your inventory from the minion's menu to offer it.";
            case "skin": {
                if (m.skin != null) return "&cTake the current skin off first!";
                if (!take(p, id)) return null;
                m.skin = id;
                refreshBody(m);
                return "&aApplied " + name(id) + "&a.";
            }
            case "infusion": {
                if (m.infused) return "&cThis minion is already infused!";
                if (!take(p, id)) return null;
                m.infused = true;
                return "&aThe minion is now permanently &e10% &afaster.";
            }
            case "stone": {
                if (u.types == null || !u.types.contains(m.type)) return "&cThis stone is for a different minion.";
                if (m.tier != u.fromTier) return "&cThis stone needs a tier " + Text.roman(u.fromTier) + " minion.";
                if (t.tier(u.toTier) == null) return "&cThat tier does not exist.";
                if (!take(p, id)) return null;
                m.tier = u.toTier;
                markCrafted(p, m.type, m.tier, true);
                refreshBody(m);
                return "&6Your minion is now " + MinionText.name(t, m.tier) + "&6!";
            }
            case "crystal", "beacon":
                return "&cPlace that on your island instead (right-click a block with it).";
            case "power":
                return "&cPower Crystals go into your island's Beacon: click the Beacon.";
            default:
                return "&cThat is not a minion upgrade.";
        }
    }

    private String installFuel(Player p, Placed m, TypeDef t, FuelDef f, ItemStack clicked) {
        if (f.onlyType != null && !f.onlyType.equals(t.id)) return "&cThis fuel only works in a " + cat.type(f.onlyType).name + " Minion.";
        if (f.onlyCategory != null && !f.onlyCategory.equals(t.category))
            return "&cThis fuel only works in " + Text.strip(MinionText.category(f.onlyCategory)) + " minions.";
        if (m.fuel != null && !m.fuel.equals(f.id)) return "&cThis minion already burns a different fuel. Take it out first.";
        if (f.onePerProfile && !f.id.equals(m.fuel)) {
            for (Placed o : store(p).placed.values())
                if (o != m && f.id.equals(o.fuel)) return "&cOnly one minion on your island can burn " + name(f.id) + "&c!";
        }
        if (f.infinite()) {
            if (m.fuel != null) return "&cThis minion already has that fuel.";
            if (!take(p, f.id)) return null;
            m.fuel = f.id;
            m.fuelCount = 1;
            m.fuelLeftMs = 0;
            return "&aFuel added: " + name(f.id) + "&a.";
        }
        int room = 64 - (m.fuel == null ? 0 : m.fuelCount);
        if (room <= 0) return "&cThe fuel slot is full.";
        int want = Math.min(room, Math.max(1, clicked.getAmount()));
        int removed = Sky.items().remove(p, f.id, want);
        if (removed <= 0) return null;
        if (m.fuel == null) {
            m.fuel = f.id;
            m.fuelCount = removed;
            m.fuelLeftMs = f.seconds * 1000L;
        } else {
            m.fuelCount += removed;
        }
        return "&aAdded &e" + removed + "x " + name(f.id) + "&a. It lasts &e" + Text.duration((long) MinionSim.fuelSecondsLeft(cat, m)) + "&a.";
    }

    private boolean take(Player p, String id) {
        return Sky.items().remove(p, id, 1) == 1;
    }

    /** Take an attachment out. which = upgrade1 | upgrade2 | shipping | chest | skin | fuel. */
    String takeOut(Player p, Placed m, String which) {
        if (!canManage(p, m)) return "&cVisit your island to change this minion!";
        String id;
        switch (which) {
            case "upgrade1" -> { id = m.upgrade1; m.upgrade1 = null; }
            case "upgrade2" -> { id = m.upgrade2; m.upgrade2 = null; }
            case "shipping" -> { id = m.shipping; m.shipping = null; }
            case "skin" -> { id = m.skin; m.skin = null; }
            case "chest" -> {
                if (m.storage.size() > MinionSim.ownSlots(cat, m)) return "&cEmpty the storage chest first!";
                id = m.chest;
                m.chest = null;
                removeChestBlock(m);
            }
            case "chair" -> { return standUp(p, m); }
            case "fuel" -> {
                FuelDef f = cat.fuel(m.fuel);
                id = m.fuel;
                m.fuel = null;
                m.fuelCount = 0;
                m.fuelLeftMs = 0;
                if (f != null && !f.infinite()) return "&7The fuel burned away.";
            }
            default -> { return null; }
        }
        if (id == null) return null;
        if (Sky.items().exists(id)) giveRaw(p, Sky.items().create(id));
        refreshBody(m);
        return "&aTook out " + name(id) + "&a.";
    }

    void refreshBody(Placed m) {
        ArmorStand as = stand(m.id);
        TypeDef t = cat.type(m.type);
        if (as != null && t != null) mod.visuals.dress(as, m, t);
        if (as != null) { mod.visuals.syncChest(as, m); mod.visuals.syncChair(as, m); }
    }

    // ---- wave 5: Minion Chair -----------------------------------------------------------------------------

    /**
     * Sit the minion on a Minion Chair (crafting's furniture item, clicked in the inventory from the minion's
     * menu). Research 4.3: a seated minion stops working and does not count toward the placement cap. The chair
     * is consumed while it sits and handed back when it stands up or is picked up.
     */
    String seat(Player p, Placed m, String chairId) {
        if (!mod.dials.chairs.get()) return "&cMinion Chairs are turned off on this server.";
        if (m.seated) return "&cThis minion is already sitting down.";
        if (!take(p, chairId)) return null;
        m.seated = true;
        m.chair = chairId;
        mod.visuals.removeMobs(m.id);
        refreshBody(m);
        MinionStore s = store(p);
        Sky.profiles().saveNow(p);
        Msg.sound(p, Sound.BLOCK_WOOD_PLACE, 1.2f);
        return "&aYour minion sits down for a rest. &7It stops working and no longer counts toward your limit &8("
                + s.working() + "/" + slotsFor(p, s) + " working)&7.";
    }

    /** Stand a seated minion back up: only when a working slot is free (it counts again). */
    String standUp(Player p, Placed m) {
        if (!m.seated) return null;
        if (FURNITURE_SEAT.equals(m.chair)) return "&cThis minion sits on a placed chair: pick the minion up to move it off.";
        MinionStore s = store(p);
        int cap = slotsFor(p, s);
        if (s.working() >= cap) return "&cYou already have " + cap + " working minions! Pick one up or seat another one first.";
        String chair = m.chair;      // null = seated by an admin or its furniture is gone: nothing to hand back
        boolean give = chair != null && Sky.items().exists(chair);
        if (give && new InvSpace(p).room(chair) < 1) return "&cYour inventory is full!";
        m.seated = false;
        m.chair = null;
        m.lastUpdate = System.currentTimeMillis();     // the break was not work: nothing to catch up
        if (give) giveRaw(p, Sky.items().create(chair));
        refreshBody(m);
        Sky.profiles().saveNow(p);
        return "&aYour minion stands up and gets back to work.";
    }

    /**
     * Is this block a PLACED Minion Chair (crafting's furniture: an oak-stairs block recorded in the chunk's
     * {@code skyclone:furniture} list as {"id":"MINION_CHAIR","b":[[x,y,z]]})? Read-only; never written here.
     */
    boolean furnitureChairAt(Block b) {
        if (b == null || b.getType() != Material.OAK_STAIRS || FURNITURE_KEY == null) return false;
        try {
            String raw = b.getChunk().getPersistentDataContainer().get(FURNITURE_KEY, PersistentDataType.STRING);
            if (raw == null || raw.indexOf(MINION_CHAIR) < 0) return false;
            for (com.google.gson.JsonElement el : com.google.gson.JsonParser.parseString(raw).getAsJsonArray()) {
                com.google.gson.JsonObject o = el.getAsJsonObject();
                if (!o.has("id") || !MINION_CHAIR.equals(o.get("id").getAsString()) || !o.has("b")) continue;
                for (com.google.gson.JsonElement pos : o.getAsJsonArray("b")) {
                    com.google.gson.JsonArray a = pos.getAsJsonArray();
                    if (a.size() >= 3 && a.get(0).getAsInt() == b.getX() && a.get(1).getAsInt() == b.getY() && a.get(2).getAsInt() == b.getZ())
                        return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    // ---- wave 5: Free Will --------------------------------------------------------------------------------

    /** Loyalty chance for this minion right now, in percent (dials). */
    double freeWillChance(Placed m) {
        return MinionSim.freeWillChance(m.tier, mod.dials.freeWillBase.get(), mod.dials.freeWillPerTier.get());
    }

    /**
     * Offer Free Will (reference: a minion stays and gets +10% speed for good, or leaves and sends a Postcard;
     * loyalty 50% at tier I, +4% per tier). The item is consumed first; if the minion leaves, everything it held
     * comes back (attachments, storage, coins, the chair) and the minion item itself is gone. Refused up front
     * when that could not all fit, so nothing is ever dropped on the floor.
     */
    String useFreeWill(Player p, String minionId) {
        if (!mod.dials.freeWill.get()) return "&cFree Will is turned off on this server.";
        if (!Sky.profiles().isReady(p)) return null;
        MinionStore s = store(p);
        Placed m = s.placed.get(minionId);
        if (m == null) return null;
        if (!canManage(p, m)) return "&cVisit your island to change this minion!";
        if (m.freeWill) return "&cThis minion already chose to stay with you.";
        if (!fitsDismantle(p, m, false, POSTCARD))
            return "&cMake room in your inventory first: if the minion leaves, everything it holds comes back to you.";
        if (Sky.items().remove(p, FREE_WILL, 1) != 1) return "&cYou don't have " + name(FREE_WILL) + "&c!";
        double chance = freeWillChance(m);
        TypeDef t = cat.type(m.type);
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble(100) < chance) {
            m.freeWill = true;
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
            Sky.profiles().saveNow(p);
            return "&aThe minion thought it over... and &lSTAYED&a! &7It now works &a+10% &7faster, for good. &8("
                    + MinionText.pct(chance) + " loyalty chance)";
        }
        s.placed.remove(minionId);                 // remove FIRST, like a pick-up
        dismantle(p, m, t, false);
        if (Sky.items().exists(POSTCARD)) giveRaw(p, Sky.items().create(POSTCARD));
        Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
        Sky.profiles().saveNow(p);
        return "&cThe minion packed its things and &lLEFT &cyou... &7Everything it held came back to you, and it mailed you a "
                + name(POSTCARD) + "&7. &8(" + MinionText.pct(chance) + " loyalty chance)";
    }

    // ---- wave 5: physical storage chests --------------------------------------------------------------

    /** The 8 cells touching the minion at its own height, the 4 faces first. */
    static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /**
     * Put the attached storage chest next to the minion as a real chest block (menu install). Picks the first free
     * touching cell, faces first; false (storage still works, shown as a display on its back) when the dial is off,
     * the chunk is not loaded or every touching cell is taken.
     */
    boolean placeChestBlockNear(Placed m, String profileId) {
        if (!mod.dials.physicalChests.get() || m.chest == null || m.chestBlock != null) return false;
        World w = Bukkit.getWorld(m.world);
        if (w == null || !w.isChunkLoaded(m.x >> 4, m.z >> 4)) return false;
        for (int[] d : AROUND) {
            Block b = w.getBlockAt(m.x + d[0], m.y, m.z + d[1]);
            if (!b.isEmpty() || minionAt(profileId, b) || !w.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            return placeChestBlockAt(m, b, profileId);
        }
        return false;
    }

    /** Is any minion of this profile standing in that block? (Another minion's feet are air.) */
    private boolean minionAt(String profileId, Block b) {
        for (UUID u : stands.values()) {
            Entity e = Bukkit.getEntity(u);
            if (e == null || !e.getWorld().equals(b.getWorld())) continue;
            Location l = e.getLocation();
            if (l.getBlockX() == b.getX() && l.getBlockY() == b.getY() && l.getBlockZ() == b.getZ()) return true;
        }
        return false;
    }

    /** Place and tag the chest block; it stays EMPTY for ever (the storage lives in the minion's data). */
    boolean placeChestBlockAt(Placed m, Block b, String profileId) {
        try {
            b.setType(Material.CHEST, false);
            org.bukkit.block.data.BlockData bd = b.getBlockData();
            if (bd instanceof org.bukkit.block.data.type.Chest cd) {
                cd.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
                cd.setFacing(facingAway(m, b));
                b.setBlockData(cd, false);
            }
            if (b.getState() instanceof org.bukkit.block.Chest c) {
                c.getPersistentDataContainer().set(mod.visuals.chestKey, PersistentDataType.STRING, profileId + "|" + m.id);
                c.customName(Text.c("&aMinion Storage"));
                c.update(true, false);
            }
            m.chestBlock = MinionVisuals.pack(b);
            return true;
        } catch (Throwable ex) {
            Sky.log().log(Level.WARNING, "[minions] could not place a storage chest block", ex);
            if (b.getType() == Material.CHEST) b.setType(Material.AIR, false);
            return false;
        }
    }

    /** A chest beside the minion opens away from it (the side the player stands on). */
    static BlockFace facingAway(Placed m, Block b) {
        int dx = b.getX() - m.x, dz = b.getZ() - m.z;
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** Remove the minion's chest block, but only if it is still OUR chest (a player may have rebuilt there). */
    void removeChestBlock(Placed m) {
        Long key = m.chestBlock;
        m.chestBlock = null;
        if (key == null) return;
        World w = Bukkit.getWorld(m.world);
        if (w == null) return;
        Block b = MinionVisuals.unpack(w, key);
        String[] tag = chestTag(b);
        if (tag != null && tag[1].equals(m.id)) {
            if (b.getState() instanceof org.bukkit.block.Chest c) c.getBlockInventory().clear();   // always empty; belt and braces
            b.setType(Material.AIR, false);
        }
    }

    /** {profileId, minionId} when this block is a minion's storage chest, else null. Reads the block's own tag. */
    String[] chestTag(Block b) {
        if (b == null || b.getType() != Material.CHEST) return null;
        try {
            if (!(b.getState(false) instanceof org.bukkit.block.Chest c)) return null;
            String v = c.getPersistentDataContainer().get(mod.visuals.chestKey, PersistentDataType.STRING);
            if (v == null) return null;
            int i = v.indexOf('|');
            return i < 0 ? null : new String[]{v.substring(0, i), v.substring(i + 1)};
        } catch (Throwable ex) {
            return null;
        }
    }

    /**
     * Right-click a block with a storage chest item: like the reference, the chest goes down TOUCHING one of your
     * minions (same height, faces or corners) and attaches to it. Elsewhere it is refused (it is not a vanilla chest).
     */
    void tryPlaceChest(Player p, ItemStack hand, EquipmentSlot slot, Block clicked, BlockFace face) {
        String id = Sky.items().idOf(hand);
        UpgradeDef u = cat.upgrade(id);
        if (u == null || !"chest".equals(u.slot)) return;
        if (!Sky.profiles().isReady(p) || rateLimited(p)) return;
        if (!mod.dials.physicalChests.get()) { Msg.error(p, "Attach storage chests from the minion's menu (click it in your inventory)."); return; }
        Block target = clicked.getRelative(face == null ? BlockFace.UP : face);
        if (!target.isEmpty()) { Msg.error(p, "You can't place that there!"); return; }
        if (!onOwnIsland(p, target.getLocation())) { Msg.error(p, "Minion storage can only go next to a minion on your own island!"); return; }
        String profileId = Sky.profiles().profileId(p);
        if (minionAt(profileId, target)) { Msg.error(p, "A minion is standing there!"); return; }
        MinionStore s = store(p);
        Placed best = null;
        boolean sawFull = false;
        int bestRank = 99;
        for (Placed m : s.placed.values()) {
            if (!m.world.equals(target.getWorld().getName()) || m.y != target.getY()) continue;
            int dx = target.getX() - m.x, dz = target.getZ() - m.z;
            if (Math.max(Math.abs(dx), Math.abs(dz)) != 1) continue;
            if (m.chest != null) { sawFull = true; continue; }
            int rank = Math.abs(dx) + Math.abs(dz);       // a face beats a corner
            if (rank < bestRank) { best = m; bestRank = rank; }
        }
        if (best == null) {
            Msg.error(p, sawFull ? "That minion already has a storage chest attached!" : "Place minion storage right next to one of your minions!");
            return;
        }
        ItemStack cur = p.getInventory().getItem(slot);
        if (cur == null || !id.equals(Sky.items().idOf(cur))) return;
        if (cur.getAmount() > 1) cur.setAmount(cur.getAmount() - 1); else p.getInventory().setItem(slot, null);
        best.chest = id;
        if (!placeChestBlockAt(best, target, profileId)) Msg.send(p, "&7(The chest could not stand there; it rides on the minion's back instead.)");
        refreshBody(best);
        TypeDef t = cat.type(best.type);
        Msg.success(p, "Attached " + Text.strip(name(id)) + " to your " + (t == null ? "minion" : Text.strip(MinionText.name(t, best.tier)))
                + ": +" + u.slots + " storage slots.");
        Msg.sound(p, Sound.BLOCK_CHEST_CLOSE, 1.2f);
        Sky.profiles().saveNow(p);
    }

    /** Right-click on a minion's chest block: its owner sees the chest storage; an orphan chest is cleared. */
    void clickChest(Player p, Block b, String[] tag) {
        if (!Sky.profiles().isReady(p)) return;
        if (!tag[0].equals(Sky.profiles().profileId(p))) { Msg.error(p, "This storage belongs to someone else's minion!"); return; }
        Placed m = store(p).placed.get(tag[1]);
        if (m == null || m.chest == null) {
            b.setType(Material.AIR, false);     // its minion is gone: a leftover, never a vanilla chest
            Msg.send(p, "&7That storage chest had no minion any more and crumbled away.");
            return;
        }
        mod.menus.openChest(p, m.id);
    }

    /** Breaking a minion's chest block takes the chest off (only when its slots are empty), like the menu does. */
    void breakChest(Player p, Block b, String[] tag) {
        if (!Sky.profiles().isReady(p)) return;
        boolean mine = tag[0].equals(Sky.profiles().profileId(p));
        if (!mine && !Sky.worlds().buildBypass(p)) { Msg.error(p, "This storage belongs to someone else's minion!"); return; }
        Placed m = mine ? store(p).placed.get(tag[1]) : null;
        if (m == null || m.chest == null) {
            if (mine || Sky.worlds().buildBypass(p)) b.setType(Material.AIR, false);   // orphan
            return;
        }
        String msg = takeOut(p, m, "chest");
        if (msg != null) Msg.send(p, msg);
        Sky.profiles().saveNow(p);
    }

    // ---- wave 5: the Winter Crystal's yearly present ----------------------------------------------------------

    static final String WINTER_CRYSTAL = "WINTER_ISLAND_CRYSTAL";
    /** profile id -> the stand of its unclaimed present. */
    final Map<String, UUID> presentStands = new HashMap<>();

    /**
     * Pure: is a new present due? NEU lore: "Once every SkyBlock Year, a present will spawn somewhere on the
     * island". One per year (the year it appeared is remembered), and never a second while one is unclaimed.
     */
    static boolean presentDue(boolean hasCrystal, int lastYear, boolean waiting, int year) {
        return hasCrystal && !waiting && year > lastYear;
    }

    /** Run by the body loop for every loaded profile: spawn this year's present, keep its stand standing. */
    void ensurePresent(Prof pr, MinionStore s) {
        if (!mod.dials.winterPresent.get()) { removePresent(pr.profileId); return; }
        Crystal crystal = null;
        for (Crystal c : s.crystals.values()) if (WINTER_CRYSTAL.equals(c.item)) { crystal = c; break; }
        int year;
        try { year = Sky.calendar().now().year(); } catch (Throwable t) { year = -1; }
        if (year >= 0 && presentDue(crystal != null, s.presentYear, s.presentWorld != null, year)) {
            World cw = Bukkit.getWorld(crystal.world);
            if (cw != null && cw.isChunkLoaded(crystal.x >> 4, crystal.z >> 4)) {
                Block spot = presentSpot(cw, crystal, pr.any);
                if (spot != null) {
                    s.presentYear = year;
                    s.presentAt = MinionVisuals.pack(spot);
                    s.presentWorld = cw.getName();
                    for (Player p : pr.online)
                        if (p.getWorld().equals(cw)) Msg.send(p, "&bA present appeared somewhere on your island! &7(Winter Crystal)");
                }
            }
        }
        if (s.presentWorld == null) { removePresent(pr.profileId); return; }
        World w = Bukkit.getWorld(s.presentWorld);
        if (w == null) return;
        int x = MinionVisuals.unpackX(s.presentAt), y = MinionVisuals.unpackY(s.presentAt), z = MinionVisuals.unpackZ(s.presentAt);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        UUID u = presentStands.get(pr.profileId);
        Entity e = u == null ? null : Bukkit.getEntity(u);
        if (e != null && e.isValid()) return;
        try {
            presentStands.put(pr.profileId, spawnPresent(w, x, y, z, pr.profileId).getUniqueId());
        } catch (Throwable ex) {
            Sky.log().log(Level.WARNING, "[minions] could not spawn the Winter Crystal present", ex);
        }
    }

    /**
     * A random free spot within {@code minions.winter-present-radius} of the crystal: air with air above and a
     * solid floor, on the owner's island, not a minion's own cell. Null when none was found this time.
     */
    private Block presentSpot(World w, Crystal c, Player owner) {
        int r = Math.max(1, mod.dials.winterPresentRadius.get());
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 48; attempt++) {
            int x = c.x + rnd.nextInt(-r, r + 1), z = c.z + rnd.nextInt(-r, r + 1);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = c.y + 6; y >= c.y - 6; y--) {
                Block b = w.getBlockAt(x, y, z);
                if (!b.isEmpty() || !b.getRelative(BlockFace.UP).isEmpty()) continue;
                Block floor = b.getRelative(BlockFace.DOWN);
                if (!floor.getType().isSolid() || isVisualBlock(floor) || chestTag(floor) != null) continue;
                if (minionAt(null, b) || !onOwnIsland(owner, b.getLocation())) break;
                return b;
            }
        }
        return null;
    }

    private ArmorStand spawnPresent(World w, int x, int y, int z, String profileId) {
        Location at = MinionVisuals.standLocation(w, x, y, z, java.util.concurrent.ThreadLocalRandom.current().nextInt(4) * 90f);
        String look = mod.dials.winterPresentItem.get();
        ItemDef d = Sky.items().def(look);
        if (d == null || d.skull == null) d = Sky.items().def("RED_GIFT");
        ItemDef head = d;
        return w.spawn(at, ArmorStand.class, as -> {
            as.setSmall(true);
            as.setInvisible(true);
            as.setBasePlate(false);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setPersistent(false);
            as.setSilent(true);
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
                    EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
                as.addEquipmentLock(s, ArmorStand.LockType.ADDING_OR_CHANGING);
            as.getEquipment().setHelmet(head != null && head.skull != null
                    ? com.skyclone.api.ui.Icons.skull(head.skull, " ", List.of()) : new ItemStack(Material.RED_WOOL));
            as.customName(Text.c("&c&lPresent &7(click to open)"));
            as.setCustomNameVisible(true);
            as.getPersistentDataContainer().set(mod.visuals.presentKey, PersistentDataType.STRING, profileId + "|present");
        });
    }

    void removePresent(String profileId) {
        UUID u = presentStands.remove(profileId);
        Entity e = u == null ? null : Bukkit.getEntity(u);
        if (e != null) e.remove();
    }

    /** Click on a present: a member of its island opens it (claimed in the data BEFORE the item is handed over). */
    void claimPresent(Player p, String profileId, Entity ent) {
        if (!Sky.profiles().isReady(p)) return;
        if (!profileId.equals(Sky.profiles().profileId(p))) { Msg.error(p, "This present is for someone else!"); return; }
        if (rateLimited(p)) return;
        MinionStore s = store(p);
        if (s.presentWorld == null) { ent.remove(); presentStands.remove(profileId); return; }
        String item = mod.dials.winterPresentItem.get();
        int n = Math.max(1, mod.dials.winterPresentAmount.get());
        if (item == null || !Sky.items().exists(item)) { Msg.error(p, "The present won't open yet (" + item + " is not on this server)."); return; }
        if (new InvSpace(p).room(item) < n) { Msg.error(p, "Your inventory is full!"); return; }
        s.presentWorld = null;
        s.presentAt = 0;
        removePresent(profileId);
        if (ent.isValid()) ent.remove();
        Sky.loot().give(p, item, n, LootSource.REWARD);
        Msg.success(p, "You opened the island present: " + (n > 1 ? n + "x " : "") + Text.strip(name(item)) + "!");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
        Sky.profiles().saveNow(p);
    }

    // ---- quick upgrade ------------------------------------------------------------------------------------

    /** The crafting recipe from this tier to the next, or null (trade tiers). */
    Recipe nextRecipe(Placed m) {
        TypeDef t = cat.type(m.type);
        if (t == null || t.tier(m.tier + 1) == null) return null;
        String cur = t.itemId(m.tier);
        for (Recipe r : Sky.recipes().producing(t.itemId(m.tier + 1))) {
            if (r.type != Recipe.Type.CRAFTING || r.grid == null) continue;
            for (String c : r.grid) if (c != null && cur.equals(cellId(c))) return r;
        }
        return null;
    }

    /** Ingredients of the next tier minus the minion itself. */
    Map<String, Integer> upgradeCost(Recipe r, String currentItem) {
        Map<String, Integer> need = new LinkedHashMap<>();
        for (String c : r.grid) {
            if (c == null || c.isEmpty()) continue;
            String id = cellId(c);
            if (id.equals(currentItem)) continue;
            need.merge(id, cellAmount(c), Integer::sum);
        }
        return need;
    }

    static String cellId(String c) {
        if (Sky.items().exists(c)) return c;
        int i = c.lastIndexOf(':');
        if (i > 0) {
            try { Integer.parseInt(c.substring(i + 1)); return c.substring(0, i); } catch (NumberFormatException ignored) { }
        }
        return c;
    }

    static int cellAmount(String c) {
        if (Sky.items().exists(c)) return 1;
        int i = c.lastIndexOf(':');
        if (i > 0) {
            try { return Integer.parseInt(c.substring(i + 1)); } catch (NumberFormatException ignored) { }
        }
        return 1;
    }

    int have(Player p, String id) {
        int n = Sky.items().count(p, id);
        if (mod.dials.upgradeFromSacks.get()) n += Sky.service(Features.Sacks.class).count(p, id);
        return n;
    }

    String quickUpgrade(Player p, Placed m) {
        if (!canManage(p, m)) return "&cVisit your island to upgrade this minion!";
        TypeDef t = cat.type(m.type);
        if (t == null) return null;
        if (t.tier(m.tier + 1) == null) return "&aThis minion is already at its highest tier!";
        Recipe r = nextRecipe(m);
        if (r == null) return "&eTier " + Text.roman(m.tier + 1) + " is not crafted: trade for it with the minion smith NPCs, or use an upgrade stone.";
        if (!Sky.recipes().unlocked(p, r)) {
            Sky.requirements().checkOrTell(p, r.unlock);
            return "";
        }
        Map<String, Integer> need = upgradeCost(r, t.itemId(m.tier));
        for (Map.Entry<String, Integer> e : need.entrySet())
            if (have(p, e.getKey()) < e.getValue()) return "&cYou need &e" + e.getValue() + "x " + name(e.getKey()) + " &c(you have " + have(p, e.getKey()) + ").";
        // take everything (inventory first, then sacks); refund all on a shortfall
        Map<String, Integer> taken = new LinkedHashMap<>();
        boolean ok = true;
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            int left = e.getValue();
            int a = Sky.items().remove(p, e.getKey(), left);
            left -= a;
            taken.merge(e.getKey() + "|inv", a, Integer::sum);
            if (left > 0 && mod.dials.upgradeFromSacks.get()) {
                int b = Sky.service(Features.Sacks.class).remove(p, e.getKey(), left);
                left -= b;
                taken.merge(e.getKey() + "|sack", b, Integer::sum);
            }
            if (left > 0) { ok = false; break; }
        }
        if (!ok) {
            for (Map.Entry<String, Integer> e : taken.entrySet()) {
                String[] k = e.getKey().split("\\|");
                if (e.getValue() <= 0) continue;
                if ("sack".equals(k[1])) Sky.service(Features.Sacks.class).add(p, k[0], e.getValue());
                else giveItems(p, k[0], e.getValue(), LootSource.STORAGE);
            }
            return "&cSomething changed in your inventory - nothing was used.";
        }
        m.tier++;
        markCrafted(p, m.type, m.tier, true);
        refreshBody(m);
        Msg.sound(p, Sound.BLOCK_ANVIL_USE, 1.4f);
        Sky.profiles().saveNow(p);
        return "&aYour minion is now " + MinionText.name(t, m.tier) + "&a!";
    }

    // ---- crystals ---------------------------------------------------------------------------------------------

    void tryPlaceCrystal(Player p, ItemStack hand, EquipmentSlot slot, Block clicked, BlockFace face) {
        String id = Sky.items().idOf(hand);
        UpgradeDef u = cat.upgrade(id);
        if (!MinionCatalog.isDeployable(u)) return;
        if (!Sky.profiles().isReady(p) || rateLimited(p)) return;
        boolean beacon = "beacon".equals(u.slot);
        Block target = clicked.getRelative(face == null ? BlockFace.UP : face);
        if (!target.isEmpty() && !target.isPassable()) { Msg.error(p, "You can't place that there!"); return; }
        if (!onOwnIsland(p, target.getLocation())) {
            Msg.error(p, (beacon ? "Beacons" : "Crystals") + " can only be placed on your own island!");
            return;
        }
        if (u.requirement != null && !Sky.requirements().checkOrTell(p, u.requirement)) return;
        MinionStore s = store(p);
        for (Crystal c : s.crystals.values()) {
            if (c.item.equals(id)) { Msg.error(p, "You already have a " + Text.strip(name(id)) + " on your island!"); return; }
            UpgradeDef other = cat.upgrade(c.item);
            if (beacon && other != null && "beacon".equals(other.slot)) {
                Msg.error(p, "Your island already has a Beacon! Pick it up first to place a different tier.");
                return;
            }
        }
        ItemStack cur = p.getInventory().getItem(slot);
        if (cur == null || !id.equals(Sky.items().idOf(cur))) return;
        if (cur.getAmount() > 1) cur.setAmount(cur.getAmount() - 1); else p.getInventory().setItem(slot, null);
        Crystal c = new Crystal();
        c.id = UUID.randomUUID().toString();
        c.item = id;
        c.world = target.getWorld().getName();
        c.x = target.getX();
        c.y = target.getY();
        c.z = target.getZ();
        c.placedAt = System.currentTimeMillis();
        s.crystals.put(c.id, c);
        if (beacon && System.currentTimeMillis() >= s.beaconPoweredUntil) {
            Msg.success(p, "Placed " + Text.strip(name(id)) + "! Power it with a Power Crystal from its menu.");
        } else {
            Msg.success(p, "Placed " + Text.strip(name(id)) + "! Your minions feel its power.");
        }
        Sky.profiles().saveNow(p);
    }

    // ---- Beacon profile stat ---------------------------------------------------------------------------

    /** Stats a Beacon can boost, in the order the menu cycles them. */
    static final com.skyclone.api.stat.Stat[] BEACON_STATS = {
            com.skyclone.api.stat.Stat.STRENGTH, com.skyclone.api.stat.Stat.DEFENSE,
            com.skyclone.api.stat.Stat.HEALTH, com.skyclone.api.stat.Stat.INTELLIGENCE,
            com.skyclone.api.stat.Stat.CRIT_DAMAGE, com.skyclone.api.stat.Stat.SPEED};

    static com.skyclone.api.stat.Stat beaconStatOf(MinionStore s) {
        if (s == null || s.beaconStat == null) return null;
        for (com.skyclone.api.stat.Stat st : BEACON_STATS) if (st.name().equals(s.beaconStat)) return st;
        return null;
    }

    /**
     * What the island's Beacon adds to its chosen stat right now: tier x dial (Health x2), x the Scorched
     * multiplier while a Scorched Power Crystal runs it, 0 when unpowered, unplaced or no stat chosen.
     */
    double beaconStatValue(MinionStore s, long now) {
        com.skyclone.api.stat.Stat st = beaconStatOf(s);
        if (st == null || now >= s.beaconPoweredUntil) return 0;
        Crystal b = beacon(s);
        UpgradeDef u = b == null ? null : cat.upgrade(b.item);
        if (u == null) return 0;
        double v = Math.max(1, u.tier) * Math.max(0, mod.dials.beaconStatPerTier.get());
        if (st == com.skyclone.api.stat.Stat.HEALTH) v *= 2;
        if (s.beaconScorched) v *= Math.max(0, mod.dials.beaconScorchedStat.get());
        return Math.round(v * 10) / 10.0;
    }

    /** StatProvider: every member of a profile whose island Beacon is powered gets its chosen stat. */
    void contributeBeaconStat(Player p, com.skyclone.api.stat.StatMap into) {
        try {
            if (!Sky.profiles().isReady(p)) return;
            MinionStore s = store(p);
            if (s == null || s.beaconStat == null || s.crystals.isEmpty()) return;
            double v = beaconStatValue(s, System.currentTimeMillis());
            com.skyclone.api.stat.Stat st = beaconStatOf(s);
            if (v > 0 && st != null) into.add(st, v);
        } catch (Throwable ignored) { }
    }

    /** Menu: switch the Beacon to the next stat. Anyone in the co-op may; the choice is the island's. */
    String cycleBeaconStat(Player p) {
        MinionStore s = store(p);
        com.skyclone.api.stat.Stat cur = beaconStatOf(s);
        int i = 0;
        if (cur != null) for (int k = 0; k < BEACON_STATS.length; k++) if (BEACON_STATS[k] == cur) i = k + 1;
        com.skyclone.api.stat.Stat next = BEACON_STATS[i % BEACON_STATS.length];
        s.beaconStat = next.name();
        Sky.profiles().saveNow(p);
        for (Player o : Bukkit.getOnlinePlayers()) {
            try { if (store(o) == s) Sky.stats().invalidate(o); } catch (Throwable ignored) { }
        }
        return "&aYour Beacon now boosts " + next.label() + "&a.";
    }

    /** The island's placed Beacon, or null. */
    Crystal beacon(MinionStore s) {
        for (Crystal c : s.crystals.values()) {
            UpgradeDef u = cat.upgrade(c.item);
            if (u != null && "beacon".equals(u.slot)) return c;
        }
        return null;
    }

    /**
     * Put one Power Crystal (or Scorched Power Crystal) into the island's Beacon. Power is the island's, not
     * the beacon's, so swapping beacon tiers keeps it. A crystal powers for up to 48 h "at a time": a new
     * one only goes in once the last has run out (nothing is wasted by a misclick).
     */
    String powerBeacon(Player p, String crystalItem) {
        UpgradeDef u = cat.upgrade(crystalItem);
        if (u == null || !"power".equals(u.slot)) return "&cThat does not power a Beacon.";
        MinionStore s = store(p);
        Crystal b = beacon(s);
        if (b == null) return "&cPlace a Beacon on your island first!";
        if (!canManageAt(p, b.world, b.x, b.y, b.z)) return "&cVisit your island to power its Beacon!";
        long now = System.currentTimeMillis();
        if (now < s.beaconPoweredUntil) return "&cThe Beacon is still powered for &e" + Text.duration((s.beaconPoweredUntil - now) / 1000) + "&c.";
        if (Sky.items().remove(p, crystalItem, 1) != 1) return "&cYou don't have a " + Text.strip(name(crystalItem)) + "&c!";
        s.beaconPoweredUntil = now + Math.max(1, u.seconds) * 1000L;
        s.beaconScorched = u.speed > 0;
        Msg.sound(p, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f);
        Sky.profiles().saveNow(p);
        return "&aThe Beacon hums to life for &e" + Text.duration(Math.max(1, u.seconds)) + "&a!";
    }

    boolean canManageAt(Player p, String world, int x, int y, int z) {
        if (mod.dials.remoteAccess.get() || Sky.worlds().buildBypass(p)) return true;
        World w = Bukkit.getWorld(world);
        return w != null && p.getWorld().equals(w) && p.getLocation().distanceSquared(new Location(w, x, y, z)) < 160 * 160;
    }

    boolean pickupCrystal(Player p, String crystalId) {
        MinionStore s = store(p);
        Crystal existing = s.crystals.get(crystalId);
        if (existing == null) return false;
        if (!canManageAt(p, existing.world, existing.x, existing.y, existing.z)) {
            Msg.error(p, "Visit your island to pick that up!");
            return false;
        }
        if (new InvSpace(p).room(existing.item) < 1) { Msg.error(p, "Your inventory is full!"); return false; }
        Crystal c = s.crystals.remove(crystalId);
        if (c == null) return false;
        UUID u = crystalStands.remove(crystalId);
        if (u != null) { Entity e = Bukkit.getEntity(u); if (e != null) e.remove(); }
        if (Sky.items().exists(c.item)) giveRaw(p, Sky.items().create(c.item));
        Msg.send(p, "&aPicked up " + name(c.item) + "&a.");
        Sky.profiles().saveNow(p);
        return true;
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    static String name(String itemId) {
        ItemDef d = Sky.items().def(itemId);
        if (d == null) return "&f" + Text.titleCase(itemId == null ? "?" : itemId);
        return d.rarity.code() + d.name;
    }
}
