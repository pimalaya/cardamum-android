package org.pimalaya.cardamum.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The only surface the app needs: config search (endpoints plus
 * authentication methods) and RFC 6764 discovery, OAuth 2.0 token
 * refresh (the code-exchange side lives on {@link OauthSession}), the
 * onboarding connection check, and the addressbook/card operations.
 * Owns the TLS sockets and the Rust bridge; the app sees neither.
 * Every call blocks, so callers must run it off the main thread.
 *
 * <p>Four backends hide behind the same operations: CardDAV,
 * Microsoft Graph for accounts whose {@link Account#baseUrl} carries
 * the {@link #MSGRAPH_PREFIX} sentinel (Microsoft exposes no CardDAV;
 * the Rust bridge projects Graph contacts to and from vCards), JMAP
 * for Contacts (RFC 9610) for accounts whose base URL carries the
 * {@link #JMAP_PREFIX} sentinel (the bridge converts JSContact cards
 * to and from vCards), and the Google People API for accounts whose
 * base URL carries the {@link #GOOGLE_PREFIX} sentinel (the bridge
 * projects People persons to and from vCards). Graph and Google
 * accounts authenticate with their OAuth access token in
 * {@link Account#password}; their addressbook URLs are the base URL
 * plus a folder segment, so they stay unique across accounts. JMAP
 * addressbook URLs likewise append the AddressBook id to the base URL.
 * Graph and JMAP updates carry no If-Match guard (last-write-wins);
 * Google updates are guarded by the person etag.
 */
public class CardamumClient {
    /**
     * Sentinel scheme of Microsoft Graph accounts: their base URL is
     * {@code msgraph://<email>} instead of a CardDAV context root.
     */
    public static final String MSGRAPH_PREFIX = "msgraph://";

    /**
     * Sentinel scheme of Google People API accounts: their base URL is
     * {@code google://<email>} instead of a CardDAV context root.
     */
    public static final String GOOGLE_PREFIX = "google://";

    /**
     * Sentinel scheme of JMAP accounts: their base URL is
     * {@code jmap://<host>[/path]} instead of a CardDAV context root.
     * The part after the sentinel is the HTTPS session URL (a bare host
     * triggers {@code /.well-known/jmap} discovery).
     */
    public static final String JMAP_PREFIX = "jmap://";

    /** Path segment of the default Contacts folder (empty Graph folder id). */
    private static final String MSGRAPH_DEFAULT_FOLDER = "contacts";

    /** Path segment of the single Google Contacts book (empty book id). */
    private static final String GOOGLE_DEFAULT_BOOK = "contacts";
    /**
     * Discovers the CardDAV context root for the email through the
     * given DNS resolver (null for the default DNS-over-HTTPS one).
     * Throws when none is found.
     */
    public String discover(String email, String resolver) {
        Transport transport = new Transport();
        try {
            JSONObject reply = object(Native.discover(transport, email, resolver));
            return string(reply, "url");
        } finally {
            transport.close();
        }
    }

    /**
     * Searches every discovery mechanism for CardDAV and JMAP service
     * configs (fixed provider rules, PACC, RFC 6764 CardDAV resolve,
     * RFC 8620 JMAP resolve), each carrying its endpoint and
     * authentication methods, so the connection screen can list them
     * for the user to choose. Resolver as in {@link #discover}.
     */
    public List<ServiceConfig> searchAll(String email, String resolver) {
        Transport transport = new Transport();
        try {
            return configs(Native.searchAll(transport, email, resolver));
        } finally {
            transport.close();
        }
    }

    /**
     * Same mechanism chain as {@link #searchAll}, stopping at the
     * first mechanism yielding a config; empty when none does.
     */
    public List<ServiceConfig> searchFirst(String email, String resolver) {
        Transport transport = new Transport();
        try {
            return configs(Native.searchFirst(transport, email, resolver));
        } finally {
            transport.close();
        }
    }

    /**
     * Refreshes OAuth 2.0 tokens; {@code scope} is the space-separated
     * scope list of the original grant (null keeps the server default)
     * and {@code clientSecret} rides along when the registration
     * issued one (null for none). The code-exchange side lives on
     * {@link OauthSession}.
     */
    public OauthTokens oauthRefresh(
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String refreshToken,
            String scope) {
        Transport transport = new Transport();
        try {
            JSONObject reply =
                    object(
                            Native.oauthRefreshAccessToken(
                                    transport,
                                    tokenEndpoint,
                                    clientId,
                                    clientSecret == null ? "" : clientSecret,
                                    refreshToken,
                                    scope == null ? "" : scope));
            return OauthTokens.from(reply);
        } finally {
            transport.close();
        }
    }

    /**
     * Fetches an authorization server's RFC 8414 metadata from its
     * issuer, so onboarding can drive the code grant and tell whether
     * the server lets a public client register itself (RFC 7591).
     */
    public ServerMetadata oauthServerMetadata(String issuer) {
        Transport transport = new Transport();
        try {
            return new ServerMetadata(object(Native.oauthServerMetadata(transport, issuer)));
        } finally {
            transport.close();
        }
    }

    /**
     * Registers a public client at the given RFC 7591 registration
     * endpoint (no secret, the loopback redirect URI, code + refresh
     * grants), returning the server-issued client id. The client
     * secret rides in {@link OauthTokens}-style JSON but a public
     * client gets none.
     */
    public String oauthRegisterClient(
            String registrationEndpoint, String redirectUri, String clientName, String scope) {
        Transport transport = new Transport();
        try {
            JSONObject reply =
                    object(
                            Native.oauthRegisterClient(
                                    transport,
                                    registrationEndpoint,
                                    redirectUri,
                                    clientName == null ? "" : clientName,
                                    scope == null ? "" : scope));
            return string(reply, "client_id");
        } finally {
            transport.close();
        }
    }

    /** Connects, authenticates and lists once to prove the account is usable. */
    public void verify(Account account) {
        listAddressbooks(account);
    }

    /**
     * Lists the account's addressbooks: the CardDAV discovery walk,
     * the Graph contact folders (default Contacts folder first), the
     * JMAP AddressBooks, or the single Google Contacts book.
     */
    public List<Addressbook> listAddressbooks(Account account) {
        Transport transport = new Transport();
        try {
            if (isGoogle(account)) {
                JSONArray reply =
                        array(Native.listGoogleAddressbooks(transport, account.password));

                List<Addressbook> books = new ArrayList<>(reply.length());
                for (int index = 0; index < reply.length(); index++) {
                    JSONObject book = object(reply, index);
                    String id = string(book, "id");
                    String segment = id.isEmpty() ? GOOGLE_DEFAULT_BOOK : id;
                    books.add(
                            new Addressbook(
                                    id,
                                    string(book, "name"),
                                    account.baseUrl + "/" + segment,
                                    null,
                                    null));
                }
                return books;
            }

            if (isGraph(account)) {
                JSONArray reply =
                        array(Native.listGraphAddressbooks(transport, account.password));

                List<Addressbook> books = new ArrayList<>(reply.length());
                for (int index = 0; index < reply.length(); index++) {
                    JSONObject book = object(reply, index);
                    String id = string(book, "id");
                    String segment = id.isEmpty() ? MSGRAPH_DEFAULT_FOLDER : id;
                    books.add(
                            new Addressbook(
                                    id,
                                    string(book, "name"),
                                    account.baseUrl + "/" + segment,
                                    null,
                                    null));
                }
                return books;
            }

            if (isJmap(account)) {
                JSONArray reply =
                        array(
                                Native.listJmapAddressbooks(
                                        transport,
                                        jmapSessionUrl(account),
                                        account.login,
                                        account.password));

                List<Addressbook> books = new ArrayList<>(reply.length());
                for (int index = 0; index < reply.length(); index++) {
                    JSONObject book = object(reply, index);
                    String id = string(book, "id");
                    books.add(
                            new Addressbook(
                                    id,
                                    string(book, "name"),
                                    account.baseUrl + "/" + id,
                                    optString(book, "description"),
                                    null));
                }
                return books;
            }

            JSONArray reply =
                    array(
                            Native.listAddressbooks(
                                    transport, account.baseUrl, account.login, account.password));

            List<Addressbook> books = new ArrayList<>(reply.length());
            for (int index = 0; index < reply.length(); index++) {
                JSONObject book = object(reply, index);
                books.add(
                        new Addressbook(
                                string(book, "id"),
                                string(book, "name"),
                                string(book, "url"),
                                optString(book, "description"),
                                optString(book, "color")));
            }
            return books;
        } finally {
            transport.close();
        }
    }

    /**
     * True when the account's cards are account-level resources with
     * m:n addressbook memberships (JMAP, Google): they list once per
     * account through {@link #listAccountCards}, not per addressbook.
     * CardDAV and Graph cards live in the one collection they were
     * listed from and go through {@link #listCards}.
     */
    public static boolean isAccountLevel(Account account) {
        return isJmap(account) || isGoogle(account);
    }

    /**
     * Lists every card of an account-level backend (JMAP, Google) in
     * one pass, each carrying its addressbook memberships as book ids
     * ({@link Card#books}).
     */
    public List<Card> listAccountCards(Account account) {
        Transport transport = new Transport();
        try {
            String reply =
                    isGoogle(account)
                            ? Native.listGoogleCards(transport, account.password)
                            : Native.listJmapCards(
                                    transport,
                                    jmapSessionUrl(account),
                                    account.login,
                                    account.password);
            return cards(reply);
        } finally {
            transport.close();
        }
    }

    /** Lists the cards of the addressbook collection at the given URL. */
    public List<Card> listCards(Account account, String addressbookUrl) {
        Transport transport = new Transport();
        try {
            String reply =
                    isGraph(account)
                            ? Native.listGraphCards(
                                    transport,
                                    account.password,
                                    graphFolder(account, addressbookUrl))
                            : Native.listCards(
                                    transport,
                                    addressbookUrl,
                                    account.login,
                                    account.password);
            return cards(reply);
        } finally {
            transport.close();
        }
    }

    /**
     * Enumerates a CardDAV addressbook's card spine (resource name
     * plus ETag, no body); the bodies are then batch-fetched with
     * {@link #multigetCards}.
     */
    public List<Card> enumCards(Account account, String addressbookUrl) {
        Transport transport = new Transport();
        try {
            JSONArray reply =
                    array(
                            Native.enumCards(
                                    transport,
                                    addressbookUrl,
                                    account.login,
                                    account.password));

            List<Card> cards = new ArrayList<>(reply.length());
            for (int index = 0; index < reply.length(); index++) {
                JSONObject entry = object(reply, index);
                cards.add(
                        new Card(
                                string(entry, "id"),
                                string(entry, "uri"),
                                optString(entry, "etag"),
                                ""));
            }
            return cards;
        } finally {
            transport.close();
        }
    }

    /**
     * Lists a collection's changes since the given cursor, on every
     * backend: a CardDAV sync-collection REPORT (RFC 6578), a Graph
     * contacts delta round (id and changeKey only), a JMAP
     * ContactCard/changes round (changed cards in full), or a People
     * connections sync (changed contacts in full, account-wide with
     * memberships). A null cursor runs the initial round: the complete
     * member set plus the cursor to delta from next time. A cursor the
     * server no longer accepts comes back as
     * {@link CardDelta#invalidToken} so the caller re-runs an initial
     * round.
     */
    public CardDelta syncCards(Account account, String addressbookUrl, String syncToken) {
        Transport transport = new Transport();
        try {
            String cursor = syncToken == null ? "" : syncToken;
            String reply;
            if (isGoogle(account)) {
                reply = Native.syncGoogleCards(transport, account.password, cursor);
            } else if (isGraph(account)) {
                reply =
                        Native.deltaGraphCards(
                                transport,
                                account.password,
                                graphFolder(account, addressbookUrl),
                                cursor);
            } else if (isJmap(account)) {
                reply =
                        Native.changesJmapCards(
                                transport,
                                jmapSessionUrl(account),
                                account.login,
                                account.password,
                                cursor);
            } else {
                reply =
                        Native.syncCards(
                                transport,
                                addressbookUrl,
                                account.login,
                                account.password,
                                cursor);
            }

            JSONObject parsed = object(reply);
            if (parsed.optBoolean("invalidToken")) {
                return new CardDelta(List.of(), List.of(), null, true);
            }

            JSONArray rows = parsed.optJSONArray("changed");
            List<Card> changed = new ArrayList<>(rows == null ? 0 : rows.length());
            for (int index = 0; rows != null && index < rows.length(); index++) {
                changed.add(card(object(rows, index)));
            }

            JSONArray gone = parsed.optJSONArray("vanished");
            List<String> vanished = new ArrayList<>(gone == null ? 0 : gone.length());
            for (int index = 0; gone != null && index < gone.length(); index++) {
                vanished.add(gone.optString(index));
            }

            return new CardDelta(changed, vanished, optString(parsed, "token"), false);
        } finally {
            transport.close();
        }
    }

    /**
     * Batch-fetches the cards at the given resource names inside a
     * CardDAV addressbook via REPORT addressbook-multiget.
     */
    public List<Card> multigetCards(Account account, String addressbookUrl, List<String> uris) {
        Transport transport = new Transport();
        try {
            return cards(
                    Native.multigetCards(
                            transport,
                            addressbookUrl,
                            account.login,
                            account.password,
                            new JSONArray(uris).toString()));
        } finally {
            transport.close();
        }
    }

    /**
     * Reconciles a collection with its remote through the io-offline
     * engine, the driver servicing every storage and remote yield;
     * with {@code full} the checkpoint is ignored and the whole remote
     * is enumerated. Returns the sync report
     * {@code {pulled, pushed, conflicts, rejected, refreshed}}.
     */
    public JSONObject offlineSync(OfflineDriver driver, String collection, boolean full) {
        return object(Native.offlineSync(driver, collection, full));
    }

    /**
     * Raises the given handles to the full detail tier through the
     * io-offline engine (bodies deduped by link id against the store).
     * Returns the upgrade report {@code {upgraded, fetched, deduped}}.
     */
    public JSONObject offlineUpgrade(OfflineDriver driver, String collection, List<String> handles) {
        return object(Native.offlineUpgrade(driver, collection, new JSONArray(handles).toString()));
    }

    /**
     * Stages a local mutation through the io-offline engine (storage
     * yields only, the remote is never touched).
     */
    public void offlineMutate(OfflineDriver driver, String collection, JSONObject mutation) {
        object(Native.offlineMutate(driver, collection, mutation.toString()));
    }

    /**
     * Adds and removes the card's addressbook memberships on an
     * account-level backend, by book id: JMAP patches addressBookIds,
     * Google modifies group members.
     */
    public void updateCardBooks(
            Account account, String cardId, List<String> add, List<String> remove) {
        Transport transport = new Transport();
        try {
            String reply =
                    isGoogle(account)
                            ? Native.updateGoogleCardBooks(
                                    transport,
                                    account.password,
                                    cardId,
                                    books(add),
                                    books(remove))
                            : Native.updateJmapCardBooks(
                                    transport,
                                    jmapSessionUrl(account),
                                    account.login,
                                    account.password,
                                    cardId,
                                    books(add),
                                    books(remove));
            object(reply);
        } finally {
            transport.close();
        }
    }

    /** Serializes a book id list for the bridge. */
    private static String books(List<String> ids) {
        return new JSONArray(ids).toString();
    }

    /** Parses a card-array reply. */
    private static List<Card> cards(String reply) {
        JSONArray parsed = array(reply);
        List<Card> cards = new ArrayList<>(parsed.length());
        for (int index = 0; index < parsed.length(); index++) {
            cards.add(card(object(parsed, index)));
        }
        return cards;
    }

    /**
     * Creates the card in the addressbook collection, returning it
     * with its ETag. Graph names the resource itself, so there the
     * returned card carries the server-assigned id instead of the
     * given one.
     */
    public Card createCard(Account account, String addressbookUrl, String id, String vcard) {
        Transport transport = new Transport();
        try {
            if (isGoogle(account)) {
                return card(object(Native.createGoogleCard(transport, account.password, vcard)));
            }

            if (isGraph(account)) {
                return card(
                        object(
                                Native.createGraphCard(
                                        transport,
                                        account.password,
                                        graphFolder(account, addressbookUrl),
                                        vcard)));
            }

            if (isJmap(account)) {
                return card(
                        object(
                                Native.createJmapCard(
                                        transport,
                                        jmapSessionUrl(account),
                                        account.login,
                                        account.password,
                                        jmapBook(account, addressbookUrl),
                                        vcard)));
            }

            JSONObject reply =
                    object(
                            Native.createCard(
                                    transport,
                                    addressbookUrl,
                                    account.login,
                                    account.password,
                                    id,
                                    vcard));
            // Creation names the resource itself, so the uri is known.
            return new Card(id, id + ".vcf", optString(reply, "etag"), vcard);
        } finally {
            transport.close();
        }
    }

    /** Reads the card at the given resource name from the addressbook collection. */
    public Card readCard(Account account, String addressbookUrl, String uri) {
        Transport transport = new Transport();
        try {
            if (isGoogle(account)) {
                return card(object(Native.readGoogleCard(transport, account.password, uri)));
            }

            if (isGraph(account)) {
                return card(object(Native.readGraphCard(transport, account.password, uri)));
            }

            if (isJmap(account)) {
                return card(
                        object(
                                Native.readJmapCard(
                                        transport,
                                        jmapSessionUrl(account),
                                        account.login,
                                        account.password,
                                        uri)));
            }

            JSONObject reply =
                    object(
                            Native.readCard(
                                    transport,
                                    addressbookUrl,
                                    account.login,
                                    account.password,
                                    uri));
            return card(reply);
        } finally {
            transport.close();
        }
    }

    /**
     * Updates the card in the addressbook collection, returning it with
     * its new ETag. The base vCard (the state last synced with the
     * server, null when unknown) trims the Graph, JMAP and Google
     * patches to the fields the edit changed; CardDAV PUTs the full
     * vCard and ignores it.
     */
    public Card updateCard(Account account, String addressbookUrl, Card card, String baseVcard) {
        Transport transport = new Transport();
        try {
            if (isGoogle(account)) {
                return card(
                        object(
                                Native.updateGoogleCard(
                                        transport,
                                        account.password,
                                        bareUriOf(card),
                                        card.vcard,
                                        baseVcard == null ? "" : baseVcard,
                                        card.etag == null ? "" : card.etag)));
            }

            if (isGraph(account)) {
                return card(
                        object(
                                Native.updateGraphCard(
                                        transport,
                                        account.password,
                                        uriOf(card),
                                        card.vcard,
                                        baseVcard == null ? "" : baseVcard)));
            }

            if (isJmap(account)) {
                return card(
                        object(
                                Native.updateJmapCard(
                                        transport,
                                        jmapSessionUrl(account),
                                        account.login,
                                        account.password,
                                        bareUriOf(card),
                                        card.vcard,
                                        baseVcard == null ? "" : baseVcard)));
            }

            JSONObject reply =
                    object(
                            Native.updateCard(
                                    transport,
                                    addressbookUrl,
                                    account.login,
                                    account.password,
                                    uriOf(card),
                                    card.vcard,
                                    card.etag == null ? "" : card.etag));
            return new Card(card.id, uriOf(card), optString(reply, "etag"), card.vcard);
        } finally {
            transport.close();
        }
    }

    /**
     * Projects the card's vCard onto the neutral field model the app
     * maps to ContactsContract rows (docs/contacts-mapping.md).
     */
    public JSONObject projectCard(Card card) {
        return object(Native.projectCard(card.vcard));
    }

    /** Projects a raw vCard document onto the neutral field model. */
    public JSONObject projectCard(String vcard) {
        return object(Native.projectCard(vcard));
    }

    /**
     * Indexes a vCard for the store: the display fields the contacts
     * list renders (name, first email and phone, UID) and a normalized
     * content hash for the divergence flag of linked replicas. Pure
     * computation, no transport.
     */
    public JSONObject indexCard(String vcard) {
        return object(Native.indexCard(vcard));
    }

    /**
     * Patches an edited field model back onto the vCard, preserving
     * every property the model does not manage.
     */
    public String applyCard(String vcard, JSONObject model) {
        return string(object(Native.applyCard(vcard, model.toString())), "vcard");
    }

    /**
     * Merges several vCard documents into one union: the merged
     * document, its field model (the merge form prefill) and the
     * per-field alternative values. Pure computation, no transport.
     */
    public JSONObject mergeCards(List<String> vcards) {
        JSONArray cards = new JSONArray();
        for (String vcard : vcards) {
            cards.put(vcard);
        }
        return object(Native.mergeCards(cards.toString()));
    }

    /**
     * Three-way merges a conflicted push: the staged local edit and
     * the fetched remote card against their common base. The local
     * side wins same-field collisions; every other remote change flows
     * in. Pure computation, no transport.
     */
    public String mergeCardChanges(String base, String local, String remote) {
        return string(object(Native.mergeCardChanges(base, local, remote)), "vcard");
    }

    /**
     * Rewrites the card's UID, preserving every other byte (a plain
     * copy is a new identity). Pure computation, no transport.
     */
    public String setCardUid(String vcard, String uid) {
        return string(object(Native.setCardUid(vcard, uid)), "vcard");
    }

    /**
     * Finds groups of likely-duplicate cards among {@code {ref,
     * vcard}} pairs: exact normalized email, phone or full-name
     * matches, conservative on purpose. Pure computation, no
     * transport.
     */
    public JSONArray findDuplicates(JSONArray cards) {
        return object(Native.findDuplicates(cards.toString())).optJSONArray("groups");
    }

    /**
     * Lists the card's raw property lines for the advanced editor, in
     * source order, unfolded. Pure computation, no transport.
     */
    public JSONArray cardProps(String vcard) {
        return object(Native.cardProps(vcard)).optJSONArray("props");
    }

    /**
     * Rewrites one raw property line for the advanced editor: the line
     * replaces the property at the index (a blank line removes it),
     * index -1 appends. Pure computation, no transport.
     */
    public String cardSetProp(String vcard, int index, String line) {
        return string(object(Native.cardSetProp(vcard, index, line)), "vcard");
    }

    /**
     * Recomposes one property from its structured parts ({@code {name,
     * params: [{name, values}], value}}) and rewrites it (index -1
     * appends). Pure computation, no transport.
     */
    public String cardSetPropParts(String vcard, int index, JSONObject prop) {
        return string(object(Native.cardSetPropParts(vcard, index, prop.toString())), "vcard");
    }

    /**
     * The component labels of a structured property name (N, ADR,
     * GENDER), empty for plain values: the advanced editor shapes its
     * value form from them. Pure computation, no transport.
     */
    public JSONArray cardPropLabels(String name) {
        return object(Native.cardPropLabels(name)).optJSONArray("labels");
    }

    /**
     * Validates a hand-edited vCard source (it must reparse) and
     * returns it re-serialized. Pure computation, no transport.
     */
    public String cardSource(String vcard) {
        return string(object(Native.cardSource(vcard)), "vcard");
    }

    /** Deletes the card from the addressbook collection. */
    public void deleteCard(Account account, String addressbookUrl, Card card) {
        Transport transport = new Transport();
        try {
            if (isGoogle(account)) {
                object(Native.deleteGoogleCard(transport, account.password, bareUriOf(card)));
                return;
            }

            if (isGraph(account)) {
                object(Native.deleteGraphCard(transport, account.password, uriOf(card)));
                return;
            }

            if (isJmap(account)) {
                object(
                        Native.deleteJmapCard(
                                transport,
                                jmapSessionUrl(account),
                                account.login,
                                account.password,
                                bareUriOf(card)));
                return;
            }

            object(
                    Native.deleteCard(
                            transport,
                            addressbookUrl,
                            account.login,
                            account.password,
                            uriOf(card),
                            card.etag == null ? "" : card.etag));
        } finally {
            transport.close();
        }
    }

    /** The card's addressing key, reconstructed for pre-uri local rows. */
    private static String uriOf(Card card) {
        return card.uri == null ? card.id + ".vcf" : card.uri;
    }

    /**
     * The card's JMAP or Google addressing key: the server id, no
     * suffix.
     */
    private static String bareUriOf(Card card) {
        return card.uri == null ? card.id : card.uri;
    }

    private static boolean isGraph(Account account) {
        return account.baseUrl != null && account.baseUrl.startsWith(MSGRAPH_PREFIX);
    }

    private static boolean isGoogle(Account account) {
        return account.baseUrl != null && account.baseUrl.startsWith(GOOGLE_PREFIX);
    }

    private static boolean isJmap(Account account) {
        return account.baseUrl != null && account.baseUrl.startsWith(JMAP_PREFIX);
    }

    /**
     * The HTTPS session URL behind a JMAP account's base URL (the part
     * after the {@link #JMAP_PREFIX} sentinel).
     */
    private static String jmapSessionUrl(Account account) {
        return "https://" + account.baseUrl.substring(JMAP_PREFIX.length());
    }

    /**
     * The JMAP AddressBook id addressed by an addressbook URL (the
     * segment after the account's base URL).
     */
    private static String jmapBook(Account account, String addressbookUrl) {
        String prefix = account.baseUrl + "/";
        return addressbookUrl.startsWith(prefix)
                ? addressbookUrl.substring(prefix.length())
                : addressbookUrl;
    }

    /**
     * The Graph folder id addressed by an addressbook URL (the segment
     * after the account's base URL), empty for the default Contacts
     * folder.
     */
    private static String graphFolder(Account account, String addressbookUrl) {
        String prefix = account.baseUrl + "/";
        String folder =
                addressbookUrl.startsWith(prefix)
                        ? addressbookUrl.substring(prefix.length())
                        : addressbookUrl;
        return folder.equals(MSGRAPH_DEFAULT_FOLDER) ? "" : folder;
    }

    /** Parses a `{id, uri, etag, vcard, books?}` reply into a card. */
    private static Card card(JSONObject reply) {
        List<String> books = new ArrayList<>();
        JSONArray parsed = reply.optJSONArray("books");
        if (parsed != null) {
            for (int index = 0; index < parsed.length(); index++) {
                books.add(parsed.optString(index));
            }
        }

        return new Card(
                string(reply, "id"),
                string(reply, "uri"),
                optString(reply, "etag"),
                string(reply, "vcard"),
                books);
    }

    /** Parses a search reply into service configs. */
    private static List<ServiceConfig> configs(String json) {
        JSONArray reply = array(json);

        List<ServiceConfig> configs = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            configs.add(ServiceConfig.from(object(reply, index)));
        }
        return configs;
    }

    // ---- Bridge reply parsing ----------------------------------------------

    /** Parses an object reply, surfacing the bridge's {@code error} field. */
    static JSONObject object(String json) {
        try {
            JSONObject reply = new JSONObject(json.trim());
            String error = reply.optString("error");
            if (!error.isEmpty()) {
                throw new CardamumException(error);
            }
            return reply;
        } catch (JSONException error) {
            throw new CardamumException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    /** Parses an array reply, surfacing the bridge's {@code error} field. */
    private static JSONArray array(String json) {
        String trimmed = json.trim();

        if (trimmed.startsWith("{")) {
            // An object where an array is expected is always an error.
            object(trimmed);
            throw new CardamumException("Unreadable bridge reply: expected an array");
        }

        try {
            return new JSONArray(trimmed);
        } catch (JSONException error) {
            throw new CardamumException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    private static JSONObject object(JSONArray array, int index) {
        try {
            return array.getJSONObject(index);
        } catch (JSONException error) {
            throw new CardamumException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    static String string(JSONObject object, String key) {
        try {
            return object.getString(key);
        } catch (JSONException error) {
            throw new CardamumException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    private static String optString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key);
    }
}
