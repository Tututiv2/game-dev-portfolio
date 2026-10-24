package com.skyclone.mod.bank;

import com.skyclone.api.ui.Menu;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One open trade between two players. Offered items are OUT of their owner's inventory for the whole
 * trade: each sits in memory here AND as an escrow row in {@code bank_pending} under a random key, so a
 * crash returns it to its owner and a commit moves the row, never the stack.
 */
final class TradeSession {

    /** One escrowed stack. */
    static final class Offer {
        final String key;
        final ItemStack stack;
        Offer(String key, ItemStack stack) { this.key = key; this.stack = stack; }
    }

    static final class Side {
        final UUID uuid;
        final String name;
        final List<Offer> offers = new ArrayList<>();
        /** Coins offered from the purse (a tenth multiple); taken only at commit. */
        double coins;
        boolean accepted;
        /** This side's trade window (one Menu instance per viewer, reused). */
        Menu menu;
        /** Set right before WE switch this player's screen, so the close it causes is not a cancel. */
        boolean expectClose;
        /** Risky trade (nothing back, first-time partner paying a lot): Accept must be clicked again before this. */
        long confirmUntil;

        Side(Player p) { this.uuid = p.getUniqueId(); this.name = p.getName(); }
    }

    final String id = UUID.randomUUID().toString();
    final Side a;
    final Side b;
    /** Millis of the last change to either side; Accept is locked for a few seconds after it. */
    long lastChange = System.currentTimeMillis();
    boolean committing;
    boolean closed;
    /** Completed trades between these two before this one (-1 = still being read from the log). */
    volatile int pairTrades = -1;

    TradeSession(Player a, Player b) {
        this.a = new Side(a);
        this.b = new Side(b);
    }

    Side side(UUID id) {
        if (a.uuid.equals(id)) return a;
        if (b.uuid.equals(id)) return b;
        return null;
    }

    Side other(Side s) { return s == a ? b : a; }

    boolean live() { return !closed && !committing; }
}
