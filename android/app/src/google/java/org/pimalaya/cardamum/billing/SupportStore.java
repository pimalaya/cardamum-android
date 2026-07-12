package org.pimalaya.cardamum.billing;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The support-prompt state of the Google build: whether any tier was
 * ever paid (silences the prompt for good) and when the panel last
 * showed (the rolling 3-day cap of docs/monetization.md). The paid flag
 * is a cache of the last definitive Play purchases answer, refreshed by
 * SupportActivity each time the panel opens, so a payment made on
 * another device or a reinstall is honoured without any startup wait.
 */
final class SupportStore {
    // The rolling cap between two showings of the support panel:
    // roughly twice a week, self-normalizing across light and heavy
    // users (interruptions per month, not per launch).
    private static final long PROMPT_WINDOW_MILLIS = 3L * 24 * 60 * 60 * 1000;

    private static final String PREFS = "billing";
    private static final String KEY_PAID = "paid";
    private static final String KEY_LAST_PROMPT = "last_prompt";

    private final SharedPreferences prefs;

    SupportStore(Context context) {
        this.prefs =
                context.getApplicationContext()
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether any tier was paid at the last definitive Play answer. */
    boolean paid() {
        return prefs.getBoolean(KEY_PAID, false);
    }

    /**
     * Whether the panel is due: nothing paid and the last showing is a
     * window or more ago. The very first check seeds the window instead
     * of firing, so a fresh install gets its first ask 3 days in, after
     * the app proved useful, not over the onboarding.
     */
    boolean due() {
        if (paid()) {
            return false;
        }
        long last = prefs.getLong(KEY_LAST_PROMPT, 0);
        if (last == 0) {
            markShown();
            return false;
        }
        long age = System.currentTimeMillis() - last;
        return age < 0 || age >= PROMPT_WINDOW_MILLIS;
    }

    /** Records a definitive Play purchases answer. */
    void record(boolean paid) {
        prefs.edit().putBoolean(KEY_PAID, paid).apply();
    }

    /** Stamps a showing of the panel, opening the next 3-day window. */
    void markShown() {
        prefs.edit().putLong(KEY_LAST_PROMPT, System.currentTimeMillis()).apply();
    }
}
