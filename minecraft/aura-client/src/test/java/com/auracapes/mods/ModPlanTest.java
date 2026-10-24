package com.auracapes.mods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The owner's real 26.2 mod set (ids, provides and depends as read from the jars on 2026-10-13). */
class ModPlanTest {

    static ModPlan.Entry e(String id, String name, Set<String> provides, Set<String> depends) {
        Set<String> p = new LinkedHashSet<>(provides);
        p.add(id);
        return new ModPlan.Entry(id + ".jar", id, name, p, depends, id.equals("auracapes") || id.equals("fabric-api"));
    }

    static final List<ModPlan.Entry> ALL = List.of(
            e("fabric-api", "Fabric API", Set.of("fabric-resource-loader-v0", "fabric-lifecycle-events-v1",
                    "fabric-block-getter-api-v2", "fabric-rendering-fluids-v1"), Set.of("minecraft", "fabricloader")),
            e("fabric-language-kotlin", "Fabric Language Kotlin", Set.of(), Set.of("fabricloader")),
            e("hypixel-mod-api", "Hypixel Mod API", Set.of(), Set.of("fabric-api", "minecraft")),
            e("skyhanni", "SkyHanni", Set.of("render-chest"),
                    Set.of("fabric-api", "fabric-language-kotlin", "hypixel-mod-api", "render-chest", "minecraft")),
            e("skyblocker", "Skyblocker", Set.of("render-chest", "hm-api", "dandelion", "yet_another_config_lib_v3"),
                    Set.of("fabric-api", "fabric-language-kotlin", "render-chest", "hm-api", "minecraft")),
            e("sodium", "Sodium", Set.of("indium"), Set.of("fabric-block-getter-api-v2", "fabric-rendering-fluids-v1")),
            e("sodium-extra", "Sodium Extra", Set.of(), Set.of("sodium")),
            e("reeses-sodium-options", "Reese's Sodium Options", Set.of(), Set.of("sodium")),
            e("cloth-config", "Cloth Config", Set.of("cloth-config2"), Set.of()),
            e("moreculling", "More Culling", Set.of(), Set.of("cloth-config")),
            e("auracapes", "AuraCapes", Set.of(), Set.of("fabric-api")));

    static Set<String> keys(String... ids) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            out.add(id + ".jar");
        }
        return out;
    }

    static Set<String> allOn() {
        Set<String> out = new LinkedHashSet<>();
        ALL.forEach(x -> out.add(x.key()));
        return out;
    }

    @Test
    void cannotTurnOffWhatSomethingStillNeeds() {
        ModPlan.Result r = ModPlan.toggle(ALL, allOn(), "sodium.jar", false);
        assertTrue(r.refused());
        assertTrue(r.notes().get(0).contains("Sodium Extra"), r.notes().toString());
        assertTrue(r.notes().get(0).contains("Reese"), r.notes().toString());
    }

    @Test
    void lockedModsStayOn() {
        assertTrue(ModPlan.toggle(ALL, allOn(), "auracapes.jar", false).refused());
        assertTrue(ModPlan.toggle(ALL, allOn(), "fabric-api.jar", false).refused());
    }

    @Test
    void turningOnPullsInDependencies() {
        Set<String> current = keys("fabric-api", "auracapes", "sodium");
        ModPlan.Result r = ModPlan.toggle(ALL, current, "skyhanni.jar", true);
        assertFalse(r.refused());
        assertTrue(r.on().containsAll(keys("skyhanni", "fabric-language-kotlin", "hypixel-mod-api")), r.on().toString());
        assertEquals(2, r.notes().size(), r.notes().toString()); // FLK + Mod API announced
    }

    /** render-chest lives inside BOTH SkyHanni and Skyblocker: Skyblocker off must not break SkyHanni. */
    @Test
    void aSecondProviderSatisfiesTheDependency() {
        ModPlan.Result r = ModPlan.toggle(ALL, allOn(), "skyblocker.jar", false);
        assertFalse(r.refused(), r.notes().toString());
    }

    @Test
    void shadowPresetTurnsOffTheHypixelSetAndNothingElse() {
        Set<String> off = keys("skyhanni", "skyblocker", "hypixel-mod-api");
        ModPlan.Result r = ModPlan.apply(ALL, allOn(), Set.of(), off);
        for (String k : off) {
            assertFalse(r.on().contains(k), k);
        }
        assertTrue(r.on().containsAll(keys("sodium", "sodium-extra", "moreculling", "fabric-language-kotlin")));
        assertTrue(r.notes().isEmpty(), r.notes().toString());
    }

    @Test
    void presetKeepsADependencyOnInsteadOfBreakingTheGame() {
        // Ask for Sodium off while Sodium Extra stays on: Sodium is kept, and the note says why.
        ModPlan.Result r = ModPlan.apply(ALL, allOn(), Set.of(), keys("sodium"));
        assertTrue(r.on().contains("sodium.jar"));
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("Kept Sodium")), r.notes().toString());
    }

    @Test
    void loaderDependenciesAreNotReportedMissing() {
        ModPlan.Entry fapi = ALL.get(0);
        assertTrue(ModPlan.missing(fapi, List.of(fapi), ALL).isEmpty());
    }

    @Test
    void hypixelPresetFromShadowState() {
        Set<String> shadow = allOn();
        shadow.removeAll(keys("skyhanni", "skyblocker", "hypixel-mod-api"));
        ModPlan.Result r = ModPlan.apply(ALL, shadow, keys("skyhanni", "skyblocker", "hypixel-mod-api"), Set.of());
        assertEquals(allOn(), r.on());
    }
}
