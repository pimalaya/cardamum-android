package org.pimalaya.cardamum.billing;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

/**
 * Google Play binding of the support prompt (docs/monetization.md):
 * {@link #prompt} opens the dismissable support panel when it is due
 * (nothing paid yet and the last showing 3 days old or more, per the
 * cached {@link SupportStore}), and does nothing otherwise; {@link
 * #open} skips the cadence for the drawer's Support row. The panel
 * itself re-checks the purchases against Play, so a payment made on
 * another device or before a reinstall silences the prompt without any
 * startup network wait.
 */
final class GoogleBilling implements Billing {
    private final Context context;

    GoogleBilling(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void prompt(Activity host) {
        if (new SupportStore(context).due()) {
            open(host);
        }
    }

    @Override
    public void open(Activity host) {
        host.startActivity(new Intent(host, SupportActivity.class));
        // A plain cross-fade in place of the default activity
        // transition; the panel's finish() mirrors it on the way out.
        host.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public boolean supported() {
        return true;
    }
}
