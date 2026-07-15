package org.pimalaya.cardamum;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Card;
import org.pimalaya.cardamum.client.Cards;

/**
 * The io-offline storage seam over the card store's SQLite helper:
 * services the engine's storage yields (load, lookup objects, write)
 * and the row-level reads and conflict bookkeeping the driver needs
 * around them.
 *
 * <p>The engine sees the store as placements: one per (addressbook,
 * card) pair, mapped from the card and membership rows. The row
 * columns carry the engine state losslessly: etag = the base revision,
 * base_vcard = the base body (null when it equals the current one),
 * dirty/deleted/etag-null/conflict_revision = the status ladder,
 * membership states = per-book created/tombstone placements on the
 * account-level backends. Object bodies live in the vcard and
 * base_vcard columns; hashes are computed on the fly (SHA-256 of the
 * vCard text), never stored.
 *
 * <p>Each addressbook has a second collection, the phone spoke
 * (docs/phone-sync-plan.md): the same card rows exposed through the
 * membership rows' phone axis (phone_revision, phone_base,
 * phone_stale, phone_conflict_revision), so the engine reconciles the
 * hub with the book's raw contacts exactly as it does with the
 * server. Cross-spoke propagation is the hub row itself: a phone-won
 * write makes the row diverge from the server base and vice versa.
 */
final class OfflineStore {
    private final CardStore base;

    /** The phone-spoke half, sharing the same card store handle. */
    private final OfflinePhoneStore phone;

    OfflineStore(CardStore base) {
        this.base = base;
        this.phone = new OfflinePhoneStore(base);
    }

