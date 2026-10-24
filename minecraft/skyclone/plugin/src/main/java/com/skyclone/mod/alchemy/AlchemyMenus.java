package com.skyclone.mod.alchemy;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Every alchemy menu: the hub, Active Effects, the Brewing Guide, and the Alchemist's shop. */
final class AlchemyMenus {

    private AlchemyMenus() {}

    private static EffectTable t() { return AlchemyModule.table(); }
    private static EffectEngine engine() { return AlchemyModule.engine(); }

    static void backToSkyMenu(Menu m) {
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
    }

    /** A potion-bottle icon tinted with the effect's colour. */
    static ItemStack bottle(String hex, String name, List<String> lore) {
        ItemStack it = Icons.of(Material.POTION, name, lore);
        if (hex != null && it.getItemMeta() instanceof PotionMeta pm) {
            try {
                pm.setColor(Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16)));
                it.setItemMeta(pm);
            } catch (RuntimeException ignored) {}
        }
        return it;
    }

    // ---- hub -------------------------------------------------------------------------------------

    static void hub(Player p) {
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading."); return; }
        AlchemyData d = EffectEngine.data(p);
        int lvl = EffectEngine.alchemyLevel(p);
        Menu m = new Menu("Alchemy", 6).chrome();
        m.set(4, Icons.of(Material.BREWING_STAND, "&aAlchemy",
                "&7Brew potions from farm, mine and mob",
                "&7drops, then drink or throw them.", "",
                "&7Alchemy Level: &b" + lvl,
                "&7Brewer bonus: &a+" + Text.commas(lvl * AlchemyDials.BREWER_PCT.get()) + "% &7duration",
                "&7Potion Affinity: &a+" + Text.commas(engine().affinity(p)) + "%",
                "", "&7Potions brewed: &a" + Text.commas(d.potionsBrewed),
                "&7Potions drunk: &a" + Text.commas(d.potionsDrunk),
                "&7Refined Bottles of Midnight: &b+" + Math.min(d.jyrreRefined, AlchemyDials.JYRRE_REFINED_MAX.get())
                        + "✎ &7for good &8(" + Math.min(d.jyrreRefined, AlchemyDials.JYRRE_REFINED_MAX.get()) + "/"
                        + AlchemyDials.JYRRE_REFINED_MAX.get() + ")"));
        m.set(20, Icons.of(Material.BREWING_STAND, "&aYour Brewing Stand",
                "&7Water + Nether Wart + an ingredient,", "&7then glowstone, redstone or gunpowder",
                "&7to push it further.", "", AlchemyModule.stand().anyBrewing(p) ? "&6A brew is running!" : "&7Idle.", "",
                AlchemyModule.stand().portableAllowed(p) ? "&eClick to open!" : "&7Use any brewing stand to brew."),
                c -> {
                    if (AlchemyModule.stand().portableAllowed(c.player())) AlchemyModule.stand().open(c.player());
                    else Msg.error(c.player(), "Find a brewing stand (the hub's alchemy shop) to brew!");
                });
        m.set(22, Icons.of(Material.BOOK, "&aBrewing Guide", "&7Every potion, which ingredient", "&7makes which level, and what",
                "&7unlocks it.", "", "&eClick to view!"), c -> guide(c.player(), 0));
        m.set(24, bottle("#7CAFC6", "&aActive Effects", List.of("&7Effects on you right now: &a" + engine().effective(p).size(),
                "", "&eClick to view!")), c -> effects(c.player()));
        int slots = PotionBag.slots(p);
        m.set(30, Icons.of(Material.LEATHER, "&aPotion Bag", slots > 0
                        ? List.of("&7A bag that holds potions.", "", "&7Slots: &a" + slots, "", "&eClick to open!")
                        : List.of("&7A bag that holds potions.", "", "&cLocked", "&7Unlocks at &aNether Wart Collection II&7.")),
                c -> PotionBag.open(c.player()));
        m.set(32, Icons.of(Material.BLAZE_POWDER, "&aAlchemy Skill", "&7Level &b" + lvl, "", "&eClick to view!"),
                c -> Sky.service(SkillService.class).openMenu(c.player()));
        backToSkyMenu(m);
        m.open(p);
    }

    // ---- active effects ----------------------------------------------------------------------

    record Row(String effect, int level, long left, String source, boolean god) {}

    static void effects(Player p) {
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading."); return; }
        AlchemyData d = EffectEngine.data(p);
        List<Row> rows = new ArrayList<>();
        if (d.godLeft > 0) rows.add(new Row(null, 0, d.godLeft, "god", true));
        d.effects.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().left, a.getValue().left))
                .forEach(e -> rows.add(new Row(e.getKey(), e.getValue().level, e.getValue().left, e.getValue().source, false)));
        boolean paused = engine().paused(p);
        Menu menu = Sky.menus().paged("Active Effects", rows, r -> effectIcon(p, d, r, paused), pc -> {
            Row r = pc.entry();
            Player pl = pc.click().player();
            if (r.god()) { godDetail(pl); return; }
            Sky.menus().confirm(pl, "Remove effect?", List.of("&7Remove " + PotionItems.label(r.effect(), r.level()) + "&7?",
                    "&cYou will not get it back."), () -> {
                engine().remove(pl, r.effect());
                Msg.send(pl, "&7Removed " + PotionItems.label(r.effect(), r.level()) + "&7.");
                effects(pl);
            }, () -> effects(pl));
        }, page -> {
            backToSkyMenu(page);
            page.set(4, Icons.of(Material.BREWING_STAND, "&aActive Effects",
                    "&7Every potion effect on you, and how", "&7long it has left. Time only runs", "&7while you are online.",
                    "", rows.isEmpty() ? "&7No active effects." : "&7Active: &a" + rows.size(),
                    paused ? "&cPaused while you are in a dungeon." : ""));
            page.set(50, Icons.of(Material.BOOK, "&aBrewing Guide", "&eClick to view!"), c -> guide(c.player(), 0));
        }, 0);
        menu.open(p);
    }

    private static ItemStack effectIcon(Player p, AlchemyData d, Row r, boolean paused) {
        if (r.god()) {
            List<String> lore = new ArrayList<>();
            lore.add("&7Time left: &a" + Text.duration(r.left()));
            lore.add("");
            lore.add("&7Grants &d" + t().god().size() + " &7effects at once.");
            if (!d.godOff.isEmpty()) lore.add("&7Switched off: &c" + d.godOff.size());
            lore.add("");
            lore.add("&eClick to view or switch off effects!");
            ItemDef def = Sky.items().def("GOD_POTION_2");
            if (def != null && def.skull != null) return Icons.skull(def.skull, "&cGod Potion", lore);
            return bottle("#FF5555", "&cGod Potion", lore);
        }
        EffectTable.Effect e = t().effect(r.effect());
        List<String> lore = new ArrayList<>();
        if (e != null) {
            EffectTable.Level l = e.level(r.level());
            if (l != null) lore.addAll(Text.wrap(l.desc, 34));
        }
        AlchemyData.Active act = d.effects.get(r.effect());
        EffectTable.BasicBrew bb = act == null ? null : t().brew(act.brew);
        if (bb != null) {
            lore.add("");
            lore.add("&7Brewed with &f" + bb.name + "&7:");
            for (String w : Text.wrap(PotionItems.brewEffectText(bb), 32)) lore.add("  " + w);
        }
        lore.add("");
        lore.add("&7Time left: &a" + EffectTable.clock(r.left()));
        if ("dungeon".equals(r.source())) lore.add("&8From a dungeon potion.");
        else if (paused) lore.add("&cPaused in dungeons.");
        lore.add("");
        lore.add("&eClick to remove!");
        return bottle(e == null ? null : e.color, PotionItems.label(r.effect(), r.level()), lore);
    }

    static void godDetail(Player p) {
        AlchemyData d = EffectEngine.data(p);
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(t().god().entrySet());
        Sky.menus().paged("God Potion Effects", rows, x -> {
            EffectTable.Effect e = t().effect(x.getKey());
            boolean off = d.godOff.contains(x.getKey());
            List<String> lore = new ArrayList<>();
            EffectTable.Level l = e == null ? null : e.level(x.getValue());
            if (l != null) lore.addAll(Text.wrap(l.desc, 34));
            lore.add("");
            lore.add(off ? "&cSwitched off" : "&aActive");
            lore.add(off ? "&eClick to switch on!" : "&eClick to switch off!");
            return bottle(e == null ? null : e.color, PotionItems.label(x.getKey(), x.getValue()), lore);
        }, pc -> {
            AlchemyData dd = EffectEngine.data(pc.click().player());
            String id = pc.entry().getKey();
            if (!dd.godOff.remove(id)) dd.godOff.add(id);
            Sky.stats().invalidate(pc.click().player());
            godDetail(pc.click().player());
        }, page -> page.set(48, Icons.back("Active Effects"), c -> effects(c.player())), 0).open(p);
    }

    // ---- brewing guide -----------------------------------------------------------------------

    static void guide(Player p, int pageNo) {
        List<EffectTable.Effect> list = new ArrayList<>(t().effects());
        list.sort((a, b) -> Boolean.compare(b.brewable, a.brewable));
        Sky.menus().paged("Brewing Guide", list, e -> guideIcon(p, e), pc -> guideDetail(pc.click().player(), pc.entry(), pc.page()),
                page -> {
                    page.set(48, Icons.back("Alchemy"), c -> hub(c.player()));
                    page.set(4, Icons.of(Material.BREWING_STAND, "&aHow to brew",
                            "&71. &fWater Bottle &7+ &cNether Wart &7= &fAwkward Potion",
                            "&72. &fAwkward Potion &7+ an ingredient = &aPotion",
                            "&73. &eGlowstone &7family: &a+1 / +2 / +3 &7levels",
                            "&74. &cRedstone &7family: &a+8 / +16 / +40 &7minutes",
                            "&75. &7Gunpowder: splash; Enchanted: wide splash",
                            "&76. &5Fermented Spider Eye&7: corrupts the effect",
                            "", "&7Each modifier type works once per potion.",
                            "&7One brewing step takes &a" + AlchemyDials.BREW_SECONDS.get() + "s&7."));
                    page.set(50, basicBrewsIcon(), c -> basicBrews(c.player(), pageNo));
                }, pageNo).open(p);
    }

    private static ItemStack basicBrewsIcon() {
        ItemDef d = Sky.items().def("CHEAP_COFFEE");
        List<String> lore = List.of("&7Coffees, teas and sodas from the", "&7tavern Bartender. Brew with one",
                "&7instead of an &fAwkward Potion &7and", "&7the potion comes out stronger.", "", "&eClick to view!");
        return d != null && d.skull != null ? Icons.skull(d.skull, "&aBasic Brews", lore) : Icons.of(Material.POTION, "&aBasic Brews", lore);
    }

    /** Every Basic Brew: what it adds and which potions it can make. */
    static void basicBrews(Player p, int fromPage) {
        Menu m = new Menu("Basic Brews", 4).chrome();
        m.set(4, Icons.of(Material.BREWING_STAND, "&aBasic Brews",
                "&7Put one in a bottle slot instead of", "&7an &fAwkward Potion&7, then add the", "&7ingredient as usual.", "",
                "&7Each brew only makes the potions it", "&7can boost. The bonus lasts as long", "&7as the potion does."));
        int slot = 10;
        for (EffectTable.BasicBrew bb : t().brews()) {
            if (slot > 25) break;
            if (slot % 9 == 8) slot += 2;
            ItemDef d = Sky.items().def(bb.item);
            List<String> lore = new ArrayList<>();
            lore.add("&8Basic Brew");
            lore.add("");
            lore.addAll(Text.wrap("&7" + bb.desc, 34));
            lore.add("");
            List<String> names = new ArrayList<>();
            for (EffectTable.Effect e : t().brewsInto(bb)) names.add(e.name);
            lore.addAll(Text.wrap("&7Makes: &f" + String.join("&7, &f", names), 34));
            String nm = d == null ? "&f" + bb.name : d.rarity.code() + d.name;
            m.set(slot++, d != null && d.skull != null ? Icons.skull(d.skull, nm, lore) : Icons.of(Material.POTION, nm, lore));
        }
        m.set(30, Icons.back("Brewing Guide"), c -> guide(c.player(), fromPage));
        m.open(p);
    }

    /** Where the unbrewable potions come from (Brewing Guide). */
    private static final java.util.Map<String, String> SOURCES = java.util.Map.ofEntries(
            java.util.Map.entry("magic_find", "Sold by tavern bartenders."),
            java.util.Map.entry("stamina", "Sold by tavern bartenders."),
            java.util.Map.entry("great_spook", "The Dread Draught: a Dread festival reward. Right-click to drink."),
            java.util.Map.entry("jyrre", "Bottles of Midnight grow sharper while you carry them, then evolve."),
            java.util.Map.entry("rift_gravity", "A splash potion sold in the Rift."),
            java.util.Map.entry("secrets", "Dungeon loot. It keeps working inside dungeons."),
            java.util.Map.entry("wisp_ice", "A splash potion from the Crimson Isle taverns."),
            java.util.Map.entry("stinky_cheese", "A Garden reward."),
            java.util.Map.entry("harvest_harbinger", "A Garden reward."),
            java.util.Map.entry("mushed_glowy_tonic", "A fishing and Crimson Isle reward."));

    private static ItemStack guideIcon(Player p, EffectTable.Effect e) {
        List<String> lore = new ArrayList<>();
        EffectTable.Level top = e.level(e.brewable ? e.brewMax : e.maxLevel());
        if (top == null) top = e.levelAtMost(e.maxLevel());
        if (top != null) lore.addAll(Text.wrap(top.desc, 34));
        lore.add("");
        if (e.brewable) {
            lore.add("&7Brewable up to: &a" + EffectTable.roman(e.brewMax));
            String unlock = null;
            for (EffectTable.Ingredient i : t().ingredientsFor(e.id)) if (i.unlock != null) unlock = i.unlock;
            if (unlock != null) {
                boolean ok = Sky.requirements().check(p, unlock);
                lore.add(ok ? "&aRecipe unlocked" : "&cRequires " + Sky.requirements().describe(unlock));
            }
        } else {
            String src = SOURCES.get(e.id);
            if (src != null) lore.addAll(Text.wrap("&8Cannot be brewed. " + src, 34));
            else {
                lore.add("&8Cannot be brewed: found as loot,");
                lore.add("&8in shops or in the God Potion.");
            }
        }
        lore.add("");
        lore.add("&eClick for details!");
        return bottle(e.color, e.line + e.name, lore);
    }

    static void guideDetail(Player p, EffectTable.Effect e, int fromPage) {
        Menu m = new Menu(Text.strip(e.name) + " Potion", 6).chrome();
        m.set(4, bottle(e.color, e.line + e.name, List.of("&7" + Text.titleCase(e.kind) + " effect",
                e.brewable ? "&7Brewable up to &a" + EffectTable.roman(e.brewMax) : "&8Not brewable")));
        int slot = 10;
        for (EffectTable.Level l : e.levels) {
            if (slot > 43) break;
            if (slot % 9 == 8) slot += 2;
            List<String> lore = new ArrayList<>(Text.wrap(l.desc, 34));
            lore.add("");
            lore.add("&7Base duration: &a" + (l.seconds > 0 ? EffectTable.clock(l.seconds) : "instant"));
            var rr = com.skyclone.api.item.Rarity.parse(l.rarity);
            lore.add(rr.code() + "&l" + rr.display());
            if (e.brewable && l.level > e.brewMax) lore.add("&8Only from the God Potion.");
            m.set(slot++, bottle(e.color, e.label(l.level), lore));
        }
        int is = 37;
        for (EffectTable.Ingredient i : t().ingredientsFor(e.id)) {
            if (is > 43) break;
            ItemDef d = Sky.items().def(i.item);
            Material mat = Material.matchMaterial(d == null ? "BARRIER" : d.material);
            if (mat == null || !mat.isItem()) mat = Material.PAPER;
            List<String> lore = new ArrayList<>();
            lore.add("&7Add to: &f" + ("WATER".equals(i.base) ? "Water Bottle" : "Awkward Potion"));
            lore.add("&7Makes: " + e.label(Math.min(i.level, e.brewMax)));
            lore.add("&7Alchemy XP: &3" + Text.commas(i.xp) + " &7per bottle");
            if (i.unlock != null) {
                boolean ok = Sky.requirements().check(p, i.unlock);
                lore.add("");
                lore.add(ok ? "&aUnlocked" : "&cRequires " + Sky.requirements().describe(i.unlock));
            }
            String nm = d == null ? Text.titleCase(i.item) : d.rarity.code() + d.name;
            m.set(is++, Icons.of(mat, nm, lore));
        }
        for (String other : List.of("speed", "strength", "healing", "night_vision", "jump_boost", "poison")) {
            EffectTable.Effect o = t().effect(other);
            if (o != null && e.id.equals(o.corruptsTo) && is <= 43) {
                m.set(is++, Icons.of(Material.FERMENTED_SPIDER_EYE, "&5Corrupt " + o.name,
                        "&7Add a &5Fermented Spider Eye &7to a", "&7" + o.name + " potion to turn it into", "&7" + e.name + "."));
            }
        }
        for (EffectTable.BasicBrew bb : t().brews()) {
            if (is > 43 || !e.brewable || !bb.appliesTo(e)) continue;
            ItemDef d = Sky.items().def(bb.item);
            List<String> lore = new ArrayList<>();
            lore.add("&8Basic Brew");
            lore.add("");
            lore.add("&7Use it instead of an &fAwkward");
            lore.add("&fPotion &7when brewing " + e.name + ":");
            for (String w : Text.wrap(PotionItems.brewEffectText(bb), 32)) lore.add("  " + w);
            lore.add("");
            lore.add("&8Sold at the tavern Bartender.");
            String nm = d == null ? "&f" + bb.name : d.rarity.code() + d.name;
            m.set(is++, d != null && d.skull != null ? Icons.skull(d.skull, nm, lore) : Icons.of(Material.POTION, nm, lore));
        }
        m.set(48, Icons.back("Brewing Guide"), c -> guide(c.player(), fromPage));
        m.open(p);
    }

    // ---- Alchemist shop --------------------------------------------------------------------------

    record Offer(String item, int amount, double price) {}

    private static final Map<UUID, Long> lastBuy = new HashMap<>();

    static void shop(Player p) {
        List<Offer> offers = AlchemyModule.shopOffers();
        Menu m = new Menu("Alchemist", 6).chrome();
        int slot = 10;
        for (Offer o : offers) {
            if (slot % 9 == 8) slot += 2;
            if (slot > 43) break;
            if (!Sky.items().exists(o.item())) continue;
            ItemStack icon;
            try { icon = Sky.items().create(o.item(), o.amount()); } catch (RuntimeException e) { continue; }
            List<String> extra = new ArrayList<>(List.of("", "&7Cost", "&6" + Text.commas(o.price()) + " Coins", "",
                    "&eClick to trade!"));
            ItemDef d = Sky.items().def(o.item());
            if (d != null && d.effectiveMaxStack() > 1 && o.amount() == 1) extra.add("&eRight-click for 16!");
            m.set(slot++, Icons.withLore(icon, extra), c -> buy(c.player(), o, c.right() ? 16 : 1));
        }
        m.set(4, Icons.of(Material.BREWING_STAND, "&aAlchemist", "&7Bottles, Nether Wart and the common",
                "&7brewing ingredients."));
        m.set(45, Icons.of(Material.BOOK, "&aBrewing Guide", "&eClick to view!"), c -> guide(c.player(), 0));
        m.set(53, Icons.of(Material.BREWING_STAND, "&aYour Brewing Stand", "&eClick to open!"),
                c -> AlchemyModule.stand().open(c.player()));
        m.open(p);
    }

    private static void buy(Player p, Offer o, int times) {
        long now = System.currentTimeMillis();
        Long last = lastBuy.get(p.getUniqueId());
        if (last != null && now - last < 150) return;            // anti-spam
        lastBuy.put(p.getUniqueId(), now);
        ItemDef d = Sky.items().def(o.item());
        if (d == null) return;
        if (d.effectiveMaxStack() == 1) times = 1;
        int n = Math.max(1, Math.min(64, times));
        double cost = o.price() * n;
        if (!Sky.economy().withdraw(p, cost, "npc-shop: alchemist " + o.item() + " x" + (o.amount() * n))) {
            Msg.error(p, "You don't have enough coins!");
            return;
        }
        int total = o.amount() * n;
        int stack = Math.max(1, d.effectiveMaxStack());
        while (total > 0) {
            int give = Math.min(stack, total);
            Sky.loot().give(p, o.item(), give, LootSource.NPC_SHOP);
            total -= give;
        }
        Msg.send(p, "&aYou bought &f" + (o.amount() * n) + "x " + d.rarity.code() + d.name + " &afor &6"
                + Text.commas(cost) + " Coins&a!");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
    }
}
