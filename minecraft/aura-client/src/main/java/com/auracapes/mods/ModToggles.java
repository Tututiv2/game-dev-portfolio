package com.auracapes.mods;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.auracapes.AuraCapes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * {@code /mods}: switch whole mods on and off, and the Hypixel / Shadow Prison presets.
 *
 * <p><b>Nothing can be switched off while the game runs, and that shapes everything here.</b> Fabric
 * cannot unload a mod, and on Windows the loaded jar is held open, so it cannot even be renamed. So:
 * <ul>
 * <li>Turning a mod ON renames {@code x.jar.disabled} back to {@code x.jar} immediately (a disabled
 *     jar was never loaded, so nothing holds it). It loads on the next start.</li>
 * <li>Turning a mod OFF that is loaded is recorded in {@code config/aura-mods.json} as pending, and
 *     applied by a tiny helper started when the game closes, which waits for this process to exit
 *     and then renames the jar. The desktop shortcut's {@code tools/sync_instance_mods.py} applies the
 *     same list before every launch, which covers a crash (no clean close, no helper).</li>
 * </ul>
 * {@code .jar.disabled} is the convention Prism and MultiMC use, and Fabric only loads {@code *.jar}.
 *
 * <p>Every change goes through {@link ModPlan} so the game always starts afterwards.
 */
public final class ModToggles {

    /** Mods that are never listed: they are the game and the loader, not something you switch. */
    private static final Set<String> BUILTIN = Set.of("minecraft", "java", "fabricloader", "mixinextras");
    /** Listed but locked on: without them there is no Aura Client to switch anything back. */
    private static final Set<String> LOCKED = Set.of("auracapes", "fabric-api");

    /** id -> {on ids, off ids}. Overridable per name in aura-mods.json "presets". */
    private static final Map<String, Preset> DEFAULT_PRESETS = new LinkedHashMap<>();

    static {
        Set<String> hypixel = Set.of("skyhanni", "skyblocker", "hypixel-mod-api");
        DEFAULT_PRESETS.put("hypixel", new Preset("Hypixel", hypixel, Set.of(), false));
        DEFAULT_PRESETS.put("shadow", new Preset("Shadow Prison", Set.of(), hypixel, false));
        DEFAULT_PRESETS.put("all", new Preset("Everything on", Set.of(), Set.of(), true));
    }

    public record Preset(String label, Set<String> on, Set<String> off, boolean everything) {
    }

