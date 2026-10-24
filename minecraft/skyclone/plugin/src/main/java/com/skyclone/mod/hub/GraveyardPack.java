package com.skyclone.mod.hub;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.config.Dials;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the Graveyard survivable for a day-one player (research 03: "no armour, 100 HP").
 *
 * <p>Integration round found a fresh player killed within about 3 s at night, even with 320 Defense:
 * one zombie did the expected 20 per hit, but about ten were hitting at once. Three things stacked up:
 * <ol>
 *   <li><b>Vanilla follow range.</b> A ZOMBIE notices a player at 35 blocks, and hitting one alerts every
 *       zombie inside that range. Across a 98 x 104 field that is about 40% of the field, so most of the
 *       night's 24 mobs converged on one player and ended up stacked on the spot where he died.
 *       Core's own {@code mob.aggro-range} (16) only covers mobs that are not hostile in vanilla.</li>
 *   <li><b>No limit on attackers.</b> Nothing stopped the whole converged pack from swinging at once.</li>
 *   <li><b>The arrival point is inside the spawn area.</b> {@code /warp graveyard} lands at the west gate,
 *       where mobs may already be standing.</li>
 * </ol>
 * So every mob carrying this behaviour (the Graveyard Zombie and the Zombie Villager):
 * <ul>
 *   <li>notices players at {@code hub.graveyard-aggro-range} (16) instead of 35 (FOLLOW_RANGE also bounds the
 *       vanilla "alert others" call), and gives up a chase past that range + 8;</li>
 *   <li>at most {@code hub.graveyard-max-chasers} (3) of them chase one player at once: the nearest ones.
 *       The rest are HELD ({@code setAware(false)}: they stand still and cannot swing) until a slot frees.
 *       A mob the player hits is "engaged" for 8 s and always fights back, so you can pick fights;</li>
 *   <li>never chase or hit a player standing within {@code hub.graveyard-gate-radius} (6) of the
 *       {@code warp.graveyard} arrival point, so arriving is never a death sentence.</li>
 * </ul>
 * The decision itself is {@link #decide}, pure, so a unit test can pin it.
 *
 * <p><b>Two packs.</b> The same rule runs a second instance, {@link #CRYPT} ({@code hub_crypt_pack}), on the
 * Crypt Ghouls and the Golden Ghoul. Playtest "loops" (2026-10-03): {@code /warp crypt} landed a 600 HP /
 * 700 Defense player inside the ghoul pack and he died within a second, twice. The ghouls are vanilla
 * zombies too (35-block follow range, "alert others"), at 350 and 800 damage a hit. The generator now gives
 * the Crypt a SAFE landing round {@code warp.crypt} (no spawns, no targeting); this pack keeps the first
 * step out of it from pulling the whole Crypt: aggro {@code hub.crypt-aggro-range} (12, corridors), at most
 * {@code hub.crypt-max-chasers} (3) at once, and a quiet radius {@code hub.crypt-gate-radius} (8) round the
 * landing. Each pack has its own members, dials and arrival anchor.
 */
final class GraveyardPack implements MobBehaviour {

    static final String ID = "hub_graveyard_pack";
    static final String CRYPT_ID = "hub_crypt_pack";
    /** Seconds a mob the player hit keeps fighting back regardless of the cap. */
    static final long ENGAGE_MS = 8_000;
    /** How far past the aggro range a chase continues before the mob gives up. */
    static final double GIVE_UP_EXTRA = 8;

    Dials.Dial<Double> aggroRange, gateRadius;
    Dials.Dial<Integer> maxChasers;
    /** Graveyard only (null on the Crypt): the newcomer allowance and the Combat level it ramps up to the full cap by. */
    Dials.Dial<Integer> newcomerChasers, newcomerCombat;
    static Dials.Dial<Boolean> tipOn;

    enum Verdict { CHASE, HOLD, DROP }

    /** Behaviour id, the name used in messages, and the arrival anchor. */
    final String id, label, gateAnchor;
    /** Live members (entity uuid -> entity). */
    private final Map<UUID, LivingEntity> pack = new LinkedHashMap<>();
    private final Map<UUID, Long> engagedUntil = new HashMap<>();
    private Location gate;
    private long gateLookedUp;

    /** The Graveyard's pack (Graveyard Zombie, Zombie Villager). */
    static final GraveyardPack INSTANCE = new GraveyardPack(ID, "Graveyard", "warp.graveyard");
    /** The Crypt's pack (Crypt Ghoul, Golden Ghoul). */
    static final GraveyardPack CRYPT = new GraveyardPack(CRYPT_ID, "Crypt", "warp.crypt");
    static final List<GraveyardPack> ALL = List.of(INSTANCE, CRYPT);

    private GraveyardPack(String id, String label, String gateAnchor) {
        this.id = id; this.label = label; this.gateAnchor = gateAnchor;
    }

    static void register() {
        Dials d = Sky.dials();
        GraveyardPack g = INSTANCE;
        g.aggroRange = d.number("hub.graveyard-aggro-range", 16,
                "Blocks at which Graveyard zombies notice a player (vanilla zombies use 35, which pulled the whole field onto one player)");
        g.maxChasers = d.integer("hub.graveyard-max-chasers", 3,
                "Most Graveyard mobs that chase one player at once; the rest wait (0 = no limit). Mobs you hit always fight back");
        g.gateRadius = d.number("hub.graveyard-gate-radius", 6,
                "Graveyard mobs ignore a player this close to the warp.graveyard arrival point (0 = off)");
        g.newcomerChasers = d.integer("hub.graveyard-newcomer-chasers", 1,
                "Graveyard mobs that may chase a Combat 0 player at once; the allowance climbs to hub.graveyard-max-chasers by "
                        + "hub.graveyard-newcomer-combat (0 = no newcomer allowance)");
        g.newcomerCombat = d.integer("hub.graveyard-newcomer-combat", 5,
                "Combat level from which a player faces the full hub.graveyard-max-chasers (the brief's first goal is Combat 5; 0 = off)");
        tipOn = d.flag("hub.graveyard-tip", true,
                "Warn a player below hub.graveyard-newcomer-combat walking into the Graveyard: bank your coins, get armour and a sword first");
        GraveyardPack c = CRYPT;
        c.aggroRange = d.number("hub.crypt-aggro-range", 12,
                "Blocks at which Crypt Ghouls and the Golden Ghoul notice a player (vanilla's 35 pulled the Crypt onto the warp landing)");
        c.maxChasers = d.integer("hub.crypt-max-chasers", 3,
                "Most Crypt ghouls that chase one player at once; the rest wait (0 = no limit). Ghouls you hit always fight back");
        c.gateRadius = d.number("hub.crypt-gate-radius", 8,
                "Crypt ghouls ignore a player this close to the warp.crypt landing (0 = off; the landing itself is also a SAFE zone)");
        for (GraveyardPack p : ALL) {
            p.aggroRange.onChange(v -> p.applyFollowRange());
            Sky.mobs().registerBehaviour(p.id, p);
        }
    }

    // ---- the pure rule ----------------------------------------------------------------------------

    static double giveUpRange(double aggro) { return Math.max(4, aggro) + GIVE_UP_EXTRA; }

    /**
     * How many of the pack may chase THIS player at once. Wave-3 playtest: a new player died six times in
     * the Graveyard (Wooden Sword and no armour, then the iron set and an Iron Sword), half the purse each
     * time, and Combat II took about 40 minutes: three chasers at 20 a hit against 110-125 HP leave no room
     * for a slow reaction. So a newcomer meets one zombie at a time, and the allowance climbs evenly to the
     * full cap by Combat {@code rampLevel}: with the shipped 1 / 3 / 5 that is 1 at Combat 0-2, 2 at 3-4
     * and 3 from Combat 5. Mobs the player hits still always fight back, so anyone can pull more on purpose.
     *
     * @param cap         the pack's full cap ({@code <= 0} = no limit, which an admin chose: kept as is)
     * @param newcomerCap the allowance at Combat 0 ({@code <= 0} = no newcomer allowance)
     * @param rampLevel   the Combat level from which the full cap applies ({@code <= 0} = off)
     */
    static int capFor(int cap, int newcomerCap, int rampLevel, int combat) {
        if (cap <= 0 || newcomerCap <= 0 || rampLevel <= 0 || combat >= rampLevel) return cap;
        int start = Math.min(newcomerCap, cap);
        return start + Math.max(0, combat) * (cap - start) / rampLevel;
    }

    /**
     * What to tell a newcomer walking into the Graveyard. Pure. A death costs half the purse, so banking
     * comes first; armour and a sword only when the player lacks them. Empty = nothing worth saying.
     */
    static List<String> tipLines(double purse, double defense, boolean hasSword) {
        List<String> out = new ArrayList<>();
        if (purse >= 50)
            out.add("&7- Dying here costs &6half your purse &7(" + (long) Math.floor(purse / 2) + " coins right now). "
                    + "Bank them first: the &6Banker &7in the Village (&e/village&7).");
        if (defense < TIP_DEFENSE)
            out.add("&7- You have &a" + (long) Math.max(0, defense) + " Defense&7: the &fArmorsmith &7sells a cheap &firon armour set &7(&e/village&7).");
        if (!hasSword)
            out.add("&7- Fight with a sword: the &fWeaponsmith &7sells them, or craft a Wooden Sword from the Recipe Book.");
        if (!out.isEmpty())
            out.add("&7- Take one zombie at a time and back off to heal; zombies you have not hit leave you alone at the &fwest gate&7.");
        return out;
    }

    /** Below this Defense the Graveyard tip suggests the Armorsmith (a full iron set is well above it). */
    static final double TIP_DEFENSE = 30;

    /**
     * @param dist          distance from the mob to its target player
     * @param targetAtGate  the player is standing on the arrival point
     * @param engaged       the player hit this mob in the last {@link #ENGAGE_MS}
     * @param closerChasers other mobs of the pack, awake and chasing the same player, that are closer to him
     */
    static Verdict decide(double dist, boolean targetAtGate, boolean engaged, int closerChasers, double aggro, int cap) {
        if (dist > giveUpRange(aggro)) return Verdict.DROP;
        if (engaged) return Verdict.CHASE;
        if (targetAtGate) return Verdict.HOLD;
        if (cap > 0 && closerChasers >= cap) return Verdict.HOLD;
        return Verdict.CHASE;
    }

    /** Mobs expected inside a circle of {@code radius} when {@code alive} are spread evenly over {@code area} blocks. */
    static double expectedWithin(int alive, double area, double radius) {
        if (area <= 0) return alive;
        return Math.min(alive, alive * Math.PI * radius * radius / area);
    }

    /**
     * Null if a player arriving at {@code anchor} stands in a SAFE zone (the smallest zone there wins,
     * as in core) and that zone does not swallow the whole spawn {@code area}; otherwise what is wrong.
     * Pure (Layout only), shared by the self-test and the generator unit test.
     */
    static String arrivalProblem(Layout l, String anchor, String area) {
        if (l == null) return "no layout";
        Layout.Anchor a = l.anchor(anchor);
        if (a == null) return anchor + " anchor missing";
        double x = a.x(), y = a.y() + 0.1, z = a.z();
        Layout.Zone best = null;
        for (Layout.Zone zn : l.zones())
            if (zn.box().contains(x, y, z) && (best == null || zn.box().volume() < best.box().volume())) best = zn;
        if (best == null || best.flags() == null || !best.flags().contains(Layout.Zone.SAFE))
            return anchor + " lands in " + (best == null ? "no zone" : "zone " + best.id()) + ", which is not SAFE";
        Box spawn = l.area(area);
        if (spawn == null) return "no " + area + " area";
        Box safe = best.box();
        if (safe.minX() <= spawn.minX() && safe.maxX() >= spawn.maxX() && safe.minZ() <= spawn.minZ() && safe.maxZ() >= spawn.maxZ()
                && safe.minY() <= spawn.minY() && safe.maxY() >= spawn.maxY())
            return "the SAFE zone " + best.id() + " covers the whole " + area + " area: nothing could spawn";
        return null;
    }

    // ---- hooks ----------------------------------------------------------------------------------

    @Override
    public void onSpawn(LivingEntity mob, MobDef def) {
        pack.put(mob.getUniqueId(), mob);
        setFollowRange(mob);
    }

    @Override
    public void onTick(LivingEntity mob, MobDef def, long tick) {
        if (!(mob instanceof Mob m)) return;
        pack.putIfAbsent(mob.getUniqueId(), mob);
        if (!(m.getTarget() instanceof Player p) || !p.isValid() || p.isDead() || p.getWorld() != m.getWorld()) {
            wake(m);
            return;
        }
        switch (verdict(m, p)) {
            case DROP -> { m.setTarget(null); wake(m); }
            case HOLD -> { if (m.isAware()) { m.setAware(false); m.getPathfinder().stopPathfinding(); } }
            case CHASE -> wake(m);
        }
    }

    @Override
    public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
        // the hold is visual; this is the guarantee: a mob outside the pack's allowance never lands a hit
        if (mob instanceof Mob m && verdict(m, target) != Verdict.CHASE) ctx.cancelled = true;
    }

    @Override
    public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null) return;
        engagedUntil.put(mob.getUniqueId(), System.currentTimeMillis() + ENGAGE_MS);
        if (mob instanceof Mob m) {
            wake(m);
            m.setTarget(p);
        }
    }

    @Override
    public void onDeath(LivingEntity mob, MobDef def, Player killer) {
        pack.remove(mob.getUniqueId());
        engagedUntil.remove(mob.getUniqueId());
    }

    // ---- helpers --------------------------------------------------------------------------------

    private Verdict verdict(Mob m, Player p) {
        double aggro = aggroRange.get();
        double give = giveUpRange(aggro);
        double myD2 = m.getLocation().distanceSquared(p.getLocation());
        int closer = 0;
        prune();
        for (LivingEntity e : pack.values()) {
            if (e == m || !(e instanceof Mob o) || !o.isAware() || o.getTarget() != p || o.getWorld() != p.getWorld()) continue;
            double d2 = o.getLocation().distanceSquared(p.getLocation());
            if (d2 <= give * give && d2 <= myD2) closer++;
        }
        return decide(Math.sqrt(myD2), atGate(p), engaged(m), closer, aggro, capFor(p));
    }

    /** Short cache of each player's Combat level: verdict() runs for every member every tick. */
    private final Map<UUID, long[]> combatCache = new HashMap<>();

    /** This pack's chaser allowance for this player (the newcomer ramp on the Graveyard, the plain cap on the Crypt). */
    int capFor(Player p) {
        int cap = maxChasers.get();
        if (newcomerChasers == null || newcomerCombat == null) return cap;
        return capFor(cap, newcomerChasers.get(), newcomerCombat.get(), combatLevel(p));
    }

    private int combatLevel(Player p) {
        long now = System.currentTimeMillis();
        long[] c = combatCache.get(p.getUniqueId());
        if (c != null && c[1] > now) return (int) c[0];
        int lvl = Integer.MAX_VALUE;                     // no skills module or no profile: no newcomer allowance
        if (Sky.has(SkillService.class) && Sky.profiles().isReady(p)) {
            try { lvl = Sky.service(SkillService.class).level(p, SkillType.COMBAT); } catch (RuntimeException ignored) { }
        }
        if (combatCache.size() > 256) combatCache.clear();
        combatCache.put(p.getUniqueId(), new long[]{lvl, now + 5_000});
        return lvl;
    }

    /** Below the Graveyard's newcomer Combat level. */
    static boolean newcomer(Player p) {
        GraveyardPack g = INSTANCE;
        if (g.newcomerCombat == null) return false;
        int ramp = g.newcomerCombat.get();
        return ramp > 0 && g.combatLevel(p) < ramp;
    }

    // ---- the Graveyard tip ----------------------------------------------------------------------

    private static final Map<UUID, Long> tipped = new HashMap<>();
    static final long TIP_EVERY_MS = 20 * 60_000L;

    /** A newcomer walked into the Graveyard: say what keeps them alive (at most once every 20 minutes). */
    static void maybeTip(Player p) {
        if (tipOn == null || !tipOn.get() || !Sky.profiles().isReady(p) || !newcomer(p)) return;
        long now = System.currentTimeMillis();
        Long last = tipped.get(p.getUniqueId());
        if (last != null && now - last < TIP_EVERY_MS) return;
        double defense = 0;
        try { defense = Sky.stats().get(p, Stat.DEFENSE); } catch (RuntimeException ignored) { }
        List<String> lines = tipLines(Sky.economy().purse(p), defense, hasSword(p));
        if (lines.isEmpty()) return;
        tipped.put(p.getUniqueId(), now);
        Msg.send(p, "&c&lGRAVEYARD! &7Zombies hit hard here. Before you fight:");
        for (String l : lines) Msg.send(p, l);
    }

    private static boolean hasSword(Player p) {
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getStorageContents())
            if (it != null && it.getType().name().endsWith("_SWORD")) return true;
        return false;
    }

    static void forget(UUID id) {
        tipped.remove(id);
        for (GraveyardPack g : ALL) g.combatCache.remove(id);
    }

    private boolean engaged(Mob m) {
        Long until = engagedUntil.get(m.getUniqueId());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) { engagedUntil.remove(m.getUniqueId()); return false; }
        return true;
    }

    private boolean atGate(Player p) {
        double r = gateRadius.get();
        if (r <= 0) return false;
        long now = System.currentTimeMillis();
        if (gate == null && now - gateLookedUp > 10_000) {
            gateLookedUp = now;
            try { gate = Sky.worlds().anchor(HubModule.ISLAND, gateAnchor); } catch (RuntimeException ignored) { }
        }
        return gate != null && gate.getWorld() == p.getWorld() && gate.distanceSquared(p.getLocation()) <= r * r;
    }

    private static void wake(Mob m) { if (!m.isAware()) m.setAware(true); }

    private void prune() {
        pack.values().removeIf(e -> !e.isValid() || e.isDead());
        if (engagedUntil.size() > pack.size() + 16) engagedUntil.keySet().retainAll(pack.keySet());
    }

    private void setFollowRange(LivingEntity mob) {
        AttributeInstance fr = mob.getAttribute(Attribute.FOLLOW_RANGE);
        if (fr != null) fr.setBaseValue(Math.max(4, aggroRange.get()));
    }

    void applyFollowRange() {
        prune();
        for (LivingEntity e : pack.values()) setFollowRange(e);
    }

    /** Module disable: never leave a frozen mob behind. */
    void wakeAll() {
        for (LivingEntity e : pack.values()) if (e instanceof Mob m && e.isValid()) wake(m);
    }

    /** The arrival anchor resolved (for the self-test); looks it up now if it never was. */
    boolean gateResolved() {
        if (gate == null) {
            try { gate = Sky.worlds().anchor(HubModule.ISLAND, gateAnchor); } catch (RuntimeException ignored) { }
        }
        return gate != null;
    }

    /** For {@code /sb hub pack}: members, held, and who is being chased by how many. */
    List<String> report() {
        prune();
        int held = 0;
        Map<String, Integer> chasing = new LinkedHashMap<>();
        for (LivingEntity e : pack.values()) {
            if (!(e instanceof Mob m)) continue;
            if (!m.isAware()) held++;
            else if (m.getTarget() instanceof Player p) chasing.merge(p.getName(), 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        out.add("&e" + label + " &7pack &f" + pack.size() + " &7held &f" + held + " &7aggro &f" + aggroRange.get() + " &7max chasers &f"
                + maxChasers.get()
                + (newcomerChasers == null ? "" : " &7(newcomers &f" + newcomerChasers.get() + "&7, full at Combat &f" + newcomerCombat.get() + "&7)")
                + " &7gate radius &f" + gateRadius.get() + " &7gate " + (gate == null ? "&cunresolved" : "&aok"));
        chasing.forEach((n, c) -> {
            Player pl = org.bukkit.Bukkit.getPlayerExact(n);
            out.add("&7  " + n + " &fchased by " + c + (pl == null ? "" : " &7(allowed &f" + capFor(pl) + "&7)"));
        });
        return out;
    }
}
