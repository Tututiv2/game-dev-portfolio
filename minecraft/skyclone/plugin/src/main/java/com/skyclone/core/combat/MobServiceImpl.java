package com.skyclone.core.combat;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.MobService;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Core MobService. Custom health lives in {@link LiveMob} (PDC holds a backup); the vanilla
 * health bar is a fixed gauge that never moves, because every point of vanilla damage to a sky
 * mob is zeroed or cancelled by {@link CombatListener}.
 */
final class MobServiceImpl implements MobService {

    private final Gson gson = new GsonBuilder().setStrictness(Strictness.LENIENT).create();

    private final Map<String, MobDef> defs = new LinkedHashMap<>();
    private final Map<String, EntityType> types = new HashMap<>();
    private final Map<String, MobBehaviour> behaviours = new HashMap<>();
    private final Map<UUID, LiveMob> live = new HashMap<>();
    private final Map<String, Set<LivingEntity>> byId = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final Map<UUID, Long> notYoursMsg = new HashMap<>();

    final MobSpawner spawner = new MobSpawner(this);
    private long tick;

    // ---- registry ---------------------------------------------------------------------------

    @Override
    public void register(MobDef def) {
        if (def == null || def.id == null || def.id.isBlank()) {
            Sky.log().warning("[mobs] ignored a MobDef with no id");
            return;
        }
        def.id = def.id.trim();
        normalise(def);
        if (defs.containsKey(def.id)) {
            Sky.log().warning("[mobs] duplicate mob id '" + def.id + "' — keeping the first registration");
            return;
        }
        EntityType type = parseType(def.entity);
        if (type == null) {
            Sky.log().warning("[mobs] mob '" + def.id + "' has entity '" + def.entity + "' which is not a living entity type; using ZOMBIE");
            type = EntityType.ZOMBIE;
        }
        types.put(def.id, type);
        defs.put(def.id, def);
    }

    private static void normalise(MobDef d) {
        if (d.name == null || d.name.isBlank()) d.name = Text.titleCase(d.id);
        if (d.drops == null) d.drops = new ArrayList<>();
        d.drops.removeIf(x -> x == null || x.item == null || x.item.isBlank());
        if (d.equipment == null) d.equipment = new HashMap<>();
        if (d.behaviours == null) d.behaviours = new ArrayList<>();
        Set<String> flags = new HashSet<>();
        if (d.flags != null) for (String f : d.flags) if (f != null && !f.isBlank()) flags.add(f.trim().toLowerCase(Locale.ROOT));
        d.flags = flags;
        if (d.extra == null) d.extra = new JsonObject();
        if (d.health <= 0) d.health = 1;
        if (d.level < 0) d.level = 0;
        if (d.speed < 0) d.speed = 0;
    }

    static EntityType parseType(String s) {
        if (s == null) return null;
        try {
            EntityType t = EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT));
            Class<? extends Entity> c = t.getEntityClass();
            if (c == null || !LivingEntity.class.isAssignableFrom(c) || Player.class.isAssignableFrom(c)) return null;
            return t;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public int loadJson(String module, String file) {
        JsonArray arr = readArray(module, file);
        if (arr == null) return 0;
        int n = 0;
        for (JsonElement el : arr) {
            try {
                MobDef d = gson.fromJson(el, MobDef.class);
                int before = defs.size();
                register(d);
                if (defs.size() > before) n++;
            } catch (RuntimeException ex) {
                Sky.log().warning("[mobs] " + module + "/" + file + ": bad entry " + abbreviate(el) + " — " + ex.getMessage());
            }
        }
        Sky.log().info("[mobs] loaded " + n + " mobs from " + module + "/" + file);
        return n;
    }

    JsonArray readArray(String module, String file) {
        String path = "data/" + module + "/" + file;
        try (InputStream in = Sky.plugin().getResource(path)) {
            if (in == null) {
                Sky.log().warning("[mobs] missing resource " + path);
                return null;
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonReader jr = new JsonReader(r);
                jr.setStrictness(Strictness.LENIENT);
                JsonElement root = JsonParser.parseReader(jr);
                if (!root.isJsonArray()) {
                    Sky.log().warning("[mobs] " + path + " is not a JSON array");
                    return null;
                }
                return root.getAsJsonArray();
            }
        } catch (Exception ex) {
            Sky.log().log(Level.WARNING, "[mobs] could not read " + path, ex);
            return null;
        }
    }

