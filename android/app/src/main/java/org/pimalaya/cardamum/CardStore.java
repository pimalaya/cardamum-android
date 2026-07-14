package org.pimalaya.cardamum;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Addressbook;
import org.pimalaya.cardamum.client.Card;
import org.pimalaya.cardamum.client.CardamumClient;

/**
 * The sync base and offline working copy: a local SQLite snapshot of the
 * remote accounts that the screens read and the editor writes. Cards are
 * replicas (docs/merged-view.md): one row per server resource, keyed by
 * account and a backend-unique key, with the addressbooks it belongs to
 * in a separate membership table (m:n on JMAP and Google, exactly one on
 * CardDAV and Graph). Every addressbook is tagged with the account it
 * belongs to and whether the user is subscribed to it (subscription
 * drives what is fetched and displayed). Local edits are staged here
 * (dirty and deleted flags) and pushed by the next sync; the app never
 * talks to the remote outside a sync.
 */
public class CardStore extends SQLiteOpenHelper {
    private static final String DATABASE = "cards.db";
    // NOTE: 15 = the phone axis columns on membership (phone_revision,
    // phone_base, phone_stale, phone_conflict_revision); 14 = the
    // io-offline engine columns (conflict_revision and stale on card,
    // checkpoint on addressbook); 13 = the dismissed_duplicate table;
    // 12 = the index gained the info fallback line; 11 = the
    // divergence hash covering the extended model fields; index
    // changes rebuild the card tables.
    private static final int VERSION = 1;

    /** Indexes vCards at write time (pure bridge computation). */
    private final org.pimalaya.cardamum.client.CardamumClient client =
            new org.pimalaya.cardamum.client.CardamumClient();

    /** Membership state: mirrored from the server. */
    private static final int MEMBER_SYNCED = 0;

    /** Membership state: added locally, awaiting push. */
    private static final int MEMBER_ADDED = 1;

    /** Membership state: removed locally, awaiting push. */
    private static final int MEMBER_REMOVED = 2;

    /**
     * The storage key of a card that lives in one collection (CardDAV,
     * Graph): resource ids are only unique per collection there, so the
     * collection is part of the identity. Account-level backends (JMAP,
     * Google) use the bare server id, unique across the account.
     */
    public static String key(String addressbookUrl, String id) {
        return addressbookUrl + "\u0000" + id;
    }

    /** One displayable replica: the card plus its write-time index. */
    public static final class Indexed {
        public final Card card;

        /** Display name (FN), empty when the card has none. */
        public final String name;

        /** First email address, empty when the card has none. */
        public final String email;

        /** First phone number, empty when the card has none. */
        public final String phone;

        /** Fallback info line (organization, website, ...), maybe empty. */
        public final String info;

        /** vCard UID, the automatic link key; empty when absent. */
        public final String uid;

        /** Normalized content hash, the divergence marker. */
        public final String hash;

        /** True when the row is a both-sides-edited conflict awaiting the
         *  user's manual resolution (a captured remote body is on hand). */
        public final boolean conflicted;

        Indexed(
                Card card,
                String name,
                String email,
                String phone,
                String info,
                String uid,
                String hash,
                boolean conflicted) {
            this.card = card;
            this.name = name;
            this.email = email;
            this.phone = phone;
            this.info = info;
            this.uid = uid;
            this.hash = hash;
            this.conflicted = conflicted;
        }
    }

    /** The write-time index columns of a vCard, empty on a parse failure. */
    private ContentValues indexValues(String vcard) {
        ContentValues values = new ContentValues();
        try {
            org.json.JSONObject index = client.indexCard(vcard);
            values.put("name", index.optString("name"));
            values.put("email", index.optString("email"));
            values.put("phone", index.optString("phone"));
            values.put("info", index.optString("info"));
            values.put("uid", index.optString("uid"));
            values.put("hash", index.optString("hash"));
        } catch (Exception error) {
            android.util.Log.w("cardamum", "card index failed", error);
            values.put("name", "");
            values.put("email", "");
            values.put("phone", "");
            values.put("info", "");
            values.put("uid", "");
            values.put("hash", "");
        }
        return values;
    }

