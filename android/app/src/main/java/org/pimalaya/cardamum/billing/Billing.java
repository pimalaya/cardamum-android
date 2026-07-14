package org.pimalaya.cardamum.billing;

import android.app.Activity;

/**
 * The support-prompt seam, bound per build variant (docs/monetization.md:
 * the app is free and complete everywhere, money is an earnest support
 * ask, never a gate). The FOSS builds (GitHub CI, F-Droid) bind a no-op
 * that carries no Google code; the Google Play build binds a Play Billing
 * implementation showing a dismissable support panel at most once per
 * rolling 3 days, silenced for good by paying any tier. Obtain an
 * instance through {@code BillingFactory.create} and call {@link #prompt}
 * on a genuine user-initiated open.
 */
public interface Billing {
    /**
     * Maybe shows the support prompt over {@code host}: on the Google
     * build, opens the dismissable panel when it is due (nothing paid
     * yet and the last showing is 3 days old or more); a no-op on the
     * FOSS build, and never blocking the app anywhere.
     */
    void prompt(Activity host);

    /**
     * Opens the support panel right away, regardless of the prompt
     * cadence: the drawer's Support row. Only meaningful when
     * {@link #supported()}; the default is a no-op.
     */
    default void open(Activity host) {}

    /**
     * Whether this build has a support panel at all: true on the Google
     * build; false (the default) on the FOSS builds, where the drawer's
     * Support row hides.
     */
    default boolean supported() {
        return false;
    }
}
