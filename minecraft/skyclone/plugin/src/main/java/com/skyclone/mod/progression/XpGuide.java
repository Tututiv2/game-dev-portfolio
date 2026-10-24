package com.skyclone.mod.progression;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Quickest SkyBlock XP for you right now" - the SkyBlock Leveling menu's guide item, {@code /xpguide}, and the
 * lines under every refused {@code sblevel:} gate (the Bazaar agent first of all).
 *
 * <p>Wave-5 regression (docs/PLAYTEST.md, open #7): 86 SkyBlock XP in the first 25 minutes, 75 of it from the
 * objective chain, so the Level 5 Bazaar was ~2 hours away. The award values are the reference's (skills 5/10/20/30,
 * collections 4, objectives 5, fairy exchanges 10) and stay; what was missing is that each objective asks for a
 * little of everything (10 wheat, 5 cobblestone, one log) and stops just short of Farming I / Wheat I / Mining I,
 * where the early XP actually is - and nothing told the player how close they were. This reads the live numbers
 * (skill XP to next level, collection items to next tier, bestiary kills to next tier, souls to the next exchange,
 * the current objective) and names the nearest ones with what each pays. Display only: it awards nothing.
 *
 * <p>Thresholds come from the owners' own data files (skills/skills.json, collections/collections.json), read as
 * plain resources - never another module's classes or member data.
 */
final class XpGuide {

    /** collection id -> its tiers in order; skill name -> SkyBlock XP for level n at index n-1. */
    private record CollTier(int tier, long amount, int sbxp) {}
    private record Coll(String id, String name, List<CollTier> tiers) {}

    private final List<Coll> collections = new ArrayList<>();
    private final Map<String, int[]> skillSbXp = new HashMap<>();

    XpGuide() {
        loadCollections();
        loadSkills();
    }

    int collectionCount() { return collections.size(); }
    int skillCount() { return skillSbXp.size(); }

    /** SkyBlock XP for reaching level {@code level} of a skill, as the skills module pays it (its data), else the research table. */
    int skillXp(SkillType s, int level) {
        int[] t = skillSbXp.get(s.name());
        if (t == null) return PMath.skillLevelSbXp(level);
        return level >= 1 && level <= t.length ? t[level - 1] : 0;
    }

    /** Every collection tier's SkyBlock XP is positive (else the guide would never name it). */
    String badCollectionTier() {
        for (Coll c : collections) for (CollTier t : c.tiers) if (t.sbxp <= 0 || t.amount <= 0) return c.id + " tier " + t.tier;
        return null;
    }

    // ---- the tips ----------------------------------------------------------------------------------------

    /** Ranked tips for this player (empty when the profile is not ready). */
    List<PMath.XpTip> tips(Player p, int limit) {
        if (!Prog.ready(p)) return List.of();
        List<PMath.XpTip> all = new ArrayList<>();
        try { objective(p, all); } catch (RuntimeException e) { Sky.log().warning("[progression] xp guide objective: " + e); }
        try { fairy(p, all); } catch (RuntimeException e) { Sky.log().warning("[progression] xp guide fairy: " + e); }
        try { skills(p, all); } catch (RuntimeException e) { Sky.log().warning("[progression] xp guide skills: " + e); }
        try { collections(p, all); } catch (RuntimeException e) { Sky.log().warning("[progression] xp guide collections: " + e); }
        try { bestiary(p, all); } catch (RuntimeException e) { Sky.log().warning("[progression] xp guide bestiary: " + e); }
        return PMath.rankXpTips(all, limit, 2);
    }

    /** "&b+5 &7Farming I &8- &f12 Farming XP to go" */
    static String line(PMath.XpTip t) {
        return "&b+" + t.xp() + " &7" + t.label();
    }

    /** The chat form ({@code /xpguide}, the gate refusal). */
    List<String> chat(Player p, int limit) {
        List<String> out = new ArrayList<>();
        List<PMath.XpTip> tips = tips(p, limit);
        if (tips.isEmpty()) return out;
        out.add("&3Quickest SkyBlock XP for you right now:");
        for (PMath.XpTip t : tips) out.add(" &8▪ " + line(t));
        return out;
    }

    private void objective(Player p, List<PMath.XpTip> all) {
        if (Prog.objectives == null || !Prog.objEnabled.get()) return;
        Catalogs.ObjectiveDef d = Prog.objectives.currentDef(p);
        if (d == null) return;
        int need = Math.max(1, d.trigger.amount);
        double rem = 0.5;   // a talk / visit step: no counter, call it half done
        String tail = "";
        if (need > 1) {
            int have = Math.min(need, Prog.obj(p).progress.getOrDefault(d.id, 0));
            rem = PMath.remainingShare(have, 0, need);
            tail = " &8(" + have + "/" + need + ")";
        }
        all.add(new PMath.XpTip("objective", "Objective: &f" + d.text + tail, Math.max(0, Prog.levelsFile.objectiveXp), rem));
    }

    private void fairy(Player p, List<PMath.XpTip> all) {
        if (Prog.fairy == null) return;
        PData.Fairy f = Prog.fairy(p);
        int found = f.found.size();
        int now = PMath.fairyExchangesAvailable(found, f.exchanged);
        if (now > 0) {
            all.add(new PMath.XpTip("fairy", "Exchange " + (now * 5) + " Fairy Souls at " + FairySouls.NPC_NAME + " &8(Hub)", 10 * now, 0));
            return;
        }
        int left = Prog.fairy.total() - found;
        if (left <= 0) return;
        int toGo = 5 - Math.max(0, found - f.exchanged * 5);
        all.add(new PMath.XpTip("fairy", "Find " + toGo + " more Fairy Soul" + (toGo == 1 ? "" : "s") + " &8(click the pink heads), then exchange",
                10, PMath.remainingShare(5 - toGo, 0, 5)));
    }

