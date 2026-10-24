package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Player commands (each also reachable from the Desk) and {@code /sb garden ...} admin tools. */
final class GardenCommands {

    private GardenCommands() {}

    private static void player(String name, String desc, Consumer<Player> run, String... aliases) {
        Sky.commands().register(SkyCommand.of(name, desc).alias(aliases).category("Garden").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (G.ready(p)) run.accept(p); }));
    }

    static void register() {
        Sky.commands().register(SkyCommand.of("garden", "Go to your Garden, or open the Desk when you are there").category("Garden").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!G.ready(p)) return;
                    if (G.onGarden(p) && G.inOwnGarden(p)) Desk.open(p); else Sky.worlds().warp(p, "garden");
                }));
        player("desk", "Open the Garden Desk", Desk::open);
        player("composter", "Open your Garden's composter", Composter::open);
        player("coppermarket", "Open the Copper Market (garden shop)", p -> Market.open(p, "All", 0), "gardenshop");
        player("visitors", "See your Garden visitors", p -> GardenModule.visitors.openVisitors(p));
        player("pests", "See the pests in your Garden", PestMenus::open);
        player("greenhouse", "Open your Greenhouse", Greenhouse::open);
        player("cropmilestones", "See your crop milestones", p -> Desk.milestones(p).open(p));
        player("cropupgrades", "Buy crop upgrades with Copper", p -> Desk.upgrades(p).open(p));
        player("gardenlevels", "See what every Garden level gives", p -> Desk.levels(p).open(p));
        player("barnskins", "Change your barn's look", p -> Desk.skins(p).open(p));
        player("greenhouseskins", "Change your greenhouse's look", p -> Desk.greenhouseSkins(p).open(p));
        player("gardenchips", "See your garden chips", p -> Chips.menu(p).open(p));
        player("pestwarden", "Pestwarden Pell: Pesthunter gear and the Pestwarden's Bonus", p -> PestMenus.pestwarden(p).open(p));
        player("gardenbonuses", "What your accessories, equipment, pet and reforges do in the Garden", p -> Bonuses.build(p).open(p));
        Sky.commands().register(SkyCommand.of("plottp", "Teleport to one of your Garden plots").alias("tptoplot").usage("<plot number|name|barn>").category("Garden").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!G.ready(p)) return;
                    if (a.length == 0) { Desk.plots(p, true).open(p); return; }
                    if (a[0].equalsIgnoreCase("barn") || a[0].equals("0")) { Desk.teleport(p, "spawn"); return; }
                    GardenData d = G.data(p);
                    for (GardenTables.Plot pl : G.T.plots()) {
                        boolean match = a[0].equals(String.valueOf(pl.number)) || Text.strip(G.plotName(d, pl.id)).equalsIgnoreCase(String.join(" ", a));
                        if (!match) continue;
                        if (!d.plots.containsKey(pl.id)) { com.skyclone.api.ui.Msg.error(p, "You don't own Plot - " + pl.number + " yet!"); return; }
                        Desk.teleport(p, "plot." + pl.id);
                        return;
                    }
                    com.skyclone.api.ui.Msg.error(p, "No plot called " + String.join(" ", a) + ".");
                }).tab((s, a) -> {
                    List<String> out = new ArrayList<>();
                    out.add("barn");
                    for (int i = 1; i <= 24; i++) out.add(String.valueOf(i));
                    return out;
                }));

        Sky.commands().admin("garden", "<help|info|level|xp|copper|plot|visitor|pest|composter|milestone|upgrade|greenhouse|chip|skin|ghskin|open|tp|reset|effects|served|sowdust|pestbag> <player> ...",
                "Garden admin tools (try /sb garden help)", GardenCommands::admin, GardenCommands::complete);
    }

    private static List<String> complete(CommandSender s, String[] a) {
        if (a.length == 1) return List.of("help", "info", "level", "xp", "copper", "plot", "visitor", "pest", "composter", "milestone", "upgrade",
                "greenhouse", "chip", "skin", "ghskin", "open", "tp", "reset", "effects", "served", "sowdust", "pestbag");
        if (a.length == 2) { List<String> n = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) n.add(p.getName()); return n; }
        return switch (a[0]) {
            case "plot" -> a.length == 3 ? List.of("unlock", "lock") : plotIds();
            case "visitor" -> a.length == 3 ? List.of("spawn", "clear", "list") : visitorIds();
            case "pest" -> a.length == 3 ? List.of("spawn", "clear") : pestIds();
            case "composter" -> List.of("fill", "skip");
            case "milestone", "upgrade" -> cropKeys();
            case "greenhouse" -> List.of("unlock", "skip", "fill");
            case "open" -> List.of("desk", "composter", "market", "visitors", "pests", "greenhouse", "plots", "milestones", "upgrades", "levels", "skins", "ghskins", "chips", "pestwarden", "bonuses");
            case "skin" -> new ArrayList<>(BarnBuilder.SKINS.keySet());
            case "ghskin" -> new ArrayList<>(GreenhouseBuilder.SKINS.keySet());
            case "chip" -> new ArrayList<>(G.T.garden.chips.keySet());
            default -> List.of();
        };
    }

    private static List<String> plotIds() { List<String> o = new ArrayList<>(List.of("all")); for (GardenTables.Plot p : G.T.plots()) o.add(p.id); return o; }
    private static List<String> visitorIds() { List<String> o = new ArrayList<>(); for (GardenTables.Visitor v : G.T.allVisitors()) o.add(v.id); return o; }
    private static List<String> pestIds() { List<String> o = new ArrayList<>(); for (GardenTables.Pest p : G.T.pests()) o.add(p.id); return o; }
    private static List<String> cropKeys() { List<String> o = new ArrayList<>(); for (GardenTables.Crop c : G.T.crops()) o.add(c.key); return o; }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static void admin(CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equals("help")) {
            for (String l : List.of(
                    "&e/sb garden info <p> &7- level, copper, plots, visitors, pests",
                    "&e/sb garden level <p> <1-15> &7- set Garden level (rewards granted)",
                    "&e/sb garden xp <p> <amount> &7- add Garden XP",
                    "&e/sb garden copper <p> <amount> &7- add (or remove, negative) Copper",
                    "&e/sb garden plot <p> unlock|lock <plotId|all>",
                    "&e/sb garden visitor <p> spawn [visitorId] | clear | list",
                    "&e/sb garden pest <p> spawn <pest> [n] | clear",
                    "&e/sb garden composter <p> fill | skip <minutes>",
                    "&e/sb garden milestone <p> <CROP> <amount> &7- add harvested crops",
                    "&e/sb garden upgrade <p> <CROP> <tier>",
                    "&e/sb garden greenhouse <p> unlock | skip <hours> | fill",
                    "&e/sb garden chip <p> <chipItemId> <level> [chipsRedeemed] &7- rarity follows the level unless given",
                    "&e/sb garden sowdust <p> <amount> &7- add (or remove, negative) Sowdust",
                    "&e/sb garden pestbag <p> <amount> &7- add (or remove) Pests currency (the vacuum bag)",
                    "&e/sb garden skin <p> <skin> &7- rebuild the barn in a skin",
                    "&e/sb garden ghskin <p> <skin> &7- rebuild the greenhouse in a skin (no unlock check)",
                    "&e/sb garden open <p> <menu> &7- open a garden menu for them",
                    "&e/sb garden tp <p> [plotId] &7- send them to their garden",
                    "&e/sb garden reset <p> &7- wipe their profile's garden",
                    "&e/sb garden effects <p> &7- every accessory / pet / reforge effect active in the Garden",
                    "&e/sb garden served <p> <n> &7- set visitors served on their worn Peony/Blossom pieces")) say(s, l);
            return;
        }
        if (a.length < 2) { say(s, "&cUsage: /sb garden " + a[0] + " <player> ..."); return; }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null || !G.ready(p)) { say(s, "&cPlayer " + a[1] + " is not online (or not loaded)."); return; }
        GardenData d = G.data(p);
        String sub = a[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "info" -> {
                    say(s, "&aGarden of " + p.getName() + " &7(profile " + G.key(p) + ")");
                    say(s, " &7Level &a" + G.level(d) + " &7xp &a" + Text.commasInt(d.xp) + " &7copper &c" + d.copper + " &7plots &a" + d.plots.size() + " " + d.plots.keySet());
                    say(s, " &7Visitors &b" + d.visitors.size() + " &7next in &e" + Math.max(0, (d.nextVisitorAt - System.currentTimeMillis()) / 1000) + "s"
                            + " &7accepted &a" + d.offersAccepted + " &7unique &a" + d.uniqueServed());
                    say(s, " &7Pests &c" + d.livePests() + " &7cooldown &e" + Math.max(0, (d.pestCooldownUntil - System.currentTimeMillis()) / 1000) + "s"
                            + " &7bodies &c" + GardenModule.pests.bodies(G.key(p)).size());
                    Composter.advance(d);
                    say(s, " &7Composter: OM &a" + Text.commasInt(d.composter.organic) + " &7fuel &a" + Text.commasInt(d.composter.fuel) + " &7stored &a" + d.composter.stored);
                    say(s, " &7Greenhouse: " + (d.greenhouse.unlocked ? "&abuilt, " + d.greenhouse.grids + " grid(s), " + d.greenhouse.analyzed.size() + " analysed" : "&cnot built")
                            + " &7| growth queue " + GardenModule.growth.size());
                }
                case "level" -> {
                    int lvl = Math.max(1, Math.min(G.maxLevel(), Integer.parseInt(a[2])));
                    double target = GardenMath.totalXpFor(lvl, G.T.garden.levels);
                    int before = G.level(d);
                    d.xp = target;
                    if (lvl > before) G.levelUps(p, d, before, lvl); else d.levelPaid = Math.min(d.levelPaid, lvl);
                    say(s, "&aGarden level set to " + lvl);
                }
                case "xp" -> { G.gardenXp(p, d, Double.parseDouble(a[2]), "admin"); say(s, "&aGarden XP now " + Text.commasInt(d.xp)); }
                case "copper" -> { d.copper = Math.max(0, d.copper + Long.parseLong(a[2])); say(s, "&aCopper now " + d.copper); }
                case "sowdust" -> { d.sowdust = Math.max(0, d.sowdust + Double.parseDouble(a[2])); say(s, "&aSowdust now " + Math.round(d.sowdust)); }
                case "pestbag" -> {
                    GardenMember pm = G.member(p);
                    pm.pestBag = Math.max(0, pm.pestBag + Long.parseLong(a[2]));
                    say(s, "&aPests currency now " + pm.pestBag);
                }
                case "plot" -> {
                    String what = a[3];
                    List<String> ids = new ArrayList<>();
                    if (what.equals("all")) for (GardenTables.Plot pl : G.T.plots()) ids.add(pl.id); else ids.add(what);
                    int n = 0;
                    for (String id : ids) {
                        if (G.T.plot(id) == null) { say(s, "&cUnknown plot " + id); continue; }
                        if (a[2].equals("unlock") && !d.plots.containsKey(id)) { Desk.unlock(p, d, id); n++; }
                        if (a[2].equals("lock") && d.plots.remove(id) != null) n++;
                    }
                    Sky.stats().invalidate(p);
                    say(s, "&a" + a[2] + "ed " + n + " plot(s).");
                }
                case "visitor" -> {
                    switch (a[2]) {
                        case "clear" -> { d.visitors.clear(); GardenModule.visitors.syncNpcs(G.key(p), d); say(s, "&aVisitors cleared."); }
                        case "list" -> { for (GardenData.VisitorState v : d.visitors) say(s, " &7" + v.slot + ": &a" + v.id + " &7wants " + v.amount + "x " + v.item + (v.item2 == null ? "" : " + " + v.amount2 + "x " + v.item2) + " for " + v.copper + " copper"); }
                        default -> {
                            d.started = true;
                            if (a.length >= 4) {
                                GardenTables.Visitor v = G.T.visitor(a[3]);
                                if (v == null) { say(s, "&cUnknown visitor " + a[3]); return; }
                                if (d.visitors.size() >= 6) { say(s, "&cGarden is full."); return; }
                                GardenData.VisitorState st = GardenModule.visitors.makeOffer(p, d, v);
                                java.util.Set<Integer> used = new java.util.HashSet<>();
                                for (GardenData.VisitorState o : d.visitors) used.add(o.slot);
                                for (int i = 1; i <= 6; i++) if (!used.contains(i)) { st.slot = i; break; }
                                d.visitors.add(st);
                                d.log.computeIfAbsent(v.id, k -> new GardenData.VisitorLog()).visits++;
                                GardenModule.visitors.syncNpcs(G.key(p), d);
                                say(s, "&aSpawned visitor " + v.name);
                            } else say(s, GardenModule.visitors.arrive(p, d, true) ? "&aA visitor arrived." : "&cNo eligible visitor (or garden full).");
                        }
                    }
                }
                case "pest" -> {
                    if (a[2].equals("clear")) {
                        for (GardenData.PlotState ps : d.plots.values()) Pests.clearPests(ps);
                        GardenModule.pests.clearBodies(G.key(p));
                        say(s, "&aPests cleared.");
                        return;
                    }
                    String pid = a.length >= 4 ? a[3] : "fly";
                    int n = a.length >= 5 ? Integer.parseInt(a[4]) : 1;
                    if (G.T.pest(pid) == null) { say(s, "&cUnknown pest " + pid); return; }
                    int[] t = G.template(G.key(p), p.getLocation());
                    String plot = t == null ? null : G.plotAt(t[0], t[2]);
                    if (plot == null || !d.plots.containsKey(plot)) { say(s, "&cThe player must stand in one of their own plots."); return; }
                    int made = 0;
                    for (int i = 0; i < n; i++) if (GardenModule.pests.spawnBody(G.key(p), plot, pid, p.getLocation()) != null) { Pests.addPest(d.plots.get(plot), pid); made++; }
                    say(s, "&aSpawned " + made + " " + pid + ".");
                }
                case "composter" -> {
                    if (a[2].equals("fill")) {
                        d.composter.organic = Composter.organicCap(d);
                        d.composter.fuel = Composter.fuelCap(d);
                        say(s, "&aComposter filled.");
                    } else {
                        long min = Long.parseLong(a[3]);
                        Composter.advance(d);
                        d.composter.last -= min * 60_000L;
                        Composter.advance(d);
                        say(s, "&aSkipped " + min + " minutes: " + d.composter.stored + " compost waiting.");
                    }
                }
                case "milestone" -> {
                    GardenTables.Crop crop = G.T.crop(a[2].toUpperCase(Locale.ROOT));
                    if (crop == null) { say(s, "&cUnknown crop " + a[2]); return; }
                    CropListener.milestone(p, d, crop, Long.parseLong(a[3]));
                    say(s, "&a" + crop.name + " milestone count now " + d.milestone(crop.key));
                }
                case "upgrade" -> {
                    GardenTables.Crop crop = G.T.crop(a[2].toUpperCase(Locale.ROOT));
                    if (crop == null) { say(s, "&cUnknown crop " + a[2]); return; }
                    d.upgrades.put(crop.key, Math.max(0, Math.min(9, Integer.parseInt(a[3]))));
                    say(s, "&a" + crop.name + " upgrade tier set to " + d.upgrade(crop.key));
                }
                case "greenhouse" -> {
                    switch (a[2]) {
                        case "unlock" -> { d.greenhouse.unlocked = true; GreenhouseSim.ensureCells(d.greenhouse, d.greenhouse.grids); say(s, "&aGreenhouse unlocked."); }
                        case "fill" -> { for (GardenData.Cell c : d.greenhouse.cells) if (c.crop != null) c.water = G.T.greenhouse.waterMax; say(s, "&aWatered."); }
                        default -> {
                            long ms = (long) (Double.parseDouble(a[3]) * 3_600_000L);
                            for (GardenData.Cell c : d.greenhouse.cells) if (c.nextStageAt > 0) c.nextStageAt -= ms;
                            d.greenhouse.lastSim -= ms;
                            Greenhouse.tick(d);
                            say(s, "&aSkipped " + a[3] + "h of greenhouse growth.");
                        }
                    }
                }
                case "chip" -> {
                    if (!G.T.garden.chips.containsKey(a[2])) { say(s, "&cUnknown chip " + a[2]); return; }
                    // chip <id> <level> [chipsRedeemed]: without a count, enough chips for the level's rarity (1 / 5 / 21)
                    int lv = Math.max(0, Integer.parseInt(a[3]));
                    int[] caps = G.T.garden.chipRules.caps, need = G.T.garden.chipRules.chipsFor;
                    int red = 0;
                    if (a.length > 4) red = Math.max(0, Integer.parseInt(a[4]));
                    else if (lv > 0) { red = need[0]; for (int i = 0; i < caps.length; i++) if (lv > caps[i] && i + 1 < need.length) red = need[i + 1]; }
                    if (lv <= 0) { d.chips.remove(a[2]); d.chipsRedeemed.remove(a[2]); }
                    else { d.chipsRedeemed.put(a[2], Math.max(1, red)); d.chips.put(a[2], lv); }
                    Sky.stats().invalidate(p);
                    say(s, "&aChip " + a[2] + " level " + Chips.level(d, a[2]) + " (" + Text.strip(Chips.rarityName(Chips.rarity(d, a[2]))) + ", " + d.redeemed(a[2]) + " chips)");
                }
                case "skin" -> {
                    if (!BarnBuilder.SKINS.containsKey(a[2])) { say(s, "&cUnknown skin " + a[2]); return; }
                    d.barnSkin = a[2];
                    Desk.rebuildBarn(G.key(p), a[2], null);
                    say(s, "&aRebuilding the barn as " + a[2]);
                }
                case "ghskin" -> {
                    if (!GreenhouseBuilder.SKINS.containsKey(a[2])) { say(s, "&cUnknown greenhouse skin " + a[2]); return; }
                    d.greenhouseSkin = a[2];
                    Desk.rebuildGreenhouse(G.key(p), a[2], null);
                    say(s, "&aRebuilding the greenhouse as " + a[2]);
                }
                case "open" -> {
                    switch (a[2]) {
                        case "desk" -> Desk.open(p);
                        case "composter" -> Composter.open(p);
                        case "market" -> Market.open(p, "All", 0);
                        case "visitors" -> GardenModule.visitors.openVisitors(p);
                        case "pests" -> PestMenus.open(p);
                        case "pestwarden" -> PestMenus.pestwarden(p).open(p);
                        case "greenhouse" -> Greenhouse.open(p);
                        case "plots" -> Desk.plots(p, false).open(p);
                        case "milestones" -> Desk.milestones(p).open(p);
                        case "upgrades" -> Desk.upgrades(p).open(p);
                        case "levels" -> Desk.levels(p).open(p);
                        case "skins" -> Desk.skins(p).open(p);
                        case "ghskins" -> Desk.greenhouseSkins(p).open(p);
                        case "chips" -> Chips.menu(p).open(p);
                        case "bonuses" -> Bonuses.build(p).open(p);
                        default -> { say(s, "&cUnknown menu " + a[2]); return; }
                    }
                    say(s, "&aOpened " + a[2] + " for " + p.getName());
                }
                case "tp" -> {
                    if (a.length >= 3) Desk.teleport(p, a[2].equals("barn") ? "spawn" : "plot." + a[2], false);
                    else Desk.teleport(p, "spawn", false);
                    say(s, "&aSending " + p.getName() + " to their garden.");
                }
                case "reset" -> {
                    GardenModule.pests.clearBodies(G.key(p));
                    GardenModule.visitors.despawnAll(G.key(p));
                    GardenData fresh = new GardenData();
                    d.xp = 0; d.copper = 0; d.levelPaid = 1; d.started = false; d.plots.clear(); d.milestones.clear(); d.milestonePaid.clear();
                    d.upgrades.clear(); d.visitors.clear(); d.log.clear(); d.offersAccepted = 0; d.uniqueTierPaid = 0; d.acceptedTierPaid = 0;
                    d.composter = fresh.composter; d.greenhouse = fresh.greenhouse; d.traps.clear(); d.chips.clear(); d.barnSkin = "default"; d.greenhouseSkin = "default"; d.greenhouseSkins.clear();
                    d.barnSkins.clear(); d.pestCooldownUntil = 0; d.nextVisitorAt = 0; d.onceGiven.clear(); d.cropsBroken = 0; d.vinyl = null;
                    say(s, "&aGarden reset (blocks in the instance are left as they are).");
                }
                case "effects" -> {
                    say(s, "&aGarden effects of " + p.getName());
                    say(s, " &7Copper line tier &a" + Effects.tier(p, "COPPER") + " &7(" + Effects.copperRarePct(p) + "% rare visitors)"
                            + " &7Gratitude &a" + Effects.tier(p, "GRATITUDE") + " &7(" + Effects.gratitudePct(p) + "%)"
                            + " &7Bioanalysis &a" + Effects.tier(p, "BIOANALYSIS") + " &7(" + Effects.bioanalysisPct(p) + "%)");
                    say(s, " &7Season &a" + Effects.season() + " &7filter pest x&a" + Effects.filterPestMult(p) + " &7filter copper +&a" + Effects.filterCopperPct(p) + "%");
                    say(s, " &7Piece fortune &6" + Effects.pieceFortune(p, G.member(p)) + " &7pesthunter pieces &a" + Effects.pesthunterPieces(p)
                            + " &7pest cooldown &e" + Math.round(GardenModule.pests.cooldown(p)) + "s");
                    say(s, " &7Fortune vs pests &6" + Math.round(Pests.pestFortune(p)) + " &7overbloom &d" + Effects.overbloom(p, false)
                            + " &7(vs pests &d" + Effects.overbloom(p, true) + "&7)");
                    GardenMember pm = G.member(p);
                    say(s, " &7Temporary FF &6" + Effects.temporaryFortune(p) + " &7(pesthunter &6" + Effects.pesthunterFortune(pm)
                            + "&7, potions &6" + Effects.potionTemporaryFortune(p) + "&7) Pests bag &c" + pm.pestBag + " &7Sowdust &2" + Math.round(G.data(p).sowdust)
                            + " &7fair running &a" + Effects.fairCrop(p, "WHEAT"));
                    StringBuilder chips = new StringBuilder();
                    for (java.util.Map.Entry<String, GardenTables.Chip> e : G.T.garden.chips.entrySet()) {
                        int lvl = Chips.level(G.data(p), e.getKey());
                        if (lvl > 0) chips.append(" ").append(e.getValue().kind).append("=")
                                .append(Effects.fmt(lvl * e.getValue().perAt(Chips.rarity(G.data(p), e.getKey()))));
                    }
                    say(s, " &7Chips&a" + (chips.length() == 0 ? " none" : chips.toString()));
                    for (GardenTables.Crop c : G.T.crops()) {
                        double acc = Effects.accessoryCropFortune(p, c.key);
                        if (acc != 0) say(s, " &7" + c.key + " accessory fortune &6+" + acc);
                    }
                    for (String k : List.of("mosquito_smooth_jazz", "mosquito_buzzin_barterer", "mosquito_bloodsucker_s_betrayal", "rose_dragon_rosy_scales",
                            "rose_dragon_dragon_s_gluttony", "rose_dragon_spiritual_perfection", "slug_repugnant_aroma", "hedgehog_spiky_quills", "hedgehog_fearsome_farmer")) {
                        double v = Effects.pet(p, k);
                        if (v != 0) say(s, " &7pet " + k + " &a" + v);
                    }
                }
                case "served" -> {
                    long n = Math.max(0, Long.parseLong(a[2]));
                    GardenMember m = G.member(p);
                    int k = 0;
                    for (org.bukkit.inventory.ItemStack it : Effects.worn(p)) {
                        String u = Effects.uuidOf(it);
                        if (!EffectMath.isGrowingPiece(Effects.id(it)) || u == null) continue;
                        m.pieceServed.put(u, n);
                        Effects.SERVED.put(u, n);
                        Sky.items().update(it, dd -> dd.extra.addProperty("visitors_served", n));
                        k++;
                    }
                    Sky.stats().invalidate(p);
                    say(s, "&aSet " + n + " visitors served on " + k + " worn piece(s).");
                }
                default -> say(s, "&cUnknown sub-command. /sb garden help");
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            say(s, "&cMissing or bad argument. /sb garden help");
        }
    }
}
