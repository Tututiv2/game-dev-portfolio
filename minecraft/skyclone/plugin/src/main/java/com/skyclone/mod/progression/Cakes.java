package com.skyclone.mod.progression;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Century Cakes (research 08 section 6.7): twenty consumables, each a stat buff lasting 48 real hours
 * (offline too); different cakes stack, eating the same one again resets its timer. Handed out by
 * the Century Celebration (every 100 SkyBlock years, a calendar event) and, TUNE, one random cake
 * per player at each New Year so the feature is actually seen.
 */
final class Cakes implements Listener {

    static final String CENTURY_EVENT = "progression_century_celebration";
    static final String NEW_YEAR_EVENT = "progression_new_year";

    record Cake(String id, Stat stat, double amount) {}

    private final Map<String, Cake> cakes = new LinkedHashMap<>();

    void load() {
        cakes.clear();
        for (ItemDef d : Sky.items().all()) {
            if (d == null || d.id == null || !d.has("century_cake") || d.extra == null) continue;
            JsonElement st = d.extra.get("cakeStat");
            JsonElement am = d.extra.get("cakeAmount");
            if (st == null || am == null) continue;
            Stat s = Stat.parse(st.getAsString());
            if (s == null) continue;
            cakes.put(d.id, new Cake(d.id, s, am.getAsDouble()));
        }
    }

    Map<String, Cake> all() { return cakes; }

    void registerEvents() {
        CalendarEvent century = CalendarEvent.onDate(CENTURY_EVENT, "Century Celebration", 1, 1, 3);
        century.everyYears = 100;   // research 08 section 6.7: every 100 SkyBlock years
        Sky.calendar().register(century);
        Sky.calendar().register(CalendarEvent.onDate(NEW_YEAR_EVENT, "New Year Celebration", 12, 29, 3));
    }

