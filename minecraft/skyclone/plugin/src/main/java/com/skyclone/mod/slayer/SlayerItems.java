package com.skyclone.mod.slayer;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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

/**
 * Behaviour of the usable slayer items: the Slayer Hotline, mixins, power orbs and flares (deployables, research 2.7 /
 * 2.9), the tubas, the Gloomlock Grimoire, Holy Ice, the stakes' Bloodcrave and the book bundles.
 */
final class SlayerItems {

    private final SlayerModule mod;

    /** A deployable's buff (one per item id). */
    record Deploy(String id, int rank, double radius, int seconds, int maxPlayers, double selfHeal, double otherHeal,
                  Map<Stat, Double> flat, Map<Stat, Double> percent, boolean healOnlyCrimson, boolean flare) {}

    static final Map<String, Deploy> DEPLOYS = new LinkedHashMap<>();

    static {
        DEPLOYS.put("RADIANT_POWER_ORB", new Deploy("RADIANT_POWER_ORB", 1, 18, 60, 5, 30, 15,
                Map.of(Stat.HEALTH_REGEN, 25.0), Map.of(), false, false));
        DEPLOYS.put("MANA_FLUX_POWER_ORB", new Deploy("MANA_FLUX_POWER_ORB", 2, 18, 60, 5, 40, 20,
                Map.of(Stat.HEALTH_REGEN, 50.0, Stat.STRENGTH, 10.0, Stat.MANA_REGEN, 50.0), Map.of(), false, false));
        DEPLOYS.put("OVERFLUX_POWER_ORB", new Deploy("OVERFLUX_POWER_ORB", 3, 18, 60, 5, 50, 25,
                Map.of(Stat.HEALTH_REGEN, 100.0, Stat.STRENGTH, 25.0, Stat.MANA_REGEN, 100.0), Map.of(), false, false));
        DEPLOYS.put("PLASMAFLUX_POWER_ORB", new Deploy("PLASMAFLUX_POWER_ORB", 4, 20, 60, 5, 60, 30,
                Map.of(Stat.HEALTH_REGEN, 125.0, Stat.STRENGTH, 35.0, Stat.MANA_REGEN, 125.0), Map.of(), false, false));
        DEPLOYS.put("WARNING_FLARE", new Deploy("WARNING_FLARE", 1, 40, 180, 8, 30, 30,
                Map.of(Stat.VITALITY, 10.0, Stat.TRUE_DEFENSE, 10.0), Map.of(), true, true));
        DEPLOYS.put("ALERT_FLARE", new Deploy("ALERT_FLARE", 2, 40, 180, 8, 40, 40,
                Map.of(Stat.VITALITY, 15.0, Stat.TRUE_DEFENSE, 20.0, Stat.FEROCITY, 10.0, Stat.MANA_REGEN, 50.0), Map.of(), true, true));
        DEPLOYS.put("SOS_FLARE", new Deploy("SOS_FLARE", 3, 40, 180, 8, 50, 50,
                Map.of(Stat.VITALITY, 25.0, Stat.TRUE_DEFENSE, 30.0, Stat.FEROCITY, 10.0, Stat.ATTACK_SPEED, 5.0, Stat.MANA_REGEN, 125.0),
                Map.of(), true, true));
    }

    /**
     * Mixin buffs (research 2.13 + NEU lore): {stat or null, value, percent?, slayer type}. A null stat is a behaviour,
     * not a stat: Wolf Fur = +7 Magic Find on one-hit kills (a per-target Magic Find modifier, wave 5), End Portal
     * Fumes = +30% Overflow from Soulstream conversions (the soulflow module reads {@code slayer:mixin:<id>}).
     */
    static final Map<String, Object[]> MIXINS = new LinkedHashMap<>();

    static {
        MIXINS.put("ZOMBIE_BRAIN_MIXIN", new Object[]{Stat.FEROCITY, 10.0, false, "zombie"});
        MIXINS.put("SPIDER_EGG_MIXIN", new Object[]{Stat.CRIT_DAMAGE, 15.0, false, "spider"});
        MIXINS.put("WOLF_FUR_MIXIN", new Object[]{null, 7.0, false, "wolf"});
        MIXINS.put("END_PORTAL_FUMES_MIXIN", new Object[]{null, 30.0, true, "enderman"});
        MIXINS.put("GABAGOEY_MIXIN", new Object[]{Stat.TRUE_DEFENSE, 5.0, true, "blaze"});
    }

