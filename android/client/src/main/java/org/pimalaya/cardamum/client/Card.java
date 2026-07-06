package org.pimalaya.cardamum.client;

import java.util.Collections;
import java.util.List;

/** One vCard of an addressbook, as stored on the server. */
public final class Card {
    /** Display identifier (resource name with any .vcf stripped). */
    public final String id;

    /**
     * Resource name exactly as the server returned it, the addressing
     * key of updates and deletes (servers need not suffix .vcf); null
     * until the card has been fetched from the server.
     */
    public final String uri;

    /** Entity tag guarding concurrent updates, or null when unknown. */
    public final String etag;

    /** Raw vCard text. */
    public final String vcard;

    /**
     * Ids of the addressbooks the card is a member of, on the backends
     * whose cards are account-level with m:n memberships (JMAP
     * AddressBook ids, Google contact group ids); empty on the backends
     * whose cards live in the one collection they were listed from
     * (CardDAV, Graph).
     */
    public final List<String> books;

    public Card(String id, String uri, String etag, String vcard) {
        this(id, uri, etag, vcard, Collections.emptyList());
    }

    public Card(String id, String uri, String etag, String vcard, List<String> books) {
        this.id = id;
        this.uri = uri;
        this.etag = etag;
        this.vcard = vcard;
        this.books = books;
    }
}