    // ---- eating --------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        String id = hand == null ? null : Sky.items().idOf(hand);
        if (id == null || !cakes.containsKey(id)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Prog.ready(p)) return;
        eat(p, id, hand);
    }

    void eat(Player p, String id, ItemStack hand) {
        Cake c = cakes.get(id);
        if (c == null) return;
        if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
        else p.getInventory().setItemInMainHand(null);
        long until = System.currentTimeMillis() + Prog.cakeHours.get() * 3_600_000L;
        Prog.cakes(p).active.put(id, until);
        ItemDef d = Sky.items().def(id);
        Msg.send(p, "&d&lYUM! &7You ate a " + (d != null ? d.rarity.code() + Text.strip(d.name) : id) + "&7: "
                + "&a+" + Text.commas(c.amount) + " " + c.stat.label() + " &7for &a" + Prog.cakeHours.get() + " hours&7.");
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 1.0f);
        Msg.sound(p, Sound.ENTITY_PLAYER_BURP, 1.0f);
        Sky.stats().invalidate(p);
    }

    void contribute(Player p, StatMap into) {
        if (!Prog.ready(p)) return;
        PData.Cakes d = Prog.cakes(p);
        if (d.active.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = d.active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) { it.remove(); continue; }
            Cake c = cakes.get(e.getKey());
            if (c != null) into.add(c.stat, c.amount);
        }
    }

    // ---- celebrations ------------------------------------------------------------------------------------------

    /** The claim key of the celebration running now, or null. */
    String activeClaim() {
        try {
            int year = Sky.calendar().now().year();
            if (Sky.calendar().active(CENTURY_EVENT)) return "century:" + year;
            if (Prog.cakeYearly.get() && Sky.calendar().active(NEW_YEAR_EVENT)) return "newyear:" + year;
        } catch (Throwable ignored) { }
        return null;
    }

    void claim(Player p) {
        String key = activeClaim();
        if (key == null) { Msg.error(p, "There is no celebration right now."); return; }
        PData.Cakes d = Prog.cakes(p);
        if (d.claimed.contains(key)) { Msg.error(p, "You already claimed your cake this celebration."); return; }
        d.claimed.add(key);
        if (key.startsWith("century:")) {
            for (String id : cakes.keySet()) Sky.loot().give(p, id, 1, LootSource.EVENT);
            Msg.success(p, "The Century Celebration! You received every Century Cake.");
        } else {
            List<String> ids = new ArrayList<>(cakes.keySet());
            if (ids.isEmpty()) return;
            String id = ids.get(new Random().nextInt(ids.size()));
            Sky.loot().give(p, id, 1, LootSource.EVENT);
            Msg.success(p, "Happy New Year! Enjoy a free cake.");
        }
        Msg.sound(p, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f);
    }

    @EventHandler
    public void onCalendar(SkyEvents.CalendarStart e) {
        if (!CENTURY_EVENT.equals(e.eventId) && !(NEW_YEAR_EVENT.equals(e.eventId) && Prog.cakeYearly.get())) return;
        String what = CENTURY_EVENT.equals(e.eventId) ? "The &d&lCentury Celebration" : "The &e&lNew Year Celebration";
        Msg.broadcast("&6&l✦ " + what + " &6has begun! &eType /cakes to claim your cake!");
    }

    // ---- menu --------------------------------------------------------------------------------------------------

    void openMenu(Player p) {
        if (!Prog.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        buildMenu(p).open(p);
    }

    Menu buildMenu(Player viewer) { return buildMenu(viewer, Prog.cakes(viewer)); }

    Menu buildMenu(Player viewer, PData.Cakes d) {
        Menu m = new Menu("Century Cakes", 6).chrome();
        long now = System.currentTimeMillis();
        int active = 0;
        for (Long v : d.active.values()) if (v > now) active++;
        m.set(4, Icons.of(Material.CAKE, "&dCentury Cakes",
                "&7Each cake grants a stat for &a" + Prog.cakeHours.get() + " hours&7,", "&7counting while you are offline.",
                "&7Different cakes stack.", "", "&7Active cakes: &a" + active + "&7/&a" + cakes.size()));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (Cake c : cakes.values()) {
            if (i >= slots.length) break;
            ItemDef def = Sky.items().def(c.id);
            String name = def != null ? def.rarity.code() + Text.strip(def.name) : c.id;
            Long until = d.active.get(c.id);
            boolean on = until != null && until > now;
            List<String> lore = new ArrayList<>();
            lore.add("&7Bonus: &a+" + Text.commas(c.amount) + " " + c.stat.label());
            lore.add("");
            lore.add(on ? "&aActive &7- &e" + Text.duration((until - now) / 1000) + " left" : "&cNot active");
            ItemStack icon = def != null && def.skull != null && !def.skull.isEmpty()
                    ? Icons.skull(def.skull, name, lore) : Icons.of(Material.CAKE, name, lore);
            m.set(slots[i++], on ? Icons.glow(icon) : icon);
        }
        String claim = activeClaim();
        boolean claimed = claim != null && d.claimed.contains(claim);
        List<String> cl = new ArrayList<>();
        if (claim == null) {
            cl.add("&7Cakes are baked for the New Year");
            cl.add("&7and, once a century, all at once.");
            try {
                long ms = Sky.calendar().millisUntil(Prog.cakeYearly.get() ? NEW_YEAR_EVENT : CENTURY_EVENT);
                cl.add("");
                cl.add("&7Next celebration in &e" + Text.duration(ms / 1000));
            } catch (Throwable ignored) { }
        } else {
            cl.add("&aA celebration is on right now!");
            cl.add("");
            cl.add(claimed ? "&7You already claimed your cake." : "&eClick to claim!");
        }
        m.set(49 - 9, Icons.of(claim != null && !claimed ? Material.CHEST_MINECART : Material.CLOCK, "&aCelebration", cl), c -> {
            claim(c.player());
            buildMenu(c.player()).open(c.player());
        });
        Prog.backToSkyMenu(m);
        return m;
    }
}
