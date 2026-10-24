package com.skyclone.core.foundation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GsonsTest {

    public enum Skill { FARMING, MINING, COMBAT }

    public static final class Data {
        public Skill main = Skill.FARMING;
        public EnumMap<Skill, Double> xp = new EnumMap<>(Skill.class);
        public Map<Skill, Integer> levels = new HashMap<>();
        public Map<String, Long> collections = new HashMap<>();
        public List<String> unlocked = new ArrayList<>();
        public double multiplier = 1.0;
        public Data() { }
    }

    @Test
    void roundTrip() {
        Data d = new Data();
        d.main = Skill.MINING;
        d.xp.put(Skill.COMBAT, 1234.5);
        d.levels.put(Skill.FARMING, 7);
        d.collections.put("WHEAT", 50_000L);
        d.unlocked.add("WHEAT_1");
        String json = Gsons.PROFILE.toJson(d);
        Data back = Gsons.PROFILE.fromJson(json, Data.class);
        assertEquals(Skill.MINING, back.main);
        assertEquals(1234.5, back.xp.get(Skill.COMBAT));
        assertEquals(7, back.levels.get(Skill.FARMING));
        assertEquals(50_000L, back.collections.get("WHEAT"));
        assertEquals(List.of("WHEAT_1"), back.unlocked);
        assertEquals(json, Gsons.PROFILE.toJson(back), "serialisation must be stable or autosave rewrites every time");
    }

    @Test
    void oldSavesSurviveRemovedConstants() {
        String json = "{\"main\":\"FISHING\",\"xp\":{\"COMBAT\":5.0,\"FISHING\":9.0},"
                + "\"levels\":{\"mining\":3,\"RUNECRAFTING\":1},\"multiplier\":Infinity}";
        Data d = Gsons.PROFILE.fromJson(json, Data.class);
        assertNull(d.main);
        assertEquals(1, d.xp.size());
        assertEquals(5.0, d.xp.get(Skill.COMBAT));
        assertEquals(1, d.levels.size());
        assertEquals(3, d.levels.get(Skill.MINING));
        assertTrue(Double.isInfinite(d.multiplier));
    }

    @Test
    void nanSerialises() {
        Data d = new Data();
        d.multiplier = Double.NaN;
        String json = Gsons.PROFILE.toJson(d);
        assertTrue(json.contains("NaN"));
        assertTrue(Double.isNaN(Gsons.PROFILE.fromJson(json, Data.class).multiplier));
    }

    @Test
    void missingFieldsKeepConstructorDefaults() {
        Data d = Gsons.PROFILE.fromJson("{}", Data.class);
        assertEquals(Skill.FARMING, d.main);
        assertEquals(1.0, d.multiplier);
    }
}
