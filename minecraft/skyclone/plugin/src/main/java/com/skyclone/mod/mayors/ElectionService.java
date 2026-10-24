package com.skyclone.mod.mayors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The election and the perk switchboard. Implements {@link Features.Mayors}: every other module asks
 * {@code perkActive("cole.mining_fiesta")} / {@code perkValue("aatrox.slayer_xp_buff")}.
 *
 * <p>State is DERIVED from the calendar every second: whatever term the date says governs, this
 * makes true (tallying the votes of the election that just closed, or rolling one if the server was
 * off through it). So a restart, a crash or {@code /sb calendar skip} can never desync the mayor.
 */
final class ElectionService implements Features.Mayors {

    static final int NONE = Integer.MIN_VALUE;

    /** Persisted as JSON under key "state". Add fields, never retype them. */
    static final class State {
        public int term = NONE;
        public String mayor;
        public List<String> mayorPerks = new ArrayList<>();
        public String minister;
        public String ministerPerk;
        public int election = NONE;
        public List<ElectionMath.Candidate> candidates = new ArrayList<>();
        public Map<String, Long> npcVotes = new LinkedHashMap<>();
        /** Long Game: this mayor runs again next election with every perk. */
        public String carry;
        public List<Result> history = new ArrayList<>();
        /** Admin overrides: perk id -> true (forced on) / false (forced off). */
        public Map<String, Boolean> forced = new LinkedHashMap<>();
        /** The last election tallied, so an admin "resolve" does not re-open it the next second. */
        public int lastResolved = NONE;
    }

    static final class Result {
        public int election;
        public String mayor;
        public String minister;
        public String ministerPerk;
        public List<String> perks = new ArrayList<>();
        public Map<String, Long> votes = new LinkedHashMap<>();
        public long at;
    }

    static final class Vote {
        final String candidate;
        final int weight;
        final String name;
        Vote(String candidate, int weight, String name) { this.candidate = candidate; this.weight = weight; this.name = name; }
    }

    private static final Gson GSON = new GsonBuilder().create();

    private final MayorRoster roster;
    private final MayorDials dials;
    private State state = new State();
    private final Map<UUID, Vote> votes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastVoteAt = new HashMap<>();
    private volatile Set<String> active = Set.of();
    private volatile String paradeMayor;
    private Map<String, Double> valueOverrides = Map.of();
    private String lastOverrideText = null;
    private final List<Runnable> perkListeners = new ArrayList<>();

    ElectionService(MayorRoster roster, MayorDials dials) {
        this.roster = roster;
        this.dials = dials;
    }

    MayorRoster roster() { return roster; }
    State state() { return state; }
    String paradeMayor() { return paradeMayor; }
    Set<String> activePerks() { return active; }
    void onPerksChanged(Runnable r) { perkListeners.add(r); }

    /** The festival Foxy's Extra Event schedules for the election that elected term {@code term}'s government. */
    String extraEventVariantForElection(int election) {
        return ElectionMath.extraEventVariant(dials.seed.get(), election, dials.extraEventVariant.get());
    }

    /** The Extra Event variant of the government in office now (fishing / mining / spooky). */
    String extraEventVariant() {
        return extraEventVariantForElection(state.term == NONE ? 0 : state.term - 1);
    }

    // ---- persistence --------------------------------------------------------------------------

    void load() {
        String js = MayorsDb.readKey("state");
        if (js != null) {
            try {
                State s = GSON.fromJson(js, State.class);
                if (s != null) state = s;
            } catch (Exception e) {
                Sky.log().warning("[mayors] stored election state unreadable, starting fresh (old JSON kept in log): " + js);
            }
        }
        if (state.candidates == null) state.candidates = new ArrayList<>();
        if (state.history == null) state.history = new ArrayList<>();
        if (state.forced == null) state.forced = new LinkedHashMap<>();
        if (state.npcVotes == null) state.npcVotes = new LinkedHashMap<>();
        if (state.mayorPerks == null) state.mayorPerks = new ArrayList<>();
        if (state.election != NONE) loadVotes(state.election);
    }

