package org.pimalaya.cardamum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.cardamum.client.Addressbook;
import org.pimalaya.cardamum.client.CardamumClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Drives OfflineEngine.serve with hand-built yield envelopes, exactly
 * as the Rust engine upcalls it: the storage ops run against the real
 * store and the real bridge, so these tests pin the driver's wire
 * contract without a network.
 */
@RunWith(RobolectricTestRunner.class)
public class OfflineEngineServeTest {
    private static final String EMAIL = "jane@example.com";
    private static final String BOOK = "https://dav.example.com/books/b1/";

    private CardStore store;
    private OfflineEngine engine;

    @Before
    public void setUp() {
        store = new CardStore(RuntimeEnvironment.getApplication());
        store.replaceAddressbooks(
                EMAIL, List.of(new Addressbook("b1", "Book One", BOOK, null, null)));
        // No account (storage yields only) and no context (no phone
        // spoke): the shape the mutate driver runs with.
        engine = new OfflineEngine(store, new CardamumClient(), null, null);
    }

    @Test
    public void serveWritesThenLoadsTheCollection() throws Exception {
        String vcard =
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:u1\r\nFN:Jane Doe\r\nEND:VCARD\r\n";
        String hash = CardStore.byteHash(vcard);

        JSONObject write = new JSONObject();
        write.put("op", "write");
        write.put(
                "writes",
                new JSONArray()
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
                                                                        .put("revision", "e1")
                                                                        .put("object", hash)))));
        assertEquals("{}", engine.serve(write.toString()));

        JSONObject load = new JSONObject();
        load.put("op", "load");
        load.put("collection", BOOK);
        JSONObject reply = new JSONObject(engine.serve(load.toString()));

        JSONArray placements = reply.getJSONArray("placements");
        assertEquals(1, placements.length());
        JSONObject placement = placements.getJSONObject(0);
        assertEquals("c1.vcf", placement.getString("handle"));
        assertEquals("clean", placement.getString("status"));
        assertEquals("full", placement.getString("level"));
        assertEquals("e1", placement.getJSONObject("base").getString("revision"));
    }

    @Test
    public void serveRejectsUnknownYields() throws Exception {
        JSONObject reply =
                new JSONObject(engine.serve("{\"op\": \"teleport\", \"collection\": \"x\"}"));
        assertTrue(reply.getString("error").contains("Unsupported engine yield"));
    }

    @Test
    public void serveWrapsDriverFailuresAsErrorReplies() throws Exception {
        // A malformed envelope must come back as an error reply, never
        // as a raised exception: the Rust engine aborts its run on the
        // error field.
        JSONObject reply = new JSONObject(engine.serve("{\"op\": \"load\"}"));
        assertTrue(reply.getString("error").length() > 0);
    }
}
