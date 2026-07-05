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
fn text_prop(
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
fn full_date(raw: &str) -> Option<String> {
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

#[cfg(test)]
mod tests {
    use super::*;

    fn card(props: &str) -> Value {
        let vcard = format!("BEGIN:VCARD\r\nVERSION:3.0\r\n{props}END:VCARD\r\n");
        project(&vcard).unwrap()
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
