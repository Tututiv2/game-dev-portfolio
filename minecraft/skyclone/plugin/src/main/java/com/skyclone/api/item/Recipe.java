package com.skyclone.api.item;

import java.util.ArrayList;
import java.util.List;

/**
 * A recipe as DATA. Registered with {@code Sky.recipes().register(...)} by whichever module owns
 * the result item; the crafting module owns the 3x3 GUI, the forge module owns FORGE recipes, etc.
 *
 * <p>Grid cells are "ITEM_ID:amount" (amount defaults to 1) or null/"" for empty. Index 0..8,
 * left-to-right, top-to-bottom. Reference-game recipes take stacks per cell — "ENCHANTED_DIAMOND:32".
 *
 * <p>JSON: {"result":"ASPECT_OF_THE_END","amount":1,"type":"CRAFTING",
 *           "grid":["","ENCHANTED_EYE_OF_ENDER:16","","","ENCHANTED_EYE_OF_ENDER:16","","","ENCHANTED_DIAMOND:1",""],
 *           "unlock":"collection:ENDER_PEARL:7"}
 */
public class Recipe {

    public enum Type { CRAFTING, FORGE, NPC_SHOP, SMELTING, CARPENTRY, OTHER }

    public String id;          // optional; defaults to result + "#" + n
    public Type type = Type.CRAFTING;
    public String result;
    public int amount = 1;
    public String[] grid = new String[9];
    /** For FORGE / NPC / OTHER: plain list of "ITEM_ID:amount" inputs. Coins as "COINS:1000". */
    public List<String> inputs = new ArrayList<>();
    public boolean shapeless;
    /** Requirement key (see util.Requirements) that unlocks it, or null for always. */
    public String unlock;
    /** FORGE only: seconds to complete. */
    public long durationSeconds;
    /** Who sells/makes it, for display ("Blacksmith", "Forge"). */
    public String station;
}
