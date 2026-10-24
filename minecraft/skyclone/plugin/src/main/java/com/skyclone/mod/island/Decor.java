package com.skyclone.mod.island;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Social-skill island goods (research 02 §2.3: what the reference's island decorator sells at Social 1-10, plus
 * "Island Ranks" at 9 and "Island Naming" at 20), as placed on a Private Island. The SHOPS module registers and sells
 * the items ({@code data/shops/items.json}, hand-off Request 12); everything they DO once placed is here:
 * <ul>
 *   <li><b>Parkour</b>: Start/End plates ({@code PARKOUR_CONTROLLER}, max 2: the first is the start, a second is the
 *       finish; with only one the course is a loop), checkpoints ({@code PARKOUR_POINT}, run in the order they were
 *       placed) and Times plinths ({@code PARKOUR_TIMES}) showing the island's best times.</li>
 *   <li><b>Social Display</b>: a plinth showing the island's most frequent visitors.</li>
 *   <li><b>Egg Hunt</b>: up to 10 eggs hidden by the members; each guest finds each egg once.</li>
 *   <li><b>Showcase Block</b>: a glass case showing a picture of an item (a display copy - the item stays yours).</li>
 *   <li><b>Island NPC</b>: a resident with a name, a line and a look the members choose.</li>
 * </ul>
 * Nothing here pays coins or items: they are social toys, so they can never print anything.
 *
 * <p>No Bukkit here: unit tests run every rule. Positions are template-relative block coordinates.
 */
public final class Decor {

    private Decor() {}

    public static final String START = "PARKOUR_CONTROLLER", POINT = "PARKOUR_POINT", TIMES = "PARKOUR_TIMES",
            DISPLAY = "SOCIAL_DISPLAY", EGG = "EGG_HUNT", SHOWCASE = "SHOWCASE_BLOCK", NPC = "ISLAND_NPC";

    /** Every decoration kind, in menu order. */
    public static final List<String> KINDS = List.of(START, POINT, TIMES, DISPLAY, EGG, SHOWCASE, NPC);

    /** Kinds that are a block in the world (the NPC is an entity). */
    public static boolean isBlock(String kind) { return KINDS.contains(kind) && !NPC.equals(kind); }

    public static boolean isKind(String id) { return id != null && KINDS.contains(id); }

    public static String label(String kind) {
        return switch (kind) {
            case START -> "Parkour Start/End";
            case POINT -> "Parkour Checkpoint";
            case TIMES -> "Parkour Times";
            case DISPLAY -> "Social Display";
            case EGG -> "Egg Hunt Egg";
            case SHOWCASE -> "Showcase Block";
            case NPC -> "Island NPC";
            default -> kind;
        };
    }

    /** Block materials a placed decoration may legitimately be (anything else means it is gone). */
    public static boolean materialMatches(String kind, String material) {
        if (material == null) return false;
        return switch (kind) {
            case START -> material.equals("LIGHT_WEIGHTED_PRESSURE_PLATE");
            case POINT -> material.equals("HEAVY_WEIGHTED_PRESSURE_PLATE");
            case TIMES -> material.equals("IRON_BLOCK");
            case DISPLAY -> material.equals("GOLD_BLOCK");
            case EGG -> material.equals("PLAYER_HEAD") || material.equals("PLAYER_WALL_HEAD");
            case SHOWCASE -> material.equals("GLASS");
            default -> true;
        };
    }

    // =========================================================================================
    // stored state (Gson: add fields, never retype them)
    // =========================================================================================

    /** One placed decoration. */
    public static final class Placed {
        public String kind;
        public int x, y, z;
        /** Placement order (checkpoints run in this order). */
        public long seq;
        public long at;
        public String placedBy;
        /** NPC: name; showcase: unused. */
        public String name;
        /** NPC: what it says. */
        public String text;
        /** NPC: villager profession id. */
        public String look;
        /** NPC: facing. */
        public float yaw;
        /** Showcase: the displayed item (base64 bytes of a display-only copy). */
        public String item;

