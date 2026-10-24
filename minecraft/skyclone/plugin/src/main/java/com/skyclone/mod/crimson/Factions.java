package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/**
 * The two factions: pledging, reputation and ranks, the daily quest board and everything that advances
 * it (kills, mini-bosses, lava catches, deliveries, Dojo scores), the {@code faction:} and {@code dojo:}
 * requirement keys, and the sidebar lines.
 */
final class Factions implements Listener {

    static void register() {
        Sky.requirements().register("faction", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (!CrimsonModule.ready(p)) return false;
                CrimsonData d = CrimsonModule.data(p);
                String f = parts.length > 1 ? parts[1].toLowerCase() : "any";
                int need = parts.length > 2 ? parseInt(parts[2]) : 0;
                if (f.equals("any")) {
                    for (String x : CrimsonRules.FACTIONS) if (d.rep(x) >= need && (need > 0 || x.equals(d.faction))) return true;
                    return false;
                }
                String fac = CrimsonRules.faction(f);
                if (fac == null) return false;
                if (need <= 0) return fac.equals(d.faction);
                return d.rep(fac) >= need;
            }

            @Override public String describe(String[] parts) {
                String f = parts.length > 1 ? parts[1].toLowerCase() : "any";
                int need = parts.length > 2 ? parseInt(parts[2]) : 0;
                String fac = CrimsonRules.faction(f);
                String who = fac == null ? "Faction" : display(fac);
                if (need <= 0) return fac == null ? "&aa Crimson Isle faction" : "&aMembership of the " + who;
                return "&a" + Text.commasInt(need) + " " + (fac == null ? "Faction" : who) + " Reputation";
            }
        });
        Sky.requirements().register("dojo", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (!CrimsonModule.ready(p) || parts.length < 2) return false;
                CrimsonData d = CrimsonModule.data(p);
                if (parts[1].equalsIgnoreCase("points")) return parts.length > 2 && Dojo.total(d) >= parseInt(parts[2]);
                for (CrimsonTables.Belt b : CrimsonModule.tables.dojo.belts)
                    if (b.id.equalsIgnoreCase("DOJO_" + parts[1] + "_BELT") || b.name.equalsIgnoreCase(parts[1] + " Belt"))
                        return Dojo.total(d) >= b.points;
                return false;
            }

            @Override public String describe(String[] parts) {
                if (parts.length > 2 && parts[1].equalsIgnoreCase("points")) return "&a" + parts[2] + " Dojo points";
                return parts.length > 1 ? "&aDojo " + Text.titleCase(parts[1]) + " Belt" : "&aa Dojo belt";
            }
        });

        Sky.sidebar().section("crimson", 300, p -> {
            if (!CrimsonModule.D.sidebar.get() || !CrimsonModule.ready(p) || !CrimsonModule.onIsle(p)) return List.of();
            CrimsonData d = CrimsonModule.data(p);
            if (d.faction == null) return List.of("&7Faction: &cnone &8(/faction)");
            ensureBoard(p, d);
            int done = (int) d.quests.stream().filter(q -> q.done).count();
            CrimsonTables.Rank r = rank(d.rep(d.faction));
            return List.of(color(d.faction) + display(d.faction) + " " + r.color + r.name + " &8| &a" + Text.shortNum(d.rep(d.faction))
                    + " &8| &e" + done + "/" + d.quests.size());
        });
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return 0; }
    }

    // ---- lookups --------------------------------------------------------------------------------------

    static CrimsonTables.FactionInfo info(String faction) { return CrimsonModule.tables.factions.factions.get(faction); }

    static String display(String faction) {
        CrimsonTables.FactionInfo i = info(faction);
        return i == null ? Text.titleCase(faction) : i.display;
    }

    static String color(String faction) {
        CrimsonTables.FactionInfo i = info(faction);
        return i == null ? "&f" : i.color;
    }

    static List<CrimsonTables.Rank> ranks() { return CrimsonModule.tables.factions.ranks; }

    static CrimsonTables.Rank rank(int rep) { return ranks().get(CrimsonRules.rankIndex(ranks(), rep)); }

    static int rankIndex(Player p) {
        CrimsonData d = CrimsonModule.data(p);
        return d.faction == null ? -1 : CrimsonRules.rankIndex(ranks(), d.rep(d.faction));
    }

    // ---- pledging ---------------------------------------------------------------------------------------

    static void leaderClicked(Player p, String faction) {
        CrimsonData d = CrimsonModule.data(p);
        if (d.faction == null) { CrimsonMenus.pledge(p, faction); return; }
        if (!d.faction.equals(faction)) {
            Msg.send(p, "&e[NPC] &f" + info(faction).leader + "&f: You already serve the " + display(d.faction) + ". Leave my hall.");
            Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 0.8f);
            return;
        }
        ensureBoard(p, d);
        // hand in finished deliveries automatically, then show the board
        deliverAll(p, true);
        CrimsonMenus.quests(p);
    }

    /** Join (or switch to) a faction. Switching costs coins and is rate-limited; reputation is kept per faction. */
    static boolean pledge(Player p, String faction) {
        CrimsonData d = CrimsonModule.data(p);
        if (faction.equals(d.faction)) return true;
        if (d.faction != null) {
            long cd = CrimsonModule.D.switchCooldownDays.get() * 86_400_000L;
            long left = d.factionSince + cd - System.currentTimeMillis();
            if (left > 0) { Msg.error(p, "You can switch factions again in " + Text.duration(left / 1000) + "."); return false; }
            int fee = CrimsonModule.D.switchFee.get();
            if (fee > 0 && !Sky.economy().withdraw(p, fee, "crimson: switch faction " + d.faction + " -> " + faction)) {
                Msg.error(p, "Switching factions costs " + Text.commasInt(fee) + " coins.");
                return false;
            }
        }
        d.faction = faction;
        d.factionSince = System.currentTimeMillis();
        d.questDay = null;                       // a fresh board for the new allegiance
        d.quests.clear();
        ensureBoard(p, d);
        Msg.box(p, color(faction) + "&lFACTION JOINED " + color(faction) + display(faction), List.of(
                "&7You now serve " + color(faction) + info(faction).leader + "&7 of " + info(faction).town + ".",
                "&7Complete &adaily quests &7and slay the isle's champions",
                "&7to earn reputation. &8(/faction, /factionquests)"));
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
        Sky.profiles().saveNow(p);
        return true;
    }

    // ---- reputation -------------------------------------------------------------------------------------------

    /** Add reputation to the player's pledged faction (or a named one). Announces rank-ups once. */
    static void addRep(Player p, String faction, int amount, String reason) {
        if (amount == 0 || faction == null) return;
        CrimsonData d = CrimsonModule.data(p);
        int before = d.rep(faction);
        int after = Math.max(-3000, before + amount);
        d.rep.put(faction, after);
        Msg.send(p, (amount > 0 ? "&a+" : "&c") + amount + " " + color(faction) + display(faction) + " Reputation &8(" + reason + ")");
        int rb = CrimsonRules.rankIndex(ranks(), before), ra = CrimsonRules.rankIndex(ranks(), after);
        if (ra > rb) {
            for (int i = rb + 1; i <= ra; i++) {
                CrimsonTables.Rank r = ranks().get(i);
                if (!d.ranksSeen.add(faction + ":" + i)) continue;
                Msg.box(p, color(faction) + "&lRANK UP! " + r.color + r.name, List.of(
                        "&7You are now " + r.color + r.name + " &7with the " + color(faction) + display(faction) + "&7.",
                        "&8+&b" + r.sbxp + " SkyBlock XP"));
                if (r.sbxp > 0) Sky.service(Features.SkyLevel.class).award(p, "crimson:rank:" + faction + ":" + i, r.sbxp, "Crimson Isle rank " + r.name);
                Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
            }
        }
    }

    // ---- the quest board ----------------------------------------------------------------------------------------

    static void ensureBoard(Player p, CrimsonData d) {
        if (d.faction == null) return;
        String day = CUtil.today(CrimsonModule.D.resetHourUtc.get());
        if (day.equals(d.questDay) && !d.quests.isEmpty()) return;
        int rank = CrimsonRules.rankIndex(ranks(), d.rep(d.faction));
        List<CrimsonTables.Quest> rolled = CrimsonRules.rollQuests(usable(), d.faction, rank,
                CrimsonRules.questSeed(p.getUniqueId().toString(), day), CrimsonModule.D.questsPerDay.get());
        d.questDay = day;
        d.quests.clear();
        d.dailyBonusClaimed = false;
        for (CrimsonTables.Quest q : rolled) {
            CrimsonData.QuestState s = new CrimsonData.QuestState(q.id);
            if (q.type.equals("KUUDRA")) s.base = leviathanClears(p, q.target);
            d.quests.add(s);
        }
    }

    static final String[] LEVIATHAN_TIERS = {"basic", "hot", "burning", "fiery", "infernal"};

    /**
     * Leviathan clears of {@code tier} or higher ("any" = every tier), read through the kuudra module's
     * requirement resolver ({@code kuudra:<tier>:<n>} holds while clears >= n), so no code is shared.
     */
    static int leviathanClears(Player p, String tier) {
        int from = 0;
        for (int i = 0; i < LEVIATHAN_TIERS.length; i++) if (LEVIATHAN_TIERS[i].equalsIgnoreCase(tier)) from = i;
        int sum = 0;
        for (int i = from; i < LEVIATHAN_TIERS.length; i++) sum += clearsOf(p, LEVIATHAN_TIERS[i]);
        return sum;
    }

    private static int clearsOf(Player p, String tier) {
        if (!Sky.requirements().check(p, "kuudra:" + tier + ":1")) return 0;
        int lo = 1, hi = 2;                                   // lo holds, hi unknown
        while (hi < (1 << 20) && Sky.requirements().check(p, "kuudra:" + tier + ":" + hi)) { lo = hi; hi <<= 1; }
        while (hi - lo > 1) {                                 // lo holds, hi fails
            int mid = (lo + hi) >>> 1;
            if (Sky.requirements().check(p, "kuudra:" + tier + ":" + mid)) lo = mid; else hi = mid;
        }
        return lo;
    }

    /** Progress today's Leviathan quests from the clear records (run every few seconds). */
    static void checkHunts(Player p) {
        CrimsonData d = CrimsonModule.data(p);
        if (d.faction == null || d.quests.isEmpty()) return;
        if (!CUtil.today(CrimsonModule.D.resetHourUtc.get()).equals(d.questDay)) return;
        for (CrimsonData.QuestState s : d.quests) {
            if (s.done) continue;
            CrimsonTables.Quest q = CrimsonModule.tables.quest(s.id);
            if (q == null || !q.type.equals("KUUDRA")) continue;
            int now = leviathanClears(p, q.target);
            if (s.base < 0) { s.base = now; continue; }
            int got = Math.max(0, Math.min(q.amount, now - s.base));
            if (got > s.progress) {
                s.progress = got;
                if (s.progress >= q.amount) complete(p, d, s, q);
            }
        }
    }

    /** The dial's per-tier Leviathan reputation (falls back to the data table when the dial text is malformed). */
    static List<Integer> leviathanRepTable() {
        List<Integer> t = CrimsonRules.parseInts(CrimsonModule.D.kuudraRep.get());
        return t != null ? t : CrimsonModule.tables.factions.kuudra_rep;
    }

    /**
     * Audit K16: the reference pays faction reputation for a Leviathan kill, +10/20/30/40/50 by tier, once a day per
     * tier. The hunts run in the kuudra module; we only see its clear records (through {@code kuudra:<tier>:<n>}), so a
     * rise in a tier's clear count since the last poll is a clear. The first poll only records the baseline.
     */
    static void checkLeviathanRep(Player p) {
        if (!CrimsonModule.kuudraResolverPresent()) return;
        CrimsonData d = CrimsonModule.data(p);
        List<Integer> table = leviathanRepTable();
        String today = CUtil.today(CrimsonModule.D.resetHourUtc.get());
        for (int t = 0; t < LEVIATHAN_TIERS.length; t++) {
            String tier = LEVIATHAN_TIERS[t];
            int now = clearsOf(p, tier);
            int seen = d.kuudraSeen.getOrDefault(tier, -1);
            if (now == seen) continue;
            boolean pay = d.faction != null && CrimsonRules.kuudraRepDue(seen, now, d.kuudraRepDay.get(tier), today);
            d.kuudraSeen.put(tier, now);
            int rep = CrimsonRules.kuudraRep(table, t);
            if (pay && rep > 0) {
                d.kuudraRepDay.put(tier, today);
                addRep(p, d.faction, rep, CrimsonRules.TIER_NAME[t] + " Leviathan, today's first");
            }
        }
    }

    /**
     * Audit K18: Rollim (our Watchman Orrin, who wears his skin) gives +25 reputation once a day for 7 days, 24 h
     * apart. Claimed by talking to him while pledged.
     */
    static void rollim(Player p) {
        CrimsonData d = CrimsonModule.data(p);
        int days = CrimsonModule.D.rollimDays.get(), rep = CrimsonModule.D.rollimRep.get();
        if (rep <= 0 || d.rollimClaims >= days) return;
        if (d.faction == null) {
            Msg.send(p, "&e[NPC] &fWatchman Orrin&f: Pledge to a faction and I'll put in a good word for you - once a day, for a week.");
            return;
        }
        long now = System.currentTimeMillis();
        if (!CrimsonRules.rollimDue(d.rollimClaims, d.rollimLast, now, days, CrimsonModule.D.rollimHours.get())) {
            long left = d.rollimLast + CrimsonModule.D.rollimHours.get() * 3_600_000L - now;
            Msg.send(p, "&e[NPC] &fWatchman Orrin&f: I've already vouched for you today. Come back in &e" + Text.duration(Math.max(0, left) / 1000) + "&f.");
            return;
        }
        d.rollimClaims++;
        d.rollimLast = now;
        Msg.send(p, "&e[NPC] &fWatchman Orrin&f: I'll tell the " + display(d.faction) + " you've been useful. &8(" + d.rollimClaims + "/" + days + ")");
        addRep(p, d.faction, rep, "Watchman Orrin");
        Sky.profiles().saveNow(p);
    }

    /** Audit K17: percent off a Leviathan key's coin part at this reputation (the Emissary discount). */
    static int emissaryPercent(int rep) {
        String v = CrimsonModule.D.emissaryDiscount.get();
        if (v == null || v.isBlank()) return 0;                       // empty dial = no discount
        java.util.Map<String, Integer> t = CrimsonRules.parseThresholds(v);
        return CrimsonRules.emissaryDiscount(t != null ? t : CrimsonModule.tables.factions.emissary_discount, rep);
    }

    /** What this player pays in coins for a shop entry (the Emissary discount on keys, reputation with that faction). */
    static double shopCoins(CrimsonData d, String faction, CrimsonTables.ShopEntry e) {
        return e.emissary ? CrimsonRules.discounted(e.coins, emissaryPercent(d.rep(faction))) : e.coins;
    }

    /** Quests whose targets exist in this server (a missing item or mob never lands on a board). */
    static List<CrimsonTables.Quest> usable() {
        List<CrimsonTables.Quest> out = new ArrayList<>();
        for (CrimsonTables.Quest q : CrimsonModule.tables.quests) {
            boolean ok = switch (q.type) {
                case "KILL", "BOSS" -> Sky.mobs().def(q.target) != null;
                case "FETCH" -> Sky.items().exists(q.target);
                case "KUUDRA" -> CrimsonModule.kuudraResolverPresent();
                default -> true;
            };
            if (ok) out.add(q);
        }
        return out;
    }

    static int rewardOf(CrimsonTables.Quest q) { return (int) Math.round(q.rep * CrimsonModule.D.questRepScale.get()); }

    private static void progress(Player p, java.util.function.Predicate<CrimsonTables.Quest> match, int by) {
        CrimsonData d = CrimsonModule.data(p);
        if (d.faction == null) return;
        ensureBoard(p, d);
        for (CrimsonData.QuestState s : d.quests) {
            if (s.done) continue;
            CrimsonTables.Quest q = CrimsonModule.tables.quest(s.id);
            if (q == null || !match.test(q)) continue;
            s.progress = Math.min(q.amount, q.type.equals("DOJO") ? Math.max(s.progress, by) : s.progress + by);
            if (s.progress >= q.amount) complete(p, d, s, q);
            else if (!q.type.equals("DOJO")) Sky.vitals().flash(p, "&6Quest: &e" + s.progress + "/" + q.amount, 30);
        }
    }

    static void complete(Player p, CrimsonData d, CrimsonData.QuestState s, CrimsonTables.Quest q) {
        if (s.done) return;
        s.done = true;
        s.progress = q.amount;
        d.questsDone++;
        Msg.send(p, "&6&lQUEST COMPLETE! &e" + q.desc);
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        addRep(p, d.faction, rewardOf(q), "quest");
        if (!d.dailyBonusClaimed && d.quests.stream().allMatch(x -> x.done)) {
            d.dailyBonusClaimed = true;
            Msg.send(p, "&6&lBOARD CLEARED! &eEvery quest for today is done.");
            if (CrimsonModule.D.dailyBonusRep.get() > 0) addRep(p, d.faction, CrimsonModule.D.dailyBonusRep.get(), "daily bonus");
        }
    }

    /** Hand in every finished-able delivery quest. Returns how many were completed. */
    static int deliverAll(Player p, boolean quiet) {
        CrimsonData d = CrimsonModule.data(p);
        int n = 0;
        for (CrimsonData.QuestState s : d.quests) if (deliver(p, s, true)) n++;
        if (n == 0 && !quiet) Msg.error(p, "You have nothing to deliver yet.");
        return n;
    }

    /** Try one FETCH quest: takes the items (inventory, then sacks) and completes it. */
    static boolean deliver(Player p, CrimsonData.QuestState s, boolean quiet) {
        CrimsonData d = CrimsonModule.data(p);
        CrimsonTables.Quest q = CrimsonModule.tables.quest(s.id);
        if (q == null || s.done || !q.type.equals("FETCH")) return false;
        int need = q.amount - s.progress;
        int have = CUtil.have(p, q.target);
        if (have <= 0) { if (!quiet) Msg.error(p, "You don't have any " + Text.strip(CUtil.name(q.target)) + "."); return false; }
        int took = CUtil.take(p, q.target, Math.min(need, have));
        s.progress += took;
        if (s.progress >= q.amount) { complete(p, d, s, q); return true; }
        if (!quiet) Msg.send(p, "&eDelivered " + took + "x " + CUtil.name(q.target) + "&e. &7(" + s.progress + "/" + q.amount + ")");
        return false;
    }

    // ---- events -------------------------------------------------------------------------------------------------

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        CrimsonData d = CrimsonModule.data(e.player);
        ensureBoard(e.player, d);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || e.def == null || !CrimsonModule.ready(p)) return;
        MobDef def = e.def;
        if (def.id == null || !def.id.startsWith("crimson_")) return;
        // BOSS quests are progressed by BossLoot for EVERY player who shares the kill (wave 5), not only the killer
        progress(p, q -> q.type.equals("KILL") && q.target.equals(def.id), 1);
    }

    /** Is a BOSS quest for {@code bossId} open (rolled today, not done) on this player's board? */
    static boolean bossQuestOpen(Player p, String bossId) {
        CrimsonData d = CrimsonModule.data(p);
        if (d.faction == null || bossId == null) return false;
        ensureBoard(p, d);
        for (CrimsonData.QuestState s : d.quests) {
            if (s.done) continue;
            CrimsonTables.Quest q = CrimsonModule.tables.quest(s.id);
            if (q != null && q.type.equals("BOSS") && bossId.equals(q.target)) return true;
        }
        return false;
    }

    /** A mini-boss this player shared fell: progress its BOSS quests. */
    static void bossKilled(Player p, String bossId) {
        progress(p, q -> q.type.equals("BOSS") && q.target.equals(bossId), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(SkyEvents.FishCaught e) {
        if (!e.lava || !CrimsonModule.ready(e.player)) return;
        progress(e.player, q -> q.type.equals("FISH") || (e.seaCreature && q.type.equals("FISH_CREATURE")), 1);
    }

    /** Called by the Dojo with a finished trial's points. */
    static void dojoScored(Player p, String trial, int points) {
        progress(p, q -> q.type.equals("DOJO") && (q.target.equals(trial) || q.target.equals("any")), points);
    }

    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) {
        if (!CrimsonModule.ISLAND.equals(e.to) || !CrimsonModule.ready(e.player)) return;
        Player p = e.player;
        CrimsonData d = CrimsonModule.data(p);
        p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&cCrimson Isle"),
                Text.c(d.faction == null ? "&7Pledge to the Mages or the Barbarians" : color(d.faction) + display(d.faction) + " &7- " + rank(d.rep(d.faction)).name)));
        if (!d.visited) {
            d.visited = true;
            Msg.send(p, "&7Talk to &fWatchman Orrin &7by the obelisk, or type &a/crimson&7.");
        }
    }
}
