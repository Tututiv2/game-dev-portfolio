package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The harp (research 08 section 6.3, the reference's "Melody's Harp"): a 6-row menu, seven lanes, notes falling
 * one row per beat and struck on the key row. First completion of a song grants permanent Intelligence and
 * SkyBlock XP; completing all of them grants the harpist's hair accessory. Played at the harpist NPC
 * (foraging's Lyra, the Melody counterpart) - dial {@code extras.harp.near-npc-blocks}.
 */
final class Harp {

    /** Playtest wave 5: an island's display name (a title-cased id leaked reference names like "Galatea"). */
    private static String islandName(String id) {
        if (id == null) return "";
        com.skyclone.api.world.IslandDef d = com.skyclone.api.Sky.worlds().island(id);
        return d != null && d.displayName != null ? com.skyclone.api.ui.Text.strip(d.displayName) : com.skyclone.api.ui.Text.titleCase(id.replace('_', ' '));
    }

    private Harp() {}

    static final Material[] KEYS_LIT = {Material.RED_WOOL, Material.ORANGE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL,
            Material.LIGHT_BLUE_WOOL, Material.BLUE_WOOL, Material.PURPLE_WOOL};
    static final Material[] NOTE = {Material.RED_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
            Material.LIME_TERRACOTTA, Material.LIGHT_BLUE_TERRACOTTA, Material.BLUE_TERRACOTTA, Material.PURPLE_TERRACOTTA};
    static final String[] LANE_COLOR = {"&c", "&6", "&e", "&a", "&b", "&9", "&5"};

    private static final Map<UUID, BukkitTask> RUNNING = new HashMap<>();

    static int slot(int row, int lane) { return row * 9 + 1 + lane; }

    // ---- stats ---------------------------------------------------------------------------------------------

    static double intelligence(ExtrasData d) {
        double t = 0;
        for (String id : d.songsDone) {
            Catalog.Song s = X.catalog.songs.get(id);
            if (s != null) t += s.intelligence;
        }
        return t * XDials.harpIntScale.get();
    }

    static void contribute(Player p, StatMap into) {
        double i = intelligence(X.data(p));
        if (i > 0) into.add(Stat.INTELLIGENCE, i);
    }

    // ---- where -------------------------------------------------------------------------------------------

    /** null when the player may play here, else the reason. */
    static String whereError(Player p) {
        int max = XDials.harpNearBlocks.get();
        if (max <= 0) return null;
        String npc = XDials.harpNpc.get();
        NpcDef def = Sky.npcs().def(npc);
        if (def == null) return null;               // no harpist on this server: play anywhere
        Entity e = Sky.npcs().entity(npc);
        String name = def.name == null ? "the harpist" : def.name;
        if (e == null || !e.getWorld().equals(p.getWorld()) || e.getLocation().distanceSquared(p.getLocation()) > (double) max * max)
            return "&cThe harp lives with " + name + "&c" + (def.island != null ? " &7(" + islandName(def.island) + ")" : "") + "&c. Go and play it there!";
        return null;
    }

    // ---- song list ---------------------------------------------------------------------------------------

    static void open(Player p) {
        if (!X.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        String err = whereError(p);
        if (err != null) { Msg.send(p, err); return; }
        songs(p).open(p);
    }

    static Menu songs(Player p) {
        ExtrasData d = X.data(p);
        Menu m = new Menu("Harp", 6).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (Catalog.Song s : X.catalog.songs.values()) {
            if (i >= slots.length) break;
            boolean done = d.songsDone.contains(s.id), perfect = d.songsPerfect.contains(s.id);
            List<String> lore = new ArrayList<>();
            lore.add("&7Difficulty: " + diffColor(s.difficulty) + Text.titleCase(s.difficulty));
            lore.add("&7Notes: &a" + HarpGame.parse(s.notes).size());
            lore.add("");
            lore.add("&7First completion:");
            if (s.intelligence > 0) lore.add("&8 +&b" + Text.commas(s.intelligence * XDials.harpIntScale.get()) + " " + Stat.INTELLIGENCE.label());
            lore.add("&8 +&b" + s.sbxp + " SkyBlock XP");
            lore.add("");
            Integer best = d.songBest.get(s.id);
            if (best != null) lore.add("&7Best accuracy: &a" + best + "%");
            lore.add(perfect ? "&a&lPERFECT!" : done ? "&aCompleted" : "&cNot completed");
            lore.add("");
            lore.add("&eClick to play!");
            Material mat = perfect ? Material.MUSIC_DISC_PIGSTEP : done ? Material.MUSIC_DISC_CAT : Material.MUSIC_DISC_13;
            m.set(slots[i++], Icons.of(mat, (done ? "&a" : "&e") + s.name, lore), c -> start(c.player(), s, false));
        }
        int done = d.songsDone.size(), total = X.catalog.songs.size();
        m.set(4, Icons.of(Material.NOTE_BLOCK, "&dHarp",
                "&7Notes fall down seven strings. When a", "&7note reaches the bottom row, click", "&7the key under it!",
                "", "&7Songs completed: &a" + done + "&7/&a" + total,
                "&7Perfect songs: &a" + d.songsPerfect.size() + "&7/&a" + total,
                "&7Intelligence earned: &b+" + Text.commas(intelligence(d)),
                "", "&7Complete every song for a gift", "&7from the harpist herself."));
        X.backToSkyMenu(m);
        return m;
    }

    static String diffColor(String d) {
        return switch (d == null ? "" : d) {
            case "EASY" -> "&a";
            case "MEDIUM" -> "&e";
            case "HARD" -> "&c";
            default -> "&5";
        };
    }

    // ---- one performance ---------------------------------------------------------------------------------

    /** {@code admin}: skip the where-check (admin/testing). */
    static void start(Player p, Catalog.Song song, boolean admin) {
        if (!X.ready(p)) return;
        if (!admin) {
            String err = whereError(p);
            if (err != null) { Msg.send(p, err); p.closeInventory(); return; }
        }
        stop(p);
        HarpGame g = new HarpGame(song.notes, XDials.harpMaxMistakes.get());
        Menu m = new Menu("Harp - " + song.name, 6);
        boolean[] over = {false};
        draw(m, g, song, over);
        m.onClose(pl -> {
            if (!over[0]) {
                over[0] = true;
                stop(pl);
                Msg.send(pl, "&cYou stopped playing &e" + song.name + "&c.");
            }
        });
        m.open(p);
        long ticks = Math.max(2L, Math.round(song.beatTicks * XDials.harpSpeedScale.get()));
        UUID id = p.getUniqueId();
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            if (!p.isOnline() || over[0]) { stop(id); return; }
            g.step();
            if (g.finished) { end(p, g, song, over); return; }
            draw(m, g, song, over);
        }, 20L, ticks);
        RUNNING.put(id, t);
    }

    /** The song is over (finished, failed by a miss, or failed by a wrong note). */
    private static void end(Player p, HarpGame g, Catalog.Song song, boolean[] over) {
        if (over[0]) return;
        over[0] = true;
        stop(p.getUniqueId());
        p.closeInventory();
        finish(p, g, song);
    }

    static void stop(Player p) { stop(p.getUniqueId()); }

    static void stop(UUID id) {
        BukkitTask t = RUNNING.remove(id);
        if (t != null) t.cancel();
    }

    static void stopAll() {
        for (BukkitTask t : RUNNING.values()) t.cancel();
        RUNNING.clear();
    }

    private static ItemStack key(HarpGame g, int lane) {
        boolean lit = g.noteAt(HarpGame.HIT_ROW, lane) >= 0;
        return lit ? Icons.of(KEYS_LIT[lane], LANE_COLOR[lane] + "&lCLICK!") : Icons.of(Material.QUARTZ_BLOCK, "&fKey");
    }

    private static void draw(Menu m, HarpGame g, Catalog.Song song, boolean[] over) {
        for (int row = 0; row < HarpGame.ROWS; row++) {
            for (int lane = 0; lane < HarpGame.LANES; lane++) {
                final int ln = lane;
                boolean note = g.noteAt(row, lane) >= 0;
                ItemStack icon = note ? Icons.of(NOTE[lane], LANE_COLOR[lane] + "♫") : null;
                if (row == HarpGame.HIT_ROW) m.set(slot(row, lane), icon, c -> strike(c.player(), m, g, song, ln, over));
                else m.set(slot(row, lane), icon);
            }
            m.set(row * 9, Icons.of(Material.BLACK_STAINED_GLASS_PANE, " "));
            m.set(row * 9 + 8, Icons.of(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        for (int lane = 0; lane < HarpGame.LANES; lane++) {
            final int ln = lane;
            m.set(slot(HarpGame.ROWS, lane), key(g, lane), c -> strike(c.player(), m, g, song, ln, over));
        }
        int span = g.notes.isEmpty() ? 1 : g.notes.get(g.notes.size() - 1).beat() + HarpGame.ROWS;
        int pct = Math.min(100, Math.max(0, 100 * Math.max(0, g.beat()) / Math.max(1, span)));
        m.set(45, Icons.of(Material.NOTE_BLOCK, "&d" + song.name, "&7Difficulty: " + diffColor(song.difficulty) + Text.titleCase(song.difficulty),
                g.beat() < 0 ? "&eGet ready..." : "&7Progress: &a" + pct + "%"));
        m.set(53, Icons.of(g.mistakes() == 0 ? Material.EMERALD : Material.REDSTONE, "&aHits: &f" + g.hits,
                "&cMistakes: &f" + g.mistakes() + "&7/&f" + XDials.harpMaxMistakes.get(), "&7One more than that fails the song."));
    }

    private static void strike(Player p, Menu m, HarpGame g, Catalog.Song song, int lane, boolean[] over) {
        if (g.finished || over[0]) return;
        float pitch = g.pitchAt(lane);
        HarpGame.Press r = g.press(lane);
        if (r == HarpGame.Press.HIT) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1f, pitch);
        } else if (r == HarpGame.Press.WRONG) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
        }
        if (g.finished) { end(p, g, song, over); return; }   // a wrong note can end it on the spot
        draw(m, g, song, over);
    }

    static void finish(Player p, HarpGame g, Catalog.Song song) {
        if (!X.ready(p)) return;
        ExtrasData d = X.data(p);
        int acc = g.accuracy();
        if (g.completed()) d.songBest.merge(song.id, acc, Math::max);
        if (g.failed) {
            Msg.send(p, "&c&lSONG FAILED! &7" + song.name + " &8- &7" + g.mistakes() + " mistakes, " + acc + "% accuracy.");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 0.6f);
            reopenList(p);
            return;
        }
        List<String> body = new ArrayList<>();
        body.add("&7Accuracy: &a" + acc + "% &8(" + g.hits + "/" + g.total() + ")");
        if (g.perfect()) body.add("&6&lPERFECT PLAY!");
        boolean first = !d.songsDone.contains(song.id);
        if (first) {
            d.songsDone.add(song.id);
            if (song.intelligence > 0) body.add("&b+" + Text.commas(song.intelligence * XDials.harpIntScale.get()) + " " + Stat.INTELLIGENCE.label() + " &8(permanent)");
            body.add("&b+" + song.sbxp + " SkyBlock XP");
            Sky.stats().invalidate(p);
            X.sbxp(p, "harp:" + song.id, song.sbxp, "Harp: " + song.name);
        }
        if (g.perfect() && !d.songsPerfect.contains(song.id)) d.songsPerfect.add(song.id);
        Msg.box(p, "&d&lSONG COMPLETE &7" + song.name, body);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
        if (XDials.harpFinalReward.get() && !d.harpReward && d.songsDone.size() >= X.catalog.songs.size()) {
            d.harpReward = true;
            if (Sky.items().exists("MELODY_HAIR")) {
                Sky.loot().give(p, "MELODY_HAIR", 1, LootSource.REWARD);
                Msg.send(p, "&d&lENCORE! &7The harpist gives you a strand of her hair: " + X.itemName("MELODY_HAIR") + "&7!");
            }
        }
        Sky.profiles().saveNow(p);
        reopenList(p);
    }

    private static void reopenList(Player p) {
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) songs(p).open(p); }, 30L);
    }
}
