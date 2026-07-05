package org.pimalaya.cardamum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
    public void nameComponentsProjectWhenDisplayMatchesTheirJoin() throws Exception {
        JSONObject model =
                new JSONObject()
                        .put("displayName", "Jane Q. Doe Jr.")
                        .put(
                                "name",
                                new JSONObject()
                                        .put("prefix", "")
                                        .put("given", "Jane")
                                        .put("middle", "Q.")
                                        .put("family", "Doe")
                                        .put("suffix", "Jr."));

        Map<String, Object> name = firstOfType(model, "vnd.android.cursor.item/name");
        assertEquals("Jane Q. Doe Jr.", name.get("data1"));
        assertEquals("Jane", name.get("data2"));
        assertEquals("Doe", name.get("data3"));
        assertEquals("Q.", name.get("data5"));
        assertEquals("Jr.", name.get("data6"));
    }

    @Test
    public void divergentDisplayNameDropsComponentsSoItWins() throws Exception {
        // Regression: ContactsProvider derives the visible name from the
        // structured components when present, so an FN-only edit must
        // project without them.
        JSONObject model =
                new JSONObject()
                        .put("displayName", "Jane Doe Yo")
                        .put(
                                "name",
                                new JSONObject().put("given", "Jane").put("family", "Doe"));

        Map<String, Object> name = firstOfType(model, "vnd.android.cursor.item/name");
        assertEquals("Jane Doe Yo", name.get("data1"));
        assertNull(name.get("data2"));
        assertNull(name.get("data3"));
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
