//! vCard to phone field model projection, and back.
//!
//! [`project`] turns a vCard into the neutral field model the Java side
//! maps to ContactsContract rows and edit-form fields; [`apply`] patches
//! an edited model back onto the original vCard through the vcard-rs
//! CST, so every property the model does not manage (PHOTO, CATEGORIES,
//! KIND, X-*, parameters of unmanaged lines) survives byte for byte.
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
        prop::{
            VcardPropLens, adr::ADR, anniversary::ANNIVERSARY, bday::BDAY, categories::CATEGORIES,
            email::EMAIL, r#fn::FN, gender::GENDER, impp::IMPP, lang::LANG, n::N,
            nickname::NICKNAME, note::NOTE, org::ORG, related::RELATED, role::ROLE, tel::TEL,
            title::TITLE, uid::UID, url::URL,
        },
    },
    value::{
        VcardValue,
        adr::VcardAdr,
        datetime::VcardDateAndOrTime,
        gender::VcardGender,
        language::VcardLanguageTag,
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
    let mut impps = Vec::new();
    let mut languages = Vec::new();
    let mut relations = Vec::new();

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
            VcardPropKind::Anniversary => {
                if let Some(date) = full_date(&line.raw_value_str()) {
                    first(&mut model, "anniversary", Value::String(date));
                }
            }
            VcardPropKind::Gender => {
                let gender = GENDER::decode(line, version);
                first(
                    &mut model,
                    "gender",
                    json!({ "sex": text(&gender.sex), "identity": text(&gender.identity) }),
                );
            }
            VcardPropKind::Impp => {
                let impp = IMPP::decode(line, version);
                impps.push(text(&impp.0));
            }
            VcardPropKind::Lang => {
                let lang = LANG::decode(line, version);
                languages.push(text(&lang.0));
            }
            VcardPropKind::Related => {
                let related = RELATED::decode(line, version);
                let (types, pref) = types(line);
                relations.push(json!({
                    "value": text(&related.0),
                    "types": types,
                    "pref": pref,
                }));
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
    model.insert("impps".into(), Value::Array(impps));
    model.insert("languages".into(), Value::Array(languages));
    model.insert("relations".into(), Value::Array(relations));

    Ok(Value::Object(model))
}

/// Patches the edited field model back onto the vCard. Every managed
/// property is removed and rewritten from the model in canonical form
/// (appended at the end of the card); every other line keeps its exact
/// bytes, so unmanaged data (PHOTO, CATEGORIES, KIND, X-*) survives.
pub fn apply(vcard: &str, model: &Value) -> Result<String, String> {
    let mut card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;
    let version = card.version();

    // NOTE: FN persists only for a real display name; the views
    // compose one on the fly otherwise, never minting into the record.
    card.remove::<FN>();
    let display = field(model, "displayName");
    if !display.is_empty() {
        card.push(text_prop(VcardPropKind::Fn, vec![], display));
    }

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
            ..Default::default()
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

    card.remove::<ANNIVERSARY>();
    if let Some(date) = full_date(field(model, "anniversary")) {
        card.push(VcardProp {
            name: VcardPropName::Kind(VcardPropKind::Anniversary),
            params: vec![],
            value: VcardValue::DateAndOrTime(VcardDateAndOrTime(Cow::Owned(date))),
        });
    }

    card.remove::<GENDER>();
    if let Some(gender) = model.get("gender").and_then(Value::as_object) {
        let sex = gender
            .get("sex")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim();
        let identity = gender
            .get("identity")
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim();

        if !sex.is_empty() || !identity.is_empty() {
            card.push(VcardProp {
                name: VcardPropName::Kind(VcardPropKind::Gender),
                params: vec![],
                value: VcardValue::Gender(VcardGender {
                    sex: Cow::Owned(sex.to_string()),
                    identity: Cow::Owned(identity.to_string()),
                }),
            });
        }
    }

    card.remove::<IMPP>();
    for impp in strings(model.get("impps")) {
        card.push(VcardProp {
            name: VcardPropName::Kind(VcardPropKind::Impp),
            params: vec![],
            value: VcardValue::Uri(VcardUri(Cow::Owned(impp))),
        });
    }

    card.remove::<LANG>();
    for tag in strings(model.get("languages")) {
        card.push(VcardProp {
            name: VcardPropName::Kind(VcardPropKind::Lang),
            params: vec![],
            value: VcardValue::LanguageTag(VcardLanguageTag(Cow::Owned(tag))),
        });
    }

    // NOTE: RELATED defaults to a URI value; a free-text relation (no
    // URI scheme) must say VALUE=text (RFC 6350 6.6.6).
    card.remove::<RELATED>();
    for relation in array(model.get("relations")) {
        let value = field(relation, "value");
        if value.is_empty() {
            continue;
        }

        let mut params = type_params(relation, version);
        if value.contains(':') {
            card.push(VcardProp {
                name: VcardPropName::Kind(VcardPropKind::Related),
                params,
                value: VcardValue::Uri(VcardUri(Cow::Owned(value.to_string()))),
            });
        } else {
            params.push(VcardParam::Value(Cow::Borrowed("text")));
            card.push(text_prop(VcardPropKind::Related, params, value));
        }
    }

    Ok(String::from_utf8_lossy(&card.to_bytes()).into_owned())
}

/// The display name to SHOW (never persisted): FN when the card has
/// one, else composed on the fly from the name parts, else the first
/// nickname, email or phone, so a nameless card still reads as
/// something in the lists.
fn display_name(model: &Value) -> String {
    let display = field(model, "displayName");
    if !display.is_empty() {
        return display.to_string();
    }

    let composed = name_summary(model);
    if !composed.is_empty() {
        return composed;
    }

    let nickname = model
        .get("nicknames")
        .and_then(Value::as_array)
        .and_then(|entries| entries.first())
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim();
    if !nickname.is_empty() {
        return nickname.to_string();
    }

    let email = first_entry(model, "emails", "address");
    if !email.is_empty() {
        return email;
    }
    first_entry(model, "phones", "number")
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

pub(crate) fn array(value: Option<&Value>) -> &[Value] {
    value
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[])
}

pub(crate) fn strings(value: Option<&Value>) -> Vec<String> {
    array(value)
        .iter()
        .filter_map(Value::as_str)
        .map(str::trim)
        .filter(|item| !item.is_empty())
        .map(str::to_string)
        .collect()
}

pub(crate) fn field<'m>(entry: &'m Value, key: &str) -> &'m str {
    entry.get(key).and_then(Value::as_str).unwrap_or("").trim()
}

