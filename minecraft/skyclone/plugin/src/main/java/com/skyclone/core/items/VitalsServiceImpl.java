package com.skyclone.core.items;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.player.VitalsService;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Layout;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Custom health and mana. The pool lives here (persisted as member data "vitals"); vanilla hearts
 * are only a gauge of it, and vanilla never kills a player: health reaching 0 calls {@link #kill}.
 *
 * <p>Damage convention for the combat core: consume absorption first
 * ({@code addAbsorption(p, -x)}), then {@code setHealth(p, health - rest)}, then
 * {@code kill(p, cause)} when it hit 0. A {@code setHealth(<= 0)} without a kill call is caught
 * at the end of the tick and kills with the last known cause, so nobody stands around at 0 HP.
 */
public final class VitalsServiceImpl implements VitalsService {

    private final ItemServiceImpl items;
    private final StatServiceImpl stats;
    private final Map<UUID, VitalsData> state = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHurt = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingKill = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> dyingTick = new HashMap<>();
    private final Map<UUID, Flash> flashes = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastScale = new HashMap<>();
    private int ticks;
    private double regenAccumulator;

    private static final String MID_SENTINEL = "@@MID@@";

    private record Flash(String legacy, Component component, long untilTick) {}

    public VitalsServiceImpl(ItemServiceImpl items, StatServiceImpl stats) {
        this.items = items;
        this.stats = stats;
    }

    // ---- state -------------------------------------------------------------------------------

    VitalsData st(Player p) {
        return state.computeIfAbsent(p.getUniqueId(), k -> new VitalsData());
    }

    /** ProfileReady: swap the transient state for the persisted member object. */
    void attachProfile(Player p) {
        VitalsData d;
        try {
            d = Sky.profiles().member(p, "vitals", VitalsData.class);
        } catch (RuntimeException e) {
            items.plugin().getLogger().log(Level.WARNING, "[vitals] could not load vitals for " + p.getName(), e);
            return;
        }
        if (d == null) return;
        double max = maxHealth(p), mmax = maxMana(p);
        if (d.health < 0 || Double.isNaN(d.health)) d.health = max;
        if (d.mana < 0 || Double.isNaN(d.mana)) d.mana = mmax;
        if (d.absorption < 0 || Double.isNaN(d.absorption)) d.absorption = 0;
        // Keep the fill fraction the save was taken at, not the absolute value: a relog before
        // every stat provider is back would otherwise clamp 596 HP to the naked 100 for good.
        d.health = VitalsMath.rescale(d.health, d.maxHealth, max);
        if (d.health <= 0) d.health = max;
        d.mana = VitalsMath.rescale(d.mana, d.maxMana, mmax);
        d.maxHealth = max;
        d.maxMana = mmax;
        state.put(p.getUniqueId(), d);
        sync(p);
    }

    void detachProfile(Player p) {
        // the profile object is saved by core; keep a transient copy until the next ProfileReady
        VitalsData d = state.get(p.getUniqueId());
        if (d == null) return;
        VitalsData copy = new VitalsData();
        copy.health = d.health; copy.mana = d.mana; copy.absorption = d.absorption;
        copy.maxHealth = d.maxHealth; copy.maxMana = d.maxMana;
        state.put(p.getUniqueId(), copy);
    }

    void forget(Player p) {
        UUID u = p.getUniqueId();
        state.remove(u);
        lastHurt.remove(u);
        pendingKill.remove(u);
        dyingTick.remove(u);
        flashes.remove(u);
        lastScale.remove(u);
    }

    // ---- API ---------------------------------------------------------------------------------

    @Override
    public double health(Player p) {
        VitalsData d = st(p);
        if (d.health < 0) d.health = maxHealth(p);
        return d.health;
    }

    @Override
    public double maxHealth(Player p) {
        return Math.max(1, stats.get(p, Stat.HEALTH));
    }

    @Override
    public void damage(Player p, double amount, String cause) {
        if (p == null || !(amount > 0)) return;
        double abs = absorption(p);
        if (abs > 0) {
            double take = Math.min(abs, amount);
            addAbsorption(p, -take);
            amount -= take;
        }
        if (amount <= 0) return;
        double hp = health(p) - amount;
        if (hp <= 0) kill(p, cause == null ? "unknown" : cause);
        else setHealth(p, hp);
    }

    @Override
    public void setHealth(Player p, double hp) {
        if (p == null || Double.isNaN(hp)) return;
        double max = maxHealth(p);
        double old = health(p);
        double nv = Math.max(0, Math.min(max, hp));
        if (nv < old) lastHurt.put(p.getUniqueId(), System.currentTimeMillis());
        st(p).health = nv;
        if (nv <= 0) pendingKill.putIfAbsent(p.getUniqueId(), "died");
        sync(p);
    }

    @Override
    public void heal(Player p, double amount) {
        if (p == null || !(amount > 0)) return;
        setHealth(p, health(p) + amount);
    }

    @Override
    public double absorption(Player p) { return Math.max(0, st(p).absorption); }

    @Override
    public void addAbsorption(Player p, double amount) {
        if (p == null || Double.isNaN(amount)) return;
        VitalsData d = st(p);
        d.absorption = Math.max(0, d.absorption + amount);
        sync(p);
    }

    @Override
    public double mana(Player p) {
        VitalsData d = st(p);
        if (d.mana < 0) d.mana = maxMana(p);
        return d.mana;
    }

    /** The genre's flat mana pool before Intelligence. */
    static final double BASE_MANA = 100;

    @Override
    public double maxMana(Player p) {
        // Genre: max mana = 100 + Intelligence (research 01 §2). Stat.INTELLIGENCE.base is 0, so a naked
        // player has 0 Int, 100 mana, and magic damage x1 (the base used to be 100, which doubled it).
        return BASE_MANA + Math.max(0, stats.get(p, Stat.INTELLIGENCE)) + Math.max(0, stats.get(p, Stat.MANA_POOL_BONUS));
    }

    @Override
    public boolean useMana(Player p, double amount) {
        if (p == null || Double.isNaN(amount)) return false;
        if (amount <= 0) return true;
        double m = mana(p);
        if (m + 1e-9 < amount) return false;
        st(p).mana = Math.max(0, m - amount);
        return true;
    }

    @Override
    public void addMana(Player p, double amount) {
        if (p == null || Double.isNaN(amount)) return;
        st(p).mana = Math.max(0, Math.min(maxMana(p), mana(p) + amount));
    }

    @Override
    public void flash(Player p, Component text, int ticks) {
        if (p == null || text == null) return;
        flashes.put(p.getUniqueId(), new Flash(null, text, Bukkit.getCurrentTick() + Math.max(1, ticks)));
        sendBar(p);
    }

    @Override
    public void flash(Player p, String legacy, int ticks) {
        if (p == null || legacy == null) return;
        flashes.put(p.getUniqueId(), new Flash(legacy, null, Bukkit.getCurrentTick() + Math.max(1, ticks)));
        sendBar(p);
    }

    /**
     * The death cause as a player reads it: "mob:hub_graveyard_zombie" -> "killed by Graveyard Zombie",
     * "player:Bob" -> "killed by Bob". The raw cause still goes to SkyDeath and the ledger (integration round 2).
     */
    static String deathCauseText(String why) {
        try {
            if (why.startsWith("mob:")) {
                com.skyclone.api.mob.MobDef d = Sky.mobs().def(why.substring(4));
                if (d != null && d.name != null && !d.name.isBlank()) return "killed by " + Text.strip(d.name);
            } else if (why.startsWith("player:")) {
                return "killed by " + why.substring(7);
            } else if (why.startsWith("entity:")) {
                return "killed by " + why.substring(7).replace('_', ' ');
            } else if (why.equals("mob")) {
                // scripted hits with no attacker (boss skulls, orbs, lasers) arrive as the bare kind (integration round 3)
                return "killed by a monster";
            } else if (why.equals("true")) {
                return "struck down";
            }
            // environmental damage groups (crimson request 9: "You died (lava)")
            String env = switch (why) {
                case "lava" -> "burned in lava";
                case "fire", "fire_tick", "hot_floor" -> "burned";
                case "fall" -> "fell";
                case "drowning" -> "drowned";
                case "suffocation" -> "suffocated";
                case "void" -> "fell into the void";
                case "kill" -> "slain";
                case "lightning" -> "struck by lightning";
                case "contact" -> "pricked to death";
                case "freeze" -> "froze";
                case "starvation" -> "starved";
                case "poison", "wither", "magic" -> "withered away";
                case "block_explosion", "entity_explosion" -> "blown up";
                default -> null;
            };
            if (env != null) return env;
        } catch (RuntimeException ignored) { }
        return why;
    }

    @Override
    public void kill(Player p, String cause) {
        if (p == null || !p.isOnline() || p.isDead()) return;
        UUID u = p.getUniqueId();
        int now = Bukkit.getCurrentTick();
        Integer last = dyingTick.get(u);
        if (last != null && last == now) return;
        dyingTick.put(u, now);
        pendingKill.remove(u);
        String why = cause == null || cause.isBlank() ? "died" : cause;

        double purse = 0;
        try { purse = Sky.economy().purse(p); } catch (RuntimeException ignored) {}
        double loss = safeFromCoinLoss(p) ? 0 : VitalsMath.deathLoss(purse, ItemsDials.num(ItemsDials.DEATH_COIN_LOSS, 0.5));
        SkyEvents.SkyDeath ev = new SkyEvents.SkyDeath(p, why, loss);
        try { Bukkit.getPluginManager().callEvent(ev); } catch (RuntimeException e) {
            items.plugin().getLogger().log(Level.WARNING, "[vitals] SkyDeath listener threw", e);
        }
        double lose = Math.max(0, Math.min(purse, Double.isNaN(ev.coinsLost) ? 0 : ev.coinsLost));
        // Whole coins: listeners (piggy bank, enchants) scale the loss and leave fractions behind.
        lose = VitalsMath.wholeCoins(lose);
        if (lose > 0) {
            boolean ok = false;
            try { ok = Sky.economy().withdraw(p, lose, "death: " + why); } catch (RuntimeException ignored) {}
            if (!ok) lose = 0;
        }
        String shown = deathCauseText(why);
        if (lose > 0) {
            String coins;
            coins = Text.commasInt(lose);
            Msg.send(p, "&c☠ &7You died &8(" + shown + ")&7 and lost &6" + coins + " &7coins!");
        } else {
            Msg.send(p, "&c☠ &7You died &8(" + shown + ")&7.");
        }

        // instant respawn
        VitalsData d = st(p);
        d.health = d.maxHealth = maxHealth(p);
        d.mana = d.maxMana = maxMana(p);
        d.absorption = 0;
        lastHurt.remove(u);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setVelocity(new Vector(0, 0, 0));
        Location to = ev.respawnAt != null && ev.respawnAt.getWorld() != null ? ev.respawnAt : respawnPoint(p);
        // Synchronous on purpose: an async teleport leaves the player in the void for a tick or more,
        // and every void-damage tick in between would be another death (and another half purse).
        if (to != null) p.teleport(to);
        parkIfLimbo(p, to);
        sync(p);
        sendBar(p);
    }

    // ---- death helpers -------------------------------------------------------------------------

    private boolean safeFromCoinLoss(Player p) {
        // Outside every game world (the void default world that holds limbo) there is nothing to
        // die to but a mistake: an admin /tp from the console lands in the overworld, and four void
        // deaths there took 94% of a purse (integration round 3). No coin loss there.
        try {
            if (Sky.worlds().islandOf(p.getWorld()) == null) return true;
        } catch (RuntimeException ignored) {}
        try {
            Layout.Zone z = Sky.worlds().zoneOf(p);
            if (z != null && z.flags() != null && z.flags().contains(Layout.Zone.SAFE)) return true;
        } catch (RuntimeException ignored) {}
        if (ItemsDials.flag(ItemsDials.DEATH_SAFE_ON_PRIVATE_ISLAND, true)) {
            try {
                if ("private_island".equals(Sky.worlds().islandOf(p.getLocation()))) return true;
            } catch (RuntimeException ignored) {}
            try {
                if (Sky.service(Features.PrivateIsland.class).isOnOwnIsland(p)) return true;
            } catch (RuntimeException ignored) {}
        }
        return false;
    }

    /** core.world keeps a platform at the default (void) world's spawn. */
    private static Location limbo() {
        return org.bukkit.Bukkit.getWorlds().isEmpty() ? null : org.bukkit.Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private static boolean isLimbo(Location l) {
        if (l == null || l.getWorld() == null || Bukkit.getWorlds().isEmpty()) return false;
        if (!l.getWorld().equals(Bukkit.getWorlds().get(0))) return false;
        try { return Sky.worlds().islandOf(l.getWorld()) == null; } catch (RuntimeException e) { return true; }
    }

    /**
     * A player respawned onto the limbo platform must not be stranded there: hand them to
     * {@code sendHome}, which either takes them home or registers them to be released the moment
     * the hub finishes building. Next tick, after the respawn teleport has settled.
     */
    void parkIfLimbo(Player p, Location to) {
        if (!isLimbo(to)) return;
        Bukkit.getScheduler().runTask(items.plugin(), () -> {
            if (!p.isOnline() || p.isDead()) return;
            try { Sky.worlds().sendHome(p); } catch (RuntimeException e) {
                items.plugin().getLogger().log(Level.WARNING, "[vitals] sendHome after a limbo respawn threw", e);
            }
        });
    }

    /** Is there something to stand on at or below this spot (solid or liquid, within a few blocks)? */
    private static boolean hasFloor(Location l) {
        if (l == null || l.getWorld() == null) return false;
        org.bukkit.World w = l.getWorld();
        try {
            return VitalsMath.hasFloor(l.getBlockY(), w.getMinHeight(), VitalsMath.FLOOR_SEARCH_DEPTH, y -> {
                org.bukkit.block.Block b = w.getBlockAt(l.getBlockX(), y, l.getBlockZ());
                return b.getType().isSolid() || b.isLiquid();
            });
        } catch (RuntimeException e) {
            return true;    // cannot look (should not happen on the main thread): do not second-guess the anchor
        }
    }

    /**
     * Where a death respawns. Never null while a world exists, and never mid-air:
     * <ol>
     *   <li>a shared, built island → its {@code spawn} anchor;</li>
     *   <li>the private island → {@code PrivateIsland.home};</li>
     *   <li>another instanced island → the {@code spawn} of the slot the player died in (the slot is
     *       found from the position, so no key is needed);</li>
     *   <li>anything else (the default world, an unbuilt island, a spawn with nothing under it) →
     *       home: the private island, else the hub spawn, else limbo (and then {@code sendHome}
     *       queues them for the hub).</li>
     * </ol>
     * Integration round 3: a death in the default world respawned on the 7x7 limbo platform with
     * no way out; a teleport-sword click there went straight back into the void, four times.
     */
    Location respawnPoint(Player p) {
        try {
            String island = Sky.worlds().islandOf(p.getWorld());
            if (island != null) {
                Location at = islandRespawn(p, island);
                if (at != null && hasFloor(at)) return at;
            }
        } catch (RuntimeException e) {
            items.plugin().getLogger().log(Level.FINE, "[vitals] island respawn failed", e);
        }
        return homePoint(p);
    }

    private Location islandRespawn(Player p, String island) {
        IslandDef def = Sky.worlds().island(island);
        if (def == null) return null;
        if (def.instanced) {
            if ("private_island".equals(island)) {
                if (!Sky.has(Features.PrivateIsland.class)) return null;
                return Sky.service(Features.PrivateIsland.class).home(p);
            }
            Layout layout = Sky.worlds().layout(island);
            Layout.Anchor a = layout == null ? null : layout.anchor("spawn");
            if (a == null) return null;
            Location l = p.getLocation();
            double ox = VitalsMath.slotOrigin(l.getX(), def.instanceSpacing);
            double oz = VitalsMath.slotOrigin(l.getZ(), def.instanceSpacing);
            return new Location(p.getWorld(), ox + a.x(), a.y(), oz + a.z(), a.yaw(), a.pitch());
        }
        // Blocks not placed yet (first boot, rebuild): the anchor is mid-air and respawning there is
        // a void-death loop.
        if (!Sky.worlds().isBuilt(island)) return null;
        return Sky.worlds().anchor(island, "spawn");
    }

    /** sendHome's destination, computed synchronously: private island home, else the hub spawn, else limbo. */
    private Location homePoint(Player p) {
        try {
            if (Sky.has(Features.PrivateIsland.class)) {
                Location home = Sky.service(Features.PrivateIsland.class).home(p);
                if (home != null && hasFloor(home)) return home;
            }
        } catch (RuntimeException e) {
            items.plugin().getLogger().log(Level.FINE, "[vitals] PrivateIsland.home failed", e);
        }
        try {
            String hubIsland = "hub", hubAnchor = "spawn";
            for (com.skyclone.api.world.WorldService.WarpInfo w : Sky.worlds().warpInfos()) {
                if ("hub".equals(w.id())) { hubIsland = w.island(); hubAnchor = w.anchor() == null ? "spawn" : w.anchor(); break; }
            }
            if (Sky.worlds().isBuilt(hubIsland)) {
                Location hub = Sky.worlds().anchor(hubIsland, hubAnchor);
                if (hub == null) hub = Sky.worlds().anchor(hubIsland, "spawn");
                if (hub != null && hasFloor(hub)) return hub;
            }
        } catch (RuntimeException e) {
            items.plugin().getLogger().log(Level.FINE, "[vitals] hub respawn failed", e);
        }
        return limbo();
    }

    // ---- ticking -------------------------------------------------------------------------------

    /** Every tick from ItemsCore. */
    void tick() {
        ticks++;
        if (!pendingKill.isEmpty()) {
            for (Map.Entry<UUID, String> e : Map.copyOf(pendingKill).entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                pendingKill.remove(e.getKey());
                if (p != null && p.isOnline() && health(p) <= 0) kill(p, e.getValue());
            }
        }
        if (ticks % 20 == 0) manaRegen();
        double regenTicks = Math.max(1, ItemsDials.num(ItemsDials.REGEN_SECONDS, 2) * 20);
        regenAccumulator++;
        if (regenAccumulator >= regenTicks) {
            regenAccumulator = 0;
            healthRegen();
        }
        if (ticks % 5 == 0 && ItemsDials.flag(ItemsDials.ACTION_BAR, true)) {
            for (Player p : Bukkit.getOnlinePlayers()) sendBar(p);
        }
    }

    private void manaRegen() {
        double pct = ItemsDials.num(ItemsDials.MANA_REGEN_PCT, 2);
        for (Player p : Bukkit.getOnlinePlayers()) {
            double max = maxMana(p);
            double m = mana(p);
            if (m >= max) { if (m > max) st(p).mana = max; continue; }
            addMana(p, VitalsMath.manaRegenPerSecond(max, stats.get(p, Stat.MANA_REGEN), pct));
        }
    }

    private void healthRegen() {
        double mult = ItemsDials.num(ItemsDials.REGEN_MULT, 1.0);
        double ooc = ItemsDials.num(ItemsDials.REGEN_OUT_OF_COMBAT_MULT, 1.0);
        long combatMs = (long) (ItemsDials.num(ItemsDials.COMBAT_SECONDS, 5) * 1000);
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            double max = maxHealth(p);
            double hp = health(p);
            if (hp >= max || hp <= 0) continue;
            Long hurt = lastHurt.get(p.getUniqueId());
            boolean inCombat = hurt != null && now - hurt < combatMs;
            double amt = VitalsMath.healthRegen(max, stats.get(p, Stat.HEALTH_REGEN), mult * (inCombat ? 1.0 : ooc));
            if (amt > 0) {
                st(p).health = Math.min(max, hp + amt);
                sync(p);
            }
        }
    }

    /**
     * Called by StatServiceImpl after each recompute: carry health and mana to the new maxima by
     * FRACTION ({@link VitalsMath#rescale}), then update the gauge. Clamping instead (the old
     * behaviour) made every armour swap a cut to the naked max: armour off took 596/596 to 117/117,
     * and putting a set back on left 117/596 for regen to grind back (playtest-loops). Scaling the
     * fraction is not a heal - off and back on returns exactly the starting fraction.
     */
    void onStatsChanged(Player p) {
        VitalsData d = st(p);
        double max = maxHealth(p), mmax = maxMana(p);
        if (d.health >= 0) d.health = VitalsMath.rescale(d.health, d.maxHealth, max);
        if (d.mana >= 0) d.mana = VitalsMath.rescale(d.mana, d.maxMana, mmax);
        d.maxHealth = max;
        d.maxMana = mmax;
        sync(p);
    }

    /** Push the custom pool onto the vanilla hearts gauge. */
    void sync(Player p) {
        if (p == null || !p.isOnline() || p.isDead()) return;
        try {
            double max = maxHealth(p);
            double hp = health(p);
            double vmax = vanillaMax(p);
            double target = VitalsMath.gauge(hp, max, vmax);
            if (Math.abs(p.getHealth() - target) > 0.01) p.setHealth(target);
            double scale = VitalsMath.heartScale(max, ItemsDials.num(ItemsDials.HEART_SCALE_MAX, 40));
            Double ls = lastScale.get(p.getUniqueId());
            if (ls == null || ls != scale || !p.isHealthScaled()) {
                p.setHealthScaled(true);
                p.setHealthScale(scale);
                lastScale.put(p.getUniqueId(), scale);
            }
            double abs = absorption(p);
            double vabs = abs <= 0 ? 0 : Math.min(vmax, Math.max(1, vmax * abs / max));
            if (vabs > 0) {
                AttributeInstance ma = p.getAttribute(Attribute.MAX_ABSORPTION);
                if (ma != null && ma.getBaseValue() < vmax) ma.setBaseValue(vmax);
            }
            if (Math.abs(p.getAbsorptionAmount() - vabs) > 0.01) p.setAbsorptionAmount(vabs);
        } catch (RuntimeException e) {
            items.warnThrottled("vitals-sync", "vitals sync failed: " + e);
        }
    }

    private static double vanillaMax(Player p) {
        try {
            AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
            double v = a == null ? 20 : a.getValue();
            return v > 0 ? v : 20;
        } catch (RuntimeException e) {
            return 20;
        }
    }

    void sendBar(Player p) {
        if (!ItemsDials.flag(ItemsDials.ACTION_BAR, true)) return;
        Flash f = flashes.get(p.getUniqueId());
        String mid = null;
        Component midC = null;
        if (f != null) {
            if (f.untilTick() < Bukkit.getCurrentTick()) flashes.remove(p.getUniqueId());
            else { mid = f.legacy(); midC = f.component(); }
        }
        double def = stats.get(p, Stat.DEFENSE);
        String tail = segmentsFor(p);
        if (midC != null) {
            String hpPart = VitalsMath.actionBar(health(p), maxHealth(p), absorption(p), def, mana(p), maxMana(p), MID_SENTINEL);
            String[] parts = hpPart.split(java.util.regex.Pattern.quote(MID_SENTINEL), 2);
            p.sendActionBar(Text.c(parts[0]).append(midC).append(Text.c((parts.length > 1 ? parts[1] : "") + tail)));
        } else {
            p.sendActionBar(Text.c(VitalsMath.actionBar(health(p), maxHealth(p), absorption(p), def, mana(p), maxMana(p), mid) + tail));
        }
    }

    private record Segment(String id, int priority, java.util.function.Function<Player, String> fn) {}
    private final java.util.List<Segment> segments = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void actionBarSegment(String id, int priority, java.util.function.Function<Player, String> fn) {
        if (id == null) return;
        segments.removeIf(s -> s.id().equals(id));
        if (fn == null) return;
        segments.add(new Segment(id, priority, fn));
        segments.sort(java.util.Comparator.comparingInt(Segment::priority));
    }

    /** "     <seg>     <seg>" for every segment that returns non-blank text. */
    private String segmentsFor(Player p) {
        if (segments.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            String t;
            try { t = s.fn().apply(p); } catch (RuntimeException e) { items.warnThrottled("bar:" + s.id(), "action bar segment '" + s.id() + "' threw " + e); continue; }
            if (t != null && !t.isBlank()) sb.append("     ").append(t);
        }
        return sb.toString();
    }

    /** A player was hurt by something outside the vitals API (vanilla damage net). */
    void markHurt(Player p) { lastHurt.put(p.getUniqueId(), System.currentTimeMillis()); }
}
