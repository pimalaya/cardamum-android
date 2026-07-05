package org.pimalaya.cardamum.client;

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

    public Card(String id, String uri, String etag, String vcard) {
        this.id = id;
        this.uri = uri;
        this.etag = etag;
        this.vcard = vcard;
    }
}
