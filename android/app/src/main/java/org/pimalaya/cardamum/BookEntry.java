package org.pimalaya.cardamum;

import org.pimalaya.cardamum.client.Addressbook;

/**
 * An addressbook with the account it belongs to and its two independent
 * switches: subscribed (its contacts are visible and take part in sync)
 * and phoneSynced (it is projected into the phone's Contacts app as its
 * own account). Phone sync requires the subscription, so a book shows up
 * in the Contacts app only when both are on.
 */
final class BookEntry {
    final Addressbook book;
    final String accountEmail;
    final boolean subscribed;
    final boolean phoneSynced;

    BookEntry(Addressbook book, String accountEmail, boolean subscribed, boolean phoneSynced) {
        this.book = book;
        this.accountEmail = accountEmail;
        this.subscribed = subscribed;
        this.phoneSynced = phoneSynced;
    }
}
