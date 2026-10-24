package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Terrain;

/**
 * The Hub: a ~600x600 floating island around a walled-less market town, with the skill districts
 * radiating out from it (see {@link HubPlan} for the map). PURE: no Bukkit anywhere in this package,
 * so {@code bash tools/preview.sh com.skyclone.mod.hub.gen.HubGenerator} renders exactly what the
 * game builds.
 *
 * <p>Pass order (additive before carving): terrain → water → village → districts → launch pads →
 * roads → carving (coal mine + its Gold Mine gate, crypts, sewer) → trees and plants → anchors,
 * areas, zones, fairy souls. Version 2 (2026-10-02 review): seasonal gate, launch pads, mine gate,
 * ship captain, fishing hotspots, festival vendor spots, vault keeper, museum warp.
 */
public final class HubGenerator implements IslandGenerator {

    @Override public String islandId() { return "hub"; }

    /** Bump to rebuild the hub in game. */
    @Override public int version() { return 11; }   // 11 (wave 5): cottages fill the last village gaps so all 8 residents get a house (v10 placed 3). 10 (wave 5): townhouse residents (npc.pet_trainer, npc.hoppity, npc.resident.1-6) and npc.runic_pedestal in the forge. 9 (wave 4 endgame): /warp wizard and /warp da land stood back facing their landmark; the Dark Auction's SAFE zone is its whole plot. 8 (wave 4): the Golden Ghoul's vault (area + zone crypt_vault). 7 (wave 3): the Rift arch is a walk-in gate; NPCs no longer face walls or lanterns. 4: the village well has a ladder
    // 4-5 (2026-10-03 playtest): Bank, Auction House, Bazaar and Builder's House get a second door facing the square;
    // door.<building> anchors for the Village Directory; the Fairy Pond can be waded out of and the fairy stands on the town side
    // 5-6 (2026-10-03 playtest "loops"): SAFE Crypt landing round warp.crypt; the Tavern's west door faces the square;
    // the sewer stairwell is railed down both sides

    @Override public Box bounds() { return new Box(-330, 20, -330, 330, 255, 330); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        HubCtx g = new HubCtx(c, layout, seed);
        g.h = HubTerrain.shape(g);
        Terrain.fill(c, g.h, HubTerrain.rules());

        // additive: water, the town, the districts
        HubSouth.lake(g);
        HubVillage.build(g);
        HubNorth.portalPlaza(g);
        HubNorth.miningDistrict(g);
        HubNorth.mountain(g);
        HubEast.graveyard(g);
        HubSouth.farm(g);
        HubSouth.wilderness(g);
        HubWest.camp(g);
        HubWest.colosseum(g);
        HubWest.ruins(g);
        HubExtras.castle(g);
        HubExtras.carnival(g);
        HubTravel.pads(g);
        HubWorld.roads(g);
        HubWorld.openCuts(g);
        // carving
        HubNorth.coalMine(g);
        HubTravel.mineGate(g);
        HubEast.crypts(g);
        HubWorld.sewer(g);
        // plants, then the last anchors
        HubWorld.nature(g);
        Terrain.grassAndFlowers(c, g.h, seed, 0.16, 0.03);
        HubWorld.souls(g);
        HubWorld.moreSouls(g, HubAnchors.REFERENCE_SOULS);   // after souls(): numbers 1..42 stay put
        HubWorld.warps(g);
        HubWorld.zones(g);
        layout.data("hub.fairy-souls", String.valueOf(g.soulCount()));
        layout.data("reservations", g.res.summary());
    }
}
