package org.pimalaya.cardamum;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Cards;
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
 * belongs to and its three switches: subscribed drives what is
 * displayed, remote_synced and phone_synced the two sync spokes.
 * Local edits are staged here
 * (dirty and deleted flags) and pushed by the next sync; the app never
 * talks to the remote outside a sync.
 */
public class CardStore extends SQLiteOpenHelper {
    private static final String DATABASE = "cards.db";
    // NOTE: a schema or index change rebuilds the card tables
    // (rebuildCardTables carries over what no remote can re-hydrate);
    // addressbook's own additions land as ALTERs in onUpgrade instead.
    private static final int VERSION = 2;

    /** Membership state: mirrored from the server. */
    static final int MEMBER_SYNCED = 0;

    /** Membership state: added locally, awaiting push. */
    static final int MEMBER_ADDED = 1;

    /** Membership state: removed locally, awaiting push. */
    static final int MEMBER_REMOVED = 2;

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
    ContentValues indexValues(String vcard) {
        ContentValues values = new ContentValues();
        try {
            org.json.JSONObject index = Cards.indexCard(vcard);
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
                        + "remote_synced INTEGER NOT NULL DEFAULT 1, "
                        + "phone_synced INTEGER NOT NULL DEFAULT 0, "
                        + "checkpoint TEXT)");
        // NOTE: name/email/phone/uid/hash are a write-time index, so the
        // contacts list never parses vCards at render time and linked
        // replicas compare by normalized content hash.
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
                        // NOTE: remote revision observed at conflict time
                        // (both sides edited); resolving pushes against
                        // it. Null outside conflicts.
                        + "conflict_revision TEXT, "
                        // NOTE: remote body captured at conflict time for
                        // manual offline resolution. Null outside
                        // conflicts.
                        + "conflict_remote_vcard TEXT, "
                        // NOTE: 1 when the kept body is a stale display
                        // copy awaiting refetch.
                        + "stale INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (account_email, key))");
        // NOTE: the phone axis lives per (book, card), not per card:
        // each book is its own Android account with its own raw contact,
        // so per-card columns would ping-pong on account-level backends.
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
        // NOTE: view-layer link exceptions of the merged list
        // (docs/merged-view.md); nothing on any server or in sync
        // depends on them. detached = a replica excluded from automatic
        // grouping, link = group keys joined into a shared cluster.
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS detached ("
                        + "account_email TEXT NOT NULL, "
                        + "card_key TEXT NOT NULL, "
                        + "PRIMARY KEY (account_email, card_key))");
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS link ("
                        + "member TEXT PRIMARY KEY, "
                        + "cluster TEXT NOT NULL)");
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS dismissed_duplicate ("
                        + "group_key TEXT PRIMARY KEY)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // NOTE: the addressbook table must survive the rebuild: syncs
        // walk the stored addressbooks, so dropping them would leave the
        // accounts with nothing to sync from. Its own additions land as
        // ALTERs instead.
        if (oldVersion < 2) {
            db.execSQL(
                    "ALTER TABLE addressbook"
                            + " ADD COLUMN remote_synced INTEGER NOT NULL DEFAULT 1");
        }
        rebuildCardTables(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // NOTE: the default helper throws on downgrade and strands the
        // app; rebuild on this build's schema like an upgrade instead.
        rebuildCardTables(db);
    }

    /**
     * Rebuilds the card tables on the current schema, carrying over
     * what no remote can re-hydrate: every card of the local account
     * (its only copy lives here) and the unpushed rows of the others
     * (dirty edits, staged deletions), plus their membership rows.
     * Only the columns both schemas share cross over, so the carry
     * works for any version pair, in both directions. The surviving
     * addressbooks' checkpoints clear along the way: an incremental
     * round from an old cursor would never re-download the dropped
     * cards, so the next sync must enumerate in full.
     */
    private void rebuildCardTables(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS detached");
        db.execSQL("DROP TABLE IF EXISTS link");
        db.execSQL("DROP TABLE IF EXISTS card_carry");
        db.execSQL("DROP TABLE IF EXISTS membership_carry");
        db.execSQL("ALTER TABLE card RENAME TO card_carry");
        db.execSQL("ALTER TABLE membership RENAME TO membership_carry");
        onCreate(db);

        String cards = sharedColumns(db, "card_carry", "card");
        db.execSQL(
                "INSERT INTO card (" + cards + ") SELECT " + cards
                        + " FROM card_carry WHERE account_email LIKE 'local://%'"
                        + " OR dirty != 0 OR deleted != 0");
        String memberships = sharedColumns(db, "membership_carry", "membership");
        db.execSQL(
                "INSERT INTO membership (" + memberships + ") SELECT " + memberships
                        + " FROM membership_carry WHERE EXISTS (SELECT 1 FROM card"
                        + " WHERE card.account_email = membership_carry.account_email"
                        + " AND card.key = membership_carry.card_key)");

        db.execSQL("DROP TABLE card_carry");
        db.execSQL("DROP TABLE membership_carry");
        db.execSQL("UPDATE addressbook SET checkpoint = NULL");
    }

    /** The comma-joined column names two tables share. */
    private static String sharedColumns(SQLiteDatabase db, String from, String to) {
        Set<String> target = new HashSet<>(columnsOf(db, to));
        List<String> shared = new ArrayList<>();
        for (String column : columnsOf(db, from)) {
            if (target.contains(column)) {
                shared.add(column);
            }
        }
        return String.join(", ", shared);
    }

    /** A table's column names, per PRAGMA table_info. */
    private static List<String> columnsOf(SQLiteDatabase db, String table) {
        List<String> columns = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
            }
        }
        return columns;
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
     * the switches of any addressbook already known (new ones default to
     * subscribed with remote sync on), dropping memberships pointing at
     * vanished books and clean cards left with no membership at all.
     */
    public void replaceAddressbooks(String accountEmail, List<Addressbook> books) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Map<String, int[]> wasSwitched = new HashMap<>();
            try (Cursor cursor =
                    db.query(
                            "addressbook",
                            new String[] {"url", "subscribed", "remote_synced", "phone_synced"},
                            "account_email = ?",
                            new String[] {accountEmail},
                            null,
                            null,
                            null)) {
                while (cursor.moveToNext()) {
                    wasSwitched.put(
                            cursor.getString(0),
                            new int[] {cursor.getInt(1), cursor.getInt(2), cursor.getInt(3)});
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
                int[] switches = wasSwitched.get(book.url);
                values.put("subscribed", switches == null ? 1 : switches[0]);
                values.put("remote_synced", switches == null ? 1 : switches[1]);
                values.put("phone_synced", switches == null ? 0 : switches[2]);
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
     * Sets one addressbook's three switches. Both sync switches require
     * the subscription, so they are forced off whenever the book is not
     * subscribed.
     */
    public void setBookState(
            String url, boolean subscribed, boolean remoteSynced, boolean phoneSynced) {
        boolean remote = subscribed && remoteSynced;
        boolean phone = subscribed && phoneSynced;
        SQLiteDatabase db = getWritableDatabase();

        ContentValues values = new ContentValues();
        values.put("subscribed", subscribed ? 1 : 0);
        values.put("remote_synced", remote ? 1 : 0);
        values.put("phone_synced", phone ? 1 : 0);
        db.update("addressbook", values, "url = ?", new String[] {url});

        if (!phone) {
            // NOTE: mirroring off tears down the raw contacts, so the
            // phone axis must clear too: otherwise turning it back on
            // reads the empty phone as "every contact was deleted" and
            // flushes them, instead of re-projecting as fresh creations.
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
            // NOTE: OR IGNORE steps over a card key that already exists
            // locally.
            db.execSQL(
                    "UPDATE OR IGNORE card SET account_email = ?, etag = NULL,"
                            + " base_vcard = NULL, dirty = 0, conflict_revision = NULL,"
                            + " conflict_remote_vcard = NULL, stale = 0"
                            + " WHERE account_email = ? AND deleted = 0",
                    new String[] {LocalBook.ACCOUNT, accountEmail});

            // NOTE: a card in several books collapses to one local row
            // (OR IGNORE); tombstones are left behind and swept below.
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

        // NOTE: refresh only the name, so a rename across versions lands
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
                            "remote_synced",
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
                                cursor.getInt(7) == 1,
                                cursor.getInt(8) == 1));
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
            // NOTE: editing a conflicted row resolves it: the remote
            // revision observed at conflict time becomes the push base,
            // so the resolving push is conditioned on the resolved state.
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
    static boolean isAccountLevelUrl(String url) {
        return CardamumClient.isAccountLevel(url);
    }

    /** True for plain CardDAV collections. */
    static boolean isCarddavUrl(String url) {
        return CardamumClient.isCarddav(url);
    }

    /** The account owning the addressbook, null when unknown. */
    static String accountEmailOf(SQLiteDatabase db, String url) {
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
}
