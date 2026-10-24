package com.skyclone.mod.rift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 52 Riddle Soul puzzles, PURE Java (the generator and the module both read it).
 *
 * <p>Every soul is one of the reference game's Enigma Souls (research data: NEU {@code rift_guide.json} gives the
 * 52 task ids and what each one asks, SkyHanni {@code EnigmaSouls.json} the per-area split), with our own name.
 * The id is the reference task id, so the Rift Guide and anything else keyed on it stays meaningful. A soul's
 * {@link Kind} decides how it is collected:
 * <ul>
 *   <li>walk-in kinds ({@link Kind#pickup()}): reach the spot ({@code PLACE}), reach it dry ({@code DRY}),
 *       land on it from a height ({@code FALL}), get through the glass two players open ({@code PLATES}) or
 *       break through the living wall ({@code WALL});</li>
 *   <li>reveal kinds: solving the puzzle next to the soul frees it (flower pots, balloons, the Flitwraith, runes,
 *       cake, a hint from an NPC);</li>
 *   <li>grant kinds: the puzzle hands the soul over wherever you are (shops, quizzes, minigames).</li>
 * </ul>
 * Names are ours (docs/names/rift.md).
 */
public final class RiftEnigma {

    private RiftEnigma() {}

    public enum Kind {
        PLACE, DRY, FALL, PLATES, WALL,                         // walk in
        POTS, BALLOONS, SPOOK, RUNES, CAKE, TALK,               // solve, then the soul shows itself
        BEACON, BUTTONS, SHOP, QUIZ, HOTDOG, VERMIN, LONELY, STILL, SQUAT, CIRCLE, AVERAGES, BOXES, TATS;   // granted

        /** Collected by walking into the soul (possibly with a condition). */
        public boolean pickup() { return this == PLACE || this == DRY || this == FALL || this == PLATES || this == WALL; }

        /** Solving it makes the soul collectable by walking in (granted at once when you stand close). */
        public boolean reveals() { return this == POTS || this == BALLOONS || this == SPOOK || this == RUNES || this == CAKE || this == TALK; }

        /** Solving it hands the soul over directly. */
        public boolean grants() { return !pickup() && !reveals(); }
    }

    /**
     * One Riddle Soul.
     *
     * @param id    the reference task id (NEU rift_guide.json)
     * @param name  our name
     * @param area  the Rift area (RiftCatalog.AREAS id)
     * @param kind  how it is solved
     * @param count props the generator places (pots, balloons, runes) or the target of the minigame (bites, squats...)
     * @param npc   NPC key for riddles run by a person ({@code npc.riddle_<key>} anchor), null otherwise
     * @param cost  Motes for SHOP riddles (TUNE: the reference sells these for its own currencies), else 0
     * @param task  one line telling the player what to do
     */
    public record Riddle(String id, String name, String area, Kind kind, int count, String npc, int cost, String task) {}

    /** The riddle NPCs: key -> display name. Kept here so the generator places one anchor per key. */
    public static final Map<String, String> NPCS;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("rourke", "&6Rourke the Fence");
        m.put("mossbeard", "&2Capwort Quorrin");
        m.put("vinny", "&cVinny Saucetoss");
        m.put("yxvael", "&dLonely Yxvael");
        m.put("blitzcoin", "&eBlitzcoin Barnes");
        m.put("prestwick", "&5Magician Prestwick");
        m.put("digby", "&6Digby the Mole");
        m.put("thistledown", "&dFaeosopher Thistledown");
        m.put("okrel", "&bOkrel the Squatter");
        m.put("skink", "&6Solar Skink");
        m.put("ubaldo", "&eUbaldo Twofold");
        NPCS = java.util.Collections.unmodifiableMap(m);
    }

    /** Entity type for each riddle NPC body (null = a player-shaped mannequin). */
    public static String npcEntity(String key) {
        return switch (key) {
            case "mossbeard" -> "MOOSHROOM";
            case "digby" -> "ARMADILLO";
            case "skink" -> "AXOLOTL";
            case "thistledown" -> "ALLAY";
            case "ubaldo" -> "WANDERING_TRADER";
            default -> null;
        };
    }

    public static final List<Riddle> RIDDLES = List.of(
            // ---- Wildwood Thicket (9) ----
            r("rift_enigma_original", "Beside the Riddler", "wyld_woods", Kind.PLACE, 0, null, 0,
                    "A practice soul. Walk right into it."),
            r("rift_enigma_jump_pad_1", "Up Among the Clouds", "wyld_woods", Kind.PLACE, 0, null, 0,
                    "Somewhere very high up. Climb."),
            r("rift_enigma_two_plates", "Twin Plates", "wyld_woods", Kind.PLATES, 2, null, 0,
                    "Locked in glass. Two players must stand on the two plates at the same time."),
            r("rift_enigma_phantom", "Flitwraith of the Thicket", "wyld_woods", Kind.SPOOK, 0, null, 0,
                    "A Flitwraith waits by the road. Walk up to it and follow it. Do not fall behind."),
            r("rift_enigma_flower_pot_1", "Thicket Flower Pots", "wyld_woods", Kind.POTS, 5, null, 0,
                    "A string of flower pots leads to it. Tap them in order, starting with the farthest."),
            r("rift_enigma_pressure_water", "Under Pressure", "wyld_woods", Kind.PLACE, 0, null, 0,
                    "Under deep water. Take a breath first."),
            r("rift_enigma_bark", "Toughest Bark", "wyld_woods", Kind.PLACE, 0, null, 0,
                    "Bark, bark, bark. Look under the thickest trees."),
            r("rift_enigma_wither_cage", "Hidden in Plain Sight", "wyld_woods", Kind.PLACE, 0, null, 0,
                    "Right out in the open, where nobody bothers to look."),
            r("rift_enigma_silk_1", "Among the Branches", "wyld_woods", Kind.PLACE, 0, null, 0,
                    "Hanging between branches, high in the thicket."),
            // ---- Black Lagoon (5) ----
            r("rift_tel_kar", "Old Vel'ka's Lessons", "black_lagoon", Kind.QUIZ, 3, null, 0,
                    "Old Vel'ka will test what you have learned about the Rift."),
            r("rift_lagoon_parkour", "Dry Feet", "black_lagoon", Kind.DRY, 0, null, 0,
                    "Deep in the Leeches Lair. Reach it without touching the toxic water."),
            r("rift_lagoon_shop", "Rourke's Bargain", "black_lagoon", Kind.SHOP, 0, "rourke", 600,
                    "Rourke the Fence fished a soul out of the pool. He will sell it to you."),
            r("rift_rocks_enigma", "Turning Runes", "black_lagoon", Kind.RUNES, 4, null, 0,
                    "Turn every rune stone so it faces the soul."),
            r("rift_long_shrooms", "Capwort's Secret", "black_lagoon", Kind.TALK, 0, "mossbeard", 0,
                    "Capwort Quorrin hid a soul close to him. Ask him nicely."),
            // ---- West Village (6) ----
            r("rift_hot_dog", "Hot Dog Showdown", "west_village", Kind.HOTDOG, 20, "vinny", 0,
                    "Beat Vinny Saucetoss's record: eat 20 hot dogs in 25 seconds."),
            r("rift_cake_house_1", "Cake House, Downstairs", "west_village", Kind.CAKE, 7, null, 0,
                    "The soul is inside the cake. Eat the whole thing."),
            r("rift_cake_house_2", "Cake House, Upstairs", "west_village", Kind.CAKE, 7, null, 0,
                    "Another cake, another soul. Eat every slice."),
            r("rift_kat_soul", "Pest Control", "west_village", Kind.VERMIN, 0, null, 0,
                    "Clear the Infested House of vermin for Racer Gustav."),
            r("rift_two_player_soul", "Beacon and Hopper", "west_village", Kind.BEACON, 2, null, 0,
                    "One player uses the beacon while another uses the hopper, at the same moment."),
            r("rift_silk_2", "Between the Roofs", "west_village", Kind.PLACE, 0, null, 0,
                    "Somewhere between the village roofs."),
            // ---- Looking-Glass Halls (1) ----
            r("rift_dolphin_soul", "Mirror Dive", "mirrorverse", Kind.PLACE, 0, null, 0,
                    "At the far end of the upside-down room. Do not fall."),
            // ---- Dread Acres (4) ----
            r("rift_buttons_soul", "Every Button", "dreadfarm", Kind.BUTTONS, 8, null, 0,
                    "Press every wooden button around the West Village and Dread Acres in one visit. A Berberis Blowgun hits them from afar."),
            r("rift_dreadfarm_flowerpot", "Dread Acres Flower Pots", "dreadfarm", Kind.POTS, 5, null, 0,
                    "A string of flower pots leads to it. Tap them in order, starting with the farthest."),
            r("rift_beanstalk", "Top of the Beanstalk", "dreadfarm", Kind.PLACE, 0, null, 0,
                    "Climb the giant beanstalk all the way up."),
            r("rift_dreadfarm_balloons", "Dread Acres Balloons", "dreadfarm", Kind.BALLOONS, 6, null, 0,
                    "Pop every balloon floating nearby. A Berberis Blowgun reaches the high ones."),
            // ---- Village Plaza (8) ----
            r("rift_lonely_dave", "Lonely Yxvael", "village_plaza", Kind.LONELY, 30, "yxvael", 0,
                    "Keep Lonely Yxvael company for 30 seconds. Close, but not too close."),
            r("rift_scammer", "Buy BLZ Today", "village_plaza", Kind.SHOP, 0, "blitzcoin", 1000,
                    "Blitzcoin Barnes has an incredible investment opportunity. A soul comes free with every BLZ."),
            r("rift_back2basics", "Back to Basics", "village_plaza", Kind.PLACE, 0, null, 0,
                    "Somewhere simple, the way things used to be."),
            r("rift_horsing_around", "Horsing Around", "village_plaza", Kind.PLACE, 0, null, 0,
                    "Tucked away where the paperwork ends."),
            r("rift_village_phantom", "Flitwraith of the Plaza", "village_plaza", Kind.SPOOK, 0, null, 0,
                    "A Flitwraith waits by the road. Walk up to it and follow it. Do not fall behind."),
            r("rift_village_glass", "Between Cooler Roofs", "village_plaza", Kind.PLACE, 0, null, 0,
                    "Higher than any roof in the Plaza."),
            r("rift_rabbit_under_hat", "Rabbit in the Hat", "village_plaza", Kind.STILL, 8, "prestwick", 0,
                    "Help Magician Prestwick with his trick: once he starts, do not move a muscle."),
            r("rift_plaza_balloons", "Plaza Balloons", "village_plaza", Kind.BALLOONS, 6, null, 0,
                    "Pop every balloon floating nearby. A Berberis Blowgun reaches the high ones."),
            // ---- Living Cave (2) ----
            r("rift_cave_wall", "Behind the Living Wall", "living_cave", Kind.WALL, 0, null, 0,
                    "Sealed behind living stone. Break through before it grows back."),
            r("rift_cave_flowers", "Flowery Message", "living_cave", Kind.POTS, 4, null, 0,
                    "Flower pots in the cave spell out the way. Tap them in order, starting with the farthest."),
            // ---- Colosseum (1) ----
            r("rift_mole", "Full Circle", "colosseum", Kind.CIRCLE, 0, "digby", 0,
                    "Talk to Digby the Mole, run a full circle around the Colosseum, and come back to him."),
            // ---- Grimhollow Chateau (6) ----
            r("rift_silk_castle", "Between the Towers", "chateau", Kind.PLACE, 0, null, 0,
                    "High up between the Chateau's towers."),
            r("rift_fairylosopher_soul", "The Faeosopher's Stock", "chateau", Kind.SHOP, 0, "thistledown", 2000,
                    "Faeosopher Thistledown sells a soul from the tower top."),
            r("rift_castle_standing", "Just Standing There", "chateau", Kind.PLACE, 0, null, 0,
                    "It is just standing there in the castle. What a weirdo."),
            r("rift_castle_phantom", "Flitwraith of the Chateau", "chateau", Kind.SPOOK, 0, null, 0,
                    "A Flitwraith waits by the road. Walk up to it and follow it. Do not fall behind."),
            r("rift_castle_balloons", "Chateau Balloons", "chateau", Kind.BALLOONS, 6, null, 0,
                    "Pop every balloon floating nearby. A Berberis Blowgun reaches the high ones."),
            r("rift_castle_flowerpot", "Chateau Flower Pots", "chateau", Kind.POTS, 5, null, 0,
                    "A string of flower pots leads to it. Tap them in order, starting with the farthest."),
            // ---- The Mountaintop (10) ----
            r("rift_mountain_ubik_averages", "Ubaldo's Averages", "mountaintop", Kind.AVERAGES, 9, "ubaldo", 0,
                    "Ubaldo Twofold, at the summit, wants a number. Pick the one that makes the average."),
            r("rift_mountain_ikrus", "Squat With Okrel", "mountaintop", Kind.SQUAT, 20, "okrel", 0,
                    "Okrel squats his way out of the Rift. Crouch 20 times beside him within 30 seconds."),
            r("rift_mountain_sungecko_perks", "Solar Skink's Wares", "mountaintop", Kind.SHOP, 0, "skink", 1500,
                    "The Solar Skink sells a soul among its wares."),
            r("rift_mountain_ubik_boxes", "Ubaldo's Boxes", "mountaintop", Kind.BOXES, 3, "ubaldo", 0,
                    "Ubaldo Twofold, at the summit, hides a soul in one of three boxes. Choose well."),
            r("rift_mountain_walk_of_fame_wall", "Walk of Fame", "mountaintop", Kind.PLACE, 0, null, 0,
                    "In a hollow on the mountain's flank."),
            r("rift_mountain_balloons", "Mountain Balloons", "mountaintop", Kind.BALLOONS, 6, null, 0,
                    "Pop every balloon floating nearby. A Berberis Blowgun reaches the high ones."),
            r("rift_mountain_flowerpot", "Summit Flower Pots", "mountaintop", Kind.POTS, 5, null, 0,
                    "A string of flower pots leads to it. Tap them in order, starting with the farthest."),
            r("rift_mountain_leap_of_faith", "Leap of Faith", "mountaintop", Kind.FALL, 0, null, 0,
                    "Jump. Land on it from high above."),
            r("rift_mountain_phantom", "Flitwraith of the Peak", "mountaintop", Kind.SPOOK, 0, null, 0,
                    "A Flitwraith waits by the road. Walk up to it and follow it. Do not fall behind."),
            r("rift_mountain_ubik_tats", "Ubaldo's Tit for Tat", "mountaintop", Kind.TATS, 0, null, 0,
                    "Play a round of Split or Steal with Ubaldo Twofold.")
    );

    private static Riddle r(String id, String name, String area, Kind kind, int count, String npc, int cost, String task) {
        return new Riddle(id, name, area, kind, count, npc, cost, task);
    }

    /** Riddles that are puzzles (not plain walk-ins): the ones RiftData.riddles can hold. */
    public static int puzzleCount() {
        int n = 0;
        for (Riddle r : RIDDLES) if (!r.kind().pickup()) n++;
        return n;
    }

    public static Riddle riddle(String id) {
        if (id == null) return null;
        for (Riddle r : RIDDLES) if (r.id().equals(id)) return r;
        return null;
    }

    /** Riddles of one area, in catalogue order. */
    public static List<Riddle> ofArea(String area) {
        List<Riddle> out = new ArrayList<>();
        for (Riddle r : RIDDLES) if (r.area().equals(area)) out.add(r);
        return out;
    }

    /** NPC anchor name for a riddle NPC key. */
    public static String npcAnchor(String key) { return "npc.riddle_" + key; }

    /** NPC id for a riddle NPC key. */
    public static String npcId(String key) { return "rift_riddle_" + key; }

    // =========================================================================================
    // the other hidden things: Montezuma's pieces and the Warden's eyes (research: NEU rift_guide.json)
    // =========================================================================================

    /** A hidden collectible near a district: id, our name, area, hint. */
    public record Hidden(int n, String name, String area, String hint) {}

    /** Nine pieces of a lost cat's soul (reference: Montezuma's soul pieces, rift_dead_cat_1..9). */
    public static final List<Hidden> CAT_PIECES = List.of(
            new Hidden(1, "Pharaoh's First", "wyld_woods", "Somewhere in the Wildwood Thicket."),
            new Hidden(2, "Pharaoh's Second", "wyld_woods", "Somewhere around the Rift Gallery."),
            new Hidden(3, "Pharaoh's Third", "black_lagoon", "By the water of the Black Lagoon."),
            new Hidden(4, "Pharaoh's Fourth", "west_village", "Stashed among the houses of the West Village."),
            new Hidden(5, "Pharaoh's Fifth", "dreadfarm", "Near the barn of Dread Acres."),
            new Hidden(6, "Pharaoh's Sixth", "village_plaza", "Between the pages of a book in the Plaza."),
            new Hidden(7, "Pharaoh's Seventh", "village_plaza", "In a quiet corner at the edge of the Plaza."),
            new Hidden(8, "Pharaoh's Eighth", "living_cave", "Under the frost of the Living Stillness."),
            new Hidden(9, "Pharaoh's Ninth", "chateau", "On the road to the Chateau."));

    /** The Warden of the Arch's seven eyes (reference: Porhtal's eyes, rift_eye_1..7); each found one is a fast-travel point. */
    public static final List<Hidden> EYES = List.of(
            new Hidden(1, "The Trespasser's Eye", "wyld_woods", "At the foot of the Rift's Wizard Tower."),
            new Hidden(2, "The Gill-Folk Eye", "black_lagoon", "By Old Vel'ka's hut on the lagoon."),
            new Hidden(3, "The Crone's Eye", "dreadfarm", "Out among the wilted fields."),
            new Hidden(4, "The Banker's Eye", "village_plaza", "On the Village Plaza."),
            new Hidden(5, "The Gooey Eye", "colosseum", "Outside the Colosseum."),
            new Hidden(6, "The Prince's Eye", "chateau", "Before the gates of the Chateau."),
            new Hidden(7, "The Seventh Sin's Eye", "mountaintop", "Up in the lands above."));

    /** Montezuma pet numbers (NEU FRACTURED_MONTEZUMA_SOUL lore): Rift Time 10 + 15 per piece; +2 Mana Regen per piece from EPIC. */
    public static double catRiftTime(int pieces) { return pieces <= 0 ? 0 : 10 + 15.0 * pieces; }

    /** +2 Mana Regen per piece once the cat is EPIC (6+ pieces in the NEU sample), else 0. */
    public static double catManaRegen(int pieces) { return catRarity(pieces) >= 3 ? 2.0 * pieces : 0; }

    /**
     * Rarity index 0..4 (COMMON..LEGENDARY) by pieces found. NEU shows RARE at 1/9 and EPIC at 6/9;
     * LEGENDARY at 9/9 is ours (TUNE: the reference sample stops at EPIC).
     */
    public static int catRarity(int pieces) {
        if (pieces >= 9) return 4;
        if (pieces >= 6) return 3;
        return pieces >= 1 ? 2 : 0;
    }

    /** Enigma Cloak (accessories data): +20 Rift Time per soul it holds, claimed in steps of 4, up to 42 (its lore). */
    public static final int CLOAK_STEP = 4, CLOAK_MAX = 42;
    public static final double CLOAK_SECONDS = 20;

    /** Souls the cloak may hold after claiming with {@code found} souls found. */
    public static int cloakClaimable(int found) {
        int steps = Math.max(0, found) / CLOAK_STEP * CLOAK_STEP;
        return Math.min(CLOAK_MAX, steps);
    }

    // =========================================================================================
    // pure puzzle maths (unit-tested)
    // =========================================================================================

    /** Cardinal facing (0 north, 1 east, 2 south, 3 west) from (x,z) towards (tx,tz). */
    public static int facingTowards(double x, double z, double tx, double tz) {
        double dx = tx - x, dz = tz - z;
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? 1 : 3;
        return dz >= 0 ? 2 : 0;
    }

    /** Ubaldo's Averages: the winning number is the rounded mean of the recent picks plus yours. */
    public static int averageTarget(List<Integer> recent, int pick) {
        double sum = pick;
        for (int v : recent) sum += v;
        return (int) Math.round(sum / (recent.size() + 1));
    }

    /** Unwrapped angle step (radians) from a to b, in (-pi, pi]: a Full Circle sums these. */
    public static double angleStep(double a, double b) {
        double d = b - a;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d <= -Math.PI) d += 2 * Math.PI;
        return d;
    }

    /** Monty Hall: the box Ubaldo opens (never the prize, never your pick), lowest index first for determinism. */
    public static int boxToOpen(int prize, int pick, int boxes) {
        for (int i = 0; i < boxes; i++) if (i != prize && i != pick) return i;
        return -1;
    }

    /** Area order the generator uses for soul numbers; every riddle area must be in RiftCatalog.SOULS_PER_AREA. */
    public static boolean consistent() {
        for (Map.Entry<String, Integer> e : RiftCatalog.SOULS_PER_AREA.entrySet())
            if (ofArea(e.getKey()).size() != e.getValue()) return false;
        return RIDDLES.size() == RiftCatalog.totalSouls();
    }
}
