package com.skyclone.mod.extras;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure checks of the extras engines and data files (no server). */
class ExtrasPureTest {

    private static final Catalog C = Catalog.load();

    @Test void dataLoads() {
        assertEquals(13, C.songs.size());
        assertEquals(18, C.phones.size());
        assertTrue(C.contacts.size() >= 40, "contacts " + C.contacts.size());
        assertTrue(C.projects.size() >= 3);
        assertTrue(C.skins.size() >= 50, "skins " + C.skins.size());
    }

    @Test void harpNumbersMatchResearch() {
        int xp = 0, intel = 0;
        for (Catalog.Song s : C.songs.values()) { xp += s.sbxp; intel += s.intelligence; }
        assertEquals(236, xp, "neu sblevels harp_songs");
        assertEquals(26, intel, "research 08 6.3: about +26 Intelligence");
    }

    @Test void notesParse() {
        List<HarpGame.Note> n = HarpGame.parse("C4 - F#4 B5");
        assertEquals(3, n.size());
        assertEquals(0, n.get(0).lane());
        assertEquals(2, n.get(1).beat());
        assertEquals(1.0f, n.get(1).pitch(), 1e-6);          // F#4 is the note block's middle
        assertEquals(6, n.get(2).lane());
        assertEquals(2.0f, HarpGame.pitch(90), 1e-6);         // clamped
        assertThrows(IllegalArgumentException.class, () -> HarpGame.parse("H4"));
        for (Catalog.Song s : C.songs.values()) assertTrue(HarpGame.parse(s.notes).size() >= 10, s.id);
    }

    @Test void perfectPlayIsPerfect() {
        for (Catalog.Song s : C.songs.values()) {
            HarpGame g = new HarpGame(s.notes, 0);
            int guard = 0;
            while (!g.finished && guard++ < 5000) {
                g.step();
                for (int lane = 0; lane < HarpGame.LANES; lane++) if (g.noteAt(HarpGame.HIT_ROW, lane) >= 0) g.press(lane);
            }
            assertTrue(g.perfect(), s.id + " mistakes " + g.mistakes());
            assertEquals(100, g.accuracy());
        }
    }

    @Test void missesAndWrongNotesFail() {
        String song = C.songs.get("SONG_HYMN_JOY").notes;
        HarpGame idle = new HarpGame(song, 3);
        while (!idle.finished) idle.step();
        assertTrue(idle.failed);
        assertEquals(4, idle.mistakes());
        HarpGame sloppy = new HarpGame(song, 3);
        int pressed = 0;
        while (!sloppy.finished) {
            sloppy.step();
            for (int lane = 0; lane < HarpGame.LANES; lane++) if (sloppy.noteAt(HarpGame.HIT_ROW, lane) >= 0) {
                if (pressed++ % 10 != 0) sloppy.press(lane);   // miss every 10th note
            }
        }
        assertTrue(sloppy.completed(), "3 misses in ~30 notes survive 3 allowed mistakes");
        assertFalse(sloppy.perfect());
        HarpGame g = new HarpGame("C4", 0);
        g.step();
        assertEquals(HarpGame.Press.WRONG, g.press(2));
        assertTrue(g.failed);
    }

    @Test void snakeMovesEatsAndDies() {
        PhoneGames.Snake s = new PhoneGames.Snake(9, 5, 1);
        int head = s.body.peekFirst();
        s.step();
        assertEquals(head + 1, (int) s.body.peekFirst());
        s.turn(PhoneGames.Snake.LEFT);                       // reversing is ignored
        s.step();
        assertEquals(head + 2, (int) s.body.peekFirst());
        for (int i = 0; i < 20 && !s.dead; i++) s.step();     // runs into the right wall
        assertTrue(s.dead);
        PhoneGames.Snake e = new PhoneGames.Snake(9, 5, 7);
        e.food = e.body.peekFirst() + 1;
        int len = e.body.size();
        e.step();
        assertEquals(1, e.score);
        assertEquals(len + 1, e.body.size());
    }

    @Test void ticTacToeNeverLosesAtZeroBlunder() {
        Random r = new Random(3);
        for (int game = 0; game < 200; game++) {
            PhoneGames.TicTacToe t = new PhoneGames.TicTacToe(game, 0);
            if (game % 2 == 0) t.phoneMove();
            while (t.result() == 0) {
                int c;
                do { c = r.nextInt(9); } while (t.cells[c] != 0);
                t.play(c);
            }
            assertTrue(t.result() != 1, "a random player beat the perfect phone in game " + game);
        }
    }

