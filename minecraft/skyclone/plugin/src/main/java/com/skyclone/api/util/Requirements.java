package com.skyclone.api.util;

import org.bukkit.entity.Player;

/**
 * One string format for "you need X to do this", resolved by whichever module owns X.
 *
 * <pre>
 *   "skill:combat:15"          skills module     "Requires Combat Skill 15"
 *   "collection:WHEAT:5"       collections       "Requires Wheat Collection V"
 *   "slayer:zombie:3"          slayer            "Requires Zombie Slayer 3"
 *   "catacombs:5"              dungeons          "Requires Catacombs 5"
 *   "hotm:3"                   mining            "Requires Heart of the Mountain 3"
 *   "sblevel:20"               sblevel           "Requires SkyBlock Level 20"
 *   "garden:5"                 garden            "Requires Garden Level 5"
 *   "quest:&lt;id&gt;"               objectives
 *   "visited:&lt;island&gt;"         core (worlds)     "Requires visiting The Park"
 * </pre>
 * Several keys joined with "&amp;" must all hold. An unknown prefix FAILS (closed by default) and
 * logs once, so a typo never silently unlocks something.
 */
public interface Requirements {

    interface Resolver {
        /** {@code parts} is the key split on ':' with the prefix at index 0. */
        boolean check(Player p, String[] parts);
        /** Human text, '&amp;' codes allowed: "&amp;aCombat Skill 15". */
        String describe(String[] parts);
    }

    void register(String prefix, Resolver resolver);

    boolean check(Player p, String key);

    String describe(String key);

    /** Checks; if it fails, tells the player "&amp;cRequires &amp;aCombat Skill 15&amp;c!" and returns false. */
    boolean checkOrTell(Player p, String key);

    /** True when some module registered a resolver for this prefix (case-blind). (Wave 5, crafting request 9.) */
    default boolean known(String prefix) { return false; }
}
