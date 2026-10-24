package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The quest state machine (research 04 sec 6.2):
 * <pre>
 *  IDLE --start(pay)--> COLLECTING --bar full--> SPAWNING --> FIGHTING --boss dead--> IDLE (+XP, loot, meter)
 *                                                  (gathers until you are healed + unhit, max wait; then summons)
 *                                                               |--timeout / death / leash / quit--> FAILED -> IDLE
 *                                                               |--server restart / chunk unload--> READY (re-summon)
 * </pre>
 * Slayer XP is credited on the kill (no trip back to the NPC), which is the one deliberate change from the reference.
 */
final class Quests implements Listener {

    static final String COLLECTING = "COLLECTING", FIGHTING = "FIGHTING", READY = "READY", SPAWNING = "SPAWNING";

    private final SlayerModule mod;
    /** owner uuid -> live fight. */
    final Map<UUID, BossFight> fights = new HashMap<>();
    private final Map<UUID, Long> spawning = new HashMap<>();
    /** Bar full, boss not risen yet (SPAWNING before the summoning animation): where the bar filled and when. */
    private record Gathering(Location near, long since) { }
    private final Map<UUID, Gathering> gathering = new HashMap<>();
    /** player -> last time a mob or player hit them (read only while their boss is gathering). */
    private final Map<UUID, Long> lastHurt = new HashMap<>();
    private final Map<String, Long> taggedCacheAt = new HashMap<>();
    private final Map<String, Boolean> taggedCache = new HashMap<>();

    Quests(SlayerModule mod) { this.mod = mod; }

    SlayerData data(Player p) { return Sky.profiles().member(p, "slayer", SlayerData.class); }

    // ---- unlocks & requirements -------------------------------------------------------------------

    /** null when the type is unlocked, else a coloured reason. */
    String typeLock(Player p, SlayerCatalog.Type t) {
        if (t.unlock == null) return null;
        SlayerData d = data(p);
        if (d.highestTierKilled(t.unlock.type) >= t.unlock.tier) return null;
        SlayerCatalog.Type prev = mod.catalog.type(t.unlock.type);
        String pn = prev == null ? t.unlock.type : prev.bossName(t.unlock.tier);
        return "&cDefeat a &e" + pn + " " + SlayerMath.roman(t.unlock.tier) + " &cto unlock.";
    }

    /** null when this tier can be started, else a coloured reason. */
    String tierLock(Player p, SlayerCatalog.Type t, SlayerCatalog.Tier tier) {
        String l = typeLock(p, t);
        if (l != null) return l;
        if (tier.requirement != null && !tier.requirement.isBlank() && !Sky.requirements().check(p, tier.requirement)) {
            return "&cRequires " + Sky.requirements().describe(tier.requirement) + "&c.";
        }
        return null;
    }

    /** The price before any discount, in the fee currency ({@link #paysMotes}). */
    double basePrice(SlayerCatalog.Type t, SlayerCatalog.Tier tier) {
        double base = tier.cost * Math.max(0, SlayerDials.COST_SCALE.get());
        if ("vampire".equals(t.id) && !paysMotes(t)) base *= Math.max(0, SlayerDials.VAMPIRE_COST_PER_MOTE.get());
        return base;
    }

    /** The price after the mayor's Cut-Rate Contracts (no player discount: admin / shared displays). */
    double price(SlayerCatalog.Type t, SlayerCatalog.Tier tier) { return price(null, t, tier); }

    /**
     * What {@code p} pays: the mayor's Cut-Rate Contracts, then the Hunting attribute Slayer Discount (Paragon
     * shard, wave 5). In Motes for the Bloodfiend once the rift module provides them, else coins.
     */
    double price(Player p, SlayerCatalog.Type t, SlayerCatalog.Tier tier) {
        Features.Mayors m = Sky.service(Features.Mayors.class);
        double disc = m.perkActive("aatrox.slashed_pricing") ? m.perkValue("aatrox.slashed_pricing") : 0;
        return SlayerMath.cost(basePrice(t, tier), disc, p == null ? 0 : attribute(p, "value:slayer_discount"));
    }

