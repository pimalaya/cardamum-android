package org.pimalaya.cardamum.billing;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

/**
 * Google Play Billing gate. The whole app is behind an active
 * subscription. {@link #enforce} gates on the cached {@link Entitlement}
 * so an entitled subscriber opens the app offline with no startup network
 * wait: within the grace window it lets the app through and refreshes the
 * cache from Play in the background; otherwise it opens the paywall, which
 * queries Play live, lets an active subscriber (trial included) straight
 * through, and otherwise offers the subscription. See
 * docs/subscription-entitlement.md.
 */
final class GoogleBilling implements Billing {
    private final Context context;

    GoogleBilling(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void enforce(Activity host) {
        Entitlement entitlement = new Entitlement(context);
        if (entitlement.isValid()) {
            // Trusted offline within the grace window: open the app now
            // and re-verify against Play in the background. A lapse
            // surfaces at the next launch, not mid-session.
            new PlayVerifier(context, entitlement).verify();
        } else {
            host.startActivity(new Intent(host, PaywallActivity.class));
        }
    }
}
