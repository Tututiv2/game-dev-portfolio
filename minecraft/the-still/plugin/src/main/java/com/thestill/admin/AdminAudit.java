package com.thestill.admin;

import com.thestill.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/**
 * Who did what to whom, and what the value was before they did it.
 *
 * <p>Shadow Prison's {@code /adminset} can set anybody's balance to anything, grant
 * any rank and empty any inventory, and it keeps no record of any of it. That is the
 * whole difference between a feature a server owner can hand to a moderator and one
 * they cannot — not because staff are suspected, but because "who gave them that"
 * has to be answerable a week later, by somebody who was not there.
 *
 * <p>Two destinations, on purpose:
 *
 * <ul>
 *   <li><b>The server log</b>, so it is in {@code latest.log} beside everything else
 *       that happened at that moment, and survives even if the database does not.
 *   <li><b>{@code admin_audit}</b>, so it can be asked a question. The question is
 *       always "what happened to this player", which is an index rather than a grep
 *       through rotated logs.
 * </ul>
 *
 * <p>Writes go through {@link Database#submit}, so an audit row costs the calling
 * tick nothing and lands on the writer thread with everything else. It is
 * fire-and-forget deliberately: an audit write that could fail a player edit would
 * be a reason to skip auditing.
 */
public final class AdminAudit {

    /** One recorded change, newest first when listed. */
    public record Entry(long at, String adminName, String action, String before, String after) {
    }

    private final Logger log;
    private final Database database;

    public AdminAudit(Logger log, Database database) {
        this.log = log;
        this.database = database;
    }

    /**
     * Records a change.
     *
     * <p>{@code before} and {@code after} are strings rather than a typed value
     * because the interesting rows are things like {@code state din.total} and
     * {@code role donor}, and a column that can hold all of them holds all of the
     * ones nobody has written yet either.
     */
    public void record(Player admin, AdminTarget target, String action,
                       String before, String after) {
        long at = System.currentTimeMillis();
        String adminUuid = admin.getUniqueId().toString();
        String adminName = admin.getName();
        String targetUuid = target.uuid().toString();
        String targetName = target.name();

        log.info("[adminset] " + adminName + " -> " + targetName + " : " + action
                + "  " + (before == null ? "-" : before) + " -> " + (after == null ? "-" : after)
                + (target.live() ? "" : "  (offline edit)"));

        database.submit("audit:" + action, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO admin_audit
                        (at, admin_uuid, admin_name, target_uuid, target_name, action,
                         old_value, new_value)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setLong(1, at);
                statement.setString(2, adminUuid);
                statement.setString(3, adminName);
                statement.setString(4, targetUuid);
                statement.setString(5, targetName);
                statement.setString(6, action);
                statement.setString(7, before);
                statement.setString(8, after);
                statement.executeUpdate();
            }
        });
    }

    /**
     * The last {@code limit} changes made to somebody.
     *
     * <p>Async, because {@link Database#read} refuses the main thread — and reading
     * an audit trail is exactly the sort of thing that would otherwise be written as
     * a blocking call inside a click handler.
     */
    public CompletableFuture<List<Entry>> recent(UUID target, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return database.read(connection -> {
                    List<Entry> out = new ArrayList<>();
                    try (PreparedStatement statement = connection.prepareStatement("""
                            SELECT at, admin_name, action, old_value, new_value
                            FROM admin_audit WHERE target_uuid = ?
                            ORDER BY at DESC LIMIT ?
                            """)) {
                        statement.setString(1, target.toString());
                        statement.setInt(2, Math.max(1, limit));
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                out.add(new Entry(rows.getLong("at"),
                                        rows.getString("admin_name"),
                                        rows.getString("action"),
                                        rows.getString("old_value"),
                                        rows.getString("new_value")));
                            }
                        }
                    }
                    return out;
                });
            } catch (Exception e) {
                log.log(Level.WARNING, "could not read the audit trail for " + target, e);
                return List.<Entry>of();
            }
        });
    }
}
