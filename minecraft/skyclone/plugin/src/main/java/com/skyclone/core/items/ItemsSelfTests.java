package com.skyclone.core.items;

import com.skyclone.api.Sky;
import com.skyclone.api.command.CommandService;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Live self-tests, run by /sb selftest items|recipes|stats|vitals|abilities. */
final class ItemsSelfTests {

    private ItemsSelfTests() {}

    static void register(ItemServiceImpl items, RecipeServiceImpl recipes, AbilityServiceImpl abilities) {
        CommandService c = Sky.commands();

        c.selfTest("items.vanilla-defs", () -> {
            List<String> missing = new ArrayList<>();
            for (Material m : Material.values()) {
                if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
                if (!items.exists(items.vanillaId(m))) missing.add(m.name());
            }
            return missing.isEmpty() ? null : missing.size() + " vanilla materials have no def, e.g. " + head(missing);
        });

        c.selfTest("items.create-roundtrip", () -> {
            String wheat = items.vanillaId(Material.WHEAT);
            ItemStack a = items.create(wheat, 3), b = items.create(wheat, 5);
            if (!wheat.equals(items.idOf(a))) return "idOf(create(" + wheat + ")) = " + items.idOf(a);
            if (!a.isSimilar(b)) return "two creates of " + wheat + " do not stack";
            if (!ItemDataCodec.write(items.data(a)).isEmpty()) return "a fresh stackable carries data: " + ItemDataCodec.write(items.data(a));
            ItemStack vanilla = new ItemStack(Material.WHEAT, 7);
            ItemStack conv = items.convert(vanilla);
            if (conv == null || !conv.isSimilar(a) || conv.getAmount() != 7) return "a converted vanilla WHEAT does not stack with a created one";
            String sword = items.vanillaId(Material.DIAMOND_SWORD);
            ItemStack s1 = items.create(sword), s2 = items.create(sword);
            ItemData d1 = items.data(s1), d2 = items.data(s2);
            if (d1.uuid == null || d1.uuid.equals(d2.uuid)) return "unique items do not get distinct uuids";
            if (s1.getMaxStackSize() != 1) return "a unique item stacks to " + s1.getMaxStackSize();
            items.update(s1, d -> d.potatoBooks = 3);
            if (items.data(s1).potatoBooks != 3) return "update() did not persist potatoBooks";
            if (!d1.uuid.equals(items.data(s1).uuid)) return "update() changed the uuid";
            return null;
        });

        c.selfTest("items.potato-math", () -> {
            ItemDef sw = ItemDef.of("SELFTEST_SWORD", "Test Sword", "DIAMOND_SWORD", Rarity.RARE, ItemCategory.SWORD);
            sw.damage = 35;
            ItemData d = new ItemData();
            d.potatoBooks = 10;
            ItemStatSheet sh = SheetMath.compute(sw, d, Rarity.RARE, id -> null, List.of(), null);
            if (sh.damage() != 55 || sh.damageBonus() != 20) return "sword +10 books: damage " + sh.damage() + " bonus " + sh.damageBonus() + " (want 55/20)";
            if (sh.total(Stat.STRENGTH) != 20) return "sword +10 books: strength " + sh.total(Stat.STRENGTH) + " (want 20)";
            ItemDef ch = ItemDef.of("SELFTEST_CHEST", "Test Chest", "DIAMOND_CHESTPLATE", Rarity.RARE, ItemCategory.CHESTPLATE);
            d.potatoBooks = 15;
            sh = SheetMath.compute(ch, d, Rarity.RARE, id -> null, List.of(), null);
            if (sh.total(Stat.HEALTH) != 60 || sh.total(Stat.DEFENSE) != 30) return "armour +15 books: " + sh.total(Stat.HEALTH) + "hp/" + sh.total(Stat.DEFENSE) + "def (want 60/30)";
            return null;
        });

        c.selfTest("items.lore-format", () -> {
            ItemDef sw = ItemDef.of("SELFTEST_SWORD", "Test Sword", "DIAMOND_SWORD", Rarity.LEGENDARY, ItemCategory.SWORD);
            sw.damage = 100;
            sw.stat(Stat.STRENGTH, 50);
            ItemData d = new ItemData();
            d.recombobulated = true;
            d.stars = 7;
            d.potatoBooks = 10;
            Rarity r = SheetMath.effectiveRarity(sw, d);
            if (r != Rarity.MYTHIC) return "recombobulated LEGENDARY is " + r;
            ItemStatSheet sh = SheetMath.compute(sw, d, r, id -> null, List.of(), null);
            LoreRenderer.Rendered out = LoreRenderer.render(sw, d, r, sh, new LoreRenderer.Env() {
                public com.skyclone.api.item.EnchantDef enchant(String id) { return null; }
                public com.skyclone.api.item.ReforgeDef reforge(String id) { return null; }
                public String describeRequirement(String key) { return "&a" + key; }
                public List<com.skyclone.api.item.LoreContributor> loreContributors() { return List.of(); }
                public String displayName(ItemDef def) { return def.name; }
                public void contributorFailed(Object o, Throwable t) {}
            }, 3);
            if (!out.name().equals("&dTest Sword &6✪✪✪✪✪&c➋")) return "name renders as " + out.name();
            String foot = out.lore().get(out.lore().size() - 1);
            if (!foot.equals("&d&l&ka&r &d&lMYTHIC SWORD &d&l&ka")) return "footer renders as " + foot;
            if (!out.lore().get(0).equals("&7Damage: &c+120 &e(+20)")) return "damage line renders as " + out.lore().get(0);
            if (!out.lore().get(1).equals("&7Strength: &c+70 &e(+20)")) return "strength line renders as " + out.lore().get(1);
            if (!out.lore().contains("&8This item can be reforged!")) return "missing the reforgeable hint";
            return null;
        });

        c.selfTest("recipes.ingredients-exist", () -> {
            List<String> bad = new ArrayList<>();
            int n = 0;
            for (Recipe r : recipes.all()) {
                n++;
                if (!items.exists(r.result)) bad.add(r.id + " result " + r.result);
                for (Map.Entry<String, Integer> e : recipes.ingredients(r).entrySet()) {
                    if (e.getKey().equals("COINS")) continue;
                    if (!items.exists(e.getKey())) bad.add(r.id + " needs " + e.getKey());
                }
            }
            return bad.isEmpty() ? null : bad.size() + " broken references in " + n + " recipes, e.g. " + head(bad);
        });

        c.selfTest("stats.base-and-formulas", () -> {
            StatMap m = new StatMap();
            if (m.total(Stat.HEALTH) != 100) return "base Health " + m.total(Stat.HEALTH);
            if (m.total(Stat.CRIT_DAMAGE) != 50) return "base Crit Damage " + m.total(Stat.CRIT_DAMAGE);
            m.add(Stat.STRENGTH, 100).addPercent(Stat.STRENGTH, 10);
            if (Math.abs(m.total(Stat.STRENGTH) - 110) > 1e-9) return "100 Strength +10% = " + m.total(Stat.STRENGTH);
            StatMap rift = new StatMap().set(Stat.INTELLIGENCE, 25);
            StatMap all = new StatMap().add(Stat.INTELLIGENCE, 500).addAll(rift).add(Stat.INTELLIGENCE, 50);
            if (all.total(Stat.INTELLIGENCE) != 25) return "override: Intelligence " + all.total(Stat.INTELLIGENCE) + " (want 25)";
            double regen = VitalsMath.healthRegen(100, 100, 1);
            if (Math.abs(regen - 2.5) > 1e-9) return "regen at 100hp/100HR = " + regen + " (want 2.5)";
            double mana = VitalsMath.manaRegenPerSecond(200, 0, 2);
            if (Math.abs(mana - 4) > 1e-9) return "mana regen 2% of 200 = " + mana;
            if (VitalsMath.heartScale(100, 40) != 20 || VitalsMath.heartScale(5000, 40) != 40) return "heart scale wrong";
            // armour swap keeps the fill fraction (playtest-loops: 596 -> 117 and stuck there)
            double off = VitalsMath.rescale(596, 596, 117), on = VitalsMath.rescale(off, 117, 700);
            if (off != 117 || on != 700) return "armour swap at full: 596/596 -> " + off + "/117 -> " + on + "/700 (want full both times)";
            return null;
        });

        // ---- wave 5 -----------------------------------------------------------------------------
        c.selfTest("items.name-prefix", () -> {
            String id = "__selftest_prefix";
            String wheat = items.vanillaId(Material.WHEAT);
            try {
                items.addNamePrefix(id, (def, data) -> data != null && data.extra.has("__selftest_prefix") ? "&7[Lvl 7] " : null);
                ItemStack s = items.create(wheat, 1);
                String before = plain(s);
                if (before.startsWith("[Lvl 7]")) return "prefix applied to a stack without the marker: " + before;
                items.update(s, d -> d.extra.addProperty("__selftest_prefix", true));
                String after = plain(s);
                if (!after.startsWith("[Lvl 7] ")) return "prefix missing after update: '" + after + "'";
                if (!after.endsWith(before)) return "prefix changed the rest of the name: '" + after + "' vs '" + before + "'";
                items.addNamePrefix(id, null);
                items.refresh(s);
                if (plain(s).startsWith("[Lvl 7]")) return "removing the prefix did not clear it";
                return null;
            } finally {
                items.addNamePrefix(id, null);
            }
        });

        c.selfTest("items.dupe-scan-sources", () -> {
            String id = "__selftest_dupesrc";
            try {
                items.addDupeScanSource(id, pl -> { throw new IllegalStateException("probe"); });
                if (!items.dupeSources().containsKey(id)) return "source was not registered";
                // a throwing source must not abort the scan (no player is changed: nothing is duplicated)
                new DupeScanner(items).scan(org.bukkit.Bukkit.getOnlinePlayers(), "selftest");
                items.addDupeScanSource(id, pl -> java.util.Arrays.asList(null, new ItemStack(Material.AIR)));
                new DupeScanner(items).scan(org.bukkit.Bukkit.getOnlinePlayers(), "selftest");
            } catch (RuntimeException e) {
                return "scan threw with a bad source: " + e;
            } finally {
                items.addDupeScanSource(id, null);
            }
            return items.dupeSources().containsKey(id) ? "a null source did not remove it" : null;
        });

        c.selfTest("abilities.mana-disintegrator", () -> {
            if (Math.abs(AbilityServiceImpl.disintegrated(100, 4) - 96) > 1e-9) return "4 disintegrators on 100 mana = " + AbilityServiceImpl.disintegrated(100, 4);
            if (Math.abs(AbilityServiceImpl.disintegrated(100, 25) - 90) > 1e-9) return "cap of 10 not applied";
            if (AbilityServiceImpl.disintegrated(100, -3) != 100) return "negative count changed the cost";
            return null;
        });

        c.selfTest("abilities.handlers-registered", () -> {
            List<String> missing = abilities.missingHandlers();
            return missing.isEmpty() ? null : missing.size() + " abilities on items have no handler: " + head(missing);
        });
    }

    private static String plain(ItemStack s) {
        var meta = s.getItemMeta();
        if (meta == null || meta.displayName() == null) return "";
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName());
    }

    private static String head(List<String> l) {
        return String.join(", ", l.subList(0, Math.min(6, l.size()))) + (l.size() > 6 ? ", ..." : "");
    }
}
