//! The advanced raw-property editor: lists, recomposes and rewrites a
//! card's property lines while keeping every untouched line intact.

use core::str::FromStr;

use serde_json::{Value, json};
use vcard::{
    prop::VcardPropKind,
    tree::{
        cst::VcardCst,
        line::VcardLine,
        prop::{VcardPropLens, adr::ADR, gender::GENDER, n::N},
    },
    version::VcardVersion,
};

use crate::project::{array, field, strings};

/// Lists the card's raw property lines for the advanced editor, in
/// source order, unfolded, envelope excluded (VERSION included).
pub fn card_props(vcard: &str) -> Result<Value, String> {
    let card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;

    let props: Vec<Value> = card
        .props
        .iter()
        .map(|prop| {
            let line = raw_line(prop);
            let value = line
                .find(':')
                .map(|colon| &line[colon + 1..])
                .unwrap_or("")
                .to_string();
            let params: Vec<Value> = prop
                .params
                .iter()
                .map(|param| {
                    json!({
                        "name": param.name.get(),
                        "values": param
                            .values
                            .iter()
                            .map(|value| value.get())
                            .collect::<Vec<_>>(),
                    })
                })
                .collect();
            let mut entry = json!({
                "name": prop.name.get(),
                "params": params,
                "value": value,
                "line": line,
            });
            if let Some(components) = structured_components(prop, card.version()) {
                entry["components"] = components;
            }
            entry
        })
        .collect();

    Ok(json!(props))
}

/// Recomposes one property from its structured parts (`{"name",
/// "params": [{"name", "values"}], "value"}`) and rewrites it like
/// [`card_set_prop`] (-1 appends). Separators and quotes are dropped
/// from parameter values: the CST's head parser is not quote-aware,
/// so a quoted separator would not survive a round trip.
pub fn card_set_prop_parts(vcard: &str, index: i64, prop: &Value) -> Result<String, String> {
    let name = field(prop, "name").to_uppercase();
    if name.is_empty() {
        return Err("Empty property name".into());
    }

    let mut line = name;
    for param in array(prop.get("params")) {
        let param_name = field(param, "name");
        if param_name.is_empty() {
            continue;
        }
        line.push(';');
        line.push_str(param_name);
        let values: Vec<String> = strings(param.get("values"))
            .iter()
            .map(|value| quote_param(value))
            .collect();
        if !values.is_empty() {
            line.push('=');
            line.push_str(&values.join(","));
        }
    }
    // NOTE: a structured value comes as named components (shape from
    // card_prop_labels), each a comma list of escaped items, joined by
    // semicolons; a plain value rides as-is.
    line.push(':');
    if let Some(components) = prop.get("components").and_then(Value::as_array) {
        let composed = components
            .iter()
            .map(|component| {
                component
                    .get("value")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .split(',')
                    .map(|item| escape_component(item.trim()))
                    .collect::<Vec<_>>()
                    .join(",")
            })
            .collect::<Vec<_>>()
            .join(";");
        line.push_str(&composed);
    } else {
        line.push_str(prop.get("value").and_then(Value::as_str).unwrap_or(""));
    }

    card_set_prop(vcard, index, &line)
}

/// The component labels of a structured property value (N, ADR,
/// GENDER), empty for plain values: the advanced editor shapes its
/// value form from them, mirroring the vcard-rs value types.
pub fn card_prop_labels(name: &str) -> Vec<&'static str> {
    match VcardPropKind::from_str(name) {
        Ok(VcardPropKind::N) => vec![
            "Family name",
            "Given name",
            "Additional names",
            "Prefixes",
            "Suffixes",
        ],
        Ok(VcardPropKind::Adr) => vec![
            "PO box",
            "Extended",
            "Street",
            "Locality",
            "Region",
            "Postal code",
            "Country",
        ],
        Ok(VcardPropKind::Gender) => vec!["Sex", "Identity"],
        _ => Vec::new(),
    }
}

