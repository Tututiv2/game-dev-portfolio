package com.skyclone.api.mob;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps up to {@link #max} of a mob alive in an area, respawning each {@link #respawnSeconds}
 * after one dies. Only ticks while a player is within {@link #activationRange} of the area.
 *
 * <p>The area is either a set of island ANCHORS (exact spawn points, cycled) or a box named by the
 * island layout ({@code area} = an area name from the generator's Layout). Exactly one should be set.
 *
 * <p>JSON: {"id":"graveyard_zombies","island":"hub","area":"graveyard","mob":"graveyard_zombie","max":12,"respawnSeconds":8}
 */
public class SpawnRule {
    public String id;
    public String island;
    /** Area name from the island Layout (a box). */
    public String area;
    /** Or explicit anchor names. */
    public List<String> anchors = new ArrayList<>();
    public String mob;
    public int max = 5;
    public double respawnSeconds = 10;
    public double activationRange = 64;
    /** Only spawns while this calendar/event condition holds (e.g. "night", "event:spooky_festival"); null = always. */
    public String condition;
}
