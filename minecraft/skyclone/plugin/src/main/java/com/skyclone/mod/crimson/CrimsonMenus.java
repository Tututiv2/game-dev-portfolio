package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Every Crimson Isle menu except the Dojo's and the armorsmith's. */
final class CrimsonMenus {

    private CrimsonMenus() {}

    private static void toSkyMenu(Menu m) {
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
    }

    // ---- /crimson ------------------------------------------------------------------------------------

    static void main(Player p) { mainMenu(p).open(p); }

    static Menu mainMenu(Player p) {
        CrimsonData d = CrimsonModule.data(p);
        Factions.ensureBoard(p, d);
        Menu m = new Menu("Crimson Isle", 6).chrome();
        m.set(4, status(p, d));
        String f = d.faction;
        m.set(20, Icons.of(f == null ? Material.WHITE_BANNER : Material.valueOf(Factions.info(f).icon), "&aFaction",
                f == null ? List.of("&7Two powers rule the isle. Pledge to", "&7one to receive its daily work.", "", "&eClick to choose a side!")
                        : List.of("&7Your faction: " + Factions.color(f) + Factions.display(f), "&7Rank: " + Factions.rank(d.rep(f)).color + Factions.rank(d.rep(f)).name,
                        "", "&eClick to view!")), c -> faction(c.player()));
        int done = (int) d.quests.stream().filter(q -> q.done).count();
        m.set(21, Icons.of(Material.WRITABLE_BOOK, "&aFaction Quests",
                f == null ? List.of("&7Pledge to a faction to receive quests.", "", "&cLocked")
                        : List.of("&7Today: &a" + done + "&7/&a" + d.quests.size() + " &7complete", "&7Resets in &e" + Text.duration(CUtil.secondsToReset(CrimsonModule.D.resetHourUtc.get())),
                        "", "&eClick to view!")), c -> quests(c.player()));
        m.set(22, Icons.of(Material.EMERALD, "&aFaction Shop",
                List.of("&7Gear and supplies, sold to members", "&7by your faction's quartermaster.", "", "&eClick to browse!")),
                c -> { if (CrimsonModule.data(c.player()).faction == null) faction(c.player()); else shop(c.player(), CrimsonModule.data(c.player()).faction, 0); });
        m.set(23, Icons.of(Material.WITHER_SKELETON_SKULL, "&cMini-bosses",
                List.of("&7Five champions haunt the isle and", "&7return a few minutes after they fall.", "", "&eClick to see who is up!")), c -> bosses(c.player()));
        m.set(24, Icons.of(Material.LEATHER_CHESTPLATE, "&eDojo",
                List.of("&7Seven trials of skill. Points earn belts.", "&7Total points: &a" + Text.commasInt(Dojo.total(d)), "", "&eClick to view!")), c -> Dojo.menu(c.player()));
        m.set(29, Icons.of(Material.HEART_OF_THE_SEA, "&5Synthesizer",
                List.of("&7Synthesizer necklaces grow stronger", "&7on &fX&7, &fY &7and &fZ &7- strange shapes the", "&7isle's beasts drop.", "",
                        "&7Hold one, then click here", "&7or type &a/synthesizer&7.", "", "&eClick to open!")),
                c -> Synthesizer.open(c.player()));
        m.set(30, Icons.of(Material.ANVIL, "&6Armor Prestige",
                List.of("&7Raise Leviathan-line armor from", "&7Basic to Hot, Burning, Fiery and Infernal.", "", "&eClick to open!")), c -> Prestige.menu(c.player(), 0));
        m.set(31, Icons.of(Material.FILLED_MAP, "&aIsle Guide", guideLore()));
        m.set(32, Icons.of(Material.MAGMA_CREAM, "&cLeviathan's Hollow",
                List.of("&7Crews gather at the north-east dock", "&7to hunt the &cMagma Leviathan&7.", "",
                        "&7Keys are sold by the faction envoys", "&7at each town gate.", "", "&eClick to open the hunts!")),
                c -> { if (!Sky.commands().run(c.player(), "kuudra")) Msg.error(c.player(), "The hunts are not running yet."); });
        List<String> belly = new java.util.ArrayList<>(Pearls.statusLore(p));
        belly.add("");
        belly.add("&eClick for directions!");
        m.set(33, Icons.of(Material.ENDER_PEARL, "&6Belly of the Beast", belly),
                c -> directions(c.player(), "crimson.belly", "the Belly of the Beast (Mystic Marsh)"));
        toSkyMenu(m);
        return m;
    }

