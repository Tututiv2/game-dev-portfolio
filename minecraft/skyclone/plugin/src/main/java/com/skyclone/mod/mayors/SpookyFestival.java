package com.skyclone.mod.mayors;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Spooky Festival (research/05 §7.6): Autumn 29-31, one real hour. Every mob kill may drop candy
 * (10%, one in ten of those Purple), spooky mobs roam the Graveyard and Wilderness at night, candy is
 * points (Green 1, Purple 5), the Dread Peddler trades candy for gear, and at the end every player is
 * placed in a bracket (Top 50, Emerald ... Wood) and paid. Points are kept per festival in
 * {@code mayors_spooky} so a restart mid-festival loses nothing.
 */
final class SpookyFestival implements Listener {

    static final class Score {
        String name;
        long points;
        int green, purple;
        boolean dirty;
    }

    private final MayorsModule mod;
    private String festival;                        // key of the running festival, or null
    private final Map<UUID, Score> scores = new LinkedHashMap<>();

    SpookyFestival(MayorsModule mod) { this.mod = mod; }

    boolean active() { return Cal.active(Cal.SPOOKY) || Cal.active(Cal.SPOOKY_EXTRA); }

    String festivalKey() {
        SkyDate d = Sky.calendar().now();
        return "Y" + d.year() + (Cal.active(Cal.SPOOKY_EXTRA) && !Cal.active(Cal.SPOOKY) ? "-encore" : "");
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    /** Every second. Drives the Encore festival and notices starts/ends missed by events. */
    void tick() {
        SkyDate d = Sky.calendar().now();
        int start = ElectionMath.doy(Math.max(1, Math.min(12, mod.dials().spookyExtraMonth.get())), 29);
        int dd = Math.floorMod(ElectionMath.doy(d.month(), d.day()) - start, ElectionMath.DAYS_PER_YEAR);
        Cal.drive(Cal.SPOOKY_EXTRA, mod.election().perkActive("foxy.extra_event")
                && "spooky".equals(mod.election().extraEventVariant()) && dd < 3);
        boolean on = active();
        if (on && festival == null) begin();
        else if (!on && festival != null) end();
        if (on && Bukkit.getCurrentTick() % 200 == 0) flush();
    }

    private void begin() {
        festival = festivalKey();
        scores.clear();
        loadScores(festival);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!EventAlerts.news(p, Cal.SPOOKY)) continue;              // the player's calendar-alert setting
            Msg.send(p, "&6&lSPOOKY FESTIVAL! &eThe festival has begun! Mobs drop &aCandy&e for the next three");
            Msg.send(p, "&eSkyBlock days. Trade it at the &5Dread Peddler &e(/spooky). Spooky mobs prowl at night!");
            Msg.sound(p, Sound.ENTITY_WITCH_CELEBRATE, 0.8f);
        }
    }

    private void loadScores(String key) {
        try {
            Map<UUID, Score> rows = Sky.db().query(c -> {
                Map<UUID, Score> out = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, points, green, purple FROM mayors_spooky WHERE festival = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Score s = new Score();
                            s.name = rs.getString(2);
                            s.points = rs.getLong(3);
                            s.green = rs.getInt(4);
                            s.purple = rs.getInt(5);
                            try { out.put(UUID.fromString(rs.getString(1)), s); } catch (IllegalArgumentException ignored) { }
                        }
                    }
                }
                return out;
            }).get(3, java.util.concurrent.TimeUnit.SECONDS);
            scores.putAll(rows);
        } catch (Exception e) {
            Sky.log().warning("[mayors] could not load spooky scores: " + e);
        }
    }

    void flush() {
        if (festival == null) return;
        String key = festival;
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<UUID, Score> e : scores.entrySet()) {
            Score s = e.getValue();
            if (!s.dirty) continue;
            s.dirty = false;
            rows.add(new Object[]{e.getKey().toString(), s.name, s.points, s.green, s.purple});
        }
        if (rows.isEmpty()) return;
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO mayors_spooky(festival, uuid, name, points, green, purple) VALUES (?,?,?,?,?,?) "
                    + "ON CONFLICT(festival, uuid) DO UPDATE SET name = excluded.name, points = excluded.points, green = excluded.green, purple = excluded.purple")) {
                for (Object[] r : rows) {
                    ps.setString(1, key);
                    ps.setString(2, (String) r[0]);
                    ps.setString(3, (String) r[1]);
                    ps.setLong(4, (Long) r[2]);
                    ps.setInt(5, (Integer) r[3]);
                    ps.setInt(6, (Integer) r[4]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** Rank everyone, pay brackets, hand out pies. */
    void end() {
        flush();
        String key = festival;
        festival = null;
        List<Map.Entry<UUID, Score>> ranked = new ArrayList<>(scores.entrySet());
        ranked.removeIf(e -> e.getValue().points <= 0);
        ranked.sort((a, b) -> Long.compare(b.getValue().points, a.getValue().points));
        List<EventData.Bracket> brackets = mod.data().spooky.brackets;
        double scale = Math.max(0.0001, mod.dials().spookyThresholdScale.get());
        SkyDate d = Sky.calendar().now();
        for (int i = 0; i < ranked.size(); i++) {
            UUID uuid = ranked.get(i).getKey();
            Score s = ranked.get(i).getValue();
            EventData.Bracket b = bracketFor(brackets, i + 1, s.points, scale);
            JsonObject pie = new JsonObject();
            pie.addProperty("year", d.year());
            pie.addProperty("points", s.points);
            pie.addProperty("rank", i + 1);
            pie.addProperty("player", s.name);
            Claims.give(uuid, "SPOOKY_PIE", 1, pie, "spooky festival " + key);
            if (b == null) continue;
            if (b.coins > 0) Sky.economy().depositOffline(uuid, b.coins, "mayors: spooky festival " + b.name + " bracket");
            for (String it : b.items) if (Goods.resolve(it) != null) Claims.give(uuid, it, 1, null, "spooky festival " + b.name);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                try { Sky.service(Features.SkyLevel.class).award(p, "spooky_bracket:" + b.id, b.sbxp, "Spooky Festival " + b.name + " bracket"); }
                catch (Throwable ignored) { }
                Msg.box(p, "&6&lSPOOKY FESTIVAL OVER", List.of(
                        "&7You placed &a#" + (i + 1) + " &7with &6" + Text.commasInt(s.points) + " &7points.",
                        "&7Bracket: &e" + b.name,
                        "&6+" + Text.commasInt(b.coins) + " coins" + (b.items.isEmpty() ? "" : " &7and prizes")));
            }
        }
        if (!ranked.isEmpty()) {
            StringBuilder top = new StringBuilder("&6&lSPOOKY FESTIVAL &7winners: ");
            for (int i = 0; i < Math.min(3, ranked.size()); i++)
                top.append(i == 0 ? "" : "&7, ").append("&e#").append(i + 1).append(" &f").append(ranked.get(i).getValue().name)
                        .append(" &6(").append(Text.commasInt(ranked.get(i).getValue().points)).append(")");
            Msg.broadcast(top.toString());
        } else {
            Msg.broadcast("&6&lSPOOKY FESTIVAL &7is over. Nobody collected any candy this time!");
        }
        scores.clear();
    }

    static EventData.Bracket bracketFor(List<EventData.Bracket> brackets, int rank, long points, double scale) {
        return EventData.bracketFor(brackets, rank, points, scale);
    }

    // ---- candy ---------------------------------------------------------------------------------

    double candyChance(Player p) {
        EventData.Spooky sp = mod.data().spooky;
        double boost = 0;
        try {
            String set = Sky.items().fullSet(p);
            if (set != null) boost += sp.candyBoosts.armorSets.getOrDefault(set, 0.0);
        } catch (Throwable ignored) { }
        double acc = 0;
        try {
            Features.Accessories a = Sky.service(Features.Accessories.class);
            for (Map.Entry<String, Double> e : sp.candyBoosts.accessories.entrySet())
                if (a.has(p, e.getKey())) acc = Math.max(acc, e.getValue());
        } catch (Throwable ignored) { }
        boost += acc;
        try {
            Features.Pets pets = Sky.service(Features.Pets.class);
            if ("BAT".equals(pets.activeType(p))) {
                // the pet's own Sweet Tooth number when pets reports it, else the research's level-scaled figure
                double sweet = pets.abilityValue(p, "bat_candy_lover", "a");
                boost += sweet > 0 ? sweet / 100.0 : sp.candyBoosts.petBat * Math.min(100, pets.activeLevel(p)) / 100.0;
            }
        } catch (Throwable ignored) { }
        if (mod.election().perkActive("foxy.sweet_benevolence")) boost += mod.election().perkValue("foxy.sweet_benevolence") / 100.0;
        boost += mod.carnival().perks().value(p, "candy_scavenger") / 100.0;       // Carnival Perk: +2% a tier
        return sp.baseCandyChance * (1 + boost) * Math.max(0, mod.dials().candyScale.get());
    }

    /** Share of candy drops that are Purple: the base 1 in 10 plus Purple Vogue (+1% a tier). */
    double purpleShare(Player p) {
        return purpleShare(mod.data().spooky.purpleShare, mod.carnival().perks().value(p, "purple_vogue"));
    }

    static double purpleShare(double base, double voguePct) { return Math.min(1, Math.max(0, base + Math.max(0, voguePct) / 100.0)); }

    /**
     * Trick-or-Treat chest chance: 1% (3% from a spooky mob), +1% Lucky Hoof or +2% Eternal Hoof (the better one), +0.5%
     * a tier of the Trick or Treat Enthusiast carnival perk. Pure for the checks.
     */
    static double trickChance(double base, boolean luckyHoof, boolean eternalHoof, double enthusiastPct) {
        double hoof = eternalHoof ? 0.02 : luckyHoof ? 0.01 : 0;
        return Math.min(1, Math.max(0, base) + hoof + Math.max(0, enthusiastPct) / 100.0);
    }

    double trickChance(Player p, boolean spookyMob) {
        EventData.Spooky sp = mod.data().spooky;
        boolean lucky = false, eternal = false;
        try {
            Features.Accessories a = Sky.service(Features.Accessories.class);
            eternal = a.has(p, "ETERNAL_HOOF");
            lucky = !eternal && a.has(p, "LUCKY_HOOF");
        } catch (Throwable ignored) { }
        return trickChance(spookyMob ? sp.trickOrTreatSpookyChance : sp.trickOrTreatChance, lucky, eternal,
                mod.carnival().perks().value(p, "trick_or_treat_enthusiast"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        if (festival == null || e.killer == null || !Sky.profiles().isReady(e.killer)) return;
        Player p = e.killer;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean spookyMob = e.def != null && "spooky".equals(e.def.familyId());
        EventData.Spooky sp = mod.data().spooky;
        if (!spookyMob && r.nextDouble() < candyChance(p)) {
            boolean purple = r.nextDouble() < purpleShare(p);
            Sky.loot().give(p, purple ? "PURPLE_CANDY" : "GREEN_CANDY", 1, LootSource.EVENT);
        }
        if (r.nextDouble() < trickChance(p, spookyMob)) trickOrTreat(p, e.location);
        if (mod.election().perkActive("foxy.a_time_for_giving") && r.nextDouble() < sp.partyChest.chance) {
            Sky.loot().give(p, "PARTY_CHEST", 1, LootSource.EVENT);
            Msg.send(p, "&d&lPARTY CHEST! &7A treat-stuffed chest fell out of the mob!");
        }
    }

    private void trickOrTreat(Player p, Location at) {
        if (ThreadLocalRandom.current().nextBoolean()) {
            int n = 1 + ThreadLocalRandom.current().nextInt(3);
            Sky.loot().give(p, "GREEN_CANDY", n, LootSource.EVENT);
            if (ThreadLocalRandom.current().nextDouble() < 0.25) Sky.loot().give(p, "PURPLE_CANDY", 1, LootSource.EVENT);
            Msg.send(p, "&6&lTREAT! &7The chest was full of candy!");
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
        } else {
            try {
                Sky.mobs().spawn("spooky_wraith", at, p);
                Msg.send(p, "&c&lTRICK! &7A Wraith burst out of the chest!");
                Msg.sound(p, Sound.ENTITY_WITCH_CELEBRATE, 0.6f);
            } catch (Throwable ignored) { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGain(SkyEvents.ItemGain e) {
        if (festival == null || e.source == null || !e.source.natural) return;
        boolean green = "GREEN_CANDY".equals(e.itemId), purple = "PURPLE_CANDY".equals(e.itemId);
        if (!green && !purple) return;
        EventData.Spooky sp = mod.data().spooky;
        Score s = scores.computeIfAbsent(e.player.getUniqueId(), k -> new Score());
        s.name = e.player.getName();
        if (green) { s.green += e.amount; s.points += (long) e.amount * sp.greenPoints; }
        else { s.purple += e.amount; s.points += (long) e.amount * sp.purplePoints; }
        s.dirty = true;
    }

    Score score(UUID uuid) { return scores.get(uuid); }

    int rankOf(UUID uuid) {
        Score me = scores.get(uuid);
        if (me == null) return -1;
        int r = 1;
        for (Score s : scores.values()) if (s.points > me.points) r++;
        return r;
    }

    // ---- items: candle, party chest --------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(held);
        if ("PARTY_CHEST".equals(id)) {
            e.setCancelled(true);
            if (Sky.items().remove(p, "PARTY_CHEST", 1) < 1) return;
            EventData.PartyChest pc = mod.data().spooky.partyChest;
            int g = Goods.between(pc.green), pu = Goods.between(pc.purple), coins = Goods.between(pc.coins);
            if (g > 0) Sky.loot().give(p, "GREEN_CANDY", g, LootSource.EVENT);
            if (pu > 0) Sky.loot().give(p, "PURPLE_CANDY", pu, LootSource.EVENT);
            if (coins > 0) Sky.economy().deposit(p, coins, "mayors: party chest");
            Msg.send(p, "&d&lPARTY CHEST! &7You found &a" + g + " Green Candy&7, &5" + pu + " Purple Candy &7and &6" + coins + " coins&7!");
            Msg.sound(p, Sound.BLOCK_CHEST_OPEN, 1.2f);
        } else if ("HORSEMAN_CANDLE".equals(id)) {
            e.setCancelled(true);
            String island = Sky.worlds().islandOf(p.getWorld());
            Box yard = "hub".equals(island) ? Sky.worlds().area("hub", "graveyard") : null;
            Location l = p.getLocation();
            if (yard == null || !yard.contains(l.getX(), l.getY(), l.getZ())) { Msg.error(p, "The candle only lights in the Graveyard."); return; }
            if (!Sky.calendar().now().isNight()) { Msg.error(p, "The candle will only light at night."); return; }
            if (Sky.items().remove(p, "HORSEMAN_CANDLE", 1) < 1) return;
            try {
                Sky.mobs().spawn("spooky_headless_rider", l.clone().add(l.getDirection().setY(0).normalize().multiply(6)), p);
                Msg.send(p, "&c&lTHE HEADLESS RIDER &7answers your call...");
                Msg.sound(p, Sound.ENTITY_WITHER_SPAWN, 0.8f);
            } catch (Throwable t) {
                Sky.loot().give(p, "HORSEMAN_CANDLE", 1, LootSource.OTHER);
                Msg.error(p, "The candle flickers out. (The rider could not be summoned.)");
            }
        }
    }

    // ---- menus ---------------------------------------------------------------------------------

    /** /spooky : the Dread Peddler's shop + leaderboard. */
    void open(Player p) {
        Menu m = new Menu("Dread Peddler", 6).chrome();
        boolean on = festival != null;
        Score me = scores.get(p.getUniqueId());
        List<String> info = new ArrayList<>();
        info.add("&7Candy drops from every mob during the");
        info.add("&6Spooky Festival &7(&eAutumn 29th - 31st&7).");
        info.add("&7Green Candy is &a1 &7point, Purple &55&7.");
        info.add("");
        if (on) {
            info.add("&aThe festival is running!");
            info.add("&7Your points: &6" + (me == null ? 0 : Text.commasInt(me.points)) + (me == null ? "" : " &8(#" + rankOf(p.getUniqueId()) + ")"));
            info.add("&7Candy chance: &a" + String.format(java.util.Locale.US, "%.1f%%", candyChance(p) * 100));
        } else {
            info.add("&7Next festival: " + Cal.when(Cal.SPOOKY));
        }
        m.set(4, Icons.of(Material.JACK_O_LANTERN, "&6Spooky Festival", info));

        List<EventData.Offer> offers = new ArrayList<>();
        for (EventData.Offer o : mod.data().spooky.shop) if (Goods.available(o)) offers.add(o);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < offers.size() && i < slots.length; i++) {
            EventData.Offer o = offers.get(i);
            m.set(slots[i], ShopUi.offerIcon(p, o, 1.0, on ? null : "&cOnly open during the Spooky Festival."),
                    c -> { if (festival == null) Msg.error(c.player(), "The Dread Peddler only trades during the Spooky Festival!"); else ShopUi.buy(c.player(), o, 1.0, "spooky shop"); open(c.player()); });
        }
        m.set(47, Icons.of(Material.GOLDEN_HELMET, "&6Leaderboard", leaderboardLore(p)));
        m.set(51, Icons.of(Material.PAPER, "&aRewards", rewardLore()));
        m.set(48, Icons.back("Festivals & Events"), c -> EventBoard.open(c.player()));
        m.open(p);
    }

    private List<String> leaderboardLore(Player p) {
        List<String> out = new ArrayList<>();
        List<Map.Entry<UUID, Score>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> Long.compare(b.getValue().points, a.getValue().points));
        if (ranked.isEmpty()) out.add("&7Nobody has any points yet.");
        for (int i = 0; i < Math.min(10, ranked.size()); i++)
            out.add("&e#" + (i + 1) + " &f" + ranked.get(i).getValue().name + " &6" + Text.commasInt(ranked.get(i).getValue().points));
        int r = rankOf(p.getUniqueId());
        if (r > 0) { out.add(""); out.add("&7You: &a#" + r); }
        return out;
    }

    private List<String> rewardLore() {
        List<String> out = new ArrayList<>();
        double scale = mod.dials().spookyThresholdScale.get();
        for (EventData.Bracket b : mod.data().spooky.brackets) {
            String cond = b.rank > 0 ? "Top " + b.rank + " &7and &a" + Text.commasInt(Math.ceil(b.minPoints * scale)) + "+" : Text.commasInt(Math.ceil(b.minPoints * scale)) + "+ points";
            out.add("&e" + b.name + " &8(" + cond + "&8)&7: &6" + Text.shortNum(b.coins) + " coins" + (b.items.isEmpty() ? "" : " &7+ prizes"));
        }
        out.add("");
        out.add("&7Everyone who scores gets a &cSpooky Pie&7.");
        return out;
    }

    List<String> sidebar(Player p) {
        if (festival == null) return List.of();
        Score s = scores.get(p.getUniqueId());
        return sidebarLines(s == null ? 0 : s.green, s == null ? 0 : s.purple, s == null ? 0 : s.points);
    }

    /**
     * The section, pure. Every count carries its NAME: the playtest read "Candy: 5 1 (10 pts)", where green and
     * purple were told apart only by colour - lost on a colour-blind viewer, and on an AI player reading chat-stripped
     * text it is two bare numbers.
     */
    static List<String> sidebarLines(long green, long purple, long points) {
        return List.of("&6Spooky Festival",
                " &7Candy: &a" + Text.commasInt(green) + " Green&7, &5" + Text.commasInt(purple) + " Purple",
                " &7Points: &e" + Text.commasInt(points));
    }

    /** Admin: add points directly. */
    void adminPoints(Player p, long n) {
        Score s = scores.computeIfAbsent(p.getUniqueId(), k -> new Score());
        s.name = p.getName();
        s.points += n;
        s.dirty = true;
    }

    String running() { return festival; }

    Set<UUID> participants() { return new HashSet<>(scores.keySet()); }
}
