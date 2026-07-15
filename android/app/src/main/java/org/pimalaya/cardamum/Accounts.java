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

    /**
     * Creates one Android account per subscribed addressbook (named after
     * its own mail account) and removes any that are no longer subscribed.
     * The full subscribed set must be passed at once: accounts absent from
     * it are purged.
     */
    static void reconcile(Context context, List<BookEntry> books) {
        AccountManager manager = AccountManager.get(context);

        for (Account account : manager.getAccountsByType(TYPE)) {
            String url = manager.getUserData(account, DATA_URL);
            boolean wanted = false;
            for (BookEntry book : books) {
                wanted |= book.book.url.equals(url);
            }
            if (!wanted) {
                manager.removeAccountExplicitly(account);
            }
        }

        for (BookEntry book : books) {
            Account account = find(context, book.book);
            if (account == null) {
                account = new Account(name(book.accountEmail, book.book), TYPE);
                Bundle data = new Bundle();
                data.putString(DATA_URL, book.book.url);
                manager.addAccountExplicitly(account, null, data);

                // NOTE: only the default content-trigger state, seeded
                // from the book's background sync choice; a later user
                // choice in system settings is never reset by reconcile.
                ContentResolver.setSyncAutomatically(
                        account,
                        ContactsContract.AUTHORITY,
                        BackgroundSync.enabled(context, book.book.url));
            }

            // NOTE: contacts apps only list accounts syncable for the
            // contacts authority; self-heals accounts from earlier
            // builds without touching anything already set.
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
        return findByUrl(context, book.url);
    }

    /**
     * Removes the Android account backing the addressbook URL, when one
     * exists; ContactsProvider purges its raw contacts with it.
     */
    static void remove(Context context, String url) {
        Account account = findByUrl(context, url);
        if (account != null) {
            AccountManager.get(context).removeAccountExplicitly(account);
        }
    }

    /** The account backing the addressbook URL, or null before reconcile. */
    static Account findByUrl(Context context, String url) {
        AccountManager manager = AccountManager.get(context);
        for (Account account : manager.getAccountsByType(TYPE)) {
            if (url.equals(manager.getUserData(account, DATA_URL))) {
                return account;
            }
        }
        return null;
    }

    private static String name(String login, Addressbook book) {
        // NOTE: the local book has no login to qualify it; its own name
        // stands alone in the phone's Contacts app.
        if (LocalBook.is(login)) {
            return book.name;
        }
        return book.name + " (" + login + ")";
    }
}
