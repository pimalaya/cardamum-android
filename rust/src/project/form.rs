//! The edit form's arrays.xml-coupled type tables and the view and
//! summary helpers that project the field model onto the form.

use serde_json::{Map, Value, json};

use crate::project::{array, field, single_field, strings, text};

/// Spinner position to vCard TYPE set of the phone rows, aligned with
/// the Android phone_types string array.
const PHONE_TYPES: [&[&str]; 8] = [
    &["cell"],
    &["home"],
    &["work"],
    &["cell", "work"],
    &["fax", "home"],
    &["fax", "work"],
    &["pager"],
    &[],
];

/// Spinner position to vCard TYPE set of the email and address rows,
/// aligned with the Android email_types and address_types arrays.
const HOME_WORK_OTHER: [&[&str]; 3] = [&["home"], &["work"], &[]];

/// Spinner position to RELATED type set, aligned with the Android
/// relation_types array.
const RELATION_TYPES: [&[&str]; 8] = [
    &["spouse"],
    &["child"],
    &["parent"],
    &["sibling"],
    &["friend"],
    &["colleague"],
    &["emergency"],
    &[],
];

/// Spinner position to GENDER sex code (RFC 6350 §6.2.7), aligned with
/// the Android gender_types array; position 0 means unset.
const GENDER_SEXES: [&str; 6] = ["", "M", "F", "O", "N", "U"];

/// The edit form's view support, computed from the field model in one
/// pass: the composed name and organization summaries, the gender
/// spinner position and identity, the dates parsed for the pickers,
/// the type spinner position of every typed list entry, and the
/// address row summaries. The Java form only builds views from it and
/// localizes the labels the positions address.
pub fn form_view(model: &Value) -> Value {
    let mut view = Map::new();
    view.insert("name".into(), text(name_summary(model)));
    view.insert("organization".into(), text(organization_summary(model)));

    if let Some(gender) = model.get("gender").and_then(Value::as_object) {
        let sex = gender.get("sex").and_then(Value::as_str).unwrap_or("");
        let identity = gender.get("identity").and_then(Value::as_str).unwrap_or("");
        view.insert(
            "gender".into(),
            json!({ "sexIndex": gender_sex_index(sex), "identity": identity.trim() }),
        );
    }

    for key in ["birthday", "anniversary"] {
        if let Some(date) = date_parts(field(model, key)) {
            view.insert(key.into(), date);
        }
    }

    let indexes = |list: &str, index: fn(&Value) -> usize| -> Value {
        let positions = array(model.get(list))
            .iter()
            .map(|entry| index(entry).into())
            .collect();
        Value::Array(positions)
    };
    view.insert("phones".into(), indexes("phones", phone_type_index));
    view.insert(
        "emails".into(),
        indexes("emails", |entry| type_index(entry, &HOME_WORK_OTHER)),
    );
    view.insert(
        "relations".into(),
        indexes("relations", |entry| type_index(entry, &RELATION_TYPES)),
    );

    let addresses = array(model.get("addresses"))
        .iter()
        .map(|entry| {
            json!({
                "index": type_index(entry, &HOME_WORK_OTHER),
                "summary": address_summary(entry),
            })
        })
        .collect();
    view.insert("addresses".into(), Value::Array(addresses));

    Value::Object(view)
}

/// One typed entry saved from a dialog, its TYPE set drawn from the
/// spinner position: `phone`, `email` and `relation` return the full
/// entry (the value under its field name, the pref flag riding along),
/// `address` returns the TYPE set alone (the dialog owns the other
/// components), and `gender` returns the GENDER object, empty when the
/// sex position and the identity are both unset.
pub fn form_entry(kind: &str, index: i64, value: &str, pref: bool) -> Result<Value, String> {
    let position = index.max(0) as usize;

    let (field, table): (&str, &[&[&str]]) = match kind {
        "phone" => ("number", &PHONE_TYPES),
        "email" => ("address", &HOME_WORK_OTHER),
        "relation" => ("value", &RELATION_TYPES),
        "address" => {
            return Ok(json!({ "types": table_types(&HOME_WORK_OTHER, position) }));
        }
        "gender" => {
            let sex = GENDER_SEXES[position.min(GENDER_SEXES.len() - 1)];
            let identity = value.trim();
            if sex.is_empty() && identity.is_empty() {
                return Ok(json!({}));
            }
            return Ok(json!({ "sex": sex, "identity": identity }));
        }
        kind => return Err(format!("Unknown form entry kind `{kind}`")),
    };

    let mut entry = Map::new();
    entry.insert(field.into(), text(value));
    entry.insert("types".into(), table_types(table, position));
    entry.insert("pref".into(), Value::Bool(pref));
    Ok(Value::Object(entry))
}

