package com.skyclone.mod.dungeons.gear;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.stat.Stat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave 5 (2026-10-06, part B "not built"): the starred dungeon weapons - Starred Shadow Fury, Adaptive Blade, Last
 * Breath, Spirit Shortbow, Spirit Sceptre, Bonemerang, Felthorn Reaper, Bonzo's Staff, Ice Spray Wand - the base weapon
 * ringed by eight of its boss's fragments (NEU recipes). The weapons module registers the base weapons (they carry
 * {@code "owner": "dungeons"}) in ITS postEnable, so the copies are made one tick after boot: the live base def is
 * copied (abilities, passives in {@code extra}, gem slots...) and the reference's starred-minus-base deltas are added
 * ({@code data/dungeons/starred_weapons.json}, from {@code tools/gen/dungeons/gen_starred.py}). The display name is the
 * base's with the reference's {@code ⚚} mark. An id that is already registered is left alone.
 */
public final class StarredWeapons {

    private StarredWeapons() {}

    /** One row of starred_weapons.json. */
    public static final class Row {
        public String id, base, rarity, unlock;
        public double damage, abilityDamage;
        public Map<String, Double> stats = new LinkedHashMap<>();
        public List<String> grid;
        /** false for the non-dungeon rows (Golden Fragment gear: Midas, Daedalus); absent = a dungeon weapon. */
        public Boolean dungeon;

        public boolean isDungeon() { return dungeon == null || dungeon; }
    }

    /** Rows loaded at boot; ids registered by us (for the self-test). */
    public static final List<Row> ROWS = new ArrayList<>();
    public static final List<String> REGISTERED = new ArrayList<>();
    public static final List<String> SKIPPED = new ArrayList<>();

    public static void load() {
        ROWS.clear();
        try (InputStream in = Sky.plugin().getResource("data/dungeons/starred_weapons.json")) {
            if (in == null) return;
            List<Row> rows = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<Row>>() {}.getType());
            if (rows != null) ROWS.addAll(rows);
        } catch (Exception e) {
            Sky.log().warning("[dungeons] starred_weapons.json: " + e);
        }
    }

    /** Called one tick after boot (after every module's postEnable). */
    public static void registerAll() {
        REGISTERED.clear();
        SKIPPED.clear();
        for (Row r : ROWS) {
            if (r.id == null || r.base == null) continue;
            if (Sky.items().exists(r.id)) { SKIPPED.add(r.id + " (already registered)"); continue; }
            ItemDef base = Sky.items().def(r.base);
            if (base == null) { SKIPPED.add(r.id + " (no " + r.base + ")"); continue; }
            try {
                Sky.items().register(starred(base, r));
                if (!Sky.items().exists(r.id)) { SKIPPED.add(r.id + " (rejected by the item registry, see the log)"); continue; }
                REGISTERED.add(r.id);
            } catch (RuntimeException e) {
                SKIPPED.add(r.id + " (" + e.getMessage() + ")");
                continue;
            }
            if (r.grid != null && r.grid.size() == 9) {
                Recipe rec = new Recipe();
                rec.id = "dungeons:" + r.id + "#0";
                rec.type = Recipe.Type.CRAFTING;
                rec.result = r.id;
                rec.amount = 1;
                rec.grid = r.grid.stream().map(s -> s == null || s.isBlank() ? null : s).toArray(String[]::new);
                rec.station = "Crafting Table";
                rec.unlock = r.unlock;
                try { Sky.recipes().register(rec); } catch (RuntimeException e) { Sky.log().warning("[dungeons] recipe " + rec.id + ": " + e); }
            }
        }
        if (!ROWS.isEmpty())
            Sky.log().info("[dungeons] starred weapons: " + REGISTERED.size() + " registered" + (SKIPPED.isEmpty() ? "" : ", skipped " + SKIPPED));
    }

    /** A copy of {@code base} with the starred id, name mark, rarity and stat deltas. */
    public static ItemDef starred(ItemDef base, Row r) {
        ItemDef d = new ItemDef();
        d.id = r.id;
        d.name = "⚚ " + base.name;
        d.material = base.material;
        Rarity rar = null;
        try { if (r.rarity != null) rar = Rarity.valueOf(r.rarity); } catch (IllegalArgumentException ignored) { }
        d.rarity = rar != null ? rar : base.rarity;
        d.category = base.category;
        d.damage = base.damage + r.damage;
        d.stats = new EnumMap<>(Stat.class);
        if (base.stats != null) d.stats.putAll(base.stats);
        for (Map.Entry<String, Double> e : r.stats.entrySet()) {
            Stat s;
            try { s = Stat.valueOf(e.getKey()); } catch (IllegalArgumentException ex) { continue; }
            d.stats.merge(s, e.getValue(), Double::sum);
        }
        d.lore = new ArrayList<>(base.lore == null ? List.of() : base.lore);
        d.abilities = new ArrayList<>();
        if (base.abilities != null) for (AbilityDef a : base.abilities) {
            AbilityDef c = new AbilityDef(a.id, a.name, a.trigger, a.mana, a.cooldown, new ArrayList<>(a.lore == null ? List.of() : a.lore));
            c.damage = a.damage > 0 ? a.damage + r.abilityDamage : a.damage;
            c.scaling = a.scaling;
            d.abilities.add(c);
        }
        d.setId = base.setId;
        d.setBonusName = base.setBonusName;
        d.setBonusLore = new ArrayList<>(base.setBonusLore == null ? List.of() : base.setBonusLore);
        d.skull = base.skull;
        d.color = base.color;
        d.glow = base.glow;
        d.maxStack = base.maxStack;
        d.requirements = new ArrayList<>(base.requirements == null ? List.of() : base.requirements);
        d.npcSell = base.npcSell;
        d.dungeon = r.isDungeon();
        d.gemSlots = new ArrayList<>(base.gemSlots == null ? List.of() : base.gemSlots);
        d.tags = new HashSet<>(base.tags == null ? java.util.Set.of() : base.tags);
        d.tags.remove("museum");            // the museum lists the base weapon; the starred one is the same exhibit
        d.extra = base.extra == null ? new com.google.gson.JsonObject() : base.extra.deepCopy();
        d.extra.addProperty("starredFrom", base.id);
        return d;
    }
}
