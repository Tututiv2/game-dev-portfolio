package com.skyclone.mod.alchemy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Alchemy: brewing (a personal brewing stand behind every brewing stand block and /brew), every potion
 * of research 08 at every level, modifiers and splash, drinking with timed effects that tick only
 * while online, the God Potion, dungeon potions, the Potion Bag, /effects, Alchemy XP, and the
 * hub Alchemist. Provides {@link Features.Potions} and the "potions" StatProvider.
 */
public final class AlchemyModule implements GameModule {

    static final String NPC_ID = "alchemy_alchemist";

    private static volatile EffectTable TABLE;
    private static volatile EffectEngine ENGINE;
    private static volatile BrewingStand STAND;
    private static volatile List<AlchemyMenus.Offer> SHOP = List.of();

    static EffectTable table() {
        EffectTable t = TABLE;
        if (t == null) throw new IllegalStateException("alchemy table not loaded");
        return t;
    }

    static EffectTable tableOrNull() { return TABLE; }
    static EffectEngine engine() { return ENGINE; }
    static BrewingStand stand() { return STAND; }
    static List<AlchemyMenus.Offer> shopOffers() { return SHOP; }

    @Override public String id() { return "alchemy"; }

    @Override
    public void enable() {
        AlchemyDials.register();
        TABLE = loadTable();
        SHOP = loadShop();
        int items = Sky.items().loadJson("alchemy", "items.json");
        int recipes = Sky.recipes().loadJson("alchemy", "recipes.json");
        Sky.items().addLoreContributor("alchemy_effects", PotionItems.EFFECTS);
        Sky.items().addLoreContributor("alchemy_hint", PotionItems.HINT);

        EffectEngine engine = new EffectEngine(TABLE);
        ENGINE = engine;
        BrewingStand stand = new BrewingStand(TABLE);
        STAND = stand;
        Sky.provide(Features.Potions.class, engine);
        Sky.stats().addProvider("potions", engine::contribute);
        Sky.combat().addModifier("alchemy_potions", 350, engine::modify);
        Bukkit.getPluginManager().registerEvents(engine, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(stand, Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            engine.tickAll();
            stand.tickAll();
        }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), engine::ageJyrre, 1200L, 1200L);   // Midnight Wit grows once a minute

        if (AlchemyDials.NPC_ENABLED.get()) {
            Sky.npcs().register(NpcDef.of(NPC_ID, "&fAlchemist Wendel", "hub", "npc.alchemist").title("&eALCHEMIST"),
                    this::clickAlchemist);
        }

