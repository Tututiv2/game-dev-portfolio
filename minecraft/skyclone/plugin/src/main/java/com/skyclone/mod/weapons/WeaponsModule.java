package com.skyclone.mod.weapons;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Weapons: every sword, longsword, bow, wand, arrow, arrow poison and the Grappling Hook, their
 * passives and abilities, shortbow and arrow mechanics, and the Arsenal / Arrow Selector menus.
 *
 * <p>Load order: {@code items.json} (ours) in enable; {@code items_fallback.json} (slayer and dungeon
 * weapons) in postEnable, each one only if its owner did not register it. Behaviour applies only to
 * ids whose registered def carries {@code extra.src = "weapons"}.
 */
public final class WeaponsModule implements GameModule, Listener {

    static final String ID = "weapons";

    private Traits traits;
    private Buffs buffs;
    private Bows bows;
    private Passives passives;
    private Abilities abilities;
    private WeaponMenus menus;
    private QuiverArrow quiverArrow;
    private Souls souls;
    private TargetBonuses targets;
    private DungeonWeapons dungeonWeapons;
    private final Set<String> mainIds = new LinkedHashSet<>();
    private final Set<String> fallbackIds = new LinkedHashSet<>();
    private final Set<String> fallbackSkipped = new LinkedHashSet<>();
    private final List<String> recipeIds = new ArrayList<>();
    /**
     * Requirement key this module owns: {@code dungeon_catacombs:<lvl>} = Catacombs Level N, enforced only
     * inside a dungeon run (dial {@code weapons.catacombs-requirement}). Our weapons carry it instead of
     * {@code catacombs:<lvl>}, which the dungeons module enforces everywhere.
     */
    static final String DUNGEON_CATA = "dungeon_catacombs";

    @Override public String id() { return ID; }