    private void skills(Player p, List<PMath.XpTip> all) {
        if (!Sky.has(SkillService.class)) return;
        SkillService s = Sky.service(SkillService.class);
        for (SkillType t : SkillType.values()) {
            int lvl = s.level(p, t);
            if (lvl >= s.cap(p, t)) continue;
            int xp = skillXp(t, lvl + 1);
            if (xp <= 0) continue;
            double needed = s.needed(p, t);
            double into = s.progress(p, t);
            if (needed <= 0) continue;
            double toGo = Math.max(0, needed - into);
            all.add(new PMath.XpTip("skill", t.display + " " + Text.roman(lvl + 1) + " &8- &f" + Text.commasInt(Math.ceil(toGo)) + " " + t.display + " XP to go",
                    xp, PMath.remainingShare(into, 0, needed)));
        }
    }

    private void collections(Player p, List<PMath.XpTip> all) {
        if (!Sky.has(Features.Collections.class) || collections.isEmpty()) return;
        Features.Collections col = Sky.service(Features.Collections.class);
        double mult = 1.0;
        try {
            mult = Math.max(0, Sky.dials().number("collections.sbxp-multiplier", 1.0, "Multiplier on collection-tier SkyBlock XP").get());
        } catch (RuntimeException ignored) { }
        for (Coll c : collections) {
            long have = col.count(p, c.id);
            if (have <= 0) continue;   // only collections the player has started: the rest would be a wall of "0/50"
            int tier = col.tier(p, c.id);
            CollTier next = null;
            long floor = 0;
            for (CollTier t : c.tiers) {
                if (t.tier <= tier) floor = Math.max(floor, t.amount);
                else if (next == null || t.tier < next.tier) next = t;
            }
            if (next == null) continue;
            int xp = (int) Math.round(next.sbxp * mult);
            if (xp <= 0) continue;
            long toGo = Math.max(0, next.amount - have);
            all.add(new PMath.XpTip("collection", c.name + " Collection " + Text.roman(next.tier) + " &8- &f" + Text.commasInt(toGo) + " more " + c.name,
                    xp, PMath.remainingShare(have, floor, next.amount)));
        }
    }

    private void bestiary(Player p, List<PMath.XpTip> all) {
        if (Prog.bestiary == null) return;
        PData.Bestiary d = Prog.best(p);
        for (Map.Entry<String, Integer> e : d.kills.entrySet()) {
            Bestiary.Fam f = Prog.bestiary.family(e.getKey());
            if (f == null) continue;
            int kills = e.getValue() == null ? 0 : e.getValue();
            int tier = PMath.bestiaryTier(kills, f.thresholds, f.maxTier);
            long next = PMath.bestiaryNext(tier, f.thresholds, f.maxTier);
            if (next < 0) continue;
            long floor = tier <= 0 ? 0 : f.thresholds.get(tier - 1);
            long toGo = Math.max(0, next - kills);
            all.add(new PMath.XpTip("bestiary", Text.strip(f.name) + " Bestiary " + Text.roman(tier + 1) + " &8- &f" + toGo + " more kill" + (toGo == 1 ? "" : "s"),
                    1, PMath.remainingShare(kills, floor, next)));
        }
    }

    // ---- data ----------------------------------------------------------------------------------------------

    private static JsonObject json(String path) {
        try (InputStream in = Sky.plugin().getResource(path)) {
            if (in == null) return null;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement e = JsonParser.parseReader(r);
                return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
            }
        } catch (Exception e) {
            Sky.log().warning("[progression] xp guide could not read " + path + ": " + e);
            return null;
        }
    }

    private void loadCollections() {
        JsonObject root = json("data/collections/collections.json");
        if (root == null || !root.has("collections") || !root.get("collections").isJsonArray()) return;
        for (JsonElement el : root.getAsJsonArray("collections")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("id") || !o.has("tiers")) continue;
            String id = o.get("id").getAsString();
            String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : Text.titleCase(id);
            List<CollTier> tiers = new ArrayList<>();
            JsonArray ts = o.getAsJsonArray("tiers");
            for (JsonElement te : ts) {
                if (!te.isJsonObject()) continue;
                JsonObject t = te.getAsJsonObject();
                tiers.add(new CollTier(t.has("tier") ? t.get("tier").getAsInt() : tiers.size() + 1,
                        t.has("amount") ? t.get("amount").getAsLong() : 0,
                        t.has("sbxp") ? t.get("sbxp").getAsInt() : 0));
            }
            if (!tiers.isEmpty()) collections.add(new Coll(id, name, tiers));
        }
    }

    private void loadSkills() {
        JsonObject root = json("data/skills/skills.json");
        if (root == null || !root.has("skills") || !root.get("skills").isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("skills").entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject s = e.getValue().getAsJsonObject();
            if (!s.has("levels") || !s.get("levels").isJsonArray()) continue;
            JsonArray lv = s.getAsJsonArray("levels");
            int[] xp = new int[lv.size()];
            for (int i = 0; i < lv.size(); i++) {
                JsonObject l = lv.get(i).getAsJsonObject();
                xp[i] = l.has("sbxp") ? l.get("sbxp").getAsInt() : 0;
            }
            skillSbXp.put(e.getKey().toUpperCase(java.util.Locale.ROOT), xp);
        }
    }
}
