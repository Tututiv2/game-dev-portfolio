package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Ringtones (the reference phone's "Maximum Discs"): every song the player has completed on the harp can be
 * loaded into one of the phone's ringtone slots, and the active one plays on every call in and out.
 */
final class Ringtones {

    private Ringtones() {}

    /** Plays the first {@code count} notes of a ringtone (a song id), or the classic two-tone ring. */
    static void play(Player p, String songId, int count) {
        Catalog.Song s = songId == null ? null : X.catalog.songs.get(songId);
        List<HarpGame.Note> notes = s == null ? List.of() : HarpGame.parse(s.notes);
        if (notes.isEmpty()) {
            for (int i = 0; i < 4; i++) {
                final float pitch = i % 2 == 0 ? 1.4f : 1.2f;
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                    if (p.isOnline()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, pitch);
                }, i * 4L + (i >= 2 ? 6 : 0));
            }
            return;
        }
        int first = notes.get(0).beat();
        for (int i = 0; i < Math.min(count, notes.size()); i++) {
            HarpGame.Note n = notes.get(i);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (p.isOnline()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HARP, 0.8f, n.pitch());
            }, (n.beat() - first) * 3L);
        }
    }

    static Menu menu(Player p, Catalog.Phone ph) {
        ExtrasData d = X.data(p);
        Menu m = new Menu("Ringtones", 6).chrome();
        m.set(4, Icons.of(Material.JUKEBOX, "&aRingtones",
                "&7Songs you completed on the harp can", "&7be loaded as ringtones.", "",
                "&7Slots used: &a" + d.ringtones.size() + "&7/&a" + ph.discs,
                "&7Active: &e" + (X.catalog.songs.containsKey(d.ringtone) ? X.catalog.songs.get(d.ringtone).name : "Classic Ring")));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        m.set(slots[i++], Icons.of(Material.BELL, (d.ringtone.isEmpty() ? "&a" : "&e") + "Classic Ring",
                "&7The ring every phone ships with.", "", d.ringtone.isEmpty() ? "&aActive" : "&eClick to use!"), c -> {
                    X.data(c.player()).ringtone = "";
                    play(c.player(), "", 4);
                    menu(c.player(), ph).open(c.player());
                });
        for (Catalog.Song s : X.catalog.songs.values()) {
            if (i >= slots.length) break;
            boolean learned = d.songsDone.contains(s.id);
            boolean loaded = d.ringtones.contains(s.id);
            boolean active = s.id.equals(d.ringtone);
            List<String> l = new ArrayList<>();
            if (!learned) {
                l.add("&cComplete this song on the harp first.");
                m.set(slots[i++], Icons.of(Material.GRAY_DYE, "&c" + s.name, l));
                continue;
            }
            l.add(loaded ? "&aLoaded" : "&7Not loaded");
            l.add("");
            if (loaded) {
                l.add(active ? "&aActive" : "&eLeft-click to use!");
                l.add("&cRight-click to unload.");
            } else {
                l.add("&eClick to load!");
            }
            m.set(slots[i++], Icons.of(active ? Material.MUSIC_DISC_PIGSTEP : loaded ? Material.MUSIC_DISC_CAT : Material.MUSIC_DISC_13,
                    (active ? "&a" : "&e") + s.name, l), c -> {
                        ExtrasData dd = X.data(c.player());
                        if (dd.ringtones.contains(s.id)) {
                            if (c.right()) {
                                dd.ringtones.remove(s.id);
                                if (s.id.equals(dd.ringtone)) dd.ringtone = "";
                            } else {
                                dd.ringtone = s.id;
                                play(c.player(), s.id, 8);
                            }
                        } else {
                            if (dd.ringtones.size() >= ph.discs) { Msg.error(c.player(), "All your ringtone slots are full! (" + ph.discs + ")"); return; }
                            dd.ringtones.add(s.id);
                        }
                        menu(c.player(), ph).open(c.player());
                    });
        }
        m.set(48, Icons.back("Phone"), c -> ExtrasModule.phone.open(c.player()));
        m.set(50, Icons.of(Material.NOTE_BLOCK, "&dThe Harp", "&7Learn more songs at the harpist's", "&7harp to load them as ringtones.", "", "&eClick to play!"),
                c -> Harp.open(c.player()));
        return m;
    }

    static String name(String songId) {
        Catalog.Song s = X.catalog.songs.get(songId);
        return s == null ? "Classic Ring" : Text.strip(s.name);
    }
}
