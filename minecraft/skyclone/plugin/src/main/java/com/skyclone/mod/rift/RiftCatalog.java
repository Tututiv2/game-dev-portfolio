package com.skyclone.mod.rift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Rift's fixed design data, PURE Java (no Bukkit): the generator and the module both read it.
 *
 * <p>Areas and gates follow research 03 sec 19.1 (verified table): each later area needs some
 * Timecharms AND a minimum of Rift Time left when you walk in. Timecharm names, area names and
 * NPC names are our own (docs/names/rift.md).
 */
public final class RiftCatalog {

    private RiftCatalog() {}

    public static final String ISLAND = "rift";

    // =========================================================================================
    // Areas
    // =========================================================================================

    /**
     * One district of the Rift.
     *
     * @param gateCharms   timecharm ids needed to walk in (all of them)
     * @param gateSeconds  Rift Time that must be LEFT when you walk in (research 19.1), 0 = none
     * @param drain        Rift Time drained per second inside (1 normal, 0 frozen, 0.5 Colosseum)
     */
    public record Area(String id, String name, char color, List<String> gateCharms, int gateSeconds, double drain, String blurb) {
        public boolean gated() { return !gateCharms.isEmpty() || gateSeconds > 0; }
        public String requirementKey() { return gated() ? "rift:gate:" + id : null; }
    }

    public static final List<Area> AREAS = List.of(
            new Area("wyld_woods", "Wildwood Thicket", '2', List.of(), 0, 1.0,
                    "Where every visit starts: the Cradle, the Wizard Tower and the Great Tree."),
            new Area("black_lagoon", "Black Lagoon", '3', List.of(), 240, 1.0,
                    "A leech-choked swamp. Its Lair hides the Leech Sovereign."),
            new Area("west_village", "West Village", 'e', List.of("supreme"), 180, 1.0,
                    "A crooked village of odd houses and the way down to the Looking-Glass Halls."),
            new Area("dreadfarm", "Dread Acres", '6', List.of("supreme"), 180, 1.0,
                    "Wilted fields, wrong mushrooms and a farmer who lost her eggs."),
            new Area("mirrorverse", "Looking-Glass Halls", 'b', List.of(), 180, 0.0,
                    "Everything backwards. Rift Time stands still down here."),
            new Area("village_plaza", "Village Plaza", 'a', List.of("chicken", "mirror"), 480, 1.0,
                    "The Rift's town square, ruled by paperwork."),
            new Area("living_cave", "Living Cave", '9', List.of("citizen"), 300, 1.0,
                    "Rock that breathes and metal that runs away from you."),
            new Area("colosseum", "Colosseum", 'c', List.of("living"), 900, 0.5,
                    "The Bloatmass waits in the pit. Time runs at half speed here."),
            new Area("chateau", "Grimhollow Chateau", '4', List.of("globulate"), 600, 1.0,
                    "A castle of thralls and bloodfiends."),
            new Area("mountaintop", "The Mountaintop", 'd', List.of("vampiric"), 0, 1.0,
                    "The highest point of the Rift, and the last Timecharm.")
    );

    /** Small zones inside areas with their own rules (time freeze), placed by the generator. */
    public static final Map<String, Double> SUBZONE_DRAIN = Map.of(
            "rift_gallery", 0.0,
            "wizard_tower_upper", 0.0,
            "cradle", 1.0
    );

    public static Area area(String id) {
        for (Area a : AREAS) if (a.id.equals(id)) return a;
        return null;
    }

    // =========================================================================================
    // Timecharms
    // =========================================================================================

    /** One of the eight Timecharms. {@code how} is the player-facing hint. */
    public record Charm(String id, String name, String area, String how) {}

    public static final List<Charm> CHARMS = List.of(
            new Charm("supreme", "Crown Timecharm", "wyld_woods",
                    "Climb the Great Wyld Tree to its crown."),
            new Charm("chicken", "Hatchling Timecharm", "dreadfarm",
                    "Help Farmer Wilma find her eggs and fill her basket."),
            new Charm("mirror", "mrahcemiT ssalG", "mirrorverse",
                    "Beat all three rooms of the Looking-Glass Halls."),
            new Charm("citizen", "Paperwork Timecharm", "village_plaza",
                    "Get your form stamped by all four clerks, in order."),
            new Charm("living", "Quickmetal Timecharm", "living_cave",
                    "Catch the Living Heart as it flees through the cave."),
            new Charm("globulate", "Gelatinous Timecharm", "colosseum",
                    "Defeat the Bloatmass in the Colosseum pit."),
            new Charm("vampiric", "Sanguine Timecharm", "chateau",
                    "Defeat the Chateau Lord in the Grimhollow crypt."),
            new Charm("celestial", "Starbound Timecharm", "mountaintop",
                    "Bring the other seven Timecharms to the summit altar.")
    );

