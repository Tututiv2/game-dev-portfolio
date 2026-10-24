package com.skyclone.mod.accessories;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-member persistent state ({@code Sky.profiles().member(p, "accessories", AccData.class)}).
 * Gson-friendly: maps, lists, primitives, strings. Items are stored as base64 of
 * {@code ItemStack.serializeAsBytes()} so every reforge / recomb / enrichment survives.
 *
 * <p>Never retype a field (CORE_GUIDE trap 3): add new fields instead.
 */
public class AccData {
    /** Bag slot index ("0".."280") -> base64 item. */
    public Map<String, String> bag = new HashMap<>();
    /** "NECKLACE" / "CLOAK" / "BELT" / "GLOVES" -> base64 item. */
    public Map<String, String> equipment = new HashMap<>();

    /** Selected power id ("" = none). */
    public String power = "";
    /** Stone powers learned (power ids). Starter powers need no learning. */
    public List<String> learned = new ArrayList<>();
    /** Tuning: stat name -> points. */
    public Map<String, Integer> tuning = new HashMap<>();
    /** Saved tuning templates: "1".."5" -> stat -> points. */
    public Map<String, Map<String, Integer>> templates = new HashMap<>();
    /** How many template slots are unlocked (the first is free). */
    public int templateSlots = 1;

    /** Bag slot pairs bought from the Bagwright. */
    public int boughtPairs;
    /** One-time redstone exchange (+4 slots) done. */
    public boolean redstoneExchange;
    /** A Rift Prism was consumed (+11 Accessory Power for good). */
    public boolean riftPrism;
    /** Highest Accessory Power ever reached (SkyBlock XP is paid once per point). */
    public int highestMp;
    /** Stone powers already paid SkyBlock XP for. */
    public List<String> powerXpPaid = new ArrayList<>();

    /** Personal compactor inputs and deletor items (item ids). */
    public List<String> compactor = new ArrayList<>();
    public List<String> deletor = new ArrayList<>();

    /** Kill counter for the blood-god crest family. */
    public long bloodCounter;

    /**
     * Wave 5: what the Carnival Mask Bag / New Year Cake Bag hold. Kind ("mask" / "cake") -> slot ("0".."44") ->
     * base64 item, like {@link #bag}. Lives here (not on the bag item) so the contents survive the item being moved
     * between the Accessory Bag and the inventory.
     */
    public Map<String, Map<String, String>> keepBags = new HashMap<>();
}