    /** A Hunting attribute's applied value (0 without the hunting module or with the dial off). */
    static double attribute(Player p, String key) {
        if (p == null || !SlayerDials.HUNTING_ATTRIBUTES.get()) return 0;
        try {
            return Math.max(0, Sky.service(Features.Hunting.class).attributeLevel(p, key));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Is this slayer's fee paid in Motes? Only the Bloodfiend (research 2.10: Riftstalker quests cost Motes), and only
     * once the rift module provides {@code Features.Rift} (dial {@code slayer.vampire-fee-currency}: auto/motes/coins).
     */
    static boolean paysMotes(SlayerCatalog.Type t) {
        if (t == null || !"vampire".equals(t.id)) return false;
        String mode = SlayerDials.VAMPIRE_FEE_CURRENCY.get();
        if (mode != null && mode.trim().equalsIgnoreCase("coins")) return false;
        return Sky.has(Features.Rift.class);
    }

    /** "&62,000 coins" / "&d2,000 Motes". */
    static String feeText(SlayerCatalog.Type t, double amount) {
        return paysMotes(t) ? "&d" + Text.commasInt(amount) + " Motes" : "&6" + Text.commasInt(amount) + " coins";
    }

    double spawnTarget(SlayerCatalog.Tier tier) {
        return Math.max(1, Math.ceil(tier.spawnXp * Math.max(0.01, SlayerDials.SPAWN_XP_SCALE.get())));
    }

    // ---- start / cancel ---------------------------------------------------------------------------

    /** Start a quest. {@code free} skips the price (admin, tests). Returns true when it started. */
    boolean start(Player p, String typeId, int tierNo, boolean free, boolean auto) {
        if (!Sky.profiles().isReady(p)) return false;
        SlayerCatalog.Type t = mod.catalog.type(typeId);
        SlayerCatalog.Tier tier = t == null ? null : t.tier(tierNo);
        if (tier == null) { Msg.error(p, "That slayer quest does not exist."); return false; }
        SlayerData d = data(p);
        if (d.hasQuest()) { Msg.error(p, "You already have an active slayer quest! Finish or cancel it first."); return false; }
        if (!free) {
            String lock = tierLock(p, t, tier);
            if (lock != null) { Msg.send(p, lock); Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f); return false; }
            double cost = price(p, t, tier);
            boolean motes = paysMotes(t);
            if (motes) {
                if (cost > 0 && !Sky.service(Features.Rift.class).spendMotes(p, (long) Math.ceil(cost), "slayer: start " + t.id + " T" + tierNo)) {
                    Msg.error(p, "You need &d" + Text.commasInt(cost) + " Motes &cto start this quest!");
                    return false;
                }
                d.motesSpent += cost;
            } else {
                if (cost > 0 && !Sky.economy().withdraw(p, cost, "slayer: start " + t.id + " T" + tierNo)) {
                    Msg.error(p, "You need &6" + Text.commas(cost) + " coins &cto start this quest!");
                    return false;
                }
                d.coinsSpent += cost;
            }
            d.questPaid = cost;
            d.questCurrency = motes ? "motes" : "coins";
        } else {
            d.questPaid = 0;
            d.questCurrency = null;
        }
        d.questType = t.id;
        d.questTier = tierNo;
        // Hunting attribute Slayer Progress (Wolverine shard): the quest starts this % complete (never full)
        d.questProgress = SlayerMath.startingProgress(spawnTarget(tier), attribute(p, "value:slayer_progress"));
        d.questState = COLLECTING;
        d.questStarted = System.currentTimeMillis();
        d.questsStarted++;
        Sky.profiles().saveNow(p);
        List<String> box = new ArrayList<>(List.of(
                "&5&l» &7Slay &c" + Text.commas(spawnTarget(tier)) + " Combat XP &7worth of " + t.mobPlural() + ".",
                "&5&l» &7Then defeat the &" + t.color + t.bossName(tierNo) + " " + SlayerMath.roman(tierNo) + "&7."
                        + (auto ? " &8(Auto-Slayer)" : "")));
        if (d.questProgress > 0) {
            box.add("&5&l» &7Slayer Progress: the bar starts at &a" + Text.commasInt(d.questProgress) + " &7Combat XP.");
        }
        if (SlayerDials.SPAWN_MAX_WAIT.get() > 0) {
            box.add("&5&l» &7When the bar fills, step out of the fight and heal: the boss rises once you are ready.");
        }
        Msg.box(p, "&5&lSLAYER QUEST STARTED!", box);
        Fx.sound(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.6f);
        Sky.sidebar().refresh(p);
        return true;
    }

    /** Abandon the quest. No refund (research 2.1 step 7). */
    void cancel(Player p) {
        SlayerData d = data(p);
        if (!d.hasQuest()) { Msg.error(p, "You have no active slayer quest."); return; }
        BossFight f = fights.get(p.getUniqueId());
        if (f != null) endFight(f);
        clearQuest(d);
        d.questsFailed++;
        Msg.send(p, "&cYour slayer quest was cancelled.");
        Sky.sidebar().refresh(p);
    }

    private void clearQuest(SlayerData d) {
        d.questType = null;
        d.questTier = 0;
        d.questProgress = 0;
        d.questState = null;
        d.questPaid = 0;
        d.questCurrency = null;
    }

    // ---- progress -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        LivingEntity mob = e.mob;
        // a boss or minion of a running fight
        BossFight f = mob == null ? null : mod.fightIndex.get(mob.getUniqueId());
        if (f != null) {
            if (f.boss != null && f.boss.getUniqueId().equals(mob.getUniqueId())) {
                if (f.bossDied()) complete(f);
            } else {
                f.minionDied(mob);
                f.minions.remove(mob.getUniqueId());
                mod.fightIndex.remove(mob.getUniqueId());
            }
            return;
        }
        // a monster The Primordial cocooned and hatched (wave 5)
        Cocoon.Hatch hatch = mob == null ? null : mod.cocoon.take(mob.getUniqueId());
        if (hatch != null && hatch.boss()) {
            Player o = Bukkit.getPlayer(hatch.owner());
            SlayerCatalog.Type ht = mod.catalog.type(hatch.type());
            if (o != null && Sky.profiles().isReady(o) && ht != null) {
                data(o).cocoonKills++;
                Msg.send(o, "&5&lCOCOON SLAIN! &7The hatched &" + ht.color + ht.bossName(hatch.tier()) + " " + SlayerMath.roman(hatch.tier()) + " &7dropped:");
                mod.loot.rollCocoon(o, ht, hatch.tier());
            }
            return;
        }
        Player p = e.killer;
        if (p == null || !Sky.profiles().isReady(p)) return;
        MobDef def = e.def;
        if (def != null && def.id.startsWith("slayer_mini_")) {
            data(p).miniKills.merge(def.id, 1, Integer::sum);
            if (hatch != null) {
                data(p).cocoonKills++;
            } else if (mob != null) {
                UUID owner = Sky.mobs().owner(mob);
                if (owner == null || owner.equals(p.getUniqueId())) {
                    SlayerCatalog.Type mt = mod.catalog.type(def.slayer);
                    mod.cocoon.maybe(p, def.id, e.location, mt, 0, false, def.health, null);
                }
            }
        }
        SlayerData d = data(p);
        if (!d.hasQuest() || !COLLECTING.equals(d.questState)) return;
        SlayerCatalog.Type t = mod.catalog.type(d.questType);
        SlayerCatalog.Tier tier = t == null ? null : t.tier(d.questTier);
        if (tier == null) return;
        if (!counts(t, def, mob, p)) return;
        double gain = Math.max(0, e.combatXp);
        if (gain <= 0) gain = Math.max(1, def == null ? 1 : def.combatXp);
        d.questProgress += gain;
        double target = spawnTarget(tier);
        if (d.questProgress >= target) {
            d.questProgress = target;
            spawnBoss(p, e.location);
        } else {
            maybeMiniboss(p, t, tier, e.location, def);
        }
    }

