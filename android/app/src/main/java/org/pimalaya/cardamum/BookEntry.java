package org.pimalaya.cardamum;

import org.pimalaya.cardamum.client.Addressbook;

/**
 * An addressbook with the account it belongs to and its three switches:
 * subscribed (the book is on: its contacts are visible in the app),
 * remoteSynced (the sync exchanges it with the server) and phoneSynced
 * (it is projected into the phone's Contacts app as its own account).
 * Both sync switches require the subscription, so an off book neither
 * talks to the server nor shows in the Contacts app.
 */
final class BookEntry {
    final Addressbook book;
    final String accountEmail;
    final boolean subscribed;
    final boolean remoteSynced;
    final boolean phoneSynced;

    BookEntry(
            Addressbook book,
            String accountEmail,
            boolean subscribed,
            boolean remoteSynced,
            boolean phoneSynced) {
        this.book = book;
        this.accountEmail = accountEmail;
        this.subscribed = subscribed;
        this.remoteSynced = remoteSynced;
        this.phoneSynced = phoneSynced;
    }
}
