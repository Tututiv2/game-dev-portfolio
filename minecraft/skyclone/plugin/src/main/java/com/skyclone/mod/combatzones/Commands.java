package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.combatzones.gen.DenGenerator;
import com.skyclone.mod.combatzones.gen.EndGenerator;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Player commands and the /sb combatzones admin tool (RCON friendly: every sub takes a player name). */
final class Commands {

    private Commands() {}

    static void register(CombatzonesModule m) {
        Sky.commands().register(SkyCommand.of("combatzones", "Open the Combat Zones menu (Spider's Den, The End, bosses)")
                .alias("zones", "cz").category("Combat").playerOnly()
                .run((s, a) -> m.menus.main((Player) s).open((Player) s)));
        Sky.commands().register(SkyCommand.of("dragon", "Dragon's Nest: variants, altar status and loot quality")
                .alias("dragons").category("Combat").playerOnly()
                .run((s, a) -> m.menus.dragon((Player) s).open((Player) s)));
        Sky.commands().register(SkyCommand.of("silkmother", "The Silkmother's altar, summoning items and loot")
                .alias("arachne").category("Combat").playerOnly()
                .run((s, a) -> m.menus.silkmother((Player) s).open((Player) s)));
        // ---- wave 5
        Sky.commands().register(SkyCommand.of("draconicaltar", "The Draconic Altar: sacrifice dragon loot for Dragon Essence")
                .alias("draconic").category("Combat").playerOnly()
                .run((s, a) -> m.essence.altar((Player) s).open((Player) s)));
        Sky.commands().register(SkyCommand.of("dragonperks", "Keeper Ixo's Dragon Essence shop (permanent perks)")
                .alias("dragonessence", "ixo").category("Combat").playerOnly()
                .run((s, a) -> m.essence.shop((Player) s).open((Player) s)));
        Sky.commands().register(SkyCommand.of("tavi", "Wanderer Tavi: End kill quests and the Void Sword")
                .alias("adventurer", "endquests").category("Combat").playerOnly()
                .run((s, a) -> m.tavi.menu((Player) s).open((Player) s)));
        Sky.commands().register(SkyCommand.of("mobtypes", "Mob types: what each type is and which you have slain")
                .alias("mobtype").category("Combat").playerOnly()
                .run((s, a) -> m.mobTypes.menu((Player) s).open((Player) s)));

        Sky.commands().admin("combatzones",
                "status | dragon spawn <variant>|fill <p> [n]|reset|kill | brood spawn|reset | silkmother <300|500> | colossus add <n>|spawn"
                        + " | weather rain|thunder|clear|auto | scroll <p> <id|all> | loot <variant> <eyes> <place> | open <p> main|dragon|silkmother|scrolls|keeper|altar|perks|tavi|mobtypes"
                        + " | essence <p> <n> | perk <p> <perkId> <level> | quest <p> <index> [progress] | mobtypes <p> reset | altar <item> [n]",
                "Combat zones admin: bosses, altar, weather, scrolls, loot simulation",
                (s, a) -> run(m, s, a),
                (s, a) -> complete(a));
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static void run(CombatzonesModule m, CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("status")) {
            say(s, "&6Combat zones");
            say(s, " &7Den built: " + Sky.worlds().isBuilt(DenGenerator.ID) + " &7End built: " + Sky.worlds().isBuilt(EndGenerator.ID));
            say(s, " &7Broodmother: " + m.bosses.broodLabel() + " &7(stage " + m.bosses.broodStage() + ")");
            say(s, " &7Silkmother: " + (m.arachne.busy() ? "&cawake Lv" + m.arachne.level() : "&acallings " + m.arachne.callings.size()));
            say(s, " &7Dragon: " + m.fight.state + (m.fight.variant != null ? " " + m.fight.variant.name() : "") + " eyes " + m.fight.eyesPlaced() + "/8 damagers " + m.fight.damage.size());
            say(s, " &7Colossus: mined " + m.bosses.endStoneMined + " stage " + DragonMath.protectorStage(m.bosses.endStoneMined, m.dials.protectorPerStage.get()) + (m.bosses.colossusId != null ? " &cALIVE" : ""));
            say(s, " &7Weather: rain=" + m.bosses.raining() + " thunder=" + m.bosses.thundering());
            if (!m.prunedDrops.isEmpty()) say(s, " &cPruned drop ids (owner missing): " + m.prunedDrops);
            return;
        }
        switch (a[0].toLowerCase()) {
            case "dragon" -> {
                String sub = a.length > 1 ? a[1].toLowerCase() : "";
                switch (sub) {
                    case "spawn" -> {
                        String v = a.length > 2 ? a[2].toLowerCase() : null;
                        if (v != null && DragonMath.variant(v) == null) { say(s, "&cUnknown variant " + v); return; }
                        if (m.fight.state != DragonFight.State.IDLE) { say(s, "&cA fight is already running (" + m.fight.state + ")."); return; }
                        say(s, m.fight.spawnDragon(v) ? "&aDragon spawned: " + m.fight.variant.name() : "&cCould not spawn (island built?)");
                    }
                    case "fill" -> {
                        Player p = player(s, a, 2);
                        if (p == null) return;
                        int n = a.length > 3 ? CombatzonesModule.parseInt(a[3]) : 8;
                        m.fight.fillFor(p, n);
                        say(s, "&aAltar now " + m.fight.eyesPlaced() + "/8");
                    }
                    case "reset" -> {
                        LivingEntity d = m.fight.dragon();
                        if (d != null) Sky.mobs().remove(d);
                        m.fight.resetAltar();
                        say(s, "&aDragon fight reset (placed eyes kept).");
                    }
                    case "kill" -> {
                        LivingEntity d = m.fight.dragon();
                        if (d == null) { say(s, "&cNo dragon."); return; }
                        Sky.mobs().setHealth(d, 0);
                        say(s, "&aDragon killed (loot paid by quality).");
                    }
                    default -> say(s, "&cdragon spawn [variant] | fill <player> [n] | reset | kill");
                }
            }
            case "brood" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("reset")) { m.bosses.broodSlainAt = System.currentTimeMillis(); say(s, "&aBroodmother clock reset to Slain."); }
                else { m.bosses.spawnBrood(); say(s, "&aBroodmother will rise next second if a player is near the nest."); }
            }
            case "silkmother" -> {
                int lvl = a.length > 1 && a[1].equals("500") ? 500 : 300;
                if (m.arachne.busy()) { say(s, "&cShe is already awake."); return; }
                Player p = s instanceof Player pl ? pl : null;
                m.arachne.summon(lvl, p);
                say(s, "&aSilkmother Lv" + lvl + " summoning (5 s).");
            }
            case "colossus" -> {
                if (a.length > 2 && a[1].equalsIgnoreCase("add")) {
                    m.bosses.endStoneMined += Math.max(0, CombatzonesModule.parseInt(a[2]));
                    say(s, "&aMined now " + m.bosses.endStoneMined);
                } else if (a.length > 1 && a[1].equalsIgnoreCase("spawn")) { m.bosses.spawnColossus(); say(s, "&aColossus spawned (if the End is built)."); }
                else say(s, "&ccolossus add <n> | spawn");
            }
            case "weather" -> {
                String w = a.length > 1 ? a[1].toLowerCase() : "auto";
                switch (w) {
                    case "rain" -> { Sky.calendar().force(WorldBosses.RAIN, true); Sky.calendar().force(WorldBosses.THUNDER, false); }
                    case "thunder" -> { Sky.calendar().force(WorldBosses.RAIN, true); Sky.calendar().force(WorldBosses.THUNDER, true); }
                    case "clear" -> { Sky.calendar().force(WorldBosses.RAIN, false); Sky.calendar().force(WorldBosses.THUNDER, false); }
                    default -> say(s, "&7Use /sb calendar force <id> clear to return to the schedule.");
                }
                m.bosses.applyWeather();
                say(s, "&aWeather: rain=" + m.bosses.raining() + " thunder=" + m.bosses.thundering());
            }
            case "scroll" -> {
                Player p = player(s, a, 1);
                if (p == null || a.length < 3) { say(s, "&cscroll <player> <nest|sanctuary|drag|void|all>"); return; }
                CzData d = m.data(p);
                if (a[2].equalsIgnoreCase("all")) for (String[] sc : ZoneMenus.SCROLLS) d.scrolls.add(sc[0]);
                else d.scrolls.add(a[2].toLowerCase());
                say(s, "&a" + p.getName() + " scrolls: " + d.scrolls);
            }
            case "loot" -> {
                if (a.length < 4) { say(s, "&cloot <variant> <eyes> <place>"); return; }
                String v = a[1].toLowerCase();
                int eyes = CombatzonesModule.parseInt(a[2]), place = CombatzonesModule.parseInt(a[3]);
                int q = DragonMath.quality(eyes, place);
                Random r = new Random();
                java.util.Map<String, Integer> tally = new java.util.TreeMap<>();
                for (int i = 0; i < 1000; i++) {
                    DragonMath.Award aw = DragonMath.award(v, q, eyes, r, Sky.items()::exists, m.dials.dragonMaxFragments.get());
                    tally.merge(aw.item() == null ? aw.fragments() + "x fragment" : aw.item(), 1, Integer::sum);
                }
                say(s, "&aQuality " + q + " (" + eyes + " eyes, place " + place + "), 1000 rolls:");
                tally.forEach((k, n) -> say(s, " &7" + k + ": &e" + n));
            }
            case "open" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                String what = a.length > 2 ? a[2].toLowerCase() : "main";
                switch (what) {
                    case "dragon" -> m.menus.dragon(p).open(p);
                    case "silkmother" -> m.menus.silkmother(p).open(p);
                    case "scrolls" -> m.menus.scrolls(p).open(p);
                    case "loot" -> m.menus.dragonLoot(p).open(p);
                    case "keeper" -> m.essence.keeper(p).open(p);
                    case "altar" -> m.essence.altar(p).open(p);
                    case "perks" -> m.essence.shop(p).open(p);
                    case "tavi" -> m.tavi.menu(p).open(p);
                    case "mobtypes" -> m.mobTypes.menu(p).open(p);
                    default -> m.menus.main(p).open(p);
                }
                say(s, "&aOpened " + what + " for " + p.getName());
            }
            // ---- wave 5
            case "essence" -> {
                Player p = player(s, a, 1);
                if (p == null || a.length < 3) { say(s, "&cessence <player> <n>"); return; }
                long n = Math.max(0, CombatzonesModule.parseInt(a[2]));
                m.essence.give(p, n, "admin");
                say(s, "&a" + p.getName() + " Dragon Essence now " + m.essence.balance(p));
            }
            case "perk" -> {
                Player p = player(s, a, 1);
                if (p == null || a.length < 4) { say(s, "&cperk <player> <perkId> <level>"); return; }
                DraconicMath.Perk pk = DraconicMath.perk(a[2]);
                if (pk == null) { say(s, "&cUnknown perk. Ids: " + DraconicMath.PERKS.stream().map(DraconicMath.Perk::id).toList()); return; }
                int lvl = Math.max(0, Math.min(pk.max(), CombatzonesModule.parseInt(a[3])));
                if (lvl == 0) m.data(p).dragonPerks.remove(pk.id()); else m.data(p).dragonPerks.put(pk.id(), lvl);
                Sky.stats().invalidate(p);
                say(s, "&a" + p.getName() + " " + pk.name() + " = " + lvl + " (value " + m.essence.value(p, pk.id()) + ")");
            }
            case "quest" -> {
                Player p = player(s, a, 1);
                if (p == null || a.length < 3) { say(s, "&cquest <player> <index 0-" + ZoneQuests.QUESTS.size() + "> [progress]"); return; }
                CzData d = m.data(p);
                d.taviQuest = Math.max(0, Math.min(ZoneQuests.QUESTS.size(), CombatzonesModule.parseInt(a[2])));
                d.taviProgress = a.length > 3 ? Math.max(0, CombatzonesModule.parseInt(a[3])) : 0;
                ZoneQuests.Quest q = ZoneQuests.at(d.taviQuest);
                say(s, "&a" + p.getName() + " Tavi quest " + d.taviQuest + (q == null ? " (chain done)" : " " + q.id() + " " + d.taviProgress + "/" + q.count()));
            }
            case "mobtypes" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                if (a.length > 2 && a[2].equalsIgnoreCase("reset")) m.data(p).mobTypes.clear();
                say(s, "&a" + p.getName() + " mob types slain: " + m.data(p).mobTypes);
            }
            case "altar" -> {
                if (a.length < 2) { say(s, "&caltar <ITEM_ID> [n] (prints what the altar pays)"); return; }
                int n = a.length > 2 ? Math.max(1, CombatzonesModule.parseInt(a[2])) : 1;
                long ess = DraconicMath.essenceFor(a[1], n, m.dials.altarScale.get());
                double ch = DraconicMath.bonusChance(ess, m.dials.altarBonusPerEssence.get(), m.dials.altarBonusCap.get(), 0);
                say(s, ess <= 0 ? "&cThe altar refuses " + a[1] : "&a" + n + "x " + a[1] + " -> " + ess + " Dragon Essence, bonus chance " + Text.commas(ch * 100) + "%");
            }
            default -> say(s, "&cUnknown sub. /sb combatzones status");
        }
    }

    private static Player player(CommandSender s, String[] a, int i) {
        Player p = a.length > i ? Bukkit.getPlayerExact(a[i]) : (s instanceof Player pl ? pl : null);
        if (p == null) s.sendMessage(Text.c("&cPlayer not online."));
        else if (!Sky.profiles().isReady(p)) { s.sendMessage(Text.c("&cProfile not ready.")); return null; }
        return p;
    }

    private static List<String> complete(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length <= 1) out.addAll(List.of("status", "dragon", "brood", "silkmother", "colossus", "weather", "scroll", "loot", "open",
                "essence", "perk", "quest", "mobtypes", "altar"));
        else if (a.length == 2) switch (a[0].toLowerCase()) {
            case "dragon" -> out.addAll(List.of("spawn", "fill", "reset", "kill"));
            case "brood" -> out.addAll(List.of("spawn", "reset"));
            case "silkmother" -> out.addAll(List.of("300", "500"));
            case "colossus" -> out.addAll(List.of("add", "spawn"));
            case "weather" -> out.addAll(List.of("rain", "thunder", "clear", "auto"));
            case "loot" -> { for (DragonMath.Variant v : DragonMath.VARIANTS) out.add(v.id()); }
            case "scroll", "open", "essence", "perk", "quest", "mobtypes" -> { for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); }
            case "altar" -> out.addAll(DraconicMath.SACRIFICE.keySet());
            default -> {}
        }
        else if (a.length == 3 && a[0].equalsIgnoreCase("dragon") && a[1].equalsIgnoreCase("spawn")) { for (DragonMath.Variant v : DragonMath.VARIANTS) out.add(v.id()); }
        else if (a.length == 3 && a[0].equalsIgnoreCase("scroll")) out.addAll(List.of("nest", "sanctuary", "drag", "void", "all"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("open")) out.addAll(List.of("main", "dragon", "silkmother", "scrolls", "loot", "keeper", "altar", "perks", "tavi", "mobtypes"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("perk")) { for (DraconicMath.Perk pk : DraconicMath.PERKS) out.add(pk.id()); }
        return out;
    }
}
