package com.skyclone.api.mob;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A mob TYPE. Loaded from {@code data/<module>/mobs.json} (array) or built in code.
 * Nameplate is rendered by core as "&amp;8[&amp;7Lv{level}&amp;8] &amp;c{name} &amp;a{hp}&amp;f/&amp;a{max}&amp;c❤".
 *
 * <p>JSON: {"id":"crypt_ghoul","name":"Crypt Ghoul","level":30,"entity":"ZOMBIE","health":2000,
 *  "damage":350,"combatXp":32,"coins":13,"drops":[...],"family":"crypt_ghoul","slayer":"zombie",
 *  "equipment":{"HEAD":"CHAINMAIL_HELMET","HAND":"IRON_SWORD"},"behaviours":["leap"]}
 */
public class MobDef {
    public String id;
    public String name;
    public int level = 1;
    /** Bukkit EntityType name. */
    public String entity = "ZOMBIE";
    public double health = 100;
    /** Damage per hit to a player (before defense). */
    public double damage = 10;
    /** Damage reduction for the mob, same formula as player defense. Usually 0. */
    public double defense;
    /** Movement speed multiplier (1.0 = vanilla). */
    public double speed = 1.0;
    public double combatXp;
    public double coins;
    public List<DropDef> drops = new ArrayList<>();
    /** Bestiary family id; defaults to id. */
    public String family;
    /** Slayer category this kill counts toward ("zombie", "spider", "wolf", "enderman", "blaze", "vampire") or null. */
    public String slayer;
    /** Equipment slot (HEAD/CHEST/LEGS/FEET/HAND/OFF_HAND) -> sky item id or Material. Cosmetic only. */
    public Map<String, String> equipment = new HashMap<>();
    /** Skull texture for the HEAD slot (base64). */
    public String skull;
    /** Behaviour ids registered with MobService.registerBehaviour. */
    public List<String> behaviours = new ArrayList<>();
    /** "boss", "aggressive", "passive", "no_knockback", "no_ai", "baby", "fire_immune", "flying", "sea_creature". */
    public Set<String> flags = new HashSet<>();
    /**
     * Free-form module data. Core reads (integration 4): {@code size} — Slime/MagmaCube size 1..127, applied
     * at spawn BEFORE core's health gauge and {@code speed}, so a module no longer has to resize and restore;
     * {@code scale} — the SCALE attribute 0.0625..16; {@code nameplate: "none"}; legacy
     * {@code magicResistance} / {@code damageCap}. A behaviour that still calls {@code Slime.setSize} in
     * {@code onSpawn} gets the gauge (and the {@code speed} multiplier) put back right after.
     */
    public JsonObject extra = new JsonObject();
    /** Fraction 0..1 of MAGIC damage this mob ignores. 0 = read extra.magicResistance (legacy). (Gate, 2026-10-02.) */
    public double magicResistance;
    /** Boss damage cap T: a hit above T counts as T + 10% of the excess. 0 = none / read extra.damageCap. (Gate.) */
    public double damageCap;

    public boolean has(String flag) { return flags.contains(flag); }
    public String familyId() { return family != null ? family : id; }
}
