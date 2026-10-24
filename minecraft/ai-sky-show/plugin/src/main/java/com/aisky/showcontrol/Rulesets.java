package com.aisky.showcontrol;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * plugins/ShowControl/rulesets.yml. A ruleset is a set of REAL SkyClone dial values (applied through
 * SkyClone's own {@code /sb dial set}, so SkyClone validates every key) plus ShowControl's own knobs
 * for what SkyClone has no dial for. The active ruleset and the dial keys it touched are remembered
 * in state.yml so switching (or {@code normal}) resets exactly those keys, even after a restart.
 */
final class Rulesets {

    private final JavaPlugin plugin;
    private final CommandRunner runner;
    private final File stateFile;

    /** ShowControl knobs (read by listeners/stat provider at use time). */
    volatile double mobCoinMult = 1.0;
    volatile double magicFindBonus = 0.0;
    volatile String active = null;
    private List<String> appliedKeys = new ArrayList<>();

    Rulesets(JavaPlugin plugin, CommandRunner runner) {
        this.plugin = plugin;
        this.runner = runner;
        this.stateFile = new File(plugin.getDataFolder(), "state.yml");
    }

    private YamlConfiguration file() {
        File f = new File(plugin.getDataFolder(), "rulesets.yml");
        if (!f.exists()) plugin.saveResource("rulesets.yml", false);
        return YamlConfiguration.loadConfiguration(f);
    }

    List<String> names() {
        ConfigurationSection s = file().getConfigurationSection("rulesets");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    String description(String name) {
        return file().getString("rulesets." + name + ".description", "");
    }

    /** Boot: restore the active ruleset's ShowControl knobs (dials persist on SkyClone's side). */
    void restore() {
        if (!stateFile.exists()) return;
        YamlConfiguration st = YamlConfiguration.loadConfiguration(stateFile);
        active = st.getString("ruleset.active", null);
        appliedKeys = new ArrayList<>(st.getStringList("ruleset.applied-dials"));
        if (active != null) {
            ConfigurationSection rs = file().getConfigurationSection("rulesets." + active);
            if (rs != null) loadKnobs(rs);
        }
    }

    /** Main thread. Returns a human report, line per action. */
    List<String> apply(String name) {
        List<String> report = new ArrayList<>();
        String key = name.toLowerCase(Locale.ROOT);
        boolean off = key.equals("off") || key.equals("none");
        ConfigurationSection rs = off ? null : file().getConfigurationSection("rulesets." + name);
        if (!off && rs == null) {
            report.add("No ruleset '" + name + "'. Known: " + String.join(", ", names()));
            return report;
        }
        // 1) undo the previous ruleset's dials
        for (String k : appliedKeys) {
            CommandRunner.Result r = runner.runNow("sb dial reset " + k);
            report.add("reset " + k + ": " + firstLine(r.output()));
        }
        appliedKeys = new ArrayList<>();
        mobCoinMult = 1.0;
        magicFindBonus = 0.0;
        active = null;
        if (off) {
            saveState();
            report.add("Ruleset cleared: reference rates.");
            return report;
        }
        // 2) set the new dials through SkyClone's own command (it validates key + value)
        Map<String, String> dials = new LinkedHashMap<>();
        ConfigurationSection ds = rs.getConfigurationSection("dials");
        if (ds != null) for (String k : ds.getKeys(false)) dials.put(k, String.valueOf(ds.get(k)));
        int ok = 0, bad = 0;
        for (Map.Entry<String, String> e : dials.entrySet()) {
            CommandRunner.Result r = runner.runNow("sb dial set " + e.getKey() + " " + e.getValue());
            String out = r.output();
            // Success prints SkyClone's dial line, which names the key; anything else (no dial, bad
            // value, a permission refusal, no output at all) is a failure.
            boolean failed = !r.ok() || out.contains("No dial") || out.startsWith("Not set") || !out.contains(e.getKey());
            if (failed) bad++; else { ok++; appliedKeys.add(e.getKey()); }
            report.add((failed ? "FAILED " : "set ") + e.getKey() + " = " + e.getValue() + "  -> " + firstLine(out));
        }
        loadKnobs(rs);
        active = name;
        saveState();
        report.add(String.format(Locale.ROOT, "Ruleset %s: %d dial(s) set, %d failed; mob_coin_mult=%.2f magic_find_bonus=%.0f",
                name, ok, bad, mobCoinMult, magicFindBonus));
        return report;
    }

    private void loadKnobs(ConfigurationSection rs) {
        mobCoinMult = Math.max(0, rs.getDouble("show.mob_coin_mult", 1.0));
        magicFindBonus = Math.max(0, rs.getDouble("show.magic_find_bonus", 0.0));
    }

    private void saveState() {
        YamlConfiguration st = stateFile.exists() ? YamlConfiguration.loadConfiguration(stateFile) : new YamlConfiguration();
        st.set("ruleset.active", active);
        st.set("ruleset.applied-dials", appliedKeys);
        try { st.save(stateFile); } catch (IOException e) { plugin.getLogger().warning("could not save state.yml: " + e); }
    }

    private static String firstLine(String s) {
        if (s == null || s.isEmpty()) return "(no output)";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
