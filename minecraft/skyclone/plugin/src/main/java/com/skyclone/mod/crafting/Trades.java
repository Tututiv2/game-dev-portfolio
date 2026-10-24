package com.skyclone.mod.crafting;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe Book -> Trades (research/05 §4.4): item-for-item swaps unlocked by collection tiers. They
 * share the NPC 640-a-day limit (dial) and never count toward collections (source NPC_SHOP).
 */
final class Trades {

    /** One trade as loaded from data/crafting/trades.json. */
    static final class Trade {
        String id;
        String name;
        String result;
        int amount = 1;
        List<String> cost = new ArrayList<>();
        String unlock;
        String collection;
    }

    private final CraftingModule mod;
    final List<Trade> trades = new ArrayList<>();

    Trades(CraftingModule mod) { this.mod = mod; }

    void load() {
        try (InputStream in = Sky.plugin().getResource("data/crafting/trades.json")) {
            if (in == null) { Sky.log().warning("[crafting] trades.json missing"); return; }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(r);
                Gson g = new Gson();
                for (JsonElement e : root.getAsJsonArray()) {
                    Trade t = g.fromJson(e, Trade.class);
                    if (t != null && t.id != null && t.result != null) trades.add(t);
                }
            }
        } catch (Exception e) {
            Sky.log().warning("[crafting] could not read trades.json: " + e);
        }
    }

    /** The result as a canonical sky id, or null when the item is not in this game. */
    String resultId(Trade t) {
        String id = mod.index.canon(t.result);
        return Sky.items().exists(id) ? id : null;
    }

    /** Cost as canonical id -> amount; "COINS" for coins. Null when a cost item does not exist. */
    Map<String, Integer> cost(Trade t) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String c : t.cost) {
            CraftMath.Cell cell = CraftMath.parse(c, null);
            if (cell == null) continue;
            if (cell.id().equals("COINS")) { m.merge("COINS", cell.amount(), Integer::sum); continue; }
            String id = mod.index.canon(cell.id());
            if (!Sky.items().exists(id)) return null;
            m.merge(id, cell.amount(), Integer::sum);
        }
        return m;
    }

    private static String today() { return LocalDate.now(ZoneOffset.UTC).toString(); }

    /** True while the mayor perk that multiplies NPC daily limits (Shopping Spree) is active. */
    boolean spree() {
        if (!Sky.has(Features.Mayors.class)) return false;
        String perk = mod.dials.tradeSpreePerk.get();
        if (perk == null || perk.isBlank()) return false;
        try { return Sky.service(Features.Mayors.class).perkActive(perk); } catch (RuntimeException e) { return false; }
    }

    /** Items per trade per real day right now (research/05 §4.4: 640, x10 under Shopping Spree). */
    int limit() {
        return CraftMath.tradeLimit(mod.dials.tradeDailyLimit.get(), spree(), mod.dials.tradeSpreeMultiplier.get());
    }

    int tradedToday(Player p, Trade t) {
        CraftData d = mod.engine.data(p);
        if (d == null) return 0;
        if (!today().equals(d.tradeDay)) return 0;
        return d.tradedToday.getOrDefault(t.id, 0);
    }

    boolean unlocked(Player p, Trade t) {
        if (t.unlock == null) return true;
        if (mod.engine.bypass.contains(p.getUniqueId())) return true;
        try { return Sky.requirements().check(p, t.unlock); } catch (RuntimeException e) { return false; }
    }

    void open(Player p) { build(p).open(p); }

    Menu build(Player p) {
        // Every trade is listed; one whose items are not in the game yet shows as unavailable instead of vanishing.
        List<Trade> shown = new ArrayList<>(trades);
        shown.sort(java.util.Comparator.comparing((Trade t) -> resultId(t) == null || cost(t) == null)
                .thenComparing(t -> p != null && !unlocked(p, t)));
        int limit = limit();
        boolean spree = spree();
        return Sky.menus().paged("Trades", shown, t -> icon(p, t), pc -> {
            Player pl = pc.click().player();
            trade(pl, pc.entry(), pc.click().shift() ? 64 : 1);
            build(pl).open(pl);
        }, menu -> {
            menu.back("Recipe Book", () -> mod.book.root(p));
            List<String> head = new ArrayList<>(List.of(
                    "&7Swap materials you have for ones", "&7you need. Each trade unlocks at a",
                    "&7collection tier.", "", "&7Daily limit: &a" + Text.commas(limit) + " &7per trade"));
            if (spree) head.add("&6Shopping Spree! &7(mayor perk)");
            head.add("&8Traded items do not count toward");
            head.add("&8collections.");
            menu.set(4, Icons.of(Material.EMERALD, "&aTrades", head));
        }, 0);
    }

    private ItemStack icon(Player p, Trade t) {
        String res = resultId(t);
        Map<String, Integer> cost = cost(t);
        if (res == null || cost == null) {
            return Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&7" + t.name + " Trade",
                    "&8This trade's items are not in", "&8the game yet.", "", "&cNot available!");
        }
        boolean open = p == null || unlocked(p, t);
        if (!open) {
            List<String> lore = new ArrayList<>();
            lore.add("&cLocked");
            lore.add("&7Requires " + CraftEngine.describe(t.unlock) + "&7.");
            return Icons.of(Material.GRAY_DYE, "&c" + CraftMenu.displayName(res) + " Trade", lore);
        }
        ItemStack it;
        try { it = Sky.items().create(res, Math.max(1, Math.min(64, t.amount))); } catch (RuntimeException e) { return Icons.of(Material.BARRIER, "&c" + res); }
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Cost");
        for (Map.Entry<String, Integer> e : cost.entrySet()) {
            if (e.getKey().equals("COINS")) lore.add("&6" + Text.commas(e.getValue()) + " Coins");
            else lore.add(CraftEngine.name(e.getKey()) + " &8x" + e.getValue());
        }
        int limit = limit();
        int done = p == null ? 0 : tradedToday(p, t);
        lore.add("");
        lore.add("&7Traded today: " + (done >= limit ? "&c" : "&a") + Text.commas(done) + "&7/&a" + Text.commas(limit));
        lore.add("");
        lore.add("&eClick to trade!");
        lore.add("&eShift-click to trade 64 times!");
        return Icons.withLore(it, lore);
    }

    /** Make up to {@code times} trades. Returns how many were made. */
    int trade(Player p, Trade t, int times) {
        if (!unlocked(p, t)) { Msg.error(p, "You have not unlocked this trade yet!"); return 0; }
        String res = resultId(t);
        Map<String, Integer> cost = cost(t);
        if (res == null || cost == null) { Msg.error(p, "This trade is not available."); return 0; }
        CraftData d = mod.engine.data(p);
        if (d == null) return 0;
        if (!today().equals(d.tradeDay)) { d.tradeDay = today(); d.tradedToday.clear(); }
        int limit = limit();
        int left = limit - d.tradedToday.getOrDefault(t.id, 0);
        int n = Math.min(times, left / Math.max(1, t.amount));
        if (n <= 0) { Msg.error(p, "You reached today's limit for this trade!"); return 0; }
        // affordability
        Map<String, Integer> inv = Stock.inventory(p, mod.index);
        for (Map.Entry<String, Integer> e : cost.entrySet()) {
            int can;
            if (e.getKey().equals("COINS")) can = (int) Math.min(Integer.MAX_VALUE, Math.floor(Sky.economy().purse(p) / e.getValue()));
            else can = inv.getOrDefault(e.getKey(), 0) / e.getValue();
            n = Math.min(n, can);
        }
        int space = Stock.capacity(p, res) / Math.max(1, t.amount);
        n = Math.min(n, space);
        if (n <= 0) {
            Msg.error(p, space <= 0 ? "You don't have enough inventory space!" : "You can't afford this trade!");
            return 0;
        }
        Map<String, Integer> take = new LinkedHashMap<>();
        int coins = 0;
        for (Map.Entry<String, Integer> e : cost.entrySet()) {
            if (e.getKey().equals("COINS")) coins = e.getValue() * n;
            else take.put(e.getKey(), e.getValue() * n);
        }
        if (coins > 0 && Sky.economy().purse(p) < coins) { Msg.error(p, "You can't afford this trade!"); return 0; }
        Stock.Taken taken = Stock.take(p, mod.index, take, false);
        if (taken == null) { Msg.error(p, "You can't afford this trade!"); return 0; }
        if (coins > 0 && !Sky.economy().withdraw(p, coins, "trade: " + t.id + " x" + n)) {
            Stock.refund(p, taken);
            Msg.error(p, "You can't afford this trade!");
            return 0;
        }
        ItemDef rd = Sky.items().def(res);
        int total = t.amount * n;
        int max = rd == null ? 64 : Math.max(1, rd.effectiveMaxStack());
        while (total > 0) {
            int k = Math.min(max, total);
            Sky.loot().give(p, Sky.items().create(res, k), LootSource.NPC_SHOP);
            total -= k;
        }
        d.tradedToday.merge(t.id, t.amount * n, Integer::sum);
        d.tradedTotal += (long) t.amount * n;
        Msg.sound(p, Sound.ENTITY_VILLAGER_TRADE, 1f);
        Msg.send(p, "&aTraded for &f" + (t.amount * n) + "x " + CraftEngine.name(res) + "&a!");
        return n;
    }

    Trade byId(String id) {
        for (Trade t : trades) if (t.id.equalsIgnoreCase(id)) return t;
        return null;
    }
}
