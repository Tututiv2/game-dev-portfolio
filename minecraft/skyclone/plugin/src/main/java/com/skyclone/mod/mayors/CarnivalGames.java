package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.TropicalFish;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The three Carnival booths, played IN THE WORLD on the Carnival Grounds ({@link CarnivalGroundsGenerator}):
 * <ul>
 *   <li><b>Fruit Digging</b>: walk onto the sand patch with the Carnival Shovel and dig squares (left-click);
 *       right-click cycles the Dowsing Mode. What you uncover is drawn for you alone with block changes and floating
 *       labels, so a crowd can dig the same patch at once.</li>
 *   <li><b>Zombie Shootout</b>: you are held on the firing stand; zombies walk the two platforms and lamps light on the
 *       back wall. Right-click the Dart Tube to fire (a ray, drawn as a trail). Zombies are real entities shown only to
 *       their shooter.</li>
 *   <li><b>Catch a Fish</b>: you are held on the dock; cast the Carnival Rod near a fish to hook it, then hold
 *       right-click to reel until its bar fills. The fish are real tropical fish shown only to you.</li>
 * </ul>
 * The rules live in the pure classes {@link FruitDig}, {@link ShootRound} and {@link FishRound}; this class only
 * moves entities, draws blocks and reads clicks. Sneak twice to stop early. A game in progress is paid out (points
 * so far) when the player quits, leaves the grounds, the Carnival closes or the server stops.
 */
final class CarnivalGames implements Listener {

    static final String ISLAND = CarnivalGroundsGenerator.ID;
    private static final long FIRE_COOLDOWN_MS = 250;   // TUNE: Phwomp at most 4 darts a second
    private static final double DART_RANGE = 48;

    private final MayorsModule mod;
    private final Carnival carnival;
    private final NamespacedKey propKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask task;
    private long ticks;

