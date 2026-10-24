package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /combatzones, /dragon, /silkmother and their sub-pages. */
final class ZoneMenus {

    private final CombatzonesModule m;

    ZoneMenus(CombatzonesModule m) { this.m = m; }

    // =========================================================================================== main

    Menu main(Player p) {
        Menu menu = new Menu("Combat Zones", 6).chrome();
        menu.set(4, Icons.of(Material.IRON_SWORD, "&aCombat Zones",
                "&7Two islands where fighting is the", "&7whole point: the &cSpider's Den &7and",
                "&7&5The End&7. World bosses, summoned bosses", "&7and the Dragon's Nest live here."));
        boolean rain = m.bosses.raining(), storm = m.bosses.thundering();
        List<String> den = new ArrayList<>(List.of("&7A spider-infested mountain wrapped", "&7in webs and, often, rain.", "",
                "&7Mobs: &fspiders Lv2-50, flint skeletons,", "&7rain slimes, the Silkmother's keepers", "",
                "&7Weather: " + (storm ? "&9⚡ Thunderstorm" : rain ? "&9☂ Raining" : "&eClear"),
                "&7Broodmother: " + m.bosses.broodLabel(), ""));
        den.add(reqLine(p, m.dials.denRequirement.get()));
        den.add("&eClick to travel!");
        menu.set(20, Icons.of(Material.COBWEB, "&cSpider's Den", den), c -> warp(c.player(), "spiders_den"));
        List<String> end = new ArrayList<>(List.of("&7Floating end stone, zealots and the", "&7altar of the Dragon's Nest.", "",
                "&7Mobs: &fEndermen Lv42-50, Zealots,", "&7Seers, Obsidian Defenders, Voidspawn", "",
                "&7Altar: &d" + m.fight.eyesPlaced() + "&7/8 eyes", "&7Dragon: " + dragonStatus(),
                "&7Colossus: &eStage " + DragonMath.protectorStage(m.bosses.endStoneMined, m.dials.protectorPerStage.get()) + "&7/5", ""));
        end.add(reqLine(p, m.dials.endRequirement.get()));
        end.add("&eClick to travel!");
        menu.set(24, Icons.of(Material.END_STONE, "&5The End", end), c -> warp(c.player(), "the_end"));

        menu.set(29, Icons.of(Material.SPIDER_EYE, "&4Broodmother",
                "&7A world boss that returns to the", "&7&cTop of the Nest &7on a clock.", "",
                "&7Status: " + m.bosses.broodLabel(), "&7Cycle: &e" + Text.duration(m.dials.broodCycle.get()), "",
                "&eClick to view loot!"), c -> loot(c.player(), "Broodmother Loot", "den_broodmother_12", this::main).open(c.player()));
        menu.set(30, Icons.of(Material.STRING, "&dSilkmother",
                "&7Summoned in the &dSilkmother's Sanctuary&7.", "",
                "&7Lv300: &a" + m.dials.arachneCallings.get() + "x Silkmother's Calling",
                "&7Lv500: &51x Silkmother Crystal", "",
                "&7Altar: " + (m.arachne.busy() ? "&cShe is awake!" : "&e" + m.arachne.callings.size() + "&7/" + m.dials.arachneCallings.get() + " callings"), "",
                "&eClick for details!"), c -> silkmother(c.player()).open(c.player()));
        menu.set(32, Icons.of(Material.DRAGON_HEAD, "&5Ender Dragons",
                "&7Place eight &5Summoning Eyes &7in the", "&7altar of the Dragon's Nest.", "",
                "&7Altar: &d" + m.fight.eyesPlaced() + "&7/8", "&7Dragon: " + dragonStatus(), "",
                "&eClick for details!"), c -> dragon(c.player()).open(c.player()));
        menu.set(33, Icons.of(Material.IRON_BLOCK, "&eEnd Stone Colossus",
                "&7Quarry end stone in the &eProtector's", "&eField &7and the Colossus rises at stage 5.", "",
                "&7Mined: &e" + m.bosses.endStoneMined + "&7/" + (5L * m.dials.protectorPerStage.get()),
                "&7Stage: &e" + DragonMath.protectorStage(m.bosses.endStoneMined, m.dials.protectorPerStage.get()) + "&7/5", "",
                "&eClick to view loot!"), c -> loot(c.player(), "Colossus Loot", "end_stone_protector", this::main).open(c.player()));
        menu.set(38, Icons.of(Material.MAP, "&bTravel Scrolls", "&7Unlock fast travel straight to the", "&7Top of the Nest, the Sanctuary,", "&7the Dragon's Nest and the Null Tomb.", "",
                "&7Unlocked: &a" + m.data(p).scrolls.size() + "&7/4", "", "&eClick to view!"), c -> scrolls(c.player()).open(c.player()));
        // ---- wave 5: the people of the zones
        menu.set(31, Icons.of(Material.LEATHER_CHESTPLATE, "&fWanderer Tavi",
                "&7The End's lone adventurer: kill", "&7tasks for coins, and the &5Void Sword&7.", "",
                "&7Tasks done: &a" + Math.min(m.data(p).taviQuest, ZoneQuests.QUESTS.size()) + "&7/&a" + ZoneQuests.QUESTS.size(), "",
                "&eClick to view!"), c -> m.tavi.menu(c.player()).open(c.player()));
        menu.set(34, Icons.of(Material.DRAGON_BREATH, "&dKeeper Ixo",
                "&7The &5Draconic Altar &7and the", "&dDragon Essence Shop &7on the Void Ledge.", "",
                "&7Dragon Essence: &d" + Text.commasInt(m.essence.balance(p)), "",
                "&eClick to view!"), c -> m.essence.keeper(c.player()).open(c.player()));
        menu.set(39, Icons.of(Material.ZOMBIE_HEAD, "&aMob Types",
                "&7Fenn and Lark at the Den Entrance", "&7explain what each type is weak to.", "",
                "&7Types slain: &a" + m.data(p).mobTypes.size() + "&7/&a" + MobTypeCatalog.TYPES.size(), "",
                "&eClick to view!"), c -> m.mobTypes.menu(c.player()).open(c.player()));
        menu.set(40, stats(p));
        menu.set(42, Icons.of(Material.WRITABLE_BOOK, "&aBestiary", "&7Every mob of both islands is tracked", "&7in your bestiary.", "", "&eClick to open!"),
                c -> Sky.commands().run(c.player(), "bestiary"));
        menu.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        return menu;
    }