    public CardStore(Context context) {
        super(context, DATABASE, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS addressbook ("
                        + "url TEXT PRIMARY KEY, "
                        + "account_email TEXT NOT NULL, "
                        + "id TEXT NOT NULL, "
                        + "name TEXT NOT NULL, "
                        + "description TEXT, "
                        + "color TEXT, "
                        + "subscribed INTEGER NOT NULL DEFAULT 1, "
                        + "phone_synced INTEGER NOT NULL DEFAULT 0, "
                        + "checkpoint TEXT)");
        // name/email/phone/uid/hash are the write-time index computed
        // by the bridge (Native.indexCard), so the contacts list never
        // parses vCards at render time and linked replicas compare by
        // normalized content hash.
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS card ("
                        + "account_email TEXT NOT NULL, "
                        + "key TEXT NOT NULL, "
                        + "id TEXT NOT NULL, "
                        + "uri TEXT, "
                        + "etag TEXT, "
                        + "vcard TEXT NOT NULL, "
                        + "base_vcard TEXT, "
                        + "name TEXT NOT NULL DEFAULT '', "
                        + "email TEXT NOT NULL DEFAULT '', "
                        + "phone TEXT NOT NULL DEFAULT '', "
                        + "info TEXT NOT NULL DEFAULT '', "
                        + "uid TEXT NOT NULL DEFAULT '', "
                        + "hash TEXT NOT NULL DEFAULT '', "
                        + "dirty INTEGER NOT NULL DEFAULT 0, "
                        + "deleted INTEGER NOT NULL DEFAULT 0, "
                        // The remote revision observed when the sync
                        // marked the row conflicted (both sides edited);
                        // resolving pushes against it. Null outside
                        // conflicts.
                        + "conflict_revision TEXT, "
                        // The remote body captured at that moment, so the
                        // user resolves the divergence by hand offline in
                        // the conflict form. Null outside conflicts.
                        + "conflict_remote_vcard TEXT, "
                        // 1 when the remote content changed and the kept
                        // body is a stale display copy awaiting refetch.
                        + "stale INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (account_email, key))");
        // The phone axis lives here, per (book, card): each book is its
        // own Android account with its own raw contact of the card, so
        // per-card columns would ping-pong on the account-level
        // backends. phone_revision = the raw contact VERSION at last
        // convergence, phone_base = the vCard last converged with the
        // phone, phone_stale = a phone content change awaiting its
        // refetch, phone_conflict_revision = the VERSION observed when
        // both sides diverged.
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS membership ("
                        + "account_email TEXT NOT NULL, "
                        + "card_key TEXT NOT NULL, "
                        + "addressbook_url TEXT NOT NULL, "
                        + "state INTEGER NOT NULL DEFAULT 0, "
                        + "phone_revision TEXT, "
                        + "phone_base TEXT, "
                        + "phone_stale INTEGER NOT NULL DEFAULT 0, "
                        + "phone_conflict_revision TEXT, "
                        + "PRIMARY KEY (account_email, card_key, addressbook_url))");
        // The view-layer link exceptions of the merged list
        // (docs/merged-view.md): a detached replica never groups
        // automatically, and link rows join group keys into a shared
        // cluster. Nothing on any server or in sync depends on them.
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS detached ("
                        + "account_email TEXT NOT NULL, "
                        + "card_key TEXT NOT NULL, "
                        + "PRIMARY KEY (account_email, card_key))");
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS link ("
                        + "member TEXT PRIMARY KEY, "
                        + "cluster TEXT NOT NULL)");
        // Duplicate groups the user chose to leave alone: never
        // proposed again by the duplicate remover.
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS dismissed_duplicate ("
                        + "group_key TEXT PRIMARY KEY)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // NOTE: schema changes rebuild the card tables; it only costs
        // the next sync. The addressbook table SURVIVES: syncs walk
        // the stored addressbooks, so dropping them would leave the
        // accounts with nothing to sync from (the sync's self-heal
        // re-fetches them as a fallback).
        db.execSQL("DROP TABLE IF EXISTS card");
        db.execSQL("DROP TABLE IF EXISTS membership");
        db.execSQL("DROP TABLE IF EXISTS detached");
        db.execSQL("DROP TABLE IF EXISTS link");
        onCreate(db);
    }

    /** A replica reference for the link layer (emails hold no newline). */
    public static String replicaRef(String accountEmail, String key) {
        return accountEmail + "\n" + key;
    }

    /** Whether the duplicate group was already dismissed. */
    public boolean isDuplicateDismissed(String groupKey) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "dismissed_duplicate",
                        new String[] {"group_key"},
                        "group_key = ?",
                        new String[] {groupKey},
                        null,
                        null,
                        null)) {
            return cursor.moveToFirst();
        }
    }

    /** Remembers a duplicate group the user chose to leave alone. */
    public void dismissDuplicate(String groupKey) {
        ContentValues values = new ContentValues();
        values.put("group_key", groupKey);
        getWritableDatabase()
                .insertWithOnConflict(
                        "dismissed_duplicate", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** The replicas detached from automatic (UID) grouping, as refs. */
    public Set<String> loadDetached() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "detached",
                        new String[] {"account_email", "card_key"},
                        null,
                        null,
                        null,
                        null,
                        null)) {
            Set<String> refs = new java.util.HashSet<>(cursor.getCount());
            while (cursor.moveToNext()) {
                refs.add(replicaRef(cursor.getString(0), cursor.getString(1)));
            }
            return refs;
        }
    }

    /** The link rows: group key to the cluster it belongs to. */
    public Map<String, String> loadLinks() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "link",
                        new String[] {"member", "cluster"},
                        null,
                        null,
                        null,
                        null,
                        null)) {
            Map<String, String> links = new HashMap<>(cursor.getCount());
            while (cursor.moveToNext()) {
                links.put(cursor.getString(0), cursor.getString(1));
            }
            return links;
        }
    }

    /**
     * Links the given group keys into one cluster (merging any cluster
     * a key already belongs to), so their rows collapse into one
     * merged contact.
     *
     * <p>NOTE: unwired today (linking goes through a shared UID);
     * kept, with {@link #unlinkGroup}, for the match-suggestion flows
     * docs/merged-view.md reserves the link and detached tables for.
     * When they come back, the cluster computation belongs in the
     * bridge next to groupContacts.
     */
    public void linkGroups(List<String> groupKeys) {
        if (groupKeys.size() < 2) {
            return;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String cluster = groupKeys.get(0);
            for (String key : groupKeys) {
                // Absorb the key's existing cluster, then bind the key.
                ContentValues rebind = new ContentValues();
                rebind.put("cluster", cluster);
                db.update("link", rebind, "cluster = ?", new String[] {key});

                ContentValues values = new ContentValues();
                values.put("member", key);
                values.put("cluster", cluster);
                db.insertWithOnConflict("link", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Splits a merged contact apart: drops the cluster's link rows and
     * detaches every replica, so each becomes its own row until it is
     * explicitly linked again.
     */
    public void unlinkGroup(String clusterKey, List<String> members, List<String> replicaRefs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("link", "cluster = ? OR member = ?", new String[] {clusterKey, clusterKey});
            for (String member : members) {
                db.delete("link", "member = ?", new String[] {member});
            }

            for (String ref : replicaRefs) {
                int newline = ref.indexOf('\n');
                ContentValues values = new ContentValues();
                values.put("account_email", ref.substring(0, newline));
                values.put("card_key", ref.substring(newline + 1));
                db.insertWithOnConflict(
                        "detached", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Replaces one account's addressbooks with the fetched set, keeping
     * the subscription of any addressbook already known (new ones default
     * to subscribed), dropping memberships pointing at vanished books and
     * clean cards left with no membership at all.
     */
    public void replaceAddressbooks(String accountEmail, List<Addressbook> books) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Map<String, Integer> wasSubscribed = new HashMap<>();
            try (Cursor cursor =
                    db.query(
                            "addressbook",
                            new String[] {"url", "subscribed"},
                            "account_email = ?",
                            new String[] {accountEmail},
                            null,
                            null,
                            null)) {
                while (cursor.moveToNext()) {
                    wasSubscribed.put(cursor.getString(0), cursor.getInt(1));
                }
            }

            db.delete("addressbook", "account_email = ?", new String[] {accountEmail});

            for (Addressbook book : books) {
                ContentValues values = new ContentValues();
                values.put("url", book.url);
                values.put("account_email", accountEmail);
                values.put("id", book.id);
                values.put("name", book.name);
                values.put("description", book.description);
                values.put("color", book.color);
                Integer subscribed = wasSubscribed.get(book.url);
                values.put("subscribed", subscribed == null ? 1 : subscribed);
                db.insert("addressbook", null, values);
            }

            db.execSQL(
                    "DELETE FROM membership WHERE addressbook_url NOT IN"
                            + " (SELECT url FROM addressbook)");
            dropMembershiplessCards(db);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Sets one addressbook's two switches. Phone sync requires the
     * subscription, so it is forced off whenever the book is not
     * subscribed.
     */
    public void setBookState(String url, boolean subscribed, boolean phoneSynced) {
        boolean phone = subscribed && phoneSynced;
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("subscribed", subscribed ? 1 : 0);
        values.put("phone_synced", phone ? 1 : 0);
        db.update("addressbook", values, "url = ?", new String[] {url});

        if (!phone) {
            // Mirroring off: the reconcile tears down the Android account
            // and its raw contacts, so the phone axis is now stale. Clear
            // it, or turning mirroring back on reads the empty phone as
            // "every contact was deleted" and flushes them. Cleared, the
            // cards re-project as fresh creations on the next sync instead.
            String accountEmail = accountEmailOf(db, url);
            if (accountEmail != null) {
                ContentValues axis = new ContentValues();
                axis.putNull("phone_revision");
                axis.putNull("phone_base");
                axis.put("phone_stale", 0);
                axis.putNull("phone_conflict_revision");
                db.update(
                        "membership",
                        axis,
                        "account_email = ? AND addressbook_url = ?",
                        new String[] {accountEmail, url});
            }
        }
    }

    /** Drops an account and everything under it (its addressbooks and cards). */
    /**
     * Removes an account but keeps its contacts: its live cards are
     * reassigned to the local "On this device" book, cleared of every
     * sync marker, so they survive as plain on-device contacts. The
     * account's own addressbooks, tombstones and view-layer links are
     * dropped. A staged local delete stays a delete (its card is not
     * carried over).
     */
    public void detachAccountToLocal(String accountEmail) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Carry the live cards over to the local account, stripped of
            // etag/base/conflict/dirty state. OR IGNORE steps over a key
            // that already exists locally.
            db.execSQL(
                    "UPDATE OR IGNORE card SET account_email = ?, etag = NULL,"
                            + " base_vcard = NULL, dirty = 0, conflict_revision = NULL,"
                            + " conflict_remote_vcard = NULL, stale = 0"
                            + " WHERE account_email = ? AND deleted = 0",
                    new String[] {LocalBook.ACCOUNT, accountEmail});

            // Move their live memberships into the local book, dropping the
            // phone-axis state. A card in several books collapses to one
            // row (OR IGNORE); tombstones are left behind and swept below.
            db.execSQL(
                    "UPDATE OR IGNORE membership SET account_email = ?,"
                            + " addressbook_url = ?, state = 0, phone_revision = NULL,"
                            + " phone_base = NULL, phone_stale = 0,"
                            + " phone_conflict_revision = NULL"
                            + " WHERE account_email = ? AND state != ?",
                    new String[] {
                        LocalBook.ACCOUNT, LocalBook.URL, accountEmail,
                        String.valueOf(MEMBER_REMOVED)
                    });

            // Whatever still names the removed account (a tombstone, a
            // collided or deleted card, its addressbooks, its links) does
            // not outlive it.
            db.delete("membership", "account_email = ?", new String[] {accountEmail});
            db.delete("card", "account_email = ?", new String[] {accountEmail});
            db.delete("addressbook", "account_email = ?", new String[] {accountEmail});
            db.delete("detached", "account_email = ?", new String[] {accountEmail});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Seeds the built-in local addressbook, so a fresh install or a
     * schema rebuild always finds it present and subscribed. Ignored
     * when the row already exists, keeping any cards and subscription.
     */
    public void ensureLocalAddressbook(String url, String accountEmail, String id, String name) {
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("url", url);
        values.put("account_email", accountEmail);
        values.put("id", id);
        values.put("name", name);
        values.put("subscribed", 1);
        db.insertWithOnConflict("addressbook", null, values, SQLiteDatabase.CONFLICT_IGNORE);

        // Refresh only the name, so a rename across versions lands
        // without resetting the subscription or the phone switch.
        ContentValues rename = new ContentValues();
        rename.put("name", name);
        db.update("addressbook", rename, "url = ?", new String[] {url});
    }

    /** Every subscribed addressbook, ordered by account then name (drives the home listing). */
    public List<BookEntry> loadSubscribedAddressbooks() {
        return queryAddressbooks("subscribed = 1");
    }

    /** Every known addressbook, subscribed or not (drives the subscription editor). */
    public List<BookEntry> loadAllAddressbooks() {
        return queryAddressbooks(null);
    }

    private List<BookEntry> queryAddressbooks(String selection) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "addressbook",
                        new String[] {
                            "url",
                            "account_email",
                            "id",
                            "name",
                            "description",
                            "color",
                            "subscribed",
                            "phone_synced"
                        },
                        selection,
                        null,
                        null,
                        null,
                        "account_email, name")) {
            List<BookEntry> books = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                Addressbook book =
                        new Addressbook(
                                cursor.getString(2),
                                cursor.getString(3),
                                cursor.getString(0),
                                cursor.isNull(4) ? null : cursor.getString(4),
                                cursor.isNull(5) ? null : cursor.getString(5));
                books.add(
                        new BookEntry(
                                book,
                                cursor.getString(1),
                                cursor.getInt(6) == 1,
                                cursor.getInt(7) == 1));
            }
            return books;
        }
    }

    /** Drops clean cards left without any membership. */
    private void dropMembershiplessCards(SQLiteDatabase db) {
        db.execSQL(
                "DELETE FROM card WHERE dirty = 0 AND deleted = 0 AND NOT EXISTS"
                        + " (SELECT 1 FROM membership WHERE membership.account_email ="
                        + " card.account_email AND membership.card_key = card.key)");
    }

    /**
     * Stages a local create or edit; the next sync pushes it. The first
     * edit of a clean card captures its vCard as the push base (what
     * the server last confirmed); follow-up edits before a sync keep
     * that original base so the push still diffs against server state.
     * A fresh card is born a member of the given addressbook; an edit
     * keeps the memberships the replica already has.
     */
    public void saveLocal(String accountEmail, String key, String addressbookUrl, Card card) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String baseVcard = null;
            String conflictRevision = null;
            try (Cursor cursor =
                    db.query(
                            "card",
                            new String[] {"vcard", "base_vcard", "dirty", "conflict_revision"},
                            "account_email = ? AND key = ?",
                            new String[] {accountEmail, key},
                            null,
                            null,
                            null)) {
                if (cursor.moveToFirst()) {
                    baseVcard =
                            cursor.getInt(2) == 0
                                    ? cursor.getString(0)
                                    : cursor.isNull(1) ? null : cursor.getString(1);
                    conflictRevision = cursor.isNull(3) ? null : cursor.getString(3);
                }
            }

            ContentValues values = new ContentValues();
            values.put("account_email", accountEmail);
            values.put("key", key);
            values.put("id", card.id);
            values.put("uri", card.uri);
            // Editing a conflicted row resolves it: the remote revision
            // observed at conflict time becomes the push base, so the
            // resolving push is conditioned on the state the user (or
            // the merge) resolved against.
            values.put("etag", conflictRevision != null ? conflictRevision : card.etag);
            values.put("vcard", card.vcard);
            values.put("base_vcard", baseVcard);
            values.putAll(indexValues(card.vcard));
            values.put("dirty", 1);
            values.put("deleted", 0);
            db.insertWithOnConflict("card", null, values, SQLiteDatabase.CONFLICT_REPLACE);

            ContentValues membership = new ContentValues();
            membership.put("account_email", accountEmail);
            membership.put("card_key", key);
            membership.put("addressbook_url", addressbookUrl);
            db.insertWithOnConflict(
                    "membership", null, membership, SQLiteDatabase.CONFLICT_IGNORE);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Stages a local delete; the next sync pushes it. A card that never
     * reached the server (no ETag) is simply dropped.
     */
    public void markDeleted(String accountEmail, String key, Card card) {
        if (card.etag == null) {
            removeCard(accountEmail, key);
            return;
        }

        ContentValues values = new ContentValues();
        values.put("deleted", 1);
        getWritableDatabase()
                .update(
                        "card",
                        values,
                        "account_email = ? AND key = ?",
                        new String[] {accountEmail, key});
    }

    /** Drops the row entirely (pushed delete, or never-pushed create). */
    public void removeCard(String accountEmail, String key) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(
                    "membership",
                    "account_email = ? AND card_key = ?",
                    new String[] {accountEmail, key});
            db.delete(
                    "card",
                    "account_email = ? AND key = ?",
                    new String[] {accountEmail, key});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * The addressbook URLs the replica is a member of (staged removals
     * excluded, staged additions included).
     */
    public List<String> loadMemberships(String accountEmail, String key) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "membership",
                        new String[] {"addressbook_url"},
                        "account_email = ? AND card_key = ? AND state != ?",
                        new String[] {accountEmail, key, String.valueOf(MEMBER_REMOVED)},
                        null,
                        null,
                        null)) {
            List<String> books = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                books.add(cursor.getString(0));
            }
            return books;
        }
    }

    /**
     * Stages a membership change (account-level backends); the next
     * sync pushes it. Adding back a staged removal (or the reverse)
     * cancels out to the synced state instead of round-tripping.
     */
    public void stageMembership(
            String accountEmail, String key, String addressbookUrl, boolean added) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Integer state = null;
            try (Cursor cursor =
                    db.query(
                            "membership",
                            new String[] {"state"},
                            "account_email = ? AND card_key = ? AND addressbook_url = ?",
                            new String[] {accountEmail, key, addressbookUrl},
                            null,
                            null,
                            null)) {
                if (cursor.moveToFirst()) {
                    state = cursor.getInt(0);
                }
            }

            if (added) {
                if (state == null) {
                    ContentValues values = new ContentValues();
                    values.put("account_email", accountEmail);
                    values.put("card_key", key);
                    values.put("addressbook_url", addressbookUrl);
                    values.put("state", MEMBER_ADDED);
                    db.insert("membership", null, values);
                } else if (state == MEMBER_REMOVED) {
                    ContentValues values = new ContentValues();
                    values.put("state", MEMBER_SYNCED);
                    db.update(
                            "membership",
                            values,
                            "account_email = ? AND card_key = ? AND addressbook_url = ?",
                            new String[] {accountEmail, key, addressbookUrl});
                }
            } else if (state != null) {
                if (state == MEMBER_ADDED) {
                    db.delete(
                            "membership",
                            "account_email = ? AND card_key = ? AND addressbook_url = ?",
                            new String[] {accountEmail, key, addressbookUrl});
                } else {
                    ContentValues values = new ContentValues();
                    values.put("state", MEMBER_REMOVED);
                    db.update(
                            "membership",
                            values,
                            "account_email = ? AND card_key = ? AND addressbook_url = ?",
                            new String[] {accountEmail, key, addressbookUrl});
                }
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * The addressbook's live cards: last sync plus staged edits, minus
     * staged deletes, resolved through the membership table.
     */
    public List<Card> loadCards(String addressbookUrl) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT c.id, c.uri, c.etag, c.vcard FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.deleted = 0"
                                + " AND c.vcard != '' AND m.state != " + MEMBER_REMOVED,
                        new String[] {addressbookUrl})) {
            return readCards(cursor);
        }
    }

    /**
     * The addressbook's live cards with their write-time index, so the
     * contacts list renders without parsing a single vCard.
     */
    public List<Indexed> loadIndexedCards(String addressbookUrl) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT c.id, c.uri, c.etag, c.vcard, c.name, c.email, c.phone,"
                                + " c.info, c.uid, c.hash, c.conflict_revision,"
                                + " c.conflict_remote_vcard FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.deleted = 0"
                                + " AND c.vcard != '' AND m.state != " + MEMBER_REMOVED,
                        new String[] {addressbookUrl})) {
            List<Indexed> cards = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                boolean conflicted = !cursor.isNull(10) && !cursor.isNull(11);
                cards.add(
                        new Indexed(
                                new Card(
                                        cursor.getString(0),
                                        cursor.isNull(1) ? null : cursor.getString(1),
                                        cursor.isNull(2) ? null : cursor.getString(2),
                                        cursor.getString(3)),
                                cursor.getString(4),
                                cursor.getString(5),
                                cursor.getString(6),
                                cursor.getString(7),
                                cursor.getString(8),
                                cursor.getString(9),
                                conflicted));
            }
            return cards;
        }
    }

    private List<Card> readCards(Cursor cursor) {
        List<Card> cards = new ArrayList<>(cursor.getCount());
        while (cursor.moveToNext()) {
            cards.add(
                    new Card(
                            cursor.getString(0),
                            cursor.isNull(1) ? null : cursor.getString(1),
                            cursor.isNull(2) ? null : cursor.getString(2),
                            cursor.getString(3)));
        }
        return cards;
    }

    // ---- io-offline storage seam --------------------------------------------
    //
    // The engine sees the store as placements: one per (addressbook,
    // card) pair, mapped from the card and membership rows. The row
    // columns carry the engine state losslessly: etag = the base
    // revision, base_vcard = the base body (null when it equals the
    // current one), dirty/deleted/etag-null/conflict_revision = the
    // status ladder, membership states = per-book created/tombstone
    // placements on the account-level backends. Object bodies live in
    // the vcard and base_vcard columns; hashes are computed on the fly
    // (SHA-256 of the vCard text), never stored.
    //
    // Each addressbook has a second collection, the phone spoke
    // (docs/phone-sync-plan.md): the same card rows exposed through the
    // membership rows' phone axis (phone_revision, phone_base,
    // phone_stale, phone_conflict_revision), so the engine reconciles
    // the hub with the book's raw contacts exactly as it does with the
    // server. Cross-spoke propagation is the hub row itself: a
    // phone-won write makes the row diverge from the server base and
    // vice versa.

    /** Marks a collection id as the addressbook's phone spoke. */
    private static final String PHONE_PREFIX = "phone:";

    /** The phone collection id of an addressbook (the second engine spoke). */
    public static String phoneCollection(String url) {
        return PHONE_PREFIX + url;
    }

    /** True when the collection id addresses the phone spoke. */
    public static boolean isPhoneCollection(String collection) {
        return collection.startsWith(PHONE_PREFIX);
    }

    /** The addressbook URL behind a collection id, phone or server. */
    public static String serverUrl(String collection) {
        return isPhoneCollection(collection)
                ? collection.substring(PHONE_PREFIX.length())
                : collection;
    }

    /**
     * Loads one collection for the engine: its placements and last
     * sync checkpoint, as the reply to a load yield.
     */
    public JSONObject loadCollection(String url) throws JSONException {
        if (isPhoneCollection(url)) {
            return loadPhoneCollection(url);
        }

        SQLiteDatabase db = getReadableDatabase();
        String accountEmail = accountEmailOf(db, url);
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
                        client.offlinePlacement(placementFacts(url, cursor, memberships));
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
                        && membership[1].equals(String.valueOf(MEMBER_SYNCED))) {
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
     * Whether the book holds phone-axis work for the engine: a card
     * not yet projected, diverged from its phone base, mid-refetch,
     * conflicted, or gone while still on the phone. One EXISTS query,
     * the store half of the phone pass's quiet-path guard (the member
     * count below matches the provider's side).
     */
    public boolean phonePending(String url) {
        SQLiteDatabase db = getReadableDatabase();
        String accountEmail = accountEmailOf(db, url);
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
                                + " OR ((c.deleted = 1 OR m.state = " + MEMBER_REMOVED + ")"
                                + " AND (m.phone_revision IS NOT NULL"
                                + " OR m.phone_base IS NOT NULL))"
                                + " OR (c.deleted = 0 AND m.state != " + MEMBER_REMOVED
                                + " AND c.vcard != ''"
                                + " AND (m.phone_base IS NULL OR m.phone_base != c.vcard))))",
                        new String[] {url, accountEmail})) {
            return cursor.moveToFirst() && cursor.getInt(0) == 1;
        }
    }

    /** The book's members the phone should mirror (quiet-path count). */
    public int phoneMemberCount(String url) {
        SQLiteDatabase db = getReadableDatabase();
        String accountEmail = accountEmailOf(db, url);
        if (accountEmail == null) {
            return 0;
        }
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.account_email = ?"
                                + " AND c.deleted = 0 AND m.state != " + MEMBER_REMOVED
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
    private JSONObject loadPhoneCollection(String collection) throws JSONException {
        String url = serverUrl(collection);
        SQLiteDatabase db = getReadableDatabase();
        String accountEmail = accountEmailOf(db, url);
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
                        client.offlinePhonePlacement(phonePlacementFacts(collection, cursor));
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
    private static JSONObject phonePlacementFacts(String collection, Cursor cursor)
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
     * Resolves which link ids already map to a stored body, as the
     * reply to a lookup yield. Always empty here: the engine's
     * cross-collection dedup assumes a link id names immutable bytes
     * (an email behind its Message-ID), but contact replicas sharing a
     * vCard UID diverge legitimately, so a body must never stand in
     * for another replica's and every upgrade fetches.
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
        SQLiteDatabase db = getWritableDatabase();
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
        if (isPhoneCollection(url)) {
            return applyPhoneUpsert(db, placement, bodies);
        }
        String accountEmail = accountEmailOf(db, url);
        if (accountEmail == null) {
            return null;
        }
        String handle = placement.getString("handle");
        String key = handleKey(url, handle);
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

        JSONObject plan = client.offlineUpsertPlan(facts);
        switch (plan.getString("action")) {
            case "removeMembership":
                // Only this book's membership goes; the card stays.
                setMembership(db, accountEmail, key, url, MEMBER_REMOVED);
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
                // A staged membership addition: the row itself is untouched.
                setMembership(db, accountEmail, key, url, MEMBER_ADDED);
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
            values.putAll(indexValues(vcard));
        }

        // The plan hands the push base back as a hash: the body it
        // names differs from the held content, so it is resolved and
        // persisted alongside.
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
                "added".equals(plan.getString("memberState")) ? MEMBER_ADDED : MEMBER_SYNCED);

        if (!exists) {
            return "created";
        }
        return vcard.equals(existingVcard) ? null : "changed";
    }

    /**
     * Maps one phone-axis placement back onto its rows: the store
     * gathers the row facts and resolves bodies, the bridge plans the
     * writes (a phone-won change stages the server push, a
     * phone-created card lands as a hub row the server has never
     * seen), and the SQL below executes them. Returns the card effect
     * for the sync report, like the server-axis path.
     */
    private String applyPhoneUpsert(
            SQLiteDatabase db, JSONObject placement, Map<String, String> bodies)
            throws JSONException {
        String url = serverUrl(placement.getString("collection"));
        String accountEmail = accountEmailOf(db, url);
        if (accountEmail == null) {
            return null;
        }
        String handle = placement.getString("handle");
        String key = handleKey(url, handle);

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

        String objectHash = jsonString(placement, "object");
        String body =
                resolvePhoneBody(
                        objectHash, bodies, existingVcard, existingPhoneBase, existingBase);

        JSONObject base = placement.optJSONObject("base");
        JSONObject facts = new JSONObject();
        facts.put("collection", placement.getString("collection"));
        facts.put("status", placement.getString("status"));
        facts.put("conflictRevision", jsonString(placement, "conflictRevision"));
        facts.put("exists", exists);
        facts.put("existingVcard", existingVcard);
        facts.put("existingDirty", existingDirty);
        facts.put("objectPresent", objectHash != null);
        facts.put("body", body);
        facts.put("basePresent", base != null);
        if (base != null) {
            facts.put("baseRevision", jsonString(base, "revision"));
            facts.put("baseObjectHash", jsonString(base, "object"));
        }

        JSONObject plan = client.offlinePhoneUpsertPlan(facts);
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
                values.putAll(emptyIndex());
            } else {
                values.putAll(indexValues(vcard));
            }
            db.insert("card", null, values);
        } else if ("update".equals(action)) {
            JSONObject row = plan.getJSONObject("row");
            String vcard = row.getString("vcard");
            kind = vcard.equals(existingVcard) ? null : "changed";
            ContentValues values = new ContentValues();
            values.put("vcard", vcard);
            values.putAll(indexValues(vcard));
            values.put("dirty", 1);
            values.put("stale", 0);
            if (row.optBoolean("captureBase")) {
                // First divergence from the server state: the held
                // vCard becomes the server push base (same capture as
                // an in-app edit).
                values.put("base_vcard", existingVcard);
            }
            db.update(
                    "card",
                    values,
                    "account_email = ? AND key = ?",
                    new String[] {accountEmail, key});
        }

        // The phone axis on the membership row (created when missing,
        // its staged state left alone otherwise).
        ContentValues member = new ContentValues();
        member.put("account_email", accountEmail);
        member.put("card_key", key);
        member.put("addressbook_url", url);
        db.insertWithOnConflict("membership", null, member, SQLiteDatabase.CONFLICT_IGNORE);

        JSONObject plannedAxis = plan.getJSONObject("axis");
        ContentValues axis = new ContentValues();
        axis.put("phone_revision", jsonString(plannedAxis, "phoneRevision"));
        String phoneBaseHash = jsonString(plannedAxis, "phoneBaseHash");
        if (phoneBaseHash != null) {
            String baseBody =
                    resolvePhoneBody(
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
        axis.put("phone_conflict_revision", jsonString(plannedAxis, "phoneConflict"));
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
    private static String resolvePhoneBody(
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
        if (vcard != null && hash.equals(byteHash(vcard))) {
            return vcard;
        }
        if (phoneBase != null && hash.equals(byteHash(phoneBase))) {
            return phoneBase;
        }
        if (serverBase != null && hash.equals(byteHash(serverBase))) {
            return serverBase;
        }
        android.util.Log.w("cardamum", "unresolved phone object body " + hash);
        return null;
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
        if (isPhoneCollection(url)) {
            applyPhoneDrop(db, url, handle);
            return;
        }
        String accountEmail = accountEmailOf(db, url);
        if (accountEmail == null) {
            return;
        }
        String key = handleKey(url, handle);

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
     * Applies a drop on the phone collection: the raw contact vanished
     * (deleted in a contacts app), so the deletion is staged hub-side
     * for the next server sync to push upstream: on the account-level
     * backends the membership goes when other books still hold the
     * card, the card itself otherwise. The phone axis clears with it.
     */
    private void applyPhoneDrop(SQLiteDatabase db, String collection, String handle) {
        String url = serverUrl(collection);
        String accountEmail = accountEmailOf(db, url);
        if (accountEmail == null) {
            return;
        }
        String key = handleKey(url, handle);

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
            facts.put("otherMemberships", countOtherMemberships(db, accountEmail, key, url));
            plan = client.offlinePhoneDropPlan(facts);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }

        if ("removeMembership".equals(plan.optString("action"))) {
            // NOTE: the replace resets the phone axis columns too.
            setMembership(db, accountEmail, key, url, MEMBER_REMOVED);
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
        if (existingVcard != null && hash.equals(byteHash(existingVcard))) {
            return existingVcard;
        }
        if (existingBase != null && hash.equals(byteHash(existingBase))) {
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
                    if (hash.equals(byteHash(candidate))) {
                        return candidate;
                    }
                }
            }
        }

        android.util.Log.w("cardamum", "unresolved object body " + hash);
        return null;
    }

    /**
     * The row behind an engine handle, for the push adapter: the
     * staged body and push base a change addresses. Null when unknown.
     * Phone handles resolve to the same rows (the card id maps to the
     * same storage key as the server resource name).
     */
    public JSONObject loadRow(String url, String handle) throws JSONException {
        url = serverUrl(url);
        SQLiteDatabase db = getReadableDatabase();
        String accountEmail = accountEmailOf(db, url);
        if (accountEmail == null) {
            return null;
        }

        try (Cursor cursor =
                db.query(
                        "card",
                        new String[] {"id", "uri", "etag", "vcard", "base_vcard", "deleted"},
                        "account_email = ? AND key = ?",
                        new String[] {accountEmail, handleKey(url, handle)},
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
        url = serverUrl(url);
        SQLiteDatabase db = getReadableDatabase();
        String accountEmail = accountEmailOf(db, url);
        if (accountEmail == null) {
            return null;
        }

        try (Cursor cursor =
                db.query(
                        "card",
                        new String[] {"vcard", "base_vcard", "conflict_remote_vcard"},
                        "account_email = ? AND key = ?",
                        new String[] {accountEmail, handleKey(url, handle)},
                        null,
                        null,
                        null)) {
            if (!cursor.moveToFirst() || cursor.isNull(2)) {
                return null;
            }
            String local = cursor.getString(0);
            JSONObject bodies = new JSONObject();
            bodies.put("local", local);
            // base_vcard is null when the base equals the current body.
            bodies.put("base", cursor.isNull(1) ? local : cursor.getString(1));
            bodies.put("remote", cursor.getString(2));
            return bodies;
        }
    }

    /** The handles of one collection still awaiting their full body. */
    public List<String> handlesBelowFull(String url) {
        if (isPhoneCollection(url)) {
            return phoneHandlesBelowFull(serverUrl(url));
        }
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT c.uri, c.id FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.deleted = 0"
                                + " AND m.state != " + MEMBER_REMOVED
                                + " AND (c.vcard = '' OR c.stale = 1)",
                        new String[] {url})) {
            List<String> handles = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                String uri = cursor.isNull(0) ? null : cursor.getString(0);
                handles.add(rowHandle(url, uri, cursor.getString(1)));
            }
            return handles;
        }
    }

    /**
     * The phone-axis handles still awaiting their body from the phone:
     * pulled phone creates (no body yet) and phone content changes
     * (stale, refetch pending).
     */
    private List<String> phoneHandlesBelowFull(String url) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT c.id FROM card c"
                                + " JOIN membership m ON m.account_email = c.account_email"
                                + " AND m.card_key = c.key"
                                + " WHERE m.addressbook_url = ? AND c.deleted = 0"
                                + " AND m.state != " + MEMBER_REMOVED
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

    /**
     * One collection's conflicted rows (both sides changed), each with
     * what the resolution merge needs: the staged local body, the push
     * base, and the remote revision observed at conflict time. On the
     * phone collection the base is the phone axis' own.
     */
    public List<JSONObject> loadConflicts(String url) throws JSONException {
        if (isPhoneCollection(url)) {
            return loadPhoneConflicts(serverUrl(url));
        }
        SQLiteDatabase db = getReadableDatabase();
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
                conflict.put("handle", rowHandle(url, uri, cursor.getString(1)));
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

    /** The phone collection's conflicted rows, phone-axis fields. */
    private List<JSONObject> loadPhoneConflicts(String url) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
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
     * Refreshes a conflicted row's observed remote revision before its
     * resolution, so the resolving push is conditioned on the state the
     * merge actually reconciled against.
     */
    public void setConflictRevision(String url, String handle, String revision) {
        SQLiteDatabase db = getWritableDatabase();
        String serverUrl = serverUrl(url);
        String accountEmail = accountEmailOf(db, serverUrl);
        if (accountEmail == null) {
            return;
        }
        String key = handleKey(serverUrl, handle);

        if (isPhoneCollection(url)) {
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
        SQLiteDatabase db = getWritableDatabase();
        String serverUrl = serverUrl(url);
        String accountEmail = accountEmailOf(db, serverUrl);
        if (accountEmail == null) {
            return;
        }
        String key = handleKey(serverUrl, handle);

        ContentValues values = new ContentValues();
        values.put("conflict_remote_vcard", remoteVcard);
        db.update(
                "card",
                values,
                "account_email = ? AND key = ?",
                new String[] {accountEmail, key});
    }

    /** The byte-exact content hash naming a body in the object store. */
    public static String byteHash(String text) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * The storage key behind an engine handle: the bare server id on
     * account-level backends, the collection-scoped key elsewhere.
     */
    static String handleKey(String url, String handle) {
        return isAccountLevelUrl(url) ? handle : key(url, handleId(url, handle));
    }

    /** The display id behind an engine handle. */
    static String handleId(String url, String handle) {
        return isCarddavUrl(url) && handle.endsWith(".vcf")
                ? handle.substring(0, handle.length() - 4)
                : handle;
    }

    /**
     * A row's engine handle: its resource name, reconstructed for
     * never-pushed rows (CardDAV creations name the resource id.vcf;
     * the other backends address the bare id).
     */
    static String rowHandle(String url, String uri, String id) {
        if (uri != null && !uri.isEmpty()) {
            return uri;
        }
        return isCarddavUrl(url) ? id + ".vcf" : id;
    }

    /** True for the backends whose cards are account-level (JMAP, Google). */
    private static boolean isAccountLevelUrl(String url) {
        return CardamumClient.isAccountLevel(url);
    }

    /** True for plain CardDAV collections. */
    private static boolean isCarddavUrl(String url) {
        return CardamumClient.isCarddav(url);
    }

    /** The account owning the addressbook, null when unknown. */
    private static String accountEmailOf(SQLiteDatabase db, String url) {
        try (Cursor cursor =
                db.query(
                        "addressbook",
                        new String[] {"account_email"},
                        "url = ?",
                        new String[] {url},
                        null,
                        null,
                        null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
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

    /**
     * The phone axis' last converged body behind an engine handle, the
     * patch base of a phone read-back; null when never converged.
     */
    public String loadPhoneBase(String url, String handle) {
        SQLiteDatabase db = getReadableDatabase();
        String serverUrl = serverUrl(url);
        String accountEmail = accountEmailOf(db, serverUrl);
        if (accountEmail == null) {
            return null;
        }
        return phoneBaseOf(db, accountEmail, handleKey(serverUrl, handle), serverUrl);
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

    /** The card's membership count outside `excludeUrl`. */
    private static int countOtherMemberships(
            SQLiteDatabase db, String accountEmail, String key, String excludeUrl) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT COUNT(*) FROM membership WHERE account_email = ?"
                                + " AND card_key = ? AND addressbook_url != ?"
                                + " AND state != " + MEMBER_REMOVED,
                        new String[] {accountEmail, key, excludeUrl})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    /** Inserts or updates one membership row with the given state. */
    private static void setMembership(
            SQLiteDatabase db, String accountEmail, String key, String url, int state) {
        // Update-then-insert, never INSERT OR REPLACE: the membership
        // row also carries the phone axis, and REPLACE deletes and
        // reinserts the row, silently wiping phone_revision and
        // phone_base on every server-axis upsert. A wiped axis reads
        // as never-projected, so the next phone pass re-created (and
        // conflicted) every card the server pass had merely touched.
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
    private static ContentValues emptyIndex() {
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
    private static String jsonString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key);
    }
}
