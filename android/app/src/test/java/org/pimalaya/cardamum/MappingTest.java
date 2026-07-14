package org.pimalaya.cardamum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Pins the neutral-field-model to ContactsContract wire mapping
 * (docs/contacts-mapping.md): mimetypes, column names and type
 * constants are asserted literally, so any drift in the mapping breaks
 * loudly here instead of silently on phones.
 */
public class MappingTest {
    @Test
    public void displayNameNeverProjectsOnlyComponentsDo() throws Exception {
        // Regression: FN is hub-only. Projected alone it was re-split
        // into the wrong parts by the provider's guesswork; written
        // alongside the parts the provider rewrote it as their join,
        // which then read back as a phone edit clearing FN. The phone
        // gets the structured parts, nothing else.
        JSONObject model =
                new JSONObject()
                        .put("displayName", "Jane Doe Yo")
                        .put(
                                "name",
                                new JSONObject()
                                        .put("prefix", "")
                                        .put("given", "Jane")
                                        .put("middle", "Q.")
                                        .put("family", "Doe")
                                        .put("suffix", "Jr."));

        Map<String, Object> name = firstOfType(model, "vnd.android.cursor.item/name");
        assertNull(name.get("data1"));
        assertEquals("Jane", name.get("data2"));
        assertEquals("Doe", name.get("data3"));
        assertEquals("Q.", name.get("data5"));
        assertEquals("Jr.", name.get("data6"));
    }

    @Test
    public void emptyDisplayNameStillProjectsComponents() throws Exception {
        JSONObject model =
                new JSONObject()
                        .put("displayName", "")
                        .put("name", new JSONObject().put("given", "Jane"));

        Map<String, Object> name = firstOfType(model, "vnd.android.cursor.item/name");
        assertEquals("Jane", name.get("data2"));
    }

    @Test
    public void phoneTypeTable() {
        assertEquals(2, Mapping.phoneType(types("cell")));
        assertEquals(17, Mapping.phoneType(types("cell", "work")));
        assertEquals(1, Mapping.phoneType(types("home")));
        assertEquals(3, Mapping.phoneType(types("work")));
        assertEquals(5, Mapping.phoneType(types("fax", "home")));
        assertEquals(4, Mapping.phoneType(types("fax", "work")));
        assertEquals(13, Mapping.phoneType(types("fax")));
        assertEquals(6, Mapping.phoneType(types("pager")));
        assertEquals(18, Mapping.phoneType(types("pager", "work")));
        assertEquals(9, Mapping.phoneType(types("car")));
        assertEquals(11, Mapping.phoneType(types("isdn")));
        assertEquals(7, Mapping.phoneType(types()));
        assertEquals(7, Mapping.phoneType(types("voice")));
    }

    @Test
    public void phoneRowCarriesNumberTypeAndPref() throws Exception {
        JSONObject model =
                new JSONObject()
                        .put(
                                "phones",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("number", "+331111")
                                                        .put(
                                                                "types",
                                                                new JSONArray().put("cell"))
                                                        .put("pref", true)));

