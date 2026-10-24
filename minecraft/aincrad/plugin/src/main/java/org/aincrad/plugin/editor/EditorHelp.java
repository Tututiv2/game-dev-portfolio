package org.aincrad.plugin.editor;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The command reference, and the only place it is written down.
 *
 * <p>The complaint this is built against is specific: WorldEdit answers "what can I do?" with a flat
 * alphabetical list of several hundred commands, which tells you everything and teaches you nothing. So
 * help here is organised by <em>what you are trying to do</em>, every entry carries a runnable example,
 * and the top level is a short menu rather than a dump.</p>
 *
 * <p>Every command is described exactly once, in {@link #REGISTRY}. Chat help, the in-game book and the
 * written Markdown reference are all rendered from it, so they cannot drift apart - which is the usual
 * fate of a hand-maintained docs page next to a hand-maintained help command.</p>
 */
public final class EditorHelp {

    private EditorHelp() {
    }

    /** One command. {@code detail} may be empty; {@code example} never is. */
    public record Info(String topic, String usage, String summary, String detail, String example) {
        public String name() {
            int space = usage().indexOf(' ');
            return space < 0 ? usage() : usage().substring(0, space);
        }
    }

    public static final Map<String, String> TOPIC_TITLES = new LinkedHashMap<>();

    static {
        TOPIC_TITLES.put("start", "Start here - the five minute version");
        TOPIC_TITLES.put("move", "Getting around");
        TOPIC_TITLES.put("select", "Choosing an area");
        TOPIC_TITLES.put("fill", "Filling and replacing");
        TOPIC_TITLES.put("shapes", "Spheres, cylinders, pyramids");
        TOPIC_TITLES.put("copy", "Copy, paste, move, stack");
        TOPIC_TITLES.put("brush", "Painting with a brush");
        TOPIC_TITLES.put("blocks", "How to write block names");
        TOPIC_TITLES.put("world", "Shaping the floor itself");
        TOPIC_TITLES.put("fix", "Undo, cancel, and when things go wrong");
    }

    public static final List<String> TOPICS = new ArrayList<>(TOPIC_TITLES.keySet());

    public static final List<Info> REGISTRY = List.of(
            // ---- getting around
            new Info("move", "//up <n>", "Rise n blocks, standing on a glass step",
                    "Teleporting into open air would just mean falling, so a glass block is placed under "
                            + "your feet - but only when that space was empty, so repeated //up inside a "
                            + "building does not perforate every floor on the way through. The step is undoable "
                            + "like any other edit.",
                    "//up 10"),
            new Info("move", "//down <n>", "Drop n blocks the same way", "", "//down 5"),
            new Info("move", "//jumpto", "Teleport to the block you are looking at",
                    "Reaches 320 blocks. The fastest way to get to the far side of something.", "//jumpto"),
            new Info("move", "//thru", "Step through the wall or floor you are facing",
                    "Finds the first open space on the other side, up to 40 blocks in.", "//thru"),
            new Info("move", "//ceil [gap]", "Stand on top of the ceiling above you",
                    "The optional gap leaves that many blocks of headroom above the ceiling.", "//ceil"),

            // ---- select
            new Info("select", "//wand", "Get the selection rod",
                    "Left-click a block for corner 1, right-click for corner 2. Clicking empty air "
                            + "reaches up to " + Editor.AIR_CLICK_RANGE + " blocks, so you can select "
                            + "things from a distance without flying to them.",
                    "//wand"),
            new Info("select", "//pos1", "Set corner 1 to where you are standing",
                    "The typing alternative to left-clicking with the rod. Worth knowing because some "
                            + "corners have no face to click: inside solid rock, or out in mid-air.",
                    "//pos1"),
            new Info("select", "//pos2", "Set corner 2 to where you are standing",
                    "The typing alternative to right-clicking with the rod.", "//pos2"),
            new Info("select", "//size", "Show the size and coordinates of your selection", "", "//size"),
            new Info("select", "//desel", "Forget the selection", "", "//desel"),
            new Info("select", "//expand <n> [direction]", "Grow the selection",
                    "With no direction it grows the way you are looking. Give two directions to grow "
                            + "both ways at once. '//expand vert' stretches from the world floor to the ceiling, "
                            + "which is how you select a whole column of a building.",
                    "//expand 10 up"),
            new Info("select", "//contract <n> [direction]", "Shrink the selection",
                    "Exactly the same rules as //expand, in reverse.", "//contract 5 north"),
            new Info("select", "//shift <n> [direction]", "Slide the selection without resizing it",
                    "Useful for working along a wall a section at a time.", "//shift 16 east"),
            new Info("select", "//outline", "Turn the glowing selection outline on or off",
                    "The outline is drawn only for you, and only when you are within about 100 blocks.",
                    "//outline"),

            // ---- fill
            new Info("fill", "//set <block>", "Fill the whole selection",
                    "Accepts mixtures: '//set 70%stone,30%cobblestone' gives a weathered look. "
                            + "'//set air' deletes everything in the selection.",
                    "//set stone"),
            new Info("fill", "//replace <find> <put>", "Swap one block for another",
                    "Only touches blocks matching <find>, so the rest of the build is safe. "
                            + "'//replace !air stone' turns every solid block to stone and leaves the gaps. "
                            + "A bare name matches every rotation, so '//replace oak_stairs stone' catches all of them.",
                    "//replace dirt grass_block"),
            new Info("fill", "//walls <block>", "Build the four vertical sides only",
                    "No floor, no ceiling. This is how you raise a room's walls in one command.",
                    "//walls stone_bricks"),
            new Info("fill", "//shell <block>", "Build all six faces",
                    "Like //walls but with a floor and ceiling too - a sealed box.", "//shell glass"),
            new Info("fill", "//hollow [block]", "Empty the inside, keep a one-block skin",
                    "Turns a solid block of stone into a room. With no block given it hollows to air.",
                    "//hollow"),
            new Info("fill", "//count <block>", "Count matching blocks in the selection", "", "//count diamond_ore"),
            new Info("fill", "//distr", "List everything in the selection, most common first",
                    "Good for checking what a build is actually made of before you start replacing things.",
                    "//distr"),

            // ---- shapes
            new Info("shapes", "//sphere <block> <radius>", "Solid sphere centred on you",
                    "Three radii make an ellipsoid: '//sphere stone 10 4 10' is a squashed dome.",
                    "//sphere stone 10"),
            new Info("shapes", "//hsphere <block> <radius>", "Hollow sphere - a dome or a bubble", "",
                    "//hsphere glass 12"),
            new Info("shapes", "//cyl <block> <radius> [height]", "Solid cylinder rising from your feet",
                    "A negative height builds downwards, which is how you sink a well or a pillar footing.",
                    "//cyl stone 8 20"),
            new Info("shapes", "//hcyl <block> <radius> [height]", "Hollow cylinder - a tower or a silo", "",
                    "//hcyl stone_bricks 8 30"),
            new Info("shapes", "//pyramid <block> <size>", "Solid pyramid rising from your feet",
                    "Size is both the height and how far the base reaches from the centre.",
                    "//pyramid sandstone 12"),
            new Info("shapes", "//hpyramid <block> <size>", "Hollow pyramid", "", "//hpyramid sandstone 12"),

            // ---- copy
            new Info("copy", "//copy", "Copy the selection to your clipboard",
                    "The copy remembers where you were standing, so pasting puts it back in the same "
                            + "relationship to you. Stand at a building's front door when you copy it and it will "
                            + "paste with its door at your feet.",
                    "//copy"),
            new Info("copy", "//cut", "Copy the selection, then delete the original", "", "//cut"),
            new Info("copy", "//paste [air]", "Paste the clipboard at your feet",
                    "Plain //paste skips air, so it stamps a build onto the landscape without punching "
                            + "holes around it. Add 'air' to paste air as well, which erases - use that when "
                            + "you are moving a room's interior and want the old contents gone.",
                    "//paste"),
            new Info("copy", "//rotate <degrees>", "Turn the clipboard, in multiples of 90",
                    "Stairs, doors, logs, signs and fences all turn with it.", "//rotate 90"),
            new Info("copy", "//flip [x|y|z]", "Mirror the clipboard",
                    "With no axis it mirrors the way you are facing. Block states are mirrored too.",
                    "//flip"),
            new Info("copy", "//stack <count> [direction]", "Repeat the selection end to end",
                    "Each copy sits exactly one selection-length along, so a corridor section stacks into "
                            + "a corridor. Does not use the clipboard.",
                    "//stack 5 east"),
            new Info("copy", "//move <n> [direction]", "Slide the selection's contents",
                    "Clears the old position and takes the selection with it.", "//move 10 up"),

            // ---- brush
            new Info("brush", "//brush sphere <size> <block>", "Paint spheres where you look",
                    "Right-click with the selection rod to paint. Left-click still selects, so the rod "
                            + "keeps doing both jobs.",
                    "//brush sphere 5 stone"),
            new Info("brush", "//brush cube <size> <block>", "Paint cubes where you look", "",
                    "//brush cube 4 stone"),
            new Info("brush", "//brush paint <size> <block>", "Coat the surface without filling underneath",
                    "Only replaces blocks that are exposed to air, so it re-skins terrain and roofs "
                            + "instead of burying them.",
                    "//brush paint 6 grass_block"),
            new Info("brush", "//brush none", "Stop painting", "", "//brush none"),

            // ---- fix
            // ---- world
            new Info("world", "//apron [width] [depth]", "Grow natural land outward from where you stand",
                    "For an island that stops dead in the middle of the countryside - which is what an "
                            + "imported build does, because its region files had to end somewhere. This "
                            + "measures the land you are standing on, grows a belt of matching terrain all "
                            + "the way round it, and puts the drop at the far side of that belt, so the "
                            + "villages end up inland instead of on the brink. "
                            + "With no numbers it picks a width that makes the island half again as big by "
                            + "area, and a rock depth matching the underside the land already has. The join "
                            + "is blended, not butted: the seam takes the real ground height next door, "
                            + "smoothed over 11 blocks, and the new hills fade in over the first 20. "
                            + "Courtyards and squares enclosed by the build are left alone. "
                            + "A bare //apron only measures and reports - //apron confirm writes it, and at "
                            + "these sizes that is not undoable, so read the report first.",
                    "//apron"),
            new Info("world", "//apron confirm", "Build the apron you just planned",
                    "Writes the plan from your last //apron. Tens of millions of blocks, so it runs in the "
                            + "background with a progress bar, and //cancel stops it. Raise //perf first if "
                            + "you want it to finish sooner.",
                    "//apron confirm"),
            new Info("world", "//island [depth] [radius]", "Carve the underside into a floating island",
                    "Keeps a band of rock beneath each surface column and voids everything below, so the "
                            + "land reads as a plate hanging in the sky instead of a pillar down to bedrock. "
                            + "The underside is deliberately irregular and thins to a rim at the edge; a "
                            + "constant thickness looks like a bug rather than a landmass. "
                            + "Select an area with the rod first and it follows that exactly. Without a "
                            + "selection the radius has to be said out loud - '//island 50' reads as "
                            + "'radius 50', and carving 800 instead once deleted 117 million blocks.",
                    "//island 90 150"),
            new Info("world", "//import <name>", "Bring in a world saved by another Minecraft version",
                    "Reads region files directly, so it opens saves this server cannot - the 1.12 and 1.16 "
                            + "SAO worlds included - and translates block names that have been renamed since. "
                            + "Worlds go in server/imports/<name>. "
                            + "//import <name> scans and reports: extent, height, what the surface is made "
                            + "of, and any block name that no longer exists and would be skipped. Only "
                            + "//import <name> confirm writes anything.",
                    "//import saof1"),

            // ---- fix
            new Info("fix", "//undo [n]", "Undo your last edit, or the last n",
                    "Every operation is undoable, including ones that are still running. History is kept "
                            + "per player and bounded by memory rather than a step count, so lots of small edits "
                            + "all stay undoable.",
                    "//undo"),
            new Info("fix", "//redo [n]", "Redo what you just undid",
                    "Making a new edit clears the redo branch, the same as any text editor.", "//redo"),
            new Info("fix", "//cancel", "Stop an operation that is still running",
                    "Blocks already written stay written, and stay undoable.", "//cancel"),
            new Info("fix", "//perf [n]", "How many blocks are placed per tick",
                    "Default 12,000 per tick, which is 240,000 a second. Raise it to finish sooner at the "
                            + "cost of server smoothness; lower it if you are playing while a big job runs.",
                    "//perf 30000"),
            new Info("fix", "//guide", "Get this guide as a book you can carry", "", "//guide")
    );

    // ------------------------------------------------------------------ chat help

    public static void dispatch(CommandSender to, String[] args) {
        if (args.length == 0) {
            overview(to);
            return;
        }
        String query = args[0].toLowerCase(Locale.ROOT).replace("/", "");
        if (TOPIC_TITLES.containsKey(query)) {
            topic(to, query);
            return;
        }
        // Allow "//help set" as well as "//help fill".
        for (Info info : REGISTRY) {
            if (info.name().replace("/", "").equalsIgnoreCase(query)) {
                command(to, info);
                return;
            }
        }
        Editor.tell(to, "<red>No help topic or command called '" + query + "'.");
        overview(to);
    }

    public static void overview(CommandSender to) {
        Editor.raw(to, "");
        Editor.raw(to, "<dark_aqua><strikethrough>                    </strikethrough> "
                + "<aqua><bold>Aincrad Editor</bold></aqua> "
                + "<dark_aqua><strikethrough>                    </strikethrough>");
        Editor.raw(to, "<gray>Pick what you are trying to do. Everything is clickable.");
        Editor.raw(to, "");
        for (Map.Entry<String, String> e : TOPIC_TITLES.entrySet()) {
            Editor.raw(to, "  " + Editor.instant("• " + e.getValue(), "//help " + e.getKey()));
        }
        Editor.raw(to, "");
        Editor.raw(to, "<gray>New here? " + Editor.instant("Start here", "//help start")
                + "<gray>. Prefer paper? " + Editor.instant("//guide", "//guide")
                + " <gray>gives you a book.");
        Editor.raw(to, "<dark_gray>Every command also works as /aed <name>, if // is awkward to type.");
        Editor.raw(to, "");
    }

    private static void topic(CommandSender to, String key) {
        if (key.equals("start")) {
            startHere(to);
            return;
        }
        if (key.equals("blocks")) {
            blocksHelp(to);
            return;
        }
        Editor.raw(to, "");
        Editor.raw(to, "<aqua><bold>" + TOPIC_TITLES.get(key) + "</bold>");
        Editor.raw(to, "");
        for (Info info : REGISTRY) {
            if (!info.topic().equals(key)) {
                continue;
            }
            Editor.raw(to, "  <white>" + info.usage() + "</white>");
            Editor.raw(to, "    <gray>" + info.summary() + "  "
                    + Editor.runnable("[try it]", info.example()));
        }
        Editor.raw(to, "");
        Editor.raw(to, "<dark_gray>Detail on any one: <white>//help <command></white>, "
                + "e.g. " + Editor.instant("//help set", "//help set"));
        Editor.raw(to, "<dark_gray>" + Editor.instant("Back to the menu", "//help"));
        Editor.raw(to, "");
    }

    private static void command(CommandSender to, Info info) {
        Editor.raw(to, "");
        Editor.raw(to, "<aqua><bold>" + info.usage() + "</bold>");
        Editor.raw(to, "<white>" + info.summary());
        if (!info.detail().isEmpty()) {
            Editor.raw(to, "");
            Editor.raw(to, "<gray>" + info.detail());
        }
        Editor.raw(to, "");
        Editor.raw(to, "<gray>Example: " + Editor.runnable(info.example(), info.example()));
        Editor.raw(to, "<dark_gray>" + Editor.instant("Back to " + TOPIC_TITLES.get(info.topic()),
                "//help " + info.topic()));
        Editor.raw(to, "");
    }

    private static void startHere(CommandSender to) {
        Editor.raw(to, "");
        Editor.raw(to, "<aqua><bold>Start here</bold>");
        Editor.raw(to, "<gray>Five steps. Do them in order and you will have used most of the editor.");
        Editor.raw(to, "");
        Editor.raw(to, " <dark_aqua>1</dark_aqua> " + Editor.instant("//wand", "//wand")
                + " <gray>- take the selection rod.");
        Editor.raw(to, " <dark_aqua>2</dark_aqua> <gray>Left-click a block. That is one corner. "
                + "<dark_gray>(or stand there and type //pos1)");
        Editor.raw(to, " <dark_aqua>3</dark_aqua> <gray>Right-click a block some distance away. "
                + "A glowing outline appears - that box is your selection. "
                + "<dark_gray>(or //pos2)");
        Editor.raw(to, " <dark_aqua>4</dark_aqua> " + Editor.runnable("//set stone", "//set stone")
                + " <gray>- the box fills with stone.");
        Editor.raw(to, " <dark_aqua>5</dark_aqua> " + Editor.instant("//undo", "//undo")
                + " <gray>- and it is gone again.");
        Editor.raw(to, "");
        Editor.raw(to, "<gray>That is the whole idea: <white>choose a box, do something to it, "
                + "undo it if you don't like it</white>.");
        Editor.raw(to, "<gray>Everything else is a variation. Nothing you do here is permanent.");
        Editor.raw(to, "");
        Editor.raw(to, "<gray>Next: " + Editor.instant("filling and replacing", "//help fill")
                + "<gray>, or " + Editor.instant("the menu", "//help") + "<gray>.");
        Editor.raw(to, "");
    }

    private static void blocksHelp(CommandSender to) {
        Editor.raw(to, "");
        Editor.raw(to, "<aqua><bold>How to write block names</bold>");
        Editor.raw(to, "");
        Editor.raw(to, "<white>stone</white> <gray>- the vanilla name. No 'minecraft:' needed, "
                + "though it is allowed.");
        Editor.raw(to, "<white>oak_stairs[facing=north]</white> <gray>- an exact state, when you care "
                + "which way it points.");
        Editor.raw(to, "");
        Editor.raw(to, "<aqua>Mixtures</aqua> <gray>- give more than one, separated by commas:");
        Editor.raw(to, "  <white>stone,cobblestone</white> <gray>- half and half.");
        Editor.raw(to, "  <white>70%stone,30%cobblestone</white> <gray>- weighted. Weights are relative, "
                + "so <white>1%glowstone,50%stone</white> works fine.");
        Editor.raw(to, "");
        Editor.raw(to, "<aqua>Groups</aqua> <gray>- for the 'find' half of //replace and for //count:");
        Editor.raw(to, "  <white>#any</white> <gray>everything, including air");
        Editor.raw(to, "  <white>#air</white> <gray>air and cave air");
        Editor.raw(to, "  <white>#solid</white> <gray>anything you cannot walk through");
        Editor.raw(to, "  <white>#liquid</white> <gray>water and lava");
        Editor.raw(to, "  <white>!air</white> <gray>a leading ! means 'not' - so this is everything "
                + "except air");
        Editor.raw(to, "");
        Editor.raw(to, "<gray>A bare name like <white>oak_stairs</white> matches <white>every</white> "
                + "rotation of it. Add a [state] only when you want to be picky.");
        Editor.raw(to, "<dark_gray>" + Editor.instant("Back to the menu", "//help"));
        Editor.raw(to, "");
    }

    // ------------------------------------------------------------------ the book

    /** A carryable copy of the guide, for reading without filling chat. */
    public static void giveBook(Player player) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Aincrad Editor");
        meta.setAuthor("Aincrad");

        List<Component> pages = new ArrayList<>();
        pages.add(Editor.component(
                "<black><bold>Aincrad Editor</bold>\n\n"
                        + "Choose a box.\nDo something to it.\nUndo it if you don't like it.\n\n"
                        + "That is the whole idea. Everything else is a variation on it, and "
                        + "<bold>nothing is permanent</bold>.\n\n"
                        + "<dark_gray>Turn the page."));
        pages.add(Editor.component(
                "<black><bold>Five steps</bold>\n\n"
                        + "1. //wand\n   take the rod\n\n"
                        + "2. Left-click a block\n   corner one\n\n"
                        + "3. Right-click another\n   corner two\n\n"
                        + "4. //set stone\n   the box fills\n\n"
                        + "5. //undo\n   and it's gone"));
        pages.add(Editor.component(
                "<black><bold>The rod</bold>\n\n"
                        + "Left-click = corner 1\nRight-click = corner 2\n\n"
                        + "Clicking <bold>empty air</bold> reaches " + Editor.AIR_CLICK_RANGE
                        + " blocks, so you can pick corners across a valley without flying there.\n\n"
                        + "A glowing outline shows the box."));

        for (Map.Entry<String, String> topic : TOPIC_TITLES.entrySet()) {
            if (topic.getKey().equals("start") || topic.getKey().equals("blocks")) {
                continue;
            }
            StringBuilder sb = new StringBuilder("<black><bold>" + topic.getValue() + "</bold>\n\n");
            for (Info info : REGISTRY) {
                if (info.topic().equals(topic.getKey())) {
                    sb.append("<dark_blue>").append(info.usage()).append("</dark_blue>\n<black>")
                            .append(info.summary()).append("\n\n");
                }
            }
            // Books hold roughly 14 short lines a page, so long topics are split rather than truncated.
            for (String chunk : splitForBook(sb.toString())) {
                pages.add(Editor.component(chunk));
            }
        }

        pages.add(Editor.component(
                "<black><bold>Block names</bold>\n\n"
                        + "stone\n\n"
                        + "70%stone,30%cobblestone\n  a weathered mix\n\n"
                        + "!air\n  everything solid\n\n"
                        + "#solid #liquid #air\n  handy groups\n\n"
                        + "oak_stairs\n  matches every rotation"));
        pages.add(Editor.component(
                "<black><bold>If it goes wrong</bold>\n\n"
                        + "//undo\n  step back\n\n"
                        + "//cancel\n  stop a running job\n\n"
                        + "//size\n  what am I even\n  selecting?\n\n"
                        + "In chat, //help is a menu you can click through."));

        meta.pages(pages);
        book.setItemMeta(meta);
        player.getInventory().addItem(book);
        Editor.tell(player, "Guide added to your inventory. " + Editor.instant("//help", "//help")
                + " <gray>is the clickable version.");
    }

    private static List<String> splitForBook(String text) {
        List<String> out = new ArrayList<>();
        String[] blocks = text.split("\n\n");
        StringBuilder current = new StringBuilder();
        int lines = 0;
        for (String block : blocks) {
            int blockLines = block.split("\n").length + 1;
            if (lines + blockLines > 13 && current.length() > 0) {
                out.add(current.toString());
                current = new StringBuilder("<black>");
                lines = 0;
            }
            current.append(block).append("\n\n");
            lines += blockLines;
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------ markdown reference

    /**
     * Writes the Markdown reference from {@link #REGISTRY}, so the file on disk cannot describe a
     * command set that no longer exists.
     */
    public static Path writeMarkdown(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(target, StandardCharsets.UTF_8))) {
            out.println("# Aincrad Editor - command reference");
            out.println();
            out.println("> Generated from `EditorHelp.REGISTRY` by `/aed docs`. Do not hand-edit: the");
            out.println("> in-game help, the book and this file are rendered from the same list, which is");
            out.println("> the only way they stay in agreement.");
            out.println();
            out.println("Every command also works as `/aed <name>` if `//` is awkward to type.");
            out.println();
            for (Map.Entry<String, String> topic : TOPIC_TITLES.entrySet()) {
                List<Info> entries = REGISTRY.stream()
                        .filter(i -> i.topic().equals(topic.getKey())).toList();
                if (entries.isEmpty()) {
                    continue;
                }
                out.println("## " + topic.getValue());
                out.println();
                out.println("| Command | What it does | Example |");
                out.println("|---|---|---|");
                for (Info info : entries) {
                    out.printf("| `%s` | %s | `%s` |%n", info.usage(), info.summary(), info.example());
                }
                out.println();
                for (Info info : entries) {
                    if (!info.detail().isEmpty()) {
                        out.println("**`" + info.usage() + "`** - " + info.detail());
                        out.println();
                    }
                }
            }
        }
        return target;
    }
}
