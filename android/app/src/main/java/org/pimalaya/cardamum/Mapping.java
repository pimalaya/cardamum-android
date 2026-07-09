package org.pimalaya.cardamum;

import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.provider.ContactsContract.Data;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The pure half of the phone spoke, both directions: neutral field
 * model to ContactsContract data rows and back, as plain maps keyed by
 * the wire column names (docs/contacts-mapping.md is the contract,
 * MappingTest pins it). Only compile-time Android constants are
 * referenced, so the whole mapping runs and is tested on the JVM;
 * PhoneRemote wraps the maps into ContentValues and owns everything
 * that needs a device.
 */
final class Mapping {
    private Mapping() {}

    /**
     * The model fields the phone rows represent: what {@link #model}
     * returns and what a fetched phone contact overwrites in the base
     * card's model (everything else is Cardamum-only and rides along).
     */
    static final String[] FIELDS = {
        "displayName",
        "name",
        "nicknames",
        "phones",
        "emails",
        "addresses",
        "organization",
        "title",
        "role",
        "websites",
        "birthday",
        "notes",
    };

    /** Maps the neutral field model onto ContactsContract data rows. */
    static List<Map<String, Object>> rows(JSONObject model) {
        List<Map<String, Object>> rows = new ArrayList<>();

        // ContactsProvider derives the visible name from the structured
        // components whenever they are present, ignoring DISPLAY_NAME.
        // When the vCard's FN diverges from the natural join of N's
        // parts, FN is authoritative: project it alone so the provider
        // has to use it.
        String display = model.optString("displayName");
        JSONObject n = model.optJSONObject("name");
        String joined =
                n == null
                        ? ""
                        : join(
                                join(
                                        join(n.optString("prefix"), n.optString("given")),
                                        join(n.optString("middle"), n.optString("family"))),
                                n.optString("suffix"));

        Map<String, Object> name = row(StructuredName.CONTENT_ITEM_TYPE);
        name.put(StructuredName.DISPLAY_NAME, display);
        if (n != null && (display.isEmpty() || display.equals(joined))) {
            name.put(StructuredName.FAMILY_NAME, n.optString("family"));
            name.put(StructuredName.GIVEN_NAME, n.optString("given"));
            name.put(StructuredName.MIDDLE_NAME, n.optString("middle"));
            name.put(StructuredName.PREFIX, n.optString("prefix"));
            name.put(StructuredName.SUFFIX, n.optString("suffix"));
        }
        rows.add(name);

        JSONArray nicknames = model.optJSONArray("nicknames");
        for (int index = 0; nicknames != null && index < nicknames.length(); index++) {
            Map<String, Object> row = row(Nickname.CONTENT_ITEM_TYPE);
            row.put(Nickname.NAME, nicknames.optString(index));
            row.put(Nickname.TYPE, Nickname.TYPE_DEFAULT);
            rows.add(row);
        }

        JSONArray phones = model.optJSONArray("phones");
        for (int index = 0; phones != null && index < phones.length(); index++) {
            JSONObject phone = phones.optJSONObject(index);
            Map<String, Object> row = row(Phone.CONTENT_ITEM_TYPE);
            row.put(Phone.NUMBER, phone.optString("number"));
            row.put(Phone.TYPE, phoneType(types(phone)));
            row.put(Phone.IS_PRIMARY, phone.optBoolean("pref") ? 1 : 0);
            rows.add(row);
        }

        JSONArray emails = model.optJSONArray("emails");
        for (int index = 0; emails != null && index < emails.length(); index++) {
            JSONObject email = emails.optJSONObject(index);
            Set<String> types = types(email);
            Map<String, Object> row = row(Email.CONTENT_ITEM_TYPE);
            row.put(Email.ADDRESS, email.optString("address"));
            row.put(
                    Email.TYPE,
                    types.contains("home")
                            ? Email.TYPE_HOME
                            : types.contains("work") ? Email.TYPE_WORK : Email.TYPE_OTHER);
            row.put(Email.IS_PRIMARY, email.optBoolean("pref") ? 1 : 0);
            rows.add(row);
        }

        JSONArray addresses = model.optJSONArray("addresses");
        for (int index = 0; addresses != null && index < addresses.length(); index++) {
            JSONObject adr = addresses.optJSONObject(index);
            Set<String> types = types(adr);
            Map<String, Object> row = row(StructuredPostal.CONTENT_ITEM_TYPE);
            row.put(StructuredPostal.STREET, adr.optString("street"));
            row.put(StructuredPostal.POBOX, adr.optString("pobox"));
            row.put(StructuredPostal.NEIGHBORHOOD, adr.optString("ext"));
            row.put(StructuredPostal.CITY, adr.optString("city"));
            row.put(StructuredPostal.REGION, adr.optString("region"));
            row.put(StructuredPostal.POSTCODE, adr.optString("postcode"));
            row.put(StructuredPostal.COUNTRY, adr.optString("country"));
            row.put(StructuredPostal.FORMATTED_ADDRESS, formatted(adr));
            row.put(
                    StructuredPostal.TYPE,
                    types.contains("home")
                            ? StructuredPostal.TYPE_HOME
                            : types.contains("work")
                                    ? StructuredPostal.TYPE_WORK
                                    : StructuredPostal.TYPE_OTHER);
            rows.add(row);
        }

        JSONObject organization = model.optJSONObject("organization");
        String title = model.optString("title");
        String role = model.optString("role");
        if (organization != null || !title.isEmpty() || !role.isEmpty()) {
            Map<String, Object> row = row(Organization.CONTENT_ITEM_TYPE);
            if (organization != null) {
                row.put(Organization.COMPANY, organization.optString("company"));
                row.put(Organization.DEPARTMENT, organization.optString("department"));
            }
            row.put(Organization.TITLE, title);
            row.put(Organization.JOB_DESCRIPTION, role);
            row.put(Organization.TYPE, Organization.TYPE_WORK);
            rows.add(row);
        }

        JSONArray websites = model.optJSONArray("websites");
        for (int index = 0; websites != null && index < websites.length(); index++) {
            Map<String, Object> row = row(Website.CONTENT_ITEM_TYPE);
            row.put(Website.URL, websites.optString(index));
            row.put(Website.TYPE, Website.TYPE_OTHER);
            rows.add(row);
        }

        String birthday = model.optString("birthday");
        if (!birthday.isEmpty()) {
            Map<String, Object> row = row(Event.CONTENT_ITEM_TYPE);
            row.put(Event.START_DATE, birthday);
            row.put(Event.TYPE, Event.TYPE_BIRTHDAY);
            rows.add(row);
        }

        JSONArray notes = model.optJSONArray("notes");
        for (int index = 0; notes != null && index < notes.length(); index++) {
            Map<String, Object> row = row(Note.CONTENT_ITEM_TYPE);
            row.put(Note.NOTE, notes.optString(index));
            rows.add(row);
        }

        return rows;
    }

