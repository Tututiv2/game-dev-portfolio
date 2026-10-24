package com.skyclone.mod.collections;

/**
 * A parsed requirement key {@code collection:<ITEM_ID>:<tier>}. Item ids may themselves contain ':'
 * ("INK_SACK:3" is Cocoa Beans), so the LAST part is the tier and everything between is the id.
 * The tier may be a number or a Roman numeral. {@code collection:<ITEM_ID>} alone means "discovered"
 * (tier 0 with at least one item).
 */
public record CollectionKey(CollectionCatalog.Coll coll, int tier) {

    /** From the full key string ("collection:INK_SACK:3:5"). Null if malformed or unknown. */
    public static CollectionKey parse(CollectionCatalog cat, String key) {
        if (key == null) return null;
        return parts(cat, key.trim().split(":"));
    }

    /** From the resolver's parts (index 0 = "collection"). */
    public static CollectionKey parts(CollectionCatalog cat, String[] parts) {
        if (parts == null || parts.length < 2) return null;
        if (parts.length == 2) {
            CollectionCatalog.Coll c = cat.resolve(parts[1]);
            return c == null ? null : new CollectionKey(c, 0);
        }
        int tier = CollectionMath.parseTier(parts[parts.length - 1]);
        String id = String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
        if (tier < 0) {
            // "collection:INK_SACK:3" could be the bare id INK_SACK:3 (discovered) when INK_SACK is not a collection
            CollectionCatalog.Coll whole = cat.resolve(String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
            return whole == null ? null : new CollectionKey(whole, 0);
        }
        CollectionCatalog.Coll c = cat.resolve(id);
        if (c == null) {
            CollectionCatalog.Coll whole = cat.resolve(String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length)));
            if (whole != null) return new CollectionKey(whole, 0);
            return null;
        }
        return new CollectionKey(c, tier);
    }
}