    /** Is this mixin active on the player right now? (Also the {@code slayer:mixin:<id>} requirement.) */
    boolean mixinActive(Player p, String mixinId) {
        if (mixinId == null || !Sky.profiles().isReady(p)) return false;
        Long until = mod.quests.data(p).mixins.get(mixinId);
        return until != null && until > System.currentTimeMillis();
    }

    /** Boon Cookie active? (bank's Features.Bank, which falls back to the {@code cookie} requirement key). */
    static boolean cookie(Player p) {
        try {
            return Sky.service(com.skyclone.api.feature.Features.Bank.class).cookieActive(p);
        } catch (RuntimeException e) {
            return Sky.requirements().check(p, "cookie");
        }
    }

    // ---- Wolf Fur Mixin: Magic Find on one-hit kills (wave 5) -------------------------------------

    /** player -> the monster they just one-shot: {mob def id, server tick}. Read by the Magic Find modifier. */
    private final Map<UUID, Object[]> oneHit = new HashMap<>();

    /** A player hit a sky mob at full health hard enough to kill it: remember it for this tick's loot roll. */
    private void trackOneHit(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null || ctx.victim instanceof Player || ctx.kind == DamageContext.Kind.FEROCITY) return;
        if (!(ctx.victim instanceof org.bukkit.entity.LivingEntity le) || !Sky.mobs().isSkyMob(le)) return;
        if (!mixinActive(p, "WOLF_FUR_MIXIN")) return;
        double hp = Sky.mobs().health(le), max = Sky.mobs().maxHealth(le);
        if (hp >= max - 0.5 && ctx.finalDamage >= hp) {
            com.skyclone.api.mob.MobDef def = Sky.mobs().defOf(le);
            oneHit.put(p.getUniqueId(), new Object[]{def == null ? "" : def.id, (long) Bukkit.getCurrentTick()});
        }
    }

    /** The per-target Magic Find modifier: +7 when this kill was a one-hit kill (same tick, same monster kind). */
    private double wolfFurMagicFind(Player p, com.skyclone.api.mob.MobDef def) {
        if (p == null || def == null) return 0;
        Object[] o = oneHit.get(p.getUniqueId());
        if (o == null) return 0;
        long tick = (Long) o[1];
        if (Bukkit.getCurrentTick() - tick > 1 || !def.id.equals(o[0])) return 0;
        oneHit.remove(p.getUniqueId());
        return mixinActive(p, "WOLF_FUR_MIXIN") ? Math.max(0, SlayerDials.WOLF_FUR_MAGIC_FIND.get()) : 0;
    }

    private static final class Live {
        UUID owner;
        Deploy spec;
        Location at;
        long until;
        ItemDisplay display;
    }

    private final Map<UUID, Live> deployed = new HashMap<>();          // owner -> their deployable
    private final Map<UUID, Deploy> applied = new HashMap<>();         // player -> buff in effect
    private final Map<UUID, long[]> hotline = new HashMap<>();         // last call ms, tries
    private final Map<UUID, Long> holyIce = new HashMap<>();
    private final Set<UUID> bloodcrave = new HashSet<>();
    private final Map<UUID, Map<Stat, Double>> howl = new HashMap<>();
    private final Map<UUID, Long> howlUntil = new HashMap<>();

    SlayerItems(SlayerModule mod) { this.mod = mod; }

    void register() {
        AbilityService a = Sky.abilities();
        a.register("slayer_hotline", this::hotline);
        a.register("slayer_mixin", this::mixin);
        a.register("slayer_orb", this::deploy);
        a.register("slayer_flare", this::deploy);
        a.register("slayer_tuba", this::tuba);
        a.register("slayer_grimoire", this::lifeTap);
        a.register("slayer_grimoire_heal", u -> {
            Sky.vitals().heal(u.player(), 1000);
            Fx.particle(u.player().getLocation().add(0, 1, 0), Particle.HEART, 5, 0.4, 0);
            return true;
        });
        a.register("slayer_holy_ice", u -> {
            holyIce.put(u.player().getUniqueId(), System.currentTimeMillis() + 1500);
            Fx.particle(u.player().getLocation().add(0, 1, 0), Particle.SNOWFLAKE, 25, 0.5, 0.02);
            Fx.sound(u.player(), Sound.BLOCK_GLASS_BREAK, 1f, 1.6f);
            return true;
        });
        a.register("slayer_book_bundle", this::bundle);
        a.register("slayer_tactical", this::tactical);
        a.register("slayer_voodoo", u -> voodoo(u, 1500, 10));
        a.register("slayer_voodoo_jinxed", u -> voodoo(u, 2222, 12));
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
            public void onDamage(com.skyclone.api.event.SkyEvents.Damage e) { trackDealt(e.ctx); trackOneHit(e.ctx); }
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { oneHit.remove(e.getPlayer().getUniqueId()); }
        }, Sky.plugin());
        Sky.loot().addMagicFindModifier("slayer_wolf_fur", this::wolfFurMagicFind);

        Sky.combat().addModifier("slayer_items_in", 400, ctx -> {
            Player v = ctx.playerVictim();
            if (v == null || ctx.playerAttacker() != null) return;
            Long until = holyIce.get(v.getUniqueId());
            if (until != null && until > System.currentTimeMillis()) ctx.multiplicative *= 0.2;
        });
        Sky.combat().addModifier("slayer_items_out", 400, ctx -> {
            Player p = ctx.playerAttacker();
            if (p == null || ctx.kind == DamageContext.Kind.FEROCITY) return;
            if (bloodcrave.remove(p.getUniqueId())) {
                ctx.multiplicative *= 2;
                Sky.vitals().flash(p, "&4&lBLOODCRAVE! &7x2 damage", 20);
            }
        });
        Sky.stats().addProvider("slayer_items", this::stats);
    }

    // ---- hotline ----------------------------------------------------------------------------------

    private boolean hotline(AbilityService.Use u) {
        Player p = u.player();
        String island = Sky.worlds().islandOf(p.getLocation());
        if ("rift".equals(island) || "catacombs".equals(island) || Sky.service(com.skyclone.api.feature.Features.Dungeons.class).inDungeon(p)) {
            Msg.send(p, "&c✆ No reception here!");
            return false;
        }
        long now = System.currentTimeMillis();
        long[] st = hotline.computeIfAbsent(p.getUniqueId(), k -> new long[]{0, 0});
        if (now - st[0] < SlayerDials.HOTLINE_COOLDOWN.get() * 1000L) {
            st[1]++;
            String[] jokes = {"&e✆ &7The line is busy. Try again later.", "&e✆ &7Nobody answers...",
                    "&e✆ &cHEY, IT'S NOT PICKING UP. STOP TRYING!"};
            Msg.send(p, jokes[(int) Math.min(jokes.length - 1, st[1] - 1)]);
            return false;
        }
        st[0] = now;
        st[1] = 0;
        for (int i = 0; i < 3; i++) {
            final int k = i;
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (!p.isOnline()) return;
                Msg.send(p, "&e✆ Ring" + ".".repeat(k + 1));
                Fx.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f + k * 0.1f);
            }, 15L * i);
        }
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            String[] hi = {"Hello? Slayer business, make it quick.", "You again? Fine, what do you need?",
                    "Brannoc speaking. Who are we hunting today?"};
            Msg.send(p, "&e✆ &5" + mod.catalog.npcName + "&f: " + hi[(int) (Math.random() * hi.length)]);
            mod.menus.openMain(p);
        }, 50L);
        return true;
    }

    // ---- mixins -----------------------------------------------------------------------------------

    private boolean mixin(AbilityService.Use u) {
        Player p = u.player();
        String id = u.itemDef().id;
        if (!MIXINS.containsKey(id)) return false;
        if (SlayerDials.MIXIN_NEEDS_COOKIE.get() && !cookie(p)) {
            Msg.error(p, "You need an active Boon Cookie to drink a mixin!");
            return false;
        }
        if (Sky.items().remove(p, id, 1) < 1) return false;
        SlayerData d = mod.quests.data(p);
        long now = System.currentTimeMillis();
        long base = Math.max(now, d.mixins.getOrDefault(id, 0L));
        long add = (long) (Math.max(0.01, SlayerDials.MIXIN_HOURS.get()) * 3_600_000L);
        d.mixins.put(id, base + add);
        Sky.stats().invalidate(p);
        Msg.success(p, "You drank the " + Text.strip(u.itemDef().name) + "! &7(" + Text.duration((base + add - now) / 1000) + " left)");
        Fx.sound(p, Sound.ENTITY_GENERIC_DRINK, 1f, 1f);
        return true;
    }

    // ---- deployables ------------------------------------------------------------------------------

    private boolean deploy(AbilityService.Use u) {
        Player p = u.player();
        Deploy spec = DEPLOYS.get(u.itemDef().id);
        if (spec == null) return false;
        Live old = deployed.remove(p.getUniqueId());
        if (old != null && old.display != null) old.display.remove();
        Live l = new Live();
        l.owner = p.getUniqueId();
        l.spec = spec;
        l.at = spec.flare() ? p.getLocation().clone().add(0, 10, 0) : p.getLocation().clone().add(0, 2.5, 0);
        l.until = System.currentTimeMillis() + spec.seconds() * 1000L;
        try {
            ItemStack look = SlayerMenus.display(spec.id(), List.of());
            l.display = p.getWorld().spawn(l.at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setItemStack(look);
                d.setBillboard(Display.Billboard.CENTER);
                d.setGlowing(true);
            });
        } catch (RuntimeException e) {
            l.display = null;
        }
        deployed.put(p.getUniqueId(), l);
        Msg.send(p, "&aDeployed your " + Text.strip(u.itemDef().name) + "&a! &7(" + spec.seconds() + "s)");
        Fx.sound(p, spec.flare() ? Sound.ENTITY_FIREWORK_ROCKET_LAUNCH : Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        return true;
    }

    /** Every 20 ticks: who is under which deployable, heals, visuals, expiry; tuba buffs expire. */
    void tick() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Live>> it = deployed.entrySet().iterator();
        while (it.hasNext()) {
            Live l = it.next().getValue();
            if (now > l.until || l.at.getWorld() == null) {
                if (l.display != null) l.display.remove();
                it.remove();
                continue;
            }
            if (l.spec.flare()) Fx.particle(l.at, Particle.FIREWORK, 6, 0.3, 0.02);
            else Fx.ring(l.at.clone().subtract(0, 2.4, 0), Math.min(6, l.spec.radius() / 3), Fx.rgb(255, 200, 80), 20);
        }
        Map<UUID, Deploy> next = new HashMap<>();
        for (Live l : deployed.values()) {
            List<Player> near = new ArrayList<>();
            for (Player p : l.at.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(l.at) <= l.spec.radius() * l.spec.radius()) near.add(p);
            }
            near.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(l.at), b.getLocation().distanceSquared(l.at)));
            int n = 0;
            for (Player p : near) {
                if (n++ >= l.spec.maxPlayers()) break;
                Deploy cur = next.get(p.getUniqueId());
                if (cur == null || better(l.spec, cur)) next.put(p.getUniqueId(), l.spec);
            }
        }
        // heals + stat refresh
        for (Map.Entry<UUID, Deploy> e : next.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            Deploy d = e.getValue();
            boolean self = deployed.containsKey(p.getUniqueId()) && deployed.get(p.getUniqueId()).spec == d;
            boolean healHere = !d.healOnlyCrimson() || isCrimson(p);
            if (healHere) Sky.vitals().heal(p, self ? d.selfHeal() : d.otherHeal());
        }
        Set<UUID> changed = new HashSet<>(applied.keySet());
        changed.addAll(next.keySet());
        for (UUID id : changed) {
            if (applied.get(id) != next.get(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) Sky.stats().invalidate(p);
            }
        }
        applied.clear();
        applied.putAll(next);
        // tuba buffs
        Iterator<Map.Entry<UUID, Long>> ht = howlUntil.entrySet().iterator();
        while (ht.hasNext()) {
            Map.Entry<UUID, Long> e = ht.next();
            if (e.getValue() < now) {
                ht.remove();
                howl.remove(e.getKey());
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) Sky.stats().invalidate(p);
            }
        }
    }

    private static boolean better(Deploy a, Deploy b) {
        if (a.flare() != b.flare()) return a.flare();     // TUNE: a flare outranks an orb
        return a.rank() > b.rank();
    }

    private static boolean isCrimson(Player p) {
        String i = Sky.worlds().islandOf(p.getLocation());
        return "crimson_isle".equals(i) || "kuudra".equals(i);
    }

    // ---- tuba, grimoire, bundles -----------------------------------------------------------------

    private boolean tuba(AbilityService.Use u) {
        Player p = u.player();
        boolean weirder = "WEIRDER_TUBA".equals(u.itemDef().id);
        Map<Stat, Double> buff = new EnumMap<>(Stat.class);
        buff.put(Stat.STRENGTH, weirder ? 40.0 : 30.0);
        buff.put(Stat.SPEED, 30.0);
        if (weirder) {
            buff.put(Stat.ATTACK_SPEED, 5.0);
            buff.put(Stat.CRIT_DAMAGE, 10.0);
        }
        long until = System.currentTimeMillis() + (weirder ? 30_000L : 20_000L);
        List<Player> who = new ArrayList<>();
        who.add(p);
        List<Player> others = new ArrayList<>();
        for (Player o : p.getWorld().getPlayers()) {
            if (o != p && o.getLocation().distanceSquared(p.getLocation()) <= 15 * 15) others.add(o);
        }
        others.sort((a, b) -> Double.compare(a.getLocation().distanceSquared(p.getLocation()), b.getLocation().distanceSquared(p.getLocation())));
        for (int i = 0; i < Math.min(4, others.size()); i++) who.add(others.get(i));
        for (Player o : who) {
            howl.put(o.getUniqueId(), buff);
            howlUntil.put(o.getUniqueId(), until);
            Sky.stats().invalidate(o);
            Msg.send(o, "&6" + p.getName() + " &7howled! &a+" + (weirder ? 40 : 30) + " &c❁ Strength &7for " + (weirder ? 30 : 20) + "s");
        }
        Fx.sound(p.getLocation(), Sound.ENTITY_WOLF_BIG_GROWL, 1.2f, 0.8f);
        return true;
    }

    private boolean lifeTap(AbilityService.Use u) {
        Player p = u.player();
        if (Sky.vitals().health(p) <= 800) {
            Msg.error(p, "You need more than 800❤ to use Life Tap!");
            return false;
        }
        Sky.vitals().setHealth(p, Sky.vitals().health(p) - 800);
        Sky.vitals().addMana(p, 600);
        Fx.particle(p.getLocation().add(0, 1, 0), Particle.ENCHANT, 25, 0.5, 0.5);
        return true;
    }

    private boolean bundle(AbilityService.Use u) {
        Player p = u.player();
        ItemDef def = u.itemDef();
        JsonElement b = def.extra == null ? null : def.extra.get("book");
        String book = b == null ? null : b.getAsString();
        if (book == null || !Sky.items().exists(book)) {
            Msg.error(p, "This bundle's book is not available yet. Keep it - it will open in a later update.");
            return false;
        }
        if (Sky.items().remove(p, def.id, 1) < 1) return false;
        Sky.loot().give(p, book, 1, LootSource.REWARD);
        Msg.success(p, "You opened the bundle!");
        return true;
    }

    /** A Bloodfiend died to a stake (Razor Steak Stake: Bloodcrave, next hit x2). */
    void stakeKill(Player p, String stakeId) {
        if ("SHARP_STEAK_STAKE".equals(stakeId)) bloodcrave.add(p.getUniqueId());
    }

    // ---- stats ------------------------------------------------------------------------------------

    private void stats(Player p, StatMap into) {
        Deploy d = applied.get(p.getUniqueId());
        if (d != null) {
            d.flat().forEach(into::add);
            d.percent().forEach(into::addPercent);
        }
        Map<Stat, Double> h = howl.get(p.getUniqueId());
        if (h != null) h.forEach(into::add);
        if (!Sky.profiles().isReady(p)) return;
        SlayerData data = mod.quests.data(p);
        if (data.mixins.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : data.mixins.entrySet()) {
            if (e.getValue() <= now) continue;
            Object[] m = MIXINS.get(e.getKey());
            if (m == null || m[0] == null) continue;     // behaviour mixins (Wolf Fur, End Portal Fumes)
            if ((Boolean) m[2]) into.addPercent((Stat) m[0], (Double) m[1]);
            else into.add((Stat) m[0], (Double) m[1]);
        }
    }

    void shutdown() {
        for (Live l : deployed.values()) if (l.display != null) l.display.remove();
        deployed.clear();
        for (org.bukkit.scheduler.BukkitTask t : dots) t.cancel();
        dots.clear();
        tactical.clear();
    }

    // ---- Tactical Insertion (Gorilla Tactics) / Voodoo Doll (Acupuncture) ------------------------
    // Finish wave 2026-10-03. Research: NEU TACTICAL_INSERTION / VOODOO_DOLL lore (numbers verbatim).

    /** Player -> damage dealt since they marked their Tactical Insertion (null when no mark is live). */
    private final Map<UUID, double[]> tactical = new HashMap<>();
    private final List<org.bukkit.scheduler.BukkitTask> dots = new ArrayList<>();
    /** Monster -> tick its Acupuncture ends (re-casting refreshes instead of stacking). */
    private final Map<UUID, Long> voodooUntil = new HashMap<>();

    private void trackDealt(DamageContext ctx) {
        if (tactical.isEmpty()) return;
        Player p = ctx.playerAttacker();
        if (p == null || ctx.victim instanceof Player || ctx.finalDamage <= 0) return;
        double[] d = tactical.get(p.getUniqueId());
        if (d != null) d[0] += ctx.finalDamage;
    }

    /** A monster the player may hurt: a hostile sky mob or vanilla enemy that is not someone else's boss. */
    private static boolean hittable(Player p, org.bukkit.entity.Entity e) {
        if (!(e instanceof org.bukkit.entity.LivingEntity le) || le instanceof Player || le.isDead() || !le.isValid()) return false;
        if (le instanceof org.bukkit.entity.ArmorStand) return false;
        if (!Sky.mobs().isSkyMob(le) && !(le instanceof org.bukkit.entity.Enemy)) return false;
        UUID owner = Sky.mobs().owner(le);
        return owner == null || owner.equals(p.getUniqueId());
    }

    private boolean tactical(AbilityService.Use u) {
        Player p = u.player();
        UUID id = p.getUniqueId();
        if (tactical.containsKey(id)) { Msg.error(p, "Your Tactical Insertion is already marked!"); return false; }
        Location mark = p.getLocation().clone();
        double[] dealt = {0};
        tactical.put(id, dealt);
        Fx.particle(mark.clone().add(0, 0.1, 0), Particle.FLAME, 30, 0.4, 0.02);
        Fx.sound(p, Sound.ITEM_FIRECHARGE_USE, 1f, 1.2f);
        Sky.vitals().flash(p, "&5Gorilla Tactics! &7Back in &e3s", 30);
        for (int i = 1; i <= 2; i++) {
            int left = 3 - i;
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (!p.isOnline() || !tactical.containsKey(id)) return;
                Fx.particle(mark.clone().add(0, 0.1, 0), Particle.FLAME, 15, 0.3, 0.01);
                Sky.vitals().flash(p, "&5Gorilla Tactics &7back in &e" + left + "s", 22);
            }, 20L * i);
        }
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            tactical.remove(id);
            if (!p.isOnline() || p.isDead() || p.getWorld() != mark.getWorld()) return;
            Location back = mark.clone();
            back.setYaw(p.getLocation().getYaw());
            back.setPitch(p.getLocation().getPitch());
            p.teleport(back);
            Sky.vitals().setHealth(p, Math.max(1, Math.floor(Sky.vitals().health(p) / 2)));
            Fx.sound(back, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f);
            Fx.particle(back.clone().add(0, 1, 0), Particle.FLAME, 60, 1.5, 0.05);
            double perSecond = Math.floor(dealt[0] * 0.10 / 6);
            List<org.bukkit.entity.LivingEntity> burn = new ArrayList<>();
            if (perSecond > 0) {
                for (org.bukkit.entity.Entity e : back.getWorld().getNearbyEntities(back, 3, 3, 3)) {
                    if (hittable(p, e)) burn.add((org.bukkit.entity.LivingEntity) e);
                }
            }
            Msg.send(p, "&5Gorilla Tactics! &7You came back"
                    + (burn.isEmpty() ? "." : " and set &c" + burn.size() + " &7enem" + (burn.size() == 1 ? "y" : "ies")
                    + " alight for &c" + Text.commas(perSecond) + "&7/s over &a6s&7."));
            if (burn.isEmpty()) return;
            int[] ticks = {0};
            org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
            task[0] = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
                if (++ticks[0] > 6) { task[0].cancel(); dots.remove(task[0]); return; }
                for (org.bukkit.entity.LivingEntity le : burn) {
                    if (le.isDead() || !le.isValid()) continue;
                    Sky.combat().rawDamage(le, perSecond, p, DamageContext.Kind.MAGIC);
                    Fx.particle(le.getLocation().add(0, 1, 0), Particle.FLAME, 8, 0.3, 0.01);
                }
            }, 20L, 20L);
            dots.add(task[0]);
        }, 60L);
        return true;
    }

    /** Acupuncture: {@code dps} damage per second for {@code seconds} (Voodoo Doll 1,500/10s, Jinxed 2,222/12s). */
    private boolean voodoo(AbilityService.Use u, double dps, int seconds) {
        Player p = u.player();
        Location eye = p.getEyeLocation();
        org.bukkit.util.RayTraceResult hit = p.getWorld().rayTraceEntities(eye, eye.getDirection(), 20, 0.6,
                e -> e != p && hittable(p, e));
        org.bukkit.entity.LivingEntity target = hit != null && hit.getHitEntity() instanceof org.bukkit.entity.LivingEntity le ? le : null;
        if (target == null) { Msg.error(p, "There is no monster in your sight!"); return false; }
        Location c = target.getLocation().add(0, target.getHeight() / 2, 0);
        // the "arrows from every direction": 8 streaks converging on the target
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            Location from = c.clone().add(Math.cos(a) * 4, (i % 2 == 0 ? 1.5 : -0.5), Math.sin(a) * 4);
            Fx.line(from, c, org.bukkit.Color.fromRGB(0xB0, 0x40, 0xFF), 0.4);
        }
        Fx.sound(c, Sound.ENTITY_ARROW_SHOOT, 1f, 0.7f);
        Fx.sound(c, Sound.ENTITY_WITCH_CELEBRATE, 0.6f, 1.4f);
        List<org.bukkit.entity.LivingEntity> victims = new ArrayList<>();
        victims.add(target);
        for (org.bukkit.entity.Entity e : target.getNearbyEntities(1.5, 1.5, 1.5)) {
            if (e != target && hittable(p, e)) victims.add((org.bukkit.entity.LivingEntity) e);
        }
        long now = Bukkit.getCurrentTick();
        int started = 0;
        for (org.bukkit.entity.LivingEntity le : victims) {
            le.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 20 * seconds, 2, false, true));
            Long until = voodooUntil.put(le.getUniqueId(), now + 20L * seconds);
            if (until != null && until > now) continue;   // already pricked: refreshed, not stacked
            started++;
            org.bukkit.scheduler.BukkitTask[] task = new org.bukkit.scheduler.BukkitTask[1];
            task[0] = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
                Long end = voodooUntil.get(le.getUniqueId());
                if (le.isDead() || !le.isValid() || end == null || Bukkit.getCurrentTick() > end) {
                    if (end != null && Bukkit.getCurrentTick() > end) voodooUntil.remove(le.getUniqueId());
                    if (le.isDead() || !le.isValid()) voodooUntil.remove(le.getUniqueId());
                    task[0].cancel();
                    dots.remove(task[0]);
                    return;
                }
                Sky.combat().rawDamage(le, dps, p.isOnline() ? p : null, DamageContext.Kind.MAGIC);
                Fx.particle(le.getLocation().add(0, le.getHeight() / 2, 0), Particle.CRIT, 6, 0.3, 0.05);
            }, 20L, 20L);
            dots.add(task[0]);
        }
        Sky.vitals().flash(p, "&5Acupuncture! &7" + victims.size() + " pricked"
                + (started < victims.size() ? " &8(refreshed)" : ""), 30);
        return true;
    }

    /** Remaining deployable seconds for a player's own deployable, 0 if none (for the menu/admin). */
    long deployLeft(Player p) {
        Live l = deployed.get(p.getUniqueId());
        return l == null ? 0 : Math.max(0, (l.until - System.currentTimeMillis()) / 1000);
    }
}