        registerCommands();
        AlchemyAdmin.register(TABLE);
        AlchemySelfTests.register(TABLE);
        Sky.sidebar().tabWidget("alchemy", 160, this::tabLines);
        Sky.log().info("[alchemy] " + TABLE.effects().size() + " effects, " + items + " items, " + recipes
                + " brewing recipes, " + SHOP.size() + " shop offers");
    }

    @Override
    public void postEnable() {
        List<String> missing = new ArrayList<>();
        for (EffectTable.Ingredient i : TABLE.ingredients()) if (!Sky.items().exists(i.item)) missing.add(i.item);
        for (EffectTable.Modifier m : TABLE.modifiers()) if (!Sky.items().exists(m.item)) missing.add(m.item);
        if (!missing.isEmpty()) Sky.log().warning("[alchemy] brewing items not registered by any module: " + missing);
    }

    private void clickAlchemist(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        AlchemyData d = EffectEngine.data(p);
        if (d.metAlchemist) { AlchemyMenus.shop(p); return; }
        d.metAlchemist = true;
        Sky.npcs().talk(p, NPC_ID, List.of(
                "Ah, a new face! Every potion starts the same way: water and a pinch of Nether Wart.",
                "Then add sugar for speed, blaze powder for strength... the enchanted stuff brews far stronger.",
                "Glowstone raises the level, redstone stretches the time, gunpowder makes it throwable.",
                "And a tip: the Bartender's coffee works in place of an Awkward Potion. Coffee and sugar makes a faster Speed potion.",
                "Use my brewing stand whenever you like. And buy something, would you?"), () -> AlchemyMenus.shop(p));
    }

    private List<String> tabLines(Player p) {
        if (!AlchemyDials.TAB_WIDGET.get() || !Sky.profiles().isReady(p)) return List.of();
        if ("false".equals(setting(p, "tab.effects"))) return List.of();     // Settings -> Display (menus owns the menu)
        AlchemyData d = EffectEngine.data(p);
        if (d.effects.isEmpty() && d.godLeft <= 0) return List.of();
        List<String> out = new ArrayList<>();
        out.add("&e&lActive Effects: &f" + (d.effects.size() + (d.godLeft > 0 ? 1 : 0)));
        if (d.godLeft > 0) out.add(" &cGod Potion &f" + com.skyclone.api.ui.Text.duration(d.godLeft));
        d.effects.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().left, a.getValue().left))
                .limit(d.godLeft > 0 ? 2 : 3)
                .forEach(e -> out.add(" " + PotionItems.label(e.getKey(), e.getValue().level) + " &f"
                        + EffectTable.clock(e.getValue().left)));
        return out;
    }

    /** A per-player setting from the menus module's PDC mirror ({@code skyclone:setting.<key>}), or null. */
    static String setting(Player p, String key) {
        try {
            return p.getPersistentDataContainer().get(new org.bukkit.NamespacedKey("skyclone", "setting." + key),
                    org.bukkit.persistence.PersistentDataType.STRING);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("alchemy", "Open the Alchemy menu (brewing, effects, potion bag)")
                .category("Alchemy").playerOnly().run((s, a) -> AlchemyMenus.hub((Player) s)));
        Sky.commands().register(SkyCommand.of("effects", "Show your active potion effects")
                .alias("activeeffects").category("Alchemy").playerOnly().run((s, a) -> AlchemyMenus.effects((Player) s)));
        Sky.commands().register(SkyCommand.of("potionbag", "Open your Potion Bag")
                .alias("pbag").category("Alchemy").playerOnly().run((s, a) -> PotionBag.open((Player) s)));
        Sky.commands().register(SkyCommand.of("brew", "Open your portable brewing stand")
                .category("Alchemy").playerOnly().run((s, a) -> {
                    Player p = (Player) s;
                    if (!STAND.portableAllowed(p)) {
                        Msg.error(p, "Find a brewing stand (the hub's alchemy shop) to brew!");
                        return;
                    }
                    STAND.open(p);
                }));
        Sky.commands().register(SkyCommand.of("brewguide", "Every potion and how to brew it")
                .alias("potionguide").category("Alchemy").playerOnly().run((s, a) -> AlchemyMenus.guide((Player) s, 0)));
    }

    // ---- data loading --------------------------------------------------------------------------

    private static EffectTable loadTable() {
        try (InputStream in = Sky.plugin().getResource("data/alchemy/effects.json")) {
            if (in == null) throw new IllegalStateException("data/alchemy/effects.json missing from the jar");
            EffectTable t = EffectTable.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            String bad = t.validate();
            if (bad != null) Sky.log().warning("[alchemy] effect table problem: " + bad);
            return t;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read data/alchemy/effects.json", e);
        }
    }

    private static List<AlchemyMenus.Offer> loadShop() {
        List<AlchemyMenus.Offer> out = new ArrayList<>();
        try (InputStream in = Sky.plugin().getResource("data/alchemy/shop.json")) {
            if (in == null) return out;
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                out.add(new AlchemyMenus.Offer(o.get("item").getAsString(),
                        o.has("amount") ? o.get("amount").getAsInt() : 1, o.get("price").getAsDouble()));
            }
        } catch (Exception e) {
            Sky.log().warning("[alchemy] could not read shop.json: " + e);
        }
        return List.copyOf(out);
    }
}
