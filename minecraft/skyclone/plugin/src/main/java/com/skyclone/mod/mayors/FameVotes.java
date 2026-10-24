package com.skyclone.mod.mayors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Election votes by Fame Rank (research/05 §8: a ballot is worth more the higher your Fame Rank).
 * The ranks belong to the bank module ({@code data/bank/fame.json}: fame threshold + votes); we read that
 * file and ask the bank's own {@code fame:<n>} requirement whether the player reaches each threshold, so
 * nothing here touches the bank's member data. Without the bank (no {@code fame} resolver) every ballot is 1.
 */
final class FameVotes {

    record Rank(String name, long fame, int votes) { }

    private static List<Rank> ranks;

    private FameVotes() { }

    static synchronized List<Rank> ranks() {
        if (ranks != null) return ranks;
        List<Rank> out = new ArrayList<>();
        try (InputStream in = Sky.plugin().getResource("data/bank/fame.json")) {
            if (in != null) {
                JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                JsonArray arr = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    out.add(new Rank(o.has("name") ? o.get("name").getAsString() : "?",
                            o.get("fame").getAsLong(), o.has("votes") ? o.get("votes").getAsInt() : 1));
                }
            }
        } catch (Throwable t) {
            Sky.log().warning("[mayors] could not read data/bank/fame.json for vote weights: " + t);
        }
        if (out.isEmpty()) {   // research/05 fame ranks (votes column), in case the bank data is absent
            out.add(new Rank("New Player", 0, 1));
            out.add(new Rank("Settler", 20_000, 1));
            out.add(new Rank("Citizen", 80_000, 2));
            out.add(new Rank("Contributor", 200_000, 3));
            out.add(new Rank("Philanthropist", 400_000, 5));
            out.add(new Rank("Patron", 800_000, 10));
            out.add(new Rank("Famous Player", 1_500_000, 20));
            out.add(new Rank("Attache", 3_000_000, 25));
            out.add(new Rank("Ambassador", 10_000_000, 50));
            out.add(new Rank("Statesperson", 20_000_000, 75));
            out.add(new Rank("Senator", 33_000_000, 100));
        }
        out.sort((a, b) -> Long.compare(a.fame(), b.fame()));
        ranks = List.copyOf(out);
        return ranks;
    }

    /** The player's Fame Rank, or null when the bank is not answering. */
    static Rank rankOf(Player p) {
        List<Rank> rs = ranks();
        Rank best = null;
        try {
            for (Rank r : rs) {
                if (r.fame() <= 0 || Sky.requirements().check(p, "fame:" + r.fame())) best = r;
                else break;   // thresholds ascend: the first miss ends it
            }
        } catch (Throwable t) {
            return null;
        }
        return best;
    }

    /** Votes for one ballot from Fame Rank alone (>= 1). */
    static int votes(Player p) {
        Rank r = rankOf(p);
        return r == null ? 1 : Math.max(1, r.votes());
    }

    /** Pure: votes for a fame total against a table (tests). */
    static int votesFor(long fame, List<Rank> table) {
        int v = 1;
        for (Rank r : table) if (fame >= r.fame()) v = Math.max(1, r.votes());
        return v;
    }
}
