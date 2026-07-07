//! vCard to phone field model projection, and back.
//!
//! [`project`] turns a vCard into the neutral field model the Java side
//! maps to ContactsContract rows and edit-form fields; [`apply`] patches
//! an edited model back onto the original vCard through the vcard-rs
//! CST, so every property the model does not manage (PHOTO, CATEGORIES,
//! IMPP, X-*, parameters of unmanaged lines) survives byte for byte.
//! See docs/contacts-mapping.md. First version: well-defined properties
//! only, per the custom property policy; X-* properties are neither
//! read nor written. Types are passed through as a lowercase list; the
//! Android TYPE constants live on the Java side.

use core::str::FromStr;
use std::borrow::Cow;

use serde_json::{Map, Value, json};
use vcard::{
    param::VcardParam,
    prop::{VcardProp, VcardPropKind, VcardPropName},
    tree::{
        cst::VcardCst,
        line::VcardLine,
        merge::merge,
        prop::{
            VcardPropLens, adr::ADR, bday::BDAY, categories::CATEGORIES, email::EMAIL, r#fn::FN,
            n::N, nickname::NICKNAME, note::NOTE, org::ORG, role::ROLE, tel::TEL, title::TITLE,
            uid::UID, url::URL,
        },
    },
    value::{
        VcardValue,
        adr::VcardAdr,
        datetime::VcardDateAndOrTime,
        n::VcardN,
        org::VcardOrg,
        text::{VcardText, VcardTextList},
        uri::VcardUri,
    },
    version::VcardVersion,
};

/// Projects a vCard onto the neutral field model, as JSON.
pub fn project(vcard: &str) -> Result<Value, String> {
    let card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;
    let version = card.version();

    let mut model = Map::new();
    let mut phones = Vec::new();
    let mut emails = Vec::new();
    let mut addresses = Vec::new();
    let mut websites = Vec::new();
    let mut nicknames = Vec::new();
    let mut notes = Vec::new();
    let mut categories = Vec::new();

    for line in &card.props {
        let Ok(kind) = VcardPropKind::from_str(line.name.get()) else {
            continue;
        };

        match kind {
            VcardPropKind::Uid => {
                let uid = UID::decode(line, version);
                first(&mut model, "uid", text(&uid.0));
            }
            VcardPropKind::Fn => {
                let name = FN::decode(line, version);
                first(&mut model, "displayName", text(&name.0));
            }
            VcardPropKind::N => {
                let n = N::decode(line, version);
                first(
                    &mut model,
                    "name",
                    json!({
                        "family": n.family.join(" "),
                        "given": n.given.join(" "),
                        "middle": n.additional.join(" "),
                        "prefix": n.prefixes.join(" "),
                        "suffix": n.suffixes.join(" "),
                    }),
                );
            }
            VcardPropKind::Nickname => {
                let all = NICKNAME::decode(line, version);
                nicknames.extend(all.0.iter().map(text));
            }
            VcardPropKind::Tel => {
                let tel = TEL::decode(line, version);
                let (types, pref) = types(line);
                phones.push(json!({
                    "number": text(&tel.0),
                    "types": types,
                    "pref": pref,
                }));
            }
            VcardPropKind::Email => {
                let email = EMAIL::decode(line, version);
                let (types, pref) = types(line);
                emails.push(json!({
                    "address": text(&email.0),
                    "types": types,
                    "pref": pref,
                }));
            }
            VcardPropKind::Adr => {
                let adr = ADR::decode(line, version);
                let (types, pref) = types(line);
                addresses.push(json!({
                    "pobox": adr.po_box.join(" "),
                    "ext": adr.extended.join(" "),
                    "street": adr.street.join("\n"),
                    "city": adr.locality.join(" "),
                    "region": adr.region.join(" "),
                    "postcode": adr.postal_code.join(" "),
                    "country": adr.country.join(" "),
                    "types": types,
                    "pref": pref,
                }));
            }
            VcardPropKind::Org => {
                let org = ORG::decode(line, version);
                let mut components = org.0.iter().map(|component| component.as_ref());
                let company = components.next().unwrap_or_default();
                let department = components.collect::<Vec<_>>().join(" ");
                first(
                    &mut model,
                    "organization",
                    json!({ "company": company, "department": department }),
                );
            }
            VcardPropKind::Title => {
                let title = TITLE::decode(line, version);
                first(&mut model, "title", text(&title.0));
            }
            VcardPropKind::Role => {
                let role = ROLE::decode(line, version);
                first(&mut model, "role", text(&role.0));
            }
            VcardPropKind::Url => {
                let url = URL::decode(line, version);
                websites.push(text(&url.0));
            }
            VcardPropKind::Bday => {
                if let Some(date) = full_date(&line.raw_value_str()) {
                    first(&mut model, "birthday", Value::String(date));
                }
            }
            VcardPropKind::Note => {
                let note = NOTE::decode(line, version);
                notes.push(text(&note.0));
            }
            // NOTE: projected for the phone's groups, but not managed
            // by apply: the edit form does not expose categories, so
            // the patch must preserve them.
            VcardPropKind::Categories => {
                let all = CATEGORIES::decode(line, version);
                categories.extend(all.0.iter().map(text));
            }
            _ => {}
        }
    }

    model.insert("phones".into(), Value::Array(phones));
    model.insert("emails".into(), Value::Array(emails));
    model.insert("addresses".into(), Value::Array(addresses));
    model.insert("websites".into(), Value::Array(websites));
    model.insert("nicknames".into(), Value::Array(nicknames));
    model.insert("notes".into(), Value::Array(notes));
    model.insert("categories".into(), Value::Array(categories));

    Ok(Value::Object(model))
}