    private void save() { MayorsDb.writeKey("state", GSON.toJson(state)); }

    private void loadVotes(int election) {
        votes.clear();
        try {
            Map<UUID, Vote> rows = Sky.db().query(c -> {
                Map<UUID, Vote> out = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid, candidate, weight, name FROM mayors_votes WHERE election = ?")) {
                    ps.setInt(1, election);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try { out.put(UUID.fromString(rs.getString(1)), new Vote(rs.getString(2), rs.getInt(3), rs.getString(4))); }
                            catch (IllegalArgumentException ignored) { }
                        }
                    }
                }
                return out;
            }).join();
            votes.putAll(rows);
        } catch (Exception e) {
            Sky.log().warning("[mayors] could not load votes for election " + election + ": " + e);
        }
    }

    // ---- the clock ------------------------------------------------------------------------------

    /** Main thread, every second. */
    void tick() {
        SkyDate d = Sky.calendar().now();
        boolean changed = false;
        int term = ElectionMath.termOf(d.year(), d.month(), d.day());
        int open = ElectionMath.openElection(d.year(), d.month(), d.day());
        if (state.term == NONE) {
            install(term, true);
            changed = true;
        } else if (state.term != term) {
            install(term, term < state.term);
            changed = true;
        }
        if (open >= 0 && state.election != open && open != state.lastResolved) {
            openElection(open, true);
            changed = true;
        }
        if (changed) save();
        recompute(d);
    }

    /** Make {@code term}'s mayor govern: tally the election that elected them (or roll it). */
    private void install(int term, boolean silent) {
        int e = term - 1;
        List<ElectionMath.Candidate> cands;
        Map<String, Long> tally;
        boolean voted = state.election == e && !state.candidates.isEmpty();
        if (voted) {
            cands = state.candidates;
            tally = tally();
        } else {
            cands = ElectionMath.rollCandidates(roster, e, dials.seed.get(), exclusions(), state.carry,
                    dials.specialEvery.get(), dials.includeRetired.get());
            tally = ElectionMath.npcVotes(cands, Math.max(1, dials.npcVotes.get()), dials.seed.get(), e);
        }
        if (cands.isEmpty()) {
            Sky.log().warning("[mayors] no candidates could be rolled for election " + e + "; is mayors.json empty?");
            state.term = term;
            return;
        }
        List<String> ids = new ArrayList<>();
        for (ElectionMath.Candidate c : cands) ids.add(c.mayor);
        List<String> order = ElectionMath.rank(tally, ids, dials.seed.get(), e);
        ElectionMath.Candidate win = find(cands, order.get(0));
        ElectionMath.Candidate second = order.size() > 1 ? find(cands, order.get(1)) : null;

        state.mayor = win.mayor;
        state.mayorPerks = new ArrayList<>(win.perks);
        if (win.special || second == null) {
            state.minister = null;
            state.ministerPerk = null;
        } else {
            state.minister = second.mayor;
            state.ministerPerk = second.ministerPerk;
        }
        boolean lti = state.mayorPerks.contains("diaz.long_term_investment") || dials.mayorAllPerks.get() && "diaz".equals(win.mayor);
        state.carry = lti && state.minister != null ? state.minister : null;

        Result r = new Result();
        r.election = e;
        r.mayor = state.mayor;
        r.minister = state.minister;
        r.ministerPerk = state.ministerPerk;
        r.perks = new ArrayList<>(state.mayorPerks);
        r.votes = new LinkedHashMap<>();
        for (String id : order) r.votes.put(id, tally.getOrDefault(id, 0L));
        r.at = System.currentTimeMillis();
        state.history.add(0, r);
        while (state.history.size() > 12) state.history.remove(state.history.size() - 1);

        Map<UUID, Vote> voters = new HashMap<>(votes);
        state.term = term;
        state.lastResolved = e;
        state.election = NONE;
        state.candidates = new ArrayList<>();
        state.npcVotes = new LinkedHashMap<>();
        votes.clear();

        if (!silent) {
            announce(r);
            if ("scorpius".equals(win.mayor) && win.perks.contains("scorpius.bribe") && voted) payBribes(voters);
        }
        Sky.log().info("[mayors] term " + term + ": mayor " + state.mayor + " " + state.mayorPerks
                + ", minister " + state.minister + " (" + state.ministerPerk + ")" + (voted ? "" : " [rolled: no ballot was open]"));
    }

    private Set<String> exclusions() {
        Set<String> ex = new HashSet<>();
        if (state.mayor != null) ex.add(state.mayor);
        if (state.minister != null && !state.minister.equals(state.carry)) ex.add(state.minister);
        return ex;
    }

    private void openElection(int e, boolean announce) {
        state.candidates = ElectionMath.rollCandidates(roster, e, dials.seed.get(), exclusions(), state.carry,
                dials.specialEvery.get(), dials.includeRetired.get());
        state.npcVotes = ElectionMath.npcVotes(state.candidates, Math.max(0, dials.npcVotes.get()), dials.seed.get(), e);
        state.election = e;
        loadVotes(e);
        if (announce && !state.candidates.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!EventAlerts.news(p, Cal.ELECTION)) continue;
                Msg.send(p, "&6&lELECTION!&eThe Election Booth is open for the &aYear " + (e + 1) + " &eterm!");
                Msg.send(p, "&7Five candidates are running. Cast your vote with &e/election&7.");
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.4f);
            }
        }
        Sky.log().info("[mayors] election " + e + " opened: " + candidateIds());
    }

    private List<String> candidateIds() {
        List<String> ids = new ArrayList<>();
        for (ElectionMath.Candidate c : state.candidates) ids.add(c.mayor);
        return ids;
    }

    private static ElectionMath.Candidate find(List<ElectionMath.Candidate> cands, String id) {
        for (ElectionMath.Candidate c : cands) if (c.mayor.equals(id)) return c;
        return cands.get(0);
    }

    /** Votes per candidate for the open election: townsfolk + every ballot's weight. */
    Map<String, Long> tally() {
        Map<String, Long> t = new LinkedHashMap<>();
        for (ElectionMath.Candidate c : state.candidates) t.put(c.mayor, state.npcVotes.getOrDefault(c.mayor, 0L));
        for (Vote v : votes.values()) if (t.containsKey(v.candidate)) t.merge(v.candidate, (long) v.weight, Long::sum);
        return t;
    }

    long totalVotes() {
        long s = 0;
        for (long v : tally().values()) s += v;
        return s;
    }

    private void announce(Result r) {
        MayorRoster.Mayor m = roster.mayor(r.mayor);
        MayorRoster.Mayor min = roster.mayor(r.minister);
        long total = 0;
        for (long v : r.votes.values()) total += v;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!EventAlerts.news(p, Cal.TERM_START)) continue;
            List<String> body = new ArrayList<>();
            body.add("&7The people have spoken! " + (m == null ? r.mayor : m.display()) + " &7is your new &6Mayor&7!");
            for (String id : r.perks) {
                MayorRoster.Perk pk = roster.perk(id);
                if (pk != null) body.add("  &8▸ &e" + pk.name);
            }
            if (min != null) {
                MayorRoster.Perk mp = roster.perk(r.ministerPerk);
                body.add("&7Minister: " + min.display() + (mp == null ? "" : " &8(&e" + mp.name + "&8)"));
            }
            if (total > 0 && m != null) body.add("&7Won with &a" + pct(r.votes.getOrDefault(r.mayor, 0L), total) + " &7of &e" + Text.commasInt(total) + " &7votes.");
            Msg.box(p, "&6&lELECTION RESULTS &8- &eYear " + (r.election + 1), body);
            Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        }
    }

    static String pct(long part, long total) {
        if (total <= 0) return "0%";
        return String.format(Locale.US, "%.1f%%", part * 100.0 / total);
    }

    /** Kickback: everyone who voted for the winner is paid by SkyBlock level (research: 50k - 1M). */
    private void payBribes(Map<UUID, Vote> voters) {
        for (Map.Entry<UUID, Vote> e : voters.entrySet()) {
            if (!"scorpius".equals(e.getValue().candidate)) continue;
            Player p = Bukkit.getPlayer(e.getKey());
            int level = 0;
            if (p != null) {
                try { level = Sky.service(Features.SkyLevel.class).level(p); } catch (Throwable ignored) { }
            }
            double amount = Math.min(dials.bribeMax.get(), dials.bribeMin.get() + level * dials.bribePerLevel.get());
            Sky.economy().depositOffline(e.getKey(), amount, "mayors: kickback for voting");
            if (p != null) Msg.success(p, "A mysterious envelope arrives... &6+" + Text.commasInt(amount) + " coins&a!");
        }
    }

    // ---- perks ------------------------------------------------------------------------------------

    private void recompute(SkyDate d) {
        Set<String> s = new LinkedHashSet<>();
        MayorRoster.Mayor m = roster.mayor(state.mayor);
        if (m != null) {
            if (m.special || dials.mayorAllPerks.get()) s.addAll(m.perkIds());
            else s.addAll(state.mayorPerks);
        }
        if (state.ministerPerk != null) {
            String c = roster.canonical(state.ministerPerk);
            if (c != null) s.add(c);
        }
        String parade = null;
        if (s.contains("jerry.perkpocalypse")) {
            parade = ElectionMath.perkpocalypseMayor(roster, state.term,
                    ElectionMath.daysIntoTerm(d.year(), d.month(), d.day()), Math.max(1, dials.paradeDays.get()), dials.seed.get());
            MayorRoster.Mayor pm = roster.mayor(parade);
            if (pm != null) s.addAll(pm.perkIds());
        }
        for (Map.Entry<String, Boolean> f : state.forced.entrySet()) {
            String c = roster.canonical(f.getKey());
            if (c == null) continue;
            if (f.getValue()) s.add(c); else s.remove(c);
        }
        for (String off : dials.disabledPerks.get().split(",")) {
            String c = roster.canonical(off.trim());
            if (c != null) s.remove(c);
        }
        String oldParade = paradeMayor;
        paradeMayor = parade;
        if (!s.equals(active)) {
            active = Collections.unmodifiableSet(s);
            for (Runnable r : perkListeners) {
                try { r.run(); } catch (Throwable t) { Sky.log().warning("[mayors] perk listener threw: " + t); }
            }
            if (parade != null && oldParade != null && !parade.equals(oldParade)) {
                MayorRoster.Mayor pm = roster.mayor(parade);
                if (pm != null) Msg.broadcast("&a&lPERK PARADE! &7Every perk of " + pm.display() + " &7is now active!");
            }
        }
    }

    @Override
    public boolean perkActive(String perkId) {
        String c = roster.canonical(perkId);
        return c != null && active.contains(c);
    }

    @Override
    public String mayorName() {
        MayorRoster.Mayor m = roster.mayor(state.mayor);
        return m == null ? "" : m.display();
    }

    @Override
    public double perkValue(String perkId) {
        String c = roster.canonical(perkId);
        if (c == null || !active.contains(c)) return 0;
        return baseValue(c);
    }

    /** The perk's number whether or not it is active (overrides applied). */
    double baseValue(String canonical) {
        String txt = dials.perkValues.get();
        if (!txt.equals(lastOverrideText)) {
            Map<String, Double> m = new HashMap<>();
            for (String part : txt.split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String id = roster.canonical(part.substring(0, eq).trim());
                try { if (id != null) m.put(id, Double.parseDouble(part.substring(eq + 1).trim())); } catch (NumberFormatException ignored) { }
            }
            valueOverrides = m;
            lastOverrideText = txt;
        }
        Double o = valueOverrides.get(canonical);
        if (o != null) return o;
        MayorRoster.Perk p = roster.perk(canonical);
        return p == null ? 0 : p.value;
    }

    // ---- voting -----------------------------------------------------------------------------------

    boolean votingOpen() { return state.election != NONE && !state.candidates.isEmpty(); }

    String voteOf(UUID uuid) { Vote v = votes.get(uuid); return v == null ? null : v.candidate; }

    int voteWeight(Player p) {
        int w = Math.max(1, dials.voteWeight.get());
        if (dials.fameVotes.get()) w *= FameVotes.votes(p);
        // Voter's Badge line (accessories, hook mayors): +3 / +6 / +9 / +12 votes; only the best one counts.
        try {
            Features.Accessories acc = Sky.service(Features.Accessories.class);
            w += badgeVotes(id -> acc.has(p, id));
            if (acc.has(p, "ARTIFACT_OF_CONTROL")) w *= 2;
        } catch (Throwable ignored) { }
        return w;
    }

    /** Extra votes from the best Voter's Badge carried (pure; {@code has} answers "is this accessory active"). */
    static int badgeVotes(java.util.function.Predicate<String> has) {
        String[][] badges = {{"VOTER_BADGE_SUPREME", "12"}, {"VOTER_BADGE_ELITE", "9"}, {"VOTER_BADGE_VIP", "6"}, {"VOTER_BADGE", "3"}};
        for (String[] b : badges) if (has.test(b[0])) return Integer.parseInt(b[1]);
        return 0;
    }

    /** Cast or change a ballot. Validates everything server-side. */
    boolean vote(Player p, String mayorId) {
        if (!votingOpen()) { Msg.error(p, "The Election Booth is closed right now."); return false; }
        ElectionMath.Candidate cand = null;
        for (ElectionMath.Candidate c : state.candidates) if (c.mayor.equals(mayorId)) cand = c;
        if (cand == null) { Msg.error(p, "That candidate is not on this year's ballot."); return false; }
        String req = dials.voteRequirement.get();
        if (req != null && !req.isBlank() && !"always".equals(req) && !Sky.requirements().checkOrTell(p, req)) return false;
        long now = System.currentTimeMillis();
        Long last = lastVoteAt.get(p.getUniqueId());
        if (last != null && now - last < 2000) { Msg.error(p, "Slow down! You can change your vote again in a moment."); return false; }
        lastVoteAt.put(p.getUniqueId(), now);
        Vote prev = votes.get(p.getUniqueId());
        if (prev != null && prev.candidate.equals(mayorId)) { Msg.send(p, "&eYou already voted for this candidate."); return false; }
        int weight = voteWeight(p);
        votes.put(p.getUniqueId(), new Vote(mayorId, weight, p.getName()));
        int election = state.election;
        String uuid = p.getUniqueId().toString(), name = p.getName();
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO mayors_votes(election, uuid, name, candidate, weight, at) VALUES (?,?,?,?,?,?) "
                    + "ON CONFLICT(election, uuid) DO UPDATE SET candidate = excluded.candidate, weight = excluded.weight, name = excluded.name, at = excluded.at")) {
                ps.setInt(1, election);
                ps.setString(2, uuid);
                ps.setString(3, name);
                ps.setString(4, mayorId);
                ps.setInt(5, weight);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
        MayorRoster.Mayor m = roster.mayor(mayorId);
        String who = m == null ? mayorId : m.display();
        Msg.success(p, (prev == null ? "You voted for " : "You changed your vote to ") + who
                + "&a! &7(" + weight + " vote" + (weight == 1 ? "" : "s") + ")");
        Msg.send(p, "&7You can change it while the booth is open: &e/election&7.");
        try { Sky.vitals().flash(p, "&aVote cast for " + who + "&a!", 60); } catch (Throwable ignored) { }
        return true;
    }

    // ---- admin ----------------------------------------------------------------------------------

    /** Install a mayor now (all their perks) and optionally a minister (their first eligible perk). */
    String adminSet(String mayorId, String ministerId) {
        MayorRoster.Mayor m = roster.mayor(mayorId);
        if (m == null) return "unknown mayor " + mayorId;
        MayorRoster.Mayor min = ministerId == null ? null : roster.mayor(ministerId);
        if (ministerId != null && min == null) return "unknown minister " + ministerId;
        state.mayor = m.id;
        state.mayorPerks = m.perkIds();
        state.minister = min == null ? null : min.id;
        state.ministerPerk = null;
        if (min != null) for (MayorRoster.Perk p : min.perks) if (p.ministerEligible) { state.ministerPerk = p.id; break; }
        state.carry = state.mayorPerks.contains("diaz.long_term_investment") && state.minister != null ? state.minister : null;
        save();
        recompute(Sky.calendar().now());
        return null;
    }

    /** Close the open election right now and install its winner for the current term. */
    String adminResolve() {
        if (!votingOpen()) return "no election is open";
        int term = state.election + 1;
        int keep = state.term;
        install(term, false);
        state.term = keep;   // the calendar still decides when the NEXT term starts
        save();
        recompute(Sky.calendar().now());
        return null;
    }

    /** Roll a fresh ballot for the open election (or open one now, outside the window, for testing). */
    String adminReroll(boolean force) {
        SkyDate d = Sky.calendar().now();
        int open = ElectionMath.openElection(d.year(), d.month(), d.day());
        if (open < 0) {
            if (!force) return "the booth is closed (use 'election open' to open one anyway)";
            open = d.year();
        }
        openElection(open, true);
        save();
        return null;
    }

    void adminForce(String perkId, Boolean on) {
        String c = roster.canonical(perkId);
        if (c == null) return;
        if (on == null) state.forced.remove(c); else state.forced.put(c, on);
        save();
        recompute(Sky.calendar().now());
    }

    void adminVote(UUID uuid, String name, String candidate, int weight) {
        votes.put(uuid, new Vote(candidate, weight, name));
    }

    List<String> status() {
        List<String> out = new ArrayList<>();
        SkyDate d = Sky.calendar().now();
        out.add("&eDate: &f" + d.dayLabel() + ", Year " + d.year() + " &7(term " + state.term + ")");
        MayorRoster.Mayor m = roster.mayor(state.mayor);
        out.add("&eMayor: " + (m == null ? "&cnone" : m.display() + " &7" + state.mayorPerks));
        MayorRoster.Mayor min = roster.mayor(state.minister);
        out.add("&eMinister: " + (min == null ? "&7none" : min.display() + " &7(" + state.ministerPerk + ")"));
        if (paradeMayor != null) out.add("&ePerk Parade: &f" + paradeMayor);
        out.add("&eActive perks: &f" + String.join(", ", active));
        if (!state.forced.isEmpty()) out.add("&eForced: &f" + state.forced);
        if (votingOpen()) {
            Map<String, Long> t = tally();
            out.add("&eElection " + state.election + " open, closes in " + ElectionMath.daysUntilClose(d.year(), d.month(), d.day()) + " SB days; "
                    + votes.size() + " ballot(s): &f" + t);
        } else {
            out.add("&eBooth closed; opens in " + ElectionMath.daysUntilOpen(d.year(), d.month(), d.day()) + " SB days");
        }
        if (state.carry != null) out.add("&eLong Game carry: &f" + state.carry);
        return out;
    }
}