    CarnivalGames(MayorsModule mod, Carnival carnival) {
        this.mod = mod;
        this.carnival = carnival;
        this.propKey = new NamespacedKey(Sky.plugin(), "mayors_carnival_prop");
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 20L, 2L);
        // props left behind by a crash: tagged, never persistent, but clear them anyway once the world is up
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), this::sweepProps, 200L);
    }

    void shutdown() {
        for (UUID u : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(u);
            Session s = sessions.get(u);
            if (p != null && s != null) finish(p, s, "The Carnival packed up for the night.");
        }
        sessions.clear();
        if (task != null) task.cancel();
    }

    Session session(UUID u) { return sessions.get(u); }

    int playing() { return sessions.size(); }

    // =============================================================================================== start / stop

    /** Start a booth for {@code p}. Pays the tickets; false (with the reason said) when it cannot start. */
    boolean start(Player p, CarnivalRules.Game game) {
        if (!carnival.active()) { Msg.error(p, "The Carnival is not in town right now."); return false; }
        if (sessions.containsKey(p.getUniqueId())) { Msg.error(p, "You are already playing! Sneak twice to stop."); return false; }
        if (!ISLAND.equals(Sky.worlds().islandOf(p.getWorld())) || !Sky.worlds().isBuilt(ISLAND)) {
            Msg.error(p, "The booths are on the Carnival Grounds. &e/warp carnival");
            return false;
        }
        if (p.getInventory().firstEmpty() < 0) { Msg.error(p, "Make room in your inventory for the game's tool first."); return false; }
        Session s;
        try {
            s = switch (game) {
                case FRUIT -> new FruitSession(p);
                case SHOOT -> new ShootSession(p);
                case FISH -> new FishSession(p);
            };
        } catch (IllegalStateException e) {
            Msg.error(p, e.getMessage());
            return false;
        }
        if (!carnival.payTickets(p)) return false;
        sessions.put(p.getUniqueId(), s);
        giveTool(p, s.tool);
        if (s.stand != null) p.teleport(s.stand);
        s.begin(p);
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f);
        return true;
    }

    /** Stop early (command, sneak twice): pays what was scored. */
    void stop(Player p, String reason) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null) finish(p, s, reason);
    }

    private void finish(Player p, Session s, String reason) {
        if (s.paid) return;
        s.paid = true;
        sessions.remove(p.getUniqueId());
        try { s.cleanup(p); } catch (Throwable t) { Sky.log().warning("[mayors] carnival cleanup failed: " + t); }
        takeTools(p);
        carnival.finishGame(p, s.game, s.score(), s.stats(), reason);
    }

    // =============================================================================================== ticking

    private void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        boolean open = carnival.active();
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p == null) { sessions.remove(s.player); continue; }
            try {
                if (!open) { finish(p, s, "The Carnival closed."); continue; }
                if (!ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) { finish(p, s, "You left the Carnival Grounds."); continue; }
                s.tick(p, now);
                if (s.over(now)) { finish(p, s, s.endReason(now)); continue; }
                if (ticks % 10 == 0) Sky.vitals().flash(p, s.hud(now), 25);
            } catch (Throwable t) {
                Sky.log().log(java.util.logging.Level.WARNING, "[mayors] carnival game tick threw", t);
                finish(p, s, "Something went wrong with the booth.");
            }
        }
    }

    /** The sidebar lines while playing (reference scoreboard: game title, Score, Fruits / Kills + Accuracy / Catch Streak). */
    List<String> sidebar(Player p) {
        Session s = sessions.get(p.getUniqueId());
        return s == null ? List.of() : s.sidebar(System.currentTimeMillis());
    }

    // =============================================================================================== tools

    private static void giveTool(Player p, String id) {
        ItemStack tool = Sky.items().create(id);
        PlayerInventory inv = p.getInventory();
        int slot = inv.firstEmpty();
        if (slot < 0) { Sky.loot().give(p, tool, com.skyclone.api.loot.LootSource.EVENT); return; }
        if (slot < 9) {
            inv.setItem(slot, tool);
            inv.setHeldItemSlot(slot);
        } else {
            int held = inv.getHeldItemSlot();
            ItemStack old = inv.getItem(held);
            inv.setItem(held, tool);
            inv.setItem(slot, old);
        }
    }

    private static boolean isTool(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        String id = Sky.items().idOf(it);
        return "CARNIVAL_SHOVEL".equals(id) || "CARNIVAL_DART_TUBE".equals(id) || "CARNIVAL_FISHING_ROD".equals(id);
    }

    /** Remove every carnival tool the player carries (they are lent for one game only). */
    static void takeTools(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isTool(inv.getItem(i))) inv.setItem(i, null);
        if (isTool(inv.getItemInOffHand())) inv.setItemInOffHand(null);
        if (isTool(p.getItemOnCursor())) p.setItemOnCursor(null);
    }

    // =============================================================================================== props

    private <T extends Entity> T prop(Player p, Location at, Class<T> type, java.util.function.Consumer<T> setup) {
        T e = at.getWorld().spawn(at, type, ent -> {
            ent.setVisibleByDefault(false);
            ent.setPersistent(false);
            ent.setInvulnerable(true);
            ent.setSilent(true);
            ent.getPersistentDataContainer().set(propKey, PersistentDataType.BYTE, (byte) 1);
            setup.accept(ent);
        });
        p.showEntity(Sky.plugin(), e);
        return e;
    }

    private boolean isProp(Entity e) { return e != null && e.getPersistentDataContainer().has(propKey, PersistentDataType.BYTE); }

    private void sweepProps() {
        World w = Bukkit.getWorld("sb_" + ISLAND);
        if (w == null) return;
        Set<UUID> live = new HashSet<>();
        for (Session s : sessions.values()) live.addAll(s.props());
        for (Entity e : w.getEntities()) if (isProp(e) && !live.contains(e.getUniqueId())) e.remove();
    }

    private static Location anchor(String name) {
        Location l = Sky.worlds().anchor(ISLAND, name);
        if (l == null) throw new IllegalStateException("The Carnival Grounds are still being set up.");
        return l;
    }

    private static Box area(String name) {
        Box b = Sky.worlds().area(ISLAND, name);
        if (b == null) throw new IllegalStateException("The Carnival Grounds are still being set up.");
        return b;
    }

    private static TextDisplay label(TextDisplay t, String text) {
        t.text(Text.c(text));
        t.setBillboard(Display.Billboard.CENTER);
        t.setSeeThrough(false);
        t.setShadowed(true);
        return t;
    }

    // =============================================================================================== sessions

    abstract static class Session {
        final UUID player;
        final CarnivalRules.Game game;
        final String tool;
        Location stand;
        long sneakAt;
        boolean paid;

        Session(Player p, CarnivalRules.Game game, String tool) { this.player = p.getUniqueId(); this.game = game; this.tool = tool; }

        abstract void begin(Player p);
        abstract void tick(Player p, long now);
        abstract boolean over(long now);
        abstract String endReason(long now);
        abstract int score();
        abstract Map<String, Integer> stats();
        abstract List<String> sidebar(long now);
        abstract String hud(long now);
        abstract void cleanup(Player p);
        Set<UUID> props() { return Set.of(); }
    }

    // ---------------------------------------------------------------------------------------------- Fruit Digging

    final class FruitSession extends Session {
        final FruitDig dig;
        final Box patch;
        final World world;
        final long end;
        FruitDig.Mode mode = FruitDig.Mode.MINES;
        final Map<Integer, TextDisplay> labels = new HashMap<>();
        int anchorCell = -1;
        long anchorUntil, lastDig, lastMode;

        FruitSession(Player p) {
            super(p, CarnivalRules.Game.FRUIT, "CARNIVAL_SHOVEL");
            patch = area("carnival.dig");
            world = Sky.worlds().world(ISLAND);
            if (world == null) throw new IllegalStateException("The Carnival Grounds are still being set up.");
            EventData.Carnival d = carnival.data();
            dig = new FruitDig(FruitDig.layout(d.grid, System.nanoTime() ^ p.getUniqueId().getLeastSignificantBits()), Math.max(1, d.digs));
            end = System.currentTimeMillis() + Math.max(10, d.digSeconds) * 1000L;
        }

        Block block(int i) { return world.getBlockAt(patch.minX() + i % FruitDig.W, patch.minY(), patch.minZ() + i / FruitDig.W); }

        int cellAt(Block b) {
            if (b.getWorld() != world || b.getY() != patch.minY()) return -1;
            int dx = b.getX() - patch.minX(), dz = b.getZ() - patch.minZ();
            if (dx < 0 || dz < 0 || dx >= FruitDig.W || dz >= FruitDig.H) return -1;
            return dz * FruitDig.W + dx;
        }

        @Override void begin(Player p) {
            Msg.send(p, "&e[NPC] &6Captain Barnacle&f: Good luck, matey!");
            Msg.send(p, "&7Dig the sand with your &aCarnival Shovel &7(&eleft-click&7). &eRight-click &7changes the Dowsing Mode. "
                    + "&a" + dig.digsLeft + " digs&7, &a" + (end - System.currentTimeMillis()) / 60000 + " minutes&7. Sneak twice to stop.");
            Msg.send(p, "&7Dowsing Mode: " + mode.label + " &8- " + mode.about);
        }

        void onDig(Player p, int i) {
            long now = System.currentTimeMillis();
            if (now - lastDig < 150) return;
            lastDig = now;
            FruitDig.Result r = dig.dig(i, mode, carnival.data().points);
            if (r == null) { redraw(p, i); return; }
            Msg.send(p, r.line());
            if (r.dowse() != null) Msg.send(p, r.dowse());
            String c = dig.cells[i];
            if ("BOMB".equals(c) && !r.line().contains("Coconut")) {
                Location at = block(i).getLocation().add(0.5, 1, 0.5);
                p.spawnParticle(Particle.EXPLOSION, at, 1);
                p.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
            } else if (FruitDig.isFruit(c)) {
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, "DRAGONFRUIT".equals(c) ? 2f : 1.2f);
            } else {
                p.playSound(p.getLocation(), Sound.BLOCK_SAND_BREAK, 1f, 1f);
            }
            for (int j : r.changed()) redraw(p, j);
            anchorCell = r.anchorCell();
            anchorUntil = now + 4000;
        }

        void cycleMode(Player p) {
            long now = System.currentTimeMillis();
            if (now - lastMode < 300) return;
            lastMode = now;
            mode = mode.next();
            Msg.send(p, "&eDowsing Mode: " + mode.label + " &8- &7" + mode.about);
            Msg.sound(p, Sound.UI_BUTTON_CLICK, 1.4f);
        }

        String blockFor(int i) {
            if (dig.destroyed[i]) return "coarse_dirt";
            if (!dig.dug[i]) return null;
            return switch (dig.cells[i]) {
                case "DRAGONFRUIT" -> "magenta_glazed_terracotta";
                case "DURIAN" -> "lime_terracotta";
                case "MANGO" -> "orange_concrete";
                case "COCONUT" -> "brown_concrete";
                case "APPLE" -> "red_concrete";
                case "CHERRY" -> "pink_concrete";
                case "POMEGRANATE" -> "red_terracotta";
                case "WATERMELON" -> "melon";
                case "BOMB" -> "tnt";
                case "RUM" -> "barrel[facing=up]";
                default -> "rooted_dirt";
            };
        }

        String labelFor(int i) {
            if (dig.destroyed[i]) return "&8Destroyed " + FruitDig.titleCase(dig.cells[i]);
            String c = dig.cells[i];
            return switch (c) {
                case "BOMB" -> "&c&lBomb";
                case "RUM" -> "&6Rum";
                case "DIRT" -> "&7Sand";
                default -> fruitColor(c) + FruitDig.titleCase(c) + (dig.earned[i] > 0 ? " &e+" + dig.earned[i] : "");
            };
        }

        void redraw(Player p, int i) {
            String b = blockFor(i);
            Block blk = block(i);
            BlockData data = b == null ? blk.getBlockData() : Bukkit.createBlockData(b);
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (p.isOnline() && !paid) p.sendBlockChange(blk.getLocation(), data); });
            if (b != null && !labels.containsKey(i)) {
                Location at = blk.getLocation().add(0.5, 1.25, 0.5);
                labels.put(i, prop(p, at, TextDisplay.class, t -> label(t, labelFor(i))));
            }
        }

        @Override void tick(Player p, long now) {
            if (ticks % 20 == 0) for (int i = 0; i < FruitDig.CELLS; i++) if (dig.dug[i] || dig.destroyed[i]) redraw(p, i);
            if (anchorCell >= 0 && now < anchorUntil && ticks % 4 == 0) {
                Location at = block(anchorCell).getLocation().add(0.5, 1.1, 0.5);
                p.spawnParticle(Particle.HAPPY_VILLAGER, at, 6, 0.25, 0.2, 0.25, 0);
            }
            Location c = new Location(world, patch.centerX() + 0.5, patch.minY() + 1, patch.centerZ() + 0.5);
            if (p.getWorld() == world && p.getLocation().distanceSquared(c) > 16 * 16) dig.over = true;
        }

        @Override boolean over(long now) { return dig.over || now >= end; }

        @Override String endReason(long now) {
            if (now >= end) return "Time's up!";
            if (dig.endReason != null) return dig.endReason;
            return "You walked away from the patch.";
        }

        @Override int score() { return dig.score; }

        @Override Map<String, Integer> stats() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("score", dig.score);
            m.put("apples", dig.apples);
            m.put("unique", dig.unique.size());
            m.put("dragonfruit", dig.dragonfruit ? 1 : 0);
            m.put("fruits", dig.fruitsFound);
            return m;
        }

        @Override List<String> sidebar(long now) {
            return List.of("&6&lFruit Digging", "Score: &e" + Text.commasInt(dig.score), "Fruits: &a" + dig.fruitsFound + "&7/&a" + dig.totalFruit());
        }

        @Override String hud(long now) {
            return "&6Fruit Digging &7| &e" + dig.digsLeft + " digs &7| " + mode.label + " &7| &a" + Text.duration(Math.max(0, (end - now) / 1000));
        }

        @Override void cleanup(Player p) {
            for (int i = 0; i < FruitDig.CELLS; i++) {
                Block b = block(i);
                p.sendBlockChange(b.getLocation(), b.getBlockData());
            }
            for (TextDisplay t : labels.values()) t.remove();
            labels.clear();
        }

        @Override Set<UUID> props() {
            Set<UUID> s = new HashSet<>();
            for (TextDisplay t : labels.values()) s.add(t.getUniqueId());
            return s;
        }
    }

    static String fruitColor(String c) {
        return switch (c) {
            case "DRAGONFRUIT" -> "&d";
            case "DURIAN" -> "&2";
            case "MANGO" -> "&6";
            case "COCONUT" -> "&f";
            case "APPLE", "CHERRY", "POMEGRANATE" -> "&c";
            case "WATERMELON" -> "&a";
            default -> "&7";
        };
    }

    // ---------------------------------------------------------------------------------------------- Zombie Shootout

    final class ShootSession extends Session {
        final ShootRound round;
        final Box[] lanes;
        final List<Location> lamps = new ArrayList<>();
        final Map<Integer, Zombie> zombies = new HashMap<>();
        int lampShown = -1;
        long lastShot;

        ShootSession(Player p) {
            super(p, CarnivalRules.Game.SHOOT, "CARNIVAL_DART_TUBE");
            stand = anchor("carnival.shoot.stand");
            lanes = new Box[]{area("carnival.shoot.lane1"), area("carnival.shoot.lane2")};
            for (int i = 1; i <= 9; i++) {
                Location l = Sky.worlds().anchor(ISLAND, "lamp." + i);
                if (l != null) lamps.add(l);
            }
            double[] len = new double[lanes.length];
            for (int i = 0; i < lanes.length; i++) len[i] = Math.max(1, lanes[i].maxZ() - lanes[i].minZ());
            round = new ShootRound(System.currentTimeMillis(), Math.max(10, carnival.data().shootSeconds), lanes.length, len, lamps.size(),
                    System.nanoTime() ^ p.getUniqueId().getMostSignificantBits());
        }

        @Override void begin(Player p) {
            Msg.send(p, "&e[NPC] &2Sheriff Dusty&f: Good luck, pal!");
            Msg.send(p, "&7Right-click the &aDart Tube &7to shoot the zombies before they leave. Hit a &clit lamp &7for &a+100 "
                    + "&7and &ax1.2 &7points for 6 seconds. Sneak twice to stop.");
        }

        Location where(ShootRound.Target t) {
            Box b = lanes[Math.min(t.lane, lanes.length - 1)];
            double z = b.minZ() + 0.5 + t.pos * (b.maxZ() - b.minZ());
            Location l = new Location(stand.getWorld(), b.minX() + 0.5, b.minY(), z);
            l.setYaw(t.dir > 0 ? 0f : 180f);
            return l;
        }

        @Override void tick(Player p, long now) {
            round.step(now);
            for (ShootRound.Target t : round.expired) {
                Zombie z = zombies.remove(t.id);
                if (z != null) { p.spawnParticle(Particle.POOF, z.getLocation().add(0, 1, 0), 8, 0.2, 0.4, 0.2, 0.01); z.remove(); }
            }
            round.expired.clear();
            for (ShootRound.Target t : round.targets) {
                Zombie z = zombies.get(t.id);
                Location at = where(t);
                if (z == null || !z.isValid()) {
                    z = spawnZombie(p, t, at);
                    zombies.put(t.id, z);
                } else {
                    z.teleport(at);
                }
                double life = t.life(now);
                int bars = (int) Math.ceil(life * 10);
                z.customName(Text.c((life > 0.3 ? "&e" : "&c") + "|".repeat(Math.max(0, bars)) + "&8" + "|".repeat(Math.max(0, 10 - bars))));
            }
            if (round.lit != lampShown) {
                if (lampShown >= 0 && lampShown < lamps.size()) {
                    Block b = lamps.get(lampShown).getBlock();
                    p.sendBlockChange(b.getLocation(), b.getBlockData());
                }
                if (round.lit >= 0 && round.lit < lamps.size()) {
                    Block b = lamps.get(round.lit).getBlock();
                    p.sendBlockChange(b.getLocation(), Bukkit.createBlockData("redstone_lamp[lit=true]"));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.6f);
                }
                lampShown = round.lit;
            }
        }

        private Zombie spawnZombie(Player p, ShootRound.Target t, Location at) {
            return prop(p, at, Zombie.class, z -> {
                z.setAI(false);
                z.setGravity(false);
                z.setRemoveWhenFarAway(false);
                z.setCanPickupItems(false);
                if (t.baby) z.setBaby(); else z.setAdult();
                z.setCustomNameVisible(true);
                EntityEquipment eq = z.getEquipment();
                String prefix = switch (t.kind) { case LEATHER -> "LEATHER"; case IRON -> "IRON"; case GOLD -> "GOLDEN"; case DIAMOND -> "DIAMOND"; };
                eq.setHelmet(new ItemStack(Material.valueOf(prefix + "_HELMET")));
                eq.setChestplate(new ItemStack(Material.valueOf(prefix + "_CHESTPLATE")));
                eq.setLeggings(new ItemStack(Material.valueOf(prefix + "_LEGGINGS")));
                eq.setBoots(new ItemStack(Material.valueOf(prefix + "_BOOTS")));
                eq.setHelmetDropChance(0); eq.setChestplateDropChance(0); eq.setLeggingsDropChance(0); eq.setBootsDropChance(0);
            });
        }

        void fire(Player p) {
            long now = System.currentTimeMillis();
            if (now - lastShot < FIRE_COOLDOWN_MS) return;
            lastShot = now;
            round.fired();
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection();
            Set<UUID> mine = new HashSet<>();
            for (Zombie z : zombies.values()) mine.add(z.getUniqueId());
            RayTraceResult r = p.getWorld().rayTrace(eye, dir, DART_RANGE, FluidCollisionMode.NEVER, true, 0.3, e -> mine.contains(e.getUniqueId()));
            double dist = r == null ? DART_RANGE : r.getHitPosition().distance(eye.toVector());
            for (double d = 1; d < dist; d += 0.7) p.spawnParticle(Particle.CRIT, eye.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0);
            p.playSound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 0.6f, 1.8f);
            if (r == null) return;
            if (r.getHitEntity() != null) {
                Integer id = null;
                for (Map.Entry<Integer, Zombie> e : zombies.entrySet()) if (e.getValue().getUniqueId().equals(r.getHitEntity().getUniqueId())) id = e.getKey();
                if (id == null) return;
                ShootRound.Target t = round.target(id);
                int pts = round.hitZombie(id, now);
                Zombie z = zombies.remove(id);
                if (z != null) {
                    p.spawnParticle(Particle.CLOUD, z.getLocation().add(0, 1, 0), 10, 0.2, 0.4, 0.2, 0.02);
                    z.remove();
                }
                p.playSound(p.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 0.8f, t != null && t.kind == ShootRound.Kind.DIAMOND ? 1.8f : 1.2f);
                Sky.vitals().flash(p, "&a+" + pts + (round.boosted(now) ? " &d(x1.2)" : ""), 15);
                return;
            }
            Block hit = r.getHitBlock();
            if (hit == null) return;
            for (int i = 0; i < lamps.size(); i++) {
                if (!lamps.get(i).getBlock().equals(hit)) continue;
                int pts = round.hitLamp(i, now);
                if (pts > 0) {
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
                    Msg.send(p, "&c&lLAMP! &a+" + pts + " &7and &dx1.2 &7points for 6 seconds!");
                }
                return;
            }
        }

        @Override boolean over(long now) { return round.over; }

        @Override String endReason(long now) { return "Time's up!"; }

        @Override int score() { return round.score; }

        @Override Map<String, Integer> stats() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("score", round.score);
            m.put("kills", round.kills);
            m.put("diamond", round.diamonds);
            m.put("lamps", round.lampHits);
            m.put("accuracy", (int) Math.round(round.accuracy()));
            return m;
        }

        @Override List<String> sidebar(long now) {
            return List.of("&c&lZombie Shootout", "Score: &e" + Text.commasInt(round.score),
                    "Kills: &a" + round.kills + " &7Accuracy: &a" + String.format("%.1f", round.accuracy()) + "%");
        }

        @Override String hud(long now) {
            return "&cZombie Shootout &7| &e" + round.score + " pts" + (round.boosted(now) ? " &dx1.2" : "") + " &7| &a" + Text.duration(round.left(now) / 1000);
        }

        @Override void cleanup(Player p) {
            for (Zombie z : zombies.values()) z.remove();
            zombies.clear();
            for (Location l : lamps) p.sendBlockChange(l, l.getBlock().getBlockData());
        }

        @Override Set<UUID> props() {
            Set<UUID> s = new HashSet<>();
            for (Zombie z : zombies.values()) s.add(z.getUniqueId());
            return s;
        }
    }

    // ---------------------------------------------------------------------------------------------- Catch a Fish

    final class FishSession extends Session {
        final FishRound round;
        final Box pond;
        final World world;
        final Map<Integer, TropicalFish> fish = new HashMap<>();
        boolean hadHook;

        FishSession(Player p) {
            super(p, CarnivalRules.Game.FISH, "CARNIVAL_FISHING_ROD");
            stand = anchor("carnival.fish.stand");
            pond = area("carnival.fish.pond");
            world = stand.getWorld();
            round = new FishRound(System.currentTimeMillis(), Math.max(10, carnival.data().fishSeconds), System.nanoTime() ^ p.getUniqueId().getMostSignificantBits());
        }

        @Override void begin(Player p) {
            Msg.send(p, "&e[NPC] &3Old Gill&f: Steady hands, now. The yellow ones fight the hardest.");
            Msg.send(p, "&7Cast the &aCarnival Rod &7next to a fish to hook it, then &ehold right-click &7until its bar fills. "
                    + "Every catch in a row is worth &a+5 &7more. Sneak twice to stop.");
        }

        Integer idOf(Entity e) {
            for (Map.Entry<Integer, TropicalFish> f : fish.entrySet()) if (f.getValue().getUniqueId().equals(e.getUniqueId())) return f.getKey();
            return null;
        }

        @Override void tick(Player p, long now) {
            FishRound.Fish before = round.hooked();
            round.step(now);
            if (before != null && round.hooked() == null && !round.over) {
                FishHook h = p.getFishHook();
                if (h != null) h.setHookedEntity(null);
                TropicalFish e = fish.get(before.id);
                if (e != null) { e.setAI(true); e.customName(null); e.setCustomNameVisible(false); }
                Msg.send(p, "&cThe " + FishRound.Kind.valueOf(before.kind.name()).color + titleOf(before.kind) + " &cgot away! &7Keep reeling next time.");
            }
            for (FishRound.Fish f : round.gone) {
                TropicalFish e = fish.remove(f.id);
                if (e != null) { p.spawnParticle(Particle.BUBBLE_POP, e.getLocation(), 6, 0.2, 0.2, 0.2, 0.02); e.remove(); }
            }
            round.gone.clear();
            for (FishRound.Fish f : round.fish) {
                if (fish.containsKey(f.id) && fish.get(f.id).isValid()) continue;
                fish.put(f.id, spawnFish(p, f));
            }
            // the hook: a cast that lands in the water near a fish hooks it
            FishHook hook = p.getFishHook();
            if (hook != null && hook.isValid() && round.hooked() == null && hook.getHookedEntity() == null) {
                Location hl = hook.getLocation();
                if (pond.contains(hl.getX(), hl.getY(), hl.getZ()) || hl.getBlock().getType() == Material.WATER) {
                    TropicalFish best = null;
                    double bd = 2.2 * 2.2;
                    for (TropicalFish e : fish.values()) {
                        double d = e.getLocation().distanceSquared(hl);
                        if (d < bd) { bd = d; best = e; }
                    }
                    Integer id = best == null ? null : idOf(best);
                    if (id != null && round.hook(id, now)) {
                        hook.setHookedEntity(best);
                        best.setAI(false);
                        best.setCustomNameVisible(true);
                        FishRound.Fish f = round.get(id);
                        Sky.vitals().flash(p, "&aHooked " + f.kind.color + titleOf(f.kind) + "&a! &eHold right-click to reel it in!", 30);
                        p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1f, 1.2f);
                    }
                }
            }
            if (hook == null && hadHook && round.hooked() != null) round.lose();
            hadHook = hook != null;
            FishRound.Fish h = round.hooked();
            if (h != null) {
                TropicalFish e = fish.get(h.id);
                if (e != null) e.customName(Text.c(h.kind.color + Text.bar(h.progress(), 10)));
            }
        }

        private TropicalFish spawnFish(Player p, FishRound.Fish f) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Location at = new Location(world, r.nextInt(pond.minX(), pond.maxX() + 1) + 0.5, pond.maxY() + 0.2 - r.nextDouble(0.8),
                    r.nextInt(pond.minZ(), pond.maxZ() + 1) + 0.5);
            return prop(p, at, TropicalFish.class, e -> {
                e.setRemoveWhenFarAway(false);
                e.setGlowing(true);
                e.setPattern(TropicalFish.Pattern.KOB);
                e.setBodyColor(switch (f.kind) { case RED -> DyeColor.RED; case BLUE -> DyeColor.BLUE; case YELLOW -> DyeColor.YELLOW; });
                e.setPatternColor(DyeColor.WHITE);
            });
        }

        /** A reel tick (the rod's retrieve with a fish on the line). True when it was ours to handle. */
        boolean reel(Player p, PlayerFishEvent e) {
            FishRound.Fish f = round.hooked();
            if (f == null) return false;
            e.setCancelled(true);
            FishRound.Pull r = round.pull(System.currentTimeMillis());
            if (r == null) return true;
            if (r.caught()) {
                TropicalFish ent = fish.remove(f.id);
                if (ent != null) { p.spawnParticle(Particle.SPLASH, ent.getLocation(), 20, 0.3, 0.2, 0.3, 0.1); ent.remove(); }
                FishHook h = p.getFishHook();
                if (h != null) h.remove();
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, f.kind == FishRound.Kind.YELLOW ? 2f : 1.5f);
                Msg.send(p, "&aYou caught " + (f.kind == FishRound.Kind.YELLOW ? "a " : "a ") + f.kind.color + titleOf(f.kind) + "&a! &e+" + r.points()
                        + (r.doubled() ? " &d&lDOUBLE!" : "") + (round.streak > 1 ? " &b(Catch Streak " + round.streak + ")" : ""));
            } else {
                p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.5f, 0.8f + (float) r.progress());
            }
            return true;
        }

        @Override boolean over(long now) { return round.over; }

        @Override String endReason(long now) { return "Time's up!"; }

        @Override int score() { return round.score; }

        @Override Map<String, Integer> stats() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("score", round.score);
            m.put("caught", round.caught);
            m.put("yellow", round.yellows);
            m.put("streak", round.bestStreak);
            return m;
        }

        @Override List<String> sidebar(long now) {
            return List.of("&3&lCatch a Fish", "Score: &e" + Text.commasInt(round.score), "Catch Streak: &a" + round.streak);
        }

        @Override String hud(long now) {
            return "&3Catch a Fish &7| &e" + round.caught + " caught &7| &a" + Text.duration(round.left(now) / 1000);
        }

        @Override void cleanup(Player p) {
            FishHook h = p.getFishHook();
            if (h != null) h.remove();
            for (TropicalFish e : fish.values()) e.remove();
            fish.clear();
        }

        @Override Set<UUID> props() {
            Set<UUID> s = new HashSet<>();
            for (TropicalFish e : fish.values()) s.add(e.getUniqueId());
            return s;
        }
    }

    static String titleOf(FishRound.Kind k) {
        return switch (k) { case RED -> "Red Fish"; case BLUE -> "Blue Fish"; case YELLOW -> "Yellow Fish"; };
    }

    // =============================================================================================== listeners

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (!isTool(item)) return;
        Player p = e.getPlayer();
        String id = Sky.items().idOf(item);
        Session s = sessions.get(p.getUniqueId());
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        if ("CARNIVAL_FISHING_ROD".equals(id)) {
            if (s == null) { e.setCancelled(true); takeTools(p); }
            return;   // casting and reeling go through PlayerFishEvent
        }
        e.setCancelled(true);
        try { Sky.abilities().consumeClick(p); } catch (Throwable ignored) { }
        if (s == null) { takeTools(p); return; }
        if (s instanceof FruitSession f && "CARNIVAL_SHOVEL".equals(id)) {
            if (right) f.cycleMode(p);
            else if (a == Action.LEFT_CLICK_BLOCK && e.getClickedBlock() != null) {
                int i = f.cellAt(e.getClickedBlock());
                if (i >= 0) f.onDig(p, i);
            }
        } else if (s instanceof ShootSession sh && "CARNIVAL_DART_TUBE".equals(id) && right) {
            sh.fire(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s instanceof FruitSession f) {
            int i = f.cellAt(e.getBlock());
            if (i >= 0) f.redraw(e.getPlayer(), i);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (!(s instanceof FishSession f)) {
            if (isTool(p.getInventory().getItemInMainHand())) e.setCancelled(true);
            return;
        }
        switch (e.getState()) {
            case FISHING -> {
                FishHook h = e.getHook();
                h.setApplyLure(false);
                h.setMaxWaitTime(20 * 6000);
                h.setMinWaitTime(20 * 6000);
            }
            case CAUGHT_ENTITY, REEL_IN -> { if (!f.reel(p, e)) { /* empty line: let it come back */ } }
            case BITE, CAUGHT_FISH, LURED -> e.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.stand == null) return;
        Location to = e.getTo();
        if (to.getWorld() != s.stand.getWorld()) return;
        double dx = to.getX() - s.stand.getX(), dz = to.getZ() - s.stand.getZ();
        if (dx * dx + dz * dz > 0.09 || to.getY() > s.stand.getY() + 0.6) {
            Location back = s.stand.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (now - s.sneakAt < 2000) { finish(p, s, "You stopped playing."); return; }
        s.sneakAt = now;
        Sky.vitals().flash(p, "&eSneak again to stop playing.", 40);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isTool(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPropDamage(EntityDamageEvent e) {
        if (isProp(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) finish(e.getPlayer(), s, "You left.");
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) finish(e.getPlayer(), s, "You left the Carnival Grounds.");
        else takeTools(e.getPlayer());
    }

    /** For the admin readout: who is playing what. */
    List<String> describe() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Iterator<Session> it = sessions.values().iterator(); it.hasNext(); ) {
            Session s = it.next();
            Player p = Bukkit.getPlayer(s.player);
            out.add((p == null ? s.player.toString() : p.getName()) + ": " + s.game.title + " score " + s.score() + " (" + Text.strip(s.hud(now)) + ")");
        }
        return out;
    }

    /** Party set pieces worn (Carnival Craze). */
    static int partyPieces(Player p) {
        Set<String> worn = new HashSet<>();
        try {
            for (ItemStack it : Sky.service(Features.Equipment.class).worn(p)) {
                String id = it == null ? null : Sky.items().idOf(it);
                if (id != null) worn.add(id);
            }
        } catch (Throwable ignored) { }
        try { String h = Sky.items().idOf(p.getInventory().getHelmet()); if (h != null) worn.add(h); } catch (Throwable ignored) { }
        int n = 0;
        for (String piece : CarnivalRules.PARTY_PIECES) if (worn.contains(piece)) n++;
        return n;
    }
}
