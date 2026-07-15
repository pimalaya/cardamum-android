//! Union-find duplicate detection for the semi-automatic duplicate
//! remover.

use std::collections::HashMap;

use serde_json::{Value, json};

use crate::project::{array, display_name, field, project, single_field};

/// Finds groups of likely duplicates for the semi-automatic duplicate
/// remover. Input: `[{"ref", "vcard"}, ...]`. Cards group on an exact
/// normalized match (emails lowercased; phones digits-only, compared
/// on their trailing digits so a country code does not split a match;
/// full names casefolded, whitespace collapsed), conservative on
/// purpose: no fuzzy matching, false positives are the killer here.
/// Cards already sharing a UID are one contact and never group with
/// each other alone. Returns `{"groups": [{"refs": [...], "reasons":
/// [...]}]}`.
pub fn find_duplicates(cards: &[(String, String)]) -> Result<Value, String> {
    let mut uids: Vec<String> = Vec::new();
    let mut keys: Vec<Vec<(&'static str, String)>> = Vec::new();
    for (_, vcard) in cards {
        let model = project(vcard)?;
        uids.push(single_field(&model, "uid").to_string());
        keys.push(match_keys(&model));
    }

    // NOTE: union-find over the cards, joined by shared match keys.
    let mut parent: Vec<usize> = (0..cards.len()).collect();
    let mut reasons: Vec<(usize, &'static str)> = Vec::new();
    let mut seen: HashMap<String, usize> = HashMap::new();
    for (index, card_keys) in keys.iter().enumerate() {
        for (reason, key) in card_keys {
            match seen.get(key.as_str()) {
                None => {
                    seen.insert(key.clone(), index);
                }
                Some(&other) => {
                    let (a, b) = (find(&mut parent, index), find(&mut parent, other));
                    if a != b {
                        parent[a] = b;
                    }
                    reasons.push((find(&mut parent, index), reason));
                }
            }
        }
    }

    let mut groups: HashMap<usize, Vec<usize>> = HashMap::new();
    for index in 0..cards.len() {
        groups
            .entry(find(&mut parent, index))
            .or_default()
            .push(index);
    }

    let mut out = Vec::new();
    let mut roots: Vec<usize> = groups.keys().copied().collect();
    roots.sort_unstable();
    for root in roots {
        let members = &groups[&root];
        if members.len() < 2 {
            continue;
        }

        // NOTE: members sharing a UID are one logical contact, so a
        // group must span at least two distinct contacts to matter.
        let mut contacts: Vec<&str> = Vec::new();
        for &member in members {
            let uid = uids[member].as_str();
            let key = if uid.is_empty() {
                cards[member].0.as_str()
            } else {
                uid
            };
            if !contacts.contains(&key) {
                contacts.push(key);
            }
        }
        if contacts.len() < 2 {
            continue;
        }

        let refs: Vec<&str> = members
            .iter()
            .map(|&member| cards[member].0.as_str())
            .collect();
        let mut group_reasons: Vec<&str> = Vec::new();
        for (reason_root, reason) in &reasons {
            if find(&mut parent, *reason_root) == root && !group_reasons.contains(reason) {
                group_reasons.push(reason);
            }
        }
        out.push(json!({ "refs": refs, "reasons": group_reasons }));
    }

    Ok(json!({ "groups": out }))
}

/// The card's exact-match keys: every email, every phone, the name.
fn match_keys(model: &Value) -> Vec<(&'static str, String)> {
    let mut keys = Vec::new();

    for email in array(model.get("emails")) {
        let address = field(email, "address").to_lowercase();
        if !address.is_empty() {
            keys.push(("email", format!("e\u{1f}{address}")));
        }
    }

    for phone in array(model.get("phones")) {
        let digits: String = field(phone, "number")
            .chars()
            .filter(char::is_ascii_digit)
            .collect();
        // NOTE: the trailing digits absorb a present-vs-absent country
        // code (the AOSP aggregator's rule); short numbers match whole.
        if digits.len() >= 6 {
            let tail = &digits[digits.len().saturating_sub(8)..];
            keys.push(("phone", format!("p\u{1f}{tail}")));
        }
    }

    let name = display_name(model)
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if !name.is_empty() {
        keys.push(("name", format!("n\u{1f}{name}")));
    }

    keys
}

/// Union-find root lookup with path compression.
fn find(parent: &mut [usize], mut index: usize) -> usize {
    while parent[index] != index {
        parent[index] = parent[parent[index]];
        index = parent[index];
    }
    index
}
