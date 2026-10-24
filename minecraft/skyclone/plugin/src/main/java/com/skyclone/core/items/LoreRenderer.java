package com.skyclone.core.items;

import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.ReforgeDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns (def, instance data, stat sheet) into the genre's name + lore. Pure Java: no Bukkit, so the
 * exact text is unit-testable. Order (research 01 §21, LoreContributor javadoc):
 *
 * <pre>
 *  name            rarity colour + [reforge] + name + [✪✪✪✪✪][➊..➎]
 *  subtitle        &8Breaking Power N            (tools)
 *  stats           &7Strength: &c+75 &e(+20) &9(+15) &d[+10]
 *  gem row          &8[⸕] &5[&d❁&5] ...
 *  AFTER_STATS
 *  ·
 *  enchants        ultimates first (&d&l); <= N enchants: one per line + description
 *  AFTER_ENCHANTS
 *  ·
 *  description
 *  ·
 *  abilities       &6Ability: Name &e&lRIGHT CLICK / lore / &8Mana Cost / &8Cooldown
 *  reforge bonus   &9Heroic Bonus / lore
 *  full set bonus  &6Full Set Bonus: Name / lore
 *  BEFORE_FOOTER
 *  ·
 *  &8This item can be reforged!       (reforgeable and not reforged)
 *  &8&l* &8Co-op Soulbound &8&l*      (tag soulbound)
 *  &4❣ &cRequires <describe>&c.       (each requirement)
 *  footer          &6&lLEGENDARY DUNGEON SWORD (recomb: &ka markers)
 * </pre>
 * Consecutive blanks collapse; no leading blank; never a blank as the last line.
 */
public final class LoreRenderer {

    private LoreRenderer() {}

    public record Rendered(String name, List<String> lore) {}

    /** Everything the renderer needs from the outside world. */
    public interface Env {
        EnchantDef enchant(String id);
        ReforgeDef reforge(String id);
        String describeRequirement(String key);
        List<LoreContributor> loreContributors();
        String displayName(ItemDef def);
        void contributorFailed(Object contributor, Throwable t);
        /** Per-instance name prefix (wave 5, {@code ItemService.addNamePrefix}); "" for none. */
        default String namePrefix(ItemDef def, ItemData data) { return ""; }
    }

    /** Stats printed in &c (offensive); everything else prints &a. */
    public static final Set<Stat> RED_STATS = EnumSet.of(Stat.STRENGTH, Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE,
            Stat.ATTACK_SPEED, Stat.ABILITY_DAMAGE, Stat.FEROCITY);

    /** Display order (research 01 §21 row 4-5), the rest follow in enum order. */
    public static final List<Stat> ORDER = List.of(
            Stat.STRENGTH, Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED, Stat.ABILITY_DAMAGE,
            Stat.HEALTH, Stat.DEFENSE, Stat.SPEED, Stat.INTELLIGENCE, Stat.MAGIC_FIND, Stat.PET_LUCK,
            Stat.TRUE_DEFENSE, Stat.FEROCITY, Stat.MINING_SPEED, Stat.PRISTINE, Stat.MINING_FORTUNE,
            Stat.FARMING_FORTUNE, Stat.FORAGING_FORTUNE, Stat.SEA_CREATURE_CHANCE, Stat.FISHING_SPEED,
            Stat.HEALTH_REGEN, Stat.VITALITY, Stat.MENDING, Stat.SWING_RANGE);

    private static final List<Stat> FULL_ORDER;
    static {
        List<Stat> l = new ArrayList<>(ORDER);
        for (Stat s : Stat.values()) if (!l.contains(s) && s != Stat.BREAKING_POWER) l.add(s);
        FULL_ORDER = List.copyOf(l);
    }

    private static final String[] MASTER = {"➊", "➋", "➌", "➍", "➎"};

