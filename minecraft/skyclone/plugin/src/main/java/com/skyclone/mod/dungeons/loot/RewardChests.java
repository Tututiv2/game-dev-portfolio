package com.skyclone.mod.dungeons.loot;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.DungeonsModule;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.ext.RewardHook;
import com.skyclone.mod.dungeons.ext.RunResult;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The end-of-run reward chests (research 04 sec 3.14): rolled per player when the boss dies, shown as a row of real
 * chests on pedestals at the arena's {@code chests} point, opened through a menu that shows the contents and the
 * price. One chest per run; a Dungeon Chest Key opens another; a Fate Feather rerolls one chest once per run.
 * Everything a player was offered is stored at once in their profile, so unopened chests wait at the Vault Keeper
 * (Croesus) after the run closes - and survive a crash. The RNG meter fills from S runs and guarantees its item.
 */
public final class RewardChests implements Listener {

    private RewardChests() {}

    public static final String KEY = "dungeons_loot";

    private record ChestBlock(String runId, LootTables.Chest tier) {}

    private static final Map<String, ChestBlock> BLOCKS = new HashMap<>();

    public static LData data(Player p) { return Sky.profiles().member(p, KEY, LData.class); }

    public static void init() {
        InputStream in = Sky.plugin().getResource("data/dungeons/loot.json");
        if (in != null) try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) { LootTables.load(r); }
        catch (Exception e) { Sky.log().severe("[dungeons] loot.json: " + e); }
        else Sky.log().severe("[dungeons] loot.json missing");
        RewardHook.set(RewardChests::onComplete);
        Bukkit.getPluginManager().registerEvents(new RewardChests(), Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), RewardChests::sweep, 1200L, 1200L);
    }

    private static void sweep() {
        BLOCKS.values().removeIf(b -> { DungeonRun r = RunManager.get().run(b.runId); return r == null || r.state == DungeonRun.State.CLOSED; });
    }

    // ---- keys and feathers may sit in the player's dungeon sack (the storage module's sacks accept both) ----

    /** Inventory plus sack count of an item. */
    static int have(Player p, String id) {
        if (!Sky.items().exists(id)) return 0;
        int n = Sky.items().count(p, id);
        try { n += Math.max(0, Sky.service(Features.Sacks.class).count(p, id)); } catch (RuntimeException ignored) { }
        return n;
    }

    /** Take one from the inventory, else from a sack. */
    static boolean takeOne(Player p, String id) {
        if (!Sky.items().exists(id)) return false;
        if (Sky.items().remove(p, id, 1) >= 1) return true;
        try { return Sky.service(Features.Sacks.class).remove(p, id, 1) >= 1; } catch (RuntimeException e) { return false; }
    }

    // ======================================================================================================
    // completion
    // ======================================================================================================

    static void onComplete(RunResult r) {
        LootTables.FloorLoot f = LootTables.floor(r.floor.id);
        if (!DDials.REAL_CHESTS.get() || f == null) { DungeonsModule.freeChest(r); return; }
        r.claimSeconds = Math.max(r.claimSeconds, DDials.CLAIM_SECONDS_B.get());
        for (UUID u : r.members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && Sky.profiles().isReady(p)) {
                LData d = data(p);
                LData.StoredRun sr = build(p, d, r, f);
                if (sr.chests.isEmpty()) continue;
                d.croesus.add(0, sr);
                trim(d);
                hecatomb(p, r.grade);
                Msg.send(p, "&6&lREWARD CHESTS &7- &e" + sr.chests.size() + " &7chest" + (sr.chests.size() == 1 ? "" : "s") + " unlocked: "
                        + String.join("&7, ", sr.chests.stream().map(c -> c.tier().label()).toList()));
                Msg.send(p, "&7Right-click one to look inside. &8Unopened chests wait at the Vault Keeper in the Dungeon Hub.");
                Sky.profiles().saveNow(p);
            } else {
                LData.StoredRun sr = build(null, null, r, f);
                if (!sr.chests.isEmpty()) Sky.profiles().modifyOffline(u, KEY, LData.class, d -> { d.croesus.add(0, sr); trim(d); });
            }
        }
        place(r, f);
    }

    private static void trim(LData d) {
        long cutoff = System.currentTimeMillis() - DDials.CROESUS_DAYS.get() * 86_400_000L;
        d.croesus.removeIf(sr -> sr.time < cutoff || !sr.anyLeft());
        while (d.croesus.size() > DDials.CROESUS_MAX.get()) d.croesus.remove(d.croesus.size() - 1);
    }

    /** Chest luck points: S+ +5, Boss Luck perk, treasure accessories (research: x1.01-1.03 -> +1..+3). */
    static double luck(Player p, int score) {
        double l = score >= 300 ? 5 : 0;
        if (p == null) return l;
        int bl = data(p).perks.getOrDefault("catacombs_boss_luck", 0);
        l += new int[]{0, 1, 3, 5, 10}[Math.max(0, Math.min(4, bl))];
        Features.Accessories acc = Sky.service(Features.Accessories.class);
        if (acc.has(p, "TREASURE_ARTIFACT")) l += 3; else if (acc.has(p, "TREASURE_RING")) l += 2; else if (acc.has(p, "TREASURE_TALISMAN")) l += 1;
        return l;
    }

    static LData.StoredRun build(Player p, LData d, RunResult r, LootTables.FloorLoot f) {
        LData.StoredRun sr = new LData.StoredRun();
        sr.runId = r.runId;
        sr.floor = r.floor.id;
        sr.time = System.currentTimeMillis();
        sr.score = r.score;
        sr.grade = r.grade;
        UUID u = p == null ? null : p.getUniqueId();
        int milestone = u == null ? 9 : r.milestones.getOrDefault(u, 0);
        List<LootTables.Chest> tiers = LootTables.unlocked(f, r.floor.master, r.score, milestone);
        double luck = luck(p, r.score);
        double ess = DDials.ESSENCE_SCALE.get() * (1 + 0.05 * (d == null ? 0 : d.perks.getOrDefault("catacombs_looting", 0)));
        // RNG meter: the selected item is guaranteed in the best chest that can hold it once the meter is full
        String sel = d == null ? null : d.rngItem.get(r.floor.id);
        LootTables.Entry selEntry = sel == null ? null : f.entry(sel);
        LootTables.Chest forcedIn = null;
        if (selEntry != null && d.rngXp.getOrDefault(r.floor.id, 0.0) >= selEntry.rng)
            for (LootTables.Chest c : tiers) if (selEntry.inChest(c)) forcedIn = c;
        long seed = r.runId.hashCode() * 31L + (u == null ? 7 : u.hashCode());
        for (LootTables.Chest c : tiers) {
            Random rng = new Random(seed ^ (c.ordinal() * 0x9E3779B97F4A7C15L));
            sr.chests.add(LootTables.roll(f, c, rng, e -> allowed(p, e), luck, DDials.BOSS_LUCK_STEP.get(), c == forcedIn ? sel : null, ess));
        }
        if (d != null && selEntry != null) {
            boolean got = forcedIn != null;
            for (LootTables.Rolled rc : sr.chests) for (String cell : rc.items) if (LootTables.idOf(cell).equals(sel)) got = true;
            if (got) {
                d.rngXp.put(r.floor.id, 0.0);
                if (p != null) Msg.send(p, "&d&lRNG METER! &7Your &d" + name(sel) + " &7is in a reward chest! The meter resets.");
            }
            double gain = LootTables.meterGain(r.score, DDials.RNG_MIN_SCORE.get(), DDials.RNG_SCALE.get());
            if (gain > 0 && !got) {
                double xp = d.rngXp.merge(r.floor.id, gain, Double::sum);
                if (p != null) Msg.send(p, "&dRNG Meter &7(" + r.floor.display() + "): &d" + Text.commasInt(Math.min(xp, selEntry.rng)) + "&7/&d"
                        + Text.commasInt(selEntry.rng) + " &7toward &d" + name(sel));
            }
        }
        return sr;
    }

    private static boolean allowed(Player p, LootTables.Entry e) {
        if (!Sky.items().exists(e.item)) return false;
        if (e.gate == null || e.gate.isBlank()) return true;
        return p != null && Sky.requirements().check(p, e.gate);
    }

    /** Enchanting's request: an S run adds one to the helmet's Hecatomb progress. */
    private static void hecatomb(Player p, String grade) {
        if (!grade.startsWith("S")) return;
        ItemStack helm = p.getInventory().getHelmet();
        if (helm == null || Sky.items().idOf(helm) == null) return;
        if (Sky.items().data(helm).enchant("hecatomb") <= 0) return;
        Sky.items().update(helm, d -> {
            int n = d.extra.has("ench_progress_hecatomb") ? d.extra.get("ench_progress_hecatomb").getAsInt() : 0;
            d.extra.add("ench_progress_hecatomb", new JsonPrimitive(n + 1));
        });
    }

    /** The row of chests on pedestals at the arena's chest point. */
    private static void place(RunResult r, LootTables.FloorLoot f) {
        Location at = r.chestSpot;
        DungeonRun run = RunManager.get().run(r.runId);
        if (at == null || at.getWorld() == null) return;
        List<LootTables.Chest> tiers = new ArrayList<>();
        for (LootTables.Chest c : LootTables.Chest.values()) if (f.chest(c) != null) tiers.add(c);
        int n = tiers.size();
        for (int i = 0; i < n; i++) {
            LootTables.Chest c = tiers.get(i);
            int dx = (i - (n - 1) / 2) * 2 - ((n % 2 == 0) ? 1 : 0);
            Block b = at.getWorld().getBlockAt(at.getBlockX() + dx, at.getBlockY(), at.getBlockZ());
            b.getRelative(0, -1, 0).setType(Material.matchMaterial(c.base) == null ? Material.STONE : Material.matchMaterial(c.base), false);
            b.setBlockData(Bukkit.createBlockData("chest[facing=south,type=single,waterlogged=false]"), false);
            b.getRelative(0, 1, 0).setType(Material.AIR, false);
            BLOCKS.put(key(b), new ChestBlock(r.runId, c));
            TextDisplay t = at.getWorld().spawn(b.getLocation().add(0.5, 1.4, 0.5), TextDisplay.class, d -> {
                d.text(Text.c(c.label() + "\n&eRight-click to open!"));
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
            });
            if (run != null) run.addProp(t);
            Fx.particle(b.getLocation().add(0.5, 1, 0.5), Particle.TOTEM_OF_UNDYING, 20, 0.4, 0.2);
        }
    }

    private static String key(Block b) { return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        String bk = key(e.getClickedBlock());
        ChestBlock cb = BLOCKS.get(bk);
        if (cb == null) return;
        DungeonRun live = RunManager.get().run(cb.runId);
        if (live == null || live.state == DungeonRun.State.CLOSED) { BLOCKS.remove(bk); return; }   // a released slot reused by a later run
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!Sky.profiles().isReady(p)) return;
        LData.StoredRun sr = find(data(p), cb.runId);
        if (sr == null) { Msg.error(p, "These chests are not yours."); return; }
        for (int i = 0; i < sr.chests.size(); i++)
            if (sr.chests.get(i).tier() == cb.tier) { chest(p, sr, i, null); return; }
        Msg.error(p, "You did not unlock the " + Text.strip(cb.tier.label()) + " this run. "
                + (cb.tier == LootTables.Chest.WOOD ? "(It needs class milestone 2.)" : "(It needs a higher score.)"));
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) { trim(data(e.player)); }

    static LData.StoredRun find(LData d, String runId) {
        for (LData.StoredRun sr : d.croesus) if (sr.runId.equals(runId)) return sr;
        return null;
    }

    // ======================================================================================================
    // the chest menu
    // ======================================================================================================

    static String name(String id) {
        // wave-5 regression: a book names its enchant ("Enchanted Book (Combo I)")
        return LootNames.plain(id);
    }

    public static double price(LootTables.Rolled c) {
        double v = c.cost * DDials.CHEST_COST_SCALE.get();
        Features.Mayors m = Sky.service(Features.Mayors.class);
        if (m.perkActive("paul.marauder")) v *= Math.max(0, 1 - m.perkValue("paul.marauder") / 100.0);
        return Math.floor(v);
    }

    public static void chest(Player p, LData.StoredRun sr, int idx, Runnable back) {
        LootTables.Rolled c = sr.chests.get(idx);
        LootTables.Chest tier = c.tier();
        Menu m = new Menu(Text.strip(tier.label()) + " &8(" + sr.floor + ")", 6).chrome();
        int slot = 10;
        for (String cell : c.items) {
            String id = LootTables.idOf(cell);
            int n = LootTables.amountOf(cell);
            ItemStack icon;
            try { icon = Sky.items().exists(id) ? LootNames.titled(Sky.items().create(id, Math.min(64, n)), id) : Icons.of(Material.BARRIER, "&c" + id); }
            catch (RuntimeException ex) { icon = Icons.of(Material.BARRIER, "&c" + id); }
            List<String> extra = new ArrayList<>();
            if (id.equals(c.rngItem)) { extra.add(""); extra.add("&d&lRNG METER DROP!"); }
            m.set(slot++, extra.isEmpty() ? icon : Icons.withLore(icon, extra));
        }
        m.set(slot++, Icons.of(Material.PLAYER_HEAD, "&dUndead Essence &8x" + c.undead, "&7Dungeon essence, for stars."));
        m.set(slot, Icons.of(Material.WITHER_SKELETON_SKULL, "&dWither Essence &8x" + c.wither, "&7Dungeon essence, for stars."));
        double price = price(c);
        boolean free = sr.canOpenFree();
        List<String> lore = new ArrayList<>(List.of("&7Floor: &c" + sr.floor + "  &7Score: &e" + sr.score + " (" + sr.grade + ")", ""));
        lore.add("&7Cost");
        lore.add(price <= 0 ? "&aFree!" : "&6" + Text.commas(price) + " Coins");
        if (!free) lore.add("&b1x Dungeon Chest Key &7(you already opened " + sr.opened + ")");
        lore.add("");
        if (c.opened) lore.add("&cAlready opened.");
        else lore.add("&eClick to open!");
        m.set(31, Icons.of(c.opened ? Material.BARRIER : Material.CHEST, c.opened ? "&cOpened" : "&aOpen Reward Chest", lore), cl -> open(cl.player(), sr, idx, back));
        boolean feather = have(p, "KISMET_FEATHER") > 0;
        m.set(33, Icons.of(Material.FEATHER, "&aReroll Chest", "&7Spend a &aFate Feather &7to roll this", "&7chest's contents again. Once per run.", "",
                sr.rerolled ? "&cYou already rerolled a chest this run." : c.opened ? "&cAlready opened." : feather ? "&eClick to reroll!" : "&cYou have no Fate Feather."),
                cl -> reroll(cl.player(), sr, idx, back));
        m.set(29, Icons.of(Material.PAPER, "&aRun", "&7" + sr.floor + " &8- " + sr.grade, "&7Chests opened: &e" + sr.opened, "&7Keys used: &b" + sr.keysUsed,
                "&7Rerolled: " + (sr.rerolled ? "&ayes" : "&7no")));
        if (back != null) m.set(48, Icons.back("Vault Keeper"), cl -> back.run());
        m.open(p);
    }

    static void open(Player p, LData.StoredRun sr, int idx, Runnable back) {
        if (!Sky.profiles().isReady(p)) return;
        LData d = data(p);
        LData.StoredRun live = find(d, sr.runId);
        if (live == null) { Msg.error(p, "That run's chests are gone."); p.closeInventory(); return; }
        LootTables.Rolled c = live.chests.get(idx);
        if (c.opened) { Msg.error(p, "You already opened this chest."); return; }
        boolean needKey = !live.canOpenFree();
        if (needKey && have(p, "DUNGEON_CHEST_KEY") < 1) {
            Msg.error(p, "You already opened a chest this run. A Dungeon Chest Key opens another.");
            return;
        }
        double price = price(c);
        if (price > 0 && Sky.economy().purse(p) < price) { Msg.error(p, "You need " + Text.commas(price) + " coins in your purse."); return; }
        if (price > 0 && !Sky.economy().withdraw(p, price, "dungeons: open " + c.chest + " chest " + live.floor + " (" + live.runId + ")")) {
            Msg.error(p, "You don't have enough coins!");
            return;
        }
        if (needKey) {
            if (!takeOne(p, "DUNGEON_CHEST_KEY")) {
                if (price > 0) Sky.economy().deposit(p, price, "dungeons: refund chest " + live.runId);
                Msg.error(p, "Your Dungeon Chest Key went missing; you were refunded.");
                return;
            }
            live.keysUsed++;
        }
        c.opened = true;
        live.opened++;
        d.chestsOpened++;
        d.coinsSpent += price;
        for (String cell : c.items) {
            String id = LootTables.idOf(cell);
            if (!Sky.items().exists(id)) continue;
            int n = LootTables.amountOf(cell);
            Sky.loot().give(p, id, n, LootSource.DUNGEON);
            Msg.send(p, "  " + LootNames.colored(id) + (n > 1 ? " &8x" + n : "")
                    + (id.equals(c.rngItem) ? " &d&l(RNG METER)" : ""));
        }
        essence(p, d, "ESSENCE_UNDEAD", c.undead);
        essence(p, d, "ESSENCE_WITHER", c.wither);
        Msg.send(p, "  &dUndead Essence &8x" + c.undead + "  &dWither Essence &8x" + c.wither);
        Msg.success(p, "You opened the " + Text.strip(c.tier().label()) + (price > 0 ? " for " + Text.commas(price) + " coins" : "") + "!");
        Fx.sound(p, Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        Fx.particle(p.getLocation().add(0, 1, 0), Particle.TOTEM_OF_UNDYING, 30, 0.6, 0.3);
        if (!live.anyLeft()) d.croesus.remove(live);
        Sky.profiles().saveNow(p);
        if (back != null) back.run(); else p.closeInventory();
    }

    static void essence(Player p, LData d, String id, int n) {
        if (n <= 0 || !Sky.items().exists(id)) return;
        boolean items = d.essenceAsItems != null ? d.essenceAsItems : DDials.ESSENCE_AS_ITEMS_DEFAULT.get();
        if (!items) { Sky.loot().give(p, id, n, LootSource.DUNGEON); return; }
        Map<Integer, ItemStack> left = p.getInventory().addItem(Sky.items().create(id, n));
        for (ItemStack s : left.values()) Sky.loot().dropFor(p, p.getLocation(), s);
    }

    static void reroll(Player p, LData.StoredRun sr, int idx, Runnable back) {
        if (!Sky.profiles().isReady(p)) return;
        LData d = data(p);
        LData.StoredRun live = find(d, sr.runId);
        if (live == null) return;
        LootTables.Rolled c = live.chests.get(idx);
        if (live.rerolled) { Msg.error(p, "You already rerolled a chest this run."); return; }
        if (c.opened) { Msg.error(p, "That chest is already open."); return; }
        LootTables.FloorLoot f = LootTables.floor(live.floor);
        if (f == null) { Msg.error(p, "That floor's loot table is gone."); return; }
        if (!takeOne(p, "KISMET_FEATHER")) { Msg.error(p, "You need a Fate Feather."); return; }
        // review fix: a reroll keeps the RNG-meter item (the meter was already spent on it when the chest rolled)
        LootTables.Rolled fresh = LootTables.roll(f, c.tier(), new Random(), e -> allowed(p, e), luck(p, live.score), DDials.BOSS_LUCK_STEP.get(), c.rngItem,
                DDials.ESSENCE_SCALE.get() * (1 + 0.05 * d.perks.getOrDefault("catacombs_looting", 0)));
        live.chests.set(idx, fresh);
        live.rerolled = true;
        Sky.profiles().saveNow(p);
        Fx.sound(p, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1f, 1.6f);
        Msg.success(p, "The Fate Feather rerolled the " + Text.strip(c.tier().label()) + "!");
        chest(p, live, idx, back);
    }

    // ======================================================================================================
    // the vault keeper (Croesus)
    // ======================================================================================================

    public static void croesus(Player p) {
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading."); return; }
        LData d = data(p);
        trim(d);
        // fixer: during a finished run's claim window, say where this run's chests stand (they are also listed below)
        DungeonRun live = RunManager.get() == null ? null : RunManager.get().runOf(p);
        if (live != null && live.state == DungeonRun.State.COMPLETE && find(d, live.id) != null)
            Msg.send(p, "&6This run's reward chests stand on pedestals in the boss room: right-click one. "
                    + "&7You can open them from this list too; any you leave wait at the Vault Keeper in the Dungeon Hub.");
        if (d.croesus.isEmpty()) {
            Menu m = new Menu("Vault Keeper", 3).chrome();
            m.set(13, Icons.of(Material.CHEST_MINECART, "&7No unclaimed chests", "&7Chests you leave unopened after a", "&7run wait here for &e"
                    + DDials.CROESUS_DAYS.get() + " &7days."));
            m.open(p);
            return;
        }
        List<LData.StoredRun> list = new ArrayList<>(d.croesus);
        // fixer: paged() BUILDS the menu and returns it; nothing opened it, so /dungeons chests, the Vault Keeper and the
        // menu button all did nothing whenever there WAS something to claim (only the empty case opened)
        Sky.menus().paged("Vault Keeper", list, sr -> {
            List<String> lore = new ArrayList<>();
            lore.add("&7Score: &e" + sr.score + " &7(" + sr.grade + ")");
            lore.add("&7" + Text.duration((System.currentTimeMillis() - sr.time) / 1000) + " ago");
            lore.add("");
            for (LootTables.Rolled c : sr.chests) lore.add((c.opened ? "&8✔ " : "&a● ") + c.tier().label() + (c.opened ? " &8(opened)" : " &7- &6" + Text.shortNum(price(c))));
            lore.add("");
            lore.add(sr.canOpenFree() ? "&aYou may open a chest." : "&bNeeds a Dungeon Chest Key.");
            lore.add("&eClick to view!");
            return Icons.of(Material.CHEST, "&c" + sr.floor + " &7run", lore);
        }, pc -> run(pc.click().player(), pc.entry()), page -> page.set(49, Icons.close(), cl -> cl.player().closeInventory()), 0).open(p);
    }

    static void run(Player p, LData.StoredRun sr) {
        Menu m = new Menu("Vault Keeper - " + sr.floor, 4).chrome();
        int slot = 10;
        for (int i = 0; i < sr.chests.size(); i++) {
            LootTables.Rolled c = sr.chests.get(i);
            final int idx = i;
            Material mat = Material.matchMaterial(c.tier().base);
            m.set(slot++, Icons.of(c.opened ? Material.BARRIER : (mat == null ? Material.CHEST : mat), c.tier().label(),
                    c.opened ? "&8Opened" : "&7Cost: &6" + Text.commas(price(c)), "", c.opened ? "" : "&eClick to view!"),
                    c.opened ? null : cl -> chest(cl.player(), sr, idx, () -> run(cl.player(), find(data(cl.player()), sr.runId) == null ? sr : find(data(cl.player()), sr.runId))));
        }
        m.set(31, Icons.back("Vault Keeper"), cl -> croesus(cl.player()));
        m.open(p);
    }

    /** Admin/testing: give a player a stored run for a floor as if they had just finished it. */
    public static String grant(Player p, String floorId, int score) {
        com.skyclone.mod.dungeons.model.FloorDef fd = com.skyclone.mod.dungeons.Floors.get(floorId);
        LootTables.FloorLoot f = fd == null ? null : LootTables.floor(fd.id);
        if (fd == null || f == null) return "Unknown floor";
        RunResult r = new RunResult("admin" + Long.toString(System.nanoTime(), 36), fd, score, com.skyclone.mod.dungeons.model.CataMath.grade(score),
                0, 0, 0, 0, 600, List.of(p.getUniqueId()), 0, 0, 0, false, p.getLocation(), 60);
        r.milestones.put(p.getUniqueId(), 9);
        LData d = data(p);
        LData.StoredRun sr = build(p, d, r, f);
        d.croesus.add(0, sr);
        trim(d);
        return null;
    }

    @SuppressWarnings("unused")
    private static void unused(Iterator<?> i) { }
}
