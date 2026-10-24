package com.thestill.mine;

import com.thestill.region.Cuboid;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Where the Depths are and what is in them.
 *
 * <p>Coordinates are a pure function of the depth <em>number</em>, never of
 * iteration order. Shadow Prison derived mine positions from their position in a
 * map's key set, so deleting one mine silently relocated every mine after it —
 * the physical world moved because a key was removed. Here D7 is at the same
 * place whether or not D6 exists, and the computed coordinates are written into
 * the region row at setup time, so even this class can change later without
 * moving anything already built.
 *
 * <p>The ore weights and filler price ramp below are <b>ported from Shadow
 * Prison's live tuning, not re-derived</b>. They have been played against and
 * retuned; the build plan is explicit that they should be inherited.
 */
public final class DepthPlan {

    public static final int DEPTH_COUNT = 26;

    // ---- the vertical world ---------------------------------------------
    //
    //   y 1900   RIGS      spawn, lamps, your platform
    //   y 1800   THE FOG   hard visual floor; everything below is "down"
    //   y 1650   D1
    //   y 1510   D2
    //     ...    (140 blocks of real rock between each)
    //   y  390   D10  THE CROSSING  (plains, and where PvP switches on)
    //     ...
    //   y -1850  D26
    //   y -2032  bedrock
    //
    // The descent is literal: D26 is three and a half thousand blocks below your
    // rig. "Shafts hung beneath the rigs" (SERVER_TEMPLATES.html) is the whole
    // shape of the world, not a flavour line — an earlier draft laid the depths
    // out as a flat row at ground level and it was the wrong game.

    /**
     * Rig deck. Spawn sits here, above the fog.
     *
     * <p>1750, not 1900. The drop onto the Landing was 270 blocks, which is long
     * enough that the rig is not a place you can see the world from — it is a
     * platform with nothing under it. At 120 the Landing is somewhere down there.
     */
    public static final int RIG_Y = 1750;

    /** The fog floor. Below this is the drowned continent; above it is home. */
    public static final int FOG_Y = 1700;

    /** Ceiling of D1, a long way under the fog so the first descent is felt. */
    public static final int FIRST_DEPTH_TOP_Y = 1650;

    /** Vertical gap between one depth's ceiling and the next. */
    public static final int DEPTH_SPACING_Y = 140;

    public static final int MINE_SIZE = 48;
    public static final int MINE_HEIGHT = 40;

    /**
     * Plains are open ground, so they are far wider and mostly air.
     *
     * <p>512 — four times the ground of the 256 it replaced, which was itself four
     * times the 96 before that. At 96 a plains read as "the quarry and a bit of
     * ground round it"; at 256 you could still cross it without meaning to.
     *
     * <p>Thirty-two chunks across. What makes that feel large rather than merely
     * take a while is the <b>fog</b>: the biome goes fully opaque at 96 blocks, so
     * you are never shown more than a fifth of it at once and the edge is never
     * visible from the middle. The cost is paid for by the ground being a crust
     * away from the quarry rather than solid rock all the way down — see
     * {@code StillWorld.PLAINS_CRUST}.
     */
    public static final int PLAINS_SIZE = 512;

    public static final int PLAINS_HEIGHT = 40;

    /**
     * How far above a plains depth's floor its walkable surface sits.
     *
     * <p>Below this is rock you cannot touch; above it is the open space. Which
     * splits the 40 blocks into 21 of ground and 19 of headroom — and 19 is
     * plenty, because {@code has_skylight} is false and {@code ambient_light} is
     * zero, so an unlit ceiling that close is simply not visible. The space reads
     * as open because you cannot find its lid, not because the lid is far away.
     */
    public static final int PLAINS_SOIL = 20;

    /**
     * The quarry cut into a plains depth: the ore, and the only part that refills.
     *
     * <p>Same footprint as a whole mine, so a plains is not a worse place to work
     * — it is the same amount of rock with a landscape around it. The 24-block
     * ring of ground left over is what makes it a place rather than a box.
     */
    public static final int PIT_SIZE = 48;

    /**
     * How much rock wraps a mine's ore box on every side and underneath.
     *
     * <p>Without this a mine is a 48x48x40 block of ore hanging in nothing, and
     * every route into it is a visible tube arriving out of the sky. Standing on
     * top of one, you could see the cave that brought you snaking away above the
     * horizon. Rock around it means the descent is <em>inside</em> something, and
     * the only place the void is ever seen is the first drop off the rig.
     */
    public static final int MASS_MARGIN = 8;