    public static Rendered render(ItemDef def, ItemData data, Rarity rarity, ItemStatSheet sheet, Env env, int enchantDetailMax) {
        if (data == null) data = new ItemData();
        String rc = rarity.code();

        // ---- name ---------------------------------------------------------------------------
        StringBuilder name = new StringBuilder();
        String prefix = env.namePrefix(def, data);
        if (prefix != null && !prefix.isEmpty()) name.append(prefix);
        name.append(rc);
        ReforgeDef rf = data.reforge == null ? null : env.reforge(data.reforge);
        if (rf != null && rf.name != null && !rf.name.isEmpty()) name.append(rf.name).append(' ');
        else if (data.reforge != null && rf == null) name.append(Text.titleCase(data.reforge)).append(' ');
        name.append(env.displayName(def));
        name.append(stars(data.stars));

        List<String> out = new ArrayList<>();

        // ---- subtitle -----------------------------------------------------------------------
        double bp = sheet.total(Stat.BREAKING_POWER);
        if (bp > 0) out.add("&8Breaking Power " + num(bp));

        // ---- stats --------------------------------------------------------------------------
        if (sheet.damage() != 0) {
            StringBuilder s = new StringBuilder("&7Damage: &c").append(signed(sheet.damage(), false));
            if (sheet.damageBonus() != 0) s.append(" &e(").append(signed(sheet.damageBonus(), false)).append(')');
            out.add(s.toString());
        }
        for (Stat st : FULL_ORDER) {
            String line = statLine(st, sheet);
            if (line != null) out.add(line);
        }
        String gems = gemRow(def, data);
        if (gems != null) out.add(gems);
        contribute(out, def, data, LoreContributor.Section.AFTER_STATS, env);
        out.add("");

        // ---- enchants -----------------------------------------------------------------------
        enchantBlock(out, data, env, enchantDetailMax);
        contribute(out, def, data, LoreContributor.Section.AFTER_ENCHANTS, env);
        out.add("");

        // ---- description --------------------------------------------------------------------
        if (def.lore != null) for (String l : def.lore) out.add(l == null ? "" : l);
        out.add("");

        // ---- abilities ----------------------------------------------------------------------
        if (def.abilities != null) for (AbilityDef a : def.abilities) {
            if (a == null) continue;
            AbilityDef.Trigger tr = a.trigger == null ? AbilityDef.Trigger.RIGHT_CLICK : a.trigger;
            String label = tr.label == null || tr.label.isEmpty() ? "" : " &e&l" + tr.label;
            out.add("&6Ability: " + (a.name == null ? Text.titleCase(String.valueOf(a.id)) : a.name) + label);
            if (a.lore != null) for (String l : a.lore) out.add(fillDamage(l == null ? "" : l, a.damage));
            if (a.mana > 0) {
                if (a.mana < 1) out.add("&8Mana Cost: &3" + num(a.mana * 100) + "% &8of max");
                else out.add("&8Mana Cost: &3" + num(a.mana));
            }
            if (a.cooldown > 0) out.add("&8Cooldown: &a" + num(a.cooldown) + "s");
            out.add("");
        }
        if (rf != null && rf.bonusName != null && !rf.bonusName.isEmpty()) {
            out.add("&9" + rf.bonusName);
            if (rf.bonusLore != null) out.addAll(rf.bonusLore);
            out.add("");
        }
        if (def.setBonusName != null && !def.setBonusName.isEmpty()) {
            out.add("&6Full Set Bonus: " + def.setBonusName);
            if (def.setBonusLore != null) out.addAll(def.setBonusLore);
            out.add("");
        }
        contribute(out, def, data, LoreContributor.Section.BEFORE_FOOTER, env);
        out.add("");

        // ---- tail ---------------------------------------------------------------------------
        if (reforgeable(def) && data.reforge == null) out.add("&8This item can be reforged!");
        if (def.has("soulbound")) out.add("&8&l* &8Co-op Soulbound &8&l*");
        if (def.requirements != null) for (String key : def.requirements) {
            if (key == null || key.isBlank()) continue;
            String d;
            try { d = env.describeRequirement(key); } catch (Throwable t) { d = null; }
            if (d == null || d.isBlank()) d = "&a" + key;
            out.add("&4❣ &cRequires " + d + "&c.");
        }
        out.add(footer(def, rarity, data.recombobulated));
        return new Rendered(name.toString(), collapse(out));
    }

    // ---- pieces -------------------------------------------------------------------------------

    /** "" for 0, " &6✪✪✪" for 1-5, " &6✪✪✪✪✪&c➋" for 7. */
    public static String stars(int stars) {
        if (stars <= 0) return "";
        int gold = Math.min(5, stars);
        StringBuilder sb = new StringBuilder(" &6").append("✪".repeat(gold));
        if (stars > 5) sb.append("&c").append(MASTER[Math.min(5, stars - 5) - 1]);
        return sb.toString();
    }

