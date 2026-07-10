package org.pimalaya.cardamum.billing;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

/**
 * Google Play Billing gate. The whole app is behind an active
 * subscription, so {@link #enforce} opens the paywall over the host; the
 * paywall itself queries Play, lets an active subscriber (trial included)
 * straight through, and otherwise offers the subscription. The billing
 * work lives in {@link PaywallActivity}.
 */
final class GoogleBilling implements Billing {
    private final Context context;

    GoogleBilling(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void enforce(Activity host) {
        host.startActivity(new Intent(host, PaywallActivity.class));
    }
}