    private static List<String> guideLore() {
        return List.of("&7Districts, roughly by compass:",
                "&8- &cStronghold &7(centre, arrival)",
                "&8- &cCrimson Fields &7(between the towns)",
                "&8- &eDrakehold &7(west, the Barbarians)",
                "&8- &dCarmine Spire &7(east, the Mages)",
                "&8- &cBlazing Volcano &7(north) + &cMagma Chamber",
                "&8- &5Mystic Marsh &7(north-west) + &6Belly of the Beast",
                "&8- &7Forgotten Skull &7+ &eDojo &7(north-east)",
                "&8- &6Burning Desert &7+ &cThe Dukedom &7(south-west)",
                "&8- &cSmoldering Tomb &7(south)",
                "&8- &cRuins of the Twin Pyres &7+ &cCourtyard &7(south-east)",
                "", "&7Lava fishing: the crater, Cinder Shore,", "&7and the desert's lava pool.");
    }

    private static ItemStack status(Player p, CrimsonData d) {
        List<String> lore = new ArrayList<>();
        if (d.faction == null) {
            lore.add("&7Faction: &cnone");
            lore.add("&7Talk to a faction leader in &eDrakehold");
            lore.add("&7or &dCarmine Spire&7, or use &a/faction&7.");
        } else {
            int rep = d.rep(d.faction);
            CrimsonTables.Rank r = Factions.rank(rep);
            int next = CrimsonRules.toNext(Factions.ranks(), rep);
            lore.add("&7Faction: " + Factions.color(d.faction) + Factions.display(d.faction));
            lore.add("&7Rank: " + r.color + r.name);
            lore.add("&7Reputation: &a" + Text.commasInt(rep));
            if (next > 0) {
                int idx = CrimsonRules.rankIndex(Factions.ranks(), rep);
                CrimsonTables.Rank nr = Factions.ranks().get(idx + 1);
                lore.add("&7Next: " + nr.color + nr.name + " &7in &a" + Text.commasInt(next));
                lore.add(CUtil.bar((double) (rep - r.rep) / Math.max(1, nr.rep - r.rep)));
            } else lore.add("&6Highest rank reached!");
            String rival = CrimsonRules.rival(d.faction);
            if (d.rep(rival) != 0) lore.add("&8" + Factions.display(rival) + " reputation: " + Text.commasInt(d.rep(rival)));
        }
        return Icons.playerHead(p.getUniqueId(), "&aYour Standing", lore);
    }

    // ---- factions ---------------------------------------------------------------------------------------

    static void faction(Player p) { factionMenu(p).open(p); }

    static Menu factionMenu(Player p) {
        CrimsonData d = CrimsonModule.data(p);
        Menu m = new Menu("Crimson Factions", 6).chrome();
        m.back("Crimson Isle", () -> mainMenu(p));
        m.set(4, status(p, d));
        int[] slots = {20, 24};
        int i = 0;
        for (String f : CrimsonRules.FACTIONS) {
            CrimsonTables.FactionInfo inf = Factions.info(f);
            int rep = d.rep(f);
            List<String> lore = new ArrayList<>();
            lore.add("&7Leader: &f" + inf.leader);
            lore.add("&7Town: &f" + inf.town);
            lore.add("&7Reputation: &a" + Text.commasInt(rep) + " &8(" + Factions.rank(rep).name + ")");
            lore.add("");
            lore.add(f.equals(CrimsonRules.MAGE) ? "&7Scholars of fire who rule from the" : "&7Raiders and smiths who hold the");
            lore.add(f.equals(CrimsonRules.MAGE) ? "&7gold-capped spires of the east." : "&7timber halls of the west.");
            lore.add("");
            if (f.equals(d.faction)) lore.add("&aYou serve this faction.");
            else if (d.faction == null) lore.add("&eClick to pledge!");
            else lore.add("&eClick to switch &8(" + Text.commasInt(CrimsonModule.D.switchFee.get()) + " coins)");
            ItemStack ic = Icons.of(Material.valueOf(inf.icon), inf.color + "&l" + inf.display.toUpperCase(), lore);
            if (f.equals(d.faction)) Icons.glow(ic);
            final String fac = f;
            m.set(slots[i++], ic, c -> { if (!fac.equals(CrimsonModule.data(c.player()).faction)) pledge(c.player(), fac); });
        }
        if (d.faction != null) {
            m.set(30, Icons.of(Material.WRITABLE_BOOK, "&aFaction Quests", List.of("&7Today's board.", "", "&eClick to view!")), c -> quests(c.player()));
            m.set(31, Icons.of(Material.EMERALD, "&aFaction Shop", List.of("&7The quartermaster's stock.", "", "&eClick to browse!")),
                    c -> shop(c.player(), CrimsonModule.data(c.player()).faction, 0));
            m.set(32, Icons.of(Material.GOLDEN_HELMET, "&aRanks", List.of("&7Every rank and what it unlocks.", "", "&eClick to view!")), c -> ranks(c.player()));
        }
        return m;
    }

