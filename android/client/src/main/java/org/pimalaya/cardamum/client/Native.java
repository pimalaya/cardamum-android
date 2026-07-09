package org.pimalaya.cardamum.client;

/**
 * The libcardamum.so boundary. Every method blocks, drives the given
 * {@link Transport} for all socket I/O, and returns JSON: the documented
 * success shape, or {@code {"error": ".."}}. ETag parameters are plain
 * strings where empty means unknown (no If-Match guard). Every account
 * operation takes the account's base URL and dispatches on the backend
 * behind it in Rust; the base URL is opaque to Java. An empty login
 * means the password parameter carries an OAuth 2.0 access token, sent
 * as Bearer instead of Basic.
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
     * Fixed provider rules: email to the provider's service configs,
     * matched by MX records (any domain whose mail lives at Google or
     * Microsoft, gmail.com and outlook.com included). Returns a JSON
     * array, empty when no rule matched. Resolver as in
     * {@link #discover}.
     */
    static native String searchProvider(Transport transport, String email, String resolver);

    /**
     * PACC discovery: the email domain's PACC document flattened into
     * service configs. Returns a JSON array. Resolver as in
     * {@link #discover}.
     */
    static native String searchPacc(Transport transport, String email, String resolver);

    /**
     * RFC 6764 resolve: the email domain's CardDAV context root as a
     * service config. Returns a JSON array. Resolver as in
     * {@link #discover}.
     */
    static native String searchCarddav(Transport transport, String email, String resolver);

    /**
     * RFC 8620 resolve: the email domain's JMAP session URL as a
     * service config. Returns a JSON array. Resolver as in
     * {@link #discover}.
     */
    static native String searchJmap(Transport transport, String email, String resolver);

    /**
     * Pure merge of per-mechanism config lists (a JSON array of
     * arrays, in mechanism-priority order) into one deduplicated list
     * restricted to the services the app drives (CardDAV, JMAP); no
     * transport. Returns a JSON array.
     */
    static native String searchMerge(String lists);

    /**
     * Probes one config's endpoints for the authentication schemes
     * they advertise on their unauthenticated 401 and refines the
     * config's password and bearer methods. Takes and returns one
     * config as JSON.
     */
    static native String searchProbe(Transport transport, String config);

    /**
     * Generates one authorization session's CSRF state and PKCE
     * verifier; pure computation, no transport. Returns
     * {@code {"state": "..", "verifier": ".."}}.
     */
    static native String oauthSessionParams();

    /**
     * The scope a contacts client should request of an authorization
     * server, negotiated against its space-separated advertised scopes
     * (empty for none); pure computation, no transport. Returns
     * {@code {"scope": ".."}}.
     */
    static native String oauthContactsScope(String scopesSupported);

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
     * The backend behind an account base URL ({@code carddav},
     * {@code graph}, {@code jmap} or {@code google}) and whether its
     * cards are account-level resources with m:n addressbook
     * memberships; pure computation, no transport. Returns
     * {@code {"backend": "..", "accountLevel": bool}}.
     */
    static native String accountInfo(String baseUrl);

    /**
     * Builds an account base URL of the given kind
     * ({@code googleCarddav}, {@code google}, {@code msgraph} from an
     * email, {@code jmap} from an HTTPS session URL or bare host);
     * pure computation, no transport. Returns {@code {"url": ".."}}.
     */
    static native String accountBase(String kind, String value);

    /**
     * Lists the account's addressbooks, the backend dispatched from
     * the base URL. Returns a JSON array of addressbooks carrying
     * absolute collection URLs.
     */
    static native String listAddressbooks(
            Transport transport, String baseUrl, String login, String password);

    /**
     * Lists every card of an account-level backend (JMAP, Google) in
     * one pass, each carrying its addressbook memberships as book ids.
     * Returns a JSON array of {@code {id, uri, etag, vcard, books}}.
     */
    static native String listAccountCards(
            Transport transport, String baseUrl, String login, String password);

    /**
     * Lists the cards of the addressbook collection (CardDAV, Graph),
     * the backend dispatched from the base URL. Returns a JSON array
     * of {@code {id, uri, etag, vcard}}.
     */
    static native String listCards(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password);

    /**
     * Creates the card in the addressbook collection, the backend
     * dispatched from the base URL. Returns the created
     * {@code {id, uri, etag, vcard}} (the server-assigned id on the
     * backends naming the resource themselves).
     */
    static native String createCard(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String id,
            String vcard);

    /**
     * Reads the card at the given resource name (as the server
     * returned it), the backend dispatched from the base URL; returns
     * {@code {id, uri, etag, vcard}}.
     */
    static native String readCard(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String uri);

    /**
     * Updates the card at the given resource name (empty falls back to
     * the id), the backend dispatched from the base URL. The base
     * vCard trims the patching backends' updates to the fields the
     * edit changed and the ETag guards the guarding backends' writes;
     * returns the updated {@code {id, uri, etag, vcard}}.
     */
    static native String updateCard(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String id,
            String uri,
            String vcard,
            String baseVcard,
            String etag);

    /**
     * Deletes the card at the given resource name (empty falls back to
     * the id), the backend dispatched from the base URL and the ETag
     * guarding the guarding backends' deletion; returns {@code {}}.
     */
    static native String deleteCard(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String id,
            String uri,
            String etag);

    /**
     * Enumerates the addressbook's card spine (resource name plus
     * ETag, no body) as a JSON array of {@code {id, uri, etag}};
     * CardDAV only, the incremental primitive behind
     * {@link #syncCards}'s fallback.
     */
    static native String enumCards(
            Transport transport, String addressbookUrl, String login, String password);

    /**
     * Lists the collection's changes since the given cursor (empty
     * runs the initial round: the complete member set plus the cursor
     * to delta from next time), the backend dispatched from the base
     * URL. An expired cursor re-runs an initial round and an initial
     * CardDAV sync a server rejects falls back to the plain
     * enumeration, both bridge-side. Returns {@code {"changed": [{id,
     * uri, etag, vcard?, books?}], "vanished": [uri], "token": "..",
     * "complete": bool}}.
     */
    static native String syncCards(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String syncToken);

    /**
     * Batch-fetches the cards at the given resource names (a JSON
     * string array) via REPORT addressbook-multiget; CardDAV only.
     * Returns a JSON array of {@code {id, uri, etag, vcard}}.
     */
    static native String multigetCards(
            Transport transport,
            String addressbookUrl,
            String login,
            String password,
            String uris);

    /**
     * Adds and removes the card's addressbook memberships on an
     * account-level backend (JSON string arrays of book ids), the
     * backend dispatched from the base URL; returns {@code {}}.
     */
    static native String updateCardBooks(
            Transport transport,
            String baseUrl,
            String login,
            String password,
            String id,
            String add,
            String remove);

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
     * Whether a 412-rejected push may retry unguarded, the last
     * enumerate proving the handle unchanged (the CardDAV If-Match
     * quirk); pure computation, no transport. Takes {@code {listed:
     * {handle: etag}?, complete, handle, ifMatch?}}, returns
     * {@code {"retry": bool}}.
     */
    static native String offlineRetryUnguarded(String facts);

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book's
     * enumerate; pure computation, no transport. Takes {@code {bookId?,
     * complete, changed: [{handle, books, known}], vanished}}, returns
     * {@code {"members": [index], "vanished": [handle]}}.
     */
    static native String offlineAccountSnapshot(String facts);

    /**
     * Plans one push change (membership patch vs create or delete,
     * plus the Google post-create membership); pure computation, no
     * transport. Takes {@code {op, collection, bookId?, origin,
     * deleted}}, returns {@code {action, postCreateBooks?}}.
     */
    static native String offlinePushPlan(String facts);

    /**
     * Maps one card-plus-membership row to its engine placement on the
     * server axis; pure computation, no transport. Returns
     * {@code {"placement": {..} | null}}.
     */
    static native String offlinePlacement(String facts);

    /**
     * Maps one card-plus-membership row to its phone-axis placement;
     * pure computation, no transport. Returns
     * {@code {"placement": {..} | null}}.
     */
    static native String offlinePhonePlacement(String facts);

    /**
     * Plans one engine upsert onto the card and membership rows; pure
     * computation, no transport. Returns {@code {action, row?,
     * memberState?}}.
     */
    static native String offlineUpsertPlan(String facts);

    /**
     * Plans one phone-axis upsert; pure computation, no transport.
     * Returns {@code {action, row?, axis}}.
     */
    static native String offlinePhoneUpsertPlan(String facts);

    /**
     * Plans a phone-collection drop (membership removal vs card
     * deletion); pure computation, no transport. Takes
     * {@code {collection, deleted, otherMemberships}}, returns
     * {@code {action}}.
     */
    static native String offlinePhoneDropPlan(String facts);

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
     * GENDER; empty for plain values), shaping the advanced editor's
     * value form; pure computation, no transport. Returns
     * {@code {"labels": [...]}}.
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
     * The edit form's view support computed from the field model
     * (summaries, type spinner positions, picker dates); pure
     * computation, no transport. Returns {@code {name, organization,
     * gender?, birthday?, anniversary?, phones, emails, relations,
     * addresses}}.
     */
    static native String formView(String model);

    /**
     * One typed entry saved from an edit dialog, its TYPE set drawn
     * from the spinner position ({@code phone}, {@code email} and
     * {@code relation} return the full entry, {@code address} the TYPE
     * set alone, {@code gender} the GENDER object, empty when unset);
     * pure computation, no transport.
     */
    static native String formEntry(String kind, int index, String value, boolean pref);

    /**
     * One picked date on the model wire (the vCard {@code yyyy-mm-dd}
     * form, 1-based month); pure computation, no transport. Returns
     * {@code {"value": ".."}}.
     */
    static native String formDate(int year, int month, int day);

    /**
     * Groups the replica pool into merged contacts, the groups sorted
     * by primary display name; pure computation, no transport. Takes
     * {@code {replicas: [{ref, uid, name, id}], links: {member:
     * cluster}, detached: [ref]}}, returns {@code {"groups": [{key,
     * replicas: [index]}]}}.
     */
    static native String groupContacts(String input);

    /**
     * The duplicate review's group facts, the dismissal key and the
     * Link eligibility; pure computation, no transport. Takes
     * {@code [{ref, book}]}, returns {@code {key, linkable}}.
     */
    static native String duplicateGroup(String members);
}
