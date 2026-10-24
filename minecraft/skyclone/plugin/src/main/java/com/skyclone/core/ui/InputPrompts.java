package com.skyclone.core.ui;

import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Text prompts. Opens a client-side (virtual) sign editor AND listens to chat at the same time —
 * whichever answer arrives first wins — so a client that refuses the sign still has a way in.
 * "cancel" or an empty sign = null. Times out after {@code ui.input-timeout-seconds} (callback gets null).
 * The callback always runs on the main thread.
 */
final class InputPrompts implements Listener {

    private record Pending(long id, Consumer<String> callback, Location sign) {}

    /** Longest answer accepted; anything longer is cut. */
    static final int MAX_LEN = 256;

    private final JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    InputPrompts(JavaPlugin plugin) { this.plugin = plugin; }

    boolean waiting(Player p) { return pending.containsKey(p.getUniqueId()); }

    /** Main thread only. Replaces (and cancels) any prompt already waiting for this player. */
    void ask(Player p, String prompt, Consumer<String> callback) {
        UUID uid = p.getUniqueId();
        Pending old = pending.remove(uid);
        if (old != null) { restore(p, old); deliver(p, old, null); }

        long id = ids.incrementAndGet();
        Location sign = null;
        boolean useSign = true;
        try { useSign = UiDials.inputSign.get(); } catch (Throwable ignored) { }
        if (useSign) sign = openSign(p, prompt);
        Pending pd = new Pending(id, callback, sign);
        pending.put(uid, pd);

        Msg.send(p, "&e" + prompt);
        Msg.send(p, "&eType your input in chat, or &ccancel&e.");

        int secs = 30;
        try { secs = Math.max(5, UiDials.inputTimeoutSeconds.get()); } catch (Throwable ignored) { }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending cur = pending.get(uid);
            if (cur == null || cur.id() != id || !pending.remove(uid, cur)) return;
            restore(p, cur);
            if (p.isOnline()) {
                Msg.send(p, "&cInput timed out.");
                deliver(p, cur, null);
            }
        }, secs * 20L);
    }

    private Location openSign(Player p, String prompt) {
        try {
            World w = p.getWorld();
            Location feet = p.getLocation();
            int x = feet.getBlockX(), z = feet.getBlockZ();
            int y = feet.getBlockY() - 3;
            if (y < w.getMinHeight()) y = Math.min(w.getMaxHeight() - 1, feet.getBlockY() + 3);
            Location loc = new Location(w, x, y, z);
            List<String> wrapped = wrapPrompt(Text.strip(prompt));
            List<Component> lines = new ArrayList<>(4);
            lines.add(Component.empty());
            lines.add(Text.c("&f^^^^^^^^^^^^^^^"));
            lines.add(Text.c("&e" + (wrapped.size() > 0 ? wrapped.get(0) : "")));
            lines.add(Text.c("&e" + (wrapped.size() > 1 ? wrapped.get(1) : "")));
            org.bukkit.block.data.BlockData data = Material.OAK_SIGN.createBlockData();
            p.sendBlockChange(loc, data);
            if (data.createBlockState() instanceof org.bukkit.block.Sign sign) {
                org.bukkit.block.sign.SignSide front = sign.getSide(Side.FRONT);
                for (int i = 0; i < 4; i++) front.line(i, lines.get(i));
                p.sendBlockUpdate(loc, sign);
            }
            p.openVirtualSign(Position.block(loc), Side.FRONT);
            return loc;
        } catch (Throwable t) {
            UiUtil.warn("virtual sign editor failed; chat input still works", t);
            return null;
        }
    }

    /** Up to two ~15-char lines for the sign. */
    static List<String> wrapPrompt(String prompt) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : prompt.split(" ")) {
            if (word.isEmpty()) continue;
            if (line.length() > 0 && line.length() + 1 + word.length() > 15) {
                out.add(line.toString());
                line = new StringBuilder();
                if (out.size() == 2) return out;
            }
            if (line.length() > 0) line.append(' ');
            line.append(word.length() > 15 ? word.substring(0, 15) : word);
        }
        if (line.length() > 0 && out.size() < 2) out.add(line.toString());
        return out;
    }

    private void restore(Player p, Pending pd) {
        Location loc = pd.sign();
        if (loc == null || !p.isOnline() || !p.getWorld().equals(loc.getWorld())) return;
        Runnable r = () -> { try { p.sendBlockChange(loc, loc.getBlock().getBlockData()); } catch (Throwable ignored) { } };
        UiUtil.main(r);
    }

    private void deliver(Player p, Pending pd, String text) {
        try { pd.callback().accept(text); }
        catch (Throwable t) { UiUtil.warn("input callback for " + p.getName(), t); }
    }

    /** Strip colour/control characters, trim, cap length. Empty or "cancel" = null. */
    static String clean(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '§') { i++; continue; }          // drop the code and its colour char
            if (c < 0x20 || c == 0x7f) continue;
            sb.append(c);
        }
        String s = sb.toString().trim();
        if (s.length() > MAX_LEN) s = s.substring(0, MAX_LEN);
        if (s.isEmpty() || s.equalsIgnoreCase("cancel")) return null;
        return s;
    }

    // ---- answers ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSign(UncheckedSignChangeEvent e) {
        Player p = e.getPlayer();
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null || pd.sign() == null) return;
        BlockPosition pos = e.getEditedBlockPosition();
        Location l = pd.sign();
        if (pos.blockX() != l.getBlockX() || pos.blockY() != l.getBlockY() || pos.blockZ() != l.getBlockZ()) return;
        e.setCancelled(true);
        if (!pending.remove(p.getUniqueId(), pd)) return;
        List<Component> lines = e.lines();
        String text = clean(lines.isEmpty() ? "" : Text.plain(lines.get(0)));
        UiUtil.main(() -> {
            restore(p, pd);
            if (!p.isOnline()) return;
            if (text == null) Msg.send(p, "&cCancelled.");
            deliver(p, pd, text);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null) return;
        e.setCancelled(true);
        if (!pending.remove(p.getUniqueId(), pd)) return;
        String text = clean(Text.plain(e.originalMessage()));
        UiUtil.main(() -> {
            restore(p, pd);
            if (!p.isOnline()) return;
            if (text == null) Msg.send(p, "&cCancelled.");
            deliver(p, pd, text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }

    void clear() { pending.clear(); }
}
