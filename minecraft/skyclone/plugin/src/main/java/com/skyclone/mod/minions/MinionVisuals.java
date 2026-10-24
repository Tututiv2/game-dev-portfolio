package com.skyclone.mod.minions;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.minions.MinionCatalog.TypeDef;
import com.skyclone.mod.minions.MinionCatalog.UpgradeDef;
import com.skyclone.mod.minions.MinionStore.Placed;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.EulerAngle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Everything a minion looks like in the world: the armour-stand body, the blocks it places and mines,
 * the mobs it spawns and slays. PURELY COSMETIC - production is the simulation's job (MinionSim); this
 * class only checks the work area (the "isn't perfect" rule) and plays the actions for whoever is near.
 *
 * <p>Every block a minion places is recorded in {@code Placed.visuals}, so a player who breaks one gets
 * no drop (otherwise a Diamond Minion's ore would be a free diamond every 30 s), and it is cleaned up when
 * the minion is picked up.
 */
final class MinionVisuals {

    final NamespacedKey standKey;
    final NamespacedKey mobKey;
    final NamespacedKey crystalKey;
    /** Wave 5: on the TileState of a minion's physical storage chest: "profileId|minionId". */
    final NamespacedKey chestKey;
    /** Wave 5: on the Winter Crystal's present stand: "profileId|present". */
    final NamespacedKey presentKey;
    private final MinionsModule mod;

    static final class Anim {
        long nextAt;
        boolean place = true;
        final List<UUID> mobs = new ArrayList<>();
        /** Wave 5: when each walking mob picks its next stroll target. */
        final Map<UUID, Long> walkAt = new HashMap<>();
        /** Wave 5: the height a floating mob (ghast, bat, blaze) bobs around. */
        final Map<UUID, Double> floatY = new HashMap<>();
        int valid = -1;
        long validAt;
        long swingUntil;
        /** Where the last visible action happened; the minion turns to face it. */
        Location target;
    }

    final Map<String, Anim> anims = new HashMap<>();

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private static final Set<Material> SOIL = Set.of(Material.DIRT, Material.GRASS_BLOCK, Material.FARMLAND, Material.PODZOL,
            Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.MYCELIUM, Material.MOSS_BLOCK, Material.MUD);
    private static final Set<Material> TILLABLE = Set.of(Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT);
    private static final Material[] FLOWERS = {Material.DANDELION, Material.DANDELION, Material.POPPY, Material.ALLIUM,
            Material.AZURE_BLUET, Material.BLUE_ORCHID, Material.OXEYE_DAISY, Material.RED_TULIP, Material.ORANGE_TULIP,
            Material.WHITE_TULIP, Material.PINK_TULIP};

    MinionVisuals(MinionsModule mod) {
        this.mod = mod;
        this.standKey = new NamespacedKey(Sky.plugin(), "minion");
        this.mobKey = new NamespacedKey(Sky.plugin(), "minion_mob");
        this.crystalKey = new NamespacedKey(Sky.plugin(), "minion_crystal");
        this.chestKey = new NamespacedKey(Sky.plugin(), "minion_chest");
        this.presentKey = new NamespacedKey(Sky.plugin(), "minion_present");
    }

    Anim anim(String minionId) { return anims.computeIfAbsent(minionId, k -> new Anim()); }

    // ---- the body ----------------------------------------------------------------------------------

    static Location standLocation(World w, int x, int y, int z, float yaw) {
        Location l = new Location(w, x + 0.5, y, z + 0.5);
        l.setYaw(yaw);
        return l;
    }

    /** How far a seated minion is lifted so it sits on the half-size chair seat. */
    static final double SEAT_LIFT = 0.25;

    /** Where the body stands: its block, lifted onto the chair while it sits. */
    static Location bodyLocation(World w, Placed m) {
        Location l = standLocation(w, m.x, m.y, m.z, m.yaw);
        // a placed (full-size) Minion Chair's seat is half a block up; our half-size display chair's a quarter
        if (m.seated) l.add(0, MinionManager.FURNITURE_SEAT.equals(m.chair) ? 0.5 : SEAT_LIFT, 0);
        return l;
    }

    ArmorStand spawnStand(World w, Placed m, TypeDef t, String profileId) {
        Location at = bodyLocation(w, m);
        return w.spawn(at, ArmorStand.class, as -> {
            as.setSmall(true);
            as.setArms(true);
            as.setBasePlate(false);
            as.setGravity(false);
            as.setInvulnerable(true);
            as.setPersistent(false);
            as.setCanPickupItems(false);
            as.setSilent(true);
            as.setCustomNameVisible(false);
            for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
                as.addEquipmentLock(s, ArmorStand.LockType.REMOVING_OR_CHANGING);
                as.addEquipmentLock(s, ArmorStand.LockType.ADDING_OR_CHANGING);
            }
            as.getPersistentDataContainer().set(standKey, PersistentDataType.STRING, profileId + "|" + m.id);
            dress(as, m, t);
        });
    }

    void dress(ArmorStand as, Placed m, TypeDef t) {
        EntityEquipment eq = as.getEquipment();
        String texture = null;
        UpgradeDef skin = mod.cat.upgrade(m.skin);
        if (skin != null && skin.texture != null) texture = skin.texture;
        if (texture == null) {
            var def = Sky.items().def(t.itemId(m.tier));
            if (def != null) texture = def.skull;
        }
        eq.setHelmet(Icons.skull(texture, " ", List.of()));
        Color c = color(t.color);
        eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, c));
        eq.setLeggings(leather(Material.LEATHER_LEGGINGS, c));
        eq.setBoots(leather(Material.LEATHER_BOOTS, c));
        eq.setItemInMainHand(new ItemStack(tool(t.tool, m.tier)));
        as.setRightArmPose(new EulerAngle(Math.toRadians(-10), 0, Math.toRadians(-10)));
        // seated: legs straight out in front, hands resting; standing: the default pose
        EulerAngle leg = m.seated ? new EulerAngle(Math.toRadians(-90), 0, 0) : EulerAngle.ZERO;
        as.setLeftLegPose(m.seated ? new EulerAngle(Math.toRadians(-90), Math.toRadians(-8), 0) : EulerAngle.ZERO);
        as.setRightLegPose(m.seated ? new EulerAngle(Math.toRadians(-90), Math.toRadians(8), 0) : leg);
        if (m.seated) as.setLeftArmPose(new EulerAngle(Math.toRadians(-20), 0, Math.toRadians(-10)));
    }

    // ---- wave 5: the Minion Chair (cosmetic) --------------------------------------------------------

    /** Minion id -> the BlockDisplay chair it sits on. */
    final Map<String, UUID> chairDisplays = new HashMap<>();

    /**
     * Seated: a half-size oak-stairs chair (the reference's Minion Chair is a small stairs seat) under a body
     * lifted onto it and turned to its placed facing. Standing: no chair, body back on its block. Idempotent.
     */
    void syncChair(ArmorStand as, Placed m) {
        UUID u = chairDisplays.get(m.id);
        Entity cur = u == null ? null : org.bukkit.Bukkit.getEntity(u);
        // sitting on a placed chair whose furniture was picked up since: it keeps sitting, on a display chair now
        if (m.seated && MinionManager.FURNITURE_SEAT.equals(m.chair)
                && !mod.manager.furnitureChairAt(as.getWorld().getBlockAt(m.x, m.y, m.z))) m.chair = null;
        Location want = bodyLocation(as.getWorld(), m);
        if (Math.abs(as.getLocation().getY() - want.getY()) > 0.01) {
            as.teleport(want);
            as.setHeadPose(EulerAngle.ZERO);
        }
        if (!m.seated || MinionManager.FURNITURE_SEAT.equals(m.chair)) {   // standing, or the real chair is there
            if (cur != null) cur.remove();
            chairDisplays.remove(m.id);
            return;
        }
        if (cur != null && cur.isValid()) return;
        Location at = new Location(as.getWorld(), m.x + 0.5, m.y, m.z + 0.5);
        float s = 0.5f;
        BlockData stairs = Material.OAK_STAIRS.createBlockData();
        if (stairs instanceof org.bukkit.block.data.Directional d) d.setFacing(backFace(m.yaw));
        try {
            org.bukkit.entity.BlockDisplay disp = as.getWorld().spawn(at, org.bukkit.entity.BlockDisplay.class, bd -> {
                bd.setPersistent(false);
                bd.setBlock(stairs);
                bd.setTransformation(new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(-s / 2, 0, -s / 2), new org.joml.Quaternionf(),
                        new org.joml.Vector3f(s, s, s), new org.joml.Quaternionf()));
            });
            chairDisplays.put(m.id, disp.getUniqueId());
        } catch (Throwable ex) {
            Sky.log().log(java.util.logging.Level.FINE, "[minions] chair display failed", ex);
        }
    }

    void removeChair(String minionId) {
        UUID u = chairDisplays.remove(minionId);
        Entity e = u == null ? null : org.bukkit.Bukkit.getEntity(u);
        if (e != null) e.remove();
    }

    /** The side BEHIND a minion facing {@code yaw} (stairs face their tall half that way: the backrest). */
    static BlockFace backFace(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4);    // 0 south-facing, 1 west, 2 north, 3 east
        return switch (q) {
            case 0 -> BlockFace.NORTH;
            case 1 -> BlockFace.EAST;
            case 2 -> BlockFace.SOUTH;
            default -> BlockFace.WEST;
        };
    }

    // ---- the attached storage chest (cosmetic) ----------------------------------------------------

    /** Minion id -> the BlockDisplay showing its attached storage chest. */
    final Map<String, UUID> chestDisplays = new HashMap<>();

    /**
     * Keep the attached storage chest visible. Since wave 5 it is normally a REAL chest block touching the minion
     * (like the reference): always empty, tagged with the minion it belongs to, clicking it opens the minion's
     * chest storage, hoppers and vanilla opening are blocked (MinionListener). The storage itself always lives in
     * the minion's data. Only when no touching cell is free (or {@code minions.physical-chests} is off) does it
     * fall back to a small BlockDisplay on the minion's back: no inventory, no hitbox, no work cell taken.
     */
    void syncChest(ArmorStand as, Placed m) {
        UUID u = chestDisplays.get(m.id);
        Entity cur = u == null ? null : org.bukkit.Bukkit.getEntity(u);
        // Wave 5: a REAL chest block beside the minion replaces the display. If that block was lost (a rebuilt
        // island, an admin edit) it is put back when its spot is free, else the display takes over again.
        // (A chest attached before wave 5, or with no free spot, stays a display: re-attaching it places a block.)
        if (m.chest != null && !m.chest.isEmpty() && mod.dials.physicalChests.get() && m.chestBlock != null
                && as.getWorld().isChunkLoaded(unpackX(m.chestBlock) >> 4, unpackZ(m.chestBlock) >> 4)) {
            Block b = unpack(as.getWorld(), m.chestBlock);
            String[] tag = mod.manager.chestTag(b);
            if (tag == null || !tag[1].equals(m.id)) {
                String profile = profileOf(as);
                if (!b.isEmpty() || profile == null || !mod.manager.placeChestBlockAt(m, b, profile)) m.chestBlock = null;
            }
        }
        boolean want = m.chest != null && !m.chest.isEmpty() && mod.dials.animate.get() && m.chestBlock == null;
        if (!want) {
            if (cur != null) cur.remove();
            chestDisplays.remove(m.id);
            return;
        }
        if (cur != null && cur.isValid()) return;
        double yaw = Math.toRadians(m.yaw);
        // behind the minion: forward is (-sin yaw, cos yaw), so back is the opposite
        double bx = Math.sin(yaw) * 0.32, bz = -Math.cos(yaw) * 0.32;
        Location at = new Location(as.getWorld(), m.x + 0.5 + bx, m.y, m.z + 0.5 + bz);
        at.setYaw(m.yaw);
        float s = 0.42f;
        try {
            org.bukkit.entity.BlockDisplay d = as.getWorld().spawn(at, org.bukkit.entity.BlockDisplay.class, bd -> {
                bd.setPersistent(false);
                bd.setBlock(Material.CHEST.createBlockData());
                bd.setTransformation(new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(-s / 2, 0, -s / 2), new org.joml.Quaternionf(),
                        new org.joml.Vector3f(s, s, s), new org.joml.Quaternionf()));
            });
            chestDisplays.put(m.id, d.getUniqueId());
        } catch (Throwable ex) {
            Sky.log().log(java.util.logging.Level.FINE, "[minions] chest display failed", ex);
        }
    }

    /** The profile id a minion body was spawned for (its PDC tag), or null. */
    String profileOf(ArmorStand as) {
        String v = as.getPersistentDataContainer().get(standKey, PersistentDataType.STRING);
        if (v == null) return null;
        int i = v.indexOf('|');
        return i <= 0 ? null : v.substring(0, i);
    }

    void removeChest(String minionId) {
        UUID u = chestDisplays.remove(minionId);
        Entity e = u == null ? null : org.bukkit.Bukkit.getEntity(u);
        if (e != null) e.remove();
    }

    private static ItemStack leather(Material mat, Color c) {
        ItemStack it = new ItemStack(mat);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) {
            lm.setColor(c);
            Icons.hide(lm);
            it.setItemMeta(lm);
        }
        return it;
    }

    static Color color(String hex) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16));
        } catch (Exception e) {
            return Color.fromRGB(0x8080C0);
        }
    }

    /** Tool by tier: wood I-III, stone IV-V, iron VI-VIII, gold IX-X, diamond XI-XII. */
    static Material tool(String kind, int tier) {
        if ("ROD".equals(kind)) return Material.FISHING_ROD;
        String grade = tier <= 3 ? "WOODEN" : tier <= 5 ? "STONE" : tier <= 8 ? "IRON" : tier <= 10 ? "GOLDEN" : "DIAMOND";
        String k = kind == null ? "PICKAXE" : kind;
        Material m = Material.matchMaterial(grade + "_" + k);
        return m == null ? Material.WOODEN_PICKAXE : m;
    }

    void bubble(ArmorStand as, String legacy) {
        if (legacy == null || legacy.isEmpty()) {
            if (as.isCustomNameVisible()) as.setCustomNameVisible(false);
            return;
        }
        as.customName(Text.c(legacy));
        as.setCustomNameVisible(true);
    }

    void swing(ArmorStand as, Anim a) {
        as.setRightArmPose(new EulerAngle(Math.toRadians(-100), 0, Math.toRadians(-10)));
        a.swingUntil = System.currentTimeMillis() + 400;
    }

    /** Remember the block an action touched (first one wins: a tree's log, not its leaf). */
    private void aim(Placed m, Block b) {
        Anim a = anims.get(m.id);
        if (a != null && a.target == null) a.target = b.getLocation().add(0.5, 0.5, 0.5);
    }

    /**
     * Turn the minion toward the cell it is working on, like the reference: the body yaws round and the
     * head tilts down at the block. A small stand's head sits about 0.75 above its feet.
     */
    void face(ArmorStand as, Location target) {
        if (target == null || target.getWorld() == null || !target.getWorld().equals(as.getWorld())) return;
        Location from = as.getLocation();
        double dx = target.getX() - from.getX(), dz = target.getZ() - from.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.05) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double dy = (from.getY() + 0.75) - target.getY();
        double pitch = Math.max(-0.6, Math.min(0.9, Math.atan2(dy, flat)));
        as.setRotation(yaw, 0f);
        as.setHeadPose(new EulerAngle(pitch, 0, 0));
    }

    /**
     * Mob minions keep their mobs AI-less (they must not wander off the island or fight anyone), which left them
     * frozen like statues. Every half second each one may glance somewhere else, so the pen looks alive.
     */
    void idleMobs(World w, Placed m, Anim a) {
        if (a.mobs.isEmpty()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long now = System.currentTimeMillis();
        int reach = radius(m);
        for (UUID id : a.mobs) {
            Entity e = org.bukkit.Bukkit.getEntity(id);
            if (e == null || !e.isValid()) continue;
            Location l = e.getLocation();
            // Wave 5: walkers stroll their pen with path-finding (every AI goal removed: they never target, flee,
            // eat grass or wander off on their own). A leash pulls anything that left the work area back in.
            if (e instanceof org.bukkit.entity.Mob mob && mob.hasAI()) {
                double dx = l.getX() - (m.x + 0.5), dz = l.getZ() - (m.z + 0.5);
                if (Math.abs(dx) > reach + 1.5 || Math.abs(dz) > reach + 1.5 || Math.abs(l.getY() - m.y) > 2.5) {
                    Block home = randomUsable(w, m);
                    if (home != null) mob.teleport(home.getLocation().add(0.5, 0, 0.5));
                    a.walkAt.put(id, now + 1500);
                    continue;
                }
                Long next = a.walkAt.get(id);
                if (next == null || now >= next) {
                    Block goal = randomUsable(w, m);
                    if (goal != null) {
                        try { mob.getPathfinder().moveTo(goal.getLocation().add(0.5, 0, 0.5), 0.8); }
                        catch (Throwable ignored) { }
                    }
                    a.walkAt.put(id, now + 2500 + r.nextInt(3500));
                }
                continue;
            }
            // Floaters (ghast, bat, blaze) bob gently around the height they were spawned at.
            Double base = a.floatY.get(id);
            if (base != null) {
                double y = base + Math.sin(now / 700.0 + (id.hashCode() & 7)) * 0.18;
                Location to = l.clone();
                to.setY(y);
                if (r.nextInt(3) == 0) to.setYaw(l.getYaw() + (r.nextFloat() - 0.5f) * 60f);
                e.teleport(to);
                continue;
            }
            // Everything else stays put and glances about now and then.
            if (r.nextInt(4) != 0) continue;
            float yaw = l.getYaw() + (r.nextFloat() - 0.5f) * 90f;
            float pitch = (r.nextFloat() - 0.5f) * 40f;
            e.setRotation(yaw, pitch);
            if (e instanceof LivingEntity le) le.setBodyYaw(yaw);
        }
    }

    /** A random cell of the work area the minion's mobs may stand in, or null. */
    private Block randomUsable(World w, Placed m) {
        TypeDef t = mod.cat.type(m.type);
        if (t == null) return null;
        List<Block> spots = new ArrayList<>();
        for (Block cell : ring(w, m)) if (usable(cell, m, t)) spots.add(cell);
        return pick(spots);
    }

    /**
     * Ground mobs that path-find around the pen. Flying and bouncing mobs stay AI-less, and so do endermen: their
     * daylight teleport lives in their own AI step, not in a goal, so removing goals would not stop it.
     */
    private static final Set<EntityType> WALKERS = java.util.EnumSet.of(EntityType.CHICKEN, EntityType.COW, EntityType.PIG,
            EntityType.SHEEP, EntityType.RABBIT, EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER,
            EntityType.CAVE_SPIDER, EntityType.CREEPER);

    /** Mobs that hover instead of standing (they bob in place). */
    private static final Set<EntityType> FLOATERS = java.util.EnumSet.of(EntityType.GHAST, EntityType.BAT, EntityType.BLAZE);

    void unswing(ArmorStand as, Anim a) {
        if (a.swingUntil > 0 && System.currentTimeMillis() >= a.swingUntil) {
            as.setRightArmPose(new EulerAngle(Math.toRadians(-10), 0, Math.toRadians(-10)));
            a.swingUntil = 0;
        }
    }

    // ---- the work area ------------------------------------------------------------------------------

    int radius(Placed m) {
        int r = 2;
        for (UpgradeDef u : MinionSim.installed(mod.cat, m)) r += Math.max(0, u.radius);
        return Math.min(4, r);
    }

    List<Block> ring(World w, Placed m) {
        int r = radius(m);
        List<Block> l = new ArrayList<>((2 * r + 1) * (2 * r + 1));
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) continue;
                l.add(w.getBlockAt(m.x + dx, m.y, m.z + dz));
            }
        return l;
    }

    /** Number of work cells this minion can use right now (0 = "This location isn't perfect!"). */
    int validCells(World w, Placed m, TypeDef t) {
        int n = 0;
        for (Block cell : ring(w, m)) if (usable(cell, m, t)) n++;
        return n;
    }

    private boolean usable(Block cell, Placed m, TypeDef t) {
        switch (t.kind) {
            case "WATER": {
                Block below = cell.getRelative(BlockFace.DOWN);
                return cell.getType() == Material.WATER || below.getType() == Material.WATER;
            }
            case "MOB":
                return (cell.isEmpty() || isMobPassable(cell)) && cell.getRelative(BlockFace.UP).isEmpty()
                        && ("NONE".equals(t.floor) || cell.getRelative(BlockFace.DOWN).getType().isSolid());
            case "TREE":
                return (cell.isEmpty() || isVisual(cell, t)) && floorOk(t.floor, cell);
            default:
                return (cell.isEmpty() || isVisual(cell, t)) && floorOk(t.floor, cell);
        }
    }

    private static boolean isMobPassable(Block b) {
        Material m = b.getType();
        return !m.isSolid() && !m.isAir() && m != Material.WATER && m != Material.LAVA;
    }

    boolean floorOk(String floor, Block cell) {
        Block below = cell.getRelative(BlockFace.DOWN);
        Material f = below.getType();
        return switch (floor == null ? "NONE" : floor) {
            case "NONE" -> true;
            case "SOLID" -> f.isSolid();
            case "FARMLAND" -> f == Material.FARMLAND || TILLABLE.contains(f);
            case "SOIL" -> SOIL.contains(f);
            case "SAND" -> f == Material.SAND || f == Material.RED_SAND;
            case "SOUL_SAND" -> f == Material.SOUL_SAND || f == Material.SOUL_SOIL;
            case "GRASS" -> f == Material.GRASS_BLOCK || f == Material.DIRT || f == Material.PODZOL || f == Material.MOSS_BLOCK
                    || f == Material.COARSE_DIRT;
            case "WATERSIDE" -> (SOIL.contains(f) || f == Material.SAND || f == Material.RED_SAND) && nextToWater(below);
            default -> f.isSolid();
        };
    }

    private static boolean nextToWater(Block b) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
            if (b.getRelative(face).getType() == Material.WATER) return true;
        return false;
    }

    /** Is this block the kind of thing this minion makes (so it may mine/harvest it)? */
    boolean isVisual(Block b, TypeDef t) {
        Material m = b.getType();
        if (m.isAir()) return false;
        switch (t.kind) {
            case "BLOCK": return m == material(t.visual);
            case "TREE": return m == log(t) || m == leaves(t);
            case "CROP":
                if ("FLOWERS".equals(t.visual)) { for (Material f : FLOWERS) if (f == m) return true; return false; }
                if ("RED_MUSHROOM".equals(t.visual)) return m == Material.RED_MUSHROOM || m == Material.BROWN_MUSHROOM;
                if ("COCOA".equals(t.visual)) return m == Material.COCOA || m == Material.JUNGLE_LOG;
                return m == material(t.visual);
            case "WATER": return m == Material.LILY_PAD && "LILY_PAD".equals(t.visual);
            default: return false;
        }
    }

    static Material material(String name) {
        if (name == null || name.isEmpty()) return null;
        return Material.matchMaterial(name);
    }

    static Material log(TypeDef t) { return material(t.visual + "_LOG"); }

    static Material leaves(TypeDef t) { return material(t.visual + "_LEAVES"); }

    // ---- actions --------------------------------------------------------------------------------------

    /** One visible action: alternate place and harvest like the reference. Returns false if nothing happened. */
    boolean act(World w, Placed m, TypeDef t, ArmorStand as) {
        Anim a = anim(m.id);
        boolean did;
        if (a.place) {
            did = place(w, m, t, a);
            if (!did) did = harvest(w, m, t, a);
        } else {
            did = harvest(w, m, t, a);
            if (!did) did = place(w, m, t, a);
        }
        a.place = !a.place;
        if (did && as != null) { face(as, a.target); swing(as, a); }
        a.target = null;
        return did;
    }

    private Block pick(List<Block> cells) {
        if (cells.isEmpty()) return null;
        return cells.get(ThreadLocalRandom.current().nextInt(cells.size()));
    }

    private boolean place(World w, Placed m, TypeDef t, Anim a) {
        switch (t.kind) {
            case "MOB": return spawnMob(w, m, t, a);
            case "WATER": return waterPlace(w, m, t);
            default: break;
        }
        List<Block> free = new ArrayList<>();
        for (Block cell : ring(w, m)) {
            if (!cell.isEmpty() || !floorOk(t.floor, cell)) continue;
            if ("TREE".equals(t.kind) || "SUNFLOWER".equals(t.visual) || "COCOA".equals(t.visual)) {
                if (!cell.getRelative(BlockFace.UP).isEmpty()) continue;
                if ("TREE".equals(t.kind) && (!cell.getRelative(BlockFace.UP, 2).isEmpty()
                        || !cell.getRelative(BlockFace.UP, 3).isEmpty())) continue;
            }
            if ("CACTUS".equals(t.visual) && !cactusRoom(cell)) continue;
            free.add(cell);
        }
        Block cell = pick(free);
        if (cell == null) return false;
        Block below = cell.getRelative(BlockFace.DOWN);
        switch (t.kind) {
            case "BLOCK": {
                Material mat = material(t.visual);
                if (mat == null) return false;
                set(m, cell, mat.createBlockData());
                w.playSound(cell.getLocation(), Sound.BLOCK_STONE_PLACE, 0.4f, 1.1f);
                return true;
            }
            case "TREE": {
                Material lg = log(t), lv = leaves(t);
                if (lg == null || lv == null) return false;
                // three logs and a plus of leaves on top; side leaves only go into AIR, never over a build
                set(m, cell, lg.createBlockData());
                set(m, cell.getRelative(BlockFace.UP), lg.createBlockData());
                set(m, cell.getRelative(BlockFace.UP, 2), lg.createBlockData());
                BlockData leaf = lv.createBlockData();
                if (leaf instanceof Leaves lf) lf.setPersistent(true);
                Block crown = cell.getRelative(BlockFace.UP, 3);
                set(m, crown, leaf);
                for (BlockFace f : SIDES) {
                    Block side = cell.getRelative(BlockFace.UP, 2).getRelative(f);
                    if (side.isEmpty()) set(m, side, leaf.clone());
                }
                w.playSound(cell.getLocation(), Sound.BLOCK_GRASS_PLACE, 0.4f, 1f);
                return true;
            }
            case "CROP": {
                Material mat;
                if ("FLOWERS".equals(t.visual)) mat = FLOWERS[ThreadLocalRandom.current().nextInt(FLOWERS.length)];
                else if ("RED_MUSHROOM".equals(t.visual)) mat = ThreadLocalRandom.current().nextBoolean() ? Material.RED_MUSHROOM : Material.BROWN_MUSHROOM;
                else if ("COCOA".equals(t.visual)) return placeCocoa(w, m, cell);
                else mat = material(t.visual);
                if (mat == null) return false;
                if ("FARMLAND".equals(t.floor) && TILLABLE.contains(below.getType())) {
                    below.setType(Material.FARMLAND, false);
                    w.playSound(below.getLocation(), Sound.ITEM_HOE_TILL, 0.4f, 1f);
                }
                BlockData data = mat.createBlockData();
                if (data instanceof Ageable ag) ag.setAge(ag.getMaximumAge());
                if (data instanceof Bisected bi) {
                    bi.setHalf(Bisected.Half.BOTTOM);
                    set(m, cell, bi);
                    BlockData top = mat.createBlockData();
                    ((Bisected) top).setHalf(Bisected.Half.TOP);
                    set(m, cell.getRelative(BlockFace.UP), top);
                } else {
                    set(m, cell, data);
                }
                w.playSound(cell.getLocation(), Sound.BLOCK_GRASS_PLACE, 0.4f, 1.2f);
                return true;
            }
            default:
                return false;
        }
    }

    /**
     * Wave 5: a Cocoa Beans minion grows a two-high jungle log with ripe pods on its sides (pods face the log
     * they hang on, like vanilla), instead of the old sparkle. Pods never go into the minion's own column.
     */
    private boolean placeCocoa(World w, Placed m, Block cell) {
        Block top = cell.getRelative(BlockFace.UP);
        set(m, cell, Material.JUNGLE_LOG.createBlockData());
        set(m, top, Material.JUNGLE_LOG.createBlockData());
        int pods = 0;
        List<BlockFace> faces = new ArrayList<>(List.of(SIDES));
        java.util.Collections.shuffle(faces, ThreadLocalRandom.current());
        for (BlockFace f : faces) {
            if (pods >= 2) break;
            Block log = pods == 0 ? top : cell;
            Block side = log.getRelative(f);
            if (!side.isEmpty() || (side.getX() == m.x && side.getZ() == m.z)) continue;
            BlockData pod = Material.COCOA.createBlockData();
            if (pod instanceof org.bukkit.block.data.type.Cocoa c) {
                c.setFacing(f.getOppositeFace());   // a pod faces the log it hangs on
                c.setAge(c.getMaximumAge());
            }
            set(m, side, pod);
            pods++;
        }
        w.playSound(cell.getLocation(), Sound.BLOCK_WOOD_PLACE, 0.4f, 1.1f);
        return true;
    }

    /** Pick one of its cocoa logs and take it down: the pods first (so none pop off as items), then the logs. */
    private boolean harvestCocoa(World w, Placed m) {
        List<Block> logs = new ArrayList<>();
        for (Block cell : ring(w, m))
            if (cell.getType() == Material.JUNGLE_LOG && m.visuals.contains(pack(cell))) logs.add(cell);
        Block base = pick(logs);
        if (base == null) return false;
        for (int dy = 0; dy <= 1; dy++) {
            Block log = base.getRelative(BlockFace.UP, dy);
            for (BlockFace f : SIDES) {
                Block side = log.getRelative(f);
                if (side.getType() == Material.COCOA && m.visuals.contains(pack(side))) breakVisual(w, m, side);
            }
        }
        for (int dy = 1; dy >= 0; dy--) {
            Block log = base.getRelative(BlockFace.UP, dy);
            if (log.getType() == Material.JUNGLE_LOG && m.visuals.contains(pack(log))) breakVisual(w, m, log);
        }
        return true;
    }

    private static boolean cactusRoom(Block cell) {
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
            if (!cell.getRelative(f).isEmpty()) return false;
        return true;
    }

    private boolean harvest(World w, Placed m, TypeDef t, Anim a) {
        switch (t.kind) {
            case "MOB": return slayMob(w, m, t, a);
            case "WATER": {
                Block lily = null;
                if ("LILY_PAD".equals(t.visual)) {
                    for (Block cell : ring(w, m)) if (cell.getType() == Material.LILY_PAD) { lily = cell; break; }
                }
                if (lily != null) { breakVisual(w, m, lily); return true; }
                Block water = null;
                for (Block cell : ring(w, m)) {
                    if (cell.getType() == Material.WATER) { water = cell; break; }
                    if (cell.getRelative(BlockFace.DOWN).getType() == Material.WATER) { water = cell.getRelative(BlockFace.DOWN); break; }
                }
                if (water == null) return false;
                Location l = water.getLocation().add(0.5, 1, 0.5);
                a.target = l.clone();
                w.spawnParticle(Particle.SPLASH, l, 12, 0.3, 0.1, 0.3, 0);
                w.spawnParticle(Particle.FISHING, l, 4, 0.2, 0.05, 0.2, 0);
                w.playSound(l, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.3f, 1.2f);
                return true;
            }
            case "CROP":
                if ("COCOA".equals(t.visual)) return harvestCocoa(w, m);
                break;
            default: break;
        }
        List<Block> mine = new ArrayList<>();
        for (Block cell : ring(w, m)) if (isVisual(cell, t)) mine.add(cell);
        Block cell = pick(mine);
        if (cell == null) return false;
        if ("TREE".equals(t.kind)) {
            for (BlockFace f : SIDES) {
                Block side = cell.getRelative(BlockFace.UP, 2).getRelative(f);
                if (side.getType() == leaves(t) && m.visuals.contains(pack(side))) breakVisual(w, m, side);
            }
            for (int dy = 3; dy >= 0; dy--) {
                Block b = cell.getRelative(BlockFace.UP, dy);
                if (isVisual(b, t)) breakVisual(w, m, b);
            }
            Block below = cell.getRelative(BlockFace.DOWN);
            if (isVisual(below, t)) breakVisual(w, m, below);
            w.playSound(cell.getLocation(), Sound.BLOCK_WOOD_BREAK, 0.5f, 1f);
            return true;
        }
        if ("SUNFLOWER".equals(t.visual)) {
            Block top = cell.getRelative(BlockFace.UP);
            if (top.getType() == Material.SUNFLOWER) breakVisual(w, m, top);
            Block bottom = cell.getRelative(BlockFace.DOWN);
            if (bottom.getType() == Material.SUNFLOWER) breakVisual(w, m, bottom);
        }
        if ("CACTUS".equals(t.visual) || "SUGAR_CANE".equals(t.visual)) {
            Block up = cell.getRelative(BlockFace.UP);
            if (up.getType() == cell.getType()) breakVisual(w, m, up);
        }
        breakVisual(w, m, cell);
        return true;
    }

    private void set(Placed m, Block b, BlockData data) {
        b.setBlockData(data, false);
        aim(m, b);
        long key = pack(b);
        if (!m.visuals.contains(key)) m.visuals.add(key);
        mod.manager.indexVisual(b.getWorld().getName(), key);
    }

    void breakVisual(World w, Placed m, Block b) {
        BlockData old = b.getBlockData();
        Material type = b.getType();
        b.setType(Material.AIR, false);
        aim(m, b);
        long key = pack(b);
        m.visuals.remove(Long.valueOf(key));
        // the spot is air again: a block a PLAYER puts here later must drop normally
        mod.manager.unindexVisual(w.getName(), key);
        Location c = b.getLocation().add(0.5, 0.5, 0.5);
        w.spawnParticle(Particle.BLOCK, c, 14, 0.25, 0.25, 0.25, old);
        Sound s = type.isSolid() ? Sound.BLOCK_STONE_BREAK : Sound.BLOCK_GRASS_BREAK;
        w.playSound(c, s, 0.5f, 1f);
    }

    /** Remove every block this minion placed and every mob it spawned (pick-up, admin clear). */
    void clearAll(World w, Placed m, TypeDef t) {
        if (w != null) {
            for (Long key : new ArrayList<>(m.visuals)) {
                Block b = unpack(w, key);
                // only blocks that still look like the minion's work (a player may have built there since)
                boolean ours = t == null ? !b.isEmpty() && !b.isLiquid() : isVisual(b, t) || b.getType() == Material.SUNFLOWER;
                if (ours) b.setType(Material.AIR, false);
                mod.manager.unindexVisual(w.getName(), key);
            }
        }
        m.visuals.clear();
        removeMobs(m.id);
        removeChest(m.id);
        removeChair(m.id);
        anims.remove(m.id);
    }

    // ---- mobs ---------------------------------------------------------------------------------------

    private boolean spawnMob(World w, Placed m, TypeDef t, Anim a) {
        a.mobs.removeIf(id -> { Entity e = org.bukkit.Bukkit.getEntity(id); return e == null || !e.isValid(); });
        a.walkAt.keySet().retainAll(a.mobs);
        a.floatY.keySet().retainAll(a.mobs);
        if (a.mobs.size() >= Math.max(1, mod.dials.maxMobs.get())) return false;
        if (t.visual == null || t.visual.isEmpty()) {
            Block c = pick(ring(w, m));
            if (c == null) return false;
            w.spawnParticle(Particle.FLAME, c.getLocation().add(0.5, 1.5, 0.5), 10, 0.3, 0.4, 0.3, 0.01);
            return true;
        }
        EntityType et;
        try { et = EntityType.valueOf(t.visual); } catch (IllegalArgumentException e) { return false; }
        Class<? extends Entity> cls = et.getEntityClass();
        if (cls == null) return false;
        List<Block> spots = new ArrayList<>();
        for (Block cell : ring(w, m)) if (usable(cell, m, t)) spots.add(cell);
        Block cell = pick(spots);
        if (cell == null) return false;
        boolean floater = FLOATERS.contains(et);
        boolean walker = !floater && WALKERS.contains(et) && mod.dials.mobWalk.get();
        double scale = et == EntityType.GHAST ? Math.max(0.05, Math.min(1, mod.dials.ghastScale.get())) : 1;
        // a floater hangs half a block up (a scaled ghast is about a block tall); a bat a little higher
        double lift = !floater ? 0 : et == EntityType.BAT ? 1.0 : 0.5;
        Location at = cell.getLocation().add(0.5, lift, 0.5);
        at.setYaw(ThreadLocalRandom.current().nextFloat() * 360f);
        Entity e = w.spawn(at, cls, ent -> {
            ent.setPersistent(false);
            ent.setSilent(true);
            ent.setInvulnerable(true);
            ent.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, m.id);
            if (ent instanceof LivingEntity le) {
                le.setAI(walker);
                le.setRemoveWhenFarAway(false);
                le.setCollidable(false);
                le.setCanPickupItems(false);
                if (floater) le.setGravity(false);
                if (scale != 1) {
                    try {
                        var attr = le.getAttribute(org.bukkit.attribute.Attribute.SCALE);
                        if (attr != null) attr.setBaseValue(scale);
                    } catch (Throwable ignored) { }
                }
                if (le instanceof org.bukkit.entity.Ageable ag) ag.setAdult();
                if (le instanceof Slime s) s.setSize(1);
                if (le instanceof Bat b) b.setAwake(true);
                if (le instanceof Zombie z) z.setShouldBurnInDay(false);
                if (le instanceof AbstractSkeleton sk) sk.setShouldBurnInDay(false);
                if (le instanceof Zombie || le instanceof AbstractSkeleton) {
                    EntityEquipment eq = le.getEquipment();
                    if (eq != null) { eq.setHelmet(new ItemStack(Material.LEATHER_HELMET)); eq.setHelmetDropChance(0f); }
                }
            }
        });
        if (walker && e instanceof org.bukkit.entity.Mob mob) {
            // no goals at all: it never targets anyone, never flees, never eats the grass; it only walks where told
            try { org.bukkit.Bukkit.getMobGoals().removeAllGoals(mob); }
            catch (Throwable ex) { mob.setAI(false); }
        }
        if (floater) a.floatY.put(e.getUniqueId(), at.getY());
        a.mobs.add(e.getUniqueId());
        a.target = at.clone().add(0, 0.5, 0);
        w.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.5, 0), 6, 0.2, 0.3, 0.2, 0.01);
        return true;
    }

    private boolean slayMob(World w, Placed m, TypeDef t, Anim a) {
        for (Iterator<UUID> it = a.mobs.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            Entity e = org.bukkit.Bukkit.getEntity(id);
            it.remove();
            a.walkAt.remove(id);
            a.floatY.remove(id);
            if (e == null || !e.isValid()) continue;
            Location l = e.getLocation();
            anim(m.id).target = l.clone().add(0, 0.5, 0);
            w.spawnParticle(Particle.POOF, l.clone().add(0, 0.5, 0), 10, 0.25, 0.3, 0.25, 0.02);
            w.spawnParticle(Particle.CRIT, l.clone().add(0, 0.8, 0), 6, 0.2, 0.2, 0.2, 0.1);
            w.playSound(l, Sound.ENTITY_GENERIC_HURT, 0.4f, 1.1f);
            e.remove();
            return true;
        }
        if (t.visual == null || t.visual.isEmpty()) {
            Block c = pick(ring(w, m));
            if (c == null) return false;
            w.spawnParticle(Particle.SMOKE, c.getLocation().add(0.5, 1.5, 0.5), 8, 0.3, 0.3, 0.3, 0.01);
            return true;
        }
        return false;
    }

    private boolean waterPlace(World w, Placed m, TypeDef t) {
        if (!"LILY_PAD".equals(t.visual)) return harvest(w, m, t, anim(m.id));
        List<Block> spots = new ArrayList<>();
        for (Block cell : ring(w, m))
            if (cell.isEmpty() && cell.getRelative(BlockFace.DOWN).getType() == Material.WATER) spots.add(cell);
        Block cell = pick(spots);
        if (cell == null) return false;
        set(m, cell, Material.LILY_PAD.createBlockData());
        return true;
    }

    void removeMobs(String minionId) {
        Anim a = anims.get(minionId);
        if (a == null) return;
        for (UUID id : a.mobs) {
            Entity e = org.bukkit.Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        a.mobs.clear();
        a.walkAt.clear();
        a.floatY.clear();
    }

    // ---- packing ------------------------------------------------------------------------------------

    static long pack(Block b) { return pack(b.getX(), b.getY(), b.getZ()); }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    static int unpackX(long k) { return (int) (k >> 38); }

    static int unpackY(long k) { int y = (int) (k & 0xFFF); return y >= 2048 ? y - 4096 : y; }

    static int unpackZ(long k) { return (int) (k << 26 >> 38); }

    static Block unpack(World w, long key) {
        return w.getBlockAt(unpackX(key), unpackY(key), unpackZ(key));
    }

    static List<String> describeFloor(String floor) {
        return switch (floor == null ? "NONE" : floor) {
            case "NONE" -> List.of("&7Open air around it.");
            case "SOLID" -> List.of("&7Solid ground around it.");
            case "FARMLAND" -> List.of("&7Dirt or farmland around it", "&7(it tills dirt itself).");
            case "SOIL" -> List.of("&7Dirt or grass around it.");
            case "SAND" -> List.of("&7Sand around it, with open", "&7space beside each cactus.");
            case "SOUL_SAND" -> List.of("&7Soul sand around it.");
            case "GRASS" -> List.of("&7Grass around it.");
            case "WATERSIDE" -> List.of("&7Dirt or sand next to water.");
            default -> List.of("&7Solid ground around it.");
        };
    }
}
