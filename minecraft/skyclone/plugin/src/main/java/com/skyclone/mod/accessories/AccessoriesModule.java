package com.skyclone.mod.accessories;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Accessories: every talisman / ring / artifact / relic (423), the Accessory Bag, Accessory Power,
 * the 32 Powers with their stones, stats tuning, enrichments, the personal compactor / deletor, and the
 * four equipment slots (necklace, cloak, belt, gloves) with their 147 pieces.
 *
 * <p>Provides {@link Features.Accessories} and {@link Features.Equipment}; registers the {@code mp:<n>}
 * requirement prefix. Player commands: /accessories, /equipment, /powers, /tuning, /accguide, /compactor,
 * /deletor. Admin: /sb accessories ...
 */
public final class AccessoriesModule implements GameModule, Listener {

    private Catalog cat;
    private Bags bags;
    private EffectEngine engine;
    private Ui ui;

    @Override public String id() { return "accessories"; }

    @Override
    public void enable() {
        AccDials.register();
        Sky.items().loadJson("accessories", "items.json");
        Sky.items().loadJson("accessories", "equipment.json");
        Sky.recipes().loadJson("accessories", "recipes.json");

        cat = new Catalog();
        cat.load();
        bags = new Bags(cat);
        KeepBags keep = new KeepBags(bags);
        engine = new EffectEngine(bags, cat, keep);
        ui = new Ui(new BagMenus(bags, cat), new PowerMenus(bags, cat), new EquipMenu(bags), new ToolMenus(bags, cat), keep);

        Sky.provide(Features.Accessories.class, new Features.Accessories() {
            @Override public boolean has(Player p, String itemId) { return AccessoriesModule.this.has(p, itemId); }
            @Override public int magicalPower(Player p) { return bags.magicalPower(p); }
            @Override public void openBag(Player p) { ui.bag.open(p, 0); }
        });
        Sky.provide(Features.Equipment.class, new Features.Equipment() {
            @Override public List<ItemStack> worn(Player p) { return bags.worn(p); }
            // wave 5 (our request 6): open it directly instead of through the command
            @Override public void openMenu(Player p) { ui.equipment.open(p); }

            // storage request 9 (playtest wave 5: the Equipment Wardrobe could only "Take Set"). storage's
            // EquipBridge binds these two by name: slot NECKLACE|CLOAK|BELT|GLOVES, null clears.
            public ItemStack equipped(Player p, String slot) {
                if (!Bags.ready(p)) return null;
                ItemStack it = bags.equipped(p, slot);
                return it == null ? null : it.clone();
            }

            public boolean setEquipped(Player p, String slot, ItemStack it) {
                if (!Bags.ready(p) || slot == null || !java.util.Arrays.asList(Bags.EQUIP_SLOTS).contains(slot)) return false;
                if (it != null && !it.getType().isAir()) {
                    String id = Sky.items().idOf(it);
                    if (id == null || !slot.equals(Bags.slotFor(Sky.items().def(id)))) return false;
                    bags.setEquip(p, slot, it);
                } else {
                    bags.setEquip(p, slot, null);
                }
                return true;
            }
        });

        Sky.requirements().register("mp", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 2) return false;
                try { return bags.magicalPower(p) >= Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return false; }
            }
            @Override public String describe(String[] parts) {
                return "&6" + (parts.length > 1 ? parts[1] : "?") + " Accessory Power";
            }
        });

        Sky.stats().addProvider("accessories", this::contribute);
        Sky.combat().addModifier("accessories", 250, engine::modifyDamage);
        // Ancient Cloak: never changes a cost, only notes that a health-costing ability is about to run
        Sky.abilities().addCostModifier("accessories_health_ability", engine::armHealthAbility);
        // Catacombs Expert Ring / Professor's books: binds to SkyEvents.DungeonXpGain once the API has it
        DungeonXpHook.install(engine);
        ItemHooks.register(cat);
        // wave 5: Golden Fleece / Shriveled Bracelet Magic Find only against their family (core's per-target hook)
        Sky.loot().addMagicFindModifier("accessories", engine::magicFindVs);
        // wave 5: core's dupe scanner also looks inside the bag, the equipment and the mask / cake bags
        Sky.items().addDupeScanSource("accessories", bags::storedStacks);

        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(engine, Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tickSecond, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { engine.magnetTick(); } catch (RuntimeException ex) { Sky.log().warning("[accessories] magnet failed: " + ex); }
            try { engine.fastTick(); } catch (RuntimeException ex) { Sky.log().warning("[accessories] fast tick failed: " + ex); }
        }, 45L, 4L);

        registerCommands();
        AccAdmin admin = new AccAdmin(bags, cat, ui);
        admin.register();
        admin.selfTests();

        if (AccDials.NPC.get()) {
            Sky.npcs().register(NpcDef.of("thaumaturgist", "&dThaumaturgist Ophira", "hub", "npc.thaumaturgist")
                            .title("&5&lACCESSORY POWERS"),
                    p -> Sky.npcs().talk(p, "thaumaturgist", List.of(
                            "&fEvery trinket you carry hums with a little power.",
                            "&fBring them together in your bag and I can shape that power for you."),
                            () -> ui.powers.openMain(p)));
        }

        Sky.sidebar().tabWidget("accessories", 150, p -> {
            if (!Bags.ready(p)) return List.of();
            AccData d = Bags.data(p);
            Catalog.Power pw = cat.powers.get(d.power);
            return List.of("&6&lAccessories:", " &7Power: &6" + bags.magicalPower(p),
                    " &7Selected: " + (pw == null ? "&cNone" : "&a" + pw.name()));
        });
    }

    @Override
    public void postEnable() {
        cat.clearFx();
        cat.resetCompactable();
        MobTypes.clear();
        // wave 5: Power talismans' gems at half power - after reforges' gem contributor (registered in its enable)
        ItemHooks.registerGemPower();
        // every module has registered its items now: no crafted item of ours may out-sell its inputs
        SellCap.apply(cat);
        // and once more after the first tick, for anything registered late
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> SellCap.apply(cat));
    }

    @Override
    public void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try { if (Bags.ready(p)) Sky.profiles().saveNow(p); } catch (RuntimeException ignored) { }
        }
    }

    // ---- commands ------------------------------------------------------------------------------

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("accessories", "Open your Accessory Bag")
                .alias("acc", "accessorybag", "abag", "talismans").usage("[page]").category("Accessories").playerOnly()
                .run((s, a) -> ui.bag.open((Player) s, a.length > 0 ? parsePage(a[0]) : 0)));
        Sky.commands().register(SkyCommand.of("equipment", "Open your equipment slots (necklace, cloak, belt, gloves)")
                .alias("eq").category("Accessories").playerOnly()
                .run((s, a) -> ui.equipment.open((Player) s)));
        Sky.commands().register(SkyCommand.of("powers", "Accessory Bag Thaumaturgy: powers, tuning and power stones")
                .alias("thaumaturgy", "accessorypower").category("Accessories").playerOnly()
                .run((s, a) -> ui.powers.openMain((Player) s)));
        Sky.commands().register(SkyCommand.of("tuning", "Spend your Accessory Power tuning points")
                .alias("statstuning").category("Accessories").playerOnly()
                .run((s, a) -> ui.powers.openTuning((Player) s)));
        Sky.commands().register(SkyCommand.of("accguide", "Browse every accessory and see which you are missing")
                .alias("missingaccessories").category("Accessories").playerOnly()
                .run((s, a) -> ui.bag.openGuide((Player) s, true, 0)));
        Sky.commands().register(SkyCommand.of("compactor", "Choose what your Personal Compactor compacts")
                .category("Accessories").playerOnly()
                .run((s, a) -> ui.tools.openCompactor((Player) s)));
        Sky.commands().register(SkyCommand.of("deletor", "Choose what your Personal Deletor destroys")
                .category("Accessories").playerOnly()
                .run((s, a) -> ui.tools.openDeletor((Player) s)));
        Sky.commands().register(SkyCommand.of("maskbag", "Open your Carnival Mask Bag")
                .alias("carnivalmaskbag").category("Accessories").playerOnly()
                .run((s, a) -> openKeep((Player) s, KeepBags.MASK)));
        Sky.commands().register(SkyCommand.of("cakebag", "Open your New Year Cake Bag")
                .alias("newyearcakebag").category("Accessories").playerOnly()
                .run((s, a) -> openKeep((Player) s, KeepBags.CAKE)));
        Sky.commands().register(SkyCommand.of("bagupgrades", "Buy and review Accessory Bag slots")
                .category("Accessories").playerOnly()
                .run((s, a) -> ui.bag.openUpgrades((Player) s)));
    }

    /** Mask / cake bag: you need the bag itself (Accessory Bag or inventory) to open it. */
    private void openKeep(Player p, String kind) {
        if (!has(p, KeepBags.bagId(kind)) && bags.slotOf(p, KeepBags.bagId(kind)) < 0) {
            Msg.error(p, "You need a " + KeepBags.title(kind) + " in your Accessory Bag or inventory.");
            return;
        }
        ui.keep.open(p, kind);
    }

    private static int parsePage(String s) {
        try { return Math.max(0, Integer.parseInt(s.trim()) - 1); } catch (NumberFormatException e) { return 0; }
    }

    // ---- feature service -----------------------------------------------------------------------

    /** Counted in the bag, or simply carried in the inventory (some reference accessories work from either). */
    boolean has(Player p, String itemId) {
        if (p == null || itemId == null || !Bags.ready(p)) return false;
        if (bags.snapshot(p).activeIds.contains(itemId)) return true;
        return Sky.items().count(p, itemId) > 0;
    }

    // ---- stats ---------------------------------------------------------------------------------

    private void contribute(Player p, com.skyclone.api.stat.StatMap into) {
        if (!Bags.ready(p)) return;
        Snapshot s = bags.snapshot(p);
        into.addAll(s.stats);
        AccData d = Bags.data(p);
        int mp = bags.magicalPower(p);
        Catalog.Power pw = cat.powers.get(d.power);
        if (pw != null && (pw.starter() || d.learned.contains(pw.id()))
                && (pw.requirement() == null || Sky.requirements().check(p, pw.requirement()))) {
            double m = AccMath.multiplier(mp) * AccDials.POWER_SCALE.get();
            for (Map.Entry<Stat, Double> e : pw.base().entrySet()) into.add(e.getKey(), e.getValue() * m);
            for (Map.Entry<Stat, Double> e : pw.unique().entrySet()) into.add(e.getKey(), e.getValue());
        }
        if (!d.tuning.isEmpty()) {
            int points = AccMath.tuningPoints(mp, AccDials.MP_PER_TUNING.get());
            Map<String, Integer> eff = AccMath.clampTuning(d.tuning, AccDials.tuningOrder(), points);
            for (Map.Entry<String, Integer> e : eff.entrySet()) {
                Stat st = Stat.parse(e.getKey());
                if (st != null) into.add(st, e.getValue() * AccDials.TUNING.get(e.getKey()).get());
            }
        }
        if (!s.fx.isEmpty()) engine.contributeStats(p, bags.state(p), s, into);
    }

    // ---- timers --------------------------------------------------------------------------------

    private int seconds;

    private void tickSecond() {
        engine.tick();
        boolean sweep = ++seconds % 30 == 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Bags.ready(p)) continue;
            try {
                ui.tools.tick(p);
                payMpXp(p);
                // never while a storage menu of ours is open: its items are in flight between the two sides
                if (sweep && Sky.menus().current(p) == null) bags.dupeSweep(p);
            } catch (RuntimeException ex) {
                Sky.log().warning("[accessories] tick for " + p.getName() + ": " + ex);
            }
        }
    }

    /** research/02: 1 SkyBlock XP per point of Accessory Power, paid once per point ever reached. */
    private void payMpXp(Player p) {
        if (!Sky.has(Features.SkyLevel.class)) return;
        AccData d = Bags.data(p);
        int mp = bags.snapshot(p).mp;
        if (mp <= d.highestMp) return;
        int to = Math.min(mp, d.highestMp + Math.max(1, AccDials.MAX_MP_XP_PER_SECOND.get()));
        Features.SkyLevel lvl = Sky.service(Features.SkyLevel.class);
        for (int n = d.highestMp + 1; n <= to; n++) lvl.award(p, "accessories:mp:" + n, 1, "Accessory Power");
        d.highestMp = to;
    }

    // ---- events --------------------------------------------------------------------------------

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        bags.unload(e.player.getUniqueId());
        bags.state(e.player);
        bags.invalidate(e.player);
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) {
        bags.unload(e.player.getUniqueId());
        EffectEngine.MINE_AT.remove(e.player.getUniqueId());
    }

    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) {
        if (Bags.ready(e.player)) bags.invalidate(e.player);
    }

    /** Right-click a power stone, an enrichment, an equipment piece or a compactor/deletor in your hand. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // a chest, door or workstation keeps its normal meaning even with an accessory in hand
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                && e.getClickedBlock().getType().isInteractable() && !p(e).isSneaking()) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(hand);
        if (id == null || !Bags.ready(p)) return;
        ItemDef def = Sky.items().def(id);
        if (def == null) return;
        if (cat.powerByStone.containsKey(id)) {
            e.setCancelled(true);
            ui.powers.learn(p, cat.powerByStone.get(id));
            return;
        }
        if (cat.enrichStatById.containsKey(id)) {
            e.setCancelled(true);
            ui.tools.openEnrich(p, id);
            return;
        }
        if (id.equals(cat.swapperId)) {
            e.setCancelled(true);
            ui.tools.openSwapper(p, id);
            return;
        }
        if (Catalog.isEquipment(def)) {
            e.setCancelled(true);
            ui.equipment.equipFromHand(p);
            return;
        }
        if (Catalog.isAccessory(def)) {
            for (Fx f : cat.effects(def)) {
                if (f.type.equals("compactor")) { e.setCancelled(true); ui.tools.openCompactor(p); return; }
                if (f.type.equals("deletor")) { e.setCancelled(true); ui.tools.openDeletor(p); return; }
                // wave 5: "Right-click to open!" (sneak + right-click still moves it into the Accessory Bag)
                if (f.type.equals("keep_bag") && f.kind != null && !p.isSneaking()) {
                    e.setCancelled(true);
                    ui.keep.open(p, f.kind);
                    return;
                }
            }
            // any other accessory: right-click drops it into the first free bag slot
            int slot = bags.firstFreeSlot(p);
            e.setCancelled(true);
            if (slot < 0) { Msg.error(p, "Your Accessory Bag is full!"); return; }
            ItemStack one = hand.clone();
            one.setAmount(1);
            if (hand.getAmount() > 1) { hand.setAmount(hand.getAmount() - 1); p.getInventory().setItemInMainHand(hand); }
            else p.getInventory().setItemInMainHand(null);
            bags.setBag(p, slot, one);
            Msg.send(p, "&aMoved " + def.name + " &ainto your Accessory Bag.");
        }
    }

    private static Player p(PlayerInteractEvent e) { return e.getPlayer(); }

    /** For tests and debugging: the module's catalog view. */
    List<String> ids() { return new ArrayList<>(cat.accessoryIds); }
}
