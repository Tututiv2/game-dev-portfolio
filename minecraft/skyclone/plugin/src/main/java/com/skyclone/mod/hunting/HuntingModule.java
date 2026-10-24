package com.skyclone.mod.hunting;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.hunting.gen.SafariGenerator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * HUNTING - the 12th skill (2025 rework). Critters live in habitats across the islands and are caught with
 * Lassos (Forest), Fishing Nets (Water) and Pocket Black Holes (Combat, with a Huntaxe to keep the monster
 * alive); Huntraps catch on their own; Salts raise Charm Chance so killed mobs drop their shard. Every shard is
 * an ATTRIBUTE: syphon shards from the Hunting Box to level it (1-10), fuse unwanted shards into new ones,
 * and spend the Critter Safari's capsules on rarer ones. Provides {@link Features.Hunting}.
 */
public final class HuntingModule implements GameModule, Listener {

    @Override public String id() { return "hunting"; }

    @Override
    public void enable() {
        HDials.register();
        Hunt.loadCatalog();
        Salts.load();
        Sky.items().loadJson("hunting", "items.json");
        Sky.mobs().loadJson("hunting", "mobs.json");
        Sky.provide(Features.Hunting.class, new Hunt());

        Sky.worlds().registerIsland(IslandDef.of(SafariGenerator.ID, "Critter Safari", new SafariGenerator()));
        Sky.worlds().registerWarp("safari", SafariGenerator.ID, "spawn", "Critter Safari", "skill:hunting:15");

        AttributeEffects.register();
        Tools.register();
        Safari.register();
        // wave-5 integration (reforges request 16): `safari:active` = a Critter Safari hunt is running for the player
        // (reforges' Majestic reads it); `safari:<anything else>` is false.
        Sky.requirements().register("safari", new com.skyclone.api.util.Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                return parts.length >= 2 && "active".equalsIgnoreCase(parts[1].trim()) && Safari.active(p);
            }
            @Override public String describe(String[] parts) { return "&aa running Critter Safari hunt"; }
        });
        npcs();
        commands();
        HuntingAdmin.register();
        HuntingSelfTests.register();
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());

        Sky.sidebar().section("hunting", 320, p -> {
            if (!Hunt.ready(p) || !Safari.active(p)) return List.of();
            HuntingData d = Hunt.data(p);
            return List.of("&2Safari Hunt &e" + Text.duration(Safari.left(p)),
                    " &7Caught: &a" + d.safariCaught + " &7Capsules: &a" + (Sky.items().count(p, "CRITTER_CAPSULE") + Sky.items().count(p, "MASTERFUL_CRITTER_CAPSULE")));
        });
        Sky.sidebar().tabWidget("hunting", 170, p -> {
            if (!Hunt.ready(p)) return List.of();
            AttrSnapshot s = Hunt.snapshot(p);
            if (s.levels.isEmpty() && Hunt.data(p).totalCaught == 0) return List.of();
            return List.of("&d&lHunting", " &d☘ Fortune: &a" + HuntMath.fmt(Sky.stats().get(p, Stat.HUNTING_FORTUNE)),
                    " &d❣ Charm: &a" + HuntMath.fmt(Hunt.charm(p, Sky.worlds().islandOf(p.getWorld()), false)) + "%",
                    " &7Attributes: &a" + s.levels.size());
        });
    }

    @Override
    public void postEnable() {
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            registerRecipes();
            Critters.start();
        });
    }

    @Override
    public void disable() {
        Critters.stop();
    }

    private void npcs() {
        String island = SafariGenerator.ID;
        Sky.npcs().register(NpcDef.of("hunting_clerk", "&aClerk Isaura", island, "npc.safari_receptionist").title("&e&lSAFARI TICKETS"),
                p -> Sky.npcs().talk(p, "hunting_clerk", List.of("&fWelcome to the Critter Safari!",
                        "&fA ticket buys a timed hunt and a stack of capsules. Better tickets, more capsules."),
                        () -> HuntingMenus.safari(p).open(p)));
        Sky.npcs().register(NpcDef.of("hunting_warden", "&aWarden Odalric", island, "npc.safari_warden").title("&6&lMILESTONES"),
                p -> Sky.npcs().talk(p, "hunting_warden", List.of("&fEvery hunt in a biome counts. The more you finish, the stronger your Safari Belt."),
                        () -> HuntingMenus.safari(p).open(p)));
        Sky.npcs().register(NpcDef.of("hunting_keeper", "&aKeeper Sabeline", island, "npc.safari_perks").title("&d&lSAFARI PERKS"),
                p -> HuntingMenus.perks(p).open(p));
        Sky.npcs().register(NpcDef.of("hunting_board", "&aSafari Notice Board", island, "npc.safari_manager").title("&e&lTICKET UPGRADES").entity("ARMOR_STAND"),
                p -> HuntingMenus.safari(p).open(p));
        Sky.npcs().register(NpcDef.of("hunting_dorran", "&aHunter Ossian", island, "npc.huntmaster").title("&e&lHUNTING"),
                p -> Sky.npcs().talk(p, "hunting_dorran", List.of("&fEvery creature out there carries a shard.",
                        "&fRope the forest ones, net the swimmers, and let a Black Hole take the brutes once they are weak.",
                        "&fFeed the shards to your Hunting Box and they become attributes for life."),
                        () -> HuntingMenus.main(p).open(p)));
    }

    private void commands() {
        Sky.commands().register(SkyCommand.of("hunting", "Open the Hunting menu: box, attributes, fusion, collection, traps, tools, safari")
                .alias("hunt").category("Skills").playerOnly().usage("[box|attributes|fusion|collection|traps|habitats|tools|salts|safari]")
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!Hunt.ready(p)) return;
                    String sub = a.length > 0 ? a[0].toLowerCase() : "";
                    switch (sub) {
                        case "box" -> HuntingMenus.box(p, 0).open(p);
                        case "attributes", "attrs" -> HuntingMenus.attributes(p, "ALL", 0).open(p);
                        case "fusion", "fuse" -> HuntingMenus.fusion(p, null, null).open(p);
                        case "collection", "shards" -> HuntingMenus.collection(p, "ALL", 0).open(p);
                        case "traps" -> HuntingMenus.traps(p).open(p);
                        case "habitats" -> HuntingMenus.habitats(p).open(p);
                        case "tools" -> HuntingMenus.tools(p).open(p);
                        case "salts" -> HuntingMenus.salts(p).open(p);
                        case "safari" -> HuntingMenus.safari(p).open(p);
                        default -> HuntingMenus.main(p).open(p);
                    }
                }).tab((s, a) -> a.length <= 1 ? List.of("box", "attributes", "fusion", "collection", "traps", "habitats", "tools", "salts", "safari") : List.of()));
        Sky.commands().register(SkyCommand.of("huntingbox", "Open your Hunting Box").alias("hbox").category("Skills").playerOnly()
                .run((s, a) -> { if (Hunt.ready((Player) s)) HuntingMenus.box((Player) s, 0).open((Player) s); }));
        Sky.commands().register(SkyCommand.of("attributes", "Open the Attribute Menu").alias("attributemenu").category("Skills").playerOnly()
                .run((s, a) -> { if (Hunt.ready((Player) s)) HuntingMenus.attributes((Player) s, "ALL", 0).open((Player) s); }));
        Sky.commands().register(SkyCommand.of("fusion", "Open the Fusion Machine").alias("fusionmachine").category("Skills").playerOnly()
                .run((s, a) -> { if (Hunt.ready((Player) s)) HuntingMenus.fusion((Player) s, null, null).open((Player) s); }));
        Sky.commands().register(SkyCommand.of("shards", "Your Shard Collection").category("Skills").playerOnly()
                .run((s, a) -> { if (Hunt.ready((Player) s)) HuntingMenus.collection((Player) s, "ALL", 0).open((Player) s); }));
        Sky.commands().register(SkyCommand.of("safari", "Go to the Critter Safari, or open its desk when you are there").category("Travel").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!Hunt.ready(p)) return;
                    if (SafariGenerator.ID.equals(Sky.worlds().islandOf(p.getWorld()))) HuntingMenus.safari(p).open(p);
                    else Sky.worlds().warp(p, "safari");
                }));
    }

    private void registerRecipes() {
        try (InputStream in = Sky.plugin().getResource("data/hunting/recipes.json")) {
            if (in == null) return;
            List<Recipe> list = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<Recipe>>() {}.getType());
            int ok = 0;
            List<String> skipped = new ArrayList<>();
            for (Recipe r : list) {
                if (r == null || r.result == null || !Sky.items().exists(r.result)) continue;
                boolean all = true;
                for (String cell : r.grid) {
                    if (cell == null || cell.isBlank()) continue;
                    String id = cell.contains(":") ? cell.substring(0, cell.lastIndexOf(':')) : cell;
                    if (!Sky.items().exists(id) && !Sky.items().exists(cell)) { all = false; break; }
                }
                if (!all) { skipped.add(r.result); continue; }
                Sky.recipes().register(r);
                ok++;
            }
            Sky.log().info("[hunting] " + ok + " tool recipes" + (skipped.isEmpty() ? "" : ", skipped (missing ingredient): " + skipped));
        } catch (Exception e) {
            Sky.log().warning("[hunting] recipes.json: " + e);
        }
    }

    // ---------------------------------------------------------------- per-player lifecycle

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) { Hunt.invalidate(e.player); }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) { forget(e.player.getUniqueId()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { forget(e.getPlayer().getUniqueId()); }

    private static void forget(UUID id) {
        Hunt.forget(id);
        AttributeEffects.forget(id);
        Tools.forget(id);
        Lasso.forget(id);
    }
}
