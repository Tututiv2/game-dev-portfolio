package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** {@code /rift}, {@code /motes} and the admin tools under {@code /sb rift}. Every admin sub takes a player name (RCON-testable). */
final class RiftCommands {

    private RiftCommands() {}

    static void register(RiftModule m) {
        Sky.commands().register(SkyCommand.of("rift", "The Rift: your Rift stats, souls, Timecharms and areas")
                .usage("[souls|charms|areas|eyes|cat|leave|shop|enter]").category("Islands").playerOnly()
                .tab((s, a) -> a.length <= 1 ? List.of("souls", "charms", "areas", "eyes", "cat", "leave", "shop", "enter") : List.of())
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!Sky.profiles().isReady(p)) return;
                    String sub = a.length == 0 ? "" : a[0].toLowerCase();
                    switch (sub) {
                        case "souls" -> m.menus.souls(p);
                        case "charms", "timecharms" -> m.menus.charms(p);
                        case "areas", "gates" -> m.menus.areas(p);
                        case "eyes", "travel" -> {
                            if (m.session(p) == null) { Msg.error(p, "The Warden's eyes only see inside the Rift."); return; }
                            m.cat.eyesMenu(p);
                        }
                        case "cat", "pharaoh" -> m.cat.menu(p);
                        case "leave" -> {
                            if (m.session(p) == null) { Msg.error(p, "You are not in the Rift."); return; }
                            Sky.menus().confirm(p, "Leave the Rift?", List.of("&7Your remaining Rift Time will be lost."),
                                    () -> m.travel.eject(p, "You left the Rift."), null);
                        }
                        case "shop", "sell" -> {
                            if (m.session(p) == null) { Msg.error(p, "The Mote Monger only trades inside the Rift."); return; }
                            m.menus.shop(p, "mote_monger");
                        }
                        case "enter" -> {
                            if (m.session(p) != null) { m.menus.overview(p); return; }
                            m.menus.entry(p);
                        }
                        default -> m.menus.overview(p);
                    }
                }));
        Sky.commands().register(SkyCommand.of("motes", "Show your Motes (the Rift's currency)").category("Islands").playerOnly()
                .run((s, a) -> {
                    RiftData d = m.data((Player) s);
                    if (d == null) return;
                    Msg.send((Player) s, "&7Motes: &d" + Text.commasInt(d.motes) + " &8(lifetime " + Text.commasInt(d.lifetimeMotes) + ")");
                }));

        Sky.commands().admin("rift", "<info|enter|leave|time|motes|charm|souls|infusions|boss|heart|tp|quest|reset|stats|hubnpc|gallery|splitsteal|riddle|cat|eyes|cloak> <player> ...",
                "Rift tools: sessions, Rift Time, Motes, Timecharms, souls, bosses",
                (s, a) -> admin(m, s, a),
                (s, a) -> {
                    if (a.length <= 1) return List.of("info", "enter", "leave", "time", "motes", "charm", "souls", "infusions", "boss", "heart", "tp", "quest", "reset", "stats", "hubnpc", "gallery", "splitsteal", "riddle", "cat", "eyes", "cloak");
                    if (a.length == 2) { List<String> n = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) n.add(p.getName()); return n; }
                    return switch (a[0]) {
                        case "charm" -> { List<String> l = new ArrayList<>(List.of("all", "reset")); for (RiftCatalog.Charm c : RiftCatalog.CHARMS) l.add(c.id()); yield l; }
                        case "boss" -> List.of("leech", "bloatmass", "lord");
                        case "motes" -> List.of("give", "take", "set");
                        case "souls" -> List.of("all", "reset");
                        case "quest" -> List.of("wilma", "paperwork", "mirror", "race", "vermin");
                        case "gallery" -> List.of("all", "reset");
                        case "riddle" -> { List<String> l = new ArrayList<>(List.of("list", "all", "reset")); for (RiftEnigma.Riddle r : RiftEnigma.RIDDLES) l.add(r.id()); yield l; }
                        case "cat", "eyes" -> List.of("all", "reset", "1", "2", "3");
                        case "cloak" -> List.of("claim", "reset");
                        default -> List.of();
                    };
                });
    }

    private static void admin(RiftModule m, CommandSender s, String[] a) {
        if (a.length == 0) { s.sendMessage(Text.c("&e/sb rift info|enter|leave|time|motes|charm|souls|infusions|boss|heart|tp|quest|reset|stats|hubnpc <player> ...")); return; }
        String sub = a[0].toLowerCase();
        if (sub.equals("heart")) { m.puzzles.placeHeart(); s.sendMessage(Text.c("&aLiving Heart moved to " + m.puzzles.heartLocation())); return; }
        if (sub.equals("hubnpc")) { m.npcs.placeHubCopy(); s.sendMessage(Text.c("&aWarden copy placed in the hub.")); return; }
        if (a.length < 2) { s.sendMessage(Text.c("&cUsage: /sb rift " + sub + " <player> ...")); return; }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null) { s.sendMessage(Text.c("&cNo such player online.")); return; }
        RiftData d = m.data(p);
        if (d == null) { s.sendMessage(Text.c("&cProfile not ready.")); return; }
        switch (sub) {
            case "info" -> {
                RiftSession ss = m.session(p);
                RiftStats.Sheet sh = m.stats.sheet(p);
                s.sendMessage(Text.c("&d" + p.getName() + " &7in rift: &f" + m.inRift(p) + " &7session: &f" + (ss != null) + " &7rift inventory live: &f" + RiftInventory.inside(p)
                        + " &7parked items: &f" + RiftInventory.parkedCount(p)));
                if (ss != null) s.sendMessage(Text.c("&7  time left &a" + RiftMath.clock(ss.remaining) + " &7gates " + ss.gates + " &7drain " + m.travel.drainHere(p)));
                s.sendMessage(Text.c("&7  max time &a" + RiftMath.clock(sh.time()) + " &7dmg &5" + sh.damage() + " &7hearts &c" + sh.hearts()
                        + " &7int &b" + sh.intelligence() + " &7speed &f" + sh.speed() + " &7sources " + sh.timeSources()));
                s.sendMessage(Text.c("&7  motes &d" + d.motes + " &7(lifetime " + d.lifetimeMotes + ") infusions &d" + m.travel.infusions(p)
                        + " &7souls &d" + d.souls.size() + " &7charms &a" + d.charms + " &7mirror " + d.mirror + " &7wilma " + d.wilma
                        + " &7paperwork " + d.paperwork + " &7vermin " + d.vermin + " &7hearts " + d.heartsCaught + " &7gallery " + d.gallery + " &7split/steal " + d.splitStealRounds));
            }
            case "enter" -> {
                if (m.session(p) != null) { s.sendMessage(Text.c("&cAlready inside.")); return; }
                Location spawn = Sky.worlds().anchor(RiftCatalog.ISLAND, "spawn");
                if (spawn == null || !Sky.worlds().isBuilt(RiftCatalog.ISLAND)) { s.sendMessage(Text.c("&cThe Rift is not built.")); return; }
                m.travel.startSession(p, RiftTravel.Pay.FREE);
                p.teleport(spawn);
                s.sendMessage(Text.c("&aSent " + p.getName() + " into the Rift (free)."));
            }
            case "leave" -> { m.travel.eject(p, "An admin pulled you out of the Rift."); s.sendMessage(Text.c("&aDone.")); }
            case "time" -> {
                RiftSession ss = m.session(p);
                if (ss == null || a.length < 3) { s.sendMessage(Text.c("&cUsage: /sb rift time <player> <seconds> (player must be inside)")); return; }
                ss.remaining = parse(a[2]);
                s.sendMessage(Text.c("&aRift Time set to " + RiftMath.clock(ss.remaining)));
            }
            case "motes" -> {
                if (a.length < 4) { s.sendMessage(Text.c("&cUsage: /sb rift motes <player> give|take|set <n>")); return; }
                long n = (long) parse(a[3]);
                if (n < 0) { s.sendMessage(Text.c("&cAmount must be positive.")); return; }
                switch (a[2]) {
                    case "give" -> m.addMotes(p, n, "admin");
                    case "take" -> d.motes = Math.max(0, d.motes - n);
                    case "set" -> d.motes = n;
                    default -> { s.sendMessage(Text.c("&cgive|take|set")); return; }
                }
                s.sendMessage(Text.c("&a" + p.getName() + " now has " + d.motes + " Motes."));
            }
            case "charm" -> {
                if (a.length < 3) { s.sendMessage(Text.c("&cUsage: /sb rift charm <player> <id|all|reset>")); return; }
                if (a[2].equals("reset")) d.charms.clear();
                else if (a[2].equals("all")) for (RiftCatalog.Charm c : RiftCatalog.CHARMS) m.puzzles.grantCharm(p, c.id());
                else if (RiftCatalog.charm(a[2]) != null) m.puzzles.grantCharm(p, a[2]);
                else { s.sendMessage(Text.c("&cUnknown charm.")); return; }
                m.stats.invalidate(p);
                s.sendMessage(Text.c("&aCharms: " + d.charms));
            }
            case "souls" -> {
                if (a.length < 3) { s.sendMessage(Text.c("&cUsage: /sb rift souls <player> <all|reset|n>")); return; }
                if (a[2].equals("reset")) d.souls.clear();
                else if (a[2].equals("all")) d.souls.addAll(RiftCatalog.soulIds());
                else d.souls.add("soul." + (int) parse(a[2]));
                m.stats.invalidate(p);
                s.sendMessage(Text.c("&aSouls: " + d.souls.size()));
            }
            case "infusions" -> {
                if (a.length < 3) { s.sendMessage(Text.c("&cUsage: /sb rift infusions <player> <n>")); return; }
                d.infusions = Math.max(0, (int) parse(a[2]));
                d.infusionClock = System.currentTimeMillis();
                s.sendMessage(Text.c("&aInfusions: " + d.infusions));
            }
            case "boss" -> {
                if (a.length < 3) { s.sendMessage(Text.c("&cUsage: /sb rift boss <player> leech|bloatmass|lord")); return; }
                String[] b = switch (a[2]) {
                    case "leech" -> new String[]{"rift_leech_sovereign", "boss.leech"};
                    case "bloatmass", "bacte" -> new String[]{"rift_bloatmass", "boss.bacte"};
                    case "lord" -> new String[]{"rift_chateau_lord", "boss.chateau"};
                    default -> null;
                };
                if (b == null) { s.sendMessage(Text.c("&cleech|bloatmass|lord")); return; }
                boolean ok = m.combat.summon(p, b[0], b[1], 0);
                s.sendMessage(Text.c(ok ? "&aSummoned." : "&cCould not summon (player must be inside, one alive at a time)."));
            }
            case "tp" -> {
                if (a.length < 3) { s.sendMessage(Text.c("&cUsage: /sb rift tp <player> <anchor>")); return; }
                Location l = Sky.worlds().anchor(RiftCatalog.ISLAND, a[2]);
                if (l == null) { s.sendMessage(Text.c("&cNo anchor " + a[2])); return; }
                if (m.session(p) == null) m.travel.startSession(p, RiftTravel.Pay.FREE);
                p.teleport(l);
                s.sendMessage(Text.c("&aTeleported to " + a[2]));
            }
            case "quest" -> {
                if (a.length < 3) { s.sendMessage(Text.c("&cUsage: /sb rift quest <player> wilma|paperwork|mirror|race|vermin")); return; }
                switch (a[2]) {
                    case "wilma" -> { d.wilma = 2; d.eggs.clear(); for (int i = 1; i <= RiftCatalog.EGGS; i++) d.eggs.add("egg." + i); }
                    case "paperwork" -> d.paperwork = 5;
                    case "mirror" -> { d.mirror.add("parkour"); d.mirror.add("maze"); d.mirror.add("dance"); m.puzzles.grantCharm(p, "mirror"); }
                    case "race" -> d.raceBest = 1;
                    case "vermin" -> d.vermin = m.dials.verminNeeded.get();
                    default -> { s.sendMessage(Text.c("&cwilma|paperwork|mirror|race|vermin")); return; }
                }
                s.sendMessage(Text.c("&aQuest step advanced."));
            }
            case "reset" -> {
                Sky.profiles().member(p, "rift", RiftData.class);
                RiftData fresh = new RiftData();
                d.motes = 0; d.lifetimeMotes = 0; d.souls.clear(); d.charms.clear(); d.motesTaken.clear(); d.wilma = 0; d.eggs.clear();
                d.paperwork = 0; d.heartHits = 0; d.heartsCaught = 0; d.mirror.clear(); d.raceBest = 0; d.vermin = 0; d.flags.clear();
                d.infusions = fresh.infusions; d.infusionClock = 0; d.kills.clear();
                d.riddles.clear(); d.riddleRetry.clear(); d.catStage = 0; d.catPieces.clear(); d.catActive = true; d.eyes.clear(); d.cloakSouls = 0;
                d.gallery.clear(); d.splitStealAt = 0; d.splitStealRounds = 0; d.splitStealSplits = 0; d.splitStealMotes = 0; d.splitStealLast = null; d.odonataCaught = 0;
                m.stats.invalidate(p);
                s.sendMessage(Text.c("&aRift progress reset for " + p.getName()));
            }
            case "stats" -> {
                RiftStats.Sheet sh = m.stats.sheet(p);
                s.sendMessage(Text.c("&7Rift sheet: time " + sh.time() + " dmg " + sh.damage() + " hearts " + sh.hearts() + " int " + sh.intelligence()
                        + " speed " + sh.speed() + " regen " + sh.manaRegen()));
            }
            case "gallery" -> {
                if (a.length > 2 && a[2].equalsIgnoreCase("reset")) d.gallery.clear(); else m.gallery.secureAll(p);
                m.stats.invalidate(p);
                s.sendMessage(Text.c("&aGallery: " + d.gallery.size() + "/" + RiftCatalog.CHARMS.size() + " secured " + d.gallery));
            }
            case "riddle" -> {
                if (a.length < 3) { s.sendMessage(Text.c("&cUsage: /sb rift riddle <player> <id|all|reset|list>")); return; }
                switch (a[2]) {
                    case "list" -> {
                        for (RiftEnigma.Riddle r : RiftEnigma.RIDDLES) {
                            RiftRiddles.Puzzle pz = m.riddles.puzzle(r.id());
                            String soul = pz == null ? "?" : pz.soul;
                            s.sendMessage(Text.c("&7" + r.id() + " &8" + r.kind() + " &7" + soul + (pz != null && pz.degraded() ? " &c(no props: walk-in)" : "")
                                    + (d.riddles.contains(r.id()) ? " &asolved" : "") + (pz != null && d.souls.contains(pz.soul) ? " &dfound" : "")));
                        }
                    }
                    case "reset" -> { d.riddles.clear(); d.riddleRetry.clear(); s.sendMessage(Text.c("&aRiddles reset (souls kept).")); }
                    case "all" -> { for (RiftEnigma.Riddle r : RiftEnigma.RIDDLES) m.riddles.adminSolve(p, r.id()); s.sendMessage(Text.c("&aEvery riddle solved.")); }
                    default -> s.sendMessage(Text.c(m.riddles.adminSolve(p, a[2]) ? "&aSolved " + a[2] : "&cUnknown riddle id (see /sb rift riddle <p> list)."));
                }
            }
            case "cat", "eyes" -> {
                boolean cat = sub.equals("cat");
                int max = cat ? RiftCatalog.CAT_PIECES : RiftCatalog.EYES;
                java.util.Set<Integer> set = cat ? d.catPieces : d.eyes;
                String arg = a.length < 3 ? "info" : a[2];
                switch (arg) {
                    case "reset" -> { set.clear(); if (cat) { d.catStage = 0; d.flags.remove("cat_pet_given"); } }
                    case "all" -> { for (int i = 1; i <= max; i++) set.add(i); if (cat) d.catStage = 1; }
                    case "info" -> { }
                    default -> { int n = (int) parse(arg); if (n >= 1 && n <= max) set.add(n); if (cat) d.catStage = Math.max(1, d.catStage); }
                }
                m.stats.invalidate(p);
                s.sendMessage(Text.c("&a" + (cat ? "Pharaoh's pieces" : "Warden's eyes") + ": " + set));
            }
            case "cloak" -> {
                if (a.length > 2 && a[2].equals("reset")) { d.cloakSouls = 0; d.flags.remove("cloak_given"); }
                else d.cloakSouls = RiftEnigma.cloakClaimable(d.souls.size());
                m.stats.invalidate(p);
                s.sendMessage(Text.c("&aEnigma Cloak holds " + d.cloakSouls + " souls."));
            }
            case "splitsteal" -> { m.splitSteal.reset(p); s.sendMessage(Text.c("&aSplit or Steal cooldown cleared for " + p.getName() + " (" + m.splitSteal.waitingCount() + " waiting).")); }
            default -> s.sendMessage(Text.c("&cUnknown sub."));
        }
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }
}
