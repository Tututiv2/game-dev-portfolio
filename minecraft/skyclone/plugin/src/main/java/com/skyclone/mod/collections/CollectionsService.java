package com.skyclone.mod.collections;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The collections feature service: counting, tiers, tier-up announcements and rewards.
 *
 * <p>Counts live in the profile's SHARED data (one object for all online co-op members): a combined
 * total per collection (which decides the tier) plus each member's contribution. Per-member rewards
 * (SkyBlock XP, lump-sum skill XP, skill caps) are paid by {@link #sync} idempotently, so a co-op
 * member who was offline when the tier was reached gets them on their next login.
 */
final class CollectionsService implements Features.Collections {

    private static final String BAR = "&e&l▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬";

    private final CollectionsModule mod;

    CollectionsService(CollectionsModule mod) { this.mod = mod; }

    // ---- data access -----------------------------------------------------------------------------

    CollectionsData.Shared shared(Player p) {
        return Sky.profiles().shared(p, CollectionsData.SHARED_KEY, CollectionsData.Shared.class);
    }

    CollectionsData.Member member(Player p) {
        return Sky.profiles().member(p, CollectionsData.MEMBER_KEY, CollectionsData.Member.class);
    }

    long total(Player p, CollectionCatalog.Coll c) {
        if (p == null || c == null || !Sky.profiles().isReady(p)) return 0;
        Long v = shared(p).totals.get(c.id);
        return v == null ? 0 : v;
    }

    int tierOf(Player p, CollectionCatalog.Coll c) {
        return c == null ? 0 : CollectionMath.tierFor(c, total(p, c));
    }

    // ---- Features.Collections ---------------------------------------------------------------------

    @Override public long count(Player p, String collectionId) { return total(p, mod.catalog().resolve(collectionId)); }

    @Override public int tier(Player p, String collectionId) { return tierOf(p, mod.catalog().resolve(collectionId)); }

    @Override public boolean isCollectionItem(String itemId) { return mod.catalog().feed(itemId) != null; }

    @Override public void openMenu(Player p) { mod.menus().openMain(p); }

    @Override public void add(Player p, String collectionId, long amount) {
        CollectionCatalog.Coll c = mod.catalog().resolve(collectionId);
        if (c != null) add(p, c, amount);
    }

    // ---- counting -------------------------------------------------------------------------------

    /**
     * Add {@code amount} units to a collection as {@code p}'s contribution. Fires CollectionGain and,
     * for every tier crossed, CollectionTierUp + the level-up box + rewards for each online member.
     * Returns the new combined total, or -1 when the player's profile is not ready.
     */
    long add(Player p, CollectionCatalog.Coll c, long amount) {
        if (p == null || c == null || amount <= 0 || !Sky.profiles().isReady(p)) return -1;
        CollectionsData.Shared s = shared(p);
        String uuid = p.getUniqueId().toString();
        long old = s.totals.getOrDefault(c.id, 0L);
        long now = CollectionMath.add(old, amount);
        s.totals.put(c.id, now);
        s.contrib.computeIfAbsent(c.id, k -> new java.util.HashMap<>()).merge(uuid, amount, CollectionMath::add);
        s.names.put(uuid, p.getName());

        Bukkit.getPluginManager().callEvent(new SkyEvents.CollectionGain(p, c.id, amount, now));
        if (mod.leaderboard() != null) mod.leaderboard().note(p, c, now);

        if (old == 0 && now > 0 && mod.announce()) {
            Msg.send(p, "&6&lCOLLECTION UNLOCKED &e" + mod.rewards().collName(c));
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        }
        int from = CollectionMath.tierFor(c, old);
        int to = CollectionMath.tierFor(c, now);
        if (to > from) tierUp(p, c, from, to);
        return now;
    }

    /** Admin: set the combined total. Raising it behaves like a gain by {@code p}; lowering is silent. */
    long set(Player p, CollectionCatalog.Coll c, long value) {
        if (!Sky.profiles().isReady(p)) return -1;
        value = Math.max(0, Math.min(CollectionMath.MAX, value));
        long cur = total(p, c);
        if (value > cur) return add(p, c, value - cur);
        remove(p, c, cur - value);
        return total(p, c);
    }

    /** Admin: take units away (from p's own contribution first, then the others'). Never announces. */
    long remove(Player p, CollectionCatalog.Coll c, long amount) {
        if (!Sky.profiles().isReady(p) || amount <= 0) return total(p, c);
        CollectionsData.Shared s = shared(p);
        long cur = s.totals.getOrDefault(c.id, 0L);
        long now = Math.max(0, cur - amount);
        s.totals.put(c.id, now);
        Map<String, Long> con = s.contrib.computeIfAbsent(c.id, k -> new java.util.HashMap<>());
        long left = cur - now;
        String me = p.getUniqueId().toString();
        List<String> order = new ArrayList<>();
        order.add(me);
        for (String k : con.keySet()) if (!k.equals(me)) order.add(k);
        for (String k : order) {
            if (left <= 0) break;
            long have = con.getOrDefault(k, 0L);
            long take = Math.min(have, left);
            if (take > 0) { con.put(k, have - take); left -= take; }
        }
        if (now == 0) { s.totals.remove(c.id); s.contrib.remove(c.id); }
        int tier = CollectionMath.tierFor(c, now);
        s.announced.computeIfPresent(c.id, (k, v) -> Math.min(v, tier));
        if (mod.leaderboard() != null) mod.leaderboard().note(p, c, now);
        Sky.stats().invalidate(p);
        return now;
    }

    private void tierUp(Player gainer, CollectionCatalog.Coll c, int from, int to) {
        CollectionsData.Shared s = shared(gainer);
        int already = s.announced.getOrDefault(c.id, 0);
        s.announced.put(c.id, Math.max(already, to));
        for (int t = from + 1; t <= to; t++) {
            Bukkit.getPluginManager().callEvent(new SkyEvents.CollectionTierUp(gainer, c.id, t));
        }
        for (Player m : onlineMembers(gainer)) {
            if (mod.announce() && to > already) announce(m, c, Math.max(from, already), to);
            sync(m, c);
            Sky.stats().invalidate(m);
        }
    }

    /** Online, ready players on the same profile as {@code p} (always includes p). */
    List<Player> onlineMembers(Player p) {
        List<Player> out = new ArrayList<>();
        out.add(p);
        String pid = Sky.profiles().profileId(p);
        if (pid == null) return out;
        for (UUID u : Sky.profiles().members(pid)) {
            if (u.equals(p.getUniqueId())) continue;
            Player m = Bukkit.getPlayer(u);
            if (m != null && Sky.profiles().isReady(m) && pid.equals(Sky.profiles().profileId(m))) out.add(m);
        }
        return out;
    }

    /** The genre's "COLLECTION LEVEL UP" box. Several tiers at once are folded into one box. */
    void announce(Player p, CollectionCatalog.Coll c, int from, int to) {
        String name = mod.rewards().collName(c);
        List<String> rewards = new ArrayList<>();
        for (int t = from + 1; t <= to; t++) {
            CollectionCatalog.Tier tier = c.tier(t);
            if (tier != null) rewards.addAll(mod.rewards().lines(c, tier, 0));
        }
        rewards = foldSbxp(rewards);
        if (rewards.size() > 14) {
            int more = rewards.size() - 13;
            rewards = new ArrayList<>(rewards.subList(0, 13));
            rewards.add("&8...and " + more + " more (see /collection)");
        }
        Msg.send(p, BAR);
        Msg.send(p, " &6&lCOLLECTION LEVEL UP &e" + name + " &8" + mod.tierText(p, from) + "➜&e" + mod.tierText(p, to));
        if (!rewards.isEmpty()) {
            Msg.send(p, "");
            Msg.send(p, " &a&lREWARDS");
            for (String r : rewards) Msg.send(p, "  " + r);
        }
        Msg.send(p, BAR);
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.0f);
    }

    /** Several "+4 SkyBlock XP" lines (multi-tier jump) become one summed line at the end. */
    private static List<String> foldSbxp(List<String> lines) {
        int sum = 0;
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (l.startsWith("&8+&b") && l.endsWith(" SkyBlock XP")) {
                try { sum += Integer.parseInt(l.substring(5, l.length() - " SkyBlock XP".length())); continue; }
                catch (NumberFormatException ignored) { }
            }
            out.add(l);
        }
        if (sum > 0) out.add("&8+&b" + sum + " SkyBlock XP");
        return out;
    }

    // ---- rewards (idempotent) ------------------------------------------------------------------

    /** Pay every reward this member is owed for every collection (run on ProfileReady). */
    void syncAll(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        for (CollectionCatalog.Coll c : mod.catalog().all()) sync(p, c);
        applySkillCaps(p);
        Sky.stats().invalidate(p);
    }

    /** Pay this member what they are owed for one collection's reached tiers. Safe to repeat. */
    void sync(Player p, CollectionCatalog.Coll c) {
        int tier = tierOf(p, c);
        if (tier <= 0) return;
        Features.SkyLevel sky = Sky.service(Features.SkyLevel.class);
        boolean skills = Sky.has(SkillService.class) && mod.skillXpRewards();
        CollectionsData.Member m = null;
        boolean caps = false;
        for (int t = 1; t <= tier; t++) {
            CollectionCatalog.Tier ti = c.tier(t);
            if (ti == null) continue;
            int sb = (int) Math.round(ti.sbxp * mod.sbxpMultiplier());
            if (sb > 0) sky.award(p, "collection:" + c.id + ":" + t, sb, "Collection: " + mod.rewards().collName(c) + " " + mod.tierText(p, t));
            for (CollectionCatalog.Reward r : ti.rewards) {
                if ("skillcap".equals(r.type)) caps = true;
                if (!skills || !"skillxp".equals(r.type)) continue;
                SkillType st = SkillType.parse(r.skill);
                if (st == null) continue;
                if (m == null) m = member(p);
                String key = "skillxp:" + c.id + ":" + t;
                if (m.claimed.containsKey(key)) continue;
                m.claimed.put(key, System.currentTimeMillis());
                Sky.service(SkillService.class).addXp(p, st, r.amount, "collection:" + c.id);
            }
        }
        if (caps) applySkillCaps(p);
    }

    /** Skill caps from collections ("+1 Foraging Level Cap"): cap = base max + bonus, never lowered. */
    void applySkillCaps(Player p) {
        if (!Sky.has(SkillService.class)) return;
        java.util.EnumMap<SkillType, Integer> bonus = new java.util.EnumMap<>(SkillType.class);
        // core-wave5: SkillType.maxLevel is now the data's absolute max (Foraging 57, was 54), so the base the
        // milestones add to is that max minus every cap reward this catalogue can give (57 - 3 = 54, as before).
        java.util.EnumMap<SkillType, Integer> possible = new java.util.EnumMap<>(SkillType.class);
        for (CollectionsModule.StatReward sr : mod.capRewards()) {
            possible.merge(sr.skill(), (int) sr.amount(), Integer::sum);
            if (tierOf(p, sr.coll()) >= sr.tier()) bonus.merge(sr.skill(), (int) sr.amount(), Integer::sum);
        }
        SkillService ss = Sky.service(SkillService.class);
        bonus.forEach((skill, b) -> {
            int want = Math.max(1, skill.maxLevel - possible.getOrDefault(skill, 0)) + b;
            if (ss.cap(p, skill) < want) ss.setCap(p, skill, want);
        });
    }

    // ---- display helpers ----------------------------------------------------------------------

    /** Member contributions, largest first, as "name -> amount" (names from the shared table). */
    List<Map.Entry<String, Long>> contributions(Player p, CollectionCatalog.Coll c) {
        CollectionsData.Shared s = shared(p);
        Map<String, Long> con = s.contrib.getOrDefault(c.id, Map.of());
        List<Map.Entry<String, Long>> out = new ArrayList<>();
        for (var e : con.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            String name = s.names.getOrDefault(e.getKey(), "?");
            out.add(Map.entry(name, e.getValue()));
        }
        out.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return out;
    }

    /** Number of distinct members who ever contributed (decides whether co-op lines are shown). */
    int contributors(Player p, CollectionCatalog.Coll c) {
        Set<String> s = new LinkedHashSet<>(shared(p).contrib.getOrDefault(c.id, Map.of()).keySet());
        return s.size();
    }

    static String num(long v) { return Text.commasInt(v); }
}
