package org.pimalaya.cardamum;

import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Account;
import org.pimalaya.cardamum.client.Card;
import org.pimalaya.cardamum.client.CardDelta;
import org.pimalaya.cardamum.client.CardamumClient;
import org.pimalaya.cardamum.client.OfflineDriver;

/**
 * One account's io-offline driver: the Rust bridge runs the engine's
 * coroutines and this class services their yields, storage against the
 * {@link CardStore} and remote against the backend clients. Every
 * backend enumerates incrementally from its stored cursor (CardDAV
 * sync-collection, Graph delta, JMAP /changes, People sync tokens),
 * falling back to a complete round when the cursor expired; the
 * account-wide deltas of JMAP and Google are projected onto each
 * book's enumerate. {@link #syncBook} is the orchestration: reconcile,
 * hydrate the missing bodies, auto-resolve conflicts by three-way
 * merge, and push the resolutions. Everything blocks; callers run it
 * off the main thread.
 */
final class OfflineEngine implements OfflineDriver {
    private final CardStore base;
    private final CardamumClient client;
    private final Account account;

    /** Book ids by collection URL (account-level membership mapping). */
    private Map<String, String> idByUrl;

    /**
     * The bodies the last delta rounds carried (JMAP and Google list
     * changed cards in full), by handle: the fetch tier reads them
     * back without another round-trip; any push invalidates them.
     */
    private final Map<String, Card> deltaCards = new HashMap<>();

    /** Per-collection card cache of the Graph listing, by handle. */
    private final Map<String, Map<String, Card>> graphCards = new HashMap<>();

    /**
     * The ETags observed by each collection's last enumerate, plus
     * whether that enumerate was complete: what the CardDAV If-Match
     * quirk retry checks the staged base against.
     */
    private final Map<String, Map<String, String>> listedEtags = new HashMap<>();
    private final Map<String, Boolean> listedComplete = new HashMap<>();

    /** What one collection's sync pass did, for the outcome toast. */
    static final class Report {
        int pulled;
        int pushed;
        int merged;
    }

    /**
     * Builds a driver for one account; a null account services storage
     * yields only (local mutations never touch the remote).
     */
    OfflineEngine(CardStore base, CardamumClient client, Account account) {
        this.base = base;
        this.client = client;
        this.account = account;
    }

    /**
     * One collection's engine pass: reconcile with the remote, fetch
     * the bodies the spine still misses, then resolve any conflict by
     * three-way merge (local wins same-field collisions) and push the
     * resolutions with a second reconcile.
     */
    Report syncBook(String url) throws JSONException {
        Report report = new Report();

        JSONObject sync = client.offlineSync(this, url, false);
        report.pulled += sync.optInt("pulled");
        report.pushed += sync.optInt("pushed");
        hydrate(url);

        List<JSONObject> conflicts = base.loadConflicts(url);
        if (!conflicts.isEmpty()) {
            for (JSONObject conflict : conflicts) {
                resolveConflict(url, conflict);
            }
            report.merged += conflicts.size();

            JSONObject retry = client.offlineSync(this, url, false);
            report.pushed += retry.optInt("pushed");
            hydrate(url);
        }

        return report;
    }

    /** Raises every bodiless or stale placement to its full body. */
    private void hydrate(String url) {
        List<String> pending = base.handlesBelowFull(url);
        if (!pending.isEmpty()) {
            client.offlineUpgrade(this, url, pending);
        }
    }

    /**
     * Resolves one conflicted row: both sides three-way merge against
     * the staged base (the local side wins same-field collisions, as
     * before the engine), and the merged card is staged as the
     * resolution, conditioned on the remote state it reconciled.
     */
    private void resolveConflict(String url, JSONObject conflict) throws JSONException {
        String handle = conflict.getString("handle");
        Card remote = client.readCard(account, url, handle);

        String local = conflict.getString("vcard");
        String mergeBase = conflict.isNull("baseVcard") ? local : conflict.getString("baseVcard");
        String merged = client.mergeCardChanges(mergeBase, local, remote.vcard);

        if (remote.etag != null) {
            base.setConflictRevision(url, handle, remote.etag);
        }
        mutateEdit(url, handle, merged);
    }

    /**
     * Stages a content edit on one placement through the engine (the
     * next sync pushes it); editing a conflicted placement resolves it.
     */
    void mutateEdit(String url, String handle, String vcard) throws JSONException {
        JSONObject mutation = new JSONObject();
        mutation.put("op", "edit");
        mutation.put("handle", handle);
        mutation.put("hash", CardStore.byteHash(vcard));
        mutation.put("size", vcard.getBytes(StandardCharsets.UTF_8).length);
        mutation.put("body", vcard);
        mutation.put("meta", client.indexCard(vcard).toString());
        client.offlineMutate(this, url, mutation);
    }