    @Test void capacityAndFeatures() {
        assertEquals(6, Catalog.capacity(C.phones.get("ABIPHONE_BASIC"), 0));
        assertEquals(12, Catalog.capacity(C.phones.get("ABIPHONE_FLIP_DRAGON"), 0));
        assertEquals(13 + 75, Catalog.capacity(C.phones.get("ABIPHONE_FLIP_PLUS_LUNAR"), 40));
        assertTrue(C.phones.get("ABIPHONE_XII_MEGA").has("dnd"));
        assertFalse(C.phones.get("ABIPHONE_XII_MEGA").has("snake"));
        assertTrue(C.phones.get("ABIPHONE_FLIP_PLUS_FLOWER").has("speed_dial"));
        assertEquals(25, C.phones.get("ABIPHONE_FLIP_NUCLEUS").discs);
    }

    @Test void contactsAreConsistent() {
        Set<String> npcs = new HashSet<>();
        for (Catalog.Contact c : C.contacts.values()) {
            assertFalse(c.npcs.isEmpty(), c.id);
            for (String n : c.npcs) assertTrue(npcs.add(n), "two contacts share npc " + n);
            if (c.req.contact != null) assertNotNull(C.contacts.get(c.req.contact), c.id);
            assertFalse(c.lines.isEmpty(), c.id + " has nothing to say");
        }
        assertNotNull(C.contact("bank_community_shop"), "lookup by npc id");
        assertEquals("elizabeth", C.contact("bank_community_shop").id);
    }

    @Test void phoneLinesFillNpcNamesLive() {
        java.util.Map<String, String> live = java.util.Map.of("shops_phone_dealer", "Phone Dealer Ude");
        assertEquals("See Phone Dealer Ude on the isle.", Catalog.fillLine("See {npc:shops_phone_dealer} on the isle.", live::get));
        // an NPC the registry does not know still reads as words, never as a raw placeholder
        assertEquals("Ask Phone Dealer.", Catalog.fillLine("Ask {npc:shops_phone_dealer}.", id -> null));
        assertEquals("plain line", Catalog.fillLine("plain line", live::get));
        for (Catalog.Contact c : C.contacts.values())
            for (String l : c.lines) assertFalse(Catalog.fillLine(l, live::get).contains("{"), c.id + " leaves a placeholder: " + l);
    }

    /** Playtest wave 5 (#6): Dialer Pim said he kept "every model in the back" while selling only the Basic. */
    @Test void phoneVendorPointsAtTheDealerWhoStocksTheModels() throws Exception {
        Catalog.Contact alda = C.contacts.get("alda");
        assertNotNull(alda);
        for (String l : alda.lines) assertFalse(l.contains("every model in the back"), l);
        assertTrue(alda.lines.stream().anyMatch(l -> l.contains("{npc:shops_phone_dealer}")), "Pim must name the dealer");
        // the shop he points at really exists and stocks every phone except the Basic, which is his own
        Set<String> stock = new HashSet<>();
        try (java.io.InputStream in = Catalog.class.getResourceAsStream("/data/shops/shops.json")) {
            assertNotNull(in, "data/shops/shops.json");
            com.google.gson.JsonArray all = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
            for (com.google.gson.JsonElement e : all) {
                com.google.gson.JsonObject s = e.getAsJsonObject();
                if (!"phone_dealer".equals(s.get("id").getAsString()) || !s.has("entries")) continue;
                for (com.google.gson.JsonElement en : s.getAsJsonArray("entries"))
                    if (en.getAsJsonObject().has("item")) stock.add(en.getAsJsonObject().get("item").getAsString());
            }
        }
        assertFalse(stock.isEmpty(), "shops has no phone_dealer stock");
        for (String id : C.phones.keySet()) {
            if (id.equals("ABIPHONE_BASIC")) continue;
            assertTrue(stock.contains(id), "the dealer does not stock " + id);
        }
    }

    @Test void fireSaleRotation() {
        int n = C.skins.size();
        Set<Integer> seen = new HashSet<>();
        int cycles = (n + 4) / 5;
        for (long c = 0; c < cycles; c++) {
            List<Integer> p = Rotation.picks(c, n, 5);
            assertEquals(Math.min(5, n), new HashSet<>(p).size());
            seen.addAll(p);
        }
        assertEquals(n, seen.size(), "every skin goes on sale before any repeats");
        long t0 = Rotation.EPOCH_MS;
        assertEquals(0, Rotation.cycle(t0, 168));
        assertEquals(1, Rotation.cycle(t0 + 168L * 3_600_000L, 168));
        assertEquals(-1, Rotation.cycle(t0 - 1, 168));
        assertTrue(Rotation.open(t0 + 3_600_000L, 168, 96));
        assertFalse(Rotation.open(t0 + 100L * 3_600_000L, 168, 96));
    }

    @Test void cityRotationWalksAllProjects() {
        int n = C.projects.size();
        Set<Integer> seen = new HashSet<>();
        for (long c = 0; c < n; c++) {
            List<Integer> a = Rotation.activeProjects(c, n, 3);
            assertEquals(Math.min(3, n), new HashSet<>(a).size());
            seen.addAll(a);
        }
        assertEquals(n, seen.size());
    }
}
