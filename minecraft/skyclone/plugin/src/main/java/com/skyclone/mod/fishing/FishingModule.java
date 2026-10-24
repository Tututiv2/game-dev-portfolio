package com.skyclone.mod.fishing;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.fishing.bayou.BayouGenerator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FISHING (research 07 §3). Custom bite timing driven by Fishing Speed, sea creatures from
 * location pools with replacement pools (sharks, spooky, hotspots...), treasure, double hooks,
 * lava fishing and Trophy Fishing, baits and the Fishing Bag, rod parts, hotspots, the Fishing
 * Festival, and the Backwater Bayou island.
 *
 * <p>Player commands: /fishing (/fish), /seacreatures, /trophyfish (/trophy), /fishingbag, /rodparts,
 * /baits, /hotspots. Admin: /sb fishing ... (see {@link FishAdmin}).
 */
public final class FishingModule implements GameModule {

    static final Set<String> OWN_ITEMS = new LinkedHashSet<>();
    static final Set<String> PRUNED = new LinkedHashSet<>();
    static int RECIPES_REGISTERED;
    static final List<String> RECIPES_SKIPPED = new ArrayList<>();

    @Override public String id() { return "fishing"; }

    @Override public void enable() {
        FishDials.register();
        Fishing.load();
        int items = Sky.items().loadJson("fishing", "items.json");
        int mobs = Sky.mobs().loadJson("fishing", "mobs.json");
        readOwnIds();

        Sky.worlds().registerIsland(IslandDef.of("backwater_bayou", "Backwater Bayou", new BayouGenerator()));
        Sky.worlds().registerWarp("backwater_bayou", "backwater_bayou", "spawn", "Backwater Bayou", null);
        // the reference's own warp id (/warp bayou); typed by a playtester and answered "Unknown destination"
        Sky.worlds().registerWarp("bayou", "backwater_bayou", "spawn", "Backwater Bayou", null);
        Sky.calendar().register(CalendarEvent.onDate(Fishing.FESTIVAL_EVENT, "Fishing Festival", 0, 1, 3));
        Sky.requirements().register("trophy", new Trophies.Resolver());

        Bukkit.getPluginManager().registerEvents(new Casting(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new SeaCreatures(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new SeaCreatureGuard(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new PuddleJumper(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new FishItems(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new ChumBuckets(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new MobyDuck(), Sky.plugin());
        SeaCreatures.registerCombat();
        SeaCreatures.registerBehaviours();
        PuddleJumper.register();
        FishItems.register();
        ChumBuckets.register();
        MobyDuck.register();
        RodParts.register();
        FishNpcs.register();
        Sky.abilities().register("fishing_hotspot_radar", use -> radar(use.player()));

        commands();
        FishAdmin.register();
        FishSelfTests.register();
        FishSelfTests5.register();
        FishSelfTestsAudit.register();

        Sky.sidebar().section("fishing", 400, FishingModule::sidebar);
        Sky.vitals().actionBarSegment("fishing", 300, SeaCreatures::actionBar);
        Sky.sidebar().tabWidget("fishing", 160, FishingModule::tab);

        Casting.start();
        SeaCreatures.start();
        Hotspots.start();
        Trophies.start();
        Geysers.start();
        PuddleJumper.start();
        ChumBuckets.start();
        Sky.log().info("[fishing] " + items + " items, " + mobs + " sea creatures, " + Fishing.CAT.pools.size() + " pools, "
                + Fishing.CAT.trophy.fish.size() + " trophy fish");
    }

    @Override public void postEnable() {
        pruneDrops();
        registerRecipes();
    }

    @Override public void disable() {
        Casting.stop();
        Hotspots.stop();
        Trophies.stop();
        Geysers.stop();
        PuddleJumper.stop();
        ChumBuckets.stop();
        SeaCreatures.removeAll(null);
    }

    // ---- commands -------------------------------------------------------------------------------------

    private void commands() {
        Sky.commands().register(SkyCommand.of("fishing", "Open the Fishing menu: stats, odds here, guide, trophies, baits, parts")
                .alias("fish").category("Skills").playerOnly().run((s, a) -> FishMenus.main((Player) s)));
        Sky.commands().register(SkyCommand.of("seacreatures", "Browse every sea creature, where it bites and what it drops")
                .alias("scguide").category("Skills").playerOnly().run((s, a) -> FishMenus.guide((Player) s, 0)));
        Sky.commands().register(SkyCommand.of("trophyfish", "Your Trophy Fishing collection, titles and filleting")
                .alias("trophy", "trophyfishing").category("Skills").playerOnly().run((s, a) -> FishMenus.trophies((Player) s, false)));
        Sky.commands().register(SkyCommand.of("fishingbag", "Open your Fishing Bag (baits)")
                .alias("fbag", "baitbag").category("Storage").playerOnly().run((s, a) -> Baits.openBag((Player) s)));
        Sky.commands().register(SkyCommand.of("rodparts", "Fit hooks, lines and sinkers to the rod in your hand")
                .alias("roddy", "tacklesmith").category("Skills").playerOnly().run((s, a) -> RodParts.open((Player) s)));
        Sky.commands().register(SkyCommand.of("baits", "Every fishing bait and what it does")
                .category("Skills").playerOnly().run((s, a) -> FishMenus.baits((Player) s)));
        Sky.commands().register(SkyCommand.of("hotspots", "Where the Fishing Hotspots are right now")
                .alias("hotspot").category("Skills").playerOnly().run((s, a) -> FishMenus.hotspots((Player) s)));
        Sky.commands().register(SkyCommand.of("chumbuckets", "Your placed Chum Buckets; 'return' brings them back")
                .alias("chumbucket", "chum").category("Skills").playerOnly().usage("[return]").run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length > 0 && a[0].equalsIgnoreCase("return")) {
                        if (!Fishing.ready(p)) return;
                        int n = ChumBuckets.returnAll(p);
                        Msg.send(p, n == 0 ? "&7You have no bucket out." : "&a" + n + " bucket" + (n == 1 ? " is" : "s are") + " back in your inventory.");
                        return;
                    }
                    ChumBuckets.menu(p);
                }));
    }

    // ---- live UI --------------------------------------------------------------------------------------

    private static List<String> sidebar(Player p) {
        List<String> out = new ArrayList<>();
        if (!Fishing.ready(p)) return out;
        if (Trophies.goldenActive(p)) out.add("&6✦ Golden Fish nearby!");
        // the sidebar has five shared lines: only an angler (rod in hand) gets the fishing ones
        if (!Fishing.isRod(p.getInventory().getItemInMainHand())) return out;
        if (Fishing.festival() && Fishing.island(p.getLocation()) != null) out.add("&b☂ Fishing Festival");
        Hotspots.Hotspot h = Hotspots.nearest(p.getLocation());
        if (h != null && h.center.distanceSquared(p.getLocation()) < 20 * 20) out.add("&d✦ Hotspot: " + h.buff.shortLabel());
        // the Gusher's clock, for a lava angler on the Crimson Isle
        if (Trophies.TROPHY_ISLAND.equals(Fishing.island(p.getLocation()))
                && Fishing.isLavaRod(Sky.items().idOf(p.getInventory().getItemInMainHand())))
            out.add(Trophies.gusherWindow() ? "&6☄ Gushers: &e" + Trophies.clock(Trophies.gusherSeconds()) + " left"
                    : "&7☄ Eruption in &e" + Trophies.clock(Trophies.eruptionSeconds()));
        if (out.size() > 2) return out.subList(0, 2);
        return out;
    }

    private static List<String> tab(Player p) {
        if (!Fishing.ready(p)) return List.of();
        FishData d = Fishing.data(p);
        int t = Trophies.title(d);
        List<String> l = new ArrayList<>();
        l.add("&3&lFishing:");
        l.add(" &7Sea creatures: &b" + SeaCreatures.count(p.getUniqueId()) + "&7/" + FishDials.CAP_PLAYER.get());
        l.add(" &7Trophy: " + (t == 0 ? "&8no title" : "&6" + Trophies.titleName(t)));
        if (Fishing.festival()) l.add(" &bFishing Festival!");
        return l;
    }

    private static boolean radar(Player p) {
        Hotspots.Hotspot h = Hotspots.nearest(p.getLocation());
        if (h == null) { Msg.error(p, "No Fishing Hotspot on this island right now."); return false; }
        var v = h.center.toVector().subtract(p.getLocation().toVector());
        double dist = v.length();
        String dir = direction(Math.toDegrees(Math.atan2(-v.getX(), v.getZ())) - p.getLocation().getYaw());
        Msg.send(p, "&dHotspot &7" + (int) dist + " blocks " + dir + " &8(" + h.buff.label(1) + "&8, moves in " + h.secondsLeft() + "s)");
        p.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, p.getEyeLocation().add(v.normalize().multiply(2)), 6, 0.05, 0.05, 0.05, 0);
        return true;
    }

    private static String direction(double rel) {
        double a = ((rel % 360) + 540) % 360 - 180;
        if (Math.abs(a) < 22.5) return "ahead";
        if (Math.abs(a) > 157.5) return "behind you";
        if (a > 0) return a < 67.5 ? "ahead to the right" : a < 112.5 ? "to your right" : "behind to the right";
        return a > -67.5 ? "ahead to the left" : a > -112.5 ? "to your left" : "behind to the left";
    }

    // ---- data wiring (postEnable: every module's items exist by now) ------------------------------

    private static void readOwnIds() {
        OWN_ITEMS.clear();
        try (InputStream in = Sky.plugin().getResource("data/fishing/items.json")) {
            if (in == null) return;
            JsonArray a = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class);
            for (JsonElement e : a) OWN_ITEMS.add(e.getAsJsonObject().get("id").getAsString());
        } catch (Exception e) {
            Sky.log().warning("[fishing] could not read own item ids: " + e);
        }
    }

    /** Remove drops whose item no module registered (logged once), so loot never names a ghost item. */
    private static void pruneDrops() {
        PRUNED.clear();
        for (FishingCatalog.Pool p : Fishing.CAT.pools) {
            for (FishingCatalog.PoolCreature c : p.creatures) {
                MobDef d = Sky.mobs().def(c.mob);
                if (d == null) continue;
                for (Iterator<DropDef> it = d.drops.iterator(); it.hasNext(); ) {
                    DropDef dr = it.next();
                    if (!Sky.items().exists(dr.item)) { PRUNED.add(dr.item); it.remove(); }
                }
            }
        }
        if (!PRUNED.isEmpty()) Sky.log().info("[fishing] sea creature drops skipped (item not registered by any module): " + PRUNED);
    }

    private static void registerRecipes() {
        RECIPES_REGISTERED = 0;
        RECIPES_SKIPPED.clear();
        Recipe[] all;
        try (InputStream in = Sky.plugin().getResource("data/fishing/recipes.json")) {
            if (in == null) return;
            all = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Recipe[].class);
        } catch (Exception e) {
            Sky.log().warning("[fishing] recipes.json: " + e);
            return;
        }
        for (Recipe r : all) {
            List<String> missing = new ArrayList<>();
            if (!Sky.items().exists(r.result)) missing.add(r.result);
            if (r.grid != null) for (String g : r.grid) { String id = cellId(g); if (id != null && !Sky.items().exists(id)) missing.add(id); }
            if (r.inputs != null) for (String g : r.inputs) { String id = cellId(g); if (id != null && !"COINS".equals(id) && !Sky.items().exists(id)) missing.add(id); }
            if (!missing.isEmpty()) { RECIPES_SKIPPED.add(r.result + missing); continue; }
            Sky.recipes().register(r);
            RECIPES_REGISTERED++;
        }
        if (!RECIPES_SKIPPED.isEmpty()) Sky.log().info("[fishing] recipes waiting on other modules' items: " + RECIPES_SKIPPED);
    }

    /** Same rule as core's recipe cells: a known id is x1, else the part after the last ':' is the amount. */
    static String cellId(String cell) {
        if (cell == null || cell.isBlank()) return null;
        String s = cell.trim();
        if (Sky.items().exists(s)) return s;
        int c = s.lastIndexOf(':');
        if (c > 0 && s.substring(c + 1).chars().allMatch(Character::isDigit) && c < s.length() - 1) return s.substring(0, c);
        return s;
    }
}
