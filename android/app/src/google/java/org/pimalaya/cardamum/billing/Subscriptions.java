package org.pimalaya.cardamum.billing;

import com.android.billingclient.api.Purchase;
import java.util.List;

/** Shared helpers over a Google Play subscription purchase list. */
final class Subscriptions {
    /** Whether any subscription in the list is in the purchased state. */
    static boolean isActive(List<Purchase> purchases) {
        if (purchases == null) {
            return false;
        }
        for (Purchase purchase : purchases) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                return true;
            }
        }
        return false;
    }

    private Subscriptions() {}
}