    /** Does this kill count toward a quest of type t? */
    boolean counts(SlayerCatalog.Type t, MobDef def, LivingEntity mob, Player killer) {
        if (def == null) return false;
        if (def.id.startsWith("slayer_") && !def.id.startsWith("slayer_mini_")) return false;
        if (def.has("boss") && !def.id.startsWith("slayer_mini_")) return false;
        if (mob != null) {
            UUID owner = Sky.mobs().owner(mob);
            if (owner != null && !owner.equals(killer.getUniqueId())) return false;
        }
        if (def.slayer != null && !def.slayer.isBlank()) return def.slayer.equalsIgnoreCase(t.id);
        if ("vampire".equals(t.id)) return SlayerDials.VAMPIRE_ANY_MOB.get() && !anyTagged("vampire");
        if (!SlayerDials.ENTITY_FALLBACK.get()) return false;
        String ent = mob != null ? mob.getType().name() : def.entity;
        return ent != null && t.entities.contains(ent.toUpperCase(java.util.Locale.ROOT));
    }

    /** Is any registered mob tagged with this slayer category? (cached 30 s) */
    boolean anyTagged(String typeId) {
        long now = System.currentTimeMillis();
        Long at = taggedCacheAt.get(typeId);
        if (at != null && now - at < 30_000) return taggedCache.getOrDefault(typeId, false);
        boolean any = false;
        for (MobDef d : Sky.mobs().all()) {
            if (typeId.equalsIgnoreCase(d.slayer) && !d.id.startsWith("slayer_")) { any = true; break; }
        }
        taggedCache.put(typeId, any);
        taggedCacheAt.put(typeId, now);
        return any;
    }

    private void maybeMiniboss(Player p, SlayerCatalog.Type t, SlayerCatalog.Tier tier, Location at, MobDef killed) {
        if (t.minibosses.isEmpty() || at == null) return;
        if (killed != null && killed.id.startsWith("slayer_mini_")) return;
        if (ThreadLocalRandom.current().nextDouble() >= Math.max(0, SlayerDials.MINIBOSS_CHANCE.get())) return;
        List<SlayerCatalog.Mini> pool = new ArrayList<>();
        for (SlayerCatalog.Mini m : t.minibosses) if (m.minTier <= tier.tier) pool.add(m);
        if (pool.isEmpty()) return;
        SlayerCatalog.Mini pick = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        spawnMiniboss(p, pick.mob, at);
    }

