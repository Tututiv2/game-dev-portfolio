package com.thestill.role;

import com.thestill.core.AtomicFiles;
import com.thestill.core.Service;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@code roles.yml} — the paid ranks and the staff tags.
 *
 * <p>Modelled on {@link com.thestill.rank.RankLadder}: writes its own defaults on first
 * run, owns the file afterwards, and per-row try/catch so one malformed entry costs one
 * role rather than everybody's tag.
 *
 * <p><b>The file cannot introduce a pay-to-win perk.</b> Every row is validated through
 * {@link RoleDefinition}'s constructor, which refuses anything outside
 * {@link Perks#ALLOWED} — so editing YAML at 2am gets you a startup warning and a
 * skipped role, not a server where money buys mining speed.
 */
public class RoleConfig implements Service {

    /**
     * Ordering between categories. Lower sorts first and wins tab priority.
     *
     * <p>Taken from Shadow Prison's <b>live</b> config, not its bundled defaults — the
     * two had drifted and the live one is the truth. It carries two categories the
     * source template does not: {@code mythic} for something extremely rare that is not
     * for sale, and {@code story} for a tag earned by being part of one. Both are worth
     * having on day one even while empty, because a category added later cannot be
     * given to anybody retroactively.
     */
    public static final String STAFF = "staff";
    public static final String MYTHIC = "mythic";
    public static final String STORY = "story";
    public static final String DONOR = "donor";
    public static final String DEFAULT = "default";

    private static final Map<String, Integer> ORDER =
            Map.of(STAFF, 0, MYTHIC, 3, STORY, 4, DONOR, 5, DEFAULT, 99);

    private final Logger log;
    private final File file;

    private final Map<String, RoleDefinition> roles = new LinkedHashMap<>();

    public RoleConfig(Logger log, File file) {
        this.log = log;
        this.file = file;
    }

    @Override
    public void start() {
        load();
    }

    public RoleDefinition role(String id) {
        return id == null ? null : roles.get(id.toLowerCase());
    }

    public Map<String, RoleDefinition> all() {
        return Map.copyOf(roles);
    }

    /** Every category, best first. */
    public List<String> categories() {
        return ORDER.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
    }

    public int order(String category) {
        return ORDER.getOrDefault(category, 99);
    }

    /** The role somebody has in this category if they have never been given one. */
    public String defaultRole(String category) {
        return DEFAULT.equals(category) ? "member" : null;
    }

    public List<RoleDefinition> inCategory(String category) {
        return roles.values().stream()
                .filter(role -> role.category().equals(category))
                .sorted((a, b) -> Integer.compare(a.weight(), b.weight()))
                .toList();
    }

    // ---- persistence ----------------------------------------------------

    public void load() {
        roles.clear();
        if (!file.isFile()) {
            log.info("no roles.yml yet — writing the default tiers");
            defaults().forEach(role -> roles.put(role.id(), role));
            save();
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("roles");
        if (section == null) {
            log.severe("roles.yml has no 'roles' section; using the defaults");
            defaults().forEach(role -> roles.put(role.id(), role));
            return;
        }
        for (String id : section.getKeys(false)) {
            try {
                ConfigurationSection entry = section.getConfigurationSection(id);
                if (entry == null) {
                    log.warning("roles.yml: '" + id + "' is not a section; skipping");
                    continue;
                }
                roles.put(id.toLowerCase(), new RoleDefinition(
                        id.toLowerCase(),
                        entry.getString("category", DEFAULT),
                        entry.getInt("weight", 0),
                        entry.getString("name", id),
                        colour(entry.getString("start", "#FFFFFF"), TextColor.color(0xFFFFFF)),
                        colour(entry.getString("end", "#AAAAAA"), TextColor.color(0xAAAAAA)),
                        entry.getBoolean("gradient", false),
                        entry.getBoolean("brackets", true),
                        entry.getStringList("permissions"),
                        Set.copyOf(entry.getStringList("perks"))));
            } catch (RuntimeException e) {
                // Loud, and survivable. A role that grants a banned perk is refused by
                // name here rather than silently sanitised — an admin who edited it
                // needs to know it did not take.
                log.severe("roles.yml: '" + id + "' rejected — " + e.getMessage());
            }
        }
        if (roles.isEmpty()) {
            log.severe("roles.yml produced no usable roles; using the defaults");
            defaults().forEach(role -> roles.put(role.id(), role));
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "The Still — roles.",
                "",
                "These are the ranks you BUY. They are cosmetic, forever: no perk here",
                "may touch ore, money, the D1-D26 ladder, the Din, the heat or the seal.",
                "That is enforced in code — see com.thestill.role.Perks. A role granting",
                "anything outside that allow-list is refused at startup with a message,",
                "not quietly ignored.",
                "",
                "The ladder (D1..D26) is a different axis entirely and lives in ranks.yml.",
                "",
                "category: staff | donor | default. weight orders within a category and",
                "inherits permissions from everything below it."));
        ConfigurationSection section = yaml.createSection("roles");
        for (RoleDefinition role : roles.values()) {
            ConfigurationSection entry = section.createSection(role.id());
            entry.set("category", role.category());
            entry.set("weight", role.weight());
            entry.set("name", role.name());
            entry.set("start", hex(role.start()));
            entry.set("end", hex(role.end()));
            entry.set("gradient", role.gradient());
            entry.set("brackets", role.brackets());
            entry.set("permissions", role.permissions());
            entry.set("perks", List.copyOf(role.perks()));
        }
        try {
            AtomicFiles.writeString(file, yaml.saveToString());
        } catch (Exception e) {
            log.severe("could not write roles.yml: " + e.getMessage());
        }
    }

    private static TextColor colour(String hex, TextColor fallback) {
        TextColor parsed = TextColor.fromHexString(hex.startsWith("#") ? hex : "#" + hex);
        return parsed != null ? parsed : fallback;
    }

    private static String hex(TextColor colour) {
        return String.format("#%06X", colour.value());
    }

    /**
     * The default tiers.
     *
     * <p>Named for how little the infected notice you, which is the only status this
     * world recognises. Buying your way to "Silent" means something here in a way that
     * "VIP" never could — and it costs nothing to give away, because being described as
     * quiet is not the same as <em>being</em> quiet. The Din does not read your tag.
     */
    private static List<RoleDefinition> defaults() {
        List<RoleDefinition> out = new ArrayList<>();

        out.add(new RoleDefinition("member", DEFAULT, 0, "Member",
                TextColor.color(0x9AA3AA), TextColor.color(0x9AA3AA), false, true,
                List.of(), Set.of()));

        // SIX tiers, weights 10..60, and the SHAPE is copied from the live ladder
        // rather than invented: the bottom three are flat colours, the top three carry
        // gradients, and only the very top one MOVES. That escalation is most of why
        // the ladder reads as a ladder — if everything shimmered, nothing would.
        out.add(donor("muffled", 10, "Muffled", 0xB8A99A, 0xB8A99A, false,
                Set.of(Perks.NICK)));
        out.add(donor("hushed", 20, "Hushed", 0x8FA9B8, 0x8FA9B8, false,
                Set.of(Perks.NICK, Perks.NICK_RENAME, Perks.HAT, Perks.CHAT_COLOUR)));
        out.add(donor("softfoot", 30, "Softfoot", 0x7E9BD4, 0x7E9BD4, false,
                Set.of(Perks.NICK, Perks.NICK_RENAME, Perks.HAT, Perks.CHAT_COLOUR,
                        Perks.TRAIL)));
        out.add(donor("silent", 40, "Silent", 0xFF00FF, 0x8A2BE2, true,
                Set.of(Perks.NICK, Perks.NICK_RENAME, Perks.HAT, Perks.CHAT_COLOUR,
                        Perks.TRAIL, Perks.SIT)));
        out.add(donor("unheard", 50, "Unheard", 0xFFD700, 0xFF4500, true,
                Set.of(Perks.NICK, Perks.NICK_RENAME, Perks.NICK_GRADIENT, Perks.HAT,
                        Perks.CHAT_COLOUR, Perks.CHAT_ANIMATED, Perks.TRAIL, Perks.SIT,
                        Perks.JOIN_MESSAGE)));
        // THE TOP TIER IS CHROME, and it is the only thing on the server that is.
        // Sovereign is the only live rank carrying rgb-nametag; scarcity is the
        // product. Its colours are the sweep's endpoints, but CHROME overrides them.
        out.add(donor("thestill", 60, "The Still", 0x8A9099, 0xFFFFFF, true,
                Set.of(Perks.NICK, Perks.NICK_RENAME, Perks.NICK_GRADIENT, Perks.HAT,
                        Perks.CHAT_COLOUR, Perks.CHAT_ANIMATED, Perks.TAG_ANIMATED,
                        Perks.TRAIL, Perks.SIT, Perks.JOIN_MESSAGE)));

        // Empty on purpose. A category with nobody in it costs one line; a category
        // that does not exist cannot be granted to the person who earned it first.
        out.add(new RoleDefinition("wake", MYTHIC, 10, "The Wake",
                TextColor.color(0x39FF88), TextColor.color(0xB8FFD9), true, true,
                List.of(), Set.of(Perks.TAG_ANIMATED)));
        out.add(new RoleDefinition("witness", STORY, 10, "Witness",
                TextColor.color(0xC9B037), TextColor.color(0xFFF3B0), true, true,
                List.of(), Set.of()));

        out.add(new RoleDefinition("helper", STAFF, 100, "Helper",
                TextColor.color(0x6FCF97), TextColor.color(0x6FCF97), false, true,
                List.of("thestill.helper"), Set.of()));
        out.add(new RoleDefinition("mod", STAFF, 200, "Mod",
                TextColor.color(0x56CCF2), TextColor.color(0x56CCF2), false, true,
                List.of("thestill.mod"), Set.of()));
        out.add(new RoleDefinition("admin", STAFF, 500, "Admin",
                TextColor.color(0xEB5757), TextColor.color(0xEB5757), false, true,
                List.of("thestill.admin"), Set.of()));
        return out;
    }

    private static RoleDefinition donor(String id, int weight, String name,
                                        int start, int end, boolean gradient,
                                        Set<String> perks) {
        return new RoleDefinition(id, DONOR, weight, name,
                TextColor.color(start), TextColor.color(end), gradient, true,
                List.of("thestill.donor." + id), perks);
    }
}
