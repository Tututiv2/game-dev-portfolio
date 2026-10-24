package com.skyclone.mod.dungeons.shop;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.DungeonMenus;
import com.skyclone.mod.dungeons.loot.LData;
import com.skyclone.mod.dungeons.loot.RewardChests;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Gatekeeper's stock (the brief's "Mort shop"): Fate Feathers and Dungeon Chest Keys for coins (bazaar products in
 * the reference; here a fixed NPC price so they exist on a fresh server - TUNE dials), and salvaging: hand in dungeon
 * gear for essence (research items.json {@code salvages}; dungeon gear without one gives Undead essence by rarity,
 * TUNE). Recombobulated or starred gear refuses (you would lose what you paid into it).
 */
public final class GatekeeperShop {

    private GatekeeperShop() {}

    public record Salvage(String essence, int amount) {}

    private static Map<String, Salvage> SALVAGE = new HashMap<>();

    public static void init() {
        // The bazaar's market makers bid on Fate Feathers (~1.3M) and Chest Keys (~0.26M). An NPC_SHOP recipe tells them
        // this shop exists, so their bid stays under its price (bazaar MarketMaker: bid <= 97% of any NPC shop price).
        // Without it, buy-from-the-Gatekeeper-and-insta-sell printed ~300k coins per feather. (Review fix.)
        shopRecipe("KISMET_FEATHER", DDials.KISMET_PRICE.get());
        shopRecipe("DUNGEON_CHEST_KEY", DDials.CHEST_KEY_PRICE.get());
        InputStream in = Sky.plugin().getResource("data/dungeons/salvage.json");
        if (in == null) return;
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Map<String, Salvage> m = new Gson().fromJson(r, new TypeToken<Map<String, Salvage>>() {}.getType());
            if (m != null) SALVAGE = m;
        } catch (Exception e) { Sky.log().warning("[dungeons] salvage.json: " + e); }
    }

    public static Map<String, Salvage> salvageTable() { return SALVAGE; }

    private static void shopRecipe(String id, double price) {
        Recipe r = new Recipe();
        r.id = "dungeons:gatekeeper_" + id.toLowerCase(java.util.Locale.ROOT);
        r.type = Recipe.Type.NPC_SHOP;
        r.result = id;
        r.amount = 1;
        r.inputs = new ArrayList<>(List.of("COINS:" + (long) Math.ceil(price)));
        r.station = "Gatekeeper";
        try { Sky.recipes().register(r); } catch (RuntimeException e) { Sky.log().warning("[dungeons] gatekeeper recipe " + id + ": " + e); }
    }

    /**
     * What the Gatekeeper charges right now: the dial, but never less than an instant sell on the bazaar would pay
     * back (+5%), so a dial lowered after boot - when the market makers' cap was taken - cannot print coins either.
     */
    public static double price(String id, double dial) {
        double p = dial;
        try {
            Features.Bazaar bz = Sky.service(Features.Bazaar.class);
            if (bz.isBazaarItem(id)) {
                double sell = bz.instantSell(id);
                if (Double.isFinite(sell) && sell > 0) p = Math.max(p, Math.ceil(sell * 1.05));
            }
        } catch (RuntimeException ignored) { }
        return p;
    }

    public static void open(Player p) {
        Menu m = new Menu("Gatekeeper's Stock", 4).chrome();
        if (DDials.MORT_SHOP_KEYS.get()) {
            offer(m, 11, "KISMET_FEATHER", DDials.KISMET_PRICE.get());
            offer(m, 13, "DUNGEON_CHEST_KEY", DDials.CHEST_KEY_PRICE.get());
        }
        m.set(15, Icons.of(Material.GRINDSTONE, "&aSalvage Dungeon Gear", "&7Turn dungeon gear you do not need into", "&7essence.", "",
                "&eClick to open!"), c -> salvage(c.player()));
        m.set(31, Icons.back("Catacombs"), c -> DungeonMenus.main(c.player()));
        m.open(p);
    }

    private static void offer(Menu m, int slot, String id, double dial) {
        if (!Sky.items().exists(id)) return;
        ItemStack icon = Sky.items().create(id, 1);
        double shown = price(id, dial);
        m.set(slot, Icons.withLore(icon, List.of("", "&7Cost", "&6" + Text.commas(shown) + " Coins", "", "&eClick to buy!")), c -> {
            Player p = c.player();
            if (!Sky.profiles().isReady(p)) return;
            double price = price(id, dial);
            if (price > shown) { Msg.error(p, "The price changed - here is the new one."); open(p); return; }
            if (!Sky.economy().withdraw(p, price, "dungeons: gatekeeper " + id)) { Msg.error(p, "You don't have enough coins!"); return; }
            Sky.loot().give(p, id, 1, LootSource.NPC_SHOP);
            Msg.success(p, "You bought a " + Text.strip(Sky.items().def(id).name) + "!");
        });
    }

    // ---- salvage ------------------------------------------------------------------------------------------------

    public static Salvage salvageOf(ItemStack it) {
        String id = Sky.items().idOf(it);
        if (id == null) return null;
        Salvage s = SALVAGE.get(id);
        if (s != null) return s;
        ItemDef d = Sky.items().def(id);
        if (d != null && d.dungeon && (d.category.isArmor() || d.category.isWeapon()) && d.rarity != null) {
            int n = switch (d.rarity) { case COMMON -> 1; case UNCOMMON -> 2; case RARE -> 4; case EPIC -> 8; case LEGENDARY -> 15; default -> 25; };
            return new Salvage("UNDEAD", n);
        }
        return null;
    }

    public static void salvage(Player p) {
        Menu m = new Menu("Salvage Dungeon Gear", 4).chrome();
        m.set(13, Icons.of(Material.GRINDSTONE, "&aSalvage", "&7Click dungeon gear in your inventory", "&7to break it down into essence.", "",
                "&cIt is destroyed!", "&8Starred or recombobulated gear is refused."));
        m.set(31, Icons.back("Gatekeeper"), c -> open(c.player()));
        m.onPlayerInventoryClick((c, stack) -> {
            Player q = c.player();
            if (stack == null || stack.getType().isAir()) return;
            Salvage s = salvageOf(stack);
            if (s == null) { Msg.error(q, "That cannot be salvaged."); return; }
            ItemData data = Sky.items().data(stack);
            if (data.recombobulated || data.stars > 0) { Msg.error(q, "Starred or recombobulated gear cannot be salvaged."); return; }
            String id = Sky.items().idOf(stack);
            // review fix: bazaar goods are refused. Buying one from the bazaar bots and salvaging it for essence the bots
            // also buy is an arbitrage valve (e.g. HELIX -> 25 Fossil essence sat within 7% of its ask). Sell those instead.
            ItemDef sdef = Sky.items().def(id);
            if ((sdef != null && sdef.has("bazaar")) || isBazaar(id)) { Msg.error(q, "That trades on the Bazaar - sell it there instead."); return; }
            int amount = (int) Math.max(1, Math.round(s.amount() * DDials.SALVAGE_SCALE.get()));
            String ess = "ESSENCE_" + s.essence();
            ItemDef def = Sky.items().def(id);
            Sky.menus().confirm(q, "Salvage?", List.of("&7" + (def == null ? id : def.rarity.code() + Text.strip(def.name)), "&7for &d" + amount + " "
                    + Text.titleCase(s.essence()) + " Essence"), () -> {
                if (!removeOne(q, id, data.uuid)) { Msg.error(q, "The item is no longer in your inventory."); return; }
                LData d = RewardChests.data(q);
                if (Sky.items().exists(ess)) {
                    boolean items = d.essenceAsItems != null ? d.essenceAsItems : DDials.ESSENCE_AS_ITEMS_DEFAULT.get();
                    if (items) for (ItemStack left : q.getInventory().addItem(Sky.items().create(ess, amount)).values()) Sky.loot().dropFor(q, q.getLocation(), left);
                    else Sky.loot().give(q, ess, amount, LootSource.REWARD);
                }
                Msg.success(q, "Salvaged for " + amount + " " + Text.titleCase(s.essence()) + " Essence.");
                Msg.sound(q, Sound.BLOCK_GRINDSTONE_USE, 1f);
                salvage(q);
            }, () -> salvage(q));
        });
        m.open(p);
    }

    static boolean isBazaar(String id) {
        try { return Sky.service(Features.Bazaar.class).isBazaarItem(id); } catch (RuntimeException e) { return false; }
    }

    /**
     * Remove one instance: the exact uuid for unique items, else one plain copy of the id (never a starred or
     * recombobulated one - the confirm screen promised those are refused).
     */
    static boolean removeOne(Player p, String id, String uuid) {
        if (uuid == null) {
            ItemStack[] inv = p.getInventory().getStorageContents();
            for (int i = 0; i < inv.length; i++) {
                ItemStack s = inv[i];
                if (s == null || !id.equals(Sky.items().idOf(s))) continue;
                ItemData d = Sky.items().data(s);
                if (d.recombobulated || d.stars > 0) continue;
                if (s.getAmount() > 1) s.setAmount(s.getAmount() - 1); else p.getInventory().setItem(i, null);
                return true;
            }
            return false;
        }
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) {
            ItemStack s = inv[i];
            if (s == null || !id.equals(Sky.items().idOf(s))) continue;
            if (uuid.equals(Sky.items().data(s).uuid)) { p.getInventory().setItem(i, null); return true; }
        }
        return false;
    }

    @SuppressWarnings("unused")
    private static List<String> none() { return new ArrayList<>(); }
}
