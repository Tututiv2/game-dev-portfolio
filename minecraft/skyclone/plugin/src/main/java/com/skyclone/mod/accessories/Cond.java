package com.skyclone.mod.accessories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An effect condition: parts joined with '&amp;', every part must hold.
 * <pre>
 *   always | day | night | burning | dungeon | master | island:a|b | zone:a|b | season:0..3 | class:BERSERK|MAGE
 *   | event:calendar_event_id|other_id
 * </pre>
 * {@code class} is the selected dungeon class ({@code Features.Dungeons.selectedClass}), compared case-blind.
 * {@code master} is a Master Mode run ({@code Features.Dungeons.masterMode}, wave 5); {@code event} holds while any
 * of the named calendar events is running ({@code Sky.calendar().active}, wave 5: the mythological ritual, the
 * Harvest Fair); {@code mining} holds while the block being dug is one of the mining module's block ids
 * ({@code mining:gem_amber}, {@code mining:mithril_gray|titanium}; wave 5, see {@link MineTargets}).
 * Pure: it asks a {@link Ctx} for the facts, so it is unit-testable without a server.
 */
final class Cond {

    interface Ctx {
        boolean night();
        boolean burning();
        boolean inDungeon();
        String island();
        String zone();
        int season();
        /** The selected dungeon class id (BERSERK, HEALER, MAGE, TANK, ARCHER), or null if unknown. */
        default String dungeonClass() { return null; }
        /** In a Master Mode dungeon run (wave 5). */
        default boolean masterMode() { return false; }
        /** Is this calendar event running right now (wave 5)? */
        default boolean event(String eventId) { return false; }
        /** The mining module's id of the block being dug right now (MineTargets), or null (wave 5). */
        default String miningBlock() { return null; }
    }

    static final Cond ALWAYS = new Cond(List.of(), "always");

    private record Part(String kind, Set<String> values) {}

    private final List<Part> parts;
    final String text;
    /** Unknown kinds make the condition never true and are reported by the self-test. */
    final boolean valid;

    private Cond(List<Part> parts, String text) {
        this.parts = parts;
        this.text = text;
        boolean ok = true;
        for (Part p : parts) {
            switch (p.kind) {
                case "day", "night", "burning", "dungeon", "master", "island", "zone", "season", "class", "event", "mining" -> { }
                default -> ok = false;
            }
        }
        this.valid = ok;
    }

    static Cond parse(String s) {
        if (s == null || s.isBlank() || s.equals("always")) return ALWAYS;
        List<Part> parts = new ArrayList<>();
        for (String raw : s.split("&")) {
            String p = raw.trim();
            if (p.isEmpty() || p.equals("always")) continue;
            int c = p.indexOf(':');
            if (c < 0) parts.add(new Part(p, Set.of()));
            else {
                String kind = p.substring(0, c);
                String vals = kind.equals("class") ? p.substring(c + 1).toUpperCase(java.util.Locale.ROOT) : p.substring(c + 1);
                parts.add(new Part(kind, new HashSet<>(Arrays.asList(vals.split("\\|")))));
            }
        }
        return new Cond(parts, s);
    }

    boolean test(Ctx ctx) {
        if (!valid) return false;
        for (Part p : parts) {
            boolean ok = switch (p.kind) {
                case "day" -> !ctx.night();
                case "night" -> ctx.night();
                case "burning" -> ctx.burning();
                case "dungeon" -> ctx.inDungeon();
                case "master" -> ctx.inDungeon() && ctx.masterMode();
                case "event" -> {
                    boolean any = false;
                    for (String id : p.values) if (ctx.event(id)) { any = true; break; }
                    yield any;
                }
                case "island" -> ctx.island() != null && p.values.contains(ctx.island());
                case "zone" -> ctx.zone() != null && p.values.contains(ctx.zone());
                case "season" -> p.values.contains(String.valueOf(ctx.season()));
                case "mining" -> ctx.miningBlock() != null && p.values.contains(ctx.miningBlock());
                case "class" -> {
                    String cls = ctx.dungeonClass();
                    yield cls != null && p.values.contains(cls.toUpperCase(java.util.Locale.ROOT));
                }
                default -> false;
            };
            if (!ok) return false;
        }
        return true;
    }

    boolean always() { return parts.isEmpty(); }

    /** True when one of the parts is of this kind (e.g. "class": the stats change when the class does). */
    boolean uses(String kind) {
        for (Part p : parts) if (p.kind.equals(kind)) return true;
        return false;
    }
}
