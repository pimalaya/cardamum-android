package org.pimalaya.cardamum.client;

/**
 * The libcardamum.so boundary. Every method blocks, drives the given
 * {@link Transport} for all socket I/O, and returns JSON: the documented
 * success shape, or {@code {"error": ".."}}. ETag parameters are plain
 * strings where empty means unknown (no If-Match guard).
 */
final class Native {
    static {
        System.loadLibrary("cardamum");
    }

    private Native() {}

    /**
     * RFC 6764 discovery: email to CardDAV context root, as
     * {@code {"url": ".."}}. The resolver is a {@code tcp://host:port}
     * DNS server (the device's own when known); empty or null falls
     * back to a public one.
     */
    static native String discover(Transport transport, String email, String resolver);

    /**
     * Walks current-user-principal to addressbook-home-set to list from
     * the discovered context root; returns a JSON array of addressbooks.
     */
    static native String listAddressbooks(
            Transport transport, String baseUrl, String login, String password);

    /** Lists the addressbook's cards as a JSON array of {@code {id, uri, etag, vcard}}. */
    static native String listCards(
            Transport transport, String addressbookUrl, String login, String password);

    /** Creates a card; returns {@code {"etag": ".." | null}}. */
    static native String createCard(
            Transport transport,
            String addressbookUrl,
            String login,
            String password,
            String id,
            String vcard);

    /**
     * Reads the card at the given resource name (as the server
     * returned it); returns {@code {id, uri, etag, vcard}}.
     */
    static native String readCard(
            Transport transport,
            String addressbookUrl,
            String login,
            String password,
            String uri);

    /**
     * Updates the card at the given resource name (as the server
     * returned it), guarded by the ETag; returns
     * {@code {"etag": ".." | null}}.
     */
    static native String updateCard(
            Transport transport,
            String addressbookUrl,
            String login,
            String password,
            String uri,
            String vcard,
            String etag);

    /**
     * Projects a vCard onto the neutral field model the app maps to
     * ContactsContract rows; pure computation, no transport.
     */
    static native String projectCard(String vcard);

    /**
     * Patches an edited field model back onto the vCard, preserving
     * every unmanaged property; pure computation, no transport. Returns
     * {@code {"vcard": ".."}}.
     */
    static native String applyCard(String vcard, String model);

    /**
     * Deletes the card at the given resource name (as the server
     * returned it), guarded by the ETag; returns {@code {}}.
     */
    static native String deleteCard(
            Transport transport,
            String addressbookUrl,
            String login,
            String password,
            String uri,
            String etag);
}