    /**
     * The chamber above a mine: where the descent puts you down.
     *
     * <p>The cave used to end by plunging through the mine's ceiling and dropping
     * you in the middle of the ore. It arrives horizontally now, out of the wall of
     * this room, the same way the mouth leaves the wall at the other end. You step
     * out of a cave into a cavern, walk across a rock ledge, and the ore is in
     * front of you.
     */
    public static final int CHAMBER_HEIGHT = 22;

    /** Rock above the chamber. Thin, but it is a roof and not a lid you can see. */
    public static final int CHAMBER_ROOF = 5;

    /** Walkable rock between the chamber's walls and the ore box. */
    public static final int CHAMBER_LEDGE = 4;

    /**
     * How far below the top of an ore body its cave mouth sits.
     *
     * <p>Not zero, because the way down should be <em>found</em>. Not deep,
     * because a player who cannot find it has no way to progress and no way to be
     * told. Eight blocks means you break into it during ordinary mining, from any
     * direction, without ever being pointed at it.
     */
    public static final int CAVE_MOUTH_DROP = 8;

    /**
     * How far above a room's floor the arrival tunnel is centred.
     *
     * <p>The carve is a ball, so its centre has to sit clear of the floor or the
     * ball scoops a pit out of the doorway you are walking through.
     */
    public static final int CAVE_DOOR_LIFT = 3;

    /**
     * How far outside the protected volume a descent has to stop.
     *
     * <p><b>Comfortably bigger than the spiral is wide, and that is the entire
     * requirement.</b> Inside a protected volume nothing is carved and nothing is
     * walled, so any stretch of path that strays in is not a tunnel — it is solid
     * rock, or a gap opening onto a 3,000-block drop. Land the descent closer than
     * the swing and its last levels wander in and kill the route without a word.
     *
     * <p>The number that matters is not {@code CAVE_SWING} but the swing after its
     * wobble, which peaks about 35% higher — 35 blocks, not 26. Sizing this against
     * 26 is exactly the mistake that severed the descent into every mine on the
     * server while a hundred tests passed. {@code CavePathTest} now checks the real
     * path against the real volume rather than trusting either number.
     */
    public static final int MASS_STANDOFF = 52;

    /** The same, for a plain — larger, because a plain protects its whole footprint. */
    public static final int PLAINS_STANDOFF = 56;

    /** How far past a chamber wall the arrival tunnel reaches, so it reads as a mouth. */
    public static final int CAVE_DOOR_DEPTH = 6;

    /**
     * The same, for a plains — far enough to get out from under the rim.
     *
     * <p>A plain's edge rises into cliffs, and the tunnel has to come through them
     * and keep going until the ground drops away, or its mouth opens inside a
     * hillside instead of at the foot of one.
     */
    public static final int CAVE_DOOR_DEPTH_PLAINS = 46;

    /**
     * Every fifth depth is a buried surface rather than a wall of ore.
     *
     * <p>Which puts one at D5 — so a new player's first session ends with the
     * rock opening into a plain under a false sky, instead of arriving at a
     * fifth identical box. And one at D10, which the spec already named
     * <b>the Crossing</b> and already made the depth where PvP switches on. An
     * open space where sound carries, loot worth taking, and other players
     * suddenly hostile: the name was waiting for it.
     */
    public static final int PLAINS_EVERY = 5;

    /**
     * How far each depth is offset from the vertical axis.
     *
     * <p>Stacking them in a perfect column would make the descent a lift shaft.
     * A slow spiral means each leg of the journey travels sideways as well as
     * down, which is what gives the connecting paths somewhere to go.
     */
    private static final int SPIRAL_RADIUS = 64;

    /** The golden angle. Successive depths never line up, and never repeat a bearing. */
    private static final double SPIRAL_STEP_RADIANS = Math.toRadians(137.507764);

    private DepthPlan() {
    }

    // ---- kinds ----------------------------------------------------------

    public enum Kind {
        /** A solid block of ore and filler. The default. */
        MINE,
        /**
         * A buried surface: open ground under a false sky, with a quarry cut into
         * the middle of it.
         *
         * <p><b>It is still a mine.</b> The ore is there and it still respawns —
         * what changes is everything around it. You are working open ground
         * instead of a tunnel, so there are no walls to contain sound, you can be
         * seen across the whole space, and the mob roster is different. A change
         * of <em>place</em>, not of activity: the rhythm of mine-sell-rank never
         * breaks, the world just stops looking like a hole every fifth depth.
         */
        PLAINS
    }

    /**
     * D1 is <b>the Landing</b>: a plain in the fog directly under the rigs.
     *
     * <p>That is the opening. You step off the rig, fall through fog, and land on
     * ground you did not expect to exist. The game introduces itself by dropping
     * you into it, and the first thing under your feet is ore.
     */
    public static Kind kindOf(int depth) {
        return depth == 1 || depth % PLAINS_EVERY == 0 ? Kind.PLAINS : Kind.MINE;
    }

