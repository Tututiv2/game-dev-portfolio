package com.skyclone.core.items;

import com.skyclone.api.Sky;
import com.skyclone.api.command.CommandService;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /sb give, /sb item ..., /sb items, /sb stats, /sb heal, /sb mana, /sb dupes. */
final class ItemsAdmin {

    private ItemsAdmin() {}

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }

    static void register(ItemServiceImpl items, StatServiceImpl stats, VitalsServiceImpl vitals) {
        CommandService c = Sky.commands();

        c.admin("give", "<player> <item id> [amount]", "Give a sky item (tab-completes ids)",
                (s, a) -> give(items, s, a),
                (s, a) -> {
                    if (a.length <= 1) return players(a.length == 0 ? "" : a[0]);
                    if (a.length == 2) return ids(items, a[1]);
                    if (a.length == 3) return List.of("1", "16", "64");
                    return List.of();
                });

        c.admin("item", "<info|rename|refresh> ...", "Held item info, admin renames, re-render",
                (s, a) -> item(items, s, a),
                (s, a) -> {
                    if (a.length <= 1) return filter(List.of("info", "rename", "refresh"), a.length == 0 ? "" : a[0]);
                    if (a.length == 2 && a[0].equalsIgnoreCase("rename")) return ids(items, a[1]);
                    if (a.length == 2 && a[0].equalsIgnoreCase("refresh")) return filter(List.of("all"), a[1]);
                    if (a.length == 3 && a[0].equalsIgnoreCase("rename")) return List.of("reset");
                    return List.of();
                });

        c.admin("items", "[filter]", "Browse every item; click to take one (shift: a stack)",
                (s, a) -> browse(items, s, String.join(" ", a), 0),
                (s, a) -> List.of());

        c.admin("stats", "[player]", "Dump every stat of a player with where it comes from",
                (s, a) -> statsDump(stats, vitals, s, a),
                (s, a) -> a.length <= 1 ? players(a.length == 0 ? "" : a[0]) : List.of());

        c.admin("heal", "[player]", "Full health, mana and hunger",
                (s, a) -> {
                    Player t = target(s, a, 0);
                    if (t == null) return;
                    vitals.addAbsorption(t, -vitals.absorption(t));
                    vitals.setHealth(t, vitals.maxHealth(t));
                    vitals.addMana(t, vitals.maxMana(t));
                    t.setFoodLevel(20);
                    t.setFireTicks(0);
                    say(s, "&aHealed " + t.getName() + ".");
                },
                (s, a) -> a.length <= 1 ? players(a.length == 0 ? "" : a[0]) : List.of());

        c.admin("mana", "[player]", "Refill mana",
                (s, a) -> {
                    Player t = target(s, a, 0);
                    if (t == null) return;
                    vitals.addMana(t, vitals.maxMana(t));
                    say(s, "&bRefilled " + t.getName() + "'s mana.");
                },
                (s, a) -> a.length <= 1 ? players(a.length == 0 ? "" : a[0]) : List.of());

        c.admin("dupes", "[count]", "Recent anti-dupe log entries",
                (s, a) -> {
                    int n = 15;
                    try { if (a.length > 0) n = Math.max(1, Math.min(100, Integer.parseInt(a[0]))); } catch (NumberFormatException ignored) {}
                    DupeScanner.recent(n).whenComplete((lines, err) -> Bukkit.getScheduler().runTask(ItemsCore.plugin(), () -> {
                        if (err != null) { say(s, "&cCould not read the dupe log: " + err.getMessage()); return; }
                        if (lines.isEmpty()) { say(s, "&aNo dupes logged."); return; }
                        say(s, "&c&lDupe log &7(newest first)");
                        for (String l : lines) say(s, l);
                    }));
                });
    }

    // ---- give ------------------------------------------------------------------------------

    private static void give(ItemServiceImpl items, CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cUsage: /sb give <player> <item id> [amount]"); return; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { say(s, "&cNo online player " + a[0]); return; }
        String id = resolveId(items, a[1]);
        if (id == null) { say(s, "&cUnknown item id " + a[1]); return; }
        int amount = 1;
        if (a.length >= 3) {
            try { amount = Integer.parseInt(a[2]); } catch (NumberFormatException e) { say(s, "&cBad amount " + a[2]); return; }
        }
        ItemDef def = items.def(id);
        int cap = def.isUnique() ? 64 : 36 * def.effectiveMaxStack();
        amount = Math.max(1, Math.min(cap, amount));
        int left = amount, ground = 0;
        while (left > 0) {
            int n = Math.min(left, def.effectiveMaxStack());
            ground += giveStack(t, items.create(id, n));
            left -= n;
        }
        say(s, "&aGave &f" + amount + "x " + def.rarity.code() + Text.strip(items.displayName(def)) + " &ato " + t.getName()
                + (ground > 0 ? " &7(" + ground + " dropped: inventory full)" : ""));
        Sky.log().info("[admin] " + s.getName() + " gave " + amount + "x " + id + " to " + t.getName());
    }

    /** Through the loot service when it exists (sacks, ItemGain), else straight into the inventory. */
    static int giveStack(Player t, ItemStack st) {
        try {
            return Sky.loot().give(t, st, LootSource.ADMIN);
        } catch (RuntimeException e) {
            int n = 0;
            for (ItemStack left : t.getInventory().addItem(st).values()) {
                n += left.getAmount();
                t.getWorld().dropItemNaturally(t.getLocation(), left);
            }
            return n;
        }
    }

    static String resolveId(ItemServiceImpl items, String raw) {
        if (raw == null) return null;
        if (items.exists(raw)) return raw;
        String up = raw.toUpperCase(Locale.ROOT);
        if (items.exists(up)) return up;
        for (ItemDef d : items.all()) if (d.id.equalsIgnoreCase(raw)) return d.id;
        return null;
    }

    // ---- item ------------------------------------------------------------------------------

    private static void item(ItemServiceImpl items, CommandSender s, String[] a) {
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> {
                if (!(s instanceof Player p)) { say(s, "&cPlayers only."); return; }
                ItemStack held = p.getInventory().getItemInMainHand();
                if (held.getType().isAir()) { say(s, "&cHold an item."); return; }
                String id = items.idOf(held);
                if (id == null) {
                    say(s, "&eNot a sky item. &7Material " + held.getType() + " converts to &f" + items.vanillaId(held.getType())
                            + (items.convertible(held) ? "" : " &c(not convertible: carries vanilla data)"));
                    return;
                }
                ItemDef d = items.def(id);
                say(s, "&6&lItem &f" + id + (d == null ? " &c(UNKNOWN id: not registered)" : ""));
                if (d != null) {
                    say(s, "&7Name: &f" + items.displayName(d) + (items.renameOf(id) != null ? " &8(renamed; def: " + d.name + "&8)" : "")
                            + (items.isAutoVanilla(id) ? " &8(auto vanilla def)" : ""));
                    say(s, "&7Material: &f" + items.material(d) + " &7Rarity: " + items.rarity(held).code() + items.rarity(held)
                            + " &7Category: &f" + d.category + " &7Max stack: &f" + d.effectiveMaxStack() + (d.isUnique() ? " &d(unique)" : ""));
                    if (!d.tags.isEmpty()) say(s, "&7Tags: &f" + d.tags);
                    if (d.setId != null) say(s, "&7Set: &f" + d.setId);
                    if (!d.requirements.isEmpty()) say(s, "&7Requires: &f" + d.requirements);
                    for (AbilityDef ab : d.abilities)
                        say(s, "&7Ability: &6" + ab.name + " &8(" + ab.id + ", " + ab.trigger + ", mana " + ab.mana + ", cd " + ab.cooldown + "s"
                                + (ItemsCore.abilities().hasHandler(ab.id) ? "" : ", &cNO HANDLER&8") + ")");
                }
                ItemData data = items.data(held);
                String json = ItemDataCodec.write(data);
                say(s, "&7Data: &f" + (json.isEmpty() ? "{}" : json));
                ItemStatSheet sh = items.sheet(held);
                StringBuilder sb = new StringBuilder("&7Sheet:");
                if (sh.damage() != 0) sb.append(" &cDamage ").append(LoreRenderer.num(sh.damage()));
                for (Map.Entry<Stat, java.util.EnumMap<ItemStatSheet.Source, Double>> e : sh.view().entrySet())
                    sb.append(" &f").append(e.getKey().display).append(' ').append(LoreRenderer.num(sh.total(e.getKey())));
                say(s, sb.toString());
            }
            case "rename" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb item rename <id> <name...|reset>"); return; }
                String id = resolveId(items, a[1]);
                if (id == null) { say(s, "&cUnknown item id " + a[1]); return; }
                String name = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                if (name.equalsIgnoreCase("reset")) {
                    items.rename(id, null);
                    say(s, "&aRename of " + id + " cleared.");
                } else {
                    if (name.length() > 80) { say(s, "&cName too long (80 max)."); return; }
                    items.rename(id, name);
                    say(s, "&a" + id + " is now shown as &r" + name);
                }
                Sky.log().info("[admin] " + s.getName() + " renamed " + id + " to " + name);
            }
            case "refresh" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("all")) {
                    for (Player p : Bukkit.getOnlinePlayers()) { items.sweep(p, false); items.refreshInventory(p, null); }
                    say(s, "&aRe-rendered every sky item held by " + Bukkit.getOnlinePlayers().size() + " players.");
                    return;
                }
                if (!(s instanceof Player p)) { say(s, "&cPlayers only (or /sb item refresh all)."); return; }
                ItemStack held = p.getInventory().getItemInMainHand();
                if (items.idOf(held) == null) { say(s, "&cHold a sky item."); return; }
                items.refresh(held);
                p.getInventory().setItemInMainHand(held);
                say(s, "&aRe-rendered.");
            }
            default -> say(s, "&cUsage: /sb item <info|rename|refresh>");
        }
    }

    // ---- items browser ------------------------------------------------------------------------

    static void browse(ItemServiceImpl items, CommandSender s, String filter, int page) {
        if (!(s instanceof Player p)) { say(s, "&cPlayers only."); return; }
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<ItemDef> list = new ArrayList<>();
        for (ItemDef d : items.all()) {
            if (f.isEmpty() || d.id.toLowerCase(Locale.ROOT).contains(f) || Text.strip(items.displayName(d)).toLowerCase(Locale.ROOT).contains(f))
                list.add(d);
        }
        // module items first (auto vanilla defs last), then by id
        list.sort(Comparator.comparing((ItemDef d) -> items.isAutoVanilla(d.id)).thenComparing(d -> d.id));
        if (list.isEmpty()) { say(s, "&cNo item matches '" + filter + "'."); return; }
        String title = "Items" + (f.isEmpty() ? "" : ": " + (f.length() > 16 ? f.substring(0, 16) : f)) + " (" + list.size() + ")";
        Menu m = Sky.menus().paged(title, list,
                d -> {
                    ItemStack icon;
                    try { icon = items.create(d.id, 1); } catch (RuntimeException e) { icon = Icons.of(org.bukkit.Material.BARRIER, "&c" + d.id); }
                    return Icons.withLore(icon, List.of("", "&8" + d.id, "&eClick to take 1!", "&eShift-click for a stack!"));
                },
                click -> {
                    Player who = click.click().player();
                    if (!who.hasPermission("skyclone.admin")) return;
                    ItemDef d = click.entry();
                    int n = click.click().shift() ? d.effectiveMaxStack() : 1;
                    giveStack(who, items.create(d.id, n));
                    Sky.log().info("[admin] " + who.getName() + " took " + n + "x " + d.id + " from /sb items");
                },
                menu -> {}, page);
        if (m != null && Sky.menus().current(p) != m) m.open(p);
    }

    // ---- stats -----------------------------------------------------------------------------

    private static void statsDump(StatServiceImpl stats, VitalsServiceImpl vitals, CommandSender s, String[] a) {
        Player t = target(s, a, 0);
        if (t == null) return;
        stats.recompute(t);
        say(s, "&6&lStats of " + t.getName() + " &7(providers: " + String.join(", ", stats.providerIds()) + ")");
        say(s, "&c" + Text.commasInt(vitals.health(t)) + "/" + Text.commasInt(vitals.maxHealth(t)) + "❤ &6+"
                + Text.commasInt(vitals.absorption(t)) + " absorption &b" + Text.commasInt(vitals.mana(t)) + "/"
                + Text.commasInt(vitals.maxMana(t)) + "✎");
        for (Stat st : Stat.values()) {
            double v = stats.get(t, st);
            Map<String, Double> b = stats.breakdown(t, st);
            if (v == 0 && b.isEmpty()) continue;
            StringBuilder sb = new StringBuilder(" ").append(st.label()).append("&7: &f").append(LoreRenderer.num(v)).append(st.percent ? "%" : "");
            if (!b.isEmpty()) {
                sb.append(" &8(");
                boolean first = true;
                for (Map.Entry<String, Double> e : b.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(e.getKey()).append(' ').append(LoreRenderer.num(e.getValue()));
                    first = false;
                }
                sb.append(')');
            }
            say(s, sb.toString());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static Player target(CommandSender s, String[] a, int idx) {
        if (a.length > idx) {
            Player t = Bukkit.getPlayerExact(a[idx]);
            if (t == null) say(s, "&cNo online player " + a[idx]);
            return t;
        }
        if (s instanceof Player p) return p;
        say(s, "&cName a player.");
        return null;
    }

    private static List<String> players(String prefix) {
        List<String> l = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) l.add(p.getName());
        return l;
    }

    private static List<String> ids(ItemServiceImpl items, String prefix) {
        String up = prefix.toUpperCase(Locale.ROOT);
        List<String> l = new ArrayList<>();
        for (ItemDef d : items.all()) if (d.id.startsWith(up)) l.add(d.id);
        l.sort(null);
        return l.size() > 100 ? l.subList(0, 100) : l;
    }

    private static List<String> filter(List<String> opts, String prefix) {
        List<String> l = new ArrayList<>();
        for (String o : opts) if (o.startsWith(prefix.toLowerCase(Locale.ROOT))) l.add(o);
        return l;
    }
}
