package com.skyclone.api.item;

import java.util.ArrayList;
import java.util.List;

/**
 * An item ability as DATA. The behaviour lives in a handler registered under the same
 * {@link #id} with {@code Sky.abilities().register(id, handler)} — usually by the module that
 * defined the item. Core does the trigger detection, mana, cooldown and the action-bar message.
 *
 * <p>JSON: {"id":"instant_transmission","name":"Instant Transmission","trigger":"RIGHT_CLICK",
 *          "mana":50,"cooldown":0,"lore":["&7Teleport &a8 blocks &7ahead of you..."]}
 */
public class AbilityDef {

    public enum Trigger {
        RIGHT_CLICK("RIGHT CLICK"), LEFT_CLICK("LEFT CLICK"), SNEAK_RIGHT_CLICK("SNEAK RIGHT CLICK"),
        SNEAK("SNEAK"), PASSIVE(""), ON_HIT(""), ON_KILL("");
        public final String label;
        Trigger(String label) { this.label = label; }
    }

    public String id;
    public String name;
    public Trigger trigger = Trigger.RIGHT_CLICK;
    /** Mana cost; 0 = free. A value in (0,1) is a fraction of max mana (e.g. 0.1 = 10%). */
    public double mana;
    /** Seconds. */
    public double cooldown;
    /** Base ability damage, if it deals magic damage (shown as "Deals &c%d &7damage"). */
    public double damage;
    /** Intelligence scaling factor for magic damage (genre default 1.0 = +1% per 100 int... see combat). */
    public double scaling = 1.0;
    public List<String> lore = new ArrayList<>();

    public AbilityDef() {}

    public AbilityDef(String id, String name, Trigger trigger, double mana, double cooldown, List<String> lore) {
        this.id = id;
        this.name = name;
        this.trigger = trigger;
        this.mana = mana;
        this.cooldown = cooldown;
        this.lore = new ArrayList<>(lore);
    }
}
