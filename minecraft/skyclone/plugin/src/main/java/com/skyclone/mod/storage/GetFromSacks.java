package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /gfs <item> [amount]} (reference: {@code /getfromsacks}): pull items straight out of your sacks without
 * opening a menu. The item is an id ({@code enchanted_diamond}) or a display name ({@code enchanted diamond});
 * the amount may be a number, {@code 2k}, or {@code all}, and defaults to one stack. Sack contents only (the
 * quiver is a menu of its own).
 */
final class GetFromSacks {

    private final StorageModule mod;

    GetFromSacks(StorageModule mod) { this.mod = mod; }

    void register() {
        Sky.commands().register(SkyCommand.of("gfs", "Take items out of your sacks: /gfs <item> [amount]")
                .alias("getfromsacks", "getfromsack").usage("<item> [amount|all]").category("Storage").playerOnly()
                .run((s, a) -> run((Player) s, a))
                .tab(this::complete));
    }

    void run(Player p, String[] args) {
        if (!Store.ready(p)) { Msg.error(p, "Your profile is still loading, try again in a moment."); return; }
        if (args.length == 0) {
            Msg.send(p, "&eUsage: &a/gfs <item> [amount]&7 - e.g. &a/gfs enchanted_diamond 64&7, &a/gfs wheat all");
            return;
        }
        List<String> words = new ArrayList<>(Arrays.asList(args));
        long amount = -2;   // not given
        if (words.size() > 1) {
            long a = StorageMath.gfsAmount(words.get(words.size() - 1), -1);
            if (a > 0) { amount = a; words.remove(words.size() - 1); }
        }
        String query = String.join(" ", words);
        // stored items first (what the player most likely means), then everything a sack can hold
        Map<String, String> stored = names(p, true);
        List<String> hits = StorageMath.matchItem(query, stored);
        if (hits.isEmpty()) {
            List<String> any = StorageMath.matchItem(query, names(p, false));
            if (any.size() == 1) {
                Msg.error(p, "You have no " + display(any.get(0)) + " &cin your sacks!");
            } else if (any.isEmpty()) {
                Msg.error(p, "No sack holds an item called \"" + query + "\".");
            } else {
                Msg.error(p, "You have none of those in your sacks. Did you mean: " + suggest(any) + "&c?");
            }
            return;
        }
        if (hits.size() > 1) {
            Msg.error(p, "\"" + query + "\" matches " + hits.size() + " items. Did you mean: " + suggest(hits) + "&c?");
            return;
        }
        String id = hits.get(0);
        if (amount == -2) {
            ItemStack proto = Sky.items().exists(id) ? Sky.items().create(id, 1) : null;
            amount = proto == null ? 64 : Math.max(1, proto.getMaxStackSize());
        }
        long have = mod.sacks().stored(p, id);
        int moved = mod.sacks().takeAmount(p, id, amount);
        if (moved <= 0) return;   // takeAmount said why (inventory full)
        long left = Math.max(0, have - moved);
        Msg.send(p, "&6[Sacks] &eMoved &a" + Text.commasInt(moved) + "x " + display(id) + " &efrom your Sacks to your inventory. &8("
                + Text.commasInt(left) + " left)");
        Msg.sound(p, Sound.ENTITY_ITEM_PICKUP, 1.2f);
        if (amount != Long.MAX_VALUE && moved < amount && moved < have) Msg.send(p, "&7Your inventory had room for " + Text.commasInt(moved) + ".");
        else if (amount != Long.MAX_VALUE && moved < amount) Msg.send(p, "&7That was all of it.");
    }

    /** id -> plain name of every sack content id that exists; {@code onlyStored} keeps the ones this player holds. */
    private Map<String, String> names(Player p, boolean onlyStored) {
        Map<String, String> out = new LinkedHashMap<>();
        if (onlyStored) {
            List<Map.Entry<String, Long>> st = new ArrayList<>(mod.store().sacks(p).stored.entrySet());
            st.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
            for (Map.Entry<String, Long> en : st) if (en.getValue() > 0) out.put(en.getKey(), plain(en.getKey()));
            return out;
        }
        for (String id : mod.catalog().allContents()) if (Sky.items().exists(id)) out.put(id, plain(id));
        return out;
    }

    private static String plain(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? id : Text.strip(d.name);
    }

    private static String display(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? "&f" + id : d.rarity.code() + d.name;
    }

    private static String suggest(List<String> ids) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(5, ids.size()); i++) {
            if (i > 0) b.append("&c, ");
            b.append("&a").append(ids.get(i).toLowerCase(Locale.ROOT));
        }
        if (ids.size() > 5) b.append(" &c(+").append(ids.size() - 5).append(" more)");
        return b.toString();
    }

    private List<String> complete(CommandSender s, String[] a) {
        if (!(s instanceof Player p) || !Store.ready(p) || a.length != 1) {
            return a.length == 2 ? List.of("64", "all") : List.of();
        }
        String pre = a[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String id : mod.store().sacks(p).stored.keySet()) {
            String low = id.toLowerCase(Locale.ROOT);
            if (low.startsWith(pre)) out.add(low);
            if (out.size() >= 50) break;
        }
        out.sort(null);
        return out;
    }
}
