package com.skyclone.mod.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Townsfolk errands (wave 5): the hub's own people each ask one small thing of a newcomer and pay for it once,
 * the way the reference hub's townsfolk do (the Lazy Miner's mining intro, research 03 section 2.1 step 10; the
 * free starter blade from the village's duelist; Romero-style deliveries). Data in {@code data/hub/errands.json}.
 * PURE: no Bukkit, so the unit test checks the shipped data and the hand-in rule.
 *
 * <p>A step's {@code trigger} is one of the District Guide event kinds, counted only while the errand is
 * running ({@code talk:<anchor>}, {@code zone:<id>}, {@code gather:<KIND>:<WHAT>:<n>}, {@code kill:<mob prefix>:<n>},
 * {@code fish:<n>}, {@code pad:<warp>}), or a HAND-IN kind checked when you talk to the errand's NPC:
 * <pre>
 *   bring:&lt;ITEM_ID&gt;:&lt;n&gt;        n of that item in your inventory, taken when every step is met
 *   pet:&lt;TYPE|*&gt;:&lt;level&gt;       your summoned pet (any type with *) at that level or higher
 *   accessory:&lt;ITEM_ID&gt;       that accessory in your Accessory Bag or inventory
 *   reforge:&lt;id|*&gt;            the item in your hand carries that reforge (any with *)
 *   req:&lt;requirement key&gt;      any requirement key ("skill:combat:3", "hub:practice:1")
 * </pre>
 */
final class Errands {

    private Errands() {}

    /** One errand (a row of errands.json). {@code npc} is the hub NPC id (npcs.json) that gives it. */
    static final class Errand {
        String id, npc, name, icon, about;
        List<Step> steps = new ArrayList<>();
        Reward reward = new Reward();
        /** Said when the errand is offered, while it runs, and when it is handed in. */
        List<String> offer = new ArrayList<>(), remind = new ArrayList<>(), thanks = new ArrayList<>();
    }

    static final class Step {
        String text, trigger;
        /** Optional chat line when an event step completes ("Old Wenna takes the letter."). */
        String note;
    }

    /** Paid once. Every number is TUNE: the reference publishes no townsfolk rewards. */
    static final class Reward {
        double coins;
        int sbxp;
        Map<String, Integer> items = new LinkedHashMap<>();
        /** SkillType name -> base XP. */
        Map<String, Double> skills = new LinkedHashMap<>();
    }

    enum Kind {
        TALK, ZONE, GATHER, KILL, FISH, PAD, BRING, PET, ACCESSORY, REFORGE, REQ, BAD;

        /** Counted from events while the errand runs (the others are checked at the NPC). */
        boolean counted() { return ordinal() <= PAD.ordinal(); }
    }

    static Kind kind(String trigger) {
        if (trigger == null) return Kind.BAD;
        String k = trigger.split(":", 2)[0].toUpperCase(Locale.ROOT);
        try { return Kind.valueOf(k); } catch (IllegalArgumentException e) { return Kind.BAD; }
    }

    /** How many a step needs: the trailing number of gather/kill/fish/bring, else 1. */
    static int need(Step s) {
        Kind k = kind(s.trigger);
        if (k == Kind.GATHER || k == Kind.KILL || k == Kind.FISH || k == Kind.BRING) {
            String[] p = s.trigger.split(":");
            try { return Math.max(1, Integer.parseInt(p[p.length - 1].trim())); } catch (NumberFormatException e) { return 1; }
        }
        return 1;
    }

    /** {@code bring:ITEM:n} -> ITEM. */
    static String bringItem(Step s) {
        String[] p = s.trigger.split(":");
        return p.length >= 3 ? p[1] : null;
    }

    /** {@code pet:TYPE:lvl} -> {TYPE, lvl}. */
    static String[] petWant(Step s) {
        String[] p = s.trigger.split(":");
        return new String[]{p.length > 1 ? p[1] : "*", p.length > 2 ? p[2] : "1"};
    }

    /** The argument after the kind ({@code accessory:X} -> X, {@code req:a:b:c} -> a:b:c). */
    static String arg(Step s) {
        int i = s.trigger.indexOf(':');
        return i < 0 ? "" : s.trigger.substring(i + 1);
    }

