package com.skyclone.mod.reforges;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.config.Dials;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reforge BONUSES implemented as behaviour (the stats themselves are data rendered by core). Each
 * case reads its numbers from the generated table at the item's effective rarity, so the code
 * holds no magic values. Bonuses whose systems belong to other modules (mithril powder, heat, cold,
 * pests, festivals) stay as lore and are listed in the hand-off.
 */
final class Bonuses implements Listener {

    static final Set<String> IMPLEMENTED = Set.of("withered", "ancient", "perfect", "renowned", "empowered", "fruitful", "auspicious",
            "hyper", "ridiculous", "fabled", "fanged", "headstrong", "precise", "undead", "cubic", "calcified", "gilded",
            "blessed", "bountiful", "refined", "magnetic", "mithraic", "scraped", "dimensional", "lustrous", "royal", "candied", "fleet", "stellar",
            "glacial", "heated", "ambered", "blazing", "mantid", "chomp", "spiritual", "majestic");

    /** The live instance (admin {@code /sb reforges bonuses}). */
    static Bonuses INSTANCE;

    private static final Set<String> MINING_ISLANDS = Set.of("gold_mine", "deep_caverns", "dwarven_mines", "crystal_hollows", "glacite_tunnels");
    private static final Set<String> UNDEAD = Set.of("ZOMBIE", "ZOMBIE_VILLAGER", "HUSK", "DROWNED", "ZOMBIFIED_PIGLIN", "SKELETON",
            "STRAY", "BOGGED", "WITHER_SKELETON", "WITHER");
    private static final Set<String> NETHER = Set.of("BLAZE", "GHAST", "MAGMA_CUBE", "WITHER_SKELETON", "PIGLIN", "PIGLIN_BRUTE",
            "ZOMBIFIED_PIGLIN", "HOGLIN", "ZOGLIN", "STRIDER");
    private static final Stat[] COMBAT = {Stat.HEALTH, Stat.DEFENSE, Stat.TRUE_DEFENSE, Stat.STRENGTH, Stat.INTELLIGENCE, Stat.CRIT_CHANCE,
            Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED, Stat.FEROCITY, Stat.ABILITY_DAMAGE, Stat.MAGIC_FIND};

    /** Short-lived per-player state. */
    private static final class Temp {
        long hyperUntil; double hyperSpeed;
        long critUntil; double critPenalty;
        long defUntil; double defBonus;
        long sneakReady;
        int meleeHits;
        long starCheckedAt; boolean nearStar; long starSeenUntil;
        /** Mantid: expiry time of each stack (one per pest slain). */
        final List<Long> mantid = new ArrayList<>();
        /** Power-scroll stat buffs: stat -> {value, until}. */
        final java.util.EnumMap<Stat, double[]> scrollBuff = new java.util.EnumMap<>(Stat.class);
        /** Power-scroll cooldowns: scroll id -> ready-at. */
        final Map<String, Long> scrollReady = new HashMap<>();
    }

    /** Mantid stacks still running at {@code now} (pure; also prunes expired ones). */
    static int activeStacks(List<Long> expiries, long now) {
        expiries.removeIf(t -> t <= now);
        return expiries.size();
    }

    /** Mantid per piece: +per Bonus Pest Chance per stack, at most cap (reference: 0.25 per pest, up to 5). */
    static double mantidBonus(int stacks, double per, double cap) {
        return Math.max(0, Math.min(cap, stacks * per));
    }

    /** Chomp: the health cut for a rod that has slain {@code distinct} lava sea creature kinds (1% each, max 20%). */
    static double chompPct(int distinct, double per, double cap) {
        return Math.max(0, Math.min(cap, distinct * per));
    }

    /** Stellar: how far a Fallen Star counts as "near" (blocks; TUNE), and how often we look for one. */
    static final int STAR_RADIUS = 12, STAR_HEIGHT = 6;
    static final long STAR_RECHECK_MS = 5000, STAR_MINED_MS = 30000;

    /**
     * Is a Fallen Star near this player? A star is the mining module's crying-obsidian heart (ringed
     * with magma) in the Dwarven Mines. Mining STARFALL proves it outright; otherwise the area is
     * scanned at most every 5 s, and only for players holding a Stellar tool there.
     */
    private boolean nearStar(Player p, String island) {
        if (!"dwarven_mines".equals(island)) return false;
        Temp tp = t(p);
        long now = System.currentTimeMillis();
        if (tp.starSeenUntil > now) return true;
        if (now - tp.starCheckedAt < STAR_RECHECK_MS) return tp.nearStar;
        tp.starCheckedAt = now;
        org.bukkit.World w = p.getWorld();
        int cx = p.getLocation().getBlockX(), cy = p.getLocation().getBlockY(), cz = p.getLocation().getBlockZ();
        boolean found = false;
        outer:
        for (int dx = -STAR_RADIUS; dx <= STAR_RADIUS; dx++)
            for (int dz = -STAR_RADIUS; dz <= STAR_RADIUS; dz++) {
                if (!w.isChunkLoaded((cx + dx) >> 4, (cz + dz) >> 4)) continue;
                for (int dy = -STAR_HEIGHT; dy <= STAR_HEIGHT; dy++) {
                    if (w.getBlockAt(cx + dx, cy + dy, cz + dz).getType() == org.bukkit.Material.CRYING_OBSIDIAN) { found = true; break outer; }
                }
            }
        tp.nearStar = found;
        return found;
    }

    private final Map<UUID, Temp> temp = new HashMap<>();
    private final Map<UUID, Double> cropCoins = new HashMap<>();
    /** victim -> {shooter, tick} of the last arrow that hit its head. */
    private final Map<UUID, Object[]> headshots = new HashMap<>();
    private final Map<UUID, Object[]> wornCache = new HashMap<>();

    private Temp t(Player p) { return temp.computeIfAbsent(p.getUniqueId(), k -> new Temp()); }

