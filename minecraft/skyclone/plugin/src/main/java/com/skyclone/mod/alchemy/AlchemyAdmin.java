package com.skyclone.mod.alchemy;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /sb alchemy ...} - every sub takes a player NAME so the integrator can drive it over RCON.
 */
final class AlchemyAdmin {

    private AlchemyAdmin() {}

    static final String USAGE = "give <p> <effect> <lvl> [splash|wide] [extMin] | apply <p> <effect> <lvl> <sec> | clear <p> | "
            + "effects <p> | god <p> <hours> | brew <p> status|finish|clear | jyrre <p> <bonus> | bag <p> | open <p> hub|stand|bag|effects|guide|shop | "
            + "simulate <ingredient> <bottle...> | list [filter]";

    static void register(EffectTable t) {
        Sky.commands().admin("alchemy", USAGE, "Alchemy test tools (potions, effects, brewing, bag)",
                (s, a) -> run(t, s, a), (s, a) -> complete(t, a));
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player player(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) { say(s, "&cNo online player " + name); return null; }
        if (!Sky.profiles().isReady(p)) { say(s, "&c" + name + "'s profile is not ready yet"); return null; }
        return p;
    }

    private static int num(String s, int def) {
        try { return Integer.parseInt(s); } catch (RuntimeException e) { return def; }
    }

