package com.skyclone.mod.skills;

import com.skyclone.api.Sky;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every line of skill text the player sees: reward lists, perk sentences, unlock lines.
 * Unlock names are resolved at RUNTIME from the owning module's registry (item, enchant, mob,
 * island), so their renames show here automatically; the fallbacks never name a reference-game
 * proper noun.
 */
final class SkillText {

    private SkillText() {}

    static final String ARROW = "➜";
    private static final int WRAP = 38;

    static String fmt(double v) { return Text.commas(Math.round(v * 100.0) / 100.0); }

    static String name(SkillType s, int level) { return s.display + " " + (level <= 0 ? "0" : Text.roman(level)); }

    /** "&8+&a2 &c❤ Health" */
    static String statLine(Stat st, double v) {
        return "&8+&a" + fmt(v) + (st.percent ? "%" : "") + " " + st.label();
    }

    /** The perk value: "&a+8" (summary) or "&8+4➜&a8" (a level's reward). */
    static String perkValue(SkillTable.Spec spec, double prev, double cur) {
        boolean plus = spec.perkStat != null;
        String p = plus ? "+" : "";
        if (prev > 0 && prev != cur) return "&8" + p + fmt(prev) + ARROW + "&a" + fmt(cur);
        return "&a" + p + fmt(cur);
    }

    /** Perk sentence(s) for one value change, wrapped, each line prefixed with {@code indent}. */
    static List<String> perkLines(SkillTable.Spec spec, double prev, double cur, double prev2, double cur2, String indent) {
        List<String> out = new ArrayList<>();
        if (spec.perkText == null || cur <= 0) return out;
        for (String l : Text.wrap(spec.perkText.replace("{v}", perkValue(spec, prev, cur) + "&f"), WRAP)) out.add(indent + l);
        if (spec.perk2Text != null && cur2 > 0) {
            String v = (prev2 > 0 && prev2 != cur2 ? "&8" + fmt(prev2) + ARROW : "") + "&a" + fmt(cur2);
            for (String l : Text.wrap(spec.perk2Text.replace("{v}", v + "&f"), WRAP)) out.add(indent + l);
        }
        return out;
    }

    /**
     * The reward lines of ONE level, the way the reference shows them under "Level V Rewards:".
     * {@code coinScale} is the coin dial; {@code perkScale} the perk dial.
     */
    static List<String> levelRewards(SkillTable table, SkillType s, int level, double coinScale, double perkScale, String indent) {
        List<String> out = new ArrayList<>();
        SkillTable.Level lv = table.level(s, level);
        if (lv == null) return out;
        SkillTable.Spec spec = table.spec(s);
        if (spec.perkName != null && lv.perk > 0) {
            out.add(indent + "&e" + spec.perkName + " " + Text.roman(level));
            out.addAll(perkLines(spec, table.perk(s, level - 1) * perkScale, lv.perk * perkScale,
                    table.perk2(s, level - 1) * perkScale, lv.perk2 * perkScale, indent + "  "));
        }
        for (Map.Entry<String, Double> e : lv.stats.entrySet()) {
            Stat st = Stat.parse(e.getKey());
            if (st != null) out.add(indent + statLine(st, e.getValue()));
        }
        out.addAll(unlockLines(lv.unlocks, indent));
        if (lv.coins > 0 && coinScale > 0) out.add(indent + "&8+&6" + Text.commasInt(lv.coins * coinScale) + " &7Coins");
        if (lv.sbxp > 0) out.add(indent + "&8+&b" + lv.sbxp + " SkyBlock XP");
        return out;
    }

    /** Lines for a list of unlocks; sea creatures the fishing module has not registered collapse into a count. */
    static List<String> unlockLines(List<SkillTable.Unlock> unlocks, String indent) {
        List<String> out = new ArrayList<>();
        int hiddenCreatures = 0, stones = 0, powerStones = 0;
        for (SkillTable.Unlock u : unlocks) {
            if ("reforge_stone".equals(u.type) && item(u.id) == null) { stones++; continue; }
            if ("power_stone".equals(u.type) && item(u.id) == null) { powerStones++; continue; }
            String line = unlockLine(u);
            if (line == null) { if ("sea_creature".equals(u.type)) hiddenCreatures++; continue; }
            out.add(indent + line);
        }
        if (stones > 0) out.add(indent + "&7" + stones + " new &9Reforge Stone" + (stones == 1 ? "" : "s"));
        if (powerStones > 0) out.add(indent + "&7" + powerStones + " new &5Power Stone" + (powerStones == 1 ? "" : "s"));
        if (hiddenCreatures > 0)
            out.add(indent + "&7" + hiddenCreatures + " new &3Sea Creature" + (hiddenCreatures == 1 ? "" : "s"));
        return out;
    }