/// The property's decoded components zipped with their labels, None
/// for plain values. Multi-valued components read as comma lists.
fn structured_components(line: &VcardLine, version: VcardVersion) -> Option<Value> {
    let kind = VcardPropKind::from_str(line.name.get()).ok()?;
    let values: Vec<String> = match kind {
        VcardPropKind::N => {
            let n = N::decode(line, version);
            vec![
                n.family.join(","),
                n.given.join(","),
                n.additional.join(","),
                n.prefixes.join(","),
                n.suffixes.join(","),
            ]
        }
        VcardPropKind::Adr => {
            let adr = ADR::decode(line, version);
            vec![
                adr.po_box.join(","),
                adr.extended.join(","),
                adr.street.join(","),
                adr.locality.join(","),
                adr.region.join(","),
                adr.postal_code.join(","),
                adr.country.join(","),
            ]
        }
        VcardPropKind::Gender => {
            let gender = GENDER::decode(line, version);
            vec![gender.sex.to_string(), gender.identity.to_string()]
        }
        _ => return None,
    };

    let components: Vec<Value> = card_prop_labels(line.name.get())
        .iter()
        .zip(values)
        .map(|(label, value)| json!({ "name": label, "value": value }))
        .collect();
    Some(Value::Array(components))
}

/// One escaped item of a structured component (RFC 6350 3.4).
fn escape_component(item: &str) -> String {
    let mut out = String::with_capacity(item.len());
    for next in item.chars() {
        match next {
            '\\' => out.push_str("\\\\"),
            ';' => out.push_str("\\;"),
            ',' => out.push_str("\\,"),
            '\n' => out.push_str("\\n"),
            '\r' => {}
            _ => out.push(next),
        }
    }
    out
}

/// A parameter value, its separators and quotes dropped.
fn quote_param(value: &str) -> String {
    value
        .chars()
        .filter(|next| !matches!(next, '"' | ';' | ':' | ','))
        .collect()
}

/// Validates a hand-edited vCard source for the advanced editor: it
/// must reparse, and comes back re-serialized.
pub fn card_source(vcard: &str) -> Result<String, String> {
    let card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;
    Ok(String::from_utf8_lossy(&card.to_bytes()).into_owned())
}

/// Rewrites one raw property line for the advanced editor: `index`
/// replaces that line (a blank line removes it), -1 appends. The
/// result must reparse as a vCard; untouched lines keep their content,
/// re-emitted unfolded.
pub fn card_set_prop(vcard: &str, index: i64, line: &str) -> Result<String, String> {
    let card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;
    let mut lines: Vec<String> = card.props.iter().map(raw_line).collect();

    let line = line.trim();
    if index < 0 {
        if line.is_empty() {
            return Err("Empty property".into());
        }
        lines.push(line.to_string());
    } else {
        let index = index as usize;
        if index >= lines.len() {
            return Err("No such property".into());
        }
        if line.is_empty() {
            lines.remove(index);
        } else {
            lines[index] = line.to_string();
        }
    }

    let mut source = String::from("BEGIN:VCARD\r\n");
    for entry in &lines {
        source.push_str(entry);
        source.push_str("\r\n");
    }
    source.push_str("END:VCARD\r\n");

    let fresh = VcardCst::parse(&source).map_err(|err| format!("Invalid vCard: {err}"))?;
    Ok(String::from_utf8_lossy(&fresh.to_bytes()).into_owned())
}

/// One property as a single unfolded content line, EOL dropped.
fn raw_line(line: &VcardLine) -> String {
    let text = line.to_string();
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();

    while let Some(next) = chars.next() {
        if next == '\r' || next == '\n' {
            if next == '\r' && chars.peek() == Some(&'\n') {
                chars.next();
            }
            // NOTE: a fold break eats the continuation's leading blank.
            if matches!(chars.peek(), Some(' ') | Some('\t')) {
                chars.next();
            }
            continue;
        }
        out.push(next);
    }

    out
}