    static void pledge(Player p, String faction) {
        CrimsonTables.FactionInfo inf = Factions.info(faction);
        CrimsonData d = CrimsonModule.data(p);
        List<String> desc = new ArrayList<>(List.of("&7Pledge to the " + inf.color + inf.display + "&7?", "",
                "&7Your quests, shop and the rival champion", "&7you are paid to hunt all follow this choice."));
        if (d.faction != null) {
            desc.add("");
            desc.add("&cSwitching costs &6" + Text.commasInt(CrimsonModule.D.switchFee.get()) + " coins");
            desc.add("&cand can be done once every " + CrimsonModule.D.switchCooldownDays.get() + " days.");
            desc.add("&7Reputation is kept for both factions.");
        }
        Sky.menus().confirm(p, "Pledge to the " + inf.display + "?", desc, () -> { if (Factions.pledge(p, faction)) faction(p); }, null);
    }

    static void ranks(Player p) { ranksMenu(p).open(p); }

    static Menu ranksMenu(Player p) {
        CrimsonData d = CrimsonModule.data(p);
        String f = d.faction;
        Menu m = new Menu("Faction Ranks", 4).chrome();
        m.back("Crimson Factions", () -> factionMenu(p));
        int rep = f == null ? 0 : d.rep(f);
        int slot = 11;
        for (int i = 0; i < Factions.ranks().size(); i++) {
            CrimsonTables.Rank r = Factions.ranks().get(i);
            boolean have = rep >= r.rep;
            List<String> lore = new ArrayList<>();
            lore.add("&7Needs &a" + Text.commasInt(r.rep) + " &7reputation.");
            lore.add("");
            lore.add("&7Unlocks:");
            if (i == 0) lore.add("&8- &7Daily quests, the basic Leviathan key");
            if (i == 1) { lore.add("&8- &7Harder quests"); lore.add("&8- &7Faction warp, Hot-tier hunts"); }
            if (i == 2) { lore.add("&8- &7Leviathan-line armor in the shop"); lore.add("&8- &7Smoldering Tomb warp, Burning hunts"); }
            if (i == 3) { lore.add("&8- &7Elite quests, faction weapons"); lore.add("&8- &7Fiery-tier hunts"); }
            if (i == 4) { lore.add("&8- &7The faction's finest gear"); lore.add("&8- &7Infernal-tier hunts"); }
            if (r.sbxp > 0) lore.add("&8+&b" + r.sbxp + " SkyBlock XP");
            lore.add("");
            lore.add(have ? "&aUNLOCKED" : "&cLOCKED");
            Material mat = have ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            m.set(slot++, Icons.of(mat, r.color + r.name, lore));
        }
        return m;
    }

    // ---- quests ------------------------------------------------------------------------------------------------

    static void quests(Player p) { questsMenu(p).open(p); }

    static Menu questsMenu(Player p) {
        CrimsonData d = CrimsonModule.data(p);
        if (d.faction == null) { Msg.error(p, "Pledge to a faction first."); return factionMenu(p); }
        Factions.ensureBoard(p, d);
        Menu m = new Menu("Faction Quests", 4).chrome();
        m.back("Crimson Isle", () -> mainMenu(p));
        m.set(4, Icons.of(Material.CLOCK, "&aToday's Board",
                List.of("&7Resets in &e" + Text.duration(CUtil.secondsToReset(CrimsonModule.D.resetHourUtc.get())),
                        CrimsonModule.D.dailyBonusRep.get() > 0 ? "&7Finish all " + d.quests.size() + " for &a+" + CrimsonModule.D.dailyBonusRep.get() + " &7bonus reputation." : "&7Finish quests for reputation.",
                        d.dailyBonusClaimed && CrimsonModule.D.dailyBonusRep.get() > 0 ? "&aBonus claimed!" : "")));
        int slot = 11;
        for (CrimsonData.QuestState s : d.quests) {
            CrimsonTables.Quest q = CrimsonModule.tables.quest(s.id);
            if (q == null) continue;
            m.set(slot++, questIcon(p, q, s), c -> {
                if (q.type.equals("FETCH") && !s.done) { Factions.deliver(c.player(), s, false); quests(c.player()); }
                else if (q.type.equals("DOJO") && !s.done) Dojo.menu(c.player());
            });
            if (slot == 16) slot = 20;
        }
        m.set(31, Icons.of(Material.CHEST, "&aDeliver Everything", List.of("&7Hand in every delivery quest you", "&7have the items for (inventory + sacks).", "", "&eClick to deliver!")),
                c -> { Factions.deliverAll(c.player(), false); quests(c.player()); });
        return m;
    }

