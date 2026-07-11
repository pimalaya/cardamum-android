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
import android.widget.AutoCompleteTextView;
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
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.CardamumClient;

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
 * Rust bridge, and the bridge also computes the page's view support
 * (summaries, type spinner positions, picker dates): the type tables
 * and their vCard semantics live there, this class only localizes the
 * labels the positions address. A diverged contact loads in conflict
 * mode: only the disagreeing items show, and the dialogs of
 * conflicted single fields carry one tappable chip per candidate
 * value.
 */
final class ContactForm {
    private static final int TEXT_NAME =
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;

    private static final int URI_INPUT =
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;

    /**
     * One addable field in the catalog: the model key, the section
     * (category) it belongs to (a section-title resource, its identity),
     * its chooser label and icon, whether it is a repeatable list (always
     * offerable, one entry per pick) or a single field (offerable only
     * while absent), and the action that opens its edit dialog.
     */
    private final class Field {
        final String key;
        final int category;
        final int label;
        final int icon;
        final boolean list;
        final Runnable open;

        Field(String key, int category, int label, int icon, boolean list, Runnable open) {
            this.key = key;
            this.category = category;
            this.label = label;
            this.icon = icon;
            this.list = list;
            this.open = open;
        }
    }

    private final Activity activity;
    private final CardamumClient client;
    private final int accentColor;
    private final int labelColor;
    private final int primaryColor;
    private final ScrollView scroll;
    private final LinearLayout container;

    /** The working field model the dialogs edit in place. */
    private JSONObject model = new JSONObject();

    /** The bridge-computed view support of the current model. */
    private JSONObject view = new JSONObject();

    /** Conflict alternatives by single-field path; null outside conflicts. */
    private JSONObject alternatives;

    /** Changed list sections of a conflict; null outside conflict mode. */
    private Set<String> changedLists;

    /** Field kinds the user revealed this session through the add-field
     *  dialog, so their empty section keeps showing. Cleared on load. */
    private final Set<String> revealed = new HashSet<>();

    /** Conflict rows the user has reviewed (opened, then confirmed with
     *  OK), so their divergence warning clears. Cleared on load. */
    private final Set<String> resolved = new HashSet<>();

    /** Conflict rows still awaiting review, rebuilt on every render. */
    private final Set<String> pending = new HashSet<>();

    /** Numbers the conflict rows of a render, so each gets a stable key. */
    private int conflictRow;

    /** The row a just-opened dialog resolves on OK; null when none. */
    private String pendingResolveKey;

    /** Notified after every render so the host can re-gate its save. */
    private Runnable onRender;

    ContactForm(Activity activity, CardamumClient client) {
        this.activity = activity;
        this.client = client;
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
        this.revealed.clear();
        this.resolved.clear();
        this.pendingResolveKey = null;
        if (changed != null) {
            changedLists = new HashSet<>();
            for (int index = 0; index < changed.length(); index++) {
                changedLists.add(changed.optString(index));
            }
        } else if (this.model.length() == 0) {
            // A brand-new contact starts on the common fields rather than a
            // blank page under the add button: first and last name, plus an
            // empty phone and email row ready to fill.
            revealed.add("name.given");
            revealed.add("name.family");
            revealed.add("phones");
            revealed.add("emails");
        }

        render();
        scroll.scrollTo(0, 0);
    }

    /** The edited field model. */
    JSONObject collect() {
        return model;
    }

    /** Sets the post-render hook (the host's save-gate refresh). */
    void setOnRender(Runnable onRender) {
        this.onRender = onRender;
    }

