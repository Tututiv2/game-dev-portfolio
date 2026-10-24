package com.aisky.showcontrol;

import com.skyclone.api.Sky;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /show reset - give the cast a brand-new SkyBlock profile without stopping the server.
 *
 * <p>How, using SkyClone's own profile semantics rather than deleting anything: every open
 * membership of the account is CLOSED ({@code profile_members.left_at = now, active = 0}), exactly
 * what SkyClone's ProfileStore.closeMembership does. SkyClone ignores closed memberships everywhere,
 * so the next login runs its normal "no profile -> create the first one" path: a new profile id,
 * empty purse, fresh member data, a new private island instance, {@code ProfileReady(firstJoin=true)}.
 * Old rows are kept (SkyClone keeps closed memberships too). Nothing in SkyClone is patched.
 *
 * <p>Order matters: the players are kicked first (SkyClone queues their profile save on its single
 * DB thread during quit), then the UPDATE is queued on that same FIFO thread two seconds later, so
 * it always lands after the save. The vanilla inventory (it lives in the world's player file, not in
 * SkyClone's DB) is wiped on the account's next join, before SkyClone hands out starter items.
 *
 * <p>The offline equivalent (server stopped) is scripts/reset_episode.ps1 -> scripts/reset_profiles.py.
 */
final class ProfileReset {

    private final ShowControlPlugin plugin;
    private final File pendingFile;
    /** uuid -> name of accounts whose vanilla inventory must be wiped on next join. */
    private final Map<UUID, String> pendingWipe = new ConcurrentHashMap<>();
    /** Accounts whose memberships are being closed right now: logins are refused until it lands. */
    private final Set<UUID> closing = ConcurrentHashMap.newKeySet();

    ProfileReset(ShowControlPlugin plugin) {
        this.plugin = plugin;
        this.pendingFile = new File(plugin.getDataFolder(), "pending-wipe.yml");
        load();
    }

    static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private UUID uuidOf(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        if (!Bukkit.getOnlineMode()) return offlineUuid(name);
        var cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }

    /** Main thread. {@code names} empty = the whole cast. */
    void reset(CommandSender who, List<String> names) {
        List<String> targets = new ArrayList<>(names.isEmpty() ? plugin.config().cast : names);
        if (targets.isEmpty()) { who.sendMessage("No cast configured and no names given."); return; }
        Map<UUID, String> ids = new LinkedHashMap<>();
        for (String n : targets) {
            if (plugin.config().isCamera(n)) continue;
            UUID u = uuidOf(n);
            if (u == null) { who.sendMessage("Unknown account " + n + " (never joined, online-mode server)"); continue; }
            ids.put(u, n);
        }
        if (ids.isEmpty()) return;
        if (plugin.episode().running) plugin.episode().stop("reset");
        for (Map.Entry<UUID, String> e : ids.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.kick(Component.text("Episode reset - rejoin in a few seconds."));
            pendingWipe.put(e.getKey(), e.getValue());
            closing.add(e.getKey());
        }
        save();
        plugin.memory().clear();
        plugin.gather().clear();
        who.sendMessage("Kicked " + ids.size() + " account(s); closing their profiles in 2 s...");
        Bukkit.getScheduler().runTaskLater(plugin, () -> closeProfiles(who, ids), 40L);
    }

    private void closeProfiles(CommandSender who, Map<UUID, String> ids) {
        long now = System.currentTimeMillis();
        Sky.db().transaction(c -> {
            int closed = 0;
            List<String> old = new ArrayList<>();
            for (UUID u : ids.keySet()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT profile_id FROM profile_members WHERE uuid = ? AND left_at = 0")) {
                    ps.setString(1, u.toString());
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) old.add(rs.getString(1)); }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE profile_members SET left_at = ?, active = 0 WHERE uuid = ? AND left_at = 0")) {
                    ps.setLong(1, now);
                    ps.setString(2, u.toString());
                    closed += ps.executeUpdate();
                }
            }
            return closed + ":" + String.join(",", old);
        }).whenComplete((res, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            closing.removeAll(ids.keySet());
            if (err != null) {
                plugin.getLogger().severe("/show reset failed: " + err);
                who.sendMessage("Reset FAILED: " + err.getMessage());
                return;
            }
            String closed = res == null ? "0" : res.substring(0, res.indexOf(':'));
            plugin.getLogger().info("/show reset closed " + closed + " membership(s): " + res);
            who.sendMessage("Reset done: " + closed + " profile membership(s) closed for " + String.join(", ", ids.values())
                    + ". They get a fresh profile on their next join.");
            plugin.bus().ev("mark").drama(3).data("label", "reset")
                    .data("accounts", EventBus.GSON.toJsonTree(new ArrayList<>(ids.values())))
                    .text("Fresh start: every profile was wiped").emit();
        }));
    }

    /** AsyncPlayerPreLoginEvent (async thread): refuse a login that would load the profile being closed. */
    void onPreLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent e) {
        if (closing.contains(e.getUniqueId())) {
            e.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Episode reset in progress - try again in a few seconds."));
        }
    }

    /** LOWEST PlayerJoinEvent. */
    void onJoin(Player p) {
        if (pendingWipe.remove(p.getUniqueId()) == null) return;
        save();
        try {
            p.getInventory().clear();
            p.getEnderChest().clear();
            p.setLevel(0);
            p.setExp(0f);
            p.setTotalExperience(0);
            for (PotionEffect fx : p.getActivePotionEffects()) p.removePotionEffect(fx.getType());
            plugin.getLogger().info("wiped vanilla inventory of " + p.getName() + " after /show reset");
        } catch (RuntimeException e) {
            plugin.getLogger().warning("could not wipe " + p.getName() + ": " + e);
        }
    }

    Set<UUID> pending() { return pendingWipe.keySet(); }

    private void load() {
        if (!pendingFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pendingFile);
        for (String k : y.getKeys(false)) {
            try { pendingWipe.put(UUID.fromString(k), y.getString(k, "")); } catch (IllegalArgumentException ignored) { }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : pendingWipe.entrySet()) y.set(e.getKey().toString(), e.getValue());
        try { y.save(pendingFile); } catch (IOException e) { plugin.getLogger().warning("pending-wipe.yml: " + e); }
    }
}