    /** Plains deep enough to have earned a name. */
    public static String nameFor(int depth) {
        return switch (depth) {
            case 1 -> "the Landing";
            case 10 -> "the Crossing";
            default -> "D" + depth;
        };
    }

    public static boolean isPlains(int depth) {
        return kindOf(depth) == Kind.PLAINS;
    }

    // ---- geometry -------------------------------------------------------

    /** Ceiling of this depth. Descends {@link #DEPTH_SPACING_Y} per level. */
    public static int topYFor(int depth) {
        return FIRST_DEPTH_TOP_Y - (depth - 1) * DEPTH_SPACING_Y;
    }

    /**
     * <b>D1 is pinned to the origin, and the spiral starts at D2.</b>
     *
     * <p>Not cosmetic. The rig's drop hole is at {@code (0, 0)} and D1 is where
     * that drop is supposed to land — "a flat plain in the fog directly under the
     * rigs" is the opening sixty seconds of the game. The spiral used to include
     * D1, which put its centre 64 blocks east of the hole and its footprint
     * nowhere near it, so stepping off the rig dropped you down the side of the
     * Landing and into 3,500 blocks of void. The first thing the game asks you to
     * do killed you.
     */
    public static int centreXFor(int depth) {
        return depth <= 1 ? 0
                : (int) Math.round(Math.cos((depth - 1) * SPIRAL_STEP_RADIANS) * SPIRAL_RADIUS);
    }

    public static int centreZFor(int depth) {
        return depth <= 1 ? 0
                : (int) Math.round(Math.sin((depth - 1) * SPIRAL_STEP_RADIANS) * SPIRAL_RADIUS);
    }

    public static int sizeFor(int depth) {
        return isPlains(depth) ? PLAINS_SIZE : MINE_SIZE;
    }

    public static int heightFor(int depth) {
        return isPlains(depth) ? PLAINS_HEIGHT : MINE_HEIGHT;
    }

    /** @param depth 1-based, D1 is 1 */
    public static Cuboid boundsFor(String world, int depth) {
        int centreX = centreXFor(depth);
        int centreZ = centreZFor(depth);
        int half = sizeFor(depth) / 2;
        int topY = topYFor(depth);
        return new Cuboid(world,
                centreX - half, topY - (heightFor(depth) - 1), centreZ - half,
                centreX + half - 1, topY, centreZ + half - 1);
    }

    // ---- plains: ground, and a quarry cut into it ------------------------

    /** The walkable surface of a plains depth. Meaningless for a mine. */
    public static int groundYFor(int depth) {
        return floorYFor(depth) + PLAINS_SOIL;
    }

    /** The lowest block of a depth. Same arithmetic as {@link #boundsFor}, without the box. */
    public static int floorYFor(int depth) {
        return topYFor(depth) - (heightFor(depth) - 1);
    }

    /**
     * The ore body of a plains depth: a quarry sunk into the ground.
     *
     * <p>This is a <b>child region</b> of the depth, and it is the only part that
     * carries an ore table or refills. The ground around it is structure, built
     * once — which is the whole point, because a refill overwrites every block in
     * its region and a plain that refilled would erase its own landscape every
     * forty minutes.
     *
     * <p>Its top sits one block below the surface, so the quarry is a step down
     * into a field of ore rather than an ore-coloured patch of floor.
     */
    public static Cuboid pitBoundsFor(String world, int depth) {
        int centreX = centreXFor(depth);
        int centreZ = centreZFor(depth);
        int half = PIT_SIZE / 2;
        // Top face LEVEL with the plain, not a block below it. A step down made the
        // quarry read as a pit dropped into the ground; flush, with the ground
        // repalletted to the same stone family as the filler, it reads as the ore
        // simply being what is under this part of the plain.
        return new Cuboid(world,
                centreX - half, floorYFor(depth), centreZ - half,
                centreX + half - 1, groundYFor(depth), centreZ + half - 1);
    }

    public static String pitIdFor(int depth) {
        return "shaft.d" + depth + "-pit";
    }

    /**
     * The box a player is actually allowed to break blocks in.
     *
     * <p>A mine is all ore, so it is the depth itself. A plains is mostly
     * landscape, so it is the quarry.
     */
    public static Cuboid oreBodyFor(String world, int depth) {
        return isPlains(depth) ? pitBoundsFor(world, depth) : boundsFor(world, depth);
    }

    // ---- the rock a mine is buried in ------------------------------------

