package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-player Bazaar state (member key "bazaar"): settings, favourites, recently viewed and lifetime stats.
 * Orders themselves live in the database, not here, because other players fill them while you are offline.
 * Fields are only ever ADDED (core: retyping a field poisons the key).
 */
public final class PlayerData {

    public static final String KEY = "bazaar";

    // settings
    public boolean notifyFills = true;
    public boolean notifyPartial = true;
    public boolean priceWarnings = true;
    public boolean claimToSacks = true;
    public boolean sellSacksInSellAll = false;
    public boolean sounds = true;
    /** Ask before any single Instant Buy costing more than this many coins (0 = never). */
    public double confirmAbove = 1_000_000;

    // shortcuts
    public List<String> favourites = new ArrayList<>();
    public List<String> recent = new ArrayList<>();

    // lifetime stats (tenths for coins)
    public long coinsSpent;
    public long coinsEarned;
    public long taxPaid;
    public long itemsBought;
    public long itemsSold;
    public long trades;
    public long ordersPlaced;
    public long ordersFilled;

    // price-warning cooldowns (epoch millis)
    public long warnBuyUntil;
    public long warnSellUntil;

    public static PlayerData of(Player p) {
        if (p == null) return new PlayerData();
        return Sky.profiles().member(p, KEY, PlayerData.class);
    }

    public void viewed(String id) {
        recent.remove(id);
        recent.add(0, id);
        while (recent.size() > 21) recent.remove(recent.size() - 1);
    }

    public boolean toggleFavourite(String id) {
        if (favourites.remove(id)) return false;
        if (favourites.size() >= 56) favourites.remove(favourites.size() - 1);
        favourites.add(0, id);
        return true;
    }
}
