package com.thestill.role;

import net.kyori.adventure.text.format.TextColor;

import java.util.List;
import java.util.Set;

/**
 * One rank you can be — bought, or granted.
 *
 * <p><b>Not the ladder.</b> The Still has two axes and confusing them is the mistake
 * this whole package exists to make impossible:
 *
 * <ul>
 *   <li><b>The ladder</b> is D1..D26. It gates depth access, it is <em>earned</em> by
 *       mining, and it lives in {@code com.thestill.rank}.
 *   <li><b>A role</b> is Member, Muffled, Hushed, Silent, Unheard, The Still. It is a
 *       tag, it is <em>bought</em>, and it is cosmetic forever.
 * </ul>
 *
 * A Member can be at D26; The Still can be at D1. They never interact, and no code in
 * this package may read a rank.
 *
 * @param id         stable, lowercase, used in storage and commands
 * @param category   one active role per category per player
 * @param weight     order within the category; higher is further up, and inherits
 * @param name       what it says on the tag
 * @param start      tag colour, or the start of its gradient
 * @param end        the other end of the gradient
 * @param gradient   whether to ramp between the two
 * @param brackets   whether to wrap the name in square brackets
 * @param permissions Bukkit nodes this role and everything below it grants
 * @param perks      capabilities, all of which must be in {@link Perks#ALLOWED}
 */
public record RoleDefinition(String id, String category, int weight, String name,
                             TextColor start, TextColor end, boolean gradient,
                             boolean brackets, List<String> permissions,
                             Set<String> perks) {

    public RoleDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a role needs an id");
        }
        // Same charset the region ids use, and for the same reason: Brigadier's
        // unquoted argument charset. A role id that cannot be typed as a command
        // argument is a role no admin can grant.
        if (!id.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("role id '" + id
                    + "' must be lowercase [a-z0-9_.-] — it has to be typeable as a "
                    + "command argument");
        }
        for (String perk : perks) {
            if (Perks.BANNED.contains(perk)) {
                throw new IllegalArgumentException("role '" + id + "' grants '" + perk
                        + "', which buys gameplay. Ranks are cosmetic FOREVER — see "
                        + "com.thestill.role.Perks");
            }
            if (!Perks.ALLOWED.contains(perk)) {
                throw new IllegalArgumentException("role '" + id + "' grants unknown perk '"
                        + perk + "'. Add it to Perks.ALLOWED in the same commit, with a "
                        + "line saying why it cannot affect ore, money, the ladder, the "
                        + "Din, the heat or the seal");
            }
        }
        permissions = List.copyOf(permissions);
        perks = Set.copyOf(perks);
    }

    /**
     * Does this role put anything above your name?
     *
     * <p>Brackets count as an explicit tag. Shadow Prison learned this from a live
     * bug: turning Member's gradient off deleted its tag entirely and players reported
     * "people don't have member rank". A role that has been given a bracketed name has
     * been given a tag, whatever its colour settings say.
     */
    public boolean hasTag() {
        return brackets || gradient || (!name.isBlank() && !"member".equals(id));
    }
}
