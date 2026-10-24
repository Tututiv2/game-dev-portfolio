package com.skyclone.mod.reforges;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Player commands (each also reachable from the /reforges hub menu) and {@code /sb reforges ...}. */
final class Commands {

    private Commands() {}

    static void register() {
        Sky.commands().register(SkyCommand.of("reforges", "Open the reforging hub: blacksmith, anvil, essence, gems, guide")
                .alias("reforging").category("Items").playerOnly().run((s, a) -> Menus.hub((Player) s)));
        Sky.commands().register(SkyCommand.of("reforge", "Open the Blacksmith's Reforge Item menu (random reforges)")
                .alias("blacksmith").category("Items").playerOnly().run((s, a) -> Menus.blacksmith((Player) s)));
        Sky.commands().register(SkyCommand.of("reforgeanvil", "Open the Reforge Anvil: stones, potato tomes, reshaper, master stars, enrichments")
                .alias("ranvil").category("Items").playerOnly().run((s, a) -> Menus.anvil((Player) s)));
        Sky.commands().register(SkyCommand.of("essence", "Open Essence & Stars: your essence and star upgrades")
                .alias("stars").category("Items").playerOnly().run((s, a) -> Menus.essence((Player) s)));
        Sky.commands().register(SkyCommand.of("gemstones", "Open the Gemstone Grinder: unlock slots, set and remove gems")
                .alias("gemgrinder").category("Items").playerOnly().run((s, a) -> Menus.gems((Player) s)));
        Sky.commands().register(SkyCommand.of("reforgeguide", "Browse every reforge with its stats and bonus")
                .category("Items").playerOnly().run((s, a) -> Menus.guide((Player) s, 0, 0)));
        Sky.commands().register(SkyCommand.of("reforgeshop", "Open the reforge stone merchant")
                .category("Items").playerOnly().run((s, a) -> Menus.shop((Player) s, 0)));

        Sky.commands().admin("reforges", "<list|info|apply|random|upgrade|stars|potato|recomb|gem|unlock|essence|givestones|open|bonuses|clear> ...",
                "Reforge module test tools (held item of <player>)", Commands::admin,
                (s, a) -> a.length <= 1 ? List.of("list", "info", "apply", "random", "upgrade", "stars", "potato", "recomb", "gem", "unlock", "essence",
                        "givestones", "open", "bonuses", "clear") : List.of());
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player target(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) say(s, "&cNo online player " + name);
        return p;
    }