/// Inserts single-instance fields, first occurrence wins.
fn first(model: &mut Map<String, Value>, key: &str, value: Value) {
    if !model.contains_key(key) {
        model.insert(key.into(), value);
    }
}

pub(crate) fn text(value: impl AsRef<str>) -> Value {
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
    if let [y, m, d] = dashed[..]
        && y.len() == 4
        && m.len() == 2
        && d.len() == 2
        && digits(y)
        && digits(m)
        && digits(d)
    {
        return Some(format!("{y}-{m}-{d}"));
    }

    if date.len() == 8 && digits(date) {
        return Some(format!("{}-{}-{}", &date[..4], &date[4..6], &date[6..]));
    }

    None
}

/// Indexes a vCard for the app's store, computed once at write time:
/// the fields the contacts list renders (FN, first EMAIL, first TEL,
/// a fallback info line, UID) and a normalized content hash for the
/// divergence flag of linked replicas. The hash is FNV-1a over the
/// canonical field model with its arrays sorted and the identity and
/// unmanaged data excluded (UID, CATEGORIES, X-*, PHOTO), so linked
/// replicas read as one contact as long as the content the edit form
/// manages agrees; per-replica identity and backend-specific baggage
/// never read as divergence.
pub fn index(vcard: &str) -> Result<Value, String> {
    let model = project(vcard)?;

    let name = display_name(&model);
    let uid = single_field(&model, "uid").to_string();
    let email = first_entry(&model, "emails", "address");
    let phone = first_entry(&model, "phones", "number");
    let info = fallback_info(&model);

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
        "info": info,
        "uid": uid,
        "hash": format!("{hash:016x}"),
    }))
}

/// The list row's fallback line for cards with no phone and no email:
/// the first valuable field the card carries, first line only.
fn fallback_info(model: &Value) -> String {
    for field in ["organization.company", "title", "role"] {
        let value = single_field(model, field);
        if !value.is_empty() {
            return value.to_string();
        }
    }

    for list in ["websites", "nicknames", "notes"] {
        let first = model
            .get(list)
            .and_then(Value::as_array)
            .and_then(|entries| entries.first())
            .and_then(Value::as_str)
            .unwrap_or("")
            .trim();
        if !first.is_empty() {
            return first.lines().next().unwrap_or("").to_string();
        }
    }

    String::new()
}

