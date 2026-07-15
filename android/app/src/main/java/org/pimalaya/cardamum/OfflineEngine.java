package org.pimalaya.cardamum;

import android.content.Context;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Account;
import org.pimalaya.cardamum.client.Card;
import org.pimalaya.cardamum.client.CardDelta;
import org.pimalaya.cardamum.client.CardamumClient;
import org.pimalaya.cardamum.client.CardamumException;
import org.pimalaya.cardamum.client.OfflineDriver;

/**
 * One account's io-offline driver: the Rust bridge runs the engine's
 * coroutines and this class services their yields, storage against the
 * {@link CardStore} and remote against the backend clients or, for a
 * book's phone collection, against its raw contacts through the
 * {@link PhoneRemote} adapter. Every server backend enumerates
 * incrementally from its stored cursor (CardDAV sync-collection, Graph
 * delta, JMAP /changes, People sync tokens), falling back to a
 * complete round when the cursor expired; the account-wide deltas of
 * JMAP and Google are projected onto each book's enumerate.
 * {@link #syncBook} is the orchestration: a phone pass pulling the
 * contacts app's edits into the hub, the server exchange, then a
 * second phone pass projecting what the server round brought; each
 * pass reconciles, hydrates the missing bodies, auto-resolves
 * conflicts by three-way merge and pushes the resolutions. Everything
 * blocks; callers run it off the main thread.
 */
final class OfflineEngine implements OfflineDriver {
    /**
     * One lock per book URL, process-wide: the in-app sync, the
     * background worker and the OS-scheduled sync service can each
     * run an engine pass over the same book, and a pass spans many
     * store transactions, so interleaved passes could double-push
     * staged creates or clobber the sync checkpoint. Reentrant, so
     * syncBook's own phone passes nest freely under its hold.
     */
    private static final ConcurrentHashMap<String, ReentrantLock> SYNC_LOCKS =
            new ConcurrentHashMap<>();

    private final CardStore base;
    private final CardamumClient client;
    private final Account account;

    /** The phone spoke's adapter; null on context-less (mutate) drivers. */
    private final PhoneRemote phone;

    /**
     * The report the running sync flows tally into (the driver seam
     * feeds it as pushes are accepted and writes land); null outside
     * syncs, so app-side mutations count nothing.
     */
    private Report tally;

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

    /**
     * What a sync did to the cards, for the outcome report: distinct
     * affected cards per axis (the phone and the server tallied
     * apart), regardless of direction. A card counts once however many
     * passes touched it: in = cards that appeared, out = cards that
     * were deleted, changed = cards whose content changed; plus the
     * conflicts left for the user.
     */
    static final class Report {
        final Set<String> localIn = new HashSet<>();
        final Set<String> localOut = new HashSet<>();
        final Set<String> localChanged = new HashSet<>();
        final Set<String> remoteIn = new HashSet<>();
        final Set<String> remoteOut = new HashSet<>();
        final Set<String> remoteChanged = new HashSet<>();
        int conflicts;

