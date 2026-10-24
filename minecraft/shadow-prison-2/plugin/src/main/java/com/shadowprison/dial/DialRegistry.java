package com.shadowprison.dial;

import com.shadowprison.core.Service;
import com.shadowprison.store.Database;
import org.bukkit.Material;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Every dial in the game. Modules register in their constructors; {@link #start()} then loads the
 * owner's stored values. A stored value for a dial nobody registered is left alone in the store
 * (a module may simply be disabled this boot) — it is never deleted.
 */
public final class DialRegistry implements Service {

    private final Logger log;
    private final Database db;
    private final Map<String, Dial> dials = new LinkedHashMap<>();

    public DialRegistry(Logger log, Database db) {
        this.log = log;
        this.db = db;
    }

    public Dial number(String key, String category, String label, String description,
                       long def, long min, long max, long step, Material icon) {
        return add(new Dial(key, category, label, description, Dial.Kind.NUMBER, def, min, max, step, icon));
    }

    public Dial toggle(String key, String category, String label, String description, boolean def, Material icon) {
        return add(new Dial(key, category, label, description, Dial.Kind.TOGGLE, def ? 1 : 0, 0, 1, 1, icon));
    }

    public Dial money(String key, String category, String label, String description,
                      long defCents, long minCents, long maxCents, long stepCents, Material icon) {
        return add(new Dial(key, category, label, description, Dial.Kind.MONEY, defCents, minCents, maxCents, stepCents, icon));
    }

    /** Basis points: 10000 = 100%. */
    public Dial percent(String key, String category, String label, String description,
                        long defBp, long minBp, long maxBp, long stepBp, Material icon) {
        return add(new Dial(key, category, label, description, Dial.Kind.PERCENT, defBp, minBp, maxBp, stepBp, icon));
    }

    private Dial add(Dial dial) {
        if (dials.putIfAbsent(dial.key(), dial) != null) {
            throw new IllegalStateException("duplicate dial " + dial.key());
        }
        return dial;
    }

    @Override
    public void start() throws Exception {
        int[] applied = {0};
        db.readDuringLifecycle(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT key, value FROM dials");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Dial dial = dials.get(rs.getString(1));
                    if (dial == null) {
                        continue;
                    }
                    try {
                        dial.set(Long.parseLong(rs.getString(2)));
                        applied[0]++;
                    } catch (NumberFormatException bad) {
                        log.warning("dial " + dial.key() + " has an unreadable stored value; using default");
                    }
                }
            }
            return null;
        });
        log.info(dials.size() + " dial(s) registered, " + applied[0] + " owner value(s) applied");
    }

    /** Sets, clamps, persists, and records who did it. Returns the stored value. */
    public long set(Dial dial, long value, String who) {
        long stored = dial.set(value);
        long now = System.currentTimeMillis();
        db.submit("dial-set", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO dials(key, value, updated_by, updated_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value,
                        updated_by = excluded.updated_by, updated_at = excluded.updated_at
                    """)) {
                ps.setString(1, dial.key());
                ps.setString(2, Long.toString(stored));
                ps.setString(3, who);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
        });
        log.info("[config] " + who + " set " + dial.key() + " = " + dial.format(stored));
        return stored;
    }

    public Dial get(String key) {
        return dials.get(key);
    }

    /** Category -> dials, categories sorted, dials in registration order. */
    public Map<String, List<Dial>> byCategory() {
        Map<String, List<Dial>> out = new TreeMap<>();
        for (Dial dial : dials.values()) {
            out.computeIfAbsent(dial.category(), k -> new ArrayList<>()).add(dial);
        }
        return out;
    }

    public int size() {
        return dials.size();
    }
}
