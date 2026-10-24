package com.skyclone.mod.crafting;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import org.bukkit.block.Container;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Carpentry furniture: one recipe per Carpentry level (research/02), placed as a small block build
 * where the player may build, removed whole (and handed back) when any of its blocks is broken.
 *
 * <p>Placements live in the CHUNK's persistent data (key {@code skyclone:furniture}), so they survive
 * restarts with the world. Furniture blocks never drop vanilla items, cannot be pushed by pistons or blown
 * up, and the vanilla containers in a build (barrels, composters, beds) are never used as themselves.
 *
 * <p>Wave 5: each piece does what its reference lore says ({@link FurnitureMath}): seats, storage pages and
 * showcases (contents in {@link FurnitureStore}, one table row per placed piece), stations whose real block is
 * answered by its owner (crafting grid, vanilla furnace, alchemy, enchanting), beds that set the island spawn, the
 * endless water trough and the clock.
 */
final class Furniture implements Listener {

    static final class Def {
        String id, name, description, rarity, skull;
        int level;
        List<String> recipe = new ArrayList<>();
        List<int[]> offsets = new ArrayList<>();
        List<String> data = new ArrayList<>();
        List<int[]> seats = new ArrayList<>();
        /** Wave 5: what the piece does (FurnitureMath), and blocks that must be free though not built. */
        String function = "cosmetic";
        List<int[]> space = new ArrayList<>();
    }

    /**
     * One placed piece: its blocks' absolute positions (same world as the chunk that stores it). Wave 5: {@code pid}
     * names its storage row (null on pieces placed before wave 5 until first needed), {@code facing} the placer's
     * facing (showcase displays face the front).
     */
    record Placed(String id, String owner, List<int[]> blocks, List<int[]> seats, String pid, String facing) {}

    final FurnitureStore store = new FurnitureStore();
    /** pid -&gt; the display entities (armour stand, item / text displays) of a showcase. Never persistent. */
    private final Map<String, List<Entity>> shows = new HashMap<>();
    private static final NamespacedKey DISPLAY_KEY = NamespacedKey.fromString("skyclone:furniture_display");

    private static final NamespacedKey KEY = NamespacedKey.fromString("skyclone:furniture");
    private final CraftingModule mod;
    final Map<String, Def> defs = new LinkedHashMap<>();
    final List<String> recipeIds = new ArrayList<>();
    private final Map<String, List<Placed>> cache = new HashMap<>();
    /**
     * world uid -> chunk keys of LOADED chunks that hold furniture. Every block break, liquid flow and hopper
     * move in the game asks "is this furniture?", so the answer for a chunk without furniture must be one set
     * lookup, not a PDC read. Kept from ChunkLoad/ChunkUnload and from every save.
     */
    private final Map<UUID, java.util.Set<Long>> marked = new HashMap<>();
    private final Map<UUID, Entity> sitting = new HashMap<>();
    private final Gson gson = new Gson();
    int itemsRegistered, recipesRegistered, recipesSkipped;

    Furniture(CraftingModule mod) { this.mod = mod; }

