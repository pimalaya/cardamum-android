package org.pimalaya.cardamum.billing;

/**
 * FOSS binding: no billing at all. The support prompt and the paid tiers
 * live on Google Play only (docs/monetization.md), so the free build
 * (GitHub CI, F-Droid) never prompts and carries no Google code. Shared
 * by the debug and release variants (see the source-set wiring in
 * build.gradle.kts).
 */
public final class BillingFactory {
    public static Billing create(android.content.Context context) {
        return host -> {
            // No support prompt in the FOSS build.
        };
    }

    private BillingFactory() {}
}
