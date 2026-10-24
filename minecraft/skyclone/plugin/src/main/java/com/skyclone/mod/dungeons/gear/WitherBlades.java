package com.skyclone.mod.dungeons.gear;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.DungeonMenus;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.Progress;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The three wither scrolls (research 04 sec 3.14: "Handle + Implosion + Shadow Warp + Wither Shield = Wither Impact")
 * bound to a Warden's Blade or one of its four refinements at the Scroll Altar ({@code /dungeons scrolls}). The blades
 * themselves are the weapons module's items; the scrolls are ours, kept in {@code ItemData.extra.dungeon_scrolls}.
 * Right-click casts every bound scroll: Implosion (10,000 magic damage, 0.3 Int scaling, 10 blocks; 300 mana),
 * Shadow Warp (10 blocks forward; 300 mana), Wither Shield (10% less damage for 5 s plus absorption that grows with
 * Catacombs level; 150 mana). All three together are Wither Impact: 300 mana, 0.15 s cooldown.
 */
public final class WitherBlades implements Listener {

    private WitherBlades() {}

    /** Last "not enough mana" chat line per player (3 s throttle). */
    private static final Map<UUID, Long> NO_MANA_MSG = new HashMap<>();

    public static final List<String> BLADES = List.of("NECRON_BLADE", "HYPERION", "VALKYRIE", "ASTRAEA", "SCYLLA");
    public static final List<String> SCROLLS = List.of("implosion", "shadow_warp", "wither_shield");
    static final String KEY = "dungeon_scrolls";
    private static final Map<UUID, Long> shield = new HashMap<>();

    public static long shieldUntil(Player p) { return shield.getOrDefault(p.getUniqueId(), 0L); }

