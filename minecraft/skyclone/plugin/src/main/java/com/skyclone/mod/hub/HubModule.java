package com.skyclone.mod.hub;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.config.Dials;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.hub.gen.HubGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The Hub: registers the island and its generator, the hub warp and one fast-travel sub-warp per
 * district (unlocked by walking into it), the hub's own mobs and spawn rules, its flavour NPCs,
 * floating labels over the Portal Plaza gates, the Hub Map ({@code /hubmap}) and the Portal
 * Directory ({@code /portals}).
 */
public final class HubModule implements GameModule, Listener {

    public static final String ISLAND = "hub";

    static Dials.Dial<Boolean> labels, discoveryNeeded, discoveryMessage, npcsOn, farTravel, travelSpots, guidesOn, guideSidebar, practiceOn, forestHint, errandsOn, errandSidebar;
    static Dials.Dial<Double> spawnScale, goldenGhoulRespawn, padPower, guideCoinScale, practiceCoinScale, errandCoinScale;
    static Dials.Dial<Integer> padFlightTicks, practiceSeconds, practicePenalty;
    static Dials.Dial<String> shipRequirement;

    /** Our spawn rules, kept so the spawn-scale dial can retune them live. */
    static final List<SpawnRule> RULES = new ArrayList<>();
    static final Map<String, Integer> BASE_MAX = new HashMap<>();

    private PortalLabels portalLabels;
    static final TravelSpots travel = new TravelSpots();

    @Override public String id() { return "hub"; }

    static HubData data(Player p) { return Sky.profiles().member(p, "hub", HubData.class); }

