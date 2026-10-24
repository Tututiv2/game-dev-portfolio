package com.skyclone.mod.hub;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.mod.hub.gen.HubAnchors;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** data/hub/guides.json: every guide is a district, every trigger is a kind the matcher knows. */
class HubGuidesDataTest {

    @Test
    void guidesParseAndNameRealDistricts() throws Exception {
        List<HubGuides.Guide> all;
        try (InputStream in = HubGuidesDataTest.class.getClassLoader().getResourceAsStream("data/hub/guides.json")) {
            assertNotNull(in, "guides.json missing");
            all = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<HubGuides.Guide>>() {}.getType());
        }
        assertTrue(all.size() >= 7, "guides: " + all.size());
        Set<String> kinds = Set.of("talk", "zone", "gather", "kill", "fish", "pad");
        for (HubGuides.Guide g : all) {
            assertNotNull(HubDistrict.byId(g.id), g.id + " is not a district");
            assertEquals(4, g.steps.size(), g.id);
            for (HubGuides.Step s : g.steps) {
                String k = s.trigger.split(":")[0];
                assertTrue(kinds.contains(k), g.id + ": " + s.trigger);
                if (k.equals("talk")) {
                    String a = s.trigger.substring(5);
                    boolean known = HubAnchors.CONTRACT.contains(a) || HubAnchors.REQUESTED.contains(a) || HubAnchors.OWN.contains(a);
                    assertTrue(known, g.id + ": talk anchor " + a + " is not one the hub promises to build");
                }
            }
        }
    }
}
