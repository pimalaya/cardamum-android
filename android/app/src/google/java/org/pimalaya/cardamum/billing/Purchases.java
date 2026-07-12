package org.pimalaya.cardamum.billing;

import com.android.billingclient.api.Purchase;
import java.util.List;

/** Shared helpers over a Google Play one-time purchase list. */
final class Purchases {
    /** Whether any purchase in the list is in the purchased state. */
    static boolean isPaid(List<Purchase> purchases) {
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

    private Purchases() {}
}