    /** Does an event key satisfy this (counted) step? Same grammar as the District Guides. */
    static boolean matches(Step s, String event) {
        if (!kind(s.trigger).counted()) return false;
        return HubGuides.matches(s.trigger, event);
    }

    /** Does a summoned pet satisfy {@code pet:TYPE|*:level}? */
    static boolean petOk(Step s, String activeType, int activeLevel) {
        if (activeType == null || activeType.isBlank()) return false;
        String[] w = petWant(s);
        int lvl;
        try { lvl = Integer.parseInt(w[1].trim()); } catch (NumberFormatException e) { lvl = 1; }
        return (w[0].equals("*") || w[0].equalsIgnoreCase(activeType)) && activeLevel >= lvl;
    }

    /** Does the held item's reforge satisfy {@code reforge:id|*}? */
    static boolean reforgeOk(Step s, String heldReforge) {
        if (heldReforge == null || heldReforge.isBlank()) return false;
        String want = arg(s);
        return want.equals("*") || want.equalsIgnoreCase(heldReforge);
    }

    /**
     * The hand-in rule: every BRING step must be fully covered by what is in the inventory ({@code have}: item
     * id -> count), counting two steps for the same item together. All-or-nothing, so nothing is taken until
     * every step of the errand is met. Returns the items to take, or null when something is short.
     */
    static Map<String, Integer> takePlan(Errand e, Map<String, Integer> have) {
        Map<String, Integer> take = new LinkedHashMap<>();
        for (Step s : e.steps) {
            if (kind(s.trigger) != Kind.BRING) continue;
            take.merge(bringItem(s), need(s), Integer::sum);
        }
        for (Map.Entry<String, Integer> t : take.entrySet())
            if (have.getOrDefault(t.getKey(), 0) < t.getValue()) return null;
        return take;
    }

    /**
     * Problems with the data; null when fine. {@code npcIds} = the hub NPC ids that exist (npcs.json), {@code items}
     * = item ids known to exist (null skips the item check), {@code skills} = SkillType names.
     */
    static String validate(List<Errand> all, Set<String> npcIds, Set<String> items, Set<String> skills) {
        List<String> bad = new ArrayList<>();
        if (all.isEmpty()) bad.add("no errands loaded");
        Set<String> seen = new java.util.HashSet<>(), givers = new java.util.HashSet<>();
        for (Errand e : all) {
            if (e.id == null || !seen.add(e.id)) bad.add("duplicate/missing id " + e.id);
            if (e.npc == null || !npcIds.contains(e.npc)) bad.add(e.id + ": no hub NPC " + e.npc);
            else if (!givers.add(e.npc)) bad.add(e.id + ": " + e.npc + " already gives an errand");
            if (e.name == null || e.name.isBlank()) bad.add(e.id + ": no name");
            if (e.offer == null || e.offer.isEmpty()) bad.add(e.id + ": no offer lines");
            if (e.thanks == null || e.thanks.isEmpty()) bad.add(e.id + ": no thanks lines");
            for (Step s : e.steps) {
                Kind k = kind(s.trigger);
                if (k == Kind.BAD) bad.add(e.id + ": bad trigger " + s.trigger);
                if (s.text == null || s.text.isBlank()) bad.add(e.id + ": a step has no text");
                if (k == Kind.GATHER && s.trigger.split(":").length < 4) bad.add(e.id + ": gather needs KIND:WHAT:n");
                if (k == Kind.BRING && (s.trigger.split(":").length < 3 || items != null && !items.contains(bringItem(s))))
                    bad.add(e.id + ": bring item " + s.trigger);
                if (k == Kind.ACCESSORY && items != null && !items.contains(arg(s))) bad.add(e.id + ": accessory " + arg(s));
            }
            Reward r = e.reward == null ? new Reward() : e.reward;
            if (r.coins < 0 || r.sbxp < 0) bad.add(e.id + ": negative reward");
            if (r.coins <= 0 && r.sbxp <= 0 && r.items.isEmpty() && r.skills.isEmpty()) bad.add(e.id + ": no reward");
            if (items != null) for (String it : r.items.keySet()) if (!items.contains(it)) bad.add(e.id + ": reward item " + it);
            if (skills != null) for (String sk : r.skills.keySet()) if (!skills.contains(sk)) bad.add(e.id + ": skill " + sk);
        }
        return bad.isEmpty() ? null : String.join(", ", bad);
    }
}
