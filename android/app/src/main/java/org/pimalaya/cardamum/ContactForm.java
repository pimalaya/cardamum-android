package org.pimalaya.cardamum;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TabHost;
import android.widget.TextView;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The contact edit form: presents the neutral field model as grouped,
 * labelled fields under scrollable tabs (name, contact, address, work,
 * more, plus a read-only source view of the raw vCard), built entirely
 * from framework widgets. Every input carries a grey label above it and
 * a brand-tinted underline rather than a placeholder, so a filled form
 * stays readable. Model fields the form does not display (address
 * pobox and ext, pref flags) ride along through per-row tags, so a
 * load/collect round trip is lossless at the model level; everything
 * beyond the model is preserved by the vCard patch in the Rust bridge.
 */
final class ContactForm {
    /** Spinner position to vCard TYPE set, aligned with R.array.phone_types. */
    private static final String[][] PHONE_TYPES = {
        {"cell"}, {"home"}, {"work"}, {"cell", "work"},
        {"fax", "home"}, {"fax", "work"}, {"pager"}, {},
    };

    /** Spinner position to vCard TYPE set, aligned with R.array.email_types. */
    private static final String[][] HOME_WORK_OTHER = {{"home"}, {"work"}, {}};

    /** Fields an address row carries but does not display. */
    private static final class AddressExtras {
        String pobox = "";
        String ext = "";
        boolean pref;
    }

    private final Activity activity;
    private final int fieldColor;
    private final int labelColor;
    private final int accentColor;

    /** Left padding of a field, so labels line up with the text start. */
    private final int labelIndent;

    private final TabHost tabs;

    private EditText display;
    private EditText prefix;
    private EditText given;
    private EditText middle;
    private EditText family;
    private EditText suffix;
    private LinearLayout nicknames;
    private LinearLayout phones;
    private LinearLayout emails;
    private LinearLayout addresses;
    private EditText company;
    private EditText department;
    private EditText jobTitle;
    private EditText role;
    private EditText birthday;
    private LinearLayout websites;
    private LinearLayout notes;
    private final TextView source;

    ContactForm(Activity activity) {
        this.activity = activity;
        this.accentColor = resolveColor(android.R.attr.colorAccent);
        this.fieldColor = accentColor;
        this.labelColor = resolveColor(android.R.attr.textColorSecondary);
        this.labelIndent = new EditText(activity).getPaddingStart();

        LinearLayout nameFields = activity.findViewById(R.id.contact_name_fields);
        LinearLayout contactFields = activity.findViewById(R.id.contact_contact_fields);
        LinearLayout addressFields = activity.findViewById(R.id.contact_address_fields);
        LinearLayout workFields = activity.findViewById(R.id.contact_work_fields);
        LinearLayout moreFields = activity.findViewById(R.id.contact_notes_fields);
        source = activity.findViewById(R.id.contact_source);

        tabs = activity.findViewById(R.id.contact_tabs);
        tabs.setup();
        addTab("name", R.string.tab_name, R.id.contact_tab_name);
        addTab("contact", R.string.tab_contact, R.id.contact_tab_contact);
        addTab("address", R.string.tab_address, R.id.contact_tab_address);
        addTab("work", R.string.tab_work, R.id.contact_tab_work);
        addTab("more", R.string.tab_more, R.id.contact_tab_notes);
        addTab("source", R.string.tab_source, R.id.contact_tab_source);
        setUpScrollHint();

        buildFields(nameFields, contactFields, addressFields, workFields, moreFields);
    }

