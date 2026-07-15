//! JNI entry points called from the Cardamum `:client` module.
//!
//! Every method captures the FFI [`EnvUnowned`], upgrades it to a usable
//! [`Env`] inside [`EnvUnowned::with_env`] (which also guards against
//! unwinding across the JNI boundary), and resolves the outcome with
//! [`LogErrorAndDefault`] so an unexpected JNI failure logs and returns a
//! null string rather than throwing. Every reply is JSON; failures are
//! `{"error": ".."}`.
//!
//! The exported symbols are grouped by domain into per-domain
//! submodules (discovery, oauth, card, offline, project); this file
//! keeps only the marshaling helpers shared across more than one of
//! them.

mod card;
mod discovery;
mod oauth;
mod offline;
mod project;

use jni::{Env, objects::JString};
use serde_json::{Value, from_str, to_string};
use url::Url;

use crate::types::BridgeError;

/// Reads a Java string, defaulting to empty on any conversion error.
pub(crate) fn read_string(env: &Env, value: &JString) -> String {
    value.try_to_string(env).unwrap_or_default()
}

/// Reads and parses a store verb's JSON facts; the error side is the
/// ready-to-return error reply.
pub(crate) fn parse_facts(env: &Env, facts: &JString) -> Result<Value, String> {
    let raw = read_string(env, facts);
    from_str(&raw).map_err(|err| error_json(format!("Invalid store facts: {err}")))
}

pub(crate) fn parse_url(raw: &str) -> Result<Url, String> {
    Url::parse(raw).map_err(|err| format!("Invalid URL `{raw}`: {err}"))
}

/// Parses a JSON string array of book ids (empty or null means none).
pub(crate) fn parse_books(raw: &str) -> Result<Vec<String>, String> {
    if raw.is_empty() {
        return Ok(Vec::new());
    }
    from_str(raw).map_err(|err| format!("Invalid book id list `{raw}`: {err}"))
}

/// Parses a JSON string array of vCards.
pub(crate) fn parse_strings(raw: &str) -> Result<Vec<String>, String> {
    from_str(raw).map_err(|err| format!("Invalid vCard list: {err}"))
}

/// Parses a JSON array of `{"ref", "vcard"}` pairs.
pub(crate) fn parse_ref_cards(raw: &str) -> Result<Vec<(String, String)>, String> {
    let value: Value = from_str(raw).map_err(|err| format!("Invalid card list: {err}"))?;
    let Some(entries) = value.as_array() else {
        return Err("Invalid card list: not an array".into());
    };

    let mut cards = Vec::with_capacity(entries.len());
    for entry in entries {
        let reference = entry
            .get("ref")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let vcard = entry
            .get("vcard")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        cards.push((reference, vcard));
    }
    Ok(cards)
}

/// Serializes a failure as the error reply, `{"error": message}` plus
/// a `status` field when the failure was an HTTP round (the type is
/// the wire shape; plain strings convert to status-less errors).
pub(crate) fn error_json(error: impl Into<BridgeError>) -> String {
    to_string(&error.into())
        .unwrap_or_else(|_| r#"{"error": "Unserializable bridge failure"}"#.to_string())
}
