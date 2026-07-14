package org.pimalaya.cardamum;

import android.accounts.Account;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.CardamumClient;

/**
 * The ContactsContract half of the io-offline driver: the phone spoke's
 * remote adapter, serving the engine's enumerate, fetch and push yields
 * against an addressbook's raw contacts (one per card, the card id in
 * SOURCE_ID, an opaque token in SYNC1 as the sync revision). Every write
 * carries CALLER_IS_SYNCADAPTER so nothing loops back as a local edit;
 * the DIRTY flag (set only by a user edit, never by our writes or the
 * provider's own churn) is what marks a contacts-app change, and pushes
 * are guarded on that revision so an edit racing the sync stays for the
 * next round. The rows-to-model
 * mapping itself lives in Mapping, pure and JVM-tested; this class owns
 * everything that needs a device.
 */
final class PhoneRemote {
    private final Context context;
    private final CardStore base;
    private final CardamumClient client;

    /** Raw contact row ids by handle, primed by the pass's enumerate. */
    private final Map<String, Long> rawIds = new HashMap<>();

    PhoneRemote(Context context, CardStore base, CardamumClient client) {
        this.context = context;
        this.base = base;
        this.client = client;
    }

    /**
     * Whether the spoke can serve this addressbook: the contacts
     * permission is granted and the book's Android account exists (the
     * user ran "Sync local" at least once). Callers skip the phone
     * passes otherwise; the server spoke never depends on it.
     */
    boolean available(String url) {
        return context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED
                && account(url) != null;
    }

