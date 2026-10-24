package com.skyclone.mod.weapons;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raise Souls (Necromancer Sword: 2 souls, Reaper Scythe: 3; research 06). A monster you kill with the
 * blade leaves its soul on the ground for a while; right-click the soul with the blade to absorb it (the
 * soul list lives on the item, {@code ItemData.extra.souls}); right-click anywhere else to raise every
 * absorbed soul as an ally; sneak right-click opens the soul list to release one. Mana scales with the
 * power (level) of what you raise. A raised monster that dies loses its soul.
 *
 * <p>Allies are plain vanilla entities, never sky mobs: they can not be farmed, drop nothing and can not be
 * hurt by players. Their vanilla attacks are replaced: every second, an ally in reach of its target deals the
 * monster's own Damage stat ({@code weapons.soul-damage-multiplier}) as damage credited to the owner, so kills
 * and drops are the owner's.
 */
final class Souls implements Listener {

    private static final NamespacedKey SUMMON = NamespacedKey.fromString("skyclone:weapons_soul_summon");
    private static final NamespacedKey GROUND = NamespacedKey.fromString("skyclone:weapons_soul_ground");

    /** A soul lying where its monster died. */
    private static final class Ground {
        UUID owner;
        String mobId;
        Interaction hit;
        ItemDisplay look;
        long until;
    }

    /** A raised soul fighting for its owner. */
    private static final class Summon {
        UUID owner;
        String mobId;
        String itemUuid;
        double damage;
        boolean ranged;
        long nextHit;
    }

    private final Traits traits;
    private final Map<UUID, Ground> ground = new HashMap<>();   // interaction entity id
    private final Map<UUID, Summon> summons = new HashMap<>();  // ally entity id