    private void warp(Player p, String warp) {
        p.closeInventory();
        Sky.worlds().warp(p, warp);
    }

    private String reqLine(Player p, String req) {
        if (req == null || req.isBlank()) return "&aNo requirement";
        return Sky.requirements().check(p, req) ? "&aUnlocked" : "&cRequires " + Sky.requirements().describe(req);
    }

    String dragonStatus() {
        return switch (m.fight.state) {
            case ACTIVE -> m.fight.variant == null ? "&cAlive" : "&" + DragonMath.readable(m.fight.variant.color()) + m.fight.variant.name() + " &cis alive!";
            case COUNTDOWN -> "&dRising...";
            default -> "&7Sleeping";
        };
    }

    private ItemStack stats(Player p) {
        CzData d = m.data(p);
        List<String> l = new ArrayList<>();
        l.add("&7Dragon kills:");
        int total = 0;
        for (DragonMath.Variant v : DragonMath.VARIANTS) {
            int k = d.dragonKills.getOrDefault(v.id(), 0);
            total += k;
            if (k > 0 || v.weight() > 0) l.add("  &" + DragonMath.readable(v.color()) + v.name() + "&7: &e" + k);
        }
        l.add("&7Total: &e" + total + " &8| &7Best placement: &e" + (d.bestPlacement == 0 ? "-" : "#" + d.bestPlacement));
        l.add("&7Best dragon damage: &e" + Text.commasInt(d.bestDragonDamage));
        l.add("&7Summoning eyes placed: &d" + d.eyesPlaced);
        l.add("");
        l.add("&7Silkmother kills: &e" + d.arachneKills.getOrDefault("300", 0) + " &7(Lv300) &e" + d.arachneKills.getOrDefault("500", 0) + " &7(Lv500)");
        l.add("&7Broodmother kills: &e" + d.broodmotherKills);
        l.add("&7Colossus fights: &e" + d.colossusKills + " &8| &7End stone mined: &e" + Text.commas(d.endStoneMined));
        l.add("&7Saved by a Remnant: &e" + d.remnantSaves);
        return Icons.of(Material.PAPER, "&aYour Combat Zone Stats", l);
    }

