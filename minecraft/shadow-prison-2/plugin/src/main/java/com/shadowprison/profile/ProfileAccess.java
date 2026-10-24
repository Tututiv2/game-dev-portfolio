package com.shadowprison.profile;

import com.shadowprison.money.Currency;

/**
 * Package-crossing write access to a {@link Profile}'s balances, for {@code Wallets} ONLY.
 * Balances have no public setter, so "only Wallets changes money" is checked by the compiler
 * plus one grep for this class.
 */
public final class ProfileAccess {

    private ProfileAccess() {
    }

    public static void setBalance(Profile profile, Currency currency, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("negative balance " + amount);
        }
        profile.balances.put(currency, amount);
    }
}