    Souls(Traits traits) {
        this.traits = traits;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 10L, 10L);
    }

    // ---- item data -----------------------------------------------------------------------------------

    static List<String> stored(ItemData d) {
        List<String> out = new ArrayList<>();
        JsonElement e = d == null ? null : d.extra.get("souls");
        if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) {
            try { out.add(x.getAsString()); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    private static void store(ItemStack s, List<String> souls) {
        JsonArray arr = new JsonArray();
        for (String id : souls) arr.add(id);
        Sky.items().update(s, d -> { if (arr.isEmpty()) d.extra.remove("souls"); else d.extra.add("souls", arr); });
    }

    int max(ItemStack s) {
        String id = Traits.idOf(s);
        return id == null ? 0 : traits.of(id).souls;
    }

    /** The blade's capacity for this player: the Ghoul pet's "Army of the Dead" stores 2 more (research 06). */
    int capacity(Player p, ItemStack s) { return WeaponMath.soulCapacity(max(s), ghoul(p, "ghoul_army_of_the_dead", "a") > 0); }

    /** Ghoul "Reaper Soul" (research 06): raising costs a% less mana. */
    static double reaperCostFactor(Player p) { return Math.max(0, 1 - ghoul(p, "ghoul_reaper_soul", "a") / 100.0); }

    /** Ghoul "Reaper Soul": raised souls deal b% more damage. */
    static double reaperDamageFactor(Player p) { return 1 + Math.max(0, ghoul(p, "ghoul_reaper_soul", "b")) / 100.0; }

    /** Ghoul "Reaper Soul": raised souls have c% more health. */
    static double reaperHealthFactor(Player p) { return 1 + Math.max(0, ghoul(p, "ghoul_reaper_soul", "c")) / 100.0; }

    /** A Ghoul pet perk number (0 without it, or with {@code weapons.pet-perks} off). */
    static double ghoul(Player p, String key, String token) {
        if (p == null || !WeaponDials.PET_PERKS.get()) return 0;
        return Passives.petValue(p, key, token);
    }

    /** Lore: what the blade carries. */
    static List<String> lore(int max, ItemData d) {
        List<String> out = new ArrayList<>();
        if (max <= 0) return out;
        List<String> souls = stored(d);
        out.add("&7Souls: &a" + souls.size() + "&7/" + max);
        for (String id : souls) {
            MobDef m = Sky.mobs().def(id);
            out.add("&8- &f" + (m == null ? Text.titleCase(id) : Text.strip(m.name) + " &8Lv" + m.level));
        }
        return out;
    }

    // ---- dropping a soul ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || e.def == null || e.location == null || e.location.getWorld() == null) return;
        if (carrier(p) == null) return;
        if (e.def.slayer != null && e.def.has("boss")) return;   // bosses keep their souls (TUNE)
        double chance = WeaponMath.soulChance(WeaponDials.SOUL_DROP_CHANCE.get(), ghoul(p, "ghoul_army_of_the_dead", "a"));
        if (chance < 1 && Math.random() >= chance) return;
        Location at = e.location.clone().add(0, 0.6, 0);
        Ground g = new Ground();
        g.owner = p.getUniqueId();
        g.mobId = e.def.id;
        g.until = Buffs.now() + (long) (WeaponDials.SOUL_LIFETIME.get() * 1000);
        g.look = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(eggOf(e.def)));
            d.setBillboard(Display.Billboard.CENTER);
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(150, 230, 255));
            d.setPersistent(false);
            d.getPersistentDataContainer().set(GROUND, PersistentDataType.BYTE, (byte) 1);
        });
        g.hit = at.getWorld().spawn(at.clone().subtract(0, 0.5, 0), Interaction.class, i -> {
            i.setInteractionWidth(1.0f);
            i.setInteractionHeight(1.2f);
            i.setResponsive(true);
            i.setPersistent(false);
            i.getPersistentDataContainer().set(GROUND, PersistentDataType.BYTE, (byte) 1);
        });
        ground.put(g.hit.getUniqueId(), g);
        Fx.sound(at, Sound.PARTICLE_SOUL_ESCAPE, 1f, 0.9f);
    }

    /**
     * The item that frees souls for this player: the held blade, else a soul-holder that works from the inventory
     * (the Summoning Ring: "monsters you kill while you have this item", NEU). Null when none.
     */
    ItemStack carrier(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (max(held) > 0 && Sky.items().meetsRequirements(p, held, false)) return held;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType().isAir() || !CARRIED.contains(Traits.idOf(it))) continue;
            if (max(it) > 0 && Sky.items().meetsRequirements(p, it, false)) return it;
        }
        return null;
    }

    /** Soul-holders that free souls from anywhere in the inventory, not only the main hand. */
    static final java.util.Set<String> CARRIED = java.util.Set.of("SUMMONING_RING");

    private static Material eggOf(MobDef def) {
        Material m = Material.matchMaterial((def.entity == null ? "ZOMBIE" : def.entity) + "_SPAWN_EGG");
        return m == null ? Material.GHAST_TEAR : m;
    }

    // ---- absorbing one -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent e) { absorb(e); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent e) { absorb(e); }

    private void absorb(PlayerInteractEntityEvent e) {
        Ground g = ground.get(e.getRightClicked().getUniqueId());
        if (g == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!p.getUniqueId().equals(g.owner)) { Msg.error(p, "That soul belongs to someone else."); return; }
        ItemStack held = p.getInventory().getItemInMainHand();
        int max = capacity(p, held);
        if (max <= 0) { Msg.error(p, "Hold a soul blade or a Summoning Ring to absorb it."); return; }
        List<String> souls = stored(Sky.items().data(held));
        if (souls.size() >= max) { Msg.error(p, "Your blade already holds " + max + " souls! Sneak right-click to release one."); return; }
        souls.add(g.mobId);
        store(held, souls);
        p.getInventory().setItemInMainHand(held);
        removeGround(g);
        MobDef m = Sky.mobs().def(g.mobId);
        Msg.send(p, "&aAbsorbed the soul of &f" + (m == null ? g.mobId : Text.strip(m.name)) + "&a! &7(" + souls.size() + "/" + max + ")");
        Fx.sound(p.getLocation(), Sound.BLOCK_SOUL_SAND_BREAK, 1f, 1.4f);
    }

    private void removeGround(Ground g) {
        ground.remove(g.hit.getUniqueId());
        if (g.hit.isValid()) g.hit.remove();
        if (g.look.isValid()) g.look.remove();
    }

    // ---- raising them --------------------------------------------------------------------------------

    /** The Raise Souls ability. Sneaking opens the soul list instead (and costs nothing). */
    AbilityService.Handler raise() {
        return u -> {
            Player p = u.player();
            if (p.isSneaking()) { menu(p).open(p); return false; }
            List<String> todo = unsummoned(p, u.item());
            if (stored(Sky.items().data(u.item())).isEmpty()) {
                Msg.error(p, "Your blade holds no souls. Kill monsters with it and right-click their souls.");
                return false;
            }
            if (todo.isEmpty()) { Msg.error(p, "Every soul on this blade is already fighting for you."); return false; }
            String itemUuid = Sky.items().data(u.item()).uuid;
            for (String mobId : todo) spawnAlly(p, mobId, itemUuid);
            Fx.sound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.4f, 1.6f);
            return true;
        };
    }

    /** Mana: the reference only says it scales with the monsters' power; ours is by level (TUNE in WeaponMath). */
    AbilityService.CostModifier cost() {
        return (use, cost) -> {
            if (use.ability() == null || !"weapons_raise_souls".equals(use.ability().id)) return cost;
            if (use.player().isSneaking()) return 0;
            double sum = 0;
            for (String id : unsummoned(use.player(), use.item())) {
                MobDef m = Sky.mobs().def(id);
                sum += WeaponMath.soulMana(m == null ? 1 : m.level);
            }
            return sum * reaperCostFactor(use.player());
        };
    }

    /** Souls on this blade not already walking around (one ally per stored entry). */
    private List<String> unsummoned(Player p, ItemStack blade) {
        List<String> souls = stored(Sky.items().data(blade));
        String itemUuid = Sky.items().data(blade).uuid;
        for (Summon s : summons.values()) {
            if (!s.owner.equals(p.getUniqueId())) continue;
            if (itemUuid != null && !itemUuid.equals(s.itemUuid)) continue;
            souls.remove(s.mobId);
        }
        return souls;
    }

    private void spawnAlly(Player p, String mobId, String itemUuid) {
        MobDef def = Sky.mobs().def(mobId);
        EntityType type;
        try { type = EntityType.valueOf(def == null || def.entity == null ? "ZOMBIE" : def.entity); } catch (IllegalArgumentException ex) { type = EntityType.ZOMBIE; }
        if (type.getEntityClass() == null || !Mob.class.isAssignableFrom(type.getEntityClass()) || type == EntityType.ENDER_DRAGON
                || type == EntityType.WITHER || type == EntityType.GIANT) type = EntityType.ZOMBIE;
        Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(1.5));
        String name = "&b" + p.getName() + "'s &f" + (def == null ? Text.titleCase(mobId) : Text.strip(def.name));
        Entity ent = p.getWorld().spawn(at, type.getEntityClass(), org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, x -> {
            x.customName(Text.c(name));
            x.setCustomNameVisible(true);
            x.setPersistent(false);
            x.setGlowing(true);
            x.getPersistentDataContainer().set(SUMMON, PersistentDataType.STRING, p.getUniqueId().toString());
            if (x instanceof Mob mob) {
                mob.setRemoveWhenFarAway(false);
                mob.setCanPickupItems(false);
                AttributeInstance hp = mob.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) {
                    hp.setBaseValue(Math.max(1, Math.min(1024, WeaponDials.SOUL_HEALTH.get() * reaperHealthFactor(p))));
                    mob.setHealth(hp.getValue());
                }
                if (mob.getEquipment() != null) mob.getEquipment().clear();
            }
            if (x instanceof Zombie z) { z.setShouldBurnInDay(false); z.setAdult(); }
            if (x instanceof AbstractSkeleton sk) sk.setShouldBurnInDay(false);
        });
        Summon s = new Summon();
        s.owner = p.getUniqueId();
        s.mobId = mobId;
        s.itemUuid = itemUuid;
        s.damage = (def == null ? 10 : def.damage) * WeaponDials.SOUL_DAMAGE.get() * reaperDamageFactor(p);
        s.ranged = ent instanceof com.destroystokyo.paper.entity.RangedEntity;
        summons.put(ent.getUniqueId(), s);
        Fx.burst(at.clone().add(0, 1, 0), Particle.SOUL, 15, 0.4);
    }

    // ---- the soul list ---------------------------------------------------------------------------------

    Menu menu(Player p) {
        ItemStack blade = p.getInventory().getItemInMainHand();
        int max = capacity(p, blade);
        List<String> souls = stored(Sky.items().data(blade));
        Menu m = new Menu("Souls", 3).chrome();
        m.set(4, Icons.of(Material.SOUL_LANTERN, "&bRaise Souls", "&7Souls held: &a" + souls.size() + "&7/" + max, "",
                "&7Click a soul to release it.", "&7A raised soul that dies is lost."));
        int[] slots = souls.size() <= 3 ? new int[]{11, 13, 15} : new int[]{10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < Math.min(slots.length, souls.size()); i++) {
            String id = souls.get(i);
            MobDef d = Sky.mobs().def(id);
            final int idx = i;
            m.set(slots[i], Icons.of(d == null ? Material.GHAST_TEAR : eggOf(d), "&f" + (d == null ? Text.titleCase(id) : Text.strip(d.name)),
                    "&7Level: &a" + (d == null ? "?" : d.level),
                    "&7Hits for: &c" + Text.commasInt((d == null ? 10 : d.damage) * WeaponDials.SOUL_DAMAGE.get() * reaperDamageFactor(p)),
                    "&7Mana to raise: &b" + Text.commasInt(WeaponMath.soulMana(d == null ? 1 : d.level) * reaperCostFactor(p)), "", "&cClick to release!"), c -> {
                ItemStack now = c.player().getInventory().getItemInMainHand();
                List<String> cur = stored(Sky.items().data(now));
                if (max(now) <= 0 || idx >= cur.size() || !cur.get(idx).equals(id)) { c.player().closeInventory(); return; }
                cur.remove(idx);
                store(now, cur);
                c.player().getInventory().setItemInMainHand(now);
                dismissOne(c.player(), id, Sky.items().data(now).uuid);
                Msg.send(c.player(), "&7Released a soul.");
                menu(c.player()).open(c.player());
            });
        }
        m.set(22, Icons.close(), c -> c.player().closeInventory());
        return m;
    }

    private void dismissOne(Player p, String mobId, String itemUuid) {
        for (Iterator<Map.Entry<UUID, Summon>> it = summons.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Summon> en = it.next();
            Summon s = en.getValue();
            if (!s.owner.equals(p.getUniqueId()) || !s.mobId.equals(mobId) || (itemUuid != null && !itemUuid.equals(s.itemUuid))) continue;
            Entity ent = Bukkit.getEntity(en.getKey());
            if (ent != null) ent.remove();
            it.remove();
            return;
        }
    }

    // ---- the allies' brains -----------------------------------------------------------------------------

    private void tick() {
        long now = Buffs.now();
        for (Iterator<Ground> it = ground.values().iterator(); it.hasNext(); ) {
            Ground g = it.next();
            if (now > g.until || !g.hit.isValid()) {
                if (g.hit.isValid()) g.hit.remove();
                if (g.look.isValid()) g.look.remove();
                it.remove();
                continue;
            }
            Fx.burst(g.look.getLocation(), Particle.SOUL, 2, 0.2);
        }
        for (Iterator<Map.Entry<UUID, Summon>> it = summons.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Summon> en = it.next();
            Summon s = en.getValue();
            Entity ent = Bukkit.getEntity(en.getKey());
            Player owner = Bukkit.getPlayer(s.owner);
            if (!(ent instanceof Mob mob) || !mob.isValid() || owner == null || !owner.getWorld().equals(mob.getWorld())) {
                if (ent != null) ent.remove();
                it.remove();
                continue;
            }
            if (mob.getLocation().distanceSquared(owner.getLocation()) > 32 * 32) {
                mob.teleport(owner.getLocation());
                mob.setTarget(null);
            }
            LivingEntity target = mob.getTarget();
            if (target == null || !Fx.target(target) || !mine(target, s.owner)) {
                target = nearestFoe(mob, s.owner);
                mob.setTarget(target);
            }
            if (target == null) {
                if (mob.getLocation().distanceSquared(owner.getLocation()) > 25) mob.getPathfinder().moveTo(owner, 1.2);
                continue;
            }
            double reach = s.ranged ? 12 : 3.2;
            if (now >= s.nextHit && mob.getLocation().distanceSquared(target.getLocation()) <= reach * reach && mob.hasLineOfSight(target)) {
                s.nextHit = now + 1000;
                mob.swingMainHand();
                Sky.combat().rawDamage(target, s.damage, owner, DamageContext.Kind.MAGIC);
            }
        }
    }

    /** Never steal: a mob owned by another player (their slayer boss, their summon) is not a target. */
    private static boolean mine(LivingEntity m, UUID owner) {
        UUID o;
        try { o = Sky.mobs().owner(m); } catch (RuntimeException e) { o = null; }
        return o == null || o.equals(owner);
    }

    private static LivingEntity nearestFoe(Mob mob, UUID owner) {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity m : Fx.mobsNear(mob.getLocation(), 16)) {
            if (m.getPersistentDataContainer().has(SUMMON) || !mine(m, owner)) continue;
            double d = m.getLocation().distanceSquared(mob.getLocation());
            if (d < bestD) { bestD = d; best = m; }
        }
        return best;
    }

    private Summon summonOf(Entity e) {
        if (e == null) return null;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Entity sh) e = sh;
        return summons.get(e.getUniqueId());
    }

    // ---- guards ------------------------------------------------------------------------------------------

    /** Allies never land vanilla hits (their damage is the timed one above) and players can not hurt them. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (summonOf(e.getDamager()) != null) { e.setCancelled(true); return; }
        if (summons.containsKey(e.getEntity().getUniqueId())) {
            Entity d = e.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
            if (d instanceof Player) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (summons.containsKey(e.getEntity().getUniqueId()) && (e.getTarget() instanceof Player
                || (e.getTarget() != null && e.getTarget().getPersistentDataContainer().has(SUMMON)))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBurn(EntityCombustEvent e) {
        if (summons.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    /** A raised soul that dies is gone from the blade (reference). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent e) {
        Summon s = summons.remove(e.getEntity().getUniqueId());
        if (s == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Player owner = Bukkit.getPlayer(s.owner);
        if (owner == null) return;
        for (ItemStack it : owner.getInventory().getContents()) {
            if (it == null || max(it) <= 0) continue;
            ItemData d = Sky.items().data(it);
            if (s.itemUuid != null && !s.itemUuid.equals(d.uuid)) continue;
            List<String> souls = stored(d);
            if (souls.remove(s.mobId)) { store(it, souls); break; }
        }
        MobDef m = Sky.mobs().def(s.mobId);
        Msg.send(owner, "&cYour raised &f" + (m == null ? s.mobId : Text.strip(m.name)) + " &cdied and its soul is lost.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { dismissAll(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { dismissAll(e.getPlayer().getUniqueId()); }

    private void dismissAll(UUID owner) {
        for (Iterator<Map.Entry<UUID, Summon>> it = summons.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Summon> en = it.next();
            if (!en.getValue().owner.equals(owner)) continue;
            Entity ent = Bukkit.getEntity(en.getKey());
            if (ent != null) ent.remove();
            it.remove();
        }
        for (Iterator<Ground> it = ground.values().iterator(); it.hasNext(); ) {
            Ground g = it.next();
            if (!g.owner.equals(owner)) continue;
            if (g.hit.isValid()) g.hit.remove();
            if (g.look.isValid()) g.look.remove();
            it.remove();
        }
    }

    void removeAll() {
        for (UUID id : summons.keySet()) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        summons.clear();
        for (Ground g : ground.values()) { if (g.hit.isValid()) g.hit.remove(); if (g.look.isValid()) g.look.remove(); }
        ground.clear();
    }

    int summonCount() { return summons.size(); }

    int groundCount() { return ground.size(); }
}
