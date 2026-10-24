package com.skyclone.mod.bank;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent per-player and per-profile state (Gson-friendly; fields are only ever ADDED, never retyped).
 * Coin balances are NOT here: the co-op account lives in core's economy, personal accounts in the
 * {@code bank_personal} table, so a profile-save delay can never mint or lose coins.
 */
public final class BankData {

    private BankData() {}

    /** Member key {@code "bank"}: this player's own state on this profile. */
    public static final class Member {
        /** Epoch millis the Boon Buff runs until (0 = never had one). */
        public long cookieUntil;
        /** True once the "your buff expired" message was shown for the current buff. */
        public boolean cookieExpiredNotified = true;
        /** Bits still waiting to be paid out by the buff. */
        public long bitsAvailable;
        /** Epoch millis of the last bits payout. */
        public long lastBitsPayout;
        /** Lifetime fame (1 per bit spent in the Community Shop). */
        public long fame;
        /** Lifetime bits spent (statistics). */
        public long bitsSpent;
        public boolean freeCookieClaimed;
        /** Cookies eaten (statistics). */
        public int cookiesEaten;

        /** UTC date key of {@link #tradeSentToday}. */
        public String tradeDay = "";
        public double tradeSentToday;
        public long tradesCompleted;

        /** Account-scope Community Shop upgrades: id -> tier owned. */
        public Map<String, Integer> upgrades = new HashMap<>();
        /** The account upgrade under construction ("" = none), its target tier and finish time. */
        public String building = "";
        public int buildingTier;
        public long buildingDone;

        /** Epoch millis of the last Pocket Bank use (cooldown). */
        public long lastPocketUse;
        /** First deposit done (objective + SkyBlock XP once). */
        public boolean firstDeposit;

        /**
         * Wave 3: stamp of the account-wide snapshot this copy matches ({@link AccountWide}); 0 = never synced
         * (legacy data, merged rather than overwritten the first time).
         */
        public long accountSavedAt;
    }

    /** Shared key {@code "bank_account"}: the profile's (co-op's) account state. */
    public static final class Shared {
        /** Account tier index (0 = Starter). */
        public int tier;
        /** Interest period last paid (-1 = not started yet; the first period seen is never paid). */
        public long lastInterestPeriod = -1;
        /** Profile-scope Community Shop upgrades: id -> tier owned. */
        public Map<String, Integer> upgrades = new HashMap<>();
        public String building = "";
        public int buildingTier;
        public long buildingDone;
        /** UTC date key of the last Coins Allowance payout. */
        public String allowanceDay = "";
        /** Total interest ever paid into the co-op account (statistics). */
        public double interestEarned;
    }
}
