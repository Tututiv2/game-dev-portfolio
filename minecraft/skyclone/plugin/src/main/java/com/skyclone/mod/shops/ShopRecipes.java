package com.skyclone.mod.shops;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.shops.ShopData.CurrencyCost;
import com.skyclone.mod.shops.ShopData.Entry;
import com.skyclone.mod.shops.ShopData.ItemCost;
import com.skyclone.mod.shops.ShopData.Shop;

import java.util.ArrayList;
import java.util.List;

/**
 * Every stock line a player can buy, registered as a {@code Recipe.Type.NPC_SHOP} recipe (bazaar hand-off
 * request 1). That is what makes the rest of the game know a merchant sells something:
 * <ul>
 *   <li>the recipe viewer ({@code /recipe}, crafting) shows "Sold by Farm Merchant" with the cost and the gate,
 *       and the item's "used in" lists the trades that take it (Gold Pickaxe for 3 Gold Ingots);</li>
 *   <li>the bazaar's market makers never ask more than a merchant charges (live ceiling instead of the one
 *       baked into {@code products.json} at generation time);</li>
 *   <li>the auction price guide can price an item by what a merchant asks.</li>
 * </ul>
 * Coins are the line's EFFECTIVE price for a viewer with no discount: price scale and anti-arbitrage floor
 * applied, rounded up to a whole coin (the bazaar reads whole coins). Ids are {@code shops:<shop>:<n>}.
 * Registered once in postEnable, after every module registered its items; yielded merchants are skipped
 * (the module that stands there owns its own lines).
 */
final class ShopRecipes {

    private ShopRecipes() {}

    static int registered;
    static int skipped;
    /** Every recipe we registered with the line it stands for, so a price dial can re-price them in place. */
    private static final List<Line> LINES = new ArrayList<>();
    private static boolean listening;

    private record Line(Recipe recipe, Shop shop, Entry entry) {}

    static void registerAll() {
        registered = 0;
        skipped = 0;
        LINES.clear();
        // a station another module already sells under (mining's "Hollows Merchant" menu lists recipes BY station
        // name) is that module's shop: adding ours would put a second copy of every line into its menu
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Recipe r : Sky.recipes().byType(Recipe.Type.NPC_SHOP))
            if (r.station != null && (r.id == null || !r.id.startsWith("shops:"))) taken.add(r.station.toLowerCase(java.util.Locale.ROOT));
        for (Shop s : Shops.all()) {
            if (Shops.status(s) == Shops.Status.YIELDED) continue;
            if (taken.contains(Text.strip(s.name).trim().toLowerCase(java.util.Locale.ROOT))) continue;
            int n = 0;
            for (Entry e : Shops.available(s, null)) {
                Recipe r = recipe(s, e, n++);
                if (r == null) { skipped++; continue; }
                try {
                    int before = Sky.recipes().all().size();
                    Sky.recipes().register(r);
                    if (Sky.recipes().all().size() > before) { registered++; LINES.add(new Line(r, s, e)); } else skipped++;
                } catch (RuntimeException ex) {
                    skipped++;
                    Sky.log().warning("[shops] recipe " + r.id + ": " + ex);
                }
            }
        }
        Sky.log().info("[shops] registered " + registered + " NPC shop recipe(s), skipped " + skipped);
        if (!listening) {
            listening = true;
            // the recipe viewer / bazaar ceilings follow a price dial at once instead of after a restart
            ShopDials.PRICE_SCALE.onChange(v -> refreshPrices());
            ShopDials.MIN_MARKUP.onChange(v -> refreshPrices());
        }
    }

    /**
     * Re-price every registered line in place (its {@code COINS:n} input): after a price dial changes, and once
     * shortly after boot, because accessories lowers some NPC sell prices a tick after postEnable and the
     * anti-arbitrage floor follows sell prices. Returns how many recipes changed.
     */
    static int refreshPrices() {
        int changed = 0;
        for (Line l : LINES) {
            Recipe r = l.recipe();
            if (r.inputs == null) continue;
            double coins = Trade.bundleCoins(null, l.entry());
            String want = coins > 0 ? "COINS:" + (long) Math.ceil(coins - 1e-9) : null;
            int at = -1;
            for (int i = 0; i < r.inputs.size(); i++) if (r.inputs.get(i) != null && r.inputs.get(i).startsWith("COINS:")) { at = i; break; }
            String have = at < 0 ? null : r.inputs.get(at);
            if (java.util.Objects.equals(have, want)) continue;
            List<String> in = new ArrayList<>(r.inputs);
            if (at >= 0 && want != null) in.set(at, want);
            else if (at >= 0) in.remove(at);
            else in.add(0, want);
            if (in.isEmpty()) continue;   // a line must cost something; keep the old price rather than make it free
            r.inputs = in;
            changed++;
        }
        if (changed > 0) Sky.log().info("[shops] re-priced " + changed + " NPC shop recipe(s)");
        return changed;
    }

    static int lineCount() { return LINES.size(); }

    /** The coins input of one registered line (for the self-test), or -1. */
    static long recipeCoins(Recipe r) {
        if (r == null || r.inputs == null) return -1;
        for (String i : r.inputs) if (i != null && i.startsWith("COINS:")) {
            try { return Long.parseLong(i.substring(6)); } catch (NumberFormatException e) { return -1; }
        }
        return 0;
    }

    /** The recipe for one line, or null when it cannot be bought (an unresolvable cost, an unspendable currency). */
    static Recipe recipe(Shop s, Entry e, int index) {
        String result = Shops.resolve(e);
        if (result == null || Trade.blocked(e) != null || e.bits > 0) return null;
        List<String> inputs = new ArrayList<>();
        double coins = Trade.bundleCoins(null, e);
        if (coins > 0) inputs.add("COINS:" + (long) Math.ceil(coins - 1e-9));
        if (e.hasItemCosts()) for (ItemCost c : e.items) {
            String id = Shops.resolve(c);
            if (id == null) return null;
            inputs.add(id + ":" + c.amount);
        }
        if (e.hasCurrency()) for (CurrencyCost c : e.currency) {
            String id = Shops.currencyItem(c.id);
            if (id == null) return null;
            inputs.add(id + ":" + c.amount);
        }
        if (inputs.isEmpty()) return null;
        Recipe r = new Recipe();
        r.id = "shops:" + s.id + ":" + index;
        r.type = Recipe.Type.NPC_SHOP;
        r.result = result;
        r.amount = Math.max(1, e.amount);
        r.inputs = inputs;
        r.station = Text.strip(s.name).trim();
        r.unlock = Shops.requirement(s, e);
        return r;
    }
}
