package com.skyclone.mod.enchanting;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code /sb enchanting ...}: every admin action takes a player name so it works over RCON. Output is synchronous. */
final class AdminCommands {

    private AdminCommands() {}

    private static final List<String> SUBS = List.of("list", "info", "apply", "remove", "book", "xp", "held", "open",
            "experiments", "progress", "calc", "mobtypes", "vitality", "wave5");

    static void register() {
        Sky.commands().admin("enchanting",
                "list [filter] | info <enchant> | apply <player> <enchant> <level> | remove <player> <enchant|all> | "
                        + "book <player> <enchant> <level> [n] | xp <player> <levels> | held <player> | "
                        + "open <player> table|anvil|experiments|guide|librarian|superpairs|chronomatron|ultrasequencer [tier] | "
                        + "experiments reset <player> | progress <player> <enchant> <amount> | calc <enchant> <level> | mobtypes <mobId> | "
                        + "vitality <player> [set <n>|spend <n>|fill] | wave5 <player>",
                "Enchanting admin: enchant held items, give books/levels, open any enchanting menu, reset experiments",
                AdminCommands::run, AdminCommands::tab);
    }

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }

    private static Player player(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) say(s, "&cNo online player " + name);
        return p;
    }

    private static int num(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static void run(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&e/sb enchanting " + String.join("|", SUBS)); return; }
        EnchantRegistry reg = EnchantingModule.reg();
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                String f = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                int n = 0;
                StringBuilder line = new StringBuilder();
                for (EnchantSpec e : reg.sorted()) {
                    if (!f.isEmpty() && !e.id.contains(f) && !e.name.toLowerCase(Locale.ROOT).contains(f)) continue;
                    n++;
                    line.append(e.ultimate ? "&d" : "&9").append(e.id).append("&7(").append(e.minLevel).append("-").append(e.maxLevel)
                            .append(e.onTable() ? ",t" + e.tableMax : "").append(") ");
                    if (line.length() > 300) { say(s, line.toString()); line.setLength(0); }
                }
                if (line.length() > 0) say(s, line.toString());
                say(s, "&a" + n + " enchantments (" + reg.size() + " total; " + EnchantingModule.bookItems + " book items + "
                        + EnchantingModule.aliasItems + " alias book items; " + EnchantingModule.aliasEnchants + " alias enchant ids)");
            }
            case "info", "calc" -> {
                EnchantSpec e = a.length > 1 ? reg.spec(a[1]) : null;
                if (e == null) { say(s, "&cUnknown enchant"); return; }
                int lvl = a.length > 2 ? num(a[2], e.maxLevel) : e.maxLevel;
                say(s, (e.ultimate ? "&d&l" : "&9") + e.name + " &7(" + e.id + ", ref: " + e.referenceName + ")");
                say(s, "&7levels " + e.minLevel + "-" + e.maxLevel + ", table " + e.tableMax + ", anvil cap "
                        + AnvilRules.anvilCap(e, EnchantingModule.dials().anvilOverTable.get()) + ", skill " + e.skill + ", combinable " + e.combinable);
                say(s, "&7applies to " + e.categories + " " + e.tags + " " + e.itemIds);
                for (int i = 0; i < e.series(); i++) {
                    List<String> vals = new ArrayList<>();
                    for (int l = 1; l <= e.maxLevel; l++) vals.add(EnchantSpec.fmt(e.v(i, l)));
                    say(s, "&7series " + i + ": &a" + String.join(" / ", vals));
                }
                say(s, "&7at " + EnchantSpec.roman(lvl) + ": " + e.describe(lvl));
                say(s, "&7table cost: " + e.cost(lvl) + " levels; conflicts " + e.conflicts.size());
            }
            case "apply" -> {
                if (a.length < 4) { say(s, "&cUsage: apply <player> <enchant> <level>"); return; }
                Player p = player(s, a[1]);
                EnchantSpec e = reg.spec(a[2]);
                if (p == null) return;
                if (e == null) { say(s, "&cUnknown enchant " + a[2]); return; }
                int lvl = Math.max(1, Math.min(num(a[3], 1), 10));
                ItemStack held = p.getInventory().getItemInMainHand();
                if (!Gear.isSky(held)) { say(s, "&c" + p.getName() + " holds no sky item"); return; }
                Sky.items().update(held, d -> d.enchants.put(e.id, lvl));
                Sky.stats().invalidate(p);
                say(s, "&aApplied " + e.name + " " + EnchantSpec.roman(lvl) + " to " + p.getName() + "'s " + Sky.items().idOf(held)
                        + (e.appliesTo(Gear.def(held)) ? "" : " &e(normally not allowed on this item)"));
            }
            case "remove" -> {
                if (a.length < 3) { say(s, "&cUsage: remove <player> <enchant|all>"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                ItemStack held = p.getInventory().getItemInMainHand();
                if (!Gear.isSky(held)) { say(s, "&cNo sky item held"); return; }
                String what = a[2].toLowerCase(Locale.ROOT);
                Sky.items().update(held, d -> { if (what.equals("all")) d.enchants.clear(); else d.enchants.remove(what); });
                Sky.stats().invalidate(p);
                say(s, "&aRemoved " + what);
            }
            case "book" -> {
                if (a.length < 4) { say(s, "&cUsage: book <player> <enchant> <level> [n]"); return; }
                Player p = player(s, a[1]);
                EnchantSpec e = reg.spec(a[2]);
                if (p == null) return;
                if (e == null) { say(s, "&cUnknown enchant " + a[2]); return; }
                int lvl = num(a[3], 1);
                if (lvl < e.minLevel || lvl > e.maxLevel) { say(s, "&cLevel must be " + e.minLevel + "-" + e.maxLevel); return; }
                int n = a.length > 4 ? Math.max(1, Math.min(64, num(a[4], 1))) : 1;
                Sky.loot().give(p, EnchantRegistry.bookId(e.id, lvl), n, LootSource.ADMIN);
                say(s, "&aGave " + n + "x " + EnchantRegistry.bookId(e.id, lvl) + " to " + p.getName());
            }
            case "xp" -> {
                if (a.length < 3) { say(s, "&cUsage: xp <player> <levels>"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                p.giveExpLevels(num(a[2], 0));
                say(s, "&a" + p.getName() + " now has " + p.getLevel() + " levels");
            }
            case "held" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                ItemStack held = p.getInventory().getItemInMainHand();
                if (!Gear.isSky(held)) { say(s, "&cNo sky item held"); return; }
                ItemData d = Sky.items().data(held);
                say(s, "&e" + Sky.items().idOf(held) + " &7enchants=" + d.enchants + " potato=" + d.potatoBooks + " extra=" + d.extra);
                for (Map.Entry<String, Integer> en : d.enchants.entrySet()) {
                    EnchantSpec e = reg.spec(en.getKey());
                    if (e != null) say(s, "&7- " + e.name + " " + EnchantSpec.roman(en.getValue()) + ": " + e.describe(en.getValue()));
                }
                say(s, "&7worn pieces cached: " + Gear.worn(p).size() + ", soul stored " + EnchantSpec.fmt(CombatEnchants.soulStored(p.getUniqueId())));
            }
            case "open" -> {
                if (a.length < 3) { say(s, "&cUsage: open <player> <menu> [tier]"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                String tierId = a.length > 3 ? a[3] : "beginner";
                switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "table" -> TableMenu.open(p, BlockListener.nearbyPower(p, EnchantingModule.dials().commandPower.get()));
                    case "anvil" -> AnvilMenu.open(p);
                    case "experiments" -> ExperimentsMenu.open(p);
                    case "guide" -> GuideMenu.open(p, null, 0);
                    case "librarian" -> LibrarianMenu.open(p);
                    case "superpairs", "chronomatron", "ultrasequencer" -> {
                        ExperimentSpec.Tier t = EnchantingModule.experiments().tier(tierId);
                        if (t == null) { say(s, "&cUnknown tier " + tierId); return; }
                        ExperimentsMenu.start(p, a[2].toLowerCase(Locale.ROOT), t);
                    }
                    default -> { say(s, "&cUnknown menu " + a[2]); return; }
                }
                say(s, "&aOpened " + a[2] + " for " + p.getName());
            }
            case "experiments" -> {
                if (a.length < 3 || !a[1].equalsIgnoreCase("reset")) { say(s, "&cUsage: experiments reset <player>"); return; }
                Player p = player(s, a[2]);
                if (p == null) return;
                PlayerData d = EnchantingModule.daily(p);
                d.superpairsToday = 0;
                d.extraRunsToday = 0;
                d.charges = EnchantingModule.dials().experimentsMaxCharges.get();
                d.chargeAt = 0;
                d.chronomatronDone = false;
                d.ultrasequencerDone = false;
                say(s, "&aReset today's experiments for " + p.getName());
            }
            case "progress" -> {
                if (a.length < 4) { say(s, "&cUsage: progress <player> <enchant> <amount>"); return; }
                Player p = player(s, a[1]);
                EnchantSpec e = reg.spec(a[2]);
                if (p == null) return;
                if (e == null || !e.levelsByUse()) { say(s, "&cNot a level-by-use enchant"); return; }
                ItemStack held = p.getInventory().getItemInMainHand();
                if (Gear.level(held, e.id) <= 0) { say(s, "&cHeld item lacks " + e.name); return; }
                long amt = Math.max(0, num(a[3], 0));
                Sky.items().update(held, d -> {
                    long v = StatEnchants.counter(d, e.id) + amt;
                    d.extra.add(StatEnchants.counterKey(e.id), new JsonPrimitive(v));
                    d.enchants.put(e.id, Math.max(d.enchants.getOrDefault(e.id, 1), e.levelForCounter(v)));
                });
                say(s, "&a" + e.name + " is now " + EnchantSpec.roman(Gear.level(held, e.id)));
            }
            case "mobtypes" -> {
                MobDef md = a.length > 1 ? Sky.mobs().def(a[1]) : null;
                if (md == null) { say(s, "&cUnknown mob id"); return; }
                say(s, "&a" + md.id + " &7(" + md.entity + "): " + EnchantMath.mobTypes(md.entity, md.flags, null, md.id));
            }
            case "vitality" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) { if (a.length <= 1) say(s, "&e/sb enchanting vitality <player> [set <n>|spend <n>|fill]"); return; }
                String op = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                double n = a.length > 3 ? num(a[3], 0) : 0;
                switch (op) {
                    case "set" -> Vitality.set(p, n);
                    case "spend" -> say(s, "&7spent &4" + EnchantSpec.fmt(Vitality.spend(p, n)) + "♨");
                    case "fill" -> Vitality.set(p, Vitality.max(p));
                    default -> { }
                }
                say(s, "&a" + p.getName() + "&7: Vitality &4" + EnchantSpec.fmt(Math.floor(Vitality.current(p) * 10) / 10.0) + "/"
                        + EnchantSpec.fmt(Vitality.max(p)) + "♨ &7(pool " + (Vitality.on() ? "&aon" : "&coff") + "&7, regen "
                        + EnchantSpec.fmt(EnchantingModule.dials().vitalityRegen.get()) + "%/s)");
                say(s, "&7costs: " + Vitality.costs() + " &8soft: " + Vitality.softCosts());
            }
            case "wave5" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                say(s, Wave5Enchants.describe(p));
                say(s, "&7requirement pressure_resistance:20 -> " + (Sky.requirements().check(p, "pressure_resistance:20") ? "&ayes" : "&cno")
                        + " &8| &7multi-number lore: " + (LevelledEnchantDef.coreRendersFullText() ? "&acore renders it" : "&ebooks/guide/table only (core request 1)"));
            }
            default -> say(s, "&cUnknown sub. " + String.join(", ", SUBS));
        }
    }

    private static List<String> tab(CommandSender s, String[] a) {
        if (a.length <= 1) return SUBS;
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2 && !sub.equals("list") && !sub.equals("info") && !sub.equals("calc") && !sub.equals("mobtypes") && !sub.equals("experiments")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        if ((a.length == 2 && (sub.equals("info") || sub.equals("calc"))) || (a.length == 3 && (sub.equals("apply") || sub.equals("book") || sub.equals("progress")))) {
            String pre = a[a.length - 1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (EnchantSpec e : EnchantingModule.reg().all()) if (e.id.startsWith(pre)) out.add(e.id);
            return out;
        }
        if (a.length == 3 && sub.equals("open")) return List.of("table", "anvil", "experiments", "guide", "librarian", "superpairs", "chronomatron", "ultrasequencer");
        if (a.length == 4 && sub.equals("open")) return List.of("beginner", "high", "grand", "supreme", "transcendent", "metaphysical");
        if (a.length == 2 && sub.equals("experiments")) return List.of("reset");
        if (a.length == 3 && sub.equals("vitality")) return List.of("set", "spend", "fill");
        return List.of();
    }
}