    /** What the screen and the command draw. */
    public record ModView(ModPlan.Entry entry, boolean loadedNow, boolean onNextStart, String version) {
        public boolean changesOnRestart() {
            return loadedNow != onNextStart;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path modsDir;
    /** key -> current file on disk (either x.jar or x.jar.disabled). */
    private static final Map<String, Path> files = new LinkedHashMap<>();
    private static final Map<String, ModPlan.Entry> entries = new LinkedHashMap<>();
    private static final Map<String, String> versions = new HashMap<>();
    /** Keys loaded in THIS run of the game. */
    private static final Set<String> loaded = new LinkedHashSet<>();
    /** Jars to rename to .disabled once the game has closed (absolute paths). */
    private static final Set<String> pendingDisable = new LinkedHashSet<>();
    private static final Map<String, Preset> presets = new LinkedHashMap<>(DEFAULT_PRESETS);
    private static boolean scanned;

    private ModToggles() {
    }

    // ------------------------------------------------------------------ public API

    public static synchronized List<ModView> list() {
        ensureScanned();
        Set<String> on = onNextStart();
        List<ModView> out = new ArrayList<>();
        for (ModPlan.Entry e : entries.values()) {
            out.add(new ModView(e, loaded.contains(e.key()), on.contains(e.key()), versions.getOrDefault(e.key(), "")));
        }
        out.sort(Comparator.comparing((ModView v) -> v.entry().name().toLowerCase(Locale.ROOT)));
        return out;
    }

    public static synchronized Map<String, Preset> presets() {
        ensureScanned();
        return Map.copyOf(presets);
    }

    /** Finds a mod by id, file name or display name (case-insensitive). */
    public static synchronized Optional<ModPlan.Entry> find(String query) {
        ensureScanned();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (ModPlan.Entry e : entries.values()) {
            if (e.id().equalsIgnoreCase(q) || e.key().equalsIgnoreCase(q) || e.name().equalsIgnoreCase(q)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** Lines to show the player. */
    public static synchronized List<String> set(String key, boolean wantOn) {
        ensureScanned();
        ModPlan.Result r = ModPlan.toggle(entries.values(), onNextStart(), key, wantOn);
        if (r.refused()) {
            return r.notes();
        }
        List<String> out = new ArrayList<>(r.notes());
        out.addAll(commit(r.on()));
        return out;
    }

    public static synchronized List<String> applyPreset(String name) {
        ensureScanned();
        Preset p = presets.get(name.toLowerCase(Locale.ROOT));
        if (p == null) {
            return List.of("No preset called " + name + ". Presets: " + String.join(", ", presets.keySet()));
        }
        Set<String> on = new LinkedHashSet<>();
        Set<String> off = new LinkedHashSet<>();
        for (ModPlan.Entry e : entries.values()) {
            if (p.everything() || p.on().contains(e.id())) {
                on.add(e.key());
            }
            if (p.off().contains(e.id())) {
                off.add(e.key());
            }
        }
        ModPlan.Result r = ModPlan.apply(entries.values(), onNextStart(), on, off);
        List<String> out = new ArrayList<>();
        out.add("Preset " + p.label() + ":");
        out.addAll(r.notes());
        List<String> changes = commit(r.on());
        out.addAll(changes.isEmpty() ? List.of("Already set up like that.") : changes);
        return out;
    }

    /** True when something only takes effect after a restart. */
    public static synchronized boolean restartNeeded() {
        ensureScanned();
        return !onNextStart().equals(loaded);
    }

    /**
     * Called on CLIENT_STOPPING. Starts the helper that renames pending jars once this JVM is gone.
     * Started through WMI so it is not a child of the game: some launchers put the game in a Windows
     * job object that kills every child when the game exits, which would kill the helper with it.
     */
    public static synchronized void onGameClosing() {
        if (!scanned || pendingDisable.isEmpty() || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return;
        }
        try {
            Path script = FabricLoader.getInstance().getConfigDir().resolve("aura-mods-apply.ps1");
            StringBuilder ps = new StringBuilder();
            ps.append("# Written by Aura Client (/mods). Waits for the game to close, then disables the jars below.\r\n");
            ps.append("$game = ").append(ProcessHandle.current().pid()).append("\r\n");
            ps.append("for ($i = 0; $i -lt 600; $i++) { if (-not (Get-Process -Id $game -ErrorAction SilentlyContinue)) { break }; Start-Sleep -Milliseconds 200 }\r\n");
            ps.append("$jars = @(\r\n");
            List<String> quoted = new ArrayList<>();
            for (String p : pendingDisable) {
                quoted.add("  '" + p.replace("'", "''") + "'");
            }
            ps.append(String.join(",\r\n", quoted)).append("\r\n)\r\n");
            ps.append("foreach ($j in $jars) {\r\n");
            ps.append("  for ($t = 0; $t -lt 25 -and (Test-Path -LiteralPath $j); $t++) {\r\n");
            ps.append("    try { Move-Item -LiteralPath $j -Destination ($j + '.disabled') -Force -ErrorAction Stop } catch { Start-Sleep -Milliseconds 400 }\r\n");
            ps.append("  }\r\n}\r\n");
            Files.writeString(script, ps.toString(), StandardCharsets.UTF_8);

            String inner = "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File \""
                    + script.toAbsolutePath() + "\"";
            String outer = "Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine='"
                    + inner.replace("'", "''") + "'} | Out-Null";
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", outer)
                    .redirectErrorStream(true).start();
            // Wait for the launcher process itself (about a second): if the game exits first and a job
            // object kills it, the helper never gets created. The helper then outlives us on its own.
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroy();
            }
            AuraCapes.LOG.info("[auracapes] /mods: {} jar(s) will be disabled once the game has closed", pendingDisable.size());
        } catch (IOException | InterruptedException ex) {
            AuraCapes.LOG.warn("[auracapes] /mods: could not start the close helper; the desktop shortcut applies it next launch", ex);
        }
    }

    // ------------------------------------------------------------------ state

    private static Set<String> onNextStart() {
        Set<String> on = new LinkedHashSet<>();
        for (Map.Entry<String, Path> f : files.entrySet()) {
            String file = f.getValue().getFileName().toString();
            boolean enabledFile = file.toLowerCase(Locale.ROOT).endsWith(".jar");
            if (enabledFile && !pendingDisable.contains(f.getValue().toAbsolutePath().toString())) {
                on.add(f.getKey());
            }
        }
        return on;
    }

    /** Makes the disk match {@code wantOn}; returns one line per change. */
    private static List<String> commit(Set<String> wantOn) {
        Set<String> now = onNextStart();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, ModPlan.Entry> en : entries.entrySet()) {
            String key = en.getKey();
            ModPlan.Entry e = en.getValue();
            boolean want = wantOn.contains(key);
            if (want == now.contains(key)) {
                continue;
            }
            Path file = files.get(key);
            try {
                if (want) {
                    String abs = file.toAbsolutePath().toString();
                    if (pendingDisable.remove(abs)) {
                        // still the loaded .jar: un-queue it, nothing to rename
                    } else {
                        Path target = file.resolveSibling(key);
                        Files.move(file, target);
                        files.put(key, target);
                    }
                    out.add("ON  " + e.name());
                } else if (loaded.contains(key)) {
                    pendingDisable.add(file.toAbsolutePath().toString());
                    out.add("OFF " + e.name());
                } else {
                    Path target = file.resolveSibling(key + ".disabled");
                    Files.move(file, target);
                    files.put(key, target);
                    out.add("OFF " + e.name());
                }
            } catch (IOException ex) {
                out.add("Could not change " + e.name() + ": " + ex.getMessage());
                AuraCapes.LOG.warn("[auracapes] /mods: rename failed for {}", file, ex);
            }
        }
        save();
        return out;
    }

    private static void ensureScanned() {
        if (!scanned) {
            scan();
            scanned = true;
        }
    }

    private static void scan() {
        files.clear();
        entries.clear();
        versions.clear();
        loaded.clear();

        // 1. Mods loaded right now: top-level jars only (nested ones belong to their parent).
        Map<Path, Integer> dirVotes = new HashMap<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            if (c.getContainingMod().isPresent() || BUILTIN.contains(c.getMetadata().getId())) {
                continue;
            }
            ModOrigin origin = c.getOrigin();
            if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1) {
                continue;
            }
            Path jar = origin.getPaths().get(0);
            if (!jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue; // dev environment: a classes folder, not a switchable jar
            }
            ModMetadata m = c.getMetadata();
            Set<String> provides = new LinkedHashSet<>();
            collectProvides(c, provides);
            Set<String> depends = new LinkedHashSet<>();
            for (ModDependency d : m.getDependencies()) {
                if (d.getKind() == ModDependency.Kind.DEPENDS) {
                    depends.add(d.getModId());
                }
            }
            String key = jar.getFileName().toString();
            entries.put(key, new ModPlan.Entry(key, m.getId(), m.getName(), provides, depends, LOCKED.contains(m.getId())));
            versions.put(key, m.getVersion().getFriendlyString());
            files.put(key, jar);
            loaded.add(key);
            dirVotes.merge(jar.getParent(), 1, Integer::sum);
        }
        modsDir = dirVotes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                .orElse(FabricLoader.getInstance().getGameDir().resolve("mods"));

        // 2. Jars switched off earlier: read their fabric.mod.json straight from the zip.
        try (Stream<Path> s = Files.list(modsDir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String name = p.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar.disabled")) {
                    continue;
                }
                String key = name.substring(0, name.length() - ".disabled".length());
                if (entries.containsKey(key)) {
                    continue;
                }
                ModPlan.Entry e = readJar(p, key);
                if (e != null && !BUILTIN.contains(e.id())) {
                    entries.put(key, e);
                    files.put(key, p);
                }
            }
        } catch (IOException ex) {
            AuraCapes.LOG.warn("[auracapes] /mods: could not list {}", modsDir, ex);
        }

