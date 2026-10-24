package com.skyclone.mod.hub;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.hub.gen.HubAnchors;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** {@code /sb selftest hub}: the hub's data must hang together on the live server. */
final class HubSelfTests {

    private HubSelfTests() {}

    static void register() {
        Sky.commands().selfTest("hub.anchors", HubSelfTests::anchors);
        Sky.commands().selfTest("hub.mob-stats", HubSelfTests::mobStats);
        Sky.commands().selfTest("hub.mob-drops-exist", HubSelfTests::drops);
        Sky.commands().selfTest("hub.spawn-areas", HubSelfTests::spawnAreas);
        Sky.commands().selfTest("hub.npc-anchors", HubSelfTests::npcAnchors);
        Sky.commands().selfTest("hub.zones", HubSelfTests::zones);
        Sky.commands().selfTest("hub.subwarps", HubSelfTests::subwarps);
        Sky.commands().selfTest("hub.portals-wired", HubSelfTests::portals);
        Sky.commands().selfTest("hub.menus-build", HubSelfTests::menus);
        Sky.commands().selfTest("hub.travel-spots", HubSelfTests::travel);
        Sky.commands().selfTest("hub.guides", HubSelfTests::guides);
        Sky.commands().selfTest("hub.scrolls", HubSelfTests::scrolls);
        Sky.commands().selfTest("hub.graveyard-pack", HubSelfTests::graveyardPack);
        Sky.commands().selfTest("hub.target-practice", HubSelfTests::practice);
        Sky.commands().selfTest("hub.warp-landings", HubSelfTests::warpLandings);
        Sky.commands().selfTest("hub.errands", HubSelfTests::errands);
        Sky.commands().selfTest("hub.residents", HubSelfTests::residents);
        Sky.commands().selfTest("hub.ship", HubSelfTests::ship);
    }

    /** errands.json hangs together against the live registries; the menu builds; the hub: keys describe. */
    static String errands() {
        java.util.Set<String> npcs = new java.util.HashSet<>();
        for (HubNpcs.Spec s : HubNpcs.SPECS) npcs.add(s.id);
        java.util.Set<String> items = new java.util.HashSet<>();
        for (Errands.Errand e : HubErrands.ALL) {
            for (Errands.Step st : e.steps) {
                Errands.Kind k = Errands.kind(st.trigger);
                String id = k == Errands.Kind.BRING ? Errands.bringItem(st) : k == Errands.Kind.ACCESSORY ? Errands.arg(st) : null;
                if (id != null && Sky.items().exists(id)) items.add(id);
            }
            for (String id : e.reward.items.keySet()) if (Sky.items().exists(id)) items.add(id);
        }
        java.util.Set<String> skills = new java.util.HashSet<>();
        for (com.skyclone.api.skill.SkillType t : com.skyclone.api.skill.SkillType.values()) skills.add(t.name());
        String bad = Errands.validate(HubErrands.ALL, npcs, items, skills);
        if (bad != null) return bad;
        Layout l = layout();
        if (l != null) for (Errands.Errand e : HubErrands.ALL) {
            HubNpcs.Spec g = HubErrands.giver(e);
            if (g == null || l.anchor(g.anchor) == null) return e.id + ": its NPC's anchor is not in the hub layout";
            for (Errands.Step st : e.steps)
                if (Errands.kind(st.trigger) == Errands.Kind.TALK && l.anchor(st.trigger.substring(5)) == null) return e.id + ": talk anchor " + st.trigger;
        }
        HubErrands.buildMenu(null);
        if (!Sky.requirements().describe("hub:met:andrew").contains("Andrin")) return "hub:met:andrew describes as " + Sky.requirements().describe("hub:met:andrew");
        if (!Sky.requirements().describe("hub:errand:starter_blade").contains("Blade")) return "hub:errand describe";
        return null;
    }

