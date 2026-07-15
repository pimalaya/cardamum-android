package org.pimalaya.cardamum;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Cards;

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
 * mode, the exact same page as a plain edit (every field shows, fields
 * can be added): the difference is that each disagreeing row wears the
 * diverged glyph until reviewed, the dialogs of conflicted single
 * fields carry one tappable chip per candidate value, and the host
 * blocks saving until every glyph cleared.
 */
final class ContactForm {
    static final int TEXT_NAME =
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

    final Activity activity;
    final int accentColor;
    private final int labelColor;
    private final int surfaceColor;
    private final ScrollView scroll;
    private final LinearLayout container;

    /** The working field model the dialogs edit in place. */
    JSONObject model = new JSONObject();

    /** The bridge-computed view support of the current model. */
    JSONObject view = new JSONObject();

    /** Conflict alternatives by single-field path; null outside conflicts. */
    JSONObject alternatives;

    /** Changed list sections of a conflict; null outside conflict mode. */
    private Set<String> changedLists;

    /** Field kinds the user revealed this session through the add-field
     *  dialog, so their empty section keeps showing. Cleared on load. */
    final Set<String> revealed = new HashSet<>();

    /** Conflict rows the user has reviewed (opened, then confirmed with
     *  OK), so their divergence warning clears. Cleared on load. */
    private final Set<String> resolved = new HashSet<>();

    /** Conflict rows still awaiting review, rebuilt on every render. */
    private final Set<String> pending = new HashSet<>();

    /** Items each changed list held at load time: rows past that count
     *  were added this session and never wear the diverged glyph. */
    private final Map<String, Integer> loadedCounts = new HashMap<>();

    /** The row a just-opened dialog resolves on OK; null when none. */
    private String pendingResolveKey;

    /** Notified after every render so the host can re-gate its save. */
    private Runnable onRender;

    private final Ui ui;

    /** The per-property edit dialogs the tappable rows open. */
    private final ContactFieldDialogs dialogs;

    ContactForm(Activity activity) {
        this.activity = activity;
        this.ui = new Ui(activity);
        this.dialogs = new ContactFieldDialogs(this);
        this.accentColor = resolveColor(android.R.attr.colorAccent);
        this.labelColor = resolveColor(android.R.attr.textColorSecondary);
        this.surfaceColor = activity.getColor(R.color.surface);
        this.scroll = activity.findViewById(R.id.contact_scroll);
        this.container = activity.findViewById(R.id.contact_form);
    }