    /** One unlock, or null when it should be folded into a count (unknown sea creature). */
    static String unlockLine(SkillTable.Unlock u) {
        if (u == null || u.type == null) return null;
        switch (u.type) {
            case "access": {
                String nm = u.name;
                try {
                    IslandDef d = Sky.worlds().island(u.id);
                    if (d != null && d.displayName != null && !d.displayName.isBlank()) nm = d.displayName;
                } catch (RuntimeException ignored) { /* worlds not up (unit test) */ }
                return "&7Access to &a" + nm;
            }
            case "reforge_stone": {
                ItemDef d = item(u.id);
                return d == null ? "&7A new &9Reforge Stone" : "&7Reforge Stone: " + d.rarity.code() + Text.strip(d.name);
            }
            case "power_stone": {
                ItemDef d = item(u.id);
                return d == null ? "&7A new &5Power Stone" : "&7Power Stone: " + d.rarity.code() + Text.strip(d.name);
            }
            case "recipe": {
                ItemDef d = item(u.id);
                String nm = d != null ? d.rarity.code() + Text.strip(d.name) : "&a" + u.name;
                return "&7Recipe: " + nm;
            }
            case "furniture": {
                // wave 5: crafting registers the furniture (data/crafting/furniture.json); show its name and rarity
                ItemDef d = item(u.id);
                String nm = d != null ? d.rarity.code() + Text.strip(d.name) : "&f" + u.name;
                return "&7Furniture: " + nm;
            }
            case "enchant": {
                EnchantDef e = enchant(u.id);
                if (e == null && hideMissingEnchants.getAsBoolean()) return null;   // nobody can get it here: do not advertise it
                return "&7Enchantment: &9" + (e != null && e.name != null ? Text.strip(e.name) : u.name);
            }
            case "sea_creature": {
                if (u.id == null) return null;
                MobDef m = mob(u.id);
                return m == null ? null : "&7Sea Creature: &3" + Text.strip(m.name);
            }
            default:
                return u.name == null ? null : (u.name.startsWith("&") ? u.name : "&7" + u.name);
        }
    }

    /** Set by the module from the dial {@code skills.hide-missing-enchant-unlocks}; false in unit tests. */
    static java.util.function.BooleanSupplier hideMissingEnchants = () -> false;

    static ItemDef item(String id) {
        if (id == null) return null;
        try { return Sky.items().exists(id) ? Sky.items().def(id) : null; } catch (RuntimeException e) { return null; }
    }

    static EnchantDef enchant(String id) {
        if (id == null) return null;
        try {
            EnchantDef e = Sky.items().enchant(id);
            if (e == null) e = Sky.items().enchant(id.toUpperCase(Locale.ROOT));
            return e;
        } catch (RuntimeException ex) { return null; }
    }

    /** Sea creature lookup: the fishing module's id, tried with the prefixes modules commonly use. */
    static MobDef mob(String id) {
        if (id == null) return null;
        for (String cand : new String[]{id, "sea_" + id, "fishing_" + id, "sc_" + id}) {
            try {
                MobDef m = Sky.mobs().def(cand);
                if (m != null) return m;
            } catch (RuntimeException ignored) { /* unknown id or mobs not up */ }
        }
        return null;
    }

    /**
     * The combined rewards of several levels at once (admin setlevel, a cap raise, a huge XP drop):
     * the perk's new total, summed stats, unlocks, coins and SkyBlock XP actually paid.
     */
    static List<String> bulkRewards(SkillTable table, SkillType s, int from, int to, double coinsPaid, int sbxp,
                                    double perkScale, String indent) {
        List<String> out = new ArrayList<>();
        SkillTable.Spec spec = table.spec(s);
        if (spec.perkName != null && table.perk(s, to) > 0) {
            out.add(indent + "&e" + spec.perkName + " " + Text.roman(to));
            out.addAll(perkLines(spec, table.perk(s, from) * perkScale, table.perk(s, to) * perkScale,
                    table.perk2(s, from) * perkScale, table.perk2(s, to) * perkScale, indent + "  "));
        }
        EnumMap<Stat, Double> sum = new EnumMap<>(Stat.class);
        List<SkillTable.Unlock> unlocks = new ArrayList<>();
        for (int l = from + 1; l <= to; l++) {
            SkillTable.Level lv = table.level(s, l);
            if (lv == null) continue;
            lv.stats.forEach((k, v) -> { Stat st = Stat.parse(k); if (st != null) sum.merge(st, v, Double::sum); });
            unlocks.addAll(lv.unlocks);
        }
        sum.forEach((st, v) -> out.add(indent + statLine(st, v)));
        List<String> ul = unlockLines(unlocks, indent);
        if (ul.size() > 8) {
            out.addAll(ul.subList(0, 7));
            out.add(indent + "&8...and " + (ul.size() - 7) + " more (see /skills)");
        } else out.addAll(ul);
        if (coinsPaid > 0) out.add(indent + "&8+&6" + Text.commasInt(coinsPaid) + " &7Coins");
        if (sbxp > 0) out.add(indent + "&8+&b" + sbxp + " SkyBlock XP");
        return out;
    }
}
