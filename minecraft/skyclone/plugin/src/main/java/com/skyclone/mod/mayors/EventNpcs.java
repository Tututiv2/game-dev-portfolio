package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Every NPC of this module.
 * <ul>
 *   <li>Hub, CONTRACT anchors: the Ballot Clerk ({@code npc.election}) and Ashgrave ({@code npc.dark_auction}).</li>
 *   <li>Winter Workshop (our island): Saint Gerald, Ingvar, Marnie — and the five seasonal vendors,
 *       whose permanent stalls are in the Festival Hall there (so their anchors always exist).</li>
 *   <li>While its event runs, each seasonal vendor ALSO appears in the Hub: at {@code npc.<vendor>} if
 *       the hub builder made that anchor, else lined up inside the {@code election_room} area.</li>
 * </ul>
 */
final class EventNpcs {

    /** {@code near}: a hub anchor to stand beside (the carnival troupe joins the hub's carnival tents). */
    private record Vendor(String id, String anchor, BooleanSupplier when, String near, int slot) {
        Vendor(String id, String anchor, BooleanSupplier when) { this(id, anchor, when, null, 0); }
    }

    private final MayorsModule mod;
    private final List<Vendor> vendors = new ArrayList<>();
    private final Set<String> inHub = new HashSet<>();

    EventNpcs(MayorsModule mod) { this.mod = mod; }

    private static void reg(String id, String name, String title, String island, String anchor, String entity, List<String> lines, Consumer<Player> click) {
        NpcDef d = NpcDef.of(id, name, island, anchor).title(title);
        if (entity != null) d.entity(entity);
        d.lines.addAll(lines);
        Sky.npcs().register(d, click);
    }

    void register() {
        reg("mayors_election", "&fBallot Clerk Tamsin", "&e&lCLICK", "hub", "npc.election", null, List.of("&7Election Booth"),
                p -> Sky.npcs().talk(p, "mayors_election", List.of("&fWelcome to the Election Booth!",
                        "&fEvery SkyBlock year the people pick a mayor. Their perks change the whole island."), () -> ElectionMenus.open(p)));
        reg("mayors_dark_auction", "&5Ashgrave", "&5&lDARK AUCTION", "hub", "npc.dark_auction", null, List.of("&7Every hour at :55"),
                p -> mod.darkAuction().join(p, true));

        String w = WinterWorkshop.ISLAND;
        reg("mayors_jerry", "&aSaint Gerald", "&e&lCLICK", w, "npc.jerry", "VILLAGER", List.of("&7Season of Gerald"),
                p -> mod.winter().open(p));
        reg("mayors_einary", "&dIngvar", "&e&lSHOP", w, "npc.einary", null, List.of("&7North Star Emporium"),
                p -> mod.winter().openShop(p, "Ingvar's Emporium", mod.data().winter.einary));
        reg("mayors_sherry", "&bMarnie", "&e&lSHOP", w, "npc.sherry", null, List.of("&7Winter Showroom"),
                p -> mod.winter().openShop(p, "Marnie's Showroom", mod.data().winter.sherry));

        reg("mayors_fear_mongerer", "&5Dread Peddler", "&6&lSPOOKY", w, "npc.fear_mongerer", "WITCH", List.of("&7Candy Shop"),
                p -> mod.spooky().open(p));
        reg("mayors_baker", "&eBaker Haverly", "&d&lNEW YEAR", w, "npc.baker", null, List.of("&7New Year Cakes"),
                p -> mod.newYear().open(p));
        reg("mayors_zoo", "&eZebulon the Keeper", "&e&lZOO", w, "npc.zoo", null, List.of("&7Traveling Zoo"),
                p -> mod.zoo().open(p));
        reg("mayors_mythology", "&2Huntress Orla", "&2&lRITUAL", w, "npc.mythology", null, List.of("&7Griffins & Spades"),
                p -> mod.myth().open(p));
        reg("mayors_stonks", "&6Broker Penhallow", "&6&lSTONKS", w, "npc.stonks", null, List.of("&7Stonks Auction"),
                p -> mod.stonks().open(p));

        // the Carnival troupe lives on the Carnival Grounds (its own island); the booth hosts start the in-world games
        String cg = CarnivalGroundsGenerator.ID;
        reg("mayors_carnival_leader", "&dRingmaster Fenella", "&d&lCARNIVAL", cg, "npc.carnival_leader", null, List.of("&7Daily Carnival Tickets"),
                p -> Sky.npcs().talk(p, "mayors_carnival_leader", List.of("&fRoll up, roll up! Every day I hand out &aCarnival Tickets&f.",
                        "&fSpend them at the booths, then see Fitz for masks and perks!"), () -> mod.carnival().openLeader(p)));
        reg("mayors_carnival_goals", "&aScorekeeper Odette", "&a&lGOALS", cg, "npc.carnival_goals", null, List.of("&7Carnival Goals"),
                p -> mod.carnival().openGoals(p));
        reg("mayors_carnival_pirate", "&6Captain Barnacle", "&6&lFRUIT DIGGING", cg, "npc.carnival_pirate", null, List.of("&7Dig for fruit, mind the bombs"),
                p -> mod.carnival().openBooth(p, CarnivalRules.Game.FRUIT));
        reg("mayors_carnival_cowboy", "&2Sheriff Dusty", "&c&lZOMBIE SHOOTOUT", cg, "npc.carnival_cowboy", null, List.of("&7Shoot the zombies, hit the lamps"),
                p -> mod.carnival().openBooth(p, CarnivalRules.Game.SHOOT));
        reg("mayors_carnival_fisher", "&3Old Gill", "&3&lCATCH A FISH", cg, "npc.carnival_fisher", null, List.of("&7Catch the yellow ones"),
                p -> mod.carnival().openBooth(p, CarnivalRules.Game.FISH));
        reg("mayors_carnival_tokens", "&dToken Trader Fitz", "&d&lSOUVENIRS", cg, "npc.carnival_tokens", null, List.of("&7Masks and Carnival Perks"),
                p -> mod.carnival().openShop(p));

        // in the Hub only the Ringmaster and the Token Trader come out to the tents; the games are on the Grounds
        vendors.add(new Vendor("mayors_carnival_leader", "npc.carnival_leader", () -> mod.carnival().active(), "npc.carnival_barker", 0));
        vendors.add(new Vendor("mayors_carnival_tokens", "npc.carnival_tokens", () -> mod.carnival().active(), "npc.carnival_games", 0));
        vendors.add(new Vendor("mayors_fear_mongerer", "npc.fear_mongerer", () -> mod.spooky().active()));
        vendors.add(new Vendor("mayors_baker", "npc.baker", () -> mod.newYear().active()));
        vendors.add(new Vendor("mayors_zoo", "npc.zoo", () -> mod.zoo().active()));
        vendors.add(new Vendor("mayors_mythology", "npc.mythology", () -> mod.myth().active()));
        vendors.add(new Vendor("mayors_stonks", "npc.stonks", () -> mod.stonks().active()));
    }

