//! The merge and conflict resolution group: three-way conflict merges,
//! the multi-card union, and the list dedup they lean on.

use core::str::FromStr;

use serde_json::{Map, Value, json};
use vcard::{
    prop::VcardPropKind,
    tree::{
        cst::VcardCst,
        merge::merge,
        prop::{VcardPropLens, rev::REV, uid::UID},
    },
};

use crate::project::{project, single_field, sort_arrays, text_prop};

/// The model's list sections, compared across merged cards for the
/// conflict view's changed set.
const LIST_FIELDS: &[&str] = &[
    "phones",
    "emails",
    "addresses",
    "websites",
    "nicknames",
    "notes",
    "impps",
    "languages",
    "relations",
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
    "anniversary",
    "gender.sex",
    "gender.identity",
];

/// Three-way merges a conflicted push: the staged local edit and the
/// fetched remote card, against the base both derive from. The local
/// side wins same-field collisions (it carries the user's explicit
/// edit), every other remote change flows in, and an update always
/// beats a removal (the vcard-rs merge rules), so nothing is silently
/// lost. An empty base means unknown (never captured): the remote side
/// stands in as the base, so against it the local edit reads as the
/// change and rides through unscathed while the remote value drops out
/// (merging against `local` would invert this and let the remote win).
/// Returns the merged card and the collision count.
pub fn merge_conflict(base: &str, local: &str, remote: &str) -> Result<Value, String> {
    let base = if base.is_empty() { remote } else { base };
    let base = VcardCst::parse(base).map_err(|err| format!("Invalid vCard: {err}"))?;
    let local = VcardCst::parse(local).map_err(|err| format!("Invalid vCard: {err}"))?;
    let remote = VcardCst::parse(remote).map_err(|err| format!("Invalid vCard: {err}"))?;

    let report = merge(&base, &local, &remote);

    Ok(json!({
        "vcard": report.merged.to_string(),
        "conflicts": report.conflicts.len(),
    }))
}

/// Builds the conflict form's inputs for a both-sides-edited row, so the
/// user resolves the divergence by hand. Same shape as [`merge_cards`]
/// (`{"vcard", "model", "alternatives", "changed"}`):
///
/// - `vcard`/`model`: the three-way merge of `local` and `remote` against
///   their `base`, with the newer side (by `REV`) winning genuine
///   collisions, so its value is the form's pre-filled default; lists
///   set-merge and non-conflicting scalars fold in.
/// - `alternatives`: for every single field both sides moved away from the
///   base to different values, its two candidates (the winner's first), so
///   the form offers one chip per choice. Lists never conflict, so
///   `changed` is always empty; it stays non-null, which turns the form's
///   conflict mode on. An empty `alternatives` means nothing needs the
///   user, and the caller may stage `vcard` straight away.
pub fn merge_conflict_form(base: &str, local: &str, remote: &str) -> Result<Value, String> {
    let remote_newer = match (rev_seconds(remote), rev_seconds(local)) {
        (Some(remote), Some(local)) => remote > local,
        _ => false,
    };
    let (winner, loser) = if remote_newer {
        (remote, local)
    } else {
        (local, remote)
    };

    // NOTE: with no ancestor, merging against the loser lets the
    // winner dominate; a real base gives a true three-way.
    let base_for_merge = if base.is_empty() { loser } else { base };
    let merged = {
        let base_cst =
            VcardCst::parse(base_for_merge).map_err(|err| format!("Invalid vCard: {err}"))?;
        let left = VcardCst::parse(winner).map_err(|err| format!("Invalid vCard: {err}"))?;
        let right = VcardCst::parse(loser).map_err(|err| format!("Invalid vCard: {err}"))?;
        merge(&base_cst, &left, &right).merged.to_string()
    };

    let mut model = project(&merged)?;
    dedup_lists(&mut model);

    let base_model = if base.is_empty() {
        Value::Object(Map::new())
    } else {
        project(base)?
    };
    let winner_model = project(winner)?;
    let loser_model = project(loser)?;

    let mut alternatives = Map::new();
    for field in SINGLE_FIELDS {
        let base_value = single_field(&base_model, field);
        let winner_value = single_field(&winner_model, field);
        let loser_value = single_field(&loser_model, field);
        if winner_value != base_value && loser_value != base_value && winner_value != loser_value {
            alternatives.insert((*field).into(), json!([winner_value, loser_value]));
        }
    }

    // NOTE: the auto-resolve verdict, owned here so the sync pass and
    // the tap-time form share one decision; no collision means the
    // caller may stage `merged` straight away.
    let resolved = alternatives.is_empty();

    Ok(json!({
        "vcard": merged,
        "model": model,
        "alternatives": Value::Object(alternatives),
        "changed": Vec::<&str>::new(),
        "resolved": resolved,
    }))
}

/// The card's REV as a comparable instant, `None` when absent or
/// unparseable.
fn rev_seconds(vcard: &str) -> Option<i64> {
    let card = VcardCst::parse(vcard).ok()?;
    let version = card.version();
    card.props
        .iter()
        .find_map(|line| match VcardPropKind::from_str(line.name.get()) {
            Ok(VcardPropKind::Rev) => REV::decode(line, version).to_unix_seconds(),
            _ => None,
        })
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
            if !values.contains(&value) {
                values.push(value);
            }
        }
        // NOTE: present-vs-absent is a conflict too, so the empty state
        // rides along as a pickable alternative for a field one card
        // carries and another lacks.
        if values.len() > 1 && values.iter().any(|value| !value.is_empty()) {
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
    dedup_entries(model, "relations", &["value"]);
    dedup_entries(
        model,
        "addresses",
        &[
            "pobox", "ext", "street", "city", "region", "postcode", "country",
        ],
    );
    for list in [
        "websites",
        "nicknames",
        "notes",
        "categories",
        "impps",
        "languages",
    ] {
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
