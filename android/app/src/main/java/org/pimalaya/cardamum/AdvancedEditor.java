package org.pimalaya.cardamum;

import android.app.AlertDialog;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Cards;

/**
 * The advanced raw-vCard sub-editor behind the edit form: a
 * structured property list over the working document, each row a
 * name/parameters/value dialog recomposed through the bridge, plus a
 * second-level free-hand source panel. It reads and writes the working
 * vCard through {@link EditSession#advancedVcard} and
 * {@link EditSession#advancedDirty}; the host keeps the flipper
 * navigation and calls in through {@link #open}, {@link #close},
 * {@link #openSource} and {@link #applySource}.
 */
final class AdvancedEditor {
    private final MainActivity host;

    AdvancedEditor(MainActivity host) {
        this.host = host;
    }

    /**
     * Opens the raw-property editor on the working document, the form's
     * current state baked in first so both views agree. Only offered on
     * single-card contacts (and new ones): raw lines cannot fan out to
     * several physical documents.
     */
    void open() {
        try {
            String working =
                    host.edit.advancedVcard != null ? host.edit.advancedVcard : host.edit.vcard;
            host.edit.advancedVcard = Cards.applyCard(working, host.form.collect());
            render();
        } catch (Exception error) {
            host.showError(error, R.string.contact_open_failed);
            return;
        }
        host.show(MainActivity.PANEL_ADVANCED);
    }

    /** Back to the form, rebuilt from the edited document. */
    void close() {
        try {
            host.form.load(Cards.projectCard(host.edit.advancedVcard), null, null);
        } catch (Exception error) {
            host.showError(error, R.string.contact_open_failed);
        }
        host.showBack(MainActivity.PANEL_CONTACT);
    }

