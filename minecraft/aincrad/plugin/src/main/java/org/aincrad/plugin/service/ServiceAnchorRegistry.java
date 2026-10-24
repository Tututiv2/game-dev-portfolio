package org.aincrad.plugin.service;

import org.aincrad.plugin.district.BuildingBuilder;
import org.aincrad.plugin.district.Parcel;
import org.aincrad.plugin.terrain.TerrainProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds each of the spec's ten required town services to a real, already-built parcel.
 *
 * <p>Assignment is deterministic: for every service the registry picks the parcel of a preferred
 * role that lies nearest a preferred anchor point (the north gate for the expedition board, the
 * plaza for starter information, the crafting quarter for repair, and so on), with each parcel used
 * at most once. Because it selects from the same parcel plan the buildings were generated from,
 * every anchor is guaranteed to sit on a building that actually exists and has a working door -
 * rather than at a bare coordinate that happens to look plausible.</p>
 */
public final class ServiceAnchorRegistry {

    private static final String VERSION = "service_anchor:v1";

    private record Preference(ServiceAnchor.ServiceType type, Parcel.ParcelRole role,
                               int anchorX, int anchorZ, String districtHint) {
    }

    private static final List<Preference> PREFERENCES = List.of(
            new Preference(ServiceAnchor.ServiceType.STARTER_INFORMATION,
                    Parcel.ParcelRole.SHOP, 0, 4330, "northgate"),
            new Preference(ServiceAnchor.ServiceType.NORTH_GATE_EXPEDITION_BOARD,
                    Parcel.ParcelRole.SHOP, 0, 4040, "northgate"),
            new Preference(ServiceAnchor.ServiceType.GUILD_PARTY_PREPARATION,
                    Parcel.ParcelRole.INN, -200, 4060, "northgate"),
            new Preference(ServiceAnchor.ServiceType.FLOOR_MAPPING_INTRODUCTION,
                    Parcel.ParcelRole.SHOP, 200, 4060, "northgate"),
            new Preference(ServiceAnchor.ServiceType.FOOD_GENERAL_GOODS,
                    Parcel.ParcelRole.SHOP, 200, 4400, "market"),
            new Preference(ServiceAnchor.ServiceType.SAFE_STORAGE,
                    Parcel.ParcelRole.SHOP, 260, 4520, "market"),
            new Preference(ServiceAnchor.ServiceType.LOST_AND_FOUND_CLAIM,
                    Parcel.ParcelRole.SHOP, 160, 4600, "market"),
            new Preference(ServiceAnchor.ServiceType.REPAIR_CRAFTING,
                    Parcel.ParcelRole.WORKSHOP, -280, 4400, "inncraft"),
            new Preference(ServiceAnchor.ServiceType.INN_RESPAWN_BINDING,
                    Parcel.ParcelRole.INN, -220, 4560, "inncraft"),
            new Preference(ServiceAnchor.ServiceType.ADMIN_VERIFICATION,
                    Parcel.ParcelRole.WORKSHOP, -350, 4300, "inncraft")
    );

    private final Map<ServiceAnchor.ServiceType, ServiceAnchor> anchors =
            new EnumMap<>(ServiceAnchor.ServiceType.class);

    public Map<ServiceAnchor.ServiceType, ServiceAnchor> resolve(List<Parcel> parcels,
                                                                  TerrainProfile terrain,
                                                                  long worldSeed) {
        anchors.clear();
        Map<String, Parcel> used = new LinkedHashMap<>();

        for (Preference pref : PREFERENCES) {
            List<Parcel> candidates = new ArrayList<>();
            for (Parcel p : parcels) {
                if (used.containsKey(p.id())) {
                    continue;
                }
                if (p.role() == pref.role()) {
                    candidates.add(p);
                }
            }
            if (candidates.isEmpty()) {
                // Fall back to any unused parcel rather than leaving a required service unbound.
                for (Parcel p : parcels) {
                    if (!used.containsKey(p.id())) {
                        candidates.add(p);
                    }
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            candidates.sort(Comparator
                    .comparingInt((Parcel p) -> p.districtId().equals(pref.districtHint()) ? 0 : 1)
                    .thenComparingDouble(p -> Math.hypot(p.centreX() - pref.anchorX(),
                            p.centreZ() - pref.anchorZ()))
                    .thenComparing(Parcel::id));
            Parcel chosen = candidates.get(0);
            used.put(chosen.id(), chosen);

            BuildingBuilder.Plan plan = BuildingBuilder.plan(chosen, terrain, worldSeed);
            anchors.put(pref.type(), new ServiceAnchor(
                    "f01/tob/service/" + pref.type().name().toLowerCase(java.util.Locale.ROOT),
                    pref.type(), chosen.id(),
                    plan.insideX(), plan.baseY() + 1, plan.insideZ(), VERSION));
        }
        return anchors;
    }

    public Map<ServiceAnchor.ServiceType, ServiceAnchor> anchors() {
        return anchors;
    }

    public boolean allBound() {
        return anchors.size() == ServiceAnchor.ServiceType.values().length;
    }
}