        /** Tallies one effect on one card, deduped per card and axis. */
        void tally(String collection, String handle, String kind) {
            boolean phone = CardStore.isPhoneCollection(collection);
            Set<String> in = phone ? localIn : remoteIn;
            Set<String> out = phone ? localOut : remoteOut;
            Set<String> changed = phone ? localChanged : remoteChanged;
            String key = collection + "\n" + handle;

            switch (kind) {
                case "created":
                    in.add(key);
                    // A new card's follow-up writes (hydration, base
                    // captures) stay part of its appearance.
                    changed.remove(key);
                    break;
                case "removed":
                    out.add(key);
                    break;
                case "changed":
                    if (!in.contains(key)) {
                        changed.add(key);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Observes the sync's coarse steps for a progress display; steps
     * fire on the sync thread. Null when the sync runs headless (the
     * background worker, the OS-scheduled adapter).
     */
    interface Progress {
        /** Exchanging the spine with the server. */
        int STAGE_SERVER = 0;
        /** Downloading `count` bodies from the server. */
        int STAGE_DOWNLOAD = 1;
        /** Sending `count` changes to the server. */
        int STAGE_UPLOAD = 2;
        /** Reconciling with the phone's contacts. */
        int STAGE_PHONE = 3;
        /** Writing `count` contacts to the phone. */
        int STAGE_PROJECT = 4;
        /** Resolving `count` conflicts. */
        int STAGE_RESOLVE = 5;

        void step(int stage, int count);
    }

    /** The foreground sync's progress observer; null when headless. */
    Progress progress;

    private void step(int stage, int count) {
        if (progress != null) {
            progress.step(stage, count);
        }
    }

    /**
     * Builds a driver for one account; a null account services storage
     * yields only (local mutations never touch the remote), a null
     * context disables the phone spoke.
     */
    OfflineEngine(CardStore base, CardamumClient client, Account account, Context context) {
        this.base = base;
        this.client = client;
        this.account = account;
        this.phone = context == null ? null : new PhoneRemote(context, base, client);
    }

    /**
     * One book's full hub sync, three engine passes: phone (pull the
     * contacts app's edits into the hub), server (exchange with the
     * remote; the phone-won hub divergences push along), phone again
     * (project what the server round brought; local-only, cheap). A
     * book whose remote switch is off keeps only the first phone pass:
     * the hub still converges with the Contacts app, nothing touches
     * the server.
     */
    Report syncBook(String url, boolean remote) throws JSONException {
        ReentrantLock lock = syncLock(url);
        lock.lock();
        try {
            Report report = new Report();
            syncPhone(url, report);
            if (remote) {
                syncServer(url, report);
                syncPhone(url, report);
            }
            return report;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The server collection's engine pass: reconcile with the remote,
     * fetch the bodies the spine still misses, then triage every
     * both-sides-edited row right here: a divergence whose three-way
     * merge needs no user choice resolves on the spot and pushes in a
     * second reconcile, and only genuine same-field collisions stay
     * conflicted (remote body captured) for the in-app resolution
     * form. So a conflict the user sees is always one the form has
     * something to ask about; the rest self-heals during the sync.
     */
    private void syncServer(String url, Report report) throws JSONException {
        tally = report;
        step(Progress.STAGE_SERVER, 0);
        Log.d("cardamum", "server sync " + url + ": " + client.offlineSync(this, url, false));
        hydrate(url);

        List<JSONObject> conflicts = base.loadConflicts(url);
        if (!conflicts.isEmpty()) {
            step(Progress.STAGE_RESOLVE, conflicts.size());
        }
        int resolved = 0;
        for (JSONObject conflict : conflicts) {
            if (resolveCleanConflict(url, conflict)) {
                resolved += 1;
            }
        }
        report.conflicts += conflicts.size() - resolved;

        if (resolved > 0) {
            client.offlineSync(this, url, false);
            hydrate(url);
        }
    }

    /**
     * The phone collection's engine pass, same shape as the server
     * one: reconcile the hub with the book's raw contacts, hydrate the
     * bodies the phone round brought, resolve divergences by the same
     * three-way merge. Skipped silently when the spoke is unavailable
     * (no contacts permission, or no Android account yet: "Sync local"
     * creates them).
     */
    void syncPhone(String url, Report report) throws JSONException {
        if (phone == null || !phone.available(url)) {
            return;
        }

        ReentrantLock lock = syncLock(url);
        lock.lock();
        try {
            // The quiet path: nothing to ingest phone-side (no dirty,
            // new or deleted raw contact), nothing staged hub-side and
            // both sides count the same members, so the engine pass
            // (placement load, enumerate, per-row bridge calls) would
            // reconcile nothing. Three cheap counts stand in for it;
            // ContactsContract has no per-account changes token to
            // compare instead.
            if (!phone.changed(url)
                    && !base.phonePending(url)
                    && phone.count(url) == base.phoneMemberCount(url)) {
                return;
            }

            tally = report;
            String collection = CardStore.phoneCollection(url);
            step(Progress.STAGE_PHONE, 0);

            Log.d(
                    "cardamum",
                    "phone sync " + url + ": " + client.offlineSync(this, collection, false));
            hydrate(collection);

            List<JSONObject> conflicts = base.loadConflicts(collection);
            if (!conflicts.isEmpty()) {
                step(Progress.STAGE_RESOLVE, conflicts.size());
                for (JSONObject conflict : conflicts) {
                    resolvePhoneConflict(collection, conflict);
                }

                client.offlineSync(this, collection, false);
                hydrate(collection);
            }
        } finally {
            lock.unlock();
        }
    }

    /** The book's process-wide sync lock, keyed by its collection URL. */
    private static ReentrantLock syncLock(String url) {
        return SYNC_LOCKS.computeIfAbsent(url, key -> new ReentrantLock());
    }

    /** Raises every bodiless or stale placement to its full body. */
    private void hydrate(String url) {
        List<String> pending = base.handlesBelowFull(url);
        if (!pending.isEmpty()) {
            if (!CardStore.isPhoneCollection(url)) {
                step(Progress.STAGE_DOWNLOAD, pending.size());
            }
            Log.d(
                    "cardamum",
                    "hydrate " + url + " (" + pending.size() + " below full): "
                            + client.offlineUpgrade(this, url, pending));
        }
    }

    /**
     * Captures one conflicted row's remote side and resolves it on the
     * spot when the three-way merge needs no user choice: lists merge
     * as sets and one-sided scalar changes flow in, so only a field
     * both sides edited, differently, is the user's to settle. A clean
     * merge stages as the resolution (the caller's second reconcile
     * pushes it) and reports true; a genuine collision leaves the row
     * conflicted, its remote body persisted for the resolution form.
     */
    private boolean resolveCleanConflict(String url, JSONObject conflict) throws JSONException {
        String handle = conflict.getString("handle");
        Card remote = client.readCard(account, url, handle);

        // Persist only the remote body, for the resolution form. The
        // revision the resolving push guards on stays the one the engine
        // recorded from the enumerate (its own axis): overriding it with
        // this read's etag breaks backends whose read etag differs from the
        // list etag (Google People API), so the push guards on a foreign
        // revision and the server rejects it.
        base.setConflictRemote(url, handle, remote.vcard);

        // The base falls back to the local body when it was never
        // captured (a create collision): the local side then reads as
        // unchanged and the remote changes flow in cleanly, like the
        // resolution form would.
        String local = conflict.getString("vcard");
        String baseVcard =
                conflict.isNull("baseVcard") ? local : conflict.getString("baseVcard");
        JSONObject resolution = client.mergeConflictForm(baseVcard, local, remote.vcard);

        JSONObject alternatives = resolution.optJSONObject("alternatives");
        if (alternatives != null && alternatives.length() > 0) {
            return false;
        }

        mutateEdit(url, handle, resolution.optString("vcard"));
        return true;
    }

    /**
     * Resolves one phone-conflicted row the same way, the remote side
     * being the raw contact read back through the fetch boundary.
     */
    private void resolvePhoneConflict(String collection, JSONObject conflict)
            throws JSONException {
        String handle = conflict.getString("handle");
        JSONObject remote = phone.read(collection, handle);
        if (remote == null) {
            // The raw contact vanished mid-conflict; the next sync
            // reconciles the removal.
            return;
        }

        String merged =
                client.mergeCardChanges(
                        conflict.isNull("baseVcard") ? "" : conflict.getString("baseVcard"),
                        conflict.getString("vcard"),
                        remote.getString("body"));

        if (!remote.isNull("revision")) {
            base.setConflictRevision(collection, handle, remote.getString("revision"));
        }
        mutateEdit(collection, handle, merged);
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
                    JSONArray effects = base.applyWrites(yielded.getJSONArray("writes"));
                    for (int index = 0; tally != null && index < effects.length(); index++) {
                        JSONObject effect = effects.getJSONObject(index);
                        tally.tally(
                                effect.getString("collection"),
                                effect.getString("handle"),
                                effect.getString("kind"));
                    }
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
            return error(failure);
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
        if (CardStore.isPhoneCollection(url)) {
            return phone.enumerate(url);
        }
        String cursor = yielded.isNull("cursor") ? null : yielded.getString("cursor");

        CardDelta delta = client.syncCards(account, url, cursor);

        for (Card card : delta.changed) {
            if (!card.vcard.isEmpty()) {
                deltaCards.put(card.uri, card);
            }
        }

        // Graph delta rows carry no body: a complete round primes the
        // pass's body cache with one full listing instead of one read
        // per card. The delta link predates the listing, so anything
        // changed in between simply re-lists on the next round.
        if (isGraph() && delta.complete && !delta.changed.isEmpty()) {
            Map<String, Card> byHandle = new HashMap<>();
            for (Card card : client.listCards(account, url)) {
                byHandle.put(card.uri, card);
            }
            graphCards.put(url, byHandle);
        }

        if (CardamumClient.isAccountLevel(account)) {
            return accountSnapshot(url, delta);
        }
        return snapshot(url, delta.changed, delta.vanished, delta.complete, delta.token);
    }

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book's
     * enumerate through the bridge: cards member of the book are its
     * items, and on an incremental round a changed card that left the
     * book (still held locally but no longer listing it) rides as
     * vanished.
     */
    private JSONObject accountSnapshot(String url, CardDelta delta) throws JSONException {
        JSONArray changed = new JSONArray();
        for (Card card : delta.changed) {
            changed.put(
                    new JSONObject()
                            .put("handle", card.uri)
                            .put("books", new JSONArray(card.books))
                            .put(
                                    "known",
                                    !delta.complete && base.loadRow(url, card.uri) != null));
        }
        JSONObject facts = new JSONObject();
        facts.put("bookId", bookId(url));
        facts.put("complete", delta.complete);
        facts.put("changed", changed);
        facts.put("vanished", new JSONArray(delta.vanished));

        JSONObject projected = client.offlineAccountSnapshot(facts);
        List<Card> members = new ArrayList<>();
        JSONArray indexes = projected.optJSONArray("members");
        for (int at = 0; indexes != null && at < indexes.length(); at++) {
            members.add(delta.changed.get(indexes.optInt(at)));
        }
        List<String> vanished = new ArrayList<>();
        JSONArray gone = projected.optJSONArray("vanished");
        for (int at = 0; gone != null && at < gone.length(); at++) {
            vanished.add(gone.optString(at));
        }

        return snapshot(url, members, vanished, delta.complete, delta.token);
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
        if (CardStore.isPhoneCollection(url)) {
            return phone.fetch(url, handles);
        }

        JSONArray items = new JSONArray();
        if (CardamumClient.isAccountLevel(account)) {
            for (int index = 0; index < handles.length(); index++) {
                String handle = handles.getString(index);
                Card card = deltaCards.get(handle);
                if (card == null) {
                    card = client.readCard(account, url, handle);
                }
                items.put(fetchedItem(url, handle, card));
            }
        } else if (isGraph()) {
            Map<String, Card> cached = graphCards.get(url);
            for (int index = 0; index < handles.length(); index++) {
                String handle = handles.getString(index);
                Card card = cached == null ? null : cached.get(handle);
                if (card == null) {
                    card = client.readCard(account, url, handle);
                }
                items.put(fetchedItem(url, handle, card));
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
                    items.put(fetchedItem(url, card.uri, card));
                }
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
    }

    /**
     * One fetched card on the engine wire. The recorded revision must
     * be the flavour the next enumerate compares against: posteo's
     * SabreDAV serves multiget ETags that differ from its listing
     * ETags for some cards (Google's people.get ETag likewise differs
     * from its listing's), and recording the fetch flavour ping-pongs
     * those cards through a refresh-and-refetch on every sync forever.
     * The ETag the pass's own enumerate listed wins; the fetched one
     * only stands in when the pass never listed the handle. Content
     * moving between the listing and the fetch records the older
     * listing ETag against the newer body, which reads as one more
     * refresh next sync and then converges.
     */
    private JSONObject fetchedItem(String url, String handle, Card card) throws JSONException {
        JSONObject index = client.indexCard(card.vcard);
        String uid = index.optString("uid");

        Map<String, String> listed = listedEtags.get(url);
        String listedEtag = listed == null ? null : listed.get(handle);
        String revision = listedEtag != null ? listedEtag : card.etag;

        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("linkId", uid.isEmpty() ? handle : uid);
        item.put("meta", index.toString());
        item.put("hash", CardStore.byteHash(card.vcard));
        item.put("body", card.vcard);
        if (revision != null) {
            item.put("revision", revision);
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
        if (CardStore.isPhoneCollection(url)) {
            step(Progress.STAGE_PROJECT, changes.length());
            JSONObject reply = phone.push(url, changes);
            tallyPushes(url, changes, reply.getJSONArray("results"));
            return reply;
        }
        step(Progress.STAGE_UPLOAD, changes.length());

        // CardDAV has no batch verb, so its round parallelizes instead:
        // every change stays one guarded HTTP round trip on its own
        // connection (the client opens one transport per call), and on
        // a latency-bound link the wall clock divides by the pool.
        boolean concurrent =
                changes.length() > 1
                        && !CardamumClient.isAccountLevel(account)
                        && !isGraph();

        JSONArray results;
        try {
            if (isGoogle() && changes.length() > 1) {
                results = pushGoogle(url, changes);
            } else if ((isJmap() || isGraph()) && changes.length() > 1) {
                results = pushRound(url, changes);
            } else {
                results = concurrent ? pushAll(url, changes) : pushEach(url, changes);
            }
        } finally {
            // One pass-cache drop per round instead of per change: no
            // fetch interleaves a push yield, so deferring is
            // equivalent, and the workers must not race the caches.
            invalidate(url);
        }
        tallyPushes(url, changes, results);

        JSONObject reply = new JSONObject();
        reply.put("results", results);
        return reply;
    }

    /** One round's changes, sequentially (the batch-verb backends). */
    private JSONArray pushEach(String url, JSONArray changes) throws JSONException {
        JSONArray results = new JSONArray();
        for (int index = 0; index < changes.length(); index++) {
            results.put(pushOne(url, changes.getJSONObject(index)));
        }
        return results;
    }

    /**
     * One round's changes, concurrently over a small pool (CardDAV).
     * Results keep the change order; the first hard failure aborts the
     * round like its sequential counterpart, dropping the still-flying
     * requests with it (the next sync reconciles whatever landed).
     */
    private JSONArray pushAll(String url, JSONArray changes) throws JSONException {
        // The book map primes single-threaded; the workers only read it.
        bookId(url);

        ExecutorService pool =
                Executors.newFixedThreadPool(Math.min(PUSH_CONCURRENCY, changes.length()));
        try {
            List<Future<JSONObject>> pending = new ArrayList<>(changes.length());
            for (int index = 0; index < changes.length(); index++) {
                JSONObject change = changes.getJSONObject(index);
                pending.add(pool.submit(() -> pushOne(url, change)));
            }

            JSONArray results = new JSONArray();
            for (Future<JSONObject> future : pending) {
                results.put(future.get());
            }
            return results;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof JSONException) {
                throw (JSONException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * One Google round: the genuine creates and deletes group into the
     * People batch verbs (200 and 500 a call, one write-quota unit per
     * call instead of one per card, which throttled large rounds);
     * membership patches and guarded updates stay per-card. Results
     * keep the change order, and a failed batch aborts the round like
     * a failed per-card call would.
     */
    private JSONArray pushGoogle(String url, JSONArray changes) throws JSONException {
        JSONObject[] results = new JSONObject[changes.length()];

        List<Integer> creates = new ArrayList<>();
        List<String> createVcards = new ArrayList<>();
        List<JSONArray> createBooks = new ArrayList<>();
        List<Integer> removes = new ArrayList<>();
        List<String> removeIds = new ArrayList<>();

        for (int index = 0; index < changes.length(); index++) {
            JSONObject change = changes.getJSONObject(index);
            String op = change.getString("op");
            String handle = change.getString("handle");

            if ("add".equals(op)) {
                JSONObject row = base.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, false, null, null);
                    continue;
                }
                JSONObject plan = pushPlan("add", url, !change.isNull("origin"), false);
                if ("membership".equals(plan.getString("action"))) {
                    client.updateCardBooks(
                            account, row.getString("id"), List.of(bookId(url)), List.of());
                    results[index] = result(handle, true, handle, null);
                    continue;
                }
                creates.add(index);
                createVcards.add(row.getString("vcard"));
                createBooks.add(plan.optJSONArray("postCreateBooks"));
            } else if ("remove".equals(op)) {
                JSONObject row = base.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, true, null, null);
                    continue;
                }
                JSONObject plan = pushPlan("remove", url, false, row.optBoolean("deleted"));
                if ("membership".equals(plan.getString("action"))) {
                    client.updateCardBooks(
                            account, row.getString("id"), List.of(), List.of(bookId(url)));
                    results[index] = result(handle, true, null, null);
                    continue;
                }
                removes.add(index);
                removeIds.add(row.getString("id"));
            } else {
                results[index] = pushOne(url, change);
            }
        }

        if (!creates.isEmpty()) {
            List<Card> created = client.createCards(account, createVcards);
            for (int at = 0; at < creates.size(); at++) {
                Card card = created.get(at);
                int index = creates.get(at);

                JSONArray postCreate = createBooks.get(at);
                for (int book = 0; postCreate != null && book < postCreate.length(); book++) {
                    client.updateCardBooks(
                            account, card.id, List.of(postCreate.optString(book)), List.of());
                }
                results[index] =
                        result(
                                changes.getJSONObject(index).getString("handle"),
                                true,
                                card.uri,
                                card.etag);
            }
        }

        if (!removes.isEmpty()) {
            client.deleteCards(account, removeIds);
            for (int index : removes) {
                results[index] =
                        result(changes.getJSONObject(index).getString("handle"), true, null, null);
            }
        }

        JSONArray ordered = new JSONArray();
        for (JSONObject result : results) {
            ordered.put(result);
        }
        return ordered;
    }

    /**
     * One JMAP or Graph round through the bridge's batch seam: JMAP
     * folds the whole round (creates, content updates, membership
     * patches, destroys) into ContactCard/set calls, Graph into $batch
     * calls of twenty, both answering one outcome per change, so a
     * rejected card no longer aborts its round. Results keep the
     * change order.
     */
    private JSONArray pushRound(String url, JSONArray changes) throws JSONException {
        JSONObject[] results = new JSONObject[changes.length()];
        Map<String, Integer> indexByRef = new HashMap<>();
        JSONArray batch = new JSONArray();

        for (int index = 0; index < changes.length(); index++) {
            JSONObject change = changes.getJSONObject(index);
            String op = change.getString("op");
            String handle = change.getString("handle");

            if ("add".equals(op)) {
                JSONObject row = base.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, false, null, null);
                    continue;
                }
                JSONObject plan = pushPlan("add", url, !change.isNull("origin"), false);
                JSONObject item = new JSONObject();
                item.put("ref", handle);
                if ("membership".equals(plan.getString("action"))) {
                    item.put("op", "books");
                    item.put("id", row.getString("id"));
                    item.put("add", new JSONArray().put(bookId(url)));
                } else {
                    item.put("op", "create");
                    item.put("vcard", row.getString("vcard"));
                }
                indexByRef.put(handle, index);
                batch.put(item);
            } else if ("update".equals(op)) {
                JSONObject row = base.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, false, null, null);
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("ref", handle);
                item.put("op", "update");
                item.put("id", row.getString("id"));
                item.put("vcard", row.getString("vcard"));
                if (!row.isNull("baseVcard")) {
                    item.put("baseVcard", row.getString("baseVcard"));
                }
                indexByRef.put(handle, index);
                batch.put(item);
            } else if ("remove".equals(op)) {
                JSONObject row = base.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, true, null, null);
                    continue;
                }
                JSONObject plan = pushPlan("remove", url, false, row.optBoolean("deleted"));
                JSONObject item = new JSONObject();
                item.put("ref", handle);
                item.put("id", row.getString("id"));
                if ("membership".equals(plan.getString("action"))) {
                    item.put("op", "books");
                    item.put("remove", new JSONArray().put(bookId(url)));
                } else {
                    item.put("op", "destroy");
                }
                indexByRef.put(handle, index);
                batch.put(item);
            } else {
                // No flag pushes on any contacts backend.
                results[index] = result(handle, true, null, null);
            }
        }

        if (batch.length() > 0) {
            JSONArray replies = client.pushCards(account, url, batch);
            for (int at = 0; at < replies.length(); at++) {
                JSONObject reply = replies.getJSONObject(at);
                String ref = reply.getString("ref");
                Integer index = indexByRef.get(ref);
                if (index == null) {
                    continue;
                }

                boolean accepted = reply.optBoolean("accepted");
                if (!accepted && !reply.isNull("error")) {
                    Log.w("cardamum", "push rejected for " + ref + ": " + reply.optString("error"));
                }

                // Only an add renames its handle: the created id when
                // the backend assigned one, the handle itself on a
                // membership add (mirrors the per-card path).
                String assigned = null;
                if ("add".equals(changes.getJSONObject(index).getString("op")) && accepted) {
                    assigned = reply.isNull("id") ? ref : reply.getString("id");
                }
                String revision = reply.isNull("etag") ? null : reply.getString("etag");
                results[index] = result(ref, accepted, assigned, revision);
            }

            // A ref the bridge failed to answer counts rejected, so the
            // next sync reconciles it rather than trusting silence.
            for (Map.Entry<String, Integer> entry : indexByRef.entrySet()) {
                if (results[entry.getValue()] == null) {
                    results[entry.getValue()] = result(entry.getKey(), false, null, null);
                }
            }
        }

        JSONArray ordered = new JSONArray();
        for (JSONObject result : results) {
            ordered.put(result);
        }
        return ordered;
    }

    /** Dispatches one push change to its verb. */
    private JSONObject pushOne(String url, JSONObject change) throws JSONException {
        switch (change.getString("op")) {
            case "add":
                return pushAdd(url, change);
            case "update":
                return pushUpdate(url, change);
            case "remove":
                return pushRemove(url, change);
            default:
                // No flag pushes on any contacts backend.
                return result(change.getString("handle"), true, null, null);
        }
    }

    /**
     * Tallies the accepted pushes into the sync report: an accepted
     * add is a card in, a remove a card out, an update a card changed.
     */
    private void tallyPushes(String url, JSONArray changes, JSONArray results)
            throws JSONException {
        if (tally == null) {
            return;
        }

        for (int index = 0; index < changes.length() && index < results.length(); index++) {
            if (!results.getJSONObject(index).optBoolean("accepted")) {
                continue;
            }
            JSONObject change = changes.getJSONObject(index);
            String kind;
            switch (change.getString("op")) {
                case "add":
                    kind = "created";
                    break;
                case "update":
                    kind = "changed";
                    break;
                case "remove":
                    kind = "removed";
                    break;
                default:
                    continue;
            }
            tally.tally(url, change.getString("handle"), kind);
        }
    }

    /**
     * Pushes a pending create as the bridge plans it: a membership
     * patch when the body already lives on the account (add with an
     * origin), a genuine create otherwise, with any post-create
     * membership patch the backend needs riding along.
     */
    private JSONObject pushAdd(String url, JSONObject change) throws JSONException {
        String handle = change.getString("handle");
        JSONObject row = base.loadRow(url, handle);
        if (row == null) {
            return result(handle, false, null, null);
        }

        JSONObject plan = pushPlan("add", url, !change.isNull("origin"), false);
        if ("membership".equals(plan.getString("action"))) {
            client.updateCardBooks(account, row.getString("id"), List.of(bookId(url)), List.of());
            return result(handle, true, handle, null);
        }

        Card created =
                client.createCard(account, url, row.getString("id"), row.getString("vcard"));

        JSONArray postCreate = plan.optJSONArray("postCreateBooks");
        for (int index = 0; postCreate != null && index < postCreate.length(); index++) {
            client.updateCardBooks(
                    account, created.id, List.of(postCreate.optString(index)), List.of());
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
            return result(handle, true, null, updated.etag);
        }
    }

    /**
     * Pushes a staged removal as the bridge plans it: a membership
     * patch when the card is not deleted on an account-level backend,
     * the card's deletion otherwise.
     */
    private JSONObject pushRemove(String url, JSONObject change) throws JSONException {
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");
        JSONObject row = base.loadRow(url, handle);
        if (row == null) {
            return result(handle, true, null, null);
        }

        JSONObject plan = pushPlan("remove", url, false, row.optBoolean("deleted"));
        if ("membership".equals(plan.getString("action"))) {
            client.updateCardBooks(account, row.getString("id"), List.of(), List.of(bookId(url)));
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

    /** How many CardDAV pushes fly concurrently (one request per card). */
    private static final int PUSH_CONCURRENCY = 4;

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

    /** One push change's bridge plan. */
    private JSONObject pushPlan(String op, String url, boolean origin, boolean deleted)
            throws JSONException {
        JSONObject facts = new JSONObject();
        facts.put("op", op);
        facts.put("collection", url);
        facts.put("bookId", bookId(url));
        facts.put("origin", origin);
        facts.put("deleted", deleted);
        return client.offlinePushPlan(facts);
    }

    /**
     * Whether the last enumerate proves the handle unchanged at the
     * staged base revision, decided by the bridge over the recorded
     * listing.
     */
    private boolean listingUnchanged(String url, String handle, String ifMatch) {
        try {
            JSONObject facts = new JSONObject();
            Map<String, String> etags = listedEtags.get(url);
            if (etags != null) {
                facts.put("listed", new JSONObject(etags));
            }
            facts.put("complete", Boolean.TRUE.equals(listedComplete.get(url)));
            facts.put("handle", handle);
            facts.put("ifMatch", ifMatch);
            return client.offlineRetryUnguarded(facts);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    /** The HTTP status a bridge failure carries, null on any other failure. */
    private static Integer status(Exception failure) {
        return failure instanceof CardamumException
                ? ((CardamumException) failure).status
                : null;
    }

    private static boolean isPreconditionFailure(Exception failure) {
        return Integer.valueOf(412).equals(status(failure));
    }

    private static boolean isGone(Exception failure) {
        return Integer.valueOf(404).equals(status(failure));
    }

    private boolean isGraph() {
        return account != null && CardamumClient.isGraph(account);
    }

    private boolean isGoogle() {
        return account != null && CardamumClient.isGoogle(account);
    }

    private boolean isJmap() {
        return account != null
                && CardamumClient.isAccountLevel(account)
                && !CardamumClient.isGoogle(account);
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

    /**
     * A failure as the driver error reply, keeping the HTTP status a
     * bridge failure carries so it survives the round trip through
     * the engine (the sync entry points branch on it for the token
     * refresh).
     */
    private static String error(Exception failure) {
        String message = failure.getMessage();
        JSONObject reply = new JSONObject();
        try {
            reply.put("error", message == null ? failure.toString() : message);
            Integer status = status(failure);
            if (status != null) {
                reply.put("status", status);
            }
        } catch (JSONException ignored) {
            return "{\"error\": \"driver failure\"}";
        }
        return reply.toString();
    }
}
