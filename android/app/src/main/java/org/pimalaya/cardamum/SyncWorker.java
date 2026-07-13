package org.pimalaya.cardamum;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.ArrayList;
import java.util.List;
import org.pimalaya.cardamum.client.Account;
import org.pimalaya.cardamum.client.CardamumClient;
import org.pimalaya.cardamum.client.OauthTokens;

/**
 * One addressbook's background pass, scheduled by BackgroundSync at the
 * user's interval: the same three-spoke hub sync as the in-app Sync
 * (phone, server, phone), headless. A pass that did something posts a
 * notification shaped like the in-app sync toast (pulled, pushed,
 * merged); a pass with nothing to report posts nothing. Contacts in a
 * both-sides-edited conflict sit the pass out (the engine parks them
 * untouched, per item, until the user resolves them in the app) while
 * everything else keeps syncing, and pending conflicts ride the
 * notification as a warning subtitle asking to resolve them in the
 * app. An expired OAuth access token refreshes once, like the in-app
 * sync.
 */
public class SyncWorker extends Worker {
    /** Input: the addressbook collection URL to sync. */
    static final String INPUT_URL = "url";

    /**
     * How many times a failing pass retries (with WorkManager's
     * exponential backoff) before waiting for the next period instead.
     */
    private static final int MAX_RUN_ATTEMPTS = 3;

    private static final String CHANNEL_SYNC = "sync";
    private static final int NOTIFICATION_SYNC = 1;

    public SyncWorker(Context context, WorkerParameters parameters) {
        super(context, parameters);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String url = getInputData().getString(INPUT_URL);
        if (url == null) {
            return Result.failure();
        }

        CardStore base = new CardStore(context);

        // Self-heal: when the store no longer wants this book synced
        // (deleted with its account, unsubscribed, or set back to
        // never), the orphaned periodic work cancels itself.
        BookEntry book = null;
        for (BookEntry entry : base.loadAllAddressbooks()) {
            if (entry.book.url.equals(url)) {
                book = entry;
            }
        }
        if (book == null || !book.subscribed || !BackgroundSync.enabled(context, url)) {
            Log.w("cardamum", "background sync obsolete for " + url + ", cancelling");
            WorkManager.getInstance(context).cancelUniqueWork(BackgroundSync.workName(url));
            return Result.success();
        }

        try {
            OfflineEngine.Report report = sync(context, base, book);
            Log.w(
                    "cardamum",
                    "background sync done for " + url + ": remote " + report.remoteIn.size()
                            + " in, " + report.remoteOut.size() + " out, "
                            + report.remoteChanged.size() + " changed, " + report.conflicts
                            + " conflicted; local " + report.localIn.size() + " in, "
                            + report.localOut.size() + " out, " + report.localChanged.size()
                            + " changed");

            // Anything to report gets the notification; pending
            // conflicts count as reportable on every pass (the engine
            // re-counts them each time), so the warning re-raises
            // until the user resolves them in the app.
            boolean activity =
                    !report.remoteIn.isEmpty()
                            || !report.remoteOut.isEmpty()
                            || !report.remoteChanged.isEmpty()
                            || !report.localIn.isEmpty()
                            || !report.localOut.isEmpty()
                            || !report.localChanged.isEmpty();
            if (activity || report.conflicts > 0) {
                notifyReport(context, book, report);
            }

            return Result.success();
        } catch (Exception error) {
            Log.w("cardamum", "background sync failed for " + url + ": " + error);
            // Transient failures (no route, server down) retry with
            // backoff a few times, then wait for the next period.
            return getRunAttemptCount() < MAX_RUN_ATTEMPTS ? Result.retry() : Result.success();
        }
    }

