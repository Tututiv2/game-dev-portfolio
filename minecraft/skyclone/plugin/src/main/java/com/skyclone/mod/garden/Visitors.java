package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Garden visitors (research 08 1.10): NPCs from all over the world drop by asking for crops and
 * pay Copper, Garden XP, Farming XP, sometimes bits and a rare item. 140 of them, renamed, gated
 * by Garden level and by having visited the island they come from.
 */
final class Visitors {

    static String npcId(String visitorId) { return "garden_visitor_" + visitorId; }

    static String color(String rarity) {
        try { return Rarity.valueOf(rarity).code(); } catch (Exception e) { return "&a"; }
    }

    void registerNpcs() {
        for (GardenTables.Visitor v : G.T.allVisitors()) {
            NpcDef def = NpcDef.of(npcId(v.id), color(v.rarity) + v.name, G.ISLAND, "visitor.1")
                    .title(color(v.rarity) + "&l" + v.rarity);
            def.entity = v.entity == null ? "MANNEQUIN" : v.entity;
            def.lines.add("&eCLICK");
            Sky.npcs().register(def, p -> clicked(p, v.id));
        }
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 200L, 400L);
    }

    private void clicked(Player p, String visitorId) {
        if (!G.ready(p)) return;
        GardenData d = G.data(p);
        GardenData.VisitorState s = find(d, visitorId);
        GardenTables.Visitor v = G.T.visitor(visitorId);
        if (s == null || v == null || !G.inOwnGarden(p)) {
            Msg.send(p, "&e[NPC] " + color(v == null ? "UNCOMMON" : v.rarity) + (v == null ? "Visitor" : v.name) + "&f: I'm only visiting a friend today.");
            return;
        }
        offer(p, s);
    }

    static GardenData.VisitorState find(GardenData d, String id) {
        for (GardenData.VisitorState s : d.visitors) if (s.id.equals(id)) return s;
        return null;
    }

    // =====================================================================================
    // the clock
    // =====================================================================================

    long interval(GardenData d, boolean online) { return interval(d, online, 0); }

    /** {@code gratitudePct}: the Gratitude line's "visitors appear N% faster while you're in The Garden". */
    long interval(GardenData d, boolean online, double gratitudePct) {
        double minutes = online ? G.visitorMinutes.get() : G.visitorOfflineMinutes.get();
        double f = 1;
        if (G.perk(G.visitorPerk.get())) f *= 0.75;
        if (online) f *= 1 - Math.max(0, Math.min(50, gratitudePct)) / 100.0;
        return (long) Math.max(10_000, minutes * 60_000 * f);
    }

    /** Best Gratitude talisman among the members standing in this garden right now (0 when nobody is there). */
    static double gratitude(String key) {
        double best = 0;
        for (Player m : G.members(key)) if (G.onGarden(m) && G.inOwnGarden(m)) best = Math.max(best, Effects.gratitudePct(m));
        return best;
    }

    /** Garden key -> when one of its members last harvested a Garden crop (the Quickdraw chip). */
    private static final java.util.Map<String, Long> HARVESTED = new java.util.concurrent.ConcurrentHashMap<>();
    static final long TICK_MILLIS = 20_000L, HARVEST_WINDOW = 30_000L;

    static void harvested(String key) { if (key != null) HARVESTED.put(key, System.currentTimeMillis()); }

    static boolean harvesting(String key, long now) {
        Long t = HARVESTED.get(key);
        return t != null && now - t <= HARVEST_WINDOW;
    }

    /** Every 20 s: visitors arrive for each profile that has a member online. */
    private void tick() {
        Set<String> done = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!G.ready(p)) continue;
            String key = G.key(p);
            if (!done.add(key)) continue;
            GardenData d = G.data(p);
            d.lastSeen = now;
            if (!d.started) continue;
            double grat = gratitude(key);
            if (d.nextVisitorAt <= 0) d.nextVisitorAt = now + interval(d, true, grat);
            // Quickdraw chip: while someone is harvesting, the wait shrinks by its % on top of the time passing
            double qd = G.chipValue(d, "visitor_harvest");
            if (qd > 0 && harvesting(key, now)) d.nextVisitorAt -= EffectMath.quickdrawExtraMillis(TICK_MILLIS, qd);
            int guard = 0;
            while (now >= d.nextVisitorAt && d.visitors.size() < G.visitorMax.get() && guard++ < 6) {
                if (!arrive(p, d, true)) break;
                d.nextVisitorAt += interval(d, true, grat);
            }
            if (d.visitors.size() >= G.visitorMax.get() || now > d.nextVisitorAt) d.nextVisitorAt = Math.max(d.nextVisitorAt, now + interval(d, true, grat));
        }
    }

    /** Offline catch-up (research 08 1.10: visitors keep coming at a slower rate while you are away). */
    void offline(Player p, GardenData d) {
        if (!d.started || d.lastSeen <= 0) return;
        long now = System.currentTimeMillis();
        long iv = interval(d, false);
        long away = Math.max(0, now - d.lastSeen);
        long due = away / iv;
        int n = (int) Math.max(0, Math.min(G.visitorMax.get() - d.visitors.size(), due));
        int came = 0;
        for (int i = 0; i < n; i++) if (arrive(p, d, false)) came++;
        if (due > 0) {
            // the offline clock has paid out: the online clock restarts from what is left of the current
            // offline interval (otherwise the first tick saw nextVisitorAt hours in the past and filled
            // every slot at once, ignoring the slower offline rate)
            long left = iv - (away - due * iv);
            d.nextVisitorAt = now + Math.max(10_000L, Math.min(interval(d, true), left));
        }
        if (came > 0) Msg.send(p, "&a&lVISITORS! &a" + came + " &7visitor" + (came == 1 ? " is" : "s are") + " waiting in your Garden.");
    }

    /** A visitor arrives; false when nobody is eligible. */
    boolean arrive(Player p, GardenData d, boolean announce) {
        GardenTables.Visitor v = choose(p, d);
        if (v == null) return false;
        GardenData.VisitorState s = makeOffer(p, d, v);
        Set<Integer> used = new HashSet<>();
        for (GardenData.VisitorState o : d.visitors) used.add(o.slot);
        for (int i = 1; i <= 6; i++) if (!used.contains(i)) { s.slot = i; break; }
        d.visitors.add(s);
        GardenData.VisitorLog log = d.log.computeIfAbsent(v.id, k -> new GardenData.VisitorLog());
        log.visits++;
        log.last = System.currentTimeMillis();
        if (announce) for (Player m : G.members(G.key(p))) {
            Msg.send(m, "&a&lVISITOR! " + color(v.rarity) + v.name + " &7has arrived on your &aGarden&7!");
            Msg.sound(m, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.4f);
        }
        syncNpcs(G.key(p), d);
        return true;
    }

    GardenTables.Visitor choose(Player p, GardenData d) {
        int lvl = G.level(d);
        int unique = d.uniqueServed();
        // Copper Talisman line: RARE-or-better visitors are 4/8/12% more likely to arrive
        double copperBoost = 1 + Effects.copperRarePct(p) / 100.0;
        List<GardenTables.Visitor> cand = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        double total = 0;
        for (GardenTables.Visitor v : G.T.allVisitors()) {
            if (v.level > lvl || find(d, v.id) != null) continue;
            if (v.requirement != null && !v.requirement.isBlank() && !requirementOk(p, v.requirement, unique)) continue;
            double wt = G.T.rarity(v.rarity).weight;
            if (rarityRank(v.rarity) >= 1) wt *= copperBoost;
            // never-seen visitors are a little more likely, so the logbook fills
            if (!d.log.containsKey(v.id)) wt *= 1.5;
            // the Carpenter brings the greenhouse blueprint: make sure a Garden VII player meets them soon
            if ("carpenter".equals(v.id) && !d.greenhouse.unlocked && !d.onceGiven.contains("carpenter")) wt *= 10;
            cand.add(v);
            w.add(wt);
            total += wt;
        }
        if (cand.isEmpty()) return null;
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (int i = 0; i < cand.size(); i++) { r -= w.get(i); if (r <= 0) return cand.get(i); }
        return cand.get(cand.size() - 1);
    }

    private static boolean requirementOk(Player p, String req, int unique) {
        for (String part : req.split("&")) {
            String k = part.trim();
            if (k.isEmpty()) continue;
            if (k.startsWith("garden:unique:")) {
                try { if (unique < Integer.parseInt(k.substring(14))) return false; } catch (NumberFormatException e) { return false; }
                continue;
            }
            if (!Sky.requirements().check(p, k)) return false;
        }
        return true;
    }

    /** The request and rewards, decided on arrival so the offer is stable (research 08 13.4). */
    GardenData.VisitorState makeOffer(Player p, GardenData d, GardenTables.Visitor v) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int lvl = G.level(d);
        GardenTables.RarityTune rt = G.T.rarity(v.rarity);
        List<String> opts = new ArrayList<>();
        for (String w : v.wants) {
            GardenTables.Crop c = G.T.cropByItem(w);
            if (G.exists(w) && (c == null || c.level <= lvl)) opts.add(w);
        }
        if (opts.isEmpty()) for (GardenTables.Crop c : G.T.crops()) if (c.level <= lvl && G.exists(c.item)) opts.add(c.item);
        if (opts.isEmpty()) opts.add("WHEAT");
        String item = opts.get(r.nextInt(opts.size()));
        GardenTables.Crop crop = G.T.cropByItem(item);
        long amount;
        double rawUnits;
        if (crop != null) {
            long raw = GardenMath.requestRaw(lvl, rt.size, (crop.baseMin + crop.baseMax) / 2.0, r.nextDouble());
            rawUnits = raw;
            if (raw >= 640 && G.exists(crop.enchanted)) { item = crop.enchanted; amount = Math.max(1, Math.round(raw / 160.0)); }
            else amount = raw;
        } else {
            amount = Math.max(1, Math.round((1 + lvl / 3.0) * rt.size * (0.7 + 0.6 * r.nextDouble())));
            rawUnits = amount * 160;
        }
        GardenData.VisitorState s = new GardenData.VisitorState();
        s.id = v.id;
        s.item = item;
        s.amount = amount;
        // rarer visitors at higher levels sometimes want a second crop as well (research 08 1.10: 1-2 item types)
        double second = rarityRank(v.rarity) <= 0 ? 0 : Math.min(0.6, 0.08 * rarityRank(v.rarity) + 0.02 * lvl);   // TUNE
        if (r.nextDouble() < second) {
            List<GardenTables.Crop> other = new ArrayList<>();
            for (GardenTables.Crop c : G.T.crops())
                if (c.level <= lvl && G.exists(c.item) && c != crop && !c.item.equals(item) && !item.equals(c.enchanted)) other.add(c);
            if (!other.isEmpty()) {
                GardenTables.Crop c2 = other.get(r.nextInt(other.size()));
                long raw2 = Math.max(10, GardenMath.requestRaw(lvl, rt.size, (c2.baseMin + c2.baseMax) / 2.0, r.nextDouble()) / 2);
                rawUnits += raw2;
                if (raw2 >= 640 && G.exists(c2.enchanted)) { s.item2 = c2.enchanted; s.amount2 = Math.max(1, Math.round(raw2 / 160.0)); }
                else { s.item2 = c2.item; s.amount2 = raw2; }
            }
        }
        s.copper = Math.round(GardenMath.offerCopper(rt.copperMin, rt.copperMax, lvl, r.nextDouble()) * G.visitorCopperMult.get());
        s.gardenXp = (int) Math.max(1, Math.round(rt.gardenXp * (1 + lvl * 0.5) * G.visitorXpMult.get()));      // TUNE
        s.farmingXp = Math.round(rawUnits * 0.75 * G.visitorXpMult.get());                                        // TUNE
        s.bits = rt.bits > 0 && r.nextDouble() < 0.5 ? rt.bits : 0;
        s.arrivedAt = System.currentTimeMillis();
        return s;
    }

    // =====================================================================================
    // NPC bodies in the instance
    // =====================================================================================

    /** Place / move / remove the visitor bodies of one garden. Safe to call any time. */
    void syncNpcs(String key, GardenData d) {
        boolean anyone = false;
        for (Player m : G.members(key)) if (G.onGarden(m) && G.inOwnGarden(m)) anyone = true;
        Set<String> present = new HashSet<>();
        for (GardenData.VisitorState s : d.visitors) present.add(s.id);
        for (GardenTables.Visitor v : G.T.allVisitors()) {
            if (!present.contains(v.id) || !anyone) Sky.npcs().despawn(npcId(v.id), key);
        }
        if (!anyone) return;
        for (GardenData.VisitorState s : d.visitors) {
            Location at = G.anchor(key, "visitor." + Math.max(1, Math.min(6, s.slot)));
            if (at != null) Sky.npcs().spawnAt(npcId(s.id), key, at);
        }
    }

    void despawnAll(String key) {
        for (GardenTables.Visitor v : G.T.allVisitors()) Sky.npcs().despawn(npcId(v.id), key);
    }

    // =====================================================================================
    // the offer
    // =====================================================================================

    static Material eggOf(String entity) {
        if (entity == null) return Material.PLAYER_HEAD;
        return switch (entity) {
            case "VILLAGER" -> Material.VILLAGER_SPAWN_EGG;
            case "WANDERING_TRADER" -> Material.WANDERING_TRADER_SPAWN_EGG;
            case "WITCH" -> Material.WITCH_SPAWN_EGG;
            case "PIGLIN" -> Material.PIGLIN_SPAWN_EGG;
            case "SNOW_GOLEM" -> Material.SNOW_GOLEM_SPAWN_EGG;
            default -> Material.PLAYER_HEAD;
        };
    }

    void offer(Player p, GardenData.VisitorState s) {
        GardenTables.Visitor v = G.T.visitor(s.id);
        if (v == null) return;
        GardenData d = G.data(p);
        GardenData.VisitorLog log = d.log.get(s.id);
        Menu m = new Menu(v.name, 3).chrome();
        m.set(13, Icons.of(eggOf(v.entity), color(v.rarity) + v.name,
                color(v.rarity) + "&l" + v.rarity,
                "",
                "&7Times visited: &a" + (log == null ? 0 : log.visits),
                "&7Offers accepted: &a" + (log == null ? 0 : log.accepted)));
        long have = G.count(p, s.item);
        long have2 = s.item2 == null ? 0 : G.count(p, s.item2);
        boolean enough = have >= s.amount && (s.item2 == null || have2 >= s.amount2);
        List<String> acc = new ArrayList<>();
        acc.add("&7Items Required:");
        acc.add(" " + G.itemName(s.item) + " &8x" + Text.commasInt(s.amount) + (have >= s.amount ? " &a✔" : " &c(" + Text.commasInt(have) + ")"));
        if (s.item2 != null)
            acc.add(" " + G.itemName(s.item2) + " &8x" + Text.commasInt(s.amount2) + (have2 >= s.amount2 ? " &a✔" : " &c(" + Text.commasInt(have2) + ")"));
        acc.add("");
        acc.add("&7Rewards:");
        acc.add(" &8+&3" + Text.shortNum(s.farmingXp) + " &7Farming XP");
        acc.add(" &8+&2" + s.gardenXp + " &7Garden Experience");
        long copperNow = copperFor(p, s);
        acc.add(" &8+&c" + Text.commasInt(copperNow) + " Copper" + (copperNow > s.copper ? " &8(+" + Text.commasInt(copperNow - s.copper) + " bonus)" : ""));
        if (s.bits > 0) acc.add(" &8+&b" + s.bits + " Bits");
        if ("carpenter".equals(s.id) && !d.greenhouse.unlocked && !d.onceGiven.contains("carpenter") && G.level(d) >= 7)
            acc.add(" &8+&9Greenhouse Blueprint");
        acc.add("");
        acc.add(enough ? "&eClick to give!" : "&cYou don't have enough!");
        m.set(11, Icons.of(Material.GREEN_TERRACOTTA, "&aAccept Offer", acc), c -> accept(c.player(), s.id));
        m.set(15, Icons.of(Material.RED_TERRACOTTA, "&cRefuse Offer", "&7The visitor leaves without a fuss.", "", "&eClick to refuse!"),
                c -> Sky.menus().confirm(c.player(), "Refuse " + v.name + "?", List.of("&7They will leave your Garden."),
                        () -> refuse(c.player(), s.id), () -> offer(c.player(), s)));
        m.set(4, Icons.of(Material.PAPER, "&aRequest Details",
                "&7" + v.name + " wants " + G.itemName(s.item) + (s.item2 == null ? "" : " &7and " + G.itemName(s.item2)) + "&7.",
                "&7You have &e" + Text.commasInt(have) + (s.item2 == null ? "" : " &7and &e" + Text.commasInt(have2)) + " &7(inventory + sacks).",
                "",
                "&8Requests grow with your Garden level."));
        m.open(p);
    }

    void accept(Player p, String visitorId) {
        if (!G.ready(p)) return;
        GardenData d = G.data(p);
        GardenData.VisitorState s = find(d, visitorId);
        GardenTables.Visitor v = G.T.visitor(visitorId);
        if (s == null || v == null) { p.closeInventory(); return; }
        java.util.Map<String, Long> need = new java.util.LinkedHashMap<>();
        need.put(s.item, s.amount);
        if (s.item2 != null && s.amount2 > 0) need.merge(s.item2, s.amount2, Long::sum);
        if (!G.take(p, need)) {
            Msg.error(p, "You need " + Text.commasInt(s.amount) + "x " + Text.strip(G.itemName(s.item))
                    + (s.item2 == null ? "" : " and " + Text.commasInt(s.amount2) + "x " + Text.strip(G.itemName(s.item2))) + "!");
            return;
        }
        d.visitors.remove(s);
        int uniqueBefore = d.uniqueServed();
        GardenData.VisitorLog log = d.log.computeIfAbsent(v.id, k -> new GardenData.VisitorLog());
        log.accepted++;
        d.offersAccepted++;
        G.member(p).visitorsAccepted++;
        long copper = copperFor(p, s);
        d.copper += copper;
        List<String> grew = Effects.countServed(p);
        if (s.bits > 0) Sky.economy().addBits(p, s.bits, "garden: visitor " + v.id);
        G.farmingXp(p, s.farmingXp, "garden:visitor");
        List<String> body = new ArrayList<>();
        body.add("&a&lREWARDS");
        body.add("  &8+&3" + Text.commasInt(s.farmingXp) + " &7Farming XP");
        body.add("  &8+&2" + s.gardenXp + " &7Garden Experience");
        body.add("  &8+&c" + Text.commasInt(copper) + " Copper");
        if (s.bits > 0) body.add("  &8+&b" + s.bits + " Bits");
        for (String g : grew) body.add("  &8+&6Piece Bonus &7on " + G.itemName(g));
        for (String bonus : bonuses(d, v)) {
            G.give(p, bonus, 1, LootSource.GARDEN);
            body.add("  &8+&r" + G.itemName(bonus));
            if (!"GREENHOUSE_BLUEPRINT".equals(bonus)) Msg.rareDrop(p, "&6&lRARE REWARD!", G.itemName(bonus), 0);
        }
        Msg.box(p, "&6&lOFFER ACCEPTED &8with " + color(v.rarity) + v.name + " &8(" + color(v.rarity) + v.rarity + "&8)", body);
        Msg.sound(p, Sound.ENTITY_VILLAGER_YES, 1f);
        G.gardenXp(p, d, s.gardenXp, "visitor");
        trackMilestones(p, d, uniqueBefore);
        syncNpcs(G.key(p), d);
        p.closeInventory();
        Sky.sidebar().refresh(p);
    }

    /**
     * Copper the visitor pays THIS player: the offer's copper (decided on arrival, chips included) plus the
     * accepting player's own bonuses - Atmospheric Filter in Winter (+5%) and the Rose Dragon's Garden Grace.
     */
    static long copperFor(Player p, GardenData.VisitorState s) {
        double pct = Effects.filterCopperPct(p) + Effects.pet(p, "rose_dragon_spiritual_perfection");
        return Math.round(s.copper * (1 + Math.max(0, pct) / 100.0));
    }

    private List<String> bonuses(GardenData d, GardenTables.Visitor v) {
        List<String> out = new ArrayList<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int rank = rarityRank(v.rarity);
        double rare = 1;
        for (GardenTables.Bonus b : G.T.visitors.bonus.global) {
            if (b.minRarity != null && rank < rarityRank(b.minRarity)) continue;
            if (G.exists(b.item) && r.nextDouble() < b.chance * rare) out.add(b.item);
        }
        List<GardenTables.Bonus> special = G.T.visitors.bonus.special.get(v.id);
        if (special != null) for (GardenTables.Bonus b : special) {
            if (b.minLevel > 0 && G.level(d) < b.minLevel) continue;
            if (b.once && (d.onceGiven.contains(v.id) || "GREENHOUSE_BLUEPRINT".equals(b.item) && d.greenhouse.unlocked)) continue;
            if (!G.exists(b.item)) continue;
            if (r.nextDouble() < b.chance * (b.chance >= 1 ? 1 : rare)) {
                out.add(b.item);
                if (b.once) d.onceGiven.add(v.id);
            }
        }
        return out;
    }

    static int rarityRank(String r) {
        return switch (r == null ? "" : r) { case "RARE" -> 1; case "LEGENDARY" -> 2; case "MYTHIC" -> 3; case "SPECIAL" -> 4; default -> 0; };
    }

    void refuse(Player p, String visitorId) {
        GardenData d = G.data(p);
        GardenData.VisitorState s = find(d, visitorId);
        if (s == null) return;
        d.visitors.remove(s);
        d.log.computeIfAbsent(visitorId, k -> new GardenData.VisitorLog()).refused++;
        GardenTables.Visitor v = G.T.visitor(visitorId);
        Msg.send(p, "&cYou refused " + (v == null ? "the visitor" : color(v.rarity) + v.name) + "&c's offer.");
        syncNpcs(G.key(p), d);
        p.closeInventory();
    }

    /** Unique-visitor and offers-accepted milestone tracks (research 08 1.10). */
    void trackMilestones(Player p, GardenData d, int uniqueBefore) {
        int unique = d.uniqueServed();
        int[] ut = G.T.garden.uniqueVisitorMilestones;
        while (d.uniqueTierPaid < ut.length && unique >= ut[d.uniqueTierPaid]) {
            d.uniqueTierPaid++;
            payTrack(p, d, "Unique Visitors Served", d.uniqueTierPaid, "visitor:unique:" + d.uniqueTierPaid, 2);
        }
        int[] at = G.T.garden.acceptedMilestones;
        while (d.acceptedTierPaid < at.length && d.offersAccepted >= at[d.acceptedTierPaid]) {
            d.acceptedTierPaid++;
            payTrack(p, d, "Offers Accepted", d.acceptedTierPaid, "visitor:accepted:" + d.acceptedTierPaid, 3);
        }
        if (unique > uniqueBefore) {
            for (GardenTables.BarnSkin s : G.T.garden.barnSkins)
                if (("unique:" + unique).equals(s.source)) Msg.success(p, "Barn Skin unlocked: &d" + s.name + "&a! Pick it at the Desk.");
        }
    }

    private void payTrack(Player p, GardenData d, String track, int tier, String key, int sb) {
        int gxp = 5 + 5 * tier;                  // TUNE: research lists the reward kinds, not the amounts
        long fxp = 2000L * tier;
        Msg.box(p, "&a&lVISITOR MILESTONE &e" + track + " " + GardenMath.roman(tier),
                List.of("&a&lREWARDS", "  &8+&2" + gxp + " &7Garden Experience", "  &8+&3" + Text.commasInt(fxp) + " &7Farming XP",
                        "  &8+&b" + sb + " SkyBlock XP"));
        G.farmingXp(p, fxp, "garden:visitor-milestone");
        G.award(p, key, sb, "Visitor milestone");
        G.gardenXp(p, d, gxp, "visitor milestone");
    }

    // =====================================================================================
    // menus
    // =====================================================================================

    void openVisitors(Player p) { buildVisitors(p).open(p); }

    Menu buildVisitors(Player p) {
        GardenData d = G.data(p);
        Menu m = new Menu("Visitors", 6).chrome();
        m.set(4, Icons.of(Material.EMERALD, "&aYour Visitors",
                "&7Visitors waiting: &a" + d.visitors.size() + "&7/&a" + G.visitorMax.get(),
                "&7Next visitor: &e" + (d.visitors.size() >= G.visitorMax.get() ? "&cGarden full" :
                        Text.duration(Math.max(0, (d.nextVisitorAt - System.currentTimeMillis()) / 1000))),
                "",
                "&7Unique visitors served: &a" + d.uniqueServed() + "&7/&a" + G.T.allVisitors().size(),
                "&7Offers accepted: &a" + Text.commasInt(d.offersAccepted)));
        int[] slots = {20, 21, 22, 23, 24, 31};
        d.visitors.sort(Comparator.comparingInt(s -> s.slot));
        for (int i = 0; i < Math.min(slots.length, d.visitors.size()); i++) {
            GardenData.VisitorState s = d.visitors.get(i);
            GardenTables.Visitor v = G.T.visitor(s.id);
            if (v == null) continue;
            m.set(slots[i], Icons.of(eggOf(v.entity), color(v.rarity) + v.name,
                    color(v.rarity) + "&l" + v.rarity, "",
                    "&7Wants: " + G.itemName(s.item) + " &8x" + Text.commasInt(s.amount)
                            + (s.item2 == null ? "" : " &7+ " + G.itemName(s.item2) + " &8x" + Text.commasInt(s.amount2)),
                    "&7Pays: &c" + Text.commasInt(s.copper) + " Copper &7+ &2" + s.gardenXp + " Garden XP",
                    "",
                    G.allowRemoteOffers.get() ? "&eClick to see the offer!" : "&7Talk to them in your Garden."),
                    c -> { if (G.allowRemoteOffers.get()) offer(c.player(), s); });
        }
        m.set(38, Icons.of(Material.BOOK, "&aVisitor's Logbook", "&7Every visitor you have met,", "&7and the ones still to come.", "", "&eClick to view!"),
                c -> logbook(c.player(), 0));
        m.set(42, Icons.of(Material.GOLD_INGOT, "&aVisitor Milestones", "&7Rewards for serving visitors.", "", "&eClick to view!"),
                c -> milestones(c.player()));
        m.back("The Desk", () -> Desk.build(p));
        return m;
    }

    void logbook(Player p, int page) {
        GardenData d = G.data(p);
        List<GardenTables.Visitor> all = new ArrayList<>(G.T.allVisitors());
        Sky.menus().paged("Visitor's Logbook", all, v -> {
            GardenData.VisitorLog log = d.log.get(v.id);
            if (log == null) {
                List<String> lore = new ArrayList<>();
                lore.add(color(v.rarity) + "&l" + v.rarity);
                lore.add("&7Appears from &aGarden " + GardenMath.roman(v.level));
                if (v.home != null) lore.add("&7Only after you have visited &a" + Text.titleCase(v.home));
                return Icons.of(Material.GRAY_DYE, "&c???", lore);
            }
            return Icons.of(eggOf(v.entity), color(v.rarity) + v.name,
                    color(v.rarity) + "&l" + v.rarity, "",
                    "&7Times visited: &a" + log.visits,
                    "&7Offers accepted: &a" + log.accepted,
                    "&7Offers refused: &c" + log.refused);
        }, pc -> { }, pm -> pm.back("Visitors", () -> buildVisitors(p)), page).open(p);
    }

    void milestones(Player p) {
        GardenData d = G.data(p);
        Menu m = new Menu("Visitor Milestones", 6).chrome();
        int unique = d.uniqueServed();
        int[] ut = G.T.garden.uniqueVisitorMilestones;
        int[] at = G.T.garden.acceptedMilestones;
        m.set(4, Icons.of(Material.NAME_TAG, "&aVisitor Milestones", "&7Unique visitors: &a" + unique, "&7Offers accepted: &a" + d.offersAccepted));
        int[] slotsA = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        // unique track: show the tier around the current one (21 slots), accepted track on the last row
        int start = Math.max(0, Math.min(ut.length - slotsA.length, d.uniqueTierPaid - 4));
        for (int i = 0; i < slotsA.length && start + i < ut.length; i++) {
            int t = start + i + 1;
            boolean done = d.uniqueTierPaid >= t;
            m.set(slotsA[i], Icons.of(done ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    (done ? "&a" : "&c") + "Unique Visitors " + GardenMath.roman(t),
                    "&7Serve &a" + ut[t - 1] + " &7different visitors.", "&7Progress: &e" + Math.min(unique, ut[t - 1]) + "&7/&e" + ut[t - 1],
                    "", "&7Rewards: &2+" + (5 + 5 * t) + " Garden XP&7, &3+" + Text.commasInt(2000L * t) + " Farming XP"));
        }
        int startA = Math.max(0, Math.min(at.length - 7, d.acceptedTierPaid - 3));
        for (int i = 0; i < 7 && startA + i < at.length; i++) {
            int t = startA + i + 1;
            boolean done = d.acceptedTierPaid >= t;
            m.set(37 + i, Icons.of(done ? Material.LIME_DYE : Material.GRAY_DYE,
                    (done ? "&a" : "&c") + "Offers Accepted " + GardenMath.roman(t),
                    "&7Accept &a" + at[t - 1] + " &7offers.", "&7Progress: &e" + Math.min(d.offersAccepted, at[t - 1]) + "&7/&e" + at[t - 1],
                    "", "&7Rewards: &2+" + (5 + 5 * t) + " Garden XP&7, &3+" + Text.commasInt(2000L * t) + " Farming XP"));
        }
        m.back("Visitors", () -> buildVisitors(p));
        m.open(p);
    }
}
