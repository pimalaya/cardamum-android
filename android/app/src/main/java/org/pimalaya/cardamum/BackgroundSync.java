package org.pimalaya.cardamum;

import android.accounts.Account;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.ContactsContract;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Per-addressbook background synchronization: the user picks an
 * interval (never by default) and a WorkManager periodic worker runs
 * the same hub pass as the in-app Sync at that pace (SyncWorker). The
 * interval lives in plain preferences keyed by the book URL (it is a
 * scheduling choice, not sync state, so no store schema is involved),
 * and all scheduling goes through {@link #reconcile}, the one place
 * work is enqueued or cancelled, so app start and every setting change
 * converge onto the same state.
 */
final class BackgroundSync {
    /**
     * The selectable intervals in minutes, aligned with the
     * background_sync_intervals labels; 0 is never (the default).
     * WorkManager floors periodic work at 15 minutes.
     */
    static final long[] INTERVAL_MINUTES = {0, 15, 30, 60, 240, 720, 1440};

    private static final String PREFS = "cardamum.background";
    private static final String WORK_PREFIX = "background-sync ";

    private BackgroundSync() {}

    /** The book's sync interval in minutes; 0 means never. */
    static long interval(Context context, String url) {
        return prefs(context).getLong(url, 0);
    }

    /** The interval's position in {@link #INTERVAL_MINUTES}; 0 when unknown. */
    static int intervalIndex(Context context, String url) {
        long minutes = interval(context, url);
        for (int index = 0; index < INTERVAL_MINUTES.length; index++) {
            if (INTERVAL_MINUTES[index] == minutes) {
                return index;
            }
        }
        return 0;
    }

    /** Whether the book synchronizes in background at all. */
    static boolean enabled(Context context, String url) {
        return interval(context, url) > 0;
    }

    /**
     * Persists the book's interval and mirrors it onto the content
     * trigger of its Android account when one exists: a
     * background-synced book uploads contacts-app edits into the hub as
     * they happen (through SyncService), and the next periodic pass
     * sends them upstream. Scheduling itself is {@link #reconcile}'s.
     */
    static void setInterval(Context context, String url, long minutes) {
        prefs(context).edit().putLong(url, minutes).apply();

        Account account = Accounts.findByUrl(context, url);
        if (account != null) {
            ContentResolver.setSyncAutomatically(
                    account, ContactsContract.AUTHORITY, minutes > 0);
        }
    }

    /**
     * Converges the periodic workers onto the given books: one unique
     * periodic work per subscribed book with an interval, cancelled
     * otherwise. A book dropped from the store entirely is not listed
     * here; its orphaned worker cancels itself on its next run.
     */
    static void reconcile(Context context, List<BookEntry> books) {
        WorkManager manager = WorkManager.getInstance(context);

        for (BookEntry entry : books) {
            String url = entry.book.url;
            long minutes = interval(context, url);
            if (!entry.subscribed || minutes == 0) {
                manager.cancelUniqueWork(workName(url));
                continue;
            }

            Data input = new Data.Builder().putString(SyncWorker.INPUT_URL, url).build();
            Constraints constraints =
                    new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build();

            // NOTE: UPDATE keeps the running period's start, so a
            // reconcile on every launch never delays the next run.
            manager.enqueueUniquePeriodicWork(
                    workName(url),
                    ExistingPeriodicWorkPolicy.UPDATE,
                    new PeriodicWorkRequest.Builder(
                                    SyncWorker.class, minutes, TimeUnit.MINUTES)
                            .setInputData(input)
                            .setConstraints(constraints)
                            .build());
        }
    }

    /** The unique periodic work name of one book's background sync. */
    static String workName(String url) {
        return WORK_PREFIX + url;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