    /**
     * Maps ContactsContract data rows back onto the neutral field
     * model, the inverse of {@link #rows}. Every field of
     * {@link #FIELDS} is present in the result, empty when the rows
     * carry nothing, so a phone-side clear reads as a clear.
     */
    static JSONObject model(List<Map<String, Object>> rows) throws JSONException {
        JSONObject model = new JSONObject();
        model.put("displayName", "");
        model.put("name", emptyName());
        model.put("nicknames", new JSONArray());
        model.put("phones", new JSONArray());
        model.put("emails", new JSONArray());
        model.put("addresses", new JSONArray());
        model.put("organization", new JSONObject());
        model.put("title", "");
        model.put("role", "");
        model.put("websites", new JSONArray());
        model.put("birthday", "");
        model.put("notes", new JSONArray());

        for (Map<String, Object> row : rows) {
            switch (string(row, Data.MIMETYPE)) {
                case StructuredName.CONTENT_ITEM_TYPE:
                    model.put("displayName", string(row, StructuredName.DISPLAY_NAME));
                    JSONObject name = model.getJSONObject("name");
                    name.put("prefix", string(row, StructuredName.PREFIX));
                    name.put("given", string(row, StructuredName.GIVEN_NAME));
                    name.put("middle", string(row, StructuredName.MIDDLE_NAME));
                    name.put("family", string(row, StructuredName.FAMILY_NAME));
                    name.put("suffix", string(row, StructuredName.SUFFIX));
                    break;

                case Nickname.CONTENT_ITEM_TYPE:
                    String nickname = string(row, Nickname.NAME);
                    if (!nickname.isEmpty()) {
                        model.getJSONArray("nicknames").put(nickname);
                    }
                    break;

                case Phone.CONTENT_ITEM_TYPE:
                    String phoneNumber = string(row, Phone.NUMBER);
                    if (!phoneNumber.isEmpty()) {
                        JSONObject phone = new JSONObject();
                        phone.put("number", phoneNumber);
                        phone.put("types", phoneTypes(number(row, Phone.TYPE)));
                        phone.put("pref", number(row, Phone.IS_PRIMARY) != 0);
                        model.getJSONArray("phones").put(phone);
                    }
                    break;

                case Email.CONTENT_ITEM_TYPE:
                    String address = string(row, Email.ADDRESS);
                    if (!address.isEmpty()) {
                        JSONObject email = new JSONObject();
                        email.put("address", address);
                        email.put(
                                "types",
                                homeWorkTypes(
                                        number(row, Email.TYPE),
                                        Email.TYPE_HOME,
                                        Email.TYPE_WORK));
                        email.put("pref", number(row, Email.IS_PRIMARY) != 0);
                        model.getJSONArray("emails").put(email);
                    }
                    break;

                case StructuredPostal.CONTENT_ITEM_TYPE:
                    JSONObject adr = readAddress(row);
                    if (adr != null) {
                        model.getJSONArray("addresses").put(adr);
                    }
                    break;

                case Organization.CONTENT_ITEM_TYPE:
                    String company = string(row, Organization.COMPANY);
                    String department = string(row, Organization.DEPARTMENT);
                    if (!company.isEmpty() || !department.isEmpty()) {
                        JSONObject organization = new JSONObject();
                        organization.put("company", company);
                        organization.put("department", department);
                        model.put("organization", organization);
                    }
                    model.put("title", string(row, Organization.TITLE));
                    model.put("role", string(row, Organization.JOB_DESCRIPTION));
                    break;

                case Website.CONTENT_ITEM_TYPE:
                    String url = string(row, Website.URL);
                    if (!url.isEmpty()) {
                        model.getJSONArray("websites").put(url);
                    }
                    break;

                case Event.CONTENT_ITEM_TYPE:
                    if (number(row, Event.TYPE) == Event.TYPE_BIRTHDAY) {
                        model.put("birthday", string(row, Event.START_DATE));
                    }
                    break;

                case Note.CONTENT_ITEM_TYPE:
                    String note = string(row, Note.NOTE);
                    if (!note.isEmpty()) {
                        model.getJSONArray("notes").put(note);
                    }
                    break;

                default:
                    // Kinds outside the mapping (photo, groups,
                    // third-party rows) stay phone-side.
                    break;
            }
        }

        return model;
    }

