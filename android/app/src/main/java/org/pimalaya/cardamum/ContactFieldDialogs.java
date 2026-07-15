package org.pimalaya.cardamum;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import java.util.Arrays;
import java.util.Calendar;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Cards;

/**
 * The per-property edit dialogs behind the contact form: one label-free
 * mini-form per field kind (display name, name parts, organization,
 * phone, email, address, gender, dates, the plain string lists), plus
 * the shared dialog scaffolding (the vertical content column, the OK
 * or Remove alert, the placeholder-hinted fields, the type spinner,
 * radios and the choices completer). Each dialog edits the working
 * field model in place then re-renders, reached through its
 * {@link ContactForm} host: the host owns the model, the view support
 * and the conflict alternatives, and re-gates its save on every render.
 */
final class ContactFieldDialogs {
    private final ContactForm host;

    ContactFieldDialogs(ContactForm host) {
        this.host = host;
    }

    /** The display name has its own row: one field, view-composed
     * otherwise (the bridge never mints an FN). */
    void displayNameDialog() {
        LinearLayout content = dialogContent();
        EditText display =
                dialogField(content, "displayName", R.string.hint_display_name,
                        host.model.optString("displayName"), ContactForm.TEXT_NAME);

        showDialog(
                host.getS(R.string.hint_display_name),
                content,
                () -> {
                    try {
                        host.model.put("displayName", host.text(display));
                        host.revealed.add("displayName");
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                () -> {
                    host.model.remove("displayName");
                    host.revealed.remove("displayName");
                });
    }

    /** Edits one structured-name component in place, leaving the others
     *  (kept under the model's {@code name} object) untouched. */
    void namePartDialog(String part, int hint) {
        LinearLayout content = dialogContent();
        EditText field =
                dialogField(content, "name." + part, hint, host.namePart(part),
                        ContactForm.TEXT_NAME);

        showDialog(
                host.getS(hint),
                content,
                () -> {
                    try {
                        JSONObject name = host.model.optJSONObject("name");
                        if (name == null) {
                            name = new JSONObject();
                        }
                        name.put(part, host.text(field));
                        host.model.put("name", name);
                        host.revealed.add("name." + part);
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                () -> {
                    JSONObject name = host.model.optJSONObject("name");
                    if (name != null) {
                        name.remove(part);
                    }
                    host.revealed.remove("name." + part);
                });
    }

    /** Edits one organization component in place, leaving the other
     *  (kept under the model's {@code organization} object) untouched. */
    void organizationPartDialog(String part, int hint) {
        LinearLayout content = dialogContent();
        EditText field =
                dialogField(content, "organization." + part, hint,
                        host.organizationPart(part), ContactForm.TEXT_NAME);

        showDialog(
                host.getS(hint),
                content,
                () -> {
                    try {
                        JSONObject organization = host.model.optJSONObject("organization");
                        if (organization == null) {
                            organization = new JSONObject();
                        }
                        organization.put(part, host.text(field));
                        host.model.put("organization", organization);
                        host.revealed.add("organization." + part);
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                () -> {
                    JSONObject organization = host.model.optJSONObject("organization");
                    if (organization != null) {
                        organization.remove(part);
                    }
                    host.revealed.remove("organization." + part);
                });
    }

    /** Edits a top-level scalar field (title, role) in place. */
    void simpleFieldDialog(String key, int hint) {
        LinearLayout content = dialogContent();
        EditText field =
                dialogField(content, key, hint, host.model.optString(key), ContactForm.TEXT_NAME);

        showDialog(
                host.getS(hint),
                content,
                () -> {
                    try {
                        host.model.put(key, host.text(field));
                        host.revealed.add(key);
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                () -> {
                    host.model.remove(key);
                    host.revealed.remove(key);
                });
    }

    void phoneDialog(int index) {
        JSONArray phones = host.array("phones");
        JSONObject entry = index >= 0 ? phones.optJSONObject(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.phone_types);
        type.setSelection(entry == null ? 0 : host.typeAt("phones", index));
        EditText number =
                typedValueLine(content, type, R.string.hint_number,
                        entry == null ? "" : entry.optString("number"),
                        InputType.TYPE_CLASS_PHONE);

        showDialog(
                host.getS(R.string.item_phone),
                content,
                () ->
                        ContactForm.putEntry(
                                phones,
                                index,
                                host.text(number),
                                Cards.formEntry(
                                        "phone",
                                        type.getSelectedItemPosition(),
                                        host.text(number),
                                        entry != null && entry.optBoolean("pref"))),
                index >= 0 ? () -> phones.remove(index) : null);
    }

    void emailDialog(int index) {
        JSONArray emails = host.array("emails");
        JSONObject entry = index >= 0 ? emails.optJSONObject(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.email_types);
        type.setSelection(entry == null ? 0 : host.typeAt("emails", index));
        EditText address =
                typedValueLine(content, type, R.string.hint_email_address,
                        entry == null ? "" : entry.optString("address"),
                        InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        showDialog(
                host.getS(R.string.item_email),
                content,
                () ->
                        ContactForm.putEntry(
                                emails,
                                index,
                                host.text(address),
                                Cards.formEntry(
                                        "email",
                                        type.getSelectedItemPosition(),
                                        host.text(address),
                                        entry != null && entry.optBoolean("pref"))),
                index >= 0 ? () -> emails.remove(index) : null);
    }

    void addressDialog(int index) {
        JSONArray addresses = host.array("addresses");
        JSONObject entry = index >= 0 ? addresses.optJSONObject(index) : null;
        JSONObject safe = entry == null ? new JSONObject() : entry;

        LinearLayout content = dialogContent();
        RadioGroup type =
                typeRadios(
                        content,
                        R.array.address_types,
                        entry == null ? 0 : host.addressAt(index).optInt("index"));
        EditText street =
                dialogField(content, null, R.string.hint_street, safe.optString("street"),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText city =
                dialogField(content, null, R.string.hint_city, safe.optString("city"),
                        ContactForm.TEXT_NAME);
        EditText region =
                dialogField(content, null, R.string.hint_region, safe.optString("region"),
                        ContactForm.TEXT_NAME);
        EditText postcode =
                dialogField(content, null, R.string.hint_postcode, safe.optString("postcode"),
                        InputType.TYPE_CLASS_TEXT);
        EditText country =
                dialogField(content, null, R.string.hint_country, safe.optString("country"),
                        ContactForm.TEXT_NAME);

        showDialog(
                host.getS(R.string.item_address),
                content,
                () -> {
                    String filled =
                            (host.text(street) + host.text(city) + host.text(region)
                                    + host.text(postcode) + host.text(country)).trim();
                    try {
                        JSONObject fresh =
                                new JSONObject()
                                        .put("street", host.text(street))
                                        .put("city", host.text(city))
                                        .put("region", host.text(region))
                                        .put("postcode", host.text(postcode))
                                        .put("country", host.text(country))
                                        // NOTE: fields the dialog omits ride
                                        // along untouched.
                                        .put("pobox", safe.optString("pobox"))
                                        .put("ext", safe.optString("ext"))
                                        .put("pref", safe.optBoolean("pref"))
                                        .put(
                                                "types",
                                                Cards.formEntry(
                                                                "address",
                                                                type.getCheckedRadioButtonId() - 1,
                                                                "",
                                                                false)
                                                        .optJSONArray("types"));
                        ContactForm.putEntry(addresses, index, filled, fresh);
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                index >= 0 ? () -> addresses.remove(index) : null);
    }

    /** A one-field dialog for the plain string lists (hint 0 = none). */
    void stringDialog(String list, int index, int title, int hint, int inputType) {
        JSONArray values = host.array(list);

        LinearLayout content = dialogContent();
        EditText input =
                dialogField(content, null, hint,
                        index >= 0 ? values.optString(index) : "", inputType);

        showDialog(
                host.getS(title),
                content,
                () -> {
                    String fresh = host.text(input);
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

    void noteDialog(int index) {
        JSONArray notes = host.array("notes");

        LinearLayout content = dialogContent();
        EditText input =
                dialogField(content, null, 0,
                        index >= 0 ? notes.optString(index) : "",
                        InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        showDialog(
                host.getS(R.string.item_note),
                content,
                () -> {
                    String fresh = host.text(input);
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

    void relationDialog(int index) {
        JSONArray relations = host.array("relations");
        JSONObject entry = index >= 0 ? relations.optJSONObject(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.relation_types);
        type.setSelection(entry == null ? 0 : host.typeAt("relations", index));
        EditText value =
                typedValueLine(content, type, R.string.hint_relation,
                        entry == null ? "" : entry.optString("value"), ContactForm.TEXT_NAME);

        showDialog(
                host.getS(R.string.item_relation),
                content,
                () ->
                        ContactForm.putEntry(
                                relations,
                                index,
                                host.text(value),
                                Cards.formEntry(
                                        "relation",
                                        type.getSelectedItemPosition(),
                                        host.text(value),
                                        entry != null && entry.optBoolean("pref"))),
                index >= 0 ? () -> relations.remove(index) : null);
    }

    /**
     * The GENDER field: one free-text line with the standard genders as
     * a dropdown of choices, since any identity is allowed. A listed
     * value stores its RFC 6350 sex code, anything else the free-text
     * identity. The dropdown opens with the dialog when adding, stays
     * closed when editing an existing value.
     */
    void genderDialog() {
        String[] labels = host.activity.getResources().getStringArray(R.array.gender_types);
        // NOTE: suggestions drop the leading "Not set" placeholder.
        String[] choices = Arrays.copyOfRange(labels, 1, labels.length);
        boolean adding = !host.filled("gender");

        LinearLayout content = dialogContent();
        AutoCompleteTextView field =
                autoCompleteField(content, R.string.item_gender, genderPrefill(labels), choices,
                        adding);

        showDialog(
                host.getS(R.string.item_gender),
                content,
                () -> {
                    String value = host.text(field);
                    try {
                        if (value.isEmpty()) {
                            host.model.remove("gender");
                        } else {
                            int position = indexOfIgnoreCase(labels, value);
                            host.model.put(
                                    "gender",
                                    position > 0
                                            ? Cards.formEntry("gender", position, "", false)
                                            : new JSONObject().put("sex", "").put("identity", value));
                            host.revealed.add("gender");
                        }
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                () -> {
                    host.model.remove("gender");
                    host.revealed.remove("gender");
                });
    }

    /** The gender field's current display value: the free identity when
     *  set, otherwise the label of its sex code, else empty. */
    private String genderPrefill(String[] labels) {
        JSONObject genderView = host.view.optJSONObject("gender");
        if (genderView == null) {
            return "";
        }
        String identity = genderView.optString("identity");
        if (!identity.isEmpty()) {
            return identity;
        }
        int sexIndex = genderView.optInt("sexIndex");
        return sexIndex > 0 && sexIndex < labels.length ? labels[sexIndex] : "";
    }

    private static int indexOfIgnoreCase(String[] labels, String value) {
        for (int index = 0; index < labels.length; index++) {
            if (labels[index].equalsIgnoreCase(value)) {
                return index;
            }
        }
        return -1;
    }

    /** A date field goes straight to the system date picker. */
    void pickDate(String field) {
        Calendar calendar = Calendar.getInstance();
        JSONObject stored = host.view.optJSONObject(field);
        if (stored != null) {
            // NOTE: today stands in when the stored value is not a date.
            calendar.set(
                    stored.optInt("year"),
                    stored.optInt("month") - 1,
                    stored.optInt("day"));
        }

        DatePickerDialog dialog =
                new DatePickerDialog(
                        host.activity,
                        (picker, year, month, day) -> {
                            try {
                                host.model.put(field, Cards.formDate(year, month + 1, day));
                                host.revealed.add(field);
                            } catch (JSONException error) {
                                throw new IllegalStateException(error);
                            }
                            host.markResolvedAndRender();
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH));
        dialog.setButton(
                DatePickerDialog.BUTTON_NEUTRAL,
                host.activity.getString(R.string.clear),
                (d, which) -> {
                    host.model.remove(field);
                    host.revealed.remove(field);
                    host.markResolvedAndRender();
                });
        dialog.show();
    }

    /** The vertical field container of an edit dialog. */
    private LinearLayout dialogContent() {
        LinearLayout content = new LinearLayout(host.activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(host.dp(24), host.dp(8), host.dp(24), 0);
        return content;
    }

    /**
     * Shows an edit dialog: OK runs the write-back and re-renders the
     * page, Remove (when given) drops the entry.
     */
    private void showDialog(
            CharSequence title, LinearLayout content, Runnable onOk, Runnable onRemove) {
        ScrollView wrap = new ScrollView(host.activity);
        wrap.addView(content);

        AlertDialog.Builder builder =
                new AlertDialog.Builder(host.activity)
                        .setTitle(title)
                        .setView(wrap)
                        .setPositiveButton(
                                android.R.string.ok,
                                (dialog, which) -> {
                                    onOk.run();
                                    host.markResolvedAndRender();
                                })
                        .setNegativeButton(android.R.string.cancel, null);
        if (onRemove != null) {
            builder.setNeutralButton(
                    R.string.remove_row,
                    (dialog, which) -> {
                        onRemove.run();
                        host.markResolvedAndRender();
                    });
        }

        AlertDialog dialog = builder.create();

        // NOTE: force the keyboard only for a plain field; an autocomplete
        // keeps it closed so its dropdown lands in that space, not behind it.
        EditText first = firstInput(content);
        if (first != null) {
            first.requestFocus();
            first.setSelection(first.getText().length());
            if (!(first instanceof AutoCompleteTextView)) {
                dialog.getWindow()
                        .setSoftInputMode(
                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
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
        EditText input = new EditText(host.activity);
        input.setBackgroundTintList(ColorStateList.valueOf(host.accentColor));
        input.setInputType(inputType);
        if (label != 0) {
            input.setHint(label);
        }
        input.setText(prefill);
        content.addView(input);

        JSONArray values = path == null || host.alternatives == null
                ? null
                : host.alternatives.optJSONArray(path);
        if (values != null && values.length() > 0) {
            LinearLayout chips = new LinearLayout(host.activity);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            for (int index = 0; index < values.length(); index++) {
                String candidate = values.optString(index);
                Button chip = new Button(host.activity, null, android.R.attr.buttonStyleSmall);
                // NOTE: the empty alternative is pickable too; it reads
                // "Not set" and clears the field.
                chip.setText(
                        candidate.isEmpty() ? host.getS(R.string.value_not_set) : candidate);
                chip.setAllCaps(false);
                chip.setOnClickListener(view -> input.setText(candidate));
                chips.addView(chip);
            }

            HorizontalScrollView chipScroll = new HorizontalScrollView(host.activity);
            chipScroll.setHorizontalScrollBarEnabled(false);
            chipScroll.addView(chips);
            content.addView(chipScroll);
        }

        return input;
    }

    /**
     * A free-text field backed by a dropdown of choices (the same
     * completer the advanced editor used for property names): the value
     * is editable to anything, the choices only suggest. When {@code
     * open} the dropdown pops as the dialog opens (a fresh field), else
     * it waits for a tap (editing an existing value).
     */
    private AutoCompleteTextView autoCompleteField(
            LinearLayout content, int hint, String prefill, String[] choices, boolean open) {
        AutoCompleteTextView field = new AutoCompleteTextView(host.activity);
        field.setBackgroundTintList(ColorStateList.valueOf(host.accentColor));
        field.setHint(hint);
        field.setText(prefill);
        field.setThreshold(1);
        field.setAdapter(new ArrayAdapter<>(host.activity, R.layout.dropdown_item, choices));
        field.setOnClickListener(view -> field.showDropDown());
        if (open) {
            field.setOnFocusChangeListener(
                    (view, focused) -> {
                        if (focused) {
                            field.post(field::showDropDown);
                        }
                    });
        }
        content.addView(field);
        return field;
    }

    /** The type spinner of an entry dialog (self-describing, no label). */
    private Spinner typeSpinner(int entries) {
        Spinner spinner = new Spinner(host.activity);
        ArrayAdapter<CharSequence> adapter =
                ArrayAdapter.createFromResource(host.activity, entries, R.layout.spinner_form_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // NOTE: zero out the stock spinner's own padding, which otherwise
        // breaks the shared text start.
        spinner.setPadding(0, 0, 0, 0);
        return spinner;
    }

    /**
     * A typed value line: the type spinner sized to its content, then
     * the value field filling the rest of the line.
     */
    private EditText typedValueLine(
            LinearLayout content, Spinner type, int hint, String prefill, int inputType) {
        LinearLayout line = new LinearLayout(host.activity);
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
        params.setMarginStart(host.dp(12));
        input.setLayoutParams(params);

        content.addView(line);
        return input;
    }

    /** The entry types as one line of radios (position = table index). */
    private RadioGroup typeRadios(LinearLayout content, int entries, int selected) {
        RadioGroup group = new RadioGroup(host.activity);
        group.setOrientation(LinearLayout.HORIZONTAL);

        String[] labels = host.activity.getResources().getStringArray(entries);
        for (int index = 0; index < labels.length; index++) {
            RadioButton option = new RadioButton(host.activity);
            option.setText(labels[index]);
            option.setId(index + 1);
            option.setPadding(0, 0, host.dp(16), 0);
            group.addView(option);
        }
        group.check(selected + 1);

        content.addView(group);
        return group;
    }
}
