package com.skyclone.mod.shops;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.shops.ShopData.CurrencyCost;
import com.skyclone.mod.shops.ShopData.Entry;
import com.skyclone.mod.shops.ShopData.ItemCost;
import com.skyclone.mod.shops.ShopData.Shop;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Buying, selling and buyback. Everything here runs on the main thread inside one tick: every check is
 * made first, then the coins move (the economy writes through to the DB with a ledger row), then items.
 * Anything that fails after money moved is refunded in the same tick.
 */
final class Trade {

    private Trade() {}

    static final String MEMBER_KEY = "shops";

    static Pricing.Ledger ledger(Player p) {
        Pricing.Ledger l = Sky.profiles().member(p, MEMBER_KEY, Pricing.Ledger.class);
        l.roll(Pricing.utcDay(System.currentTimeMillis()));
        return l;
    }

    // ---- prices ---------------------------------------------------------------------------------------

    /** Best accessory discount (Shady Ring / Crooked Artifact / Seal of the Family; they do not stack). */
    static double accessoryDiscount(Player p) {
        if (p == null) return 0;
        Features.Accessories acc = Sky.service(Features.Accessories.class);
        return Pricing.bestDiscount(acc.has(p, "SHADY_RING"), acc.has(p, "CROOKED_ARTIFACT"), acc.has(p, "SEAL_OF_THE_FAMILY"),
                ShopDials.SHADY_PCT.get(), ShopDials.CROOKED_PCT.get(), ShopDials.SEAL_PCT.get());
    }

    /** This merchant's own Hunting-attribute discount (Builder / Bartender Discount), percent; 0 elsewhere. */
    static double attributeDiscount(Player p, Shop shop) {
        if (p == null || shop == null || shop.discountAttr == null || shop.discountAttr.isBlank()) return 0;
        try {
            if (!ShopDials.HUNTING_DISCOUNTS.get()) return 0;
            double v = Sky.service(Features.Hunting.class).attributeLevel(p, "value:" + shop.discountAttr);
            return Double.isFinite(v) ? Math.max(0, v) : 0;
        } catch (Throwable t) { return 0; }
    }

    /** The whole discount this viewer gets at this merchant (no shop = accessories only). */
    static double discountPct(Player p, Shop shop) {
        return Pricing.combinedDiscount(accessoryDiscount(p), attributeDiscount(p, shop));
    }

    static double discountPct(Player p) { return discountPct(p, null); }

    static double npcSellEach(String resolvedId) {
        ItemDef d = Shops.def(resolvedId);
        return d == null ? 0 : Math.max(0, d.npcSell);
    }

    /**
     * Percent off an envoy's Leviathan key for this viewer: the reference Emissary discount by reputation with the
     * envoy's faction, read through crimson's {@code faction:<id>:<rep>} requirement key (no crimson import).
     */
    static int emissaryDiscount(Player p, Shop shop, Entry e) {
        if (p == null || shop == null || e == null || !e.emissary || shop.emissaryFaction == null) return 0;
        try {
            String fac = shop.emissaryFaction;
            return Pricing.emissaryPercent(Pricing.parseEmissary(ShopDials.EMISSARY_DISCOUNT.get()),
                    rep -> Sky.requirements().check(p, "faction:" + fac + ":" + rep));
        } catch (Throwable t) { return 0; }
    }

    /** Coins for ONE bundle of {@code e} for this viewer at this merchant (discount, price scale, anti-arbitrage floor). */
    static double bundleCoins(Player viewer, Shop shop, Entry e) {
        if (e.coins <= 0) return 0;
        String id = Shops.resolve(e);
        double listed = Pricing.emissaryCoins(e.coins * Math.max(0, ShopDials.PRICE_SCALE.get()), emissaryDiscount(viewer, shop, e));
        return Pricing.bundleCoins(listed, e.amount, npcSellEach(id), discountPct(viewer, shop), ShopDials.MIN_MARKUP.get());
    }

