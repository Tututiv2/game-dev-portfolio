package com.secondwatch.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JournalTest {

    private static final List<String> PLAYERS = List.of("tutut", "friend");

    /**
     * Hints contain {@code {other}} placeholders that are resolved to a real
     * groupmate before the player sees them, so a rendered entry is never
     * character-for-character equal to its template. Compare on the fixed parts.
     */
    private static boolean matchesHint(String text, String hint) {
        for (String part : hint.split("\\{other\\}", -1)) {
            if (!part.isEmpty() && !text.contains(part)) return false;
        }
        return true;
    }

    private static Journal journalFor(long seed) {
        RunRules rules = RunRules.roll(seed);
        PreviousGroup group = PreviousGroup.generate(rules, new Rng(seed));
        return Journal.generate(rules, group, PLAYERS, new Rng(seed));
    }

    /**
     * The entry everybody screenshots. The previous group wrote down a username
     * that had not arrived yet, and nothing in the mod ever explains it.
     */
    @Test
    void containsAnEntryNamingOneOfThePlayers() {
        for (long seed = 0; seed < 100; seed++) {
            Journal j = journalFor(seed);
            List<JournalEntry> prescient = j.ofKind(JournalEntry.Kind.PRESCIENT);
            assertEquals(1, prescient.size(), "seed " + seed + ": expected exactly one prescient entry");

            String text = prescient.get(0).text();
            assertTrue(PLAYERS.stream().anyMatch(text::contains),
                    "seed " + seed + ": prescient entry does not name a player: " + text);
        }
    }

    /**
     * The false leads must be invisible. No marker, no phrasing tic, no length
     * signature - if a player can spot a wrong entry without testing it against
     * the world, the device is dead and the journal becomes a quest marker.
     */
    @Test
    void falseEntriesAreIndistinguishableFromTrueOnes() {
        for (long seed = 0; seed < 100; seed++) {
            RunRules rules = RunRules.roll(seed);
            Journal j = journalFor(seed);

            assertEquals(rules.falseLeads(), j.falseCount(),
                    "seed " + seed + ": wrong number of false leads");

            for (JournalEntry e : j.entries()) {
                String rendered = e.rendered();
                assertFalse(rendered.toLowerCase().contains("false"), "truth leaked into the text");
                assertFalse(rendered.toLowerCase().contains("true"), "truth leaked into the text");
                assertFalse(rendered.contains("truthful"), "truth leaked into the text");
            }

            // Every claim about the tell reads the same way whether or not it is
            // this run's tell: same author format, same shape, no tell-tale.
            List<JournalEntry> tellEntries = j.ofKind(JournalEntry.Kind.TELL);
            for (JournalEntry e : tellEntries) {
                assertTrue(e.rendered().startsWith("[night "),
                        "entries must all render identically regardless of truth");
            }
        }
    }

    /** The journal must always describe this run, not a generic one. */
    @Test
    void theTrueTellEntryDescribesThisRunsActualTell() {
        for (long seed = 0; seed < 100; seed++) {
            RunRules rules = RunRules.roll(seed);
            Journal j = journalFor(seed);

            boolean found = j.ofKind(JournalEntry.Kind.TELL).stream()
                    .anyMatch(e -> e.truthful() && matchesHint(e.text(), rules.tell().journalHint()));
            assertTrue(found, "seed " + seed + ": no true entry describing " + rules.tell());

            boolean triggerFound = j.ofKind(JournalEntry.Kind.TRIGGER).stream()
                    .anyMatch(e -> e.truthful() && matchesHint(e.text(), rules.trigger().journalHint()));
            assertTrue(triggerFound, "seed " + seed + ": no true entry describing " + rules.trigger());
        }
    }

    /** A false lead must never accidentally state the truth. */
    @Test
    void falseLeadsNeverDescribeTheRealRules() {
        for (long seed = 0; seed < 200; seed++) {
            RunRules rules = RunRules.roll(seed);
            Journal j = journalFor(seed);

            for (JournalEntry e : j.entries()) {
                if (e.truthful()) continue;
                assertFalse(matchesHint(e.text(), rules.tell().journalHint()),
                        "seed " + seed + ": a 'false' lead states the real tell");
                assertFalse(matchesHint(e.text(), rules.trigger().journalHint()),
                        "seed " + seed + ": a 'false' lead states the real trigger");
            }
        }
    }

    /** The law is always in the book. It is the players' floor. */
    @Test
    void theLawIsAlwaysRecordedAndAlwaysTrue() {
        for (long seed = 0; seed < 100; seed++) {
            Journal j = journalFor(seed);
            List<JournalEntry> law = j.ofKind(JournalEntry.Kind.LAW);
            assertEquals(1, law.size(), "seed " + seed);
            assertTrue(law.get(0).truthful(), "the law is never a false lead");
        }
    }

    /** The book has to end on the sentence that sets up the ending. */
    @Test
    void theSeatEntryIsLast() {
        for (long seed = 0; seed < 100; seed++) {
            Journal j = journalFor(seed);
            List<JournalEntry> all = j.entries();
            assertEquals(JournalEntry.Kind.SEAT, all.get(all.size() - 1).kind(), "seed " + seed);
        }
    }

    /**
     * Dead people do not write things down. A page signed by somebody whose body
     * the players found two nights ago turns the journal from an artefact back
     * into generated content, instantly.
     */
    @Test
    void nobodySignsAnEntryFromAfterTheyWereLost() {
        for (long seed = 0; seed < 300; seed++) {
            RunRules rules = RunRules.roll(seed);
            PreviousGroup group = PreviousGroup.generate(rules, new Rng(seed));
            Journal j = Journal.generate(rules, group, PLAYERS, new Rng(seed));

            for (JournalEntry e : j.entries()) {
                Survivor author = group.members().stream()
                        .filter(s -> s.name().equals(e.author()))
                        .findFirst().orElseThrow();
                if (author.gotOut()) continue;
                assertTrue(author.nightLost() >= e.night(),
                        "seed " + seed + ": " + author.name() + " was lost on night "
                                + author.nightLost() + " but signed a night " + e.night() + " entry");
            }
        }
    }

    /**
     * No template may credit somebody who was never in this run's group. A
     * hardcoded name reads fine in isolation and then ships an entry about a
     * person the players never find any trace of.
     */
    @Test
    void noEntryNamesSomebodyWhoWasNeverHere() {
        for (long seed = 0; seed < 300; seed++) {
            RunRules rules = RunRules.roll(seed);
            PreviousGroup group = PreviousGroup.generate(rules, new Rng(seed));
            Journal j = Journal.generate(rules, group, PLAYERS, new Rng(seed));

            List<String> known = new java.util.ArrayList<>(
                    group.members().stream().map(Survivor::name).toList());
            known.addAll(PLAYERS);

            for (JournalEntry e : j.entries()) {
                assertFalse(e.text().contains("{other}"),
                        "seed " + seed + ": unresolved placeholder in: " + e.text());

                // Every name from the generator's pool that appears in the text
                // must belong to somebody who was actually here.
                for (String candidate : PreviousGroup.namePool()) {
                    if (known.contains(candidate)) continue;
                    assertFalse(e.text().contains(candidate),
                            "seed " + seed + ": entry names " + candidate
                                    + ", who was never in this group: " + e.text());
                }
            }
        }
    }

    /** The diarist always lasts to the final night, so the book can end. */
    @Test
    void theDiaristLastsToTheEnd() {
        for (long seed = 0; seed < 300; seed++) {
            RunRules rules = RunRules.roll(seed);
            PreviousGroup group = PreviousGroup.generate(rules, new Rng(seed));
            assertEquals(rules.nights(), group.diarist().nightLost(), "seed " + seed);
        }
    }

    @Test
    void everyEntryIsAttributedToSomebodyWhoWasActuallyHere() {
        for (long seed = 0; seed < 100; seed++) {
            RunRules rules = RunRules.roll(seed);
            PreviousGroup group = PreviousGroup.generate(rules, new Rng(seed));
            Journal j = Journal.generate(rules, group, PLAYERS, new Rng(seed));

            List<String> names = group.members().stream().map(Survivor::name).toList();
            for (JournalEntry e : j.entries()) {
                assertTrue(names.contains(e.author()),
                        "seed " + seed + ": entry signed by a stranger: " + e.author());
            }
        }
    }
}
