package com.auraqol.shadow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A profile RESPONSE arranged for the card: rows grouped by {@code group} in a
 * fixed order, unknown groups under "Other" at the end. Pure.
 *
 * <p>The rows arrive already privacy-filtered and already formatted by the
 * server; this only arranges them. Nothing here parses a value - the card
 * draws exactly the strings that were sent (law 7: the client never computes
 * from them).
 */
public final class ProfileCard {

    /** Protocol group ids in drawing order, with their headings. */
    private static final Map<String, String> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("rank", "Rank");
        GROUPS.put("stats", "Stats");
        GROUPS.put("skills", "Skills");
        GROUPS.put("contracts", "Contracts");
        GROUPS.put("gang", "Gang");
        GROUPS.put("playtime", "Playtime");
        GROUPS.put("balance", "Balance");
    }

    public record Line(String label, String value) {
    }

    public record Section(String title, List<Line> lines) {
    }

    public record Card(String name, String uuid, boolean online, List<Section> sections) {
    }

    private ProfileCard() {
    }

    public static Card of(Msg.Response r) {
        Map<String, List<Line>> by = new LinkedHashMap<>();
        for (String g : GROUPS.keySet()) {
            by.put(g, new ArrayList<>());
        }
        List<Line> other = new ArrayList<>();
        for (Msg.Row row : r.rows()) {
            String g = row.group() == null ? "" : row.group().toLowerCase(Locale.ROOT);
            Line line = new Line(row.label() == null ? "" : row.label(), row.value() == null ? "" : row.value());
            List<Line> into = by.get(g);
            (into != null ? into : other).add(line);
        }
        List<Section> sections = new ArrayList<>();
        for (Map.Entry<String, List<Line>> e : by.entrySet()) {
            if (!e.getValue().isEmpty()) {
                sections.add(new Section(GROUPS.get(e.getKey()), List.copyOf(e.getValue())));
            }
        }
        if (!other.isEmpty()) {
            sections.add(new Section("Other", List.copyOf(other)));
        }
        return new Card(r.name(), r.uuid(), r.online(), List.copyOf(sections));
    }
}
