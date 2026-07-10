package org.pimalaya.cardamum.billing;

import android.content.Context;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.QueryPurchasesParams;

/**
 * A one-shot, headless Google Play check that refreshes the cached
 * {@link Entitlement} off the startup critical path. It connects, queries
 * the subscription purchases, records the result, and disconnects; it
 * shows no UI and never blocks the app. A lapse it discovers surfaces at
 * the next launch, when the cache is re-evaluated, not mid-session.
 */
final class PlayVerifier {
    private final Context context;
    private final Entitlement entitlement;

    PlayVerifier(Context context, Entitlement entitlement) {
        this.context = context.getApplicationContext();
        this.entitlement = entitlement;
    }

    void verify() {
        BillingClient billing =
                BillingClient.newBuilder(context)
                        .enablePendingPurchases(
                                PendingPurchasesParams.newBuilder()
                                        .enableOneTimeProducts()
                                        .build())
                        .setListener((result, purchases) -> {})
                        .build();

        billing.startConnection(
                new BillingClientStateListener() {
                    @Override
                    public void onBillingSetupFinished(BillingResult result) {
                        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                            billing.endConnection();
                            return;
                        }
                        billing.queryPurchasesAsync(
                                QueryPurchasesParams.newBuilder()
                                        .setProductType(BillingClient.ProductType.SUBS)
                                        .build(),
                                (queryResult, purchases) -> {
                                    if (queryResult.getResponseCode()
                                            == BillingClient.BillingResponseCode.OK) {
                                        entitlement.record(Subscriptions.isActive(purchases));
                                    }
                                    billing.endConnection();
                                });
                    }

                    @Override
                    public void onBillingServiceDisconnected() {}
                });
    }
}
