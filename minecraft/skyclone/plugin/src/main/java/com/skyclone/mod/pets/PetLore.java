package com.skyclone.mod.pets;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.pets.PetService.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The genre's pet tooltip: type line, stat lines, abilities with live numbers, held item, level progress. */
final class PetLore {

    private PetLore() {}

    private static final Set<Stat> RED = Set.of(Stat.STRENGTH, Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED,
            Stat.ABILITY_DAMAGE, Stat.FEROCITY);
    private static final Pattern TOKEN = Pattern.compile("\\{([a-z])\\}");

    static String title(View v) {
        return "&7[Lvl " + v.level() + "] " + v.rarity().code() + v.def().name;
    }

    private static final Pattern ITEM = Pattern.compile("<item:([A-Z0-9_:;]+)>");

    /** Fill {a}, {b} with this pet's numbers at its current level, and <item:ID> with that item's current name. */
    static String fill(String line, View v, PetDefs.Ability a) {
        Matcher m = TOKEN.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(PetMath.fmt(v.num(m.group(1), a))));
        m.appendTail(sb);
        String out = sb.toString();
        if (!out.contains("<item:")) return out;
        Matcher im = ITEM.matcher(out);
        StringBuilder sb2 = new StringBuilder();
        while (im.find()) {
            String id = im.group(1);
            ItemDef d = null;
            try { d = Sky.items().def(id); } catch (RuntimeException ignored) { }
            String name = d == null ? "&a" + Text.titleCase(id) : d.rarity.code() + d.name;
            im.appendReplacement(sb2, Matcher.quoteReplacement(name));
        }
        im.appendTail(sb2);
        return sb2.toString();
    }

    /** Ember Ward progress, as the genre shows it: current bonus, then the next step and kills toward it. */
    static List<String> bulwarkLines(View v, com.google.gson.JsonObject fx) {
        int kills = v.owned().counter("bulwark");
        int every = fx.has("every") ? Math.max(1, fx.get("every").getAsInt()) : 100;
        int max = fx.has("max") ? fx.get("max").getAsInt() : 10;
        double def = fx.has("defense") ? fx.get("defense").getAsDouble() : 30;
        double td = fx.has("true_defense") ? fx.get("true_defense").getAsDouble() : 3;
        int steps = Math.min(max, kills / every);
        List<String> out = new ArrayList<>();
        out.add("&7Bonus: &a+" + PetMath.fmt(steps * def) + " &7& &f+" + PetMath.fmt(steps * td));
        if (steps >= max) out.add("&8(MAX - " + Text.commasInt(kills) + " kills)");
        else out.add("&7Next Upgrade: &a+" + PetMath.fmt((steps + 1) * def) + " &7& &f+" + PetMath.fmt((steps + 1) * td)
                + " &8(" + Text.commasInt(kills) + "/" + Text.commasInt((long) (steps + 1) * every) + ")");
        return out;
    }

    static List<String> lines(PetService svc, View v, boolean withLevelInHeader) {
        List<String> out = new ArrayList<>();
        out.add((withLevelInHeader ? "&7[Lvl " + v.level() + "] " : "") + "&8" + v.def().skillLabel() + " Pet");
        if (v.boosted()) out.add("&6Tier Boosted &7(base " + Text.titleCase(v.owned().tier) + ")");
        out.add("");
        Map<Stat, Double> stats = svc.petStats(v, 0);
        boolean any = false;
        for (Map.Entry<Stat, Double> e : stats.entrySet()) {
            Stat s = e.getKey();
            double val = e.getValue();
            if (Math.abs(val) < 0.005) continue;
            String col = RED.contains(s) ? "&c" : "&a";
            out.add("&7" + s.display + ": " + col + (val >= 0 ? "+" : "") + PetMath.fmt(val) + (s.percent ? "%" : ""));
            any = true;
        }
        for (Map.Entry<String, Double> e : svc.extraStats(v).entrySet()) {
            if (Math.abs(e.getValue()) < 0.005) continue;
            out.add("&7" + Text.titleCase(e.getKey()) + ": &a+" + PetMath.fmt(e.getValue()));
            any = true;
        }
        if (any) out.add("");
        for (PetDefs.Ability a : v.t().abilities) {
            out.add("&6" + a.name);
            for (String l : a.lore) out.add(fill(l, v, a));
            for (com.google.gson.JsonObject fx : a.fx) {
                String t = PetService.str(fx, "t");
                if ("bulwark".equals(t)) out.addAll(bulwarkLines(v, fx));
                else if ("fear_harvest".equals(t)) {
                    int every = fx.has("every") ? fx.get("every").getAsInt() : 10, cap = fx.has("cap") ? fx.get("cap").getAsInt() : 150;
                    int kills = Math.min(cap, v.owned().counter("fear"));
                    out.add("&7Fear kills: &a" + kills + "&7/&a" + cap + " &8(+" + PetMath.fmt(PetMath.fearHarvest(kills, every, cap, v.num("a", a))) + " Fear)");
                }
            }
            out.add("");
        }
        if (v.def().passive) {
            out.add("&8This pet's perks work even when it");
            out.add("&8is not summoned.");
            out.add("");
        }
        if (v.owned().skin != null && svc.defs().skins.get(v.owned().skin) != null) {
            out.add("&7Skin: &d" + svc.defs().skins.get(v.owned().skin).name);
            out.add("");
        }
        if (v.owned().heldItem != null) {
            ItemDef h = Sky.items().def(v.owned().heldItem);
            if (h != null) {
                out.add("&6Held Item: " + h.rarity.code() + h.name);
                for (String l : h.lore) {
                    if (l == null || l.isEmpty()) break;
                    out.add(l);
                }
                out.add("");
            }
        }
        if (v.maxed()) {
            out.add("&bMAX LEVEL");
        } else {
            double into = v.owned().exp - svc.totalFor(v, v.level());
            double need = svc.totalFor(v, v.level() + 1) - svc.totalFor(v, v.level());
            double frac = need <= 0 ? 1 : into / need;
            out.add("&7Progress to Level " + (v.level() + 1) + ": &e" + PetMath.fmt(Math.floor(frac * 1000) / 10.0) + "%");
            out.add(Text.bar(frac, 20) + " &e" + Text.shortNum(into) + "&6/&e" + Text.shortNum(need));
        }
        out.add("&7Total XP: &e" + Text.commasInt(v.owned().exp) + " &6/ &e" + Text.shortNum(svc.maxXpOf(v)));
        if (v.owned().candyUsed > 0) out.add("&a(" + v.owned().candyUsed + "/10) Pet Candy Used");
        return out;
    }
}
