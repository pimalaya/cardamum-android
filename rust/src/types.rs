//! Payloads threaded across the JNI boundary.

use serde::Serialize;

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
}
