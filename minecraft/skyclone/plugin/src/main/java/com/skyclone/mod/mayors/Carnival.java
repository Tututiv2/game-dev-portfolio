package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Carnival (research/05 §8: Fairground / "Chivalrous Carnival" keeps it open all year while the Fox mayor
 * governs; reference wiki: Carnival, Fruit Digging, Zombie Shootout, Catch a Fish, Doug, Carnival Token).
 * <ul>
 *   <li><b>Ringmaster Fenella</b> (Carnival Leader): 25 Carnival Tickets once per real (UTC) day.</li>
 *   <li>The three booths are played in the world on the <b>Carnival Grounds</b> island ({@link CarnivalGames});
 *       each costs 5 tickets and pays 1 Carnival Token per 100 points (+4% per Party set piece).</li>
 *   <li><b>Scorekeeper Odette</b> (Chantelle): fifteen goals per Carnival, 50 or 100 tokens + 5 tickets each.</li>
 *   <li><b>Token Trader Fitz</b> (Doug): the Souvenir Shop (masks, Mask Bag) and the Carnival Perks, permanent
 *       token-bought boosts to six festivals ({@link CarnivalPerks}).</li>
 * </ul>
 */
final class Carnival {

    static long today() { return LocalDate.now(ZoneOffset.UTC).toEpochDay(); }

    private final MayorsModule mod;
    private final CarnivalGames games;
    private final CarnivalPerks perks;
    private boolean announced;

    Carnival(MayorsModule mod) {
        this.mod = mod;
        this.games = new CarnivalGames(mod, this);
        this.perks = new CarnivalPerks(mod, this);
    }

    CarnivalGames games() { return games; }
    CarnivalPerks perks() { return perks; }

    void register() {
        new CarnivalMasks().register();
        Sky.worlds().registerIsland(IslandDef.of(CarnivalGroundsGenerator.ID, "Carnival Grounds", new CarnivalGroundsGenerator()));
        Sky.worlds().registerWarp("carnival", CarnivalGroundsGenerator.ID, "spawn", "Carnival Grounds", "mayors:carnival");
        games.register();
        perks.register();
    }

    void shutdown() { games.shutdown(); }

    boolean active() { return mod.election().perkActive("foxy.chivalrous_carnival"); }

    EventData.Carnival data() { return mod.data().carnival; }

    void tick() {
        boolean on = active();
        Cal.drive(Cal.CARNIVAL, on);
        if (on && !announced) {
            announced = true;
            for (Player p : Bukkit.getOnlinePlayers())
                if (EventAlerts.news(p, Cal.CARNIVAL))
                    Msg.send(p, "&d&lTHE CARNIVAL IS IN TOWN! &7Claim your free &aCarnival Tickets &7and play the booths on the Carnival Grounds. (&a/carnival&7)");
        } else if (!on) {
            announced = false;
        }
    }

    /** The current Carnival = the mayor term it runs in; goal progress resets when it changes. */
    int term() { return mod.election().state().term; }

    /** Member data with the goal progress rolled over to this Carnival. Null before ProfileReady. */
    PlayerData pd(Player p) {
        PlayerData pd = mod.data(p);
        if (pd == null) return null;
        int t = term();
        if (pd.carnivalGoalTerm != t) {
            pd.carnivalGoalTerm = t;
            pd.carnivalGoalsDone.clear();
            pd.carnivalPlays.clear();
        }
        return pd;
    }

    // =============================================================================================== Ringmaster

