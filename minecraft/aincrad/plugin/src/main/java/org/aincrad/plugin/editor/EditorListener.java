package org.aincrad.plugin.editor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;

import java.util.Random;

/**
 * Turns rod clicks into selections and brush strokes, and draws the selection so you can see it.
 *
 * <p><b>The outline is the point.</b> The single most confusing thing about a region editor is that the
 * region is invisible: you type a command and something happens somewhere, and if the box was not what
 * you thought, the only way to find out is by the damage. Drawing the box continuously - corners marked
 * differently from edges - removes almost all of that guesswork, so it is on by default rather than
 * being a debug toggle.</p>
 */
public final class EditorListener implements Listener {

    /** Particles are spaced along edges rather than drawn per block, so a 500-block box stays cheap. */
    private static final double EDGE_SPACING = 1.0;
    private static final int MAX_PARTICLES_PER_PLAYER = 900;
    private static final double VIEW_DISTANCE = 110.0;

    private final Editor editor;
    private final Random random = new Random();

    public EditorListener(Editor editor) {
        this.editor = editor;
    }

    /** Starts the outline renderer. Every four ticks is smooth enough and cheap. */
    public void startOutlineRenderer(Plugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, this::drawOutlines, 20L, 4L);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!editor.isWand(event.getItem())) {
            return;
        }
        Action action = event.getAction();
        boolean left = action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR;
        boolean right = action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR;
        if (!left && !right) {
            return;
        }
        // Cancel first: in creative a left-click would otherwise break the block instantly, and a
        // right-click with an axe would strip a log.
        event.setCancelled(true);

        Block target = event.getClickedBlock();
        if (target == null) {
            RayTraceResult hit = player.rayTraceBlocks(Editor.AIR_CLICK_RANGE);
            if (hit == null || hit.getHitBlock() == null) {
                Editor.tell(player, "<gray>Nothing within " + Editor.AIR_CLICK_RANGE
                        + " blocks that way.");
                return;
            }
            target = hit.getHitBlock();
        }

        Editor.Brush brush = editor.brush(player);
        if (right && brush != null) {
            applyBrush(player, brush, target);
            return;
        }

        Selection sel = editor.selection(player);
        if (left) {
            sel.setCorner1(target.getX(), target.getY(), target.getZ());
        } else {
            sel.setCorner2(target.getX(), target.getY(), target.getZ());
        }
        String which = left ? "Corner 1" : "Corner 2";
        if (sel.isComplete()) {
            Editor.tell(player, which + " at " + target.getX() + ", " + target.getY() + ", " + target.getZ()
                    + "  <white>" + sel.width() + "x" + sel.height() + "x" + sel.length() + "</white> = "
                    + Editor.plural(sel.volume(), "block"));
        } else {
            Editor.tell(player, which + " at " + target.getX() + ", " + target.getY() + ", " + target.getZ()
                    + ". <gray>Now " + (left ? "right" : "left") + "-click the opposite corner.");
        }
    }

    private void applyBrush(Player player, Editor.Brush brush, Block centre) {
        int size = brush.size();
        int cx = centre.getX();
        int cy = centre.getY();
        int cz = centre.getZ();
        long estimate = (long) (size * 2 + 1) * (size * 2 + 1) * (size * 2 + 1);
        World world = player.getWorld();

        Shapes.Test test = switch (brush.kind()) {
            case "cube" -> Shapes.ALL;
            case "paint" -> exposedSphere(cx, cy, cz, size, world);
            default -> Shapes.ellipsoid(cx, cy, cz, size, size, size, false);
        };
        editor.queue().submit(player, world, "brush " + brush.kind(),
                Shapes.region(cx - size, cy - size, cz - size, cx + size, cy + size, cz + size,
                        test, Shapes.pattern(brush.pattern(), random), brush.mask(), estimate),
                false);
    }

    /**
     * A sphere restricted to blocks touching air, so "paint" re-skins a surface instead of filling the
     * volume behind it. Reading neighbours here is safe: the brush centre is a block the player just
     * looked at, so its chunk and neighbours are loaded.
     */
    private static Shapes.Test exposedSphere(int cx, int cy, int cz, int size, World world) {
        Shapes.Test sphere = Shapes.ellipsoid(cx, cy, cz, size, size, size, false);
        return (x, y, z) -> {
            if (!sphere.inside(x, y, z)) {
                return false;
            }
            if (world.getBlockAt(x, y, z).getType().isAir()) {
                return false;
            }
            return world.getBlockAt(x + 1, y, z).getType().isAir()
                    || world.getBlockAt(x - 1, y, z).getType().isAir()
                    || world.getBlockAt(x, y + 1, z).getType().isAir()
                    || world.getBlockAt(x, y - 1, z).getType().isAir()
                    || world.getBlockAt(x, y, z + 1).getType().isAir()
                    || world.getBlockAt(x, y, z - 1).getType().isAir();
        };
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Selections and clipboards are cheap and worth keeping across a relog; undo history is not,
        // and holding it for someone who left would pin hundreds of megabytes indefinitely.
        editor.history().clear(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ outline rendering

    private void drawOutlines() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!editor.outlineEnabled(player)) {
                continue;
            }
            Selection sel = editor.selection(player);
            if (!sel.isComplete() || !sel.world().equals(player.getWorld())) {
                if (sel.hasCorner1() && sel.world().equals(player.getWorld())) {
                    mark(player, sel.corner1X(), sel.corner1Y(), sel.corner1Z());
                }
                if (sel.hasCorner2() && sel.world().equals(player.getWorld())) {
                    mark(player, sel.corner2X(), sel.corner2Y(), sel.corner2Z());
                }
                continue;
            }
            drawBox(player, sel);
        }
    }

    /** A lone corner gets a bright cross so a half-finished selection is still visible. */
    private void mark(Player player, int x, int y, int z) {
        Location loc = new Location(player.getWorld(), x + 0.5, y + 0.5, z + 0.5);
        if (loc.distanceSquared(player.getLocation()) > VIEW_DISTANCE * VIEW_DISTANCE) {
            return;
        }
        player.spawnParticle(Particle.END_ROD, loc, 4, 0.35, 0.35, 0.35, 0);
    }

    private void drawBox(Player player, Selection sel) {
        double x1 = sel.minX();
        double y1 = sel.minY();
        double z1 = sel.minZ();
        double x2 = sel.maxX() + 1.0;
        double y2 = sel.maxY() + 1.0;
        double z2 = sel.maxZ() + 1.0;

        Location eye = player.getLocation();
        // Skip entirely when the box is far away; particles would not be visible anyway and a huge
        // selection would otherwise cost a packet storm for nothing.
        double nearestX = Math.max(x1, Math.min(eye.getX(), x2));
        double nearestY = Math.max(y1, Math.min(eye.getY(), y2));
        double nearestZ = Math.max(z1, Math.min(eye.getZ(), z2));
        double dx = nearestX - eye.getX();
        double dy = nearestY - eye.getY();
        double dz = nearestZ - eye.getZ();
        if (dx * dx + dy * dy + dz * dz > VIEW_DISTANCE * VIEW_DISTANCE) {
            return;
        }

        double perimeter = 4 * ((x2 - x1) + (y2 - y1) + (z2 - z1));
        double spacing = Math.max(EDGE_SPACING, perimeter / MAX_PARTICLES_PER_PLAYER);
        World world = sel.world();

        for (double x = x1; x <= x2; x += spacing) {
            edge(player, world, x, y1, z1);
            edge(player, world, x, y1, z2);
            edge(player, world, x, y2, z1);
            edge(player, world, x, y2, z2);
        }
        for (double y = y1; y <= y2; y += spacing) {
            edge(player, world, x1, y, z1);
            edge(player, world, x1, y, z2);
            edge(player, world, x2, y, z1);
            edge(player, world, x2, y, z2);
        }
        for (double z = z1; z <= z2; z += spacing) {
            edge(player, world, x1, y1, z);
            edge(player, world, x1, y2, z);
            edge(player, world, x2, y1, z);
            edge(player, world, x2, y2, z);
        }
    }

    private void edge(Player player, World world, double x, double y, double z) {
        player.spawnParticle(Particle.END_ROD, new Location(world, x, y, z), 1, 0, 0, 0, 0);
    }
}