    /** Effective price with no viewer (no discount): /sb shops info, recipes, self-tests. */
    static double bundleCoins(Player viewer, Entry e) { return bundleCoins(viewer, null, e); }

    static int dailyLimit() {
        boolean spree = false;
        try {
            String perk = ShopDials.SPREE_PERK.get();
            spree = perk != null && !perk.isBlank() && Sky.service(Features.Mayors.class).perkActive(perk);
        } catch (Throwable ignored) { }
        return Pricing.dailyLimit(ShopDials.DAILY_LIMIT.get(), spree, ShopDials.SPREE_MULT.get());
    }

    /** Bundles a shift-click buys: a full stack of the item, or 1 for unique items. */
    static int stackBundles(Entry e) {
        ItemDef d = Shops.def(Shops.resolve(e));
        if (d == null || d.isUnique()) return 1;
        return Math.max(1, d.effectiveMaxStack() / Math.max(1, e.amount));
    }

    // ---- counting what a player has ------------------------------------------------------------------

    static int have(Player p, String id) {
        long n = Sky.items().count(p, id);
        if (ShopDials.USE_SACKS.get()) {
            try { n += Math.max(0, Sky.service(Features.Sacks.class).count(p, id)); } catch (Throwable ignored) { }
        }
        // essence a player picks up goes straight into the reforges pouch (dial reforges.absorb-essence), so the
        // Essence Broker would otherwise ask for items nobody ever holds
        String ess = Shops.essenceType(id);
        if (ess != null) {
            try { n += Math.max(0, Sky.service(Features.Essence.class).essence(p, ess)); } catch (Throwable ignored) { }
        }
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** What a purchase took of one cost: the count, and the actual stacks of a unique item (a refund returns THEM). */
    static final class Took {
        int n;
        final List<ItemStack> stacks = new ArrayList<>();
    }

    /**
     * How much has been put into one unique item: enchants, reforge, books, stars, gems, recombobulation, module
     * data. Decides WHICH copy of a unique cost a trade takes (same weights as crafting's supercraft).
     */
    static int upgradeWorth(com.skyclone.api.item.ItemData d) {
        if (d == null) return 0;
        int w = 0;
        for (int lvl : d.enchants.values()) w += Math.max(1, lvl);
        if (d.reforge != null) w += 3;
        w += d.potatoBooks + d.stars * 2 + d.gems.size() * 2 + d.unlockedGemSlots.size();
        if (d.recombobulated) w += 5;
        if (d.extra != null) w += d.extra.size();
        return w;
    }

    /**
     * True when {@code costId} is upgraded INTO {@code resultId} (Green Gift Talisman from a White one, a tier-12
     * minion from a tier 11): both unique, same category. Then the most upgraded copy goes and its upgrades carry
     * over onto the result; for any other trade the plainest copy goes, so a player's best gear is never eaten.
     */
    static boolean isUpgrade(String costId, String resultId) {
        ItemDef c = Shops.def(costId), r = Shops.def(resultId);
        return c != null && r != null && c.isUnique() && r.isUnique() && c.category == r.category;
    }

    /**
     * Remove {@code n}. A unique item is taken copy by copy from the inventory, plainest first ({@code order} -1) or
     * most upgraded first (+1); anything else from the inventory, then sacks, then (essence) the reforges pouch.
     */
    static Took take(Player p, String id, int n, int order) {
        Took t = new Took();
        ItemDef d = Shops.def(id);
        if (d != null && d.isUnique() && order != 0) {
            ItemStack[] st = p.getInventory().getStorageContents();
            List<Integer> slots = new ArrayList<>();
            Map<Integer, Integer> worth = new HashMap<>();
            for (int i = 0; i < st.length; i++) {
                if (st[i] == null || !id.equals(Sky.items().idOf(st[i]))) continue;
                slots.add(i);
                worth.put(i, upgradeWorth(Sky.items().data(st[i])));
            }
            slots.sort((a, b) -> order * Integer.compare(worth.get(b), worth.get(a)));
            for (int slot : slots) {
                if (t.n >= n) break;
                ItemStack s = p.getInventory().getItem(slot);
                if (s == null || s.getType().isAir()) continue;
                t.stacks.add(s.clone());
                t.n += s.getAmount();
                p.getInventory().setItem(slot, null);
            }
            if (t.n >= n) return t;
        }
        int got = t.n + Sky.items().remove(p, id, n - t.n);
        if (got < n && ShopDials.USE_SACKS.get()) {
            try { got += Math.max(0, Sky.service(Features.Sacks.class).remove(p, id, n - got)); } catch (Throwable ignored) { }
        }
        String ess = Shops.essenceType(id);
        if (got < n && ess != null) {
            try {
                Features.Essence pouch = Sky.service(Features.Essence.class);
                long want = n - got;
                if (pouch.essence(p, ess) >= want && pouch.takeEssence(p, ess, want)) got += (int) want;
            } catch (Throwable ignored) { }
        }
        t.n = got;
        return t;
    }

    /**
     * Hand back what a purchase took before it failed: the very stacks of a unique item (with their upgrades), the
     * rest as fresh items. Essence items given through loot fall into the pouch again when reforges absorbs them,
     * and stay items when it does not, so nothing can vanish into a pouch that is not there.
     */
    private static void refund(Player p, String id, Took t) {
        if (t == null) return;
        int back = 0;
        for (ItemStack s : t.stacks) { Sky.loot().give(p, s, LootSource.OTHER); back += s.getAmount(); }
        if (t.n - back > 0) give(p, id, t.n - back, LootSource.OTHER);
    }

    /** The instance data of the most upgraded stack taken, worth carrying onto the result, or null. */
    private static com.skyclone.api.item.ItemData carryFrom(Took t) {
        com.skyclone.api.item.ItemData best = null;
        int bw = 0;
        if (t != null) for (ItemStack s : t.stacks) {
            com.skyclone.api.item.ItemData d = Sky.items().data(s);
            int w = upgradeWorth(d);
            if (w > bw) { best = d; bw = w; }
        }
        return best;
    }

    /** Give the bought units; the first unique copy receives the carried upgrades (reforge, enchants, stars...). */
    private static void giveBought(Player p, String id, int units, com.skyclone.api.item.ItemData carry) {
        ItemDef d = Shops.def(id);
        if (carry == null || d == null || !d.isUnique() || units <= 0) { give(p, id, units, LootSource.NPC_SHOP); return; }
        ItemStack it = Sky.items().create(id, 1);
        final com.skyclone.api.item.ItemData c = carry;
        Sky.items().update(it, nd -> {
            nd.enchants.putAll(c.enchants);
            if (c.reforge != null) nd.reforge = c.reforge;
            nd.potatoBooks = Math.max(nd.potatoBooks, c.potatoBooks);
            nd.recombobulated |= c.recombobulated;
            nd.stars = Math.max(nd.stars, c.stars);
            nd.gems.putAll(c.gems);
            nd.unlockedGemSlots.addAll(c.unlockedGemSlots);
            for (Map.Entry<String, com.google.gson.JsonElement> x : c.extra.entrySet())
                if (!nd.extra.has(x.getKey())) nd.extra.add(x.getKey(), x.getValue().deepCopy());
        });
        Sky.loot().give(p, it, LootSource.NPC_SHOP);
        if (units > 1) give(p, id, units - 1, LootSource.NPC_SHOP);
    }

    /** How many of {@code sample} still fit in the main inventory (empty slots + partial matching stacks). */
    static int capacity(Player p, ItemStack sample) {
        int max = Math.max(1, sample.getMaxStackSize());
        int cap = 0;
        ItemStack[] st = p.getInventory().getStorageContents();
        for (ItemStack s : st) {
            if (s == null || s.getType().isAir()) cap += max;
            else if (max > 1 && s.isSimilar(sample)) cap += Math.max(0, max - s.getAmount());
        }
        return cap;
    }

    // ---- spam guard -----------------------------------------------------------------------------------

    private static final Map<UUID, Long> LAST = new HashMap<>();

    static boolean tooFast(Player p) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(p.getUniqueId());
        if (last != null && now - last < ShopDials.CLICK_MS.get()) return true;
        LAST.put(p.getUniqueId(), now);
        return false;
    }

