package com.skyclone.mod.minions;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.minions.MinionCatalog.TypeDef;
import com.skyclone.mod.minions.MinionStore.Placed;

import java.util.Map;

/** Shared wording so every menu and message names minions the same way. */
final class MinionText {

    private MinionText() {}

    /** "&9Cobblestone Minion IV" from the registered def (so a postEnable rename shows). */
    static String name(TypeDef t, int tier) {
        ItemDef d = Sky.items().def(t.itemId(tier));
        if (d != null) return d.rarity.code() + d.name;
        return Rarity.RARE.code() + t.name + " Minion " + Text.roman(tier);
    }

    static String plain(TypeDef t, int tier) { return Text.strip(name(t, tier)); }

    static String where(Placed m) { return m.x + ", " + m.y + ", " + m.z; }

    static String category(String c) {
        return switch (c == null ? "" : c) {
            case "FARMING" -> "&eFarming";
            case "MINING" -> "&7Mining";
            case "COMBAT" -> "&cCombat";
            case "FORAGING" -> "&2Foraging";
            case "FISHING" -> "&3Fishing";
            case "SLAYER" -> "&4Slayer";
            default -> "&f" + c;
        };
    }

    static String skillOf(TypeDef t) {
        return switch (t.skill == null ? "" : t.skill) {
            case "FARMING" -> "&eFarming";
            case "MINING" -> "&7Mining";
            case "COMBAT" -> "&cCombat";
            case "FORAGING" -> "&2Foraging";
            case "FISHING" -> "&3Fishing";
            default -> "&f" + Text.titleCase(t.skill == null ? "" : t.skill);
        };
    }

    static String itemName(String id) { return MinionManager.name(id); }

    static String rate(double perHour) {
        if (perHour >= 100) return Text.commasInt(perHour);
        return Text.commas(Math.round(perHour * 10) / 10.0);
    }

    static String seconds(double s) {
        double r = Math.round(s * 10) / 10.0;
        return (r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r)) + "s";
    }

    static String pct(double v) {
        double r = Math.round(v * 10) / 10.0;
        return (r == Math.floor(r) ? String.valueOf((long) r) : String.valueOf(r)) + "%";
    }

    static String sumKey(Map<String, Double> m) {
        double s = 0;
        for (double v : m.values()) s += v;
        return pct(s);
    }
}
