package com.skyclone.mod.galatea;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;

/**
 * The Canyon Journal (ref. Helia's chapters on Torrhus Canyon): data-driven tasks in chapters, each
 * chapter paying Foraging XP and coins once all its tasks are done. Task kinds:
 * {@code zone:<zoneId>}, {@code talk:<npcId>}, {@code kill:<mobId>}, {@code fish}, {@code gain:<ITEM_ID>}.
 */
final class Journal {

    static final class Task { String id, text, kind; int need = 1; }

    static final class Chapter { String id, name; double foragingXp, coins; List<Task> tasks = new ArrayList<>(); }

    final List<Chapter> chapters = new ArrayList<>();

    static Journal load(JsonElement root) {
        Journal j = new Journal();
        if (root != null && root.isJsonObject() && ((JsonObject) root).has("chapters"))
            for (JsonElement e : ((JsonObject) root).getAsJsonArray("chapters")) j.chapters.add(new Gson().fromJson(e, Chapter.class));
        return j;
    }

    static List<Chapter> chapters() { return GalateaModule.journal == null ? List.of() : GalateaModule.journal.chapters; }

    /** Adds progress to every unfinished task of {@code kind}; finishing a chapter pays it. */
    static void credit(Player p, String kind, int amount) {
        if (!GalateaModule.ready(p) || amount <= 0) return;
        GalateaData d = GalateaModule.data(p);
        for (Chapter c : chapters()) {
            boolean changed = false;
            for (Task t : c.tasks) {
                if (!kind.equals(t.kind) || d.journalDone.contains(t.id)) continue;
                int now = Math.min(t.need, d.journal.getOrDefault(t.id, 0) + amount);
                d.journal.put(t.id, now);
                if (now >= t.need) {
                    d.journalDone.add(t.id);
                    Msg.send(p, "&6&lJOURNAL &7" + c.name + ": &a" + t.text + " &a&l✔");
                    Msg.sound(p, Sound.ITEM_BOOK_PAGE_TURN, 1.2f);
                    changed = true;
                }
            }
            if (changed) maybePay(p, c);
        }
    }

    static boolean chapterDone(GalateaData d, Chapter c) {
        for (Task t : c.tasks) if (!d.journalDone.contains(t.id)) return false;
        return true;
    }

    static void maybePay(Player p, Chapter c) {
        GalateaData d = GalateaModule.data(p);
        if (!chapterDone(d, c) || !d.chaptersPaid.add(c.id)) return;
        double scale = Math.max(0, GalateaModule.D.journalScale.get());
        double xp = c.foragingXp * scale, coins = c.coins * scale;
        if (xp > 0) Sky.service(SkillService.class).addXp(p, SkillType.FORAGING, xp, "galatea:journal:" + c.id);
        if (coins > 0) Sky.economy().deposit(p, coins, "galatea: canyon journal " + c.id);
        Msg.box(p, "&6&lCHAPTER COMPLETE &7- &e" + c.name, List.of("&3+" + Text.commasInt(xp) + " Foraging XP", "&6+" + Text.commasInt(coins) + " Coins"));
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        Sky.profiles().saveNow(p);
    }

    /** The first unfinished task's text (sidebar), or null when the journal is complete. */
    static String nextTask(Player p) {
        GalateaData d = GalateaModule.data(p);
        for (Chapter c : chapters())
            for (Task t : c.tasks)
                if (!d.journalDone.contains(t.id)) {
                    String prog = t.need > 1 ? " &7(" + d.journal.getOrDefault(t.id, 0) + "/" + t.need + ")" : "";
                    return t.text + prog;
                }
        return null;
    }

    /** Event wiring. */
    static final class Listen implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onZone(SkyEvents.ZoneChange e) {
            if (e.to != null && GalateaModule.CANYON.equals(e.island)) credit(e.player, "zone:" + e.to.id(), 1);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onKill(SkyEvents.MobKill e) {
            if (e.killer != null && e.def != null && e.def.id != null && e.def.id.startsWith("galatea_")) credit(e.killer, "kill:" + e.def.id, 1);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onFish(SkyEvents.FishCaught e) {
            if (GalateaModule.on(e.player, GalateaModule.CANYON)) credit(e.player, "fish", 1);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onGain(SkyEvents.ItemGain e) {
            if (e.itemId != null && e.source != null && e.source.natural) credit(e.player, "gain:" + e.itemId, e.amount);
        }
    }
}
