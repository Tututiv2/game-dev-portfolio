package com.skyclone.core.items;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import io.papermc.paper.datacomponent.DataComponentTypes;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemService;
import com.skyclone.api.item.ItemStatContributor;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.ReforgeDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Text;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The item registry and the ItemStack factory.
 *
 * <p><b>Canonical stacks.</b> Every sky stack is rebuilt from (def, ItemData) by one function,
 * {@link #buildMeta}, so a stack made by {@link #create}, one converted from a vanilla pickup and
 * one {@link #refresh}ed from an old render are byte-identical and stack together. Untouched
 * stackables carry ONLY the {@code skyclone:id} tag (no data, no timestamp).
 */
public final class ItemServiceImpl implements ItemService {

    public static final NamespacedKey KEY_ID = new NamespacedKey("skyclone", "id");
    public static final NamespacedKey KEY_DATA = new NamespacedKey("skyclone", "data");
    /** Tag a module puts on a def to say "this is THE sky item for its vanilla material". */
    public static final String TAG_VANILLA = "vanilla";
    /** Tag: a non-vanilla sky item that may be placed as a block. */
    public static final String TAG_PLACEABLE = "placeable";

    private final JavaPlugin plugin;
    private final Map<String, ItemDef> defs = new ConcurrentHashMap<>();
    private final Set<String> autoVanilla = ConcurrentHashMap.newKeySet();
    private final Map<String, Material> materials = new ConcurrentHashMap<>();
    private final Map<String, EnchantDef> enchants = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, ReforgeDef> reforges = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String> renames = new ConcurrentHashMap<>();
    private final Map<String, ItemStatContributor> statContributors = new LinkedHashMap<>();
    private final Map<String, LoreContributor> loreContributors = new LinkedHashMap<>();
    private volatile List<ItemStatContributor> statSnapshot = List.of();
    private volatile List<LoreContributor> loreSnapshot = List.of();
    private final Map<String, List<String>> aliases = new HashMap<>();
    private final Map<Material, String> vanillaTagged = new ConcurrentHashMap<>();
    private final Map<Material, String> vanillaCache = new ConcurrentHashMap<>();
    private final Map<String, ItemStack> templates = new ConcurrentHashMap<>();
    private final AtomicInteger version = new AtomicInteger();
    private final Map<String, Long> throttle = new ConcurrentHashMap<>();
    private volatile String loadingModule;
    private volatile boolean registeringAuto;

    public ItemServiceImpl(JavaPlugin plugin) {
        this.plugin = plugin;
        DefJson.setWarnSink(msg -> plugin.getLogger().warning("[items] " + msg));
    }

    // =========================================================================================
    // boot helpers
    // =========================================================================================

    /** Register a plain COMMON def for every vanilla item material. They are replaceable. */
    void registerVanilla() {
        registeringAuto = true;
        int n = 0;
        try {
            for (Material m : Material.values()) {
                if (m.isLegacy() || !m.isItem() || m.isAir()) continue;
                if (defs.containsKey(m.name())) continue;
                ItemDef d = ItemDef.of(m.name(), Text.titleCase(m.name()), m.name(), Rarity.COMMON, vanillaCategory(m));
                d.maxStack = d.category.isUniqueByNature() ? 1 : Math.max(1, Math.min(99, m.getMaxStackSize()));
                register(d);
                autoVanilla.add(d.id);
                n++;
            }
        } finally {
            registeringAuto = false;
        }
        plugin.getLogger().info("[items] registered " + n + " vanilla item defs");
    }

    static ItemCategory vanillaCategory(Material m) {
        String n = m.name();
        if (n.endsWith("_SWORD") || n.equals("MACE") || n.equals("TRIDENT")) return ItemCategory.SWORD;
        if (n.equals("BOW") || n.equals("CROSSBOW")) return ItemCategory.BOW;
        if (n.endsWith("_PICKAXE")) return ItemCategory.PICKAXE;
        if (n.endsWith("_AXE")) return ItemCategory.AXE;
        if (n.endsWith("_SHOVEL")) return ItemCategory.SHOVEL;
        if (n.endsWith("_HOE")) return ItemCategory.HOE;
        if (n.equals("SHEARS")) return ItemCategory.SHEARS;
        if (n.equals("FISHING_ROD")) return ItemCategory.FISHING_ROD;
        if (n.endsWith("_HELMET")) return ItemCategory.HELMET;
        if (n.endsWith("_CHESTPLATE")) return ItemCategory.CHESTPLATE;
        if (n.endsWith("_LEGGINGS")) return ItemCategory.LEGGINGS;
        if (n.endsWith("_BOOTS")) return ItemCategory.BOOTS;
        return ItemCategory.MATERIAL;
    }

    /** data/core-items/vanilla_aliases.json: Material -> reference ids meaning that vanilla item. */
    void loadAliases() {
        try (InputStream in = plugin.getResource("data/core-items/vanilla_aliases.json")) {
            if (in == null) { plugin.getLogger().warning("[items] vanilla_aliases.json missing from the jar"); return; }
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                List<String> l = new ArrayList<>();
                for (JsonElement x : e.getValue().getAsJsonArray()) l.add(x.getAsString());
                aliases.put(e.getKey(), List.copyOf(l));
            }
            plugin.getLogger().info("[items] " + aliases.size() + " vanilla id aliases loaded");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[items] could not read vanilla aliases", e);
        }
    }

    void loadRenames() {
        try {
            Map<String, String> m = Sky.db().query(c -> {
                Map<String, String> out = new HashMap<>();
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT id, name FROM items_renames")) {
                    while (rs.next()) out.put(rs.getString(1), rs.getString(2));
                }
                return out;
            }).get(15, TimeUnit.SECONDS);
            renames.putAll(m);
            if (!m.isEmpty()) plugin.getLogger().info("[items] " + m.size() + " admin renames loaded");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[items] could not load item renames", e);
        }
    }

    // =========================================================================================
    // registry
    // =========================================================================================

    public int version() { return version.get(); }

    /** Drop cached stackable renders (requirement texts and contributors may have changed). */
    void clearTemplates() { templates.clear(); }

    @Override
    public boolean isAutoVanilla(String id) { return id != null && autoVanilla.contains(id); }

    @Override
    public void register(ItemDef def) {
        String why = DefJson.normalize(def);
        if (why != null) {
            plugin.getLogger().warning("[items] item def rejected (" + why + ")" + from());
            return;
        }
        ItemDef prev = defs.get(def.id);
        if (prev != null) {
            if (autoVanilla.contains(def.id) && !registeringAuto) {
                autoVanilla.remove(def.id);
            } else {
                plugin.getLogger().warning("[items] duplicate item id " + def.id + from() + " ignored; the first registration is kept");
                return;
            }
        }
        materials.put(def.id, resolveMaterial(def));
        defs.put(def.id, def);
        if (def.has(TAG_VANILLA) && !registeringAuto) {
            String had = vanillaTagged.putIfAbsent(materials.get(def.id), def.id);
            if (had != null && !had.equals(def.id))
                plugin.getLogger().warning("[items] " + def.id + " and " + had + " both claim to be the vanilla "
                        + materials.get(def.id) + "; keeping " + had);
        }
        vanillaCache.clear();
        templates.remove(def.id);
        version.incrementAndGet();
    }

    private String from() {
        String m = loadingModule;
        return m == null ? "" : " (from " + m + ")";
    }

    private Material resolveMaterial(ItemDef def) {
        String name = def.material.trim().toUpperCase(Locale.ROOT);
        Material m = Material.getMaterial(name);
        if (m == null) m = Material.matchMaterial(name);
        if (m == null || m.isLegacy() || !m.isItem() || m.isAir()) {
            plugin.getLogger().warning("[items] " + def.id + ": material '" + def.material + "' is not an item; using BARRIER");
            return Material.BARRIER;
        }
        return m;
    }

    @Override
    public int loadJson(String module, String file) {
        String path = "data/" + module + "/" + file;
        int[] ok = {0}, bad = {0};
        String prevModule = loadingModule;
        loadingModule = module;
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                plugin.getLogger().warning("[items] " + path + " not found in the jar");
                return 0;
            }
            try (Reader rd = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                DefJson.readItems(rd, new DefJson.EntrySink<>() {
                    @Override public void ok(ItemDef d) {
                        int v = version.get();
                        register(d);
                        if (version.get() != v) ok[0]++;
                    }
                    @Override public void bad(String id, String reason) {
                        bad[0]++;
                        plugin.getLogger().warning("[items] " + path + " entry " + id + " skipped: " + reason);
                    }
                });
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[items] could not read " + path, e);
        } finally {
            loadingModule = prevModule;
        }
        plugin.getLogger().info("[items] " + module + "/" + file + ": " + ok[0] + " items" + (bad[0] > 0 ? " (" + bad[0] + " bad)" : ""));
        return ok[0];
    }

    @Override public ItemDef def(String id) { return id == null ? null : defs.get(id); }

    @Override public boolean exists(String id) { return id != null && defs.containsKey(id); }

    @Override public Collection<ItemDef> all() { return Collections.unmodifiableCollection(defs.values()); }

    @Override
    public void rename(String id, String displayName) {
        if (id == null) return;
        String clean = displayName == null || displayName.isBlank() ? null : displayName.trim();
        if (clean == null) renames.remove(id); else renames.put(id, clean);
        templates.remove(id);
        version.incrementAndGet();
        Sky.db().transaction(c -> {
            if (clean == null) {
                try (var ps = c.prepareStatement("DELETE FROM items_renames WHERE id = ?")) { ps.setString(1, id); ps.executeUpdate(); }
            } else {
                try (var ps = c.prepareStatement("INSERT INTO items_renames(id, name) VALUES(?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET name = excluded.name")) {
                    ps.setString(1, id); ps.setString(2, clean); ps.executeUpdate();
                }
            }
            return null;
        }).exceptionally(t -> { plugin.getLogger().log(Level.WARNING, "[items] could not persist rename of " + id, t); return null; });
        // re-render that id for everyone online
        for (Player p : plugin.getServer().getOnlinePlayers()) refreshInventory(p, id);
    }

    public String renameOf(String id) { return renames.get(id); }

    public String displayName(ItemDef def) {
        String r = renames.get(def.id);
        return r != null ? r : def.name;
    }

    @Override
    public String vanillaId(Material material) {
        if (material == null) return null;
        return vanillaCache.computeIfAbsent(material, m -> {
            String t = vanillaTagged.get(m);
            if (t != null && defs.containsKey(t)) return t;
            List<String> al = aliases.get(m.name());
            if (al != null) for (String a : al) {
                if (defs.containsKey(a) && !autoVanilla.contains(a)) return a;
            }
            return m.name();
        });
    }

    // ---- enchants & reforges -----------------------------------------------------------------

    @Override
    public void registerEnchant(EnchantDef def) {
        if (def == null || def.id == null || def.id.isBlank()) { plugin.getLogger().warning("[items] enchant without id ignored"); return; }
        if (enchants.putIfAbsent(def.id, def) != null) {
            plugin.getLogger().warning("[items] duplicate enchant " + def.id + " ignored; first kept");
            return;
        }
        templates.clear();
        version.incrementAndGet();
    }

    @Override public EnchantDef enchant(String id) { return id == null ? null : enchants.get(id); }

    @Override public Collection<EnchantDef> enchants() { synchronized (enchants) { return List.copyOf(enchants.values()); } }

    @Override
    public void registerReforge(ReforgeDef def) {
        if (def == null || def.id == null || def.id.isBlank()) { plugin.getLogger().warning("[items] reforge without id ignored"); return; }
        if (reforges.putIfAbsent(def.id, def) != null) {
            plugin.getLogger().warning("[items] duplicate reforge " + def.id + " ignored; first kept");
            return;
        }
        templates.clear();
        version.incrementAndGet();
    }

    @Override public ReforgeDef reforge(String id) { return id == null ? null : reforges.get(id); }

    @Override public Collection<ReforgeDef> reforges() { synchronized (reforges) { return List.copyOf(reforges.values()); } }

    @Override
    public synchronized void addStatContributor(String id, ItemStatContributor contributor) {
        if (id == null || contributor == null) return;
        statContributors.put(id, contributor);
        statSnapshot = List.copyOf(statContributors.values());
        templates.clear();
        version.incrementAndGet();
    }

    @Override
    public synchronized void addLoreContributor(String id, LoreContributor contributor) {
        if (id == null || contributor == null) return;
        loreContributors.put(id, contributor);
        loreSnapshot = List.copyOf(loreContributors.values());
        templates.clear();
        version.incrementAndGet();
    }

    // ---- wave 5: name prefixes + dupe-scan sources -------------------------------------------------

    private final Map<String, java.util.function.BiFunction<ItemDef, ItemData, String>> namePrefixes = new LinkedHashMap<>();
    private volatile List<java.util.function.BiFunction<ItemDef, ItemData, String>> prefixSnapshot = List.of();
    private final Map<String, java.util.function.Function<Player, List<ItemStack>>> dupeSources = new ConcurrentHashMap<>();

    @Override
    public synchronized void addNamePrefix(String id, java.util.function.BiFunction<ItemDef, ItemData, String> prefix) {
        if (id == null) return;
        if (prefix == null) namePrefixes.remove(id); else namePrefixes.put(id, prefix);
        prefixSnapshot = List.copyOf(namePrefixes.values());
        templates.clear();
        version.incrementAndGet();
    }

    /** Every registered prefix for one stack, concatenated in registration order ("" for none). */
    String namePrefix(ItemDef def, ItemData data) {
        List<java.util.function.BiFunction<ItemDef, ItemData, String>> l = prefixSnapshot;
        if (l.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var f : l) {
            try {
                String s = f.apply(def, data);
                if (s != null) sb.append(s);
            } catch (RuntimeException e) {
                warnThrottled("nameprefix:" + f.getClass().getName(), "name prefix " + f.getClass().getName() + " threw " + e);
            }
        }
        return sb.toString();
    }

    @Override
    public void addDupeScanSource(String id, java.util.function.Function<Player, List<ItemStack>> source) {
        if (id == null) return;
        if (source == null) dupeSources.remove(id); else dupeSources.put(id, source);
    }

    /** Snapshot of the module-supplied dupe-scan sources (id -> supplier). */
    Map<String, java.util.function.Function<Player, List<ItemStack>>> dupeSources() { return Map.copyOf(dupeSources); }

    // =========================================================================================
    // stacks
    // =========================================================================================

    public Material material(ItemDef def) {
        Material m = materials.get(def.id);
        return m == null ? Material.BARRIER : m;
    }

    @Override
    public ItemStack create(String id, int amount) {
        ItemDef def = def(id);
        if (def == null) throw new IllegalArgumentException("Unknown item id: " + id);
        int max = Math.max(1, Math.min(99, def.effectiveMaxStack()));
        int n = Math.max(1, Math.min(max, amount));
        return build(def, n);
    }

    /** create() without the max-stack clamp (conversion keeps whatever amount the vanilla stack had). */
    ItemStack build(ItemDef def, int amount) {
        if (def.isUnique()) {
            ItemData d = new ItemData();
            d.uuid = UUID.randomUUID().toString();
            d.created = System.currentTimeMillis();
            ItemStack st = new ItemStack(material(def), 1);
            st.setItemMeta(buildMeta(st, def, d, null));
            finish(st, def);
            return st;
        }
        ItemStack t = templates.get(def.id);
        if (t == null) {
            t = new ItemStack(material(def), 1);
            t.setItemMeta(buildMeta(t, def, new ItemData(), null));
            finish(t, def);
            templates.put(def.id, t);
        }
        ItemStack out = t.clone();
        out.setAmount(Math.max(1, Math.min(99, amount)));
        return out;
    }

    @Override
    public String idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        try {
            return stack.getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public ItemData data(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return new ItemData();
        try {
            String json = stack.getPersistentDataContainer().get(KEY_DATA, PersistentDataType.STRING);
            return ItemDataCodec.read(json);
        } catch (RuntimeException e) {
            return new ItemData();
        }
    }

    @Override
    public void update(ItemStack stack, Consumer<ItemData> change) {
        String id = idOf(stack);
        ItemDef def = def(id);
        if (def == null) {
            warnThrottled("update:" + id, "update() on a non-sky or unknown item (" + id + "); ignored");
            return;
        }
        ItemData d = data(stack);
        if (change != null) change.accept(d);
        if (def.isUnique() && (d.uuid == null || d.uuid.isEmpty())) {
            d.uuid = UUID.randomUUID().toString();
            if (d.created <= 0) d.created = System.currentTimeMillis();
        }
        writeInPlace(stack, def, d);
    }

    @Override
    public void refresh(ItemStack stack) {
        String id = idOf(stack);
        ItemDef def = def(id);
        if (def == null) return;
        ItemData d = data(stack);
        if (def.isUnique() && (d.uuid == null || d.uuid.isEmpty())) {
            d.uuid = UUID.randomUUID().toString();
            if (d.created <= 0) d.created = System.currentTimeMillis();
        }
        writeInPlace(stack, def, d);
    }

    private void writeInPlace(ItemStack stack, ItemDef def, ItemData d) {
        Material m = material(def);
        ItemMeta old = stack.getItemMeta();
        if (stack.getType() != m) stack.setType(m);
        ItemStack probe = new ItemStack(m);
        stack.setItemMeta(buildMeta(probe, def, d, old));
        finish(stack, def);
    }

    /** Component fixes ItemMeta cannot express: a stackable sky item on a tool material loses its durability bar. */
    private static void finish(ItemStack st, ItemDef def) {
        int maxStack = Math.max(1, Math.min(99, def.effectiveMaxStack()));
        if (maxStack > 1 && st.getType().getMaxDurability() > 0) {
            try {
                st.unsetData(DataComponentTypes.DAMAGE);
                st.unsetData(DataComponentTypes.MAX_DAMAGE);
                st.unsetData(DataComponentTypes.UNBREAKABLE);
                st.setData(DataComponentTypes.MAX_STACK_SIZE, maxStack);
            } catch (RuntimeException ignored) {}
        }
    }

    /**
     * The one place a sky item's meta is made. {@code carry} (nullable) is an old meta whose
     * foreign PDC keys (other modules' tags) are carried over; everything else is rebuilt.
     */
    ItemMeta buildMeta(ItemStack base, ItemDef def, ItemData data, ItemMeta carry) {
        ItemMeta meta = base.getItemMeta();
        Rarity rarity = SheetMath.effectiveRarity(def, data);
        ItemStatSheet sheet = sheet(def, data, rarity);
        LoreRenderer.Rendered r;
        try {
            r = LoreRenderer.render(def, data, rarity, sheet, renderEnv(), ItemsDials.integer(ItemsDials.ENCHANT_DETAIL_MAX, 3));
        } catch (RuntimeException e) {
            warnThrottled("render:" + def.id, "rendering " + def.id + " failed: " + e);
            r = new LoreRenderer.Rendered(rarity.code() + displayName(def), List.of(LoreRenderer.footer(def, rarity, data.recombobulated)));
        }
        meta.displayName(Text.c(r.name()));
        meta.lore(Text.lines(r.lore()));

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (carry != null) {
            try {
                // other modules' own PDC keys survive a re-render; ours are rewritten just below
                PersistentDataContainer old = carry.getPersistentDataContainer();
                if (!old.isEmpty()) old.copyTo(pdc, true);
                pdc.remove(KEY_DATA);
            } catch (RuntimeException ignored) {}
        }
        pdc.set(KEY_ID, PersistentDataType.STRING, def.id);
        String json = ItemDataCodec.write(data);
        if (json.isEmpty()) pdc.remove(KEY_DATA); else pdc.set(KEY_DATA, PersistentDataType.STRING, json);

        boolean glow = def.glow || def.has("glow") || def.id.startsWith("ENCHANTED_")
                || (data.enchants != null && !data.enchants.isEmpty());
        meta.setEnchantmentGlintOverride(glow ? Boolean.TRUE : null);
        Icons.hide(meta);
        try { meta.setAttributeModifiers(ImmutableMultimap.of()); } catch (RuntimeException ignored) {}
        boolean damageable = base.getType().getMaxDurability() > 0;
        int maxStack = Math.max(1, Math.min(99, def.effectiveMaxStack()));
        if (damageable && maxStack == 1) meta.setUnbreakable(true);
        // a damageable AND stackable item is invalid in vanilla; finish() strips durability first
        if (!damageable || maxStack == 1) {
            try { meta.setMaxStackSize(maxStack); } catch (RuntimeException ignored) {}
        }

        if (def.color != null && !def.color.isBlank()) {
            Color c = parseColor(def.color);
            if (c != null) {
                if (meta instanceof LeatherArmorMeta lm) lm.setColor(c);
                else if (meta instanceof PotionMeta pm) pm.setColor(c);
            }
        }
        if (def.skull != null && !def.skull.isBlank() && meta instanceof SkullMeta sm) {
            try { Icons.applyTexture(sm, def.skull); } catch (RuntimeException e) { warnThrottled("skull:" + def.id, "bad skull texture on " + def.id); }
        }
        return meta;
    }

    static Color parseColor(String s) {
        try {
            String h = s.trim();
            if (h.startsWith("#")) h = h.substring(1);
            if (h.contains(",")) {
                String[] p = h.split(",");
                return Color.fromRGB(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
            }
            return Color.fromRGB(Integer.parseInt(h, 16) & 0xFFFFFF);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LoreRenderer.Env renderEnv() {
        return new LoreRenderer.Env() {
            @Override public EnchantDef enchant(String id) { return ItemServiceImpl.this.enchant(id); }
            @Override public ReforgeDef reforge(String id) { return ItemServiceImpl.this.reforge(id); }
            @Override public String describeRequirement(String key) {
                try { return Sky.requirements().describe(key); } catch (RuntimeException e) { return null; }
            }
            @Override public List<LoreContributor> loreContributors() { return loreSnapshot; }
            @Override public String displayName(ItemDef def) { return ItemServiceImpl.this.displayName(def); }
            @Override public String namePrefix(ItemDef def, ItemData data) { return ItemServiceImpl.this.namePrefix(def, data); }
            @Override public void contributorFailed(Object c, Throwable t) {
                warnThrottled("lore:" + c.getClass().getName(), "lore contributor " + c.getClass().getName() + " threw " + t);
            }
        };
    }

    @Override
    public Rarity rarity(ItemStack stack) {
        ItemDef def = def(idOf(stack));
        if (def == null) return Rarity.COMMON;
        return SheetMath.effectiveRarity(def, data(stack));
    }

    @Override
    public ItemStatSheet sheet(ItemStack stack) {
        ItemDef def = def(idOf(stack));
        if (def == null) return new ItemStatSheet();
        ItemData d = data(stack);
        return sheet(def, d, SheetMath.effectiveRarity(def, d));
    }

    public ItemStatSheet sheet(ItemDef def, ItemData data, Rarity rarity) {
        return SheetMath.compute(def, data.copy(), rarity, this::reforge, statSnapshot,
                (c, t) -> warnThrottled("stat:" + c.getClass().getName(), "stat contributor " + c.getClass().getName() + " threw " + t));
    }

    // =========================================================================================
    // conversion
    // =========================================================================================

    /** Vanilla materials whose meta carries content we must never throw away. */
    private static boolean contentBearing(Material m) {
        String n = m.name();
        return n.endsWith("SHULKER_BOX") || n.endsWith("BUNDLE") || n.equals("FILLED_MAP") || n.equals("WRITTEN_BOOK")
                || n.equals("WRITABLE_BOOK") || n.equals("POTION") || n.equals("SPLASH_POTION") || n.equals("LINGERING_POTION")
                || n.equals("TIPPED_ARROW") || n.equals("ENCHANTED_BOOK") || n.equals("FIREWORK_ROCKET") || n.equals("FIREWORK_STAR")
                || n.endsWith("_BANNER") || n.equals("PLAYER_HEAD") || n.equals("SUSPICIOUS_STEW") || n.equals("GOAT_HORN")
                || n.equals("KNOWLEDGE_BOOK") || n.equals("COMPASS") || n.equals("RECOVERY_COMPASS") || n.endsWith("SPAWN_EGG")
                || n.equals("DEBUG_STICK") || n.equals("BUNDLE");
    }

    /** True when this non-sky stack would be converted. */
    public boolean convertible(ItemStack s) {
        if (s == null || s.getType().isAir()) return false;
        if (idOf(s) != null) return false;
        Material m = s.getType();
        if (s.hasItemMeta()) {
            if (contentBearing(m)) return false;
            ItemCategory c = vanillaCategory(m);
            // tools/weapons/armour may carry damage/vanilla enchants: converting just repairs them
            if (c == ItemCategory.MATERIAL) {
                ItemMeta meta = s.getItemMeta();
                if (meta == null) return false;
                if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants() || !meta.getPersistentDataContainer().isEmpty())
                    return false;
            }
        }
        return def(vanillaId(m)) != null;
    }

    /**
     * The sky version of a vanilla stack, or null when it must stay as it is. Unique defs with
     * amount > 1 return ONE stack of 1; the caller hands out the rest via {@link #convertExtras}.
     */
    public ItemStack convert(ItemStack s) {
        if (!convertible(s)) return null;
        ItemDef def = def(vanillaId(s.getType()));
        if (def == null) return null;
        return build(def, def.isUnique() ? 1 : s.getAmount());
    }

    /** Extra unique stacks (each with its own uuid) when a vanilla stack of n became a unique sky item. */
    public List<ItemStack> convertExtras(ItemStack original) {
        ItemDef def = def(vanillaId(original.getType()));
        if (def == null || !def.isUnique() || original.getAmount() <= 1) return List.of();
        List<ItemStack> l = new ArrayList<>();
        for (int i = 1; i < original.getAmount(); i++) l.add(build(def, 1));
        return l;
    }

    /**
     * Convert vanilla stacks in a player's inventory (never a Menu) and give uuids to unique sky
     * items missing one. {@code rerender} also re-renders every sky stack (join, rename).
     */
    public int sweep(Player p, boolean rerender) {
        if (!ItemsDials.flag(ItemsDials.CONVERT_VANILLA, true) && !rerender) return 0;
        PlayerInventory inv = p.getInventory();
        int changed = 0;
        List<ItemStack> overflow = new ArrayList<>();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType().isAir()) continue;
            String id = idOf(s);
            if (id == null) {
                if (!ItemsDials.flag(ItemsDials.CONVERT_VANILLA, true)) continue;
                ItemStack c = convert(s);
                if (c == null) continue;
                overflow.addAll(convertExtras(s));
                inv.setItem(i, c);
                changed++;
            } else {
                ItemDef def = def(id);
                if (def == null) continue;
                if (rerender) { refresh(s); inv.setItem(i, s); changed++; }
                else if (def.isUnique()) {
                    ItemData d = data(s);
                    if (d.uuid == null || d.uuid.isEmpty()) { refresh(s); inv.setItem(i, s); changed++; }
                }
            }
        }
        for (ItemStack o : overflow) {
            for (ItemStack left : inv.addItem(o).values()) {
                // a full inventory: to the item stash rather than the floor (only a full stash drops)
                int notStashed = com.skyclone.core.combat.ItemStash.put(p, left, true);
                if (notStashed <= 0) continue;
                left.setAmount(notStashed);
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        return changed;
    }

    /** Re-render every stack of {@code id} (or all sky stacks when null) in a player's inventory + ender chest. */
    public void refreshInventory(Player p, String id) {
        for (var inv : List.of(p.getInventory(), p.getEnderChest())) {
            ItemStack[] c = inv.getContents();
            for (int i = 0; i < c.length; i++) {
                ItemStack s = c[i];
                String sid = idOf(s);
                if (sid == null || (id != null && !id.equals(sid))) continue;
                refresh(s);
                inv.setItem(i, s);
            }
        }
    }

    // =========================================================================================
    // player helpers
    // =========================================================================================

    private boolean matches(ItemStack s, String id) {
        if (s == null || s.getType().isAir()) return false;
        String sid = idOf(s);
        if (sid != null) return sid.equals(id);
        return id.equals(vanillaId(s.getType())) && convertible(s);
    }

    @Override
    public int count(Player p, String id) {
        if (p == null || id == null) return 0;
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) if (matches(s, id)) n += s.getAmount();
        return n;
    }

    @Override
    public int remove(Player p, String id, int amount) {
        if (p == null || id == null || amount <= 0) return 0;
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getStorageContents();
        int left = amount;
        for (int i = 0; i < c.length && left > 0; i++) {
            ItemStack s = c[i];
            if (!matches(s, id)) continue;
            int take = Math.min(left, s.getAmount());
            left -= take;
            if (take >= s.getAmount()) inv.setItem(i, null);
            else { s.setAmount(s.getAmount() - take); inv.setItem(i, s); }
        }
        return amount - left;
    }

    @Override
    public String fullSet(Player p) {
        if (p == null) return null;
        String set = null;
        for (ItemStack s : p.getInventory().getArmorContents()) {
            ItemDef d = def(idOf(s));
            if (d == null || d.setId == null || d.setId.isEmpty()) return null;
            if (set == null) set = d.setId;
            else if (!set.equals(d.setId)) return null;
        }
        return set;
    }

    @Override
    public int setPieces(Player p, String setId) {
        if (p == null || setId == null) return 0;
        int n = 0;
        for (ItemStack s : p.getInventory().getArmorContents()) {
            ItemDef d = def(idOf(s));
            if (d != null && setId.equals(d.setId)) n++;
        }
        return n;
    }

    @Override
    public boolean meetsRequirements(Player p, ItemStack stack, boolean tell) {
        ItemDef def = def(idOf(stack));
        if (def == null || def.requirements == null || def.requirements.isEmpty()) return true;
        for (String key : def.requirements) {
            boolean ok;
            try {
                ok = tell ? Sky.requirements().checkOrTell(p, key) : Sky.requirements().check(p, key);
            } catch (RuntimeException e) {
                ok = false;
            }
            if (!ok) return false;
        }
        return true;
    }

    // =========================================================================================

    void warnThrottled(String key, String msg) {
        long now = System.currentTimeMillis();
        Long last = throttle.get(key);
        if (last != null && now - last < 300_000) return;
        throttle.put(key, now);
        plugin.getLogger().warning("[items] " + msg);
    }

    JavaPlugin plugin() { return plugin; }
}
