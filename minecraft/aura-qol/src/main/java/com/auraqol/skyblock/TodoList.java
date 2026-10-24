package com.auraqol.skyblock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The to-do list: ordered items, each done or not. Pure; the file lives in
 * {@code TodoHud}, the JSON shape lives here so it can be tested.
 *
 * <p>Numbers in commands are 1-based and are always the number shown on the
 * HUD, so "/todo done 3" ticks the third line you can see. That is why
 * {@link #toggle}/{@link #remove} take display numbers and range-check them
 * here rather than trusting the caller.
 *
 * <h2>File format</h2>
 * Saved list ({@code config/auraqol-todo.json}) and importable checklists
 * ({@code config/auraqol-todo/<name>.json}) share one reader, which accepts:
 * <pre>
 * { "items": [ {"text": "Collect minions", "done": false}, "Kill 10 Voidglooms" ] }
 * [ "Collect minions", {"text": "Bazaar flips", "done": true} ]
 * </pre>
 * A plain string is an item that is not done. Anything else in the file is
 * ignored rather than rejected, so a hand-written checklist with a typo loses
 * the bad line, not the whole list.
 */
public final class TodoList {

    /** Longest text kept; anything longer is cut. One HUD line is ~40 characters. */
    public static final int MAX_TEXT = 120;
    /** More than this and the HUD would cover the screen; the list refuses to grow. */
    public static final int MAX_ITEMS = 50;

    public record Item(String text, boolean done) {
    }

    private final List<Item> items = new ArrayList<>();

    public synchronized List<Item> items() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized int doneCount() {
        int n = 0;
        for (Item i : items) {
            if (i.done()) {
                n++;
            }
        }
        return n;
    }

    /** @return false if the text was blank or the list is full */
    public synchronized boolean add(String text) {
        String t = clean(text);
        if (t.isEmpty() || items.size() >= MAX_ITEMS) {
            return false;
        }
        items.add(new Item(t, false));
        return true;
    }

    /** Flip done/not-done. @return the item's new state, or null if {@code n} is out of range */
    public synchronized Boolean toggle(int n) {
        if (n < 1 || n > items.size()) {
            return null;
        }
        Item i = items.get(n - 1);
        Item flipped = new Item(i.text(), !i.done());
        items.set(n - 1, flipped);
        return flipped.done();
    }

    /** Set done explicitly. @return false if out of range */
    public synchronized boolean setDone(int n, boolean done) {
        if (n < 1 || n > items.size()) {
            return false;
        }
        items.set(n - 1, new Item(items.get(n - 1).text(), done));
        return true;
    }

    /** @return the removed item, or null if out of range */
    public synchronized Item remove(int n) {
        if (n < 1 || n > items.size()) {
            return null;
        }
        return items.remove(n - 1);
    }

    /** Move item {@code from} to position {@code to} (both 1-based). */
    public synchronized boolean move(int from, int to) {
        if (from < 1 || from > items.size() || to < 1 || to > items.size()) {
            return false;
        }
        Item i = items.remove(from - 1);
        items.add(to - 1, i);
        return true;
    }

    public synchronized void clear() {
        items.clear();
    }

    /** @return how many were removed */
    public synchronized int clearDone() {
        int before = items.size();
        items.removeIf(Item::done);
        return before - items.size();
    }

    /**
     * Append every item from another list that is not already here (same text,
     * ignoring case), stopping at {@link #MAX_ITEMS}.
     *
     * @return {added, skippedAsDuplicate}
     */
    public synchronized int[] merge(List<Item> incoming) {
        Set<String> have = new HashSet<>();
        for (Item i : items) {
            have.add(i.text().toLowerCase(Locale.ROOT));
        }
        int added = 0;
        int dup = 0;
        for (Item i : incoming) {
            String t = clean(i.text());
            if (t.isEmpty()) {
                continue;
            }
            if (!have.add(t.toLowerCase(Locale.ROOT))) {
                dup++;
                continue;
            }
            if (items.size() >= MAX_ITEMS) {
                break;
            }
            items.add(new Item(t, i.done()));
            added++;
        }
        return new int[] {added, dup};
    }

    public synchronized void replaceWith(List<Item> incoming) {
        items.clear();
        merge(incoming);
    }

    // ------------------------------------------------------------ JSON

    public synchronized String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Edit in game with /todo. Import checklists from config/auraqol-todo/<name>.json with /todo load <name>.");
        JsonArray arr = new JsonArray();
        for (Item i : items) {
            JsonObject o = new JsonObject();
            o.addProperty("text", i.text());
            o.addProperty("done", i.done());
            arr.add(o);
        }
        root.add("items", arr);
        return root.toString();
    }

    /**
     * Read either accepted shape. Never throws: an unreadable file is an empty
     * list, and the caller says so.
     *
     * @return the items, or null if the text was not JSON in either shape
     */
    public static List<Item> parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray arr;
            if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("items")
                    && root.getAsJsonObject().get("items").isJsonArray()) {
                arr = root.getAsJsonObject().getAsJsonArray("items");
            } else {
                return null;
            }
            List<Item> out = new ArrayList<>();
            for (JsonElement el : arr) {
                try {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        out.add(new Item(el.getAsString(), false));
                    } else if (el.isJsonObject()) {
                        JsonObject o = el.getAsJsonObject();
                        if (o.has("text") && o.get("text").isJsonPrimitive()) {
                            boolean done = o.has("done") && o.get("done").isJsonPrimitive()
                                    && o.get("done").getAsBoolean();
                            out.add(new Item(o.get("text").getAsString(), done));
                        }
                    }
                } catch (RuntimeException ignored) {
                    // one bad entry, not a bad file
                }
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Trim, collapse newlines, cap the length. Formatting codes are removed so text cannot recolour the HUD. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String t = Fmt.strip(text).replaceAll("[\\r\\n\\t]+", " ").trim();
        if (t.length() > MAX_TEXT) {
            t = t.substring(0, MAX_TEXT).trim();
        }
        return t;
    }
}