    /**
     * The chevron hints that the tab strip scrolls. It starts visible
     * (so users know before touching anything) and hides only once the
     * strip is scrolled to its right end.
     */
    private void setUpScrollHint() {
        HorizontalScrollView scroll = activity.findViewById(R.id.contact_tab_scroll);
        ImageView more = activity.findViewById(R.id.contact_tab_more);
        ImageView less = activity.findViewById(R.id.contact_tab_less);

        Runnable update =
                () -> {
                    more.setVisibility(scroll.canScrollHorizontally(1) ? View.VISIBLE : View.GONE);
                    less.setVisibility(scroll.canScrollHorizontally(-1) ? View.VISIBLE : View.GONE);
                };
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> update.run());
        // Re-evaluate once the tab strip has actually measured, otherwise
        // canScrollHorizontally reports false and the hint never shows.
        scroll.getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                update.run();
                            }
                        });
    }

    private void addTab(String tag, int label, int content) {
        tabs.addTab(
                tabs.newTabSpec(tag).setIndicator(activity.getString(label)).setContent(content));
    }

    /** Builds the labelled inputs and repeating sections once. */
    private void buildFields(
            LinearLayout nameFields,
            LinearLayout contactFields,
            LinearLayout addressFields,
            LinearLayout workFields,
            LinearLayout moreFields) {
        display = labelled(nameFields, R.string.hint_display_name, TEXT_NAME);
        prefix = labelled(nameFields, R.string.hint_prefix, TEXT_NAME);
        given = labelled(nameFields, R.string.hint_given, TEXT_NAME);
        middle = labelled(nameFields, R.string.hint_middle, TEXT_NAME);
        family = labelled(nameFields, R.string.hint_family, TEXT_NAME);
        suffix = labelled(nameFields, R.string.hint_suffix, TEXT_NAME);
        nicknames = section(nameFields, R.string.section_nicknames, R.string.add_nickname,
                () -> nicknames.addView(lineRow(nicknames, "", TEXT_NAME)));

        phones = section(contactFields, R.string.section_phones, R.string.add_phone,
                () -> phones.addView(phoneRow(null)));
        emails = section(contactFields, R.string.section_emails, R.string.add_email,
                () -> emails.addView(emailRow(null)));

        addresses = section(addressFields, R.string.section_addresses, R.string.add_address,
                () -> addresses.addView(addressBlock(null)));

        company = labelled(workFields, R.string.hint_company, TEXT_WORDS);
        department = labelled(workFields, R.string.hint_department, TEXT_WORDS);
        jobTitle = labelled(workFields, R.string.hint_job_title, TEXT_WORDS);
        role = labelled(workFields, R.string.hint_role, TEXT_WORDS);

        birthday = dateField(moreFields, R.string.hint_birthday);
        websites = section(moreFields, R.string.section_websites, R.string.add_website,
                () -> websites.addView(lineRow(websites, "",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI)));
        notes = section(moreFields, R.string.section_notes, R.string.add_note,
                () -> notes.addView(noteRow("")));
    }

    /** Fills the form from the field model (null for a new contact). */
    void load(JSONObject model, String vcard) {
        JSONObject safe = model == null ? new JSONObject() : model;
        JSONObject name = safe.optJSONObject("name");

        display.setText(safe.optString("displayName"));
        prefix.setText(name == null ? "" : name.optString("prefix"));
        given.setText(name == null ? "" : name.optString("given"));
        middle.setText(name == null ? "" : name.optString("middle"));
        family.setText(name == null ? "" : name.optString("family"));
        suffix.setText(name == null ? "" : name.optString("suffix"));

        // Every multi-value section keeps one trailing empty row, so
        // there is always a blank to fill without tapping the plus.
        fill(nicknames, safe.optJSONArray("nicknames"),
                value -> nicknames.addView(lineRow(nicknames, value, TEXT_NAME)));
        nicknames.addView(lineRow(nicknames, "", TEXT_NAME));

        fillObjects(phones, safe.optJSONArray("phones"),
                entry -> phones.addView(phoneRow(entry)));
        phones.addView(phoneRow(null));

        fillObjects(emails, safe.optJSONArray("emails"),
                entry -> emails.addView(emailRow(entry)));
        emails.addView(emailRow(null));

        fillObjects(addresses, safe.optJSONArray("addresses"),
                entry -> addresses.addView(addressBlock(entry)));
        addresses.addView(addressBlock(null));

        JSONObject organization = safe.optJSONObject("organization");
        company.setText(organization == null ? "" : organization.optString("company"));
        department.setText(organization == null ? "" : organization.optString("department"));
        jobTitle.setText(safe.optString("title"));
        role.setText(safe.optString("role"));
        birthday.setText(safe.optString("birthday"));

        fill(websites, safe.optJSONArray("websites"),
                value -> websites.addView(lineRow(websites, value,
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI)));
        websites.addView(lineRow(websites, "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));

        fill(notes, safe.optJSONArray("notes"), value -> notes.addView(noteRow(value)));
        notes.addView(noteRow(""));

        source.setText(vcard);
        tabs.setCurrentTab(0);
    }

    /** Collects the form back into the field model. */
    JSONObject collect() {
        try {
            JSONObject model = new JSONObject();
            model.put("displayName", text(display));
            model.put(
                    "name",
                    new JSONObject()
                            .put("prefix", text(prefix))
                            .put("given", text(given))
                            .put("middle", text(middle))
                            .put("family", text(family))
                            .put("suffix", text(suffix)));

            JSONArray nicks = new JSONArray();
            for (int index = 0; index < nicknames.getChildCount(); index++) {
                String value = rowValue(nicknames.getChildAt(index));
                if (!value.isEmpty()) {
                    nicks.put(value);
                }
            }
            model.put("nicknames", nicks);

            JSONArray phoneEntries = new JSONArray();
            for (int index = 0; index < phones.getChildCount(); index++) {
                LinearLayout row = (LinearLayout) phones.getChildAt(index);
                String number = text((EditText) row.getChildAt(0));
                if (number.isEmpty()) {
                    continue;
                }
                int type = ((Spinner) row.getChildAt(1)).getSelectedItemPosition();
                phoneEntries.put(
                        new JSONObject()
                                .put("number", number)
                                .put("types", array(PHONE_TYPES[type]))
                                .put("pref", Boolean.TRUE.equals(row.getTag())));
            }
            model.put("phones", phoneEntries);

            JSONArray emailEntries = new JSONArray();
            for (int index = 0; index < emails.getChildCount(); index++) {
                LinearLayout row = (LinearLayout) emails.getChildAt(index);
                String address = text((EditText) row.getChildAt(0));
                if (address.isEmpty()) {
                    continue;
                }
                int type = ((Spinner) row.getChildAt(1)).getSelectedItemPosition();
                emailEntries.put(
                        new JSONObject()
                                .put("address", address)
                                .put("types", array(HOME_WORK_OTHER[type]))
                                .put("pref", Boolean.TRUE.equals(row.getTag())));
            }
            model.put("emails", emailEntries);

            JSONArray addressEntries = new JSONArray();
            for (int index = 0; index < addresses.getChildCount(); index++) {
                LinearLayout block = (LinearLayout) addresses.getChildAt(index);
                AddressExtras extras = (AddressExtras) block.getTag();
                String street = fieldText(block, 0);
                String city = fieldText(block, 1);
                String region = fieldText(block, 2);
                String postcode = fieldText(block, 3);
                String country = fieldText(block, 4);
                int type =
                        ((Spinner) ((LinearLayout) block.getChildAt(5)).getChildAt(0))
                                .getSelectedItemPosition();

                if (street.isEmpty()
                        && city.isEmpty()
                        && region.isEmpty()
                        && postcode.isEmpty()
                        && country.isEmpty()
                        && extras.pobox.isEmpty()
                        && extras.ext.isEmpty()) {
                    continue;
                }

                addressEntries.put(
                        new JSONObject()
                                .put("street", street)
                                .put("city", city)
                                .put("region", region)
                                .put("postcode", postcode)
                                .put("country", country)
                                .put("pobox", extras.pobox)
                                .put("ext", extras.ext)
                                .put("types", array(HOME_WORK_OTHER[type]))
                                .put("pref", extras.pref));
            }
            model.put("addresses", addressEntries);

            model.put(
                    "organization",
                    new JSONObject()
                            .put("company", text(company))
                            .put("department", text(department)));
            model.put("title", text(jobTitle));
            model.put("role", text(role));
            model.put("birthday", text(birthday));

            JSONArray sites = new JSONArray();
            for (int index = 0; index < websites.getChildCount(); index++) {
                String value = rowValue(websites.getChildAt(index));
                if (!value.isEmpty()) {
                    sites.put(value);
                }
            }
            model.put("websites", sites);

            JSONArray noteEntries = new JSONArray();
            for (int index = 0; index < notes.getChildCount(); index++) {
                String value = rowValue(notes.getChildAt(index));
                if (!value.isEmpty()) {
                    noteEntries.put(value);
                }
            }
            model.put("notes", noteEntries);

            return model;
        } catch (JSONException error) {
            throw new IllegalStateException("Could not collect the contact form", error);
        }
    }

    /** The birthday as typed, empty when blank. */
    String birthday() {
        return text(birthday);
    }

    // ---- Field builders -------------------------------------------------------

    private static final int TEXT_NAME =
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;
    private static final int TEXT_WORDS =
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;

    /** A labelled single-line field appended to the container. */
    private EditText labelled(LinearLayout container, int label, int inputType) {
        addLabel(container, label);
        EditText input = field();
        input.setInputType(inputType);
        container.addView(input);
        return input;
    }

    /** A labelled read-only field that opens a date picker on tap. */
    private EditText dateField(LinearLayout container, int label) {
        addLabel(container, label);
        EditText input = field();
        input.setFocusable(false);
        input.setClickable(true);
        input.setInputType(InputType.TYPE_NULL);
        input.setKeyListener(null);
        input.setOnClickListener(view -> pickDate(input));
        container.addView(input);
        return input;
    }

    /** A bare field with its underline tinted the brand (app-bar) colour. */
    private EditText field() {
        EditText input = new EditText(activity);
        input.setBackgroundTintList(ColorStateList.valueOf(fieldColor));
        return input;
    }

    private void pickDate(EditText field) {
        Calendar calendar = Calendar.getInstance();
        String[] parts = field.getText().toString().split("-");
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
                        (view, year, month, day) ->
                                field.setText(
                                        String.format("%04d-%02d-%02d", year, month + 1, day)),
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH));
        dialog.setButton(
                DatePickerDialog.BUTTON_NEUTRAL,
                activity.getString(R.string.clear),
                (d, which) -> field.setText(""));
        dialog.show();
    }

    /** Vertical space above each field group (label + input, or label + list). */
    private static final int GROUP_TOP = 20;

    /**
     * A titled section: a header row with the small label and, right
     * next to it (inline, not pushed to the edge), a plus button; then a
     * vertical container for its repeating rows. Returns the container.
     */
    private LinearLayout section(LinearLayout parent, int title, int addLabel, Runnable onAdd) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        // Same top spacing as a single field's label, so groups are even.
        header.setPadding(0, dp(GROUP_TOP), 0, 0);

        TextView label = smallLabel(title);
        label.setPadding(labelIndent, 0, 0, 0);
        header.addView(label);

        header.addView(iconButton(R.drawable.ic_add, addLabel, accentColor, view -> onAdd.run()));
        parent.addView(header);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        parent.addView(rows);

        return rows;
    }

    /** A small, secondary-coloured label sitting just above its input. */
    private void addLabel(LinearLayout container, int label) {
        TextView view = smallLabel(label);
        // Indent to the field's text start; top spacing separates groups.
        view.setPadding(labelIndent, dp(GROUP_TOP), 0, 0);
        container.addView(view);
    }

    private TextView smallLabel(int text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(labelColor);
        view.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small);
        return view;
    }

    /**
     * A small borderless round icon button (add, remove) with no
     * background, tinted `tint`, sized so its glyph lines up with the
     * adjacent field or label.
     */
    private ImageButton iconButton(int icon, int description, int tint, View.OnClickListener c) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(icon);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        button.setBackgroundResource(
                resolveAttr(android.R.attr.selectableItemBackgroundBorderless));
        button.setContentDescription(activity.getString(description));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(3), dp(3), dp(3), dp(3));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        button.setOnClickListener(c);
        return button;
    }

    private int resolveAttr(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    private int resolveColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(attr, value, true);
        if (value.resourceId != 0) {
            return activity.getResources().getColor(value.resourceId, activity.getTheme());
        }
        return value.data;
    }

    // ---- Row builders ---------------------------------------------------------

    private LinearLayout phoneRow(JSONObject entry) {
        LinearLayout row = valueTypeRow(R.array.phone_types, InputType.TYPE_CLASS_PHONE);
        if (entry != null) {
            ((EditText) row.getChildAt(0)).setText(entry.optString("number"));
            ((Spinner) row.getChildAt(1)).setSelection(phoneTypeIndex(entry));
            row.setTag(entry.optBoolean("pref"));
        }
        return row;
    }

    private LinearLayout emailRow(JSONObject entry) {
        LinearLayout row =
                valueTypeRow(
                        R.array.email_types,
                        InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if (entry != null) {
            ((EditText) row.getChildAt(0)).setText(entry.optString("address"));
            ((Spinner) row.getChildAt(1)).setSelection(typeIndex(entry, HOME_WORK_OTHER));
            row.setTag(entry.optBoolean("pref"));
        }
        return row;
    }

    /** A removable [value, type spinner, remove] row. */
    private LinearLayout valueTypeRow(int typesArray, int inputType) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText value = field();
        value.setLayoutParams(
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        value.setInputType(inputType);
        row.addView(value);

        row.addView(spinner(typesArray));
        row.addView(removeButton(row));
        return row;
    }

    private LinearLayout addressBlock(JSONObject entry) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(8), 0, dp(16));

        block.addView(blockField(R.string.hint_street, InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        block.addView(blockField(R.string.hint_city, InputType.TYPE_TEXT_FLAG_CAP_WORDS));
        block.addView(blockField(R.string.hint_region, InputType.TYPE_TEXT_FLAG_CAP_WORDS));
        block.addView(blockField(R.string.hint_postcode, 0));
        block.addView(blockField(R.string.hint_country, InputType.TYPE_TEXT_FLAG_CAP_WORDS));

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        Spinner type = spinner(R.array.address_types);
        type.setLayoutParams(
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(type);
        controls.addView(removeButton(block));
        block.addView(controls);

        AddressExtras extras = new AddressExtras();
        block.setTag(extras);

        if (entry != null) {
            fieldOf(block, 0).setText(entry.optString("street"));
            fieldOf(block, 1).setText(entry.optString("city"));
            fieldOf(block, 2).setText(entry.optString("region"));
            fieldOf(block, 3).setText(entry.optString("postcode"));
            fieldOf(block, 4).setText(entry.optString("country"));
            type.setSelection(typeIndex(entry, HOME_WORK_OTHER));
            extras.pobox = entry.optString("pobox");
            extras.ext = entry.optString("ext");
            extras.pref = entry.optBoolean("pref");
        }
        return block;
    }

    /** A labelled field inside an address block (label + input in one holder). */
    private LinearLayout blockField(int label, int inputTypeFlags) {
        LinearLayout holder = new LinearLayout(activity);
        holder.setOrientation(LinearLayout.VERTICAL);
        addLabel(holder, label);
        EditText input = field();
        input.setInputType(InputType.TYPE_CLASS_TEXT | inputTypeFlags);
        holder.addView(input);
        return holder;
    }

    /** The EditText inside an address block's nth labelled holder. */
    private static EditText fieldOf(LinearLayout block, int index) {
        return (EditText) ((LinearLayout) block.getChildAt(index)).getChildAt(1);
    }

    private static String fieldText(LinearLayout block, int index) {
        return fieldOf(block, index).getText().toString().trim();
    }

    /** A removable single-value row (nicknames, websites). */
    private LinearLayout lineRow(LinearLayout container, String value, int inputType) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText input = field();
        input.setLayoutParams(
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        input.setInputType(inputType);
        input.setText(value);
        row.addView(input);

        row.addView(removeButton(row));
        return row;
    }

    /** A removable multi-line row (notes). */
    private LinearLayout noteRow(String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        EditText input = field();
        input.setLayoutParams(
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(value);
        row.addView(input);

        row.addView(removeButton(row));
        return row;
    }

    /** The value of a single-input row (nickname, website, note). */
    private static String rowValue(View row) {
        return ((EditText) ((LinearLayout) row).getChildAt(0)).getText().toString().trim();
    }

    private Spinner spinner(int entries) {
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<CharSequence> adapter =
                ArrayAdapter.createFromResource(
                        activity, entries, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    /** A round, background-less remove (minus) button for the row. */
    private ImageButton removeButton(LinearLayout target) {
        return iconButton(
                R.drawable.ic_close,
                R.string.remove_row,
                labelColor,
                view -> ((ViewGroup) target.getParent()).removeView(target));
    }

    // ---- Model fill helpers ---------------------------------------------------

    private interface StringConsumer {
        void accept(String value);
    }

    private interface ObjectConsumer {
        void accept(JSONObject value);
    }

    private static void fill(LinearLayout container, JSONArray values, StringConsumer add) {
        container.removeAllViews();
        for (int index = 0; values != null && index < values.length(); index++) {
            add.accept(values.optString(index));
        }
    }

    private static void fillObjects(
            LinearLayout container, JSONArray values, ObjectConsumer add) {
        container.removeAllViews();
        for (int index = 0; values != null && index < values.length(); index++) {
            add.accept(values.optJSONObject(index));
        }
    }

    // ---- Type mapping ---------------------------------------------------------

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

    private static String text(EditText input) {
        return input.getText().toString().trim();
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