    Gson gson() { return gson; }

    private static String abbreviate(JsonElement el) {
        String s = String.valueOf(el);
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    @Override public MobDef def(String id) { return id == null ? null : defs.get(id); }

    @Override public Collection<MobDef> all() { return Collections.unmodifiableCollection(defs.values()); }

    @Override
    public void registerBehaviour(String id, MobBehaviour behaviour) {
        if (id == null || behaviour == null) return;
        if (behaviours.put(id, behaviour) != null) Sky.log().warning("[mobs] behaviour '" + id + "' registered twice; the later one wins");
    }

    boolean hasBehaviour(String id) { return behaviours.containsKey(id); }

    @Override public void addSpawnRule(SpawnRule rule) { spawner.add(rule, "code"); }

    @Override public int loadSpawnRules(String module, String file) { return spawner.load(module, file); }

    // ---- spawning ---------------------------------------------------------------------------

    @Override
    public LivingEntity spawn(String mobId, Location at, Player owner) {
        MobDef def = def(mobId);
        if (def == null) throw new IllegalArgumentException("Unknown sky mob id: " + mobId);
        if (at == null || at.getWorld() == null) throw new IllegalArgumentException("spawn location has no world");
        return spawnInternal(def, at, owner == null ? null : owner.getUniqueId(), null);
    }

    LivingEntity spawnInternal(MobDef def, Location at, UUID owner, String ruleId) {
        EntityType type = types.getOrDefault(def.id, EntityType.ZOMBIE);
        @SuppressWarnings("unchecked")
        Class<? extends LivingEntity> cls = (Class<? extends LivingEntity>) type.getEntityClass();
        World w = at.getWorld();
        double gauge = Math.max(1, Math.min(1024, CombatDials.GAUGE_HEALTH.get()));
        LivingEntity le;
        try {
            le = w.spawn(at, cls, (Consumer<LivingEntity>) e -> configure(e, def, owner, gauge), CreatureSpawnEvent.SpawnReason.CUSTOM);
        } catch (RuntimeException ex) {
            Sky.log().log(Level.WARNING, "[mobs] could not spawn " + def.id, ex);
            return null;
        }
        if (le == null || !le.isValid()) return null;   // a CreatureSpawnEvent listener refused it
        LiveMob lm = new LiveMob(le, def, owner, ruleId, def.health);
        track(lm);
        updatePlate(lm);
        for (MobBehaviour b : behavioursOf(def)) {
            try { b.onSpawn(le, def); } catch (Throwable t) { hookError("onSpawn", def, t); }
        }
        if (le instanceof Slime s && le.isValid()) keepGaugeAfterResize(s, def, gauge);
        return le;
    }

    private void configure(LivingEntity le, MobDef def, UUID owner, double gauge) {
        le.setPersistent(false);
        le.setRemoveWhenFarAway(false);
        le.setCanPickupItems(false);
        PersistentDataContainer pdc = le.getPersistentDataContainer();
        pdc.set(CombatKeys.MOB, PersistentDataType.STRING, def.id);
        if (owner != null) pdc.set(CombatKeys.OWNER, PersistentDataType.STRING, owner.toString());
        pdc.set(CombatKeys.HP, PersistentDataType.DOUBLE, def.health);
        pdc.set(CombatKeys.MAX, PersistentDataType.DOUBLE, def.health);

        // extra.size FIRST: Slime.setSize resets vanilla max health, speed and attack damage, so the
        // gauge and the speed multiplier below must be applied after it (kuudra Request 7).
        int size = slimeSize(def);
        if (size > 0 && le instanceof Slime sl) sl.setSize(size);
        AttributeInstance mh = le.getAttribute(Attribute.MAX_HEALTH);
        if (mh != null) {
            mh.setBaseValue(gauge);
            le.setHealth(Math.min(gauge, mh.getValue()));
        }
        double scale = entityScale(def);
        if (scale > 0) {
            AttributeInstance sc = le.getAttribute(Attribute.SCALE);
            if (sc != null) sc.setBaseValue(scale);
        }
        AttributeInstance sp = le.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp != null && def.speed != 1.0) sp.setBaseValue(sp.getBaseValue() * def.speed);
        if (def.has("no_knockback")) {
            AttributeInstance kb = le.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (kb != null) kb.setBaseValue(1.0);
        }
        if (def.has("baby") && le instanceof Ageable a) a.setBaby();
        if (def.has("silent")) le.setSilent(true);
        if (def.has("no_ai")) le.setAI(false);
        le.setFireTicks(0);
        le.setMaximumNoDamageTicks(0);   // core rate-limits hits per attacker instead (LiveMob.tryHit)

        EntityEquipment eq = le.getEquipment();
        if (eq != null) {
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
                try { eq.setDropChance(s, 0f); } catch (RuntimeException ignored) { }
            }
            for (Map.Entry<String, String> en : def.equipment.entrySet()) {
                EquipmentSlot slot = parseSlot(en.getKey());
                ItemStack st = equipmentStack(en.getValue());
                if (slot == null || st == null) {
                    warnOnce("equip:" + def.id + ":" + en.getKey(), "[mobs] mob '" + def.id + "' equipment " + en.getKey() + "=" + en.getValue() + " could not be resolved");
                    continue;
                }
                try { eq.setItem(slot, st); } catch (RuntimeException ignored) { }
            }
            if (def.skull != null && !def.skull.isBlank()) {
                ItemStack head = skull(def.skull);
                if (head != null) eq.setHelmet(head);
            }
        }
        le.setCustomNameVisible(!"none".equalsIgnoreCase(str(def.extra, "nameplate")));
        le.customName(Text.c(plateFor(def, def.health, def.health)));
    }

    /**
     * {@code extra.size} of a slime / magma cube def, clamped to vanilla's 1..127; 0 = absent or not a
     * number (vanilla's random size stays). Ignored for every other entity type.
     */
    static int slimeSize(MobDef def) {
        if (def == null || def.extra == null || !def.extra.has("size")) return 0;
        double v = num(def.extra, "size", Double.NaN);
        if (!Double.isFinite(v)) return 0;
        return (int) Math.max(1, Math.min(127, Math.round(v)));
    }

    /** {@code extra.scale} (the SCALE attribute), clamped to vanilla's 0.0625..16; 0 = absent / invalid / 1. */
    static double entityScale(MobDef def) {
        if (def == null || def.extra == null || !def.extra.has("scale")) return 0;
        double v = num(def.extra, "scale", Double.NaN);
        if (!Double.isFinite(v) || v <= 0 || v == 1.0) return 0;
        return Math.max(0.0625, Math.min(16, v));
    }

    /** Vanilla's movement speed for a slime of {@code size} (Slime.setSize, float maths as vanilla does it). */
    static double vanillaSlimeSpeed(int size) { return (double) (0.2F + 0.1F * (float) size); }

    /**
     * A behaviour that calls {@code Slime.setSize} in {@code onSpawn} (several modules do) silently resets
     * the vanilla max health that core keeps as a fixed gauge, and the speed multiplier of the def. Put
     * both back. Untouched when nothing resized the slime (the gauge is still exactly where core left it).
     */
    private static void keepGaugeAfterResize(Slime s, MobDef def, double gauge) {
        try {
            AttributeInstance mh = s.getAttribute(Attribute.MAX_HEALTH);
            if (mh == null || Math.abs(mh.getBaseValue() - gauge) < 1e-9) return;
            mh.setBaseValue(gauge);
            s.setHealth(Math.max(0.5, Math.min(gauge, mh.getValue())));
            AttributeInstance sp = s.getAttribute(Attribute.MOVEMENT_SPEED);
            if (sp != null && def.speed != 1.0 && Math.abs(sp.getBaseValue() - vanillaSlimeSpeed(s.getSize())) < 1e-4)
                sp.setBaseValue(sp.getBaseValue() * def.speed);
        } catch (RuntimeException ignored) { }
    }

    static EquipmentSlot parseSlot(String s) {
        if (s == null) return null;
        String k = s.trim().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "HEAD", "HELMET" -> EquipmentSlot.HEAD;
            case "CHEST", "CHESTPLATE" -> EquipmentSlot.CHEST;
            case "LEGS", "LEGGINGS" -> EquipmentSlot.LEGS;
            case "FEET", "BOOTS" -> EquipmentSlot.FEET;
            case "HAND", "MAIN_HAND", "MAINHAND" -> EquipmentSlot.HAND;
            case "OFF_HAND", "OFFHAND" -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    private static ItemStack equipmentStack(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            if (Sky.items().exists(id)) return Sky.items().create(id, 1);
        } catch (RuntimeException ignored) { }
        Material m = Material.matchMaterial(id);
        return m == null || !m.isItem() ? null : new ItemStack(m);
    }

    private static ItemStack skull(String texture) {
        try {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            PlayerProfile prof = Bukkit.createProfile(UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)), "skymob");
            prof.setProperty(new ProfileProperty("textures", texture));
            meta.setPlayerProfile(prof);
            head.setItemMeta(meta);
            return head;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- tracking ---------------------------------------------------------------------------

    private void track(LiveMob lm) {
        live.put(lm.entity.getUniqueId(), lm);
        byId.computeIfAbsent(lm.def.id, k -> new HashSet<>()).add(lm.entity);
        spawner.onTracked(lm.ruleId);
    }

    /** Forget a mob. {@code died} = killed (starts the respawn timer of its rule). */
    void untrack(LiveMob lm, boolean died) {
        if (live.remove(lm.entity.getUniqueId()) == null) return;
        Set<LivingEntity> s = byId.get(lm.def.id);
        if (s != null) {
            s.remove(lm.entity);
            if (s.isEmpty()) byId.remove(lm.def.id);
        }
        spawner.onUntracked(lm.ruleId, died);
    }

    LiveMob get(Entity e) { return e == null ? null : live.get(e.getUniqueId()); }

    LiveMob get(UUID id) { return live.get(id); }

    int aliveCount() { return live.size(); }

    Collection<LiveMob> liveMobs() { return live.values(); }

    @Override
    public MobDef defOf(LivingEntity entity) {
        LiveMob lm = get(entity);
        return lm == null ? null : lm.def;
    }

    @Override public boolean isSkyMob(LivingEntity entity) { return get(entity) != null; }

    @Override
    public double health(LivingEntity mob) {
        LiveMob lm = get(mob);
        return lm == null ? 0 : Math.max(0, lm.hp);
    }

    @Override
    public double maxHealth(LivingEntity mob) {
        LiveMob lm = get(mob);
        return lm == null ? 0 : lm.max;
    }

    @Override
    public void setHealth(LivingEntity mob, double health) {
        LiveMob lm = get(mob);
        if (lm == null || lm.dead) return;
        lm.hp = Math.max(0, Math.min(lm.max, health));
        persistHealth(lm);
        updatePlate(lm);
        if (lm.hp <= 0) die(lm);
    }

    @Override
    public void setMaxHealth(LivingEntity mob, double max) {
        LiveMob lm = get(mob);
        if (lm == null || lm.dead || !(max > 0) || Double.isInfinite(max)) return;
        boolean wasFull = lm.hp >= lm.max;
        lm.max = max;
        lm.hp = wasFull ? max : Math.min(lm.hp, max);
        persistHealth(lm);
        updatePlate(lm);
    }

    @Override
    public void setDisplayName(LivingEntity mob, String name) {
        LiveMob lm = mob == null ? null : get(mob);
        if (lm == null || lm.dead) return;
        try {
            var pdc = mob.getPersistentDataContainer();
            if (name == null || name.isBlank()) pdc.remove(CombatKeys.NAME);
            else pdc.set(CombatKeys.NAME, PersistentDataType.STRING, name);
        } catch (RuntimeException ignored) { }
        updatePlate(lm);
    }

    @Override
    public void setAllies(LivingEntity mob, java.util.function.Predicate<Player> allies) {
        LiveMob lm = mob == null ? null : get(mob);
        if (lm != null) lm.allies = allies;
    }

    @Override
    public UUID owner(LivingEntity mob) {
        LiveMob lm = get(mob);
        return lm == null ? null : lm.owner;
    }

    @Override
    public void remove(LivingEntity mob) {
        if (mob == null) return;
        LiveMob lm = get(mob);
        if (lm != null) {
            lm.dead = true;
            untrack(lm, false);
        }
        mob.remove();
    }

    @Override
    public Collection<LivingEntity> live(String mobId) {
        Set<LivingEntity> s = byId.get(mobId);
        return s == null ? List.of() : List.copyOf(s);
    }

    // ---- damage & death (called by CombatServiceImpl) ---------------------------------------

    void forget(UUID player) { notYoursMsg.remove(player); }

    /** Owner rule: an owned mob can only be hurt by its owner. Tells the player (throttled) when not. */
    boolean canDamage(Player p, LiveMob lm, boolean tell) {
        if (lm.owner == null || p == null || lm.allows(p)) return true;
        if (p.getGameMode() == GameMode.CREATIVE && p.hasPermission("skyclone.admin")) return true;
        if (tell) {
            long now = System.currentTimeMillis();
            Long last = notYoursMsg.get(p.getUniqueId());
            if (last == null || now - last > 2000) {
                notYoursMsg.put(p.getUniqueId(), now);
                Msg.send(p, lm.def.has("boss") ? "&cThis is not your boss!" : "&cThis mob belongs to someone else!");
            }
        }
        return false;
    }

    /** Subtract health, credit the attacker, refresh the plate, die at 0. */
    void damage(LiveMob lm, double amount, Player attacker) {
        if (lm.dead || !(amount > 0)) return;
        if (attacker != null) {
            lm.lastPlayer = attacker.getUniqueId();
            lm.lastPlayerAt = System.currentTimeMillis();
            if (lm.entity instanceof Mob m && !lm.def.has("passive") && !lm.def.has("no_ai")
                    && lm.allows(attacker)) {
                LivingEntity t = m.getTarget();
                if (t == null || !t.isValid()) m.setTarget(attacker);
            }
        }
        lm.hp -= amount;
        if (lm.hp < 0) lm.hp = 0;
        persistHealth(lm);
        updatePlate(lm);
        if (lm.hp <= 0) die(lm);
    }

    void runOnDamaged(LiveMob lm, DamageContext ctx) {
        for (MobBehaviour b : behavioursOf(lm.def)) {
            try { b.onDamaged(lm.entity, lm.def, ctx); } catch (Throwable t) { hookError("onDamaged", lm.def, t); }
        }
    }

    void runOnAttack(LiveMob lm, Player target, DamageContext ctx) {
        for (MobBehaviour b : behavioursOf(lm.def)) {
            try { b.onAttack(lm.entity, lm.def, target, ctx); } catch (Throwable t) { hookError("onAttack", lm.def, t); }
        }
    }

    private void persistHealth(LiveMob lm) {
        try {
            PersistentDataContainer pdc = lm.entity.getPersistentDataContainer();
            pdc.set(CombatKeys.HP, PersistentDataType.DOUBLE, lm.hp);
            pdc.set(CombatKeys.MAX, PersistentDataType.DOUBLE, lm.max);
        } catch (RuntimeException ignored) { }
    }

    String plateFor(MobDef def, double hp, double max) {
        return CombatFormat.nameplate(def.name, def.level, hp, max, def.has("boss"),
                CombatDials.SHORT_NUM_THRESHOLD.get(), str(def.extra, "nameFormat"));
    }

    void updatePlate(LiveMob lm) {
        String plate = plateFor(lm.def, lm.hp, lm.max);
        // wave 5: a per-instance name (MobService.setDisplayName) replaces the def's name in the plate
        try {
            String ov = lm.entity.getPersistentDataContainer().get(CombatKeys.NAME, PersistentDataType.STRING);
            if (ov != null && !ov.isEmpty()) {
                plate = CombatFormat.nameplate(ov, lm.def.level, lm.hp, lm.max, lm.def.has("boss"),
                        CombatDials.SHORT_NUM_THRESHOLD.get(), str(lm.def.extra, "nameFormat"));
            }
        } catch (RuntimeException ignored) { }
        // skills request 11 (wave 3): a Runic mob (PDC skyclone:runic = 1) has a purple name
        try {
            Byte runic = lm.entity.getPersistentDataContainer().get(CombatKeys.RUNIC, PersistentDataType.BYTE);
            if (runic != null && runic == 1 && lm.def.name != null) {
                int i = plate.indexOf("&c" + lm.def.name);
                if (i >= 0) plate = plate.substring(0, i) + "&5" + plate.substring(i + 2);
            }
        } catch (RuntimeException ignored) { }
        if (plate.equals(lm.lastPlate)) return;
        lm.lastPlate = plate;
        try { lm.entity.customName(Text.c(plate)); } catch (RuntimeException ignored) { }
    }

    /** Death: MobKill -> coins -> loot -> vanilla death animation. Idempotent. */
    void die(LiveMob lm) {
        if (lm.dead) return;
        lm.dead = true;
        untrack(lm, true);
        LivingEntity mob = lm.entity;
        MobDef def = lm.def;
        Location loc = mob.getLocation();
        Player killer = resolveKiller(lm);

        for (MobBehaviour b : behavioursOf(def)) {
            try { b.onDeath(mob, def, killer); } catch (Throwable t) { hookError("onDeath", def, t); }
        }
        SkyEvents.MobKill ev = new SkyEvents.MobKill(killer, mob, def, loc);
        try { Bukkit.getPluginManager().callEvent(ev); } catch (Throwable t) { Sky.log().log(Level.WARNING, "[mobs] MobKill listener threw", t); }

        if (killer != null && killer.isOnline()) {
            double coins = Math.floor(Math.max(0, ev.coins) * 10) / 10.0;
            if (coins > 0 && Double.isFinite(coins)) {
                try {
                    Sky.economy().deposit(killer, coins, "mob_kill:" + def.id);
                    if (CombatDials.COIN_FLASH.get() && CombatCore.indicators != null) {
                        CombatCore.indicators.show(loc.clone().add(0, Math.max(0.6, mob.getHeight() * 0.5), 0),
                                CombatFormat.coins(coins), killer);
                    }
                } catch (Throwable t) {
                    Sky.log().log(Level.WARNING, "[mobs] could not pay kill coins", t);
                }
            }
            if (def.drops != null && !def.drops.isEmpty()) {
                try {
                    double mf = Sky.loot().magicFind(killer, def);   // stat + per-target modifiers (wave 5)
                    Sky.loot().roll(killer, def.drops, lootSource(def), mf);
                } catch (Throwable t) {
                    Sky.log().log(Level.WARNING, "[mobs] loot roll failed for " + def.id, t);
                }
            }
        }
        // Vanilla death animation, one tick later so we never kill an entity in the middle of its
        // own vanilla damage event. Until then it is untracked, so CombatListener cancels any hit
        // on it. EntityDeathEvent clears drops/xp for anything carrying our tag.
        try {
            EntityEquipment eq = mob.getEquipment();
            if (eq != null) eq.clear();
            mob.setAI(false);
        } catch (Throwable ignored) { }
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            try {
                if (mob.isValid() && !mob.isDead()) mob.setHealth(0);
            } catch (Throwable t) {
                mob.remove();
            }
        });
    }

    private Player resolveKiller(LiveMob lm) {
        long window = Math.max(1, CombatDials.KILLER_WINDOW.get()) * 1000L;
        Player p = null;
        if (lm.lastPlayer != null && System.currentTimeMillis() - lm.lastPlayerAt <= window) {
            p = Bukkit.getPlayer(lm.lastPlayer);
        }
        if (lm.owner != null) {
            // Owned mobs: loot only ever goes to the owner.
            Player o = Bukkit.getPlayer(lm.owner);
            return o != null && o.isOnline() ? o : null;
        }
        return p != null && p.isOnline() ? p : null;
    }

    static LootSource lootSource(MobDef def) {
        String s = str(def.extra, "lootSource");
        if (s != null) {
            try { return LootSource.valueOf(s.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { }
        }
        if (def.has("sea_creature")) return LootSource.FISHING;
        if (def.slayer != null && def.has("boss")) return LootSource.SLAYER;
        return LootSource.MOB_DROP;
    }

    // ---- behaviours ---------------------------------------------------------------------------

    List<MobBehaviour> behavioursOf(MobDef def) {
        if (def.behaviours == null || def.behaviours.isEmpty()) return List.of();
        List<MobBehaviour> out = new ArrayList<>(def.behaviours.size());
        for (String id : def.behaviours) {
            MobBehaviour b = behaviours.get(id);
            if (b != null) out.add(b);
            else warnOnce("beh:" + id, "[mobs] behaviour '" + id + "' (used by " + def.id + ") is not registered");
        }
        return out;
    }

    private void hookError(String hook, MobDef def, Throwable t) {
        if (warned.add("hook:" + hook + ":" + def.id)) {
            Sky.log().log(Level.WARNING, "[mobs] behaviour " + hook + " threw for " + def.id + " (logged once)", t);
        }
    }

    void warnOnce(String key, String msg) {
        if (warned.add(key)) Sky.log().warning(msg);
    }

    /** Every 5 ticks: validity, void, behaviours' onTick, 'aggressive' targeting. */
    void tick() {
        tick += 5;
        if (live.isEmpty()) return;
        List<LiveMob> snapshot = new ArrayList<>(live.values());
        boolean aggroPass = tick % 20 == 0;
        double range = CombatDials.AGGRO_RANGE.get();
        for (LiveMob lm : snapshot) {
            if (lm.dead) continue;
            LivingEntity e = lm.entity;
            if (!e.isValid() || e.isDead()) {
                untrack(lm, false);
                continue;
            }
            if (e.getLocation().getY() < e.getWorld().getMinHeight() - 8) {
                remove(e);
                continue;
            }
            List<MobBehaviour> bs = behavioursOf(lm.def);
            for (MobBehaviour b : bs) {
                try { b.onTick(e, lm.def, tick); } catch (Throwable t) { hookError("onTick", lm.def, t); }
                if (lm.dead) break;
            }
            if (aggroPass && !lm.dead && lm.def.has("aggressive") && e instanceof Mob m && !lm.def.has("no_ai")) {
                LivingEntity t = m.getTarget();
                if (t == null || !t.isValid() || t.isDead()) {
                    Player best = nearestTarget(lm, range);
                    if (best != null) m.setTarget(best);
                }
            }
        }
    }

    private Player nearestTarget(LiveMob lm, double range) {
        Location l = lm.entity.getLocation();
        double best = range * range;
        Player out = null;
        for (Player p : l.getWorld().getPlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (!lm.allows(p)) continue;
            double d = p.getLocation().distanceSquared(l);
            if (d < best) { best = d; out = p; }
        }
        return out;
    }

    /** Remove every tagged entity in a world that core is not tracking (ghosts after a crash). */
    int cleanGhosts(Iterable<? extends Entity> entities) {
        int n = 0;
        for (Entity e : entities) {
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            if (pdc.has(CombatKeys.MOB, PersistentDataType.STRING) && !live.containsKey(e.getUniqueId())) {
                e.remove();
                n++;
            } else if (pdc.has(CombatKeys.INDICATOR, PersistentDataType.BYTE)
                    && (CombatCore.indicators == null || !CombatCore.indicators.isTracked(e))) {
                e.remove();
                n++;
            }
        }
        return n;
    }

    void removeAll() {
        for (LiveMob lm : new ArrayList<>(live.values())) {
            lm.dead = true;
            untrack(lm, false);
            try { lm.entity.remove(); } catch (RuntimeException ignored) { }
        }
    }

    static String str(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static double num(JsonObject o, String key, double def) {
        if (o == null || !o.has(key)) return def;
        try { return o.get(key).getAsDouble(); } catch (RuntimeException e) { return def; }
    }

    /** Drop items of every def that are not registered items (for the self-test). */
    List<String> missingDropItems() {
        List<String> out = new ArrayList<>();
        for (MobDef d : defs.values()) {
            for (DropDef dd : d.drops) {
                if (!Sky.items().exists(dd.item)) out.add(d.id + "->" + dd.item);
            }
        }
        return out;
    }
}
