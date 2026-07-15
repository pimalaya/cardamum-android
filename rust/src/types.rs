//! Payloads threaded across the JNI boundary.

use core::fmt;

use serde::{Deserialize, Serialize};

/// One failed bridge operation, serialized as the error reply the
/// Java client parses: the message every layer displays, plus the
/// HTTP status when the failure was an HTTP round. Java branches on
/// the status (412 retries unguarded, 404 converges a removal, 401
/// refreshes the token), so it crosses as its own field instead of
/// riding the message prose.
#[derive(Debug, Serialize)]
pub struct BridgeError {
    /// Human-readable failure message.
    #[serde(rename = "error")]
    pub message: String,
    /// Status of the failed HTTP round, absent on non-HTTP failures.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<u16>,
}

impl fmt::Display for BridgeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.message.fmt(f)
    }
}

impl From<String> for BridgeError {
    fn from(message: String) -> Self {
        Self {
            message,
            status: None,
        }
    }
}

impl From<&str> for BridgeError {
    fn from(message: &str) -> Self {
        message.to_string().into()
    }
}

/// Borrowed account credentials threaded through one connection. An
/// empty login means the password field carries an OAuth 2.0 access
/// token, authenticated as Bearer instead of Basic.
pub struct Credentials<'a> {
    pub login: &'a str,
    pub password: &'a str,
}

/// One CardDAV addressbook surfaced to the Java client.
#[derive(Serialize)]
pub struct Addressbook {
    /// Last non-empty path segment of the collection URL.
    pub id: String,
    /// Human-readable name (display name, falling back to the id).
    pub name: String,
    /// Absolute collection URL, the target of every card operation.
    pub url: String,
    /// Free-form description, when the server exposes one.
    pub description: Option<String>,
    /// Display colour (`#RRGGBB`), when the server exposes one.
    pub color: Option<String>,
}

/// Incremental changes of one collection since a sync cursor: the
/// changed cards (spine-only or full, per backend), the removed
/// resource names, the next cursor to checkpoint, and whether the
/// round listed the complete member set.
#[derive(Serialize)]
pub struct CardDelta {
    /// Cards created or updated since the cursor.
    pub changed: Vec<Card>,
    /// Resource names removed since the cursor.
    pub vanished: Vec<String>,
    /// The next cursor, when the backend issued one.
    pub token: Option<String>,
    /// True when the round listed the complete member set (an initial
    /// round, or an expired cursor re-run as one).
    pub complete: bool,
}

/// One change of a batched push round, handed down by the Java driver.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushChange {
    /// Opaque correlation key the outcome echoes back (the engine
    /// handle in practice).
    #[serde(rename = "ref")]
    pub reference: String,
    /// One of create, update, books (membership patch) or destroy.
    pub op: String,
    /// The card id addressed by update, books and destroy.
    pub id: Option<String>,
    /// The full vCard of create and update.
    pub vcard: Option<String>,
    /// The state last synced with the server, trimming update patches
    /// to the fields the edit changed.
    pub base_vcard: Option<String>,
    /// Book ids a books op adds the card to.
    #[serde(default)]
    pub add: Vec<String>,
    /// Book ids a books op removes the card from.
    #[serde(default)]
    pub remove: Vec<String>,
}

/// One change's outcome of a batched push round.
#[derive(Default, Serialize)]
pub struct PushOutcome {
    /// The correlation key of the change this outcome answers.
    #[serde(rename = "ref")]
    pub reference: String,
    /// Whether the server took the change; a rejected change is
    /// reconciled by the next sync instead of failing the round.
    pub accepted: bool,
    /// The server-assigned id of an accepted create.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// The revision of the accepted write, when the backend has one
    /// (the Graph changeKey; JMAP revisions only exist fetch-side).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub etag: Option<String>,
    /// What the server objected, on a rejected change (driver-side
    /// logging only).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// One vCard surfaced to the Java client.
#[derive(Serialize)]
pub struct Card {
    /// Display identifier (resource name with any `.vcf` stripped).
    pub id: String,
    /// Resource name exactly as the server returned it, the addressing
    /// key of updates and deletes (servers need not suffix `.vcf`).
    pub uri: String,
    /// Entity tag guarding concurrent updates, when the server sent one.
    pub etag: Option<String>,
    /// Raw vCard text.
    pub vcard: String,
    /// Ids of the addressbooks the card is a member of, on the
    /// backends whose cards are account-level with m:n memberships
    /// (JMAP AddressBook ids, Google contact group ids). Empty on the
    /// backends whose cards live in the one collection they were
    /// listed from (CardDAV, Graph).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub books: Vec<String>,
}
