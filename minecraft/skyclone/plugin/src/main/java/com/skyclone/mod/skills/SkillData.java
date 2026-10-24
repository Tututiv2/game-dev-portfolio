package com.skyclone.mod.skills;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-member skill state ({@code Sky.profiles().member(p, "skills", SkillData.class)}). Skills are
 * per player, never shared by a co-op. Gson-friendly; add fields, never retype them.
 */
public final class SkillData {
    /** SkillType name -> cumulative XP (keeps accumulating past the cap, so raising a cap pays out). */
    public Map<String, Double> xp = new HashMap<>();
    /** SkillType name -> unlocked cap, only for skills whose cap was raised (setCap). */
    public Map<String, Integer> caps = new HashMap<>();
    /** SkillType name -> highest level whose COIN reward has been paid (so setlevel down/up never pays twice). */
    public Map<String, Integer> paid = new HashMap<>();
    /** SkillType name -> highest level whose SkyBlock XP was handed to a REAL SkyLevel service. */
    public Map<String, Integer> sbxpPaid = new HashMap<>();
    /** Show "+4 Farming (x/y)" on the action bar. */
    public boolean xpPopups = true;
    /** Last skill that gained XP (tab widget). */
    public String lastSkill = "";
    /** Unique guests who visited this player's island (Social XP is paid once per guest). Capped. */
    public List<String> guests = new ArrayList<>();

    // ---- wave 5 ----------------------------------------------------------------------------------

    /** Runic mobs killed while carrying the Runebook accessory (its Runecrafting Wisdom = digits of this). */
    public long runebookKills;
    /** The running Pet Trainer session, or null. */
    public Training training;
    /** Coins ever spent on Pet Trainer sessions (shown in the menu). */
    public double trainerSpent;
    /** First-visit dialogue already played. */
    public boolean metPedestal, metTrainer;

    /** One Pet Trainer session: the pet that was handed over, what it will gain and when it is done. */
    public static final class Training {
        public String tier;
        public String petType;
        public String petRarity;
        public double xp;
        public long start;
        public long end;
        public double paidCoins;
        public long paidBits;
        /** "your pet finished training" already told. */
        public boolean notified;
    }
}
