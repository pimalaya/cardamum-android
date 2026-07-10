package org.pimalaya.cardamum.billing;

/**
 * FOSS binding: no billing at all. The gate lets everything through, so
 * the free build (GitHub CI, F-Droid) is fully functional and carries no
 * Google code. Shared by the debug and release variants (see the
 * source-set wiring in build.gradle.kts).
 */
public final class BillingFactory {
    public static Billing create(android.content.Context context) {
        return host -> {
            // No paid tier in the FOSS build.
        };
    }

    private BillingFactory() {}
}