    // ---- Driver seam --------------------------------------------------------

    @Override
    public String serve(String yieldJson) {
        try {
            JSONObject yielded = new JSONObject(yieldJson);
            switch (yielded.getString("op")) {
                case "load":
                    return base.loadCollection(yielded.getString("collection")).toString();
                case "lookup":
                    return base.lookupObjects(yielded.getJSONArray("links")).toString();
                case "write":
                    base.applyWrites(yielded.getJSONArray("writes"));
                    return "{}";
                case "enumerate":
                    return enumerate(yielded).toString();
                case "fetch":
                    return fetch(yielded).toString();
                case "push":
                    return push(yielded).toString();
                default:
                    return error("Unsupported engine yield " + yielded.getString("op"));
            }
        } catch (Exception failure) {
            Log.w("cardamum", "offline driver failed", failure);
            String message = failure.getMessage();
            return error(message == null ? failure.toString() : message);
        }
    }

    /**
     * Services an enumerate yield: the collection's member spine
     * (handle plus content revision), incrementally from the cursor on
     * every backend (RFC 6578 sync-collection, Graph delta, JMAP
     * /changes, People sync tokens). An initial round (no cursor)
     * lists the complete member set and still yields the cursor to
     * delta from next time; a cursor the server no longer accepts
     * falls back to an initial round.
     */
    private JSONObject enumerate(JSONObject yielded) throws JSONException {
        String url = yielded.getString("collection");
        String cursor = yielded.isNull("cursor") ? null : yielded.getString("cursor");

        CardDelta delta;
        try {
            delta = client.syncCards(account, url, cursor);
        } catch (RuntimeException failure) {
            if (cursor != null || CardamumClient.isAccountLevel(account) || isGraph()) {
                throw failure;
            }
            // No sync-collection support on an initial CardDAV sync:
            // fall back to the plain enumeration (a genuine outage
            // fails there too, the same offline fallback as before).
            Log.w("cardamum", "sync-collection failed, falling back to enum", failure);
            return snapshot(url, client.enumCards(account, url), List.of(), true, null);
        }

        boolean complete = cursor == null;
        if (delta.invalidToken) {
            delta = client.syncCards(account, url, null);
            if (delta.invalidToken) {
                // Never treat a still-rejected initial round as an
                // empty collection: that would read as remote-deleted.
                throw new IllegalStateException("Initial sync round rejected for " + url);
            }
            complete = true;
        }

        for (Card card : delta.changed) {
            if (!card.vcard.isEmpty()) {
                deltaCards.put(card.uri, card);
            }
        }

        // Graph delta rows carry no body: a complete round primes the
        // pass's body cache with one full listing instead of one read
        // per card. The delta link predates the listing, so anything
        // changed in between simply re-lists on the next round.
        if (isGraph() && complete && !delta.changed.isEmpty()) {
            Map<String, Card> byHandle = new HashMap<>();
            for (Card card : client.listCards(account, url)) {
                byHandle.put(card.uri, card);
            }
            graphCards.put(url, byHandle);
        }

        if (CardamumClient.isAccountLevel(account)) {
            return accountSnapshot(url, delta, complete);
        }
        return snapshot(url, delta.changed, delta.vanished, complete, delta.token);
    }

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book's
     * enumerate: cards member of the book are its items, and on an
     * incremental round a changed card that left the book (still held
     * locally but no longer listing it) rides as vanished.
     */
    private JSONObject accountSnapshot(String url, CardDelta delta, boolean complete)
            throws JSONException {
        String bookId = bookId(url);
        List<Card> members = new ArrayList<>();
        List<String> vanished = new ArrayList<>(delta.vanished);

        for (Card card : delta.changed) {
            if (card.books.contains(bookId)) {
                members.add(card);
            } else if (!complete && base.loadRow(url, card.uri) != null) {
                vanished.add(card.uri);
            }
        }

        return snapshot(url, members, vanished, complete, delta.token);
    }

    /** Builds an enumerate reply, recording the ETags for the 412 quirk. */
    private JSONObject snapshot(
            String url, List<Card> cards, List<String> vanished, boolean complete, String token)
            throws JSONException {
        Map<String, String> etags = new HashMap<>();
        JSONArray items = new JSONArray();
        for (Card card : cards) {
            JSONObject item = new JSONObject();
            item.put("handle", card.uri);
            if (card.etag != null) {
                item.put("revision", card.etag);
            }
            items.put(item);
            etags.put(card.uri, card.etag);
        }
        listedEtags.put(url, etags);
        listedComplete.put(url, complete);

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        reply.put("vanished", new JSONArray(vanished));
        reply.put("complete", complete);
        if (token != null) {
            reply.put("checkpoint", token);
        }
        return reply;
    }

