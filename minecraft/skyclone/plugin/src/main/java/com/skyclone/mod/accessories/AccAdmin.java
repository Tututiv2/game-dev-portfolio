package com.skyclone.mod.accessories;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** "/sb accessories ..." admin tools (all take a player name, so they work over RCON) and the self-tests. */
final class AccAdmin {

    private static final List<String> SUBS = List.of("info", "give", "fill", "clear", "pairs", "exchange", "prism", "power",
            "learn", "tune", "equip", "unequip", "mp", "list", "counter", "runic", "reload", "open", "sellcap");

    private final Bags bags;
    private final Catalog cat;
    private final Ui ui;

    AccAdmin(Bags bags, Catalog cat, Ui ui) {
        this.bags = bags;
        this.cat = cat;
        this.ui = ui;
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    void register() {
        Sky.commands().admin("accessories", "<" + String.join("|", SUBS) + "> ...",
                "Accessory bag, powers, tuning and equipment tools", this::run, (s, a) -> {
                    if (a.length <= 1) return filter(SUBS, a.length == 0 ? "" : a[0]);
                    if (a.length == 2 && !a[0].equals("mp") && !a[0].equals("list") && !a[0].equals("sellcap")) {
                        List<String> names = new ArrayList<>();
                        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                        return filter(names, a[1]);
                    }
                    if (a.length == 3) {
                        switch (a[0]) {
                            case "power", "learn" -> { return filter(new ArrayList<>(cat.powers.keySet()), a[2]); }
                            case "give" -> { return filter(cat.accessoryIds, a[2].toUpperCase(Locale.ROOT)); }
                            case "equip" -> { return filter(cat.equipmentIds, a[2].toUpperCase(Locale.ROOT)); }
                            case "tune" -> { return filter(AccDials.tuningOrder(), a[2].toUpperCase(Locale.ROOT)); }
                            case "open" -> { return filter(List.of("bag", "powers", "tuning", "stones", "equipment", "guide", "upgrades", "compactor", "deletor", "maskbag", "cakebag"), a[2]); }
                            default -> { return List.of(); }
                        }
                    }
                    return List.of();
                });
    }

    private static List<String> filter(List<String> all, String prefix) {
        List<String> out = new ArrayList<>();
        for (String s : all) if (s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(s);
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    private Player target(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cMissing player name."); return null; }
        Player p = Bukkit.getPlayerExact(a[i]);
        if (p == null) { say(s, "&cPlayer not online: " + a[i]); return null; }
        if (!Bags.ready(p)) { say(s, "&cThat profile is still loading."); return null; }
        return p;
    }

    private void run(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&e/sb accessories <" + String.join("|", SUBS) + ">"); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "mp" -> {
                if (a.length < 2) { say(s, "&c/sb accessories mp <power>"); return; }
                int mp = (int) Double.parseDouble(a[1]);
                say(s, "&6" + mp + " Accessory Power &7-> multiplier &a" + PowerMenus.fmt(AccMath.multiplier(mp))
                        + "&7, tuning points &e" + AccMath.tuningPoints(mp, AccDials.MP_PER_TUNING.get()));
            }
            case "sellcap" -> {
                // re-apply the NPC sell cap (e.g. after changing accessories.sell-cap-factor); it only lowers prices
                Map<String, String> changed = SellCap.apply(cat);
                say(s, changed.isEmpty() ? "&aNo crafted accessory sells for more than "
                        + AccDials.SELL_CAP_FACTOR.get() + "x its recipe inputs." : "&eLowered " + changed.size() + ": &f" + changed);
                String bad = SellCap.check(cat);
                if (bad != null) say(s, "&c" + bad);
            }
            case "list" -> {
                String f = a.length > 1 ? a[1].toUpperCase(Locale.ROOT) : "";
                int n = 0;
                for (String id : cat.accessoryIds) {
                    if (!id.contains(f)) continue;
                    ItemDef d = Sky.items().def(id);
                    if (d == null) { say(s, "&c" + id + " (not registered)"); continue; }
                    if (n++ >= 40) { say(s, "&8...more, narrow the filter"); break; }
                    say(s, "&7" + id + " &8| " + d.rarity.code() + d.rarity.display() + " &8| family &f" + Catalog.family(d)
                            + " &8#" + Catalog.familyRank(d) + (cat.effects(d).isEmpty() ? "" : " &8| fx " + cat.effects(d).size()));
                }
            }
            case "info" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                AccData d = Bags.data(p);
                Snapshot snap = bags.snapshot(p);
                int mp = bags.magicalPower(p);
                say(s, "&6" + p.getName() + "&7: power &6" + mp + "&7 (x" + PowerMenus.fmt(AccMath.multiplier(mp)) + "), slots &a"
                        + bags.bagView(p).size() + "/" + bags.capacity(p) + "&7, counted &a" + snap.active.size()
                        + "&7, not counted &c" + snap.inactive.size());
                say(s, "&7Selected power: &a" + (d.power == null || d.power.isEmpty() ? "none" : d.power) + "&7, learned: &a" + d.learned);
                say(s, "&7Tuning: &e" + d.tuning + "&7, pairs &a" + d.boughtPairs + "&7, exchange &a" + d.redstoneExchange
                        + "&7, prism &a" + d.riftPrism + "&7, blood counter &a" + d.bloodCounter);
                Set<String> types = new TreeSet<>();
                for (Fx f : snap.fx) types.add(f.type);
                say(s, "&7Live effects: &f" + types);
                say(s, "&7Equipment: &f" + snap.equipment.keySet() + "&7, compactor " + snap.compactorSlots + ", deletor " + snap.deletorSlots);
                say(s, "&7Wave 5: abicase contacts &a" + snap.contacts + "&7 (+" + snap.abicaseMp + " power), bats &a"
                        + EffectEngine.bats(p, bags.state(p)) + "&7, mask bag &a" + ui.keep.keys(p, KeepBags.MASK).size()
                        + "&7, cake bag &a" + ui.keep.keys(p, KeepBags.CAKE).size());
                say(s, "&7Digging: &a" + EffectEngine.activeMineBlock(EffectEngine.MINE_AT.get(p.getUniqueId()),
                        System.currentTimeMillis(), EffectEngine.mineWindowMs()) + "&7 (mining table " + EffectEngine.mines.size()
                        + " blocks), Frozen Corpses near &a" + bags.state(p).corpsesNear.size());
                say(s, "&7Stats from bag: STR " + PowerMenus.fmt(snap.stats.flat(Stat.STRENGTH)) + ", HP "
                        + PowerMenus.fmt(snap.stats.flat(Stat.HEALTH)) + ", SPD " + PowerMenus.fmt(snap.stats.flat(Stat.SPEED)));
            }
            case "give" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3) { if (p != null) say(s, "&c/sb accessories give <player> <id>"); return; }
                String id = a[2].toUpperCase(Locale.ROOT);
                if (!Catalog.isAccessory(Sky.items().def(id))) { say(s, "&cNot an accessory: " + id); return; }
                int slot = nextSlot(p);
                bags.setBag(p, slot, Sky.items().create(id));
                say(s, "&aPut " + id + " in " + p.getName() + "'s bag slot " + slot + ".");
            }
            case "fill" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                int limit = a.length > 2 ? parseInt(a[2], 9999) : 9999;
                // the top tier of every family (what a maxed player would carry)
                Map<String, String> top = new HashMap<>();
                Map<String, Integer> rank = new HashMap<>();
                for (String id : cat.accessoryIds) {
                    ItemDef d = Sky.items().def(id);
                    if (d == null || Catalog.noPower(d)) continue;
                    String fam = Catalog.family(d);
                    if (!rank.containsKey(fam) || Catalog.familyRank(d) > rank.get(fam)) { rank.put(fam, Catalog.familyRank(d)); top.put(fam, id); }
                }
                Set<String> have = new HashSet<>();
                for (ItemStack it : bags.bagView(p).values()) have.add(Sky.items().idOf(it));
                int added = 0;
                for (String id : new TreeSet<>(top.values())) {
                    if (added >= limit) break;
                    if (have.contains(id)) continue;
                    bags.setBag(p, nextSlot(p), Sky.items().create(id));
                    added++;
                }
                say(s, "&aAdded " + added + " accessories; " + p.getName() + " now has " + bags.magicalPower(p) + " Accessory Power.");
            }
            case "clear" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                AccData d = Bags.data(p);
                int n = d.bag.size();
                d.bag.clear();
                bags.reload(p);
                say(s, "&aCleared " + n + " bag slots of " + p.getName() + ".");
            }
            case "pairs" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                Bags.data(p).boughtPairs = Math.max(0, parseInt(a.length > 2 ? a[2] : "0", 0));
                bags.invalidate(p);
                say(s, "&aBought pairs set; capacity now " + bags.capacity(p) + ".");
            }
            case "exchange", "prism" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                boolean on = a.length < 3 || !a[2].equalsIgnoreCase("off");
                if (a[0].equalsIgnoreCase("exchange")) Bags.data(p).redstoneExchange = on;
                else Bags.data(p).riftPrism = on;
                bags.invalidate(p);
                say(s, "&a" + a[0] + " = " + on + "; power " + bags.magicalPower(p) + ", slots " + bags.capacity(p) + ".");
            }
            case "power" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3) return;
                Catalog.Power pw = cat.powers.get(a[2].toLowerCase(Locale.ROOT));
                if (pw == null) { say(s, "&cUnknown power. " + cat.powers.keySet()); return; }
                AccData d = Bags.data(p);
                if (!pw.starter() && !d.learned.contains(pw.id())) d.learned.add(pw.id());
                d.power = pw.id();
                bags.invalidate(p);
                say(s, "&a" + p.getName() + " now uses " + pw.name() + ".");
            }
            case "learn" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3) return;
                AccData d = Bags.data(p);
                int n = 0;
                for (Catalog.Power pw : cat.powers.values()) {
                    if (pw.starter()) continue;
                    if (!a[2].equalsIgnoreCase("all") && !pw.id().equalsIgnoreCase(a[2])) continue;
                    if (!d.learned.contains(pw.id())) { d.learned.add(pw.id()); n++; }
                }
                bags.invalidate(p);
                say(s, "&aLearned " + n + " powers.");
            }
            case "tune" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 4) { if (p != null) say(s, "&c/sb accessories tune <player> <stat> <points>"); return; }
                String key = a[2].toUpperCase(Locale.ROOT);
                if (!AccDials.TUNING.containsKey(key)) { say(s, "&cTunable: " + AccDials.tuningOrder()); return; }
                int pts = Math.max(0, parseInt(a[3], 0));
                if (pts == 0) Bags.data(p).tuning.remove(key); else Bags.data(p).tuning.put(key, pts);
                bags.invalidate(p);
                say(s, "&aTuning of " + key + " = " + pts + ".");
            }
            case "equip" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3) return;
                String id = a[2].toUpperCase(Locale.ROOT);
                String slot = Bags.slotFor(Sky.items().def(id));
                if (slot == null) { say(s, "&cNot an equipment piece: " + id); return; }
                bags.setEquip(p, slot, Sky.items().create(id));
                say(s, "&aEquipped " + id + " in " + slot + ".");
            }
            case "unequip" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                String which = a.length > 2 ? a[2].toUpperCase(Locale.ROOT) : "ALL";
                for (String slot : Bags.EQUIP_SLOTS) {
                    if (!which.equals("ALL") && !which.equals(slot)) continue;
                    ItemStack it = bags.equipped(p, slot);
                    if (it == null) continue;
                    bags.setEquip(p, slot, null);
                    BagMenus.giveBack(p, it);
                }
                say(s, "&aUnequipped " + which + ".");
            }
            case "counter" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                Bags.data(p).bloodCounter = Math.max(0, parseLong(a.length > 2 ? a[2] : "0"));
                bags.invalidate(p);
                say(s, "&aBlood counter set.");
            }
            case "runic" -> {
                // wave 5: set the Runic kill counter on the Runebook in the player's bag (its bag rarity follows)
                Player p = target(s, a, 1);
                if (p == null) return;
                int slot = bags.slotOf(p, "RUNEBOOK");
                if (slot < 0) { say(s, "&cNo Runebook in " + p.getName() + "'s Accessory Bag."); return; }
                long n = Math.max(0, parseLong(a.length > 2 ? a[2] : "0"));
                ItemStack next = bags.bagItem(p, slot).clone();
                Sky.items().update(next, d -> {
                    if (d.extra == null) d.extra = new com.google.gson.JsonObject();
                    d.extra.addProperty(Bags.RUNIC_KEY, n);
                });
                bags.setBag(p, slot, next);
                say(s, "&aRunebook counter set to " + n + " (" + AccMath.runicSteps(n, 4) + " rarity steps).");
            }
            case "reload" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                bags.reload(p);
                cat.clearFx();
                say(s, "&aReloaded " + p.getName() + "'s bag from saved data.");
            }
            case "open" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                String what = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "bag";
                switch (what) {
                    case "powers" -> ui.powers.openPowers(p, 0);
                    case "tuning" -> ui.powers.openTuning(p);
                    case "stones" -> ui.powers.openStones(p, 0);
                    case "thaumaturgy" -> ui.powers.openMain(p);
                    case "equipment" -> ui.equipment.open(p);
                    case "guide" -> ui.bag.openGuide(p, true, 0);
                    case "upgrades" -> ui.bag.openUpgrades(p);
                    case "compactor" -> ui.tools.openCompactor(p);
                    case "deletor" -> ui.tools.openDeletor(p);
                    case "maskbag" -> ui.keep.open(p, KeepBags.MASK);
                    case "cakebag" -> ui.keep.open(p, KeepBags.CAKE);
                    default -> ui.bag.open(p, 0);
                }
                say(s, "&aOpened " + what + " for " + p.getName() + ".");
            }
            default -> say(s, "&cUnknown action. " + SUBS);
        }
    }

    private int nextSlot(Player p) {
        int free = bags.firstFreeSlot(p);
        if (free >= 0) return free;
        int max = -1;
        for (int k : bags.bagView(p).keySet()) max = Math.max(max, k);
        return Math.max(max + 1, bags.capacity(p));
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return 0; }
    }

    // ---- self-tests ----------------------------------------------------------------------------

    void selfTests() {
        Sky.commands().selfTest("accessories.items-registered", () -> {
            List<String> bad = new ArrayList<>();
            for (String id : cat.accessoryIds) {
                ItemDef d = Sky.items().def(id);
                if (d == null) bad.add(id + " missing");
                else if (d.category != ItemCategory.ACCESSORY) bad.add(id + " is " + d.category + " (another module registered it first?)");
            }
            for (String id : cat.equipmentIds) {
                ItemDef d = Sky.items().def(id);
                if (d == null) bad.add(id + " missing");
                else if (Bags.slotFor(d) == null) bad.add(id + " is " + d.category);
            }
            for (String id : cat.stoneIds) if (!Sky.items().exists(id)) bad.add(id + " missing");
            for (String id : cat.enrichmentIds) if (!Sky.items().exists(id)) bad.add(id + " missing");
            if (cat.accessoryIds.size() < 400) bad.add("only " + cat.accessoryIds.size() + " accessories loaded (expected 423)");
            if (cat.equipmentIds.size() < 140) bad.add("only " + cat.equipmentIds.size() + " equipment pieces loaded (expected 147)");
            return bad.isEmpty() ? null : bad.size() + " problems: " + bad.subList(0, Math.min(6, bad.size()));
        });

        Sky.commands().selfTest("accessories.families", () -> {
            Map<String, Set<Integer>> ranks = new HashMap<>();
            List<String> bad = new ArrayList<>();
            for (String id : cat.accessoryIds) {
                ItemDef d = Sky.items().def(id);
                if (d == null) continue;
                String fam = Catalog.family(d);
                if (!Sky.items().exists(fam)) bad.add(id + ": family root " + fam + " missing");
                if (!ranks.computeIfAbsent(fam, k -> new HashSet<>()).add(Catalog.familyRank(d))) bad.add(id + ": duplicate rank in " + fam);
            }
            ItemDef z = Sky.items().def("ZOMBIE_ARTIFACT");
            if (z == null || !"ZOMBIE_TALISMAN".equals(Catalog.family(z)) || Catalog.familyRank(z) != 2) bad.add("ZOMBIE_ARTIFACT should be rank 2 of ZOMBIE_TALISMAN");
            return bad.isEmpty() ? null : bad.subList(0, Math.min(6, bad.size())).toString();
        });

        Sky.commands().selfTest("accessories.power-formula", () -> {
            // research/01 20.2: M(250) = 9.64, M(1000) = 32.31, M(2000) = 51.44; 1 tuning point per 10 power
            if (Math.abs(AccMath.multiplier(250) - 9.642) > 0.002) return "M(250) = " + AccMath.multiplier(250);
            if (Math.abs(AccMath.multiplier(1000) - 32.312) > 0.01) return "M(1000) = " + AccMath.multiplier(1000);
            if (Math.abs(AccMath.multiplier(2000) - 51.441) > 0.01) return "M(2000) = " + AccMath.multiplier(2000);
            if (AccMath.tuningPoints(255, 10) != 25) return "tuning points of 255 != 25";
            int[] research = {3, 5, 8, 12, 16, 22, 28, 3, 5};
            for (int i = 0; i < research.length; i++) {
                if (AccMath.DEFAULT_MP[i] != research[i]) return "MP of " + Rarity.values()[i] + " != " + research[i];
            }
            Catalog.Power sang = cat.powers.get("sanguisuge");
            if (sang == null) return "sanguisuge power missing";
            double str = sang.base().getOrDefault(Stat.STRENGTH, 0.0) * AccMath.multiplier(250);
            if (Math.abs(str - 138.85) > 0.1) return "Sanguisuge strength at 250 = " + str + " (research 138.85)";
            Catalog.Power fort = cat.powers.get("fortuitous");
            if (fort == null || Math.abs(fort.base().getOrDefault(Stat.HEALTH, 0.0) * AccMath.multiplier(250) - 32.3) > 0.2) return "Fortuitous HP at 250 != 32";
            int max = AccMath.bagSlots(9, 9, 6, 6, 2, true, 4, 99);
            if (max != 271) return "max bag slots " + max + " != 271 (9 + 48 + 12 + 4 + 198)";
            long total = 0;
            for (int n = 1; n <= 99; n++) total += AccMath.pairCost(n, 5360);
            if (Math.abs(total - 1.76e9) > 0.02e9) return "bag pair total " + total + " far from 1.76B";
            return null;
        });

        Sky.commands().selfTest("accessories.powers", () -> {
            if (cat.powers.size() != 32) return cat.powers.size() + " powers (expected 32: 10 starter + 22 stone)";
            for (Catalog.Power pw : cat.powers.values()) {
                if (pw.base().isEmpty()) return pw.id() + " has no stats";
                if (pw.stone() != null) {
                    ItemDef d = Sky.items().def(pw.stone());
                    if (d == null) return "stone " + pw.stone() + " missing";
                    if (d.category != ItemCategory.POWER_STONE) return "stone " + pw.stone() + " registered as " + d.category;
                }
                if (pw.requirement() != null && !pw.requirement().startsWith("skill:combat:")) return pw.id() + " odd requirement " + pw.requirement();
            }
            return null;
        });

        Sky.commands().selfTest("accessories.effects", () -> {
            List<String> bad = new ArrayList<>();
            List<String> all = new ArrayList<>(cat.accessoryIds);
            all.addAll(cat.equipmentIds);
            int count = 0;
            for (String id : all) {
                ItemDef d = Sky.items().def(id);
                if (d == null) continue;
                List<Fx> flat = new ArrayList<>();
                for (Fx f : cat.effects(d)) f.flatten(flat);
                for (Fx f : flat) {
                    count++;
                    if (!f.known) bad.add(id + ": unknown effect " + f.type);
                    else if (!f.when.valid) bad.add(id + ": bad condition " + f.when.text);
                    else if ((f.type.equals("stat_if") || f.type.equals("stat_pct") || f.type.equals("per_skill")) && f.stat == null) bad.add(id + ": no stat");
                    else if ((f.type.equals("dmg_taken") || f.type.equals("dmg_dealt")) && f.mob == null) bad.add(id + ": no mob type");
                    else if (f.type.equals("piggy") && f.next != null && !Sky.items().exists(f.next)) bad.add(id + ": piggy next " + f.next + " missing");
                    else if ((f.type.equals("vs_stat") || f.type.equals("piece_bonus")) && f.stat == null) bad.add(id + ": no stat");
                    else if ((f.type.equals("weapon_buff") || f.type.equals("drop_boost")) && (f.item == null || !Sky.items().exists(f.item)))
                        bad.add(id + ": " + f.type + " item " + f.item + " is not registered");
                }
            }
            if (count < 150) bad.add("only " + count + " effects parsed");
            return bad.isEmpty() ? null : bad.size() + " bad: " + bad.subList(0, Math.min(6, bad.size()));
        });

        Sky.commands().selfTest("accessories.recipes", () -> {
            Set<String> missing = new TreeSet<>();
            for (String id : cat.recipeIngredients) if (!Sky.items().exists(id)) missing.add(id);
            int ours = 0;
            for (String r : cat.recipeResults) {
                if (!Sky.items().exists(r)) return "recipe result " + r + " not registered";
                for (Recipe rec : Sky.recipes().producing(r)) if (rec != null) { ours++; break; }
            }
            if (ours == 0) return "no recipe of ours is registered";
            return missing.isEmpty() ? null : missing.size() + " ingredient ids no module registers: "
                    + new ArrayList<>(missing).subList(0, Math.min(12, missing.size()));
        });

        // no craft-and-sell coin printer among our recipes (the module-local half of crafting.no-coin-loops)
        Sky.commands().selfTest("accessories.sell-cap", () -> SellCap.check(cat));

        Sky.commands().selfTest("accessories.menus-build", () -> {
            for (Catalog.Power pw : cat.powers.values()) {
                ItemStack it = ui.powers.powerIcon(null, pw, 250);
                if (it == null) return "power icon " + pw.id() + " is null";
                if (pw.stone() != null && ui.powers.stoneIcon(null, pw) == null) return "stone icon " + pw.id();
            }
            for (String id : List.of("SPEED_TALISMAN", "HEGEMONY_ARTIFACT", "MITHRIL_BELT")) {
                ItemDef d = Sky.items().def(id);
                if (d == null) return id + " missing";
                List<String> lore = ItemHooks.afterStats(d, new com.skyclone.api.item.ItemData());
                if (id.equals("HEGEMONY_ARTIFACT") && !String.join("|", lore).contains("+32")) return "Hegemony should show +32 power: " + lore;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!Bags.ready(p)) continue;
                bags.snapshot(p);
                bags.capacity(p);
                break;
            }
            return null;
        });

        Sky.commands().selfTest("accessories.enrichments", () -> {
            if (cat.enrichValues.size() < 10) return "only " + cat.enrichValues.size() + " enrichment stats";
            for (Map.Entry<Stat, String> e : cat.enrichItemByStat.entrySet()) {
                if (!Sky.items().exists(e.getValue())) return e.getValue() + " missing";
            }
            Double str = cat.enrichValues.get(Stat.STRENGTH);
            if (str == null || Math.abs(str - 1) > 1e-9) return "strength enrichment should be +1, is " + str;
            return null;
        });

        // Adaptive Belt / Ancient Cloak / Catacombs Expert Ring + Professor's books (wave 3 fixer)
        Sky.commands().selfTest("accessories.dungeon-gear", () -> {
            for (String belt : List.of("ADAPTIVE_BELT", "STARRED_ADAPTIVE_BELT")) {
                ItemDef d = Sky.items().def(belt);
                if (d == null) return belt + " not registered";
                Set<String> classes = new TreeSet<>();
                for (Fx f : cat.effects(d)) if (f.when.uses("class") && f.stat != null) classes.add(f.when.text.substring(6));
                if (classes.size() != 5) return belt + " has class stats for " + classes + ", wants all 5 classes";
            }
            ItemDef cloak = Sky.items().def("ANCIENT_CLOAK");
            if (cloak == null) return "ANCIENT_CLOAK not registered";
            Fx hp = null;
            for (Fx f : cat.effects(cloak)) if (f.type.equals("health_ability")) hp = f;
            if (hp == null || hp.members.isEmpty()) return "ANCIENT_CLOAK has no health-cost ability list";
            int real = 0;
            for (String id : hp.members) if (Sky.items().exists(id)) real++;
            if (real == 0) return "none of ANCIENT_CLOAK's health-cost items " + hp.members + " is registered";
            return null;
        });
        // Accessories' half of the Catacombs/class XP boost: the four items carry their numbers and the hook scales a
        // stand-in event correctly. Whether the API has SkyEvents.DungeonXpGain yet (request 12) is the integrator's
        // half, not this module's, so its absence is a boot warning; once it exists, failing to bind IS a failure.
        Sky.commands().selfTest("accessories.dungeon-xp", () -> {
            Map<String, double[]> want = Map.of(
                    "CATACOMBS_EXPERT_RING", new double[]{0, 10},
                    "SCARF_STUDIES", new double[]{1, 2},
                    "SCARF_THESIS", new double[]{1, 4},
                    "SCARF_GRIMOIRE", new double[]{1, 6});
            for (Map.Entry<String, double[]> e : want.entrySet()) {
                ItemDef d = Sky.items().def(e.getKey());
                if (d == null) return e.getKey() + " not registered";
                Fx x = null;
                for (Fx f : cat.effects(d)) if (f.type.equals("dungeon_xp")) x = f;
                if (x == null) return e.getKey() + " has no dungeon_xp effect";
                String track = e.getValue()[0] == 0 ? "catacombs" : "class";
                if (!track.equals(x.skill) || Math.abs(x.pct - e.getValue()[1]) > 1e-9)
                    return e.getKey() + " is " + x.skill + " +" + x.pct + "%, wants " + track + " +" + e.getValue()[1] + "%";
            }
            return DungeonXpHook.selfCheck(DungeonXpHook.apiPresent, DungeonXpHook.bound, DungeonXpHook.why);
        });
        wave5Tests();
    }

    /** The first effect of this type an item carries, or null. */
    private Fx effect(String id, String type) {
        ItemDef d = Sky.items().def(id);
        if (d == null) return null;
        List<Fx> flat = new ArrayList<>();
        for (Fx f : cat.effects(d)) f.flatten(flat);
        for (Fx f : flat) if (f.type.equals(type)) return f;
        return null;
    }

    private void wave5Tests() {
        // Abicase + Runebook: the two accessories whose Accessory Power is not just their rarity's
        Sky.commands().selfTest("accessories.wave5-power", () -> {
            if (AccMath.abicaseMp(9, 2) != 4 || AccMath.abicaseMp(1, 2) != 0) return "abicase power is not floor(contacts / 2)";
            if (AccMath.countByThreshold(n -> n <= 37, 256) != 37 || AccMath.countByThreshold(n -> false, 256) != 0)
                return "contact count by threshold is wrong";
            if (effect("ABICASE", "abicase") == null) return "ABICASE has no abicase effect";
            if (AccMath.runicSteps(0, 4) != 0 || AccMath.runicSteps(7, 4) != 1 || AccMath.runicSteps(123456, 4) != 4)
                return "runebook rarity steps are not one per digit, max 4";
            if (effect("RUNEBOOK", "runic_counter") == null) return "RUNEBOOK has no runic_counter effect";
            ItemDef rb = Sky.items().def("RUNEBOOK");
            com.skyclone.api.item.Rarity r = rb.rarity;
            for (int i = 0; i < 4; i++) r = r.upgrade();
            if (r.ordinal() <= rb.rarity.ordinal()) return "a Runebook cannot rise in rarity from " + rb.rarity;
            return null;
        });
        // the new typed effects carry the research numbers
        Sky.commands().selfTest("accessories.wave5-effects", () -> {
            Fx fleece = effect("GOLDEN_FLEECE", "mf_vs");
            if (fleece == null || !"mythological".equals(fleece.mob) || fleece.value != 5) return "Golden Fleece: +5 MF vs mythological";
            Fx shriv = effect("SHRIVELED_BRACELET", "mf_vs");
            if (shriv == null || !"arthropod".equals(shriv.mob) || shriv.value != 10) return "Shriveled Bracelet: +10 MF vs arthropods";
            Fx bat = effect("BAT_PERSON_ARTIFACT", "bat_person");
            if (bat == null || bat.per != 10 || AccMath.bats(true, 2, 1) != 4) return "Bat Person Artifact: +10 per bat";
            Fx del = effect("DELIRIUM_NECKLACE", "fire_taken"), sc = effect("SCOURGE_CLOAK", "fire_taken");
            if (del == null || del.per != 8 || Math.abs(AccMath.fireTakenMult(0, 8, 1) - 1.08) > 1e-9) return "Delirium: +8% fire per Strength";
            if (sc == null || sc.carry != 20 || Math.abs(AccMath.fireTakenMult(10000, 0, 0) - 101) > 1e-9) return "Scourge: x101 fire, 20% carry";
            Fx fist = effect("FLAMING_FIST", "fire_fist");
            if (fist == null || fist.n != 3 || fist.dmg != 10000) return "Flaming Fist: 3 fists, 10,000 fire damage";
            Fx skull = effect("SOULWEAVER_GLOVES", "haunt_skull");
            if (skull == null || skull.cd != 15 || skull.window != 2 || skull.pct != 5) return "Soulweaver: every 15s, 2s stun, 5% less damage";
            Fx bone = effect("BONE_NECKLACE", "nearby_def");
            if (bone == null || bone.tankRange != 30 || bone.tankCap != 60) return "Bone Necklace: Tank range 30, cap 60";
            Fx skullPct = effect("MASTER_SKULL_TIER_7", "stat_pct");
            if (skullPct == null || !skullPct.when.uses("master")) return "Master Skulls must apply in Master Mode only";
            Fx myth = effect("MYTHOS_CLOAK", "equip_mult"), zorro = effect("ZORROS_CAPE", "equip_mult");
            if (myth == null || !myth.when.uses("event") || zorro == null || !zorro.when.uses("event")) return "Mythos / Zorro x2 need their event";
            if (effect("MOLTEN_BELT", "set") == null) return "Molten Core tier missing";
            ItemDef core = Sky.items().def("INFERNAL_KUUDRA_CORE");
            if (core == null || core.stats == null || !core.stats.containsKey(Stat.HUNTING_FORTUNE)) return "Leviathan cores grant Hunting Fortune";
            return null;
        });
        // the mask / cake bags: data, what fits, distinct counting
        Sky.commands().selfTest("accessories.keep-bags", () -> {
            Fx mask = effect(KeepBags.MASK_BAG, "keep_bag"), cake = effect(KeepBags.CAKE_BAG, "keep_bag");
            if (mask == null || !KeepBags.MASK.equals(mask.kind) || mask.stat != Stat.INTELLIGENCE) return "Carnival Mask Bag: +1 Int per mask";
            if (cake == null || !KeepBags.CAKE.equals(cake.kind) || cake.stat != Stat.HEALTH) return "New Year Cake Bag: +1 HP per year";
            if (KeepBags.distinct(List.of("A", "A", "B", "C"), Set.of("C")) != 2) return "distinct counting is wrong";
            if (!Sky.items().exists("NEW_YEAR_CAKE")) return "NEW_YEAR_CAKE is not registered (mayors), nothing can go in the cake bag";
            if (!KeepBags.accepts(KeepBags.CAKE, Sky.items().create("NEW_YEAR_CAKE"))) return "the cake bag refuses a New Year cake";
            if (KeepBags.accepts(KeepBags.CAKE, Sky.items().create(KeepBags.MASK_BAG))) return "the cake bag accepts a non-cake";
            return null;
        });
        // "while mining X" against the mining module's real block table, Organ Donor, Power talisman gems, Hocus Pocus
        Sky.commands().selfTest("accessories.wave5-gathering", () -> {
            MineTargets mt = EffectEngine.mines;
            if (mt.size() == 0) return "data/mining/blocks.json not found: the 'while mining' bonuses can never apply";
            if (!"gem_amber".equals(mt.resolve("crystal_hollows", "ORANGE_STAINED_GLASS"))) return "amber glass is not gem_amber";
            if (!"titanium".equals(mt.resolve("dwarven_mines", "POLISHED_DIORITE"))) return "polished diorite is not titanium";
            if (!"hard_stone".equals(mt.resolve("crystal_hollows", "STONE"))) return "stone in the Hollows is not hard_stone";
            Fx amber = effect("AMBER_NECKLACE", "stat_if");
            if (amber == null || amber.value != 30 || !amber.when.uses("mining")) return "Amber Necklace: +30 MF while mining amber";
            Fx tita = effect("TITANIUM_BELT", "stat_if");
            if (tita == null || tita.value != 10 || !tita.when.uses("mining")) return "Titanium Belt: +10 MF while mining mithril/titanium";
            Fx divan = effect("DIVAN_PENDANT", "stat_if");
            if (divan == null || divan.stat != Stat.PRISTINE || divan.value != 0.25) return "Divan Pendant: +0.25 Pristine in mineshafts";
            Fx ding = effect("ORGAN_DONOR_TALISMAN", "corpse_ding");
            if (ding == null || ding.range != 20 || !EffectEngine.isCorpseName("Frozen Lapis Corpse")) return "Organ Donor: ding within 20 of a corpse";
            ItemDef power = Sky.items().def("POWER_RING");
            if (power == null) return "POWER_RING not registered";
            com.skyclone.api.item.ItemStatSheet sh = new com.skyclone.api.item.ItemStatSheet();
            sh.add(Stat.STRENGTH, 10, com.skyclone.api.item.ItemStatSheet.Source.GEMSTONE);
            sh.add(Stat.STRENGTH, 4, com.skyclone.api.item.ItemStatSheet.Source.BASE);
            ItemHooks.scaleGems(power, sh);
            if (Math.abs(sh.total(Stat.STRENGTH) - 9) > 1e-9) return "Power Ring gems are not at half power (" + sh.total(Stat.STRENGTH) + ")";
            ItemDef hocus = Sky.items().def("HOCUS_POCUS_CIPHER");
            if (hocus == null || hocus.extra == null || !hocus.extra.has("riftStats")) return "Hocus-Pocus Cipher has no riftStats for the rift module";
            ItemDef zorro = Sky.items().def("ZORROS_CAPE");
            if (zorro == null || zorro.requirements == null || !zorro.requirements.contains("chocolate:rabbit:zorro"))
                return "Zorro's Cape lacks chocolate:rabbit:zorro";
            return null;
        });
        // every requirement key our items now carry resolves (dungeon floors, trophy titles, frogs, harp)
        Sky.commands().selfTest("accessories.requirement-keys", () -> {
            Set<String> unknown = new TreeSet<>();
            List<String> ids = new ArrayList<>(cat.accessoryIds);
            ids.addAll(cat.equipmentIds);
            for (String id : ids) {
                ItemDef d = Sky.items().def(id);
                if (d == null || d.requirements == null) continue;
                for (String r : d.requirements) {
                    String prefix = r.contains(":") ? r.substring(0, r.indexOf(':')) : r;
                    if (!Sky.requirements().known(prefix)) unknown.add(prefix + " (" + id + ")");
                }
            }
            return unknown.isEmpty() ? null : "no resolver for " + unknown;
        });
    }
}
