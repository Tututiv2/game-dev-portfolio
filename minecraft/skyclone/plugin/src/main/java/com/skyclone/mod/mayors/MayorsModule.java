package com.skyclone.mod.mayors;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Mayors, the election, every mayor perk and the calendar's festivals: Spooky Festival, New Year,
 * Traveling Zoo, Season of Gerald + the Winter Workshop island, Dark Auction, Mythological Ritual,
 * Stonks Auction and Gerald Sightings. Provides {@link Features.Mayors}. Perk ids: docs/handoff/mayors.md.
 */
public final class MayorsModule implements GameModule, Listener {

    public static final String ID = "mayors";
    private static MayorsModule instance;

    private MayorDials dials;
    private MayorRoster roster;
    private EventData data;
    private ElectionService election;
    private SpookyFestival spooky;
    private ExtraEvents extraEvents;
    private NewYear newYear;
    private TravelingZoo zoo;
    private DarkAuction darkAuction;
    private Mythology myth;
    private Carnival carnival;
    private MythMechanics mythMechanics;
    private WinterWorkshop winter;
    private StonksAuction stonks;
    private EventNpcs npcs;
    private EventAlerts alerts;
    private BukkitTask ticker;

    static MayorsModule get() { return instance; }

    MayorDials dials() { return dials; }
    MayorRoster roster() { return roster; }
    EventData data() { return data; }
    ElectionService election() { return election; }
    SpookyFestival spooky() { return spooky; }
    ExtraEvents extraEvents() { return extraEvents; }
    NewYear newYear() { return newYear; }
    TravelingZoo zoo() { return zoo; }
    DarkAuction darkAuction() { return darkAuction; }
    Mythology myth() { return myth; }
    Carnival carnival() { return carnival; }
    MythMechanics mythMechanics() { return mythMechanics; }
    WinterWorkshop winter() { return winter; }
    StonksAuction stonks() { return stonks; }
    EventNpcs npcs() { return npcs; }
    EventAlerts alerts() { return alerts; }

    /** Member data, or null before ProfileReady (never cache it). */
    PlayerData data(Player p) {
        if (p == null || !Sky.profiles().isReady(p)) return null;
        return Sky.profiles().member(p, ID, PlayerData.class);
    }

    @Override public String id() { return ID; }

