package org.pimalaya.cardamum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.cardamum.client.Addressbook;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Engine-level store tests on the real pieces: Robolectric's SQLite
 * backs the store and the host-built libcardamum.so backs the bridge
 * (indexCard, offlineUpsertPlan), so the rows these tests pin are the
 * rows the app writes.
 */
@RunWith(RobolectricTestRunner.class)
public class CardStoreTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BOOK = "https://dav.example.com/books/b1/";

    /** Membership states, mirroring the store's private constants. */
    private static final int SYNCED = 0;

    private static final int ADDED = 1;
    private static final int REMOVED = 2;

    private CardStore store;

    @Before
    public void setUp() {
        store = new CardStore(RuntimeEnvironment.getApplication());
        store.replaceAddressbooks(
                EMAIL, List.of(new Addressbook("b1", "Book One", BOOK, null, null)));
    }

    @Test
    public void stageMembershipCancelsRoundTrips() {
        // No row yet: adding stages an addition.
        store.stageMembership(EMAIL, "k1", BOOK, true);
        assertEquals(Integer.valueOf(ADDED), membershipState("k1"));

        // Removing a staged addition cancels it out entirely: nothing
        // ever reached the server, so nothing must push.
        store.stageMembership(EMAIL, "k1", BOOK, false);
        assertNull(membershipState("k1"));

        // Removing a synced membership stages the removal; adding it
        // back cancels out to synced instead of round-tripping.
        insertMembership(EMAIL, "k2", SYNCED);
        store.stageMembership(EMAIL, "k2", BOOK, false);
        assertEquals(Integer.valueOf(REMOVED), membershipState("k2"));
        store.stageMembership(EMAIL, "k2", BOOK, true);
        assertEquals(Integer.valueOf(SYNCED), membershipState("k2"));

        // Removing an unknown membership stages nothing.
        store.stageMembership(EMAIL, "k3", BOOK, false);
        assertNull(membershipState("k3"));

        // Adding an already-synced membership changes nothing.
        store.stageMembership(EMAIL, "k2", BOOK, true);
        assertEquals(Integer.valueOf(SYNCED), membershipState("k2"));
    }

    @Test
    public void rebuildCarriesWhatNoRemoteRestores() {
        SQLiteDatabase db = store.getWritableDatabase();
        insertCard(db, LocalBook.ACCOUNT, "local-card", 0, 0);
        insertCard(db, EMAIL, "dirty-card", 1, 0);
        insertCard(db, EMAIL, "deleted-card", 0, 1);
        insertCard(db, EMAIL, "clean-card", 0, 0);
        insertMembership(LocalBook.ACCOUNT, "local-card", SYNCED);
        for (String key : List.of("dirty-card", "deleted-card", "clean-card")) {
            insertMembership(EMAIL, key, SYNCED);
        }
        db.execSQL("UPDATE addressbook SET checkpoint = 'sync-token-1'");

        store.onUpgrade(db, 2, 3);

        // The local book's cards and the unpushed rows survive; the
        // clean remote row goes (the next sync re-downloads it).
        assertTrue(cardExists(db, LocalBook.ACCOUNT, "local-card"));
        assertTrue(cardExists(db, EMAIL, "dirty-card"));
        assertTrue(cardExists(db, EMAIL, "deleted-card"));
        assertFalse(cardExists(db, EMAIL, "clean-card"));

        // Memberships follow their carried cards.
        assertEquals(Integer.valueOf(SYNCED), membershipState("local-card"));
        assertEquals(Integer.valueOf(SYNCED), membershipState("dirty-card"));
        assertNull(membershipState("clean-card"));

        // The checkpoint clears: an incremental round from the old
        // cursor would never re-download the dropped clean rows.
        try (Cursor cursor =
                db.rawQuery("SELECT checkpoint FROM addressbook WHERE url = ?",
                        new String[] {BOOK})) {
            assertTrue(cursor.moveToFirst());
            assertTrue(cursor.isNull(0));
        }

        // The carry scaffolding is gone.
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE name LIKE '%_carry'", null)) {
            assertFalse(cursor.moveToNext());
        }
    }

    @Test
    public void applyWritesLandsARemoteCreateAndKeepsThePhoneAxis() throws Exception {
        String vcard =
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:u1\r\nFN:Jane Doe\r\nEND:VCARD\r\n";
        JSONArray effects = new OfflineStore(store).applyWrites(remoteUpsert(vcard, "e1"));

        // The pull landed as a created card, indexed by the real
        // bridge at write time, its membership synced.
        assertEquals(1, effects.length());
        assertEquals("created", effects.getJSONObject(0).getString("kind"));
        SQLiteDatabase db = store.getWritableDatabase();
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT name, etag, dirty FROM card WHERE uri = 'c1.vcf'", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("Jane Doe", cursor.getString(0));
            assertEquals("e1", cursor.getString(1));
            assertEquals(0, cursor.getInt(2));
        }
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT m.state FROM membership m JOIN card c ON c.key = m.card_key"
                                + " WHERE c.uri = 'c1.vcf'",
                        null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(SYNCED, cursor.getInt(0));
        }

        // Seed the phone axis, then apply a server-axis change: the
        // axis must survive in place (a REPLACE-style write would null
        // it and make the next phone pass re-create the whole book).
        db.execSQL("UPDATE membership SET phone_revision = '7', phone_base = 'PB'");
        String updated =
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:u1\r\nFN:Jane Smith\r\nEND:VCARD\r\n";
        effects = new OfflineStore(store).applyWrites(remoteUpsert(updated, "e2"));

        assertEquals(1, effects.length());
        assertEquals("changed", effects.getJSONObject(0).getString("kind"));
        try (Cursor cursor =
                db.rawQuery("SELECT name, etag FROM card WHERE uri = 'c1.vcf'", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("Jane Smith", cursor.getString(0));
            assertEquals("e2", cursor.getString(1));
        }
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT phone_revision, phone_base FROM membership"
                                + " WHERE addressbook_url = ?",
                        new String[] {BOOK})) {
            assertTrue(cursor.moveToFirst());
            assertEquals("7", cursor.getString(0));
            assertEquals("PB", cursor.getString(1));
        }
    }

    /**
     * The write batch of one pulled card, as the engine emits it: the
     * body into the object store, then the placement upsert naming it.
     */
    private static JSONArray remoteUpsert(String vcard, String etag) throws Exception {
        String hash = CardStore.byteHash(vcard);
        return new JSONArray()
                .put(
                        new JSONObject()
                                .put("op", "storeObject")
                                .put("hash", hash)
                                .put("size", vcard.length())
                                .put("body", vcard))
                .put(
                        new JSONObject()
                                .put("op", "upsert")
                                .put(
                                        "placement",
                                        new JSONObject()
                                                .put("collection", BOOK)
                                                .put("handle", "c1.vcf")
                                                .put("object", hash)
                                                .put("level", "full")
                                                .put("status", "clean")
                                                .put("flags", new JSONArray())
                                                .put(
                                                        "base",
                                                        new JSONObject()
                                                                .put("revision", etag)
                                                                .put("object", hash))));
    }

    private void insertMembership(String email, String key, int state) {
        ContentValues values = new ContentValues();
        values.put("account_email", email);
        values.put("card_key", key);
        values.put("addressbook_url", BOOK);
        values.put("state", state);
        store.getWritableDatabase().insert("membership", null, values);
    }

    /** The membership row's state, or null when no row exists. */
    private Integer membershipState(String key) {
        try (Cursor cursor =
                store.getReadableDatabase()
                        .rawQuery(
                                "SELECT state FROM membership WHERE card_key = ?"
                                        + " AND addressbook_url = ?",
                                new String[] {key, BOOK})) {
            return cursor.moveToFirst() ? cursor.getInt(0) : null;
        }
    }

    private static void insertCard(
            SQLiteDatabase db, String email, String key, int dirty, int deleted) {
        ContentValues values = new ContentValues();
        values.put("account_email", email);
        values.put("key", key);
        values.put("id", key);
        values.put("vcard", "BEGIN:VCARD\r\nVERSION:4.0\r\nEND:VCARD\r\n");
        values.put("dirty", dirty);
        values.put("deleted", deleted);
        db.insert("card", null, values);
    }

    private static boolean cardExists(SQLiteDatabase db, String email, String key) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM card WHERE account_email = ? AND key = ?",
                        new String[] {email, key})) {
            return cursor.moveToNext();
        }
    }
}
