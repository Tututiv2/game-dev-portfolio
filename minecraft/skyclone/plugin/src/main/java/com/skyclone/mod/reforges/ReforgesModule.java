package com.skyclone.mod.reforges;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.npc.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reforging and every other "make this item better" station: the Blacksmith's random reforges, the
 * Reforge Anvil (stones, potato tomes, rarity reshaper, Art of War/Peace, master stars, enrichments),
 * essence and stars, and the Gemstone Grinder. Data comes from {@code data/reforges/} (generated).
 */
public final class ReforgesModule implements GameModule {

    /** Per-player state ({@code Sky.profiles().member(p, "reforges", PData.class)}). */
    public static final class PData {
        /** Essence type ("WITHER") -> amount held. */
        public Map<String, Long> essence = new HashMap<>();
        /** The very first blacksmith reforge costs coal instead of coins (genre tutorial). */
        public boolean firstReforgeDone;
        public int reforges;
        public int starsAdded;
        public int gemsApplied;
    }

    static ReforgeData DATA;
    static ItemMods MODS;

    // ---- dials ---------------------------------------------------------------------------------
    static Dials.Dial<Double> blacksmithCostMult, stoneCostMult, shopPriceMult, gildedChance, gildedMax, removalFeeMult, unlockCostMult;
    static Dials.Dial<Boolean> firstReforgeCoal, stoneSkillReq, shopEnabled, fillGemSlots, absorbEssence;
    static Dials.Dial<Integer> firstReforgeCoalAmount, ridiculousCooldown, anvilBlockRadius, decoySeconds;
    static Dials.Dial<Double> decoyRadius;

    @Override public String id() { return "reforges"; }

    static PData data(Player p) { return Sky.profiles().member(p, "reforges", PData.class); }

    @Override
    public void enable() {
        DATA = ReforgeData.load(f -> Sky.plugin().getResource("data/reforges/" + f));
        MODS = new ItemMods(DATA);
        // integrator (dungeons part B request 1 / reforges request 3): the essence pouch, for the dungeon essence shop
        Sky.provide(com.skyclone.api.feature.Features.Essence.class, new com.skyclone.api.feature.Features.Essence() {
            @Override public long essence(Player p, String type) { return Gear.essence(p, type); }
            @Override public boolean takeEssence(Player p, String type, long amount) { return Gear.takeEssence(p, type, amount); }
            @Override public void addEssence(Player p, String type, long amount) { Gear.addEssence(p, type, amount); }
        });

        blacksmithCostMult = Sky.dials().number("reforges.blacksmith-cost-mult", 1.0, "Multiplier on the Blacksmith's random-reforge coin cost (research 01 §13.1 table)");
        stoneCostMult = Sky.dials().number("reforges.stone-cost-mult", 1.0, "Multiplier on the coin cost of applying a reforge stone");
        firstReforgeCoal = Sky.dials().flag("reforges.first-reforge-coal", true, "A player's first Blacksmith reforge costs coal instead of coins");
        firstReforgeCoalAmount = Sky.dials().integer("reforges.first-reforge-coal-amount", 10, "Coal taken for the first Blacksmith reforge");
        stoneSkillReq = Sky.dials().flag("reforges.stone-skill-requirements", true, "Reforge stones require their Mining level to be applied");
        shopEnabled = Sky.dials().flag("reforges.stone-merchant", true, "Sell every reforge stone for coins at the Blacksmith (fallback while drops are thin)");
        shopPriceMult = Sky.dials().number("reforges.stone-merchant-price-mult", 1.0, "Multiplier on stone-merchant prices (base = bazaar snapshot, floored by rarity)");
        removalFeeMult = Sky.dials().number("reforges.gem-removal-fee-mult", 1.0, "Multiplier on the coin fee for taking a gem out of a slot");
        unlockCostMult = Sky.dials().number("reforges.gem-unlock-coin-mult", 1.0, "Multiplier on the coin part of gem slot unlock costs");
        gildedChance = Sky.dials().number("reforges.gilded-chance", 0.05, "Chance per kill that a Gilded weapon showers a nearby player with coins // TUNE: reference says 'low chance'");
        gildedMax = Sky.dials().number("reforges.gilded-max-coins", 500, "Most coins one Gilded shower pays // TUNE: reference says 'a random amount'");
        ridiculousCooldown = Sky.dials().integer("reforges.ridiculous-cooldown-seconds", 20, "Cooldown of the Ridiculous helmet's sneak effect // TUNE: matches its 20s crit penalty");
        anvilBlockRadius = Sky.dials().integer("reforges.anvil-block-radius", 4, "Right-clicking an anvil block this close to the hub reforge-anvil anchor opens the Reforge Anvil");
        fillGemSlots = Sky.dials().flag("reforges.fill-missing-gem-slots", true, "At boot, give items their reference gem slots when their def declares none");
        absorbEssence = Sky.dials().flag("reforges.absorb-essence", true, "Essence items a player gains go straight into their essence pouch");
        decoySeconds = Sky.dials().integer("reforges.spirit-decoy-seconds", 5, "How long a Spiritual bow's spirit decoy holds foes' attention // TUNE: the reference gives no duration");
        decoyRadius = Sky.dials().number("reforges.spirit-decoy-radius", 8, "Foes this close to a spirit decoy chase it instead of its summoner // TUNE");

        int items = Sky.items().loadJson("reforges", "items.json");
        for (ReforgeData.Row r : DATA.rows.values()) Sky.items().registerReforge(ReforgeData.toDef(r));
        Sky.items().addStatContributor("reforges", MODS::contribute);
        Sky.items().addLoreContributor("reforges_bonus", new BonusLore());
        Sky.log().info("[reforges] " + DATA.rows.size() + " reforges, " + items + " items, " + DATA.stars.size()
                + " star ladders, " + DATA.slots.size() + " gem layouts");

        Sky.npcs().register(NpcDef.of("reforges_blacksmith", "&fSmith Ottmar", "hub", "npc.blacksmith")
                        .title("&9&lBLACKSMITH").skin(DATA.npcSkins.get("blacksmith"), null),
                p -> Sky.npcs().talk(p, "reforges_blacksmith", data(p).reforges == 0
                        ? List.of("&fHand me a weapon, a tool or a bit of armor and I'll hammer some character into it.",
                                  "&fNever the same twice, mind. That's half the fun.")
                        : List.of("&fBack for another swing?"), () -> Menus.blacksmith(p)));
        NpcDef anvil = NpcDef.of("reforges_anvil", "&fAnvil-keeper Sigrun", "hub", "npc.reforge_anvil").title("&5&lREFORGE ANVIL")
                .skin(DATA.npcSkins.get("anvil"), null);
        anvil.hand = "ANVIL";
        anvil.lines.add("&7Stones, tomes & stars");
        Sky.npcs().register(anvil, Menus::anvil);

        // Mana Disintegrators: core reads ItemData.extra.mana_disintegrator_count itself since wave 5 (core-wave5 sec 6).
        // The cost modifier the integrator added here in wave 3 is gone: with both, 10 disintegrators cut 19%, not 10%.

        // Farmer's Almanac cap is farming's dial; looked up by key at first use (same default as its registration)
        ItemMods.almanacMax = new java.util.function.IntSupplier() {
            private Dials.Dial<Integer> dial;
            @Override public int getAsInt() {
                if (dial == null) dial = Sky.dials().integer("farming.almanac-max", 5, "Farmer's Almanacs per tool (research 08 1.4).");
                return dial.get();
            }
        };

        Bukkit.getPluginManager().registerEvents(new ModListener(), Sky.plugin());
        Bonuses bonuses = new Bonuses();
        bonuses.register();
        Bonuses.INSTANCE = bonuses;
        new StatsBook().register();
        Commands.register();
        SelfTests.register();
    }

