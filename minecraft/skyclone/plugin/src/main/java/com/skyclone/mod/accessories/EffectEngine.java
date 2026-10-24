package com.skyclone.mod.accessories;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;

/**
 * Makes accessory and equipment effects real: the conditional part of the stat provider, the damage
 * modifier, and the event listeners (kills, deaths, falls, targeting, xp, bits, drops) plus a 1 s timer
 * for regen / potions / income.
 */
final class EffectEngine implements Listener {

    private final Bags bags;
    private final Catalog cat;
    private final KeepBags keep;

    EffectEngine(Bags bags, Catalog cat, KeepBags keep) {
        this.bags = bags;
        this.cat = cat;
        this.keep = keep;
        mines = MineTargets.load(EffectEngine.class.getClassLoader());
    }

    // ---- condition context ---------------------------------------------------------------------

    /** Lazily computed facts about one player right now. */
    static final class LiveCtx implements Cond.Ctx {
        private final Player p;
        private String island;
        private boolean islandDone;
        private String zone;
        private boolean zoneDone;
        private SkyDate date;
        private Boolean dungeon;

        LiveCtx(Player p) { this.p = p; }

        private SkyDate date() {
            if (date == null) {
                try { date = Sky.calendar().now(); } catch (RuntimeException e) { date = new SkyDate(1, 1, 1, 12, 0); }
            }
            return date;
        }

        @Override public boolean night() { return date().isNight(); }
        @Override public boolean burning() { return p.getFireTicks() > 0; }
        @Override public boolean inDungeon() {
            if (dungeon == null) {
                try { dungeon = Sky.service(Features.Dungeons.class).inDungeon(p); } catch (RuntimeException e) { dungeon = false; }
            }
            return dungeon;
        }
        @Override public String island() {
            if (!islandDone) {
                islandDone = true;
                try { island = Sky.worlds().islandOf(p.getWorld()); } catch (RuntimeException e) { island = null; }
            }
            return island;
        }
        @Override public String zone() {
            if (!zoneDone) {
                zoneDone = true;
                try {
                    Layout.Zone z = Sky.worlds().zoneOf(p);
                    zone = z == null ? null : z.id();
                } catch (RuntimeException e) { zone = null; }
            }
            return zone;
        }
        @Override public int season() { return date().season(); }
        private String dungeonClass;
        private boolean classDone;
        @Override public String dungeonClass() {
            if (!classDone) {
                classDone = true;
                dungeonClass = selectedClass(p);
            }
            return dungeonClass;
        }
        private Boolean master;
        @Override public boolean masterMode() {
            if (master == null) {
                try { master = Sky.service(Features.Dungeons.class).masterMode(p); } catch (RuntimeException e) { master = false; }
            }
            return master;
        }
        @Override public boolean event(String eventId) { return eventActive(eventId); }
        @Override public String miningBlock() {
            return activeMineBlock(MINE_AT.get(p.getUniqueId()), System.currentTimeMillis(), mineWindowMs());
        }
    }

    // ---- wave 5: "while mining X" ------------------------------------------------------------------

    /** The block a player last started digging (mining module id) and when. */
    record MineHit(String block, long at) {}

    /** Player uuid -> the block they are digging; read by {@link LiveCtx#miningBlock}. */
    static final Map<UUID, MineHit> MINE_AT = new java.util.concurrent.ConcurrentHashMap<>();
    /** The mining module's block table (data/mining/blocks.json), loaded once. */
    static volatile MineTargets mines = MineTargets.EMPTY;

    static long mineWindowMs() {
        try { return Math.round(Math.max(0, AccDials.MINING_TARGET_SECONDS.get()) * 1000); } catch (RuntimeException e) { return 3000; }
    }

    /** The block id of {@code h} while it is fresh (within {@code windowMs}), else null. Pure (unit-tested). */
    static String activeMineBlock(MineHit h, long now, long windowMs) {
        if (h == null || h.block() == null) return null;
        long age = now - h.at();
        return age >= 0 && age <= windowMs ? h.block() : null;
    }

