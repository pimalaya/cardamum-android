package org.pimalaya.cardamum.billing;

import android.app.Activity;

/**
 * The paid-access gate, bound per build variant. The FOSS builds (GitHub
 * CI, F-Droid) bind a no-op that lets everything through and carries no
 * Google code; the Google Play build binds a Google Play Billing
 * implementation that holds the whole app behind an active subscription
 * (a Play-managed free trial counts as active). Obtain an instance
 * through {@code BillingFactory.create} and call {@link #enforce} once at
 * launch.
 */
public interface Billing {
    /**
     * Enforces paid access. On the Google build it opens a full-screen
     * paywall over {@code host} unless an active subscription is found,
     * so the app cannot be used without one; a no-op on the FOSS build.
     */
    void enforce(Activity host);
}