    /** The whole rock body of a mine depth: crust, ore box, chamber and roof. */
    public static Cuboid massBoundsFor(String world, int depth) {
        int centreX = centreXFor(depth);
        int centreZ = centreZFor(depth);
        int half = MINE_SIZE / 2 + MASS_MARGIN;
        return new Cuboid(world,
                centreX - half, floorYFor(depth) - MASS_MARGIN, centreZ - half,
                centreX + half - 1, topYFor(depth) + CHAMBER_HEIGHT + CHAMBER_ROOF,
                centreZ + half - 1);
    }

    /**
     * The only part of a depth the descent may not touch: the cavern, its walls
     * and its roof.
     *
     * <p>This used to be the whole rock body, and it severed two descents. The
     * spiral swings 26 blocks either side of its axis, so its last levels routinely
     * pass over the destination's footprint — and inside an exclusion, nothing is
     * carved <em>and</em> nothing is walled, so the tunnel simply stopped being a
     * tunnel. Blocked solid on the way into D2; a length of it missing entirely on
     * the way into D5.
     *
     * <p>What actually needs protecting is far smaller: the room, so the tube
     * cannot punch through its roof. Everywhere else in the mass, a descent boring
     * through solid rock is exactly what a descent should be doing.
     */
    public static Cuboid protectedCoreFor(String world, int depth) {
        Cuboid chamber = chamberBoundsFor(world, depth);
        Cuboid mass = massBoundsFor(world, depth);
        return new Cuboid(world,
                chamber.minX() - 3, chamber.minY() - 1, chamber.minZ() - 3,
                chamber.maxX() + 3, mass.maxY(), chamber.maxZ() + 3);
    }

    /**
     * The open room above a mine's ore.
     *
     * <p>Its floor is the ore box's top face plus a ledge of rock all round, so you
     * can walk the whole way round the ore without falling off anything. The actual
     * cavern carved inside it is blobbier than this and different at every depth —
     * this is the envelope, not the shape.
     */
    public static Cuboid chamberBoundsFor(String world, int depth) {
        int centreX = centreXFor(depth);
        int centreZ = centreZFor(depth);
        int half = MINE_SIZE / 2 + CHAMBER_LEDGE;
        return new Cuboid(world,
                centreX - half, topYFor(depth) + 1, centreZ - half,
                centreX + half - 1, topYFor(depth) + CHAMBER_HEIGHT, centreZ + half - 1);
    }

    // ---- the winch: the only way up --------------------------------------

    /**
     * How far past the quarry rim a station's winch stands.
     *
     * <p>Inside the level apron, so the machine sits on flat ground and does not
     * need its own terrain carve — and close enough to the ore that walking to it
     * with a full load is a decision rather than an expedition.
     */
    public static final int WINCH_OFFSET = 30;

    public static final int WINCH_HEIGHT = 11;

    /** The base of a station's winch. Plains only — a mine is not somewhere safe. */
    public static int[] winchAt(int depth) {
        return new int[]{
                centreXFor(depth),
                groundYFor(depth) + 1,
                centreZFor(depth) + PIT_SIZE / 2 + WINCH_OFFSET
        };
    }

    /**
     * The block you actually pull.
     *
     * <p>Both this and the structure come from {@link #winchAt}, so the block a
     * player right-clicks and the block the world placed cannot drift apart — which
     * is the sort of thing that fails silently and looks like the winch is broken.
     */
    public static int[] winchLever(int depth) {
        int[] base = winchAt(depth);
        return new int[]{base[0] + 2, base[1] + 1, base[2]};
    }

    // ---- the descent -----------------------------------------------------

    /**
     * Where the cave down to the next depth opens.
     *
     * <p>One block <em>outside</em> the ore body, which is the property that makes
     * this work at all: a refill overwrites everything inside its region, so a
     * mouth carved into the ore would be sealed the first time the mine turned
     * over and the way down would silently vanish. Outside by one, and it is
     * permanent.
     *
     * <p>The bearing is toward the next depth, so the cave never has to double
     * back on itself, and {@link #CAVE_MOUTH_DROP} below the top of the ore so
     * that you break into it while mining rather than being shown it.
     */
    public static int[] caveMouth(int depth) {
        Cuboid ore = oreBodyFor("", depth);
        double bearing = bearingTo(depth, depth + 1);
        double alongX = Math.cos(bearing);
        double alongZ = Math.sin(bearing);
        int centreX = centreXFor(depth);
        int centreZ = centreZFor(depth);

        // Stepped off the FACE the bearing points at, not off a circle. An ore
        // body is a square, so a diagonal bearing walked out by half its width
        // lands back inside it — which is how the first version of this put D1's
        // mouth six blocks deep in its own quarry, where the first refill would
        // have bricked the only way down with nothing to report it.
        int x;
        int z;
        if (Math.abs(alongX) >= Math.abs(alongZ)) {
            x = alongX >= 0 ? ore.maxX() + 1 : ore.minX() - 1;
            z = centreZ + (int) Math.round((x - centreX) * (alongZ / alongX));
        } else {
            z = alongZ >= 0 ? ore.maxZ() + 1 : ore.minZ() - 1;
            x = centreX + (int) Math.round((z - centreZ) * (alongX / alongZ));
        }
        // On a plains the way down is at the BOTTOM of the quarry, not high in its
        // wall. Two reasons, and the second is a bug the first would have hidden.
        // It reads right — you work the pit out and find the way on at its floor,
        // so the quarry IS the route. And it has to be deep: the mouth's carve is a
        // ball, so a mouth a few blocks under a plain's surface bursts up through
        // the ground and leaves holes in the floor of the plain around it.
        int y = isPlains(depth)
                ? ore.minY() + 3
                : ore.maxY() - CAVE_MOUTH_DROP;
        return new int[]{x, y, z};
    }