    /**
     * Whether the form may be validated: always in a plain edit, in a
     * merge only once every diverging row has been reviewed.
     */
    boolean conflictResolved() {
        return !conflict() || pending.isEmpty();
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

    /**
     * Whether a field or list section shows in normal (non-conflict)
     * mode: one that carries a value or was added through a chooser this
     * session. So the editor opens showing only the sections that hold
     * data. Conflict mode shows the disagreeing items instead, via the
     * {@code || conflicted / changed} branch kept at each render site.
     */
    private boolean shown(String key, boolean filled) {
        return !conflict() && (filled || revealed.contains(key));
    }

    /** Whether the field or list section currently carries a value. */
    private boolean filled(String key) {
        switch (key) {
            case "displayName":
                return !model.optString("displayName").trim().isEmpty();
            case "name.prefix":
            case "name.given":
            case "name.middle":
            case "name.family":
            case "name.suffix":
                return !namePart(key.substring("name.".length())).isEmpty();
            case "birthday":
            case "anniversary":
                return !model.optString(key).trim().isEmpty();
            case "gender":
                return model.optJSONObject("gender") != null;
            case "organization.company":
            case "organization.department":
                return !organizationPart(key.substring("organization.".length())).isEmpty();
            case "title":
            case "role":
                return !model.optString(key).trim().isEmpty();
            default:
                JSONArray values = model.optJSONArray(key);
                return values != null && values.length() > 0;
        }
    }

    /** A single component of the structured name, empty when unset. */
    private String namePart(String part) {
        JSONObject name = model.optJSONObject("name");
        return name == null ? "" : name.optString(part).trim();
    }

    /** Appends a structured-name component row when it carries a value,
     *  was revealed, or (in conflict mode) disagrees. */
    private void namePartRow(List<View> identity, String part, int hint) {
        String key = "name." + part;
        if (shown(key, filled(key)) || conflicted(key)) {
            identity.add(entryItem(getS(hint), value(namePart(part)), () -> namePartDialog(part, hint)));
        }
    }

    /** The full field catalog with each field's category, chooser label
     *  and icon, and the action that opens its edit dialog. */
    private List<Field> catalog() {
        int person = R.drawable.ic_section_person;
        int identity = R.string.section_identity;
        List<Field> fields = new ArrayList<>();

        fields.add(new Field("displayName", identity, R.string.hint_display_name,
                R.drawable.ic_display_name, false, this::displayNameDialog));
        fields.add(new Field("name.prefix", identity, R.string.hint_prefix,
                R.drawable.ic_prefix, false, () -> namePartDialog("prefix", R.string.hint_prefix)));
        fields.add(new Field("name.given", identity, R.string.hint_given, person, false,
                () -> namePartDialog("given", R.string.hint_given)));
        fields.add(new Field("name.middle", identity, R.string.hint_middle, person, false,
                () -> namePartDialog("middle", R.string.hint_middle)));
        fields.add(new Field("name.family", identity, R.string.hint_family, person, false,
                () -> namePartDialog("family", R.string.hint_family)));
        fields.add(new Field("name.suffix", identity, R.string.hint_suffix,
                R.drawable.ic_suffix, false, () -> namePartDialog("suffix", R.string.hint_suffix)));
        fields.add(new Field("gender", identity, R.string.item_gender,
                R.drawable.ic_gender, false, this::genderDialog));
        fields.add(new Field("birthday", identity, R.string.hint_birthday,
                R.drawable.ic_birthday, false, () -> pickDate("birthday")));
        fields.add(new Field("anniversary", identity, R.string.item_anniversary,
                R.drawable.ic_anniversary, false, () -> pickDate("anniversary")));

        int work = R.string.tab_work;
        fields.add(new Field("organization.company", work, R.string.hint_company,
                R.drawable.ic_company, false,
                () -> organizationPartDialog("company", R.string.hint_company)));
        fields.add(new Field("organization.department", work, R.string.hint_department,
                R.drawable.ic_section_work, false,
                () -> organizationPartDialog("department", R.string.hint_department)));
        fields.add(new Field("title", work, R.string.hint_job_title,
                R.drawable.ic_title, false,
                () -> simpleFieldDialog("title", R.string.hint_job_title)));
        fields.add(new Field("role", work, R.string.hint_role,
                R.drawable.ic_role, false,
                () -> simpleFieldDialog("role", R.string.hint_role)));

        fields.add(new Field("nicknames", R.string.section_nicknames, R.string.item_nickname,
                R.drawable.ic_section_label, true,
                () -> stringDialog("nicknames", -1, R.string.item_nickname, 0, TEXT_NAME)));
        fields.add(new Field("relations", R.string.section_relations, R.string.item_relation,
                R.drawable.ic_section_group, true, () -> relationDialog(-1)));
        fields.add(new Field("phones", R.string.section_phones, R.string.item_phone,
                R.drawable.ic_section_call, true, () -> phoneDialog(-1)));
        fields.add(new Field("emails", R.string.section_emails, R.string.item_email,
                R.drawable.ic_section_mail, true, () -> emailDialog(-1)));
        fields.add(new Field("impps", R.string.section_impps, R.string.item_impp,
                R.drawable.ic_section_chat, true,
                () -> stringDialog("impps", -1, R.string.item_impp, R.string.hint_impp, URI_INPUT)));
        fields.add(new Field("addresses", R.string.section_addresses, R.string.item_address,
                R.drawable.ic_section_location_on, true, () -> addressDialog(-1)));
        fields.add(new Field("websites", R.string.section_websites, R.string.item_website,
                R.drawable.ic_section_language, true,
                () -> stringDialog("websites", -1, R.string.item_website, R.string.hint_url,
                        URI_INPUT)));
        fields.add(new Field("languages", R.string.section_languages, R.string.item_language,
                R.drawable.ic_section_translate, true,
                () -> stringDialog("languages", -1, R.string.item_language, R.string.hint_language,
                        InputType.TYPE_CLASS_TEXT)));
        fields.add(new Field("notes", R.string.section_notes, R.string.item_note,
                R.drawable.ic_section_notes, true, () -> noteDialog(-1)));

        return fields;
    }

    /** Whether a field can still be added: a list takes another entry any
     *  time, a single field only while it is absent. */
    private boolean offerable(Field field) {
        return field.list || !shown(field.key, filled(field.key));
    }

    /** The main add action (FAB): every field that can still be added,
     *  across all sections. */
    void addField() {
        List<Field> offer = new ArrayList<>();
        for (Field field : catalog()) {
            if (offerable(field)) {
                offer.add(field);
            }
        }
        showChooser(offer);
    }

    /** A section's add action: the same chooser, filtered to that section
     *  (category), so its header icon only offers its own fields. */
    private void addToSection(int category) {
        List<Field> offer = new ArrayList<>();
        for (Field field : catalog()) {
            if (field.category == category && offerable(field)) {
                offer.add(field);
            }
        }
        showChooser(offer);
    }

    /**
     * Presents the field chooser. Picking a field opens its edit dialog;
     * saving it adds the field (and its section, when new) to the page. A
     * lone option skips the list and opens straight away; an empty set
     * says everything is already on the page.
     */
    private void showChooser(List<Field> fields) {
        if (fields.isEmpty()) {
            Toast.makeText(activity, R.string.add_field_none, Toast.LENGTH_SHORT).show();
            return;
        }
        if (fields.size() == 1) {
            fields.get(0).open.run();
            return;
        }

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        // Match the framework title's top padding at the bottom so the
        // dialog is vertically symmetric and the last row clears the
        // rounded corner; the title supplies the gap above the first row.
        list.setPadding(0, dp(8), 0, dp(18));
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(list);

        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.contact_add_field)
                        .setView(scroller)
                        .create();

        for (Field field : fields) {
            list.addView(chooserRow(field, dialog));
        }
        dialog.show();
    }