    @Override
    public void disable() {
        if (Bonuses.INSTANCE != null) Bonuses.INSTANCE.shutdown();
    }

    @Override
    public void postEnable() {
        // One tick later: every module's postEnable has run, so "register only if absent" items
        // (materials' ENCHANTED_* family) exist before we check recipe ingredients against them.
        Bukkit.getScheduler().runTask(Sky.plugin(), this::lateWiring);
    }

    private void lateWiring() {
        // Items owned by other modules may not declare their reference gem slots; the grinder and the
        // lore row read def.gemSlots, so fill the gaps from the reference table (never overwrite).
        if (fillGemSlots.get()) {
            int filled = 0;
            for (Map.Entry<String, List<ReforgeData.Slot>> e : DATA.slots.entrySet()) {
                ItemDef d = Sky.items().def(e.getKey());
                if (d == null || (d.gemSlots != null && !d.gemSlots.isEmpty())) continue;
                if (d.gemSlots == null) d.gemSlots = new java.util.ArrayList<>();
                for (ReforgeData.Slot s : e.getValue()) d.gemSlots.add(s.type);
                filled++;
            }
            if (filled > 0) Sky.log().info("[reforges] filled reference gem slots on " + filled + " item defs");
        }
        // Recipes for items we own (stones, Roasted Potato Tome): register only those whose every
        // ingredient some module registered, so recipes.ingredients-exist stays green.
        int ok = 0;
        java.util.List<String> skipped = new java.util.ArrayList<>();
        try (java.io.InputStream in = Sky.plugin().getResource("data/reforges/recipes.json")) {
            if (in != null) {
                Recipe[] all = new com.google.gson.Gson().fromJson(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), Recipe[].class);
                for (Recipe r : all) {
                    String missing = missingIngredient(r);
                    if (missing != null) { skipped.add(r.result + "(" + missing + ")"); continue; }
                    Sky.recipes().register(r);
                    ok++;
                }
            }
        } catch (Exception e) {
            Sky.log().warning("[reforges] recipes.json: " + e);
        }
        Sky.log().info("[reforges] " + ok + " recipes registered" + (skipped.isEmpty() ? "" : "; skipped (missing ingredient): " + skipped));
    }

    /** First ingredient id of a recipe that no module registered, or null. */
    static String missingIngredient(Recipe r) {
        java.util.List<String> cells = new java.util.ArrayList<>();
        if (r.grid != null) cells.addAll(java.util.Arrays.asList(r.grid));
        if (r.inputs != null) cells.addAll(r.inputs);
        for (String c : cells) {
            if (c == null || c.isBlank()) continue;
            String id = Sky.items().exists(c) ? c : c.substring(0, Math.max(0, c.lastIndexOf(':')));
            if (id.isEmpty()) id = c;
            if (id.equals("COINS")) continue;
            if (!Sky.items().exists(id)) return id;
        }
        return null;
    }
}
