package com.skyclone.mod.dungeons.boss;

import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.ext.BossFloor;
import com.skyclone.mod.dungeons.ext.Bosses;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registers the seven scripted boss floors (Master Mode uses the same fights with {@code master()} true). */
public final class BossFloors {

    private BossFloors() {}

    public static final Map<String, BossFloor> ALL = new LinkedHashMap<>();

    public static void register() {
        Kit.init();
        Hooks.init();
        ALL.put("F1", new GrinkoFight());
        ALL.put("F2", new VeilwrightFight());
        ALL.put("F3", new LecturerFight());
        ALL.put("F4", new BramblemawFight());
        ALL.put("F5", new LuridFight());
        ALL.put("F6", new GorrakFight());
        ALL.put("F7", new WitherLordsFight());
        ALL.forEach(Bosses::register);
        Bosses.enabled = () -> DDials.REAL_BOSSES == null || DDials.REAL_BOSSES.get();
    }
}