/// Patches the edited field model back onto the vCard. Every managed
/// property is removed and rewritten from the model in canonical form
/// (appended at the end of the card); every other line keeps its exact
/// bytes, so unmanaged data (PHOTO, CATEGORIES, IMPP, X-*) survives.
pub fn apply(vcard: &str, model: &Value) -> Result<String, String> {
    let mut card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;
    let version = card.version();

    // FN is mandatory: the display name, or composed from N's parts.
    card.remove::<FN>();
    card.push(text_prop(VcardPropKind::Fn, vec![], &display_name(model)));

    card.remove::<N>();
    if let Some(name) = model.get("name").and_then(Value::as_object) {
        let component = |key: &str| single_component(name.get(key));
        let n = VcardN {
            family: component("family"),
            given: component("given"),
            additional: component("middle"),
            prefixes: component("prefix"),
            suffixes: component("suffix"),
        };

        let empty = n.family.is_empty()
            && n.given.is_empty()
            && n.additional.is_empty()
            && n.prefixes.is_empty()
            && n.suffixes.is_empty();
        if !empty {
            card.push(VcardProp {
                name: VcardPropName::Kind(VcardPropKind::N),
                params: vec![],
                value: VcardValue::N(n),
            });
        }
    }

    card.remove::<NICKNAME>();
    let nicknames: Vec<Cow<'static, str>> = strings(model.get("nicknames"))
        .into_iter()
        .map(Cow::Owned)
        .collect();
    if !nicknames.is_empty() {
        card.push(VcardProp {
            name: VcardPropName::Kind(VcardPropKind::Nickname),
            params: vec![],
            value: VcardValue::TextList(VcardTextList(nicknames)),
        });
    }

    card.remove::<TEL>();
    for phone in array(model.get("phones")) {
        let number = field(phone, "number");
        if !number.is_empty() {
            card.push(text_prop(
                VcardPropKind::Tel,
                type_params(phone, version),
                number,
            ));
        }
    }

    card.remove::<EMAIL>();
    for email in array(model.get("emails")) {
        let address = field(email, "address");
        if !address.is_empty() {
            card.push(text_prop(
                VcardPropKind::Email,
                type_params(email, version),
                address,
            ));
        }
    }

    card.remove::<ADR>();
    for adr in array(model.get("addresses")) {
        let value = VcardAdr {
            po_box: single_component(adr.get("pobox")),
            extended: single_component(adr.get("ext")),
            street: street_components(adr.get("street")),
            locality: single_component(adr.get("city")),
            region: single_component(adr.get("region")),
            postal_code: single_component(adr.get("postcode")),
            country: single_component(adr.get("country")),
        };

        let empty = value.po_box.is_empty()
            && value.extended.is_empty()
            && value.street.is_empty()
            && value.locality.is_empty()
            && value.region.is_empty()
            && value.postal_code.is_empty()
            && value.country.is_empty();
        if !empty {
            card.push(VcardProp {
                name: VcardPropName::Kind(VcardPropKind::Adr),
                params: type_params(adr, version),
                value: VcardValue::Adr(value),
            });
        }
    }

    card.remove::<ORG>();
    if let Some(org) = model.get("organization").and_then(Value::as_object) {
        let company = org
            .get("company")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim();
        let department = org
            .get("department")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim();

        if !company.is_empty() || !department.is_empty() {
            let mut components: Vec<Cow<'static, str>> = vec![Cow::Owned(company.to_string())];
            if !department.is_empty() {
                components.push(Cow::Owned(department.to_string()));
            }
            card.push(VcardProp {
                name: VcardPropName::Kind(VcardPropKind::Org),
                params: vec![],
                value: VcardValue::Org(VcardOrg(components)),
            });
        }
    }

    card.remove::<TITLE>();
    let title = field(model, "title");
    if !title.is_empty() {
        card.push(text_prop(VcardPropKind::Title, vec![], title));
    }

    card.remove::<ROLE>();
    let role = field(model, "role");
    if !role.is_empty() {
        card.push(text_prop(VcardPropKind::Role, vec![], role));
    }

    card.remove::<BDAY>();
    if let Some(date) = full_date(field(model, "birthday")) {
        card.push(VcardProp {
            name: VcardPropName::Kind(VcardPropKind::Bday),
            params: vec![],
            value: VcardValue::DateAndOrTime(VcardDateAndOrTime(Cow::Owned(date))),
        });
    }

    card.remove::<URL>();
    for site in strings(model.get("websites")) {
        card.push(VcardProp {
            name: VcardPropName::Kind(VcardPropKind::Url),
            params: vec![],
            value: VcardValue::Uri(VcardUri(Cow::Owned(site))),
        });
    }

    card.remove::<NOTE>();
    for note in strings(model.get("notes")) {
        card.push(text_prop(VcardPropKind::Note, vec![], &note));
    }

    Ok(String::from_utf8_lossy(&card.to_bytes()).into_owned())
}

