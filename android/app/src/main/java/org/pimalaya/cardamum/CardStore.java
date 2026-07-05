package org.pimalaya.cardamum;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import org.pimalaya.cardamum.client.Addressbook;
import org.pimalaya.cardamum.client.Card;

/**
 * The sync base and offline working copy: a local SQLite snapshot of the
 * remote addressbooks that the screens read and the editor writes. Local
 * edits are staged here (dirty and deleted flags) and pushed by the next
 * sync; the app never talks to the remote outside a sync. Local phone
 * contacts (ContactsContract) are never touched. This becomes the second
 * way of the three-way merge once the io-offline engine lands (see
 * docs/design.md).
 */
public class CardStore extends SQLiteOpenHelper {
    private static final String DATABASE = "cards.db";
    private static final int VERSION = 3;

    /** A staged local change awaiting push: the card and its kind of change. */
    public static final class Pending {
        public final Card card;
        public final boolean deleted;

        Pending(Card card, boolean deleted) {
            this.card = card;
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
                        + "id TEXT NOT NULL, "
                        + "name TEXT NOT NULL, "
                        + "description TEXT, "
                        + "color TEXT)");
        db.execSQL(
                "CREATE TABLE card ("
                        + "addressbook_url TEXT NOT NULL, "
                        + "id TEXT NOT NULL, "
                        + "uri TEXT, "
                        + "etag TEXT, "
                        + "vcard TEXT NOT NULL, "
                        + "dirty INTEGER NOT NULL DEFAULT 0, "
                        + "deleted INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (addressbook_url, id))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // Pre-staging schema: a plain snapshot of the remote,
            // rebuilding it only costs the next sync.
            db.execSQL("DROP TABLE IF EXISTS addressbook");
            db.execSQL("DROP TABLE IF EXISTS card");
            onCreate(db);
            return;
        }

        if (oldVersion < 3) {
            // Additive: staged rows survive, their uri is backfilled by
            // the next fetch.
            db.execSQL("ALTER TABLE card ADD COLUMN uri TEXT");
        }
    }

    /** Replaces the synced addressbooks (the user's selection) and drops orphaned cards. */
    public void replaceAddressbooks(List<Addressbook> books) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("addressbook", null, null);

            for (Addressbook book : books) {
                ContentValues values = new ContentValues();
                values.put("url", book.url);
                values.put("id", book.id);
                values.put("name", book.name);
                values.put("description", book.description);
                values.put("color", book.color);
                db.insert("addressbook", null, values);
            }

            db.delete("card", "addressbook_url NOT IN (SELECT url FROM addressbook)", null);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
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

    /** Stages a local create or edit; the next sync pushes it. */
    public void saveLocal(String addressbookUrl, Card card) {
        ContentValues values = new ContentValues();
        values.put("addressbook_url", addressbookUrl);
        values.put("id", card.id);
        values.put("uri", card.uri);
        values.put("etag", card.etag);
        values.put("vcard", card.vcard);
        values.put("dirty", 1);
        values.put("deleted", 0);
        getWritableDatabase()
                .insertWithOnConflict("card", null, values, SQLiteDatabase.CONFLICT_REPLACE);
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

    /** Marks a staged change as pushed: fresh ETag and uri, clean flags. */
    public void confirmPush(String addressbookUrl, Card card) {
        ContentValues values = new ContentValues();
        values.put("uri", card.uri);
        values.put("etag", card.etag);
        values.put("vcard", card.vcard);
        values.put("dirty", 0);
        getWritableDatabase()
                .update(
                        "card",
                        values,
                        "addressbook_url = ? AND id = ?",
                        new String[] {addressbookUrl, card.id});
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
                        new String[] {"id", "uri", "etag", "vcard", "deleted"},
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
                                cursor.getInt(4) == 1));
            }
            return pending;
        }
    }

    /** The synced addressbooks, empty before the first selection. */
    public List<Addressbook> loadAddressbooks() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor =
                db.query(
                        "addressbook",
                        new String[] {"url", "id", "name", "description", "color"},
                        null,
                        null,
                        null,
                        null,
                        "name")) {
            List<Addressbook> books = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                books.add(
                        new Addressbook(
                                cursor.getString(1),
                                cursor.getString(2),
                                cursor.getString(0),
                                cursor.isNull(3) ? null : cursor.getString(3),
                                cursor.isNull(4) ? null : cursor.getString(4)));
            }
            return books;
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
