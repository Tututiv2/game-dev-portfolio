package org.aincrad.plugin.editor;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Every editor command, dispatched by name.
 *
 * <p>Commands are reachable two ways: as <code>//set stone</code> and as <code>/aed set stone</code>.
 * The double-slash form is the one worth muscle memory, but it relies on registering commands whose
 * name literally begins with a slash, so the plain <code>/aed</code> form exists as a guaranteed path
 * to the same code. Both land in {@link #onCommand} and share one implementation per verb - there is no
 * second copy that could drift.</p>
 */
public final class EditorCommands implements CommandExecutor, TabCompleter {

    private final Editor editor;
    private final Random random = new Random();
    private WorldImporter importer;

    /** Verbs, in the order they appear in help. */
    public static final List<String> VERBS = List.of(
            "help", "guide", "wand", "pos1", "pos2", "size", "desel", "expand", "contract", "shift",
            "set", "replace", "walls", "shell", "hollow", "count", "distr",
            "copy", "cut", "paste", "rotate", "flip", "stack", "move",
            "sphere", "hsphere", "cyl", "hcyl", "pyramid", "hpyramid",
            "undo", "redo", "brush", "outline", "perf", "cancel",
            "up", "down", "jumpto", "thru", "ceil", "import", "schem", "island", "apron");

    public EditorCommands(Editor editor) {
        this.editor = editor;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        String verb;
        String[] rest;
        if (name.equals("aed")) {
            if (args.length == 0) {
                EditorHelp.overview(sender);
                return true;
            }
            verb = args[0].toLowerCase(Locale.ROOT);
            rest = Arrays.copyOfRange(args, 1, args.length);
        } else {
            verb = name;
            rest = args;
        }

        if (verb.equals("help")) {
            EditorHelp.dispatch(sender, rest);
            return true;
        }
        if (verb.equals("scan")) {
            // Read-only, so unlike //import it needs no player and can run from the console. That also
            // makes it the thing to point a verification harness at.
            scanOnly(sender, args.length > (name.equals("aed") ? 1 : 0)
                    ? (name.equals("aed") ? args[1] : args[0]) : null);
            return true;
        }
        if (verb.equals("docs")) {
            // Writes a file rather than editing the world, so unlike every other verb it does not need
            // a player - which also means the reference can be generated from the console.
            writeReference(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Editor commands need a player - they act on where you are standing.");
            return true;
        }
        try {
            handle(player, verb, rest);
        } catch (BlockPattern.PatternException ex) {
            Editor.tell(player, "<red>" + ex.getMessage());
        } catch (NumberFormatException ex) {
            Editor.tell(player, "<red>That needed to be a number.</red> Try "
                    + Editor.runnable("//help " + verb, "//help " + verb) + " for an example.");
        }
        return true;
    }

    /** Regenerates docs/WORLD-EDITOR-REFERENCE.md from the help registry. */
    private void writeReference(CommandSender to) {
        if (!to.isOp() && to instanceof Player) {
            Editor.tell(to, "<red>That one is operator-only.");
            return;
        }
        try {
            java.nio.file.Path target = editor.plugin().getDataFolder().toPath()
                    .toAbsolutePath().getParent().getParent()
                    .resolveSibling("docs").resolve("WORLD-EDITOR-REFERENCE.md").normalize();
            EditorHelp.writeMarkdown(target);
            Editor.tell(to, "Wrote the command reference to <white>" + target + "</white>");
            Editor.tell(to, "<gray>Generated from the same list that feeds //help and the book, "
                    + "so the three cannot disagree.");
        } catch (java.io.IOException ex) {
            Editor.tell(to, "<red>Could not write the reference: " + ex.getMessage());
        }
    }

    private void handle(Player p, String verb, String[] a) throws BlockPattern.PatternException {
        switch (verb) {
            case "guide" -> EditorHelp.giveBook(p);
            case "wand" -> {
                p.getInventory().addItem(editor.createWand());
                Editor.tell(p, "Selection rod added to your inventory.");
                Editor.tell(p, "<gray>Left-click one corner, right-click the opposite one. "
                        + "Clicking air reaches " + Editor.AIR_CLICK_RANGE + " blocks.");
            }
            case "pos1", "pos2" -> {
                Location loc = p.getLocation();
                Selection sel = editor.selection(p);
                if (verb.equals("pos1")) {
                    sel.setCorner1(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                } else {
                    sel.setCorner2(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                }
                describeSelection(p, sel, "Corner " + verb.charAt(3) + " set to where you are standing");
            }
            case "size" -> {
                Selection sel = editor.selection(p);
                if (!sel.isComplete()) {
                    editor.requireSelection(p);
                    return;
                }
                Editor.tell(p, "Selection: <white>" + sel.width() + " wide, " + sel.height()
                        + " tall, " + sel.length() + " long</white>  (" + Editor.plural(sel.volume(), "block") + ")");
                Editor.tell(p, "<gray>From " + sel.minX() + ", " + sel.minY() + ", " + sel.minZ()
                        + "  to  " + sel.maxX() + ", " + sel.maxY() + ", " + sel.maxZ());
                Editor.tell(p, "<gray>World: " + sel.world().getName());
            }
            case "desel" -> {
                editor.clearSelection(p);
                Editor.tell(p, "Selection cleared.");
            }
            case "expand", "contract", "shift" -> adjust(p, verb, a);
            case "set" -> {
                Selection sel = editor.requireSelection(p);
                if (sel == null) {
                    return;
                }
                BlockPattern pattern = BlockPattern.parse(join(a, "You need to say what to fill it with. "
                        + "Example: //set stone"));
                submit(p, sel, "set " + pattern.describe(), Shapes.ALL, pattern, BlockMask.any(), sel.volume());
            }
            case "replace" -> {
                Selection sel = editor.requireSelection(p);
                if (sel == null) {
                    return;
                }
                if (a.length < 2) {
                    Editor.tell(p, "<red>//replace needs two things: what to look for, and what to put there.");
                    Editor.tell(p, "Example: " + Editor.runnable("//replace stone air", "//replace stone air"));
                    Editor.tell(p, "<gray>Or a whole group: <white>//replace !air stone</white> fills every "
                            + "solid block, leaving the gaps alone.");
                    return;
                }
                BlockMask mask = BlockMask.parse(a[0]);
                BlockPattern pattern = BlockPattern.parse(joinFrom(a, 1));
                submit(p, sel, "replace " + mask.describe() + " with " + pattern.describe(),
                        Shapes.ALL, pattern, mask, sel.volume());
            }
            case "walls", "shell" -> {
                Selection sel = editor.requireSelection(p);
                if (sel == null) {
                    return;
                }
                BlockPattern pattern = BlockPattern.parse(join(a,
                        "Say what to build it from. Example: //" + verb + " stone_bricks"));
                Shapes.Test test = verb.equals("walls")
                        ? Shapes.walls(sel.minX(), sel.minZ(), sel.maxX(), sel.maxZ())
                        : Shapes.shell(sel.minX(), sel.minY(), sel.minZ(), sel.maxX(), sel.maxY(), sel.maxZ());
                submit(p, sel, verb + " " + pattern.describe(), test, pattern, BlockMask.any(), sel.volume());
            }
            case "hollow" -> {
                Selection sel = editor.requireSelection(p);
                if (sel == null) {
                    return;
                }
                BlockPattern pattern = a.length == 0 ? BlockPattern.parse("air") : BlockPattern.parse(join(a, ""));
                int minX = sel.minX() + 1, minY = sel.minY() + 1, minZ = sel.minZ() + 1;
                int maxX = sel.maxX() - 1, maxY = sel.maxY() - 1, maxZ = sel.maxZ() - 1;
                if (minX > maxX || minY > maxY || minZ > maxZ) {
                    Editor.tell(p, "<red>That selection has no inside</red> - it needs to be at least "
                            + "3 blocks in every direction.");
                    return;
                }
                long vol = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
                editor.queue().submit(p, sel.world(), "hollow out with " + pattern.describe(),
                        Shapes.region(minX, minY, minZ, maxX, maxY, maxZ, Shapes.ALL,
                                Shapes.pattern(pattern, random), BlockMask.any(), vol), false);
                Editor.tell(p, "Emptying the inside, leaving a one-block skin.");
            }
            case "count" -> {
                Selection sel = editor.requireSelection(p);
                if (sel == null) {
                    return;
                }
                BlockMask mask = BlockMask.parse(join(a, "Say what to count. Example: //count stone"));
                countBlocks(p, sel, mask);
            }
            case "distr" -> {
                Selection sel = editor.requireSelection(p);
                if (sel != null) {
                    distribution(p, sel);
                }
            }
            case "copy", "cut" -> {
                Selection sel = editor.requireSelection(p);
                if (sel == null) {
                    return;
                }
                if (sel.volume() > 4_000_000) {
                    Editor.tell(p, "<red>That is " + Editor.format(sel.volume())
                            + " blocks, which is too much to hold in a clipboard.</red>");
                    Editor.tell(p, "<gray>The limit is 4,000,000. Copy it in pieces, or use //stack "
                            + "which does not need a clipboard.");
                    return;
                }
                boolean cut = verb.equals("cut");
                editor.ensureChunksThen(sel.world(), sel.minX(), sel.minZ(), sel.maxX(), sel.maxZ(), p, loaded -> {
                    Clipboard clip = editor.copyRegion(sel, p);
                    editor.setClipboard(p, clip);
                    Editor.tell(p, (cut ? "Cut" : "Copied") + " <white>" + clip.width() + "x"
                            + clip.height() + "x" + clip.length() + "</white> ("
                            + Editor.plural(clip.solidCount(), "solid block") + ").");
                    Editor.tell(p, "<gray>It keeps its position relative to you, so //paste puts it back "
                            + "the same way round.");
                    if (cut) {
                        try {
                            BlockPattern air = BlockPattern.parse("air");
                            submit(p, sel, "clear the cut area", Shapes.ALL, air, BlockMask.any(), sel.volume());
                        } catch (BlockPattern.PatternException ignored) {
                            // "air" is a constant; this cannot fail.
                        }
                    }
                });
            }
            case "paste" -> {
                Clipboard clip = editor.clipboard(p);
                if (clip == null) {
                    Editor.tell(p, "<red>Your clipboard is empty.</red> Select something and "
                            + Editor.runnable("//copy", "//copy") + " it first.");
                    return;
                }
                boolean withAir = Arrays.stream(a).anyMatch(s -> s.equalsIgnoreCase("air"));
                int ox = p.getLocation().getBlockX() + clip.offsetX();
                int oy = p.getLocation().getBlockY() + clip.offsetY();
                int oz = p.getLocation().getBlockZ() + clip.offsetZ();
                int maxX = ox + clip.width() - 1;
                int maxZ = oz + clip.length() - 1;
                World world = p.getWorld();
                editor.queue().submit(p, world,
                        "paste " + clip.width() + "x" + clip.height() + "x" + clip.length()
                                + (withAir ? " including air" : ""),
                        Shapes.region(ox, oy, oz, maxX, oy + clip.height() - 1, maxZ, Shapes.ALL,
                                Shapes.clipboard(clip, ox, oy, oz, withAir), BlockMask.any(), clip.volume()),
                        false);
                Editor.tell(p, "Pasting at " + ox + ", " + oy + ", " + oz
                        + (withAir ? " <gray>(air included, so it erases)" : " <gray>(air skipped, so it stamps)"));
            }
            case "rotate" -> {
                Clipboard clip = editor.clipboard(p);
                if (clip == null) {
                    Editor.tell(p, "<red>Nothing in the clipboard to rotate.</red> //copy first.");
                    return;
                }
                int deg = a.length == 0 ? 90 : Integer.parseInt(a[0]);
                if (deg % 90 != 0) {
                    Editor.tell(p, "<red>Rotation has to be a multiple of 90</red> - anything else would "
                            + "turn straight walls into staircases.");
                    return;
                }
                editor.setClipboard(p, clip.rotated(deg));
                Editor.tell(p, "Clipboard rotated " + deg + " degrees. Stairs, doors and logs turned with it.");
            }
            case "flip" -> {
                Clipboard clip = editor.clipboard(p);
                if (clip == null) {
                    Editor.tell(p, "<red>Nothing in the clipboard to flip.</red> //copy first.");
                    return;
                }
                String axis = a.length == 0 ? facingAxis(p) : a[0].toLowerCase(Locale.ROOT);
                if (!axis.equals("x") && !axis.equals("y") && !axis.equals("z")) {
                    Editor.tell(p, "<red>Axis must be x, y or z.</red> With no axis it mirrors the way "
                            + "you are facing.");
                    return;
                }
                editor.setClipboard(p, clip.flipped(axis));
                Editor.tell(p, "Clipboard mirrored along " + axis + ".");
            }
            case "stack" -> stack(p, a);
            case "move" -> move(p, a);
            case "sphere", "hsphere" -> sphere(p, a, verb.startsWith("h"));
            case "cyl", "hcyl" -> cylinder(p, a, verb.startsWith("h"));
            case "pyramid", "hpyramid" -> pyramid(p, a, verb.startsWith("h"));
            case "up", "down" -> {
                int n = a.length == 0 ? 1 : Integer.parseInt(a[0]);
                if (n < 1) {
                    Editor.tell(p, "<red>How many blocks? Example: " + "//" + verb + " 10");
                    return;
                }
                glideVertically(p, verb.equals("up") ? n : -n);
            }
            case "jumpto" -> {
                var hit = p.rayTraceBlocks(320);
                if (hit == null || hit.getHitBlock() == null) {
                    Editor.tell(p, "<gray>Nothing within 320 blocks that way to jump to.");
                    return;
                }
                Location target = hit.getHitBlock().getLocation().add(0.5, 1.0, 0.5);
                target.setYaw(p.getLocation().getYaw());
                target.setPitch(p.getLocation().getPitch());
                p.teleport(target);
                Editor.tell(p, "Jumped to " + target.getBlockX() + ", " + target.getBlockY()
                        + ", " + target.getBlockZ() + ".");
            }
            case "thru" -> passThrough(p);
            case "ceil" -> {
                int gap = a.length == 0 ? 0 : Integer.parseInt(a[0]);
                World w = p.getWorld();
                int x = p.getLocation().getBlockX();
                int z = p.getLocation().getBlockZ();
                int from = p.getLocation().getBlockY();
                int found = -1;
                for (int y = from + 2; y <= w.getMaxHeight() - 2; y++) {
                    if (!w.getBlockAt(x, y, z).getType().isAir()
                            && w.getBlockAt(x, y + 1, z).getType().isAir()
                            && w.getBlockAt(x, y + 2, z).getType().isAir()) {
                        found = y + 1 + gap;
                        break;
                    }
                }
                if (found < 0) {
                    Editor.tell(p, "<gray>No ceiling above you - you are already under open sky.");
                    return;
                }
                standOn(p, found);
                Editor.tell(p, "Up on the ceiling at Y=" + found + ".");
            }
            case "island" -> {
                Selection sel = editor.selection(p);
                int thickness = a.length >= 1 ? Integer.parseInt(a[0]) : IslandCarve.DEFAULT_THICKNESS;
                int minX;
                int minZ;
                int maxX;
                int maxZ;
                if (sel.isComplete()) {
                    minX = sel.minX();
                    minZ = sel.minZ();
                    maxX = sel.maxX();
                    maxZ = sel.maxZ();
                } else {
                    if (a.length < 2) {
                        // Never guess a radius. "//island 50" reads as "radius 50" to anyone sane, and
                        // silently carving 800 blocks instead deleted 117 million blocks of somebody's
                        // world. If there is no selection, the radius must be said out loud.
                        Editor.tell(p, "<red>//island needs to know how far.</red>");
                        Editor.tell(p, "<white>//island " + (a.length >= 1 ? a[0] : "90")
                                + " 150</white>  <gray>= keep " + (a.length >= 1 ? a[0] : "90")
                                + " blocks of rock, out to 150 blocks around you.");
                        Editor.tell(p, "<gray>Or select an area with the rod first and just type "
                                + "<white>//island</white> - then it follows your selection exactly.");
                        return;
                    }
                    int r = Integer.parseInt(a[1]);
                    minX = p.getLocation().getBlockX() - r;
                    maxX = p.getLocation().getBlockX() + r;
                    minZ = p.getLocation().getBlockZ() - r;
                    maxZ = p.getLocation().getBlockZ() + r;
                    Editor.tell(p, "<gray>No selection, so carving " + r
                            + " blocks around you. Select an area first to be exact.");
                }
                IslandCarve.start(editor.plugin(), editor, p, p.getWorld(),
                        minX, minZ, maxX, maxZ, thickness);
            }
            case "apron" -> apron(p, a);
            case "schem", "schematic" -> schematic(p, a);
            case "import" -> importWorld(p, a);
            case "undo", "redo" -> undoRedo(p, verb, a);
            case "brush" -> brush(p, a);
            case "outline" -> {
                boolean on = editor.toggleOutline(p);
                Editor.tell(p, "Selection outline " + (on ? "<green>on" : "<gray>off"));
            }
            case "perf" -> {
                if (a.length == 0) {
                    Editor.tell(p, "Currently applying <white>" + Editor.format(editor.queue().blocksPerTick())
                            + "</white> blocks per tick (" + Editor.format(editor.queue().blocksPerTick() * 20L)
                            + " per second).");
                    Editor.tell(p, "<gray>Raise it to go faster at the cost of server smoothness: "
                            + "<white>//perf 30000");
                    return;
                }
                editor.queue().setBlocksPerTick(Integer.parseInt(a[0]));
                Editor.tell(p, "Now applying <white>" + Editor.format(editor.queue().blocksPerTick())
                        + "</white> blocks per tick.");
            }
            case "cancel" -> {
                int n = editor.queue().cancelAll();
                Editor.tell(p, n == 0 ? "Nothing was running." : "Cancelled " + n + " operation(s). "
                        + "What had already been written is still undoable.");
            }
            default -> {
                Editor.tell(p, "<red>There is no //" + verb + " command.</red>");
                Editor.tell(p, "Try " + Editor.instant("//help", "//help") + " - it is a menu, not a wall of text.");
            }
        }
    }

    // ------------------------------------------------------------------ selection adjustment

    /**
     * Moves the player vertically and puts a block under their feet so they do not fall back.
     *
     * <p>This is what makes //up usable rather than a novelty: teleporting into open air just means
     * falling, so the block is the whole point. It only places one when the destination is empty, so
     * repeated //up inside a building does not perforate every floor on the way through.</p>
     */
    private void glideVertically(Player p, int delta) {
        World world = p.getWorld();
        Location from = p.getLocation();
        int x = from.getBlockX();
        int z = from.getBlockZ();
        int targetY = from.getBlockY() + delta;
        int lo = world.getMinHeight() + 1;
        int hi = world.getMaxHeight() - 2;
        if (targetY < lo || targetY > hi) {
            Editor.tell(p, "<red>That would leave the world.</red> <gray>This world runs from Y="
                    + world.getMinHeight() + " to Y=" + (world.getMaxHeight() - 1) + ".");
            return;
        }
        var floor = world.getBlockAt(x, targetY - 1, z);
        boolean placed = false;
        if (floor.getType().isAir()) {
            BlockSnapshot undo = new BlockSnapshot(1);
            undo.add(x, targetY - 1, z, floor.getBlockData());
            floor.setType(Material.GLASS, false);
            editor.history().pushUndo(p.getUniqueId(),
                    new EditHistory.Entry("glass step from //" + (delta > 0 ? "up" : "down"),
                            world.getName(), undo));
            placed = true;
        }
        standOn(p, targetY);
        Editor.tell(p, (delta > 0 ? "Up " : "Down ") + Math.abs(delta) + " to Y=" + targetY
                + (placed ? " <gray>(glass step placed, //undo removes it)" : ""));
    }

    /** Steps through the wall or floor you are facing, landing in the first open space beyond it. */
    private void passThrough(Player p) {
        World world = p.getWorld();
        Location eye = p.getEyeLocation();
        var direction = eye.getDirection();
        boolean insideSolid = false;
        for (int step = 1; step <= 40; step++) {
            int x = (int) Math.floor(eye.getX() + direction.getX() * step);
            int y = (int) Math.floor(eye.getY() + direction.getY() * step);
            int z = (int) Math.floor(eye.getZ() + direction.getZ() * step);
            boolean solid = !world.getBlockAt(x, y, z).getType().isAir();
            if (solid) {
                insideSolid = true;
                continue;
            }
            if (insideSolid && world.getBlockAt(x, y + 1, z).getType().isAir()) {
                Location target = new Location(world, x + 0.5, y, z + 0.5,
                        p.getLocation().getYaw(), p.getLocation().getPitch());
                p.teleport(target);
                Editor.tell(p, "Through to " + x + ", " + y + ", " + z + ".");
                return;
            }
        }
        Editor.tell(p, "<gray>No open space within 40 blocks through whatever you are facing.");
    }

    private void standOn(Player p, int y) {
        Location from = p.getLocation();
        Location target = new Location(p.getWorld(), from.getBlockX() + 0.5, y,
                from.getBlockZ() + 0.5, from.getYaw(), from.getPitch());
        p.teleport(target);
    }

    /**
     * Imports a world saved by another Minecraft version.
     *
     * <p>Scans by default and writes only on {@code confirm}: the downloaded Floor One is 7,217 chunks,
     * and finding out a third of the way through that a block name no longer exists would be a bad way
     * to discover it. The scan answers that question first, for free.</p>
     */
    /** Reports what is inside an importable save without writing anything. Console-safe. */
    private void scanOnly(CommandSender to, String name) {
        if (importer == null) {
            importer = new WorldImporter(editor.plugin(), editor);
        }
        java.io.File root = importer.importsRoot();
        if (name == null) {
            Editor.tell(to, "Usage: <white>/aed scan <name></white>  <gray>- looks in " + root.getAbsolutePath());
            java.io.File[] found = root.listFiles(java.io.File::isDirectory);
            if (found != null) {
                for (java.io.File f : found) {
                    Editor.raw(to, "  <white>" + f.getName());
                }
            }
            return;
        }
        java.io.File base = new java.io.File(root, name);
        java.io.File regionDir = base.isDirectory() ? importer.findRegionDir(base) : null;
        if (regionDir == null) {
            Editor.tell(to, "<red>No region folder found for '" + name + "' under "
                    + root.getAbsolutePath());
            return;
        }
        Editor.tell(to, "Scanning " + name + "...");
        importer.scan(to, regionDir, scan -> importer.report(to, scan, name));
    }

    /** Where saved parts live. One folder, so a style library is just a subfolder of it. */
    private java.io.File schematicRoot() {
        return new java.io.File(editor.plugin().getDataFolder(), "schematics");
    }

    /**
     * Saves and loads clipboard parts.
     *
     * <p>Names may contain a folder, so <code>//schem save medieval/wall_a</code> puts that part into a
     * "medieval" library. That is the whole mechanism the build generator will read from - libraries are
     * folders, parts are files, and nothing else needs inventing.</p>
     */
    private void schematic(Player p, String[] a) {
        java.io.File root = schematicRoot();
        String action = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "save" -> {
                if (a.length < 2) {
                    Editor.tell(p, "<red>Name it.</red> Example: "
                            + Editor.runnable("//schem save myhouse", "//schem save myhouse"));
                    Editor.tell(p, "<gray>Use a folder to build a style library: "
                            + "<white>//schem save medieval/wall_a");
                    return;
                }
                Clipboard clip = editor.clipboard(p);
                if (clip == null) {
                    Editor.tell(p, "<red>Your clipboard is empty.</red> "
                            + Editor.runnable("//copy", "//copy") + " a selection first.");
                    return;
                }
                java.io.File target = SchematicIO.fileFor(root, a[1]);
                try {
                    SchematicIO.save(clip, target);
                    Editor.tell(p, "Saved <white>" + clip.width() + "x" + clip.height() + "x"
                            + clip.length() + "</white> as <white>" + a[1] + "</white> ("
                            + (target.length() / 1024) + " KB).");
                } catch (java.io.IOException ex) {
                    Editor.tell(p, "<red>Could not save: " + ex.getMessage());
                }
            }
            case "load" -> {
                if (a.length < 2) {
                    Editor.tell(p, "<red>Which one?</red> " + Editor.runnable("//schem list", "//schem list"));
                    return;
                }
                java.io.File source = SchematicIO.fileFor(root, a[1]);
                if (!source.isFile()) {
                    Editor.tell(p, "<red>No part called '" + a[1] + "'.</red> "
                            + Editor.runnable("//schem list", "//schem list"));
                    return;
                }
                try {
                    Clipboard clip = SchematicIO.load(source);
                    editor.setClipboard(p, clip);
                    Editor.tell(p, "Loaded <white>" + a[1] + "</white> ("
                            + clip.width() + "x" + clip.height() + "x" + clip.length() + "). "
                            + Editor.runnable("//paste", "//paste") + " to place it.");
                } catch (java.io.IOException ex) {
                    Editor.tell(p, "<red>Could not load: " + ex.getMessage());
                }
            }
            case "list" -> {
                String folder = a.length >= 2 ? a[1] : null;
                List<String> names = SchematicIO.list(root, folder);
                if (names.isEmpty()) {
                    Editor.tell(p, "<gray>Nothing saved" + (folder == null ? "" : " in " + folder)
                            + " yet. " + Editor.runnable("//schem save name", "//schem save name")
                            + " <gray>after a //copy.");
                    java.io.File[] dirs = root.listFiles(java.io.File::isDirectory);
                    if (dirs != null && dirs.length > 0) {
                        Editor.tell(p, "<gray>Libraries: ");
                        for (java.io.File d : dirs) {
                            Editor.raw(p, "  " + Editor.runnable(d.getName(),
                                    "//schem list " + d.getName()));
                        }
                    }
                    return;
                }
                Editor.tell(p, "Saved parts" + (folder == null ? "" : " in " + folder) + ":");
                for (String n : names) {
                    String full = folder == null ? n : folder + "/" + n;
                    Editor.raw(p, "  " + Editor.runnable(full, "//schem load " + full));
                }
            }
            case "delete" -> {
                if (a.length < 2) {
                    Editor.tell(p, "<red>Which one?");
                    return;
                }
                java.io.File target = SchematicIO.fileFor(root, a[1]);
                if (target.isFile() && target.delete()) {
                    Editor.tell(p, "Deleted <white>" + a[1] + "</white>.");
                } else {
                    Editor.tell(p, "<red>Could not delete '" + a[1] + "'.");
                }
            }
            default -> {
                Editor.tell(p, "<white>//schem save <name></white>  <gray>store the clipboard as a part");
                Editor.tell(p, "<white>//schem load <name></white>  <gray>put a part back on the clipboard");
                Editor.tell(p, "<white>//schem list [folder]</white>  <gray>see what you have");
                Editor.tell(p, "<white>//schem delete <name></white>");
                Editor.tell(p, "<gray>Folders make style libraries: <white>medieval/wall_a</white>. "
                        + "Files live in <white>plugins/AincradPlugin/schematics</white>.");
            }
        }
    }

    /**
     * Grows natural land outward from the island the player is standing on.
     *
     * <p>Two words rather than one because of the scale: a bare {@code //apron} measures and reports, and
     * only {@code //apron confirm} writes. See {@link org.aincrad.plugin.terrain.ApronGenerator}.</p>
     */
    private void apron(Player p, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("confirm")) {
            org.aincrad.plugin.terrain.ApronGenerator.confirm(editor.plugin(), editor, p);
            return;
        }
        Integer margin = a.length >= 1 ? Integer.valueOf(Integer.parseInt(a[0])) : null;
        Integer thickness = a.length >= 2 ? Integer.valueOf(Integer.parseInt(a[1])) : null;
        if (margin != null && margin < 16) {
            Editor.tell(p, "<red>A belt thinner than 16 blocks is not worth the write.</red> "
                    + "<gray>Leave the number off and it picks a width that makes the island half again "
                    + "as big.");
            return;
        }
        // The selection says which part of the land is "the island". It has to, because the imported
        // build is not separated from the leftover pregeneration slab around it - an unbounded walk over
        // connected land returns 50,489 chunks instead of the build. Same rule as //island.
        Selection sel = editor.selection(p);
        if (!sel.isComplete()) {
            Editor.tell(p, "<red>//apron needs to know which land is the island.</red>");
            Editor.tell(p, "<gray>Select the imported build with the rod - "
                    + Editor.runnable("//wand", "//wand")
                    + "<gray>, left-click one corner, right-click the far one - then run "
                    + Editor.runnable("//apron", "//apron") + "<gray> again. Only X and Z are used.");
            Editor.tell(p, "<dark_gray>It cannot be worked out automatically: the build sits inside a "
                    + "much larger slab of leftover terrain, so following the ground outward just walks "
                    + "off across the whole floor.");
            return;
        }
        org.aincrad.plugin.terrain.ApronGenerator.scan(editor.plugin(), p,
                new org.aincrad.plugin.terrain.ApronPlan.Bounds(sel.minX(), sel.minZ(),
                        sel.maxX(), sel.maxZ()),
                margin, thickness);
    }

    private void importWorld(Player p, String[] a) {
        if (importer == null) {
            importer = new WorldImporter(editor.plugin(), editor);
        }
        java.io.File root = importer.importsRoot();
        if (a.length == 0) {
            Editor.tell(p, "<red>Which world?</red> Put an extracted save in:");
            Editor.raw(p, "  <white>" + root.getAbsolutePath());
            java.io.File[] found = root.listFiles(java.io.File::isDirectory);
            if (found == null || found.length == 0) {
                Editor.tell(p, "<gray>That folder is empty or missing. Extract a save into it so you end "
                        + "up with <white>imports/NAME/region/r.0.0.mca</white>.");
            } else {
                Editor.tell(p, "<gray>Available:");
                for (java.io.File f : found) {
                    Editor.raw(p, "  " + Editor.runnable(f.getName(), "//import " + f.getName()));
                }
            }
            return;
        }
        String name = a[0];
        java.io.File base = new java.io.File(root, name);
        if (!base.isDirectory()) {
            Editor.tell(p, "<red>No folder called '" + name + "' in " + root.getAbsolutePath());
            return;
        }
        java.io.File regionDir = importer.findRegionDir(base);
        if (regionDir == null) {
            Editor.tell(p, "<red>Could not find a 'region' folder inside " + name + ".</red>");
            Editor.tell(p, "<gray>It should be at <white>" + name + "/region</white> or one level deeper.");
            return;
        }

        boolean confirm = false;
        for (String arg : a) {
            if (arg.equalsIgnoreCase("confirm")) {
                confirm = true;
            }
        }
        // Offsets default to putting the source surface onto this floor's plate: their ground sits
        // around Y=65 and ours is at Y=319.
        int dx = a.length >= 5 ? Integer.parseInt(a[2]) : 0;
        int dy = a.length >= 5 ? Integer.parseInt(a[3]) : 254;
        int dz = a.length >= 5 ? Integer.parseInt(a[4]) : 0;
        final boolean go = confirm;

        Editor.tell(p, "Scanning <white>" + name + "</white>... reading region files, writing nothing.");
        final java.io.File finalRegionDir = regionDir;
        importer.scan(p, regionDir, scan -> {
            importer.report(p, scan, name);
            String problem = importer.checkOffset(p, scan, dy);
            if (problem != null) {
                Editor.tell(p, "<red>" + problem);
                return;
            }
            Editor.raw(p, "  <gray>Would land at X " + (scan.minX() + dx) + ".." + (scan.maxX() + dx)
                    + ", Y " + (scan.minY() + dy) + ".." + (scan.maxY() + dy)
                    + ", Z " + (scan.minZ() + dz) + ".." + (scan.maxZ() + dz)
                    + "  <dark_gray>(offset " + dx + ", " + dy + ", " + dz + ")");
            long perSecond = Math.max(1, editor.queue().blocksPerTick() * 20L);
            long seconds = scan.solidBlocks() / perSecond;
            Editor.raw(p, "  <gray>About " + Editor.format(scan.solidBlocks()) + " blocks to write, "
                    + "roughly " + (seconds / 60) + "m " + (seconds % 60) + "s at the current rate.");
            if (!go) {
                Editor.tell(p, "<yellow>Nothing written.</yellow> To go ahead:");
                Editor.raw(p, "  " + Editor.runnable("//import " + name + " confirm",
                        "//import " + name + " confirm"));
                Editor.raw(p, "  <dark_gray>Or with your own offset: //import " + name
                        + " confirm 0 254 0");
                Editor.tell(p, "<gray>Air is skipped, so it stamps onto the terrain rather than cutting "
                        + "a box out of it.");
                Editor.tell(p, "<red>An import this large is not undoable</red> <gray>- the history "
                        + "would need gigabytes. Import into empty ground.");
                return;
            }
            startImport(p, finalRegionDir, name, dx, dy, dz);
        });
    }

    /** Set once the reader has finished; the progress ticker uses it to know when to sign off. */
    private final boolean[] importDone = {false};

    /** Queues one operation per source chunk, decoding off the main thread as it goes. */
    private void startImport(Player p, java.io.File regionDir, String name, int dx, int dy, int dz) {
        importDone[0] = false;
        java.io.File[] files = regionDir.listFiles((d, n) -> n.startsWith("r.") && n.endsWith(".mca"));
        if (files == null || files.length == 0) {
            Editor.tell(p, "<red>No region files to import.");
            return;
        }
        Editor.tell(p, "Importing <white>" + name + "</white>. "
                + Editor.runnable("//cancel", "//cancel") + " stops it.");
        // Individual chunk tasks are silent - thousands of them would bury chat - so overall progress
        // is reported here instead. Without this an import is several minutes of nothing at all, which
        // is indistinguishable from being broken.
        final int[] queued = {0};
        final long startBlocks = editor.queue().totalChanged();
        final long startedAt = System.currentTimeMillis();
        final int[] tickerId = {-1};
        tickerId[0] = org.bukkit.Bukkit.getScheduler().runTaskTimer(editor.plugin(), () -> {
            if (!p.isOnline()) {
                return;
            }
            long placed = editor.queue().totalChanged() - startBlocks;
            int pending = editor.queue().queuedOperations();
            if (importDone[0] && pending == 0) {
                long secs = (System.currentTimeMillis() - startedAt) / 1000;
                Editor.tell(p, "<green>Import finished.</green> " + Editor.plural(placed, "block")
                        + " placed from " + queued[0] + " chunks in " + (secs / 60) + "m " + (secs % 60) + "s.");
                org.bukkit.Bukkit.getScheduler().cancelTask(tickerId[0]);
                return;
            }
            p.sendActionBar(net.kyori.adventure.text.Component.text(
                    "Importing " + name + "  " + queued[0] + " chunks read  "
                            + Editor.format(placed) + " blocks placed  " + pending + " queued"));
        }, 40L, 20L).getTaskId();
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(editor.plugin(), () -> {
            int done = 0;
            for (java.io.File file : files) {
                String[] parts = file.getName().split("\\.");
                int rx;
                int rz;
                try {
                    rx = Integer.parseInt(parts[1]);
                    rz = Integer.parseInt(parts[2]);
                } catch (RuntimeException ex) {
                    continue;
                }
                List<int[]> present;
                try {
                    present = RegionReader.listChunks(file, rx, rz);
                } catch (java.io.IOException ex) {
                    continue;
                }
                for (int[] coord : present) {
                    RegionReader.DecodedChunk chunk;
                    try {
                        chunk = RegionReader.decode(
                                RegionReader.readChunk(file, coord[0], coord[1]), coord[0], coord[1]);
                    } catch (java.io.IOException ex) {
                        continue;
                    }
                    if (chunk == null) {
                        continue;
                    }
                    EditQueue.WriteSource source = importer.writesFor(chunk, dx, dy, dz);
                    if (source.estimate() == 0) {
                        continue;
                    }
                    done++;
                    queued[0] = done;
                    final int soFar = done;
                    org.bukkit.Bukkit.getScheduler().runTask(editor.plugin(), () -> {
                        if (p.isOnline()) {
                            editor.queue().submitWithoutUndo(p, p.getWorld(),
                                    "import chunk " + soFar, source);
                        }
                    });
                    // Do not outrun the writer: decoding a 7,000-chunk world all at once would hold
                    // every chunk's writes in memory simultaneously.
                    while (editor.queue().queuedOperations() > 48) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
            final int total = done;
            org.bukkit.Bukkit.getScheduler().runTask(editor.plugin(), () -> {
                importDone[0] = true;
                Editor.tell(p, "All <white>" + total + "</white> chunks read and queued.");
                Map<String, Integer> missed = importer.unknownEncountered();
                if (!missed.isEmpty()) {
                    Editor.tell(p, "<yellow>Skipped " + missed.size()
                            + " block name(s) this version does not have:");
                    int shown = 0;
                    for (Map.Entry<String, Integer> e : missed.entrySet()) {
                        if (shown++ >= 10) {
                            break;
                        }
                        Editor.raw(p, "   <yellow>" + e.getKey() + "</yellow> x " + e.getValue());
                    }
                }
            });
        });
    }

    private void adjust(Player p, String verb, String[] a) {
        Selection sel = editor.requireSelection(p);
        if (sel == null) {
            return;
        }
        if (a.length == 0) {
            Editor.tell(p, "<red>How far?</red> Example: " + Editor.runnable("//" + verb + " 10 up",
                    "//" + verb + " 10 up"));
            Editor.tell(p, "<gray>With no direction it uses the way you are looking. "
                    + "<white>//expand vert</white> stretches to the world's floor and ceiling.");
            return;
        }
        if (verb.equals("expand") && a[0].equalsIgnoreCase("vert")) {
            int before = sel.height();
            sel.expand(BlockFace.UP, sel.world().getMaxHeight());
            sel.expand(BlockFace.DOWN, sel.world().getMaxHeight());
            sel.clampVertical();
            describeSelection(p, sel, "Stretched vertically from " + before + " to " + sel.height() + " blocks");
            return;
        }
        int amount = Integer.parseInt(a[0]);
        // //expand 10 up down grows both ways in one go, which is the common case for hollowing rooms.
        if (a.length >= 3) {
            BlockFace f1 = direction(p, a[1]);
            BlockFace f2 = direction(p, a[2]);
            if (f1 == null || f2 == null) {
                unknownDirection(p, f1 == null ? a[1] : a[2]);
                return;
            }
            apply(sel, verb, f1, amount);
            apply(sel, verb, f2, amount);
        } else {
            BlockFace face = a.length >= 2 ? direction(p, a[1]) : facing(p);
            if (face == null) {
                unknownDirection(p, a[1]);
                return;
            }
            apply(sel, verb, face, amount);
        }
        sel.clampVertical();
        describeSelection(p, sel, "Selection now");
    }

    private void apply(Selection sel, String verb, BlockFace face, int amount) {
        switch (verb) {
            case "expand" -> sel.expand(face, amount);
            case "contract" -> sel.expand(face, -amount);
            case "shift" -> sel.shift(face, amount);
            default -> throw new IllegalStateException(verb);
        }
    }

    // ------------------------------------------------------------------ shapes

    private void sphere(Player p, String[] a, boolean hollow) throws BlockPattern.PatternException {
        if (a.length < 2) {
            Editor.tell(p, "<red>Needs a block and a radius.</red> Example: "
                    + Editor.runnable("//sphere stone 10", "//sphere stone 10"));
            Editor.tell(p, "<gray>Three radii make an egg: <white>//sphere stone 10 4 10");
            return;
        }
        BlockPattern pattern = BlockPattern.parse(a[0]);
        double rx = Double.parseDouble(a[1]);
        double ry = a.length >= 3 ? Double.parseDouble(a[2]) : rx;
        double rz = a.length >= 4 ? Double.parseDouble(a[3]) : rx;
        Location at = p.getLocation();
        int cx = at.getBlockX();
        int cy = at.getBlockY();
        int cz = at.getBlockZ();
        int ix = (int) Math.ceil(rx);
        int iy = (int) Math.ceil(ry);
        int iz = (int) Math.ceil(rz);
        long est = (long) (ix * 2 + 1) * (iy * 2 + 1) * (iz * 2 + 1);
        if (!withinBudget(p, est)) {
            return;
        }
        editor.queue().submit(p, p.getWorld(), (hollow ? "hollow sphere " : "sphere ") + pattern.describe(),
                Shapes.region(cx - ix, cy - iy, cz - iz, cx + ix, cy + iy, cz + iz,
                        Shapes.ellipsoid(cx, cy, cz, rx, ry, rz, hollow),
                        Shapes.pattern(pattern, random), BlockMask.any(), est), false);
        Editor.tell(p, "Building a " + (hollow ? "hollow " : "") + "sphere centred on you.");
    }

    private void cylinder(Player p, String[] a, boolean hollow) throws BlockPattern.PatternException {
        if (a.length < 2) {
            Editor.tell(p, "<red>Needs a block and a radius.</red> Example: "
                    + Editor.runnable("//cyl stone 8 20", "//cyl stone 8 20"));
            Editor.tell(p, "<gray>The third number is the height, upwards from your feet. Default 1.");
            return;
        }
        BlockPattern pattern = BlockPattern.parse(a[0]);
        double r = Double.parseDouble(a[1]);
        int height = a.length >= 3 ? Integer.parseInt(a[2]) : 1;
        if (height == 0) {
            Editor.tell(p, "<red>A height of 0 would build nothing.");
            return;
        }
        Location at = p.getLocation();
        int cx = at.getBlockX();
        int cz = at.getBlockZ();
        int baseY = at.getBlockY();
        int topY = height > 0 ? baseY + height - 1 : baseY;
        int lowY = height > 0 ? baseY : baseY + height + 1;
        int ir = (int) Math.ceil(r);
        long est = (long) (ir * 2 + 1) * (ir * 2 + 1) * Math.abs(height);
        if (!withinBudget(p, est)) {
            return;
        }
        editor.queue().submit(p, p.getWorld(), (hollow ? "hollow cylinder " : "cylinder ") + pattern.describe(),
                Shapes.region(cx - ir, Math.min(lowY, topY), cz - ir, cx + ir, Math.max(lowY, topY), cz + ir,
                        Shapes.cylinder(cx, cz, r, r, hollow),
                        Shapes.pattern(pattern, random), BlockMask.any(), est), false);
        Editor.tell(p, "Building a " + (hollow ? "hollow " : "") + "cylinder "
                + Math.abs(height) + " tall.");
    }

    private void pyramid(Player p, String[] a, boolean hollow) throws BlockPattern.PatternException {
        if (a.length < 2) {
            Editor.tell(p, "<red>Needs a block and a size.</red> Example: "
                    + Editor.runnable("//pyramid sandstone 12", "//pyramid sandstone 12"));
            Editor.tell(p, "<gray>Size is both the height and how far the base reaches from the centre.");
            return;
        }
        BlockPattern pattern = BlockPattern.parse(a[0]);
        int size = Integer.parseInt(a[1]);
        if (size < 1) {
            Editor.tell(p, "<red>Size has to be at least 1.");
            return;
        }
        Location at = p.getLocation();
        int cx = at.getBlockX();
        int cz = at.getBlockZ();
        int baseY = at.getBlockY();
        long est = (long) (size * 2 + 1) * (size * 2 + 1) * (size + 1);
        if (!withinBudget(p, est)) {
            return;
        }
        editor.queue().submit(p, p.getWorld(), (hollow ? "hollow pyramid " : "pyramid ") + pattern.describe(),
                Shapes.region(cx - size, baseY, cz - size, cx + size, baseY + size, cz + size,
                        Shapes.pyramid(cx, baseY, cz, size, hollow),
                        Shapes.pattern(pattern, random), BlockMask.any(), est), false);
        Editor.tell(p, "Building a " + (hollow ? "hollow " : "") + "pyramid " + size + " tall.");
    }

    // ------------------------------------------------------------------ stack and move

    private void stack(Player p, String[] a) {
        Selection sel = editor.requireSelection(p);
        if (sel == null) {
            return;
        }
        int count = a.length == 0 ? 1 : Integer.parseInt(a[0]);
        if (count < 1) {
            Editor.tell(p, "<red>Count has to be at least 1.");
            return;
        }
        BlockFace face = a.length >= 2 ? direction(p, a[1]) : facing(p);
        if (face == null) {
            unknownDirection(p, a[1]);
            return;
        }
        int stepX = face.getModX() * sel.width();
        int stepY = face.getModY() * sel.height();
        int stepZ = face.getModZ() * sel.length();
        long total = sel.volume() * count;
        if (!withinBudget(p, total)) {
            return;
        }
        final BlockFace dir = face;
        editor.ensureChunksThen(sel.world(), sel.minX(), sel.minZ(), sel.maxX(), sel.maxZ(), p, loaded -> {
            Clipboard clip = editor.copyRegion(sel, p);
            for (int i = 1; i <= count; i++) {
                int ox = sel.minX() + stepX * i;
                int oy = sel.minY() + stepY * i;
                int oz = sel.minZ() + stepZ * i;
                editor.queue().submit(p, sel.world(), "stack copy " + i + "/" + count,
                        Shapes.region(ox, oy, oz, ox + clip.width() - 1, oy + clip.height() - 1,
                                oz + clip.length() - 1, Shapes.ALL,
                                Shapes.clipboard(clip, ox, oy, oz, true), BlockMask.any(), clip.volume()),
                        false);
            }
            Editor.tell(p, "Stacking " + count + " copy(ies) " + dir.name().toLowerCase(Locale.ROOT) + ".");
        });
    }

    private void move(Player p, String[] a) {
        Selection sel = editor.requireSelection(p);
        if (sel == null) {
            return;
        }
        int amount = a.length == 0 ? 1 : Integer.parseInt(a[0]);
        BlockFace face = a.length >= 2 ? direction(p, a[1]) : facing(p);
        if (face == null) {
            unknownDirection(p, a[1]);
            return;
        }
        if (sel.volume() > 4_000_000) {
            Editor.tell(p, "<red>Too big to move in one go</red> (limit 4,000,000 blocks).");
            return;
        }
        int dx = face.getModX() * amount;
        int dy = face.getModY() * amount;
        int dz = face.getModZ() * amount;
        final BlockFace dir = face;
        editor.ensureChunksThen(sel.world(), Math.min(sel.minX(), sel.minX() + dx),
                Math.min(sel.minZ(), sel.minZ() + dz), Math.max(sel.maxX(), sel.maxX() + dx),
                Math.max(sel.maxZ(), sel.maxZ() + dz), p, loaded -> {
                    Clipboard clip = editor.copyRegion(sel, p);
                    try {
                        // Clear first, then write: the other order would erase blocks that had just
                        // been written when the source and destination overlap.
                        BlockPattern air = BlockPattern.parse("air");
                        submit(p, sel, "clear the old position", Shapes.ALL, air, BlockMask.any(), sel.volume());
                    } catch (BlockPattern.PatternException ignored) {
                        // "air" is a constant.
                    }
                    int ox = sel.minX() + dx;
                    int oy = sel.minY() + dy;
                    int oz = sel.minZ() + dz;
                    editor.queue().submit(p, sel.world(), "move " + amount + " "
                                    + dir.name().toLowerCase(Locale.ROOT),
                            Shapes.region(ox, oy, oz, ox + clip.width() - 1, oy + clip.height() - 1,
                                    oz + clip.length() - 1, Shapes.ALL,
                                    Shapes.clipboard(clip, ox, oy, oz, true), BlockMask.any(), clip.volume()),
                            false);
                    sel.shift(dir, amount);
                    Editor.tell(p, "Moved " + amount + " " + dir.name().toLowerCase(Locale.ROOT)
                            + ". The selection followed it.");
                });
    }

    // ------------------------------------------------------------------ undo / redo

    private void undoRedo(Player p, String verb, String[] a) {
        int times = a.length == 0 ? 1 : Math.max(1, Integer.parseInt(a[0]));
        boolean undo = verb.equals("undo");
        int done = 0;
        for (int i = 0; i < times; i++) {
            EditHistory.Entry entry = undo
                    ? editor.history().popUndo(p.getUniqueId())
                    : editor.history().popRedo(p.getUniqueId());
            if (entry == null) {
                break;
            }
            editor.applySnapshot(p, entry, undo);
            done++;
        }
        if (done == 0) {
            Editor.tell(p, undo
                    ? "<gray>Nothing left to undo."
                    : "<gray>Nothing to redo. Redo only exists straight after an undo.");
        } else if (done < times) {
            Editor.tell(p, "<gray>Only " + done + " step(s) were available.");
        }
        Editor.tell(p, "<dark_gray>" + editor.history().undoDepth(p.getUniqueId()) + " undo, "
                + editor.history().redoDepth(p.getUniqueId()) + " redo remaining.");
    }

    // ------------------------------------------------------------------ brush

    private void brush(Player p, String[] a) throws BlockPattern.PatternException {
        if (a.length == 0) {
            Editor.Brush current = editor.brush(p);
            Editor.tell(p, current == null
                    ? "<gray>No brush set."
                    : "Brush: <white>" + current.kind() + "</white>, size " + current.size()
                            + ", " + current.pattern().describe());
            Editor.tell(p, "Set one: " + Editor.runnable("//brush sphere 5 stone", "//brush sphere 5 stone"));
            Editor.tell(p, "<gray>Kinds: <white>sphere</white>, <white>cube</white>, "
                    + "<white>paint</white> (surface only), <white>none</white> to stop.");
            Editor.tell(p, "<gray>Then right-click with the rod to paint. Left-click still selects.");
            return;
        }
        String kind = a[0].toLowerCase(Locale.ROOT);
        if (kind.equals("none") || kind.equals("off")) {
            editor.setBrush(p, null);
            Editor.tell(p, "Brush cleared. The rod is back to selecting only.");
            return;
        }
        if (!List.of("sphere", "cube", "paint").contains(kind)) {
            Editor.tell(p, "<red>'" + kind + "' is not a brush.</red> Kinds are sphere, cube, paint, none.");
            return;
        }
        int size = a.length >= 2 ? Integer.parseInt(a[1]) : 3;
        if (size < 1 || size > 40) {
            Editor.tell(p, "<red>Brush size must be between 1 and 40.");
            return;
        }
        BlockPattern pattern = BlockPattern.parse(a.length >= 3 ? joinFrom(a, 2) : "stone");
        editor.setBrush(p, new Editor.Brush(kind, size, pattern, BlockMask.any()));
        Editor.tell(p, "Brush set: <white>" + kind + "</white>, size " + size + ", " + pattern.describe());
        Editor.tell(p, "<gray>Right-click with the selection rod to paint. "
                + Editor.runnable("//brush none", "//brush none") + " to stop.");
    }

    // ------------------------------------------------------------------ analysis

    private void countBlocks(Player p, Selection sel, BlockMask mask) {
        if (sel.volume() > 16_000_000) {
            Editor.tell(p, "<red>Too large to count</red> (limit 16,000,000 blocks).");
            return;
        }
        editor.ensureChunksThen(sel.world(), sel.minX(), sel.minZ(), sel.maxX(), sel.maxZ(), p, loaded -> {
            long found = 0;
            World w = sel.world();
            for (int x = sel.minX(); x <= sel.maxX(); x++) {
                for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                    for (int y = sel.minY(); y <= sel.maxY(); y++) {
                        if (mask.matches(w.getBlockAt(x, y, z).getBlockData())) {
                            found++;
                        }
                    }
                }
            }
            Editor.tell(p, "Found <white>" + Editor.format(found) + "</white> matching "
                    + mask.describe() + ", out of " + Editor.plural(sel.volume(), "block") + ".");
        });
    }

    private void distribution(Player p, Selection sel) {
        if (sel.volume() > 16_000_000) {
            Editor.tell(p, "<red>Too large to analyse</red> (limit 16,000,000 blocks).");
            return;
        }
        editor.ensureChunksThen(sel.world(), sel.minX(), sel.minZ(), sel.maxX(), sel.maxZ(), p, loaded -> {
            Map<Material, Long> counts = new HashMap<>();
            World w = sel.world();
            for (int x = sel.minX(); x <= sel.maxX(); x++) {
                for (int z = sel.minZ(); z <= sel.maxZ(); z++) {
                    for (int y = sel.minY(); y <= sel.maxY(); y++) {
                        counts.merge(w.getBlockAt(x, y, z).getType(), 1L, Long::sum);
                    }
                }
            }
            Editor.tell(p, "What is in this selection (" + Editor.plural(sel.volume(), "block") + "):");
            counts.entrySet().stream()
                    .sorted(Map.Entry.<Material, Long>comparingByValue().reversed())
                    .limit(15)
                    .forEach(e -> Editor.raw(p, "  <white>" + String.format("%,12d", e.getValue())
                            + "</white>  <gray>" + String.format("%5.1f%%", 100.0 * e.getValue() / sel.volume())
                            + "</gray>  " + e.getKey().getKey().getKey()));
            if (counts.size() > 15) {
                Editor.raw(p, "  <dark_gray>...and " + (counts.size() - 15) + " more kinds");
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void submit(Player p, Selection sel, String label, Shapes.Test test,
                        BlockPattern pattern, BlockMask mask, long estimate) {
        if (!withinBudget(p, estimate)) {
            return;
        }
        editor.queue().submit(p, sel.world(), label,
                Shapes.region(sel.minX(), sel.minY(), sel.minZ(), sel.maxX(), sel.maxY(), sel.maxZ(),
                        test, Shapes.pattern(pattern, random), mask, estimate), false);
        if (estimate > 500_000) {
            Editor.tell(p, "Working through " + Editor.plural(estimate, "block")
                    + " at " + Editor.format(editor.queue().blocksPerTick() * 20L) + "/second. "
                    + Editor.runnable("//cancel", "//cancel") + " stops it.");
        }
    }

    /** Refuses absurd operations with a number, rather than trying and dying. */
    private boolean withinBudget(Player p, long blocks) {
        long limit = 120_000_000L;
        if (blocks <= limit) {
            return true;
        }
        Editor.tell(p, "<red>That is " + Editor.format(blocks) + " blocks.</red> The limit is "
                + Editor.format(limit) + ".");
        Editor.tell(p, "<gray>At the current rate it would take about "
                + (blocks / Math.max(1, editor.queue().blocksPerTick() * 20L) / 60) + " minutes. "
                + "Work in pieces.");
        return false;
    }

    private void describeSelection(Player p, Selection sel, String prefix) {
        if (!sel.isComplete()) {
            Editor.tell(p, prefix + ". <gray>Now set the other corner.");
            return;
        }
        Editor.tell(p, prefix + ": <white>" + sel.width() + "x" + sel.height() + "x" + sel.length()
                + "</white> = " + Editor.plural(sel.volume(), "block"));
    }

    private void unknownDirection(Player p, String given) {
        Editor.tell(p, "<red>'" + given + "' is not a direction.</red>");
        Editor.tell(p, "<gray>Use up, down, north, south, east, west, or leave it out to use "
                + "the way you are looking.");
    }

    private static BlockFace facing(Player p) {
        float pitch = p.getLocation().getPitch();
        if (pitch < -50) {
            return BlockFace.UP;
        }
        if (pitch > 50) {
            return BlockFace.DOWN;
        }
        return yawToFace(p.getLocation().getYaw());
    }

    private static String facingAxis(Player p) {
        BlockFace f = facing(p);
        if (f == BlockFace.UP || f == BlockFace.DOWN) {
            return "y";
        }
        return (f == BlockFace.EAST || f == BlockFace.WEST) ? "x" : "z";
    }

    private static BlockFace yawToFace(float yaw) {
        float y = (yaw % 360 + 360) % 360;
        if (y >= 315 || y < 45) {
            return BlockFace.SOUTH;
        }
        if (y < 135) {
            return BlockFace.WEST;
        }
        if (y < 225) {
            return BlockFace.NORTH;
        }
        return BlockFace.EAST;
    }

    private static BlockFace direction(Player p, String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "up", "u", "^" -> BlockFace.UP;
            case "down", "d", "v" -> BlockFace.DOWN;
            case "north", "n" -> BlockFace.NORTH;
            case "south", "s" -> BlockFace.SOUTH;
            case "east", "e" -> BlockFace.EAST;
            case "west", "w" -> BlockFace.WEST;
            case "me", "forward", "f" -> facing(p);
            case "back", "b" -> facing(p).getOppositeFace();
            default -> null;
        };
    }

    private static String join(String[] a, String errorIfEmpty) throws BlockPattern.PatternException {
        if (a.length == 0) {
            throw new BlockPattern.PatternException(errorIfEmpty.isEmpty() ? "Missing block name." : errorIfEmpty);
        }
        return String.join(" ", a).replace(" ", "");
    }

    private static String joinFrom(String[] a, int from) {
        return String.join("", Arrays.copyOfRange(a, from, a.length));
    }

    // ------------------------------------------------------------------ tab completion

    private static final List<String> DIRECTIONS = List.of("up", "down", "north", "south", "east", "west", "me");
    private static final List<String> GROUPS = List.of("#any", "#air", "#solid", "#liquid", "!air");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        String verb = name;
        String[] rest = args;
        if (name.equals("aed")) {
            if (args.length <= 1) {
                return prefixed(VERBS, args.length == 0 ? "" : args[0]);
            }
            verb = args[0].toLowerCase(Locale.ROOT);
            rest = Arrays.copyOfRange(args, 1, args.length);
        }
        String current = rest.length == 0 ? "" : rest[rest.length - 1];

        return switch (verb) {
            case "help" -> prefixed(EditorHelp.TOPICS, current);
            case "set", "walls", "shell", "hollow", "sphere", "hsphere", "cyl", "hcyl",
                 "pyramid", "hpyramid" -> rest.length <= 1 ? blockNames(current) : List.of();
            case "replace" -> rest.length <= 1
                    ? prefixed(concat(GROUPS, materialNames()), current)
                    : (rest.length == 2 ? blockNames(current) : List.of());
            case "count" -> prefixed(concat(GROUPS, materialNames()), current);
            case "brush" -> rest.length <= 1
                    ? prefixed(List.of("sphere", "cube", "paint", "none"), current)
                    : (rest.length == 3 ? blockNames(current) : List.of());
            case "expand", "contract", "shift", "stack", "move" ->
                    rest.length <= 1 ? List.of("1", "5", "10", "vert") : prefixed(DIRECTIONS, current);
            case "up", "down" -> List.of("1", "5", "10", "20");
            case "ceil" -> List.of("0", "2", "5");
            case "flip" -> prefixed(List.of("x", "y", "z"), current);
            case "rotate" -> List.of("90", "180", "270");
            case "paste" -> prefixed(List.of("air"), current);
            case "apron" -> rest.length <= 1
                    ? prefixed(List.of("confirm", "150", "200", "300"), current)
                    : List.of();
            case "island" -> rest.length <= 1 ? List.of("60", "90") : List.of();
            case "schem", "schematic" -> rest.length <= 1
                    ? prefixed(List.of("save", "load", "list", "delete"), current)
                    : prefixed(SchematicIO.list(schematicRoot(), null), current);
            default -> List.of();
        };
    }

    private static List<String> blockNames(String current) {
        return prefixed(materialNames(), current);
    }

    private static List<String> materialNames() {
        List<String> out = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isBlock() && !m.isLegacy()) {
                out.add(m.getKey().getKey());
            }
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> prefixed(List<String> options, String current) {
        String c = current.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.startsWith(c)).limit(80).collect(Collectors.toList());
    }
}