/// The model's display name, composed from the name parts when blank.
fn display_name(model: &Value) -> String {
    let display = field(model, "displayName");
    if !display.is_empty() {
        return display.to_string();
    }

    let mut composed = Vec::new();
    if let Some(name) = model.get("name").and_then(Value::as_object) {
        for key in ["given", "family"] {
            let part = name.get(key).and_then(Value::as_str).unwrap_or("").trim();
            if !part.is_empty() {
                composed.push(part);
            }
        }
    }
    composed.join(" ")
}

/// A canonical text property built from an owned value.
pub(crate) fn text_prop(
    kind: VcardPropKind,
    params: Vec<VcardParam<'static>>,
    value: &str,
) -> VcardProp<'static> {
    VcardProp {
        name: VcardPropName::Kind(kind),
        params,
        value: VcardValue::Text(VcardText(Cow::Owned(value.to_string()))),
    }
}

/// The entry's TYPE parameter (plus the pref flag, encoded as `TYPE=pref`
/// under vCard 3 and as `PREF=1` under vCard 4).
fn type_params(entry: &Value, version: VcardVersion) -> Vec<VcardParam<'static>> {
    let mut types: Vec<Cow<'static, str>> = strings(entry.get("types"))
        .into_iter()
        .map(Cow::Owned)
        .collect();

    let mut params = Vec::new();
    if entry.get("pref").and_then(Value::as_bool).unwrap_or(false) {
        if version == VcardVersion::V4_0 {
            params.push(VcardParam::Pref(Cow::Borrowed("1")));
        } else {
            types.push(Cow::Borrowed("pref"));
        }
    }
    if !types.is_empty() {
        params.push(VcardParam::Type(types));
    }
    params
}

/// A structured-value component holding the trimmed string, or empty.
fn single_component(value: Option<&Value>) -> Vec<Cow<'static, str>> {
    let text = value.and_then(Value::as_str).unwrap_or("").trim();
    if text.is_empty() {
        Vec::new()
    } else {
        vec![Cow::Owned(text.to_string())]
    }
}

/// The street component: one value per line of the form field, matching
/// the newline join `project` uses on the way out.
fn street_components(value: Option<&Value>) -> Vec<Cow<'static, str>> {
    let text = value.and_then(Value::as_str).unwrap_or("");
    text.split('\n')
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(|line| Cow::Owned(line.to_string()))
        .collect()
}

fn array(value: Option<&Value>) -> &[Value] {
    value
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[])
}

fn strings(value: Option<&Value>) -> Vec<String> {
    array(value)
        .iter()
        .filter_map(Value::as_str)
        .map(str::trim)
        .filter(|item| !item.is_empty())
        .map(str::to_string)
        .collect()
}

fn field<'m>(entry: &'m Value, key: &str) -> &'m str {
    entry.get(key).and_then(Value::as_str).unwrap_or("").trim()
}

/// Inserts single-instance fields, first occurrence wins.
fn first(model: &mut Map<String, Value>, key: &str, value: Value) {
    if !model.contains_key(key) {
        model.insert(key.into(), value);
    }
}

fn text(value: impl AsRef<str>) -> Value {
    Value::String(value.as_ref().into())
}

/// Collects every TYPE parameter value, lowercased, folding the vCard 3
/// `TYPE=PREF` marker and the vCard 4 `PREF` parameter into one flag.
fn types(line: &VcardLine) -> (Vec<String>, bool) {
    let mut types = Vec::new();
    let mut pref = false;

    for param in &line.params {
        if param.name.get().eq_ignore_ascii_case("TYPE") {
            for value in &param.values {
                let value = value.get().to_ascii_lowercase();
                if value == "pref" {
                    pref = true;
                } else if !value.is_empty() {
                    types.push(value);
                }
            }
        } else if param.name.get().eq_ignore_ascii_case("PREF") {
            pref = true;
        }
    }

    (types, pref)
}

