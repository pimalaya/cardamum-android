//! The CardStore's engine-facing policy, as pure decisions over JSON
//! facts.
//!
//! The Java store owns SQLite and gathers row facts; every decision
//! about them lives here: the placement codec mapping card and
//! membership rows to io-offline placements ([`placement`],
//! [`phone_placement`]) and engine upserts back to row writes
//! ([`upsert_plan`], [`phone_upsert_plan`], [`phone_drop_plan`]), the
//! driver's push planning ([`push_plan`]), the account-wide delta
//! projection ([`account_snapshot`]) and the CardDAV If-Match quirk
//! retry ([`retry_unguarded`]). Bodies stay content-addressed by
//! [`byte_hash`], the same SHA-256 hex the Java store computes.

use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};

use crate::account::{self, Backend};

/// Membership state of a staged addition awaiting its push.
const MEMBER_ADDED: i64 = 1;

/// Membership state of a staged removal awaiting its push.
const MEMBER_REMOVED: i64 = 2;

/// The SHA-256 hex of a body, the store's content address (identical
/// to the Java CardStore.byteHash).
pub fn byte_hash(text: &str) -> String {
    let hashed = Sha256::digest(text.as_bytes());
    hashed.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// Whether a 412-rejected push may retry unguarded: the last enumerate
/// proves the handle unchanged at the staged base revision, listed
/// with that very ETag or unlisted by a delta (a delta only lists what
/// changed). Some servers, posteo's SabreDAV among them, serve listing
/// ETags their If-Match never matches. Takes `{"listed": {handle:
/// etag}?, "complete": bool, "handle", "ifMatch"?}`.
pub fn retry_unguarded(facts: &Value) -> bool {
    let Some(listed) = facts.get("listed").and_then(Value::as_object) else {
        return false;
    };
    let Some(if_match) = facts.get("ifMatch").and_then(Value::as_str) else {
        return false;
    };
    let handle = str_field(facts, "handle");

    match listed.get(handle) {
        Some(etag) => etag.as_str() == Some(if_match),
        None => !bool_field(facts, "complete"),
    }
}

/// Projects an account-wide delta (JMAP, Google) onto one book's
/// enumerate: cards member of the book are its items, and on an
/// incremental round a changed card that left the book (still held
/// locally but no longer listing it) rides as vanished. Takes
/// `{"bookId"?, "complete": bool, "changed": [{"handle", "books",
/// "known": bool}], "vanished": [handle]}`, returns `{"members":
/// [index], "vanished": [handle]}`.
pub fn account_snapshot(facts: &Value) -> Result<Value, String> {
    let changed = facts
        .get("changed")
        .and_then(Value::as_array)
        .ok_or_else(|| "Invalid account delta: no changed array".to_string())?;
    let book_id = facts.get("bookId").and_then(Value::as_str);
    let complete = bool_field(facts, "complete");

    let mut members: Vec<usize> = Vec::new();
    let mut vanished: Vec<String> = facts
        .get("vanished")
        .and_then(Value::as_array)
        .map(|handles| {
            handles
                .iter()
                .filter_map(Value::as_str)
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default();

    for (index, card) in changed.iter().enumerate() {
        let books = card.get("books").and_then(Value::as_array);
        let member = match (book_id, books) {
            (Some(id), Some(books)) => books.iter().any(|book| book.as_str() == Some(id)),
            _ => false,
        };

        if member {
            members.push(index);
        } else if !complete && bool_field(card, "known") {
            vanished.push(str_field(card, "handle").to_string());
        }
    }

    Ok(json!({ "members": members, "vanished": vanished }))
}

/// Plans one push change: a pending create is a membership patch when
/// the body already lives on the account (add with an origin, on an
/// account-level backend), a genuine create otherwise (a Google create
/// lands in the myContacts system group, so a create aimed at another
/// group patches the membership right after); a staged removal is a
/// membership patch when the card is not deleted on an account-level
/// backend, the card's deletion otherwise. Takes `{"op": "add" |
/// "remove", "collection", "bookId"?, "origin": bool, "deleted":
/// bool}`, returns `{"action": "membership" | "create" | "delete",
/// "postCreateBooks"?: [bookId]}`.
pub fn push_plan(facts: &Value) -> Result<Value, String> {
    let collection = str_field(facts, "collection");
    let backend = Backend::of(collection);
    let book_id = facts.get("bookId").and_then(Value::as_str);

    match str_field(facts, "op") {
        "add" => {
            if bool_field(facts, "origin") && backend.account_level() {
                return Ok(json!({ "action": "membership" }));
            }

            let mut plan = Map::new();
            plan.insert("action".into(), "create".into());
            if backend == Backend::Google {
                if let Some(book_id) = book_id {
                    if book_id != "myContacts" {
                        plan.insert("postCreateBooks".into(), json!([book_id]));
                    }
                }
            }
            Ok(Value::Object(plan))
        }
        "remove" => {
            if backend.account_level() && !bool_field(facts, "deleted") {
                Ok(json!({ "action": "membership" }))
            } else {
                Ok(json!({ "action": "delete" }))
            }
        }
        op => Err(format!("Unknown push op `{op}`")),
    }
}

/// Maps one card-plus-membership row to its engine placement on the
/// server axis, [`None`] when the row surfaces nowhere (a never-synced
/// create only surfaces in the first of its memberships, so several
/// memberships never fan out into several server creates). Takes
/// `{"collection", "row": {key, id, uri?, etag?, vcard, baseVcard?,
/// dirty, deleted, conflictRevision?, stale, memberState, name, email,
/// phone, info, uid, hash}, "firstMembershipUrl"?, "originUrl"?}`
/// (`originUrl` being a synced membership outside the collection).
pub fn placement(facts: &Value) -> Result<Option<Value>, String> {
    let collection = str_field(facts, "collection");
    let row = facts
        .get("row")
        .ok_or_else(|| "Invalid placement facts: no row".to_string())?;

    let etag = row.get("etag").and_then(Value::as_str);
    let base_vcard = row.get("baseVcard").and_then(Value::as_str);
    let vcard = str_field(row, "vcard");
    let dirty = bool_field(row, "dirty");
    let deleted = bool_field(row, "deleted");
    let conflict_revision = row.get("conflictRevision").and_then(Value::as_str);
    let stale = bool_field(row, "stale");
    let member_state = int_field(row, "memberState");
    let uid = str_field(row, "uid");

    // A pending create is a row that never reached the server: no
    // revision and no captured base. The revision alone is not the
    // marker, since a server may confirm a push without an ETag.
    let never_synced = etag.is_none() && base_vcard.is_none() && dirty;

    if never_synced
        && !deleted
        && facts.get("firstMembershipUrl").and_then(Value::as_str) != Some(collection)
    {
        return Ok(None);
    }

    let handle = account::addressing_key(
        Backend::of(collection),
        str_field(row, "id"),
        row.get("uri").and_then(Value::as_str).unwrap_or(""),
    );

    let (status, origin) = if deleted || member_state == MEMBER_REMOVED {
        ("tombstone", None)
    } else if member_state == MEMBER_ADDED || never_synced {
        // A staged membership addition (not a never-synced create):
        // the body already lives under another book, so the push
        // patches memberships instead of re-uploading.
        let origin = (!never_synced)
            .then(|| facts.get("originUrl").and_then(Value::as_str))
            .flatten()
            .map(|url| json!({ "collection": url, "handle": handle }));
        ("created", origin)
    } else if conflict_revision.is_some() {
        ("conflict", None)
    } else if dirty {
        ("dirty", None)
    } else {
        ("clean", None)
    };

    let mut placement = Map::new();
    placement.insert("collection".into(), collection.into());
    placement.insert("handle".into(), handle.clone().into());
    placement.insert(
        "linkId".into(),
        if uid.is_empty() {
            handle.clone()
        } else {
            uid.to_string()
        }
        .into(),
    );
    placement.insert("status".into(), status.into());
    if let Some(origin) = origin {
        placement.insert("origin".into(), origin);
    }
    if let Some(revision) = conflict_revision {
        placement.insert("conflictRevision".into(), revision.into());
    }

    insert_levels(&mut placement, row, vcard, stale);

    if status != "created" {
        let mut base = Map::new();
        if let Some(etag) = etag {
            base.insert("revision".into(), etag.into());
        }
        if !stale && !vcard.is_empty() {
            base.insert(
                "object".into(),
                byte_hash(base_vcard.unwrap_or(vcard)).into(),
            );
        }
        placement.insert("base".into(), Value::Object(base));
    }

    Ok(Some(Value::Object(placement)))
}

/// Maps one card-plus-membership row to its phone-axis placement,
/// [`None`] when there is nothing to reconcile phone-side (removed
/// before ever reaching the phone, or a spine row still awaiting its
/// first body). The phone handle is the card id (what SOURCE_ID
/// carries); the status ladder derives from the shared deleted flag
/// and the phone axis alone, so the server axis never leaks into this
/// spoke. Takes `{"collection", "row": {id, vcard, deleted, uid,
/// memberState, phoneRevision?, phoneBase?, phoneStale,
/// phoneConflict?, name, email, phone, info, hash}}`.
pub fn phone_placement(facts: &Value) -> Result<Option<Value>, String> {
    let collection = str_field(facts, "collection");
    let row = facts
        .get("row")
        .ok_or_else(|| "Invalid placement facts: no row".to_string())?;

    let id = str_field(row, "id");
    let vcard = str_field(row, "vcard");
    let deleted = bool_field(row, "deleted");
    let uid = str_field(row, "uid");
    let member_state = int_field(row, "memberState");
    let phone_revision = row.get("phoneRevision").and_then(Value::as_str);
    let phone_base = row.get("phoneBase").and_then(Value::as_str);
    let phone_stale = bool_field(row, "phoneStale");
    let phone_conflict = row.get("phoneConflict").and_then(Value::as_str);

    let on_phone = phone_revision.is_some() || phone_base.is_some();
    let removed = deleted || member_state == MEMBER_REMOVED;

    if !on_phone && (removed || vcard.is_empty()) {
        return Ok(None);
    }

    let status = if removed {
        "tombstone"
    } else if phone_conflict.is_some() {
        "conflict"
    } else if !on_phone {
        "created"
    } else if !vcard.is_empty() && phone_base.is_none_or(|base| base != vcard) {
        // On the phone, but the base body differs from the current card,
        // or is unknown (a lost base). Either way the phone has not yet
        // seen this content, so it is dirty and pushes: a lost base is
        // re-established from the push instead of being assumed clean,
        // which would let a stale phone value pull back and silently win
        // over a hub edit the phone never received.
        "dirty"
    } else {
        "clean"
    };

    let mut placement = Map::new();
    placement.insert("collection".into(), collection.into());
    placement.insert("handle".into(), id.into());
    placement.insert(
        "linkId".into(),
        if uid.is_empty() { id } else { uid }.into(),
    );
    placement.insert("status".into(), status.into());
    if let Some(revision) = phone_conflict {
        placement.insert("conflictRevision".into(), revision.into());
    }

    insert_levels(&mut placement, row, vcard, phone_stale);

    if status != "created" {
        let mut base = Map::new();
        if let Some(revision) = phone_revision {
            base.insert("revision".into(), revision.into());
        }
        if !phone_stale {
            if let Some(phone_base) = phone_base {
                base.insert("object".into(), byte_hash(phone_base).into());
            }
        }
        placement.insert("base".into(), Value::Object(base));
    }

    Ok(Some(Value::Object(placement)))
}

/// Plans one engine upsert onto the card and membership rows. Takes
/// `{"collection", "status", "origin": bool, "conflictRevision"?,
/// "handle", "exists": bool, "existingEtag"?, "existingVcard"?,
/// "otherMemberships": int, "body"? (the placement object's resolved
/// body), "baseRevision"?, "baseObjectHash"?}` and returns one of:
/// `{"action": "removeMembership"}` (only this book's membership goes,
/// the card stays), `{"action": "deleteCard"}`, `{"action":
/// "addMembership"}` (a staged membership addition, the row itself
/// untouched), or `{"action": "upsert", "row": {id, vcard, stale,
/// etag?, dirty, conflictRevision?, baseVcardHash?}, "memberState"}`;
/// a `baseVcardHash` asks the store to resolve and persist that body
/// as the push base (the base object differs from the held content).
pub fn upsert_plan(facts: &Value) -> Result<Value, String> {
    let collection = str_field(facts, "collection");
    let status = str_field(facts, "status");
    let account_level = Backend::of(collection).account_level();

    if status == "tombstone" {
        if account_level && int_field(facts, "otherMemberships") > 0 {
            return Ok(json!({ "action": "removeMembership" }));
        }
        return Ok(json!({ "action": "deleteCard" }));
    }

    let origin = bool_field(facts, "origin");
    if status == "created"
        && account_level
        && origin
        && bool_field(facts, "exists")
        && facts.get("existingEtag").and_then(Value::as_str).is_some()
    {
        return Ok(json!({ "action": "addMembership" }));
    }

    let body = facts.get("body").and_then(Value::as_str);
    let existing = facts.get("existingVcard").and_then(Value::as_str);

    // No current body (a probed spine row, or a remote content change
    // whose refetch is pending): any body already held stays as a
    // stale display copy until the next upgrade.
    let (vcard, stale) = match body {
        Some(body) => (body, false),
        None => {
            let held = existing.unwrap_or("");
            (held, !held.is_empty())
        }
    };

    let handle = str_field(facts, "handle");
    let mut row = Map::new();
    row.insert(
        "id".into(),
        display_id(collection, handle).to_string().into(),
    );
    row.insert("vcard".into(), vcard.into());
    row.insert("stale".into(), stale.into());
    if let Some(revision) = facts.get("baseRevision").and_then(Value::as_str) {
        row.insert("etag".into(), revision.into());
    }

    if let Some(base_object) = facts.get("baseObjectHash").and_then(Value::as_str) {
        if base_object != byte_hash(vcard) {
            row.insert("baseVcardHash".into(), base_object.into());
        }
    }

    match status {
        "clean" => {
            row.insert("dirty".into(), false.into());
        }
        "conflict" => {
            row.insert("dirty".into(), true.into());
            if let Some(revision) = facts.get("conflictRevision").and_then(Value::as_str) {
                row.insert("conflictRevision".into(), revision.into());
            }
        }
        // dirty and created rows both await a push.
        _ => {
            row.insert("dirty".into(), true.into());
        }
    }

    let member_state = if status == "created" && origin {
        "added"
    } else {
        "synced"
    };
    Ok(json!({ "action": "upsert", "row": row, "memberState": member_state }))
}

/// Plans one phone-axis upsert. A body differing from the held vCard
/// is a phone-won change, so the plan also stages the server push
/// (capture the push base, mark dirty); a phone-created card lands as
/// a hub row the server has never seen. Takes `{"collection",
/// "status", "conflictRevision"?, "exists": bool, "existingVcard"?,
/// "existingDirty": bool, "objectPresent": bool, "body"?,
/// "basePresent": bool, "baseRevision"?, "baseObjectHash"?}` and
/// returns `{"action": "skip" | "insert" | "update" | "axisOnly",
/// "row"?: {vcard, captureBase?}, "axis": {phoneRevision (null clears),
/// "phoneBaseHash"?, "phoneStale", "phoneConflict"?}}`; the axis
/// always applies, `phoneBaseHash` asking the store to resolve and
/// persist that body on the phone axis.
pub fn phone_upsert_plan(facts: &Value) -> Result<Value, String> {
    let status = str_field(facts, "status");
    if status == "tombstone" {
        // NOTE: phone deletes arrive as drops; a tombstone upsert is
        // not part of the phone flow.
        return Ok(json!({ "action": "skip" }));
    }

    let exists = bool_field(facts, "exists");
    let body = facts.get("body").and_then(Value::as_str);
    let existing = facts.get("existingVcard").and_then(Value::as_str);
    let changed = body.is_some_and(|body| Some(body) != existing);

    let mut plan = Map::new();
    if !exists {
        plan.insert("action".into(), "insert".into());
        plan.insert("row".into(), json!({ "vcard": body.unwrap_or("") }));
    } else if changed {
        let mut row = Map::new();
        row.insert("vcard".into(), body.unwrap_or("").into());
        if !bool_field(facts, "existingDirty") {
            // First divergence from the server state: the held vCard
            // becomes the server push base (same capture as an in-app
            // edit).
            row.insert("captureBase".into(), true.into());
        }
        plan.insert("action".into(), "update".into());
        plan.insert("row".into(), Value::Object(row));
    } else {
        plan.insert("action".into(), "axisOnly".into());
    }

    let mut axis = Map::new();
    if bool_field(facts, "basePresent") {
        axis.insert(
            "phoneRevision".into(),
            facts
                .get("baseRevision")
                .and_then(Value::as_str)
                .map(Into::into)
                .unwrap_or(Value::Null),
        );
        if let Some(hash) = facts.get("baseObjectHash").and_then(Value::as_str) {
            axis.insert("phoneBaseHash".into(), hash.into());
        }
    } else {
        axis.insert("phoneRevision".into(), Value::Null);
    }
    axis.insert(
        "phoneStale".into(),
        (!bool_field(facts, "objectPresent") && existing.is_some_and(|held| !held.is_empty()))
            .into(),
    );
    if status == "conflict" {
        if let Some(revision) = facts.get("conflictRevision").and_then(Value::as_str) {
            axis.insert("phoneConflict".into(), revision.into());
        }
    }
    plan.insert("axis".into(), Value::Object(axis));

    Ok(Value::Object(plan))
}

/// Plans a phone-collection drop (the raw contact vanished in a
/// contacts app), staging the deletion hub-side: on the account-level
/// backends the membership goes when other books still hold the card,
/// the card itself otherwise. Takes `{"collection" (the server URL),
/// "deleted": bool, "otherMemberships": int}`, returns `{"action":
/// "removeMembership" | "deleteCard"}`.
pub fn phone_drop_plan(facts: &Value) -> Result<Value, String> {
    let collection = str_field(facts, "collection");

    if Backend::of(collection).account_level()
        && !bool_field(facts, "deleted")
        && int_field(facts, "otherMemberships") > 0
    {
        return Ok(json!({ "action": "removeMembership" }));
    }
    Ok(json!({ "action": "deleteCard" }))
}

/// The `level` tier of a placement, plus its meta and content hash:
/// probed for a bodiless spine row, meta for a stale display copy,
/// full (with the object hash) otherwise.
fn insert_levels(placement: &mut Map<String, Value>, row: &Value, vcard: &str, stale: bool) {
    if vcard.is_empty() {
        placement.insert("level".into(), "probed".into());
        return;
    }

    let meta = json!({
        "name": str_field(row, "name"),
        "email": str_field(row, "email"),
        "phone": str_field(row, "phone"),
        "info": str_field(row, "info"),
        "uid": str_field(row, "uid"),
        "hash": str_field(row, "hash"),
    });
    placement.insert("meta".into(), meta.to_string().into());

    if stale {
        placement.insert("level".into(), "meta".into());
    } else {
        placement.insert("level".into(), "full".into());
        placement.insert("object".into(), byte_hash(vcard).into());
    }
}

/// The display id behind an engine handle (CardDAV resource names
/// carry a `.vcf` suffix the id strips).
fn display_id<'a>(collection: &str, handle: &'a str) -> &'a str {
    if Backend::of(collection) == Backend::Carddav {
        return handle.strip_suffix(".vcf").unwrap_or(handle);
    }
    handle
}

fn str_field<'m>(value: &'m Value, key: &str) -> &'m str {
    value.get(key).and_then(Value::as_str).unwrap_or("")
}