    static void forget(Player p) {
        LAST.remove(p.getUniqueId());
        BUYBACK.remove(p.getUniqueId());
    }

    // ---- buying ---------------------------------------------------------------------------------------

    /** Where the unspendable currency of a blocked line CAN be spent (one '&' line), or null. */
    static String blockedHint(Entry e) {
        if (e.hasCurrency()) for (CurrencyCost c : e.currency)
            if (!Shops.currencySupported(c.id)) return Currencies.unsupportedHint(c.id);
        return null;
    }

    /** Why this entry can never be bought right now regardless of the player, or null. */
    static String blocked(Entry e) {
        if (Shops.resolve(e) == null) return "&cThis item is not in stock.";
        if (e.hasCurrency()) for (CurrencyCost c : e.currency) {
            if (!Shops.currencySupported(c.id)) return "&c" + Text.strip(Shops.currencyName(c.id)) + " can't be spent here.";
        }
        if (e.hasItemCosts()) for (ItemCost c : e.items) {
            if (Shops.resolve(c) == null) return "&cThis trade needs an item that doesn't exist yet.";
        }
        return null;
    }

    /**
     * Buy {@code bundles} bundles of {@code e} from {@code shop}. Tells the player the outcome; returns the
     * number of ITEMS bought (0 when refused).
     */
    static int buy(Player p, Shop shop, Entry e, int bundles, boolean silentSuccess) {
        if (p == null || !p.isOnline()) return 0;
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading."); return 0; }
        String why = blocked(e);
        if (why != null) { Msg.error(p, Text.strip(why)); return 0; }
        String req = Shops.requirement(shop, e);
        if (req != null) {
            boolean ok;
            try { ok = Sky.requirements().checkOrTell(p, req); } catch (Throwable t) { ok = false; }
            if (!ok) { Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f); return 0; }
        }
        String id = Shops.resolve(e);
        ItemDef def = Shops.def(id);
        Pricing.Ledger led = ledger(p);
        int limit = dailyLimit();
        int done = led.boughtToday(shop.id, id);
        int asked = Math.max(1, bundles);
        int allowed = Pricing.allowedBundles(asked, e.amount, done, limit);
        if (allowed <= 0) {
            Msg.error(p, "You have bought your daily limit of " + Text.commasInt(limit) + " " + Text.strip(Shops.displayName(id))
                    + " here! The merchant restocks at midnight (UTC).");
            return 0;
        }
        int units = allowed * e.amount;

