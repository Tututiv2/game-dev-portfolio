package com.skyclone.mod.mayors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The Sphinx's questions (reference wiki: "a trivia question with three randomized answer options"; a right answer
 * gives a random piece of treasure, a wrong one enrages it). The questions are about THIS game and written here, not
 * copied. Pure: the live side shows them in a menu.
 */
final class SphinxRiddles {

    /** A question, its right answer and two wrong ones. */
    record Riddle(String question, String answer, String wrong1, String wrong2) {
        /** The three answers in a seeded order. */
        List<String> shuffled(long seed) {
            List<String> a = new ArrayList<>(List.of(answer, wrong1, wrong2));
            Collections.shuffle(a, new Random(seed));
            return a;
        }
    }

    static final List<Riddle> ALL = List.of(
            new Riddle("How many floors does the Catacombs have?", "7", "5", "9"),
            new Riddle("What do you use to deal with Pests in the Garden?", "A Vacuum", "A Hoe", "A Fishing Rod"),
            new Riddle("How many burrows does an Ancestral Spade reveal in a chain?", "4", "3", "6"),
            new Riddle("Which Gift is the rarest?", "Red Gift", "White Gift", "Green Gift"),
            new Riddle("How many Carnival Tickets does the Ringmaster hand out each day?", "25", "10", "50"),
            new Riddle("How many Candy points is a Purple Candy worth?", "5", "1", "10"),
            new Riddle("Which pet makes Mythological creatures rise from burrows?", "Griffin", "Bat", "Wolf"),
            new Riddle("How many hits does an Earthen Construct ignore before the first one lands?", "5", "3", "7"),
            new Riddle("Every how many minutes does the Dark Auction start?", "60", "30", "120"),
            new Riddle("How many digs does the Carnival Shovel have in Fruit Digging?", "15", "10", "20"),
            new Riddle("What do Gift Thieves fear?", "Snowballs", "Fire", "Water"),
            new Riddle("Which item doubles the coins you dig out of Griffin Burrows?", "Crown of Greed", "Midas' Sword", "Golden Fleece"),
            new Riddle("How long does a Zombie Shootout round last?", "100 seconds", "60 seconds", "5 minutes"),
            new Riddle("Which candy is worth more points?", "Purple Candy", "Green Candy", "They are equal"));

    static Riddle pick(long seed) { return ALL.get(Math.floorMod(new Random(seed).nextInt(), ALL.size())); }

    static boolean correct(Riddle r, String chosen) { return r != null && r.answer().equals(chosen); }
}