    /** One icon-and-label chooser row; picking it closes the chooser and
     *  opens the field's edit dialog. */
    private View chooserRow(Field field, AlertDialog dialog) {
        ImageView iconView = new ImageView(activity);
        iconView.setImageResource(field.icon);
        iconView.setImageTintList(ColorStateList.valueOf(labelColor));

        TextView labelView = new TextView(activity);
        labelView.setText(field.label);
        labelView.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(dp(12));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(14), dp(24), dp(14));
        row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        row.addView(iconView, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.addView(labelView, labelParams);
        row.setOnClickListener(
                view -> {
                    dialog.dismiss();
                    field.open.run();
                });
        return row;
    }

    /** Rebuilds the whole page from the working model. */
    private void render() {
        view = client.formView(model);
        container.removeAllViews();
        conflictRow = 0;
        pending.clear();

        // Identity holds the solo fields: the display name (its own
        // line), the name parts, the dates and the gender. Every
        // multi-valued list is its own section below.
        List<View> identity = new ArrayList<>();
        if (shown("displayName", filled("displayName")) || conflicted("displayName")) {
            identity.add(
                    entryItem(
                            getS(R.string.hint_display_name),
                            value(model.optString("displayName")),
                            this::displayNameDialog));
        }
        namePartRow(identity, "prefix", R.string.hint_prefix);
        namePartRow(identity, "given", R.string.hint_given);
        namePartRow(identity, "middle", R.string.hint_middle);
        namePartRow(identity, "family", R.string.hint_family);
        namePartRow(identity, "suffix", R.string.hint_suffix);
        if (shown("birthday", filled("birthday")) || conflicted("birthday")) {
            identity.add(
                    entryItem(
                            getS(R.string.hint_birthday),
                            value(model.optString("birthday")),
                            () -> pickDate("birthday")));
        }
        if (shown("anniversary", filled("anniversary")) || conflicted("anniversary")) {
            identity.add(
                    entryItem(
                            getS(R.string.item_anniversary),
                            value(model.optString("anniversary")),
                            () -> pickDate("anniversary")));
        }
        if (shown("gender", filled("gender")) || conflicted("gender.sex", "gender.identity")) {
            identity.add(
                    entryItem(getS(R.string.item_gender), genderSummary(), this::genderDialog));
        }
        section(R.string.section_identity, R.drawable.ic_section_person, identity);

        if (shown("nicknames", filled("nicknames")) || (conflict() && changedLists.contains("nicknames"))) {
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
            section(R.string.section_nicknames, R.drawable.ic_section_label, items);
        }

        // Work holds solo fields too, each its own row so a single one
        // can be edited and (in conflict mode) resolved on its own, like
        // the identity section.
        List<View> work = new ArrayList<>();
        if (shown("organization.company", filled("organization.company"))
                || conflicted("organization.company")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_company),
                            value(organizationPart("company")),
                            () -> organizationPartDialog("company", R.string.hint_company)));
        }
        if (shown("organization.department", filled("organization.department"))
                || conflicted("organization.department")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_department),
                            value(organizationPart("department")),
                            () -> organizationPartDialog("department", R.string.hint_department)));
        }
        if (shown("title", filled("title")) || conflicted("title")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_job_title),
                            value(model.optString("title")),
                            () -> simpleFieldDialog("title", R.string.hint_job_title)));
        }
        if (shown("role", filled("role")) || conflicted("role")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_role),
                            value(model.optString("role")),
                            () -> simpleFieldDialog("role", R.string.hint_role)));
        }
        section(R.string.tab_work, R.drawable.ic_section_work, work);

        if (shown("relations", filled("relations")) || (conflict() && changedLists.contains("relations"))) {
            List<View> items = new ArrayList<>();
            JSONArray relations = array("relations");
            for (int index = 0; index < relations.length(); index++) {
                int at = index;
                JSONObject entry = relations.optJSONObject(index);
                items.add(
                        entryItem(
                                entry.optString("value"),
                                typeLabel(R.array.relation_types, typeAt("relations", index)),
                                () -> relationDialog(at)));
            }
            section(R.string.section_relations, R.drawable.ic_section_group, items);
        }

        if (shown("phones", filled("phones")) || (conflict() && changedLists.contains("phones"))) {
            List<View> items = new ArrayList<>();
            JSONArray phones = array("phones");
            for (int index = 0; index < phones.length(); index++) {
                int at = index;
                JSONObject entry = phones.optJSONObject(index);
                items.add(
                        entryItem(
                                entry.optString("number"),
                                typeLabel(R.array.phone_types, typeAt("phones", index)),
                                () -> phoneDialog(at)));
            }
            // A fresh contact reveals the section as an empty header (with
            // its add control), no blank placeholder row.
            section(R.string.section_phones, R.drawable.ic_section_call, items, true);
        }

        if (shown("emails", filled("emails")) || (conflict() && changedLists.contains("emails"))) {
            List<View> items = new ArrayList<>();
            JSONArray emails = array("emails");
            for (int index = 0; index < emails.length(); index++) {
                int at = index;
                JSONObject entry = emails.optJSONObject(index);
                items.add(
                        entryItem(
                                entry.optString("address"),
                                typeLabel(R.array.email_types, typeAt("emails", index)),
                                () -> emailDialog(at)));
            }
            section(R.string.section_emails, R.drawable.ic_section_mail, items, true);
        }

        if (shown("impps", filled("impps")) || (conflict() && changedLists.contains("impps"))) {
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
            section(R.string.section_impps, R.drawable.ic_section_chat, items);
        }

        if (shown("addresses", filled("addresses")) || (conflict() && changedLists.contains("addresses"))) {
            List<View> items = new ArrayList<>();
            JSONArray entries = array("addresses");
            for (int index = 0; index < entries.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                value(addressAt(index).optString("summary")),
                                typeLabel(
                                        R.array.address_types,
                                        addressAt(index).optInt("index")),
                                () -> addressDialog(at)));
            }
            section(R.string.section_addresses, R.drawable.ic_section_location_on, items);
        }

        if (shown("websites", filled("websites")) || (conflict() && changedLists.contains("websites"))) {
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
            section(R.string.section_websites, R.drawable.ic_section_language, items);
        }

        if (shown("languages", filled("languages")) || (conflict() && changedLists.contains("languages"))) {
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
            section(R.string.section_languages, R.drawable.ic_section_translate, items);
        }

        if (shown("notes", filled("notes")) || (conflict() && changedLists.contains("notes"))) {
            List<View> items = new ArrayList<>();
            JSONArray notes = array("notes");
            for (int index = 0; index < notes.length(); index++) {
                int at = index;
                items.add(entryItem(notes.optString(index), null, () -> noteDialog(at)));
            }
            section(R.string.section_notes, R.drawable.ic_section_notes, items);
        }

        if (onRender != null) {
            onRender.run();
        }
    }

    /** Marks the open conflict row reviewed (its warning clears), then
     *  re-renders. Cancelling a dialog skips this, so the warning stays. */
    private void markResolvedAndRender() {
        if (pendingResolveKey != null) {
            resolved.add(pendingResolveKey);
            pendingResolveKey = null;
        }
        render();
    }

    /**
     * Adds a section: its icon and accent label, then a right-aligned add
     * icon opening the chooser filtered to this section (so its header
     * only offers its own fields), then its items, separated from the
     * previous section by a line in the app bar tone. An empty section
     * vanishes, and the add icon is hidden in conflict mode.
     */
    private void section(int title, int icon, List<View> items) {
        section(title, icon, items, false);
    }

    private void section(int title, int icon, List<View> items, boolean keepEmpty) {
        if (items.isEmpty() && !keepEmpty) {
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
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(dp(8));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), container.getChildCount() <= 1 ? dp(16) : dp(10), dp(10), dp(0));
        header.addView(iconView, new LinearLayout.LayoutParams(dp(18), dp(18)));
        header.addView(label, labelParams);
        if (!conflict()) {
            header.addView(addIcon(R.string.contact_add_field, () -> addToSection(title)));
        }
        container.addView(header);

        for (View item : items) {
            container.addView(item);
        }
    }

    /** The right-aligned add action of a section header. */
    private View addIcon(int label, Runnable onClick) {
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_add_entry);
        icon.setImageTintList(ColorStateList.valueOf(accentColor));
        icon.setContentDescription(getS(label));
        icon.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackgroundBorderless));
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        icon.setOnClickListener(view -> onClick.run());
        return icon;
    }

    /**
     * A tappable row: title over a diminished subtitle. In conflict mode
     * every row diverges, so it carries a trailing warning (the same one
     * the list shows) until the user opens it and confirms with OK; its
     * key is positional, stable while rows only get reviewed, not added.
     */
    private View entryItem(CharSequence title, CharSequence subtitle, Runnable onClick) {
        String key = conflict() ? "row" + conflictRow++ : null;

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(titleView);

        if (subtitle != null && subtitle.length() > 0) {
            TextView subtitleView = new TextView(activity);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(labelColor);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            text.addView(subtitleView);
        }

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        row.setOnClickListener(
                view -> {
                    pendingResolveKey = key;
                    onClick.run();
                });
        row.addView(
                text,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (key != null && !resolved.contains(key)) {
            pending.add(key);
            ImageView warn = new ImageView(activity);
            warn.setImageResource(R.drawable.ic_diverged);
            // The exact glyph the list shows: same 32dp box, same error
            // tint, centred against the row (the row is CENTER_VERTICAL).
            warn.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            resolveColor(android.R.attr.colorError)));
            LinearLayout.LayoutParams warnParams =
                    new LinearLayout.LayoutParams(dp(32), dp(32));
            warnParams.setMarginStart(dp(12));
            row.addView(warn, warnParams);
        }

        return row;
    }

    // ---- Item summaries -----------------------------------------------------

    /** A field's display value, or empty for an unset field: an empty
     *  subtitle leaves the row present but blank, no "not set" filler. */
    private String value(String text) {
        return text.trim();
    }

    private String genderSummary() {
        JSONObject gender = view.optJSONObject("gender");
        if (gender == null) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        int sex = gender.optInt("sexIndex");
        if (sex > 0) {
            parts.add(typeLabel(R.array.gender_types, sex));
        }
        if (!gender.optString("identity").isEmpty()) {
            parts.add(gender.optString("identity"));
        }
        return String.join(" · ", parts);
    }

    private String typeLabel(int arrayId, int index) {
        String[] labels = activity.getResources().getStringArray(arrayId);
        return labels[Math.min(index, labels.length - 1)];
    }

    // ---- View support -------------------------------------------------------

    /** The type spinner position of a list entry, bridge-computed. */
    private int typeAt(String list, int index) {
        JSONArray positions = view.optJSONArray(list);
        return positions == null ? 0 : positions.optInt(index);
    }

    /** An address entry's view support ({@code {index, summary}}). */
    private JSONObject addressAt(int index) {
        JSONArray entries = view.optJSONArray("addresses");
        JSONObject entry = entries == null ? null : entries.optJSONObject(index);
        return entry == null ? new JSONObject() : entry;
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
                        revealed.add("displayName");
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                () -> {
                    model.remove("displayName");
                    revealed.remove("displayName");
                });
    }

    /** Edits one structured-name component in place, leaving the others
     *  (kept under the model's {@code name} object) untouched. */
    private void namePartDialog(String part, int hint) {
        LinearLayout content = dialogContent();
        EditText field =
                dialogField(content, "name." + part, hint, namePart(part), TEXT_NAME);

        showDialog(
                getS(hint),
                content,
                () -> {
                    try {
                        JSONObject name = model.optJSONObject("name");
                        if (name == null) {
                            name = new JSONObject();
                        }
                        name.put(part, text(field));
                        model.put("name", name);
                        revealed.add("name." + part);
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                () -> {
                    JSONObject name = model.optJSONObject("name");
                    if (name != null) {
                        name.remove(part);
                    }
                    revealed.remove("name." + part);
                });
    }

    /** A single organization component (company or department), kept
     *  under the model's {@code organization} object; empty when unset. */
    private String organizationPart(String part) {
        JSONObject organization = model.optJSONObject("organization");
        return organization == null ? "" : organization.optString(part).trim();
    }

    /** Edits one organization component in place, leaving the other
     *  (kept under the model's {@code organization} object) untouched. */
    private void organizationPartDialog(String part, int hint) {
        LinearLayout content = dialogContent();
        EditText field =
                dialogField(content, "organization." + part, hint, organizationPart(part),
                        TEXT_NAME);

        showDialog(
                getS(hint),
                content,
                () -> {
                    try {
                        JSONObject organization = model.optJSONObject("organization");
                        if (organization == null) {
                            organization = new JSONObject();
                        }
                        organization.put(part, text(field));
                        model.put("organization", organization);
                        revealed.add("organization." + part);
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                () -> {
                    JSONObject organization = model.optJSONObject("organization");
                    if (organization != null) {
                        organization.remove(part);
                    }
                    revealed.remove("organization." + part);
                });
    }

    /** Edits a top-level scalar field (title, role) in place. */
    private void simpleFieldDialog(String key, int hint) {
        LinearLayout content = dialogContent();
        EditText field = dialogField(content, key, hint, model.optString(key), TEXT_NAME);

        showDialog(
                getS(hint),
                content,
                () -> {
                    try {
                        model.put(key, text(field));
                        revealed.add(key);
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                () -> {
                    model.remove(key);
                    revealed.remove(key);
                });
    }

    private void phoneDialog(int index) {
        JSONArray phones = array("phones");
        JSONObject entry = index >= 0 ? phones.optJSONObject(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.phone_types);
        type.setSelection(entry == null ? 0 : typeAt("phones", index));
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
                                client.formEntry(
                                        "phone",
                                        type.getSelectedItemPosition(),
                                        text(number),
                                        entry != null && entry.optBoolean("pref"))),
                index >= 0 ? () -> phones.remove(index) : null);
    }

    private void emailDialog(int index) {
        JSONArray emails = array("emails");
        JSONObject entry = index >= 0 ? emails.optJSONObject(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.email_types);
        type.setSelection(entry == null ? 0 : typeAt("emails", index));
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
                                client.formEntry(
                                        "email",
                                        type.getSelectedItemPosition(),
                                        text(address),
                                        entry != null && entry.optBoolean("pref"))),
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
                        entry == null ? 0 : addressAt(index).optInt("index"));
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
                                                client.formEntry(
                                                                "address",
                                                                type.getCheckedRadioButtonId() - 1,
                                                                "",
                                                                false)
                                                        .optJSONArray("types"));
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
        type.setSelection(entry == null ? 0 : typeAt("relations", index));
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
                                client.formEntry(
                                        "relation",
                                        type.getSelectedItemPosition(),
                                        text(value),
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
    private void genderDialog() {
        String[] labels = activity.getResources().getStringArray(R.array.gender_types);
        // The suggestions drop the leading "Not set" placeholder.
        String[] choices = Arrays.copyOfRange(labels, 1, labels.length);
        boolean adding = !filled("gender");

        LinearLayout content = dialogContent();
        AutoCompleteTextView field =
                autoCompleteField(content, R.string.item_gender, genderPrefill(labels), choices,
                        adding);

        showDialog(
                getS(R.string.item_gender),
                content,
                () -> {
                    String value = text(field);
                    try {
                        if (value.isEmpty()) {
                            model.remove("gender");
                        } else {
                            int position = indexOfIgnoreCase(labels, value);
                            model.put(
                                    "gender",
                                    position > 0
                                            ? client.formEntry("gender", position, "", false)
                                            : new JSONObject().put("sex", "").put("identity", value));
                            revealed.add("gender");
                        }
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                },
                () -> {
                    model.remove("gender");
                    revealed.remove("gender");
                });
    }

    /** The gender field's current display value: the free identity when
     *  set, otherwise the label of its sex code, else empty. */
    private String genderPrefill(String[] labels) {
        JSONObject genderView = view.optJSONObject("gender");
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
    private void pickDate(String field) {
        Calendar calendar = Calendar.getInstance();
        JSONObject stored = view.optJSONObject(field);
        if (stored != null) {
            // Today stands in when the stored value is not a date.
            calendar.set(
                    stored.optInt("year"),
                    stored.optInt("month") - 1,
                    stored.optInt("day"));
        }

        DatePickerDialog dialog =
                new DatePickerDialog(
                        activity,
                        (picker, year, month, day) -> {
                            try {
                                model.put(field, client.formDate(year, month + 1, day));
                                revealed.add(field);
                            } catch (JSONException error) {
                                throw new IllegalStateException(error);
                            }
                            markResolvedAndRender();
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH));
        dialog.setButton(
                DatePickerDialog.BUTTON_NEUTRAL,
                activity.getString(R.string.clear),
                (d, which) -> {
                    model.remove(field);
                    revealed.remove(field);
                    markResolvedAndRender();
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
                                    markResolvedAndRender();
                                })
                        .setNegativeButton(android.R.string.cancel, null);
        if (onRemove != null) {
            builder.setNeutralButton(
                    R.string.remove_row,
                    (dialog, which) -> {
                        onRemove.run();
                        markResolvedAndRender();
                    });
        }

        AlertDialog dialog = builder.create();

        // Focus the first field so it is ready. A plain field opens the
        // keyboard with the dialog; an autocomplete does not, so its
        // choices drop into the space the keyboard would cover instead of
        // landing behind it.
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

    /**
     * A free-text field backed by a dropdown of choices (the same
     * completer the advanced editor used for property names): the value
     * is editable to anything, the choices only suggest. When {@code
     * open} the dropdown pops as the dialog opens (a fresh field), else
     * it waits for a tap (editing an existing value).
     */
    private AutoCompleteTextView autoCompleteField(
            LinearLayout content, int hint, String prefill, String[] choices, boolean open) {
        AutoCompleteTextView field = new AutoCompleteTextView(activity);
        field.setBackgroundTintList(ColorStateList.valueOf(accentColor));
        field.setHint(hint);
        field.setText(prefill);
        field.setThreshold(1);
        field.setAdapter(new ArrayAdapter<>(activity, R.layout.dropdown_item, choices));
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
