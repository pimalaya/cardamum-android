package org.pimalaya.cardamum;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.ContactsContract;
import java.util.List;
import org.pimalaya.cardamum.client.Addressbook;

/**
 * AccountManager plumbing: one Android account per synced addressbook
 * (Android has accounts, not addressbooks), all of Cardamum's own type.
 * The addressbook collection URL lives in the account's user data and is
 * the matching key; the visible name is the addressbook name plus the
 * login. Removing an account makes ContactsProvider purge its raw
 * contacts, so deselecting an addressbook cleans the phone up by itself.
 */
final class Accounts {
    /** Matches android:accountType in res/xml/authenticator.xml. */
    static final String TYPE = "org.pimalaya.cardamum";

    private static final String DATA_URL = "url";

    private Accounts() {}

    /** Creates accounts for the selected addressbooks and removes stale ones. */
    static void reconcile(Context context, String login, List<Addressbook> books) {
        AccountManager manager = AccountManager.get(context);

        for (Account account : manager.getAccountsByType(TYPE)) {
            String url = manager.getUserData(account, DATA_URL);
            boolean wanted = false;
            for (Addressbook book : books) {
                wanted |= book.url.equals(url);
            }
            if (!wanted) {
                manager.removeAccountExplicitly(account);
            }
        }

        for (Addressbook book : books) {
            Account account = find(context, book);
            if (account == null) {
                account = new Account(name(login, book), TYPE);
                Bundle data = new Bundle();
                data.putString(DATA_URL, book.url);
                manager.addAccountExplicitly(account, null, data);

                // Automatic sync starts off; this is only the default:
                // the user's later choice in the system settings is
                // theirs and is never reset by a reconcile.
                ContentResolver.setSyncAutomatically(
                        account, ContactsContract.AUTHORITY, false);
            }

            // Contacts apps only list accounts that are syncable for
            // the contacts authority. Self-heals accounts created by
            // earlier builds, without touching anything already set.
            if (ContentResolver.getIsSyncable(account, ContactsContract.AUTHORITY) <= 0) {
                ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1);
            }
        }
    }

    /** The addressbook collection URL backing the account, or null. */
    static String url(Context context, Account account) {
        return AccountManager.get(context).getUserData(account, DATA_URL);
    }

    /** The account backing the addressbook, or null before reconcile. */
    static Account find(Context context, Addressbook book) {
        AccountManager manager = AccountManager.get(context);
        for (Account account : manager.getAccountsByType(TYPE)) {
            if (book.url.equals(manager.getUserData(account, DATA_URL))) {
                return account;
            }
        }
        return null;
    }

    private static String name(String login, Addressbook book) {
        return book.name + " (" + login + ")";
    }
}
