package org.pimalaya.cardamum.billing;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.pimalaya.cardamum.R;

/**
 * The support panel of the Google build (docs/monetization.md): a
 * dismissable full-screen ask framed as the app's price list rather
 * than a donation banner. It opens on a spinner while Play answers the
 * purchases query: any owned tier records the paid flag and closes the
 * panel unseen (a payment made elsewhere or before a reinstall), and
 * only an unpaid answer (or an unreachable Play) reveals the ask and
 * stamps the 3-day window. Each tier is a list-item block (a radio
 * with the name and price, its description always visible below, the
 * whole block one ripple tap target that selects it), the cheapest
 * checked by default, live from Play when reachable and static labels
 * otherwise; the fixed bottom bar on the app-bar tone carries the
 * LATER dismiss and the accent pay button, whose label follows the
 * selected tier's amount and opens Google Play's purchase sheet.
 * Paying any tier silences the prompt for good; dismissing (LATER, the
 * top cross or back) just closes it until the next window.
 */
public final class SupportActivity extends Activity {
    // The one-time, pay-what-you-want, cumulative tiers created in the
    // Play Console (Monetize > Products > In-app products), keyed by
    // their EUR amount and ordered cheapest first, with the fallback
    // name shown when Play's live details are unreachable.
    private static final String[] TIER_IDS = {
        "cardamum5", "cardamum10", "cardamum25", "cardamum50", "cardamum100",
    };
    private static final int[] TIER_NAMES = {
        R.string.support_tier_5,
        R.string.support_tier_10,
        R.string.support_tier_25,
        R.string.support_tier_50,
        R.string.support_tier_100,
    };
    private static final int[] TIER_DESCS = {
        R.string.support_tier_5_desc,
        R.string.support_tier_10_desc,
        R.string.support_tier_25_desc,
        R.string.support_tier_50_desc,
        R.string.support_tier_100_desc,
    };

    private BillingClient billing;
    private SupportStore store;

    /** Live Play details per tier; null keeps the static fallback row. */
    private final ProductDetails[] tiers = new ProductDetails[TIER_IDS.length];

    private final RadioButton[] tierButtons = new RadioButton[TIER_IDS.length];
    private final TextView[] tierDescs = new TextView[TIER_IDS.length];

    /** The static fallback amounts, aligned with TIER_IDS. */
    private String[] amounts;

    /** The tier currently checked; drives the pay button. */
    private int selected;