    void register() {
        Sky.stats().addProvider("reforges", this::stats);
        Sky.combat().addModifier("reforges_offense", 150, this::offense);
        Sky.combat().addModifier("reforges_defense", 250, this::defense);
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::flushCropCoins, 100L, 100L);
    }

    /** Reforged gear in effect, cached for the current tick (stat + damage paths call this a lot). */
    @SuppressWarnings("unchecked")
    private List<Gear.Worn> worn(Player p) {
        int tick = Bukkit.getCurrentTick();
        Object[] c = wornCache.get(p.getUniqueId());
        if (c != null && (int) c[0] == tick) return (List<Gear.Worn>) c[1];
        List<Gear.Worn> w = Gear.worn(p);
        wornCache.put(p.getUniqueId(), new Object[]{tick, w});
        return w;
    }

    private Gear.Worn held(Player p, String reforge) {
        for (Gear.Worn w : worn(p)) if (w.held() && w.row().id.equals(reforge)) return w;
        return null;
    }

    private static double v(Gear.Worn w, int idx, double def) { return ReforgeData.bonusValue(w.row(), w.rarity(), idx, def); }

    // ---- stats --------------------------------------------------------------------------------------

    private void stats(Player p, StatMap m) {
        List<Gear.Worn> list = worn(p);
        long now = System.currentTimeMillis();
        Temp tp = temp.get(p.getUniqueId());
        if (tp != null) {
            if (tp.hyperUntil > now) m.add(Stat.SPEED, tp.hyperSpeed);
            if (tp.critUntil > now) m.add(Stat.CRIT_CHANCE, -tp.critPenalty);
            if (tp.defUntil > now) m.add(Stat.DEFENSE, tp.defBonus);
            for (Map.Entry<Stat, double[]> b : tp.scrollBuff.entrySet()) if (b.getValue()[1] > now) m.add(b.getKey(), b.getValue()[0]);
        }
        if (list.isEmpty()) return;
        int cata = -1, mantidStacks = -1;
        String island = null;
        for (Gear.Worn w : list) {
            switch (w.row().id) {
                case "withered" -> {
                    if (!w.held()) break;
                    if (cata < 0) cata = Math.max(0, Sky.service(Features.Dungeons.class).catacombsLevel(p));
                    m.add(Stat.STRENGTH, v(w, 0, 1) * cata);
                }
                case "ancient" -> {
                    if (w.held()) break;
                    if (cata < 0) cata = Math.max(0, Sky.service(Features.Dungeons.class).catacombsLevel(p));
                    m.add(Stat.CRIT_DAMAGE, v(w, 0, 1) * cata);
                }
                case "perfect" -> { if (!w.held()) m.addPercent(Stat.DEFENSE, v(w, 0, 2)); }
                case "renowned" -> { if (!w.held()) for (Stat s : COMBAT) m.addPercent(s, v(w, 0, 1)); }
                case "empowered" -> {
                    if (!w.held() && Sky.service(Features.Dungeons.class).inDungeon(p)) m.add(Stat.MENDING, v(w, 0, 10));
                }
                case "fruitful" -> {
                    if (!w.held()) break;
                    if (island == null) { island = Sky.worlds().islandOf(p.getWorld()); if (island == null) island = ""; }
                    if (MINING_ISLANDS.contains(island)) m.add(Stat.DEFENSE, v(w, 0, 25));
                }
                case "auspicious" -> { if (w.held()) m.addPercent(Stat.MINING_FORTUNE, v(w, 0, 0.4)); }
                case "glacial" -> {
                    // +x Mining Fortune per Cold (reforges request 12; Cold from the mining module)
                    if (!w.held()) break;
                    int cold = 0;
                    try { cold = Sky.service(Features.Mining.class).cold(p); } catch (Throwable ignored) { }
                    if (cold > 0) m.add(Stat.MINING_FORTUNE, v(w, 0, 0.5) * cold);
                }
                case "heated" -> {
                    // +x Mining Fortune and +y Mining Speed per Heat
                    if (!w.held()) break;
                    int heat = 0;
                    try { heat = Sky.service(Features.Mining.class).heat(p); } catch (Throwable ignored) { }
                    if (heat > 0) {
                        m.add(Stat.MINING_FORTUNE, v(w, 0, 0.3) * heat);
                        m.add(Stat.MINING_SPEED, v(w, 1, 1) * heat);
                    }
                }
                case "lustrous" -> { if (w.held() && MiningBlocks.fiesta()) m.add(Stat.MINING_SPREAD, v(w, 0, 25)); }
                case "majestic" -> { if (!w.held() && safariActive(p)) m.add(Stat.SPEED, v(w, 0, 50)); }
                case "mantid" -> {
                    // armor, per piece: each piece pays its own +0.25/stack up to its own cap (TUNE: per-piece reading of the lore)
                    if (w.held() || tp == null) break;
                    if (mantidStacks < 0) mantidStacks = activeStacks(tp.mantid, now);
                    if (mantidStacks > 0) m.add(Stat.PEST_CHANCE, mantidBonus(mantidStacks, v(w, 0, 0.25), v(w, 2, 5)));
                }
                case "coldfused" -> {
                    // reference: "Only if Wisp is equipped" (any of the four Wisp pets); the fire-pillar part is the slayer's
                    if (!w.held()) break;
                    String pet = null;
                    try { pet = Sky.service(Features.Pets.class).activeType(p); } catch (Throwable ignored) { }
                    if (pet != null && pet.toUpperCase(java.util.Locale.ROOT).endsWith("WISP")) {
                        m.add(Stat.STRENGTH, v(w, 0, 75));
                        m.add(Stat.CRIT_DAMAGE, v(w, 1, 55));
                    }
                }
                case "stellar" -> {
                    if (!w.held()) break;
                    if (island == null) { island = Sky.worlds().islandOf(p.getWorld()); if (island == null) island = ""; }
                    if (nearStar(p, island)) m.add(Stat.MINING_FORTUNE, v(w, 1, 55));
                }
                case "fleet" -> {
                    // the sheet is recomputed every 10 ticks; the block you are digging is the one you look at
                    if (!w.held()) break;
                    if (island == null) { island = Sky.worlds().islandOf(p.getWorld()); if (island == null) island = ""; }
                    if (island.isEmpty()) break;
                    org.bukkit.block.Block tb = p.getTargetBlockExact(5);
                    if (tb != null && MiningBlocks.plain(MiningBlocks.kind(island, tb.getType()))) m.add(Stat.MINING_SPREAD, v(w, 0, 100));
                }
                default -> { }
            }
        }
    }

    // ---- outgoing damage ---------------------------------------------------------------------------

    private void offense(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null || ctx.victim == null || ctx.victim instanceof Player) return;
        for (Gear.Worn w : worn(p)) {
            if (!w.held()) continue;
            switch (w.row().id) {
                case "fabled" -> {
                    if (ctx.crit && (ctx.kind == DamageContext.Kind.MELEE || ctx.kind == DamageContext.Kind.FEROCITY))
                        ctx.additive += ThreadLocalRandom.current().nextDouble() * v(w, 0, 15);
                }
                case "fanged" -> {
                    if (ctx.kind != DamageContext.Kind.MELEE) break;
                    Temp tp = t(p);
                    int every = (int) Math.max(1, v(w, 0, 7));
                    if (++tp.meleeHits % every == 0) { ctx.additive += v(w, 1, 100); ctx.tags.add("fanged"); }
                }
                case "headstrong", "precise" -> {
                    if (ctx.kind != DamageContext.Kind.PROJECTILE) break;
                    Object[] h = headshots.get(ctx.victim.getUniqueId());
                    if (h != null && p.getUniqueId().equals(h[0]) && Bukkit.getCurrentTick() - (int) h[1] <= 2) {
                        ctx.additive += v(w, 0, 8);
                        ctx.tags.add("headshot");
                    }
                }
                default -> { }
            }
        }
    }

    // ---- incoming damage ---------------------------------------------------------------------------

    private void defense(DamageContext ctx) {
        Player p = ctx.playerVictim();
        if (p == null || ctx.attacker == null || ctx.attacker instanceof Player) return;
        List<Gear.Worn> list = worn(p);
        if (list.isEmpty()) return;
        String type = ctx.attacker.getType().name();
        boolean undead = UNDEAD.contains(type);
        boolean nether = NETHER.contains(type) || "crimson_isle".equals(Sky.worlds().islandOf(ctx.attacker.getWorld()));
        MobDef md = null;
        double undeadCut = 0, netherCut = 0, seaDef = 0;
        for (Gear.Worn w : list) {
            if (w.held()) continue;
            switch (w.row().id) {
                case "undead" -> { if (undead) undeadCut += v(w, 0, 2); }
                case "cubic" -> { if (nether) netherCut += v(w, 0, 2); }
                case "calcified" -> {
                    if (ctx.kind == DamageContext.Kind.TRUE) break;
                    if (md == null) md = Sky.mobs().defOf(ctx.attacker);
                    if (md != null && md.has("sea_creature")) seaDef += v(w, 0, 25);
                }
                default -> { }
            }
        }
        if (undeadCut > 0) ctx.multiplicative *= Math.max(0, 1 - undeadCut / 100.0);
        if (netherCut > 0) ctx.multiplicative *= Math.max(0, 1 - netherCut / 100.0);
        if (seaDef > 0) {
            double d = Math.max(0, Sky.stats().get(p, Stat.DEFENSE));
            ctx.multiplicative *= (100 + d) / (100 + d + seaDef);
        }
    }

    // ---- events --------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        if (!(pr.getShooter() instanceof Player shooter) || !(e.getHitEntity() instanceof LivingEntity victim)) return;
        double headBottom = victim.getEyeLocation().getY() - 0.35;
        if (pr.getLocation().getY() >= headBottom) {
            headshots.put(victim.getUniqueId(), new Object[]{shooter.getUniqueId(), Bukkit.getCurrentTick()});
            if (headshots.size() > 512) headshots.clear();
        }
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || !p.isOnline()) return;
        candied(p, e);
        double speed = 0, secs = 0, mantidMinutes = 0;
        for (Gear.Worn w : worn(p)) {
            if (!w.held() && w.row().id.equals("hyper")) { speed += v(w, 0, 1); secs = Math.max(secs, v(w, 1, 5)); }
            if (w.held() && w.row().id.equals("gilded")) gilded(p);
            if (!w.held() && w.row().id.equals("mantid")) mantidMinutes = Math.max(mantidMinutes, v(w, 1, 10));
            if (w.held() && w.row().id.equals("chomp")) chompKill(p, w, e.def);
            if (w.held() && w.row().id.equals("spiritual")) spiritual(p, w, e);
        }
        if (mantidMinutes > 0 && isPest(e.def)) {
            Temp tp = t(p);
            long now = System.currentTimeMillis();
            activeStacks(tp.mantid, now);
            if (tp.mantid.size() >= MANTID_MAX_STACKS) tp.mantid.remove(0);   // oldest first: a fresh kill always refreshes
            tp.mantid.add(now + (long) (mantidMinutes * 60_000));
            Sky.stats().invalidate(p);
        }
        if (speed > 0) {
            Temp tp = t(p);
            tp.hyperSpeed = speed;
            tp.hyperUntil = System.currentTimeMillis() + (long) (secs * 1000);
            Sky.stats().invalidate(p);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) Sky.stats().invalidate(p); }, (long) (secs * 20) + 1);
        }
    }

    /** Research 05 §7.6: a mob drops candy 10% of the time during the festival, one in ten of those Purple. */
    static final double CANDY_BASE = 0.10, CANDY_PURPLE = 0.10;
    static final java.util.List<String> SPOOKY_EVENTS = java.util.List.of("spooky_festival", "spooky_festival_extra");

    /**
     * Candied (armor): +X% chance to find candy during the autumn festival. The festival owner rolls the
     * base chance; this rolls only the extra the reforge adds (base x sum of pieces' %), so the total
     * matches "+X% candy chance" without touching the other module.
     */
    private void candied(Player p, SkyEvents.MobKill e) {
        double pct = 0;
        for (Gear.Worn w : worn(p)) if (!w.held() && w.row().id.equals("candied")) pct += v(w, 0, 1);
        if (pct <= 0) return;
        if (e.def != null && "spooky".equals(e.def.familyId())) return;
        boolean on = false;
        try { for (String ev : SPOOKY_EVENTS) if (Sky.calendar().active(ev)) { on = true; break; } } catch (Throwable ignored) { }
        if (!on) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() >= CANDY_BASE * pct / 100.0) return;
        String id = r.nextDouble() < CANDY_PURPLE ? "PURPLE_CANDY" : "GREEN_CANDY";
        if (Sky.items().exists(id)) Sky.loot().give(p, id, 1, LootSource.EVENT);
    }

    private void gilded(Player killer) {
        if (ThreadLocalRandom.current().nextDouble() >= ReforgesModule.gildedChance.get()) return;
        List<Player> near = new ArrayList<>();
        for (Entity en : killer.getNearbyEntities(15, 8, 15)) if (en instanceof Player o && o != killer && Sky.profiles().isReady(o)) near.add(o);
        if (near.isEmpty()) return;
        Player lucky = near.get(ThreadLocalRandom.current().nextInt(near.size()));
        double amount = 1 + Math.floor(ThreadLocalRandom.current().nextDouble() * Math.max(1, ReforgesModule.gildedMax.get()));
        Sky.economy().deposit(lucky, amount, "reforges: gilded shower from " + killer.getName());
        Msg.send(lucky, "&6" + killer.getName() + "'s gilded blade showers you with &6" + Text.commasInt(amount) + " coins&6!");
        Msg.send(killer, "&6Your gilded blade showered " + lucky.getName() + " with " + Text.commasInt(amount) + " coins!");
        Msg.sound(lucky, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.8f);
    }

    // ---- Majestic ----------------------------------------------------------------------------------

    /** Requirement key the hunting module answers while a Critter Safari hunt runs (hand-off request 16). */
    static final String SAFARI_PREFIX = "safari", SAFARI_KEY = "safari:active";

    /** Is a Critter Safari hunt running for this player? False until hunting registers the {@code safari} prefix. */
    static boolean safariActive(Player p) {
        try { return Sky.requirements().known(SAFARI_PREFIX) && Sky.requirements().check(p, SAFARI_KEY); }
        catch (RuntimeException e) { return false; }
    }

    // ---- Mantid ------------------------------------------------------------------------------------

    /** Stacks kept per player (the cap is reached at 20 with the reference numbers; the rest is headroom). */
    static final int MANTID_MAX_STACKS = 64;

    /** A garden pest (the garden module's mob ids; any module may also use family "pest"). */
    static boolean isPest(MobDef def) {
        return def != null && ((def.id != null && def.id.startsWith("garden_pest_")) || "pest".equals(def.family) || def.has("pest"));
    }

    // ---- Chomp -------------------------------------------------------------------------------------

    /** Lava sea creature: the fishing module's defs carry flag {@code sea_creature} and {@code extra.lava = true}. */
    static boolean lavaSeaCreature(MobDef def) {
        if (def == null || !def.has("sea_creature") || def.extra == null || !def.extra.has("lava")) return false;
        try { return def.extra.get("lava").getAsBoolean(); } catch (RuntimeException ex) { return false; }
    }

    /** Distinct lava sea creature ids this rod has slain (stored on the rod: "with this rod"). */
    static int chompCount(ItemData d) {
        if (d == null || d.extra == null || !d.extra.has(CHOMP_KEY) || !d.extra.get(CHOMP_KEY).isJsonArray()) return 0;
        return d.extra.getAsJsonArray(CHOMP_KEY).size();
    }

    static final String CHOMP_KEY = "chomp_slain";

    private void chompKill(Player p, Gear.Worn w, MobDef def) {
        if (!lavaSeaCreature(def) || def.id == null) return;
        ItemStack rod = w.stack();
        ItemData d = Sky.items().data(rod);
        int max = (int) Math.ceil(v(w, 1, 20) / Math.max(0.01, v(w, 0, 1)));
        if (chompCount(d) >= max) return;
        if (d.extra != null && d.extra.has(CHOMP_KEY) && d.extra.get(CHOMP_KEY).isJsonArray())
            for (com.google.gson.JsonElement el : d.extra.getAsJsonArray(CHOMP_KEY)) if (def.id.equals(el.getAsString())) return;
        String id = def.id;
        Sky.items().update(rod, x -> {
            com.google.gson.JsonArray arr = x.extra.has(CHOMP_KEY) && x.extra.get(CHOMP_KEY).isJsonArray()
                    ? x.extra.getAsJsonArray(CHOMP_KEY) : new com.google.gson.JsonArray();
            arr.add(id);
            x.extra.add(CHOMP_KEY, arr);
        });
        // the stack we updated may be a copy of the hand item: write it back while the hand still holds that rod
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (d.uuid != null && !Gear.empty(hand) && Gear.def(hand) != null && d.uuid.equals(Sky.items().data(hand).uuid))
            p.getInventory().setItemInMainHand(rod);
        double pct = chompPct(chompCount(Sky.items().data(rod)), v(w, 0, 1), v(w, 1, 20));
        Msg.send(p, "&9Chomp! &7Your rod has a taste for " + Text.strip(def.name == null ? id : def.name) + "&7. Lava sea creatures you hook now have &a-"
                + Text.commas(pct) + "% &c❤ Health&7.");
    }

    /** victim uuids already trimmed (a sea creature is trimmed once). */
    private final java.util.Set<UUID> chomped = new java.util.HashSet<>();

    @EventHandler
    public void onFishCaught(SkyEvents.FishCaught e) {
        if (e.player == null || !e.seaCreature || !e.lava || e.what == null) return;
        Player p = e.player;
        Gear.Worn rod = held(p, "chomp");
        if (rod == null) return;
        double pct = chompPct(chompCount(Sky.items().data(rod.stack())), v(rod, 0, 1), v(rod, 1, 20));
        if (pct <= 0) return;
        String mobId = e.what;
        UUID owner = p.getUniqueId();
        // the fishing module spawns the creature (owned by the angler) around the event; look a tick later
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (chomped.size() > 2048) chomped.clear();
            for (LivingEntity le : Sky.mobs().live(mobId)) {
                if (!le.isValid() || le.getTicksLived() > 60 || !owner.equals(Sky.mobs().owner(le)) || !chomped.add(le.getUniqueId())) continue;
                double max = Sky.mobs().maxHealth(le), nm = Math.max(1, max * (1 - pct / 100.0));
                Sky.mobs().setMaxHealth(le, nm);
                Sky.mobs().setHealth(le, Math.min(Sky.mobs().health(le), nm));
            }
        }, 2L);
    }

    // ---- Spiritual ---------------------------------------------------------------------------------

    /** One live spirit decoy: who summoned it and until when it holds the room's attention. */
    private record Decoy(UUID owner, org.bukkit.entity.ArmorStand stand, long until) {}

    private final List<Decoy> decoys = new ArrayList<>();
    private int decoyTask = -1;

    private void spiritual(Player p, Gear.Worn w, SkyEvents.MobKill e) {
        boolean inDungeon = false;
        try { inDungeon = Sky.service(Features.Dungeons.class).inDungeon(p); } catch (Throwable ignored) { }
        if (!inDungeon || e.location == null || e.location.getWorld() == null) return;
        if (ThreadLocalRandom.current().nextDouble() * 100 >= v(w, 0, 10)) return;
        decoys.removeIf(d -> d.owner.equals(p.getUniqueId()) && retire(d));   // one decoy per player at a time
        Location at = e.location.clone();
        org.bukkit.entity.ArmorStand st = at.getWorld().spawn(at, org.bukkit.entity.ArmorStand.class, a -> {
            a.setSmall(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setPersistent(false);
            a.setGlowing(true);
            a.setBasePlate(false);
            a.customName(Text.c("&b" + p.getName() + "'s Spirit Decoy"));
            a.setCustomNameVisible(true);
            a.getEquipment().setHelmet(new ItemStack(org.bukkit.Material.SOUL_LANTERN));
            a.getPersistentDataContainer().set(DECOY_TAG, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        });
        decoys.add(new Decoy(p.getUniqueId(), st, System.currentTimeMillis() + ReforgesModule.decoySeconds.get() * 1000L));
        Msg.send(p, "&b&lSPIRIT DECOY! &7Nearby foes turn on your decoy.");
        Msg.sound(p, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.8f);
        if (decoyTask < 0) decoyTask = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tickDecoys, 1L, 5L).getTaskId();
    }

    static final org.bukkit.NamespacedKey DECOY_TAG = new org.bukkit.NamespacedKey("skyclone", "reforges_decoy");

    private boolean retire(Decoy d) {
        if (d.stand.isValid()) d.stand.remove();
        return true;
    }

    /** Every 5 ticks: foes near a live decoy drop the summoner as a target and walk to the decoy. */
    private void tickDecoys() {
        long now = System.currentTimeMillis();
        decoys.removeIf(d -> (d.until <= now || !d.stand.isValid()) && retire(d));
        if (decoys.isEmpty()) {
            if (decoyTask >= 0) { Bukkit.getScheduler().cancelTask(decoyTask); decoyTask = -1; }
            return;
        }
        double r = ReforgesModule.decoyRadius.get();
        for (Decoy d : decoys) {
            Location c = d.stand.getLocation();
            c.getWorld().spawnParticle(Particle.SOUL, c.clone().add(0, 0.8, 0), 3, 0.25, 0.3, 0.25, 0.01);
            for (Entity en : d.stand.getNearbyEntities(r, r / 2, r)) {
                if (!(en instanceof org.bukkit.entity.Mob m) || !Sky.mobs().isSkyMob(m) || !m.hasAI()) continue;
                if (m.getTarget() != null && d.owner.equals(m.getTarget().getUniqueId())) m.setTarget(null);
                m.getPathfinder().moveTo(c, 1.1);
            }
        }
    }

    /** While a decoy is up, foes near it cannot (re)target the summoner (core retargets idle mobs to the nearest player). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent e) {
        if (decoys.isEmpty() || !(e.getTarget() instanceof Player t)) return;
        double r = ReforgesModule.decoyRadius.get();
        for (Decoy d : decoys) {
            if (!d.owner.equals(t.getUniqueId()) || !d.stand.isValid() || d.stand.getWorld() != e.getEntity().getWorld()) continue;
            if (d.stand.getLocation().distanceSquared(e.getEntity().getLocation()) <= r * r) { e.setCancelled(true); return; }
        }
    }

    /** Module disable: no decoy outlives the plugin (they are also non-persistent). */
    void shutdown() {
        for (Decoy d : decoys) retire(d);
        decoys.clear();
    }

    // ---- power scrolls -------------------------------------------------------------------------------

    /** A RIGHT CLICK ability was used: run the power scroll bound to that item (its numbers are the scroll def's extra). */
    @EventHandler
    public void onAbility(SkyEvents.AbilityUsed e) {
        Player p = e.player;
        if (p == null || e.item == null || e.ability == null || e.ability.trigger != com.skyclone.api.item.AbilityDef.Trigger.RIGHT_CLICK) return;
        String sid;
        try { sid = ItemMods.str(Sky.items().data(e.item), ItemMods.SCROLL_KEY); } catch (RuntimeException ex) { return; }
        if (sid == null) return;
        com.skyclone.api.item.ItemDef sd = Sky.items().def(sid);
        if (sd == null || sd.extra == null) return;
        Scroll s = Scroll.of(sd.extra);
        if (s == null) return;
        Temp tp = t(p);
        long now = System.currentTimeMillis();
        if (s.cooldown > 0) {
            Long ready = tp.scrollReady.get(sid);
            if (ready != null && ready > now) return;
            tp.scrollReady.put(sid, now + (long) (s.cooldown * 1000));
        }
        if (s.healMissingPct > 0) {
            double missing = Math.max(0, Sky.vitals().maxHealth(p) - Sky.vitals().health(p));
            if (missing > 0) Sky.vitals().heal(p, missing * s.healMissingPct / 100.0);
        }
        if (s.mana > 0) Sky.vitals().addMana(p, s.mana);
        if (s.stat != null && s.value != 0 && s.seconds > 0) {
            tp.scrollBuff.put(s.stat, new double[]{s.value, now + s.seconds * 1000});
            Sky.stats().invalidate(p);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) Sky.stats().invalidate(p); }, (long) (s.seconds * 20) + 1);
        }
    }

    /** What a power scroll does, read from its item def's extra (generated from the reference lore). Pure. */
    record Scroll(Stat stat, double value, double seconds, double cooldown, double healMissingPct, double mana) {
        static Scroll of(com.google.gson.JsonObject x) {
            if (x == null || !x.has("power_scroll")) return null;
            Stat st = x.has("stat") ? Stat.parse(x.get("stat").getAsString()) : null;
            return new Scroll(st, num(x, "value"), num(x, "seconds"), num(x, "cooldown"), num(x, "heal_missing_pct"), num(x, "mana"));
        }
        private static double num(com.google.gson.JsonObject x, String k) {
            try { return x.has(k) ? x.get(k).getAsDouble() : 0; } catch (RuntimeException e) { return 0; }
        }
    }

    // ---- admin view ----------------------------------------------------------------------------------

    List<String> describe(Player p) {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        Temp tp = temp.get(p.getUniqueId());
        int stacks = tp == null ? 0 : activeStacks(tp.mantid, now);
        out.add("Mantid stacks: " + stacks + (stacks > 0 ? " (next expires in " + (tp.mantid.get(0) - now) / 1000 + "s)" : ""));
        if (tp != null) for (Map.Entry<Stat, double[]> b : tp.scrollBuff.entrySet())
            if (b.getValue()[1] > now) out.add("Scroll buff: +" + Text.commas(b.getValue()[0]) + " " + b.getKey().display + " for " + (long) ((b.getValue()[1] - now) / 1000) + "s");
        if (tp != null) for (Map.Entry<String, Long> c : tp.scrollReady.entrySet())
            if (c.getValue() > now) out.add("Scroll cooldown: " + c.getKey() + " " + (c.getValue() - now) / 1000 + "s");
        Gear.Worn rod = held(p, "chomp");
        if (rod != null) out.add("Chomp rod: " + chompCount(Sky.items().data(rod.stack())) + " kinds slain, -"
                + Text.commas(chompPct(chompCount(Sky.items().data(rod.stack())), v(rod, 0, 1), v(rod, 1, 20))) + "% health");
        long mine = decoys.stream().filter(d -> d.owner.equals(p.getUniqueId())).count();
        out.add("Spirit decoys up: " + mine + " (all players: " + decoys.size() + ")");
        if (tp != null && tp.hyperUntil > now) out.add("Hyper: +" + tp.hyperSpeed + " Speed");
        List<String> worn = new ArrayList<>();
        for (Gear.Worn w : worn(p)) worn.add(w.row().id + (w.held() ? "(held)" : ""));
        out.add("Reforged gear in effect: " + worn);
        return out;
    }

    @EventHandler
    public void onGather(SkyEvents.Gather e) {
        Player p = e.player;
        if (p == null || e.kind == null) return;
        if (e.kind == LootSource.FARMING) {
            Gear.Worn bless = held(p, "blessed");
            if (bless != null) enchantedDrop(p, e.what, v(bless, 0, 0.1), LootSource.FARMING);
            Gear.Worn bount = held(p, "bountiful");
            if (bount != null && e.amount > 0) cropCoins.merge(p.getUniqueId(), v(bount, 0, 0.2) * e.amount, Double::sum);
        } else if (e.kind == LootSource.MINING) {
            Gear.Worn ref = held(p, "refined");
            if (ref != null) enchantedDrop(p, e.what, v(ref, 0, 0.1), LootSource.MINING);
            miningBonuses(p, e);
        }
    }

    /**
     * The mining-island reforge bonuses that need only "what was mined": extra Mining Fortune on some
     * blocks is paid as the extra drops that fortune would have given, and powder bonuses go through
     * {@code Features.Mining.addPowder}. Block facts (breaking power, powder) come from the mining
     * module's own block table, read by id.
     */
    private void miningBonuses(Player p, SkyEvents.Gather e) {
        if (e.what == null || e.amount <= 0) return;
        List<Gear.Worn> list = worn(p);
        if (list.isEmpty()) return;
        String what = e.what;
        double bonusFortune = 0, titaniumPowder = 0, royalPct = 0, stellarPct = 0, goblinPct = 0, wormPctPerHeat = 0;
        if (what.equals("STARFALL")) t(p).starSeenUntil = System.currentTimeMillis() + STAR_MINED_MS;
        for (Gear.Worn w : list) {
            switch (w.row().id) {
                case "ambered" -> { if (w.held()) goblinPct += v(w, 0, 10); }
                // values: [heat step, % per step]; equipment, so every worn piece adds its own
                case "blazing" -> { if (!w.held()) wormPctPerHeat += v(w, 1, 0.05) / Math.max(1, v(w, 0, 1)); }
                case "mithraic" -> { if (w.held() && (what.equals("MITHRIL_ORE") || what.equals("TITANIUM_ORE"))) bonusFortune += v(w, 0, 30); }
                case "scraped" -> {
                    if (!w.held()) break;
                    java.util.Set<Integer> bps = MiningBlocks.breakingPower(what);
                    if (bps.isEmpty()) break;
                    int toolBp = (int) Math.floor(Sky.stats().get(p, Stat.BREAKING_POWER));   // as the mining module reads it
                    if (bps.contains(toolBp)) bonusFortune += v(w, 0, 25);
                }
                case "dimensional" -> { if (!w.held() && what.equals("TITANIUM_ORE")) titaniumPowder += v(w, 0, 10); }
                case "royal" -> { if (!w.held()) royalPct += v(w, 0, 15); }
                case "stellar" -> { if (w.held() && nearStar(p, Sky.worlds().islandOf(p.getWorld()))) stellarPct += v(w, 0, 14); }
                default -> { }
            }
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // mining folded Ambered/Blazing into its own rolls (mining wave 4); while its dial mining.reforges-extra-roll is
        // true it leaves this module's share out and we roll it here, when false it pays all of it and we roll nothing.
        // Reading the same dial means the bonus is paid exactly once at either setting, in any order.
        miningDials();
        if ((goblinPct > 0 || wormPctPerHeat > 0) && extraRoll.get()) rareFinds(p, e, goblinPct, wormPctPerHeat, rnd);
        if (bonusFortune > 0 && Sky.items().exists(what)) {
            double mf = Math.max(0, Sky.stats().get(p, Stat.MINING_FORTUNE));
            double expected = e.amount * bonusFortune / (100.0 + mf);
            int extra = (int) Math.floor(expected) + (rnd.nextDouble() < expected - Math.floor(expected) ? 1 : 0);
            if (extra > 0) Sky.loot().give(p, what, extra, LootSource.MINING);
        }
        Features.Mining mining = Sky.service(Features.Mining.class);
        if (titaniumPowder > 0) {
            long n = (long) Math.floor(titaniumPowder) + (rnd.nextDouble() < titaniumPowder - Math.floor(titaniumPowder) ? 1 : 0);
            if (n > 0) mining.addPowder(p, "MITHRIL", n, "reforges: dimensional");
        }
        double powderPct = stellarPct + (MiningBlocks.fiesta() ? royalPct : 0);
        if (powderPct > 0) {
            double base = MiningBlocks.mithrilPowder(what);
            String island = Sky.worlds().islandOf(p.getWorld());
            if (base > 0 && island != null && MiningBlocks.POWDER_ISLANDS.contains(island)) {
                double amt = base * powderPct / 100.0;
                long n = (long) Math.floor(amt) + (rnd.nextDouble() < amt - Math.floor(amt) ? 1 : 0);
                if (n > 0) mining.addPowder(p, "MITHRIL", n, "reforges: royal/stellar");
            }
        }
    }

    /** The mining module's own spawn dials, looked up by key at first use (same defaults as its registration). */
    private static Dials.Dial<Double> goblinChance, diamondGoblinRatio, wormChance, scathaRatio;
    private static Dials.Dial<Boolean> extraRoll;

    private static void miningDials() {
        if (goblinChance != null) return;
        Dials d = Sky.dials();
        extraRoll = d.flag("mining.reforges-extra-roll", true,
                "The reforges module still rolls its own extra Ambered goblin / Blazing worm (Bonuses.rareFinds): mining takes that share "
                        + "off its own roll so the bonus is never paid twice. Set false once reforges deletes that roll");
        goblinChance = d.number("mining.goblin-chance", 0.0004, "Chance per Dwarven Mines block of a treasure goblin (TUNE, x Cave Luck)");
        diamondGoblinRatio = d.number("mining.diamond-goblin-ratio", 0.1, "Share of treasure goblins that are Diamond Goblins (TUNE)");
        wormChance = d.number("mining.worm-chance", 0.003, "Chance per Hard Stone/gem block of a Cave Worm in the crystal caverns (TUNE)");
        scathaRatio = d.number("mining.scatha-ratio", 0.2, "Share of worms that are Tunnel Wyrms (TUNE)");
    }

    /** Mob ids the mining module spawns for treasure goblins and worms (its kill rewards key off them). */
    static final String GOLDEN_GOBLIN = "mining_golden_goblin", DIAMOND_GOBLIN = "mining_diamond_goblin",
            WORM = "mining_worm", SCATHA = "mining_scatha";

    /**
     * Ambered (+X% treasure goblins) and Blazing (+Y% worms per Heat). The mining module rolls the base
     * chance on every block it breaks; this rolls only the EXTRA the reforge adds, under the same
     * conditions (goblins on any Dwarven Mines block, worms on Hard Stone or gemstone in the crystal
     * caverns) and from the same dials, and spawns the mining module's own mobs, so their kill rewards,
     * bestiary and commissions all apply. Like mining, a Mining Spread follow-up block never rolls a find:
     * only the block the player is actually looking at counts.
     */
    private void rareFinds(Player p, SkyEvents.Gather e, double goblinPct, double wormPctPerHeat, ThreadLocalRandom rnd) {
        if (e.location == null || e.location.getWorld() == null) return;
        String island = Sky.worlds().islandOf(e.location.getWorld());
        if (island == null) return;
        org.bukkit.block.Block b = e.location.getBlock();
        org.bukkit.block.Block target = p.getTargetBlockExact(6);
        if (target == null || !target.equals(b)) return;
        miningDials();
        if (goblinPct > 0 && "dwarven_mines".equals(island)) {
            if (rnd.nextDouble() < extraChance(goblinChance.get(), goblinPct)) {
                boolean diamond = rnd.nextDouble() < diamondGoblinRatio.get();
                Location at = groundNear(p.getLocation(), rnd);
                if (spawnFor(p, diamond ? DIAMOND_GOBLIN : GOLDEN_GOBLIN, at)) {
                    Msg.send(p, (diamond ? "&b&lDIAMOND GOBLIN! " : "&6&lGOLDEN GOBLIN! ") + "&7Your Ambered pickaxe shook a treasure goblin loose. Catch it!");
                    Msg.sound(p, Sound.ENTITY_PIGLIN_CELEBRATE, 1.4f);
                }
            }
        }
        if (wormPctPerHeat > 0 && "crystal_hollows".equals(island)) {
            String kind = MiningBlocks.kind(island, b.getType());
            if (!"HARD_STONE".equals(kind) && !"GEMSTONE".equals(kind)) return;
            int heat = 0;
            try { heat = Sky.service(Features.Mining.class).heat(p); } catch (Throwable ignored) { }
            if (heat <= 0) return;
            if (rnd.nextDouble() < extraChance(wormChance.get(), wormPctPerHeat * heat)) {
                boolean scatha = rnd.nextDouble() < scathaRatio.get();
                if (spawnFor(p, scatha ? SCATHA : WORM, p.getLocation())) {
                    Msg.send(p, scatha ? "&c&lTUNNEL WYRM! &7The heat drew something huge out of the rock..."
                            : "&e&lCAVE WORM! &7The heat drew a worm out of the rock!");
                    Msg.sound(p, Sound.ENTITY_SILVERFISH_AMBIENT, 0.5f);
                }
            }
        }
    }

    /** The extra per-block chance a "+pct% more often" bonus adds on top of a base chance. */
    static double extraChance(double base, double pct) {
        return Math.max(0, Math.min(1, base * pct / 100.0));
    }

    private static boolean spawnFor(Player p, String mobId, Location at) {
        if (Sky.mobs().def(mobId) == null) return false;
        try { return Sky.mobs().spawn(mobId, at, p) != null; } catch (Exception ex) { return false; }
    }

    /** A standable spot 2-5 blocks from the player (as the mining module places its goblins), else the player's feet. */
    private static Location groundNear(Location c, ThreadLocalRandom rnd) {
        org.bukkit.World w = c.getWorld();
        for (int tries = 0; tries < 16; tries++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            int d = 2 + rnd.nextInt(4);
            int x = c.getBlockX() + (int) Math.round(Math.cos(a) * d), z = c.getBlockZ() + (int) Math.round(Math.sin(a) * d);
            for (int dy = 3; dy >= -4; dy--) {
                int y = c.getBlockY() + dy;
                if (w.getBlockAt(x, y - 1, z).getType().isSolid() && w.getBlockAt(x, y, z).isPassable() && w.getBlockAt(x, y + 1, z).isPassable())
                    return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return c;
    }

    /** {@code pct}% chance to also drop the enchanted form of what was gathered. */
    private void enchantedDrop(Player p, String what, double pct, LootSource src) {
        if (what == null || ThreadLocalRandom.current().nextDouble() * 100 >= pct) return;
        String id = enchantedOf(what);
        if (id == null) return;
        Sky.loot().give(p, id, 1, src);
        Msg.send(p, "&9&lBLESSED DROP! " + Gear.itemName(id));
    }

    static String enchantedOf(String what) {
        String base = what.toUpperCase(java.util.Locale.ROOT);
        if (base.startsWith("DEEPSLATE_")) base = base.substring(10);
        if (base.endsWith("_ORE")) base = base.substring(0, base.length() - 4);
        if (base.endsWith("_ITEM")) base = base.substring(0, base.length() - 5);
        if (base.equals("LAPIS")) base = "LAPIS_LAZULI";
        if (base.equals("NETHER_QUARTZ")) base = "QUARTZ";
        if (base.equals("STONE")) base = "COBBLESTONE";
        for (String c : new String[]{"ENCHANTED_" + base, "ENCHANTED_" + what.toUpperCase(java.util.Locale.ROOT)}) {
            if (Sky.items().exists(c)) return c;
        }
        return null;
    }

    private void flushCropCoins() {
        if (cropCoins.isEmpty()) return;
        for (Map.Entry<UUID, Double> en : new ArrayList<>(cropCoins.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            double amt = Math.floor(en.getValue() * 10) / 10.0;
            if (p == null || !Sky.profiles().isReady(p)) { cropCoins.remove(en.getKey()); continue; }
            if (amt < 1) continue;
            cropCoins.put(en.getKey(), en.getValue() - amt);
            Sky.economy().deposit(p, amt, "reforges: bountiful crop coins");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSkillXp(SkyEvents.SkillXpGain e) {
        if (e.skill != SkillType.MINING || e.player == null) return;
        Gear.Worn mag = held(e.player, "magnetic");
        if (mag != null) e.amount *= 1 + v(mag, 0, 25) / 100.0;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        ItemStack helm = p.getInventory().getHelmet();
        if (Gear.empty(helm) || Gear.def(helm) == null) return;
        String rf = Sky.items().data(helm).reforge;
        if (!"ridiculous".equals(rf)) return;
        ReforgeData.Row row = ReforgesModule.DATA.rows.get("ridiculous");
        if (row == null) return;
        Rarity r = Sky.items().rarity(helm);
        Temp tp = t(p);
        long now = System.currentTimeMillis();
        if (now < tp.sneakReady) return;
        double needCc = ReforgeData.bonusValue(row, r, 5, 20);
        if (Sky.stats().get(p, Stat.CRIT_CHANCE) < needCc) return;
        tp.sneakReady = now + ReforgesModule.ridiculousCooldown.get() * 1000L;
        tp.critPenalty = ReforgeData.bonusValue(row, r, 0, 20);
        tp.critUntil = now + (long) (ReforgeData.bonusValue(row, r, 1, 20) * 1000);
        tp.defBonus = ReforgeData.bonusValue(row, r, 2, 30);
        tp.defUntil = now + (long) (ReforgeData.bonusValue(row, r, 3, 5) * 1000);
        Sky.vitals().addMana(p, ReforgeData.bonusValue(row, r, 4, 50));
        Sky.stats().invalidate(p);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 0.4, 0), 12, 0.25, 0.1, 0.25, 0.01);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_HORSE_BREATHE, 1f, 0.6f);
        long end = (long) (ReforgeData.bonusValue(row, r, 1, 20) * 20) + 1;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) Sky.stats().invalidate(p); }, (long) (ReforgeData.bonusValue(row, r, 3, 5) * 20) + 1);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) Sky.stats().invalidate(p); }, end);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Double c = cropCoins.remove(id);
        if (c != null && c >= 1 && Sky.profiles().isLoaded(e.getPlayer())) {
            Sky.economy().deposit(e.getPlayer(), Math.floor(c * 10) / 10.0, "reforges: bountiful crop coins");
        }
        temp.remove(id);
        wornCache.remove(id);
    }
}
