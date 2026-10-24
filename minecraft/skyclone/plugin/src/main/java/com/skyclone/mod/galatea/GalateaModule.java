package com.skyclone.mod.galatea;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.config.Dials;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.galatea.gen.LotusAtollGenerator;
import com.skyclone.mod.galatea.gen.TorrhusCanyonGenerator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GALATEA: the islands of the Foraging and fishing updates this game was missing (research 03 §21,
 * §24; research 07). Two pure generators - the LOTUS ATOLL (fishing island 2, Fishing X, by ship from
 * the Hub) and TORRENT CANYON (ref. Torrhus Canyon, Foraging XII + HotF IV, through the Moonmarsh) -
 * plus what lives on them:
 * <ul>
 *   <li>Trophy Frogs: 12 species x 4 qualities caught while fishing on the Atoll, logged by
 *       Professor Quennell (collector titles, a title-gated shop, frogs released for Lotus - the
 *       Lotus / Silver / Gold / Diamond Lotus source), the Frogcoin wishing pool and its blessing,
 *       the Lotus Eater's shop, the Atoll rain (+25 Fishing Speed), the launch pad to the Highlands.</li>
 *   <li>Torrent Canyon's mobs (Parched, Grizzly Bear, Queen Ant and her soldiers), its NPCs, the
 *       Canyon Journal (four chapters of tasks), the Ant Overseer's minion stones, travel scrolls.</li>
 * </ul>
 * Zones are {@code lotus_atoll*} / {@code torrhus_canyon*} so the fishing module can key sea-creature
 * pools on the island id or a zone keyword.
 */
public final class GalateaModule implements GameModule {

    public static final String ATOLL = LotusAtollGenerator.ID, CANYON = TorrhusCanyonGenerator.ID;
    public static final String RAIN = "lotus_atoll_rain";

    /** Every tunable, read at use time. */
    static final class D {
        static Dials.Dial<String> atollReq, canyonReq;
        static Dials.Dial<Double> frogBase, journalScale;
        static Dials.Dial<Integer> goldPity, diamondPity, rainFs, blessingMinutes, eaterCoins, eaterMinutes, launchCooldown;
        static Dials.Dial<Boolean> frogs, announce, puddleJumperDrop, rainWeather, sidebar;
    }

    /** An NPC row from npcs.json. */
    static final class Npc {
        String id, name, island, anchor, title, role, entity;
        List<String> lines = new ArrayList<>();
        List<String> frogs = new ArrayList<>();
    }

    static final Map<String, Npc> NPCS = new LinkedHashMap<>();
    static Journal journal;
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override public String id() { return "galatea"; }

    @Override
    public void enable() {
        dials();
        Sky.items().loadJson("galatea", "items.json");
        registerFrogItems();
        Sky.recipes().loadJson("galatea", "recipes.json");
        Sky.mobs().loadJson("galatea", "mobs.json");
        Sky.mobs().loadSpawnRules("galatea", "spawns.json");
        journal = Journal.load(json("journal.json"));

        Sky.worlds().registerIsland(IslandDef.of(ATOLL, "Lotus Atoll", new LotusAtollGenerator()));
        Sky.worlds().registerIsland(IslandDef.of(CANYON, "Torrent Canyon", new TorrhusCanyonGenerator()));
        registerWarps();
        D.atollReq.onChange(v -> registerWarps());
        D.canyonReq.onChange(v -> registerWarps());

        Sky.calendar().register(CalendarEvent.realtime(RAIN, "Atoll Rain", 60, 0, 20));
        registerNpcs();
        registerRequirements();
        Sky.abilities().register("galatea_read_scroll", use -> Atoll.readScroll(use.player(), use.item()));
        Sky.stats().addProvider("galatea", Atoll::stats);

        Bukkit.getPluginManager().registerEvents(new Frogs(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Atoll(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Journal.Listen(), Sky.plugin());
        Sky.sidebar().section("galatea", 350, Atoll::sidebar);

        registerCommands();
        GalateaAdmin.register();
        GalateaTests.register();
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Atoll::tick, 100L, 100L));
    }

    @Override
    public void postEnable() {
        List<String> missing = new ArrayList<>();
        for (MobDef d : Sky.mobs().all()) {
            if (d.id == null || !d.id.startsWith("galatea_")) continue;
            d.drops.removeIf(dr -> {
                boolean gone = dr.item == null || !Sky.items().exists(dr.item);
                if (gone) missing.add(d.id + ":" + dr.item);
                return gone;
            });
        }
        if (!missing.isEmpty()) Sky.log().warning("[galatea] dropped drop lines whose item is not registered: " + missing);
    }