    // =========================================================================================== dragon

    Menu dragon(Player p) {
        Menu menu = new Menu("The Dragon's Nest", 6).chrome();
        double total = 0;
        Map<String, Double> w = m.dials.weights();
        for (double x : w.values()) total += Math.max(0, x);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        int i = 0;
        for (DragonMath.Variant v : DragonMath.VARIANTS) {
            double wt = Math.max(0, w.getOrDefault(v.id(), v.weight()));
            MobDef def = Sky.mobs().def("end_dragon_" + v.id());
            double hp = (def == null ? v.health() : def.health) * m.dials.dragonHealthScale.get();
            menu.set(slots[i++], Icons.of(wt > 0 ? Material.DRAGON_HEAD : Material.GRAY_DYE, "&" + DragonMath.readable(v.color()) + v.name() + " Dragon",
                    "&7Health: &a" + Text.shortNum(hp) + "&c❤", "&7Damage: &c" + Text.commasInt(v.damage()),
                    "&7Spawn chance: &e" + (total <= 0 ? "0" : Text.commas(Math.round(wt / total * 1000) / 10.0)) + "%",
                    "", trait(v), "", "&7Kills: &e" + m.data(p).dragonKills.getOrDefault(v.id(), 0)));
        }
        menu.set(31, Icons.of(Material.END_PORTAL_FRAME, "&5The Altar",
                "&7Eyes placed: &d" + m.fight.eyesPlaced() + "&7/8", "&7Yours: &d" + m.fight.eyesOf(p.getUniqueId()), "",
                "&7Right-click an empty frame with a", "&5Summoning Eye &7to place it. You get a", "&7Sleeping Eye back: keep it to take", "&7your eye out again before the dragon", "&7wakes.", "",
                "&7Stand on the &fbedrock pedestal &7in the", "&7middle: every frame is in reach.", "",
                "&7Status: " + dragonStatus()));
        menu.set(39, Icons.of(Material.ENDER_EYE, "&5Where do eyes come from?",
                "&7Special Zealots &8(1 in 420 Zealots)", "&7Zealot Bruisers &8(1 in 380)", "&7Voidspawn Extremists &8(rare)", "", "&7Zealots live in the Dragon's Nest."));
        menu.set(40, Icons.of(Material.CHEST, "&6Loot Quality",
                "&7When the dragon dies, everyone who", "&7placed an eye or dealt damage gets", "&7loot by &bquality&7:", "",
                "  &d+100 &7per Summoning Eye placed", "  &e+200 &71st damage, &e+175 &72nd, &e+150 &73rd",
                "  &e+125 &74th, &e+110 &75th, &e+100 &76th-8th", "", "&7You get the best drop you can afford.", "&7Nothing affordable? Dragon fragments.", "",
                "&eClick to view the loot table!"), c -> dragonLoot(c.player()).open(c.player()));
        menu.set(41, Icons.of(Material.END_CRYSTAL, "&dEnd Crystals",
                "&7Six crystals on the obsidian towers", "&7heal the dragon while they stand.", "&7Shoot or strike them to break them."));
        menu.set(43, Icons.of(Material.DRAGON_BREATH, "&dDragon Essence",
                "&7Every fighter earns &dDragon Essence", "&7by loot quality. Spend it at &dKeeper", "&dIxo &7on the Void Ledge, or turn spare", "&7dragon loot into more at the altar.", "",
                "&eClick to visit Keeper Ixo!"), c -> m.essence.keeper(c.player()).open(c.player()));
        menu.back("Combat Zones", () -> main(p));
        return menu;
    }

