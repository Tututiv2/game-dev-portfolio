package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Lyra's harp: listen to a song light up across seven strings, then play it back. The first clean
 * play of each song grants Intelligence for good (a memory game in place of the reference's
 * rhythm game; the per-song Intelligence is TUNE).
 */
final class Harp {

    private Harp() {}

    static final Material[] STRINGS = {Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE};
    static final Material[] LIT = {Material.RED_WOOL, Material.ORANGE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL,
            Material.LIGHT_BLUE_WOOL, Material.BLUE_WOOL, Material.PURPLE_WOOL};
    static final float[] PITCH = {0.6f, 0.7f, 0.8f, 0.95f, 1.1f, 1.3f, 1.5f};

    /** The notes of a song: deterministic from its id so everyone learns the same tune. */
    static int[] notes(ForagingNpcs.Song s) {
        Random r = new Random(s.id().hashCode() * 31L + s.notes());
        int[] n = new int[s.notes()];
        for (int i = 0; i < n.length; i++) n[i] = r.nextInt(7);
        return n;
    }

    /** Lyra: her log request or the harp. */
    static Menu lyra(Player p) {
        Menu m = new Menu("Lyra", 3).chrome();
        ForagingNpcs.Quest q = ForagingNpcs.QUESTS.stream().filter(x -> x.npc().equals("harpist")).findFirst().orElseThrow();
        m.set(11, Icons.of(Material.ACACIA_LOG, "&aLyra's Request", "&7She needs acacia for a new frame.", "", "&eClick to view!"),
                c -> ForagingNpcs.questMenu(c.player(), q).open(c.player()));
        m.set(15, Icons.of(Material.NOTE_BLOCK, "&dPlay the Harp", "&7Learn Lyra's songs. Each one you play", "&7cleanly grants &bIntelligence &7for good.", "", "&eClick to choose a song!"),
                c -> {
                    // wave 5: extras owns the faithful falling-note harp (13 songs, permanent Intelligence);
                    // this memory-game harp is the fallback when extras is absent (its Intelligence: foraging.harp-int-scale)
                    if (Sky.commands().exists("harp")) Sky.commands().run(c.player(), "harp");
                    else songs(c.player()).open(c.player());
                });
        return m;
    }

    static Menu songs(Player p) {
        ForagingData d = ForagingModule.data(p);
        Menu m = new Menu("Lyra's Songs", 4).chrome();
        int i = 0;
        int total = 0;
        for (ForagingNpcs.Song s : ForagingNpcs.SONGS) {
            boolean done = d.songs.contains(s.id());
            if (done) total += s.intelligence();
            List<String> lore = new ArrayList<>();
            lore.add("&7Notes: &a" + s.notes());
            lore.add("&7Reward: &b+" + Text.commas(s.intelligence() * FDials.harpIntScale.get()) + "✎ Intelligence &8(once)");
            lore.add("");
            lore.add(done ? "&aLearned! &eClick to play again." : "&eClick to play!");
            m.set(10 + i + (i >= 4 ? 5 : 0), Icons.of(done ? Material.MUSIC_DISC_CAT : Material.MUSIC_DISC_13, (done ? "&a" : "&e") + s.name(), lore),
                    c -> play(c.player(), s));
            i++;
        }
        m.set(31, Icons.of(Material.BOOK, "&bIntelligence earned: +" + Text.commas(total * FDials.harpIntScale.get()),
                "&7Watch the strings light up, then", "&7click them in the same order."));
        m.set(30, Icons.back("Lyra"), c -> lyra(c.player()).open(c.player()));
        return m;
    }

    /** One performance: listen, then repeat. */
    static void play(Player p, ForagingNpcs.Song song) {
        int[] tune = notes(song);
        Menu m = new Menu("Harp - " + song.name(), 6).chrome();
        int[] next = {0};
        boolean[] listening = {true};
        for (int col = 0; col < 7; col++) drawString(m, col, false);
        m.set(4, Icons.of(Material.NOTE_BLOCK, "&dListen...", "&7Watch the strings."));
        for (int col = 0; col < 7; col++) {
            int c0 = col;
            for (int row = 1; row <= 4; row++)
                m.set(row * 9 + 1 + col, m.get(row * 9 + 1 + col), click -> {
                    if (listening[0]) return;
                    Player pl = click.player();
                    if (tune[next[0]] != c0) {
                        Msg.error(pl, "Wrong string! Lyra winces. Try again.");
                        pl.playSound(pl.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                        songs(pl).open(pl);
                        return;
                    }
                    pl.playSound(pl.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1f, PITCH[c0]);
                    next[0]++;
                    if (next[0] >= tune.length) finish(pl, song);
                });
        }
        m.open(p);
        int ticks = Math.max(4, FDials.harpNoteTicks.get());
        new BukkitRunnable() {
            int i = -1;
            @Override public void run() {
                if (!p.isOnline() || Sky.menus().current(p) != m) { cancel(); return; }
                if (i >= 0 && i < tune.length) drawString(m, tune[i], false);
                i++;
                if (i >= tune.length) {
                    listening[0] = false;
                    m.set(4, Icons.of(Material.NOTE_BLOCK, "&aYour turn!", "&7Click the strings in the same order."), null);
                    cancel();
                    return;
                }
                drawString(m, tune[i], true);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 1f, PITCH[tune[i]]);
            }
        }.runTaskTimer(Sky.plugin(), 20L, ticks);
    }

    private static void drawString(Menu m, int col, boolean lit) {
        for (int row = 1; row <= 4; row++) {
            int slot = row * 9 + 1 + col;
            m.getInventory().setItem(slot, Icons.of(lit ? LIT[col] : STRINGS[col], lit ? "&f♪" : "&7String " + (col + 1)));
        }
    }

    private static void finish(Player p, ForagingNpcs.Song song) {
        ForagingData d = ForagingModule.data(p);
        if (d.songs.add(song.id())) {
            Sky.stats().invalidate(p);
            Sky.profiles().saveNow(p);
            Msg.box(p, "&d&lSONG LEARNED &7" + song.name(), List.of("&b+" + Text.commas(song.intelligence() * FDials.harpIntScale.get()) + "✎ Intelligence"));
        } else Msg.success(p, "Lovely! You played " + song.name() + " perfectly.");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.8f);
        songs(p).open(p);
    }
}