    /**
     * One book's engine pass. The local book has no server, so its pass
     * is the phone spoke alone; a server book runs the full three-spoke
     * pass with its account's stored credentials.
     */
    private OfflineEngine.Report sync(Context context, CardStore base, BookEntry book)
            throws Exception {
        CardamumClient client = new CardamumClient();
        String url = book.book.url;

        if (LocalBook.is(book.accountEmail)) {
            OfflineEngine.Report report = new OfflineEngine.Report();
            new OfflineEngine(base, client, null, context).syncPhone(url, report);
            return report;
        }

        SecureStore store = new SecureStore(context);
        AccountEntry entry = null;
        for (AccountEntry candidate : store.loadAll()) {
            if (candidate.email.equals(book.accountEmail)) {
                entry = candidate;
            }
        }
        if (entry == null) {
            Log.w("cardamum", "no stored account for " + book.accountEmail + ", skipping");
            return new OfflineEngine.Report();
        }

        try {
            return new OfflineEngine(base, client, entry.account, context).syncBook(url);
        } catch (Exception error) {
            // An expired OAuth access token: refresh it, persist the
            // rotated credentials, and retry the book once (the in-app
            // sync does the same).
            if (!expiredToken(error) || entry.refreshToken == null) {
                throw error;
            }
            return new OfflineEngine(base, client, refresh(client, store, entry), context)
                    .syncBook(url);
        }
    }

    /**
     * Refreshes an OAuth account's access token and re-persists the
     * account, returning the fresh credentials. Providers may rotate
     * the refresh token, so a reissued one replaces the stored one.
     */
    private static Account refresh(CardamumClient client, SecureStore store, AccountEntry entry) {
        OauthTokens tokens =
                client.oauthRefresh(
                        entry.tokenEndpoint,
                        entry.clientId,
                        entry.clientSecret,
                        entry.refreshToken,
                        null);

        String refreshToken =
                tokens.refreshToken != null ? tokens.refreshToken : entry.refreshToken;
        Account fresh = new Account(entry.account.baseUrl, "", tokens.accessToken);
        store.add(
                new AccountEntry(
                        fresh,
                        entry.email,
                        refreshToken,
                        entry.tokenEndpoint,
                        entry.clientId,
                        entry.clientSecret));
        return fresh;
    }

    /** True for an HTTP 401 from either backend (expired or revoked token). */
    private static boolean expiredToken(Exception error) {
        String message = error.getMessage();
        return message != null && message.contains("HTTP 401");
    }

    /**
     * The sync-report notification: the account email as the title (the
     * addressbook name joins it expanded, where there is room), one
     * line per axis as the text (Local against the phone's Contacts app
     * when the book mirrors there, Remote for the server), each
     * counting the cards in, out and changed. When conflicts pend, the
     * warning subtitle asks to resolve them in the app and the diverged
     * glyph replaces the sync one. Tagged by book URL so each book
     * carries one, replaced in place by the next report. On Android 13+
     * it shows only when the notifications permission was granted
     * (asked when background sync gets enabled); without it the pass
     * just runs silently.
     */
    private static void notifyReport(
            Context context, BookEntry book, OfflineEngine.Report report) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);

        manager.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL_SYNC,
                        context.getString(R.string.notif_channel_sync),
                        NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_SYNC);

        PendingIntent open =
                PendingIntent.getActivity(
                        context,
                        0,
                        new Intent(context, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean localBook = LocalBook.is(book.accountEmail);
        String owner =
                localBook ? context.getString(R.string.local_account) : book.accountEmail;

        List<String> lines = new ArrayList<>(2);
        if (book.phoneSynced || localBook) {
            lines.add(
                    context.getString(
                            R.string.sync_line_local,
                            report.localIn.size(),
                            report.localOut.size(),
                            report.localChanged.size()));
        }
        if (!localBook) {
            lines.add(
                    context.getString(
                            R.string.sync_line_remote,
                            report.remoteIn.size(),
                            report.remoteOut.size(),
                            report.remoteChanged.size()));
        }

        boolean conflicts = report.conflicts > 0;
        builder.setSmallIcon(conflicts ? R.drawable.ic_diverged : R.drawable.ic_sync)
                .setContentTitle(owner)
                .setContentText(lines.isEmpty() ? "" : lines.get(lines.size() - 1))
                .setStyle(
                        new Notification.BigTextStyle()
                                .setBigContentTitle(owner + " · " + book.book.name)
                                .bigText(TextUtils.join("\n", lines)))
                .setContentIntent(open)
                .setAutoCancel(true);
        if (conflicts) {
            builder.setSubText(context.getString(R.string.notif_conflicts_sub));
        }

        manager.notify(book.book.url, NOTIFICATION_SYNC, builder.build());
    }
}