        ItemStack sample = Sky.items().create(id, 1);
        if (capacity(p, sample) < units) {
            if (allowed == 1 || capacity(p, sample) < e.amount) { Msg.error(p, "You don't have enough inventory space for that!"); return 0; }
            allowed = Math.max(1, capacity(p, sample) / e.amount);
            units = allowed * e.amount;
        }

        double coins = Pricing.ceilTenth(bundleCoins(p, shop, e) * allowed);
        long bits = e.bits * allowed;
        Map<String, Integer> needItems = new java.util.LinkedHashMap<>();
        if (e.hasItemCosts()) for (ItemCost c : e.items) needItems.merge(Shops.resolve(c), c.amount * allowed, Integer::sum);
        // currencies carried as items (North Stars) are paid exactly like an item cost; the rest by their service
        Map<String, Long> needCurrency = new java.util.LinkedHashMap<>();
        if (e.hasCurrency()) for (CurrencyCost c : e.currency) {
            String asItem = Shops.currencyItem(c.id);
            if (asItem != null) needItems.merge(asItem, (int) Math.min(Integer.MAX_VALUE, c.amount * allowed), Integer::sum);
            else needCurrency.merge(c.id, c.amount * allowed, Long::sum);
        }

        // ---- all checks before anything moves ----
        if (coins > 0 && Sky.economy().purse(p) + 1e-9 < coins) {
            Msg.error(p, "You don't have enough coins! (" + Sky.economy().format(coins) + " needed)");
            return 0;
        }
        if (bits > 0 && Sky.economy().bits(p) < bits) {
            Msg.error(p, "You don't have enough bits! (" + Text.commasInt(bits) + " needed)");
            return 0;
        }
        for (Map.Entry<String, Integer> n : needItems.entrySet()) {
            if (have(p, n.getKey()) < n.getValue()) {
                Msg.error(p, "You need " + Text.strip(Shops.displayName(n.getKey())) + " x" + n.getValue() + " for that!");
                return 0;
            }
        }
        for (Map.Entry<String, Long> n : needCurrency.entrySet()) {
            if (Currencies.balance(p, n.getKey()) < n.getValue()) {
                Msg.error(p, "You need " + Text.commasInt(n.getValue()) + " " + Text.strip(Shops.currencyName(n.getKey())) + " for that!");
                return 0;
            }
        }

