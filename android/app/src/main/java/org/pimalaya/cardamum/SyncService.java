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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Card;
import org.pimalaya.cardamum.client.CardamumClient;

/**
 * Contacts sync adapter running the store-to-phone projection through
 * Android's sync scheduler. Registering it also associates Cardamum's
 * account type with the contacts authority, and its CONTACTS_STRUCTURE
 * meta-data is what makes contacts apps list the accounts and allow
 * editing their raw contacts. Triggered by the in-app "Sync local"
 * action and by the system's per-account "sync now"; automatic sync
 * stays off. The store-to-remote spoke stays in-app; it hooks in here
 * once the io-offline engine lands.
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
            Log.w("cardamum", "local sync for " + account.name + ", url " + url);
            if (url == null) {
                return;
            }

            try {
                List<Card> cards = new CardStore(context).loadCards(url);
                Log.w("cardamum", "projecting " + cards.size() + " cards");

                CardamumClient client = new CardamumClient();
                Map<String, JSONObject> models = new HashMap<>();
                for (Card card : cards) {
                    models.put(card.id, client.projectCard(card));
                }

                Projector.project(context.getContentResolver(), account, cards, models);
            } catch (Exception error) {
                Log.w("cardamum", "local sync failed for " + account.name + ": " + error);
                result.stats.numIoExceptions++;
            }
        }
    }
}
