package com.auraqol.feature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.auraqol.skyblock.SkyblockText;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.level.GameType;

/**
 * Where-am-I state shared by the SkyBlock features: on Hypixel or not, on
 * SkyBlock or not, and the sidebar as plain lines.
 *
 * <p>The sidebar is read the way vanilla draws it: each score's owner is a
 * throwaway name and the text you see is the owner's TEAM prefix + name +
 * suffix, so {@code PlayerTeam.formatNameForTeam} is applied to every entry.
 * It is refreshed twice a second, not per frame - the purse changes at the
 * speed the server redraws it, and walking a scoreboard sixty times a second
 * would buy nothing.
 */
final class Skyblock {

    /** Read by the price thread, written by the client thread. */
    private static volatile boolean onHypixel;
    private static volatile boolean inSkyblock;
    private static volatile List<String> sidebar = List.of();
    private static volatile List<String> tab = List.of();
    private static int tick;
    private static boolean registered;

    private Skyblock() {
    }

    static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++tick % 10 != 0) {
                return;
            }
            try {
                refresh(mc);
            } catch (Throwable t) {
                inSkyblock = false;
                sidebar = List.of();
            }
        });
    }

    static boolean onHypixel() {
        return onHypixel;
    }

    static boolean inSkyblock() {
        return inSkyblock;
    }

    static List<String> sidebar() {
        return sidebar;
    }

    /**
     * The TAB player list as plain lines, in the order vanilla draws it
     * (column by column, top to bottom). Hypixel builds its widgets - Area,
     * Commissions, Pickaxe Ability, Powders - out of these fake entries.
     */
    static List<String> tabLines() {
        return tab;
    }

    /** Vanilla's PlayerTabOverlay.PLAYER_COMPARATOR (private there), same four keys in the same order. */
    private static final Comparator<PlayerInfo> TAB_ORDER = Comparator
            .<PlayerInfo>comparingInt(p -> -p.getTabListOrder())
            .thenComparingInt(p -> p.getGameMode() == GameType.SPECTATOR ? 1 : 0)
            .thenComparing(p -> p.getTeam() == null ? "" : p.getTeam().getName())
            .thenComparing(p -> p.getProfile().name(), String::compareToIgnoreCase);

    /** Vanilla's sidebar order: score high to low, then owner name. */
    private static final Comparator<PlayerScoreEntry> SIDEBAR_ORDER = Comparator
            .comparingInt(PlayerScoreEntry::value).reversed()
            .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

    private static void refresh(Minecraft mc) {
        ServerData sd = mc.getCurrentServer();
        onHypixel = sd != null && sd.ip != null && sd.ip.toLowerCase(Locale.ROOT).contains("hypixel");
        if (mc.level == null || mc.player == null) {
            inSkyblock = false;
            sidebar = List.of();
            tab = List.of();
            return;
        }
        tab = readTab(mc);
        Scoreboard sb = mc.level.getScoreboard();
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) {
            inSkyblock = false;
            sidebar = List.of();
            return;
        }
        // Sorted the way the sidebar is drawn (the slayer panel reads "the
        // line after Slayer Quest", which only means something in draw order).
        List<PlayerScoreEntry> entries = new ArrayList<>(sb.listPlayerScores(obj));
        entries.sort(SIDEBAR_ORDER);
        List<String> lines = new ArrayList<>(16);
        for (PlayerScoreEntry e : entries) {
            if (e.isHidden()) {
                continue;
            }
            PlayerTeam team = sb.getPlayersTeam(e.owner());
            String line = PlayerTeam.formatNameForTeam(team, e.ownerName()).getString();
            // A fixed right-hand number replacement belongs to the same row.
            if (e.display() != null && !e.display().getString().isBlank()) {
                line = line + " " + e.display().getString();
            }
            lines.add(line);
        }
        sidebar = List.copyOf(lines);
        // A Shadow Prison server that said WELCOME is never SkyBlock, whatever
        // its sidebar title: every Hypixel feature gates on this flag, and they
        // must never activate there (Shadow Live has the server's real numbers).
        inSkyblock = !ShadowLive.active() && SkyblockText.isSkyblockTitle(obj.getDisplayName().getString());
    }

    private static List<String> readTab(Minecraft mc) {
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) {
            return List.of();
        }
        List<PlayerInfo> infos = new ArrayList<>(conn.getListedOnlinePlayers());
        infos.sort(TAB_ORDER);
        int n = Math.min(80, infos.size()); // vanilla draws at most 80
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            PlayerInfo p = infos.get(i);
            Component name = p.getTabListDisplayName();
            out.add(name != null ? name.getString()
                    : PlayerTeam.formatNameForTeam(p.getTeam(), Component.literal(p.getProfile().name())).getString());
        }
        return List.copyOf(out);
    }

    /**
     * Every hover text inside a chat component, depth first. The [Sacks]
     * breakdown lives in the hover of one child of the message, not in the
     * visible text.
     */
    static void hoverTexts(Component c, List<String> out) {
        if (c == null || out.size() > 16) {
            return;
        }
        HoverEvent he = c.getStyle().getHoverEvent();
        if (he instanceof HoverEvent.ShowText st && st.value() != null) {
            // The same hover can sit on several parts of one message ("+1,280"
            // and " items"); reading it twice would count every sack line twice.
            String text = st.value().getString();
            if (!out.contains(text)) {
                out.add(text);
            }
        }
        for (Component sib : c.getSiblings()) {
            hoverTexts(sib, out);
        }
    }
}
