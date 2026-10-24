package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.gen.Layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code /sb selftest mayors}: does the data hang together on the live server? */
final class MayorsSelfTests {

    private MayorsSelfTests() {}

    static final List<String> OWN_ITEMS = List.of("GREEN_CANDY", "PURPLE_CANDY", "WHITE_GIFT", "GREEN_GIFT", "RED_GIFT", "NEW_YEAR_CAKE",
            "SPOOKY_PIE", "HORSEMAN_CANDLE", "GRIFFIN_FEATHER", "ANCIENT_CLAW", "ENCHANTED_ANCIENT_CLAW", "DAEDALUS_STICK", "ANCESTRAL_SPADE",
            "ARCHAIC_SPADE", "DEIFIC_SPADE", "STOCK_OF_STONKS", "JERRY_BOX_GREEN", "JERRY_BOX_BLUE", "JERRY_BOX_PURPLE", "JERRY_BOX_GOLDEN", "SIEGE_SNOWBALL", "NORTH_STAR", "PARTY_CHEST");

    static void register(MayorsModule mod) {
        var c = Sky.commands();
        c.selfTest("mayors.roster", () -> MayorsChecks.roster(mod.roster()));
        c.selfTest("mayors.election-math", () -> MayorsChecks.math(mod.roster()));
        c.selfTest("mayors.reference-perks", () -> MayorsChecks.referencePerks(mod.roster()));
        c.selfTest("mayors.extra-events", MayorsChecks::extraEvents);
        c.selfTest("mayors.brackets", () -> MayorsChecks.brackets(mod.data()));
        c.selfTest("mayors.prices", () -> MayorsChecks.prices(mod.data()));
        c.selfTest("mayors.myth-mechanics", MayorsChecks::mythMechanics);
        c.selfTest("mayors.fame-votes", MayorsChecks::fameVotes);
        c.selfTest("mayors.carnival", () -> MayorsChecks.carnival(mod.data()));
        c.selfTest("mayors.presence", MayorsChecks::chatAndSidebar);
        c.selfTest("mayors.myth-rules", () -> MayorsChecks.mythRules(mod.data()));
        c.selfTest("mayors.siege-rules", () -> MayorsChecks.siegeRules(mod.data()));
        c.selfTest("mayors.masks", MayorsChecks::masksAndSpooky);
        c.selfTest("mayors.carnival-grounds", () -> {
            Layout l = Sky.worlds().layout(CarnivalGroundsGenerator.ID);
            if (l == null) return "the carnival island has no layout (not registered?)";
            List<String> missing = new ArrayList<>();
            for (String a : new String[]{"spawn", "carnival.shoot.stand", "carnival.fish.stand", "lamp.1", "npc.carnival_leader", "npc.carnival_goals",
                    "npc.carnival_tokens", "npc.carnival_pirate", "npc.carnival_cowboy", "npc.carnival_fisher"})
                if (l.anchor(a) == null) missing.add(a);
            for (String a : new String[]{"carnival.dig", "carnival.shoot.lane1", "carnival.shoot.lane2", "carnival.fish.pond", "portal.hub"})
                if (l.area(a) == null) missing.add("area " + a);
            for (String t : new String[]{"CARNIVAL_SHOVEL", "CARNIVAL_DART_TUBE", "CARNIVAL_FISHING_ROD"}) if (!Sky.items().exists(t)) missing.add(t);
            return missing.isEmpty() ? null : "missing: " + missing;
        });
        c.selfTest("mayors.alerts", () -> {
            String err = MayorsChecks.alerts();
            if (err != null) return err;
            // live half: exactly one announcer, and every 'important' id is a real calendar event
            if (mod.dials().alertsTakeover.get() && !mod.alerts().running()) return "takeover is on but menus.event-reminders is still on (two announcers)";
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (var ev : Sky.calendar().events()) if (ev != null && ev.id != null) ids.add(ev.id);
            List<String> unknown = new ArrayList<>();
            for (String id : AlertPolicy.ids(mod.dials().alertsImportant.get())) if (!ids.contains(id)) unknown.add(id);
            return unknown.isEmpty() ? null : "mayors.alerts.important names ids no module registered: " + unknown;
        });
        c.selfTest("mayors.service", () -> {
            if (!Sky.has(Features.Mayors.class)) return "Features.Mayors is not provided";
            Features.Mayors m = Sky.service(Features.Mayors.class);
            if (m.mayorName() == null || m.mayorName().isBlank()) return "no mayor is installed (the election tick has not run?)";
            if (mod.election().activePerks().isEmpty()) return "the mayor has no active perks";
            for (String id : mod.election().activePerks()) if (m.perkValue(id) == 0 && mod.roster().perk(id).value != 0) return "perkValue(" + id + ") is 0 while active";
            if (m.perkActive("nobody.nothing")) return "an unknown perk reads as active";
            return null;
        });
        c.selfTest("mayors.items-exist", () -> {
            List<String> missing = new ArrayList<>();
            for (String id : OWN_ITEMS) if (!Sky.items().exists(id)) missing.add(id);
            for (MobDef d : ourMobs())
                for (DropDef dr : d.drops) if (Goods.resolve(dr.item) == null) missing.add(d.id + ":" + dr.item);
            // drops owned by other modules are tolerated (skipped at runtime) but our own must exist
            missing.removeIf(s -> s.contains(":") && !OWN_ITEMS.contains(s.substring(s.indexOf(':') + 1)));
            return missing.isEmpty() ? null : "missing: " + missing;
        });
        c.selfTest("mayors.mobs-exist", () -> {
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, Map<String, Integer>> pool : mod.data().myth.pools.entrySet())
                for (String kind : pool.getValue().keySet()) {
                    String id = "myth_" + kind + "_" + pool.getKey();
                    // every creature a Griffin of this rarity can dig up exists in that rarity's variant
                    if (Sky.mobs().def(id) == null) missing.add(id);
                }
            for (Map<String, Integer> wave : mod.data().winter.siege.waves)
                for (String id : wave.keySet()) if (Sky.mobs().def(id) == null) missing.add(id);
            for (String col : new String[]{"green", "blue", "purple", "golden"}) if (Sky.mobs().def("hidden_jerry_" + col) == null) missing.add("hidden_jerry_" + col);
            for (String id : new String[]{"spooky_trick_or_treater", "spooky_wraith", "spooky_headless_rider"}) if (Sky.mobs().def(id) == null) missing.add(id);
            return missing.isEmpty() ? null : "missing mobs: " + missing;
        });
        c.selfTest("mayors.shops-stocked", () -> {
            int spooky = count(mod.data().spooky.shop), zoo = count(mod.data().zoo.offers), einary = count(mod.data().winter.einary);
            int lots = 0;
            for (EventData.Lot l : mod.data().darkAuction.lots) if (Goods.resolve(l.item) != null) lots++;
            int books = 0;
            for (EventData.Lot l : mod.data().darkAuction.bookRound) if (Goods.resolve(l.item) != null) books++;
            if (spooky == 0) return "the Dread Peddler has nothing to sell (accessories/armor items missing?)";
            if (lots < 4 || books == 0) return "Dark Auction can only fill " + lots + " general lots and " + books + " book lots";
            if (zoo == 0) return "the zoo has no pet that exists (pets module missing?)";
            return einary == 0 ? "Ingvar's Emporium has nothing that exists" : null;
        });
        c.selfTest("mayors.workshop-anchors", () -> {
            Layout l = Sky.worlds().layout(WinterWorkshop.ISLAND);
            if (l == null) return "jerrys_workshop has no layout yet (not built?)";
            List<String> missing = new ArrayList<>();
            for (String a : new String[]{"spawn", "npc.jerry", "npc.einary", "npc.sherry", "siege.target", "npc.fear_mongerer", "npc.baker", "npc.zoo", "npc.mythology", "npc.stonks",
                    "npc.carnival_leader", "npc.carnival_pirate", "npc.carnival_tokens", "npc.carnival_cowboy", "npc.carnival_fisher", "npc.ice_carver", "npc.frozen_trader"})
                if (l.anchor(a) == null) missing.add(a);
            if (l.area("portal.hub") == null) missing.add("area portal.hub");
            long spawns = l.anchors().keySet().stream().filter(k -> k.startsWith("siege.spawn.")).count();
            if (spawns < 3) missing.add("siege.spawn.* (" + spawns + ")");
            return missing.isEmpty() ? null : "missing: " + missing;
        });
        c.selfTest("mayors.hub-anchors", () -> {
            Layout l = Sky.worlds().layout("hub");
            if (l == null) return "hub has no layout yet";
            List<String> missing = new ArrayList<>();
            if (l.anchor("npc.election") == null) missing.add("npc.election");
            if (l.anchor("npc.dark_auction") == null) missing.add("npc.dark_auction");
            if (l.area("election_room") == null) missing.add("area election_room");
            if (l.area("graveyard") == null) missing.add("area graveyard (spooky mobs, candle)");
            return missing.isEmpty() ? null : "hub is missing: " + missing;
        });
        c.selfTest("mayors.calendar", () -> {
            for (String id : new String[]{Cal.SPOOKY, Cal.NEW_YEAR, Cal.ZOO_SUMMER, Cal.SEASON_OF_JERRY, Cal.DARK_AUCTION, Cal.ELECTION, Cal.MYTH}) {
                boolean found = Sky.calendar().events().stream().anyMatch(e -> e.id.equals(id));
                if (!found) return "calendar event " + id + " is not registered";
            }
            if (Cal.active(Cal.MYTH) != mod.myth().active()) return "mythological_ritual calendar state does not follow the perk";
            for (String id : new String[]{Cal.FIESTA, Cal.FISHING_EXTRA, Cal.SPOOKY_EXTRA})
                if (Sky.calendar().events().stream().noneMatch(e -> e.id.equals(id))) return "calendar event " + id + " is not registered";
            if (Cal.active(Cal.FIESTA) != mod.extraEvents().fiestaWanted(Sky.calendar().now()))
                return "mining_fiesta calendar state does not follow Cole's perk / Foxy's Extra Event";
            return null;
        });
    }

    private static int count(List<EventData.Offer> offers) {
        int n = 0;
        for (EventData.Offer o : offers) if (Goods.available(o)) n++;
        return n;
    }

    private static List<MobDef> ourMobs() {
        List<MobDef> out = new ArrayList<>();
        for (MobDef d : Sky.mobs().all())
            if (d.id.startsWith("spooky_") || d.id.startsWith("myth_") || d.id.startsWith("siege_") || d.id.startsWith("hidden_jerry_")) out.add(d);
        return out;
    }
}