    private static ItemStack questIcon(Player p, CrimsonTables.Quest q, CrimsonData.QuestState s) {
        Material mat = switch (q.type) {
            case "KILL" -> Material.IRON_SWORD;
            case "BOSS" -> Material.WITHER_SKELETON_SKULL;
            case "FETCH" -> Material.CHEST;
            case "FISH", "FISH_CREATURE" -> Material.FISHING_ROD;
            case "DOJO" -> Material.LEATHER_CHESTPLATE;
            case "KUUDRA" -> Material.MAGMA_CREAM;
            default -> Material.PAPER;
        };
        String diff = switch (q.difficulty) {
            case "easy" -> "&aEasy"; case "medium" -> "&eMedium"; case "hard" -> "&cHard"; case "legendary" -> "&6Legendary"; default -> "&5Elite";
        };
        List<String> lore = new ArrayList<>();
        lore.addAll(Text.wrap("&7" + q.desc, 34));
        lore.add("");
        lore.add("&7Difficulty: " + diff);
        lore.add("&7Progress: &a" + Text.commasInt(s.progress) + "&7/&a" + Text.commasInt(q.amount));
        lore.add(CUtil.bar((double) s.progress / Math.max(1, q.amount)));
        lore.add("");
        lore.add("&7Reward: &a+" + Factions.rewardOf(q) + " " + Factions.color(CrimsonModule.data(p).faction) + "Reputation");
        if (q.type.equals("FETCH") && !s.done) {
            lore.add("&7You have: &a" + Text.commasInt(CUtil.have(p, q.target)) + " " + CUtil.name(q.target));
            lore.add("");
            lore.add("&eClick to deliver!");
        }
        if (s.done) { lore.add(""); lore.add("&a&lCOMPLETED"); }
        ItemStack ic = Icons.of(s.done ? Material.LIME_DYE : mat, (s.done ? "&a" : "&e") + typeName(q.type), lore);
        return s.done ? Icons.glow(ic) : ic;
    }

    private static String typeName(String t) {
        return switch (t) {
            case "KILL" -> "Slay"; case "BOSS" -> "Champion Hunt"; case "FETCH" -> "Delivery";
            case "FISH" -> "Lava Fishing"; case "FISH_CREATURE" -> "Lava Beasts"; case "DOJO" -> "Dojo Trial";
            case "KUUDRA" -> "Leviathan Hunt";
            default -> Text.titleCase(t);
        };
    }

    // ---- faction shop -----------------------------------------------------------------------------------------

    static void shop(Player p, String faction, int page) {
        CrimsonData d = CrimsonModule.data(p);
        List<CrimsonTables.ShopEntry> list = new ArrayList<>();
        for (CrimsonTables.ShopEntry e : CrimsonModule.tables.shop)
            if (e.faction.equals(faction) && Sky.items().exists(e.item)) list.add(e);
        CrimsonTables.FactionInfo inf = Factions.info(faction);
        Menu menu = Sky.menus().paged(inf.display + " Quartermaster", list, e -> shopIcon(p, d, faction, e),
                pc -> buy(pc.click().player(), faction, pc.entry(), pc.page()),
                m -> m.back("Crimson Isle", () -> mainMenu(p)), page);
        menu.open(p);
    }

