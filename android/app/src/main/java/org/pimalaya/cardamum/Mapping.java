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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The pure half of the phone projection: neutral field model to
 * ContactsContract data rows, as plain maps keyed by the wire column
 * names (docs/contacts-mapping.md is the contract, MappingTest pins it).
 * Only compile-time Android constants are referenced, so the whole
 * mapping runs and is tested on the JVM; Projector wraps the maps into
 * ContentValues and owns everything that needs a device.
 */
final class Mapping {
    private Mapping() {}

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
