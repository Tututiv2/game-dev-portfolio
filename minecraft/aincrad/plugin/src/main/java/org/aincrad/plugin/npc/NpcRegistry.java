package org.aincrad.plugin.npc;

import org.aincrad.plugin.district.BuildingBuilder;
import org.aincrad.plugin.district.Parcel;
import org.aincrad.plugin.ledger.GenerationLedger;
import org.aincrad.plugin.terrain.TerrainProfile;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The town's logical population, and the controller that decides which sliver of it is ever
 * allowed to be active.
 *
 * <p><b>Nothing here spawns an entity.</b> The roster is generated deterministically from the same
 * parcel plan the buildings came from - so every inhabitant lives in a house that genuinely exists
 * - persisted for stable ids and versioning, and then left as rows. {@link #computeActive} answers
 * "who would be active right now" against the spec's two hard budgets (160 town-wide, 48 in any
 * 10-chunk cluster) without instantiating anything, which is what lets a town of ~1,500 people cost
 * nothing when nobody is looking at it.</p>
 *
 * <p>Activation is keyed by global floor as well as position. Ten floors share one dimension and
 * one chunk column, so a controller keyed only on (chunkX, chunkZ) would happily wake Floor 7's
 * residents because someone walked through Floor 1's market.</p>
 */
public final class NpcRegistry {

    public static final String VERSION = "npc_roster:v1";
    private static final int GLOBAL_FLOOR = 1;

    private final List<NpcRecord> roster = new ArrayList<>();
    private int townWideBudget = 160;
    private int localClusterBudget = 48;
    private int activationRadius = 48;

    public void configure(int townWideBudget, int localClusterBudget, int activationRadius) {
        this.townWideBudget = townWideBudget;
        this.localClusterBudget = localClusterBudget;
        this.activationRadius = activationRadius;
    }

    /**
     * Build the roster from the parcel plan. Household sizes are tuned so the total lands inside
     * the spec's 1,200-1,800 logical-record band rather than being capped afterwards.
     */
    public List<NpcRecord> build(List<Parcel> parcels, TerrainProfile terrain, long worldSeed) {
        roster.clear();
        for (Parcel p : parcels) {
            Random rng = new Random(worldSeed ^ (p.id().hashCode() * 2654435761L));
            BuildingBuilder.Plan plan = BuildingBuilder.plan(p, terrain, worldSeed);
            int count;
            NpcRecord.Occupation occ;
            switch (p.role()) {
                case HOME -> {
                    count = 1 + rng.nextInt(2);          // 1-2
                    occ = NpcRecord.Occupation.RESIDENT;
                }
                case SHOP -> {
                    count = 1;
                    occ = NpcRecord.Occupation.SHOPKEEPER;
                }
                case WORKSHOP -> {
                    count = 1;
                    occ = NpcRecord.Occupation.ARTISAN;
                }
                case INN -> {
                    count = 2 + rng.nextInt(2);          // 2-3
                    occ = NpcRecord.Occupation.INNKEEPER;
                }
                default -> {
                    count = 1;
                    occ = NpcRecord.Occupation.CIVIC_STAFF;
                }
            }
            for (int i = 0; i < count; i++) {
                String id = p.id().replace("/parcel_", "/npc_") + "_" + i;
                String schedule = switch (occ) {
                    case RESIDENT -> "sched/home_day";
                    case SHOPKEEPER, ARTISAN -> "sched/trade_day";
                    case INNKEEPER -> "sched/inn_evening";
                    default -> "sched/civic_day";
                };
                roster.add(new NpcRecord(id, GLOBAL_FLOOR, p.id(), occ,
                        plan.insideX(), plan.baseY() + 1, plan.insideZ(), schedule, VERSION));
            }
        }
        roster.sort(Comparator.comparing(NpcRecord::id));
        return roster;
    }

    public void persist(GenerationLedger ledger, Logger logger) {
        if (ledger == null) {
            return;
        }
        try {
            ledger.connection().setAutoCommit(false);
            for (NpcRecord r : roster) {
                ledger.upsertNpc(r.id(), r.globalFloor(), r.homeParcelId(), r.occupation().name(),
                        r.x(), r.y(), r.z(), r.scheduleId(), r.version());
            }
            ledger.connection().commit();
            ledger.connection().setAutoCommit(true);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to persist NPC roster", e);
        }
    }

    /**
     * Which records would be active for the given players. Enforces the local 10-chunk cluster cap
     * first and the town-wide cap second, so one dense crowd cannot consume the whole global budget
     * and starve the rest of the town.
     *
     * @param players {x, y, z} per online player, already filtered to this dimension
     */
    public List<NpcRecord> computeActive(List<int[]> players) {
        List<NpcRecord> candidates = new ArrayList<>();
        for (NpcRecord r : roster) {
            for (int[] pl : players) {
                // Floor-aware: a player must be on this record's own floor band to wake it.
                if (floorOf(pl[1]) != r.globalFloor()) {
                    continue;
                }
                if (Math.hypot(r.x() - pl[0], r.z() - pl[2]) <= activationRadius) {
                    candidates.add(r);
                    break;
                }
            }
        }
        candidates.sort(Comparator.comparing(NpcRecord::id));

        Map<Long, Integer> perCluster = new HashMap<>();
        List<NpcRecord> active = new ArrayList<>();
        for (NpcRecord r : candidates) {
            if (active.size() >= townWideBudget) {
                break;
            }
            long cluster = r.clusterKey();
            int used = perCluster.getOrDefault(cluster, 0);
            if (used >= localClusterBudget) {
                continue;
            }
            perCluster.put(cluster, used + 1);
            active.add(r);
        }
        return active;
    }

    /** Maps a Y coordinate to its global floor in the ten-floor stack. */
    public static int floorOf(int y) {
        if (y >= -64 && y <= 479) {
            return 1;
        }
        for (int floor = 2; floor <= 10; floor++) {
            int surface = 320 + 160 * (floor - 1);
            if (y >= surface && y < surface + 160) {
                return floor;
            }
        }
        return -1;
    }

    public List<NpcRecord> roster() {
        return roster;
    }

    public int townWideBudget() {
        return townWideBudget;
    }

    public int localClusterBudget() {
        return localClusterBudget;
    }
}