    /**
     * Services a fetch yield: the full bodies of the given handles,
     * each with its link id (the vCard UID), cached summary (the
     * write-time index) and content hash.
     */
    private JSONObject fetch(JSONObject yielded) throws JSONException {
        String url = yielded.getString("collection");
        JSONArray handles = yielded.getJSONArray("handles");

        JSONArray items = new JSONArray();
        if (CardamumClient.isAccountLevel(account)) {
            for (int index = 0; index < handles.length(); index++) {
                String handle = handles.getString(index);
                Card card = deltaCards.get(handle);
                if (card == null) {
                    card = client.readCard(account, url, handle);
                }
                items.put(fetchedItem(handle, card));
            }
        } else if (isGraph()) {
            Map<String, Card> cached = graphCards.get(url);
            for (int index = 0; index < handles.length(); index++) {
                String handle = handles.getString(index);
                Card card = cached == null ? null : cached.get(handle);
                if (card == null) {
                    card = client.readCard(account, url, handle);
                }
                items.put(fetchedItem(handle, card));
            }
        } else {
            // CardDAV: multiget in chunks, matched back by resource name.
            List<String> uris = new ArrayList<>(handles.length());
            for (int index = 0; index < handles.length(); index++) {
                uris.add(handles.getString(index));
            }
            for (int start = 0; start < uris.size(); start += MULTIGET_CHUNK) {
                List<String> chunk =
                        uris.subList(start, Math.min(start + MULTIGET_CHUNK, uris.size()));
                for (Card card : client.multigetCards(account, url, chunk)) {
                    items.put(fetchedItem(card.uri, card));
                }
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
    }

    /** One fetched card on the engine wire. */
    private JSONObject fetchedItem(String handle, Card card) throws JSONException {
        JSONObject index = client.indexCard(card.vcard);
        String uid = index.optString("uid");

        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("linkId", uid.isEmpty() ? handle : uid);
        item.put("meta", index.toString());
        item.put("hash", CardStore.byteHash(card.vcard));
        item.put("body", card.vcard);
        if (card.etag != null) {
            item.put("revision", card.etag);
        }
        return item;
    }

    /**
     * Services a push yield: creates, content updates, deletes and
     * membership patches, one result per change. An optimistic
     * concurrency failure (HTTP 412) retries unguarded when the last
     * enumerate proves the remote unchanged (some servers, posteo's
     * SabreDAV among them, serve listing ETags their If-Match never
     * matches), and reports the change rejected otherwise so the next
     * sync reconciles it; any other failure aborts the pass (offline
     * fallback).
     */
    private JSONObject push(JSONObject yielded) throws JSONException {
        String url = yielded.getString("collection");
        JSONArray changes = yielded.getJSONArray("changes");

        JSONArray results = new JSONArray();
        for (int index = 0; index < changes.length(); index++) {
            JSONObject change = changes.getJSONObject(index);
            switch (change.getString("op")) {
                case "add":
                    results.put(pushAdd(url, change));
                    break;
                case "update":
                    results.put(pushUpdate(url, change));
                    break;
                case "remove":
                    results.put(pushRemove(url, change));
                    break;
                default:
                    // No flag pushes on any contacts backend.
                    results.put(result(change.getString("handle"), true, null, null));
                    break;
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("results", results);
        return reply;
    }

    /**
     * Pushes a pending create: a membership patch when the body
     * already lives on the account (add with an origin), a genuine
     * create otherwise.
     */
    private JSONObject pushAdd(String url, JSONObject change) throws JSONException {
        String handle = change.getString("handle");
        JSONObject row = base.loadRow(url, handle);
        if (row == null) {
            return result(handle, false, null, null);
        }

        if (!change.isNull("origin") && CardamumClient.isAccountLevel(account)) {
            client.updateCardBooks(account, row.getString("id"), List.of(bookId(url)), List.of());
            invalidate(url);
            return result(handle, true, handle, null);
        }

        Card created =
                client.createCard(account, url, row.getString("id"), row.getString("vcard"));
        invalidate(url);

        // Google creates land in the myContacts system group; a create
        // aimed at another group patches the membership right after.
        if (isGoogle()) {
            String bookId = bookId(url);
            if (bookId != null && !"myContacts".equals(bookId)) {
                client.updateCardBooks(account, created.id, List.of(bookId), List.of());
            }
        }

        return result(handle, true, created.uri, created.etag);
    }

    /** Pushes a staged content edit, guarded by the base revision. */
    private JSONObject pushUpdate(String url, JSONObject change) throws JSONException {
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");
        JSONObject row = base.loadRow(url, handle);
        if (row == null) {
            return result(handle, false, null, null);
        }
        String baseVcard = row.isNull("baseVcard") ? null : row.getString("baseVcard");

        try {
            Card updated =
                    client.updateCard(
                            account,
                            url,
                            new Card(row.getString("id"), handle, ifMatch, row.getString("vcard")),
                            baseVcard);
            invalidate(url);
            return result(handle, true, null, updated.etag);
        } catch (RuntimeException failure) {
            if (!isPreconditionFailure(failure)) {
                throw failure;
            }
            if (!listingUnchanged(url, handle, ifMatch)) {
                return result(handle, false, null, null);
            }

            // The If-Match quirk: the enumerate moments ago proves the
            // remote unchanged, so the unguarded write carries the same
            // guarantee, enforced by us instead.
            Log.w(
                    "cardamum",
                    "If-Match rejected but listing unchanged, retrying without it: " + handle);
            Card updated =
                    client.updateCard(
                            account,
                            url,
                            new Card(row.getString("id"), handle, null, row.getString("vcard")),
                            baseVcard);
            invalidate(url);
            return result(handle, true, null, updated.etag);
        }
    }

    /**
     * Pushes a staged removal: a membership patch when the card is not
     * deleted and other books still hold it (account-level backends),
     * the card's deletion otherwise.
     */
    private JSONObject pushRemove(String url, JSONObject change) throws JSONException {
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");
        JSONObject row = base.loadRow(url, handle);
        if (row == null) {
            return result(handle, true, null, null);
        }

        if (CardamumClient.isAccountLevel(account) && !row.optBoolean("deleted")) {
            client.updateCardBooks(account, row.getString("id"), List.of(), List.of(bookId(url)));
            invalidate(url);
            return result(handle, true, null, null);
        }

        try {
            client.deleteCard(account, url, new Card(row.getString("id"), handle, ifMatch, ""));
        } catch (RuntimeException failure) {
            if (isGone(failure)) {
                // Already deleted upstream; the removal converged.
            } else if (!isPreconditionFailure(failure)) {
                throw failure;
            } else if (!listingUnchanged(url, handle, ifMatch)) {
                return result(handle, false, null, null);
            } else {
                Log.w(
                        "cardamum",
                        "If-Match rejected but listing unchanged, retrying without it: " + handle);
                client.deleteCard(account, url, new Card(row.getString("id"), handle, null, ""));
            }
        }
        invalidate(url);
        return result(handle, true, null, null);
    }

    /** One push result on the engine wire. */
    private static JSONObject result(
            String handle, boolean accepted, String assigned, String revision)
            throws JSONException {
        JSONObject result = new JSONObject();
        result.put("handle", handle);
        result.put("accepted", accepted);
        if (assigned != null) {
            result.put("assigned", assigned);
        }
        if (revision != null) {
            result.put("revision", revision);
        }
        return result;
    }

    // ---- Backend plumbing ---------------------------------------------------

    /** How many resource names one addressbook-multiget carries. */
    private static final int MULTIGET_CHUNK = 50;

    /** Drops the pass caches after a push changed the remote. */
    private void invalidate(String url) {
        deltaCards.clear();
        graphCards.remove(url);
    }

    /** The book id behind a collection URL (membership patches address it). */
    private String bookId(String url) {
        if (idByUrl == null) {
            idByUrl = new HashMap<>();
            for (BookEntry entry : base.loadAllAddressbooks()) {
                idByUrl.put(entry.book.url, entry.book.id);
            }
        }
        return idByUrl.get(url);
    }

    /**
     * Whether the last enumerate proves the handle unchanged at the
     * staged base revision: listed with that very ETag, or unlisted by
     * a delta (a delta only lists what changed).
     */
    private boolean listingUnchanged(String url, String handle, String ifMatch) {
        Map<String, String> etags = listedEtags.get(url);
        if (etags == null || ifMatch == null) {
            return false;
        }
        if (etags.containsKey(handle)) {
            return ifMatch.equals(etags.get(handle));
        }
        return !Boolean.TRUE.equals(listedComplete.get(url));
    }

    private static boolean isPreconditionFailure(Exception failure) {
        String message = failure.getMessage();
        return message != null && message.contains("HTTP 412");
    }

    private static boolean isGone(Exception failure) {
        String message = failure.getMessage();
        return message != null && message.contains("HTTP 404");
    }

    private boolean isGraph() {
        return account != null
                && account.baseUrl != null
                && account.baseUrl.startsWith(CardamumClient.MSGRAPH_PREFIX);
    }

    private boolean isGoogle() {
        return account != null
                && account.baseUrl != null
                && account.baseUrl.startsWith(CardamumClient.GOOGLE_PREFIX);
    }

    private static String error(String message) {
        JSONObject reply = new JSONObject();
        try {
            reply.put("error", message);
        } catch (JSONException ignored) {
            return "{\"error\": \"driver failure\"}";
        }
        return reply.toString();
    }
}