    /** Record that {@code p} is digging {@code b}; re-stat at once when the block kind changes. */
    private void noteDig(Player p, org.bukkit.block.Block b) {
        if (b == null || !Bags.ready(p) || !bags.snapshot(p).usesCond("mining")) return;
        String island;
        try { island = Sky.worlds().islandOf(p.getWorld()); } catch (RuntimeException e) { island = null; }
        String id = mines.resolve(island, b.getType().name());
        long now = System.currentTimeMillis();
        MineHit prev = MINE_AT.put(p.getUniqueId(), new MineHit(id, now));
        if (!java.util.Objects.equals(activeMineBlock(prev, now, mineWindowMs()), id)) Sky.stats().invalidate(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDig(org.bukkit.event.block.BlockDamageEvent e) {
        noteDig(e.getPlayer(), e.getBlock());
    }

    /** The mining module also starts digs from arm swings (a re-target the client folded into one action). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        if (e.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING || !Bags.ready(p)) return;
        if (!bags.snapshot(p).usesCond("mining")) return;
        noteDig(p, p.getTargetBlockExact(5));
    }

    // ---- wave 5: Organ Donor -------------------------------------------------------------------------

    private static org.bukkit.NamespacedKey corpseKey;

    /** The mining module's Frozen Corpses: armour stands named "Frozen &lt;kind&gt; Corpse", removed when looted. */
    static boolean isCorpseName(String plain) {
        return plain != null && plain.startsWith("Frozen ") && plain.endsWith(" Corpse");
    }

    private static boolean isCorpse(Entity e) {
        if (!(e instanceof org.bukkit.entity.ArmorStand st) || st.isDead()) return false;
        try {
            if (corpseKey == null) corpseKey = new org.bukkit.NamespacedKey(Sky.plugin(), "frozen_corpse");
            if (st.getPersistentDataContainer().has(corpseKey)) return true;   // if mining ever tags them
        } catch (RuntimeException ignored) { }
        net.kyori.adventure.text.Component name = st.customName();
        return name != null && isCorpseName(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name));
    }

    /** Once a second: a Ding for every unlooted Frozen Corpse that came within range since the last check. */
    private void corpseDing(Player p, Snapshot s, Bags.State st) {
        double r = 0;
        for (Fx f : s.fx) if (f.type.equals("corpse_ding")) r = Math.max(r, f.range <= 0 ? 20 : f.range);
        if (r <= 0) { st.corpsesNear.clear(); return; }
        java.util.Set<UUID> now = new java.util.HashSet<>();
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (e.getLocation().distanceSquared(p.getLocation()) > r * r || !isCorpse(e)) continue;
            now.add(e.getUniqueId());
        }
        int fresh = 0;
        for (UUID u : now) if (!st.corpsesNear.contains(u)) fresh++;
        st.corpsesNear.clear();
        st.corpsesNear.addAll(now);
        if (fresh > 0) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.6f);
            p.sendActionBar(Text.c("&b&lDING! &7" + (fresh == 1 ? "An unlooted Frozen Corpse is" : fresh + " unlooted Frozen Corpses are")
                    + " within &a" + (int) r + " &7blocks."));
        }
    }

    /** Is this calendar event running (the mythological ritual, the Harvest Fair...)? False when unknown. */
    static boolean eventActive(String eventId) {
        try { return eventId != null && Sky.calendar().active(eventId); } catch (RuntimeException e) { return false; }
    }

    /** The selected dungeon class (Adaptive Belt), null when the dungeons module does not know. */
    static String selectedClass(Player p) {
        try { return Sky.service(Features.Dungeons.class).selectedClass(p); } catch (RuntimeException e) { return null; }
    }

    // ---- set expansion -------------------------------------------------------------------------

    /** Pieces worn (armor + equipment) that belong to a set effect. */
    static int setPieces(Player p, Fx set, Map<String, ItemStack> equipment) {
        return armorPieces(p, set) + equipPieces(set, equipment);
    }

    static int armorPieces(Player p, Fx set) {
        int n = 0;
        for (ItemStack it : p.getInventory().getArmorContents()) n += member(set, Sky.items().idOf(it)) ? 1 : 0;
        return n;
    }

    static int equipPieces(Fx set, Map<String, ItemStack> equipment) {
        int n = 0;
        for (ItemStack it : equipment.values()) n += member(set, Sky.items().idOf(it)) ? 1 : 0;
        return n;
    }

    private static boolean member(Fx set, String id) {
        if (id == null) return false;
        if (!set.members.isEmpty()) return set.members.contains(id);
        return set.prefix != null && id.startsWith(set.prefix);
    }

    /** Visit every live leaf effect with its strength factor (1, or a set's piece fraction). */
    static void forEach(Player p, Snapshot s, BiConsumer<Fx, Double> visit) {
        for (Fx f : s.fx) {
            if (f.type.equals("set")) {
                int need = Math.max(1, f.need);
                double factor;
                if (f.perPiece) {
                    // armour pieces are paid by the armour module; we pay the equipment pieces, up to the tier cap
                    factor = AccMath.tieredEquipPieces(armorPieces(p, f), equipPieces(f, s.equipment), need);
                } else {
                    int have = setPieces(p, f, s.equipment);
                    factor = f.scale ? Math.min(1.0, have / (double) need) : (have >= need ? 1.0 : 0.0);
                }
                if (factor <= 0) continue;
                for (Fx sub : f.subs) visit.accept(sub, factor);
            } else {
                visit.accept(f, 1.0);
            }
        }
    }

    // ---- stats ---------------------------------------------------------------------------------

    void contributeStats(Player p, Bags.State st, Snapshot s, StatMap into) {
        LiveCtx ctx = new LiveCtx(p);
        forEach(p, s, (f, factor) -> stat(p, st, s, f, factor, ctx, into));
    }

    private void stat(Player p, Bags.State st, Snapshot s, Fx f, double factor, LiveCtx ctx, StatMap into) {
        switch (f.type) {
            case "stat_if" -> {
                if (f.stat != null && f.when.test(ctx)) into.add(f.stat, f.value * factor);
            }
            case "stat_pct" -> {
                if (f.stat != null && f.when.test(ctx)) into.addPercent(f.stat, f.pct * factor);
            }
            case "stat_zero" -> {
                if (f.stat != null) into.multiply(f.stat, 0);
            }
            case "per_skill" -> {
                SkillType t = SkillType.parse(f.skill == null ? "" : f.skill);
                if (t != null && f.stat != null) {
                    int lvl = Sky.service(SkillService.class).level(p, t);
                    into.add(f.stat, lvl * f.per * factor);
                }
            }
            case "gravity" -> {
                String island = ctx.island();
                if (island == null) return;
                Location spawn;
                try { spawn = Sky.worlds().anchor(island, "spawn"); } catch (RuntimeException e) { spawn = null; }
                if (spawn == null || spawn.getWorld() != p.getWorld()) return;
                double v = AccMath.gravity(spawn.distance(p.getLocation()), f.min, f.max, AccDials.GRAVITY_STEP.get());
                into.add(Stat.STRENGTH, v * factor);
                into.add(Stat.DEFENSE, v * factor);
            }
            case "magic8" -> {
                SkyDate d;
                try { d = Sky.calendar().now(); } catch (RuntimeException e) { return; }
                int roll = Math.floorMod(p.getUniqueId().hashCode() + d.year() * 4 + d.season(), 5);
                Stat[][] table = {
                        {Stat.FARMING_FORTUNE, Stat.FARMING_WISDOM}, {Stat.FORAGING_FORTUNE, Stat.FORAGING_WISDOM},
                        {Stat.MINING_FORTUNE, Stat.MINING_WISDOM}, {Stat.FISHING_SPEED, Stat.FISHING_WISDOM},
                        {Stat.STRENGTH, Stat.COMBAT_WISDOM}};
                into.add(table[roll][0], 25 * factor);   // research: +25 of the rolled stat, +1 wisdom
                into.add(table[roll][1], 1 * factor);
            }
            case "equip_mult" -> {
                // island (Ender set on The End) and/or a condition (wave 5: Mythic Ritual in the Hub, Harvest Fair)
                if (f.mult <= 1 || (f.island == null && f.when.always())) return;
                if (f.island != null && !f.island.equals(ctx.island())) return;
                if (!f.when.test(ctx)) return;
                for (ItemStack it : s.equipment.values()) {
                    if (!f.source.equals(Sky.items().idOf(it))) continue;
                    ItemStatSheet sheet = Sky.items().sheet(it);
                    for (Map.Entry<Stat, EnumMap<ItemStatSheet.Source, Double>> e : sheet.view().entrySet()) {
                        into.add(e.getKey(), sheet.total(e.getKey()) * (f.mult - 1) * factor);
                    }
                }
            }
            case "nearby_def" -> {
                double r = f.range <= 0 ? 10 : f.range;
                double cap = f.cap;
                // Bone Necklace (wave 5): a Tank in a dungeon reaches 30 blocks and +60 (research lore)
                if (f.tankRange > 0 && ctx.inDungeon() && "TANK".equalsIgnoreCase(ctx.dungeonClass())) {
                    r = f.tankRange;
                    if (f.tankCap > 0) cap = f.tankCap;
                }
                int count = 0;
                for (Entity e : p.getNearbyEntities(r, r, r)) {
                    if (!(e instanceof LivingEntity le) || e instanceof Player || !Sky.mobs().isSkyMob(le)) continue;
                    if ("burning".equals(f.mob) && le.getFireTicks() <= 0) continue;
                    count++;
                }
                into.add(Stat.DEFENSE, Math.min(cap, count * f.per) * factor);
            }
            case "bat_person" -> {
                int n = bats(p, st);
                if (n <= 0) return;
                into.add(Stat.DEFENSE, n * f.per * factor);
                into.add(Stat.STRENGTH, n * f.per * factor);
                into.add(Stat.INTELLIGENCE, n * f.per * factor);
            }
            case "keep_bag" -> {
                if (f.stat == null || f.kind == null || keep == null) return;
                int n = keep.bonusCount(p, f.kind);
                if (n > 0) into.add(f.stat, n * (f.per <= 0 ? 1 : f.per) * factor);
            }
            case "reaper" -> {
                long cut = System.currentTimeMillis() - (long) (f.window * 1000);
                int n = 0;
                for (long t : st.kills) if (t >= cut) n++;
                into.add(Stat.STRENGTH, n * f.str * factor);
            }
            case "weapon_buff" -> {
                if (f.item != null && f.item.equals(Sky.items().idOf(p.getInventory().getItemInMainHand()))) {
                    into.add(Stat.STRENGTH, f.str * factor);
                }
            }
            case "pet_copy" -> {
                // Manticore Claw: a share of what the "pets" stat provider gave on the last pass. The guard
                // stops the very first compute (no breakdown cached yet) from recursing into itself.
                if (IN_PET_COPY.get()) return;
                IN_PET_COPY.set(true);
                try {
                    for (Stat cs : PET_COPY_STATS) {
                        Double v = Sky.stats().breakdown(p, cs).get("pets");
                        if (v != null && v > 0) into.add(cs, v * f.pct / 100.0 * factor);
                    }
                } finally {
                    IN_PET_COPY.set(false);
                }
            }
            case "vs_stat" -> {
                if (f.stat != null && f.mob != null && st.vsActive && st.lastTargetTypes.contains(f.mob)) into.add(f.stat, f.value * factor);
            }
            case "counter" -> {
                int digits = Math.min(7, AccMath.digits(Bags.data(p).bloodCounter));
                into.add(Stat.STRENGTH, digits * f.per * factor);
            }
            default -> { }
        }
    }

    private static final ThreadLocal<Boolean> IN_PET_COPY = ThreadLocal.withInitial(() -> false);
    /** "Combat stats" for the Manticore Claw. */
    static final Stat[] PET_COPY_STATS = {Stat.HEALTH, Stat.DEFENSE, Stat.STRENGTH, Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE,
            Stat.ATTACK_SPEED, Stat.FEROCITY, Stat.INTELLIGENCE, Stat.TRUE_DEFENSE};

    // ---- wave 5: Bat Person, per-target Magic Find ------------------------------------------------

    /** Bats you have summoned right now: the Bat pet, a bat-swarm mask, and guided bats still flying. */
    static int bats(Player p, Bags.State st) {
        boolean pet;
        try { pet = "BAT".equalsIgnoreCase(Sky.service(Features.Pets.class).activeType(p)); } catch (RuntimeException e) { pet = false; }
        int mask = csvHas(AccDials.BAT_MASKS.get(), Sky.items().idOf(p.getInventory().getHelmet())) ? AccDials.MASK_BATS.get() : 0;
        long cut = System.currentTimeMillis() - Math.round(AccDials.GUIDED_BAT_SECONDS.get() * 1000);
        int guided = 0;
        for (long t : st.guidedBats) if (t >= cut) guided++;
        return AccMath.bats(pet, mask, guided);
    }

    static boolean csvHas(String csv, String id) {
        if (csv == null || id == null) return false;
        for (String s : csv.split(",")) if (s.trim().equalsIgnoreCase(id)) return true;
        return false;
    }

    /**
     * Core's per-target Magic Find hook ({@code LootService.addMagicFindModifier}): Golden Fleece +5 against
     * Mythological mobs, Shriveled Bracelet +10 against Arthropods. Only drops of that family see it.
     */
    double magicFindVs(Player p, MobDef def) {
        if (p == null || def == null || !Bags.ready(p)) return 0;
        Snapshot s = bags.snapshot(p);
        double sum = 0;
        Set<String> types = null;
        for (Fx f : s.fx) {
            if (!f.type.equals("mf_vs") || f.mob == null) continue;
            if (types == null) types = MobTypes.of(def);
            if (types.contains(f.mob)) sum += f.value;
        }
        return sum;
    }

    // ---- damage --------------------------------------------------------------------------------

    void modifyDamage(DamageContext ctx) {
        Player victim = ctx.playerVictim();
        // Soulweaver Gloves (wave 5): a mob a Haunted Skull touched deals less damage while the curse lasts
        if (victim != null && ctx.attacker != null && !(ctx.attacker instanceof Player)) {
            double[] curse = CURSED.get(ctx.attacker.getUniqueId());
            if (curse != null) {
                if (curse[0] < System.currentTimeMillis()) CURSED.remove(ctx.attacker.getUniqueId());
                else ctx.multiplicative *= Math.max(0, 1 - curse[1] / 100.0);
            }
        }
        if (victim != null && ctx.attacker != null && !(ctx.attacker instanceof Player) && Bags.ready(victim)) {
            Snapshot s = bags.snapshot(victim);
            if (!s.fx.isEmpty()) {
                Set<String> types = MobTypes.of(Sky.mobs().defOf(ctx.attacker));
                LiveCtx lc = new LiveCtx(victim);
                forEach(victim, s, (f, factor) -> {
                    if (!f.type.equals("dmg_taken") || f.mob == null) return;
                    if (!f.mob.equals("all") && !types.contains(f.mob)) return;
                    if (!f.when.test(lc)) return;
                    ctx.multiplicative *= Math.max(0, 1 - f.pct * factor / 100.0);
                });
                if (ctx.tags.contains("projectile") && throwsFireballs(ctx.attacker)) {
                    double chance = 0;
                    for (Fx f : s.fx) if (f.type.equals("reflect")) chance = Math.max(chance, f.chance);
                    if (chance > 0 && ThreadLocalRandom.current().nextDouble() * 100 < chance) reflect(victim, ctx);
                }
            }
        }
        Player att = ctx.playerAttacker();
        if (att != null && ctx.victim != null && !(ctx.victim instanceof Player) && Bags.ready(att)) {
            Snapshot s = bags.snapshot(att);
            if (s.fx.isEmpty()) return;
            Bags.State st = bags.state(att);
            Set<String> types = MobTypes.of(Sky.mobs().defOf(ctx.victim));
            String weaponId = Sky.items().idOf(ctx.weapon);
            ItemDef weapon = Sky.items().def(weaponId);
            boolean melee = ctx.kind == DamageContext.Kind.MELEE;
            boolean weaponHit = melee || ctx.kind == DamageContext.Kind.PROJECTILE;
            trackTarget(att, st, s, types);
            forEach(att, s, (f, factor) -> {
                switch (f.type) {
                    case "dmg_dealt" -> {
                        if (f.mob == null || (!f.mob.equals("all") && !types.contains(f.mob))) return;
                        if ("AXE".equals(f.weapon) && (weapon == null || weapon.category != ItemCategory.AXE)) return;
                        if (f.multiplicative) ctx.multiplicative *= 1 + f.pct * factor / 100.0;
                        else ctx.additive += f.pct * factor;
                    }
                    case "nth_hit" -> {
                        if (!melee || f.n <= 0) return;
                        UUID target = ctx.victim.getUniqueId();
                        if (!target.equals(st.hitTarget)) { st.hitTarget = target; st.hitCount = 0; }
                        st.hitCount++;
                        if (st.hitCount % f.n == 0) ctx.additive += f.pct * factor;
                    }
                    case "weapon_buff" -> {
                        if (weaponHit && f.item != null && f.item.equals(weaponId)) ctx.flatBonus += f.dmg * factor;
                    }
                    case "ability_boost" -> {
                        if (matchesAbility(ctx, f.match)) ctx.multiplicative *= 1 + f.pct * factor / 100.0;
                    }
                    case "health_ability" -> {
                        if (weaponId == null || !f.members.contains(weaponId)) return;
                        long window = Math.round(AccDials.HEALTH_ABILITY_WINDOW.get() * 1000);
                        if (AccMath.healthAbilityHit(ctx.tags.contains("ability"), weaponId, st.hpAbilityItem,
                                st.hpAbilityArmedAt, st.hpAbilityUsedAt, System.currentTimeMillis(), window)) {
                            ctx.multiplicative *= 1 + f.pct * factor / 100.0;
                        }
                    }
                    case "static" -> {
                        if (!weaponHit || ctx.tags.contains("static_charge")) return;   // the full Thunder set already discharged
                        long now = System.currentTimeMillis();
                        if (st.staticSince <= 0) { st.staticSince = now; return; }
                        int charges = (int) Math.min(Math.max(1, f.cap), (now - st.staticSince) / Math.max(1000L, (long) (f.window * 1000)));
                        if (charges <= 0) return;
                        st.staticSince = now;
                        ctx.additive += charges * f.pct * factor;
                        ctx.tags.add("static_charge");
                        att.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, ctx.victim.getLocation().add(0, 1, 0), 12 * charges, 0.3, 0.5, 0.3, 0.1);
                        att.playSound(att.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.4f, 1.8f);
                    }
                    case "tp_strike" -> {
                        if (!melee || st.lastTeleport <= 0) return;
                        if (System.currentTimeMillis() - st.lastTeleport > (long) (f.window * 1000)) return;
                        st.lastTeleport = 0;   // one hit spends it
                        ctx.additive += f.pct * factor;
                    }
                    case "wedding" -> {
                        if (ThreadLocalRandom.current().nextDouble() < f.chance) ctx.multiplicative *= 2;
                    }
                    case "fire_taken" -> {
                        // Scourge Cloak: the next weapon hit adds a share of the last fire damage you took
                        if (f.carry <= 0 || !weaponHit || st.lastFireDamage <= 0 || !AccDials.FIRE_DOWNSIDES.get()) return;
                        ctx.flatBonus += st.lastFireDamage * f.carry / 100.0;
                        st.lastFireDamage = 0;
                    }
                    case "fire_fist" -> {
                        if (!melee) return;
                        long now = System.currentTimeMillis();
                        if (now < st.fistReadyAt) return;
                        st.fistReadyAt = now + Math.round(Math.max(0, AccDials.FIST_COOLDOWN.get()) * 1000);
                        launchFists(att, ctx.victim, f);
                    }
                    case "haunt_skull" -> {
                        if (!melee) return;
                        st.lastMeleeTarget = ctx.victim.getUniqueId();
                        st.lastMeleeAt = System.currentTimeMillis();
                    }
                    case "crowd" -> {
                        if (att.getWorld().getPlayers().size() >= Math.max(1, f.players)) ctx.flatBonus += f.dmg * factor;
                    }
                    case "vamp_regen" -> {
                        if (!melee) return;
                        long now = System.currentTimeMillis();
                        if (now - st.lastVamp < (long) (f.cd * 1000)) return;
                        st.lastVamp = now;
                        double max = Sky.vitals().maxHealth(att);
                        double regen = Sky.stats().get(att, Stat.HEALTH_REGEN);
                        Sky.vitals().heal(att, (1.5 + max / 100.0) * regen / 100.0);   // one natural regen tick (core formula)
                    }
                    default -> { }
                }
            });
        }
    }

    // ---- Ancient Cloak: abilities that cost health ------------------------------------------------

    /**
     * Core cost modifier (runs before the handler, after the cooldown check): remember that a health-costing
     * ability of this item is about to run, so the MAGIC damage its handler deals right now is recognised.
     * Never changes the cost.
     */
    double armHealthAbility(com.skyclone.api.ability.AbilityService.Use use, double cost) {
        Player p = use.player();
        if (p == null || !Bags.ready(p)) return cost;
        String id = use.itemDef() != null ? use.itemDef().id : Sky.items().idOf(use.item());
        if (id == null || !costsHealth(bags.snapshot(p), id)) return cost;
        Bags.State st = bags.state(p);
        st.hpAbilityItem = id;
        st.hpAbilityArmedAt = System.currentTimeMillis();
        st.hpAbilityUsedAt = 0;
        return cost;
    }

    /** Core confirms the ability ran: hits it schedules a few ticks later (rose chains) still count. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAbilityUsed(SkyEvents.AbilityUsed e) {
        Player p = e.player;
        if (p == null || !Bags.ready(p)) return;
        Bags.State st = bags.state(p);
        String id = Sky.items().idOf(e.item);
        if (id == null || !id.equals(st.hpAbilityItem)) return;
        if (System.currentTimeMillis() - st.hpAbilityArmedAt > 1000) return;
        st.hpAbilityUsedAt = System.currentTimeMillis();
    }

    private static boolean costsHealth(Snapshot s, String itemId) {
        for (Fx f : s.fx) if (f.type.equals("health_ability") && f.members.contains(itemId)) return true;
        return false;
    }

    // ---- Catacombs Expert Ring / Professor's books ------------------------------------------------

    /** Summed percentage of every counted {@code dungeon_xp} effect for Catacombs XP ({@code classXp} false) or class XP. */
    double dungeonXpPct(Player p, boolean classXp) {
        if (p == null || !Bags.ready(p)) return 0;
        double pct = 0;
        String want = classXp ? "class" : "catacombs";
        for (Fx f : bags.snapshot(p).fx) if (f.type.equals("dungeon_xp") && want.equals(f.skill)) pct += f.pct;
        return pct;
    }

    /** Remember which families the player is fighting; re-stat when "against X" bonuses switch on. */
    private static void trackTarget(Player att, Bags.State st, Snapshot s, Set<String> types) {
        boolean wants = false;
        for (Fx f : s.fx) if (f.type.equals("vs_stat")) { wants = true; break; }
        if (!wants) return;
        st.lastTargetAt = System.currentTimeMillis();
        if (st.vsActive && st.lastTargetTypes.equals(types)) return;
        st.lastTargetTypes = types;
        st.vsActive = true;
        Sky.stats().invalidate(att);
    }

    /** True when the hit is a named ability whose name contains one of the '|'-separated words. */
    static boolean matchesAbility(DamageContext ctx, String match) {
        if (match == null || match.isBlank()) return false;
        for (String tag : ctx.tags) {
            if (!tag.startsWith("ability:")) continue;
            String name = tag.substring(8).toLowerCase(Locale.ROOT);
            for (String w : match.toLowerCase(Locale.ROOT).split("\\|")) {
                if (!w.isBlank() && name.contains(w.trim())) return true;
            }
        }
        return false;
    }

    /** Blazes and ghasts (and anything a mob module calls a blaze/ghast) throw the fireballs a belt can return. */
    static boolean throwsFireballs(LivingEntity mob) {
        if (mob == null || mob instanceof Player) return false;
        EntityType t = mob.getType();
        if (t == EntityType.BLAZE || t == EntityType.GHAST) return true;
        MobDef def = Sky.mobs().defOf(mob);
        if (def == null || def.id == null) return false;
        String id = def.id.toLowerCase(Locale.ROOT);
        return id.contains("blaze") || id.contains("ghast");
    }

    /** Blaze Belt: the fireball does not land, and the thrower takes the hit it meant for you. */
    private void reflect(Player p, DamageContext ctx) {
        ctx.cancelled = true;
        LivingEntity thrower = ctx.attacker;
        double dmg = ctx.base;
        Location from = p.getEyeLocation();
        p.getWorld().spawnParticle(Particle.FLAME, from, 12, 0.3, 0.3, 0.3, 0.02);
        p.getWorld().playSound(from, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1.4f);
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {   // never deal damage from inside a damage modifier
            if (!p.isOnline() || thrower.isDead() || !thrower.isValid()) return;
            Location to = thrower.getLocation().add(0, thrower.getHeight() / 2, 0);
            if (to.getWorld() == from.getWorld()) {
                Vector step = to.toVector().subtract(from.toVector());
                double len = step.length();
                if (len > 0 && len < 64) {
                    step.multiply(1.0 / len);
                    for (double d = 0; d < len; d += 0.75) {
                        from.getWorld().spawnParticle(Particle.FLAME, from.clone().add(step.clone().multiply(d)), 1, 0, 0, 0, 0);
                    }
                }
            }
            Sky.combat().rawDamage(thrower, Math.max(1, dmg), p, DamageContext.Kind.PROJECTILE);
        });
        p.sendActionBar(Text.c("&6Your belt sent the fireball back!"));
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFinalDamage(SkyEvents.Damage e) {
        Player p = e.ctx.playerVictim();
        if (p == null || !Bags.ready(p)) return;
        Snapshot s = bags.snapshot(p);
        double max = Sky.vitals().maxHealth(p);
        for (Fx f : s.fx) {
            if (f.type.equals("burst") && e.ctx.finalDamage >= max * 0.5 && f.mult > 0) {
                e.ctx.finalDamage *= f.mult;
            }
        }
    }

    /** Scourge Cloak: remember how much the fire hit that {@link #onEnvironment} just scaled really did. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFireDealt(SkyEvents.Damage e) {
        Player p = e.ctx.playerVictim();
        if (p == null || e.ctx.kind != DamageContext.Kind.ENVIRONMENT || !Bags.ready(p)) return;
        Bags.State st = bags.state(p);
        if (st.fireAt <= 0 || System.currentTimeMillis() - st.fireAt > 1500) return;
        st.fireAt = 0;
        if (e.ctx.finalDamage > 0) st.lastFireDamage = e.ctx.finalDamage;
    }

    // ---- kills, deaths -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || !Bags.ready(p)) return;
        if (e.mob != null && isRunic(e.mob)) countRunic(p);
        Snapshot s = bags.snapshot(p);
        if (s.fx.isEmpty()) return;
        Bags.State st = bags.state(p);
        Set<String> types = MobTypes.of(e.def);
        if (e.mob != null) countPieces(p, s, f -> f.item == null && unseenBy(f.mob, e.mob, e.def));
        boolean counted = false;
        for (Fx f : s.fx) {
            switch (f.type) {
                case "haunt_skull" -> {
                    // "...every 15 seconds or when you kill a mob with melee damage"
                    if (e.mob != null && e.mob.getUniqueId().equals(st.lastMeleeTarget)
                            && System.currentTimeMillis() - st.lastMeleeAt < 1000 && inDungeon(p)) startSkull(p, f);
                }
                case "scavenger" -> e.coins += Math.max(0, e.def == null ? 0 : e.def.level) * f.per;
                case "counter" -> {
                    if (!counted) { Bags.data(p).bloodCounter++; counted = true; }
                }
                case "reaper" -> {
                    st.kills.addLast(System.currentTimeMillis());
                    while (st.kills.size() > 200) st.kills.removeFirst();
                    Sky.stats().invalidate(p);
                }
                case "contagion" -> contagion(p, e, f);
                case "heal_on_kill" -> {
                    if (f.mob != null && !types.contains(f.mob)) continue;
                    long now = System.currentTimeMillis();
                    if (now - st.lastHealKill < (long) (f.cd * 1000)) continue;
                    st.lastHealKill = now;
                    Sky.vitals().heal(p, f.hp);
                }
                default -> { }
            }
        }
    }

    /** Vanquished piece bonus: bump the counter stored on each worn piece whose bonus this kill / block feeds. */
    private void countPieces(Player p, Snapshot s, java.util.function.Predicate<Fx> feeds) {
        for (Map.Entry<String, ItemStack> en : s.equipment.entrySet()) {
            ItemDef def = Sky.items().def(Sky.items().idOf(en.getValue()));
            Fx pb = ItemHooks.pieceBonus(cat, def);
            if (pb == null || !feeds.test(pb)) continue;
            ItemStack worn = bags.equipped(p, en.getKey());
            if (worn == null) continue;
            ItemStack next = worn.clone();
            long[] after = new long[1];
            Sky.items().update(next, d -> {
                if (d.extra == null) d.extra = new com.google.gson.JsonObject();
                after[0] = ItemHooks.pieceCount(d) + 1;
                d.extra.addProperty(ItemHooks.PIECE_KEY, after[0]);
            });
            int every = Math.max(1, AccDials.PIECE_EVERY.get());
            int before = AccMath.pieceBonus(after[0] - 1, every, AccDials.PIECE_CAP.get());
            int now = AccMath.pieceBonus(after[0], every, AccDials.PIECE_CAP.get());
            bags.setEquip(p, en.getKey(), next);   // persists; re-stats only matter when the bonus moves
            if (now > before) {
                Sky.stats().invalidate(p);
                Msg.send(p, "&6&lPIECE BONUS! &7Your " + def.name + " &7now grants &a+" + now + " " + pb.stat.display + "&7.");
            }
        }
    }

    /** Glowstone Gauntlet: each glowstone block mined feeds its piece bonus. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGather(SkyEvents.Gather e) {
        Player p = e.player;
        if (p == null || e.what == null || !Bags.ready(p)) return;
        Snapshot s = bags.snapshot(p);
        if (s.equipment.isEmpty()) return;
        countPieces(p, s, f -> f.item != null && f.item.equals(e.what));
    }

    /** Gauntlet of Contagion: the kill bursts for a share of the victim's max health on its neighbours. */
    private static void contagion(Player p, SkyEvents.MobKill e, Fx f) {
        if (e.mob == null || e.location == null || e.location.getWorld() == null) return;
        double max;
        try { max = Sky.mobs().maxHealth(e.mob); } catch (RuntimeException ex) { max = e.def == null ? 0 : e.def.health; }
        double dmg = max * f.pct / 100.0;
        if (!(dmg > 0)) return;
        double r = f.range <= 0 ? 2 : f.range;
        Location at = e.location.clone();
        UUID dead = e.mob.getUniqueId();
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {   // the next tick: a chain of kills is a chain of ticks
            if (!p.isOnline() || at.getWorld() == null) return;
            at.getWorld().spawnParticle(Particle.ITEM_SLIME, at.clone().add(0, 0.8, 0), 20, r / 2, 0.4, r / 2, 0.05);
            at.getWorld().playSound(at, Sound.ENTITY_SLIME_SQUISH, 0.8f, 0.7f);
            for (Entity n : at.getWorld().getNearbyEntities(at, r, r, r)) {
                if (!(n instanceof LivingEntity le) || n instanceof Player || n.getUniqueId().equals(dead) || le.isDead()) continue;
                if (!Sky.mobs().isSkyMob(le)) continue;
                Sky.combat().rawDamage(le, dmg, p, DamageContext.Kind.MAGIC);
            }
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(SkyEvents.SkyDeath e) {
        Player p = e.player;
        if (!Bags.ready(p) || e.coinsLost < AccDials.PIGGY_MIN.get()) return;
        // the best piggy bank in the bag first, then the inventory (research: "inventory or Accessory Bag")
        for (String id : new String[]{"PIGGY_BANK", "CRACKED_PIGGY_BANK"}) {
            ItemDef def = Sky.items().def(id);
            Fx piggy = null;
            for (Fx f : cat.effects(def)) if (f.type.equals("piggy")) piggy = f;
            if (piggy == null) continue;
            int slot = bags.slotOf(p, id);
            boolean inInv = slot < 0 && Sky.items().count(p, id) > 0;
            if (slot < 0 && !inInv) continue;
            double saved = e.coinsLost * Math.min(100, piggy.save) / 100.0;
            e.coinsLost -= saved;
            String next = piggy.next;
            if (slot >= 0) {
                bags.setBag(p, slot, next != null && Sky.items().exists(next) ? Sky.items().create(next) : null);
            } else {
                Sky.items().remove(p, id, 1);
                if (next != null && Sky.items().exists(next)) Sky.loot().give(p, next, 1, LootSource.OTHER);
            }
            Msg.send(p, "&d&lPIGGY BANK! &7Your " + def.name + " &7saved &6" + Text.commasInt(saved) + " coins &7and "
                    + (piggy.save >= 100 ? "cracked." : "broke."));
            return;
        }
    }

    // ---- world events --------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEnvironment(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !Bags.ready(p)) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        boolean fall = c == EntityDamageEvent.DamageCause.FALL;
        boolean lava = c == EntityDamageEvent.DamageCause.LAVA;
        boolean fire = c == EntityDamageEvent.DamageCause.FIRE || c == EntityDamageEvent.DamageCause.FIRE_TICK
                || c == EntityDamageEvent.DamageCause.HOT_FLOOR || c == EntityDamageEvent.DamageCause.CAMPFIRE;
        boolean poison = c == EntityDamageEvent.DamageCause.POISON;
        if (!fall && !lava && !fire && !poison) return;
        Snapshot s = bags.snapshot(p);
        if (s.fx.isEmpty()) return;
        double dmg = e.getDamage();
        for (Fx f : s.fx) {
            if (fall && f.type.equals("fall")) {
                dmg = Math.max(0, dmg - f.blocks) * (1 - f.pct / 100.0);
            } else if (poison && f.type.equals("poison_resist")) {
                dmg = dmg * Math.max(0, 1 - f.pct / 100.0);
            } else if (lava && f.type.equals("lava_immune")) {
                dmg = 0;
            } else if (fire && f.type.equals("fire_immune")) {
                String island = Sky.worlds().islandOf(p.getWorld());
                if ("crimson_isle".equals(island)) dmg = dmg * (1 - f.crimsonPct / 100.0);
                else dmg = 0;
            }
        }
        // Delirium Necklace / Scourge Cloak (wave 5): fire and lava hurt more, as the red lore line says
        if ((fire || lava) && dmg > 0 && AccDials.FIRE_DOWNSIDES.get()) {
            boolean carry = false;
            for (Fx f : s.fx) {
                if (!f.type.equals("fire_taken")) continue;
                dmg *= AccMath.fireTakenMult(f.pct, f.per, f.per > 0 ? strengthOn(s, f.source) : 0);
                carry |= f.carry > 0;
            }
            if (carry) bags.state(p).fireAt = System.currentTimeMillis();
        }
        if (dmg <= 0) e.setCancelled(true);
        else e.setDamage(dmg);
    }

    /** Strength on the worn equipment piece with this id (base + reforge + everything on its sheet). */
    private static double strengthOn(Snapshot s, String id) {
        for (ItemStack it : s.equipment.values()) {
            if (id != null && id.equals(Sky.items().idOf(it))) return Sky.items().sheet(it).total(Stat.STRENGTH);
        }
        return 0;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p) || !(e.getEntity() instanceof LivingEntity mob) || !Bags.ready(p)) return;
        MobDef def = Sky.mobs().defOf(mob);
        if (def == null || def.has("boss")) return;
        Snapshot s = bags.snapshot(p);
        int level = 0;
        for (Fx f : s.fx) {
            if (f.type.equals("intimidate")) level = Math.max(level, f.level);
            else if (f.type.equals("unseen") && unseenBy(f.mob, mob, def)) { e.setCancelled(true); return; }
        }
        if (level > 0 && def.level <= level) e.setCancelled(true);
    }

    /** "ghast" matches the GHAST entity type or any mob id containing "ghast". */
    static boolean unseenBy(String what, LivingEntity mob, MobDef def) {
        if (what == null || what.isEmpty()) return false;
        String w = what.toLowerCase(Locale.ROOT);
        if (mob.getType().name().toLowerCase(Locale.ROOT).equals(w)) return true;
        return def != null && def.id != null && def.id.toLowerCase(Locale.ROOT).contains(w);
    }

    /** Shadow Assassin Cloak: remember a short same-world teleport (abilities, pearls); the next melee hit spends it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (!Bags.ready(p) || e.getTo() == null || e.getFrom().getWorld() != e.getTo().getWorld()) return;
        if (e.getFrom().distanceSquared(e.getTo()) > 64 * 64) return;   // a warp, not a blink
        for (Fx f : bags.snapshot(p).fx) {
            if (f.type.equals("tp_strike")) { bags.state(p).lastTeleport = System.currentTimeMillis(); return; }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onXp(PlayerExpChangeEvent e) {
        Player p = e.getPlayer();
        if (!Bags.ready(p) || e.getAmount() <= 0) return;
        double pct = 0;
        for (Fx f : bags.snapshot(p).fx) if (f.type.equals("xp_boost")) pct = Math.max(pct, f.pct);
        if (pct > 0) e.setAmount((int) Math.round(e.getAmount() * (1 + pct / 100.0)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBits(SkyEvents.CurrencyChange e) {
        if (!"bits".equals(e.currency) || e.delta <= 0 || e.player == null || !Bags.ready(e.player)) return;
        if (e.reason != null && e.reason.startsWith("accessories:")) return;   // never double a double
        for (Fx f : bags.snapshot(e.player).fx) {
            if (f.type.equals("bits_double") && ThreadLocalRandom.current().nextDouble() * 100 < f.chance) {
                long extra = Math.round(e.delta);
                Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                    if (!e.player.isOnline()) return;
                    Sky.economy().addBits(e.player, extra, "accessories: bits talisman double");
                    Msg.send(e.player, "&bYour Bits Talisman doubled that! &3+" + extra + " Bits");
                });
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGain(SkyEvents.ItemGain e) {
        Player p = e.player;
        if (!Bags.ready(p)) return;
        Snapshot s = bags.snapshot(p);
        // personal deletor: destroy chosen items before they land (collections still count them)
        if (s.deletorSlots > 0 && AccDials.COMPACTOR_ON.get()) {
            List<String> del = Bags.data(p).deletor;
            int limit = Math.min(del.size(), s.deletorSlots);
            for (int i = 0; i < limit; i++) {
                if (del.get(i).equals(e.itemId)) { e.setCancelled(true); return; }
            }
        }
        if (e.source == LootSource.MOB_DROP && e.amount > 0) {
            double pct = 0;
            for (Fx f : s.fx) if (f.type.equals("drop_boost") && e.itemId.equals(f.item)) pct = Math.max(pct, f.pct);
            if (pct > 0) {
                double want = e.amount * pct / 100.0;
                int extra = (int) want + (ThreadLocalRandom.current().nextDouble() < want - (int) want ? 1 : 0);
                if (extra > 0) {
                    String id = e.itemId;
                    // source OTHER on the next tick: never re-enters this branch, so it can never boost itself
                    Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (p.isOnline()) Sky.loot().give(p, id, extra, LootSource.OTHER); });
                }
            }
        }
        if ((e.source == LootSource.MOB_DROP || e.source == LootSource.SLAYER) && e.stack != null) {
            for (Fx f : s.fx) {
                if (!f.type.equals("auto_recomb")) continue;
                ItemDef def = Sky.items().def(e.itemId);
                if (def == null || !def.isUnique() || def.has("no_reforge")) return;
                if (Sky.items().data(e.stack).recombobulated) return;
                if (ThreadLocalRandom.current().nextDouble() * 100 < f.chance) {
                    Sky.items().update(e.stack, d -> d.recombobulated = true);
                    Msg.send(p, "&d&lAUTO-RECOMBOBULATED! &7Your drop arrived one rarity higher.");
                }
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p) || !Bags.ready(p) || !AccDials.COMPACTOR_ON.get()) return;
        Snapshot s = bags.snapshot(p);
        if (s.deletorSlots <= 0) return;
        ItemStack it = e.getItem().getItemStack();
        String id = Sky.items().idOf(it);
        if (id == null) id = Sky.items().vanillaId(it.getType());
        List<String> del = Bags.data(p).deletor;
        int limit = Math.min(del.size(), s.deletorSlots);
        for (int i = 0; i < limit; i++) {
            if (del.get(i).equals(id)) {
                e.setCancelled(true);
                e.getItem().remove();
                return;
            }
        }
    }

    // ---- wave 5: Runebook, Flaming Fist, Haunted Skull, guided bats -------------------------------

    private static org.bukkit.NamespacedKey runicKey;

    /** The skills module marks Runic mobs with the PDC byte {@code skyclone:runic} (Runes.isRunic). */
    static boolean isRunic(LivingEntity mob) {
        try {
            if (runicKey == null) runicKey = new org.bukkit.NamespacedKey(Sky.plugin(), "runic");
            return mob.getPersistentDataContainer().has(runicKey, org.bukkit.persistence.PersistentDataType.BYTE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Runebook: +1 on the counter of the Runebook in your bag (else the first one in your inventory). */
    private void countRunic(Player p) {
        String id = "RUNEBOOK";
        int slot = bags.slotOf(p, id);
        ItemStack book = slot >= 0 ? bags.bagItem(p, slot) : null;
        int invSlot = -1;
        if (book == null) {
            ItemStack[] inv = p.getInventory().getStorageContents();
            for (int i = 0; i < inv.length; i++) if (id.equals(Sky.items().idOf(inv[i]))) { invSlot = i; book = inv[i]; break; }
        }
        if (book == null) return;
        ItemDef def = Sky.items().def(id);
        boolean counts = false;
        for (Fx f : cat.effects(def)) counts |= f.type.equals("runic_counter");
        if (!counts) return;
        ItemStack next = book.clone();
        long[] after = new long[1];
        Sky.items().update(next, d -> {
            if (d.extra == null) d.extra = new com.google.gson.JsonObject();
            after[0] = (d.extra.has(Bags.RUNIC_KEY) ? d.extra.get(Bags.RUNIC_KEY).getAsLong() : 0) + 1;
            d.extra.addProperty(Bags.RUNIC_KEY, after[0]);
        });
        if (slot >= 0) bags.setBag(p, slot, next);
        else p.getInventory().setItem(invSlot, next);
        if (AccMath.digits(after[0]) > AccMath.digits(after[0] - 1) && AccMath.digits(after[0]) <= 4) {
            bags.invalidate(p);
            Msg.send(p, "&5&lRUNEBOOK! &7It now counts &d" + Text.commasInt(after[0]) + " &7Runic kills and grows a rarity.");
        }
    }

    /** One burn from a Flaming Fist volley: pulses of fire damage, refreshed (never stacked) by the next volley. */
    private static final class Burn {
        final UUID owner;
        double perPulse;
        int left;
        long nextAt, every;
        Burn(UUID owner) { this.owner = owner; }
    }

    /** Victim uuid -> burns by owner uuid. */
    private final Map<UUID, Map<UUID, Burn>> burns = new java.util.HashMap<>();

    private void launchFists(Player p, LivingEntity target, Fx f) {
        int fists = Math.max(1, f.n);
        int pulses = Math.max(1, AccDials.FIST_PULSES.get());
        double seconds = f.window <= 0 ? 3 : f.window;
        Burn b = burns.computeIfAbsent(target.getUniqueId(), k -> new java.util.HashMap<>())
                .computeIfAbsent(p.getUniqueId(), Burn::new);
        b.perPulse = f.dmg / pulses;
        b.left = pulses;
        b.every = Math.max(100, Math.round(seconds * 1000 / pulses));
        b.nextAt = System.currentTimeMillis() + b.every;
        // the three fists: short flame arcs from your shoulders to the target
        Location from = p.getEyeLocation().subtract(0, 0.3, 0);
        Location to = target.getLocation().add(0, target.getHeight() / 2, 0);
        if (from.getWorld() != to.getWorld()) return;
        Vector side = from.getDirection().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1e-6) side = new Vector(1, 0, 0);
        side.normalize();
        for (int i = 0; i < fists; i++) {
            double off = (i - (fists - 1) / 2.0) * 0.6;
            Location start = from.clone().add(side.clone().multiply(off));
            Vector step = to.toVector().subtract(start.toVector());
            double len = Math.min(16, step.length());
            if (len <= 0) continue;
            step.normalize();
            for (double d = 0; d < len; d += 0.8) {
                p.getWorld().spawnParticle(Particle.FLAME, start.clone().add(step.clone().multiply(d)), 1, 0.02, 0.02, 0.02, 0);
            }
        }
        p.getWorld().playSound(to, Sound.ITEM_FIRECHARGE_USE, 0.6f, 1.3f);
    }

    /** A Haunted Skull circling one player: started at, and the mobs it already touched. */
    private static final class Skull {
        final UUID player;
        final long start;
        final double radius, stunSeconds, weakPct;
        final Set<UUID> hit = new java.util.HashSet<>();
        Skull(UUID player, long start, double radius, double stunSeconds, double weakPct) {
            this.player = player; this.start = start; this.radius = radius; this.stunSeconds = stunSeconds; this.weakPct = weakPct;
        }
    }

    private final List<Skull> skulls = new java.util.ArrayList<>();
    /** Mobs a skull touched: uuid -> {until (wall ms), percent less damage dealt}. */
    static final Map<UUID, double[]> CURSED = new java.util.concurrent.ConcurrentHashMap<>();

    private void startSkull(Player p, Fx f) {
        for (Skull s : skulls) if (s.player.equals(p.getUniqueId())) return;   // one at a time
        skulls.add(new Skull(p.getUniqueId(), System.currentTimeMillis(), f.range <= 0 ? 2.5 : f.range,
                f.window <= 0 ? 2 : f.window, f.pct));
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SKELETON_AMBIENT, 0.6f, 1.6f);
    }

    private static boolean inDungeon(Player p) {
        try { return Sky.service(Features.Dungeons.class).inDungeon(p); } catch (RuntimeException e) { return false; }
    }

    /** Guided bats: a bat-summoning ability (Spirit Sceptre) counts one bat for a few seconds (Bat Person). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBatAbility(SkyEvents.AbilityUsed e) {
        Player p = e.player;
        if (p == null || !Bags.ready(p) || !csvHas(AccDials.BAT_ABILITY_ITEMS.get(), Sky.items().idOf(e.item))) return;
        if (!bags.snapshot(p).has("bat_person")) return;
        Bags.State st = bags.state(p);
        st.guidedBats.addLast(System.currentTimeMillis());
        while (st.guidedBats.size() > 20) st.guidedBats.removeFirst();
        Sky.stats().invalidate(p);
    }

    /** Every 4 ticks: Flaming Fist burns and Haunted Skulls. */
    void fastTick() {
        long now = System.currentTimeMillis();
        if (!burns.isEmpty()) {
            for (Iterator<Map.Entry<UUID, Map<UUID, Burn>>> it = burns.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Map<UUID, Burn>> en = it.next();
                Entity ent = Bukkit.getEntity(en.getKey());
                if (!(ent instanceof LivingEntity victim) || victim.isDead() || !victim.isValid()) { it.remove(); continue; }
                for (Iterator<Burn> bi = en.getValue().values().iterator(); bi.hasNext(); ) {
                    Burn b = bi.next();
                    Player owner = Bukkit.getPlayer(b.owner);
                    if (owner == null || b.left <= 0) { bi.remove(); continue; }
                    if (now < b.nextAt) continue;
                    b.nextAt = now + b.every;
                    b.left--;
                    victim.setFireTicks(Math.max(victim.getFireTicks(), 20));
                    victim.getWorld().spawnParticle(Particle.FLAME, victim.getLocation().add(0, victim.getHeight() / 2, 0), 6, 0.3, 0.4, 0.3, 0.01);
                    Sky.combat().rawDamage(victim, Math.max(1, b.perPulse), owner, DamageContext.Kind.MAGIC);
                }
                if (en.getValue().isEmpty()) it.remove();
            }
        }
        if (skulls.isEmpty()) return;
        double period = Math.max(0.5, AccDials.SKULL_ORBIT_SECONDS.get());
        for (Iterator<Skull> it = skulls.iterator(); it.hasNext(); ) {
            Skull s = it.next();
            Player p = Bukkit.getPlayer(s.player);
            double t = (now - s.start) / 1000.0;
            if (p == null || p.isDead() || t > period || !inDungeon(p)) { it.remove(); continue; }
            double[] xz = AccMath.orbit(t, period, s.radius);
            Location at = p.getLocation().add(xz[0], 1.2, xz[1]);
            p.getWorld().spawnParticle(Particle.SOUL, at, 3, 0.1, 0.1, 0.1, 0.01);
            p.getWorld().spawnParticle(Particle.SMOKE, at, 2, 0.05, 0.05, 0.05, 0.0);
            for (Entity n : p.getWorld().getNearbyEntities(at, 1.2, 1.2, 1.2)) {
                if (!(n instanceof LivingEntity le) || n instanceof Player || le.isDead() || !Sky.mobs().isSkyMob(le)) continue;
                if (!s.hit.add(le.getUniqueId())) continue;
                int ticks = (int) Math.round(s.stunSeconds * 20);
                le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 9, false, false, false));
                le.setVelocity(new Vector(0, 0, 0));
                if (le instanceof org.bukkit.entity.Mob mob) mob.setTarget(null);
                CURSED.put(le.getUniqueId(), new double[]{now + s.stunSeconds * 1000, s.weakPct});
                le.getWorld().spawnParticle(Particle.SCULK_SOUL, le.getLocation().add(0, le.getHeight(), 0), 6, 0.2, 0.2, 0.2, 0.01);
            }
        }
        CURSED.values().removeIf(c -> c[0] < now);
    }

    // ---- 1 s timer -----------------------------------------------------------------------------

    void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Bags.ready(p)) continue;
            try { tick(p); } catch (RuntimeException ex) {
                Sky.log().warning("[accessories] tick failed for " + p.getName() + ": " + ex);
            }
        }
    }

    private void tick(Player p) {
        Snapshot s = bags.snapshot(p);
        Bags.State st = bags.state(p);
        double regen = 0, perMinute = 0;
        for (Fx f : s.fx) {
            switch (f.type) {
                case "regen" -> regen += f.hp;
                case "coins_minute" -> perMinute += f.coins;
                case "potion" -> potion(p, f);
                default -> { }
            }
        }
        if (regen > 0 && Sky.vitals().health(p) < Sky.vitals().maxHealth(p)) Sky.vitals().heal(p, regen);
        if (perMinute > 0) {
            st.minuteTimer++;
            if (st.minuteTimer >= 60) {
                st.minuteTimer = 0;
                Sky.economy().deposit(p, perMinute, "accessories: coins per minute");
            }
        } else {
            st.minuteTimer = 0;
        }
        // Adaptive Belt: the stats follow the selected dungeon class, which another module changes
        boolean classStats = false;
        for (Fx f : s.fx) if (f.when.uses("class")) { classStats = true; break; }
        if (classStats) {
            String cls = selectedClass(p);
            if (!java.util.Objects.equals(cls, st.dungeonClass)) {
                st.dungeonClass = cls;
                Sky.stats().invalidate(p);
            }
        }
        // wave 5: Bat Person follows the bats you have (pet swaps, masks, guided bats running out)
        if (s.has("bat_person")) {
            int n = bats(p, st);
            if (n != st.lastBats) { st.lastBats = n; Sky.stats().invalidate(p); }
        }
        // wave 5: Soulweaver Gloves send a Haunted Skull round every cd seconds while you are in a dungeon
        for (Fx f : s.fx) {
            if (!f.type.equals("haunt_skull")) continue;
            long now = System.currentTimeMillis();
            if (now >= st.skullNextAt && inDungeon(p)) {
                st.skullNextAt = now + Math.round(Math.max(1, f.cd) * 1000);
                startSkull(p, f);
            }
            break;
        }
        // wave 5: Organ Donor dings for corpses that came into range; a lapsed "while mining X" re-stats at once
        corpseDing(p, s, st);
        MineHit hit = MINE_AT.get(p.getUniqueId());
        if (hit != null && activeMineBlock(hit, System.currentTimeMillis(), mineWindowMs()) == null) {
            MINE_AT.remove(p.getUniqueId());
            if (hit.block() != null) Sky.stats().invalidate(p);
        }
        if (st.vsActive && System.currentTimeMillis() - st.lastTargetAt > 5000) {
            st.vsActive = false;
            Sky.stats().invalidate(p);
        }
        st.coinFindTimer++;
        if (st.coinFindTimer >= Math.max(5, AccDials.COIN_FIND_SECONDS.get())) {
            st.coinFindTimer = 0;
            coinFind(p, s);
        }
        // trim old kill stamps so the reaper bonus decays
        long cut = System.currentTimeMillis() - 10_000;
        Iterator<Long> it = st.kills.iterator();
        boolean changed = false;
        while (it.hasNext()) { if (it.next() < cut) { it.remove(); changed = true; } }
        if (changed) Sky.stats().invalidate(p);
    }

    /** Magnetic Talisman, every 4 ticks: items you could pick up, within mult x the vanilla reach, drift to you. */
    void magnetTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Bags.ready(p) || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
            double mult = 0;
            for (Fx f : bags.snapshot(p).fx) if (f.type.equals("magnet")) mult = Math.max(mult, f.mult);
            if (mult <= 1) continue;
            double r = 1.0 * mult;   // vanilla pickup reaches about 1 block past the hitbox
            Location chest = p.getLocation().add(0, 0.6, 0);
            for (Entity n : p.getNearbyEntities(r, r * 0.75, r)) {
                if (!(n instanceof Item item) || item.isDead() || item.getPickupDelay() > 0) continue;
                UUID owner = item.getOwner();
                if (owner != null && !owner.equals(p.getUniqueId())) continue;
                Vector v = chest.toVector().subtract(item.getLocation().toVector());
                double len = v.length();
                if (len < 0.8) continue;
                item.setVelocity(v.multiply(Math.min(0.5, 0.35 + 0.05 * len) / len));
            }
        }
    }

    private void coinFind(Player p, Snapshot s) {
        String island = Sky.worlds().islandOf(p.getWorld());
        if (island == null || island.equals("private_island") || island.equals("garden")) return;   // public islands only
        for (Fx f : s.fx) {
            if (!f.type.equals("coin_find")) continue;
            if (ThreadLocalRandom.current().nextDouble() >= f.chance) continue;
            int lo = (int) Math.max(1, f.min), hi = (int) Math.max(lo, f.max);
            int coins = ThreadLocalRandom.current().nextInt(lo, hi + 1);
            Sky.economy().deposit(p, coins, "accessories: coins found (" + f.source + ")");
            Msg.send(p, "&6+" + coins + " coins &7found lying on the ground!");
        }
    }

    private static void potion(Player p, Fx f) {
        PotionEffectType type = switch (f.effect == null ? "" : f.effect) {
            case "haste" -> PotionEffectType.HASTE;
            case "night_vision" -> PotionEffectType.NIGHT_VISION;
            case "jump_boost" -> PotionEffectType.JUMP_BOOST;
            default -> null;
        };
        if (type == null) return;
        int dur = type == PotionEffectType.NIGHT_VISION ? 400 : 60;   // night vision flickers below 10 s
        PotionEffect cur = p.getPotionEffect(type);
        if (cur != null && cur.getAmplifier() > f.amp) return;
        if (cur != null && cur.getAmplifier() == f.amp && cur.getDuration() > dur - 25) return;
        p.addPotionEffect(new PotionEffect(type, dur, Math.max(0, f.amp), true, false, true));
    }
}
