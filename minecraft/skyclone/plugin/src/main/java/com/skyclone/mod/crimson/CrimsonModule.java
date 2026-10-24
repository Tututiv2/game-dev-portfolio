package com.skyclone.mod.crimson;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.config.Dials;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.crimson.gen.CrimsonIsleGenerator;
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
 * CRIMSON: the Crimson Isle. Builds the island (pure generator in {@code gen/}), its mobs and five
 * mini-bosses, the two factions (pledge, reputation, ranks, daily quests, faction shops), the Dojo's
 * seven trials and belts, the Leviathan-line armor (five sets x five prestiges) with their set bonuses,
 * the armor prestige smith, the Kuudra-gated weapons, Leviathan keys, and the volcano's eruptions.
 *
 * <p>Research: 03 §15 (zones, mini-bosses, mobs, factions, Dojo), 04 §4 (Kuudra keys, armor), 06 §3
 * (armor sets), 02 (SkyBlock XP sources). Data: {@code data/crimson/*.json} from {@code tools/gen/crimson/}.
 */
public final class CrimsonModule implements GameModule {

    public static final String ISLAND = "crimson_isle";
    static CrimsonTables tables;
    static final Map<String, SpawnRule> RULES = new LinkedHashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    /** Every tunable, read at use time. */
    static final class D {
        static Dials.Dial<String> warpRequirement;
        static Dials.Dial<Double> mobHpScale, bossHpScale, bossDamageScale, setBonusScale, questRepScale, vanquisherChance, essenceValue, teethValue;
        static Dials.Dial<Integer> bossRespawn, magmaRespawn, questsPerDay, dailyBonusRep, bossRep, rivalBossRep,
                switchFee, switchCooldownDays, bossBits, resetHourUtc, dojoTrialScale, gatherRegenSeconds;
        static Dials.Dial<Boolean> sidebar, coinsForPrestige, leviathanPlaceholder, eruptionFx, shopNeedsMembership, armorNeedsKuudra;
        static Dials.Dial<Integer> pearlsPerDay, perfumePearls, perfumeUsesPerDay;
        static Dials.Dial<Double> bossLootMinPercent;
        static Dials.Dial<Boolean> bossSharedLoot, factionSpoils, synthesizer;
        // audit 2026-10-06 (K14, K16, K17, K18)
        static Dials.Dial<String> kuudraRep, emissaryDiscount;
        static Dials.Dial<Integer> rollimRep, rollimDays, rollimHours;
        static Dials.Dial<Double> prestigeCoinScale;
    }

    @Override public String id() { return "crimson"; }

    @Override
    public void enable() {
        tables = CrimsonTables.load(f -> Sky.plugin().getResource("data/crimson/" + f));
        dials();

        Sky.items().loadJson("crimson", "items.json");
        Sky.recipes().loadJson("crimson", "recipes.json");
        Sky.mobs().loadJson("crimson", "mobs.json");
        loadSpawnRules();

        IslandDef isle = IslandDef.of(ISLAND, "Crimson Isle", new CrimsonIsleGenerator());
        isle.environment = "NETHER";
        String req = D.warpRequirement.get();
        isle.requirement = req == null || req.isBlank() ? null : req;
        Sky.worlds().registerIsland(isle);
        Sky.worlds().registerWarp(ISLAND, ISLAND, "spawn", "Crimson Isle", null);
        Sky.worlds().registerWarp("crimson_drakehold", ISLAND, "warp.drakehold", "Drakehold", "faction:barbarian:1000");
        Sky.worlds().registerWarp("crimson_carmine", ISLAND, "warp.carmine", "Carmine Spire", "faction:mage:1000");
        Sky.worlds().registerWarp("crimson_tomb", ISLAND, "warp.tomb", "Smoldering Tomb", "faction:any:3000");
        CrimsonScrolls.register();

        registerNpcs();
        Bosses.register();
        SetBonuses.register();
        Dojo.register();
        Factions.register();
        Pearls.register();
        Sky.worlds().addBreakHandler(new Gathering());

        Bukkit.getPluginManager().registerEvents(new Factions(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Bosses(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new SetBonuses(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Dojo(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new BossLoot(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Attrs(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new CrimsonScrolls(), Sky.plugin());
        Synthesizer.register();

        Sky.calendar().register(CalendarEvent.realtime(Bosses.ERUPTION, "Volcano Eruption", 135, 60, 15));

        registerCommands();
        CrimsonAdmin.register();
        CrimsonSelfTests.register();

        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), SetBonuses::tick, 40L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Bosses::tick, 60L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Pearls::tick, 80L, 10L));
        // Leviathan-hunt quests read the kuudra module's clear records (hunts run off-isle, in their own world)
        tasks.add(Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (ready(p)) { Factions.checkHunts(p); Factions.checkLeviathanRep(p); }
        }, 200L, 100L));
    }

    @Override
    public void postEnable() {
        // Drop-table entries whose item nobody registered would roll "unknown item" forever: prune them once,
        // loudly, so the integrator sees exactly which owner is missing.
        List<String> missing = new ArrayList<>();
        for (MobDef d : Sky.mobs().all()) {
            if (d.id == null || !d.id.startsWith("crimson_")) continue;
            d.drops.removeIf(dr -> {
                boolean gone = dr.item == null || !Sky.items().exists(dr.item);
                if (gone) missing.add(d.id + ":" + dr.item);
                return gone;
            });
        }
        if (!missing.isEmpty()) Sky.log().warning("[crimson] dropped " + missing.size() + " drop lines whose item is not registered: " + missing);
        applyKuudraRequirements();
    }

    /** Items we put a {@code kuudra:<tier>} requirement on (so the dial can take it off again). */
    private static final Map<String, String> KUUDRA_REQS = new LinkedHashMap<>();

    /** True when some module answers {@code kuudra:} keys (an unknown prefix fails closed and would lock the armor). */
    static boolean kuudraResolverPresent() {
        try {
            String d = com.skyclone.api.ui.Text.strip(Sky.requirements().describe("kuudra:basic"));
            return d != null && !d.isBlank() && !d.contains("kuudra:basic");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Puts (or removes) the reference's Leviathan clear on the armor and the three Kuudra weapons. */
    static void applyKuudraRequirements() {
        for (Map.Entry<String, String> e : KUUDRA_REQS.entrySet()) {
            ItemDef d = Sky.items().def(e.getKey());
            if (d != null && d.requirements != null) d.requirements.remove(e.getValue());
        }
        KUUDRA_REQS.clear();
        if (!D.armorNeedsKuudra.get()) return;
        if (!kuudraResolverPresent()) {
            Sky.log().info("[crimson] no kuudra:<tier> resolver - Leviathan-line armor needs only its Combat level");
            return;
        }
        for (ItemDef d : Sky.items().all()) {
            if (d == null || d.id == null) continue;
            boolean armor = d.extra != null && d.extra.has("kuudraSet");
            if (!armor && !(d.id.equals("RUNIC_STAFF") || d.id.equals("HOLLOW_WAND") || d.id.equals("TORMENTOR"))) continue;
            String req = CrimsonRules.kuudraRequirement(d.id, armor);
            if (req == null) continue;
            if (d.requirements == null) d.requirements = new ArrayList<>();
            if (!d.requirements.contains(req)) {
                d.requirements.add(req);
                KUUDRA_REQS.put(d.id, req);
            }
        }
    }

    @Override
    public void disable() {
        Dojo.stopAll("the server is stopping");
        Gathering.restoreAll();
        Pearls.removeAll();
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
    }

    static CrimsonData data(Player p) { return Sky.profiles().member(p, "crimson", CrimsonData.class); }

    static boolean ready(Player p) { return p != null && p.isOnline() && Sky.profiles().isReady(p); }

    static boolean onIsle(Player p) { return ISLAND.equals(Sky.worlds().islandOf(p.getWorld())); }

    // ---- dials ---------------------------------------------------------------------------------------

    private static void dials() {
        Dials d = Sky.dials();
        D.warpRequirement = d.text("crimson.warp-requirement", "skill:combat:22", "Requirement to enter the Crimson Isle (research: Combat XXII). Restart to apply.");
        D.mobHpScale = d.number("crimson.mob-hp-scale", 1.0, "Multiplier on Crimson Isle regular mob health (1.0 = research numbers)");
        D.bossHpScale = d.number("crimson.boss-hp-scale", 1.0, "Multiplier on mini-boss health (1.0 = research: 50M/70M/200M)");
        D.bossDamageScale = d.number("crimson.boss-damage-scale", 1.0, "Multiplier on every hit from a mini-boss and the Cindermaw's split Smoldering Blazes (melee, fire bolts, flame rings, slams, blade storms). 1.0 = as designed; lower it to test a fight, 0 = harmless");
        D.setBonusScale = d.number("crimson.set-bonus-scale", 1.0, "Multiplier on Leviathan-line set bonus effects");
        D.questRepScale = d.number("crimson.quest-rep-scale", 1.0, "Multiplier on reputation from faction quests");
        D.vanquisherChance = d.number("crimson.vanquisher-chance", 0.002, "Chance a Crimson Isle kill calls a Vanquisher (TUNE: not published)");
        D.essenceValue = d.number("crimson.essence-coin-value", 1166, "Coins per Crimson Essence when prestige is paid in coins (bazaar price 2026-10)");
        D.teethValue = d.number("crimson.teeth-coin-value", 7046, "Coins per Leviathan tooth when prestige is paid in coins (bazaar price 2026-10)");
        D.bossRespawn = d.integer("crimson.boss-respawn-seconds", 120, "Mini-boss respawn (research: 2 min)");
        D.magmaRespawn = d.integer("crimson.magma-boss-respawn-seconds", 300, "Magma Colossus respawn (research: 5 min)");
        D.questsPerDay = d.integer("crimson.quests-per-day", tables.factions.quests_per_day, "Faction quests on the board each day");
        D.dailyBonusRep = d.integer("crimson.daily-bonus-rep", tables.factions.daily_bonus_rep, "Bonus reputation for finishing the whole board (0 = the reference game, which has no such bonus; ours was 25)");
        D.bossRep = d.integer("crimson.boss-rep", tables.factions.boss_rep, "Reputation for any mini-boss kill (TUNE)");
        D.rivalBossRep = d.integer("crimson.rival-boss-rep", tables.factions.rival_boss_rep, "Reputation for killing the RIVAL faction's champion (TUNE)");
        D.switchFee = d.integer("crimson.switch-fee", 50_000, "Coins to switch factions (TUNE)");
        D.switchCooldownDays = d.integer("crimson.switch-cooldown-days", 7, "Days between faction switches (TUNE)");
        D.bossBits = d.integer("crimson.boss-bits", 30, "Bits for the Magma Colossus and the Cindermaw (research 05: 30)");
        D.resetHourUtc = d.integer("crimson.quest-reset-hour-utc", 0, "UTC hour the faction quest board resets");
        D.dojoTrialScale = d.integer("crimson.dojo-seconds-percent", 100, "Dojo trial length in percent of the default");
        D.gatherRegenSeconds = d.integer("crimson.gather-regen-seconds", 20, "Seconds before a mined quartz/glowstone/sand/mycelium block grows back (TUNE)");
        D.sidebar = d.flag("crimson.sidebar", true, "Show faction + quests on the sidebar while on the isle");
        D.coinsForPrestige = d.flag("crimson.prestige-accepts-coins", true, "Armor prestige may pay missing essence/teeth in coins (interim until Leviathan hunts exist)");
        D.leviathanPlaceholder = d.flag("crimson.leviathan-placeholder", true, "Run the dockhand at the Leviathan dock (turn off when the kuudra module owns it)");
        D.eruptionFx = d.flag("crimson.eruption-effects", true, "Particles and sounds while the volcano erupts");
        D.shopNeedsMembership = d.flag("crimson.shop-needs-membership", true, "Faction shops only sell to their own members");
        D.armorNeedsKuudra = d.flag("crimson.armor-needs-kuudra", true, "Leviathan-line armor and the Kuudra weapons need the reference's Leviathan clear (Basic armor: a Basic clear, Hot: a Hot clear...). Only applied when the kuudra module is running");
        D.pearlsPerDay = d.integer("crimson.pearls-per-day", 3, "Heavy Pearls the Matriarch leaves in the Belly of the Beast for each player per day (TUNE)");
        D.perfumePearls = d.integer("crimson.perfume-pearls", 3, "Extra Heavy Pearls one Matriarch's Perfume releases (TUNE)");
        D.perfumeUsesPerDay = d.integer("crimson.perfume-uses-per-day", 4, "Matriarch's Perfume uses per day (reference item lore: 4)");
        D.bossLootMinPercent = d.number("crimson.boss-loot-min-percent", tables.factions.boss_loot.min_damage_percent, "Share of a mini-boss's health a player must deal to share its loot, reputation and quest credit (the killer always does; TUNE)");
        D.bossSharedLoot = d.flag("crimson.boss-shared-loot", true, "Every player who shares a mini-boss kill gets its drops, coins and Combat XP (off = only the last hitter, the pre-wave-5 behaviour)");
        D.factionSpoils = d.flag("crimson.faction-spoils", true, "Pledged players get their faction's spoils from mini-bosses (key materials, the rival champion's spoils, quest-target key rolls)");
        D.synthesizer = d.flag("crimson.synthesizer", true, "Synthesizer necklaces accept X / Y / Z and gain their stats (/synthesizer)");
        CrimsonTables.Factions tf = tables.factions;
        D.kuudraRep = d.text("crimson.kuudra-rep", tf.kuudra_rep.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("/")),
                "Faction reputation for the day's first Leviathan clear of each tier, Basic/Hot/Burning/Fiery/Infernal (reference: 10/20/30/40/50, once a day per tier; 0/0/0/0/0 = off)");
        D.emissaryDiscount = d.text("crimson.emissary-discount", CrimsonRules.thresholdsText(tf.emissary_discount),
                "Faction-shop Leviathan keys: reputation:percent off the key's COIN part (reference Emissary: 1000:5/3000:10/7000:15/12000:20; empty = no discount)");
        D.rollimRep = d.integer("crimson.rollim-rep", tf.rollim.rep, "Reputation Watchman Orrin (the reference's Rollim) gives once a day (reference: 25)");
        D.rollimDays = d.integer("crimson.rollim-days", tf.rollim.days, "How many daily Rollim claims a player gets in total (reference: 7)");
        D.rollimHours = d.integer("crimson.rollim-hours", tf.rollim.hours, "Hours between two Rollim claims (reference: 24, counted from the last claim)");
        D.prestigeCoinScale = d.number("crimson.prestige-coin-scale", 1.0, "Multiplier on the coin part of armor prestige (reference: to Hot 2M / Burning 5M / Fiery 10M / Infernal 20M per piece; 0 = no coins)");
        D.armorNeedsKuudra.onChange(v -> applyKuudraRequirements());
        D.bossRespawn.onChange(v -> applyRespawns());
        D.magmaRespawn.onChange(v -> applyRespawns());
    }

    private static void loadSpawnRules() {
        try (InputStream in = Sky.plugin().getResource("data/crimson/spawns.json")) {
            if (in == null) { Sky.log().warning("[crimson] spawns.json missing"); return; }
            List<SpawnRule> rules = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<List<SpawnRule>>() {}.getType());
            for (SpawnRule r : rules) {
                RULES.put(r.id, r);
                Sky.mobs().addSpawnRule(r);
            }
            applyRespawns();
        } catch (Exception e) {
            Sky.log().warning("[crimson] could not load spawn rules: " + e);
        }
    }

    static void applyRespawns() {
        for (SpawnRule r : RULES.values()) {
            if (!r.id.startsWith("crimson_boss_")) continue;
            r.respawnSeconds = r.mob.equals("crimson_magma_boss") ? D.magmaRespawn.get() : D.bossRespawn.get();
        }
    }

    // ---- NPCs ----------------------------------------------------------------------------------------

    private static void registerNpcs() {
        for (CrimsonTables.Npc n : tables.npcs) {
            NpcDef def = NpcDef.of(n.id, n.name, ISLAND, n.anchor);
            if (n.title != null) def.title(n.title);
            if (n.skin != null) def.skin(n.skin, null);
            final CrimsonTables.Npc npc = n;
            Sky.npcs().register(def, p -> click(p, npc));
        }
    }

    private static void click(Player p, CrimsonTables.Npc n) {
        if (!ready(p)) return;
        Runnable then = switch (n.role) {
            case "guide" -> () -> { Factions.rollim(p); CrimsonMenus.main(p); };
            case "leader_mage" -> () -> Factions.leaderClicked(p, CrimsonRules.MAGE);
            case "leader_barbarian" -> () -> Factions.leaderClicked(p, CrimsonRules.BARBARIAN);
            case "shop_mage" -> () -> CrimsonMenus.shop(p, CrimsonRules.MAGE, 0);
            case "shop_barbarian" -> () -> CrimsonMenus.shop(p, CrimsonRules.BARBARIAN, 0);
            case "dojo" -> () -> Dojo.menu(p);
            case "armorsmith" -> () -> Prestige.menu(p, 0);
            case "bank" -> () -> { if (!Sky.commands().run(p, "bank")) Msg.error(p, "The bank is closed right now."); };
            case "bazaar" -> () -> { if (!Sky.commands().run(p, "bz")) Msg.error(p, "The bazaar is closed right now."); };
            case "dock" -> null;
            default -> null;
        };
        if ("dock".equals(n.role) && !D.leviathanPlaceholder.get()) return;
        CrimsonData d = data(p);
        // the long greeting only the first few times; afterwards straight to business
        boolean first = d.talked.add(n.id);
        List<String> lines = first || then == null ? n.lines : n.lines.subList(0, Math.min(1, n.lines.size()));
        Sky.npcs().talk(p, n.id, lines, then);
    }

    // ---- commands --------------------------------------------------------------------------------------

    private static void registerCommands() {
        Sky.commands().register(SkyCommand.of("crimson", "Crimson Isle: faction, quests, mini-bosses, Dojo")
                .alias("crimsonisle", "ci").category("Combat").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (ready(p)) CrimsonMenus.main(p); }));
        Sky.commands().register(SkyCommand.of("faction", "Your Crimson Isle faction and reputation")
                .alias("factions", "reputation").category("Combat").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (ready(p)) CrimsonMenus.faction(p); }));
        Sky.commands().register(SkyCommand.of("factionquests", "Today's faction quests")
                .alias("fquests", "crimsonquests").category("Combat").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (ready(p)) CrimsonMenus.quests(p); }));
        Sky.commands().register(SkyCommand.of("factionshop", "Your faction's quartermaster")
                .alias("fshop").category("Combat").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!ready(p)) return;
                    String f = data(p).faction;
                    if (f == null) { Msg.error(p, "Pledge to a faction first: /faction"); return; }
                    CrimsonMenus.shop(p, f, 0);
                }));
        Sky.commands().register(SkyCommand.of("minibosses", "Crimson Isle mini-boss status")
                .alias("crimsonbosses").category("Combat").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (ready(p)) CrimsonMenus.bosses(p); }));
        Sky.commands().register(SkyCommand.of("dojo", "The Dojo's trials and your belt")
                .category("Combat").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (ready(p)) Dojo.menu(p); }));
        Sky.commands().register(SkyCommand.of("prestige", "Raise the prestige of Leviathan-line armor")
                .alias("armorprestige").category("Combat").playerOnly()
                .run((s, a) -> { Player p = (Player) s; if (ready(p)) Prestige.menu(p, 0); }));
        Sky.commands().register(SkyCommand.of("dojoquit", "Give up the Dojo trial you are in")
                .category("Combat").playerOnly()
                .run((s, a) -> Dojo.quit((Player) s)));
    }

    /** Def lookup that tolerates a missing registration (another module failed, a test world). */
    static ItemDef item(String id) { return Sky.items().def(id); }

    static boolean dropOk(DropDef d) { return d != null && d.item != null && Sky.items().exists(d.item); }
}
