package org.pimalaya.cardamum;

import org.pimalaya.cardamum.client.Addressbook;

/**
 * An addressbook with the account it belongs to and whether the user is
 * subscribed to it. Subscription drives the home listing (and which
 * addressbooks sync); it is independent from projecting the addressbook
 * into the phone's Contacts app.
 */
final class BookEntry {
    final Addressbook book;
    final String accountEmail;
    final boolean subscribed;

    BookEntry(Addressbook book, String accountEmail, boolean subscribed) {
        this.book = book;
        this.accountEmail = accountEmail;
        this.subscribed = subscribed;
    }
}