mod dedup;
mod form;
mod group;
mod merge;
mod props;

pub(crate) use dedup::*;
pub(crate) use form::*;
pub(crate) use group::*;
pub(crate) use merge::*;
pub(crate) use props::*;

/// A single-valued model field reached by its dot path, empty when
/// absent.
pub(crate) fn single_field<'m>(model: &'m Value, path: &str) -> &'m str {
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
pub(crate) fn sort_arrays(value: &mut Value) {
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
        // NOTE: per-replica identity (UID) and unmanaged data (X-*,
        // PHOTO) stay per replica under fan-out editing, so they must
        // not read as divergence.
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

        assert_eq!(merged["model"]["displayName"], "John Doe");
        assert_eq!(merged["model"]["phones"].as_array().unwrap().len(), 2);
        assert_eq!(merged["model"]["emails"][0]["address"], "j@doe.org");
        assert_eq!(
            merged["alternatives"]["displayName"],
            json!(["John Doe", "Jon Doe"]),
        );
        assert!(merged["alternatives"].get("title").is_none());
        assert_eq!(merged["changed"], json!(["phones", "emails"]));
    }

    #[test]
    fn merge_cards_dedups_similar_list_items() {
        // NOTE: the same number under different types, and the same
        // address in a different case, prefill as one entry each.
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

        assert!(merged.contains("FN:Janet\r\n"), "got: {merged}");
        assert!(merged.contains("TEL:+332222\r\n"), "got: {merged}");
        assert_eq!(report["conflicts"], 0);
    }

    #[test]
    fn merge_conflict_empty_base_lets_the_local_edit_win() {
        // NOTE: no captured base (the phone axis lost it), so the local
        // hub edit must win, not silently revert to the remote value.
        let local = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:ZZZ123\r\nEND:VCARD\r\n";
        let remote = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company2\r\nEND:VCARD\r\n";

        let report = merge_conflict("", local, remote).unwrap();
        let merged = report["vcard"].as_str().unwrap();

        assert!(merged.contains("ORG:ZZZ123\r\n"), "got: {merged}");
        assert!(!merged.contains("ORG:Company2\r\n"), "got: {merged}");
    }

    #[test]
    fn merge_conflict_form_surfaces_same_field_collisions_only() {
        // NOTE: ORG collides (both moved from Company20); TITLE moved on
        // the remote only, so it is auto-taken, not offered for review.
        let base = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company20\r\nTITLE:Dev\r\nREV:2026-07-11T17:00:00Z\r\nEND:VCARD\r\n";
        let local = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company30\r\nTITLE:Dev\r\nREV:2026-07-11T17:08:14Z\r\nEND:VCARD\r\n";
        let remote = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company31\r\nTITLE:Lead\r\nREV:2026-07-11T17:25:59Z\r\nEND:VCARD\r\n";

        let form = merge_conflict_form(base, local, remote).unwrap();

        assert_eq!(form["model"]["title"], "Lead");
        assert_eq!(form["model"]["organization"]["company"], "Company31");

        let alternatives = form["alternatives"].as_object().unwrap();
        assert_eq!(alternatives.len(), 1);
        let org = alternatives["organization.company"].as_array().unwrap();
        assert_eq!(org[0], "Company31");
        assert_eq!(org[1], "Company30");

        assert_eq!(form["resolved"], false);
        assert_eq!(form["changed"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn merge_conflict_form_resolves_one_sided_changes() {
        // NOTE: each side moved a different field (local TITLE, remote
        // ORG), so no field collides and the merge takes both.
        let base = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company20\r\nTITLE:Dev\r\nREV:2026-07-11T17:00:00Z\r\nEND:VCARD\r\n";
        let local = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company20\r\nTITLE:Lead\r\nREV:2026-07-11T17:08:14Z\r\nEND:VCARD\r\n";
        let remote = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company30\r\nTITLE:Dev\r\nREV:2026-07-11T17:25:59Z\r\nEND:VCARD\r\n";

        let form = merge_conflict_form(base, local, remote).unwrap();

        assert!(form["alternatives"].as_object().unwrap().is_empty());
        assert_eq!(form["resolved"], true);
        assert_eq!(form["model"]["title"], "Lead");
        assert_eq!(form["model"]["organization"]["company"], "Company30");
    }

    #[test]
    fn extended_fields_round_trip() {
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Jane\r\n\
            ANNIVERSARY:20200102\r\nGENDER:F;girl\r\n\
            IMPP:xmpp:jane@chat.example\r\nLANG:fr\r\nLANG:en\r\n\
            RELATED;TYPE=spouse;VALUE=text:John Doe\r\n\
            X-FOO:bar\r\nEND:VCARD\r\n";

        let model = project(vcard).unwrap();
        assert_eq!(model["anniversary"], "2020-01-02");
        assert_eq!(model["gender"]["sex"], "F");
        assert_eq!(model["gender"]["identity"], "girl");
        assert_eq!(model["impps"][0], "xmpp:jane@chat.example");
        assert_eq!(model["languages"][1], "en");
        assert_eq!(model["relations"][0]["value"], "John Doe");
        assert_eq!(model["relations"][0]["types"][0], "spouse");

        let fresh = apply(vcard, &model).unwrap();
        assert!(fresh.contains("ANNIVERSARY:2020-01-02\r\n"), "got: {fresh}");
        assert!(fresh.contains("GENDER:F;girl\r\n"), "got: {fresh}");
        assert!(
            fresh.contains("IMPP:xmpp:jane@chat.example\r\n"),
            "got: {fresh}"
        );
        assert!(fresh.contains("LANG:fr\r\n"), "got: {fresh}");
        assert!(
            fresh.contains("RELATED;TYPE=spouse;VALUE=text:John Doe\r\n"),
            "got: {fresh}"
        );
        assert!(fresh.contains("X-FOO:bar\r\n"), "got: {fresh}");
    }

    #[test]
    fn merge_cards_flags_present_vs_absent_single_fields() {
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            TITLE:Boss\r\nEND:VCARD\r\n";
        let b = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\nEND:VCARD\r\n";

        let merged = merge_cards(&[a.to_string(), b.to_string()]).unwrap();

        let title = merged["alternatives"]["title"].as_array().unwrap();
        assert!(
            title.contains(&Value::String("Boss".into())),
            "got: {title:?}"
        );
        assert!(title.contains(&Value::String("".into())), "got: {title:?}");
    }

    #[test]
    fn card_props_lists_and_card_set_prop_rewrites() {
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Jane\r\n\
            X-FOO:bar\r\nEND:VCARD\r\n";

        let props = card_props(vcard).unwrap();
        let props = props.as_array().unwrap();
        assert_eq!(props.len(), 3);
        assert_eq!(props[0]["line"], "VERSION:4.0");
        assert_eq!(props[2]["name"], "X-FOO");
        assert_eq!(props[2]["value"], "bar");

        let edited = card_set_prop(vcard, 2, "X-FOO:baz").unwrap();
        assert!(edited.contains("X-FOO:baz\r\n"), "got: {edited}");
        assert!(!edited.contains("X-FOO:bar"), "got: {edited}");

        let removed = card_set_prop(&edited, 2, "").unwrap();
        assert!(!removed.contains("X-FOO"), "got: {removed}");

        let appended = card_set_prop(&removed, -1, "NOTE:hi").unwrap();
        assert!(appended.contains("NOTE:hi\r\n"), "got: {appended}");

        assert!(card_set_prop(vcard, 9, "X:y").is_err());
    }

    #[test]
    fn card_set_prop_parts_recomposes_and_card_source_validates() {
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Jane\r\nEND:VCARD\r\n";

        let prop = json!({
            "name": "tel",
            "params": [{ "name": "TYPE", "values": ["cell", "a;b"] }],
            "value": "+336",
        });
        let fresh = card_set_prop_parts(vcard, -1, &prop).unwrap();
        assert!(fresh.contains("TEL;TYPE=cell,ab:+336\r\n"), "got: {fresh}");

        let props = card_props(&fresh).unwrap();
        let tel = &props.as_array().unwrap()[2];
        assert_eq!(tel["params"][0]["name"], "TYPE");
        assert_eq!(tel["params"][0]["values"][0], "cell");

        assert!(card_source(&fresh).unwrap().contains("FN:Jane\r\n"));
        assert!(card_set_prop_parts(vcard, -1, &json!({ "value": "x" })).is_err());

        // NOTE: structured values recompose from labeled components,
        // items escaped, and card_props decodes them back.
        let structured = json!({
            "name": "N",
            "params": [],
            "components": [
                { "value": "Doe;X" },
                { "value": "Jane" },
                { "value": "" },
                { "value": "Dr" },
                { "value": "" },
            ],
        });
        let named = card_set_prop_parts(vcard, -1, &structured).unwrap();
        assert!(named.contains("N:Doe\\;X;Jane;;Dr;\r\n"), "got: {named}");

        let props = card_props(&named).unwrap();
        let n = &props.as_array().unwrap()[2];
        assert_eq!(n["components"][0]["name"], "Family name");
        assert_eq!(n["components"][0]["value"], "Doe;X");
        assert_eq!(n["components"][3]["value"], "Dr");
        assert_eq!(card_prop_labels("ADR").len(), 7);
        assert!(card_prop_labels("TEL").is_empty());
    }

    #[test]
    fn find_duplicates_groups_matches_and_skips_uid_linked() {
        let cards = vec![
            (
                "a".to_string(),
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane Doe\r\n\
                    TEL:+33 6 12 34 56 78\r\nEND:VCARD\r\n"
                    .to_string(),
            ),
            (
                "b".to_string(),
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:b\r\nFN:Janette\r\n\
                    TEL:06 12 34 56 78\r\nEND:VCARD\r\n"
                    .to_string(),
            ),
            // NOTE: same email, but one contact already (shared UID).
            (
                "c".to_string(),
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:x\r\nFN:Bob\r\n\
                    EMAIL:bob@example.org\r\nEND:VCARD\r\n"
                    .to_string(),
            ),
            (
                "d".to_string(),
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:x\r\nFN:Bobby\r\n\
                    EMAIL:BOB@example.org\r\nEND:VCARD\r\n"
                    .to_string(),
            ),
        ];

        let found = find_duplicates(&cards).unwrap();
        let groups = found["groups"].as_array().unwrap();

        assert_eq!(groups.len(), 1, "got: {groups:?}");
        assert_eq!(groups[0]["refs"], json!(["a", "b"]));
        assert_eq!(groups[0]["reasons"], json!(["phone"]));
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
    fn apply_never_mints_fn_and_drops_emptied_fields() {
        let vcard =
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Old\r\nTITLE:Boss\r\nNOTE:gone\r\nEND:VCARD\r\n";
        let model = json!({
            "displayName": "",
            "name": { "family": "Doe", "given": "Jane" },
            "title": "",
            "notes": [],
        });

        // NOTE: an emptied display name drops FN instead of persisting a
        // composed one; the views compose on the fly (index name).
        let patched = apply(vcard, &model).unwrap();
        assert!(!patched.contains("FN:"), "got: {patched}");
        assert!(!patched.contains("TITLE"));
        assert!(!patched.contains("NOTE"));

        let indexed = index(&patched).unwrap();
        assert_eq!(indexed["name"], "Jane Doe");
    }

    /// The form view composes the summaries, positions every typed
    /// entry's spinner and parses the picker dates in one pass.
    #[test]
    fn form_view_supports_the_whole_page() {
        let model = json!({
            "name": { "prefix": "Dr", "given": "Jane", "family": "Doe" },
            "organization": { "company": "ACME" },
            "title": "Boss",
            "gender": { "sex": "f", "identity": " she " },
            "birthday": "1985-04-12",
            "anniversary": "not-a-date",
            "phones": [
                { "number": "1", "types": ["fax", "home"] },
                { "number": "2", "types": [] },
            ],
            "emails": [{ "address": "a@b", "types": ["work"] }],
            "relations": [{ "value": "Bob", "types": ["colleague"] }],
            "addresses": [
                { "street": "12 Main St\nBuilding B", "types": ["home"] },
                { "city": "Paris", "types": [] },
            ],
        });

        let view = form_view(&model);

        assert_eq!(view["name"], "Dr Jane Doe");
        assert_eq!(view["organization"], "ACME · Boss");
        assert_eq!(view["gender"]["sexIndex"], 2);
        assert_eq!(view["gender"]["identity"], "she");
        assert_eq!(
            view["birthday"],
            json!({ "year": 1985, "month": 4, "day": 12 })
        );
        assert!(view.get("anniversary").is_none());
        assert_eq!(view["phones"], json!([4, 7]));
        assert_eq!(view["emails"], json!([1]));
        assert_eq!(view["relations"], json!([5]));
        assert_eq!(view["addresses"][0]["index"], 0);
        assert_eq!(view["addresses"][0]["summary"], "12 Main St");
        assert_eq!(view["addresses"][1]["index"], 2);
        assert_eq!(view["addresses"][1]["summary"], "Paris");
    }

    /// Every form entry kind resolves its spinner position to the
    /// documented shape; the gender pair empties to an empty object.
    #[test]
    fn form_entry_builds_every_kind() {
        let phone = form_entry("phone", 3, "0612", true).unwrap();
        assert_eq!(
            phone,
            json!({ "number": "0612", "types": ["cell", "work"], "pref": true }),
        );

        let email = form_entry("email", 99, "a@b", false).unwrap();
        assert_eq!(email["types"], json!([]));

        let relation = form_entry("relation", 0, "Bob", false).unwrap();
        assert_eq!(relation["value"], "Bob");
        assert_eq!(relation["types"], json!(["spouse"]));

        let address = form_entry("address", 1, "", false).unwrap();
        assert_eq!(address, json!({ "types": ["work"] }));

        let gender = form_entry("gender", 2, "she", false).unwrap();
        assert_eq!(gender, json!({ "sex": "F", "identity": "she" }));
        assert_eq!(form_entry("gender", 0, "  ", false).unwrap(), json!({}));

        assert!(form_entry("website", 0, "", false).is_err());
        assert_eq!(form_date(1985, 4, 2), "1985-04-02");
    }

    /// Replicas group by UID, a detached replica falls back to its own
    /// ref, a link rebinds a natural key to its cluster, and the
    /// groups sort case-insensitively by primary display name (the id
    /// standing in for a nameless card).
    #[test]
    fn group_contacts_links_detaches_and_sorts() {
        let input = json!({
            "replicas": [
                { "ref": "a\nk1", "uid": "u1", "name": "zoe", "id": "k1" },
                { "ref": "b\nk2", "uid": "u1", "name": "Zoe", "id": "k2" },
                { "ref": "c\nk3", "uid": "u1", "name": "Ann", "id": "k3" },
                { "ref": "d\nk4", "uid": "", "name": "", "id": "Bob-id" },
                { "ref": "e\nk5", "uid": "u2", "name": "Solo", "id": "k5" },
            ],
            "links": { "uid\u{0}u2": "cluster-1", "ref\u{0}d\nk4": "cluster-1" },
            "detached": ["c\nk3"],
        });

        let reply = group_contacts(&input).unwrap();
        let groups = reply["groups"].as_array().unwrap();

        // NOTE: Ann (detached), then the linked Bob+Solo cluster, then
        // Zoe.
        assert_eq!(groups.len(), 3);
        assert_eq!(groups[0]["key"], "ref\u{0}c\nk3");
        assert_eq!(groups[0]["replicas"], json!([2]));
        assert_eq!(groups[1]["key"], "cluster-1");
        assert_eq!(groups[1]["replicas"], json!([3, 4]));
        assert_eq!(groups[2]["key"], "uid\u{0}u1");
        assert_eq!(groups[2]["replicas"], json!([0, 1]));
    }

    /// The dismissal key is the sorted distinct refs joined; Link
    /// needs at least two cards, each in its own addressbook.
    #[test]
    fn duplicate_group_keys_and_gates_link() {
        let linkable = json!([
            { "ref": "b", "book": "book2" },
            { "ref": "a", "book": "book1" },
            { "ref": "a", "book": "book1" },
        ]);
        let reply = duplicate_group(&linkable).unwrap();
        assert_eq!(reply["key"], "a\u{1f}b");
        assert_eq!(reply["linkable"], true);

        let same_book = json!([
            { "ref": "a", "book": "book1" },
            { "ref": "b", "book": "book1" },
        ]);
        assert_eq!(duplicate_group(&same_book).unwrap()["linkable"], false);

        let single = json!([{ "ref": "a", "book": "book1" }]);
        assert_eq!(duplicate_group(&single).unwrap()["linkable"], false);
    }
}