    /**
     * Loads one collection for the engine: its placements and last
     * sync checkpoint, as the reply to a load yield.
     */
    public JSONObject loadCollection(String url) throws JSONException {
        if (CardStore.isPhoneCollection(url)) {
            return phone.loadCollection(url);
        }

        SQLiteDatabase db = base.getReadableDatabase();
        String accountEmail = CardStore.accountEmailOf(db, url);
        JSONObject reply = new JSONObject();
        JSONArray placements = new JSONArray();
        reply.put("placements", placements);

        if (accountEmail == null) {
            return reply;
        }
        String checkpoint = checkpointOf(db, url);
        if (checkpoint != null) {
            reply.put("checkpoint", checkpoint);
        }

        Map<String, List<String[]>> memberships = membershipsByKey(db, accountEmail);
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT c.key, c.id, c.uri, c.etag, c.vcard, c.base_vcard, c.dirty,"
                                + " c.deleted, c.conflict_revision, c.stale, c.name, c.email,"
                                + " c.phone, c.info, c.uid, c.hash, m.state FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.account_email = ?",
                        new String[] {url, accountEmail})) {
            while (cursor.moveToNext()) {
                JSONObject placement =
                        Cards.offlinePlacement(placementFacts(url, cursor, memberships));
                if (placement != null) {
                    placements.put(placement);
                }
            }
        }

        return reply;
    }

    /**
     * The bridge facts of one card-plus-membership row: the row
     * columns, the row's first membership and any synced membership
     * outside the collection (the origin of a staged membership
     * addition). The placement itself is the bridge's decision.
     */
    private static JSONObject placementFacts(
            String url, Cursor cursor, Map<String, List<String[]>> memberships)
            throws JSONException {
        JSONObject row = new JSONObject();
        row.put("id", cursor.getString(1));
        row.put("uri", cursor.isNull(2) ? null : cursor.getString(2));
        row.put("etag", cursor.isNull(3) ? null : cursor.getString(3));
        row.put("vcard", cursor.getString(4));
        row.put("baseVcard", cursor.isNull(5) ? null : cursor.getString(5));
        row.put("dirty", cursor.getInt(6) == 1);
        row.put("deleted", cursor.getInt(7) == 1);
        row.put("conflictRevision", cursor.isNull(8) ? null : cursor.getString(8));
        row.put("stale", cursor.getInt(9) == 1);
        row.put("name", cursor.getString(10));
        row.put("email", cursor.getString(11));
        row.put("phone", cursor.getString(12));
        row.put("info", cursor.getString(13));
        row.put("uid", cursor.getString(14));
        row.put("hash", cursor.getString(15));
        row.put("memberState", cursor.getInt(16));

        JSONObject facts = new JSONObject();
        facts.put("collection", url);
        facts.put("row", row);

        List<String[]> held = memberships.get(cursor.getString(0));
        if (held != null && !held.isEmpty()) {
            facts.put("firstMembershipUrl", held.get(0)[0]);
            for (String[] membership : held) {
                if (!url.equals(membership[0])
                        && membership[1].equals(String.valueOf(CardStore.MEMBER_SYNCED))) {
                    facts.put("originUrl", membership[0]);
                    break;
                }
            }
        }
        return facts;
    }

    /** One account's memberships by card key: (url, state), URL order. */
    private static Map<String, List<String[]>> membershipsByKey(
            SQLiteDatabase db, String accountEmail) {
        Map<String, List<String[]>> byKey = new HashMap<>();
        try (Cursor cursor =
                db.query(
                        "membership",
                        new String[] {"card_key", "addressbook_url", "state"},
                        "account_email = ?",
                        new String[] {accountEmail},
                        null,
                        null,
                        "addressbook_url")) {
            while (cursor.moveToNext()) {
                byKey.computeIfAbsent(cursor.getString(0), key -> new ArrayList<>())
                        .add(
                                new String[] {
                                    cursor.getString(1), String.valueOf(cursor.getInt(2))
                                });
            }
        }
        return byKey;
    }

    /**
     * Resolves which link ids already map to a stored body, as the
     * reply to a lookup yield. Always empty here: the engine's
     * cross-collection dedup assumes a link id names immutable bytes,
     * but contact replicas sharing a vCard UID diverge legitimately, so
     * no body may stand in for another replica's and every upgrade
     * fetches.
     */
    public JSONObject lookupObjects(JSONArray links) throws JSONException {
        JSONObject reply = new JSONObject();
        reply.put("objects", new JSONObject());
        return reply;
    }

    /**
     * Applies one batch of engine writes atomically. Drops are held to
     * the end of the batch and cancelled by an upsert of the same
     * placement, so an accepted create's rekey (drop the placeholder,
     * upsert the assigned handle) never deletes what it just renamed.
     *
     * <p>Returns what the batch did to the cards, for the sync report:
     * one {collection, handle, kind} entry per row created, changed
     * (its body differs) or removed; bookkeeping upserts (flags, bases,
     * checkpoints) report nothing.
     */
    public JSONArray applyWrites(JSONArray writes) throws JSONException {
        SQLiteDatabase db = base.getWritableDatabase();
        db.beginTransaction();
        try {
            Map<String, String> bodies = new HashMap<>();
            List<JSONObject> drops = new ArrayList<>();
            Set<String> upserted = new java.util.HashSet<>();
            JSONArray effects = new JSONArray();

            for (int index = 0; index < writes.length(); index++) {
                JSONObject op = writes.getJSONObject(index);
                switch (op.getString("op")) {
                    case "storeObject":
                        bodies.put(op.getString("hash"), op.getString("body"));
                        break;
                    case "upsert":
                        JSONObject placement = op.getJSONObject("placement");
                        String kind = applyUpsert(db, placement, bodies);
                        if (kind != null) {
                            effects.put(
                                    effect(
                                            placement.getString("collection"),
                                            placement.getString("handle"),
                                            kind));
                        }
                        upserted.add(
                                placement.getString("collection")
                                        + "\n"
                                        + placement.getString("handle"));
                        break;
                    case "drop":
                        drops.add(op);
                        break;
                    case "setCheckpoint":
                        applySetCheckpoint(db, op);
                        break;
                    default:
                        throw new JSONException("Unknown write op " + op.getString("op"));
                }
            }

            for (JSONObject drop : drops) {
                String collection = drop.getString("collection");
                String handle = drop.getString("handle");
                if (!upserted.contains(collection + "\n" + handle)) {
                    applyDrop(db, collection, handle);
                    effects.put(effect(collection, handle, "removed"));
                }
            }

            db.setTransactionSuccessful();
            return effects;
        } finally {
            db.endTransaction();
        }
    }

    /** One card effect of a write batch, for the sync report. */
    private static JSONObject effect(String collection, String handle, String kind)
            throws JSONException {
        JSONObject effect = new JSONObject();
        effect.put("collection", collection);
        effect.put("handle", handle);
        effect.put("kind", kind);
        return effect;
    }

    /**
     * Maps one engine placement back onto its card and membership
     * rows: the store gathers the row facts and resolves bodies, the
     * bridge plans the writes, and the SQL below executes them.
     * Returns the card effect for the sync report (created, changed or
     * removed), null for pure bookkeeping.
     */
    private String applyUpsert(SQLiteDatabase db, JSONObject placement, Map<String, String> bodies)
            throws JSONException {
        String url = placement.getString("collection");
        if (CardStore.isPhoneCollection(url)) {
            return phone.applyUpsert(db, placement, bodies);
        }
        String accountEmail = CardStore.accountEmailOf(db, url);
        if (accountEmail == null) {
            return null;
        }
        String handle = placement.getString("handle");
        String key = CardStore.handleKey(url, handle);
        String status = placement.getString("status");

        String existingVcard = null;
        String existingBase = null;
        String existingEtag = null;
        boolean exists = false;
        try (Cursor cursor =
                db.query(
                        "card",
                        new String[] {"vcard", "base_vcard", "etag"},
                        "account_email = ? AND key = ?",
                        new String[] {accountEmail, key},
                        null,
                        null,
                        null)) {
            if (cursor.moveToFirst()) {
                exists = true;
                existingVcard = cursor.getString(0);
                existingBase = cursor.isNull(1) ? null : cursor.getString(1);
                existingEtag = cursor.isNull(2) ? null : cursor.getString(2);
            }
        }

        String objectHash = jsonString(placement, "object");
        String linkId = jsonString(placement, "linkId");
        String body =
                objectHash == null
                        ? null
                        : resolveBody(db, objectHash, bodies, existingVcard, existingBase, linkId);

        JSONObject base = placement.optJSONObject("base");
        JSONObject facts = new JSONObject();
        facts.put("collection", url);
        facts.put("status", status);
        facts.put("origin", !placement.isNull("origin"));
        facts.put("conflictRevision", jsonString(placement, "conflictRevision"));
        facts.put("handle", handle);
        facts.put("exists", exists);
        facts.put("existingEtag", existingEtag);
        facts.put("existingVcard", existingVcard);
        facts.put("otherMemberships", countOtherMemberships(db, accountEmail, key, url));
        facts.put("body", body);
        if (base != null) {
            facts.put("baseRevision", jsonString(base, "revision"));
            facts.put("baseObjectHash", jsonString(base, "object"));
        }

        JSONObject plan = Cards.offlineUpsertPlan(facts);
        switch (plan.getString("action")) {
            case "removeMembership":
                setMembership(db, accountEmail, key, url, CardStore.MEMBER_REMOVED);
                return null;
            case "deleteCard":
                ContentValues deletion = new ContentValues();
                deletion.put("deleted", 1);
                db.update(
                        "card",
                        deletion,
                        "account_email = ? AND key = ?",
                        new String[] {accountEmail, key});
                return "removed";
            case "addMembership":
                setMembership(db, accountEmail, key, url, CardStore.MEMBER_ADDED);
                return null;
            default:
                break;
        }

        JSONObject row = plan.getJSONObject("row");
        String vcard = row.getString("vcard");

        ContentValues values = new ContentValues();
        values.put("account_email", accountEmail);
        values.put("key", key);
        values.put("id", row.getString("id"));
        values.put("uri", handle);
        values.put("vcard", vcard);
        values.put("stale", row.getBoolean("stale") ? 1 : 0);
        if (vcard.isEmpty()) {
            values.putAll(emptyIndex());
        } else {
            values.putAll(this.base.indexValues(vcard));
        }

        String baseVcardHash = jsonString(row, "baseVcardHash");
        values.put("etag", jsonString(row, "etag"));
        values.put(
                "base_vcard",
                baseVcardHash == null
                        ? null
                        : resolveBody(db, baseVcardHash, bodies, existingVcard, existingBase, null));

        values.put("deleted", 0);
        values.put("dirty", row.getBoolean("dirty") ? 1 : 0);
        values.put("conflict_revision", jsonString(row, "conflictRevision"));

        db.insertWithOnConflict("card", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        setMembership(
                db,
                accountEmail,
                key,
                url,
                "added".equals(plan.getString("memberState")) ? CardStore.MEMBER_ADDED : CardStore.MEMBER_SYNCED);

        if (!exists) {
            return "created";
        }
        return vcard.equals(existingVcard) ? null : "changed";
    }

    /** Applies a setCheckpoint write on the addressbook row. */
    private void applySetCheckpoint(SQLiteDatabase db, JSONObject op) throws JSONException {
        String checkpoint = jsonString(op, "checkpoint");
        ContentValues values = new ContentValues();
        if (checkpoint == null || checkpoint.isEmpty()) {
            values.putNull("checkpoint");
        } else {
            values.put("checkpoint", checkpoint);
        }
        db.update("addressbook", values, "url = ?", new String[] {op.getString("collection")});
    }

    /**
     * Applies a drop write: the placement's membership goes, and the
     * row with it when no other book still holds the card.
     */
    private void applyDrop(SQLiteDatabase db, String url, String handle) {
        if (CardStore.isPhoneCollection(url)) {
            phone.applyDrop(db, url, handle);
            return;
        }
        String accountEmail = CardStore.accountEmailOf(db, url);
        if (accountEmail == null) {
            return;
        }
        String key = CardStore.handleKey(url, handle);

        db.delete(
                "membership",
                "account_email = ? AND card_key = ? AND addressbook_url = ?",
                new String[] {accountEmail, key, url});
        if (countOtherMemberships(db, accountEmail, key, url) == 0) {
            db.delete(
                    "card",
                    "account_email = ? AND key = ?",
                    new String[] {accountEmail, key});
        }
    }

    /**
     * Resolves an object hash to its body: the batch's stored bodies
     * first, then the row's own current and base bodies, then any row
     * sharing the link id (the engine's cross-collection dedup).
     */
    private String resolveBody(
            SQLiteDatabase db,
            String hash,
            Map<String, String> bodies,
            String existingVcard,
            String existingBase,
            String linkId) {
        String body = bodies.get(hash);
        if (body != null) {
            return body;
        }
        if (existingVcard != null && hash.equals(CardStore.byteHash(existingVcard))) {
            return existingVcard;
        }
        if (existingBase != null && hash.equals(CardStore.byteHash(existingBase))) {
            return existingBase;
        }

        if (linkId != null && !linkId.isEmpty()) {
            try (Cursor cursor =
                    db.query(
                            "card",
                            new String[] {"vcard"},
                            "uid = ? AND vcard != ''",
                            new String[] {linkId},
                            null,
                            null,
                            null)) {
                while (cursor.moveToNext()) {
                    String candidate = cursor.getString(0);
                    if (hash.equals(CardStore.byteHash(candidate))) {
                        return candidate;
                    }
                }
            }
        }

        Log.w("cardamum", "unresolved object body " + hash);
        return null;
    }

    /**
     * The row behind an engine handle, for the push adapter: the
     * staged body and push base a change addresses. Null when unknown.
     * Phone handles resolve to the same rows (the card id maps to the
     * same storage key as the server resource name).
     */
    public JSONObject loadRow(String url, String handle) throws JSONException {
        url = CardStore.serverUrl(url);
        SQLiteDatabase db = base.getReadableDatabase();
        String accountEmail = CardStore.accountEmailOf(db, url);
        if (accountEmail == null) {
            return null;
        }

        try (Cursor cursor =
                db.query(
                        "card",
                        new String[] {"id", "uri", "etag", "vcard", "base_vcard", "deleted"},
                        "account_email = ? AND key = ?",
                        new String[] {accountEmail, CardStore.handleKey(url, handle)},
                        null,
                        null,
                        null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }

            JSONObject row = new JSONObject();
            row.put("id", cursor.getString(0));
            if (!cursor.isNull(1)) {
                row.put("uri", cursor.getString(1));
            }
            if (!cursor.isNull(2)) {
                row.put("etag", cursor.getString(2));
            }
            row.put("vcard", cursor.getString(3));
            if (!cursor.isNull(4)) {
                row.put("baseVcard", cursor.getString(4));
            }
            row.put("deleted", cursor.getInt(5) == 1);
            return row;
        }
    }

    /**
     * The three documents a conflict resolution needs: the staged local
     * body, the common base (the local body itself when none was stored,
     * meaning they agree), and the captured remote body. Null when the row
     * has no captured remote yet, so it is not resolvable.
     */
    public JSONObject loadConflict(String url, String handle) throws JSONException {
        url = CardStore.serverUrl(url);
        SQLiteDatabase db = base.getReadableDatabase();
        String accountEmail = CardStore.accountEmailOf(db, url);
        if (accountEmail == null) {
            return null;
        }

        try (Cursor cursor =
                db.query(
                        "card",
                        new String[] {"vcard", "base_vcard", "conflict_remote_vcard"},
                        "account_email = ? AND key = ?",
                        new String[] {accountEmail, CardStore.handleKey(url, handle)},
                        null,
                        null,
                        null)) {
            if (!cursor.moveToFirst() || cursor.isNull(2)) {
                return null;
            }
            String local = cursor.getString(0);
            JSONObject bodies = new JSONObject();
            bodies.put("local", local);
            // NOTE: base_vcard is null when the base equals the current
            // body.
            bodies.put("base", cursor.isNull(1) ? local : cursor.getString(1));
            bodies.put("remote", cursor.getString(2));
            return bodies;
        }
    }

    /** The handles of one collection still awaiting their full body. */
    public List<String> handlesBelowFull(String url) {
        if (CardStore.isPhoneCollection(url)) {
            return phone.handlesBelowFull(CardStore.serverUrl(url));
        }
        SQLiteDatabase db = base.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT c.uri, c.id FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.deleted = 0"
                                + " AND m.state != " + CardStore.MEMBER_REMOVED
                                + " AND (c.vcard = '' OR c.stale = 1)",
                        new String[] {url})) {
            List<String> handles = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                String uri = cursor.isNull(0) ? null : cursor.getString(0);
                handles.add(CardStore.rowHandle(url, uri, cursor.getString(1)));
            }
            return handles;
        }
    }

    /**
     * One collection's conflicted rows (both sides changed), each with
     * what the resolution merge needs: the staged local body, the push
     * base, and the remote revision observed at conflict time. On the
     * phone collection the base is the phone axis' own.
     */
    public List<JSONObject> loadConflicts(String url) throws JSONException {
        if (CardStore.isPhoneCollection(url)) {
            return phone.loadConflicts(CardStore.serverUrl(url));
        }
        SQLiteDatabase db = base.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT c.uri, c.id, c.etag, c.vcard, c.base_vcard, c.conflict_revision"
                                + " FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.deleted = 0"
                                + " AND c.conflict_revision IS NOT NULL",
                        new String[] {url})) {
            List<JSONObject> conflicts = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                String uri = cursor.isNull(0) ? null : cursor.getString(0);
                JSONObject conflict = new JSONObject();
                conflict.put("handle", CardStore.rowHandle(url, uri, cursor.getString(1)));
                conflict.put("id", cursor.getString(1));
                if (uri != null) {
                    conflict.put("uri", uri);
                }
                if (!cursor.isNull(2)) {
                    conflict.put("etag", cursor.getString(2));
                }
                conflict.put("vcard", cursor.getString(3));
                if (!cursor.isNull(4)) {
                    conflict.put("baseVcard", cursor.getString(4));
                }
                conflict.put("conflictRevision", cursor.getString(5));
                conflicts.add(conflict);
            }
            return conflicts;
        }
    }

    /**
     * Refreshes a conflicted row's observed remote revision before its
     * resolution, so the resolving push is conditioned on the state the
     * merge actually reconciled against.
     */
    public void setConflictRevision(String url, String handle, String revision) {
        SQLiteDatabase db = base.getWritableDatabase();
        String serverUrl = CardStore.serverUrl(url);
        String accountEmail = CardStore.accountEmailOf(db, serverUrl);
        if (accountEmail == null) {
            return;
        }
        String key = CardStore.handleKey(serverUrl, handle);

        if (CardStore.isPhoneCollection(url)) {
            ContentValues values = new ContentValues();
            values.put("phone_conflict_revision", revision);
            db.update(
                    "membership",
                    values,
                    "account_email = ? AND card_key = ? AND addressbook_url = ?",
                    new String[] {accountEmail, key, serverUrl});
            return;
        }

        ContentValues values = new ContentValues();
        values.put("conflict_revision", revision);
        db.update(
                "card",
                values,
                "account_email = ? AND key = ?",
                new String[] {accountEmail, key});
    }

    /**
     * Stores the remote body observed when a row was marked conflicted, so
     * the user resolves the divergence by hand offline (the conflict form
     * reads it back). Server collections only.
     */
    public void setConflictRemote(String url, String handle, String remoteVcard) {
        SQLiteDatabase db = base.getWritableDatabase();
        String serverUrl = CardStore.serverUrl(url);
        String accountEmail = CardStore.accountEmailOf(db, serverUrl);
        if (accountEmail == null) {
            return;
        }
        String key = CardStore.handleKey(serverUrl, handle);

        ContentValues values = new ContentValues();
        values.put("conflict_remote_vcard", remoteVcard);
        db.update(
                "card",
                values,
                "account_email = ? AND key = ?",
                new String[] {accountEmail, key});
    }


    /** The addressbook's last sync checkpoint, null when never synced. */
    private static String checkpointOf(SQLiteDatabase db, String url) {
        try (Cursor cursor =
                db.query(
                        "addressbook",
                        new String[] {"checkpoint"},
                        "url = ?",
                        new String[] {url},
                        null,
                        null,
                        null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    /** The card's membership count outside `excludeUrl`. */
    static int countOtherMemberships(
            SQLiteDatabase db, String accountEmail, String key, String excludeUrl) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM membership WHERE account_email = ?"
                                + " AND card_key = ? AND addressbook_url != ?"
                                + " AND state != " + CardStore.MEMBER_REMOVED,
                        new String[] {accountEmail, key, excludeUrl})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Inserts or updates one membership row with the given state. */
    static void setMembership(
            SQLiteDatabase db, String accountEmail, String key, String url, int state) {
        // NOTE: update-then-insert, never INSERT OR REPLACE: REPLACE
        // deletes and reinserts the row, wiping the phone axis it also
        // carries. A wiped axis reads as never-projected, so the next
        // phone pass re-creates (and conflicts) every touched card.
        ContentValues values = new ContentValues();
        values.put("state", state);
        int updated =
                db.update(
                        "membership",
                        values,
                        "account_email = ? AND card_key = ? AND addressbook_url = ?",
                        new String[] {accountEmail, key, url});
        if (updated == 0) {
            values.put("account_email", accountEmail);
            values.put("card_key", key);
            values.put("addressbook_url", url);
            db.insert("membership", null, values);
        }
    }

    /** Empty write-time index values for a bodiless spine row. */
    static ContentValues emptyIndex() {
        ContentValues values = new ContentValues();
        values.put("name", "");
        values.put("email", "");
        values.put("phone", "");
        values.put("info", "");
        values.put("uid", "");
        values.put("hash", "");
        return values;
    }

    /** Reads a JSON string field, null when absent or JSON null. */
    static String jsonString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key);
    }
}
