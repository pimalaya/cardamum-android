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
     * Fetches an authorization server's RFC 8414 metadata from its
     * issuer (the {@code oauth-authorization-server} well-known,
     * falling back to the OpenID Connect Discovery document). Returns
     * the metadata JSON (issuer, endpoints, {@code registration_endpoint}
     * when the server supports RFC 7591, grants, scopes).
     */
    static native String oauthServerMetadata(Transport transport, String issuer);

    /**
     * Registers a public client at an RFC 7591 registration endpoint
     * ({@code token_endpoint_auth_method: none}, the given loopback
     * redirect URI, code + refresh grants, client name and scope).
     * Returns {@code {"client_id": "..", "client_secret": ".." | null}}.
     */
    static native String oauthRegisterClient(
            Transport transport,
            String registrationEndpoint,
            String redirectUri,
            String clientName,
            String scope);

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
     * Indexes a vCard for the store (display name, first email and
     * phone, UID, normalized content hash); pure computation, no
     * transport. Returns {@code {name, email, phone, uid, hash}}.
     */
    static native String indexCard(String vcard);

    /**
     * Merges several vCards (a JSON string array) into one union
     * document with its field model and per-field alternatives; pure
     * computation, no transport. Returns
     * {@code {vcard, model, alternatives}}.
     */
    static native String mergeCards(String cards);

    /**
     * Three-way merges a conflicted push: the staged local edit and
     * the fetched remote card against their common base, the local
     * side winning same-field collisions; pure computation, no
     * transport. Returns {@code {vcard, conflicts}}.
     */
    static native String mergeCardChanges(String base, String local, String remote);

    /**
     * Rewrites the card's UID (a plain copy is a new identity); pure
     * computation, no transport. Returns {@code {"vcard": ".."}}.
     */
    static native String setCardUid(String vcard, String uid);

    /**
     * Finds groups of likely-duplicate cards (exact normalized email,
     * phone or name matches); pure computation, no transport. Takes a
     * JSON array of {@code {ref, vcard}} pairs, returns
     * {@code {"groups": [{"refs": [...], "reasons": [...]}]}}.
     */
    static native String findDuplicates(String cards);

    /**
     * Lists the card's raw property lines for the advanced editor;
     * pure computation, no transport. Returns
     * {@code {"props": ["VERSION:4.0", ...]}}.
     */
    static native String cardProps(String vcard);

    /**
     * Rewrites one raw property line for the advanced editor (a blank
     * line removes, index -1 appends); pure computation, no transport.
     * Returns {@code {"vcard": ".."}}.
     */
    static native String cardSetProp(String vcard, int index, String line);

    /**
     * Recomposes one property from its structured parts
     * ({@code {name, params: [{name, values}], value}}) and rewrites
     * it (index -1 appends); pure computation, no transport. Returns
     * {@code {"vcard": ".."}}.
     */
    static native String cardSetPropParts(String vcard, int index, String prop);

    /**
     * The component labels of a structured property name (N, ADR,
     * GENDER; empty for plain values); pure computation, no transport.
     * Returns {@code {"labels": [...]}}.
     */
    static native String cardPropLabels(String name);

    /**
     * Validates a hand-edited vCard source (it must reparse) and
     * returns it re-serialized; pure computation, no transport.
     * Returns {@code {"vcard": ".."}}.
     */
    static native String cardSource(String vcard);

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
     * Enumerates the addressbook's card spine (resource name plus
     * ETag, no body) as a JSON array of {@code {id, uri, etag}}.
     */
    static native String enumCards(
            Transport transport, String addressbookUrl, String login, String password);

    /**
     * Runs a sync-collection REPORT (RFC 6578) against the
     * addressbook, delta from the given sync token (empty for an
     * initial sync); returns {@code {"changed": [{id, uri, etag}],
     * "vanished": [uri], "token": ".."}}, or
     * {@code {"invalidToken": true}} when the server rejected the
     * token so the caller falls back to {@link #enumCards}.
     */
    static native String syncCards(
            Transport transport,
            String addressbookUrl,
            String login,
            String password,
            String syncToken);

    /**
     * Batch-fetches the cards at the given resource names (a JSON
     * string array) via REPORT addressbook-multiget; returns a JSON
     * array of {@code {id, uri, etag, vcard}}.
     */
    static native String multigetCards(
            Transport transport,
            String addressbookUrl,
            String login,
            String password,
            String uris);

    /**
     * Lists the ContactCard changes since the given state, the changed
     * cards fetched in full (empty state runs the initial round: every
     * card plus the state to delta from); returns {@code {"changed":
     * [{id, uri, etag, vcard, books}], "vanished": [id], "token":
     * ".."}}, or {@code {"invalidToken": true}} when the server can no
     * longer compute changes from the state.
     */
    static native String changesJmapCards(
            Transport transport,
            String sessionUrl,
            String login,
            String password,
            String state);

    /**
     * Lists the Graph contact changes since the given delta link, rows
     * carrying the id and changeKey only (empty link runs the initial
     * round: every contact plus the link to delta from); same shape as
     * {@link #changesJmapCards}, {@code invalidToken} when the server
     * expired the link.
     */
    static native String deltaGraphCards(
            Transport transport, String token, String folder, String deltaLink);

    /**
     * Lists the People contact changes since the given sync token,
     * deleted persons riding as vanished ids (empty token runs the
     * initial round: every contact plus the token to delta from); same
     * shape as {@link #changesJmapCards}, {@code invalidToken} when
     * the server expired the token.
     */
    static native String syncGoogleCards(Transport transport, String token, String syncToken);

    /**
     * Reconciles the collection with its remote through the io-offline
     * engine, servicing every engine yield via the driver; with
     * {@code full} the checkpoint is ignored and the whole remote is
     * enumerated. Returns the sync report {@code {pulled, pushed,
     * conflicts, rejected, refreshed}}.
     */
    static native String offlineSync(OfflineDriver driver, String collection, boolean full);

    /**
     * Raises the given handles (a JSON string array) to the full
     * detail tier through the io-offline engine, servicing every
     * engine yield via the driver. Returns the upgrade report
     * {@code {upgraded, fetched, deduped}}.
     */
    static native String offlineUpgrade(OfflineDriver driver, String collection, String handles);

    /**
     * Stages a local mutation (a JSON object, e.g. {@code {"op":
     * "edit", handle, hash, size, body, meta}}) through the io-offline
     * engine, servicing the storage yields via the driver; the remote
     * is never touched. Returns {@code {}}.
     */
    static native String offlineMutate(OfflineDriver driver, String collection, String mutation);

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
     * Lists the account's ContactCards across every JMAP AddressBook,
     * each converted to a vCard, as a JSON array of {@code {id, uri,
     * etag, vcard, books}} (ContactCard id as id and uri, a JSON hash
     * as etag, the m:n addressBookIds as books).
     */
    static native String listJmapCards(
            Transport transport, String sessionUrl, String login, String password);

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

    /**
     * Adds and removes AddressBook memberships of the ContactCard at
     * the given id, as JSON string arrays of book ids (one m:n
     * addressBookIds patch); returns {@code {}}.
     */
    static native String updateJmapCardBooks(
            Transport transport,
            String sessionUrl,
            String login,
            String password,
            String id,
            String add,
            String remove);

    /**
     * Lists the Google account's contact groups as addressbooks (the
     * myContacts system group first, as Contacts, then the user's own
     * groups). Returns a JSON array whose collection URLs are empty:
     * the caller composes them, since only it knows the account they
     * belong to.
     */
    static native String listGoogleAddressbooks(Transport transport, String token);

    /**
     * Lists the account's People contacts, each projected onto a vCard,
     * as a JSON array of {@code {id, uri, etag, vcard, books}} (person
     * id as id and uri, person etag as etag, the m:n contact group
     * memberships as books).
     */
    static native String listGoogleCards(Transport transport, String token);

    /**
     * Creates the vCard as a People contact; the server names the
     * resource, so the returned {@code {id, uri, etag, vcard}} carries
     * the server-assigned id.
     */
    static native String createGoogleCard(Transport transport, String token, String vcard);

    /**
     * Reads the People contact at the given id, projected onto a
     * vCard; returns {@code {id, uri, etag, vcard}}.
     */
    static native String readGoogleCard(Transport transport, String token, String id);

    /**
     * Updates the People contact at the given id from the vCard,
     * shrinking the update mask to the fields that differ from the base
     * vCard when one is passed (empty means unknown). People guards
     * updates with the person etag (empty fetches it first); returns
     * the updated {@code {id, uri, etag, vcard}}.
     */
    static native String updateGoogleCard(
            Transport transport,
            String token,
            String id,
            String vcard,
            String baseVcard,
            String etag);

    /** Deletes the People contact at the given id; returns {@code {}}. */
    static native String deleteGoogleCard(Transport transport, String token, String id);

    /**
     * Adds and removes the People contact at the given id from contact
     * groups, as JSON string arrays of group ids (one members:modify
     * call per group; adds only reach user groups, removing the last
     * group is rejected server-side); returns {@code {}}.
     */
    static native String updateGoogleCardBooks(
            Transport transport, String token, String id, String add, String remove);
}
