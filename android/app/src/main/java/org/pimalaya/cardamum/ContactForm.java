package org.pimalaya.cardamum;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The contact edit form, settings-style like the system apps: one
 * scrolling page of sections (accent sentence-case headers) holding
 * tappable items, each opening a dialog mini-form. The vCard fields
 * group by theme: Identity (FN, N, BDAY, ANNIVERSARY, GENDER), Work
 * (ORG, TITLE, ROLE), then one section per list (NICKNAME, RELATED,
 * TEL, EMAIL, IMPP, ADR, URL, LANG, NOTE); everything the form does
 * not cover is reachable through the advanced raw-property editor.
 *
 * <p>The page renders from a working field model that the dialogs
 * edit in place, so {@link #collect()} just hands the model back;
 * everything beyond the model is preserved by the vCard patch in the
 * Rust bridge. A diverged contact loads in conflict mode: only the
 * disagreeing items show, and the dialogs of conflicted single fields
 * carry one tappable chip per candidate value.
 */
final class ContactForm {
    /** Spinner position to vCard TYPE set, aligned with R.array.phone_types. */
    private static final String[][] PHONE_TYPES = {
        {"cell"}, {"home"}, {"work"}, {"cell", "work"},
        {"fax", "home"}, {"fax", "work"}, {"pager"}, {},
    };

    /** Spinner position to vCard TYPE set, aligned with R.array.email_types. */
    private static final String[][] HOME_WORK_OTHER = {{"home"}, {"work"}, {}};

    /** Spinner position to vCard TYPE set, aligned with R.array.relation_types. */
    private static final String[][] RELATION_TYPES = {
        {"spouse"}, {"child"}, {"parent"}, {"sibling"},
        {"friend"}, {"colleague"}, {"emergency"}, {},
    };

    /** Spinner position to GENDER sex code, aligned with R.array.gender_types. */
    private static final String[] GENDER_SEXES = {"", "M", "F", "O", "N", "U"};

    private static final int TEXT_NAME =
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;

    private final Activity activity;
    private final int accentColor;
    private final int labelColor;
    private final int primaryColor;
    private final ScrollView scroll;
    private final LinearLayout container;

    /** The working field model the dialogs edit in place. */
    private JSONObject model = new JSONObject();

    /** Conflict alternatives by single-field path; null outside conflicts. */
    private JSONObject alternatives;

    /** Changed list sections of a conflict; null outside conflict mode. */
    private Set<String> changedLists;

    ContactForm(Activity activity) {
        this.activity = activity;
        this.accentColor = resolveColor(android.R.attr.colorAccent);
        this.labelColor = resolveColor(android.R.attr.textColorSecondary);
        this.primaryColor = resolveColor(android.R.attr.colorPrimary);
        this.scroll = activity.findViewById(R.id.contact_scroll);
        this.container = activity.findViewById(R.id.contact_form);
    }

    /**
     * Loads the field model (null for a new contact) and renders the
     * page. A non-null `changed` turns on conflict mode: only the
     * disagreeing items show, and `alternatives` feeds the value chips
     * of the conflicted single-field dialogs.
     */
    void load(JSONObject model, JSONObject alternatives, JSONArray changed) {
        this.model = model != null ? model : new JSONObject();
        this.alternatives = alternatives;
        this.changedLists = null;
        if (changed != null) {
            changedLists = new HashSet<>();
            for (int index = 0; index < changed.length(); index++) {
                changedLists.add(changed.optString(index));
            }
        }

        render();
        scroll.scrollTo(0, 0);
    }

    /** The edited field model. */
    JSONObject collect() {
        return model;
    }

    /** The birthday as picked, empty when unset. */
    String birthday() {
        return model.optString("birthday").trim();
    }

    /** The anniversary as picked, empty when unset. */
    String anniversary() {
        return model.optString("anniversary").trim();
    }

    // ---- Page rendering ---------------------------------------------------

    private boolean conflict() {
        return changedLists != null;
    }

    /** Rebuilds the whole page from the working model. */
    private void render() {
        container.removeAllViews();

        // Identity holds the solo fields: the display name (its own
        // line), the name parts, the dates and the gender. Every
        // multi-valued list is its own section below.
        List<View> identity = new ArrayList<>();
        if (!conflict() || conflicted("displayName")) {
            identity.add(
                    entryItem(
                            getS(R.string.hint_display_name),
                            value(model.optString("displayName")),
                            this::displayNameDialog));
        }
        if (!conflict()
                || conflicted(
                        "name.prefix",
                        "name.given",
                        "name.middle",
                        "name.family",
                        "name.suffix")) {
            identity.add(
                    entryItem(getS(R.string.item_name), nameSummary(), this::nameDialog));
        }
        if (!conflict() || conflicted("birthday")) {
            identity.add(
                    entryItem(
                            getS(R.string.hint_birthday),
                            value(model.optString("birthday")),
                            () -> pickDate("birthday")));
        }
        if (!conflict() || conflicted("anniversary")) {
            identity.add(
                    entryItem(
                            getS(R.string.item_anniversary),
                            value(model.optString("anniversary")),
                            () -> pickDate("anniversary")));
        }
        if (!conflict() || conflicted("gender.sex", "gender.identity")) {
            identity.add(
                    entryItem(getS(R.string.item_gender), genderSummary(), this::genderDialog));
        }
        section(R.string.section_identity, R.drawable.ic_section_person, identity);

        if (!conflict() || changedLists.contains("nicknames")) {
            List<View> items = new ArrayList<>();
            JSONArray nicknames = array("nicknames");
            for (int index = 0; index < nicknames.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                nicknames.optString(index),
                                null,
                                () -> stringDialog("nicknames", at, R.string.item_nickname, 0,
                                        TEXT_NAME)));
            }
            listSection(R.string.section_nicknames, R.drawable.ic_section_label, R.string.add_nickname, items,
                    () -> stringDialog("nicknames", -1, R.string.item_nickname, 0, TEXT_NAME));
        }

        List<View> work = new ArrayList<>();
        if (!conflict()
                || conflicted(
                        "organization.company", "organization.department", "title", "role")) {
            work.add(
                    entryItem(
                            getS(R.string.item_organization),
                            organizationSummary(),
                            this::organizationDialog));
        }
        section(R.string.tab_work, R.drawable.ic_section_work, work);

        if (!conflict() || changedLists.contains("relations")) {
            List<View> items = new ArrayList<>();
            JSONArray relations = array("relations");
            for (int index = 0; index < relations.length(); index++) {
                int at = index;
                JSONObject entry = relations.optJSONObject(index);
                items.add(
                        entryItem(
                                entry.optString("value"),
                                typeLabel(
                                        R.array.relation_types,
                                        typeIndex(entry, RELATION_TYPES)),
                                () -> relationDialog(at)));
            }
            listSection(R.string.section_relations, R.drawable.ic_section_group, R.string.add_relation, items,
                    () -> relationDialog(-1));
        }

        if (!conflict() || changedLists.contains("phones")) {
            List<View> items = new ArrayList<>();
            JSONArray phones = array("phones");
            for (int index = 0; index < phones.length(); index++) {
                int at = index;
                JSONObject entry = phones.optJSONObject(index);
                items.add(
                        entryItem(
                                entry.optString("number"),
                                typeLabel(R.array.phone_types, phoneTypeIndex(entry)),
                                () -> phoneDialog(at)));
            }
            listSection(R.string.section_phones, R.drawable.ic_section_call, R.string.add_phone, items,
                    () -> phoneDialog(-1));
        }

        if (!conflict() || changedLists.contains("emails")) {
            List<View> items = new ArrayList<>();
            JSONArray emails = array("emails");
            for (int index = 0; index < emails.length(); index++) {
                int at = index;
                JSONObject entry = emails.optJSONObject(index);
                items.add(
                        entryItem(
                                entry.optString("address"),
                                typeLabel(R.array.email_types, typeIndex(entry, HOME_WORK_OTHER)),
                                () -> emailDialog(at)));
            }
            listSection(R.string.section_emails, R.drawable.ic_section_mail, R.string.add_email, items,
                    () -> emailDialog(-1));
        }

        if (!conflict() || changedLists.contains("impps")) {
            List<View> items = new ArrayList<>();
            JSONArray impps = array("impps");
            for (int index = 0; index < impps.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                impps.optString(index),
                                null,
                                () -> stringDialog("impps", at, R.string.item_impp,
                                        R.string.hint_impp,
                                        InputType.TYPE_CLASS_TEXT
                                                | InputType.TYPE_TEXT_VARIATION_URI)));
            }
            listSection(R.string.section_impps, R.drawable.ic_section_chat, R.string.add_impp, items,
                    () -> stringDialog("impps", -1, R.string.item_impp, R.string.hint_impp,
                            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
        }

        if (!conflict() || changedLists.contains("addresses")) {
            List<View> items = new ArrayList<>();
            JSONArray entries = array("addresses");
            for (int index = 0; index < entries.length(); index++) {
                int at = index;
                JSONObject entry = entries.optJSONObject(index);
                items.add(
                        entryItem(
                                addressSummary(entry),
                                typeLabel(
                                        R.array.address_types,
                                        typeIndex(entry, HOME_WORK_OTHER)),
                                () -> addressDialog(at)));
            }
            listSection(R.string.section_addresses, R.drawable.ic_section_location_on, R.string.add_address, items,
                    () -> addressDialog(-1));
        }

        if (!conflict() || changedLists.contains("websites")) {
            List<View> items = new ArrayList<>();
            JSONArray websites = array("websites");
            for (int index = 0; index < websites.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                websites.optString(index),
                                null,
                                () -> stringDialog("websites", at, R.string.item_website,
                                        R.string.hint_url,
                                        InputType.TYPE_CLASS_TEXT
                                                | InputType.TYPE_TEXT_VARIATION_URI)));
            }
            listSection(R.string.section_websites, R.drawable.ic_section_language, R.string.add_website, items,
                    () -> stringDialog("websites", -1, R.string.item_website,
                            R.string.hint_url,
                            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
        }

        if (!conflict() || changedLists.contains("languages")) {
            List<View> items = new ArrayList<>();
            JSONArray languages = array("languages");
            for (int index = 0; index < languages.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                languages.optString(index),
                                null,
                                () -> stringDialog("languages", at, R.string.item_language,
                                        R.string.hint_language, InputType.TYPE_CLASS_TEXT)));
            }
            listSection(R.string.section_languages, R.drawable.ic_section_translate, R.string.add_language, items,
                    () -> stringDialog("languages", -1, R.string.item_language,
                            R.string.hint_language, InputType.TYPE_CLASS_TEXT));
        }

        if (!conflict() || changedLists.contains("notes")) {
            List<View> items = new ArrayList<>();
            JSONArray notes = array("notes");
            for (int index = 0; index < notes.length(); index++) {
                int at = index;
                items.add(entryItem(notes.optString(index), null, () -> noteDialog(at)));
            }
            listSection(R.string.section_notes, R.drawable.ic_section_notes, R.string.add_note, items,
                    () -> noteDialog(-1));
        }

    }

    /**
     * Adds a section header (its icon in front of the accent label)
     * and its items, separated from the previous section by a line in
     * the app bar tone; an empty section vanishes.
     */
    private void section(int title, int icon, List<View> items) {
        if (items.isEmpty()) {
            return;
        }

        if (container.getChildCount() > 0) {
            View line = new View(activity);
            line.setBackgroundColor(primaryColor);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
            params.topMargin = dp(12);
            container.addView(line, params);
        }

        ImageView iconView = new ImageView(activity);
        iconView.setImageResource(icon);
        iconView.setImageTintList(ColorStateList.valueOf(accentColor));

        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextColor(accentColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMarginStart(dp(8));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), container.getChildCount() <= 1 ? dp(16) : dp(12), dp(16), dp(4));
        header.addView(iconView, new LinearLayout.LayoutParams(dp(18), dp(18)));
        header.addView(label, labelParams);
        container.addView(header);

        for (View item : items) {
            container.addView(item);
        }
    }

    /**
     * A multi-value section: one row per entry, closed by an accent
     * "Add ..." row. An empty list still shows its header and add row,
     * since that row is the way in.
     */
    private void listSection(int title, int icon, int addLabel, List<View> items, Runnable onAdd) {
        List<View> rows = new ArrayList<>(items);
        rows.add(addItem(addLabel, onAdd));
        section(title, icon, rows);
    }

    /** The add action closing a repeatable section. */
    private View addItem(int label, Runnable onClick) {
        int textColor = resolveColor(android.R.attr.textColorPrimary);

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_add);
        icon.setImageTintList(ColorStateList.valueOf(textColor));

        TextView titleView = new TextView(activity);
        titleView.setText(label);
        titleView.setTextColor(textColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMarginStart(dp(8));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        row.setOnClickListener(view -> onClick.run());
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        row.addView(titleView, titleParams);
        return row;
    }

    /** A tappable row: title over a diminished subtitle. */
    private View entryItem(CharSequence title, CharSequence subtitle, Runnable onClick) {
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        row.setOnClickListener(view -> onClick.run());
        row.addView(titleView);

        if (subtitle != null && subtitle.length() > 0) {
            TextView subtitleView = new TextView(activity);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(labelColor);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            row.addView(subtitleView);
        }

        return row;
    }

    // ---- Item summaries -----------------------------------------------------

    /** The name parts composed (the display name has its own row). */
    private String nameSummary() {
        JSONObject name = model.optJSONObject("name");
        StringBuilder composed = new StringBuilder();
        if (name != null) {
            for (String key : new String[] {"prefix", "given", "middle", "family", "suffix"}) {
                String part = name.optString(key).trim();
                if (!part.isEmpty()) {
                    if (composed.length() > 0) {
                        composed.append(' ');
                    }
                    composed.append(part);
                }
            }
        }
        return value(composed.toString());
    }

    private String organizationSummary() {
        JSONObject organization = model.optJSONObject("organization");
        List<String> parts = new ArrayList<>();
        if (organization != null && !organization.optString("company").trim().isEmpty()) {
            parts.add(organization.optString("company").trim());
        }
        if (!model.optString("title").trim().isEmpty()) {
            parts.add(model.optString("title").trim());
        }
        return parts.isEmpty()
                ? getS(R.string.value_not_set)
                : String.join(" · ", parts);
    }

    private String addressSummary(JSONObject entry) {
        for (String key : new String[] {"street", "city", "postcode", "country"}) {
            String part = entry.optString(key).trim();
            if (!part.isEmpty()) {
                // The street's first line stands for the whole block.
                int newline = part.indexOf('\n');
                return newline < 0 ? part : part.substring(0, newline);
            }
        }
        return getS(R.string.value_not_set);
    }

    /** The empty-value placeholder. */
    private String value(String text) {
        return text.trim().isEmpty() ? getS(R.string.value_not_set) : text.trim();
    }

    private String genderSummary() {
        JSONObject gender = model.optJSONObject("gender");
        if (gender == null) {
            return getS(R.string.value_not_set);
        }

        List<String> parts = new ArrayList<>();
        int sex = genderSexIndex(gender.optString("sex"));
        if (sex > 0) {
            parts.add(typeLabel(R.array.gender_types, sex));
        }
        if (!gender.optString("identity").trim().isEmpty()) {
            parts.add(gender.optString("identity").trim());
        }
        return parts.isEmpty()
                ? getS(R.string.value_not_set)
                : String.join(" · ", parts);
    }

    private static int genderSexIndex(String sex) {
        for (int index = 1; index < GENDER_SEXES.length; index++) {
            if (GENDER_SEXES[index].equalsIgnoreCase(sex.trim())) {
                return index;
            }
        }
        return 0;
    }

    private String typeLabel(int arrayId, int index) {
        String[] labels = activity.getResources().getStringArray(arrayId);
        return labels[Math.min(index, labels.length - 1)];
    }

    // ---- Dialogs ------------------------------------------------------------

    /** The display name has its own row: one field, view-composed
     * otherwise (the bridge never mints an FN). */
    private void displayNameDialog() {
        LinearLayout content = dialogContent();
        EditText display =
                dialogField(content, "displayName", R.string.hint_display_name,
                        model.optString("displayName"), TEXT_NAME);

        showDialog(
                getS(R.string.hint_display_name),
                content,
                () -> {
                    try {
                        model.put("displayName", text(display));
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                null);
    }

    private void nameDialog() {
        JSONObject name = model.optJSONObject("name");
        JSONObject safe = name == null ? new JSONObject() : name;

        LinearLayout content = dialogContent();
        EditText prefix =
                dialogField(content, "name.prefix", R.string.hint_prefix,
                        safe.optString("prefix"), TEXT_NAME);
        EditText given =
                dialogField(content, "name.given", R.string.hint_given,
                        safe.optString("given"), TEXT_NAME);
        EditText middle =
                dialogField(content, "name.middle", R.string.hint_middle,
                        safe.optString("middle"), TEXT_NAME);
        EditText family =
                dialogField(content, "name.family", R.string.hint_family,
                        safe.optString("family"), TEXT_NAME);
        EditText suffix =
                dialogField(content, "name.suffix", R.string.hint_suffix,
                        safe.optString("suffix"), TEXT_NAME);

        showDialog(
                getS(R.string.item_name),
                content,
                () -> {
                    try {
                        model.put(
                                "name",
                                new JSONObject()
                                        .put("prefix", text(prefix))
                                        .put("given", text(given))
                                        .put("middle", text(middle))
                                        .put("family", text(family))
                                        .put("suffix", text(suffix)));
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                null);
    }

    private void organizationDialog() {
        JSONObject organization = model.optJSONObject("organization");
        JSONObject safe = organization == null ? new JSONObject() : organization;

        LinearLayout content = dialogContent();
        EditText company =
                dialogField(content, "organization.company", R.string.hint_company,
                        safe.optString("company"), TEXT_NAME);
        EditText department =
                dialogField(content, "organization.department", R.string.hint_department,
                        safe.optString("department"), TEXT_NAME);
        EditText jobTitle =
                dialogField(content, "title", R.string.hint_job_title,
                        model.optString("title"), TEXT_NAME);
        EditText role =
                dialogField(content, "role", R.string.hint_role,
                        model.optString("role"), TEXT_NAME);

        showDialog(
                getS(R.string.item_organization),
                content,
                () -> {
                    try {
                        model.put(
                                "organization",
                                new JSONObject()
                                        .put("company", text(company))
                                        .put("department", text(department)));
                        model.put("title", text(jobTitle));
                        model.put("role", text(role));
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                null);
    }

    private void phoneDialog(int index) {
        JSONArray phones = array("phones");
        JSONObject entry = index >= 0 ? phones.optJSONObject(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.phone_types);
        type.setSelection(entry == null ? 0 : phoneTypeIndex(entry));
        EditText number =
                typedValueLine(content, type, R.string.hint_number,
                        entry == null ? "" : entry.optString("number"),
                        InputType.TYPE_CLASS_PHONE);

        showDialog(
                getS(R.string.item_phone),
                content,
                () ->
                        putEntry(
                                phones,
                                index,
                                text(number),
                                entryOf(
                                        "number",
                                        text(number),
                                        PHONE_TYPES[type.getSelectedItemPosition()],
                                        entry)),
                index >= 0 ? () -> phones.remove(index) : null);
    }

    private void emailDialog(int index) {
        JSONArray emails = array("emails");
        JSONObject entry = index >= 0 ? emails.optJSONObject(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.email_types);
        type.setSelection(entry == null ? 0 : typeIndex(entry, HOME_WORK_OTHER));
        EditText address =
                typedValueLine(content, type, R.string.hint_email_address,
                        entry == null ? "" : entry.optString("address"),
                        InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        showDialog(
                getS(R.string.item_email),
                content,
                () ->
                        putEntry(
                                emails,
                                index,
                                text(address),
                                entryOf(
                                        "address",
                                        text(address),
                                        HOME_WORK_OTHER[type.getSelectedItemPosition()],
                                        entry)),
                index >= 0 ? () -> emails.remove(index) : null);
    }

    private void addressDialog(int index) {
        JSONArray addresses = array("addresses");
        JSONObject entry = index >= 0 ? addresses.optJSONObject(index) : null;
        JSONObject safe = entry == null ? new JSONObject() : entry;

        LinearLayout content = dialogContent();
        RadioGroup type =
                typeRadios(
                        content,
                        R.array.address_types,
                        entry == null ? 0 : typeIndex(entry, HOME_WORK_OTHER));
        EditText street =
                dialogField(content, null, R.string.hint_street, safe.optString("street"),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText city =
                dialogField(content, null, R.string.hint_city, safe.optString("city"), TEXT_NAME);
        EditText region =
                dialogField(content, null, R.string.hint_region, safe.optString("region"),
                        TEXT_NAME);
        EditText postcode =
                dialogField(content, null, R.string.hint_postcode, safe.optString("postcode"),
                        InputType.TYPE_CLASS_TEXT);
        EditText country =
                dialogField(content, null, R.string.hint_country, safe.optString("country"),
                        TEXT_NAME);

        showDialog(
                getS(R.string.item_address),
                content,
                () -> {
                    String filled =
                            (text(street) + text(city) + text(region) + text(postcode)
                                    + text(country)).trim();
                    try {
                        JSONObject fresh =
                                new JSONObject()
                                        .put("street", text(street))
                                        .put("city", text(city))
                                        .put("region", text(region))
                                        .put("postcode", text(postcode))
                                        .put("country", text(country))
                                        // Fields the dialog does not show
                                        // ride along untouched.
                                        .put("pobox", safe.optString("pobox"))
                                        .put("ext", safe.optString("ext"))
                                        .put("pref", safe.optBoolean("pref"))
                                        .put(
                                                "types",
                                                array(
                                                        HOME_WORK_OTHER[
                                                                type.getCheckedRadioButtonId()
                                                                        - 1]));
                        putEntry(addresses, index, filled, fresh);
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                index >= 0 ? () -> addresses.remove(index) : null);
    }

    /** A one-field dialog for the plain string lists (hint 0 = none). */
    private void stringDialog(String list, int index, int title, int hint, int inputType) {
        JSONArray values = array(list);

        LinearLayout content = dialogContent();
        EditText input =
                dialogField(content, null, hint,
                        index >= 0 ? values.optString(index) : "", inputType);

        showDialog(
                getS(title),
                content,
                () -> {
                    String fresh = text(input);
                    if (fresh.isEmpty()) {
                        if (index >= 0) {
                            values.remove(index);
                        }
                    } else if (index >= 0) {
                        try {
                            values.put(index, fresh);
                        } catch (JSONException error) {
                            throw new IllegalStateException(error);
                        }
                    } else {
                        values.put(fresh);
                    }
                },
                index >= 0 ? () -> values.remove(index) : null);
    }

    private void noteDialog(int index) {
        JSONArray notes = array("notes");

        LinearLayout content = dialogContent();
        EditText input =
                dialogField(content, null, 0,
                        index >= 0 ? notes.optString(index) : "",
                        InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        showDialog(
                getS(R.string.item_note),
                content,
                () -> {
                    String fresh = text(input);
                    if (fresh.isEmpty()) {
                        if (index >= 0) {
                            notes.remove(index);
                        }
                    } else if (index >= 0) {
                        try {
                            notes.put(index, fresh);
                        } catch (JSONException error) {
                            throw new IllegalStateException(error);
                        }
                    } else {
                        notes.put(fresh);
                    }
                },
                index >= 0 ? () -> notes.remove(index) : null);
    }

    private void relationDialog(int index) {
        JSONArray relations = array("relations");
        JSONObject entry = index >= 0 ? relations.optJSONObject(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.relation_types);
        type.setSelection(entry == null ? 0 : typeIndex(entry, RELATION_TYPES));
        EditText value =
                typedValueLine(content, type, R.string.hint_relation,
                        entry == null ? "" : entry.optString("value"), TEXT_NAME);

        showDialog(
                getS(R.string.item_relation),
                content,
                () ->
                        putEntry(
                                relations,
                                index,
                                text(value),
                                entryOf(
                                        "value",
                                        text(value),
                                        RELATION_TYPES[type.getSelectedItemPosition()],
                                        entry)),
                index >= 0 ? () -> relations.remove(index) : null);
    }

    /** The GENDER dialog: the sex code as a spinner, the identity free. */
    private void genderDialog() {
        JSONObject gender = model.optJSONObject("gender");
        JSONObject safe = gender == null ? new JSONObject() : gender;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.gender_types);
        type.setSelection(genderSexIndex(safe.optString("sex")));
        EditText identity =
                typedValueLine(content, type, R.string.hint_gender_identity,
                        safe.optString("identity"), TEXT_NAME);

        showDialog(
                getS(R.string.item_gender),
                content,
                () -> {
                    String sex = GENDER_SEXES[type.getSelectedItemPosition()];
                    String fresh = text(identity);
                    try {
                        if (sex.isEmpty() && fresh.isEmpty()) {
                            model.remove("gender");
                        } else {
                            model.put(
                                    "gender",
                                    new JSONObject().put("sex", sex).put("identity", fresh));
                        }
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                null);
    }

    /** A date field goes straight to the system date picker. */
    private void pickDate(String field) {
        Calendar calendar = Calendar.getInstance();
        String[] parts = model.optString(field).split("-");
        if (parts.length == 3) {
            try {
                calendar.set(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]) - 1,
                        Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
                // Keep today when the stored value is not a date.
            }
        }

        DatePickerDialog dialog =
                new DatePickerDialog(
                        activity,
                        (view, year, month, day) -> {
                            try {
                                model.put(
                                        field,
                                        String.format(
                                                Locale.ROOT,
                                                "%04d-%02d-%02d",
                                                year,
                                                month + 1,
                                                day));
                            } catch (JSONException error) {
                                throw new IllegalStateException(error);
                            }
                            render();
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH));
        dialog.setButton(
                DatePickerDialog.BUTTON_NEUTRAL,
                activity.getString(R.string.clear),
                (d, which) -> {
                    model.remove(field);
                    render();
                });
        dialog.show();
    }

    // ---- Dialog building ----------------------------------------------------

    /** The vertical field container of an edit dialog. */
    private LinearLayout dialogContent() {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), 0);
        return content;
    }

    /**
     * Shows an edit dialog: OK runs the write-back and re-renders the
     * page, Remove (when given) drops the entry.
     */
    private void showDialog(
            CharSequence title, LinearLayout content, Runnable onOk, Runnable onRemove) {
        ScrollView wrap = new ScrollView(activity);
        wrap.addView(content);

        AlertDialog.Builder builder =
                new AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setView(wrap)
                        .setPositiveButton(
                                android.R.string.ok,
                                (dialog, which) -> {
                                    onOk.run();
                                    render();
                                })
                        .setNegativeButton(android.R.string.cancel, null);
        if (onRemove != null) {
            builder.setNeutralButton(
                    R.string.remove_row,
                    (dialog, which) -> {
                        onRemove.run();
                        render();
                    });
        }

        AlertDialog dialog = builder.create();

        // Focus the first field so the keyboard opens with the dialog.
        EditText first = firstInput(content);
        if (first != null) {
            first.requestFocus();
            first.setSelection(first.getText().length());
            dialog.getWindow()
                    .setSoftInputMode(
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }

        dialog.show();
    }

    /** The first EditText under the view, depth first; null when none. */
    private static EditText firstInput(View view) {
        if (view instanceof EditText) {
            return (EditText) view;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText input = firstInput(group.getChildAt(index));
                if (input != null) {
                    return input;
                }
            }
        }

        return null;
    }

    /**
     * A dialog field, label-free: the field name rides as the
     * placeholder hint. A conflicted path renders one tappable chip
     * per candidate value below it.
     */
    private EditText dialogField(
            LinearLayout content, String path, int label, String prefill, int inputType) {
        EditText input = new EditText(activity);
        input.setBackgroundTintList(ColorStateList.valueOf(accentColor));
        input.setInputType(inputType);
        if (label != 0) {
            input.setHint(label);
        }
        input.setText(prefill);
        content.addView(input);

        JSONArray values = path == null || alternatives == null
                ? null
                : alternatives.optJSONArray(path);
        if (values != null && values.length() > 0) {
            LinearLayout chips = new LinearLayout(activity);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            for (int index = 0; index < values.length(); index++) {
                String candidate = values.optString(index);
                Button chip = new Button(activity, null, android.R.attr.buttonStyleSmall);
                // The empty alternative (one card lacks the field) is a
                // pickable choice too: it reads "Not set" and clears.
                chip.setText(candidate.isEmpty() ? getS(R.string.value_not_set) : candidate);
                chip.setAllCaps(false);
                chip.setOnClickListener(view -> input.setText(candidate));
                chips.addView(chip);
            }

            HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
            chipScroll.setHorizontalScrollBarEnabled(false);
            chipScroll.addView(chips);
            content.addView(chipScroll);
        }

        return input;
    }

    /** The type spinner of an entry dialog (self-describing, no label). */
    private Spinner typeSpinner(int entries) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<CharSequence> adapter =
                ArrayAdapter.createFromResource(activity, entries, R.layout.spinner_form_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // Flush with the fields: the stock spinner carries its own
        // padding that breaks the shared text start.
        spinner.setPadding(0, 0, 0, 0);
        return spinner;
    }

    /**
     * A typed value line: the type spinner sized to its content, then
     * the value field filling the rest of the line.
     */
    private EditText typedValueLine(
            LinearLayout content, Spinner type, int hint, String prefill, int inputType) {
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setBaselineAligned(false);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(
                type,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = dialogField(line, null, hint, prefill, inputType);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginStart(dp(12));
        input.setLayoutParams(params);

        content.addView(line);
        return input;
    }

    /** The entry types as one line of radios (position = table index). */
    private RadioGroup typeRadios(LinearLayout content, int entries, int selected) {
        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(LinearLayout.HORIZONTAL);

        String[] labels = activity.getResources().getStringArray(entries);
        for (int index = 0; index < labels.length; index++) {
            RadioButton option = new RadioButton(activity);
            option.setText(labels[index]);
            option.setId(index + 1);
            // Flush start, and the same gap between options as between
            // the fields below.
            option.setPadding(0, 0, dp(16), 0);
            group.addView(option);
        }
        group.check(selected + 1);

        content.addView(group);
        return group;
    }

    // ---- Model helpers ------------------------------------------------------

    /** The model's list under the key, created empty when absent. */
    private JSONArray array(String key) {
        JSONArray values = model.optJSONArray(key);
        if (values == null) {
            values = new JSONArray();
            try {
                model.put(key, values);
            } catch (JSONException error) {
                throw new IllegalStateException(error);
            }
        }
        return values;
    }

    /** A typed value entry, the pref flag riding along from the old one. */
    private static JSONObject entryOf(
            String field, String value, String[] types, JSONObject previous) {
        try {
            return new JSONObject()
                    .put(field, value)
                    .put("types", array(types))
                    .put("pref", previous != null && previous.optBoolean("pref"));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * Writes an entry back into its list: replaced in place, appended
     * when new, dropped when emptied.
     */
    private static void putEntry(JSONArray values, int index, String filled, JSONObject entry) {
        if (filled.trim().isEmpty()) {
            if (index >= 0) {
                values.remove(index);
            }
            return;
        }

        if (index >= 0) {
            try {
                values.put(index, entry);
            } catch (JSONException error) {
                throw new IllegalStateException(error);
            }
        } else {
            values.put(entry);
        }
    }

    private boolean conflicted(String... paths) {
        if (alternatives == null) {
            return false;
        }
        for (String path : paths) {
            if (alternatives.has(path)) {
                return true;
            }
        }
        return false;
    }

    // ---- Type mapping -------------------------------------------------------

    private static int phoneTypeIndex(JSONObject entry) {
        Set<String> types = typeSet(entry);
        if (types.contains("fax")) {
            return types.contains("home") ? 4 : 5;
        }
        if (types.contains("cell")) {
            return types.contains("work") ? 3 : 0;
        }
        if (types.contains("pager")) {
            return 6;
        }
        if (types.contains("work")) {
            return 2;
        }
        if (types.contains("home")) {
            return 1;
        }
        return 7;
    }

    private static int typeIndex(JSONObject entry, String[][] table) {
        Set<String> types = typeSet(entry);
        for (int index = 0; index < table.length; index++) {
            if (table[index].length > 0 && types.contains(table[index][0])) {
                return index;
            }
        }
        return table.length - 1;
    }

    private static Set<String> typeSet(JSONObject entry) {
        Set<String> types = new HashSet<>();
        JSONArray array = entry.optJSONArray("types");
        for (int index = 0; array != null && index < array.length(); index++) {
            types.add(array.optString(index));
        }
        return types;
    }

    private static JSONArray array(String[] values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    // ---- Utils --------------------------------------------------------------

    private static String text(EditText input) {
        return input.getText().toString().trim();
    }

    private String getS(int id) {
        return activity.getString(id);
    }

    private int resolveAttr(int attr) {
        TypedValue value = new TypedValue();
        activity.getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    private int resolveColor(int attr) {
        TypedValue value = new TypedValue();
        activity.getTheme().resolveAttribute(attr, value, true);
        if (value.resourceId != 0) {
            return activity.getResources().getColor(value.resourceId, activity.getTheme());
        }
        return value.data;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