    public static void init() {
        Sky.items().addLoreContributor("dungeons_wither_scrolls", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) { return lore(def, data); }
        });
        Bukkit.getPluginManager().registerEvents(new WitherBlades(), Sky.plugin());
    }

    public static Set<String> scrolls(ItemData d) {
        Set<String> out = new LinkedHashSet<>();
        if (d != null && d.extra != null && d.extra.has(KEY))
            for (String s : d.extra.get(KEY).getAsString().split(",")) if (SCROLLS.contains(s)) out.add(s);
        return out;
    }

    static List<String> lore(ItemDef def, ItemData data) {
        if (def == null || !BLADES.contains(def.id)) return List.of();
        Set<String> s = scrolls(data);
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) { out.add("&8Scrolls: none &7(bind them at the Scroll Altar)"); return out; }
        if (s.size() == 3) {
            out.add("&6Ability: Wither Impact &e&lRIGHT CLICK");
            out.add("&7Step &a10 &7blocks forward, blast every");
            out.add("&7monster within &a10 &7blocks for &c" + Text.commasInt(DDials.SCROLL_DAMAGE.get()));
            out.add("&7damage and gain a &6Wither Shield&7.");
            out.add("&8Mana Cost: &3300");
            out.add("&8Cooldown: &a0.15s");
            return out;
        }
        for (String k : s) {
            switch (k) {
                case "implosion" -> { out.add("&6Ability: Implosion &e&lRIGHT CLICK"); out.add("&7Blast every monster within &a10 &7blocks."); out.add("&8Mana Cost: &3300"); }
                case "shadow_warp" -> { out.add("&6Ability: Shadow Warp &e&lRIGHT CLICK"); out.add("&7Step &a10 &7blocks forward."); out.add("&8Mana Cost: &3300"); }
                default -> { out.add("&6Ability: Wither Shield &e&lRIGHT CLICK"); out.add("&7Take &a10% &7less damage for &a5s&7, plus a shield."); out.add("&8Mana Cost: &3150"); }
            }
        }
        out.add("&8Cooldown: &a10s");
        return out;
    }

    // ---- casting ---------------------------------------------------------------------------------------------------

    /** Last time a player's entity click was consumed by a puzzle/boss prop (that click must not also cast). */
    private static final Map<UUID, Long> PROP_CLICK = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPropClick(PlayerInteractEntityEvent e) {
        if (e.isCancelled()) PROP_CLICK.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    /**
     * wave-4 endgame: opening the Three Strangers chest with a Highlord in hand opened it AND fired Wither Impact
     * (teleport, shield, 300 mana). A right-click that the dungeon already consumed (a puzzle chest, lantern, lever,
     * wither door, secret: those listeners run at LOW and cancel the click), a right-click on a block that does
     * something by itself (chest, barrel, door, lever, button, workstation... as in vanilla, using the block wins),
     * and the item-use packet that follows a consumed puzzle/boss entity click do not cast. Sneak + right-click on an
     * interactable block still casts, so nothing becomes impossible.
     */
    static boolean clickTaken(PlayerInteractEvent e) {
        if (e.useItemInHand() == Event.Result.DENY) return true;
        Player p = e.getPlayer();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && !p.isSneaking()
                && e.getClickedBlock().getType().isInteractable()) return true;
        Long t = PROP_CLICK.get(p.getUniqueId());
        return t != null && System.currentTimeMillis() - t < 250;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (clickTaken(e)) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(held);
        if (id == null || !BLADES.contains(id)) return;
        Set<String> s = scrolls(Sky.items().data(held));
        if (s.isEmpty()) return;
        if (!Sky.items().meetsRequirements(p, held, true)) return;
        boolean impact = s.size() == 3;
        String cd = impact ? "dungeons_wither_impact" : "dungeons_wither_scroll";
        if (Sky.abilities().cooldownLeft(p, cd) > 0) { if (!impact) Sky.vitals().flash(p, "&cThis ability is on cooldown.", 20); return; }
        double cost = impact ? 300 : s.stream().mapToDouble(k -> k.equals("wither_shield") ? 150 : 300).sum();
        if (!Sky.vitals().useMana(p, cost)) {
            // wave-4 endgame playtest: Wither Impact (0.15 s cooldown) printed this once per click when out of mana.
            Sky.vitals().flash(p, "&c&lNOT ENOUGH MANA", 30);
            long nowMs = System.currentTimeMillis();
            Long last = NO_MANA_MSG.get(p.getUniqueId());
            if (last == null || nowMs - last >= 3000) { NO_MANA_MSG.put(p.getUniqueId(), nowMs); Msg.send(p, "&cYou do not have enough mana!"); }
            return;
        }
        e.setCancelled(true);
        Sky.abilities().setCooldown(p, cd, impact ? 0.15 : 10);
        if (s.contains("shadow_warp")) warp(p);
        if (s.contains("implosion")) implode(p);
        if (s.contains("wither_shield")) shield(p);
        Sky.vitals().flash(p, "&b-" + Text.commasInt(cost) + " Mana (&6" + (impact ? "Wither Impact" : "Scroll") + "&b)", 25);
    }

    static void warp(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, dir, 10, FluidCollisionMode.NEVER, true);
        double dist = hit == null ? 10 : Math.max(0, hit.getHitPosition().distance(eye.toVector()) - 1);
        Location to = p.getLocation().add(dir.clone().multiply(dist));
        to.setYaw(p.getLocation().getYaw());
        to.setPitch(p.getLocation().getPitch());
        if (to.getBlock().getType().isSolid() || to.clone().add(0, 1, 0).getBlock().getType().isSolid()) return;
        p.teleport(to);
        p.setFallDistance(0);
        Fx.sound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
    }

    static void implode(Player p) {
        Location at = p.getLocation();
        Fx.particle(at.clone().add(0, 1, 0), Particle.EXPLOSION_EMITTER, 1, 0, 0);
        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
        for (Entity n : p.getNearbyEntities(10, 6, 10))
            if (n instanceof LivingEntity le && !(n instanceof Player) && Sky.mobs().isSkyMob(le))
                Sky.combat().magicDamage(p, le, DDials.SCROLL_DAMAGE.get(), 0.3, "Implosion");
    }

    /** Wither Shield's length: the damage cut AND the absorption (lore: "for 5s, plus a shield"). */
    static final long SHIELD_TICKS = 100;

    static void shield(Player p) {
        long now = System.currentTimeMillis();
        if (now < shield.getOrDefault(p.getUniqueId(), 0L)) return;
        shield.put(p.getUniqueId(), now + SHIELD_TICKS * 50);
        double amount = 100 + 25 * Progress.cataLevel(p);     // TUNE: research only says it grows with Catacombs level
        // playtest-endgame: a plain addAbsorption never decayed (600 absorption kept for minutes, across warps)
        TimedShields.grant(p, amount, SHIELD_TICKS);
        Fx.particle(p.getLocation().add(0, 1, 0), Particle.ENCHANTED_HIT, 20, 0.6, 0.1);
    }

    // ---- the Scroll Altar ------------------------------------------------------------------------------------------

    static final Map<String, String> SCROLL_ITEM = Map.of("implosion", "IMPLOSION_SCROLL", "shadow_warp", "SHADOW_WARP_SCROLL", "wither_shield", "WITHER_SHIELD_SCROLL");

    public static void altar(Player p) {
        Menu m = new Menu("Scroll Altar", 5).chrome();
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(held);
        boolean blade = id != null && BLADES.contains(id);
        m.set(13, blade ? Icons.withLore(held.clone(), List.of("", "&aThis blade is in your hand.")) : Icons.of(Material.IRON_SWORD, "&cHold a blade",
                "&7Hold a &6Warden's Blade &7or one of its", "&7refinements in your main hand, then", "&7come back here."));
        Set<String> have = blade ? scrolls(Sky.items().data(held)) : Set.of();
        int slot = 29;
        for (String k : SCROLLS) {
            String item = SCROLL_ITEM.get(k);
            boolean bound = have.contains(k);
            int count = Sky.items().exists(item) ? Sky.items().count(p, item) : 0;
            ItemStack icon = Sky.items().exists(item) ? Sky.items().create(item, 1) : Icons.of(Material.PAPER, "&5" + Text.titleCase(k));
            m.set(slot, Icons.withLore(icon, List.of("", bound ? "&aBound to this blade." : count > 0 ? "&eClick to bind (uses 1)!" : "&cYou have none.")), c -> bind(c.player(), k));
            slot += 2;
        }
        m.set(40, Icons.back("Catacombs"), c -> DungeonMenus.main(c.player()));
        m.open(p);
    }

    static void bind(Player p, String k) {
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(held);
        if (id == null || !BLADES.contains(id)) { Msg.error(p, "Hold the blade in your main hand."); return; }
        Set<String> have = scrolls(Sky.items().data(held));
        if (have.contains(k)) { Msg.error(p, "That scroll is already bound."); return; }
        String item = SCROLL_ITEM.get(k);
        if (!Sky.items().exists(item) || Sky.items().count(p, item) < 1) { Msg.error(p, "You need the scroll in your inventory."); return; }
        int heldSlot = p.getInventory().getHeldItemSlot();
        if (Sky.items().remove(p, item, 1) < 1) { Msg.error(p, "You need the scroll in your inventory."); return; }
        // the blade must still be the one in hand (the removal cannot have touched it: it is a different id)
        ItemStack blade = p.getInventory().getItem(heldSlot);
        if (blade == null || !id.equals(Sky.items().idOf(blade))) { Sky.loot().give(p, item, 1, com.skyclone.api.loot.LootSource.OTHER); Msg.error(p, "Hold the blade in your main hand."); return; }
        have.add(k);
        String joined = String.join(",", have);
        Sky.items().update(blade, d -> d.extra.add(KEY, new JsonPrimitive(joined)));
        p.getInventory().setItem(heldSlot, blade);   // write back explicitly (a non-mirror copy would otherwise lose the bind)
        Msg.success(p, "Bound " + Text.titleCase(k) + " to your blade." + (have.size() == 3 ? " &6Wither Impact &ais awake!" : ""));
        Fx.sound(p, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
        altar(p);
    }
}
