package org.pimalaya.cardamum.client;

/**
 * The libcardamum.so boundary. Every method blocks, drives the given
 * {@link Transport} for all socket I/O, and returns JSON: the documented
 * success shape, or {@code {"error": ".."}}. ETag parameters are plain
 * strings where empty means unknown (no If-Match guard). On every
 * CardDAV method, an empty login means the password parameter carries
 * an OAuth 2.0 access token, sent as Bearer instead of Basic.
 */
final class Native {
    static {
        System.loadLibrary("cardamum");
    }

    private Native() {}

    /**
     * RFC 6764 discovery: email to CardDAV context root, as
     * {@code {"url": ".."}}. The resolver is a {@code tcp://host:port}
     * DNS server or an RFC 8484 {@code https://…/dns-query} URL; empty
     * or null falls back to a public DNS-over-HTTPS one, which works on
     * mobile networks that block outbound DNS over TCP.
     */
    static native String discover(Transport transport, String email, String resolver);

    /**
     * Unified search: email to CardDAV service configs (endpoint,
     * username, authentication methods, source mechanism), collected
     * from every mechanism: fixed provider rules, PACC, RFC 6764
     * resolve. Returns a JSON array. Resolver as in {@link #discover}.
     */
    static native String searchAll(Transport transport, String email, String resolver);

    /**
     * Same mechanism chain as {@link #searchAll}, stopping at the
     * first mechanism yielding a config; empty array when none does.
     */
    static native String searchFirst(Transport transport, String email, String resolver);

    /**
     * Builds the OAuth 2.0 authorization URL with PKCE (S256) and CSRF
     * state; pure computation, no transport. {@code extras} is a JSON
     * object of provider-specific query parameters (empty for none).
     * Returns {@code {"url": ".."}}.
     */
    static native String oauthAuthorizeUrl(
            String authorizationEndpoint,
            String clientId,
            String redirectUri,
            String scope,
            String state,
            String pkceVerifier,
            String extras);

    /**
     * Validates the authorization redirect against the expected CSRF
     * state and extracts the authorization code; pure computation, no
     * transport. Returns {@code {"code": ".."}}.
     */
    static native String oauthValidateRedirect(String redirectUrl, String state);

    /**
     * Exchanges an authorization code for tokens against the token
     * endpoint, with the PKCE verifier of the authorization request
     * and the client secret when the registration issued one (empty
     * means none). Returns the RFC 6749 success params as JSON
     * ({@code access_token}, {@code token_type}, {@code expires_in},
     * {@code refresh_token}, {@code scope}, {@code issued_at}).
     */
    static native String oauthRequestAccessToken(
            Transport transport,
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String code,
            String redirectUri,
            String pkceVerifier);

    /**
     * Refreshes tokens against the token endpoint; {@code scope} is
     * the space-separated scope list of the original grant (empty
     * keeps the server default) and the client secret rides along when
     * the registration issued one (empty means none). Same JSON shape
     * as {@link #oauthRequestAccessToken}.
     */
    static native String oauthRefreshAccessToken(
            Transport transport,
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String refreshToken,
            String scope);

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

    /**
     * Lists the Microsoft Graph contact folders (default Contacts
     * folder first, empty id) as a JSON array of addressbooks whose
     * collection URLs are empty: the caller composes them, since only
     * it knows the account they belong to.
     */
    static native String listGraphAddressbooks(Transport transport, String token);

    /**
     * Lists the contacts of the Graph folder (empty id means the
     * default Contacts folder), each projected onto a vCard, as a JSON
     * array of {@code {id, uri, etag, vcard}} (Graph id as id and uri,
     * changeKey as etag).
     */
    static native String listGraphCards(Transport transport, String token, String folder);

    /**
     * Creates the vCard as a Graph contact in the folder (empty id
     * means the default Contacts folder); Graph names the resource, so
     * the returned {@code {id, uri, etag, vcard}} carries the
     * server-assigned id.
     */
    static native String createGraphCard(
            Transport transport, String token, String folder, String vcard);

    /**
     * Reads the Graph contact at the given id, projected onto a vCard;
     * returns {@code {id, uri, etag, vcard}}.
     */
    static native String readGraphCard(Transport transport, String token, String id);

    /**
     * Updates the Graph contact at the given id from the vCard,
     * PATCHing only the fields that differ from the base vCard when
     * one is passed (empty means unknown; no If-Match guard); returns
     * the updated {@code {id, uri, etag, vcard}}.
     */
    static native String updateGraphCard(
            Transport transport, String token, String id, String vcard, String baseVcard);

    /** Deletes the Graph contact at the given id; returns {@code {}}. */
    static native String deleteGraphCard(Transport transport, String token, String id);

    /**
     * Lists the JMAP AddressBooks (RFC 9610) of the account behind the
     * session URL as a JSON array of addressbooks whose collection URLs
     * are empty: the caller composes them, since only it knows the
     * account they belong to.
     */
    static native String listJmapAddressbooks(
            Transport transport, String sessionUrl, String login, String password);

    /**
     * Lists the ContactCards of the JMAP AddressBook, each converted
     * to a vCard, as a JSON array of {@code {id, uri, etag, vcard}}
     * (ContactCard id as id and uri, a JSON hash as etag).
     */
    static native String listJmapCards(
            Transport transport, String sessionUrl, String login, String password, String book);

    /**
     * Creates the vCard as a ContactCard in the JMAP AddressBook; the
     * server names the card, so the returned {@code {id, uri, etag,
     * vcard}} carries the server-assigned id.
     */
    static native String createJmapCard(
            Transport transport,
            String sessionUrl,
            String login,
            String password,
            String book,
            String vcard);

    /**
     * Reads the ContactCard at the given id, converted to a vCard;
     * returns {@code {id, uri, etag, vcard}}.
     */
    static native String readJmapCard(
            Transport transport, String sessionUrl, String login, String password, String id);

    /**
     * Updates the ContactCard at the given id from the vCard, patching
     * only the properties that differ from the base vCard when one is
     * passed (empty means unknown; no If-Match guard); returns the
     * updated {@code {id, uri, etag, vcard}}.
     */
    static native String updateJmapCard(
            Transport transport,
            String sessionUrl,
            String login,
            String password,
            String id,
            String vcard,
            String baseVcard);

    /** Destroys the ContactCard at the given id; returns {@code {}}. */
    static native String deleteJmapCard(
            Transport transport, String sessionUrl, String login, String password, String id);
}
