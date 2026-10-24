package com.skyclone.mod.shops;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every tunable of the shops module. Read at use time. */
final class ShopDials {

    private ShopDials() {}

    static Dials.Dial<Integer> DAILY_LIMIT;
    static Dials.Dial<Integer> SPREE_MULT;
    static Dials.Dial<String> SPREE_PERK;
    static Dials.Dial<Double> SELL_CAP;
    static Dials.Dial<Integer> BUYBACK_SIZE;
    static Dials.Dial<Integer> BUYBACK_MINUTES;
    static Dials.Dial<Double> MIN_MARKUP;
    static Dials.Dial<Boolean> USE_SACKS;
    static Dials.Dial<Boolean> REMOTE_TRADE;
    static Dials.Dial<Boolean> REMOTE_UNSTATIONED;
    static Dials.Dial<String> CONFIRM_RARITY;
    static Dials.Dial<Boolean> GREET_FIRST;
    static Dials.Dial<Integer> MAX_CUSTOM;
    static Dials.Dial<Integer> CLICK_MS;
    static Dials.Dial<Double> SHADY_PCT;
    static Dials.Dial<Double> CROOKED_PCT;
    static Dials.Dial<Double> SEAL_PCT;
    static Dials.Dial<Boolean> TOWNSFOLK;
    static Dials.Dial<Boolean> NPCS;
    static Dials.Dial<Double> PRICE_SCALE;
    static Dials.Dial<Boolean> SELL_ENABLED;
    static Dials.Dial<Boolean> BUYBACK_ANYWHERE;
    static Dials.Dial<Boolean> RECIPES;
    static Dials.Dial<Boolean> REQUIREMENTS;
    // wave 5
    static Dials.Dial<Boolean> HUNTING_DISCOUNTS;
    static Dials.Dial<String> EMISSARY_DISCOUNT;
    static Dials.Dial<Boolean> ABIPHONE_REMOTE;
    static Dials.Dial<Boolean> DIRECTORY_EXTERNAL;
    static Dials.Dial<Boolean> DIRECTORY_YIELDED;
    static Dials.Dial<Boolean> BUYBACK_DUPE_SCAN;

    static void register() {
        Dials d = Sky.dials();
        DAILY_LIMIT = d.integer("shops.daily-limit", 640,
                "Items of one kind a player may buy from one merchant per UTC day (research 05: 640).");
        SPREE_MULT = d.integer("shops.spree-multiplier", 10,
                "Daily-limit multiplier while the Shopping Spree mayor perk is active (research 05: x10 = 6,400).");
        SPREE_PERK = d.text("shops.spree-perk", "diaz.shopping_spree",
                "Mayor perk id that multiplies the daily limit (TUNE: confirm against the mayors hand-off).");
        SELL_CAP = d.number("shops.sell-cap", 500_000_000,
                "Most coins one player may earn selling to merchants per UTC day (research 05: 500M).");
        BUYBACK_SIZE = d.integer("shops.buyback-size", 10, "Recent sales kept for buyback (research 05 clone rule: 10).");
        BUYBACK_MINUTES = d.integer("shops.buyback-minutes", 15, "Minutes a sale stays in buyback (research 05 clone rule: 15).");
        MIN_MARKUP = d.number("shops.min-markup", 1.0,
                "A merchant never sells an item for less than its NPC sell price x this (anti coin-printer floor; values below 1 act as 1).");
        USE_SACKS = d.flag("shops.use-sacks", true, "Item costs may be paid from sacks as well as the inventory.");
        REMOTE_TRADE = d.flag("shops.remote-trade", false,
                "Let players trade with ANY merchant from /merchants, not only by visiting them.");
        REMOTE_UNSTATIONED = d.flag("shops.remote-unstationed", true,
                "Merchants whose NPC has no place in the world yet can be traded with from /merchants (on islands you have visited).");
        CONFIRM_RARITY = d.text("shops.confirm-rarity", "EPIC",
                "Selling a unique item of this rarity or higher asks for confirmation first (COMMON..MYTHIC, or NONE).");
        GREET_FIRST = d.flag("shops.greet-first-time", true, "A merchant introduces itself the first time a player clicks it.");
        MAX_CUSTOM = d.integer("shops.max-custom-amount", 640, "Most items one custom-amount purchase may buy.");
        CLICK_MS = d.integer("shops.click-interval-ms", 120, "Minimum milliseconds between two trades by one player (spam guard).");
        SHADY_PCT = d.number("shops.discount-shady-ring", 1, "Shop discount (percent) for the Shady Ring (research 06: 1%).");
        CROOKED_PCT = d.number("shops.discount-crooked-artifact", 2, "Shop discount (percent) for the Crooked Artifact (research 06: 2%).");
        SEAL_PCT = d.number("shops.discount-seal", 3, "Shop discount (percent) for the Seal of the Family (research 06: 3%).");
        TOWNSFOLK = d.flag("shops.townsfolk", true, "Spawn the talk-only hub townsfolk (needs anchors npc.lore.N). Restart to apply.");
        NPCS = d.flag("shops.npcs", true, "Station merchant NPCs at all (off = shops only through /merchants). Restart to apply.");
        PRICE_SCALE = d.number("shops.price-scale", 1.0,
                "Multiplier on every listed coin price (the anti-arbitrage floor still applies).");
        SELL_ENABLED = d.flag("shops.selling", true, "Players may sell items to merchants.");
        BUYBACK_ANYWHERE = d.flag("shops.buyback-anywhere", true,
                "/buyback (and the directory's Buyback button) work away from merchants. Off = only inside a shop.");
        RECIPES = d.flag("shops.register-recipes", true,
                "Register every stock line as an NPC_SHOP recipe (recipe viewer 'Sold by', bazaar ceilings). Restart to apply.");
        REQUIREMENTS = d.flag("shops.requirements", true,
                "Enforce shop / line requirements (faction envoys trade only with their faction, keys need reputation).");
        HUNTING_DISCOUNTS = d.flag("shops.hunting-discounts", true,
                "Hunting attributes Builder Discount / Bartender Discount take their percent off those merchants' coin prices.");
        EMISSARY_DISCOUNT = d.text("shops.emissary-discount", Pricing.DEFAULT_EMISSARY,
                "Envoy Leviathan keys: faction reputation:percent off the coin price, like crimson.emissary-discount (reference Emissary 1000:5/3000:10/7000:15/12000:20; empty = none)");
        ABIPHONE_REMOTE = d.flag("shops.abiphone-remote", true,
                "A merchant in your Abiphone contacts trades from /merchants (a phone call), not only in person.");
        DIRECTORY_EXTERNAL = d.flag("shops.directory-external", true,
                "List merchants other modules run (Community Shop, medal exchange, faction quartermaster...) in the Merchant Directory.");
        DIRECTORY_YIELDED = d.flag("shops.directory-yielded", true,
                "List merchants whose spot another module's NPC holds (Librarian, pelt shops...) in the Merchant Directory.");
        BUYBACK_DUPE_SCAN = d.flag("shops.buyback-dupe-scan", true,
                "Register buyback as a core dupe-scan source, so a unique item both in buyback and in an inventory is caught. Restart to apply.");
    }
}