    /**
     * Where the descending cave stops — <b>outside</b> the depth, level with the
     * room it is about to open into.
     *
     * <p>The descent used to end at the middle of a mine's ceiling and drop you
     * through it, which meant the way in was a hole in the sky and the tube that
     * made it was visible from the whole depth. Now the descent comes down the
     * outside of the rock and stops; {@link #caveAdit} runs the last stretch in
     * horizontally, so you arrive out of a wall exactly the way you left one.
     *
     * <p>The face it stops against is chosen by the bearing from the depth above,
     * so the cave never has to double back on itself to reach its own door.
     */
    public static int[] caveLanding(int depth) {
        Cuboid room = roomFor(depth);
        double bearing = bearingTo(depth, Math.max(1, depth - 1));
        int y = (isPlains(depth) ? groundYFor(depth) : topYFor(depth)) + 1 + CAVE_DOOR_LIFT;
        int[] face = faceOf(room, centreXFor(depth), centreZFor(depth), bearing,
                isPlains(depth) ? PLAINS_STANDOFF : MASS_STANDOFF);
        return new int[]{face[0], y, face[1]};
    }

    /**
     * The inner end of the horizontal run that finishes a descent.
     *
     * <p>Sweeping from {@link #caveLanding} to here is what turns "a tube ending
     * above the floor" into "a cave mouth in a wall".
     */
    public static int[] caveAdit(int depth) {
        Cuboid room = roomFor(depth);
        double bearing = bearingTo(depth, Math.max(1, depth - 1));
        int y = (isPlains(depth) ? groundYFor(depth) : topYFor(depth)) + 1 + CAVE_DOOR_LIFT;
        // Far enough past the wall that the opening is a mouth and not a dent.
        int[] inner = faceOf(room, centreXFor(depth), centreZFor(depth), bearing,
                -(isPlains(depth) ? CAVE_DOOR_DEPTH_PLAINS : CAVE_DOOR_DEPTH));
        return new int[]{inner[0], y, inner[1]};
    }

    /** The room a descent opens into: a mine's chamber, or the plain itself. */
    private static Cuboid roomFor(int depth) {
        return isPlains(depth) ? boundsFor("", depth) : chamberBoundsFor("", depth);
    }

    /**
     * A point on the face of a box that a bearing points at, pushed out by
     * {@code beyond} blocks (negative to move inward).
     */
    private static int[] faceOf(Cuboid box, int centreX, int centreZ,
                                double bearing, int beyond) {
        double alongX = Math.cos(bearing);
        double alongZ = Math.sin(bearing);
        if (Math.abs(alongX) >= Math.abs(alongZ)) {
            int x = alongX >= 0 ? box.maxX() + beyond : box.minX() - beyond;
            return new int[]{x, centreZ + (int) Math.round((x - centreX) * (alongZ / alongX))};
        }
        int z = alongZ >= 0 ? box.maxZ() + beyond : box.minZ() - beyond;
        return new int[]{centreX + (int) Math.round((z - centreZ) * (alongX / alongZ)), z};
    }

    /** Radians from one depth's centre toward another's. */
    private static double bearingTo(int from, int to) {
        int dx = centreXFor(to) - centreXFor(from);
        int dz = centreZFor(to) - centreZFor(from);
        // Two depths cannot share a centre, but a clamped depth number could make
        // them; a fixed bearing is better than NaN.
        return dx == 0 && dz == 0 ? 0.0 : Math.atan2(dz, dx);
    }