    /** "&7Strength: &c+75 &e(+20) &9(+15) &d[+10]" or null when the stat is not on the item. */
    public static String statLine(Stat st, ItemStatSheet sheet) {
        double total = sheet.total(st);
        if (total == 0) return null;
        char col = RED_STATS.contains(st) ? 'c' : 'a';
        StringBuilder s = new StringBuilder("&7").append(st.display).append(": &").append(col).append(signed(total, st.percent));
        double potato = sheet.part(st, ItemStatSheet.Source.POTATO);
        double reforge = sheet.part(st, ItemStatSheet.Source.REFORGE);
        double gem = sheet.part(st, ItemStatSheet.Source.GEMSTONE);
        if (potato != 0) s.append(" &e(").append(signed(potato, st.percent)).append(')');
        if (reforge != 0) s.append(" &9(").append(signed(reforge, st.percent)).append(')');
        if (gem != 0) s.append(" &d[").append(signed(gem, st.percent)).append(']');
        return s.toString();
    }

    public static String footer(ItemDef def, Rarity rarity, boolean recomb) {
        String rc = rarity.code();
        ItemCategory cat = def.category == null ? ItemCategory.MATERIAL : def.category;
        String word = cat.label == null ? "" : cat.label;
        if (def.dungeon) {
            if (word.isEmpty()) word = "DUNGEON ITEM";
            else if (!word.startsWith("DUNGEON")) word = "DUNGEON " + word;
        }
        String text = rarity.display() + (word.isEmpty() ? "" : " " + word);
        if (recomb) return rc + "&l&ka&r " + rc + "&l" + text + " " + rc + "&l&ka";
        return rc + "&l" + text;
    }

    /** Explicit tag wins; otherwise weapons, armour, tools, equipment and accessories are reforgeable. */
    public static boolean reforgeable(ItemDef def) {
        if (def.has("no_reforge") || def.has("not_reforgeable")) return false;
        if (def.has("reforgeable")) return true;
        ItemCategory c = def.category;
        return c != null && (c.isWeapon() || c.isArmor() || c.isTool() || c.isEquipment() || c == ItemCategory.ACCESSORY);
    }

