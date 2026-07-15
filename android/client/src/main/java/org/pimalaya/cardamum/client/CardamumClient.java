package org.pimalaya.cardamum.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Four backends hide behind the same operations: CardDAV, Microsoft
 * Graph, JMAP for Contacts (RFC 9610) and the Google People API. The
 * Rust bridge tells them apart by the account's base URL (the
 * non-CardDAV backends carry a sentinel scheme there) and dispatches
 * every operation; the base URL is opaque here, built by the
 * {@code *Base} helpers and threaded back verbatim. Graph and Google
 * accounts authenticate with their OAuth access token in
 * {@link Account#password}. Graph and JMAP updates carry no If-Match
 * guard (last-write-wins); CardDAV and Google updates are guarded by
 * the ETag.
 */
public class CardamumClient {
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
     * Runs the MX provider probe: one DNS lookup matching the mail
     * exchanges against the fixed provider rules, catching every
     * domain whose mail lives at Google or Microsoft (gmail.com and
     * outlook.com included, their own exchanges match too). Meant to
     * run in parallel with {@link #searchAll} so the caller decides
     * what to take. Returns the raw provider configs (their source
     * naming the provider), empty when no rule matched. Resolver as
     * in {@link #discover}.
     */
    public List<ServiceConfig> searchProvider(String email, String resolver) {
        Transport transport = new Transport();
        try {
            return configs(Native.searchProvider(transport, email, resolver));
        } finally {
            transport.close();
        }
    }

    /**
     * Searches every discovery mechanism for CardDAV and JMAP service
     * configs (PACC, RFC 6764 CardDAV resolve, RFC 8620 JMAP
     * resolve), each carrying its endpoint and authentication
     * methods, so the connection screen can list them for the user to
     * choose. The input is an email address or a bare domain (every
     * mechanism is domain-driven; a domain just skips the username
     * hints), normally pre-gated by {@link #searchProvider}. The
     * mechanisms run in parallel, each on its own transport; a
     * failing mechanism is skipped. Their outputs merge in
     * mechanism-priority order, and the merged configs' endpoints are
     * probed in parallel for the authentication schemes they actually
     * advertise. Resolver as in {@link #discover}.
     */
    public List<ServiceConfig> searchAll(String email, String resolver) {
        String[] outputs = new String[3];
        Thread[] mechanisms = new Thread[outputs.length];
        for (int index = 0; index < mechanisms.length; index++) {
            final int mechanism = index;
            mechanisms[index] =
                    new Thread(
                            () -> {
                                Transport transport = new Transport();
                                try {
                                    outputs[mechanism] =
                                            searchMechanism(mechanism, transport, email, resolver);
                                } finally {
                                    transport.close();
                                }
                            });
            mechanisms[index].start();
        }
        joinAll(mechanisms);

        JSONArray lists = new JSONArray();
        for (String output : outputs) {
            lists.put(configLists(output));
        }
        JSONArray merged = array(Native.searchMerge(lists.toString()));

        String[] probed = new String[merged.length()];
        Thread[] probes = new Thread[probed.length];
        for (int index = 0; index < probes.length; index++) {
            final int at = index;
            final String config = object(merged, at).toString();
            probes[index] =
                    new Thread(
                            () -> {
                                Transport transport = new Transport();
                                try {
                                    probed[at] = Native.searchProbe(transport, config);
                                } finally {
                                    transport.close();
                                }
                                try {
                                    object(probed[at]);
                                } catch (RuntimeException error) {
                                    // A failed probe keeps the config
                                    // as discovered.
                                    probed[at] = config;
                                }
                            });
            probes[index].start();
        }
        joinAll(probes);

        JSONArray result = new JSONArray();
        for (String config : probed) {
            result.put(object(config));
        }
        return configs(result.toString());
    }

    /** One discovery mechanism run, by priority rank. */
    private static String searchMechanism(
            int mechanism, Transport transport, String email, String resolver) {
        switch (mechanism) {
            case 0:
                return Native.searchPacc(transport, email, resolver);
            case 1:
                return Native.searchCarddav(transport, email, resolver);
            default:
                return Native.searchJmap(transport, email, resolver);
        }
    }

    /**
     * Parses one mechanism's output into a config array; a failed
     * mechanism (error reply, or none at all) is skipped as an empty
     * list, like the serial search chain did.
     */
    private static JSONArray configLists(String output) {
        if (output == null) {
            return new JSONArray();
        }
        try {
            return array(output);
        } catch (RuntimeException error) {
            return new JSONArray();
        }
    }

    /** Joins every thread, restoring the interrupt flag if raised. */
    private static void joinAll(Thread[] threads) {
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
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

    // ---- Account base URLs and backend info ---------------------------------

    /**
     * Google's CardDAV principal root for the email; the standard
     * PROPFIND discovery runs from there.
     */
    public static String googleCarddavBase(String email) {
        return base("googleCarddav", email);
    }

    /** A Google People account's base URL for the email. */
    public static String googlePeopleBase(String email) {
        return base("google", email);
    }

    /** A Microsoft Graph account's base URL for the email. */
    public static String msgraphBase(String email) {
        return base("msgraph", email);
    }

    /**
     * A JMAP account's base URL wrapping the HTTPS session URL (a bare
     * host triggers {@code /.well-known/jmap} discovery).
     */
    public static String jmapBase(String sessionUrl) {
        return base("jmap", sessionUrl);
    }

    /** Builds one account base URL through the bridge. */
    private static String base(String kind, String value) {
        return string(object(Native.accountBase(kind, value)), "url");
    }

    /**
     * True when the account's cards are account-level resources with
     * m:n addressbook memberships (JMAP, Google): they list once per
     * account through {@link #listAccountCards}, not per addressbook.
     * CardDAV and Graph cards live in the one collection they were
     * listed from and go through {@link #listCards}.
     */
    public static boolean isAccountLevel(Account account) {
        return account != null && isAccountLevel(account.baseUrl);
    }

    /**
     * True when the URL (an account base URL, or a collection URL
     * derived from one) belongs to an account-level backend.
     */
    public static boolean isAccountLevel(String url) {
        return info(url).optBoolean("accountLevel");
    }

    /** True when the URL belongs to a plain CardDAV backend. */
    public static boolean isCarddav(String url) {
        return "carddav".equals(info(url).optString("backend"));
    }

    /** True when the account's backend is Microsoft Graph. */
    public static boolean isGraph(Account account) {
        return account != null && "graph".equals(info(account.baseUrl).optString("backend"));
    }

    /** True when the account's backend is the Google People API. */
    public static boolean isGoogle(Account account) {
        return account != null && "google".equals(info(account.baseUrl).optString("backend"));
    }

    /** Parsed backend info by URL, cached (pure computation). */
    private static final Map<String, JSONObject> infos = new ConcurrentHashMap<>();

    /** The URL's {@code {backend, accountLevel}} info. */
    private static JSONObject info(String url) {
        return infos.computeIfAbsent(
                url == null ? "" : url, key -> object(Native.accountInfo(key)));
    }

    // ---- Addressbook and card operations ------------------------------------

    /** Connects, authenticates and lists once to prove the account is usable. */
    public void verify(Account account) {
        listAddressbooks(account);
    }

    /**
     * Lists the account's addressbooks: the CardDAV discovery walk,
     * the Graph contact folders (default Contacts folder first), the
     * JMAP AddressBooks, or the Google contact groups.
     */
    public List<Addressbook> listAddressbooks(Account account) {
        Transport transport = new Transport();
        try {
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
     * Lists every card of an account-level backend (JMAP, Google) in
     * one pass, each carrying its addressbook memberships as book ids
     * ({@link Card#books}).
     */
    public List<Card> listAccountCards(Account account) {
        Transport transport = new Transport();
        try {
            return cards(
                    Native.listAccountCards(
                            transport, account.baseUrl, account.login, account.password));
        } finally {
            transport.close();
        }
    }

    /** Lists the cards of the addressbook collection at the given URL. */
    public List<Card> listCards(Account account, String addressbookUrl) {
        Transport transport = new Transport();
        try {
            return cards(
                    Native.listCards(
                            transport,
                            account.baseUrl,
                            addressbookUrl,
                            account.login,
                            account.password));
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
     * server no longer accepts re-runs an initial round bridge-side,
     * flagged {@link CardDelta#complete}.
     */
    public CardDelta syncCards(Account account, String addressbookUrl, String syncToken) {
        Transport transport = new Transport();
        try {
            String reply =
                    Native.syncCards(
                            transport,
                            account.baseUrl,
                            addressbookUrl,
                            account.login,
                            account.password,
                            syncToken == null ? "" : syncToken);

            JSONObject parsed = object(reply);
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

            return new CardDelta(
                    changed,
                    vanished,
                    optString(parsed, "token"),
                    parsed.optBoolean("complete"));
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
     * Whether a 412-rejected push may retry unguarded, the last
     * enumerate proving the handle unchanged (the CardDAV If-Match
     * quirk). Pure computation, no transport.
     */
    public boolean offlineRetryUnguarded(JSONObject facts) {
        return object(Native.offlineRetryUnguarded(facts.toString())).optBoolean("retry");
    }

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book's
     * enumerate ({@code {members, vanished}}). Pure computation, no
     * transport.
     */
    public JSONObject offlineAccountSnapshot(JSONObject facts) {
        return object(Native.offlineAccountSnapshot(facts.toString()));
    }

    /**
     * Plans one push change ({@code {action, postCreateBooks?}}). Pure
     * computation, no transport.
     */
    public JSONObject offlinePushPlan(JSONObject facts) {
        return object(Native.offlinePushPlan(facts.toString()));
    }

    /**
     * Maps one card-plus-membership row to its engine placement on the
     * server axis, null when the row surfaces nowhere. Pure
     * computation, no transport.
     */
    public JSONObject offlinePlacement(JSONObject facts) {
        return object(Native.offlinePlacement(facts.toString())).optJSONObject("placement");
    }

    /**
     * Maps one card-plus-membership row to its phone-axis placement,
     * null when the row surfaces nowhere. Pure computation, no
     * transport.
     */
    public JSONObject offlinePhonePlacement(JSONObject facts) {
        return object(Native.offlinePhonePlacement(facts.toString())).optJSONObject("placement");
    }

    /**
     * Plans one engine upsert onto the card and membership rows
     * ({@code {action, row?, memberState?}}). Pure computation, no
     * transport.
     */
    public JSONObject offlineUpsertPlan(JSONObject facts) {
        return object(Native.offlineUpsertPlan(facts.toString()));
    }

    /**
     * Plans one phone-axis upsert ({@code {action, row?, axis}}). Pure
     * computation, no transport.
     */
    public JSONObject offlinePhoneUpsertPlan(JSONObject facts) {
        return object(Native.offlinePhoneUpsertPlan(facts.toString()));
    }

    /**
     * Plans a phone-collection drop ({@code {action}}). Pure
     * computation, no transport.
     */
    public JSONObject offlinePhoneDropPlan(JSONObject facts) {
        return object(Native.offlinePhoneDropPlan(facts.toString()));
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
            object(
                    Native.updateCardBooks(
                            transport,
                            account.baseUrl,
                            account.login,
                            account.password,
                            cardId,
                            books(add),
                            books(remove)));
        } finally {
            transport.close();
        }
    }

    /**
     * Creates the card in the addressbook collection, returning it
     * with its ETag. The backends naming the resource themselves
     * return the server-assigned id instead of the given one.
     */
    public Card createCard(Account account, String addressbookUrl, String id, String vcard) {
        Transport transport = new Transport();
        try {
            return card(
                    object(
                            Native.createCard(
                                    transport,
                                    account.baseUrl,
                                    addressbookUrl,
                                    account.login,
                                    account.password,
                                    id,
                                    vcard)));
        } finally {
            transport.close();
        }
    }

    /** Reads the card at the given resource name from the addressbook collection. */
    public Card readCard(Account account, String addressbookUrl, String uri) {
        Transport transport = new Transport();
        try {
            return card(
                    object(
                            Native.readCard(
                                    transport,
                                    account.baseUrl,
                                    addressbookUrl,
                                    account.login,
                                    account.password,
                                    uri)));
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
            return card(
                    object(
                            Native.updateCard(
                                    transport,
                                    account.baseUrl,
                                    addressbookUrl,
                                    account.login,
                                    account.password,
                                    card.id,
                                    card.uri == null ? "" : card.uri,
                                    card.vcard,
                                    baseVcard == null ? "" : baseVcard,
                                    card.etag == null ? "" : card.etag)));
        } finally {
            transport.close();
        }
    }

    /** Deletes the card from the addressbook collection. */
    public void deleteCard(Account account, String addressbookUrl, Card card) {
        Transport transport = new Transport();
        try {
            object(
                    Native.deleteCard(
                            transport,
                            account.baseUrl,
                            addressbookUrl,
                            account.login,
                            account.password,
                            card.id,
                            card.uri == null ? "" : card.uri,
                            card.etag == null ? "" : card.etag));
        } finally {
            transport.close();
        }
    }

    /**
     * Creates a batch of cards (Google-only: the one backend with a
     * batch create verb), returning the created cards in input order
     * with their server-assigned ids.
     */
    public List<Card> createCards(Account account, List<String> vcards) {
        Transport transport = new Transport();
        try {
            return cards(
                    Native.createCards(
                            transport,
                            account.baseUrl,
                            account.login,
                            account.password,
                            new JSONArray(vcards).toString()));
        } finally {
            transport.close();
        }
    }

    /** Deletes a batch of cards by id (Google-only, like createCards). */
    public void deleteCards(Account account, List<String> ids) {
        Transport transport = new Transport();
        try {
            object(
                    Native.deleteCards(
                            transport,
                            account.baseUrl,
                            account.login,
                            account.password,
                            new JSONArray(ids).toString()));
        } finally {
            transport.close();
        }
    }

    /**
     * Pushes a round of changes as batch calls (JMAP ContactCard/set,
     * Graph $batch), returning one {@code {ref, accepted, id?, etag?,
     * error?}} outcome per change: a rejected change reports rejected
     * instead of failing the round.
     */
    public JSONArray pushCards(Account account, String addressbookUrl, JSONArray changes) {
        Transport transport = new Transport();
        try {
            return array(
                    Native.pushCards(
                            transport,
                            account.baseUrl,
                            addressbookUrl,
                            account.login,
                            account.password,
                            changes.toString()));
        } finally {
            transport.close();
        }
    }

    // ---- Pure card computations ----------------------------------------------

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
     * the fetched remote card against their common base (empty means
     * unknown; the local side stands in). The local side wins
     * same-field collisions; every other remote change flows in. Pure
     * computation, no transport.
     */
    public String mergeCardChanges(String base, String local, String remote) {
        return string(object(Native.mergeCardChanges(base, local, remote)), "vcard");
    }

    /**
     * Builds the conflict form's inputs for a both-sides-edited row: the
     * merged document with the newer side (by REV) winning collisions as
     * the pre-filled default, its field model, the two candidate values of
     * every genuinely conflicted field, and an (always empty, non-null)
     * changed list. An empty {@code alternatives} means nothing needs the
     * user. Pure computation, no transport.
     */
    public JSONObject mergeConflictForm(String base, String local, String remote) {
        return object(Native.mergeConflictForm(base, local, remote));
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

    /**
     * The edit form's view support computed from the field model:
     * summaries, type spinner positions and picker dates. Pure
     * computation, no transport.
     */
    public JSONObject formView(JSONObject model) {
        return object(Native.formView(model.toString()));
    }

    /**
     * One typed entry saved from an edit dialog, its TYPE set drawn
     * from the spinner position. Pure computation, no transport.
     */
    public JSONObject formEntry(String kind, int index, String value, boolean pref) {
        return object(Native.formEntry(kind, index, value, pref));
    }

    /**
     * One picked date on the model wire (the vCard {@code yyyy-mm-dd}
     * form, 1-based month). Pure computation, no transport.
     */
    public String formDate(int year, int month, int day) {
        return string(object(Native.formDate(year, month, day)), "value");
    }

    /**
     * Groups the replica pool ({@code {replicas, links, detached}})
     * into merged contacts, the groups sorted by primary display name.
     * Pure computation, no transport.
     */
    public JSONObject groupContacts(JSONObject input) {
        return object(Native.groupContacts(input.toString()));
    }

    /**
     * The duplicate review's group facts ({@code {key, linkable}})
     * from its {@code {ref, book}} members. Pure computation, no
     * transport.
     */
    public JSONObject duplicateGroup(JSONArray members) {
        return object(Native.duplicateGroup(members.toString()));
    }

    // ---- Bridge reply parsing ----------------------------------------------

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

    /**
     * Parses an object reply, surfacing the bridge's {@code error}
     * field along with the HTTP status riding it, when the failure
     * was an HTTP round.
     */
    static JSONObject object(String json) {
        try {
            JSONObject reply = new JSONObject(json.trim());
            String error = reply.optString("error");
            if (!error.isEmpty()) {
                Integer status = reply.has("status") ? reply.getInt("status") : null;
                throw new CardamumException(error, status);
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
