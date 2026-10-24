package com.skyclone.mod.skills;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The skills module's two Hub NPCs (wave 5): the Runic Pedestal keeper (reference "Dusk", Mining District,
 * next to the Blacksmith) and the Pet Trainer (reference "Fann", the Taming road's training sessions).
 *
 * <p>The Hub belongs to the hub module, which has no anchors for either (skills requests 10 and 13). So,
 * decided once in {@code postEnable}, per NPC:
 * <ol>
 *   <li>another module already registered one (id or name mentions it) - we stand aside;</li>
 *   <li>the hub layout HAS our anchor ({@code npc.runic_pedestal} / {@code npc.pet_trainer}) - registered at it,
 *       core places it like every other NPC;</li>
 *   <li>otherwise registered on a placeholder island (core never spawns it there, and its anchor self-test skips
 *       islands with no layout) and placed by THIS class with {@code spawnAt}: the dial position if one is set,
 *       else the nearest free standable block 2-6 blocks from a neighbouring Hub anchor (the Blacksmith / the Pet
 *       Sitter), only once that chunk is loaded (it never loads chunks itself). Re-placed when the Hub world reloads.</li>
 * </ol>
 */
final class SkillNpcs {

    static final String PLACEHOLDER_ISLAND = "skills_unplaced";
    static final String HUB = "hub";

    /** One NPC and how it got placed. */
    static final class Spot {
        final String npcId, label, wantAnchor, nearAnchor;
        final String[] hints;
        final Dials.Dial<Boolean> on;
        final Dials.Dial<String> at;
        String mode = "not started";
        Location placed;

        Spot(String npcId, String label, String wantAnchor, String nearAnchor, String[] hints,
             Dials.Dial<Boolean> on, Dials.Dial<String> at) {
            this.npcId = npcId; this.label = label; this.wantAnchor = wantAnchor; this.nearAnchor = nearAnchor;
            this.hints = hints; this.on = on; this.at = at;
        }
    }

    final List<Spot> spots = new ArrayList<>();

    Spot add(String npcId, String label, String wantAnchor, String nearAnchor, String... hints) {
        String k = label.toLowerCase(Locale.ROOT).replace(' ', '-');
        var d = Sky.dials();
        Spot s = new Spot(npcId, label, wantAnchor, nearAnchor, hints,
                d.flag("skills.npc." + k, true, "Place the " + label + " NPC in the Hub (skills module; stands aside if another module made one)"),
                d.text("skills.npc." + k + "-at", "", "Exact Hub position for the " + label + " NPC as 'x,y,z[,yaw]' (empty = next to " + nearAnchor + "); /sb skillnpc " + k + " here"));
        spots.add(s);
        return s;
    }

    /** postEnable: decide and register (see class doc). */
    void register(Spot s, NpcDef def, Consumer<Player> onClick) {
        if (!s.on.get()) { s.mode = "off (dial)"; return; }
        String other = otherOwner(s);
        if (other != null) { s.mode = "yielded to NPC '" + other + "'"; return; }
        if (hubHas(s.wantAnchor)) {
            def.island = HUB;
            def.anchor = s.wantAnchor;
            s.mode = "hub anchor " + s.wantAnchor;
        } else {
            def.island = PLACEHOLDER_ISLAND;
            def.anchor = s.wantAnchor;
            s.mode = "auto (next to " + s.nearAnchor + ")";
        }
        Sky.npcs().register(def, onClick);
    }

    /** Someone else's NPC that is already this one, or null. */
    private static String otherOwner(Spot s) {
        try {
            for (NpcDef d : Sky.npcs().all()) {
                if (d == null || d.id == null || d.id.equals(s.npcId)) continue;
                if (HUB.equals(d.island) && s.wantAnchor.equals(d.anchor)) return d.id;
                // id and title only: a person's NAME may contain a hint by accident ("Duskvine")
                String hay = (d.id + " " + Text.strip(d.title == null ? "" : d.title)).toLowerCase(Locale.ROOT);
                for (String h : s.hints) if (hay.contains(h)) return d.id;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    static boolean hubHas(String anchor) {
        try {
            Layout l = Sky.worlds().layout(HUB);
            return l != null && l.anchor(anchor) != null;
        } catch (RuntimeException e) { return false; }
    }

    // ---- placement (auto mode) -----------------------------------------------------------------------

    void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 200L, 100L);
    }

    private void tick() {
        World w = Bukkit.getWorld("sb_" + HUB);
        for (Spot s : spots) {
            if (!s.mode.startsWith("auto") && !s.mode.startsWith("dial")) continue;
            if (w == null) { s.placed = null; continue; }
            if (s.placed != null && s.placed.getWorld() == w) continue;
            Location loc = target(s, w);
            if (loc == null) continue;
            try {
                Sky.npcs().spawnAt(s.npcId, HUB, loc);
                s.placed = loc;
            } catch (RuntimeException e) {
                Sky.log().warning("[skills] could not place NPC " + s.npcId + ": " + e.getMessage());
            }
        }
    }

    /** Where this NPC should stand right now, or null (Hub chunk not loaded, nothing free). */
    Location target(Spot s, World w) {
        Location fixed = parse(s.at.get(), w);
        if (fixed != null) return fixed;
        Location near = null;
        try { near = Sky.worlds().anchor(HUB, s.nearAnchor); } catch (RuntimeException ignored) { }
        if (near == null) try { near = Sky.worlds().anchor(HUB, "spawn"); } catch (RuntimeException ignored) { }
        if (near == null || near.getWorld() == null) return null;
        World nw = near.getWorld();
        if (!nw.isChunkLoaded(near.getBlockX() >> 4, near.getBlockZ() >> 4)) return null;
        int bx = near.getBlockX(), by = near.getBlockY(), bz = near.getBlockZ();
        int[] o = TrainerTiers.findSpot(2, 6, (dx, dy, dz) -> standable(nw, bx + dx, by + dy, bz + dz));
        if (o == null) return null;
        double x = bx + o[0] + 0.5, z = bz + o[2] + 0.5;
        float yaw = (float) Math.toDegrees(Math.atan2(-(near.getX() - x), near.getZ() - z));
        return new Location(nw, x, by + o[1], z, yaw, 0f);
    }

    /** Feet and head free (not liquid), a full block underneath, chunk loaded, nobody standing there. */
    private static boolean standable(World w, int x, int y, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
        Block feet = w.getBlockAt(x, y, z);
        Block head = feet.getRelative(BlockFace.UP);
        Block below = feet.getRelative(BlockFace.DOWN);
        if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) return false;
        if (!below.getType().isOccluding() || below.isLiquid()) return false;
        // never on a door, carpet path, pressure plate...: only air at the feet
        if (!feet.getType().isAir() || !head.getType().isAir()) return false;
        for (Entity e : w.getNearbyEntities(new Location(w, x + 0.5, y + 1, z + 0.5), 1.2, 1.5, 1.2))
            if (!(e instanceof Player)) return false;
        return true;
    }

    static Location parse(String spec, World w) {
        if (spec == null || spec.isBlank() || w == null) return null;
        String[] a = spec.trim().split("\\s*,\\s*");
        if (a.length < 3) return null;
        try {
            double x = Double.parseDouble(a[0]), y = Double.parseDouble(a[1]), z = Double.parseDouble(a[2]);
            float yaw = a.length > 3 ? Float.parseFloat(a[3]) : 0f;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
            return new Location(w, x, y, z, yaw, 0f);
        } catch (NumberFormatException e) { return null; }
    }

    // ---- admin: /sb skillnpc <pedestal|trainer> [info|here|auto] ----------------------------------------

    static final String USAGE = "<runic-pedestal|pet-trainer> [info|here|auto|respawn]";

    void admin(CommandSender sender, String[] a) {
        if (a.length == 0) {
            for (Spot s : spots) say(sender, "&e" + s.label + " &7(" + s.npcId + "): &f" + s.mode
                    + (s.placed != null ? " &7at &f" + fmt(s.placed) : ""));
            say(sender, "&7/sb skillnpc " + USAGE);
            return;
        }
        Spot s = find(a[0]);
        if (s == null) { say(sender, "&cWhich NPC? runic-pedestal or pet-trainer"); return; }
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        switch (sub) {
            case "here" -> {
                if (!(sender instanceof Player p)) { say(sender, "&cRun it in game, standing where it should be."); return; }
                Location l = p.getLocation();
                if (l.getWorld() == null || !l.getWorld().getName().equals("sb_" + HUB)) { say(sender, "&cStand in the Hub."); return; }
                s.at.set(String.format(Locale.ROOT, "%.1f,%.1f,%.1f,%.0f", l.getX(), l.getY(), l.getZ(), l.getYaw()));
                s.placed = null;
                if (s.mode.startsWith("auto")) s.mode = "dial position";
                tick();
                say(sender, "&aThe " + s.label + " now stands at " + fmt(l) + " &7(dial skills.npc.*-at; 'auto' to undo).");
            }
            case "auto" -> {
                s.at.set("");
                s.placed = null;
                if (s.mode.startsWith("dial")) s.mode = "auto (next to " + s.nearAnchor + ")";
                tick();
                say(sender, "&aThe " + s.label + " is placed automatically again.");
            }
            case "respawn" -> { s.placed = null; tick(); say(sender, "&aRe-placed: " + (s.placed == null ? "&cnot placed (Hub not loaded?)" : fmt(s.placed))); }
            default -> say(sender, "&e" + s.label + "&7: " + s.mode + (s.placed != null ? " at " + fmt(s.placed) : "")
                    + " &7dial-at='" + s.at.get() + "'");
        }
    }

    List<String> complete(String[] a) {
        if (a.length == 1) return List.of("runic-pedestal", "pet-trainer");
        if (a.length == 2) return List.of("info", "here", "auto", "respawn");
        return List.of();
    }

    Spot find(String arg) {
        String k = arg.toLowerCase(Locale.ROOT);
        for (Spot s : spots) if (s.label.toLowerCase(Locale.ROOT).replace(' ', '-').startsWith(k) || s.npcId.equalsIgnoreCase(k)) return s;
        if (k.startsWith("ped") || k.startsWith("rune")) return spots.isEmpty() ? null : spots.get(0);
        if (k.startsWith("tr") || k.startsWith("pet")) return spots.size() < 2 ? null : spots.get(1);
        return null;
    }

    private static String fmt(Location l) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", l.getX(), l.getY(), l.getZ());
    }

    private static void say(CommandSender s, String m) { s.sendMessage(Text.c(m)); }
}
