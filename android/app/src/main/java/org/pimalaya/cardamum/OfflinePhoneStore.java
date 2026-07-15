package org.pimalaya.cardamum;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Cards;

/**
 * The phone-spoke half of the io-offline storage seam
 * (docs/phone-sync-plan.md): the same card store as {@link
 * OfflineStore}, exposed through the membership rows' phone axis
 * (phone_revision, phone_base, phone_stale, phone_conflict_revision),
 * so the engine reconciles the hub with the book's raw contacts
 * exactly as it does with the server. Shares the card store handle and
 * the store's cross-spoke helpers (jsonString, emptyIndex,
 * countOtherMemberships, setMembership stay package-private on {@link
 * OfflineStore}); the server-spoke dispatchers on {@link OfflineStore}
 * route the phone collection here.
 */
final class OfflinePhoneStore {
    private final CardStore base;

    OfflinePhoneStore(CardStore base) {
        this.base = base;
    }

    /**
     * Whether the book holds phone-axis work for the engine: a card
     * not yet projected, diverged from its phone base, mid-refetch,
     * conflicted, or gone while still on the phone. One EXISTS query,
     * the store half of the phone pass's quiet-path guard (the member
     * count below matches the provider's side).
     */
    public boolean pending(String url) {
        SQLiteDatabase db = base.getReadableDatabase();
        String accountEmail = CardStore.accountEmailOf(db, url);
        if (accountEmail == null) {
            return false;
        }
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT EXISTS (SELECT 1 FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.account_email = ?"
                                + " AND (m.phone_stale = 1"
                                + " OR m.phone_conflict_revision IS NOT NULL"
                                + " OR ((c.deleted = 1 OR m.state = " + CardStore.MEMBER_REMOVED + ")"
                                + " AND (m.phone_revision IS NOT NULL"
                                + " OR m.phone_base IS NOT NULL))"
                                + " OR (c.deleted = 0 AND m.state != " + CardStore.MEMBER_REMOVED
                                + " AND c.vcard != ''"
                                + " AND (m.phone_base IS NULL OR m.phone_base != c.vcard))))",
                        new String[] {url, accountEmail})) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    /** The book's members the phone should mirror (quiet-path count). */
    public int memberCount(String url) {
        SQLiteDatabase db = base.getReadableDatabase();
        String accountEmail = CardStore.accountEmailOf(db, url);
        if (accountEmail == null) {
            return 0;
        }
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.account_email = ?"
                                + " AND c.deleted = 0 AND m.state != " + CardStore.MEMBER_REMOVED
                                + " AND c.vcard != ''",
                        new String[] {url, accountEmail})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /**
     * Loads one addressbook's phone collection: the same card rows,
     * exposed through the membership rows' phone axis. No checkpoint;
     * the phone enumerates complete rounds.
     */
    JSONObject loadCollection(String collection) throws JSONException {
        String url = CardStore.serverUrl(collection);
        SQLiteDatabase db = base.getReadableDatabase();
        String accountEmail = CardStore.accountEmailOf(db, url);
        JSONObject reply = new JSONObject();
        JSONArray placements = new JSONArray();
        reply.put("placements", placements);

        if (accountEmail == null) {
            return reply;
        }

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT c.id, c.vcard, c.deleted, c.name, c.email, c.phone, c.info,"
                                + " c.uid, c.hash, m.state, m.phone_revision, m.phone_base,"
                                + " m.phone_stale, m.phone_conflict_revision FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.account_email = ?",
                        new String[] {url, accountEmail})) {
            while (cursor.moveToNext()) {
                JSONObject placement =
                        Cards.offlinePhonePlacement(placementFacts(collection, cursor));
                if (placement != null) {
                    placements.put(placement);
                }
            }
        }

        return reply;
    }

    /**
     * The bridge facts of one card-plus-membership row on the phone
     * axis. The placement itself is the bridge's decision.
     */
    private static JSONObject placementFacts(String collection, Cursor cursor)
            throws JSONException {
        JSONObject row = new JSONObject();
        row.put("id", cursor.getString(0));
        row.put("vcard", cursor.getString(1));
        row.put("deleted", cursor.getInt(2) == 1);
        row.put("name", cursor.getString(3));
        row.put("email", cursor.getString(4));
        row.put("phone", cursor.getString(5));
        row.put("info", cursor.getString(6));
        row.put("uid", cursor.getString(7));
        row.put("hash", cursor.getString(8));
        row.put("memberState", cursor.getInt(9));
        row.put("phoneRevision", cursor.isNull(10) ? null : cursor.getString(10));
        row.put("phoneBase", cursor.isNull(11) ? null : cursor.getString(11));
        row.put("phoneStale", cursor.getInt(12) == 1);
        row.put("phoneConflict", cursor.isNull(13) ? null : cursor.getString(13));

        JSONObject facts = new JSONObject();
        facts.put("collection", collection);
        facts.put("row", row);
        return facts;
    }

    /**
     * Maps one phone-axis placement back onto its rows: the store
     * gathers the row facts and resolves bodies, the bridge plans the
     * writes (a phone-won change stages the server push, a
     * phone-created card lands as a hub row the server has never
     * seen), and the SQL below executes them. Returns the card effect
     * for the sync report, like the server-axis path.
     */
    String applyUpsert(SQLiteDatabase db, JSONObject placement, Map<String, String> bodies)
            throws JSONException {
        String url = CardStore.serverUrl(placement.getString("collection"));
        String accountEmail = CardStore.accountEmailOf(db, url);
        if (accountEmail == null) {
            return null;
        }
        String handle = placement.getString("handle");
        String key = CardStore.handleKey(url, handle);

        String existingVcard = null;
        String existingBase = null;
        boolean existingDirty = false;
        boolean exists = false;
        try (Cursor cursor =
                db.query(
                        "card",
                        new String[] {"vcard", "base_vcard", "dirty"},
                        "account_email = ? AND key = ?",
                        new String[] {accountEmail, key},
                        null,
                        null,
                        null)) {
            if (cursor.moveToFirst()) {
                exists = true;
                existingVcard = cursor.getString(0);
                existingBase = cursor.isNull(1) ? null : cursor.getString(1);
                existingDirty = cursor.getInt(2) == 1;
            }
        }
        String existingPhoneBase = phoneBaseOf(db, accountEmail, key, url);

        String objectHash = OfflineStore.jsonString(placement, "object");
        String body =
                resolveBody(
                        objectHash, bodies, existingVcard, existingPhoneBase, existingBase);

        JSONObject base = placement.optJSONObject("base");
        JSONObject facts = new JSONObject();
        facts.put("collection", placement.getString("collection"));
        facts.put("status", placement.getString("status"));
        facts.put("conflictRevision", OfflineStore.jsonString(placement, "conflictRevision"));
        facts.put("exists", exists);
        facts.put("existingVcard", existingVcard);
        facts.put("existingDirty", existingDirty);
        facts.put("objectPresent", objectHash != null);
        facts.put("body", body);
        facts.put("basePresent", base != null);
        if (base != null) {
            facts.put("baseRevision", OfflineStore.jsonString(base, "revision"));
            facts.put("baseObjectHash", OfflineStore.jsonString(base, "object"));
        }

        JSONObject plan = Cards.offlinePhoneUpsertPlan(facts);
        String action = plan.getString("action");
        if ("skip".equals(action)) {
            return null;
        }

        String kind = null;
        if ("insert".equals(action)) {
            String vcard = plan.getJSONObject("row").getString("vcard");
            kind = "created";
            ContentValues values = new ContentValues();
            values.put("account_email", accountEmail);
            values.put("key", key);
            values.put("id", handle);
            values.putNull("uri");
            values.putNull("etag");
            values.put("vcard", vcard);
            values.putNull("base_vcard");
            values.put("dirty", 1);
            values.put("deleted", 0);
            values.put("stale", 0);
            values.putNull("conflict_revision");
            if (vcard.isEmpty()) {
                values.putAll(OfflineStore.emptyIndex());
            } else {
                values.putAll(this.base.indexValues(vcard));
            }
            db.insert("card", null, values);
        } else if ("update".equals(action)) {
            JSONObject row = plan.getJSONObject("row");
            String vcard = row.getString("vcard");
            kind = vcard.equals(existingVcard) ? null : "changed";
            ContentValues values = new ContentValues();
            values.put("vcard", vcard);
            values.putAll(this.base.indexValues(vcard));
            values.put("dirty", 1);
            values.put("stale", 0);
            if (row.optBoolean("captureBase")) {
                // NOTE: first divergence from the server state captures
                // the held vCard as the push base, like an in-app edit.
                values.put("base_vcard", existingVcard);
            }
            db.update(
                    "card",
                    values,
                    "account_email = ? AND key = ?",
                    new String[] {accountEmail, key});
        }

        ContentValues member = new ContentValues();
        member.put("account_email", accountEmail);
        member.put("card_key", key);
        member.put("addressbook_url", url);
        db.insertWithOnConflict("membership", null, member, SQLiteDatabase.CONFLICT_IGNORE);

        JSONObject plannedAxis = plan.getJSONObject("axis");
        ContentValues axis = new ContentValues();
        axis.put("phone_revision", OfflineStore.jsonString(plannedAxis, "phoneRevision"));
        String phoneBaseHash = OfflineStore.jsonString(plannedAxis, "phoneBaseHash");
        if (phoneBaseHash != null) {
            String baseBody =
                    resolveBody(
                            phoneBaseHash,
                            bodies,
                            body != null ? body : existingVcard,
                            existingPhoneBase,
                            existingBase);
            if (baseBody != null) {
                axis.put("phone_base", baseBody);
            }
        }
        axis.put("phone_stale", plannedAxis.getBoolean("phoneStale") ? 1 : 0);
        axis.put("phone_conflict_revision", OfflineStore.jsonString(plannedAxis, "phoneConflict"));
        db.update(
                "membership",
                axis,
                "account_email = ? AND card_key = ? AND addressbook_url = ?",
                new String[] {accountEmail, key, url});

        return kind;
    }

    /**
     * Resolves an object hash against the write batch's bodies and the
     * bodies the row already holds (current, phone base, server base);
     * null when unresolved.
     */
    private static String resolveBody(
            String hash,
            Map<String, String> bodies,
            String vcard,
            String phoneBase,
            String serverBase) {
        if (hash == null) {
            return null;
        }
        String body = bodies.get(hash);
        if (body != null) {
            return body;
        }
        if (vcard != null && hash.equals(CardStore.byteHash(vcard))) {
            return vcard;
        }
        if (phoneBase != null && hash.equals(CardStore.byteHash(phoneBase))) {
            return phoneBase;
        }
        if (serverBase != null && hash.equals(CardStore.byteHash(serverBase))) {
            return serverBase;
        }
        Log.w("cardamum", "unresolved phone object body " + hash);
        return null;
    }

    /**
     * Applies a drop on the phone collection: the raw contact vanished
     * (deleted in a contacts app), so the deletion is staged hub-side
     * for the next server sync to push upstream: on the account-level
     * backends the membership goes when other books still hold the
     * card, the card itself otherwise. The phone axis clears with it.
     */
    void applyDrop(SQLiteDatabase db, String collection, String handle) {
        String url = CardStore.serverUrl(collection);
        String accountEmail = CardStore.accountEmailOf(db, url);
        if (accountEmail == null) {
            return;
        }
        String key = CardStore.handleKey(url, handle);

        boolean deleted = false;
        try (Cursor cursor =
                db.query(
                        "card",
                        new String[] {"deleted"},
                        "account_email = ? AND key = ?",
                        new String[] {accountEmail, key},
                        null,
                        null,
                        null)) {
            if (!cursor.moveToFirst()) {
                return;
            }
            deleted = cursor.getInt(0) == 1;
        }

        JSONObject plan;
        try {
            JSONObject facts = new JSONObject();
            facts.put("collection", url);
            facts.put("deleted", deleted);
            facts.put(
                    "otherMemberships",
                    OfflineStore.countOtherMemberships(db, accountEmail, key, url));
            plan = Cards.offlinePhoneDropPlan(facts);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }

        if ("removeMembership".equals(plan.optString("action"))) {
            // NOTE: the replace resets the phone axis columns too.
            OfflineStore.setMembership(db, accountEmail, key, url, CardStore.MEMBER_REMOVED);
            return;
        }

        ContentValues values = new ContentValues();
        values.put("deleted", 1);
        db.update(
                "card",
                values,
                "account_email = ? AND key = ?",
                new String[] {accountEmail, key});

        ContentValues axis = new ContentValues();
        axis.putNull("phone_revision");
        axis.putNull("phone_base");
        axis.put("phone_stale", 0);
        axis.putNull("phone_conflict_revision");
        db.update(
                "membership",
                axis,
                "account_email = ? AND card_key = ? AND addressbook_url = ?",
                new String[] {accountEmail, key, url});
    }

    /**
     * The phone-axis handles still awaiting their body from the phone:
     * pulled phone creates (no body yet) and phone content changes
     * (stale, refetch pending).
     */
    List<String> handlesBelowFull(String url) {
        SQLiteDatabase db = base.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT c.id FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.deleted = 0"
                                + " AND m.state != " + CardStore.MEMBER_REMOVED
                                + " AND ((c.vcard = '' AND m.phone_revision IS NOT NULL)"
                                + " OR m.phone_stale = 1)",
                        new String[] {url})) {
            List<String> handles = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                handles.add(cursor.getString(0));
            }
            return handles;
        }
    }

    /** The phone collection's conflicted rows, phone-axis fields. */
    List<JSONObject> loadConflicts(String url) throws JSONException {
        SQLiteDatabase db = base.getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT c.id, c.vcard, m.phone_base, m.phone_conflict_revision"
                                + " FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.deleted = 0"
                                + " AND m.phone_conflict_revision IS NOT NULL",
                        new String[] {url})) {
            List<JSONObject> conflicts = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                JSONObject conflict = new JSONObject();
                conflict.put("handle", cursor.getString(0));
                conflict.put("id", cursor.getString(0));
                conflict.put("vcard", cursor.getString(1));
                if (!cursor.isNull(2)) {
                    conflict.put("baseVcard", cursor.getString(2));
                }
                conflict.put("conflictRevision", cursor.getString(3));
                conflicts.add(conflict);
            }
            return conflicts;
        }
    }

    /**
     * The phone axis' last converged body behind an engine handle, the
     * patch base of a phone read-back; null when never converged.
     */
    public String loadBase(String url, String handle) {
        SQLiteDatabase db = base.getReadableDatabase();
        String serverUrl = CardStore.serverUrl(url);
        String accountEmail = CardStore.accountEmailOf(db, serverUrl);
        if (accountEmail == null) {
            return null;
        }
        return phoneBaseOf(db, accountEmail, CardStore.handleKey(serverUrl, handle), serverUrl);
    }

    /** The membership row's phone base body, null when never converged. */
    private static String phoneBaseOf(
            SQLiteDatabase db, String accountEmail, String key, String url) {
        try (Cursor cursor =
                db.query(
                        "membership",
                        new String[] {"phone_base"},
                        "account_email = ? AND card_key = ? AND addressbook_url = ?",
                        new String[] {accountEmail, key, url},
                        null,
                        null,
                        null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }
}