        // ---- move: coins, bits, items; refund on any failure ----
        String what = id + " x" + units;
        if (coins > 0 && !Sky.economy().withdraw(p, coins, "npc-buy:" + shop.id + ":" + what)) {
            Msg.error(p, "You don't have enough coins!");
            return 0;
        }
        if (bits > 0 && !Sky.economy().spendBits(p, bits, "npc-buy:" + shop.id + ":" + what)) {
            if (coins > 0) Sky.economy().deposit(p, coins, "npc-buy-refund:" + shop.id + ":" + what);
            Msg.error(p, "You don't have enough bits!");
            return 0;
        }
        Map<String, Took> taken = new HashMap<>();
        boolean shortItems = false;
        com.skyclone.api.item.ItemData carry = null;
        for (Map.Entry<String, Integer> n : needItems.entrySet()) {
            // an upgrade trade takes the most upgraded copy and carries it over; anything else the plainest copy
            boolean upgrade = isUpgrade(n.getKey(), id);
            Took got = take(p, n.getKey(), n.getValue(), upgrade ? 1 : -1);
            taken.put(n.getKey(), got);
            if (got.n < n.getValue()) { shortItems = true; break; }
            if (upgrade && carry == null) carry = carryFrom(got);
        }
        // service currencies go LAST: each spend is all-or-nothing, and nothing after it can fail, so a currency
        // never needs refunding (owners do not all expose an add)
        if (!shortItems) for (Map.Entry<String, Long> n : needCurrency.entrySet()) {
            if (!Currencies.spend(p, n.getKey(), n.getValue(), "npc-buy:" + shop.id + ":" + what)) { shortItems = true; break; }
        }
        if (shortItems) {
            for (Map.Entry<String, Took> t : taken.entrySet()) refund(p, t.getKey(), t.getValue());
            if (coins > 0) Sky.economy().deposit(p, coins, "npc-buy-refund:" + shop.id + ":" + what);
            if (bits > 0) Sky.economy().addBits(p, bits, "npc-buy-refund:" + shop.id + ":" + what);
            Msg.error(p, "Some of what you trade with went missing - nothing was bought.");
            return 0;
        }

        giveBought(p, id, units, carry);
        if (carry != null) Msg.send(p, "&7Your upgrades carried over onto the new " + Shops.displayName(id) + "&7.");
        led.addBought(shop.id, id, units);
        led.lifetimeCoinsSpent += coins;

