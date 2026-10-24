package com.skyclone.mod.mines;

import java.util.ArrayList;
import java.util.List;

/** Per-player state of the mines module (member key "mines"). Gson-friendly: add fields, never retype. */
public final class MinesData {
    /** Deep Caverns layers this player has walked into (1..6): the lift only stops at these. */
    public List<Integer> layers = new ArrayList<>();
    /** The delver at the bottom of the Deep Caverns let this player through to the Dwarven Mines. */
    public boolean dwarvenPass;
    /** Minecart stations seen (only for the menu's "discovered" marker; every station can be ridden to). */
    public List<String> stations = new ArrayList<>();
    /** Epoch ms of the last lift / minecart ride (cooldown). */
    public long lastRide;
    /** Wave 5: epoch ms a Crystal Caverns Pass (bought from the warden at Deepking's Gateway) runs out; 0 = none. */
    public long hollowsPassUntil;

    public boolean hollowsPass(long now) { return hollowsPassUntil > now; }

    public boolean visited(int layer) { return layers.contains(layer); }

    public boolean visit(int layer) {
        if (layer < 1 || layers.contains(layer)) return false;
        layers.add(layer);
        layers.sort(Integer::compare);
        return true;
    }
}
