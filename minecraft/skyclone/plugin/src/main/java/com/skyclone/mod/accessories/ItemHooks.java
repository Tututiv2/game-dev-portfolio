package com.skyclone.mod.accessories;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Item-level hooks: the enrichment stat contributor and our lore lines. All pure functions of (def, data). */
final class ItemHooks {

    private ItemHooks() {}

    /**
     * Wave 5: Power Talisman / Ring / Artifact / Relic - "only half of the normal power can be harnessed". Reforges'
     * contributor puts full gem stats on the sheet; this one, registered in postEnable so it runs after it (core keeps
     * contributors in registration order), takes back {@code 1 - extra.gemPowerMult} of the GEMSTONE part, so the
     * lore and the bag agree. If reforges ever halves these itself, delete this registration.
     */
    static void registerGemPower() {
        Sky.items().addStatContributor("accessories-gem-power", (def, data, rarity, sheet) -> scaleGems(def, sheet));
    }

    /** Scales the GEMSTONE part of every stat on {@code sheet} by {@code def.extra.gemPowerMult}. Pure (unit-tested). */
    static void scaleGems(ItemDef def, ItemStatSheet sheet) {
        if (def == null || def.extra == null || !def.extra.has("gemPowerMult") || sheet == null) return;
        double mult;
        try { mult = def.extra.get("gemPowerMult").getAsDouble(); } catch (RuntimeException e) { return; }
        if (!(mult >= 0) || mult >= 1) return;
        for (Stat st : new ArrayList<>(sheet.view().keySet())) {
            double gem = sheet.part(st, ItemStatSheet.Source.GEMSTONE);
            if (gem != 0) sheet.add(st, -gem * (1 - mult), ItemStatSheet.Source.GEMSTONE);
        }
    }

    static void register(Catalog cat) {
        Sky.items().addStatContributor("accessories-enrichment", (def, data, rarity, sheet) -> {
            if (def == null || data == null || data.extra == null || !Catalog.isAccessory(def)) return;
            if (!data.extra.has("enrichment")) return;
            Stat s = Stat.parse(data.extra.get("enrichment").getAsString());
            if (s == null) return;
            Double v = cat.enrichValues.get(s);
            sheet.add(s, v == null ? 1 : v, ItemStatSheet.Source.OTHER);
        });

        // Vanquished equipment: the stat a piece has built up lives on the piece itself
        Sky.items().addStatContributor("accessories-piece-bonus", (def, data, rarity, sheet) -> {
            if (def == null || data == null || !Catalog.isEquipment(def)) return;
            Fx pb = pieceBonus(cat, def);
            if (pb == null || pb.stat == null) return;
            int b = AccMath.pieceBonus(pieceCount(data), AccDials.PIECE_EVERY.get(), AccDials.PIECE_CAP.get());
            if (b > 0) sheet.add(pb.stat, b, ItemStatSheet.Source.OTHER);
        });

        Sky.items().addLoreContributor("accessories-info", new LoreContributor() {
            @Override public Section section() { return Section.AFTER_STATS; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                List<String> out = new ArrayList<>(afterStats(def, data));
                Fx pb = pieceBonus(cat, def);
                if (pb != null && pb.stat != null) {
                    int every = Math.max(1, AccDials.PIECE_EVERY.get()), cap = AccDials.PIECE_CAP.get();
                    long n = pieceCount(data);
                    int b = AccMath.pieceBonus(n, every, cap);
                    out.add("&7" + (pb.item != null ? "Mined" : "Kills") + ": &a" + Text.commasInt(n) + " &8| &7Piece Bonus: &a+" + b + " " + pb.stat.display);
                    if (b < cap) out.add("&8Next upgrade: +" + (b + 1) + " (" + Text.commasInt(n % every) + "/" + Text.commasInt(every) + ")");
                    else out.add("&8Maxed!");
                }
                return out;
            }
        });
        Sky.items().addLoreContributor("accessories-hint", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                if (Catalog.isAccessory(def)) {
                    if (def.extra != null && def.extra.has("effects")) {
                        String fx = def.extra.get("effects").toString();
                        if (fx.contains("\"compactor\"") || fx.contains("\"deletor\"")) {
                            return List.of("&8Works while in your Accessory Bag!", "&eRight-click to configure!");
                        }
                    }
                    return List.of("&8Works while in your Accessory Bag!", "&eRight-click to put it in your bag!");
                }
                if (Catalog.isEquipment(def)) return List.of("&eRight-click to equip!");
                return List.of();
            }
        });
    }

    static final String PIECE_KEY = "pieceCount";

    static Fx pieceBonus(Catalog cat, ItemDef def) {
        if (def == null) return null;
        for (Fx f : cat.effects(def)) if (f.type.equals("piece_bonus")) return f;
        return null;
    }

    static long pieceCount(ItemData data) {
        if (data == null || data.extra == null || !data.extra.has(PIECE_KEY)) return 0;
        try { return data.extra.get(PIECE_KEY).getAsLong(); } catch (RuntimeException e) { return 0; }
    }

    static List<String> afterStats(ItemDef def, ItemData data) {
        boolean acc = Catalog.isAccessory(def);
        if (def == null || (!acc && !Catalog.isEquipment(def))) return List.of();
        List<String> out = new ArrayList<>();
        JsonObject extra = def.extra;
        if (extra != null && extra.has("otherStats") && extra.get("otherStats").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : extra.getAsJsonObject("otherStats").entrySet()) {
                double v = e.getValue().getAsDouble();
                out.add("&7" + Text.titleCase(e.getKey()) + ": &6" + (v >= 0 ? "+" : "") + PowerMenus.fmt(v));
            }
        }
        if (extra != null && extra.has("riftStats") && extra.get("riftStats").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : extra.getAsJsonObject("riftStats").entrySet()) {
                double v = e.getValue().getAsDouble();
                String k = e.getKey().startsWith("RIFT_") && !e.getKey().equals("RIFT_TIME") ? e.getKey().substring(5) : e.getKey();
                out.add("&7" + Text.titleCase(k) + ": &d" + (v >= 0 ? "+" : "") + PowerMenus.fmt(v)
                        + (e.getKey().equals("RIFT_TIME") ? "s" : "") + " &8(Rift)");
            }
        }
        if (acc && data != null && data.extra != null && data.extra.has("enrichment")) {
            Stat s = Stat.parse(data.extra.get("enrichment").getAsString());
            if (s != null) out.add("&d✦ Enriched with " + s.display);
        }
        if (acc) {
            if (Catalog.noPower(def)) {
                out.add("&8No Accessory Power");
            } else {
                Rarity r = def.rarity == null ? Rarity.COMMON : def.rarity;
                if (data != null && data.recombobulated) r = r.upgrade();
                int mp = (int) Math.round(AccMath.mpFor(r, AccDials.mpTable()) * Catalog.mpMult(def));
                out.add("&7Accessory Power: &6+" + mp);
            }
        }
        return out;
    }
}