        // 3. Pending changes from last time. A pending jar that no longer exists as .jar was applied.
        load();
        pendingDisable.removeIf(p -> !Files.exists(Path.of(p)));
        save();
    }

    private static void collectProvides(ModContainer c, Set<String> out) {
        out.add(c.getMetadata().getId());
        out.addAll(c.getMetadata().getProvides());
        for (ModContainer child : c.getContainedMods()) {
            collectProvides(child, out);
        }
    }

    // ------------------------------------------------------------------ disabled jars

    private static ModPlan.Entry readJar(Path jar, String key) {
        try (InputStream in = Files.newInputStream(jar)) {
            Set<String> provides = new LinkedHashSet<>();
            Set<String> depends = new LinkedHashSet<>();
            JsonObject root = readZip(in, provides, depends, true);
            if (root == null) {
                return null;
            }
            String id = root.get("id").getAsString();
            String name = root.has("name") ? root.get("name").getAsString() : id;
            if (root.has("version")) {
                versions.put(key, root.get("version").getAsString());
            }
            return new ModPlan.Entry(key, id, name, provides, depends, LOCKED.contains(id));
        } catch (IOException | RuntimeException ex) {
            AuraCapes.LOG.warn("[auracapes] /mods: could not read {}", jar, ex);
            return null;
        }
    }