    /**
     * Overlays the phone's field model onto the base card's model: a
     * field is taken from the phone only when it differs from what the
     * base itself projects onto the phone, so mapping lossiness never
     * reads as a phone edit, and every field outside {@link #FIELDS}
     * rides along from the base untouched.
     */
    static JSONObject merge(JSONObject base, JSONObject phone) throws JSONException {
        JSONObject baseView = model(rows(base));

        JSONObject merged = new JSONObject();
        for (Iterator<String> keys = base.keys(); keys.hasNext(); ) {
            String key = keys.next();
            merged.put(key, base.get(key));
        }

        for (String field : FIELDS) {
            // Both sides come out of model(), so equal content prints
            // equal (same construction order on either side).
            String phoneValue = String.valueOf(phone.opt(field));
            if (!phoneValue.equals(String.valueOf(baseView.opt(field)))) {
                merged.put(field, phone.get(field));
            }
        }

        return merged;
    }

    /** vCard TYPE set to the Android phone type, per docs/contacts-mapping.md. */
    static int phoneType(Set<String> types) {
        if (types.contains("fax")) {
            return types.contains("work")
                    ? Phone.TYPE_FAX_WORK
                    : types.contains("home") ? Phone.TYPE_FAX_HOME : Phone.TYPE_OTHER_FAX;
        }
        if (types.contains("cell")) {
            return types.contains("work") ? Phone.TYPE_WORK_MOBILE : Phone.TYPE_MOBILE;
        }
        if (types.contains("pager")) {
            return types.contains("work") ? Phone.TYPE_WORK_PAGER : Phone.TYPE_PAGER;
        }
        if (types.contains("car")) {
            return Phone.TYPE_CAR;
        }
        if (types.contains("isdn")) {
            return Phone.TYPE_ISDN;
        }
        if (types.contains("work")) {
            return Phone.TYPE_WORK;
        }
        if (types.contains("home")) {
            return Phone.TYPE_HOME;
        }
        return Phone.TYPE_OTHER;
    }

