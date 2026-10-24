package com.skyclone.mod.enchanting;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.stat.Stat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Item-instance stats from enchants (Protection -> Defense, Growth -> Health, Critical -> Crit Damage,
 * Efficiency -> Mining Speed...) as one ItemStatContributor, so the lore line and the player's real
 * stat are the same number. The enchant -> stat table is data (enchants.json "stats").
 * Also the lore of level-by-use enchants ("Champion: 12,345 / 50,000 Combat XP").
 */
final class StatEnchants {

    private StatEnchants() {}

    /** ItemData.extra keys of the level-by-use counters. */
    static String counterKey(String enchant) { return "ench_progress_" + enchant; }

    static void register() {
        Sky.items().addStatContributor("enchanting", (def, data, rarity, sheet) -> {
            if (data == null || data.enchants == null || data.enchants.isEmpty()) return;
            if (!EnchantingModule.dials().effects.get()) return;
            EnchantRegistry reg = EnchantingModule.reg();
            for (Map.Entry<String, Integer> e : data.enchants.entrySet()) {
                EnchantSpec s = reg.spec(e.getKey());
                int lvl = e.getValue() == null ? 0 : e.getValue();
                if (s == null || lvl <= 0) continue;
                if (s.stats != null) for (EnchantSpec.StatRef ref : s.stats) {
                    Stat st = Stat.parse(ref.stat);
                    if (st != null) sheet.add(st, s.v(ref.series, lvl), ItemStatSheet.Source.ENCHANT);
                }
                if ("tabasco".equals(s.id)) sheet.addDamage(s.v(lvl), true);
            }
        });

        Sky.items().addLoreContributor("enchanting_progress", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }

            @Override public List<String> lines(ItemDef def, ItemData data) {
                if (data == null || data.enchants == null || data.enchants.isEmpty()) return List.of();
                List<String> out = new ArrayList<>();
                for (Map.Entry<String, Integer> e : data.enchants.entrySet()) {
                    EnchantSpec s = EnchantingModule.reg().spec(e.getKey());
                    if (s == null || !s.levelsByUse()) continue;
                    long have = counter(data, s.id);
                    int lvl = e.getValue() == null ? 1 : e.getValue();
                    String what = unit(s.levelBy.type);
                    if (lvl >= s.maxLevel) {
                        out.add("&7" + s.name + ": &a" + EnchantSpec.fmt(have) + " " + what + " &8(maxed)");
                    } else {
                        long need = s.thresholdFor(lvl + 1);
                        out.add("&7" + s.name + ": &e" + EnchantSpec.fmt(have) + "&7/&a" + EnchantSpec.fmt(need) + " " + what);
                    }
                }
                return out;
            }
        });
    }

    static long counter(ItemData data, String enchant) {
        if (data == null || data.extra == null) return 0;
        JsonElement el = data.extra.get(counterKey(enchant));
        try { return el == null ? 0 : el.getAsLong(); } catch (RuntimeException ex) { return 0; }
    }

    static String unit(String type) {
        if (type == null) return "";
        return switch (type) {
            case "combat_xp", "bow_combat_xp" -> "Combat XP";
            case "blocks_mined" -> "blocks";
            case "crops_harvested" -> "crops";
            case "logs_chopped" -> "logs";
            case "sea_creature_kills" -> "sea creatures";
            case "s_runs" -> "S runs";
            default -> "";
        };
    }
}
