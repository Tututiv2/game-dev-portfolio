package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Live self-tests ({@code /sb selftest extras}). Each is a few milliseconds. */
final class ExtrasTests {

    private ExtrasTests() {}

    static void register() {
        Sky.commands().selfTest("extras.items-exist", () -> {
            List<String> bad = new ArrayList<>();
            for (String id : X.catalog.phones.keySet()) if (!Sky.items().exists(id)) bad.add(id);
            if (!Sky.items().exists("TRIO_CONTACTS_ADDON")) bad.add("TRIO_CONTACTS_ADDON");
            for (Catalog.Contact c : X.catalog.contacts.values())
                if (c.req.items != null) for (String id : c.req.items.keySet()) if (!Sky.items().exists(id)) bad.add("contact " + c.id + ": " + id);
            for (Catalog.Project p : X.catalog.projects.values())
                for (Catalog.Component co : p.components) {
                    for (String id : co.items.keySet()) if (!Sky.items().exists(id)) bad.add("city " + co.id + ": " + id);
                    if (co.stat != null && Stat.parse(co.stat) == null) bad.add("city " + co.id + ": stat " + co.stat);
                }
            for (Catalog.Skin s : X.catalog.skins.values()) if (!Sky.items().exists(s.id)) bad.add("skin " + s.id);
            return bad.isEmpty() ? null : "missing: " + String.join(", ", bad.subList(0, Math.min(12, bad.size())));
        });
        Sky.commands().selfTest("extras.songs", () -> {
            int xp = 0, intel = 0;
            for (Catalog.Song s : X.catalog.songs.values()) {
                List<HarpGame.Note> n;
                try { n = HarpGame.parse(s.notes); } catch (IllegalArgumentException e) { return s.id + ": " + e.getMessage(); }
                if (n.size() < 10) return s.id + " has only " + n.size() + " notes";
                for (HarpGame.Note x : n) if (x.lane() < 0 || x.lane() >= HarpGame.LANES || x.pitch() < 0.5f || x.pitch() > 2f) return s.id + ": bad note";
                xp += s.sbxp;
                intel += s.intelligence;
            }
            if (X.catalog.songs.size() != 13) return "expected 13 songs, got " + X.catalog.songs.size();
            if (xp != 236) return "harp SkyBlock XP " + xp + " != 236 (neu sblevels harp_songs)";
            if (intel != 26) return "harp Intelligence " + intel + " != 26 (research 08 6.3)";
            return null;
        });
        Sky.commands().selfTest("extras.harp-engine", () -> {
            Catalog.Song s = X.catalog.songs.get("SONG_HYMN_JOY");
            if (s == null) return "no SONG_HYMN_JOY";
            HarpGame perfect = new HarpGame(s.notes, 0);
            int guard = 0;
            while (!perfect.finished && guard++ < 1000) {
                perfect.step();
                for (int lane = 0; lane < HarpGame.LANES; lane++) if (perfect.noteAt(HarpGame.HIT_ROW, lane) >= 0) perfect.press(lane);
            }
            if (!perfect.perfect()) return "a perfect play was not perfect (" + perfect.mistakes() + " mistakes)";
            HarpGame idle = new HarpGame(s.notes, 5);
            guard = 0;
            while (!idle.finished && guard++ < 1000) idle.step();
            if (!idle.failed) return "doing nothing did not fail the song";
            HarpGame wrong = new HarpGame(s.notes, 0);
            wrong.step();
            if (wrong.press(3) != HarpGame.Press.WRONG || !wrong.failed) return "a wrong note at 0 allowed mistakes did not fail";
            return null;
        });
        Sky.commands().selfTest("extras.contacts", () -> {
            int ok = 0;
            List<String> bad = new ArrayList<>();
            for (Catalog.Contact c : X.catalog.contacts.values()) {
                if (Phone.available(c)) ok++;
                if (c.command != null && !Phone.COMMUNITY.equals(c.command) && !Sky.commands().exists(c.command)) bad.add(c.id + " -> /" + c.command);
                if (c.req.contact != null && !X.catalog.contacts.containsKey(c.req.contact)) bad.add(c.id + " needs unknown contact " + c.req.contact);
                // a phone line that names another NPC must name one that is really on this server
                for (String l : c.lines) {
                    java.util.regex.Matcher m = Catalog.NPC_REF.matcher(l);
                    while (m.find()) if (Sky.npcs().def(m.group(1)) == null) bad.add(c.id + " line names unknown npc " + m.group(1));
                }
            }
            if (ok < X.catalog.contacts.size() * 0.7) return "only " + ok + "/" + X.catalog.contacts.size() + " contacts have an NPC";
            return bad.isEmpty() ? null : "bad: " + String.join(", ", bad);
        });
        Sky.commands().selfTest("extras.phone-capacity", () -> {
            Catalog.Phone basic = X.catalog.phones.get("ABIPHONE_BASIC");
            Catalog.Phone clock = X.catalog.phones.get("ABIPHONE_FLIP_PLUS_CLOCKWORK");
            if (basic == null || clock == null) return "phones missing";
            if (Catalog.capacity(basic, 0) != 6) return "basic != 6";
            if (Catalog.capacity(clock, 25) != 13 + 75) return "flip+ with 25 trios != 88";
            if (Catalog.capacity(clock, 99) != 88) return "trios not capped at 25";
            if (!clock.has("speed_dial") || basic.has("snake")) return "phone features wrong";
            return null;
        });
        Sky.commands().selfTest("extras.firesale", () -> {
            List<Catalog.Skin> cat = FireSales.catalogue();
            if (cat.size() < XDials.fireCount.get()) return "only " + cat.size() + " skins can be sold";
            Set<String> seen = new HashSet<>();
            for (Catalog.Skin s : FireSales.sale(FireSales.cycle())) if (!seen.add(s.id)) return "a sale repeats " + s.id;
            for (Catalog.Skin s : cat) {
                if (!"COSMETIC".equals(String.valueOf(Sky.items().def(s.id).category))) return s.id + " is not a cosmetic";
            }
            return null;
        });
        Sky.commands().selfTest("extras.city", () -> {
            if (X.catalog.projects.size() < 3) return "too few projects";
            for (Catalog.Project p : X.catalog.projects.values()) {
                if (p.perk == null || p.perk.type == null) return p.id + " has no community perk";
                if (p.components.isEmpty()) return p.id + " has no components";
                if (p.goal <= 0) return p.id + " goal";
            }
            if (City.active().size() != Math.min(XDials.cityPerCycle.get(), X.catalog.projects.size()) && XDials.cityEnabled.get())
                return "active projects " + City.active().size();
            for (Map.Entry<String, Catalog.Project> e : X.catalog.projects.entrySet())
                if (!e.getKey().equals(e.getValue().id)) return "id mismatch";
            return null;
        });
    }
}