    LivingEntity spawnMiniboss(Player p, String mobId, Location at) {
        LivingEntity m;
        try {
            m = Sky.mobs().spawn(mobId, BossFight.ground(at.clone().add(0, 1, 0)), p);
        } catch (RuntimeException ex) {
            return null;
        }
        if (m == null) return null;
        MobDef def = Sky.mobs().defOf(m);
        Fx.particle(m.getLocation().add(0, 1, 0), Particle.EXPLOSION, 2, 0.3, 0);
        Fx.sound(m.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.8f);
        Msg.send(p, "&c&lMINIBOSS! &7A &c" + (def == null ? "miniboss" : def.name) + " &7crawled out to defend its master!");
        if (m instanceof org.bukkit.entity.Mob mm) mm.setTarget(p);
        return m;
    }

    // ---- spawning ---------------------------------------------------------------------------------

    /** The bar is full: the boss gathers and rises once the player is ready (see {@link #spawnBoss(Player, Location, boolean)}). */
    void spawnBoss(Player p, Location near) { spawnBoss(p, near, false); }

    /**
     * The bar is full (or an admin / the player forced it). With {@code immediate} (re-summon, admin spawn) or the
     * grace dial at 0 the summoning plays at once, as in the reference. Otherwise the boss GATHERS first: playtest
     * wave3 had a new player's Ghoul Horror I spawn inside the zombie pack at 59% Health, and since death fails the
     * quest and keeps the fee, the first slayer was usually a 2,000-coin loss. The quest stays SPAWNING until the
     * player is healthy and has not been hit for a moment (or the max wait runs out) - {@link #tickGathering()}.
     */
    void spawnBoss(Player p, Location near, boolean immediate) {
        SlayerData d = data(p);
        UUID id = p.getUniqueId();
        if (!d.hasQuest() || fights.containsKey(id) || spawning.containsKey(id)) return;
        SlayerCatalog.Type t = mod.catalog.type(d.questType);
        SlayerCatalog.Tier tier = t == null ? null : t.tier(d.questTier);
        if (tier == null) return;
        d.questState = SPAWNING;
        Gathering g = gathering.remove(id);
        if (immediate || SlayerDials.SPAWN_MAX_WAIT.get() <= 0) {
            summoning(p, t, tier, near != null ? near : g == null ? null : g.near());
            return;
        }
        if (g != null) { gathering.put(id, g); return; }
        gathering.put(id, new Gathering(near == null ? null : near.clone(), System.currentTimeMillis()));
        String boss = t.bossName(tier.tier) + " " + SlayerMath.roman(tier.tier);
        if (alerts(p)) {
            Fx.title(p, "&" + t.color + "&l" + t.bossName(tier.tier).toUpperCase(java.util.Locale.ROOT) + " STIRS",
                    "&7Step out of the fight and heal up", 5, 50, 10);
            Fx.sound(p, Sound.ENTITY_WITHER_AMBIENT, 0.7f, 0.6f);
        }
        Msg.send(p, "&5&lSLAYER &7The bar is full! Your &" + t.color + boss + " &7rises once you are above &c"
                + Text.commasInt(SlayerDials.SPAWN_READY_HEALTH.get()) + "% Health &7and have not been hit for &e"
                + Text.commasInt(SlayerDials.SPAWN_CALM_SECONDS.get()) + "s &7(in &e" + SlayerDials.SPAWN_MAX_WAIT.get()
                + "s &7at the latest). &a/slayer summon &7calls it now.");
        Sky.sidebar().refresh(p);
    }

