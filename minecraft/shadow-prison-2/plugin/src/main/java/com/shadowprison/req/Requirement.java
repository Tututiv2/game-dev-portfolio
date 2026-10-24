package com.shadowprison.req;

import com.shadowprison.menu.Ui;
import com.shadowprison.profile.Profile;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A condition that gates something — an item, a mine, an area, a recipe (SkyBlock's "Requires
 * Mining Skill 12"). Every lock in the game is a list of these, so every lock can EXPLAIN itself:
 * the lore and the refusal message are generated from the same objects that decide.
 */
public interface Requirement {

    boolean met(Profile profile);

    /** "Mining Skill 12" — what the player needs, in words. */
    String describe();

    /** "7/12" — optional progress toward it, or null. */
    default String progress(Profile profile) {
        return null;
    }

    /** A value stored in profile state is at least {@code needed}. Skills, collections and ranks
     *  all store their levels as state ("skill.mining.level"), so one class gates on any of them. */
    static Requirement atLeast(String stateKey, long needed, String label) {
        return new Requirement() {
            @Override
            public boolean met(Profile profile) {
                return profile.stateLong(stateKey, 0) >= needed;
            }

            @Override
            public String describe() {
                return label + " " + needed;
            }

            @Override
            public String progress(Profile profile) {
                return Math.min(profile.stateLong(stateKey, 0), needed) + "/" + needed;
            }
        };
    }

    /** The requirements this profile does not meet (empty = unlocked). */
    static List<Requirement> missing(Profile profile, List<Requirement> all) {
        List<Requirement> out = new ArrayList<>();
        for (Requirement r : all) {
            if (!r.met(profile)) {
                out.add(r);
            }
        }
        return out;
    }

    /** Lore lines: "✔ Mining Skill 12" in green / "✘ Mining Skill 12 (7/12)" in red. */
    static List<Component> lore(Profile profile, List<Requirement> all) {
        List<Component> out = new ArrayList<>();
        for (Requirement r : all) {
            boolean ok = r.met(profile);
            String prog = ok ? null : r.progress(profile);
            out.add(Ui.text((ok ? "✔ " : "✘ ") + r.describe() + (prog == null ? "" : " (" + prog + ")"),
                    ok ? Ui.GOOD : Ui.BAD));
        }
        return out;
    }

    /** The refusal a player sees when a lock stops them. */
    static Component refusal(String what, List<Requirement> missing) {
        Component msg = Ui.text("You need ", Ui.BAD);
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                msg = msg.append(Ui.text(i == missing.size() - 1 ? " and " : ", ", Ui.BAD));
            }
            msg = msg.append(Ui.text(missing.get(i).describe(), Ui.ACCENT));
        }
        return msg.append(Ui.text(" to " + what + ".", Ui.BAD));
    }
}
