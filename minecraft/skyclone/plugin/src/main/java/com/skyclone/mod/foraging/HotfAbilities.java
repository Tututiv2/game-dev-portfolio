package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Heart of the Forest axe abilities (research 07 §4.5): pick one in the HotF menu, then SNEAK + RIGHT-CLICK
 * any axe on a foraging island to fire it.
 * <ul>
 *   <li><b>Damage Boost</b> (tier II): your axe deals x2 damage to creatures on foraging islands, 10 s.</li>
 *   <li><b>Axe Toss</b> (tier II): thrown axes lose no Sweep, 10 s.</li>
 *   <li><b>Mana Toss</b> (tier VI): each throw spends ALL your mana for +1 Sweep per 100 mana, 15-35 s.</li>
 * </ul>
 * Ability level is 1, or 2 once Center of the Forest II is bought (research: "+1 axe ability level");
 * cooldowns 120 s -> 100 s over five levels (Damage Boost / Axe Toss), 60 -> 56 s (Mana Toss).
 * Also owns the axe enchant Missile (cuts the thrown-axe penalty).
 */
final class HotfAbilities implements Listener {

    record Ability(String id, String name, int tier, Material icon, String[] lore) {
        int duration(int lv) { return "mana_toss".equals(id) ? 15 + 5 * (lv - 1) : 10; }
        int cooldown(int lv) { return "mana_toss".equals(id) ? 60 - (lv - 1) : 120 - 5 * (lv - 1); }
    }

    static final List<Ability> ALL = List.of(
            new Ability("damage_boost", "Damage Boost", 2, Material.IRON_SWORD, new String[]{
                    "&7Your axe deals &cx2 damage &7to creatures", "&7on foraging islands."}),
            new Ability("axe_toss", "Axe Toss", 2, Material.GOLDEN_AXE, new String[]{
                    "&7Thrown axes lose &ano &2∮ Sweep&7."}),
            new Ability("mana_toss", "Mana Toss", 6, Material.LAPIS_LAZULI, new String[]{
                    "&7Each throw spends &ball &7your mana and", "&7gains &2+1∮ Sweep &7per &b100 mana &7spent."}));

    static Ability byId(String id) {
        for (Ability a : ALL) if (a.id.equals(id)) return a;
        return null;
    }

    private static final Map<UUID, Long> activeUntil = new HashMap<>();
    private static final Map<UUID, String> activeId = new HashMap<>();
    private static final Map<UUID, Map<String, Long>> readyAt = new HashMap<>();
    private static final Map<UUID, Integer> usedTick = new HashMap<>();

    void register() {
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Sky.combat().addModifier("foraging_damage_boost", 410, ctx -> {
            Player p = ctx.playerAttacker();
            if (p == null || !active(p, "damage_boost") || ctx.kind == DamageContext.Kind.TRUE) return;
            if (!isAxe(ctx.weapon) || !Chopper.foragingIsland(Chopper.island(p.getWorld()))) return;
            ctx.multiplicative *= 2;
        });
    }

    static int level(ForagingData d) { return Hotf.rawLevel(d, "center") >= 2 ? 2 : 1; }

    static boolean active(Player p, String id) {
        Long until = activeUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis() && id.equals(activeId.get(p.getUniqueId()));
    }

    /** Fired an ability this tick (so the same click does not also throw the axe). */
    static boolean justUsed(Player p) {
        Integer t = usedTick.get(p.getUniqueId());
        return t != null && t == Bukkit.getCurrentTick();
    }

    static boolean isAxe(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        String id = Sky.items().idOf(it);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (def != null && def.category == ItemCategory.AXE) return true;
        return it.getType().name().endsWith("_AXE");
    }

    // ================================================================================ throw modifiers

    /** The Sweep fraction a thrown axe loses: the dial, cut by Missile, removed by Axe Toss. */
    static double throwPenalty(Player p, ItemStack hand) {
        if (active(p, "axe_toss") || Bonuses.noThrowPenalty(p)) return 0;   // Relic Drone pet: "loses its throwing penalty"
        double pen = FDials.throwPenalty.get();
        if (hand != null && !hand.getType().isAir() && Sky.items().idOf(hand) != null) {
            int lv = Sky.items().data(hand).enchant("ultimate_missile");
            if (lv > 0 && Sky.items().enchant("ultimate_missile") != null)
                pen *= Math.max(0, 1 - Sky.items().enchant("ultimate_missile").value(lv) / 100.0);
        }
        return pen;
    }

    /** Mana Toss: spend all mana on this throw; returns the extra Sweep (+1 per 100 mana). */
    static double manaToss(Player p) {
        if (!active(p, "mana_toss")) return 0;
        double mana = Sky.vitals().mana(p);
        if (mana < 1 || !Sky.vitals().useMana(p, mana)) return 0;
        double bonus = Math.floor(mana / 100.0);
        if (bonus > 0) p.sendActionBar(Text.c("&b-" + Text.commasInt(mana) + " Mana &7(&2+" + (int) bonus + "∮ Sweep&7)"));
        return bonus;
    }

