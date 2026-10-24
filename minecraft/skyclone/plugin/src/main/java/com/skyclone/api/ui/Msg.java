package com.skyclone.api.ui;

import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.List;

/** The genre's standard chat formats, so every module's messages look like one game. */
public final class Msg {

    private Msg() {}

    public static final String BAR = "&3&l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬";

    public static void send(Player p, String legacy) { p.sendMessage(Text.c(legacy)); }

    public static void send(Player p, List<String> legacy) { for (String s : legacy) send(p, s); }

    public static void broadcast(String legacy) { Bukkit.broadcast(Text.c(legacy)); }

    /** Red message + the "no" sound. */
    public static void error(Player p, String legacy) {
        send(p, "&c" + legacy);
        sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
    }

    public static void success(Player p, String legacy) {
        send(p, "&a" + legacy);
        sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f);
    }

    public static void sound(Player p, Sound s, float pitch) { p.playSound(p.getLocation(), s, 1f, pitch); }

    /**
     * The boxed announcement used for level-ups and unlocks:
     * <pre>
     * ▬▬▬▬▬▬▬▬▬▬▬▬▬▬
     *   SKILL LEVEL UP Farming IV ➜ V
     *
     *   REWARDS
     *     +4 ☘ Farming Fortune
     * ▬▬▬▬▬▬▬▬▬▬▬▬▬▬
     * </pre>
     */
    public static void box(Player p, String headline, List<String> body) {
        send(p, BAR);
        send(p, " " + headline);
        if (!body.isEmpty()) {
            send(p, "");
            for (String line : body) send(p, " " + line);
        }
        send(p, BAR);
    }

    /** "&amp;e[NPC] &amp;fName&amp;f: line", one line every 1.2s, then {@code then} (may be null). */
    public static void npc(Player p, String npcName, List<String> lines, Runnable then) {
        long delay = 0;
        for (String line : lines) {
            final String l = line;
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (!p.isOnline()) return;
                send(p, "&e[NPC] &f" + npcName + "&f: " + l);
                sound(p, Sound.ENTITY_VILLAGER_AMBIENT, 1f);
            }, delay);
            delay += 24;
        }
        if (then != null) Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) then.run(); }, delay);
    }

    /** "&amp;6&amp;lRARE DROP! &amp;9Item Name &amp;b(+120% ✯ Magic Find)" */
    public static void rareDrop(Player p, String label, String itemName, double magicFind) {
        // wave-5 integration (menus request 13): Settings -> Chat "Rare Drop Messages" off hides the line (the drop
        // itself and its sound are untouched). Defaults to shown when menus is absent.
        boolean show = true;
        try { show = Sky.service(com.skyclone.api.feature.Features.Settings.class).flag(p, "chat.rare_drops", true); }
        catch (RuntimeException ignored) { }
        if (!show) { sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f); return; }
        String mf = magicFind > 0 ? " &b(+" + Text.commasInt(magicFind) + "% ✯ Magic Find)" : "";
        send(p, label + " " + itemName + mf);
        sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
    }
}
