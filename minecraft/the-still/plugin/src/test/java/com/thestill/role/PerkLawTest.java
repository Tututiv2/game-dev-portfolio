package com.thestill.role;

import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>No pay-to-win. Forever.</b>
 *
 * <p>This is the design law made structural. It is not a style check — it is the only
 * version of that law that survives somebody editing YAML at 2am, or copying a row out
 * of another server's config because it looked useful.
 *
 * <p>Shadow Prison sold three perks that bought progression: a rank-up discount, a crate
 * rate bonus and extra storage. Two have since been removed from its live config by
 * hand. Nothing stopped them going in, and nothing would have stopped them coming back.
 *
 * <p>In this game it would be worse than an advantage. The Still's economy <b>is</b> its
 * perception channel — noise calls them, heat keeps them — so a purchasable muffle or
 * seal bonus is not a leg up, it is the game itself for sale.
 */
class PerkLawTest {

    @Test
    @DisplayName("no shipped role grants anything outside the cosmetic allow-list")
    void shippedRolesAreCosmetic() {
        RoleConfig config = defaults();
        // Constructing every default runs RoleDefinition's constructor, which is where
        // the law is enforced. If a default ever carried a banned perk this would throw
        // before reaching an assertion.
        for (RoleDefinition role : config.all().values()) {
            for (String perk : role.perks()) {
                assertTrue(Perks.ALLOWED.contains(perk),
                        role.id() + " grants '" + perk + "', which is not in the "
                                + "cosmetic allow-list");
            }
        }
    }

    @Test
    @DisplayName("a role that buys gameplay is refused by name, at construction")
    void payToWinIsUnwritable() {
        for (String banned : Perks.BANNED) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> role(Set.of(banned)),
                    "'" + banned + "' was accepted — the law is not enforced");
            assertTrue(thrown.getMessage().contains(banned),
                    "the refusal should name the perk so the admin knows what to remove");
        }
    }

    @Test
    @DisplayName("an unknown perk is refused too, not silently ignored")
    void unknownPerksAreRefused() {
        // The dangerous failure is the quiet one: a typo'd perk that does nothing looks
        // identical to a working perk nobody has tested. Both are bugs; only one is
        // findable.
        assertThrows(IllegalArgumentException.class, () -> role(Set.of("free-diamonds")));
    }

    @Test
    @DisplayName("the allow-list and the ban-list cannot overlap")
    void theTwoListsAgree() {
        for (String banned : Perks.BANNED) {
            assertFalse(Perks.ALLOWED.contains(banned),
                    "'" + banned + "' is in both lists — one of them is wrong, and the "
                            + "allow-list is the one that decides");
        }
    }

    @Test
    @DisplayName("a role id has to be typeable as a command argument")
    void roleIdsAreTypeable() {
        // Same rule the region ids follow, and the same reason: Brigadier's unquoted
        // charset. A role nobody can type is a role no admin can grant.
        assertThrows(IllegalArgumentException.class, () -> new RoleDefinition(
                "The Still", RoleConfig.DONOR, 1, "x",
                TextColor.color(0xFFFFFF), TextColor.color(0xFFFFFF), false, true,
                List.of(), Set.of()));
    }

    @Test
    @DisplayName("Member is a placeholder that any earned tag replaces")
    void memberIsAPlaceholder() {
        RoleConfig config = defaults();
        RoleDefinition member = config.role("member");
        assertEquals(RoleConfig.DEFAULT, member.category(),
                "Member must be in the default category or it will not be replaced");
        assertTrue(member.hasTag(), "Member still has to render — it is what a new "
                + "player sees, and a live bug on the source server was exactly this "
                + "tag vanishing when its gradient was switched off");
    }

    /**
     * A config holding only the shipped defaults.
     *
     * <p>Pointed at a path that does not exist, so {@code load()} takes the
     * write-the-defaults branch — and deliberately NOT at a temp file it would then
     * create, because a test that writes YAML into the build tree is a test that
     * passes differently on the second run.
     */
    private static RoleConfig defaults() {
        RoleConfig config = new RoleConfig(java.util.logging.Logger.getLogger("test"),
                new java.io.File("build/tmp/roles-defaults-only.yml")) {
            @Override
            public void save() {
                // The defaults are the subject under test; persisting them is not.
            }
        };
        config.load();
        return config;
    }

    private static RoleDefinition role(Set<String> perks) {
        return new RoleDefinition("test", RoleConfig.DONOR, 1, "Test",
                TextColor.color(0xFFFFFF), TextColor.color(0xFFFFFF), false, true,
                List.of(), perks);
    }
}
