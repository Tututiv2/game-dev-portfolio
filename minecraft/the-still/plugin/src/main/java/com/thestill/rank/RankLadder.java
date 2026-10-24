package com.thestill.rank;

import com.thestill.core.AtomicFiles;
import com.thestill.core.Service;
import com.thestill.economy.Money;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The rank ladder, loaded from {@code ranks.yml}.
 *
 * <p>Data rather than code, because the cost curve is the single most retuned
 * number on a prison server and it should never need a rebuild. The file is
 * generated on first run and then owned by whoever is tuning it.
 */
public final class RankLadder implements Service {

    private final Logger log;
    private final File file;

    private List<Rank> ranks = List.of();
    private final Map<String, Rank> byId = new HashMap<>();

    public RankLadder(Logger log, File file) {
        this.log = log;
        this.file = file;
    }

    @Override
    public void start() {
        load();
    }

    // ---- lookup ---------------------------------------------------------

    public List<Rank> all() {
        return ranks;
    }

    public int size() {
        return ranks.size();
    }

    public Rank starting() {
        return ranks.get(0);
    }

    public Rank highest() {
        return ranks.get(ranks.size() - 1);
    }

    /** Clamps rather than throwing: a stored index from an older, longer ladder must still resolve. */
    public Rank byIndex(int index) {
        if (ranks.isEmpty()) {
            throw new IllegalStateException("rank ladder is empty");
        }
        return ranks.get(Math.clamp(index, 0, ranks.size() - 1));
    }

