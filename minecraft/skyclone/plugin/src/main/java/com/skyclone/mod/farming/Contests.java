package com.skyclone.mod.farming;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.logging.Level;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Harvest Fair (our name for the reference's farming contest): every hour, three crops, twenty minutes,
 * ranked against every real participant plus a field of NPC "ghost" farmers so it works on a small server.
 */
final class Contests implements Listener {

    static final String EVENT_ID = "farming_contest";

    private final FarmingModule m;

    boolean active;
    long index = Long.MIN_VALUE;
    long startMillis, endMillis;
    boolean forced;
    List<Crop> crops = List.of();
    private long lastIndex = Long.MIN_VALUE;
    private final Map<Crop, double[]> ghostFinals = new EnumMap<>(Crop.class);
    private final Map<Crop, double[]> ghostNow = new EnumMap<>(Crop.class);
    /** Each ghost's pace through this fair (index-aligned with ghostFinals). */
    private final Map<Crop, ContestMath.GhostPace[]> ghostPaces = new EnumMap<>(Crop.class);
    /** Every participant this contest (online or not): uuid -> entry. */
    private final Map<UUID, Entry> field = new HashMap<>();
    private final Map<UUID, Long> notified = new HashMap<>();

    static final class Entry {
        UUID uuid; String name; Crop crop; long score;
    }

    Contests(FarmingModule m) { this.m = m; }

    void start() {
        CalendarEvent ev = CalendarEvent.realtime(EVENT_ID, "Harvest Fair", m.dials.contestPeriod.get(),
                m.dials.contestOffset.get(), m.dials.contestDuration.get());
        Sky.calendar().register(ev);
        file = new File(Sky.plugin().getDataFolder(), "farming-contest.json");
        load();
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 20L);
    }

    /** Shutdown: remember how far the running fair got, so a restart resumes it or ranks it where it stood. */
    void stop() {
        if (active && saved.live != null) saved.live.reached = ContestMath.progress(System.currentTimeMillis(), startMillis, endMillis);
        write(file, GSON.toJson(saved));
    }

    // ---- persistence (farming-contest.json) ---------------------------------------------------------

    /**
     * What survives a restart: the fair that was running (so a FORCED fair resumes, and any fair whose window
     * passed while the server was down is ranked where it stood when the server went down), and the progress at
     * which recent fairs stopped (so {@link #finishLate} ranks a late player against the ghosts as they stood at
     * the end, not against their 20-minute finals).
     */
    static final class Saved {
        Fair live;
        Map<String, Double> stops = new LinkedHashMap<>();
        /** All-time Hall of Fame: crop -> best real scores, one per player, best first. */
        Map<String, List<Fame>> fame = new LinkedHashMap<>();
    }

    static final class Fame {
        String uuid, name, bracket;
        long score, start;
    }

    static final int FAME_SIZE = 10;

    /** The all-time Hall of Fame of a crop (real players only), best first. */
    List<Fame> hallOfFame(Crop c) {
        List<Fame> l = saved.fame.get(c.name());
        return l == null ? List.of() : List.copyOf(l);
    }

    /** Offers a finished score to the Hall of Fame; keeps each player's best and the top {@link #FAME_SIZE}. */
    private void offerFame(Entry en, long start, String bracket) {
        List<Fame> l = saved.fame.computeIfAbsent(en.crop.name(), k -> new ArrayList<>());
        String id = en.uuid.toString();
        for (Fame f : l) if (id.equals(f.uuid)) {
            if (f.score >= en.score) return;
            l.remove(f);
            break;
        }
        Fame f = new Fame();
        f.uuid = id; f.name = en.name; f.score = en.score; f.start = start; f.bracket = bracket;
        l.add(f);
        l.sort((a, b) -> Long.compare(b.score, a.score));
        while (l.size() > FAME_SIZE) l.remove(l.size() - 1);
    }

    static final class Fair {
        long index, start, end;
        boolean forced;
        List<String> crops = new ArrayList<>();
        double reached;
    }

    private static final Gson GSON = new Gson();
    private File file;
    private Saved saved = new Saved();
    private int saveTicks;

    private void load() {
        if (file == null || !file.isFile()) return;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            Saved s = GSON.fromJson(r, Saved.class);
            if (s != null) saved = s;
            if (saved.stops == null) saved.stops = new LinkedHashMap<>();
            if (saved.fame == null) saved.fame = new LinkedHashMap<>();
        } catch (Exception e) {
            Sky.log().log(Level.WARNING, "[farming] could not read " + file.getName(), e);
            return;
        }
        Fair f = saved.live;
        if (f == null) return;
        long now = System.currentTimeMillis();
        List<Crop> cs = new ArrayList<>();
        if (f.crops != null) for (String c : f.crops) { Crop x = Crop.parse(c); if (x != null) cs.add(x); }
        if (now < f.end && !cs.isEmpty()) {
            if (f.forced) {
                // a forced fair is not on the schedule, so nothing else would bring it back
                Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                    if (!active) {
                        begin(f.index, cs, f.start, f.end, true);
                        Sky.log().info("[farming] resumed forced Harvest Fair #" + f.index + " after a restart");
                    }
                });
            }
            // a scheduled one in its window is picked up by tick() with the same index, start and end
            return;
        }
        // its window passed while the server was down: nobody could farm after the last save, ghosts included
        recordStop(f.index, Math.max(0, Math.min(1, f.reached)));
        saved.live = null;
        saveAsync();
        Sky.log().info("[farming] Harvest Fair #" + f.index + " ended while the server was down (at "
                + Math.round(f.reached * 100) + "%); late participants are ranked where the field stood");
    }

    private void recordStop(long idx, double progress) {
        saved.stops.put(Long.toString(idx), progress);
        while (saved.stops.size() > 50) saved.stops.remove(saved.stops.keySet().iterator().next());
    }

    /** How far contest {@code idx} got before it stopped (1 = ran to the end, or unknown). */
    double stoppedAt(long idx) {
        Double d = saved.stops.get(Long.toString(idx));
        return d == null ? 1 : Math.max(0, Math.min(1, d));
    }

    private void saveAsync() {
        String json = GSON.toJson(saved);
        File f = file;
        Bukkit.getScheduler().runTaskAsynchronously(Sky.plugin(), () -> write(f, json));
    }

    private static synchronized void write(File f, String json) {
        if (f == null) return;
        try {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) { w.write(json); }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Sky.log().log(Level.WARNING, "[farming] could not save Harvest Fair state", e);
        }
    }

    long period() { return Math.max(5, m.dials.contestPeriod.get()) * 60_000L; }
    long offset() { return Math.floorMod(m.dials.contestOffset.get(), Math.max(5, m.dials.contestPeriod.get())) * 60_000L; }
    long duration() { return Math.max(1, Math.min(m.dials.contestDuration.get(), Math.max(5, m.dials.contestPeriod.get()) - 1)) * 60_000L; }

    List<Crop> eligible() { return Crop.contestCrops(m.dials.contestFlowers.get()); }

    // ---- lifecycle ---------------------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        if (active) {
            if (now >= endMillis) { end(); return; }
            refreshGhosts(now);
            if (++saveTicks >= 30 && saved.live != null) {          // every 30 s: how far the fair has got
                saveTicks = 0;
                saved.live.reached = ContestMath.progress(now, startMillis, endMillis);
                saveAsync();
            }
            return;
        }
        if (!m.dials.contestEnabled.get()) return;
        if (ContestMath.active(now, period(), offset(), duration())) {
            long idx = ContestMath.index(now, period(), offset());
            if (idx != lastIndex) begin(idx, ContestMath.crops(idx, eligible()), ContestMath.startOf(idx, period(), offset()),
                    ContestMath.startOf(idx, period(), offset()) + duration(), false);
        }
    }

    /** Admin / calendar force: start now with these crops (null = the scheduled pick for a fresh index). */
    void forceStart(List<Crop> chosen) {
        if (active) end();
        long now = System.currentTimeMillis();
        long idx = -(now / 1000);                      // unique, never collides with a scheduled index
        List<Crop> cs = chosen != null && !chosen.isEmpty() ? chosen : ContestMath.crops(idx, eligible());
        begin(idx, cs, now, now + duration(), true);
    }

    void forceEnd() { if (active) end(); }

    private void begin(long idx, List<Crop> cs, long start, long end, boolean force) {
        active = true; index = idx; lastIndex = idx; startMillis = start; endMillis = end; forced = force;
        crops = List.copyOf(cs);
        field.clear(); notified.clear(); ghostFinals.clear(); ghostNow.clear(); ghostPaces.clear();
        for (Crop c : crops) {
            int n = Math.max(0, m.dials.contestGhosts.get());
            ghostFinals.put(c, ContestMath.ghostFinals(idx, c, n, m.dials.contestGhostMedian.get(), m.dials.contestGhostSigma.get()));
            ghostPaces.put(c, ContestMath.ghostPaces(idx, c, n));
        }
        refreshGhosts(System.currentTimeMillis());
        Fair f = new Fair();
        f.index = idx; f.start = start; f.end = end; f.forced = force;
        for (Crop c : crops) f.crops.add(c.name());
        f.reached = ContestMath.progress(System.currentTimeMillis(), start, end);
        saved.live = f;
        saveAsync();
        // players who were already in this contest before a restart keep their score
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            FarmData d = m.data(p);
            if (d.contestIndex == idx && d.contestCrop != null) track(p, Crop.parse(d.contestCrop), d.contestScore);
        }
        String list = cropList();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!eligibleLevel(p) || !wantsEventChat(p)) continue;
            Msg.send(p, "&e[NPC] " + FarmingModule.HOST_NAME + "&f: &bThe Harvest Fair has begun!");
            Msg.send(p, "&eCrops: " + list + " &7(" + Text.duration((end - System.currentTimeMillis()) / 1000) + ")");
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        }
        Sky.log().info("[farming] Harvest Fair #" + idx + " started: " + crops);
    }

    String cropList() {
        StringBuilder sb = new StringBuilder();
        for (Crop c : crops) { if (sb.length() > 0) sb.append("&e, "); sb.append("&a").append(c.display); }
        return sb.toString();
    }

    private void refreshGhosts(long now) {
        double t = ContestMath.progress(now, startMillis, endMillis);
        for (Map.Entry<Crop, double[]> e : ghostFinals.entrySet()) ghostNow.put(e.getKey(), ContestMath.ghostsAt(e.getValue(), ghostPaces.get(e.getKey()), t));
    }

    private void end() {
        if (!active) return;
        active = false;
        long idx = index;
        boolean goated = goated();
        int min = Math.max(1, m.dials.contestMinScore.get());
        // The ghosts stop farming when the fair stops, like everyone else. A natural end is t = 1 (their final
        // scores); an early end (admin stop, calendar force-off, a new force) ranks them where they stood, which is
        // what the live sidebar was showing. Ranking a 6-minute score against 20-minute ghosts turned a live
        // "BRONZE #23/41" into "#37 of 41, no medal" (playtest-loops).
        double progress = ContestMath.progress(System.currentTimeMillis(), startMillis, endMillis);
        boolean early = progress < 1;
        recordStop(idx, progress);
        saved.live = null;
        saveAsync();
        // live field from online data first (authoritative), then everyone who left mid-contest
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            FarmData d = m.data(p);
            if (d.contestIndex == idx && d.contestCrop != null) track(p, Crop.parse(d.contestCrop), d.contestScore);
        }
        int ranked = 0;
        for (Entry en : new ArrayList<>(field.values())) {
            if (en.crop == null) continue;
            double[] ghosts = ContestMath.ghostsAt(ghostFinals.getOrDefault(en.crop, new double[0]), ghostPaces.get(en.crop), progress);
            List<Double> others = new ArrayList<>();
            for (double g : ghosts) others.add(g);
            for (Entry o : field.values()) if (o != en && o.crop == en.crop && o.score >= min) others.add((double) o.score);
            double[] arr = others.stream().mapToDouble(Double::doubleValue).toArray();
            FarmData.ContestRecord r = new FarmData.ContestRecord();
            r.index = idx; r.start = startMillis; r.crop = en.crop.name(); r.score = en.score;
            r.participants = arr.length + 1;
            if (en.score >= min) {
                r.position = ContestMath.position(en.score, arr);
                r.bracket = ContestMath.bracket(r.position, r.participants, goated).name();
                ranked++;
                offerFame(en, startMillis, r.bracket);
            } else {
                r.position = 0;
                r.bracket = null;          // too few crops: no rewards
            }
            Player online = Bukkit.getPlayer(en.uuid);
            if (online != null && Sky.profiles().isReady(online)) {
                FarmData d = m.data(online);
                addRecord(d, r);
                d.contestCrop = null; d.contestScore = 0;
                summary(online, r, early ? progress : 1);
                if (m.dials.contestAutoClaim.get()) claimAll(online);
                else if (r.bracket != null) Msg.send(online, "&eClaim your rewards from &6" + FarmingModule.HOST_NAME + "&e (/harvestfair).");
            } else {
                Sky.profiles().modifyOffline(en.uuid, FarmingModule.KEY, FarmData.class, d -> {
                    addRecord(d, r);
                    if (d.contestIndex == idx) { d.contestCrop = null; d.contestScore = 0; }
                });
            }
        }
        for (Player p : Bukkit.getOnlinePlayers())
            if (eligibleLevel(p) && !field.containsKey(p.getUniqueId()) && wantsEventChat(p))
                Msg.send(p, "&e[NPC] " + FarmingModule.HOST_NAME + "&f: &bThe Harvest Fair is over! &7Next one in &a"
                        + Text.duration(ContestMath.untilNext(System.currentTimeMillis(), period(), offset(), duration()) / 1000) + "&7.");
        Sky.log().info("[farming] Harvest Fair #" + idx + " ended" + (early ? " early at " + Math.round(progress * 100) + "%" : "")
                + ": " + field.size() + " participants, " + ranked + " ranked");
        saveAsync();                                         // the Hall of Fame entries just offered
        field.clear();
        ghostNow.clear();
    }

    private void addRecord(FarmData d, FarmData.ContestRecord r) {
        for (FarmData.ContestRecord o : d.history) if (o.index == r.index) return;     // never twice
        d.history.add(r);
        int keep = Math.max(5, m.dials.contestHistory.get());
        while (d.history.size() > keep) {
            // drop the oldest CLAIMED record first; never drop an unpaid one
            int drop = -1;
            for (int i = 0; i < d.history.size(); i++) if (d.history.get(i).claimed || d.history.get(i).bracket == null) { drop = i; break; }
            if (drop < 0) break;
            d.history.remove(drop);
        }
    }

    private void summary(Player p, FarmData.ContestRecord r, double progress) {
        Crop c = Crop.parse(r.crop);
        List<String> body = new ArrayList<>();
        body.add("&7Crop: &a" + (c == null ? r.crop : c.display) + "   &7Collected: &e" + Text.commasInt(r.score));
        if (progress < 1)
            body.add("&7The fair was ended early (&e" + Math.round(progress * 100) + "%&7 through): everyone was ranked where they stood.");
        if (r.bracket == null) body.add("&cYou needed at least " + m.dials.contestMinScore.get() + " to place.");
        else {
            ContestMath.Bracket b = ContestMath.Bracket.valueOf(r.bracket);
            body.add("&7Placement: &e#" + r.position + " &7of &e" + r.participants + "   " + (b == ContestMath.Bracket.NONE ? "&7No medal" : b.colored()));
        }
        Msg.box(p, "&6&lHARVEST FAIR RESULTS", body);
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
    }

    /** Pay every unclaimed record. Returns how many were paid. */
    int claimAll(Player p) {
        FarmData d = m.data(p);
        int n = 0;
        for (FarmData.ContestRecord r : d.history) {
            if (r.claimed) continue;
            r.claimed = true;
            if (r.bracket == null) continue;
            pay(p, d, r);
            n++;
        }
        if (n > 0) Sky.profiles().saveNow(p);
        return n;
    }

    private void pay(Player p, FarmData d, FarmData.ContestRecord r) {
        ContestMath.Bracket b = ContestMath.Bracket.parse(r.bracket);
        Crop c = Crop.parse(r.crop);
        List<String> lines = new ArrayList<>();
        int tickets = b == ContestMath.Bracket.NONE ? ContestMath.participationTickets(r.score) : b.tickets;
        if (b != ContestMath.Bracket.NONE) {
            d.bronze += b.bronze(); d.silver += b.silver(); d.gold += b.gold();
            d.medalsEarned.merge(b.name(), 1, Integer::sum);
            if (b.gold() > 0) lines.add("&6+" + b.gold() + " Gold Medal");
            if (b.silver() > 0) lines.add("&f+" + b.silver() + " Silver Medal");
            if (b.bronze() > 0) lines.add("&c+" + b.bronze() + " Bronze Medal");
            // Masked Hopper's Cape (research 06: "20% chance to obtain an extra medal from contests") - wave 5
            if (Perks.wears(p, "ZORROS_CAPE") && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < m.dials.zorroChance.get()) {
                String extra = PerkMath.bonusMedal(b.gold(), b.silver(), b.bronze());
                if ("gold".equals(extra)) { d.gold++; lines.add("&6+1 Gold Medal &a(Cape bonus!)"); }
                else if ("silver".equals(extra)) { d.silver++; lines.add("&f+1 Silver Medal &a(Cape bonus!)"); }
                else if ("bronze".equals(extra)) { d.bronze++; lines.add("&c+1 Bronze Medal &a(Cape bonus!)"); }
            }
            if (m.dials.contestBits.get() && b.bits > 0) { Sky.economy().addBits(p, b.bits, "farming: harvest fair " + b.name()); lines.add("&b+" + b.bits + " Bits"); }
            if (b.carnivalTickets > 0 && Sky.items().exists("CARNIVAL_TICKET")) {
                Sky.loot().give(p, "CARNIVAL_TICKET", b.carnivalTickets, LootSource.REWARD);
                lines.add("&a+" + b.carnivalTickets + " Carnival Ticket");
            }
            if (c != null) {
                ContestMath.Bracket best = ContestMath.Bracket.parse(d.bestBracket.get(c.name()));
                if (b.ordinal() > best.ordinal()) d.bestBracket.put(c.name(), b.name());
                if (b.goldOrBetter() && d.uniqueGolds.add(c.name()))
                    lines.add("&6&lNEW UNIQUE GOLD! &7(" + d.uniqueGolds.size() + " crops) - raise your Farming cap at Harvesta.");
            }
        }
        if (Sky.items().exists("JACOBS_TICKET")) {
            Sky.loot().give(p, "JACOBS_TICKET", tickets, LootSource.REWARD);
            lines.add("&a+" + tickets + " Harvest Fair Ticket" + (tickets == 1 ? "" : "s"));
        }
        if (c != null && b != ContestMath.Bracket.NONE && m.dials.contestTurboBooks.get()) {
            String book = "ENCHANTMENT_" + c.turboEnchant.toUpperCase(java.util.Locale.ROOT) + "_1";
            if (Sky.items().exists(book)) {
                Sky.loot().give(p, book, 1, LootSource.REWARD);
                lines.add("&a+1 " + bookName(book));
            }
        }
        if (c != null) {
            long pb = d.personalBest.getOrDefault(c.name(), 0L);
            if (r.score > pb) { d.personalBest.put(c.name(), r.score); lines.add("&d&lNEW PERSONAL BEST! &7" + Text.commasInt(r.score) + " " + c.display); }
        }
        Msg.box(p, "&e&lHARVEST FAIR REWARDS " + (b == ContestMath.Bracket.NONE ? "&7(participation)" : b.colored()), lines);
    }

    /**
     * "Enchanted Book" alone says nothing (slayer request 11): name the enchant and level the book carries, read from
     * the book def's {@code extra.enchant}/{@code level} (enchanting's registration), else from the id.
     */
    static String bookName(String bookId) {
        ItemDef bd = Sky.items().def(bookId);
        String colour = bd == null || bd.rarity == null ? "&f" : bd.rarity.code();
        String ench = null;
        int lvl = 0;
        if (bd != null && bd.extra != null) {
            if (bd.extra.has("enchant") && bd.extra.get("enchant").isJsonPrimitive()) ench = bd.extra.get("enchant").getAsString();
            if (bd.extra.has("level") && bd.extra.get("level").isJsonPrimitive()) lvl = bd.extra.get("level").getAsInt();
        }
        if (ench == null && bookId.startsWith("ENCHANTMENT_")) {
            int us = bookId.lastIndexOf('_');
            ench = bookId.substring("ENCHANTMENT_".length(), us).toLowerCase(java.util.Locale.ROOT);
            try { lvl = Integer.parseInt(bookId.substring(us + 1)); } catch (NumberFormatException ignored) { }
        }
        com.skyclone.api.item.EnchantDef e = ench == null ? null : Sky.items().enchant(ench.toLowerCase(java.util.Locale.ROOT));
        String en = e != null && e.name != null ? Text.strip(e.name) : ench == null ? null : Text.titleCase(ench);
        String base = bd == null ? "Enchanted Book" : Text.strip(bd.name);
        return en == null ? colour + base : colour + base + " &7(" + en + (lvl > 0 ? " " + Text.roman(lvl) : "") + ")";
    }

    /**
     * A player whose contest ended while the server could not rank them (offline at the end AND not in the live field,
     * e.g. after a restart mid-fair): rank their saved score now against that contest's ghost field, which is
     * deterministic per (index, crop), so the reward is the one they would have got (minus real rivals, unknown now).
     */
    void finishLate(FarmData d) {
        long idx = d.contestIndex;
        Crop c = Crop.parse(d.contestCrop);
        boolean recorded = false;
        for (FarmData.ContestRecord r : d.history) if (r.index == idx) { recorded = true; break; }
        if (!recorded && c != null) {
            FarmData.ContestRecord r = new FarmData.ContestRecord();
            r.index = idx; r.crop = c.name(); r.score = d.contestScore;
            r.start = idx >= 0 ? ContestMath.startOf(idx, period(), offset()) : -idx * 1000L;
            int n = Math.max(0, m.dials.contestGhosts.get());
            // the ghosts as they stood when that fair stopped (1 = it ran to the end), on the same paces it used live
            double[] ghosts = ContestMath.ghostsAt(
                    ContestMath.ghostFinals(idx, c, n, m.dials.contestGhostMedian.get(), m.dials.contestGhostSigma.get()),
                    ContestMath.ghostPaces(idx, c, n), stoppedAt(idx));
            r.participants = ghosts.length + 1;
            if (d.contestScore >= Math.max(1, m.dials.contestMinScore.get())) {
                r.position = ContestMath.position(d.contestScore, ghosts);
                r.bracket = ContestMath.bracket(r.position, r.participants, goated()).name();
            }
            addRecord(d, r);
        }
        d.contestCrop = null;
        d.contestScore = 0;
    }

    // ---- scoring ------------------------------------------------------------------------------------

    boolean eligibleLevel(Player p) {
        if (!Sky.has(SkillService.class)) return true;
        return Sky.service(SkillService.class).level(p, SkillType.FARMING) >= m.dials.contestMinLevel.get();
    }

    /** Called by the harvest code. True when this harvest counts for the fair (contest drops may roll). */
    boolean onHarvest(Player p, Crop crop, int drops) {
        if (!active || !crops.contains(crop)) return false;
        FarmData d = m.data(p);
        if (d.contestIndex != index) {
            if (!eligibleLevel(p)) {
                notifyOnce(p, "&cYou need &aFarming " + Text.roman(m.dials.contestMinLevel.get()) + " &cto take part in the Harvest Fair.");
                return false;
            }
            if (m.dials.contestRequireHost.get() && !d.metHost) {
                notifyOnce(p, "&eA Harvest Fair is running! &7Talk to " + FarmingModule.HOST_NAME
                        + " &7(Hub farm or The Barn) to sign up, then farm " + cropList() + "&7.");
                return false;
            }
            d.contestIndex = index; d.contestCrop = crop.name(); d.contestScore = 0; d.contestsJoined++;
            Msg.send(p, "&eYou are now competing in the &6Harvest Fair &efor &a" + crop.display + "&e! &7(/harvestfair)");
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f);
        }
        if (!crop.name().equals(d.contestCrop)) {
            Crop joined = Crop.parse(d.contestCrop);
            notifyOnce(p, "&7You are competing with &a" + (joined == null ? d.contestCrop : joined.display) + "&7 this Harvest Fair.");
            return false;
        }
        d.contestScore += drops;
        track(p, crop, d.contestScore);
        return true;
    }

    private void track(Player p, Crop crop, long score) {
        if (crop == null) return;
        Entry e = field.computeIfAbsent(p.getUniqueId(), k -> new Entry());
        e.uuid = p.getUniqueId(); e.name = p.getName(); e.crop = crop; e.score = score;
    }

    private void notifyOnce(Player p, String msg) {
        long now = System.currentTimeMillis();
        Long t = notified.get(p.getUniqueId());
        if (t != null && now - t < 60_000) return;
        notified.put(p.getUniqueId(), now);
        Msg.send(p, msg);
    }

    boolean goated() {
        String perk = m.dials.goatedPerk.get();
        return perk != null && !perk.isBlank() && Sky.service(Features.Mayors.class).perkActive(perk);
    }

    /** Current standing of a player in their crop: {position, participants}, or null. */
    int[] standing(Player p) {
        if (!active) return null;
        FarmData d = m.data(p);
        if (d.contestIndex != index || d.contestCrop == null) return null;
        Crop c = Crop.parse(d.contestCrop);
        double[] others = othersNow(c, p.getUniqueId());
        return new int[]{ContestMath.position(d.contestScore, others), others.length + 1};
    }

    /** Everyone else's current score in a crop (ghosts at the current time + real players). */
    double[] othersNow(Crop c, UUID except) {
        List<Double> l = new ArrayList<>();
        for (double g : ghostNow.getOrDefault(c, new double[0])) l.add(g);
        int min = m.dials.contestMinScore.get();
        for (Entry e : field.values()) if (e.crop == c && !e.uuid.equals(except) && e.score >= min) l.add((double) e.score);
        return l.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Leaderboard rows for a crop: name, score, isGhost. Top {@code n}. */
    List<Object[]> leaderboard(Crop c, int n) {
        List<Object[]> rows = new ArrayList<>();
        double[] g = ghostNow.getOrDefault(c, new double[0]);
        double[] f = ghostFinals.getOrDefault(c, new double[0]);
        for (int i = 0; i < g.length && i < f.length; i++) rows.add(new Object[]{ContestMath.ghostName(index, c, i), (long) g[i], true});
        for (Entry e : field.values()) if (e.crop == c) rows.add(new Object[]{e.name, e.score, false});
        rows.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));
        return rows.size() > n ? new ArrayList<>(rows.subList(0, n)) : rows;
    }

    int participants(Crop c) {
        int n = 0;
        for (Entry e : field.values()) if (e.crop == c) n++;
        return n;
    }

    long millisLeft() { return active ? Math.max(0, endMillis - System.currentTimeMillis()) : 0; }

    long millisUntilNext() {
        if (active) return 0;
        return ContestMath.untilNext(System.currentTimeMillis(), period(), offset(), duration());
    }

    // ---- sidebar ------------------------------------------------------------------------------------

    /**
     * The menus module's /settings (read through Features.Settings, defaults when it is absent): "Event Start
     * Messages" and "Calendar Alerts" (off = silent) gate the start/end chat lines. Your own placement and rewards
     * always show.
     */
    boolean wantsEventChat(Player p) {
        if (!m.dials.respectSettings.get()) return true;
        try {
            Features.Settings s = Sky.service(Features.Settings.class);
            return s.flag(p, "notify.event_start", true) && !"off".equals(s.text(p, "notify.event_scope", "important"));
        } catch (RuntimeException e) { return true; }
    }

    List<String> sidebar(Player p) {
        if (!m.dials.contestSidebar.get() || !Sky.profiles().isReady(p)) return List.of();
        if (m.dials.respectSettings.get()) {
            try { if (!Sky.service(Features.Settings.class).flag(p, "sidebar.events", true)) return List.of(); }
            catch (RuntimeException ignored) { }
        }
        String island = Sky.worlds().islandOf(p.getWorld());
        boolean farmingPlace = island != null && (m.dials.islandSet().contains(island) && !"hub".equals(island) || "garden".equals(island));
        if (!active) {
            long until = millisUntilNext();
            if (farmingPlace && m.dials.contestEnabled.get() && until > 0 && until <= 10 * 60_000L)
                return List.of("&eHarvest Fair &7in &a" + Text.duration(until / 1000));
            return List.of();
        }
        FarmData d = m.data(p);
        boolean joined = d.contestIndex == index && d.contestCrop != null;
        boolean inHubFields = "hub".equals(island) && Sky.worlds().zoneOf(p) != null && Sky.worlds().zoneOf(p).id().contains("farm");
        if (!joined && !farmingPlace && !inHubFields) return List.of();
        List<String> out = new ArrayList<>();
        out.add("&eHarvest Fair &a" + Text.duration(millisLeft() / 1000));
        if (!joined) {
            StringBuilder sb = new StringBuilder("&7");
            for (Crop c : crops) { if (sb.length() > 2) sb.append(", "); sb.append(c.display); }
            out.add(sb.toString());
            return out;
        }
        Crop c = Crop.parse(d.contestCrop);
        out.add("&e○ &f" + (c == null ? d.contestCrop : c.display) + " &6" + Text.commasInt(d.contestScore));
        if (d.contestScore < m.dials.contestMinScore.get()) out.add("&7Collect &e" + m.dials.contestMinScore.get() + " &7to place");
        else {
            int[] st = standing(p);
            if (st != null) {
                ContestMath.Bracket b = ContestMath.bracket(st[0], st[1], goated());
                out.add((b == ContestMath.Bracket.NONE ? "&7Unranked" : b.colored()) + " &7#" + st[0] + "/" + st[1]);
            }
        }
        return out;
    }

    // ---- events -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCalStart(SkyEvents.CalendarStart e) {
        if (!EVENT_ID.equals(e.eventId) || active) return;
        long now = System.currentTimeMillis();
        // the schedule's own window: the tick will pick it up; anything else is an admin force
        if (!ContestMath.active(now, period(), offset(), duration())) forceStart(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCalEnd(SkyEvents.CalendarEnd e) {
        if (EVENT_ID.equals(e.eventId) && active && forced) end();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        FarmData d = m.data(p);
        if (d.contestCrop != null) {
            if (active && d.contestIndex == index) track(p, Crop.parse(d.contestCrop), d.contestScore);
            else finishLate(d);
        }
        boolean unpaid = false;
        for (FarmData.ContestRecord r : d.history) if (!r.claimed && r.bracket != null) unpaid = true;
        if (!unpaid) return;
        if (m.dials.contestAutoClaim.get()) Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) claimAll(p); }, 60L);
        else Msg.send(p, "&eYou have unclaimed Harvest Fair rewards! &7Visit &6" + FarmingModule.HOST_NAME + "&7 or use /harvestfair.");
    }
}
