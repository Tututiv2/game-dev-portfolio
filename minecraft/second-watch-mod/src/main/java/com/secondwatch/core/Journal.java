package com.secondwatch.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The book you find in somebody else's base.
 *
 * <p>This is the mod's tutorial, its lore and its trap in one object, and the
 * trap is the point: it is generated from THIS run's real rules, by people who
 * were guessing, so most of it is correct and {@link RunRules#falseLeads()} of
 * it is confidently wrong. The players have to decide how much to trust a dead
 * stranger, which is a far better mechanic than a difficulty slider because the
 * answer changes as the night goes on.
 *
 * <p>The entry everybody will screenshot is {@link JournalEntry.Kind#PRESCIENT}:
 * the previous group writing down a username that had not arrived yet. Yours.
 * Nothing in the mod ever explains it.
 */
public final class Journal {

    private final List<JournalEntry> entries;

    private Journal(List<JournalEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<JournalEntry> entries() {
        return entries;
    }

    public int count() {
        return entries.size();
    }

    public List<JournalEntry> ofKind(JournalEntry.Kind kind) {
        return entries.stream().filter(e -> e.kind() == kind).toList();
    }

    public long falseCount() {
        return entries.stream().filter(e -> !e.truthful()).count();
    }

    /** Everything the players actually read, in order, with no truth markers. */
    public String rendered() {
        StringBuilder sb = new StringBuilder();
        for (JournalEntry e : entries) {
            sb.append(e.rendered()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * {@code {other}} is replaced with the name of somebody who was actually in
     * this run's group. Hardcoding a name here reads fine in isolation and then
     * ships an entry crediting a person the players never find, which is the
     * same immersion break as a dead diarist - see
     * {@code JournalTest#noEntryNamesSomebodyWhoWasNeverHere}.
     */
    private static final String[] MUNDANE = {
            "found iron. proper vein of it. {other} is insufferable now",
            "day three. nothing to report which i am told is not worth writing down. writing it down anyway",
            "the wheat came up. we have bread. {other} cried a bit, do not tell {other} i wrote that",
            "fixed the roof. again. whoever built this place did not think about rain",
            "{other} won every round of the coal game and we have decided the coal game is retired"
    };

    private static final String[] FIRST_WRONG = {
            "sun did not come up. we waited. we are still waiting. clock says half nine at night and it has said that for six hours",
            "no dawn. everyone is being very calm about it in the way where nobody is calm",
            "it is still dark. i have checked the clock four times like checking it is what fixes it"
    };

    private static final String[] LAW = {
            "it has never once said a thing we did not say first. not one word. i have been keeping a list and there is nothing on it that is not already on the other list",
            "here is the thing and it is the only thing that has kept us alive. IT CANNOT MAKE ANYTHING UP. everything out of its mouth is secondhand. ours",
            "we worked it out. it is not talking. it is playing us back. every sentence is one of ours with the day taken off it",
            "test it with something new. it has nothing. it only owns what we have already given it, which is why we have stopped giving it things"
    };

    private static final String[] SEAT = {
            "found what they built. it works. we watched it work.\n\nit fits one.\n\nwe counted. we counted six times",
            "the way out is real and it is one seat wide. that is the whole entry. i do not want to write the rest of it",
            "it fits one of us. we have not talked about it since we found it and we have talked about nothing else"
    };

    /**
     * Build the journal for a run.
     *
     * @param playerNames the usernames of the people about to play - one of them
     *                    is going to turn up in an entry written before they
     *                    arrived, and no part of the mod ever accounts for it
     */
    public static Journal generate(RunRules rules, PreviousGroup group, List<String> playerNames, Rng seedRng) {
        if (playerNames == null || playerNames.isEmpty()) {
            throw new IllegalArgumentException("the journal needs somebody to be prescient about");
        }
        Rng rng = seedRng.fork("journal");

        Survivor diarist = group.diarist();
        List<Survivor> everyone = group.members();
        List<JournalEntry> out = new ArrayList<>();

        // --- Night 1: completely ordinary. The control sample. ---
        out.add(new JournalEntry(diarist.name(), 1, rng.pick(MUNDANE), true, JournalEntry.Kind.MUNDANE));

        // --- Night 2: the only thing wrong is the sky. ---
        out.add(new JournalEntry(diarist.name(), 2, rng.pick(FIRST_WRONG), true, JournalEntry.Kind.MUNDANE));

        // --- The trigger, correctly observed. ---
        int triggerNight = Math.min(3, rules.nights());
        out.add(new JournalEntry(
                pickName(rng, everyone, diarist, triggerNight), triggerNight,
                rules.trigger().journalHint(), true, JournalEntry.Kind.TRIGGER));

        // --- The law. The single most useful page in the book. ---
        out.add(new JournalEntry(
                diarist.name(), Math.min(3, rules.nights()),
                rng.pick(LAW), true, JournalEntry.Kind.LAW));

        // --- The tell, correctly observed. ---
        int tellNight = Math.min(4, rules.nights());
        out.add(new JournalEntry(
                pickName(rng, everyone, diarist, tellNight), tellNight,
                rules.tell().journalHint(), true, JournalEntry.Kind.TELL));

        // --- The false leads. Written with exactly the same confidence. ---
        for (int i = 0; i < rules.falseLeads(); i++) {
            boolean aboutTell = rng.chance(0.5);
            int night = rng.range(2, rules.nights());
            String author = pickName(rng, everyone, diarist, night);
            if (aboutTell) {
                out.add(new JournalEntry(author, night,
                        rules.decoyTell(rng).journalHint(),
                        false, JournalEntry.Kind.TELL));
            } else {
                out.add(new JournalEntry(author, night,
                        rules.decoyTrigger(rng).journalHint(),
                        false, JournalEntry.Kind.TRIGGER));
            }
        }

        // --- The one they will screenshot. ---
        String name = rng.pick(playerNames);
        String[] prescient = {
                "it used a name today. one of ours, in one of our voices, but the NAME is not ours.\n\n"
                        + name + "\n\nwe do not have a " + name + ". we have never had a " + name + ". "
                        + "{other} says it is practising",
                "new one. it said \"" + name + "\" twice, clear as anything, in {other}'s voice.\n\n"
                        + "nobody here is called that. i made everyone say their name out loud into the dark "
                        + "just to be sure. nobody is called that",
                "i am writing this down so that whoever finds it knows we noticed.\n\n"
                        + "it has started saying " + name + ".\n\n"
                        + "if that is you: it knew before you did"
        };
        out.add(new JournalEntry(
                diarist.name(),
                Math.max(2, rules.nights() - 1),
                rng.pick(prescient),
                true,
                JournalEntry.Kind.PRESCIENT));

        // --- The seat. Last thing in the book. ---
        out.add(new JournalEntry(
                diarist.name(), rules.nights(),
                rng.pick(SEAT), true, JournalEntry.Kind.SEAT));

        // Every {other} becomes somebody who was really in this group and really
        // still alive on the night the entry is dated.
        List<JournalEntry> resolved = new ArrayList<>(out.size());
        for (JournalEntry e : out) {
            resolved.add(new JournalEntry(
                    e.author(), e.night(),
                    substitute(e.text(), e.author(), e.night(), everyone, rng),
                    e.truthful(), e.kind()));
        }

        // Chronological, but the SEAT entry always sits last within its night -
        // the book has to end on the sentence that sets up the ending.
        resolved.sort(Comparator
                .comparingInt(JournalEntry::night)
                .thenComparingInt(e -> e.kind() == JournalEntry.Kind.SEAT ? 1 : 0));

        return new Journal(resolved);
    }

    /**
     * Resolve {@code {other}} to one real groupmate, the SAME one throughout a
     * single entry - "{other} cried, do not tell {other} i wrote that" has to
     * name one person twice, not two people once.
     */
    private static String substitute(String text, String author, int night, List<Survivor> everyone, Rng rng) {
        if (!text.contains("{other}")) return text;

        List<Survivor> options = new ArrayList<>();
        for (Survivor s : everyone) {
            if (s.name().equals(author)) continue;
            if (s.gotOut() || s.nightLost() >= night) options.add(s);
        }
        if (options.isEmpty()) {
            for (Survivor s : everyone) {
                if (!s.name().equals(author)) options.add(s);
            }
        }
        String other = options.isEmpty() ? author : rng.pick(options).name();
        return text.replace("{other}", other);
    }

    /**
     * Pick somebody who was still here on this night to sign the entry.
     *
     * <p>Dead people do not write things down. This looks like a nicety and is
     * not: the journal is the one object in the mod that has to read as a real
     * artefact somebody actually kept, and a signature from a person the reader
     * has already found the body of is the single fastest way to turn it back
     * into generated content.
     */
    private static String pickName(Rng rng, List<Survivor> group, Survivor diarist, int night) {
        List<Survivor> present = new ArrayList<>();
        for (Survivor s : group) {
            // Whoever got out was here for all of it; the lost only until they were.
            if (s.gotOut() || s.nightLost() >= night) present.add(s);
        }
        if (present.isEmpty()) return diarist.name();
        return rng.pick(present).name();
    }
}