    /** The player's weapons data, or null before ProfileReady (never touch data before that). */
    static WeaponData data(Player p) {
        try {
            if (p == null || !Sky.profiles().isReady(p)) return null;
            return Sky.profiles().member(p, ID, WeaponData.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void enable() {
        WeaponDials.register();
        registerDungeonCatacombsKey();
        mainIds.addAll(ids("items.json"));
        int n = Sky.items().loadJson(ID, "items.json");
        int r = Sky.recipes().loadJson(ID, "recipes.json");
        for (JsonElement e : array("recipes.json")) recipeIds.add(e.getAsJsonObject().get("id").getAsString());

        traits = new Traits();
        buffs = new Buffs();
        bows = new Bows(traits, buffs);
        passives = new Passives(traits, buffs, bows);
        JsonObject meta = object("meta.json");
        passives.meta(strings(meta, "woodCollections"), strings(meta, "combatCollections"), strings(meta, "miningIslands"));
        passives.register();
        targets = new TargetBonuses(traits);
        targets.register();
        passives.targets = targets;
        Sky.stats().addProvider("weapons_buffs", buffs);
        abilities = new Abilities(buffs, bows, passives);
        abilities.registerAll();
        new FallbackAbilities(abilities, buffs, bows, passives).registerAll();
        menus = new WeaponMenus(traits);
        menus.targets = targets;
        menus.passives = passives;

        var pm = Bukkit.getPluginManager();
        pm.registerEvents(this, Sky.plugin());
        pm.registerEvents(bows, Sky.plugin());
        pm.registerEvents(passives, Sky.plugin());
        pm.registerEvents(abilities, Sky.plugin());
        pm.registerEvents(new GrapplingHook(), Sky.plugin());
        quiverArrow = new QuiverArrow(traits);
        bows.quiverArrow = quiverArrow;
        pm.registerEvents(quiverArrow, Sky.plugin());
        souls = new Souls(traits);
        pm.registerEvents(new Daggers(traits, buffs), Sky.plugin());
        pm.registerEvents(souls, Sky.plugin());
        dungeonWeapons = new DungeonWeapons(traits);
        pm.registerEvents(dungeonWeapons, Sky.plugin());
        abilities.reg("raise_souls", souls.raise());
        Sky.abilities().addCostModifier("weapons_raise_souls", souls.cost());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), buffs::sweep, 600L, 600L);

        Sky.commands().register(SkyCommand.of("weapons", "Open the Arsenal: every weapon, what it does and how to get it")
                .alias("arsenal").category("Combat").playerOnly()
                .run((s, a) -> {
                    Player pl = (Player) s;
                    String sub = a.length > 0 ? a[0].toLowerCase(java.util.Locale.ROOT) : "";
                    switch (sub) {
                        case "tune" -> Teleporters.applyTuner(pl);
                        case "merge" -> Teleporters.applyMerger(pl);
                        default -> menus.arsenal(pl).open(pl);
                    }
                }));
        Sky.commands().register(SkyCommand.of("arrows", "Choose which arrows your bows use")
                .alias("arrowselect").category("Combat").playerOnly()
                .run((s, a) -> menus.arrows((Player) s).open((Player) s)));
        WeaponAdmin.register(this);
        WeaponSelfTests.register(this);
        Sky.log().info("[weapons] " + n + " weapons, " + r + " recipes, " + abilities.registered.size() + " ability handlers");
    }

    @Override
    public void postEnable() {
        registerFallbacks();
        traits.rebuild();
        Sky.log().info("[weapons] fallback weapons registered: " + fallbackIds.size() + " (" + fallbackSkipped.size()
                + " already provided by their owner); dungeon-only Catacombs requirements: " + dungeonCataCount()
                + "; weapons with behaviour: " + traits.ownedIds().size());
    }

    @Override
    public void disable() {
        Missile.removeAll();
        if (quiverArrow != null) quiverArrow.removeEverywhere();
        if (souls != null) souls.removeAll();
        if (dungeonWeapons != null) dungeonWeapons.removeAll();
        try { Sky.loot().addMagicFindModifier("weapons", null); } catch (RuntimeException ignored) { }
        if (buffs != null) buffs.thawAll();
    }

    // ---- fallback items ---------------------------------------------------------------------------

    private void registerFallbacks() {
        Gson gson = new Gson();
        for (JsonElement el : array("items_fallback.json")) {
            JsonObject o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            String owner = o.getAsJsonObject("extra").get("owner").getAsString();
            if ("slayer".equals(owner) && !WeaponDials.SLAYER_FALLBACK.get()) { fallbackSkipped.add(id); continue; }
            if ("dungeons".equals(owner) && !WeaponDials.DUNGEON_FALLBACK.get()) { fallbackSkipped.add(id); continue; }
            if (Sky.items().exists(id)) { fallbackSkipped.add(id); continue; }
            try {
                ItemDef d = gson.fromJson(o, ItemDef.class);
                Sky.items().register(d);
                if (Sky.items().exists(id)) fallbackIds.add(id);
            } catch (RuntimeException ex) {
                Sky.log().warning("[weapons] fallback item " + id + " could not be read: " + ex.getMessage());
            }
        }
        for (JsonElement el : array("recipes_fallback.json")) {
            JsonObject o = el.getAsJsonObject();
            if (!fallbackIds.contains(o.get("result").getAsString())) continue;
            try {
                Recipe rec = gson.fromJson(o, Recipe.class);
                Sky.recipes().register(rec);
                recipeIds.add(rec.id);
            } catch (RuntimeException ex) {
                Sky.log().warning("[weapons] fallback recipe for " + o.get("result") + " could not be read: " + ex.getMessage());
            }
        }
    }

    /**
     * {@code dungeon_catacombs:<lvl>}: outside a dungeon it always holds (the reference only gates dungeon gear
     * inside runs); inside one it asks the dungeons module's own {@code catacombs:<lvl>}, so that module's
     * ignore-requirements dial still works. No dungeons module = nobody is ever in a dungeon = always holds.
     */
    static void registerDungeonCatacombsKey() {
        Sky.requirements().register(DUNGEON_CATA, new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                boolean in = false;
                if (p != null && Sky.has(Features.Dungeons.class)) {
                    try { in = Sky.service(Features.Dungeons.class).inDungeon(p); } catch (RuntimeException ignored) { }
                }
                if (!WeaponMath.catacombsApplies(WeaponDials.CATACOMBS_REQUIREMENT.get(), in)) return true;
                return parts.length >= 2 && Sky.requirements().check(p, "catacombs:" + parts[1]);
            }
            @Override public String describe(String[] parts) {
                String lvl = parts.length >= 2 ? parts[1] : "?";
                String mode = WeaponDials.CATACOMBS_REQUIREMENT.get();
                if (WeaponMath.catacombsApplies(mode, false)) return "&aCatacombs Level " + lvl;
                if (!WeaponMath.catacombsApplies(mode, true)) return "&aCatacombs Level " + lvl + " &8(not enforced)";
                return "&aCatacombs Level " + lvl + " &cin dungeons";
            }
        });
    }

    // ---- data files -----------------------------------------------------------------------------

    static JsonArray array(String file) {
        JsonElement e = read(file);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    static JsonObject object(String file) {
        JsonElement e = read(file);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static JsonElement read(String file) {
        try (InputStream in = Sky.plugin().getResource("data/" + ID + "/" + file)) {
            if (in == null) { Sky.log().warning("[weapons] data/weapons/" + file + " missing from the jar"); return null; }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) { return JsonParser.parseReader(r); }
        } catch (Exception e) {
            Sky.log().warning("[weapons] could not read " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static List<String> ids(String file) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array(file)) out.add(e.getAsJsonObject().get("id").getAsString());
        return out;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) for (JsonElement e : o.getAsJsonArray(key)) out.add(e.getAsString());
        return out;
    }

    // ---- accessors for admin/self-tests ------------------------------------------------------------

    Traits traits() { return traits; }
    Buffs buffs() { return buffs; }
    Bows bows() { return bows; }
    Passives passives() { return passives; }
    Abilities abilities() { return abilities; }
    WeaponMenus menus() { return menus; }
    QuiverArrow quiverArrow() { return quiverArrow; }
    Souls souls() { return souls; }
    TargetBonuses targets() { return targets; }
    DungeonWeapons dungeonWeapons() { return dungeonWeapons; }
    Set<String> mainIds() { return mainIds; }
    Set<String> fallbackIds() { return fallbackIds; }
    Set<String> fallbackSkipped() { return fallbackSkipped; }
    List<String> recipeIds() { return recipeIds; }
    /** Our weapons gated by {@link #DUNGEON_CATA}. */
    int dungeonCataCount() {
        int n = 0;
        for (ItemDef d : Sky.items().all()) {
            if (!Traits.isOurs(d)) continue;
            for (String r : d.requirements) if (r.startsWith(DUNGEON_CATA + ":")) { n++; break; }
        }
        return n;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { if (buffs != null) buffs.forget(e.getPlayer().getUniqueId()); }
}
