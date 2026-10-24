package com.skyclone.mod.alchemy;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Live checks ({@code /sb selftest alchemy}). {@link #researchNumbers} and {@link #brewChain} are
 * PURE so the unit tests run exactly the same assertions.
 */
final class AlchemySelfTests {

    private AlchemySelfTests() {}

    static void register(EffectTable t) {
        Sky.commands().selfTest("alchemy.table-valid", t::validate);
        Sky.commands().selfTest("alchemy.research-numbers", () -> researchNumbers(t));
        Sky.commands().selfTest("alchemy.brew-chain", () -> brewChain(t));
        Sky.commands().selfTest("alchemy.basic-brews", () -> basicBrews(t));
        Sky.commands().selfTest("alchemy.items-exist", () -> itemsExist(t));
        Sky.commands().selfTest("alchemy.potion-lore-renders", () -> loreRenders(t));
        Sky.commands().selfTest("alchemy.unlock-keys", () -> unlockKeys(t));
        Sky.commands().selfTest("alchemy.hub-anchors", AlchemySelfTests::anchors);
        Sky.commands().selfTest("alchemy.special-potions", () -> specialPotions(t));
        Sky.commands().selfTest("alchemy.stand-bookkeeping", AlchemySelfTests::standBookkeeping);
        Sky.commands().selfTest("alchemy.special-items-behave", () -> specialItemsBehave(t));
    }

    /** The special potions' items (wave 5). */
    static final List<String> SPECIAL_ITEMS = List.of("GREAT_SPOOK_POTION", "BOTTLE_OF_JYRRE", "NEW_BOTTLE_OF_JYRRE",
            "REFINED_BOTTLE_OF_JYRRE", "RECALL_POTION", "POTION_SECRETS;1", "POTION_RIFT_GRAVITY;1", "POTION_WISP_ICE;1",
            "POTION_STINKY_CHEESE;1", "POTION_MUSHED_GLOWY_TONIC;1");

    /** Special potions (pure): NEU numbers, how each one is drunk, Midnight Wit growth and scaling. */
    static String specialPotions(EffectTable t) {
        EffectTable.Effect spook = t.effect("great_spook");
        if (spook == null || spook.level(1) == null) return "great_spook missing";
        if (spook.level(1).seconds != 86400 || spook.level(1).param("fear") != 3) return "Dread Draught must be +3 Fear for 24 hours (NEU)";
        if (!"GREAT_SPOOK_POTION".equals(spook.itemId(1))) return "great_spook's item should be GREAT_SPOOK_POTION";
        EffectTable.Effect wit = t.effect("jyrre");
        if (wit == null || !wit.scaled) return "jyrre must be a scaled effect";
        if (wit.scale(120) != 120 || wit.level(1).stat("INTELLIGENCE") != 1) return "Midnight Wit +120 must give +120 Intelligence";
        if (!wit.label(120).endsWith("+120")) return "scaled label should read +120, got " + wit.label(120);
        if (PotionItems.jyrreBonus(0, 10, 300) != 0 || PotionItems.jyrreBonus(125, 10, 300) != 12
                || PotionItems.jyrreBonus(99_999, 10, 300) != 300) return "Midnight Wit growth: minutes/rate, capped at 300";
        EffectTable.Effect grav = t.effect("rift_gravity");
        if (grav == null || !grav.splashOnly || grav.level(1).seconds != 600) return "Gravity is a 10:00 splash potion (NEU)";
        EffectTable.Effect sec = t.effect("secrets");
        if (sec == null || sec.brewable || sec.level(1).seconds <= 0) return "Secret potion missing or brewable";
        EffectTable.Effect wisp = t.effect("wisp_ice");
        if (wisp == null || wisp.level(1).stat("TRUE_DEFENSE") != 25 || wisp.level(1).stat("HEALTH_REGEN") != 10)
            return "Chilled Spirit Water must be +10 Health Regen +25 True Defense (NEU)";
        for (String id : List.of("great_spook", "jyrre", "rift_gravity", "secrets", "wisp_ice", "stinky_cheese",
                "mushed_glowy_tonic", "harvest_harbinger")) {
            EffectTable.Effect e = t.effect(id);
            if (e == null) return "missing special effect " + id;
            if (e.brewable) return id + " must not be brewable";
            if (Brew.react(t, Brew.Bottle.potion(id, e.minLevel()), "GLOWSTONE_DUST") != null
                    || Brew.react(t, Brew.Bottle.potion(id, e.minLevel()), "REDSTONE") != null
                    || Brew.react(t, Brew.Bottle.potion(id, e.minLevel()), "SULPHUR") != null)
                return id + " reacts with a modifier at the stand";
        }
        if (EffectEngine.woundedHealth(100, 140, 25) != 130) return "Wounded mob: a +40 heal at 25% should keep +30";
        if (EffectEngine.woundedHealth(100, 80, 25) != 80) return "Wounded mob: damage must never be touched";
        return null;
    }

    /** Brewing-stand bookkeeping (pure): island keys follow the island, XP is paid once to the taker, Witch speed. */
    static String standBookkeeping() {
        String a = Stands.islandKey("private_island", 3, 70, -12, 1024);
        String b = Stands.islandKey("private_island", 1024 * 3 + 3, 70, -1024 - 12, 1024);
        if (!a.equals(b)) return "island stand key must be slot-relative: " + a + " vs " + b;
        if (a.equals(Stands.islandKey("garden", 3, 70, -12, 1024))) return "island keys must include the island";
        if (Stands.publicKey("hub", 1, 2, 3).equals(Stands.publicKey("hub", 1, 2, 4))) return "public keys must differ per block";
        AlchemyData.Stand s = new AlchemyData.Stand();
        Stands.normalise(s);
        Stands.store(s, 0, 300, "A");
        Stands.store(s, 2, 5, "C");
        if (Stands.pending(s) != 305) return "pending XP should be 305";
        if (Stands.collect(s, new String[]{"A", null, "C"}) != 0) return "untouched potions must not pay";
        if (Stands.collect(s, new String[]{null, null, "C"}) != 300) return "taking potion 1 should pay its 300 XP";
        if (Stands.collect(s, new String[]{"A", null, "C"}) != 0) return "putting it back must not pay again";
        if (Stands.collect(s, new String[]{null, null, "X"}) != 5) return "swapping potion 3 should pay its 5 XP";
        if (Stands.pending(s) != 0 || !Stands.idle(s)) return "a collected, empty, idle stand is idle";
        if (Stands.sig("abc") == null || Stands.sig("abc").equals(Stands.sig("abd")) || Stands.sig("") != null) return "sig()";
        if (Stands.brewMillis(20, 0) != 20_000 || Stands.brewMillis(20, 100) != 10_000) return "Witch Quick Brew +100% = half the time";
        AlchemyData legacy = new AlchemyData();
        legacy.stand = new ArrayList<>(List.of("ing", "b1", "", ""));
        legacy.brewStart = 42;
        legacy.brewIngredient = "SUGAR";
        AlchemyData.Stand port = Stands.portable(legacy);
        if (!"b1".equals(port.slots.get(1)) || port.brewStart != 42 || !legacy.stand.isEmpty() || legacy.brewStart != 0)
            return "the pre-wave-5 personal stand must migrate into the portable stand";
        if (Stands.portable(legacy) != port) return "migration must happen once";
        return null;
    }

    /** Runtime: the special items really are what the drinking code expects. */
    static String specialItemsBehave(EffectTable t) {
        ItemDef spook = Sky.items().def("GREAT_SPOOK_POTION");
        if (!PotionItems.isSkullPotion(spook)) return "GREAT_SPOOK_POTION is not a skull potion";
        ItemStack sp = PotionItems.create("GREAT_SPOOK_POTION");
        if (sp == null || PotionItems.bottleOf(sp) != null) return "a skull potion must not be a brewing bottle";
        ItemDef wit = Sky.items().def("NEW_BOTTLE_OF_JYRRE");
        if (!PotionItems.isJyrre(wit)) return "NEW_BOTTLE_OF_JYRRE is not a Midnight Wit bottle";
        ItemStack w = PotionItems.create("NEW_BOTTLE_OF_JYRRE");
        if (w == null) return "cannot create NEW_BOTTLE_OF_JYRRE";
        if (PotionItems.jyrreBonus(wit, Sky.items().data(w)) != 0) return "a fresh bottle has no bonus";
        int per = Math.max(1, AlchemyDials.JYRRE_MINUTES_PER_INT.get());
        Sky.items().update(w, d -> d.extra.addProperty("jmin", 50L * per));
        if (PotionItems.jyrreBonus(wit, Sky.items().data(w)) != 50) return "50 x rate minutes should be +50 Intelligence";
        if (PotionItems.jyrreBonus(Sky.items().def("REFINED_BOTTLE_OF_JYRRE"), null) != 300) return "a Refined bottle is always +300";
        if (!"REFINED_BOTTLE_OF_JYRRE".equals(PotionItems.str(wit.extra, "evolves"))) return "the bottle must evolve into the Refined one";
        ItemDef sec = Sky.items().def("POTION_SECRETS;1");
        if (sec == null || !"dungeon".equals(PotionItems.str(sec.extra, "source"))) return "Secret potion must tick as a dungeon potion";
        ItemStack grav = PotionItems.create("POTION_RIFT_GRAVITY;1");
        Brew.Bottle gb = grav == null ? null : PotionItems.bottleOf(grav);
        if (gb == null || gb.splash() <= 0) return "Gravity must be a thrown splash potion";
        if (!PotionItems.isRecall(Sky.items().def("RECALL_POTION"))) return "RECALL_POTION is not alchemy's Recall Potion";
        return null;
    }

    // ---- pure ------------------------------------------------------------------------------------

    private static String expect(String what, double got, double want) {
        return Math.abs(got - want) > 1e-6 ? what + ": got " + got + ", research says " + want : null;
    }

    private static String stat(EffectTable t, String eff, int lvl, String stat, double want) {
        EffectTable.Effect e = t.effect(eff);
        if (e == null) return "missing effect " + eff;
        EffectTable.Level l = e.level(lvl);
        if (l == null) return "missing " + eff + " " + lvl;
        return expect(eff + " " + lvl + " " + stat, l.stat(stat), want);
    }

    private static String param(EffectTable t, String eff, int lvl, String p, double want) {
        EffectTable.Effect e = t.effect(eff);
        EffectTable.Level l = e == null ? null : e.level(lvl);
        if (l == null) return "missing " + eff + " " + lvl;
        return expect(eff + " " + lvl + " " + p, l.param(p), want);
    }

    /** Numbers from research 08 section 2.3 and the NEU item files. */
    static String researchNumbers(EffectTable t) {
        String r;
        if ((r = stat(t, "speed", 1, "SPEED", 5)) != null) return r;
        if ((r = stat(t, "speed", 8, "SPEED", 40)) != null) return r;
        if ((r = stat(t, "strength", 1, "STRENGTH", 5)) != null) return r;
        if ((r = stat(t, "strength", 8, "STRENGTH", 75)) != null) return r;
        if ((r = stat(t, "resistance", 1, "DEFENSE", 5)) != null) return r;
        if ((r = stat(t, "resistance", 8, "DEFENSE", 60)) != null) return r;
        if ((r = stat(t, "critical", 4, "CRIT_CHANCE", 25)) != null) return r;
        if ((r = stat(t, "critical", 4, "CRIT_DAMAGE", 40)) != null) return r;
        if ((r = stat(t, "haste", 1, "MINING_SPEED", 50)) != null) return r;
        if ((r = stat(t, "true_defense", 4, "TRUE_DEFENSE", 20)) != null) return r;
        if ((r = stat(t, "alchemy_xp_boost", 3, "ALCHEMY_WISDOM", 20)) != null) return r;
        if ((r = param(t, "archery", 4, "archery", 75)) != null) return r;
        if ((r = param(t, "absorption", 8, "absorption", 300)) != null) return r;
        if ((r = param(t, "mana", 8, "mana_per_sec", 8)) != null) return r;
        if ((r = param(t, "healing", 1, "heal_per_sec", 5)) != null) return r;
        EffectTable.Effect speed = t.effect("speed");
        if (speed.level(1).seconds != 180) return "speed I lasts " + speed.level(1).seconds + "s, research says 3:00";
        if (t.effect("combat_xp_boost").level(3).seconds != 2160) return "XP Boost III should last 36:00";
        if (speed.brewMax != 8 || t.effect("regeneration").brewMax != 8) return "brewable max should be VIII";
        if (!"EPIC".equals(speed.level(8).rarity) || !"COMMON".equals(speed.level(1).rarity)) return "potion rarity I=COMMON, VIII=EPIC";
        EffectTable.Ingredient cane = t.ingredient("AWKWARD", "ENCHANTED_SUGAR_CANE");
        if (cane == null || cane.level != 5 || cane.xp != 15000) return "Enchanted Sugar Cane must brew Speed V for 15,000 XP";
        EffectTable.Ingredient rod = t.ingredient("AWKWARD", "ENCHANTED_BLAZE_ROD");
        if (rod == null || rod.xp != 23000) return "Enchanted Blaze Rod must give 23,000 XP";
        // duration: Speed I + Redstone (+8m) with Alchemy 50 (+50%) = (180 + 480) x 1.5 = 990 s
        long d = Brew.duration(t, new Brew.Bottle(Brew.Kind.POTION, "speed", 1, 480, 0, 0, 50), 0, 1);
        if (d != 990) return "Speed I + redstone at Alchemy 50 should last 990s, got " + d;
        // + Potion Affinity Talisman (+10%) -> 1089 s
        d = Brew.duration(t, new Brew.Bottle(Brew.Kind.POTION, "speed", 1, 480, 0, 0, 50), 10, 1);
        if (d != 1089) return "with the affinity talisman it should be 1089s, got " + d;
        // God Potion: 12h + 12h at Alchemy 50 = 24h; x1.2 parrot -> 28h 48m (reference lore)
        if (Brew.godSeconds(false, 24, 12, 12, 50, 1.2) != (28 * 3600 + 48 * 60)) return "God Potion at Alchemy 50 + parrot should be 28h 48m";
        if (Brew.godSeconds(true, 24, 12, 12, 0, 1.0) != 24 * 3600) return "legacy God Potion should be 24h";
        if (t.god().size() < 20) return "God Potion grants only " + t.god().size() + " effects";
        return null;
    }

    private static String step(EffectTable t, Brew.Bottle b, String item, String wantEffect, int wantLevel) {
        Brew.Result r = Brew.react(t, b, item);
        if (r == null) return "no reaction: " + AlchemyAdmin.describe(b) + " + " + item;
        if (wantEffect == null) return r.bottle().kind() == Brew.Kind.AWKWARD ? null : "expected awkward from " + item;
        if (!wantEffect.equals(r.bottle().effect()) || r.bottle().level() != wantLevel)
            return AlchemyAdmin.describe(b) + " + " + item + " gave " + AlchemyAdmin.describe(r.bottle())
                    + ", expected " + wantEffect + " " + wantLevel;
        return null;
    }

    /** The whole chain: water -> awkward -> speed -> boosted, extended, splashed, corrupted. */
    static String brewChain(EffectTable t) {
        String r;
        Brew.Bottle water = Brew.Bottle.water();
        if ((r = step(t, water, "NETHER_STALK", null, 0)) != null) return r;
        Brew.Bottle awk = Brew.Bottle.awkward();
        if ((r = step(t, awk, "SUGAR", "speed", 1)) != null) return r;
        if ((r = step(t, awk, "ENCHANTED_SUGAR", "speed", 3)) != null) return r;
        if ((r = step(t, awk, "ENCHANTED_SUGAR_CANE", "speed", 5)) != null) return r;
        if ((r = step(t, water, "FERMENTED_SPIDER_EYE", "weakness", 1)) != null) return r;
        if (Brew.react(t, water, "SUGAR") != null) return "sugar must not react with plain water";
        Brew.Bottle s1 = Brew.react(t, awk, "SUGAR").bottle();
        Brew.Bottle s2 = Brew.react(t, s1, "GLOWSTONE_DUST").bottle();
        if (s2.level() != 2 || s2.enh() != 1) return "glowstone should make Speed II";
        if (Brew.react(t, s2, "ENCHANTED_GLOWSTONE") != null) return "a second level modifier must not apply";
        Brew.Bottle ext = Brew.react(t, s2, "REDSTONE").bottle();
        if (ext.ext() != 480) return "redstone should add 8 minutes";
        if (Brew.react(t, ext, "ENCHANTED_REDSTONE_BLOCK") != null) return "a second duration modifier must not apply";
        Brew.Bottle sp = Brew.react(t, ext, "SULPHUR").bottle();
        if (sp.splash() != 1) return "gunpowder should make a splash potion";
        Brew.Result wide = Brew.react(t, sp, "ENCHANTED_GUNPOWDER");
        if (wide == null || wide.bottle().splash() != 2) return "enchanted gunpowder should widen the splash";
        Brew.Result slow = Brew.react(t, sp, "FERMENTED_SPIDER_EYE");
        if (slow == null || !"slowness".equals(slow.bottle().effect()) || slow.bottle().level() != 2 || slow.bottle().splash() != 1)
            return "fermented spider eye should corrupt splash Speed II into splash Slowness II";
        Brew.Bottle s7 = Brew.Bottle.potion("speed", 7);
        Brew.Result capped = Brew.react(t, s7, "ENCHANTED_GLOWSTONE");
        if (capped == null || capped.bottle().level() != 8) return "level modifiers cap at VIII";
        if (Brew.react(t, Brew.Bottle.potion("speed", 8), "GLOWSTONE_DUST") != null) return "Speed VIII cannot be raised";
        if (Brew.react(t, Brew.Bottle.potion("combat_xp_boost", 1), "REDSTONE") != null) return "XP boosts ignore redstone";
        Brew.Result nv = Brew.react(t, Brew.Bottle.potion("night_vision", 1), "FERMENTED_SPIDER_EYE");
        if (nv == null || !"invisibility".equals(nv.bottle().effect())) return "night vision should corrupt into invisibility";
        if (Brew.react(t, Brew.Bottle.potion("healing", 1), "REDSTONE") != null) return "healing is not extendable";
        return null;
    }

    /**
     * Basic Brews (NEU lore numbers): Cheap/Decent/Black Coffee +5/+8/+12 Speed, Tepid Green Tea +10% Defense,
     * Knockoff Cola +5% Strength, Bitter Iced Tea +20% mana, Red Thornleaf Tea brews only Resistance.
     */
    static String basicBrews(EffectTable t) {
        String[][] want = {{"CHEAP_COFFEE", "SPEED", "5", "10"}, {"DECENT_COFFEE", "SPEED", "5", "13"},
                {"BLACK_COFFEE", "SPEED", "5", "17"}, {"TEPID_GREEN_TEA", "DEFENSE", "60", "66"},
                {"KNOCKOFF_COLA", "STRENGTH", "75", "78.75"}, {"BITTER_ICE_TEA", "mana_per_sec", "8", "9.6"},
                {"PULPOUS_ORANGE_JUICE", "heal_per_sec", "100", "105"}, {"RED_THORNLEAF_TEA", "reflect", "0", "5"}};
        for (String[] w : want) {
            EffectTable.BasicBrew bb = t.brew(w[0]);
            if (bb == null) return "missing brew " + w[0];
            String r = expect(w[0] + " on " + w[1] + " " + w[2], bb.apply(w[1], Double.parseDouble(w[2])), Double.parseDouble(w[3]));
            if (r != null) return r;
        }
        if (t.brew("CHEAP_COFFEE").apply("SPEED", -20) != -20) return "a brew must never touch Slowness' negative Speed";
        // brewing: coffee + sugar = Speed I that carries the coffee, through every modifier
        Brew.Bottle coffee = Brew.Bottle.basicBrew("CHEAP_COFFEE");
        Brew.Result s1 = Brew.react(t, coffee, "SUGAR");
        if (s1 == null || !"speed".equals(s1.bottle().effect()) || !"CHEAP_COFFEE".equals(s1.bottle().brew()))
            return "Cheap Coffee + Sugar should brew a coffee Speed I";
        if (s1.xp() != 5) return "a Basic Brew pays the ingredient's normal XP";
        Brew.Bottle up = Brew.react(t, s1.bottle(), "GLOWSTONE_DUST").bottle();
        up = Brew.react(t, up, "REDSTONE").bottle();
        up = Brew.react(t, up, "SULPHUR").bottle();
        if (!"CHEAP_COFFEE".equals(up.brew()) || up.level() != 2 || up.ext() != 480 || up.splash() != 1)
            return "modifiers must keep the brew: " + up;
        if (Brew.react(t, coffee, "BLAZE_POWDER") != null) return "coffee must not brew Strength (no Speed to boost)";
        if (Brew.react(t, coffee, "NETHER_STALK") != null) return "nether wart does nothing to a Basic Brew";
        Brew.Result slow = Brew.react(t, up, "FERMENTED_SPIDER_EYE");
        if (slow == null || slow.bottle().brew() != null) return "corrupting coffee Speed into Slowness must drop the coffee";
        Brew.Bottle thorn = Brew.Bottle.basicBrew("RED_THORNLEAF_TEA");
        Brew.Result res = Brew.react(t, thorn, "CACTUS");
        if (res == null || !"resistance".equals(res.bottle().effect()) || !"RED_THORNLEAF_TEA".equals(res.bottle().brew()))
            return "Red Thornleaf Tea + Cactus should brew a thornleaf Resistance I";
        if (Brew.react(t, thorn, "SUGAR") != null) return "Red Thornleaf Tea brews Resistance only";
        Brew.Result cola = Brew.react(t, Brew.Bottle.basicBrew("KNOCKOFF_COLA"), "ENCHANTED_BLAZE_ROD");
        if (cola == null || cola.bottle().level() != 5) return "Knockoff Cola + Ench. Blaze Rod should brew Strength V";
        for (EffectTable.BasicBrew bb : t.brews()) if (t.brewsInto(bb).isEmpty()) return bb.item + " brews nothing";
        return null;
    }

    // ---- runtime -----------------------------------------------------------------------------------

    static String itemsExist(EffectTable t) {
        List<String> miss = new ArrayList<>();
        for (EffectTable.Effect e : t.effects()) for (EffectTable.Level l : e.levels) {
            if (!Sky.items().exists(e.itemId(l.level))) miss.add(e.itemId(l.level));
            if (e.brewable && l.level <= e.brewMax && !Sky.items().exists(EffectTable.potionId(e.id, l.level, true)))
                miss.add(EffectTable.potionId(e.id, l.level, true));
        }
        for (EffectTable.Ingredient i : t.ingredients()) if (!Sky.items().exists(i.item)) miss.add(i.item);
        for (EffectTable.Modifier m : t.modifiers()) if (!Sky.items().exists(m.item)) miss.add(m.item);
        for (AlchemyMenus.Offer o : AlchemyModule.shopOffers()) if (!Sky.items().exists(o.item())) miss.add(o.item());
        for (String id : List.of("POTION", "POTION_AWKWARD", "GOD_POTION", "GOD_POTION_2", "HOT_CHOCOLATE", "SCORNCLAW_BREW")) if (!Sky.items().exists(id)) miss.add(id);
        for (String id : SPECIAL_ITEMS) if (!Sky.items().exists(id)) miss.add(id);
        ItemDef refined = Sky.items().def("REFINED_BOTTLE_OF_JYRRE");
        if (refined != null && !refined.has("jyrre")) miss.add("REFINED_BOTTLE_OF_JYRRE (registered by another module without the jyrre tag)");
        for (EffectTable.BasicBrew bb : t.brews()) {
            ItemDef bd = Sky.items().def(bb.item);
            if (bd == null) miss.add(bb.item);
            else if (!bd.has("basic_brew")) miss.add(bb.item + " (registered by another module without the basic_brew tag)");
        }
        for (EffectTable.Bundle b : t.bundles("dungeon")) if (!Sky.items().exists("POTION_DUNGEON;" + b.level)) miss.add("POTION_DUNGEON;" + b.level);
        ItemDef water = Sky.items().def("POTION");
        if (water != null && !water.has("alchemy")) miss.add("POTION is not alchemy's Water Bottle (another module registered it first)");
        return miss.isEmpty() ? null : "missing: " + (miss.size() > 12 ? miss.subList(0, 12) + " ... (" + miss.size() + ")" : miss);
    }

    static String loreRenders(EffectTable t) {
        int n = 0;
        for (ItemDef d : Sky.items().all()) {
            if (!d.has("alchemy") || !d.has("potion")) continue;
            ItemStack st = Sky.items().create(d.id);
            ItemMeta m = st.getItemMeta();
            if (m == null || m.lore() == null || m.lore().size() < 3) return d.id + " renders without effect lore";
            n++;
        }
        // a modified potion round-trips through the stack
        Brew.Bottle b = new Brew.Bottle(Brew.Kind.POTION, "speed", 3, 960, 2, 2, 25);
        ItemStack st = PotionItems.itemOf(b);
        if (st == null) return "could not make a splash Speed III";
        Brew.Bottle back = PotionItems.bottleOf(st);
        if (!b.equals(back)) return "potion data did not round-trip: " + b + " -> " + back;
        ItemStack again = PotionItems.decode(PotionItems.encode(st));
        if (again == null || !b.equals(PotionItems.bottleOf(again))) return "potion did not survive serialisation (bag/stand)";
        Brew.Bottle brewed = new Brew.Bottle(Brew.Kind.POTION, "speed", 2, 480, 1, 0, 10, "BLACK_COFFEE");
        ItemStack bs = PotionItems.itemOf(brewed);
        if (bs == null || !brewed.equals(PotionItems.bottleOf(bs))) return "a Basic Brew potion did not round-trip: " + brewed;
        ItemStack coffee = PotionItems.create("CHEAP_COFFEE");
        Brew.Bottle cb = coffee == null ? null : PotionItems.bottleOf(coffee);
        if (cb == null || !cb.isBasicBrew()) return "Cheap Coffee is not read as a Basic Brew bottle";
        return n < 200 ? "only " + n + " potion items rendered" : null;
    }

    static String unlockKeys(EffectTable t) {
        boolean have = Sky.has(Features.Collections.class);
        Features.Collections col = Sky.service(Features.Collections.class);
        for (EffectTable.Ingredient i : t.ingredients()) {
            if (i.unlock == null) continue;
            if (!i.unlock.startsWith("collection:")) return "unexpected unlock key " + i.unlock;
            String rest = i.unlock.substring("collection:".length());
            int cut = rest.lastIndexOf(':');
            if (cut <= 0) return "bad unlock key " + i.unlock;
            String item = rest.substring(0, cut);
            try { Integer.parseInt(rest.substring(cut + 1)); } catch (NumberFormatException e) { return "bad tier in " + i.unlock; }
            if (have && !col.isCollectionItem(item)) return i.unlock + ": " + item + " is not a collection";
        }
        return null;
    }

    static String anchors() {
        Layout hub = Sky.worlds().layout("hub");
        if (hub == null) return "hub layout not available (is the hub island registered?)";
        List<String> miss = new ArrayList<>();
        for (String a : List.of("npc.alchemist", "npc.brewing_stand")) if (hub.anchor(a) == null) miss.add(a);
        return miss.isEmpty() ? null : "hub layout lacks " + miss + " (the Alchemist falls back to spawn; /brew still works)";
    }
}