    /**
     * Whether the account's raw contacts carry anything for the hub to
     * ingest: a user edit (DIRTY), a contacts-app creation (no
     * SOURCE_ID yet) or a deletion (DELETED). One indexed provider
     * count, half of the phone pass's quiet-path guard.
     */
    boolean changed(String url) {
        Account account = requireAccount(url);
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                rawContactsUri(account),
                                new String[] {RawContacts._ID},
                                RawContacts.DIRTY
                                        + " = 1 OR "
                                        + RawContacts.SOURCE_ID
                                        + " IS NULL OR "
                                        + RawContacts.SOURCE_ID
                                        + " = '' OR "
                                        + RawContacts.DELETED
                                        + " = 1",
                                null,
                                null)) {
            return cursor != null && cursor.getCount() > 0;
        }
    }

    /** The account's live raw contacts, for the quiet-path count match. */
    int count(String url) {
        Account account = requireAccount(url);
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                rawContactsUri(account),
                                new String[] {RawContacts._ID},
                                RawContacts.DELETED + " = 0",
                                null,
                                null)) {
            return cursor == null ? 0 : cursor.getCount();
        }
    }

    /**
     * Services an enumerate yield: the account's raw contacts, each as
     * (handle = SOURCE_ID, revision = the SYNC1 token). A delta round,
     * never complete: only rows the contacts app explicitly deleted
     * (DELETED) vanish, so an emptied or torn-down account never reads
     * as a mass deletion (the hub re-projects a card the phone lacks).
     * Rows it created (no SOURCE_ID) are stamped a fresh handle first,
     * so a crash never ingests them twice; rows whose card the store no
     * longer holds are stale projections and are purged too (heals
     * interrupted deletes and pre-phone-spoke projections in one rule).
     */
    JSONObject enumerate(String collection) throws JSONException {
        String url = CardStore.serverUrl(collection);
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();

        ensureUngroupedVisible(resolver, account);
        removeGroups(resolver, account);
        rawIds.clear();

        JSONArray items = new JSONArray();
        JSONArray vanished = new JSONArray();
        try (Cursor cursor =
                resolver.query(
                        rawContactsUri(account),
                        new String[] {
                            RawContacts._ID,
                            RawContacts.SOURCE_ID,
                            RawContacts.VERSION,
                            RawContacts.DELETED,
                            RawContacts.SYNC1,
                            RawContacts.DIRTY,
                        },
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                long rawId = cursor.getLong(0);
                String sourceId = cursor.isNull(1) ? null : cursor.getString(1);
                boolean deleted = cursor.getInt(3) == 1;

                if (deleted) {
                    // A genuine contacts-app delete: report it explicitly
                    // (only a DELETED row propagates), then finalize it. A
                    // row merely absent from the round is never a delete,
                    // so a torn-down or empty account cannot flush the
                    // cards (see the delta round below).
                    if (sourceId != null && !sourceId.isEmpty()) {
                        vanished.put(sourceId);
                    }
                    resolver.delete(rawContactUri(account, rawId), null, null);
                    continue;
                }

                String handle;
                if (sourceId == null || sourceId.isEmpty()) {
                    handle = UUID.randomUUID().toString();
                    ContentValues values = new ContentValues();
                    values.put(RawContacts.SOURCE_ID, handle);
                    resolver.update(rawContactUri(account, rawId), values, null, null);
                } else if (base.loadRow(url, sourceId) == null) {
                    // A projection of a card the store no longer holds
                    // (interrupted delete, or a pre-phone-spoke store
                    // rebuild): purge instead of resurrecting it.
                    resolver.delete(rawContactUri(account, rawId), null, null);
                    continue;
                } else {
                    handle = sourceId;
                }
                // The revision rides the same cursor (one provider
                // query per round, not one per contact).
                String token = cursor.isNull(4) ? null : cursor.getString(4);
                String revision = revisionOf(token, cursor.getInt(5) != 0, cursor.getInt(2));

                rawIds.put(handle, rawId);
                JSONObject item = new JSONObject();
                item.put("handle", handle);
                item.put("revision", revision);
                items.put(item);
            }
        }

        // A delta round, never a complete one: absence from the listing
        // must not read as a deletion (a removed Android account, a book
        // whose mirroring was toggled off, or any empty account would
        // otherwise flush every card). Only the explicit DELETED rows
        // above vanish; everything else is left to the hub, which
        // re-projects a card the phone is missing.
        JSONObject reply = new JSONObject();
        reply.put("items", items);
        reply.put("vanished", vanished);
        reply.put("complete", false);
        return reply;
    }

    /**
     * Services a fetch yield: each handle's raw contact read back as a
     * vCard. The phone only represents the Mapping fields, so the body
     * is never a bare reverse-mapped vCard: the phone's field model is
     * merged over the last converged vCard's own model (field-space
     * diff, Mapping.merge) and applied onto it as a patch, so unknown
     * properties and mapping lossiness survive every round-trip. A
     * phone untouched at field level rides back byte for byte, and
     * every read stamps the row converged, so a fetch is content-quiet
     * on both sides.
     */
    JSONObject fetch(String collection, JSONArray handles) throws JSONException {
        JSONArray items = new JSONArray();
        for (int index = 0; index < handles.length(); index++) {
            JSONObject item = read(collection, handles.getString(index));
            if (item != null) {
                items.put(item);
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
    }

    /** One handle's fetched item (also the conflict-merge remote side). */
    JSONObject read(String collection, String handle) throws JSONException {
        String url = CardStore.serverUrl(collection);
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();
        Long rawId = rawId(resolver, account, handle);
        if (rawId == null) {
            return null;
        }

        // The sync signals are captured before the data rows: the
        // convergence stamp below is guarded on this VERSION, so a
        // contacts-app edit racing the read loses the stamp, stays
        // dirty and is ingested by the next round instead of vanishing.
        String heldToken = null;
        boolean dirty = false;
        int version = 0;
        try (Cursor cursor =
                resolver.query(
                        rawContactUri(account, rawId),
                        new String[] {
                            RawContacts.SYNC1, RawContacts.DIRTY, RawContacts.VERSION
                        },
                        null,
                        null,
                        null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            heldToken = cursor.isNull(0) ? null : cursor.getString(0);
            dirty = cursor.getInt(1) != 0;
            version = cursor.getInt(2);
        }

        String baseVcard = phoneBase(url, handle);
        JSONObject baseModel = client.projectCard(baseVcard);
        JSONObject phoneModel = Mapping.model(dataRows(resolver, account, rawId));
        JSONObject merged = Mapping.merge(baseModel, phoneModel);

        // No field taken from the phone means no edit: the base card
        // rides back byte for byte. Re-encoding a no-op through
        // applyCard would change the bytes (managed properties are
        // rewritten in canonical form), and the engine diffs content by
        // hash, so the no-op would read as a phone edit and cascade to
        // the server.
        String body = merged == null ? baseVcard : client.applyCard(baseVcard, merged);
        if (merged != null) {
            // Which fields the phone won: the one trace that explains a
            // phone-won hub change when hunting phantom edits.
            StringBuilder taken = new StringBuilder();
            for (String field : Mapping.FIELDS) {
                if (!String.valueOf(merged.opt(field))
                        .equals(String.valueOf(baseModel.opt(field)))) {
                    taken.append(taken.length() == 0 ? "" : ", ").append(field);
                }
            }
            android.util.Log.d(
                    "cardamum", "phone edit on " + handle + " (fields: " + taken + ")");
        }

        // The row is converged as of this read: stamp it clean so it
        // stops enumerating as a pending edit. This heals contacts-app
        // edits once pulled (nothing else clears DIRTY on the pull
        // path) and rows projected before the SYNC1 scheme existed,
        // both of which otherwise refetch on every provider VERSION
        // bump. Already-stamped rows skip the write.
        String token = CardStore.byteHash(body);
        String revision;
        if (!dirty && token.equals(heldToken)) {
            revision = token;
        } else if (stampIfUnchanged(resolver, account, rawId, token, version)) {
            revision = token;
        } else {
            revision = revisionOf(heldToken, dirty, version);
        }

        JSONObject index = client.indexCard(body);
        String uid = index.optString("uid");

        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("linkId", uid.isEmpty() ? handle : uid);
        item.put("meta", index.toString());
        item.put("hash", token);
        item.put("body", body);
        item.put("revision", revision);
        return item;
    }

    /**
     * Services a push yield: creates project the card's data rows onto
     * a fresh raw contact, updates rewrite them in place, removes purge
     * the raw contact; updates and removes are guarded on the recorded
     * VERSION and report rejected on a mismatch, so a contacts-app edit
     * racing the sync is reconciled by the next round instead of being
     * overwritten.
     */
    JSONObject push(String collection, JSONArray changes) throws JSONException {
        JSONArray results = new JSONArray();
        for (int index = 0; index < changes.length(); index++) {
            JSONObject change = changes.getJSONObject(index);
            String handle = change.getString("handle");
            try {
                switch (change.getString("op")) {
                    case "add":
                        results.put(pushAdd(collection, change));
                        break;
                    case "update":
                        results.put(pushUpdate(collection, change));
                        break;
                    case "remove":
                        results.put(pushRemove(collection, change));
                        break;
                    default:
                        // No flag pushes on the phone spoke.
                        results.put(result(handle, true, null, null));
                        break;
                }
            } catch (Exception failure) {
                android.util.Log.w("cardamum", "phone push failed for " + handle, failure);
                results.put(result(handle, false, null, null));
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("results", results);
        return reply;
    }

    /**
     * Pushes a pending create: the card's model projected onto a new
     * raw contact stamped with its handle; a raw contact already
     * carrying the handle (a crash between insert and convergence) is
     * rewritten in place instead of duplicated.
     */
    private JSONObject pushAdd(String collection, JSONObject change) throws Exception {
        String url = CardStore.serverUrl(collection);
        String handle = change.getString("handle");
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();

        JSONObject row = base.loadRow(collection, handle);
        if (row == null || row.getString("vcard").isEmpty()) {
            return result(handle, false, null, null);
        }
        String vcard = row.getString("vcard");
        JSONObject model = client.projectCard(vcard);

        Long existing = rawId(resolver, account, handle);
        long rawId;
        if (existing != null) {
            rawId = existing;
            rewrite(resolver, account, rawId, model);
        } else {
            rawId = insert(resolver, account, handle, model);
            rawIds.put(handle, rawId);
        }

        return result(handle, true, handle, stampRevision(resolver, account, rawId, vcard));
    }

    /** Pushes a staged content change onto the existing raw contact. */
    private JSONObject pushUpdate(String collection, JSONObject change) throws Exception {
        String url = CardStore.serverUrl(collection);
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();

        JSONObject row = base.loadRow(collection, handle);
        Long rawId = rawId(resolver, account, handle);
        if (row == null || rawId == null) {
            return result(handle, false, null, null);
        }
        if (ifMatch != null && !ifMatch.equals(phoneRevision(resolver, account, rawId))) {
            // A contacts-app edit landed since the base: keep it for the
            // next round rather than clobbering it.
            return result(handle, false, null, null);
        }

        String vcard = row.getString("vcard");
        rewrite(resolver, account, rawId, client.projectCard(vcard));
        return result(handle, true, null, stampRevision(resolver, account, rawId, vcard));
    }

    /** Pushes a staged removal: the raw contact goes entirely. */
    private JSONObject pushRemove(String collection, JSONObject change) throws JSONException {
        String url = CardStore.serverUrl(collection);
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();

        Long rawId = rawId(resolver, account, handle);
        if (rawId == null) {
            // Already gone phone-side; the removal converged.
            return result(handle, true, null, null);
        }
        if (ifMatch != null && !ifMatch.equals(phoneRevision(resolver, account, rawId))) {
            return result(handle, false, null, null);
        }

        context.getContentResolver().delete(rawContactUri(account, rawId), null, null);
        rawIds.remove(handle);
        return result(handle, true, null, null);
    }

    // ---- ContactsContract plumbing --------------------------------------------

    /** Inserts a raw contact with its data rows, returning its row id. */
    private long insert(ContentResolver resolver, Account account, String handle,
            JSONObject model) throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(
                ContentProviderOperation.newInsert(rawContactsUri(account))
                        .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                        .withValue(RawContacts.ACCOUNT_NAME, account.name)
                        .withValue(RawContacts.SOURCE_ID, handle)
                        .withValue(RawContacts.SYNC2, model.optString("uid"))
                        .build());
        for (ContentValues row : contentValues(model)) {
            ops.add(
                    ContentProviderOperation.newInsert(dataUri(account))
                            .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                            .withValues(row)
                            .build());
        }

        ContentProviderResult[] results =
                resolver.applyBatch(ContactsContract.AUTHORITY, ops);
        return ContentUris.parseId(results[0].uri);
    }

    /** Rewrites a raw contact's data rows wholesale from the model. */
    private void rewrite(ContentResolver resolver, Account account, long rawId,
            JSONObject model) throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(
                ContentProviderOperation.newDelete(dataUri(account))
                        .withSelection(
                                Data.RAW_CONTACT_ID + " = ?",
                                new String[] {String.valueOf(rawId)})
                        .build());
        for (ContentValues row : contentValues(model)) {
            ops.add(
                    ContentProviderOperation.newInsert(dataUri(account))
                            .withValue(Data.RAW_CONTACT_ID, rawId)
                            .withValues(row)
                            .build());
        }
        ops.add(
                ContentProviderOperation.newUpdate(rawContactUri(account, rawId))
                        .withValue(RawContacts.SYNC2, model.optString("uid"))
                        .build());

        resolver.applyBatch(ContactsContract.AUTHORITY, ops);
    }

    /** The raw contact's data rows as plain maps for the pure Mapping. */
    private static List<Map<String, Object>> dataRows(
            ContentResolver resolver, Account account, long rawId) {
        String[] projection = {
            Data.MIMETYPE,
            Data.DATA1,
            Data.DATA2,
            Data.DATA3,
            Data.DATA4,
            Data.DATA5,
            Data.DATA6,
            Data.DATA7,
            Data.DATA8,
            Data.DATA9,
            Data.DATA10,
            Data.IS_PRIMARY,
        };

        List<Map<String, Object>> rows = new ArrayList<>();
        // Insertion order, pinned: the field-space diff against the
        // base's own projection compares multi-valued fields as lists,
        // so an unordered listing could read as a permutation edit.
        try (Cursor cursor =
                resolver.query(
                        dataUri(account),
                        projection,
                        Data.RAW_CONTACT_ID + " = ?",
                        new String[] {String.valueOf(rawId)},
                        Data._ID)) {
            while (cursor != null && cursor.moveToNext()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 0; column < projection.length; column++) {
                    if (!cursor.isNull(column)) {
                        row.put(projection[column], cursor.getString(column));
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** The model's data rows (built by the pure Mapping), as ContentValues. */
    private static List<ContentValues> contentValues(JSONObject model) throws JSONException {
        List<ContentValues> rows = new ArrayList<>();
        for (Map<String, Object> row : Mapping.rows(model)) {
            ContentValues values = new ContentValues();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getValue() instanceof Integer) {
                    values.put(entry.getKey(), (Integer) entry.getValue());
                } else {
                    values.put(entry.getKey(), (String) entry.getValue());
                }
            }
            rows.add(values);
        }
        return rows;
    }

    /**
     * The vCard the phone last converged with, the patch base of every
     * read-back; a phone-created contact starts from a fresh skeleton
     * carrying its handle as UID.
     */
    private String phoneBase(String url, String handle) throws JSONException {
        JSONObject row = base.loadRow(url, handle);
        String held = null;
        if (row != null) {
            held = base.loadPhoneBase(url, handle);
            if (held == null) {
                // Never converged but the hub holds it: its own vCard
                // is the closest base (heals a lost axis without
                // dropping the card's unmapped properties).
                String vcard = row.getString("vcard");
                held = vcard.isEmpty() ? null : vcard;
            }
        }
        if (held != null) {
            return held;
        }
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + handle + "\r\nEND:VCARD\r\n";
    }

    /** The raw contact id behind a handle, the pass cache first. */
    private Long rawId(ContentResolver resolver, Account account, String handle) {
        Long cached = rawIds.get(handle);
        if (cached != null) {
            return cached;
        }
        try (Cursor cursor =
                resolver.query(
                        rawContactsUri(account),
                        new String[] {RawContacts._ID},
                        RawContacts.SOURCE_ID + " = ? AND " + RawContacts.DELETED + " = 0",
                        new String[] {handle},
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long rawId = cursor.getLong(0);
                rawIds.put(handle, rawId);
                return rawId;
            }
        }
        return null;
    }

    /**
     * The raw contact's sync revision, read from the contacts-app sync
     * signals rather than a content read. Neither RawContacts.VERSION nor
     * a hash of the data rows is stable: the provider bumps VERSION and
     * rewrites rows (aggregation, name display and phonetic fields, phone
     * normalization) asynchronously after a write, which would read back
     * as a phantom contacts-app edit and let the stale phone side win.
     * DIRTY is the real signal: it is set only by a non-sync-adapter
     * (user) edit, never by our own writes or provider churn. So the
     * revision is the opaque token we last stamped in SYNC1 while the row
     * is clean, and a version-tagged sentinel while it is dirty (a user
     * edit the sync has not yet ingested).
     */
    private static String phoneRevision(
            ContentResolver resolver, Account account, long rawId) {
        try (Cursor cursor =
                resolver.query(
                        rawContactUri(account, rawId),
                        new String[] {
                            RawContacts.SYNC1, RawContacts.DIRTY, RawContacts.VERSION
                        },
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String token = cursor.isNull(0) ? null : cursor.getString(0);
                return revisionOf(token, cursor.getInt(1) != 0, cursor.getInt(2));
            }
        }
        return null;
    }

    /** The revision rule: the stamped token while clean, else the sentinel. */
    private static String revisionOf(String token, boolean dirty, int version) {
        if (token != null && !dirty) {
            return token;
        }
        return "dirty:" + version;
    }

    /**
     * Stamps the raw contact converged at the given token, guarded on
     * the VERSION observed before the data rows were read: an edit
     * racing the ingestion bumps VERSION, the guarded update matches
     * nothing, and the row stays dirty for the next round.
     */
    private static boolean stampIfUnchanged(
            ContentResolver resolver, Account account, long rawId, String token, int version) {
        ContentValues values = new ContentValues();
        values.put(RawContacts.SYNC1, token);
        values.put(RawContacts.DIRTY, 0);
        int stamped =
                resolver.update(
                        rawContactUri(account, rawId),
                        values,
                        RawContacts.VERSION + " = ?",
                        new String[] {String.valueOf(version)});
        return stamped == 1;
    }

    /**
     * Stamps the raw contact as converged: SYNC1 holds the opaque token
     * the next enumerate reports while clean (the vCard hash, stable
     * across provider churn), and DIRTY is cleared so a push we just
     * applied is not read back as a user edit.
     */
    private static String stampRevision(
            ContentResolver resolver, Account account, long rawId, String vcard) {
        String token = CardStore.byteHash(vcard);
        ContentValues values = new ContentValues();
        values.put(RawContacts.SYNC1, token);
        values.put(RawContacts.DIRTY, 0);
        resolver.update(rawContactUri(account, rawId), values, null, null);
        return token;
    }

    private Account account(String url) {
        return Accounts.findByUrl(context, url);
    }

    private Account requireAccount(String url) {
        Account account = account(url);
        if (account == null) {
            throw new IllegalStateException("No Android account for " + url);
        }
        return account;
    }

    // ---- Account-level provider settings ---------------------------------------

    /** Contacts in accounts without groups are hidden by default; unhide ours. */
    private static void ensureUngroupedVisible(ContentResolver resolver, Account account) {
        ContentValues values = new ContentValues();
        values.put(ContactsContract.Settings.ACCOUNT_NAME, account.name);
        values.put(ContactsContract.Settings.ACCOUNT_TYPE, account.type);
        values.put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1);
        resolver.insert(asSyncAdapter(ContactsContract.Settings.CONTENT_URI, account), values);
    }

    /**
     * Drops every group of the account. Cardamum does not touch the
     * contacts app's labels section (user decision): the account shows
     * up under the accounts section via the syncable flag instead, and
     * vCard CATEGORIES stay vCard-side. Also heals phones that got
     * groups from earlier builds.
     */
    private static void removeGroups(ContentResolver resolver, Account account) {
        resolver.delete(asSyncAdapter(Groups.CONTENT_URI, account), null, null);
    }

    private static Uri rawContactsUri(Account account) {
        return asSyncAdapter(RawContacts.CONTENT_URI, account);
    }

    private static Uri rawContactUri(Account account, long rawId) {
        return ContentUris.withAppendedId(rawContactsUri(account), rawId);
    }

    private static Uri dataUri(Account account) {
        return asSyncAdapter(Data.CONTENT_URI, account);
    }

    private static Uri asSyncAdapter(Uri uri, Account account) {
        return uri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
                .build();
    }

    /** One push outcome on the engine wire. */
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
}
