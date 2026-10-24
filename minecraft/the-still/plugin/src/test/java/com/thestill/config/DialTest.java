package com.thestill.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialTest {

    private static final Logger LOG = Logger.getLogger("dial-test");

    private static DialRegistry registry(Path dir) {
        return new DialRegistry(LOG, new File(dir.toFile(), "dials.yml"));
    }

    // ---- declaration-time validation ------------------------------------

    @Test
    @DisplayName("a numeric dial must declare its range")
    void numericDialsNeedARange(@TempDir Path dir) {
        DialRegistry dials = registry(dir);
        // Twenty characters of friction that forecloses the family of bugs where
        // an admin types a value nobody thought to bound. Shadow Prison shipped a
        // rankup discount that could exceed 100% and paid players to rank up.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> dials.doubleDial("a.b", 1.0).register());
        assertTrue(thrown.getMessage().contains("range"), thrown.getMessage());

        // Non-numeric dials have nothing to bound, so they need no range.
        assertNotNull(dials.boolDial("a.flag", true).register());
        assertNotNull(dials.stringDial("a.text", "hi").register());
    }

    @Test
    @DisplayName("a default outside its own range is a programming error, caught at startup")
    void defaultMustBeInRange(@TempDir Path dir) {
        DialRegistry dials = registry(dir);
        assertThrows(IllegalStateException.class,
                () -> dials.doubleDial("a.b", 500.0).range(0, 100).register());
        assertThrows(IllegalStateException.class,
                () -> dials.intDial("a.c", -5).range(0, 100).register());
    }

    @Test
    @DisplayName("an inverted range and a non-finite default are refused")
    void malformedDeclarationsAreRefused(@TempDir Path dir) {
        DialRegistry dials = registry(dir);
        assertThrows(IllegalStateException.class,
                () -> dials.doubleDial("a.b", 1.0).range(100, 0).register());
        assertThrows(IllegalStateException.class,
                () -> dials.doubleDial("a.c", Double.NaN).range(0, 100).register());
    }

    @Test
    @DisplayName("duplicate keys are refused")
    void keysAreUnique(@TempDir Path dir) {
        DialRegistry dials = registry(dir);
        dials.intDial("a.b", 1).range(0, 10).register();
        assertThrows(IllegalStateException.class,
                () -> dials.intDial("a.b", 2).range(0, 10).register());
    }

    @Test
    @DisplayName("label and category are derived from the key when not given")
    void metadataIsDerived(@TempDir Path dir) {
        Dial<Integer> dial = registry(dir)
                .intDial("store.flush-seconds", 20).range(1, 300).register();
        assertEquals("Store", dial.category());
        assertEquals("Flush seconds", dial.label());
    }

    // ---- value handling --------------------------------------------------

    @Test
    @DisplayName("NaN and Infinity are rejected, not coerced")
    void refusesNonFinite(@TempDir Path dir) {
        Dial<Double> dial = registry(dir)
                .doubleDial("din.decay", 4.0).range(0, 1000).register();
        // `/bal give <player> NaN` permanently poisoned an account on Shadow
        // Prison: Math.max(0, NaN) is NaN and NaN < 0 is false, so the guard
        // passed. Rejection happens here, once, instead of at every call site.
        assertNotNull(dial.applyFromString("NaN"));
        assertNotNull(dial.applyFromString("Infinity"));
        assertNotNull(dial.applyFromString("-Infinity"));
        assertEquals(4.0, dial.get(), "the bad input changed nothing");
    }

    @Test
    @DisplayName("values are clamped into the declared range")
    void clamps(@TempDir Path dir) {
        Dial<Integer> dial = registry(dir)
                .intDial("store.flush-seconds", 20).range(1, 300).register();
        assertNull(dial.applyFromString("99999"));
        assertEquals(300, dial.get(), "clamped to the ceiling rather than refused");
        assertNull(dial.applyFromString("-4"));
        assertEquals(1, dial.get());
        assertNull(dial.applyFromString("42"));
        assertEquals(42, dial.get());
    }

    @Test
    @DisplayName("unparseable text is rejected with a reason and changes nothing")
    void rejectsGarbage(@TempDir Path dir) {
        Dial<Integer> dial = registry(dir).intDial("a.b", 7).range(0, 100).register();
        assertNotNull(dial.applyFromString("banana"));
        assertNotNull(dial.applyFromString(""));
        assertNotNull(dial.applyFromString(null));
        assertEquals(7, dial.get());
    }

    @Test
    @DisplayName("booleans accept the obvious aliases and refuse the rest")
    void booleanAliases(@TempDir Path dir) {
        Dial<Boolean> dial = registry(dir).boolDial("a.flag", false).register();
        for (String yes : new String[]{"true", "TRUE", "yes", "on", "1"}) {
            assertNull(dial.applyFromString(yes), yes);
            assertTrue(dial.get(), yes);
            dial.applyFromString("false");
        }
        assertNotNull(dial.applyFromString("maybe"), "not a boolean");
        assertFalse(dial.get());
    }

    @Test
    @DisplayName("listeners fire on a real change and stay quiet otherwise")
    void listenersFireOnChangeOnly(@TempDir Path dir) {
        Dial<Integer> dial = registry(dir).intDial("a.b", 10).range(0, 100).register();
        AtomicInteger fired = new AtomicInteger();
        dial.onChange(value -> fired.incrementAndGet());

        dial.applyFromString("20");
        assertEquals(1, fired.get());
        dial.applyFromString("20");
        assertEquals(1, fired.get(), "same value is not a change");
        dial.applyFromString("500");
        assertEquals(2, fired.get(), "clamped to 100, which IS a change");
        dial.applyFromString("999");
        assertEquals(2, fired.get(), "still clamps to 100, so nothing changed");
    }

    @Test
    @DisplayName("isDefault tracks whether the value differs from the code default")
    void tracksDefault(@TempDir Path dir) {
        Dial<Integer> dial = registry(dir).intDial("a.b", 10).range(0, 100).register();
        assertTrue(dial.isDefault());
        dial.applyFromString("11");
        assertFalse(dial.isDefault());
        dial.applyFromString("10");
        assertTrue(dial.isDefault(), "back to the default counts as default again");
    }

    // ---- file overrides --------------------------------------------------

    @Test
    @DisplayName("an override applies to a dial registered AFTER the file was read")
    void overridesAreOrderIndependent(@TempDir Path dir) throws Exception {
        File file = new File(dir.toFile(), "dials.yml");
        Files.writeString(file.toPath(), """
                store:
                  flush-seconds: 45
                din:
                  decay: 9.5
                """);

        DialRegistry dials = new DialRegistry(LOG, file);
        dials.load(); // read before a single dial exists - the normal case

        // A service declaring its own dial in its own constructor, long after
        // load(), must still pick up the admin's value. Otherwise "register the
        // dial where the behaviour lives" quietly stops working.
        Dial<Integer> flush = dials.intDial("store.flush-seconds", 20).range(1, 300).register();
        Dial<Double> decay = dials.doubleDial("din.decay", 4.0).range(0, 1000).register();

        assertEquals(45, flush.get());
        assertEquals(9.5, decay.get());
        assertFalse(flush.isDefault());
    }

    @Test
    @DisplayName("an out-of-range override is clamped rather than trusted")
    void overridesAreValidated(@TempDir Path dir) throws Exception {
        File file = new File(dir.toFile(), "dials.yml");
        Files.writeString(file.toPath(), """
                store:
                  flush-seconds: 100000
                """);
        DialRegistry dials = new DialRegistry(LOG, file);
        dials.load();
        Dial<Integer> flush = dials.intDial("store.flush-seconds", 20).range(1, 300).register();
        assertEquals(300, flush.get(), "a hand-edited file is still input");
    }

    @Test
    @DisplayName("an override of the wrong type is ignored and the default kept")
    void wrongTypeOverrideIsIgnored(@TempDir Path dir) throws Exception {
        File file = new File(dir.toFile(), "dials.yml");
        Files.writeString(file.toPath(), """
                store:
                  flush-seconds: not-a-number
                """);
        DialRegistry dials = new DialRegistry(LOG, file);
        dials.load();
        Dial<Integer> flush = dials.intDial("store.flush-seconds", 20).range(1, 300).register();
        assertEquals(20, flush.get(), "fell back to the code default");
    }

    @Test
    @DisplayName("save then load round-trips, and a deleted line restores the default")
    void reloadRestoresDefaults(@TempDir Path dir) throws Exception {
        File file = new File(dir.toFile(), "dials.yml");
        DialRegistry dials = new DialRegistry(LOG, file);
        dials.load();
        Dial<Integer> flush = dials.intDial("store.flush-seconds", 20).range(1, 300).register();

        flush.applyFromString("77");
        dials.save();
        assertTrue(file.isFile());
        assertTrue(Files.readString(file.toPath()).contains("77"));

        // Reload from the file as written: the change persists.
        assertEquals(0, dials.reload(), "nothing changed by re-reading the same file");
        assertEquals(77, flush.get());

        // Now delete the override entirely. The file is only an override layer,
        // so removing a line must restore the code default rather than keep the
        // last loaded value.
        Files.writeString(file.toPath(), "# nothing here\n");
        int changed = dials.reload();
        assertEquals(1, changed);
        assertEquals(20, flush.get());
        assertTrue(flush.isDefault());
    }
}