/// The ordered type vocabularies the edit-form spinners address, by
/// kind: each spinner position's vCard TYPE set (the sex code alone
/// for `gender`), in the exact order the Android string-arrays must
/// mirror. The ordering has a single owner here; a cross-language
/// test pins the `arrays.xml` labels to it, so a reorder or resize on
/// either side fails loudly instead of silently mismapping types.
pub fn card_type_order(kind: &str) -> Vec<Vec<&'static str>> {
    let table: &[&[&str]] = match kind {
        "phone" => &PHONE_TYPES,
        "email" | "address" => &HOME_WORK_OTHER,
        "relation" => &RELATION_TYPES,
        "gender" => return GENDER_SEXES.iter().map(|code| vec![*code]).collect(),
        _ => &[],
    };
    table.iter().map(|set| set.to_vec()).collect()
}

/// One picked date on the model wire (the vCard `yyyy-mm-dd` form the
/// BDAY and ANNIVERSARY patches expect; the month is 1-based).
pub fn form_date(year: i64, month: i64, day: i64) -> String {
    format!("{year:04}-{month:02}-{day:02}")
}

/// The name parts composed for the form's Name row (the display name
/// has its own row).
pub(crate) fn name_summary(model: &Value) -> String {
    let mut composed = Vec::new();
    if let Some(name) = model.get("name").and_then(Value::as_object) {
        for key in ["prefix", "given", "middle", "family", "suffix"] {
            let part = name.get(key).and_then(Value::as_str).unwrap_or("").trim();
            if !part.is_empty() {
                composed.push(part);
            }
        }
    }
    composed.join(" ")
}

/// The organization summary: the company and the job title, joined by
/// a middot.
fn organization_summary(model: &Value) -> String {
    let mut parts = Vec::new();
    for key in ["organization.company", "title"] {
        let value = single_field(model, key);
        if !value.is_empty() {
            parts.push(value);
        }
    }
    parts.join(" · ")
}

/// The address row summary: the first non-empty component in display
/// priority, cut at its first line (the street stands for the block).
fn address_summary(entry: &Value) -> String {
    for key in ["street", "city", "postcode", "country"] {
        let part = field(entry, key);
        if !part.is_empty() {
            return part.lines().next().unwrap_or("").to_string();
        }
    }
    String::new()
}

/// The gender_types position of a GENDER sex code, 0 (unset) for
/// anything unknown.
fn gender_sex_index(sex: &str) -> usize {
    let sex = sex.trim();
    GENDER_SEXES
        .iter()
        .skip(1)
        .position(|code| code.eq_ignore_ascii_case(sex))
        .map(|at| at + 1)
        .unwrap_or(0)
}

/// The phone_types position of a TEL entry's TYPE set: the fax and
/// cell pairings first, then the plain types, the untyped "other" row
/// last.
fn phone_type_index(entry: &Value) -> usize {
    let types = strings(entry.get("types"));
    let has = |wanted: &str| types.iter().any(|t| t == wanted);

    if has("fax") {
        return if has("home") { 4 } else { 5 };
    }
    if has("cell") {
        return if has("work") { 3 } else { 0 };
    }
    if has("pager") {
        return 6;
    }
    if has("work") {
        return 2;
    }
    if has("home") {
        return 1;
    }
    7
}

/// The spinner position of an entry's TYPE set against a table: the
/// first row whose lead type the entry carries, the last (untyped)
/// row otherwise.
fn type_index(entry: &Value, table: &[&[&str]]) -> usize {
    let types = strings(entry.get("types"));
    for (index, row) in table.iter().enumerate() {
        if let Some(lead) = row.first()
            && types.iter().any(|t| t == lead)
        {
            return index;
        }
    }
    table.len() - 1
}

/// The TYPE set at a spinner position; anything past the end reads as
/// the last (untyped) row.
fn table_types(table: &[&[&str]], position: usize) -> Value {
    let row = table[position.min(table.len() - 1)];
    Value::Array(row.iter().map(text).collect())
}

/// A `yyyy-mm-dd` model value parsed into picker parts (1-based
/// month), None when absent or not a full numeric date.
fn date_parts(value: &str) -> Option<Value> {
    let parts: Vec<&str> = value.split('-').collect();
    let [year, month, day] = parts[..] else {
        return None;
    };

    let year: i64 = year.parse().ok()?;
    let month: i64 = month.parse().ok()?;
    let day: i64 = day.parse().ok()?;
    Some(json!({ "year": year, "month": month, "day": day }))
}
