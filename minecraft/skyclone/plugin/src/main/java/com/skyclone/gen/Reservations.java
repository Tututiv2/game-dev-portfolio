package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Reserve a box per building first, then build inside it." Generators claim each structure's
 * box here BEFORE writing it; a claim that overlaps an earlier one is refused, so two buildings
 * can never grow into each other. Use only the X/Z footprint ({@code xzOnly}) for buildings on
 * terrain, full 3D for stacked things.
 *
 * <pre>
 *   Reservations res = new Reservations();
 *   if (res.reserve("bank", house.reserve())) house.build(canvas);
 *   ...
 *   layout.data("reservations", res.summary());   // shows up in the preview report
 * </pre>
 */
public final class Reservations {

    private final Map<String, Box> boxes = new LinkedHashMap<>();
    private final List<String> refused = new ArrayList<>();
    private final boolean xzOnly;

    public Reservations() { this(true); }

    public Reservations(boolean xzOnly) { this.xzOnly = xzOnly; }

    /** Claim a box; false (and nothing claimed) if it overlaps an existing claim or the name is taken. */
    public boolean reserve(String name, Box box) {
        if (boxes.containsKey(name)) { refused.add(name + " (name already reserved)"); return false; }
        String hit = overlap(box);
        if (hit != null) { refused.add(name + " overlaps " + hit); return false; }
        boxes.put(name, box);
        return true;
    }

    /** Name of the first claim that {@code box} would overlap, or null. */
    public String overlap(Box box) {
        for (Map.Entry<String, Box> e : boxes.entrySet()) {
            Box o = e.getValue();
            boolean xz = box.minX() <= o.maxX() && box.maxX() >= o.minX() && box.minZ() <= o.maxZ() && box.maxZ() >= o.minZ();
            if (xz && (xzOnly || box.minY() <= o.maxY() && box.maxY() >= o.minY())) return e.getKey();
        }
        return null;
    }

    /** Is this column inside any claim (grown by {@code margin})? Handy as a forest keep-out. */
    public boolean covers(int x, int z, int margin) {
        for (Box o : boxes.values())
            if (x >= o.minX() - margin && x <= o.maxX() + margin && z >= o.minZ() - margin && z <= o.maxZ() + margin) return true;
        return false;
    }

    public Map<String, Box> boxes() { return boxes; }

    public List<String> refused() { return refused; }

    public String summary() {
        return boxes.size() + " reserved" + (refused.isEmpty() ? "" : "; REFUSED: " + String.join("; ", refused));
    }
}
