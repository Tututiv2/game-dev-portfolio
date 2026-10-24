package org.aincrad.plugin.editor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * What to place. Accepts a single block or a weighted mixture, and keeps the original text so
 * messages can echo back exactly what the player typed.
 *
 * <pre>
 *   stone                          a single block
 *   minecraft:stone                the namespace is optional everywhere
 *   oak_stairs[facing=north]       full block states are supported
 *   stone,cobblestone              50/50 mixture
 *   70%stone,30%cobblestone        weighted mixture
 *   3%glowstone,97%stone           weights are relative, they need not total 100
 * </pre>
 *
 * <p>Weights are relative rather than required to sum to 100, because insisting on 100 turns
 * "sprinkle in a bit of glowstone" into an arithmetic exercise. {@code 1%glowstone,50%stone} does the
 * obvious thing.</p>
 */
public final class BlockPattern {

    private record Entry(BlockData data, double weight, String text) {
    }

    private final List<Entry> entries;
    private final double totalWeight;
    private final String source;

    private BlockPattern(List<Entry> entries, String source) {
        this.entries = entries;
        this.source = source;
        double sum = 0;
        for (Entry e : entries) {
            sum += e.weight();
        }
        this.totalWeight = sum;
    }

    /**
     * @throws PatternException with a message written for the player, not for a log file
     */
    public static BlockPattern parse(String spec) throws PatternException {
        if (spec == null || spec.isBlank()) {
            throw new PatternException("You need to say which block. Try: stone");
        }
        List<Entry> out = new ArrayList<>();
        // Split on commas that are not inside a [state] bracket, so oak_stairs[facing=north,half=top]
        // survives intact.
        for (String part : splitTopLevel(spec)) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            double weight = 1.0;
            int pct = piece.indexOf('%');
            int bracket = piece.indexOf('[');
            // Only treat '%' as a weight marker when it comes before any [state] block, so a state
            // value that happens to contain '%' can never be mistaken for a weight.
            boolean isWeight = pct > 0 && (bracket < 0 || pct < bracket);
            if (isWeight) {
                String num = piece.substring(0, pct).trim();
                try {
                    weight = Double.parseDouble(num);
                } catch (NumberFormatException ex) {
                    throw new PatternException("'" + num + "' is not a number. Weights look like: 70%stone");
                }
                if (weight <= 0) {
                    throw new PatternException("Weights must be greater than zero, got " + num);
                }
                piece = piece.substring(pct + 1).trim();
            }
            out.add(new Entry(parseOne(piece), weight, piece));
        }
        if (out.isEmpty()) {
            throw new PatternException("You need to say which block. Try: stone");
        }
        return new BlockPattern(out, spec);
    }

    /** Parses one block, with or without a namespace or a [state] suffix. */
    static BlockData parseOne(String piece) throws PatternException {
        String text = piece.toLowerCase().trim();
        if (!text.contains(":")) {
            text = "minecraft:" + text;
        }
        try {
            return Bukkit.createBlockData(text);
        } catch (IllegalArgumentException ex) {
            String bare = text.substring(text.indexOf(':') + 1);
            int bracket = bare.indexOf('[');
            String nameOnly = bracket < 0 ? bare : bare.substring(0, bracket);
            Material guess = Material.matchMaterial(nameOnly);
            if (guess == null) {
                throw new PatternException("'" + nameOnly + "' is not a block I know. "
                        + "Names are the vanilla ones, like stone, oak_planks, polished_blackstone.");
            }
            if (!guess.isBlock()) {
                throw new PatternException(nameOnly + " is an item, not a block, so it cannot be placed.");
            }
            throw new PatternException("'" + bare + "' has a block state " + nameOnly
                    + " does not accept. Check the property names inside the [brackets].");
        }
    }

    static List<String> splitTopLevel(String spec) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : spec.toCharArray()) {
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /** Single-block patterns return the same block every time; mixtures roll. */
    public BlockData next(Random random) {
        if (entries.size() == 1) {
            return entries.get(0).data();
        }
        double roll = random.nextDouble() * totalWeight;
        double running = 0;
        for (Entry e : entries) {
            running += e.weight();
            if (roll < running) {
                return e.data();
            }
        }
        return entries.get(entries.size() - 1).data();
    }

    public boolean isSingle() {
        return entries.size() == 1;
    }

    /** The one block, for callers that need it directly. Only valid when {@link #isSingle()}. */
    public BlockData single() {
        return entries.get(0).data();
    }

    public String source() {
        return source;
    }

    /** Human description, e.g. "stone" or "a mix of stone (70%), cobblestone (30%)". */
    public String describe() {
        if (entries.size() == 1) {
            return entries.get(0).text();
        }
        StringBuilder sb = new StringBuilder("a mix of ");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Entry e = entries.get(i);
            sb.append(e.text()).append(" (")
                    .append(Math.round(100.0 * e.weight() / totalWeight)).append("%)");
        }
        return sb.toString();
    }

    /** Thrown with player-facing wording; callers send {@link Exception#getMessage()} straight to chat. */
    public static final class PatternException extends Exception {
        public PatternException(String message) {
            super(message);
        }
    }
}
