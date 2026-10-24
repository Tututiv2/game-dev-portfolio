package com.skyclone.mod.mines;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.mines.gen.DeepCavernsGenerator;
import com.skyclone.mod.mines.gen.DwarvenMinesGenerator;
import com.skyclone.mod.mines.gen.GoldMineGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The mines: three islands (Gold Mine, Deep Caverns, Dwarven Mines), their mobs and spawns, the
 * NPCs that move you around them (Lift Operators, Ticket Masters) and the delver whose pass opens
 * the Dwarven Mines. The mining SYSTEMS (breaking, powder, HOTM, commissions, the Forge) belong to
 * the mining module, which finds its ores through this module's {@code ores.*} areas and NPC anchors.
 */
public final class MinesModule implements GameModule, Listener {

    MinesDials dials;
    Travel travel;
    Rides rides;
    private final List<String> mobIds = new ArrayList<>();
    private final List<SpawnRule> spawnRules = new ArrayList<>();
    /** Players standing in The Mist right now (zone events keep it; the fog task reads it). */
    private final java.util.Set<java.util.UUID> inTheMist = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override public String id() { return "mines"; }

    MinesData data(Player p) { return Sky.profiles().member(p, "mines", MinesData.class); }

    @Override
    public void enable() {
        dials = new MinesDials();
        travel = new Travel(this);
        rides = new Rides(this);

        // ---- islands and warps ----
        Sky.worlds().registerIsland(IslandDef.of(Places.GOLD, "Gold Mine", new GoldMineGenerator()));
        Sky.worlds().registerIsland(IslandDef.of(Places.DEEP, "Deep Caverns", new DeepCavernsGenerator()));
        Sky.worlds().registerIsland(IslandDef.of(Places.DWARVEN, "Dwarven Mines", new DwarvenMinesGenerator()));
        String gold = MinesDials.blankToNull(dials.goldRequirement.get());
        String deep = MinesDials.blankToNull(dials.deepRequirement.get());
        String dwarf = MinesDials.blankToNull(dials.dwarvenRequirement.get());
        Sky.worlds().registerWarp("gold_mine", Places.GOLD, "spawn", "Gold Mine", gold);
        Sky.worlds().registerWarp("gold", Places.GOLD, "spawn", "Gold Mine", gold);
        Sky.worlds().registerWarp("deep_caverns", Places.DEEP, "spawn", "Deep Caverns", deep);
        Sky.worlds().registerWarp("deep", Places.DEEP, "spawn", "Deep Caverns", deep);
        Sky.worlds().registerWarp("dwarven_mines", Places.DWARVEN, "spawn", "Dwarven Mines", dwarf);
        Sky.worlds().registerWarp("mines", Places.DWARVEN, "spawn", "Dwarven Mines", dwarf);
        Sky.worlds().registerWarp("forge", Places.DWARVEN, "station.forge", "The Forge", dwarf);
        Sky.worlds().registerWarp("camp", Places.DWARVEN, "station.base_camp", "Dwarven Base Camp", dwarf);

        // ---- mobs ----
        Sky.mobs().loadJson("mines", "mobs.json");
        Sky.mobs().loadSpawnRules("mines", "spawns.json");
        for (MobDef d : readJson("mobs.json", new TypeToken<List<MobDef>>() {})) mobIds.add(d.id);
        spawnRules.addAll(readJson("spawns.json", new TypeToken<List<SpawnRule>>() {}));
        new MineMobs(dials).register();

        // ---- NPCs ----
        for (int n = 1; n <= Places.layers(); n++) {
            final int layer = n;
            NpcDef d = NpcDef.of("mines_lift_" + n, "&fLift Operator", Places.DEEP, "npc.lift." + n).title("&e&lLIFT");
            d.hand = "IRON_CHAIN";
            d.lines.add("&7" + Places.layerName(n));
            Sky.npcs().register(d, p -> {
                if (Sky.profiles().isReady(p)) data(p).visit(layer);
                travel.openLift(p);
            });
        }
        // wave 5: the Lift Operator at the bottom of the shaft in the Dwarven Mines (reference: Lift Operator at The Lift)
        NpcDef dwLift = NpcDef.of("mines_lift_dwarven", "&fLift Operator", Places.DWARVEN, "npc.lift.dwarven").title("&e&lLIFT");
        dwLift.hand = "IRON_CHAIN";
        dwLift.lines.add("&7Up to the Deep Caverns");
        Sky.npcs().register(dwLift, travel::openLift);
        NpcDef warden = NpcDef.of("mines_hollows_warden", "&fWarden Corvenna", Places.DWARVEN, "npc.gwendolyn").title("&d&lCRYSTAL CAVERNS");
        warden.hand = "AMETHYST_SHARD";
        Sky.npcs().register(warden, this::talkWarden);
        NpcDef delver = NpcDef.of("mines_delver", "&fDelver Thornley", Places.DEEP, "npc.delver").title("&e&lGATEKEEPER");
        delver.hand = "DIAMOND_PICKAXE";
        Sky.npcs().register(delver, this::talkDelver);
        for (String st : Places.stations()) {
            NpcDef t = NpcDef.of("mines_ticket_" + st, "&fTicket Master", Places.DWARVEN, "npc.ticket." + st).title("&e&lMINECART");
            t.lines.add("&7" + Places.stationName(st));
            Sky.npcs().register(t, p -> {
                if (Sky.profiles().isReady(p) && !data(p).stations.contains(st)) data(p).stations.add(st);
                travel.openMinecart(p);
            });
        }

        registerRegulars();

        // ---- requirement keys: mines:pass, mines:layer:<n> ----
        Sky.requirements().register("mines", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2 || !Sky.profiles().isReady(p)) return false;
                MinesData d = data(p);
                return switch (parts[1]) {
                    case "pass" -> d.dwarvenPass;
                    case "hollows_pass" -> d.hollowsPass(System.currentTimeMillis());
                    case "layer" -> parts.length > 2 && d.visited(parseInt(parts[2]));
                    default -> false;
                };
            }
            @Override public String describe(String[] parts) {
                if (parts.length >= 2 && parts[1].equals("pass")) return "&athe Delver's pass to the Dwarven Mines &7(Delver Thornley, bottom of the Deep Caverns, Mining "
                        + Text.roman(dials.passMiningLevel.get()) + ")";
                if (parts.length >= 2 && parts[1].equals("hollows_pass")) return "&aa Crystal Caverns Pass &7(Warden Corvenna, Deepking's Gateway)";
                if (parts.length >= 3 && parts[1].equals("layer")) return "&avisiting the " + Places.layerName(parseInt(parts[2]));
                return "&a" + String.join(":", parts);
            }
        });

        // ---- commands ----
        Sky.commands().register(SkyCommand.of("mines", "Open the Mining Islands menu (warps, the lift, minecarts)")
                .category("Mining").playerOnly().run((s, a) -> travel.openOverview((Player) s)));
        Sky.commands().register(SkyCommand.of("lift", "Ride the lift: a Deep Caverns layer you have visited, or down to the Dwarven Mines")
                .category("Mining").playerOnly().usage("[1-6|dwarven]").run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length > 0) travel.rideLift(p, liftStop(a[0]), false);
                    else travel.openLift(p);
                }).tab((s, a) -> a.length == 1 ? List.of("1", "2", "3", "4", "5", "6", "dwarven") : List.of()));
        Sky.commands().register(SkyCommand.of("minecart", "Ride the Dwarven Mines minecart network")
                .alias("dwarventravel").category("Mining").playerOnly().usage("[station]").run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length > 0) travel.rideCart(p, a[0].toLowerCase(), false);
                    else travel.openMinecart(p);
                }).tab((s, a) -> a.length == 1 ? Places.stations() : List.of()));
        MinesAdmin.register(this);

        // ---- self-tests ----
        MinesSelfTests.register(this);

        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(rides, Sky.plugin());
        rides.startTicking();
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::drawMist, 40L, 10L);
    }

    @Override
    public void disable() { if (rides != null) rides.shutdown(); }

    /** "1".."6" = a Deep Caverns layer, "dwarven"/"mines"/"7" = The Lift in the Dwarven Mines, else -1. */
    static int liftStop(String a) {
        String s = a == null ? "" : a.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("dwarven") || s.equals("mines") || s.equals("dwarven_mines")) return Travel.DWARVEN_STOP;
        return parseInt(s);
    }

    /**
     * The caverns' entry requirement with the pass taken out: what the warden checks before she talks pass. Pure.
     * "skill:mining:12 &amp; mines:hollows_pass &amp; hotm:4" gives "skill:mining:12 &amp; hotm:4"; null or blank gives null.
     */
    static String withoutPass(String req) {
        if (req == null) return null;
        List<String> parts = new ArrayList<>();
        for (String s : req.split("&")) {
            String t = s.trim();
            if (!t.isEmpty() && !t.equals("mines:hollows_pass")) parts.add(t);
        }
        return parts.isEmpty() ? null : String.join(" & ", parts);
    }

    /**
     * Wave 5: the warden of the Crystal Caverns entrance (the reference's Gwendolyn, at the gate down from the Dwarven
     * Mines). She tells you what the caverns ask of you. When the caverns' entry requirement names
     * {@code mines:hollows_pass} (the reference's 10,000-coin, 6-hour pass) she sells it; otherwise entry is by that
     * requirement alone and she sells nothing, so nobody buys a pass that opens no door.
     */
    private void talkWarden(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        var isl = Sky.worlds().island("crystal_hollows");
        if (isl == null) {
            Sky.npcs().talk(p, "mines_hollows_warden", List.of("&fThe gate is sealed. Nothing below answers today."), null);
            return;
        }
        String req = isl.requirement;
        boolean passMode = req != null && req.contains("mines:hollows_pass");
        String other = withoutPass(req);
        if (other != null && !Sky.requirements().check(p, other)) {
            Sky.npcs().talk(p, "mines_hollows_warden", List.of(
                    "&fBelow this arch lie the &dCrystal Caverns&f: gemstones, ruins, and worse.",
                    "&fThey will not have you yet. You need " + Sky.requirements().describe(other) + "&f."), null);
            return;
        }
        if (!passMode) {
            Sky.npcs().talk(p, "mines_hollows_warden", List.of(
                    "&fBelow this arch lie the &dCrystal Caverns&f: gemstones, ruins, and worse.",
                    "&fYou've earned the way. Walk through the amethyst when you're ready."), null);
            return;
        }
        long now = System.currentTimeMillis();
        MinesData d = data(p);
        if (d.hollowsPass(now)) {
            Sky.npcs().talk(p, "mines_hollows_warden", List.of("&fYour pass holds for another &a" + Text.duration((d.hollowsPassUntil - now) / 1000) + "&f. Go on through."), null);
            return;
        }
        double price = Math.max(0, dials.hollowsPassPrice.get());
        String lasts = Text.duration((long) (Math.max(0.1, dials.hollowsPassHours.get()) * 3600));
        Sky.npcs().talk(p, "mines_hollows_warden", List.of(
                "&fBelow this arch lie the &dCrystal Caverns&f. Nobody goes down without a pass.",
                "&fA pass costs &6" + Text.commas(price) + " Coins &fand holds for &a" + lasts + "&f."), () -> {
            if (!p.isOnline()) return;
            Sky.menus().confirm(p, "Buy a Crystal Caverns Pass?", List.of("&7Cost: &6" + Text.commas(price) + " Coins", "&7Lasts: &a" + lasts),
                    () -> buyHollowsPass(p), null);
        });
    }

    /** Buy a Crystal Caverns Pass (refused while one is still running). */
    boolean buyHollowsPass(Player p) {
        MinesData d = data(p);
        long now = System.currentTimeMillis();
        if (d.hollowsPass(now)) { Msg.error(p, "You already hold a Crystal Caverns Pass!"); return false; }
        double price = Math.max(0, dials.hollowsPassPrice.get());
        if (price > 0 && !Sky.economy().withdraw(p, price, "mines: Crystal Caverns Pass")) { Msg.error(p, "You need " + Text.commas(price) + " coins for a pass."); return false; }
        d.hollowsPassUntil = now + (long) (Math.max(0.1, dials.hollowsPassHours.get()) * 3_600_000L);
        Sky.profiles().saveNow(p);
        Msg.send(p, "&aYou bought a &dCrystal Caverns Pass&a! It lasts &e" + Text.duration((d.hollowsPassUntil - now) / 1000) + "&a.");
        Msg.sound(p, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f);
        return true;
    }

    List<String> mobIds() { return mobIds; }

    List<SpawnRule> spawnRules() { return spawnRules; }

    private <T> List<T> readJson(String file, TypeToken<List<T>> type) {
        try (InputStream in = Sky.plugin().getResource("data/mines/" + file)) {
            if (in == null) return List.of();
            List<T> l = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type.getType());
            return l == null ? List.of() : l;
        } catch (Exception e) {
            Sky.log().warning("[mines] could not read " + file + ": " + e);
            return List.of();
        }
    }

    static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    // ---- where is the player ------------------------------------------------------------------------

    /** The Deep Caverns layer the player stands on (1..6), or 0 elsewhere. Nearest layer floor by height. */
    int currentLayer(Player p) {
        if (!Places.DEEP.equals(Travel.islandOf(p))) return 0;
        Layout.Zone z = Sky.worlds().zoneOf(p);
        int byZone = z == null ? 0 : Places.layerOfZone(z.id());
        if (byZone > 0) return byZone;
        double y = p.getLocation().getY();
        int best = 0;
        double bd = 1e9;
        for (int i = 0; i < DeepCavernsGenerator.FLOOR.length; i++) {
            double dd = Math.abs(y - DeepCavernsGenerator.FLOOR[i] - 1);
            if (dd < bd) { bd = dd; best = i + 1; }
        }
        return bd < 12 ? best : 0;
    }

    // ---- the delver: the pass to the Dwarven Mines ---------------------------------------------------

    /**
     * Wave 3: the Dwarven Mines' talking townsfolk (the reference fills its village, tavern and palace with
     * them). Each one teaches something a new arrival needs, so a player who clicks around the village learns
     * the island: commissions, the King's gift, mithril vs titanium, the Heart of the Mountain, where the
     * dangerous districts are and how to get around. Names are original (docs/names/mines.md).
     */
    private void registerRegulars() {
        regular("mines_barkeep", "&fBarkeep Odda", "npc.barkeep", "&e&lTAVERN", "BREAD", List.of(
                "&fMithril's thirsty work. Sit down, rest those arms.",
                "&fThe board in the plaza posts &acommissions&f. Finish them for &2Mithril Powder &fand mountain experience.",
                "&fThe King on the throne changes every day. Pay him a visit for his &6daily gift&f."));
        regular("mines_archivist", "&fArchivist Lisette", "npc.archivist", "&e&lGRAND LIBRARY", "BOOK", List.of(
                "&fGrey and blue-green stone in the mines is &2Mithril&f. The bright blue is the richest grade.",
                "&fThe pale white veins are &fTitanium&f: rare, and worth far more powder.",
                "&fThe crystal in the plaza is the &5Heart of the Mountain&f. Its Spirit trades your powder for new perks."));
        String[] guard = {
                "&fHalt! ...Ah, a miner. The King receives visitors in the throne room.",
                "&fMind the bridge. It is a long way down to the lava."};
        regular("mines_guard_1", "&6Royal Guard", "npc.guard.1", null, "IRON_SWORD", List.of(guard));
        regular("mines_guard_2", "&6Royal Guard", "npc.guard.2", null, "IRON_SWORD", List.of(guard[1], guard[0]));
        regular("mines_old_miner", "&fOld Grimble", "npc.old_miner", null, "IRON_PICKAXE", List.of(
                "&fTired legs? The &eTicket Masters &fwill run you to any minecart station in the mines.",
                "&fGoblins hold the &cGoblin Burrows&f. Thieves and knife-throwers, every one of them.",
                "&fPast the &bGreat Ice Wall &fwalk the Ice Walkers. Beyond it lies &8The Mist&f, where Ghosts drift unseen.",
                "&fOnly a blade hurts a Ghost. Arrows and spells pass right through.",
                "&fDeepking's Gateway leads further down, to the &dCrystal Caverns&f."));
        // wave 5: the Miner's Guild (reference sub-area) and its elder
        regular("mines_guild_elder", "&fElder Varrowin", "npc.guild", "&e&lMINER'S GUILD", "GOLDEN_PICKAXE", List.of(
                "&fWelcome to the Miner's Guild. Every dwarf down here started with a wooden pick.",
                "&fPowder is the mountain's thanks. Spend it at the &5Heart of the Mountain&f.",
                "&fThe &6Forge &fis slow but sure. Start a long job before you rest.",
                "&fLost? The cage at &bThe Lift &fruns up to the Deep Caverns, and the carts run everywhere else."));
    }

    private void regular(String id, String name, String anchor, String title, String hand, List<String> lines) {
        NpcDef d = NpcDef.of(id, name, Places.DWARVEN, anchor);
        if (title != null) d.title(title);
        d.hand = hand;
        Sky.npcs().register(d, p -> Sky.npcs().talk(p, id, lines, null));
    }

    private void talkDelver(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        MinesData d = data(p);
        int need = dials.passMiningLevel.get();
        if (d.dwarvenPass) {
            Sky.npcs().talk(p, "mines_delver", List.of(
                    "&fThe gate's open to you, friend. Mind the goblins down there.",
                    "&fThe dwarves pay well for mithril. Ask at the commission board."), null);
            return;
        }
        boolean ok = Sky.requirements().check(p, "skill:mining:" + need);
        if (!ok) {
            Sky.npcs().talk(p, "mines_delver", List.of(
                    "&fThis gate leads to the Dwarven Mines. I keep it.",
                    "&fThe dwarves only want miners who know their trade.",
                    "&fCome back when you have reached &aMining " + Text.roman(need) + "&f."), null);
            return;
        }
        Sky.npcs().talk(p, "mines_delver", List.of(
                "&fSo you've dug your way all the way down here.",
                "&fThat'll do. Here, take my token. Show it at the gate.",
                "&fThe Dwarven Mines are through the obsidian arch."), () -> {
            if (!p.isOnline()) return;
            MinesData dd = data(p);
            if (dd.dwarvenPass) return;
            dd.dwarvenPass = true;
            Sky.profiles().saveNow(p);
            Msg.box(p, "&a&lDWARVEN MINES UNLOCKED", List.of("&7Walk through the obsidian arch, or", "&7use &e/warp mines &7from anywhere."));
            Msg.sound(p, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
            Sky.service(Features.SkyLevel.class).award(p, "mines.dwarven_pass", dials.passSkyXp.get(), "Unlocked the Dwarven Mines");
        });
    }

    // ---- listeners -----------------------------------------------------------------------------------

    @EventHandler
    public void onZone(SkyEvents.ZoneChange e) {
        Player p = e.player;
        if (!Sky.profiles().isReady(p)) return;
        // wave 5: riding the cage PAST a layer is not visiting it (the lift only stops where you have set foot)
        if (Places.DEEP.equals(e.island) && e.to != null && !Rides.riding(p)) {
            int layer = Places.layerOfZone(e.to.id());
            if (layer > 0 && data(p).visit(layer))
                Msg.send(p, "&7The lift can now take you to the &" + Places.layerColor(layer) + Places.layerName(layer) + "&7.");
        }
        boolean inMist = Places.DWARVEN.equals(e.island) && e.to != null && "the_mist".equals(e.to.id());
        boolean leftMist = e.from != null && "the_mist".equals(e.from.id());
        if (inMist) {
            inTheMist.add(p.getUniqueId());
            Msg.send(p, "&8A thick mist closes in around you...");
            if (dials.mistBlindness.get())
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, true, false, false));
        } else if (leftMist) clearMist(p);
    }

    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) {
        if (Places.DWARVEN.equals(e.from)) clearMist(e.player);
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) { clearMist(e.player); }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { inTheMist.remove(e.getPlayer().getUniqueId()); }

    private void clearMist(Player p) {
        inTheMist.remove(p.getUniqueId());
        PotionEffect pe = p.getPotionEffect(PotionEffectType.BLINDNESS);
        if (pe != null && pe.getDuration() == PotionEffect.INFINITE_DURATION) p.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    /**
     * The Mist's fog, drawn for each player in it (only they see their own cloud, so a crowd does not
     * multiply the particle count for everyone): slow white ash everywhere within ~10 blocks and a few low
     * cloud puffs hugging the floor. Thick enough to read as fog, thin enough to keep nameplates visible.
     */
    private void drawMist() {
        if (inTheMist.isEmpty() || !dials.mistParticles.get()) return;
        for (java.util.UUID id : List.copyOf(inTheMist)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) { inTheMist.remove(id); continue; }
            Location at = p.getLocation();
            p.spawnParticle(org.bukkit.Particle.WHITE_ASH, at.clone().add(0, 1.5, 0), 70, 9, 3, 9, 0);
            p.spawnParticle(org.bukkit.Particle.CLOUD, at.clone().add(0, 0.2, 0), 6, 6, 0.3, 6, 0.005);
        }
    }

    // ---- small helpers for admin + tests -------------------------------------------------------------

    static Player player(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) s.sendMessage(Text.c("&cNo online player " + name));
        else if (!Sky.profiles().isReady(p)) { s.sendMessage(Text.c("&c" + name + "'s profile is not ready")); return null; }
        return p;
    }

    static Map<String, Location> anchorsOf(String island, String prefix) {
        Map<String, Location> out = new java.util.LinkedHashMap<>();
        Layout l = Sky.worlds().layout(island);
        if (l == null) return out;
        for (String n : l.anchors().keySet()) if (n.startsWith(prefix)) out.put(n, Sky.worlds().anchor(island, n));
        return out;
    }

    static List<DropDef> dropsOf(String mobId) {
        MobDef d = Sky.mobs().def(mobId);
        return d == null || d.drops == null ? List.of() : d.drops;
    }
}
