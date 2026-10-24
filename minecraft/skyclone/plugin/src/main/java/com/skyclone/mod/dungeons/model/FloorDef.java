package com.skyclone.mod.dungeons.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One Catacombs floor (Entrance, F1-F7, M1-M7). Loaded from {@code data/dungeons/floors.json}, which
 * {@code tools/gen/dungeons/gen_dungeons.py} writes from research 04 sec 3.2 / 3.13 / 3.16. Pure data, Gson-friendly.
 */
public final class FloorDef {
    /** "E", "F1".."F7", "M1".."M7". */
    public String id;
    /** "Entrance", "Floor I", "Master Mode Floor I". */
    public String name;
    /** 0 = Entrance, 1..7. */
    public int number;
    public boolean master;
    public int gridW = 4, gridH = 4;
    public int puzzlesMin = 1, puzzlesMax = 2;
    public int witherDoorsMin = 1, witherDoorsMax = 1;
    /** Max trap rooms (0 or 1). */
    public int trapRooms;
    /** Chance (0..1) that the trap room appears when allowed. */
    public double trapChance = 0.75;
    public int minibossRooms = 1;
    /** Required Catacombs level. */
    public int cataReq;
    /** Extra requirement key ("skill:combat:15", "dungeon_completion:F7:1") or null. */
    public String requirement;
    public int speedLimitSec = 600;
    /** Fraction of the secrets needed for full exploration score (0..1). */
    public double secretPct = 0.3;
    /** Room mob health / damage multipliers against the base mob table (TUNE). */
    public double hpScale = 1, dmgScale = 1;
    public double baseXp;
    public int bloodMobs = 6;
    public double bloodSpawnSeconds = 3;
    /** Registry key of the boss floor (agent B): "F1".."F7"; null for the Entrance. */
    public String bossKey;
    public String bossName;
    /** Fallback boss mob id (used until a BossFloor is registered). */
    public String bossMob;
    public double bossHp;
    public double bossDamage;
    /** Boss collection id for Features.Collections ("BOSS_BONZO"), null for the Entrance. */
    public String bossCollection;
    public List<String> mobPool = new ArrayList<>();
    public List<String> minibossPool = new ArrayList<>();
    public List<String> bloodPool = new ArrayList<>();
    public List<String> puzzlePool = new ArrayList<>();
    /** Visual theme id for room tiles ("crypt", "ruin", "deep", "wither"). */
    public String theme = "crypt";
    /** Room mobs per cell of a normal room. */
    public int mobsPerCell = 3;
    /** SkyBlock XP the first completion of this floor awards (TUNE). */
    public int firstCompletionSbXp = 20;
    /** Wood chest essence: a fixed Undead + Wither pair (woodWitherMax is the exact Wither amount, not a cap). */
    public int woodUndead = 2, woodWitherMax = 1;

    public boolean isEntrance() { return number == 0; }

    /** "F3" for "M3", the id itself otherwise. */
    public String baseId() { return master ? "F" + number : id; }

    /** Display "Floor III" / "Master Mode Floor III" / "Entrance". */
    public String display() { return name != null ? name : id; }
}