    /** Every townhouse resident anchor is built and has an NPC standing on it (ours, skills' Pet Trainer, chocolate's keeper). */
    static String residents() {
        Layout l = layout();
        if (l == null) return "no hub layout";
        List<String> bad = new ArrayList<>();
        for (String a : HubAnchors.RESIDENTS) {
            if (l.anchor(a) == null) { bad.add("anchor " + a + " missing (" + l.data().get("hub.residents") + ")"); continue; }
            boolean someone = false;
            for (com.skyclone.api.npc.NpcDef d : Sky.npcs().all()) if (HubModule.ISLAND.equals(d.island) && a.equals(d.anchor)) someone = true;
            // pet_trainer / hoppity belong to skills / chocolate; they may also place the NPC with spawnAt (no NpcDef anchor)
            if (!someone && a.startsWith("npc.resident.")) bad.add("nobody lives at " + a);
        }
        for (String a : HubAnchors.WAVE5) if (l.anchor(a) == null) bad.add("anchor " + a + " missing");
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    /** The ship's voyages: a registered warp's island exists, the Lotus Atoll is charted once galatea registers it. */
    static String ship() {
        HubMenus.buildShip(null);
        List<String> bad = new ArrayList<>();
        for (HubMenus.Voyage v : HubMenus.VOYAGES) {
            WorldService.WarpInfo w = HubMenus.warp(v.warp());
            if (w == null) {
                if (Sky.worlds().island(v.warp()) != null) bad.add(v.warp() + " island exists but its warp is not registered");
                continue;
            }
            if (Sky.worlds().island(w.island()) == null) bad.add(v.warp() + " warps to unknown island " + w.island());
        }
        if (HubMenus.warp("backwater_bayou") == null) bad.add("backwater_bayou not charted (fishing)");
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    static Layout layout() { return Sky.worlds().layout(HubModule.ISLAND); }

    /**
     * No district warp lands inside another district's spawn area outside a SAFE zone (wave-4 endgame:
     * /warp da landed in the Wilderness one block from a Spooky Festival Wraith). The festival's mobs use the
     * Graveyard and Wilderness areas, which the hub's own rules use too, so the hub's rule areas cover them.
     */
    static String warpLandings() {
        Layout l = layout();
        if (l == null) return "no hub layout (island not generated yet?)";
        Set<String> areas = new java.util.TreeSet<>();
        for (SpawnRule r : HubModule.RULES) if (r.area != null && !r.area.isBlank()) areas.add(r.area);
        if (areas.isEmpty()) return "no hub spawn rules loaded";
        List<String> bad = new ArrayList<>(com.skyclone.mod.hub.gen.Landing.unsafeLandings(l, areas));
        Layout.Anchor w = l.anchor("warp.dark_auction");
        Layout.Zone da = w == null ? null : com.skyclone.mod.hub.gen.Landing.smallestZone(l, w.x(), w.y() + 0.1, w.z());
        if (w == null) bad.add("anchor warp.dark_auction missing");
        else if (da == null || !"dark_auction".equals(da.id())) bad.add("warp.dark_auction is not in the Dark Auction zone (" + (da == null ? "none" : da.id()) + ")");
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    /** Target Practice: its anchors and areas exist, the targets in the built world are target blocks, the medal rules hold. */
    static String practice() {
        List<String> bad = new ArrayList<>();
        for (int i = 1; i < TargetPractice.TIERS.size(); i++)
            if (TargetPractice.TIERS.get(i).score() <= TargetPractice.TIERS.get(i - 1).score()) bad.add("medal scores not ascending");
        if (TargetPractice.tierFor(0) != 0 || TargetPractice.tierFor(TargetPractice.TIERS.get(0).score()) != 1) bad.add("tierFor");
        for (int prev = 0; prev < 4; prev++)
            for (double r : new double[]{0, 0.3, 0.6, 0.999}) {
                int n = TargetPractice.nextTarget(prev, 4, r);
                if (n == prev || n < 0 || n > 3) bad.add("nextTarget(" + prev + "," + r + ")=" + n);
            }
        Layout l = layout();
        if (l == null) return bad.isEmpty() ? "no hub layout (island not generated yet?)" : String.join(", ", bad);
        if (l.anchor("npc.range_master") == null) bad.add("anchor npc.range_master");
        for (String a : HubAnchors.ARCHERY_AREAS) if (l.area(a) == null) bad.add("area " + a);
        for (String a : HubAnchors.ARCHERY_TARGETS) if (l.anchor(a) == null) bad.add("anchor " + a);
        if (Sky.worlds().isBuilt(HubModule.ISLAND))
            for (org.bukkit.Location t : TargetPractice.targets())
                if (t.getWorld().isChunkLoaded(t.getBlockX() >> 4, t.getBlockZ() >> 4) && t.getBlock().getType() != org.bukkit.Material.TARGET)
                    bad.add("target block at " + t.getBlockX() + "," + t.getBlockY() + "," + t.getBlockZ() + " is " + t.getBlock().getType());
        if (!Sky.requirements().describe("hub:practice:2").contains("Silver")) bad.add("hub:practice describe");
        TargetPractice.Step w = TargetPractice.step(3, 1, 2, 4, 0.5, Math.max(0, HubModule.practicePenalty.get()));
        if (w.right() || w.active() == 2 || (HubModule.practicePenalty.get() > 0 && w.score() >= 3)) bad.add("a dark target must cost a point and move the light: " + w);
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    static List<String> missingAnchors(Layout l) {
        List<String> miss = new ArrayList<>();
        for (String a : HubAdmin.anchorsExpected()) if (l.anchor(a) == null) miss.add(a);
        for (String a : HubAnchors.CONTRACT_AREAS) if (l.area(a) == null) miss.add("area " + a);
        for (String p : HubAnchors.PORTALS) if (l.area("portal." + p) == null) miss.add("portal." + p);
        return miss;
    }

    static List<String> unwiredPortals(Layout l) {
        List<String> out = new ArrayList<>();
        Set<String> warps = Set.copyOf(Sky.worlds().warps());
        for (String n : l.areas().keySet())
            if (n.startsWith("portal.") && !warps.contains(n.substring(7))
                    // the dungeon gate is built ahead of the wave-2 dungeons module; not an error until it exists
                    && !(n.equals("portal.dungeon_hub") && !Sky.has(com.skyclone.api.feature.Features.Dungeons.class)))
                out.add(n.substring(7));
        return out;
    }

    static String anchors() {
        Layout l = layout();
        if (l == null) return "no hub layout (island not generated yet?)";
        List<String> miss = missingAnchors(l);
        int souls = 0;
        for (String n : l.anchors().keySet()) if (n.startsWith("fairy_soul")) souls++;
        if (souls < HubAnchors.MIN_SOULS) miss.add("only " + souls + " fairy souls (want " + HubAnchors.MIN_SOULS + ")");
        return miss.isEmpty() ? null : "missing: " + String.join(", ", miss);
    }

    /** The research numbers (research 03 section 4.3) for the hub's signature mobs. */
    static String mobStats() {
        Object[][] want = {
                {"hub_graveyard_zombie", 1, 100.0, 20.0, 6.0}, {"hub_zombie_villager", 1, 120.0, 24.0, 7.0},
                {"hub_crypt_ghoul", 30, 2000.0, 350.0, 32.0}, {"hub_golden_ghoul", 60, 45000.0, 800.0, 50.0},
                {"hub_wolf", 15, 250.0, 90.0, 15.0}, {"hub_old_wolf", 50, 15000.0, 800.0, 40.0},
        };
        List<String> bad = new ArrayList<>();
        for (Object[] w : want) {
            MobDef d = Sky.mobs().def((String) w[0]);
            if (d == null) { bad.add(w[0] + " missing"); continue; }
            if (d.level != (int) w[1] || d.health != (double) w[2] || d.damage != (double) w[3] || d.combatXp != (double) w[4])
                bad.add(w[0] + " is Lv" + d.level + " " + d.health + "hp " + d.damage + "dmg " + d.combatXp + "xp");
        }
        MobDef g = Sky.mobs().def("hub_crypt_ghoul");
        if (g != null && !"zombie".equals(g.slayer)) bad.add("crypt ghoul does not count for the zombie slayer");
        return bad.isEmpty() ? null : String.join("; ", bad);
    }

    static String drops() {
        List<String> bad = new ArrayList<>();
        for (MobDef m : HubModule.hubMobs())
            for (DropDef d : m.drops) if (!Sky.items().exists(d.item)) bad.add(m.id + ":" + d.item);
        if (HubModule.hubMobs().size() < 12) bad.add("only " + HubModule.hubMobs().size() + " hub mobs loaded");
        return bad.isEmpty() ? null : "unknown drop items " + bad;
    }

    static String spawnAreas() {
        Layout l = layout();
        if (l == null) return "no hub layout";
        List<String> bad = new ArrayList<>();
        for (SpawnRule r : HubModule.RULES) {
            if (Sky.mobs().def(r.mob) == null) bad.add(r.id + " mob " + r.mob);
            if (r.area != null && l.area(r.area) == null) bad.add(r.id + " area " + r.area);
        }
        if (HubModule.RULES.isEmpty()) bad.add("no spawn rules loaded");
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    static String npcAnchors() {
        Layout l = layout();
        if (l == null) return "no hub layout";
        List<String> bad = new ArrayList<>();
        for (HubNpcs.Spec s : HubNpcs.SPECS) {
            if (l.anchor(s.anchor) == null) bad.add(s.id + "@" + s.anchor);
        }
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    static String zones() {
        Layout l = layout();
        if (l == null) return "no hub layout";
        List<String> bad = new ArrayList<>();
        for (String safe : List.of("village", "plaza", "portal_plaza", "bank")) {
            Layout.Zone z = zone(l, safe);
            if (z == null) bad.add(safe + " zone missing");
            else if (!z.flags().contains(Layout.Zone.SAFE)) bad.add(safe + " is not SAFE");
        }
        for (String danger : List.of("graveyard", "crypt", "ruins", "wilderness")) {
            Layout.Zone z = zone(l, danger);
            if (z == null) bad.add(danger + " zone missing");
            else if (z.flags().contains(Layout.Zone.SAFE)) bad.add(danger + " is SAFE (mobs could not spawn)");
        }
        for (HubDistrict d : HubDistrict.ALL) if (zone(l, d.id()) == null) bad.add("district zone " + d.id());
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    static Layout.Zone zone(Layout l, String id) {
        for (Layout.Zone z : l.zones()) if (z.id().equals(id)) return z;
        return null;
    }

    static String subwarps() {
        List<String> bad = new ArrayList<>();
        for (HubDistrict d : HubDistrict.ALL) {
            WorldService.WarpInfo w = HubMenus.warp(d.warpId());
            if (w == null) bad.add(d.warpId() + " not registered");
            else if (!("hubzone:" + d.id()).equals(w.requirement())) bad.add(d.warpId() + " requirement " + w.requirement());
        }
        if (Sky.requirements().describe("hubzone:crypt").isBlank()) bad.add("hubzone resolver missing");
        if (!HubMenus.compass(0, -10).equals("north") || !HubMenus.compass(10, 10).equals("south-east")) bad.add("compass maths wrong");
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    static String portals() {
        Layout l = layout();
        if (l == null) return "no hub layout";
        List<String> un = unwiredPortals(l);
        return un.isEmpty() ? null : "no warp registered yet for: " + String.join(", ", un) + " (their island modules register them)";
    }

    static String menus() {
        HubMenus.buildMap(null);
        HubMenus.buildPortals(null);
        HubMenus.buildGuides(null);
        HubMenus.buildShip(null);
        HubMenus.buildVillage(null);
        if (VillagePlace.ALL.size() != HubAnchors.VILLAGE.length) return "Village Directory has " + VillagePlace.ALL.size() + " places, layout list " + HubAnchors.VILLAGE.length;
        for (VillagePlace vp : VillagePlace.ALL) if (vp.entrance().isEmpty()) return "Village place " + vp.id() + " has no 'way in' text";
        for (String id : HubAnchors.PORTALS) PortalLabels.labelText(id);
        for (String a : HubAnchors.TRAVEL) PortalLabels.labelText(a.substring(a.indexOf('.') + 1), a.substring(0, a.indexOf('.')));
        return null;
    }

    /** Every pad/gate is in the layout with a walk-check anchor, its warp exists, and the Exit list matches. */
    static String travel() {
        Layout l = layout();
        if (l == null) return "no hub layout";
        List<String> bad = new ArrayList<>();
        for (String a : HubAnchors.TRAVEL) {
            if (l.area(a) == null) bad.add("area " + a);
            if (l.anchor(a) == null) bad.add("anchor " + a);
            String warp = a.substring(a.indexOf('.') + 1);
            // the Rift arch waits for the rift module like the dungeon gate waits for dungeons
            if (HubMenus.warp(warp) == null && !(warp.equals("rift") && Sky.worlds().island("rift") == null))
                bad.add(a + " -> no warp '" + warp + "' registered");
            boolean listed = false;
            for (HubMenus.Exit e : HubMenus.EXITS) if (e.area().equals(a)) { listed = true; if (HubDistrict.byId(e.district()) == null) bad.add(a + " district " + e.district()); }
            if (!listed) bad.add(a + " missing from the Portal Directory");
        }
        if (HubModule.travel.spots().size() != HubAnchors.TRAVEL.size()) bad.add("listener sees " + HubModule.travel.spots().size() + " spots");
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    /** Guide data parses, every talk anchor / zone it names exists in the hub, kill targets are hub mobs. */
    static String guides() {
        String v = HubGuides.validate();
        if (v != null) return v;
        Layout l = layout();
        List<String> bad = new ArrayList<>();
        for (HubGuides.Guide g : HubGuides.ALL) {
            if (HubDistrict.byId(g.id) == null) bad.add(g.id + " is not a district");
            for (HubGuides.Step st : g.steps) {
                String[] t = st.trigger.split(":");
                if (t[0].equals("talk") && l != null && l.anchor(t[1]) == null) bad.add(g.id + ": no anchor " + t[1]);
                if (t[0].equals("zone") && l != null && zone(l, t[1]) == null) bad.add(g.id + ": no zone " + t[1]);
                if (t[0].equals("kill") && Sky.mobs().def(t[1]) == null) bad.add(g.id + ": no mob " + t[1]);
                if (t[0].equals("pad") && l != null && l.area("pad." + t[1]) == null) bad.add(g.id + ": no pad " + t[1]);
            }
        }
        // the matcher itself, against known event keys
        if (!HubGuides.matches("gather:MINING:COAL:10", "gather:MINING:COAL")) bad.add("gather matcher");
        if (!HubGuides.matches("gather:FORAGING:LOG:20", "gather:FORAGING:BIRCH_LOG")) bad.add("gather contains-matcher");
        if (HubGuides.matches("gather:FARMING:WHEAT:20", "gather:MINING:WHEAT")) bad.add("gather kind check");
        if (!HubGuides.matches("kill:hub_wolf:10", "kill:hub_wolf") || HubGuides.matches("kill:hub_old_wolf:1", "kill:hub_wolf")) bad.add("kill matcher");
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    /** Every hub travel scroll is registered, points at a district, and every district with a short warp has a scroll. */
    static String scrolls() {
        List<String> bad = new ArrayList<>();
        for (String id : List.of("HUB_CASTLE_TRAVEL_SCROLL", "HUB_CRYPTS_TRAVEL_SCROLL", "HUB_DA_TRAVEL_SCROLL", "HUB_WIZARD_TOWER_TRAVEL_SCROLL", "MUSEUM_TRAVEL_SCROLL")) {
            ItemDef d = Sky.items().def(id);
            if (d == null) { bad.add(id + " not registered"); continue; }
            if (d.extra == null || !d.extra.has("hubDistrict")) { bad.add(id + " has no district (another module's def won?)"); continue; }
            HubDistrict dist = HubDistrict.byId(d.extra.get("hubDistrict").getAsString());
            if (dist == null) bad.add(id + " -> unknown district");
            else if (HubMenus.warp(dist.warpId()) == null) bad.add(id + " -> warp " + dist.warpId() + " missing");
        }
        return bad.isEmpty() ? null : String.join(", ", bad);
    }

    /**
     * The Graveyard must stay survivable for a day-one player: its mobs carry the pack behaviour (short
     * aggro, capped chasers), and at night the mobs expected inside one aggro circle stay at or under the cap.
     * The Crypt's ghouls carry the Crypt pack, and {@code /warp crypt} lands in a SAFE zone (playtest "loops":
     * it used to land in the ghoul pack and kill a 600 HP / 700 Defense player within a second).
     */
    static String graveyardPack() {
        List<String> bad = new ArrayList<>();
        int night = 0;
        GraveyardPack gy = GraveyardPack.INSTANCE, cr = GraveyardPack.CRYPT;
        for (SpawnRule r : HubModule.RULES) {
            GraveyardPack want = "graveyard".equals(r.area) ? gy : "crypt".equals(r.area) || GoldenVault.AREA.equals(r.area) ? cr : null;
            if (r.mob.equals("hub_golden_ghoul")) {
                // wave-4 playtest: the Golden Ghoul walked the main hall where everyone grinds; it keeps to its vault now
                if (!GoldenVault.AREA.equals(r.area)) bad.add("hub_golden_ghoul spawns in area " + r.area + ", not " + GoldenVault.AREA);
                MobDef gd = Sky.mobs().def(r.mob);
                if (gd == null || gd.behaviours == null || !gd.behaviours.contains(GoldenVault.ID)) bad.add("hub_golden_ghoul lacks behaviour " + GoldenVault.ID);
            }
            if (want == null) continue;
            MobDef d = Sky.mobs().def(r.mob);
            if (d == null || d.behaviours == null || !d.behaviours.contains(want.id))
                bad.add(r.mob + " lacks behaviour " + want.id);
            if (want == gy && (r.condition == null || r.condition.equals("night"))) night += r.max;
        }
        double aggro = gy.aggroRange.get();
        int cap = gy.maxChasers.get();
        if (aggro > 24) bad.add("hub.graveyard-aggro-range " + aggro + " is above 24 (vanilla's 35 is what pulled the field together)");
        if (cr.aggroRange.get() > 24) bad.add("hub.crypt-aggro-range " + cr.aggroRange.get() + " is above 24 (vanilla's 35 pulled the Crypt onto the landing)");
        Layout l = layout();
        var box = l == null ? null : l.area("graveyard");
        if (box == null) bad.add("no graveyard area in the layout");
        else if (cap > 0) {
            double area = (double) (box.maxX() - box.minX() + 1) * (box.maxZ() - box.minZ() + 1);
            double near = GraveyardPack.expectedWithin(night, area, aggro);
            if (near > cap) bad.add(String.format("night: %d mobs over %.0f blocks puts %.1f inside one %.0f-block aggro circle (cap %d)", night, area, near, aggro, cap));
        }
        // the newcomer ramp: never above the cap, never below one, full cap from the ramp level, monotone in Combat
        int nc = gy.newcomerChasers.get(), ramp = gy.newcomerCombat.get();
        int prev = 0;
        for (int lvl = 0; lvl <= Math.max(0, ramp) + 1; lvl++) {
            int c = GraveyardPack.capFor(cap, nc, ramp, lvl);
            if (cap > 0 && (c > cap || c < 1)) bad.add("capFor(Combat " + lvl + ")=" + c + " outside 1.." + cap);
            if (c < prev) bad.add("capFor drops at Combat " + lvl);
            prev = c;
        }
        if (ramp > 0 && GraveyardPack.capFor(cap, nc, ramp, ramp) != cap) bad.add("Combat " + ramp + " does not get the full cap");
        if (GraveyardPack.tipLines(1000, 0, false).size() < 3) bad.add("Graveyard tip misses a line for a broke, unarmoured, swordless newcomer");
        for (GraveyardPack p : GraveyardPack.ALL)
            if (!p.gateResolved()) bad.add(p.gateAnchor + " anchor missing (the " + p.label + "'s quiet arrival needs it)");
        String crypt = GraveyardPack.arrivalProblem(l, "warp.crypt", "crypt");
        if (crypt != null) bad.add("Crypt arrival: " + crypt + " (old hub build? /sb island build hub force)");
        String vault = GoldenVault.vaultProblem(l);
        if (vault != null) bad.add("Golden Ghoul vault: " + vault + " (old hub build? /sb island build hub force)");
        return bad.isEmpty() ? null : String.join("; ", bad);
    }
}