    /**
     * Android phone type back to the vCard TYPE set, the inverse of
     * {@link #phoneType}; exotic types map to the empty set (a bare
     * TEL), per docs/contacts-mapping.md.
     */
    static JSONArray phoneTypes(int type) {
        switch (type) {
            case Phone.TYPE_HOME:
                return typeArray("home");
            case Phone.TYPE_MOBILE:
                return typeArray("cell");
            case Phone.TYPE_WORK:
                return typeArray("work");
            case Phone.TYPE_FAX_WORK:
                return typeArray("work", "fax");
            case Phone.TYPE_FAX_HOME:
                return typeArray("home", "fax");
            case Phone.TYPE_OTHER_FAX:
                return typeArray("fax");
            case Phone.TYPE_PAGER:
                return typeArray("pager");
            case Phone.TYPE_WORK_PAGER:
                return typeArray("work", "pager");
            case Phone.TYPE_WORK_MOBILE:
                return typeArray("work", "cell");
            case Phone.TYPE_CAR:
                return typeArray("car");
            case Phone.TYPE_ISDN:
                return typeArray("isdn");
            default:
                return new JSONArray();
        }
    }

    /** HOME/WORK-style Android type back to the vCard TYPE set. */
    static JSONArray homeWorkTypes(int type, int home, int work) {
        if (type == home) {
            return typeArray("home");
        }
        if (type == work) {
            return typeArray("work");
        }
        return new JSONArray();
    }

    /** One postal row back to a model address, null when empty. */
    private static JSONObject readAddress(Map<String, Object> row) throws JSONException {
        JSONObject adr = new JSONObject();
        adr.put("street", string(row, StructuredPostal.STREET));
        adr.put("pobox", string(row, StructuredPostal.POBOX));
        adr.put("ext", string(row, StructuredPostal.NEIGHBORHOOD));
        adr.put("city", string(row, StructuredPostal.CITY));
        adr.put("region", string(row, StructuredPostal.REGION));
        adr.put("postcode", string(row, StructuredPostal.POSTCODE));
        adr.put("country", string(row, StructuredPostal.COUNTRY));

        boolean empty = true;
        for (Iterator<String> keys = adr.keys(); keys.hasNext(); ) {
            empty &= adr.getString(keys.next()).isEmpty();
        }
        if (empty) {
            // A free-form-only address (phone apps allow it) rides in
            // the street component, per docs/contacts-mapping.md.
            String formatted = string(row, StructuredPostal.FORMATTED_ADDRESS);
            if (formatted.isEmpty()) {
                return null;
            }
            adr.put("street", formatted);
        }

        adr.put(
                "types",
                homeWorkTypes(
                        number(row, StructuredPostal.TYPE),
                        StructuredPostal.TYPE_HOME,
                        StructuredPostal.TYPE_WORK));
        adr.put("pref", number(row, StructuredPostal.IS_PRIMARY) != 0);
        return adr;
    }

    /** The all-empty structured name every inverse model starts from. */
    private static JSONObject emptyName() throws JSONException {
        JSONObject name = new JSONObject();
        name.put("prefix", "");
        name.put("given", "");
        name.put("middle", "");
        name.put("family", "");
        name.put("suffix", "");
        return name;
    }

    private static JSONArray typeArray(String... types) {
        JSONArray array = new JSONArray();
        for (String type : types) {
            array.put(type);
        }
        return array;
    }

    /** A row's column as text, empty when absent. */
    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** A row's column as a number, 0 when absent or malformed. */
    private static int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static Map<String, Object> row(String mimetype) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(Data.MIMETYPE, mimetype);
        return row;
    }

    private static Set<String> types(JSONObject entry) {
        Set<String> types = new HashSet<>();
        JSONArray array = entry == null ? null : entry.optJSONArray("types");
        for (int index = 0; array != null && index < array.length(); index++) {
            types.add(array.optString(index));
        }
        return types;
    }

    /** Composes the display form contact apps show for an address. */
    private static String formatted(JSONObject adr) {
        StringBuilder out = new StringBuilder();
        appendLine(out, adr.optString("street"));
        appendLine(out, join(adr.optString("postcode"), adr.optString("city")));
        appendLine(out, adr.optString("region"));
        appendLine(out, adr.optString("country"));
        return out.toString();
    }

    private static void appendLine(StringBuilder out, String line) {
        if (!line.isEmpty()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
    }

    private static String join(String left, String right) {
        if (left.isEmpty()) {
            return right;
        }
        return right.isEmpty() ? left : left + " " + right;
    }
}
