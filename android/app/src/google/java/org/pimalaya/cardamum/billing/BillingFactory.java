package org.pimalaya.cardamum.billing;

import android.content.Context;

/**
 * Google Play binding: routes to the Play Billing gate. Only compiled
 * into the google build, so no other variant references Google code.
 */
public final class BillingFactory {
    public static Billing create(Context context) {
        return new GoogleBilling(context);
    }

    private BillingFactory() {}
}