    /**
     * Somewhere in this depth a player can be put down safely.
     *
     * <p>Not a destination anyone can ask for — there is no fast travel. It exists
     * because a mine refilling under a player's feet has to move them somewhere,
     * and the right somewhere is this depth's own arrival point rather than the
     * far side of the world.
     *
     * <p>{@code +2}, not {@code +1}. Shadow Prison used +1 and players spawned
     * knee-deep in the deck and had to jump out every single time.
     */
    public static Location entryFor(World world, int depth) {
        int[] landing = caveLanding(depth);
        return new Location(world, landing[0] + 0.5, landing[1] + 1, landing[2] + 0.5, 0f, 0f);
    }

    /** The open middle of the rig deck. You step into it to begin. */
    public static final int RIG_HOLE = 6;

    /**
     * Half-width of the rig deck.
     *
     * <p>96, so 193 across. It is not a platform any more, it is the <b>town</b>:
     * the spawn has to hold a market of NPCs and a district of player plots, and
     * the previous 53-wide deck could not have held either without them sitting on
     * top of each other. Laid out in rings from the drop hole — plaza, then the
     * market, then the plots.
     */
    public static final int RIG_HALF = 96;

    /** Everything inside this is the plaza around the drop. */
    public static final int RIG_PLAZA = 26;

    /** The market ring: NPC buildings live between these radii. */
    public static final int RIG_MARKET_IN = 30;

    public static final int RIG_MARKET_OUT = 44;

    /** Player plots start here and run to the deck edge. */
    public static final int RIG_PLOTS_IN = 50;

    /** One plot cell: a 16x16 pad with a 4-wide walkway on two sides. */
    public static final int RIG_PLOT_CELL = 20;

    public static final int RIG_PLOT_WALK = 4;

    /**
     * Spawn, on the rig deck above the fog — <b>beside the hole, not in it</b>.
     *
     * <p>It used to be {@code (0.5, RIG_Y + 1, 0.5)}, which is dead centre of the
     * drop hole, so {@code /spawn} did not put you on the rig at all: it dropped
     * you straight back down the shaft you had just climbed out of. Standing on the
     * deck a few blocks south, facing north, the hole is the first thing in view —
     * which is the whole job of this location.
     */
    public static Location rigSpawn(World world) {
        return new Location(world, 0.5, RIG_Y + 1, RIG_HOLE + 5.5, 180f, 0f);
    }

    public static String regionIdFor(int depth) {
        return "depth.d" + depth;
    }

    /**
     * Rank needed to mine here. D1 is the starting rank, and each depth after it
     * is one rung further up, so "rank up, go deeper" needs no explanation.
     */
    public static int requiredRankFor(int depth) {
        return depth - 1;
    }

    // ---- filler ---------------------------------------------------------

    /**
     * One filler per depth.
     *
     * <p>Three constraints, all of which cost money to get wrong:
     *
     * <ol>
     *   <li><b>It must drop itself.</b> {@code STONE} yields {@code COBBLESTONE}
     *       and {@code DEEPSLATE} yields {@code COBBLED_DEEPSLATE}, so either
     *       one means the block placed and the item priced are different
     *       materials — and a price set on the wrong one silently pays nothing.
     *   <li><b>It must not be craftable from a cheaper priced item.</b> This is
     *       the one that bites. An earlier draft of this list used the polished
     *       and brick variants, which look right and are money printers:
     *       {@code POLISHED_TUFF} at $7.25 crafts four-for-four from
     *       {@code TUFF} at $0.70. Shadow Prison learned the same lesson from
     *       {@code NETHER_GOLD_ORE} dropping {@code GOLD_NUGGET} at 8.1x, and
     *       wrote the rule down: a price is only safe when nothing cheap crafts
     *       into it in bulk. Enforced by {@code PriceTable.findCraftPrinters}.
     *   <li><b>Deep filler must be worth much more than shallow filler.</b>
     *       Nearly flat for twelve depths, then a step change. That step is most
     *       of why anyone wants the next depth.
     * </ol>
     *
     * <p>Which is why the deep half is <b>concrete</b>, copying Shadow Prison —
     * not for the look, which is wrong for a drowned continent, but because
     * concrete is reachable only through concrete powder from sand, gravel and
     * dye, none of which are priced. It is the only cheap family in the game
     * with no path back from anything on the price list. Repaint it later; keep
     * the property.
     */
    private static final Material[] FILLERS = {
            // D1-D12: natural stone, $0.50-$1.05. The band is narrow enough that
            // the craft relationships inside it are break-even or worse.
            Material.COBBLESTONE, Material.ANDESITE, Material.MOSSY_COBBLESTONE,
            Material.DIORITE, Material.GRANITE, Material.TUFF,
            Material.COBBLED_DEEPSLATE, Material.BLACKSTONE, Material.BASALT,
            Material.DRIPSTONE_BLOCK, Material.CALCITE, Material.END_STONE,
            // D13-D26: concrete, $5.00-$9.75. Uncraftable from anything priced.
            Material.GRAY_CONCRETE, Material.LIGHT_GRAY_CONCRETE, Material.CYAN_CONCRETE,
            Material.BLUE_CONCRETE, Material.BROWN_CONCRETE, Material.GREEN_CONCRETE,
            Material.PURPLE_CONCRETE, Material.BLACK_CONCRETE, Material.RED_CONCRETE,
            Material.ORANGE_CONCRETE, Material.MAGENTA_CONCRETE, Material.PINK_CONCRETE,
            Material.LIME_CONCRETE, Material.YELLOW_CONCRETE
    };