    public Rank byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase());
    }

    /** The rung above this one, or null at the top. */
    public Rank next(Rank rank) {
        int next = rank.index() + 1;
        return next < ranks.size() ? ranks.get(next) : null;
    }

    public boolean isMax(Rank rank) {
        return rank.index() >= ranks.size() - 1;
    }

    // ---- persistence ----------------------------------------------------

    public void load() {
        if (!file.isFile()) {
            log.info("no ranks.yml yet — writing the default ladder");
            ranks = defaultLadder();
            reindex();
            save();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("ranks");
        if (section == null) {
            log.severe("ranks.yml has no 'ranks' section; using the default ladder");
            ranks = defaultLadder();
            reindex();
            return;
        }

        List<Rank> loaded = new ArrayList<>();
        int index = 0;
        for (String id : section.getKeys(false)) {
            // Per-row try/catch: one malformed rung must not cost the server its
            // whole progression, which would strand every player at rank 0.
            try {
                ConfigurationSection entry = section.getConfigurationSection(id);
                if (entry == null) {
                    log.warning("ranks.yml: '" + id + "' is not a section; skipping");
                    continue;
                }
                double costUnits = entry.getDouble("cost", -1);
                if (!Double.isFinite(costUnits) || costUnits < 0) {
                    log.warning("ranks.yml: '" + id + "' has a bad cost; skipping");
                    continue;
                }
                loaded.add(new Rank(
                        index,
                        id.toLowerCase(),
                        entry.getString("display", id.toUpperCase()),
                        Money.fromUnits(costUnits),
                        parseColour(entry.getString("colour"), NamedTextColor.GRAY)));
                index++;
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "ranks.yml: could not read '" + id + "'", e);
            }
        }

        if (loaded.isEmpty()) {
            log.severe("ranks.yml produced no usable ranks; using the default ladder");
            ranks = defaultLadder();
        } else {
            if (loaded.get(0).cost() != 0L) {
                log.warning("the first rank costs " + Money.format(loaded.get(0).cost())
                        + "; a starting rank nobody can afford strands new players");
            }
            ranks = List.copyOf(loaded);
        }
        reindex();
        log.info("rank ladder: " + ranks.size() + " rungs, "
                + ranks.get(0).display() + " to " + highest().display()
                + ", top rung " + Money.format(highest().cost()));
    }

    private void reindex() {
        byId.clear();
        ranks.forEach(rank -> byId.put(rank.id(), rank));
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Rank rank : ranks) {
            yaml.set("ranks." + rank.id() + ".display", rank.display());
            yaml.set("ranks." + rank.id() + ".cost", Money.toUnits(rank.cost()));
            yaml.set("ranks." + rank.id() + ".colour", rank.colour().asHexString());
        }
        yaml.options().setHeader(List.of(
                "The Still — the rank ladder.",
                "",
                "Order in this file IS the order of the ladder. The first entry is the",
                "starting rank and must cost 0. `cost` is what it takes to buy THAT rank",
                "from the one below it, in dollars.",
                "",
                "Depth access is gated on rank index — see /still region state <id>",
                "depth.required-rank."));
        try {
            AtomicFiles.writeString(file, yaml.saveToString());
        } catch (IOException e) {
            log.log(Level.SEVERE, "could not write ranks.yml", e);
        }
    }

    private static TextColor parseColour(String hex, TextColor fallback) {
        if (hex == null || hex.isBlank()) {
            return fallback;
        }
        TextColor parsed = TextColor.fromHexString(hex.startsWith("#") ? hex : "#" + hex);
        return parsed != null ? parsed : fallback;
    }

    /**
     * The ladder: Shadow Prison's live <em>costs</em>, but the rungs are <b>depths</b>.
     *
     * <p>A rung IS a depth, and it is displayed as one. It was A to Z, inherited from
     * Shadow Prison, and a letter is not a rank — it is a placeholder that survived
     * into something players read every second they are online. A round of themed names
     * was tried on top (Driftwood, Deckhand, Mudder...) and that was worse: it made a
     * <em>third</em> vocabulary to learn, on top of paid ranks and depths, for a thing
     * that is already a number.
     *
     * <p>So rung {@code n} is {@code Dn}, it unlocks {@code Dn}, and the sidebar's
     * "Rank" and "Depth" lines finally agree with each other. <b>Do not put a second
     * name on this axis.</b> The words the crew uses for how quiet you are belong to
     * the PAID ranks — Member, Muffled, Hushed, Silent, Unheard, The Still —
     * which are a different axis entirely and are cosmetic forever.
     *
     * <p><b>The costs are unchanged and must not be re-derived.</b>
     *
     * <p>$0 to $1,200,000, summing to $15,221,000 for a full climb — retuned down
     * from about $32M on 2026-07-16 after real play. Two things about its shape
     * are worth understanding before anyone "fixes" it:
     *
     * <ul>
     *   <li>It is <b>concave</b>: big multiples early (3k → 8k → 20k → 40k is
     *       2.7x, 2.5x, 2x) decelerating to almost flat at the top (1.19M →
     *       1.20M). That is the deliberate opposite of a geometric ladder. Early
     *       ranks are meant to fall fast so a new player feels the loop working;
     *       late ranks are meant to be a formality, because by then the reason
     *       to keep playing is depth, not the rung.
     *   <li>The build plan's "$1.4M with a 1.09x multiplier" describes neither
     *       this table nor any live table. $1.4M is entry 14 of a stale hardcoded
     *       array that stopped being used in July, and 1.09 is the per-<i>prestige</i>
     *       scalar applied over the whole ladder at once — a Phase 7 concern,
     *       not a rung-to-rung ratio.
     * </ul>
     */
    private static List<Rank> defaultLadder() {
        String[][] rungs = {
                {"d1", "D1"},
                {"d2", "D2"},
                {"d3", "D3"},
                {"d4", "D4"},
                {"d5", "D5"},
                {"d6", "D6"},
                {"d7", "D7"},
                {"d8", "D8"},
                {"d9", "D9"},
                {"d10", "D10"},
                {"d11", "D11"},
                {"d12", "D12"},
                {"d13", "D13"},
                {"d14", "D14"},
                {"d15", "D15"},
                {"d16", "D16"},
                {"d17", "D17"},
                {"d18", "D18"},
                {"d19", "D19"},
                {"d20", "D20"},
                {"d21", "D21"},
                {"d22", "D22"},
                {"d23", "D23"},
                {"d24", "D24"},
                {"d25", "D25"},
                {"d26", "D26"},
        };
        long[] costs = {
                0, 3_000, 8_000, 20_000, 40_000, 75_000, 120_000, 200_000, 280_000,
                360_000, 440_000, 510_000, 575_000, 640_000, 700_000, 760_000, 820_000,
                880_000, 940_000, 1_000_000, 1_050_000, 1_100_000, 1_140_000, 1_170_000,
                1_190_000, 1_200_000
        };
        List<Rank> ladder = new ArrayList<>(costs.length);
        for (int i = 0; i < costs.length; i++) {
            ladder.add(new Rank(i, rungs[i][0], rungs[i][1],
                    Money.fromUnits(costs[i]),
                    colourFor(i, costs.length)));
        }
        return List.copyOf(ladder);
    }

    /** Cool grey at the bottom, warming as the ladder climbs. */
    private static TextColor colourFor(int index, int count) {
        float t = count <= 1 ? 0f : (float) index / (count - 1);
        int red = (int) (110 + t * 145);
        int green = (int) (125 + t * 60);
        int blue = (int) (135 - t * 65);
        return TextColor.color(red, green, blue);
    }
}