    @Override
    public void enable() {
        instance = this;
        dials = new MayorDials();
        roster = read("mayors.json", MayorRoster::parse);
        data = read("events.json", EventData::parse);
        MayorsDb.migrate();

        Sky.items().loadJson(ID, "items.json");
        Sky.recipes().loadJson(ID, "recipes.json");
        registerBehaviours();
        Sky.mobs().loadJson(ID, "mobs.json");
        Sky.mobs().loadSpawnRules(ID, "spawns.json");
        NewYear.registerLore();

        election = new ElectionService(roster, dials);
        election.load();
        Sky.provide(Features.Mayors.class, election);
        new PerkEffects(election, dials).register();
        Cal.register();

        spooky = new SpookyFestival(this);
        extraEvents = new ExtraEvents(this);
        newYear = new NewYear(this);
        zoo = new TravelingZoo(this);
        darkAuction = new DarkAuction(this);
        myth = new Mythology(this);
        carnival = new Carnival(this);
        carnival.register();
        winter = new WinterWorkshop(this);
        stonks = new StonksAuction(this);
        stonks.load();
        npcs = new EventNpcs(this);
        alerts = new EventAlerts(this);

        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(spooky, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(alerts, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new HiddenGeralds(this), Sky.plugin());
        myth.register();
        winter.register();
        npcs.register();

        Sky.requirements().register("mayors", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2) return false;
                return switch (parts[1]) {
                    case "workshop" -> winter.open();
                    case "carnival" -> carnival.active();
                    // mayors:carnival_perk:<id>[:<tier>] - does the player own at least that tier (default 1) of a Carnival Perk?
                    case "carnival_perk" -> parts.length > 2 && carnival.perks().tier(p, parts[2]) >= (parts.length > 3 ? parseInt(parts[3], 1) : 1);
                    case "perk" -> parts.length > 2 && election.perkActive(parts[2]);
                    case "mayor" -> parts.length > 2 && parts[2].equalsIgnoreCase(election.state().mayor);
                    // mayors:alert:<eventId>[:start|remind] - would this player get a calendar line for the event?
                    case "alert" -> parts.length > 2 && alerts.wants(p, parts[2],
                            parts.length > 3 && "start".equalsIgnoreCase(parts[3]) ? AlertPolicy.Kind.START : AlertPolicy.Kind.REMIND);
                    default -> false;
                };
            }
            @Override public String describe(String[] parts) {
                if (parts.length >= 2 && "workshop".equals(parts[1])) return "&bthe Winter Workshop season (Late Winter)";
                if (parts.length >= 2 && "carnival".equals(parts[1])) return "&dthe Carnival (the Fairground perk)";
                if (parts.length >= 3 && "carnival_perk".equals(parts[1])) {
                    EventData.Perk pk = data.carnival.perk(parts[2]);
                    return "&6" + (pk == null ? parts[2] : pk.name) + " " + (parts.length > 3 ? parts[3] : "1") + " &7(Carnival Perk)";
                }
                if (parts.length >= 3 && "perk".equals(parts[1])) {
                    MayorRoster.Perk pk = roster.perk(parts[2]);
                    return "&ethe " + (pk == null ? parts[2] : pk.name) + " perk";
                }
                if (parts.length >= 3 && "mayor".equals(parts[1])) {
                    MayorRoster.Mayor m = roster.mayor(parts[2]);
                    return (m == null ? parts[2] : m.display()) + " &7as Mayor";
                }
                if (parts.length >= 3 && "alert".equals(parts[1])) return "&7calendar alerts for &e" + parts[2];
                return "&7a mayor condition";
            }
        });

        MayorsCommands.register(this);
        MayorsSelfTests.register(this);

        Sky.sidebar().section(ID, 400, this::sidebar);
        Sky.sidebar().tabWidget(ID, 150, p -> {
            List<String> out = new ArrayList<>();
            out.add("&e&lMayor: " + (election.mayorName().isBlank() ? "&7none" : election.mayorName()));
            MayorRoster.Mayor min = roster.mayor(election.state().minister);
            if (min != null) out.add(" &7Minister: " + min.display());
            if (election.paradeMayor() != null) {
                MayorRoster.Mayor pm = roster.mayor(election.paradeMayor());
                if (pm != null) out.add(" &7Perk Parade: " + pm.display());
            }
            return out;
        });

        ticker = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 20L);
    }

    private interface Parser<T> { T parse(java.io.Reader r); }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    private <T> T read(String file, Parser<T> parser) {
        try (InputStream in = Sky.plugin().getResource("data/" + ID + "/" + file)) {
            if (in == null) throw new IllegalStateException("missing data/" + ID + "/" + file);
            return parser.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read data/" + ID + "/" + file, e);
        }
    }

    private void registerBehaviours() {
        // Shielded mobs: health counted in hits, every hit deals exactly 1.
        Sky.mobs().registerBehaviour("mayors_hits", new MobBehaviour() {
            @Override public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
                if (ctx.finalDamage > 0) ctx.finalDamage = 1;
            }
        });
        // Mythological creatures: the fight mechanics (lynx mark, construct hit stages, bleed, ramping
        // champions/inquisitors). Bestiary/armor also read the "mythological" flag + family.
        mythMechanics = new MythMechanics();
        MythMechanics.griffinRule = () -> dials.mythGriffinToHurt.get();
        mythMechanics.register();
    }

    private long ticks;

    private void tick() {
        ticks++;
        run("election", election::tick);
        run("spooky", spooky::tick);
        run("extra-events", extraEvents::tick);
        run("newyear", newYear::tick);
        run("zoo", zoo::tick);
        run("darkauction", darkAuction::tick);
        run("myth", myth::tick);
        run("carnival", carnival::tick);
        run("winter", winter::tick);
        run("stonks", stonks::tick);
        if (ticks % 5 == 0) run("npcs", npcs::tick);
        if (ticks % 5 == 2) run("alerts", alerts::scan);
    }

    private static void run(String what, Runnable r) {
        try { r.run(); } catch (Throwable t) { Sky.log().log(java.util.logging.Level.WARNING, "[mayors] " + what + " tick threw", t); }
    }

    private List<String> sidebar(Player p) {
        if (!dials.sidebar.get()) return List.of();
        List<String> l = carnival.games().sidebar(p);
        if (l.isEmpty()) l = darkAuction.sidebar(p);
        if (l.isEmpty()) l = winter.sidebar(p);
        if (l.isEmpty()) l = myth.sidebar(p);
        if (l.isEmpty()) l = spooky.sidebar(p);
        return l;
    }

    /**
     * Our mobs drop items other modules own (and "NAME;level" book ids). Once every module has
     * registered (one tick after boot, after every postEnable), point each drop at the id that really
     * exists and drop the lines that do not, so a kill can never roll an unknown id.
     */
    @Override
    public void postEnable() {
        // Every module has registered its dials by now, so menus' calendar-reminder dial exists to be taken over.
        try { alerts.takeOver(); } catch (Throwable t) { Sky.log().warning("[mayors] calendar alert takeover failed: " + t); }
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            List<String> dropped = new ArrayList<>();
            for (MobDef d : Sky.mobs().all()) {
                if (!(d.id.startsWith("spooky_") || d.id.startsWith("myth_") || d.id.startsWith("siege_") || d.id.startsWith("hidden_jerry_"))) continue;
                d.drops.removeIf(dr -> {
                    String r = Goods.resolve(dr.item);
                    if (r == null) { dropped.add(d.id + ":" + dr.item); return true; }
                    dr.item = r;
                    return false;
                });
            }
            if (!dropped.isEmpty()) Sky.log().info("[mayors] drops skipped (item not registered on this server): " + dropped);
        });
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Claims.flush(e.player);
    }

    /** Rewards won while in the Rift wait in the claim queue until the player is back outside. */
    @EventHandler
    public void onIslandChange(SkyEvents.IslandChange e) {
        if (!"rift".equals(e.from) || "rift".equals(e.to)) return;
        Player p = e.player;
        // a second later, after the Rift module has swapped the main inventory back in
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline() && Sky.profiles().isReady(p)) Claims.flush(p); }, 40L);
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        try { carnival.shutdown(); } catch (Throwable ignored) { }
        try { darkAuction.shutdown(); } catch (Throwable ignored) { }
        try { spooky.flush(); } catch (Throwable ignored) { }
        try { winter.shutdown(); } catch (Throwable ignored) { }
        try { npcs.shutdown(); } catch (Throwable ignored) { }
    }
}