/// Normalizes a BDAY value to `yyyy-mm-dd`, or None for anything partial
/// (year-less dates have no standard vCard 3 form, so they do not sync).
pub(crate) fn full_date(raw: &str) -> Option<String> {
    let date = raw.trim();
    let digits = |s: &str| s.bytes().all(|b| b.is_ascii_digit());

    let dashed: Vec<&str> = date.split('-').collect();
    if let [y, m, d] = dashed[..] {
        if y.len() == 4 && m.len() == 2 && d.len() == 2 && digits(y) && digits(m) && digits(d) {
            return Some(format!("{y}-{m}-{d}"));
        }
    }

    if date.len() == 8 && digits(date) {
        return Some(format!("{}-{}-{}", &date[..4], &date[4..6], &date[6..]));
    }

    None
}

/// Indexes a vCard for the app's store, computed once at write time:
/// the fields the contacts list renders (FN, first EMAIL, first TEL,
/// UID) and a normalized content hash for the divergence flag of
/// linked replicas. The hash is FNV-1a over the canonical field model
/// with its arrays sorted and the identity and unmanaged data excluded
/// (UID, CATEGORIES, X-*, PHOTO), so linked replicas read as one
/// contact as long as the content the edit form manages agrees;
/// per-replica identity and backend-specific baggage never read as
/// divergence.
pub fn index(vcard: &str) -> Result<Value, String> {
    let model = project(vcard)?;

    let name = single_field(&model, "displayName").to_string();
    let uid = single_field(&model, "uid").to_string();
    let email = first_entry(&model, "emails", "address");
    let phone = first_entry(&model, "phones", "number");

    let mut canon = model;
    if let Some(map) = canon.as_object_mut() {
        map.remove("uid");
        map.remove("categories");
    }
    sort_arrays(&mut canon);
    let hash = fnv1a(canon.to_string().as_bytes());

    Ok(json!({
        "name": name,
        "email": email,
        "phone": phone,
        "uid": uid,
        "hash": format!("{hash:016x}"),
    }))
}

/// Three-way merges a conflicted push: the staged local edit and the
/// fetched remote card, against the base both derive from. The local
/// side wins same-field collisions (it carries the user's explicit
/// edit), every other remote change flows in, and an update always
/// beats a removal (the vcard-rs merge rules), so nothing is silently
/// lost. Returns the merged card and the collision count.
pub fn merge_conflict(base: &str, local: &str, remote: &str) -> Result<Value, String> {
    let base = VcardCst::parse(base).map_err(|err| format!("Invalid vCard: {err}"))?;
    let local = VcardCst::parse(local).map_err(|err| format!("Invalid vCard: {err}"))?;
    let remote = VcardCst::parse(remote).map_err(|err| format!("Invalid vCard: {err}"))?;

    let report = merge(&base, &local, &remote);

    Ok(json!({
        "vcard": report.merged.to_string(),
        "conflicts": report.conflicts.len(),
    }))
}

/// Rewrites the card's UID: a plain copy is a new identity, and
/// CardDAV servers enforce UID uniqueness per collection. Every other
/// byte is preserved.
pub fn set_uid(vcard: &str, uid: &str) -> Result<String, String> {
    let mut card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;

    card.remove::<UID>();
    card.push(text_prop(VcardPropKind::Uid, vec![], uid));

    Ok(String::from_utf8_lossy(&card.to_bytes()).into_owned())
}

/// The model's list sections, compared across merged cards for the
/// conflict view's changed set.
const LIST_FIELDS: &[&str] = &[
    "phones",
    "emails",
    "addresses",
    "websites",
    "nicknames",
    "notes",
];

/// The model's single-valued fields the merge form offers alternative
/// values for, as dot paths.
const SINGLE_FIELDS: &[&str] = &[
    "displayName",
    "name.prefix",
    "name.given",
    "name.middle",
    "name.family",
    "name.suffix",
    "organization.company",
    "organization.department",
    "title",
    "role",
    "birthday",
];

