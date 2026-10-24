package com.skyclone.mod.hunting;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Salts: consumables that grant Charm Chance (the chance a killed mob drops its shard) for hours.
 * Numbers are the reference items' lore (Lushlilac 0.5 / 8h ... Brine Tonic 2.75 / 24h).
 */
final class Salts {

    private Salts() {}

    static final class Salt {
        String id;
        double charm;
        double hours;
        String target;
    }

    private static final Map<String, Salt> SALTS = new HashMap<>();

    static void load() {
        try (InputStream in = Sky.plugin().getResource("data/hunting/salts.json")) {
            if (in == null) return;
            List<Salt> l = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<Salt>>() {}.getType());
            if (l != null) for (Salt s : l) SALTS.put(s.id, s);
        } catch (Exception e) {
            Sky.log().warning("[hunting] salts.json: " + e);
        }
    }

    static Salt get(String id) { return SALTS.get(id); }

    static Map<String, Salt> all() { return SALTS; }

    /** Left-click with a salt: eat one, extend (not stack) its timer. Berry Mogul makes it last longer. */
    static boolean consume(Player p, ItemStack hand) {
        String id = Sky.items().idOf(hand);
        Salt s = get(id);
        if (s == null || !Hunt.ready(p)) return false;
        HuntingData d = Hunt.data(p);
        double mogul = Hunt.snapshot(p).value("berry_mogul");
        long dur = (long) (s.hours * 3_600_000L * (1 + mogul / 100.0));
        long now = System.currentTimeMillis();
        long cur = Math.max(now, d.salts.getOrDefault(id, 0L));
        long cap = now + dur * 2;   // TUNE: at most two salts' worth banked
        d.salts.put(id, Math.min(cap, cur + dur));
        hand.setAmount(hand.getAmount() - 1);
        Msg.send(p, "&aYou ate a &f" + Text.strip(Sky.items().def(id) == null ? id : Sky.items().def(id).name) + "&a: &d+"
                + HuntMath.fmt(s.charm) + "❣ Charm Chance &afor &e" + Text.duration((d.salts.get(id) - now) / 1000) + "&a.");
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 1.2f);
        return true;
    }
}