    /** Returns the top-level fabric.mod.json; adds ids from it and every nested jar to {@code provides}. */
    private static JsonObject readZip(InputStream raw, Set<String> provides, Set<String> depends, boolean top)
            throws IOException {
        JsonObject self = null;
        ZipInputStream zip = new ZipInputStream(raw);
        for (ZipEntry ze; (ze = zip.getNextEntry()) != null; ) {
            String n = ze.getName();
            if (n.equals("fabric.mod.json")) {
                JsonObject m = JsonParser.parseReader(new InputStreamReader(zip, StandardCharsets.UTF_8)).getAsJsonObject();
                provides.add(m.get("id").getAsString());
                if (m.has("provides")) {
                    for (JsonElement p : m.getAsJsonArray("provides")) {
                        provides.add(p.isJsonObject() ? p.getAsJsonObject().get("id").getAsString() : p.getAsString());
                    }
                }
                if (top && m.has("depends")) {
                    depends.addAll(m.getAsJsonObject("depends").keySet());
                }
                if (top) {
                    self = m;
                }
            } else if (n.startsWith("META-INF/jars/") && n.endsWith(".jar")) {
                readZip(new NonClosing(zip), provides, depends, false);
            }
        }
        return self;
    }

    /** Lets a nested ZipInputStream read one entry without closing the outer stream. */
    private static final class NonClosing extends java.io.FilterInputStream {
        NonClosing(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
        }
    }

    // ------------------------------------------------------------------ config/aura-mods.json

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("aura-mods.json");
    }

    private static void load() {
        pendingDisable.clear();
        presets.clear();
        presets.putAll(DEFAULT_PRESETS);
        Path p = configPath();
        if (!Files.exists(p)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("pendingDisable")) {
                for (JsonElement e : root.getAsJsonArray("pendingDisable")) {
                    pendingDisable.add(e.getAsString());
                }
            }
            if (root.has("presets")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("presets").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    presets.put(e.getKey().toLowerCase(Locale.ROOT), new Preset(
                            o.has("label") ? o.get("label").getAsString() : e.getKey(),
                            ids(o, "on"), ids(o, "off"), o.has("everything") && o.get("everything").getAsBoolean()));
                }
            }
        } catch (IOException | RuntimeException ex) {
            AuraCapes.LOG.warn("[auracapes] /mods: ignoring unreadable {}", p, ex);
        }
    }

    private static Set<String> ids(JsonObject o, String field) {
        Set<String> out = new LinkedHashSet<>();
        if (o.has(field)) {
            for (JsonElement e : o.getAsJsonArray(field)) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Aura Client /mods. pendingDisable = jars renamed to .disabled after the game closes "
                + "(also applied by the desktop shortcut). presets: add your own as \"name\": {\"label\": ..., \"on\": [mod ids], \"off\": [mod ids]}.");
        JsonArray pend = new JsonArray();
        for (String s : pendingDisable) {
            pend.add(s);
        }
        root.add("pendingDisable", pend);
        JsonObject pre = new JsonObject();
        for (Map.Entry<String, Preset> e : presets.entrySet()) {
            if (DEFAULT_PRESETS.get(e.getKey()) == e.getValue()) {
                continue; // only user-defined or overridden presets are written
            }
            JsonObject o = new JsonObject();
            o.addProperty("label", e.getValue().label());
            o.add("on", array(e.getValue().on()));
            o.add("off", array(e.getValue().off()));
            if (e.getValue().everything()) {
                o.addProperty("everything", true);
            }
            pre.add(e.getKey(), o);
        }
        root.add("presets", pre);
        try {
            Path p = configPath();
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            AuraCapes.LOG.warn("[auracapes] /mods: could not save", ex);
        }
    }

    private static JsonArray array(Collection<String> in) {
        JsonArray a = new JsonArray();
        for (String s : in) {
            a.add(s);
        }
        return a;
    }
}
