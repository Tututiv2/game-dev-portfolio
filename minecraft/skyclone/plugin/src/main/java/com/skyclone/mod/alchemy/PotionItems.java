package com.skyclone.mod.alchemy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The bridge between {@link Brew.Bottle} (pure) and real ItemStacks, plus potion lore.
 *
 * <p>A potion's TYPE (effect + level, splash or not) is its item id ({@code POTION_SPEED;5},
 * {@code SPLASH_POTION_SPEED;5}); its MODIFIERS live in {@code ItemData.extra}:
 * {@code ext} (extra seconds), {@code enh} (levels added), {@code sp} (splash tier), {@code brewer} (%).
 */
final class PotionItems {

    private PotionItems() {}

    static final String WATER = "POTION";
    static final String AWKWARD = "POTION_AWKWARD";

    private static EffectTable t() { return AlchemyModule.table(); }

    // ---- def helpers ---------------------------------------------------------------------------

    static String str(JsonObject o, String k) {
        if (o == null) return null;
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static int integer(JsonObject o, String k, int def) {
        if (o == null) return def;
        JsonElement e = o.get(k);
        try { return e == null || e.isJsonNull() ? def : e.getAsInt(); } catch (RuntimeException ex) { return def; }
    }

    static double number(JsonObject o, String k, double def) {
        if (o == null) return def;
        JsonElement e = o.get(k);
        try { return e == null || e.isJsonNull() ? def : e.getAsDouble(); } catch (RuntimeException ex) { return def; }
    }

    static boolean bool(JsonObject o, String k) {
        if (o == null) return false;
        JsonElement e = o.get(k);
        try { return e != null && !e.isJsonNull() && e.getAsBoolean(); } catch (RuntimeException ex) { return false; }
    }

    static boolean isPotionDef(ItemDef d) { return d != null && d.has("potion"); }
    static boolean isGod(ItemDef d) { return d != null && d.has("god_potion"); }
    static boolean isBundle(ItemDef d) { return d != null && d.has("bundle"); }
    static boolean isSplash(ItemDef d) { return d != null && d.has("splash"); }
    static boolean isBase(ItemDef d) { return d != null && d.has("alchemy_base"); }
    static boolean isBasicBrew(ItemDef d) { return d != null && d.has("basic_brew"); }
    static boolean isTavernDrink(ItemDef d) { return d != null && d.has("tavern_drink"); }
    /** Midnight Wit bottles (reference Bottle of Jyrre). */
    static boolean isJyrre(ItemDef d) { return d != null && d.has("jyrre"); }
    /** A skull you right-click to drink (Dread Draught). */
    static boolean isSkullPotion(ItemDef d) { return d != null && d.has("skull_potion"); }
    static boolean isRecall(ItemDef d) { return d != null && d.has("recall_potion"); }

    /**
     * Intelligence a Midnight Wit bottle gives right now: Refined = its cap; otherwise the minutes it has
     * spent in an inventory ({@code extra.jmin}) / the dial, capped.
     */
    static int jyrreBonus(ItemDef def, ItemData data) {
        int cap = integer(def.extra, "cap", 300);
        if ("refined".equals(str(def.extra, "jyrre"))) return cap;
        long min = integer(data == null ? null : data.extra, "jmin", 0);
        int per = AlchemyDials.JYRRE_MINUTES_PER_INT == null ? 10 : Math.max(1, AlchemyDials.JYRRE_MINUTES_PER_INT.get());
        return jyrreBonus(min, per, cap);
    }

    /** Pure: bonus after {@code minutes} at {@code perInt} minutes per Intelligence, capped. */
    static int jyrreBonus(long minutes, int perInt, int cap) {
        return (int) Math.max(0, Math.min(cap, minutes / Math.max(1, perInt)));
    }

    /** Anything the potion bag accepts. */
    static boolean bagAccepts(ItemStack st) {
        if (st == null || st.getType().isAir()) return false;
        ItemDef d = Sky.items().def(Sky.items().idOf(st));
        return d != null && (d.has("potion") || d.has("alchemy_base"));
    }

    // ---- stack <-> bottle ----------------------------------------------------------------------

    /** The bottle a stack represents, or null if it is not a brewable bottle. Accepts vanilla water bottles. */
    static Brew.Bottle bottleOf(ItemStack st) {
        if (st == null || st.getType().isAir()) return null;
        String id = Sky.items().idOf(st);
        if (id == null) {
            if (st.getType() == Material.POTION && st.getItemMeta() instanceof PotionMeta pm) {
                PotionType bt = pm.getBasePotionType();
                if (bt == null || bt == PotionType.WATER) return Brew.Bottle.water();
                if (bt == PotionType.AWKWARD) return Brew.Bottle.awkward();
            }
            return null;
        }
        if (WATER.equals(id)) return Brew.Bottle.water();
        if (AWKWARD.equals(id)) return Brew.Bottle.awkward();
        ItemDef d = Sky.items().def(id);
        if (isBasicBrew(d)) return Brew.Bottle.basicBrew(id);
        if (!isPotionDef(d) || isGod(d) || isBundle(d) || isSkullPotion(d)) return null;
        String eff = str(d.extra, "effect");
        int lvl = integer(d.extra, "level", 0);
        if (eff == null || lvl <= 0) return null;
        ItemData data = Sky.items().data(st);
        int sp = isSplash(d) ? Math.max(1, integer(data.extra, "sp", 1)) : 0;
        return new Brew.Bottle(Brew.Kind.POTION, eff, lvl, integer(data.extra, "ext", 0), integer(data.extra, "enh", 0),
                sp, number(data.extra, "brewer", 0), str(data.extra, "brew"));
    }

    /** A fresh stack for a bottle (one item). Null when the id is not registered. */
    static ItemStack itemOf(Brew.Bottle b) {
        switch (b.kind()) {
            case WATER -> { return create(WATER); }
            case AWKWARD -> { return create(b.brew() != null ? b.brew() : AWKWARD); }
            default -> { }
        }
        String id = EffectTable.potionId(b.effect(), b.level(), b.splash() > 0);
        ItemStack st = create(id);
        if (st == null) return null;
        if (b.ext() > 0 || b.enh() > 0 || b.splash() > 1 || b.brewer() > 0 || b.brew() != null) {
            Sky.items().update(st, d -> {
                if (b.ext() > 0) d.extra.addProperty("ext", b.ext());
                if (b.enh() > 0) d.extra.addProperty("enh", b.enh());
                if (b.splash() > 1) d.extra.addProperty("sp", b.splash());
                if (b.brewer() > 0) d.extra.addProperty("brewer", Math.round(b.brewer() * 10) / 10.0);
                if (b.brew() != null) d.extra.addProperty("brew", b.brew());
            });
        }
        return st;
    }

    static ItemStack create(String id) {
        if (!Sky.items().exists(id)) return null;
        try { return Sky.items().create(id, 1); } catch (RuntimeException e) { return null; }
    }

    /** Sky id, or the vanilla id for an unconverted vanilla stack (a menu inventory is never converted). */
    static String itemId(ItemStack st) {
        if (st == null || st.getType().isAir()) return null;
        String id = Sky.items().idOf(st);
        return id != null ? id : Sky.items().vanillaId(st.getType());
    }

    // ---- serialisation (bag + stand) -----------------------------------------------------------

    static String encode(ItemStack st) {
        if (st == null || st.getType().isAir()) return "";
        try { return Base64.getEncoder().encodeToString(st.serializeAsBytes()); }
        catch (RuntimeException e) { Sky.log().warning("[alchemy] could not serialise " + st.getType() + ": " + e); return ""; }
    }

    static ItemStack decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return ItemStack.deserializeBytes(Base64.getDecoder().decode(s)); }
        catch (RuntimeException e) { Sky.log().warning("[alchemy] could not read a stored item: " + e); return null; }
    }

    // ---- display ------------------------------------------------------------------------------

    /** "&9Speed V" for an effect id + level. */
    static String label(String effect, int level) {
        EffectTable.Effect e = t().effect(effect);
        return e == null ? "&7" + Text.titleCase(effect) + " " + EffectTable.roman(level) : e.label(level);
    }

    static List<String> effectLines(EffectTable.Effect e, int level, long seconds) {
        List<String> out = new ArrayList<>();
        out.add(e.label(level) + (seconds > 0 ? " &f(" + EffectTable.clock(seconds) + ")" : ""));
        EffectTable.Level l = e.level(level);
        if (l == null) l = e.levelAtMost(level);
        if (l != null && l.desc != null && !l.desc.isEmpty()) out.addAll(Text.wrap(l.desc, 36));
        return out;
    }

    static String hours(double h) {
        return h == Math.floor(h) ? (long) h + "h" : Text.commas(Math.round(h * 10) / 10.0) + "h";
    }

    /** Lore after the (empty) stat block: the effect line(s) and the modifiers. */
    static final LoreContributor EFFECTS = new LoreContributor() {
        @Override public Section section() { return Section.AFTER_STATS; }

        @Override public List<String> lines(ItemDef def, ItemData data) {
            if (def == null || !def.has("alchemy")) return List.of();
            EffectTable tab = AlchemyModule.tableOrNull();
            if (tab == null) return List.of();
            List<String> out = new ArrayList<>();
            if (isGod(def)) {
                boolean legacy = bool(def.extra, "legacy");
                if (legacy) out.add("&7Duration: &a" + hours(AlchemyDials.GOD_LEGACY_HOURS.get()));
                else {
                    out.add("&7Duration: &a" + hours(AlchemyDials.GOD_BASE_HOURS.get()) + " &7base");
                    out.add("  &a+" + hours(AlchemyDials.GOD_ALCHEMY_HOURS.get()) + " &7at &bAlchemy 50 &8(scales by level)");
                    out.add("  &6Parrot pet &7stretches it further");
                }
                out.add("&7Effects: &d" + tab.god().size() + " &7at once, ticking only");
                out.add("&7while you are online.");
                return out;
            }
            if (isJyrre(def)) {
                int cap = integer(def.extra, "cap", 300);
                int bonus = jyrreBonus(def, data);
                if ("refined".equals(str(def.extra, "jyrre"))) return List.of();     // its lore already says it all
                out.add("&7Intelligence Bonus: &b+" + bonus + "✎ &8/ +" + cap);
                out.add("&7Duration: &a" + integer(def.extra, "minutes", 60) + "m");
                String ev = str(def.extra, "evolves");
                var evDef = ev == null ? null : Sky.items().def(ev);
                if (evDef != null) out.add("&7At &b+" + cap + " &7it becomes " + evDef.rarity.code() + evDef.name + "&7.");
                return out;
            }
            if (isRecall(def)) {
                JsonObject x = data == null ? null : data.extra;
                String w = str(x, "recall_world");
                if (w == null) out.add("&8No location saved yet.");
                else out.add("&7Saved: &d" + (long) Math.floor(number(x, "recall_x", 0)) + ", "
                        + (long) Math.floor(number(x, "recall_y", 0)) + ", " + (long) Math.floor(number(x, "recall_z", 0)));
                return out;
            }
            if (isSkullPotion(def)) return List.of();     // its lore states the effect
            if (isBundle(def)) {
                EffectTable.Bundle b = tab.bundle(str(def.extra, "bundle"), integer(def.extra, "level", 1));
                if (b == null) return List.of();
                for (Map.Entry<String, Integer> x : b.effects.entrySet()) {
                    EffectTable.Effect e = tab.effect(x.getKey());
                    if (e != null) out.add(e.label(x.getValue()) + " &f(" + EffectTable.clock(b.seconds) + ")");
                }
                out.add("&8Works inside dungeons.");
                return out;
            }
            if (!isPotionDef(def)) return List.of();
            EffectTable.Effect e = tab.effect(str(def.extra, "effect"));
            int lvl = integer(def.extra, "level", 1);
            if (e == null) return List.of();
            JsonObject x = data == null ? null : data.extra;
            int ext = integer(x, "ext", 0), enh = integer(x, "enh", 0), sp = integer(x, "sp", 1);
            double brewer = number(x, "brewer", 0);
            EffectTable.BasicBrew bb = tab.brew(str(x, "brew"));
            Brew.Bottle b = new Brew.Bottle(Brew.Kind.POTION, e.id, lvl, ext, enh, isSplash(def) ? sp : 0, brewer);
            out.addAll(effectLines(e, lvl, Brew.duration(tab, b, 0, 1)));
            boolean any = enh > 0 || ext > 0 || brewer > 0 || bb != null || isSplash(def);
            if (any) out.add("");
            if (enh > 0) out.add("&7Enhanced &8(+" + enh + (enh == 1 ? " level" : " levels") + ")");
            if (ext > 0) out.add("&7Extended &8(+" + EffectTable.clock(ext) + ")");
            if (brewer > 0) out.add("&7Brewer bonus &8(+" + Text.commas(brewer) + "% duration)");
            if (bb != null) {
                out.add("&7Brewed with &f" + bb.name + "&7:");
                for (String w : Text.wrap(brewEffectText(bb), 34)) out.add("  " + w);
            }
            if (isSplash(def)) out.add(sp > 1 ? "&7Wide splash &8(reaches further)" : "&7Splash &8(hits everyone nearby)");
            return out;
        }
    };

    /** What a Basic Brew adds, for potion lore: its description minus the "potions with that stat" framing. */
    static String brewEffectText(EffectTable.BasicBrew bb) {
        StringBuilder sb = new StringBuilder();
        if (bb.flat != null) for (Map.Entry<String, Double> f : bb.flat.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("&a+").append(Text.commas(f.getValue())).append(' ').append(keyName(f.getKey())).append("&7");
        }
        if (bb.pct != null) for (Map.Entry<String, Double> f : bb.pct.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("&a+").append(Text.commas(f.getValue())).append("% ").append(keyName(f.getKey())).append("&7");
        }
        return sb.toString();
    }

    /** "SPEED" -> "Speed", "heal_per_sec" -> "healing", "reflect" -> "damage reflected". */
    static String keyName(String key) {
        return switch (key) {
            case "heal_per_sec" -> "healing";
            case "heal_instant" -> "instant healing";
            case "mana_per_sec" -> "mana";
            case "mana_instant" -> "instant mana";
            case "exp" -> "experience";
            case "heal_reduce" -> "healing cut";
            case "reflect" -> "damage reflected";
            default -> Text.titleCase(key.toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        };
    }

    /** The click hint above the footer. */
    static final LoreContributor HINT = new LoreContributor() {
        @Override public Section section() { return Section.BEFORE_FOOTER; }

        @Override public List<String> lines(ItemDef def, ItemData data) {
            if (def == null || !def.has("alchemy")) return List.of();
            if (isBasicBrew(def)) return List.of("&eUse it like an Awkward Potion!");
            if (isBase(def)) return List.of("&8Brewing Ingredient");
            if (isTavernDrink(def)) return List.of();
            if (isGod(def) || isSkullPotion(def)) return List.of("&eRight-click to consume!");
            if (isJyrre(def)) return List.of("&eRight-click to drink!");
            if (isRecall(def)) return List.of();
            if (isSplash(def)) return List.of("&eRight-click to throw!");
            if (isPotionDef(def)) return List.of("&eRight-click to drink!");
            return List.of();
        }
    };
}
