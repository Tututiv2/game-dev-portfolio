package com.skyclone.mod.foraging;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.foraging.gen.MarshGenerator;
import com.skyclone.mod.foraging.gen.ParkGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * FORAGING: logs, Sweep and Foraging Fortune, the axes, regrowing trees, The Park (five forests and
 * the Howling Cave), the Moonmarsh (shared tough trees, Tree Gifts) and the Heart of the Forest.
 */
public final class ForagingModule implements GameModule {

    private static TreeRegen regen;
    private static Chopper chopper;
    private static MarshTrees marsh;
    private static Hives hives;

    static Hives hives() { return hives; }

    @Override public String id() { return "foraging"; }

    static ForagingData data(Player p) { return Sky.profiles().member(p, "foraging", ForagingData.class); }

    static MarshTrees marsh() { return marsh; }

    static TreeRegen regen() { return regen; }

    static Chopper chopper() { return chopper; }

    @Override
    public void enable() {
        FDials.register();
        Sky.items().loadJson("foraging", "items.json");
        Sky.recipes().loadJson("foraging", "recipes.json");
        Sky.mobs().loadJson("foraging", "mobs.json");
        Sky.mobs().loadSpawnRules("foraging", "spawns.json");
        mobBehaviours();

        // islands + warps
        Sky.worlds().registerIsland(IslandDef.of(ParkGenerator.ID, "The Park", new ParkGenerator()));
        Sky.worlds().registerWarp("park", ParkGenerator.ID, "spawn", "The Park", "skill:foraging:1");
        Sky.worlds().registerWarp("jungle", ParkGenerator.ID, "warp.jungle", "Jungle Island", "skill:foraging:5 & foraging:scroll:jungle");
        Sky.worlds().registerWarp("howl", ParkGenerator.ID, "warp.howl", "Howling Cave", "skill:foraging:2 & foraging:scroll:howl");
        Sky.worlds().registerIsland(IslandDef.of(MarshGenerator.ID, "The Moonmarsh", new MarshGenerator()));
        Sky.worlds().registerWarp("moonmarsh", MarshGenerator.ID, "spawn", "The Moonmarsh", "skill:foraging:12");
        // wave 5: the Torrent Canyon is the galatea module's own island now; "canyon" goes there, and the Moonmarsh's
        // own spiral mesa (same anchor as before) is the Spiral Mesa
        Sky.worlds().registerWarp("canyon", Chopper.TORRHUS, "spawn", "Torrent Canyon", "skill:foraging:12 & hotf:4");
        Sky.worlds().registerWarp("mesa", MarshGenerator.ID, "warp.canyon", "Spiral Mesa", "skill:foraging:12 & hotf:4");

        // requirement prefixes we own
        Sky.requirements().register("foraging", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 3 || !Sky.profiles().isReady(p)) return false;
                ForagingData d = data(p);
                return switch (parts[1]) {
                    case "scroll" -> d.scrolls.contains(parts[2]);
                    case "quest" -> d.quests.getOrDefault(parts[2], 0) >= (parts.length > 3 ? parse(parts[3], 3) : 3);
                    case "trial" -> d.trialTier >= parse(parts[2], 1);
                    default -> false;
                };
            }
            @Override public String describe(String[] parts) {
                if (parts.length < 3) return "&cForaging ?";
                return switch (parts[1]) {
                    case "scroll" -> "&aTravel Scroll to " + Text.titleCase(parts[2]);
                    case "quest" -> "&a" + Text.titleCase(parts[2]) + " keeper's request";
                    case "trial" -> "&6Ember Trial " + parts[2];
                    default -> "&a" + String.join(":", parts);
                };
            }
        });
        Sky.requirements().register("hotf", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (!Sky.profiles().isReady(p)) return false;
                ForagingData d = data(p);
                return d.hotfUnlocked && Hotf.tier(d) >= (parts.length > 1 ? parse(parts[1], 1) : 1);
            }
            @Override public String describe(String[] parts) {
                return "&2Heart of the Forest " + Text.roman(parts.length > 1 ? parse(parts[1], 1) : 1);
            }
        });

        // the systems
        regen = new TreeRegen();
        chopper = new Chopper(regen);
        marsh = new MarshTrees(regen);
        Sky.worlds().addBreakHandler(chopper);
        Bukkit.getPluginManager().registerEvents(chopper, Sky.plugin());
        new AxeAbilities(chopper).register();
        new HotfAbilities().register();
        hives = new Hives();
        hives.register();
        new Depths().register();
        Boosters.register();
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { HotfAbilities.forget(e.getPlayer().getUniqueId()); }
        }, Sky.plugin());
        ForagingNpcs.register();

        // commands
        Sky.commands().register(SkyCommand.of("foraging", "Open the Foraging menu: woods, axes, forests, requests")
                .alias("forage", "trees").category("Skills").playerOnly().run((s, a) -> ForagingMenus.main((Player) s).open((Player) s)));
        Sky.commands().register(SkyCommand.of("hotf", "Open the Heart of the Forest")
                .alias("heartoftheforest").category("Skills").playerOnly().run((s, a) -> Hotf.menu((Player) s).open((Player) s)));
        Sky.commands().register(SkyCommand.of("starfall", "Starfall Contests: your points, the Herbalist's and the Curator's shops")
                .alias("forestcontest").category("Skills").playerOnly().run((s, a) -> Contest.menu((Player) s).open((Player) s)));
        Sky.commands().register(SkyCommand.of("axes", "Every axe and where to get it")
                .category("Skills").playerOnly().run((s, a) -> ForagingMenus.axes((Player) s, 0).open((Player) s)));
        ForagingAdmin.register();
        ForagingSelfTests.register();

        Sky.sidebar().tabWidget("foraging", 160, p -> {
            if (!Sky.profiles().isReady(p) || !Chopper.foragingIsland(Chopper.island(p.getWorld()))) return List.of();
            ForagingData d = data(p);
            return List.of("&2&lForaging", " &2∮ Sweep: &a" + Text.commas(Sky.stats().get(p, Stat.SWEEP)),
                    " &6☘ Fortune: &a" + Text.commas(Sky.stats().get(p, Stat.FORAGING_FORTUNE)),
                    " &7Logs felled: &a" + Text.shortNum(d.logs));
        });
        Sky.sidebar().section("foraging", 300, p -> {
            if (!Sky.profiles().isReady(p) || !Chopper.toughIsland(Chopper.island(p.getWorld()))) return List.of();
            ForagingData d = data(p);
            List<String> out = new java.util.ArrayList<>();
            if (d.hotfUnlocked) {
                out.add("&bForest Whispers: &3" + Text.shortNum(d.forestWhispers));
                out.add("&eDesert Whispers: &6" + Text.shortNum(d.desertWhispers));
            }
            Boolean canyon = Contest.canyonAt(p);
            out.addAll(Contest.lines(p, d, canyon != null && canyon));
            if (!d.lathered.isEmpty()) {
                long next = Long.MAX_VALUE;
                for (ForagingData.Lather l : d.lathered) next = Math.min(next, l.due);
                long s = Math.max(0, (next - System.currentTimeMillis()) / 1000);
                out.add("&6Honey lure: &e" + (s == 0 ? "ready!" : Text.duration(s)));
            }
            return out;
        });
    }

    @Override
    public void postEnable() {
        // drop rows naming items nobody registered (logged; the self-test lists the core ones)
        for (MobDef def : Sky.mobs().all()) {
            if (!def.id.startsWith("park_") && !def.id.startsWith("marsh_")) continue;
            def.drops.removeIf(dr -> {
                boolean missing = !Sky.items().exists(dr.item);
                if (missing) Sky.log().info("[foraging] mob " + def.id + ": dropping unknown drop " + dr.item);
                return missing;
            });
        }
        Bukkit.getScheduler().runTask(Sky.plugin(), regen::start);   // after boot: worlds exist
        Contest.start();
    }

    @Override
    public void disable() {
        if (regen != null) regen.stop();
    }

    static int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // ================================================================================ wolves

    private static final ThreadLocal<Boolean> TRUE_HIT = ThreadLocal.withInitial(() -> false);

    private void mobBehaviours() {
        Sky.mobs().registerBehaviour("foraging_wolf", new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) {
                if (mob instanceof org.bukkit.entity.Wolf w) { w.setAngry(true); }
            }
        });
        // the Shade of the Alpha bites with TRUE damage (research 03 §7.3) and howls now and then
        Sky.mobs().registerBehaviour("foraging_alpha", new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) {
                if (mob instanceof org.bukkit.entity.Wolf w) { w.setAngry(true); }
            }
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (tick % 240 == 0) mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_WOLF_GROWL, 2f, 0.5f);
            }
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                if (TRUE_HIT.get() || ctx.kind == DamageContext.Kind.TRUE) return;
                ctx.cancelled = true;
                TRUE_HIT.set(true);
                try { Sky.combat().damagePlayer(target, def.damage, mob, DamageContext.Kind.TRUE); }
                finally { TRUE_HIT.set(false); }
            }
        });
    }
}