    private static ItemStack shopIcon(Player p, CrimsonData d, String faction, CrimsonTables.ShopEntry e) {
        ItemStack base = CUtil.icon(e.item, Material.BARRIER);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Cost");
        double coins = Factions.shopCoins(d, faction, e);
        if (coins < e.coins) lore.add("&8&m" + Text.commasInt(e.coins) + " Coins");
        lore.addAll(CUtil.costLines(p, coins, e.items));
        if (e.emissary) {
            int pct = Factions.emissaryPercent(d.rep(faction));
            lore.add(pct > 0 ? "&a-" + pct + "% &7reputation discount on the coins" : "&8More reputation lowers the coin price");
        }
        lore.add("");
        CrimsonTables.Rank need = Factions.ranks().get(Math.min(e.rank, Factions.ranks().size() - 1));
        int rep = d.rep(faction);
        boolean member = faction.equals(d.faction) || !CrimsonModule.D.shopNeedsMembership.get();
        boolean rankOk = CrimsonRules.rankIndex(Factions.ranks(), rep) >= e.rank && rep >= e.repMin;
        if (e.rank > 0) lore.add((rankOk ? "&a" : "&c") + "Requires " + need.color + need.name + (rankOk ? "&a" : "&c") + " rank");
        if (e.repMin > 0) lore.add((rep >= e.repMin ? "&a" : "&c") + "Requires " + Text.commasInt(e.repMin) + " reputation");
        if (!member) lore.add("&cOnly sold to the " + Factions.display(faction) + ".");
        lore.add(member && rankOk ? "&eClick to buy!" : "&cLocked");
        return Icons.withLore(base, lore);
    }

    /** All checks, then items, then coins (refund the items if the coins fail). */
    static void buy(Player p, String faction, CrimsonTables.ShopEntry e, int page) {
        CrimsonData d = CrimsonModule.data(p);
        if (CrimsonModule.D.shopNeedsMembership.get() && !faction.equals(d.faction)) { Msg.error(p, "Only members of the " + Factions.display(faction) + " may buy here."); return; }
        int rep = d.rep(faction);
        if (CrimsonRules.rankIndex(Factions.ranks(), rep) < e.rank || rep < e.repMin) { Msg.error(p, "Your rank is too low for that."); return; }
        final double coins = Factions.shopCoins(d, faction, e);
        if (Sky.economy().purse(p) < coins) { Msg.error(p, "You don't have enough coins!"); return; }
        for (String c : e.items) {
            Object[] cell = CrimsonRules.cell(c);
            if (CUtil.have(p, (String) cell[0]) < (int) cell[1]) { Msg.error(p, "You need " + cell[1] + "x " + Text.strip(CUtil.name((String) cell[0])) + "."); return; }
        }
        List<String> desc = new ArrayList<>(List.of("&7Buy " + CUtil.name(e.item) + "&7 for:"));
        desc.addAll(CUtil.costLines(p, coins, e.items));
        Sky.menus().confirm(p, "Confirm Purchase", desc, () -> {
            // re-validate after the confirm click
            for (String c : e.items) {
                Object[] cell = CrimsonRules.cell(c);
                if (CUtil.have(p, (String) cell[0]) < (int) cell[1]) { Msg.error(p, "You no longer have the items."); return; }
            }
            if (Sky.economy().purse(p) < coins) { Msg.error(p, "You don't have enough coins!"); return; }
            List<Object[]> taken = new ArrayList<>();
            for (String c : e.items) {
                Object[] cell = CrimsonRules.cell(c);
                int got = CUtil.take(p, (String) cell[0], (int) cell[1]);
                taken.add(new Object[]{cell[0], got});
                if (got < (int) cell[1]) { refund(p, taken); Msg.error(p, "Something moved your items - nothing was bought."); return; }
            }
            if (coins > 0 && !Sky.economy().withdraw(p, coins, "crimson: faction shop " + faction + " " + e.item)) {
                refund(p, taken);
                Msg.error(p, "You don't have enough coins!");
                return;
            }
            Sky.loot().give(p, e.item, 1, LootSource.NPC_SHOP);
            Msg.success(p, "You bought " + Text.strip(CUtil.name(e.item)) + "!");
            Sky.profiles().saveNow(p);
            shop(p, faction, page);
        }, null);
    }

    private static void refund(Player p, List<Object[]> taken) {
        for (Object[] t : taken) CUtil.refund(p, (String) t[0], (int) t[1]);
    }

    // ---- mini-bosses ---------------------------------------------------------------------------------------------

    static final String[][] BOSSES = {
            {"crimson_bladesoul", "boss.bladesoul", "Wraith Yard, east of the Stronghold"},
            {"crimson_mage_outlaw", "boss.mage_outlaw", "the Courtyard, south of Carmine Spire"},
            {"crimson_barbarian_duke", "boss.barbarian_duke", "the Dukedom, south-west of Drakehold"},
            {"crimson_ashfang", "boss.ashfang", "the Ruins of the Twin Pyres, south-east"},
            {"crimson_magma_boss", "boss.magma_boss", "the Magma Chamber, under the volcano (tunnel on its south side)"},
    };

