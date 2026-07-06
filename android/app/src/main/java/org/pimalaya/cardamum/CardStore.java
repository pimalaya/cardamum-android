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
import org.pimalaya.cardamum.client.Addressbook;
import org.pimalaya.cardamum.client.Card;

/**
 * The sync base and offline working copy: a local SQLite snapshot of the
 * remote addressbooks that the screens read and the editor writes. Every
 * addressbook is tagged with the account it belongs to and whether the
 * user is subscribed to it (subscription drives the home listing and
 * which addressbooks sync). Local edits are staged here (dirty and
 * deleted flags) and pushed by the next sync; the app never talks to the
 * remote outside a sync.
 */
public class CardStore extends SQLiteOpenHelper {
    private static final String DATABASE = "cards.db";
    private static final int VERSION = 5;

    /** A staged local change awaiting push: the card and its kind of change. */
    public static final class Pending {
        public final Card card;

        /**
         * The card's vCard as last synced with the server, null when
         * unknown; the push diffs the edit against it (Graph PATCH).
         */
        public final String baseVcard;

        public final boolean deleted;

        Pending(Card card, String baseVcard, boolean deleted) {
            this.card = card;
            this.baseVcard = baseVcard;
            this.deleted = deleted;
        }
    }

    public CardStore(Context context) {
        super(context, DATABASE, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE addressbook ("
                        + "url TEXT PRIMARY KEY, "
                        + "account_email TEXT NOT NULL, "
                        + "id TEXT NOT NULL, "
                        + "name TEXT NOT NULL, "
                        + "description TEXT, "
                        + "color TEXT, "
                        + "subscribed INTEGER NOT NULL DEFAULT 1)");
        db.execSQL(
                "CREATE TABLE card ("
                        + "addressbook_url TEXT NOT NULL, "
                        + "id TEXT NOT NULL, "
                        + "uri TEXT, "
                        + "etag TEXT, "
                        + "vcard TEXT NOT NULL, "
                        + "base_vcard TEXT, "
                        + "dirty INTEGER NOT NULL DEFAULT 0, "
                        + "deleted INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (addressbook_url, id))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // NOTE: schema changes (v4 multi-account, v5 push base) rebuild
        // both tables; it only costs the next sync.
        db.execSQL("DROP TABLE IF EXISTS addressbook");
        db.execSQL("DROP TABLE IF EXISTS card");
        onCreate(db);
    }

    /**
     * Replaces one account's addressbooks with the fetched set, keeping
     * the subscription of any addressbook already known (new ones default
     * to subscribed) and dropping cards orphaned by the replacement.
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

            db.delete("card", "addressbook_url NOT IN (SELECT url FROM addressbook)", null);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Sets the subscribed addressbooks to exactly the given URLs and
     * drops the cards of any addressbook that just became unsubscribed
     * (an unsubscribed addressbook no longer syncs, so its cached cards
     * go too).
     */
    public void setSubscriptions(Set<String> subscribedUrls) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues off = new ContentValues();
            off.put("subscribed", 0);
            db.update("addressbook", off, null, null);

            ContentValues on = new ContentValues();
            on.put("subscribed", 1);
            for (String url : subscribedUrls) {
                db.update("addressbook", on, "url = ?", new String[] {url});
            }

            db.delete(
                    "card",
                    "addressbook_url IN (SELECT url FROM addressbook WHERE subscribed = 0)",
                    null);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Drops an account and everything under it (its addressbooks and cards). */
    public void removeAccount(String accountEmail) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(
                    "card",
                    "addressbook_url IN (SELECT url FROM addressbook WHERE account_email = ?)",
                    new String[] {accountEmail});
            db.delete("addressbook", "account_email = ?", new String[] {accountEmail});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
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
                            "url", "account_email", "id", "name", "description", "color", "subscribed"
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
                books.add(new BookEntry(book, cursor.getString(1), cursor.getInt(6) == 1));
            }
            return books;
        }
    }

    /**
     * Replaces the addressbook's synced cards with the fetched set,
     * leaving staged local changes (dirty or deleted rows) untouched: a
     * fetched card with a staged counterpart is skipped, the staged
     * version wins until it is pushed.
     */
    public void replaceCards(String addressbookUrl, List<Card> cards) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(
                    "card",
                    "addressbook_url = ? AND dirty = 0 AND deleted = 0",
                    new String[] {addressbookUrl});

            for (Card card : cards) {
                // Staged rows keep their content but learn the server's
                // resource name, so the push addresses the right path.
                ContentValues uriOnly = new ContentValues();
                uriOnly.put("uri", card.uri);
                db.update(
                        "card",
                        uriOnly,
                        "addressbook_url = ? AND id = ?",
                        new String[] {addressbookUrl, card.id});

                ContentValues values = new ContentValues();
                values.put("addressbook_url", addressbookUrl);
                values.put("id", card.id);
                values.put("uri", card.uri);
                values.put("etag", card.etag);
                values.put("vcard", card.vcard);
                db.insertWithOnConflict("card", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Stages a local create or edit; the next sync pushes it. The first
     * edit of a clean card captures its vCard as the push base (what
     * the server last confirmed); follow-up edits before a sync keep
     * that original base so the push still diffs against server state.
     */
    public void saveLocal(String addressbookUrl, Card card) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String baseVcard = null;
            try (Cursor cursor =
                    db.query(
                            "card",
                            new String[] {"vcard", "base_vcard", "dirty"},
                            "addressbook_url = ? AND id = ?",
                            new String[] {addressbookUrl, card.id},
                            null,
                            null,
                            null)) {
                if (cursor.moveToFirst()) {
                    baseVcard =
                            cursor.getInt(2) == 0
                                    ? cursor.getString(0)
                                    : cursor.isNull(1) ? null : cursor.getString(1);
                }
            }

            ContentValues values = new ContentValues();
            values.put("addressbook_url", addressbookUrl);
            values.put("id", card.id);
            values.put("uri", card.uri);
            values.put("etag", card.etag);
            values.put("vcard", card.vcard);
            values.put("base_vcard", baseVcard);
            values.put("dirty", 1);
            values.put("deleted", 0);
            db.insertWithOnConflict("card", null, values, SQLiteDatabase.CONFLICT_REPLACE);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Stages a local delete; the next sync pushes it. A card that never
     * reached the server (no ETag) is simply dropped.
     */
    public void markDeleted(String addressbookUrl, Card card) {
        if (card.etag == null) {
            removeCard(addressbookUrl, card.id);
            return;
        }

        ContentValues values = new ContentValues();
        values.put("deleted", 1);
        getWritableDatabase()
                .update(
                        "card",
                        values,
                        "addressbook_url = ? AND id = ?",
                        new String[] {addressbookUrl, card.id});
    }

    /**
     * Marks the staged change at the old id as pushed: fresh ETag, uri
     * and vcard, clean flags. The confirmed card may carry a new id
     * when the server names created resources itself (Microsoft Graph
     * assigns ids; CardDAV keeps ours).
     */
    public void confirmPush(String addressbookUrl, String oldId, Card card) {
        ContentValues values = new ContentValues();
        values.put("id", card.id);
        values.put("uri", card.uri);
        values.put("etag", card.etag);
        values.put("vcard", card.vcard);
        values.putNull("base_vcard");
        values.put("dirty", 0);
        getWritableDatabase()
                .update(
                        "card",
                        values,
                        "addressbook_url = ? AND id = ?",
                        new String[] {addressbookUrl, oldId});
    }

    /** Drops the row entirely (pushed delete, or never-pushed create). */
    public void removeCard(String addressbookUrl, String id) {
        getWritableDatabase()
                .delete(
                        "card",
                        "addressbook_url = ? AND id = ?",
                        new String[] {addressbookUrl, id});
    }

    /** The addressbook's staged local changes, deletes first. */
    public List<Pending> loadPending(String addressbookUrl) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "card",
                        new String[] {"id", "uri", "etag", "vcard", "base_vcard", "deleted"},
                        "addressbook_url = ? AND (dirty = 1 OR deleted = 1)",
                        new String[] {addressbookUrl},
                        null,
                        null,
                        "deleted DESC")) {
            List<Pending> pending = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                pending.add(
                        new Pending(
                                new Card(
                                        cursor.getString(0),
                                        cursor.isNull(1) ? null : cursor.getString(1),
                                        cursor.isNull(2) ? null : cursor.getString(2),
                                        cursor.getString(3)),
                                cursor.isNull(4) ? null : cursor.getString(4),
                                cursor.getInt(5) == 1));
            }
            return pending;
        }
    }

    /** The addressbook's live cards: last sync plus staged edits, minus staged deletes. */
    public List<Card> loadCards(String addressbookUrl) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "card",
                        new String[] {"id", "uri", "etag", "vcard"},
                        "addressbook_url = ? AND deleted = 0",
                        new String[] {addressbookUrl},
                        null,
                        null,
                        null)) {
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
    }
}
