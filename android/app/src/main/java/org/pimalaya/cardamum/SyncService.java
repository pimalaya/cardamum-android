package org.pimalaya.cardamum;

import android.accounts.Account;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import org.pimalaya.cardamum.client.CardamumClient;

/**
 * Contacts sync adapter running the phone spoke's engine pass through
 * Android's sync scheduler: the book's raw contacts reconcile two-way
 * with the store, so a contacts-app edit converges into the hub
 * without opening Cardamum (the next remote sync pushes it upstream).
 * Registering it also associates Cardamum's account type with the
 * contacts authority, and its CONTACTS_STRUCTURE meta-data is what
 * makes contacts apps list the accounts and allow editing their raw
 * contacts. Serves only the syncs the OS schedules on its own: the
 * per-account "sync now" of the system settings and the upload syncs
 * Android requests after edits on our raw contacts; the in-app actions
 * run the same engine pass directly, behind their own spinner.
 * Automatic periodic sync stays off.
 */
public class SyncService extends Service {
    private static final Object LOCK = new Object();
    private static Adapter adapter;

    @Override
    public void onCreate() {
        synchronized (LOCK) {
            if (adapter == null) {
                adapter = new Adapter(getApplicationContext());
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return adapter.getSyncAdapterBinder();
    }

    private static final class Adapter extends AbstractThreadedSyncAdapter {
        Adapter(Context context) {
            super(context, true);
        }

        @Override
        public void onPerformSync(
                Account account,
                Bundle extras,
                String authority,
                ContentProviderClient provider,
                SyncResult result) {
            Context context = getContext();

            String url = Accounts.url(context, account);
            Log.w("cardamum", "phone sync for " + account.name + ", url " + url);
            if (url == null) {
                return;
            }

            try {
                CardStore store = new CardStore(context);
                OfflineEngine engine =
                        new OfflineEngine(store, new CardamumClient(), null, context);
                OfflineEngine.Report report = new OfflineEngine.Report();
                engine.syncPhone(url, report);
                Log.w(
                        "cardamum",
                        "phone sync done: " + report.pulled + " pulled, " + report.pushed
                                + " pushed, " + report.merged + " merged");
            } catch (Exception error) {
                Log.w("cardamum", "phone sync failed for " + account.name + ": " + error);
                result.stats.numIoExceptions++;
            }
        }
    }
}
