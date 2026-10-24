package com.skyclone.mod.island;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5: per-member co-op permissions - the rules, with no server. */
class CoopPermTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Set<CoopPerm> ALL = EnumSet.allOf(CoopPerm.class);

    @Test
    void ownerCanAlwaysDoEverything() {
        Map<String, CoopPerm.Toggles> perms = new HashMap<>();
        perms.put(OWNER.toString(), CoopPerm.Toggles.of(EnumSet.noneOf(CoopPerm.class)));   // even a stray entry can't lock the owner out
        for (CoopPerm p : CoopPerm.values()) assertTrue(CoopPerm.allowed(OWNER, p, OWNER, perms, EnumSet.noneOf(CoopPerm.class)));
    }

    @Test
    void membersGetDefaultsUntilTheOwnerChangesThem() {
        Map<String, CoopPerm.Toggles> perms = new HashMap<>();
        // the reference: every member may do everything
        for (CoopPerm p : CoopPerm.values()) assertTrue(CoopPerm.allowed(ALEX, p, OWNER, perms, ALL));
        // a server that starts members with build only
        Set<CoopPerm> buildOnly = CoopPerm.parseSet("build");
        assertTrue(CoopPerm.allowed(ALEX, CoopPerm.BUILD, OWNER, perms, buildOnly));
        assertFalse(CoopPerm.allowed(ALEX, CoopPerm.CONTAINERS, OWNER, perms, buildOnly));
        // the owner revokes Alex's containers: Sam is untouched
        CoopPerm.Toggles t = CoopPerm.Toggles.of(ALL);
        t.set(CoopPerm.CONTAINERS, false);
        perms.put(ALEX.toString(), t);
        assertFalse(CoopPerm.allowed(ALEX, CoopPerm.CONTAINERS, OWNER, perms, ALL));
        assertTrue(CoopPerm.allowed(ALEX, CoopPerm.BREAK, OWNER, perms, ALL));
        assertTrue(CoopPerm.allowed(SAM, CoopPerm.CONTAINERS, OWNER, perms, ALL));
        assertEquals(4, t.count());
        assertEquals("build,break,minions,manage", CoopPerm.summary(t));
    }

    @Test
    void unknownOwnerRestrictsNobody() {
        Map<String, CoopPerm.Toggles> perms = new HashMap<>();
        perms.put(ALEX.toString(), CoopPerm.Toggles.of(EnumSet.noneOf(CoopPerm.class)));
        assertTrue(CoopPerm.allowed(ALEX, CoopPerm.BUILD, null, perms, ALL));
        assertFalse(CoopPerm.allowed(null, CoopPerm.BUILD, OWNER, perms, ALL));
    }

    @Test
    void ownerResolution() {
        // the recorded owner wins while still a member
        assertEquals(OWNER, CoopPerm.resolveOwner(OWNER, List.of(ALEX, OWNER), List.of(ALEX, OWNER)));
        // the recorded owner left: the longest-standing remaining member
        assertEquals(ALEX, CoopPerm.resolveOwner(OWNER, List.of(SAM, ALEX), List.of(ALEX, SAM)));
        // nobody recorded: first by join order who is still here
        assertEquals(SAM, CoopPerm.resolveOwner(null, List.of(SAM), List.of(OWNER, SAM)));
        // nothing known at all
        assertNull(CoopPerm.resolveOwner(null, List.of(ALEX), List.of()));
        // no live member list (nobody online): the record stands
        assertEquals(OWNER, CoopPerm.resolveOwner(OWNER, List.of(), List.of()));
    }

    @Test
    void parsingAndPruning() {
        assertEquals(CoopPerm.CONTAINERS, CoopPerm.parse("chests"));
        assertEquals(CoopPerm.BREAK, CoopPerm.parse("Mine"));
        assertNull(CoopPerm.parse("fly"));
        assertEquals(ALL, CoopPerm.parseSet("all"));
        assertTrue(CoopPerm.parseSet("none").isEmpty());
        assertEquals(EnumSet.of(CoopPerm.BUILD, CoopPerm.MINIONS), CoopPerm.parseSet("build, minions, nonsense"));
        Map<String, CoopPerm.Toggles> perms = new HashMap<>();
        perms.put(ALEX.toString(), new CoopPerm.Toggles());
        perms.put(SAM.toString(), new CoopPerm.Toggles());
        perms.put("garbage", new CoopPerm.Toggles());
        assertEquals(2, CoopPerm.prune(perms, List.of(OWNER, ALEX)));
        assertEquals(Set.of(ALEX.toString()), perms.keySet());
        assertEquals(0, CoopPerm.prune(perms, List.of()));   // unknown membership never wipes anything
    }

    @Test
    void togglesRoundTripEveryPermission() {
        for (CoopPerm p : CoopPerm.values()) {
            CoopPerm.Toggles t = CoopPerm.Toggles.of(EnumSet.of(p));
            for (CoopPerm q : CoopPerm.values()) assertEquals(p == q, t.get(q), p + " vs " + q);
        }
    }
}
