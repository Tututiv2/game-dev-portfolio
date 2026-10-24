package com.aisky.showcontrol;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** config.yml, read once at enable (and on /show reload-config, if ever added). Immutable. */
final class ShowConfig {

    final String httpHost;
    final int httpPort;
    final int ringSize;
    final int heartbeatSeconds;

    /** Cast account names, original case, in config order. */
    final List<String> cast;
    private final Set<String> castLower;
    /** Mutable: /show camera <account> re-designates it live. */
    volatile String camera;

    final int gatherWindowSeconds;
    final double nearDeathFraction;
    final int nearDeathDebounceSeconds;
    final double bigHitFraction;
    final int bigHitDebounceSeconds;
    final int abilityDebounceSeconds;
    final List<Long> coinMilestones;
    final double stateRadius;
    final int snapshotTicks;
    final boolean freezeWorldTicks;
    final boolean feedbackSender;
    final boolean allowTestCommands;

    ShowConfig(FileConfiguration c) {
        httpHost = c.getString("http.host", "127.0.0.1");
        httpPort = c.getInt("http.port", 25642);
        ringSize = Math.max(100, c.getInt("http.ring-size", 5000));
        heartbeatSeconds = Math.max(2, c.getInt("http.heartbeat-seconds", 10));

        List<String> names = new ArrayList<>();
        for (String s : c.getStringList("cast")) if (s != null && !s.isBlank()) names.add(s.trim());
        cast = Collections.unmodifiableList(names);
        Set<String> low = new LinkedHashSet<>();
        for (String s : names) low.add(s.toLowerCase(Locale.ROOT));
        castLower = Collections.unmodifiableSet(low);
        camera = c.getString("camera", "ShowCam");

        gatherWindowSeconds = Math.max(5, c.getInt("feed.gather-window-seconds", 30));
        nearDeathFraction = c.getDouble("feed.near-death-fraction", 0.15);
        nearDeathDebounceSeconds = c.getInt("feed.near-death-debounce-seconds", 20);
        bigHitFraction = c.getDouble("feed.big-hit-fraction", 0.30);
        bigHitDebounceSeconds = c.getInt("feed.big-hit-debounce-seconds", 5);
        abilityDebounceSeconds = c.getInt("feed.ability-debounce-seconds", 8);
        List<Long> ms = new ArrayList<>();
        for (Object o : c.getList("feed.coin-milestones", List.of())) {
            if (o instanceof Number n) ms.add(n.longValue());
        }
        if (ms.isEmpty()) ms.addAll(List.of(1_000L, 10_000L, 100_000L, 1_000_000L));
        Collections.sort(ms);
        coinMilestones = Collections.unmodifiableList(ms);
        stateRadius = c.getDouble("feed.state-radius", 24);
        snapshotTicks = Math.max(2, c.getInt("feed.snapshot-ticks", 10));
        freezeWorldTicks = c.getBoolean("freeze-world-ticks", true);
        feedbackSender = !"console".equalsIgnoreCase(c.getString("cmd-sender", "feedback"));
        allowTestCommands = c.getBoolean("allow-test-commands", true);
    }

    boolean isCamera(String name) { return name != null && name.equalsIgnoreCase(camera); }

    boolean isCamera(Player p) { return p != null && isCamera(p.getName()); }

    /** A player whose actions belong on the feed: a cast member (or anyone when cast is empty), never the camera. */
    boolean tracked(Player p) {
        if (p == null || isCamera(p)) return false;
        return castLower.isEmpty() || castLower.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    boolean isCast(String name) {
        return name != null && castLower.contains(name.toLowerCase(Locale.ROOT));
    }
}
