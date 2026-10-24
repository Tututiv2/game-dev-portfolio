package com.skyclone.mod.crimson;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

/**
 * The armorsmith: raises a Leviathan-line piece one prestige (Basic -> Hot -> Burning -> Fiery ->
 * Infernal) for the cost the reference lists on the piece (Crimson Essence + Leviathan teeth + a coin part:
 * 2M / 5M / 10M / 20M per piece to Hot / Burning / Fiery / Infernal, audit K14, SkyHanni's table). The new
 * piece keeps its reforge, enchants, books, recombobulation and gems; stars reset, as in the reference.
 * Until the wave-2 Leviathan hunts exist, missing essence/teeth may be paid in coins (dial).
 */
final class Prestige {

    private Prestige() {}

    /** A piece found on the player: slot 0-35 = inventory, 36-39 = armor (boots..helmet). */
    record Piece(int slot, String id, String uuid, ItemStack stack) {}

    static List<Piece> pieces(Player p) {
        List<Piece> out = new ArrayList<>();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 40; i++) {
            ItemStack it = inv.getItem(i);
            String id = Sky.items().idOf(it);
            if (id == null) continue;
            ItemDef d = Sky.items().def(id);
            if (d == null || d.extra == null || !d.extra.has("kuudraSet")) continue;
            out.add(new Piece(i, id, Sky.items().data(it).uuid, it.clone()));
        }
        return out;
    }

    static List<String> cost(String id) {
        ItemDef d = Sky.items().def(id);
        List<String> out = new ArrayList<>();
        if (d == null || d.extra == null || !d.extra.has("prestigeCost")) return out;
        JsonArray a = d.extra.getAsJsonArray("prestigeCost");
        for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    /** The coin part of this piece's prestige (data {@code extra.prestigeCoins} x dial), 0 when none. */
    static double fixedCoins(String id) {
        ItemDef d = Sky.items().def(id);
        if (d == null || d.extra == null || !d.extra.has("prestigeCoins")) return 0;
        return Math.max(0, Math.round(d.extra.get("prestigeCoins").getAsDouble() * CrimsonModule.D.prestigeCoinScale.get()));
    }

    static String next(String id) {
        ItemDef d = Sky.items().def(id);
        if (d != null && d.extra != null && d.extra.has("prestigeTo")) return d.extra.get("prestigeTo").getAsString();
        return CrimsonRules.nextPrestige(id);
    }

    /** Coin value of one unit of a cost item when paid in coins. */
    static double coinValue(String item) {
        double bz = 0;
        try { bz = Sky.service(Features.Bazaar.class).instantBuy(item); } catch (RuntimeException ignored) { }
        if (bz > 0) return bz;
        return item.equals("KUUDRA_TEETH") ? CrimsonModule.D.teethValue.get() : CrimsonModule.D.essenceValue.get();
    }

    /** Coins needed to cover what the player lacks of a cost list, or -1 if coins are not accepted and something is missing. */
    static double shortfallCoins(Player p, List<String> cost) {
        double coins = 0;
        for (String c : cost) {
            Object[] cell = CrimsonRules.cell(c);
            int need = (int) cell[1] - CUtil.have(p, (String) cell[0]);
            if (need <= 0) continue;
            if (!CrimsonModule.D.coinsForPrestige.get()) return -1;
            coins += need * coinValue((String) cell[0]);
        }
        return coins;
    }

    static void menu(Player p, int page) {
        List<Piece> list = pieces(p);
        Menu m = Sky.menus().paged("Armor Prestige", list, pc -> icon(p, pc), click -> confirm(click.click().player(), click.entry()),
                mm -> {
                    mm.back("Crimson Isle", () -> CrimsonMenus.mainMenu(p));
                    mm.set(4, Icons.of(Material.ANVIL, "&6Armor Prestige", List.of(
                            "&7Leviathan-line armor (Crimson, Aurora,", "&7Terror, Fervor, Hollow) climbs five", "&7prestiges: &fBasic &7> &eHot &7> &6Burning &7> &cFiery &7> &4Infernal&7.",
                            "", "&7Each step raises the piece's stats by", "&7about a quarter and costs Crimson", "&7Essence, Leviathan teeth and coins.",
                            "", list.isEmpty() ? "&cYou carry no Leviathan-line armor." : "&7Pick a piece below.")));
                }, page);
        m.open(p);
    }

    private static ItemStack icon(Player p, Piece pc) {
        String nx = next(pc.id);
        List<String> lore = new ArrayList<>();
        lore.add("");
        if (nx == null || !Sky.items().exists(nx)) {
            lore.add("&6This piece is at its highest prestige.");
            return Icons.withLore(pc.stack, lore);
        }
        lore.add("&7Prestige to: " + CUtil.name(nx));
        lore.add("&7Cost:");
        List<String> cost = cost(pc.id);
        lore.addAll(CUtil.costLines(p, fixedCoins(pc.id), cost));
        double coins = shortfallCoins(p, cost);
        if (coins > 0) lore.add("&8(missing items paid as &6" + Text.commasInt(coins) + " coins&8)");
        if (coins < 0) lore.add("&cYou are missing materials.");
        lore.add("");
        lore.add("&eClick to prestige!");
        return Icons.withLore(pc.stack, lore);
    }

    private static void confirm(Player p, Piece pc) {
        String nx = next(pc.id);
        if (nx == null || !Sky.items().exists(nx)) { Msg.error(p, "That piece cannot be raised further."); return; }
        List<String> cost = cost(pc.id);
        double coins = shortfallCoins(p, cost);
        if (coins < 0) { Msg.error(p, "You are missing materials for this prestige."); return; }
        List<String> desc = new ArrayList<>(List.of("&7Raise " + CUtil.name(pc.id) + " &7to " + CUtil.name(nx) + "&7.", "", "&7Cost:"));
        desc.addAll(CUtil.costLines(p, fixedCoins(pc.id), cost));
        if (coins > 0) desc.add("&7+ &6" + Text.commasInt(coins) + " coins &7for what you lack");
        desc.add("");
        desc.add("&7Reforge, enchants and gems carry over.");
        desc.add("&cEssence stars reset.");
        Sky.menus().confirm(p, "Confirm Prestige", desc, () -> apply(p, pc, nx), null);
    }

    static boolean apply(Player p, Piece pc, String nx) {
        ItemStack cur = p.getInventory().getItem(pc.slot);
        if (!pc.id.equals(Sky.items().idOf(cur)) || pc.uuid == null || !pc.uuid.equals(Sky.items().data(cur).uuid)) {
            Msg.error(p, "That piece moved - open the menu again.");
            return false;
        }
        List<String> cost = cost(pc.id);
        double coins = shortfallCoins(p, cost);
        if (coins < 0) { Msg.error(p, "You are missing materials for this prestige."); return false; }
        double fixed = fixedCoins(pc.id);
        if (coins + fixed > 0 && Sky.economy().purse(p) < coins + fixed) {
            Msg.error(p, "You need " + Text.commasInt(coins + fixed) + " coins" + (coins > 0 ? " (prestige + the materials you lack)." : " for this prestige."));
            return false;
        }
        // pay: items first (what the player has), then coins for the rest
        List<Object[]> taken = new ArrayList<>();
        for (String c : cost) {
            Object[] cell = CrimsonRules.cell(c);
            String id = (String) cell[0];
            int n = Math.min((int) cell[1], CUtil.have(p, id));
            if (n > 0) taken.add(new Object[]{id, CUtil.take(p, id, n)});
        }
        if (coins + fixed > 0 && !Sky.economy().withdraw(p, coins + fixed, "crimson: prestige " + pc.id + " -> " + nx + (coins > 0 ? " (incl. materials)" : ""))) {
            for (Object[] t : taken) CUtil.refund(p, (String) t[0], (int) t[1]);
            Msg.error(p, "You don't have enough coins!");
            return false;
        }
        ItemData old = Sky.items().data(cur);
        ItemStack fresh = Sky.items().create(nx, 1);
        Sky.items().update(fresh, d -> {
            d.reforge = old.reforge;
            d.enchants.putAll(old.enchants);
            d.potatoBooks = old.potatoBooks;
            d.recombobulated = old.recombobulated;
            d.gems.putAll(old.gems);
            d.unlockedGemSlots.addAll(old.unlockedGemSlots);
            d.stars = 0;
        });
        p.getInventory().setItem(pc.slot, fresh);
        Msg.box(p, "&6&lPRESTIGE! " + CUtil.name(nx), List.of("&7The forge roars and your armor grows stronger."));
        Msg.sound(p, Sound.BLOCK_ANVIL_USE, 0.8f);
        Sky.stats().invalidate(p);
        Sky.profiles().saveNow(p);
        return true;
    }
}