        Map<String, Object> phone = firstOfType(model, "vnd.android.cursor.item/phone_v2");
        assertEquals("+331111", phone.get("data1"));
        assertEquals(2, phone.get("data2"));
        assertEquals(1, phone.get("is_primary"));
    }

    @Test
    public void emailTypesMapToHomeWorkOther() throws Exception {
        assertEquals(1, emailType("home"));
        assertEquals(2, emailType("work"));
        assertEquals(3, emailType("internet"));
    }

    @Test
    public void addressRowIsStructuredWithComposedFormatted() throws Exception {
        JSONObject model =
                new JSONObject()
                        .put(
                                "addresses",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("street", "12 Main St")
                                                        .put("city", "Paris")
                                                        .put("postcode", "75000")
                                                        .put("country", "France")
                                                        .put(
                                                                "types",
                                                                new JSONArray().put("home"))));

        Map<String, Object> adr =
                firstOfType(model, "vnd.android.cursor.item/postal-address_v2");
        assertEquals("12 Main St", adr.get("data4"));
        assertEquals("Paris", adr.get("data7"));
        assertEquals("75000", adr.get("data9"));
        assertEquals("France", adr.get("data10"));
        assertEquals(1, adr.get("data2"));
        assertEquals("12 Main St\n75000 Paris\nFrance", adr.get("data1"));
    }

    @Test
    public void organizationRowOnlyWhenAnyFieldSet() throws Exception {
        JSONObject empty = new JSONObject().put("displayName", "X");
        for (Map<String, Object> row : Mapping.rows(empty)) {
            assertFalse("vnd.android.cursor.item/organization".equals(row.get("mimetype")));
        }

        JSONObject model =
                new JSONObject()
                        .put(
                                "organization",
                                new JSONObject().put("company", "ACME").put("department", "R&D"))
                        .put("title", "Boss")
                        .put("role", "CTO");
        Map<String, Object> org = firstOfType(model, "vnd.android.cursor.item/organization");
        assertEquals("ACME", org.get("data1"));
        assertEquals("R&D", org.get("data5"));
        assertEquals("Boss", org.get("data4"));
        assertEquals("CTO", org.get("data6"));
        assertEquals(1, org.get("data2"));
    }

    @Test
    public void birthdayNicknameWebsiteAndNoteRows() throws Exception {
        JSONObject model =
                new JSONObject()
                        .put("birthday", "1985-04-12")
                        .put("nicknames", new JSONArray().put("JD"))
                        .put("websites", new JSONArray().put("https://doe.org"))
                        .put("notes", new JSONArray().put("a note"));

        Map<String, Object> event = firstOfType(model, "vnd.android.cursor.item/contact_event");
        assertEquals("1985-04-12", event.get("data1"));
        assertEquals(3, event.get("data2"));

        Map<String, Object> nick = firstOfType(model, "vnd.android.cursor.item/nickname");
        assertEquals("JD", nick.get("data1"));

        Map<String, Object> site = firstOfType(model, "vnd.android.cursor.item/website");
        assertEquals("https://doe.org", site.get("data1"));
        assertEquals(7, site.get("data2"));

        Map<String, Object> note = firstOfType(model, "vnd.android.cursor.item/note");
        assertEquals("a note", note.get("data1"));
    }

    @Test
    public void noBirthdayRowWhenEmpty() throws Exception {
        JSONObject model = new JSONObject().put("displayName", "X").put("birthday", "");
        for (Map<String, Object> row : Mapping.rows(model)) {
            assertFalse("vnd.android.cursor.item/contact_event".equals(row.get("mimetype")));
        }
    }

    // ---- Inverse direction (rows to model) -------------------------------------

    @Test
    public void richModelRoundTripsThroughRows() throws Exception {
        // NOTE: the display name stays empty: FN is hub-only, never
        // projected to the phone, so it cannot round-trip through rows.
        JSONObject model =
                new JSONObject()
                        .put("displayName", "")
                        .put(
                                "name",
                                new JSONObject()
                                        .put("prefix", "")
                                        .put("given", "Jane")
                                        .put("middle", "Q.")
                                        .put("family", "Doe")
                                        .put("suffix", "Jr."))
                        .put("nicknames", new JSONArray().put("JD"))
                        .put(
                                "phones",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("number", "+331111")
                                                        .put(
                                                                "types",
                                                                new JSONArray().put("cell"))
                                                        .put("pref", true)))
                        .put(
                                "emails",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("address", "a@b.c")
                                                        .put(
                                                                "types",
                                                                new JSONArray().put("home"))
                                                        .put("pref", false)))
                        .put(
                                "addresses",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("street", "12 Main St")
                                                        .put("pobox", "")
                                                        .put("ext", "")
                                                        .put("city", "Paris")
                                                        .put("region", "")
                                                        .put("postcode", "75000")
                                                        .put("country", "France")
                                                        .put(
                                                                "types",
                                                                new JSONArray().put("home"))
                                                        .put("pref", false)))
                        .put(
                                "organization",
                                new JSONObject().put("company", "ACME").put("department", "R&D"))
                        .put("title", "Boss")
                        .put("role", "CTO")
                        .put("websites", new JSONArray().put("https://doe.org"))
                        .put("birthday", "1985-04-12")
                        .put("notes", new JSONArray().put("a note"));

        JSONObject round = Mapping.model(Mapping.rows(model));
        for (String field : Mapping.FIELDS) {
            assertEquals(
                    field,
                    String.valueOf(model.opt(field)),
                    String.valueOf(round.opt(field)));
        }
    }

    @Test
    public void phoneTypesInvertsThePhoneTypeTable() {
        int[] table = {1, 2, 3, 4, 5, 13, 6, 18, 17, 9, 11};
        for (int type : table) {
            Set<String> set = new HashSet<>();
            JSONArray types = Mapping.phoneTypes(type);
            for (int index = 0; index < types.length(); index++) {
                set.add(types.optString(index));
            }
            assertEquals(type, Mapping.phoneType(set));
        }
        assertEquals(0, Mapping.phoneTypes(7).length());
        assertEquals(0, Mapping.phoneTypes(0).length());
    }

    @Test
    public void displayNameOnlyRowReadsAsBareDisplayName() throws Exception {
        // The shape phone apps author when only a full name is typed.
        Map<String, Object> row = new HashMap<>();
        row.put("mimetype", "vnd.android.cursor.item/name");
        row.put("data1", "Jane Doe");

        JSONObject model = Mapping.model(List.of(row));
        assertEquals("Jane Doe", model.getString("displayName"));
        assertEquals("", model.getJSONObject("name").getString("given"));
        assertEquals("", model.getJSONObject("name").getString("family"));
    }

    @Test
    public void customLabelledNumberReadsAsBareTel() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("mimetype", "vnd.android.cursor.item/phone_v2");
        row.put("data1", "0611");
        row.put("data2", 0);
        row.put("data3", "Batphone");

        JSONObject phone = Mapping.model(List.of(row)).getJSONArray("phones").getJSONObject(0);
        assertEquals("0611", phone.getString("number"));
        assertEquals(0, phone.getJSONArray("types").length());
    }

    @Test
    public void formattedOnlyAddressReadsIntoStreet() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("mimetype", "vnd.android.cursor.item/postal-address_v2");
        row.put("data1", "12 Main St, Paris");
        row.put("data2", 1);

        JSONObject adr = Mapping.model(List.of(row)).getJSONArray("addresses").getJSONObject(0);
        assertEquals("12 Main St, Paris", adr.getString("street"));
        assertEquals("home", adr.getJSONArray("types").getString(0));
    }

    // ---- Field-space merge ------------------------------------------------------

    @Test
    public void mergeTakesOnlyThePhoneEditedFields() throws Exception {
        JSONObject base = divergentBase();
        JSONObject phone = Mapping.model(Mapping.rows(base));
        phone.getJSONArray("phones").getJSONObject(0).put("number", "+332222");

        JSONObject merged = Mapping.merge(base, phone);
        assertEquals(
                "+332222",
                merged.getJSONArray("phones").getJSONObject(0).getString("number"));
        // FN never reaches the phone rows, so an untouched phone must
        // not read as a name clear.
        assertEquals("Doe", merged.getJSONObject("name").getString("family"));
        // Fields outside the phone mapping ride along from the base.
        assertEquals("friends", merged.getJSONArray("categories").getString(0));
    }

    @Test
    public void mergeOfAnUntouchedPhoneIsNull() throws Exception {
        JSONObject base = divergentBase();
        JSONObject phone = Mapping.model(Mapping.rows(base));

        // No field taken means no phone edit: the caller keeps the base
        // card's exact bytes instead of re-encoding a no-op.
        assertNull(Mapping.merge(base, phone));
    }

    /** A base model whose FN diverges from N (lossy on the phone). */
    private static JSONObject divergentBase() throws Exception {
        return new JSONObject()
                .put("displayName", "Jane Doe Yo")
                .put("name", new JSONObject().put("given", "Jane").put("family", "Doe"))
                .put(
                        "phones",
                        new JSONArray()
                                .put(
                                        new JSONObject()
                                                .put("number", "+331111")
                                                .put("types", new JSONArray().put("cell"))
                                                .put("pref", false)))
                .put("categories", new JSONArray().put("friends"));
    }

    // ---- Helpers --------------------------------------------------------------

    private static Map<String, Object> firstOfType(JSONObject model, String mimetype) {
        List<Map<String, Object>> rows = Mapping.rows(model);
        for (Map<String, Object> row : rows) {
            if (mimetype.equals(row.get("mimetype"))) {
                return row;
            }
        }
        throw new AssertionError("no row of type " + mimetype + " in " + rows);
    }

    private static int emailType(String type) throws Exception {
        JSONObject model =
                new JSONObject()
                        .put(
                                "emails",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("address", "a@b.c")
                                                        .put(
                                                                "types",
                                                                new JSONArray().put(type))));
        return (int)
                firstOfType(model, "vnd.android.cursor.item/email_v2").get("data2");
    }

    private static Set<String> types(String... values) {
        Set<String> set = new HashSet<>();
        for (String value : values) {
            set.add(value);
        }
        return set;
    }
}