fn bool_field(value: &Value, key: &str) -> bool {
    value.get(key).and_then(Value::as_bool).unwrap_or(false)
}

fn int_field(value: &Value, key: &str) -> i64 {
    value.get(key).and_then(Value::as_i64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The content address matches the Java store's SHA-256 hex.
    #[test]
    fn byte_hash_matches_the_java_store() {
        assert_eq!(
            byte_hash(""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        );
        assert_eq!(
            byte_hash("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        );
    }

    /// A listed matching ETag proves the remote unchanged, a listed
    /// mismatch disproves it, and an unlisted handle only counts on an
    /// incremental round.
    #[test]
    fn retry_unguarded_reads_the_listing() {
        let listed = json!({
            "listed": { "a.vcf": "e1" }, "complete": true,
            "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(retry_unguarded(&listed));

        let mismatch = json!({
            "listed": { "a.vcf": "e2" }, "complete": false,
            "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(!retry_unguarded(&mismatch));

        let unlisted_delta = json!({
            "listed": {}, "complete": false, "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(retry_unguarded(&unlisted_delta));

        let unlisted_complete = json!({
            "listed": {}, "complete": true, "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(!retry_unguarded(&unlisted_complete));

        let no_listing = json!({ "complete": false, "handle": "a.vcf", "ifMatch": "e1" });
        assert!(!retry_unguarded(&no_listing));

        let no_guard = json!({ "listed": {}, "complete": false, "handle": "a.vcf" });
        assert!(!retry_unguarded(&no_guard));
    }

    /// Members of the book are items; on an incremental round a known
    /// card that left the book rides as vanished, an unknown one is
    /// skipped.
    #[test]
    fn account_snapshot_projects_the_delta() {
        let facts = json!({
            "bookId": "b1",
            "complete": false,
            "changed": [
                { "handle": "c1", "books": ["b1", "b2"], "known": true },
                { "handle": "c2", "books": ["b2"], "known": true },
                { "handle": "c3", "books": ["b2"], "known": false },
            ],
            "vanished": ["gone"],
        });

        let reply = account_snapshot(&facts).unwrap();
        assert_eq!(reply["members"], json!([0]));
        assert_eq!(reply["vanished"], json!(["gone", "c2"]));

        let complete = json!({
            "bookId": "b1",
            "complete": true,
            "changed": [{ "handle": "c2", "books": ["b2"], "known": true }],
            "vanished": [],
        });
        assert_eq!(account_snapshot(&complete).unwrap()["vanished"], json!([]));
    }

    /// An origin add on an account-level backend patches memberships;
    /// a Google create aimed outside myContacts patches right after;
    /// removals patch memberships while other books hold the card.
    #[test]
    fn push_plan_decides_membership_vs_create_and_delete() {
        let origin_add = json!({
            "op": "add", "collection": "jmap://host/b1",
            "bookId": "b1", "origin": true, "deleted": false,
        });
        assert_eq!(push_plan(&origin_add).unwrap()["action"], "membership");

        let google_add = json!({
            "op": "add", "collection": "google://a@b/g1",
            "bookId": "g1", "origin": false, "deleted": false,
        });
        let plan = push_plan(&google_add).unwrap();
        assert_eq!(plan["action"], "create");
        assert_eq!(plan["postCreateBooks"], json!(["g1"]));

        let google_default = json!({
            "op": "add", "collection": "google://a@b/contacts",
            "bookId": "myContacts", "origin": false, "deleted": false,
        });
        assert!(
            push_plan(&google_default)
                .unwrap()
                .get("postCreateBooks")
                .is_none()
        );

        let carddav_add = json!({
            "op": "add", "collection": "https://dav/b1",
            "origin": true, "deleted": false,
        });
        assert_eq!(push_plan(&carddav_add).unwrap()["action"], "create");

        let membership_remove = json!({
            "op": "remove", "collection": "jmap://host/b1",
            "bookId": "b1", "origin": false, "deleted": false,
        });
        assert_eq!(
            push_plan(&membership_remove).unwrap()["action"],
            "membership"
        );

        let deleted_remove = json!({
            "op": "remove", "collection": "jmap://host/b1",
            "bookId": "b1", "origin": false, "deleted": true,
        });
        assert_eq!(push_plan(&deleted_remove).unwrap()["action"], "delete");
    }

    /// A clean synced row maps to a full placement with its base; a
    /// never-synced create surfaces only in its first membership; a
    /// staged membership addition carries its origin.
    #[test]
    fn placement_maps_the_status_ladder() {
        let clean = json!({
            "collection": "https://dav/b1",
            "row": {
                "key": "k", "id": "c1", "uri": "c1.vcf", "etag": "e1",
                "vcard": "BODY", "dirty": false, "deleted": false,
                "stale": false, "memberState": 0,
                "name": "N", "email": "E", "phone": "P", "info": "I",
                "uid": "u1", "hash": "h1",
            },
            "firstMembershipUrl": "https://dav/b1",
        });
        let placement = placement(&clean).unwrap().unwrap();
        assert_eq!(placement["status"], "clean");
        assert_eq!(placement["handle"], "c1.vcf");
        assert_eq!(placement["linkId"], "u1");
        assert_eq!(placement["level"], "full");
        assert_eq!(placement["object"], byte_hash("BODY"));
        assert_eq!(placement["base"]["revision"], "e1");
        assert_eq!(placement["base"]["object"], byte_hash("BODY"));

        let elsewhere = json!({
            "collection": "jmap://host/b2",
            "row": {
                "key": "k", "id": "c1", "vcard": "BODY", "dirty": true,
                "deleted": false, "stale": false, "memberState": 0,
                "name": "", "email": "", "phone": "", "info": "",
                "uid": "", "hash": "h",
            },
            "firstMembershipUrl": "jmap://host/b1",
        });
        assert!(super::placement(&elsewhere).unwrap().is_none());

        let added = json!({
            "collection": "jmap://host/b2",
            "row": {
                "key": "k", "id": "c1", "uri": "c1", "etag": "e1",
                "vcard": "BODY", "baseVcard": "OLD", "dirty": false,
                "deleted": false, "stale": false, "memberState": 1,
                "name": "", "email": "", "phone": "", "info": "",
                "uid": "u1", "hash": "h",
            },
            "firstMembershipUrl": "jmap://host/b1",
            "originUrl": "jmap://host/b1",
        });
        let placement = super::placement(&added).unwrap().unwrap();
        assert_eq!(placement["status"], "created");
        assert_eq!(placement["origin"]["collection"], "jmap://host/b1");
        assert!(placement.get("base").is_none());

        let stale = json!({
            "collection": "https://dav/b1",
            "row": {
                "key": "k", "id": "c1", "uri": "c1.vcf", "etag": "e2",
                "vcard": "BODY", "dirty": false, "deleted": false,
                "stale": true, "memberState": 0,
                "name": "", "email": "", "phone": "", "info": "",
                "uid": "", "hash": "h",
            },
            "firstMembershipUrl": "https://dav/b1",
        });
        let placement = super::placement(&stale).unwrap().unwrap();
        assert_eq!(placement["level"], "meta");
        assert_eq!(placement["linkId"], "c1.vcf");
        assert!(placement.get("object").is_none());
        assert!(placement["base"].get("object").is_none());
    }

    /// The phone axis surfaces only rows that reached the phone or
    /// await their first projection, and derives dirty from the phone
    /// base alone.
    #[test]
    fn phone_placement_reads_the_phone_axis() {
        let unhydrated = json!({
            "collection": "phone:https://dav/b1",
            "row": {
                "id": "c1", "vcard": "", "deleted": false, "uid": "",
                "memberState": 0, "phoneStale": false,
                "name": "", "email": "", "phone": "", "info": "", "hash": "",
            },
        });
        assert!(phone_placement(&unhydrated).unwrap().is_none());

        let fresh = json!({
            "collection": "phone:https://dav/b1",
            "row": {
                "id": "c1", "vcard": "BODY", "deleted": false, "uid": "u1",
                "memberState": 0, "phoneStale": false,
                "name": "", "email": "", "phone": "", "info": "", "hash": "",
            },
        });
        let placement = phone_placement(&fresh).unwrap().unwrap();
        assert_eq!(placement["status"], "created");
        assert_eq!(placement["handle"], "c1");
        assert!(placement.get("base").is_none());

        let diverged = json!({
            "collection": "phone:https://dav/b1",
            "row": {
                "id": "c1", "vcard": "NEW", "deleted": false, "uid": "u1",
                "memberState": 0, "phoneRevision": "5", "phoneBase": "OLD",
                "phoneStale": false,
                "name": "", "email": "", "phone": "", "info": "", "hash": "",
            },
        });
        let placement = phone_placement(&diverged).unwrap().unwrap();
        assert_eq!(placement["status"], "dirty");
        assert_eq!(placement["base"]["revision"], "5");
        assert_eq!(placement["base"]["object"], byte_hash("OLD"));
    }

    /// Tombstones split between membership removal and card deletion,
    /// origin creates become membership additions, and a stale row
    /// keeps its held body as a display copy.
    #[test]
    fn upsert_plan_maps_the_placement_back() {
        let tombstone_shared = json!({
            "collection": "jmap://host/b1", "status": "tombstone",
            "origin": false, "handle": "c1", "exists": true,
            "otherMemberships": 1,
        });
        assert_eq!(
            upsert_plan(&tombstone_shared).unwrap()["action"],
            "removeMembership",
        );

        let tombstone_last = json!({
            "collection": "https://dav/b1", "status": "tombstone",
            "origin": false, "handle": "c1.vcf", "exists": true,
            "otherMemberships": 3,
        });
        assert_eq!(
            upsert_plan(&tombstone_last).unwrap()["action"],
            "deleteCard"
        );

        let membership_add = json!({
            "collection": "jmap://host/b2", "status": "created",
            "origin": true, "handle": "c1", "exists": true,
            "existingEtag": "e1", "otherMemberships": 1,
        });
        assert_eq!(
            upsert_plan(&membership_add).unwrap()["action"],
            "addMembership"
        );

        let stale = json!({
            "collection": "https://dav/b1", "status": "clean",
            "origin": false, "handle": "c1.vcf", "exists": true,
            "existingVcard": "HELD", "otherMemberships": 0,
            "baseRevision": "e2", "baseObjectHash": byte_hash("HELD"),
        });
        let plan = upsert_plan(&stale).unwrap();
        assert_eq!(plan["action"], "upsert");
        assert_eq!(plan["row"]["id"], "c1");
        assert_eq!(plan["row"]["vcard"], "HELD");
        assert_eq!(plan["row"]["stale"], true);
        assert_eq!(plan["row"]["etag"], "e2");
        assert_eq!(plan["row"]["dirty"], false);
        assert!(plan["row"].get("baseVcardHash").is_none());
        assert_eq!(plan["memberState"], "synced");

        let conflict = json!({
            "collection": "https://dav/b1", "status": "conflict",
            "origin": false, "conflictRevision": "e9", "handle": "c1.vcf",
            "exists": true, "existingVcard": "OLD", "otherMemberships": 0,
            "body": "NEW", "baseRevision": "e2",
            "baseObjectHash": byte_hash("BASE"),
        });
        let plan = upsert_plan(&conflict).unwrap();
        assert_eq!(plan["row"]["vcard"], "NEW");
        assert_eq!(plan["row"]["stale"], false);
        assert_eq!(plan["row"]["dirty"], true);
        assert_eq!(plan["row"]["conflictRevision"], "e9");
        assert_eq!(plan["row"]["baseVcardHash"], byte_hash("BASE"));
    }

    /// A phone-won change stages the server push (base captured once);
    /// the axis always applies, clearing the revision when the
    /// placement carries no base.
    #[test]
    fn phone_upsert_plan_stages_phone_wins() {
        let phone_edit = json!({
            "collection": "phone:https://dav/b1", "status": "dirty",
            "exists": true, "existingVcard": "OLD", "existingDirty": false,
            "objectPresent": true, "body": "NEW",
            "basePresent": true, "baseRevision": "7",
            "baseObjectHash": byte_hash("NEW"),
        });
        let plan = phone_upsert_plan(&phone_edit).unwrap();
        assert_eq!(plan["action"], "update");
        assert_eq!(plan["row"]["vcard"], "NEW");
        assert_eq!(plan["row"]["captureBase"], true);
        assert_eq!(plan["axis"]["phoneRevision"], "7");
        assert_eq!(plan["axis"]["phoneBaseHash"], byte_hash("NEW"));
        assert_eq!(plan["axis"]["phoneStale"], false);

        let created = json!({
            "collection": "phone:https://dav/b1", "status": "created",
            "exists": false, "existingDirty": false,
            "objectPresent": true, "body": "NEW", "basePresent": false,
        });
        let plan = phone_upsert_plan(&created).unwrap();
        assert_eq!(plan["action"], "insert");
        assert_eq!(plan["axis"]["phoneRevision"], Value::Null);

        let unchanged = json!({
            "collection": "phone:https://dav/b1", "status": "clean",
            "exists": true, "existingVcard": "SAME", "existingDirty": false,
            "objectPresent": true, "body": "SAME",
            "basePresent": true, "baseRevision": "7",
        });
        assert_eq!(phone_upsert_plan(&unchanged).unwrap()["action"], "axisOnly");

        let tombstone = json!({ "status": "tombstone" });
        assert_eq!(phone_upsert_plan(&tombstone).unwrap()["action"], "skip");
    }

    /// A phone drop patches the membership away while other books hold
    /// the card on an account-level backend, and deletes the card
    /// otherwise.
    #[test]
    fn phone_drop_plan_splits_membership_and_delete() {
        let shared = json!({
            "collection": "google://a@b/g1", "deleted": false,
            "otherMemberships": 2,
        });
        assert_eq!(
            phone_drop_plan(&shared).unwrap()["action"],
            "removeMembership"
        );

        let last = json!({
            "collection": "google://a@b/g1", "deleted": false,
            "otherMemberships": 0,
        });
        assert_eq!(phone_drop_plan(&last).unwrap()["action"], "deleteCard");

        let carddav = json!({
            "collection": "https://dav/b1", "deleted": false,
            "otherMemberships": 2,
        });
        assert_eq!(phone_drop_plan(&carddav).unwrap()["action"], "deleteCard");
    }
}
