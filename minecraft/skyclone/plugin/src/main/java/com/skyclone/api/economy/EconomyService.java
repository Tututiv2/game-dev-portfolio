package com.skyclone.api.economy;

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Coins (purse + bank) and bits. Every mutation is written to the database immediately and
 * appended to an audit ledger with its {@code reason} — this is built to run as a public
 * server, so "where did these coins come from" must always have an answer.
 *
 * <p>Amounts are coins with up to one decimal (stored as tenths). Negative amounts are rejected.
 * All methods are main-thread only.
 */
public interface EconomyService {

    double purse(Player p);

    /** False (and nothing taken) when the purse is short. */
    boolean withdraw(Player p, double amount, String reason);

    void deposit(Player p, double amount, String reason);

    /** Pay someone who may be offline (auction proceeds, co-op). Completes once durable. */
    CompletableFuture<Void> depositOffline(UUID player, double amount, String reason);

    /**
     * Pay into ONE specific profile (the one that listed the auction / placed the bazaar order), not
     * whichever profile the player has active when it sells. Get the id with
     * {@code Sky.profiles().profileId(p)} when the listing is made. If the player has since left that
     * profile, the coins go to their active profile instead (never lost). (Added by the core gate.)
     */
    CompletableFuture<Void> depositOffline(String profileId, UUID player, double amount, String reason);

    /** Bank is shared by every member of a co-op profile. */
    double bank(Player p);

    double bankCapacity(Player p);

    /** Bank module sets this as the account is upgraded. */
    void setBankCapacity(Player p, double capacity);

    boolean bankDeposit(Player p, double amount);

    boolean bankWithdraw(Player p, double amount);

    /** Interest and admin paths that bypass the purse. */
    void bankCredit(Player p, double amount, String reason);

    long bits(Player p);

    void addBits(Player p, long amount, String reason);

    boolean spendBits(Player p, long amount, String reason);

    /** "1,234,567.5" — drops ".0". */
    String format(double coins);
}