    void openLeader(Player p) {
        Menu m = new Menu("Carnival", 5).chrome();
        boolean on = active();
        PlayerData pd = pd(p);
        boolean claimed = pd != null && pd.carnivalTicketDay == today();
        int tickets = Sky.items().count(p, "CARNIVAL_TICKET");
        boolean here = CarnivalGames.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()));
        m.set(4, Icons.of(Material.FIREWORK_ROCKET, "&d&lThe Carnival",
                "&7While the &dFox &7mayor holds the",
                "&dFairground &7perk, the Carnival is in town",
                "&7all year long.",
                "",
                on ? "&aThe Carnival is open!" : "&cClosed: the Fairground perk is not active.",
                "",
                "&7Your tickets: &a" + tickets,
                "&7Your tokens: &d" + Text.commasInt(pd == null ? 0 : pd.carnivalTokens)));
        m.set(10, Icons.of(Material.PAPER, claimed ? "&7Tickets claimed" : "&aClaim Carnival Tickets",
                "&7Collect &a" + data().ticketsPerDay + " Carnival Tickets &7once",
                "&7every day.",
                "",
                claimed ? "&cAlready claimed today!" : on ? "&eClick to claim!" : "&cOnly while the Carnival is open."),
                c -> { claimTickets(c.player()); openLeader(c.player()); });
        m.set(12, booth(CarnivalRules.Game.FRUIT, Material.IRON_SHOVEL, "&7North of the plaza, under the orange canopy."), c -> openBooth(c.player(), CarnivalRules.Game.FRUIT));
        m.set(13, booth(CarnivalRules.Game.SHOOT, Material.STICK, "&7East of the plaza, at the firing stand."), c -> openBooth(c.player(), CarnivalRules.Game.SHOOT));
        m.set(14, booth(CarnivalRules.Game.FISH, Material.FISHING_ROD, "&7South of the plaza, on the dock."), c -> openBooth(c.player(), CarnivalRules.Game.FISH));
        m.set(16, Icons.of(Material.ENDER_PEARL, here ? "&aYou are on the Carnival Grounds" : "&aGo to the Carnival Grounds",
                "&7The booths are played on their own", "&7island. &8(/warp carnival)", "",
                here ? "&7Walk to a booth and talk to its host." : on ? "&eClick to travel!" : "&cOnly while the Carnival is open."),
                c -> { c.player().closeInventory(); if (!here) Sky.worlds().warp(c.player(), "carnival"); });
        m.set(29, Icons.of(Material.WRITABLE_BOOK, "&aCarnival Goals", "&7Fifteen goals, &d50-100 tokens &7and", "&a5 tickets &7each. &8(Scorekeeper Odette)", "",
                "&7Completed: &a" + (pd == null ? 0 : pd.carnivalGoalsDone.size()) + "&7/&a" + data().goals.size(), "", "&eClick to view!"),
                c -> openGoals(c.player()));
        m.set(31, Icons.of(Material.CARVED_PUMPKIN, "&dSouvenir Shop", "&7Masks and the Carnival Mask Bag.", "", "&eClick to browse!"),
                c -> openShop(c.player()));
        m.set(33, Icons.of(Material.NETHER_STAR, "&6Carnival Perks", "&7Permanent boosts to the festivals,", "&7bought with &dCarnival Tokens&7.", "", "&eClick to browse!"),
                c -> perks.open(c.player()));
        m.set(22, Icons.of(Material.BOOK, "&aYour Carnival",
                "&7Games played: &e" + (pd == null ? 0 : pd.carnivalGames),
                "&7Best &6Fruit Digging&7: &e" + Text.commasInt(best(pd, "FRUIT")),
                "&7Best &cZombie Shootout&7: &e" + Text.commasInt(best(pd, "SHOOT")),
                "&7Best &3Catch a Fish&7: &e" + Text.commasInt(best(pd, "FISH"))));
        m.set(39, Icons.back("Festivals & Events"), c -> EventBoard.open(c.player()));
        m.open(p);
    }

    private static int best(PlayerData pd, String game) {
        if (pd == null) return 0;
        Integer b = pd.carnivalBestScores.get(game);
        if (b == null && "FRUIT".equals(game)) return pd.carnivalBest;
        return b == null ? 0 : b;
    }

    private ItemStack booth(CarnivalRules.Game g, Material icon, String where) {
        return Icons.of(icon, g.color + g.title, where, "", "&7Cost: &a" + data().gameCost + " Carnival Tickets", "", "&eClick for the rules!");
    }

    void claimTickets(Player p) {
        if (!active()) { Msg.error(p, "The Carnival is not in town right now."); return; }
        PlayerData pd = mod.data(p);
        if (pd == null) return;
        if (pd.carnivalTicketDay == today()) {
            Msg.send(p, "&e[NPC] &dRingmaster Fenella&f: You've already claimed your &aCarnival Tickets &ffor &btoday&f. Come back tomorrow!");
            return;
        }
        pd.carnivalTicketDay = today();
        Goods.give(p, "CARNIVAL_TICKET", data().ticketsPerDay, LootSource.EVENT);
        Msg.send(p, "&aYou claimed &aCarnival Ticket &8x" + data().ticketsPerDay + "&a!");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
    }

    // =============================================================================================== booths

    /** A booth host's rules screen with the Play button. */
    void openBooth(Player p, CarnivalRules.Game g) {
        if (games.session(p.getUniqueId()) != null) { Msg.error(p, "You are already playing! Sneak twice to stop."); return; }
        Menu m = new Menu(g.title, 4).chrome();
        List<String> rules = switch (g) {
            case FRUIT -> List.of(
                    "&7Dig the &e7x7 &7sand patch with the", "&aCarnival Shovel &7(left-click a square).",
                    "&7You get &a" + data().digs + " digs &7and &a" + data().digSeconds / 60 + " minutes&7.", "",
                    "&dDragonfruit &e1,200  &2Durian &e800 &8(next x0.5)", "&6Mango &e300  &fCoconut &e200 &8(stops a bomb)",
                    "&cPomegranate &e200 &8(next x1.5)  &cCherry &e200 &8+300/pair",
                    "&cApple &e+100 &8per apple so far  &aWatermelon &e100", "&8(splashes open its neighbours for half)", "",
                    "&cBombs &7destroy the fruit around them.", "&6Rum &7switches dowsing off for one dig.", "",
                    "&eRight-click &7the shovel to change the", "&7Dowsing Mode: &cMines&7, &9Anchor &7or &6Treasure&7.");
            case SHOOT -> List.of(
                    "&7Stand at the firing stand and shoot", "&7the zombies with the &aDart Tube &7(right-click).",
                    "&7The round lasts &a" + data().shootSeconds + " seconds&7.", "",
                    "&fLeather &e30 &8(babies 60)  &fIron &e50", "&6Gold &e80  &bDiamond &e120",
                    "&7Each one leaves after a few seconds.", "",
                    "&cLit lamps&7: &e+100 &7and &dx1.2 &7points for 6 s.");
            case FISH -> List.of(
                    "&7Stand on the dock and cast the", "&aCarnival Rod &7next to a fish.",
                    "&eHold right-click &7until its bar fills.", "&7The round lasts &a" + data().fishSeconds + " seconds&7.", "",
                    "&cRed &e50  &9Blue &e100  &eYellow &e200", "&7Catches in a row: &a+5 &7each (max +50).",
                    "&710% of catches count &ddouble&7.");
        };
        List<String> lore = new ArrayList<>(rules);
        lore.add("");
        lore.add("&7Points become &dCarnival Tokens &8(1 per 100)&7.");
        m.set(4, Icons.of(g == CarnivalRules.Game.FRUIT ? Material.IRON_SHOVEL : g == CarnivalRules.Game.SHOOT ? Material.STICK : Material.FISHING_ROD,
                g.color + g.title, lore.toArray(new String[0])));
        int tickets = Sky.items().count(p, "CARNIVAL_TICKET");
        boolean here = CarnivalGames.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()));
        m.set(22, Icons.of(Material.LIME_CONCRETE, "&aPlay!",
                "&7Cost: &a" + data().gameCost + " Carnival Tickets",
                "&7You have: " + (tickets >= data().gameCost ? "&a" : "&c") + tickets,
                "",
                !active() ? "&cThe Carnival is closed." : here ? "&eClick to play!" : "&eClick to travel to the Carnival Grounds!"),
                c -> {
                    Player pl = c.player();
                    pl.closeInventory();
                    if (!CarnivalGames.ISLAND.equals(Sky.worlds().islandOf(pl.getWorld()))) { Sky.worlds().warp(pl, "carnival"); return; }
                    games.start(pl, g);
                });
        m.set(31, Icons.back("Carnival"), c -> openLeader(c.player()));
        m.open(p);
    }

    /** Take one game's tickets. False (with the reason said) when closed or short. */
    boolean payTickets(Player p) {
        if (!active()) { Msg.error(p, "The Carnival is not in town right now."); return false; }
        int cost = Math.max(0, data().gameCost);
        if (Sky.items().count(p, "CARNIVAL_TICKET") < cost) {
            Msg.error(p, "You need " + cost + " Carnival Tickets to play! The Ringmaster hands them out daily.");
            return false;
        }
        if (cost > 0 && Sky.items().remove(p, "CARNIVAL_TICKET", cost) < cost) { Msg.error(p, "Could not take your tickets."); return false; }
        return true;
    }

    /** A finished game: tokens (Carnival Craze included), the play count, bests, goals and the summary. */
    void finishGame(Player p, CarnivalRules.Game g, int score, Map<String, Integer> stats, String reason) {
        long tokens = CarnivalRules.tokens(score, data().tokensPerPoint, CarnivalGames.partyPieces(p));
        PlayerData pd = pd(p);
        List<String> body = new ArrayList<>();
        if (reason != null) body.add("&7" + reason);
        body.add("&7Score: &e" + Text.commasInt(score));
        for (Map.Entry<String, Integer> e : stats.entrySet()) {
            if ("score".equals(e.getKey())) continue;
            body.add("&7" + statName(e.getKey()) + ": &a" + e.getValue() + ("accuracy".equals(e.getKey()) ? "%" : ""));
        }
        body.add("&d+" + tokens + " Carnival Tokens");
        Msg.box(p, g.color + "&l" + g.title.toUpperCase(), body);
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.0f);
        if (pd == null) return;
        pd.carnivalTokens += tokens;
        pd.carnivalGames++;
        pd.carnivalPlays.merge(g.name(), 1, Integer::sum);
        pd.carnivalBestScores.merge(g.name(), score, Math::max);
        if (g == CarnivalRules.Game.FRUIT) pd.carnivalBest = Math.max(pd.carnivalBest, score);
        Map<String, Integer> st = new LinkedHashMap<>(stats);
        st.put("score", score);
        for (EventData.Goal goal : CarnivalRules.newlyMet(data().goals, g.name(), st, pd.carnivalPlays.getOrDefault(g.name(), 0), pd.carnivalGoalsDone)) {
            pd.carnivalGoalsDone.add(goal.id);
            pd.carnivalTokens += goal.tokens;
            if (goal.tickets > 0) Goods.give(p, "CARNIVAL_TICKET", goal.tickets, LootSource.REWARD);
            Msg.send(p, "&d&lCARNIVAL GOAL COMPLETE! &7" + goal.text);
            Msg.send(p, "  &8+&d" + goal.tokens + " Carnival Tokens &8+&aCarnival Ticket x" + goal.tickets);
        }
        try { Sky.profiles().saveNow(p); } catch (Throwable ignored) { }
    }

    private static String statName(String k) {
        return switch (k) {
            case "apples" -> "Apples";
            case "unique" -> "Unique fruits";
            case "dragonfruit" -> "Dragonfruit";
            case "fruits" -> "Fruits found";
            case "kills" -> "Zombies shot";
            case "diamond" -> "Diamond Zombies";
            case "lamps" -> "Lamps hit";
            case "accuracy" -> "Accuracy";
            case "caught" -> "Fish caught";
            case "yellow" -> "Yellow Fish";
            case "streak" -> "Best Catch Streak";
            default -> Text.titleCase(k);
        };
    }

    // =============================================================================================== goals

    void openGoals(Player p) {
        Menu m = new Menu("Carnival Goals", 6).chrome();
        PlayerData pd = pd(p);
        m.set(4, Icons.of(Material.WRITABLE_BOOK, "&aCarnival Goals",
                "&7Finish a goal and its reward is paid", "&7at once. They reset with each Carnival.",
                "", "&7Completed: &a" + (pd == null ? 0 : pd.carnivalGoalsDone.size()) + "&7/&a" + data().goals.size()));
        int[] rows = {10, 19, 28};
        int r = 0;
        for (CarnivalRules.Game g : CarnivalRules.Game.values()) {
            int slot = rows[r++];
            m.set(slot, Icons.of(g == CarnivalRules.Game.FRUIT ? Material.IRON_SHOVEL : g == CarnivalRules.Game.SHOOT ? Material.STICK : Material.FISHING_ROD,
                    g.color + g.title, "&7Played this Carnival: &e" + (pd == null ? 0 : pd.carnivalPlays.getOrDefault(g.name(), 0))));
            int s = slot + 1;
            for (EventData.Goal goal : data().goals) {
                if (!goal.game.equalsIgnoreCase(g.name())) continue;
                boolean done = pd != null && pd.carnivalGoalsDone.contains(goal.id);
                m.set(s++, Icons.of(done ? Material.LIME_DYE : Material.GRAY_DYE, done ? "&a&lCOMPLETE" : "&eGoal",
                        "&7" + goal.text, "", "&7Reward:", "&8+ &d" + goal.tokens + " Carnival Tokens", "&8+ &aCarnival Ticket x" + goal.tickets));
            }
        }
        m.set(48, Icons.back("Carnival"), c -> openLeader(c.player()));
        m.open(p);
    }

    // =============================================================================================== souvenir shop

    void openShop(Player p) {
        Menu m = new Menu("Souvenir Shop", 4).chrome();
        PlayerData pd = mod.data(p);
        long have = pd == null ? 0 : pd.carnivalTokens;
        m.set(4, Icons.of(Material.SUNFLOWER, "&dCarnival Tokens", "&7You have &d" + Text.commasInt(have) + " Carnival Tokens&7.",
                "&7Earn them at the three booths and", "&7from Carnival Goals."));
        int slot = 10;
        for (EventData.TokenOffer o : data().shop) {
            if (Goods.resolve(o.result) == null) continue;
            ItemStack icon = Goods.icon(o.result, o.amount);
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7Cost: &d" + Text.commasInt(o.tokens) + " Carnival Tokens");
            extra.add(have >= o.tokens ? "&eClick to trade!" : "&cNot enough tokens!");
            icon = Icons.withLore(icon, extra);
            m.set(slot, icon, c -> { buy(c.player(), o); openShop(c.player()); });
            slot = (slot % 9 == 7) ? slot + 3 : slot + 1;
        }
        m.set(30, Icons.back("Carnival"), c -> openLeader(c.player()));
        m.set(32, Icons.of(Material.NETHER_STAR, "&6Carnival Perks", "&7Festival boosts for tokens.", "", "&eClick to browse!"), c -> perks.open(c.player()));
        m.open(p);
    }

    void buy(Player p, EventData.TokenOffer o) {
        PlayerData pd = mod.data(p);
        if (pd == null) return;
        if (pd.carnivalTokens < o.tokens) { Msg.error(p, "You need " + Text.commasInt(o.tokens) + " Carnival Tokens!"); return; }
        pd.carnivalTokens -= o.tokens;
        String got = Goods.give(p, o.result, Math.max(1, o.amount), LootSource.NPC_SHOP);
        Msg.send(p, "&aYou traded &d" + Text.commasInt(o.tokens) + " tokens &afor " + Goods.displayName(got == null ? o.result : got) + "&a!");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f);
    }
}