    /**
     * Dollars per filler block, indexed by depth - 1.
     *
     * <p><b>Solved, not chosen.</b> The first draft copied Shadow Prison's smooth
     * ramp, and {@code /still economy curve} immediately showed the result was
     * not monotonic: D15 paid 8% <em>less</em> per block than D14, and D14 to D18
     * drifted downward overall. A player would buy rank O for $700,000 and get
     * poorer. Shadow Prison's live table has the same dips.
     *
     * <p>The cause is the multi-drop ores. Lapis yields 4-9 per block, so at $14
     * an item it is worth about $91 a block — four times gold. When lapis leaves
     * the mix at D16, ore income falls off a cliff that the ore weights alone
     * cannot smooth.
     *
     * <p>Filler is 75% of every mine, so it is the lever that can absorb that.
     * These numbers were solved backwards from the curve with one rule — <b>each
     * depth must pay at least 3% more per block than the one above it, and filler
     * price never decreases</b> — which is why there are flat runs: through D4-D9
     * and D12-D14 the ore ramp is already doing the work.
     *
     * <p>{@code DepthPlanTest} asserts the monotonicity, so a future retune that
     * reintroduces a dip fails the build rather than the player.
     */
    private static final double[] FILLER_PRICES = {
            0.50, 0.60, 0.70, 0.70, 0.70, 0.70, 0.70, 0.70, 0.70, 1.55, 1.55, 1.55,
            3.85, 3.85, 6.10, 7.15, 8.30, 9.25, 9.25, 11.20, 11.20, 11.30, 12.20,
            12.20, 12.20, 12.20
    };

    public static Material fillerFor(int depth) {
        return FILLERS[Math.clamp(depth - 1, 0, FILLERS.length - 1)];
    }

    public static double fillerPriceFor(int depth) {
        return FILLER_PRICES[Math.clamp(depth - 1, 0, FILLER_PRICES.length - 1)];
    }

    // ---- ores -----------------------------------------------------------

    /**
     * The ore ramp, in the order it unlocks:
     * coal → iron → copper → redstone → gold → lapis → emerald → quartz.
     *
     * <p>Deepslate variants where they exist, for the fiction. The variant makes
     * no economic difference — it drops the same item — which is exactly why
     * Shadow Prison forbade <em>mixing</em> both variants in one table. Using one
     * consistently is fine.
     */
    private static final Material[] ORES = {
            Material.DEEPSLATE_COAL_ORE,      // 0 drops COAL          x1
            Material.DEEPSLATE_IRON_ORE,      // 1 drops RAW_IRON      x1
            Material.DEEPSLATE_COPPER_ORE,    // 2 drops RAW_COPPER    x2-5
            Material.DEEPSLATE_REDSTONE_ORE,  // 3 drops REDSTONE      x4-5
            Material.DEEPSLATE_GOLD_ORE,      // 4 drops RAW_GOLD      x1
            Material.DEEPSLATE_LAPIS_ORE,     // 5 drops LAPIS_LAZULI  x4-9
            Material.DEEPSLATE_EMERALD_ORE,   // 6 drops EMERALD       x1
            Material.NETHER_QUARTZ_ORE        // 7 drops QUARTZ        x1
    };