/// Merges several vCards into one union document through the vcard-rs
/// three-way merge against an empty base: identical properties land
/// once, multi-valued properties accumulate, and a divergent
/// single-valued property keeps the first card's value. Returns the
/// union document, its projected field model (the merge form prefill),
/// the per-field alternatives (every distinct value the cards carry
/// for each conflicted single-valued field, the prefilled winner
/// included, so the form shows all the choices) and the changed list
/// sections (the model lists the cards do not agree on, order aside),
/// so a conflict view can show only what disagrees.
///
/// Unlike faithful single-card editing, merging reformats: model list
/// entries duplicating an earlier one collapse, matched by their
/// identifying fields case-insensitively (two cards carrying the same
/// number under different types prefill as one phone).
pub fn merge_cards(cards: &[String]) -> Result<Value, String> {
    let Some(first) = cards.first() else {
        return Err("No cards to merge".into());
    };

    let version = VcardCst::parse(first.as_str())
        .map_err(|err| format!("Invalid vCard: {err}"))?
        .version();
    let empty = format!("BEGIN:VCARD\r\nVERSION:{}\r\nEND:VCARD\r\n", &*version);

    let mut merged = first.clone();
    for card in &cards[1..] {
        merged = {
            let base =
                VcardCst::parse(empty.as_str()).map_err(|err| format!("Invalid vCard: {err}"))?;
            let left =
                VcardCst::parse(merged.as_str()).map_err(|err| format!("Invalid vCard: {err}"))?;
            let right =
                VcardCst::parse(card.as_str()).map_err(|err| format!("Invalid vCard: {err}"))?;
            merge(&base, &left, &right).merged.to_string()
        };
    }

    let mut model = project(&merged)?;
    dedup_lists(&mut model);
    let models = cards
        .iter()
        .map(|card| project(card))
        .collect::<Result<Vec<_>, _>>()?;

    let mut alternatives = Map::new();
    for field in SINGLE_FIELDS {
        let mut values: Vec<&str> = Vec::new();
        for candidate in &models {
            let value = single_field(candidate, field);
            if !value.is_empty() && !values.contains(&value) {
                values.push(value);
            }
        }
        if values.len() > 1 {
            alternatives.insert((*field).into(), json!(values));
        }
    }

    let mut changed: Vec<&str> = Vec::new();
    for list in LIST_FIELDS {
        let mut seen: Vec<String> = Vec::new();
        for candidate in &models {
            let mut entries = candidate
                .get(*list)
                .cloned()
                .unwrap_or(Value::Array(Vec::new()));
            sort_arrays(&mut entries);
            let key = entries.to_string();
            if !seen.contains(&key) {
                seen.push(key);
            }
        }
        if seen.len() > 1 {
            changed.push(list);
        }
    }

    Ok(json!({
        "vcard": merged,
        "model": model,
        "alternatives": Value::Object(alternatives),
        "changed": changed,
    }))
}

/// Collapses the model's duplicated list entries, keeping the first of
/// each: object entries match on their identifying fields, string
/// entries on themselves, both trimmed and case-insensitive.
fn dedup_lists(model: &mut Value) {
    dedup_entries(model, "phones", &["number"]);
    dedup_entries(model, "emails", &["address"]);
    dedup_entries(
        model,
        "addresses",
        &[
            "pobox", "ext", "street", "city", "region", "postcode", "country",
        ],
    );
    for list in ["websites", "nicknames", "notes", "categories"] {
        dedup_strings(model, list);
    }
}

/// Drops the list's object entries whose identifying fields duplicate
/// an earlier entry's.
fn dedup_entries(model: &mut Value, list: &str, fields: &[&str]) {
    let Some(entries) = model.get_mut(list).and_then(Value::as_array_mut) else {
        return;
    };

    let mut seen: Vec<String> = Vec::new();
    entries.retain(|entry| {
        let key = fields
            .iter()
            .map(|field| {
                entry
                    .get(*field)
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .trim()
                    .to_lowercase()
            })
            .collect::<Vec<_>>()
            .join("\u{1f}");

        if seen.contains(&key) {
            false
        } else {
            seen.push(key);
            true
        }
    });
}

/// Drops the list's string entries duplicating an earlier one.
fn dedup_strings(model: &mut Value, list: &str) {
    let Some(items) = model.get_mut(list).and_then(Value::as_array_mut) else {
        return;
    };

    let mut seen: Vec<String> = Vec::new();
    items.retain(|item| {
        let key = item.as_str().unwrap_or("").trim().to_lowercase();
        if seen.contains(&key) {
            false
        } else {
            seen.push(key);
            true
        }
    });
}

/// A single-valued model field reached by its dot path, empty when
/// absent.
fn single_field<'m>(model: &'m Value, path: &str) -> &'m str {
    let mut value = model;
    for part in path.split('.') {
        match value.get(part) {
            Some(inner) => value = inner,
            None => return "",
        }
    }
    value.as_str().unwrap_or("")
}

/// The named field of a model array's first entry, empty when absent.
fn first_entry(model: &Value, array: &str, field: &str) -> String {
    model
        .get(array)
        .and_then(|entries| entries.get(0))
        .and_then(|entry| entry.get(field))
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string()
}

