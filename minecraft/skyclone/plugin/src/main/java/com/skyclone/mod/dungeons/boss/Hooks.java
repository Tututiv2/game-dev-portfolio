package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.Sky;
import com.skyclone.mod.dungeons.ext.BossContext;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Things in a boss arena players click or shoot: clickable entities (energy crystals, terminals, relics), clickable
 * blocks (podiums, levers, buttons, cauldrons), arrow hits on entities or blocks, and punch-to-hit device targets.
 * Every registration belongs to a run and dies with it ({@link #clear}).
 */
public final class Hooks implements Listener {

    private Hooks() {}

    public static final NamespacedKey SPIRIT = NamespacedKey.fromString("skyclone:dungeon_spirit_arrow");
    /**
     * Run-only things a player carries in a boss fight (Energy Crystals, relics, the Spirit Bow). They are plain
     * vanilla stacks with this tag - never sky items, so no sack, collection, bazaar or NPC sell price ever sees them -
     * and {@link #stripCarried} removes them whenever the player leaves the run. They cannot be dropped.
     */
    public static final NamespacedKey CARRY = NamespacedKey.fromString("skyclone:dungeon_carry");

    /** A tagged run-only stack (see {@link #CARRY}); {@code kind} names it (crystal, relic.flame, spirit_bow). */
    public static ItemStack carried(Material m, String name, List<String> lore, String kind) {
        ItemStack it = new ItemStack(m);
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        meta.displayName(com.skyclone.api.ui.Text.c(name));
        if (lore != null) meta.lore(com.skyclone.api.ui.Text.lines(lore));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(CARRY, PersistentDataType.STRING, kind);
        it.setItemMeta(meta);
        return it;
    }

    /** The {@link #CARRY} kind of a stack, or null. */
    public static String carryKind(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(CARRY, PersistentDataType.STRING);
    }

    /** How many stacks of {@code kind} (or any kind when null) the player holds. */
    public static int countCarried(Player p, String kind) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            String k = carryKind(it);
            if (k != null && (kind == null || k.equals(kind))) n += it.getAmount();
        }
        return n;
    }

    /** Remove carried stacks of {@code kind} (or every kind when null), plus Spirit Bows. Returns stacks removed. */
    public static int stripCarried(Player p, String kind) {
        int n = 0;
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            String k = carryKind(it);
            boolean spirit = kind == null && it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(SPIRIT, PersistentDataType.BYTE);
            if ((k != null && (kind == null || k.equals(kind))) || spirit) { inv.setItem(i, null); n++; }
        }
        ItemStack cursor = p.getItemOnCursor();
        if (kind == null && carryKind(cursor) != null) p.setItemOnCursor(null);
        return n;
    }

    public interface BlockClick { boolean click(Player p, Block b, Action a); }
    public interface ArrowHit { void hit(Player shooter, Entity victim, Block block); }

    private record EntityHook(String runId, BiConsumer<Player, Entity> click) {}
    private record BlockHook(String runId, BlockClick click) {}

    private static final Map<UUID, EntityHook> ENTITIES = new HashMap<>();
    private static final Map<String, BlockHook> BLOCKS = new HashMap<>();
    private static final Map<String, ArrowHit> ARROWS = new HashMap<>();

    private static Hooks listener;

    public static void init() {
        if (listener != null) return;
        listener = new Hooks();
        org.bukkit.Bukkit.getPluginManager().registerEvents(listener, Sky.plugin());
    }

    static String key(Block b) { return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    public static void onEntity(BossContext c, Entity e, BiConsumer<Player, Entity> click) { ENTITIES.put(e.getUniqueId(), new EntityHook(c.runId(), click)); }

    public static void onBlock(BossContext c, Block b, BlockClick click) { BLOCKS.put(key(b), new BlockHook(c.runId(), click)); }

    public static void unBlock(Block b) { BLOCKS.remove(key(b)); }

    /** Arrow hits (and punches on registered blocks) in this run go to {@code h}. */
    public static void onArrows(BossContext c, ArrowHit h) { ARROWS.put(c.runId(), h); }

    public static void clear(String runId) {
        ENTITIES.values().removeIf(h -> h.runId.equals(runId));
        BLOCKS.values().removeIf(h -> h.runId.equals(runId));
        ARROWS.remove(runId);
    }

    // ---- spawning helpers ------------------------------------------------------------------------------------

    /** A floating item with a click box under it (crystals, relics). Both are props of the run. */
    public static Interaction clickableItem(BossContext c, Location at, ItemStack shown, String label, float size, BiConsumer<Player, Entity> click) {
        ItemDisplay d = at.getWorld().spawn(at.clone().add(0, 0.9, 0), ItemDisplay.class, it -> {
            it.setItemStack(shown);
            it.setPersistent(false);
            it.setBillboard(Display.Billboard.VERTICAL);
            it.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(size, size, size), new AxisAngle4f()));
            it.setGlowing(true);
        });
        c.addProp(d);
        TextDisplay t = label == null ? null : at.getWorld().spawn(at.clone().add(0, 1.9, 0), TextDisplay.class, it -> {
            it.text(com.skyclone.api.ui.Text.c(label));
            it.setBillboard(Display.Billboard.CENTER);
            it.setPersistent(false);
        });
        if (t != null) c.addProp(t);
        Interaction box = at.getWorld().spawn(at, Interaction.class, it -> {
            it.setInteractionWidth(1.2f);
            it.setInteractionHeight(1.8f);
            it.setResponsive(true);
            it.setPersistent(false);
        });
        c.addProp(box);
        onEntity(c, box, click);
        return box;
    }

    public static TextDisplay label(BossContext c, Location at, String text) {
        TextDisplay t = at.getWorld().spawn(at, TextDisplay.class, it -> {
            it.text(com.skyclone.api.ui.Text.c(text));
            it.setBillboard(Display.Billboard.CENTER);
            it.setPersistent(false);
        });
        c.addProp(t);
        return t;
    }

    public static void relabel(TextDisplay t, String text) {
        if (t != null && t.isValid()) t.text(com.skyclone.api.ui.Text.c(text));
    }

    // ---- events ---------------------------------------------------------------------------------------------

    private static boolean live(String runId) { return Kit.live(runId); }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityClick(PlayerInteractEntityEvent e) {
        EntityHook h = ENTITIES.get(e.getRightClicked().getUniqueId());
        if (h == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND || !live(h.runId)) return;
        DungeonRun r = RunManager.get().runOf(e.getPlayer());
        if (r == null || !r.id.equals(h.runId) || r.isGhost(e.getPlayer())) return;
        h.click.accept(e.getPlayer(), e.getRightClicked());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Action a = e.getAction();
        if (a == Action.LEFT_CLICK_AIR) {
            // punching a device target from range (so non-archers can do the target device)
            DungeonRun r = RunManager.get().runOf(p);
            if (r == null || r.state != DungeonRun.State.BOSS || r.isGhost(p)) return;
            RayTraceResult hit = p.getWorld().rayTraceBlocks(p.getEyeLocation(), p.getEyeLocation().getDirection(), 18);
            if (hit != null && hit.getHitBlock() != null) {
                BlockHook h = BLOCKS.get(key(hit.getHitBlock()));
                if (h != null && h.runId.equals(r.id) && live(h.runId)) h.click.click(p, hit.getHitBlock(), a);
            }
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null) return;
        BlockHook h = BLOCKS.get(key(b));
        if (h == null) return;
        e.setCancelled(true);
        if (!live(h.runId)) return;
        DungeonRun r = RunManager.get().runOf(p);
        if (r == null || !r.id.equals(h.runId) || r.isGhost(p)) return;
        h.click.click(p, b, a);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(e.getProjectile() instanceof Projectile pr)) return;
        ItemStack bow = e.getBow();
        if (bow != null && bow.hasItemMeta() && bow.getItemMeta().getPersistentDataContainer().has(SPIRIT, PersistentDataType.BYTE)) {
            pr.getPersistentDataContainer().set(SPIRIT, PersistentDataType.BYTE, (byte) 1);
            BramblemawFight.bowFired(p, bow);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArrowHit(ProjectileHitEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        DungeonRun r = RunManager.get().runOf(p);
        if (r == null || r.state != DungeonRun.State.BOSS) return;
        if (e.getHitBlock() != null) {
            BlockHook h = BLOCKS.get(key(e.getHitBlock()));
            if (h != null && h.runId.equals(r.id)) h.click.click(p, e.getHitBlock(), Action.LEFT_CLICK_BLOCK);
        }
        ArrowHit ah = ARROWS.get(r.id);
        if (ah != null) {
            boolean spirit = e.getEntity().getPersistentDataContainer().has(SPIRIT, PersistentDataType.BYTE);
            if (spirit || e.getHitEntity() != null) {
                if (spirit && e.getHitEntity() != null) {
                    e.setCancelled(true);
                    e.getEntity().remove();
                }
                if (spirit) ah.hit(p, e.getHitEntity(), e.getHitBlock());
            }
        }
        if (e.getEntity() instanceof Arrow arr && arr.getPersistentDataContainer().has(SPIRIT, PersistentDataType.BYTE) && e.getHitEntity() == null)
            arr.remove();
    }

    /** Run-only carried items (crystals, relics, the Spirit Bow) never leave the inventory they were given to. */
    @EventHandler(priority = EventPriority.LOW)
    public void onDropCarried(org.bukkit.event.player.PlayerDropItemEvent e) {
        ItemStack it = e.getItemDrop().getItemStack();
        boolean spirit = it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(SPIRIT, PersistentDataType.BYTE);
        if (carryKind(it) == null && !spirit) return;
        e.setCancelled(true);
        com.skyclone.api.ui.Msg.error(e.getPlayer(), "You cannot drop that - it belongs to the fight.");
    }

    /** ...nor go into another inventory (a chest, a backpack menu): only the player's own slots. */
    @EventHandler(priority = EventPriority.LOW)
    public void onMoveCarried(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) return;
        ItemStack cur = e.getCurrentItem(), cursor = e.getCursor();
        ItemStack hot = e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY && e.getHotbarButton() >= 0 ? p.getInventory().getItem(e.getHotbarButton()) : null;
        if (carryKind(cur) != null || carryKind(cursor) != null || carryKind(hot) != null) e.setCancelled(true);
    }

    /** Drop dead references (entities removed with their run's props). Called rarely. */
    static void sweep() {
        for (Iterator<Map.Entry<UUID, EntityHook>> it = ENTITIES.entrySet().iterator(); it.hasNext(); )
            if (!live(it.next().getValue().runId)) it.remove();
    }
}
