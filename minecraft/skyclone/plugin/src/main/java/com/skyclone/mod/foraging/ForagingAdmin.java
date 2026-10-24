package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** {@code /sb foraging ...}: every admin action takes a player name so it works over RCON. */
final class ForagingAdmin {

    private ForagingAdmin() {}

    static final List<String> SUBS = List.of("info", "sweep", "player", "kit", "scroll", "quest", "trial", "songs", "hotf", "gift",
            "regen", "chop", "menu", "ability", "contest", "hive", "lather", "depth", "bonus", "coupons", "booster", "jade");

    static void register() {
        Sky.commands().admin("foraging", "<" + String.join("|", SUBS) + "> ...", "Foraging admin and test tools", ForagingAdmin::run,
                (s, a) -> a.length <= 1 ? SUBS : a.length == 2 && !List.of("info", "sweep", "regen").contains(a[0])
                        ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of());
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player player(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cMissing player name."); return null; }
        Player p = Bukkit.getPlayerExact(a[i]);
        if (p == null) { say(s, "&cNo online player " + a[i]); return null; }
        if (!Sky.profiles().isReady(p)) { say(s, "&cTheir profile is not ready yet."); return null; }
        return p;
    }

    static void run(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&eUsage: /sb foraging <" + String.join("|", SUBS) + ">"); return; }
        switch (a[0].toLowerCase()) {
            case "info" -> {
                say(s, "&aForaging: &7trees regrowing &e" + ForagingModule.regen().pendingCount() + " &7(" + ForagingModule.regen().pendingBlocks()
                        + " blocks), Moonmarsh trees being cut &e" + ForagingModule.marsh().active());
                say(s, "&7axes registered: &e" + ForagingMenus.axeDefs().size() + "&7, park built: &e" + Sky.worlds().isBuilt("park")
                        + "&7, moonmarsh built: &e" + Sky.worlds().isBuilt("galatea"));
            }
            case "sweep" -> {
                double sw = a.length > 1 ? ForagingModule.parse(a[1], 0) : 0;
                for (Wood w : Wood.values())
                    say(s, "&7" + w.display + " &8(t" + w.toughness + ")&7: sweep " + sw + " -> &a" + SweepMath.logsPerSwing(sw, w.toughness, false, 0, false)
                            + " &7logs, thrown &a" + SweepMath.logsPerSwing(sw, w.toughness, true, FDials.throwPenalty.get(), false));
            }
            case "player" -> {
                Player p = player(s, a, 1); if (p == null) return;
                ForagingData d = ForagingModule.data(p);
                say(s, "&a" + p.getName() + "&7: sweep &a" + Text.commas(Sky.stats().get(p, Stat.SWEEP)) + "&7, fortune &a" + Text.commas(Sky.stats().get(p, Stat.FORAGING_FORTUNE))
                        + "&7, logs &a" + d.logs + "&7, scrolls &a" + d.scrolls + "&7, quests &a" + d.quests + "&7, trial &a" + d.trialTier + "&7, songs &a" + d.songs.size());
                say(s, "&7HotF " + (d.hotfUnlocked ? "&atier " + Hotf.tier(d) + " (" + d.hotfXp + " xp)" : "&clocked") + "&7, whispers &b" + d.forestWhispers
                        + "&7/&e" + d.desertWhispers + "&7, essence &d" + d.forestEssence + "&7, tokens &5" + Hotf.tokensFree(d) + "&7/" + Hotf.tokensTotal(d)
                        + "&7, perks " + d.perks + "&7, gifts " + d.treeGifts);
                say(s, "&7resources &a" + d.resources + "&7, axe ability &6" + d.axeAbility
                        + "&7 (damage boost " + HotfAbilities.active(p, "damage_boost") + ", axe toss " + HotfAbilities.active(p, "axe_toss")
                        + ", mana toss " + HotfAbilities.active(p, "mana_toss") + ")");
            }
            case "contest" -> {
                // /sb foraging contest <p> <marsh|canyon> <points>   |   /sb foraging contest <p> end
                Player p = player(s, a, 1); if (p == null) return;
                ForagingData d = ForagingModule.data(p);
                if (a.length > 2 && a[2].equalsIgnoreCase("end")) {
                    if (d.contestId == 0) { say(s, "&7" + p.getName() + " has no contest points."); return; }
                    d.contestId = Contest.currentId() - 1;
                    Contest.settle(p, d);
                    say(s, "&aEnded and paid " + p.getName() + "'s contest.");
                    return;
                }
                if (a.length < 4) { say(s, "&cUsage: contest <p> <marsh|canyon> <points> | contest <p> end"); return; }
                Contest.add(p, a[2].equalsIgnoreCase("canyon"), ForagingModule.parse(a[3], 0));
                say(s, "&a" + p.getName() + ": marsh " + d.contestMarsh + ", canyon " + d.contestCanyon + " (" + Contest.msLeft() / 1000 + "s left)");
            }
            case "hive" -> {
                // /sb foraging hive <p> [reset|harvest]
                Player p = player(s, a, 1); if (p == null) return;
                ForagingData d = ForagingModule.data(p);
                if (a.length > 2 && a[2].equalsIgnoreCase("reset")) { d.hiveReady.replaceAll((k, v) -> 0L); say(s, "&aAll of " + p.getName() + "'s hives are full again."); return; }
                if (a.length > 2 && a[2].equalsIgnoreCase("harvest")) {
                    Block b = p.getTargetBlockExact(6);
                    if (b == null || !Hives.isHive(b.getType())) { say(s, "&c" + p.getName() + " is not looking at a hive."); return; }
                    ForagingModule.hives().harvest(p, b);
                    say(s, "&aHarvested for " + p.getName() + ": total " + d.hivesHarvested + " hives, " + d.honeycomb + " honeycomb.");
                    return;
                }
                say(s, "&7" + p.getName() + ": hives harvested &a" + d.hivesHarvested + "&7, honeycomb &a" + d.honeycomb + "&7, hives waiting &e" + d.hiveReady.size()
                        + "&7, haul &a" + Bonuses.honeyHaulPercent(p) + "%&7, refill &a" + Bonuses.honeyRefillPercent(p) + "%&7, Hive Mind face &a" + Bonuses.dailyHive(p));
            }
            case "lather" -> {
                // /sb foraging lather <p> [ready|clear]
                Player p = player(s, a, 1); if (p == null) return;
                ForagingData d = ForagingModule.data(p);
                if (a.length > 2 && a[2].equalsIgnoreCase("ready")) d.lathered.forEach(l -> l.due = 0);
                if (a.length > 2 && a[2].equalsIgnoreCase("clear")) d.lathered.clear();
                say(s, "&7" + p.getName() + ": lathered trees &e" + d.lathered.size() + "&7, critters lured &a" + d.crittersLured
                        + "&7, lure faster &a" + Bonuses.latherFasterPercent(p) + "%&7, second critter &a" + Bonuses.honeyLuckPercent(p) + "%&7, live lured &a" + ForagingModule.hives().luredAlive());
                for (Wood w : List.of(Wood.FIG, Wood.MANGROVE, Wood.HELIX)) say(s, "&8 " + w.display + " lures e.g. " + Hives.pick(w));
            }
            case "depth" -> {
                Player p = player(s, a, 1); if (p == null) return;
                for (String l : Depths.report(p)) say(s, l);
            }
            case "bonus" -> {
                // every non-stat bonus the player has right now
                Player p = player(s, a, 1); if (p == null) return;
                for (Wood w : List.of(Wood.FIG, Wood.MANGROVE, Wood.HELIX))
                    say(s, "&7" + w.display + ": fortune &6+" + Text.commas(Bonuses.woodFortune(p, w)) + "&7, sweep &2+" + Text.commas(Bonuses.woodSweep(p, w))
                            + "&7, timber &a+" + Text.commas(Bonuses.timberBonus(p, w)) + "&7, gift rare x" + Text.commas(Bonuses.giftRareFactor(p, w)));
                say(s, "&7throw sweep +" + Bonuses.throwSweep(p) + ", melee sweep +" + Bonuses.meleeSweep(p) + ", first cut +" + Bonuses.firstSweepPercent(p)
                        + "%, claws -" + Bonuses.toughClaws(p) + ", chop " + Bonuses.chopPercent(p) + "%, throw cooldown x" + Text.commas(Bonuses.throwCooldownFactor(p))
                        + ", no throw penalty " + Bonuses.noThrowPenalty(p));
                say(s, "&7contest +" + Text.commas(Bonuses.contestPercent(p, true)) + "% (chop) / +" + Text.commas(Bonuses.contestPercent(p, false))
                        + "%, whispers x" + Text.commas(Bonuses.forestWhisperFactor(p)) + "/x" + Text.commas(Bonuses.desertWhisperFactor(p))
                        + ", essence x" + Text.commas(Bonuses.essenceFactor(p)) + ", HotF XP x" + Text.commas(Bonuses.hotfXpFactor(p))
                        + ", gift double " + Bonuses.giftDoublePercent(p) + "%, Wandering Grove face " + Bonuses.dailyGrove(p));
            }
            case "coupons" -> {
                // /sb foraging coupons <p> <n> [canyon]  (gives coupons + 20 prizes for shop tests)
                Player p = player(s, a, 1); if (p == null) return;
                int n = a.length > 2 ? ForagingModule.parse(a[2], 50) : 50;
                boolean canyon = a.length > 3 && a[3].equalsIgnoreCase("canyon");
                Chopper.giveChunked(p, canyon ? Contest.CANYON_COUPON : Contest.MARSH_COUPON, n, LootSource.ADMIN);
                Chopper.giveChunked(p, canyon ? Contest.CANYON_PRIZE : Contest.MARSH_PRIZE, 20, LootSource.ADMIN);
                say(s, "&aGave " + n + (canyon ? " canyon" : " marsh") + " coupons + 20 prizes to " + p.getName());
            }
            case "ability" -> {
                // /sb foraging ability <p> <damage_boost|axe_toss|mana_toss> [fire]
                Player p = player(s, a, 1); if (p == null) return;
                HotfAbilities.Ability ab = a.length > 2 ? HotfAbilities.byId(a[2]) : null;
                if (ab == null) { say(s, "&cability: damage_boost | axe_toss | mana_toss"); return; }
                ForagingData d = ForagingModule.data(p);
                d.axeAbility = ab.id();
                if (a.length > 3 && a[3].equalsIgnoreCase("fire")) HotfAbilities.fire(p, d, ab);
                say(s, "&aSelected " + ab.name() + " for " + p.getName() + (a.length > 3 ? " and fired it" : ""));
            }
            case "kit" -> {
                Player p = player(s, a, 1); if (p == null) return;
                int n = 0;
                for (var def : ForagingMenus.axeDefs()) { Sky.loot().give(p, def.id, 1, LootSource.ADMIN); n++; }
                say(s, "&aGave " + n + " axes to " + p.getName());
            }
            case "scroll" -> {
                Player p = player(s, a, 1); if (p == null) return;
                if (a.length < 3) { say(s, "&c/sb foraging scroll <player> <jungle|howl> [remove]"); return; }
                ForagingData d = ForagingModule.data(p);
                if (a.length > 3) d.scrolls.remove(a[2]); else d.scrolls.add(a[2]);
                say(s, "&aScrolls of " + p.getName() + ": " + d.scrolls);
            }
            case "quest" -> {
                Player p = player(s, a, 1); if (p == null) return;
                if (a.length < 4) { say(s, "&c/sb foraging quest <player> <birch|spruce|dark_oak|acacia|jungle> <0-3>"); return; }
                ForagingModule.data(p).quests.put(a[2], Math.max(0, Math.min(3, ForagingModule.parse(a[3], 0))));
                say(s, "&aQuest " + a[2] + " set to " + a[3]);
            }
            case "trial" -> {
                Player p = player(s, a, 1); if (p == null) return;
                ForagingModule.data(p).trialTier = Math.max(0, Math.min(29, a.length > 2 ? ForagingModule.parse(a[2], 0) : 0));
                say(s, "&aEmber trial tier set to " + ForagingModule.data(p).trialTier);
            }
            case "songs" -> {
                Player p = player(s, a, 1); if (p == null) return;
                ForagingData d = ForagingModule.data(p);
                if (a.length > 2 && a[2].equals("all")) for (ForagingNpcs.Song so : ForagingNpcs.SONGS) d.songs.add(so.id()); else d.songs.clear();
                Sky.stats().invalidate(p);
                say(s, "&aSongs: " + d.songs);
            }
            case "hotf" -> hotf(s, a);
            case "booster" -> {
                // /sb foraging booster <p> <sweep|foraging_fortune|foraging_wisdom|hunting_wisdom|fighting|clear|favor> [1|2]: on the held item
                Player p = player(s, a, 1); if (p == null) return;
                org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
                if (!Boosters.gear(hand)) { say(s, "&c" + p.getName() + " is not holding foraging gear."); return; }
                String what = a.length > 2 ? a[2] : "info";
                if (what.equalsIgnoreCase("info")) {
                    var d = Sky.items().data(hand);
                    say(s, "&7booster: &e" + Boosters.kindOf(d) + " " + Boosters.tierOf(d) + "&7, karma " + d.enchant("karma") + ", fighting % now " + Text.commas(Boosters.fighting(p)));
                    return;
                }
                if (what.equalsIgnoreCase("clear")) {
                    Sky.items().update(hand, x -> { x.extra.remove(Boosters.K_KIND); x.extra.remove(Boosters.K_TIER); x.extra.remove(Boosters.K_FAVOR); });
                    Sky.stats().invalidate(p); say(s, "&aCleared boosters on " + p.getName() + "'s held item."); return;
                }
                if (what.equalsIgnoreCase("favor")) {
                    Sky.items().update(hand, x -> { x.enchants.put("karma", Math.max(5, x.enchant("karma")) + 1); x.extra.addProperty(Boosters.K_FAVOR, true); });
                    say(s, "&aForest's Favor applied (karma " + Sky.items().data(hand).enchant("karma") + ")."); return;
                }
                BoosterMath.Kind k = BoosterMath.Kind.parse(what);
                if (k == null) { say(s, "&cKinds: " + java.util.Arrays.toString(BoosterMath.Kind.values()) + ", clear, favor, info"); return; }
                int t = a.length > 3 ? Math.max(1, Math.min(2, ForagingModule.parse(a[3], 1))) : 1;
                Sky.items().update(hand, x -> { x.extra.addProperty(Boosters.K_KIND, k.name()); x.extra.addProperty(Boosters.K_TIER, t); });
                Sky.stats().invalidate(p);
                say(s, "&aPut a tier " + t + " " + k.display + " booster on " + p.getName() + "'s held item.");
            }
            case "jade" -> {
                Player p = player(s, a, 1); if (p == null) return;
                ForagingNpcs.jadeShop(p).open(p);
                say(s, "&aOpened the jade dragon's hoard for " + p.getName() + " &7(anchor npc.jade_dragon: "
                        + (Sky.worlds().anchor("galatea", "npc.jade_dragon") != null) + ")");
            }
            case "gift" -> {
                Player p = player(s, a, 1); if (p == null) return;
                Wood w = a.length > 2 ? parseWood(a[2]) : Wood.FIG;
                double share = a.length > 3 ? ForagingModule.parse(a[3], 33) / 100.0 : 0.34;
                MarshTrees.Gift g = MarshTrees.giftOf(w);
                if (g == null) { say(s, "&cOnly fig, mangrove and helix trees give gifts."); return; }
                ForagingModule.marsh().give(p, w, g, SweepMath.giftShare(share) * MarshTrees.sizeOf(100), share);
                say(s, "&aGave a " + w.display + " Tree Gift to " + p.getName());
            }
            case "regen" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("restore")) say(s, "&aRestored " + ForagingModule.regen().restoreAll() + " trees.");
                else say(s, "&7" + ForagingModule.regen().pendingCount() + " trees pending (" + ForagingModule.regen().pendingBlocks() + " blocks). &e/sb foraging regen restore");
            }
            case "chop" -> {
                Player p = player(s, a, 1); if (p == null) return;
                Block b = AxeAbilities.findLog(p, 8);
                if (b == null) { say(s, "&c" + p.getName() + " is not looking at a log."); return; }
                if (!ForagingModule.chopper().handles(p, b, Sky.worlds().zoneAt(b.getLocation()))) { say(s, "&cThat log is not choppable here."); return; }
                Wood w = Wood.ofBlock(b.getType().name(), Chopper.toughIsland(Chopper.island(b.getWorld())));
                int n = ForagingModule.chopper().chop(p, ForagingModule.chopper().scan(b, w), Chopper.island(b.getWorld()), false);
                say(s, "&aChopped " + n + " logs for " + p.getName());
            }
            case "menu" -> {
                Player p = player(s, a, 1); if (p == null) return;
                String which = a.length > 2 ? a[2] : "main";
                switch (which) {
                    case "hotf" -> Hotf.menu(p).open(p);
                    case "woods" -> ForagingMenus.woods(p).open(p);
                    case "axes" -> ForagingMenus.axes(p, 0).open(p);
                    case "places" -> ForagingMenus.places(p).open(p);
                    case "trials" -> ForagingNpcs.trialsMenu(p).open(p);
                    case "harp" -> Harp.songs(p).open(p);
                    case "shop" -> ForagingNpcs.shop(p, "Axe-trader Hewitt", ForagingNpcs.AXE_TRADER, true).open(p);
                    case "contest" -> Contest.menu(p).open(p);
                    case "herbalist" -> Contest.shop(p, false).open(p);
                    case "curator" -> Contest.shop(p, true).open(p);
                    case "boosters" -> Boosters.bench(p).open(p);
                    case "jade" -> ForagingNpcs.jadeShop(p).open(p);
                    default -> ForagingMenus.main(p).open(p);
                }
                say(s, "&aOpened " + which + " for " + p.getName());
            }
            default -> say(s, "&cUnknown sub. " + SUBS);
        }
    }

    private static void hotf(CommandSender s, String[] a) {
        Player p = player(s, a, 1); if (p == null) return;
        ForagingData d = ForagingModule.data(p);
        String op = a.length > 2 ? a[2] : "info";
        switch (op) {
            case "unlock" -> { Hotf.unlock(p); say(s, "&aUnlocked HotF for " + p.getName()); }
            case "xp" -> { Hotf.addXp(p, a.length > 3 ? ForagingModule.parse(a[3], 0) : 0); say(s, "&aHotF xp now " + d.hotfXp + " (tier " + Hotf.tier(d) + ")"); }
            case "whispers" -> {
                d.forestWhispers += a.length > 3 ? ForagingModule.parse(a[3], 0) : 0;
                d.desertWhispers += a.length > 4 ? ForagingModule.parse(a[4], 0) : 0;
                say(s, "&aWhispers now " + d.forestWhispers + " / " + d.desertWhispers);
            }
            case "reset" -> {
                d.perks.clear(); d.disabledPerks.clear(); d.tokensSpent = 0; d.hotfXp = 0; d.hotfRewarded = 0; d.hotfUnlocked = false;
                Sky.stats().invalidate(p);
                say(s, "&aHotF reset for " + p.getName());
            }
            default -> say(s, "&7/sb foraging hotf <player> unlock|xp <n>|whispers <forest> [desert]|reset");
        }
    }

    static Wood parseWood(String s) {
        try { return Wood.valueOf(s.toUpperCase()); } catch (Exception e) { return Wood.FIG; }
    }

    static List<String> list(String... s) { return new ArrayList<>(List.of(s)); }
}