        public Placed() {}

        public Placed(String kind, int x, int y, int z, long seq) {
            this.kind = kind; this.x = x; this.y = y; this.z = z; this.seq = seq;
        }

        public String pos() { return posKey(x, y, z); }
    }

    /** Everything decorations remember per island. */
    public static final class State {
        public List<Placed> placed = new ArrayList<>();
        public long seq;
        /** Best parkour time (ms) per player uuid, for the course with signature {@link #courseSig}. */
        public Map<String, Long> parkourBest = new HashMap<>();
        public String courseSig;
        /** Egg positions each guest (uuid) has found. */
        public Map<String, Set<String>> eggsFound = new HashMap<>();
    }

    public static State sanitize(State s) {
        if (s == null) s = new State();
        if (s.placed == null) s.placed = new ArrayList<>();
        s.placed.removeIf(p -> p == null || !isKind(p.kind));
        if (s.parkourBest == null) s.parkourBest = new HashMap<>();
        if (s.eggsFound == null) s.eggsFound = new HashMap<>();
        for (Placed p : s.placed) s.seq = Math.max(s.seq, p.seq);
        return s;
    }

    public static String posKey(int x, int y, int z) { return x + "," + y + "," + z; }

    public static Placed at(State s, int x, int y, int z) {
        String k = posKey(x, y, z);
        for (Placed p : s.placed) if (p.pos().equals(k)) return p;
        return null;
    }

    public static Placed bySeq(State s, long seq) {
        for (Placed p : s.placed) if (p.seq == seq) return p;
        return null;
    }

    public static int count(State s, String kind) {
        int n = 0;
        for (Placed p : s.placed) if (kind.equals(p.kind)) n++;
        return n;
    }

    public static List<Placed> of(State s, String kind) {
        List<Placed> out = new ArrayList<>();
        for (Placed p : s.placed) if (kind.equals(p.kind)) out.add(p);
        out.sort(Comparator.comparingLong(p -> p.seq));
        return out;
    }

    /** Why {@code kind} may not be placed (limit reached), or null. {@code limits}: kind -> max (missing = no limit). */
    public static String placeProblem(State s, String kind, Map<String, Integer> limits) {
        if (!isKind(kind)) return "That is not an island decoration.";
        Integer max = limits.get(kind);
        if (max != null && count(s, kind) >= Math.max(0, max))
            return "Your island already has the most " + label(kind) + (label(kind).endsWith("s") ? "" : "s") + " it can hold (" + max + ").";
        return null;
    }

    /** Record a new decoration; returns it. */
    public static Placed add(State s, String kind, int x, int y, int z, long now, String by) {
        Placed p = new Placed(kind, x, y, z, ++s.seq);
        p.at = now;
        p.placedBy = by;
        s.placed.add(p);
        return p;
    }

    public static Placed remove(State s, int x, int y, int z) {
        Placed p = at(s, x, y, z);
        if (p != null) s.placed.remove(p);
        return p;
    }

    // =========================================================================================
    // parkour
    // =========================================================================================

    /** The island's course: start, finish (== start for a loop), checkpoints in placement order. */
    public record Course(Placed start, Placed end, List<Placed> checkpoints, String problem) {
        public boolean ok() { return problem == null; }
        public boolean loop() { return start != null && start == end; }
        public String signature() {
            if (start == null) return "";
            StringBuilder b = new StringBuilder(start.pos()).append('>');
            for (Placed c : checkpoints) b.append(c.pos()).append('>');
            return b.append(end == null ? "" : end.pos()).toString();
        }
        /** -1 start, -2 finish (or the loop's start/finish), 0.. checkpoint index, -3 not part of the course. */
        public int role(String pos) {
            if (start != null && start.pos().equals(pos)) return loop() ? -2 : -1;
            if (end != null && end.pos().equals(pos)) return -2;
            for (int i = 0; i < checkpoints.size(); i++) if (checkpoints.get(i).pos().equals(pos)) return i;
            return -3;
        }
    }

