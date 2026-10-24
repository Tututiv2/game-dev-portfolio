package com.thestill.admin;

import com.thestill.player.Profile;

import java.util.UUID;

/**
 * The player an admin is editing, and whether they are here.
 *
 * <p>Every admin page takes one of these rather than a bare {@link Profile}, and that
 * is the point: {@code live} is the difference between "this object is the one they are
 * playing with" and "this object is a detached copy that will be written over the top
 * of the database". A page that does not know which it has will eventually do the wrong
 * one — refresh a scoreboard for somebody who is not online, or overwrite a live
 * session with stale data.
 */
public record AdminTarget(UUID uuid, String name, Profile profile, boolean live) {
}
