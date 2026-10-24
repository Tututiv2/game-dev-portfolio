package com.skyclone.mod.dungeons.ext;

import com.skyclone.mod.dungeons.model.FloorDef;
import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the reward hook needs about a COMPLETED run (passed to {@link RewardHook#onComplete}). Read-only snapshot,
 * except {@link #claimSeconds}, which a hook may raise to keep the run open while players open chests.
 */
public final class RunResult {

    public final String runId;
    public final FloorDef floor;
    public final int score;
    public final String grade;
    public final int skill, exploration, speed, bonus;
    public final long seconds;
    public final List<UUID> members;
    /** uuid -> class key ("healer"...). */
    public final Map<UUID, String> classes = new LinkedHashMap<>();
    /** uuid -> deaths this run. */
    public final Map<UUID, Integer> deaths = new LinkedHashMap<>();
    /** uuid -> class milestone reached (0..9). */
    public final Map<UUID, Integer> milestones = new LinkedHashMap<>();
    public final int secretsFound, totalSecrets, cryptsOpened;
    public final boolean mimicKilled;
    /** Where chests should appear (the arena's "chests" point; the blood room for the Entrance). */
    public final Location chestSpot;
    /** Seconds the run stays open after completion before everyone is sent back (hooks may raise it). */
    public int claimSeconds;

    public RunResult(String runId, FloorDef floor, int score, String grade, int skill, int exploration, int speed, int bonus,
                     long seconds, List<UUID> members, int secretsFound, int totalSecrets, int cryptsOpened, boolean mimicKilled,
                     Location chestSpot, int claimSeconds) {
        this.runId = runId; this.floor = floor; this.score = score; this.grade = grade;
        this.skill = skill; this.exploration = exploration; this.speed = speed; this.bonus = bonus;
        this.seconds = seconds; this.members = List.copyOf(members);
        this.secretsFound = secretsFound; this.totalSecrets = totalSecrets; this.cryptsOpened = cryptsOpened;
        this.mimicKilled = mimicKilled; this.chestSpot = chestSpot; this.claimSeconds = claimSeconds;
    }
}