    private Button pay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_support);
        applyEdgeToEdge();
        store = new SupportStore(this);
        amounts = getResources().getStringArray(R.array.support_tier_amounts);

        pay = findViewById(R.id.support_pay);
        pay.setOnClickListener(view -> pay(selected));

        buildTierOptions();
        findViewById(R.id.support_later).setOnClickListener(view -> finish());
        findViewById(R.id.support_close).setOnClickListener(view -> finish());

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
                            checkPaid();
                        } else {
                            // The ask still matters without Play; the
                            // rows keep their static labels and taps
                            // report unavailable.
                            runOnUiThread(SupportActivity.this::showAsk);
                        }
                    }

                    @Override
                    public void onBillingServiceDisconnected() {
                        // The next due panel retries the connection.
                    }
                });
    }

    /** Closes the panel unseen when any tier is already owned. */
    private void checkPaid() {
        billing.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                (result, purchases) -> {
                    boolean answered =
                            result.getResponseCode() == BillingClient.BillingResponseCode.OK;
                    boolean paid = answered && Purchases.isPaid(purchases);
                    // Only refresh the flag on a definitive Play answer,
                    // so a failed query never re-prompts a supporter.
                    if (answered) {
                        store.record(paid);
                    }
                    if (paid) {
                        acknowledge(purchases);
                        runOnUiThread(this::finish);
                    } else {
                        queryTiers();
                        runOnUiThread(this::showAsk);
                    }
                });
    }

    /**
     * One list-item block per tier, static labels first: a condensed
     * vertical block (the radio with the name and price, then the
     * always-visible description aligned under the label) behind one
     * full-bleed ripple that selects the tier on tap, like a list row.
     * The radios are pure indicators (the block owns the tap), so
     * exclusivity is managed by {@link #select} rather than a
     * RadioGroup, which only covers direct RadioButton children.
     */
    private void buildTierOptions() {
        LinearLayout container = findViewById(R.id.support_tiers);

        // The radio-to-text gap is half the radio-to-screen-edge inset
        // (the block's item_padding), and the description column starts
        // where the label does: after the button glyph plus that same
        // gap.
        int gap = dp(8);

        for (int at = 0; at < TIER_IDS.length; at++) {
            final int tier = at;

            RadioButton option = new RadioButton(this);
            option.setText(tierLabel(getString(TIER_NAMES[at]), amounts[at]));
            option.setTextColor(resolveColor(android.R.attr.textColorPrimary));
            option.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.item_text));
            option.setMinHeight(0);
            option.setMinimumHeight(0);
            option.setPaddingRelative(gap, 0, 0, 0);
            option.setClickable(false);

            int glyph =
                    option.getButtonDrawable() != null
                            ? option.getButtonDrawable().getIntrinsicWidth()
                            : dp(32);

            TextView desc = new TextView(this);
            desc.setText(TIER_DESCS[at]);
            desc.setTextColor(resolveColor(android.R.attr.textColorSecondary));
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            desc.setPaddingRelative(glyph + gap, 0, 0, 0);

            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(
                    dimen(R.dimen.item_padding), dp(10), dimen(R.dimen.item_padding), dp(10));
            block.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
            block.addView(option);
            block.addView(desc);
            block.setOnClickListener(view -> select(tier));
            container.addView(block);

            tierButtons[at] = option;
            tierDescs[at] = desc;
        }

        select(0);
    }

    /** Checks the tier's radio, unchecks the others, updates the pay button. */
    private void select(int tier) {
        for (int at = 0; at < tierButtons.length; at++) {
            tierButtons[at].setChecked(at == tier);
        }
        selected = tier;
        updateAmount();
    }

    /** The pay button follows the selected tier's amount. */
    private void updateAmount() {
        ProductDetails details = tiers[selected];
        pay.setText(
                details != null
                        ? details.getOneTimePurchaseOfferDetails().getFormattedPrice()
                        : amounts[selected]);
    }

    private static String tierLabel(String name, String price) {
        return name + " · " + price;
    }

    /** Refreshes the rows with Play's live names and prices. */
    private void queryTiers() {
        List<QueryProductDetailsParams.Product> products = new ArrayList<>(TIER_IDS.length);
        for (String id : TIER_IDS) {
            products.add(
                    QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build());
        }
        billing.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(products).build(),
                (result, details) -> runOnUiThread(() -> bindTiers(details)));
    }

    private void bindTiers(List<ProductDetails> details) {
        for (ProductDetails product : details) {
            if (product.getOneTimePurchaseOfferDetails() == null) {
                continue;
            }
            for (int at = 0; at < TIER_IDS.length; at++) {
                if (TIER_IDS[at].equals(product.getProductId())) {
                    tiers[at] = product;
                    tierButtons[at].setText(
                            tierLabel(
                                    product.getName(),
                                    product.getOneTimePurchaseOfferDetails()
                                            .getFormattedPrice()));
                    if (!product.getDescription().isEmpty()) {
                        tierDescs[at].setText(product.getDescription());
                    }
                }
            }
        }
        updateAmount();
    }

    /** Opens Google Play's purchase sheet on the tapped tier. */
    private void pay(int tier) {
        ProductDetails details = tiers[tier];
        if (details == null) {
            Toast.makeText(this, R.string.support_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        BillingFlowParams.ProductDetailsParams selection =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build();
        billing.launchBillingFlow(
                this,
                BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(Collections.singletonList(selection))
                        .build());
    }

    private void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                && Purchases.isPaid(purchases)) {
            store.record(true);
            acknowledge(purchases);
            runOnUiThread(
                    () -> {
                        Toast.makeText(this, R.string.support_thanks, Toast.LENGTH_LONG).show();
                        finish();
                    });
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

    /** Reveals the ask and stamps the 3-day window of the next one. */
    private void showAsk() {
        store.markShown();
        findViewById(R.id.support_progress).setVisibility(View.GONE);
        findViewById(R.id.support_content).setVisibility(View.VISIBLE);
        findViewById(R.id.support_actions).setVisibility(View.VISIBLE);
    }

    /**
     * Edge-to-edge (enforced for apps targeting API 35): pads the content
     * by the system-bar insets so the centred ask clears the status and
     * navigation bars. A no-op below API 35, where the bars stay opaque.
     */
    private void applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }
        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    Insets bars =
                            insets.getInsets(
                                    WindowInsets.Type.systemBars()
                                            | WindowInsets.Type.displayCutout());
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return insets;
                });
        content.requestApplyInsets();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int dimen(int id) {
        return getResources().getDimensionPixelSize(id);
    }

    private int resolveColor(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private int resolveAttr(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    @Override
    public void finish() {
        super.finish();
        // Leave with the same plain cross-fade the panel entered with
        // (GoogleBilling.prompt), on every close path: LATER, the
        // cross, back, a purchase, or the already-paid self-close.
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        if (billing != null) {
            billing.endConnection();
        }
        super.onDestroy();
    }
}
