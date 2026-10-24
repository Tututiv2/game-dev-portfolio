package com.thestill.region;

import com.thestill.state.StateMap;

import java.util.regex.Pattern;

/**
 * A named area with arbitrary persisted state.
 *
 * <p>Regions nest. {@code depth:d7} contains {@code shaft:d7-north}, and a
 * lookup returns the most specific match, so Din pressure can be asked of the
 * level while heat is asked of the room without either system knowing the other
 * exists.
 *
 * <p>Everything that will ever be measured about an area — Din pressure, room
 * heat, the PvP flag, the Ledger — lives in {@link #state()} rather than in
 * fields here. That is what stops the next four phases from each editing this
 * class.
 */
public final class Region {

    /**
     * Lowercase, with {@code .} as the namespace separator: {@code depth.d7},
     * {@code shaft.d7-north}.
     *
     * <p>Deliberately no colon, which would read more naturally. Brigadier's
     * unquoted-string charset is {@code [a-zA-Z0-9_.+-]}, so a colon in an id
     * cannot be typed as a command argument without quoting — and an id format
     * the command line cannot express is a trap that only shows up the first
     * time someone tries to delete a region. The validator and the parser agree
     * on purpose.
     *
     * <p>Stable ids are the whole point: they are the primary key, the command
     * argument and the log line, and they must survive a region being redefined
     * or moved.
     */
    private static final Pattern VALID_ID = Pattern.compile("^[a-z0-9][a-z0-9_.+-]{0,63}$");

    private final String id;
    private final RegionType type;
    private final String parentId;
    private final Cuboid bounds;
    private final long createdAt;
    private final StateMap state;

    /** Depth in the parent chain; 0 for a root. Set by {@link RegionIndex} at link time. */
    private volatile int nesting;

    public Region(String id, RegionType type, String parentId, Cuboid bounds,
                  long createdAt, StateMap state) {
        this.id = requireValidId(id);
        this.type = type;
        this.parentId = parentId == null || parentId.isBlank() ? null : parentId;
        this.bounds = bounds;
        this.createdAt = createdAt;
        this.state = state == null ? new StateMap() : state;
    }

    public static String requireValidId(String id) {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("bad region id '" + id
                    + "' — expected lowercase letters, digits, and _ - . : (max 64 chars)");
        }
        return id;
    }

    public static boolean isValidId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }

    public String id() {
        return id;
    }

    public RegionType type() {
        return type;
    }

    public String parentId() {
        return parentId;
    }

    public Cuboid bounds() {
        return bounds;
    }

    public long createdAt() {
        return createdAt;
    }

    public StateMap state() {
        return state;
    }

    public int nesting() {
        return nesting;
    }

    void setNesting(int nesting) {
        this.nesting = nesting;
    }

    public boolean contains(int x, int y, int z) {
        return bounds.contains(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Region r && id.equals(r.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id + " (" + type.id() + ", " + bounds + ")";
    }
}
