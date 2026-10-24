package com.skyclone.mod.chocolate;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * All-time Chocolate leaderboard. One row per profile member, pushed every few minutes and on logout; read on the
 * DB thread and drawn back on the main thread. Never blocks the main thread.
 */
final class Leaderboard {

    private Leaderboard() {}

    record Row(String uuid, String name, double allTime, int prestige) {}

    static void migrate() {
        Sky.db().migrate("chocolate", 1,
                "CREATE TABLE IF NOT EXISTS chocolate_leaderboard (profile TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, "
                        + "all_time REAL NOT NULL, prestige INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (profile, uuid))",
                "CREATE INDEX IF NOT EXISTS chocolate_leaderboard_all ON chocolate_leaderboard (all_time DESC)");
    }

    /** Main thread: snapshot, then write on the DB thread. */
    static void push(Player p) {
        if (!Choc.ready(p)) return;
        ChocData d = Choc.data(p);
        if (d.allTime <= 0) return;
        String profile = Sky.profiles().profileId(p);
        if (profile == null) return;
        String uuid = p.getUniqueId().toString(), name = p.getName();
        double all = d.allTime;
        int prestige = d.prestige;
        long now = System.currentTimeMillis();
        Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chocolate_leaderboard (profile, uuid, name, all_time, prestige, updated) VALUES (?,?,?,?,?,?) "
                            + "ON CONFLICT(profile, uuid) DO UPDATE SET name = excluded.name, all_time = excluded.all_time, "
                            + "prestige = excluded.prestige, updated = excluded.updated")) {
                ps.setString(1, profile);
                ps.setString(2, uuid);
                ps.setString(3, name);
                ps.setDouble(4, all);
                ps.setInt(5, prestige);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(t -> { Sky.log().warning("[chocolate] leaderboard push failed: " + t); return null; });
    }

    static void pushAll() {
        for (Player p : Bukkit.getOnlinePlayers()) push(p);
    }

    static void open(Player viewer) {
        push(viewer);
        UUID id = viewer.getUniqueId();
        double mine = Choc.ready(viewer) ? Choc.data(viewer).allTime : 0;
        Sky.db().query(c -> {
            List<Row> top = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, name, all_time, prestige FROM chocolate_leaderboard ORDER BY all_time DESC LIMIT 10");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) top.add(new Row(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getInt(4)));
            }
            long rank;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM chocolate_leaderboard WHERE all_time > ?")) {
                ps.setDouble(1, mine);
                try (ResultSet rs = ps.executeQuery()) { rank = rs.next() ? rs.getLong(1) + 1 : 0; }
            }
            long total;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM chocolate_leaderboard");
                 ResultSet rs = ps.executeQuery()) { total = rs.next() ? rs.getLong(1) : 0; }
            long r = rank, t = total;
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) draw(p, top, r, t, mine);
            });
            return null;
        }).exceptionally(t -> { Sky.log().warning("[chocolate] leaderboard read failed: " + t); return null; });
    }

    static void draw(Player p, List<Row> top, long rank, long total, double mine) {
        Menu m = new Menu("Chocolate Leaderboard", 4).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 20, 21, 22};
        for (int i = 0; i < top.size() && i < slots.length; i++) {
            Row r = top.get(i);
            String color = i == 0 ? "&6" : i == 1 ? "&f" : i == 2 ? "&c" : "&e";
            UUID u;
            try { u = UUID.fromString(r.uuid()); } catch (IllegalArgumentException e) { u = null; }
            List<String> lore = List.of("&7All-time Chocolate: &6" + Choc.num(r.allTime()),
                    "&7Factory: &6Chocolate Factory " + Text.roman(Math.max(1, r.prestige())));
            m.set(slots[i], u == null ? Icons.of(Material.PLAYER_HEAD, color + "#" + (i + 1) + " " + r.name(), lore)
                    : Icons.playerHead(u, color + "#" + (i + 1) + " &f" + r.name(), lore));
        }
        m.set(4, Icons.of(Material.OAK_SIGN, "&aYour Position",
                mine > 0 ? "&7Rank &e#" + Choc.num(rank) + " &7of &e" + Choc.num(total) : "&7Make some Chocolate first!",
                "&7All-time Chocolate: &6" + Choc.num(mine)));
        m.set(m.closeSlot() - 1, Icons.back("Chocolate Factory"), c -> FactoryMenus.openFactory(c.player()));
        m.open(p);
    }
}