    // ================================================================================ activation

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !FDials.axeAbilities.get()) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!p.isSneaking() || !isAxe(p.getInventory().getItemInMainHand())) return;
        if (!Chopper.foragingIsland(Chopper.island(p.getWorld())) || !Sky.profiles().isReady(p)) return;
        ForagingData d = ForagingModule.data(p);
        if (!d.hotfUnlocked || Hotf.tier(d) < 2 || d.axeAbility == null) return;
        Ability a = byId(d.axeAbility);
        if (a == null || Hotf.tier(d) < a.tier) return;
        usedTick.put(p.getUniqueId(), Bukkit.getCurrentTick());
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        fire(p, d, a);
    }

    static void fire(Player p, ForagingData d, Ability a) {
        int lv = level(d);
        long now = System.currentTimeMillis();
        Map<String, Long> cds = readyAt.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        long ready = cds.getOrDefault(a.id, 0L);
        if (ready > now) {
            Msg.error(p, "Your " + a.name + " is on cooldown for " + (int) Math.ceil((ready - now) / 1000.0) + "s.");
            return;
        }
        cds.put(a.id, now + a.cooldown(lv) * 1000L);
        activeUntil.put(p.getUniqueId(), now + a.duration(lv) * 1000L);
        activeId.put(p.getUniqueId(), a.id);
        Msg.send(p, "&aYou used your &6" + a.name + " &aaxe ability! &7(" + a.duration(lv) + "s)");
        p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 0.4f, 1.6f);
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 20, 0.5, 0.8, 0.5, 0);
        long until = now + a.duration(lv) * 1000L;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            Long u = activeUntil.get(p.getUniqueId());
            if (p.isOnline() && u != null && u == until) Msg.send(p, "&7Your &6" + a.name + " &7has worn off.");
        }, a.duration(lv) * 20L);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (p.isOnline()) Msg.send(p, "&a" + a.name + " &ais ready!");
        }, a.cooldown(lv) * 20L);
    }

    static void forget(UUID id) {
        activeUntil.remove(id); activeId.remove(id); readyAt.remove(id); usedTick.remove(id);
    }

    // ================================================================================ menu

    static ItemStack icon(ForagingData d) {
        Ability cur = d.axeAbility == null ? null : byId(d.axeAbility);
        List<String> l = new ArrayList<>();
        l.add("&7Selected: " + (cur == null ? "&cnone" : "&6" + cur.name));
        l.add("");
        l.add("&7Sneak + right-click any axe on a");
        l.add("&7foraging island to use it.");
        l.add("");
        l.add(Hotf.tier(d) >= 2 ? "&eClick to choose!" : "&cRequires Heart of the Forest II");
        return Icons.of(Material.DIAMOND_AXE, "&6Axe Abilities", l);
    }

    static Menu menu(Player p) {
        ForagingData d = ForagingModule.data(p);
        Menu m = new Menu("Axe Abilities", 4).chrome();
        int lv = level(d);
        int slot = 11;
        for (Ability a : ALL) {
            boolean ok = Hotf.tier(d) >= a.tier;
            boolean sel = a.id.equals(d.axeAbility);
            List<String> l = new ArrayList<>();
            l.add("&8Heart of the Forest " + Text.roman(a.tier) + " ability");
            l.add("");
            for (String s : a.lore) l.add(s);
            l.add("");
            l.add("&7Level: &a" + lv + (lv < 2 ? " &8(Center of the Forest II: +1)" : ""));
            l.add("&7Duration: &a" + a.duration(lv) + "s");
            l.add("&8Cooldown: &a" + a.cooldown(lv) + "s");
            l.add("");
            l.add(!ok ? "&cRequires Heart of the Forest " + Text.roman(a.tier) : sel ? "&aSELECTED" : "&eClick to select!");
            ItemStack it = Icons.of(ok ? a.icon : Material.COAL, (ok ? (sel ? "&a" : "&e") : "&c") + a.name, l);
            m.set(slot, sel ? Icons.glow(it) : it, c -> {
                if (!ok) { Msg.error(c.player(), "You need Heart of the Forest " + Text.roman(a.tier) + "!"); return; }
                d.axeAbility = a.id;
                Msg.success(c.player(), "Selected " + a.name + ". Sneak + right-click an axe to use it.");
                menu(c.player()).open(c.player());
            });
            slot += 2;
        }
        m.set(31, Icons.back("Heart of the Forest"), c -> Hotf.menu(c.player()).open(c.player()));
        return m;
    }
}