    private static void enchantBlock(List<String> out, ItemData data, Env env, int detailMax) {
        if (data.enchants == null || data.enchants.isEmpty()) return;
        List<String> ult = new ArrayList<>(), norm = new ArrayList<>();
        List<String[]> detail = new ArrayList<>();
        for (Map.Entry<String, Integer> e : data.enchants.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0) continue;
            EnchantDef ed = env.enchant(e.getKey());
            String nm = (ed != null && ed.name != null ? ed.name : Text.titleCase(e.getKey())) + " " + Text.roman(e.getValue());
            boolean ultimate = ed != null && ed.ultimate;
            String coloured = (ultimate ? "&d&l" : "&9") + nm;
            (ultimate ? ult : norm).add(coloured);
            String desc = "";
            if (ed != null && ed.description != null && !ed.description.isEmpty()) {
                // plain replace, not String.format: descriptions contain "%&7" which format() rejects
                desc = ed.description.replace("%s", num(ed.value(e.getValue())));
            }
            detail.add(new String[]{coloured, desc});
        }
        List<String> all = new ArrayList<>(ult);
        all.addAll(norm);
        if (all.isEmpty()) return;
        if (all.size() <= detailMax) {
            // one per line, ultimates first, each followed by its description
            List<String[]> sorted = new ArrayList<>();
            for (String[] d : detail) if (d[0].startsWith("&d&l")) sorted.add(d);
            for (String[] d : detail) if (!d[0].startsWith("&d&l")) sorted.add(d);
            for (String[] d : sorted) {
                out.add(d[0]);
                if (!d[1].isEmpty()) out.addAll(Text.wrap(d[1].startsWith("&") ? d[1] : "&7" + d[1], 40));
            }
            return;
        }
        // comma-joined, three per line
        for (int i = 0; i < all.size(); i += 3) {
            StringBuilder line = new StringBuilder();
            for (int j = i; j < Math.min(all.size(), i + 3); j++) {
                if (j > i) line.append("&9, ");
                line.append(all.get(j));
            }
            out.add(line.toString());
        }
    }

    /** One bracket per gem slot: filled = quality-coloured bracket around the gem symbol. */
    public static String gemRow(ItemDef def, ItemData data) {
        if (def.gemSlots == null || def.gemSlots.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < def.gemSlots.size(); i++) {
            String slot = def.gemSlots.get(i) == null ? "UNIVERSAL" : def.gemSlots.get(i).toUpperCase(Locale.ROOT);
            String gem = data.gems == null ? null : data.gems.get(String.valueOf(i));
            sb.append(' ');
            if (gem != null && !gem.isEmpty()) {
                String[] qt = gemQualityType(gem);
                char q = qualityColor(qt[0]);
                sb.append('&').append(q).append('[').append('&').append(typeColor(qt[1])).append(symbol(qt[1]))
                        .append('&').append(q).append(']');
            } else {
                boolean unlocked = data.unlockedGemSlots != null && data.unlockedGemSlots.contains(String.valueOf(i));
                sb.append(unlocked ? "&7[&8" : "&8[").append(symbol(slot)).append(unlocked ? "&7]" : "&8]");
            }
        }
        return sb.toString();
    }

    /** "FINE_JASPER_GEM" -> {"FINE","JASPER"}. */
    public static String[] gemQualityType(String gemId) {
        String s = gemId.toUpperCase(Locale.ROOT);
        if (s.endsWith("_GEM")) s = s.substring(0, s.length() - 4);
        int u = s.indexOf('_');
        if (u < 0) return new String[]{"ROUGH", s};
        return new String[]{s.substring(0, u), s.substring(u + 1)};
    }

    public static char qualityColor(String q) {
        return switch (q) {
            case "ROUGH" -> 'f';
            case "FLAWED" -> 'a';
            case "FINE" -> '9';
            case "FLAWLESS" -> '5';
            case "PERFECT" -> '6';
            default -> '7';
        };
    }

    public static char typeColor(String t) {
        return switch (t) {
            case "RUBY" -> 'c';
            case "AMETHYST" -> '5';
            case "JADE" -> 'a';
            case "AMBER" -> '6';
            case "SAPPHIRE" -> 'b';
            case "TOPAZ" -> 'e';
            case "JASPER" -> 'd';
            case "OPAL" -> 'f';
            case "ONYX" -> '8';
            case "AQUAMARINE" -> '3';
            case "CITRINE" -> '4';
            case "PERIDOT" -> '2';
            default -> '7';
        };
    }

    public static String symbol(String t) {
        return switch (t) {
            case "RUBY" -> "❤";
            case "AMETHYST" -> "❈";
            case "JADE" -> "☘";
            case "AMBER" -> "⸕";
            case "SAPPHIRE" -> "✎";
            case "TOPAZ" -> "✧";
            case "JASPER" -> "❁";
            case "OPAL" -> "❂";
            case "ONYX" -> "☠";
            case "AQUAMARINE" -> "α";
            case "CITRINE" -> "☘";
            case "PERIDOT" -> "☘";
            case "COMBAT" -> "⚔";
            case "OFFENSIVE" -> "☠";
            case "DEFENSIVE" -> "☤";
            case "MINING" -> "✦";
            default -> "❂"; // UNIVERSAL, CHISEL
        };
    }

    private static void contribute(List<String> out, ItemDef def, ItemData data, LoreContributor.Section sec, Env env) {
        for (LoreContributor c : env.loreContributors()) {
            try {
                if (c.section() != sec) continue;
                List<String> l = c.lines(def, data);
                if (l != null) for (String s : l) out.add(s == null ? "" : s);
            } catch (Throwable t) {
                env.contributorFailed(c, t);
            }
        }
    }

    /** "%d" and "{damage}" in ability lore become the ability's base damage. */
    static String fillDamage(String line, double damage) {
        if (damage <= 0) return line;
        String d = Text.commasInt(damage);
        return line.replace("{damage}", d).replace("%d", d);
    }

    /** Drop leading blanks, collapse runs, never end on a blank before the footer. */
    public static List<String> collapse(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) {
            boolean blank = s == null || Text.strip(s).isBlank();
            if (blank) {
                if (out.isEmpty() || isBlank(out.get(out.size() - 1))) continue;
                out.add("");
            } else out.add(s);
        }
        while (!out.isEmpty() && isBlank(out.get(out.size() - 1))) out.remove(out.size() - 1);
        return out;
    }

    private static boolean isBlank(String s) { return s == null || Text.strip(s).isBlank(); }

    /** 12 -> "12", 2.5 -> "2.5", 1234 -> "1,234". */
    public static String num(double v) {
        double r = Math.round(v * 10) / 10.0;
        return Text.commas(r);
    }

    public static String signed(double v, boolean percent) {
        String s = (v < 0 ? "-" : "+") + num(Math.abs(v));
        return percent ? s + "%" : s;
    }
}