    public static Course course(State s) {
        List<Placed> ctl = of(s, START);
        List<Placed> cps = of(s, POINT);
        if (ctl.isEmpty()) return new Course(null, null, cps, "Place a Parkour Start/End plate to begin a course.");
        Placed start = ctl.get(0);
        Placed end = ctl.size() > 1 ? ctl.get(1) : start;
        if (start == end && cps.isEmpty())
            return new Course(start, end, cps, "A course that starts and finishes on one plate needs at least one checkpoint (or a second Start/End plate).");
        return new Course(start, end, cps, null);
    }

    /** Outcome of stepping onto a plate. */
    public enum Step { NONE, STARTED, RESTARTED, CHECKPOINT, WRONG_CHECKPOINT, MISSED, FINISHED, COURSE_CHANGED }

    /** One player's run in progress. */
    public static final class Run {
        public final String sig;
        public long startMs;
        public int next;
        /** Elapsed time of the last FINISHED step. */
        public long finalMs;

        public Run(String sig, long startMs) { this.sig = sig; this.startMs = startMs; }
    }

    /**
     * The state machine. {@code run} may be null (not running); returns the new run (null = not running) inside
     * {@code out[0]} and the outcome. Stepping on the start restarts the timer; checkpoints must be taken in order;
     * the finish only counts after the last checkpoint.
     */
    public static Step step(Course c, Run run, String pos, long now, Run[] out) {
        out[0] = run;
        if (c == null || !c.ok()) { out[0] = null; return run == null ? Step.NONE : Step.COURSE_CHANGED; }
        if (run != null && !run.sig.equals(c.signature())) { out[0] = null; return Step.COURSE_CHANGED; }
        int role = c.role(pos);
        if (role == -3) return Step.NONE;
        if (run == null) {
            if (role == -1 || (role == -2 && c.loop())) { out[0] = new Run(c.signature(), now); return Step.STARTED; }
            return Step.NONE;
        }
        if (role == -1) { out[0] = new Run(c.signature(), now); return Step.RESTARTED; }
        if (role == -2) {
            if (run.next >= c.checkpoints().size()) {
                run.finalMs = Math.max(0, now - run.startMs);
                out[0] = null;
                return Step.FINISHED;
            }
            if (c.loop() && run.next == 0) { out[0] = new Run(c.signature(), now); return Step.RESTARTED; }
            return Step.MISSED;
        }
        if (role == run.next) { run.next++; return Step.CHECKPOINT; }
        return role < run.next ? Step.NONE : Step.WRONG_CHECKPOINT;
    }

    /** Record a finish. True when it is the player's new best. Times are kept for the current course only. */
    public static boolean recordTime(State s, String course, String player, long ms, int cap) {
        if (!course.equals(s.courseSig)) { s.parkourBest.clear(); s.courseSig = course; }
        Long old = s.parkourBest.get(player);
        if (old != null && old <= ms) return false;
        s.parkourBest.put(player, ms);
        if (cap > 0 && s.parkourBest.size() > cap) {
            // drop the slowest
            String worst = null;
            long w = Long.MIN_VALUE;
            for (Map.Entry<String, Long> e : s.parkourBest.entrySet()) if (e.getValue() > w) { w = e.getValue(); worst = e.getKey(); }
            if (worst != null) s.parkourBest.remove(worst);
        }
        return true;
    }

    /** True when the course changed since the times were recorded (they were then cleared). */
    public static boolean syncCourse(State s) {
        String sig = course(s).signature();
        if (sig.equals(s.courseSig)) return false;
        boolean had = !s.parkourBest.isEmpty();
        s.parkourBest.clear();
        s.courseSig = sig;
        return had;
    }