    private static ItemStack heldSky(CommandSender s, Player p) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (Gear.def(it) == null) { say(s, "&c" + p.getName() + " is not holding a sky item."); return null; }
        return it;
    }

    private static void admin(CommandSender s, String[] a) {
        if (a.length == 0) { usage(s); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        String[] r = Arrays.copyOfRange(a, 1, a.length);
        try {
            switch (sub) {
                case "list" -> list(s, r);
                case "info" -> { if (r.length >= 1) info(s, r[0]); else say(s, "&e/sb reforges info <player>"); }
                case "apply" -> apply(s, r);
                case "random" -> random(s, r);
                case "upgrade" -> upgrade(s, r);
                case "stars" -> setData(s, r, "stars", (d, n) -> d.stars = Math.max(0, Math.min(15, n)));
                case "potato" -> setData(s, r, "potato", (d, n) -> d.potatoBooks = Math.max(0, Math.min(ReforgeData.POTATO_MAX, n)));
                case "recomb" -> setData(s, new String[]{r.length > 0 ? r[0] : "", r.length > 1 ? r[1] : "1"}, "recomb", (d, n) -> d.recombobulated = n != 0);
                case "gem" -> gem(s, r);
                case "unlock" -> setData(s, r, "unlock", (d, n) -> d.unlockedGemSlots.add(String.valueOf(n)));
                case "essence" -> essence(s, r);
                case "givestones" -> giveStones(s, r);
                case "open" -> open(s, r);
                case "bonuses" -> bonuses(s, r);
                case "clear" -> setData(s, new String[]{r.length > 0 ? r[0] : "", "0"}, "clear", (d, n) -> {
                    d.reforge = null; d.stars = 0; d.potatoBooks = 0; d.recombobulated = false; d.gems.clear(); d.unlockedGemSlots.clear();
                    for (String k : ItemMods.ANVIL_KEYS) d.extra.remove(k);
                });
                default -> usage(s);
            }
        } catch (NumberFormatException e) {
            say(s, "&cBad number: " + e.getMessage());
        }
    }

    private static void usage(CommandSender s) {
        say(s, "&e/sb reforges list [filter]           &7all reforges (id, kind, fits, stone)");
        say(s, "&e/sb reforges info <player>           &7held item: reforge, stars, gems, potato, stat sheet");
        say(s, "&e/sb reforges apply <player> <id>     &7put reforge <id> on the held item (free)");
        say(s, "&e/sb reforges random <player>         &7one free Blacksmith roll on the held item");
        say(s, "&e/sb reforges upgrade <player> <ITEM_ID>  &7free anvil combine with that upgrade (stone, tome, Polarvoid...)");
        say(s, "&e/sb reforges stars <player> <n>      &7set stars (6-10 = master stars on dungeon items)");
        say(s, "&e/sb reforges potato <player> <n>     &7set potato tomes (0-15)");
        say(s, "&e/sb reforges recomb <player> [0|1]   &7set the rarity-reshaped flag");
        say(s, "&e/sb reforges gem <player> <slot> <GEM_ID|none>  &7set or clear a gem");
        say(s, "&e/sb reforges unlock <player> <slot>  &7unlock a gem slot");
        say(s, "&e/sb reforges essence <player> <TYPE> <add|set|take> <n>");
        say(s, "&e/sb reforges givestones <player> [filter]  &7one of every stone (matching filter)");
        say(s, "&e/sb reforges open <player> <hub|blacksmith|anvil|essence|gems|guide|shop>");
        say(s, "&e/sb reforges bonuses <player>        &7live bonus state: Mantid stacks, scroll buffs, Chomp count");
        say(s, "&e/sb reforges clear <player>          &7strip every modifier from the held item");
    }

    private static void list(CommandSender s, String[] r) {
        String f = r.length > 0 ? r[0].toLowerCase(Locale.ROOT) : "";
        int n = 0;
        for (ReforgeData.Row row : ReforgesModule.DATA.rows.values()) {
            String line = row.id + " " + row.name + " " + row.kind + " " + row.types + " " + row.stone;
            if (!f.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(f)) continue;
            n++;
            say(s, "&9" + row.name + " &8(" + row.id + ") &7" + row.kind + " &f" + Menus.typePhrase(row)
                    + (row.stone != null ? " &7stone &f" + row.stone : "") + (row.bonus != null ? bonusTag(row) : ""));
        }
        say(s, "&7" + n + " reforges shown.");
    }

    /** bonus:code (ours), bonus:&lt;module&gt; (another module's code carries it), bonus:lore (text only). */
    static String bonusTag(ReforgeData.Row row) {
        String st = ReforgeData.bonusState(row);
        return switch (st) {
            case "code" -> " &abonus:code";
            case "lore" -> " &ebonus:lore";
            case "none" -> "";
            default -> " &bbonus:" + st;
        };
    }

    /** Live state of the per-player reforge bonuses (Mantid stacks, timed buffs, power-scroll cooldowns, Chomp count). */
    private static void bonuses(CommandSender s, String[] r) {
        if (r.length < 1) { say(s, "&e/sb reforges bonuses <player>"); return; }
        Player p = target(s, r[0]);
        if (p == null) return;
        for (String line : Bonuses.INSTANCE == null ? List.of("bonuses not registered") : Bonuses.INSTANCE.describe(p)) say(s, "&7" + line);
    }

    private static void info(CommandSender s, String name) {
        Player p = target(s, name);
        if (p == null) return;
        ItemStack it = heldSky(s, p);
        if (it == null) return;
        ItemDef def = Gear.def(it);
        ItemData d = Sky.items().data(it);
        Rarity r = Sky.items().rarity(it);
        say(s, "&a" + def.id + " &7" + r.name() + " cat=" + def.category + " ref=" + ReforgesModule.DATA.refCategory(def));
        say(s, "&7reforge=" + d.reforge + " stars=" + d.stars + " potato=" + d.potatoBooks + " recomb=" + d.recombobulated
                + " gems=" + d.gems + " unlocked=" + d.unlockedGemSlots + " extra=" + d.extra);
        say(s, "&7blacksmith pool=" + ReforgesModule.DATA.blacksmithPool(def).size() + " essenceStars=" + ReforgesModule.DATA.essenceStars(def)
                + " masterStars=" + ReforgesModule.DATA.masterStarsAllowed(def) + " slots=" + ReforgesModule.DATA.slotTypes(def));
        ItemStatSheet sh = Sky.items().sheet(it);
        StringBuilder sb = new StringBuilder("&7sheet: dmg=" + Text.commas(sh.damage()) + " (+" + Text.commas(sh.damageBonus()) + ")");
        for (var e : sh.view().entrySet()) sb.append(' ').append(e.getKey().name()).append('=').append(Text.commas(sh.total(e.getKey())));
        say(s, sb.toString());
    }

    private static void apply(CommandSender s, String[] r) {
        if (r.length < 2) { say(s, "&e/sb reforges apply <player> <reforgeId>"); return; }
        Player p = target(s, r[0]);
        if (p == null) return;
        ItemStack it = heldSky(s, p);
        if (it == null) return;
        String id = r[1].toLowerCase(Locale.ROOT);
        ReforgeData.Row row = ReforgesModule.DATA.rows.get(id);
        if (row == null) { say(s, "&cUnknown reforge " + id); return; }
        if (!ReforgesModule.DATA.appliesTo(row, Gear.def(it))) say(s, "&e(warning: " + row.name + " does not normally fit this item)");
        Sky.items().update(it, d -> d.reforge = id);
        say(s, "&aApplied " + row.name + " to " + p.getName() + "'s " + Text.strip(Gear.name(it)));
    }

    /** Run the Reforge Anvil's own rules for an upgrade item on the held item, without consuming anything. */
    private static void upgrade(CommandSender s, String[] r) {
        if (r.length < 2) { say(s, "&e/sb reforges upgrade <player> <ITEM_ID>"); return; }
        Player p = target(s, r[0]);
        if (p == null) return;
        ItemStack it = heldSky(s, p);
        if (it == null) return;
        ItemDef mod = Sky.items().def(r[1].toUpperCase(Locale.ROOT));
        if (mod == null) { say(s, "&cUnknown item " + r[1]); return; }
        ItemMods.Plan plan = ReforgesModule.MODS.plan(Gear.def(it), Sky.items().data(it), Sky.items().rarity(it), mod);
        if (!plan.ok()) { say(s, "&cRefused: " + plan.error()); return; }
        Sky.items().update(it, plan.change());
        p.getInventory().setItemInMainHand(it);
        say(s, "&aDone: " + Text.strip(plan.verb()) + " on " + p.getName() + "'s " + Text.strip(Gear.name(it))
                + (plan.coins() > 0 ? " &7(would cost " + Text.commasInt(plan.coins()) + " coins)" : ""));
    }

    private static void random(CommandSender s, String[] r) {
        if (r.length < 1) { say(s, "&e/sb reforges random <player>"); return; }
        Player p = target(s, r[0]);
        if (p == null) return;
        ItemStack it = heldSky(s, p);
        if (it == null) return;
        List<ReforgeData.Row> pool = ReforgesModule.DATA.blacksmithPool(Gear.def(it));
        if (pool.isEmpty()) { say(s, "&cNo blacksmith pool for this item."); return; }
        ReforgeData.Row pick = pool.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size()));
        Sky.items().update(it, d -> d.reforge = pick.id);
        say(s, "&aRolled " + pick.name + " (pool of " + pool.size() + ")");
    }

    private interface DataSet { void set(ItemData d, int n); }

    private static void setData(CommandSender s, String[] r, String what, DataSet fn) {
        if (r.length < 2 || r[0].isEmpty()) { say(s, "&e/sb reforges " + what + " <player> <n>"); return; }
        Player p = target(s, r[0]);
        if (p == null) return;
        ItemStack it = heldSky(s, p);
        if (it == null) return;
        int n = Integer.parseInt(r[1]);
        Sky.items().update(it, d -> fn.set(d, n));
        Sky.stats().invalidate(p);
        say(s, "&aDone: " + what + " " + n + " on " + Text.strip(Gear.name(it)));
    }

    private static void gem(CommandSender s, String[] r) {
        if (r.length < 3) { say(s, "&e/sb reforges gem <player> <slot> <GEM_ID|none>"); return; }
        Player p = target(s, r[0]);
        if (p == null) return;
        ItemStack it = heldSky(s, p);
        if (it == null) return;
        int slot = Integer.parseInt(r[1]);
        String gem = r[2].toUpperCase(Locale.ROOT);
        if (gem.equals("NONE")) {
            Sky.items().update(it, d -> d.gems.remove(String.valueOf(slot)));
        } else {
            if (ReforgeData.parseGem(gem) == null) { say(s, "&cNot a gem id: " + gem); return; }
            Sky.items().update(it, d -> d.gems.put(String.valueOf(slot), gem));
        }
        Sky.stats().invalidate(p);
        say(s, "&aSlot " + slot + " = " + gem + " (slot type " + (slot < ReforgesModule.DATA.slotTypes(Gear.def(it)).size()
                ? ReforgesModule.DATA.slotTypes(Gear.def(it)).get(slot) : "none") + ")");
    }

    private static void essence(CommandSender s, String[] r) {
        if (r.length < 2) { say(s, "&e/sb reforges essence <player> <TYPE> [add|set|take] [n]"); return; }
        Player p = target(s, r[0]);
        if (p == null) return;
        if (!Sky.profiles().isReady(p)) { say(s, "&cProfile not ready."); return; }
        String type = r[1].toUpperCase(Locale.ROOT);
        if (!Arrays.asList(ReforgeData.ESSENCES).contains(type)) { say(s, "&cTypes: " + String.join(", ", ReforgeData.ESSENCES)); return; }
        if (r.length >= 4) {
            long n = Long.parseLong(r[3]);
            switch (r[2].toLowerCase(Locale.ROOT)) {
                case "add" -> Gear.addEssence(p, type, n);
                case "set" -> ReforgesModule.data(p).essence.put(type, Math.max(0, n));
                case "take" -> Gear.takeEssence(p, type, Math.min(n, Gear.essence(p, type)));
                default -> { say(s, "&cadd|set|take"); return; }
            }
        }
        say(s, "&d" + p.getName() + " " + type + " essence: " + Gear.essence(p, type));
    }

    private static void giveStones(CommandSender s, String[] r) {
        if (r.length < 1) { say(s, "&e/sb reforges givestones <player> [filter]"); return; }
        Player p = target(s, r[0]);
        if (p == null) return;
        String f = r.length > 1 ? r[1].toLowerCase(Locale.ROOT) : "";
        List<String> given = new ArrayList<>();
        for (ReforgeData.Row row : ReforgesModule.DATA.stones()) {
            if (!f.isEmpty() && !(row.id + " " + row.stone + " " + row.types).toLowerCase(Locale.ROOT).contains(f)) continue;
            if (!Sky.items().exists(row.stone)) continue;
            Sky.loot().give(p, row.stone, 1, LootSource.ADMIN);
            given.add(row.stone);
        }
        say(s, "&aGave " + given.size() + " stones to " + p.getName());
    }

    private static void open(CommandSender s, String[] r) {
        if (r.length < 2) { say(s, "&e/sb reforges open <player> <hub|blacksmith|anvil|essence|gems|guide|shop>"); return; }
        Player p = target(s, r[0]);
        if (p == null) return;
        switch (r[1].toLowerCase(Locale.ROOT)) {
            case "hub" -> Menus.hub(p);
            case "blacksmith" -> Menus.blacksmith(p);
            case "anvil" -> Menus.anvil(p);
            case "essence" -> Menus.essence(p);
            case "gems" -> Menus.gems(p);
            case "guide" -> Menus.guide(p, 0, 0);
            case "shop" -> Menus.shop(p, 0);
            default -> { say(s, "&cUnknown menu"); return; }
        }
        say(s, "&aOpened " + r[1] + " for " + p.getName());
    }

    /** Used by self-tests: the stat sheet a fresh stack would show. */
    static double sheetStat(ItemStack it, Stat st) { return Sky.items().sheet(it).total(st); }
}
