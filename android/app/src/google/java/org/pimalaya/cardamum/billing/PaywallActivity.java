package org.pimalaya.cardamum.billing;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import java.util.Collections;
import java.util.List;
import org.pimalaya.cardamum.R;

/**
 * The full-screen paywall of the Google build: the whole app is behind
 * it. On launch it connects to Google Play, and if an active
 * subscription exists (a Play-managed free trial reports as active) it
 * finishes at once, revealing the app. Otherwise it offers the
 * subscription; a successful purchase finishes it, and backing out
 * leaves the app entirely, since nothing is usable without it.
 */
public final class PaywallActivity extends Activity {
    // The subscription product id created in the Play Console (Monetize >
    // Products > Subscriptions): base plan "cardamum" with the trial offer
    // "cardamum-trial".
    private static final String SUBSCRIPTION_ID = "cardamum";

    private BillingClient billing;
    private Entitlement entitlement;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_paywall);
        entitlement = new Entitlement(this);
        findViewById(R.id.paywall_subscribe).setOnClickListener(view -> subscribe());

        billing =
                BillingClient.newBuilder(this)
                        .enablePendingPurchases(
                                PendingPurchasesParams.newBuilder()
                                        .enableOneTimeProducts()
                                        .build())
                        .setListener(this::onPurchasesUpdated)
                        .build();

        billing.startConnection(
                new BillingClientStateListener() {
                    @Override
                    public void onBillingSetupFinished(BillingResult result) {
                        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                            checkEntitlement();
                        } else {
                            runOnUiThread(PaywallActivity.this::showOffer);
                        }
                    }

                    @Override
                    public void onBillingServiceDisconnected() {
                        // The next launch retries the connection.
                    }
                });
    }

    /** Reveals the app if the subscription is already active. */
    private void checkEntitlement() {
        billing.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                (result, purchases) -> {
                    boolean answered =
                            result.getResponseCode() == BillingClient.BillingResponseCode.OK;
                    boolean active = answered && Subscriptions.isActive(purchases);
                    // Only refresh the cache on a definitive Play answer, so
                    // a failed query never marks a subscriber as lapsed.
                    if (answered) {
                        entitlement.record(active);
                    }
                    if (active) {
                        acknowledge(purchases);
                        runOnUiThread(this::unlock);
                    } else {
                        runOnUiThread(this::showOffer);
                    }
                });
    }

    /** Queries the product, then opens Google Play's purchase sheet. */
    private void subscribe() {
        QueryProductDetailsParams.Product product =
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SUBSCRIPTION_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build();
        billing.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder()
                        .setProductList(Collections.singletonList(product))
                        .build(),
                (result, products) -> runOnUiThread(() -> launch(products)));
    }

    private void launch(List<ProductDetails> products) {
        if (products.isEmpty()
                || products.get(0).getSubscriptionOfferDetails() == null
                || products.get(0).getSubscriptionOfferDetails().isEmpty()) {
            Toast.makeText(this, R.string.paywall_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        ProductDetails product = products.get(0);
        String offer = product.getSubscriptionOfferDetails().get(0).getOfferToken();
        BillingFlowParams.ProductDetailsParams selection =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(offer)
                        .build();
        billing.launchBillingFlow(
                this,
                BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(Collections.singletonList(selection))
                        .build());
    }

    private void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                && Subscriptions.isActive(purchases)) {
            entitlement.record(true);
            acknowledge(purchases);
            unlock();
        }
    }

    /** Google requires a purchase to be acknowledged or it is refunded. */
    private void acknowledge(List<Purchase> purchases) {
        for (Purchase purchase : purchases) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED
                    && !purchase.isAcknowledged()) {
                billing.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchase.getPurchaseToken())
                                .build(),
                        result -> {});
            }
        }
    }

    private void showOffer() {
        findViewById(R.id.paywall_progress).setVisibility(View.GONE);
        findViewById(R.id.paywall_content).setVisibility(View.VISIBLE);
    }

    private void unlock() {
        finish();
    }

    @Override
    public void onBackPressed() {
        // The whole app is behind the wall, so leaving the paywall leaves
        // the app rather than slipping past it.
        finishAffinity();
    }

    @Override
    protected void onDestroy() {
        if (billing != null) {
            billing.endConnection();
        }
        super.onDestroy();
    }
}
