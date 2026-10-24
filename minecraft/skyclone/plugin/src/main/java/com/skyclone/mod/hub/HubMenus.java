package com.skyclone.mod.hub;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.WorldService;
import com.skyclone.mod.hub.gen.HubAnchors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The hub's menus: the Hub Map (every district, fast travel to the discovered ones, directions to
 * the rest), the Portal Directory (every island gate plus the launch pads and the mine gate, with
 * their real lock state), the District Guides and the Ship Navigator. Every one is also a command
 * ({@code /hubmap}, {@code /portals}, {@code /hubguides}, {@code /sail}) and reachable from the
 * Hub Map, which the SkyBlock Menu opens.
 */
final class HubMenus {

    private HubMenus() {}

    static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    static Material mat(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m == null ? fallback : m;
    }

    // ---- Hub Map ------------------------------------------------------------------------------

    static void openMap(Player p) { buildMap(p).open(p); }

    /** {@code p} may be null (self-test): everything shows as undiscovered. */
    static Menu buildMap(Player p) {
        Menu m = new Menu("Hub Map", 6).chrome();
        boolean ready = p != null && Sky.profiles().isReady(p);
        HubData hd = ready ? HubModule.data(p) : null;
        List<String> found = hd != null ? hd.discovered : List.of();
        int n = 0;
        for (HubDistrict d : HubDistrict.ALL) if (found.contains(d.id())) n++;
        m.set(4, Icons.of(Material.FILLED_MAP, "&aHub Map",
                "&7Every district of the Hub. Walk into one", "&7once and you can travel back to it", "&7from here at any time.", "",
                "&7Discovered: &a" + n + "&7/&a" + HubDistrict.ALL.size(), Text.bar((double) n / HubDistrict.ALL.size(), 20)));
        int i = 0;
        // first: the Village's own buildings (2026-10-03 playtest: a new player sent to the Banker or
        // the Fairy found nothing about them here, only districts)
        List<String> vl = new ArrayList<>(List.of("&8/village", "", "&7The shops and halls around the", "&7square: where each one is and", "&7which side its door is on.", ""));
        for (VillagePlace vp : VillagePlace.ALL) vl.add("&8- &" + vp.color() + vp.name());
        vl.add("");
        vl.add("&eClick to view!");
        m.set(GRID[i++], Icons.of(Material.BELL, "&bVillage", vl), c -> openVillage(c.player()));
        for (HubDistrict d : HubDistrict.ALL) {
            if (i >= GRID.length) break;
            boolean known = found.contains(d.id());
            List<String> lore = new ArrayList<>();
            lore.add("&8/warp " + d.warpId());
            lore.add("");
            for (String a : d.about()) lore.addAll(Text.wrap("&7" + a, 32));
            if (!d.services().isEmpty()) {
                lore.add("");
                lore.add("&7Here: &f" + String.join("&7, &f", d.services()));
            }
            HubGuides.Guide guide = HubGuides.byId(d.id());
            if (guide != null && hd != null && HubModule.guidesOn.get()) {
                lore.add("");
                lore.add(hd.guidesDone.contains(guide.id) ? "&7District Guide: &a&lCOMPLETE"
                        : "&7District Guide: &e" + HubGuides.stepsDone(hd, guide) + "&7/&e" + guide.steps.size());
            }
            Location at = Sky.worlds().anchor(HubModule.ISLAND, d.anchor());
            if (at != null) { lore.add(""); lore.add("&8Location: " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ()); }
            lore.add("");
            if (known) {
                lore.add(hd != null && hd.scrolls.contains(d.id()) ? "&a&lUNLOCKED &8(Travel Scroll)" : "&a&lDISCOVERED");
                lore.add("&eClick to travel!");
            } else {
                lore.add("&cNot discovered yet");
                lore.add("&7Walk into it once to unlock travel.");
                lore.add("&eClick to get directions!");
            }
            Material icon = known ? mat(d.icon(), Material.MAP) : Material.GRAY_DYE;
            m.set(GRID[i++], Icons.of(icon, "&" + d.color() + d.name(), lore), c -> clickDistrict(c.player(), d));
        }
        m.set(45, Icons.of(Material.COMPASS, "&aBack to Spawn", "&7Return to the village square.", "", "&eClick to travel!"),
                c -> Sky.worlds().warp(c.player(), HubModule.ISLAND));
        m.set(46, Icons.of(Material.OAK_BOAT, "&3Ship Navigator", "&7Captain Marlowe's ship at the", "&7Fishing Outpost sails to the", "&7fishing islands.", "",
                "&8/sail", "", "&eClick to view!"), c -> openShip(c.player()));
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.set(50, Icons.of(Material.ENDER_EYE, "&dPortal Directory", "&7Every island gate in the Portal", "&7Plaza, the launch pads and the", "&7Coal Mine gate, and whether", "&7they let you through yet.", "",
                "&8/portals", "", "&eClick to view!"), c -> openPortals(c.player()));
        int done = hd == null ? 0 : hd.guidesDone.size();
        m.set(52, Icons.of(Material.WRITABLE_BOOK, "&eDistrict Guides", "&7A short tour of each district,", "&7with a reward for finishing it.", "",
                "&7Completed: &a" + done + "&7/&a" + HubGuides.ALL.size(), "", "&8/hubguides", "", "&eClick to view!"), c -> openGuides(c.player()));
        int errands = 0;
        if (hd != null) for (Errands.Errand e : HubErrands.ALL) if (HubErrands.state(hd, e) == HubErrands.DONE) errands++;
        m.set(47, Icons.of(Material.PAPER, "&eTownsfolk Errands", "&7The Hub's residents each have a", "&7small favour to ask, and pay for", "&7it once.", "",
                "&7Completed: &a" + errands + "&7/&a" + HubErrands.ALL.size(), "", "&8/errands", "", "&eClick to view!"), c -> HubErrands.openMenu(c.player()));
        return m;
    }

