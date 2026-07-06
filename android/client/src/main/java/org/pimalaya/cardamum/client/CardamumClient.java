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
 * <p>Three backends hide behind the same operations: CardDAV,
 * Microsoft Graph for accounts whose {@link Account#baseUrl} carries
 * the {@link #MSGRAPH_PREFIX} sentinel (Microsoft exposes no CardDAV;
 * the Rust bridge projects Graph contacts to and from vCards), and
 * JMAP for Contacts (RFC 9610) for accounts whose base URL carries the
 * {@link #JMAP_PREFIX} sentinel (the bridge converts JSContact cards
 * to and from vCards). A Graph account authenticates with its OAuth
 * access token in {@link Account#password}; its addressbook URLs are
 * the base URL plus the folder id, so they stay unique across
 * accounts. JMAP addressbook URLs likewise append the AddressBook id
 * to the base URL. Graph and JMAP updates carry no If-Match guard
 * (last-write-wins).
 */
public class CardamumClient {
    /**
     * Sentinel scheme of Microsoft Graph accounts: their base URL is
     * {@code msgraph://<email>} instead of a CardDAV context root.
     */
    public static final String MSGRAPH_PREFIX = "msgraph://";

    /**
     * Sentinel scheme of JMAP accounts: their base URL is
     * {@code jmap://<host>[/path]} instead of a CardDAV context root.
     * The part after the sentinel is the HTTPS session URL (a bare host
     * triggers {@code /.well-known/jmap} discovery).
     */
    public static final String JMAP_PREFIX = "jmap://";

    /** Path segment of the default Contacts folder (empty Graph folder id). */
    private static final String MSGRAPH_DEFAULT_FOLDER = "contacts";
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

    /** Connects, authenticates and lists once to prove the account is usable. */
    public void verify(Account account) {
        listAddressbooks(account);
    }

    /**
     * Lists the account's addressbooks: the CardDAV discovery walk,
     * the Graph contact folders (default Contacts folder first), or
     * the JMAP AddressBooks.
     */
    public List<Addressbook> listAddressbooks(Account account) {
        Transport transport = new Transport();
        try {
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
                            : isJmap(account)
                                    ? Native.listJmapCards(
                                            transport,
                                            jmapSessionUrl(account),
                                            account.login,
                                            account.password,
                                            jmapBook(account, addressbookUrl))
                                    : Native.listCards(
                                            transport,
                                            addressbookUrl,
                                            account.login,
                                            account.password);

            JSONArray parsed = array(reply);
            List<Card> cards = new ArrayList<>(parsed.length());
            for (int index = 0; index < parsed.length(); index++) {
                cards.add(card(object(parsed, index)));
            }
            return cards;
        } finally {
            transport.close();
        }
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
     * server, null when unknown) trims the Graph and JMAP patches to
     * the fields the edit changed; CardDAV PUTs the full vCard and
     * ignores it.
     */
    public Card updateCard(Account account, String addressbookUrl, Card card, String baseVcard) {
        Transport transport = new Transport();
        try {
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
                                        jmapUriOf(card),
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

    /**
     * Patches an edited field model back onto the vCard, preserving
     * every property the model does not manage.
     */
    public String applyCard(String vcard, JSONObject model) {
        return string(object(Native.applyCard(vcard, model.toString())), "vcard");
    }

    /** Deletes the card from the addressbook collection. */
    public void deleteCard(Account account, String addressbookUrl, Card card) {
        Transport transport = new Transport();
        try {
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
                                jmapUriOf(card)));
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

    /** The card's JMAP addressing key: the ContactCard id, no suffix. */
    private static String jmapUriOf(Card card) {
        return card.uri == null ? card.id : card.uri;
    }

    private static boolean isGraph(Account account) {
        return account.baseUrl != null && account.baseUrl.startsWith(MSGRAPH_PREFIX);
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

    /** Parses a `{id, uri, etag, vcard}` reply into a card. */
    private static Card card(JSONObject reply) {
        return new Card(
                string(reply, "id"),
                string(reply, "uri"),
                optString(reply, "etag"),
                string(reply, "vcard"));
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