    /**
     * Shadow Prison's live per-tier ore weights, ported verbatim.
     *
     * <p>Columns are {@link #ORES}; every row sums to exactly 25 against a filler
     * of 75. That denominator is fixed on purpose — it is the constant every
     * later multiplier is tuned against, so the Din band in Phase 2 and the heat
     * state in Phase 4 can be reasoned about without also wondering whether
     * somebody edited an ore table.
     *
     * <p>Note the overlap: a new ore always arrives before the previous one
     * leaves, so going deeper adds something rather than swapping one grind for
     * another. That is the property to preserve if these are ever retuned.
     */
    private static final int[][] ORE_WEIGHTS = {
            //  coal iron copp reds gold laps emer qrtz
            {25, 0, 0, 0, 0, 0, 0, 0},   // D1
            {25, 0, 0, 0, 0, 0, 0, 0},   // D2
            {25, 0, 0, 0, 0, 0, 0, 0},   // D3
            {15, 10, 0, 0, 0, 0, 0, 0},  // D4
            {12, 13, 0, 0, 0, 0, 0, 0},  // D5
            {9, 11, 5, 0, 0, 0, 0, 0},   // D6
            {6, 12, 7, 0, 0, 0, 0, 0},   // D7
            {5, 10, 6, 4, 0, 0, 0, 0},   // D8
            {0, 9, 8, 5, 3, 0, 0, 0},    // D9
            {0, 9, 5, 4, 7, 0, 0, 0},    // D10
            {0, 6, 4, 5, 10, 0, 0, 0},   // D11
            {0, 7, 5, 0, 8, 5, 0, 0},    // D12
            {0, 7, 5, 0, 10, 3, 0, 0},   // D13
            {0, 6, 4, 0, 8, 4, 3, 0},    // D14
            {0, 9, 3, 0, 8, 3, 2, 0},    // D15
            {0, 5, 2, 0, 12, 0, 6, 0},   // D16
            {0, 5, 1, 0, 14, 0, 5, 0},   // D17
            {0, 6, 0, 0, 10, 0, 3, 6},   // D18
            {0, 0, 0, 0, 12, 0, 5, 8},   // D19
            {0, 0, 0, 0, 13, 0, 2, 10},  // D20
            {0, 0, 0, 0, 12, 0, 4, 9},   // D21
            {0, 0, 0, 0, 11, 0, 5, 9},   // D22
            {0, 0, 0, 0, 13, 0, 5, 7},   // D23
            {0, 0, 0, 0, 12, 0, 7, 6},   // D24
            {0, 0, 0, 0, 12, 0, 9, 4},   // D25
            {0, 0, 0, 0, 10, 0, 12, 3},  // D26
    };

    /**
     * How many items one of these blocks yields on average, and which item.
     *
     * <p>The multi-drop ores are the reason the income curve is not simply the
     * weight table: a weight of 5 lapis outearns a weight of 12 gold.
     */
    public static Material dropOf(Material block) {
        return switch (block) {
            case DEEPSLATE_COAL_ORE -> Material.COAL;
            case DEEPSLATE_IRON_ORE -> Material.RAW_IRON;
            case DEEPSLATE_COPPER_ORE -> Material.RAW_COPPER;
            case DEEPSLATE_REDSTONE_ORE -> Material.REDSTONE;
            case DEEPSLATE_GOLD_ORE -> Material.RAW_GOLD;
            case DEEPSLATE_LAPIS_ORE -> Material.LAPIS_LAZULI;
            case DEEPSLATE_EMERALD_ORE -> Material.EMERALD;
            case NETHER_QUARTZ_ORE -> Material.QUARTZ;
            default -> block; // filler drops itself, which is why FILLERS must self-drop
        };
    }

    public static double averageDropCount(Material block) {
        return switch (block) {
            case DEEPSLATE_COPPER_ORE -> 3.5;   // vanilla 2-5
            case DEEPSLATE_REDSTONE_ORE -> 4.5; // vanilla 4-5
            case DEEPSLATE_LAPIS_ORE -> 6.5;    // vanilla 4-9
            default -> 1.0;
        };
    }

    /**
     * Expected money per block broken at this depth, in minor units.
     *
     * <p>The number the whole progression rests on. Shared by
     * {@code /still economy curve} and by the test that guards monotonicity, so
     * the instrument and the assertion can never disagree.
     *
     * @param unitPrice minor units per item, or 0 for anything unpriced
     */
    public static double expectedValuePerBlock(int depth,
                                               java.util.function.ToLongFunction<Material> unitPrice) {
        OreTable table = tableFor(depth);
        double expected = 0;
        for (var entry : table.weights().entrySet()) {
            Material block = entry.getKey();
            double share = (double) entry.getValue() / table.totalWeight();
            expected += share * unitPrice.applyAsLong(dropOf(block)) * averageDropCount(block);
        }
        return expected;
    }

    public static OreTable tableFor(int depth) {
        int row = Math.clamp(depth - 1, 0, ORE_WEIGHTS.length - 1);
        OreTable.Builder table = OreTable.builder(fillerFor(depth));
        int[] weights = ORE_WEIGHTS[row];
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > 0) {
                table.ore(ORES[i], weights[i]);
            }
        }
        // build() gives the filler whatever is left of 100, and throws if the
        // ores ever exceed it - so a mistyped row fails at startup, loudly,
        // rather than shipping a mine that is 4% filler.
        return table.build();
    }
}
