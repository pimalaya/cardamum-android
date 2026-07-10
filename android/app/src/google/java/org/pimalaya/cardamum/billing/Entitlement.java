package org.pimalaya.cardamum.billing;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The cached subscription entitlement of the Google build, so the app can
 * open offline without waiting on Google Play at startup. It records
 * whether the subscription was entitled at the last successful Play check
 * and when that check happened; an entitled state is then trusted for a
 * grace window, after which a fresh Play verification is required. See
 * docs/subscription-entitlement.md.
 */
final class Entitlement {
    // How long an entitled state is trusted offline after the last
    // successful Play verification: the offline lease a subscriber gets
    // between renewals.
    private static final long GRACE_WINDOW_MILLIS = 14L * 24 * 60 * 60 * 1000;

    private static final String PREFS = "billing";
    private static final String KEY_ENTITLED = "entitled";
    private static final String KEY_LAST_VERIFIED = "last_verified";

    private final SharedPreferences prefs;

    Entitlement(Context context) {
        this.prefs =
                context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Whether the app may open on the cached entitlement alone: entitled
     * at the last Play check and still within the grace window.
     */
    boolean isValid() {
        if (!prefs.getBoolean(KEY_ENTITLED, false)) {
            return false;
        }
        long age = System.currentTimeMillis() - prefs.getLong(KEY_LAST_VERIFIED, 0);
        return age >= 0 && age <= GRACE_WINDOW_MILLIS;
    }

    /** Records a Play verification result, stamped as of now. */
    void record(boolean entitled) {
        prefs.edit()
                .putBoolean(KEY_ENTITLED, entitled)
                .putLong(KEY_LAST_VERIFIED, System.currentTimeMillis())
                .apply();
    }
}