    /** Rebuilds the property rows and the closing source block. */
    private void render() {
        LinearLayout container = host.findViewById(R.id.advanced_container);
        container.removeAllViews();

        org.json.JSONArray props = Cards.cardProps(host.edit.advancedVcard);
        for (int index = 0; props != null && index < props.length(); index++) {
            int at = index;
            JSONObject prop = props.optJSONObject(index);
            container.addView(
                    propertyRow(
                            prop.optString("name"),
                            prop.optString("line"),
                            () -> advancedDialog(at, prop)));
        }

        android.widget.ImageView addIcon = new android.widget.ImageView(host);
        addIcon.setImageResource(R.drawable.ic_add);
        addIcon.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.ui.resolveColor(android.R.attr.textColorPrimary)));

        TextView add = new TextView(host);
        add.setText(R.string.advanced_add);
        add.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        add.setTextSize(14);
        LinearLayout.LayoutParams addParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        addParams.setMarginStart(host.ui.dp(8));

        LinearLayout addRow = new LinearLayout(host);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        addRow.setPadding(host.ui.dp(16), host.ui.dp(12), host.ui.dp(16), host.ui.dp(12));
        addRow.setBackgroundResource(host.ui.resolveAttr(android.R.attr.selectableItemBackground));
        addRow.setOnClickListener(view -> advancedDialog(-1, new JSONObject()));
        addRow.addView(addIcon, new LinearLayout.LayoutParams(host.ui.dp(24), host.ui.dp(24)));
        addRow.addView(add, addParams);
        container.addView(addRow);

        TextView header = new TextView(host);
        header.setText(R.string.advanced_source);
        header.setTextColor(host.ui.resolveColor(android.R.attr.colorAccent));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.setPadding(host.ui.dp(16), host.ui.dp(24), host.ui.dp(16), host.ui.dp(4));
        container.addView(header);

        TextView source = new TextView(host);
        source.setText(host.edit.advancedVcard);
        source.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
        source.setTypeface(android.graphics.Typeface.MONOSPACE);
        source.setTextSize(12);
        source.setPadding(host.ui.dp(16), 0, host.ui.dp(16), host.ui.dp(12));
        source.setBackgroundResource(host.ui.resolveAttr(android.R.attr.selectableItemBackground));
        source.setOnClickListener(view -> openSource());
        container.addView(source);
    }

    /** A tappable property row: the name over the whole raw line. */
    private android.view.View propertyRow(String title, String line, Runnable onClick) {
        TextView titleView = new TextView(host);
        titleView.setText(title);
        titleView.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
        titleView.setTextSize(15);

        TextView lineView = new TextView(host);
        lineView.setText(line);
        lineView.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
        lineView.setTypeface(android.graphics.Typeface.MONOSPACE);
        lineView.setTextSize(12);

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(host.ui.dp(16), host.ui.dp(12), host.ui.dp(16), host.ui.dp(12));
        row.setBackgroundResource(host.ui.resolveAttr(android.R.attr.selectableItemBackground));
        row.setOnClickListener(view -> onClick.run());
        row.addView(titleView);
        row.addView(lineView);
        return row;
    }

    /**
     * Edits one property as a structured form: its name, one row per
     * parameter (name and comma-separated values, an emptied name
     * drops the parameter), a closing accent row to add one, and the
     * raw value. OK recomposes through the bridge, Remove drops the
     * whole property.
     */
    private void advancedDialog(int index, JSONObject prop) {
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(host.ui.dp(16), host.ui.dp(8), host.ui.dp(16), 0);

        android.widget.AutoCompleteTextView name =
                registryField(R.string.advanced_prop_name, prop.optString("name"), KNOWN_PROPS);
        name.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        content.addView(name);

        LinearLayout params = new LinearLayout(host);
        params.setOrientation(LinearLayout.VERTICAL);
        org.json.JSONArray existing = prop.optJSONArray("params");
        for (int at = 0; existing != null && at < existing.length(); at++) {
            JSONObject param = existing.optJSONObject(at);
            org.json.JSONArray values = param.optJSONArray("values");
            List<String> parts = new ArrayList<>();
            for (int v = 0; values != null && v < values.length(); v++) {
                parts.add(values.optString(v));
            }
            params.addView(paramRow(param.optString("name"), String.join(",", parts)));
        }
        content.addView(params);

        TextView addParam = new TextView(host);
        addParam.setText(R.string.advanced_add_param);
        addParam.setTextColor(host.ui.resolveColor(android.R.attr.colorAccent));
        addParam.setTextSize(14);
        addParam.setPadding(0, host.ui.dp(8), 0, host.ui.dp(8));
        addParam.setOnClickListener(view -> params.addView(paramRow("", "")));
        content.addView(addParam);

        // The value area shapes itself from the property name: one
        // labeled field per component of a structured value, or one raw field.
        LinearLayout valueArea = new LinearLayout(host);
        valueArea.setOrientation(LinearLayout.VERTICAL);
        buildValueArea(valueArea, prop.optString("name"), prop);
        name.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        buildValueArea(valueArea, s.toString(), prop);
                    }
                });
        content.addView(valueArea);

        ScrollView scroll = new ScrollView(host);
        scroll.addView(content);

        AlertDialog.Builder builder =
                new AlertDialog.Builder(host)
                        .setTitle(
                                index < 0
                                        ? host.getString(R.string.advanced_add)
                                        : prop.optString("name"))
                        .setView(scroll)
                        .setPositiveButton(
                                android.R.string.ok,
                                (dialog, which) ->
                                        applyAdvancedParts(
                                                index, collectProp(name, params, valueArea)))
                        .setNegativeButton(android.R.string.cancel, null);
        if (index >= 0) {
            builder.setNeutralButton(
                    R.string.remove_row,
                    (dialog, which) -> applyAdvancedRaw(index, ""));
        }
        builder.show();
    }

    /** RFC 6350 property names, proposed by the advanced editor. */
    private static final String[] KNOWN_PROPS = {
        "ADR", "ANNIVERSARY", "BDAY", "CALADRURI", "CALURI", "CATEGORIES",
        "CLIENTPIDMAP", "EMAIL", "FBURL", "FN", "GENDER", "GEO", "IMPP",
        "KEY", "KIND", "LANG", "LOGO", "MEMBER", "N", "NICKNAME", "NOTE",
        "ORG", "PHOTO", "PRODID", "RELATED", "REV", "ROLE", "SOUND",
        "SOURCE", "TEL", "TITLE", "TZ", "UID", "URL", "VERSION", "XML",
    };

    /** RFC 6350 parameter names, proposed by the advanced editor. */
    private static final String[] KNOWN_PARAMS = {
        "ALTID", "CALSCALE", "GEO", "LABEL", "LANGUAGE", "MEDIATYPE",
        "PID", "PREF", "SORT-AS", "TYPE", "TZ", "VALUE",
    };

    /** RFC 6350 TYPE values (general, TEL and RELATED registries). */
    private static final String[] TYPE_VALUES = {
        "home", "work", "cell", "fax", "voice", "video", "pager", "text",
        "textphone", "contact", "acquaintance", "friend", "met",
        "co-worker", "colleague", "co-resident", "neighbor", "child",
        "parent", "sibling", "spouse", "kin", "muse", "crush", "date",
        "sweetheart", "me", "agent", "emergency",
    };

    /** RFC 6350 VALUE kinds. */
    private static final String[] VALUE_KINDS = {
        "text", "uri", "date", "time", "date-time", "date-and-or-time",
        "timestamp", "boolean", "integer", "float", "utc-offset",
        "language-tag",
    };

    /** The known values of a parameter, empty when free-form. */
    private static String[] paramValues(String param) {
        switch (param.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "TYPE":
                return TYPE_VALUES;
            case "VALUE":
                return VALUE_KINDS;
            case "CALSCALE":
                return new String[] {"gregorian"};
            default:
                return new String[0];
        }
    }

    /** A free-text field proposing the given registry as a dropdown. */
    private android.widget.AutoCompleteTextView registryField(
            int hint, String text, String[] registry) {
        android.widget.AutoCompleteTextView field =
                new android.widget.AutoCompleteTextView(host);
        field.setHint(hint);
        field.setText(text);
        field.setThreshold(1);
        field.setAdapter(
                new android.widget.ArrayAdapter<>(
                        host, android.R.layout.simple_dropdown_item_1line, registry));
        // NOTE: a first tap only focuses (the click fires on the second),
        // so focus gain opens the dropdown too.
        field.setOnClickListener(view -> field.showDropDown());
        field.setOnFocusChangeListener(
                (view, focused) -> {
                    if (focused) {
                        field.post(field::showDropDown);
                    }
                });
        return field;
    }

    private LinearLayout paramRow(String name, String values) {
        android.widget.AutoCompleteTextView nameField =
                registryField(R.string.advanced_param_name, name, KNOWN_PARAMS);
        nameField.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);

        // The values complete per comma-separated token, against the
        // named parameter's registry (TYPE, VALUE, CALSCALE).
        android.widget.MultiAutoCompleteTextView valuesField =
                new android.widget.MultiAutoCompleteTextView(host);
        valuesField.setHint(R.string.advanced_param_values);
        valuesField.setText(values);
        valuesField.setThreshold(1);
        valuesField.setTokenizer(
                new android.widget.MultiAutoCompleteTextView.CommaTokenizer());
        valuesField.setOnClickListener(view -> valuesField.showDropDown());
        valuesField.setOnFocusChangeListener(
                (view, focused) -> {
                    if (focused) {
                        valuesField.post(valuesField::showDropDown);
                    }
                });
        Runnable retune =
                () ->
                        valuesField.setAdapter(
                                new android.widget.ArrayAdapter<>(
                                        host,
                                        android.R.layout.simple_dropdown_item_1line,
                                        paramValues(nameField.getText().toString())));
        retune.run();
        nameField.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        retune.run();
                    }
                });

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.addView(
                nameField,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(
                valuesField,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        return row;
    }

    /**
     * Shapes the dialog's value area from the property name: one
     * labeled field per component of a structured value (the labels
     * from the bridge, mirroring the vcard-rs value types), or one raw
     * monospace field. Only rebuilt when the shape actually changes,
     * so typing in the fields survives name keystrokes.
     */
    private void buildValueArea(LinearLayout area, String propName, JSONObject prop) {
        List<String> labels = new ArrayList<>();
        try {
            org.json.JSONArray found = Cards.cardPropLabels(propName.trim());
            for (int index = 0; found != null && index < found.length(); index++) {
                labels.add(found.optString(index));
            }
        } catch (Exception ignored) {
            // NOTE: an unreadable registry just leaves the raw field.
        }

        String shape = String.join("\u001F", labels);
        if (shape.equals(area.getTag())) {
            return;
        }
        area.setTag(shape);
        area.removeAllViews();

        if (labels.isEmpty()) {
            EditText value = new EditText(host);
            value.setHint(R.string.advanced_value);
            value.setText(prop.optString("value"));
            value.setTypeface(android.graphics.Typeface.MONOSPACE);
            value.setInputType(
                    android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            area.addView(value);
            return;
        }

        org.json.JSONArray existing = prop.optJSONArray("components");
        for (int index = 0; index < labels.size(); index++) {
            EditText component = new EditText(host);
            component.setHint(labels.get(index));
            if (existing != null && index < existing.length()) {
                component.setText(existing.optJSONObject(index).optString("value"));
            }
            area.addView(component);
        }
    }

    /** The structured property the dialog's fields describe. */
    private static JSONObject collectProp(
            EditText name, LinearLayout params, LinearLayout valueArea) {
        try {
            org.json.JSONArray collected = new org.json.JSONArray();
            for (int index = 0; index < params.getChildCount(); index++) {
                LinearLayout row = (LinearLayout) params.getChildAt(index);
                String paramName =
                        ((EditText) row.getChildAt(0)).getText().toString().trim();
                if (paramName.isEmpty()) {
                    continue;
                }
                org.json.JSONArray values = new org.json.JSONArray();
                for (String part :
                        ((EditText) row.getChildAt(1)).getText().toString().split(",")) {
                    if (!part.trim().isEmpty()) {
                        values.put(part.trim());
                    }
                }
                collected.put(
                        new JSONObject().put("name", paramName).put("values", values));
            }

            JSONObject prop =
                    new JSONObject()
                            .put("name", name.getText().toString().trim())
                            .put("params", collected);

            String shape = (String) valueArea.getTag();
            if (shape == null || shape.isEmpty()) {
                prop.put("value", ((EditText) valueArea.getChildAt(0)).getText().toString());
            } else {
                String[] labels = shape.split("\u001F", -1);
                org.json.JSONArray components = new org.json.JSONArray();
                for (int index = 0; index < valueArea.getChildCount(); index++) {
                    components.put(
                            new JSONObject()
                                    .put("name", labels[index])
                                    .put(
                                            "value",
                                            ((EditText) valueArea.getChildAt(index))
                                                    .getText()
                                                    .toString()));
                }
                prop.put("components", components);
            }
            return prop;
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private void applyAdvancedParts(int index, JSONObject prop) {
        try {
            host.edit.advancedVcard = Cards.cardSetPropParts(host.edit.advancedVcard, index, prop);
            host.edit.advancedDirty = true;
            render();
        } catch (Exception error) {
            host.showError(error, R.string.advanced_invalid);
        }
    }

    /** A raw rewrite: only removal uses it (a blank line drops). */
    private void applyAdvancedRaw(int index, String line) {
        try {
            host.edit.advancedVcard = Cards.cardSetProp(host.edit.advancedVcard, index, line);
            host.edit.advancedDirty = true;
            render();
        } catch (Exception error) {
            host.showError(error, R.string.advanced_invalid);
        }
    }

    /** The advanced editor's second level: the free-hand source. */
    void openSource() {
        ((EditText) host.findViewById(R.id.source_input)).setText(host.edit.advancedVcard);
        host.show(MainActivity.PANEL_SOURCE);
    }

    /** Applies the hand-edited source (it must reparse) and returns. */
    void applySource() {
        try {
            host.edit.advancedVcard =
                    Cards.cardSource(
                            ((EditText) host.findViewById(R.id.source_input))
                                    .getText()
                                    .toString());
            host.edit.advancedDirty = true;
            render();
        } catch (Exception error) {
            host.showError(error, R.string.advanced_invalid);
            return;
        }
        host.showBack(MainActivity.PANEL_ADVANCED);
    }
}