    @Override
    public void disable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        for (Player p : Bukkit.getOnlinePlayers()) p.resetPlayerWeather();
    }

    static GalateaData data(Player p) { return Sky.profiles().member(p, "galatea", GalateaData.class); }

    static boolean ready(Player p) { return p != null && p.isOnline() && Sky.profiles().isReady(p); }

    static boolean on(Player p, String island) { return p != null && island.equals(Sky.worlds().islandOf(p.getWorld())); }

    static JsonElement json(String file) {
        try (InputStream in = Sky.plugin().getResource("data/galatea/" + file)) {
            if (in == null) { Sky.log().warning("[galatea] missing data/galatea/" + file); return new JsonObject(); }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Sky.log().warning("[galatea] could not read " + file + ": " + e);
            return new JsonObject();
        }
    }

    // ---- dials ----------------------------------------------------------------------------------

    private static void dials() {
        Dials d = Sky.dials();
        D.atollReq = d.text("galatea.atoll-requirement", "skill:fishing:10", "Requirement to sail to / warp to the Lotus Atoll (research: Fishing X)");
        D.canyonReq = d.text("galatea.canyon-requirement", "skill:foraging:12 & hotf:4", "Requirement to enter Torrent Canyon (research: Foraging XII + Heart of the Forest IV)");
        D.frogBase = d.number("galatea.frog-chance", 0.10, "Chance a catch on the Lotus Atoll also brings a Trophy Frog, before Trophy Chance (TUNE)");
        D.goldPity = d.integer("galatea.frog-gold-pity", 100, "Frog catches without a Gold before one is guaranteed (TUNE: trophy fishing's 100)");
        D.diamondPity = d.integer("galatea.frog-diamond-pity", 600, "Frog catches without a Diamond before one is guaranteed (TUNE: trophy fishing's 600)");
        D.rainFs = d.integer("galatea.rain-fishing-speed", 25, "Fishing Speed while the Atoll rains (research: +25)");
        D.blessingMinutes = d.integer("galatea.blessing-minutes", 10, "How long a Frogcoin blessing lasts (TUNE)");
        D.eaterCoins = d.integer("galatea.lotus-eater-coins", 4, "Frogcoins thrown into the cave pool before the Lotus Eater wakes (reference: 4)");
        D.eaterMinutes = d.integer("galatea.lotus-eater-minutes", 60, "How long the Lotus Eater stays awake for you (TUNE)");
        D.launchCooldown = d.integer("galatea.launch-cooldown-seconds", 3, "Seconds between launches from Hobb's pad");
        D.journalScale = d.number("galatea.journal-reward-scale", 1.0, "Multiplier on Canyon Journal chapter rewards");
        D.frogs = d.flag("galatea.trophy-frogs", true, "Trophy Frogs bite on the Lotus Atoll");
        D.announce = d.flag("galatea.announce-diamond-frogs", true, "Tell the server when someone lands a DIAMOND frog");
        D.puddleJumperDrop = d.flag("galatea.puddle-jumper-drop", true, "The Puddle Jumper sea creature drops a Puddle Jumper trophy frog (turn off if fishing adds the drop itself)");
        D.rainWeather = d.flag("galatea.rain-weather", true, "Show rain to players on the Atoll while it rains there");
        D.sidebar = d.flag("galatea.sidebar", true, "Atoll rain / blessing and canyon journal lines on the sidebar");
    }

    static void registerWarps() {
        String a = blank(D.atollReq.get()), c = blank(D.canyonReq.get());
        Sky.worlds().registerWarp(ATOLL, ATOLL, "spawn", "Lotus Atoll", a);
        Sky.worlds().registerWarp(CANYON, CANYON, "spawn", "Torrent Canyon", c);
        Sky.worlds().registerWarp("torrhus_springs", CANYON, "warp.springs", "Torrent Springs", and(c, "galatea:scroll:torrhus_springs"));
        Sky.worlds().registerWarp("torrhus_safari", CANYON, "warp.safari", "Critter Safari Entrance", and(c, "galatea:scroll:torrhus_safari"));
    }

    static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    static String and(String a, String b) { return a == null ? b : b == null ? a : a + " & " + b; }

    // ---- frog items ---------------------------------------------------------------------------

    /** The 48 trophy frogs (reference ids; names, rarities and catch rules from NEU). Soulbound, like trophy fish. */
    private static void registerFrogItems() {
        for (FrogMath.Species s : FrogMath.SPECIES)
            for (FrogMath.Tier t : FrogMath.Tier.values()) {
                ItemDef d = new ItemDef();
                d.id = s.item(t);
                d.name = s.name() + " " + t.color + "&l" + t.label;
                d.material = "PAPER";
                d.rarity = Rarity.valueOf(s.rarity());
                d.category = ItemCategory.MATERIAL;
                d.lore = new ArrayList<>(List.of("&7" + s.hint(), "", "&7Bring this to &9Professor Quennell &7on", "&7the &dLotus Atoll&7."));
                d.tags.add("soulbound");
                d.tags.add("trophy_frog");
                d.extra.addProperty("owner", "galatea");
                d.extra.addProperty("frogTier", t.name());
                d.glow = t == FrogMath.Tier.DIAMOND;
                Sky.items().register(d);
            }
    }

    // ---- NPCs -----------------------------------------------------------------------------------

    private static void registerNpcs() {
        JsonElement root = json("npcs.json");
        if (!root.isJsonArray()) return;
        for (JsonElement e : (JsonArray) root) {
            Npc n = new Gson().fromJson(e, Npc.class);
            NPCS.put(n.id, n);
            NpcDef def = NpcDef.of(n.id, n.name, n.island, n.anchor);
            if (n.title != null) def.title(n.title);
            if (n.entity != null) def.entity(n.entity);
            Sky.npcs().register(def, p -> click(p, n));
        }
    }

    static void click(Player p, Npc n) {
        if (!ready(p)) return;
        Journal.credit(p, "talk:" + n.id, 1);
        GalateaData d = data(p);
        boolean first = d.talked.add(n.id);
        Runnable then = switch (n.role) {
            case "frogs" -> () -> GalateaMenus.professor(p);
            case "lotus_eater" -> () -> Atoll.lotusEater(p);
            case "wishing" -> () -> Atoll.toss(p);
            case "launcher" -> () -> Atoll.launch(p);
            case "vendor" -> () -> GalateaMenus.vendor(p);
            case "captain" -> () -> { if (!Sky.commands().run(p, "sail")) Sky.worlds().warp(p, "hub"); };
            case "journal" -> () -> GalateaMenus.journal(p);
            case "contest" -> () -> runOr(p, "foraging", "The contest board is closed right now.");
            case "ant" -> () -> GalateaMenus.overseer(p);
            case "hunting" -> () -> runOr(p, "hunting", null);
            case "foraging" -> () -> runOr(p, "foraging", null);
            case "fishing" -> () -> runOr(p, "fishing", null);
            case "safari", "safari_gate" -> () -> Atoll.safari(p, n);
            case "hint" -> () -> GalateaMenus.frogHints(p, n);
            default -> null;
        };
        if ("lotus_eater".equals(n.role) || "wishing".equals(n.role) || "launcher".equals(n.role)) { if (then != null) then.run(); return; }
        List<String> lines = first || then == null ? n.lines : n.lines.subList(0, Math.min(1, n.lines.size()));
        Sky.npcs().talk(p, n.id, lines, then);
    }

    static void runOr(Player p, String cmd, String fallback) {
        if (Sky.commands().exists(cmd) && Sky.commands().run(p, cmd)) return;
        if (fallback != null) Msg.error(p, fallback);
    }

    // ---- requirements ---------------------------------------------------------------------------

    private static void registerRequirements() {
        Sky.requirements().register("frogs", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (!ready(p) || parts.length < 3) return false;
                GalateaData d = data(p);
                if ("title".equals(parts[1])) return FrogMath.title(d.frogs) >= parse(parts[2]);
                if ("caught".equals(parts[1])) return d.frogs.getOrDefault(parts[2], 0) > 0;
                return false;
            }
            @Override public String describe(String[] parts) {
                if (parts.length >= 3 && "title".equals(parts[1])) {
                    int t = Math.max(1, Math.min(4, parse(parts[2])));
                    return "&a" + FrogMath.TITLES[t];
                }
                return parts.length >= 3 ? "&aCatch a " + parts[2] : "&aTrophy Frogs";
            }
        });
        Sky.requirements().register("galatea", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (!ready(p) || parts.length < 3) return false;
                GalateaData d = data(p);
                return switch (parts[1]) {
                    case "scroll" -> d.scrolls.contains(parts[2]);
                    case "journal" -> d.chaptersPaid.contains(parts[2]);
                    default -> false;
                };
            }
            @Override public String describe(String[] parts) {
                if (parts.length < 3) return "&aGalatea";
                return switch (parts[1]) {
                    case "scroll" -> "&aRead the " + Atoll.scrollName(parts[2]);
                    case "journal" -> "&aCanyon Journal: " + parts[2];
                    default -> "&a" + parts[2];
                };
            }
        });
    }

    static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return Integer.MAX_VALUE; }
    }

    // ---- commands -------------------------------------------------------------------------------

    private static void registerCommands() {
        Sky.commands().register(SkyCommand.of("galatea", "The Lotus Atoll and Torrent Canyon: travel, frogs, journal")
                .alias("lotusatoll", "atoll").category("Fishing").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (ready(p)) GalateaMenus.main(p); }));
        Sky.commands().register(SkyCommand.of("frogs", "Your Trophy Frog ledger and collector title")
                .alias("trophyfrogs", "frogledger").category("Fishing").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (ready(p)) GalateaMenus.ledger(p); }));
        Sky.commands().register(SkyCommand.of("canyonjournal", "Torrent Canyon's journal: chapters, tasks, rewards")
                .alias("torrhus", "journal").category("Foraging").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (ready(p)) GalateaMenus.journal(p); }));
    }
}
