package com.skyclone.core.combat;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.mob.MobDef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** kuudra Request 7: core reads MobDef.extra.size / extra.scale at spawn. */
class MobExtraTest {

    private static MobDef def(String key, Object v) {
        MobDef d = new MobDef();
        d.id = "t";
        if (v instanceof Number n) d.extra.add(key, new JsonPrimitive(n));
        else if (v instanceof String s) d.extra.add(key, new JsonPrimitive(s));
        return d;
    }

    @Test void slimeSize() {
        assertEquals(0, MobServiceImpl.slimeSize(new MobDef()));          // absent: vanilla random size stays
        assertEquals(0, MobServiceImpl.slimeSize(null));
        assertEquals(12, MobServiceImpl.slimeSize(def("size", 12)));       // the Kuudra beast
        assertEquals(1, MobServiceImpl.slimeSize(def("size", 0)));
        assertEquals(1, MobServiceImpl.slimeSize(def("size", -5)));
        assertEquals(127, MobServiceImpl.slimeSize(def("size", 1000)));
        assertEquals(3, MobServiceImpl.slimeSize(def("size", 2.6)));
        assertEquals(0, MobServiceImpl.slimeSize(def("size", "big")));     // not a number
    }

    @Test void entityScale() {
        assertEquals(0, MobServiceImpl.entityScale(new MobDef()));
        assertEquals(2.4, MobServiceImpl.entityScale(def("scale", 2.4)), 1e-9);
        assertEquals(0, MobServiceImpl.entityScale(def("scale", 1.0)));    // 1 = leave the attribute alone
        assertEquals(0, MobServiceImpl.entityScale(def("scale", -1)));
        assertEquals(16, MobServiceImpl.entityScale(def("scale", 99)), 1e-9);
        assertEquals(0.0625, MobServiceImpl.entityScale(def("scale", 0.001)), 1e-9);
    }

    @Test void vanillaSlimeSpeedMatchesSetSize() {
        // Slime.setSize: MOVEMENT_SPEED base = 0.2F + 0.1F * size, computed in float
        assertEquals(0.3, MobServiceImpl.vanillaSlimeSpeed(1), 1e-6);
        assertEquals(1.4, MobServiceImpl.vanillaSlimeSpeed(12), 1e-6);
    }
}