    /** Every 5 ticks: raise each gathering boss whose summoner is ready (or has waited long enough). */
    private void tickGathering() {
        if (gathering.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Gathering> en : new ArrayList<>(gathering.entrySet())) {
            UUID id = en.getKey();
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || !Sky.profiles().isReady(p)) { gathering.remove(id); continue; }
            SlayerData d = data(p);
            if (!d.hasQuest() || !SPAWNING.equals(d.questState) || fights.containsKey(id) || spawning.containsKey(id)) {
                gathering.remove(id);
                continue;
            }
            if (p.isDead() || summonBlocked(p) != null) continue;     // never rises in a safe area: it waits for you
            if (!SlayerMath.readyToSummon(healthFraction(p), secondsSinceHurt(id, now), (now - en.getValue().since()) / 1000.0,
                    SlayerDials.SPAWN_READY_HEALTH.get(), SlayerDials.SPAWN_CALM_SECONDS.get(), SlayerDials.SPAWN_MAX_WAIT.get())) {
                continue;
            }
            gathering.remove(id);
            SlayerCatalog.Type t = mod.catalog.type(d.questType);
            SlayerCatalog.Tier tier = t == null ? null : t.tier(d.questTier);
            // it rises where you stand now; the spot where the bar filled only if you are still there (not the pack you left)
            Location near = en.getValue().near();
            if (near != null && (near.getWorld() != p.getWorld() || near.distance(p.getLocation()) > 6)) near = null;
            if (tier != null) summoning(p, t, tier, near);
        }
    }

    /** Is this player's boss gathering (bar full, waiting for them to be ready)? */
    boolean isGathering(Player p) {
        return gathering.containsKey(p.getUniqueId()) && SPAWNING.equals(data(p).questState);
    }

    /** Can the player call their boss right now (READY after a restart, or gathering)? */
    boolean canCallNow(Player p) {
        return READY.equals(data(p).questState) || isGathering(p);
    }

    private static double healthFraction(Player p) {
        double max = Sky.vitals().maxHealth(p);
        return max > 0 ? Sky.vitals().health(p) / max : 1;
    }

    private double secondsSinceHurt(UUID id, long now) {
        Long at = lastHurt.get(id);
        return at == null ? Double.MAX_VALUE : (now - at) / 1000.0;
    }

    /** Remembers when a player was last hit by something (a mob, a boss, a player), for the gathering check. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(SkyEvents.Damage e) {
        if (e.ctx.victim instanceof Player pv && e.ctx.attacker != null && e.ctx.attacker != pv) {
            lastHurt.put(pv.getUniqueId(), System.currentTimeMillis());
        }
    }

    /** null when a boss may be summoned where the player stands, else why not. */
    private String summonBlocked(Player p) {
        Layout.Zone z = Sky.worlds().zoneOf(p);
        if (z != null && z.flags() != null && (z.flags().contains(Layout.Zone.SAFE) || z.flags().contains(Layout.Zone.NO_MOBS))) {
            return "You cannot summon a slayer boss in a safe area!";
        }
        if (Sky.worlds().islandOf(p.getLocation()) == null) return "Summon your boss on a SkyBlock island.";
        return null;
    }

    /** The summoning animation, then the boss spawns next to the player. */
    private void summoning(Player p, SlayerCatalog.Type t, SlayerCatalog.Tier tier, Location near) {
        SlayerData d = data(p);
        d.questState = SPAWNING;
        Location at = near != null && near.getWorld() == p.getWorld() && near.distance(p.getLocation()) < 20 ? near.clone() : p.getLocation().clone();
        at = BossFight.ground(at.add(0, 1, 0));
        spawning.put(p.getUniqueId(), System.currentTimeMillis());
        if (alerts(p)) {      // Settings > Slayer Boss Alerts (menus' combat.slayer_alerts)
            Fx.title(p, "&" + t.color + "&l" + t.bossName(tier.tier).toUpperCase(java.util.Locale.ROOT), "&7is coming for you...", 5, 40, 10);
            Fx.sound(p, Sound.ENTITY_WITHER_SPAWN, 0.8f, 0.9f);
        }
        final Location spot = at;
        int delay = Math.max(1, SlayerDials.SPAWN_DELAY_TICKS.get());
        for (int i = 0; i < delay; i += 5) {
            final int k = i;
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                double r = 2.5 - 2.0 * k / Math.max(1, delay);
                Fx.ring(spot, Math.max(0.3, r), Fx.rgb(140, 0, 30), 24);
                Fx.particle(spot.clone().add(0, 1, 0), Particle.WITCH, 8, 0.4, 0.05);
            }, i);
        }
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            spawning.remove(p.getUniqueId());
            if (!p.isOnline() || !Sky.profiles().isReady(p)) return;
            SlayerData dd = data(p);
            if (!dd.hasQuest() || !SPAWNING.equals(dd.questState)) return;
            summonNow(p, t, tier, spot);
        }, delay);
    }

    private void summonNow(Player p, SlayerCatalog.Type t, SlayerCatalog.Tier tier, Location spot) {
        SlayerData d = data(p);
        LivingEntity boss;
        try {
            boss = Sky.mobs().spawn(tier.mob, spot, p);
        } catch (RuntimeException ex) {
            Sky.log().warning("[slayer] cannot spawn " + tier.mob + ": " + ex.getMessage());
            boss = null;
        }
        if (boss == null) {
            d.questState = READY;
            Msg.error(p, "The boss could not appear here. Try &e/slayer summon &csomewhere else.");
            return;
        }
        Sky.mobs().setMaxHealth(boss, bossHealth(t, tier));
        BossFight f = create(p, t, tier);
        fights.put(p.getUniqueId(), f);
        mod.fightIndex.put(boss.getUniqueId(), f);
        int seconds = "vampire".equals(t.id) ? SlayerDials.VAMPIRE_TIME.get() : SlayerDials.BOSS_TIME.get();
        f.begin(boss, Math.max(10, seconds));
        d.questState = FIGHTING;
        try { boss.getWorld().strikeLightningEffect(spot); } catch (RuntimeException ignored) { }
        Fx.particle(spot.clone().add(0, 1, 0), Particle.EXPLOSION_EMITTER, 1, 0, 0);
        Msg.send(p, "&5&lSLAYER &7Your &" + t.color + t.bossName(tier.tier) + " " + SlayerMath.roman(tier.tier)
                + " &7has spawned! &eKill it within &c" + SlayerMath.clock(seconds) + "&e!");
        Sky.sidebar().refresh(p);
    }

    /** A boss tier's health on this server (dials applied; the Bloodfiend's Rift hearts scaled to overworld HP). */
    static double bossHealth(SlayerCatalog.Type t, SlayerCatalog.Tier tier) {
        double hp = tier.health * Math.max(0.0001, SlayerDials.BOSS_HP_SCALE.get());
        if ("vampire".equals(t.id)) hp *= Math.max(1, SlayerDials.VAMPIRE_HP_SCALE.get());
        return hp;
    }

    BossFight create(Player p, SlayerCatalog.Type t, SlayerCatalog.Tier tier) {
        return switch (t.id) {
            case "zombie" -> new ZombieFight(mod, p, t, tier);
            case "spider" -> new SpiderFight(mod, p, t, tier);
            case "wolf" -> new WolfFight(mod, p, t, tier);
            case "enderman" -> new EndermanFight(mod, p, t, tier);
            case "blaze" -> new BlazeFight(mod, p, t, tier);
            case "vampire" -> new VampireFight(mod, p, t, tier);
            default -> new ZombieFight(mod, p, t, tier);
        };
    }

    /** Summon a READY boss again (after a restart), or call a gathering boss now: the menu or /slayer summon. */
    void summonReady(Player p) {
        SlayerData d = data(p);
        if (!d.hasQuest()) { Msg.error(p, "You have no active slayer quest."); return; }
        if (!canCallNow(p)) { Msg.error(p, "Your boss is not waiting to be summoned."); return; }
        String blocked = summonBlocked(p);
        if (blocked != null) { Msg.error(p, blocked); return; }
        spawnBoss(p, p.getLocation(), true);
    }

    // ---- ending -----------------------------------------------------------------------------------

    void complete(BossFight f) {
        Player p = f.owner();
        endFight(f);
        if (p == null || !Sky.profiles().isReady(p)) return;
        SlayerData d = data(p);
        SlayerCatalog.Type t = f.type;
        SlayerCatalog.Tier tier = f.tier;
        long secs = (System.currentTimeMillis() - f.startedMillis) / 1000;
        clearQuest(d);
        d.questsCompleted++;
        d.kills.merge(t.id + ":" + tier.tier, 1, Integer::sum);
        String bk = t.id + ":" + tier.tier;
        Long best = d.bestTime.get(bk);
        if (best == null || secs < best) d.bestTime.put(bk, secs);

        Features.Mayors m = Sky.service(Features.Mayors.class);
        double boost = m.perkActive("aatrox.slayer_xp_buff") ? m.perkValue("aatrox.slayer_xp_buff") : 0;
        boost += petSlayerXp(p);
        double gained = SlayerMath.slayerXp(tier.slayerXp * Math.max(0, SlayerDials.XP_SCALE.get()), boost);

        Msg.send(p, Msg.BAR);
        Msg.send(p, "  &a&lNICE! SLAYER BOSS SLAIN!");
        Msg.send(p, "   &7Time: &e" + SlayerMath.clock(secs) + (best == null || secs < best ? " &6&lNEW BEST!" : ""));
        mod.addXp(p, t, gained, "boss");
        mod.loot.rollBoss(p, f, gained);
        Msg.send(p, Msg.BAR);
        Fx.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);

        // bits (research 2.1), needs the Boon Cookie unless the dial says otherwise. The bank applies the fame
        // multiplier to any bits whose reason starts with the module id ("slayer: ...").
        if (tier.bits > 0 && (!SlayerDials.BITS_NEED_COOKIE.get() || SlayerItems.cookie(p))) {
            Sky.economy().addBits(p, tier.bits, "slayer: " + t.id + " T" + tier.tier);
        }
        // The Primordial: the boss may be cocooned and hatch to be slain again (loot only)
        Location died = f.lastBossLocation;
        if (died != null) mod.cocoon.maybe(p, tier.mob, died, t, tier.tier, true, bossHealth(t, tier), null);
        // after-kill heal (research 2.1): tier heal per second for 6..10 s by level
        healOverTime(p, tier.heal, SlayerMath.healSeconds(mod.level(p, t)));
        // lootshare for helpers
        mod.loot.lootshare(f);
        Sky.profiles().saveNow(p);
        Sky.sidebar().refresh(p);
        Sky.stats().invalidate(p);
        // auto-slayer
        if (d.autoSlayer.contains(t.id) && mod.level(p, t) >= SlayerDials.AUTO_SLAYER_LEVEL.get()) {
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (p.isOnline() && Sky.profiles().isReady(p) && !data(p).hasQuest()) {
                    if (!start(p, t.id, tier.tier, false, true)) Msg.send(p, "&cAuto-Slayer could not restart the quest.");
                }
            }, 40L);
        }
    }

    /**
     * Pet perks that add Slayer XP, in percent (pets' ability keys): the Bingo pet's Quick Study ("more Skill XP and
     * Slayer XP"). The pets module applies its Skill XP half; the Slayer XP half is ours.
     */
    static double petSlayerXp(Player p) {
        try {
            return Math.max(0, Sky.service(Features.Pets.class).abilityValue(p, "bingo_fast_learner", "a"));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The player's "Slayer Boss Alerts" setting (menus' Settings, combat.slayer_alerts): titles + sounds on spawn. */
    static boolean alerts(Player p) {
        try {
            return Sky.service(Features.Settings.class).flag(p, "combat.slayer_alerts", true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void healOverTime(Player p, double perSecond, double seconds) {
        if (perSecond <= 0) return;
        int n = (int) Math.round(seconds);
        for (int i = 1; i <= n; i++) {
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (p.isOnline()) Sky.vitals().heal(p, perSecond);
            }, 20L * i);
        }
    }

    void fail(Player p, String why) {
        SlayerData d = data(p);
        BossFight f = fights.get(p.getUniqueId());
        if (f != null) endFight(f);
        if (!d.hasQuest()) return;
        clearQuest(d);
        d.questsFailed++;
        Msg.send(p, Msg.BAR);
        Msg.send(p, "  &c&lSLAYER QUEST FAILED!");
        Msg.send(p, "   &7" + why);
        Msg.send(p, Msg.BAR);
        Fx.sound(p, Sound.ENTITY_WITHER_DEATH, 0.5f, 1.5f);
        Sky.sidebar().refresh(p);
        Sky.profiles().saveNow(p);
    }

    void endFight(BossFight f) {
        fights.remove(f.ownerId);
        if (f.boss != null && f.boss.isValid() && !f.boss.isDead()) {
            mod.fightIndex.remove(f.boss.getUniqueId());
            if (Sky.mobs().isSkyMob(f.boss) && Sky.mobs().health(f.boss) > 0) Sky.mobs().remove(f.boss);
        }
        f.end();
    }

    /** The boss entity vanished without dying (chunk unload, removed by something else). */
    void bossVanished(BossFight f) {
        Player p = f.owner();
        Location last = f.lastBossLocation;
        // The usual way a boss "vanishes" is its chunk unloading because the owner warped or ran off. That is
        // leaving the fight, not an accident: handing back a fresh full-health boss on a new timer would let anyone
        // escape a losing fight for free. Only a boss lost while the owner stood near it can be called back.
        if (p != null && last != null && (p.getWorld() != last.getWorld()
                || p.getLocation().distance(last) > Math.max(16, SlayerDials.LEASH_RANGE.get()))) {
            fail(p, "You left your slayer boss behind.");
            return;
        }
        endFight(f);
        if (p != null && Sky.profiles().isReady(p)) {
            SlayerData d = data(p);
            if (d.hasQuest()) {
                d.questState = READY;
                Msg.send(p, "&eYour slayer boss vanished. Use &a/slayer summon &eor the Slayer menu to call it back.");
            }
        }
    }

    // ---- ticking ----------------------------------------------------------------------------------

    /** Every 5 ticks. */
    void tick() {
        tickGathering();
        if (fights.isEmpty()) return;
        for (BossFight f : new ArrayList<>(fights.values())) {
            if (f.ended) { fights.remove(f.ownerId); continue; }
            Player p = f.owner();
            if (p == null) continue;
            if (System.currentTimeMillis() > f.deadlineMillis) {
                fail(p, "You did not kill the boss in time.");
                continue;
            }
            if (f.ticks % 4 == 0 && f.boss != null && f.boss.isValid()) {
                boolean far = p.getWorld() != f.boss.getWorld()
                        || p.getLocation().distance(f.boss.getLocation()) > Math.max(16, SlayerDials.LEASH_RANGE.get());
                f.outOfRangeSteps = far ? f.outOfRangeSteps + 1 : 0;
                if (far && f.outOfRangeSteps == 1) {
                    Msg.send(p, "&cYou left your slayer boss! Return within &e" + SlayerDials.LEASH_SECONDS.get() + "s &cor the quest fails.");
                }
                if (f.outOfRangeSteps > Math.max(1, SlayerDials.LEASH_SECONDS.get())) {
                    fail(p, "You strayed too far from the boss.");
                    continue;
                }
            }
            f.tick();
        }
    }

    // ---- player lifecycle -------------------------------------------------------------------------

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        SlayerData d = data(p);
        if (d.hasQuest() && (FIGHTING.equals(d.questState) || SPAWNING.equals(d.questState))) {
            d.questState = READY;     // interrupted (restart or crash): the boss can be called back
        }
        if (d.hasQuest() && READY.equals(d.questState)) {
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (p.isOnline()) Msg.send(p, "&eYour slayer boss is waiting. Use &a/slayer summon &eto call it.");
            }, 60L);
        }
        if (d.loginNotice != null) {
            final String note = d.loginNotice;
            d.loginNotice = null;
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) Msg.send(p, note); }, 60L);
        }
        mod.checkLevelAnnouncements(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        spawning.remove(p.getUniqueId());
        gathering.remove(p.getUniqueId());
        lastHurt.remove(p.getUniqueId());
        BossFight f = fights.get(p.getUniqueId());
        if (f == null) return;
        if (!Sky.profiles().isLoaded(p)) { endFight(f); return; }
        SlayerData d = data(p);
        endFight(f);
        if (SlayerDials.RELOG_FAILS.get()) {
            if (d.hasQuest()) {
                clearQuest(d);
                d.questsFailed++;
                d.loginNotice = "&c&lSLAYER QUEST FAILED! &7You logged out during the boss fight.";
            }
        } else if (d.hasQuest()) {
            d.questState = READY;
        }
    }

    @EventHandler
    public void onDeath(SkyEvents.SkyDeath e) {
        Player p = e.player;
        // Fenris L8 perk: lose 5% fewer coins on death (research 2.7)
        if (mod.level(p, mod.catalog.type("wolf")) >= 8 && e.coinsLost > 0) e.coinsLost *= 0.95;
        if (!fights.containsKey(p.getUniqueId())) return;
        if (SlayerDials.DEATH_FAILS.get()) fail(p, "You died! Slayer bosses do not forgive.");
    }

    /** Server stop: fights become READY so nobody loses a paid quest to a restart. */
    void shutdown() {
        for (BossFight f : new ArrayList<>(fights.values())) {
            Player p = f.owner();
            endFight(f);
            if (p != null && Sky.profiles().isLoaded(p)) {
                SlayerData d = data(p);
                if (d.hasQuest()) d.questState = READY;
            }
        }
        fights.clear();
    }

    // ---- display ----------------------------------------------------------------------------------

    List<String> sidebar(Player p) {
        if (!Sky.profiles().isReady(p)) return List.of();
        SlayerData d = data(p);
        if (!d.hasQuest()) return List.of();
        SlayerCatalog.Type t = mod.catalog.type(d.questType);
        SlayerCatalog.Tier tier = t == null ? null : t.tier(d.questTier);
        if (tier == null) return List.of();
        int lines = Math.max(1, Math.min(3, SlayerDials.SIDEBAR_LINES.get()));
        List<String> out = new ArrayList<>();
        String boss = "&" + t.color + t.bossName(tier.tier) + " " + SlayerMath.roman(tier.tier);
        String prog = progressLine(p, d, t, tier);
        if (lines == 1) {
            out.add(prog);
        } else {
            out.add(lines >= 3 ? "&5Slayer Quest" : boss);
            if (lines >= 3) out.add(" " + boss);
            out.add(" " + prog);
        }
        return out;
    }

    String progressLine(Player p, SlayerData d, SlayerCatalog.Type t, SlayerCatalog.Tier tier) {
        BossFight f = fights.get(p.getUniqueId());
        if (f != null) return "&eSlay the boss! &c" + SlayerMath.clock(f.secondsLeft());
        if (READY.equals(d.questState)) return "&eBoss waiting! &a/slayer summon";
        if (SPAWNING.equals(d.questState)) return gathering.containsKey(p.getUniqueId()) ? "&cThe boss stirs... &7heal up" : "&cThe boss approaches...";
        return "&7(&e" + Text.commasInt(d.questProgress) + "&7/&c" + Text.shortNum(spawnTarget(tier)) + "&7) Combat XP";
    }

    String actionBar(Player p) {
        if (!SlayerDials.ACTIONBAR.get() || !Sky.profiles().isReady(p)) return null;
        SlayerData d = data(p);
        if (!d.hasQuest()) return null;
        SlayerCatalog.Type t = mod.catalog.type(d.questType);
        SlayerCatalog.Tier tier = t == null ? null : t.tier(d.questTier);
        if (tier == null) return null;
        Gathering g = gathering.get(p.getUniqueId());
        if (g != null && SPAWNING.equals(d.questState)) return gatheringBar(p, t, g);
        if (!COLLECTING.equals(d.questState)) return null;
        double target = spawnTarget(tier);
        return "&" + t.color + "☠ " + Text.dashBar(d.questProgress / target, 10) + " &7" + (int) Math.floor(100 * d.questProgress / target) + "%";
    }

    /** Action bar while the boss gathers: what still keeps it from rising. */
    private String gatheringBar(Player p, SlayerCatalog.Type t, Gathering g) {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append('&').append(t.color).append("☠ &7Boss stirs");
        String blocked = summonBlocked(p);
        if (blocked != null) {
            sb.append(" &8| &cleave the safe area");
        } else {
            double pct = Math.floor(100 * healthFraction(p));
            double need = SlayerDials.SPAWN_READY_HEALTH.get();
            if (pct < need) sb.append(" &8| &cHealth ").append((int) pct).append("%&7/").append((int) Math.ceil(need)).append('%');
            if (secondsSinceHurt(p.getUniqueId(), now) < SlayerDials.SPAWN_CALM_SECONDS.get()) sb.append(" &8| &cget out of the fight");
            long left = Math.max(0, SlayerDials.SPAWN_MAX_WAIT.get() - (now - g.since()) / 1000);
            sb.append(" &8| &7rises in &e").append(left).append('s');
        }
        return sb.toString();
    }
}