    static void clickDistrict(Player p, HubDistrict d) {
        boolean known = Sky.profiles().isReady(p) && HubModule.data(p).discovered.contains(d.id());
        boolean inHub = HubModule.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()));
        if (known || !HubModule.discoveryNeeded.get()) {
            if (!inHub && !HubModule.farTravel.get()) { Msg.error(p, "You must be in the Hub to use the Hub Map."); return; }
            p.closeInventory();
            Sky.worlds().warp(p, d.warpId());
            return;
        }
        Location at = Sky.worlds().anchor(HubModule.ISLAND, d.anchor());
        if (at == null) { Msg.error(p, "That district is not on the map yet."); return; }
        p.closeInventory();
        if (!inHub) { Msg.send(p, "&7The &" + d.color() + d.name() + " &7is in the Hub. Head there first: &e/hub"); return; }
        Location me = p.getLocation();
        double dx = at.getX() - me.getX(), dz = at.getZ() - me.getZ();
        int dist = (int) Math.round(Math.hypot(dx, dz));
        p.setCompassTarget(at);
        Msg.send(p, "&7The &" + d.color() + d.name() + " &7is &e" + dist + " blocks &7to the &e" + compass(dx, dz)
                + "&7. &8(Your compass points there now.)");
        Msg.sound(p, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.2f);
    }

    /** "north-east" etc. for a vector in world coordinates (north = -z). */
    static String compass(double dx, double dz) {
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        int i = (int) Math.floorMod(Math.round(ang / 45.0), 8);
        return names[i];
    }

    // ---- Village Directory --------------------------------------------------------------------

    static void openVillage(Player p) { buildVillage(p).open(p); }

    /** {@code p} may be null (self-test): no distances are shown. */
    static Menu buildVillage(Player p) {
        Menu m = new Menu("Village", 6).chrome();
        m.set(4, Icons.of(Material.BELL, "&bVillage", "&7The buildings around the square.", "&7Click one and your compass points", "&7at its door."));
        boolean inHub = p != null && HubModule.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()));
        int i = 0;
        for (VillagePlace vp : VillagePlace.ALL) {
            if (i >= GRID.length) break;
            List<String> lore = new ArrayList<>();
            if (!vp.about().isEmpty()) lore.addAll(Text.wrap("&7" + vp.about(), 32));
            if (!vp.here().isEmpty()) { lore.add(""); lore.addAll(Text.wrap("&7Here: &f" + vp.here(), 32)); }
            if (!vp.entrance().isEmpty()) { lore.add(""); lore.addAll(Text.wrap("&7Way in: &f" + vp.entrance(), 32)); }
            Location at = Sky.worlds().anchor(HubModule.ISLAND, vp.anchor());
            if (at != null && inHub) {
                Location me = p.getLocation();
                double dx = at.getX() - me.getX(), dz = at.getZ() - me.getZ();
                lore.add("");
                lore.add("&7" + Math.round(Math.hypot(dx, dz)) + " blocks to the &e" + compass(dx, dz));
            }
            lore.add("");
            lore.add("&eClick to get directions!");
            m.set(GRID[i++], Icons.of(mat(vp.icon(), Material.MAP), "&" + vp.color() + vp.name(), lore), c -> clickPlace(c.player(), vp));
        }
        m.set(48, Icons.back("Hub Map"), c -> openMap(c.player()));
        return m;
    }

    /** Point the compass at the place's entrance and say where it is, in words. */
    static void clickPlace(Player p, VillagePlace vp) {
        p.closeInventory();
        if (!HubModule.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) {
            Msg.send(p, "&7The &" + vp.color() + vp.name() + " &7is in the Hub's Village. Head there first: &e/hub");
            return;
        }
        Location at = Sky.worlds().anchor(HubModule.ISLAND, vp.anchor());
        if (at == null) { Msg.error(p, "That place is not on the map yet."); return; }
        Location me = p.getLocation();
        double dx = at.getX() - me.getX(), dz = at.getZ() - me.getZ();
        int dist = (int) Math.round(Math.hypot(dx, dz));
        p.setCompassTarget(at);
        if (dist <= 3) Msg.send(p, "&7You are at the &" + vp.color() + vp.name() + "&7. " + vp.entrance());
        else Msg.send(p, "&7The &" + vp.color() + vp.name() + " &7is &e" + dist + " blocks &7to the &e" + compass(dx, dz)
                + "&7. " + vp.entrance() + " &8(Your compass points there now.)");
        Msg.sound(p, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.2f);
    }

    // ---- Portal Directory ---------------------------------------------------------------------

    /** The hub's other exits: travel spot area, district it stands in, icon, where it is. */
    record Exit(String area, String district, Material icon, String where) {
        String warp() { return area.substring(area.indexOf('.') + 1); }
        boolean pad() { return area.startsWith("pad."); }
    }

    static final List<Exit> EXITS = List.of(
            new Exit("pad.spiders_den", "graveyard", Material.SLIME_BLOCK, "At the far end of the Graveyard walk."),
            new Exit("pad.barn", "farm", Material.SLIME_BLOCK, "At the east end of the Farm's middle track."),
            new Exit("pad.park", "foraging_camp", Material.SLIME_BLOCK, "In the Foraging Camp, by the sawmill."),
            new Exit("gate.gold_mine", "coal_mine", Material.DEEPSLATE_TILES, "At the back of the Coal Mine's chamber."),
            new Exit("gate.rift", "wizard_tower", Material.AMETHYST_CLUSTER, "The glowing arch west of the Wizard Tower."));

    static void openPortals(Player p) { buildPortals(p).open(p); }

    static Menu buildPortals(Player p) {
        Menu m = new Menu("Portal Directory", 6).chrome();
        m.set(4, Icons.of(Material.END_PORTAL_FRAME, "&dPortal Plaza", "&7The gates west of the village lead", "&7to every other island. The icy",
                "&7one in the middle only opens in", "&7late winter.", "", "&7Walk into a gate, or click one here."));
        int i = 0;
        for (String id : HubAnchors.PORTALS) {
            WorldService.WarpInfo w = warp(id);
            List<String> lore = new ArrayList<>();
            String name;
            Material icon = portalIcon(id);
            if (w == null) {
                name = "&8" + Text.titleCase(id);
                lore.add("&8This island is not open yet.");
                icon = Material.GRAY_DYE;
            } else {
                name = "&b" + w.displayName();
                lore.add("&8/warp " + w.id());
                lore.add("");
                lore.addAll(lockLines(p, w));
                lore.add("");
                lore.add("&eClick to travel!");
            }
            WorldService.WarpInfo fw = w;
            m.set(GRID[i++], Icons.of(icon, name, lore), c -> {
                if (fw == null) { Msg.error(c.player(), "That island is not open yet."); return; }
                c.player().closeInventory();
                Sky.worlds().warp(c.player(), fw.id());
            });
        }
        // the launch pads and the mine gate: shown on the row below
        int[] exitSlots = {37, 38, 39, 40, 41};
        for (int k = 0; k < EXITS.size() && k < exitSlots.length; k++) {
            Exit ex = EXITS.get(k);
            WorldService.WarpInfo w = warp(ex.warp());
            HubDistrict d = HubDistrict.byId(ex.district());
            List<String> lore = new ArrayList<>();
            lore.add("&7" + ex.where());
            lore.add("");
            if (w == null) lore.add("&8Leads nowhere yet.");
            else lore.addAll(lockLines(p, w));
            lore.add("");
            lore.add("&eClick to go to the " + (ex.pad() ? "pad" : "gate") + "!");
            String kind = ex.pad() ? "&aLaunch Pad: &b" : ex.warp().equals("rift") ? "&dRift Arch: &b" : "&aTunnel Gate: &b";
            String title = kind + (w == null ? Text.titleCase(ex.warp()) : w.displayName());
            m.set(exitSlots[k], Icons.of(ex.icon(), title, lore), c -> { if (d != null) clickDistrict(c.player(), d); });
        }
        m.set(36, Icons.of(Material.SLIME_BALL, "&aOther Ways Out", "&7Launch pads fling you to the", "&7next island; the tunnel gate and", "&7the Rift arch walk you there. All", "&7check the same requirements as", "&7the gates."));
        m.set(48, Icons.back("Hub Map"), c -> openMap(c.player()));
        m.set(50, Icons.of(Material.OAK_BOAT, "&3Ship Navigator", "&7Sail to the fishing islands.", "", "&eClick to view!"), c -> openShip(c.player()));
        return m;
    }

    /** "&aOpen to everyone" / "&aUnlocked (...)" / "&cRequires ...", plus a still-building note. */
    static List<String> lockLines(Player p, WorldService.WarpInfo w) {
        List<String> out = new ArrayList<>();
        String req = requirementOf(w);
        if (req == null) out.add("&aOpen to everyone");
        else if (p != null && Sky.requirements().check(p, req)) out.add("&aUnlocked &8(" + Text.strip(Sky.requirements().describe(req)) + ")");
        else out.add("&cRequires " + Sky.requirements().describe(req));
        IslandDef isl = Sky.worlds().island(w.island());
        if (isl != null && !isl.instanced && !Sky.worlds().isBuilt(w.island())) out.add("&eStill being built...");
        return out;
    }

    static WorldService.WarpInfo warp(String id) {
        for (WorldService.WarpInfo w : Sky.worlds().warpInfos()) if (w.id().equalsIgnoreCase(id)) return w;
        // the private island's warp may be registered as "home" by the island module
        return id.equals("private_island") ? warp("home") : null;
    }

    /** The warp's own requirement, and its island's, joined with '&'; null when there is none. */
    static String requirementOf(WorldService.WarpInfo w) {
        List<String> parts = new ArrayList<>();
        IslandDef isl = Sky.worlds().island(w.island());
        if (isl != null && isl.requirement != null && !isl.requirement.isBlank()) parts.add(isl.requirement);
        if (w.requirement() != null && !w.requirement().isBlank()) parts.add(w.requirement());
        return parts.isEmpty() ? null : String.join(" & ", parts);
    }

    static Material portalIcon(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "private_island" -> Material.GRASS_BLOCK;
            case "barn" -> Material.HAY_BLOCK;
            case "park" -> Material.BIRCH_SAPLING;
            case "gold_mine" -> Material.GOLD_ORE;
            case "spiders_den" -> Material.COBWEB;
            case "the_end" -> Material.END_STONE;
            case "crimson_isle" -> Material.NETHERRACK;
            case "dungeon_hub" -> Material.SKELETON_SKULL;
            case "garden" -> Material.FLOWERING_AZALEA;
            case "backwater_bayou" -> Material.MANGROVE_ROOTS;
            case "jerrys_workshop" -> Material.SNOW_BLOCK;
            default -> Material.ENDER_PEARL;
        };
    }

    // ---- District Guides ----------------------------------------------------------------------

    static void openGuides(Player p) { buildGuides(p).open(p); }

    static Menu buildGuides(Player p) {
        Menu m = new Menu("District Guides", 6).chrome();
        HubData hd = p != null && Sky.profiles().isReady(p) ? HubModule.data(p) : null;
        int done = hd == null ? 0 : hd.guidesDone.size();
        m.set(4, Icons.of(Material.WRITABLE_BOOK, "&eDistrict Guides", "&7Each district of the Hub has a", "&7short guide: meet its people and",
                "&7try what it is for. Steps count in", "&7any order, and finishing a guide", "&7pays a reward once.", "",
                "&7Completed: &a" + done + "&7/&a" + HubGuides.ALL.size(),
                HubModule.guidesOn.get() ? "" : "&cGuides are switched off right now."));
        int i = 0;
        for (HubGuides.Guide g : HubGuides.ALL) {
            if (i >= GRID.length) break;
            boolean complete = hd != null && hd.guidesDone.contains(g.id);
            List<String> lore = new ArrayList<>();
            if (g.about != null) lore.addAll(Text.wrap("&7" + g.about, 32));
            lore.add("");
            for (int k = 0; k < g.steps.size(); k++) {
                HubGuides.Step s = g.steps.get(k);
                boolean ok = complete || hd != null && HubGuides.stepDone(hd, g, k);
                int need = HubGuides.need(s);
                String count = need > 1 && !ok ? " &8(" + (hd == null ? 0 : HubGuides.have(hd, g, k)) + "/" + need + ")" : "";
                lore.add((ok ? "&a✔ &7" : "&c✖ &f") + s.text + count);
            }
            lore.add("");
            lore.add("&7Reward:");
            double coins = g.coins * HubModule.guideCoinScale.get();
            if (coins > 0) lore.add(" &6" + Text.commasInt(coins) + " Coins");
            if (g.sbxp > 0) lore.add(" &b" + g.sbxp + " SkyBlock XP");
            lore.add("");
            HubDistrict d = HubDistrict.byId(g.id);
            if (complete) lore.add("&a&lCOMPLETE");
            else lore.add("&eClick to " + (hd != null && d != null && hd.discovered.contains(d.id()) ? "travel there!" : "get directions!"));
            Material icon = complete ? Material.LIME_DYE : mat(g.icon, Material.BOOK);
            m.set(GRID[i++], Icons.of(icon, (complete ? "&a" : "&e") + g.name + " Guide", lore), c -> {
                if (d != null) clickDistrict(c.player(), d);
            });
        }
        m.set(48, Icons.back("Hub Map"), c -> openMap(c.player()));
        return m;
    }

    // ---- Ship Navigator -----------------------------------------------------------------------

    /** A ship destination: warp id, name shown when the island is missing, icon, blurb. */
    record Voyage(String warp, String name, Material icon, String about, List<String> there) {}

    /**
     * Research 03 section 1.1: Captain Baha sails to the Backwater Bayou (Fishing V) and the Lotus Atoll (Fishing X).
     * Each island's warp carries its own gate (fishing's Bayou warp is open; galatea's {@code lotus_atoll} warp needs
     * {@code galatea.atoll-requirement}, Fishing X by default), so the ship reads the warp and adds only
     * {@code hub.ship-requirement}. A destination whose island module is missing shows as "not charted yet".
     */
    static final List<Voyage> VOYAGES = List.of(
            new Voyage("backwater_bayou", "Backwater Bayou", Material.MANGROVE_ROOTS, "A murky swamp where fish bite that bite nowhere else.",
                    List.of("Bayou sea creatures", "the Tackle-smith's rod parts", "a lighthouse and the Moonlit Marsh")),
            new Voyage("lotus_atoll", "Lotus Atoll", Material.LILY_PAD, "A ring of land round a lily-choked lagoon, far out to sea.",
                    List.of("Trophy Frogs and the frog professor", "Frogcoin blessings in the cave", "rain every hour: +Fishing Speed")));

    static void openShip(Player p) { buildShip(p).open(p); }

    /** The voyage's full requirement: the warp's (and its island's) plus the ship dial. */
    static String voyageRequirement(WorldService.WarpInfo w) {
        String base = w == null ? null : requirementOf(w);
        String extra = HubModule.shipRequirement.get();
        if (extra == null || extra.isBlank()) return base;
        return base == null ? extra.trim() : base + " & " + extra.trim();
    }

    static Menu buildShip(Player p) {
        Menu m = new Menu("Ship Navigator", 6).chrome();
        m.set(4, Icons.of(Material.OAK_BOAT, "&3Captain Marlowe's Ship", "&7Moored at the Fishing Outpost dock.", "&7Pick a destination and the ship", "&7casts off."));
        int[] slots = {20, 22, 24};
        for (int k = 0; k < VOYAGES.size() && k < slots.length; k++) {
            Voyage v = VOYAGES.get(k);
            WorldService.WarpInfo w = warp(v.warp());
            List<String> lore = new ArrayList<>(Text.wrap("&7" + v.about(), 32));
            lore.add("");
            for (String t : v.there()) lore.add("&8- &7" + t);
            lore.add("");
            if (w == null) {
                lore.add("&8Not charted yet.");
                m.set(slots[k], Icons.of(Material.GRAY_DYE, "&8" + v.name(), lore), c -> Msg.error(c.player(), "The captain has no chart for that yet."));
                continue;
            }
            String req = voyageRequirement(w);
            boolean ok = req == null || p != null && Sky.requirements().check(p, req);
            lore.add("&8/warp " + w.id());
            lore.add(req == null ? "&aOpen to everyone" : ok ? "&aUnlocked" : "&cRequires " + Sky.requirements().describe(req));
            IslandDef isl = Sky.worlds().island(w.island());
            if (isl != null && !isl.instanced && !Sky.worlds().isBuilt(w.island())) lore.add("&eStill being built...");
            lore.add("");
            lore.add(ok ? "&eClick to set sail!" : "&cLocked");
            m.set(slots[k], Icons.of(v.icon(), (ok ? "&a" : "&c") + w.displayName(), lore), c -> sail(c.player(), v.warp()));
        }
        m.set(48, Icons.back("Hub Map"), c -> openMap(c.player()));
        return m;
    }

    static void sail(Player p, String warpId) {
        WorldService.WarpInfo w = warp(warpId);
        if (w == null) { Msg.error(p, "The captain has no chart for that yet."); return; }
        String req = voyageRequirement(w);
        if (req != null && !Sky.requirements().checkOrTell(p, req)) return;
        p.closeInventory();
        Msg.send(p, "&e[NPC] &fCaptain Marlowe&f: Cast off! Next stop, &b" + w.displayName() + "&f!");
        Msg.sound(p, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.8f);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) Sky.worlds().warp(p, warpId); }, 30L);
    }
}
