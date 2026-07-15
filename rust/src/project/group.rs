//! The merged-view grouping: clusters the replica pool into contacts
//! and reports the duplicate review's group facts.

use std::collections::HashMap;

use serde_json::{Value, json};

use crate::project::strings;

/// Groups the replica pool into merged contacts (docs/merged-view.md).
/// Each replica's natural key is its vCard UID (`uid\0<uid>`), or the
/// replica itself (`ref\0<ref>`) when it has none or the user detached
/// it, and a link row rebinds a natural key to the cluster the user
/// linked it into. Takes `{"replicas": [{ref, uid, name, id}],
/// "links": {member: cluster}, "detached": [ref]}` and returns
/// `{"groups": [{key, replicas: [index]}]}`: the groups sorted by
/// their primary replica's display name (case-insensitive, the id
/// standing in for a nameless card), the members in pool order.
pub fn group_contacts(input: &Value) -> Result<Value, String> {
    let replicas = input
        .get("replicas")
        .and_then(Value::as_array)
        .ok_or_else(|| "Invalid replica pool: no replicas array".to_string())?;
    let links = input.get("links").and_then(Value::as_object);
    let detached = strings(input.get("detached"));

    let mut order: Vec<String> = Vec::new();
    let mut members: HashMap<String, Vec<usize>> = HashMap::new();
    for (index, replica) in replicas.iter().enumerate() {
        let reference = raw(replica, "ref");
        let uid = raw(replica, "uid");

        let natural = if uid.is_empty() || detached.contains(&reference) {
            format!("ref\0{reference}")
        } else {
            format!("uid\0{uid}")
        };
        let key = links
            .and_then(|map| map.get(&natural))
            .and_then(Value::as_str)
            .unwrap_or(&natural)
            .to_string();

        members
            .entry(key.clone())
            .or_insert_with(|| {
                order.push(key);
                Vec::new()
            })
            .push(index);
    }

    order.sort_by_cached_key(|key| {
        let primary = &replicas[members[key][0]];
        let name = raw(primary, "name");
        let shown = if name.is_empty() {
            raw(primary, "id")
        } else {
            name
        };
        shown.to_lowercase()
    });

    let groups: Vec<Value> = order
        .into_iter()
        .map(|key| json!({ "replicas": members[&key], "key": key }))
        .collect();
    Ok(json!({ "groups": groups }))
}

/// The duplicate review's group facts: the dismissal key (the sorted
/// distinct replica refs, joined by the unit separator; the store
/// persists it, so the format is frozen) and whether Link may be
/// offered (at least two cards, each in its own addressbook: a
/// same-book pair sharing a UID would collide). Takes `[{ref, book}]`,
/// returns `{key, linkable}`.
pub fn duplicate_group(members: &Value) -> Result<Value, String> {
    let members = members
        .as_array()
        .ok_or_else(|| "Invalid duplicate group: not an array".to_string())?;

    let mut refs: Vec<String> = Vec::new();
    let mut ref_by_book: HashMap<String, String> = HashMap::new();
    let mut linkable = true;
    for member in members {
        let reference = raw(member, "ref");
        let book = raw(member, "book");

        if !refs.contains(&reference) {
            refs.push(reference.clone());
        }
        match ref_by_book.get(&book) {
            Some(other) if other != &reference => linkable = false,
            Some(_) => {}
            None => {
                ref_by_book.insert(book, reference);
            }
        }
    }

    linkable = linkable && refs.len() >= 2;
    refs.sort();
    Ok(json!({ "key": refs.join("\u{1f}"), "linkable": linkable }))
}

/// A string field read verbatim (refs carry meaningful bytes; the
/// trimming [`field`](crate::project::field) does would corrupt them).
fn raw(entry: &Value, key: &str) -> String {
    entry
        .get(key)
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string()
}