    void enable() {
        try (InputStream in = Sky.plugin().getResource("data/crafting/furniture.json")) {
            if (in == null) { Sky.log().warning("[crafting] furniture.json missing"); return; }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                for (JsonElement el : JsonParser.parseReader(r).getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    Def d = new Def();
                    d.id = o.get("id").getAsString();
                    d.name = o.get("name").getAsString();
                    d.description = o.get("description").getAsString();
                    d.rarity = o.get("rarity").getAsString();
                    d.skull = o.has("skull") && !o.get("skull").isJsonNull() ? o.get("skull").getAsString() : null;
                    d.level = o.get("level").getAsInt();
                    for (JsonElement c : o.getAsJsonArray("recipe")) d.recipe.add(c.getAsString());
                    for (JsonElement b : o.getAsJsonArray("blocks")) {
                        JsonArray a = b.getAsJsonArray();
                        d.offsets.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                        d.data.add(a.get(3).getAsString());
                    }
                    for (JsonElement s : o.getAsJsonArray("seats")) {
                        JsonArray a = s.getAsJsonArray();
                        d.seats.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                    }
                    if (o.has("function") && !o.get("function").isJsonNull()) d.function = o.get("function").getAsString();
                    if (o.has("space")) for (JsonElement s : o.getAsJsonArray("space")) {
                        JsonArray a = s.getAsJsonArray();
                        d.space.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                    }
                    defs.put(d.id, d);
                }
            }
        } catch (Exception e) {
            Sky.log().warning("[crafting] could not read furniture.json: " + e);
        }
        store.enable();
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) markIfStored(c);
        // Showcases already loaded at boot get their displays once the worlds have settled.
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) settle(c);
        }, 40L);
    }

    Def def(String id) { return defs.get(id); }

    /** Pieces whose contents live in {@link FurnitureStore}. */
    static boolean holdsItems(Def d) {
        return d != null && (FurnitureMath.pages(d.function) > 0 || FurnitureMath.showSlots(d.function) > 0);
    }

    private void markIfStored(Chunk c) {
        try {
            if (c.getPersistentDataContainer().has(KEY, PersistentDataType.STRING)) mark(c, true);
        } catch (RuntimeException ignored) {}
    }

    private void mark(Chunk c, boolean on) {
        UUID w = c.getWorld().getUID();
        if (on) marked.computeIfAbsent(w, k -> new java.util.HashSet<>()).add(c.getChunkKey());
        else {
            java.util.Set<Long> s = marked.get(w);
            if (s != null) { s.remove(c.getChunkKey()); if (s.isEmpty()) marked.remove(w); }
        }
    }

    int markedChunks() { int n = 0; for (java.util.Set<Long> s : marked.values()) n += s.size(); return n; }

    /** Items, only where no other module registered the id (they may own a richer version). */
    void postEnable() {
        for (Def d : defs.values()) {
            if (Sky.items().exists(d.id)) continue;
            ItemDef it = ItemDef.of(d.id, d.name, "PLAYER_HEAD", Rarity.parse(d.rarity), ItemCategory.MATERIAL);
            it.skull = d.skull;
            it.maxStack = 16;
            // The reference's header: "Furniture", then what kind of furniture it is (Seat / Storage / Item Showcase).
            it.lore.add("&8Furniture");
            for (String t : FurnitureMath.tags(d.function)) it.lore.add("&8" + t);
            it.lore.add("");
            it.lore.addAll(Text.wrap("&7" + d.description, 30));
            String does = FurnitureMath.describe(d.function);
            if (does != null) { it.lore.add(""); it.lore.add(does); }
            it.lore.add("");
            it.lore.add("&8Carpentry " + Text.roman(d.level));
            it.lore.add("&eRight-click a block where you");
            it.lore.add("&ecan build to place it!");
            it.tag("furniture", "no_reforge");
            it.extra.addProperty("furniture_level", d.level);
            Sky.items().register(it);
            itemsRegistered++;
        }
    }

    /** CARPENTRY recipes, unlocked at the matching Carpentry level. Run after materials registered its ids. */
    void registerRecipes() {
        for (Def d : defs.values()) {
            String[] grid = new String[9];
            boolean ok = true;
            for (int i = 0; i < 9 && i < d.recipe.size(); i++) {
                String cell = d.recipe.get(i);
                if (cell == null || cell.isBlank()) continue;
                CraftMath.Cell c = CraftMath.parse(cell, null);
                String id = RecipeIndex.resolveMaterial(c.id());
                if (id == null) id = mod.index.canon(c.id());
                if (!Sky.items().exists(id)) { ok = false; break; }
                grid[i] = mod.index.cellText(id, c.amount());
            }
            if (!ok) { recipesSkipped++; continue; }
            Recipe r = new Recipe();
            r.id = "furniture:" + d.id;
            r.type = Recipe.Type.CARPENTRY;
            r.result = d.id;
            r.amount = 1;
            r.grid = grid;
            r.unlock = "skill:carpentry:" + d.level;
            r.station = "Carpentry";
            int before = Sky.recipes().all().size();
            Sky.recipes().register(r);
            if (Sky.recipes().all().size() > before) { recipesRegistered++; recipeIds.add(r.id); } else recipesSkipped++;
        }
    }

    void openMenu(Player p) { mod.book.category(p, Category.CARPENTRY, 0).open(p); }

    // ---- placement --------------------------------------------------------------------------------

    private static StructureRotation rotationFor(BlockFace facing) {
        return switch (facing) {
            case EAST -> StructureRotation.CLOCKWISE_90;
            case SOUTH -> StructureRotation.CLOCKWISE_180;
            case WEST -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }

    /** (dx, dz) rotated clockwise as seen from above, north = -z. */
    static int[] rotate(int dx, int dz, StructureRotation r) {
        return switch (r) {
            case CLOCKWISE_90 -> new int[]{-dz, dx};
            case CLOCKWISE_180 -> new int[]{-dx, -dz};
            case COUNTERCLOCKWISE_90 -> new int[]{dz, -dx};
            default -> new int[]{dx, dz};
        };
    }

    static boolean canBuild(Player p, Location l) {
        try {
            if (Sky.worlds().buildBypass(p)) return true;
            String island = Sky.worlds().islandOf(l.getWorld());
            if (island == null) return false;                  // furniture is an island thing
            Layout.Zone z = Sky.worlds().zoneAt(l);
            if (z == null || !z.flags().contains(Layout.Zone.BUILD)) return false;
            if ("private_island".equals(island) && Sky.has(Features.PrivateIsland.class)) {
                return Sky.service(Features.PrivateIsland.class).canBuild(p, l);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        ItemStack hand = e.getItem();
        if (hand == null || e.getClickedBlock() == null) return;
        String id = Sky.items().idOf(hand);
        Def d = id == null ? null : defs.get(id);
        if (d == null) return;
        // Like any block: clicking a usable block (a crafting table, a door) uses it unless sneaking. A furniture
        // block is usable only when its piece gives it a use (a station block, storage, a showcase...).
        Block clicked = e.getClickedBlock();
        Hit onPiece = find(clicked);
        boolean usable = onPiece == null ? clicked.getType().isInteractable() : usesBlock(onPiece, clicked);
        if (!e.getPlayer().isSneaking() && usable) {
            e.setUseItemInHand(Event.Result.DENY);
            return;
        }
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        Player p = e.getPlayer();
        if (!mod.dials.furnitureEnabled.get()) { Msg.error(p, "Furniture is disabled right now."); return; }
        Block origin = e.getClickedBlock().getRelative(e.getBlockFace());
        place(p, d, origin, p.getFacing(), true);
    }

    /** Place {@code d} at {@code origin}. Returns null on success, else the reason (also told to the player). */
    String place(Player p, Def d, Block origin, BlockFace facing, boolean consume) {
        StructureRotation rot = rotationFor(facing);
        List<Block> targets = new ArrayList<>();
        for (int[] o : d.offsets) {
            int[] xz = rotate(o[0], o[2], rot);
            targets.add(origin.getRelative(xz[0], o[1], xz[1]));
        }
        for (Block b : targets) {
            if (b.getY() >= b.getWorld().getMaxHeight() || b.getY() < b.getWorld().getMinHeight()) return fail(p, "There's no room here!");
            if (!(b.isEmpty() || b.isReplaceable())) return fail(p, "There's not enough room to place this here!");
            Hit ghost = findAny(b);
            if (ghost != null) {
                // A record whose blocks are all gone (island rebuilt, admin build mode) is dropped, not obeyed.
                if (liveCount(ghost.placed(), b.getWorld()) == 0) drop(ghost);
                else return fail(p, "There's furniture in the way!");
            }
            if (!canBuild(p, b.getLocation())) return fail(p, "You can't place furniture here!");
        }
        for (int[] s : d.space) {        // room the piece needs but does not build (the showcase's armour stand)
            int[] xz = rotate(s[0], s[2], rot);
            Block b = origin.getRelative(xz[0], s[1], xz[1]);
            if (!(b.isEmpty() || b.isReplaceable()) || findAny(b) != null) return fail(p, "There's not enough room to place this here!");
        }
        if (consume) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!d.id.equals(Sky.items().idOf(hand))) return fail(p, "Hold the furniture to place it!");
            if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
            else { hand.setAmount(hand.getAmount() - 1); p.getInventory().setItemInMainHand(hand); }
        }
        List<int[]> abs = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Block b = targets.get(i);
            String data = d.data.get(i);
            try {
                BlockData bd;
                if (data.equals("HEAD")) {
                    bd = Bukkit.createBlockData(Material.PLAYER_HEAD);
                    if (bd instanceof Rotatable r) r.setRotation(BlockFace.SOUTH);
                } else {
                    bd = Bukkit.createBlockData("minecraft:" + data);
                }
                bd.rotate(rot);
                b.setBlockData(bd, false);
                if (data.equals("HEAD") && d.skull != null) {
                    BlockState st = b.getState();
                    if (st instanceof Skull sk) {
                        PlayerProfile prof = Bukkit.createProfile(UUID.nameUUIDFromBytes(d.skull.getBytes(StandardCharsets.UTF_8)), "furniture");
                        prof.setProperty(new ProfileProperty("textures", d.skull));
                        sk.setPlayerProfile(prof);
                        sk.update(true, false);
                    }
                }
            } catch (RuntimeException ex) {
                Sky.log().warning("[crafting] furniture " + d.id + " block '" + data + "' failed: " + ex.getMessage());
            }
            abs.add(new int[]{b.getX(), b.getY(), b.getZ()});
        }
        List<int[]> seats = new ArrayList<>();
        for (int[] s : d.seats) {
            int[] xz = rotate(s[0], s[2], rot);
            seats.add(new int[]{origin.getX() + xz[0], origin.getY() + s[1], origin.getZ() + xz[1]});
        }
        Placed placed = new Placed(d.id, p == null ? "" : p.getUniqueId().toString(), abs, seats,
                UUID.randomUUID().toString(), facing == null ? "NORTH" : facing.name());
        store(origin.getChunk(), placed, true);
        if (FurnitureMath.showSlots(d.function) > 0) refreshShow(placed, origin.getWorld());
        if (p != null) {
            Msg.sound(p, Sound.BLOCK_WOOD_PLACE, 1f);
            Msg.send(p, "&aPlaced " + CraftEngine.name(d.id) + "&a! &7Break any part of it to pick it up.");
            CraftData cd = mod.engine.data(p);
            if (cd != null) cd.furniturePlaced++;
        }
        return null;
    }

    private static String fail(Player p, String why) {
        if (p != null) Msg.error(p, why);
        return why;
    }

    // ---- registry in chunk PDC ---------------------------------------------------------------------

    private static String chunkKey(Chunk c) { return c.getWorld().getName() + ":" + c.getX() + ":" + c.getZ(); }

    private List<Placed> load(Chunk c) {
        return cache.computeIfAbsent(chunkKey(c), k -> {
            List<Placed> l = new ArrayList<>();
            String raw = c.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
            if (raw == null || raw.isEmpty()) return l;
            try {
                for (JsonElement el : JsonParser.parseString(raw).getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    List<int[]> b = new ArrayList<>(), s = new ArrayList<>();
                    for (JsonElement x : o.getAsJsonArray("b")) b.add(gson.fromJson(x, int[].class));
                    if (o.has("s")) for (JsonElement x : o.getAsJsonArray("s")) s.add(gson.fromJson(x, int[].class));
                    l.add(new Placed(o.get("id").getAsString(), o.has("o") ? o.get("o").getAsString() : "", b, s,
                            o.has("u") ? o.get("u").getAsString() : null, o.has("f") ? o.get("f").getAsString() : "NORTH"));
                }
            } catch (RuntimeException ex) {
                Sky.log().warning("[crafting] bad furniture data in chunk " + k + ": " + ex.getMessage());
            }
            return l;
        });
    }

    private void save(Chunk c, List<Placed> l) {
        JsonArray arr = new JsonArray();
        for (Placed pl : l) {
            JsonObject o = new JsonObject();
            o.addProperty("id", pl.id());
            o.addProperty("o", pl.owner());
            o.add("b", gson.toJsonTree(pl.blocks()));
            o.add("s", gson.toJsonTree(pl.seats()));
            if (pl.pid() != null) o.addProperty("u", pl.pid());
            if (pl.facing() != null) o.addProperty("f", pl.facing());
            arr.add(o);
        }
        if (arr.isEmpty()) c.getPersistentDataContainer().remove(KEY);
        else c.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, arr.toString());
        mark(c, !arr.isEmpty());
    }

    private void store(Chunk c, Placed pl, boolean add) {
        List<Placed> l = load(c);
        if (add) l.add(pl); else l.remove(pl);
        save(c, l);
    }

    /** The piece's storage id, giving a pre-wave-5 record one (saved to its chunk) the first time it is needed. */
    private String ensurePid(Hit h) {
        Placed pl = h.placed();
        if (pl.pid() != null) return pl.pid();
        List<Placed> l = load(h.chunk());
        Placed withId = new Placed(pl.id(), pl.owner(), pl.blocks(), pl.seats(), UUID.randomUUID().toString(),
                pl.facing() == null ? "NORTH" : pl.facing());
        for (int i = 0; i < l.size(); i++) if (l.get(i) == pl) { l.set(i, withId); break; }
        save(h.chunk(), l);
        return withId.pid();
    }

    /**
     * Drop a record whose blocks are gone (an island rebuild, admin build mode). Wave 5: its displays go, and the
     * items it held are orphaned - the owner gets them back on their next login (FurnitureStore.claimOrphans).
     */
    private void drop(Hit ghost) {
        Placed pl = ghost.placed();
        store(ghost.chunk(), pl, false);
        if (pl.pid() != null) {
            removeShow(pl.pid());
            if (holdsItems(defs.get(pl.id()))) store.orphan(pl.pid());
        }
    }

    /** The placed piece this block belongs to (searching its chunk and the 8 around it), with its chunk and index. */
    private record Hit(Placed placed, Chunk chunk, int index) {}

    private final Map<String, Material[]> expected = new HashMap<>();

    /** The block material each position of a piece should hold (rotation never changes a material). */
    private Material[] expectedOf(String id) {
        return expected.computeIfAbsent(id, k -> {
            Def d = defs.get(k);
            if (d == null) return new Material[0];
            Material[] m = new Material[d.data.size()];
            for (int i = 0; i < m.length; i++) {
                String data = d.data.get(i);
                try { m[i] = data.equals("HEAD") ? Material.PLAYER_HEAD : Bukkit.createBlockData("minecraft:" + data).getMaterial(); }
                catch (RuntimeException ex) { m[i] = null; }
            }
            return m;
        });
    }

    /** Whether the block at position {@code i} of a piece is still that piece's block (not air, not a block placed since). */
    private boolean holds(Placed pl, int i, Block b) {
        Material[] m = expectedOf(pl.id());
        if (i < 0 || i >= m.length || m[i] == null) return !b.isEmpty();          // unknown def: trust anything solid
        Material t = b.getType();
        if (m[i] == Material.PLAYER_HEAD) return t == Material.PLAYER_HEAD || t == Material.PLAYER_WALL_HEAD;
        return t == m[i];
    }

    private int liveCount(Placed pl, World w) {
        int n = 0;
        for (int i = 0; i < pl.blocks().size(); i++) {
            int[] x = pl.blocks().get(i);
            if (holds(pl, i, w.getBlockAt(x[0], x[1], x[2]))) n++;
        }
        return n;
    }

    /**
     * The piece whose block this IS. A record whose block at this spot was replaced (an island rebuild, admin
     * build mode) does not count: otherwise breaking a plain block placed there later would hand back the
     * whole piece and wipe the other positions.
     */
    private Hit find(Block b) {
        Hit h = findAny(b);
        return h != null && holds(h.placed(), h.index(), b) ? h : null;
    }

    /** Any record covering this position, live or stale. */
    private Hit findAny(Block b) {
        if (b == null) return null;
        World w = b.getWorld();
        java.util.Set<Long> m = marked.get(w.getUID());
        if (m == null || m.isEmpty()) return null;                 // the common case: no furniture in this world
        int cx = b.getX() >> 4, cz = b.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (!m.contains(Chunk.getChunkKey(cx + dx, cz + dz))) continue;
            if (!w.isChunkLoaded(cx + dx, cz + dz)) continue;
            Chunk c = w.getChunkAt(cx + dx, cz + dz);
            for (Placed pl : load(c)) {
                List<int[]> bl = pl.blocks();
                for (int i = 0; i < bl.size(); i++) {
                    int[] x = bl.get(i);
                    if (x[0] == b.getX() && x[1] == b.getY() && x[2] == b.getZ()) return new Hit(pl, c, i);
                }
            }
        }
        return null;
    }

    boolean isFurniture(Block b) { return find(b) != null; }

    /** Remove a placed piece; true when it was found. */
    boolean pickUp(Player p, Block b, boolean giveBack) {
        Hit h = find(b);
        if (h == null) return false;
        World w = b.getWorld();
        // Anyone sitting on this piece stands up first (their seat is about to vanish).
        for (Map.Entry<UUID, Entity> en : new ArrayList<>(sitting.entrySet())) {
            Location sl = en.getValue().getLocation();
            if (sl.getWorld() != w) continue;
            for (int[] s : h.placed().seats()) {
                if (sl.getBlockX() == s[0] && sl.getBlockY() == s[1] && sl.getBlockZ() == s[2]) { en.getValue().eject(); break; }
            }
        }
        List<int[]> bl = h.placed().blocks();
        List<ItemStack> inside = new ArrayList<>();
        for (int i = 0; i < bl.size(); i++) {
            int[] x = bl.get(i);
            Block t = w.getBlockAt(x[0], x[1], x[2]);
            if (!holds(h.placed(), i, t)) continue;                             // never a block placed there since
            // Wave 5: a station's real container (Furnace+'s furnace) hands its contents back instead of eating them.
            try {
                if (t.getState(false) instanceof Container c) {
                    Inventory inv = c.getInventory();
                    for (ItemStack s : inv.getContents()) if (s != null && !s.getType().isAir()) inside.add(s.clone());
                    inv.clear();
                }
            } catch (RuntimeException ignored) {}
            t.setType(Material.AIR, false);
        }
        store(h.chunk(), h.placed(), false);
        if (h.placed().pid() != null) { removeShow(h.placed().pid()); store.forget(h.placed().pid()); }
        for (ItemStack s : inside) {
            if (p != null) Sky.loot().give(p, s, LootSource.STORAGE);
            else w.dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), s);
        }
        if (giveBack && p != null && Sky.items().exists(h.placed().id())) {
            Sky.loot().give(p, h.placed().id(), 1, LootSource.OTHER);
            Msg.sound(p, Sound.ENTITY_ITEM_PICKUP, 1f);
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent e) {
        Hit h = find(e.getBlock());
        if (h == null) {
            // A torch, flower pot or carpet would pop off as a loose vanilla item and leave a ghost record
            // behind (and the rest of the piece would still hand back the whole item): the block a piece
            // stands on cannot be broken while the piece is there.
            if (find(e.getBlock().getRelative(BlockFace.UP)) != null) {
                e.setCancelled(true);
                Msg.error(e.getPlayer(), "Pick up the furniture on top of this block first!");
            }
            return;
        }
        e.setCancelled(true);
        e.setDropItems(false);
        Player p = e.getPlayer();
        if (!canBuild(p, e.getBlock().getLocation())) { Msg.error(p, "You can't pick up furniture here!"); return; }
        Def d = defs.get(h.placed().id());
        if (!holdsItems(d)) { pickUp(p, e.getBlock(), true); return; }
        // Wave 5: storage and showcases come up only EMPTY and only when nobody has them open (the contents live in
        // the table, keyed by this piece; a piece picked up full would orphan or duplicate them).
        String pid = ensurePid(h);
        if (store.inUse(pid)) { Msg.error(p, "Someone is using this " + Text.strip(CraftEngine.name(d.id)) + "!"); return; }
        Block b = e.getBlock();
        store.load(pid, h.placed().owner(), m -> {
            Hit again = find(b);
            if (again == null || !pid.equals(again.placed().pid()) || !p.isOnline()) return;
            if (store.inUse(pid)) { Msg.error(p, "Someone is using this furniture!"); return; }
            if (!m.isEmpty()) {
                Msg.error(p, FurnitureMath.showSlots(d.function) > 0 ? "Take the items off this showcase before picking it up!"
                        : "Empty this storage before picking it up!");
                return;
            }
            pickUp(p, b, true);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        Hit h = find(b);
        if (h == null) return;
        Def d = defs.get(h.placed().id());
        // Wave 5: a station's working block (Crafting Table+'s table, Furnace+'s furnace, the brewing stand, the
        // enchanting table and anvil of Brewing+/Enchanting+/Blacksmith+) is answered by whoever owns that block:
        // this module's crafting grid, the vanilla furnace, alchemy, enchanting.
        if (isStationBlock(d, b)) return;
        if (e.getHand() != EquipmentSlot.HAND) { e.setUseInteractedBlock(Event.Result.DENY); return; }   // off-hand pass
        // Every other block of a piece is never used as its vanilla self (a barrel that could be filled would delete
        // its contents on pick-up; a bed would set a vanilla respawn): the piece's own use runs instead.
        e.setUseInteractedBlock(Event.Result.DENY);
        ItemStack hand = e.getItem();
        // Sneaking with a block (or a furniture item) places against the piece, like on any usable block.
        if (e.getPlayer().isSneaking() && hand != null && hand.getType().isBlock() && !hand.getType().isAir()) return;
        if (!usesBlock(h, b)) return;                                          // cosmetic: onPlace may place against it
        e.setUseItemInHand(Event.Result.DENY);
        use(e.getPlayer(), h, d, b);
    }

    private static boolean isStationBlock(Def d, Block b) {
        String want = d == null ? null : FurnitureMath.stationBlock(d.function);
        if (want == null) return false;
        Material t = b.getType();
        if (want.equals("ANVIL")) return t == Material.ANVIL || t == Material.CHIPPED_ANVIL || t == Material.DAMAGED_ANVIL;
        return t.name().equals(want);
    }

    private static boolean onSeat(Hit h, Block b) {
        for (int[] s : h.placed().seats()) if (s[0] == b.getX() && s[1] == b.getY() && s[2] == b.getZ()) return true;
        return false;
    }

    /** Whether right-clicking this block of the piece does something (else it is decoration). */
    private boolean usesBlock(Hit h, Block b) {
        Def d = defs.get(h.placed().id());
        if (d == null) return onSeat(h, b);
        return switch (FurnitureMath.kind(d.function)) {
            case "station" -> isStationBlock(d, b);
            case "storage", "showcase", "bed", "water", "clock" -> true;
            default -> onSeat(h, b);
        };
    }

    /** The piece's own use (wave 5): sit, open its storage or showcase, set spawn, fill a bucket, read the time. */
    private void use(Player p, Hit h, Def d, Block b) {
        String kind = d == null ? "cosmetic" : FurnitureMath.kind(d.function);
        if (onSeat(h, b)) { sit(p, b); return; }
        switch (kind) {
            case "storage" -> {
                if (!canBuild(p, b.getLocation())) { Msg.error(p, "You can't open this!"); return; }
                openStorage(p, b, 0);
            }
            case "showcase" -> {
                if (!canBuild(p, b.getLocation())) { Msg.error(p, "You can't change this showcase!"); return; }
                openShowcase(p, b);
            }
            case "bed" -> {
                if (!canBuild(p, b.getLocation()) || !"private_island".equals(Sky.worlds().islandOf(b.getWorld()))) {
                    Msg.error(p, "You can only set your spawn on your own island!");
                    return;
                }
                if (!Sky.commands().exists("setspawn") || !Sky.commands().run(p, "setspawn")) Msg.error(p, "Setting a spawn point is not available.");
            }
            case "water" -> fillWater(p);
            case "clock" -> {
                try {
                    com.skyclone.api.time.SkyDate now = Sky.calendar().now();
                    Msg.send(p, "&eThe clock reads &a" + now.timeLabel() + "&e, " + now.dayLabel() + ", Year " + now.year() + ".");
                } catch (RuntimeException ex) {
                    Msg.send(p, "&eThe clock ticks quietly.");
                }
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 1.2f);
            }
            default -> { }
        }
    }

    /** Water Trough: "an infinite source of water for buckets and bottles". Only plain vanilla containers. */
    private void fillWater(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) { Msg.send(p, "&7Hold an empty bucket or glass bottle to fill it."); return; }
        String id = Sky.items().idOf(hand);
        ItemStack out;
        if (hand.getType() == Material.BUCKET && java.util.Objects.equals(id, Sky.items().vanillaId(Material.BUCKET))) {
            out = vanilla(Material.WATER_BUCKET);
        } else if (hand.getType() == Material.GLASS_BOTTLE && java.util.Objects.equals(id, Sky.items().vanillaId(Material.GLASS_BOTTLE))) {
            out = new ItemStack(Material.POTION);
            if (out.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                pm.setBasePotionType(org.bukkit.potion.PotionType.WATER);
                out.setItemMeta(pm);
            }
        } else {
            Msg.send(p, "&7Hold an empty bucket or glass bottle to fill it.");
            return;
        }
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else { hand.setAmount(hand.getAmount() - 1); p.getInventory().setItemInMainHand(hand); }
        Sky.loot().give(p, out, LootSource.OTHER);
        Msg.sound(p, Sound.ITEM_BUCKET_FILL, 1f);
    }

    private static ItemStack vanilla(Material m) {
        try {
            String id = Sky.items().vanillaId(m);
            if (id != null && Sky.items().exists(id)) return Sky.items().create(id, 1);
        } catch (RuntimeException ignored) {}
        return new ItemStack(m);
    }

    private void sit(Player p, Block seat) {
        if (p.isInsideVehicle() || sitting.containsKey(p.getUniqueId())) return;
        Location at = seat.getLocation().add(0.5, 0.3, 0.5);
        for (Entity en : seat.getWorld().getNearbyEntities(at, 0.4, 0.6, 0.4)) {
            if (en instanceof ArmorStand && sitting.containsValue(en)) { Msg.error(p, "Someone is already sitting there!"); return; }
        }
        at.setYaw(p.getLocation().getYaw());
        ArmorStand stand = seat.getWorld().spawn(at, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setGravity(false);
            s.setSmall(true);
            s.setPersistent(false);
            s.setInvulnerable(true);
        });
        stand.addPassenger(p);
        sitting.put(p.getUniqueId(), stand);
    }

    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Entity stand = sitting.remove(p.getUniqueId());
        if (stand != null) {
            Location seat = stand.getLocation();
            stand.remove();
            Location up = seat.clone().add(0, 0.6, 0);
            up.setYaw(p.getLocation().getYaw());
            up.setPitch(p.getLocation().getPitch());
            // Only lift a player who is still at the seat: a dismount caused by a warp must never pull
            // them back to the chair a tick later.
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                if (!p.isOnline() || p.isInsideVehicle()) return;
                Location now = p.getLocation();
                if (now.getWorld() == up.getWorld() && now.distanceSquared(up) < 4) p.teleport(up);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Entity stand = sitting.remove(e.getPlayer().getUniqueId());
        if (stand != null) stand.remove();
    }

    void shutdown() {
        for (Entity en : sitting.values()) en.remove();
        sitting.clear();
        store.kickAll();          // open storage menus close (and save) before the database goes away
        store.flush();
        for (String pid : new ArrayList<>(shows.keySet())) removeShow(pid);
    }

    int showCount() { return shows.size(); }

    // ---- wave 5: storage furniture ("You can store three pages of items in this chest!") ------------------

    /** Open page {@code page} of the storage piece at {@code b} (async the first time: the contents load from the table). */
    void openStorage(Player p, Block b, int page) {
        Hit h = find(b);
        if (h == null) return;
        Def d = defs.get(h.placed().id());
        int pages = d == null ? 0 : FurnitureMath.pages(d.function);
        if (pages <= 0) return;
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading."); return; }
        String pid = ensurePid(h);
        String owner = h.placed().owner();
        store.load(pid, owner, m -> {
            Hit again = find(b);                                   // still there, still the same piece?
            if (again == null || !pid.equals(again.placed().pid()) || !p.isOnline()) return;
            int pg = Math.max(0, Math.min(pages - 1, page));
            Menu menu = new Menu(Text.strip(CraftEngine.name(d.id)) + (pages > 1 ? " (" + (pg + 1) + "/" + pages + ")" : ""), 6)
                    .keepFreeSlotItems();
            if (!store.claim(pid, menu, p)) { Msg.error(p, "Someone else is using this right now!"); return; }
            Inventory inv = menu.getInventory();
            for (int i = 0; i < FurnitureMath.PAGE; i++) {
                String raw = m.get(FurnitureMath.pageKey(pg, i));
                ItemStack it = FurnitureStore.decode(raw);
                if (raw != null && it == null) {
                    menu.set(i, Icons.of(Material.BARRIER, "&cUnreadable item", "&7The item stored here could not be loaded.",
                            "&7It is kept untouched. Tell an admin."));
                    continue;
                }
                menu.free(i);
                inv.setItem(i, it);
            }
            for (int i = 45; i < 54; i++) menu.set(i, Icons.filler());
            menu.closeButton();
            if (pg > 0) menu.set(45, Icons.prevPage(pg + 1), c -> openStorage(c.player(), b, pg - 1));
            if (pg < pages - 1) menu.set(53, Icons.nextPage(pg + 1), c -> openStorage(c.player(), b, pg + 1));
            menu.set(48, Icons.of(Material.CHEST, "&a" + Text.strip(CraftEngine.name(d.id)),
                    "&7Page &a" + (pg + 1) + " &7of &a" + pages + "&7.", "&7Shared by everyone who can build here.",
                    "", "&8Empty it to pick the furniture up."));
            int[] o = again.placed().blocks().get(0);
            String world = b.getWorld().getName();
            Runnable sync = () -> {
                for (int i = 0; i < FurnitureMath.PAGE; i++) {
                    if (!menu.freeSlots().contains(i)) continue;
                    String enc = FurnitureStore.encode(inv.getItem(i));
                    String key = FurnitureMath.pageKey(pg, i);
                    if (enc == null) m.remove(key); else m.put(key, enc);
                }
            };
            menu.onFreeSlotChange(c -> sync.run());
            menu.onClose(pl -> {
                sync.run();
                store.save(pid, owner, d.id, world, o[0], o[1], o[2]);
                store.release(pid, menu);
            });
            menu.open(p);
            Msg.sound(p, Sound.BLOCK_CHEST_OPEN, 1f);
        });
    }

    // ---- wave 5: showcases (Armor Stand, Weapon Rack, Weapon Rack+) ----------------------------------------

    /** Showcase slot -&gt; menu slot. */
    private static int[] showMenuSlots(String function) {
        int n = FurnitureMath.showSlots(function);
        if ("showcase:armor".equals(function)) return new int[]{11, 12, 13, 14, 16};
        if ("showcase:best".equals(function)) return new int[]{13};
        int[] s = new int[n];
        int start = 13 - n / 2;
        for (int i = 0; i < n; i++) s[i] = start + i;
        return s;
    }

    void openShowcase(Player p, Block b) {
        Hit h = find(b);
        if (h == null) return;
        Def d = defs.get(h.placed().id());
        int n = d == null ? 0 : FurnitureMath.showSlots(d.function);
        if (n <= 0) return;
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading."); return; }
        String pid = ensurePid(h);
        String owner = h.placed().owner();
        store.load(pid, owner, m -> {
            Hit again = find(b);
            if (again == null || !pid.equals(again.placed().pid()) || !p.isOnline()) return;
            Menu menu = new Menu(Text.strip(CraftEngine.name(d.id)), 3).keepFreeSlotItems();
            if (!store.claim(pid, menu, p)) { Msg.error(p, "Someone else is using this right now!"); return; }
            Inventory inv = menu.getInventory();
            int[] slots = showMenuSlots(d.function);
            for (int i = 0; i < 27; i++) menu.set(i, Icons.filler());
            for (int k = 0; k < slots.length; k++) {
                String raw = m.get(FurnitureMath.showKey(k));
                ItemStack it = FurnitureStore.decode(raw);
                if (raw != null && it == null) { menu.set(slots[k], Icons.of(Material.BARRIER, "&cUnreadable item")); continue; }
                menu.free(slots[k]);
                inv.setItem(slots[k], it);
            }
            List<String> help = new ArrayList<>();
            if ("showcase:armor".equals(d.function)) {
                help.add("&7Helmet, chestplate, leggings and");
                help.add("&7boots go left to right, a sword,");
                help.add("&7pickaxe or axe on the right.");
            } else {
                help.add("&7Put " + (n == 1 ? "your best sword, pickaxe or axe" : "swords, pickaxes or axes") + " here");
                help.add("&7to show them off" + ("showcase:best".equals(d.function) ? " with their stats." : "."));
            }
            help.add("");
            help.add("&8Empty it to pick the furniture up.");
            menu.set(4, Icons.of(Material.ARMOR_STAND, "&a" + Text.strip(CraftEngine.name(d.id)), help));
            menu.set(22, Icons.close(), c -> c.player().closeInventory());
            int[] o = again.placed().blocks().get(0);
            String world = b.getWorld().getName();
            Placed piece = again.placed();
            menu.onClose(pl -> {
                for (int k = 0; k < slots.length; k++) {
                    if (!menu.freeSlots().contains(slots[k])) continue;
                    ItemStack it = inv.getItem(slots[k]);
                    if (it != null && !it.getType().isAir() && !FurnitureMath.accepts(d.function, k, categoryOf(it))) {
                        inv.setItem(slots[k], null);
                        for (ItemStack left : pl.getInventory().addItem(it).values()) pl.getWorld().dropItemNaturally(pl.getLocation(), left);
                        Msg.error(pl, "That doesn't go on this showcase, so it went back to your inventory.");
                        it = null;
                    }
                    String enc = FurnitureStore.encode(it);
                    if (enc == null) m.remove(FurnitureMath.showKey(k)); else m.put(FurnitureMath.showKey(k), enc);
                }
                store.save(pid, owner, d.id, world, o[0], o[1], o[2]);
                store.release(pid, menu);
                refreshShow(piece, b.getWorld());
            });
            menu.open(p);
        });
    }

    private static ItemCategory categoryOf(ItemStack it) {
        try {
            ItemDef def = Sky.items().def(Sky.items().idOf(it));
            return def == null ? null : def.category;
        } catch (RuntimeException e) { return null; }
    }

    /** Re-spawn a showcase's display entities from its stored contents. Never persistent; rebuilt on chunk load. */
    void refreshShow(Placed pl, World w) {
        if (pl == null || pl.pid() == null || w == null) return;
        Def d = defs.get(pl.id());
        if (d == null || FurnitureMath.showSlots(d.function) <= 0 || pl.blocks().isEmpty()) return;
        Map<String, String> m = store.peek(pl.pid());
        if (m == null) {
            store.load(pl.pid(), pl.owner(), x -> { if (stillPlaced(pl, w)) refreshShow(pl, w); });
            return;
        }
        removeShow(pl.pid());
        int[] o = pl.blocks().get(0);
        int q = FurnitureMath.quarters(pl.facing());
        float yaw = FurnitureMath.frontYaw(pl.facing());
        List<Entity> made = new ArrayList<>();
        try {
            if ("showcase:armor".equals(d.function)) {
                Location at = local(w, o, q, 0, 0.5, 0, yaw);
                ArmorStand st = w.spawn(at, ArmorStand.class, s -> {
                    s.setArms(true);
                    s.setBasePlate(false);
                    s.setMarker(true);
                    s.setGravity(false);
                    s.setInvulnerable(true);
                    s.setPersistent(false);
                    s.setCanPickupItems(false);
                    tagDisplay(s);
                });
                ItemStack[] armor = new ItemStack[4];
                for (int k = 0; k < 4; k++) armor[k] = FurnitureStore.decode(m.get(FurnitureMath.showKey(k)));
                if (st.getEquipment() != null) {
                    st.getEquipment().setHelmet(armor[0]);
                    st.getEquipment().setChestplate(armor[1]);
                    st.getEquipment().setLeggings(armor[2]);
                    st.getEquipment().setBoots(armor[3]);
                    st.getEquipment().setItemInMainHand(FurnitureStore.decode(m.get(FurnitureMath.showKey(4))));
                }
                made.add(st);
            } else if ("showcase:best".equals(d.function)) {
                ItemStack it = FurnitureStore.decode(m.get(FurnitureMath.showKey(0)));
                if (it != null) {
                    made.add(itemDisplay(w, local(w, o, q, 0, 1.25, 0.62, yaw), it, 0.75f));
                    made.add(textDisplay(w, local(w, o, q, 0, 3.0, 0, yaw), hologram(it)));
                }
            } else {
                int n = FurnitureMath.showSlots(d.function);
                for (int k = 0; k < n; k++) {
                    ItemStack it = FurnitureStore.decode(m.get(FurnitureMath.showKey(k)));
                    if (it == null) continue;
                    double x = -0.5 + (k + 0.5) * (2.0 / n);              // the rack spans two blocks
                    made.add(itemDisplay(w, local(w, o, q, x, 1.15, 0.62, yaw), it, 0.6f));
                }
            }
        } catch (RuntimeException ex) {
            Sky.log().warning("[crafting] showcase display " + pl.id() + ": " + ex);
        }
        if (!made.isEmpty()) shows.put(pl.pid(), made);
    }

    private boolean stillPlaced(Placed pl, World w) {
        int[] o = pl.blocks().get(0);
        if (!w.isChunkLoaded(o[0] >> 4, o[2] >> 4)) return false;
        Hit h = find(w.getBlockAt(o[0], o[1], o[2]));
        return h != null && pl.pid().equals(h.placed().pid());
    }

    /** A point given in the piece's own frame (x right, y up, z toward its front), from the origin block's centre. */
    private static Location local(World w, int[] o, int quarters, double x, double y, double z, float yaw) {
        double[] r = FurnitureMath.rotate(x, z, quarters);
        Location l = new Location(w, o[0] + 0.5 + r[0], o[1] + y, o[2] + 0.5 + r[1]);
        l.setYaw(yaw);
        return l;
    }

    private static void tagDisplay(Entity e) {
        try { e.getPersistentDataContainer().set(DISPLAY_KEY, PersistentDataType.BYTE, (byte) 1); } catch (RuntimeException ignored) {}
    }

    private static ItemDisplay itemDisplay(World w, Location at, ItemStack it, float scale) {
        return w.spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.FIXED);
            tagDisplay(e);
        });
    }

    private static TextDisplay textDisplay(World w, Location at, List<String> lines) {
        return w.spawn(at, TextDisplay.class, e -> {
            e.text(Text.c(String.join("\n", lines)));
            e.setBillboard(Display.Billboard.CENTER);
            e.setPersistent(false);
            e.setSeeThrough(false);
            tagDisplay(e);
        });
    }

    /** Weapon Rack+: "including a hologram with stats". */
    private static List<String> hologram(ItemStack it) {
        List<String> out = new ArrayList<>();
        ItemDef def = null;
        try { def = Sky.items().def(Sky.items().idOf(it)); } catch (RuntimeException ignored) {}
        if (def == null) { out.add("&f" + it.getType().name()); return out; }
        out.add(def.rarity.code() + Text.strip(def.name));
        if (def.damage > 0) out.add("&7Damage: &c+" + Text.commas(Math.round(def.damage)));
        int k = 0;
        for (Map.Entry<Stat, Double> e : def.stats.entrySet()) {
            if (e.getValue() == null || e.getValue() == 0) continue;
            if (k++ >= 4) break;
            out.add(e.getKey().label() + "&7: &a" + (e.getValue() > 0 ? "+" : "") + Text.commas(Math.round(e.getValue())));
        }
        return out;
    }

    void removeShow(String pid) {
        List<Entity> l = pid == null ? null : shows.remove(pid);
        if (l != null) for (Entity e : l) { try { e.remove(); } catch (RuntimeException ignored) {} }
    }

    /** Displays of a chunk's showcases come back; a storage piece whose blocks are all gone gives its items back. */
    private void settle(Chunk c) {
        if (!c.getPersistentDataContainer().has(KEY, PersistentDataType.STRING)) return;
        World w = c.getWorld();
        for (Placed pl : new ArrayList<>(load(c))) {
            Def d = defs.get(pl.id());
            if (d == null) continue;
            if (pl.pid() != null && holdsItems(d) && liveCount(pl, w) == 0) {
                drop(new Hit(pl, c, 0));
                continue;
            }
            if (FurnitureMath.showSlots(d.function) > 0 && pl.pid() != null) refreshShow(pl, w);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent e) {
        if (e.getRightClicked().getPersistentDataContainer().has(DISPLAY_KEY, PersistentDataType.BYTE)) e.setCancelled(true);
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) store.claimOrphans(p); }, 60L);
    }

    // ---- world protection: furniture never turns into loose vanilla blocks --------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (find(b) != null) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (find(b) != null) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> find(b) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> find(b) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (find(e.getBlock()) != null) e.setCancelled(true); }

    /** Water or lava would wash a torch, pot or carpet off as a loose item. */
    @EventHandler(ignoreCancelled = true)
    public void onFlow(org.bukkit.event.block.BlockFromToEvent e) { if (find(e.getToBlock()) != null) e.setCancelled(true); }

    /** Furniture containers are decoration: a hopper must not fill them (their contents vanish on pick-up). */
    @EventHandler(ignoreCancelled = true)
    public void onHopper(org.bukkit.event.inventory.InventoryMoveItemEvent e) {
        Location to = e.getDestination().getLocation();
        Location from = e.getSource().getLocation();
        if ((to != null && to.getWorld() != null && find(to.getBlock()) != null)
                || (from != null && from.getWorld() != null && find(from.getBlock()) != null)) e.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent e) {
        if (e.isNewChunk()) return;
        Chunk c = e.getChunk();
        markIfStored(c);
        if (marked.getOrDefault(c.getWorld().getUID(), java.util.Set.of()).contains(c.getChunkKey())) {
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (c.isLoaded()) settle(c); });
        }
    }

    /** Drop the in-memory chunk cache entry when the chunk unloads (the PDC is the truth). */
    @EventHandler
    public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent e) {
        List<Placed> l = cache.remove(chunkKey(e.getChunk()));
        if (l != null) for (Placed pl : l) if (pl.pid() != null) removeShow(pl.pid());
        mark(e.getChunk(), false);
    }
}