    /** Every 5 seconds: put seasonal vendors into the Hub while their event runs. */
    void tick() {
        boolean hubReady;
        try { hubReady = Sky.worlds().isBuilt("hub") && Sky.worlds().layout("hub") != null; } catch (Throwable t) { hubReady = false; }
        Map<String, Location> spots = hubReady ? hubSpots() : Map.of();
        for (Vendor v : vendors) {
            boolean want = hubReady && v.when().getAsBoolean() && spots.containsKey(v.id());
            if (want && !inHub.contains(v.id())) {
                try { Sky.npcs().spawnAt(v.id(), "hub", spots.get(v.id())); inHub.add(v.id()); } catch (Throwable t) { Sky.log().warning("[mayors] could not place " + v.id() + " in the hub: " + t); }
            } else if (!want && inHub.contains(v.id())) {
                try { Sky.npcs().despawn(v.id(), "hub"); } catch (Throwable ignored) { }
                inHub.remove(v.id());
            }
        }
    }

    /** Where each vendor stands in the Hub: its own anchor, else a free spot in the election room. */
    Map<String, Location> hubSpots() {
        Map<String, Location> out = new LinkedHashMap<>();
        Layout layout = Sky.worlds().layout("hub");
        World world = Sky.worlds().world("hub");
        if (layout == null || world == null) return out;
        List<Vendor> fallback = new ArrayList<>();
        for (Vendor v : vendors) {
            Location a = Sky.worlds().anchor("hub", v.anchor());
            if (a == null && v.near() != null) {
                Location near = Sky.worlds().anchor("hub", v.near());
                if (near != null && near.getWorld() != null && !near.getWorld().isChunkLoaded(near.getBlockX() >> 4, near.getBlockZ() >> 4))
                    continue;   // tents not loaded yet: try again on the next tick rather than settle for the election room
                a = beside(near, v.slot());
            }
            if (a != null) out.put(v.id(), a); else fallback.add(v);
        }
        if (fallback.isEmpty()) return out;
        Box room = layout.area("election_room");
        Location clerk = Sky.worlds().anchor("hub", "npc.election");
        List<Location> free = new ArrayList<>();
        if (room != null) {
            boolean alongX = room.maxX() - room.minX() >= room.maxZ() - room.minZ();
            int z = alongX ? room.maxZ() - 1 : 0, x = alongX ? 0 : room.maxX() - 1;
            int from = alongX ? room.minX() + 1 : room.minZ() + 1, to = alongX ? room.maxX() - 1 : room.maxZ() - 1;
            for (int i = from; i <= to && free.size() < fallback.size(); i += 2) {
                Location l = alongX ? new Location(world, i + 0.5, room.minY(), z + 0.5) : new Location(world, x + 0.5, room.minY(), i + 0.5);
                if (clerk != null && clerk.getWorld() == world && clerk.distanceSquared(l) < 4) continue;
                free.add(l);
            }
        } else if (clerk != null) {
            for (int i = 0; i < fallback.size(); i++) free.add(clerk.clone().add(2 + i * 1.5, 0, 0));
        }
        for (int i = 0; i < fallback.size() && i < free.size(); i++) out.put(fallback.get(i).id(), free.get(i));
        return out;
    }

    /**
     * A free standing spot 2-3 blocks to one side of {@code at} (the {@code slot}-th free one), so a
     * troupe member never stands inside the hub NPC that owns that anchor or inside a wall.
     */
    static Location beside(Location at, int slot) {
        if (at == null || at.getWorld() == null) return null;
        int[][] offs = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {3, 1}, {-3, 1}, {3, -1}, {-3, -1}, {2, 2}, {-2, -2}};
        int found = 0;
        for (int[] o : offs) {
            Location l = at.clone().add(o[0], 0, o[1]);
            if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            var feet = l.getBlock();
            if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable() || !feet.getRelative(0, -1, 0).getType().isSolid()) continue;
            if (found++ == slot) { l.setYaw(at.getYaw()); return l; }
        }
        return null;
    }

    Set<String> inHub() { return inHub; }

    void shutdown() {
        for (String id : inHub) { try { Sky.npcs().despawn(id, "hub"); } catch (Throwable ignored) { } }
        inHub.clear();
    }
}
