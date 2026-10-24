package com.auracapes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.auracapes.mixin.MinecraftAccessor;
import com.mojang.authlib.yggdrasil.ProfileResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.core.UUIDUtil;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Switch who you play as, from inside the game - the Lunar account switcher.
 *
 * <p><b>Two kinds of account.</b> The <em>launch account</em> is whoever the
 * launcher (or Play.bat) started the game as; it is always first in the list
 * and can never be removed. Every other entry is an <em>offline account</em>:
 * just a name, with the same UUID an offline-mode server would give it, so
 * your inventory on an offline server follows the name and not the session.
 *
 * <p><b>What an offline account can join</b>, stated plainly so nobody finds
 * out by getting kicked: singleplayer, LAN, and servers with
 * {@code online-mode=false}. A real Microsoft account needs Microsoft's
 * sign-in, which needs an approved Azure app - see MinecraftLauncher's
 * KNOWN_LIMITATIONS - so it is not faked here.
 *
 * <p><b>Switching only happens with no world open.</b> A connected server
 * already knows you by the old name and UUID; changing the client's idea of
 * who it is underneath a live connection would desync "which player is me"
 * and nothing good comes of that.
 */
public final class Accounts {

    /** Offline names you have added, in the order you added them. */
    private static final List<String> SAVED = new ArrayList<>();

    /** The name to restore on startup; blank means the launch account. */
    private static String activeName = "";

    private static User launchUser;
    private static CompletableFuture<ProfileResult> launchProfile;
    private static Supplier<PlayerSkin> launchSkin;

    private Accounts() {
    }

    // ------------------------------------------------------------ state

    /** Called by CapeRegistry while reading the config. Does not save. */
    static void restore(List<String> names, String active) {
        SAVED.clear();
        for (String n : names) {
            if (validate(n) == null && indexOf(n) < 0) {
                SAVED.add(n);
            }
        }
        activeName = active == null ? "" : active;
    }

    static List<String> savedNames() {
        return new ArrayList<>(SAVED);
    }

    static String savedActive() {
        return activeName;
    }

    /**
     * Remember what the launcher handed us, before anything replaces it.
     * Idempotent, and called lazily from every entry point, because there is
     * no single moment that is guaranteed to come first.
     */
    private static void captureLaunch() {
        if (launchUser != null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        launchUser = mc.getUser();
        launchProfile = ((MinecraftAccessor) mc).auracapes$getProfileFuture();
    }

    /** Puts you back on whichever account you were on when you quit. */
    public static void applySaved() {
        captureLaunch();
        if (activeName.isBlank() || activeName.equalsIgnoreCase(launchUser.getName())) {
            return;
        }
        if (indexOf(activeName) < 0) {
            activeName = "";
            return;
        }
        apply(offlineUser(activeName), CompletableFuture.completedFuture(null));
        AuraCapes.LOG.info("[auracapes] restored account '{}'", activeName);
    }

    // ------------------------------------------------------------ queries

    public static String launchName() {
        captureLaunch();
        return launchUser.getName();
    }

    public static String currentName() {
        return Minecraft.getInstance().getUser().getName();
    }

    public static boolean isCurrent(String name) {
        return currentName().equalsIgnoreCase(name);
    }

    public static boolean onLaunchAccount() {
        return isCurrent(launchName());
    }

    /** Every account, launch account first. */
    public static List<String> all() {
        List<String> out = new ArrayList<>();
        out.add(launchName());
        out.addAll(SAVED);
        return out;
    }

    /**
     * True when there is no world or server to pull the rug out from under.
     * The connection check matters on its own: during a join there is a
     * connection but no level yet, and that is the worst moment of all.
     */
    public static boolean canSwitchNow() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null && mc.getConnection() == null;
    }

    /** The face to draw for an account in the switcher. */
    public static PlayerSkin skinFor(String name) {
        captureLaunch();
        if (name.equalsIgnoreCase(launchUser.getName())) {
            // An online launch account has a real skin; fetch it once and let
            // the skin manager cache it. getNow, never join: this is called
            // every frame and must not wait on the network.
            ProfileResult result = launchProfile == null ? null : launchProfile.getNow(null);
            if (result != null) {
                if (launchSkin == null) {
                    launchSkin = Minecraft.getInstance().getSkinManager()
                            .createLookup(result.profile(), false);
                }
                return launchSkin.get();
            }
            return DefaultPlayerSkin.get(launchUser.getProfileId());
        }
        return DefaultPlayerSkin.get(UUIDUtil.createOfflinePlayerUUID(name));
    }

    /** The skin of whoever you are right now. */
    public static PlayerSkin currentSkin() {
        return skinFor(currentName());
    }

    // ------------------------------------------------------------ actions

    /**
     * Why a name is unusable, or null if it is fine.
     *
     * <p>The rule is vanilla's own: 3-16 of letters, digits and underscore.
     * An offline server would accept more, but a name that only works on some
     * servers is a name that will fail at the worst moment.
     */
    public static String validate(String name) {
        if (name == null || name.isBlank()) {
            return "Type a username first.";
        }
        if (name.length() < 3) {
            return "Too short - names are 3 to 16 characters.";
        }
        if (name.length() > 16) {
            return "Too long - names are 3 to 16 characters.";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return "Only letters, numbers and _ are allowed.";
            }
        }
        return null;
    }

    /** Adds the name if new. Returns false if it was already in the list. */
    public static boolean add(String name) {
        captureLaunch();
        if (name.equalsIgnoreCase(launchUser.getName()) || indexOf(name) >= 0) {
            return false;
        }
        SAVED.add(name);
        CapeRegistry.save();
        return true;
    }

    /** The launch account and the account you are on cannot be removed. */
    public static boolean removable(String name) {
        return indexOf(name) >= 0 && !isCurrent(name);
    }

    public static void remove(String name) {
        int i = indexOf(name);
        if (i >= 0 && !isCurrent(name)) {
            SAVED.remove(i);
            CapeRegistry.save();
        }
    }

    /**
     * Become this account. Returns null on success, or why it did not happen.
     */
    public static String switchTo(String name) {
        captureLaunch();
        if (!canSwitchNow()) {
            return "Leave your world first - the server already knows you as "
                    + currentName() + ".";
        }
        if (name.equalsIgnoreCase(launchUser.getName())) {
            // Back to the launch account: restore the ORIGINAL objects, so an
            // online account gets its real token and fetched profile back
            // rather than an offline lookalike with the same name.
            apply(launchUser, launchProfile);
            activeName = "";
        } else {
            if (indexOf(name) < 0) {
                return "That account is not in the list.";
            }
            apply(offlineUser(SAVED.get(indexOf(name))),
                    CompletableFuture.completedFuture(null));
            activeName = SAVED.get(indexOf(name));
        }
        CapeRegistry.save();
        AuraCapes.LOG.info("[auracapes] now playing as '{}'", currentName());
        return null;
    }

    private static void apply(User user, CompletableFuture<ProfileResult> profile) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftAccessor access = (MinecraftAccessor) mc;
        access.auracapes$setUser(user);
        access.auracapes$setProfileFuture(profile);
        mc.updateTitle();
    }

    private static User offlineUser(String name) {
        UUID id = UUIDUtil.createOfflinePlayerUUID(name);
        // "0" is the token every offline launcher sends. There is nothing
        // behind it, which is the honest value for an account with no login.
        return new User(name, id, "0", Optional.empty(), Optional.empty());
    }

    private static int indexOf(String name) {
        for (int i = 0; i < SAVED.size(); i++) {
            if (SAVED.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
