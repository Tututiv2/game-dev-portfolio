package com.skyclone.api.npc;

import java.util.ArrayList;
import java.util.List;

/**
 * A clickable NPC. Spawned by core at {@code island}'s anchor {@code anchor} whenever that world
 * is loaded (anchors come from the island generator's Layout — see WorldService). If the anchor
 * does not exist, core logs it and places the NPC at the island spawn so the feature still works.
 *
 * <p>Default body is a MANNEQUIN (a player-shaped entity with a skin). Name line is drawn above
 * it, with an optional title line ("&amp;eBANKER", "&amp;e&amp;lCLICK") above that.
 */
public class NpcDef {
    public String id;
    /** '&amp;' coded display name, e.g. "&amp;fBanker Rhys". Also used in "[NPC] Name: ..." dialogue. */
    public String name;
    /** Line above the name, or null. */
    public String title;
    public String island;
    public String anchor;
    /** Base64 skin texture value (+ optional signature) for MANNEQUIN bodies. */
    public String skin;
    public String skinSignature;
    /** "MANNEQUIN" (default), or any EntityType name ("VILLAGER", "ZOMBIE", "IRON_GOLEM"...). */
    public String entity = "MANNEQUIN";
    /** Item in hand (sky id or Material), cosmetic. */
    public String hand;
    public boolean lookAtPlayers = true;
    /** Extra hologram lines under the title (shop hints, quest markers). */
    public List<String> lines = new ArrayList<>();

    public static NpcDef of(String id, String name, String island, String anchor) {
        NpcDef d = new NpcDef();
        d.id = id; d.name = name; d.island = island; d.anchor = anchor;
        return d;
    }

    public NpcDef title(String t) { title = t; return this; }
    public NpcDef skin(String value, String signature) { skin = value; skinSignature = signature; return this; }
    public NpcDef entity(String e) { entity = e; return this; }
}