    static void bosses(Player p) { bossesMenu(p).open(p); }

    static Menu bossesMenu(Player p) {
        Menu m = new Menu("Crimson Mini-bosses", 4).chrome();
        m.back("Crimson Isle", () -> mainMenu(p));
        int slot = 11;
        CrimsonData d = CrimsonModule.data(p);
        for (String[] b : BOSSES) {
            MobDef def = Sky.mobs().def(b[0]);
            if (def == null) continue;
            List<String> lore = new ArrayList<>();
            lore.add("&7Where: &f" + b[2]);
            lore.add("&7Health: &a" + Text.shortNum(def.health * CrimsonModule.D.bossHpScale.get()));
            lore.add("&7Level: &e" + def.level);
            lore.add("");
            LivingEntity live = Bosses.alive(b[0]);
            if (live != null) {
                lore.add("&a&lALIVE &7- " + Text.shortNum(Sky.mobs().health(live)) + "&7/" + Text.shortNum(Sky.mobs().maxHealth(live)) + "&c❤");
            } else {
                long left = Bosses.respawnLeft(b[0]);
                lore.add(left > 0 ? "&eRespawning in " + Text.duration(left) : "&7Waiting for someone to come near.");
            }
            lore.add("");
            lore.add("&7Your kills: &a" + d.bossKills.getOrDefault(b[0], 0));
            String rivalOf = rivalFaction(b[0]);
            if (rivalOf != null) lore.add("&7Champion of the " + Factions.color(rivalOf) + Factions.display(rivalOf) + "&7: their rivals pay extra.");
            lore.addAll(spoilsLore(p, d, b[0]));
            lore.add("");
            lore.add("&eClick for directions!");
            final String anchor = b[1];
            final String where = b[2];
            m.set(slot++, Icons.of(Material.WITHER_SKELETON_SKULL, "&c" + def.name, lore), c -> directions(c.player(), anchor, where));
        }
        return m;
    }

    /** What this player's faction spoils from one mini-boss would be right now (wave 5). */
    static List<String> spoilsLore(Player p, CrimsonData d, String bossId) {
        List<String> l = new ArrayList<>();
        l.add("&7Everyone who deals &c" + Text.commas(CrimsonModule.D.bossLootMinPercent.get()) + "% &7of its health");
        l.add("&7shares the loot.");
        if (d.faction == null || !CrimsonModule.D.factionSpoils.get()) return l;
        CrimsonRules.Spoils sp = CrimsonRules.spoils(CrimsonModule.tables.factions, d.faction, bossId, Factions.bossQuestOpen(p, bossId));
        if (sp.lines().isEmpty()) return l;
        l.add(Factions.color(d.faction) + Factions.display(d.faction) + " spoils"
                + (sp.champion() ? " &6(rival champion)" : "") + (sp.quest() ? " &e(on your board)" : "") + "&7:");
        for (CrimsonTables.LootLine x : sp.lines()) {
            if (x.item == null || !Sky.items().exists(x.item)) continue;
            String n = x.min == x.max ? String.valueOf(x.min) : x.min + "-" + x.max;
            l.add(" &8- &f" + n + "x " + CUtil.name(x.item) + (x.chance < 1 ? " &8(" + Text.commas(x.chance * 100) + "%)" : ""));
        }
        return l;
    }

    static String rivalFaction(String bossId) {
        for (String f : CrimsonRules.FACTIONS) {
            CrimsonTables.FactionInfo i = Factions.info(f);
            if (i != null && bossId.equals(i.rival_boss)) return CrimsonRules.rival(f);
        }
        return null;
    }

    private static void directions(Player p, String anchor, String where) {
        Location l = Sky.worlds().anchor(CrimsonModule.ISLAND, anchor);
        if (l == null) { Msg.error(p, "That champion's lair is not built yet."); return; }
        Msg.send(p, "&eHead to &f" + where + " &7(" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + ")");
        if (!CrimsonModule.onIsle(p)) Msg.send(p, "&7Travel there with &a/warp crimson_isle&7.");
        Msg.sound(p, Sound.UI_BUTTON_CLICK, 1f);
        p.closeInventory();
    }

    static ItemDef def(String id) { return Sky.items().def(id); }
}
