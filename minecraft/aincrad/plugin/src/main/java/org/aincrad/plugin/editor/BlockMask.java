package org.aincrad.plugin.editor;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;

/**
 * Which blocks an operation is allowed to touch.
 *
 * <pre>
 *   stone                 only stone
 *   stone,dirt,grass_block   any of those
 *   !air                  anything that is NOT air
 *   #air                  air and cave air
 *   #solid                blocks you cannot walk through
 *   #liquid               water and lava
 *   #any or *             everything, including air
 *   oak_stairs[facing=north]   an exact block state
 * </pre>
 *
 * <p>A bare material name matches that material in <em>any</em> state, so {@code //replace oak_stairs
 * stone} catches all eight rotations. Add a {@code [state]} only when you want to be picky - that
 * default is the opposite way round from being strict, and it is the behaviour people expect.</p>
 */
public final class BlockMask {

    private sealed interface Rule permits MaterialRule, ExactRule, GroupRule {
        boolean matches(BlockData data);

        String describe();
    }

    private record MaterialRule(Material material) implements Rule {
        @Override
        public boolean matches(BlockData data) {
            return data.getMaterial() == material;
        }

        @Override
        public String describe() {
            return material.getKey().getKey();
        }
    }

    private record ExactRule(BlockData data, String text) implements Rule {
        @Override
        public boolean matches(BlockData other) {
            return other.matches(data);
        }

        @Override
        public String describe() {
            return text;
        }
    }

    private enum Group {
        ANY, AIR, SOLID, LIQUID
    }

    private record GroupRule(Group group) implements Rule {
        @Override
        public boolean matches(BlockData data) {
            return switch (group) {
                case ANY -> true;
                case AIR -> data.getMaterial().isAir();
                case SOLID -> data.getMaterial().isSolid();
                case LIQUID -> data.getMaterial() == Material.WATER || data.getMaterial() == Material.LAVA;
            };
        }

        @Override
        public String describe() {
            return "#" + group.name().toLowerCase();
        }
    }

    private final List<Rule> rules;
    private final boolean negated;
    private final String source;

    private BlockMask(List<Rule> rules, boolean negated, String source) {
        this.rules = rules;
        this.negated = negated;
        this.source = source;
    }

    /** A mask that matches everything, used when a command takes no mask. */
    public static BlockMask any() {
        return new BlockMask(List.of(new GroupRule(Group.ANY)), false, "#any");
    }

    public static BlockMask parse(String spec) throws BlockPattern.PatternException {
        if (spec == null || spec.isBlank()) {
            throw new BlockPattern.PatternException(
                    "You need to say which blocks to match. Try: stone, or !air");
        }
        String text = spec.trim();
        boolean neg = text.startsWith("!");
        if (neg) {
            text = text.substring(1).trim();
            if (text.isEmpty()) {
                throw new BlockPattern.PatternException("'!' needs something after it, like !air");
            }
        }
        List<Rule> out = new ArrayList<>();
        for (String raw : BlockPattern.splitTopLevel(text)) {
            String piece = raw.trim().toLowerCase();
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.equals("*") || piece.equals("#any")) {
                out.add(new GroupRule(Group.ANY));
            } else if (piece.equals("#air")) {
                out.add(new GroupRule(Group.AIR));
            } else if (piece.equals("#solid")) {
                out.add(new GroupRule(Group.SOLID));
            } else if (piece.equals("#liquid")) {
                out.add(new GroupRule(Group.LIQUID));
            } else if (piece.startsWith("#")) {
                throw new BlockPattern.PatternException("'" + piece
                        + "' is not a group I know. The groups are #any, #air, #solid, #liquid.");
            } else if (piece.contains("[")) {
                out.add(new ExactRule(BlockPattern.parseOne(piece), piece));
            } else {
                String nameOnly = piece.contains(":") ? piece.substring(piece.indexOf(':') + 1) : piece;
                Material m = Material.matchMaterial(nameOnly);
                if (m == null || !m.isBlock()) {
                    // Route through the pattern parser so the wording of "unknown block" errors is
                    // written in exactly one place.
                    BlockPattern.parseOne(piece);
                    throw new BlockPattern.PatternException("'" + nameOnly + "' is not a block.");
                }
                out.add(new MaterialRule(m));
            }
        }
        if (out.isEmpty()) {
            throw new BlockPattern.PatternException("You need to say which blocks to match.");
        }
        return new BlockMask(out, neg, spec.trim());
    }

    public boolean matches(BlockData data) {
        for (Rule r : rules) {
            if (r.matches(data)) {
                return !negated;
            }
        }
        return negated;
    }

    public String source() {
        return source;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (negated) {
            sb.append("anything except ");
        }
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) {
                sb.append(i == rules.size() - 1 ? " or " : ", ");
            }
            sb.append(rules.get(i).describe());
        }
        return sb.toString();
    }
}