    /**
     * Loads the field model (null for a new contact) and renders the
     * page. A non-null `changed` turns on conflict mode: the same full
     * page, with the diverged glyph on every disagreeing row and
     * `alternatives` feeding the value chips of the conflicted
     * single-field dialogs.
     */
    void load(JSONObject model, JSONObject alternatives, JSONArray changed) {
        this.model = model != null ? model : new JSONObject();
        this.alternatives = alternatives;
        this.changedLists = null;
        this.revealed.clear();
        this.resolved.clear();
        this.loadedCounts.clear();
        this.pendingResolveKey = null;
        if (changed != null) {
            changedLists = new HashSet<>();
            for (int index = 0; index < changed.length(); index++) {
                String section = changed.optString(index);
                changedLists.add(section);
                loadedCounts.put(section, array(section).length());
            }
        } else if (this.model.length() == 0) {
            // NOTE: a new contact opens on the common fields, not a blank
            // page: given/family name plus an empty phone and email row.
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

    private boolean conflict() {
        return changedLists != null;
    }

    /**
     * Whether a field or list section shows: one that carries a value
     * or was added through a chooser this session, in conflict mode
     * like in a plain edit. So the editor opens showing only the
     * sections that hold data; the {@code || conflicted / changed}
     * branch at each render site adds the rows that diverge while
     * empty on the merged side.
     */
    private boolean shown(String key, boolean filled) {
        return filled || revealed.contains(key);
    }

    /** Whether the field or list section currently carries a value. */
    boolean filled(String key) {
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
    String namePart(String part) {
        JSONObject name = model.optJSONObject("name");
        return name == null ? "" : name.optString(part).trim();
    }

    /** Appends a structured-name component row when it carries a value,
     *  was revealed, or (in conflict mode) disagrees. */
    private void namePartRow(List<View> identity, String part, int hint) {
        String key = "name." + part;
        if (shown(key, filled(key)) || unreviewed(key)) {
            identity.add(
                    entryItem(
                            getS(hint),
                            value(namePart(part)),
                            divergedField(key),
                            () -> dialogs.namePartDialog(part, hint)));
        }
    }

    /** The full field catalog with each field's category, chooser label
     *  and icon, and the action that opens its edit dialog. */
    private List<Field> catalog() {
        int person = R.drawable.ic_section_person;
        int identity = R.string.section_identity;
        List<Field> fields = new ArrayList<>();

        fields.add(new Field("displayName", identity, R.string.hint_display_name,
                R.drawable.ic_display_name, false, dialogs::displayNameDialog));
        fields.add(new Field("name.prefix", identity, R.string.hint_prefix,
                R.drawable.ic_prefix, false,
                () -> dialogs.namePartDialog("prefix", R.string.hint_prefix)));
        fields.add(new Field("name.given", identity, R.string.hint_given, person, false,
                () -> dialogs.namePartDialog("given", R.string.hint_given)));
        fields.add(new Field("name.middle", identity, R.string.hint_middle, person, false,
                () -> dialogs.namePartDialog("middle", R.string.hint_middle)));
        fields.add(new Field("name.family", identity, R.string.hint_family, person, false,
                () -> dialogs.namePartDialog("family", R.string.hint_family)));
        fields.add(new Field("name.suffix", identity, R.string.hint_suffix,
                R.drawable.ic_suffix, false,
                () -> dialogs.namePartDialog("suffix", R.string.hint_suffix)));
        fields.add(new Field("gender", identity, R.string.item_gender,
                R.drawable.ic_gender, false, dialogs::genderDialog));
        fields.add(new Field("birthday", identity, R.string.hint_birthday,
                R.drawable.ic_birthday, false, () -> dialogs.pickDate("birthday")));
        fields.add(new Field("anniversary", identity, R.string.item_anniversary,
                R.drawable.ic_anniversary, false, () -> dialogs.pickDate("anniversary")));

        int work = R.string.tab_work;
        fields.add(new Field("organization.company", work, R.string.hint_company,
                R.drawable.ic_company, false,
                () -> dialogs.organizationPartDialog("company", R.string.hint_company)));
        fields.add(new Field("organization.department", work, R.string.hint_department,
                R.drawable.ic_section_work, false,
                () -> dialogs.organizationPartDialog("department", R.string.hint_department)));
        fields.add(new Field("title", work, R.string.hint_job_title,
                R.drawable.ic_title, false,
                () -> dialogs.simpleFieldDialog("title", R.string.hint_job_title)));
        fields.add(new Field("role", work, R.string.hint_role,
                R.drawable.ic_role, false,
                () -> dialogs.simpleFieldDialog("role", R.string.hint_role)));

        fields.add(new Field("nicknames", R.string.section_nicknames, R.string.item_nickname,
                R.drawable.ic_section_label, true,
                () -> dialogs.stringDialog("nicknames", -1, R.string.item_nickname, 0, TEXT_NAME)));
        fields.add(new Field("relations", R.string.section_relations, R.string.item_relation,
                R.drawable.ic_section_group, true, () -> dialogs.relationDialog(-1)));
        fields.add(new Field("phones", R.string.section_phones, R.string.item_phone,
                R.drawable.ic_section_call, true, () -> dialogs.phoneDialog(-1)));
        fields.add(new Field("emails", R.string.section_emails, R.string.item_email,
                R.drawable.ic_section_mail, true, () -> dialogs.emailDialog(-1)));
        fields.add(new Field("impps", R.string.section_impps, R.string.item_impp,
                R.drawable.ic_section_chat, true,
                () -> dialogs.stringDialog("impps", -1, R.string.item_impp, R.string.hint_impp,
                        URI_INPUT)));
        fields.add(new Field("addresses", R.string.section_addresses, R.string.item_address,
                R.drawable.ic_section_location_on, true, () -> dialogs.addressDialog(-1)));
        fields.add(new Field("websites", R.string.section_websites, R.string.item_website,
                R.drawable.ic_section_language, true,
                () -> dialogs.stringDialog("websites", -1, R.string.item_website, R.string.hint_url,
                        URI_INPUT)));
        fields.add(new Field("languages", R.string.section_languages, R.string.item_language,
                R.drawable.ic_section_translate, true,
                () -> dialogs.stringDialog("languages", -1, R.string.item_language,
                        R.string.hint_language, InputType.TYPE_CLASS_TEXT)));
        fields.add(new Field("notes", R.string.section_notes, R.string.item_note,
                R.drawable.ic_section_notes, true, () -> dialogs.noteDialog(-1)));

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

        // NOTE: locale-aware sort by localized label, so a field is found
        // by name rather than by the catalog's layout order.
        java.text.Collator collator = java.text.Collator.getInstance();
        fields.sort((left, right) -> collator.compare(getS(left.label), getS(right.label)));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        // NOTE: bottom padding mirrors the framework title's top padding
        // so the dialog is symmetric and the last row clears the corner.
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
        view = Cards.formView(model);
        container.removeAllViews();
        pending.clear();

        // NOTE: identity holds the solo fields (display name, name parts,
        // dates, gender); every multi-valued list is its own section below.
        List<View> identity = new ArrayList<>();
        if (shown("displayName", filled("displayName")) || unreviewed("displayName")) {
            identity.add(
                    entryItem(
                            getS(R.string.hint_display_name),
                            value(model.optString("displayName")),
                            divergedField("displayName"),
                            dialogs::displayNameDialog));
        }
        namePartRow(identity, "prefix", R.string.hint_prefix);
        namePartRow(identity, "given", R.string.hint_given);
        namePartRow(identity, "middle", R.string.hint_middle);
        namePartRow(identity, "family", R.string.hint_family);
        namePartRow(identity, "suffix", R.string.hint_suffix);
        if (shown("birthday", filled("birthday")) || unreviewed("birthday")) {
            identity.add(
                    entryItem(
                            getS(R.string.hint_birthday),
                            value(model.optString("birthday")),
                            divergedField("birthday"),
                            () -> dialogs.pickDate("birthday")));
        }
        if (shown("anniversary", filled("anniversary")) || unreviewed("anniversary")) {
            identity.add(
                    entryItem(
                            getS(R.string.item_anniversary),
                            value(model.optString("anniversary")),
                            divergedField("anniversary"),
                            () -> dialogs.pickDate("anniversary")));
        }
        if (shown("gender", filled("gender")) || unreviewed("gender", "gender.sex", "gender.identity")) {
            identity.add(
                    entryItem(
                            getS(R.string.item_gender),
                            genderSummary(),
                            divergedField("gender", "gender.sex", "gender.identity"),
                            dialogs::genderDialog));
        }
        section(R.string.section_identity, R.drawable.ic_section_person, identity);

        if (shown("nicknames", filled("nicknames")) || unreviewedList("nicknames")) {
            List<View> items = new ArrayList<>();
            JSONArray nicknames = array("nicknames");
            for (int index = 0; index < nicknames.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                getS(R.string.item_nickname),
                                nicknames.optString(index),
                                divergedItem("nicknames", at),
                                () -> dialogs.stringDialog("nicknames", at, R.string.item_nickname,
                                        0, TEXT_NAME)));
            }
            section(R.string.section_nicknames, R.drawable.ic_section_label, items);
        }

        // NOTE: work fields are solo rows too, so each edits and (in
        // conflict mode) resolves on its own, like the identity section.
        List<View> work = new ArrayList<>();
        if (shown("organization.company", filled("organization.company"))
                || unreviewed("organization.company")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_company),
                            value(organizationPart("company")),
                            divergedField("organization.company"),
                            () -> dialogs.organizationPartDialog("company",
                                    R.string.hint_company)));
        }
        if (shown("organization.department", filled("organization.department"))
                || unreviewed("organization.department")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_department),
                            value(organizationPart("department")),
                            divergedField("organization.department"),
                            () -> dialogs.organizationPartDialog("department",
                                    R.string.hint_department)));
        }
        if (shown("title", filled("title")) || unreviewed("title")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_job_title),
                            value(model.optString("title")),
                            divergedField("title"),
                            () -> dialogs.simpleFieldDialog("title", R.string.hint_job_title)));
        }
        if (shown("role", filled("role")) || unreviewed("role")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_role),
                            value(model.optString("role")),
                            divergedField("role"),
                            () -> dialogs.simpleFieldDialog("role", R.string.hint_role)));
        }
        section(R.string.tab_work, R.drawable.ic_section_work, work);

        if (shown("relations", filled("relations")) || unreviewedList("relations")) {
            List<View> items = new ArrayList<>();
            JSONArray relations = array("relations");
            for (int index = 0; index < relations.length(); index++) {
                int at = index;
                JSONObject entry = relations.optJSONObject(index);
                items.add(
                        entryItem(
                                typeLabel(R.array.relation_types, typeAt("relations", index)),
                                entry.optString("value"),
                                divergedItem("relations", at),
                                () -> dialogs.relationDialog(at)));
            }
            section(R.string.section_relations, R.drawable.ic_section_group, items);
        }

        if (shown("phones", filled("phones")) || unreviewedList("phones")) {
            List<View> items = new ArrayList<>();
            JSONArray phones = array("phones");
            for (int index = 0; index < phones.length(); index++) {
                int at = index;
                JSONObject entry = phones.optJSONObject(index);
                items.add(
                        entryItem(
                                typeLabel(R.array.phone_types, typeAt("phones", index)),
                                entry.optString("number"),
                                divergedItem("phones", at),
                                () -> dialogs.phoneDialog(at)));
            }
            // NOTE: keepEmpty renders the section as a bare header (with its
            // add control) for a fresh contact, no blank placeholder row.
            section(R.string.section_phones, R.drawable.ic_section_call, items, true);
        }

        if (shown("emails", filled("emails")) || unreviewedList("emails")) {
            List<View> items = new ArrayList<>();
            JSONArray emails = array("emails");
            for (int index = 0; index < emails.length(); index++) {
                int at = index;
                JSONObject entry = emails.optJSONObject(index);
                items.add(
                        entryItem(
                                typeLabel(R.array.email_types, typeAt("emails", index)),
                                entry.optString("address"),
                                divergedItem("emails", at),
                                () -> dialogs.emailDialog(at)));
            }
            section(R.string.section_emails, R.drawable.ic_section_mail, items, true);
        }

        if (shown("impps", filled("impps")) || unreviewedList("impps")) {
            List<View> items = new ArrayList<>();
            JSONArray impps = array("impps");
            for (int index = 0; index < impps.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                getS(R.string.item_impp),
                                impps.optString(index),
                                divergedItem("impps", at),
                                () -> dialogs.stringDialog("impps", at, R.string.item_impp,
                                        R.string.hint_impp,
                                        InputType.TYPE_CLASS_TEXT
                                                | InputType.TYPE_TEXT_VARIATION_URI)));
            }
            section(R.string.section_impps, R.drawable.ic_section_chat, items);
        }

        if (shown("addresses", filled("addresses")) || unreviewedList("addresses")) {
            List<View> items = new ArrayList<>();
            JSONArray entries = array("addresses");
            for (int index = 0; index < entries.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                typeLabel(
                                        R.array.address_types,
                                        addressAt(index).optInt("index")),
                                value(addressAt(index).optString("summary")),
                                divergedItem("addresses", at),
                                () -> dialogs.addressDialog(at)));
            }
            section(R.string.section_addresses, R.drawable.ic_section_location_on, items);
        }

        if (shown("websites", filled("websites")) || unreviewedList("websites")) {
            List<View> items = new ArrayList<>();
            JSONArray websites = array("websites");
            for (int index = 0; index < websites.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                getS(R.string.item_website),
                                websites.optString(index),
                                divergedItem("websites", at),
                                () -> dialogs.stringDialog("websites", at, R.string.item_website,
                                        R.string.hint_url,
                                        InputType.TYPE_CLASS_TEXT
                                                | InputType.TYPE_TEXT_VARIATION_URI)));
            }
            section(R.string.section_websites, R.drawable.ic_section_language, items);
        }

        if (shown("languages", filled("languages")) || unreviewedList("languages")) {
            List<View> items = new ArrayList<>();
            JSONArray languages = array("languages");
            for (int index = 0; index < languages.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                getS(R.string.item_language),
                                languages.optString(index),
                                divergedItem("languages", at),
                                () -> dialogs.stringDialog("languages", at, R.string.item_language,
                                        R.string.hint_language, InputType.TYPE_CLASS_TEXT)));
            }
            section(R.string.section_languages, R.drawable.ic_section_translate, items);
        }

        if (shown("notes", filled("notes")) || unreviewedList("notes")) {
            List<View> items = new ArrayList<>();
            JSONArray notes = array("notes");
            for (int index = 0; index < notes.length(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                getS(R.string.item_note),
                                notes.optString(index),
                                divergedItem("notes", at),
                                () -> dialogs.noteDialog(at)));
            }
            section(R.string.section_notes, R.drawable.ic_section_notes, items);
        }

        if (onRender != null) {
            onRender.run();
        }
    }

    /** Marks the open conflict row reviewed (its warning clears), then
     *  re-renders. Cancelling a dialog skips this, so the warning stays. */
    void markResolvedAndRender() {
        if (pendingResolveKey != null) {
            resolved.add(pendingResolveKey);
            pendingResolveKey = null;
        }
        render();
    }

    /** The review key of a diverged single field, null when settled:
     *  the row key itself, checked against the alternative paths (a
     *  compound field like gender checks each of its components). */
    private String divergedField(String key, String... paths) {
        return conflicted(paths.length == 0 ? new String[] {key} : paths) ? key : null;
    }

    /** The review key of one row of a changed list section, null when
     *  the section is settled or the row was added this session. */
    private String divergedItem(String section, int index) {
        if (!conflict() || !changedLists.contains(section)) {
            return null;
        }
        Integer loaded = loadedCounts.get(section);
        return loaded != null && index < loaded ? section + ":" + index : null;
    }

    /** Whether a diverged single field still awaits review: it renders
     *  even while empty so the divergence can be seen; once reviewed it
     *  follows the plain rules again, so clearing its value drops the
     *  row exactly like in a plain edit. */
    private boolean unreviewed(String key, String... paths) {
        return divergedField(key, paths) != null && !resolved.contains(key);
    }

    /** Whether a changed list section still awaits review on any of
     *  its loaded rows, so it renders even once emptied; all reviewed,
     *  it follows the plain rules again. */
    private boolean unreviewedList(String section) {
        if (!conflict() || !changedLists.contains(section)) {
            return false;
        }
        for (int index = 0; index < loadedCounts.getOrDefault(section, 0); index++) {
            if (!resolved.contains(section + ":" + index)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a section: its icon and accent label, then a right-aligned add
     * icon opening the chooser filtered to this section (so its header
     * only offers its own fields), then its items, separated from the
     * previous section by a line in the app bar tone. An empty section
     * vanishes.
     */
    private void section(int title, int icon, List<View> items) {
        section(title, icon, items, false);
    }

    private void section(int title, int icon, List<View> items, boolean keepEmpty) {
        if (items.isEmpty() && !keepEmpty) {
            return;
        }

        // NOTE: the line sits flush; the previous section's last item (or
        // an item-less header, below) already pads 12dp, so a margin here
        // would double the gap.
        if (container.getChildCount() > 0) {
            View line = new View(activity);
            line.setBackgroundColor(surfaceColor);
            container.addView(
                    line,
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }

        ImageView iconView = new ImageView(activity);
        iconView.setImageResource(icon);
        iconView.setImageTintList(ColorStateList.valueOf(accentColor));

        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextColor(accentColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(dp(8));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(
                dp(16),
                container.getChildCount() <= 1 ? dp(16) : dp(10),
                dp(10),
                items.isEmpty() ? dp(12) : dp(0));
        header.addView(iconView, new LinearLayout.LayoutParams(dp(18), dp(18)));
        header.addView(label, labelParams);
        header.addView(addIcon(R.string.contact_add_field, () -> addToSection(title)));
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
     * A tappable row: title over a diminished subtitle. A non-null
     * `diverged` key marks the row as disagreeing in a conflict: it
     * carries a trailing warning (the same glyph the list shows) until
     * the user opens it and confirms with OK. Single fields key by
     * their path, list rows by section and loaded position (stable
     * while rows only get reviewed; a row added this session never
     * wears the glyph).
     */
    private View entryItem(
            CharSequence title, CharSequence subtitle, String diverged, Runnable onClick) {
        String key = diverged;

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);

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
            // NOTE: 24dp (not the list's 32dp) so the glyph centres on the
            // section headers' add-icon column instead of reading off-axis.
            warn.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            resolveColor(android.R.attr.colorError)));
            LinearLayout.LayoutParams warnParams =
                    new LinearLayout.LayoutParams(dp(24), dp(24));
            warnParams.setMarginStart(dp(12));
            row.addView(warn, warnParams);
        }

        return row;
    }

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

    /** The type spinner position of a list entry, bridge-computed. */
    int typeAt(String list, int index) {
        JSONArray positions = view.optJSONArray(list);
        return positions == null ? 0 : positions.optInt(index);
    }

    /** An address entry's view support ({@code {index, summary}}). */
    JSONObject addressAt(int index) {
        JSONArray entries = view.optJSONArray("addresses");
        JSONObject entry = entries == null ? null : entries.optJSONObject(index);
        return entry == null ? new JSONObject() : entry;
    }

    /** A single organization component (company or department), kept
     *  under the model's {@code organization} object; empty when unset. */
    String organizationPart(String part) {
        JSONObject organization = model.optJSONObject("organization");
        return organization == null ? "" : organization.optString(part).trim();
    }

    /** The model's list under the key, created empty when absent. */
    JSONArray array(String key) {
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
    static void putEntry(JSONArray values, int index, String filled, JSONObject entry) {
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

    static String text(EditText input) {
        return input.getText().toString().trim();
    }

    String getS(int id) {
        return activity.getString(id);
    }

    int resolveAttr(int attr) {
        return ui.resolveAttr(attr);
    }

    int resolveColor(int attr) {
        return ui.resolveColor(attr);
    }

    int dp(int value) {
        return ui.dp(value);
    }
}