    private String trait(DragonMath.Variant v) {
        return switch (v.id()) {
            case "young" -> "&7Fast and fragile.";
            case "old" -> "&7Slow, with the most health.";
            case "protector" -> "&7Takes &a25% less &7damage.";
            case "strong" -> "&7Its attacks hit &c50% harder&7.";
            case "unstable" -> "&7Calls down extra lightning.";
            case "wise" -> "&7Its magic hits &ctwice &7as hard.";
            case "superior" -> "&7Every trick, and more of them.";
            default -> "&7Disabled unless an admin weights it.";
        };
    }

    Menu dragonLoot(Player p) {
        Menu menu = new Menu("Dragon Loot", 6).chrome();
        List<DragonMath.LootEntry> l = DragonMath.lootTable("superior");
        int slot = 10;
        for (DragonMath.LootEntry e : l) {
            String id = e.variantSpecific() ? e.item().replace("SUPERIOR_", "<VARIANT>_") : e.item();
            String show = e.variantSpecific() ? e.item() : e.item();
            ItemStack icon = iconOf(show, List.of("&7Weight: &b" + e.weight(),
                    e.perEyeChance() > 0 ? "&7Also needs a roll: &e" + Text.commas(e.perEyeChance() * 100) + "% &7per eye placed"
                            : e.flatChance() > 0 ? "&7Also needs a roll: &e" + Text.commas(e.flatChance() * 100) + "%" : "&7Always yours if affordable",
                    e.variantSpecific() ? "&8Piece of the matching dragon's set" : (id.equals("DRAGON_HORN") ? "&8Superior Dragon only" : "")));
            menu.set(slot, icon);
            slot = next(slot);
        }
        ItemStack frag = iconOf("SUPERIOR_FRAGMENT", List.of("&7Weight: &b" + DragonMath.FRAGMENT_WEIGHT + " &7each", "&7What you get when nothing else is affordable", "&8(the matching dragon's fragment)"));
        menu.set(slot, frag);
        menu.back("The Dragon's Nest", () -> dragon(p));
        return menu;
    }

    // =========================================================================================== silkmother

    Menu silkmother(Player p) {
        Menu menu = new Menu("Silkmother's Sanctuary", 5).chrome();
        menu.set(11, iconOf("ARACHNE_KEEPER_FRAGMENT", List.of("&7Dropped by &5Silkmother's Keepers &7in the", "&7Burrow (always), and rarely by high", "&7level spiders. Place &a" + m.dials.arachneCallings.get() + " &7at the altar", "&7to wake the &dLv300 &7Silkmother.")));
        menu.set(13, Icons.of(Material.LODESTONE, "&dThe Altar",
                "&7Callings placed: &e" + m.arachne.callings.size() + "&7/" + m.dials.arachneCallings.get(),
                "&7Status: " + (m.arachne.busy() ? "&cThe Silkmother is awake (Lv" + m.arachne.level() + ")"
                        : m.arachne.interruptedLevel > 0 ? "&dRemembers a Lv" + m.arachne.interruptedLevel + " calling: right-click to wake her" : "&aWaiting"), "",
                "&7Right-click the lodestone in the", "&7Sanctuary while holding a Calling", "&7or a Crystal."));
        menu.set(15, iconOf("ARACHNE_CRYSTAL", List.of("&7Sold by the &aCrystal Mender &7in the", "&7Sanctuary. Wakes the &cLv500 &7Silkmother.")));
        menu.set(29, Icons.of(Material.CHEST, "&aLv300 Loot", "&eClick to view!"), c -> loot(c.player(), "Silkmother Lv300 Loot", "den_arachne_300", this::silkmother).open(c.player()));
        menu.set(31, Icons.of(Material.ENDER_CHEST, "&cLv500 Loot", "&eClick to view!"), c -> loot(c.player(), "Silkmother Lv500 Loot", "den_arachne_500", this::silkmother).open(c.player()));
        menu.set(33, Icons.of(Material.SPIDER_EYE, "&5Keeper Loot", "&eClick to view!"), c -> loot(c.player(), "Keeper Loot", "den_arachnes_keeper", this::silkmother).open(c.player()));
        menu.back("Combat Zones", () -> main(p));
        return menu;
    }

