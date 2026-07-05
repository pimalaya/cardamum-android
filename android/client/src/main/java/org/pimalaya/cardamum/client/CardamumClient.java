package org.pimalaya.cardamum.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The only surface the app needs: RFC 6764 discovery, the onboarding
 * connection check, and the CardDAV addressbook/card operations. Owns
 * the TLS sockets and the Rust bridge; the app sees neither. Every call
 * blocks, so callers must run it off the main thread.
 */
public class CardamumClient {
    /**
     * Discovers the CardDAV context root for the email, preferring the
     * given {@code tcp://host:port} DNS resolver (null for the default).
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

    /** Connects, authenticates and lists once to prove the account is usable. */
    public void verify(Account account) {
        listAddressbooks(account);
    }

    /** Walks CardDAV discovery and returns the account's addressbooks. */
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

    /** Lists the cards of the addressbook collection at the given URL. */
    public List<Card> listCards(Account account, String addressbookUrl) {
        Transport transport = new Transport();
        try {
            JSONArray reply =
                    array(
                            Native.listCards(
                                    transport, addressbookUrl, account.login, account.password));

            List<Card> cards = new ArrayList<>(reply.length());
            for (int index = 0; index < reply.length(); index++) {
                JSONObject card = object(reply, index);
                cards.add(
                        new Card(
                                string(card, "id"),
                                string(card, "uri"),
                                optString(card, "etag"),
                                string(card, "vcard")));
            }
            return cards;
        } finally {
            transport.close();
        }
    }

    /** Creates the card in the addressbook collection, returning it with its ETag. */
    public Card createCard(Account account, String addressbookUrl, String id, String vcard) {
        Transport transport = new Transport();
        try {
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
            JSONObject reply =
                    object(
                            Native.readCard(
                                    transport,
                                    addressbookUrl,
                                    account.login,
                                    account.password,
                                    uri));
            return new Card(
                    string(reply, "id"),
                    string(reply, "uri"),
                    optString(reply, "etag"),
                    string(reply, "vcard"));
        } finally {
            transport.close();
        }
    }

    /** Updates the card in the addressbook collection, returning it with its new ETag. */
    public Card updateCard(Account account, String addressbookUrl, Card card) {
        Transport transport = new Transport();
        try {
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

    // ---- Bridge reply parsing ----------------------------------------------

    /** Parses an object reply, surfacing the bridge's {@code error} field. */
    private static JSONObject object(String json) {
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

    private static String string(JSONObject object, String key) {
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
