package com.skyclone.mod.soulflow;

import java.util.HashMap;
import java.util.Map;

/** Per-player Soulstream state, member key {@code soulflow}. Gson-friendly; add fields, never retype them. */
public final class SoulflowData {
    /** Internalized Soulstream (whole units). */
    public long pool;
    /** Overflow Mana held (spent once ordinary mana is short). */
    public double overflow;
    /** Toggles: "overflow" (accessory conversion) and every drain id with toggle=true. Missing = the drain's default. */
    public Map<String, Boolean> toggles = new HashMap<>();
    /** Lifetime numbers for the menu. */
    public long gained;
    public long spent;
    public long converted;

    public SoulflowData() {}
}
