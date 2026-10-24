package com.thestill.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>An item that is not in the registry cannot be built.</b>
 *
 * <p>Same law {@code DialRegistry} enforces for tunables, aimed at items, and for the
 * same reason: the moment a custom item can be constructed somewhere else, the item
 * vault in {@code /config} becomes a second list of what the plugin can make — and a
 * catalogue that is quietly out of date is worse than no catalogue, because you stop
 * checking it.
 *
 * <p>The interesting test here is {@link #identityCannotBeAppliedOutsideThisPackage()}.
 * The others check that entries are well formed; that one checks the law is still
 * <em>structural</em> rather than a thing everybody has agreed to remember.
 *
 * <p>Nothing below touches Bukkit. The catalogue registers lambdas and never calls
 * one, which is deliberate — it is what lets the real {@link StillItems} roster run
 * through the real registry with no server underneath it.
 */
class ItemRegistryTest {

    /**
     * The real catalogue, with a null {@link ItemTags}.
     *
     * <p>Safe because a factory is a lambda that is never invoked here. If a future
     * entry does work at <em>registration</em> time rather than inside its supplier,
     * this line is where it will fail — and it should, because registration runs at
     * plugin enable before a world exists.
     */
    private static ItemRegistry catalogue() {
        return StillItems.catalogue(null);
    }

    @Test
    @DisplayName("the shipped catalogue is not empty")
    void theCatalogueExists() {
        assertTrue(catalogue().size() > 0,
                "StillItems registered nothing — the vault would open on an empty shelf");
    }

    @Test
    @DisplayName("every id is unique")
    void idsAreUnique() {
        // The registry throws on a repeat, so a duplicate could never reach here —
        // which is the point. This asserts the collection agrees, so the failure
        // shows up as "two items are called x" rather than as a missing tile.
        Set<String> seen = new HashSet<>();
        for (ItemRegistry.CustomItem item : catalogue().all()) {
            assertTrue(seen.add(item.id()), "two items share the id '" + item.id() + "'");
        }
        assertEquals(catalogue().size(), seen.size());
    }

    @Test
    @DisplayName("a repeated id is refused, by name")
    void duplicateIdsAreRefused() {
        ItemRegistry registry = new ItemRegistry();
        registry.define("thing").category("c").label("Thing").purpose("p")
                .tag("k", "1").register(() -> null);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> registry.define("thing").category("c").label("Other").purpose("p")
                        .tag("k", "2").register(() -> null));
        assertTrue(thrown.getMessage().contains("thing"),
                "the refusal has to name the id, or nobody knows what to rename");
    }

    @Test
    @DisplayName("two items may not claim one PDC identity")
    void tagsAreUnique() {
        // kind+id is what every matcher in the plugin reads — ItemTags.is, the price
        // table, the fog service's "am I holding a lamp". Two items sharing a tag are
        // literally the same item, and they would work by accident right up until one
        // of them was given different behaviour.
        ItemRegistry registry = new ItemRegistry();
        registry.define("one").category("c").label("One").purpose("p")
                .tag("tool", "pick").register(() -> null);
        assertThrows(IllegalStateException.class,
                () -> registry.define("two").category("c").label("Two").purpose("p")
                        .tag("tool", "pick").register(() -> null));

        Set<String> tags = new HashSet<>();
        for (ItemRegistry.CustomItem item : catalogue().all()) {
            assertTrue(tags.add(item.kind() + "/" + item.tagId()),
                    "'" + item.id() + "' claims a PDC identity another item already has");
        }
    }

    @Test
    @DisplayName("every id can be typed as a command argument")
    void idsAreTypeable() {
        // Brigadier's unquoted charset, same rule region ids and role ids follow. An
        // id nobody can type is an id no admin can ask for.
        for (ItemRegistry.CustomItem item : catalogue().all()) {
            assertTrue(ItemRegistry.isValidId(item.id()),
                    "'" + item.id() + "' is not typeable as a command argument");
        }

        assertFalse(ItemRegistry.isValidId("Fog_Lamp"), "uppercase would let two ids "
                + "differ only in case and mean the same thing");
        assertFalse(ItemRegistry.isValidId("fog:lamp"), "a colon cannot be typed unquoted");
        assertFalse(ItemRegistry.isValidId("fog lamp"), "a space ends the argument");
        assertFalse(ItemRegistry.isValidId(""));
        assertFalse(ItemRegistry.isValidId(null));
        assertTrue(ItemRegistry.isValidId("fog_lamp.mk2"));
    }

    @Test
    @DisplayName("every entry carries the descriptive fields the vault renders")
    void everyEntryIsDescribed() {
        for (ItemRegistry.CustomItem item : catalogue().all()) {
            assertNotNull(item.factory(), item.id() + " has no factory");
            assertFalse(item.category().isBlank(), item.id() + " has no category");
            assertFalse(item.label().isBlank(), item.id() + " has no label");
            // The purpose line is the reason the vault beats a command nobody knows
            // about. A tile with a pretty name and no caption has told you nothing.
            assertFalse(item.purpose().isBlank(), item.id() + " has no purpose line");
            assertFalse(item.kind().isBlank(), item.id() + " has no PDC kind");
            assertFalse(item.tagId().isBlank(), item.id() + " has no PDC id");
        }
    }

    @Test
    @DisplayName("an incomplete entry is refused at registration, not at render")
    void incompleteEntriesAreRefused() {
        assertThrows(IllegalStateException.class, () -> new ItemRegistry()
                .define("x").label("X").purpose("p").tag("k", "1").register(() -> null),
                "no category");
        assertThrows(IllegalStateException.class, () -> new ItemRegistry()
                .define("x").category("c").purpose("p").tag("k", "1").register(() -> null),
                "no label");
        assertThrows(IllegalStateException.class, () -> new ItemRegistry()
                .define("x").category("c").label("X").tag("k", "1").register(() -> null),
                "no purpose");
        assertThrows(IllegalStateException.class, () -> new ItemRegistry()
                .define("x").category("c").label("X").purpose("p").register(() -> null),
                "no tag — identity is never the display name");
        assertThrows(IllegalStateException.class, () -> new ItemRegistry()
                .define("X").category("c").label("X").purpose("p").tag("k", "1")
                .register(() -> null),
                "an untypeable id");
    }

    @Test
    @DisplayName("asking for an item that does not exist fails loudly and names it")
    void unknownIdsThrow() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> catalogue().build("crate_key"));
        assertTrue(thrown.getMessage().contains("crate_key"));
        // The known ids come with the message, because the caller passing a bad id is
        // always a typo at a call site and the answer is usually two characters away.
        assertTrue(thrown.getMessage().contains(StillItems.FOG_LAMP));
    }

    @Test
    @DisplayName("categories group, and every item lands in exactly one")
    void categoriesAccountForEverything() {
        int counted = catalogue().byCategory().values().stream().mapToInt(java.util.List::size).sum();
        assertEquals(catalogue().size(), counted,
                "an item is missing from the grouping the vault draws from");
    }

    /**
     * THE ONE THAT MATTERS.
     *
     * <p>{@link ItemTags#stamp} is package-private, so the compiler refuses any custom
     * item built outside {@code com.thestill.item} — a class in another package can
     * make something that <em>looks</em> like a Fog Lamp, but it cannot give it the
     * identity every matcher in the plugin reads. That is what makes the registry the
     * only source rather than merely the polite one.
     *
     * <p>Widening it back to {@code public} would compile, ship, and quietly undo the
     * whole arrangement — nothing would break, items would just start being built
     * elsewhere again and the vault would drift. So the modifier itself is the
     * assertion.
     */
    @Test
    @DisplayName("identity cannot be applied outside com.thestill.item")
    void identityCannotBeAppliedOutsideThisPackage() throws Exception {
        Method stamp = ItemTags.class.getDeclaredMethod("stamp",
                org.bukkit.inventory.ItemStack.class, String.class, String.class);
        assertFalse(Modifier.isPublic(stamp.getModifiers()),
                "ItemTags.stamp is public again — any class anywhere can now mint a "
                        + "custom item without registering it, and /config's item vault "
                        + "is back to being a hand-maintained second list that drifts. "
                        + "Add the item to StillItems instead.");
        assertFalse(Modifier.isProtected(stamp.getModifiers()),
                "protected is public to a subclass; ItemTags is final, but do not "
                        + "leave the door ajar");
    }
}
