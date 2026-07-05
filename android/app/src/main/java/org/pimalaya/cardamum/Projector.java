package org.pimalaya.cardamum;

import android.accounts.Account;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Card;

/**
 * One-way projection of an addressbook's cards into ContactsContract,
 * under the addressbook's own account: remote wins, one raw contact per
 * card, the card id in SOURCE_ID, the ETag in SYNC1 and a hash of the
 * vCard in SYNC4 (the phone spoke's content token: it changes on staged
 * local edits too, where the ETag does not). Every write carries
 * CALLER_IS_SYNCADAPTER so nothing is marked dirty; contacts of other
 * accounts are never touched. Unchanged hashes are skipped; changed
 * cards get their data rows rewritten wholesale (field-level minimal
 * updates come with the io-offline engine and phone-edit detection).
 * The model-to-rows mapping itself lives in Mapping, pure and
 * JVM-tested; this class owns everything that needs a device.
 */
final class Projector {
    private Projector() {}

    /** Projects the cards (paired with their field models) into the account. */
    static void project(ContentResolver resolver, Account account, List<Card> cards,
            Map<String, JSONObject> models) throws Exception {
        ensureUngroupedVisible(resolver, account);
        removeGroups(resolver, account);

        // Existing raw contacts of this account: card id -> (row id, hash).
        Map<String, long[]> rowIds = new HashMap<>();
        Map<String, String> hashes = new HashMap<>();
        try (Cursor cursor =
                resolver.query(
                        rawContactsUri(account),
                        new String[] {RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC4},
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                String sourceId = cursor.getString(1);
                if (sourceId == null) {
                    continue;
                }
                rowIds.put(sourceId, new long[] {cursor.getLong(0)});
                hashes.put(sourceId, cursor.getString(2));
            }
        }

        Set<String> remote = new HashSet<>();
        for (Card card : cards) {
            remote.add(card.id);
            JSONObject model = models.get(card.id);
            if (model == null) {
                continue;
            }

            long[] existing = rowIds.get(card.id);
            if (existing == null) {
                insert(resolver, account, card, model);
            } else if (!hash(card.vcard).equals(hashes.get(card.id))) {
                rewrite(resolver, account, existing[0], card, model);
            }
        }

        // Cards gone from the remote lose their projection.
        for (Map.Entry<String, long[]> entry : rowIds.entrySet()) {
            if (!remote.contains(entry.getKey())) {
                resolver.delete(
                        rawContactUri(account, entry.getValue()[0]), null, null);
            }
        }
    }

    private static void insert(ContentResolver resolver, Account account, Card card,
            JSONObject model) throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        ops.add(
                ContentProviderOperation.newInsert(rawContactsUri(account))
                        .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                        .withValue(RawContacts.ACCOUNT_NAME, account.name)
                        .withValue(RawContacts.SOURCE_ID, card.id)
                        .withValue(RawContacts.SYNC1, card.etag)
                        .withValue(RawContacts.SYNC2, model.optString("uid"))
                        .withValue(RawContacts.SYNC4, hash(card.vcard))
                        .build());

        for (ContentValues row : contentValues(model)) {
            ops.add(
                    ContentProviderOperation.newInsert(dataUri(account))
                            .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                            .withValues(row)
                            .build());
        }

        resolver.applyBatch(ContactsContract.AUTHORITY, ops);
    }

    private static void rewrite(ContentResolver resolver, Account account, long rawId, Card card,
            JSONObject model) throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        ops.add(
                ContentProviderOperation.newDelete(dataUri(account))
                        .withSelection(Data.RAW_CONTACT_ID + " = ?",
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
                        .withValue(RawContacts.SYNC1, card.etag)
                        .withValue(RawContacts.SYNC4, hash(card.vcard))
                        .build());

        resolver.applyBatch(ContactsContract.AUTHORITY, ops);
    }

    /** The model's data rows (built by the pure Mapping), as ContentValues. */
    private static List<ContentValues> contentValues(JSONObject model) {
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

    // ---- Groups -----------------------------------------------------------

    /**
     * Drops every group of the account. Cardamum does not touch the
     * contacts app's labels section (user decision): the account shows
     * up under the accounts section via the syncable flag instead, and
     * vCard CATEGORIES stay vCard-side for now. Also heals phones that
     * got groups from earlier builds.
     */
    private static void removeGroups(ContentResolver resolver, Account account) {
        resolver.delete(asSyncAdapter(Groups.CONTENT_URI, account), null, null);
    }

    // ---- Sync-adapter URIs ----------------------------------------------------

    /** Contacts in accounts without groups are hidden by default; unhide ours. */
    private static void ensureUngroupedVisible(ContentResolver resolver, Account account) {
        ContentValues values = new ContentValues();
        values.put(ContactsContract.Settings.ACCOUNT_NAME, account.name);
        values.put(ContactsContract.Settings.ACCOUNT_TYPE, account.type);
        values.put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1);
        resolver.insert(
                asSyncAdapter(ContactsContract.Settings.CONTENT_URI, account), values);
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

    /**
     * Projection algorithm version, salted into the content token: bump
     * it whenever the model-to-rows mapping changes, so every contact
     * rewrites once on the next local sync.
     */
    private static final String VERSION = "2";

    /** The phone spoke's content token: a hex SHA-1 of the vCard text. */
    private static String hash(String vcard) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-1")
                            .digest((VERSION + ":" + vcard).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-1 unavailable", error);
        }
    }
}
