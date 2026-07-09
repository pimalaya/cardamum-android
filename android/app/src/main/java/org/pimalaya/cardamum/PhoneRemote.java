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
 * SOURCE_ID, its raw contact VERSION as the content revision). Every
 * write carries CALLER_IS_SYNCADAPTER so nothing loops back as a local
 * edit; pushes are guarded on the recorded VERSION so a contacts-app
 * edit racing the sync stays for the next round. The rows-to-model
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
     * Services an enumerate yield: the account's raw contacts, each as
     * (handle = SOURCE_ID, revision = VERSION), always a complete
     * round. Rows the contacts app deleted (DELETED) are purged and
     * read as vanished by their absence; rows it created (no
     * SOURCE_ID) are stamped a fresh handle first, so a crash never
     * ingests them twice; rows whose card the store no longer holds
     * are stale projections and are purged too (heals interrupted
     * deletes and pre-phone-spoke projections in one rule).
     */
    JSONObject enumerate(String collection) throws JSONException {
        String url = CardStore.serverUrl(collection);
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();

        ensureUngroupedVisible(resolver, account);
        removeGroups(resolver, account);
        rawIds.clear();

        JSONArray items = new JSONArray();
        try (Cursor cursor =
                resolver.query(
                        rawContactsUri(account),
                        new String[] {
                            RawContacts._ID,
                            RawContacts.SOURCE_ID,
                            RawContacts.VERSION,
                            RawContacts.DELETED,
                        },
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                long rawId = cursor.getLong(0);
                String sourceId = cursor.isNull(1) ? null : cursor.getString(1);
                boolean deleted = cursor.getInt(3) == 1;

                if (deleted) {
                    // A contacts-app delete: finalize it; the absence
                    // from this round is the vanished signal.
                    resolver.delete(rawContactUri(account, rawId), null, null);
                    continue;
                }

                String handle;
                String revision;
                if (sourceId == null || sourceId.isEmpty()) {
                    handle = UUID.randomUUID().toString();
                    ContentValues values = new ContentValues();
                    values.put(RawContacts.SOURCE_ID, handle);
                    resolver.update(rawContactUri(account, rawId), values, null, null);
                    // The stamp bumps VERSION; record the bumped one.
                    revision = readVersion(resolver, account, rawId);
                } else if (base.loadRow(url, sourceId) == null) {
                    // A projection of a card the store no longer holds
                    // (interrupted delete, or a pre-phone-spoke store
                    // rebuild): purge instead of resurrecting it.
                    resolver.delete(rawContactUri(account, rawId), null, null);
                    continue;
                } else {
                    handle = sourceId;
                    revision = String.valueOf(cursor.getInt(2));
                }

                rawIds.put(handle, rawId);
                JSONObject item = new JSONObject();
                item.put("handle", handle);
                item.put("revision", revision);
                items.put(item);
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        reply.put("vanished", new JSONArray());
        reply.put("complete", true);
        return reply;
    }

    /**
     * Services a fetch yield: each handle's raw contact read back as a
     * vCard. The phone only represents the Mapping fields, so the body
     * is never a bare reverse-mapped vCard: the phone's field model is
     * merged over the last converged vCard's own model (field-space
     * diff, Mapping.merge) and applied onto it as a patch, so unknown
     * properties and mapping lossiness survive every round-trip.
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

        String baseVcard = phoneBase(url, handle);
        JSONObject baseModel = client.projectCard(baseVcard);
        JSONObject phoneModel = Mapping.model(dataRows(resolver, account, rawId));
        String body = client.applyCard(baseVcard, Mapping.merge(baseModel, phoneModel));

        JSONObject index = client.indexCard(body);
        String uid = index.optString("uid");

        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("linkId", uid.isEmpty() ? handle : uid);
        item.put("meta", index.toString());
        item.put("hash", CardStore.byteHash(body));
        item.put("body", body);
        item.put("revision", readVersion(resolver, account, rawId));
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

        return result(handle, true, handle, readVersion(resolver, account, rawId));
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
        if (ifMatch != null && !ifMatch.equals(readVersion(resolver, account, rawId))) {
            return result(handle, false, null, null);
        }

        rewrite(resolver, account, rawId, client.projectCard(row.getString("vcard")));
        return result(handle, true, null, readVersion(resolver, account, rawId));
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
        if (ifMatch != null && !ifMatch.equals(readVersion(resolver, account, rawId))) {
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
        try (Cursor cursor =
                resolver.query(
                        dataUri(account),
                        projection,
                        Data.RAW_CONTACT_ID + " = ?",
                        new String[] {String.valueOf(rawId)},
                        null)) {
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

    /** The raw contact's current VERSION, the spoke's revision token. */
    private static String readVersion(ContentResolver resolver, Account account, long rawId) {
        try (Cursor cursor =
                resolver.query(
                        rawContactUri(account, rawId),
                        new String[] {RawContacts.VERSION},
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return String.valueOf(cursor.getInt(0));
            }
        }
        return null;
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