    @Override
    public void enable() {
        Dials d = Sky.dials();
        labels = d.flag("hub.portal-labels", true, "Show floating destination labels over the Portal Plaza gates");
        discoveryNeeded = d.flag("hub.subwarps-need-discovery", true, "Hub district fast travel unlocks only after walking into the district");
        discoveryMessage = d.flag("hub.discovery-message", true, "Tell players when a hub district's fast travel unlocks");
        npcsOn = d.flag("hub.npcs", true, "Register the hub's own flavour NPCs (guide, portal keeper, miner...)");
        farTravel = d.flag("hub.map-travel-anywhere", true, "The Hub Map can send you to a discovered district from any island");
        spawnScale = d.number("hub.spawn-scale", 1.0, "Multiplier on how many hub mobs each spawn area keeps alive");
        goldenGhoulRespawn = d.number("hub.golden-ghoul-respawn", 180, "Seconds before the crypts' Golden Ghoul returns (TUNE: research gives no number)");
        travelSpots = d.flag("hub.travel-spots", true, "Launch pads (Graveyard, Farm, Foraging Camp) and the Coal Mine's Gold Mine gate work");
        padPower = d.number("hub.pad-power", 1.5, "Upward speed a launch pad gives (TUNE: feel only)");
        padFlightTicks = d.integer("hub.pad-flight-ticks", 30, "Ticks in the air before a launch pad lands you on the other island (TUNE)");
        guidesOn = d.flag("hub.guides", true, "District Guides (per-district first-visit objectives with rewards)");
        guideSidebar = d.flag("hub.guide-sidebar", true, "Show the current district guide step on the sidebar");
        guideCoinScale = d.number("hub.guide-coin-scale", 1.0, "Multiplier on District Guide coin rewards (TUNE: the reference does not publish them)");
        shipRequirement = d.text("hub.ship-requirement", "", "Extra requirement to sail with the hub ship (research: Fishing V for the Bayou; blank = same as the Bayou warp)");
        practiceOn = d.flag("hub.practice", true, "Target Practice at the Archery Range (Range Master Corliss)");
        practiceSeconds = d.integer("hub.practice-seconds", 60, "Length of one Target Practice round in seconds (TUNE)");
        practiceCoinScale = d.number("hub.practice-coin-scale", 1.0, "Multiplier on Target Practice medal coins (TUNE: the reference publishes none)");
        forestHint = d.flag("hub.forest-hint", true, "Breaking a decorative tree outside the Forest says where the choppable Forest is and points the compass there");
        practicePenalty = d.integer("hub.practice-wrong-penalty", 1, "Points a Target Practice arrow in a DARK target costs (the light also moves; TUNE)");
        errandsOn = d.flag("hub.errands", true, "Townsfolk errands: the Hub's residents and own NPCs each offer one small paid favour (/errands)");
        errandSidebar = d.flag("hub.errand-sidebar", true, "Show the running errand's next step on the sidebar in the Hub (outside a District Guide's district)");
        errandCoinScale = d.number("hub.errand-coin-scale", 1.0, "Multiplier on townsfolk errand coin rewards (TUNE: the reference publishes none)");
        spawnScale.onChange(v -> rescale());
        goldenGhoulRespawn.onChange(v -> rescale());

        IslandDef def = IslandDef.of(ISLAND, "Hub", new HubGenerator());
        Sky.worlds().registerIsland(def);
        Sky.worlds().registerWarp(ISLAND, ISLAND, "spawn", "Hub", null);
        for (HubDistrict dist : HubDistrict.ALL)
            Sky.worlds().registerWarp(dist.warpId(), ISLAND, dist.anchor(), dist.name(), "hubzone:" + dist.id());
        Sky.requirements().register("hubzone", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2) return false;
                if (!discoveryNeeded.get()) return true;
                return Sky.profiles().isReady(p) && data(p).discovered.contains(parts[1]);
            }
            @Override public String describe(String[] parts) {
                HubDistrict dd = parts.length < 2 ? null : HubDistrict.byId(parts[1]);
                return "&adiscovering the " + (dd == null ? (parts.length < 2 ? "?" : parts[1]) : dd.name()) + " in the Hub";
            }
        });

        TargetPractice.registerRequirement();
        GraveyardPack.register();   // before the mobs: their defs name it in "behaviours"
        GoldenVault.register();     // likewise (the Golden Ghoul's hub_golden_vault)
        Sky.items().loadJson("hub", "items.json");
        Sky.mobs().loadJson("hub", "mobs.json");
        loadSpawns();
        HubGuides.load();
        HubErrands.load();
        HubNpcs.load();
        // 450: below every LIVE section (mayors' Spooky candy and fishing are 400). At 350 a District Guide hid the
        // Spooky Festival's candy count in the Graveyard - the place candy is farmed (wave-4 endgame playtest).
        Sky.sidebar().section("hub.guide", 450, HubGuides::sidebar);
        Sky.sidebar().section("hub.errand", 451, HubErrands::sidebar);

        portalLabels = new PortalLabels();
        HubAdmin.HubModuleAccess.refresher = () -> { portalLabels.clear(); portalLabels.tick(); };
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(portalLabels, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(travel, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new HubGuides(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(TargetPractice.INSTANCE, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new ForestHint(), Sky.plugin());

        Sky.commands().register(SkyCommand.of("hubmap", "Open the Hub Map (district fast travel)").alias("hm", "districts")
                .category("Travel").playerOnly().run((s, a) -> HubMenus.openMap((Player) s)));
        Sky.commands().register(SkyCommand.of("village", "Open the Village Directory (where each Hub building is, and its door)").alias("town")
                .category("Travel").playerOnly().run((s, a) -> HubMenus.openVillage((Player) s)));
        Sky.commands().register(SkyCommand.of("portals", "Open the island Portal Directory").category("Travel").playerOnly()
                .run((s, a) -> HubMenus.openPortals((Player) s)));
        Sky.commands().register(SkyCommand.of("hubguides", "Open the Hub's District Guides").alias("districtguides").category("General").playerOnly()
                .run((s, a) -> HubMenus.openGuides((Player) s)));
        Sky.commands().register(SkyCommand.of("sail", "Open the Ship Navigator (the hub ship to the fishing islands)").alias("ship").category("Travel").playerOnly()
                .run((s, a) -> HubMenus.openShip((Player) s)));
        Sky.commands().register(SkyCommand.of("errands", "Townsfolk Errands: what the Hub's people asked of you, and where they live").alias("townsfolk")
                .category("General").playerOnly().run((s, a) -> HubErrands.openMenu((Player) s)));
        HubAdmin.register();
        HubSelfTests.register();
    }

    @Override
    public void postEnable() {
        if (npcsOn.get()) HubNpcs.register();     // after every module's enable, so we can yield shared spots
        if (practiceOn.get()) TargetPractice.INSTANCE.registerNpc();
        // drop lines whose item no module registered: a missing item must never break a kill
        for (Iterator<MobDef> it = hubMobs().iterator(); it.hasNext(); ) {
            MobDef m = it.next();
            m.drops.removeIf(dr -> {
                boolean missing = !Sky.items().exists(dr.item);
                if (missing) Sky.log().info("[hub] mob " + m.id + ": drop " + dr.item + " is not a registered item; skipped");
                return missing;
            });
        }
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), portalLabels::tick, 60L, 200L);
    }

    @Override
    public void disable() {
        if (portalLabels != null) portalLabels.clear();
        TargetPractice.INSTANCE.stopAll();
        for (GraveyardPack gp : GraveyardPack.ALL) gp.wakeAll();
    }

    static List<MobDef> hubMobs() {
        List<MobDef> out = new ArrayList<>();
        for (MobDef m : Sky.mobs().all()) if (m.id != null && m.id.startsWith("hub_")) out.add(m);
        return out;
    }

    private void loadSpawns() {
        try (InputStream in = Sky.plugin().getResource("data/hub/spawns.json")) {
            if (in == null) { Sky.log().warning("[hub] data/hub/spawns.json missing"); return; }
            List<SpawnRule> rules = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<SpawnRule>>() {}.getType());
            for (SpawnRule r : rules) {
                BASE_MAX.put(r.id, r.max);
                RULES.add(r);
            }
            rescale();
            for (SpawnRule r : RULES) Sky.mobs().addSpawnRule(r);
        } catch (Exception e) {
            Sky.log().warning("[hub] could not load spawns.json: " + e);
        }
    }

    static void rescale() {
        double s = Math.max(0, spawnScale.get());
        for (SpawnRule r : RULES) {
            int base = BASE_MAX.getOrDefault(r.id, r.max);
            r.max = base == 0 ? 0 : Math.max(s == 0 ? 0 : 1, (int) Math.round(base * s));
            if (r.mob.equals("hub_golden_ghoul")) r.respawnSeconds = Math.max(5, goldenGhoulRespawn.get());
        }
    }

    // ---- discovery ----------------------------------------------------------------------------

    @EventHandler
    public void onZone(SkyEvents.ZoneChange e) {
        if (!ISLAND.equals(e.island) || e.to == null || !Sky.profiles().isReady(e.player)) return;
        HubData hd = data(e.player);
        if (hd.firstVisit == 0) hd.firstVisit = System.currentTimeMillis();
        // the smallest zone wins the event, so also look at every zone around the player: walking
        // straight into the chapel, or a warp landing inside a building, still discovers the district
        Layout l = Sky.worlds().layout(ISLAND);
        Location at = e.player.getLocation();
        List<String> here = new ArrayList<>();
        here.add(e.to.id());
        if (l != null)
            for (Layout.Zone z : l.zones())
                if (z.box().contains(at.getX(), at.getY(), at.getZ()) && !here.contains(z.id())) here.add(z.id());
        for (String zid : here) {
            HubGuides.progress(e.player, "zone:" + zid, 1);
            if (HubGuides.byId(zid) != null) hd.lastDistrict = zid;
            HubDistrict dist = HubDistrict.byId(zid);
            if (dist != null) discover(e.player, hd, dist, null);
        }
        if (here.contains("graveyard")) GraveyardPack.maybeTip(e.player);
        if (here.contains(GoldenVault.AREA)) GoldenVault.maybeVaultWarning(e.player);
        else if (here.contains("crypt") || here.contains("crypt_landing")) GoldenVault.maybeCryptTip(e.player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        GraveyardPack.forget(e.getPlayer().getUniqueId());
        GoldenVault.forget(e.getPlayer().getUniqueId());
        ForestHint.forget(e.getPlayer().getUniqueId());
    }

    /** Unlock a district's fast travel; {@code how} prefixes the message (scrolls). True if it was new. */
    static boolean discover(Player p, HubData hd, HubDistrict dist, String how) {
        if (hd.discovered.contains(dist.id())) return false;
        hd.discovered.add(dist.id());
        if (how != null || discoveryMessage.get())
            Msg.send(p, (how == null ? "" : how + " ") + "&7Fast travel to &" + dist.color() + dist.name() + " &7unlocked! &8(&e/warp "
                    + dist.warpId() + "&8, " + hd.discovered.size() + "/" + HubDistrict.ALL.size() + " districts)");
        return true;
    }

    // ---- travel scrolls -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onScroll(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType().isAir()) return;
        String id = Sky.items().idOf(it);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (def == null || def.extra == null || !def.extra.has("hubDistrict")) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        HubDistrict dist = HubDistrict.byId(def.extra.get("hubDistrict").getAsString());
        if (dist == null) { Msg.error(p, "This scroll's destination no longer exists."); return; }
        HubData hd = data(p);
        if (hd.discovered.contains(dist.id())) { Msg.error(p, "You already unlocked this destination."); return; }
        // take exactly one from the main hand as it is NOW (never trust the event's stack copy)
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!id.equals(Sky.items().idOf(hand)) || hand.getAmount() < 1) return;
        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        discover(p, hd, dist, "&a&lSCROLL READ!");
        if (!hd.scrolls.contains(dist.id())) hd.scrolls.add(dist.id());
        Msg.sound(p, Sound.ITEM_BOOK_PAGE_TURN, 1f);
        Sky.profiles().saveNow(p);
    }

}