        if (!silentSuccess) {
            StringBuilder cost = new StringBuilder();
            if (coins > 0) cost.append("&6").append(Sky.economy().format(coins)).append(" Coins");
            if (bits > 0) cost.append(cost.length() > 0 ? "&a, " : "").append("&b").append(Text.commasInt(bits)).append(" Bits");
            for (Map.Entry<String, Integer> n : needItems.entrySet())
                cost.append(cost.length() > 0 ? "&a, " : "").append(Shops.displayName(n.getKey())).append(" &8x").append(n.getValue());
            for (Map.Entry<String, Long> n : needCurrency.entrySet())
                cost.append(cost.length() > 0 ? "&a, " : "").append(Shops.currencyName(n.getKey())).append(" &8x").append(Text.commasInt(n.getValue()));
            Msg.send(p, "&aYou bought " + Shops.displayName(id) + (units > 1 ? " &8x" + units : "") + "&a for " + cost + "&a!");
            Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f);
        }
        if (allowed < asked) Msg.send(p, "&eOnly " + Text.commasInt(units) + " could be bought (daily limit or inventory space).");
        return units;
    }

    /** Hand items over in correctly sized stacks; unique items one by one with their own uuid. */
    static void give(Player p, String id, int units, LootSource source) {
        ItemDef d = Shops.def(id);
        if (d == null || units <= 0) return;
        int per = d.isUnique() ? 1 : Math.max(1, d.effectiveMaxStack());
        int left = units;
        while (left > 0) {
            int n = Math.min(per, left);
            Sky.loot().give(p, Sky.items().create(id, n), source);
            left -= n;
        }
    }

    // ---- selling --------------------------------------------------------------------------------------

    /** Coins this stack sells for to a merchant, or 0 when it cannot be sold. */
    static double sellValue(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return 0;
        String id = Sky.items().idOf(stack);
        if (id == null) return 0;
        ItemDef d = Sky.items().def(id);
        if (d == null || d.npcSell <= 0 || d.has("no_sell")) return 0;
        // whole tenths, rounded down: the economy credits tenths, and buyback must cost exactly what was paid
        return Math.floor(d.npcSell * stack.getAmount() * 10.0 + 1e-9) / 10.0;
    }

    /** True when selling this needs a confirmation first (a unique item at or above the dial's rarity). */
    static boolean needsConfirm(ItemStack stack) {
        String r = ShopDials.CONFIRM_RARITY.get();
        if (r == null || r.isBlank() || r.equalsIgnoreCase("NONE")) return false;
        String id = Sky.items().idOf(stack);
        ItemDef d = id == null ? null : Sky.items().def(id);
        if (d == null || !d.isUnique()) return false;
        Rarity have = Sky.items().rarity(stack);
        return have != null && have.ordinal() >= Rarity.parse(r).ordinal() && have.ordinal() <= Rarity.MYTHIC.ordinal();
    }

    /**
     * Sell the stack sitting in player-inventory slot {@code slot} (it must still equal {@code expect}).
     * Returns the coins paid, 0 when refused.
     */
    static double sellSlot(Player p, Shop shop, int slot, ItemStack expect) {
        if (!ShopDials.SELL_ENABLED.get()) { Msg.error(p, "Merchants are not buying right now."); return 0; }
        if (!Sky.profiles().isReady(p)) return 0;
        ItemStack live = p.getInventory().getItem(slot);
        if (live == null || expect == null || !live.isSimilar(expect) || live.getAmount() != expect.getAmount()) return 0;
        double value = sellValue(live);
        if (value <= 0) { Msg.error(p, "Merchants won't buy that item."); return 0; }
        Pricing.Ledger led = ledger(p);
        double cap = ShopDials.SELL_CAP.get();
        if (led.sold + value > cap + 1e-9) {
            Msg.error(p, "You have sold " + Text.shortNum(cap) + " coins of items today - the merchants can't afford any more until midnight (UTC).");
            return 0;
        }
        String id = Sky.items().idOf(live);
        int amount = live.getAmount();
        ItemStack sold = live.clone();
        p.getInventory().setItem(slot, null);
        Sky.economy().deposit(p, value, "npc-sell:" + id + " x" + amount);
        led.sold += value;
        led.lifetimeCoinsSold += value;
        buyback(p).push(sold, id, amount, value, System.currentTimeMillis(), shop == null ? "" : shop.id, ShopDials.BUYBACK_SIZE.get());
        Msg.send(p, "&aYou sold " + Shops.displayName(id) + (amount > 1 ? " &8x" + amount : "") + "&a for &6"
                + Sky.economy().format(value) + " Coins&a!");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f);
        return value;
    }

    /** Sell a stack that is already OUT of the player's inventory (the Sell Items box). Returns coins paid. */
    static double sellLoose(Player p, Shop shop, ItemStack stack) {
        if (!Sky.profiles().isReady(p)) return 0;
        double value = sellValue(stack);
        if (value <= 0) return 0;
        Pricing.Ledger led = ledger(p);
        if (led.sold + value > ShopDials.SELL_CAP.get() + 1e-9) return 0;
        String id = Sky.items().idOf(stack);
        Sky.economy().deposit(p, value, "npc-sell:" + id + " x" + stack.getAmount());
        led.sold += value;
        led.lifetimeCoinsSold += value;
        buyback(p).push(stack.clone(), id, stack.getAmount(), value, System.currentTimeMillis(), shop == null ? "" : shop.id, ShopDials.BUYBACK_SIZE.get());
        return value;
    }

    // ---- buyback --------------------------------------------------------------------------------------

    private static final Map<UUID, Pricing.Buyback<ItemStack>> BUYBACK = new HashMap<>();

    static Pricing.Buyback<ItemStack> buyback(Player p) {
        return BUYBACK.computeIfAbsent(p.getUniqueId(), k -> new Pricing.Buyback<>());
    }

    /** Copies of the stacks in this player's buyback (core dupe-scan source; nothing is changed). */
    static List<ItemStack> buybackStacks(Player p) {
        List<ItemStack> out = new ArrayList<>();
        if (p == null) return out;
        Pricing.Buyback<ItemStack> bb = BUYBACK.get(p.getUniqueId());
        if (bb == null || bb.isEmpty()) return out;
        for (Pricing.Buyback.Sale<ItemStack> s : bb.list(System.currentTimeMillis(), buybackMaxAge()))
            if (s.item != null) out.add(s.item.clone());
        return out;
    }

    static long buybackMaxAge() { return Math.max(1, ShopDials.BUYBACK_MINUTES.get()) * 60_000L; }

    static List<Pricing.Buyback.Sale<ItemStack>> buybackList(Player p) {
        return p == null ? new ArrayList<>() : buyback(p).list(System.currentTimeMillis(), buybackMaxAge());
    }

    /** Buy back sale {@code index} (newest = 0) for exactly what it sold for. */
    static boolean buyBack(Player p, int index) {
        Pricing.Buyback<ItemStack> bb = buyback(p);
        Pricing.Buyback.Sale<ItemStack> s = bb.take(index, System.currentTimeMillis(), buybackMaxAge());
        if (s == null) { Msg.error(p, "That sale is no longer available."); return false; }
        if (capacity(p, s.item) < s.amount) {
            bb.restore(s, ShopDials.BUYBACK_SIZE.get());
            Msg.error(p, "You don't have enough inventory space for that!");
            return false;
        }
        if (!Sky.economy().withdraw(p, s.price, "npc-buyback:" + s.itemId + " x" + s.amount)) {
            bb.restore(s, ShopDials.BUYBACK_SIZE.get());
            Msg.error(p, "You don't have enough coins! (" + Sky.economy().format(s.price) + " needed)");
            return false;
        }
        Pricing.Ledger led = ledger(p);
        led.sold = Math.max(0, led.sold - s.price);   // an undone sale no longer counts toward today's cap
        Sky.loot().give(p, s.item, LootSource.NPC_SHOP);
        Msg.send(p, "&aYou bought back " + Shops.displayName(s.itemId) + (s.amount > 1 ? " &8x" + s.amount : "")
                + "&a for &6" + Sky.economy().format(s.price) + " Coins&a!");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f);
        return true;
    }
}
