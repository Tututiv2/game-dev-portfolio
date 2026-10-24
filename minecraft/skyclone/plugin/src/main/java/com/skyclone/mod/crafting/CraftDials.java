package com.skyclone.mod.crafting;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every crafting tunable, editable in game with /sb dial set crafting.<key> <value>. Read at use time. */
final class CraftDials {

    final Dials.Dial<Boolean> useSacks;
    final Dials.Dial<Boolean> allowMirror;
    final Dials.Dial<Boolean> interceptTables;
    final Dials.Dial<Integer> quickCraftLevel;
    final Dials.Dial<Integer> quickCraftSlots;
    final Dials.Dial<Double> carpentryRate;
    final Dials.Dial<Boolean> vanillaCarpentryXp;
    final Dials.Dial<Integer> shiftCraftCap;
    final Dials.Dial<Integer> superCraftCap;
    final Dials.Dial<Boolean> fallbackRecipes;
    final Dials.Dial<Boolean> vanillaRecipes;
    final Dials.Dial<Boolean> carryUpgrades;
    final Dials.Dial<Integer> tradeDailyLimit;
    final Dials.Dial<Double> tradeSpreeMultiplier;
    final Dials.Dial<String> tradeSpreePerk;
    final Dials.Dial<Integer> recentSize;
    final Dials.Dial<Integer> treeDepth;
    final Dials.Dial<Boolean> craftSounds;
    final Dials.Dial<Integer> craftRateMs;
    final Dials.Dial<Boolean> furnitureEnabled;
    final Dials.Dial<Double> sellCapFactor;
    final Dials.Dial<Boolean> sellCapGated;

    CraftDials() {
        Dials d = Sky.dials();
        useSacks = d.flag("crafting.use-sacks", true,
                "Supercraft and Quick Craft also take ingredients from the player's sacks");
        allowMirror = d.flag("crafting.allow-mirror", true,
                "Shaped recipes also match when mirrored left-right (vanilla behaviour)");
        interceptTables = d.flag("crafting.intercept-tables", true,
                "Right-clicking any crafting table opens the Craft Item menu instead of the vanilla table");
        quickCraftLevel = d.integer("crafting.quick-craft-level", 3,
                "Carpentry level that unlocks the Quick Crafting slots (research: Carpentry III; the paid-rank gate is dropped)");
        quickCraftSlots = d.integer("crafting.quick-craft-slots", 3,
                "How many Quick Crafting suggestions the Craft Item menu shows (1-3)");
        carpentryRate = d.number("crafting.carpentry-rate", 0.03,
                "Carpentry XP per craft as a fraction of the ingredients' summed NPC sell value (research: 3%)");
        vanillaCarpentryXp = d.flag("crafting.vanilla-carpentry-xp", false,
                "Vanilla recipes (planks, sticks...) also grant Carpentry XP (research: most give none)");
        shiftCraftCap = d.integer("crafting.shift-craft-cap", 576,
                "Most crafts one shift-click may make at once (TUNE: 9 stacks of output is plenty)");
        superCraftCap = d.integer("crafting.supercraft-cap", 640,
                "Most crafts one Supercraft / Quick Craft click may make at once (TUNE)");
        fallbackRecipes = d.flag("crafting.fallback-recipes", true,
                "At boot, register the reference recipe for every existing item no module gave a recipe (restart to apply)");
        vanillaRecipes = d.flag("crafting.vanilla-recipes", true,
                "At boot, register the vanilla crafting recipes (planks, sticks, tools...) (restart to apply)");
        carryUpgrades = d.flag("crafting.carry-upgrades", true,
                "Upgrading a unique item through a recipe keeps its enchants, reforge, books, stars and gems");
        tradeDailyLimit = d.integer("crafting.trade-daily-limit", 640,
                "Items per trade per real day in the Trades menu (research: shares the NPC 640/day limit)");
        tradeSpreeMultiplier = d.number("crafting.trade-spree-multiplier", 10.0,
                "Trade daily-limit multiplier while the Shopping Spree mayor perk is active (research/05: x10 = 6,400, incl. NPC Trades)");
        tradeSpreePerk = d.text("crafting.trade-spree-perk", "diaz.shopping_spree",
                "Mayor perk id that multiplies the Trades daily limit (same perk the shops module uses)");
        recentSize = d.integer("crafting.recent-size", 21,
                "How many recently crafted items the Recipe Book remembers");
        treeDepth = d.integer("crafting.tree-depth", 6,
                "How many recipe steps the Raw Materials breakdown follows");
        craftSounds = d.flag("crafting.sounds", true, "Play a sound when crafting");
        craftRateMs = d.integer("crafting.craft-rate-ms", 120,
                "Minimum milliseconds between two crafts by one player (anti-spam)");
        furnitureEnabled = d.flag("crafting.furniture", true,
                "Furniture items (Carpentry unlocks) can be placed on islands where the player may build");
        sellCapFactor = d.number("crafting.sell-cap-factor", 2.0,
                "At boot, lower any crafted item's NPC sell price to this many times what its recipe inputs sell for, "
                        + "so craft-and-sell is never a coin printer (0 = off; restart or /sb crafting sellcap to apply)");
        sellCapGated = d.flag("crafting.sell-cap-gated", true,
                "The sell cap also covers recipes behind an unlock (a loop behind an unlock is still a loop)");
    }
}