    static void run(EffectTable t, CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&e/sb alchemy " + USAGE); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        EffectEngine eng = AlchemyModule.engine();
        switch (sub) {
            case "list" -> {
                String f = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                for (EffectTable.Effect e : t.effects()) {
                    if (!f.isEmpty() && !e.id.contains(f)) continue;
                    say(s, "&f" + e.id + " &7" + e.name + " &8levels " + e.minLevel() + "-" + e.maxLevel()
                            + (e.brewable ? " &abrew<=" + e.brewMax : " &8not brewable") + " &8kind " + e.kind);
                }
            }
            case "give" -> {
                if (a.length < 4) { say(s, "&cgive <p> <effect> <lvl> [splash|wide] [extMin]"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                String eff = a[2].toLowerCase(Locale.ROOT);
                int lvl = num(a[3], 1);
                int sp = 0, ext = 0;
                for (int i = 4; i < a.length; i++) {
                    if (a[i].equalsIgnoreCase("splash")) sp = 1;
                    else if (a[i].equalsIgnoreCase("wide")) sp = 2;
                    else ext = num(a[i], 0) * 60;
                }
                EffectTable.Effect e = t.effect(eff);
                if (e == null || e.level(lvl) == null) { say(s, "&cUnknown effect/level " + eff + " " + lvl); return; }
                ItemStack st = e.potionItem != null ? PotionItems.create(e.potionItem)      // Dread Draught, Midnight Wit
                        : PotionItems.itemOf(new Brew.Bottle(Brew.Kind.POTION, eff, lvl, ext, 0, sp, 0));
                if (st == null) { say(s, "&cNo item for that (splash only exists for brewable levels)"); return; }
                Sky.loot().give(p, st, LootSource.ADMIN);
                say(s, "&aGave " + p.getName() + " " + Sky.items().idOf(st));
            }
            case "apply" -> {
                if (a.length < 5) { say(s, "&capply <p> <effect> <lvl> <seconds>"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                String label = eng.applyEffect(p, a[2].toLowerCase(Locale.ROOT), num(a[3], 1), num(a[4], 60), "admin");
                say(s, label == null ? "&cUnknown effect" : "&aApplied " + label + " &afor " + a[4] + "s to " + p.getName());
            }
            case "clear" -> {
                if (a.length < 2) { say(s, "&cclear <p>"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                eng.clearAll(p);
                say(s, "&aCleared every effect on " + p.getName());
            }
            case "effects" -> {
                if (a.length < 2) { say(s, "&ceffects <p>"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                AlchemyData d = EffectEngine.data(p);
                say(s, "&e" + p.getName() + ": " + d.effects.size() + " effects, god " + Text.duration(d.godLeft)
                        + (eng.paused(p) ? " &c(paused)" : ""));
                for (Map.Entry<String, AlchemyData.Active> x : d.effects.entrySet())
                    say(s, " " + PotionItems.label(x.getKey(), x.getValue().level) + " &f" + EffectTable.clock(x.getValue().left)
                            + " &8" + x.getValue().source);
                say(s, "&7In force: " + eng.effective(p));
            }
            case "god" -> {
                if (a.length < 3) { say(s, "&cgod <p> <hours>"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                double h;
                try { h = Double.parseDouble(a[2]); } catch (RuntimeException ex) { say(s, "&cbad hours"); return; }
                eng.addGod(p, Math.round(h * 3600));
                say(s, "&aGod Potion on " + p.getName() + ": " + Text.duration(EffectEngine.data(p).godLeft));
            }
            case "brew" -> {
                if (a.length < 3) { say(s, "&cbrew <p> status|finish|clear"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                BrewingStand st = AlchemyModule.stand();
                switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "finish" -> {
                        int n = st.rush(p);
                        say(s, n > 0 ? "&aRushed " + n + " brew(s) (an open stand finishes within half a second)" : "&cNothing is brewing");
                    }
                    case "clear" -> {
                        int n = 0;
                        for (BrewingStand.Ref r : st.refsOf(p)) { st.clear(p, r); n++; }
                        say(s, "&aEmptied " + n + " stand(s) of " + p.getName() + " (contents deleted)");
                    }
                    default -> {
                        say(s, "&e" + p.getName() + "'s brewing stands:");
                        for (String line : st.describe(p)) for (String l : line.split("\n")) say(s, " " + l);
                    }
                }
            }
            case "jyrre" -> {
                if (a.length < 3) { say(s, "&cjyrre <p> <bonus>  (sets the held Bottle of Midnight's Intelligence bonus)"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                ItemStack held = p.getInventory().getItemInMainHand();
                var def = Sky.items().def(Sky.items().idOf(held));
                if (!PotionItems.isJyrre(def)) { say(s, "&c" + p.getName() + " is not holding a Bottle of Midnight"); return; }
                long mins = (long) Math.max(0, num(a[2], 0)) * Math.max(1, AlchemyDials.JYRRE_MINUTES_PER_INT.get());
                Sky.items().update(held, d -> d.extra.addProperty("jmin", mins));
                p.getInventory().setItemInMainHand(held);
                say(s, "&aBottle bonus now +" + PotionItems.jyrreBonus(def, Sky.items().data(held)) + " Intelligence ("
                        + mins + " min); it evolves on the next minute tick if at its cap");
            }
            case "bag" -> {
                if (a.length < 2) { say(s, "&cbag <p>"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                AlchemyData d = EffectEngine.data(p);
                say(s, "&e" + p.getName() + "'s Potion Bag: " + PotionBag.count(d) + " potions in " + PotionBag.slots(p) + " slots");
            }
            case "open" -> {
                if (a.length < 3) { say(s, "&copen <p> hub|stand|bag|effects|guide|shop"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "stand" -> AlchemyModule.stand().open(p);
                    case "bag" -> PotionBag.open(p);
                    case "effects" -> AlchemyMenus.effects(p);
                    case "guide" -> AlchemyMenus.guide(p, 0);
                    case "shop" -> AlchemyMenus.shop(p);
                    default -> AlchemyMenus.hub(p);
                }
                say(s, "&aOpened " + a[2] + " for " + p.getName());
            }
            case "simulate" -> {
                if (a.length < 3) { say(s, "&csimulate <ingredient> <water|awkward|brew:ITEM|effect:lvl[:ext][:enh][:splash]>..."); return; }
                String ing = a[1].toUpperCase(Locale.ROOT);
                for (int i = 2; i < a.length; i++) {
                    Brew.Bottle b = parseBottle(a[i]);
                    if (b == null) { say(s, "&cbad bottle " + a[i]); continue; }
                    Brew.Result r = Brew.react(t, b, ing);
                    say(s, "&7" + a[i] + " + " + ing + " &8-> " + (r == null ? "&cno reaction" : "&a" + describe(r.bottle())
                            + " &3(+" + Text.commas(r.xp()) + " xp)" + (r.ingredient() != null && r.ingredient().unlock != null
                            ? " &8unlock " + r.ingredient().unlock : "")));
                }
            }
            default -> say(s, "&e/sb alchemy " + USAGE);
        }
    }

    static Brew.Bottle parseBottle(String s) {
        String x = s.toLowerCase(Locale.ROOT);
        if (x.equals("water")) return Brew.Bottle.water();
        if (x.equals("awkward")) return Brew.Bottle.awkward();
        if (x.startsWith("brew:") && x.length() > 5) return Brew.Bottle.basicBrew(s.substring(5).toUpperCase(Locale.ROOT));
        String[] p = x.split(":");
        if (p.length < 2) return null;
        try {
            return new Brew.Bottle(Brew.Kind.POTION, p[0], Integer.parseInt(p[1]), p.length > 2 ? Integer.parseInt(p[2]) : 0,
                    p.length > 3 ? Integer.parseInt(p[3]) : 0, p.length > 4 ? Integer.parseInt(p[4]) : 0, 0);
        } catch (NumberFormatException e) { return null; }
    }

    static String describe(Brew.Bottle b) {
        return switch (b.kind()) {
            case WATER -> "water";
            case AWKWARD -> b.brew() != null ? "brew:" + b.brew() : "awkward";
            case POTION -> b.effect() + ":" + b.level() + (b.ext() > 0 ? " ext=" + b.ext() + "s" : "")
                    + (b.enh() > 0 ? " enh=+" + b.enh() : "") + (b.splash() > 0 ? " splash=" + b.splash() : "")
                    + (b.brew() != null ? " brew=" + b.brew() : "");
        };
    }

    private static List<String> complete(EffectTable t, String[] a) {
        if (a.length == 1) return List.of("give", "apply", "clear", "effects", "god", "brew", "bag", "open", "simulate", "list");
        if (a.length == 2 && !a[0].equalsIgnoreCase("simulate") && !a[0].equalsIgnoreCase("list")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        if (a.length == 3 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("apply"))) {
            List<String> ids = new ArrayList<>();
            for (EffectTable.Effect e : t.effects()) if (e.id.startsWith(a[2].toLowerCase(Locale.ROOT))) ids.add(e.id);
            return ids;
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("brew")) return List.of("status", "finish", "clear");
        if (a.length == 3 && a[0].equalsIgnoreCase("open")) return List.of("hub", "stand", "bag", "effects", "guide", "shop");
        return List.of();
    }
}