    /** Fastest first, at most {@code n}. */
    public static List<Map.Entry<String, Long>> leaderboard(Map<String, Long> best, int n) {
        List<Map.Entry<String, Long>> l = new ArrayList<>(best.entrySet());
        l.sort(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
        return l.size() > n ? new ArrayList<>(l.subList(0, Math.max(0, n))) : l;
    }

    /** Most visits first, at most {@code n}. */
    public static List<Map.Entry<String, Integer>> topVisitors(Map<String, Integer> visits, int n) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(visits.entrySet());
        l.sort((a, b) -> b.getValue().equals(a.getValue()) ? a.getKey().compareTo(b.getKey()) : Integer.compare(b.getValue(), a.getValue()));
        return l.size() > n ? new ArrayList<>(l.subList(0, Math.max(0, n))) : l;
    }

    /** "12.34s" / "1:02.50". */
    public static String time(long ms) {
        long cs = Math.max(0, ms) / 10;
        long m = cs / 6000, s = (cs / 100) % 60, c = cs % 100;
        return m > 0 ? String.format(Locale.ROOT, "%d:%02d.%02d", m, s, c) : String.format(Locale.ROOT, "%d.%02ds", s, c);
    }

    // =========================================================================================
    // egg hunt
    // =========================================================================================

    /** {found, total} for a guest, counting only eggs that are still placed. */
    public static int[] eggProgress(State s, String player) {
        List<Placed> eggs = of(s, EGG);
        Set<String> f = s.eggsFound.get(player);
        int found = 0;
        if (f != null) for (Placed e : eggs) if (f.contains(e.pos())) found++;
        return new int[]{found, eggs.size()};
    }

    /** Mark an egg found. False when this guest had already found it. */
    public static boolean findEgg(State s, String player, String pos, int playerCap) {
        Set<String> f = s.eggsFound.get(player);
        if (f == null) {
            if (playerCap > 0 && s.eggsFound.size() >= playerCap) return false;
            f = new LinkedHashSet<>();
            s.eggsFound.put(player, f);
        }
        return f.add(pos);
    }

    /** How many guests have found EVERY egg currently placed (0 when there are no eggs). */
    public static int completedHunts(State s) {
        int total = count(s, EGG);
        if (total == 0) return 0;
        int n = 0;
        for (String u : s.eggsFound.keySet()) if (eggProgress(s, u)[0] >= total) n++;
        return n;
    }

    // =========================================================================================
    // player-typed text (NPC names/lines, island names): public-server safe
    // =========================================================================================

    private static final Pattern CODES = Pattern.compile("(?i)[&§]([0-9a-fk-orx#])");
    private static final Pattern ALLOWED = Pattern.compile("[^\\p{L}\\p{N} .,!?'\\-:()]");

    /** Strip colour codes and anything that is not a letter, digit, space or plain punctuation; null when empty. */
    public static String cleanText(String raw, int max) {
        if (raw == null) return null;
        String t = CODES.matcher(raw).replaceAll("");
        t = ALLOWED.matcher(t).replaceAll("");
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > max) t = t.substring(0, Math.max(0, max)).trim();
        return t.isEmpty() ? null : t;
    }

    /** True when the text contains a word of the comma-separated blocklist (letters only, case-insensitive). */
    public static boolean blocked(String text, String blocklist) {
        if (text == null || blocklist == null || blocklist.isBlank()) return false;
        String flat = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        for (String w : blocklist.split("[,;]+")) {
            String b = w.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
            if (!b.isEmpty() && flat.contains(b)) return true;
        }
        return false;
    }

    /** Villager professions an Island NPC can wear (vanilla registry keys). */
    public static final List<String> LOOKS = List.of("none", "farmer", "fisherman", "shepherd", "fletcher", "librarian",
            "cartographer", "cleric", "armorer", "weaponsmith", "toolsmith", "butcher", "leatherworker", "mason", "nitwit");

    public static String nextLook(String look, int dir) {
        int i = LOOKS.indexOf(look == null ? "none" : look);
        if (i < 0) i = 0;
        return LOOKS.get(Math.floorMod(i + dir, LOOKS.size()));
    }
}