/// Sorts every array of the model by the items' serialized form, so
/// the content hash ignores entry order.
fn sort_arrays(value: &mut Value) {
    match value {
        Value::Array(items) => {
            for item in items.iter_mut() {
                sort_arrays(item);
            }
            items.sort_by_key(|item| item.to_string());
        }
        Value::Object(map) => {
            for (_, inner) in map.iter_mut() {
                sort_arrays(inner);
            }
        }
        _ => {}
    }
}

/// FNV-1a 64: a stable hash across runs and releases, unlike the std
/// hasher (stored hashes compare against freshly computed ones).
fn fnv1a(bytes: &[u8]) -> u64 {
    let mut hash: u64 = 0xcbf29ce484222325;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

/// Longest raw property line the provider backends stash server-side.
/// Longer lines (base64 PHOTO blobs, essentially) stay only in the
/// local document of record instead of risking the whole write against
/// undocumented provider size limits.
pub(crate) const MAX_STASH_LINE: usize = 8 * 1024;

/// Splices raw property lines (logical lines without their ending)
/// into a serialized vCard, right before its END:VCARD line.
pub(crate) fn splice_props(vcard: String, lines: &[String]) -> String {
    if lines.is_empty() {
        return vcard;
    }

    let mut extra = lines.join("\r\n");
    extra.push_str("\r\n");

    match vcard.rfind("END:VCARD") {
        Some(position) => {
            let mut out = vcard;
            out.insert_str(position, &extra);
            out
        }
        None => vcard + &extra,
    }
}

/// Escapes a text value for a minted property line (RFC 6350 3.4:
/// backslash, comma, semicolon and newline).
pub(crate) fn escape_text(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '\\' => out.push_str("\\\\"),
            ',' => out.push_str("\\,"),
            ';' => out.push_str("\\;"),
            '\n' => out.push_str("\\n"),
            '\r' => {}
            _ => out.push(character),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn card(props: &str) -> Value {
        let vcard = format!("BEGIN:VCARD\r\nVERSION:3.0\r\n{props}END:VCARD\r\n");
        project(&vcard).unwrap()
    }

    #[test]
    fn index_extracts_display_fields() {
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nFN:Jane Doe\r\n\
            EMAIL:jane@doe.org\r\nEMAIL:b@x.org\r\nTEL;TYPE=cell:+331111\r\nEND:VCARD\r\n";

        let index = index(vcard).unwrap();
        assert_eq!(index["name"], "Jane Doe");
        assert_eq!(index["email"], "jane@doe.org");
        assert_eq!(index["phone"], "+331111");
        assert_eq!(index["uid"], "abc");
        assert!(!index["hash"].as_str().unwrap().is_empty());
    }

    #[test]
    fn index_hash_ignores_order_and_volatile_props() {
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nFN:Jane\r\n\
            TEL:+331111\r\nREV:20260707T000000Z\r\nEND:VCARD\r\n";
        let b = "BEGIN:VCARD\r\nVERSION:3.0\r\nTEL:+331111\r\nFN:Jane\r\n\
            UID:abc\r\nPRODID:-//other//tool//EN\r\nEND:VCARD\r\n";
        let c = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nFN:Janet\r\n\
            TEL:+331111\r\nEND:VCARD\r\n";

        assert_eq!(index(a).unwrap()["hash"], index(b).unwrap()["hash"]);
        assert_ne!(index(a).unwrap()["hash"], index(c).unwrap()["hash"]);
    }

    #[test]
    fn index_hash_ignores_identity_and_unmanaged_props() {
        // Per-replica identity (UID) and unmanaged data (X-*, PHOTO)
        // stay per replica under fan-out editing, so they must not
        // read as divergence.
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nFN:Jane\r\n\
            X-FOO:bar\r\nPHOTO;ENCODING=b:AAAA\r\nEND:VCARD\r\n";
        let b = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:def\r\nFN:Jane\r\nEND:VCARD\r\n";

        assert_eq!(index(a).unwrap()["hash"], index(b).unwrap()["hash"]);
    }

    #[test]
    fn merge_cards_unions_and_reports_alternatives() {
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:John Doe\r\n\
            TEL:+331111\r\nEND:VCARD\r\n";
        let b = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:b\r\nFN:Jon Doe\r\n\
            TEL:+332222\r\nEMAIL:j@doe.org\r\nEND:VCARD\r\n";

        let merged = merge_cards(&[a.to_string(), b.to_string()]).unwrap();

        // Single-valued conflicts keep the first card's value and
        // surface every distinct choice as an alternative (the winner
        // included); multi-valued properties union.
        assert_eq!(merged["model"]["displayName"], "John Doe");
        assert_eq!(merged["model"]["phones"].as_array().unwrap().len(), 2);
        assert_eq!(merged["model"]["emails"][0]["address"], "j@doe.org");
        assert_eq!(
            merged["alternatives"]["displayName"],
            json!(["John Doe", "Jon Doe"]),
        );
        assert!(merged["alternatives"].get("title").is_none());
        // The cards disagree on phones and emails, and on nothing else.
        assert_eq!(merged["changed"], json!(["phones", "emails"]));
    }

    #[test]
    fn merge_cards_dedups_similar_list_items() {
        // The same number under different types, and the same address
        // in a different case, prefill as one entry each.
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            TEL;TYPE=home:+331111\r\nEMAIL:jane@doe.org\r\nEND:VCARD\r\n";
        let b = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:b\r\nFN:Jane\r\n\
            TEL;TYPE=work:+331111\r\nEMAIL:JANE@doe.org\r\n\
            EMAIL:j@doe.org\r\nEND:VCARD\r\n";

        let merged = merge_cards(&[a.to_string(), b.to_string()]).unwrap();

        assert_eq!(merged["model"]["phones"].as_array().unwrap().len(), 1);
        assert_eq!(merged["model"]["emails"].as_array().unwrap().len(), 2);
        assert_eq!(merged["model"]["emails"][0]["address"], "jane@doe.org");
    }

    #[test]
    fn merge_conflict_reconciles_both_sides_against_the_base() {
        let base = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            TEL:+331111\r\nEND:VCARD\r\n";
        let local = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            TEL:+332222\r\nEND:VCARD\r\n";
        let remote = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Janet\r\n\
            TEL:+331111\r\nEND:VCARD\r\n";

        let report = merge_conflict(base, local, remote).unwrap();
        let merged = report["vcard"].as_str().unwrap();

        // The remote rename and the local phone edit both survive.
        assert!(merged.contains("FN:Janet\r\n"), "got: {merged}");
        assert!(merged.contains("TEL:+332222\r\n"), "got: {merged}");
        assert_eq!(report["conflicts"], 0);
    }

    #[test]
    fn set_uid_rewrites_only_the_uid() {
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:old\r\nFN:Jane\r\n\
            X-FOO:bar\r\nEND:VCARD\r\n";

        let fresh = set_uid(vcard, "new").unwrap();

        assert!(fresh.contains("UID:new\r\n"), "got: {fresh}");
        assert!(!fresh.contains("UID:old"), "got: {fresh}");
        assert!(fresh.contains("X-FOO:bar\r\n"), "got: {fresh}");
    }

    #[test]
    fn merge_cards_identical_documents_merge_clean() {
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            TEL:+331111\r\nEND:VCARD\r\n";

        let merged = merge_cards(&[a.to_string(), a.to_string()]).unwrap();

        assert_eq!(merged["model"]["phones"].as_array().unwrap().len(), 1);
        assert!(merged["alternatives"].as_object().unwrap().is_empty());
    }

    #[test]
    fn names_and_identity() {
        let model = card("UID:abc-123\r\nFN:John Doe\r\nN:Doe;John;Quincy;Dr.;Jr.\r\n");
        assert_eq!(model["uid"], "abc-123");
        assert_eq!(model["displayName"], "John Doe");
        assert_eq!(model["name"]["family"], "Doe");
        assert_eq!(model["name"]["given"], "John");
        assert_eq!(model["name"]["middle"], "Quincy");
        assert_eq!(model["name"]["prefix"], "Dr.");
        assert_eq!(model["name"]["suffix"], "Jr.");
    }

    #[test]
    fn phones_collect_types_and_pref() {
        let model = card("TEL;TYPE=HOME,VOICE:+331111\r\nTEL;type=CELL;type=pref:+332222\r\n");
        let phones = model["phones"].as_array().unwrap();
        assert_eq!(phones[0]["number"], "+331111");
        assert_eq!(phones[0]["types"], json!(["home", "voice"]));
        assert_eq!(phones[0]["pref"], false);
        assert_eq!(phones[1]["types"], json!(["cell"]));
        assert_eq!(phones[1]["pref"], true);
    }

    #[test]
    fn address_components() {
        let model = card("ADR;TYPE=WORK:;;12 Main St;Paris;IDF;75000;France\r\n");
        let adr = &model["addresses"][0];
        assert_eq!(adr["street"], "12 Main St");
        assert_eq!(adr["city"], "Paris");
        assert_eq!(adr["region"], "IDF");
        assert_eq!(adr["postcode"], "75000");
        assert_eq!(adr["country"], "France");
        assert_eq!(adr["types"], json!(["work"]));
    }

    #[test]
    fn org_note_escapes() {
        let model = card("ORG:ACME\\, Inc.;R&D\r\nNOTE:line one\\nline two\r\nTITLE:Boss\r\n");
        assert_eq!(model["organization"]["company"], "ACME, Inc.");
        assert_eq!(model["organization"]["department"], "R&D");
        assert_eq!(model["notes"][0], "line one\nline two");
        assert_eq!(model["title"], "Boss");
    }

    #[test]
    fn birthday_full_dates_only() {
        assert_eq!(card("BDAY:1985-04-12\r\n")["birthday"], "1985-04-12");
        assert_eq!(card("BDAY:19850412\r\n")["birthday"], "1985-04-12");
        assert!(card("BDAY:--04-12\r\n").get("birthday").is_none());
    }

    #[test]
    fn custom_properties_ignored() {
        let model = card("FN:X\r\nX-PHONETIC-FIRST-NAME:Zhon\r\nX-ANNIVERSARY:2001-01-01\r\n");
        assert_eq!(model["displayName"], "X");
        assert!(model.get("birthday").is_none());
    }

    #[test]
    fn categories_projected_but_preserved_by_apply() {
        let vcard =
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:X\r\nCATEGORIES:friends,band\r\nEND:VCARD\r\n";
        assert_eq!(
            project(vcard).unwrap()["categories"],
            json!(["friends", "band"])
        );

        let patched = apply(vcard, &json!({ "displayName": "Y" })).unwrap();
        assert!(patched.contains("CATEGORIES:friends,band\r\n"));
    }

    #[test]
    fn apply_rewrites_managed_and_preserves_the_rest() {
        let vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:abc\r\nFN:Old Name\r\nTEL;TYPE=HOME:+331111\r\nX-FOO:bar\r\nPHOTO;ENCODING=b;TYPE=JPEG:AAAA\r\nEND:VCARD\r\n";
        let model = json!({
            "displayName": "New Name",
            "phones": [{ "number": "+332222", "types": ["cell"], "pref": true }],
        });

        let patched = apply(vcard, &model).unwrap();
        assert!(patched.contains("FN:New Name\r\n"));
        assert!(patched.contains("TEL;TYPE=cell,pref:+332222\r\n"));
        assert!(!patched.contains("+331111"));
        assert!(patched.contains("UID:abc\r\n"));
        assert!(patched.contains("X-FOO:bar\r\n"));
        assert!(patched.contains("PHOTO;ENCODING=b;TYPE=JPEG:AAAA\r\n"));
    }

    #[test]
    fn apply_project_round_trip() {
        let vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:abc\r\nFN:X\r\nEND:VCARD\r\n";
        let model = json!({
            "displayName": "Jane Doe",
            "name": { "family": "Doe", "given": "Jane", "middle": "", "prefix": "", "suffix": "" },
            "phones": [{ "number": "+333333", "types": ["work"], "pref": false }],
            "emails": [{ "address": "jane@doe.org", "types": ["home"], "pref": false }],
            "addresses": [{
                "pobox": "", "ext": "", "street": "12 Main St", "city": "Paris",
                "region": "", "postcode": "75000", "country": "France",
                "types": ["home"], "pref": false,
            }],
            "organization": { "company": "ACME, Inc.", "department": "R&D" },
            "title": "Boss",
            "birthday": "1985-04-12",
            "websites": ["https://doe.org"],
            "notes": ["line one\nline two"],
        });

        let projected = project(&apply(vcard, &model).unwrap()).unwrap();
        assert_eq!(projected["displayName"], "Jane Doe");
        assert_eq!(projected["name"]["family"], "Doe");
        assert_eq!(projected["phones"][0]["number"], "+333333");
        assert_eq!(projected["phones"][0]["types"], json!(["work"]));
        assert_eq!(projected["emails"][0]["address"], "jane@doe.org");
        assert_eq!(projected["addresses"][0]["street"], "12 Main St");
        assert_eq!(projected["addresses"][0]["postcode"], "75000");
        assert_eq!(projected["organization"]["company"], "ACME, Inc.");
        assert_eq!(projected["organization"]["department"], "R&D");
        assert_eq!(projected["title"], "Boss");
        assert_eq!(projected["birthday"], "1985-04-12");
        assert_eq!(projected["websites"][0], "https://doe.org");
        assert_eq!(projected["notes"][0], "line one\nline two");
    }

    #[test]
    fn apply_composes_fn_and_drops_emptied_fields() {
        let vcard =
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Old\r\nTITLE:Boss\r\nNOTE:gone\r\nEND:VCARD\r\n";
        let model = json!({
            "displayName": "",
            "name": { "family": "Doe", "given": "Jane" },
            "title": "",
            "notes": [],
        });

        let patched = apply(vcard, &model).unwrap();
        assert!(patched.contains("FN:Jane Doe\r\n"));
        assert!(!patched.contains("TITLE"));
        assert!(!patched.contains("NOTE"));
    }
}