    public static Charm charm(String id) {
        for (Charm c : CHARMS) if (c.id.equals(id)) return c;
        return null;
    }

    // =========================================================================================
    // Collectibles
    // =========================================================================================

    /** Riddle Souls per area (research: 52 in total; split as the reference guide lists them). */
    public static final Map<String, Integer> SOULS_PER_AREA;
    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("wyld_woods", 9);
        m.put("black_lagoon", 5);
        m.put("west_village", 6);
        m.put("mirrorverse", 1);
        m.put("dreadfarm", 4);
        m.put("village_plaza", 8);
        m.put("living_cave", 2);
        m.put("colosseum", 1);
        m.put("chateau", 6);
        m.put("mountaintop", 10);
        SOULS_PER_AREA = Collections.unmodifiableMap(m);
    }

    public static int totalSouls() {
        int n = 0;
        for (int v : SOULS_PER_AREA.values()) n += v;
        return n;
    }

    /** Soul anchor names in a stable order: soul.1 .. soul.52, grouped by area in SOULS_PER_AREA order. */
    public static List<String> soulIds() {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= totalSouls(); i++) out.add("soul." + i);
        return out;
    }

    /** Area a soul number belongs to. */
    public static String soulArea(int n) {
        int i = 0;
        for (Map.Entry<String, Integer> e : SOULS_PER_AREA.entrySet()) {
            i += e.getValue();
            if (n <= i) return e.getKey();
        }
        return null;
    }

    /** Mote orbs the generator scatters (mote.1 .. mote.N). */
    public static final int MOTE_ORBS = 64;
    /** Lost eggs in Dread Acres (egg.1 .. egg.N). */
    public static final int EGGS = 5;
    /** Spots the Living Heart can jump between (heart.1 .. heart.N). */
    public static final int HEART_SPOTS = 8;
    /** Race checkpoints in the West Village (race.1 .. race.N), plus race.start / race.finish. */
    public static final int RACE_CHECKPOINTS = 6;
    /** Clerks of the paperwork questline, in order. */
    public static final int CLERKS = 4;

    // =========================================================================================
    // Anchors the module needs (the generator test and the self-test both check these)
    // =========================================================================================

    public static final List<String> NPC_ANCHORS = List.of(
            "npc.rift_wizard", "npc.mote_monger", "npc.riddler", "npc.glade_peddler", "npc.vel_ka",
            "npc.lagoon_scientist", "npc.racer", "npc.quillon", "npc.wilma", "npc.clerk_1", "npc.clerk_2",
            "npc.clerk_3", "npc.clerk_4", "npc.barnaby", "npc.tailor", "npc.plaza_monger", "npc.cave_warden",
            "npc.arena_keeper", "npc.chateau_hunter", "npc.starkeeper", "npc.tower_sage", "npc.curator", "npc.split_steal",
            // wave 5: the riddle NPCs (RiftEnigma.NPCS) and the lost cat's owner
            "npc.riddle_rourke", "npc.riddle_mossbeard", "npc.riddle_vinny", "npc.riddle_yxvael", "npc.riddle_blitzcoin",
            "npc.riddle_prestwick", "npc.riddle_digby", "npc.riddle_thistledown", "npc.riddle_okrel", "npc.riddle_skink",
            "npc.riddle_ubaldo", "npc.riddle_maribel");

    /** Wave 5: the lost cat's pieces (cat.1..N) and the Warden's eyes (eye.1..N). */
    public static final int CAT_PIECES = 9, EYES = 7;

    public static final List<String> POINT_ANCHORS = List.of(
            "spawn", "charm.supreme.start", "charm.supreme.top", "mirror.parkour.start", "mirror.parkour.end",
            "mirror.maze.start", "mirror.maze.end", "mirror.dance.center", "boss.leech", "boss.bacte", "boss.chateau",
            "race.start", "race.finish", "living.heart.home", "altar.celestial", "fairy_soul.rift");

    public static final List<String> MOB_AREAS = List.of(
            "mobs.wyld_shy", "mobs.wyld_larva", "mobs.lagoon_leech", "mobs.lagoon_deadgehog", "mobs.west_shadow",
            "mobs.west_vermin", "mobs.dread_volt", "mobs.plaza_scribe", "mobs.cave_autonull", "mobs.cave_frozille",
            "mobs.chateau_splatter", "mobs.chateau_thrall", "mobs.mountain_puff");

    public static final List<String> OTHER_AREAS = List.of(
            "portal.hub", "mirror.maze.tiles", "mirror.parkour.room", "mirror.dance.floor", "colosseum.pit");
}
