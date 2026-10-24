package com.skyclone.mod.hub;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The live side of {@link Errands}: talking to an errand-giver offers, reminds or hands in; District Guide
 * events count towards running errands; {@code /errands} lists every errand in the Hub with its progress and
 * points your compass at whoever gave it. Each errand pays once (coins, SkyBlock XP, skill XP, items: TUNE).
 */
final class HubErrands {

    private HubErrands() {}

    static final List<Errands.Errand> ALL = new ArrayList<>();

    static final int RUNNING = 1, DONE = 2;

    static void load() {
        try (InputStream in = Sky.plugin().getResource("data/hub/errands.json")) {
            if (in == null) { Sky.log().warning("[hub] data/hub/errands.json missing"); return; }
            List<Errands.Errand> list = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<List<Errands.Errand>>() {}.getType());
            ALL.clear();
            ALL.addAll(list);
        } catch (Exception e) {
            Sky.log().warning("[hub] could not read errands.json: " + e);
        }
    }

    static Errands.Errand byId(String id) {
        for (Errands.Errand e : ALL) if (e.id.equals(id)) return e;
        return null;
    }

    static Errands.Errand byNpc(String npcId) {
        for (Errands.Errand e : ALL) if (e.npc.equals(npcId)) return e;
        return null;
    }

    static HubNpcs.Spec giver(Errands.Errand e) {
        for (HubNpcs.Spec s : HubNpcs.SPECS) if (s.id.equals(e.npc)) return s;
        return null;
    }

    static String giverName(Errands.Errand e) {
        HubNpcs.Spec s = giver(e);
        return s == null ? e.name : s.name;
    }

    static int state(HubData d, Errands.Errand e) { return d.errands.getOrDefault(e.id, 0); }

    static int have(HubData d, Errands.Errand e, int i) { return d.errandSteps.getOrDefault(e.id + "#" + i, 0); }

    // ---- talking to the giver ---------------------------------------------------------------

    /** The NPC's click when it gives an errand. {@code intro} = its first-meeting lines (may be empty). */
    static void click(Player p, HubNpcs.Spec s, Errands.Errand e, List<String> intro) {
        HubData d = HubModule.data(p);
        List<String> lines = new ArrayList<>(intro);
        Runnable then;
        switch (state(d, e)) {
            case RUNNING -> {
                List<Integer> missing = unmet(p, d, e);
                if (missing.isEmpty()) {
                    lines.addAll(e.thanks);
                    then = () -> handIn(p, e);
                } else {
                    lines.addAll(e.remind);
                    then = () -> showSteps(p, e, missing);
                }
            }
            case DONE -> {
                if (intro.isEmpty()) lines.addAll(s.repeat);
                then = null;
            }
            default -> {
                lines.addAll(e.offer);
                then = () -> accept(p, e);
            }
        }
        List<String> f = new ArrayList<>();
        for (String l : lines) f.add("&f" + l);
        if (f.isEmpty()) { if (then != null) then.run(); return; }
        Sky.npcs().talk(p, s.id, f, then);
    }

    static void accept(Player p, Errands.Errand e) {
        if (!p.isOnline() || !Sky.profiles().isReady(p)) return;
        HubData d = HubModule.data(p);
        if (state(d, e) != 0) return;
        d.errands.put(e.id, RUNNING);
        d.errandFocus = e.id;
        if (e.steps.isEmpty()) { handIn(p, e); return; }
        List<String> body = new ArrayList<>();
        if (e.about != null) body.addAll(Text.wrap("&7" + e.about, 40));
        body.add("");
        for (Errands.Step s : e.steps) body.add("&8- &f" + s.text);
        body.add("");
        body.add("&7Then return to " + giverName(e) + "&7. &8(/errands)");
        Msg.box(p, "&e&lERRAND ACCEPTED &f" + e.name, body);
        Msg.sound(p, Sound.ITEM_BOOK_PAGE_TURN, 1.2f);
    }

    /** Indexes of the steps not met right now (event steps from progress, hand-in steps checked live). */
    static List<Integer> unmet(Player p, HubData d, Errands.Errand e) {
        List<Integer> out = new ArrayList<>();
        Map<String, Integer> inv = null;
        for (int i = 0; i < e.steps.size(); i++) {
            Errands.Step s = e.steps.get(i);
            Errands.Kind k = Errands.kind(s.trigger);
            boolean ok;
            if (k.counted()) ok = have(d, e, i) >= Errands.need(s);
            else if (k == Errands.Kind.BRING) {
                if (inv == null) inv = inventory(p);
                ok = inv.getOrDefault(Errands.bringItem(s), 0) >= Errands.need(s);
            } else ok = handInOk(p, s);
            if (!ok) out.add(i);
        }
        // two bring steps for one item must be covered together
        if (out.isEmpty() && Errands.takePlan(e, inv == null ? inventory(p) : inv) == null)
            for (int i = 0; i < e.steps.size(); i++) if (Errands.kind(e.steps.get(i).trigger) == Errands.Kind.BRING) out.add(i);
        return out;
    }

    /** A pet / accessory / reforge / requirement step, checked now. */
    static boolean handInOk(Player p, Errands.Step s) {
        return switch (Errands.kind(s.trigger)) {
            case PET -> {
                Features.Pets pets = Sky.service(Features.Pets.class);
                String type = pets.activeType(p);
                yield Errands.petOk(s, type, type == null ? 0 : pets.activeLevel(p));
            }
            case ACCESSORY -> Sky.service(Features.Accessories.class).has(p, Errands.arg(s));
            case REFORGE -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                yield !hand.getType().isAir() && Sky.items().idOf(hand) != null && Errands.reforgeOk(s, Sky.items().data(hand).reforge);
            }
            case REQ -> Sky.requirements().check(p, Errands.arg(s));
            default -> false;
        };
    }

    static void showSteps(Player p, Errands.Errand e, List<Integer> missing) {
        HubData d = HubModule.data(p);
        Msg.send(p, "&e" + e.name + " &8» &7still to do:");
        for (int i : missing) Msg.send(p, " &c✖ &f" + e.steps.get(i).text + count(p, d, e, i));
    }

    /** " (3/10)" for a step that counts, "" otherwise. */
    static String count(Player p, HubData d, Errands.Errand e, int i) {
        Errands.Step s = e.steps.get(i);
        int need = Errands.need(s);
        if (need <= 1) return "";
        int n = Errands.kind(s.trigger) == Errands.Kind.BRING
                ? (p == null ? 0 : inventory(p).getOrDefault(Errands.bringItem(s), 0)) : have(d, e, i);
        return " &8(" + Math.min(n, need) + "/" + need + ")";
    }

    /** Re-checks everything, takes the brought items, pays once. */
    static void handIn(Player p, Errands.Errand e) {
        if (!p.isOnline() || !Sky.profiles().isReady(p)) return;
        HubData d = HubModule.data(p);
        if (state(d, e) != RUNNING) return;
        List<Integer> missing = unmet(p, d, e);
        if (!missing.isEmpty()) { showSteps(p, e, missing); return; }
        Map<String, Integer> take = Errands.takePlan(e, inventory(p));
        if (take == null || !take(p, take)) { Msg.error(p, "Something you were bringing is no longer in your inventory."); return; }
        d.errands.put(e.id, DONE);
        if (e.id.equals(d.errandFocus)) d.errandFocus = null;
        pay(p, e);
        Sky.profiles().saveNow(p);
    }

    static void pay(Player p, Errands.Errand e) {
        Errands.Reward r = e.reward;
        List<String> body = new ArrayList<>();
        body.add("&7" + Text.strip(giverName(e)) + " thanks you.");
        body.add("");
        body.add("&a&lREWARDS");
        double coins = Math.max(0, r.coins * HubModule.errandCoinScale.get());
        if (coins > 0) { Sky.economy().deposit(p, coins, "hub: errand " + e.id); body.add("  &6+" + Text.commasInt(coins) + " Coins"); }
        if (Sky.has(SkillService.class))
            for (Map.Entry<String, Double> sk : r.skills.entrySet()) {
                SkillType t;
                try { t = SkillType.valueOf(sk.getKey()); } catch (IllegalArgumentException ex) { continue; }
                Sky.service(SkillService.class).addXp(p, t, sk.getValue(), "hub:errand");
                body.add("  &3+" + Text.commasInt(sk.getValue()) + " " + t.display + " XP");
            }
        for (Map.Entry<String, Integer> it : r.items.entrySet()) {
            if (!Sky.items().exists(it.getKey())) continue;
            Sky.loot().give(p, it.getKey(), it.getValue(), LootSource.REWARD);
            body.add("  &f" + it.getValue() + "x " + Sky.items().def(it.getKey()).name);
        }
        Msg.box(p, "&6&lERRAND COMPLETE &e" + e.name, body);
        // SkyLevel prints its own "+N SkyBlock XP" line
        if (r.sbxp > 0 && Sky.has(Features.SkyLevel.class))
            Sky.service(Features.SkyLevel.class).award(p, "hub_errand:" + e.id, r.sbxp, e.name);
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
    }

    // ---- inventory ----------------------------------------------------------------------------

    /** Item id -> count over the 36 storage slots. Plain vanilla stacks count under their material name. */
    static Map<String, Integer> inventory(Player p) {
        Map<String, Integer> out = new HashMap<>();
        for (ItemStack st : p.getInventory().getStorageContents()) {
            String id = idFor(st);
            if (id != null) out.merge(id, st.getAmount(), Integer::sum);
        }
        return out;
    }

    static String idFor(ItemStack st) {
        if (st == null || st.getType().isAir()) return null;
        String id = Sky.items().idOf(st);
        return id != null ? id : st.getType().name();
    }

    /** Takes exactly these amounts (counted again first); false and nothing taken when short. */
    static boolean take(Player p, Map<String, Integer> want) {
        Map<String, Integer> inv = inventory(p);
        for (Map.Entry<String, Integer> w : want.entrySet()) if (inv.getOrDefault(w.getKey(), 0) < w.getValue()) return false;
        Map<String, Integer> left = new HashMap<>(want);
        ItemStack[] slots = p.getInventory().getStorageContents();
        for (int i = 0; i < slots.length; i++) {
            String id = idFor(slots[i]);
            Integer need = id == null ? null : left.get(id);
            if (need == null || need <= 0) continue;
            int n = Math.min(need, slots[i].getAmount());
            left.put(id, need - n);
            if (n == slots[i].getAmount()) slots[i] = null;
            else slots[i].setAmount(slots[i].getAmount() - n);
        }
        p.getInventory().setStorageContents(slots);
        return true;
    }

    // ---- events -------------------------------------------------------------------------------

    /** Count a District Guide event key towards every running errand of this player. */
    static void progress(Player p, String event, int amount) {
        if (!HubModule.errandsOn.get() || amount <= 0 || !Sky.profiles().isReady(p)) return;
        HubData d = HubModule.data(p);
        for (Errands.Errand e : ALL) {
            if (state(d, e) != RUNNING) continue;
            boolean finishedOne = false;
            for (int i = 0; i < e.steps.size(); i++) {
                Errands.Step s = e.steps.get(i);
                int need = Errands.need(s);
                if (have(d, e, i) >= need || !Errands.matches(s, event)) continue;
                int now = Math.min(need, have(d, e, i) + amount);
                d.errandSteps.put(e.id + "#" + i, now);
                if (now >= need) {
                    finishedOne = true;
                    Msg.send(p, "&e" + e.name + " &8» &a" + s.text + " &a&l✔");
                    if (s.note != null && !s.note.isBlank()) Msg.send(p, "&7" + s.note);
                    Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
                }
            }
            if (finishedOne && countedDone(d, e))
                Msg.send(p, "&7Go back to " + giverName(e) + " &7to finish &e" + e.name + "&7. &8(/errands)");
        }
    }

    static boolean countedDone(HubData d, Errands.Errand e) {
        for (int i = 0; i < e.steps.size(); i++)
            if (Errands.kind(e.steps.get(i).trigger).counted() && have(d, e, i) < Errands.need(e.steps.get(i))) return false;
        return true;
    }

    // ---- sidebar ------------------------------------------------------------------------------

    static List<String> sidebar(Player p) {
        if (!HubModule.errandsOn.get() || !HubModule.errandSidebar.get() || !Sky.profiles().isReady(p)) return List.of();
        if (!HubModule.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) return List.of();
        HubData d = HubModule.data(p);
        Errands.Errand e = d.errandFocus == null ? null : byId(d.errandFocus);
        if (e == null || state(d, e) != RUNNING) return List.of();
        if (!HubGuides.sidebar(p).isEmpty()) return List.of();   // a District Guide step has the floor in its district
        for (int i = 0; i < e.steps.size(); i++) {
            Errands.Step s = e.steps.get(i);
            if (!Errands.kind(s.trigger).counted() || have(d, e, i) >= Errands.need(s)) continue;
            return List.of("&eErrand: &f" + e.name, " &7» &f" + s.text + count(null, d, e, i).replace("&8", "&7"));
        }
        return List.of("&eErrand: &f" + e.name, " &7» &fReturn to " + giverName(e));
    }

    // ---- /errands -----------------------------------------------------------------------------

    static void openMenu(Player p) { buildMenu(p).open(p); }

    /** {@code p} may be null (self-test). */
    static Menu buildMenu(Player p) {
        Menu m = new Menu("Townsfolk Errands", 6).chrome();
        HubData d = p != null && Sky.profiles().isReady(p) ? HubModule.data(p) : null;
        int done = 0;
        if (d != null) for (Errands.Errand e : ALL) if (state(d, e) == DONE) done++;
        m.set(4, Icons.of(Material.WRITABLE_BOOK, "&eTownsfolk Errands", "&7The Hub's people each have one", "&7small favour to ask. Talk to them",
                "&7to take it on; each pays once.", "", "&7Completed: &a" + done + "&7/&a" + ALL.size(), Text.bar(ALL.isEmpty() ? 0 : (double) done / ALL.size(), 20),
                HubModule.errandsOn.get() ? "" : "&cErrands are switched off right now."));
        int i = 0;
        for (Errands.Errand e : ALL) {
            if (i >= HubMenus.GRID.length) break;
            int st = d == null ? 0 : state(d, e);
            List<String> lore = new ArrayList<>();
            lore.add("&7From: " + giverName(e));
            if (e.about != null) { lore.add(""); lore.addAll(Text.wrap("&7" + e.about, 32)); }
            if (!e.steps.isEmpty()) lore.add("");
            List<Integer> missing = st == RUNNING && p != null ? unmet(p, d, e) : List.of();
            for (int k = 0; k < e.steps.size(); k++) {
                boolean ok = st == DONE || st == RUNNING && !missing.contains(k);
                lore.add((ok ? "&a✔ &7" : st == RUNNING ? "&c✖ &f" : "&8- &7") + e.steps.get(k).text + (st == RUNNING && !ok ? count(p, d, e, k) : ""));
            }
            lore.add("");
            lore.add("&7Reward:");
            Errands.Reward r = e.reward;
            double coins = r.coins * HubModule.errandCoinScale.get();
            if (coins > 0) lore.add(" &6" + Text.commasInt(coins) + " Coins");
            for (Map.Entry<String, Double> sk : r.skills.entrySet()) lore.add(" &3" + Text.commasInt(sk.getValue()) + " " + Text.titleCase(sk.getKey().toLowerCase(Locale.ROOT)) + " XP");
            for (Map.Entry<String, Integer> it : r.items.entrySet())
                lore.add(" &f" + it.getValue() + "x " + (Sky.items().exists(it.getKey()) ? Sky.items().def(it.getKey()).name : Text.titleCase(it.getKey())));
            if (r.sbxp > 0) lore.add(" &b" + r.sbxp + " SkyBlock XP");
            lore.add("");
            lore.add(st == DONE ? "&a&lCOMPLETE" : st == RUNNING ? (missing.isEmpty() ? "&aReady to hand in!" : "&eIn progress") : "&7Not started");
            if (st != DONE) lore.add("&eClick to find " + Text.strip(giverName(e)) + "!");
            Material icon = st == DONE ? Material.LIME_DYE : HubMenus.mat(e.icon, Material.PAPER);
            m.set(HubMenus.GRID[i++], Icons.of(icon, (st == DONE ? "&a" : st == RUNNING ? "&e" : "&f") + e.name, lore), c -> find(c.player(), e));
        }
        m.set(48, Icons.back("Hub Map"), c -> HubMenus.openMap(c.player()));
        return m;
    }

    /** Point the compass at the errand-giver and say where they are. */
    static void find(Player p, Errands.Errand e) {
        p.closeInventory();
        HubNpcs.Spec s = giver(e);
        Location at = s == null ? null : Sky.worlds().anchor(HubModule.ISLAND, s.anchor);
        if (at == null) { Msg.error(p, "Nobody knows where they are right now."); return; }
        if (!HubModule.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) { Msg.send(p, "&7" + giverName(e) + " &7lives in the Hub. Head there first: &e/hub"); return; }
        Location me = p.getLocation();
        double dx = at.getX() - me.getX(), dz = at.getZ() - me.getZ();
        p.setCompassTarget(at);
        Msg.send(p, giverName(e) + " &7is &e" + Math.round(Math.hypot(dx, dz)) + " blocks &7to the &e" + HubMenus.compass(dx, dz)
                + "&7. &8(Your compass points there now.)");
        Msg.sound(p, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.2f);
    }
}