    // =========================================================================================== scrolls

    static final String[][] SCROLLS = {
            {"nest", "SPIDERS_DEN_TOP_TRAVEL_SCROLL", "Top of the Nest", "Dropped by Spider's Den spiders (rare)"},
            {"sanctuary", "ARACHNE_SANCTUARY_TRAVEL_SCROLL", "Silkmother's Sanctuary", "Sold by the Spider Tamer"},
            {"drag", "DRAGON_NEST_TRAVEL_SCROLL", "Dragon's Nest", "Sold by the End Warden at the End Gate"},
            {"void", "VOID_SEPULTURE_TRAVEL_SCROLL", "Null Tomb", "Sold by the End Warden at the End Gate"}};

    Menu scrolls(Player p) {
        Menu menu = new Menu("Travel Scrolls", 4).chrome();
        int[] slots = {10, 12, 14, 16};
        CzData d = m.data(p);
        for (int i = 0; i < SCROLLS.length; i++) {
            String[] s = SCROLLS[i];
            boolean has = d.scrolls.contains(s[0]);
            List<String> lore = new ArrayList<>(List.of("&7" + s[3], "", has ? "&aUnlocked! &7/warp " + s[0] : "&cLocked"));
            if (has) lore.add("&eClick to travel!");
            menu.set(slots[i], Icons.of(has ? Material.FILLED_MAP : Material.MAP, (has ? "&a" : "&c") + s[2], lore),
                    has ? c -> warp(c.player(), s[0]) : null);
        }
        menu.back("Combat Zones", () -> main(p));
        return menu;
    }

    // =========================================================================================== loot

    Menu loot(Player p, String title, String mobId, java.util.function.Function<Player, Menu> back) {
        Menu menu = new Menu(title, 6).chrome();
        MobDef def = Sky.mobs().def(mobId);
        int slot = 10;
        if (def != null) {
            menu.set(4, Icons.of(Material.ZOMBIE_HEAD, "&c" + def.name + " &8[Lv" + def.level + "]",
                    "&7Health: &a" + Text.commasInt(def.health) + "&c❤", "&7Damage: &c" + Text.commasInt(def.damage),
                    "&7Coins: &6" + Text.commas(def.coins), "&7Combat XP: &3" + Text.commas(def.combatXp)));
            for (DropDef d : def.drops) {
                if (slot > 43) break;
                String amt = d.min == d.max ? "x" + d.min : "x" + d.min + "-" + d.max;
                menu.set(slot, iconOf(d.item, List.of("&7Amount: &e" + amt, "&7Chance: &e" + chance(d.chance))));
                slot = next(slot);
            }
        }
        menu.back(title.contains("Silk") || title.contains("Keeper") ? "Silkmother's Sanctuary" : "Combat Zones", () -> back.apply(p));
        return menu;
    }

    static String chance(double c) {
        if (c >= 0.999) return "100%";
        if (c >= 0.01) return Text.commas(Math.round(c * 1000) / 10.0) + "%";
        return "1/" + Text.commasInt(Math.round(1 / Math.max(1e-9, c)));
    }

    static int next(int slot) {
        slot++;
        if (slot % 9 == 8) slot += 2;
        return slot;
    }

    static ItemStack iconOf(String id, List<String> extra) {
        try {
            if (Sky.items().exists(id)) {
                ItemDef def = Sky.items().def(id);
                ItemStack it = Sky.items().create(id, 1);
                List<String> lore = new ArrayList<>();
                lore.add("");
                for (String s : extra) if (!s.isEmpty()) lore.add(s);
                return Icons.withLore(it, lore);
            }
        } catch (Exception ignored) {}
        return Icons.of(Material.BARRIER, "&c" + Text.titleCase(id.replace(';', ' ')), extra);
    }
}
